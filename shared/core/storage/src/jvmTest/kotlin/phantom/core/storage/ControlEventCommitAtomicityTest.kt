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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N1-F1b R-N1.12 — the control-event settlement transaction, against a
 * real SQLite engine.
 *
 * Each of the six actions must land together with its ledger entry, and
 * a failing ledger write must roll the action back. The rollback is
 * forced with a `RAISE(ABORT)` trigger on `processed_envelopes` and
 * asserted on the SAME open database, because a test that opens a fresh
 * one proves nothing.
 */
class ControlEventCommitAtomicityTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: PhantomDatabase
    private lateinit var commit: ControlEventCommitRepository
    private lateinit var messages: MessageRepository
    private lateinit var conversations: ConversationRepository
    private lateinit var reactions: ReactionRepository
    private lateinit var ledger: ProcessedEnvelopeRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhantomDatabase.Schema.create(driver)
        db = PhantomDatabase(driver)
        commit = SqlDelightControlEventCommitRepository(db)
        messages = SqlDelightMessageRepository(db)
        conversations = SqlDelightConversationRepository(db)
        reactions = SqlDelightReactionRepository(db)
        ledger = SqlDelightProcessedEnvelopeRepository(db)
    }

    @AfterTest
    fun tearDown() = driver.close()

    private suspend fun seedMessage(id: String, text: String = "original") {
        messages.insertMessage(
            MessageEntity(
                id = id,
                conversationId = CONV,
                ciphertext = "ct".encodeToByteArray(),
                plaintextCache = text,
                sent = false,
                status = MessageStatus.DELIVERED,
                createdAt = 1_000L,
                expiresAtMs = null,
            ),
        )
    }

    private suspend fun seedConversation() {
        conversations.upsertConversation(
            ConversationEntity(
                id = CONV,
                theirUsername = "peer",
                theirPublicKeyHex = SENDER,
                lastMessagePreview = null,
                lastMessageAt = null,
                unreadCount = 0L,
                trustTier = TrustTier.TRUSTED,
            ),
        )
    }

    private suspend fun settle(
        action: ControlEventCommitRepository.Action,
        envelopeId: String,
        type: String,
    ) = commit.commitControlEvent(
        action = action,
        envelopeId = envelopeId,
        conversationId = CONV,
        senderPubKeyHex = SENDER,
        payloadType = type,
        nowMs = 2_000L,
    )

    private fun rejectLedger() = driver.execute(
        identifier = null,
        sql = """
            CREATE TRIGGER reject_ledger
            BEFORE INSERT ON processed_envelopes
            BEGIN
                SELECT RAISE(ABORT, 'ledger refused by test trigger');
            END;
        """.trimIndent(),
        parameters = 0,
    )

    // ── Each action lands with its ledger entry ───────────────────────

    @Test
    fun delete_commits_the_action_and_the_ledger() = runTest {
        seedMessage("m1")
        settle(ControlEventCommitRepository.Action.DeleteMessage("m1"), "e-del", "delete")
        assertNull(messages.getMessageById("m1"), "the message must be gone")
        assertTrue(ledger.exists("e-del"))
    }

    @Test
    fun edit_commits_the_action_and_the_ledger() = runTest {
        seedMessage("m2")
        settle(
            ControlEventCommitRepository.Action.EditMessageText("m2", "edited"),
            "e-edit", "edit",
        )
        assertEquals("edited", messages.getMessageById("m2")?.plaintextCache)
        assertTrue(ledger.exists("e-edit"))
    }

    @Test
    fun timer_commits_the_action_and_the_ledger() = runTest {
        seedConversation()
        settle(
            ControlEventCommitRepository.Action.SetDisappearingTimer(CONV, 60L),
            "e-timer", "disappearing_timer",
        )
        assertEquals(60L, conversations.getDisappearingTimer(CONV))
        assertTrue(ledger.exists("e-timer"))
    }

    @Test
    fun reaction_upsert_and_delete_commit_with_the_ledger() = runTest {
        seedMessage("m3")
        settle(
            ControlEventCommitRepository.Action.UpsertReaction("m3", SENDER, "👍", 3_000L),
            "e-react", "reaction",
        )
        assertEquals(1, reactions.getReactions("m3").size)
        assertTrue(ledger.exists("e-react"))

        settle(
            ControlEventCommitRepository.Action.DeleteReaction("m3", SENDER),
            "e-unreact", "reaction",
        )
        assertEquals(0, reactions.getReactions("m3").size)
        assertTrue(ledger.exists("e-unreact"))
    }

    @Test
    fun pin_commits_the_action_and_the_ledger() = runTest {
        seedMessage("m4")
        settle(
            ControlEventCommitRepository.Action.PinMessage("m4", true, SENDER),
            "e-pin", "pin",
        )
        assertEquals(true, messages.getMessageById("m4")?.pinned)
        assertTrue(ledger.exists("e-pin"))
    }

    @Test
    fun read_receipt_commits_the_action_and_the_ledger() = runTest {
        seedMessage("m5")
        settle(ControlEventCommitRepository.Action.MarkRead("m5"), "e-read", "read_receipt")
        assertEquals(MessageStatus.READ, messages.getMessageById("m5")?.status)
        assertTrue(ledger.exists("e-read"))
    }

    // ── A failing ledger write rolls the action back ──────────────────

    @Test
    fun a_failing_ledger_write_rolls_back_the_delete() = runTest {
        seedMessage("m6")
        rejectLedger()
        assertTrue(
            runCatching {
                settle(ControlEventCommitRepository.Action.DeleteMessage("m6"), "e-x", "delete")
            }.isFailure,
        )
        assertNotNull(
            messages.getMessageById("m6"),
            "the delete survived a failed ledger write: action and ledger are not one " +
                "transaction, so a redelivery would find the message gone with the envelope " +
                "unsettled -- or, the other way round, settled with the message still there",
        )
        assertFalse(ledger.exists("e-x"))
    }

    @Test
    fun a_failing_ledger_write_rolls_back_the_edit() = runTest {
        seedMessage("m7", "original")
        rejectLedger()
        runCatching {
            settle(
                ControlEventCommitRepository.Action.EditMessageText("m7", "should not stick"),
                "e-y", "edit",
            )
        }
        assertEquals(
            "original", messages.getMessageById("m7")?.plaintextCache,
            "the edit survived a failed ledger write",
        )
        assertFalse(ledger.exists("e-y"))
    }

    @Test
    fun a_failing_ledger_write_rolls_back_the_reaction() = runTest {
        seedMessage("m8")
        rejectLedger()
        runCatching {
            settle(
                ControlEventCommitRepository.Action.UpsertReaction("m8", SENDER, "🔥", 3_000L),
                "e-z", "reaction",
            )
        }
        assertEquals(
            0, reactions.getReactions("m8").size,
            "the reaction survived a failed ledger write",
        )
        assertFalse(ledger.exists("e-z"))
    }

    // ── Non-vacuity control ───────────────────────────────────────────

    @Test
    fun without_the_trigger_the_same_calls_succeed() = runTest {
        // Without this, an implementation that never wrote anything would
        // pass every rollback case above.
        seedMessage("m9")
        settle(ControlEventCommitRepository.Action.DeleteMessage("m9"), "e-ok", "delete")
        assertNull(messages.getMessageById("m9"))
        assertTrue(ledger.exists("e-ok"))
    }

    @Test
    fun the_database_stays_usable_after_a_rollback() = runTest {
        seedMessage("m10")
        seedMessage("m11")
        driver.execute(
            identifier = null,
            sql = """
                CREATE TRIGGER reject_one
                BEFORE INSERT ON processed_envelopes
                WHEN NEW.envelope_id = 'e-doomed'
                BEGIN
                    SELECT RAISE(ABORT, 'refused');
                END;
            """.trimIndent(),
            parameters = 0,
        )
        runCatching {
            settle(ControlEventCommitRepository.Action.DeleteMessage("m10"), "e-doomed", "delete")
        }
        assertNotNull(messages.getMessageById("m10"))

        settle(ControlEventCommitRepository.Action.DeleteMessage("m11"), "e-after", "delete")
        assertNull(messages.getMessageById("m11"), "the database must remain usable")
        assertTrue(ledger.exists("e-after"))
    }

    // ── The transaction writes what the repositories write ────────────

    @Test
    fun the_committed_rows_match_the_repository_written_ones() = runTest {
        // The statements are issued through the generated queries rather
        // than the repositories, so a column mapping could drift.
        seedMessage("viaCommit")
        seedMessage("viaRepo")
        settle(
            ControlEventCommitRepository.Action.PinMessage("viaCommit", true, SENDER),
            "e-cmp", "pin",
        )
        messages.pinMessage("viaRepo", pinned = true, pinnedByPubkey = SENDER)

        val a = messages.getMessageById("viaCommit")
        val b = messages.getMessageById("viaRepo")
        assertNotNull(a); assertNotNull(b)
        assertEquals(b.pinned, a.pinned)
        assertEquals(b.pinnedByPubkey, a.pinnedByPubkey)

        seedMessage("statusCommit")
        seedMessage("statusRepo")
        settle(ControlEventCommitRepository.Action.MarkRead("statusCommit"), "e-cmp2", "read_receipt")
        messages.updateStatus("statusRepo", MessageStatus.READ)
        assertEquals(
            messages.getMessageById("statusRepo")?.status,
            messages.getMessageById("statusCommit")?.status,
            "the status string written inside the transaction must match the repository's",
        )
    }

    private companion object {
        const val CONV = "conv-1"
        val SENDER = "aa".repeat(32)
    }
}
