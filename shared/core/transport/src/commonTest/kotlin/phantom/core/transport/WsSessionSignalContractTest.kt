// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Stage 2 B1 (2026-09-13): the transport's ONE ordered session-signal
 * channel and its ONE consumer.
 *
 * Before Stage 2 the lifecycle stream had its own channel while frames,
 * acks, pongs, the idle-stall watchdog and the per-envelope ACK deadline
 * reached the state machine through separate flows and separate
 * collectors. The machine therefore saw those signals in COLLECTOR order,
 * not socket order, and none of them carried a session identity: a frame
 * of session N was indistinguishable from one of N+1, and a deadline
 * armed on N fired against whatever session was live when it expired.
 *
 * These cases pin what replaced that: one channel, one subscription, and
 * an identity on every signal. The negative control for each is the
 * pre-Stage-2 shape.
 */
class WsSessionSignalContractTest {

    private fun newTransport(): KtorRelayTransport =
        KtorRelayTransport(httpClientFactory = { error("no network in this fixture") })

    // ── One consumer, enforced by the transport ─────────────────────────────

    @Test
    fun a_second_consumer_is_refused_while_one_is_attached() = runTest {
        val transport = newTransport()
        val first = transport.attachSessionSignalConsumer()
        assertTrue(transport.hasSessionSignalConsumer())
        val failure = assertFailsWith<IllegalStateException> {
            transport.attachSessionSignalConsumer()
        }
        assertTrue(
            failure.message?.contains("already consumed") == true,
            "the refusal must name the reason; got ${failure.message}",
        )
        // A channel-backed flow DISTRIBUTES elements between concurrent
        // collectors, so a second subscriber would steal signals rather
        // than observe them. That is why this is an error and not a fan-out.
        first.detachAndJoin()
        assertFalse(transport.hasSessionSignalConsumer())
        val second = transport.attachSessionSignalConsumer()
        assertTrue(transport.hasSessionSignalConsumer())
        second.detachAndJoin()
    }

    @Test
    fun detach_and_join_waits_for_the_collector_to_unwind() = runTest {
        val transport = newTransport()
        val subscription = transport.attachSessionSignalConsumer()
        val seen = mutableListOf<WsSessionSignal>()
        val collector: Job = launch {
            subscription.signals.collect { seen += it }
        }
        runCurrent()
        assertEquals(1, subscription.activeCollectionCount)
        transport.simulateSessionConnectedForTest(sessionEpoch = 1L)
        runCurrent()
        assertEquals(1, seen.size)

        // The owner cancels its collector FIRST; detachAndJoin only joins.
        collector.cancel()
        runCurrent()
        subscription.detachAndJoin()
        assertTrue(subscription.isDetached)
        assertEquals(0, subscription.activeCollectionCount)
        assertFalse(transport.hasSessionSignalConsumer())
    }

    @Test
    fun collecting_a_detached_subscription_is_a_programming_error() = runTest {
        val transport = newTransport()
        val subscription = transport.attachSessionSignalConsumer()
        subscription.detachAndJoin()
        assertFailsWith<IllegalStateException> {
            subscription.signals.toList()
        }
    }

    // ── Per-collection admission (review round 8, second pass) ─────────────

    @Test
    fun a_refused_second_collection_does_not_decrement_the_real_collector() = runTest {
        // The defect this pins: the admission used
        // `source.onStart { … }.onCompletion { … }`, and `onCompletion`
        // runs when the upstream terminates for ANY reason -- including
        // the `onStart` check throwing. A refused second collection had
        // never incremented the counter, and decremented it anyway.
        val transport = newTransport()
        val subscription = transport.attachSessionSignalConsumer()
        val collector: Job = launch { subscription.signals.collect { } }
        runCurrent()
        assertEquals(1, subscription.activeCollectionCount, "the real collector is in")

        val refusal = assertFailsWith<IllegalStateException> {
            subscription.signals.toList()
        }
        assertTrue(
            refusal.message?.contains("already being collected") == true,
            "the refusal must name the reason; got ${refusal.message}",
        )
        assertEquals(
            1, subscription.activeCollectionCount,
            "a collection that was REFUSED never held a slot, so it must not give one " +
                "back. Dropping to 0 here is what let `detachAndJoin` stop waiting and " +
                "hand the transport's consumer slot to a successor while this collector " +
                "was still reading the channel.",
        )
        assertTrue(transport.hasSessionSignalConsumer(), "and the slot is still held")

        collector.cancel()
        runCurrent()
        subscription.detachAndJoin()
    }

