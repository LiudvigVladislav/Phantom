// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

/**
 * SqlDelight-backed [ControlEventCommitRepository] (N1-F1b R-N1.12).
 *
 * One `db.transaction` spanning the control action and the
 * processed-envelope ledger, following
 * [SqlDelightInboundCommitRepository] and
 * [SqlDelightSessionTransactionRepository].
 *
 * The generated queries are issued directly rather than through the
 * ordinary repositories: each of those wraps its call in
 * `withContext(Dispatchers.IO)`, and a context switch inside
 * `db.transaction { }` would leave the transaction's thread. The column
 * mappings are duplicated as a consequence, so
 * `ControlEventCommitAtomicityTest` compares the rows this writes with
 * the rows the repositories write.
 */
class SqlDelightControlEventCommitRepository(
    private val db: PhantomDatabase,
) : ControlEventCommitRepository {

    override suspend fun commitControlEvent(
        action: ControlEventCommitRepository.Action,
        envelopeId: String,
        conversationId: String,
        senderPubKeyHex: String,
        payloadType: String,
        nowMs: Long,
    ): Unit = withContext(Dispatchers.IO) {
        db.transaction {
            when (action) {
                is ControlEventCommitRepository.Action.DeleteMessage ->
                    db.messageQueries.deleteMessage(action.messageId)

                is ControlEventCommitRepository.Action.EditMessageText ->
                    db.messageQueries.updateMessageText(
                        plaintext_cache = action.text,
                        id = action.messageId,
                    )

                is ControlEventCommitRepository.Action.SetDisappearingTimer ->
                    db.conversationQueries.setDisappearingTimer(
                        secs = action.seconds,
                        id = action.conversationId,
                    )

                is ControlEventCommitRepository.Action.UpsertReaction ->
                    db.reactionQueries.upsertReaction(
                        message_id = action.messageId,
                        sender_key_hex = action.senderKeyHex,
                        emoji = action.emoji,
                        created_at = action.createdAtMs,
                    )

                is ControlEventCommitRepository.Action.DeleteReaction ->
                    db.reactionQueries.deleteReaction(
                        message_id = action.messageId,
                        sender_key_hex = action.senderKeyHex,
                    )

                is ControlEventCommitRepository.Action.PinMessage ->
                    db.messageQueries.pinMessage(
                        pinned = if (action.pinned) 1L else 0L,
                        pinnedByPubkey = action.pinnedByPubkeyHex,
                        id = action.messageId,
                    )

                is ControlEventCommitRepository.Action.MarkRead ->
                    db.messageQueries.updateMessageStatus(
                        status = MessageStatus.READ.name.lowercase(),
                        id = action.messageId,
                    )
            }
            db.processedEnvelopeQueries.markProcessed(
                envelope_id = envelopeId,
                conversation_id = conversationId,
                sender_pubkey_hex = senderPubKeyHex,
                payload_type = payloadType,
                status = ProcessedEnvelopeRepository.Status.PROCESSED.wire,
                created_at_ms = nowMs,
            )
        }
    }
}
