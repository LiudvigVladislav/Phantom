// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    suspend fun getMessages(conversationId: String): List<MessageEntity>

    /**
     * PR-UI-CHAT-THREAD-STATE1 (2026-05-25) — reactive message stream for a
     * conversation. Cold Flow: subscribes on collect; emits once immediately
     * with the current snapshot, then re-emits on every database write to the
     * `messages` table affecting this conversation (insert / update / delete
     * via SQLDelight's `Query<T>.asFlow()` extension).
     *
     * Purpose: replaces the pull-style `var messages by remember + suspend
     * reloadMessages()` pattern in `ChatScreen` which caused the chat-open
     * UX bugs documented in `docs/tracks/chat-bottom-anchor.md` and the
     * agent-memory entry `feedback_chatscreen_pull_style_root_cause.md`.
     * With `observeMessages(...).collectAsState(...)`, ChatScreen has the
     * loaded list on the first Compose frame — no empty initial state,
     * no anchor-to-empty-list race, no manual `reloadMessages()` after
     * every DB write.
     *
     * The existing `suspend fun getMessages` stays for one-shot pulls
     * (preview generation, archive screens, tests, anywhere a Flow
     * subscription is overkill).
     */
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    suspend fun getMessageById(id: String): MessageEntity?
    suspend fun insertMessage(entity: MessageEntity)
    suspend fun updateStatus(messageId: String, status: MessageStatus)
    suspend fun updateMessageText(messageId: String, text: String)
    suspend fun deleteMessage(messageId: String)
    suspend fun deleteMessagesForConversation(conversationId: String)
    suspend fun setExpiresAt(messageId: String, expiresAtMs: Long)
    suspend fun getNextExpiry(): Long?
    suspend fun deleteExpiredMessages()
    suspend fun pinMessage(messageId: String, pinned: Boolean, pinnedByPubkey: String?)
    suspend fun getPinnedMessages(conversationId: String): List<MessageEntity>
    suspend fun saveMessage(id: String)
    suspend fun unsaveMessage(id: String)
    suspend fun getSavedMessages(): List<MessageEntity>
}

enum class MessageStatus {
    QUEUED,
    /** Sender: media chunks are being uploaded to the relay (PR-M1w). */
    UPLOADING,
    /** Receiver: media chunks are being downloaded from the relay (PR-M1w). */
    DOWNLOADING,
    /**
     * Encryption attempted but the recipient has no published prekey
     * bundle on the relay yet (404 from PreKeyApi.fetchBundle). The
     * message stays in the outbox; the foreground retry hook re-tries
     * `sendMessage` periodically. The chat UI surfaces this as a
     * "Waiting for {contact} to update PHANTOM" indicator.
     *
     * PR C-followup-3.
     */
    WAITING_FOR_RECIPIENT_BUNDLE,
    SENT,
    RELAYED,
    DELIVERED,
    READ,
    /**
     * Send permanently failed for a non-retryable reason (encrypt
     * threw on something other than missing peer bundle). Surfaced
     * in the chat UI with a retry-by-tap indicator. PR C-followup-3.
     */
    FAILED,
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
    val pinned: Boolean = false,
    val saved: Boolean = false,
    val pinnedByPubkey: String? = null,
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
            expiresAtMs == other.expiresAtMs &&
            pinned == other.pinned &&
            saved == other.saved &&
            pinnedByPubkey == other.pinnedByPubkey
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
        result = 31 * result + pinned.hashCode()
        result = 31 * result + saved.hashCode()
        result = 31 * result + (pinnedByPubkey?.hashCode() ?: 0)
        return result
    }
}
