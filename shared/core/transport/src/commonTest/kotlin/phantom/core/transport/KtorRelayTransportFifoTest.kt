// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Verifies the strict-FIFO ordering guarantee introduced in PR-H2a.
 *
 * Bug context (Test #33, 2026-05-13): a read receipt encrypted at Double-
 * Ratchet chain position N+1 reached the receiver BEFORE the user message
 * encrypted at chain position N because the requeue/flush path on
 * reconnect did not preserve encrypt-time order across the
 * live-send → ack-pending → outbox-requeue transition. Receiver's chain
 * key advanced past N+1's MAC key before the late-arriving N could
 * decrypt it; MAC failed; envelope was lost; user saw "сообщение не
 * пришло" while server logs confirmed delivery+ack. These tests pin the
 * fix in place: every envelope carries a sequenceTs assigned ONCE, and
 * both the merge path and the flush snapshot sort by sequenceTs ASC.
 *
 * Tests do not exercise the network — they construct KtorRelayTransport
 * with a factory that intentionally fails if invoked, then drive the
 * outbox/pendingAcks state machine through the internal test-only
 * accessors added at the bottom of KtorRelayTransport. This isolates
 * the FIFO logic from Ktor / OkHttp / WebSocket lifecycle, which is
 * what we want — the bug was in pure data-structure manipulation, and
 * a test that exercises only that surface keeps regressions from
 * sneaking back in via unrelated network-layer changes.
 */
class KtorRelayTransportFifoTest {

    private fun newTransport(): KtorRelayTransport = KtorRelayTransport(
        httpClientFactory = {
            error("test must not invoke httpClientFactory — it exercises in-memory outbox only")
        },
    )

    private fun fakeSendMessage(id: String, recipient: String = "deadbeef"): RelayMessage.Send =
        RelayMessage.Send(
            to = recipient,
            sealedSender = "",
            payload = "payload-of-$id",
            messageId = id,
        )

    /**
     * Reconnect scenario from Test #33: an envelope was sent live and is
     * sitting in pendingAcks (it never received an Ack — socket was dead),
     * then a NEWER envelope was queued in pendingOutbox while the
     * connection was down. The pre-H2a logic prepended pendingAcks values
     * to outbox.front, but only after the NEW outbox entry had been
     * appended — yielding wire order [new, old] which broke the ratchet
     * chain on the receiver. The H2a fix sorts by sequenceTs ASC, so the
     * older envelope comes first regardless of which queue it ended up in.
     */
    @Test
    fun mergeUnackedIntoOutboxOrdered_sortsByEncryptOrder_notQueueOrder() = runTest {
        val transport = newTransport()
        val now = TimeSource.Monotonic.markNow()

        // Encrypt order: A (seq=1) first, B (seq=2) second.
        val seqA = transport.nextSequenceTsForTest()
        val seqB = transport.nextSequenceTsForTest()
        assertTrue(seqA < seqB, "sequence counter must be monotonic")

        val msgA = fakeSendMessage("A-old-unacked")
        val msgB = fakeSendMessage("B-new-queued")

        // Simulate the reconnect-time state:
        //  • A was sent live but never acked -> sits in pendingAcks
        //  • B was queued while down -> sits in pendingOutbox
        // This is exactly the Test #33 layout: A is the read-receipt
        // encrypted at chain pos N, B is the user message at chain pos N+1.
        transport.seedPendingAckForTest(
            KtorRelayTransport.AckPending(
                message = msgA,
                sentAt = now,
                sequenceTs = seqA,
                queuedAtMs = 1000L,
            ),
        )
        transport.seedOutboxForTest(
            KtorRelayTransport.OutboxEntry(
                message = msgB,
                sequenceTs = seqB,
                queuedAtMs = 2000L,
            ),
        )

        // Run the merge path that the reconnect loop uses.
        transport.mergeUnackedIntoOutboxOrdered(mySession = 0L)

        val outbox = transport.snapshotOutboxForTest()
        assertEquals(2, outbox.size, "both envelopes must be in outbox after merge")
        assertEquals("A-old-unacked", (outbox[0].message as RelayMessage.Send).messageId,
            "older sequenceTs must flush first — this is the Test #33 fix")
        assertEquals("B-new-queued", (outbox[1].message as RelayMessage.Send).messageId,
            "newer sequenceTs must follow")
        assertEquals(seqA, outbox[0].sequenceTs)
        assertEquals(seqB, outbox[1].sequenceTs)

        // pendingAcks must be drained — those envelopes are now in outbox
        // and a duplicate would result in the relay getting the same
        // envelope twice on flush.
        val acks = transport.snapshotPendingAcksForTest()
        assertTrue(acks.isEmpty(), "pendingAcks must be drained after merge")
    }

    /**
     * Variant: 5 envelopes interleaved across pendingAcks (oldest 3) and
     * pendingOutbox (newest 2). Merge must produce [1, 2, 3, 4, 5].
     */
    @Test
    fun mergeUnackedIntoOutboxOrdered_handlesInterleavedSources() = runTest {
        val transport = newTransport()
        val now = TimeSource.Monotonic.markNow()

        val seqs = (1..5).map { transport.nextSequenceTsForTest() }
        val msgs = (1..5).map { fakeSendMessage("msg-$it") }

        // Oldest 3 are unacked from a previous live send.
        for (i in 0..2) {
            transport.seedPendingAckForTest(
                KtorRelayTransport.AckPending(msgs[i], now, seqs[i], 1000L + i),
            )
        }
        // Newest 2 were queued while the WS was down.
        for (i in 3..4) {
            transport.seedOutboxForTest(
                KtorRelayTransport.OutboxEntry(msgs[i], seqs[i], 1000L + i),
            )
        }

        transport.mergeUnackedIntoOutboxOrdered(mySession = 0L)

        val outbox = transport.snapshotOutboxForTest()
        assertEquals(5, outbox.size)
        assertEquals(
            listOf("msg-1", "msg-2", "msg-3", "msg-4", "msg-5"),
            outbox.map { (it.message as RelayMessage.Send).messageId },
            "merge must produce strict encrypt order regardless of source queue",
        )
    }

    /**
     * If pendingAcks is empty (no envelopes were in flight), merge is a
     * no-op and outbox order is preserved. Defends against accidentally
     * shuffling an already-ordered outbox.
     */
    @Test
    fun mergeUnackedIntoOutboxOrdered_isNoOp_whenPendingAcksEmpty() = runTest {
        val transport = newTransport()

        val seq1 = transport.nextSequenceTsForTest()
        val seq2 = transport.nextSequenceTsForTest()
        transport.seedOutboxForTest(
            KtorRelayTransport.OutboxEntry(fakeSendMessage("first"), seq1, 1000L),
        )
        transport.seedOutboxForTest(
            KtorRelayTransport.OutboxEntry(fakeSendMessage("second"), seq2, 2000L),
        )

        transport.mergeUnackedIntoOutboxOrdered(mySession = 0L)

        val outbox = transport.snapshotOutboxForTest()
        assertEquals(2, outbox.size)
        assertEquals("first", (outbox[0].message as RelayMessage.Send).messageId)
        assertEquals("second", (outbox[1].message as RelayMessage.Send).messageId)
    }

    /**
     * PR-H2a.1: live send() must defer to the outbox when anything older
     * is still queued, otherwise a fresh send (higher sequenceTs) would
     * race past the queued envelopes onto the wire while a flush is
     * about to drain them — exactly the race Vladislav flagged in
     * H2a review and the reason H2a alone wasn't enough.
     *
     * Scenario: WS is Connected, outbox has one queued envelope (e.g.
     * left over from a previous flush failure or a brief reconnect
     * window). A new live send() arrives. Pre-H2a.1 it would have
     * sendRaw'd straight to the wire, overtaking the queued one. Post-
     * H2a.1 it must queue itself behind the older entry and return false.
     */
    @Test
    fun liveSend_defersToOutbox_whenOlderEntryStillQueued() = runTest {
        val transport = newTransport()
        transport.setStateConnectedForTest()

        // Older envelope is sitting in the outbox (e.g. previous flush
        // failed mid-way and re-merged remainder; or queued during a
        // brief disconnect that cleared before we drained).
        val olderSeq = transport.nextSequenceTsForTest()
        transport.seedOutboxForTest(
            KtorRelayTransport.OutboxEntry(fakeSendMessage("older-queued"), olderSeq, 1000L),
        )

        // Fresh live send arrives. Must defer.
        val ok = transport.send(fakeSendMessage("new-live"))

        assertEquals(false, ok,
            "live send must return false (queued, not sent live) when outbox still holds older entries")

        val outbox = transport.snapshotOutboxForTest()
        assertEquals(2, outbox.size, "both entries must end up in outbox")
        assertEquals("older-queued", (outbox[0].message as RelayMessage.Send).messageId,
            "older entry stays first — sort by sequenceTs preserved")
        assertEquals("new-live", (outbox[1].message as RelayMessage.Send).messageId,
            "newer entry sorts after, awaiting the same flush")

        // pendingAcks must be empty — live send did NOT pre-track because
        // it deferred to outbox. The flush will track it when it drains.
        val acks = transport.snapshotPendingAcksForTest()
        assertTrue(acks.isEmpty(),
            "pendingAcks must be empty: deferred live send is in outbox, not in flight")
    }

    /**
     * Adversarial: even if some path managed to insert entries into the
     * outbox in the WRONG order (e.g. a future Tor-route-switch that
     * fans envelopes back in via addLast irrespective of sequenceTs),
     * the merge must still output strict encrypt order.
     */
    @Test
    fun mergeUnackedIntoOutboxOrdered_correctsOutOfOrderOutboxInsertions() = runTest {
        val transport = newTransport()
        val now = TimeSource.Monotonic.markNow()

        val seq1 = transport.nextSequenceTsForTest()
        val seq2 = transport.nextSequenceTsForTest()
        val seq3 = transport.nextSequenceTsForTest()

        // Adversarial insertion order: 3, 1, 2 — completely mixed.
        transport.seedOutboxForTest(
            KtorRelayTransport.OutboxEntry(fakeSendMessage("third"), seq3, 3000L),
        )
        transport.seedPendingAckForTest(
            KtorRelayTransport.AckPending(fakeSendMessage("first"), now, seq1, 1000L),
        )
        transport.seedOutboxForTest(
            KtorRelayTransport.OutboxEntry(fakeSendMessage("second"), seq2, 2000L),
        )

        transport.mergeUnackedIntoOutboxOrdered(mySession = 0L)

        val outbox = transport.snapshotOutboxForTest()
        assertEquals(
            listOf("first", "second", "third"),
            outbox.map { (it.message as RelayMessage.Send).messageId },
            "merge must repair order regardless of insertion sequence",
        )
    }
}
