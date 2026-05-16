// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import phantom.core.storage.ProcessedEnvelopeRepository
import phantom.core.transport.AckOutcome
import phantom.core.transport.KtorRelayTransport
import phantom.core.transport.PollEnvelope
import phantom.core.transport.RelayMessage
import phantom.core.transport.RelayTransport
import phantom.core.transport.RestFallbackOrchestrator
import phantom.core.transport.RestMode
import phantom.core.transport.RestStateMachine
import phantom.core.transport.SendOutcome
import phantom.core.transport.TransportState

/**
 * PR-D1b (2026-05-16): wraps the existing [KtorRelayTransport] (WS path)
 * and the [RestFallbackOrchestrator] (REST short-poll fallback) behind the
 * same [RelayTransport] interface that the rest of the app consumes.
 *
 * **Why this lives in the Android app layer (not commonMain transport):**
 * the wrapper needs to dedup REST-sourced envelopes against
 * [ProcessedEnvelopeRepository], which lives in `shared/core/storage`.
 * Pulling that dependency into `shared/core/transport` would invert the
 * existing module graph (transport currently does not know about storage).
 * Keeping the wrapper at the app layer preserves the boundary.
 *
 * **Routing rules** (locked 2026-05-16):
 * - Outbound `send(...)`:
 *     - [RestMode.WsActive] → delegate to `wsTransport.send(...)` (WS path
 *       unchanged from pre-D1b behaviour)
 *     - [RestMode.RestActive] OR [RestMode.WsCandidate] → route via
 *       `orchestrator.sendEnvelope(...)` (REST POST `/relay/send`)
 *   On `SendOutcome.DisabledByCapability` (relay does not advertise
 *   `rest_fallback=true`) we fall back to `wsTransport.send(...)` — that is
 *   the inert-passthrough mode that keeps the app behaving exactly like
 *   pre-D1b against old relays.
 * - Inbound `incoming` flow:
 *     - Frame.Text from WS → forwarded verbatim into the merged flow and
 *       fed into the state machine as `WsFrameTextReceived`.
 *     - `PollEnvelope` from REST poll → dedup-checked against (1) the
 *       persistent `ProcessedEnvelopeRepository` ledger (a WS-then-REST
 *       race where DMS already processed the envelope) and (2) an
 *       in-memory short-TTL set (a REST-then-REST replay because the
 *       previous ack failed). On dedup hit, the envelope is silently
 *       re-acked via the orchestrator and NOT re-emitted. On miss, it is
 *       translated 1:1 into a [RelayMessage.Deliver] and emitted.
 * - `sendDeliveryAck(messageId)`:
 *     - If the id is currently tracked in `restPendingAck` (i.e. we just
 *       emitted it via the REST path), route the ack to
 *       `orchestrator.ackInbound(...)`. This is the ACK-after-persistence
 *       path locked by Vladislav 2026-05-16 — DMS only calls
 *       `sendDeliveryAck` after successful decrypt + DB insert, which
 *       guarantees the relay only sees the ack once durable storage
 *       owns the message.
 *     - Else delegate to `wsTransport.sendDeliveryAck(messageId)`.
 *
 * **State-machine feeds** wired by [bootstrapAndStart]:
 * - `wsTransport.wsSessionEnded` → [RestStateMachine.Event.WsSessionEnded]
 * - every `wsTransport.incoming` emission → `WsFrameTextReceived`
 * - every `wsTransport.acks` emission → `WsFrameTextReceived` AND
 *   `WsOutboundAckReceived` (the strongest "WS is bidirectional"
 *   signal — commits a candidate session into `WsActive` immediately)
 *
 * `NetworkChanged` events are deliberately NOT wired in D1b
 * (deferred to PR-D1c).
 *
 * **Capability gate.** [bootstrapAndStart] returns early without starting
 * the orchestrator's poll loop or state-observer if the relay does not
 * advertise `rest_fallback=true`. The wrapper then behaves as a thin
 * passthrough: all `send` calls go to `wsTransport`, no REST traffic ever
 * fires. This preserves the locked rule that pre-D0r relays remain
 * functionally identical to today.
 */
