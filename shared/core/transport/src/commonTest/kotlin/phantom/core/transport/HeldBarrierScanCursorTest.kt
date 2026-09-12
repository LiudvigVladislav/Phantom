// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Queue-progress fix (2026-09-12) -- the orchestrator's two positions,
 * at the unit level: the persisted ACK cursor (durable completion
 * boundary) and the process-local scan position (may pass a durably
 * held, unacknowledged envelope). The full-stack cases live in
 * `SessionOrderFullStackTest` on the Android side; these pin the
 * arithmetic without a poll loop.
 */
class HeldBarrierScanCursorTest {

    private val IDENTITY: String = "aa".repeat(32)

    private val SESSION_RESPONSE_OK: RestFallbackResponse<AuthSessionResponse> =
        RestFallbackResponse(
            statusCode = 200,
            bodyParsed = AuthSessionResponse(
                token = "test-token",
                expiresAt = Long.MAX_VALUE,
                restFallback = true,
                maxSendBodyBytes = 4096,
                pollMaxEnvelopes = 1,
                pollHoldSecs = 30,
                seqMacVerifyKey = "",
            ),
            rawBody = "{}",
            elapsedMs = 1L,
        )

    private class FakeTransport(
        private val session: RestFallbackResponse<AuthSessionResponse>,
    ) : RestFallbackTransport {
        val ackCalls: MutableList<String> = mutableListOf()
        override suspend fun authSession(url: String, body: AuthSessionRequest): RestFallbackResponse<AuthSessionResponse> = session
        override suspend fun send(url: String, token: String, idempotencyKey: String, body: SendRequest): RestFallbackResponse<SendResponse> =
            fail("send unexpected")
        override suspend fun poll(url: String, token: String, sinceSeq: Long?, longPollOptIn: Boolean, readTimeoutMs: Long?): RestFallbackResponse<PollResponse> =
            fail("poll unexpected: these cases drive the cursor directly")
        override suspend fun ackDeliver(url: String, token: String, body: AckDeliverRequest): RestFallbackResponse<AckDeliverResponse> {
            ackCalls += body.id
            return RestFallbackResponse(statusCode = 200, bodyParsed = AckDeliverResponse(ok = 1), rawBody = "{}", elapsedMs = 1L)
        }
    }

    /** Monotonic, like the SQLDelight repository; records every genuine forward write. */
    private class FakeCursorRepo(private var persisted: Long? = null) : LongPollCursorRepository {
        val writes: MutableList<Long> = mutableListOf()
        val persistedSeq: Long? get() = persisted
        override suspend fun getLastSeenSeq(identityHex: String): Long? = persisted
        override suspend fun upsertLastSeenSeq(identityHex: String, seq: Long, nowMs: Long): CursorUpsertOutcome {
            val current = persisted
            if (current != null && current >= seq) return CursorUpsertOutcome.NoChange(current)
            persisted = seq
            writes += seq
            return CursorUpsertOutcome.Advanced(seq)
        }
    }

    private class Rig(val transport: FakeTransport, val cursor: FakeCursorRepo, val log: MutableList<String>, val orch: RestFallbackOrchestrator)

    private suspend fun rig(
        initialCursor: Long? = null,
        /** The durable held registry seam; null = trusted scan (legacy fixture shape). */
        heldExists: (suspend (String) -> Boolean)? = null,
    ): Rig {
        val transport = FakeTransport(SESSION_RESPONSE_OK)
        val cursor = FakeCursorRepo(initialCursor)
        val log = mutableListOf<String>()
        val orch = RestFallbackOrchestrator(
            egressGate = RestEgressGate(RestEgressPolicy { RestEgressDecision.DirectAllowed }),
            baseUrl = "https://relay.test",
            identityHex = IDENTITY,
            signingPubkeyHex = "bb".repeat(32),
            getChallenge = { _ -> "cc".repeat(32) },
            signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
            transport = transport,
            now = { 0L },
            log = { log += it },
            longPollEnabled = false,
            cursorRepository = cursor,
            heldEnvelopeExists = heldExists,
            dispatcher = UnconfinedTestDispatcher(),
        )
        check(orch.bootstrap().restFallback)
        return Rig(transport, cursor, log, orch)
    }

