package phantom.core.storage

interface ReactionRepository {
    suspend fun upsertReaction(messageId: String, senderKeyHex: String, emoji: String, createdAt: Long)
    suspend fun deleteReaction(messageId: String, senderKeyHex: String)
    suspend fun getReactions(messageId: String): List<ReactionEntry>
}

data class ReactionEntry(val emoji: String, val senderKeyHex: String)
