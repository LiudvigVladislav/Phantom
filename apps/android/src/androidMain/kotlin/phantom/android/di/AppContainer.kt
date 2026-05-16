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
import phantom.core.transport.KtorRelayTransport
import phantom.core.transport.KtorTransportProbe
import phantom.core.transport.PrivacyMode
import phantom.core.transport.TorService
import phantom.core.transport.TorServiceConfig
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
        )
        // Join the bootstrap job and mark ready (success or failure) so the UI
        // can observe bootstrapReady and remove any "setting up keys…" indicator.
        appScope.launch {
            bootstrapJob.join()
            service.markBootstrapReady()
        }

        // Wire local notification callback — Android-only side-effect, not part of the KMP interface.
        service.onNewMessageNotification = { convId, sender, preview, senderPubKeyHex ->
            try {
                PhantomNotificationManager.showMessageNotification(context, convId, sender, preview, senderPubKeyHex)
            } catch (e: Throwable) {
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
    suspend fun initMessagingFromStorage() {
        if (messagingService != null) return
        val record = _identityState.value
            ?: identityRepo.loadIdentity()?.also { _identityState.value = it }
            ?: return
        val dhKeyPair = phantom.core.crypto.DhKeyPair(
            phantom.core.crypto.DhPublicKey(record.publicKeyHex.hexToByteArray()),
            phantom.core.crypto.DhPrivateKey(record.dhPrivateKeyHex.hexToByteArray()),
        )
        initMessaging(record, dhKeyPair)
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
