// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import phantom.core.transport.WsDegradationDetector
import phantom.core.transport.WsSessionLifecycleEvent

/**
 * R3.6 (2026-06-20) end-to-end check that the
 * [WsSessionLifecycleEvent.Ended] → [toLegacyEndedEvent] →
 * [feedDegradationDetectorOnWsSessionEnded] chain produces the expected
 * `WS_DEGRADED_TELEMETRY` log lines on a **real** [WsDegradationDetector].
 *
 * These tests pin the wire-format the detector emits — the dispatcher
 * behavioural tests in [WsLifecycleDispatchBehaviourTest] verify the
 * dispatcher actually invokes this path, but a spy callback cannot prove
 * the detector itself produces a `session_total` line. This file does.
 *
 * Vladislav review 2026-06-20: rewritten from the misleading
 * "R-A/R-B/R-C" labels (which previously only checked mapper field
 * round-trips, not real side effects) to focus on the
 * detector-wire-format contract. Mapper round-trip is covered in
 * [HybridRelayTransportMapperTest]; collector behaviour in
 * [WsLifecycleDispatchBehaviourTest].
 */
class WsLifecycleCollectorSideEffectsTest {

    private val fixedNow: Long = 1_000_000L

    private fun buildEndedLifecycleEvent(
        durationMs: Long = 30_000L,
        inboundFrames: Int = 0,
        okhttpPingTimeoutDetected: Boolean = true,
        sessionEpoch: Long = 1L,
    ) = WsSessionLifecycleEvent.Ended(
        durationMs = durationMs,
        inboundFrames = inboundFrames,
        pendingAcksAtClose = 0,
        closeOrigin = "error",
        closeError = "SocketTimeoutException",
        okhttpPingTimeoutDetected = okhttpPingTimeoutDetected,
        sessionEpoch = sessionEpoch,
    )

    @Test
    fun detector_emits_session_total_line_on_every_ended_close() {
        val logLines = mutableListOf<String>()
        val detector = WsDegradationDetector(
            now = { fixedNow },
            log = { logLines.add(it) },
        )
        feedDegradationDetectorOnWsSessionEnded(
            detector = detector,
            event = buildEndedLifecycleEvent(okhttpPingTimeoutDetected = false).toLegacyEndedEvent(),
            currentKind = null,
        )
        assertTrue(
            logLines.any { it.contains("session_total") },
            "Detector must log `session_total` on every WsSessionEnded close. logs=$logLines",
        )
    }

    @Test
    fun detector_records_ping_counter_when_ping_timeout_flag_set() {
        val logLines = mutableListOf<String>()
        val detector = WsDegradationDetector(
            now = { fixedNow },
            log = { logLines.add(it) },
        )
        feedDegradationDetectorOnWsSessionEnded(
            detector = detector,
            event = buildEndedLifecycleEvent(okhttpPingTimeoutDetected = true).toLegacyEndedEvent(),
            currentKind = null,
        )
        assertTrue(
            logLines.any { it.contains("WS_DEGRADED_TELEMETRY") && it.contains("kind=ping") },
            "Detector must record a ping counter tick (kind=ping) when okhttpPingTimeoutDetected=true. " +
                "logs=$logLines",
        )
    }

    @Test
    fun detector_records_no_ping_counter_when_flag_unset() {
        val logLines = mutableListOf<String>()
        val detector = WsDegradationDetector(
            now = { fixedNow },
            log = { logLines.add(it) },
        )
        feedDegradationDetectorOnWsSessionEnded(
            detector = detector,
            event = buildEndedLifecycleEvent(okhttpPingTimeoutDetected = false).toLegacyEndedEvent(),
            currentKind = null,
        )
        assertFalse(
            logLines.any { it.contains("WS_DEGRADED_TELEMETRY") && it.contains("kind=ping") },
            "Detector must NOT record a ping counter when okhttpPingTimeoutDetected=false. " +
                "logs=$logLines",
        )
        assertTrue(
            logLines.any { it.contains("session_total") },
            "Mandatory session_total line must still appear even without a ping timeout",
        )
    }
}
