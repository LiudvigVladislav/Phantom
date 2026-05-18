// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Android production implementation of [MediaUploadTransport] — PR-M1w R4.
 *
 * Mirrors the native-OkHttp pattern locked in PR-R0.1
 * ([AndroidNativeOkHttpPreKeyPublishTransport]) and PR-R0.3
 * ([AndroidNativeOkHttpDirectProbe]):
 *
 *   - HTTP/1.1 pinned (no HTTP/2 stream-stalls under Tele2-class middleboxes)
 *   - `Connection: close` header on every request (no half-closed pool entries)
 *   - `Cache-Control: no-store` on GET (prevents middlebox caching of chunks)
 *   - Fresh [OkHttpClient] per call: `ConnectionPool(0, 1, MILLISECONDS)` and
 *     no pool sharing between calls
 *   - `retryOnConnectionFailure(false)` — [VoiceV2Sender]'s 5-attempt backoff
 *     loop owns retry semantics; OkHttp must not silently re-fire
 *   - Response body wrapped in `.use { }` — status captured INSIDE the block
 *   - Network/IO exceptions wrapped as [MediaTransportException] so the
 *     VoiceV2Sender retry loop fires (same fix applied to KtorMediaUploadTransport
 *     in cc456a36; must not regress on the native path)
 *   - callTimeout 10s: per-call ceiling that lets 5 retry attempts complete
 *     cleanly within the VoiceV2Sender backoff series (1s/3s/8s/20s/60s)
 *
 * Why fresh client per call: relay logs (Test #58, mediaId CCIUsQKw) showed
 * the relay serving chunks in milliseconds while Android's GET-to-GET interval
 * was ~31 s — matching Ktor/OkHttp HTTP/2 + persistent-connection misbehaviour
 * under Tele2 middleboxes (the same class of issue PR-R0.1/R0.3 already fixed
 * for other endpoints). One fresh TCP+TLS per chunk costs ~50–200 ms on a
 * healthy uplink and is the correct trade-off for reliable delivery.
 *
 * [log] is a `(String) -> Unit` constructor parameter (same convention as
 * [AndroidNativeOkHttpRestFallbackTransport] and every other native transport).
 * In production AppContainer binds `{ msg -> android.util.Log.i("PhantomMedia", msg) }`.
 */
@OptIn(ExperimentalEncodingApi::class)
class AndroidNativeOkHttpMediaUploadTransport(
    private val relayBaseUrl: String,
    private val log: (String) -> Unit,
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
    private val writeTimeoutMs: Long = WRITE_TIMEOUT_MS,
) : MediaUploadTransport {

    // ── uploadChunk ────────────────────────────────────────────────────────────

    override suspend fun uploadChunk(
        token: String,
        mediaId: String,
        idx: Int,
        total: Int,
        ciphertext: ByteArray,
    ): Result<MediaUploadTransport.UploadStatus> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        log("MEDIA_HTTP upload_start mediaId=${mediaId.take(8)} idx=$idx")
        runCatching {
            val ciphertextB64 = Base64.encode(ciphertext)
            val bodyJson = buildUploadBody(mediaId, idx, total, ciphertextB64)
            val url = "$relayBaseUrl${MediaRelayEndpoints.UPLOAD_CHUNK}"
            val client = buildClient()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Connection", "close")
                // PR-M2d.1: opt into 204 + headers success path. Removes
                // response body from the upload roundtrip — critical on Tele2
                // LTE where the relay's 201/JSON response body is dropped on
                // the way back (verified by Test #66.2 where status=201
                // surfaced alongside InterruptedIOException at 15-sec
                // readTimeout). Relay falls back to legacy 201/JSON if it
                // does not understand the Prefer header.
                .header("Prefer", "return=minimal")
                .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            var statusCode: Int
            var headersOnlyElapsedMs = 0L
            var bodyReadError: Throwable? = null
            var rawBody = ""
            var duplicateHeader = false
            client.newCall(request).execute().use { response: Response ->
                statusCode = response.code
                headersOnlyElapsedMs = System.currentTimeMillis() - startMs
                duplicateHeader = response.headers["X-Chunk-Duplicate"] == "1"
                // PR-M2d.1: idempotency-aware body-read. Tele2 Layer B can
                // drop response bodies after a valid status line arrived.
                // We capture the status first; if status indicates the relay
                // accepted/stored the chunk, body-read failure is non-fatal.
                // For 204 (Prefer=minimal path) there is no body to read; for
                // 201/200 (legacy path) we drain it but tolerate timeout.
                rawBody = try {
                    response.body?.string() ?: ""
                } catch (e: Throwable) {
                    bodyReadError = e
                    ""
                }
            }
            val totalElapsedMs = System.currentTimeMillis() - startMs
            log(
                "MEDIA_HTTP upload_response mediaId=${mediaId.take(8)} idx=$idx " +
                    "status=$statusCode headersMs=$headersOnlyElapsedMs " +
                    "bodyMs=${totalElapsedMs - headersOnlyElapsedMs} totalMs=$totalElapsedMs" +
                    (bodyReadError?.let { " bodyReadError=${it::class.simpleName}" } ?: "")
            )
            // 204 is the new minimal-success path. Body always empty by RFC.
            // Map duplicate vs stored from header instead of body.
            if (statusCode == 204) {
                return@runCatching if (duplicateHeader) {
                    Result.success(MediaUploadTransport.UploadStatus.DUPLICATE)
                } else {
                    Result.success(MediaUploadTransport.UploadStatus.STORED)
                }
            }
            // Idempotency-aware: 201/200 with body-read failure = relay
            // already stored the chunk (status line arrived). Don't retry —
            // a retry would just hit the same Layer B drop, costing another
            // 15-sec readTimeout for no benefit.
            if (bodyReadError != null && (statusCode == 201 || statusCode == 200)) {
                log(
                    "MEDIA_HTTP upload_response_body_dropped_ok mediaId=${mediaId.take(8)} " +
                        "idx=$idx status=$statusCode — relay-stored success despite body-read failure"
                )
                return@runCatching if (statusCode == 201) {
                    Result.success(MediaUploadTransport.UploadStatus.STORED)
                } else {
                    Result.success(MediaUploadTransport.UploadStatus.DUPLICATE)
                }
            }
            mapUploadStatus(statusCode, rawBody)
        }.getOrElse { e ->
            val elapsed = System.currentTimeMillis() - startMs
            log("MEDIA_HTTP upload_fail mediaId=${mediaId.take(8)} idx=$idx error=${e::class.simpleName} elapsedMs=$elapsed")
            Result.failure(
                MediaTransportException(
                    "upload-chunk network ${e::class.simpleName}: ${e.message?.take(120) ?: ""}"
                )
            )
        }
    }

    // ── downloadChunk ──────────────────────────────────────────────────────────

    override suspend fun downloadChunk(
        token: String,
        mediaId: String,
        idx: Int,
    ): Result<MediaUploadTransport.DownloadResult> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        log("MEDIA_HTTP download_start mediaId=${mediaId.take(8)} idx=$idx")
        runCatching {
            val url = "$relayBaseUrl${MediaRelayEndpoints.DOWNLOAD_CHUNK_PREFIX}/$mediaId/$idx"
            val client = buildClient()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Connection", "close")
                .header("Cache-Control", "no-store")
                .get()
                .build()
            var statusCode: Int
            var headersOnlyElapsedMs = 0L
            val rawBody: String
            client.newCall(request).execute().use { response: Response ->
                statusCode = response.code
                headersOnlyElapsedMs = System.currentTimeMillis() - startMs
                rawBody = response.body?.string() ?: ""
            }
            val totalElapsedMs = System.currentTimeMillis() - startMs
            log(
                "MEDIA_HTTP download_response mediaId=${mediaId.take(8)} idx=$idx " +
                    "status=$statusCode headersMs=$headersOnlyElapsedMs " +
                    "bodyMs=${totalElapsedMs - headersOnlyElapsedMs} totalMs=$totalElapsedMs"
            )
            mapDownloadResult(statusCode, rawBody)
        }.getOrElse { e ->
            val elapsed = System.currentTimeMillis() - startMs
            log("MEDIA_HTTP download_fail mediaId=${mediaId.take(8)} idx=$idx error=${e::class.simpleName} elapsedMs=$elapsed")
            Result.failure(
                MediaTransportException(
                    "download-chunk network ${e::class.simpleName}: ${e.message?.take(120) ?: ""}"
                )
            )
        }
    }

    // ── Response mappers ───────────────────────────────────────────────────────

    private fun mapUploadStatus(
        statusCode: Int,
        rawBody: String,
    ): Result<MediaUploadTransport.UploadStatus> = when (statusCode) {
        201 -> Result.success(MediaUploadTransport.UploadStatus.STORED)
        200 -> Result.success(MediaUploadTransport.UploadStatus.DUPLICATE)
        409 -> {
            val reason = extractError(rawBody)
            Result.failure(MediaConflictException(reason))
        }
        413 -> {
            val reason = extractError(rawBody)
            Result.failure(MediaQuotaException(reason))
        }
        400 -> Result.failure(IllegalArgumentException("upload-chunk 400: $rawBody"))
        401 -> Result.failure(MediaAuthException("media_auth_401"))
        else -> Result.failure(
            MediaTransportException("upload-chunk unexpected status=$statusCode: $rawBody")
        )
    }

    private fun mapDownloadResult(
        statusCode: Int,
        rawBody: String,
    ): Result<MediaUploadTransport.DownloadResult> = when (statusCode) {
        200 -> {
            val ciphertextB64 = extractCiphertextB64(rawBody)
            val total = extractTotal(rawBody)
            val ciphertext = Base64.decode(ciphertextB64)
            Result.success(
                MediaUploadTransport.DownloadResult(
                    ciphertext = ciphertext,
                    total = total,
                )
            )
        }
        404 -> Result.failure(NotFoundException)
        401 -> Result.failure(MediaAuthException("media_auth_401"))
        else -> Result.failure(
            MediaTransportException("download-chunk unexpected status=$statusCode: $rawBody")
        )
    }

    // ── JSON helpers (minimal — no full serialization framework in androidMain) ─

    /** Builds the JSON body for POST /media/upload-chunk without kotlinx.serialization. */
    private fun buildUploadBody(
        mediaId: String,
        idx: Int,
        total: Int,
        ciphertextB64: String,
    ): String = buildString {
        append("{")
        append("\"media_id\":\"").append(mediaId.replace("\"", "\\\"")).append("\",")
        append("\"idx\":").append(idx).append(",")
        append("\"total\":").append(total).append(",")
        append("\"ciphertext_b64\":\"").append(ciphertextB64).append("\"")
        append("}")
    }

    /**
     * Extracts the "ciphertext_b64" string value from a minimal relay JSON body.
     * Example: {"ciphertext_b64":"<base64>","total":25}
     */
    private fun extractCiphertextB64(rawBody: String): String {
        val key = "\"ciphertext_b64\":\""
        val start = rawBody.indexOf(key)
        require(start >= 0) { "download-chunk: missing ciphertext_b64 in body: $rawBody" }
        val valueStart = start + key.length
        val valueEnd = rawBody.indexOf('"', valueStart)
        require(valueEnd > valueStart) { "download-chunk: malformed ciphertext_b64 in body: $rawBody" }
        return rawBody.substring(valueStart, valueEnd)
    }

    /**
     * Extracts the "total" integer value from a minimal relay JSON body.
     * Example: {"ciphertext_b64":"...","total":25}
     */
    private fun extractTotal(rawBody: String): Int {
        val key = "\"total\":"
        val start = rawBody.indexOf(key)
        require(start >= 0) { "download-chunk: missing total in body: $rawBody" }
        val valueStart = start + key.length
        val valueEnd = rawBody.indexOfFirst(valueStart) { it == ',' || it == '}' }
        require(valueEnd > valueStart) { "download-chunk: malformed total in body: $rawBody" }
        return rawBody.substring(valueStart, valueEnd).trim().toInt()
    }

    /** Extracts the "error" string from a relay error JSON body, falls back to raw. */
    private fun extractError(rawBody: String): String {
        val key = "\"error\":\""
        val start = rawBody.indexOf(key)
        if (start < 0) return rawBody
        val valueStart = start + key.length
        val valueEnd = rawBody.indexOf('"', valueStart)
        if (valueEnd <= valueStart) return rawBody
        return rawBody.substring(valueStart, valueEnd)
    }

    // ── OkHttp client factory ─────────────────────────────────────────────────

    private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .retryOnConnectionFailure(false)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    companion object {
        /**
         * Per-call ceiling: 10 s lets VoiceV2Sender's 5-attempt retry series
         * (backoffs 1s/3s/8s/20s/60s) fire cleanly without the client-level
         * timeout racing the application-level retry.
         */
        const val CALL_TIMEOUT_MS: Long    = 10_000L
        const val CONNECT_TIMEOUT_MS: Long = 10_000L
        const val READ_TIMEOUT_MS: Long    = 10_000L
        const val WRITE_TIMEOUT_MS: Long   = 10_000L

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

// ── Extension helper ───────────────────────────────────────────────────────────

/**
 * Finds the first index in [this] string starting at [fromIndex] where [predicate]
 * is true. Returns -1 if not found. Used by [AndroidNativeOkHttpMediaUploadTransport]
 * to parse minimal relay JSON without a full JSON library.
 */
private fun String.indexOfFirst(fromIndex: Int, predicate: (Char) -> Boolean): Int {
    for (i in fromIndex until length) {
        if (predicate(this[i])) return i
    }
    return -1
}
