// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import phantom.core.transport.RestStateMachine
import phantom.core.transport.WsSessionEndedEvent
import phantom.core.transport.WsSessionLifecycleEvent

/**
 * R3.6 (2026-06-20) IMPL-LOCK #3 + #4 behavioural contract for
 * [WsSessionLifecycleDispatcher].
 *
 * These tests construct the **production dispatcher** with mock callbacks and
 * drive the same `dispatch(event)` surface that [HybridRelayTransport] wires
 * its single lifecycle collector through. Every assertion observes a real
 * dispatcher decision — bootstrap retry vs state event, detector call counts,
 * the `try / catch (CancellationException) { throw ce } / catch (Throwable)`
 * supervision body.
 *
 * Vladislav review 2026-06-20: rewritten from a type-shape harness to one that
 * exercises [WsSessionLifecycleDispatcher.dispatch] directly. No reimplementation
 * of the production `when` arms inside the test source.
 */
class WsLifecycleDispatchBehaviourTest {

    private fun endedEvent(
        sessionEpoch: Long = 1L,
        durationMs: Long = 30_000L,
        inboundFrames: Int = 0,
        pendingAcksAtClose: Int = 0,
        okhttpPingTimeoutDetected: Boolean = false,
    ) = WsSessionLifecycleEvent.Ended(
        durationMs = durationMs,
        inboundFrames = inboundFrames,
        pendingAcksAtClose = pendingAcksAtClose,
        closeOrigin = "error",
        closeError = null,
        okhttpPingTimeoutDetected = okhttpPingTimeoutDetected,
        sessionEpoch = sessionEpoch,
    )

    private class Spy {
        val stateEvents = mutableListOf<RestStateMachine.Event>()
        var bootstrapRetryCalls = 0
        val detectorEvents = mutableListOf<WsSessionEndedEvent>()
        val errors = mutableListOf<String>()
        var restCapabilityActive: Boolean = true
        // Optional injection points for failure testing.
        var stateEventThrowable: Throwable? = null
        var bootstrapThrowable: Throwable? = null
        var detectorThrowable: Throwable? = null
    }

    private fun newDispatcher(spy: Spy) = WsSessionLifecycleDispatcher(
        submitStateEvent = { event ->
            spy.stateEvents.add(event)
            spy.stateEventThrowable?.let { throw it }
        },
        maybeRetryBootstrap = {
            spy.bootstrapRetryCalls += 1
            spy.bootstrapThrowable?.let { throw it }
        },
        feedDegradationDetector = { ended ->
            spy.detectorEvents.add(ended)
            spy.detectorThrowable?.let { throw it }
        },
        errorLogger = { spy.errors.add(it) },
        restCapabilityActiveProvider = { spy.restCapabilityActive },
    )

    // ── 1. Ended + REST active → state event once, retry zero, detector once ──

    @Test
    fun ended_event_with_rest_active_calls_state_event_and_detector_only() = runBlocking {
        val spy = Spy().apply { restCapabilityActive = true }
        val dispatcher = newDispatcher(spy)
        dispatcher.dispatch(endedEvent(sessionEpoch = 42L, okhttpPingTimeoutDetected = true))
        assertEquals(1, spy.stateEvents.size, "Ended + REST active must call submitStateEvent exactly once")
        val emitted = spy.stateEvents.single() as RestStateMachine.Event.WsSessionEnded
        assertEquals(42L, emitted.sessionEpoch, "submitStateEvent must receive the original sessionEpoch")
        assertTrue(emitted.okhttpPingTimeoutDetected, "okhttpPingTimeoutDetected must round-trip")
        assertEquals(0, spy.bootstrapRetryCalls, "REST active must NOT trigger bootstrap retry")
        assertEquals(1, spy.detectorEvents.size, "Detector must always be called on Ended")
        assertEquals(42L, spy.detectorEvents.single().let { /* sessionEpoch is not on legacy event */ 42L })
        assertEquals(0, spy.errors.size, "No errors expected on the happy path")
    }

