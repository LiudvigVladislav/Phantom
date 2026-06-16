// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * Platform-independent contract for the POST /prekeys/publish upload.
 *
 * PR-R0.1: The Ktor OkHttp engine adapter stalls deterministically at
 * 8192 bytes (one JVM ByteChannel default write buffer) when streaming
 * the request body on Android. This interface lets the Android actual
 * bypass Ktor entirely and hand a pre-built ByteArray to native OkHttp,
 * which writes it in one shot via okio.Buffer.writeAll().
 *
 * Callers (PreKeyApiClient.publishWithRetry) still own retry, backoff,
 * mutex debounce, and structured logging — this interface only covers
 * the single HTTP round-trip.
 *
 * Implementations MUST:
 *  - Use HTTP/1.1, no connection pool, fresh socket per call.
 *  - Send a "Connection: close" header.
 *  - Pass the raw [bodyBytes] without chunked transfer encoding.
 *  - Set Content-Length to bodyBytes.size.
 *  - Throw the underlying exception unchanged so isRetryable() can match it.
 *
 * On any throwable the caller's retry layer decides whether to retry.
 */
interface PreKeyPublishHttpTransport {
    /**
     * POST the pre-serialised JSON body to /prekeys/publish and return
     * the response status + body text.
     *
     * @param url          Full HTTPS URL, e.g. "https://relay.phntm.pro/prekeys/publish".
     * @param bodyBytes    UTF-8-encoded JSON payload (pre-built by caller).
     * @param contentType  MIME type; defaults to "application/json".
     * @throws Throwable   Any network exception propagated as-is for the caller's
     *                     isRetryable() classification.
     */
    suspend fun publish(
        url: String,
        bodyBytes: ByteArray,
        contentType: String = "application/json",
    ): PreKeyPublishHttpResponse
}

/**
 * Value returned by a successful [PreKeyPublishHttpTransport.publish] call
 * (i.e., when an HTTP response was received — status may be an error code).
 */
data class PreKeyPublishHttpResponse(
    /** HTTP status code, e.g. 201, 408, 422. */
    val statusCode: Int,
    /** Response body decoded as UTF-8; relay returns small JSON or an error string. */
    val bodyText: String,
    /** Wall-clock elapsed time for this single HTTP round-trip, in milliseconds. */
    val elapsedMs: Long,
    /**
     * Negotiated HTTP protocol on this call's wire — `"http/1.1"`,
     * `"h2"`, `"h2_prior_knowledge"`, `"h3"`, `"spdy/3.1"`, or
     * implementation-specific strings. Null when the platform
     * implementation cannot expose the value (e.g. JVM stub) or when
     * the response object never carried protocol metadata.
     *
     * T2 carrier-ceiling instrumentation (2026-06-16 Option A Item 3):
     * the orchestrator distinguishes HTTP/1.1 + HTTP/2 (over TCP, both
     * subject to the carrier byte-budget mechanism) from HTTP/3 (over
     * QUIC/UDP, not subject to the TCP byte-budget). If field stalls
     * turn out to come exclusively from H1.1/H2 connections, the fix
     * shape changes — HTTP/3 negotiation may be the cheap fix.
     *
     * Production callers emit this field into trace lines ONLY when the
     * client-side T2 diag gate is on (see [PreKeyApiClient]'s
     * `t2DiagPublishTraceEnabled` flag).
     */
    val protocol: String? = null,
)
