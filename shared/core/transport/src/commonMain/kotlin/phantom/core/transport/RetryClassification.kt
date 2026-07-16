// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.statement.HttpResponse

/**
 * Internal-timeout signal for fetchStatus attempts. NOT a
 * CancellationException — so the outer verify layer's re-throw of
 * CancellationException does not accidentally kill the periodic ticker
 * / Connected collector.
 *
 * commonMain-compatible: extends kotlin.Exception (not IOException),
 * requires no expect/actual scaffolding.
 *
 * classifyNetworkFailure returns TerminalOther for this class: by the
 * time it is thrown, the retry loop has already exhausted its budget,
 * so the classifier MUST NOT re-classify it as transient.
 */
class FetchStatusDeadlineExceededException(
    cause: Throwable? = null,
) : Exception("fetchStatus attempt deadline exceeded", cause)

/**
 * Signalled by fetchStatus after retries exhaust on repeated
 * empty or Content-Length-truncated 2xx bodies. commonMain-native
 * (extends kotlin.Exception, not java.net.ProtocolException) so it
 * compiles on iosMain as well.
 *
 * classifyNetworkFailure returns TerminalOther for this class:
 * by the time it is thrown the retry loop has already exhausted
 * its budget, so it MUST NOT be re-classified as transient.
 */
class PreKeyBodyTruncatedException(
    val attempts: Int,
) : Exception("body truncated after $attempts attempts")

/**
 * Verdict for whether a network exception should be retried at the
 * transport retry loop. Consumed by both fetchStatus (request-read
 * and body-read catch sites) AND publishWithRetry.
 */
enum class RetryDecision {
    /** Retry per attempt-budget + backoff. Transient network/TLS class. */
    RetryableTransient,

    /** Terminal TLS class (cert/peer-verification/hostname). No retry. */
    TerminalTls,

    /**
     * Non-network terminal: SerializationException, protocol violation,
     * arbitrary non-retryable throwable, or synthesized
     * [FetchStatusDeadlineExceededException] (already-exhausted signal).
     */
    TerminalOther,
}

/**
 * Platform-specific classification. Actuals live in androidMain +
 * jvmMain + iosMain. commonMain contains ZERO java.* / javax.* /
 * SSLException references.
 */
expect fun classifyNetworkFailure(t: Throwable): RetryDecision

/**
 * Body-shape verdict for 2xx responses. Distinguishes middlebox
 * truncation (retryable) from empty (retryable) from complete
 * (parse and decide from status/decode).
 */
enum class BodyClass { Empty, TruncatedByLength, Complete }

fun classifyBodyTruncation(response: HttpResponse, body: String): BodyClass {
    if (body.isEmpty()) return BodyClass.Empty
    val encoding = response.headers["Content-Encoding"]?.lowercase()
    if (encoding != null && encoding != "identity") return BodyClass.Complete
    val declaredLenStr = response.headers["Content-Length"] ?: return BodyClass.Complete
    val declaredLen = declaredLenStr.toLongOrNull() ?: return BodyClass.Complete
    val receivedLen = body.encodeToByteArray().size.toLong()
    return if (declaredLen > receivedLen) BodyClass.TruncatedByLength else BodyClass.Complete
}

/**
 * Retry-After parser — narrow, deterministic contract:
 *   - Accepts non-negative decimal delta-seconds ONLY.
 *   - Caps the parsed value at 60 seconds BEFORE conversion to millis.
 *   - HTTP-date form is NOT parsed in this PR.
 *   - Null header, non-numeric, negative, or unparsable → returns null.
 *     Caller coalesces null to a fallback wait.
 */
fun parseRetryAfterMs(headerValue: String?): Long? {
    if (headerValue == null) return null
    val seconds = headerValue.trim().toLongOrNull() ?: return null
    if (seconds < 0L) return null
    val cappedSeconds = seconds.coerceAtMost(60L)
    return cappedSeconds * 1000L
}

/**
 * Signal keywords used by JVM/Android actuals to distinguish a
 * genuinely-transient SSL failure (retry) from a terminal cert/peer
 * failure (do not retry) when the top-level throwable is a plain
 * SSLException. Kept commonMain so both actuals import from one place.
 */
internal val TRANSIENT_MESSAGE_SIGNALS: List<String> = listOf(
    "timeout",
    "timed out",
    "connection reset",
    "connection closed",
    "broken pipe",
    "stream closed",
    "closed connection",
    "unexpected end of stream",
    "eof",
)