class HybridRelayTransport(
    private val wsTransport: KtorRelayTransport,
    private val orchestrator: RestFallbackOrchestrator,
    private val processedEnvelopeRepository: ProcessedEnvelopeRepository?,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : RelayTransport {

    // ── Delegated RelayTransport surface ─────────────────────────────────────

    override val state: StateFlow<TransportState> get() = wsTransport.state
    override val acks: Flow<RelayMessage.Ack> get() = wsTransport.acks
    override val typingEvents: SharedFlow<String> get() = wsTransport.typingEvents
    override val lastPongElapsedMs: Long get() = wsTransport.lastPongElapsedMs
    override val lastInboundFrameElapsedMs: Long get() = wsTransport.lastInboundFrameElapsedMs
    override val pendingAckCount: Int get() = wsTransport.pendingAckCount

    override fun isConnected(): Boolean = wsTransport.isConnected()

    override suspend fun connect(
        relayUrl: String,
        identityPublicKeyHex: String,
        signingPublicKeyHex: String,
        signChallenge: suspend (challenge: ByteArray) -> ByteArray?,
        socksProxyPort: Int?,
    ) = wsTransport.connect(
        relayUrl = relayUrl,
        identityPublicKeyHex = identityPublicKeyHex,
        signingPublicKeyHex = signingPublicKeyHex,
        signChallenge = signChallenge,
        socksProxyPort = socksProxyPort,
    )

    override suspend fun disconnect() = wsTransport.disconnect()

    override suspend fun forceReconnect() = wsTransport.forceReconnect()

    override suspend fun sendTyping(toPubKeyHex: String): Boolean =
        wsTransport.sendTyping(toPubKeyHex)

    // ── Merged inbound flow ──────────────────────────────────────────────────

    private val _incoming = MutableSharedFlow<RelayMessage.Deliver>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val incoming: Flow<RelayMessage.Deliver> = _incoming.asSharedFlow()

    // ── REST routing bookkeeping ─────────────────────────────────────────────

    /**
     * IDs of envelopes that arrived via REST poll and were emitted into
     * [_incoming]. When DMS later calls [sendDeliveryAck] with one of these
     * ids, we route the ACK to [RestFallbackOrchestrator.ackInbound]
     * instead of the WS path. Mutated under [restLock].
     */
    private val restPendingAck = mutableSetOf<String>()

    /**
     * Recently-emitted REST envelope IDs with timestamps, for second-line
     * dedup against orchestrator re-emissions when an ack failed. Capped at
     * [DEDUP_CAPACITY] entries with FIFO eviction. Mutated under [restLock].
     */
    private val restDeliveredIds = LinkedHashMap<String, Long>()

    private val restLock = Mutex()

    // ── State machine accessor ───────────────────────────────────────────────

    /**
     * Exposed so observers ([phantom.android.service.PhantomMessagingService]
     * notification updater) can render an honest "Online via REST fallback"
     * UI label when the state machine is in [RestMode.RestActive].
     */
    val stateMachine: RestStateMachine get() = orchestrator.stateMachine

    /** Convenience accessor for the underlying orchestrator's capabilities. */
    val capabilities get() = orchestrator.capabilities

    // ── Bootstrap + wiring ───────────────────────────────────────────────────

    /**
     * Called once from [phantom.android.di.AppContainer.initMessaging] after
     * the identity and signing keypair are available. Idempotent at the
     * caller's level — AppContainer wraps this in `runCatching` so a failed
     * bootstrap (e.g. the relay is unreachable at app start) does NOT prevent
     * the WS path from functioning.
     *
     * Sequence:
     *  1. Call `orchestrator.bootstrap()` — this attempts `POST /auth/session`
     *     against the relay and reads the capability fields.
     *  2. Log the outcome (`bootstrap_ok capability=true|false`).
     *  3. If `restFallback == false` (old relay, or auth-session failed),
     *     return without starting any of the observation jobs. The wrapper
     *     then behaves as a transparent WS passthrough.
     *  4. Otherwise start the orchestrator (poll loop is armed but gated
     *     on state-machine mode), and launch background collectors that
     *     forward WS events into the state machine and REST inbound into
     *     the merged flow.
     */
    suspend fun bootstrapAndStart() {
        val caps = orchestrator.bootstrap()
        Log.i(TAG, "REST_TRACE bootstrap_ok capability=${caps.restFallback}")
        if (!caps.restFallback) {
            // Capability disabled — wrapper stays in passthrough mode.
            // No background jobs, no behaviour change vs pre-D1b.
            return
        }
        orchestrator.start()

        // WS session-end events → state machine.
        scope.launch {
            wsTransport.wsSessionEnded.collect { event ->
                orchestrator.submitEvent(
                    RestStateMachine.Event.WsSessionEnded(
                        durationMs = event.durationMs,
                        inboundFrames = event.inboundFrames,
                        pendingAcksAtClose = event.pendingAcksAtClose,
                    )
                )
            }
        }

        // WS Frame.Text → state machine + merged inbound.
        scope.launch {
            wsTransport.incoming.collect { deliver ->
                orchestrator.submitEvent(RestStateMachine.Event.WsFrameTextReceived)
                _incoming.emit(deliver)
            }
        }

        // WS ACK → state machine (strongest "WS bidirectional" signal).
        // We forward both WsFrameTextReceived and WsOutboundAckReceived so
        // a WS_CANDIDATE session commits to WS_ACTIVE on the first
        // round-trip rather than waiting the 60 s alive timer.
        scope.launch {
            wsTransport.acks.collect {
                orchestrator.submitEvent(RestStateMachine.Event.WsFrameTextReceived)
                orchestrator.submitEvent(RestStateMachine.Event.WsOutboundAckReceived)
            }
        }

        // REST poll inbound → dedup → translate → merged inbound.
        scope.launch {
            orchestrator.inbound.collect { handleRestInbound(it) }
        }
    }

    // ── Outbound routing ─────────────────────────────────────────────────────

    override suspend fun send(message: RelayMessage.Send): Boolean {
        val mode = stateMachine.current
        return when (mode) {
            RestMode.WsActive -> wsTransport.send(message)
            RestMode.RestActive, RestMode.WsCandidate -> sendViaRest(message, mode)
        }
    }

    private suspend fun sendViaRest(message: RelayMessage.Send, mode: RestMode): Boolean {
        val payloadBase64 = message.payload
        val sealedSender = message.sealedSender
        val approxBodyBytes = payloadBase64.length + sealedSender.length +
            APPROX_REST_BODY_OVERHEAD_BYTES
        Log.i(
            TAG,
            "REST_TRACE route_send mode=$mode id=${message.messageId.take(8)} " +
                "bodyBytes=$approxBodyBytes",
        )
        val outcome = orchestrator.sendEnvelope(
            envelopeId = message.messageId,
            toHex = message.to,
            payloadBase64 = payloadBase64,
            sequenceTs = nowMs(),
            sealedSenderBase64 = sealedSender,
        )
        return when (outcome) {
            is SendOutcome.Accepted -> true
            is SendOutcome.Duplicate -> true
            is SendOutcome.DisabledByCapability -> {
                // Capability flipped to false after bootstrap (shouldn't
                // happen in steady state, but defend against it): fall
                // back to WS for this single send.
                Log.w(
                    TAG,
                    "REST_TRACE route_send_fallback_ws id=${message.messageId.take(8)} " +
                        "reason=disabled_by_capability",
                )
                wsTransport.send(message)
            }
            is SendOutcome.OversizeBody -> {
                Log.w(
                    TAG,
                    "REST_TRACE send_oversize id=${message.messageId.take(8)} " +
                        "bodyBytes=${outcome.bodyBytes} max=${outcome.maxBytes}",
                )
                // Do NOT silently fall back to WS — caller (DMS) sees false,
                // existing retry/queue logic decides what to do. Voice
                // envelopes hit this path until PR-D2 ships a chunking
                // strategy for REST.
                false
            }
            is SendOutcome.Failed -> {
                Log.w(
                    TAG,
                    "REST_TRACE send_failed id=${message.messageId.take(8)} " +
                        "status=${outcome.statusCode} reason=${outcome.reason}",
                )
                false
            }
        }
    }

    // ── Inbound merge + dedup ────────────────────────────────────────────────

    private suspend fun handleRestInbound(env: PollEnvelope) {
        // Layer 1: persistent ledger — was this envelope already fully
        // processed (decrypt + insert) by DMS on a previous WS-path
        // delivery? If so, drop and re-ack to clear the relay queue.
        val alreadyProcessed = runCatching {
            processedEnvelopeRepository?.exists(env.id) ?: false
        }.getOrDefault(false)
        if (alreadyProcessed) {
            Log.i(
                TAG,
                "REST_TRACE inbound_skip_already_processed id=${env.id.take(8)}",
            )
            runCatching { orchestrator.ackInbound(env.id) }
            return
        }

        // Layer 2: in-memory short-TTL dedup against orchestrator re-emit.
        // The orchestrator does NOT remember what it already emitted — if
        // its ack call fails, the server keeps redelivering on the next
        // poll. The state goes "emit → ack fail → server returns same id
        // on next poll → emit again". We absorb the re-emission here so
        // DMS does not see the same envelope twice in quick succession.
        val nowMillis = nowMs()
        var shouldEmit = false
        restLock.withLock {
            val seenAt = restDeliveredIds[env.id]
            if (seenAt != null && (nowMillis - seenAt) < DEDUP_TTL_MS) {
                // Recent re-emit; re-ack and skip.
            } else {
                restDeliveredIds[env.id] = nowMillis
                // Capacity-cap with FIFO eviction.
                while (restDeliveredIds.size > DEDUP_CAPACITY) {
                    val oldest = restDeliveredIds.keys.iterator().next()
                    restDeliveredIds.remove(oldest)
                }
                restPendingAck.add(env.id)
                shouldEmit = true
            }
        }

        if (!shouldEmit) {
            Log.i(
                TAG,
                "REST_TRACE inbound_skip_recent_dedup id=${env.id.take(8)}",
            )
            runCatching { orchestrator.ackInbound(env.id) }
            return
        }

        Log.i(
            TAG,
            "REST_TRACE inbound_deliver id=${env.id.take(8)} " +
                "from=${env.fromHex.take(8)} source=REST",
        )
        _incoming.emit(
            RelayMessage.Deliver(
                from = env.fromHex,
                sealedSender = env.sealedSenderBase64,
                payload = env.payloadBase64,
                messageId = env.id,
            )
        )
    }

    // ── ACK routing ──────────────────────────────────────────────────────────

    override suspend fun sendDeliveryAck(messageId: String): Boolean {
        val viaRest = restLock.withLock {
            if (messageId in restPendingAck) {
                restPendingAck.remove(messageId)
                true
            } else {
                false
            }
        }
        if (!viaRest) {
            return wsTransport.sendDeliveryAck(messageId)
        }
        // DMS only calls sendDeliveryAck AFTER markProcessed + insertMessage,
        // so by the time we get here the envelope is durably stored locally.
        // This is the load-bearing ACK-after-persistence invariant.
        Log.i(TAG, "REST_TRACE ack_after_save id=${messageId.take(8)}")
        val outcome = runCatching { orchestrator.ackInbound(messageId) }
            .getOrElse { AckOutcome.Failed(statusCode = null, reason = it::class.simpleName ?: "Throwable") }
        return when (outcome) {
            is AckOutcome.Acked -> true
            is AckOutcome.DisabledByCapability -> {
                // Capability flipped — DMS won't retry, and the relay never
                // saw this REST envelope acked. On next reconnect/poll the
                // relay will redeliver, and our [restDeliveredIds] dedup
                // will catch the re-emit and try ackInbound again. Safe.
                Log.w(
                    TAG,
                    "REST_TRACE ack_disabled_by_capability id=${messageId.take(8)}",
                )
                false
            }
            is AckOutcome.Failed -> {
                Log.w(
                    TAG,
                    "REST_TRACE ack_failed id=${messageId.take(8)} " +
                        "status=${outcome.statusCode} reason=${outcome.reason}",
                )
                false
            }
        }
    }

    companion object {
        private const val TAG = "PhantomHybrid"

        /**
         * Maximum number of recently-emitted REST envelope IDs we remember
         * for in-memory dedup against orchestrator re-emissions. FIFO
         * eviction beyond this cap. 256 covers a long worst-case poll
         * burst (server retains up to ~7 days; 256 entries × ~64 b id =
         * 16 KB, negligible).
         */
        const val DEDUP_CAPACITY: Int = 256

        /**
         * After this many ms a REST inbound id falls out of the dedup
         * window. The relay's poll-retention is 7 days, but a recipient
         * client that survived a poll-and-ack failure should not still
         * be deduping the same id 5 minutes later — by then the
         * persistent ledger guard catches it.
         */
        const val DEDUP_TTL_MS: Long = 5 * 60_000L

        /**
         * Rough overhead approximating JSON keys + Idempotency-Key header
         * + base envelope size on top of `payload.length + sealed_sender.length`.
         * Used only for the `route_send` log line — actual wire size is
         * computed at the orchestrator/transport layer.
         */
        const val APPROX_REST_BODY_OVERHEAD_BYTES: Int = 256
    }
}
