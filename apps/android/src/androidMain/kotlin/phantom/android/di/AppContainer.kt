// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.di

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import phantom.android.calls.CallManager
import phantom.android.notifications.PhantomNotificationManager
import phantom.android.service.DisappearingMessageScheduler
import phantom.android.security.KeystoreManager
import phantom.core.crypto.LibsodiumDoubleRatchet
import phantom.core.crypto.LibsodiumX3DH
import phantom.core.identity.IdentityManager
import phantom.core.identity.IdentityRecord
import phantom.core.identity.IdentityRepository
import phantom.core.messaging.ChatThreadStateHolder
import phantom.core.messaging.DefaultGroupMessagingService
import phantom.core.messaging.DefaultMessagingService
import phantom.core.messaging.GroupMessagingService
import phantom.core.messaging.MessagePayload.Companion.TYPE_CALL_ANSWER
import phantom.core.messaging.MessagePayload.Companion.TYPE_CALL_HANGUP
import phantom.core.messaging.MessagePayload.Companion.TYPE_CALL_ICE
import phantom.core.messaging.MessagePayload.Companion.TYPE_CALL_OFFER
import phantom.core.messaging.MessagePayload.Companion.TYPE_CALL_REJECT
import phantom.core.messaging.MessagingService
import phantom.core.messaging.SessionManager
import phantom.core.storage.DatabaseDriverFactory
import phantom.core.storage.PhantomDatabaseHolder
import phantom.core.storage.SqlDelightConversationRepository
import phantom.core.storage.SqlDelightGroupRepository
import phantom.core.storage.SqlDelightIdentityRepository
import phantom.core.storage.SqlDelightMessageRepository
import phantom.core.storage.SqlDelightProcessedEnvelopeRepository
import phantom.core.storage.SqlDelightRatchetStateRepository
import phantom.core.storage.SqlDelightReactionRepository
import phantom.core.storage.SqlDelightSenderKeyRepository
import phantom.core.storage.SqlDelightVoiceChunkRepository
import phantom.core.transport.KtorRelayTransport
import phantom.core.transport.KtorTransportProbe
import phantom.core.transport.PrivacyMode
import phantom.core.transport.TorService
import phantom.core.transport.TorServiceConfig
import phantom.core.transport.TorState
import phantom.core.transport.TransportCapabilities
import phantom.core.transport.TransportCapabilitiesResolver
import phantom.core.transport.TransportManager
import phantom.core.transport.TransportManagerLog
import phantom.core.transport.TransportPreferences
import phantom.core.transport.TransportPreferencesAndroid
import phantom.core.transport.createHttpClientFactory
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import phantom.core.transport.createPreKeyPublishHttpTransport
import phantom.core.transport.createRestHttpClient
import phantom.core.transport.createTorService
import phantom.core.xray.OperatorXrayConfig
import phantom.core.xray.XrayService
import phantom.core.xray.createXrayService

