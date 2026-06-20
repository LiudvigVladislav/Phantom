// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import phantom.core.transport.RestStateMachine
import phantom.core.transport.WsSessionEndedEvent
import phantom.core.transport.WsSessionLifecycleEvent

/**
 * 3.6 Fast REST degradation + R3.6 sticky (2026-06-20) — propagation contract tests.
 *
 * Lives in `androidUnitTest` next to the production caller in
 * [phantom.android.transport.HybridRelayTransport] so the test can
 * exercise the file-level `internal fun` extensions at the exact
 * visibility the production wiring uses.
 *
 * Covers:
 *   - Legacy [WsSessionEndedEvent.toRestStateMachineEvent] (backward compat)
 *   - R3.6 [WsSessionLifecycleEvent.Ended.toRestStateMachineEvent] (L8/L9)
 *   - R3.6 [WsSessionLifecycleEvent.Ended.toLegacyEndedEvent] (degradation adapter)
 */
class HybridRelayTransportMapperTest {

    // ── Legacy WsSessionEndedEvent mapper (backward-compat) ──────────────

    @Test
    fun mapper_propagates_okhttp_ping_timeout_detected_true() {
        val source = WsSessionEndedEvent(
            durationMs = 31_000L,
            inboundFrames = 0,
            pendingAcksAtClose = 0,
            closeOrigin = "error",
            closeError = "SocketTimeoutException after 0 successful ping/pongs",
            okhttpPingTimeoutDetected = true,
        )
        val target = source.toRestStateMachineEvent()
        assertTrue(
            target.okhttpPingTimeoutDetected,
            "mapper MUST preserve okhttpPingTimeoutDetected=true; the Mode-2 " +
                "signature depends on this propagation surviving the boundary",
        )
    }

    @Test
    fun mapper_propagates_okhttp_ping_timeout_detected_false() {
        val source = WsSessionEndedEvent(
            durationMs = 1_000L,
            inboundFrames = 0,
            pendingAcksAtClose = 0,
            closeOrigin = "remote",
            closeError = null,
            okhttpPingTimeoutDetected = false,
        )
        val target = source.toRestStateMachineEvent()
        assertFalse(
            target.okhttpPingTimeoutDetected,
            "mapper MUST preserve okhttpPingTimeoutDetected=false; absent flag " +
                "must not be inferred as true (would over-trip fast-path on " +
                "server-initiated closes)",
        )
    }

    @Test
    fun mapper_preserves_other_fields() {
        val source = WsSessionEndedEvent(
            durationMs = 42_000L,
            inboundFrames = 3,
            pendingAcksAtClose = 7,
            closeOrigin = "error",
            closeError = "SocketTimeoutException after 1 successful ping/pongs",
            okhttpPingTimeoutDetected = true,
        )
        val target = source.toRestStateMachineEvent()
        assertEquals(42_000L, target.durationMs, "durationMs must survive boundary")
        assertEquals(3, target.inboundFrames, "inboundFrames must survive boundary")
        assertEquals(7, target.pendingAcksAtClose, "pendingAcksAtClose must survive boundary")
    }

    @Test
    fun legacy_mapper_uses_sentinel_epoch_minus1() {
        val source = WsSessionEndedEvent(
            durationMs = 31_000L, inboundFrames = 0, pendingAcksAtClose = 0,
            closeOrigin = "error", closeError = null, okhttpPingTimeoutDetected = false,
        )
        val target = source.toRestStateMachineEvent()
        assertEquals(
            -1L, target.sessionEpoch,
            "legacy mapper must use sentinel epoch -1L so it never matches a live recoveryWsEpoch",
        )
    }

    // ── R3.6 WsSessionLifecycleEvent.Ended mapper (L8/L9) ────────────────

    private fun buildEndedEvent(
        durationMs: Long = 31_000L,
        inboundFrames: Int = 0,
        pendingAcksAtClose: Int = 0,
        okhttpPingTimeoutDetected: Boolean = true,
        sessionEpoch: Long = 42L,
    ) = WsSessionLifecycleEvent.Ended(
        durationMs = durationMs,
        inboundFrames = inboundFrames,
        pendingAcksAtClose = pendingAcksAtClose,
        closeOrigin = "error",
        closeError = null,
        okhttpPingTimeoutDetected = okhttpPingTimeoutDetected,
        sessionEpoch = sessionEpoch,
    )

    @Test
    fun L8_lifecycle_ended_to_state_machine_event_carries_session_epoch() {
        val source = buildEndedEvent(sessionEpoch = 42L)
        val target = source.toRestStateMachineEvent()
        assertEquals(
            42L, target.sessionEpoch,
            "L8: sessionEpoch MUST be propagated by the lifecycle mapper for sticky-recovery epoch filter",
        )
    }

    @Test
    fun L9_lifecycle_ended_to_state_machine_event_preserves_all_fields() {
        val source = buildEndedEvent(
            durationMs = 55_000L,
            inboundFrames = 3,
            pendingAcksAtClose = 7,
            okhttpPingTimeoutDetected = true,
            sessionEpoch = 77L,
        )
        val target = source.toRestStateMachineEvent()
        assertEquals(55_000L, target.durationMs, "L9: durationMs must survive")
        assertEquals(3, target.inboundFrames, "L9: inboundFrames must survive")
        assertEquals(7, target.pendingAcksAtClose, "L9: pendingAcksAtClose must survive")
        assertTrue(target.okhttpPingTimeoutDetected, "L9: okhttpPingTimeoutDetected must survive")
        assertEquals(77L, target.sessionEpoch, "L9: sessionEpoch must survive")
    }

    @Test
    fun lifecycle_ended_okhttp_false_preserved_in_new_mapper() {
        val source = buildEndedEvent(okhttpPingTimeoutDetected = false)
        val target = source.toRestStateMachineEvent()
        assertFalse(
            target.okhttpPingTimeoutDetected,
            "new mapper MUST preserve okhttpPingTimeoutDetected=false",
        )
    }

    // ── R3.6 toLegacyEndedEvent adapter ──────────────────────────────────

    @Test
    fun lifecycle_ended_to_legacy_event_preserves_degradation_fields() {
        val source = buildEndedEvent(
            durationMs = 30_000L,
            inboundFrames = 0,
            pendingAcksAtClose = 1,
            okhttpPingTimeoutDetected = true,
        )
        val legacy = source.toLegacyEndedEvent()
        assertEquals(30_000L, legacy.durationMs)
        assertEquals(0, legacy.inboundFrames)
        assertEquals(1, legacy.pendingAcksAtClose)
        assertTrue(legacy.okhttpPingTimeoutDetected)
        assertEquals("error", legacy.closeOrigin)
    }
}
