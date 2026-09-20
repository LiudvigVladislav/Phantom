// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import phantom.core.storage.db.PhantomDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * residual N1 Revision 4 — `replaceMessage` against a real SQLite database.
 *
 * The defect being closed: Revision 3 implemented the envelope replacement as
 * `DELETE` then `INSERT` inside one transaction. The transaction kept the
 * message row from ever being observably absent, which is what it was written
 * to do — and it destroyed two things anyway, both silently:
 *
 *  - `reaction.message_id` references `message(id) ON DELETE CASCADE`, so
 *    wherever foreign keys are enforced the delete took every reaction on the
 *    message with it, and the re-insert did not bring them back;
 *  - `insertMessage` does not carry `pinned`, `saved` or `pinned_by_pubkey`,
 *    so the re-insert reset all three to their defaults. That one does not
 *    need foreign keys switched on to happen.
 *
 * SQLite enforces foreign keys only when asked, so the cascade case turns it
 * on explicitly here rather than assuming a default. The user-state case runs
 * on the same database and would have failed either way.
 */
class MessageEnvelopeReplacementTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: PhantomDatabase
    private lateinit var messages: MessageRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhantomDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        db = PhantomDatabase(driver)
        messages = SqlDelightMessageRepository(db)
    }

    @AfterTest
    fun tearDown() = driver.close()

    /** Proof that the pragma took, so the cascade assertions below mean something. */
    private fun foreignKeysOn(): Boolean =
        driver.executeQuery(null, "PRAGMA foreign_keys;", { c -> app.cash.sqldelight.db.QueryResult.Value(c.next().value && c.getLong(0) == 1L) }, 0)
            .value

    private suspend fun conversation(id: String = CONV) {
        SqlDelightConversationRepository(db).upsertConversation(
            ConversationEntity(
                id = id,
                theirUsername = "bob",
                theirPublicKeyHex = "ccdd",
                lastMessagePreview = null,
                lastMessageAt = null,
                unreadCount = 0L,
                trustTier = TrustTier.TRUSTED,
            ),
        )
    }

    private fun original() = MessageEntity(
        id = MSG,
        conversationId = CONV,
        ciphertext = "envelope-v1".encodeToByteArray(),
        plaintextCache = "hello",
        sent = false,
        status = MessageStatus.QUEUED,
        createdAt = 1_000L,
        expiresAtMs = null,
    )

    private fun replacement() = original().copy(
        ciphertext = "envelope-v2".encodeToByteArray(),
        status = MessageStatus.QUEUED,
        // A caller that got these wrong must not be able to move the message:
        // the query does not write them, and the repository rejects a change.
        conversationId = CONV,
        createdAt = 1_000L,
    )

    private fun reactions() = db.reactionQueries.getReactionsForMessage(MSG).executeAsList()

    private fun raw() = db.messageQueries.getMessageById(MSG).executeAsOne()

    // ── D02 ────────────────────────────────────────────────────────────────

    @Test
    fun the_envelope_is_rewritten_in_place_and_the_reaction_survives() = runTest {
        assertTrue(foreignKeysOn(), "control: foreign keys must be enforced or the cascade proves nothing")
        conversation()
        messages.insertMessage(original())
        db.reactionQueries.upsertReaction(MSG, "aabb", "👍", 1_500L)
        assertEquals(1, reactions().size, "precondition: the reaction is attached")

        messages.replaceMessage(replacement())

        val after = raw()
        assertEquals(MSG, after.id, "the row id is unchanged")
        assertContentEquals("envelope-v2".encodeToByteArray(), after.ciphertext, "the envelope is the new one")
        assertEquals("queued", after.status)
        assertEquals(CONV, after.conversation_id, "the identity columns are untouched")
        assertEquals(1_000L, after.created_at)
        assertEquals(1, reactions().size, "the reaction survived the replacement")
        assertEquals("👍", reactions().single().emoji)
        assertEquals(1, db.messageQueries.getMessages(CONV).executeAsList().size, "exactly one message row")
    }

    @Test
    fun pinned_and_saved_state_survives_the_replacement() = runTest {
        conversation()
        messages.insertMessage(original())
        messages.pinMessage(MSG, pinned = true, pinnedByPubkey = "aabb")
        messages.saveMessage(MSG)
        messages.setExpiresAt(MSG, 9_000L)

        messages.replaceMessage(replacement().copy(expiresAtMs = 9_000L))

        val after = raw()
        assertEquals(1L, after.pinned, "a pinned message stays pinned")
        assertEquals("aabb", after.pinned_by_pubkey, "and remembers who pinned it")
        assertEquals(1L, after.saved, "a saved message stays saved")
        assertEquals(9_000L, after.expires_at_ms)
        assertContentEquals("envelope-v2".encodeToByteArray(), after.ciphertext)
    }

    /**
     * Positive control for the two tests above: the Revision 3 shape, run
     * against the same database, really does destroy both. Without this the
     * assertions could pass for a reason unrelated to the change.
     */
    @Test
    fun control_the_revision_3_delete_and_insert_destroys_both() = runTest {
        assertTrue(foreignKeysOn())
        conversation()
        messages.insertMessage(original())
        db.reactionQueries.upsertReaction(MSG, "aabb", "👍", 1_500L)
        messages.pinMessage(MSG, pinned = true, pinnedByPubkey = "aabb")
        messages.saveMessage(MSG)

        db.transaction {
            db.messageQueries.deleteMessage(MSG)
            db.messageQueries.insertMessage(
                id = MSG, conversation_id = CONV, ciphertext = "envelope-v2".encodeToByteArray(),
                plaintext_cache = "hello", sent = 0L, status = "queued", created_at = 1_000L,
                expires_at_ms = null,
            )
        }

        assertEquals(0, reactions().size, "control: the cascade removed the reaction")
        assertEquals(0L, raw().pinned, "control: the re-insert reset pinned")
        assertEquals(0L, raw().saved, "control: the re-insert reset saved")
    }

    // ── D03 ────────────────────────────────────────────────────────────────

    @Test
    fun a_missing_row_throws_and_is_not_created() = runTest {
        conversation()

        assertFailsWith<NoSuchElementException> { messages.replaceMessage(replacement()) }

        assertEquals(
            0, db.messageQueries.getMessages(CONV).executeAsList().size,
            "a replacement must never invent the message it was asked to replace",
        )
    }

    @Test
    fun an_injected_failure_leaves_the_message_and_the_reaction_byte_for_byte_intact() = runTest {
        assertTrue(foreignKeysOn())
        conversation()
        messages.insertMessage(original())
        db.reactionQueries.upsertReaction(MSG, "aabb", "👍", 1_500L)
        messages.pinMessage(MSG, pinned = true, pinnedByPubkey = "aabb")
        val before = raw()
        val reactionsBefore = reactions()

        // The failure is injected where a real one lands: inside the
        // transaction, after the update has been issued.
        assertFailsWith<IllegalStateException> {
            db.transaction {
                db.messageQueries.updateMessageEnvelope(
                    ciphertext = "envelope-v2".encodeToByteArray(),
                    plaintextCache = "hello",
                    sent = 0L,
                    status = "queued",
                    expiresAtMs = null,
                    id = MSG,
                )
                throw IllegalStateException("injected failure after the update")
            }
        }

        val after = raw()
        assertContentEquals(before.ciphertext, after.ciphertext, "the old envelope is still on the row")
        assertEquals(before.status, after.status)
        assertEquals(before.pinned, after.pinned)
        assertEquals(before.pinned_by_pubkey, after.pinned_by_pubkey)
        assertEquals(before.created_at, after.created_at)
        assertEquals(reactionsBefore.size, reactions().size, "the reaction is untouched")
        assertEquals(reactionsBefore.single().emoji, reactions().single().emoji)
    }

    private companion object {
        const val CONV = "aabb_ccdd"
        const val MSG = "msg-1"
    }
}