    @Test
    fun a_deferral_for_an_envelope_the_orchestrator_never_emitted_changes_nothing() = runTest {
        val r = rig(initialCursor = 3L)
        assertEquals(HeldDeferralOutcome.NotRestOrigin, r.orch.deferInboundHeld("ghost"))
        assertNull(r.orch.peekScanSinceSeqForTest())
        assertEquals(3L, r.orch.effectiveSinceSeqForTest(3L))
        assertNull(r.orch.effectiveSinceSeqForTest(null))
        assertTrue(r.transport.ackCalls.isEmpty())
        assertTrue(r.cursor.writes.isEmpty())
    }

    @Test
    fun a_deferral_raises_only_the_scan_position_and_keeps_the_pending_seq() = runTest {
        val r = rig(initialCursor = 3L)
        r.orch.primePendingSeqForAckForTest("env-5", 5L)
        assertEquals(HeldDeferralOutcome.ScanAdvanced(seq = 5L, scanSinceSeq = 5L), r.orch.deferInboundHeld("env-5"))
        assertEquals(5L, r.orch.peekScanSinceSeqForTest())
        assertEquals(5L, r.orch.peekPendingSeqForAckForTest("env-5"), "the seq is kept for the eventual ACK")
        assertEquals(5L, r.orch.effectiveSinceSeqForTest(3L), "a poll asks past the held barrier")
        assertEquals(9L, r.orch.effectiveSinceSeqForTest(9L), "but never below the persisted cursor")
        assertEquals(5L, r.orch.effectiveSinceSeqForTest(null))
        assertEquals(3L, r.cursor.persistedSeq, "the persisted cursor is untouched")
        assertTrue(r.cursor.writes.isEmpty())
        assertTrue(r.transport.ackCalls.isEmpty(), "a deferral never talks to the relay")
        assertTrue(r.log.any { it.startsWith("REST_TRACE inbound_held_deferred id=env-5 seq=5 scan_since_seq=5") })
    }

    @Test
    fun a_deferral_is_idempotent() = runTest {
        val r = rig()
        r.orch.primePendingSeqForAckForTest("env-5", 5L)
        r.orch.deferInboundHeld("env-5")
        assertEquals(HeldDeferralOutcome.ScanAdvanced(5L, 5L), r.orch.deferInboundHeld("env-5"))
        assertEquals(5L, r.orch.peekScanSinceSeqForTest())
    }

    @Test
    fun a_later_ack_persists_the_cursor_only_up_to_the_earliest_unresolved_barrier() = runTest {
        val r = rig(initialCursor = 3L)
        r.orch.primePendingSeqForAckForTest("env-5", 5L)
        r.orch.deferInboundHeld("env-5")
        r.orch.primePendingSeqForAckForTest("env-7", 7L)
        assertEquals(AckOutcome.Acked, r.orch.ackInboundAndAdvanceCursor("env-7"))
        assertEquals(listOf("env-7"), r.transport.ackCalls)
        assertEquals(listOf(4L), r.cursor.writes, "persisted just below the barrier, not at the acked seq")
        assertNull(r.orch.peekPendingSeqForAckForTest("env-7"))
        assertEquals(5L, r.orch.peekPendingSeqForAckForTest("env-5"), "the barrier is still tracked")
        assertEquals(5L, r.orch.peekScanSinceSeqForTest())
        assertTrue(r.log.any { it.startsWith("REST_TRACE cursor_bounded id=env-7 acked_seq=7 persist_seq=4") })
    }

