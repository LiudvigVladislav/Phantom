// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

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
                is_verified = if (entity.isVerified) 1L else 0L,
                disappearing_timer_secs = entity.disappearingTimerSecs,
                archived = if (entity.archived) 1L else 0L,
                identity_key_changed_at = entity.identityKeyChangedAt,
                muted_until = entity.mutedUntil,
                pinned = if (entity.pinned) 1L else 0L,
                needs_rehandshake = if (entity.needsRehandshake) 1L else 0L,
                session_suspect = if (entity.sessionSuspect) 1L else 0L,
                session_suspect_set_at_ms = entity.sessionSuspectSetAtMs,
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

    override suspend fun getBlockedConversations(): List<ConversationEntity> =
        withContext(Dispatchers.IO) {
            db.conversationQueries.getBlockedConversations().executeAsList().map { it.toEntity() }
        }

    override suspend fun blockConversation(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.blockConversation(conversationId)
        }

    override suspend fun unblockConversation(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.unblockConversation(conversationId)
        }

    override suspend fun acceptRequest(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.acceptRequest(conversationId)
        }

    override suspend fun deleteConversation(id: String): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.deleteConversation(id)
        }

    override suspend fun setVerified(conversationId: String, verified: Boolean): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.setVerified(
                isVerified = if (verified) 1L else 0L,
                id = conversationId,
            )
        }

    override suspend fun setDisappearingTimer(conversationId: String, secs: Long): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.setDisappearingTimer(secs = secs, id = conversationId)
        }

    override suspend fun getDisappearingTimer(conversationId: String): Long =
        withContext(Dispatchers.IO) {
            db.conversationQueries.getDisappearingTimer(conversationId)
                .executeAsOneOrNull()
                ?: 0L
        }

    override suspend fun archiveConversation(id: String): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.archiveConversation(id)
        }

    override suspend fun unarchiveConversation(id: String): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.unarchiveConversation(id)
        }

    override suspend fun getArchivedConversations(): List<ConversationEntity> =
        withContext(Dispatchers.IO) {
            db.conversationQueries.getArchivedConversations().executeAsList().map { it.toEntity() }
        }

    override suspend fun setIdentityKeyChangedAt(conversationId: String, ts: Long): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.setIdentityKeyChangedAt(ts = ts, id = conversationId)
        }

    override suspend fun clearIdentityKeyChangedAt(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.clearIdentityKeyChangedAt(id = conversationId)
        }

    override suspend fun setMutedUntil(conversationId: String, until: Long?): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.setMutedUntil(until = until, id = conversationId)
        }

    override suspend fun setPinned(conversationId: String, pinned: Boolean): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.setPinned(
                pinned = if (pinned) 1L else 0L,
                id = conversationId,
            )
        }

    override suspend fun setNeedsRehandshake(conversationId: String, needs: Boolean): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.setNeedsRehandshake(
                flag = if (needs) 1L else 0L,
                id = conversationId,
            )
        }

    override suspend fun markAllNeedsRehandshake(): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.markAllNeedsRehandshake()
        }

    override suspend fun setSessionSuspect(conversationId: String, setAtMs: Long): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.setSessionSuspect(
                setAtMs = setAtMs,
                id = conversationId,
            )
        }

    override suspend fun clearSessionSuspect(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            db.conversationQueries.clearSessionSuspect(id = conversationId)
        }

    override suspend fun getSessionSuspectConversations(): List<ConversationEntity> =
        withContext(Dispatchers.IO) {
            db.conversationQueries.getSessionSuspectConversations()
                .executeAsList()
                .map { it.toEntity() }
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
        isVerified = is_verified != 0L,
        disappearingTimerSecs = disappearing_timer_secs,
        archived = archived != 0L,
        identityKeyChangedAt = identity_key_changed_at,
        mutedUntil = muted_until,
        pinned = pinned != 0L,
        needsRehandshake = needs_rehandshake != 0L,
        sessionSuspect = session_suspect != 0L,
        sessionSuspectSetAtMs = session_suspect_set_at_ms,
    )
}
