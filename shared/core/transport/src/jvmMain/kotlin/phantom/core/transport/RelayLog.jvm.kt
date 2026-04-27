// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

internal actual fun relayLog(
    level: RelayLogLevel,
    message: String,
    throwable: Throwable?,
) {
    println("PhantomRelay [${level.name}]: $message")
    throwable?.printStackTrace()
}
