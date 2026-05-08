// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.calls

// Architecture note (ADR-025):
// Call signalling payloads ride the same Double Ratchet + Sealed Sender pipeline as chat messages:
//   Incoming  — DefaultMessagingService decrypts → calls onCallMessage → CallManager.handle*
//   Outgoing  — CallManager calls MessagingService.sendCallSignal() which encrypts via DR and
//               seals the sender identity. The relay sees only an opaque blob; it cannot read
//               SDP / ICE candidates or correlate caller identity.
//   Ring UI   — The caller's identity is recovered from the decrypted payload (fromPubKeyHex
//               passed through onCallMessage), not from the relay `from` field.

import android.content.Context
import android.media.AudioManager
import com.benasher44.uuid.uuid4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import phantom.core.messaging.MessagePayload
import phantom.core.messaging.MessagePayload.Companion.TYPE_CALL_ANSWER
import phantom.core.messaging.MessagePayload.Companion.TYPE_CALL_HANGUP
import phantom.core.messaging.MessagePayload.Companion.TYPE_CALL_ICE
import phantom.core.messaging.MessagePayload.Companion.TYPE_CALL_OFFER
import phantom.core.messaging.MessagePayload.Companion.TYPE_CALL_REJECT
import phantom.core.messaging.MessagingService

@Serializable
private data class IceCandidateJson(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidate: String,
)

