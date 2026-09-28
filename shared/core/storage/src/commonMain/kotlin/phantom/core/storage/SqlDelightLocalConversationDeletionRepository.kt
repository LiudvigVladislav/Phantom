// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

class SqlDelightLocalConversationDeletionRepository(
    private val db: PhantomDatabase,
) : LocalConversationDeletionRepository {
    override suspend fun referencedVoiceFiles(): Set<String> = withContext(Dispatchers.IO) {
        db.messageQueries.getLocalVoiceReferences().executeAsList().mapNotNull { row ->
            row.plaintext_cache?.takeIf { it.startsWith("[AUDIO_LOCAL:") && it.endsWith("]") }
                ?.removePrefix("[AUDIO_LOCAL:")?.dropLast(1)
        }.toSet()
    }

    override suspend fun deleteIfIdle(conversationId: String): List<LocalVoiceFile> =
        eraseIfIdle(conversationId, removeContact = true)

    override suspend fun clearHistoryIfIdle(conversationId: String): List<LocalVoiceFile> =
        eraseIfIdle(conversationId, removeContact = false)

    private suspend fun eraseIfIdle(conversationId: String, removeContact: Boolean): List<LocalVoiceFile> =
        withContext(Dispatchers.IO) {
            require(conversationId.isNotBlank())
            db.transactionWithResult {
                val conversation = db.conversationQueries.getConversation(conversationId).executeAsOneOrNull()
                    ?: throw NoSuchElementException("Conversation not found")
                if (!removeContact && conversation.trust_tier != TrustTier.TRUSTED.name) {
                    throw IllegalStateException("Only a trusted contact can be retained")
                }
                val messages = db.messageQueries.getMessages(conversationId).executeAsList()
                if (messages.any {
                        it.sent != 0L && it.status.lowercase() in setOf(
                            "queued", "uploading", "waiting_for_recipient_bundle"
                        )
                    }) {
                    throw LocalConversationBusyException(LocalConversationBusyException.Reason.OUTBOX)
                }
                if (db.decryptFailedEnvelopeQueries.listByConversation(conversationId).executeAsList().isNotEmpty()) {
                    throw LocalConversationBusyException(LocalConversationBusyException.Reason.HELD_ENVELOPE)
                }
                if (db.voiceV2DownloadQueries.listByConversation(conversationId).executeAsList()
                        .any { it.status == VoiceV2DownloadRepository.STATUS_PENDING }) {
                    throw LocalConversationBusyException(LocalConversationBusyException.Reason.VOICE_DOWNLOAD)
                }
                if (db.voiceChunkQueries.countByConversation(conversationId).executeAsOne() != 0L) {
                    throw LocalConversationBusyException(LocalConversationBusyException.Reason.VOICE_CHUNKS)
                }

                val files = messages.mapNotNull { row ->
                    row.plaintext_cache
                        ?.takeIf { it.startsWith("[AUDIO_LOCAL:") && it.endsWith("]") }
                        ?.let { LocalVoiceFile(row.id, it.removePrefix("[AUDIO_LOCAL:").dropLast(1)) }
                }
                db.voiceV2DownloadQueries.deleteByConversation(conversationId)
                db.reactionQueries.deleteForConversation(conversationId)
                db.messageQueries.deleteMessagesForConversation(conversationId)
                if (removeContact) db.conversationQueries.deleteConversation(conversationId)
                else db.conversationQueries.hideClearedChat(conversationId)
                files
            }
        }
}
