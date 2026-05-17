// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * PR-D1c (2026-05-16) unit tests for the snapshot/mark API consumed by
 * `HybridRelayTransport` to migrate pending WS outbound envelopes to REST
 * on a WS → REST mode switch.
 *
 * Test #49 root cause: an X3DH bootstrap envelope sat in `pendingAcks`
 * after a Tele2 LTE middlebox dropped the relay's Ack frame. The state
 * machine then routed the next message via REST without first migrating
 * the bootstrap, so the receiver got a session-existing ciphertext with no
 * session and dropped it. These tests pin the contract that the wrapper
 * relies on:
 *
 *  - [KtorRelayTransport.snapshotPendingOutbound] unions `pendingAcks`
 *    and `pendingOutbox`, deduplicates by id, and sorts by `sequenceTs`
 *    ASC so the wrapper re-sends in encrypt-time order.
 *  - [KtorRelayTransport.markPendingOutboundAcceptedByFallback] removes
 *    the envelope from BOTH maps atomically so a WS reconnect cannot
 *    re-flush an envelope the recipient has already received via REST.
 */
class KtorRelayTransportPendingOutboundTest {

    private fun newTransport(): KtorRelayTransport = KtorRelayTransport(
        httpClientFactory = {
            error("test must not invoke httpClientFactory — pure in-memory exercise")
        },
    )

    private fun fakeSendMessage(id: String, recipient: String = "deadbeef"): RelayMessage.Send =
        RelayMessage.Send(
            to = recipient,
            sealedSender = "",
            payload = "payload-$id",
            messageId = id,
        )

    @Test
    fun snapshot_returns_empty_when_no_pending() = runTest {
        val transport = newTransport()
        val snapshot = transport.snapshotPendingOutbound()
        assertTrue(snapshot.isEmpty(), "empty transport must produce empty snapshot")
    }

    /**
     * Bootstrap scenario: one envelope sits in `pendingAcks` because WS
     * delivered the frame but no Ack ever returned (Tele2 case). Snapshot
     * must expose it so the migration can re-send via REST.
     */
    @Test
    fun snapshot_includes_pendingAcks_entries_in_sequenceTs_order() = runTest {
        val transport = newTransport()
        val now = TimeSource.Monotonic.markNow()
        val seqA = transport.nextSequenceTsForTest()
        val seqB = transport.nextSequenceTsForTest()

        transport.seedPendingAckForTest(
            KtorRelayTransport.AckPending(
                message = fakeSendMessage("B"), sentAt = now, sequenceTs = seqB, queuedAtMs = 200L,
            ),
        )
        transport.seedPendingAckForTest(
            KtorRelayTransport.AckPending(
                message = fakeSendMessage("A"), sentAt = now, sequenceTs = seqA, queuedAtMs = 100L,
            ),
        )

        val snapshot = transport.snapshotPendingOutbound()
        assertEquals(2, snapshot.size)
        assertEquals("A", snapshot[0].id, "older sequenceTs must come first")
        assertEquals("B", snapshot[1].id)
        assertEquals(seqA, snapshot[0].sequenceTs)
        assertEquals(seqB, snapshot[1].sequenceTs)
    }

    @Test
    fun snapshot_includes_pendingOutbox_entries() = runTest {
        val transport = newTransport()
        val seqA = transport.nextSequenceTsForTest()
        transport.seedOutboxForTest(
            KtorRelayTransport.OutboxEntry(
                message = fakeSendMessage("queued-A"), sequenceTs = seqA, queuedAtMs = 100L,
            ),
        )

        val snapshot = transport.snapshotPendingOutbound()
        assertEquals(1, snapshot.size)
        assertEquals("queued-A", snapshot[0].id)
        assertEquals(seqA, snapshot[0].sequenceTs)
    }

