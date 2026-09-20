// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * residual N1 — the pure outbox model: which rows are work, which state a
 * stored envelope belongs to, what settlement owes it, and what order the
 * ratchet actually encrypted in.
 *
 * Revision 3 replaced two things the independent review rejected:
 *  - a recovery decision that compared every envelope against the ACTIVE
 *    session's `sendCount`, which says nothing about an envelope produced from
 *    the initiator-pending candidate (a different chain, a different count);
 *  - an order built from `createdAt` and a random id, which is not the order
 *    the ratchet encrypted in and therefore cannot keep a successor behind its
 *    predecessor.
 *
 * Everything here is pure, so it runs on a host without the libsodium binding.
 * `OutboxRecoveryOrchestrationTest` drives the same rules through the service.
 */
class OutboxRetrySelectionTest {

    private val active = PersistedChain("aa".repeat(32), sendCount = 5, ChainSource.ACTIVE)
    private val pending = PersistedChain("bb".repeat(32), sendCount = 1, ChainSource.PENDING)
    private val superseded = "cc".repeat(32)
    private val superseded2 = "dd".repeat(32)

    private fun row(
        id: String,
        status: MessageStatus = MessageStatus.QUEUED,
        createdAt: Long = 100L,
        ciphertext: ByteArray = byteArrayOf(1, 2, 3),
    ) = MessageEntity(
        id = id, conversationId = "conv", ciphertext = ciphertext, plaintextCache = "text-$id",
        sent = true, status = status, createdAt = createdAt,
    )

    /** The envelope each row carries, supplied directly: no JSON in the pure tests. */
    private fun refs(vararg pairs: Pair<String, StoredEnvelopeRef?>): (MessageEntity) -> StoredEnvelopeRef? {
        val m = pairs.toMap()
        return { m[it.id] }
    }

    // ── selection ──────────────────────────────────────────────────────────

    @Test
    fun only_queued_rows_with_an_envelope_are_work() {
        val rows = listOf(
            row("queued"),
            row("sent", status = MessageStatus.SENT),
            row("placeholder", status = MessageStatus.WAITING_FOR_RECIPIENT_BUNDLE, ciphertext = ByteArray(0)),
            row("empty-queued", ciphertext = ByteArray(0)),
            row("unrecoverable", status = MessageStatus.FAILED),
        )
        assertEquals(listOf("queued"), retryableQueuedRows(rows).map { it.id })
    }

    // ── recovery decision, per source of state ─────────────────────────────

    @Test
    fun an_active_envelope_the_saved_count_covers_is_resent() {
        assertEquals(
            OutboxRecovery.RESEND_STORED,
            outboxRecoveryFor(StoredEnvelopeRef(active.chainKeyHex, 4), listOf(active)),
        )
    }

    /**
     * W1 on the active session: encrypted at `sendCount` 5, the save never
     * happened. The state on disk is still the exact state that produced the
     * envelope, so the commit it missed can be performed now — no plaintext,
     * no second envelope, no second index.
     */
    @Test
    fun an_active_envelope_at_the_saved_count_is_adopted() {
        assertEquals(
            OutboxRecovery.ADOPT_STEP,
            outboxRecoveryFor(StoredEnvelopeRef(active.chainKeyHex, 5), listOf(active)),
        )
    }

    /**
     * The Revision 2 defect, as a negative test. A pending-reuse envelope sits
     * at index 0 on chain B while the ACTIVE session's `sendCount` is 5. A rule
     * that compared against the active count would say "0 < 5, re-send the
     * stored bytes" — while chain B's own count is 0, i.e. its commit never
     * happened and the peer would be handed a step the sender does not believe
     * it took.
     */
    @Test
    fun a_pending_envelope_is_never_judged_by_the_active_count() {
        val uncommittedPending = PersistedChain(pending.chainKeyHex, sendCount = 0, ChainSource.PENDING)
        assertEquals(
            OutboxRecovery.ADOPT_STEP,
            outboxRecoveryFor(StoredEnvelopeRef(pending.chainKeyHex, 0), listOf(active, uncommittedPending)),
            "the active session's count says nothing about an envelope from the pending chain",
        )
        assertEquals(
            OutboxRecovery.RESEND_STORED,
            outboxRecoveryFor(StoredEnvelopeRef(pending.chainKeyHex, 0), listOf(active, pending)),
            "once the pending commit happened (count 1) the same envelope is re-sent",
        )
        assertEquals(
            OutboxRecovery.ADOPT_STEP,
            outboxRecoveryFor(StoredEnvelopeRef(pending.chainKeyHex, 1), listOf(active, pending)),
            "and index 1 is below the active count of 5 yet uncommitted on its own chain",
        )
    }

