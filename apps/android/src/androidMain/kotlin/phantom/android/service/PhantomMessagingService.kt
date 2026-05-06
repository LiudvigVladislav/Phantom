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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import phantom.android.BuildConfig
import phantom.android.PhantomApplication
import phantom.core.transport.TorService
import phantom.core.transport.TorServiceConfig
import phantom.core.transport.TorState
import phantom.core.transport.createTorService

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

    // ADR-016 Stage 2C: embedded Tor service. Lazy-constructed only when
    // [BuildConfig.USE_TOR] is true so non-Tor builds carry zero kmp-tor
    // initialisation cost. Lifecycle is bound to onStartCommand → onDestroy
    // — start before connect, stop after disconnect. The work + cache
    // directories live under the app-private dataDir so guards persist
    // across restarts (warm bootstrap on next start) and the OS can evict
    // the cache without breaking functionality.
    private val torService: TorService? by lazy {
        if (!BuildConfig.USE_TOR) return@lazy null
        val workDir = applicationContext.filesDir.resolve("tor-data")
        val cacheDir = applicationContext.cacheDir.resolve("tor-cache")
        workDir.mkdirs()
        cacheDir.mkdirs()
        // ADR-018: Briar's AndroidTorWrapper requires the host Application
        // for its wake-lock manager and resource extraction. We pass it
        // through the platformContext channel of the cross-platform factory.
        createTorService(
            config = TorServiceConfig(
                dataDirectoryPath = workDir.absolutePath,
                cacheDirectoryPath = cacheDir.absolutePath,
                // ADR-018 Stage 5B: bridges always on while USE_TOR build flag
                // is the only Tor gate. Stage 5C will make this mode-dependent
                // — Standard/Private use bridges only when auto-fallback path
                // is active; Ghost always uses bridges (privacy contract).
                useBridges = true,
            ),
            platformContext = application,
        )
    }

    // Stage 2C.5: tracks the foreground notification updater coroutine so
    // we can launch it once in onCreate and not duplicate on Android's
    // potential redelivered onStartCommand intents.
    private var notificationUpdaterJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(DEFAULT_STATUS_TEXT))
        acquireKeepAliveLocks()
        startNotificationUpdater()
    }

    /**
     * Stage 2C.5: subscribe to [TorService.state] and rewrite the foreground
     * notification's content text whenever the state changes. Lets the user
     * see live bootstrap progress — and pinpoint stuck phases — without USB
     * cable + logcat. No-op when [BuildConfig.USE_TOR] is false (the lazy
     * [torService] is null and the launch exits immediately).
     */
    private fun startNotificationUpdater() {
        val tor = torService ?: return
        if (notificationUpdaterJob?.isActive == true) return
        notificationUpdaterJob = serviceScope.launch {
            tor.state.collect { state ->
                val text = when (state) {
                    is TorState.Off -> DEFAULT_STATUS_TEXT
                    is TorState.Bootstrapping ->
                        if (state.percent == 0) "Tor: connecting…"
                        else "Tor: bootstrapping ${state.percent}%"
                    is TorState.Ready -> "Tor: ready (SOCKS ${state.socksPort})"
                    is TorState.Failed -> "Tor: failed — ${state.message.take(80)}"
                }
                runCatching {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(NOTIFICATION_ID, buildNotification(text))
                }.onFailure {
                    Log.w(TAG, "Notification update failed: ${it.message}")
                }
            }
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
            // ADR-016 Stage 2C: choose data path. USE_TOR=true → start the
            // embedded Tor daemon, await its SOCKS port, route the WebSocket
            // through it to the relay's onion address. USE_TOR=false →
            // existing direct-WSS path, unchanged. The Privacy-Mode UI in
            // Stage 4 will replace BuildConfig.USE_TOR with a runtime
            // setting, but the surrounding flow remains the same.
            val useTor = BuildConfig.USE_TOR
            val socksProxyPort: Int? = if (useTor) {
                val tor = torService
                if (tor == null) {
                    Log.e(TAG, "USE_TOR=true but TorService is null — cannot start, aborting connect")
                    return@launch
                }
                Log.i(TAG, "Starting embedded Tor (USE_TOR=true)…")
                runCatching { tor.start() }.onFailure {
                    Log.e(TAG, "TorService.start() failed: ${it.message}", it)
                }
                try {
                    val ready = withTimeout(TOR_BOOTSTRAP_TIMEOUT_MS) {
                        tor.state.first { it is TorState.Ready }
                    } as TorState.Ready
                    Log.i(TAG, "Tor bootstrapped (SOCKS port ${ready.socksPort})")
                    ready.socksPort
                } catch (timeout: TimeoutCancellationException) {
                    Log.e(TAG, "Tor bootstrap timed out after ${TOR_BOOTSTRAP_TIMEOUT_MS} ms — aborting connect")
                    return@launch
                }
            } else {
                null
            }

            val relayUrl = if (useTor) BuildConfig.RELAY_ONION_URL else BuildConfig.RELAY_URL
            Log.i(
                "PhantomRelay",
                "PhantomMessagingService about to connect: " +
                    "url=$relayUrl " +
                    "tokenSet=${BuildConfig.RELAY_TOKEN != null} " +
                    "socks=${socksProxyPort ?: "direct"} " +
                    "myPubKey=${myPubKey.take(16)}…",
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
                    token = BuildConfig.RELAY_TOKEN,
                    socksProxyPort = socksProxyPort,
                )
            }.onFailure { e ->
                Log.e(TAG, "Transport connect loop exited: ${e.message}", e)
                Log.e("PhantomRelay", "Transport connect loop exited: ${e.message}", e)
            }
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
        serviceScope.launch {
            runCatching { (application as PhantomApplication).container.transport.disconnect() }
        }
        // ADR-016 Stage 2C: tear down the embedded Tor daemon. Inline
        // runBlocking on the main thread is acceptable in onDestroy because
        // tor.stop() returns as soon as the kmp-tor StopDaemon action is
        // accepted (the actual socket teardown happens off-thread); without
        // this, a stop-and-restart of the service could see overlapping tor
        // instances racing on the same DataDirectory volume.
        torService?.let { tor ->
            runCatching {
                runBlocking { withTimeout(TOR_STOP_TIMEOUT_MS) { tor.stop() } }
            }.onFailure { Log.w(TAG, "TorService.stop() failed: ${it.message}") }
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
        // Used for the foreground notification when no Tor state info is
        // relevant (USE_TOR=false, or USE_TOR=true and Tor is currently Off).
        private const val DEFAULT_STATUS_TEXT = "Encrypted connection active"

        // ADR-018 Stage 5C: maximum time we wait for Tor to bootstrap a
        // circuit before giving up and aborting the connect attempt.
        // Bumped from 120 s to 600 s (10 min) after Test 11 (2026-05-06)
        // showed a fresh operator-controlled bridge on Hetzner takes
        // 5-8 min for first-time bootstrap on a censored network — the
        // bridge has no cached microdescriptors, no established guard
        // relations, and no reputation in Tor consensus, so directory
        // authorities deprioritise it. After 24-48 h of warm-up the
        // expected number drops to 60-90 s; the timeout is sized for
        // the cold-start case so the app does not abort prematurely.
        // Once Stage 5D (multi-bridge load balancing + bridge warming)
        // ships, this can be tightened back to ~180 s.
        private const val TOR_BOOTSTRAP_TIMEOUT_MS = 600_000L
        // Tor stop is fast (the StopDaemon action ack is immediate), so
        // a much shorter cap is fine in onDestroy.
        private const val TOR_STOP_TIMEOUT_MS = 5_000L
    }
}
