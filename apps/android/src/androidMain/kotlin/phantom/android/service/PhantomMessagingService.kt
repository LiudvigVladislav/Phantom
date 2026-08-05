// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import phantom.android.BuildConfig
import phantom.android.PhantomApplication
import phantom.core.transport.ConnectedTransport
import phantom.core.transport.ManagerState
import phantom.core.transport.NoTransportReachableException
import phantom.core.transport.TransportKind

/**
 * Foreground service that owns the WebSocket connection lifetime.
 *
 * Rationale: [KtorRelayTransport] runs a suspending [connect] loop with internal
 * exponential-backoff reconnect. Running it inside an Activity [LaunchedEffect]
 * means the socket is torn down whenever the app is backgrounded. Moving it here
 * keeps the socket alive regardless of Activity lifecycle.
 *
 * Architecture decision recorded here (not hidden in implementation):
 * - The service calls [AppContainer.initMessagingFromStorage] then
 *   [MessagingService.startReceiving] then [RelayTransport.connect].
 * - The Activity no longer calls connect/startReceiving directly.
 * - START_STICKY ensures Android restarts this service if the process is killed.
 */
class PhantomMessagingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Held for the lifetime of the foreground service. Without these, QA-v7
    // showed a real Wi-Fi-connected phone losing its WebSocket every ~64 s
    // while an emulator on the same Wi-Fi router stayed connected for hours
    // — the difference being that the OEM Android build was parking the
    // Wi-Fi radio between transmissions to save power, even with a
    // foreground notification visible. WIFI_MODE_FULL_HIGH_PERF disables
    // that parking; PARTIAL_WAKE_LOCK keeps the CPU available so the
    // OkHttp ping/pong scheduler is not deferred into doze windows.
    //
    // Both locks are released in onDestroy(). They are scoped tightly to
    // this service, so they only contribute to battery drain while the
    // user has chosen to keep the messenger running.
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // PR-F2 (2026-05-12): atomic CAS guard to prevent multiple parallel
    // onStartCommand callbacks from each spawning their own connect path.
    // Test #26 relay logs captured 5 simultaneous `event="connect"` events
    // for the same identity within 30 ms — five parallel coroutines all
    // raced past the previous `@Volatile var connectStarted = false` guard
    // (volatile gives visibility but NOT atomic check-then-set), each
    // opened its own WebSocket, and the relay's `state.clients[identity]`
    // map ended up with whichever registered last. The other four became
    // server-side zombies that never got pongs (relay routes pong by
    // identity → only latest gen) and triggered self-perpetuating
    // forceReconnect cascades on the client.
    //
    // AtomicBoolean.compareAndSet(false, true) gives us the atomic
    // "first-caller-wins" semantics we need.
    private val connectStarted = AtomicBoolean(false)

    /**
     * PR-LTE-NETCHANGE1 P1 fix (architect 2026-05-28): generation token
     * that closes the "stale-cleanup-reopens-CAS-while-new-generation-
     * is-running" race.
     *
     * Race scenario without this fix:
     * 1. Generation A is running its serviceScope.launch block.
     * 2. Rewalk fires → coordinator runs → `EXTRA_REWALK_RESTART` intent
     *    resets `connectStarted` CAS to false.
     * 3. Generation B's launch starts, CAS succeeds, B is alive.
     * 4. Generation A's `KtorRelayTransport.connect()` returns (because
     *    `disconnect()` cancelled its internal `reconnectJob`).
     * 5. Generation A reaches its `connectStarted.set(false)` cleanup
     *    line (one of four sites) and naively resets the CAS — WHILE
     *    Generation B is still holding it.
     * 6. A third `onStartCommand` (AlarmManager, ConnectivityChange)
     *    races past the now-open CAS and starts Generation C in
     *    parallel with B.
     *
     * Fix pattern: each generation claims a unique token via
     * `incrementAndGet()` AFTER its CAS succeeds (so failed-CAS launches
     * do NOT consume a token). At cleanup time, only reset the CAS if
     * `connectGeneration.get() == myGen` — the current value. If a
     * subsequent generation has claimed a higher token, the current
     * generation knows it is no longer the canonical owner of the CAS
     * and skips the reset.
     *
     * Rewalk's `EXTRA_REWALK_RESTART` path still resets the CAS to
     * unblock the next generation; it does not need to bump the token
     * itself because the next launch will claim a fresh token on its
     * own.
     */
    private val connectGeneration = AtomicLong(0L)
    // ADR Tier-1 (HiOS workaround): MulticastLock changes the Wi-Fi
    // radio idle profile on aggressive OEMs (Tecno HiOS, Infinix XOS,
    // Xiaomi MIUI) where battery management ignores both
    // setUnlockedDeviceRequired battery override and WifiLock.
    // Multicast reception requires the radio not to deep-park, so
    // holding this lock keeps the radio out of the most aggressive
    // sleep state. Confirmed in 2026-05-02 QA: pong becomes stale
    // every ~30 s without it, on a healthy Wi-Fi where the same
    // emulator runs for 5+ min uninterrupted.
    private var multicastLock: WifiManager.MulticastLock? = null

    // ADR-020 Phase 2: embedded Tor and Xray subsystems are now owned by
    // AppContainer.transportManager. The service no longer holds direct
    // references — it asks the manager for a connected transport and gets back
    // a ConnectedTransport(kind, socksPort) it then hands to KtorRelayTransport.

    // Tracks the foreground notification updater coroutine so
    // we can launch it once in onCreate and not duplicate on Android's
    // potential redelivered onStartCommand intents.
    private var notificationUpdaterJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        // PR-RECV-DIAG1 — mirror under PhantomMessaging tag so the
        // standard logcat filter `PhantomMessaging:V` catches service
        // lifecycle. Test #83.6c proved that PhantomMessagingService
        // tag is exact-match invisible to that filter.
        Log.i("PhantomMessaging", "RECV_DIAG service_onCreate pid=${android.os.Process.myPid()}")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(DEFAULT_STATUS_TEXT))
        acquireKeepAliveLocks()
        // ADR-020 Phase 2: subscribe to TransportManager state for live
        // foreground-notification text. This replaces the old per-subsystem
        // updater (one branch per Tor / Xray / direct) — the manager surfaces
        // a single ManagerState that already abstracts the chain walk.
        startTransportNotificationUpdater()
        // PR-D1b (2026-05-16): also observe the REST fallback state machine
        // so an honest "Online via Direct · Limited realtime" label appears
        // when the WS frame layer has degraded and we're polling REST. Pure
        // overlay — on recovery to WS_ACTIVE the next TransportManager state
        // emission resets the notification to its normal label.
        startRestFallbackNotificationOverlay()
        // PR-LTE-NETCHANGE1 P2 fix (architect 2026-05-28): the
        // NetworkChangeObserver registration was previously kicked off
        // from BOTH onCreate (here) AND onStartCommand's post-init
        // success branch. Test #88 logs A/B/D each contained two
        // `NETWORK_TRACE observer_registered` lines because the two
        // paths could race past the @Volatile `registered` check before
        // either wrote it.
        //
        // The onCreate path is now removed entirely. Cold start sees a
        // null observer here anyway (AppContainer constructs it inside
        // initMessagingFromStorage, which has not yet run at onCreate
        // time), so the path did no useful work on cold; on warm starts
        // it just raced. The single registration point is now
        // onStartCommand's `runCatching { container.initMessagingFromStorage() }
        // .onSuccess { container.networkChangeObserver?.register() }`
        // — guaranteed to run after the observer exists, and now also
        // atomic via `synchronized` inside `register()` itself.
    }

    private fun startTransportNotificationUpdater() {
        if (notificationUpdaterJob?.isActive == true) return
        notificationUpdaterJob = serviceScope.launch {
            val app = application as PhantomApplication
            runCatching { app.ready.await() }
            val mgr = app.container.transportManager
            val prefs = app.container.transportPreferences
            mgr.state.collect { state ->
                // Bug #2 fix: include PrivacyMode in the visible text so a
                // mode switch that lands on the SAME working transport (e.g.
                // Standard → Private both end up on REALITY) is still visible
                // to the user.
                val mode = prefs.privacyMode.name
                val text = when (state) {
                    is ManagerState.Idle ->
                        "$DEFAULT_STATUS_TEXT · $mode"
                    is ManagerState.Probing ->
                        // For Tor: surface the time-based bootstrap stage,
                        // current percent, current bridge profile, and the
                        // 1-based "(N/total)" rotation index so the user
                        // can see what's happening during the multi-minute
                        // bridge negotiation instead of a silent
                        // "Connecting via Tor…" (PR-B + PR-C). For
                        // Direct / Reality: keep the original short text —
                        // those probes are sub-second and the staged copy
                        // would only flicker.
                        state.torStatus?.let { tor ->
                            "${tor.stage.userText} ${tor.percent}% " +
                                "· ${tor.bridgeProfile.displayName} (${tor.attempt}/${tor.totalAttempts}) " +
                                "· $mode"
                        } ?: "Connecting via ${state.kind}… · $mode"
                    is ManagerState.Connected ->
                        "Online via ${state.kind} · $mode"
                    is ManagerState.AllFailed ->
                        // Bug #3 fix: drop the misleading "Tap to retry" — the
                        // notification body has no PendingIntent for it, so
                        // tapping does nothing. Real retry path is the Privacy
                        // Mode selector in Settings (which calls
                        // setPrivacyMode → release → fresh chain walk).
                        //
                        // PR-D (2026-05-12): give Ghost-mode users a concrete
                        // next step instead of a dead-end "Cannot reach". On
                        // some censored networks (notably МТС RU without VPN)
                        // every Tor bridge profile times out — telling the
                        // user to try Private/Reality or enable a VPN turns
                        // a frustrating wall into actionable advice. Standard
                        // and Private already keep the original copy because
                        // a Direct/Reality failure usually means the relay
                        // itself is unreachable, not a censorship layer.
                        if (state.attempts.any {
                                it.kind == phantom.core.transport.TransportKind.Tor
                            }
                        ) {
                            "Tor is blocked or slowed by this network. " +
                                "Try Private/Reality or enable a VPN. · $mode"
                        } else {
                            "Cannot reach relay (tried ${state.attempts.size}) · $mode"
                        }
                }
                Log.i(
                    TAG,
                    "TransportManager state → ${state::class.simpleName} mode=$mode text=\"$text\"",
                )
                pushNotificationText(text)
            }
        }
    }

    private fun startRestFallbackNotificationOverlay() {
        serviceScope.launch {
            val app = application as PhantomApplication
            runCatching { app.ready.await() }
            val hybrid = app.container.hybridTransport ?: return@launch
            val prefs = app.container.transportPreferences
            val transportManager = app.container.transportManager
            hybrid.stateMachine.state.collect { mode ->
                val modeLabel = prefs.privacyMode.name
                // DWS-UX.1 (2026-06-17): read the currently-connected outer
                // transport kind instead of hardcoding "Direct". The
                // earlier text "Online via Direct · …" was a factual lie
                // when the user was in Ghost (Tor) or Private (Reality)
                // and the REST overlay fired — the relay was reachable
                // via the configured outer transport, NOT via a fresh
                // Direct connection. Read the kind from TransportManager;
                // fall back to "relay" on transient null state so the
                // string is still a coherent sentence.
                val transportName = Companion.transportNameForOverlay(
                    transportManager.state.value,
                )
                val text = when (mode) {
                    phantom.core.transport.RestMode.RestActive ->
                        "Online via $transportName · Limited realtime · $modeLabel"
                    phantom.core.transport.RestMode.WsCandidate ->
                        "Online via $transportName · Recovering · $modeLabel"
                    phantom.core.transport.RestMode.WsActive ->
                        null // Let the TransportManager state collector reassert
                }
                if (text != null) {
                    Log.i(TAG, "REST_TRACE notification_overlay mode=$mode text=\"$text\"")
                    pushNotificationText(text)
                }
            }
        }
    }

    private fun pushNotificationText(text: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, buildNotification(text))
        }.onFailure {
            Log.w(TAG, "Notification update failed: ${it.message}")
        }
    }

    private fun acquireKeepAliveLocks() {
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "phantom:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WifiLock acquired (FULL_HIGH_PERF)")
        }.onFailure { Log.w(TAG, "WifiLock acquire failed: ${it.message}") }

        runCatching {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phantom:cpu").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WakeLock acquired (PARTIAL)")
        }.onFailure { Log.w(TAG, "WakeLock acquire failed: ${it.message}") }

        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("phantom:multicast").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "MulticastLock acquired — keeps Wi-Fi radio out of deep-park on aggressive OEMs")
        }.onFailure { Log.w(TAG, "MulticastLock acquire failed: ${it.message}") }
    }

    private fun releaseKeepAliveLocks() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (multicastLock?.isHeld == true) multicastLock?.release() }
        wifiLock = null
        wakeLock = null
        multicastLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        // PR-RECV-DIAG1 — mirror service-lifecycle milestones under
        // PhantomMessaging tag so they show up in the standard log
        // filter Vladislav uses (PhantomMessaging:V exact-match).
        Log.i("PhantomMessaging", "RECV_DIAG service_onStartCommand startId=$startId flags=$flags")

        // PR-LTE-NETCHANGE1 (2026-05-28): if this onStartCommand was
        // triggered by `TransportRewalkCoordinator.requestServiceRestart`
        // (architect-locked single re-entry path), force-reset the
        // `connectStarted` CAS so the next connect attempt re-runs the
        // chain walk against the new network. Without this reset, the
        // CAS guard at line ~335 would treat the rewalk as a duplicate
        // and short-circuit.
        //
        // The coordinator already executed: state-machine notify, hint
        // clear, hybrid.disconnect(), transportManager.release(). All we
        // do here is unblock the re-entry path.
        if (intent?.getBooleanExtra(EXTRA_REWALK_RESTART, false) == true) {
            val reason = intent.getStringExtra(EXTRA_REWALK_REASON) ?: "unknown"
            Log.i(
                "PhantomHybrid",
                "NETWORK_TRACE service_restart_received reason=$reason — resetting connectStarted CAS",
            )
            connectStarted.set(false)
        }

        serviceScope.launch {
            val app = application as PhantomApplication
            // Wait for libsodium + AppContainer to be fully initialised before using them.
            runCatching { app.ready.await() }.onFailure { t ->
                Log.e(TAG, "App init failed — service cannot start: ${t.message}")
                Log.e("PhantomMessaging", "RECV_DIAG service_app_init_failed err=${t::class.simpleName}")
                stopSelf()
                return@launch
            }
            Log.i("PhantomMessaging", "RECV_DIAG service_app_ready")

            val container = app.container

            // Ensure messaging is wired (no-op if already done by Activity on warm start).
            runCatching { container.initMessagingFromStorage() }
                .onSuccess {
                    Log.i("PhantomMessaging", "RECV_DIAG container_init_ok")
                    // PR-LTE-NETCHANGE1 P1 fix (architect 2026-05-28): the
                    // observer is CREATED inside initMessagingFromStorage,
                    // not before. The onCreate-launched register call may
                    // have raced ahead and seen `observer = null`, doing a
                    // silent no-op. Register here as well (idempotent via
                    // the `if (registered) return` guard on NetworkChange
                    // Observer.register) so the observer is guaranteed
                    // registered by the time we kick off the connect path.
                    runCatching { container.networkChangeObserver?.register() }
                        .onFailure { e ->
                            Log.w(
                                "PhantomHybrid",
                                "NETWORK_TRACE observer_register_post_init_throw " +
                                    "errorClass=${e::class.simpleName} message=${e.message?.take(120)}",
                            )
                        }
                }
                .onFailure { e ->
                    Log.e(TAG, "initMessagingFromStorage failed: ${e.message}")
                    Log.e("PhantomMessaging", "RECV_DIAG container_init_fail err=${e::class.simpleName}")
                }

            val service = container.messagingService
            if (service == null) {
                Log.w(TAG, "messagingService is null after init — no identity yet, stopping")
                Log.w("PhantomMessaging", "RECV_DIAG service_messagingService_null reason=no_identity")
                stopSelf()
                return@launch
            }
            Log.i("PhantomMessaging", "RECV_DIAG messagingService_acquired")

            // startReceiving is idempotent; safe to call even if Activity already called it.
            runCatching { service.startReceiving() }
                .onSuccess {
                    Log.d(TAG, "startReceiving OK")
                    Log.i("PhantomMessaging", "RECV_DIAG startReceiving_ok")
                }
                .onFailure { e ->
                    Log.e(TAG, "startReceiving failed: ${e.message}")
                    Log.e("PhantomMessaging", "RECV_DIAG startReceiving_fail err=${e::class.simpleName}")
                }

            // connect() runs an infinite suspend loop with internal reconnect — it only
            // returns on unrecoverable failure or when disconnect() is called.
            val myPubKey = container.identityRepo.loadIdentity()?.publicKeyHex
            if (myPubKey == null) {
                Log.w(TAG, "No local identity — cannot open WebSocket")
                return@launch
            }
            // ADR-020 Phase 2: outer transport is now selected at runtime by
            // TransportManager. It walks the strategy chain implied by the
            // user's PrivacyMode (Standard → DIRECT_FIRST, Private →
            // REALITY_FIRST, Ghost → TOR_FIRST), starts the matching
            // subsystem, probes /health through it, and returns the first
            // ConnectedTransport that reaches the relay. Last-working hint
            // is recorded so subsequent connects skip dead paths.
            //
            // RELAY_ONION_URL is consumed only when the chosen kind is Tor;
            // Direct and Reality both exit via the public WSS endpoint
            // (Reality just tunnels that exit through its outer envelope).
            // Guard against a second onStartCommand (e.g. AlarmManager wakeup,
            // foreground bring-back, ConnectivityChange broadcast) arriving while
            // the first connect path is still establishing. Uses atomic CAS so
            // racing coroutines all see consistent state — the first one wins,
            // every other one bails. PR-F2.
            if (!connectStarted.compareAndSet(false, true)) {
                Log.d(TAG, "connect already in progress — duplicate onStartCommand ignored")
                return@launch
            }

            // PR-LTE-NETCHANGE1 P1 fix (architect 2026-05-28): claim a
            // generation token AFTER CAS succeeds, so failed-CAS launches
            // do not bump it. Every `connectStarted.set(false)` cleanup
            // site below is wrapped in [resetConnectStartedIfCurrent] so
            // a stale generation (cancelled by a rewalk) cannot clobber
            // the CAS while a fresher generation is alive.
            val myGen = connectGeneration.incrementAndGet()
            Log.i(
                "PhantomHybrid",
                "NETWORK_TRACE generation_claimed gen=$myGen",
            )

            // F11 + F26: signed-challenge auth requires our Ed25519 signing
            // keypair. Resolve BEFORE asking TransportManager to start an
            // outer subsystem — no point bootstrapping Tor / Xray if we
            // cannot present a valid signed challenge once the WSS opens.
            val signingPair = container.identityManager.loadSigningKeyPair()
            if (signingPair == null) {
                Log.w(
                    TAG,
                    "Cannot connect: Ed25519 signing keypair not provisioned yet (migration pending). Service will exit; foreground restart after onboarding will reconnect.",
                )
                resetConnectStartedIfCurrent(myGen, "signingPair_null")
                return@launch
            }
            val signingPubKeyHex = signingPair.publicKey.bytes
                .joinToString("") { ((it.toInt() and 0xFF) or 0x100).toString(16).substring(1) }

            // RC-DIRECT-STABILITY1 Arm A short-circuit. When DEBUG_BYPASS_URL
            // is non-empty in a debug build, route the service to the
            // Caddy-bypass diagnostic raw-OkHttp socket instead of the
            // production Hybrid Ktor `transport.connect(...)` path. Same
            // Inv-ParallelArmIsolation rationale as Arm B below — production
            // and diagnostic WS must never share `state.clients[identity]`.
            //
            // This branch is checked BEFORE the Arm B branch so that if
            // both flags were somehow set simultaneously, Arm A takes
            // precedence (the bypass URL is the more specific override).
            // Both arms should never be active at once in practice — they
            // measure different things and would compete for the same
            // identity slot on the relay.
            //
            // Release builds (`!BuildConfig.DEBUG`) NEVER enter this branch
            // even if `DEBUG_BYPASS_URL` was somehow non-empty — the release
            // BuildConfig block pins it to "" as defence-in-depth.
            //
            // Locked in `docs/tracks/rc-direct-stability1.md` §4 Arm A + §7 step 2.
            if (phantom.android.BuildConfig.DEBUG &&
                phantom.android.BuildConfig.DEBUG_BYPASS_URL.isNotEmpty()
            ) {
                Log.i(
                    "RC_DIRECT_ARM_A",
                    "RC_DIRECT_ARM_A_service_short_circuit " +
                        "identity_prefix=${myPubKey.take(16)} " +
                        "signing_prefix=${signingPubKeyHex.take(16)} " +
                        "bypass_url=${phantom.android.BuildConfig.DEBUG_BYPASS_URL} " +
                        "gen=$myGen",
                )
                container.rcDirectArmA?.start(myPubKey, signingPubKeyHex)
                // Service stays alive (foreground service is the diagnostic
                // host); the arm runs its own reconnect loop until cancelled
                // via container.rcDirectArmA?.stop() or the app dies.
                return@launch
            }

            // RC-DIRECT-STABILITY1 Arm A.2 short-circuit. When
            // DEBUG_RC_DIRECT_ARM_A2_URL is non-empty in a debug build,
            // route the service to the public non-Caddy TLS bypass
            // diagnostic raw-OkHttp socket (stunnel on host `:8444`)
            // instead of the production Hybrid Ktor `transport.connect(...)`
            // path. Same Inv-ParallelArmIsolation rationale as Arm A
            // above — production and diagnostic WS must never share
            // `state.clients[identity]` at the relay.
            //
            // Precedence per §7 step 5e (locked in mini-lock): Arm A
            // (Caddy-bypass loopback URL) → Arm A.2 (public non-Caddy
            // TLS bypass URL via stunnel `:8444`) → Arm B (raw OkHttp
            // baseline through Caddy `:443`) → Arm C (ping interval
            // matrix) → Arm D (heartbeat echo) → production. Arms A
            // and A.2 both use a `BuildConfig.DEBUG_*_URL.isNotEmpty()`
            // gate; they are mutually exclusive in practice because a
            // build sets one or the other. If both happened to be set,
            // Arm A wins (above) because its block is earlier — the
            // narrower override.
            //
            // Release builds (`!BuildConfig.DEBUG`) NEVER enter this
            // branch even if `DEBUG_RC_DIRECT_ARM_A2_URL` was somehow
            // non-empty — the release BuildConfig block pins it to "".
            //
            // Server-side dependency: this branch is meaningful only if
            // the §4 Arm A.2 PR-8a stunnel overlay is deployed and
            // verified on the VPS (`docker compose -f docker-compose.yml
            // -f docker-compose.armA2.yml up -d stunnel-arm-a2`). Without
            // that, the URL `wss://relay.phntm.pro:8444/ws` returns
            // connection refused and Arm A.2 logs ws_failure on every
            // session.
            //
            // Locked in `docs/tracks/rc-direct-stability1.md` §4 Arm A.2
            // + §7 step 5e + PR-8a implementation record subsection.
            if (phantom.android.BuildConfig.DEBUG &&
                phantom.android.BuildConfig.DEBUG_RC_DIRECT_ARM_A2_URL.isNotEmpty()
            ) {
                Log.i(
                    "RC_DIRECT_ARM_A2",
                    "RC_DIRECT_ARM_A2_service_short_circuit " +
                        "identity_prefix=${myPubKey.take(16)} " +
                        "signing_prefix=${signingPubKeyHex.take(16)} " +
                        "bypass_url=${phantom.android.BuildConfig.DEBUG_RC_DIRECT_ARM_A2_URL} " +
                        "gen=$myGen",
                )
                container.rcDirectArmA2?.start(myPubKey, signingPubKeyHex)
                // Service stays alive (foreground service is the diagnostic
                // host); the arm runs its own reconnect loop until cancelled
                // via container.rcDirectArmA2?.stop() or the app dies.
                return@launch
            }

            // RC-DIRECT-STABILITY1 §10 T2 short-circuit. When DEBUG_T2_SLOW_POST_URL
            // is non-empty in a debug build, route the service to the slow-POST
            // byte-threshold diagnostic instead of the production Hybrid Ktor
            // `transport.connect(...)` path. Same Inv-ParallelArmIsolation
            // rationale as Arms A / A.2 / B / C / D above.
            //
            // T2 is **ONE-SHOT** — NOT a reconnect loop. One POST sends 40 960
            // bytes chunked over ~70-80 s, the POST completes (or aborts), and
            // the diagnostic job terminates. The Service stays alive (it's the
            // foreground host) but T2 itself is finished after one run. Re-
            // running requires killing the app and starting it again with the
            // BuildConfig flag still set.
            //
            // Precedence per §7 step 5f (T2 inserted between A.2 and B):
            // Arm A → Arm A.2 → T2 → Arm B → Arm C → Arm D → production. T2
            // and the WebSocket arms are mutually exclusive in practice
            // because a build sets DEBUG_T2_SLOW_POST_URL OR DEBUG_RC_DIRECT_*
            // — never both.
            //
            // Release builds (`!BuildConfig.DEBUG`) NEVER enter this branch
            // even if `DEBUG_T2_SLOW_POST_URL` was somehow non-empty — the
            // release BuildConfig block pins it to "".
            //
            // Server-side dependency: this branch is meaningful only if the
            // operator has flipped `RELAY_ENABLE_SLOW_POST_DIAG=1` on the
            // VPS `.env` and recreated relay so `/diag/slow-post` is mounted.
            // Without that, the endpoint returns 404 and T2 logs failure on
            // first POST.
            //
            // Locked in `docs/tracks/rc-direct-stability1.md` §10 T2 mini-lock.
            if (phantom.android.BuildConfig.DEBUG &&
                phantom.android.BuildConfig.DEBUG_T2_SLOW_POST_URL.isNotEmpty()
            ) {
                Log.i(
                    "T2_SLOW_POST",
                    "T2_SLOW_POST_service_short_circuit " +
                        "identity_prefix=${myPubKey.take(16)} " +
                        "endpoint_url=${phantom.android.BuildConfig.DEBUG_T2_SLOW_POST_URL} " +
                        "gen=$myGen",
                )
                container.t2SlowPostDiag?.start()
                // Service stays alive (foreground service is the diagnostic
                // host); T2 runs ONE shot and the job terminates. No
                // reconnect loop. Re-run requires app restart.
                return@launch
            }

            // PR-RC-DIRECT-WS-DEATH1 Phase 1 Arm B short-circuit. When the
            // diagnostic flag selects Arm B, the production Hybrid Ktor path
            // is bypassed entirely so the diagnostic raw-OkHttp socket and a
            // production socket cannot collide on the relay's
            // state.clients[identity] map (Inv-ParallelArmIsolation).
            //
            // Inv-NoProductionBehaviour: the gate is
            // `BuildConfig.DEBUG && BuildConfig.DEBUG_RC_DIRECT_ARM == "B"`.
            // Release builds (`!BuildConfig.DEBUG`) NEVER enter this branch
            // even if the flag string was somehow non-"0" — the release
            // BuildConfig block pins it to "0" as defence-in-depth.
            //
            // Locked in `docs/tracks/rc-direct-ws-death1.md` § Commit 3.2b
            // (rev4) §7 step 3.
            if (phantom.android.BuildConfig.DEBUG &&
                phantom.android.BuildConfig.DEBUG_RC_DIRECT_ARM == "B"
            ) {
                Log.i(
                    "RC_DIRECT_ARM_B",
                    "RC_DIRECT_ARM_B_service_short_circuit " +
                        "identity_prefix=${myPubKey.take(16)} " +
                        "signing_prefix=${signingPubKeyHex.take(16)} " +
                        "gen=$myGen",
                )
                container.rcDirectArmB?.start(myPubKey, signingPubKeyHex)
                // Service stays alive (foreground service is the diagnostic
                // host); the arm runs its own reconnect loop until cancelled
                // via container.rcDirectArmB?.stop() or the app dies.
                return@launch
            }

            // RC-DIRECT-STABILITY1 Arm C short-circuit. When the ping
            // interval matrix flag is non-"0" in a debug build, route the
            // service to the cadence diagnostic raw-OkHttp socket instead
            // of the production Hybrid Ktor `transport.connect(...)` path.
            // Same Inv-ParallelArmIsolation rationale as Arm A and Arm B
            // above.
            //
            // Precedence: Arm A (bypass URL) → Arm B (raw OkHttp baseline)
            // → Arm C (ping interval matrix) → production. They are all
            // sequential diagnostic experiments and should never be active
            // at once in practice — they would compete for the same
            // identity slot on the relay.
            //
            // Release builds (`!BuildConfig.DEBUG`) NEVER enter this branch
            // even if `DEBUG_RC_DIRECT_PING_INTERVAL_MS` was somehow non-"0"
            // — the release BuildConfig block pins it to "0" as
            // defence-in-depth.
            //
            // Locked in `docs/tracks/rc-direct-stability1.md` §4 Arm C + §7 step 4.
            if (phantom.android.BuildConfig.DEBUG &&
                phantom.android.BuildConfig.DEBUG_RC_DIRECT_PING_INTERVAL_MS != "0"
            ) {
                Log.i(
                    "RC_DIRECT_ARM_C",
                    "RC_DIRECT_ARM_C_service_short_circuit " +
                        "identity_prefix=${myPubKey.take(16)} " +
                        "signing_prefix=${signingPubKeyHex.take(16)} " +
                        "ping_interval_ms=${phantom.android.BuildConfig.DEBUG_RC_DIRECT_PING_INTERVAL_MS} " +
                        "gen=$myGen",
                )
                container.rcDirectArmC?.start(myPubKey, signingPubKeyHex)
                return@launch
            }

            // RC-DIRECT-STABILITY1 Arm D short-circuit. When the heartbeat
            // echo flag is "1" in a debug build, route the service to the
            // data-frame heartbeat diagnostic raw-OkHttp socket instead
            // of the production Hybrid Ktor `transport.connect(...)` path.
            // Same Inv-ParallelArmIsolation rationale as Arms A / B / C
            // above.
            //
            // Precedence: Arm A (bypass URL) → Arm B (raw OkHttp baseline)
            // → Arm C (ping interval matrix) → Arm D (heartbeat echo) →
            // production. They are all sequential diagnostic experiments
            // and should never be active at once in practice — they would
            // compete for the same identity slot on the relay.
            //
            // Release builds (`!BuildConfig.DEBUG`) NEVER enter this branch
            // even if `DEBUG_RC_DIRECT_HEARTBEAT_ECHO` was somehow non-"0"
            // — the release BuildConfig block pins it to "0".
            //
            // Locked in `docs/tracks/rc-direct-stability1.md` §4 Arm D + §7 step 5.
            if (phantom.android.BuildConfig.DEBUG &&
                phantom.android.BuildConfig.DEBUG_RC_DIRECT_HEARTBEAT_ECHO == "1"
            ) {
                Log.i(
                    "RC_DIRECT_ARM_D",
                    "RC_DIRECT_ARM_D_service_short_circuit " +
                        "identity_prefix=${myPubKey.take(16)} " +
                        "signing_prefix=${signingPubKeyHex.take(16)} " +
                        "gen=$myGen",
                )
                container.rcDirectArmD?.start(myPubKey, signingPubKeyHex)
                return@launch
            }

            // RC-DIRECT-STABILITY1 §14 Arm G short-circuit. When
            // DEBUG_RC_DIRECT_ARM_G_VIA_REALITY is exactly "1" in a debug
            // build, route the service to the Reality-tunneled WS heartbeat
            // diagnostic. Arm G's OkHttp client connects through a SOCKS5
            // proxy at `127.0.0.1:<Ready.socksPort>` provided by the
            // embedded libXray daemon (production `xrayService` singleton),
            // which wraps the outbound stream in VLESS+REALITY to the
            // Stage 5E production endpoint at `:8443`. The inner target
            // endpoint stays `BuildConfig.RELAY_URL` (production WSS
            // through Caddy) — single-variable change vs Arm D baseline.
            //
            // **Transport isolation, NOT structural bootstrap isolation**
            // (per §14 hard gate 6 + PR-G1 fixup commit `06486195`). This
            // short-circuit prevents production `transport.connect(...)`
            // — no production `KtorRelayTransport` WS to relay in parallel.
            // **However**, `container.initMessagingFromStorage()` and
            // `service.startReceiving()` already ran at lines ~344-393
            // above. MessagingService internal state may therefore still
            // generate short-lived `prekey_publish` / `rest_session_issued`
            // REST traffic during the Arm G capture window. This is the
            // same surface §13 T2 hit per the T2 Outcome isolation caveat.
            // Mitigation: PR-G3 outcome capture grep-verifies absence (or
            // annotates counts + timings) of `PREKEY_TRACE|REST_TRACE|
            // prekey_publish|rest_session_issued` in both the UTF-8-
            // decoded Tecno logcat (per §13 T2 Outcome UTF-16-vs-ASCII
            // grep-mismatch lesson) and the relay log over the Arm G
            // window.
            //
            // Precedence per §14 hard gate 7: Arm A → Arm A.2 → T2 →
            // Arm B → Arm C → Arm D → **Arm G** → production. All
            // diagnostic arms are sequential `if` blocks gated by
            // mutually-exclusive BuildConfig flags; only one arm runs
            // per build.
            //
            // Release builds (`!BuildConfig.DEBUG`) NEVER enter this branch
            // even if `DEBUG_RC_DIRECT_ARM_G_VIA_REALITY` was somehow
            // non-empty — the release BuildConfig block pins it to "".
            //
            // Server-side dependency: this branch is meaningful only if
            // the operator has flipped `RELAY_ENABLE_HEARTBEAT_ECHO=1` on
            // the VPS `.env` (same flag Arm A.2 / Arm D used). Without
            // that, Arm G logs `echo_sent` but never `echo_received`,
            // which still produces a useful (PARTIAL or FAIL) signal but
            // is not the intended PASS experiment.
            //
            // Locked in `docs/tracks/rc-direct-stability1.md` §14 Arm G
            // mini-lock (PR #294 squash `f0b436a5` master 2026-06-05).
            if (phantom.android.BuildConfig.DEBUG &&
                phantom.android.BuildConfig.DEBUG_RC_DIRECT_ARM_G_VIA_REALITY == "1"
            ) {
                Log.i(
                    "RC_DIRECT_ARM_G",
                    "RC_DIRECT_ARM_G_service_short_circuit " +
                        "identity_prefix=${myPubKey.take(16)} " +
                        "signing_prefix=${signingPubKeyHex.take(16)} " +
                        "relay_url=${phantom.android.BuildConfig.RELAY_URL} " +
                        "gen=$myGen",
                )
                container.rcDirectArmG?.start(myPubKey, signingPubKeyHex)
                return@launch
            }

            val connected: ConnectedTransport = try {
                container.transportManager.connect()
            } catch (e: NoTransportReachableException) {
                Log.e(TAG, "TransportManager: no path reachable — ${e.message}", e)
                resetConnectStartedIfCurrent(myGen, "transportManager_no_path")
                return@launch
            } catch (t: Throwable) {
                Log.e(TAG, "TransportManager.connect threw: ${t::class.simpleName}: ${t.message}", t)
                resetConnectStartedIfCurrent(myGen, "transportManager_connect_threw")
                return@launch
            }
            val socksProxyPort: Int? = connected.socksPort
            val relayUrl =
                if (connected.kind == TransportKind.Tor) BuildConfig.RELAY_ONION_URL
                else BuildConfig.RELAY_URL
            Log.i(
                "PhantomRelay",
                "PhantomMessagingService about to connect: " +
                    "url=$relayUrl " +
                    "auth=signed-challenge " +
                    "socks=${socksProxyPort ?: "direct"} " +
                    "myPubKey=${myPubKey.take(16)}… " +
                    "signing=${signingPubKeyHex.take(16)}…",
            )
            // ADR-011: schedule the AlarmManager wakeup BEFORE entering
            // the suspending connect loop. connect() doesn't return until
            // disconnect() is called — if we scheduled after, the alarm
            // would never be set up. Idempotent: re-schedules replace.
            val exactGranted = PhantomWakeupReceiver.schedule(applicationContext)
            Log.d(
                TAG,
                "AlarmManager keepalive scheduled (exact=$exactGranted, interval=${PhantomWakeupReceiver.WAKEUP_INTERVAL_MS}ms)",
            )

            runCatching {
                container.transport.connect(
                    relayUrl = relayUrl,
                    identityPublicKeyHex = myPubKey,
                    signingPublicKeyHex = signingPubKeyHex,
                    signChallenge = { nonce -> container.identityManager.signRelayChallenge(nonce) },
                    socksProxyPort = socksProxyPort,
                )
            }.onFailure { e ->
                Log.e(TAG, "Transport connect loop exited: ${e.message}", e)
                Log.e("PhantomRelay", "Transport connect loop exited: ${e.message}", e)
            }
            resetConnectStartedIfCurrent(myGen, "transport_loop_exited")
        }
        return START_STICKY
    }

    /**
     * PR-LTE-NETCHANGE1 P1 fix (architect 2026-05-28): cleanup-time CAS
     * reset gated by generation token. See `connectGeneration` field
     * kdoc for race scenario. Logs the skip case so a stale-cleanup
     * incident is visible in the future logcat without code reading.
     */
    private fun resetConnectStartedIfCurrent(myGen: Long, site: String) {
        val current = connectGeneration.get()
        if (current == myGen) {
            connectStarted.set(false)
            Log.i(
                "PhantomHybrid",
                "NETWORK_TRACE generation_cleanup gen=$myGen site=$site connectStarted=false",
            )
        } else {
            Log.i(
                "PhantomHybrid",
                "NETWORK_TRACE generation_stale skip_cas_reset myGen=$myGen current=$current site=$site",
            )
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — disconnecting transport")
        super.onDestroy()
        // RC-DIRECT-STABILITY1 Arm A teardown. Stop the Caddy-bypass
        // diagnostic socket before the rest of cleanup so a service
        // restart cannot race two diagnostic sockets sharing
        // `state.clients[identity]` at the relay (Inv-ParallelArmIsolation
        // carried forward from Phase 1). Same debug + flag gate as the
        // start site in onStartCommand. Release builds never reach this
        // branch — the release BuildConfig pins DEBUG_BYPASS_URL to "".
        if (phantom.android.BuildConfig.DEBUG &&
            phantom.android.BuildConfig.DEBUG_BYPASS_URL.isNotEmpty()
        ) {
            runCatching {
                (application as PhantomApplication).container.rcDirectArmA?.stop()
            }.onFailure {
                Log.w(TAG, "RC_DIRECT_ARM_A_stop_failed: ${it.message}")
            }
        }
        // RC-DIRECT-STABILITY1 Arm A.2 teardown. Stop the public non-Caddy
        // TLS bypass diagnostic socket (stunnel :8444) before the rest of
        // cleanup so a service restart cannot race two diagnostic sockets
        // sharing `state.clients[identity]` at the relay
        // (Inv-ParallelArmIsolation carried forward from Phase 1). Same
        // debug + flag gate as the start site in onStartCommand. Release
        // builds never reach this branch — the release BuildConfig pins
        // DEBUG_RC_DIRECT_ARM_A2_URL to "".
        if (phantom.android.BuildConfig.DEBUG &&
            phantom.android.BuildConfig.DEBUG_RC_DIRECT_ARM_A2_URL.isNotEmpty()
        ) {
            runCatching {
                (application as PhantomApplication).container.rcDirectArmA2?.stop()
            }.onFailure {
                Log.w(TAG, "RC_DIRECT_ARM_A2_stop_failed: ${it.message}")
            }
        }
        // RC-DIRECT-STABILITY1 §10 T2 teardown. Cancel the in-flight slow
        // POST job if any. T2 is a one-shot diagnostic that normally
        // completes on its own after ~70-80 s; this teardown handles the
        // case where the foreground Service is killed mid-POST (e.g. app
        // force-stop or OS-driven cleanup). Same debug + flag gate as the
        // start site. Release builds never reach this branch — the release
        // BuildConfig pins DEBUG_T2_SLOW_POST_URL to "".
        if (phantom.android.BuildConfig.DEBUG &&
            phantom.android.BuildConfig.DEBUG_T2_SLOW_POST_URL.isNotEmpty()
        ) {
            runCatching {
                (application as PhantomApplication).container.t2SlowPostDiag?.stop()
            }.onFailure {
                Log.w(TAG, "T2_SLOW_POST_stop_failed: ${it.message}")
            }
        }
        // PR-RC-DIRECT-WS-DEATH1 Phase 1 Arm B teardown. Stop the
        // diagnostic raw OkHttp socket before the rest of cleanup so a
        // service restart cannot race two diagnostic sockets sharing
        // `state.clients[identity]` at the relay. Same debug + flag gate
        // as the start site in onStartCommand. Release builds never reach
        // this branch — the release BuildConfig pins the flag to "0".
        if (phantom.android.BuildConfig.DEBUG &&
            phantom.android.BuildConfig.DEBUG_RC_DIRECT_ARM == "B"
        ) {
            runCatching {
                (application as PhantomApplication).container.rcDirectArmB?.stop()
            }.onFailure {
                Log.w(TAG, "RC_DIRECT_ARM_B_stop_failed: ${it.message}")
            }
        }
        // RC-DIRECT-STABILITY1 Arm C teardown. Stop the ping-interval matrix
        // diagnostic socket before the rest of cleanup so a service restart
        // cannot race two diagnostic sockets sharing `state.clients[identity]`
        // at the relay (Inv-ParallelArmIsolation carried forward from Phase 1).
        // Same debug + flag gate as the start site in onStartCommand. Release
        // builds never reach this branch — the release BuildConfig pins
        // DEBUG_RC_DIRECT_PING_INTERVAL_MS to "0".
        if (phantom.android.BuildConfig.DEBUG &&
            phantom.android.BuildConfig.DEBUG_RC_DIRECT_PING_INTERVAL_MS != "0"
        ) {
            runCatching {
                (application as PhantomApplication).container.rcDirectArmC?.stop()
            }.onFailure {
                Log.w(TAG, "RC_DIRECT_ARM_C_stop_failed: ${it.message}")
            }
        }
        // RC-DIRECT-STABILITY1 Arm D teardown. Stop the heartbeat echo
        // diagnostic socket + heartbeat sender loop before the rest of
        // cleanup so a service restart cannot race two diagnostic sockets
        // sharing `state.clients[identity]` at the relay
        // (Inv-ParallelArmIsolation carried forward from Phase 1). Same
        // debug + flag gate as the start site in onStartCommand. Release
        // builds never reach this branch — the release BuildConfig pins
        // DEBUG_RC_DIRECT_HEARTBEAT_ECHO to "0".
        if (phantom.android.BuildConfig.DEBUG &&
            phantom.android.BuildConfig.DEBUG_RC_DIRECT_HEARTBEAT_ECHO == "1"
        ) {
            runCatching {
                (application as PhantomApplication).container.rcDirectArmD?.stop()
            }.onFailure {
                Log.w(TAG, "RC_DIRECT_ARM_D_stop_failed: ${it.message}")
            }
        }
        // RC-DIRECT-STABILITY1 §14 Arm G teardown — TWO-STEP ordering per
        // hard gate 8 + implementation default #5: cancel Arm G's runJob +
        // WS FIRST, THEN stop the xrayService daemon. Reason: if libXray
        // were stopped first, Arm G's last reconnect attempt could hit
        // "connection refused" mid-shutdown and produce a noisy
        // RC_DIRECT_ARM_G_ws_failure log line that confounds the outcome
        // analysis. Stopping the diagnostic class first cleanly cancels
        // the runJob + cancels the in-flight WebSocket, and only then
        // does the daemon shut down.
        //
        // Same debug + flag gate as the start site in onStartCommand.
        // Release builds never reach this branch — the release BuildConfig
        // pins DEBUG_RC_DIRECT_ARM_G_VIA_REALITY to "".
        //
        // The xrayService.stop() call is intentionally OWNED HERE (in the
        // Service teardown), NOT inside RcDirectArmG.stop(). Reasons:
        // (i) defence-in-depth — even if a future RcDirectArmG.stop()
        // implementation were to drop the xrayService.stop() call, the
        // Service teardown still owns it; (ii) the Service is the
        // lifecycle owner of the foreground container, so xrayService
        // lifecycle aligns with Service lifecycle naturally; (iii) keeps
        // RcDirectArmG.stop() symmetric with RcDirectArmA2.stop() /
        // RcDirectArmD.stop() — the diagnostic class only cancels its
        // own runJob + WS.
        if (phantom.android.BuildConfig.DEBUG &&
            phantom.android.BuildConfig.DEBUG_RC_DIRECT_ARM_G_VIA_REALITY == "1"
        ) {
            runCatching {
                (application as PhantomApplication).container.rcDirectArmG?.stop()
            }.onFailure {
                Log.w(TAG, "RC_DIRECT_ARM_G_stop_failed: ${it.message}")
            }
            // Now stop the embedded libXray daemon. Idempotent per
            // XrayService contract — no-op if already Off. We log the
            // request boundary so a post-mortem can correlate the
            // teardown timing with any late RC_DIRECT_ARM_G_ws_failure
            // entries that may still be in flight from a final reconnect
            // attempt cancelled above. We log the done boundary too so
            // PR-G3 outcome capture can verify the daemon actually
            // reached Off rather than hanging in Stopping.
            //
            // §14 hard gate 3: redact libXray error strings (may embed
            // credentials / config paths). On failure log only generic
            // class marker — never `it.message`.
            Log.i("RC_DIRECT_ARM_G", "RC_DIRECT_ARM_G_xray_stop_requested")
            runCatching {
                kotlinx.coroutines.runBlocking {
                    (application as PhantomApplication).container.xrayService.stop()
                }
            }.onSuccess {
                Log.i("RC_DIRECT_ARM_G", "RC_DIRECT_ARM_G_xray_stop_done")
            }.onFailure { t ->
                Log.w(
                    TAG,
                    "RC_DIRECT_ARM_G_xray_stop_failed " +
                        "message_class=stop_call_threw t=${t::class.simpleName}",
                )
            }
        }
        // ADR-011: cancel the AlarmManager wakeup so we don't keep waking
        // the device after the user has explicitly stopped the service.
        runCatching { PhantomWakeupReceiver.cancel(applicationContext) }
        // PR-LTE-NETCHANGE1 (2026-05-28): unregister the NetworkChangeObserver
        // so we don't leak the ConnectivityManager callback past the service
        // lifetime. Robust to "observer not registered" via runCatching.
        runCatching {
            (application as PhantomApplication).container.networkChangeObserver?.unregister()
        }.onFailure {
            Log.w(TAG, "NetworkChangeObserver unregister failed: ${it.message}")
        }
        releaseKeepAliveLocks()
        // ADR-020 Phase 2: a single release tears down both Tor and Xray
        // (whichever the chain walk happened to start). Bounded inline so
        // a stop-and-restart cannot race two daemons on the same DataDir.
        runCatching {
            runBlocking {
                withTimeout(SUBSYSTEM_STOP_TIMEOUT_MS) {
                    (application as PhantomApplication).container.transportManager.release()
                }
            }
        }.onFailure { Log.w(TAG, "TransportManager.release failed: ${it.message}") }
        serviceScope.launch {
            runCatching { (application as PhantomApplication).container.transport.disconnect() }
        }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PHANTOM Messaging",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps encrypted connection active"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("PHANTOM")
        .setContentText(statusText)
        // TODO(Beta): replace with a dedicated monochrome status-bar icon
        // (24dp, white-on-transparent). Android status-bar icons must be
        // pure-white silhouettes with alpha; @drawable/ic_phantom_mono is
        // 432dp and full-mark shaped, not suited to 24dp. Using system
        // placeholder until the small status-bar variant ships.
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .setSilent(true)
        .build()

    companion object {
        private const val TAG = "PhantomMessagingService"
        const val CHANNEL_ID = "phantom_messaging"
        const val NOTIFICATION_ID = 1001

        /**
         * DWS-UX.1 (2026-06-17): derive the outer transport label for
         * the REST fallback notification overlay. Reads
         * [phantom.core.transport.ManagerState.Connected.kind] when
         * the outer is connected; falls back to the generic `"relay"`
         * word on pre-connected or failed states so the overlay does
         * not lie. The test contract
         * [phantom.android.service.TransportNameForOverlayTest] pins
         * the contract; living on the companion makes the helper
         * straightforwardly testable on the JVM unit-test source set
         * without a foreground-service runtime.
         */
        internal fun transportNameForOverlay(
            managerState: phantom.core.transport.ManagerState,
        ): String = when (managerState) {
            is phantom.core.transport.ManagerState.Connected ->
                managerState.kind.toString()
            else -> "relay"
        }

        /**
         * PR-LTE-NETCHANGE1 (2026-05-28) — boolean intent extra set by
         * `TransportRewalkCoordinator.requestServiceRestart` when it
         * needs the service to re-enter `onStartCommand` with a fresh
         * connect generation. Service reads it and force-resets the
         * `connectStarted` CAS guard. If absent, the normal CAS path
         * runs unchanged.
         */
        const val EXTRA_REWALK_RESTART = "phantom.rewalk_restart"
        /** Optional string extra: `NetworkChangeReason.name` for log attribution. */
        const val EXTRA_REWALK_REASON = "phantom.rewalk_reason"
        // Default text when TransportManager is Idle (pre-connect) or has no
        // useful state to surface; the notification updater overwrites this
        // as soon as the manager transitions to Probing / Connected / AllFailed.
        private const val DEFAULT_STATUS_TEXT = "Encrypted connection active"

        // ADR-020 Phase 2: outer-subsystem start/stop timeouts moved into
        // [TransportManager] (PER_ATTEMPT_TIMEOUT_MS = 5 s). The service only
        // bounds the synchronous tear-down in onDestroy.
        private const val SUBSYSTEM_STOP_TIMEOUT_MS = 5_000L
    }
}