    @Test
    fun an_envelope_from_a_chain_no_state_remembers_is_reencrypted() {
        // Fresh-bootstrap W1: the bootstrap state was never committed, so no
        // persisted chain carries this key. Also the superseded-generation case.
        assertEquals(
            OutboxRecovery.REENCRYPT_FROM_SESSION,
            outboxRecoveryFor(StoredEnvelopeRef(superseded, 0), listOf(active, pending)),
        )
        assertEquals(
            OutboxRecovery.REENCRYPT_FROM_SESSION,
            outboxRecoveryFor(StoredEnvelopeRef(superseded, 0), emptyList()),
            "no state at all: nothing can be proven",
        )
    }

    @Test
    fun an_unparsable_envelope_is_reencrypted() {
        assertEquals(OutboxRecovery.REENCRYPT_FROM_SESSION, outboxRecoveryFor(null, listOf(active)))
    }

    @Test
    fun an_index_beyond_the_saved_count_is_held_not_guessed() {
        // No code path produces it; the model refuses to invent a meaning.
        assertEquals(
            OutboxRecovery.HOLD,
            outboxRecoveryFor(StoredEnvelopeRef(active.chainKeyHex, 6), listOf(active)),
        )
    }

    @Test
    fun the_boundary_between_committed_and_uncommitted_is_exact() {
        for (i in 0 until active.sendCount) {
            assertEquals(
                OutboxRecovery.RESEND_STORED,
                outboxRecoveryFor(StoredEnvelopeRef(active.chainKeyHex, i), listOf(active)),
                "index $i",
            )
        }
        assertEquals(
            OutboxRecovery.ADOPT_STEP,
            outboxRecoveryFor(StoredEnvelopeRef(active.chainKeyHex, active.sendCount), listOf(active)),
        )
        for (i in active.sendCount + 1..active.sendCount + 2) {
            assertEquals(
                OutboxRecovery.HOLD,
                outboxRecoveryFor(StoredEnvelopeRef(active.chainKeyHex, i), listOf(active)),
                "index $i",
            )
        }
    }

    @Test
    fun w1_is_not_closed_by_the_mere_presence_of_a_queued_envelope() {
        val rows = listOf(row("w1"))
        assertEquals(1, retryableQueuedRows(rows).size, "the row is work")
        assertEquals(
            OutboxRecovery.ADOPT_STEP,
            outboxRecoveryFor(StoredEnvelopeRef(active.chainKeyHex, active.sendCount), listOf(active)),
            "and being work does not mean 're-send it'",
        )
        assertEquals(
            OutboxRecovery.RESEND_STORED,
            outboxRecoveryFor(StoredEnvelopeRef(active.chainKeyHex, active.sendCount), listOf(active.copy(sendCount = 6))),
            "the same envelope, one commit later, is a different decision",
        )
    }

    // ── order, from the ratchet ────────────────────────────────────────────

    @Test
    fun within_one_chain_the_order_is_the_message_index_and_nothing_else() {
        val rows = listOf(row("c", createdAt = 1L), row("a", createdAt = 3L), row("b", createdAt = 2L))
        val order = orderOutbox(rows, refs(
            "a" to StoredEnvelopeRef(active.chainKeyHex, 0),
            "b" to StoredEnvelopeRef(active.chainKeyHex, 1),
            "c" to StoredEnvelopeRef(active.chainKeyHex, 2),
        ), listOf(active))
        assertIs<OutboxOrder.Ordered>(order)
        assertEquals(listOf("a", "b", "c"), order.rows.map { it.first.id },
            "createdAt says c,b,a; the ratchet says a,b,c")
    }

