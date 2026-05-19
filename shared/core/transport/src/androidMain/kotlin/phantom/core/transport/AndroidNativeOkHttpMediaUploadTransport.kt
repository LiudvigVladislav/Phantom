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
    /**
     * PR-M2f — runtime capability probe. The orchestrator updates
     * [RestFallbackOrchestrator.capabilities] after each `/auth/session`
     * refresh; we read it on every call so the transport stays in sync
     * without an explicit setter. Lambda returns `false` until a session
     * has been obtained (safe default — stay on v2).
     */
    private val binaryV3Enabled: () -> Boolean = { false },
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
    private val writeTimeoutMs: Long = WRITE_TIMEOUT_MS,
) : MediaUploadTransport {

    /**
     * PR-M2f — sticky runtime fallback. If a v3 call returns 404/405 (relay
     * announced v3 in `/auth/session` but the actual route is missing —
     * e.g. mid-redeploy), we drop to v2 for the remainder of this process
     * lifetime. Reset only by reinstantiation; this is intentional to
     * avoid oscillating between paths under a broken capability advert.
     */
    @Volatile
    private var v3DisabledByFallback: Boolean = false

    private fun shouldUseV3(): Boolean = binaryV3Enabled() && !v3DisabledByFallback

    // ── uploadChunk ────────────────────────────────────────────────────────────

    override suspend fun uploadChunk(
        token: String,
        mediaId: String,
        idx: Int,
        total: Int,
        ciphertext: ByteArray,
    ): Result<MediaUploadTransport.UploadStatus> = withContext(Dispatchers.IO) {
        if (shouldUseV3()) {
            val v3 = uploadChunkV3(token, mediaId, idx, total, ciphertext)
            if (v3 != null) return@withContext v3
            // null sentinel = sticky-fallback path requested. v3DisabledByFallback
            // was set inside uploadChunkV3 before the early-return.
        }
        uploadChunkV2(token, mediaId, idx, total, ciphertext)
    }

    /**
     * PR-M2f — POST /media/v3/{mediaId}/{idx}?total=N with raw ciphertext body.
     * Returns `null` if the relay returns 404/405 (capability stale → fallback
     * to v2). Other terminal failures propagate via [Result.failure].
     */
    private suspend fun uploadChunkV3(
        token: String,
        mediaId: String,
        idx: Int,
        total: Int,
        ciphertext: ByteArray,
    ): Result<MediaUploadTransport.UploadStatus>? {
        val startMs = System.currentTimeMillis()
        log("MEDIA_V3 upload_start mediaId=${mediaId.take(8)} idx=$idx bytes=${ciphertext.size}")
        return runCatching {
            val url = "$relayBaseUrl/media/v3/$mediaId/$idx?total=$total"
            val client = buildClient()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Connection", "close")
                .post(ciphertext.toRequestBody(OCTET_STREAM_MEDIA_TYPE))
                .build()
            var statusCode: Int
            var headersOnlyElapsedMs = 0L
            var duplicateHeader = false
            client.newCall(request).execute().use { response: Response ->
                statusCode = response.code
                headersOnlyElapsedMs = System.currentTimeMillis() - startMs
                duplicateHeader = response.headers["X-Chunk-Duplicate"] == "1"
                // 204 is body-less by RFC. Drain anyway to free the connection.
                try { response.body?.bytes() } catch (_: Throwable) {}
            }
            val totalElapsedMs = System.currentTimeMillis() - startMs
            log(
                "MEDIA_V3 upload_response mediaId=${mediaId.take(8)} idx=$idx " +
                    "status=$statusCode headersMs=$headersOnlyElapsedMs totalMs=$totalElapsedMs"
            )
            when (statusCode) {
                204 -> if (duplicateHeader) {
                    Result.success(MediaUploadTransport.UploadStatus.DUPLICATE)
                } else {
                    Result.success(MediaUploadTransport.UploadStatus.STORED)
                }
                404, 405 -> {
                    // Capability advertised but route absent — sticky-disable v3.
                    v3DisabledByFallback = true
                    log(
                        "MEDIA_V3 fallback reason=not_supported status=$statusCode " +
                            "mediaId=${mediaId.take(8)} idx=$idx"
                    )
                    null // sentinel: caller falls back to v2
                }
                409 -> Result.failure(MediaConflictException("ciphertext_mismatch"))
                413 -> Result.failure(MediaQuotaException("body_too_large_or_quota"))
                401 -> Result.failure(MediaAuthException("media_auth_401"))
                else -> Result.failure(
                    MediaTransportException("upload-v3 unexpected status=$statusCode")
                )
            }
        }.getOrElse { e ->
            val elapsed = System.currentTimeMillis() - startMs
            log(
                "MEDIA_V3 upload_fail mediaId=${mediaId.take(8)} idx=$idx " +
                    "error=${e::class.simpleName} elapsedMs=$elapsed"
            )
            // Network errors are NOT a capability-stale signal — surface them
            // through the same retry loop the v2 path uses. Do not flip the
            // sticky fallback here.
            Result.failure(
                MediaTransportException(
                    "upload-v3 network ${e::class.simpleName}: ${e.message?.take(120) ?: ""}"
                )
            )
        }
    }

    private suspend fun uploadChunkV2(
        token: String,
        mediaId: String,
        idx: Int,
        total: Int,
        ciphertext: ByteArray,
    ): Result<MediaUploadTransport.UploadStatus> {
        val startMs = System.currentTimeMillis()
        log("MEDIA_HTTP upload_start mediaId=${mediaId.take(8)} idx=$idx")
        return runCatching {
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
        if (shouldUseV3()) {
            val v3 = downloadChunkV3(token, mediaId, idx)
            if (v3 != null) return@withContext v3
        }
        downloadChunkV2(token, mediaId, idx)
    }

    /**
     * PR-M2f — GET /media/v3/{mediaId}/{idx}. Returns body as raw octet-stream;
     * no JSON parsing, no Base64 decode. Returns `null` if relay says 404/405
     * via the "capability stale" path (sticky-disable v3 + caller falls back
     * to v2). A genuine 404 (the chunk truly does not exist) maps to
     * [NotFoundException] like the v2 path, NOT to the sticky-disable sentinel.
     *
     * Distinguishing the two 404 cases: the relay only returns 404 from v3
     * when (media_id, idx) is missing in the in-memory store. A "route is
     * absent" 404 cannot occur if we routed to /media/v3 — axum returns a
     * different status (typically 404 too, but with a default body) only
     * when the route doesn't exist at all. In practice we treat 405
     * (Method Not Allowed) as the unambiguous "route exists, method
     * unsupported" capability-stale signal; a 404 stays semantic
     * `chunk not found` and is handled by the existing
     * [NotFoundException] path. This matches the locked Vladislav
     * decision 2026-05-19: "v3 unexpectedly returns 404/405 → fallback".
     */
    private suspend fun downloadChunkV3(
        token: String,
        mediaId: String,
        idx: Int,
    ): Result<MediaUploadTransport.DownloadResult>? {
        val startMs = System.currentTimeMillis()
        log("MEDIA_V3 download_start mediaId=${mediaId.take(8)} idx=$idx")
        return runCatching {
            val url = "$relayBaseUrl/media/v3/$mediaId/$idx"
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
            var totalHeader: String? = null
            var ciphertext: ByteArray = ByteArray(0)
            client.newCall(request).execute().use { response: Response ->
                statusCode = response.code
                headersOnlyElapsedMs = System.currentTimeMillis() - startMs
                totalHeader = response.headers["X-Chunk-Total"]
                if (statusCode == 200) {
                    ciphertext = response.body?.bytes() ?: ByteArray(0)
                } else {
                    try { response.body?.bytes() } catch (_: Throwable) {}
                }
            }
            val totalElapsedMs = System.currentTimeMillis() - startMs
            log(
                "MEDIA_V3 download_response mediaId=${mediaId.take(8)} idx=$idx " +
                    "status=$statusCode bytes=${ciphertext.size} " +
                    "headersMs=$headersOnlyElapsedMs totalMs=$totalElapsedMs"
            )
            when (statusCode) {
                200 -> Result.success(
                    MediaUploadTransport.DownloadResult(
                        ciphertext = ciphertext,
                        total = totalHeader?.toIntOrNull() ?: 0,
                    )
                )
                405 -> {
                    v3DisabledByFallback = true
                    log(
                        "MEDIA_V3 fallback reason=method_not_allowed status=405 " +
                            "mediaId=${mediaId.take(8)} idx=$idx"
                    )
                    null
                }
                404 -> Result.failure(NotFoundException)
                401 -> Result.failure(MediaAuthException("media_auth_401"))
                else -> Result.failure(
                    MediaTransportException("download-v3 unexpected status=$statusCode")
                )
            }
        }.getOrElse { e ->
            val elapsed = System.currentTimeMillis() - startMs
            log(
                "MEDIA_V3 download_fail mediaId=${mediaId.take(8)} idx=$idx " +
                    "error=${e::class.simpleName} elapsedMs=$elapsed"
            )
            Result.failure(
                MediaTransportException(
                    "download-v3 network ${e::class.simpleName}: ${e.message?.take(120) ?: ""}"
                )
            )
        }
    }

    private suspend fun downloadChunkV2(
        token: String,
        mediaId: String,
        idx: Int,
    ): Result<MediaUploadTransport.DownloadResult> {
        val startMs = System.currentTimeMillis()
        log("MEDIA_HTTP download_start mediaId=${mediaId.take(8)} idx=$idx")
        return runCatching {
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

        // PR-M2f — raw ciphertext upload body type.
        private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()
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