/** Alpha-0 DI container — manual wiring, no framework. */
class AppContainer(private val context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Alpha 1 → Alpha 2 migration manager. Set by [initMessaging] so
     * the launch path can route to MigrationScreen when
     * [phantom.core.messaging.MigrationManager.needsMigration] returns
     * true. Null until initMessaging runs (i.e. before the user has an
     * IdentityRecord, which is the no-op path for migration anyway).
     */
    @Volatile var migrationManager: phantom.core.messaging.MigrationManager? = null
        private set

    /**
     * Steady-state prekey lifecycle service: onboarding bootstrap,
     * OPK pool refill, weekly SPK rotation. The 24-hour ticker is
     * launched in [initMessaging]; UI surfaces (e.g. MigrationScreen)
     * also call [phantom.core.messaging.PreKeyLifecycleService.bootstrapForNewIdentity]
     * directly after a flow that yields a fresh signing keypair so the
     * first publish doesn't wait 24 h.
     */
    @Volatile var preKeyLifecycle: phantom.core.messaging.PreKeyLifecycleService? = null
        private set

    // ── Storage ───────────────────────────────────────────────────────────────
    private val driverFactory = DatabaseDriverFactory(context)
    private val dbHolder = PhantomDatabaseHolder(driverFactory)

    /** Identity repo wrapped with Android Keystore encryption for the DH private key. */
    val identityRepo: IdentityRepository = KeystoreIdentityRepository(
        SqlDelightIdentityRepository(dbHolder.database)
    )
    val conversationRepo = SqlDelightConversationRepository(dbHolder.database)
    val messageRepo      = SqlDelightMessageRepository(dbHolder.database)

    /**
     * PR-UI-CHAT-THREAD-CACHE1 — hot, in-memory `StateFlow<List<MessageEntity>>`
     * cache keyed by `conversationId`. Owns the long-lived observer Jobs that
     * pump SqlDelight DB-change emissions into the cached StateFlows. Read
     * by `ChatScreen` (`holder.snapshot(...)` + `holder.observe(...)`) and
     * preloaded by `ChatListScreen` row-tap. See
     * `docs/tracks/chat-thread-cache.md` for the mini-lock + 8 acceptance
     * scenarios. Lives on `appScope` so cache entries survive Compose
     * disposal — the entire point of moving message state out of the
     * Composable lifecycle after THREAD-STATE1's first-emit gap proved
     * fatal on Tecno (PR #228 park, Tests #81 / #81.1).
     */
    val chatThreadStateHolder = ChatThreadStateHolder(
        messageRepo = messageRepo,
        scope = appScope,
        log = { line -> android.util.Log.i("PhantomUI", line) },
    )
    private val ratchetRepo = SqlDelightRatchetStateRepository(
        db = dbHolder.database,
        blobCipher = phantom.core.storage.createAndroidRatchetKeystoreCipher(),
    )
    val reactionRepo     = SqlDelightReactionRepository(dbHolder.database)
    val groupRepo        = SqlDelightGroupRepository(dbHolder.database)
    val senderKeyRepo    = SqlDelightSenderKeyRepository(dbHolder.database)
    // PR-H2b (2026-05-13): idempotent envelope ledger. See
    // ProcessedEnvelope.sq for full rationale; in short, it stops the
    // relay's at-least-once delivery from MAC-failing the ratchet on
    // the second decrypt of a re-delivered envelope after a lost ack.
    val processedEnvelopeRepo = SqlDelightProcessedEnvelopeRepository(dbHolder.database)

    /**
     * PR-CRYPTO-SESSION-REPAIR1 commit 2 (2026-05-29) — durable hold
     * table for envelopes that produce MAC-error decrypt failures in
     * debug/beta builds. Wired into [DefaultMessagingService] below.
     *
     * Read for commit 2: the constructor parameter exists but is NOT
     * consumed in the receive path. Commit 3 introduces the hold
     * branch behind `holdMacFailures = BuildConfig.DEBUG`.
     */
    val decryptFailedEnvelopeRepo = phantom.core.storage
        .SqlDelightDecryptFailedEnvelopeRepository(dbHolder.database)

    // PR-D2b.1 (2026-05-17): durable partial-assembly buffer for chunked
    // voice receive. Backs `DefaultMessagingService` so 1:1 voices that
    // start arriving over REST short-poll (or any path) survive a process
    // death between chunk save and final assembly. See VoiceChunk.sq for
    // the schema and `assembleAndDispatch1to1Voice` for the helper that
    // both the live chunk handler and the startReceiving finalizer call.
    val voiceChunkRepo = SqlDelightVoiceChunkRepository(dbHolder.database)

    // PR-M1w (2026-05-18): durable task queue for voice_v2 downloads.
    // Constructed unconditionally — the receiver must always have somewhere
    // to enqueue inbound voice_v2 manifests even during the Alpha-1 migration
    // window where signingPubHexForRest is null (i.e. sender-side M1w is
    // inactive). Only the sender and download orchestrator are gated on
    // signingPubHexForRest; the repository itself is always available.
    val voiceV2DownloadRepo = phantom.core.storage.SqlDelightVoiceV2DownloadRepository(dbHolder.database)

    // Starts immediately — deletes expired messages while the app is alive.
    private val disappearingMessageScheduler = DisappearingMessageScheduler(messageRepo, appScope)
        .also { it.start() }

    // ── Crypto ────────────────────────────────────────────────────────────────
    private val x3dh    = LibsodiumX3DH()
    private val ratchet = LibsodiumDoubleRatchet()

    // ── Identity ──────────────────────────────────────────────────────────────
    val identityManager = IdentityManager(
        crypto = phantom.core.identity.LibsodiumIdentityCrypto(),
        repository = identityRepo,
    )

    // In-memory cache of the current identity. Populated eagerly at startup and
    // by initMessaging*; readers (ProfileScreen, top-bar avatar, etc.) collect
    // this StateFlow instead of calling identityRepo.loadIdentity() per screen,
    // which avoids the Keystore-decrypt round-trip on every navigation.
    private val _identityState = MutableStateFlow<IdentityRecord?>(null)
    val identityState: StateFlow<IdentityRecord?> = _identityState.asStateFlow()

    // Self-avatar shared across screens (top-bar, chat list, settings, profile).
    // Single source of truth; ProfileScreen calls refreshSelfAvatar() after the
    // user picks a new photo, and any screen that observes selfAvatar updates.
    private val avatarFile: File = File(context.filesDir, "profile_avatar.jpg")
    private val _selfAvatar = MutableStateFlow<Bitmap?>(null)
    val selfAvatar: StateFlow<Bitmap?> = _selfAvatar.asStateFlow()

    fun refreshSelfAvatar() {
        appScope.launch(Dispatchers.IO) {
            _selfAvatar.value = if (avatarFile.exists()) {
                BitmapFactory.decodeFile(avatarFile.absolutePath)
            } else null
        }
    }

    init {
        appScope.launch {
            if (_identityState.value == null) {
                _identityState.value = identityRepo.loadIdentity()
            }
        }
        refreshSelfAvatar()
    }

    // ── Transport ─────────────────────────────────────────────────────────────
    // PR-D1b (2026-05-16): wsTransport is the bare WS implementation that has
    // always lived behind the public `transport` field. Pre-initMessaging the
    // getter returns this directly (callers see a regular WS transport, just
    // like before). After initMessaging it returns a [HybridRelayTransport]
    // wrapper that adds the REST fallback path on top of the same WS transport.
    private val wsTransport = KtorRelayTransport(createHttpClientFactory())

    /**
     * The [HybridRelayTransport] wrapper constructed inside [initMessaging]
     * once identity + signing pair are known. Null until then. Exposed for
     * the [phantom.android.service.PhantomMessagingService] notification
     * observer to read [HybridRelayTransport.stateMachine] for honest UI
     * labels (e.g. "Online via Direct · Limited realtime" when REST_ACTIVE).
     */
    var hybridTransport: phantom.android.transport.HybridRelayTransport? = null
        private set

    /**
     * PR-LTE-NETCHANGE1 (2026-05-28) — Android network-change observer.
     * Owns the single `ConnectivityManager.NetworkCallback` for the
     * process. Constructed inside [initMessaging] once
     * [transportRewalkCoordinator] is available. Registered by
     * `PhantomMessagingService.onCreate` after `app.ready` resolves.
     *
     * `internal` visibility because both the field and its type live in
     * the `apps:android` module and are consumed only by
     * `PhantomMessagingService` (same module). Exposing this as `public`
     * would leak a module-private type across boundaries.
     */
    internal var networkChangeObserver: phantom.android.transport.NetworkChangeObserver? = null
        private set

    /**
     * PR-LTE-NETCHANGE1 (2026-05-28) — owns the 4-step network-change
     * rewalk (notify state machine, clear sticky hint, disconnect,
     * release). Receives meaningful-change events from
     * [networkChangeObserver]; triggers `PhantomMessagingService`
     * re-entry via an Intent with `EXTRA_REWALK_RESTART=true` to start
     * a fresh connect generation. Constructed inside [initMessaging]
     * once [hybridTransport] exists.
     *
     * `internal` visibility for the same reason as
     * [networkChangeObserver] — module-private implementation detail.
     */
    internal var transportRewalkCoordinator: phantom.android.transport.TransportRewalkCoordinator? = null
        private set

    /**
     * PR-C1 (2026-05-17): current transport capability snapshot.
     *
     * Derived from [hybridTransport]'s [RestStateMachine] and [torService]
     * state. Emits [TransportCapabilities] using [TransportCapabilitiesResolver]
     * — the single source of truth for whether calls and voice are allowed.
     *
     * Starts in the NO_TRANSPORT state (no signing key / before initMessaging).
     * After [initMessaging] completes, two coroutines update it in response to:
     *   1. RestMode transitions from [HybridRelayTransport.stateMachine.state].
     *   2. TorState changes from [torService.state] (only if Tor is lazy-started).
     *
     * UI collectors: ChatScreen uses `.collectAsState()` to drive snackbar copy
     * and call-button gating. CallManager's lambda reads `.value` for the
     * synchronous second-layer guard.
     */
    private val _transportCapabilities = MutableStateFlow(
        TransportCapabilitiesResolver.resolve(restMode = null, torActive = false)
    )
    val transportCapabilities: StateFlow<TransportCapabilities> =
        _transportCapabilities.asStateFlow()

    /** Recomputes and emits a new [TransportCapabilities] snapshot.
     *
     * Called from coroutines tracking RestMode and TorState changes.
     * Thread-safe: MutableStateFlow is thread-safe by contract.
     *
     * [torActiveOverride]: pass the just-observed [TorState] to avoid
     * re-reading the lazy field if Tor has not been initialised. When
     * null, reads [_torStarted] as the guard before touching the lazy.
     */
    private fun recomputeCapabilities(torActiveOverride: Boolean? = null) {
        val restMode = hybridTransport?.stateMachine?.current
        val torActive = torActiveOverride
            ?: (_torStarted && torService.state.value is TorState.Ready)
        _transportCapabilities.value = TransportCapabilitiesResolver.resolve(
            restMode = restMode,
            torActive = torActive,
        )
    }

    /**
     * Becomes true the first time [torService] is accessed inside
     * [initMessaging]'s Tor-tracking coroutine. Guards against
     * force-initialising the 25 MB libtor native library for Standard-mode
     * users who never use Tor. Set to true ONLY when `transportManager`
     * has already accessed [torService] (i.e. Ghost mode is in use), OR
     * when [initMessaging] decides to start observing Tor state because the
     * current [TransportPreferences.privacyMode] requires Tor.
     */
    @Volatile private var _torStarted: Boolean = false

    /**
     * Public transport accessor used by every caller (DMS, CallManager,
     * service status observers, etc.). Returns the [HybridRelayTransport]
     * after initMessaging completes, falling back to the bare WS transport
     * before that. The wrapper is a transparent passthrough whenever the
     * relay does not advertise `rest_fallback=true` capability — so against
     * old relays the behaviour is exactly identical to pre-D1b.
     */
    val transport: phantom.core.transport.RelayTransport
        get() = hybridTransport ?: wsTransport

    // ADR-020 Phase 2: outer-transport subsystems are always-on at construction.
    // The runtime [transportManager] decides which to start (or both, in the
    // chain-walk fallback path) based on the user's [PrivacyMode] preference.
    // Both fields are lazy so the heavy native init (libtor ~25 MB, libXray
    // ~45 MB) is paid only on first reach into the subsystem.
    val torService: TorService by lazy {
        val workDir = context.applicationContext.filesDir.resolve("tor-data")
        val cacheDir = context.applicationContext.cacheDir.resolve("tor-cache")
        workDir.mkdirs(); cacheDir.mkdirs()
        createTorService(
            config = TorServiceConfig(
                dataDirectoryPath = workDir.absolutePath,
                cacheDirectoryPath = cacheDir.absolutePath,
                useBridges = true,
            ),
            platformContext = context.applicationContext,
        )
    }
    val xrayService: XrayService by lazy {
        val dataDir = context.applicationContext.filesDir.resolve("xray-data")
        dataDir.mkdirs()
        createXrayService(OperatorXrayConfig.toConfig(dataDir.absolutePath))
    }

    /** ADR-020 transport prefs (privacy mode + last-working hint) stored under `phantom_prefs`. */
    val transportPreferences: TransportPreferences = TransportPreferencesAndroid(
        prefs = context.applicationContext
            .getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE),
    )

    /**
     * ADR-020 adaptive transport selector. Walks the strategy chain implied by
     * [TransportPreferences.privacyMode], starts the matching subsystem, probes
     * the relay's `/health` through it, and returns the first reachable
     * [phantom.core.transport.ConnectedTransport]. PhantomMessagingService
     * delegates to this on every connect.
     */
    val transportManager: TransportManager by lazy {
        val healthUrl = phantom.android.BuildConfig.RELAY_URL
            .replace("wss://", "https://")
            .replace("ws://", "http://")
            .removeSuffix("/ws")
            .removeSuffix("/") + "/health"
        TransportManager(
            torServiceProvider = { torService },
            xrayServiceProvider = { xrayService },
            preferences = transportPreferences,
            probe = KtorTransportProbe(healthUrl = healthUrl),
            log = object : TransportManagerLog {
                override fun info(msg: String) { android.util.Log.i("TransportManager", msg) }
                override fun warn(msg: String) { android.util.Log.w("TransportManager", msg) }
            },
            vpnDetector = ::isSystemVpnActive,
        )
    }

    /**
     * Returns true when the active default network advertises the VPN
     * transport (i.e. NET_CAPABILITY_NOT_VPN is missing). Diagnostic-only
     * for PR-A1: the result is logged inside TransportManager.connect so a
     * test log shows whether a VPN was active when probes ran. Behavioural
     * gating is deferred to PR-A2 once we have audit evidence on whether
     * Reality+VPN is genuinely broken or just slow on the user's network.
     *
     * Defensive against API quirks (no active network, missing capabilities,
     * security exceptions on locked-down OEMs) — any failure returns false
     * so we never accidentally claim a VPN is active when we cannot tell.
     */
    private fun isSystemVpnActive(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
            ?: return@runCatching false
        val activeNetwork = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return@runCatching false
        // NET_CAPABILITY_NOT_VPN absent ⇒ this network IS a VPN.
        // hasTransport(TRANSPORT_VPN) is the same idea via the transport
        // axis — we check both to cover the (rare) case where a VPN app
        // sets the transport but the framework forgets to clear the
        // NOT_VPN capability, or vice versa.
        !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
    }.getOrDefault(false)

    /**
     * ADR-020 Phase 3: Privacy Mode setter that handles graceful reconnect.
     *
     * Writes the new mode to the canonical [TransportPreferences.privacyMode]
     * AND mirrors it into the legacy `privacy_mode` SharedPreferences key
     * (the read-receipt suppression in `ChatScreen` still reads that key —
     * keeping both in sync avoids a behaviour split mid-migration).
     *
     * Then forces a transport teardown so the next connect generation walks
     * the chain implied by the new mode. The foreground service's
     * `onStartCommand` will be re-invoked by the caller's
     * `startForegroundService` Intent (see [SettingsScreen]); the resulting
     * fresh [TransportManager.connect] call sees the new preference.
     */
    suspend fun setPrivacyMode(mode: PrivacyMode) {
        transportPreferences.privacyMode = mode
        // Drop the previous-mode "preferred transport" hint. Without this, a
        // Ghost → Standard switch reorders the new chain to put Tor first
        // because the last successful Ghost connect recorded Tor as the hint.
        transportPreferences.lastWorkingTransport = null
        transportPreferences.lastSuccessAt = null
        // Legacy mirror so ChatScreen's read-receipt gate keeps working
        // until everything is migrated to read from TransportPreferences.
        context.applicationContext
            .getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("privacy_mode", mode.name)
            .apply()
        // Tear down the live socket — the service's connect coroutine exits
        // its runReconnectLoop and the connectStarted flag flips back so a
        // subsequent startForegroundService picks up the new mode cleanly.
        runCatching { transport.disconnect() }
        runCatching { transportManager.release() }
    }

    // ── Messaging (initialised after identity is loaded) ──────────────────────
    // Nullable until onboarding completes and we have a real identity.
    var messagingService: MessagingService? = null
        private set

    // PR-M2d.1b — live chunk-progress bus for voice_v2 upload / download.
    // Lifetime tied to the AppContainer (process). UI reads `flow` and
    // looks up live N/M counters by message row id.
    val mediaProgressBus: phantom.core.messaging.MediaProgressBus =
        phantom.core.messaging.MediaProgressBus()

    // Initialised together with messagingService — requires identity for myPubKeyHex.
    var groupMessagingService: GroupMessagingService? = null
        private set

    // Initialised in initMessaging — requires identity.publicKeyHex and transport.
    var callManager: CallManager? = null
        private set

    fun initMessaging(
        identity: phantom.core.identity.IdentityRecord,
        localKeyPair: phantom.core.crypto.DhKeyPair,
    ) {
        _identityState.value = identity
        // PR C commit 10: SessionManager rewrite — see ADR-009 supplement
        // and Alpha2_Migration.md. Constructor expanded to accept the
        // local SignedPreKey + OneTimePreKey repositories (recipient
        // bootstrap path looks up own keypairs by id) and IdentityCrypto
        // (verifies peer's published SPK signature). The full bundle-
        // fetch + first-message bootstrap wiring lands in commit 11.
        // F22 / ADR-023: wrap the prekey-repository private-key TEXT
        // columns with an Android Keystore AES-256-GCM master key
        // (alias `phantom_prekey_wrap_v1`). Reads transparently fall
        // back to the legacy raw-hex format for any rows still on the
        // pre-wrap schema; lazy migration via `maybeReplenishOneTimePreKeys`
        // / `maybeRotateSignedPreKey` rewrites them on the next cycle.
        val prekeyKeystoreCipher = phantom.core.storage.createAndroidPrekeyKeystoreCipher()
        val signedPreKeyRepo = phantom.core.storage.SqlDelightLocalSignedPreKeyRepository(
            db = dbHolder.database,
            privateKeyCipher = prekeyKeystoreCipher,
        )
        val oneTimePreKeyRepo = phantom.core.storage.SqlDelightLocalOneTimePreKeyRepository(
            db = dbHolder.database,
            privateKeyCipher = prekeyKeystoreCipher,
        )
        val sessionManagerIdentityCrypto = phantom.core.identity.LibsodiumIdentityCrypto()
        val sessionManager = SessionManager(
            x3dh = x3dh,
            ratchetStateRepository = ratchetRepo,
            signedPreKeyRepository = signedPreKeyRepo,
            oneTimePreKeyRepository = oneTimePreKeyRepo,
            identityCrypto = sessionManagerIdentityCrypto,
            json = json,
        )
        // PR C commit 11: DMS gains the prekey REST client (for the
        // first-message bundle-fetch path) plus a signing-key provider
        // that resolves the local Ed25519 keypair on demand. The HTTP
        // base URL is derived from BuildConfig.RELAY_URL (which is
        // wss:// or ws://) — same pattern as the report-endpoint flow
        // in ContactProfileScreen.
        val relayHttpBase = phantom.android.BuildConfig.RELAY_URL
            .replace("wss://", "https://")
            .replace("ws://", "http://")
            .removeSuffix("/ws")
            .removeSuffix("/")
        // PR-R0.1 (2026-05-15): use native OkHttp transport for POST /prekeys/publish.
        // Test #43 showed the Ktor→OkHttp body-streaming adapter stalls at 8192 bytes
        // deterministically on Android (both Tele2 LTE and emulator Direct). The native
        // transport bypasses Ktor and writes a pre-built ByteArray in one shot.
        // GET /prekeys/status and GET /prekeys/bundle continue using the shared REST
        // client (small GETs; connection reuse fine for them).
        val restHttpClient = createRestHttpClient()
        val preKeyApi = phantom.core.transport.PreKeyApiClient(
            httpClient = restHttpClient,
            relayBaseUrl = relayHttpBase,
            publishTransport = createPreKeyPublishHttpTransport(),
        )

        // PR-D1b (2026-05-16): construct the REST fallback orchestrator using
        // the same long-lived Ktor REST client. Wire it into the HybridRelayTransport
        // wrapper that DMS will see as its `transport` argument below. The
        // wrapper stays in transparent WS passthrough mode unless the relay's
        // `/auth/session` response advertises `rest_fallback=true`, so against
        // old relays the behaviour is identical to pre-D1b.
        //
        // Graceful degrade: if the identity has no signing public key (Alpha 1
        // record mid-migration, or a corrupted record), we leave hybridTransport
        // null and the app uses the bare WS transport. The MigrationManager
        // path eventually backfills the signing key; on the next app start the
        // hybrid will construct normally. Crashing here would brick the app
        // for the entire migration window.
        val signingPubHexForRest = identity.signingPublicKeyHex
        // PR-M1w (2026-05-18): nullable holders for sender-side objects.
        // Remain null in the Alpha-1 migration window (signingPubHexForRest == null)
        // so DefaultMessagingService falls back to the legacy audio_chunk path
        // automatically — zero behaviour change for Alpha-1 records.
        var voiceV2SenderLocal: phantom.core.messaging.VoiceV2Sender? = null
        var voiceV2DownloadOrchestratorLocal: phantom.core.messaging.VoiceV2DownloadOrchestrator? = null
        if (signingPubHexForRest == null) {
            android.util.Log.w(
                "PhantomHybrid",
                "PR-D1b: identity.signingPublicKeyHex is null — skipping hybrid " +
                    "construction, falling back to WS-only transport. Migration " +
                    "will populate it on a later app start.",
            )
        } else {
            val restOrchestrator = phantom.core.transport.RestFallbackOrchestrator(
                baseUrl = relayHttpBase,
                identityHex = identity.publicKeyHex,
                signingPubkeyHex = signingPubHexForRest,
                getChallenge = { identityHex ->
                    // Re-use the long-lived Ktor REST client (HTTP/1.1 pinned by
                    // PR-G4). The relay returns `{"nonce_hex":"<64 hex>"}` on
                    // success. We throw on IOException or non-2xx so the
                    // orchestrator's runCatching surfaces it as
                    // `session_challenge_fail` and the next bootstrap attempt
                    // tries again.
                    val resp = restHttpClient.get(
                        "$relayHttpBase/auth/challenge?identity=$identityHex"
                    )
                    val text = resp.bodyAsText()
                    if (!resp.status.isSuccess()) {
                        error(
                            "auth/challenge non-2xx: ${resp.status.value} body=${text.take(120)}"
                        )
                    }
                    val nonceMatch = Regex("\"nonce_hex\"\\s*:\\s*\"([a-fA-F0-9]+)\"")
                        .find(text)
                        ?: error("auth/challenge response missing nonce_hex: ${text.take(120)}")
                    nonceMatch.groupValues[1]
                },
                signChallenge = { nonceBytes ->
                    identityManager.signRelayChallenge(nonceBytes)
                        ?: error("signing key not provisioned")
                },
                transport = phantom.core.transport.createRestFallbackTransport(),
                log = { msg -> android.util.Log.i("PhantomHybrid", msg) },
            )

            // PR-M1w wire-up (2026-05-18) — encrypted media upload for 1:1 voice.
            // MediaCrypto wraps libsodium AEAD (already initialized at app start).
            // AndroidNativeOkHttpMediaUploadTransport: native OkHttp with HTTP/1.1
            // pinned, fresh client per call, Connection:close. Replaces Ktor path
            // because Test #58 relay logs confirmed Tele2 LTE drops Ktor/HTTP/2
            // persistent connections, causing ~31s per-chunk stalls on the receiver
            // side (25 chunks × 31s ≈ 13 min). KtorMediaUploadTransport stays in
            // commonMain as the JVM/test fallback.
            // RestMediaAuthTokenProvider is a thin adapter to the orchestrator's
            // CAS facade — auth lives in the orchestrator, not here.
            val mediaCryptoLocal = phantom.core.crypto.MediaCrypto()
            val mediaUploadTransportLocal = phantom.core.transport.AndroidNativeOkHttpMediaUploadTransport(
                relayBaseUrl = relayHttpBase,
                log          = { msg -> android.util.Log.i("PhantomMedia", msg) },
                // PR-M2f — relay advertises `media_capabilities.binary_v3=true`
                // in the /auth/session response. The orchestrator caches the
                // parsed capabilities; we consult the StateFlow on every chunk
                // so the transport stays in sync with token refreshes without
                // an explicit setter. Sticky 404/405 fallback inside the
                // transport flips its own internal guard if the relay
                // contradicts the announcement at runtime.
                binaryV3Enabled = { restOrchestrator.capabilities.value.mediaBinaryV3 },
            )
            val mediaAuthTokenProviderLocal = phantom.core.transport.RestMediaAuthTokenProvider(
                orchestrator = restOrchestrator,
            )
            voiceV2SenderLocal = phantom.core.messaging.VoiceV2Sender(
                mediaCrypto    = mediaCryptoLocal,
                mediaTransport = mediaUploadTransportLocal,
                tokenProvider  = mediaAuthTokenProviderLocal,
                log            = { msg -> android.util.Log.i("PhantomMedia", msg) },
                // PR-M2f.1 — debug-only Settings selector binds to a
                // SharedPreferences int. Default 1700 == current production
                // baseline; debug Settings UI offers 1700 / 2200 / 2300 /
                // 2400 / 2600 so the chunk-size ceiling can be probed in
                // one APK without rebuilding per row. The provider is
                // invoked once per voice send (at chunk_split).
                chunkSizeProvider = {
                    phantom.android.diagnostics.ChunkSizeProbe.currentValue(
                        context.getSharedPreferences(
                            "phantom_prefs",
                            Context.MODE_PRIVATE,
                        )
                    )
                },
            )
            voiceV2DownloadOrchestratorLocal = phantom.core.messaging.VoiceV2DownloadOrchestrator(
                downloadRepo   = voiceV2DownloadRepo,
                messageRepo    = messageRepo,
                mediaTransport = mediaUploadTransportLocal,
                tokenProvider  = mediaAuthTokenProviderLocal,
                mediaCrypto    = mediaCryptoLocal,
                fileStore      = phantom.core.messaging.VoiceFileStore(context),
                log            = { msg -> android.util.Log.i("PhantomMedia", msg) },
            )

            val hybrid = phantom.android.transport.HybridRelayTransport(
                wsTransport = wsTransport,
                orchestrator = restOrchestrator,
                processedEnvelopeRepository = processedEnvelopeRepo,
                scope = appScope,
            )
            hybridTransport = hybrid
            // Async REST bootstrap — never blocks AppContainer init. On failure
            // (relay unreachable at app start, network down, etc.) the hybrid
            // stays in passthrough mode and the WS path continues to function.
            appScope.launch {
                runCatching { hybrid.bootstrapAndStart() }
                    .onFailure { e ->
                        android.util.Log.w(
                            "PhantomHybrid",
                            "bootstrapAndStart failed: ${e::class.simpleName}: ${e.message}",
                        )
                    }
            }

            // PR-LTE-NETCHANGE1 (2026-05-28): wire the network-change
            // rewalk pipeline. Coordinator owns reset (notify state
            // machine + clear hint + disconnect + release); observer
            // owns Android NetworkCallback + debounce + fresh-snapshot
            // read; PhantomMessagingService owns the connect re-entry
            // path via the `EXTRA_REWALK_RESTART=true` intent extra.
            //
            // The request-restart lambda goes through Context.startService
            // (NOT startForegroundService — the service is already in the
            // foreground because we're inside initMessaging, which only
            // runs after the service is up).
            val rewalkCoordinator = phantom.android.transport.TransportRewalkCoordinator(
                scope = appScope,
                transportPreferences = transportPreferences,
                transportManager = transportManager,
                hybridTransportProvider = { hybridTransport },
                requestServiceRestart = { reason ->
                    val intent = android.content.Intent(
                        context.applicationContext,
                        phantom.android.service.PhantomMessagingService::class.java,
                    ).apply {
                        putExtra(
                            phantom.android.service.PhantomMessagingService.EXTRA_REWALK_RESTART,
                            true,
                        )
                        putExtra(
                            phantom.android.service.PhantomMessagingService.EXTRA_REWALK_REASON,
                            reason.name,
                        )
                    }
                    runCatching { context.applicationContext.startService(intent) }
                        .onFailure { e ->
                            android.util.Log.w(
                                "PhantomHybrid",
                                "NETWORK_TRACE service_restart_intent_failed " +
                                    "errorClass=${e::class.simpleName} " +
                                    "message=${e.message?.take(120)}",
                            )
                        }
                },
            )
            transportRewalkCoordinator = rewalkCoordinator
            networkChangeObserver = phantom.android.transport.NetworkChangeObserver(
                context = context.applicationContext,
                scope = appScope,
                coordinator = rewalkCoordinator,
            )

            // PR-C1 (2026-05-17): keep transportCapabilities in sync with
            // RestMode transitions. Emits immediately with the current mode,
            // then updates on every subsequent transition so UI observers
            // (ChatScreen) see real-time capability changes without polling.
            appScope.launch {
                hybrid.stateMachine.state.collect {
                    recomputeCapabilities()
                }
            }
        }

        // PR-C1: track TorState so Tor-active flips canSendVoice/canStartCalls.
        // Only start observing Tor state when the current privacy mode requires
        // Tor (Ghost = Tor-first strategy). Standard/Direct-mode users never
        // trigger the torService lazy, so libtor is not loaded unnecessarily.
        // If the user later switches to Ghost mode, setPrivacyMode() calls
        // transportManager.release() and then a new foreground service start
        // re-calls initMessaging — at that point privacyMode == Ghost and the
        // coroutine is started.
        if (transportPreferences.privacyMode == PrivacyMode.Ghost) {
            _torStarted = true
            appScope.launch {
                torService.state.collect { torState ->
                    recomputeCapabilities(torActiveOverride = torState is TorState.Ready)
                }
            }
        }

        // PR C commit 12: MigrationManager — drives Alpha 1 → Alpha 2
        // upgrade. Inspected by the launch path (`needsMigration()`);
        // executed when the user taps Continue on MigrationScreen.
        // Lives on AppContainer alongside DMS so the Activity can
        // reach it before normal messaging starts.
        migrationManager = phantom.core.messaging.MigrationManager(
            identityManager = identityManager,
            identityCrypto = sessionManagerIdentityCrypto,
            signedPreKeyRepository = signedPreKeyRepo,
            oneTimePreKeyRepository = oneTimePreKeyRepo,
            ratchetStateRepository = ratchetRepo,
            senderKeyRepository = senderKeyRepo,
            conversationRepository = conversationRepo,
            preKeyApi = preKeyApi,
            x3dh = x3dh,
        )

        // PR C-followup-1: PreKeyLifecycleService — drives the steady
        // state of the user's published bundle. Three operations:
        //
        //   * bootstrapForNewIdentity() runs once per install
        //     immediately. No-op when an SPK already exists locally
        //     (covers app restart between onboarding completion and
        //     the publish call landing). Initial publish makes this
        //     identity discoverable on the relay.
        //
        //   * maybeReplenishOneTimePreKeys() refills the OPK pool
        //     when count drops below 20.
        //
        //   * maybeRotateSignedPreKey() rotates the SPK every 7 days.
        //
        // The 24-hour ticker below polls both replenish + rotate.
        // On every successful WS reconnect we also fire the same pair
        // so a long-offline device catches up promptly.
        val lifecycleService = phantom.core.messaging.PreKeyLifecycleService(
            identityManager = identityManager,
            signedPreKeyRepository = signedPreKeyRepo,
            oneTimePreKeyRepository = oneTimePreKeyRepo,
            preKeyApi = preKeyApi,
            x3dh = x3dh,
        )
        preKeyLifecycle = lifecycleService

        // Onboarding bootstrap. Runs asynchronously; the resulting Job is
        // joined below (after service creation) to signal bootstrapReady.
        val bootstrapJob = appScope.launch {
            runCatching { lifecycleService.bootstrapForNewIdentity() }
                .onFailure { e ->
                    android.util.Log.w(
                        "PreKeyLifecycle",
                        "Onboarding bootstrap failed: ${e.message}",
                    )
                }
        }

        // 24-hour ticker — drives steady-state replenish + rotate.
        // Idempotent: skips when pool >= threshold and SPK age < 7d.
        // Survives the app being suspended via Dispatchers.Default
        // (continues in background as long as the process is alive;
        // a foreground service or WorkManager job would harden this
        // against process death — flagged for future work).
        //
        // PR-H2b (2026-05-13): also runs the processed-envelopes ledger
        // TTL sweep (8 days, one day longer than the relay's 7-day
        // envelope TTL) so the table cannot grow unbounded. Piggybacks
        // on the existing ticker rather than launching a second one —
        // process resources are scarce on low-end Tecno hardware and
        // a separate scheduler buys nothing.
        appScope.launch {
            while (true) {
                kotlinx.coroutines.delay(24 * 60 * 60 * 1000L)
                runCatching { lifecycleService.maybeReplenishOneTimePreKeys() }
                    .onFailure {
                        android.util.Log.w("PreKeyLifecycle", "Replenish failed: ${it.message}")
                    }
                runCatching {
                    val cutoff = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000L
                    processedEnvelopeRepo.deleteOlderThan(cutoff)
                }.onFailure {
                    android.util.Log.w("ProcessedEnvelopes", "TTL sweep failed: ${it.message}")
                }
                runCatching { lifecycleService.maybeRotateSignedPreKey() }
                    .onFailure {
                        android.util.Log.w("PreKeyLifecycle", "Rotate failed: ${it.message}")
                    }
            }
        }

        val service = DefaultMessagingService(
            identity = identity,
            localKeyPair = localKeyPair,
            ratchet = ratchet,
            sessionManager = sessionManager,
            transport = transport,
            messageRepository = messageRepo,
            conversationRepository = conversationRepo,
            processedEnvelopeRepository = processedEnvelopeRepo,
            scope = appScope,
            json = json,
            reactionRepository = reactionRepo,
            preKeyApi = preKeyApi,
            // Signing-key provider — looks up the Ed25519 keypair from
            // the IdentityManager. Returns null on Alpha 1 records that
            // haven't yet been backfilled by the migration flow
            // (PR C commit 12). DMS surfaces null as a hard send error.
            signingKeyProvider = { identityManager.loadSigningKeyPair() },
            // PR-C1 (2026-05-17): voice send guard via TransportCapabilities.
            // Single source of truth: transportCapabilities.value.canSendVoice.
            // Voice is allowed ONLY on WsActive + no Tor. Limited realtime
            // (RestActive/WsCandidate), Tor, and null state all block. The
            // old D2b voice-over-/relay/send path was parked as
            // proof-of-concept (PR #166); voice re-opens in Limited realtime
            // when PR-M1w wires the new media-upload path. When
            // hybridTransport is null (Alpha 1 record without a signing
            // key), canSendVoice = false (NO_TRANSPORT reason) — safe
            // conservative fallback.
            canSendVoice = {
                val caps = _transportCapabilities.value
                val allowed = caps.canSendVoice
                if (!allowed) {
                    android.util.Log.i(
                        "PhantomTransport",
                        "VOICE_CAPABILITY disabled " +
                            "reason=${caps.callDisabledReason?.name?.lowercase() ?: "unknown"} " +
                            "rest_mode=${hybridTransport?.stateMachine?.current} " +
                            "tor_active=${_torStarted && torService.state.value is TorState.Ready}",
                    )
                }
                allowed
            },
            // PR-D2b.1 (2026-05-17): durable reassembly. Keeps the 1:1
            // voice receive path crash-safe — chunks are saved to
            // SQLDelight before ack-deliver and the startReceiving
            // finalizer resumes any voice whose chunks all landed before
            // the previous process death.
            voiceChunkRepository = voiceChunkRepo,
            // PR-M1w (2026-05-18): encrypted media upload for 1:1 voice.
            // voiceV2SenderLocal and voiceV2DownloadOrchestratorLocal are null
            // during the Alpha-1 migration window (signingPubHexForRest == null);
            // DMS falls back to legacy audio_chunk path when either is null.
            // voiceV2DownloadRepo is always non-null so the receiver can always
            // enqueue inbound manifests (avoids "no_download_repo degraded_mode").
            voiceV2Sender               = voiceV2SenderLocal,
            voiceV2DownloadRepository   = voiceV2DownloadRepo,
            voiceV2DownloadOrchestrator = voiceV2DownloadOrchestratorLocal,
            mediaProgressBus            = mediaProgressBus,
            // PR-MEDIA-UPLOAD-CANCEL2.1 — route DMS media-side diagnostic
            // logs (the upload cancel path in particular) to the
            // `PhantomMedia` tag. `messagingLog(...)` itself writes under
            // `PhantomMessaging`, which the standard logcat filter
            // (`PhantomMedia:V PhantomUI:V PhantomTransport:V *:S`)
            // excludes; the cancel diagnostic chain was invisible during
            // Test #76.5 review even though the cancel worked.
            mediaLog                    = { msg -> android.util.Log.i("PhantomMedia", msg) },
            // PR-CRYPTO-SESSION-REPAIR1 commit 2 (2026-05-29): wire the
            // new hold table + the semantic flag. The flag stays at
            // `BuildConfig.DEBUG` so production builds keep the existing
            // ack-on-MAC + processed_envelopes.FAILED_MAC path completely
            // unchanged in this commit. Commit 3 introduces the
            // conditional `if (holdMacFailures)` branch in
            // DefaultMessagingService.handleDeliver.
            decryptFailedEnvelopeRepository = decryptFailedEnvelopeRepo,
            holdMacFailures             = phantom.android.BuildConfig.DEBUG,
        )
        // Join the bootstrap job and mark ready (success or failure) so the UI
        // can observe bootstrapReady and remove any "setting up keys…" indicator.
        appScope.launch {
            bootstrapJob.join()
            service.markBootstrapReady()
        }

        // Wire local notification callback — Android-only side-effect, not part of the KMP interface.
        // PR-NOTIF-DIAG: callback signature gained a leading `source` field so the
        // platform binding can log which incoming-message path triggered the
        // notification. Diagnostic only — `source` is logged, not used for routing.
        // The previous `PhantomMessaging`-tagged error log is preserved so older
        // triage habits that filter on that tag still see the failure.
        service.onNewMessageNotification = { source, convId, sender, preview, senderPubKeyHex ->
            android.util.Log.i(
                "PhantomNotif",
                "NOTIF callback_invoked source=$source conv=${convId.take(8)} " +
                    "sender=${senderPubKeyHex.take(8)} previewLen=${preview.length}",
            )
            try {
                PhantomNotificationManager.showMessageNotification(
                    context = context,
                    source = source,
                    conversationId = convId,
                    senderName = sender,
                    preview = preview,
                    recipientPubKey = senderPubKeyHex,
                )
                android.util.Log.i(
                    "PhantomNotif",
                    "NOTIF callback_returned source=$source conv=${convId.take(8)}",
                )
            } catch (e: Throwable) {
                android.util.Log.e(
                    "PhantomNotif",
                    "NOTIF callback_threw source=$source conv=${convId.take(8)} " +
                        "error=${e::class.simpleName}:${e.message}",
                    e,
                )
                // Retained for backwards-compatibility with older diagnostic
                // habits that grep `PhantomMessaging` for this exact phrase.
                android.util.Log.e(
                    "PhantomMessaging",
                    "showMessageNotification threw (${e::class.simpleName}): ${e.message}",
                    e,
                )
            }
        }

        // Wire group messaging delegate so DefaultMessagingService can route
        // group-type payloads to DefaultGroupMessagingService after decryption.
        val groupService = DefaultGroupMessagingService(
            myPubKeyHex = identity.publicKeyHex,
            myUsername = identity.username,
            groupRepo = groupRepo,
            senderKeyRepo = senderKeyRepo,
            messageRepo = messageRepo,
            messagingService = service,
            json = json,
        )
        service.groupMessagingService = groupService
        groupMessagingService = groupService

        // PR C-followup-3: pending-bundle retry sweep. Messages that
        // tried to send while the peer had no published bundle on the
        // relay sit in `WAITING_FOR_RECIPIENT_BUNDLE` until either a
        // WS reconnect (peer just came online and published) or a
        // ticker tick (peer published but we haven't reconnected).
        //
        // The sweep is a no-op when the WAITING set is empty, so a
        // 60-second cadence is fine even on quiet conversations.
        appScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60 * 1000L)
                runCatching { service.retryWaitingMessages() }
                    .onFailure {
                        android.util.Log.w(
                            "PendingBundleRetry",
                            "retryWaitingMessages failed: ${it.message}",
                        )
                    }
            }
        }
        // Same retry on every WS reconnect — peer who came online
        // and published their bundle gets their backlog flushed
        // before the user even notices the message was queued.
        appScope.launch {
            transport.state.collect { st ->
                if (st is phantom.core.transport.TransportState.Connected) {
                    runCatching { service.retryWaitingMessages() }
                        .onFailure {
                            android.util.Log.w(
                                "PendingBundleRetry",
                                "retryWaitingMessages on reconnect failed: ${it.message}",
                            )
                        }
                }
            }
        }

        // Prekey-lifecycle reconnect hooks. The 24-hour ticker above
        // covers steady-state OPK refill and SPK rotation, but a long-
        // offline device that just woke up should not have to wait up
        // to a day for the next tick to publish a missing bundle. This
        // block fires three idempotent operations on every successful
        // WS reconnect:
        //
        //   1. verifyBundleOnRelay() — defence against the silent-
        //      onboarding-publish-failure case. Issues a single GET to
        //      /prekeys/status; if the relay reports
        //      `signed_prekey_age_days = null` it republishes the full
        //      local bundle. Cheap when the bundle is already there.
        //   2. maybeReplenishOneTimePreKeys() — handles the case where
        //      we have been offline long enough for peers to drain our
        //      OPK pool below the threshold while the 24-hour ticker
        //      slept.
        //   3. maybeRotateSignedPreKey() — same idea for the weekly
        //      SPK rotation cadence.
        //
        // All three are no-ops on the common path; cost is one GET +
        // one tiny pool-count read on each reconnect.
        appScope.launch {
            transport.state.collect { st ->
                if (st !is phantom.core.transport.TransportState.Connected) return@collect
                runCatching { lifecycleService.verifyBundleOnRelay() }
                    .onFailure {
                        android.util.Log.w(
                            "PreKeyLifecycle",
                            "verifyBundleOnRelay on reconnect failed: ${it.message}",
                        )
                    }
                runCatching { lifecycleService.maybeReplenishOneTimePreKeys() }
                    .onFailure {
                        android.util.Log.w(
                            "PreKeyLifecycle",
                            "Replenish on reconnect failed: ${it.message}",
                        )
                    }
                runCatching { lifecycleService.maybeRotateSignedPreKey() }
                    .onFailure {
                        android.util.Log.w(
                            "PreKeyLifecycle",
                            "Rotate on reconnect failed: ${it.message}",
                        )
                    }
            }
        }

        // Initialise CallManager and wire call-signalling routing.
        // ADR-025: call signals are routed through the DR + Sealed Sender pipeline;
        // CallManager no longer holds a direct reference to RelayTransport.
        val cm = CallManager(
            context = context,
            messagingService = service,
            // PR-C1 (2026-05-17): defence-in-depth calls guard via TransportCapabilities.
            // The full snapshot is passed so CallManager.checkCallCapability can log
            // reason + restModeLabel without a second injected dependency.
            // Single source of truth: _transportCapabilities StateFlow — same StateFlow
            // that drives the UI CALL_CAPABILITY guard in ChatScreen.
            transportCapabilitiesProvider = { _transportCapabilities.value },
        )
        cm.initialize()
        callManager = cm

        service.onCallMessage = { payload, fromPubKeyHex ->
            appScope.launch {
                val conversationId = listOf(identity.publicKeyHex, fromPubKeyHex).sorted().joinToString("_")
                val fromUsername = conversationRepo.getConversation(conversationId)
                    ?.theirUsername ?: fromPubKeyHex.take(8)
                when (payload.type) {
                    TYPE_CALL_OFFER  -> cm.handleOffer(fromPubKeyHex, fromUsername, payload.callId ?: "", payload.sdp ?: "")
                    TYPE_CALL_ANSWER -> cm.handleAnswer(payload.sdp ?: "")
                    TYPE_CALL_ICE    -> cm.handleIce(payload.iceCandidateJson ?: "")
                    TYPE_CALL_HANGUP -> cm.handleRemoteHangup()
                    TYPE_CALL_REJECT -> cm.handleRemoteReject()
                }
            }
        }

        messagingService = service
    }

    /**
     * Called on app restart when identity already exists — restores messaging from stored keys.
     * Idempotent: no-op if [messagingService] is already initialised (e.g. by the foreground
     * service before the Activity's LaunchedEffect fires).
     */
    // PR-RECV-DIAG1 v1.2 — Mutex guard against the check-then-act race
    // observed in Test #84.1. Tecno log showed two coroutines (MainActivity
    // LaunchedEffect + PhantomMessagingService onStartCommand) calling
    // initMessagingFromStorage concurrently, BOTH passing the
    // `if (messagingService != null) return` gate before either wrote,
    // BOTH calling initMessaging, BOTH installing transport.incoming
    // subscriptions. Result: two `startReceiving_first_call` lines and
    // duplicate observer infrastructure. Mutex closes the race; second
    // caller waits for first to finish, then sees the populated
    // messagingService and returns early.
    private val initMessagingMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun initMessagingFromStorage() {
        initMessagingMutex.withLock {
            if (messagingService != null) {
                android.util.Log.i(
                    "PhantomMessaging",
                    "RECV_DIAG initMessagingFromStorage_already_initialized",
                )
                return@withLock
            }
            val record = _identityState.value
                ?: identityRepo.loadIdentity()?.also { _identityState.value = it }
                ?: run {
                    android.util.Log.i(
                        "PhantomMessaging",
                        "RECV_DIAG initMessagingFromStorage_no_identity",
                    )
                    return@withLock
                }
            android.util.Log.i(
                "PhantomMessaging",
                "RECV_DIAG initMessagingFromStorage_creating",
            )
            val dhKeyPair = phantom.core.crypto.DhKeyPair(
                phantom.core.crypto.DhPublicKey(record.publicKeyHex.hexToByteArray()),
                phantom.core.crypto.DhPrivateKey(record.dhPrivateKeyHex.hexToByteArray()),
            )
            initMessaging(record, dhKeyPair)
            android.util.Log.i(
                "PhantomMessaging",
                "RECV_DIAG initMessagingFromStorage_done",
            )
        }
    }
}