class CallManager(
    private val context: Context,
    private val messagingService: MessagingService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Cached once; getSystemService is cheap but calling it on every audio operation is noisy.
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val _activeCall = MutableStateFlow<ActiveCall?>(null)
    val activeCall: StateFlow<ActiveCall?> = _activeCall.asStateFlow()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    private var pendingRemoteSdp: String? = null
    private var pendingRemoteFrom: String? = null
    private var ringTimeoutJob: Job? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    )

    fun initialize() {
        val initOptions = PeerConnectionFactory.InitializationOptions
            .builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()
    }

    // ── Outgoing call ─────────────────────────────────────────────────────────

    suspend fun startCall(toPubKeyHex: String, toUsername: String) {
        if (_activeCall.value != null) return
        val callId = uuid4().toString()
        pendingIceCandidates.clear()
        _activeCall.value = ActiveCall(callId, toPubKeyHex, toUsername, CallState.CALLING)

        // MODE_IN_COMMUNICATION is required for WebRTC microphone routing and for
        // isSpeakerphoneOn to take effect. Must be set before createAudioSource.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false

        ringTimeoutJob = scope.launch {
            delay(60_000)
            cleanupCall(CallState.ENDED)
        }

        createPeerConnection(toPubKeyHex)

        val audioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory?.createAudioTrack("audio0", audioSource)
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("stream0")) }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        scope.launch {
                            sendSignal(
                                to = toPubKeyHex,
                                payload = MessagePayload(
                                    type = TYPE_CALL_OFFER,
                                    callId = callId,
                                    sdp = sdp.description,
                                ),
                            )
                        }
                    }
                    override fun onSetFailure(p0: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(p0: String?) {
                _activeCall.value = _activeCall.value?.copy(state = CallState.ENDED)
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    // ── Incoming call handling ────────────────────────────────────────────────

    suspend fun handleOffer(
        fromPubKeyHex: String,
        fromUsername: String,
        callId: String,
        sdp: String,
    ) {
        if (_activeCall.value != null) {
            // Already in a call — reject immediately.
            sendSignal(fromPubKeyHex, MessagePayload(type = TYPE_CALL_REJECT, callId = callId))
            return
        }
        pendingIceCandidates.clear()
        pendingRemoteSdp = sdp
        pendingRemoteFrom = fromPubKeyHex
        _activeCall.value = ActiveCall(callId, fromPubKeyHex, fromUsername, CallState.RINGING)
    }

    suspend fun answerCall() {
        val call = _activeCall.value ?: return
        val remoteSdp = pendingRemoteSdp ?: return
        val remoteFrom = pendingRemoteFrom ?: return

        // MODE_IN_COMMUNICATION is required for WebRTC microphone routing and for
        // isSpeakerphoneOn to take effect. Must be set before createAudioSource.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false

        createPeerConnection(remoteFrom)

        val audioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory?.createAudioTrack("audio0", audioSource)
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("stream0")) }

        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, remoteSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                // Flush ICE candidates that arrived before remote description was set.
                pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
                pendingIceCandidates.clear()

                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                scope.launch {
                                    sendSignal(
                                        to = remoteFrom,
                                        payload = MessagePayload(
                                            type = TYPE_CALL_ANSWER,
                                            callId = call.callId,
                                            sdp = sdp.description,
                                        ),
                                    )
                                    _activeCall.value = call.copy(
                                        state = CallState.IN_CALL,
                                        startedAt = System.currentTimeMillis(),
                                    )
                                }
                            }
                            override fun onSetFailure(p0: String?) {}
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, sdp)
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetSuccess() {}
                    override fun onSetFailure(p0: String?) {}
                }, constraints)
            }
            override fun onSetFailure(p0: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remoteDesc)
    }

    suspend fun handleAnswer(sdp: String) {
        val call = _activeCall.value ?: return
        if (peerConnection == null) {
            cleanupCall(CallState.ENDED)
            return
        }
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
                pendingIceCandidates.clear()
                _activeCall.value = call.copy(
                    state = CallState.IN_CALL,
                    startedAt = System.currentTimeMillis(),
                )
            }
            override fun onSetFailure(p0: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remoteDesc)
    }

    suspend fun handleIce(iceCandidateJsonStr: String) {
        val ice = runCatching {
            json.decodeFromString<IceCandidateJson>(iceCandidateJsonStr)
        }.getOrNull() ?: return
        val candidate = IceCandidate(ice.sdpMid, ice.sdpMLineIndex, ice.candidate)
        if (peerConnection?.remoteDescription != null) {
            peerConnection?.addIceCandidate(candidate)
        } else {
            pendingIceCandidates.add(candidate)
        }
    }

    // ── Call control ──────────────────────────────────────────────────────────

    suspend fun hangup() {
        val call = _activeCall.value ?: return
        sendSignal(
            to = call.remotePubKeyHex,
            payload = MessagePayload(type = TYPE_CALL_HANGUP, callId = call.callId),
        )
        cleanupCall(CallState.ENDED)
    }

    suspend fun rejectCall() {
        val call = _activeCall.value ?: return
        sendSignal(
            to = call.remotePubKeyHex,
            payload = MessagePayload(type = TYPE_CALL_REJECT, callId = call.callId),
        )
        cleanupCall(CallState.REJECTED)
    }

    fun handleRemoteHangup() { cleanupCall(CallState.ENDED) }
    fun handleRemoteReject()  { cleanupCall(CallState.REJECTED) }

    fun toggleMute() {
        val wasEnabled = localAudioTrack?.enabled() ?: return
        localAudioTrack?.setEnabled(!wasEnabled)
        _activeCall.value = _activeCall.value?.copy(isMuted = wasEnabled)
    }

    fun toggleSpeaker() {
        audioManager.isSpeakerphoneOn = !audioManager.isSpeakerphoneOn
        _activeCall.value = _activeCall.value?.copy(isSpeakerOn = audioManager.isSpeakerphoneOn)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun createPeerConnection(remotePubKeyHex: String) {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peerConnection = peerConnectionFactory?.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    scope.launch {
                        val iceJson = json.encodeToString(
                            IceCandidateJson(
                                sdpMid = candidate.sdpMid ?: "",
                                sdpMLineIndex = candidate.sdpMLineIndex,
                                candidate = candidate.sdp,
                            )
                        )
                        sendSignal(
                            to = remotePubKeyHex,
                            payload = MessagePayload(
                                type = TYPE_CALL_ICE,
                                callId = _activeCall.value?.callId ?: "",
                                iceCandidateJson = iceJson,
                            ),
                        )
                    }
                }

                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED ->
                            _activeCall.value = _activeCall.value?.copy(state = CallState.IN_CALL)
                        PeerConnection.PeerConnectionState.DISCONNECTED,
                        PeerConnection.PeerConnectionState.FAILED ->
                            cleanupCall(CallState.ENDED)
                        else -> {}
                    }
                }

                // Unused callbacks — required by interface
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            }
        )
    }

    private suspend fun sendSignal(to: String, payload: MessagePayload) {
        messagingService.sendCallSignal(
            recipientPublicKeyHex = to,
            payload = payload,
        ).getOrElse { }
    }

    private fun cleanupCall(endState: CallState) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        peerConnection?.dispose()
        peerConnection = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        pendingIceCandidates.clear()
        pendingRemoteSdp = null
        pendingRemoteFrom = null
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
        _activeCall.value = _activeCall.value?.copy(state = endState)
        scope.launch {
            delay(2_000)
            _activeCall.value = null
        }
    }

    fun dispose() {
        cleanupCall(CallState.ENDED)
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        scope.cancel()
    }
}
