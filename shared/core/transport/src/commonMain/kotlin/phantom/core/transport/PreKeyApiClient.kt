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
class PreKeyApiClient(
    private val httpClient: HttpClient,
    private val relayBaseUrl: String,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {

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
    suspend fun publishBundle(request: PublishRequest): PublishResult {
        val body = json.encodeToString(PublishRequest.serializer(), request)
        val response: HttpResponse = httpClient.post("$relayBaseUrl/prekeys/publish") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
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
    suspend fun fetchBundle(
        identityPubkeyHex: String,
        requesterPubkeyHex: String? = null,
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
        val response: HttpResponse = httpClient.get(url)
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
    suspend fun fetchStatus(
        identityPubkeyHex: String,
        requesterPubkeyHex: String? = null,
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
        val response: HttpResponse = httpClient.get(url)
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
