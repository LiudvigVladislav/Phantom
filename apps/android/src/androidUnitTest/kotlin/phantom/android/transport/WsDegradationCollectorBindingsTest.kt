// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import phantom.core.transport.RestMode
import phantom.core.transport.TransportKind
import phantom.core.transport.WsDegradationDetector
import phantom.core.transport.WsSessionEndedEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PR-WS-HEALTH-STATE1 Commit 3.2a (Gate 6b, 2026-06-01) — focused
 * tests for the [WsDegradationCollectorBindings] helpers. Closes the
 * gap design note rev3 §7 Gate 6b called out: the
 * collector→detector mapping must be covered outside `commonTest`.
 *
 * Runs on the JVM (`androidUnitTest`). No Android runtime is required —
 * the helpers are pure routing and the detector is pure logic.
 */
class WsDegradationCollectorBindingsTest {

    private class VirtualClock(var nowMs: Long = 0L) : () -> Long {
        override fun invoke(): Long = nowMs
    }

    private class CapturingLog : (String) -> Unit {
        val lines = mutableListOf<String>()
        override fun invoke(msg: String) {
            lines.add(msg)
        }
    }

    private fun detector(
        clock: VirtualClock,
        log: CapturingLog,
        state: RestMode = RestMode.WsActive,
    ): WsDegradationDetector = WsDegradationDetector(
        now = clock,
        log = log,
        stateProvider = { state },
    )

    private fun cleanWsSessionEndedEvent(
        durationMs: Long = 12_000L,
        closeOrigin: String = "remote",
        okhttpPingTimeoutDetected: Boolean = false,
    ): WsSessionEndedEvent = WsSessionEndedEvent(
        durationMs = durationMs,
        inboundFrames = 0,
        pendingAcksAtClose = 0,
        closeOrigin = closeOrigin,
        closeError = null,
        okhttpPingTimeoutDetected = okhttpPingTimeoutDetected,
    )

    // ── feedDegradationDetectorOnWsSessionEnded ──────────────────────────────

    @Test
    fun wsSessionEnded_withPingTimeoutTrue_recordsPingAndEmitsSessionTotal() {
        val clock = VirtualClock()
        val log = CapturingLog()
        val det = detector(clock, log)

        feedDegradationDetectorOnWsSessionEnded(
            detector = det,
            event = cleanWsSessionEndedEvent(
                durationMs = 60_000L,
                closeOrigin = "error",
                okhttpPingTimeoutDetected = true,
            ),
            currentKind = TransportKind.Direct,
        )

        // counter tick for ping recorded
        assertTrue(
            log.lines.any {
                it.startsWith("WS_DEGRADED_TELEMETRY counter kind=ping")
            },
            "counter tick for ping must fire",
        )
        // session_total fired with the right close_kind
        val sessionTotal =
            log.lines.firstOrNull { it.startsWith("WS_DEGRADED_TELEMETRY session_total") }
        assertTrue(sessionTotal != null)
        assertTrue(sessionTotal!!.contains("close_kind=error"))
        assertTrue(sessionTotal.contains("session_duration_ms=60000"))
        assertTrue(sessionTotal.contains("ping_in_session=1"))
        assertTrue(sessionTotal.contains("on_close=true"))
    }

    @Test
    fun wsSessionEnded_withPingTimeoutFalse_emitsOnlySessionTotal() {
        val clock = VirtualClock()
        val log = CapturingLog()
        val det = detector(clock, log)

        feedDegradationDetectorOnWsSessionEnded(
            detector = det,
            event = cleanWsSessionEndedEvent(
                durationMs = 15_000L,
                closeOrigin = "local",
                okhttpPingTimeoutDetected = false,
            ),
            currentKind = TransportKind.Direct,
        )

        // No counter / verdict lines for ping.
        assertTrue(
            log.lines.none { it.startsWith("WS_DEGRADED_TELEMETRY counter") },
            "no counter tick must fire when okhttpPingTimeoutDetected=false",
        )
        assertTrue(
            log.lines.none { it.startsWith("WS_DEGRADED detected") },
            "no verdict line must fire when okhttpPingTimeoutDetected=false",
        )
        // But session_total MUST fire (denominator mandate).
        val sessionTotal =
            log.lines.firstOrNull { it.startsWith("WS_DEGRADED_TELEMETRY session_total") }
        assertTrue(sessionTotal != null, "session_total is mandatory")
        assertTrue(sessionTotal!!.contains("ping_in_session=0"))
        assertTrue(sessionTotal.contains("close_kind=local"))
        assertTrue(sessionTotal.contains("session_duration_ms=15000"))
    }