    /**
     * The mandatory negative test. Two messages share a millisecond. The one
     * encrypted SECOND (index 1) has the lexically SMALLER id. A `createdAt`
     * + id order would put it first; the ratchet order must not.
     */
    @Test
    fun a_later_message_with_a_lexically_smaller_id_does_not_overtake() {
        val first = row("zzz-first", createdAt = 500L)
        val second = row("aaa-second", createdAt = 500L)
        val envelopeOf = refs(
            "zzz-first" to StoredEnvelopeRef(active.chainKeyHex, 0),
            "aaa-second" to StoredEnvelopeRef(active.chainKeyHex, 1),
        )
        val order = orderOutbox(listOf(second, first), envelopeOf, listOf(active))
        assertIs<OutboxOrder.Ordered>(order)
        assertEquals(listOf("zzz-first", "aaa-second"), order.rows.map { it.first.id })
        assertEquals(
            listOf("aaa-second", "zzz-first"),
            listOf(second, first).sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.id },
            "control: the rejected createdAt + id order really does reverse them",
        )
        assertTrue(
            hasUnresolvedPredecessor(listOf(first, second), envelopeOf, listOf(active),
                self = StoredEnvelopeRef(active.chainKeyHex, 1), selfId = "aaa-second"),
            "the guard must hold the later message behind the earlier one too",
        )
        assertFalse(
            hasUnresolvedPredecessor(listOf(first, second), envelopeOf, listOf(active),
                self = StoredEnvelopeRef(active.chainKeyHex, 0), selfId = "zzz-first"),
            "and the earlier one is not held behind the later one",
        )
    }

    @Test
    fun the_active_chain_drains_before_the_pending_one() {
        val rows = listOf(row("p"), row("a"))
        val order = orderOutbox(rows, refs(
            "p" to StoredEnvelopeRef(pending.chainKeyHex, 0),
            "a" to StoredEnvelopeRef(active.chainKeyHex, 3),
        ), listOf(active, pending))
        assertIs<OutboxOrder.Ordered>(order)
        assertEquals(listOf("a", "p"), order.rows.map { it.first.id },
            "sends go through the pending candidate when one exists, so it is the newer chain")
    }

    /**
     * `LibsodiumDoubleRatchet.decrypt` keeps no skipped message keys, so bytes
     * from a chain no persisted state remembers can never be decrypted by the
     * peer. They have no position in the wire order; settlement must re-encrypt
     * them before the drain can order anything.
     */
    @Test
    fun a_chain_no_state_remembers_has_no_position_and_holds_the_queue() {
        val order = orderOutbox(listOf(row("s"), row("a")), refs(
            "s" to StoredEnvelopeRef(superseded, 9),
            "a" to StoredEnvelopeRef(active.chainKeyHex, 3),
        ), listOf(active, pending))
        assertIs<OutboxOrder.Unprovable>(order)
        assertTrue(order.reason.startsWith("unsettled_chain"), order.reason)
        assertTrue("s" in order.reason, order.reason)
    }

    @Test
    fun two_unsettled_chains_are_held_as_well() {
        val order = orderOutbox(listOf(row("x"), row("y")), refs(
            "x" to StoredEnvelopeRef(superseded, 0),
            "y" to StoredEnvelopeRef(superseded2, 0),
        ), listOf(active))
        assertIs<OutboxOrder.Unprovable>(order)
        assertTrue(order.reason.startsWith("unsettled_chain"), order.reason)
    }

    @Test
    fun two_rows_claiming_one_step_cannot_be_ordered_and_are_held() {
        val order = orderOutbox(listOf(row("x"), row("y")), refs(
            "x" to StoredEnvelopeRef(active.chainKeyHex, 3),
            "y" to StoredEnvelopeRef(active.chainKeyHex, 3),
        ), listOf(active))
        assertIs<OutboxOrder.Unprovable>(order)
        assertTrue(order.reason.startsWith("duplicate_index"), order.reason)
    }

    @Test
    fun an_unparsable_envelope_has_no_position_and_holds_the_queue() {
        val order = orderOutbox(listOf(row("ok"), row("bad")), refs(
            "ok" to StoredEnvelopeRef(active.chainKeyHex, 0),
            "bad" to null,
        ), listOf(active))
        assertIs<OutboxOrder.Unprovable>(order)
        assertTrue(order.reason.startsWith("unparsable_envelope"), order.reason)
    }

    @Test
    fun a_chain_no_state_remembers_has_no_rank() {
        assertEquals(0, chainRank(active.chainKeyHex, listOf(active, pending)))
        assertEquals(1, chainRank(pending.chainKeyHex, listOf(active, pending)))
        assertNull(chainRank(superseded, listOf(active, pending)))
        assertNull(chainRank(active.chainKeyHex, emptyList()))
    }

    // ── predecessor guard ──────────────────────────────────────────────────

    @Test
    fun a_successor_is_held_behind_a_lower_index_on_its_own_chain() {
        val queued = listOf(row("older"))
        val envelopeOf = refs("older" to StoredEnvelopeRef(active.chainKeyHex, 1))
        assertTrue(hasUnresolvedPredecessor(queued, envelopeOf, listOf(active), StoredEnvelopeRef(active.chainKeyHex, 3), "me"))
        assertFalse(hasUnresolvedPredecessor(queued, envelopeOf, listOf(active), StoredEnvelopeRef(active.chainKeyHex, 0), "me"),
            "an envelope with a lower index than the queued one is not its successor")
    }

    @Test
    fun a_message_is_not_its_own_predecessor() {
        val rows = listOf(row("me"))
        assertFalse(hasUnresolvedPredecessor(rows, refs("me" to StoredEnvelopeRef(active.chainKeyHex, 2)), listOf(active),
            StoredEnvelopeRef(active.chainKeyHex, 2), "me"))
    }

    @Test
    fun an_unsettled_chain_in_the_queue_always_holds_a_new_send() {
        val rows = listOf(row("old"))
        assertTrue(hasUnresolvedPredecessor(rows, refs("old" to StoredEnvelopeRef(superseded, 7)), listOf(active),
            StoredEnvelopeRef(active.chainKeyHex, 0), "me"))
    }

    @Test
    fun a_send_from_a_chain_the_state_does_not_know_is_held() {
        // Defensive: the caller's own envelope must be placeable too.
        val rows = listOf(row("old"))
        assertTrue(hasUnresolvedPredecessor(rows, refs("old" to StoredEnvelopeRef(active.chainKeyHex, 0)), listOf(active),
            StoredEnvelopeRef(superseded, 0), "me"))
    }

    @Test
    fun an_unprovable_queue_holds_a_new_send() {
        val rows = listOf(row("bad"))
        assertTrue(hasUnresolvedPredecessor(rows, refs("bad" to null), listOf(active),
            StoredEnvelopeRef(active.chainKeyHex, 0), "me"),
            "if the queue cannot be ordered, sending might overtake something")
    }

    @Test
    fun a_queued_active_row_holds_back_a_send_on_the_pending_chain() {
        val rows = listOf(row("a"))
        assertTrue(hasUnresolvedPredecessor(rows, refs("a" to StoredEnvelopeRef(active.chainKeyHex, 4)),
            listOf(active, pending), StoredEnvelopeRef(pending.chainKeyHex, 0), "me"))
    }

    @Test
    fun a_delivered_predecessor_holds_nothing_back() {
        val rows = listOf(row("done", status = MessageStatus.SENT))
        assertFalse(hasUnresolvedPredecessor(rows, refs("done" to StoredEnvelopeRef(active.chainKeyHex, 0)), listOf(active),
            StoredEnvelopeRef(active.chainKeyHex, 1), "me"))
    }
}
