// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import java.util.Locale

actual fun classifyNetworkFailure(t: Throwable): RetryDecision =
    classifyOnJvm(t)

/**
 * JVM/Android classifier body. Duplicated verbatim in the sibling
 * source set at
 * `shared/core/transport/src/androidMain/kotlin/phantom/core/transport/RetryClassificationAndroid.kt`
 * because `androidMain` and `jvmMain` are independent source sets and
 * cannot share a private function without an additional intermediate
 * source set (not configured on this module).
 *
 * The two bodies MUST stay identical. A CI check
 * (`assertClassifyOnJvmBodiesMatch`) in
 * `shared/core/transport/build.gradle.kts` extracts both function
 * bodies and fails the build if they differ.
 *
 * Any change here MUST also land in the androidMain twin, character-
 * for-character, or CI will reject the PR.
 */
internal fun classifyOnJvm(t: Throwable): RetryDecision {
    // Cause-chain walk (depth 5). Symmetric TLS-terminal + transient
    // recognition — Ktor 3.0.3's SavedCall / DoubleReceivePlugin wrap
    // body-read failures in IOException(cause=IOException(cause=<real>))
    // through io.ktor.utils.io.CloseToken; the real transient throwable
    // sits on cause depth 2. Walking the chain here (instead of relying
    // on IOException-message keywords alone) covers request-read AND
    // body-read paths through the same classifier — the T-F4 invariant.
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 5) {
        if (cur is javax.net.ssl.SSLPeerUnverifiedException) return RetryDecision.TerminalTls
        if (cur is java.security.cert.CertificateException) return RetryDecision.TerminalTls
        if (cur is java.net.SocketTimeoutException) return RetryDecision.RetryableTransient
        if (cur is java.net.ConnectException) return RetryDecision.RetryableTransient
        if (cur is java.net.NoRouteToHostException) return RetryDecision.RetryableTransient
        if (cur is java.net.BindException) return RetryDecision.RetryableTransient
        if (cur is java.net.UnknownHostException) return RetryDecision.RetryableTransient
        if (cur is java.io.EOFException) return RetryDecision.RetryableTransient
        if (cur is io.ktor.client.plugins.HttpRequestTimeoutException) return RetryDecision.RetryableTransient
        if (cur is io.ktor.client.network.sockets.ConnectTimeoutException) return RetryDecision.RetryableTransient
        cur = cur.cause
        depth++
    }

    // Own-deadline synthesized signal.
    if (t is FetchStatusDeadlineExceededException) return RetryDecision.TerminalOther

    // SSL classes retryable only if a transient transport signal is
    // present in the cause chain OR message; otherwise terminal.
    if (t is javax.net.ssl.SSLException) {
        return if (hasTransientTransportSignalJvm(t)) RetryDecision.RetryableTransient
        else RetryDecision.TerminalTls
    }

    return when {
        t is java.io.IOException -> {
            val msg = t.message?.lowercase(Locale.ROOT) ?: ""
            if (TRANSIENT_MESSAGE_SIGNALS.any { it in msg }) RetryDecision.RetryableTransient
            else RetryDecision.TerminalOther
        }
        else -> RetryDecision.TerminalOther
    }
}

internal fun hasTransientTransportSignalJvm(t: Throwable): Boolean {
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 5) {
        if (cur is java.net.SocketTimeoutException) return true
        if (cur is java.io.EOFException) return true
        if (cur is io.ktor.client.plugins.HttpRequestTimeoutException) return true
        if (cur is io.ktor.client.network.sockets.ConnectTimeoutException) return true
        val msg = cur.message?.lowercase(Locale.ROOT)
        if (msg != null && TRANSIENT_MESSAGE_SIGNALS.any { it in msg }) return true
        cur = cur.cause
        depth++
    }
    return false
}
