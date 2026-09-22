// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RestStateMachine] — pure logic, an injected clock, no I/O.
 *
 * Stage 2 (2026-09-13) replaced the counter-and-dwell rules with rules
 * about a SESSION. What each group pins:
 *
 *  - **Freshness (B2).** Only the live session's signals are handled; a
 *    stale one can neither raise nor lower the mode. Every test here that
 *    ends in "…_ignored" or "…_does_not_…" is a discriminating case: the
 *    pre-Stage-2 machine, which saw untagged frames, acks, ticks, stalls
 *    and ACK deadlines, would fail it.
 *  - **Proof (B3).** `WsActive` needs an ACK round-trip on the live
 *    session, or one inbound frame on it plus the commit dwell confirmed
 *    by a tick naming that same session. Wall-clock dwell alone — the
 *    `ws_alive_60s` promotion that put the phone in `Offline` at 19:08
 *    over a socket that never connected — no longer promotes anything.
 *  - **Degradation.** One END of the live session drops the mode (the
 *    two- and three-strike counters are gone); a stall or an ACK deadline
 *    of the live session drops it too but KEEPS the session, because a
 *    silent socket is not a gone socket.
 *  - The R3.6 sticky window and the quiescence gate keep their contracts
 *    behind their build flags, with the B3 proof added on top (B10).
 */
class RestStateMachineTest {

    /** Mutable clock for time-dependent assertions. */
    private class FakeClock(var nowMs: Long = 0L) {
        fun advance(deltaMs: Long) { nowMs += deltaMs }
    }

    private fun build(clock: FakeClock = FakeClock(), logSink: MutableList<String>? = null): RestStateMachine =
        RestStateMachine(now = { clock.nowMs }, log = { logSink?.add(it) ?: Unit })

    private fun ended(
        sessionEpoch: Long,
        durationMs: Long = 31_000L,
        inboundFrames: Int = 0,
        pendingAcksAtClose: Int = 1,
        okhttpPingTimeoutDetected: Boolean = false,
    ) = RestStateMachine.Event.WsSessionEnded(
        durationMs = durationMs,
        inboundFrames = inboundFrames,
        pendingAcksAtClose = pendingAcksAtClose,
        okhttpPingTimeoutDetected = okhttpPingTimeoutDetected,
        sessionEpoch = sessionEpoch,
    )

    private fun connected(epoch: Long, generation: Long = -1L) =
        RestStateMachine.Event.WsSessionConnected(sessionEpoch = epoch, connectionGeneration = generation)

    private fun frame(epoch: Long) = RestStateMachine.Event.WsFrameTextReceived(epoch)
    private fun ack(epoch: Long) = RestStateMachine.Event.WsOutboundAckReceived(epoch)
    private fun pong(epoch: Long) = RestStateMachine.Event.WsPongReceived(epoch)
    private fun candidateProof(epoch: Long) =
        RestStateMachine.Event.WsCandidateProbeRoundTrip(epoch)
    private fun tick(epoch: Long) = RestStateMachine.Event.WsAliveTickElapsed(epoch)
    private fun stalled(epoch: Long, sinceMs: Long = 60_000L) =
        RestStateMachine.Event.InboundIdleTimeout(sessionEpoch = epoch, sinceLastInboundMs = sinceMs)
    private fun ackDeadline(epoch: Long, id: String = "env-1") =
        RestStateMachine.Event.ActiveOutboundAckTimeout(sessionEpoch = epoch, msgId = id, ageMs = 10_000L)

    // ── Cold start and the trusted first connect ─────────────────────────────

    @Test
    fun the_machine_starts_in_rest_active_with_no_session_and_no_proof() = runTest {
        val sm = build()
        assertEquals(
            RestMode.RestActive, sm.current,
            "`WsActive` is the mode that STOPS REST polling; nothing has earned that yet",
        )
        assertNull(sm.liveSessionEpoch)
        assertNull(sm.provenSessionEpoch)
    }

    @Test
    fun the_first_connect_is_a_candidate_not_a_proof() = runTest {
        val sm = build()
        sm.onEvent(connected(1))
        assertEquals(
            RestMode.WsCandidate, sm.current,
            "a handshake has never been evidence that the socket carries traffic",
        )
        assertEquals(1L, sm.liveSessionEpoch)
        assertNull(sm.provenSessionEpoch, "the first session proves itself like every other one")
    }

