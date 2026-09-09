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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.random.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import phantom.android.BuildConfig
import phantom.android.PhantomApplication
import phantom.android.di.AppContainer
import phantom.android.security.DeviceUnlockGate
import phantom.core.identity.IdentitySigningKeyPair
import phantom.core.transport.ConnectOwnership
import phantom.core.transport.ConnectRetryScheduler
import phantom.core.transport.ConnectedTransport
import phantom.core.transport.HandoffRecovery
import phantom.core.transport.Handover
import phantom.core.transport.ManagerState
import phantom.core.transport.NoTransportReachableException
import phantom.core.transport.SessionNotStoppedException
import phantom.core.transport.TorLifecycleUnsettled
import phantom.core.transport.label
import phantom.core.transport.TransportActivation
import phantom.core.transport.TransportSession
import phantom.core.transport.TransportKind
import phantom.core.transport.TransportPolicyChangedException

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
    private val startupInProgress = AtomicBoolean(false)
    @Volatile private var readyContainer: AppContainer? = null

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

    // PR-F2 (2026-05-12), why single-flight exists at all: Test #26
    // relay logs captured 5 simultaneous `event="connect"` events for
    // the same identity within 30 ms — five parallel coroutines all
    // raced past a `@Volatile var connectStarted = false` guard
    // (volatile gives visibility but NOT atomic check-then-set), each
    // opened its own WebSocket, and the relay's `state.clients[identity]`
    // map ended up with whichever registered last. The other four became
    // server-side zombies that never got pongs (relay routes pong by
    // identity → only latest gen) and triggered self-perpetuating
    // forceReconnect cascades on the client.
    //
    // That guard was an AtomicBoolean CAS, then a CAS plus a separate
    // generation token, and is now [ConnectOwnership]. R-N1.16 P3: this
    // comment still described the AtomicBoolean as the live mechanism
    // long after it had been replaced.
    /**
     * N1-F3 review P1 - ownership of the chain-walk slot as ONE atomic.
     *
     * This used to be an AtomicBoolean plus a separate generation token.
     * Releasing read the generation, compared it, and then wrote the
     * boolean - a check-then-act with a window in between. A rewalk
     * could force-clear the boolean and a new generation could claim it
     * inside that window, after which the departing generation's
     * already-decided write freed a slot it no longer owned, and a third
     * start could run a second TransportManager.connect() beside the
     * live one.
     *
     * [ConnectOwnership] makes claim and release the same atomic step
     * against the same state, so a stale release is refused rather than
     * applied. The interleaving is unit-tested directly in
     * ConnectOwnershipTest, deterministically, without racing threads.
     */
    // Both the lease and its token source live in the companion - see
    // the note there. Referenced unqualified throughout this class.


    /**
     * N1-F3 - the retry cadence this service was already being credited
     * with and did not have.
     *
     * `PhantomWakeupReceiver` used to skip its keepalive nudge whenever
     * the manager sat in [ManagerState.AllFailed], on the stated grounds
     * that "the foreground service owns the retry cadence". The service
     * meanwhile caught [NoTransportReachableException], logged it,
     * released its CAS and returned. Each side deferred to the other, so
     * a chain exhausted while the network stayed up - a relay outage, a
     * DPI block, a slow bridge - left the app dark until something
     * restarted the service. That is the observed `direct_unavailable`.
     *
     * The policy itself lives in [ConnectRetryScheduler], which is pure
     * and unit-tested with an injected clock. This service owns only the
     * timer and the single-flight interaction with the existing CAS and
     * generation guards.
     */
    private val retryScheduler = ConnectRetryScheduler(
        nowMs = { SystemClock.elapsedRealtime() },
        jitterFactor = {
            ConnectRetryScheduler.JITTER_MIN_FACTOR +
                Random.nextDouble() *
                (ConnectRetryScheduler.JITTER_MAX_FACTOR - ConnectRetryScheduler.JITTER_MIN_FACTOR)
        },
        log = { line -> Log.i("PhantomHybrid", line) },
    )

    /**
     * The armed retry timer, if any. Replaced on every arm and cancelled
     * by every invalidation, so at most one is ever outstanding. The
     * scheduler's epoch is the authority - this job is only the thing
     * that sleeps.
     */
    @Volatile
    private var retryJob: Job? = null

    // Recovery lives in the companion beside the lease - see the note
    // there. Referenced unqualified throughout this class.
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
        // Recovery lives in the companion and can outlive this
        // instance, so it needs a context that does too.
        appContext = applicationContext
        super.onCreate()
        Log.d(TAG, "onCreate")
        // R-N1.17 P1: an instance is up, so successors are wanted again.
        // A shutdown suppresses them and may hand an obligation forward -
        // this is where that obligation gets its driver back.
        runBlocking { handoffRecovery.resume("service_created") }
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
            val container = awaitContainerForService() ?: return@launch
            val mgr = container.transportManager
            val prefs = container.transportPreferences
            mgr.state.collect { state ->
                // Bug #2 fix: include PrivacyMode in the visible text so a
                // mode switch that lands on the SAME working transport (e.g.
                // Standard → Private both end up on REALITY) is still visible
                // to the user.
                // R-N1.17 P2: the EFFECTIVE mode. This string is shown to
                // the user, and showing the requested one would announce
                // Ghost as active while a Direct socket from the previous
                // posture was still up - the silent downgrade in the one
                // place the user actually looks.
                val mode = container.privacyModeCoordinator.state.value.effective.name
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
            // Readiness admission from the delivery line: never touch an
            // unready container, and fail closed instead of throwing out of
            // the service scope.
            val container = awaitContainerForService() ?: return@launch
            val hybrid = container.hybridTransport ?: return@launch
            val transportManager = container.transportManager
            // Idle-only Limited presentation from the delivery line; the label
            // still reports the EFFECTIVE privacy mode (R-N1.17 P2), not the
            // requested preference, so the overlay cannot claim a mode that
            // the coordinator has not actually granted.
            container.connectionUiState.collect { presentation ->
                val modeLabel =
                    container.privacyModeCoordinator.state.value.effective.name
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
                val text = when (presentation) {
                    phantom.android.transport.ConnectionUiState.LimitedRealtime ->
                        "Online via $transportName · Limited realtime · $modeLabel"
                    phantom.android.transport.ConnectionUiState.Recovering ->
                        "Online via $transportName · Recovering · $modeLabel"
                    else ->
                        null // Let the TransportManager state collector reassert
                }
                if (text != null) {
                    Log.i(TAG, "REST_TRACE notification_overlay mode=${hybrid.stateMachine.current} text=\"$text\"")
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
        // the chain-walk lease so the next connect attempt re-runs the
        // chain walk against the new network. Without this reset, the
        // CAS guard at line ~335 would treat the rewalk as a duplicate
        // and short-circuit.
        //
        // The coordinator already executed: state-machine notify, hint
        // clear, hybrid.disconnect(), transportManager.release(). All we
        // do here is unblock the re-entry path.
        val rewalkReason: String? =
            if (intent?.getBooleanExtra(EXTRA_REWALK_RESTART, false) == true) {
                intent.getStringExtra(EXTRA_REWALK_REASON) ?: "unknown"
            } else {
                null
            }
        if (rewalkReason != null) {
            Log.i(
                "PhantomHybrid",
                "NETWORK_TRACE service_restart_received reason=$rewalkReason — " +
                    "handing the lease over",
            )
            // Step 1 of the handover, and it stays here on the main
            // thread because it does not wait for anything: the rewalk
            // owns recovery from now on, so a pending retry must not
            // survive to drive a second walk, and a timer already past
            // its delay must not claim during the handover.
            cancelPendingRetry("rewalk_restart_$rewalkReason")
        }

        // N1-F3 review P1-1: a retry attempt has its OWN intent and does
        // NOT reset the CAS.
        //
        // It used to reuse EXTRA_REWALK_RESTART, whose handler clears
        // the lease unconditionally. That is safe for a real
        // network rewalk, because TransportRewalkCoordinator has already
        // disconnected and released before asking for the restart. A
        // retry does none of that. If an ordinary onStartCommand was
        // mid-walk when the timer fired, force-opening the CAS started a
        // SECOND TransportManager.connect() beside the first, and
        // TransportManager has no internal serialization.
        //
        // So the retry now falls through to the normal lease. If a walk
        // is already running the lease refuses the retry and the attempt
        // is DROPPED, not re-armed: the running walk arms on its own
        // AllFailed exit, and the alarm heartbeat is the backstop. See
        // the refusal branch below, which spells out why inventing state
        // here would be wrong.
        //
        // R-N1.16 P3: this comment used to claim the opposite ("re-armed
        // rather than dropped") and contradicted the code it introduces.
        val isRetryAttempt = intent?.getBooleanExtra(EXTRA_RETRY_ATTEMPT, false) == true
        if (isRetryAttempt) {
            Log.i(
                "PhantomHybrid",
                "RETRY_TRACE attempt_received source=" +
                    "${intent?.getStringExtra(EXTRA_RETRY_NUDGE_SOURCE) ?: "unknown"}",
            )
        }

        if (intent?.getBooleanExtra(EXTRA_RETRY_NUDGE, false) == true) {
            val source = intent.getStringExtra(EXTRA_RETRY_NUDGE_SOURCE) ?: "unknown"
            Log.i("PhantomHybrid", "RETRY_TRACE nudge_received source=$source")
            onExternalRetryNudge(source)
            return START_STICKY
        }

        serviceScope.launch {
            // Startup admission, identity and receive wiring all live in
            // prepareStart(): it refuses duplicate concurrent starts, waits
            // for readiness and reads identity only through the unlock gate.
            val prepared = prepareStart() ?: return@launch
            val container = prepared.container
            val myPubKey = prepared.publicKeyHex
            val signingPair = prepared.signingPair
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
            // R-N1.16 review item 3: fail-closed must be recoverable.
            // If an earlier handover could not confirm the previous walk
            // had stopped, the lease is still held and every claim is
            // refused. Retry the handover here, on whatever signal got
            // us this far -- a start, a retry attempt or the alarm
            // heartbeat. If that walk has since finished, the join
            // returns at once and the lease is released; if it has not,
            // the claim below is refused exactly as before.
            //
            // Without this the only thing that could lift the block was
            // another network rewalk, so on a stable network the app
            // would have stayed dark permanently.
            handoffRecovery.onSignal(
                if (isRetryAttempt) "retry_attempt" else "onStartCommand",
            )

            // R-N1.16 P1-1. Steps 2-4 of the handover: cancel the walk
            // that currently owns the lease, WAIT for it to finish, and
            // only then take the slot. This runs here rather than in
            // onStartCommand because it joins, and joining on the main
            // thread is an ANR.
            //
            // Revoking the token alone used to be the whole handover,
            // which left the displaced coroutine running inside
            // TransportManager.connect() beside its successor.
            if (rewalkReason != null) {
                when (val outcome = connectOwnership.handOver("rewalk_$rewalkReason")) {
                    is Handover.TimedOut -> {
                        // Fail-closed. We could not prove the previous
                        // walk stopped, so we do not start another one:
                        // being briefly disconnected is better than two
                        // transports competing over the same subsystems.
                        // The lease is deliberately still held, and
                        // recovery waits for the next allowed signal,
                        // which retries the handover.
                        Log.w(
                            "PhantomHybrid",
                            "NETWORK_TRACE handoff_timeout reason=$rewalkReason " +
                                "previousOwner=${outcome.previousOwner} " +
                                "timeoutMs=${outcome.timeoutMs} " +
                                "lease=kept successor=refused",
                        )
                        // Belt and braces. The coordinator arms recovery
                        // through handOverOrArm before it ever sends a
                        // restart, so this branch is normally
                        // unreachable: a failed handover abandons the
                        // rewalk instead of restarting the service.
                        // Arming again is idempotent - arm() replaces the
                        // timer rather than adding one.
                        handoffRecovery.arm(rewalkReason)
                        return@launch
                    }
                    Handover.AlreadyInProgress -> {
                        Log.w(
                            "PhantomHybrid",
                            "NETWORK_TRACE handoff_skipped reason=$rewalkReason " +
                                "cause=already_in_progress",
                        )
                        return@launch
                    }
                    is Handover.Quiesced -> Log.i(
                        "PhantomHybrid",
                        "NETWORK_TRACE handoff_complete reason=$rewalkReason " +
                            "previousOwner=${outcome.previousOwner}",
                    )
                    Handover.NothingToStop -> Log.i(
                        "PhantomHybrid",
                        "NETWORK_TRACE handoff_noop reason=$rewalkReason",
                    )
                }
            }

            // Guard against a second onStartCommand (e.g. AlarmManager wakeup,
            // foreground bring-back, ConnectivityChange broadcast) arriving while
            // the first connect path is still establishing. The lease is a
            // single atomic: the first caller wins and every other one bails.
            // R-N1.16 P1-1: the lease and the walk it owns are taken in
            // ONE step. The Job is read from inside this coroutine rather
            // than captured at the launch site, because a
            // `lateinit var job = scope.launch { ... }` races its own
            // body and the body needs the handle first.
            val myWalk: Job? = currentCoroutineContext()[Job]
            val claimed = connectOwnership.claim(
                if (isRetryAttempt) "retry_attempt" else "onStartCommand",
            ) { timeoutMs ->
                myWalk?.cancel(CancellationException("ownership_handover"))
                myWalk == null || withTimeoutOrNull(timeoutMs) { myWalk.join() } != null
            }
            if (claimed == null) {
                Log.d(TAG, "connect already in progress — duplicate onStartCommand ignored")
                if (isRetryAttempt) {
                    // A walk is already running, so this attempt is
                    // dropped. That is safe without re-arming: the
                    // running walk arms on its own AllFailed exit, and
                    // the alarm heartbeat is the backstop.
                    //
                    // Nothing is put back, because this launch never
                    // held a grant — there is no token to restore and
                    // inventing one would let a launch that lost the
                    // race schedule work.
                    //
                    // R-N1.16 P3: two sentences here used to say the
                    // claim was "put back", three lines above the
                    // sentence saying there was nothing to put back.
                    Log.i(
                        "PhantomHybrid",
                        "RETRY_TRACE attempt_deferred reason=connect_in_progress",
                    )
                }
                return@launch
            }

            // The token is minted only on a successful claim, so a
            // refused launch never consumes one. Every cleanup site
            // below goes through [releaseConnectOwnership], which
            // compares and clears in one atomic step, so a displaced
            // generation cannot free a slot that has changed hands.
            val myGen = claimed
            Log.i(
                "PhantomHybrid",
                "NETWORK_TRACE generation_claimed gen=$myGen",
            )


            // F11 + F26: signed-challenge auth requires our Ed25519 signing
            // keypair. Resolve BEFORE asking TransportManager to start an
            // outer subsystem — no point bootstrapping Tor / Xray if we
            // cannot present a valid signed challenge once the WSS opens.
            // The signing keypair was already resolved in prepareStart(),
            // read through the unlock gate and refused there when absent, so
            // it is available before any ownership is claimed. Reading it a
            // second time here would bypass that gate on a locked device.
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
            } catch (e: TransportPolicyChangedException) {
                // R-N1.16 P1: this walk started under a policy that is no
                // longer in force - a privacy switch cancelled it and it
                // wedged somewhere non-cancellable, then resumed. It must
                // not open a socket.
                //
                // No retry is armed here on purpose. The live policy
                // implies a different chain, and whoever changed the
                // policy owns starting the next connect: a successful
                // switch starts one itself, and a switch whose handover
                // timed out has recovery armed, which starts exactly one
                // as soon as this walk is finally joinable.
                Log.w(
                    "PhantomHybrid",
                    "NETWORK_TRACE walk_discarded reason=privacy_mode_changed " +
                        "attempted=${e.attempted} startedUnder=${e.startedUnder} " +
                        "liveNow=${e.liveNow} gen=$myGen",
                )
                releaseConnectOwnership(myGen, "privacy_mode_changed")
                return@launch
            } catch (e: NoTransportReachableException) {
                Log.e(TAG, "TransportManager: no path reachable — ${e.message}", e)
                val stillOwner = releaseConnectOwnership(myGen, "transportManager_no_path")
                // N1-F3: this is the AllFailed exit that used to end the
                // story. The chain is exhausted; nobody else will retry.
                //
                // Only the CURRENT generation may arm. A superseded one
                // has lost ownership, and its arm would carry a newer
                // epoch than the live generation's -- so epoch checking
                // alone would let it through and put a timer on top of
                // live work.
                armConnectRetry(myGen, "all_failed", stillOwner)
                return@launch
            } catch (unsettled: TorLifecycleUnsettled) {
                Log.e(
                    TAG,
                    "NETWORK_TRACE connect_tor_lifecycle_unsettled " +
                        "gen=$myGen torGen=${unsettled.attempt.generation} " +
                        "result=${unsettled.result.label}",
                    unsettled,
                )
                releaseConnectOwnership(myGen, "tor_lifecycle_unsettled")
                // Deliberately NOT armed. Every other exit here ends with a
                // transport that is merely not connected, and trying again later
                // is the right answer. This one ends with a daemon that was never
                // confirmed gone, or a host still holding its threads. A timer
                // would walk straight back into a start the owner refuses by
                // construction, and each pass would leave one more unreleased
                // host behind. Recovery has to come from the lifecycle settling,
                // not from the ladder.
                return@launch
            } catch (t: Throwable) {
                Log.e(TAG, "TransportManager.connect threw: ${t::class.simpleName}: ${t.message}", t)
                val stillOwnerAfterThrow =
                    releaseConnectOwnership(myGen, "transportManager_connect_threw")
                // N1-F3: an unexpected throw leaves the manager off
                // Connected just as surely as chain exhaustion does, so it
                // gets the same cadence rather than a silent dead end.
                armConnectRetry(myGen, "connect_threw", stillOwnerAfterThrow)
                return@launch
            }
            // N1-F3: the outer chain walk succeeded, so the backoff
            // ladder starts again from the bottom next time. This says
            // nothing about whether the WSS session below is healthy or
            // messages are flowing -- a wedged session after a good outer
            // connect is F-7, a separate open finding.
            retryScheduler.onOuterConnectSucceeded()
            retryJob?.cancel()
            retryJob = null
            // R-N1.16 P1: the second half of the gate. connect()
            // returning proves the policy was current at the instant of
            // publication and nothing more - a privacy switch can land
            // while this coroutine is on its way here, and opening a
            // Direct socket then is the silent downgrade with extra
            // steps.
            // R-N1.16 P1: a PERMIT, not a check. `isStillCurrent()`
            // answered a question and left a window; a permit registers
            // this socket with the thing that can invalidate it, so a
            // privacy switch either lands before the permit is issued or
            // finds it and revokes it - and does not complete until the
            // revocation has torn the socket down.
            // R-N1.16 P1: the whole permission-and-open step goes through
            // TransportActivation, which is Android-free and therefore
            // driven by the same fixtures that prove the boundary. What
            // used to live here as a few lines could only ever be
            // asserted from source text.
            //
            // The permit deliberately stays live past this point: it is
            // released when the socket closes, not when it opens, so a
            // privacy switch arriving later still finds an open Direct
            // socket and tears it down.
            val socksProxyPort: Int? = connected.socksPort
            val relayUrl =
                if (connected.kind == TransportKind.Tor) BuildConfig.RELAY_ONION_URL
                else BuildConfig.RELAY_URL

            // R-N1.17 P1: the session Job IS the socket, and it must be
            // started under the permit.
            //
            // An earlier revision passed an empty `openSocket` and called
            // `transport.connect()` forty lines further down, outside the
            // permit entirely - so the permit protected nothing in
            // production while the fixtures, whose `openSocket` really
            // opened a fake socket, stayed green. The mechanism was
            // decorative exactly where it mattered.
            //
            // It cannot simply move inside `useToOpen`: that holds the
            // permit's lock until the callback returns, and connect()
            // does not return until disconnect(). So the callback STARTS
            // the session and returns; the waiting happens outside the
            // lock, and revocation cancels and joins it.
            // R-N1.17 P1: the session is a CHILD of this walk. A sibling
            // outlives an ownership handover that believed it had stopped
            // everything, and that is what the previous revision created.
            val session = TransportSession(
                ownerScope = CoroutineScope(currentCoroutineContext()),
                connectLoop = {
                    container.transport.connect(
                        relayUrl = relayUrl,
                        identityPublicKeyHex = myPubKey,
                        signingPublicKeyHex = signingPubKeyHex,
                        signChallenge = { nonce ->
                            container.identityManager.signRelayChallenge(nonce)
                        },
                        socksProxyPort = socksProxyPort,
                    )
                },
                closeTransport = {
                    // R-N1.17 P1: `disconnect()` is the wrong close
                    // here, for two reasons - and not the one an earlier
                    // revision of this comment gave.
                    //
                    // It DOES cancel the reconnect loop: it routes to
                    // `teardownAndJoin(flushBeforeClose = true)`. What it
                    // does wrong is flush, and then throw the join result
                    // away.
                    //
                    // The flush spends up to three seconds pushing
                    // pendingOutbox and pendingAcks through the very
                    // socket the switch is closing, so a switch away from
                    // Standard kept sending the user's queued payloads
                    // over Direct AFTER they asked Direct to stop.
                    // PrivacyModeTeardown states that prohibition for its
                    // own path; this path had it too and did not obey it.
                    //
                    // And discarding the join result means an unconfirmed
                    // teardown is indistinguishable from a clean one.
                    //
                    // But `disconnectAndJoin` is not enough either, and
                    // saying it was is what an earlier revision got wrong.
                    // It confirms that the RECONNECT LOOP ended; the
                    // session and HTTP-client closes are handed to a
                    // cleanup scope and may finish afterwards - or be
                    // refused outright when that scope's budget is
                    // exhausted. A permit released on that answer can
                    // leave a live WSS in no register at all.
                    //
                    // `disconnectAndConfirm` reports the loop and the
                    // closes as the separate facts they are. Anything less
                    // than `confirmed` is a FAILED close, which is what
                    // keeps the permit registered.
                    val teardown = container.transport.disconnectAndConfirm(
                        phantom.android.di.PRIVACY_SWITCH_DISCONNECT_TIMEOUT_MS,
                    )
                    if (!teardown.confirmed) {
                        throw SessionNotStoppedException(
                            "loopJoined=${teardown.loopJoined} " +
                                "closesConfirmed=${teardown.closesConfirmed}",
                        )
                    }
                },
                log = { line -> Log.i("PhantomHybrid", line) },
            )
            val activation = TransportActivation(
                // The authority comes FROM the manager, so the permits
                // this registers cannot end up in a different register
                // from the one a privacy switch revokes.
                manager = container.transportManager,
                openSocket = { session.start() },
                closeSocket = {
                    // cancel -> close -> confirmed join, inside the
                    // session. A stop that cannot be confirmed throws, so
                    // the permit records a FAILED close rather than a
                    // completed one.
                    if (!session.stop("privacy_mode_changed")) {
                        throw SessionNotStoppedException("privacy_mode_changed")
                    }
                },
                log = { line -> Log.i("PhantomHybrid", line) },
            )
            val usePermit = when (val outcome = activation.activate(connected)) {
                is TransportActivation.Outcome.Opened -> outcome.permit
                TransportActivation.Outcome.RefusedStale,
                TransportActivation.Outcome.RevokedBeforeOpen -> {
                    Log.w(
                        "PhantomHybrid",
                        "NETWORK_TRACE connected_transport_discarded " +
                            "reason=privacy_mode_changed kind=${connected.kind} " +
                            "outcome=$outcome gen=$myGen",
                    )
                    releaseConnectOwnership(myGen, "connected_transport_stale")
                    return@launch
                }
            }
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

            // Wait for the session OUTSIDE the permit lock. The release
            // runs non-cancellably AFTER it has finished: an ordinary
            // `finally` runs while this coroutine is being cancelled and
            // can be skipped at a suspension point, leaving a permit
            // registered against a socket that has closed.
            runCatching { session.awaitCompletion() }
            session.afterCompletion {
                // R-N1.17 P1: the socket is closed THROUGH the permit,
                // and only a close that succeeded releases it.
                //
                // Cancelling this walk ends the wait on the transport, not
                // necessarily the transport. Releasing here used to claim
                // the socket was closed - a claim this side cannot check -
                // so a possibly-live socket left every register. Now a
                // refusal is the interesting case: the permit stays
                // registered and something has to retry the close.
                if (!usePermit.closeAndRelease("transport_loop_exited")) {
                    Log.w(
                        TAG,
                        "NETWORK_TRACE permit_release_refused reason=socket_not_closed " +
                            "gen=$myGen — arming a sweep",
                    )
                    handoffRecovery.arm("socket_not_confirmed_closed")
                }
                releaseConnectOwnership(myGen, "transport_loop_exited")
            }
        }
        return START_STICKY
    }

    private suspend fun awaitContainerForService(): AppContainer? = serviceStartupOrNull(
        onFailure = {
            Log.e("PhantomMessaging", "RECV_DIAG service_app_init_failed err=${it::class.simpleName}")
            stopSelf()
        },
    ) {
        val app = application as PhantomApplication
        awaitReadyValue(app.ready) { app.container }.also { readyContainer = it }
    }

    private data class PreparedStart(
        val container: AppContainer,
        val publicKeyHex: String,
        val signingPair: IdentitySigningKeyPair,
    )

    private suspend fun prepareStart(): PreparedStart? {
        // Duplicate alarms must not accumulate unlock waiters or initialize in parallel.
        if (!startupInProgress.compareAndSet(false, true)) return null
        try {
            return serviceStartupOrNull(onFailure = {
                Log.e("PhantomMessaging", "RECV_DIAG service_start_failed err=${it::class.simpleName}")
                stopSelf()
            }) {
                val container = awaitContainerForService() ?: return@serviceStartupOrNull null
                Log.i("PhantomMessaging", "RECV_DIAG service_app_ready")
                // R-N1.17 P2: the legacy-only privacy mode is copied to the
                // canonical key here - off Main, before anything connects.
                // The authority already holds the right mode in memory; this
                // only makes it durable.
                container.migratePrivacyModeIfNeeded()
                val unlock = DeviceUnlockGate(applicationContext)
                val publicKeyHex = unlock.readWhenUnlocked {
                    container.identityRepo.loadIdentity()?.publicKeyHex
                }
                val signingPair = unlock.readWhenUnlocked {
                    container.identityManager.loadSigningKeyPair()
                }
                if (publicKeyHex == null || signingPair == null) {
                    Log.w("PhantomMessaging", "RECV_DIAG service_identity_missing")
                    stopSelf()
                    return@serviceStartupOrNull null
                }
                // These operations are not retried as reads: partial setup may own work.
                container.initMessagingFromStorage()
                container.networkChangeObserver?.register()
                val messaging = checkNotNull(container.messagingService)
                messaging.startReceiving()
                Log.i("PhantomMessaging", "RECV_DIAG startReceiving_ok")
                PreparedStart(container, publicKeyHex, signingPair)
            }
        } finally {
            startupInProgress.set(false)
        }
    }

    /**
     * PR-LTE-NETCHANGE1 P1 fix (architect 2026-05-28): cleanup-time CAS
     * reset gated by generation token. See `connectGeneration` field
     * kdoc for race scenario. Logs the skip case so a stale-cleanup
     * incident is visible in the future logcat without code reading.
     */
    /**
     * N1-F3 - the single service-owned entry point for every retry
     * signal. Nothing else may call `TransportManager.connect()`.
     *
     * Three sources converge here: the armed timer this function starts,
     * the alarm heartbeat's nudge, and a duplicate nudge. They are
     * de-duplicated by [ConnectRetryScheduler.claim], which grants at
     * most one of them; the granted claimant then goes through the
     * existing CAS and generation guards, so a retry can never overlap a
     * chain walk that is still running.
     *
     * The delay starts when the failed attempt RETURNED, not when it
     * began. A chain walk has no proven upper bound - Tor prepare walks
     * BRIDGE_ROTATION_ORDER, 600 + 420 + 180 + 60 = 1260 s, before its
     * 90 s probe, and `tor.start()` sits outside the timeout that bounds
     * the state wait - so "make the backoff longer than a walk" is not a
     * rule that can be honoured. Overlap is prevented structurally
     * instead.
     */
    private fun armConnectRetry(myGen: Long, reason: String, stillOwner: Boolean) {
        if (!stillOwner) {
            Log.i(
                "PhantomHybrid",
                "RETRY_TRACE arm_skipped reason=stale_generation gen=$myGen site=$reason",
            )
            return
        }
        retryJob?.cancel()
        retryJob = serviceScope.launch {
            val arm = retryScheduler.armAfterFailure(myGen, connectGeneration.get())
            if (arm == null) {
                Log.i(
                    "PhantomHybrid",
                    "RETRY_TRACE arm_refused_by_scheduler gen=$myGen site=$reason",
                )
                return@launch
            }
            Log.i(
                "PhantomHybrid",
                "RETRY_TRACE scheduled reason=$reason gen=$myGen " +
                    "delayMs=${arm.delayMs} streak=${arm.streak} epoch=${arm.epoch}",
            )
            delay(arm.delayMs)
            when (val claim = retryScheduler.claim(arm.epoch, "timer", connectGeneration.get())) {
                is ConnectRetryScheduler.Claim.Granted ->
                    startSelfForRetry("timer_epoch_${arm.epoch}", claim.token)
                else ->
                    Log.i(
                        "PhantomHybrid",
                        "RETRY_TRACE timer_yielded epoch=${arm.epoch} outcome=$claim",
                    )
            }
        }
    }

    /**
     * Deliver a retry signal that did not come from our own timer - the
     * alarm heartbeat's nudge. It claims through the same scheduler, so
     * a nudge that arrives early is refused and a nudge that races the
     * timer loses to whichever gets the lock first.
     */
    private fun onExternalRetryNudge(source: String) {
        serviceScope.launch {
            // R-N1.16 P1-3. A nudge that arrives while the lease is
            // fail-closed must be able to lift it. This branch returns
            // before the ordinary claim path, so without this the
            // heartbeat could not reach recoverIfBlocked at all.
            //
            // This is not the primary recovery route - the receiver does
            // not nudge on Idle, which is where a released transport
            // leaves the manager - but it costs nothing and it means a
            // nudge that DOES arrive is not wasted.
            when (val recovery = handoffRecovery.onSignal("nudge_$source")) {
                HandoffRecovery.Signal.NotBlocked -> Unit // ordinary nudge below
                is HandoffRecovery.Signal.Recovered -> {
                    Log.i(
                        "PhantomHybrid",
                        "NETWORK_TRACE handoff_recovered_by_nudge source=$source " +
                            "previousOwner=${recovery.previousOwner}",
                    )
                    return@launch
                }
                HandoffRecovery.Signal.StillBlocked -> return@launch
            }
            when (val claim = retryScheduler.claim(null, source, connectGeneration.get())) {
                is ConnectRetryScheduler.Claim.Granted -> startSelfForRetry(source, claim.token)
                else ->
                    Log.i(
                        "PhantomHybrid",
                        "RETRY_TRACE nudge_yielded source=$source outcome=$claim",
                    )
            }
        }
    }

    /**
     * Re-enter through `onStartCommand` rather than calling `connect()`
     * inline, so the retry uses exactly the same path - CAS, generation,
     * privacy-mode re-read - as any other connect.
     *
     * It carries its OWN extra, [EXTRA_RETRY_ATTEMPT], and not the
     * rewalk one. The rewalk extra clears the CAS unconditionally, which
     * is safe only after the coordinator has disconnected and released;
     * a retry has not, so borrowing it could start a second concurrent
     * chain walk. Going through the CAS instead means a retry is refused
     * while a walk is running.
     *
     * Nothing is cached between attempts: `TransportManager.connect()`
     * takes a fresh policy snapshot from the authority on entry, so Ghost stays
     * Tor-only across a retry with no downgrade.
     *
     * [token] identifies the grant this start is acting on. If the start
     * throws, only that exact grant may be put back - a restore without
     * identity would resurrect a retry that had been invalidated in the
     * meantime.
     */
    private fun startSelfForRetry(source: String, token: ConnectRetryScheduler.ClaimToken) {
        Log.i("PhantomHybrid", "RETRY_TRACE attempt_start source=$source")
        runCatching {
            val intent = Intent(applicationContext, PhantomMessagingService::class.java)
                .putExtra(EXTRA_RETRY_ATTEMPT, true)
                .putExtra(EXTRA_RETRY_NUDGE_SOURCE, source)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        }.onFailure {
            Log.e(TAG, "RETRY_TRACE attempt_start_failed source=$source: ${it.message}", it)
            // The claim already cleared the pending slot. If the start
            // threw, nothing is scheduled and every later nudge would see
            // NotPending -- the original latch, reintroduced. Put it back.
            serviceScope.launch {
                retryScheduler.restoreAfterFailedStart(token, "start_failed_$source")
            }
        }
    }

    /**
     * Drop a pending retry because something else has taken over
     * recovery, or because we are shutting down. Cancels the timer AND
     * bumps the scheduler epoch, so a timer already past its delay
     * cannot still claim.
     */
    private fun cancelPendingRetry(reason: String) {
        retryJob?.cancel()
        retryJob = null
        // R-N1.17 P1: NOT cancelled here. This is the retry path, and the
        // recovery now also carries unclosed sockets and an owed start.
        // Cancelling it on every network rewalk stood those down too. The
        // timer ends itself once nothing is left.
        // Synchronous on purpose. Launching this would leave the rewalk
        // restart and an already-due timer unordered: the timer could win
        // the race, claim, and start a second walk beside the rewalk. The
        // epoch must be bumped BEFORE the restart proceeds.
        runBlocking { retryScheduler.invalidate(reason) }
    }

    /**
     * Release the chain-walk slot, if this generation still holds it.
     *
     * N1-F3 review P1: this used to read the generation counter and then
     * write the ownership flag as two steps, so a release decided before
     * a rewalk handed the slot to a newer generation still took effect
     * afterwards. [ConnectOwnership.release] compares and clears in one
     * step against the same state, and returns false when ownership has
     * moved on - in which case nothing is written at all.
     *
     * The return value is what gates arming a retry: only the generation
     * that still owns the walk may schedule the next one.
     */
    private suspend fun releaseConnectOwnership(myGen: Long, site: String): Boolean {
        val released = connectOwnership.release(myGen, site)
        Log.i(
            "PhantomHybrid",
            if (released) {
                "NETWORK_TRACE generation_cleanup gen=$myGen site=$site released=true"
            } else {
                "NETWORK_TRACE generation_stale skip_release myGen=$myGen site=$site"
            },
        )
        return released
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — disconnecting transport")
        super.onDestroy()
        // N1-F3: an explicit stop must not leave a timer behind that
        // starts the service again a few minutes later. Cancelling the
        // job alone would not be enough - a timer already past its delay
        // could still claim - so the scheduler epoch is bumped too.
        retryJob?.cancel()
        retryJob = null
        // R-N1.16: the handoff-recovery timer has exactly the hazard the
        // comment above describes, and it is the more dangerous of the
        // two because it starts the service unconditionally rather than
        // through the scheduler. serviceScope.cancel() at the end of this
        // method would eventually take it, but "eventually" leaves a
        // window across the whole teardown in which it can fire.
        // R-N1.17 P1: this ALWAYS stops the timer - the contract above
        // is not negotiable - but a settlement obligation is not thrown
        // away with it, because it owns the claim fence and the fence
        // lives in the process-wide ConnectOwnership. The obligation is
        // handed to this shutdown, which discharges it inside its own
        // teardown below rather than beside it.
        val owedAtShutdown = runBlocking { handoffRecovery.standDown("service_destroyed") }
        runBlocking { retryScheduler.invalidate("service_destroyed") }
        // A service that never reached a ready container has nothing
        // connected to tear down, and must not touch the container to find
        // that out. The timer and the scheduler above are already stopped,
        // and the obligation is checked rather than assumed: if anything is
        // still owed, the full teardown below runs and discharges it.
        if (readyContainer == null &&
            owedAtShutdown !is HandoffRecovery.StandDown.SettlementOwed
        ) {
            Log.i(TAG, "NETWORK_TRACE shutdown_unready_container nothing_owed")
            serviceScope.cancel()
            runCatching { PhantomWakeupReceiver.cancel(applicationContext) }
            releaseKeepAliveLocks()
            return
        }
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
        // R-N1.16 P1: the same cancel -> confirmed join -> release
        // contract the rewalk and the privacy switch use. A shutdown that
        // released under a live walk would tear the subsystems out from
        // under a chain walk still starting or probing them.
        //
        // The difference from those two paths: a shutdown must NOT arm
        // recovery. An explicit stop that schedules its own restart is
        // the hazard the retry timer already carries a comment about, and
        // recovery starts the service unconditionally rather than through
        // the scheduler. So the handover here is the plain one, and the
        // recovery timer is stood down either way.
        runCatching {
            runBlocking {
                if (owedAtShutdown is HandoffRecovery.StandDown.SettlementOwed) {
                    // R-N1.17 P1: ONE worker does this teardown.
                    //
                    // Leaving the obligation's timer alive to do it was a
                    // race, not a handover: this method is not inside the
                    // privacy transition lock, and TransportManager
                    // release is not serialised against connect - so the
                    // timer could run the whole teardown, lower the
                    // fence and start a successor while the block below
                    // was still releasing, and the old instance would
                    // then stop the new walk's subsystems.
                    //
                    // finishDeferredPrivacySwitch takes the transition
                    // lock and runs the FULL teardown - the same handover
                    // and release this branch would otherwise do - and
                    // its successor is suppressed while recovery is stood
                    // down, so the stop does not restart the service.
                    // R-N1.17 P1: the WAIT is bounded, not the work.
                    //
                    // Wrapping the settlement in withTimeoutOrNull here
                    // bounded nothing: the settlement and the teardown
                    // beneath it run inside NonCancellable by design, and
                    // a native stop already in progress cannot be aborted
                    // from Kotlin. So a wedged phase could block
                    // onDestroy past its budget and, in principle,
                    // indefinitely.
                    //
                    // The obligation runs in the companion's recovery
                    // scope - which outlives this instance, and is the
                    // reason that scope is not a per-instance one - and
                    // this method stops waiting after the budget. On
                    // expiry the obligation stands, the cleanup is
                    // re-armed, and no successor is started because
                    // recovery is stood down.
                    val settled = phantom.core.transport.settleWithinShutdownBudget(
                        claim = owedAtShutdown,
                        budgetMs = SUBSYSTEM_STOP_TIMEOUT_MS,
                        scope = recoveryScope,
                        settle = { epoch ->
                            (application as PhantomApplication).container
                                .finishDeferredPrivacySwitch(epoch)
                        },
                        noteOutcome = { claimToken, ok ->
                            handoffRecovery.noteShutdownSettlement(claimToken, ok)
                        },
                        noteOverBudget = { claimToken ->
                            handoffRecovery.noteShutdownOverBudget(claimToken)
                        },
                        log = { line -> Log.i(TAG, line) },
                    )
                    if (!settled) {
                        Log.w(
                            TAG,
                            "NETWORK_TRACE shutdown_settlement_incomplete " +
                                "epoch=${owedAtShutdown.epoch} — the obligation stands " +
                                "and keeps the lease shut until it can be finished",
                        )
                    }
                    return@runBlocking
                }
                val quiesced = withTimeout(SUBSYSTEM_STOP_TIMEOUT_MS) {
                    when (connectOwnership.handOver("service_destroyed")) {
                        is Handover.Quiesced, Handover.NothingToStop -> true
                        else -> false
                    }
                }
                if (quiesced) {
                    withTimeout(SUBSYSTEM_STOP_TIMEOUT_MS) {
                        val outcome = (application as PhantomApplication)
                            .container.transportManager.release()
                        if (!outcome.clean) {
                            Log.w(
                                TAG,
                                "NETWORK_TRACE shutdown_release_not_clean " +
                                    "xray=${outcome.xrayFailure?.let { it::class.simpleName }} " +
                                    "tor=${outcome.torIncomplete}",
                            )
                        }
                    }
                } else {
                    Log.w(
                        TAG,
                        "NETWORK_TRACE shutdown_release_skipped reason=handoff_timeout — " +
                            "not releasing under a walk we could not stop",
                    )
                }
            }
        }.onFailure { Log.w(TAG, "TransportManager.release failed: ${it.message}") }
        // R-N1.17 P1. Two things were wrong here, and the second was
        // introduced fixing the first.
        //
        // It used to `launch` the disconnect into `serviceScope` and
        // cancel that scope on the very next line, so the teardown was
        // cancelled before it could run. Replacing that with a
        // `runBlocking` wait fixed the cancellation and put a five-second
        // block on the MAIN THREAD, which is ANR territory.
        //
        // The wait belongs neither on the main thread nor in a scope that
        // is about to die. It goes to the recovery scope, which
        // deliberately outlives any one instance - and its RESULT is
        // owned: a join that cannot be confirmed arms the sweep, and the
        // permit is still registered because a release over an unconfirmed
        // socket is refused. Successors stay suppressed: `standDown` has
        // already run.
        // R-N1.17 P1: BOUND TO THE CONNECTION THIS INSTANCE OWNED.
        //
        // The cleanup runs later, in a scope that outlives this instance -
        // and by then a new instance may have come up and connected. An
        // unqualified `disconnectAndJoin` at that point captures whatever
        // reconnect job is current, which would be the SUCCESSOR's, and
        // cancels a connection this instance has never seen.
        //
        // The identity is read now, while it is still ours, and the
        // teardown is refused if it no longer matches.
        val ownedConnection = (application as PhantomApplication)
            .container.transport.teardownIdentity
        recoveryScope.launch {
            val teardown = runCatching {
                (application as PhantomApplication).container.transport
                    .disconnectAndConfirm(
                        timeoutMs = SUBSYSTEM_STOP_TIMEOUT_MS,
                        onlyIfIdentity = ownedConnection,
                    )
            }.onFailure {
                Log.w(TAG, "shutdown disconnect failed: ${it.message}")
            }.getOrNull()
            when {
                teardown == null -> handoffRecovery.arm("shutdown_socket_not_closed")
                teardown.supersededIdentity -> Log.i(
                    TAG,
                    "NETWORK_TRACE shutdown_disconnect_skipped reason=identity_superseded " +
                        "owned=$ownedConnection — a newer instance owns the transport",
                )
                !teardown.confirmed -> {
                    Log.w(
                        TAG,
                        "NETWORK_TRACE shutdown_disconnect_not_confirmed " +
                            "loopJoined=${teardown.loopJoined} " +
                            "closesConfirmed=${teardown.closesConfirmed} — arming a sweep; " +
                            "the permit stays registered until a close is confirmed",
                    )
                    handoffRecovery.arm("shutdown_socket_not_closed")
                }
            }
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
    /**
         * The token source for [ConnectOwnership]. Nothing else reads it.
         *
         * Historical note, because this field outlived two designs. It was
         * introduced (PR-LTE-NETCHANGE1, 2026-05-28) as the second half of a
         * two-atomic guard: an `AtomicBoolean` said whether a connect was in
         * flight, and cleanup reset that boolean only if
         * `connectGeneration.get() == myGen`. Reading one atomic and then
         * writing another is a check-then-act, and R-N1.16 found the race it
         * leaves: the slot can change hands between the two steps, so a
         * displaced generation's cleanup frees a slot its successor holds.
         *
         * Ownership is now a single value under a single lock in
         * [ConnectOwnership], and this counter only mints the values.
         *
         * R-N1.16 P3: the KDoc here used to describe the two-atomic scheme
         * as the current mechanism, including the `get() == myGen` cleanup
         * rule that had already been removed as unsafe.
         */
        internal val connectGeneration = AtomicLong(0L)

        /**
         * The chain-walk lease, scoped to the PROCESS rather than to a
         * service instance.
         *
         * R-N1.16 P1-2 made this necessary and P1-1 made it correct.
         * `TransportRewalkCoordinator` is built by `AppContainer` and has
         * to hand the lease over before it releases the transport, so it
         * needs to reach the same lease the service claims under.
         *
         * Instance scope was also wrong on its own terms: Android can
         * destroy and recreate the service, and a fresh instance would
         * have minted a fresh lease while a walk from the previous
         * instance was still running - the same "owned by nobody" state
         * this class exists to prevent. The walk handle is a Job
         * reference, so a handover works across instances.
         */
        internal val connectOwnership = ConnectOwnership(
            nextToken = { connectGeneration.incrementAndGet() },
            log = { line -> Log.i("PhantomHybrid", line) },
        )

        /**
         * Application context, set in [onCreate]. Recovery needs it to
         * start a service, and recovery outlives any one instance.
         */
        @Volatile
        private var appContext: Context? = null

        /**
         * The scope recovery runs in.
         *
         * Deliberately NOT a service instance's scope: the situation
         * recovery exists for is one where the rewalk was abandoned and
         * no service restart was issued, so there may be no live
         * instance to hang it on. `onDestroy` still stands the timer
         * down explicitly, which is the behaviour that was wanted.
         */
        private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * R-N1.16 P1-3: recovery from a fail-closed connect lease.
         *
         * In the companion because `TransportRewalkCoordinator` - built
         * by `AppContainer` - is the component that actually discovers a
         * failed handover, and it must be able to arm this.
         */
        internal val handoffRecovery = HandoffRecovery(
            ownership = connectOwnership,
            scope = recoveryScope,
            startOneOrdinaryConnect = { reason -> startForHandoffRecovery(reason) },
            log = { line -> Log.i("PhantomHybrid", line) },
            // R-N1.16 P1: the same timer drives the retry of sockets a
            // privacy switch could not close. Both mean "something from a
            // superseded policy is still live", and one timer with a
            // proven single-instance guarantee beats two.
            finishDeferredSwitch = { epoch ->
                val c = appContext?.let { (it as? PhantomApplication)?.container }
                c?.finishDeferredPrivacySwitch(epoch) ?: false
            },
            // R-N1.17 P2: a start debt belongs to the epoch that recorded
            // it. Without this the timer could not tell a debt the world
            // has moved past from one that still stands.
            // Null means "cannot tell", NOT epoch zero. Returning 0
            // here made every privacy-epoch debt look superseded exactly
            // when the container was not up - which is when a start had
            // most likely just failed, so the debt that existed to fix it
            // was the one being discharged.
            currentPolicyEpoch = {
                appContext?.let { (it as? PhantomApplication)?.container }
                    ?.privacyModeCoordinator?.currentEpoch()
            },
            sweepPending = {
                val authority = appContext?.let {
                    (it as? PhantomApplication)?.container?.privacyModeCoordinator
                }
                authority != null && !authority.retryPending().isComplete
            },
        )

        private fun startForHandoffRecovery(reason: String): Boolean {
            val ctx = appContext
            if (ctx == null) {
                Log.w("PhantomHybrid", "handoff_recovery_no_context reason=$reason")
                return false
            }
            return runCatching {
                val intent = Intent(ctx, PhantomMessagingService::class.java)
                    .putExtra(EXTRA_RETRY_ATTEMPT, true)
                    .putExtra(EXTRA_RETRY_NUDGE_SOURCE, "handoff_recovery_$reason")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }.onFailure {
                Log.e(TAG, "handoff_recovery_start_failed reason=$reason: ${it.message}", it)
            }.isSuccess
        }

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
         * chain-walk lease. If absent, the normal claim path
         * runs unchanged.
         */
        const val EXTRA_REWALK_RESTART = "phantom.rewalk_restart"
        /** Optional string extra: `NetworkChangeReason.name` for log attribution. */
        const val EXTRA_REWALK_REASON = "phantom.rewalk_reason"

        /**
         * N1-F3 - boolean extra marking a retry NUDGE from
         * [PhantomWakeupReceiver].
         *
         * A nudge is not a connect. The receiver may only say "a retry
         * may be due"; the service checks due time, epoch and
         * single-flight through [ConnectRetryScheduler] and decides. That
         * keeps the number of components allowed to start a chain walk at
         * one, which is what went wrong before: the receiver deferred to
         * a service cadence that did not exist.
         */
        const val EXTRA_RETRY_NUDGE = "phantom.retry_nudge"

        /**
         * N1-F3 review P1-1 - a retry ATTEMPT, distinct from the
         * rewalk restart it used to borrow.
         *
         * The rewalk extra clears the CAS unconditionally, which is
         * safe only because the coordinator has already quiesced the
         * transport. A retry has not, so it goes through the CAS like
         * any other start and is refused while a walk is running.
         */
        const val EXTRA_RETRY_ATTEMPT = "phantom.retry_attempt"


        /** Optional string extra: where the nudge came from, for log attribution. */
        const val EXTRA_RETRY_NUDGE_SOURCE = "phantom.retry_nudge_source"
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
