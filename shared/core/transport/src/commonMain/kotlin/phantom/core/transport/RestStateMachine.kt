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

    // ── Transition helpers ───────────────────────────────────────────────────

    private fun transitionToRest(reason: String) {
        val from = _state.value
        if (from == RestMode.RestActive) return
        activeFailCount = 0
        idleFailCount = 0
        candidateEnteredAtMs = null
        _state.value = RestMode.RestActive
        log("REST_TRACE mode_switched from=$from to=REST_ACTIVE reason=$reason")
    }

    private fun transitionToCandidate(reason: String) {
        val from = _state.value
        if (from == RestMode.WsCandidate) return
        candidateEnteredAtMs = now()
        _state.value = RestMode.WsCandidate
        log("REST_TRACE mode_switched from=$from to=WS_CANDIDATE reason=$reason")
    }

    private fun transitionToWsActive(reason: String) {
        val from = _state.value
        if (from == RestMode.WsActive) return
        activeFailCount = 0
        idleFailCount = 0
        candidateEnteredAtMs = null
        _state.value = RestMode.WsActive
        log("REST_TRACE mode_switched from=$from to=WS_ACTIVE reason=$reason")
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
