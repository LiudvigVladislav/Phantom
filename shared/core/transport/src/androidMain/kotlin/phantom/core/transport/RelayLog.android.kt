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
    when (level) {
        RelayLogLevel.INFO  -> if (throwable == null) Log.i(TAG, message) else Log.i(TAG, message, throwable)
        RelayLogLevel.WARN  -> if (throwable == null) Log.w(TAG, message) else Log.w(TAG, message, throwable)
        RelayLogLevel.ERROR -> if (throwable == null) Log.e(TAG, message) else Log.e(TAG, message, throwable)
    }
}
