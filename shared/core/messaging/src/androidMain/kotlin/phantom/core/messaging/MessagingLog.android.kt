// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import android.util.Log

private const val TAG = "PhantomMessaging"

internal actual fun messagingLog(
    level: MessagingLogLevel,
    message: String,
    throwable: Throwable?,
) {
    MessagingDiagnosticObserver.observe(message)
    when (level) {
        MessagingLogLevel.INFO  -> if (throwable == null) Log.i(TAG, message) else Log.i(TAG, message, throwable)
        MessagingLogLevel.WARN  -> if (throwable == null) Log.w(TAG, message) else Log.w(TAG, message, throwable)
        MessagingLogLevel.ERROR -> if (throwable == null) Log.e(TAG, message) else Log.e(TAG, message, throwable)
    }
}

/** Optional Android observer; only the explicitly armed debug recorder installs it. */
object MessagingDiagnosticObserver {
    @Volatile var sink: ((String) -> Unit)? = null
    fun observe(message: String) {
        try { sink?.invoke(message) } catch (_: Exception) { /* Diagnostics must not affect delivery. */ }
    }
}
