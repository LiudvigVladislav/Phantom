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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalConversationDeletionTest {
    private lateinit var driver: SqlDriver
    private lateinit var db: PhantomDatabase
    private lateinit var conversations: SqlDelightConversationRepository
    private lateinit var messages: SqlDelightMessageRepository
    private lateinit var deletion: SqlDelightLocalConversationDeletionRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhantomDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        db = PhantomDatabase(driver)
        conversations = SqlDelightConversationRepository(db)
        messages = SqlDelightMessageRepository(db)
        deletion = SqlDelightLocalConversationDeletionRepository(db)
    }

    @AfterTest
    fun tearDown() = driver.close()

    private suspend fun conversation(id: String) {
        conversations.upsertConversation(
            ConversationEntity(id, id, "aa", null, null, 0, trustTier = TrustTier.TRUSTED)
        )
    }

    private suspend fun message(
        id: String,
        conversationId: String = "a",
        status: MessageStatus = MessageStatus.DELIVERED,
        sent: Boolean = false,
        text: String = "hello",
    ) {
        messages.insertMessage(
            MessageEntity(id, conversationId, ByteArray(0), text, sent, status, 1_000, null)
        )
    }

    @Test
    fun deletes_only_local_history_and_keeps_session_and_dedupe_state() = runTest {
        conversation("a")
        conversation("b")
        message("voice-a", text = "[AUDIO_LOCAL:/private/voice/voice-a.ogg]")
        message("msg-b", conversationId = "b")
        db.ratchetStateQueries.upsertRatchetState("a", "active-state")
        db.pendingRatchetStateQueries.upsert("a", "candidate", 1_000, null, null, 0)
        db.receiveSessionArchiveQueries.insertArchive("a", "archive", 9_000)
        db.processedEnvelopeQueries.markProcessed("env-a", "a", "aa", "message", "processed", 1_000)
        db.voiceV2DownloadQueries.insert("failed-media", "a", "aa", "{}", "failed", 1, 1_000, "expired", 1_000)

        val files = deletion.deleteIfIdle("a")

        assertEquals(listOf(LocalVoiceFile("voice-a", "/private/voice/voice-a.ogg")), files)
        assertNull(conversations.getConversation("a"))
        assertTrue(messages.getMessages("a").isEmpty())
        assertEquals(1, messages.getMessages("b").size)
        assertTrue(db.voiceV2DownloadQueries.listByConversation("a").executeAsList().isEmpty())
        assertEquals("active-state", db.ratchetStateQueries.getRatchetState("a").executeAsOne())
        assertEquals("candidate", db.pendingRatchetStateQueries.getByConversationId("a").executeAsOne().state_blob)
        assertEquals(1L, db.receiveSessionArchiveQueries.countUnexpired("a", 0).executeAsOne())
        assertTrue(db.processedEnvelopeQueries.exists("env-a").executeAsOne())
    }

    @Test
    fun clearing_history_keeps_contact_but_hides_empty_chat_until_new_message() = runTest {
        conversation("a")
        conversation("b")
        message("old")
        message("other", conversationId = "b")
        db.ratchetStateQueries.upsertRatchetState("a", "active-state")
        db.processedEnvelopeQueries.markProcessed("old-envelope", "a", "aa", "message", "processed", 1_000)

        deletion.clearHistoryIfIdle("a")

        assertEquals(TrustTier.TRUSTED, conversations.getConversation("a")?.trustTier)
        assertTrue(conversations.getConversation("a")?.chatHidden == true)
        assertTrue(conversations.getActiveConversations().any { it.id == "a" })
        assertFalse(conversations.getVisibleChats().any { it.id == "a" })
        assertTrue(messages.getMessages("a").isEmpty())
        assertEquals(listOf("other"), messages.getMessages("b").map { it.id })
        assertEquals("active-state", db.ratchetStateQueries.getRatchetState("a").executeAsOne())
        assertTrue(db.processedEnvelopeQueries.exists("old-envelope").executeAsOne())

        message("new")
        assertTrue(conversations.getVisibleChats().any { it.id == "a" })
        assertEquals(TrustTier.TRUSTED, conversations.getConversation("a")?.trustTier)
        val updated = conversations.getConversation("a")!!.copy(lastMessageAt = 2_000, lastMessagePreview = "new")
        conversations.upsertConversation(updated)
        assertTrue(conversations.getVisibleChats().any { it.id == "a" })
    }

    @Test
    fun clearing_history_refuses_unsettled_outbox_without_touching_contact() = runTest {
        conversation("a")
        message("waiting", status = MessageStatus.WAITING_FOR_RECIPIENT_BUNDLE, sent = true)
        assertEquals(LocalConversationBusyException.Reason.OUTBOX,
            assertFailsWith<LocalConversationBusyException> { deletion.clearHistoryIfIdle("a") }.reason)
        assertFalse(conversations.getConversation("a")?.chatHidden ?: true)
        assertEquals(1, messages.getMessages("a").size)
    }

    @Test
    fun inbound_after_history_only_deletion_reopens_trusted_chat_without_request() = runTest {
        conversation("a")
        message("old")
        deletion.clearHistoryIfIdle("a")

        SqlDelightInboundCommitRepository(db).commitInboundMessage(
            message = MessageEntity("fresh", "a", byteArrayOf(1), "hello", false, MessageStatus.DELIVERED, 2_000, null),
            envelopeId = "fresh-envelope", conversationId = "a", senderPubKeyHex = "aa",
            payloadType = "message", nowMs = 2_000,
        )

        assertEquals(TrustTier.TRUSTED, conversations.getConversation("a")?.trustTier)
        assertTrue(conversations.getVisibleChats().any { it.id == "a" })
        assertTrue(conversations.getMessageRequests().none { it.id == "a" })
        assertEquals(listOf("fresh"), messages.getMessages("a").map { it.id })
    }

    @Test
    fun migration_keeps_existing_chats_visible() {
        val legacy = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            legacy.execute(null, "CREATE TABLE conversation (id TEXT PRIMARY KEY)", 0)
            legacy.execute(null, "CREATE TABLE message (id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL)", 0)
            legacy.execute(null, "INSERT INTO conversation (id) VALUES ('contact')", 0)
            PhantomDatabase.Schema.migrate(legacy, 25L, 26L)
            val hidden = legacy.executeQuery(null, "SELECT chat_hidden FROM conversation WHERE id = 'contact'", { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
            }, 0).value
            assertEquals(0L, hidden)
        } finally {
            legacy.close()
        }
    }

    @Test
    fun refuses_every_unsent_status_without_partial_deletion() = runTest {
        for (status in listOf(
            MessageStatus.QUEUED,
            MessageStatus.WAITING_FOR_RECIPIENT_BUNDLE,
            MessageStatus.UPLOADING,
        )) {
            val id = status.name
            conversation(id)
            message("message-$id", id, status, sent = true)
            val failure = assertFailsWith<LocalConversationBusyException> {
                deletion.deleteIfIdle(id)
            }
            assertEquals(LocalConversationBusyException.Reason.OUTBOX, failure.reason)
            assertEquals(1, messages.getMessages(id).size)
            assertTrue(conversations.getConversation(id) != null)
        }
    }

    @Test
    fun refuses_held_and_partial_inbound_work_without_ack_or_purge() = runTest {
        conversation("a")
        message("visible")
        db.decryptFailedEnvelopeQueries.insert("held", "a", "aa", "mac", 1_000, 0, "{}")
        assertEquals(LocalConversationBusyException.Reason.HELD_ENVELOPE,
            assertFailsWith<LocalConversationBusyException> { deletion.deleteIfIdle("a") }.reason)
        db.decryptFailedEnvelopeQueries.deleteByEnvelopeId("held")
        db.voiceChunkQueries.insertChunk("voice", 0, 2, "a", "aa", "audio/ogg", 1_000, byteArrayOf(1), 1_000)
        assertEquals(LocalConversationBusyException.Reason.VOICE_CHUNKS,
            assertFailsWith<LocalConversationBusyException> { deletion.deleteIfIdle("a") }.reason)
        assertEquals(1, messages.getMessages("a").size)
        assertTrue(conversations.getConversation("a") != null)
    }

    @Test
    fun refuses_pending_download_but_can_remove_failed_task() = runTest {
        conversation("a")
        db.voiceV2DownloadQueries.insert("media", "a", "aa", "{}", "pending", 1, 0, null, 1_000)
        assertEquals(LocalConversationBusyException.Reason.VOICE_DOWNLOAD,
            assertFailsWith<LocalConversationBusyException> { deletion.deleteIfIdle("a") }.reason)
        assertTrue(conversations.getConversation("a") != null)
        db.voiceV2DownloadQueries.update("failed", "expired", 1_001, "media")
        deletion.deleteIfIdle("a")
        assertTrue(db.voiceV2DownloadQueries.listByConversation("a").executeAsList().isEmpty())
    }

    @Test
    fun voice_references_are_scoped_to_actual_audio_local_rows() = runTest {
        conversation("a")
        message("voice", text = "[AUDIO_LOCAL:/private/voice/voice.ogg]")
        message("text", text = "[AUDIO_LOCAL:unterminated")
        assertEquals(setOf("/private/voice/voice.ogg"), deletion.referencedVoiceFiles())
    }

    @Test
    fun deletes_messages_and_reactions_even_when_sqlite_foreign_keys_are_off() = runTest {
        conversation("a")
        conversation("b")
        message("a-message")
        message("b-message", conversationId = "b")
        db.reactionQueries.upsertReaction("a-message", "peer", "ok", 1_000)
        db.reactionQueries.upsertReaction("b-message", "peer", "ok", 1_000)
        driver.execute(null, "PRAGMA foreign_keys = OFF;", 0)

        deletion.deleteIfIdle("a")

        assertTrue(messages.getMessages("a").isEmpty())
        assertTrue(db.reactionQueries.getReactionsForMessage("a-message").executeAsList().isEmpty())
        assertEquals(1, messages.getMessages("b").size)
        assertEquals(1, db.reactionQueries.getReactionsForMessage("b-message").executeAsList().size)
    }

    @Test
    fun own_new_message_can_recreate_deleted_chat() = runTest {
        conversation("a")
        message("old")
        deletion.deleteIfIdle("a")
        assertNull(conversations.getConversation("a"))

        conversation("a")
        message("new")

        assertEquals(listOf("new"), messages.getMessages("a").map { it.id })
        assertTrue(conversations.getConversation("a")?.blocked == false)
    }

    @Test
    fun fresh_inbound_commit_recreates_request_without_forgetting_old_envelopes() = runTest {
        conversation("a")
        message("old")
        db.processedEnvelopeQueries.markProcessed("old-envelope", "a", "aa", "message", "processed", 1_000)
        deletion.deleteIfIdle("a")

        SqlDelightInboundCommitRepository(db).commitInboundMessage(
            message = MessageEntity("new", "a", byteArrayOf(1), "fresh", false, MessageStatus.DELIVERED, 2_000, null),
            envelopeId = "new-envelope",
            conversationId = "a",
            senderPubKeyHex = "aa",
            payloadType = "message",
            nowMs = 2_000,
        )

        assertEquals(listOf("new"), messages.getMessages("a").map { it.id })
        assertEquals(TrustTier.REQUEST, conversations.getConversation("a")?.trustTier)
        assertTrue(conversations.getMessageRequests().any { it.id == "a" })
        assertTrue(conversations.getConversation("a")?.blocked == false)
        assertTrue(db.processedEnvelopeQueries.exists("old-envelope").executeAsOne())
        assertTrue(db.processedEnvelopeQueries.exists("new-envelope").executeAsOne())
    }

    @Test
    fun primary_inbound_transaction_recreates_chat_and_rolls_back_placeholder_on_failure() = runTest {
        conversation("a")
        deletion.deleteIfIdle("a")
        val inbound = MessageEntity("fresh", "a", byteArrayOf(1), "hello", false, MessageStatus.DELIVERED, 2_000, null)
        val broken = SqlDelightSessionTransactionRepository(db, transactionProbe = { point ->
            if (point == "after_message") error("injected failure")
        })
        assertFailsWith<IllegalStateException> {
            broken.commitInboundMessage(
                conversationId = "a", envelopeId = "fresh-envelope", senderPubKeyHex = "aa",
                payloadType = "message", nowMs = 2_000, message = inbound,
                advancedStateBlob = null, promotePending = false, expectedOpkKeyIdHex = null,
                stateTarget = InboundStateTarget.Active,
            )
        }
        assertNull(conversations.getConversation("a"))
        assertTrue(messages.getMessages("a").isEmpty())
        assertFalse(db.processedEnvelopeQueries.exists("fresh-envelope").executeAsOne())

        val outcome = SqlDelightSessionTransactionRepository(db).commitInboundMessage(
            conversationId = "a", envelopeId = "fresh-envelope", senderPubKeyHex = "aa",
            payloadType = "message", nowMs = 2_000, message = inbound,
            advancedStateBlob = null, promotePending = false, expectedOpkKeyIdHex = null,
            stateTarget = InboundStateTarget.Active,
        )
        assertEquals(InboundCommitOutcome.Committed, outcome)
        assertEquals(listOf("fresh"), messages.getMessages("a").map { it.id })
        assertEquals(TrustTier.REQUEST, conversations.getConversation("a")?.trustTier)
    }
}
