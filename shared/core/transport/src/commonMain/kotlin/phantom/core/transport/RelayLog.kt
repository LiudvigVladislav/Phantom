// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * Platform-agnostic logger used by the transport layer.
 *
 * On Android the actual implementation calls android.util.Log with the
 * "PhantomRelay" tag so logcat filters like `adb logcat PhantomRelay:V *:S`
 * capture every transport event.
 *
 * On the JVM target (tests, future desktop) the actual writes to stdout
 * with a "PhantomRelay" prefix.
 */
internal enum class RelayLogLevel { INFO, WARN, ERROR }

internal expect fun relayLog(
    level: RelayLogLevel,
    message: String,
    throwable: Throwable? = null,
)
