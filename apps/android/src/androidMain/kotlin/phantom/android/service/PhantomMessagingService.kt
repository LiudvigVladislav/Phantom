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
    @Volatile private var connectStarted = false
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
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(DEFAULT_STATUS_TEXT))
        acquireKeepAliveLocks()
        // ADR-020 Phase 2: subscribe to TransportManager state for live
        // foreground-notification text. This replaces the old per-subsystem
        // updater (one branch per Tor / Xray / direct) — the manager surfaces
        // a single ManagerState that already abstracts the chain walk.
        startTransportNotificationUpdater()
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
                        "Connecting via ${state.kind}… · $mode"
                    is ManagerState.Connected ->
                        "Online via ${state.kind} · $mode"
                    is ManagerState.AllFailed ->
                        // Bug #3 fix: drop the misleading "Tap to retry" — the
                        // notification body has no PendingIntent for it, so
                        // tapping does nothing. Real retry path is the Privacy
                        // Mode selector in Settings (which calls
                        // setPrivacyMode → release → fresh chain walk).
                        "Cannot reach relay (tried ${state.attempts.size}) · $mode"
                }
                Log.i(
                    TAG,
                    "TransportManager state → ${state::class.simpleName} mode=$mode text=\"$text\"",
                )
                pushNotificationText(text)
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
        serviceScope.launch {
            val app = application as PhantomApplication
            // Wait for libsodium + AppContainer to be fully initialised before using them.
            runCatching { app.ready.await() }.onFailure { t ->
                Log.e(TAG, "App init failed — service cannot start: ${t.message}")
                stopSelf()
                return@launch
            }

            val container = app.container

            // Ensure messaging is wired (no-op if already done by Activity on warm start).
            runCatching { container.initMessagingFromStorage() }
                .onFailure { e -> Log.e(TAG, "initMessagingFromStorage failed: ${e.message}") }

            val service = container.messagingService
            if (service == null) {
                Log.w(TAG, "messagingService is null after init — no identity yet, stopping")
                stopSelf()
                return@launch
            }

            // startReceiving is idempotent; safe to call even if Activity already called it.
            runCatching { service.startReceiving() }
                .onSuccess { Log.d(TAG, "startReceiving OK") }
                .onFailure { e -> Log.e(TAG, "startReceiving failed: ${e.message}") }

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
            // Guard against a second onStartCommand (e.g. AlarmManager wakeup)
            // arriving while the first connect loop is still establishing.
            if (connectStarted) {
                Log.d(TAG, "connect already in progress — duplicate onStartCommand ignored")
                return@launch
            }
            connectStarted = true

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
                connectStarted = false
                return@launch
            }
            val signingPubKeyHex = signingPair.publicKey.bytes
                .joinToString("") { ((it.toInt() and 0xFF) or 0x100).toString(16).substring(1) }

            val connected: ConnectedTransport = try {
                container.transportManager.connect()
            } catch (e: NoTransportReachableException) {
                Log.e(TAG, "TransportManager: no path reachable — ${e.message}", e)
                connectStarted = false
                return@launch
            } catch (t: Throwable) {
                Log.e(TAG, "TransportManager.connect threw: ${t::class.simpleName}: ${t.message}", t)
                connectStarted = false
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
            connectStarted = false
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — disconnecting transport")
        super.onDestroy()
        // ADR-011: cancel the AlarmManager wakeup so we don't keep waking
        // the device after the user has explicitly stopped the service.
        runCatching { PhantomWakeupReceiver.cancel(applicationContext) }
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
        // ic_launcher_foreground is a vector layer, not a standalone icon — use system placeholder.
        // Replace with a dedicated monochrome status-bar icon (24dp, white-on-transparent) in Beta.
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .setSilent(true)
        .build()

    companion object {
        private const val TAG = "PhantomMessagingService"
        const val CHANNEL_ID = "phantom_messaging"
        const val NOTIFICATION_ID = 1001
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
