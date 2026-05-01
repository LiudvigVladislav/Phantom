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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import phantom.android.BuildConfig
import phantom.android.PhantomApplication

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

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireKeepAliveLocks()
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
    }

    private fun releaseKeepAliveLocks() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wifiLock = null
        wakeLock = null
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
            Log.i(
                "PhantomRelay",
                "PhantomMessagingService about to connect: " +
                    "BuildConfig.RELAY_URL=${BuildConfig.RELAY_URL} " +
                    "tokenSet=${BuildConfig.RELAY_TOKEN != null} " +
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
                    relayUrl = BuildConfig.RELAY_URL,
                    identityPublicKeyHex = myPubKey,
                    token = BuildConfig.RELAY_TOKEN,
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

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("PHANTOM")
        .setContentText("Encrypted connection active")
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
    }
}
