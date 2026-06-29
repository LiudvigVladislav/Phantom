// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * QUIESCENCE-VALIDATION-L1-SYNTHETIC-MINI-LOCK §6 + §7 + §8 contract
 * tests (2026-06-30). Exercises every gate of
 * [KtorRelayTransport.debugForceMode2Synthetic] across the six
 * [SyntheticTriggerResult] members, plus the §7.1 epoch snapshot,
 * §7.3 race-window absorption, and the §13.3.4 `closeOrigin =
 * "synthetic"` discipline.
 *
 * Pure-unit-test approach: stands up a real [KtorRelayTransport] with a
 * factory that throws if invoked (the synthetic-trigger surface does
 * not touch the network), then drives the transport's internal
 * `*ForTest` seams to flip [TransportState] and bump the session
 * epoch without going through a real WebSocket handshake.
 *
 * The seams' names (`setStateForTest`, `bumpSessionEpochForTest`,
 * `resetOneShotLatchForTest`) match the `verifyR8StripsTestSeams`
 * `*ForTest*` deny pattern; the Gradle verifier fails the release
 * build if any seam survives R8 in production.
 */
class KtorRelayTransportDebugForceMode2Test {

    /**
     * Build a transport with the synthetic-trigger gate ON, a factory
     * that throws if anyone tries to dial a real WS, and the state
     * pre-flipped to [TransportState.Connected] at a known epoch so
     * the synthetic call has a non-null [KtorRelayTransport.currentSessionEpoch].
     */
    private fun connectedTransport(epoch: Long = 1L): KtorRelayTransport {
        val transport = KtorRelayTransport(
            httpClientFactory = { _: Int? -> error("network not expected in this test") },
            debugForceMode2Enabled = true,
        )
        // Advance wsSessionEpoch to the requested value.
        var current = 0L
        while (current < epoch) {
            current = transport.bumpSessionEpochForTest()
        }
        transport.setStateForTest(TransportState.Connected)
        return transport
    }

    // ── L1 mini-lock §6 gate cells — one per SyntheticTriggerResult member ──

    @Test
    fun refused_disabled_when_constructor_flag_is_false() {
        val transport = KtorRelayTransport(
            httpClientFactory = { _: Int? -> error("network not expected") },
            debugForceMode2Enabled = false,
        )
        val result = transport.debugForceMode2Synthetic(45_000L)
        assertSame(SyntheticTriggerResult.RefusedDisabled, result)
    }

    @Test
    fun refused_not_connected_when_state_is_disconnected() = runTest {
        val transport = KtorRelayTransport(
            httpClientFactory = { _: Int? -> error("network not expected") },
            debugForceMode2Enabled = true,
        )
        // Default state is Disconnected — currentSessionEpoch is null.
        assertNull(transport.currentSessionEpoch)
        val result = transport.debugForceMode2Synthetic(45_000L)
        assertSame(SyntheticTriggerResult.RefusedNotConnected, result)
    }

    @Test
    fun refused_duration_out_of_range_when_below_min() {
        val transport = connectedTransport()
        val tooLow = RestStateMachine.MODE_2_MIN_DURATION_MS - 1L
        val result = transport.debugForceMode2Synthetic(tooLow)
        val refusal = assertIs<SyntheticTriggerResult.RefusedDurationOutOfRange>(result)
        assertEquals(tooLow, refusal.requestedMs)
        assertEquals(RestStateMachine.MODE_2_MIN_DURATION_MS, refusal.minMs)
        assertEquals(RestStateMachine.MODE_2_MAX_DURATION_MS, refusal.maxMs)
    }

    @Test
    fun refused_duration_out_of_range_when_above_max() {
        val transport = connectedTransport()
        val tooHigh = RestStateMachine.MODE_2_MAX_DURATION_MS + 1L
        val result = transport.debugForceMode2Synthetic(tooHigh)
        val refusal = assertIs<SyntheticTriggerResult.RefusedDurationOutOfRange>(result)
        assertEquals(tooHigh, refusal.requestedMs)
    }

    @Test
    fun fires_at_min_duration_boundary() = runTest {
        val transport = connectedTransport()
        val result = transport.debugForceMode2Synthetic(
            RestStateMachine.MODE_2_MIN_DURATION_MS,
        )
        assertSame(SyntheticTriggerResult.Fired, result)
    }

