// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import phantom.core.transport.RestStateMachine
import phantom.core.transport.WsSessionLifecycleEvent

/**
 * R3.6 Sticky-per-route Fast REST degradation (2026-06-20) — lifecycle
 * contract tests L5/L6/L7.
 *
 * L5: single ordered stream — ordering guarantee at the channel level.
 * L6: burst capacity — Channel.UNLIMITED never drops under backpressure.
 * L7: HybridRelayTransport consumer sees both Connected and Ended events in order.
 *
 * Uses [runBlocking] rather than `runTest` because `kotlinx-coroutines-test`
 * is not declared in `apps/android` test dependencies. [runBlocking] from
 * `coroutines-core` (present transitively via `coroutines-android`) is
 * sufficient for these synchronous-channel ordering proofs.
 */
class WsSessionLifecycleContractTest {

    private fun buildEndedEvent(sessionEpoch: Long) = WsSessionLifecycleEvent.Ended(
        durationMs = 30_000L,
        inboundFrames = 0,
        pendingAcksAtClose = 0,
        closeOrigin = "error",
        closeError = null,
        okhttpPingTimeoutDetected = true,
        sessionEpoch = sessionEpoch,
    )

    // ── L5: single ordered stream ────────────────────────────────────────

    @Test
    fun L5_lifecycle_events_arrive_in_emission_order() = runBlocking {
        val channel = Channel<WsSessionLifecycleEvent>(Channel.UNLIMITED)
        val flow = channel.receiveAsFlow()

        // Producer emits Connected(1), Ended(1), Connected(2), Ended(2).
        check(channel.trySend(WsSessionLifecycleEvent.Connected(1L)).isSuccess)
        check(channel.trySend(buildEndedEvent(sessionEpoch = 1L)).isSuccess)
        check(channel.trySend(WsSessionLifecycleEvent.Connected(2L)).isSuccess)
        check(channel.trySend(buildEndedEvent(sessionEpoch = 2L)).isSuccess)
        channel.close()

        val collected = flow.toList()
        assertEquals(4, collected.size, "All 4 events must arrive")
        assertTrue(collected[0] is WsSessionLifecycleEvent.Connected &&
            (collected[0] as WsSessionLifecycleEvent.Connected).sessionEpoch == 1L,
            "First must be Connected(1)")
        assertTrue(collected[1] is WsSessionLifecycleEvent.Ended &&
            (collected[1] as WsSessionLifecycleEvent.Ended).sessionEpoch == 1L,
            "Second must be Ended(1)")
        assertTrue(collected[2] is WsSessionLifecycleEvent.Connected &&
            (collected[2] as WsSessionLifecycleEvent.Connected).sessionEpoch == 2L,
            "Third must be Connected(2)")
        assertTrue(collected[3] is WsSessionLifecycleEvent.Ended &&
            (collected[3] as WsSessionLifecycleEvent.Ended).sessionEpoch == 2L,
            "Fourth must be Ended(2)")
    }

    // ── L6: burst does not drop Ended events ────────────────────────────

    @Test
    fun L6_burst_of_connected_ended_pairs_does_not_drop_events() = runBlocking {
        val channel = Channel<WsSessionLifecycleEvent>(Channel.UNLIMITED)

        // Producer emits 100 Connected/Ended pairs without consumer pulling.
        val expectedCount = 200
        for (i in 1..100) {
            val result1 = channel.trySend(WsSessionLifecycleEvent.Connected(i.toLong()))
            assertTrue(result1.isSuccess, "trySend Connected($i) must succeed on UNLIMITED channel")
            val result2 = channel.trySend(buildEndedEvent(sessionEpoch = i.toLong()))
            assertTrue(result2.isSuccess, "trySend Ended($i) must succeed on UNLIMITED channel")
        }
        channel.close()

        // Now consumer starts collecting.
        val collected = channel.receiveAsFlow().toList()
        assertEquals(expectedCount, collected.size,
            "All $expectedCount events must arrive without drops")

        // Verify all Ended(N) are present.
        val endedEpochs = collected
            .filterIsInstance<WsSessionLifecycleEvent.Ended>()
            .map { it.sessionEpoch }
            .toSet()
        for (i in 1..100) {
            assertTrue(i.toLong() in endedEpochs,
                "Ended(epoch=$i) must be present in burst collection")
        }
    }

    // ── L7: production WsSessionLifecycleDispatcher orders events correctly ─

    /**
     * L7 (Vladislav review 2026-06-20 — Fix #4 part 3): the channel → flow →
     * dispatcher pipeline must deliver state-machine events to
     * [WsSessionLifecycleDispatcher] in submission order. Constructs the
     * **production dispatcher** with spy callbacks, drains a channel through
     * `receiveAsFlow()` and `dispatcher.dispatch(event)` for each event, and
     * asserts the resulting state-machine event order matches the wire order.
     * No `when` reimplementation in this test — all routing decisions are made
     * by the production dispatcher.
     */
    @Test
    fun L7_production_dispatcher_routes_events_in_order_through_real_channel_flow() = runBlocking {
        val channel = Channel<WsSessionLifecycleEvent>(Channel.UNLIMITED)

        check(channel.trySend(WsSessionLifecycleEvent.Connected(7L)).isSuccess)
        check(channel.trySend(buildEndedEvent(sessionEpoch = 7L)).isSuccess)
        check(channel.trySend(WsSessionLifecycleEvent.Connected(8L)).isSuccess)
        check(channel.trySend(buildEndedEvent(sessionEpoch = 8L)).isSuccess)
        channel.close()

        val stateMachineEvents = mutableListOf<RestStateMachine.Event>()
        val detectorEvents = mutableListOf<phantom.core.transport.WsSessionEndedEvent>()
        var bootstrapRetryCalls = 0

        val dispatcher = WsSessionLifecycleDispatcher(
            submitStateEvent = { event -> stateMachineEvents.add(event) },
            maybeRetryBootstrap = { bootstrapRetryCalls += 1 },
            feedDegradationDetector = { ended -> detectorEvents.add(ended) },
            errorLogger = { /* ignored — happy path */ },
            restCapabilityActiveProvider = { true },
        )

        launch {
            channel.receiveAsFlow().collect { event ->
                dispatcher.dispatch(event)
            }
        }.join()

        assertEquals(4, stateMachineEvents.size,
            "Dispatcher must produce 4 state-machine events for 4 lifecycle events")
        val first = stateMachineEvents[0] as RestStateMachine.Event.WsSessionConnected
        val second = stateMachineEvents[1] as RestStateMachine.Event.WsSessionEnded
        val third = stateMachineEvents[2] as RestStateMachine.Event.WsSessionConnected
        val fourth = stateMachineEvents[3] as RestStateMachine.Event.WsSessionEnded
        assertEquals(7L, first.sessionEpoch, "1st event: WsSessionConnected(7)")
        assertEquals(7L, second.sessionEpoch, "2nd event: WsSessionEnded(sessionEpoch=7)")
        assertEquals(8L, third.sessionEpoch, "3rd event: WsSessionConnected(8)")
        assertEquals(8L, fourth.sessionEpoch, "4th event: WsSessionEnded(sessionEpoch=8)")
        assertEquals(2, detectorEvents.size,
            "Detector must be fed exactly twice — once per Ended branch")
        assertEquals(0, bootstrapRetryCalls,
            "REST is active throughout — bootstrap retry must NEVER fire")
    }
}
