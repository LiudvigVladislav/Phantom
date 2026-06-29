// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
    fun one_shot_latch_gives_fresh_allowance_on_new_epoch_without_reset() = runTest {
        // PR #353 round 3 (2026-06-30): production code no longer
        // resets `oneShotLatchConsumedAtEpoch` on Connected — the
        // semantic ("one synthetic per Connected epoch") is preserved
        // purely by the equality check inside
        // `debugForceMode2Synthetic`. Each new epoch sees a stale
        // "last consumed epoch" that naturally fails to equal it and
        // is allowed to fire.
        val transport = connectedTransport(epoch = 1L)
        assertEquals(1L, transport.currentSessionEpoch)
        val first = transport.debugForceMode2Synthetic(45_000L)
        assertSame(SyntheticTriggerResult.Fired, first)
        // A second call on the SAME epoch is refused (one-shot).
        assertSame(
            SyntheticTriggerResult.RefusedAlreadyFired,
            transport.debugForceMode2Synthetic(45_000L),
        )
        // Bump to a new epoch WITHOUT resetting the latch — this is
        // what production reconnect-loop does after the round-3 fix.
        val newEpoch = transport.bumpSessionEpochForTest()
        assertEquals(2L, newEpoch)
        // Synthetic fires because `currentLatchEpoch (1) != currentEpoch (2)`.
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

    /**
     * D-1 dedup mechanism load-bearing test: when two
     * `Ended(sessionEpoch=N)` events arrive at the state machine
     * sequentially (the synthetic+real race outcome after both reach
     * the lifecycle channel), the second event is silently absorbed
     * by the `RestActive` arm of [RestStateMachine.onWsSessionEnded].
     * This is the actual D-1 guarantee — the latch atomicity above
     * preserves the operator-facing one-shot contract, but the state
     * machine's mode-transition arm is what makes the race itself
     * benign.
     */
    @Test
    fun rest_state_machine_silently_absorbs_duplicate_ended_for_same_epoch() {
        var clock = 100L
        val logs = mutableListOf<String>()
        val machine = RestStateMachine(
            now = { clock },
            log = { logs.add(it) },
            mode2FastPathEnabled = true,
            mode2StickyEnabled = true,
        )
        // Establish a Connected session at epoch 1.
        machine.onEvent(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 1L))
        assertEquals(RestMode.WsActive, machine.current)
        // First `Ended` for epoch 1 — matches the Mode 2 signature and
        // drives the state machine through transitionToRest +
        // armSticky.
        clock += 30_000L
        machine.onEvent(
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
            machine.current,
            "First Ended for epoch 1 (matching Mode 2 signature) MUST drive " +
                "the state machine to RestActive via the fast-path.",
        )
        val stickyArmedAfterFirst =
            logs.count { it.contains("REST_TRACE sticky_armed gen=") }
        assertEquals(
            1,
            stickyArmedAfterFirst,
            "First Ended MUST emit exactly one `sticky_armed gen=` log line.",
        )
        // Second `Ended` for the SAME epoch 1 — the synthetic+real
        // race outcome. The state machine is now `RestActive`; the
        // `RestActive` arm of `onWsSessionEnded` silently absorbs the
        // duplicate. State stays `RestActive`; no second `armSticky`
        // fires; no `mode_2_signature_matched` line repeats.
        val logSizeBeforeSecond = logs.size
        machine.onEvent(
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
            machine.current,
            "Second Ended for the SAME epoch 1 MUST be silently absorbed in " +
                "the RestActive arm — state stays RestActive (D-1 dedup verdict).",
        )
        val stickyArmedAfterSecond =
            logs.count { it.contains("REST_TRACE sticky_armed gen=") }
        assertEquals(
            1,
            stickyArmedAfterSecond,
            "Second Ended MUST NOT cause a second `armSticky` call — the " +
                "single `sticky_armed gen=` log line from the first event is " +
                "the load-bearing single-actuation guarantee.",
        )
        val secondPassLogs = logs.subList(logSizeBeforeSecond, logs.size)
        assertTrue(
            secondPassLogs.none { it.contains("mode_2_signature_matched") },
            "Second Ended MUST NOT emit `mode_2_signature_matched` — the " +
                "signature check sits inside the `WsActive` arm and is not " +
                "re-reached. Second-pass logs (should be empty): $secondPassLogs",
        )
    }

    /**
     * Atomic latch load-bearing test: two concurrent
     * [KtorRelayTransport.debugForceMode2Synthetic] calls on the same
     * epoch MUST produce exactly one [SyntheticTriggerResult.Fired]
     * and one refusal. The latch's `Mutex.tryLock()` shape (round 2
     * fix) makes the check-then-set atomic so a concurrent
     * double-fire cannot enqueue two synthetic `Ended` events for the
     * same epoch.
     */
    @Test
    fun concurrent_double_fire_yields_exactly_one_fired_result() = runTest {
        val transport = connectedTransport(epoch = 13L)
        // Two concurrent invocations on the SAME epoch. The atomic
        // latch guarantees exactly one wins.
        val results: List<SyntheticTriggerResult> = coroutineScope {
            (0 until 2).map {
                async(Dispatchers.Default) {
                    transport.debugForceMode2Synthetic(45_000L)
                }
            }.awaitAll()
        }
        val firedCount = results.count { it is SyntheticTriggerResult.Fired }
        val alreadyFiredCount = results.count {
            it is SyntheticTriggerResult.RefusedAlreadyFired
        }
        assertEquals(
            1,
            firedCount,
            "Exactly one of the two concurrent calls MUST return Fired. " +
                "Results: $results",
        )
        assertEquals(
            1,
            alreadyFiredCount,
            "Exactly one of the two concurrent calls MUST return " +
                "RefusedAlreadyFired (atomic latch contention or post-claim " +
                "epoch match). Results: $results",
        )
        // The channel must carry EXACTLY one synthetic Ended event
        // for this epoch (the one Fired call's enqueue). Read the
        // first event, then assert the channel has NO second event
        // waiting via withTimeoutOrNull (round-3 fix: round 2 used
        // .take(1).toList() which would silently pass if a second
        // event were also enqueued).
        val ended = assertIs<WsSessionLifecycleEvent.Ended>(
            transport.wsSessionLifecycle.first(),
        )
        assertEquals(13L, ended.sessionEpoch)
        assertEquals("synthetic", ended.closeOrigin)
        val second = withTimeoutOrNull(timeMillis = 200L) {
            transport.wsSessionLifecycle.first()
        }
        assertNull(
            second,
            "Channel MUST carry exactly one synthetic Ended for epoch 13. " +
                "Found a second event after the Fired one: $second. The atomic " +
                "latch claim should have prevented the second concurrent call " +
                "from enqueueing.",
        )
    }

    /**
     * Connected-reset-race test (PR #353 round 3): asserts the
     * round-3 fix where the production reconnect-loop no longer
     * resets `oneShotLatchConsumedAtEpoch` on Connected. The
     * round-2 production code had:
     *
     *   ```
     *   _state.value = Connected; trySend(Connected(mySession));
     *   oneShotLatchConsumedAtEpoch = null  // ← RACE
     *   ```
     *
     * A synthetic that took the mutex between `trySend(Connected)`
     * and the reset would atomically set the latch to `mySession`,
     * then the reset would blow it away, then a SECOND synthetic on
     * the SAME `mySession` epoch would see latch != epoch and fire
     * AGAIN — violating the one-shot contract.
     *
     * Round-3 fix removes the production reset; the equality check
     * `latch == epoch` preserves the semantic on its own (each new
     * Connected brings a new `wsSessionEpoch`).
     */
    @Test
    fun synthetic_claim_survives_test_seam_simulating_connected_emit() = runTest {
        // Simulate the round-2-race: synthetic fires for epoch N (claim
        // sets latch = N), THEN a "Connected emit-like" code path
        // runs that would have, in round 2, cleared the latch.
        // Round-3 production simply does NOT clear; this test asserts
        // that absent the clear, a second synthetic on the same epoch
        // is correctly refused.
        val transport = connectedTransport(epoch = 21L)
        val first = transport.debugForceMode2Synthetic(45_000L)
        assertSame(SyntheticTriggerResult.Fired, first)
        // NOTE: we deliberately do NOT call `resetOneShotLatchForTest()`
        // here — the whole point of the round-3 fix is that production
        // doesn't reset on Connected emit either. The second call on
        // the SAME epoch MUST be refused.
        val second = transport.debugForceMode2Synthetic(45_000L)
        assertSame(
            SyntheticTriggerResult.RefusedAlreadyFired,
            second,
            "After round-3 fix, the production reconnect-loop's Connected " +
                "emit does NOT reset the one-shot latch. A second synthetic " +
                "on the SAME epoch (which would happen if the round-2 race " +
                "had let the reset blow away the claim) MUST be refused.",
        )
    }

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
