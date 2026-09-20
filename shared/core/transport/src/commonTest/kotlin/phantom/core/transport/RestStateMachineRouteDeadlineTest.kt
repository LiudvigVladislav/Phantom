// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * residual N1 / F3 — the post-route-change recovery deadline.
 *
 * The Mac runtime campaign measured `WS_ACTIVE` 61 823 ms after the final
 * effective route change, against a declared external limit of 60 000 ms:
 *
 *   20:15:43.929  NETWORK_TRACE changed CELLULAR -> WIFI      (external t0)
 *   20:15:45.746  RestActive -> WS_CANDIDATE ws_connected      (+1 817 ms)
 *   20:16:45.752  WsCandidate -> WS_ACTIVE ws_alive_60s        (+60 006 ms)
 *
 * The overflow is arithmetic, not scheduler jitter: the dwell clock starts when
 * the candidate is entered, which is *after* the socket reconnects, while the
 * external oracle starts at the route change. With a dwell of exactly the
 * external limit, any reconnect latency at all puts promotion past the limit.
 *
 * These tests pin the relationship rather than a magic number, so the internal
 * budget can never silently grow back to consume the whole external limit.
 */
class RestStateMachineRouteDeadlineTest {

    private class FakeClock(var nowMs: Long = 0L) {
        fun advance(ms: Long) { nowMs += ms }
    }

    private fun build(clock: FakeClock, logSink: MutableList<String>? = null): RestStateMachine =
        RestStateMachine(now = { clock.nowMs }, log = { logSink?.add(it) ?: Unit })

    private fun connected(epoch: Long) =
        RestStateMachine.Event.WsSessionConnected(sessionEpoch = epoch, connectionGeneration = -1L)
    private fun tick(epoch: Long) = RestStateMachine.Event.WsAliveTickElapsed(epoch)
    private fun frame(epoch: Long) = RestStateMachine.Event.WsFrameTextReceived(epoch)
    private fun ended(epoch: Long) = RestStateMachine.Event.WsSessionEnded(
        durationMs = 1_000L,
        inboundFrames = 1,
        pendingAcksAtClose = 0,
        okhttpPingTimeoutDetected = false,
        sessionEpoch = epoch,
    )

    /**
     * The measured field case, replayed on the virtual clock: a route change
     * kills the live candidate, the socket takes 1 817 ms to come back, and the
     * new candidate must reach `WsActive` within 60 000 ms of the route change.
     */
    @Test
    fun promotion_fits_the_external_limit_after_a_route_change() = runTest {
        val clock = FakeClock(nowMs = 1_000_000L)
        val sm = build(clock)

        // A proven session exists before the flap.
        sm.onEvent(connected(13))
        sm.onEvent(frame(13))
        clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS)
        sm.onEvent(tick(13))
        assertEquals(RestMode.WsActive, sm.current, "precondition: a proven session before the flap")

        // t0: the effective route change. The live session regresses.
        val routeChangeAtMs = clock.nowMs
        sm.onEvent(ended(13))

        // The socket reconnects after the latency the campaign measured.
        clock.advance(MEASURED_RECONNECT_MS)
        sm.onEvent(connected(14))
        sm.onEvent(frame(14))

        // Drive alive ticks until the machine promotes, bounded by the external
        // limit so a non-promoting machine fails instead of looping.
        var promotedAtMs: Long? = null
        while (clock.nowMs - routeChangeAtMs <= EXTERNAL_LIMIT_MS) {
            sm.onEvent(tick(14))
            if (sm.current == RestMode.WsActive) {
                promotedAtMs = clock.nowMs
                break
            }
            clock.advance(TICK_STEP_MS)
        }

        val elapsed = (promotedAtMs ?: Long.MAX_VALUE) - routeChangeAtMs
        assertTrue(
            promotedAtMs != null && elapsed <= EXTERNAL_LIMIT_MS,
            "WS_ACTIVE must be reached within $EXTERNAL_LIMIT_MS ms of the effective route " +
                "change; measured ${if (promotedAtMs == null) "never promoted" else "$elapsed ms"}",
        )
    }

    /**
     * The internal dwell must leave a measurable margin under the external
     * limit. A dwell equal to the limit cannot fit inside it once any reconnect
     * latency is added, which is exactly how the field run overran.
     */
    @Test
    fun the_internal_dwell_leaves_a_measurable_margin() {
        assertTrue(
            RestStateMachine.ROUTE_RECOVERY_MARGIN_MS > 0,
            "the internal budget must reserve a margin for reconnect and scheduling",
        )
        assertEquals(
            RestStateMachine.ROUTE_RECOVERY_EXTERNAL_LIMIT_MS - RestStateMachine.ROUTE_RECOVERY_MARGIN_MS,
            RestStateMachine.CANDIDATE_COMMIT_MS,
            "the dwell must be the external limit minus the reserved margin",
        )
        assertTrue(
            RestStateMachine.CANDIDATE_COMMIT_MS + MEASURED_RECONNECT_MS
                <= RestStateMachine.ROUTE_RECOVERY_EXTERNAL_LIMIT_MS,
            "dwell plus the reconnect latency measured in the field must still fit the limit",
        )
    }

    /**
     * Generation safety: a tick addressed to a superseded session must never
     * publish. Route flapping produced epochs 10, 11, 13, 14 in the field.
     */
    @Test
    fun a_stale_generation_tick_cannot_publish() = runTest {
        val clock = FakeClock(nowMs = 1_000_000L)
        val log = mutableListOf<String>()
        val sm = build(clock, log)

        sm.onEvent(connected(14))
        sm.onEvent(frame(14))
        clock.advance(RestStateMachine.CANDIDATE_COMMIT_MS)

        // A tick for the previous generation must be ignored.
        sm.onEvent(tick(13))
        assertEquals(
            RestMode.WsCandidate, sm.current,
            "a tick from a superseded generation must not promote the current candidate",
        )
        assertTrue(
            log.any { it.contains("stale_alive_tick_ignored") },
            "the machine must record that it ignored the stale tick; log was $log",
        )

        // The current generation still promotes.
        sm.onEvent(tick(14))
        assertEquals(RestMode.WsActive, sm.current)
        assertEquals(14L, sm.provenSessionEpoch)
    }

    private companion object {
        /** The external oracle from the runtime plan: 60 s from the route change. */
        const val EXTERNAL_LIMIT_MS = 60_000L

        /** `20:15:45.746 - 20:15:43.929` from the scenario-3 phone log. */
        const val MEASURED_RECONNECT_MS = 1_817L

        const val TICK_STEP_MS = 500L
    }
}
