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
     *
     * Trek 2 Stage 2B-A — [longPollOptIn] controls BOTH the
     * `X-Phantom-Long-Poll: 1` and `X-Phantom-Padded-Poll: 1` opt-in
     * headers as a single pair. When `true`, both headers are emitted;
     * when `false`, neither is. The two headers are intentionally
     * coupled at the boundary because the Stage 2B-A scope (lock L1)
     * forbids any LP-alone or PP-alone client posture — a separate
     * parameter per header would let a future caller break that
     * invariant. The orchestrator decides the boolean by reading the
     * `LONGPOLL_V2_ENABLED` BuildConfig flag (debug `"1"` / release
     * `"0"`). Backwards-compat default `false` means a call site that
     * has not been updated still produces a Stage-1-byte-identical
     * legacy short-poll request.
     *
     * Trek 2 Stage 2B-A (B2) — [readTimeoutMs] OPTIONALLY overrides the
     * transport's default read-timeout for THIS call only. `null` (the
     * legacy default) means "use the transport's own short-poll
     * timeout" — the orchestrator passes a non-null value ONLY when
     * both halves of lock L2 hold:
     *
     *   * `LONGPOLL_V2_ENABLED == "1"`, AND
     *   * the relay-advertised `pollHoldSecs` is in `[1, 480]`.
     *
     * The override value is `(pollHoldSecs + safety_margin) * 1000`
     * milliseconds, where `safety_margin` is a few seconds inside
     * `[2, 8]`. The wire-up layer applies this override; legacy code
     * paths that do not pass the parameter remain byte-identical.
     */
    suspend fun poll(
        url: String,
        token: String,
        sinceSeq: Long? = null,
        longPollOptIn: Boolean = false,
        readTimeoutMs: Long? = null,
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
    /**
     * Trek 2 Stage 2B-B (C5, L8 + L9, M-B24) — parsed
     * `Retry-After` header value, in seconds.
     *
     * Populated only when:
     *   * The transport observed a `Retry-After` header on the
     *     response.
     *   * The header parsed as a non-negative numeric
     *     `Long` (HTTP-date form, non-numeric junk, empty, zero,
     *     and negative values are normalised to `null`).
     *
     * The orchestrator clamps the value to
     * `RETRY_AFTER_HARD_CAP_SECONDS = 120` BEFORE multiplying by
     * `1_000L` so a malicious or misconfigured relay sending
     * `Retry-After: 86400` (one day) cannot lock the client out
     * for a day, AND so the multiplication can NEVER overflow
     * `Long` regardless of advertised value. The clamp pattern
     * is `secs.coerceAtMost(RETRY_AFTER_HARD_CAP_SECONDS) * 1000L`
     * — applied in both REST poll loops and the send-path retry
     * that consumes the header.
     *
     * Default `null` keeps the wire shape backwards-compatible
     * with the Stage 2B-A transport surface and with every test
     * fake that does not exercise 429.
     */
    val retryAfterSeconds: Long? = null,
)

// ── Trek 2 Stage 2B-A (B1) — long-poll opt-in header constants ───────────────

/**
 * Name of the `X-Phantom-Long-Poll` opt-in header. Strict equality
 * `v == "1"` on the server side per Stage 1.x
 * (`services/relay/src/rest_fallback.rs:1964-1968`); sending any other
 * shape (e.g. `"true"`, mixed case in the value, trailing whitespace)
 * is silently treated as legacy short-poll.
 *
 * Names are lowercase to match the relay's `HeaderMap` lookup key.
 * OkHttp normalises outgoing header names to canonical case on the
 * wire, so the casing of THIS constant does not change the bytes that
 * leave the device — but keeping it lowercase keeps the constant
 * value-equal to the server's literal, which is the property tests
 * pin in M1.
 *
 * Const lives in commonMain so the orchestrator (commonMain) and the
 * Android wire-up (androidMain) reference one source of truth.
 */
const val LONG_POLL_OPT_IN_HEADER: String = "x-phantom-long-poll"

/**
 * Name of the `X-Phantom-Padded-Poll` opt-in header. Same `v == "1"`
 * strict equality contract as [LONG_POLL_OPT_IN_HEADER]
 * (`services/relay/src/rest_fallback.rs:1972-1976`).
 *
 * Stage 2B-A always emits this header together with
 * [LONG_POLL_OPT_IN_HEADER] (scope lock L1: no LP-alone or PP-alone
 * client posture). The constant is exposed independently because the
 * server contract treats them independently, and a future client
 * stage may need them separately.
 */
const val PADDED_POLL_OPT_IN_HEADER: String = "x-phantom-padded-poll"

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
    /**
     * PR-M2f — server-announced media capabilities. Older relays that have
     * not been redeployed yet omit this field entirely; the field default
     * makes that case behave like `binary_v3=false` (client stays on v2).
     */
    @SerialName("media_capabilities") val mediaCapabilities: MediaCapabilities = MediaCapabilities(),
    /**
     * Trek 2 Stage 2 (Stage 2A) — long-poll hold-time in seconds
     * announced by the relay. `0` means short-poll (the relay either
     * has long-poll disabled via `RELAY_POLL_HOLD_SECS=0` or is an old
     * pre-Stage-1 build that doesn't know the field). The Stage 1
     * server contract ships this field always-present; older relays
     * omit it and the default `0` makes that case behave like
     * short-poll without any client behaviour change. Stage 2A is
     * the foundation-only landing — the runtime gating of the
     * `X-Phantom-Long-Poll: 1` opt-in header on this value is a
     * Stage 2B deliverable (no behaviour change in this commit).
     */
    @SerialName("poll_hold_secs") val pollHoldSecs: Int = 0,
    /**
     * Trek 2 Stage 2B-A (B3, L5) — per-identity verify key for the
     * `seq_mac` integrity tag on `/relay/poll` envelopes. 64-char
     * lowercase hex (32-byte HMAC-SHA-256 output). Derived from the
     * relay-side root key (which never leaves the relay process) and
     * the bound identity per the Stage 1.x contract in
     * `services/relay/src/rest_fallback.rs`.
     *
     * Stage 2B-A presence-parses the field for wire stability —
     * the orchestrator caches the value in a session-scoped in-memory
     * slot so Stage 2B-B can verify MACs without a session-rotation
     * handshake. Stage 2B-A does NOT verify MACs and does NOT
     * advance `since_seq` based on the unverified key (locks L5,
     * L4). Default `""` makes the wire shape robust against older
     * relays that have not been redeployed with Stage 1.x.
     */
    @SerialName("seq_mac_verify_key") val seqMacVerifyKey: String = "",
)