    @Test
    fun wsSessionEnded_twoPingTimeoutCloses_triggersRisingEdgeVerdict() {
        val clock = VirtualClock()
        val log = CapturingLog()
        val det = detector(clock, log)

        feedDegradationDetectorOnWsSessionEnded(
            detector = det,
            event = cleanWsSessionEndedEvent(okhttpPingTimeoutDetected = true),
            currentKind = TransportKind.Direct,
        )
        clock.nowMs = 1_000L
        feedDegradationDetectorOnWsSessionEnded(
            detector = det,
            event = cleanWsSessionEndedEvent(okhttpPingTimeoutDetected = true),
            currentKind = TransportKind.Direct,
        )

        val verdictLines = log.lines.filter { it.startsWith("WS_DEGRADED detected") }
        assertEquals(1, verdictLines.size, "rising edge fires exactly once")
        val v = verdictLines[0]
        assertTrue(v.contains("would_rewalk=true"))
        assertTrue(v.contains("would_mark_suspect=true"))
        assertTrue(v.contains("current_kind=Direct"))
    }

    @Test
    fun wsSessionEnded_nonDirect_doesNotMarkSuspect() {
        val clock = VirtualClock()
        val log = CapturingLog()
        val det = detector(clock, log)

        feedDegradationDetectorOnWsSessionEnded(
            detector = det,
            event = cleanWsSessionEndedEvent(okhttpPingTimeoutDetected = true),
            currentKind = TransportKind.Reality,
        )
        clock.nowMs = 1_000L
        feedDegradationDetectorOnWsSessionEnded(
            detector = det,
            event = cleanWsSessionEndedEvent(okhttpPingTimeoutDetected = true),
            currentKind = TransportKind.Reality,
        )

        val v = log.lines.first { it.startsWith("WS_DEGRADED detected") }
        assertTrue(v.contains("would_rewalk=true"))
        assertTrue(
            v.contains("would_mark_suspect=false"),
            "D2 lock: suspect must be false for non-Direct kinds",
        )
        assertTrue(v.contains("current_kind=Reality"))
    }

    // ── feedDegradationDetectorOnAckTimeout ──────────────────────────────────

    @Test
    fun ackTimeout_recordsAckEvent_andEmitsCounter() {
        val clock = VirtualClock()
        val log = CapturingLog()
        val det = detector(clock, log)

        feedDegradationDetectorOnAckTimeout(
            detector = det,
            currentKind = TransportKind.Direct,
        )

        assertTrue(
            log.lines.any {
                it.startsWith("WS_DEGRADED_TELEMETRY counter kind=ack count_now=1")
            },
            "ack counter tick must fire",
        )
        // Below verdict threshold — no WS_DEGRADED detected line.
        assertTrue(log.lines.none { it.startsWith("WS_DEGRADED detected") })
    }

    // ── feedDegradationDetectorOnInboundStalled ──────────────────────────────

    @Test
    fun inboundStalled_recordsIdleEvent_andEmitsCounter() {
        val clock = VirtualClock()
        val log = CapturingLog()
        val det = detector(clock, log)

        feedDegradationDetectorOnInboundStalled(
            detector = det,
            currentKind = TransportKind.Direct,
        )

        assertTrue(
            log.lines.any {
                it.startsWith("WS_DEGRADED_TELEMETRY counter kind=idle count_now=1")
            },
            "idle counter tick must fire",
        )
        assertTrue(log.lines.none { it.startsWith("WS_DEGRADED detected") })
    }

    @Test
    fun mixedEvents_canTrigger_weightedSumPath() {
        val clock = VirtualClock()
        val log = CapturingLog()
        val det = detector(clock, log)

        // First step: ping arrives via session close → weighted = 2.0,
        // below threshold 3.0 → no rising edge.
        feedDegradationDetectorOnWsSessionEnded(
            detector = det,
            event = cleanWsSessionEndedEvent(okhttpPingTimeoutDetected = true),
            currentKind = TransportKind.Direct,
        )
        clock.nowMs = 1_000L
        // Second step: ack arrives → weighted = 2.0 + 1.0 = 3.0 → crosses
        // the weighted-sum branch even though ping_timeout_count is still
        // only 1 (below the strong-only branch). Rising-edge log fires
        // exactly at this moment, BEFORE the idle event below — so the
        // verdict snapshot has idle_timeout_count=0.
        feedDegradationDetectorOnAckTimeout(det, TransportKind.Direct)
        clock.nowMs = 2_000L
        // Third step: idle arrives, weighted grows to 3.6. Verdict
        // remains wouldRewalk=true (no falling edge), so no new line.
        feedDegradationDetectorOnInboundStalled(det, TransportKind.Direct)

        val risingLines = log.lines.filter { it.startsWith("WS_DEGRADED detected") }
        assertEquals(1, risingLines.size, "rising edge fires exactly once")
        val verdict = risingLines[0]
        assertTrue(verdict.contains("would_rewalk=true"))
        assertTrue(verdict.contains("ping_timeout_count=1"))
        assertTrue(verdict.contains("ack_timeout_count=1"))
        // idle was recorded AFTER the verdict snapshot — it does not
        // appear in this line, only in the subsequent counter tick.
        assertTrue(verdict.contains("idle_timeout_count=0"))
    }
}
