// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import phantom.android.PhantomApplication

/**
 * AlarmManager-driven network-keepalive receiver. ADR-011.
 *
 * Wakes the device every [WAKEUP_INTERVAL_MS] (nominally 30 s; Android Doze
 * floors `setExactAndAllowWhileIdle()` to 60 s, so during deep idle we get
 * ~60 s spacing whether we ask for less or not). On each fire we check
 * whether the WebSocket transport is healthy by reading
 * [phantom.core.transport.RelayTransport.lastPongElapsedMs]. If the pong is
 * stale (>= [PONG_STALE_THRESHOLD_MS]) we call `forceReconnect()` — this is
 * the only known way to recover a parked-radio dead WebSocket on aggressive-
 * battery OEMs like Tecno HiOS. See ADR-010 + ADR-011 for the chain of
 * reasoning behind why no user-space-only solution worked.
 */
class PhantomWakeupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // goAsync() returns a PendingResult that keeps the receiver alive
        // long enough for our coroutine to finish. Without it the system
        // tears down the receiver and our process the moment onReceive
        // returns — which would happen before the suspend forceReconnect()
        // call has a chance to dispatch.
        val pendingResult = goAsync()
        // Acquire a short WakeLock independently of the goAsync window so
        // the CPU stays awake even if the broadcast pending-result is
        // released early. Released in the coroutine's finally clause.
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "phantom:wakeup-receiver",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_BUDGET_MS)
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                handleWake(context.applicationContext)
            } catch (t: Throwable) {
                Log.e(TAG, "wakeup handler threw: ${t.message}", t)
            } finally {
                runCatching { if (wakeLock.isHeld) wakeLock.release() }
                pendingResult.finish()
                // Always reschedule, regardless of whether we forced a
                // reconnect. This forms a self-sustaining heartbeat.
                schedule(context.applicationContext)
            }
        }
    }

    private suspend fun handleWake(appContext: Context) {
        // ADR Tier-1 (HiOS workaround): poke ConnectivityManager BEFORE
        // checking pong staleness. This is a higher-level API the OEM
        // is less likely to silently ignore than WifiLock/MulticastLock.
        // It tells the OS "I need internet now"; on aggressive-OEM
        // builds it can force the radio out of deep-park even when the
        // battery panel says we should be unrestricted. Best-effort —
        // never blocks for long; silently no-ops on Android <23.
        runCatching {
            pokeConnectivity(appContext)
        }.onFailure { Log.w(TAG, "pokeConnectivity threw: ${it.message}") }

        val app = appContext as? PhantomApplication
        if (app == null) {
            Log.w(TAG, "applicationContext is not PhantomApplication — cannot reach container")
            return
        }
        // If AppContainer is still initialising (cold start triggered by
        // alarm before the user has launched the app), let the service
        // start path handle the connection — don't try to forceReconnect on
        // a half-initialised transport.
        if (!app.ready.isCompleted) {
            Log.i(TAG, "App not ready yet — starting foreground service to drive init")
            startMessagingService(appContext)
            return
        }
        val container = app.container
        val transport = runCatching { container.transport }.getOrNull()
        if (transport == null) {
            Log.w(TAG, "transport unavailable — starting service")
            startMessagingService(appContext)
            return
        }
        // No identity yet → service start is a no-op anyway, no reason
        // to force a reconnect on a transport that hasn't connected yet.
        val identityLoaded = runCatching { container.messagingService != null }.getOrDefault(false)
        if (!identityLoaded) {
            Log.i(TAG, "no identity yet — skipping wake action")
            return
        }
        val elapsed = transport.lastPongElapsedMs
        val connected = transport.isConnected()
        if (connected && elapsed < PONG_STALE_THRESHOLD_MS) {
            Log.i(TAG, "pong fresh (${elapsed}ms) — no action")
            return
        }
        Log.w(
            TAG,
            "stale pong (${elapsed}ms) or transport not connected (connected=$connected) — forcing reconnect",
        )
        runCatching { transport.forceReconnect() }
            .onFailure { Log.e(TAG, "forceReconnect threw: ${it.message}", it) }
    }

    /**
     * Best-effort radio wake. Sends a NetworkRequest with
     * NET_CAPABILITY_INTERNET; the system MAY interpret this as
     * "user wants connectivity now" and bring the radio out of low-
     * power state. Releases the request after a short window so the
     * scheduler doesn't think we want a permanent dedicated network.
     */
    private fun pokeConnectivity(appContext: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        var registered: ConnectivityManager.NetworkCallback? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "pokeConnectivity: network available — radio is awake")
            }
        }
        runCatching {
            cm.requestNetwork(request, callback, CONNECTIVITY_POKE_BUDGET_MS.toInt())
            registered = callback
        }.onFailure {
            Log.w(TAG, "pokeConnectivity: requestNetwork failed: ${it.message}")
        }
        // Release the request after the budget regardless of whether
        // the callback ever fired. unregister is idempotent.
        if (registered != null) {
            android.os.Handler(appContext.mainLooper).postDelayed({
                runCatching { cm.unregisterNetworkCallback(callback) }
            }, CONNECTIVITY_POKE_BUDGET_MS)
        }
    }

    private fun startMessagingService(appContext: Context) {
        runCatching {
            val intent = Intent(appContext, PhantomMessagingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }.onFailure {
            Log.e(TAG, "could not start PhantomMessagingService: ${it.message}", it)
        }
    }

    companion object {
        private const val TAG = "PhantomWakeup"

        /**
         * Nominal interval between wakeups. Doze rate-limits
         * `setExactAndAllowWhileIdle` to 1/min so during deep idle the
         * effective interval is ~60 s; when the device is active we get
         * the requested 30 s.
         */
        const val WAKEUP_INTERVAL_MS: Long = 30_000L

        /**
         * Pong staleness above which we force a reconnect. Aligns with
         * RelayTransportConfig.PONG_TIMEOUT_MS (25 s by default) plus a
         * small margin so we don't race the in-process pong watchdog.
         */
        const val PONG_STALE_THRESHOLD_MS: Long = 25_000L

        /**
         * WakeLock budget: how long we will hold the CPU awake during
         * onReceive's coroutine. Generous — forceReconnect is fast (it
         * just dispatches a scope cancel) but the actual reconnect's
         * webSocket() handshake on a slow link can take several seconds.
         */
        const val WAKELOCK_BUDGET_MS: Long = 10_000L

        /**
         * Lifetime of the ConnectivityManager.requestNetwork poke
         * inside onReceive. Long enough to give the OS time to
         * respond, short enough that we don't accumulate held
         * network requests across alarm fires.
         */
        const val CONNECTIVITY_POKE_BUDGET_MS: Long = 3_000L

        /**
         * Single PendingIntent request code shared between schedule and
         * cancel. Keeping this constant guarantees alarm idempotency:
         * subsequent schedules with the same code REPLACE rather than
         * accumulate.
         */
        const val WAKEUP_ALARM_REQUEST_CODE = 9001

        /**
         * Schedule (or replace) the next wakeup alarm. Safe to call from
         * anywhere; idempotent.
         *
         * Returns true if the alarm was scheduled exactly, false if we
         * had to fall back to inexact (Android 14+ permission revoked).
         */
        fun schedule(appContext: Context): Boolean {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = pendingIntentFor(appContext)
            val triggerAt = SystemClock.elapsedRealtime() + WAKEUP_INTERVAL_MS

            return runCatching {
                val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.canScheduleExactAlarms()
                } else {
                    true
                }
                if (canExact) {
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                    true
                } else {
                    Log.w(TAG, "USE_EXACT_ALARM revoked — falling back to inexact alarm")
                    am.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                    false
                }
            }.onFailure {
                Log.e(TAG, "alarm schedule failed: ${it.message}", it)
            }.getOrDefault(false)
        }

        /** Cancel any scheduled wakeup. Called from PhantomMessagingService.onDestroy. */
        fun cancel(appContext: Context) {
            runCatching {
                val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(pendingIntentFor(appContext))
            }
        }

        private fun pendingIntentFor(appContext: Context): PendingIntent {
            val intent = Intent(appContext, PhantomWakeupReceiver::class.java).apply {
                action = ACTION_WAKEUP
            }
            return PendingIntent.getBroadcast(
                appContext,
                WAKEUP_ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private const val ACTION_WAKEUP = "phantom.android.WAKEUP_KEEPALIVE"
    }
}
