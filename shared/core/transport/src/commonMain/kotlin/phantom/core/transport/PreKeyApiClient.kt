// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
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
) : PreKeyApi {

    /**
     * Publish (or refresh) the local SignedPreKey + a batch of OneTimePreKeys.
     *
     * The batch fully replaces any previously-published OPK pool for this
     * identity (relay semantics, see ADR-009 + PR B). The client is
     * responsible for re-uploading any OPKs it still holds locally if it
     * wants them all to remain available — the lifecycle service already
     * does this on every publish.
     *
     * @return [PublishResult.Stored] with the count of OPKs the relay
     *         retained (after dedup + cap), or [PublishResult.Failure]
     *         describing why the relay rejected the bundle.
     */
    override suspend fun publishBundle(request: PublishRequest): PublishResult {
        val body = json.encodeToString(PublishRequest.serializer(), request)
        val url = "$relayBaseUrl/prekeys/publish"
        val identityTag = request.identity_pubkey_hex.take(16)
        relayLog(
            RelayLogLevel.INFO,
            "PREKEY_TRACE http_publish_start identity=$identityTag… opks=${request.one_time_pre_keys.size} url=$url",
        )
        val startMs = Clock.System.now().toEpochMilliseconds()
        val response: HttpResponse = try {
            httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (t: Throwable) {
            val elapsed = Clock.System.now().toEpochMilliseconds() - startMs
            relayLog(
                RelayLogLevel.WARN,
                "PREKEY_TRACE http_publish_fail identity=$identityTag… " +
                    "type=${t::class.simpleName} elapsedMs=$elapsed message=${t.message ?: "<null>"}",
                t,
            )
            throw t
        }
        val elapsed = Clock.System.now().toEpochMilliseconds() - startMs
        relayLog(
            RelayLogLevel.INFO,
            "PREKEY_TRACE http_publish_done identity=$identityTag… status=${response.status.value} elapsedMs=$elapsed",
        )
        return when (response.status) {
            HttpStatusCode.Created -> {
                val parsed = json.decodeFromString(
                    PublishResponse.serializer(),
                    response.bodyAsText(),
                )
                PublishResult.Stored(parsed.storedOpks)
            }
            HttpStatusCode.Conflict ->
                PublishResult.Failure(
                    PublishResult.Reason.SigningKeyMismatch,
                    response.bodyAsText(),
                )
            HttpStatusCode.TooManyRequests ->
                PublishResult.Failure(
                    PublishResult.Reason.RateLimited,
                    response.bodyAsText(),
                )
            HttpStatusCode.BadRequest, HttpStatusCode.PayloadTooLarge ->
                PublishResult.Failure(
                    PublishResult.Reason.BadRequest,
                    response.bodyAsText(),
                )
            else ->
                PublishResult.Failure(
                    PublishResult.Reason.Unexpected(response.status.value),
                    response.bodyAsText(),
                )
        }
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