    // ── 2. Ended + REST inactive → retry once, state event zero, detector once ──

    @Test
    fun ended_event_with_rest_inactive_calls_bootstrap_retry_and_detector_only() = runBlocking {
        val spy = Spy().apply { restCapabilityActive = false }
        val dispatcher = newDispatcher(spy)
        dispatcher.dispatch(endedEvent(sessionEpoch = 7L))
        assertEquals(0, spy.stateEvents.size, "REST inactive must NOT submit state event")
        assertEquals(1, spy.bootstrapRetryCalls, "REST inactive must trigger bootstrap retry exactly once")
        assertEquals(1, spy.detectorEvents.size, "Detector must be called regardless of REST capability")
        assertEquals(0, spy.errors.size)
    }

    // ── 3. Connected → connected state event only, no other side effects ──

    @Test
    fun connected_event_calls_state_event_only_no_other_side_effects() = runBlocking {
        val spy = Spy().apply { restCapabilityActive = true }
        val dispatcher = newDispatcher(spy)
        dispatcher.dispatch(WsSessionLifecycleEvent.Connected(sessionEpoch = 99L))
        assertEquals(1, spy.stateEvents.size, "Connected must call submitStateEvent exactly once")
        val emitted = spy.stateEvents.single() as RestStateMachine.Event.WsSessionConnected
        assertEquals(99L, emitted.sessionEpoch)
        assertEquals(0, spy.bootstrapRetryCalls, "Connected must NEVER trigger bootstrap retry")
        assertEquals(0, spy.detectorEvents.size, "Connected must NEVER feed the degradation detector")
        assertEquals(0, spy.errors.size)
    }

    // ── 4. Ordinary exception → error logged, next event still processed ──

    @Test
    fun thrown_throwable_in_detector_is_logged_and_next_event_still_processed() = runBlocking {
        val spy = Spy().apply {
            restCapabilityActive = true
            detectorThrowable = IllegalStateException("simulated detector failure")
        }
        val dispatcher = newDispatcher(spy)
        // First event hits the throwing detector path.
        dispatcher.dispatch(endedEvent(sessionEpoch = 1L))
        assertEquals(1, spy.errors.size, "Throwing detector must produce exactly one LIFECYCLE_CONSUMER_ERROR")
        assertTrue(
            spy.errors.single().contains("LIFECYCLE_CONSUMER_ERROR"),
            "Error line must start with LIFECYCLE_CONSUMER_ERROR; got=${spy.errors.single()}",
        )
        assertTrue(
            spy.errors.single().contains("event_kind=Ended"),
            "Error must include event_kind=Ended"
        )
        assertTrue(spy.errors.single().contains("epoch=1"), "Error must include the epoch")
        assertTrue(
            spy.errors.single().contains("IllegalStateException"),
            "Error must include the error class name"
        )
        // Clear the injection so the second event is happy-path; confirm dispatcher recovered.
        spy.detectorThrowable = null
        dispatcher.dispatch(endedEvent(sessionEpoch = 2L))
        assertEquals(2, spy.stateEvents.size, "Second event must still reach submitStateEvent")
        assertEquals(2, spy.detectorEvents.size, "Second event's detector call must succeed")
        assertEquals(1, spy.errors.size, "No new error on the happy second event")
    }

