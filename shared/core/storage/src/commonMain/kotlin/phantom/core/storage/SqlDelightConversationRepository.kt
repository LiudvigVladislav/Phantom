package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

class SqlDelightConversationRepository(
    private val db: PhantomDatabase,
) : ConversationRepository {

    override suspend fun getAllConversations(): List<ConversationEntity> =
        withContext(Dispatchers.IO) {
            db.conversationQueries.getAllConversations().executeAsList().map { it.toEntity() }
        }

    override suspend fun getConversation(id: String): ConversationEntity? =
        withContext(Dispatchers.IO) {
            db.conversationQueries.getConversation(id).executeAsOneOrNull()?.toEntity()
        }

    override suspend fun upsertConversation(entity: ConversationEntity): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.upsertConversation(
                id = entity.id,
                their_username = entity.theirUsername,
                their_public_key_hex = entity.theirPublicKeyHex,
                last_message_preview = entity.lastMessagePreview,
                last_message_at = entity.lastMessageAt,
                unread_count = entity.unreadCount,
            )
        }

    override suspend fun incrementUnread(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.incrementUnread(conversationId)
        }

    override suspend fun resetUnread(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.resetUnread(conversationId)
        }

    override suspend fun deleteConversation(id: String): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.deleteConversation(id)
        }

    // ---------------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------------

    private fun phantom.core.storage.db.Conversation.toEntity() = ConversationEntity(
        id = id,
        theirUsername = their_username,
        theirPublicKeyHex = their_public_key_hex,
        lastMessagePreview = last_message_preview,
        lastMessageAt = last_message_at,
        unreadCount = unread_count,
    )
}
