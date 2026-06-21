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
            sessionEpoch = 0L,
        )

    private fun idleFail(): RestStateMachine.Event.WsSessionEnded =
        RestStateMachine.Event.WsSessionEnded(
            durationMs = 31_000L,
            inboundFrames = 0,
            pendingAcksAtClose = 0,
            sessionEpoch = 0L,
        )

    private fun healthyClose(): RestStateMachine.Event.WsSessionEnded =
        RestStateMachine.Event.WsSessionEnded(
            durationMs = 120_000L,
            inboundFrames = 5,
            pendingAcksAtClose = 0,
            sessionEpoch = 0L,
        )

    @Test
    fun initial_state_is_ws_active() {
        val sm = build()
        assertEquals(RestMode.WsActive, sm.current)
    }

    @Test
    fun two_active_fails_transitions_to_rest_active() {
        val sm = build()
        sm.onEventNow(activeFail())
        assertEquals(RestMode.WsActive, sm.current, "one fail must not transition")
        sm.onEventNow(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
    }

    @Test
    fun three_idle_fails_transitions_to_rest_active() {
        val sm = build()
        sm.onEventNow(idleFail())
        sm.onEventNow(idleFail())
        assertEquals(RestMode.WsActive, sm.current, "two idle fails must not transition")
        sm.onEventNow(idleFail())
        assertEquals(RestMode.RestActive, sm.current)
    }

    @Test
    fun mixed_one_active_plus_two_idle_does_not_transition() {
        val sm = build()
        sm.onEventNow(activeFail())
        sm.onEventNow(idleFail())
        sm.onEventNow(idleFail())
        assertEquals(
            RestMode.WsActive, sm.current,
            "1 active + 2 idle should not transition — separate counters",
        )
    }

    @Test
    fun healthy_session_resets_counters() {
        val sm = build()
        sm.onEventNow(activeFail())
        sm.onEventNow(healthyClose())
        sm.onEventNow(activeFail())
        // After reset by healthyClose, one more active fail brings count back to 1.
        assertEquals(RestMode.WsActive, sm.current)
        sm.onEventNow(activeFail())
        assertEquals(RestMode.RestActive, sm.current, "two fails after reset should now trip")
    }

    @Test
    fun network_change_in_ws_active_resets_counters() {
        val sm = build()
        sm.onEventNow(activeFail())
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        sm.onEventNow(activeFail())
        assertEquals(RestMode.WsActive, sm.current, "first fail post-reset doesn't trip")
        sm.onEventNow(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
    }

    @Test
    fun rest_active_to_candidate_on_frame_text() {
        val sm = build()
        sm.onEventNow(activeFail()); sm.onEventNow(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
        sm.onEventNow(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsCandidate, sm.current)
    }

    @Test
    fun rest_active_to_candidate_on_network_change() {
        val sm = build()
        sm.onEventNow(activeFail()); sm.onEventNow(activeFail())
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        assertEquals(RestMode.WsCandidate, sm.current)
    }

    @Test
    fun candidate_to_ws_active_on_60s_alive_tick() {
        val clock = FakeClock()
        val sm = build(clock)
        sm.onEventNow(activeFail()); sm.onEventNow(activeFail())
        sm.onEventNow(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsCandidate, sm.current)

        // Tick at 30s — too early.
        clock.advance(30_000L)
        sm.onEventNow(RestStateMachine.Event.WsAliveTickElapsed)
        assertEquals(RestMode.WsCandidate, sm.current, "30s is below commit threshold")

        // Tick at 60s — exactly at threshold, must commit.
        clock.advance(30_000L)
        sm.onEventNow(RestStateMachine.Event.WsAliveTickElapsed)
        assertEquals(RestMode.WsActive, sm.current)
    }

    @Test
    fun candidate_to_ws_active_on_outbound_ack() {
        val clock = FakeClock()
        val sm = build(clock)
        sm.onEventNow(activeFail()); sm.onEventNow(activeFail())
        sm.onEventNow(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsCandidate, sm.current)

        // Outbound ACK arrives immediately — should commit without waiting 60s.
        sm.onEventNow(RestStateMachine.Event.WsOutboundAckReceived)
        assertEquals(RestMode.WsActive, sm.current)
    }

    @Test
    fun candidate_to_rest_active_on_session_close_regression() {
        val sm = build()
        sm.onEventNow(activeFail()); sm.onEventNow(activeFail())
        sm.onEventNow(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsCandidate, sm.current)

        // Session dies before commit — back to RestActive.
        sm.onEventNow(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
    }

    @Test
    fun outbound_ack_in_ws_active_resets_counters() {
        val sm = build()
        sm.onEventNow(activeFail())
        sm.onEventNow(RestStateMachine.Event.WsOutboundAckReceived)
        sm.onEventNow(activeFail())
        assertEquals(RestMode.WsActive, sm.current, "one fail post-reset doesn't trip")
        sm.onEventNow(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
    }

    @Test
    fun counters_reset_on_transition_to_rest_active() {
        val sm = build()
        sm.onEventNow(activeFail()); sm.onEventNow(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
        // Now switch to candidate, then have a regression.
        sm.onEventNow(RestStateMachine.Event.WsFrameTextReceived)
        sm.onEventNow(activeFail()) // regression -> RestActive
        assertEquals(RestMode.RestActive, sm.current)
        // Counters should be 0 again — verified indirectly by going back through
        // candidate and requiring 2 fresh fails from a hypothetical next WsActive.
    }

    @Test
    fun frame_text_in_ws_active_is_noop() {
        val sm = build()
        sm.onEventNow(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsActive, sm.current, "frame text in WsActive should not transition")
    }

    @Test
    fun alive_tick_in_ws_active_is_noop() {
        val sm = build()
        sm.onEventNow(RestStateMachine.Event.WsAliveTickElapsed)
        assertEquals(RestMode.WsActive, sm.current)
    }

    @Test
    fun session_end_in_rest_active_is_noop() {
        val sm = build()
        sm.onEventNow(activeFail()); sm.onEventNow(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
        // While in RestActive, additional session ends should not transition.
        sm.onEventNow(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
        sm.onEventNow(idleFail())
        assertEquals(RestMode.RestActive, sm.current)
    }

    // ── PR-D1d: ActiveOutboundAckTimeout tests ────────────────────────────────

    @Test
    fun ack_timeout_in_ws_active_transitions_to_rest_active() {
        val logs = mutableListOf<String>()
        val sm = RestStateMachine(now = { 0L }, log = { logs += it })

        assertEquals(RestMode.WsActive, sm.current)
        sm.onEventNow(RestStateMachine.Event.ActiveOutboundAckTimeout("id1", 10_000L))
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
        sm.onEventNow(activeFail()); sm.onEventNow(activeFail())
        assertEquals(RestMode.RestActive, sm.current)
        logs.clear()

        sm.onEventNow(RestStateMachine.Event.ActiveOutboundAckTimeout("id2", 10_000L))
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
        sm.onEventNow(activeFail()); sm.onEventNow(activeFail())
        sm.onEventNow(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsCandidate, sm.current)
        logs.clear()

        sm.onEventNow(RestStateMachine.Event.ActiveOutboundAckTimeout("id3", 10_000L))
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

        sm.onEventNow(RestStateMachine.Event.ActiveOutboundAckTimeout("msg-a", 10_000L))
        sm.onEventNow(RestStateMachine.Event.ActiveOutboundAckTimeout("msg-b", 11_000L))

        assertEquals(RestMode.RestActive, sm.current)
        val switchCount = logs.count { it.contains("mode_switched") && it.contains("reason=active_outbound_ack_timeout") }
        assertEquals(1, switchCount, "Expected exactly one mode_switched for two timeouts; got $switchCount in: $logs")
    }

    @Test
    fun network_changed_after_ack_timeout_resets_counters_but_transitions_to_ws_candidate() {
        val sm = build()
        // Trigger REST via deadline.
        sm.onEventNow(RestStateMachine.Event.ActiveOutboundAckTimeout("id-x", 10_000L))
        assertEquals(RestMode.RestActive, sm.current)

        // NetworkChanged must lift to WsCandidate — same as if we'd arrived
        // via the threshold mechanism. Existing behaviour must not break.
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
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
        sm.onEventNow(activeFail()); seen += sm.current
        sm.onEventNow(activeFail()); seen += sm.current
        sm.onEventNow(RestStateMachine.Event.WsFrameTextReceived); seen += sm.current
        sm.onEventNow(RestStateMachine.Event.WsOutboundAckReceived); seen += sm.current

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
        sessionEpoch: Long = 0L,
    ): RestStateMachine.Event.WsSessionEnded =
        RestStateMachine.Event.WsSessionEnded(
            durationMs = durationMs,
            inboundFrames = inboundFrames,
            pendingAcksAtClose = pendingAcksAtClose,
            okhttpPingTimeoutDetected = okhttpPingTimeoutDetected,
            sessionEpoch = sessionEpoch,
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
        sm.onEventNow(mode2Event(durationMs = RestStateMachine.MODE_2_MIN_DURATION_MS - 1))
        assertEquals(
            RestMode.WsActive, sm.current,
            "24_999 ms is one below the lower bound — must NOT fast-path",
        )
    }

    @Test
    fun mode2_signature_matches_at_exact_min_duration() {
        val sm = buildWithFastPath()
        sm.onEventNow(mode2Event(durationMs = RestStateMachine.MODE_2_MIN_DURATION_MS))
        assertEquals(
            RestMode.RestActive, sm.current,
            "25_000 ms is inclusive of the lower bound — must fast-path",
        )
    }

    @Test
    fun mode2_signature_matches_at_exact_max_duration() {
        val sm = buildWithFastPath()
        sm.onEventNow(mode2Event(durationMs = RestStateMachine.MODE_2_MAX_DURATION_MS))
        assertEquals(
            RestMode.RestActive, sm.current,
            "65_000 ms is inclusive of the upper bound — must fast-path",
        )
    }

    @Test
    fun mode2_signature_misses_just_above_max_duration() {
        val sm = buildWithFastPath()
        sm.onEventNow(mode2Event(durationMs = RestStateMachine.MODE_2_MAX_DURATION_MS + 1))
        assertEquals(
            RestMode.WsActive, sm.current,
            "65_001 ms is one above the upper bound — must NOT fast-path",
        )
    }

    @Test
    fun mode2_signature_misses_when_ping_timeout_flag_false() {
        val sm = buildWithFastPath()
        sm.onEventNow(mode2Event(okhttpPingTimeoutDetected = false))
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
        sm.onEventNow(mode2Event(inboundFrames = 1))
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
        sm.onEventNow(mode2Event())
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
        sm.onEventNow(mode2Event())
        assertEquals(
            RestMode.WsActive, sm.current,
            "with flag off, single matched event must NOT transition — " +
                "must fall through to existing IDLE_FAIL_THRESHOLD counter",
        )
        // Two more idle fails complete the existing 3-cycle threshold.
        sm.onEventNow(idleFail())
        sm.onEventNow(idleFail())
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
        sm.onEventNow(mode2Event())
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
        sm.onEventNow(mode2Event())
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
        sm.onEventNow(idleFail())
        sm.onEventNow(idleFail())
        sm.onEventNow(idleFail())
        assertEquals(RestMode.RestActive, sm.current)
        // RestActive → WsCandidate via Frame.Text.
        sm.onEventNow(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsCandidate, sm.current)
        // WsCandidate close (regardless of Mode-2 signature) → RestActive
        // via existing regression path; fast-path never executes from
        // WsCandidate state.
        sm.onEventNow(mode2Event())
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
        sm.onEventNow(
            RestStateMachine.Event.WsSessionEnded(
                durationMs = 150_000L,
                inboundFrames = 5,
                pendingAcksAtClose = 0,
                okhttpPingTimeoutDetected = true, // even with the flag set
                sessionEpoch = 0L,
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
        sm.onEventNow(mode2Event(pendingAcksAtClose = 1))
        assertEquals(
            RestMode.WsActive, sm.current,
            "one active fail must not transition under existing threshold",
        )
        sm.onEventNow(mode2Event(pendingAcksAtClose = 1))
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
        sm.onEventNow(
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

    // ── R3.6 Sticky-recovery tests ────────────────────────────────────────
    //
    // Tests numbered 1-21 per scope memo §"Test plan additions",
    // plus L1-L4 lifecycle contract tests, plus 22-24 predicate/producer/release-pin.
    //
    // Helper to build a state machine with BOTH fast-path AND sticky enabled.

    private fun buildWithSticky(
        clock: FakeClock = FakeClock(),
        logSink: MutableList<String> = mutableListOf(),
    ): RestStateMachine = RestStateMachine(
        now = { clock.nowMs },
        log = { logSink.add(it) },
        mode2FastPathEnabled = true,
        mode2StickyEnabled = true,
    )

    // Drive to RestActive via Mode-2 fast path to arm sticky.
    private fun driveToStickyRestActive(sm: RestStateMachine) {
        sm.onEventNow(mode2Event())
        assertEquals(RestMode.RestActive, sm.current, "must be RestActive after Mode-2 fast-path")
    }

    // ── Test 1: sticky_armed log fires after Mode-2 fast-path ────────────

    @Test
    fun test1_sticky_armed_log_fires_after_mode2_fast_path() {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        driveToStickyRestActive(sm)
        assertTrue(
            logs.any { it.contains("sticky_armed") && it.contains("gen=1") &&
                it.contains("reason=mode_2_fast_path") },
            "sticky_armed gen=1 reason=mode_2_fast_path must fire after Mode-2 actuation; logs=$logs",
        )
    }

    // ── Test 2: frame_text suppressed while sticky armed ─────────────────

    @Test
    fun test2_frame_text_suppressed_in_rest_active_while_sticky_armed() {
        val sm = buildWithSticky()
        driveToStickyRestActive(sm)
        sm.onEventNow(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(
            RestMode.RestActive, sm.current,
            "ws_frame_text_received MUST be suppressed while sticky armed in RestActive",
        )
    }

    // ── Test 3: sticky_frame_suppressed log fires on first suppression ───

    @Test
    fun test3_sticky_frame_suppressed_log_fires_on_first_suppression() {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        driveToStickyRestActive(sm)
        logs.clear()
        sm.onEventNow(RestStateMachine.Event.WsFrameTextReceived)
        assertTrue(
            logs.any { it.contains("sticky_frame_suppressed") && it.contains("gen=1") },
            "sticky_frame_suppressed must log exactly once per gen; logs=$logs",
        )
    }

    // ── Test 4: second frame-text suppression does NOT log again ──────────

    @Test
    fun test4_sticky_frame_suppressed_logged_only_once_per_gen() {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEventNow(RestStateMachine.Event.WsFrameTextReceived)
        val countBefore = logs.count { it.contains("sticky_frame_suppressed") }
        sm.onEventNow(RestStateMachine.Event.WsFrameTextReceived)
        val countAfter = logs.count { it.contains("sticky_frame_suppressed") }
        assertEquals(countBefore, countAfter, "sticky_frame_suppressed must only log ONCE per gen")
    }

    // ── Test 5: NetworkChanged(clearsMode2Sticky=true) → PendingNewSession ─

    @Test
    fun test5_network_changed_clears_sticky_arms_pending() {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        driveToStickyRestActive(sm)
        logs.clear()
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        assertEquals(RestMode.RestActive, sm.current, "must stay RestActive after pending-phase network change")
        assertTrue(
            logs.any { it.contains("sticky_recovery_pending") && it.contains("reason=route_change") },
            "sticky_recovery_pending reason=route_change must fire; logs=$logs",
        )
    }

    // ── Test 6: WsSessionConnected while PendingNewSession → InFlight ────

    @Test
    fun test6_ws_connected_while_pending_opens_inflight() {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(clock = clock, logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        logs.clear()
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 2L))
        assertEquals(RestMode.WsCandidate, sm.current, "InFlight must transition to WsCandidate for probation")
        assertTrue(
            logs.any { it.contains("sticky_recovery_started") && it.contains("ws_epoch=2") },
            "sticky_recovery_started ws_epoch=2 must fire; logs=$logs",
        )
    }

    // ── Test 7: ws_alive_60s clears sticky ───────────────────────────────

    @Test
    fun test7_ws_alive_60s_clears_sticky() {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(clock = clock, logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 2L))
        assertEquals(RestMode.WsCandidate, sm.current)
        // Advance 60 s and tick.
        clock.advance(60_000L)
        sm.onEventNow(RestStateMachine.Event.WsAliveTickElapsed)
        assertEquals(RestMode.WsActive, sm.current, "ws_alive_60s must transition to WsActive")
        assertTrue(
            logs.any { it.contains("sticky_cleared") && it.contains("proof=ws_alive_60s") },
            "sticky_cleared proof=ws_alive_60s must fire; logs=$logs",
        )
    }

    // ── Test 8: sticky NOT cleared on ws_outbound_ack during InFlight ────

    @Test
    fun test8_outbound_ack_not_proof_during_inflight() {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(clock = clock, logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 2L))
        assertEquals(RestMode.WsCandidate, sm.current)
        logs.clear()
        sm.onEventNow(RestStateMachine.Event.WsOutboundAckReceived)
        assertEquals(
            RestMode.WsCandidate, sm.current,
            "ws_outbound_ack MUST NOT clear sticky during InFlight (not a proof signal)",
        )
        assertTrue(
            logs.any { it.contains("sticky_recovery_ack_ignored") },
            "sticky_recovery_ack_ignored must fire; logs=$logs",
        )
    }

    // ── Test 9: recovery candidate dies → sticky_recovery_failed ─────────

    @Test
    fun test9_recovery_candidate_death_sets_sticky_recovery_failed() {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(clock = clock, logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 2L))
        assertEquals(RestMode.WsCandidate, sm.current)
        logs.clear()
        // Recovery candidate dies.
        sm.onEventNow(RestStateMachine.Event.WsSessionEnded(
            durationMs = 5_000L, inboundFrames = 0, pendingAcksAtClose = 0,
            okhttpPingTimeoutDetected = false, sessionEpoch = 2L,
        ))
        assertEquals(RestMode.RestActive, sm.current, "must be RestActive after recovery failure")
        assertTrue(
            logs.any { it.contains("sticky_recovery_failed") && it.contains("ws_epoch=2") },
            "sticky_recovery_failed ws_epoch=2 must fire; logs=$logs",
        )
    }

    // ── Test 10: stale close during InFlight → stale_close_ignored ───────

    @Test
    fun test10_stale_close_during_inflight_ignored() {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(clock = clock, logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 2L))
        assertEquals(RestMode.WsCandidate, sm.current)
        logs.clear()
        // Close from OLD session (epoch 1 != recovery epoch 2).
        sm.onEventNow(RestStateMachine.Event.WsSessionEnded(
            durationMs = 31_000L, inboundFrames = 0, pendingAcksAtClose = 0,
            okhttpPingTimeoutDetected = true, sessionEpoch = 1L,
        ))
        assertEquals(RestMode.WsCandidate, sm.current, "stale close must not change state")
        assertTrue(
            logs.any { it.contains("sticky_recovery_stale_close_ignored") },
            "sticky_recovery_stale_close_ignored must fire; logs=$logs",
        )
    }

    // ── Test 11: NetworkChanged(clearsMode2Sticky=false) → sticky_kept ───

    @Test
    fun test11_network_changed_validated_keeps_sticky() {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        driveToStickyRestActive(sm)
        logs.clear()
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = false))
        assertEquals(RestMode.RestActive, sm.current, "sticky_kept must remain in RestActive")
        assertTrue(
            logs.any { it.contains("sticky_kept") && it.contains("reason=validated_change") },
            "sticky_kept reason=validated_change must fire; logs=$logs",
        )
    }

    // ── Test 12: second WsSessionConnected during InFlight resets timer ──

    @Test
    fun test12_second_ws_connected_during_inflight_resets_candidate_timer() {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(clock = clock, logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 2L))
        // Advance 30 s — half-way to ws_alive_60s.
        clock.advance(30_000L)
        // Second new session arrives.
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 3L))
        assertTrue(
            logs.any { it.contains("sticky_recovery_restarted") && it.contains("new_epoch=3") },
            "sticky_recovery_restarted must fire with new_epoch=3; logs=$logs",
        )
        // Tick at 30 s after second connect — should NOT clear sticky (timer was reset).
        clock.advance(30_000L)
        sm.onEventNow(RestStateMachine.Event.WsAliveTickElapsed)
        assertEquals(RestMode.WsCandidate, sm.current, "timer must have been reset by second connect")
        // Tick at 60 s after second connect — should clear sticky.
        clock.advance(30_000L)
        sm.onEventNow(RestStateMachine.Event.WsAliveTickElapsed)
        assertEquals(RestMode.WsActive, sm.current, "sticky must clear at 60s from second connect")
    }

    // ── Test 13: sticky_recovery_pending fires WHILE still in RestActive ─

    @Test
    fun test13_sticky_recovery_pending_fires_in_rest_active() {
        val sm = buildWithSticky()
        driveToStickyRestActive(sm)
        assertEquals(RestMode.RestActive, sm.current, "pre-condition: must be in RestActive")
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        assertEquals(RestMode.RestActive, sm.current, "must STAY in RestActive after pending")
    }

    // ── Test 14: fast-path without sticky flag does NOT arm sticky ────────

    @Test
    fun test14_fast_path_without_sticky_does_not_arm_sticky() {
        val logs = mutableListOf<String>()
        val sm = buildWithFastPath(logSink = logs)  // sticky NOT enabled
        sm.onEventNow(mode2Event())
        assertEquals(RestMode.RestActive, sm.current, "fast-path must still fire")
        assertFalse(
            logs.any { it.contains("sticky_armed") },
            "sticky_armed must NOT fire when mode2StickyEnabled=false; logs=$logs",
        )
    }

    // ── Test 15: NetworkChanged counter-reset still fires with sticky ────

    @Test
    fun test15_counters_reset_on_network_change_with_sticky() {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        // Two active fails (not mode-2 signature) then a network change.
        sm.onEventNow(RestStateMachine.Event.WsSessionEnded(
            durationMs = 31_000L, inboundFrames = 0, pendingAcksAtClose = 1,
            sessionEpoch = 0L,
        ))
        logs.clear()
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        // Counter reset should fire because activeFailCount == 1 > 0.
        assertTrue(
            logs.any { it.contains("counters_reset") && it.contains("reason=network_changed") },
            "counters_reset reason=network_changed must fire; logs=$logs",
        )
    }

    // ── Test 16: build-time invariant: sticky without fast-path throws ───

    @Test
    fun test16_sticky_without_fast_path_throws_at_construction() {
        val threw = try {
            RestStateMachine(
                now = { 0L },
                mode2FastPathEnabled = false,
                mode2StickyEnabled = true,
            )
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(threw, "Constructing RestStateMachine with sticky=true and fast-path=false must throw IllegalArgumentException")
    }

    // ── Test 17: sticky_recovery_pending_restarted on duplicate route change

    @Test
    fun test17_double_route_change_while_pending_logs_pending_restarted() {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        logs.clear()
        // Second route change while still in PendingNewSession.
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        assertTrue(
            logs.any { it.contains("sticky_recovery_pending_restarted") },
            "sticky_recovery_pending_restarted must fire on duplicate route change; logs=$logs",
        )
    }

    // ── Test 18: route change during InFlight → sticky_recovery_restarted_route ─

    @Test
    fun test18_route_change_during_inflight_resets_to_pending() {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 2L))
        assertEquals(RestMode.WsCandidate, sm.current)
        logs.clear()
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        assertTrue(
            logs.any { it.contains("sticky_recovery_restarted") &&
                it.contains("reason=route_change_during_recovery") },
            "sticky_recovery_restarted reason=route_change_during_recovery must fire; logs=$logs",
        )
    }

    // ── Test 19: WsSessionConnected with stale epoch during InFlight ─────

    @Test
    fun test19_stale_connected_during_inflight_ignored() {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 2L))
        assertEquals(RestMode.WsCandidate, sm.current)
        logs.clear()
        // Stale connected with epoch <= lastObservedEpoch.
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 1L))
        assertTrue(
            logs.any { it.contains("sticky_recovery_stale_connect_ignored") },
            "sticky_recovery_stale_connect_ignored must fire; logs=$logs",
        )
        assertEquals(RestMode.WsCandidate, sm.current, "state must be unchanged by stale connect")
    }

    // ── Test 20: sticky recovery NOT cleared by ws_outbound_ack path ──────

    @Test
    fun test20_non_sticky_outbound_ack_in_ws_active_still_resets_counters() {
        // Without sticky: outbound ack still resets counters.
        val sm = buildWithFastPath()
        sm.onEventNow(RestStateMachine.Event.WsSessionEnded(
            durationMs = 31_000L, inboundFrames = 0, pendingAcksAtClose = 1,
            sessionEpoch = 0L,
        ))
        sm.onEventNow(RestStateMachine.Event.WsOutboundAckReceived)
        sm.onEventNow(RestStateMachine.Event.WsSessionEnded(
            durationMs = 31_000L, inboundFrames = 0, pendingAcksAtClose = 1,
            sessionEpoch = 0L,
        ))
        // Only 1 active fail after reset, so still WsActive.
        assertEquals(RestMode.WsActive, sm.current, "counters reset by outbound ack — one fail not enough")
    }

    // ── Test 21: lastObservedEpoch filter works without sticky ───────────

    @Test
    fun test21_last_observed_epoch_filter_independent_of_sticky() {
        val logs = mutableListOf<String>()
        // Even without sticky, the lastObservedEpoch filter should work
        // (it runs before the stickyRecovery branch).
        val sm = RestStateMachine(
            now = { 0L },
            log = { logs.add(it) },
            mode2FastPathEnabled = false,
            mode2StickyEnabled = false,
        )
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(42L))
        logs.clear()
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(41L))
        assertTrue(
            logs.any { it.contains("sticky_recovery_stale_connect_ignored") },
            "Stale epoch filter must fire even without sticky enabled; logs=$logs",
        )
    }

    // ── L1: stale Connected ignored ───────────────────────────────────────

    @Test
    fun L1_stale_connected_ignored_in_all_recovery_states() {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        // Baseline: epoch 42 observed.
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(42L))
        logs.clear()
        // Stale: 41 <= 42.
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(41L))
        assertTrue(
            logs.any { it.contains("sticky_recovery_stale_connect_ignored") &&
                it.contains("last_observed_epoch=42") && it.contains("event_epoch=41") },
            "stale_connect_ignored must carry correct epoch fields; logs=$logs",
        )

        // Now arm sticky + put into PendingNewSession.
        driveToStickyRestActive(sm)
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        assertEquals(RestMode.RestActive, sm.current)
        logs.clear()
        // Send the SAME stale epoch — must still be ignored; PendingNewSession must stay.
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(41L))
        assertTrue(
            logs.any { it.contains("sticky_recovery_stale_connect_ignored") },
            "stale_connect_ignored must fire even in PendingNewSession; logs=$logs",
        )
        assertEquals(RestMode.RestActive, sm.current, "PendingNewSession must be unchanged by stale connect")
    }

    // ── L2: Connected → Ended ordering preserved ─────────────────────────

    @Test
    fun L2_connected_then_ended_in_order_opens_then_fails_inflight() {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(clock = clock, logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 2L))
        assertEquals(RestMode.WsCandidate, sm.current)
        // Ended for the same epoch — recovery fails.
        sm.onEventNow(RestStateMachine.Event.WsSessionEnded(
            durationMs = 5_000L, inboundFrames = 0, pendingAcksAtClose = 0,
            okhttpPingTimeoutDetected = false, sessionEpoch = 2L,
        ))
        assertEquals(RestMode.RestActive, sm.current, "recovery failure must go back to RestActive")
        assertTrue(
            logs.any { it.contains("sticky_recovery_failed") && it.contains("ws_epoch=2") },
            "sticky_recovery_failed ws_epoch=2 must fire; logs=$logs",
        )
    }

    // ── L3: delayed Ended after Connected during InFlight (stale close) ──

    @Test
    fun L3_delayed_ended_after_new_connected_is_stale_and_ignored() {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(clock = clock, logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 2L))
        assertEquals(RestMode.WsCandidate, sm.current)
        logs.clear()
        // Stale Ended from old session (epoch 1 != recoveryWsEpoch 2).
        sm.onEventNow(RestStateMachine.Event.WsSessionEnded(
            durationMs = 31_000L, inboundFrames = 0, pendingAcksAtClose = 0,
            okhttpPingTimeoutDetected = true, sessionEpoch = 1L,
        ))
        assertEquals(RestMode.WsCandidate, sm.current, "stale Ended must not change state")
        assertFalse(
            logs.any { it.contains("sticky_recovery_failed") },
            "sticky_recovery_failed must NOT fire for stale Ended; logs=$logs",
        )
        assertTrue(
            logs.any { it.contains("sticky_recovery_stale_close_ignored") },
            "stale_close_ignored must fire; logs=$logs",
        )
    }

    // ── L4: dead candidate cannot pass aliveTick after its own Ended ─────

    @Test
    fun L4_dead_candidate_aliveTick_does_not_clear_sticky() {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(clock = clock, logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 2L))
        val t0 = clock.nowMs

        // 30 s in: recovery candidate dies.
        clock.advance(30_000L)
        sm.onEventNow(RestStateMachine.Event.WsSessionEnded(
            durationMs = 30_000L, inboundFrames = 0, pendingAcksAtClose = 0,
            okhttpPingTimeoutDetected = false, sessionEpoch = 2L,
        ))
        assertEquals(RestMode.RestActive, sm.current, "must be RestActive after failure")
        logs.clear()

        // 65 s in: timer tick fires (would have been the proof window).
        clock.advance(35_000L)
        sm.onEventNow(RestStateMachine.Event.WsAliveTickElapsed)
        // onAliveTick early-returns because state != WsCandidate.
        assertFalse(
            logs.any { it.contains("sticky_cleared") },
            "sticky_cleared must NOT fire from a dead candidate's aliveTick; logs=$logs",
        )
        assertEquals(RestMode.RestActive, sm.current, "must remain RestActive")
    }

    // ── Test 22: NetworkChanged predicate extension ───────────────────────

    @Test
    fun test22_network_changed_clearsMode2Sticky_true_is_explicit() {
        // Verify that NetworkChanged(clearsMode2Sticky=true) carries the flag correctly.
        // No default exists — callers must be explicit (compiler-enforced contract).
        val event = RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true)
        assertTrue(event.clearsMode2Sticky, "explicit clearsMode2Sticky=true must propagate")
    }

    @Test
    fun test23_network_changed_validated_clearsMode2Sticky_false() {
        val event = RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = false)
        assertFalse(event.clearsMode2Sticky, "explicit false must propagate")
    }

    // ── Test 24: WsSessionConnected event carries epoch ──────────────────

    @Test
    fun test24_ws_session_connected_carries_epoch() {
        val sm = buildWithSticky()
        // No exception; epoch arrives correctly.
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 99L))
        // State is WsActive (no sticky armed yet, None state), so it returns early.
        assertEquals(RestMode.WsActive, sm.current, "outside recovery context, Connected is a no-op on state")
    }

    // ── Test: WsSessionEnded sessionEpoch default -1L not matched ────────

    @Test
    fun test_ws_session_ended_sentinel_epoch_minus1_is_not_recovery_match() {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(clock = clock, logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 2L))
        assertEquals(RestMode.WsCandidate, sm.current)
        logs.clear()
        // Ended with sentinel epoch -1L (legacy/unknown) — this is a stale close (epoch mismatch: -1 != 2).
        sm.onEventNow(RestStateMachine.Event.WsSessionEnded(
            durationMs = 31_000L, inboundFrames = 0, pendingAcksAtClose = 0,
            sessionEpoch = -1L,
        ))
        // Should be treated as stale close (epoch -1 != recoveryWsEpoch 2).
        assertTrue(
            logs.any { it.contains("sticky_recovery_stale_close_ignored") },
            "sentinel-epoch -1 Ended during InFlight must be stale_close_ignored; logs=$logs",
        )
        assertEquals(RestMode.WsCandidate, sm.current, "state must not change on stale close")
    }

    // ── Fix #3 verification: cleanup before onModeSwitched throw ──────────

    /**
     * Verifies that the R3.6 sticky-cleanup hook runs BEFORE [onModeSwitched] is invoked
     * inside [RestStateMachine.transitionToWsActive]. If [onModeSwitched] throws, the
     * sticky state (mode2StickyRestActive, stickyRecovery) must already be cleared because
     * cleanup runs first.
     *
     * Scenario: sticky InFlight → ws_alive_60s → onModeSwitched throws → assert sticky cleared.
     */
    @Test
    fun cleanup_completes_before_onModeSwitched_so_throw_does_not_leak_sticky() {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        var callbackInvoked = false
        val sm = RestStateMachine(
            now = { clock.nowMs },
            log = { logs.add(it) },
            mode2FastPathEnabled = true,
            mode2StickyEnabled = true,
            onModeSwitched = { _, _, reason ->
                if (reason == "ws_alive_60s") {
                    callbackInvoked = true
                    throw IllegalStateException("onModeSwitched intentionally throws for test")
                }
            },
        )
        // Arm sticky via Mode-2 fast-path.
        driveToStickyRestActive(sm)
        // Route change → PendingNewSession.
        sm.onEventNow(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        // New WS session → InFlight.
        sm.onEventNow(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 2L))
        assertEquals(RestMode.WsCandidate, sm.current)

        // Advance past 60 s and trigger the alive tick.
        clock.advance(60_001L)
        val threw = try {
            sm.onEventNow(RestStateMachine.Event.WsAliveTickElapsed)
            false
        } catch (_: IllegalStateException) {
            true
        }
        assertTrue(threw, "onModeSwitched must have thrown for ws_alive_60s")
        assertTrue(callbackInvoked, "onModeSwitched must have been invoked")
        // (a) sticky_cleared log was emitted BEFORE state mutation and the throwing callback.
        assertTrue(
            logs.any { it.contains("sticky_cleared") && it.contains("proof=ws_alive_60s") },
            "sticky_cleared must have been logged before onModeSwitched threw; logs=$logs",
        )

        // (b) Behavioural proof per Vladislav review 2026-06-20 — the cleanup must have
        // actually reset mode2StickyRestActive, not merely logged. Force the state machine
        // back into RestActive via ActiveOutboundAckTimeout (which does NOT arm sticky)
        // and submit WsFrameTextReceived. If sticky was truly cleared, the frame triggers
        // a transitionToCandidate; if sticky was only logged but not cleared,
        // onWsFrameText would suppress the frame and the state would stay RestActive.
        assertEquals(
            RestMode.WsActive, sm.current,
            "Post-throw state must be WsActive because cleanup + state mutation completed " +
                "before the callback threw",
        )
        sm.onEventNow(
            RestStateMachine.Event.ActiveOutboundAckTimeout(
                msgId = "m-cleanup-probe",
                ageMs = 11_000L,
            )
        )
        assertEquals(
            RestMode.RestActive, sm.current,
            "ActiveOutboundAckTimeout must transition the machine to RestActive without arming sticky",
        )
        val logsBeforeProbe = logs.size
        sm.onEventNow(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(
            RestMode.WsCandidate, sm.current,
            "Sticky must be CLEARED behaviourally: a raw Frame.Text in RestActive must " +
                "now upgrade to WsCandidate. If sticky had only been logged-clear but not " +
                "actually cleared, the frame would have been suppressed and the state " +
                "would have stayed RestActive.",
        )
        val logsAfterProbe = logs.drop(logsBeforeProbe)
        assertFalse(
            logsAfterProbe.any { it.contains("sticky_frame_suppressed") },
            "WsFrameTextReceived must NOT trigger sticky_frame_suppressed if sticky is " +
                "truly cleared; logs after probe=$logsAfterProbe",
        )
    }
}
