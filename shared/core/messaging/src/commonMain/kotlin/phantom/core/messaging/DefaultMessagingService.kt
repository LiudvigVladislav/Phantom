// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.benasher44.uuid.uuid4
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import phantom.core.crypto.DoubleRatchet
import phantom.core.crypto.DhKeyPair
import phantom.core.crypto.MessagePadding
import phantom.core.crypto.SealedSender
import phantom.core.identity.IdentityRecord
import phantom.core.identity.IdentitySigningKeyPair
import phantom.core.storage.ConversationEntity
import phantom.core.storage.ConversationRepository
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageRepository
import phantom.core.storage.MessageStatus
import phantom.core.storage.ProcessedEnvelopeRepository
import phantom.core.storage.ReactionRepository
import phantom.core.storage.TrustTier
import phantom.core.storage.VoiceChunkRepository
import phantom.core.storage.VoiceV2DownloadRepository
import phantom.core.transport.BundleFetchException
import kotlinx.coroutines.launch
import phantom.core.transport.PreKeyApi
import phantom.core.transport.RelayMessage
import phantom.core.transport.RelayTransport

class DefaultMessagingService(
    private val identity: IdentityRecord,
    private val localKeyPair: DhKeyPair,
    private val ratchet: DoubleRatchet,
    private val sessionManager: SessionManager,
    private val transport: RelayTransport,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    /**
     * Idempotent receive ledger (PR-H2b, 2026-05-13). Every envelope id
     * fed to `ratchet.decrypt` is recorded here regardless of payload
     * type, so a relay-side redelivery (after a lost ack-deliver frame)
     * cannot trigger a second decrypt attempt that would MAC-fail on
     * the now-advanced chain key.
     *
     * Nullable + default null to keep call-site compatibility for
     * existing tests that construct DMS without storage. Production
     * always wires the real SQLDelight repository through AppContainer.
     */
    private val processedEnvelopeRepository: ProcessedEnvelopeRepository? = null,
    private val scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val reactionRepository: ReactionRepository? = null,
    /**
     * REST client for the relay's prekey endpoints. Used by the send
     * path to fetch a peer's [phantom.core.transport.PreKeyBundle] when
     * no session exists yet for a conversation. PR C commit 11.
     */
    private val preKeyApi: PreKeyApi,
    /**
     * Resolves the local user's Ed25519 signing keypair on demand.
     * The send path calls this on the FIRST message of a fresh session
     * to attach `senderSigningPublicKeyHex` to the [WireFrame] so the
     * recipient can cache the key for verifying future SPK rotations.
     *
     * Returns `null` when the identity hasn't been backfilled yet
     * (Alpha 1 → Alpha 2 migration not run). The caller surfaces that
     * as a hard send failure with a clear error — by the time DMS is
     * receiving send calls, migration must have completed.
     */
    private val signingKeyProvider: suspend () -> IdentitySigningKeyPair?,
    /**
     * PR-D2a (2026-05-17): guard for voice send on Limited realtime
     * transports. Returns `true` when voice may be sent, `false` when
     * the current transport state cannot carry an audio_chunk envelope
     * within the contract this PR is designed around (REST short-poll
     * fallback caps `max_send_body=4096` and is not realtime, so voice
     * recorded in `Limited realtime` would silently fail in the relay
     * or be rejected by the receiver assembly window).
     *
     * The lambda is read on every `sendAudio` entry — implementers
     * decide how fresh the answer must be. The Android DI wires this
     * to `hybridTransport.stateMachine.state.value == RestMode.WsActive`.
     *
     * Default `{ true }` keeps existing tests + non-Android callers
     * working unchanged. The real chunked-voice path lands in PR-D2b;
     * this PR is a guard, not a delivery path.
     */
    private val canSendVoice: () -> Boolean = { true },
    /**
     * Durable receive-side reassembly buffer for chunked voice
     * (PR-D2b.1, 2026-05-17). When present, the 1:1 voice receive
     * path saves each incoming chunk to SQLDelight BEFORE sending
     * ack-deliver, so a process death between chunk save and
     * assembly does not lose the partial voice — the next
     * `startReceiving` finalizer scans for `findVoicesReadyToAssemble`
     * and finishes the work. Group voice (`payload.groupId != null`)
     * intentionally stays on the in-memory path because the durable
     * row schema does not carry `groupId` — group durability is queued
     * for a later PR per the D2b architect review (2026-05-17).
     *
     * Nullable + default `null` to keep call-site compatibility for
     * existing tests that construct DMS without storage. Production
     * always wires the real SQLDelight repository through AppContainer.
     */
    private val voiceChunkRepository: VoiceChunkRepository? = null,
    /**
     * Composite voice-v2 upload helper (PR-M1w). When non-null, [sendAudio]
     * switches from the legacy audio_chunk ratchet path to the encrypted-media
     * upload path: encrypt → chunk → upload → send one manifest envelope.
     * When null, [sendAudio] falls back to the legacy audio_chunk path so
     * existing tests that construct DMS without this dependency still compile.
     */
    private val voiceV2Sender: VoiceV2Sender? = null,
    /**
     * Durable download-task store for voice_v2 manifests (PR-M1w, Q4).
     * Nullable + default null for test call-site compatibility. Production
     * AppContainer MUST wire a non-null instance. A null value causes the
     * voice_v2 receive handler to refuse ACKing the manifest (better to
     * retry forever than silently drop a voice message).
     */
    private val voiceV2DownloadRepository: VoiceV2DownloadRepository? = null,
    /**
     * Download orchestrator for voice_v2 chunks (PR-M1w, Commit 4).
     * Nullable; when non-null, [runVoiceV2DownloadTask] delegates to it.
     */
    private val voiceV2DownloadOrchestrator: VoiceV2DownloadOrchestrator? = null,
    /**
     * In-memory live chunk-progress bus (PR-M2d.1b). Sender callback hooks
     * into [VoiceV2Sender] keyed by local message id; receiver callback hooks
     * into [VoiceV2DownloadOrchestrator] keyed by mediaId (which is the row PK
     * on the receive side). Cleared on completion/failure. Exposed to the UI
     * via [mediaProgressBus].
     */
    val mediaProgressBus: MediaProgressBus = MediaProgressBus(),
) : MessagingService {

    private val _bootstrapReady = MutableStateFlow(false)
    override val bootstrapReady: StateFlow<Boolean> = _bootstrapReady.asStateFlow()

    /** Called by the platform container after the initial prekey-bundle publish attempt. */
    fun markBootstrapReady() { _bootstrapReady.value = true }

    // PR-M1w: single voice in-flight per conversation. Guarded by mutexFor(conversationId).
    private val voiceSendInProgress = mutableSetOf<String>()

    init {
        // Q4: loud WARN if production wires this incorrectly.
        if (voiceV2DownloadRepository == null) {
            messagingLog(
                MessagingLogLevel.WARN,
                "MEDIA_RX no_download_repo running_in_degraded_mode — voice_v2 manifests will NOT be ACKed",
            )
        }
    }

    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val incomingMessages: Flow<IncomingMessage> = _incomingMessages.asSharedFlow()

    private val startReceivingLock = Mutex()
    private var receiving = false   // guarded by startReceivingLock

    // Guard against duplicate in-flight delivery: if startReceiving() is somehow
    // called twice a SharedFlow delivers to both collectors simultaneously. The
    // DB-level INSERT OR IGNORE is the last line of defence, but it fires after
    // ratchet.decrypt which irreversibly advances chain state. This set prevents
    // two coroutines from entering handleDeliver for the same messageId at all.
    private val processingLock = Mutex()
    private val activeProcessing = mutableSetOf<String>()

    // Per-conversation Mutex protects every load → encrypt/decrypt → save sequence
    // on the Double Ratchet state. Without this lock two concurrent sendMessage()
    // calls (e.g. user typing + automatic profile-card sync) could both load the
    // same starting RatchetState, both derive the same chain key, and both ship
    // a ciphertext encrypted with that key. The receiver advances its chain on
    // the first message and then fails MAC verification on the second — the
    // exact symptom reported in the 2026-04-25 QA pass.
    private val sessionMutexesLock = Mutex()
    private val sessionMutexes = mutableMapOf<String, Mutex>()

    private suspend fun mutexFor(conversationId: String): Mutex =
        sessionMutexesLock.withLock {
            sessionMutexes.getOrPut(conversationId) { Mutex() }
        }

    override suspend fun removeConversationMutex(conversationId: String) {
        sessionMutexesLock.withLock { sessionMutexes.remove(conversationId) }
    }

    companion object {
        const val MAX_AUDIO_BYTES = 10 * 1024 * 1024   // 10 MB hard cap on raw audio bytes
        // PR-D2b.1 (2026-05-17): shrunk from 8 KB → 3 KB so envelopes pass
        // the REST short-poll body cap. Background: D1c+D1d turned REST
        // into a first-class transport for text on Tele2 LTE; D2a closed
        // voice on that path as a temporary guard after Test #53.1 showed
        // a real envelope `bodyBytes=7608 > max=4096` on the migrate path
        // for the old 8 KB chunk. D2b is doing voice over REST properly,
        // so every chunk envelope must stay under `max_send_body_bytes`
        // (server-side cap = 4096; client-side target ≈ 2.5–3 KB ciphertext
        // for headroom). 3 KB raw input + Ratchet + JSON wrap + Base64 +
        // padding empirically lands around 3.5–4 KB on the wire. If real
        // device logs show `chunk_prepare` envelopeBytes > 4096 even at
        // 3 KB, shrink further to 2 KB — the `VOICE_TX chunk_prepare`
        // log line carries the exact `envelopeBytes` so this is empirical,
        // not theoretical. Group voice (`DefaultGroupMessagingService`)
        // references this same constant, so group voice gets the smaller
        // chunks too — accepted side effect documented in the PR body.
        const val AUDIO_CHUNK_BYTES = 3 * 1024          // 3 KB plaintext per chunk; tuned for REST fallback envelope cap

        // PR-G1 (2026-05-12): hard cap for the prekey-bundle HTTP fetch on
        // the send-bootstrap path. Without this, a slow / dead
        // /prekeys/bundle endpoint (server overload, network jitter,
        // identity-keyed routing race after reconnect, etc.) blocks the
        // per-conversation mutex indefinitely — Test #27 captured 64 sec
        // between chat open and first envelope sent. 8 s is generous:
        // healthy /prekeys/bundle returns in < 200 ms; if it has not
        // returned by 8 s the relay path is degraded and the right
        // answer is to fail fast — sendMessage catches the resulting
        // PeerBundleMissingException and lands the message in
        // WAITING_FOR_RECIPIENT_BUNDLE so retryWaitingMessages() can
        // re-try on the next reconnect.
        const val PREKEY_BUNDLE_FETCH_TIMEOUT_MS: Long = 8_000L

        // PR-D2b.1 (2026-05-17): TTL for partial voice reassembly state in
        // the durable `voice_chunks` table. 24 h is intentionally generous
        // so a phone that loses connectivity overnight on Tele2 can still
        // finish receiving the voice in the morning (the relay envelope
        // TTL is 7 d; the bottleneck is the local partial buffer, not
        // the network). The sweep runs (a) opportunistically on every
        // inbound chunk so the table cannot grow unbounded, and (b) at
        // `startReceiving` so a long-idle restart does not start by
        // carrying state from before the previous Doze window. Each
        // sweep emits a `VOICE_RX partial_expired` log line per voice
        // dropped so the eviction is observable.
        const val VOICE_CHUNK_TTL_MS: Long = 24L * 60L * 60L * 1_000L
    }

    // Buffer for reassembling incoming audio chunks. Key = chunkId.
    // Each entry holds the received slices plus the timestamp of the last
    // chunk arrival so the stale-entry sweep can drop incomplete reassemblies
    // older than 5 minutes (prevents a leak when a sender goes offline mid-send).
    private data class ChunkBuffer(
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        var lastUpdatedMs: Long = 0L,
    )
    private val reassemblyMutex = Mutex()
    private val audioReassemblyBuffer = mutableMapOf<String, ChunkBuffer>()

    /**
     * Loads (or bootstraps) the ratchet state for the conversation,
     * encrypts [plaintext] with the Double Ratchet, persists the
     * advanced state, and returns a wire-ready [WireFrame] — all under
     * the per-conversation mutex so concurrent callers cannot derive
     * the same chain key.
     *
     * On the FIRST message of a fresh conversation the session does
     * not yet exist. We:
     *   1. Fetch the peer's PreKeyBundle from the relay
     *   2. Run [SessionManager.initiatorBootstrap] (verifies SPK signature,
     *      runs X3DH 4-DH, persists fresh RatchetState, asserts F15)
     *   3. Encrypt under the freshly-bootstrapped state
     *   4. Wrap result in a [WireFrame] WITH `x3dhInit` + the local
     *      signing pubkey so the recipient can mirror the bootstrap
     *
     * On every subsequent message the session is loaded as-is and the
     * resulting WireFrame has `x3dhInit = null` and
     * `senderSigningPublicKeyHex = null` — wasted bytes once the
     * recipient cached them.
     */
    private suspend fun encryptUnderLock(
        conversationId: String,
        recipientPublicKeyHex: String,
        plaintext: ByteArray,
        // Runs inside the mutex BEFORE saveSession so the message is in the
        // DB before the ratchet state advances. On crash between the two DB
        // writes the message stays visible (QUEUED) and the session resets —
        // better than the inverse (lost message, advanced chain).
        afterEncrypt: suspend (WireFrame) -> Unit = {},
    ): WireFrame {
        // PR-G1 (2026-05-12): trace every stage of the send pipeline so we
        // can localise where a delayed first-send actually blocks. Test #27
        // showed 64 sec between ChatScreen subscribed and the first
        // `Sending envelope` log — without per-stage trace we cannot tell
        // whether the gap is in mutex acquire, prekey fetch, ratchet init,
        // or transport.send. Tag every line with `SEND_TRACE` so a
        // post-mortem grep on the device log gives the timeline directly.
        val convTag = conversationId.take(12)
        messagingLog(MessagingLogLevel.INFO, "SEND_TRACE encrypt_lock_wait conv=$convTag")
        return mutexFor(conversationId).withLock {
            messagingLog(MessagingLogLevel.INFO, "SEND_TRACE encrypt_lock_acquired conv=$convTag")
            messagingLog(MessagingLogLevel.INFO, "SEND_TRACE session_lookup conv=$convTag")
            val existingState = sessionManager.tryLoadSession(conversationId)
            if (existingState != null) {
                messagingLog(MessagingLogLevel.INFO, "SEND_TRACE session_existing conv=$convTag")
                messagingLog(MessagingLogLevel.INFO, "SEND_TRACE ratchet_encrypt_start conv=$convTag plaintextBytes=${plaintext.size}")
                val (newState, encrypted) = ratchet.encrypt(existingState, plaintext)
                messagingLog(MessagingLogLevel.INFO, "SEND_TRACE ratchet_encrypt_ok conv=$convTag")
                val wireFrame = WireFrame(encryptedMessage = encrypted)
                messagingLog(MessagingLogLevel.INFO, "SEND_TRACE after_encrypt_callback_start conv=$convTag")
                afterEncrypt(wireFrame)
                messagingLog(MessagingLogLevel.INFO, "SEND_TRACE after_encrypt_callback_ok conv=$convTag")
                messagingLog(MessagingLogLevel.INFO, "SEND_TRACE save_session_start conv=$convTag")
                sessionManager.saveSession(conversationId, newState)
                messagingLog(MessagingLogLevel.INFO, "SEND_TRACE save_session_ok conv=$convTag")
                wireFrame
            } else {
                // Bootstrap path: peer has no session here yet.
                // Fetch their bundle, run 4-DH, ship the bootstrap
                // header with the first message.
                messagingLog(MessagingLogLevel.INFO, "SEND_TRACE bootstrap_path conv=$convTag — no existing session")
                val recipientTag = recipientPublicKeyHex.take(16)
                val endpointPath =
                    "/prekeys/bundle/$recipientPublicKeyHex?requester=${identity.publicKeyHex}"
                messagingLog(
                    MessagingLogLevel.INFO,
                    "SEND_TRACE prekey_fetch_start recipient=$recipientTag… endpoint=$endpointPath",
                )
                // PR-G1: hard cap the prekey-bundle HTTP fetch. Without a
                // ceiling, a slow / dead /prekeys/bundle endpoint blocks
                // the per-conversation mutex indefinitely, which is what
                // Test #27 captured as "64 sec between chat open and first
                // envelope sent". 8 s is generous: under healthy conditions
                // /prekeys/bundle returns in < 200 ms; if it has not
                // returned by 8 s the relay is degraded and the right
                // answer is to fail fast with PeerBundleMissingException
                // (treated as 404 by sendMessage, message lands in
                // WAITING_FOR_RECIPIENT_BUNDLE, retry hook re-tries on the
                // next reconnect).
                //
                // PR-G3: distinguish the four failure modes — timeout, 404,
                // 429 (RateLimited), other 5xx (Unexpected) — in the trace,
                // and always emit elapsedMs so we can tell "fetched fast and
                // returned 404" (peer never published) apart from "fetch hung
                // 8s" (relay/network degraded). Behaviour for all four is
                // unchanged: defer to WAITING + retry on reconnect/sweep.
                val fetchStartMs = Clock.System.now().toEpochMilliseconds()
                val wireBundle = try {
                    withTimeout(PREKEY_BUNDLE_FETCH_TIMEOUT_MS) {
                        preKeyApi.fetchBundle(
                            identityPubkeyHex = recipientPublicKeyHex,
                            requesterPubkeyHex = identity.publicKeyHex,
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    val elapsed = Clock.System.now().toEpochMilliseconds() - fetchStartMs
                    messagingLog(
                        MessagingLogLevel.WARN,
                        "SEND_TRACE prekey_fetch_result=timeout recipient=$recipientTag… " +
                            "elapsedMs=$elapsed budgetMs=$PREKEY_BUNDLE_FETCH_TIMEOUT_MS " +
                            "endpoint=$endpointPath — message will WAIT for retry",
                    )
                    throw PeerBundleMissingException(recipientPublicKeyHex)
                } catch (e: BundleFetchException.RateLimited) {
                    val elapsed = Clock.System.now().toEpochMilliseconds() - fetchStartMs
                    messagingLog(
                        MessagingLogLevel.WARN,
                        "SEND_TRACE prekey_fetch_result=429 recipient=$recipientTag… " +
                            "elapsedMs=$elapsed endpoint=$endpointPath — message will WAIT for retry",
                    )
                    throw PeerBundleMissingException(recipientPublicKeyHex)
                } catch (e: BundleFetchException.Unexpected) {
                    val elapsed = Clock.System.now().toEpochMilliseconds() - fetchStartMs
                    messagingLog(
                        MessagingLogLevel.WARN,
                        "SEND_TRACE prekey_fetch_result=http${e.httpStatus} recipient=$recipientTag… " +
                            "elapsedMs=$elapsed endpoint=$endpointPath — message will WAIT for retry",
                    )
                    throw PeerBundleMissingException(recipientPublicKeyHex)
                } ?: run {
                    val elapsed = Clock.System.now().toEpochMilliseconds() - fetchStartMs
                    messagingLog(
                        MessagingLogLevel.INFO,
                        "SEND_TRACE prekey_fetch_result=404 recipient=$recipientTag… " +
                            "elapsedMs=$elapsed endpoint=$endpointPath — peer has not published yet",
                    )
                    throw PeerBundleMissingException(recipientPublicKeyHex)
                }
                val elapsedOk = Clock.System.now().toEpochMilliseconds() - fetchStartMs
                messagingLog(
                    MessagingLogLevel.INFO,
                    "SEND_TRACE prekey_fetch_result=200 recipient=$recipientTag… " +
                        "elapsedMs=$elapsedOk endpoint=$endpointPath",
                )
                messagingLog(MessagingLogLevel.INFO, "SEND_TRACE bootstrap_init_start conv=$convTag")
                val pkBundle = PreKeyBundle.fromWire(wireBundle)
                val bootstrap = sessionManager.initiatorBootstrap(
                    conversationId = conversationId,
                    localIdentityKeyPair = localKeyPair,
                    bundle = pkBundle,
                )
                messagingLog(MessagingLogLevel.INFO, "SEND_TRACE bootstrap_init_ok conv=$convTag")
                messagingLog(MessagingLogLevel.INFO, "SEND_TRACE ratchet_encrypt_start conv=$convTag plaintextBytes=${plaintext.size} bootstrap=true")
                val (newState, encrypted) = ratchet.encrypt(bootstrap.ratchetState, plaintext)
                messagingLog(MessagingLogLevel.INFO, "SEND_TRACE ratchet_encrypt_ok conv=$convTag")

                // Attach our Ed25519 signing pubkey so the recipient can
                // cache it under our X25519 identity for verifying our
                // future SPK rotations.
                val ourSigning = signingKeyProvider() ?: error(
                    "encryptUnderLock: local Ed25519 signing keypair is missing. " +
                        "Either onboarding (commit 13) or migration (commit 12) " +
                        "must run before any first-send happens.",
                )
                val ourSigningHex = ourSigning.publicKey.bytes
                    .joinToString("") { "%02x".format(it.toInt().and(0xFF)) }

                val wireFrame = WireFrame(
                    encryptedMessage = encrypted,
                    x3dhInit = bootstrap.x3dhInit,
                    senderSigningPublicKeyHex = ourSigningHex,
                )
                messagingLog(MessagingLogLevel.INFO, "SEND_TRACE after_encrypt_callback_start conv=$convTag bootstrap=true")
                afterEncrypt(wireFrame)
                messagingLog(MessagingLogLevel.INFO, "SEND_TRACE after_encrypt_callback_ok conv=$convTag")
                messagingLog(MessagingLogLevel.INFO, "SEND_TRACE save_session_start conv=$convTag")
                sessionManager.saveSession(conversationId, newState)
                messagingLog(MessagingLogLevel.INFO, "SEND_TRACE save_session_ok conv=$convTag")
                wireFrame
            }
        }
    }

    /**
     * Platform hook for local push notifications.
     * Set by the Android AppContainer after [initMessaging]; null on other platforms.
     * Called on the scope's thread after a message is successfully decrypted and stored.
     * The callback must be non-blocking (fire-and-forget on the platform side).
     *
     * Parameters: conversationId, senderName, preview, senderPublicKeyHex
     */
    @Volatile var onNewMessageNotification: ((conversationId: String, senderName: String, preview: String, senderPublicKeyHex: String) -> Unit)? = null

    /**
     * Optional group messaging delegate. When set, any incoming payload whose type is in
     * [MessagePayload.GROUP_TYPES] is forwarded here instead of being processed as a 1:1
     * message. Set by AppContainer after identity is loaded.
     */
    @Volatile var groupMessagingService: GroupMessagingService? = null

    /**
     * Platform hook for call signalling.
     * Set by the Android AppContainer after [initMessaging].
     * Called for any incoming payload whose type is in [MessagePayload.CALL_TYPES].
     * The callback must be non-blocking (delegate processing to a coroutine scope).
     *
     * Parameters: payload, senderPublicKeyHex
     */
    @Volatile var onCallMessage: ((MessagePayload, String) -> Unit)? = null

    override suspend fun sendMessage(message: OutgoingMessage): Result<Unit> = runCatching {
        // PR-G1 (2026-05-12): trace entry. Pair with `SEND_TRACE` lines in
        // encryptUnderLock to localise where a delayed first-send blocks.
        val convTag = message.conversationId.take(12)
        messagingLog(
            MessagingLogLevel.INFO,
            "SEND_TRACE send_start id=${message.id.take(12)}… conv=$convTag textLen=${message.text.length}",
        )
        val payload = json.encodeToString(
            MessagePayload(
                text = message.text,
                sentAt = Clock.System.now().toEpochMilliseconds(),
                senderUsername = identity.username,
            )
        ).encodeToByteArray()

        val insertedAtMs = Clock.System.now().toEpochMilliseconds()
        val outgoingTimerSecs = conversationRepository.getDisappearingTimer(message.conversationId)
        val outgoingExpiresAtMs = if (outgoingTimerSecs > 0L) insertedAtMs + outgoingTimerSecs * 1_000L else null

        // insertMessage runs inside the per-conversation mutex (via afterEncrypt),
        // BEFORE saveSession — so on crash the message is visible in the DB (QUEUED)
        // and the ratchet state has not yet advanced. Better than the inverse
        // (advanced state, lost message).
        var ciphertextBytes = ByteArray(0) // captured from afterEncrypt

        try {
            encryptUnderLock(
                conversationId = message.conversationId,
                recipientPublicKeyHex = message.recipientPublicKeyHex,
                plaintext = payload,
                afterEncrypt = { wireFrame ->
                    val ct = json.encodeToString(wireFrame).encodeToByteArray()
                    ciphertextBytes = ct
                    messageRepository.insertMessage(
                        MessageEntity(
                            id = message.id,
                            conversationId = message.conversationId,
                            ciphertext = ct,
                            plaintextCache = message.text,
                            sent = true,
                            status = MessageStatus.QUEUED,
                            createdAt = insertedAtMs,
                            expiresAtMs = outgoingExpiresAtMs,
                        )
                    )
                },
            )
        } catch (e: PeerBundleMissingException) {
            // Peer has no published prekey bundle yet — either they
            // haven't installed Alpha 2 + migrated, or their device
            // hasn't been online to publish, or the relay is missing
            // the prekey endpoints entirely (server-side deployment
            // lag). Insert a placeholder row with WAITING status so:
            //   * UI shows the message immediately (no silent /dev/null)
            //   * Retry hook in retryWaitingMessages() can pick it up
            //     later, on WS reconnect or a periodic ticker.
            messageRepository.insertMessage(
                MessageEntity(
                    id = message.id,
                    conversationId = message.conversationId,
                    // Empty placeholder ciphertext — never sent over
                    // the wire from this row. retryWaitingMessages()
                    // will re-encrypt with a fresh bundle and update
                    // the row to QUEUED → SENT.
                    ciphertext = ByteArray(0),
                    plaintextCache = message.text,
                    sent = true,
                    status = MessageStatus.WAITING_FOR_RECIPIENT_BUNDLE,
                    createdAt = insertedAtMs,
                    expiresAtMs = outgoingExpiresAtMs,
                ),
            )
            messagingLog(
                MessagingLogLevel.INFO,
                "send DEFERRED: peer ${e.recipientPubKeyHex.take(16)}… has no bundle " +
                    "(404 from /prekeys/bundle). Message saved with WAITING status; " +
                    "retryWaitingMessages() will retry on next reconnect / ticker tick.",
            )
            return@runCatching Unit
        }

        messagingLog(MessagingLogLevel.INFO, "SEND_TRACE sealed_sender_pack_start conv=$convTag")
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val sealedSenderB64 = Base64.encode(
            SealedSender.seal(
                fromPubKeyHex = identity.publicKeyHex,
                toPublicKeyBytes = hexToBytes(message.recipientPublicKeyHex),
            )
        )
        messagingLog(MessagingLogLevel.INFO, "SEND_TRACE sealed_sender_pack_ok conv=$convTag")

        val paddedCiphertext = MessagePadding.pad(ciphertextBytes)

        messagingLog(
            MessagingLogLevel.INFO,
            "SEND_TRACE relay_send_call id=${message.id.take(12)}… conv=$convTag payloadBytes=${paddedCiphertext.size}",
        )
        val sent = transport.send(
            RelayMessage.Send(
                to = message.recipientPublicKeyHex,
                from = "",
                sealedSender = sealedSenderB64,
                payload = paddedCiphertext.encodeBase64(),
                messageId = message.id,
            )
        )
        messagingLog(
            MessagingLogLevel.INFO,
            "SEND_TRACE relay_send_return id=${message.id.take(12)}… conv=$convTag ok=$sent",
        )

        val newStatus = if (sent) MessageStatus.SENT else MessageStatus.QUEUED
        messageRepository.updateStatus(message.id, newStatus)

        conversationRepository.upsertConversation(
            conversationRepository.getConversation(message.conversationId)
                ?.copy(
                    lastMessagePreview = previewText(message.text),
                    lastMessageAt = Clock.System.now().toEpochMilliseconds(),
                )
                ?: ConversationEntity(
                    id = message.conversationId,
                    theirUsername = message.recipientPublicKeyHex.take(8),
                    theirPublicKeyHex = message.recipientPublicKeyHex,
                    lastMessagePreview = previewText(message.text),
                    lastMessageAt = Clock.System.now().toEpochMilliseconds(),
                    unreadCount = 0,
                    trustTier = TrustTier.TRUSTED,
                    blocked = false,
                )
        )
    }

    /**
     * Split [audioBytes] into [AUDIO_CHUNK_BYTES]-sized plaintext chunks, encrypt
     * each chunk independently as its own [MessagePayload.TYPE_AUDIO_CHUNK] envelope,
     * and send each envelope via [transport.send]. The chunk size is tuned (PR-D2b.1)
     * so every individual envelope stays under the REST short-poll body cap.
     *
     * Per-chunk failure handling: [transport.send] returns `false` when the
     * relay rejected the envelope (offline / handshake mid-flight / over cap).
     * We log `VOICE_TX chunk_send_fail` and throw out of the loop so
     * `runCatching` catches the failure and the local message stays
     * `MessageStatus.QUEUED` — the user sees the voice in the chat as
     * "not yet delivered" instead of as "sent" while half the chunks
     * silently never reached the relay.
     *
     * The sender's own DB row uses the full reassembled base64 so AudioBubble
     * renders immediately without waiting for chunk acknowledgements.
     *
     * PR-M1w: when [voiceV2Sender] is wired, this method switches to the
     * encrypted-media upload path (encrypt → chunk → /media/upload-chunk × N →
     * single voice_v2 manifest envelope). The legacy audio_chunk path is kept
     * as a fallback for tests that construct DMS without [voiceV2Sender].
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    override suspend fun sendAudio(
        conversationId: String,
        audioBytes: ByteArray,
        durationMs: Long,
        mimeType: String,
    ): Result<Unit> {
        if (audioBytes.size > MAX_AUDIO_BYTES) {
            return Result.failure(IllegalArgumentException(
                "Audio payload ${audioBytes.size} bytes exceeds MAX_AUDIO_BYTES cap ($MAX_AUDIO_BYTES). " +
                    "Recording must be shorter."
            ))
        }

        // PR-D2a — send-layer guard. UI button is gated separately, but a
        // gesture / callback / retry path can still call here; this guard
        // makes "recorded but disappeared" impossible.
        if (!canSendVoice()) {
            messagingLog(
                MessagingLogLevel.WARN,
                "VOICE_TX blocked_limited_realtime conv=${conversationId.take(12)} audioBytes=${audioBytes.size} source=send_layer",
            )
            return Result.failure(IllegalStateException(
                "Voice messages are temporarily unavailable in Limited realtime mode."
            ))
        }

        val conv = conversationRepository.getConversation(conversationId)
            ?: return Result.failure(IllegalStateException(
                "sendAudio: no conversation found for id=$conversationId"
            ))

        // PR-M1w: use encrypted-media path when VoiceV2Sender is wired.
        if (voiceV2Sender != null) {
            return sendAudioV2(conversationId, conv, audioBytes, durationMs, mimeType)
        }

        // ── Legacy audio_chunk path (kept for tests + backward compat) ──────────
        val recipientPublicKeyHex = conv.theirPublicKeyHex

        val voiceId = uuid4().toString()
        val total = kotlin.math.ceil(audioBytes.size.toDouble() / AUDIO_CHUNK_BYTES).toInt()
            .coerceAtLeast(1)

        val insertedAtMs = Clock.System.now().toEpochMilliseconds()
        val outgoingTimerSecs = conversationRepository.getDisappearingTimer(conversationId)
        val outgoingExpiresAtMs = if (outgoingTimerSecs > 0L) insertedAtMs + outgoingTimerSecs * 1_000L else null

        val fullBase64 = Base64.encode(audioBytes)
        val localMsgId = uuid4().toString()
        messageRepository.insertMessage(
            MessageEntity(
                id = localMsgId,
                conversationId = conversationId,
                ciphertext = ByteArray(0),
                plaintextCache = "[AUDIO:$fullBase64]",
                sent = true,
                status = MessageStatus.QUEUED,
                createdAt = insertedAtMs,
                expiresAtMs = outgoingExpiresAtMs,
            )
        )

        messagingLog(
            MessagingLogLevel.INFO,
            "VOICE_TX send_start voiceId=${voiceId.take(8)} totalChunks=$total rawBytes=${audioBytes.size} chunkSizeBytes=$AUDIO_CHUNK_BYTES",
        )

        return runCatching {
            for (i in 0 until total) {
                val start = i * AUDIO_CHUNK_BYTES
                val end = minOf((i + 1) * AUDIO_CHUNK_BYTES, audioBytes.size)
                val slice = audioBytes.copyOfRange(start, end)
                val chunkBase64 = Base64.encode(slice)

                val payloadBytes = json.encodeToString(
                    MessagePayload(
                        type = MessagePayload.TYPE_AUDIO_CHUNK,
                        sentAt = Clock.System.now().toEpochMilliseconds(),
                        senderUsername = identity.username,
                        audioChunkId = voiceId,
                        audioChunkIndex = i,
                        audioChunkTotal = total,
                        audioChunkB64 = chunkBase64,
                        audioDurationMs = durationMs,
                        audioMimeType = mimeType,
                    )
                ).encodeToByteArray()

                val encrypted = encryptUnderLock(
                    conversationId = conversationId,
                    recipientPublicKeyHex = recipientPublicKeyHex,
                    plaintext = payloadBytes,
                )
                val ciphertext = json.encodeToString(encrypted).encodeToByteArray()
                val paddedCiphertext = MessagePadding.pad(ciphertext)
                val envelopeId = uuid4().toString()

                messagingLog(
                    MessagingLogLevel.INFO,
                    "VOICE_TX chunk_prepare voiceId=${voiceId.take(8)} idx=$i/$total rawBytes=${slice.size} envelopeBytes=${paddedCiphertext.size} envelopeId=${envelopeId.take(8)}",
                )

                val sent = transport.send(
                    RelayMessage.Send(
                        to = recipientPublicKeyHex,
                        from = "",
                        sealedSender = Base64.encode(
                            SealedSender.seal(identity.publicKeyHex, hexToBytes(recipientPublicKeyHex))
                        ),
                        payload = paddedCiphertext.encodeBase64(),
                        messageId = envelopeId,
                    )
                )

                if (!sent) {
                    messagingLog(
                        MessagingLogLevel.WARN,
                        "VOICE_TX chunk_send_fail voiceId=${voiceId.take(8)} idx=$i/$total envelopeId=${envelopeId.take(8)}",
                    )
                    throw IllegalStateException(
                        "sendAudio: transport.send rejected chunk $i/$total for voiceId=${voiceId.take(8)}"
                    )
                }

                messagingLog(
                    MessagingLogLevel.INFO,
                    "VOICE_TX chunk_send_ok voiceId=${voiceId.take(8)} idx=$i/$total envelopeId=${envelopeId.take(8)}",
                )
            }

            conversationRepository.upsertConversation(
                conv.copy(
                    lastMessagePreview = "Voice message",
                    lastMessageAt = Clock.System.now().toEpochMilliseconds(),
                )
            )
            messageRepository.updateStatus(localMsgId, MessageStatus.SENT)

            messagingLog(
                MessagingLogLevel.INFO,
                "VOICE_TX send_complete voiceId=${voiceId.take(8)} totalChunks=$total",
            )
        }
    }

    /**
     * PR-M1w encrypted-media send path (voice_v2).
     *
     * Step-by-step per §3 of the design:
     * 1. In-progress guard — prevents two concurrent uploads for the same conversation.
     * 2. Local row INSERT (QUEUED) with [AUDIO:<base64>] — sender-side self-contained playback (Q1).
     * 3. Status → UPLOADING.
     * 4. Delegate to [VoiceV2Sender.uploadVoice] (encrypt + chunk + upload).
     * 5. On failure → FAILED, remove guard, no manifest.
     * 6. On success → build manifest payload, encrypt, send via transport.
     * 7. Update conversation preview; remove guard.
     * Status → SENT/DELIVERED arrives via the existing acks flow handler.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private suspend fun sendAudioV2(
        conversationId: String,
        conv: ConversationEntity,
        audioBytes: ByteArray,
        durationMs: Long,
        mimeType: String,
    ): Result<Unit> {
        val sender = voiceV2Sender!! // safe: caller already null-checked
        val recipientPublicKeyHex = conv.theirPublicKeyHex

        // Step 1 — In-progress guard
        val alreadyInProgress = mutexFor(conversationId).withLock {
            if (voiceSendInProgress.contains(conversationId)) true
            else { voiceSendInProgress.add(conversationId); false }
        }
        if (alreadyInProgress) {
            return Result.failure(IllegalStateException(
                "A voice message is still uploading. Please wait."
            ))
        }

        val insertedAtMs = Clock.System.now().toEpochMilliseconds()
        val outgoingTimerSecs = conversationRepository.getDisappearingTimer(conversationId)
        val outgoingExpiresAtMs = if (outgoingTimerSecs > 0L) insertedAtMs + outgoingTimerSecs * 1_000L else null

        // Step 2 — Local row INSERT (QUEUED). Q1: sender stores Base64 for self-contained playback.
        val localMsgId = uuid4().toString()
        val fullBase64 = Base64.encode(audioBytes)
        messageRepository.insertMessage(
            MessageEntity(
                id             = localMsgId,
                conversationId = conversationId,
                ciphertext     = ByteArray(0),
                plaintextCache = "[AUDIO:$fullBase64]",
                sent           = true,
                status         = MessageStatus.QUEUED,
                createdAt      = insertedAtMs,
                expiresAtMs    = outgoingExpiresAtMs,
            )
        )

        // Step 3 — Status → UPLOADING
        messageRepository.updateStatus(localMsgId, MessageStatus.UPLOADING)

        // Steps 4–7 launched on the DMS-injected appScope so the upload survives
        // UI lifecycle changes (MB8, Test #58). The caller (ChatScreen viewModelScope
        // or any other Compose scope) returns immediately after scheduling the job.
        // The in-progress guard is released INSIDE the launched coroutine — moving
        // it outside would allow a second concurrent send to fire before the first
        // one has truly finished (or failed).
        messagingLog(
            MessagingLogLevel.INFO,
            "MEDIA_TX upload_job_started scope=dms localMsgId=${localMsgId.take(8)}",
        )
        scope.launch {
            try {
                val uploadResult = sender.uploadVoice(
                    audioBytes = audioBytes,
                    durationMs = durationMs,
                    mime       = mimeType,
                    onSplit          = { total ->
                        mediaProgressBus.update(localMsgId, sent = 0, total = total, direction = MediaProgressBus.Direction.UPLOAD)
                    },
                    onChunkUploaded  = { sent, total ->
                        mediaProgressBus.update(localMsgId, sent = sent, total = total, direction = MediaProgressBus.Direction.UPLOAD)
                    },
                )
                if (uploadResult.isFailure) {
                    val reason = uploadResult.exceptionOrNull()?.message?.take(60) ?: "unknown"
                    messagingLog(
                        MessagingLogLevel.WARN,
                        "MEDIA_TX send_failed_no_manifest mediaId=unknown reason=$reason",
                    )
                    messageRepository.updateStatus(localMsgId, MessageStatus.FAILED)
                    mediaProgressBus.clear(localMsgId)
                    return@launch
                }

                val manifest = uploadResult.getOrThrow()

                // Step 6 — Wrap manifest in MessagePayload(TYPE_VOICE_V2) so the
                // receiver's `payload.type == TYPE_VOICE_V2` branch fires. Before
                // this fix the raw VoiceManifestV2 JSON went on the wire; the
                // receiver decoded it as `MessagePayload(type=message, text="")`
                // and surfaced it as an empty chat bubble (Test #57 root cause).
                val manifestJson = json.encodeToString(manifest)
                val payload = MessagePayload(
                    type           = MessagePayload.TYPE_VOICE_V2,
                    text           = manifestJson,
                    sentAt         = Clock.System.now().toEpochMilliseconds(),
                    senderUsername = identity.username,
                )
                val payloadBytes = json.encodeToString(payload).encodeToByteArray()
                val encrypted = encryptUnderLock(
                    conversationId        = conversationId,
                    recipientPublicKeyHex = recipientPublicKeyHex,
                    plaintext             = payloadBytes,
                )
                val ciphertextBytes = json.encodeToString(encrypted).encodeToByteArray()
                val paddedCiphertext = MessagePadding.pad(ciphertextBytes)
                val envelopeId = uuid4().toString()

                transport.send(
                    RelayMessage.Send(
                        to           = recipientPublicKeyHex,
                        from         = "",
                        sealedSender = Base64.encode(
                            SealedSender.seal(identity.publicKeyHex, hexToBytes(recipientPublicKeyHex))
                        ),
                        payload   = paddedCiphertext.encodeBase64(),
                        messageId = envelopeId,
                    )
                )
                // transport.send returning false is OK — the envelope is in the outbox durable store
                // (HybridRelayTransport retry path). The ratchet ack flow will lift SENT → DELIVERED.
                messagingLog(
                    MessagingLogLevel.INFO,
                    "MEDIA_TX manifest_sent mediaId=${manifest.mediaId.take(8)} " +
                        "envelopeId=${envelopeId.take(8)} chunkCount=${manifest.chunkCount}",
                )
                // R5-3 — Flip local row UPLOADING → SENT immediately after manifest leaves us.
                // Without this the sender bubble visually stays in UPLOADING until the
                // receiver's read receipt (potentially many minutes on Limited realtime).
                // Mirrors the text-send path which marks rows SENT after transport.send returns.
                messageRepository.updateStatus(localMsgId, MessageStatus.SENT)
                messagingLog(
                    MessagingLogLevel.INFO,
                    "MEDIA_TX local_status_sent mediaId=${manifest.mediaId.take(8)} " +
                        "localMsgId=${localMsgId.take(8)}",
                )

                // Step 7 — Update conversation preview
                conversationRepository.upsertConversation(
                    conv.copy(
                        lastMessagePreview = "Voice message",
                        lastMessageAt      = Clock.System.now().toEpochMilliseconds(),
                    )
                )
            } catch (ce: kotlinx.coroutines.CancellationException) {
                messagingLog(
                    MessagingLogLevel.WARN,
                    "MEDIA_TX upload_job_cancelled localMsgId=${localMsgId.take(8)} " +
                        "reason=${ce.message?.take(60) ?: "scope_cancelled"}",
                )
                messageRepository.updateStatus(localMsgId, MessageStatus.FAILED)
                throw ce
            } finally {
                mediaProgressBus.clear(localMsgId)
                mutexFor(conversationId).withLock {
                    voiceSendInProgress.remove(conversationId)
                }
            }
        }
        return Result.success(Unit)
    }

    override suspend fun startReceiving() {
        // compareAndSet under the lock: two concurrent callers cannot both see
        // receiving == false, preventing duplicate flow collectors.
        val alreadyStarted = startReceivingLock.withLock {
            if (receiving) true else { receiving = true; false }
        }
        if (alreadyStarted) return

        // PR-D2b.1 (2026-05-17): durable voice finalizer. Runs once per
        // startReceiving, before the live chunk subscription, so any voice
        // whose chunks all landed before the previous process death gets
        // assembled + inserted on restart. Also sweeps anything older than
        // VOICE_CHUNK_TTL_MS so a long-idle restart does not start with
        // stale partial state. `messageRepository.insertMessage` is
        // INSERT OR IGNORE (see Message.sq), so a finalizer run after a
        // live insert that succeeded but failed to deleteByVoiceId is a
        // silent no-op for the messages table and just cleans up the
        // orphaned chunks. Group voice is intentionally skipped here
        // because the durable row schema does not carry `groupId`.
        voiceChunkRepository?.let { repo ->
            val nowMs = Clock.System.now().toEpochMilliseconds()
            val cutoffMs = nowMs - VOICE_CHUNK_TTL_MS
            sweepExpiredVoiceChunks(repo, cutoffMs, nowMs)
            val ready = repo.findVoicesReadyToAssemble()
            if (ready.isNotEmpty()) {
                messagingLog(
                    MessagingLogLevel.INFO,
                    "VOICE_RX finalizer_start voiceCount=${ready.size}",
                )
                ready.forEach { rv ->
                    messagingLog(
                        MessagingLogLevel.INFO,
                        "VOICE_RX finalizer_resume voiceId=${rv.voiceId.take(8)} total=${rv.total}",
                    )
                    assembleAndDispatch1to1Voice(
                        voiceId = rv.voiceId,
                        conversationId = rv.conversationId,
                        senderPubKeyHex = rv.senderPubKeyHex,
                        senderUsername = "",
                        mimeType = rv.mimeType,
                        durationMs = rv.durationMs,
                        nowMs = nowMs,
                        origin = "finalizer",
                    )
                }
                messagingLog(
                    MessagingLogLevel.INFO,
                    "VOICE_RX finalizer_done voiceCount=${ready.size}",
                )
            }
        }

        // PR-M1w finalizer: resume any voice_v2 download tasks that were PENDING
        // when the previous process died. Mirrors the D2b.1 chunk-assembly finalizer.
        voiceV2DownloadRepository?.let { repo ->
            val pending = repo.findPending()
            if (pending.isNotEmpty()) {
                messagingLog(
                    MessagingLogLevel.INFO,
                    "MEDIA_RX finalizer_start taskCount=${pending.size}",
                )
                pending.forEach { task ->
                    messagingLog(
                        MessagingLogLevel.INFO,
                        "MEDIA_RX finalizer_resume mediaId=${task.mediaId.take(8)}",
                    )
                    scope.launch {
                        runCatching { runVoiceV2DownloadTask(task.mediaId) }
                            .onFailure { ex ->
                                messagingLog(
                                    MessagingLogLevel.WARN,
                                    "MEDIA_RX finalizer_task_threw mediaId=${task.mediaId.take(8)} error=${ex::class.simpleName}",
                                )
                            }
                    }
                }
            }
        }

        transport.incoming
            .onEach { deliver -> handleDeliver(deliver) }
            .launchIn(scope)

        transport.acks
            .onEach { ack ->
                val newStatus = when (ack.status) {
                    "delivered" -> MessageStatus.DELIVERED
                    else        -> MessageStatus.RELAYED
                }
                messageRepository.updateStatus(ack.messageId, newStatus)
            }
            .launchIn(scope)
    }

    /**
     * Drops every chunk row older than [cutoffMs] (PR-D2b.1).
     *
     * Logs one `VOICE_RX partial_expired` line per voice that will be
     * swept BEFORE the bulk delete so a log reader can see which voice,
     * with what chunk count, was dropped and how old it was. Safe to
     * call on a hot path — when nothing is stale the function returns
     * after a single empty `findExpiredSummaries` SELECT.
     */
    private suspend fun sweepExpiredVoiceChunks(
        repo: VoiceChunkRepository,
        cutoffMs: Long,
        nowMs: Long,
    ) {
        val expired = repo.findExpiredSummaries(cutoffMs)
        if (expired.isEmpty()) return
        expired.forEach { s ->
            messagingLog(
                MessagingLogLevel.WARN,
                "VOICE_RX partial_expired voiceId=${s.voiceId.take(8)} received=${s.receivedChunks}/${s.total} ageMs=${nowMs - s.oldestUpdatedMs}",
            )
        }
        repo.deleteOlderThan(cutoffMs)
    }

    /**
     * Concat every saved chunk for [voiceId] (in idx order), insert one
     * `[AUDIO:<base64>]` message into the message store, upsert the
     * conversation, emit `_incomingMessages`, fire the new-message
     * notification, and finally delete the chunks (PR-D2b.1).
     *
     * Called from two places:
     *
     * - Live chunk handler when `countChunks(voiceId) == total` for the
     *   just-saved chunk. `origin = "live"`.
     * - `startReceiving` finalizer for voices whose chunk count was
     *   already complete before the previous process died.
     *   `origin = "finalizer"`.
     *
     * Crash-safety contract:
     *
     * - `messageRepository.insertMessage` is `INSERT OR IGNORE` on `id`,
     *   so a finalizer run after a previous successful insert is a
     *   silent no-op for the message store. We still proceed to delete
     *   chunks at the end so the partial buffer doesn't grow forever.
     * - If `insertMessage` itself throws, chunks are NOT deleted. The
     *   finalizer will retry on next `startReceiving`.
     * - `messages.id` deliberately uses [voiceId] rather than the
     *   triggering `deliver.messageId` so the live and finalizer paths
     *   converge on the same primary key — INSERT OR IGNORE turns the
     *   rare double-call into a safe no-op rather than a duplicate row.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private suspend fun assembleAndDispatch1to1Voice(
        voiceId: String,
        conversationId: String,
        senderPubKeyHex: String,
        senderUsername: String,
        mimeType: String,
        durationMs: Long,
        nowMs: Long,
        origin: String,
    ) {
        val repo = voiceChunkRepository ?: return

        // PR-D2b.1 review round 2 (2026-05-17): pre-check for an already
        // inserted voice message keyed on this `voiceId`. INSERT OR IGNORE
        // makes the actual SQL insert a silent no-op when the row exists,
        // but every downstream side effect (conversation unread bump,
        // `_incomingMessages.emit`, `onNewMessageNotification`) would
        // still fire — so a crash between live insert and `deleteByVoiceId`
        // would let the finalizer double-emit + double-notify + double-
        // unread the same voice. The check happens BEFORE reading and
        // concatenating chunk bytes so the wasted blob allocation is
        // skipped on the recovery path too.
        val existingRow = runCatching { messageRepository.getMessageById(voiceId) }.getOrNull()
        if (existingRow != null) {
            messagingLog(
                MessagingLogLevel.INFO,
                "VOICE_RX already_inserted_cleanup voiceId=${voiceId.take(8)} source=$origin",
            )
            repo.deleteByVoiceId(voiceId)
            return
        }

        val ordered = repo.findOrderedChunks(voiceId)
        if (ordered.isEmpty()) {
            messagingLog(
                MessagingLogLevel.WARN,
                "VOICE_RX assembly_no_chunks voiceId=${voiceId.take(8)} source=$origin",
            )
            return
        }
        val assembledSize = ordered.sumOf { it.size }
        val assembled = ByteArray(assembledSize)
        var offset = 0
        for (chunk in ordered) {
            chunk.copyInto(assembled, destinationOffset = offset)
            offset += chunk.size
        }
        messagingLog(
            MessagingLogLevel.INFO,
            "VOICE_RX assembly_complete voiceId=${voiceId.take(8)} assembledBytes=${assembled.size} source=$origin",
        )

        val assembledB64 = Base64.encode(assembled)
        val timerSecs = conversationRepository.getDisappearingTimer(conversationId)
        val expiresAtMs = if (timerSecs > 0L) nowMs + timerSecs * 1_000L else null

        messagingLog(
            MessagingLogLevel.INFO,
            "VOICE_RX message_insert_start voiceId=${voiceId.take(8)} bytes=${assembled.size} source=$origin",
        )
        val inserted = runCatching {
            messageRepository.insertMessage(
                MessageEntity(
                    id = voiceId,
                    conversationId = conversationId,
                    ciphertext = ByteArray(0),
                    plaintextCache = "[AUDIO:$assembledB64]",
                    sent = false,
                    status = MessageStatus.DELIVERED,
                    createdAt = nowMs,
                    expiresAtMs = expiresAtMs,
                )
            )
        }.fold(
            onSuccess = {
                messagingLog(
                    MessagingLogLevel.INFO,
                    "VOICE_RX message_insert_ok voiceId=${voiceId.take(8)} source=$origin",
                )
                true
            },
            onFailure = { ex ->
                messagingLog(
                    MessagingLogLevel.WARN,
                    "VOICE_RX message_insert_fail voiceId=${voiceId.take(8)} error=${ex::class.simpleName} message=${ex.message?.take(120) ?: ""} chunks_preserved=true source=$origin",
                )
                false
            },
        )
        if (!inserted) {
            // Chunks intentionally NOT deleted — finalizer retries on
            // next startReceiving.
            return
        }

        val existing = conversationRepository.getConversation(conversationId)
        val senderName = senderUsername.ifBlank { senderPubKeyHex.take(8) }
        if (existing == null) {
            conversationRepository.upsertConversation(
                ConversationEntity(
                    id = conversationId,
                    theirUsername = senderName,
                    theirPublicKeyHex = senderPubKeyHex,
                    lastMessagePreview = "🎤 Voice message",
                    lastMessageAt = nowMs,
                    unreadCount = 1,
                    trustTier = TrustTier.REQUEST,
                    blocked = false,
                )
            )
        } else {
            conversationRepository.upsertConversation(
                existing.copy(
                    lastMessagePreview = "🎤 Voice message",
                    lastMessageAt = nowMs,
                    unreadCount = existing.unreadCount + 1,
                )
            )
        }

        _incomingMessages.emit(
            IncomingMessage(
                id = voiceId,
                conversationId = conversationId,
                senderPublicKeyHex = senderPubKeyHex,
                text = "[AUDIO:$assembledB64]",
                receivedAt = nowMs,
            )
        )

        runCatching {
            onNewMessageNotification?.invoke(conversationId, senderName, "🎤 Voice message", senderPubKeyHex)
        }

        // Successful end-to-end durable receive: free the partial state.
        repo.deleteByVoiceId(voiceId)
        messagingLog(
            MessagingLogLevel.INFO,
            "VOICE_RX handler_complete voiceId=${voiceId.take(8)} result=assembled source=$origin",
        )
    }

    private suspend fun handleDeliver(deliver: RelayMessage.Deliver) {
        val claimed = processingLock.withLock {
            if (deliver.messageId in activeProcessing) false
            else { activeProcessing.add(deliver.messageId); true }
        }
        if (!claimed) {
            messagingLog(
                MessagingLogLevel.WARN,
                "Duplicate in-flight delivery skipped: id=${deliver.messageId.take(12)}…",
            )
            return
        }
        try {
        messagingLog(
            MessagingLogLevel.INFO,
            "handleDeliver start: id=${deliver.messageId.take(12)}… sealed=${deliver.sealedSender.isNotEmpty()} payloadBytes=${deliver.payload.length}",
        )
        runCatching {
            // Recover sender identity: sealed sender hides `from` from the relay.
            @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
            val senderPubKeyHex = if (deliver.sealedSender.isNotEmpty()) {
                val sealedBytes = Base64.decode(deliver.sealedSender)
                SealedSender.unseal(sealedBytes, localKeyPair.privateKey.bytes)
                    ?: run {
                        messagingLog(
                            MessagingLogLevel.WARN,
                            "Sealed Sender unseal returned null — dropping envelope id=${deliver.messageId.take(12)}…",
                        )
                        return@runCatching
                    }
            } else {
                deliver.from // Backward compat: relay-supplied `from` for non-sealed messages.
            }
            messagingLog(
                MessagingLogLevel.INFO,
                "Sender identified: ${senderPubKeyHex.take(16)}…",
            )

            // PR-H2b (2026-05-13): idempotent envelope ledger guard.
            // Runs BEFORE ratchet.decrypt so a duplicate redelivery cannot
            // advance the chain key a second time and MAC-fail.
            //
            // Test #34 (2026-05-13) reproduced exactly this: phone read-
            // receipt 5b5c4faa decrypted successfully on first delivery,
            // its ack-deliver was lost when the WS reconnected mid-write,
            // the relay re-delivered, the second decrypt MAC-failed
            // because the ratchet chain had already advanced. The
            // pre-existing `messages.id` check below only caught
            // user-message envelopes (read_receipts and other control
            // payloads were never inserted into the messages table).
            // The new ledger covers ALL payload types.
            //
            // Backward compat: when `processedEnvelopeRepository == null`
            // (older test setups, no SQLDelight) we fall through to the
            // legacy `messages.id` check below. Production always wires
            // the real repository.
            if (processedEnvelopeRepository?.exists(deliver.messageId) == true) {
                messagingLog(
                    MessagingLogLevel.INFO,
                    "Duplicate envelope (already in ledger): id=${deliver.messageId.take(12)}… — sending ack-deliver and skipping decrypt",
                )
                transport.sendDeliveryAck(deliver.messageId)
                return@runCatching
            }

            // Pre-PR-H2b legacy guard kept as defence-in-depth: catches
            // duplicates among ledger-less envelopes that happen to be
            // stored as messages (the common case before this PR landed).
            // Once every install has run for >8 days the ledger TTL will
            // hold all live ids and this branch becomes unreachable, but
            // there's no rush to remove it.
            val alreadyProcessed = messageRepository.getMessageById(deliver.messageId) != null
            if (alreadyProcessed) {
                messagingLog(
                    MessagingLogLevel.INFO,
                    "Duplicate envelope (already in messages DB): id=${deliver.messageId.take(12)}… — sending ack-deliver and skipping",
                )
                transport.sendDeliveryAck(deliver.messageId)
                return@runCatching
            }

            val rawPayloadBytes = deliver.payload.decodeBase64Bytes()

            // Unpad ISO 7816-4 padding applied by the sender to hide message length.
            val ciphertext = MessagePadding.unpad(rawPayloadBytes)
            // PR C commit 11: parse the WireFrame wrapper. Alpha 2 wraps
            // every outbound message in WireFrame; Alpha 1 sent a bare
            // EncryptedMessage. Migration wipes every session so there is
            // no Alpha-1 → Alpha-2 cross traffic — anything arriving here
            // SHOULD be well-formed WireFrame.
            //
            // Tolerant fallback: if WireFrame parse fails, retry as a bare
            // EncryptedMessage and wrap. This rescues legacy envelopes
            // that sat in the relay store across the migration.
            //
            // CRITICAL — DO NOT ack on parse failure. Earlier revision
            // ack'd to break a perceived redeliver loop, but logs from
            // 2026-05-01 testing showed the "unparseable" envelopes were
            // actually fresh sealed=false payloads from the peer (delivery
            // receipts / voice / control msgs) — silent ack ate real
            // user data. Log a payload preview so we can identify the
            // unknown format, and let the relay redeliver while we work
            // out what kind of envelope it is.
            val ciphertextText = ciphertext.decodeToString()
            val wireFrame = try {
                json.decodeFromString<WireFrame>(ciphertextText)
            } catch (firstErr: SerializationException) {
                try {
                    val legacy = json.decodeFromString<phantom.core.crypto.EncryptedMessage>(ciphertextText)
                    messagingLog(
                        MessagingLogLevel.WARN,
                        "Legacy bare EncryptedMessage payload — wrapping into WireFrame: id=${deliver.messageId.take(12)}…",
                    )
                    WireFrame(encryptedMessage = legacy)
                } catch (secondErr: SerializationException) {
                    messagingLog(
                        MessagingLogLevel.ERROR,
                        "Unparseable wire payload (neither WireFrame nor EncryptedMessage) — " +
                            "NOT ack'ing (relay will redeliver). " +
                            "id=${deliver.messageId.take(12)}… sealed=${deliver.sealedSender.isNotEmpty()} " +
                            "totalBytes=${ciphertextText.length} " +
                            "wireFrameErr=${firstErr.message} legacyErr=${secondErr.message}",
                    )
                    return@runCatching
                }
            }
            val encrypted = wireFrame.encryptedMessage

            val conversationId = deriveConversationId(senderPubKeyHex)
            // The same per-conversation mutex protects the receive path so that an
            // outgoing send (which advances the local sending chain and saves) cannot
            // race with an inbound decrypt. Receive itself is already serialised by
            // transport.incoming.onEach, but a parallel sendMessage on the same
            // conversation could still observe a half-saved state.
            val mutex = mutexFor(conversationId)
            val plainBytes: ByteArray? = mutex.withLock {
                val state = sessionManager.tryLoadSession(conversationId)
                if (state != null) {
                    // Existing session — decrypt directly. Any x3dhInit /
                    // signing pubkey that came with this frame are
                    // re-bootstraps; ignored when we already hold a
                    // session, the peer simply pays a few wasted bytes.
                    messagingLog(
                        MessagingLogLevel.INFO,
                        "Session loaded: conv=${conversationId.take(24)}… decrypting…",
                    )
                    try {
                        val (newState, decrypted) = ratchet.decrypt(state, encrypted)
                        sessionManager.saveSession(conversationId, newState)
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "Decrypt OK: plaintextBytes=${decrypted.size}",
                        )
                        // PR-H2b: record the envelope id BEFORE we leave
                        // the per-conversation mutex so a concurrent
                        // redelivery cannot squeak through the ledger
                        // check and re-enter ratchet.decrypt. payload_type
                        // is only known after JSON-parsing the plaintext
                        // below; we record "unknown" here and rely on the
                        // markProcessed INSERT OR IGNORE semantics if a
                        // second markProcessed call later wants to refine
                        // it (it won't — there's no such call path).
                        processedEnvelopeRepository?.markProcessed(
                            envelopeId = deliver.messageId,
                            conversationId = conversationId,
                            senderPubKeyHex = senderPubKeyHex,
                            payloadType = "unknown",
                            status = ProcessedEnvelopeRepository.Status.PROCESSED,
                            nowMs = Clock.System.now().toEpochMilliseconds(),
                        )
                        decrypted
                    } catch (e: IllegalArgumentException) {
                        // ADR-012 / 2026-05-01 audit finding: a MAC
                        // verification error is a HARD cryptographic
                        // verdict — the chain key positions on sender
                        // and receiver have permanently diverged
                        // (typically: pre-migration envelope still in
                        // relay store after PR C wiped sessions; or
                        // double-send under the same id with chain
                        // advanced between sends). The receiver can
                        // never recover this envelope, no matter how
                        // many redeliveries happen. Ack so the relay
                        // drops it. Log the id so QA can audit which
                        // envelopes died this way.
                        if (e.message?.contains("MAC", ignoreCase = true) == true ||
                            e.message?.contains("verification", ignoreCase = true) == true
                        ) {
                            messagingLog(
                                MessagingLogLevel.WARN,
                                "Permanent decrypt failure (MAC error) — ack-deliver'ing to clear " +
                                    "relay store. id=${deliver.messageId.take(12)}… " +
                                    "conv=${conversationId.take(16)}… err=${e.message}",
                            )
                            // PR-H2b: pin this envelope id with FAILED_MAC
                            // so a future redelivery sees the ledger hit
                            // and skips the (already-doomed) decrypt
                            // attempt. The ledger row's diagnostic value
                            // also helps QA grep for crypto-divergence
                            // patterns per peer.
                            processedEnvelopeRepository?.markProcessed(
                                envelopeId = deliver.messageId,
                                conversationId = conversationId,
                                senderPubKeyHex = senderPubKeyHex,
                                payloadType = "unknown",
                                status = ProcessedEnvelopeRepository.Status.FAILED_MAC,
                                nowMs = Clock.System.now().toEpochMilliseconds(),
                            )
                            transport.sendDeliveryAck(deliver.messageId)
                            return@withLock null
                        }
                        // Other IAE — rethrow, outer onFailure handles it.
                        throw e
                    }
                } else {
                    // Fresh session — require x3dhInit on the wire. The
                    // initiator side (their encryptUnderLock) is contract-
                    // bound to attach it on the first message of a new
                    // session.
                    val x3dhInit = wireFrame.x3dhInit
                    if (x3dhInit == null) {
                        // Legacy Alpha 1 bare-EncryptedMessage envelope that
                        // survived the migration in the relay store. We
                        // cannot decrypt it (session was wiped, no x3dhInit
                        // means no way to bootstrap). Ack so the relay
                        // drops it instead of redelivering on every
                        // reconnect.
                        messagingLog(
                            MessagingLogLevel.WARN,
                            "Legacy envelope: no session for conv=${conversationId.take(16)}… " +
                                "and no x3dhInit header — ack-deliver'ing to clear relay store: " +
                                "id=${deliver.messageId.take(12)}…",
                        )
                        // PR-H2b: same as the MAC-fail path above —
                        // record so a future redelivery skips immediately.
                        processedEnvelopeRepository?.markProcessed(
                            envelopeId = deliver.messageId,
                            conversationId = conversationId,
                            senderPubKeyHex = senderPubKeyHex,
                            payloadType = "unknown",
                            status = ProcessedEnvelopeRepository.Status.FAILED_MAC,
                            nowMs = Clock.System.now().toEpochMilliseconds(),
                        )
                        transport.sendDeliveryAck(deliver.messageId)
                        return@withLock null
                    }
                    messagingLog(
                        MessagingLogLevel.INFO,
                        "Bootstrapping recipient session: conv=${conversationId.take(24)}…",
                    )
                    val freshState = sessionManager.recipientBootstrap(
                        conversationId = conversationId,
                        localIdentityKeyPair = localKeyPair,
                        senderIdentityPublicKeyHex = senderPubKeyHex,
                        x3dhInit = x3dhInit,
                    )
                    val (advancedState, decrypted) = ratchet.decrypt(freshState, encrypted)
                    sessionManager.saveSession(conversationId, advancedState)

                    // TODO PR C commit 12 (or follow-up): persist
                    // wireFrame.senderSigningPublicKeyHex on the
                    // ConversationEntity (or a new PeerSigningKey table)
                    // so future SPK rotations from this peer can be
                    // verified against it. For Alpha 2 we trust the
                    // bundle's signing key on each fetch; the cache
                    // hardens the rotation path which lands in Phase 5.

                    messagingLog(
                        MessagingLogLevel.INFO,
                        "Decrypt OK after bootstrap: plaintextBytes=${decrypted.size}",
                    )
                    // PR-H2b: same ledger insert as the existing-session
                    // branch above. Bootstrap path lands here once per
                    // conversation; subsequent messages take the existing
                    // branch.
                    processedEnvelopeRepository?.markProcessed(
                        envelopeId = deliver.messageId,
                        conversationId = conversationId,
                        senderPubKeyHex = senderPubKeyHex,
                        payloadType = "unknown",
                        status = ProcessedEnvelopeRepository.Status.PROCESSED,
                        nowMs = Clock.System.now().toEpochMilliseconds(),
                    )
                    decrypted
                }
            }

            // Legacy/garbage envelope was already ack'd inside withLock —
            // skip downstream payload processing.
            if (plainBytes == null) return@runCatching

            val payload = json.decodeFromString<MessagePayload>(plainBytes.decodeToString())
            messagingLog(
                MessagingLogLevel.INFO,
                "Payload parsed: type=${payload.type} textLen=${payload.text.length}",
            )

            // Route group-related messages to GroupMessagingService before 1:1 handling.
            if (payload.type in MessagePayload.GROUP_TYPES) {
                groupMessagingService?.handleIncoming(payload, senderPubKeyHex)
                transport.sendDeliveryAck(deliver.messageId)
                return@runCatching
            }

            // Chunked audio: accumulate slices and reassemble when all chunks arrived.
            // Ack-deliver each chunk immediately so the relay stops redelivering it
            // while reassembly accumulates the remaining chunks.
            if (payload.type == MessagePayload.TYPE_AUDIO_CHUNK) {
                val chunkId = payload.audioChunkId ?: run {
                    messagingLog(MessagingLogLevel.WARN, "VOICE_RX chunk_invalid_missing_field field=audioChunkId envelopeId=${deliver.messageId.take(8)}")
                    transport.sendDeliveryAck(deliver.messageId); return@runCatching
                }
                val chunkIndex = payload.audioChunkIndex ?: run {
                    messagingLog(MessagingLogLevel.WARN, "VOICE_RX chunk_invalid_missing_field field=audioChunkIndex envelopeId=${deliver.messageId.take(8)} chunkId=${chunkId.take(8)}")
                    transport.sendDeliveryAck(deliver.messageId); return@runCatching
                }
                val chunkTotal = payload.audioChunkTotal ?: run {
                    messagingLog(MessagingLogLevel.WARN, "VOICE_RX chunk_invalid_missing_field field=audioChunkTotal envelopeId=${deliver.messageId.take(8)} chunkId=${chunkId.take(8)}")
                    transport.sendDeliveryAck(deliver.messageId); return@runCatching
                }
                val chunkB64 = payload.audioChunkB64 ?: run {
                    messagingLog(MessagingLogLevel.WARN, "VOICE_RX chunk_invalid_missing_field field=audioChunkB64 envelopeId=${deliver.messageId.take(8)} chunkId=${chunkId.take(8)}")
                    transport.sendDeliveryAck(deliver.messageId); return@runCatching
                }

                messagingLog(
                    MessagingLogLevel.INFO,
                    "VOICE_RX chunk_received envelopeId=${deliver.messageId.take(8)} chunkId=${chunkId.take(8)} index=$chunkIndex total=$chunkTotal payloadBytes=${plainBytes!!.size}",
                )

                messagingLog(
                    MessagingLogLevel.INFO,
                    "VOICE_RX chunk_decode_start chunkId=${chunkId.take(8)} index=$chunkIndex b64Len=${chunkB64.length}",
                )
                @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                val chunkBytes = runCatching { Base64.decode(chunkB64) }.fold(
                    onSuccess = { bytes ->
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "VOICE_RX chunk_decode_ok chunkId=${chunkId.take(8)} index=$chunkIndex decodedBytes=${bytes.size}",
                        )
                        bytes
                    },
                    onFailure = { ex ->
                        messagingLog(
                            MessagingLogLevel.WARN,
                            "VOICE_RX chunk_decode_fail chunkId=${chunkId.take(8)} index=$chunkIndex error=${ex::class.simpleName} message=${ex.message?.take(120) ?: ""}",
                        )
                        transport.sendDeliveryAck(deliver.messageId)
                        return@runCatching
                    },
                )

                // PR-D2b.1 review round 2 (2026-05-17): validate
                // `chunkIndex` is inside `[0, chunkTotal)` before saving.
                // A malformed payload (`audioChunkIndex >= audioChunkTotal`
                // or `audioChunkTotal <= 0`) would otherwise be stored
                // either as an out-of-range row that never participates
                // in `findOrderedChunks` correctly, or as an extra slot
                // that pushes `countChunks` past `total` without filling
                // every index 0..N-1. Both states leave reassembly broken
                // in subtle ways; the cheap fix is to reject the chunk
                // up front and let the relay drop it (ack-deliver).
                if (chunkTotal <= 0 || chunkIndex !in 0 until chunkTotal) {
                    messagingLog(
                        MessagingLogLevel.WARN,
                        "VOICE_RX chunk_invalid_range voiceId=${chunkId.take(8)} idx=$chunkIndex total=$chunkTotal envelopeId=${deliver.messageId.take(8)}",
                    )
                    transport.sendDeliveryAck(deliver.messageId)
                    return@runCatching
                }

                val nowMs = Clock.System.now().toEpochMilliseconds()
                val groupId = payload.groupId
                val isGroupAudio = groupId != null
                val useDurable = !isGroupAudio && voiceChunkRepository != null

                if (useDurable) {
                    // === DURABLE PATH (PR-D2b.1) — 1:1 voice over REST/WS ===
                    //
                    // Save chunk → ACK → check count → assemble + insert + delete.
                    // The save happens BEFORE ack-deliver so a relay redelivery
                    // after a lost ack runs `INSERT OR REPLACE` over identical
                    // bytes (no-op) and the count is unchanged. A process death
                    // anywhere between save and assembly is recovered by the
                    // `startReceiving` finalizer.
                    val repo = voiceChunkRepository!!
                    val cutoffMs = nowMs - VOICE_CHUNK_TTL_MS
                    sweepExpiredVoiceChunks(repo, cutoffMs, nowMs)

                    repo.insertChunk(
                        voiceId = chunkId,
                        idx = chunkIndex,
                        total = chunkTotal,
                        conversationId = conversationId,
                        senderPubKeyHex = senderPubKeyHex,
                        mimeType = payload.audioMimeType ?: "audio/m4a",
                        durationMs = payload.audioDurationMs ?: 0L,
                        chunkBytes = chunkBytes,
                        nowMs = nowMs,
                    )
                    messagingLog(
                        MessagingLogLevel.INFO,
                        "VOICE_RX chunk_saved voiceId=${chunkId.take(8)} idx=$chunkIndex total=$chunkTotal source=durable",
                    )

                    transport.sendDeliveryAck(deliver.messageId)
                    messagingLog(
                        MessagingLogLevel.INFO,
                        "VOICE_RX ack_send_after_handler envelopeId=${deliver.messageId.take(8)} voiceId=${chunkId.take(8)} idx=$chunkIndex total=$chunkTotal source=durable",
                    )

                    val received = repo.countChunks(chunkId)
                    if (received == chunkTotal) {
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "VOICE_RX assembly_started voiceId=${chunkId.take(8)} received=$received total=$chunkTotal source=live",
                        )
                        assembleAndDispatch1to1Voice(
                            voiceId = chunkId,
                            conversationId = conversationId,
                            senderPubKeyHex = senderPubKeyHex,
                            senderUsername = payload.senderUsername,
                            mimeType = payload.audioMimeType ?: "audio/m4a",
                            durationMs = payload.audioDurationMs ?: 0L,
                            nowMs = nowMs,
                            origin = "live",
                        )
                    } else {
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "VOICE_RX assembly_waiting voiceId=${chunkId.take(8)} received=$received total=$chunkTotal source=durable",
                        )
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "VOICE_RX handler_complete voiceId=${chunkId.take(8)} result=waiting source=durable",
                        )
                    }
                    return@runCatching
                }

                // === LEGACY IN-MEMORY PATH ===
                //
                // Reached for: group audio (groupId != null — durable schema
                // does not carry groupId so we keep group reassembly in the
                // process-local buffer until a follow-up PR adds groupId to
                // voice_chunks); and tests / Alpha 1 call sites that
                // construct DMS without a `voiceChunkRepository` injection.
                // The buffer's 5-minute TTL is intentionally short for the
                // in-memory case because there is no crash safety anyway.
                val staleCutoff = nowMs - 5 * 60 * 1_000L

                val complete: ByteArray? = reassemblyMutex.withLock {
                    // Sweep entries that have not received a chunk for more than 5 minutes
                    // before inserting so the map cannot grow unbounded when senders abandon
                    // a send mid-way (e.g. killed by Doze while chunking).
                    val staleIds = audioReassemblyBuffer.entries
                        .filter { (_, buf) -> buf.lastUpdatedMs < staleCutoff }
                        .map { (id, _) -> id }
                    staleIds.forEach { audioReassemblyBuffer.remove(it) }

                    messagingLog(
                        MessagingLogLevel.INFO,
                        "VOICE_RX buffer_before chunkId=${chunkId.take(8)} bufferCountBefore=${audioReassemblyBuffer.size} staleSwept=${staleIds.size} source=memory",
                    )
                    val buf = audioReassemblyBuffer.getOrPut(chunkId) { ChunkBuffer() }
                    buf.chunks[chunkIndex] = chunkBytes
                    buf.lastUpdatedMs = nowMs

                    messagingLog(
                        MessagingLogLevel.INFO,
                        "VOICE_RX buffer_added chunkId=${chunkId.take(8)} index=$chunkIndex received=${buf.chunks.size} total=$chunkTotal source=memory",
                    )

                    if (buf.chunks.size == chunkTotal) {
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "VOICE_RX assembly_started chunkId=${chunkId.take(8)} received=${buf.chunks.size} total=$chunkTotal source=memory",
                        )
                        val assembled = (0 until chunkTotal)
                            .flatMap { idx -> buf.chunks[idx]!!.toList() }
                            .toByteArray()
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "VOICE_RX assembly_complete chunkId=${chunkId.take(8)} assembledBytes=${assembled.size} source=memory",
                        )
                        audioReassemblyBuffer.remove(chunkId)
                        assembled
                    } else {
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "VOICE_RX assembly_waiting chunkId=${chunkId.take(8)} received=${buf.chunks.size} total=$chunkTotal source=memory",
                        )
                        null
                    }
                }

                messagingLog(
                    MessagingLogLevel.INFO,
                    "VOICE_RX ack_send_after_handler envelopeId=${deliver.messageId.take(8)} chunkId=${chunkId.take(8)} index=$chunkIndex total=$chunkTotal assembled=${complete != null} source=memory",
                )
                transport.sendDeliveryAck(deliver.messageId)

                if (complete != null) {
                    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                    val assembledB64 = Base64.encode(complete)
                    val groupId = payload.groupId
                    if (groupId != null) {
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "VOICE_RX group_dispatch_start chunkId=${chunkId.take(8)} groupId=${groupId.take(8)}",
                        )
                        groupMessagingService?.handleIncoming(
                            payload.copy(
                                type = MessagePayload.TYPE_GROUP_MESSAGE,
                                audioDataB64 = assembledB64,
                                audioChunkB64 = null,
                                audioChunkId = null,
                                audioChunkIndex = null,
                                audioChunkTotal = null,
                            ),
                            senderPubKeyHex,
                        )
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "VOICE_RX group_dispatch_ok chunkId=${chunkId.take(8)} groupId=${groupId.take(8)}",
                        )
                    } else {
                        val timerSecs = conversationRepository.getDisappearingTimer(conversationId)
                        val expiresAtMs = if (timerSecs > 0L) nowMs + timerSecs * 1_000L else null
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "VOICE_RX message_insert_start chunkId=${chunkId.take(8)} messageId=${deliver.messageId.take(8)} bytes=${complete.size}",
                        )
                        runCatching {
                            messageRepository.insertMessage(
                                MessageEntity(
                                    id = deliver.messageId,
                                    conversationId = conversationId,
                                    ciphertext = ByteArray(0),
                                    plaintextCache = "[AUDIO:$assembledB64]",
                                    sent = false,
                                    status = MessageStatus.DELIVERED,
                                    createdAt = nowMs,
                                    expiresAtMs = expiresAtMs,
                                )
                            )
                        }.fold(
                            onSuccess = {
                                messagingLog(
                                    MessagingLogLevel.INFO,
                                    "VOICE_RX message_insert_ok chunkId=${chunkId.take(8)} messageId=${deliver.messageId.take(8)}",
                                )
                            },
                            onFailure = { ex ->
                                messagingLog(
                                    MessagingLogLevel.WARN,
                                    "VOICE_RX message_insert_fail chunkId=${chunkId.take(8)} messageId=${deliver.messageId.take(8)} error=${ex::class.simpleName} message=${ex.message?.take(120) ?: ""}",
                                )
                            },
                        )
                        val existing = conversationRepository.getConversation(conversationId)
                        val senderName = payload.senderUsername.ifBlank { senderPubKeyHex.take(8) }
                        if (existing == null) {
                            messagingLog(
                                MessagingLogLevel.INFO,
                                "VOICE_RX conversation_upsert_start chunkId=${chunkId.take(8)} convId=${conversationId.take(8)} existing=false",
                            )
                            conversationRepository.upsertConversation(
                                ConversationEntity(
                                    id = conversationId,
                                    theirUsername = senderName,
                                    theirPublicKeyHex = senderPubKeyHex,
                                    lastMessagePreview = "🎤 Voice message",
                                    lastMessageAt = nowMs,
                                    unreadCount = 1,
                                    trustTier = TrustTier.REQUEST,
                                    blocked = false,
                                )
                            )
                            messagingLog(
                                MessagingLogLevel.INFO,
                                "VOICE_RX conversation_upsert_ok chunkId=${chunkId.take(8)} convId=${conversationId.take(8)}",
                            )
                        } else {
                            messagingLog(
                                MessagingLogLevel.INFO,
                                "VOICE_RX conversation_upsert_start chunkId=${chunkId.take(8)} convId=${conversationId.take(8)} existing=true",
                            )
                            conversationRepository.upsertConversation(
                                existing.copy(
                                    lastMessagePreview = "🎤 Voice message",
                                    lastMessageAt = nowMs,
                                    unreadCount = existing.unreadCount + 1,
                                )
                            )
                            messagingLog(
                                MessagingLogLevel.INFO,
                                "VOICE_RX conversation_upsert_ok chunkId=${chunkId.take(8)} convId=${conversationId.take(8)}",
                            )
                        }
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "VOICE_RX emit_incoming_start chunkId=${chunkId.take(8)} messageId=${deliver.messageId.take(8)}",
                        )
                        _incomingMessages.emit(
                            IncomingMessage(
                                id = deliver.messageId,
                                conversationId = conversationId,
                                senderPublicKeyHex = senderPubKeyHex,
                                text = "[AUDIO:$assembledB64]",
                                receivedAt = nowMs,
                            )
                        )
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "VOICE_RX emit_incoming_ok chunkId=${chunkId.take(8)} messageId=${deliver.messageId.take(8)}",
                        )
                        runCatching {
                            messagingLog(
                                MessagingLogLevel.INFO,
                                "VOICE_RX notification_start chunkId=${chunkId.take(8)} messageId=${deliver.messageId.take(8)}",
                            )
                            onNewMessageNotification?.invoke(conversationId, senderName, "🎤 Voice message", senderPubKeyHex)
                            messagingLog(
                                MessagingLogLevel.INFO,
                                "VOICE_RX notification_ok chunkId=${chunkId.take(8)} messageId=${deliver.messageId.take(8)}",
                            )
                        }
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "VOICE_RX handler_complete chunkId=${chunkId.take(8)} result=assembled",
                        )
                    }
                } else {
                    messagingLog(
                        MessagingLogLevel.INFO,
                        "VOICE_RX handler_complete chunkId=${chunkId.take(8)} result=waiting",
                    )
                }
                return@runCatching
            }

            // PR-M1w: encrypted-media voice manifest. The receiver saves a durable
            // download task, ACKs the manifest (so the relay drops the envelope),
            // then kicks off the chunk download coroutine.
            if (payload.type == MessagePayload.TYPE_VOICE_V2) {
                // The manifest JSON lives in payload.text (see sendAudioV2 wrap).
                handleVoiceV2Manifest(
                    deliver         = deliver,
                    payloadText     = payload.text,
                    senderUsername  = payload.senderUsername,
                    senderPubKeyHex = senderPubKeyHex,
                    conversationId  = conversationId,
                    nowMs           = Clock.System.now().toEpochMilliseconds(),
                )
                return@runCatching
            }

            // Route call-signalling messages to CallManager; never store as chat messages.
            if (payload.type in MessagePayload.CALL_TYPES) {
                onCallMessage?.invoke(payload, senderPubKeyHex)
                transport.sendDeliveryAck(deliver.messageId)
                return@runCatching
            }

            // Handle control messages — do not store as chat messages.
            // Every branch ack-delivers BEFORE returning so the relay drops the
            // envelope from its store; without the explicit ack the relay keeps
            // re-delivering the same control message on every reconnect for up
            // to RELAY_ENVELOPE_TTL_SECS (7 days).
            if (payload.type == MessagePayload.TYPE_DELETE && payload.targetMessageId.isNotEmpty()) {
                messageRepository.deleteMessage(payload.targetMessageId)
                _incomingMessages.emit(
                    IncomingMessage(
                        id = payload.targetMessageId,
                        conversationId = conversationId,
                        senderPublicKeyHex = senderPubKeyHex,
                        text = "",
                        receivedAt = Clock.System.now().toEpochMilliseconds(),
                    )
                )
                transport.sendDeliveryAck(deliver.messageId)
                return@runCatching
            }
            if (payload.type == MessagePayload.TYPE_EDIT && payload.targetMessageId.isNotEmpty()) {
                messageRepository.updateMessageText(payload.targetMessageId, payload.text)
                _incomingMessages.emit(
                    IncomingMessage(
                        id = payload.targetMessageId,
                        conversationId = conversationId,
                        senderPublicKeyHex = senderPubKeyHex,
                        text = payload.text,
                        receivedAt = Clock.System.now().toEpochMilliseconds(),
                    )
                )
                transport.sendDeliveryAck(deliver.messageId)
                return@runCatching
            }
            if (payload.type == MessagePayload.TYPE_DISAPPEARING_TIMER) {
                val secs = payload.disappearingTimerSecs ?: 0L
                // Only apply non-zero timers from peers — peer cannot silently disable
                // disappearing messages on the local device (local user controls Off via UI).
                if (secs > 0L) {
                    conversationRepository.setDisappearingTimer(conversationId, secs)
                }
                transport.sendDeliveryAck(deliver.messageId)
                return@runCatching
            }
            if (payload.type == MessagePayload.TYPE_REACTION && payload.targetMessageId.isNotEmpty()) {
                val em = payload.emoji
                val repo = reactionRepository
                messagingLog(
                    MessagingLogLevel.INFO,
                    "Reaction received: target=${payload.targetMessageId.take(12)}… " +
                        "sender=${senderPubKeyHex.take(16)}… emoji=${em ?: "<null>"} " +
                        "repoSet=${repo != null}",
                )
                if (em == null || repo == null) {
                    transport.sendDeliveryAck(deliver.messageId)
                    return@runCatching
                }
                if (em.isEmpty()) {
                    repo.deleteReaction(payload.targetMessageId, senderPubKeyHex)
                } else {
                    repo.upsertReaction(
                        messageId = payload.targetMessageId,
                        senderKeyHex = senderPubKeyHex,
                        emoji = em,
                        createdAt = Clock.System.now().toEpochMilliseconds(),
                    )
                }
                messagingLog(MessagingLogLevel.INFO, "Reaction upserted/deleted OK")
                transport.sendDeliveryAck(deliver.messageId)
                return@runCatching
            }
            if (payload.type == MessagePayload.TYPE_PIN && payload.targetMessageId.isNotEmpty()) {
                // Whoever sent the wire message is the pinner. Store their
                // pubkey so the banner can render "Pinned by <username>".
                // On unpin (pinned=false) we clear the column to null so the
                // next pin gets a fresh attribution.
                val pinnedFlag = payload.pinned ?: false
                messageRepository.pinMessage(
                    messageId = payload.targetMessageId,
                    pinned = pinnedFlag,
                    pinnedByPubkey = if (pinnedFlag) senderPubKeyHex else null,
                )
                transport.sendDeliveryAck(deliver.messageId)
                return@runCatching
            }
            if (payload.type == MessagePayload.TYPE_KEY_ROTATION) {
                val existing = conversationRepository.getConversation(conversationId)
                if (existing != null && existing.theirPublicKeyHex != senderPubKeyHex) {
                    conversationRepository.upsertConversation(
                        existing.copy(
                            theirPublicKeyHex = senderPubKeyHex,
                            isVerified = false,
                            identityKeyChangedAt = Clock.System.now().toEpochMilliseconds(),
                        )
                    )
                    sessionManager.deleteSession(conversationId)
                }
                transport.sendDeliveryAck(deliver.messageId)
                return@runCatching
            }
            if (payload.type == MessagePayload.TYPE_READ_RECEIPT && payload.targetMessageId.isNotEmpty()) {
                // The peer has read one of our outgoing messages. Update its
                // status locally — no DB row is created for the receipt itself.
                // C-2: receipt arrived via the sealed Double Ratchet pipeline,
                // so the relay never saw `from`, `to`, or `messageId`.
                messageRepository.updateStatus(payload.targetMessageId, MessageStatus.READ)
                transport.sendDeliveryAck(deliver.messageId)
                return@runCatching
            }

            val nowMs = Clock.System.now().toEpochMilliseconds()
            val timerSecs = conversationRepository.getDisappearingTimer(conversationId)
            val expiresAtMs = if (timerSecs > 0L) nowMs + timerSecs * 1_000L else null

            messagingLog(
                MessagingLogLevel.INFO,
                "Inserting message into DB: id=${deliver.messageId.take(12)}… conv=${conversationId.take(24)}…",
            )
            messageRepository.insertMessage(
                MessageEntity(
                    id = deliver.messageId,
                    conversationId = conversationId,
                    ciphertext = ciphertext,
                    plaintextCache = payload.text,
                    sent = false,
                    status = MessageStatus.DELIVERED,
                    createdAt = nowMs,
                    expiresAtMs = expiresAtMs,
                )
            )
            messagingLog(MessagingLogLevel.INFO, "DB insertMessage OK")

            // Create conversation as REQUEST if unknown sender, keep TRUSTED if already known.
            val existing = conversationRepository.getConversation(conversationId)
            if (existing == null) {
                val senderName = payload.senderUsername.ifBlank { senderPubKeyHex.take(8) }
                // Detect key rotation: existing conversation with same username but different key.
                val prevByName = conversationRepository.getActiveConversations()
                    .firstOrNull { it.theirUsername == senderName && it.theirPublicKeyHex != senderPubKeyHex }
                val keyChangedAt = if (prevByName != null) Clock.System.now().toEpochMilliseconds() else null
                messagingLog(
                    MessagingLogLevel.INFO,
                    "Creating new conversation as REQUEST: conv=${conversationId.take(24)}… " +
                        "sender=$senderName — will appear in Message Requests, NOT in main chat list " +
                        "until accepted (this is by design; see qa_report_2026_04_24.md BUG-A).",
                )
                conversationRepository.upsertConversation(
                    ConversationEntity(
                        id = conversationId,
                        theirUsername = senderName,
                        theirPublicKeyHex = senderPubKeyHex,
                        lastMessagePreview = previewText(payload.text),
                        lastMessageAt = Clock.System.now().toEpochMilliseconds(),
                        unreadCount = 1,
                        trustTier = TrustTier.REQUEST,
                        blocked = false,
                        identityKeyChangedAt = keyChangedAt,
                    )
                )
            } else {
                messagingLog(
                    MessagingLogLevel.INFO,
                    "Updating existing conversation: conv=${conversationId.take(24)}… " +
                        "trustTier=${existing.trustTier} (kept) unreadCount=${existing.unreadCount + 1}",
                )
                // Auto-sync the contact's username if our local label is a
                // pubkey-fallback ("b97d6aed" — first 8 chars of hex) AND
                // the incoming message carries a non-empty senderUsername.
                // Reproduces "added by key without username" → username
                // appears in chat header on first message receive.
                // 2026-04-30 bug A.
                val pubKeyFallbackPrefix = senderPubKeyHex.take(8)
                val labelLooksLikePubkeyFallback =
                    existing.theirUsername.equals(pubKeyFallbackPrefix, ignoreCase = true) ||
                    existing.theirUsername.isBlank()
                val incomingUsername = payload.senderUsername.trim()
                val updatedUsername = if (
                    labelLooksLikePubkeyFallback &&
                    incomingUsername.isNotEmpty() &&
                    incomingUsername != existing.theirUsername
                ) {
                    messagingLog(
                        MessagingLogLevel.INFO,
                        "Auto-syncing contact username: '${existing.theirUsername}' → " +
                            "'$incomingUsername' (label was pubkey-fallback)",
                    )
                    incomingUsername
                } else {
                    existing.theirUsername
                }
                conversationRepository.upsertConversation(
                    existing.copy(
                        theirUsername = updatedUsername,
                        lastMessagePreview = previewText(payload.text),
                        lastMessageAt = Clock.System.now().toEpochMilliseconds(),
                        unreadCount = existing.unreadCount + 1,
                    )
                )
            }

            messagingLog(MessagingLogLevel.INFO, "Emitting IncomingMessage to UI flow")
            _incomingMessages.emit(
                IncomingMessage(
                    id = deliver.messageId,
                    conversationId = conversationId,
                    senderPublicKeyHex = senderPubKeyHex,
                    text = payload.text,
                    receivedAt = Clock.System.now().toEpochMilliseconds(),
                )
            )

            val senderName = payload.senderUsername.ifBlank { senderPubKeyHex.take(8) }
            messagingLog(
                MessagingLogLevel.INFO,
                "Invoking onNewMessageNotification callback (null=${onNewMessageNotification == null})",
            )
            // Defensive: the platform notification callback may throw on pre-signed URLs
            // or when background restrictions kick in. A thrown exception here used to
            // propagate into the silent onFailure below and lose the stack entirely.
            runCatching {
                onNewMessageNotification?.invoke(conversationId, senderName, previewText(payload.text), senderPubKeyHex)
            }.onFailure { notifErr ->
                messagingLog(
                    MessagingLogLevel.ERROR,
                    "onNewMessageNotification threw (${notifErr::class.simpleName}): ${notifErr.message}",
                    notifErr,
                )
            }
            // Tell the relay to drop this envelope from its store now that we
            // have safely persisted + emitted it. Without this the relay will
            // re-deliver the same message on every reconnect for up to
            // RELAY_ENVELOPE_TTL_SECS (7 days). INSERT OR IGNORE in the
            // messages table makes any duplication harmless, but explicit
            // ack-deliver is what actually frees server-side memory.
            transport.sendDeliveryAck(deliver.messageId)

            messagingLog(
                MessagingLogLevel.INFO,
                "handleDeliver DONE for id=${deliver.messageId.take(12)}… (ack-deliver sent)",
            )
        }.onFailure { e ->
            // Previously this branch was silent ("avoids leaking error details"). That policy hides
            // legitimate bugs (decrypt mismatch, DB unique-constraint, parse error, UI callback
            // throwing) and produces crashes with no context. Log with full stack so a future QA
            // run on a physical device yields actionable diagnostics.
            messagingLog(
                MessagingLogLevel.ERROR,
                "handleDeliver FAILED for id=${deliver.messageId.take(12)}… (${e::class.simpleName}): ${e.message}",
                e,
            )
        }
        } finally {
            processingLock.withLock { activeProcessing.remove(deliver.messageId) }
        }
    }

    /**
     * Encrypts [payload] under the Double Ratchet and ships it as a
     * sealed envelope — identical to the send path in [sendMessage]
     * but without persisting a DB row or updating conversation state.
     *
     * Used for control messages that must not appear as chat bubbles
     * (currently: read receipts). The generated messageId is ephemeral
     * and is only used as the relay's deduplication key.
     *
     * C-2: replaces the old plaintext [RelayMessage.ReadReceipt] path so
     * the relay never learns `from`, `to`, or the referenced messageId.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private suspend fun sendSealedPayload(
        payload: MessagePayload,
        conversationId: String,
        theirPublicKeyHex: String,
    ) {
        val plaintext = json.encodeToString(payload).encodeToByteArray()
        val encrypted = try {
            encryptUnderLock(
                conversationId = conversationId,
                recipientPublicKeyHex = theirPublicKeyHex,
                plaintext = plaintext,
            )
        } catch (e: Exception) {
            messagingLog(
                MessagingLogLevel.WARN,
                "sendSealedPayload: encrypt failed for type=${payload.type} — dropping. ${e.message}",
            )
            return
        }
        val ciphertext = json.encodeToString(encrypted).encodeToByteArray()
        val paddedCiphertext = MessagePadding.pad(ciphertext)
        transport.send(
            RelayMessage.Send(
                to = theirPublicKeyHex,
                from = "",
                sealedSender = Base64.encode(
                    SealedSender.seal(identity.publicKeyHex, hexToBytes(theirPublicKeyHex))
                ),
                payload = paddedCiphertext.encodeBase64(),
                messageId = uuid4().toString(),
            )
        )
    }

    override suspend fun markConversationRead(
        conversationId: String,
        theirPublicKeyHex: String,
        sendReceipt: Boolean,
    ) {
        val unreadMessages = messageRepository.getMessages(conversationId)
            .filter { !it.sent && it.status != MessageStatus.READ }
        unreadMessages.forEach { msg ->
            if (sendReceipt) {
                // C-2: route the read receipt through the sealed Double Ratchet
                // pipeline. The relay sees just another sealed envelope — no
                // `from`, `to`, or `messageId` in plaintext.
                sendSealedPayload(
                    payload = MessagePayload(
                        type = MessagePayload.TYPE_READ_RECEIPT,
                        targetMessageId = msg.id,
                        sentAt = Clock.System.now().toEpochMilliseconds(),
                        senderUsername = identity.username,
                    ),
                    conversationId = conversationId,
                    theirPublicKeyHex = theirPublicKeyHex,
                )
            }
            messageRepository.updateStatus(msg.id, MessageStatus.READ)
        }
        conversationRepository.resetUnread(conversationId)
    }

    override suspend fun sendCallSignal(
        recipientPublicKeyHex: String,
        payload: MessagePayload,
    ): Result<Unit> = runCatching {
        val conversationId = deriveConversationId(recipientPublicKeyHex)
        sendSealedPayload(payload, conversationId, recipientPublicKeyHex)
    }

    override suspend fun sendGroupControlMessage(
        toPubKeyHex: String,
        payload: MessagePayload,
    ): Result<Unit> = runCatching {
        val conversationId = deriveConversationId(toPubKeyHex)
        sendSealedPayload(payload, conversationId, toPubKeyHex)
    }

    private fun deriveConversationId(theirPublicKeyHex: String): String {
        val keys = listOf(identity.publicKeyHex, theirPublicKeyHex).sorted()
        return "${keys[0]}_${keys[1]}"
    }

    /**
     * Re-attempt every message currently in [MessageStatus.WAITING_FOR_RECIPIENT_BUNDLE].
     * Called by AppContainer on every WS reconnect and on a periodic
     * ticker (60-second cadence) so a peer who comes online and
     * publishes their bundle gets their backlog flushed automatically.
     *
     * Each retry calls [sendMessage] again with the original message
     * id + plaintext. On success the WAITING row is replaced (insertOrIgnore
     * by id) — to swap the placeholder ciphertext for the real one we
     * delete-then-reinsert. PR C-followup-3.
     *
     * Idempotent: messages that succeed transition out of WAITING; the
     * next sweep finds nothing to do. Messages that still fail (peer
     * still has no bundle) stay in WAITING for the next sweep.
     */
    override suspend fun retryWaitingMessages(): Result<Int> = runCatching {
        // Snapshot the WAITING set first so a successful retry that
        // mutates state doesn't shift the iteration cursor underneath us.
        val convIds = conversationRepository.getAllConversations()
            .map { it.id }
        var attempts = 0
        for (convId in convIds) {
            val msgs = messageRepository.getMessages(convId)
                .filter { it.status == MessageStatus.WAITING_FOR_RECIPIENT_BUNDLE }
            if (msgs.isEmpty()) continue

            val conv = conversationRepository.getConversation(convId) ?: continue
            for (m in msgs) {
                attempts++
                // Drop the placeholder row first — sendMessage will
                // re-insert with real ciphertext on success, or
                // re-insert another WAITING row on continued failure.
                messageRepository.deleteMessage(m.id)
                sendMessage(
                    OutgoingMessage(
                        id = m.id,
                        conversationId = conv.id,
                        recipientPublicKeyHex = conv.theirPublicKeyHex,
                        text = m.plaintextCache.orEmpty(),
                    ),
                )
            }
        }
        attempts
    }

    override suspend fun deleteMessageForBoth(
        messageId: String,
        conversationId: String,
        recipientPublicKeyHex: String,
    ): Result<Unit> = runCatching {
        val payload = json.encodeToString(
            MessagePayload(
                text = "",
                sentAt = Clock.System.now().toEpochMilliseconds(),
                senderUsername = identity.username,
                type = MessagePayload.TYPE_DELETE,
                targetMessageId = messageId,
            )
        ).encodeToByteArray()
        val encrypted = encryptUnderLock(
            conversationId = conversationId,
            recipientPublicKeyHex = recipientPublicKeyHex,
            plaintext = payload,
        )
        val ciphertext = json.encodeToString(encrypted).encodeToByteArray()
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        transport.send(
            RelayMessage.Send(
                to = recipientPublicKeyHex,
                from = "",
                sealedSender = Base64.encode(
                    SealedSender.seal(identity.publicKeyHex, hexToBytes(recipientPublicKeyHex))
                ),
                payload = MessagePadding.pad(ciphertext).encodeBase64(),
                messageId = uuid4().toString(),
            )
        )
        messageRepository.deleteMessage(messageId)
    }

    override suspend fun sendDisappearingTimerUpdate(
        timerSecs: Long,
        conversationId: String,
        recipientPublicKeyHex: String,
    ): Result<Unit> = runCatching {
        val payload = json.encodeToString(
            MessagePayload(
                text = "",
                sentAt = Clock.System.now().toEpochMilliseconds(),
                senderUsername = identity.username,
                type = MessagePayload.TYPE_DISAPPEARING_TIMER,
                disappearingTimerSecs = timerSecs,
            )
        ).encodeToByteArray()
        val encrypted = encryptUnderLock(
            conversationId = conversationId,
            recipientPublicKeyHex = recipientPublicKeyHex,
            plaintext = payload,
        )
        val ciphertext = json.encodeToString(encrypted).encodeToByteArray()
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        transport.send(
            RelayMessage.Send(
                to = recipientPublicKeyHex,
                from = "",
                sealedSender = Base64.encode(
                    SealedSender.seal(identity.publicKeyHex, hexToBytes(recipientPublicKeyHex))
                ),
                payload = MessagePadding.pad(ciphertext).encodeBase64(),
                messageId = uuid4().toString(),
            )
        )
    }

    override suspend fun editMessageForBoth(
        messageId: String,
        newText: String,
        conversationId: String,
        recipientPublicKeyHex: String,
    ): Result<Unit> = runCatching {
        val payload = json.encodeToString(
            MessagePayload(
                text = newText,
                sentAt = Clock.System.now().toEpochMilliseconds(),
                senderUsername = identity.username,
                type = MessagePayload.TYPE_EDIT,
                targetMessageId = messageId,
            )
        ).encodeToByteArray()
        val encrypted = encryptUnderLock(
            conversationId = conversationId,
            recipientPublicKeyHex = recipientPublicKeyHex,
            plaintext = payload,
        )
        val ciphertext = json.encodeToString(encrypted).encodeToByteArray()
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        transport.send(
            RelayMessage.Send(
                to = recipientPublicKeyHex,
                from = "",
                sealedSender = Base64.encode(
                    SealedSender.seal(identity.publicKeyHex, hexToBytes(recipientPublicKeyHex))
                ),
                payload = MessagePadding.pad(ciphertext).encodeBase64(),
                messageId = uuid4().toString(),
            )
        )
        messageRepository.updateMessageText(messageId, newText)
    }

    override suspend fun sendReaction(
        messageId: String,
        conversationId: String,
        recipientPublicKeyHex: String,
        emoji: String,
    ): Result<Unit> = runCatching {
        messagingLog(
            MessagingLogLevel.INFO,
            "sendReaction: target=${messageId.take(12)}… emoji=${emoji.ifEmpty { "<remove>" }} " +
                "to=${recipientPublicKeyHex.take(16)}…",
        )
        val payload = json.encodeToString(
            MessagePayload(
                text = "",
                sentAt = Clock.System.now().toEpochMilliseconds(),
                senderUsername = identity.username,
                type = MessagePayload.TYPE_REACTION,
                targetMessageId = messageId,
                emoji = emoji,
            )
        ).encodeToByteArray()
        val encrypted = encryptUnderLock(
            conversationId = conversationId,
            recipientPublicKeyHex = recipientPublicKeyHex,
            plaintext = payload,
        )
        val ciphertext = json.encodeToString(encrypted).encodeToByteArray()
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        transport.send(
            RelayMessage.Send(
                to = recipientPublicKeyHex,
                from = "",
                sealedSender = Base64.encode(
                    SealedSender.seal(identity.publicKeyHex, hexToBytes(recipientPublicKeyHex))
                ),
                payload = MessagePadding.pad(ciphertext).encodeBase64(),
                messageId = uuid4().toString(),
            )
        )
        // Also apply locally so the sender sees their own reaction immediately
        val repo = reactionRepository
        if (repo != null) {
            if (emoji.isEmpty()) {
                repo.deleteReaction(messageId, identity.publicKeyHex)
            } else {
                repo.upsertReaction(
                    messageId = messageId,
                    senderKeyHex = identity.publicKeyHex,
                    emoji = emoji,
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                )
            }
        }
    }

    override suspend fun pinMessageForBoth(
        messageId: String,
        conversationId: String,
        recipientPublicKeyHex: String,
        pinned: Boolean,
    ): Result<Unit> = runCatching {
        val payload = json.encodeToString(
            MessagePayload(
                text = "",
                sentAt = Clock.System.now().toEpochMilliseconds(),
                senderUsername = identity.username,
                type = MessagePayload.TYPE_PIN,
                targetMessageId = messageId,
                pinned = pinned,
            )
        ).encodeToByteArray()
        val encrypted = encryptUnderLock(
            conversationId = conversationId,
            recipientPublicKeyHex = recipientPublicKeyHex,
            plaintext = payload,
        )
        val ciphertext = json.encodeToString(encrypted).encodeToByteArray()
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        transport.send(
            RelayMessage.Send(
                to = recipientPublicKeyHex,
                from = "",
                sealedSender = Base64.encode(
                    SealedSender.seal(identity.publicKeyHex, hexToBytes(recipientPublicKeyHex))
                ),
                payload = MessagePadding.pad(ciphertext).encodeBase64(),
                messageId = uuid4().toString(),
            )
        )
        // Apply locally so the sender sees the pin state immediately. We
        // are the pinner — record our own pubkey so the banner reads
        // "Pinned by you" on this side.
        messageRepository.pinMessage(
            messageId = messageId,
            pinned = pinned,
            pinnedByPubkey = if (pinned) identity.publicKeyHex else null,
        )
    }

    /** Strip reply prefix "> quote\n" and substitute media markers with
     *  human-readable labels so chat list and notifications never leak the
     *  raw `[AUDIO:base64...]` envelope. */
    private fun previewText(text: String): String {
        // Body to inspect: drop reply quote first.
        val body = if (text.startsWith("> ")) {
            val nl = text.indexOf('\n')
            if (nl in 1 until text.length) text.substring(nl + 1) else text
        } else {
            text
        }
        return when {
            body.startsWith("[AUDIO:") -> "🎤 Voice message"
            body.startsWith("[IMAGE:") -> "🖼️ Photo"
            body.startsWith("[FILE:")  -> "📎 File"
            else                       -> body.take(60)
        }
    }

    // ── PR-M1w receive path ────────────────────────────────────────────────────

    /**
     * Handles an incoming [TYPE_VOICE_V2] manifest envelope (§4, PR-M1w).
     *
     * Contract (Q4): ACK is sent ONLY after the durable task INSERT commits.
     * If [voiceV2DownloadRepository] is null (degraded mode), we do NOT ack —
     * the relay will redeliver on the next poll so the message is not lost.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private suspend fun handleVoiceV2Manifest(
        deliver: RelayMessage.Deliver,
        payloadText: String,
        senderUsername: String,
        senderPubKeyHex: String,
        conversationId: String,
        nowMs: Long,
    ) {
        val repo = voiceV2DownloadRepository
        if (repo == null) {
            messagingLog(
                MessagingLogLevel.WARN,
                "MEDIA_RX no_download_repo envelopeId=${deliver.messageId.take(8)} — manifest NOT acked, will redeliver",
            )
            return
        }

        // Step 1 — Parse manifest
        val manifest = runCatching {
            json.decodeFromString<VoiceManifestV2>(payloadText)
        }.getOrElse { ex ->
            messagingLog(
                MessagingLogLevel.WARN,
                "MEDIA_RX manifest_parse_fail envelopeId=${deliver.messageId.take(8)} error=${ex::class.simpleName}",
            )
            transport.sendDeliveryAck(deliver.messageId)
            return
        }

        // Step 1b — Validate manifest fields (security: reject before any DB write)
        val validationFailure = validateVoiceV2Manifest(manifest)
        if (validationFailure != null) {
            messagingLog(
                MessagingLogLevel.WARN,
                "MEDIA_RX manifest_invalid envelopeId=${deliver.messageId.take(8)} " +
                    "reason=$validationFailure mediaId=${manifest.mediaId.take(8)}",
            )
            transport.sendDeliveryAck(deliver.messageId)
            return
        }

        // Step 2 — Idempotency check
        val existing = repo.find(manifest.mediaId)
        if (existing != null) {
            messagingLog(
                MessagingLogLevel.INFO,
                "MEDIA_RX manifest_duplicate mediaId=${manifest.mediaId.take(8)} status=${existing.status}",
            )
            transport.sendDeliveryAck(deliver.messageId)
            return
        }

        // Step 3 — Insert local message row (DOWNLOADING)
        val timerSecs = conversationRepository.getDisappearingTimer(conversationId)
        val expiresAtMs = if (timerSecs > 0L) nowMs + timerSecs * 1_000L else null
        messageRepository.insertMessage(
            MessageEntity(
                id             = manifest.mediaId, // stable PK mirrors D2b.1 voiceId pattern
                conversationId = conversationId,
                ciphertext     = ByteArray(0),
                plaintextCache = "[AUDIO_DOWNLOADING]",
                sent           = false,
                status         = MessageStatus.DOWNLOADING,
                createdAt      = nowMs,
                expiresAtMs    = expiresAtMs,
            )
        )

        // Step 4 — Insert durable download task. MUST commit before ACK.
        repo.insert(
            VoiceV2DownloadRepository.Task(
                mediaId         = manifest.mediaId,
                conversationId  = conversationId,
                senderPubKeyHex = senderPubKeyHex,
                manifestJson    = json.encodeToString(manifest),
                status          = VoiceV2DownloadRepository.STATUS_PENDING,
                chunkCount      = manifest.chunkCount,
                lastAttemptAtMs = 0L,
                failureReason   = null,
                createdAtMs     = nowMs,
            )
        )

        // Step 5 — ACK manifest envelope (durable save already committed above).
        transport.sendDeliveryAck(deliver.messageId)
        messagingLog(
            MessagingLogLevel.INFO,
            "MEDIA_RX manifest_acked_and_queued mediaId=${manifest.mediaId.take(8)} chunks=${manifest.chunkCount}",
        )

        // Step 6 — Emit placeholder to UI
        _incomingMessages.emit(
            IncomingMessage(
                id                 = manifest.mediaId,
                conversationId     = conversationId,
                senderPublicKeyHex = senderPubKeyHex,
                text               = "[AUDIO_DOWNLOADING]",
                receivedAt         = nowMs,
            )
        )
        // R6.1 — Update conversation preview + unreadCount so the Chats list
        // shows the unread badge for incoming voice. Mirrors the normal-message
        // path at line 2067 (text messages). Without unreadCount++ the chat
        // row looked "read" even though the user hadn't opened it (Test #60).
        val existingConv = conversationRepository.getConversation(conversationId) ?: return
        conversationRepository.upsertConversation(
            existingConv.copy(
                lastMessagePreview = "🎤 Voice message",
                lastMessageAt      = nowMs,
                unreadCount        = existingConv.unreadCount + 1,
            )
        )
        // R5-2 — Use senderUsername from wrapped MessagePayload as the
        // notification display name. Falls back to pubkey-truncated form only
        // when the sender didn't set a username. Mirrors the normal-message
        // path at line 2077.
        val senderName = senderUsername.ifBlank { senderPubKeyHex.take(8) }
        onNewMessageNotification?.invoke(conversationId, senderName, "Voice message", senderPubKeyHex)

        // Step 7 — Trigger download (Commit 4 wires runDownloadTask).
        // TODO(PR-M1w Commit4): replace stub with voiceV2DownloadOrchestrator.runDownloadTask(manifest.mediaId)
        scope.launch {
            runCatching { runVoiceV2DownloadTask(manifest.mediaId) }
                .onFailure { ex ->
                    messagingLog(
                        MessagingLogLevel.WARN,
                        "MEDIA_RX download_task_threw mediaId=${manifest.mediaId.take(8)} error=${ex::class.simpleName}",
                    )
                }
        }
    }

    /**
     * Returns the failed field name if the manifest is invalid, null if OK.
     * Security: reject any manifest with wrong key/nonce/sha256 sizes before
     * inserting any rows (prevents relay from poisoning our local DB).
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun validateVoiceV2Manifest(m: VoiceManifestV2): String? {
        if (m.mediaId.isBlank()) return "mediaId"
        if (runCatching { Base64.decode(m.mediaKey) }.getOrNull()?.size != 32) return "mediaKey"
        if (runCatching { Base64.decode(m.nonce) }.getOrNull()?.size != 24) return "nonce"
        if (runCatching { Base64.decode(m.sha256) }.getOrNull()?.size != 32) return "sha256"
        if (m.chunkCount !in 1..256) return "chunkCount"
        if (m.encryptedSizeBytes <= 0) return "encryptedSizeBytes"
        if (m.alg != VoiceManifestV2.ALG) return "alg"
        return null
    }

    /**
     * Download task dispatcher — delegates to [VoiceV2DownloadOrchestrator] when wired.
     * Called from [handleVoiceV2Manifest] and the startReceiving finalizer via scope.launch.
     *
     * When the orchestrator is null (tests that don't inject it), this is a no-op:
     * the task row stays PENDING and the UI shows the spinner indefinitely.
     * Production AppContainer always wires the orchestrator.
     *
     * TODO(PR-M1w §8): after Test #56, flip canSendVoice for RestActive/WsCandidate
     * in TransportCapabilitiesResolver (separate Commit 5 step per design §8).
     */
    internal open suspend fun runVoiceV2DownloadTask(mediaId: String) {
        try {
            voiceV2DownloadOrchestrator?.runDownloadTask(
                mediaId = mediaId,
                onChunkDownloaded = { received, total ->
                    // Receiver row PK == mediaId (see handleVoiceV2Manifest insert path).
                    mediaProgressBus.update(
                        rowId = mediaId,
                        sent = received,
                        total = total,
                        direction = MediaProgressBus.Direction.DOWNLOAD,
                    )
                },
            )
        } finally {
            mediaProgressBus.clear(mediaId)
        }
        // R5-1 — UI/state propagation after download completes.
        // The orchestrator updated `plaintextCache` to `[AUDIO_LOCAL:<path>]` and
        // set status=DELIVERED in the DB, but ChatScreen observes the
        // `incomingMessages` flow rather than polling the DB. Without this emit
        // the bubble stays on `[AUDIO_DOWNLOADING]` even though the audio is
        // on disk and playable (Test #59 root cause).
        val updated = messageRepository.getMessageById(mediaId) ?: return
        // Only emit if the orchestrator actually finalised the row. Failed/pending
        // rows have prefixes other than [AUDIO_LOCAL:]; those will surface via
        // the FAILED-status branch in ChatScreen StatusIcon and don't need a
        // new incoming-message event.
        val finalText = updated.plaintextCache.orEmpty()
        if (!finalText.startsWith("[AUDIO_LOCAL:")) return
        _incomingMessages.emit(
            IncomingMessage(
                id                 = updated.id,
                conversationId     = updated.conversationId,
                senderPublicKeyHex = "", // not surfaced by ChatScreen for self-refresh
                text               = finalText,
                receivedAt         = Clock.System.now().toEpochMilliseconds(),
            )
        )
        messagingLog(
            MessagingLogLevel.INFO,
            "MEDIA_RX message_ready mediaId=${mediaId.take(8)} path=AUDIO_LOCAL",
        )
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun ByteArray.encodeBase64(): String = Base64.encode(this)

@OptIn(ExperimentalEncodingApi::class)
private fun String.decodeBase64Bytes(): ByteArray = Base64.decode(this)

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/**
 * Thrown by [DefaultMessagingService.encryptUnderLock] when the peer
 * has no published prekey bundle on the relay (404 from
 * [phantom.core.transport.PreKeyApi.fetchBundle]). The send path catches
 * this distinctly and stores the message with [phantom.core.storage.MessageStatus.WAITING_FOR_RECIPIENT_BUNDLE]
 * for later retry. Other encrypt failures (signature verify, malformed
 * bundle, signing-key mismatch) bubble through as the original
 * SessionBootstrapException variants. PR C-followup-3.
 */
class PeerBundleMissingException(
    val recipientPubKeyHex: String,
) : Exception(
    "peer ${recipientPubKeyHex.take(16)}… has no published prekey bundle " +
        "(404 from /prekeys/bundle). Message will retry on reconnect.",
)
