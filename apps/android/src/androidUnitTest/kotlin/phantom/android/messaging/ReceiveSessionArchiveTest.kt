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
class ReceiveSessionArchiveTest {
    private class Store(val db: PhantomDatabase) {
        var now = 1_000L
        var failAt: String? = null
        val tx = SqlDelightSessionTransactionRepository(db,
            transactionProbe = { if (it == failAt) error("transaction fault at $it") }, clock = { now })
        val active = SqlDelightRatchetStateRepository(db)
        val pending = SqlDelightPendingRatchetStateRepository(db)
        val messages = SqlDelightMessageRepository(db)
        val processed = SqlDelightProcessedEnvelopeRepository(db)
        suspend fun commit(id: String, state: String, target: InboundStateTarget,
            conversation: String = "conv", promote: Boolean = false): InboundCommitOutcome = tx.commitInboundMessage(
            conversationId = conversation, envelopeId = id, senderPubKeyHex = "sender", payloadType = "message",
            nowMs = now, message = MessageEntity(id, conversation, byteArrayOf(1), id,
                false, MessageStatus.DELIVERED, now), advancedStateBlob = state,
            promotePending = promote, stateTarget = target)
        suspend fun archives() = tx.listReceiveArchives("conv", now)
        suspend fun assertNoMessage(id: String) {
            assertNull(messages.getMessageById(id))
            assertFalse(processed.exists(id))
        }
    }