    @Test
    fun acking_the_barrier_itself_releases_the_bound_and_catches_up_to_the_highest_acked_seq() = runTest {
        val r = rig(initialCursor = 3L)
        r.orch.primePendingSeqForAckForTest("env-5", 5L)
        r.orch.deferInboundHeld("env-5")
        r.orch.primePendingSeqForAckForTest("env-7", 7L)
        r.orch.ackInboundAndAdvanceCursor("env-7")
        assertEquals(AckOutcome.Acked, r.orch.ackInboundAndAdvanceCursor("env-5"))
        assertEquals(listOf(4L, 7L), r.cursor.writes, "after the barrier resolves the cursor covers every acked seq")
        assertNull(r.orch.peekScanSinceSeqForTest())
        assertNull(r.orch.peekPendingSeqForAckForTest("env-5"))
        assertEquals(7L, r.orch.effectiveSinceSeqForTest(7L))
    }

    @Test
    fun a_barrier_at_the_front_of_the_queue_skips_the_cursor_write_instead_of_regressing() = runTest {
        val r = rig()
        r.orch.primePendingSeqForAckForTest("env-1", 1L)
        r.orch.deferInboundHeld("env-1")
        r.orch.primePendingSeqForAckForTest("env-2", 2L)
        assertEquals(AckOutcome.Acked, r.orch.ackInboundAndAdvanceCursor("env-2"))
        assertTrue(r.cursor.writes.isEmpty(), "nothing below seq 1 is worth persisting")
        assertNull(r.cursor.persistedSeq)
        assertTrue(r.log.any { it.startsWith("REST_TRACE cursor_write_skipped_barrier id=env-2 acked_seq=2") })
        assertEquals(AckOutcome.Acked, r.orch.ackInboundAndAdvanceCursor("env-1"))
        assertEquals(listOf(2L), r.cursor.writes)
    }

    @Test
    fun a_released_claim_without_a_durable_copy_bounds_the_cursor_but_moves_no_scan_position() = runTest {
        val r = rig(initialCursor = 1L)
        // Emitted, processing claim released, hold write FAILED: still tracked, never deferred.
        r.orch.primePendingSeqForAckForTest("env-3", 3L)
        r.orch.primePendingSeqForAckForTest("env-4", 4L)
        assertNull(r.orch.peekScanSinceSeqForTest())
        assertEquals(1L, r.orch.effectiveSinceSeqForTest(1L), "the relay must offer seq 3 again")
        assertEquals(AckOutcome.Acked, r.orch.ackInboundAndAdvanceCursor("env-4"))
        assertEquals(listOf(2L), r.cursor.writes, "the cursor stops before the unresolved seq 3")
        assertEquals(3L, r.orch.peekPendingSeqForAckForTest("env-3"))
    }

    @Test
    fun a_monotonic_repository_never_regresses_when_the_bound_is_below_the_persisted_cursor() = runTest {
        val r = rig(initialCursor = 6L)
        r.orch.primePendingSeqForAckForTest("env-5", 5L)
        r.orch.deferInboundHeld("env-5")
        r.orch.primePendingSeqForAckForTest("env-8", 8L)
        assertEquals(AckOutcome.Acked, r.orch.ackInboundAndAdvanceCursor("env-8"))
        assertTrue(r.cursor.writes.isEmpty())
        assertEquals(6L, r.cursor.persistedSeq)
        assertTrue(r.log.any { it.startsWith("REST_TRACE cursor_noop existing_seq=6 rejected_seq=4 acked_seq=8") })
    }

    // ── Round 2: the scan entry is only as good as the durable row ──────

