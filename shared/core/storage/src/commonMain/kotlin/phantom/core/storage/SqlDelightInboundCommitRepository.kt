// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

/**
 * SqlDelight-backed [InboundCommitRepository] (N1-F1 R-N1.8).
 *
 * One `db.transaction` spanning `message` and `processed_envelopes`,
 * following the same pattern as [SqlDelightSessionTransactionRepository].
 * Both tables belong to [PhantomDatabase], so no cross-database
 * two-phase problem exists here — the transaction is the whole fix.
 *
 * The statements are issued through the generated queries directly
 * rather than through [MessageRepository] / [ProcessedEnvelopeRepository],
 * because those wrap each call in its own `withContext(Dispatchers.IO)`
 * and would each run outside this transaction. The column mapping is
 * kept identical to [SqlDelightMessageRepository.insertMessage] and
 * [SqlDelightProcessedEnvelopeRepository.markProcessed]; a divergence
 * there would be a silent schema bug, so
 * `InboundCommitAtomicityTest` asserts the row this writes is
 * indistinguishable from the one the message repository writes.
 */
class SqlDelightInboundCommitRepository(
    private val db: PhantomDatabase,
) : InboundCommitRepository {

    override suspend fun commitInboundMessage(
        message: MessageEntity,
        envelopeId: String,
        conversationId: String,
        senderPubKeyHex: String,
        payloadType: String,
        nowMs: Long,
    ): Unit = withContext(Dispatchers.IO) {
        db.transaction {
            db.messageQueries.insertMessage(
                id = message.id,
                conversation_id = message.conversationId,
                ciphertext = message.ciphertext,
                plaintext_cache = message.plaintextCache,
                sent = if (message.sent) 1L else 0L,
                status = message.status.name.lowercase(),
                created_at = message.createdAt,
                expires_at_ms = message.expiresAtMs,
            )
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
