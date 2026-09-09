package phantom.android.security

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Waits for access, without changing any Keystore policy or retaining decrypted keys. */
internal class DeviceUnlockGate(private val context: Context) {
    private val keyguard get() = context.getSystemService(KeyguardManager::class.java)

    suspend fun awaitUnlocked() {
        currentCoroutineContext().ensureActive()
        if (!keyguard.isDeviceLocked) return
        val unlocked = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // The broadcast is only a wakeup. The system state grants access.
                if (!keyguard.isDeviceLocked) unlocked.complete(Unit)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            // Registration precedes the second check, so an unlock cannot be missed.
            if (!keyguard.isDeviceLocked) unlocked.complete(Unit)
            unlocked.await()
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    /** Only repeat a read if the device locked between admission and key use. */
    suspend fun <T> readWhenUnlocked(read: suspend () -> T): T {
        while (true) {
            awaitUnlocked()
            try {
                return read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!keyguard.isDeviceLocked) throw failure
            }
        }
    }
}
