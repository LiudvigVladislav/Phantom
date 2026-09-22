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
import android.util.Log
import com.benasher44.uuid.uuid4
import kotlinx.coroutines.CancellationException
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
import phantom.core.transport.CallDisabledReason
import phantom.core.transport.TransportCapabilities
import phantom.core.transport.TurnCredentialsResponse

@Serializable
private data class IceCandidateJson(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidate: String,
)

internal data class CallIceServerSpec(
    val uris: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

internal fun buildCallIceServerSpecs(
    turnCredentials: TurnCredentialsResponse?,
): List<CallIceServerSpec> {
    val specs = mutableListOf(
        CallIceServerSpec(uris = listOf("stun:turn.phntm.pro:3478")),
    )
    val turnUris = turnCredentials?.uris
        ?.filter { uri -> uri.startsWith("turn:") || uri.startsWith("turns:") }
        ?.distinct()
        .orEmpty()
    if (turnCredentials != null && turnUris.isNotEmpty()) {
        specs += CallIceServerSpec(
            uris = turnUris,
            username = turnCredentials.username,
            credential = turnCredentials.credential,
        )
    }
    return specs
}

class CallManager(
    private val context: Context,
    private val messagingService: MessagingService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * PR-C1 (2026-05-17): defence-in-depth guard for outbound call start.
     *
     * Supplying a lambda rather than a [kotlinx.coroutines.flow.StateFlow]
     * avoids a Flow dependency in [CallManager]'s constructor. The lambda
     * reads [AppContainer._transportCapabilities].value on every [startCall]
     * invocation — always the current snapshot, never stale.
     *
     * Default returns a fully-permissive snapshot so existing tests and
     * tooling that construct [CallManager] directly are unaffected.
     */
    private val transportCapabilitiesProvider: () -> TransportCapabilities = {
        TransportCapabilities(
            canSendText = true,
            canSendVoice = true,
            canStartCalls = true,
            realtimeStable = true,
            callDisabledReason = null,
            restModeLabel = null,
        )
    },
    /** Fresh per-call credentials. They are kept in memory only. */
    private val turnCredentialsProvider: suspend () -> TurnCredentialsResponse? = { null },
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
        // PR-C1 — defence-in-depth call-layer guard.
        // The UI button is gated by ChatScreen; this second layer catches
        // programmatic / retry / deep-link paths that bypass ChatScreen.
        // No state mutation occurs on early return — we never enter CALLING,
        // never touch AudioManager, never construct a PeerConnection.
        if (!checkCallCapability(transportCapabilitiesProvider())) return  // package-level helper
        val turnCredentials = fetchTurnCredentials()
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

        createPeerConnection(toPubKeyHex, turnCredentials)

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

        val turnCredentials = fetchTurnCredentials()
        createPeerConnection(remoteFrom, turnCredentials)

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

    private suspend fun fetchTurnCredentials(): TurnCredentialsResponse? = try {
        turnCredentialsProvider().also { credentials ->
            Log.i(
                LOG_TAG,
                "turn_credentials=${if (credentials == null) "unavailable" else "available"} " +
                    "uri_count=${credentials?.uris?.size ?: 0}",
            )
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (error: Throwable) {
        Log.w(LOG_TAG, "turn_credentials=unavailable reason=${error::class.simpleName}")
        null
    }

    private fun createPeerConnection(
        remotePubKeyHex: String,
        turnCredentials: TurnCredentialsResponse?,
    ) {
        val iceServers = buildCallIceServerSpecs(turnCredentials).map { spec ->
            PeerConnection.IceServer.builder(spec.uris).apply {
                if (spec.username != null && spec.credential != null) {
                    setUsername(spec.username)
                    setPassword(spec.credential)
                }
            }.createIceServer()
        }
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
                    Log.i(LOG_TAG, "peer_connection_state=$state")
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            _activeCall.value = _activeCall.value?.copy(state = CallState.IN_CALL)
                            scope.launch {
                                delay(1_000)
                                logSelectedCandidatePath()
                            }
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED,
                        PeerConnection.PeerConnectionState.FAILED ->
                            cleanupCall(CallState.ENDED)
                        else -> {}
                    }
                }

                // Unused callbacks — required by interface
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.i(LOG_TAG, "ice_connection_state=$state")
                }
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.i(LOG_TAG, "ice_gathering_state=$state")
                }
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    Log.i(LOG_TAG, "remote_track_added kind=${receiver?.track()?.kind() ?: "unknown"}")
                }
            }
        )
    }

    private fun logSelectedCandidatePath() {
        val connection = peerConnection ?: return
        connection.getStats { report ->
            val stats = report.statsMap
            val pair = stats.values.firstOrNull { stat ->
                if (stat.type != "candidate-pair") return@firstOrNull false
                val state = stat.members["state"] as? String
                val nominated = stat.members["nominated"] as? Boolean ?: false
                val selected = stat.members["selected"] as? Boolean ?: false
                state == "succeeded" && (nominated || selected)
            }
            val localId = pair?.members?.get("localCandidateId") as? String
            val remoteId = pair?.members?.get("remoteCandidateId") as? String
            val local = localId?.let(stats::get)
            val remote = remoteId?.let(stats::get)
            val localType = local?.members?.get("candidateType") as? String ?: "unknown"
            val remoteType = remote?.members?.get("candidateType") as? String ?: "unknown"
            val protocol = local?.members?.get("protocol") as? String ?: "unknown"
            Log.i(
                LOG_TAG,
                "selected_ice_path local_type=$localType remote_type=$remoteType " +
                    "protocol=$protocol relayed=${localType == "relay" || remoteType == "relay"}",
            )
        }
    }

    private companion object {
        const val LOG_TAG = "PhantomCall"
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

// ── Package-level guard helper ────────────────────────────────────────────────
//
// Kept outside [CallManager] so it can be unit-tested without constructing the
// WebRTC stack (PeerConnectionFactory / IceServer require native initialisation
// which is unavailable in JVM unit tests). [CallManager.startCall] delegates
// to this function.
//
// Log format (locked by architect, PR-C1):
//   CALL_TX blocked_<reason> reason=<reason> mode=<restModeLabel|null> source=call_manager
// Tag: PhantomTransport (consistent with UI-level CALL_CAPABILITY lines).

/**
 * Returns `true` if [caps] permits a call to start.
 * Returns `false` and logs a single `CALL_TX blocked_*` line if not.
 *
 * Visible to `androidUnitTest` (same module) and used by [CallManager.startCall].
 */
internal fun checkCallCapability(caps: TransportCapabilities): Boolean {
    if (caps.canStartCalls) return true
    val reason = when (caps.callDisabledReason) {
        CallDisabledReason.LIMITED_REALTIME -> "limited_realtime"
        CallDisabledReason.TOR_TRANSPORT    -> "tor_transport"
        CallDisabledReason.REALITY_UNPROBED -> "reality_unprobed"
        CallDisabledReason.NO_TRANSPORT     -> "no_transport"
        null                                -> "unknown"
    }
    android.util.Log.i(
        "PhantomTransport",
        "CALL_TX blocked_${reason} reason=${reason} mode=${caps.restModeLabel} source=call_manager",
    )
    return false
}
