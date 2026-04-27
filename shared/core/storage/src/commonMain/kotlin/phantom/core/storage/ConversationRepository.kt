// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

enum class TrustTier { TRUSTED, REQUEST, BLOCKED }

interface ConversationRepository {
    suspend fun getAllConversations(): List<ConversationEntity>
    suspend fun getActiveConversations(): List<ConversationEntity>
    suspend fun getMessageRequests(): List<ConversationEntity>
    suspend fun getConversation(id: String): ConversationEntity?
    suspend fun upsertConversation(entity: ConversationEntity)
    suspend fun incrementUnread(conversationId: String)
    suspend fun resetUnread(conversationId: String)
    suspend fun updateNotes(conversationId: String, notes: String?)
    suspend fun getBlockedConversations(): List<ConversationEntity>
    suspend fun blockConversation(conversationId: String)
    suspend fun unblockConversation(conversationId: String)
    suspend fun acceptRequest(conversationId: String)
    suspend fun deleteConversation(id: String)
    suspend fun setVerified(conversationId: String, verified: Boolean)
    suspend fun setDisappearingTimer(conversationId: String, secs: Long)
    suspend fun getDisappearingTimer(conversationId: String): Long
    suspend fun archiveConversation(id: String)
    suspend fun unarchiveConversation(id: String)
    suspend fun getArchivedConversations(): List<ConversationEntity>
    suspend fun setIdentityKeyChangedAt(conversationId: String, ts: Long)
    suspend fun clearIdentityKeyChangedAt(conversationId: String)
}

data class ConversationEntity(
    val id: String,
    val theirUsername: String,
    val theirPublicKeyHex: String,
    val lastMessagePreview: String?,
    val lastMessageAt: Long?,
    val unreadCount: Long,
    val trustTier: TrustTier = TrustTier.TRUSTED,
    val blocked: Boolean = false,
    val notes: String? = null,
    val isVerified: Boolean = false,
    val disappearingTimerSecs: Long = 0L,
    val archived: Boolean = false,
    val identityKeyChangedAt: Long? = null,
)
