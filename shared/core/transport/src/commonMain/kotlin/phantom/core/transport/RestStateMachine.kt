// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure state machine for WS ↔ REST fallback mode selection — PR-D1.
 *
 * Decides whether outbound envelope traffic and inbound polling should run
 * over the WebSocket path ([RestMode.WsActive]), the REST short-poll
 * fallback path ([RestMode.RestActive]), or the transitional candidate
 * state ([RestMode.WsCandidate]) where WS appears recovered but we still
 * keep REST polling as a safety net until WS proves stable.
 *
 * The state machine is **input-driven**: it consumes events fed in by the
 * orchestrator (or by tests) and emits state transitions via [state].
 * It has no direct dependency on [RelayTransport] or any I/O — that makes
 * the logic fully deterministic and unit-testable under [kotlinx.coroutines.test.runTest].
 *
 * Locked triggers (2026-05-16):
 *
 * From [RestMode.WsActive]:
 *   - 2 consecutive [Event.WsSessionEnded] with `inboundFrames == 0` AND
 *     `pendingAcksAtClose > 0` (active outbound, pessimistic trigger) → REST
 *   - 3 consecutive [Event.WsSessionEnded] with `inboundFrames == 0` AND
 *     `pendingAcksAtClose == 0` (passive idle, conservative trigger) → REST
 *   - Any [Event.WsSessionEnded] with `inboundFrames > 0` → reset counters
 *   - [Event.NetworkChanged] → reset counters (new path may behave differently)
 *
 * From [RestMode.RestActive]:
 *   - [Event.WsFrameTextReceived] (relay pushed any Frame.Text — Deliver, Ack,
 *     application Pong-as-text) → [RestMode.WsCandidate], REST polling continues
 *   - [Event.NetworkChanged] → [RestMode.WsCandidate], REST polling continues
 *
 * From [RestMode.WsCandidate]:
 *   - [Event.WsAliveTickElapsed] with `elapsedSinceCandidateEnteredMs >= 60_000`
 *     → [RestMode.WsActive], REST polling stops
 *   - [Event.WsOutboundAckReceived] → [RestMode.WsActive], REST polling stops
 *   - [Event.WsSessionEnded] (any close) → [RestMode.RestActive] (regression),
 *     REST polling continues, both counters reset
 *
 * Anti-flapping rationale:
 *   The two-phase WsCandidate state exists because a single Frame.Text under
 *   broken Tele2 conditions does not prove the link is healthy — Test #48
 *   showed isolated messages occasionally proxime even on heavily-degraded
 *   sessions. We require either 60 seconds of WS uptime OR an outbound ACK
 *   round-trip before fully committing back to WS.
 *
 * Threading: not thread-safe. The orchestrator should serialise calls onto a
 * single coroutine context (typically `Dispatchers.Default` or the same
 * dispatcher that owns the polling loop).
 */
