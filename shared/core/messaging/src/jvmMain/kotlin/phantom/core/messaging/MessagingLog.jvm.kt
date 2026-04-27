// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

internal actual fun messagingLog(
    level: MessagingLogLevel,
    message: String,
    throwable: Throwable?,
) {
    println("PhantomMessaging [${level.name}]: $message")
    throwable?.printStackTrace()
}