    /**
     * Union scenario: bootstrap envelope ("A", seq=1) sits in `pendingAcks`,
     * a follow-up ("B", seq=2) sits in `pendingOutbox` because the WS
     * session dropped after A was put on the wire. Migration must see both,
     * sorted by encrypt order, so the receiver sees A's bootstrap before
     * B's session-existing message.
     */
    @Test
    fun snapshot_unions_both_stores_and_sorts_by_sequenceTs() = runTest {
        val transport = newTransport()
        val now = TimeSource.Monotonic.markNow()
        val seqA = transport.nextSequenceTsForTest()
        val seqB = transport.nextSequenceTsForTest()

        transport.seedPendingAckForTest(
            KtorRelayTransport.AckPending(
                message = fakeSendMessage("A"), sentAt = now, sequenceTs = seqA, queuedAtMs = 100L,
            ),
        )
        transport.seedOutboxForTest(
            KtorRelayTransport.OutboxEntry(
                message = fakeSendMessage("B"), sequenceTs = seqB, queuedAtMs = 200L,
            ),
        )

        val snapshot = transport.snapshotPendingOutbound()
        assertEquals(2, snapshot.size)
        assertEquals("A", snapshot[0].id)
        assertEquals("B", snapshot[1].id)
    }

    /**
     * Defensive: if the same id is briefly present in both stores (a
     * transient state that flush/merge code paths can produce while moving
     * an entry across maps), the snapshot still includes it once, not
     * twice — otherwise the migration would send the same envelope to REST
     * twice and one of the duplicates would later log
     * `migrate_pending_conflict` for no real reason.
     */
    @Test
    fun snapshot_deduplicates_when_same_id_in_both_stores() = runTest {
        val transport = newTransport()
        val now = TimeSource.Monotonic.markNow()
        val seq = transport.nextSequenceTsForTest()
        val msg = fakeSendMessage("dup-id")
        transport.seedPendingAckForTest(
            KtorRelayTransport.AckPending(
                message = msg, sentAt = now, sequenceTs = seq, queuedAtMs = 100L,
            ),
        )
        transport.seedOutboxForTest(
            KtorRelayTransport.OutboxEntry(
                message = msg, sequenceTs = seq, queuedAtMs = 100L,
            ),
        )

        val snapshot = transport.snapshotPendingOutbound()
        assertEquals(1, snapshot.size, "same id in both stores must appear exactly once")
        assertEquals("dup-id", snapshot[0].id)
    }

    /**
     * Snapshot must carry the exact ciphertext blobs (payload + sealed
     * sender) so the migration can re-send over REST without re-encrypting.
     * Re-encrypting at this layer would advance the Double-Ratchet chain
     * and the receiver would fail MAC on the original ciphertext.
     */
    @Test
    fun snapshot_preserves_payload_and_sealedSender_blobs() = runTest {
        val transport = newTransport()
        val seq = transport.nextSequenceTsForTest()
        val msg = RelayMessage.Send(
            to = "recipient-hex",
            sealedSender = "sealed-blob-base64",
            payload = "ciphertext-base64",
            messageId = "id-1",
        )
        transport.seedOutboxForTest(
            KtorRelayTransport.OutboxEntry(message = msg, sequenceTs = seq, queuedAtMs = 100L),
        )

        val snapshot = transport.snapshotPendingOutbound()
        val entry = snapshot.single()
        assertEquals("id-1", entry.id)
        assertEquals("recipient-hex", entry.to)
        assertEquals("ciphertext-base64", entry.payloadBase64)
        assertEquals("sealed-blob-base64", entry.sealedSenderBase64)
        assertEquals(seq, entry.sequenceTs)
    }

    @Test
    fun mark_accepted_removes_from_pendingAcks() = runTest {
        val transport = newTransport()
        val now = TimeSource.Monotonic.markNow()
        val seq = transport.nextSequenceTsForTest()
        transport.seedPendingAckForTest(
            KtorRelayTransport.AckPending(
                message = fakeSendMessage("X"), sentAt = now, sequenceTs = seq, queuedAtMs = 100L,
            ),
        )

        transport.markPendingOutboundAcceptedByFallback("X")

        val acks = transport.snapshotPendingAcksForTest()
        assertTrue(acks.isEmpty(), "pendingAcks must be empty after mark")
    }

    @Test
    fun mark_accepted_removes_from_pendingOutbox() = runTest {
        val transport = newTransport()
        val seq = transport.nextSequenceTsForTest()
        transport.seedOutboxForTest(
            KtorRelayTransport.OutboxEntry(
                message = fakeSendMessage("Y"), sequenceTs = seq, queuedAtMs = 100L,
            ),
        )

        transport.markPendingOutboundAcceptedByFallback("Y")

        val outbox = transport.snapshotOutboxForTest()
        assertTrue(outbox.isEmpty(), "pendingOutbox must be empty after mark")
    }

