package phantom.android.diagnostic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import phantom.core.messaging.MessagingDiagnosticObserver
import phantom.core.transport.RelayDiagnosticObserver

/** Opt-in debug-only journal; no wake lock, timer, reconnect or network probe. */
internal object BackgroundRecorder {
    private var installed = false
    internal const val MAX_RUN_MS = 2 * 60 * 60 * 1000L

    internal fun remainingArmMs(text: String, now: Long): Long? {
        if (text.length > 32) return null
        val until = text.trim().toLongOrNull() ?: return null
        if (until <= now || until > now + MAX_RUN_MS) return null
        return until - now
    }

    @Synchronized
    fun start(context: Context) {
        if (installed) return
        val directory = File(context.noBackupFilesDir, "background-diagnostic")
        val arm = File(directory, "armed-until")
        val duration = try {
            if (!arm.isFile || arm.length() > 32) return
            remainingArmMs(arm.readText(), System.currentTimeMillis()) ?: return
        } catch (_: Exception) { return }
        installed = true
        val deadline = SystemClock.elapsedRealtime() + duration
        val journal = BackgroundJournal(directory, System::currentTimeMillis,
            SystemClock::elapsedRealtime, Process.myPid())
        fun record(fields: Map<String, String>) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                journal.close()
                return
            }
            try { journal.record(fields) } catch (_: Exception) { journal.close() }
        }
        val sink: (String, String) -> Unit = { source, line ->
            BackgroundEventFilter.project(source, line)?.let(::record)
        }
        BackgroundTrace.sink = sink
        MessagingDiagnosticObserver.sink = { sink("messaging", it) }
        RelayDiagnosticObserver.sink = { sink("relay", it) }
        record(mapOf("event" to "process_start", "source" to "device", "schema" to "1"))

        val power = context.getSystemService(PowerManager::class.java)
        fun powerSnapshot(event: String) {
            try {
            record(mapOf("source" to "device", "event" to event,
                "interactive" to power.isInteractive.toString(),
                "idle" to power.isDeviceIdleMode.toString(),
                "power_save" to power.isPowerSaveMode.toString(),
                "battery_exempt" to power.isIgnoringBatteryOptimizations(context.packageName).toString()))
            } catch (_: Exception) { record(mapOf("event" to "observer_failed", "observer" to "power")) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == "android.hardware.usb.action.USB_STATE") {
                    record(mapOf("source" to "device", "event" to "usb_state",
                        "connected" to intent.getBooleanExtra("connected", false).toString()))
                    return
                }
                val event = when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> "screen_off"
                    Intent.ACTION_SCREEN_ON -> "screen_on"
                    Intent.ACTION_USER_PRESENT -> "unlocked"
                    Intent.ACTION_POWER_CONNECTED -> "power_connected"
                    Intent.ACTION_POWER_DISCONNECTED -> "power_disconnected"
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "idle_changed"
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "power_save_changed"
                    else -> return
                }
                powerSnapshot(event)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED); addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction("android.hardware.usb.action.USB_STATE")
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
            powerSnapshot("power_initial")
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            record(mapOf("source" to "device", "event" to "charging_initial",
                "plugged" to (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1).toString()))
        } catch (_: Exception) { record(mapOf("event" to "observer_failed", "observer" to "power")) }

        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun networkSnapshot(event: String, capabilities: NetworkCapabilities?) {
            record(mapOf("source" to "device", "event" to event,
                "present" to (capabilities != null).toString(),
                "wifi" to (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true).toString(),
                "cellular" to (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true).toString(),
                "vpn" to (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true).toString(),
                "validated" to (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true).toString()))
        }
        try {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    record(mapOf("source" to "device", "event" to "network_available"))
                }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    networkSnapshot("network_capabilities", capabilities)
                }
                override fun onLost(network: Network) {
                    record(mapOf("source" to "device", "event" to "network_lost"))
                }
            })
            networkSnapshot("network_initial", connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities))
        } catch (_: Exception) { record(mapOf("event" to "observer_failed", "observer" to "network")) }
    }
}
