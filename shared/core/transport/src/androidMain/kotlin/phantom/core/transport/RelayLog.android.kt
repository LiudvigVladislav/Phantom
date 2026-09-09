// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import android.util.Log

private const val TAG = "PhantomRelay"

internal actual fun relayLog(
    level: RelayLogLevel,
    message: String,
    throwable: Throwable?,
) {
    RelayDiagnosticObserver.observe(message)
    when (level) {
        RelayLogLevel.INFO  -> if (throwable == null) Log.i(TAG, message) else Log.i(TAG, message, throwable)
        RelayLogLevel.WARN  -> if (throwable == null) Log.w(TAG, message) else Log.w(TAG, message, throwable)
        RelayLogLevel.ERROR -> if (throwable == null) Log.e(TAG, message) else Log.e(TAG, message, throwable)
    }
}

/** Release-inert observer. No sink is installed by release code. */
object RelayDiagnosticObserver {
    @Volatile var sink: ((String) -> Unit)? = null
    fun observe(message: String) {
        try { sink?.invoke(message) } catch (_: Exception) { /* Diagnostics must not affect transport. */ }
    }
}
