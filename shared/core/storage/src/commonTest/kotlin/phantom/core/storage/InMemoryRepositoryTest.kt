package phantom.core.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// In-memory fakes — no SQLDelight driver required in commonTest
// ---------------------------------------------------------------------------

private class FakeConversationRepository : ConversationRepository {
    private val store = mutableMapOf<String, ConversationEntity>()

    override suspend fun getAllConversations(): List<ConversationEntity> =
        store.values.sortedByDescending { it.lastMessageAt ?: 0L }

    override suspend fun getConversation(id: String): ConversationEntity? = store[id]

    override suspend fun upsertConversation(entity: ConversationEntity) {
        store[entity.id] = entity
    }

    override suspend fun incrementUnread(conversationId: String) {
        store[conversationId]?.let { store[conversationId] = it.copy(unreadCount = it.unreadCount + 1) }
    }

    override suspend fun resetUnread(conversationId: String) {
        store[conversationId]?.let { store[conversationId] = it.copy(unreadCount = 0) }
    }

    override suspend fun deleteConversation(id: String) {
        store.remove(id)
    }
}

private class FakeMessageRepository : MessageRepository {
    private val store = mutableListOf<MessageEntity>()

    override suspend fun getMessages(conversationId: String): List<MessageEntity> =
        store.filter { it.conversationId == conversationId }.sortedBy { it.createdAt }

    override suspend fun insertMessage(entity: MessageEntity) {
        store.add(entity)
    }

    override suspend fun updateStatus(messageId: String, status: MessageStatus) {
        val index = store.indexOfFirst { it.id == messageId }
        if (index != -1) store[index] = store[index].copy(status = status)
    }

    override suspend fun deleteMessagesForConversation(conversationId: String) {
        store.removeAll { it.conversationId == conversationId }
    }
}

private class FakeRatchetStateRepository : RatchetStateRepository {
    private val store = mutableMapOf<String, String>()

    override suspend fun getRatchetState(conversationId: String): String? = store[conversationId]

    override suspend fun upsertRatchetState(conversationId: String, stateBlob: String) {
        store[conversationId] = stateBlob
    }

    override suspend fun deleteRatchetState(conversationId: String) {
        store.remove(conversationId)
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

class ConversationRepositoryTest {
    private val repo = FakeConversationRepository()

    private fun makeConversation(id: String = "conv-1") = ConversationEntity(
        id = id,
        theirUsername = "alice",
        theirPublicKeyHex = "aabb",
        lastMessagePreview = null,
        lastMessageAt = null,
        unreadCount = 0,
    )

    @Test
    fun upsert_thenGetAll_returnsOneItem() = runTest {
        repo.upsertConversation(makeConversation())
        val all = repo.getAllConversations()
        assertEquals(1, all.size)
        assertEquals("conv-1", all.first().id)
    }

    @Test
    fun upsert_thenGet_returnsItem() = runTest {
        repo.upsertConversation(makeConversation("conv-2"))
        val item = repo.getConversation("conv-2")
        assertNotNull(item)
        assertEquals("alice", item.theirUsername)
    }

    @Test
    fun incrementUnread_updatesCount() = runTest {
        repo.upsertConversation(makeConversation())
        repo.incrementUnread("conv-1")
        val item = repo.getConversation("conv-1")
        assertEquals(1L, item?.unreadCount)
    }

    @Test
    fun resetUnread_setsCountToZero() = runTest {
        repo.upsertConversation(makeConversation())
        repo.incrementUnread("conv-1")
        repo.incrementUnread("conv-1")
        repo.resetUnread("conv-1")
        val item = repo.getConversation("conv-1")
        assertEquals(0L, item?.unreadCount)
    }

    @Test
    fun delete_removesItem() = runTest {
        repo.upsertConversation(makeConversation())
        repo.deleteConversation("conv-1")
        assertNull(repo.getConversation("conv-1"))
        assertTrue(repo.getAllConversations().isEmpty())
    }

    @Test
    fun upsert_overwritesExistingEntry() = runTest {
        repo.upsertConversation(makeConversation())
        repo.upsertConversation(makeConversation().copy(theirUsername = "bob"))
        val all = repo.getAllConversations()
        assertEquals(1, all.size)
        assertEquals("bob", all.first().theirUsername)
    }
}

class MessageRepositoryTest {
    private val repo = FakeMessageRepository()

    private fun makeMessage(
        id: String = "msg-1",
        convId: String = "conv-1",
        status: MessageStatus = MessageStatus.QUEUED,
    ) = MessageEntity(
        id = id,
        conversationId = convId,
        ciphertext = byteArrayOf(0x01, 0x02),
        plaintextCache = null,
        sent = false,
        status = status,
        createdAt = 1_000L,
    )

    @Test
    fun insertMessage_thenGetMessages_returnsIt() = runTest {
        repo.insertMessage(makeMessage())
        val messages = repo.getMessages("conv-1")
        assertEquals(1, messages.size)
        assertEquals("msg-1", messages.first().id)
    }

    @Test
    fun updateStatus_changesStatus() = runTest {
        repo.insertMessage(makeMessage())
        repo.updateStatus("msg-1", MessageStatus.DELIVERED)
        val msg = repo.getMessages("conv-1").first()
        assertEquals(MessageStatus.DELIVERED, msg.status)
    }

    @Test
    fun deleteMessagesForConversation_removesAll() = runTest {
        repo.insertMessage(makeMessage("msg-1"))
        repo.insertMessage(makeMessage("msg-2"))
        repo.deleteMessagesForConversation("conv-1")
        assertTrue(repo.getMessages("conv-1").isEmpty())
    }

    @Test
    fun getMessages_onlyReturnsMatchingConversation() = runTest {
        repo.insertMessage(makeMessage("msg-1", "conv-1"))
        repo.insertMessage(makeMessage("msg-2", "conv-2"))
        val conv1Messages = repo.getMessages("conv-1")
        assertEquals(1, conv1Messages.size)
        assertEquals("msg-1", conv1Messages.first().id)
    }
}

class RatchetStateRepositoryTest {
    private val repo = FakeRatchetStateRepository()

    @Test
    fun upsert_thenGet_returnsBlob() = runTest {
        repo.upsertRatchetState("conv-1", """{"chain":1}""")
        val blob = repo.getRatchetState("conv-1")
        assertEquals("""{"chain":1}""", blob)
    }

    @Test
    fun get_unknownConversation_returnsNull() = runTest {
        assertNull(repo.getRatchetState("unknown"))
    }

    @Test
    fun upsert_overwritesExistingBlob() = runTest {
        repo.upsertRatchetState("conv-1", """{"chain":1}""")
        repo.upsertRatchetState("conv-1", """{"chain":2}""")
        assertEquals("""{"chain":2}""", repo.getRatchetState("conv-1"))
    }

    @Test
    fun delete_removesEntry() = runTest {
        repo.upsertRatchetState("conv-1", """{"chain":1}""")
        repo.deleteRatchetState("conv-1")
        assertNull(repo.getRatchetState("conv-1"))
    }
}
