// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Platform-independent contract for the REST short-poll fallback transport — PR-D1.
 *
 * Motivation: Test #48 (2026-05-16) proved the Tele2 LTE Иркутск middlebox
 * passes the WS Upgrade handshake (101) but silently drops all subsequent
 * WS frames upstream (server-side `pings_received=0, inbound_frames=0`).
 * The same middlebox passes HTTP POST request bodies but MAY drop the
 * response (Caddy logs: `/prekeys/publish` body=5863b status=201 sent in
 * 2.9 ms, client never received the 18-byte response). Small GET req/resp
 * (≤ ~100 b) remain reliable.
 *
 * REST fallback gives the client a poll-driven HTTP/1.1 path that avoids
 * the WS frame layer entirely. The transport is **at-least-once** with
 * server-side idempotency, because the client cannot trust that a POST
 * response actually reached it — it may need to retry with the same
 * `Idempotency-Key` and rely on the server to deduplicate.
 *
 * Implementations MUST follow the native-OkHttp pattern from PR-R0.1
 * ([AndroidNativeOkHttpPreKeyPublishTransport]) and PR-R0.3
 * ([AndroidNativeOkHttpDirectProbe]):
 *   - Fresh OkHttpClient per call (no shared connection pool)
 *   - HTTP/1.1 pinned
 *   - `Connection: close` header
 *   - `retryOnConnectionFailure(false)` (caller owns retry semantics)
 *   - Response body wrapped in `.use { }` so it always closes
 *   - Log status INSIDE the `.use { }` block, log result AFTER it returns
 *
 * Threading: every method MUST suspend off the calling dispatcher (the
 * Android actual uses `withContext(Dispatchers.IO)`).
 *
 * Token lifecycle: callers obtain a bearer token via [authSession], then
 * pass it to [send], [poll], and [ackDeliver] until expiry. The transport
 * itself is stateless w.r.t. tokens — token caching lives in the caller
 * (typically [RestFallbackOrchestrator]).
 */
interface RestFallbackTransport {

    /**
     * One-shot bearer token issuance. Calls `POST /auth/session` with the
     * signed challenge. Server returns a bearer token plus capability flags.
     *
     * **Retry-safety**: the server treats `(identity, challenge, signature)`
     * within a ~5 min window as identical and returns the SAME token. The
     * client may safely retry a request whose response was lost in transit
     * without burning a fresh challenge.
     */
    suspend fun authSession(
        url: String,
        body: AuthSessionRequest,
    ): RestFallbackResponse<AuthSessionResponse>

    /**
     * Send one envelope. Idempotent: re-issuing the same `envelope_id` (as
     * both the body field and the `Idempotency-Key` header) within the
     * server cache TTL (~24 h) returns the same response without creating
     * a duplicate envelope.
     *
     * Status 201 = first delivery accepted; 200 = duplicate replay; 400 =
     * malformed; 401 = bad/expired token; 403 = token identity ≠ sender;
     * 409 = idempotency key reused with different body (client bug);
     * 413 = body > server's `max_send_body_bytes` cap.
     */
    suspend fun send(
        url: String,
        token: String,
        idempotencyKey: String,
        body: SendRequest,
    ): RestFallbackResponse<SendResponse>

    /**
     * Fetch up to one inbound envelope. The server retains the envelope
     * until the client sends [ackDeliver]; subsequent poll calls keep
     * returning the same envelope until acked. Short-poll only — server
     * returns immediately, empty array if nothing.
     */
    suspend fun poll(
        url: String,
        token: String,
        sinceSeq: Long? = null,
    ): RestFallbackResponse<PollResponse>

    /**
     * Remove an envelope from the server's per-recipient retention queue.
     * Called by the client AFTER successful decrypt + local persistence
     * (same pattern as PR-V0b voice ACK).
     *
     * Idempotent: re-acking an already-removed envelope returns 200.
     */
    suspend fun ackDeliver(
        url: String,
        token: String,
        body: AckDeliverRequest,
    ): RestFallbackResponse<AckDeliverResponse>
}

/**
 * Generic response wrapper carrying the parsed body, HTTP status, and
 * elapsed wall-clock time. The transport itself doesn't classify errors
 * — callers (the orchestrator) decide whether a given status is retryable.
 *
 * `bodyParsed` is null when the response status implies no parseable body
 * (5xx, 4xx with a plain-text error, or a body decode failure). `rawBody`
 * is always populated with what came off the wire so the caller can log
 * or surface error text.
 */
data class RestFallbackResponse<T>(
    /** HTTP status code, e.g. 201, 200, 409, 413, 5xx. */
    val statusCode: Int,
    /** Successfully parsed payload, or null on parse failure / non-success. */
    val bodyParsed: T?,
    /** Raw response body as UTF-8 text — never null, may be empty. */
    val rawBody: String,
    /** Wall-clock elapsed time for this single HTTP round-trip, in ms. */
    val elapsedMs: Long,
)

