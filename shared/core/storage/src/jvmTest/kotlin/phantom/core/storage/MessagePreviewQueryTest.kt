package phantom.core.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import phantom.core.storage.db.PhantomDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessagePreviewQueryTest {
    @Test fun previewQueryIsReadOnlyAndRetainsTimestampTies() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            PhantomDatabase.Schema.create(driver)
            val db = PhantomDatabase(driver)
            val repo = SqlDelightMessageRepository(db)
            val conversations = SqlDelightConversationRepository(db)
            for (id in listOf("chat", "other")) {
                conversations.upsertConversation(ConversationEntity(id, "peer", "abcd", null, null, 0L))
            }
            val original = MessageEntity("voice", "chat", byteArrayOf(1, 2), "[AUDIO:AA==]", true, MessageStatus.SENT, 42L)
            repo.insertMessage(original)
            repo.insertMessage(original.copy(id = "tie", plaintextCache = "Voice message"))
            repo.insertMessage(original.copy(id = "older", createdAt = 41L))
            repo.insertMessage(original.copy(id = "foreign", conversationId = "other"))
            val before = repo.getMessages("chat")
            assertEquals(setOf("voice", "tie"), repo.getLatestMessages("chat").map { it.id }.toSet())
            assertTrue(repo.getLatestMessages("missing").isEmpty())
            assertEquals(before, repo.getMessages("chat"))
            assertEquals(original, repo.getMessageById("voice"))
        } finally {
            driver.close()
        }
    }
}
