// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import kotlinx.coroutines.runBlocking
import phantom.core.transport.RestStateMachine
import phantom.core.transport.WsSessionLifecycleEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * QUIESCENCE-VALIDATION-MC-HALF-MINI-LOCK §4.2 (L-13.3.3) — sequential
 * dispatcher order load-bearing pin (MC-3, 2026-07-01).
 *
 * The `WsSessionLifecycleDispatcher.dispatch(Ended)` MUST invoke the
 * state-machine consumer FIRST and the telemetry (degradation
 * detector) consumer SECOND. A future refactor that flips the order
 * or fans out in parallel breaks this test.
 *
 * Why the order matters (load-bearing):
 *
 *   - The state-machine consumer transitions `WsActive → RestActive`
 *     (or `WsCandidate → RestActive` under R3.6 sticky recovery) on a
 *     qualifying Mode 2 signature. This transition is what arms the
 *     sticky window and — under RC-RECONNECT-QUIESCENCE1 — engages the
 *     gate's `Open → Quiesced` flip.
 *   - The telemetry consumer (`feedDegradationDetector`) increments the
 *     Mode 2 signature counter and emits `WS_DEGRADED` telemetry.
 *
 *   If the order were reversed (telemetry FIRST, state-machine SECOND),
 *   a downstream observer collecting on `WS_DEGRADED` telemetry could
 *   observe a Mode 2 detection BEFORE the state machine has transitioned
 *   to `RestActive` — the telemetry log would claim degradation while
 *   the state machine still reports `WsActive`, which is exactly the
 *   ordering violation the L1 mini-lock L-13.3.3 lock guards against.
 *
 *   Parallelising the two consumers (e.g., wrapping them in `launch { ... }`
 *   inside dispatch) breaks the same invariant more subtly: the ordering
 *   becomes non-deterministic. The state-machine consumer accesses
 *   `_stateMutex`-protected fields via `submitStateEvent → onEvent`; the
 *   telemetry consumer accesses `WsDegradedDetector` internal counters.
 *   A race between them would let a `sticky_armed gen=` log line
 *   interleave with a `WS_DEGRADED counter kind=ping` line in a way
 *   that post-mortem observers cannot reconstruct.
 *
 * The MB half's PR #353 acceptance matrix relied on OBSERVED sequential
 * order (the L1 acceptance matrix at
 * `C:\temp\quiescence-h-me-council-2026-06-29\tests.md` markers
 * assumed `sticky_armed` fires before `WS_DEGRADED` for the same event).
 * MC closes the STRUCTURAL gap — asserting the order at compile-time
 * via a mocked dispatcher rather than trusting field observation.
 */
class WsSessionLifecycleDispatcherSequentialOrderTest {

    /**
     * Assertion pattern: pass ctor lambdas that both increment the same
     * `AtomicInteger` and record their own invocation-order tag. On
     * dispatch of an `Ended` event with `restCapabilityActive == true`,
     * `submitStateEvent` MUST record tag 1 and `feedDegradationDetector`
     * MUST record tag 2.
     */
    @Test
    fun dispatch_ended_invokes_state_machine_consumer_before_telemetry_consumer() = runBlocking {
        val counter = AtomicInteger(0)
        var stateMachineTag = -1
        var telemetryTag = -1

        val dispatcher = WsSessionLifecycleDispatcher(
            submitStateEvent = { _: RestStateMachine.Event ->
                stateMachineTag = counter.incrementAndGet()
            },
            maybeRetryBootstrap = {
                error(
                    "maybeRetryBootstrap must not fire when " +
                        "restCapabilityActiveProvider() == true",
                )
            },
            feedDegradationDetector = { _ ->
                telemetryTag = counter.incrementAndGet()
            },
            errorLogger = { msg ->
                error("dispatch reported a supervised body error: $msg")
            },
            restCapabilityActiveProvider = { true },
        )

        dispatcher.dispatch(
            WsSessionLifecycleEvent.Ended(
                durationMs = 45_000L,
                inboundFrames = 0,
                pendingAcksAtClose = 0,
                closeOrigin = "remote",
                closeError = "test",
                okhttpPingTimeoutDetected = true,
                sessionEpoch = 1L,
            ),
        )

        assertEquals(
            1,
            stateMachineTag,
            "submitStateEvent MUST be invoked FIRST (tag 1). Actual: $stateMachineTag. " +
                "Reversing the order would let a Mode 2 signature reach the " +
                "telemetry counter BEFORE the state machine transitions to " +
                "RestActive — the exact race the L-13.3.3 sequential-order " +
                "lock guards against.",
        )
        assertEquals(
            2,
            telemetryTag,
            "feedDegradationDetector MUST be invoked SECOND (tag 2). Actual: $telemetryTag.",
        )
    }