/**
 * PR-M2f — media-endpoint capabilities advertised by the relay in the
 * `/auth/session` response. New flags land here without breaking older
 * clients that ignore unknown fields (kotlinx-serialization default).
 */
@Serializable
data class MediaCapabilities(
    @SerialName("binary_v3") val binaryV3: Boolean = false,
    @SerialName("max_upload_body_bytes") val maxUploadBodyBytes: Int = 0,
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
    /**
     * Sealed-sender envelope (base64) — relay-opaque. Mirrors the WS-side
     * `RelayMessage.Deliver.sealedSender` field. The server omits this in
     * the JSON when empty (`skip_serializing_if = "String::is_empty"`),
     * so the default `""` keeps the wire model backwards-compatible with
     * older relays that never set it.
     *
     * PR-D1 follow-up (2026-05-16): without this field on the client side,
     * D1b wire-up would silently drop `sealed_sender` when translating a
     * polled envelope into the existing `Deliver` pipeline — same class of
     * bug PR-D0r blocker 3 addressed on the server.
     */
    @SerialName("sealed_sender") val sealedSenderBase64: String = "",
    @SerialName("payload") val payloadBase64: String,
    @SerialName("sequence_ts") val sequenceTs: Long,
    @SerialName("seq") val seq: Long,
    /**
     * Trek 2 Stage 2B-A (B3, L5) — per-envelope HMAC-SHA-256
     * integrity tag computed at store time on the relay over the
     * canonical `(identity_hex, seq, envelope_id, sequence_ts)`
     * tuple. 64-char lowercase hex. See the server-side doc-comment
     * in `services/relay/src/rest_fallback.rs` for the locked threat
     * wording — verbatim, do not soften.
     *
     * Stage 2B-A presence-parses the field for wire stability; the
     * orchestrator does NOT verify the MAC and does NOT advance
     * `since_seq` based on the unverified value (locks L5, L4).
     * Verification lands in Stage 2B-B together with the cursor
     * advancement path, both gated on MAC verify + storage
     * accept-or-dedup. Default `""` makes the wire shape robust
     * against older relays that have not been redeployed.
     */
    @SerialName("seq_mac") val seqMac: String = "",
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
    /**
     * PR-M2f — `true` when the relay serves the binary `/media/v3/...`
     * endpoint pair. False on older relays that haven't been redeployed yet
     * and also after a runtime 404/405 fallback at the media transport
     * layer (sticky disable until the next capability refresh).
     */
    val mediaBinaryV3: Boolean,
    /** PR-M2f — relay's media upload body cap echoed in the session response. */
    val mediaUploadBodyBytes: Int,
    /**
     * Trek 2 Stage 2 (Stage 2A) — relay-announced long-poll hold-time
     * in seconds (see [AuthSessionResponse.pollHoldSecs]). `0` means
     * the relay is short-poll-only either because the operator left
     * `RELAY_POLL_HOLD_SECS=0` (production default + kill switch) or
     * because the relay is an old pre-Stage-1 build that does not
     * announce the field. Default `0` preserves byte-identical runtime
     * behaviour. Stage 2A surfaces this value through capabilities so
     * Stage 2B can gate the `X-Phantom-Long-Poll: 1` opt-in header on
     * it; Stage 2A itself does NOT consume the value at runtime.
     */
    val pollHoldSecs: Int,
    /**
     * Trek 2 Stage 2B-A (B3, L5) — per-identity `seq_mac` verify key
     * projected from [AuthSessionResponse.seqMacVerifyKey]. 64-char
     * lowercase hex (32 bytes HMAC-SHA-256 output). Empty when the
     * relay does not announce the field — older relays or a Stage 1.x
     * deployment without `RELAY_SEQ_MAC_KEY` provisioned.
     *
     * Stage 2B-A surfaces this value through capabilities so the
     * orchestrator can cache it in a session-scoped slot for Stage
     * 2B-B without a session-rotation handshake. Stage 2B-A itself
     * does NOT verify MACs (lock L5).
     */
    val seqMacVerifyKey: String,
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
            mediaBinaryV3 = false,
            mediaUploadBodyBytes = 0,
            pollHoldSecs = 0,
            seqMacVerifyKey = "",
        )
    }
}

/** Project [AuthSessionResponse] capability fields into [RelayCapabilities]. */
fun AuthSessionResponse.toCapabilities(): RelayCapabilities = RelayCapabilities(
    restFallback = restFallback,
    maxSendBodyBytes = maxSendBodyBytes,
    pollMaxEnvelopes = pollMaxEnvelopes,
    mediaBinaryV3 = mediaCapabilities.binaryV3,
    mediaUploadBodyBytes = mediaCapabilities.maxUploadBodyBytes,
    pollHoldSecs = pollHoldSecs,
    seqMacVerifyKey = seqMacVerifyKey,
)