// ── Wire models — /auth/session ──────────────────────────────────────────────

/**
 * Request body for `POST /auth/session`. The challenge is obtained via the
 * existing `GET /auth/challenge?identity=<hex>` endpoint; the signature is
 * the client's Ed25519 signature over the challenge bytes.
 *
 * Field names are snake_case to match the relay's contract (PR-D0r).
 */
@Serializable
data class AuthSessionRequest(
    @SerialName("identity") val identityHex: String,
    @SerialName("signing_pubkey") val signingPubkeyHex: String,
    @SerialName("challenge") val challengeHex: String,
    @SerialName("signature") val signatureHex: String,
)

/**
 * Response body from `POST /auth/session`. The relay returns the bearer
 * token plus capability flags so the client can gate REST mode behind
 * `rest_fallback=true`.
 *
 * Older relays without REST fallback support either return an error or
 * omit the capability fields — in both cases the client treats the
 * relay as REST-incapable (see [RelayCapabilities.SAFE_DEFAULTS]).
 */
@Serializable
data class AuthSessionResponse(
    @SerialName("token") val token: String,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("rest_fallback") val restFallback: Boolean = false,
    @SerialName("max_send_body_bytes") val maxSendBodyBytes: Int = 0,
    @SerialName("poll_max_envelopes") val pollMaxEnvelopes: Int = 0,
)

// ── Wire models — /relay/send ────────────────────────────────────────────────

@Serializable
data class SendRequest(
    @SerialName("envelope_id") val envelopeId: String,
    @SerialName("to") val toHex: String,
    /**
     * Sealed-sender envelope (base64 of `eph_pub || nonce || ct`). Optional.
     * When non-empty, the recipient uses it to unseal the sender's identity;
     * the relay treats the value as opaque.
     *
     * PR-D0r review fix (2026-05-16): the original `SendRequest` omitted
     * this field, which would have broken sealed-mode message decrypt for
     * any client routing through the REST fallback. Mirrors the WS-side
     * `RelayMessage.Send.sealedSender` field. Defaults to empty so plain
     * messages do not pay the byte cost.
     */
    @SerialName("sealed_sender") val sealedSenderBase64: String = "",
    @SerialName("payload") val payloadBase64: String,
    @SerialName("sequence_ts") val sequenceTs: Long,
)

@Serializable
data class SendResponse(
    @SerialName("ok") val ok: Int,
)

// ── Wire models — /relay/poll ────────────────────────────────────────────────

@Serializable
data class PollResponse(
    @SerialName("envelopes") val envelopes: List<PollEnvelope> = emptyList(),
    @SerialName("more") val more: Boolean = false,
)

@Serializable
data class PollEnvelope(
    @SerialName("id") val id: String,
    @SerialName("from") val fromHex: String,
    @SerialName("payload") val payloadBase64: String,
    @SerialName("sequence_ts") val sequenceTs: Long,
    @SerialName("seq") val seq: Long,
)

// ── Wire models — /relay/ack-deliver ─────────────────────────────────────────

@Serializable
data class AckDeliverRequest(
    @SerialName("id") val id: String,
)

@Serializable
data class AckDeliverResponse(
    @SerialName("ok") val ok: Int,
)

// ── Capability model ─────────────────────────────────────────────────────────

/**
 * Snapshot of REST fallback capabilities advertised by the relay in the
 * [AuthSessionResponse]. Defaults are deliberately SAFE: if the relay
 * doesn't advertise REST support, the client stays in WS-only mode and
 * never calls /relay/send, /relay/poll, /relay/ack-deliver.
 *
 * The orchestrator checks [restFallback] before enabling the REST-mode
 * state machine. Old relays remain compatible without any client change
 * because the client default is to assume REST is unavailable.
 */
data class RelayCapabilities(
    val restFallback: Boolean,
    val maxSendBodyBytes: Int,
    val pollMaxEnvelopes: Int,
) {
    companion object {
        /**
         * Defaults applied when the relay's [AuthSessionResponse] is missing
         * the capability fields (old relay) or the auth-session call failed
         * entirely. Both cases mean: assume the relay does NOT support REST
         * fallback, and stay in WS-only mode.
         */
        val SAFE_DEFAULTS = RelayCapabilities(
            restFallback = false,
            maxSendBodyBytes = 0,
            pollMaxEnvelopes = 0,
        )
    }
}

/** Project [AuthSessionResponse] capability fields into [RelayCapabilities]. */
fun AuthSessionResponse.toCapabilities(): RelayCapabilities = RelayCapabilities(
    restFallback = restFallback,
    maxSendBodyBytes = maxSendBodyBytes,
    pollMaxEnvelopes = pollMaxEnvelopes,
)
