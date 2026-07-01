// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * QUIESCENCE-VALIDATION-MC-HALF-MINI-LOCK §6 SHOULD-reinforce cell
 * (MC-3, 2026-07-01) for hypothesis **H-330-Preserves-REST**.
 *
 * Coverage story for this PARTIAL hypothesis is distributed across
 * the MC stack:
 *
 *   - The gate-aware `WsActive → RestActive` transition itself is
 *     asserted at ctor-wiring level by
 *     `RestFallbackOrchestratorQuiescenceWiringTest.armSticky_engages_quiescence_when_currentKindProvider_returns_Direct`
 *     (MC-3, byte-identical with PR #330).
 *   - The gate state-transition atomicity is asserted by
 *     `WsReconnectGateTest` (MC-1, 34 cells).
 *
 * This cell adds the "state stays in `RestActive` across the
 * quiescence window" reinforcement at the state-machine layer:
 * after a Mode 2 signature drives `WsActive → RestActive` under
 * `reconnectQuiescenceEnabled = true`, subsequent qualifying
 * `WsSessionEnded` events (retry-tick artefacts, delayed real close
 * of the dying session, etc.) must NOT bounce the state back out of
 * `RestActive`. The L1 D-1 dedup verdict (RestActive arm silently
 * absorbs duplicates) is what makes REST-side inbound delivery
 * uninterrupted; this cell pins that the guard survives with the
 * quiescence gate ARMED.
 */
class GateQuiescenceRestPreservationReinforcementTest {

    private fun newSm(): RestStateMachine =
        RestStateMachine(
            now = { 0L },
            log = { /* silent */ },
            mode2FastPathEnabled = true,
            mode2StickyEnabled = true,
            reconnectQuiescenceEnabled = true,
            currentKindProvider = { TransportKind.Direct },
            tokenSource = { 0xDEADBEEFL },
        )

    @Test
    fun rest_active_state_survives_across_quiescence_window_under_gate_armed() {
        val sm = newSm()

        // 1. Connected: state = WsActive
        sm.onEventNow(
            RestStateMachine.Event.WsSessionConnected(sessionEpoch = 1L),
        )
        assertEquals(RestMode.WsActive, sm.state.value, "cold-start after Connected must be WsActive")

        // 2. Mode 2 signature drives WsActive → RestActive + engages gate.
        sm.onEventNow(
            RestStateMachine.Event.WsSessionEnded(
                durationMs = 45_000L,
                inboundFrames = 0,
                pendingAcksAtClose = 0,
                sessionEpoch = 1L,
                okhttpPingTimeoutDetected = true,
            ),
        )
        assertEquals(
            RestMode.RestActive,
            sm.state.value,
            "first Mode-2-signature Ended must drive WsActive → RestActive",
        )
        val gateAfterFirstEnded = sm.gate.value
        assertEquals(
            "Quiesced",
            gateAfterFirstEnded.simpleKind(),
            "gate must engage Quiesced on the first Mode-2 armSticky under " +
                "reconnectQuiescenceEnabled + Direct — got $gateAfterFirstEnded",
        )

        // 3. Repeat Ended events for the same epoch — the L1 D-1 dedup
        // verdict says the `RestActive` arm silently absorbs
        // duplicates. Under gate-armed conditions, that guarantee MUST
        // still hold — otherwise a subsequent `WsSessionEnded` (from a
        // real socket close arriving after synthetic, or from a delayed
        // watchdog tick) could bounce state out of `RestActive` and
        // interrupt REST inbound delivery mid-quiescence-window. The
        // gate must NOT change either — it stays `Quiesced` until a
        // route change or successful proof.
        repeat(3) { i ->
            sm.onEventNow(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 45_000L + i * 100L,
                    inboundFrames = 0,
                    pendingAcksAtClose = 0,
                    sessionEpoch = 1L,
                    okhttpPingTimeoutDetected = true,
                ),
            )
            assertEquals(
                RestMode.RestActive,
                sm.state.value,
                "duplicate Ended #${i + 1} must be silently absorbed by RestActive arm " +
                    "(D-1 dedup verdict); state must NOT bounce out of RestActive " +
                    "or REST inbound delivery is interrupted mid-quiescence-window",
            )
            assertEquals(
                "Quiesced",
                sm.gate.value.simpleKind(),
                "gate must stay Quiesced across duplicate Endeds — " +
                    "seen ${sm.gate.value} after Ended #${i + 1}",
            )
        }

        // 4. A `WsAliveTickElapsed` event without a matching gate epoch
        // must NOT change state (the state machine is not in
        // `WsCandidate`; the tick is stale for this quiescence
        // window). This pins that stray ticks do not accidentally
        // wake `RestActive` back into a candidate mode.
        sm.onEventNow(RestStateMachine.Event.WsAliveTickElapsed)
        assertEquals(
            RestMode.RestActive,
            sm.state.value,
            "stray WsAliveTickElapsed must NOT bounce state — must stay RestActive",
        )
        assertEquals(
            "Quiesced",
            sm.gate.value.simpleKind(),
            "stray WsAliveTickElapsed must NOT reset the gate — must stay Quiesced",
        )
    }
}