    @Test
    fun detach_and_join_does_not_free_the_slot_until_the_real_collector_ends() = runTest {
        val transport = newTransport()
        val subscription = transport.attachSessionSignalConsumer()
        val collector: Job = launch { subscription.signals.collect { } }
        runCurrent()
        assertEquals(1, subscription.activeCollectionCount)

        val detach: Job = launch { subscription.detachAndJoin() }
        runCurrent()
        assertTrue(
            detach.isActive,
            "detachAndJoin must WAIT: the collector has not unwound, so the slot is " +
                "not free to give away",
        )
        assertTrue(
            transport.hasSessionSignalConsumer(),
            "and the transport must still report a consumer, or a successor could attach",
        )
        assertTrue(subscription.isDetached, "no NEW collector is admitted from now on")

        // A refused collection arriving mid-detach must not release it either.
        assertFailsWith<IllegalStateException> { subscription.signals.toList() }
        runCurrent()
        assertTrue(detach.isActive, "a refused collection is not the collector unwinding")
        assertTrue(transport.hasSessionSignalConsumer())

        collector.cancel()
        runCurrent()
        assertTrue(detach.isCompleted, "once the collector has unwound, the detach completes")
        assertEquals(0, subscription.activeCollectionCount)
        assertFalse(transport.hasSessionSignalConsumer(), "and only then is the slot free")
    }

    @Test
    fun a_collection_after_detach_is_refused_and_leaves_the_count_at_zero() = runTest {
        val transport = newTransport()
        val subscription = transport.attachSessionSignalConsumer()
        subscription.detachAndJoin()
        assertEquals(0, subscription.activeCollectionCount)

        repeat(3) {
            assertFailsWith<IllegalStateException> { subscription.signals.toList() }
        }
        assertEquals(
            0, subscription.activeCollectionCount,
            "a refused collection must leave the count alone; going negative would let " +
                "a later detach believe a collector it never had has finished",
        )
    }

    @Test
    fun two_concurrent_detach_and_joins_wait_for_one_completion() = runTest {
        val transport = newTransport()
        val subscription = transport.attachSessionSignalConsumer()
        val collector: Job = launch { subscription.signals.collect { } }
        runCurrent()

        val first: Job = launch { subscription.detachAndJoin() }
        val second: Job = launch { subscription.detachAndJoin() }
        runCurrent()
        assertTrue(
            first.isActive && second.isActive,
            "neither caller may return while the collector is still reading: the second " +
                "used to see the detached flag the first had just set and return at once",
        )

        collector.cancel()
        runCurrent()
        assertTrue(first.isCompleted && second.isCompleted, "both return on the same completion")
        assertFalse(transport.hasSessionSignalConsumer())

        // The slot was released exactly once, so a successor can take it.
        val successor = transport.attachSessionSignalConsumer()
        assertTrue(transport.hasSessionSignalConsumer())
        successor.detachAndJoin()

        // And a third, later call is still a join rather than a no-op.
        subscription.detachAndJoin()
    }

    // ── Ordering (i): Connected precedes that session's activity ────────────

    @Test
    fun connected_precedes_the_activity_of_the_same_session() = runTest {
        val transport = newTransport()
        val subscription = transport.attachSessionSignalConsumer()
        val seen = mutableListOf<WsSessionSignal>()
        val collector = launch { subscription.signals.collect { seen += it } }

        transport.simulateSessionConnectedForTest(sessionEpoch = 1L)
        transport.simulateInboundDeliverForTest(
            RelayMessage.Deliver(payload = "p", messageId = "m1"),
            sessionEpoch = 1L,
        )
        runCurrent()

        assertEquals(2, seen.size, "both signals must arrive; got $seen")
        val first = assertIs<WsSessionLifecycleEvent.Connected>(seen[0])
        assertEquals(1L, first.sessionEpoch)
        val second = assertIs<WsSessionSignal.Activity>(seen[1])
        assertEquals(WsSessionSignal.ActivityKind.Frame, second.kind)
        assertEquals(WsSessionId(1L), second.sessionId)
        collector.cancel()
    }

    @Test
    fun every_signal_carries_the_session_it_is_about() = runTest {
        val transport = newTransport()
        val subscription = transport.attachSessionSignalConsumer()
        val seen = mutableListOf<WsSessionSignal>()
        val collector = launch { subscription.signals.collect { seen += it } }

        transport.simulateSessionConnectedForTest(sessionEpoch = 4L)
        transport.simulateInboundStallForTest(sessionEpoch = 4L, sinceLastInboundMs = 61_000L)
        transport.simulateSessionEndedForTest(sessionEpoch = 4L)
        runCurrent()

        assertEquals(
            listOf(4L, 4L, 4L),
            seen.map { it.sessionId.sessionEpoch },
            "the identity is on the signal, not inferred at delivery time; got $seen",
        )
        assertIs<WsSessionSignal.Stalled>(seen[1])
        assertIs<WsSessionLifecycleEvent.Ended>(seen[2])
        collector.cancel()
    }

    // ── The ACK deadline names the session that WROTE the frame ─────────────