class RestStateMachine(
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
    // PR-WS-HEALTH-STATE1 Commit 3.2a (architect P2-2, 2026-06-01):
    // observation hook for every mode-switch. Fires AFTER `_state.value`
    // has been assigned the new mode and AFTER the `REST_TRACE
    // mode_switched ...` log line has been emitted. Used by
    // [phantom.core.transport.WsDegradationDetector] to emit
    // `WS_DEGRADED_TELEMETRY state_transition_seen reason=<reason>` so
    // calibration can correlate detector verdicts with state-machine
    // transitions per design note §6. Optional and defaults to no-op
    // for backward compatibility with existing tests.
    private val onModeSwitched: ((from: RestMode, to: RestMode, reason: String) -> Unit)? = null,
) {
    private val _state = MutableStateFlow<RestMode>(RestMode.WsActive)
    val state: StateFlow<RestMode> = _state.asStateFlow()

    /** Convenience accessor. */
    val current: RestMode
        get() = _state.value

    // Mutable bookkeeping. Reset rules are documented inline at the reset call sites.
    private var activeFailCount: Int = 0
    private var idleFailCount: Int = 0
    private var candidateEnteredAtMs: Long? = null

    /**
     * Submit an [Event] to the state machine. Idempotent w.r.t. duplicate
     * events: e.g. multiple [Event.WsFrameTextReceived] in [RestMode.RestActive]
     * only fire the first transition into [RestMode.WsCandidate]; later ones
     * are no-ops while in candidate.
     */
    fun onEvent(event: Event) {
        when (event) {
            is Event.WsSessionEnded -> onWsSessionEnded(event)
            is Event.WsFrameTextReceived -> onWsFrameText()
            is Event.NetworkChanged -> onNetworkChanged()
            is Event.WsOutboundAckReceived -> onWsOutboundAck()
            is Event.WsAliveTickElapsed -> onAliveTick()
            is Event.ActiveOutboundAckTimeout -> onActiveOutboundAckTimeout(event)
            is Event.InboundIdleTimeout -> onInboundIdleTimeout(event)
        }
    }

    private fun onWsSessionEnded(event: Event.WsSessionEnded) {
        when (_state.value) {
            RestMode.WsActive -> {
                if (event.inboundFrames > 0) {
                    // Healthy session despite close — reset both counters.
                    if (activeFailCount > 0 || idleFailCount > 0) {
                        log(
                            "REST_TRACE counters_reset reason=healthy_session_close " +
                                "inbound_frames=${event.inboundFrames}",
                        )
                    }
                    activeFailCount = 0
                    idleFailCount = 0
                    return
                }
                // Zero inbound frames — classify by pending outbound and increment.
                if (event.pendingAcksAtClose > 0) {
                    activeFailCount += 1
                    log(
                        "REST_TRACE counter_tick kind=active count=$activeFailCount " +
                            "pending_acks=${event.pendingAcksAtClose} duration_ms=${event.durationMs}",
                    )
                    if (activeFailCount >= ACTIVE_FAIL_THRESHOLD) {
                        transitionToRest("active_outbound_threshold")
                    }
                } else {
                    idleFailCount += 1
                    log(
                        "REST_TRACE counter_tick kind=idle count=$idleFailCount " +
                            "duration_ms=${event.durationMs}",
                    )
                    if (idleFailCount >= IDLE_FAIL_THRESHOLD) {
                        transitionToRest("idle_threshold")
                    }
                }
            }
            RestMode.WsCandidate -> {
                // Regression: a candidate session closed before we could commit
                // back to WsActive. Drop back to RestActive and reset counters
                // so the next round of WS sessions starts fresh.
                transitionToRest("candidate_session_regression")
            }
            RestMode.RestActive -> {
                // Already in REST mode — a WS session close is expected
                // and irrelevant. We do continue counting nothing here:
                // counters only matter while in WsActive.
            }
        }
    }

    private fun onWsFrameText() {
        if (_state.value == RestMode.RestActive) {
            transitionToCandidate("ws_frame_text_received")
        }
        // In WsActive: no-op (frames are expected).
        // In WsCandidate: no-op (we already saw the upgrade signal).
    }

    private fun onNetworkChanged() {
        // Reset counters in any mode — a new network's behaviour is unknown
        // and stale counter state should not influence the next decisions.
        if (activeFailCount > 0 || idleFailCount > 0) {
            log("REST_TRACE counters_reset reason=network_changed")
        }
        activeFailCount = 0
        idleFailCount = 0
        if (_state.value == RestMode.RestActive) {
            transitionToCandidate("network_changed")
        }
    }

    private fun onWsOutboundAck() {
        if (_state.value == RestMode.WsCandidate) {
            transitionToWsActive("ws_outbound_ack")
        }
        // Any outbound ACK on WS counts as a healthy round-trip: reset
        // accumulated failure counters even when already in WsActive.
        activeFailCount = 0
        idleFailCount = 0
    }

    private fun onAliveTick() {
        if (_state.value != RestMode.WsCandidate) return
        val entered = candidateEnteredAtMs ?: return
        val elapsed = now() - entered
        if (elapsed >= CANDIDATE_COMMIT_MS) {
            transitionToWsActive("ws_alive_60s")
        }
    }

    /**
     * PR-D1d: a per-envelope ACK deadline expired — the relay did not
     * AckDeliver within [RelayTransportConfig.ACK_DEADLINE_MS] (10 s).
     *
     * - In [RestMode.WsActive]: immediate mode switch to REST. This is the
     *   fast-path that avoids waiting for two full session deaths
     *   (~60 s each) as the existing [ACTIVE_FAIL_THRESHOLD] requires.
     * - In [RestMode.RestActive] or [RestMode.WsCandidate]: no-op. The mode
     *   has already been switched or is in a transitional state; the timer
     *   event is stale and would add no information.
     */
    private fun onActiveOutboundAckTimeout(event: Event.ActiveOutboundAckTimeout) {
        when (_state.value) {
            RestMode.WsActive -> transitionToRest("active_outbound_ack_timeout")
            RestMode.RestActive, RestMode.WsCandidate -> {
                // Already migrated or in candidate — timer is stale. No-op,
                // no log (the spec says do NOT log in these states).
            }
        }
    }

    /**
     * PR-RECV-DIAG1 v1.6 — inbound-stall fallback. Test #84.7 proved a
     * real production-class gap: Tecno-side WS handshake succeeds, but
     * NO server-pushed frames arrive (no ack, no pong, no deliver). The
     * existing fallback triggers (WsSessionEnded count, outbound ACK
     * deadline) only fire after the user TRIES to send. If the user
     * only WAITS for incoming, the device stays stuck in WsActive
     * forever — for 60+120+180+ seconds with `inbound_frames=0`,
     * confirmed in test84_7-tecno.log idle_watchdog lines.
     *
     * This event is emitted by [KtorRelayTransport.startIdleWatchdog]
     * once per session when `sinceLastInbound >= INBOUND_STALL_THRESHOLD_MS`.
     * The handler forces the same WsActive → RestActive transition the
     * outbound ack timeout uses, so REST poll picks up the envelope
     * relay already mirrored via `mirror_envelope_to_rest_store`.
     *
     * In RestActive or WsCandidate it's a no-op: those modes already
     * have an active poll loop or are transitioning out, so the
     * inbound-stall signal carries no additional information.
     */
    private fun onInboundIdleTimeout(event: Event.InboundIdleTimeout) {
        when (_state.value) {
            RestMode.WsActive -> transitionToRest("inbound_idle_timeout")
            RestMode.RestActive, RestMode.WsCandidate -> {
                // Already migrated or in candidate — no-op.
            }
        }
    }

    // ── Transition helpers ───────────────────────────────────────────────────

    private fun transitionToRest(reason: String) {
        val from = _state.value
        if (from == RestMode.RestActive) return
        activeFailCount = 0
        idleFailCount = 0
        candidateEnteredAtMs = null
        _state.value = RestMode.RestActive
        log("REST_TRACE mode_switched from=$from to=REST_ACTIVE reason=$reason")
        onModeSwitched?.invoke(from, RestMode.RestActive, reason)
    }

    private fun transitionToCandidate(reason: String) {
        val from = _state.value
        if (from == RestMode.WsCandidate) return
        candidateEnteredAtMs = now()
        _state.value = RestMode.WsCandidate
        log("REST_TRACE mode_switched from=$from to=WS_CANDIDATE reason=$reason")
        onModeSwitched?.invoke(from, RestMode.WsCandidate, reason)
    }

    private fun transitionToWsActive(reason: String) {
        val from = _state.value
        if (from == RestMode.WsActive) return
        activeFailCount = 0
        idleFailCount = 0
        candidateEnteredAtMs = null
        _state.value = RestMode.WsActive
        log("REST_TRACE mode_switched from=$from to=WS_ACTIVE reason=$reason")
        onModeSwitched?.invoke(from, RestMode.WsActive, reason)
    }

    /**
     * Inputs the state machine consumes. Wire-up code in the orchestrator (and
     * tests) constructs these from observed transport events.
     */
    sealed class Event {
        /**
         * Emitted once per WS session at the moment the WebSocket closes
         * (any reason: protocol error, RST, server close, OS network drop).
         * [inboundFrames] is the count of Frame.Text the client received
         * over the just-ended session; [pendingAcksAtClose] is the number
         * of envelopes the client had sent but not yet received an ACK for
         * when the session ended.
         */
        data class WsSessionEnded(
            val durationMs: Long,
            val inboundFrames: Int,
            val pendingAcksAtClose: Int,
        ) : Event()

        /**
         * Emitted each time the WS layer surfaces ANY Frame.Text from the
         * relay — Deliver, Ack, application-level Pong-as-Text, malformed
         * payloads, anything. Used to detect "WS is alive enough to push
         * something" and exit [RestMode.RestActive] into the candidate
         * state. The actual content is irrelevant.
         */
        object WsFrameTextReceived : Event()

        /**
         * Emitted on Android `ConnectivityManager` capability changes
         * (Wi-Fi ↔ cellular, network gained/lost). Resets counters because
         * the new path's behaviour is unknown.
         */
        object NetworkChanged : Event()

        /**
         * Emitted when an outbound envelope completes its ACK round-trip
         * over the WS path. Strongest signal that the WS data plane works
         * bidirectionally; commits a candidate session into [RestMode.WsActive]
         * immediately.
         */
        object WsOutboundAckReceived : Event()

        /**
         * Periodic timer tick (driven by the orchestrator's polling/timer
         * loop) used by [RestMode.WsCandidate] to commit into
         * [RestMode.WsActive] after [CANDIDATE_COMMIT_MS] of continuous
         * uptime without a session close.
         */
        object WsAliveTickElapsed : Event()

        /**
         * PR-D1d: a per-envelope ACK deadline expired. [msgId] is the
         * messageId of the envelope that was not acknowledged by the relay
         * within [RelayTransportConfig.ACK_DEADLINE_MS] (10 s). [ageMs] is
         * the actual elapsed time at expiry (always >= ACK_DEADLINE_MS).
         *
         * Emitted by [KtorRelayTransport.outboundAckDeadlineExpired] and
         * forwarded here by [HybridRelayTransport]. Triggers an immediate
         * [RestMode.WsActive] → [RestMode.RestActive] transition, bypassing
         * the two-session-death requirement of the existing
         * [ACTIVE_FAIL_THRESHOLD] counter. The threshold counter continues
         * accumulating independently as a safety net.
         */
        data class ActiveOutboundAckTimeout(val msgId: String, val ageMs: Long) : Event()

        /**
         * PR-RECV-DIAG1 v1.6 — WS is open and was healthy enough for
         * handshake, but no inbound Frame.Text has been received for
         * [sinceLastInboundMs] >= [INBOUND_STALL_THRESHOLD_MS] (60 s).
         * This is the "half-dead inbound" production case observed in
         * test #84.7: Tecno's WS stays connected indefinitely, but
         * relay→Tecno frames (ack, pong, deliver) never arrive. The
         * existing fail-counter and outbound-ack-timeout paths can't
         * detect this case because no session-end happens and no
         * outbound is in flight.
         *
         * Emitted by [KtorRelayTransport.startIdleWatchdog] once per WS
         * session (re-armed on each new session via the per-session
         * pingJob restart). Forwarded by [HybridRelayTransport] into
         * the state machine. Handler: see [onInboundIdleTimeout].
         */
        data class InboundIdleTimeout(val sinceLastInboundMs: Long) : Event()
    }

    companion object {
        /**
         * From WsActive → RestActive when the client has pending outbound
         * acks (the user just sent something and is waiting for delivery
         * confirmation). Two consecutive zero-inbound sessions are enough
         * because the user-perceived delay of waiting for a third failure
         * is already 60-90 s of bad UX.
         */
        const val ACTIVE_FAIL_THRESHOLD: Int = 2

        /**
         * From WsActive → RestActive when the client has no pending outbound
         * acks (passive idle). Higher threshold because zero-inbound on idle
         * is less load-bearing — it might just mean nobody is sending us
         * anything. Three consecutive failures is the locked compromise.
         */
        const val IDLE_FAIL_THRESHOLD: Int = 3

        /**
         * From WsCandidate → WsActive: the candidate WS session must hold
         * for at least this many ms without a session close, OR receive an
         * outbound ACK round-trip, before committing fully. Test #48 showed
         * isolated successful Frame.Text on otherwise-broken sessions, so a
         * single Frame.Text is not enough to commit.
         */
        const val CANDIDATE_COMMIT_MS: Long = 60_000L
    }
}

/** Current REST fallback mode — fed back to UI for honest state labels. */
enum class RestMode {
    /** WebSocket healthy. Outbound and inbound flow over WS. REST polling stopped. */
    WsActive,

    /** WebSocket broken under current network. REST short-poll is the active path. */
    RestActive,

    /**
     * Transitional state: WS just produced a Frame.Text, but we're still
     * keeping REST polling alive as a safety net until WS proves stable
     * ([RestStateMachine.CANDIDATE_COMMIT_MS] of uptime OR an outbound ACK).
     */
    WsCandidate,
}
