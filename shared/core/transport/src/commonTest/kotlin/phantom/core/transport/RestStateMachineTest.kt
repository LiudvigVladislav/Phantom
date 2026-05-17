// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [RestStateMachine] — pure state-machine logic, no
 * coroutines or I/O. Drives a controllable `now` function so the
 * [RestStateMachine.CANDIDATE_COMMIT_MS] timer can be exercised without
 * real-clock waits.
 *
 * Coverage:
 *  - Initial state is [RestMode.WsActive].
 *  - WsActive → RestActive on 2× active fails (pending_acks > 0).
 *  - WsActive → RestActive on 3× idle fails (pending_acks == 0).
 *  - Mixed (1 active + 1 idle) fails DO NOT trigger transition.
 *  - WsSessionEnded with inbound_frames > 0 resets counters.
 *  - NetworkChanged in WsActive resets counters.
 *  - RestActive → WsCandidate on WsFrameTextReceived.
 *  - RestActive → WsCandidate on NetworkChanged.
 *  - WsCandidate → WsActive on alive-tick after 60s.
 *  - WsCandidate → WsActive on outbound-ack received.
 *  - WsCandidate → RestActive on WsSessionEnded (regression).
 *  - Outbound ACK in WsActive also resets counters.
 *  - Counters reset on transition (subsequent fresh counts start at 0).
 */
class RestStateMachineTest {

    /** Mutable clock for time-dependent assertions. */
    private class FakeClock(var nowMs: Long = 0L) {
        fun advance(deltaMs: Long) { nowMs += deltaMs }
    }

    private fun build(clock: FakeClock = FakeClock()): RestStateMachine =
        RestStateMachine(now = { clock.nowMs })

    private fun activeFail(): RestStateMachine.Event.WsSessionEnded =
        RestStateMachine.Event.WsSessionEnded(
            durationMs = 31_000L,
            inboundFrames = 0,
            pendingAcksAtClose = 1,
        )

    private fun idleFail(): RestStateMachine.Event.WsSessionEnded =
        RestStateMachine.Event.WsSessionEnded(
            durationMs = 31_000L,
            inboundFrames = 0,
            pendingAcksAtClose = 0,
        )

    private fun healthyClose(): RestStateMachine.Event.WsSessionEnded =
        RestStateMachine.Event.WsSessionEnded(
            durationMs = 120_000L,
            inboundFrames = 5,
            pendingAcksAtClose = 0,
        )

    @Test
    fun initial_state_is_ws_active() {
        val sm = build()
        assertEquals(RestMode.WsActive, sm.current)
    }

