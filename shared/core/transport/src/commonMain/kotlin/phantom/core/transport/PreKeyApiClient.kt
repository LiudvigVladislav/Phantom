// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * HTTP client for the relay's `/prekeys` REST surface (publish, bundle,
 * status, opk-delete).
 *
 * Mirrors the wire types defined server-side in
 * `services/relay/src/prekeys.rs` (PR B + signing_pubkey_hex backport
 * from PR C commit 3). This client is used by:
 *
 *  - The lifecycle service (commit 13) for onboarding publish, weekly
 *    SPK rotation, and OPK pool refill at < 20 remaining.
 *  - The SessionManager initiator path (commit 10) to fetch a peer's
 *    [PreKeyBundle] before running X3DH 4-DH.
 *
 * The constructor takes an [HttpClient] so platform-specific configuration
 * (logging, retry, OkHttp interceptors) is injected from the AppContainer
 * rather than baked in here. The same client instance is reused across
 * the WebSocket transport and this REST client, matching the existing
 * pattern in [KtorRelayTransport].
 *
 * The `relayBaseUrl` is the http(s):// origin — the WebSocket origin is
 * ws(s):// and lives on the same Caddy/host pair, so callers typically
 * derive one from the other in the AppContainer wiring.
 */
/**
 * Surface that the prekey REST client exposes to its consumers
 * (lifecycle service, SessionManager bootstrap path via DMS). Extracted
 * as an interface in PR C commit 11 so DMS tests can swap in an
 * in-memory fake without spinning up an HttpClient + MockEngine.
 *
 * The HTTP-backed implementation is [PreKeyApiClient]; production code
 * wires a single instance via AppContainer.
 */
interface PreKeyApi {
    suspend fun publishBundle(request: PublishRequest): PublishResult
    suspend fun fetchBundle(
        identityPubkeyHex: String,
        requesterPubkeyHex: String? = null,
    ): PreKeyBundle?
    suspend fun fetchStatus(
        identityPubkeyHex: String,
        requesterPubkeyHex: String? = null,
    ): PreKeyStatus
}

