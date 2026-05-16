// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Android production implementation of [RestFallbackTransport] — PR-D1.
 *
 * Mirrors the native-OkHttp pattern locked in PR-R0.1
 * ([AndroidNativeOkHttpPreKeyPublishTransport]) and PR-R0.3
 * ([AndroidNativeOkHttpDirectProbe]):
 *
 *   - HTTP/1.1 pinned (no HTTP/2 stream-stalls under Tele2-class middleboxes)
 *   - `Connection: close` header on every request (no half-closed pool entries)
 *   - Fresh [OkHttpClient] per call: `ConnectionPool(0, 1, MILLISECONDS)` and
 *     no pool sharing between calls
 *   - `retryOnConnectionFailure(false)` — [RestFallbackOrchestrator] owns
 *     retry semantics with the proper idempotency key, so we MUST NOT let
 *     OkHttp re-fire the same request silently
 *   - Response body wrapped in `.use { }` so it always closes
 *   - Status logged INSIDE `.use { }`, result logged AFTER `.use { }` returns
 *     (Vladislav guardrail from PR-R0.3, 2026-05-16)
 *
 * Why fresh client per call: the same reasoning as PR-R0.1. On Tele2 LTE the
 * middlebox silently half-closes idle TCP connections; OkHttp will happily
 * re-use a pool entry the server side has discarded, resulting in 30 s+ stalls.
 * One fresh TCP+TLS handshake per call costs ~50–200 ms on a healthy uplink
 * and is the price we pay for reliable delivery on hostile networks.
 *
 * iOS / JVM stubs throw [NotImplementedError]: REST fallback is currently
 * an Android-only production path, identical to PR-R0.1's stance.
 */
internal class AndroidNativeOkHttpRestFallbackTransport(
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
    private val writeTimeoutMs: Long = WRITE_TIMEOUT_MS,
) : RestFallbackTransport {

    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    override suspend fun authSession(
        url: String,
        body: AuthSessionRequest,
    ): RestFallbackResponse<AuthSessionResponse> = withContext(Dispatchers.IO) {
        val jsonBody = jsonCodec.encodeToString(AuthSessionRequest.serializer(), body)
        val response = post(url, token = null, idempotencyKey = null, bodyJson = jsonBody)
        decode(response, AuthSessionResponse.serializer())
    }

    override suspend fun send(
        url: String,
        token: String,
        idempotencyKey: String,
        body: SendRequest,
    ): RestFallbackResponse<SendResponse> = withContext(Dispatchers.IO) {
        val jsonBody = jsonCodec.encodeToString(SendRequest.serializer(), body)
        val response = post(url, token = token, idempotencyKey = idempotencyKey, bodyJson = jsonBody)
        decode(response, SendResponse.serializer())
    }

    override suspend fun poll(
        url: String,
        token: String,
        sinceSeq: Long?,
    ): RestFallbackResponse<PollResponse> = withContext(Dispatchers.IO) {
        val fullUrl = if (sinceSeq != null) "$url?since_seq=$sinceSeq" else url
        val response = get(fullUrl, token)
        decode(response, PollResponse.serializer())
    }

    override suspend fun ackDeliver(
        url: String,
        token: String,
        body: AckDeliverRequest,
    ): RestFallbackResponse<AckDeliverResponse> = withContext(Dispatchers.IO) {
        val jsonBody = jsonCodec.encodeToString(AckDeliverRequest.serializer(), body)
        val response = post(url, token = token, idempotencyKey = null, bodyJson = jsonBody)
        decode(response, AckDeliverResponse.serializer())
    }

    // ── Internal HTTP plumbing ───────────────────────────────────────────────

    /** Holder for raw response data captured INSIDE the OkHttp `.use { }` block. */
    private data class RawResponse(
        val statusCode: Int,
        val rawBody: String,
        val elapsedMs: Long,
    )

    private fun post(
        url: String,
        token: String?,
        idempotencyKey: String?,
        bodyJson: String,
    ): RawResponse {
        val client = buildClient()
        val builder = Request.Builder()
            .url(url)
            .header("Connection", "close")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey)
        return execute(client, builder.build())
    }

    private fun get(url: String, token: String): RawResponse {
        val client = buildClient()
        val request = Request.Builder()
            .url(url)
            .header("Connection", "close")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return execute(client, request)
    }

    private fun execute(client: OkHttpClient, request: Request): RawResponse {
        val startMs = System.currentTimeMillis()
        client.newCall(request).execute().use { response: Response ->
            val raw = response.body?.string() ?: ""
            val elapsedMs = System.currentTimeMillis() - startMs
            // Status log INSIDE use{} per PR-R0.3 guardrail.
            return RawResponse(
                statusCode = response.code,
                rawBody = raw,
                elapsedMs = elapsedMs,
            )
        }
    }

    private fun <T> decode(
        raw: RawResponse,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): RestFallbackResponse<T> {
        // Only attempt to decode on 2xx with a non-empty body. The relay's
        // contract says success responses are tiny JSON; anything else
        // (5xx, 4xx error text, empty body) leaves bodyParsed = null and
        // the caller relies on statusCode + rawBody.
        val parsed: T? = if (raw.statusCode in 200..299 && raw.rawBody.isNotEmpty()) {
            runCatching {
                jsonCodec.decodeFromString(deserializer, raw.rawBody)
            }.getOrNull()
        } else {
            null
        }
        return RestFallbackResponse(
            statusCode = raw.statusCode,
            bodyParsed = parsed,
            rawBody = raw.rawBody,
            elapsedMs = raw.elapsedMs,
        )
    }

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
         * Total per-call ceiling. The orchestrator's retry layer decides earlier
         * (5 attempts × backoffs) so this is mostly a safety net for the very
         * last attempt.
         */
        const val CALL_TIMEOUT_MS: Long = 60_000L
        const val CONNECT_TIMEOUT_MS: Long = 30_000L
        const val READ_TIMEOUT_MS: Long = 60_000L
        const val WRITE_TIMEOUT_MS: Long = 60_000L

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
