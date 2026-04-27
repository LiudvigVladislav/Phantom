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
    when (level) {
        MessagingLogLevel.INFO  -> if (throwable == null) Log.i(TAG, message) else Log.i(TAG, message, throwable)
        MessagingLogLevel.WARN  -> if (throwable == null) Log.w(TAG, message) else Log.w(TAG, message, throwable)
        MessagingLogLevel.ERROR -> if (throwable == null) Log.e(TAG, message) else Log.e(TAG, message, throwable)
    }
}