    /**
     * Atomicity check: when an id sits in both stores, a single mark call
     * must clear both. This is the case the wrapper relies on when WS
     * flush moved an envelope from `pendingAcks` to `pendingOutbox`
     * mid-migration — without atomic removal the next WS reconnect would
     * re-flush the envelope and the relay would see (and live-deliver) a
     * duplicate of one that the recipient already processed via REST.
     */
    @Test
    fun mark_accepted_removes_from_both_stores_atomically() = runTest {
        val transport = newTransport()
        val now = TimeSource.Monotonic.markNow()
        val seq = transport.nextSequenceTsForTest()
        val msg = fakeSendMessage("Z")
        transport.seedPendingAckForTest(
            KtorRelayTransport.AckPending(
                message = msg, sentAt = now, sequenceTs = seq, queuedAtMs = 100L,
            ),
        )
        transport.seedOutboxForTest(
            KtorRelayTransport.OutboxEntry(message = msg, sequenceTs = seq, queuedAtMs = 100L),
        )

        transport.markPendingOutboundAcceptedByFallback("Z")

        assertTrue(transport.snapshotPendingAcksForTest().isEmpty(),
            "pendingAcks must be empty after mark")
        assertTrue(transport.snapshotOutboxForTest().isEmpty(),
            "pendingOutbox must be empty after mark")
    }

    @Test
    fun mark_accepted_unknown_id_is_noop() = runTest {
        val transport = newTransport()
        val now = TimeSource.Monotonic.markNow()
        val seq = transport.nextSequenceTsForTest()
        transport.seedPendingAckForTest(
            KtorRelayTransport.AckPending(
                message = fakeSendMessage("real"), sentAt = now, sequenceTs = seq, queuedAtMs = 100L,
            ),
        )

        // Should not throw; should not affect the existing entry.
        transport.markPendingOutboundAcceptedByFallback("does-not-exist")

        val remaining = transport.snapshotPendingAcksForTest()
        assertEquals(1, remaining.size)
        assertEquals("real", (remaining.single().message as RelayMessage.Send).messageId)
    }

    /**
     * Defensive: outbox can in principle hold non-Send entries (e.g.
     * AckDelivery messages that were queued for the next reconnect).
     * Those must be IGNORED by the snapshot — only outbound user/payload
     * envelopes are eligible for REST migration. We probe this by seeding
     * an AckDelivery into the outbox alongside a Send and verifying the
     * snapshot returns only the Send.
     */
    // ── PR-D1d: per-envelope ACK deadline timer tests ─────────────────────────

