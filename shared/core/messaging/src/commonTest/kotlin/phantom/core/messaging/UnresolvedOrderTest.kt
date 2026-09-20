// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * residual N1 Revision 4 — the order in which settlement is allowed to
 * re-encrypt rows whose chain no persisted state remembers.
 *
 * Re-encrypting an unresolved row gives it a new index on the current chain,
 * so the sequence settlement chooses is the sequence the peer will see.
 * Revision 3 took the first row in repository order, which is `created_at
 * ASC` — a timestamp order under another name, and the very thing Revision 3
 * had already rejected for known chains.
 *
 * Every case here supplies rows in an order that would give the wrong answer
 * if the list position were trusted, so a regression to list order fails
 * rather than passing by luck.
 */
class UnresolvedOrderTest {

    private val chainA = "aa".repeat(32)
    private val chainB = "bb".repeat(32)

    private fun row(id: String, createdAt: Long = 500L) = MessageEntity(
        id = id, conversationId = "conv", ciphertext = byteArrayOf(1),
        plaintextCache = "text-$id", sent = true, status = MessageStatus.QUEUED, createdAt = createdAt,
    )

    private fun at(chain: String, index: Int) = StoredEnvelopeRef(chain, index)

    // ── what can be proven ─────────────────────────────────────────────────

    @Test
    fun nothing_unresolved_is_not_a_choice_to_make() {
        assertIs<UnresolvedChoice.None>(nextUnresolved(emptyList()))
    }

    @Test
    fun a_single_unresolved_row_is_next_with_no_position_to_prove() {
        val only = row("only")
        val choice = assertIs<UnresolvedChoice.Recover>(nextUnresolved(listOf(only to at(chainA, 7))))
        assertEquals("only", choice.row.id)
    }

    @Test
    fun a_single_unparsable_row_is_still_the_one_to_handle() {
        // Nothing competes with it, so settlement may act on it — which for a
        // row with no plaintext means marking it failed, not sending anything.
        val only = row("only")
        val choice = assertIs<UnresolvedChoice.Recover>(nextUnresolved(listOf(only to null)))
        assertEquals("only", choice.row.id)
        assertEquals(null, choice.ref)
    }

    @Test
    fun within_one_forgotten_chain_the_lowest_index_is_next() {
        val first = row("first")
        val second = row("second")
        // Supplied second-then-first: list position would answer "second".
        val choice = assertIs<UnresolvedChoice.Recover>(
            nextUnresolved(listOf(second to at(chainA, 1), first to at(chainA, 0))),
        )
        assertEquals("first", choice.row.id)
        assertEquals(0, choice.ref?.index)
    }

    @Test
    fun equal_timestamps_do_not_decide_anything() {
        val later = row("aaa-later", createdAt = 500L)
        val earlier = row("zzz-earlier", createdAt = 500L)
        val choice = assertIs<UnresolvedChoice.Recover>(
            nextUnresolved(listOf(later to at(chainA, 1), earlier to at(chainA, 0))),
        )
        assertEquals("zzz-earlier", choice.row.id, "the ratchet index decides, not the id and not the clock")
        assertEquals(
            listOf("aaa-later", "zzz-earlier"),
            listOf(later, earlier).sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.id },
            "control: the rejected createdAt + id order really does reverse them",
        )
    }

    @Test
    fun a_gap_in_the_indices_does_not_stop_the_lowest_from_being_next() {
        val a = row("a")
        val b = row("b")
        val choice = assertIs<UnresolvedChoice.Recover>(
            nextUnresolved(listOf(b to at(chainA, 9), a to at(chainA, 4))),
        )
        assertEquals("a", choice.row.id)
    }

    // ── what cannot, and therefore stops ───────────────────────────────────

    @Test
    fun two_forgotten_chains_have_no_provable_order() {
        val choice = assertIs<UnresolvedChoice.Unprovable>(
            nextUnresolved(listOf(row("a") to at(chainA, 0), row("b") to at(chainB, 0))),
        )
        assertTrue(choice.reason.startsWith("multiple_forgotten_chains"), choice.reason)
        assertTrue("count=2" in choice.reason, choice.reason)
    }

    @Test
    fun two_rows_claiming_one_step_have_no_provable_order() {
        val choice = assertIs<UnresolvedChoice.Unprovable>(
            nextUnresolved(listOf(row("a") to at(chainA, 3), row("b") to at(chainA, 3))),
        )
        assertTrue(choice.reason.startsWith("duplicate_index"), choice.reason)
    }

    @Test
    fun an_unparsable_row_among_others_has_no_position_at_all() {
        val choice = assertIs<UnresolvedChoice.Unprovable>(
            nextUnresolved(listOf(row("ok") to at(chainA, 0), row("bad") to null)),
        )
        assertTrue(choice.reason.startsWith("unparsable_among_unresolved"), choice.reason)
        assertTrue("bad" in choice.reason, choice.reason)
    }

    @Test
    fun an_unparsable_row_is_reported_before_a_chain_count() {
        // Both defects at once: the unparsable row is named, because a caller
        // cannot act on a chain count it has no complete set for.
        val choice = assertIs<UnresolvedChoice.Unprovable>(
            nextUnresolved(listOf(row("a") to at(chainA, 0), row("b") to at(chainB, 0), row("bad") to null)),
        )
        assertTrue(choice.reason.startsWith("unparsable_among_unresolved"), choice.reason)
    }

    /**
     * Negative control on the whole model: list position must not be the
     * answer in any case above where it differs from the index.
     */
    @Test
    fun control_list_position_is_never_the_answer() {
        val first = row("first")
        val second = row("second")
        val supplied = listOf(second to at(chainA, 1), first to at(chainA, 0))
        assertEquals(
            "second", supplied.first().first.id,
            "control: list position would answer 'second'",
        )
        assertEquals(
            "first", assertIs<UnresolvedChoice.Recover>(nextUnresolved(supplied)).row.id,
            "the model answers 'first'",
        )
    }
}
