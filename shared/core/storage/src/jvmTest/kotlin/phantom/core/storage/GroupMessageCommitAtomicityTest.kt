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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The group receive path must never acknowledge an outer envelope while only
 * some of its SenderKey step, decoded effect and processed ledger are durable.
 */
class GroupMessageCommitAtomicityTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: PhantomDatabase
    private lateinit var commit: GroupMessageCommitRepository
    private lateinit var senderKeys: SenderKeyRepository
    private lateinit var chunks: VoiceChunkRepository
    private lateinit var messages: MessageRepository
    private lateinit var groups: GroupRepository
    private lateinit var conversations: ConversationRepository
    private lateinit var ledger: ProcessedEnvelopeRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhantomDatabase.Schema.create(driver)
        db = PhantomDatabase(driver)
        commit = SqlDelightGroupMessageCommitRepository(db)
        senderKeys = SqlDelightSenderKeyRepository(db)
        chunks = SqlDelightVoiceChunkRepository(db)
        messages = SqlDelightMessageRepository(db)
        groups = SqlDelightGroupRepository(db)
        conversations = SqlDelightConversationRepository(db)
        ledger = SqlDelightProcessedEnvelopeRepository(db)
    }

    @AfterTest
    fun tearDown() = driver.close()

    @Test
    fun audio_chunk_sender_key_and_ledger_commit_together() = runTest {
        commit.commit(
            senderKey = advancedKey(),
            effect = audioEffect(),
            envelope = envelope("e-audio"),
        )

        assertEquals(8L, senderKeys.get(GROUP, SENDER)?.iteration)
        assertEquals(1, chunks.countChunks(VOICE))
        assertContentEquals(CHUNK, chunks.findOrderedChunks(VOICE).single())
        assertTrue(ledger.exists("e-audio"))
    }

    @Test
    fun text_message_sender_key_preview_and_ledger_commit_together() = runTest {
        seedGroupAndConversation()
        val message = incomingMessage("m1")
        commit.commit(
            senderKey = advancedKey(),
            effect = GroupMessageCommitRepository.Effect.Message(message, "hello", 2_000L),
            envelope = envelope("e-text"),
        )

        assertEquals(8L, senderKeys.get(GROUP, SENDER)?.iteration)
        assertEquals("hello", messages.getMessageById("m1")?.plaintextCache)
        assertEquals("hello", groups.getGroup(GROUP)?.lastMessagePreview)
        assertTrue(ledger.exists("e-text"))
    }

    @Test
    fun rejected_ledger_rolls_back_sender_key_and_audio_chunk() = runTest {
        rejectLedger()

        assertTrue(
            runCatching {
                commit.commit(advancedKey(), audioEffect(), envelope("e-rejected"))
            }.isFailure,
        )
        assertNull(senderKeys.get(GROUP, SENDER))
        assertEquals(0, chunks.countChunks(VOICE))
        assertFalse(ledger.exists("e-rejected"))
    }

    @Test
    fun rejected_ledger_rolls_back_sender_key_message_and_preview() = runTest {
        seedGroupAndConversation()
        rejectLedger()

        assertTrue(
            runCatching {
                commit.commit(
                    advancedKey(),
                    GroupMessageCommitRepository.Effect.Message(
                        incomingMessage("m-rejected"),
                        "must roll back",
                        2_000L,
                    ),
                    envelope("e-message-rejected"),
                )
            }.isFailure,
        )
        assertNull(senderKeys.get(GROUP, SENDER))
        assertNull(messages.getMessageById("m-rejected"))
        assertNull(groups.getGroup(GROUP)?.lastMessagePreview)
        assertFalse(ledger.exists("e-message-rejected"))
    }

    private suspend fun seedGroupAndConversation() {
        conversations.upsertConversation(
            ConversationEntity(
                id = GROUP,
                theirUsername = "group",
                theirPublicKeyHex = SENDER,
                lastMessagePreview = null,
                lastMessageAt = null,
                unreadCount = 0L,
                trustTier = TrustTier.TRUSTED,
            ),
        )
        groups.insertGroup(
            GroupEntity(
                id = GROUP,
                name = "Test group",
                myRole = "member",
                isChannel = false,
                createdAt = 1_000L,
            ),
        )
    }

    private fun incomingMessage(id: String) = MessageEntity(
        id = id,
        conversationId = GROUP,
        ciphertext = "ciphertext".encodeToByteArray(),
        plaintextCache = "hello",
        sent = false,
        status = MessageStatus.DELIVERED,
        createdAt = 2_000L,
        expiresAtMs = null,
    )

    private fun advancedKey() = SenderKeyEntity(
        groupId = GROUP,
        memberPubkeyHex = SENDER,
        chainKeyHex = "ab".repeat(32),
        iteration = 8L,
    )

    private fun audioEffect() = GroupMessageCommitRepository.Effect.AudioChunk(
        voiceId = VOICE,
        idx = 0,
        total = 2,
        groupId = GROUP,
        senderPubKeyHex = SENDER,
        mimeType = "audio/mp4",
        durationMs = 3_000L,
        chunkBytes = CHUNK,
        nowMs = 2_000L,
    )

    private fun envelope(id: String) = GroupEnvelopeMetadata(
        envelopeId = id,
        conversationId = GROUP,
        senderPubKeyHex = SENDER,
        payloadType = "group_message",
        nowMs = 2_000L,
    )

    private fun rejectLedger() = driver.execute(
        identifier = null,
        sql = """
            CREATE TRIGGER reject_group_ledger
            BEFORE INSERT ON processed_envelopes
            BEGIN
                SELECT RAISE(ABORT, 'ledger refused by test trigger');
            END;
        """.trimIndent(),
        parameters = 0,
    )

    private companion object {
        const val GROUP = "group-1"
        const val VOICE = "group-audio:group-1:sender:voice-1"
        val SENDER = "aa".repeat(32)
        val CHUNK = byteArrayOf(1, 2, 3, 4)
    }
}