class PreKeyApiClient(
    private val httpClient: HttpClient,
    private val relayBaseUrl: String,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
    /**
     * Native transport for POST /prekeys/publish. When non-null this
     * transport is used exclusively for the publish call; all other
     * endpoints keep using [httpClient].
     *
     * Production code passes [createPreKeyPublishHttpTransport()] here
     * (PR-R0.1). The native transport bypasses Ktor's body-streaming path
     * (which stalls at 8192 bytes on Android — Test #43, 2026-05-15) and
     * hands a pre-built ByteArray to OkHttp for a single-shot write.
     *
     * Null causes publishWithRetry() to throw — production code must always
     * supply a transport. Tests inject a fake implementation.
     *
     * @see createPreKeyPublishHttpTransport
     */
    private val publishTransport: PreKeyPublishHttpTransport? = null,
) : PreKeyApi {

    // One in-flight publish per PreKeyApiClient instance at a time.
    //
    // WHY: Caddy access logs from Tele2 LTE Test #42 showed two parallel
    // POSTs from the same identity at the exact same millisecond (ports
    // 16889 + 16890). Both arrived from a parallel reconnect generation
    // calling publishPreKeys() simultaneously. Even after the body-stuck
    // fix, a race wastes bandwidth and battery and can cause the relay to
    // see two concurrent publishes for the same identity.
    //
    // The Mutex serialises all publishBundle calls on this client instance.
    // AppContainer wires a single PreKeyApiClient per identity, so this is
    // effectively per-identity serialisation. If a second call arrives while
    // one is in-flight it is debounced (logged and returns immediately) rather
    // than queued — queueing a publish that will be superseded by the one
    // in-flight is pointless and burns extra bandwidth. PR-R0.
    // internal for testing only — do not access from production call sites.
    internal val publishMutex = Mutex()

    /**
     * Publish (or refresh) the local SignedPreKey + a batch of OneTimePreKeys.
     *
     * The batch fully replaces any previously-published OPK pool for this
     * identity (relay semantics, see ADR-009 + PR B). The client is
     * responsible for re-uploading any OPKs it still holds locally if it
     * wants them all to remain available — the lifecycle service already
     * does this on every publish.
     *
     * PR-R0 / PR-R0.1 additions:
     *  - PR-R0.1: uses [publishTransport] (native OkHttp, pre-built ByteArray)
     *    to bypass the Ktor body-streaming 8192-byte stall (Test #43).
     *  - Mutex-guarded: at most one in-flight publish per client instance.
     *    Duplicate concurrent calls are debounced (logged + skipped).
     *  - Retry with exponential backoff on transient failures: up to
     *    [PUBLISH_MAX_ATTEMPTS] total, delays [PUBLISH_RETRY_DELAYS_MS].
     *    Retried: SocketTimeoutException, IOException (connection reset /
     *    broken pipe), HTTP 408, HTTP 5xx. NOT retried: HTTP 400/401/403/422.
     *
     * @return [PublishResult.Stored] with the count of OPKs the relay
     *         retained (after dedup + cap), or [PublishResult.Failure]
     *         describing why the relay rejected the bundle.
     */
    override suspend fun publishBundle(request: PublishRequest): PublishResult {
        val identityTag = request.identity_pubkey_hex.take(16)

        // Debounce: if another publish is already running for this identity,
        // skip silently rather than queue. The in-flight publish will leave
        // the relay in the correct state.
        if (!publishMutex.tryLock()) {
            relayLog(
                RelayLogLevel.INFO,
                "PREKEY_TRACE prekey_publish_debounced identity=$identityTag…",
            )
            // Another publish is already in-flight for this identity. Skip
            // rather than queue — the in-flight call will leave the relay in
            // the correct state. verifyBundleOnRelay() on the next reconnect
            // catches any gap if the in-flight publish fails. Return a synthetic
            // Stored(0) so callers do not crash; the 0 just means "we did not
            // upload new OPKs in this call", which is accurate.
            return PublishResult.Stored(storedOpks = 0)
        }

        // Lock acquired via tryLock(). Run the work and unconditionally unlock
        // in the finally block — Mutex.tryLock() does NOT use the owner token
        // mechanism, so we must call unlock() directly (not withLock{}).
        return try {
            publishWithRetry(request, identityTag)
        } finally {
            publishMutex.unlock()
        }
    }

    private suspend fun publishWithRetry(
        request: PublishRequest,
        identityTag: String,
    ): PublishResult {
        val bodyJson = json.encodeToString(PublishRequest.serializer(), request)
        // Pre-build the byte array so OkHttp can write it in one shot.
        // This is the PR-R0.1 fix: Ktor streams the body through a ByteWriteChannel
        // that stalls at 8192 bytes on Android; native OkHttp writes the full
        // ByteArray via okio.Buffer.writeAll() without any streaming.
        val bodyBytes = bodyJson.encodeToByteArray()
        val url = "$relayBaseUrl/prekeys/publish"
        val totalStartMs = Clock.System.now().toEpochMilliseconds()
        var lastException: Throwable? = null

        val transport = publishTransport
            ?: error("PreKeyApiClient requires a publishTransport (PR-R0.1). " +
                "Pass createPreKeyPublishHttpTransport() from AppContainer.")

        for (attempt in 1..PUBLISH_MAX_ATTEMPTS) {
            if (bodyBytes.size >= 7800) {
                relayLog(
                    RelayLogLevel.WARN,
                    "PREKEY_TRACE prekey_publish_body_near_carrier_limit bodyBytes=${bodyBytes.size} threshold=7800 attempt=$attempt note=may_hit_tele2_8192_byte_cut",
                )
            }
            relayLog(
                RelayLogLevel.INFO,
                "PREKEY_TRACE prekey_publish_start native=true identity=$identityTag… " +
                    "opks=${request.one_time_pre_keys.size} " +
                    "spk_key_id=${request.signed_pre_key.key_id} " +
                    "bodyBytes=${bodyBytes.size} attempt=$attempt/${PUBLISH_MAX_ATTEMPTS}",
            )
            val attemptStartMs = Clock.System.now().toEpochMilliseconds()

            val nativeResp: PreKeyPublishHttpResponse = try {
                transport.publish(url, bodyBytes)
            } catch (t: Throwable) {
                val elapsed = Clock.System.now().toEpochMilliseconds() - attemptStartMs
                lastException = t
                if (attempt < PUBLISH_MAX_ATTEMPTS && t.isRetryable()) {
                    val nextDelay = PUBLISH_RETRY_DELAYS_MS[attempt - 1]
                    relayLog(
                        RelayLogLevel.WARN,
                        "PREKEY_TRACE prekey_publish_retry identity=$identityTag… " +
                            "reason=${t::class.simpleName} attempt=$attempt " +
                            "next_delay_ms=$nextDelay elapsedMs=$elapsed",
                        t,
                    )
                    delay(nextDelay)
                    continue
                } else {
                    val totalElapsed = Clock.System.now().toEpochMilliseconds() - totalStartMs
                    relayLog(
                        RelayLogLevel.WARN,
                        "PREKEY_TRACE prekey_publish_fail_giving_up identity=$identityTag… " +
                            "total_elapsedMs=$totalElapsed attempts=$attempt " +
                            "last_exception=${t::class.simpleName}: ${t.message ?: "<null>"}",
                        t,
                    )
                    throw t
                }
            }

            // We have a response — check if it warrants a retry.
            val elapsed = Clock.System.now().toEpochMilliseconds() - attemptStartMs
            val statusValue = nativeResp.statusCode

            // HTTP 408 or 5xx: server-side transient. Retry if budget remains.
            if ((statusValue == 408 || statusValue in 500..599) &&
                attempt < PUBLISH_MAX_ATTEMPTS
            ) {
                val nextDelay = PUBLISH_RETRY_DELAYS_MS[attempt - 1]
                relayLog(
                    RelayLogLevel.WARN,
                    "PREKEY_TRACE prekey_publish_retry identity=$identityTag… " +
                        "reason=http$statusValue attempt=$attempt " +
                        "next_delay_ms=$nextDelay elapsedMs=$elapsed",
                )
                delay(nextDelay)
                continue
            }

            // Non-retryable or final attempt — process the result.
            relayLog(
                RelayLogLevel.INFO,
                "PREKEY_TRACE prekey_publish_ok native=true identity=$identityTag… " +
                    "status=$statusValue elapsedMs=${nativeResp.elapsedMs} attempt=$attempt",
            )
            return when (statusValue) {
                201 -> {
                    val parsed = json.decodeFromString(
                        PublishResponse.serializer(),
                        nativeResp.bodyText,
                    )
                    PublishResult.Stored(parsed.storedOpks)
                }
                409 ->
                    PublishResult.Failure(
                        PublishResult.Reason.SigningKeyMismatch,
                        nativeResp.bodyText,
                    )
                429 ->
                    PublishResult.Failure(
                        PublishResult.Reason.RateLimited,
                        nativeResp.bodyText,
                    )
                400, 413 ->
                    PublishResult.Failure(
                        PublishResult.Reason.BadRequest,
                        nativeResp.bodyText,
                    )
                else ->
                    PublishResult.Failure(
                        PublishResult.Reason.Unexpected(statusValue),
                        nativeResp.bodyText,
                    )
            }
        }

        // Unreachable: the loop either returns or throws. Satisfy the compiler.
        val totalElapsed = Clock.System.now().toEpochMilliseconds() - totalStartMs
        val ex = lastException ?: IllegalStateException("publishWithRetry exhausted loop without result")
        relayLog(
            RelayLogLevel.WARN,
            "PREKEY_TRACE prekey_publish_fail_giving_up identity=$identityTag… " +
                "total_elapsedMs=$totalElapsed attempts=$PUBLISH_MAX_ATTEMPTS " +
                "last_exception=${ex::class.simpleName}: ${ex.message ?: "<null>"}",
        )
        throw ex
    }

    /**
     * Returns true if this throwable should trigger a publish retry.
     *
     * Retried:
     *  - SocketTimeoutException — body upload stall (Tele2 LTE bug, PR-R0)
     *    or read timeout on response headers.
     *  - IOException with "connection reset" or "broken pipe" in the message —
     *    TCP connection torn down by NAT/carrier mid-request.
     *
     * NOT retried (server says the request itself was bad):
     *  - HTTP 400 Bad Request, 401 Unauthorized, 403 Forbidden, 422
     *    Unprocessable Entity — retrying with the same payload won't help.
     *  - These do not reach this function; the HTTP-status branch in
     *    [publishWithRetry] handles them as non-retryable directly.
     *
     * Note: class-name matching is used instead of `is java.io.*` to keep
     * this function in commonMain (no JVM-only imports). The names
     * "SocketTimeoutException" and "IOException" are stable across JDK
     * versions and Android runtime versions.
     */
    private fun Throwable.isRetryable(): Boolean {
        val simpleName = this::class.simpleName ?: ""
        if (simpleName == "SocketTimeoutException") return true
        // Match IOException and its subclasses by checking the chain of
        // class simple names. SocketException, ConnectException, etc. are
        // all subclasses of IOException; we want them too when their message
        // indicates a network-layer teardown.
        val msg = message?.lowercase() ?: ""
        val isIoLike = simpleName.endsWith("IOException") ||
            simpleName.endsWith("SocketException") ||
            simpleName == "EOFException"
        if (isIoLike && ("connection reset" in msg || "broken pipe" in msg)) return true
        return false
    }

    /**
     * Fetch a peer's [PreKeyBundle] — the server atomically consumes one
     * OPK from the peer's pool and returns it together with the SPK.
     *
     * Returns null on 404 (peer has not published yet or pool was cleared).
     * Other non-2xx codes propagate as a [BundleFetchException] so callers
     * can distinguish "transient relay error" from "peer not on Alpha 2."
     */
    override suspend fun fetchBundle(
        identityPubkeyHex: String,
        requesterPubkeyHex: String?,
    ): PreKeyBundle? {
        val url = buildString {
            append(relayBaseUrl)
            append("/prekeys/bundle/")
            append(identityPubkeyHex)
            if (requesterPubkeyHex != null) {
                append("?requester=")
                append(requesterPubkeyHex)
            }
        }
        val identityTag = identityPubkeyHex.take(16)
        relayLog(
            RelayLogLevel.INFO,
            "PREKEY_TRACE http_bundle_fetch_start identity=$identityTag… url=$url",
        )
        val startMs = Clock.System.now().toEpochMilliseconds()
        val response: HttpResponse = try {
            httpClient.get(url)
        } catch (t: Throwable) {
            val elapsed = Clock.System.now().toEpochMilliseconds() - startMs
            relayLog(
                RelayLogLevel.WARN,
                "PREKEY_TRACE http_bundle_fetch_fail identity=$identityTag… " +
                    "type=${t::class.simpleName} elapsedMs=$elapsed message=${t.message ?: "<null>"}",
                t,
            )
            throw t
        }
        val elapsed = Clock.System.now().toEpochMilliseconds() - startMs
        relayLog(
            RelayLogLevel.INFO,
            "PREKEY_TRACE http_bundle_fetch_done identity=$identityTag… status=${response.status.value} elapsedMs=$elapsed",
        )
        return when (response.status) {
            HttpStatusCode.OK ->
                json.decodeFromString(PreKeyBundle.serializer(), response.bodyAsText())
            HttpStatusCode.NotFound -> null
            HttpStatusCode.TooManyRequests ->
                throw BundleFetchException.RateLimited(response.bodyAsText())
            else ->
                throw BundleFetchException.Unexpected(
                    response.status.value,
                    response.bodyAsText(),
                )
        }
    }

    /**
     * Query how many OPKs the relay still holds for a given identity, and
     * how old its current SPK is in days. The lifecycle service uses this
     * to decide replenish (count < 20) and SPK rotation (age >= 7 days)
     * without uploading anything.
     */
    override suspend fun fetchStatus(
        identityPubkeyHex: String,
        requesterPubkeyHex: String?,
    ): PreKeyStatus {
        val url = buildString {
            append(relayBaseUrl)
            append("/prekeys/status/")
            append(identityPubkeyHex)
            if (requesterPubkeyHex != null) {
                append("?requester=")
                append(requesterPubkeyHex)
            }
        }
        val identityTag = identityPubkeyHex.take(16)
        relayLog(
            RelayLogLevel.INFO,
            "PREKEY_TRACE http_status_start identity=$identityTag… url=$url",
        )
        val startMs = Clock.System.now().toEpochMilliseconds()
        val response: HttpResponse = try {
            httpClient.get(url)
        } catch (t: Throwable) {
            val elapsed = Clock.System.now().toEpochMilliseconds() - startMs
            relayLog(
                RelayLogLevel.WARN,
                "PREKEY_TRACE http_status_fail identity=$identityTag… " +
                    "type=${t::class.simpleName} elapsedMs=$elapsed message=${t.message ?: "<null>"}",
                t,
            )
            throw t
        }
        val elapsed = Clock.System.now().toEpochMilliseconds() - startMs
        relayLog(
            RelayLogLevel.INFO,
            "PREKEY_TRACE http_status_done identity=$identityTag… status=${response.status.value} elapsedMs=$elapsed",
        )
        return when (response.status) {
            HttpStatusCode.OK ->
                json.decodeFromString(PreKeyStatus.serializer(), response.bodyAsText())
            HttpStatusCode.TooManyRequests ->
                throw BundleFetchException.RateLimited(response.bodyAsText())
            else ->
                throw BundleFetchException.Unexpected(
                    response.status.value,
                    response.bodyAsText(),
                )
        }
    }

    companion object {
        /** Total publish attempts (1 initial + 2 retries). PR-R0. */
        const val PUBLISH_MAX_ATTEMPTS: Int = 3

        /**
         * Delays in milliseconds between consecutive publish attempts.
         * Index i is the delay BEFORE attempt i+2 (i.e., after attempt i+1 fails).
         * Length must be >= PUBLISH_MAX_ATTEMPTS - 1. PR-R0.
         */
        val PUBLISH_RETRY_DELAYS_MS: LongArray = longArrayOf(500L, 1500L, 3000L)
    }
}

