// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

data class LocalVoiceFile(val messageId: String, val path: String)

class LocalConversationBusyException(val reason: Reason) : IllegalStateException(reason.name) {
    enum class Reason { OUTBOX, HELD_ENVELOPE, VOICE_DOWNLOAD, VOICE_CHUNKS, VOICE_UPLOAD }
}

/** Deletes only local presentation data. Ratchet and delivery ledgers survive. */
interface LocalConversationDeletionRepository {
    /** The caller must quiesce the messaging service before invoking this transaction. */
    suspend fun deleteIfIdle(conversationId: String): List<LocalVoiceFile>

    /** Clear history but retain the trusted contact, hiding its empty chat until a new message. */
    suspend fun clearHistoryIfIdle(conversationId: String): List<LocalVoiceFile> =
        throw UnsupportedOperationException("local history clearing unavailable")

    /** Used before receive jobs start to finish file cleanup interrupted by process death. */
    suspend fun referencedVoiceFiles(): Set<String>
}
