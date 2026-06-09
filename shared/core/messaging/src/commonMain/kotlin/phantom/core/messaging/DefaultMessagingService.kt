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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
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
import phantom.core.crypto.RatchetState
import phantom.core.crypto.SealedSender
import phantom.core.identity.IdentityRecord
import phantom.core.identity.IdentitySigningKeyPair
import phantom.core.storage.ConversationEntity
import phantom.core.storage.ConversationRepository
import phantom.core.storage.DecryptFailedEnvelopeRepository
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
    /**
     * PR-MEDIA-UPLOAD-CANCEL2.1 — Android logcat sink for media-tag
     * diagnostics. `messagingLog(...)` writes under tag `PhantomMessaging`,
     * which our standard logcat filters (`PhantomMedia:V PhantomUI:V
     * PhantomTransport:V *:S`) exclude. The cancel path needed to be
     * visible alongside `MEDIA_TX upload_*` from VoiceV2Sender (which goes
     * through this same callback in AppContainer), so the wiring routes
     * both to the same tag. Default is no-op for tests / non-Android.
     */
    private val mediaLog: (String) -> Unit = {},
    /**
     * PR-CRYPTO-SESSION-REPAIR1 (2026-05-29, commit 2/N) — durable
     * hold table for envelopes that produce `Permanent decrypt failure
     * (MAC error)`. NULLABLE + DEFAULT NULL for call-site compatibility
     * with existing tests and the legacy code paths that construct DMS
     * without storage. Production wires the SQLDelight repository
     * through AppContainer.
     *
     * **Not read in commit 2** — wired through the constructor so the
     * dependency is available when commit 3 introduces the hold path.
     * The receive MAC branch still ack-delivers + writes
     * `processed_envelopes.markProcessed(status=FAILED_MAC)` unchanged
     * in this commit.
     */
    private val decryptFailedEnvelopeRepository: DecryptFailedEnvelopeRepository? = null,
    /**
     * PR-CRYPTO-SESSION-REPAIR1 (2026-05-29, commit 2/N) — semantic
     * flag that gates the upcoming hold-on-MAC behaviour. Platform-
     * agnostic: Android wires `BuildConfig.DEBUG`, JVM tests set per-
     * scenario, default `false` keeps existing tests + release builds
     * on the unchanged ack-on-MAC path.
     *
     * **Not branched on in commit 2** — the flag is plumbed through
     * the constructor so commit 3 can read it without touching the
     * constructor again. Grep evidence for invariant 2 (release
     * preserved): no `if (holdMacFailures)` branch exists in this
     * file in commit 2.
     */
    private val holdMacFailures: Boolean = false,
) : MessagingService {

    private val _bootstrapReady = MutableStateFlow(false)
    override val bootstrapReady: StateFlow<Boolean> = _bootstrapReady.asStateFlow()

    /** Called by the platform container after the initial prekey-bundle publish attempt. */
    fun markBootstrapReady() { _bootstrapReady.value = true }

    // PR-M1w: single voice in-flight per conversation. Guarded by mutexFor(conversationId).
    private val voiceSendInProgress = mutableSetOf<String>()

    // PR-MEDIA-UPLOAD-CANCEL1: in-flight voice upload jobs keyed by
    // localMsgId. Populated when `sendAudioV2` launches its upload coroutine
    // on the DMS scope and removed by the same coroutine's `finally`. The
    // X button on the uploading voice bubble routes through
    // `cancelVoiceUpload(...)` which cancels the captured Job here. The
    // separate Mutex below serialises concurrent insert / lookup / remove
    // — `mutableMapOf` is not thread-safe and at least two threads
    // (the DMS scope coroutine and any UI-triggered cancel call) reach it.
    private val voiceUploadJobsLock = Mutex()
    private val voiceUploadJobs = mutableMapOf<String, Job>()
    private val USER_CANCELLED_UPLOAD = "user_cancelled_upload"

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
        /**
         * PR-CRYPTO-SESSION-REPAIR1 commit 5 (2026-05-30) — diagnostic
         * cap on per-envelope replay attempts (architect-locked at 3).
         * Held envelopes whose ciphertext was encrypted under the
         * drifted chain will fail decrypt on every replay; capping
         * stops the loop from re-trying them on EVERY subsequent
         * successful repair until the 24h TTL sweep clears them
         * (commit 6).
         */
        const val MAX_REPLAY_ATTEMPTS_PER_HELD = 3L

        /**
         * PR-CRYPTO-SESSION-REPAIR1 commit 6 (2026-05-31) — local 24h
         * TTL on held decrypt-failed envelopes. Held rows whose
         * `received_at_ms` is older than `nowMs - HELD_ENVELOPE_TTL_MS`
         * are deleted from the local hold table opportunistically at
         * the entry of each replay cycle. This is LOCAL cleanup only:
         * the relay's own envelope TTL is separate and remains
         * authoritative on the server side. TTL eviction does NOT
         * send a delivery ack — the relay's copy is dealt with by
         * the relay's own TTL.
         *
         * Architect-locked at 24h (PR #243 thread, commit 6 mini-lock).
         */
        const val HELD_ENVELOPE_TTL_MS = 24L * 60L * 60L * 1_000L

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

            // ═════════════════════════════════════════════════════════
            // PR-CRYPTO-SESSION-REPAIR1 commit 4 (2026-05-30) — per-
            // conversation suspect check inside the existing per-
            // conversation mutex (architect pre-decision #1: reuse
            // mutexFor, do not introduce a parallel map).
            //
            // When the receive path's hold-on-MAC branch flagged this
            // conversation as `session_suspect=true`, the next outgoing
            // message bypasses tryLoadSession and forces the fresh X3DH
            // bootstrap branch. The flag is cleared only after the
            // local saveSession commits — bootstrap, encrypt, or save
            // failure leaves the flag set so the NEXT outgoing retries
            // the repair (architect-correction 2026-05-29: clear on
            // local crypto commit, NOT on transport.send which runs
            // outside the mutex).
            //
            // Receive ack / markProcessed ownership stays in DMS receive
            // path (architect pre-decision #2). Replay of held envelopes
            // is OUT OF SCOPE for commit 4 — that lands in commit 5.
            // ═════════════════════════════════════════════════════════
            val sessionSuspect =
                conversationRepository.getConversation(conversationId)?.sessionSuspect == true
            if (sessionSuspect) {
                messagingLog(
                    MessagingLogLevel.INFO,
                    "DECRYPT_TRACE repair_armed trigger=outbound_send conv=$convTag",
                )
            }

            messagingLog(
                MessagingLogLevel.INFO,
                "SEND_TRACE session_lookup conv=$convTag suspect=$sessionSuspect",
            )
            val existingState = sessionManager.tryLoadSession(conversationId)
            // Existing-session branch runs ONLY when a session exists
            // AND the conversation is NOT flagged suspect. Suspect flows
            // into the bootstrap branch below regardless of existingState.
            if (existingState != null && !sessionSuspect) {
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

                // PR-CRYPTO-SESSION-REPAIR1 commit 4 (2026-05-30): clear
                // session_suspect ONLY after the local crypto commit
                // (saveSession) has succeeded — still inside mutexFor
                // (conversationId) so the next outgoing send sees the
                // cleared state atomically. If anything above this line
                // throws (prekey fetch, bootstrap, encrypt, afterEncrypt
                // callback, saveSession), the exception propagates out
                // of the mutex and this clear DOES NOT run, leaving the
                // flag at true so the NEXT outgoing retries the repair.
                //
                // Only fires when the bootstrap branch ran BECAUSE of
                // the suspect flag — for a regular first-send
                // (sessionSuspect=false, just a fresh peer), this is a
                // no-op.
                if (sessionSuspect) {
                    conversationRepository.clearSessionSuspect(conversationId)
                    messagingLog(
                        MessagingLogLevel.INFO,
                        "DECRYPT_TRACE repair_done conv=$convTag",
                    )

                    // PR-CRYPTO-SESSION-REPAIR1 commit 5 (2026-05-30) —
                    // best-effort replay of held envelopes for this
                    // conversation now that the fresh ratchet is committed.
                    // Architect-locked guardrails honoured inside
                    // [replayHeldEnvelopesAfterRepair]:
                    //   * fires ONLY after successful repair / saveSession
                    //     (this block);
                    //   * decodes wire_frame_json as inner WireFrame;
                    //   * on success: marks PROCESSED + best-effort
                    //     inserts text messages + acks + deletes row;
                    //   * on failure: bumps replay_attempt_count, leaves
                    //     row held, NEVER re-sets session_suspect;
                    //   * caps replay attempts at
                    //     MAX_REPLAY_ATTEMPTS_PER_HELD = 3;
                    //   * per-conversation (only the conv we just
                    //     repaired);
                    //   * runs inside the existing per-conversation
                    //     mutex so concurrent sends + the replay decrypt
                    //     cannot race on the ratchet state.
                    if (decryptFailedEnvelopeRepository != null) {
                        replayHeldEnvelopesAfterRepair(conversationId, convTag)
                    }
                }
                wireFrame
            }
        }
    }

    /**
     * PR-CRYPTO-SESSION-REPAIR1 commit 5 / 5a / 5b
     * (2026-05-30 / 2026-05-31) — replay loop.
     *
     * Best-effort decrypt of every held envelope for [conversationId]
     * under the fresh ratchet that was just committed by
     * [encryptUnderLock]. ALL invocation paths reach this method
     * INSIDE the per-conversation mutex acquired by encryptUnderLock,
     * so concurrent sends in the same conversation cannot race on
     * the ratchet state.
     *
     * Architect-locked semantics (commit 5a Safety Patch + commit
     * 5b Ratchet Commit Ordering):
     *
     *   - No replay exception MAY escape this method. The outer
     *     per-envelope guard catches any throwable (decode, decrypt,
     *     saveSession, db, transport, callback), best-effort bumps
     *     `replay_attempt_count`, logs `replay_unexpected_exception`,
     *     and CONTINUES to the next entry. The trigger-send that
     *     invoked us MUST NEVER be aborted by replay (commit 5a).
     *
     *   - Replay SUCCESS = decrypt OK + payload is TYPE_MESSAGE +
     *     text durably inserted + ratchet advanced + conv / emit /
     *     notify + ledger + ack + delete-held all OK. Order is
     *     strict per [attemptReplayOne] (commit 5b):
     *       1. decode inner WireFrame from `wire_frame_json`;
     *       2. load fresh session (the one just saveSession'd by
     *          encryptUnderLock);
     *       3. ratchet.decrypt IN MEMORY (do NOT save yet);
     *       4. decode `MessagePayload` JSON;
     *       5. gate on `payload.type == TYPE_MESSAGE` (complex types
     *          return false BEFORE saveSession — commit 5b);
     *       6. insert text row with disappearing-timer expiry
     *          (return false BEFORE saveSession on insert failure —
     *          commit 5b);
     *       7. saveSession with the advanced state (ONLY NOW —
     *          commit 5b architect rule);
     *       8. upsert conversation + `_incomingMessages.emit` +
     *          `invokeIncomingNotificationCallback("text", …)`;
     *       9. `processedEnvelopeRepository.markProcessed PROCESSED`;
     *      10. `transport.sendDeliveryAck`;
     *      11. `decryptFailedEnvelopeRepository.deleteByEnvelopeId`.
     *     Failure at ANY step → recordReplayAttempt + leave row
     *     held + no ack + no delete + no setSessionSuspect. Failures
     *     in steps 1–6 leave the ratchet UN-ADVANCED so the same
     *     row remains decryptable on the next replay cycle.
     *
     *   - Complex (non-`TYPE_MESSAGE`) payloads: row stays held +
     *     attempt recorded + ratchet UN-ADVANCED + NO ack + NO
     *     delete. When the complex-payload replay handler ships in
     *     a follow-up commit, the same wireFrame is still
     *     decryptable under the un-advanced chain.
     *
     *   - **MUST NOT** call setSessionSuspect on ANY failure path —
     *     the anti-loop guarantee from architect pre-decision #3
     *     invariant 4. Replay is best-effort; the user's repaired
     *     send already succeeded, so re-suspecting on a replay
     *     decrypt failure would cause an infinite repair loop.
     *
     *   - Rows whose `replay_attempt_count >= MAX_REPLAY_ATTEMPTS_PER_HELD`
     *     are skipped without further decrypt attempts (and without
     *     bumping the counter further).
     */
    private suspend fun replayHeldEnvelopesAfterRepair(
        conversationId: String,
        convTag: String,
    ) {
        val repo = decryptFailedEnvelopeRepository ?: return

        // PR-CRYPTO-SESSION-REPAIR1 commit 6 (2026-05-31): opportunistic
        // 24h TTL sweep BEFORE listByConversation so expired held rows
        // are dropped before any replay/decrypt happens against them.
        // LOCAL cleanup only — no ack, no markProcessed; the relay's
        // own envelope TTL is the authoritative server-side cleanup.
        // The sweep is global (deletes across all conversations) which
        // is acceptable because the call is already serialized by
        // encryptUnderLock's per-conversation mutex; a stale row on
        // another conv that gets evicted here is identical to what
        // that conv's own next replay cycle would have done.
        val ttlCutoffMs = Clock.System.now().toEpochMilliseconds() - HELD_ENVELOPE_TTL_MS
        messagingLog(
            MessagingLogLevel.INFO,
            "DECRYPT_TRACE held_ttl_sweep_start conv=$convTag cutoffMs=$ttlCutoffMs",
        )
        try {
            repo.deleteOlderThan(ttlCutoffMs)
        } catch (e: Throwable) {
            // TTL sweep is best-effort. A failure here does NOT abort
            // the rest of the replay loop — surviving rows still get
            // their decrypt attempts.
            messagingLog(
                MessagingLogLevel.WARN,
                "DECRYPT_TRACE held_ttl_sweep_fail conv=$convTag " +
                    "errorClass=${e::class.simpleName}",
            )
        }
        messagingLog(
            MessagingLogLevel.INFO,
            "DECRYPT_TRACE held_ttl_sweep_done conv=$convTag",
        )

        val held = repo.listByConversation(conversationId)
        if (held.isEmpty()) {
            messagingLog(
                MessagingLogLevel.INFO,
                "DECRYPT_TRACE replay_loop_empty conv=$convTag",
            )
            return
        }
        messagingLog(
            MessagingLogLevel.INFO,
            "DECRYPT_TRACE replay_loop_start conv=$convTag count=${held.size}",
        )

        for (entry in held) {
            val msgTag = entry.envelopeId.take(8)

            if (entry.replayAttemptCount >= MAX_REPLAY_ATTEMPTS_PER_HELD) {
                messagingLog(
                    MessagingLogLevel.INFO,
                    "DECRYPT_TRACE replay_skip_max_attempts conv=$convTag " +
                        "msgId=$msgTag attemptCount=${entry.replayAttemptCount}",
                )
                continue
            }

            val nowMs = Clock.System.now().toEpochMilliseconds()
            val attemptNo = entry.replayAttemptCount + 1

            messagingLog(
                MessagingLogLevel.INFO,
                "DECRYPT_TRACE replay_attempt conv=$convTag msgId=$msgTag attemptNo=$attemptNo",
            )

            // OUTER SAFETY NET (commit 5a). Any throwable that the
            // inner steps do not catch + handle MUST stop here. The
            // trigger-send in encryptUnderLock is one stack-frame
            // above us; an escaping replay exception would abort it
            // and produce silent send failures.
            val replayedOk = try {
                attemptReplayOne(
                    entry = entry,
                    msgTag = msgTag,
                    convTag = convTag,
                    conversationId = conversationId,
                    attemptNo = attemptNo,
                    nowMs = nowMs,
                )
            } catch (e: Throwable) {
                messagingLog(
                    MessagingLogLevel.WARN,
                    "DECRYPT_TRACE replay_unexpected_exception conv=$convTag msgId=$msgTag " +
                        "attemptNo=$attemptNo errorClass=${e::class.simpleName}",
                )
                false
            }

            if (!replayedOk) {
                // Inner path may have already recorded; this catch
                // path also records, idempotent because we only call
                // it when the inner returned false (no recording yet)
                // OR when an unexpected throwable escaped. The
                // ledger's `recordReplayAttempt` is itself wrapped to
                // never re-throw from this safety net.
                //
                // CRITICAL: must NOT setSessionSuspect — anti-loop.
                runCatching { repo.recordReplayAttempt(entry.envelopeId, nowMs) }
                    .onFailure { e ->
                        messagingLog(
                            MessagingLogLevel.WARN,
                            "DECRYPT_TRACE replay_record_attempt_fail conv=$convTag " +
                                "msgId=$msgTag errorClass=${e::class.simpleName}",
                        )
                    }
            }
        }

        messagingLog(
            MessagingLogLevel.INFO,
            "DECRYPT_TRACE replay_loop_done conv=$convTag",
        )
    }

    /**
     * PR-CRYPTO-SESSION-REPAIR1 commit 5b (2026-05-31) — replay
     * worker for a single held envelope.
     *
     * Architect-locked step order — the receive ratchet MUST NOT be
     * advanced (`saveSession`) until the replayed plaintext is known
     * to be a supported text payload AND durably inserted into the
     * messages table. Otherwise a held row whose decrypt-then-insert
     * sequence fails would leave the ratchet committed to a state
     * past the held envelope's message index, making the same row
     * un-retryable on the next replay cycle even though the relay
     * still has the (un-acked) ciphertext.
     *
     * Steps:
     *   1. decode `WireFrame`;
     *   2. load fresh session;
     *   3. `ratchet.decrypt` IN MEMORY — do NOT save yet;
     *   4. decode `MessagePayload`;
     *   5. require `TYPE_MESSAGE` — return false BEFORE save on
     *      complex payloads (architect tightened in 5b);
     *   6. insert text row with disappearing-timer expiry — return
     *      false BEFORE save on insert failure;
     *   7. `saveSession(newState)` — commit ratchet (only NOW);
     *   8. upsert conversation + emit `_incomingMessages` + notify;
     *   9. `processedEnvelopeRepository.markProcessed`;
     *  10. `transport.sendDeliveryAck`;
     *  11. `decryptFailedEnvelopeRepository.deleteByEnvelopeId`.
     *
     * Returns `true` iff every step (1–11) completed. Returns
     * `false` on any KNOWN failure (logged with a specific tag);
     * throws only on UNKNOWN failures, which the outer guard in
     * [replayHeldEnvelopesAfterRepair] catches.
     *
     * On `false`, the caller MUST call `recordReplayAttempt` exactly
     * once and MUST NOT touch `sessionSuspect`. On `true`, the
     * caller does nothing — bookkeeping is complete inside.
     */
    private suspend fun attemptReplayOne(
        entry: DecryptFailedEnvelopeRepository.Entry,
        msgTag: String,
        convTag: String,
        conversationId: String,
        attemptNo: Long,
        nowMs: Long,
    ): Boolean {
        val repo = decryptFailedEnvelopeRepository ?: return false

        // Step 1: decode inner WireFrame from the stored JSON —
        // architect-locked semantic (PR #243 95c7aae0).
        val wireFrame = try {
            json.decodeFromString(WireFrame.serializer(), entry.wireFrameJson)
        } catch (e: Throwable) {
            messagingLog(
                MessagingLogLevel.WARN,
                "DECRYPT_TRACE replay_decode_fail conv=$convTag msgId=$msgTag " +
                    "errorClass=${e::class.simpleName}",
            )
            return false
        }

        // Step 2: load fresh session.
        val currentState = sessionManager.tryLoadSession(conversationId)
        if (currentState == null) {
            // Defensive: the fresh ratchet was just saved by the
            // caller. Logged for forensics; never setSessionSuspect.
            messagingLog(
                MessagingLogLevel.WARN,
                "DECRYPT_TRACE replay_no_session conv=$convTag msgId=$msgTag " +
                    "— session vanished between repair save and replay",
            )
            return false
        }

        // Step 3: ratchet.decrypt IN MEMORY. The advanced state
        // [newState] is held in a local until step 7 — commit-5b
        // architect rule. Failure here leaves the held row + ratchet
        // both untouched, so the row remains decryptable on the
        // next replay cycle.
        val decryptResult = try {
            ratchet.decrypt(currentState, wireFrame.encryptedMessage)
        } catch (e: Throwable) {
            messagingLog(
                MessagingLogLevel.WARN,
                "DECRYPT_TRACE replay_fail conv=$convTag msgId=$msgTag " +
                    "attemptCount=$attemptNo errorClass=${e::class.simpleName}",
            )
            return false
        }
        val (newState, plainBytes) = decryptResult

        // Step 4: decode MessagePayload JSON. Failure here returns
        // BEFORE saveSession — the un-advanced ratchet means the
        // next replay (after the held row's wireFrame is fixed, or
        // after manual intervention) can still decrypt the original
        // ciphertext under the same chain key.
        val payload = try {
            json.decodeFromString<MessagePayload>(plainBytes.decodeToString())
        } catch (e: Throwable) {
            messagingLog(
                MessagingLogLevel.WARN,
                "DECRYPT_TRACE replay_payload_decode_fail conv=$convTag msgId=$msgTag " +
                    "errorClass=${e::class.simpleName}",
            )
            return false
        }

        // Step 5: gate on TYPE_MESSAGE. Architectural scope note:
        // group routing + voice chunk assembly + reaction/pin
        // handlers all require state outside the receive ratchet
        // (memory buffers, GroupMessagingService,
        // voiceChunkRepository, etc). Replicating their handlers
        // inside the send-path replay loop risks duplicate state
        // mutations.
        //
        // Commit 5b architect rule: NO saveSession on the
        // complex-payload path. The row stays held + ratchet stays
        // un-advanced + no ack + no delete. When the complex-
        // payload replay handler ships in a follow-up commit, the
        // ratchet will advance via that handler — the same wireFrame
        // is still decryptable under the un-advanced chain.
        if (payload.type != MessagePayload.TYPE_MESSAGE) {
            messagingLog(
                MessagingLogLevel.INFO,
                "DECRYPT_TRACE replay_skipped_complex_payload conv=$convTag " +
                    "msgId=$msgTag type=${payload.type}",
            )
            return false
        }

        // Step 6: insert text row with disappearing-timer expiry —
        // architect-locked durability gate before ratchet commit.
        // Failure here returns BEFORE saveSession; the un-advanced
        // ratchet keeps the row retryable on the next cycle.
        val timerSecs = try {
            conversationRepository.getDisappearingTimer(conversationId)
        } catch (e: Throwable) {
            messagingLog(
                MessagingLogLevel.WARN,
                "DECRYPT_TRACE replay_disappearing_read_fail conv=$convTag " +
                    "msgId=$msgTag errorClass=${e::class.simpleName}",
            )
            return false
        }
        val expiresAtMs = if (timerSecs > 0L) nowMs + timerSecs * 1_000L else null
        try {
            messageRepository.insertMessage(
                MessageEntity(
                    id = entry.envelopeId,
                    conversationId = conversationId,
                    ciphertext = wireFrame.encryptedMessage.ciphertext,
                    plaintextCache = payload.text,
                    sent = false,
                    status = MessageStatus.DELIVERED,
                    createdAt = nowMs,
                    expiresAtMs = expiresAtMs,
                )
            )
        } catch (e: Throwable) {
            messagingLog(
                MessagingLogLevel.WARN,
                "DECRYPT_TRACE replay_insert_fail conv=$convTag msgId=$msgTag " +
                    "errorClass=${e::class.simpleName}",
            )
            return false
        }
        messagingLog(
            MessagingLogLevel.INFO,
            "DECRYPT_TRACE replay_inserted_text conv=$convTag msgId=$msgTag " +
                "expiresAtMs=$expiresAtMs",
        )

        // Step 7: commit ratchet. Only after a successful durable
        // insert do we advance the receive chain. Failure here
        // (rare — usually DB connectivity) leaves the message in
        // the DB but the ratchet un-advanced; future replay re-
        // executes the same chain (INSERT OR IGNORE is idempotent
        // so the duplicate insert no-ops).
        try {
            sessionManager.saveSession(conversationId, newState)
        } catch (e: Throwable) {
            messagingLog(
                MessagingLogLevel.WARN,
                "DECRYPT_TRACE replay_save_session_fail conv=$convTag msgId=$msgTag " +
                    "errorClass=${e::class.simpleName}",
            )
            return false
        }

        // Step 8: conversation upsert + UI emit + notification —
        // the user-visible mirror of normal text receive. Failure
        // here is partial state (message durable, ratchet advanced,
        // conv preview / unread / notification missed). Row stays
        // held; next replay finds the message already inserted and
        // re-runs upsert/emit/notify idempotently.
        try {
            applyReplayConvUpsertEmitNotify(
                entry = entry,
                payload = payload,
                conversationId = conversationId,
                nowMs = nowMs,
            )
        } catch (e: Throwable) {
            messagingLog(
                MessagingLogLevel.WARN,
                "DECRYPT_TRACE replay_side_effect_fail conv=$convTag msgId=$msgTag " +
                    "errorClass=${e::class.simpleName}",
            )
            return false
        }

        // Step 9: ledger PROCESSED.
        try {
            processedEnvelopeRepository?.markProcessed(
                envelopeId = entry.envelopeId,
                conversationId = conversationId,
                senderPubKeyHex = entry.senderPubKeyHex,
                payloadType = payload.type,
                status = ProcessedEnvelopeRepository.Status.PROCESSED,
                nowMs = nowMs,
            )
        } catch (e: Throwable) {
            messagingLog(
                MessagingLogLevel.WARN,
                "DECRYPT_TRACE replay_mark_processed_fail conv=$convTag msgId=$msgTag " +
                    "errorClass=${e::class.simpleName}",
            )
            return false
        }

        // Step 10: ack the relay.
        try {
            transport.sendDeliveryAck(entry.envelopeId)
        } catch (e: Throwable) {
            messagingLog(
                MessagingLogLevel.WARN,
                "DECRYPT_TRACE replay_ack_fail conv=$convTag msgId=$msgTag " +
                    "errorClass=${e::class.simpleName}",
            )
            return false
        }

        // Step 11: delete the held row. Failure here is the most
        // benign — message is durably in UI + ledger + relay-ack'd;
        // only the row remains. Still keep held + recordAttempt
        // so cleanup converges via attempt cap or commit-6 TTL.
        try {
            repo.deleteByEnvelopeId(entry.envelopeId)
        } catch (e: Throwable) {
            messagingLog(
                MessagingLogLevel.WARN,
                "DECRYPT_TRACE replay_delete_fail conv=$convTag msgId=$msgTag " +
                    "errorClass=${e::class.simpleName}",
            )
            return false
        }

        messagingLog(
            MessagingLogLevel.INFO,
            "DECRYPT_TRACE replay_ok conv=$convTag msgId=$msgTag " +
                "plaintextBytes=${plainBytes.size}",
        )
        return true
    }

    /**
     * PR-CRYPTO-SESSION-REPAIR1 commit 5b (2026-05-31). Conversation
     * upsert + `_incomingMessages.emit` + notification — the post-
     * `saveSession` half of the text-receive mirror. Splits commit
     * 5a's `applyTextReplaySideEffects` so the insert can run BEFORE
     * the ratchet commit and the upsert/emit/notify after, per the
     * architect-locked step ordering.
     *
     * Throws if any sub-step fails — the caller wraps this in
     * try/catch and records a replay attempt on failure.
     */
    private suspend fun applyReplayConvUpsertEmitNotify(
        entry: DecryptFailedEnvelopeRepository.Entry,
        payload: MessagePayload,
        conversationId: String,
        nowMs: Long,
    ) {
        // Upsert conversation: mirror the live receive's
        // preview/lastMessageAt/unreadCount++ on existing convs and
        // REQUEST-creation on unknown senders. In the repair-replay
        // path the conv almost always exists (the user just sent
        // through it), but the create-REQUEST branch stays for
        // defensive symmetry with the live receive path.
        val senderName = payload.senderUsername.ifBlank { entry.senderPubKeyHex.take(8) }
        val existing = conversationRepository.getConversation(conversationId)
        if (existing == null) {
            conversationRepository.upsertConversation(
                ConversationEntity(
                    id = conversationId,
                    theirUsername = senderName,
                    theirPublicKeyHex = entry.senderPubKeyHex,
                    lastMessagePreview = previewText(payload.text),
                    lastMessageAt = nowMs,
                    unreadCount = 1,
                    trustTier = TrustTier.REQUEST,
                    blocked = false,
                )
            )
        } else {
            conversationRepository.upsertConversation(
                existing.copy(
                    lastMessagePreview = previewText(payload.text),
                    lastMessageAt = nowMs,
                    unreadCount = existing.unreadCount + 1,
                )
            )
        }

        // Emit to UI flow.
        _incomingMessages.emit(
            IncomingMessage(
                id = entry.envelopeId,
                conversationId = conversationId,
                senderPublicKeyHex = entry.senderPubKeyHex,
                text = payload.text,
                receivedAt = nowMs,
            )
        )

        // Local push notification (best-effort; the callback itself
        // is `runCatching`-wrapped inside).
        invokeIncomingNotificationCallback(
            source = "text",
            conversationId = conversationId,
            senderName = senderName,
            preview = previewText(payload.text),
            senderPubKeyHex = entry.senderPubKeyHex,
        )
    }


    /**
     * Platform hook for local push notifications.
     * Set by the Android AppContainer after [initMessaging]; null on other platforms.
     * Called on the scope's thread after a message is successfully decrypted and stored.
     * The callback must be non-blocking (fire-and-forget on the platform side).
     *
     * PR-NOTIF-DIAG (2026-05-22): added `source` as the FIRST parameter. Values are a
     * closed enum: `text`, `voice_v1_assembled`, `voice_v1_chunk`, `voice_v2_manifest`.
     * The platform binding logs this under the `PhantomNotif` tag so a logcat capture
     * shows which incoming-message path triggered the notification — diagnostic only,
     * not used for any routing decision. See `docs/tracks/notifications-diag.md`.
     *
     * Parameters: source, conversationId, senderName, preview, senderPublicKeyHex
     */
    @Volatile var onNewMessageNotification: ((source: String, conversationId: String, senderName: String, preview: String, senderPublicKeyHex: String) -> Unit)? = null

    /**
     * PR-NOTIF-DIAG (2026-05-22) — unified `runCatching` wrapper around the
     * platform notification callback. Replaces four independent invoke sites
     * that each had slightly different logging (text path had a "callback null"
     * log, legacy voice chunk path had `VOICE_RX notification_start/ok`,
     * the other two had nothing).
     *
     * Behaviour invariants — diagnostic only, do NOT alter:
     * - `runCatching` swallows exceptions exactly as the old code did.
     * - Callback is invoked at the SAME four code points (no new sites, no removed sites).
     * - Arguments pass through unchanged — no truncation, no transformation.
     *
     * Logs (privacy: only first 8 chars of conv/sender pubkey, no plaintext,
     * no ciphertext, no tokens):
     * - `NOTIF invoke_attempt source=<…> conv=<8> sender=<8> callbackNull=<…>`
     * - `NOTIF invoke_ok source=<…> conv=<8>`
     * - `NOTIF invoke_threw source=<…> conv=<8> error=<class>:<message>`
     */
    private fun invokeIncomingNotificationCallback(
        source: String,
        conversationId: String,
        senderName: String,
        preview: String,
        senderPubKeyHex: String,
    ) {
        val callbackNull = onNewMessageNotification == null
        messagingLog(
            MessagingLogLevel.INFO,
            "NOTIF invoke_attempt source=$source conv=${conversationId.take(8)} " +
                "sender=${senderPubKeyHex.take(8)} callbackNull=$callbackNull",
        )
        runCatching {
            onNewMessageNotification?.invoke(source, conversationId, senderName, preview, senderPubKeyHex)
        }.onSuccess {
            messagingLog(
                MessagingLogLevel.INFO,
                "NOTIF invoke_ok source=$source conv=${conversationId.take(8)}",
            )
        }.onFailure { e ->
            messagingLog(
                MessagingLogLevel.ERROR,
                "NOTIF invoke_threw source=$source conv=${conversationId.take(8)} " +
                    "error=${e::class.simpleName}:${e.message}",
                e,
            )
        }
    }

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
                // TODO(stage3-migration): ENVELOPE_ID_FULL_RETROFIT —
                // Trek 2 Stage 3 migration audit converts this call site
                // to `phantom.core.transport.EnvelopeId.random().value`
                // after confirming this path never derives the id from
                // payload / ratchet state. uuid4 is already CSPRNG-backed
                // on Android (UUID.randomUUID delegates to SecureRandom
                // on API 19+) so no security regression in Stage 2A.
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
        // PR-MEDIA-UPLOAD-CANCEL1 — hold the Job handle so the UI X-button
        // path can find and cancel it. `lateinit var` is the standard trick
        // for self-referencing a launched coroutine; the Job is assigned
        // before the coroutine body actually starts running, so the cancel
        // path that races with the very first `ensureActive` checkpoint
        // still finds a live entry.
        lateinit var uploadJob: Job
        uploadJob = scope.launch {
            // PR-M2e — manifestSent guards the early-vs-tail double-send
            // race. PR-MEDIA-UPLOAD-CANCEL1 hoists it out of the `try`
            // block so the cancel-by-user catch can log whether the
            // receiver was already aware of this voice when we dropped it.
            var manifestSent = false
            try {
                // PR-M2e — extract the manifest-send block into a suspend lambda
                // so it can fire either AS the early-manifest hook (after the
                // first K=3 chunks) OR as a tail fallback (legacy path / very
                // short voices). It's idempotent on its own state — the flag
                // `manifestSent` guards against double-send.
                suspend fun sendManifestEnvelope(manifest: VoiceManifestV2) {
                    if (manifestSent) return
                    manifestSent = true
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
                    // TODO(stage3-migration): ENVELOPE_ID_FULL_RETROFIT —
                    // Trek 2 Stage 3 migration audit converts this call
                    // site to `phantom.core.transport.EnvelopeId.random().value`
                    // after confirming this path never derives the id
                    // from payload / ratchet state. uuid4 is CSPRNG-backed
                    // on Android (SecureRandom). See
                    // `shared/core/transport/.../EnvelopeId.kt`.
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
                    // transport.send returning false is OK — the envelope is in
                    // the outbox durable store (HybridRelayTransport retry path).
                    messagingLog(
                        MessagingLogLevel.INFO,
                        "MEDIA_TX manifest_sent mediaId=${manifest.mediaId.take(8)} " +
                            "envelopeId=${envelopeId.take(8)} chunkCount=${manifest.chunkCount}",
                    )
                    // R5-3 — Flip local row UPLOADING → SENT after manifest leaves us.
                    // With PR-M2e this happens BEFORE the upload tail completes; the
                    // remaining chunks continue in background and the sender bubble's
                    // Uploading N/M counter keeps ticking until upload_complete.
                    messageRepository.updateStatus(localMsgId, MessageStatus.SENT)
                    messagingLog(
                        MessagingLogLevel.INFO,
                        "MEDIA_TX local_status_sent mediaId=${manifest.mediaId.take(8)} " +
                            "localMsgId=${localMsgId.take(8)}",
                    )

                    // Conversation preview update
                    conversationRepository.upsertConversation(
                        conv.copy(
                            lastMessagePreview = "Voice message",
                            lastMessageAt      = Clock.System.now().toEpochMilliseconds(),
                        )
                    )
                }

                // PR-MEDIA-UPLOAD-CANCEL2 — early manifest is DISABLED while
                // the X-button cancellation path exists. With it on, the
                // sender pushes the manifest to the receiver after the
                // first two chunks; if the user then taps X mid-upload the
                // receiver has already started downloading and gets stuck
                // at `MEDIA_RX chunk_not_ready_yet … reason=media_chunks_gone`
                // (Test #76.4 reproduced this). Until the relay grows a
                // receiver-side media-cancel / chunk-delete protocol, the
                // manifest only goes out after `uploadVoice(...)` returns
                // success (single tail `sendManifestEnvelope(manifest)` at
                // the bottom of the try). This slows the M2e overlap to
                // sequential upload-then-download, but the trade-off is
                // correct cancellation semantics and no half-broken
                // receiver bubbles.
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
                    onEarlyManifest  = null,
                )
                if (uploadResult.isFailure) {
                    val reason = uploadResult.exceptionOrNull()?.message?.take(60) ?: "unknown"
                    // PR-M2e — distinguish "upload failed before manifest could be
                    // sent" (legacy case) from "manifest already on the wire, tail
                    // upload failed mid-stream" (new M2e edge). The receiver will
                    // surface the tail failure via its own deadline path; this log
                    // is the sender-side diagnostic that pairs with it.
                    val tag = if (manifestSent) "send_failed_after_early_manifest"
                              else "send_failed_no_manifest"
                    messagingLog(
                        MessagingLogLevel.WARN,
                        "MEDIA_TX $tag localMsgId=${localMsgId.take(8)} reason=$reason " +
                            "early_manifest_sent=$manifestSent",
                    )
                    messageRepository.updateStatus(localMsgId, MessageStatus.FAILED)
                    mediaProgressBus.clear(localMsgId)
                    return@launch
                }

                val manifest = uploadResult.getOrThrow()
                // Fallback for the (rare) case where uploadVoice succeeded but
                // onEarlyManifest never fired — e.g. the byte-budget
                // threshold (EARLY_MANIFEST_AFTER_BYTES) is reconfigured
                // to require more bytes than a single chunk can deliver,
                // or a 0-chunk path slipped through.
                sendManifestEnvelope(manifest)
            } catch (ce: CancellationException) {
                // PR-MEDIA-UPLOAD-CANCEL2 — robust user-cancel detection.
                // CancellationException can be wrapped by the coroutine
                // runtime (e.g. when crossing a Mutex/Job boundary), so
                // check both the direct message and the cause's message
                // for the `user_cancelled_upload` marker. Without this
                // some user-cancel events would fall into the generic
                // FAILED branch and leave the bubble in a confusing state.
                if (ce.isUserUploadCancel()) {
                    // The cleanup itself MUST run inside NonCancellable —
                    // we already entered the cancelled context, and any
                    // suspend point (Mutex.withLock, messageRepository.*)
                    // would normally throw CancellationException again
                    // and bypass the cleanup, leaving voiceSendInProgress
                    // stuck (Test #76.4: "приложение не даёт записать
                    // новое голосовое").
                    withContext(NonCancellable) {
                        val msg = "MEDIA_TX upload_cancelled_by_user localMsgId=${localMsgId.take(8)} " +
                            "manifestSent=$manifestSent"
                        messagingLog(MessagingLogLevel.INFO, msg)
                        mediaLog(msg)
                        runCatching { messageRepository.deleteMessage(localMsgId) }
                    }
                    // Do NOT rethrow — surfacing CancellationException to
                    // the parent scope would attempt to cancel siblings on
                    // some implementations. We've owned the cleanup here.
                    return@launch
                }
                val scopeCancelMsg = "MEDIA_TX upload_job_cancelled localMsgId=${localMsgId.take(8)} " +
                    "reason=${ce.message?.take(60) ?: "scope_cancelled"}"
                messagingLog(MessagingLogLevel.WARN, scopeCancelMsg)
                mediaLog(scopeCancelMsg)
                withContext(NonCancellable) {
                    runCatching { messageRepository.updateStatus(localMsgId, MessageStatus.FAILED) }
                }
                throw ce
            } finally {
                // PR-MEDIA-UPLOAD-CANCEL2 — cleanup runs unconditionally,
                // even if the coroutine context is already cancelled. Each
                // suspend in here (Mutex.withLock especially) would
                // otherwise re-throw CancellationException and abort the
                // remaining clearing, leaving stale entries in
                // `voiceSendInProgress` (blocks future recordings) and
                // `voiceUploadJobs` (memory leak + ghost cancel target).
                withContext(NonCancellable) {
                    mediaProgressBus.clear(localMsgId)
                    voiceUploadJobsLock.withLock {
                        voiceUploadJobs.remove(localMsgId)
                    }
                    mutexFor(conversationId).withLock {
                        voiceSendInProgress.remove(conversationId)
                    }
                }
            }
        }
        voiceUploadJobsLock.withLock {
            voiceUploadJobs[localMsgId] = uploadJob
        }
        return Result.success(Unit)
    }

    /** PR-MEDIA-UPLOAD-CANCEL2 — robust check for the user-cancel marker.
     *  The marker may live directly on the message OR on the cause depending
     *  on whether the framework wrapped the exception. */
    private fun CancellationException.isUserUploadCancel(): Boolean {
        return message == USER_CANCELLED_UPLOAD || cause?.message == USER_CANCELLED_UPLOAD
    }

    /**
     * PR-MEDIA-UPLOAD-CANCEL1 — user-initiated voice upload cancellation
     * triggered by the X button on the uploading bubble. Looks up the
     * captured upload Job by `localMsgId`, cancels it with the
     * `user_cancelled_upload` marker so the `sendAudioV2` catch branch can
     * tell user-cancel apart from generic CancellationException, then
     * clears the progress entry and deletes the local row.
     */
    override suspend fun cancelVoiceUpload(
        conversationId: String,
        localMsgId: String,
    ): Result<Unit> = runCatching {
        // PR-MEDIA-UPLOAD-CANCEL2.1 — mirror every cancel-path log line to
        // `mediaLog` so it lands in the `PhantomMedia` tag and is visible
        // under the standard `PhantomMedia:V` logcat filter. Otherwise the
        // diagnostic chain breaks between the UI-side log (which uses
        // `Log.i("PhantomMedia", …)` directly) and the DMS-side
        // `messagingLog(...)` which uses tag `PhantomMessaging`.
        val requestedMsg = "MEDIA_TX upload_cancel_requested localMsgId=${localMsgId.take(8)}"
        messagingLog(MessagingLogLevel.INFO, requestedMsg)
        mediaLog(requestedMsg)

        val job = voiceUploadJobsLock.withLock {
            voiceUploadJobs.remove(localMsgId)
        }

        if (job == null) {
            // Either the upload already finished and the Job entry was
            // cleared in the `finally`, or the user tapped X on a row that
            // never had a live upload (e.g. an earlier FAILED row that
            // still shows the X). Clear UI state defensively and best-
            // effort drop a QUEUED / UPLOADING row so the bubble doesn't
            // linger.
            val noopMsg = "MEDIA_TX upload_cancel_noop localMsgId=${localMsgId.take(8)} reason=no_active_job"
            messagingLog(MessagingLogLevel.WARN, noopMsg)
            mediaLog(noopMsg)
            mediaProgressBus.clear(localMsgId)
            val msg = runCatching { messageRepository.getMessageById(localMsgId) }.getOrNull()
            if (msg?.status == MessageStatus.UPLOADING || msg?.status == MessageStatus.QUEUED) {
                runCatching { messageRepository.deleteMessage(localMsgId) }
            }
            mutexFor(conversationId).withLock {
                voiceSendInProgress.remove(conversationId)
            }
            return@runCatching
        }

        // Cancel the coroutine and WAIT for it to finish via `join()`.
        // PR-MEDIA-UPLOAD-CANCEL2 — Test #76.4 showed that without the
        // join the UI reload (`onReloadMessages` on the caller side)
        // could fire before the catch+finally inside `sendAudioV2`
        // actually deleted the local row, so the bubble briefly
        // re-appeared then vanished. After `join`, the row is gone and
        // the next reload renders the LazyColumn without it. The
        // launched coroutine's catch+finally is wrapped in
        // `withContext(NonCancellable)` so it always completes — `join`
        // here cannot deadlock.
        job.cancel(CancellationException(USER_CANCELLED_UPLOAD))
        val dispatchedMsg = "MEDIA_TX upload_cancel_dispatched localMsgId=${localMsgId.take(8)}"
        messagingLog(MessagingLogLevel.INFO, dispatchedMsg)
        mediaLog(dispatchedMsg)
        job.join()
        val joinedMsg = "MEDIA_TX upload_cancel_joined localMsgId=${localMsgId.take(8)}"
        messagingLog(MessagingLogLevel.INFO, joinedMsg)
        mediaLog(joinedMsg)
    }

    override suspend fun startReceiving() {
        // PR-RECV-DIAG1 — log entry + early-return path. If we see
        // `startReceiving_called` without a matching `subscription_setup`,
        // it means a previous startReceiving already wired the flow and
        // we returned early (idempotent path). If we see neither, the
        // service-side path never reached startReceiving at all.
        messagingLog(MessagingLogLevel.INFO, "RECV_DIAG startReceiving_called")
        // compareAndSet under the lock: two concurrent callers cannot both see
        // receiving == false, preventing duplicate flow collectors.
        val alreadyStarted = startReceivingLock.withLock {
            if (receiving) true else { receiving = true; false }
        }
        if (alreadyStarted) {
            messagingLog(
                MessagingLogLevel.INFO,
                "RECV_DIAG startReceiving_idempotent_skip",
            )
            return
        }
        messagingLog(MessagingLogLevel.INFO, "RECV_DIAG startReceiving_first_call")

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

        // PR-RECV-DIAG1 — log entry/exit of the transport.incoming
        // subscription. Test #83.6c showed Tecno-side has NO handleDeliver
        // events even when the emulator's relay_send_return ok=true. To
        // disambiguate "subscription never wired" vs "transport never
        // emits" vs "envelope arrived but handleDeliver dropped it", we
        // log the subscription setup AND every envelope that crosses
        // this onEach BEFORE handleDeliver runs. `handleDeliver start`
        // already logs from inside the handler, so two adjacent log
        // lines per envelope = onEach is wired correctly; one
        // `envelope_seen` without a matching `handleDeliver start` =
        // handler aborted; nothing at all = upstream silence.
        messagingLog(
            MessagingLogLevel.INFO,
            "RECV_DIAG transport_incoming_subscribed",
        )
        transport.incoming
            .onEach { deliver ->
                messagingLog(
                    MessagingLogLevel.INFO,
                    "RECV_DIAG envelope_seen id=${deliver.messageId.take(8)} " +
                        "sealed=${deliver.sealedSender.isNotEmpty()} " +
                        "payloadBytes=${deliver.payload.length}",
                )
                handleDeliver(deliver)
            }
            .launchIn(scope)
        messagingLog(
            MessagingLogLevel.INFO,
            "RECV_DIAG acks_subscribed",
        )

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

        // PR-NOTIF-DIAG: unified callback wrapper. Source = voice_v1_assembled
        // (legacy voice path, full assembly inside DMS — D2b.1 durable receive).
        invokeIncomingNotificationCallback(
            source = "voice_v1_assembled",
            conversationId = conversationId,
            senderName = senderName,
            preview = "🎤 Voice message",
            senderPubKeyHex = senderPubKeyHex,
        )

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
                // PR-CRYPTO-SESSION-REPAIR1 commit 2 (2026-05-29) — DECRYPT_TRACE
                // attempt marker. Emitted before EVERY ratchet.decrypt call so
                // the next-session DECRYPT_TRACE pipeline can correlate
                // attempt → outcome lines without ambiguity. Architect-locked
                // log content (no raw key material): short ids only, plus
                // boolean session/x3dhInit flags.
                val decryptStartMs = Clock.System.now().toEpochMilliseconds()
                messagingLog(
                    MessagingLogLevel.INFO,
                    "DECRYPT_TRACE attempt msgId=${deliver.messageId.take(8)} " +
                        "sender=${senderPubKeyHex.take(8)} " +
                        "conv=${conversationId.take(8)} " +
                        "sessionExists=${state != null} " +
                        "x3dhInitPresent=${wireFrame.x3dhInit != null}",
                )
                if (state != null) {
                    // Existing session — decrypt directly first. The peer's
                    // signing pubkey carried alongside the frame is ignored
                    // here; identity-key change handling is a separate path
                    // (PR C / Phase 5 SPK rotation cache).
                    //
                    // After PR-CRYPTO-INBOUND-X3DH-REPAIR1 commit 2
                    // (2026-05-29): if the direct decrypt fails MAC and the
                    // frame carries `x3dhInit`, the catch branch below uses
                    // that `x3dhInit` as a recipient-side repair hint —
                    // candidate bootstrap in memory + decrypt under the
                    // candidate + commit only on success (mini-lock fe90c8a9
                    // §Scope items 1+5). On the SUCCESSFUL direct-decrypt
                    // path here the `x3dhInit` is still ignored — the peer
                    // simply pays a few wasted bytes when their suspect
                    // outbound (#243 commit 4) attaches a repair hint to a
                    // frame the receiver could already decrypt.
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
                        // PR-CRYPTO-SESSION-REPAIR1 commit 2 (2026-05-29) —
                        // observability-only DECRYPT_TRACE ok marker.
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "DECRYPT_TRACE ok msgId=${deliver.messageId.take(8)} " +
                                "conv=${conversationId.take(8)} " +
                                "plaintextBytes=${decrypted.size} " +
                                "elapsedMs=${Clock.System.now().toEpochMilliseconds() - decryptStartMs} " +
                                "bootstrap=false",
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
                        // PR-CRYPTO-SESSION-REPAIR1 commit 3c (architect P2
                        // 2026-05-30 on PR #243): the pre-commit-3 destructive-
                        // ack warning ("…ack-deliver'ing to clear relay store…")
                        // and the ADR-012 rationale that justified it both
                        // moved INSIDE the release/ack branch below. Pre-PR
                        // they were the only path; in commit 3a they sat in
                        // front of BOTH the new hold branch and the existing
                        // release branch, which made the warning lie in debug
                        // hold mode ("ack-deliver'ing" while no ack actually
                        // fires). The DECRYPT_TRACE `action=hold|ack` lines
                        // remain the canonical action source for both modes.
                        if (e.message?.contains("MAC", ignoreCase = true) == true ||
                            e.message?.contains("verification", ignoreCase = true) == true
                        ) {
                            // ═════════════════════════════════════════════════════════
                            // PR-CRYPTO-INBOUND-X3DH-REPAIR1 commit 2 (2026-05-29) —
                            // ADDITIVE inbound-repair branch (architect-ACKed
                            // mini-lock fe90c8a9 + commit-1 ACK 61724ed7).
                            //
                            // Architect-locked invariants (mini-lock §Scope items 1+4+5+7):
                            //   (1) Fires ONLY when wireFrame.x3dhInit != null. Frames
                            //       without an inbound repair hint fall through to the
                            //       existing hold/release branches below — PR #243
                            //       commit 3a contract preserved.
                            //   (2) Old ratchet session row is NEVER touched on failure.
                            //       sessionManager.saveSession() runs ONLY after the
                            //       candidate-decrypt succeeds. On any non-cancellation
                            //       failure (candidate bootstrap throws
                            //       SessionBootstrapException OR candidate decrypt
                            //       throws MAC again OR any other non-cancellation
                            //       Throwable), control falls through to the existing
                            //       hold branch with the on-disk session row byte-
                            //       identical to its pre-receive content.
                            //       CancellationException is re-thrown (commit 2a),
                            //       not converted to a failure — see comments at the
                            //       try/catch site below.
                            //   (3) Does NOT call setSessionSuspect. The new branch
                            //       itself never re-suspects; if it fails, the existing
                            //       hold branch below may set suspect exactly as it
                            //       does today (PR #243 commit 3a behaviour unchanged).
                            //   (4) Does NOT early-ack and does NOT bypass downstream
                            //       payload processing. Successful repair returns the
                            //       decrypted plaintext from `withLock { ... }` (same
                            //       block-return shape as the existing state != null
                            //       success path at line 2371), so the rest of
                            //       handleDeliver routes it through the same payload
                            //       handlers and the eventual ack happens via that
                            //       normal flow.
                            //   (5) OPK is eagerly consumed inside
                            //       recipientBootstrapInMemory (explicit commit-1
                            //       decision matching recipientBootstrap behaviour;
                            //       see SessionManager KDoc).
                            //   (6) NOT gated on holdMacFailures. This is successful
                            //       crypto recovery — not a destructive-ack-vs-hold
                            //       choice — so it fires in BOTH debug AND release
                            //       builds. Worst case for release builds is the same
                            //       as today (fall through to the existing ack path).
                            //       Best case is the user recovers a message that
                            //       would have been silently lost.
                            // ═════════════════════════════════════════════════════════
                            val inboundX3dhInit = wireFrame.x3dhInit
                            if (inboundX3dhInit != null) {
                                val repairStartMs = Clock.System.now().toEpochMilliseconds()
                                messagingLog(
                                    MessagingLogLevel.INFO,
                                    "DECRYPT_TRACE inbound_repair_armed msgId=${deliver.messageId.take(8)} " +
                                        "sender=${senderPubKeyHex.take(8)} " +
                                        "conv=${conversationId.take(8)} " +
                                        "reason=fail_mac_existing_session",
                                )
                                // PR-CRYPTO-INBOUND-X3DH-REPAIR1 commit 2a
                                // (2026-05-29) — Vladislav P2 finding on
                                // Commit 2 `23394e8f`: replace runCatching
                                // with an explicit try/catch so
                                // CancellationException is RE-THROWN, not
                                // swallowed as a generic failure that would
                                // fall through to the existing hold path —
                                // and in release builds (where
                                // holdMacFailures=false) further fall through
                                // to the destructive `FAILED_MAC + ack-deliver`
                                // branch, dropping the envelope due to a
                                // coroutine lifecycle event rather than any
                                // actual crypto verdict. The same idiom is
                                // already documented as the
                                // PR-MEDIA-UPLOAD-CANCEL2 fix in
                                // `VoiceV2Sender.kt:73-91` — Test #76.4
                                // surfaced the exact bug in that module
                                // (cancellation became a normal Result.failure
                                // and the cancellation handler never ran).
                                //
                                // Candidate state is NOT yet persisted — that
                                // happens only after this ratchet.decrypt
                                // succeeds. On non-cancellation failure here,
                                // the catch block produces Result.failure(t),
                                // the candidate state is discarded (it was
                                // only a local val), control falls through to
                                // the existing hold branch below, and the
                                // on-disk session row remains byte-identical
                                // to its pre-receive content (mini-lock
                                // §Scope item 5 CENTRAL invariant).
                                val repairResult: Result<Pair<RatchetState, ByteArray>> = try {
                                    val candidate = sessionManager.recipientBootstrapInMemory(
                                        conversationId = conversationId,
                                        localIdentityKeyPair = localKeyPair,
                                        senderIdentityPublicKeyHex = senderPubKeyHex,
                                        x3dhInit = inboundX3dhInit,
                                    )
                                    val (advancedState, decryptedPlaintext) =
                                        ratchet.decrypt(candidate, encrypted)
                                    Result.success(advancedState to decryptedPlaintext)
                                } catch (ce: kotlinx.coroutines.CancellationException) {
                                    // Cancellation is a coroutine lifecycle
                                    // signal, not a crypto verdict — re-throw
                                    // so the structured-concurrency parent can
                                    // observe it. The runCatching variant
                                    // (which would have caught it as Throwable)
                                    // is the regression vector documented in
                                    // VoiceV2Sender.kt:73-91 and re-confirmed
                                    // by Vladislav P2 review of this PR's
                                    // Commit 2 `23394e8f`.
                                    throw ce
                                } catch (t: Throwable) {
                                    Result.failure(t)
                                }
                                if (repairResult.isSuccess) {
                                    val (advancedState, decryptedPlaintext) =
                                        repairResult.getOrThrow()
                                    // Commit the advanced state ONLY NOW —
                                    // candidate-decrypt succeeded, so the new
                                    // ratchet is valid and replaces the stale
                                    // on-disk row.
                                    sessionManager.saveSession(conversationId, advancedState)
                                    messagingLog(
                                        MessagingLogLevel.INFO,
                                        "DECRYPT_TRACE inbound_repair_ok msgId=${deliver.messageId.take(8)} " +
                                            "conv=${conversationId.take(8)} " +
                                            "bootstrap=true " +
                                            "plaintextBytes=${decryptedPlaintext.size} " +
                                            "elapsedMs=${Clock.System.now().toEpochMilliseconds() - repairStartMs}",
                                    )
                                    // markProcessed PROCESSED — matches the
                                    // existing state != null success path at
                                    // line ~2363 (same payload_type=unknown
                                    // placeholder, same INSERT OR IGNORE
                                    // semantic).
                                    processedEnvelopeRepository?.markProcessed(
                                        envelopeId = deliver.messageId,
                                        conversationId = conversationId,
                                        senderPubKeyHex = senderPubKeyHex,
                                        payloadType = "unknown",
                                        status = ProcessedEnvelopeRepository.Status.PROCESSED,
                                        nowMs = Clock.System.now().toEpochMilliseconds(),
                                    )
                                    // Return the decrypted plaintext to flow
                                    // back into the normal downstream payload
                                    // processing — same block-return shape as
                                    // the existing success path at line 2371.
                                    // Invariant 4: no early ack, no special
                                    // path; the ack happens via the normal
                                    // downstream flow after payload handling.
                                    return@withLock decryptedPlaintext
                                } else {
                                    val err = repairResult.exceptionOrNull()
                                    messagingLog(
                                        MessagingLogLevel.WARN,
                                        "DECRYPT_TRACE inbound_repair_fail msgId=${deliver.messageId.take(8)} " +
                                            "sender=${senderPubKeyHex.take(8)} " +
                                            "conv=${conversationId.take(8)} " +
                                            "errorClass=${err?.let { it::class.simpleName } ?: "Unknown"} " +
                                            "action=fall_through_to_hold",
                                    )
                                    // INTENTIONAL fall-through to the existing
                                    // hold branch below. The candidate state
                                    // is discarded (local val inside the
                                    // try block — nothing was persisted; the
                                    // explicit try/catch shape from commit
                                    // 2a re-throws CancellationException and
                                    // converts only non-cancellation
                                    // Throwables to Result.failure). The
                                    // on-disk session row
                                    // remains byte-identical to its pre-
                                    // receive content (mini-lock §Scope item
                                    // 5 invariant). No setSessionSuspect from
                                    // this branch — the existing hold branch
                                    // sets suspect on fresh hold-row insertion
                                    // exactly as today (invariant 3 unchanged).
                                }
                            }
                            // ═════════════════════════════════════════════════════════
                            // PR-CRYPTO-SESSION-REPAIR1 commit 3 (2026-05-29) —
                            // ADDITIVE hold-on-MAC branch (architect re-ACKed
                            // PR #243 commit 95c7aae0 → e0b61403 sequence).
                            //
                            // Architect-locked invariants verified by grep
                            // on this diff:
                            //   (1) NO call to transport.sendDeliveryAck here.
                            //   (2) NO call to processedEnvelopeRepository
                            //       .markProcessed here.
                            //   (3) `return@withLock null` short-circuits
                            //       BEFORE the release-path log + ack +
                            //       markProcessed code below, so the existing
                            //       release behaviour is reached only when
                            //       this branch is NOT taken.
                            //   (4) The else-branch below (the original ack
                            //       + FAILED_MAC ledger path) is COMPLETELY
                            //       UNCHANGED. Additive only — zero deletions.
                            //
                            // Gate: (holdMacFailures && repo != null). Both
                            // conditions are required because Android wires
                            // BuildConfig.DEBUG into holdMacFailures and the
                            // repo into decryptFailedEnvelopeRepository; if
                            // either is missing (test scaffolding, legacy
                            // DMS construction), we fall through to the
                            // existing ack path — same as before this PR.
                            //
                            // Storage-failure safety: if insert OR
                            // setSessionSuspect throws, we still return null
                            // WITHOUT acking. The envelope sits on the relay
                            // and gets re-delivered next session; worst case
                            // is 7-day relay TTL eviction. We do NOT fall
                            // through to the ack path because that would
                            // re-introduce the silent destructive loss this
                            // PR exists to prevent.
                            // ═════════════════════════════════════════════════════════
                            if (holdMacFailures && decryptFailedEnvelopeRepository != null) {
                                // Local-capture the non-null repository so
                                // the smart-cast holds inside the runCatching
                                // lambda (Kotlin smart-cast doesn't propagate
                                // a class-property nullability check across
                                // a lambda boundary).
                                val heldRepo: DecryptFailedEnvelopeRepository =
                                    decryptFailedEnvelopeRepository
                                val nowMs = Clock.System.now().toEpochMilliseconds()
                                val holdResult = runCatching {
                                    // Architect-locked inner-WireFrame JSON
                                    // semantic (PR #243 95c7aae0): encode the
                                    // SAME `wireFrame` object the receive path
                                    // just decoded, so the replay loop in
                                    // commit 5 can decode → re-feed
                                    // `wireFrame.encryptedMessage` into
                                    // `ratchet.decrypt` under the fresh
                                    // ratchet.
                                    val wireFrameJson = json.encodeToString(wireFrame)
                                    heldRepo.insert(
                                        envelopeId = deliver.messageId,
                                        conversationId = conversationId,
                                        senderPubKeyHex = senderPubKeyHex,
                                        errorType = "mac",
                                        receivedAtMs = nowMs,
                                        x3dhInitPresent = wireFrame.x3dhInit != null,
                                        wireFrameJson = wireFrameJson,
                                    )
                                    conversationRepository.setSessionSuspect(
                                        conversationId = conversationId,
                                        setAtMs = nowMs,
                                    )
                                }
                                if (holdResult.isSuccess) {
                                    messagingLog(
                                        MessagingLogLevel.WARN,
                                        "DECRYPT_TRACE fail_mac msgId=${deliver.messageId.take(8)} " +
                                            "sender=${senderPubKeyHex.take(8)} " +
                                            "conv=${conversationId.take(8)} " +
                                            "x3dhInitPresent=${wireFrame.x3dhInit != null} " +
                                            "action=hold",
                                    )
                                } else {
                                    // Storage failure inside hold path: we
                                    // STILL skip ack so the envelope is not
                                    // silently destroyed. The relay redelivers
                                    // next session; if hold path keeps failing
                                    // we get repeated redeliveries until the
                                    // relay's 7-day TTL evicts. action=hold_
                                    // storage_error makes this state grep-able.
                                    val err = holdResult.exceptionOrNull()
                                    messagingLog(
                                        MessagingLogLevel.WARN,
                                        "DECRYPT_TRACE fail_mac msgId=${deliver.messageId.take(8)} " +
                                            "sender=${senderPubKeyHex.take(8)} " +
                                            "conv=${conversationId.take(8)} " +
                                            "x3dhInitPresent=${wireFrame.x3dhInit != null} " +
                                            "action=hold_storage_error " +
                                            "errorClass=${err?.let { it::class.simpleName } ?: "Unknown"}",
                                    )
                                }
                                return@withLock null
                            }
                            // ═════════════════════════════════════════════════════════
                            // RELEASE / ack-on-MAC PATH.
                            //
                            // ADR-012 / 2026-05-01 audit finding (relocated
                            // here in commit 3c per architect P2 2026-05-30):
                            // a MAC verification error is a HARD cryptographic
                            // verdict — the chain key positions on sender and
                            // receiver have permanently diverged (typically:
                            // pre-migration envelope still in relay store
                            // after PR C wiped sessions; or double-send under
                            // the same id with chain advanced between sends).
                            // The receiver can never recover THIS envelope on
                            // the unchanged ratchet, no matter how many
                            // redeliveries happen. Ack so the relay drops it.
                            // Log the id so QA can audit which envelopes died
                            // this way.
                            //
                            // The hold path above (debug/beta only) replaces
                            // this destructive ack with a persistent held row
                            // + suspect mark; under that path the relay copy
                            // is preserved (not acked) and a future fresh
                            // X3DH gives the held envelope a second chance.
                            //
                            // PR-CRYPTO-SESSION-REPAIR1 commit 2 (2026-05-29):
                            // DECRYPT_TRACE fail_mac marker. `action=ack`
                            // reflects the release-build behaviour.
                            // ═════════════════════════════════════════════════════════
                            messagingLog(
                                MessagingLogLevel.WARN,
                                "Permanent decrypt failure (MAC error) — ack-deliver'ing to clear " +
                                    "relay store. id=${deliver.messageId.take(12)}… " +
                                    "conv=${conversationId.take(16)}… err=${e.message}",
                            )
                            messagingLog(
                                MessagingLogLevel.WARN,
                                "DECRYPT_TRACE fail_mac msgId=${deliver.messageId.take(8)} " +
                                    "sender=${senderPubKeyHex.take(8)} " +
                                    "conv=${conversationId.take(8)} " +
                                    "x3dhInitPresent=${wireFrame.x3dhInit != null} " +
                                    "action=ack",
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
                        // PR-CRYPTO-SESSION-REPAIR1 commit 2 (2026-05-29) —
                        // DECRYPT_TRACE fail_other for non-MAC IllegalArgument
                        // exceptions. These don't get held under
                        // holdMacFailures (the gate is MAC-specific by
                        // design); their action remains `rethrow`.
                        messagingLog(
                            MessagingLogLevel.WARN,
                            "DECRYPT_TRACE fail_other msgId=${deliver.messageId.take(8)} " +
                                "conv=${conversationId.take(8)} " +
                                "errorClass=${e::class.simpleName} " +
                                "action=rethrow",
                        )
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
                        // PR-CRYPTO-SESSION-REPAIR1 commit 2 (2026-05-29) —
                        // DECRYPT_TRACE fail_legacy_no_x3dh marker. This is
                        // structurally similar to fail_mac (destructive ack)
                        // but the root cause is different: no x3dhInit on
                        // a no-session envelope makes recovery architecturally
                        // impossible, whereas fail_mac is chain divergence
                        // that fresh X3DH can repair. action=ack in this
                        // commit and beyond — holdMacFailures only gates the
                        // MAC class, not this one.
                        messagingLog(
                            MessagingLogLevel.WARN,
                            "DECRYPT_TRACE fail_legacy_no_x3dh msgId=${deliver.messageId.take(8)} " +
                                "sender=${senderPubKeyHex.take(8)} " +
                                "conv=${conversationId.take(8)} action=ack",
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
                    // PR-CRYPTO-SESSION-REPAIR1 commit 2 (2026-05-29) —
                    // DECRYPT_TRACE ok for the bootstrap path. `bootstrap=true`
                    // distinguishes this from the existing-session ok line so
                    // the next-session DECRYPT_TRACE pipeline can compute
                    // "fresh sessions per hour" diagnostics without parsing
                    // session state.
                    messagingLog(
                        MessagingLogLevel.INFO,
                        "DECRYPT_TRACE ok msgId=${deliver.messageId.take(8)} " +
                            "conv=${conversationId.take(8)} " +
                            "plaintextBytes=${decrypted.size} " +
                            "elapsedMs=${Clock.System.now().toEpochMilliseconds() - decryptStartMs} " +
                            "bootstrap=true",
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
                        // PR-NOTIF-DIAG: unified callback wrapper. Source = voice_v1_chunk
                        // (legacy voice chunk path). The existing
                        // VOICE_RX notification_start/ok lines stay in place
                        // alongside as path-specific diagnostics.
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "VOICE_RX notification_start chunkId=${chunkId.take(8)} messageId=${deliver.messageId.take(8)}",
                        )
                        invokeIncomingNotificationCallback(
                            source = "voice_v1_chunk",
                            conversationId = conversationId,
                            senderName = senderName,
                            preview = "🎤 Voice message",
                            senderPubKeyHex = senderPubKeyHex,
                        )
                        messagingLog(
                            MessagingLogLevel.INFO,
                            "VOICE_RX notification_ok chunkId=${chunkId.take(8)} messageId=${deliver.messageId.take(8)}",
                        )
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
            // PR-NOTIF-DIAG: unified callback wrapper. Source = text
            // (incoming text via handleDeliver). The legacy
            // "Invoking onNewMessageNotification callback (null=…)" line
            // is preserved below as well — older diagnostic habits already
            // grep for it, removal would be a behaviour change for triage
            // workflows.
            messagingLog(
                MessagingLogLevel.INFO,
                "Invoking onNewMessageNotification callback (null=${onNewMessageNotification == null})",
            )
            invokeIncomingNotificationCallback(
                source = "text",
                conversationId = conversationId,
                senderName = senderName,
                preview = previewText(payload.text),
                senderPubKeyHex = senderPubKeyHex,
            )
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
        // PR-NOTIF-DIAG: unified callback wrapper. Source = voice_v2_manifest
        // (M1w 1:1 voice manifest path — `handleVoiceV2Manifest`). Previously
        // a bare `?.invoke(...)` with no logs around it; now wrapped in
        // `invokeIncomingNotificationCallback` for parity with the other three
        // call sites. Preview stays "Voice message" (legacy spelling without
        // the 🎤 emoji that the v1 paths use — diagnostic PR keeps it as-is).
        invokeIncomingNotificationCallback(
            source = "voice_v2_manifest",
            conversationId = conversationId,
            senderName = senderName,
            preview = "Voice message",
            senderPubKeyHex = senderPubKeyHex,
        )

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
