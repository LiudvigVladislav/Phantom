// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.messaging

import android.app.Application
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.core.storage.*
import phantom.core.storage.db.PhantomDatabase
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InboundCommitBindingTest {
    private class Store(val db: PhantomDatabase) {
        val pending = SqlDelightPendingRatchetStateRepository(db)
        val reservations = SqlDelightOpkReservationRepository(db)
        val opks = SqlDelightLocalOneTimePreKeyRepository(db)
        val active = SqlDelightRatchetStateRepository(db)
        val messages = SqlDelightMessageRepository(db)
        val processed = SqlDelightProcessedEnvelopeRepository(db)
        val tx = SqlDelightSessionTransactionRepository(db)

        suspend fun key(id: String = "key", conversation: String = "conv") {
            opks.insert(LocalOneTimePreKeyEntity(id, "11".repeat(32), "22".repeat(32), 1L))
            reservations.reserve(id, "original-envelope", conversation, 1L)
        }

        suspend fun commit(expected: String? = null): InboundCommitOutcome = tx.commitInboundMessage(
            conversationId = "conv", envelopeId = "message", senderPubKeyHex = "sender",
            payloadType = "message", nowMs = 2L,
            message = MessageEntity("message", "conv", byteArrayOf(1), "text", false, MessageStatus.DELIVERED, 2L),
            advancedStateBlob = "advanced", promotePending = true, expectedOpkKeyIdHex = expected,
        )

        suspend fun assertNotCommitted() {
            assertNull(messages.getMessageById("message"))
            assertFalse(processed.exists("message"))
            assertNull(active.getRatchetState("conv"))
            assertEquals("pending", pending.get("conv")?.stateBlob)
        }
    }

    private fun withStore(block: suspend Store.() -> Unit) = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            PhantomDatabase.Schema.create(driver)
            Store(PhantomDatabase(driver)).block()
        } finally {
            driver.close()
        }
    }

    @Test
    fun a_headerless_promotion_consumes_the_stored_key_and_only_that_key() = withStore {
        key()
        assertTrue(tx.commitBootstrap("key", "conv", "pending", null))
        key("unrelated")
        assertEquals(PendingOpkBinding.Bound("key"), pending.get("conv")?.opkBinding)
        assertEquals(InboundCommitOutcome.Committed, commit())
        assertNull(opks.get("key"))
        assertNull(reservations.get("key"))
        assertNotNull(opks.get("unrelated"))
        assertNotNull(reservations.get("unrelated"))
        assertNull(pending.get("conv"))
        assertEquals("advanced", active.getRatchetState("conv"))
        assertTrue(processed.exists("message"))
        assertNotNull(messages.getMessageById("message"))
    }

    @Test
    fun a_candidate_without_a_local_key_leaves_other_reservations_untouched() = withStore {
        tx.commitInitiatorPending("conv", "pending", "peer-key-artifacts", 1L)
        key()
        assertEquals(PendingOpkBinding.None, pending.get("conv")?.opkBinding)
        assertEquals(InboundCommitOutcome.Committed, commit())
        assertNotNull(opks.get("key"))
        assertNotNull(reservations.get("key"))
        assertTrue(processed.exists("message"))
    }

    @Test
    fun an_unknown_binding_is_not_an_absent_key() = withStore {
        pending.upsert("conv", "pending", 1L)
        assertEquals(InboundCommitOutcome.PendingBindingUnknown, commit())
        assertNotCommitted()
    }

    @Test
    fun a_missing_stored_reservation_cannot_be_replaced_by_a_conversation_lookup() = withStore {
        key()
        tx.commitBootstrap("key", "conv", "pending", null)
        reservations.release("key")
        key("unrelated")
        assertEquals(InboundCommitOutcome.ReservationMissing, commit())
        assertNotCommitted()
        assertNotNull(opks.get("key"))
        assertNotNull(opks.get("unrelated"))
        assertNotNull(reservations.get("unrelated"))
    }

    @Test
    fun a_reservation_owned_by_another_conversation_cannot_authorize_a_commit() = withStore {
        pending.upsert("conv", "pending", 1L, opkBinding = PendingOpkBinding.Bound("key"))
        key(conversation = "other")
        assertEquals(InboundCommitOutcome.ReservationMissing, commit())
        assertNotCommitted()
        assertFalse(tx.commitBootstrap("key", "conv", "replacement", null))
        assertEquals("other", reservations.get("key")?.conversationId)
    }

    @Test
    fun a_later_header_cannot_change_the_pending_key_binding() = withStore {
        key()
        tx.commitBootstrap("key", "conv", "pending", null)
        key("unrelated")
        assertEquals(InboundCommitOutcome.PendingBindingMismatch, commit("unrelated"))
        assertNotCommitted()
        assertNotNull(opks.get("key"))
        assertNotNull(opks.get("unrelated"))
    }

    @Test
    fun repository_round_trips_all_three_binding_states() = withStore {
        val bindings = listOf(PendingOpkBinding.Unknown, PendingOpkBinding.None, PendingOpkBinding.Bound("key"))
        for ((i, binding) in bindings.withIndex()) {
            pending.upsert("conv-$i", "state-$i", i.toLong(), opkBinding = binding)
            assertEquals(binding, pending.get("conv-$i")?.opkBinding)
        }
        assertEquals(bindings, pending.getAll().map { it.opkBinding })
    }

    @Test
    fun migration_preserves_unknown_provenance_instead_of_guessing_no_key() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Schema version 22 (before migration file 22.sqm) has the old
            // four-column pending table. Exercise the GENERATED migration.
            driver.execute(null, "CREATE TABLE pending_ratchet_state (conversation_id TEXT NOT NULL PRIMARY KEY, state_blob TEXT NOT NULL, reserved_at_ms INTEGER NOT NULL, bootstrap_artifacts_blob TEXT)", 0)
            driver.execute(null, "CREATE TABLE opk_reservation (opk_key_id_hex TEXT NOT NULL PRIMARY KEY, envelope_id TEXT NOT NULL, conversation_id TEXT NOT NULL, reserved_at_ms INTEGER NOT NULL)", 0)
            fun pending(id: String, artifacts: String = "NULL") {
                driver.execute(null, "INSERT INTO pending_ratchet_state VALUES ('$id', 'state', 10, $artifacts)", 0)
            }
            fun reserve(key: String, conv: String, time: Int = 10) {
                driver.execute(null, "INSERT INTO opk_reservation VALUES ('$key', 'envelope', '$conv', $time)", 0)
            }
            pending("missing")
            pending("bound"); reserve("one", "bound")
            pending("ambiguous"); reserve("two", "ambiguous"); reserve("three", "ambiguous")
            pending("different-time"); reserve("four", "different-time", 11)
            pending("initiator", "'artifacts'"); reserve("unrelated", "initiator")
            assertEquals(25L, PhantomDatabase.Schema.version)
            PhantomDatabase.Schema.migrate(driver, 22L, 23L)
            val repo = SqlDelightPendingRatchetStateRepository(PhantomDatabase(driver))
            assertEquals(PendingOpkBinding.Unknown, repo.get("missing")?.opkBinding)
            assertEquals(PendingOpkBinding.Unknown, repo.get("bound")?.opkBinding,
                "one matching reservation still does not identify which key derived the old candidate")
            assertEquals(PendingOpkBinding.Unknown, repo.get("ambiguous")?.opkBinding)
            assertEquals(PendingOpkBinding.Unknown, repo.get("different-time")?.opkBinding)
            assertEquals(PendingOpkBinding.None, repo.get("initiator")?.opkBinding)
            assertEquals(5, repo.count())
            assertTrue(repo.getAll().all { it.stateBlob == "state" })
            assertEquals(5, SqlDelightOpkReservationRepository(PhantomDatabase(driver)).count())
        } finally {
            driver.close()
        }
    }

    @Test
    fun held_retry_migration_preserves_the_ciphertext_and_allows_one_fresh_attempt() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(null, "CREATE TABLE decrypt_failed_envelopes (" +
                "envelope_id TEXT NOT NULL PRIMARY KEY, conversation_id TEXT NOT NULL, " +
                "sender_pubkey_hex TEXT NOT NULL, error_type TEXT NOT NULL, received_at_ms INTEGER NOT NULL, " +
                "x3dh_init_present INTEGER NOT NULL DEFAULT 0, wire_frame_json TEXT NOT NULL, " +
                "replay_attempt_count INTEGER NOT NULL DEFAULT 0, last_replay_at_ms INTEGER)", 0)
            driver.execute(null, "INSERT INTO decrypt_failed_envelopes VALUES " +
                "('held', 'conv', 'sender', 'mac', 1, 1, 'ciphertext', 3, 2)", 0)
            PhantomDatabase.Schema.migrate(driver, 23L, 24L)
            val repository = SqlDelightDecryptFailedEnvelopeRepository(PhantomDatabase(driver))
            val entry = repository.listByConversation("conv").single()
            assertEquals("ciphertext", entry.wireFrameJson)
            assertEquals(3L, entry.replayAttemptCount)
            assertNull(entry.lastReceiveVersion, "the old attempt limit must not lock out recovery")
            repository.recordFailure("held", "commit", "receive-version", 4L)
            val updated = repository.listByConversation("conv").single()
            assertEquals("commit", updated.errorType)
            assertEquals("receive-version", updated.lastReceiveVersion)
            assertEquals(4L, updated.lastReplayAtMs)
            assertEquals("ciphertext", updated.wireFrameJson)
        } finally {
            driver.close()
        }
    }
}