    @Test
    fun a_scan_entry_whose_held_row_is_gone_is_revoked_before_the_poll() = runTest {
        val rows = mutableSetOf("env-5")
        val r = rig(initialCursor = 3L, heldExists = { it in rows })
        r.orch.primePendingSeqForAckForTest("env-5", 5L)
        r.orch.deferInboundHeld("env-5")
        assertEquals(5L, r.orch.effectiveSinceSeqForTest(3L), "row present: the poll asks past the barrier")
        rows.remove("env-5") // the TTL sweep, or any other removal without commit + ACK
        assertEquals(3L, r.orch.effectiveSinceSeqForTest(3L), "row gone: back to the durable cursor")
        assertNull(r.orch.peekScanSinceSeqForTest())
        assertEquals(5L, r.orch.peekPendingSeqForAckForTest("env-5"), "still unresolved: keeps bounding the cursor")
        assertTrue(r.transport.ackCalls.isEmpty()); assertTrue(r.cursor.writes.isEmpty())
        assertTrue(r.log.any { it.startsWith("REST_TRACE scan_revoked id=env-5 seq=5 reason=held_row_gone") })
    }

    @Test
    fun a_failing_registry_check_revokes_rather_than_trusts() = runTest {
        val r = rig(initialCursor = 3L, heldExists = { error("storage unavailable") })
        r.orch.primePendingSeqForAckForTest("env-5", 5L)
        r.orch.deferInboundHeld("env-5")
        assertEquals(3L, r.orch.effectiveSinceSeqForTest(3L))
        assertNull(r.orch.peekScanSinceSeqForTest())
        assertTrue(r.log.any { it.startsWith("REST_TRACE scan_revoked id=env-5 seq=5 reason=check_failed_IllegalStateException") })
    }

    @Test
    fun a_cancelled_registry_check_keeps_the_entry_and_propagates() = runTest {
        var cancelNext = true
        val r = rig(initialCursor = 3L, heldExists = { if (cancelNext) throw CancellationException("poll cancelled") else true })
        r.orch.primePendingSeqForAckForTest("env-5", 5L)
        r.orch.deferInboundHeld("env-5")
        assertFailsWith<CancellationException> { r.orch.effectiveSinceSeqForTest(3L) }
        assertEquals(5L, r.orch.peekScanSinceSeqForTest(), "no verdict, no change: the next poll re-checks")
        assertTrue(r.log.none { it.startsWith("REST_TRACE scan_revoked") })
        cancelNext = false
        assertEquals(5L, r.orch.effectiveSinceSeqForTest(3L))
    }

    /**
     * Round 3 (review R2 P1). Two barriers; the EARLIER row disappears.
     * Its entry is revoked, but the later barrier's entry must not carry the
     * poll past the earlier sequence: the relay only returns `seq > since_seq`,
     * so the poll has to fall back before env-5 (to 4), not stay at 7.
     */
    @Test
    fun only_the_barrier_whose_row_is_gone_is_revoked_and_the_poll_falls_back_before_it() = runTest {
        val rows = mutableSetOf("env-5", "env-7")
        val r = rig(initialCursor = 3L, heldExists = { it in rows })
        r.orch.primePendingSeqForAckForTest("env-5", 5L); r.orch.deferInboundHeld("env-5")
        r.orch.primePendingSeqForAckForTest("env-7", 7L); r.orch.deferInboundHeld("env-7")
        assertEquals(7L, r.orch.effectiveSinceSeqForTest(3L))
        rows.remove("env-5")
        assertEquals(4L, r.orch.effectiveSinceSeqForTest(3L), "the poll must fall back before the unshielded env-5")
        assertEquals(7L, r.orch.peekScanSinceSeqForTest(), "env-7's entry itself is kept")
        assertTrue(r.log.any { it.startsWith("REST_TRACE scan_revoked id=env-5 seq=5") })
        assertTrue(r.log.none { it.startsWith("REST_TRACE scan_revoked id=env-7") })
        // env-5 held again (the relay re-offered it): the bound is back at 7.
        rows += "env-5"; r.orch.deferInboundHeld("env-5")
        assertEquals(7L, r.orch.effectiveSinceSeqForTest(3L))
        rows.remove("env-7")
        // env-7 is unshielded (7 - 1 = 6), but the only valid scan entry is env-5 at 5:
        // min(5, 6) = 5. Nothing durable stands behind 6, so the poll may not skip it.
        assertEquals(5L, r.orch.effectiveSinceSeqForTest(3L), "the earlier valid barrier env-5 is the highest safe bound")
        rows.remove("env-5")
        assertEquals(3L, r.orch.effectiveSinceSeqForTest(3L))
        assertNull(r.orch.peekScanSinceSeqForTest())
        assertEquals(5L, r.orch.peekPendingSeqForAckForTest("env-5"))
        assertEquals(7L, r.orch.peekPendingSeqForAckForTest("env-7"))
        assertTrue(r.transport.ackCalls.isEmpty()); assertTrue(r.cursor.writes.isEmpty())
    }