    @Test
    fun the_first_session_reaches_ws_active_only_through_the_proof() = runTest {
        val clock = FakeClock()
        val sm = build(clock)
        sm.onEvent(connected(1))
        clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS * 2)
        sm.onEvent(tick(1))
        assertEquals(RestMode.WsCandidate, sm.current, "dwell alone proves nothing")
        sm.onEvent(frame(1))
        clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS)
        sm.onEvent(tick(1))
        assertEquals(RestMode.WsActive, sm.current)
        assertEquals(1L, sm.provenSessionEpoch)
    }

    @Test
    fun a_later_session_must_prove_itself_too() = runTest {
        val sm = build()
        sm.onEvent(connected(1))
        sm.onEvent(ended(1))
        assertEquals(RestMode.RestActive, sm.current)
        sm.onEvent(connected(2))
        assertEquals(
            RestMode.WsCandidate, sm.current,
            "a session after a degradation is a candidate, not proof",
        )
        assertNull(sm.provenSessionEpoch)
    }

    // ── Freshness (B2) ───────────────────────────────────────────────────────

    @Test
    fun connected_2_then_delayed_connected_1_keeps_session_2() = runTest {
        val logs = mutableListOf<String>()
        val sm = build(logSink = logs)
        sm.onEvent(connected(2))
        sm.onEvent(connected(1))
        assertEquals(2L, sm.liveSessionEpoch)
        assertTrue(logs.any { it.contains("stale_connect_ignored") }, "logs=$logs")
        // The old session's end must not touch the live one …
        sm.onEvent(ended(1))
        assertEquals(RestMode.WsCandidate, sm.current)
        assertEquals(2L, sm.liveSessionEpoch)
    }

    @Test
    fun late_ended_of_the_old_session_does_not_touch_the_new_one() = runTest {
        val sm = build()
        sm.onEvent(connected(1))
        sm.onEvent(ended(1))
        sm.onEvent(connected(2))
        assertEquals(RestMode.WsCandidate, sm.current)
        sm.onEvent(ended(1)) // late close of the session that already ended
        assertEquals(RestMode.WsCandidate, sm.current, "a stale close must not regress the candidate")
        assertEquals(2L, sm.liveSessionEpoch)
    }

    @Test
    fun frame_and_ack_of_a_dead_session_do_not_prove_the_new_one() = runTest {
        val clock = FakeClock()
        val sm = build(clock)
        sm.onEvent(connected(1))
        sm.onEvent(ended(1))
        sm.onEvent(connected(2))
        assertEquals(RestMode.WsCandidate, sm.current)
        sm.onEvent(frame(1))
        sm.onEvent(ack(1))
        assertEquals(RestMode.WsCandidate, sm.current, "session 1 cannot prove session 2")
        assertNull(sm.provenSessionEpoch)
        sm.onEvent(ack(2))
        assertEquals(RestMode.WsActive, sm.current)
        assertEquals(2L, sm.provenSessionEpoch)
    }

    @Test
    fun stale_alive_tick_after_session_ended_never_promotes() = runTest {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        val sm = build(clock, logs)
        sm.onEvent(connected(1))
        sm.onEvent(ended(1))
        sm.onEvent(connected(2))
        sm.onEvent(frame(2))
        sm.onEvent(ended(2)) // the candidate dies
        assertEquals(RestMode.RestActive, sm.current)
        clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS + 1)
        sm.onEvent(tick(2))
        assertEquals(RestMode.RestActive, sm.current, "a dead session's tick must not promote")
    }

    @Test
    fun a_tick_of_the_old_session_after_a_network_change_is_ignored() = runTest {
        val clock = FakeClock()
        val sm = build(clock)
        sm.onEvent(connected(1))
        sm.onEvent(ended(1))
        sm.onEvent(connected(2))
        sm.onEvent(frame(2))
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true, networkGeneration = 7))
        assertEquals(RestMode.RestActive, sm.current)
        assertNull(sm.liveSessionEpoch, "a network change invalidates the session")
        clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS + 1)
        sm.onEvent(tick(2))
        assertEquals(RestMode.RestActive, sm.current)
        // Only a fresh session, with its own frame and dwell, promotes.
        sm.onEvent(connected(3))
        sm.onEvent(frame(3))
        clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS)
        sm.onEvent(tick(3))
        assertEquals(RestMode.WsActive, sm.current)
        assertEquals(3L, sm.provenSessionEpoch)
    }

    @Test
    fun network_changed_without_a_new_connect_never_leaves_rest_active() = runTest {
        val clock = FakeClock()
        val sm = build(clock)
        sm.onEvent(connected(1))
        sm.onEvent(ended(1))
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true, networkGeneration = 2))
        assertEquals(RestMode.RestActive, sm.current)
        assertTrue(sm.snapshot.value.reconnectRequested)
        repeat(24) {
            clock.advance(5_000)
            sm.onEvent(tick(1))
            sm.onEvent(tick(2))
        }
        assertEquals(
            RestMode.RestActive, sm.current,
            "120 s of ticks with no session must not promote — this is the 19:07 sequence",
        )
    }

    // ── Proof (B3) ───────────────────────────────────────────────────────────

    @Test
    fun only_proof_from_the_live_session_promotes() = runTest {
        val clock = FakeClock()
        val sm = build(clock)
        sm.driveToRestActive(1)
        sm.onEvent(connected(3))
        sm.onEvent(frame(3))
        clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS)
        sm.onEvent(tick(3))
        assertEquals(RestMode.WsActive, sm.current)
    }

    @Test
    fun dwell_without_a_frame_does_not_promote() = runTest {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        val sm = build(clock, logs)
        sm.driveToRestActive(1)
        sm.onEvent(connected(2))
        assertEquals(RestMode.WsCandidate, sm.current)
        clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS * 3)
        sm.onEvent(tick(2))
        assertEquals(
            RestMode.WsCandidate, sm.current,
            "wall-clock dwell alone is what `ws_alive_60s` used to promote on",
        )
        assertTrue(logs.any { it.contains("candidate_dwell_without_frame") }, "logs=$logs")
    }

    @Test
    fun a_pong_is_liveness_only_and_never_proof() = runTest {
        val clock = FakeClock()
        val sm = build(clock)
        sm.driveToRestActive(1)
        sm.onEvent(connected(2))
        sm.onEvent(pong(2))
        clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS)
        sm.onEvent(tick(2))
        assertEquals(RestMode.WsCandidate, sm.current, "a pong must not stand in for a frame")
    }

    @Test
    fun a_correlated_candidate_probe_round_trip_proves_only_after_the_dwell() = runTest {
        val clock = FakeClock()
        val sm = build(clock)
        sm.driveToRestActive(1)
        sm.onEvent(connected(2))

        clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS - 10_000L)
        sm.onEvent(candidateProof(2))
        assertEquals(
            RestMode.WsCandidate,
            sm.current,
            "the one-shot round trip proves bidirectional traffic, not the dwell",
        )

        clock.advance(10_000L)
        sm.onEvent(tick(2))
        assertEquals(RestMode.WsActive, sm.current)
        assertEquals(2L, sm.provenSessionEpoch)
    }

    @Test
    fun a_candidate_probe_round_trip_from_a_stale_session_is_ignored() = runTest {
        val clock = FakeClock()
        val sm = build(clock)
        sm.driveToRestActive(1)
        sm.onEvent(connected(2))
        sm.onEvent(connected(3))
        sm.onEvent(candidateProof(2))
        clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS)
        sm.onEvent(tick(3))
        assertEquals(RestMode.WsCandidate, sm.current)
        assertNull(sm.provenSessionEpoch)
    }

    @Test
    fun dwell_is_measured_from_the_candidate_session_not_from_the_first() = runTest {
        val clock = FakeClock()
        val sm = build(clock)
        sm.driveToRestActive(1)
        sm.onEvent(connected(2))
        sm.onEvent(frame(2))
        clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS - 1_000)
        // A newer session replaces the candidate: its clock restarts.
        sm.onEvent(connected(3))
        sm.onEvent(frame(3))
        clock.advance(2_000)
        sm.onEvent(tick(3))
        assertEquals(RestMode.WsCandidate, sm.current, "the new candidate has not dwelled yet")
        clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS)
        sm.onEvent(tick(3))
        assertEquals(RestMode.WsActive, sm.current)
        assertEquals(3L, sm.provenSessionEpoch)
    }

    @Test
    fun an_outbound_ack_promotes_the_live_candidate_immediately() = runTest {
        val sm = build()
        sm.driveToRestActive(1)
        sm.onEvent(connected(2))
        sm.onEvent(ack(2))
        assertEquals(RestMode.WsActive, sm.current)
        assertEquals(2L, sm.provenSessionEpoch)
    }

    // ── Negative signals of the live session ─────────────────────────────────

    @Test
    fun a_live_session_end_degrades_on_the_first_close() = runTest {
        val sm = build()
        sm.onEvent(connected(1))
        sm.onEvent(frame(1))
        assertEquals(RestMode.WsCandidate, sm.current)
        sm.onEvent(ended(1))
        assertEquals(
            RestMode.RestActive, sm.current,
            "one close of the proven session is the evidence; the old machine waited for two or three",
        )
        assertNull(sm.liveSessionEpoch)
    }

    @Test
    fun stalled_of_the_current_candidate_cancels_the_proof_and_keeps_the_session() = runTest {
        val clock = FakeClock()
        val sm = build(clock)
        sm.driveToRestActive(1)
        sm.onEvent(connected(2))
        sm.onEvent(frame(2))
        sm.onEvent(stalled(2))
        assertEquals(RestMode.RestActive, sm.current)
        assertEquals(2L, sm.liveSessionEpoch, "a silent socket is not a gone socket")
        assertEquals(
            RestRecoveryCause.FailureOrUnknown, sm.snapshot.value.recoveryCause,
            "a session already failed before this one, so silence is not the whole story",
        )
        // A later frame on the same session re-enters candidate.
        sm.onEvent(frame(2))
        assertEquals(RestMode.WsCandidate, sm.current)
    }

    @Test
    fun silence_alone_on_the_live_session_is_presented_as_silence() = runTest {
        val sm = build()
        sm.onEvent(connected(1))
        assertEquals(RestMode.WsCandidate, sm.current)
        sm.onEvent(stalled(1))
        assertEquals(RestMode.RestActive, sm.current)
        assertEquals(
            RestRecoveryCause.InboundSilence, sm.snapshot.value.recoveryCause,
            "nothing has failed: a quiet chat must not be presented as a failure",
        )
        assertEquals(1L, sm.liveSessionEpoch, "the socket is silent, not gone")
    }

    @Test
    fun stalled_of_the_old_session_does_not_degrade_the_current_one() = runTest {
        val clock = FakeClock()
        val sm = build(clock)
        sm.driveToRestActive(1)
        sm.onEvent(connected(2))
        sm.onEvent(ack(2))
        assertEquals(RestMode.WsActive, sm.current)
        sm.onEvent(stalled(1))
        assertEquals(RestMode.WsActive, sm.current, "session 1 is long gone")
        sm.onEvent(stalled(2))
        assertEquals(RestMode.RestActive, sm.current)
        assertEquals(2L, sm.liveSessionEpoch)
    }

    @Test
    fun ack_deadline_of_the_current_candidate_cancels_the_proof() = runTest {
        val sm = build()
        sm.driveToRestActive(1)
        sm.onEvent(connected(2))
        sm.onEvent(frame(2))
        sm.onEvent(ackDeadline(2))
        assertEquals(RestMode.RestActive, sm.current)
        assertEquals(RestRecoveryCause.FailureOrUnknown, sm.snapshot.value.recoveryCause)
        assertEquals(2L, sm.liveSessionEpoch)
    }

    @Test
    fun ack_deadline_of_the_old_session_does_not_degrade_the_current_one() = runTest {
        val sm = build()
        sm.driveToRestActive(1)
        sm.onEvent(connected(2))
        sm.onEvent(ack(2))
        assertEquals(RestMode.WsActive, sm.current)
        sm.onEvent(ackDeadline(1, id = "written-by-session-1"))
        assertEquals(
            RestMode.WsActive, sm.current,
            "a deadline armed on the previous session must not degrade this one",
        )
        sm.onEvent(ackDeadline(2))
        assertEquals(RestMode.RestActive, sm.current)
    }

    @Test
    fun an_invalidated_live_session_degrades_and_clears_it() = runTest {
        val sm = build()
        sm.onEvent(connected(1))
        sm.onEvent(ack(1))
        assertEquals(RestMode.WsActive, sm.current)
        sm.onEvent(
            RestStateMachine.Event.WsSessionInvalidated(sessionEpoch = 1, reason = "force_reconnect"),
        )
        assertEquals(RestMode.RestActive, sm.current)
        assertNull(sm.liveSessionEpoch)
    }

    @Test
    fun an_invalidated_old_session_changes_nothing() = runTest {
        val sm = build()
        sm.driveToRestActive(1)
        sm.onEvent(connected(2))
        sm.onEvent(ack(2))
        sm.onEvent(
            RestStateMachine.Event.WsSessionInvalidated(sessionEpoch = 1, reason = "force_reconnect"),
        )
        assertEquals(RestMode.WsActive, sm.current)
        assertEquals(2L, sm.liveSessionEpoch)
    }

    // ── What the machine reports ─────────────────────────────────────────────

    @Test
    fun on_event_returns_the_transition_it_caused_and_null_otherwise() = runTest {
        val sm = build()
        val entered = sm.onEvent(connected(1))
        assertNotNull(entered)
        assertEquals(RestMode.RestActive, entered.from)
        assertEquals(RestMode.WsCandidate, entered.to)
        assertNull(sm.onEvent(frame(1)), "a frame in candidate only records evidence")
        val proven = sm.onEvent(ack(1))
        assertNotNull(proven)
        assertEquals(RestMode.WsActive, proven.to)
        val degraded = sm.onEvent(ended(1))
        assertNotNull(degraded)
        assertEquals(RestMode.WsActive, degraded.from)
        assertEquals(RestMode.RestActive, degraded.to)
        assertNull(sm.onEvent(ended(1)), "a stale close changes nothing")
    }

    @Test
    fun the_snapshot_carries_the_session_identity_presentation_needs() = runTest {
        val clock = FakeClock()
        val sm = build(clock)
        sm.driveToRestActive(1)
        sm.onEvent(connected(2))
        assertEquals(2L, sm.snapshot.value.liveSessionEpoch)
        assertEquals(2L, sm.snapshot.value.candidateEpoch)
        assertNull(sm.snapshot.value.provenSessionEpoch)
        sm.onEvent(ack(2))
        assertEquals(2L, sm.snapshot.value.provenSessionEpoch)
        assertNull(sm.snapshot.value.candidateEpoch)
    }

    @Test
    fun state_flow_emits_the_stage_2_transition_sequence() = runTest {
        val clock = FakeClock()
        val sm = build(clock)
        val seen = mutableListOf<RestMode>()
        seen += sm.current
        sm.onEvent(connected(1)); seen += sm.current
        sm.onEvent(ended(1)); seen += sm.current
        sm.onEvent(connected(2)); seen += sm.current
        sm.onEvent(ack(2)); seen += sm.current
        assertEquals(
            listOf(
                RestMode.RestActive,
                RestMode.WsCandidate,
                RestMode.RestActive,
                RestMode.WsCandidate,
                RestMode.WsActive,
            ),
            seen,
        )
    }

    @Test
    fun the_commit_dwell_is_the_locked_constant() {
        // residual N1 / F3 (2026-09-18): the dwell was pinned at 60_000L, which
        // is the whole external post-route-change budget. Promotion happens at
        // route_change + reconnect + dwell, so an exactly-60 s dwell made the
        // 60 s oracle unreachable — the field run landed at 61 823 ms. The
        // dwell is now derived from the external limit minus a reserved margin.
        // It is still locked; the locked value changed, and the derivation is
        // asserted so the dwell can never silently reclaim the whole budget.
        assertEquals(55_000L, RestStateMachine.CANDIDATE_COMMIT_MS)
        assertEquals(
            RestStateMachine.ROUTE_RECOVERY_EXTERNAL_LIMIT_MS - RestStateMachine.ROUTE_RECOVERY_MARGIN_MS,
            RestStateMachine.CANDIDATE_COMMIT_MS,
        )
    }

    // ── 3.6 Fast REST degradation: signature and telemetry ───────────────────
    //
    // Stage 2 kept the Mode-2 signature and its telemetry. What the flag
    // now selects is the REASON and the sticky arming, not whether the
    // mode drops — a live session end always degrades (I1).

    private fun mode2Event(
        durationMs: Long = 31_000L,
        inboundFrames: Int = 0,
        pendingAcksAtClose: Int = 0,
        okhttpPingTimeoutDetected: Boolean = true,
        sessionEpoch: Long = 1L,
    ) = ended(
        sessionEpoch = sessionEpoch,
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

    /**
     * Make [epoch] the live session. It lands in candidate, which is where
     * a Mode-2 death now most often finds it.
     */
    private suspend fun RestStateMachine.live(epoch: Long = 1L) {
        onEvent(connected(epoch))
    }

    @Test
    fun mode2_signature_boundaries_select_the_reason() = runTest {
        val cases = listOf(
            RestStateMachine.MODE_2_MIN_DURATION_MS - 1 to false,
            RestStateMachine.MODE_2_MIN_DURATION_MS to true,
            RestStateMachine.MODE_2_MAX_DURATION_MS to true,
            RestStateMachine.MODE_2_MAX_DURATION_MS + 1 to false,
        )
        for ((duration, matches) in cases) {
            val logs = mutableListOf<String>()
            val sm = buildWithFastPath(logSink = logs)
            sm.live()
            sm.onEvent(mode2Event(durationMs = duration))
            assertEquals(RestMode.RestActive, sm.current, "every live session end degrades")
            assertEquals(
                matches,
                logs.any { it.contains("mode_switched") && it.contains("mode_2_fast_path") },
                "duration=$duration matches=$matches logs=$logs",
            )
        }
    }

    @Test
    fun mode2_signature_misses_when_ping_timeout_flag_false() = runTest {
        val logs = mutableListOf<String>()
        val sm = buildWithFastPath(logSink = logs)
        sm.live()
        sm.onEvent(mode2Event(okhttpPingTimeoutDetected = false))
        assertEquals(RestMode.RestActive, sm.current)
        assertFalse(logs.any { it.contains("mode_2_signature_matched") }, "logs=$logs")
    }

    @Test
    fun mode2_signature_misses_when_inbound_frames_positive() = runTest {
        val logs = mutableListOf<String>()
        val sm = buildWithFastPath(logSink = logs)
        sm.live()
        sm.onEvent(mode2Event(inboundFrames = 1))
        assertFalse(logs.any { it.contains("mode_2_signature_matched") }, "logs=$logs")
    }

    @Test
    fun mode2_telemetry_labels_the_action_by_the_flag() = runTest {
        for (flagOn in listOf(true, false)) {
            val logs = mutableListOf<String>()
            val sm = buildWithFastPath(mode2FastPathEnabled = flagOn, logSink = logs)
            sm.live()
            sm.onEvent(mode2Event())
            val expected = if (flagOn) "action=fast_path" else "action=observe_only"
            assertTrue(
                logs.any { it.contains("mode_2_signature_matched") && it.contains(expected) },
                "flagOn=$flagOn logs=$logs",
            )
        }
    }

    @Test
    fun mode2_matched_signature_telemetry_format_is_locked() = runTest {
        val logs = mutableListOf<String>()
        val sm = buildWithFastPath(logSink = logs)
        sm.live()
        sm.onEvent(mode2Event(durationMs = 42_000L, inboundFrames = 0, pendingAcksAtClose = 3))
        assertEquals(
            "REST_TRACE mode_2_signature_matched action=fast_path " +
                "duration_ms=42000 inbound_frames=0 pending_acks=3",
            logs.firstOrNull { it.contains("mode_2_signature_matched") },
            "the format is grep-anchored for post-mortem analysis",
        )
    }

    @Test
    fun mode1_like_healthy_close_does_not_match_the_signature() = runTest {
        val logs = mutableListOf<String>()
        val sm = buildWithFastPath(logSink = logs)
        sm.live()
        sm.onEvent(mode2Event(durationMs = 150_000L, inboundFrames = 5))
        assertFalse(
            logs.any { it.contains("mode_2_signature_matched") },
            "Mode-1 rhythm must not match; logs=$logs",
        )
    }

    // ── R3.6 sticky window (flag-gated, off in the shipped APK) ─────────────

    private fun buildWithSticky(
        clock: FakeClock = FakeClock(),
        logSink: MutableList<String> = mutableListOf(),
    ): RestStateMachine = RestStateMachine(
        now = { clock.nowMs },
        log = { logSink.add(it) },
        mode2FastPathEnabled = true,
        mode2StickyEnabled = true,
    )

    private suspend fun driveToStickyRestActive(sm: RestStateMachine, epoch: Long = 1L) {
        sm.onEvent(connected(epoch))
        sm.onEvent(mode2Event(sessionEpoch = epoch))
        assertEquals(RestMode.RestActive, sm.current, "must be RestActive after Mode-2 fast-path")
    }

    @Test
    fun sticky_armed_log_fires_after_mode2_fast_path() = runTest {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        driveToStickyRestActive(sm)
        assertTrue(
            logs.any {
                it.contains("sticky_armed") && it.contains("gen=1") &&
                    it.contains("reason=mode_2_fast_path")
            },
            "logs=$logs",
        )
    }

    @Test
    fun frame_text_is_suppressed_while_sticky_is_armed() = runTest {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        driveToStickyRestActive(sm)
        // A new session arrives but sticky holds RestActive until a route change.
        sm.onEvent(connected(2))
        assertEquals(RestMode.RestActive, sm.current)
        sm.onEvent(frame(2))
        assertEquals(RestMode.RestActive, sm.current)
        assertTrue(logs.any { it.contains("sticky_frame_suppressed") }, "logs=$logs")
        val before = logs.count { it.contains("sticky_frame_suppressed") }
        sm.onEvent(frame(2))
        assertEquals(before, logs.count { it.contains("sticky_frame_suppressed") }, "logged once per gen")
    }

    @Test
    fun a_route_change_that_clears_sticky_arms_recovery() = runTest {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        driveToStickyRestActive(sm)
        logs.clear()
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true, networkGeneration = 2))
        assertEquals(RestMode.RestActive, sm.current)
        assertTrue(
            logs.any { it.contains("sticky_recovery_pending") && it.contains("reason=route_change") },
            "logs=$logs",
        )
    }

    @Test
    fun a_validated_change_keeps_sticky_armed() = runTest {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        driveToStickyRestActive(sm)
        logs.clear()
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = false, networkGeneration = 2))
        assertEquals(RestMode.RestActive, sm.current)
        assertTrue(
            logs.any { it.contains("sticky_kept") && it.contains("reason=validated_change") },
            "logs=$logs",
        )
    }

    @Test
    fun a_new_session_while_pending_opens_the_recovery_probation() = runTest {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true, networkGeneration = 2))
        logs.clear()
        sm.onEvent(connected(2))
        assertEquals(RestMode.WsCandidate, sm.current)
        assertTrue(
            logs.any { it.contains("sticky_recovery_started") && it.contains("ws_epoch=2") },
            "logs=$logs",
        )
    }

    @Test
    fun ws_alive_60s_with_a_frame_clears_sticky() = runTest {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(clock = clock, logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true, networkGeneration = 2))
        sm.onEvent(connected(2))
        assertEquals(RestMode.WsCandidate, sm.current)
        // B10: the sticky proof now carries the B3 proof as well.
        sm.proveCandidateByDwell(2) { clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS) }
        assertEquals(RestMode.WsActive, sm.current)
        assertTrue(
            logs.any { it.contains("sticky_cleared") && it.contains("proof=ws_alive_60s") },
            "logs=$logs",
        )
    }

    @Test
    fun an_outbound_ack_is_not_proof_during_sticky_recovery() = runTest {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true, networkGeneration = 2))
        sm.onEvent(connected(2))
        logs.clear()
        sm.onEvent(ack(2))
        assertEquals(RestMode.WsCandidate, sm.current, "the ACK routes over REST and proves nothing here")
        assertTrue(logs.any { it.contains("sticky_recovery_ack_ignored") }, "logs=$logs")
    }

    @Test
    fun the_recovery_candidate_dying_fails_the_recovery() = runTest {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true, networkGeneration = 2))
        sm.onEvent(connected(2))
        logs.clear()
        sm.onEvent(ended(2, durationMs = 5_000L, pendingAcksAtClose = 0))
        assertEquals(RestMode.RestActive, sm.current)
        assertTrue(
            logs.any { it.contains("sticky_recovery_failed") && it.contains("ws_epoch=2") },
            "logs=$logs",
        )
    }

    @Test
    fun a_stale_close_during_recovery_is_ignored() = runTest {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true, networkGeneration = 2))
        sm.onEvent(connected(2))
        logs.clear()
        sm.onEvent(ended(1)) // the session that died before the recovery began
        assertEquals(RestMode.WsCandidate, sm.current)
        assertTrue(logs.any { it.contains("sticky_recovery_stale_close_ignored") }, "logs=$logs")
    }

    @Test
    fun a_second_session_during_recovery_restarts_the_probation_clock() = runTest {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(clock = clock, logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true, networkGeneration = 2))
        sm.onEvent(connected(2))
        sm.onEvent(frame(2))
        clock.advance(30_000)
        sm.onEvent(connected(3))
        assertTrue(
            logs.any { it.contains("sticky_recovery_restarted") && it.contains("new_epoch=3") },
            "logs=$logs",
        )
        sm.onEvent(frame(3))
        clock.advance(30_000)
        sm.onEvent(tick(3))
        assertEquals(RestMode.WsCandidate, sm.current, "the clock restarted with session 3")
        clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS)
        sm.onEvent(tick(3))
        assertEquals(RestMode.WsActive, sm.current)
    }

    @Test
    fun a_stale_connect_is_ignored_in_every_recovery_state() = runTest {
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(logSink = logs)
        sm.onEvent(connected(42))
        logs.clear()
        sm.onEvent(connected(41))
        assertTrue(
            logs.any {
                it.contains("sticky_recovery_stale_connect_ignored") &&
                    it.contains("last_observed_epoch=42") && it.contains("event_epoch=41")
            },
            "logs=$logs",
        )
        assertEquals(42L, sm.liveSessionEpoch)
    }

    @Test
    fun the_stale_epoch_filter_works_without_sticky() = runTest {
        val logs = mutableListOf<String>()
        val sm = build(logSink = logs)
        sm.onEvent(connected(42))
        logs.clear()
        sm.onEvent(connected(41))
        assertTrue(logs.any { it.contains("stale_connect_ignored") }, "logs=$logs")
        assertEquals(42L, sm.liveSessionEpoch)
    }

    @Test
    fun a_dead_candidates_tick_cannot_clear_sticky() = runTest {
        val clock = FakeClock()
        val logs = mutableListOf<String>()
        val sm = buildWithSticky(clock = clock, logSink = logs)
        driveToStickyRestActive(sm)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true, networkGeneration = 2))
        sm.onEvent(connected(2))
        sm.onEvent(frame(2))
        clock.advance(30_000)
        sm.onEvent(ended(2, durationMs = 30_000L, pendingAcksAtClose = 0))
        assertEquals(RestMode.RestActive, sm.current)
        logs.clear()
        clock.advance(35_000)
        sm.onEvent(tick(2))
        assertFalse(logs.any { it.contains("sticky_cleared") }, "logs=$logs")
        assertEquals(RestMode.RestActive, sm.current)
    }

    @Test
    fun fast_path_without_the_sticky_flag_does_not_arm_sticky() = runTest {
        val logs = mutableListOf<String>()
        val sm = buildWithFastPath(logSink = logs)
        sm.live()
        sm.onEvent(mode2Event())
        assertEquals(RestMode.RestActive, sm.current)
        assertFalse(logs.any { it.contains("sticky_armed") }, "logs=$logs")
    }

    @Test
    fun sticky_without_fast_path_throws_at_construction() {
        val threw = try {
            RestStateMachine(now = { 0L }, mode2FastPathEnabled = false, mode2StickyEnabled = true)
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(threw, "sticky requires the fast path; the invariant is build-time")
    }

    @Test
    fun quiescence_without_sticky_throws_at_construction() {
        val threw = try {
            RestStateMachine(
                now = { 0L },
                mode2FastPathEnabled = true,
                mode2StickyEnabled = false,
                reconnectQuiescenceEnabled = true,
            )
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(threw, "the gate is meaningless without sticky armed")
    }

    @Test
    fun the_sticky_cleanup_completes_before_the_mode_switched_callback() = runTest {
        val clock = FakeClock()
        var callbackInvoked = false
        val sm = RestStateMachine(
            now = { clock.nowMs },
            log = {},
            onModeSwitched = { _, to, _ ->
                if (to == RestMode.WsActive) {
                    callbackInvoked = true
                    throw IllegalStateException("observer threw")
                }
            },
            mode2FastPathEnabled = true,
            mode2StickyEnabled = true,
        )
        driveToStickyRestActive(sm)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true, networkGeneration = 2))
        sm.onEvent(connected(2))
        sm.onEvent(frame(2))
        clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS)
        val threw = runCatching { sm.onEvent(tick(2)) }.isFailure
        assertTrue(callbackInvoked && threw, "the observer must have run and its failure must surface")
        assertFalse(
            sm.isStickyOrRecoveryActive,
            "the sticky cleanup runs before the callback, so a throwing observer cannot leak it",
        )
    }
}
