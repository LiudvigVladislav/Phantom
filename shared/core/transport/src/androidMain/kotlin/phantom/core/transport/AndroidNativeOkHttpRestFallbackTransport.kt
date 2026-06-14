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
import java.net.InetSocketAddress
import java.net.Proxy
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
    /**
     * Trek 2 Stage 2A (A4) — local TCP port on which a SOCKS5 proxy
     * listens for this transport's outbound calls. When `null` (the
     * Stage 2 Standard mode default), every call uses direct TCP.
     * When non-null, each fresh OkHttp client built by [buildClient]
     * is wired to `Proxy.Type.SOCKS @ 127.0.0.1:<socksProxyPort>` —
     * the same one-port-per-transport pattern the future Reality
     * (Stage 3) and Tor (Stage 4) wire-ups will use.
     *
     * Stored as an immutable constructor field so the same transport
     * instance always goes through the same proxy throughout its
     * lifetime; the orchestrator caches one transport per app run.
     */
    private val socksProxyPort: Int? = null,
    /**
     * Round 12 step 2 — debug-only diagnostic toggle for the per-chunk
     * response-body byte accounting interceptor
     * ([DebugBodyByteLoggingInterceptor]). When `false` (the release
     * default and the production default), the interceptor is never
     * constructed and the OkHttp pipeline runs identically to its
     * pre-Round-12 shape. When `true`, the interceptor is added to
     * the OkHttp client for `op == "poll"` calls only — auth/session,
     * send, and ack/deliver paths are not instrumented because the
     * S6 incident pinpointed the poll body-read as the discriminator
     * surface, and instrumenting auth/send would pollute the
     * diagnostic signal with shapes the council did not request.
     *
     * Wired by [phantom.android.di.AppContainer] from
     * `BuildConfig.DEBUG`. The release-variant `BuildConfig.DEBUG`
     * is always `false`, so the production constructor receives
     * `debugBodyLogging = false` at the application-module
     * boundary.
     */
    private val debugBodyLogging: Boolean = false,
    /**
     * Round 13 — debug-only gate for the OkHttp
     * [HttpPhaseEventListener] attached to every REST call. The
     * listener emits `dnsEnd addresses=[...]` and
     * `connectStart host=...` lines to Android logcat under the
     * `PhantomHybrid` tag; on a release build those lines expose
     * the resolved relay IP address on every poll/send/auth/ack
     * call, which violates the Ghost privacy-mode contract that
     * keeps the relay endpoint off-device.
     *
     * Wired by [phantom.android.di.AppContainer] from
     * `BuildConfig.DEBUG`. The release-variant `BuildConfig.DEBUG`
     * is always `false`, so the production constructor receives
     * `httpPhaseLogging = false` and the listener is never attached.
     * Mirrors the gating discipline of [debugBodyLogging].
     */
    private val httpPhaseLogging: Boolean = false,
    /**
     * Round 12 step 3 — diagnostic provider that, when it returns
     * `true`, causes [buildPollRequest] to drop BOTH the
     * `X-Phantom-Long-Poll` and the `X-Phantom-Padded-Poll` opt-in
     * headers atomically. Read at request-build time on each
     * `poll(...)` call so a PrivacyMode switch mid-session is
     * observed promptly.
     *
     * Default `{ false }` preserves byte-identical wire shape for
     * every existing call site. Production wiring (Android,
     * `AppContainer`) injects
     * `{ BuildConfig.DEBUG && BuildConfig.POLL_SKIP_LP_AND_PP == "1"
     * && PrivacyMode == Standard }`; all three conjuncts MUST hold
     * for the strip to fire.
     */
    private val pollSkipLpAndPpProvider: () -> Boolean = { false },
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
        val response = post(
            url = url,
            token = null,
            idempotencyKey = null,
            bodyJson = jsonBody,
            op = "session",
        )
        decode(response, AuthSessionResponse.serializer())
    }

    override suspend fun send(
        url: String,
        token: String,
        idempotencyKey: String,
        body: SendRequest,
    ): RestFallbackResponse<SendResponse> = withContext(Dispatchers.IO) {
        val jsonBody = jsonCodec.encodeToString(SendRequest.serializer(), body)
        val response = post(
            url = url,
            token = token,
            idempotencyKey = idempotencyKey,
            bodyJson = jsonBody,
            op = "send",
        )
        decode(response, SendResponse.serializer())
    }

    override suspend fun poll(
        url: String,
        token: String,
        sinceSeq: Long?,
        longPollOptIn: Boolean,
        readTimeoutMs: Long?,
    ): RestFallbackResponse<PollResponse> = withContext(Dispatchers.IO) {
        val fullUrl = if (sinceSeq != null) "$url?since_seq=$sinceSeq" else url
        // Round 12 step 3 — evaluate the provider once per poll
        // iteration so a runtime PrivacyMode change is reflected on
        // the very next request (Standard → Ghost atomically turns
        // the diagnostic strip off again).
        val skipLpAndPp = pollSkipLpAndPpProvider()
        val response = get(
            url = fullUrl,
            token = token,
            op = "poll",
            longPollOptIn = longPollOptIn,
            pollSkipLpAndPp = skipLpAndPp,
            readTimeoutOverrideMs = readTimeoutMs,
        )
        decode(response, PollResponse.serializer())
    }

    override suspend fun ackDeliver(
        url: String,
        token: String,
        body: AckDeliverRequest,
    ): RestFallbackResponse<AckDeliverResponse> = withContext(Dispatchers.IO) {
        val jsonBody = jsonCodec.encodeToString(AckDeliverRequest.serializer(), body)
        val response = post(
            url = url,
            token = token,
            idempotencyKey = null,
            bodyJson = jsonBody,
            op = "ack",
        )
        decode(response, AckDeliverResponse.serializer())
    }

    // ── Internal HTTP plumbing ───────────────────────────────────────────────

    /** Holder for raw response data captured INSIDE the OkHttp `.use { }` block. */
    private data class RawResponse(
        val statusCode: Int,
        val rawBody: String,
        val elapsedMs: Long,
        /**
         * Trek 2 Stage 2B-B (C5, L8 + M-B24) — parsed `Retry-After`
         * header value in seconds. `null` when absent / malformed
         * (HTTP-date form, non-numeric, empty, zero, negative — all
         * normalised to `null` by
         * [RestFallbackOrchestrator.parseRetryAfterHeader]). The
         * orchestrator clamps to `RETRY_AFTER_HARD_CAP_SECONDS = 120`
         * before multiplying by 1_000L (see
         * [RestFallbackOrchestrator.clampRetryAfterMs]).
         */
        val retryAfterSeconds: Long? = null,
    )

    private fun post(
        url: String,
        token: String?,
        idempotencyKey: String?,
        bodyJson: String,
        op: String,
    ): RawResponse {
        val client = buildClient(op = op, correlationKey = idempotencyKey ?: url)
        val builder = Request.Builder()
            .url(url)
            .header("Connection", "close")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey)
        return execute(client, builder.build())
    }

    private fun get(
        url: String,
        token: String,
        op: String,
        longPollOptIn: Boolean = false,
        pollSkipLpAndPp: Boolean = false,
        readTimeoutOverrideMs: Long? = null,
    ): RawResponse {
        val client = buildClient(
            op = op,
            correlationKey = url,
            readTimeoutOverrideMs = readTimeoutOverrideMs,
        )
        val request = buildPollRequest(
            url = url,
            token = token,
            longPollOptIn = longPollOptIn,
            pollSkipLpAndPp = pollSkipLpAndPp,
        )
        return execute(client, request)
    }

    private fun execute(client: OkHttpClient, request: Request): RawResponse {
        val startMs = System.currentTimeMillis()
        client.newCall(request).execute().use { response: Response ->
            val raw = response.body?.string() ?: ""
            val elapsedMs = System.currentTimeMillis() - startMs
            // Trek 2 Stage 2B-B (C5, L8) — parse `Retry-After` INSIDE
            // the `.use { }` block. OkHttp's `Response.header(name)`
            // returns the value verbatim; the parse normalises
            // malformed inputs to `null`. The orchestrator clamps
            // the typed value to `RETRY_AFTER_HARD_CAP_SECONDS = 120`
            // before multiplying by 1_000L (see
            // `RestFallbackOrchestrator.clampRetryAfterMs`). Reading
            // the header here is cheap and avoids surfacing the raw
            // OkHttp Response object outside the `.use { }` boundary.
            val retryAfterSeconds = RestFallbackOrchestrator.parseRetryAfterHeader(
                response.header("Retry-After"),
            )
            // Status log INSIDE use{} per PR-R0.3 guardrail.
            return RawResponse(
                statusCode = response.code,
                rawBody = raw,
                elapsedMs = elapsedMs,
                retryAfterSeconds = retryAfterSeconds,
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
            retryAfterSeconds = raw.retryAfterSeconds,
        )
    }

    private fun buildClient(
        op: String,
        correlationKey: String,
        readTimeoutOverrideMs: Long? = null,
    ): OkHttpClient {
        // Trek 2 Stage 2B-A (B2) — long-poll path needs BOTH read and
        // call ceilings lifted, since OkHttp's `callTimeout` is the
        // hard cap on the whole request. Lifting only `readTimeout`
        // would let the call die at `callTimeoutMs` (~10 s) before the
        // server's hold window completed. The override applies to
        // both budgets symmetrically — same value, so the call cannot
        // outlive the read budget but neither budget cuts the
        // negotiated hold time short.
        val effectiveReadMs = readTimeoutOverrideMs ?: readTimeoutMs
        val effectiveCallMs = if (readTimeoutOverrideMs != null) readTimeoutOverrideMs else callTimeoutMs
        return OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .retryOnConnectionFailure(false)
            .callTimeout(effectiveCallMs, TimeUnit.MILLISECONDS)
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(effectiveReadMs, TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
            .also { builder ->
                // Trek 2 Stage 2A (A4) — wire the SOCKS5 proxy iff the
                // transport was constructed with a port. Null = direct
                // TCP (Standard mode, the Stage 2 default), no proxy
                // overhead. The future Reality / Tor wire-ups (Stages
                // 3 / 4) pass a live local SOCKS port from the
                // transport-strategy layer; nothing in Stage 2A invokes
                // the non-null branch in production code paths.
                val port = socksProxyPort
                if (port != null) {
                    builder.proxy(
                        Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)),
                    )
                }
            }
            // PR-WS-HEALTH-STATE1 Commit 1 (2026-05-30): diagnostic-only
            // phase logging. Emits `REST_TRACE phase_event op=<op> key=<idem
            // or url> event=<dns|connect|secureConnect|responseHeaders|...>
            // elapsedMs=<n>` per OkHttp lifecycle event, under the existing
            // `PhantomHybrid` tag so the new lines sort with the existing
            // REST_TRACE send/poll/auth bodies. NO behaviour change; the
            // listener does string concatenation and Log.i only.
            //
            // Round 13 — gated on `httpPhaseLogging` (debug-build only;
            // wired by AppContainer from `BuildConfig.DEBUG`). The
            // `dnsEnd addresses=[...]` and `connectStart host=...` lines
            // emit the resolved relay IP, which on a release build would
            // expose the relay endpoint on every REST call and violate
            // the Ghost privacy-mode contract.
            .also { builder ->
                if (httpPhaseLogging) {
                    builder.eventListener(
                        HttpPhaseEventListener(
                            tag = "PhantomHybrid",
                            keyword = "REST_TRACE",
                            op = op,
                            correlationKey = correlationKey,
                        ),
                    )
                }
            }
            .also { builder ->
                // Round 12 step 2 — per-chunk response-body byte
                // accounting for the REST poll path. Gated on
                // `debugBodyLogging` (debug-build only) AND `op == "poll"`
                // (auth/send/ack are out of scope per the S6 council
                // narrow finding). The interceptor wraps the response
                // body's Source with a logging ForwardingSource;
                // `responseBodyStart` / `responseBodyEnd` events from
                // the EventListener still fire normally, and the new
                // `body_chunk` / `body_eof` lines compose with them to
                // discriminate the four failure-mode hypotheses.
                if (debugBodyLogging && op == "poll") {
                    builder.addInterceptor(
                        DebugBodyByteLoggingInterceptor(
                            tag = "PhantomHybrid",
                            keyword = "REST_TRACE",
                            op = op,
                            correlationKey = correlationKey,
                        ),
                    )
                }
            }
            .build()
    }

    companion object {
        /**
         * Per-call ceilings for the short relay paths (`/relay/send`,
         * `/relay/poll`, `/relay/ack-deliver`, `/auth/session`).
         *
         * PR-WS-HEALTH-STATE1 Commit 2 (2026-05-30): tightened from
         * `call=60s / connect=30s / read=60s / write=60s` to the values
         * below per the design note locked in
         * `docs/tracks/ws-health-state.md` § Implementation plan § Commit 2
         * design note (Vladislav-locked rev2). Empirical base
         * `C:\temp\test83-v4-tecno.log:679-:721` proved the 60 s wall was
         * OkHttp's `callTimeout(60s)` force-closing the socket on a
         * TLS handshake that never completes — entirely client-side, so
         * the budget can be tightened without server cooperation.
         *
         * The 10 s ceiling matches `AndroidNativeOkHttpMediaUploadTransport.kt`
         * `:673` (`CALL_TIMEOUT_MS = 10_000L`), which has run in production
         * since PR-M2 without trouble — i.e. it is the proven class of
         * budget for PHANTOM's networks, not a guess.
         *
         * Trek 2 Stage 2B-A (B2) — `CALL_TIMEOUT_MS` and `READ_TIMEOUT_MS`
         * both reference [RestFallbackOrchestrator.LEGACY_SHORT_POLL_TIMEOUT_MS]
         * so the legacy-floor invariant inside
         * [RestFallbackOrchestrator.computeLongPollReadTimeoutMs] cannot
         * drift away from the actual OkHttp default applied here. The
         * commonMain constant is the single source of truth; this
         * companion just routes it to the right OkHttp builder fields.
         */
        const val CALL_TIMEOUT_MS: Long = RestFallbackOrchestrator.LEGACY_SHORT_POLL_TIMEOUT_MS
        const val CONNECT_TIMEOUT_MS: Long = 5_000L
        const val READ_TIMEOUT_MS: Long = RestFallbackOrchestrator.LEGACY_SHORT_POLL_TIMEOUT_MS
        const val WRITE_TIMEOUT_MS: Long = 10_000L

        /**
         * Trek 2 Stage 2B-A (B1) — pure builder for the `/relay/poll` GET
         * request. Pulled out of [get] so the header-emission contract
         * can be asserted at the OkHttp `Request` level without standing
         * up an `OkHttpClient`, an event loop, or a mock server.
         * `internal` so a Kotlin test in the same module's test source
         * set can call it directly.
         *
         * Coupling [LONG_POLL_OPT_IN_HEADER] and [PADDED_POLL_OPT_IN_HEADER]
         * inside a single `if (longPollOptIn)` block is the structural
         * enforcement of scope lock L1: a future caller cannot accidentally
         * emit one header without the other.
         *
         * Round 12 step 3 — the [pollSkipLpAndPp] diagnostic toggle
         * skips BOTH headers atomically when `true`, preserving the
         * L1 coupling by structural form. A partial strip (PP only)
         * was explicitly rejected by the d395f682 council; the
         * `&& !pollSkipLpAndPp` gate keeps the emission decision a
         * single boolean — both headers go out together or neither
         * does. The diagnostic toggle's gating chain (BuildConfig +
         * PrivacyMode == Standard) lives at the AppContainer wiring
         * layer, not here; this function trusts its caller to have
         * already applied those checks.
         */
        internal fun buildPollRequest(
            url: String,
            token: String,
            longPollOptIn: Boolean,
            pollSkipLpAndPp: Boolean = false,
        ): Request {
            val builder = Request.Builder()
                .url(url)
                .header("Connection", "close")
                .header("Authorization", "Bearer $token")
            if (longPollOptIn && !pollSkipLpAndPp) {
                builder.header(LONG_POLL_OPT_IN_HEADER, "1")
                builder.header(PADDED_POLL_OPT_IN_HEADER, "1")
            }
            return builder.get().build()
        }

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
