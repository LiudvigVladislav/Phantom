package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

class SqlDelightReactionRepository(
    private val db: PhantomDatabase,
) : ReactionRepository {

    override suspend fun upsertReaction(
        messageId: String,
        senderKeyHex: String,
        emoji: String,
        createdAt: Long,
    ): Unit = withContext(Dispatchers.IO) {
        db.reactionQueries.upsertReaction(
            message_id = messageId,
            sender_key_hex = senderKeyHex,
            emoji = emoji,
            created_at = createdAt,
        )
    }

    override suspend fun deleteReaction(
        messageId: String,
        senderKeyHex: String,
    ): Unit = withContext(Dispatchers.IO) {
        db.reactionQueries.deleteReaction(
            message_id = messageId,
            sender_key_hex = senderKeyHex,
        )
    }

    override suspend fun getReactions(messageId: String): List<ReactionEntry> =
        withContext(Dispatchers.IO) {
            db.reactionQueries.getReactionsForMessage(messageId)
                .executeAsList()
                .map { row -> ReactionEntry(emoji = row.emoji, senderKeyHex = row.sender_key_hex) }
        }
}