// ── Wire types ───────────────────────────────────────────────────────────────

/**
 * Wire-format SignedPreKey published by the user. Mirrors the relay's
 * `SignedPreKeyPublicBundle` and the client's
 * `phantom.core.crypto.SignedPreKeyPublicBundle`.
 */
@Serializable
data class WireSignedPreKey(
    val key_id: Long,
    val public_key_hex: String,
    val created_at_ms: Long,
    val signature_hex: String,
)

@Serializable
data class WireOneTimePreKey(
    val key_id_hex: String,
    val public_key_hex: String,
)

@Serializable
data class PublishRequest(
    val identity_pubkey_hex: String,
    val signing_pubkey_hex: String,
    val signed_pre_key: WireSignedPreKey,
    val one_time_pre_keys: List<WireOneTimePreKey>,
)

@Serializable
internal data class PublishResponse(
    val stored_opks: Int,
) {
    val storedOpks: Int get() = stored_opks
}

@Serializable
data class PreKeyBundle(
    val identity_pubkey_hex: String,
    val signing_pubkey_hex: String,
    val signed_pre_key: WireSignedPreKey,
    val one_time_pre_key: WireOneTimePreKey? = null,
)

@Serializable
data class PreKeyStatus(
    val remaining_opks: Int,
    val signed_prekey_age_days: Long? = null,
)

// ── Result + exception types ─────────────────────────────────────────────────

sealed class PublishResult {
    data class Stored(val storedOpks: Int) : PublishResult()
    data class Failure(val reason: Reason, val serverMessage: String) : PublishResult()

    sealed class Reason {
        /** Relay already bound a different signing key to this identity. */
        data object SigningKeyMismatch : Reason()
        data object RateLimited : Reason()
        data object BadRequest : Reason()
        data class Unexpected(val httpStatus: Int) : Reason()
    }
}

sealed class BundleFetchException(message: String) : Exception(message) {
    class RateLimited(serverMessage: String) :
        BundleFetchException("rate limited: $serverMessage")
    class Unexpected(val httpStatus: Int, serverMessage: String) :
        BundleFetchException("unexpected http $httpStatus: $serverMessage")
}

/**
 * Helper: build an [HttpClient] suitable for prekey REST calls. Platform
 * implementations (transport factory) usually share a single client across
 * WebSocket and REST surfaces; this helper exists for tests that want a
 * minimal wired client.
 */
fun buildJsonHttpClient(httpClient: HttpClient): HttpClient = httpClient.config {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
}
