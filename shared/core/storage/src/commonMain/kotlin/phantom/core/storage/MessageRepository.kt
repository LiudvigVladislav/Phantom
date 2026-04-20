package phantom.core.storage

interface MessageRepository {
    suspend fun getMessages(conversationId: String): List<MessageEntity>
    suspend fun insertMessage(entity: MessageEntity)
    suspend fun updateStatus(messageId: String, status: MessageStatus)
    suspend fun updateMessageText(messageId: String, text: String)
    suspend fun deleteMessage(messageId: String)
    suspend fun deleteMessagesForConversation(conversationId: String)
    suspend fun setExpiresAt(messageId: String, expiresAtMs: Long)
    suspend fun getNextExpiry(): Long?
    suspend fun deleteExpiredMessages()
}

enum class MessageStatus {
    QUEUED,
    SENT,
    RELAYED,
    DELIVERED,
    READ,
}

data class MessageEntity(
    val id: String,
    val conversationId: String,
    val ciphertext: ByteArray,
    val plaintextCache: String?,
    val sent: Boolean,
    val status: MessageStatus,
    val createdAt: Long,
    val expiresAtMs: Long? = null,
) {
    // ByteArray requires explicit equals/hashCode to avoid identity comparison.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEntity) return false
        return id == other.id &&
            conversationId == other.conversationId &&
            ciphertext.contentEquals(other.ciphertext) &&
            plaintextCache == other.plaintextCache &&
            sent == other.sent &&
            status == other.status &&
            createdAt == other.createdAt &&
            expiresAtMs == other.expiresAtMs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + conversationId.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + (plaintextCache?.hashCode() ?: 0)
        result = 31 * result + sent.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (expiresAtMs?.hashCode() ?: 0)
        return result
    }
}
