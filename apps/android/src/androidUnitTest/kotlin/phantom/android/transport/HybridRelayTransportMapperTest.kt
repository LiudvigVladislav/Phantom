// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import phantom.core.transport.RestStateMachine
import phantom.core.transport.WsSessionEndedEvent

/**
 * 3.6 Fast REST degradation (2026-06-18) — propagation contract test.
 *
 * Lives in `androidUnitTest` next to the production caller in
 * [phantom.android.transport.HybridRelayTransport] so the test can
 * exercise the file-level `internal fun toRestStateMachineEvent()`
 * extension at the exact visibility the production wiring uses.
 *
 * Pins the invariant that the four fields of [WsSessionEndedEvent] that
 * cross into [RestStateMachine.Event.WsSessionEnded] are preserved
 * losslessly by the mapper. The Mode-2 signature in
 * [RestStateMachine.onWsSessionEnded] depends on
 * `okhttpPingTimeoutDetected` being propagated truthfully — losing it
 * would silently break the fast-path actuation while passing every other
 * test in the suite.
 *
 * If future work adds a field to either side, the mapper must be
 * updated AND a new propagation case added here. The mapper file
 * (`HybridRelayTransport.kt`) and this test should always be edited in
 * the same PR.
 */
class HybridRelayTransportMapperTest {

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
}