    /**
     * Arms a deadline for an envelope and advances virtual time past
     * ACK_DEADLINE_MS (10 s) without delivering an ACK.
     * [KtorRelayTransport.outboundAckDeadlineExpired] must emit exactly one
     * event with the correct msgId, and pendingAcks must still hold the entry
     * (the timer must NOT remove it — removal is owned by the ACK/session-end
     * paths, not the timer itself).
     *
     * Pattern: subscribe first (replay=0 → must subscribe before emission),
     * then arm, then advance virtual time, then cancel the collector and
     * inspect the captured list.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun outbound_ack_deadline_arms_and_expires_when_no_ack() = runTest {
        val transport = newTransport()
        // Redirect launched delay() Jobs onto the TestScope's virtual-time
        // scheduler so advanceTimeBy() controls them.
        transport.ackDeadlineScopeOverride = this

        val msgId = "expire-test-id"
        val sentMark = TimeSource.Monotonic.markNow()
        val seq = transport.nextSequenceTsForTest()
        val entry = KtorRelayTransport.AckPending(
            message = fakeSendMessage(msgId),
            sentAt = sentMark,
            sequenceTs = seq,
            queuedAtMs = 0L,
        )

        // Subscribe BEFORE arming (replay=0).
        val emitted = mutableListOf<OutboundAckDeadlineExpiredEvent>()
        val collectJob: Job = launch {
            transport.outboundAckDeadlineExpired.collect { emitted += it }
        }

        // Arm the deadline (seeds pendingAcks + launches timer on TestScope).
        transport.armAckDeadlineForTest(entry)

        // Advance virtual time past the deadline.
        advanceTimeBy(RelayTransportConfig.ACK_DEADLINE_MS + 1L)

        // Cancel the collection coroutine — we have seen all events emitted
        // up to this virtual-time point.
        collectJob.cancel()

        assertEquals(1, emitted.size, "Exactly one deadline event must be emitted")
        assertEquals(msgId, emitted.single().msgId)
        // ageMs is measured against TimeSource.Monotonic (real clock), so in a
        // virtual-time test the elapsed real duration is near-zero. We cannot
        // assert ageMs >= ACK_DEADLINE_MS here — just verify the field is
        // non-negative (the event was emitted with a coherent timestamp).
        assertTrue(emitted.single().ageMs >= 0L,
            "ageMs must be non-negative; got ${emitted.single().ageMs}")

        // The timer must NOT have removed the entry from pendingAcks.
        val remaining = transport.snapshotPendingAcksForTest()
        assertEquals(1, remaining.size, "pendingAcks must retain the entry after deadline (removal belongs to ACK/session-end path)")
        assertEquals(msgId, (remaining.single().message as RelayMessage.Send).messageId)
    }

    /**
     * Arms a deadline for an envelope, simulates an ACK arriving at 5 s
     * (before the 10 s deadline), then advances virtual time past the
     * deadline. No event must be emitted on
     * [KtorRelayTransport.outboundAckDeadlineExpired] because the ACK
     * cancels the timer Job before it fires.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun outbound_ack_deadline_cancelled_by_ack_received() = runTest {
        val transport = newTransport()
        transport.ackDeadlineScopeOverride = this

        val msgId = "cancel-test-id"
        val sentMark = TimeSource.Monotonic.markNow()
        val seq = transport.nextSequenceTsForTest()
        val entry = KtorRelayTransport.AckPending(
            message = fakeSendMessage(msgId),
            sentAt = sentMark,
            sequenceTs = seq,
            queuedAtMs = 0L,
        )

        // Subscribe before arming.
        val emitted = mutableListOf<OutboundAckDeadlineExpiredEvent>()
        val collectJob: Job = launch {
            transport.outboundAckDeadlineExpired.collect { emitted += it }
        }

        // Arm the deadline timer.
        transport.armAckDeadlineForTest(entry)

        // ACK arrives at 5 s — before the 10 s deadline.
        advanceTimeBy(5_000L)
        transport.simulateAckReceivedForTest(msgId)

        // Advance well past the original 10 s mark. The cancelled Job
        // must not emit anything.
        advanceTimeBy(RelayTransportConfig.ACK_DEADLINE_MS + 1_000L)

        collectJob.cancel()

        assertTrue(emitted.isEmpty(), "No deadline event must fire when ACK arrived before deadline; got: $emitted")

        // ACK must have removed the entry from pendingAcks.
        assertTrue(transport.snapshotPendingAcksForTest().isEmpty(),
            "pendingAcks must be empty after ACK received")
    }

    // ── end PR-D1d tests ──────────────────────────────────────────────────────

    @Test
    fun snapshot_ignores_non_send_outbox_entries() = runTest {
        val transport = newTransport()
        val seqAck = transport.nextSequenceTsForTest()
        val seqSend = transport.nextSequenceTsForTest()
        transport.seedOutboxForTest(
            KtorRelayTransport.OutboxEntry(
                message = RelayMessage.AckDelivery(messageId = "inbound-id"),
                sequenceTs = seqAck,
                queuedAtMs = 100L,
            ),
        )
        transport.seedOutboxForTest(
            KtorRelayTransport.OutboxEntry(
                message = fakeSendMessage("real-send"),
                sequenceTs = seqSend,
                queuedAtMs = 200L,
            ),
        )

        val snapshot = transport.snapshotPendingOutbound()
        assertEquals(1, snapshot.size)
        assertEquals("real-send", snapshot[0].id)
        assertNull(snapshot.firstOrNull { it.id == "inbound-id" },
            "AckDelivery entries in outbox must not appear in pending outbound snapshot")
    }
}
