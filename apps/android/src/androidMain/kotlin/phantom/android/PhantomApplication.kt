// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.notifications.PhantomNotificationManager

class PhantomApplication : Application() {

    /** Completes once libsodium is initialised and AppContainer is ready. */
    val ready = CompletableDeferred<Unit>()

    lateinit var container: AppContainer
        private set

    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // SQLCipher native lib must be loaded before any database access.
        System.loadLibrary("sqlcipher")
        // Channel must exist before the first notification — idempotent, safe to call here.
        PhantomNotificationManager.createChannel(this)
        logNotificationStartupSnapshot()
        Log.d("PHANTOM_INIT", "Application onCreate — starting background init")
        initScope.launch {
            try {
                Log.d("PHANTOM_INIT", "Initializing libsodium…")
                LibsodiumInitializer.initialize()
                Log.d("PHANTOM_INIT", "libsodium OK — creating AppContainer…")
                container = AppContainer(this@PhantomApplication)
                Log.d("PHANTOM_INIT", "AppContainer OK — signalling ready")
                ready.complete(Unit)
            } catch (t: Throwable) {
                // Catch ALL throwables — including UnsatisfiedLinkError (native JNI)
                Log.e("PHANTOM_INIT", "INIT FAILED: ${t::class.simpleName}: ${t.message}", t)
                ready.completeExceptionally(t)
            }
        }
    }

    /**
     * PR-NOTIF-DIAG (2026-05-22) — one-time per-process snapshot of the device's
     * notification posture. Lets a Test #78 logcat capture immediately attribute
     * a missed heads-up to (a) revoked POST_NOTIFICATIONS, (b) OEM-disabled
     * channel, (c) lowered channel importance, without re-deriving the state
     * after the test. No behaviour change — observability only.
     */
    private fun logNotificationStartupSnapshot() {
        val nmc = NotificationManagerCompat.from(this)
        val appNotificationsEnabled = nmc.areNotificationsEnabled()
        val channelExists: Boolean
        val channelEnabled: Boolean
        val channelImportance: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = nmc.getNotificationChannel(PhantomNotificationManager.CHANNEL_ID)
            channelExists = channel != null
            channelImportance = channel?.importance ?: NotificationManager.IMPORTANCE_NONE
            channelEnabled = channelExists && channelImportance != NotificationManager.IMPORTANCE_NONE
        } else {
            channelExists = false
            channelEnabled = appNotificationsEnabled
            channelImportance = -1
        }
        val permissionGranted: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            granted.toString()
        } else {
            "n_a_pre_33"
        }
        Log.i(
            "PhantomNotif",
            "NOTIF app_snapshot permissionGranted=$permissionGranted channelExists=$channelExists " +
                "channelEnabled=$channelEnabled channelImportance=$channelImportance " +
                "appNotificationsEnabled=$appNotificationsEnabled sdk=${Build.VERSION.SDK_INT}",
        )
    }
}
