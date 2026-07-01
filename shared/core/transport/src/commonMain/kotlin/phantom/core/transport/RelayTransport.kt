// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface RelayTransport {
    val state: StateFlow<TransportState>
    val incoming: Flow<RelayMessage.Deliver>
    val acks: Flow<RelayMessage.Ack>

    /**
     * Emits the sender's pubKeyHex each time a "typing" event arrives from the relay.
     * These events are ephemeral — never stored or encrypted.
     */
    val typingEvents: SharedFlow<String>

    /**
     * Open the WebSocket and run the reconnect loop until [disconnect] is
     * called. Idempotent at the call-site level with two disjoint cases:
     *
     *   1. **No live reconnect loop** (fresh cold start OR after a prior
     *      [disconnect] / [disconnectAndJoin] that fully drained the
     *      loop): the new call-site args (`relayUrl`, `identityPublicKeyHex`,
     *      `signingPublicKeyHex`, `signChallenge`, `socksProxyPort`) are
     *      written under the connection-lifecycle mutex and a fresh
     *      reconnect loop is launched.
     *   2. **Live reconnect loop still running or draining** (e.g. after
     *      a [disconnectAndJoin] timed out without a full drain and left
     *      the previous job's ref alive): the new call-site args are
     *      IGNORED to prevent config corruption of the live loop
     *      mid-auth or mid-handshake. The existing job is reused — the
     *      call returns join semantics equivalent to awaiting the live
     *      loop. Callers that need to change `relayUrl` / identity /
     *      signing / socks MUST first invoke [disconnect] or
     *      [disconnectAndJoin] and confirm the drain completed.
     *
     * Rationale for case 2: an earlier shape wrote the args
     * unconditionally, which permitted a parallel `connect` to overwrite
     * an in-flight loop's `relayUrl` / identity mid-handshake — a
     * silent data race that produced connect-to-wrong-relay
     * regressions. The mutex+refuse discipline is a Review-amendment-P2
     * lock; the KDoc here mirrors it so callers know what a repeated
     * `connect` really does.
     *
     * Per F11 + F26 fix the relay no longer accepts a shared `?token=`. Each
     * (re)connect performs a per-user signed-challenge handshake:
     *
     *   1. HTTP `GET /auth/challenge?identity=<X25519_hex>` — relay returns
     *      a single-shot 32-byte nonce.
     *   2. The implementation calls [signChallenge] with the nonce; the
     *      caller's Ed25519 signing private key produces a 64-byte signature.
     *   3. WS upgrade to `?id=<X25519>&signing_pubkey=<Ed25519>
     *      &challenge=<nonce_hex>&signature=<sig_hex>`.
     *
     * The transport layer holds no signing-key material — the [signChallenge]
     * lambda owns it. A null return signals "cannot sign" (e.g. signing key
     * not yet provisioned during onboarding); the connect attempt aborts and
     * is retried on the next reconnect tick.
     *
     * @param relayUrl the WebSocket URL to dial. The same hostname is used
     *   for the HTTP `/auth/challenge` round-trip with the `/ws` path
     *   stripped. For direct WSS this is the public hostname
     *   (e.g. `wss://relay.phntm.pro/ws`); when [socksProxyPort] is non-null,
     *   callers usually pass the relay's onion address (plain
     *   `ws://…onion/ws`, plaintext over Tor by ADR-016 design).
     * @param identityPublicKeyHex the local X25519 identity, appended as
     *   `?id=…` so the relay can dispatch envelopes to the right queue.
     * @param signingPublicKeyHex the local Ed25519 signing pubkey that the
     *   relay verifies the challenge signature against. Must match the one
     *   already bound to the identity (TOFU on first connect).
     * @param signChallenge produces an Ed25519 signature over the issued
     *   nonce. Returning null aborts this connect attempt.
     * @param socksProxyPort if non-null, route the WebSocket TCP connection
     *   through `127.0.0.1:<port>` over SOCKS5. Null = direct.
     */
    suspend fun connect(
        relayUrl: String,
        identityPublicKeyHex: String,
        signingPublicKeyHex: String,
        signChallenge: suspend (challenge: ByteArray) -> ByteArray?,
        socksProxyPort: Int? = null,
    )
    suspend fun disconnect()

    /**
     * RC-RECONNECT-QUIESCENCE1 (2026-06-21) — disconnect variant that
     * ALSO `cancelAndJoin`s the per-transport reconnect loop with a
     * bounded wait. Locked default timeout 10_000 ms (longer than the
     * existing 5 s `finally`-block teardown inside `runReconnectLoop`
     * so the outer wait does not race the inner one). Returns `true`
     * if the reconnect-loop coroutine completed within [timeoutMs];
     * `false` if the wait timed out (in which case cancellation has
     * already been requested but the body has not yet exited).
     *
     * **Strict bound:** the [timeoutMs] hard-bound covers ONLY
     * `job.join()`. `job.cancel()` and the ping/ACK/per-generation
     * scope cancels run inside an uninterruptible critical region
     * (atomic under caller cancellation) and are completed before the
     * bounded join is entered. `session.close()` and `HttpClient.close()`
     * are dispatched fire-and-forget to a dedicated cleanup scope and
     * bounded by a hard in-flight cap — a cooperative `withTimeoutOrNull`
     * cannot interrupt a truly blocking close, so the cap is the honest
     * bound. The closes still happen best-effort, but may complete after
     * this method returns.
     *
     * **Outbox flush policy:** `disconnectAndJoin` does NOT flush the
     * pending outbox or pending ACKs. Pending stores are LEFT INTACT so
     * the downstream PR-D1c REST migration in `HybridRelayTransport` can
     * pick them up on the subsequent non-RestActive → RestActive
     * transition. AckDelivery entries are out-of-scope of D1c (snapshot
     * filter); their loss is tolerated by the relay's existing
     * redelivery semantics and the H2b idempotent envelope ledger
     * dedupes at the application layer. The legacy [disconnect] entry
     * point (logout / shutdown) DOES perform a bounded best-effort flush
     * before teardown — that is a property of `disconnect`, not of
     * `disconnectAndJoin`.
     *
     * **Transport state:** implementations MUST publish
     * [phantom.core.transport.TransportState.Disconnected] IMMEDIATELY
     * when teardown begins (before the strict-bound body), so external
     * observers (`isConnected()`, REST orchestrator, UI banner) see a
     * disconnected transport even if a close path hangs or the caller
     * is cancelled mid-teardown.
     *
     * **forceReconnect interaction:** while `disconnectRequested == true`
     * OR `reconnectJob.isCancelled && !isCompleted` (post-timeout drain),
     * `forceReconnect()` MUST be a no-op. Otherwise it would overwrite
     * the still-draining old job reference that a subsequent
     * `disconnectAndJoin()` needs to re-await.
     *
     * **Relationship to legacy `disconnect()`:** the legacy [disconnect]
     * now delegates to the same private teardown impl with the flush
     * policy enabled, so both entry points share the locked, bounded,
     * ref-preserving teardown semantics. Their flush behaviour differs.
     *
     * Implementations MUST serialise this call with `connect()` /
     * `forceReconnect()` via the same lifecycle mutex so a parallel
     * `connect()` cannot observe a half-torn-down state. The
     * `reconnectJob` reference MUST NOT be nulled when the captured job
     * is still alive (post-timeout drain) — nulling it would let a
     * concurrent `connect()` see `reconnectJob == null` and launch a
     * fresh loop while the old one is still in teardown.
     *
     * Implementations MUST propagate [kotlinx.coroutines.CancellationException]
     * from the wait without swallowing it (structured concurrency).
     */
    suspend fun disconnectAndJoin(timeoutMs: Long = 10_000L): Boolean

    suspend fun send(message: RelayMessage.Send): Boolean

    /**
     * Tells the relay that [messageId] has been fully processed on the recipient.
     * The relay removes that envelope from its per-recipient store. Best-effort —
     * if the WS is not connected the call is enqueued and retried on reconnect.
     */
    suspend fun sendDeliveryAck(messageId: String): Boolean

    /**
     * Sends an ephemeral typing notification to [toPubKeyHex].
     * The relay forwards it live if the recipient is online, drops it silently otherwise.
     * Returns false if not connected.
     */
    suspend fun sendTyping(toPubKeyHex: String): Boolean

    fun isConnected(): Boolean

    /**
     * Milliseconds elapsed since the last Pong frame was received from the
     * relay. ADR-011 — used by the AlarmManager-driven wakeup receiver to
     * detect a parked-radio dead WebSocket. A fresh value (< ~25 s) means
     * the connection is healthy; a stale value means the OS has parked the
     * Wi-Fi radio and the receiver should call [forceReconnect].
     *
     * Initial value (before the first connect) is conventionally large
     * (effectively "infinitely stale") so the first scheduled alarm fire
     * after cold start triggers a reconnect attempt.
     */
    val lastPongElapsedMs: Long

    /**
     * PR-H1c (2026-05-13): milliseconds since ANY inbound WebSocket frame
     * was observed (Deliver, Ack, Pong, malformed text — anything). Used
     * by the AlarmManager diagnostic log in PhantomWakeupReceiver (PR-R0.4a)
     * and the passive idle_watchdog log in KtorRelayTransport (PR-R0.4b).
     *
     * Why: under Tor / Reality and on networks with weird middleboxes the
     * Pong-routing path may be selectively dropped while normal envelope
     * traffic still flows. `lastPongElapsedMs` would false-positive in
     * those scenarios; this metric does not.
     *
     * Initial value before the first connect is conventionally large so
     * the first scheduled alarm after cold start sees a stale connection
     * and triggers a reconnect attempt.
     */
    val lastInboundFrameElapsedMs: Long

    /**
     * Number of envelopes that have been sent on the wire but not yet
     * acknowledged by the relay. Used by the AlarmManager wakeup receiver
     * to defer `forceReconnect()` while an upload is in-flight: tearing
     * down the WebSocket mid-flush loses every queued frame in the OkHttp
     * dispatcher, and the in-process ACK watchdog
     * ([RelayTransportConfig.ACK_TIMEOUT_MS]) is the right authority for
     * declaring those envelopes lost. Real-device test 2026-05-12 (no VPN,
     * RU carrier) showed wakeup firing every 30 s during a 6-chunk voice
     * upload, dropping all chunks before the relay could ack them.
     *
     * Reading this is lock-free against the in-flight tracker map; the
     * value may be off-by-one mid-update but that race is harmless because
     * the gate decision is "> 0", not exact.
     */
    val pendingAckCount: Int

    /**
     * Force the current reconnect generation to tear down and start over.
     * ADR-011 — invoked by [phantom.android.service.PhantomWakeupReceiver]
     * (Android only) when AlarmManager fires and detects a stale pong.
     *
     * Idempotent: cancelling an already-cancelled per-generation scope is
     * a no-op in coroutines, so concurrent calls (e.g. ack watchdog and
     * alarm receiver firing within the same second) collapse safely into
     * a single reconnect.
     */
    suspend fun forceReconnect()
}