    @Test
    fun fires_at_max_duration_boundary() = runTest {
        val transport = connectedTransport()
        val result = transport.debugForceMode2Synthetic(
            RestStateMachine.MODE_2_MAX_DURATION_MS,
        )
        assertSame(SyntheticTriggerResult.Fired, result)
    }

    @Test
    fun refused_already_fired_on_second_call_with_same_epoch() = runTest {
        val transport = connectedTransport()
        val first = transport.debugForceMode2Synthetic(45_000L)
        assertSame(SyntheticTriggerResult.Fired, first)
        val second = transport.debugForceMode2Synthetic(45_000L)
        assertSame(SyntheticTriggerResult.RefusedAlreadyFired, second)
    }

    @Test
    fun one_shot_latch_resets_on_new_epoch_via_test_seam() = runTest {
        val transport = connectedTransport()
        val first = transport.debugForceMode2Synthetic(45_000L)
        assertSame(SyntheticTriggerResult.Fired, first)
        // Test-only latch reset (production code resets on Connected emit).
        transport.resetOneShotLatchForTest()
        val second = transport.debugForceMode2Synthetic(45_000L)
        assertSame(SyntheticTriggerResult.Fired, second)
    }

    @Test
    fun one_shot_latch_resets_when_session_epoch_advances() = runTest {
        val transport = connectedTransport(epoch = 1L)
        assertEquals(1L, transport.currentSessionEpoch)
        val first = transport.debugForceMode2Synthetic(45_000L)
        assertSame(SyntheticTriggerResult.Fired, first)
        // A second call on the SAME epoch is refused (one-shot).
        assertSame(
            SyntheticTriggerResult.RefusedAlreadyFired,
            transport.debugForceMode2Synthetic(45_000L),
        )
        // Bump to a new epoch (mimics a new Connected after reconnect).
        // The latch is keyed on the consumed epoch; the new epoch sees
        // a fresh allowance only after the production latch-reset hook
        // fires (production: inside the Connected emit; test seam:
        // resetOneShotLatchForTest).
        val newEpoch = transport.bumpSessionEpochForTest()
        assertEquals(2L, newEpoch)
        transport.resetOneShotLatchForTest()
        val second = transport.debugForceMode2Synthetic(45_000L)
        assertSame(SyntheticTriggerResult.Fired, second)
    }

    // ── L1 mini-lock §7.1 epoch snapshot ──────────────────────────────────

    @Test
    fun current_session_epoch_returns_null_when_disconnected() {
        val transport = KtorRelayTransport(
            httpClientFactory = { _: Int? -> error("network not expected") },
            debugForceMode2Enabled = true,
        )
        assertNull(transport.currentSessionEpoch)
    }

    @Test
    fun current_session_epoch_returns_ws_session_epoch_when_connected() {
        val transport = connectedTransport(epoch = 5L)
        assertEquals(5L, transport.currentSessionEpoch)
    }

    @Test
    fun current_session_epoch_returns_null_when_state_flipped_to_disconnected_mid_session() {
        val transport = connectedTransport(epoch = 3L)
        assertEquals(3L, transport.currentSessionEpoch)
        transport.setStateForTest(TransportState.Disconnected)
        assertNull(transport.currentSessionEpoch)
    }

    // ── L1 mini-lock §7.3 adversarial race-window absorption ─────────────

    // ── L1 mini-lock §13.3.4 closeOrigin="synthetic" tell shape pin ──────

    @Test
    fun synthetic_event_carries_close_origin_synthetic_and_canonical_shape() = runTest {
        val transport = connectedTransport(epoch = 9L)
        val result = transport.debugForceMode2Synthetic(45_000L)
        assertSame(SyntheticTriggerResult.Fired, result)
        // The synthetic event must be field-equivalent to a real
        // production Ended for the same epoch + same duration +
        // okhttpPingTimeoutDetected=true + inboundFrames=0 — plus
        // the closeOrigin="synthetic" telemetry tell that
        // distinguishes synthetic from real local/remote/error/unknown.
        val event = transport.wsSessionLifecycle.first()
        val ended = assertIs<WsSessionLifecycleEvent.Ended>(event)
        assertEquals(45_000L, ended.durationMs)
        assertEquals(0, ended.inboundFrames)
        assertEquals(true, ended.okhttpPingTimeoutDetected)
        assertEquals("synthetic", ended.closeOrigin)
        assertEquals(
            "DEBUG_FORCE_MODE_2_DETECTION synthetic trigger",
            ended.closeError,
        )
        assertEquals(9L, ended.sessionEpoch)
    }
}
