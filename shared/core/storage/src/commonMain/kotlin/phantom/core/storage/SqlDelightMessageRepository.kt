package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

class SqlDelightMessageRepository(
    private val db: PhantomDatabase,
) : MessageRepository {

    override suspend fun getMessages(conversationId: String): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            db.messageQueries.getMessages(conversationId).executeAsList().map { it.toEntity() }
        }

    override suspend fun insertMessage(entity: MessageEntity): Unit =
        withContext(Dispatchers.IO) {
            db.messageQueries.insertMessage(
                id = entity.id,
                conversation_id = entity.conversationId,
                ciphertext = entity.ciphertext,
                plaintext_cache = entity.plaintextCache,
                sent = if (entity.sent) 1L else 0L,
                status = entity.status.name.lowercase(),
                created_at = entity.createdAt,
            )
        }

    override suspend fun updateStatus(messageId: String, status: MessageStatus): Unit =
        withContext(Dispatchers.IO) {
            db.messageQueries.updateMessageStatus(
                status = status.name.lowercase(),
                id = messageId,
            )
        }

    override suspend fun deleteMessagesForConversation(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            db.messageQueries.deleteMessagesForConversation(conversationId)
        }

    // ---------------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------------

    private fun phantom.core.storage.db.Message.toEntity() = MessageEntity(
        id = id,
        conversationId = conversation_id,
        ciphertext = ciphertext,
        plaintextCache = plaintext_cache,
        sent = sent != 0L,
        status = statusFromString(status),
        createdAt = created_at,
    )

    private fun statusFromString(raw: String): MessageStatus =
        when (raw.uppercase()) {
            "SENT" -> MessageStatus.SENT
            "RELAYED" -> MessageStatus.RELAYED
            "DELIVERED" -> MessageStatus.DELIVERED
            "READ" -> MessageStatus.READ
            else -> MessageStatus.QUEUED
        }
}