    /** Symmetric control: the LATER row disappears; the earlier barrier keeps shielding up to itself. */
    @Test
    fun removing_the_later_barrier_falls_back_to_the_earlier_one() = runTest {
        val rows = mutableSetOf("env-5", "env-7")
        val r = rig(initialCursor = 3L, heldExists = { it in rows })
        r.orch.primePendingSeqForAckForTest("env-5", 5L); r.orch.deferInboundHeld("env-5")
        r.orch.primePendingSeqForAckForTest("env-7", 7L); r.orch.deferInboundHeld("env-7")
        rows.remove("env-7")
        assertEquals(5L, r.orch.effectiveSinceSeqForTest(3L))
        assertEquals(5L, r.orch.peekScanSinceSeqForTest())
    }

    /** A claim released without a durable copy (hold write failed) below a later barrier is not skipped either. */
    @Test
    fun an_unshielded_earlier_envelope_keeps_the_poll_below_it_even_under_a_later_barrier() = runTest {
        val r = rig(initialCursor = 3L, heldExists = { true })
        r.orch.primePendingSeqForAckForTest("env-5", 5L) // tracked, never deferred: the relay must offer it again
        r.orch.primePendingSeqForAckForTest("env-7", 7L)
        assertEquals(HeldDeferralOutcome.ScanAdvanced(seq = 7L, scanSinceSeq = 4L), r.orch.deferInboundHeld("env-7"))
        assertEquals(4L, r.orch.effectiveSinceSeqForTest(3L))
        assertEquals(7L, r.orch.peekScanSinceSeqForTest())
        // env-5 resolves (committed and acknowledged): the bound moves up to the barrier.
        assertEquals(AckOutcome.Acked, r.orch.ackInboundAndAdvanceCursor("env-5"))
        assertEquals(7L, r.orch.effectiveSinceSeqForTest(5L))
    }

    @Test
    fun without_a_registry_seam_the_scan_is_trusted_as_reported() = runTest {
        val r = rig(initialCursor = 3L)
        r.orch.primePendingSeqForAckForTest("env-5", 5L)
        r.orch.deferInboundHeld("env-5")
        assertEquals(5L, r.orch.effectiveSinceSeqForTest(3L))
        assertEquals(5L, r.orch.peekScanSinceSeqForTest())
    }

    @Test
    fun a_revoked_barrier_that_is_held_again_shields_its_sequence_again() = runTest {
        val rows = mutableSetOf<String>()
        val r = rig(initialCursor = 3L, heldExists = { it in rows })
        r.orch.primePendingSeqForAckForTest("env-5", 5L)
        rows += "env-5"; r.orch.deferInboundHeld("env-5")
        rows -= "env-5"
        assertEquals(3L, r.orch.effectiveSinceSeqForTest(3L))
        // The relay re-offered it; the service held it again and reported the deferral again.
        rows += "env-5"; r.orch.deferInboundHeld("env-5")
        assertEquals(5L, r.orch.effectiveSinceSeqForTest(3L))
        assertTrue(r.cursor.writes.isEmpty()); assertTrue(r.transport.ackCalls.isEmpty())
    }
}