    /**
     * Same ordering invariant under the REST-inactive branch: when
     * `restCapabilityActiveProvider() == false`, dispatch takes the
     * `maybeRetryBootstrap` path (NOT submitStateEvent), then still
     * fires `feedDegradationDetector` afterwards. The bootstrap-retry
     * path MUST still precede the telemetry consumer.
     */
    @Test
    fun dispatch_ended_bootstrap_retry_precedes_telemetry_consumer() = runBlocking {
        val counter = AtomicInteger(0)
        var bootstrapTag = -1
        var telemetryTag = -1

        val dispatcher = WsSessionLifecycleDispatcher(
            submitStateEvent = { _: RestStateMachine.Event ->
                error(
                    "submitStateEvent must not fire when " +
                        "restCapabilityActiveProvider() == false — the " +
                        "bootstrap-retry path takes over",
                )
            },
            maybeRetryBootstrap = {
                bootstrapTag = counter.incrementAndGet()
            },
            feedDegradationDetector = { _ ->
                telemetryTag = counter.incrementAndGet()
            },
            errorLogger = { msg ->
                error("dispatch reported a supervised body error: $msg")
            },
            restCapabilityActiveProvider = { false },
        )

        dispatcher.dispatch(
            WsSessionLifecycleEvent.Ended(
                durationMs = 45_000L,
                inboundFrames = 0,
                pendingAcksAtClose = 0,
                closeOrigin = "remote",
                closeError = "test",
                okhttpPingTimeoutDetected = true,
                sessionEpoch = 1L,
            ),
        )

        assertEquals(
            1,
            bootstrapTag,
            "maybeRetryBootstrap MUST fire FIRST (tag 1) on the REST-inactive branch. " +
                "Actual: $bootstrapTag.",
        )
        assertEquals(
            2,
            telemetryTag,
            "feedDegradationDetector MUST fire SECOND (tag 2). Actual: $telemetryTag.",
        )
    }

    /**
     * `Connected` events are single-consumer only (`submitStateEvent`) —
     * no telemetry call, no ordering to enforce beyond "the state
     * machine gets the event". This test pins that shape so a future
     * refactor that adds a second consumer for `Connected` surfaces
     * here first.
     */
    @Test
    fun dispatch_connected_invokes_only_state_machine_consumer() = runBlocking {
        val counter = AtomicInteger(0)
        var stateMachineTag = -1

        val dispatcher = WsSessionLifecycleDispatcher(
            submitStateEvent = { _: RestStateMachine.Event ->
                stateMachineTag = counter.incrementAndGet()
            },
            maybeRetryBootstrap = {
                error("maybeRetryBootstrap must not fire on Connected")
            },
            feedDegradationDetector = { _ ->
                error("feedDegradationDetector must not fire on Connected")
            },
            errorLogger = { msg ->
                error("dispatch reported a supervised body error: $msg")
            },
            restCapabilityActiveProvider = { true },
        )

        dispatcher.dispatch(
            WsSessionLifecycleEvent.Connected(
                sessionEpoch = 7L,
                connectionGeneration = 3L,
            ),
        )

        assertEquals(
            1,
            stateMachineTag,
            "submitStateEvent MUST fire exactly once on Connected. Actual: $stateMachineTag.",
        )
        assertEquals(
            1,
            counter.get(),
            "Only ONE consumer must fire on Connected (state machine). A future " +
                "refactor adding a second Connected consumer breaks this pin " +
                "before it can regress the invariant.",
        )
    }
}