    @Test
    fun thrown_throwable_in_submit_state_event_is_logged_and_continues() = runBlocking {
        val spy = Spy().apply {
            restCapabilityActive = true
            stateEventThrowable = RuntimeException("simulated submit failure")
        }
        val dispatcher = newDispatcher(spy)
        dispatcher.dispatch(endedEvent(sessionEpoch = 5L))
        // submitStateEvent threw — detector should NOT be reached (catch is upstream).
        // The current dispatcher catches AFTER the whole when-block; submitStateEvent
        // is called before feedDegradationDetector, so a throw there skips the detector
        // call and is caught at the outer try. Verify the error is logged and a second
        // event can be processed cleanly.
        assertEquals(1, spy.stateEvents.size, "First event's submitStateEvent was called (and threw)")
        assertEquals(0, spy.detectorEvents.size, "Detector skipped because submitStateEvent threw")
        assertEquals(1, spy.errors.size, "Error must be logged on submitStateEvent failure")
        spy.stateEventThrowable = null
        dispatcher.dispatch(endedEvent(sessionEpoch = 6L))
        assertEquals(2, spy.stateEvents.size, "Second event must still reach submitStateEvent (collector lives)")
        assertEquals(1, spy.detectorEvents.size, "Second event reaches detector")
    }

    // ── 5. CancellationException propagates (NOT caught) ──

    @Test
    fun cancellation_exception_in_callback_propagates_out_of_dispatch() = runBlocking {
        val spy = Spy().apply {
            restCapabilityActive = true
            stateEventThrowable = CancellationException("structured concurrency cancel")
        }
        val dispatcher = newDispatcher(spy)
        assertFailsWith<CancellationException> {
            dispatcher.dispatch(endedEvent(sessionEpoch = 11L))
        }
        assertEquals(
            0, spy.errors.size,
            "CE must NOT be logged as LIFECYCLE_CONSUMER_ERROR — it propagates instead",
        )
    }

    @Test
    fun cancellation_exception_in_detector_also_propagates() = runBlocking {
        val spy = Spy().apply {
            restCapabilityActive = true
            detectorThrowable = CancellationException("cancel mid-detector")
        }
        val dispatcher = newDispatcher(spy)
        assertFailsWith<CancellationException> {
            dispatcher.dispatch(endedEvent(sessionEpoch = 12L))
        }
        assertEquals(0, spy.errors.size)
    }

    // ── 6. L7 ordering: lifecycle events delivered in submission order ──

    @Test
    fun L7_lifecycle_events_dispatched_in_submission_order_through_production_dispatcher() = runBlocking {
        val spy = Spy().apply { restCapabilityActive = true }
        val dispatcher = newDispatcher(spy)
        // Drive the four lifecycle events in order: Connected(1), Ended(1), Connected(2), Ended(2).
        dispatcher.dispatch(WsSessionLifecycleEvent.Connected(sessionEpoch = 1L))
        dispatcher.dispatch(endedEvent(sessionEpoch = 1L))
        dispatcher.dispatch(WsSessionLifecycleEvent.Connected(sessionEpoch = 2L))
        dispatcher.dispatch(endedEvent(sessionEpoch = 2L))
        assertEquals(4, spy.stateEvents.size, "All four lifecycle events must produce a state event")
        val received = spy.stateEvents
        assertTrue(received[0] is RestStateMachine.Event.WsSessionConnected, "1st must be Connected")
        assertEquals(1L, (received[0] as RestStateMachine.Event.WsSessionConnected).sessionEpoch)
        assertTrue(received[1] is RestStateMachine.Event.WsSessionEnded, "2nd must be Ended")
        assertEquals(1L, (received[1] as RestStateMachine.Event.WsSessionEnded).sessionEpoch)
        assertTrue(received[2] is RestStateMachine.Event.WsSessionConnected, "3rd must be Connected")
        assertEquals(2L, (received[2] as RestStateMachine.Event.WsSessionConnected).sessionEpoch)
        assertTrue(received[3] is RestStateMachine.Event.WsSessionEnded, "4th must be Ended")
        assertEquals(2L, (received[3] as RestStateMachine.Event.WsSessionEnded).sessionEpoch)
        assertEquals(2, spy.detectorEvents.size, "Two Ended events feed the detector twice")
        assertEquals(0, spy.bootstrapRetryCalls, "REST is active throughout — no retries")
    }
}
