// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [WsDegradationDetector] — design note
 * `docs/tracks/ws-health-state.md` § Commit 3.2a (rev3) acceptance
 * gates 3, 4, 5, 6a, 7.
 *
 * The detector is pure logic: virtual clock + virtual state provider,
 * no coroutines. Tests assert verdict shape, rising-edge log emission,
 * Direct-only suspect lock, WsCandidate gate, window expiry, and
 * monotonic-clock safety.
 */
class WsDegradationDetectorTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private class VirtualClock(var nowMs: Long = 0L) : () -> Long {
        override fun invoke(): Long = nowMs
    }

    private class CapturingLog : (String) -> Unit {
        val lines = mutableListOf<String>()
        override fun invoke(msg: String) {
            lines.add(msg)
        }
    }

    private fun detectorWith(
        clock: VirtualClock,
        log: CapturingLog = CapturingLog(),
        state: RestMode = RestMode.WsActive,
    ): Pair<WsDegradationDetector, CapturingLog> {
        val det = WsDegradationDetector(
            now = clock,
            log = log,
            stateProvider = { state },
        )
        return det to log
    }

    private fun detectorWithDynamicState(
        clock: VirtualClock,
        log: CapturingLog = CapturingLog(),
        stateRef: () -> RestMode,
    ): Pair<WsDegradationDetector, CapturingLog> {
        val det = WsDegradationDetector(
            now = clock,
            log = log,
            stateProvider = stateRef,
        )
        return det to log
    }

    // ── Gate-3 tests: WsCandidate gate ───────────────────────────────────────

    @Test
    fun wsCandidate_gates_wouldRewalk_evenAboveThreshold() {
        val clock = VirtualClock()
        val (det, log) = detectorWith(clock, state = RestMode.WsCandidate)
        // Feed two PingTimeouts — would normally cross the candidate
        // threshold (WS_DEGRADED_CANDIDATE_PING_THRESHOLD = 2).
        det.recordAndEmit(WsDegradationDetector.Event.PingTimeout, TransportKind.Direct)
        clock.nowMs = 1_000L
        det.recordAndEmit(WsDegradationDetector.Event.PingTimeout, TransportKind.Direct)

        val v = det.evaluate(TransportKind.Direct)
        assertFalse(v.wouldRewalk, "WsCandidate must gate wouldRewalk to false")
        assertTrue(v.gatedByWsCandidate, "gatedByWsCandidate must be true")
        assertFalse(v.wouldMarkSuspect, "suspect must follow wouldRewalk")
        // The emitted lines must mark the gate explicitly so calibration
        // can spot suppressed triggers in the field.
        assertTrue(
            log.lines.any { it.contains("gated_by_ws_candidate=true") },
            "log must contain gated_by_ws_candidate=true",
        )
    }

    @Test
    fun wsActive_doesNotGate_aboveThreshold() {
        val clock = VirtualClock()
        val (det, _) = detectorWith(clock, state = RestMode.WsActive)
        det.record(WsDegradationDetector.Event.PingTimeout)
        clock.nowMs = 1_000L
        det.record(WsDegradationDetector.Event.PingTimeout)

        val v = det.evaluate(TransportKind.Direct)
        assertTrue(v.wouldRewalk)
        assertFalse(v.gatedByWsCandidate)
    }

    // ── Gate-4 tests: window expiry ──────────────────────────────────────────

    @Test
    fun eventOlderThanWindow_doesNotContribute() {
        val clock = VirtualClock()
        val (det, _) = detectorWith(clock)

        det.record(WsDegradationDetector.Event.PingTimeout)
        // Advance past the window so this event must be pruned.
        clock.nowMs = RelayTransportConfig.WS_DEGRADED_WINDOW_MS + 1
        det.record(WsDegradationDetector.Event.PingTimeout)

        val v = det.evaluate(TransportKind.Direct)
        assertEquals(1, v.pingTimeoutCount, "old event must be pruned from window")
        // Threshold is 2 ping timeouts — one survivor must not cross.
        assertFalse(v.wouldRewalk)
    }

    @Test
    fun windowBoundary_inclusive_atExactlyWindowMs() {
        val clock = VirtualClock()
        val (det, _) = detectorWith(clock)

        det.record(WsDegradationDetector.Event.PingTimeout)
        // Advance to the exact window edge — cutoff is `nowMs - windowMs`,
        // so an event timestamped 0 with nowMs == windowMs has
        // `ts (0) < cutoff (0)` = false, it must remain.
        clock.nowMs = RelayTransportConfig.WS_DEGRADED_WINDOW_MS
        det.record(WsDegradationDetector.Event.PingTimeout)

        val v = det.evaluate(TransportKind.Direct)
        assertEquals(2, v.pingTimeoutCount, "boundary event must survive prune")
        assertTrue(v.wouldRewalk)
    }

    // ── Gate-5 tests: monotonic clock safety ─────────────────────────────────

    @Test
    fun clockGoingBackwards_doesNotProduceNegativeOrCrash() {
        val clock = VirtualClock(nowMs = 1_000L)
        val (det, _) = detectorWith(clock)
        det.record(WsDegradationDetector.Event.PingTimeout)

        clock.nowMs = -50L
        det.record(WsDegradationDetector.Event.IdleTimeout)
        clock.nowMs = -1_000_000L
        det.record(WsDegradationDetector.Event.AckTimeout)

        // No exceptions, no negative weights, deterministic verdict.
        val v = det.evaluate(TransportKind.Direct)
        assertTrue(v.weightedSum >= 0.0, "weightedSum must never go negative")
        // Counters in session are still accurate (record bumps regardless
        // of clock direction).
        val counts = det.sessionCountsForTest()
        assertEquals(1, counts.first)
        assertEquals(1, counts.second)
        assertEquals(1, counts.third)
    }

    // ── Direct-only suspect lock (rev3 P3-6) ─────────────────────────────────

    @Test
    fun wouldMarkSuspect_isFalse_whenCurrentKindIsReality_evenAtThreshold() {
        val clock = VirtualClock()
        val (det, _) = detectorWith(clock)
        det.record(WsDegradationDetector.Event.PingTimeout)
        clock.nowMs = 1_000L
        det.record(WsDegradationDetector.Event.PingTimeout)

        val v = det.evaluate(TransportKind.Reality)
        assertTrue(v.wouldRewalk, "wouldRewalk is allowed for non-Direct kinds")
        assertFalse(
            v.wouldMarkSuspect,
            "wouldMarkSuspect MUST be false for non-Direct kinds (D2 lock)",
        )
    }

    @Test
    fun wouldMarkSuspect_isFalse_whenCurrentKindIsTor_evenAtThreshold() {
        val clock = VirtualClock()
        val (det, _) = detectorWith(clock)
        det.record(WsDegradationDetector.Event.PingTimeout)
        clock.nowMs = 1_000L
        det.record(WsDegradationDetector.Event.PingTimeout)

        val v = det.evaluate(TransportKind.Tor)
        assertFalse(v.wouldMarkSuspect, "D2 lock applies to Tor too")
    }

    @Test
    fun wouldMarkSuspect_isFalse_whenCurrentKindIsNull() {
        val clock = VirtualClock()
        val (det, _) = detectorWith(clock)
        det.record(WsDegradationDetector.Event.PingTimeout)
        clock.nowMs = 1_000L
        det.record(WsDegradationDetector.Event.PingTimeout)

        val v = det.evaluate(currentKind = null)
        assertFalse(
            v.wouldMarkSuspect,
            "wouldMarkSuspect MUST be false when kind is unknown",
        )
        assertNull(v.currentKind)
    }

    @Test
    fun wouldMarkSuspect_isTrue_onlyWhenDirect_atThreshold_andNotGated() {
        val clock = VirtualClock()
        val (det, _) = detectorWith(clock, state = RestMode.WsActive)
        det.record(WsDegradationDetector.Event.PingTimeout)
        clock.nowMs = 1_000L
        det.record(WsDegradationDetector.Event.PingTimeout)

        val v = det.evaluate(TransportKind.Direct)
        assertTrue(v.wouldRewalk)
        assertTrue(v.wouldMarkSuspect)
    }

    // ── Gate-7 tests: rising-edge log emission ───────────────────────────────

    @Test
    fun risingEdge_fires_exactlyOnce_onCrossing() {
        val clock = VirtualClock()
        val (det, log) = detectorWith(clock)
        // Below threshold — no rising edge.
        det.recordAndEmit(WsDegradationDetector.Event.PingTimeout, TransportKind.Direct)
        val firstCount = log.lines.count { it.startsWith("WS_DEGRADED detected") }
        assertEquals(
            0,
            firstCount,
            "no WS_DEGRADED detected line before threshold crossing",
        )

        clock.nowMs = 1_000L
        det.recordAndEmit(WsDegradationDetector.Event.PingTimeout, TransportKind.Direct)
        val afterRiseCount = log.lines.count { it.startsWith("WS_DEGRADED detected") }
        assertEquals(1, afterRiseCount, "rising edge must fire exactly once")

        // A follow-up record at the same verdict state must not re-fire.
        clock.nowMs = 2_000L
        det.recordAndEmit(WsDegradationDetector.Event.AckTimeout, TransportKind.Direct)
        val afterFollowupCount = log.lines.count { it.startsWith("WS_DEGRADED detected") }
        assertEquals(
            1,
            afterFollowupCount,
            "no re-fire while verdict remains at the same level",
        )
    }

    @Test
    fun risingEdge_fires_again_afterFallingEdge() {
        val clock = VirtualClock()
        val (det, log) = detectorWith(clock)
        det.recordAndEmit(WsDegradationDetector.Event.PingTimeout, TransportKind.Direct)
        clock.nowMs = 1_000L
        det.recordAndEmit(WsDegradationDetector.Event.PingTimeout, TransportKind.Direct)
        // First rising edge fired.
        val firstFire = log.lines.count { it.startsWith("WS_DEGRADED detected") }
        assertEquals(1, firstFire)

        // Advance past the window so both events are pruned → falling edge.
        clock.nowMs = RelayTransportConfig.WS_DEGRADED_WINDOW_MS + 2_000L
        det.emitVerdictIfRising(TransportKind.Direct)
        // No re-fire on the falling edge itself.
        assertEquals(1, log.lines.count { it.startsWith("WS_DEGRADED detected") })

        // New pair of ping timeouts within a fresh window — rising edge fires again.
        det.recordAndEmit(WsDegradationDetector.Event.PingTimeout, TransportKind.Direct)
        clock.nowMs += 1_000L
        det.recordAndEmit(WsDegradationDetector.Event.PingTimeout, TransportKind.Direct)
        assertEquals(
            2,
            log.lines.count { it.startsWith("WS_DEGRADED detected") },
            "rising edge must fire again after intervening falling edge",
        )
    }

    // ── Mandatory session_total emission (Gate 7 P2-3) ───────────────────────

    @Test
    fun emitSessionTotal_emitsLine_andResetsCounters() {
        val clock = VirtualClock()
        val (det, log) = detectorWith(clock)
        det.record(WsDegradationDetector.Event.PingTimeout)
        det.record(WsDegradationDetector.Event.IdleTimeout)
        det.record(WsDegradationDetector.Event.IdleTimeout)
        det.record(WsDegradationDetector.Event.AckTimeout)

        det.emitSessionTotal(sessionDurationMs = 120_000L, closeKind = "error")

        val sessionTotal =
            log.lines.firstOrNull { it.startsWith("WS_DEGRADED_TELEMETRY session_total") }
        assertTrue(sessionTotal != null, "session_total line must be emitted")
        assertTrue(sessionTotal!!.contains("ping_in_session=1"))
        assertTrue(sessionTotal.contains("idle_in_session=2"))
        assertTrue(sessionTotal.contains("ack_in_session=1"))
        assertTrue(sessionTotal.contains("session_duration_ms=120000"))
        assertTrue(sessionTotal.contains("close_kind=error"))
        assertTrue(sessionTotal.contains("on_close=true"))

        // Counters reset after emit.
        val resetCounts = det.sessionCountsForTest()
        assertEquals(0, resetCounts.first)
        assertEquals(0, resetCounts.second)
        assertEquals(0, resetCounts.third)
    }

    @Test
    fun emitSessionTotal_fires_evenWithZeroEvents() {
        val clock = VirtualClock()
        val (det, log) = detectorWith(clock)
        det.emitSessionTotal(sessionDurationMs = 5_000L, closeKind = "local")
        val sessionTotal =
            log.lines.firstOrNull { it.startsWith("WS_DEGRADED_TELEMETRY session_total") }
        assertTrue(
            sessionTotal != null,
            "session_total is mandatory even with zero events (denominator)",
        )
        assertTrue(sessionTotal!!.contains("ping_in_session=0"))
        assertTrue(sessionTotal.contains("idle_in_session=0"))
        assertTrue(sessionTotal.contains("ack_in_session=0"))
    }

    // ── Counter tick + state transition mirroring ────────────────────────────

    @Test
    fun emitCounterTick_emitsOnEveryRecordCall() {
        val clock = VirtualClock()
        val (det, log) = detectorWith(clock)
        det.recordAndEmit(WsDegradationDetector.Event.PingTimeout, TransportKind.Direct)
        clock.nowMs = 1_000L
        det.recordAndEmit(WsDegradationDetector.Event.IdleTimeout, TransportKind.Direct)
        clock.nowMs = 2_000L
        det.recordAndEmit(WsDegradationDetector.Event.AckTimeout, TransportKind.Direct)

        val ticks = log.lines.filter { it.startsWith("WS_DEGRADED_TELEMETRY counter") }
        assertEquals(3, ticks.size)
        assertTrue(ticks[0].contains("kind=ping"))
        assertTrue(ticks[1].contains("kind=idle"))
        assertTrue(ticks[2].contains("kind=ack"))
    }

    @Test
    fun emitStateTransitionSeen_emitsMirrorLine() {
        val clock = VirtualClock()
        val (det, log) = detectorWith(clock)
        det.emitStateTransitionSeen("active_outbound_threshold")
        assertEquals(1, log.lines.size)
        assertTrue(
            log.lines[0].startsWith(
                "WS_DEGRADED_TELEMETRY state_transition_seen reason=active_outbound_threshold",
            ),
        )
    }

    // ── Weighted-sum threshold ───────────────────────────────────────────────

    @Test
    fun weightedSum_canTrigger_withoutTwoPingTimeouts() {
        val clock = VirtualClock()
        val (det, _) = detectorWith(clock)
        // 1 ping (2.0) + 1 ack (1.0) + 1 idle (0.6) = 3.6 → crosses 3.0.
        det.record(WsDegradationDetector.Event.PingTimeout)
        clock.nowMs = 1_000L
        det.record(WsDegradationDetector.Event.AckTimeout)
        clock.nowMs = 2_000L
        det.record(WsDegradationDetector.Event.IdleTimeout)

        val v = det.evaluate(TransportKind.Direct)
        assertTrue(v.wouldRewalk, "weighted-sum branch must trigger above 3.0")
        assertEquals(1, v.pingTimeoutCount, "ping count is still 1, below the 2-strong branch")
    }

    @Test
    fun weightedSum_doesNotTrigger_belowThreshold() {
        val clock = VirtualClock()
        val (det, _) = detectorWith(clock)
        // 1 ack (1.0) + 1 idle (0.6) = 1.6 → does NOT cross 3.0.
        det.record(WsDegradationDetector.Event.AckTimeout)
        clock.nowMs = 1_000L
        det.record(WsDegradationDetector.Event.IdleTimeout)

        val v = det.evaluate(TransportKind.Direct)
        assertFalse(v.wouldRewalk)
        assertFalse(v.wouldMarkSuspect)
    }

    // ── State-change post-trigger (covers Gate 3 sequencing) ─────────────────

    @Test
    fun gateActivates_whenStateMachineFlipsToWsCandidate_afterTrigger() {
        val clock = VirtualClock()
        var state: RestMode = RestMode.WsActive
        val (det, log) = detectorWithDynamicState(clock, stateRef = { state })

        det.recordAndEmit(WsDegradationDetector.Event.PingTimeout, TransportKind.Direct)
        clock.nowMs = 1_000L
        det.recordAndEmit(WsDegradationDetector.Event.PingTimeout, TransportKind.Direct)
        // First rising edge fired while WsActive.
        val countAfterRise = log.lines.count { it.startsWith("WS_DEGRADED detected") }
        assertEquals(1, countAfterRise)

        // State machine flips to WsCandidate — next evaluate must gate.
        state = RestMode.WsCandidate
        clock.nowMs = 2_000L
        det.emitVerdictIfRising(TransportKind.Direct)
        // gatedByWsCandidate=true fires a fresh log line (per spec, even
        // though it is a "downgrade" from wouldRewalk=true to false).
        val lastLine = log.lines.last { it.startsWith("WS_DEGRADED detected") }
        assertTrue(lastLine.contains("gated_by_ws_candidate=true"))
        assertTrue(lastLine.contains("would_rewalk=false"))
    }
}
