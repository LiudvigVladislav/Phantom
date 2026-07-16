// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * Conservative iOS actual. Ktor Darwin engine's own timeout exceptions
 * are recognised as retryable; everything else defaults to
 * [RetryDecision.TerminalOther] so we never retry an unrecognised
 * class on iOS.
 *
 * NOT compile-checked from Windows: iOS targets are only enabled on
 * macOS / CI (see `shared/core/transport/build.gradle.kts` line 10-11).
 * This file is a spec placeholder against future iOS enablement.
 *
 * Note on `SocketTimeoutException`: the binding doc §5 D5 lists
 * `SocketTimeoutException` as retryable, but that entry refers to
 * `java.net.SocketTimeoutException` (JVM/Android — see the androidMain
 * and jvmMain classifiers). Ktor 3.0.3 does NOT ship a Ktor-owned
 * `SocketTimeoutException` class — the only sockets-timeout class in
 * `ktor-client-core:3.0.3` is [io.ktor.client.network.sockets.ConnectTimeoutException].
 * On Darwin the equivalent socket-read-timeout surfaces as
 * [io.ktor.client.plugins.HttpRequestTimeoutException] when the
 * HttpTimeout plugin is installed, or as an NSURLError-derived
 * throwable otherwise.
 *
 * Full NSURLError inspection (secure-connection-failed, cert-invalid,
 * NSURLErrorTimedOut, etc.) is deferred until iOS becomes a shipped
 * prekey target. Track: CLIENT-PREKEY-SELFHEAL follow-up.
 */
actual fun classifyNetworkFailure(t: Throwable): RetryDecision = when {
    t is FetchStatusDeadlineExceededException -> RetryDecision.TerminalOther
    t is io.ktor.client.plugins.HttpRequestTimeoutException -> RetryDecision.RetryableTransient
    t is io.ktor.client.network.sockets.ConnectTimeoutException -> RetryDecision.RetryableTransient
    else -> RetryDecision.TerminalOther
}
