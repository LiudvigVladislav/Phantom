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
 * N1-F1 R-N1.8 — the settlement transaction, against a real SQLite
 * database rather than a fake repository.
 *
 * The defect being closed: the processed-envelope ledger was written
 * ~1350 lines before the message row, so a process death in between
 * left a ledger entry with no message. The redelivery then hit the
 * dedupe gate, was acked, and the message was gone for good.
 *
 * These cases pin the property that makes that impossible: the row and
 * the ledger entry are one transaction, so no observer can ever see one
 * without the other.
 */
class InboundCommitAtomicityTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: PhantomDatabase
    private lateinit var commit: InboundCommitRepository
    private lateinit var messages: MessageRepository
    private lateinit var ledger: ProcessedEnvelopeRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhantomDatabase.Schema.create(driver)
        db = PhantomDatabase(driver)
        commit = SqlDelightInboundCommitRepository(db)
        messages = SqlDelightMessageRepository(db)
        ledger = SqlDelightProcessedEnvelopeRepository(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun row(id: String, conv: String = "conv-1") = MessageEntity(
        id = id,
        conversationId = conv,
        ciphertext = "ct-$id".encodeToByteArray(),
        plaintextCache = "hello $id",
        sent = false,
        status = MessageStatus.DELIVERED,
        createdAt = 1_000L,
        expiresAtMs = null,
    )

    private suspend fun settle(id: String, conv: String = "conv-1") =
        commit.commitInboundMessage(
            message = row(id, conv),
            envelopeId = id,
            conversationId = conv,
            senderPubKeyHex = "aa".repeat(32),
            payloadType = "message",
            nowMs = 2_000L,
        )

    // ── Both writes land, and they land together ──────────────────────

    @Test
    fun a_successful_commit_writes_the_row_and_the_ledger() = runTest {
        settle("env-1")
        assertNotNull(messages.getMessageById("env-1"), "the message row must exist")
        assertTrue(ledger.exists("env-1"), "the ledger entry must exist")
    }

    @Test
    fun the_committed_row_is_identical_to_the_repository_written_one() = runTest {
        // The transaction issues the INSERT through the generated queries
        // rather than through MessageRepository, so a column mapping
        // could drift silently. Compare against the repository's own row.
        settle("env-atomic")
        messages.insertMessage(row("env-repo"))

        val viaCommit = messages.getMessageById("env-atomic")
        val viaRepo = messages.getMessageById("env-repo")
        assertNotNull(viaCommit); assertNotNull(viaRepo)
        assertEquals(viaRepo.conversationId, viaCommit.conversationId)
        assertEquals(
            viaRepo.ciphertext.decodeToString().replace("env-repo", "env-atomic"),
            viaCommit.ciphertext.decodeToString(),
        )
        assertEquals(
            viaRepo.plaintextCache?.replace("env-repo", "env-atomic"),
            viaCommit.plaintextCache,
        )
        assertEquals(viaRepo.sent, viaCommit.sent)
        assertEquals(viaRepo.status, viaCommit.status)
        assertEquals(viaRepo.createdAt, viaCommit.createdAt)
        assertEquals(viaRepo.expiresAtMs, viaCommit.expiresAtMs)
    }

    // ── Fault window: the SECOND statement fails ──────────────────────

    @Test
    fun a_failing_ledger_write_rolls_back_the_message_row() = runTest {
        // R-N1.8 shipped a test with this name that proved nothing: it
        // closed the driver BEFORE the call, then opened a brand-new
        // empty database and asserted two rows were absent from it. Those
        // assertions hold against any implementation, including one with
        // no transaction at all.
        //
        // This is the real thing. A trigger makes the SECOND statement --
        // the ledger insert -- abort, and the assertions run on the SAME
        // open database. If the two writes were not one transaction the
        // message row would survive the ledger failure, which is exactly
        // the state that must be impossible.
        driver.execute(
            identifier = null,
            sql = """
                CREATE TRIGGER reject_ledger
                BEFORE INSERT ON processed_envelopes
                BEGIN
                    SELECT RAISE(ABORT, 'ledger write refused by test trigger');
                END;
            """.trimIndent(),
            parameters = 0,
        )

        val failed = runCatching { settle("env-rollback") }
        assertTrue(
            failed.isFailure,
            "the aborted ledger insert must surface as a failure, not a silent partial commit",
        )

        // Same database, still open.
        assertNull(
            messages.getMessageById("env-rollback"),
            "the message row survived a failed ledger write: the two statements are not one " +
                "transaction, so a redelivery would find a row with no ledger entry -- or, " +
                "with the writes the other way round, a ledger entry with no message",
        )
        assertFalse(ledger.exists("env-rollback"), "and no ledger entry either")
    }

    @Test
    fun the_rollback_test_is_not_vacuous_without_the_trigger() = runTest {
        // The control for the case above: with no trigger the identical
        // call succeeds and both rows land. Without this, a policy that
        // simply never wrote anything would pass the rollback test.
        settle("env-control")
        assertNotNull(messages.getMessageById("env-control"))
        assertTrue(ledger.exists("env-control"))
    }

    @Test
    fun a_write_after_a_rolled_back_commit_still_works() = runTest {
        // A rollback must leave the connection usable, or the first
        // failure would take the whole receive path down with it.
        driver.execute(
            identifier = null,
            sql = """
                CREATE TRIGGER reject_ledger_once
                BEFORE INSERT ON processed_envelopes
                WHEN NEW.envelope_id = 'env-doomed'
                BEGIN
                    SELECT RAISE(ABORT, 'refused');
                END;
            """.trimIndent(),
            parameters = 0,
        )
        assertTrue(runCatching { settle("env-doomed") }.isFailure)
        assertNull(messages.getMessageById("env-doomed"))

        settle("env-after")
        assertNotNull(
            messages.getMessageById("env-after"),
            "the database must remain usable after a rolled-back settlement",
        )
        assertTrue(ledger.exists("env-after"))
    }

    // ── Redelivery convergence ────────────────────────────────────────

    @Test
    fun a_repeated_commit_is_idempotent_and_creates_no_duplicate() = runTest {
        settle("env-dup")
        settle("env-dup")
        settle("env-dup")

        assertNotNull(messages.getMessageById("env-dup"))
        assertTrue(ledger.exists("env-dup"))
        assertEquals(
            1, messages.getMessages("conv-1").count { it.id == "env-dup" },
            "a redelivery must not duplicate the message row",
        )
        assertEquals(
            1L, ledger.countByStatus()[ProcessedEnvelopeRepository.Status.PROCESSED],
            "a redelivery must not duplicate the ledger entry",
        )
    }

    @Test
    fun a_commit_over_an_existing_row_does_not_overwrite_it() = runTest {
        // INSERT OR IGNORE on both tables: an redelivery whose payload
        // differs must not clobber what the first delivery stored.
        messages.insertMessage(row("env-keep").copy(plaintextCache = "original"))
        commit.commitInboundMessage(
            message = row("env-keep").copy(plaintextCache = "replacement"),
            envelopeId = "env-keep",
            conversationId = "conv-1",
            senderPubKeyHex = "aa".repeat(32),
            payloadType = "message",
            nowMs = 2_000L,
        )
        assertEquals(
            "original", messages.getMessageById("env-keep")?.plaintextCache,
            "INSERT OR IGNORE must keep the first row, not the redelivered one",
        )
        assertTrue(ledger.exists("env-keep"), "the ledger must still settle")
    }

    // ── The state the old code could produce is not reachable here ────

    @Test
    fun the_ledger_is_never_ahead_of_the_row() = runTest {
        // The exact defect: ledger present, row absent. With one
        // transaction there is no interleaving that produces it. Assert
        // it over a batch so a partial write would show up.
        repeat(25) { i -> settle("env-batch-$i") }
        repeat(25) { i ->
            val hasRow = messages.getMessageById("env-batch-$i") != null
            val hasLedger = ledger.exists("env-batch-$i")
            assertEquals(
                hasRow, hasLedger,
                "env-batch-$i: row=$hasRow ledger=$hasLedger — the ledger must never be " +
                    "ahead of the row, that is exactly the state that loses messages",
            )
        }
    }
}