    private fun withStore(block: suspend Store.() -> Unit) = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            PhantomDatabase.Schema.create(driver)
            Store(PhantomDatabase(driver)).block()
        } finally { driver.close() }
    }

    @Test fun replacement_preserves_old_state_and_replay_updates_only_its_archive() = withStore {
        active.upsertRatchetState("conv", "old-0")
        assertEquals(InboundCommitOutcome.Committed, commit("new", "new-0", InboundStateTarget.ReplaceActive))
        val old = archives().single()
        assertEquals("old-0", old.stateBlob)
        now += 123L
        assertEquals(InboundCommitOutcome.Committed,
            commit("old-next", "old-1", InboundStateTarget.Archive(old.id, old.revision)))
        assertEquals("new-0", active.getRatchetState("conv"))
        assertEquals(old.expiresAtMs, archives().single().expiresAtMs, "replay cannot extend retention")
        assertEquals("old-1", archives().single().stateBlob)
        assertEquals(old.revision + 1L, archives().single().revision)
    }

    @Test fun held_promotion_preserves_active_and_consumes_only_the_bound_key() = withStore {
        active.upsertRatchetState("conv", "current")
        val opks = SqlDelightLocalOneTimePreKeyRepository(db)
        val reservations = SqlDelightOpkReservationRepository(db)
        opks.insert(LocalOneTimePreKeyEntity("key", "11".repeat(32), "22".repeat(32), now))
        reservations.reserve("key", "old", "conv", now)
        assertTrue(tx.commitBootstrap("key", "conv", "candidate", null))
        assertEquals(InboundCommitOutcome.Committed,
            commit("old", "old-advanced", InboundStateTarget.KeepActive, promote = true))
        assertEquals("current", active.getRatchetState("conv"))
        assertEquals("old-advanced", archives().single().stateBlob)
        assertNull(pending.get("conv"))
        assertNull(opks.get("key"))
        assertNull(reservations.get("key"))
    }

    @Test fun failed_archive_swap_rolls_back_both_chains_message_and_ledger() = withStore {
        active.upsertRatchetState("conv", "old")
        commit("new", "current", InboundStateTarget.ReplaceActive)
        val old = archives().single()
        failAt = "after_message"
        assertFailsWith<IllegalStateException> {
            commit("swap", "old-advanced", InboundStateTarget.Archive(old.id, old.revision, activate = true))
        }
        assertNoMessage("swap")
        assertEquals("current", active.getRatchetState("conv"))
        assertEquals(old, archives().single())
        failAt = null
        assertEquals(InboundCommitOutcome.Committed,
            commit("swap", "old-advanced", InboundStateTarget.Archive(old.id, old.revision, activate = true)))
        assertEquals("old-advanced", active.getRatchetState("conv"))
        assertEquals(listOf("current"), archives().map { it.stateBlob })
    }

    @Test fun failed_replacement_rolls_back_the_archive_too() = withStore {
        active.upsertRatchetState("conv", "old")
        failAt = "after_state"
        assertFailsWith<IllegalStateException> { commit("new", "next", InboundStateTarget.ReplaceActive) }
        assertNoMessage("new")
        assertEquals("old", active.getRatchetState("conv"))
        assertTrue(archives().isEmpty())
    }

    @Test fun archive_commits_reject_wrong_conversation_stale_revision_and_expiry() = withStore {
        active.upsertRatchetState("conv", "old")
        commit("new", "current", InboundStateTarget.ReplaceActive)
        val old = archives().single()
        val target = InboundStateTarget.Archive(old.id, old.revision)
        assertEquals(InboundCommitOutcome.ArchiveUnavailable, commit("wrong", "x", target, "other"))
        assertNoMessage("wrong")
        commit("advance", "old-next", target)
        assertEquals(InboundCommitOutcome.ArchiveUnavailable, commit("stale", "x", target))
        assertNoMessage("stale")
        val updated = archives().single()
        now = old.expiresAtMs
        assertTrue(archives().isEmpty(), "expiry is exclusive at admission")
        assertEquals(InboundCommitOutcome.ArchiveUnavailable,
            commit("expired", "x", InboundStateTarget.Archive(updated.id, updated.revision)))
        assertNoMessage("expired")
        tx.deleteExpiredReceiveArchives(now)
        assertEquals(0L, db.receiveSessionArchiveQueries.countUnexpired("conv", 0).executeAsOne())
    }

    @Test fun the_capacity_bound_refuses_without_eviction_or_partial_commit() = withStore {
        active.upsertRatchetState("conv", "initial")
        repeat(ReceiveSessionArchivePolicy.MAX_PER_CONVERSATION.toInt()) {
            tx.replaceActiveSession("conv", "state-$it", now)
        }
        val before = archives()
        val current = active.getRatchetState("conv")
        assertFailsWith<ReceiveSessionArchiveFull> {
            commit("overflow", "not-admitted", InboundStateTarget.ReplaceActive)
        }
        assertNoMessage("overflow")
        assertEquals(before, archives())
        assertEquals(current, active.getRatchetState("conv"))
        now += ReceiveSessionArchivePolicy.RETENTION_MS
        assertEquals(InboundCommitOutcome.Committed, commit("after-expiry", "next", InboundStateTarget.ReplaceActive))
        assertEquals(1, archives().size)
    }

    @Test fun archive_expiry_is_checked_at_admission_not_when_the_request_was_created() = withStore {
        active.upsertRatchetState("conv", "old")
        commit("new", "current", InboundStateTarget.ReplaceActive)
        val archive = archives().single()
        val requestCreatedAt = now
        now = archive.expiresAtMs
        val outcome = tx.commitInboundMessage(
            conversationId = "conv", envelopeId = "delayed", senderPubKeyHex = "sender", payloadType = "message",
            nowMs = requestCreatedAt,
            message = MessageEntity("delayed", "conv", byteArrayOf(1), "text", false, MessageStatus.DELIVERED, now),
            advancedStateBlob = "must-not-commit",
            stateTarget = InboundStateTarget.Archive(archive.id, archive.revision))
        assertEquals(InboundCommitOutcome.ArchiveUnavailable, outcome)
        assertNoMessage("delayed")
        assertEquals("current", active.getRatchetState("conv"))
        assertEquals("old", tx.listReceiveArchives("conv", requestCreatedAt).single().stateBlob)
    }

    @Test fun repeated_outbound_pending_advance_does_not_archive_the_same_chain() = withStore {
        tx.commitInitiatorPending("conv", "p0", "bootstrap-1", now)
        tx.commitInitiatorPending("conv", "p1", "bootstrap-1", now)
        assertTrue(archives().isEmpty())
        tx.commitInitiatorPending("conv", "p2", "bootstrap-2", now)
        assertEquals(listOf("p1"), archives().map { it.stateBlob })
        assertEquals("p2", pending.get("conv")?.stateBlob)
    }

    @Test fun session_reset_conversation_delete_and_global_wipe_clear_archives() = withStore {
        suspend fun seed(conv: String) {
            active.upsertRatchetState(conv, "old")
            tx.replaceActiveSession(conv, "new", now)
        }
        seed("conv")
        active.deleteRatchetState("conv")
        assertTrue(archives().isEmpty())
        seed("conv")
        SqlDelightConversationRepository(db).deleteConversation("conv")
        assertTrue(archives().isEmpty())
        seed("conv"); seed("other")
        active.deleteAll()
        assertTrue(archives().isEmpty())
        assertTrue(tx.listReceiveArchives("other", now).isEmpty())
    }

    @Test fun migration_adds_an_empty_archive_without_rewriting_the_live_state() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(null, "CREATE TABLE ratchet_state (conversation_id TEXT PRIMARY KEY NOT NULL, state_blob TEXT NOT NULL)", 0)
            driver.execute(null, "INSERT INTO ratchet_state VALUES ('conv', 'unchanged')", 0)
            assertEquals(25L, PhantomDatabase.Schema.version)
            PhantomDatabase.Schema.migrate(driver, 24L, 25L)
            val db = PhantomDatabase(driver)
            assertEquals("unchanged", SqlDelightRatchetStateRepository(db).getRatchetState("conv"))
            assertTrue(SqlDelightSessionTransactionRepository(db).listReceiveArchives("conv", 0).isEmpty())
        } finally { driver.close() }
    }

    @Test fun archiving_a_legacy_plaintext_row_uses_the_configured_authenticated_cipher() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            PhantomDatabase.Schema.create(driver)
            val db = PhantomDatabase(driver)
            val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 7 }, "AES")
            val cipher = object : KeystoreBlobCipher {
                override fun wrap(plaintext: ByteArray): ByteArray {
                    val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
                    val aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    aes.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, nonce))
                    return nonce + aes.doFinal(plaintext)
                }
                override fun unwrap(wrappedBlob: ByteArray): ByteArray {
                    val aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    aes.init(javax.crypto.Cipher.DECRYPT_MODE, key,
                        javax.crypto.spec.GCMParameterSpec(128, wrappedBlob.copyOfRange(0, 12)))
                    return aes.doFinal(wrappedBlob.copyOfRange(12, wrappedBlob.size))
                }
            }
            db.ratchetStateQueries.upsertRatchetState("conv", "legacy-secret-state")
            val tx = SqlDelightSessionTransactionRepository(db, blobCipher = cipher)
            tx.replaceActiveSession("conv", "current-secret-state", 100)
            val archive = tx.listReceiveArchives("conv", 100).single()
            assertEquals("legacy-secret-state", archive.stateBlob)
            val stored = db.receiveSessionArchiveQueries.getArchive(archive.id, "conv").executeAsOne().state_blob
            assertTrue(stored.startsWith("rs1:"))
            assertFalse(stored.contains("legacy-secret-state"))
            // JCA exercises the codec boundary here, not Android Keystore hardware.
            val rejectingCipher = object : KeystoreBlobCipher {
                override fun wrap(plaintext: ByteArray): ByteArray = error("not used")
                override fun unwrap(wrappedBlob: ByteArray): ByteArray = error("wrong key")
            }
            assertFailsWith<IllegalStateException> {
                SqlDelightSessionTransactionRepository(db, rejectingCipher).listReceiveArchives("conv", 100)
            }
        } finally { driver.close() }
    }
}