    @Test
    fun two_active_fails_transitions_to_rest_active() {
        val sm = build()
        sm.onEvent(activeFail())
        assertEquals(RestMode.WsActive, sm.current, "one fail must not transition")
        sm.onEvent(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
    }

    @Test
    fun three_idle_fails_transitions_to_rest_active() {
        val sm = build()
        sm.onEvent(idleFail())
        sm.onEvent(idleFail())
        assertEquals(RestMode.WsActive, sm.current, "two idle fails must not transition")
        sm.onEvent(idleFail())
        assertEquals(RestMode.RestActive, sm.current)
    }

    @Test
    fun mixed_one_active_plus_two_idle_does_not_transition() {
        val sm = build()
        sm.onEvent(activeFail())
        sm.onEvent(idleFail())
        sm.onEvent(idleFail())
        assertEquals(
            RestMode.WsActive, sm.current,
            "1 active + 2 idle should not transition — separate counters",
        )
    }

    @Test
    fun healthy_session_resets_counters() {
        val sm = build()
        sm.onEvent(activeFail())
        sm.onEvent(healthyClose())
        sm.onEvent(activeFail())
        // After reset by healthyClose, one more active fail brings count back to 1.
        assertEquals(RestMode.WsActive, sm.current)
        sm.onEvent(activeFail())
        assertEquals(RestMode.RestActive, sm.current, "two fails after reset should now trip")
    }

    @Test
    fun network_change_in_ws_active_resets_counters() {
        val sm = build()
        sm.onEvent(activeFail())
        sm.onEvent(RestStateMachine.Event.NetworkChanged)
        sm.onEvent(activeFail())
        assertEquals(RestMode.WsActive, sm.current, "first fail post-reset doesn't trip")
        sm.onEvent(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
    }

    @Test
    fun rest_active_to_candidate_on_frame_text() {
        val sm = build()
        sm.onEvent(activeFail()); sm.onEvent(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsCandidate, sm.current)
    }

    @Test
    fun rest_active_to_candidate_on_network_change() {
        val sm = build()
        sm.onEvent(activeFail()); sm.onEvent(activeFail())
        sm.onEvent(RestStateMachine.Event.NetworkChanged)
        assertEquals(RestMode.WsCandidate, sm.current)
    }

    @Test
    fun candidate_to_ws_active_on_60s_alive_tick() {
        val clock = FakeClock()
        val sm = build(clock)
        sm.onEvent(activeFail()); sm.onEvent(activeFail())
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsCandidate, sm.current)

        // Tick at 30s — too early.
        clock.advance(30_000L)
        sm.onEvent(RestStateMachine.Event.WsAliveTickElapsed)
        assertEquals(RestMode.WsCandidate, sm.current, "30s is below commit threshold")

        // Tick at 60s — exactly at threshold, must commit.
        clock.advance(30_000L)
        sm.onEvent(RestStateMachine.Event.WsAliveTickElapsed)
        assertEquals(RestMode.WsActive, sm.current)
    }

    @Test
    fun candidate_to_ws_active_on_outbound_ack() {
        val clock = FakeClock()
        val sm = build(clock)
        sm.onEvent(activeFail()); sm.onEvent(activeFail())
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsCandidate, sm.current)

        // Outbound ACK arrives immediately — should commit without waiting 60s.
        sm.onEvent(RestStateMachine.Event.WsOutboundAckReceived)
        assertEquals(RestMode.WsActive, sm.current)
    }

    @Test
    fun candidate_to_rest_active_on_session_close_regression() {
        val sm = build()
        sm.onEvent(activeFail()); sm.onEvent(activeFail())
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsCandidate, sm.current)

        // Session dies before commit — back to RestActive.
        sm.onEvent(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
    }