    @Test
    fun the_ack_deadline_carries_the_epoch_captured_when_it_was_armed() = runTest {
        val transport = newTransport()
        transport.ackDeadlineScopeOverride = this
        val subscription = transport.attachSessionSignalConsumer()
        val deadlines = mutableListOf<WsSessionSignal.AckDeadlineExpired>()
        val collector = launch {
            subscription.signals.collect { if (it is WsSessionSignal.AckDeadlineExpired) deadlines += it }
        }

        val entry = KtorRelayTransport.AckPending(
            message = RelayMessage.Send(
                to = "dd".repeat(32),
                payload = "",
                messageId = "written-by-session-2",
                sealedSender = "",
            ),
            sentAt = kotlin.time.TimeSource.Monotonic.markNow(),
            sequenceTs = transport.nextSequenceTsForTest(),
            queuedAtMs = 0L,
        )
        // Armed while session 2 is the writer; session 3 becomes live before
        // the deadline expires.
        transport.armAckDeadlineForTest(entry, sessionEpoch = 2L)
        transport.simulateSessionConnectedForTest(sessionEpoch = 3L)
        advanceTimeBy(RelayTransportConfig.ACK_DEADLINE_MS + 1L)
        runCurrent()

        assertEquals(1, deadlines.size, "exactly one deadline; got $deadlines")
        assertEquals(
            WsSessionId(2L), deadlines.single().sessionId,
            "the deadline belongs to the session that wrote the frame, not to whichever is live",
        )
        assertEquals("written-by-session-2", deadlines.single().msgId)
        collector.cancel()
    }

    // ── Ordering (ii): a teardown invalidates before it closes ──────────────

    @Test
    fun a_teardown_invalidates_the_session_before_it_cancels_the_loop() = runTest {
        val transport = newTransport()
        val subscription = transport.attachSessionSignalConsumer()
        val seen = mutableListOf<WsSessionSignal>()
        val collector = launch { subscription.signals.collect { seen += it } }

        transport.simulateSessionConnectedForTest(sessionEpoch = 1L)
        runCurrent()
        transport.disconnectAndJoin(timeoutMs = 1_000L, reason = "privacy_mode_changed")
        runCurrent()

        val invalidated = seen.filterIsInstance<WsSessionSignal.Invalidated>()
        assertEquals(1, invalidated.size, "the teardown must invalidate exactly once; got $seen")
        assertEquals(WsSessionId(1L), invalidated.single().sessionId)
        assertEquals(
            "privacy_mode_changed", invalidated.single().reason,
            "B11: a privacy teardown must be distinguishable from a rewalk in the trace",
        )
        collector.cancel()
    }

    @Test
    fun the_teardown_reason_defaults_without_one_being_given() = runTest {
        val transport = newTransport()
        val subscription = transport.attachSessionSignalConsumer()
        val seen = mutableListOf<WsSessionSignal>()
        val collector = launch { subscription.signals.collect { seen += it } }

        transport.simulateSessionConnectedForTest(sessionEpoch = 1L)
        transport.disconnectAndJoin(timeoutMs = 1_000L)
        runCurrent()

        assertEquals(
            "teardown",
            seen.filterIsInstance<WsSessionSignal.Invalidated>().single().reason,
        )
        collector.cancel()
    }

    // ── The channel never drops and never closes ───────────────────────────

    @Test
    fun a_burst_enqueued_before_the_collector_starts_is_not_lost() = runTest {
        val transport = newTransport()
        val subscription = transport.attachSessionSignalConsumer()
        repeat(50) { i ->
            transport.simulateSessionConnectedForTest(sessionEpoch = (i + 1).toLong())
        }
        val seen = mutableListOf<WsSessionSignal>()
        val collector = launch { subscription.signals.collect { seen += it } }
        runCurrent()
        assertEquals(50, seen.size, "an UNLIMITED channel buffers what the consumer has not read")
        assertEquals(
            (1L..50L).toList(),
            seen.map { it.sessionId.sessionEpoch },
            "and it preserves enqueue order",
        )
        collector.cancel()
    }

    @Test
    fun a_cancelled_collector_does_not_close_the_channel() = runTest {
        val transport = newTransport()
        val subscription = transport.attachSessionSignalConsumer()
        val firstRun = mutableListOf<WsSessionSignal>()
        val collector = launch { subscription.signals.collect { firstRun += it } }
        runCurrent()
        collector.cancel()
        runCurrent()

        // The producer keeps enqueueing; a loud failure here would mean the
        // channel had been closed by the collector's termination.
        transport.simulateSessionConnectedForTest(sessionEpoch = 9L)
        val secondRun = mutableListOf<WsSessionSignal>()
        val second = launch { subscription.signals.collect { secondRun += it } }
        runCurrent()
        assertEquals(1, secondRun.size, "the buffered signal survived the collector")
        second.cancel()
    }
}
