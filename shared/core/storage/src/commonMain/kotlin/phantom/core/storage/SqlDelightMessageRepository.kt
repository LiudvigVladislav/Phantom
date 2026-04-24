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
                expires_at_ms = entity.expiresAtMs,
            )
        }

    override suspend fun updateStatus(messageId: String, status: MessageStatus): Unit =
        withContext(Dispatchers.IO) {
            db.messageQueries.updateMessageStatus(
                status = status.name.lowercase(),
                id = messageId,
            )
        }

    override suspend fun updateMessageText(messageId: String, text: String): Unit =
        withContext(Dispatchers.IO) {
            db.messageQueries.updateMessageText(plaintext_cache = text, id = messageId)
        }

    override suspend fun deleteMessage(messageId: String): Unit =
        withContext(Dispatchers.IO) {
            db.messageQueries.deleteMessage(messageId)
        }

    override suspend fun deleteMessagesForConversation(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            db.messageQueries.deleteMessagesForConversation(conversationId)
        }

    override suspend fun setExpiresAt(messageId: String, expiresAtMs: Long): Unit =
        withContext(Dispatchers.IO) {
            db.messageQueries.setExpiresAt(expiresAtMs = expiresAtMs, id = messageId)
        }

    override suspend fun getNextExpiry(): Long? =
        withContext(Dispatchers.IO) {
            val nowMs = System.currentTimeMillis()
            db.messageQueries.getNextExpiry(nowMs).executeAsOneOrNull()
                ?.next_expiry
        }

    override suspend fun deleteExpiredMessages(): Unit =
        withContext(Dispatchers.IO) {
            val nowMs = System.currentTimeMillis()
            db.messageQueries.deleteExpiredMessages(nowMs)
        }

    override suspend fun pinMessage(messageId: String, pinned: Boolean): Unit =
        withContext(Dispatchers.IO) {
            db.messageQueries.pinMessage(
                pinned = if (pinned) 1L else 0L,
                id = messageId,
            )
        }

    override suspend fun getPinnedMessages(conversationId: String): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            db.messageQueries.getPinnedMessages(conversationId).executeAsList().map { it.toEntity() }
        }

    override suspend fun saveMessage(id: String): Unit =
        withContext(Dispatchers.IO) {
            db.messageQueries.saveMessage(id)
        }

    override suspend fun unsaveMessage(id: String): Unit =
        withContext(Dispatchers.IO) {
            db.messageQueries.unsaveMessage(id)
        }

    override suspend fun getSavedMessages(): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            db.messageQueries.getSavedMessages().executeAsList().map { it.toEntity() }
        }

    // ---------------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------------

    private fun Message.toEntity() = MessageEntity(
        id = id,
        conversationId = conversation_id,
        ciphertext = ciphertext,
        plaintextCache = plaintext_cache,
        sent = sent != 0L,
        status = statusFromString(status),
        createdAt = created_at,
        expiresAtMs = expires_at_ms,
        pinned = pinned != 0L,
        saved = saved != 0L,
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