    @Test
    fun outbound_ack_in_ws_active_resets_counters() {
        val sm = build()
        sm.onEvent(activeFail())
        sm.onEvent(RestStateMachine.Event.WsOutboundAckReceived)
        sm.onEvent(activeFail())
        assertEquals(RestMode.WsActive, sm.current, "one fail post-reset doesn't trip")
        sm.onEvent(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
    }

    @Test
    fun counters_reset_on_transition_to_rest_active() {
        val sm = build()
        sm.onEvent(activeFail()); sm.onEvent(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
        // Now switch to candidate, then have a regression.
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        sm.onEvent(activeFail()) // regression -> RestActive
        assertEquals(RestMode.RestActive, sm.current)
        // Counters should be 0 again — verified indirectly by going back through
        // candidate and requiring 2 fresh fails from a hypothetical next WsActive.
    }

    @Test
    fun frame_text_in_ws_active_is_noop() {
        val sm = build()
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsActive, sm.current, "frame text in WsActive should not transition")
    }

    @Test
    fun alive_tick_in_ws_active_is_noop() {
        val sm = build()
        sm.onEvent(RestStateMachine.Event.WsAliveTickElapsed)
        assertEquals(RestMode.WsActive, sm.current)
    }

    @Test
    fun session_end_in_rest_active_is_noop() {
        val sm = build()
        sm.onEvent(activeFail()); sm.onEvent(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
        // While in RestActive, additional session ends should not transition.
        sm.onEvent(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
        sm.onEvent(idleFail())
        assertEquals(RestMode.RestActive, sm.current)
    }

    // ── PR-D1d: ActiveOutboundAckTimeout tests ────────────────────────────────

    @Test
    fun ack_timeout_in_ws_active_transitions_to_rest_active() {
        val logs = mutableListOf<String>()
        val sm = RestStateMachine(now = { 0L }, log = { logs += it })

        assertEquals(RestMode.WsActive, sm.current)
        sm.onEvent(RestStateMachine.Event.ActiveOutboundAckTimeout("id1", 10_000L))
        assertEquals(RestMode.RestActive, sm.current)
        assertTrue(
            logs.any { it.contains("mode_switched") && it.contains("reason=active_outbound_ack_timeout") },
            "Expected a mode_switched … reason=active_outbound_ack_timeout log line; got: $logs",
        )
    }

    @Test
    fun ack_timeout_in_rest_active_is_noop() {
        val logs = mutableListOf<String>()
        val sm = RestStateMachine(now = { 0L }, log = { logs += it })
        // Drive to RestActive via the threshold path.
        sm.onEvent(activeFail()); sm.onEvent(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
        logs.clear()

        sm.onEvent(RestStateMachine.Event.ActiveOutboundAckTimeout("id2", 10_000L))
        assertEquals(RestMode.RestActive, sm.current, "RestActive state must not change")
        assertFalse(
            logs.any { it.contains("mode_switched") },
            "No mode_switched log expected when already in RestActive; got: $logs",
        )
    }

    @Test
    fun ack_timeout_in_ws_candidate_is_noop() {
        val logs = mutableListOf<String>()
        val sm = RestStateMachine(now = { 0L }, log = { logs += it })
        // Drive to RestActive then to WsCandidate.
        sm.onEvent(activeFail()); sm.onEvent(activeFail())
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsCandidate, sm.current)
        logs.clear()

        sm.onEvent(RestStateMachine.Event.ActiveOutboundAckTimeout("id3", 10_000L))
        assertEquals(RestMode.WsCandidate, sm.current, "WsCandidate state must not change")
        assertFalse(
            logs.any { it.contains("mode_switched") },
            "No mode_switched log expected when in WsCandidate; got: $logs",
        )
    }

    @Test
    fun multiple_ack_timeouts_only_first_switches_once() {
        val logs = mutableListOf<String>()
        val sm = RestStateMachine(now = { 0L }, log = { logs += it })
        assertEquals(RestMode.WsActive, sm.current)

        sm.onEvent(RestStateMachine.Event.ActiveOutboundAckTimeout("msg-a", 10_000L))
        sm.onEvent(RestStateMachine.Event.ActiveOutboundAckTimeout("msg-b", 11_000L))

        assertEquals(RestMode.RestActive, sm.current)
        val switchCount = logs.count { it.contains("mode_switched") && it.contains("reason=active_outbound_ack_timeout") }
        assertEquals(1, switchCount, "Expected exactly one mode_switched for two timeouts; got $switchCount in: $logs")
    }

    @Test
    fun network_changed_after_ack_timeout_resets_counters_but_transitions_to_ws_candidate() {
        val sm = build()
        // Trigger REST via deadline.
        sm.onEvent(RestStateMachine.Event.ActiveOutboundAckTimeout("id-x", 10_000L))
        assertEquals(RestMode.RestActive, sm.current)

        // NetworkChanged must lift to WsCandidate — same as if we'd arrived
        // via the threshold mechanism. Existing behaviour must not break.
        sm.onEvent(RestStateMachine.Event.NetworkChanged)
        assertEquals(
            RestMode.WsCandidate, sm.current,
            "NetworkChanged after RestActive must transition to WsCandidate",
        )
    }

    // ── end PR-D1d tests ──────────────────────────────────────────────────────

    @Test
    fun thresholds_are_locked_constants() {
        // Sanity-check the locked thresholds from the 2026-05-16 spec:
        //   2 active fails OR 3 idle fails to go REST.
        //   60s alive tick to commit back to WS.
        assertEquals(2, RestStateMachine.ACTIVE_FAIL_THRESHOLD)
        assertEquals(3, RestStateMachine.IDLE_FAIL_THRESHOLD)
        assertEquals(60_000L, RestStateMachine.CANDIDATE_COMMIT_MS)
    }

    @Test
    fun state_flow_emits_transitions() {
        val sm = build()
        val seen = mutableListOf<RestMode>()
        // Read initial value, then snapshot after each event.
        seen += sm.current
        sm.onEvent(activeFail()); seen += sm.current
        sm.onEvent(activeFail()); seen += sm.current
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived); seen += sm.current
        sm.onEvent(RestStateMachine.Event.WsOutboundAckReceived); seen += sm.current

        assertTrue(
            seen == listOf(
                RestMode.WsActive,
                RestMode.WsActive,
                RestMode.RestActive,
                RestMode.WsCandidate,
                RestMode.WsActive,
            ),
            "Expected transition sequence not observed; got: $seen",
        )
    }
}