/**
 * Wraps SqlDelightIdentityRepository and transparently encrypts the DH private key
 * with Android Keystore (AES-256-GCM) before persisting.
 *
 * On save  → raw hex private key  →  AES-GCM encrypt  →  Base64 → stored in DB
 * On load  → Base64 from DB       →  AES-GCM decrypt  →  raw hex → returned to caller
 *
 * If the stored value looks like plain hex (migration from unencrypted builds),
 * it is used as-is — so existing installs keep working after upgrade.
 */
@OptIn(ExperimentalEncodingApi::class)
private class KeystoreIdentityRepository(
    private val delegate: IdentityRepository,
) : IdentityRepository by delegate {

    override suspend fun saveIdentity(record: IdentityRecord) {
        val encryptedPrivKey = record.dhPrivateKeyHex
            .hexToBytes()
            .let { KeystoreManager.encrypt(it) }
            .let { Base64.encode(it) }
        delegate.saveIdentity(record.copy(dhPrivateKeyHex = encryptedPrivKey))
    }

    override suspend fun loadIdentity(): IdentityRecord? {
        val raw = delegate.loadIdentity() ?: return null
        val decryptedHex = runCatching {
            Base64.decode(raw.dhPrivateKeyHex)
                .let { KeystoreManager.decrypt(it) }
                .toHexString()
        }.getOrElse { cause ->
            // Only fall back if value looks like legacy plain hex (pre-Keystore migration).
            // Base64 strings always contain +/= chars or uppercase; plain hex never does.
            val isPlainHex = raw.dhPrivateKeyHex.all { it in '0'..'9' || it in 'a'..'f' }
            if (isPlainHex) {
                raw.dhPrivateKeyHex  // one-time migration path
            } else {
                throw SecurityException("Keystore decryption failed — identity keys are inaccessible", cause)
            }
        }
        return raw.copy(dhPrivateKeyHex = decryptedHex)
    }

    override suspend fun deleteIdentity() {
        KeystoreManager.deleteKey()
        delegate.deleteIdentity()
    }
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

private fun String.hexToByteArray(): ByteArray = hexToBytes()

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
