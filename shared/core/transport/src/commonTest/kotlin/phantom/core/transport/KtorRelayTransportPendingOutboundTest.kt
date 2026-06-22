// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
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

    private val livingTransports = mutableListOf<KtorRelayTransport>()

    private fun newTransport(): KtorRelayTransport = KtorRelayTransport(
        httpClientFactory = {
            error("test must not invoke httpClientFactory — pure in-memory exercise")
        },
    ).also { livingTransports.add(it) }

    @AfterTest
    fun closeAllTransports() = runBlocking {
        // RC-RECONNECT-QUIESCENCE1 commit 2e fix-round-4 P2 (2026-06-23).
        // See `KtorRelayTransportDisconnectAndJoinTest.closeAllTransports`
        // for the same teardown rationale: `closeForTest(): Boolean`
        // must fail loudly when `cleanupInflight` does not drain.
        val failures = mutableListOf<String>()
        livingTransports.forEachIndexed { idx, t ->
            val outcome = runCatching {
                withTimeoutOrNull(6_000L) {
                    t.closeForTest(awaitInflightTimeoutMs = 5_000L)
                }
            }
            when {
                outcome.isFailure ->
                    failures.add(
                        "transport[$idx] closeForTest threw " +
                            outcome.exceptionOrNull(),
                    )
                outcome.getOrNull() == null ->
                    failures.add(
                        "transport[$idx] closeForTest did not return within " +
                            "6 s outer timeout; cleanupInflight=" +
                            "${t.cleanupInflightForTest()}",
                    )
                outcome.getOrNull() == false ->
                    failures.add(
                        "transport[$idx] closeForTest reported stuck " +
                            "cleanupInflight after the 5 s drain window; " +
                            "cleanupInflight=${t.cleanupInflightForTest()}",
                    )
            }
        }
        livingTransports.clear()
        if (failures.isNotEmpty()) {
            error(
                "@AfterTest teardown failed (${failures.size} failure(s)):\n" +
                    failures.joinToString("\n") { "  - $it" },
            )
        }
    }

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

    // ── RC-RECONNECT-QUIESCENCE1 (2026-06-21) — Test 20 FOUNDATION ────────────
    //
    // Review 2026-06-21: the two tests below are the FOUNDATION
    // for Test 20 — they prove the primitives (snapshot reads, mark-accepted
    // clears) the production migration chain relies on. They do NOT yet drive
    // the full production chain:
    //
    //     Mode 2 / quiescence
    //       → state machine RestActive transition
    //       → HybridRelayTransport.maybeArmMigrationLocked
    //       → migratePendingWsToRest (under restOutboundOrderMutex)
    //       → REST send via orchestrator
    //       → markPendingOutboundAcceptedByFallback
    //       → no loss, no duplicate.
    //
    // The full behavioral Test 20 lands AFTER the gate integration commit (so
    // the gate's interaction with the migration coroutine is exercised end-to-
    // end on a single test fixture). For now these foundation tests are the
    // CI anchor that ensures the primitives stay correct while the gate work
    // proceeds in subsequent commits.
    //
    // Scope-lock R3 requirement: an envelope in `pendingAcks` at the moment
    // the gate transitions to `Quiesced` (after Mode 2 fast-path actuation)
    // MUST either (a) migrate to the REST send path, or (b) remain visible to
    // the migration coroutine in a stable in-memory store. Loss is forbidden.
    //
    // The PR-D1c migration in `HybridRelayTransport.maybeArmMigrationLocked`
    // fires on every non-RestActive → RestActive transition, including the
    // one produced by `mode_2_fast_path`. It calls `snapshotPendingOutbound()`
    // and re-sends every entry via REST under `restOutboundOrderMutex`, then
    // calls `markPendingOutboundAcceptedByFallback` to clear the WS-side maps.
    //
    // The reconnect-quiescence gate (R2/R3) suspends the reconnect-loop after
    // sticky armed, which means `mergeUnackedIntoOutboxOrdered` (run at the
    // START of the NEXT WS session in `runReconnectLoop`) does NOT execute
    // before the migration coroutine reads the pending stores. These tests pin
    // the foundation: the migration's read window observes the same
    // `pendingAcks` content that was alive at session death — no clearing,
    // no loss.

    /**
     * Test 20 (outbox/ACK invariant on quiescence entry). Envelopes sitting in
     * `pendingAcks` at session-end remain visible to `snapshotPendingOutbound()`
     * without the next session's `mergeUnackedIntoOutboxOrdered` having run.
     *
     * Scenario: two envelopes E1, E2 were sent on the WS and are awaiting ACK.
     * The WS session dies (Mode 2 silent drop). The reconnect-quiescence gate
     * will hold the reconnect-loop in `Quiesced` so no new session starts and
     * `mergeUnackedIntoOutboxOrdered` is NOT invoked. The migration coroutine
     * in `HybridRelayTransport`, triggered by the state-machine transition
     * to `RestActive`, calls `snapshotPendingOutbound()` to drive the REST
     * re-send. This test pins that the snapshot returns BOTH envelopes,
     * in sequenceTs ASC order, so the migration sees the full set.
     *
     * Failure of this test would mean reconnect-quiescence cannot ship without
     * a precursor PR that reroutes the orphaned `pendingAcks` envelopes into
     * the migration's read window.
     */
    @Test
    fun outbox_ack_invariant_pendingAcks_visible_to_migration_snapshot_under_quiescence() = runTest {
        val transport = newTransport()
        val now = TimeSource.Monotonic.markNow()
        val seqE1 = transport.nextSequenceTsForTest()
        val seqE2 = transport.nextSequenceTsForTest()
        // Two envelopes on the wire awaiting ACK at the moment of session death.
        transport.seedPendingAckForTest(
            KtorRelayTransport.AckPending(
                message = fakeSendMessage("E1"), sentAt = now, sequenceTs = seqE1, queuedAtMs = 100L,
            ),
        )
        transport.seedPendingAckForTest(
            KtorRelayTransport.AckPending(
                message = fakeSendMessage("E2"), sentAt = now, sequenceTs = seqE2, queuedAtMs = 200L,
            ),
        )
        // Reconnect-quiescence holds the reconnect-loop in Quiesced; no NEXT
        // session starts; `mergeUnackedIntoOutboxOrdered` is never invoked in
        // this window. Migration in HybridRelayTransport, however, fires on
        // the WsActive → RestActive transition and reads via this snapshot.
        val snapshot = transport.snapshotPendingOutbound()
        assertEquals(2, snapshot.size,
            "migration must see BOTH envelopes in pendingAcks at quiescence entry — loss is forbidden")
        assertEquals("E1", snapshot[0].id,
            "snapshot must preserve sequenceTs ASC order so bootstrap (E1) lands before follow-up (E2) via REST")
        assertEquals("E2", snapshot[1].id)
        assertEquals(seqE1, snapshot[0].sequenceTs)
        assertEquals(seqE2, snapshot[1].sequenceTs)
    }

    /**
     * Test 20 continuation. After the migration coroutine successfully delivers
     * each envelope via REST, it calls `markPendingOutboundAcceptedByFallback`
     * to clear the WS-side maps. This test pins that the clear is honoured so
     * a subsequent WS reconnect does NOT re-flush a duplicate.
     */
    /**
     * Review amendment P1 (2026-06-21): disconnectAndJoin no longer calls
     * flushPendingOutbox in its strict bound. Previously `session = null`
     * was set BEFORE flushPendingOutbox, so every `sendRaw()` returned
     * `true` silently without actually sending. This test pins both halves
     * of the fix:
     *   - sendRaw refuses with `false` when `session == null` (loud
     *     failure rather than silent false success);
     *   - the pending stores remain intact (no false drain) so D1c REST
     *     migration in HybridRelayTransport can pick them up on the
     *     subsequent WsActive → RestActive transition.
     */
    @Test
    fun outbox_ack_invariant_disconnectAndJoin_does_not_silently_drain_pending_stores() = runTest {
        val transport = newTransport()
        val now = TimeSource.Monotonic.markNow()
        val seqAck = transport.nextSequenceTsForTest()
        val seqSend = transport.nextSequenceTsForTest()
        // Pre-seed both stores: an AckDelivery queued for the next live WS
        // session and a Send awaiting ACK.
        transport.seedOutboxForTest(
            KtorRelayTransport.OutboxEntry(
                message = RelayMessage.AckDelivery(messageId = "inbound-id-1"),
                sequenceTs = seqAck,
                queuedAtMs = 100L,
            ),
        )
        transport.seedPendingAckForTest(
            KtorRelayTransport.AckPending(
                message = fakeSendMessage("out-id-1"),
                sentAt = now,
                sequenceTs = seqSend,
                queuedAtMs = 200L,
            ),
        )

        // Drive the quiescence-entry path: disconnectAndJoin with no live
        // reconnect job and no live session is a clean teardown. It MUST
        // NOT attempt to flush via the dead WS (which would have produced
        // a silent false success under the old code path).
        val result = transport.disconnectAndJoin(timeoutMs = 1_000)
        assertTrue(result, "clean teardown returns true")

        // Both stores must still hold their entries — D1c migration in
        // HybridRelayTransport will pick them up on the next non-RestActive
        // → RestActive transition.
        val snapshot = transport.snapshotPendingOutbound()
        assertEquals(
            1, snapshot.size,
            "Send entry MUST remain in pending stores after disconnectAndJoin — D1c migration owns the re-send",
        )
        assertEquals(
            "out-id-1", snapshot[0].id,
            "the surviving entry is the original Send (AckDelivery is filtered from snapshotPendingOutbound by design)",
        )
        // Snapshot does NOT include AckDelivery (snapshot_ignores_non_send_outbox_entries
        // test pins this). To verify AckDelivery survived, inspect the outbox
        // directly via the test seam.
        val outboxAfter = transport.snapshotOutboxForTest()
        assertTrue(
            outboxAfter.any { it.message is RelayMessage.AckDelivery && (it.message as RelayMessage.AckDelivery).messageId == "inbound-id-1" },
            "AckDelivery MUST remain in outbox after disconnectAndJoin — it cannot be silently drained " +
                "via a sendRaw to a null session. Outbox after: $outboxAfter",
        )
    }

    @Test
    fun outbox_ack_invariant_mark_accepted_clears_pendingAcks_no_duplicate_on_reconnect() = runTest {
        val transport = newTransport()
        val now = TimeSource.Monotonic.markNow()
        val seqE1 = transport.nextSequenceTsForTest()
        transport.seedPendingAckForTest(
            KtorRelayTransport.AckPending(
                message = fakeSendMessage("E1"), sentAt = now, sequenceTs = seqE1, queuedAtMs = 100L,
            ),
        )
        // Migration successfully delivered E1 via REST; mark it accepted.
        transport.markPendingOutboundAcceptedByFallback("E1")
        // pendingAcks must now be empty so a subsequent WS reconnect's
        // `mergeUnackedIntoOutboxOrdered` does NOT re-queue E1 for a duplicate
        // WS send.
        val snapshot = transport.snapshotPendingOutbound()
        assertTrue(snapshot.isEmpty(),
            "after markPendingOutboundAcceptedByFallback the envelope must be gone from BOTH WS-side maps; " +
                "got snapshot=$snapshot")
    }
}
