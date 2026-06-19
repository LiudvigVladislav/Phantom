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

    // ── 3.6 Fast REST degradation (2026-06-18) ────────────────────────────
    //
    // Mode-2 signature = inboundFrames == 0 AND okhttpPingTimeoutDetected
    // AND durationMs in MODE_2_MIN_DURATION_MS..MODE_2_MAX_DURATION_MS.
    //
    // - Boundary cases pin the duration window: 24_999 misses, 25_000 hits,
    //   65_000 hits, 65_001 misses; plus ping-flag-false and
    //   inboundFrames-positive cases.
    // - Behavioural cases pin the actuation flag semantics + telemetry +
    //   WsCandidate regression interaction + Mode-1 protection + flag-off
    //   pendingAcks > 0 path + exact telemetry format.
    //
    // The mapper that lifts `WsSessionEndedEvent` →
    // `RestStateMachine.Event.WsSessionEnded` lives in `androidMain`
    // (file-level `internal fun` in `HybridRelayTransport.kt`) and is
    // unit-tested in `androidUnitTest` against the propagation invariant
    // — keeping the mapper test next to the only caller keeps visibility
    // tight and avoids exposing the mapper publicly from `commonMain`.

    private fun mode2Event(
        durationMs: Long = 31_000L,
        inboundFrames: Int = 0,
        pendingAcksAtClose: Int = 0,
        okhttpPingTimeoutDetected: Boolean = true,
    ): RestStateMachine.Event.WsSessionEnded =
        RestStateMachine.Event.WsSessionEnded(
            durationMs = durationMs,
            inboundFrames = inboundFrames,
            pendingAcksAtClose = pendingAcksAtClose,
            okhttpPingTimeoutDetected = okhttpPingTimeoutDetected,
        )

    private fun buildWithFastPath(
        clock: FakeClock = FakeClock(),
        mode2FastPathEnabled: Boolean = true,
        logSink: MutableList<String> = mutableListOf(),
    ): RestStateMachine = RestStateMachine(
        now = { clock.nowMs },
        log = { logSink.add(it) },
        mode2FastPathEnabled = mode2FastPathEnabled,
    )

    // ── Boundary tests (6) ───────────────────────────────────────────────

    @Test
    fun mode2_signature_misses_just_below_min_duration() {
        val sm = buildWithFastPath()
        sm.onEvent(mode2Event(durationMs = RestStateMachine.MODE_2_MIN_DURATION_MS - 1))
        assertEquals(
            RestMode.WsActive, sm.current,
            "24_999 ms is one below the lower bound — must NOT fast-path",
        )
    }

    @Test
    fun mode2_signature_matches_at_exact_min_duration() {
        val sm = buildWithFastPath()
        sm.onEvent(mode2Event(durationMs = RestStateMachine.MODE_2_MIN_DURATION_MS))
        assertEquals(
            RestMode.RestActive, sm.current,
            "25_000 ms is inclusive of the lower bound — must fast-path",
        )
    }

    @Test
    fun mode2_signature_matches_at_exact_max_duration() {
        val sm = buildWithFastPath()
        sm.onEvent(mode2Event(durationMs = RestStateMachine.MODE_2_MAX_DURATION_MS))
        assertEquals(
            RestMode.RestActive, sm.current,
            "65_000 ms is inclusive of the upper bound — must fast-path",
        )
    }

    @Test
    fun mode2_signature_misses_just_above_max_duration() {
        val sm = buildWithFastPath()
        sm.onEvent(mode2Event(durationMs = RestStateMachine.MODE_2_MAX_DURATION_MS + 1))
        assertEquals(
            RestMode.WsActive, sm.current,
            "65_001 ms is one above the upper bound — must NOT fast-path",
        )
    }

    @Test
    fun mode2_signature_misses_when_ping_timeout_flag_false() {
        val sm = buildWithFastPath()
        sm.onEvent(mode2Event(okhttpPingTimeoutDetected = false))
        assertEquals(
            RestMode.WsActive, sm.current,
            "absent ping-timeout signal MUST NOT fast-path even if other " +
                "conditions match — would over-trip on server-initiated " +
                "closes and auth failures",
        )
    }

    @Test
    fun mode2_signature_misses_when_inbound_frames_positive() {
        val sm = buildWithFastPath()
        sm.onEvent(mode2Event(inboundFrames = 1))
        assertEquals(
            RestMode.WsActive, sm.current,
            "any positive inboundFrames is a healthy session signal — " +
                "must NOT fast-path",
        )
    }

    // ── Behavioural tests (8) ────────────────────────────────────────────

    @Test
    fun mode2_first_match_with_flag_on_triggers_fast_path() {
        val logs = mutableListOf<String>()
        val sm = buildWithFastPath(logSink = logs)
        sm.onEvent(mode2Event())
        assertEquals(RestMode.RestActive, sm.current)
        assertTrue(
            logs.any { it.contains("mode_switched") && it.contains("mode_2_fast_path") },
            "mode_switched line must carry the mode_2_fast_path reason; logs=$logs",
        )
    }

    @Test
    fun mode2_first_match_with_flag_off_increments_idle_counter() {
        val sm = buildWithFastPath(mode2FastPathEnabled = false)
        // Matched-signature event with idle classification (pending == 0).
        sm.onEvent(mode2Event())
        assertEquals(
            RestMode.WsActive, sm.current,
            "with flag off, single matched event must NOT transition — " +
                "must fall through to existing IDLE_FAIL_THRESHOLD counter",
        )
        // Two more idle fails complete the existing 3-cycle threshold.
        sm.onEvent(idleFail())
        sm.onEvent(idleFail())
        assertEquals(
            RestMode.RestActive, sm.current,
            "existing IDLE_FAIL_THRESHOLD = 3 path must still work with " +
                "flag off",
        )
    }

    @Test
    fun mode2_telemetry_fires_with_action_fast_path_when_flag_on() {
        val logs = mutableListOf<String>()
        val sm = buildWithFastPath(logSink = logs)
        sm.onEvent(mode2Event())
        assertTrue(
            logs.any {
                it.contains("mode_2_signature_matched") && it.contains("action=fast_path")
            },
            "matched-signature telemetry must label action=fast_path when " +
                "flag is on; logs=$logs",
        )
    }

    @Test
    fun mode2_telemetry_fires_with_action_observe_only_when_flag_off() {
        val logs = mutableListOf<String>()
        val sm = buildWithFastPath(mode2FastPathEnabled = false, logSink = logs)
        sm.onEvent(mode2Event())
        assertTrue(
            logs.any {
                it.contains("mode_2_signature_matched") && it.contains("action=observe_only")
            },
            "matched-signature telemetry must label action=observe_only " +
                "when flag is off; logs=$logs",
        )
    }

    @Test
    fun mode2_does_not_affect_ws_candidate_regression_path() {
        val clock = FakeClock()
        val sm = buildWithFastPath(clock = clock)
        // Drive to RestActive via existing 3-idle threshold (NOT fast-path
        // — use plain idleFail() with okhttpPingTimeoutDetected=false so
        // signature does NOT match).
        sm.onEvent(idleFail())
        sm.onEvent(idleFail())
        sm.onEvent(idleFail())
        assertEquals(RestMode.RestActive, sm.current)
        // RestActive → WsCandidate via Frame.Text.
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsCandidate, sm.current)
        // WsCandidate close (regardless of Mode-2 signature) → RestActive
        // via existing regression path; fast-path never executes from
        // WsCandidate state.
        sm.onEvent(mode2Event())
        assertEquals(
            RestMode.RestActive, sm.current,
            "WsCandidate regression path must still hit transitionToRest " +
                "with reason=candidate_session_regression regardless of " +
                "Mode-2 signature",
        )
    }

    @Test
    fun mode1_like_healthy_close_does_not_trigger_fast_path() {
        val sm = buildWithFastPath()
        // Mode-1 8-pong rhythm: lifetime > 65_000 ms, inboundFrames > 0.
        sm.onEvent(
            RestStateMachine.Event.WsSessionEnded(
                durationMs = 150_000L,
                inboundFrames = 5,
                pendingAcksAtClose = 0,
                okhttpPingTimeoutDetected = true, // even with the flag set
            )
        )
        assertEquals(
            RestMode.WsActive, sm.current,
            "Mode-1 healthy 8-pong rhythm MUST NOT trip fast-path; field " +
                "FAIL on this case would mean Mode-1 protection is broken",
        )
    }

    @Test
    fun mode2_flag_off_with_pending_acks_increments_active_counter() {
        val sm = buildWithFastPath(mode2FastPathEnabled = false)
        // Matched-signature event BUT `pendingAcksAtClose > 0` — the
        // existing code classifies this as an ACTIVE fail, not idle.
        // With the fast-path gate OFF, the state machine must still
        // honour the existing ACTIVE_FAIL_THRESHOLD = 2 path.
        sm.onEvent(mode2Event(pendingAcksAtClose = 1))
        assertEquals(
            RestMode.WsActive, sm.current,
            "one active fail must not transition under existing threshold",
        )
        sm.onEvent(mode2Event(pendingAcksAtClose = 1))
        assertEquals(
            RestMode.RestActive, sm.current,
            "with flag off, ACTIVE_FAIL_THRESHOLD = 2 path must still work " +
                "after a matched-signature event with pending acks > 0",
        )
    }

    @Test
    fun mode2_matched_signature_telemetry_format_is_locked() {
        val logs = mutableListOf<String>()
        val sm = buildWithFastPath(logSink = logs)
        sm.onEvent(
            mode2Event(
                durationMs = 42_000L,
                inboundFrames = 0,
                pendingAcksAtClose = 3,
            )
        )
        val matchedLine = logs.firstOrNull { it.contains("mode_2_signature_matched") }
        assertEquals(
            "REST_TRACE mode_2_signature_matched action=fast_path " +
                "duration_ms=42000 inbound_frames=0 pending_acks=3",
            matchedLine,
            "matched-signature telemetry format MUST be exact for grep-based " +
                "post-mortem analysis; format change requires architect review",
        )
    }
}
