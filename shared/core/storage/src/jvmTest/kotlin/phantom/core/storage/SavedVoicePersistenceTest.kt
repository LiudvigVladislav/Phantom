package phantom.core.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import phantom.core.storage.db.PhantomDatabase
import java.nio.file.Files
import kotlin.test.*

class SavedVoicePersistenceTest {
    @Test fun localVoiceSurvivesReopenAndRetryDoesNotDuplicate() = runTest {
        val path = Files.createTempFile("saved-voice-", ".db")
        try {
            val row = MessageEntity("saved-id", "saved_messages_local", byteArrayOf(), "[AUDIO:AQID]", true, MessageStatus.DELIVERED, 42)
            JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
                PhantomDatabase.Schema.create(driver)
                val db = PhantomDatabase(driver)
                SqlDelightConversationRepository(db).upsertConversation(
                    ConversationEntity("saved_messages_local", "Notes", "", null, null, 0L),
                )
                val repo = SqlDelightMessageRepository(db)
                repo.insertMessage(row)
                repo.insertMessage(row.copy(createdAt = 99L))
                assertEquals(listOf(row), repo.getMessages("saved_messages_local"))
            }
            JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
                val repo = SqlDelightMessageRepository(PhantomDatabase(driver))
                assertEquals(row, repo.getMessageById("saved-id"))
                assertTrue(repo.getMessages("saved_messages_local").none { it.status == MessageStatus.QUEUED })
            }
        } finally { Files.deleteIfExists(path) }
    }
}
