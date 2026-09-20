// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

class SqlDelightMessageRepository(
    private val db: PhantomDatabase,
) : MessageRepository {

    override suspend fun getMessages(conversationId: String): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            db.messageQueries.getMessages(conversationId).executeAsList().map { it.toEntity() }
        }

    /**
     * PR-UI-CHAT-THREAD-STATE1 — reactive message stream for one conversation.
     *
     * Backed by SQLDelight's `Query<T>.asFlow()` extension
     * (`app.cash.sqldelight:coroutines-extensions`, already in
     * `shared/core/storage/build.gradle.kts`). Behaviour:
     *  - On `collect`, subscribes the underlying `Query` to the database's
     *    write notifier and emits the current snapshot immediately.
     *  - On every successful write to the `messages` table touched by this
     *    query (any `INSERT`, `UPDATE`, `DELETE` on `messages`), re-runs the
     *    query and emits the new list.
     *  - `mapToList(Dispatchers.IO)` moves the query execution off the
     *    main thread; the downstream `.map { it.toEntity() }` runs on the
     *    collector's context (Compose `collectAsState` runs in main scope,
     *    so the entity mapping happens on the main thread per row — fast
     *    enough for the chat sizes we ship).
     *
     * Naming: matches the existing `getMessages` query name so a future
     * `MessageQueries.observe(...)` direct call site could swap in without
     * a repository change.
     */
    override fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        db.messageQueries.getMessages(conversationId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toEntity() } }

    override suspend fun getMessageById(id: String): MessageEntity? =
        withContext(Dispatchers.IO) {
            db.messageQueries.getMessageById(id).executeAsOneOrNull()?.toEntity()
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

    override suspend fun replaceMessage(entity: MessageEntity): Unit =
        withContext(Dispatchers.IO) {
            // residual N1 Revision 4 — the row is UPDATED, never removed.
            //
            // Revision 3 did this as a delete-then-insert inside one
            // transaction. Atomicity kept the message row from being
            // observably absent, but it did not protect what hangs off the
            // row: `reaction.message_id` references `message(id) ON DELETE
            // CASCADE`, so wherever foreign keys are enforced the delete took
            // every reaction with it and the insert did not bring them back.
            // Independently of foreign keys, `insertMessage` does not carry
            // `pinned`, `saved` or `pinned_by_pubkey`, so the re-insert reset
            // all three to their defaults. Both losses were silent.
            //
            // The update is still issued inside a transaction together with
            // its verification, so a row that vanished between the outbox's
            // decision and this write cannot be papered over: the caller
            // throws before `encryptUnderLock` commits the new ratchet state,
            // and the outbox keeps the old envelope for the next sweep.
            //
            // The queries are issued directly inside `db.transaction { }`, as
            // the atomic commit repositories do, because a context switch
            // inside it would leave the transaction's thread.
            db.transaction {
                val before = db.messageQueries.getMessageById(entity.id).executeAsOneOrNull()
                    ?: throw NoSuchElementException(
                        "replaceMessage: no row with id=${entity.id}; refusing to create one, " +
                            "because the caller is replacing an envelope on an existing message",
                    )
                db.messageQueries.updateMessageEnvelope(
                    ciphertext = entity.ciphertext,
                    plaintextCache = entity.plaintextCache,
                    sent = if (entity.sent) 1L else 0L,
                    status = entity.status.name.lowercase(),
                    expiresAtMs = entity.expiresAtMs,
                    id = entity.id,
                )
                val after = db.messageQueries.getMessageById(entity.id).executeAsOneOrNull()
                    ?: throw IllegalStateException("replaceMessage: row id=${entity.id} disappeared during update")
                if (!after.ciphertext.contentEquals(entity.ciphertext) ||
                    after.status != entity.status.name.lowercase()
                ) {
                    throw IllegalStateException(
                        "replaceMessage: update did not take effect for id=${entity.id}",
                    )
                }
                // The identity columns must be exactly what they were: this
                // operation replaces an envelope, not a message.
                if (after.id != before.id ||
                    after.conversation_id != before.conversation_id ||
                    after.created_at != before.created_at
                ) {
                    throw IllegalStateException(
                        "replaceMessage: identity columns changed for id=${entity.id}",
                    )
                }
            }
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

    override suspend fun pinMessage(
        messageId: String,
        pinned: Boolean,
        pinnedByPubkey: String?,
    ): Unit = withContext(Dispatchers.IO) {
        db.messageQueries.pinMessage(
            pinned = if (pinned) 1L else 0L,
            pinnedByPubkey = pinnedByPubkey,
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
        pinnedByPubkey = pinned_by_pubkey,
    )

    private fun statusFromString(raw: String): MessageStatus =
        when (raw.uppercase()) {
            "SENT" -> MessageStatus.SENT
            "RELAYED" -> MessageStatus.RELAYED
            "DELIVERED" -> MessageStatus.DELIVERED
            "READ" -> MessageStatus.READ
            "WAITING_FOR_RECIPIENT_BUNDLE" -> MessageStatus.WAITING_FOR_RECIPIENT_BUNDLE
            "FAILED" -> MessageStatus.FAILED
            else -> MessageStatus.QUEUED
        }
}
