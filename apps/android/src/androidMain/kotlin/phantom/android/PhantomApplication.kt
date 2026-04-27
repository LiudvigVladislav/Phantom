// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android

import android.app.Application
import android.util.Log
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
}
