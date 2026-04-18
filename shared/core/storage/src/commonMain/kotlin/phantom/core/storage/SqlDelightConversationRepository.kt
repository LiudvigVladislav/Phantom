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

    override suspend fun getActiveConversations(): List<ConversationEntity> =
        withContext(Dispatchers.IO) {
            db.conversationQueries.getActiveConversations().executeAsList().map { it.toEntity() }
        }

    override suspend fun getMessageRequests(): List<ConversationEntity> =
        withContext(Dispatchers.IO) {
            db.conversationQueries.getMessageRequests().executeAsList().map { it.toEntity() }
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
                trust_tier = entity.trustTier.name,
                blocked = if (entity.blocked) 1L else 0L,
                notes = entity.notes,
            )
        }

    override suspend fun updateNotes(conversationId: String, notes: String?): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.updateNotes(notes = notes, id = conversationId)
        }

    override suspend fun incrementUnread(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.incrementUnread(conversationId)
        }

    override suspend fun resetUnread(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.resetUnread(conversationId)
        }

    override suspend fun blockConversation(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.blockConversation(conversationId)
        }

    override suspend fun acceptRequest(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.acceptRequest(conversationId)
        }

    override suspend fun deleteConversation(id: String): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.deleteConversation(id)
        }

    // ---------------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------------

    private fun Conversation.toEntity() = ConversationEntity(
        id = id,
        theirUsername = their_username,
        theirPublicKeyHex = their_public_key_hex,
        lastMessagePreview = last_message_preview,
        lastMessageAt = last_message_at,
        unreadCount = unread_count,
        trustTier = runCatching { TrustTier.valueOf(trust_tier) }.getOrDefault(TrustTier.TRUSTED),
        blocked = blocked != 0L,
        notes = notes,
    )
}
