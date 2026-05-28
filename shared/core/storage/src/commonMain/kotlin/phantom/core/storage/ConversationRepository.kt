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

    /**
     * Set or clear the per-conversation mute timestamp.
     *
     * @param until epoch ms after which mute auto-expires; pass null to
     *   unmute, or [Long.MAX_VALUE] for "muted forever". Alpha 2 surfaces
     *   only the binary toggle; timed-mute UI lands in Phase 5.
     */
    suspend fun setMutedUntil(conversationId: String, until: Long?)

    /** Toggle conversation pin state — pinned chats sort first. */
    suspend fun setPinned(conversationId: String, pinned: Boolean)

    /** Set or clear the per-conversation needs-rehandshake flag (PR C). */
    suspend fun setNeedsRehandshake(conversationId: String, needs: Boolean)

    /**
     * Mark every conversation as needing a re-handshake. Called once by
     * the Alpha 1 → Alpha 2 migration after wiping ratchet states.
     */
    suspend fun markAllNeedsRehandshake()

    /**
     * PR-CRYPTO-SESSION-REPAIR1 (2026-05-29) — mark a conversation as
     * having a suspect (drifted) local ratchet state, observed via a
     * `Permanent decrypt failure (MAC error)` on the receive path in
     * debug/beta builds where `holdMacFailures = true`.
     *
     * The flag triggers a fresh X3DH 4-DH bootstrap on the NEXT outgoing
     * message in this conversation. It is cleared via [clearSessionSuspect]
     * ONLY after the new ratchet is encrypt+save committed; if bootstrap
     * fails partway, the flag stays so the next send retries.
     *
     * @param setAtMs wall-clock ms when the suspect mark was set. Logged
     *   for diagnostics; persisted on the row for future "give up after
     *   N hours of repair failures" policies.
     */
    suspend fun setSessionSuspect(conversationId: String, setAtMs: Long)

    /** Clear the suspect flag after a successful X3DH bootstrap commit. */
    suspend fun clearSessionSuspect(conversationId: String)

    /** Returns all conversations currently flagged suspect (diagnostic). */
    suspend fun getSessionSuspectConversations(): List<ConversationEntity>
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
    val mutedUntil: Long? = null,
    val pinned: Boolean = false,
    /**
     * Set by the Alpha 1 → Alpha 2 migration (PR C). Indicates that the
     * existing ratchet state for this conversation was wiped during the
     * cryptographic protocol upgrade and a fresh X3DH 4-DH handshake
     * must run before the next outbound message ships. Cleared after the
     * new session is established. Default `false` covers both new
     * conversations and post-migration ones whose handshake has already
     * completed.
     */
    val needsRehandshake: Boolean = false,
    /**
     * PR-CRYPTO-SESSION-REPAIR1 (2026-05-29) — runtime-detected suspect
     * ratchet state. Differs from [needsRehandshake] in trigger
     * (decrypt-failure detection vs migration code) and UX
     * implications (silent, no chat-list indicator). Both flags share
     * the same recovery action (force X3DH on next outgoing); a future
     * cleanup could unify them. Default `false`.
     */
    val sessionSuspect: Boolean = false,
    /**
     * Wall-clock epoch ms when [sessionSuspect] was set. `null` when
     * the flag is false. Logged for diagnostics and reserved for
     * future "give up repair after N hours" policies.
     */
    val sessionSuspectSetAtMs: Long? = null,
)
