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
)
