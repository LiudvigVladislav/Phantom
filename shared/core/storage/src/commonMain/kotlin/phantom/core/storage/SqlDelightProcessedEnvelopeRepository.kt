// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

/**
 * SQLDelight-backed [ProcessedEnvelopeRepository].
 *
 * Hot-path footprint per envelope: one SELECT EXISTS (PK lookup, O(log n))
 * and one INSERT OR IGNORE on the same table. Both run on `Dispatchers.IO`
 * to keep the receive coroutine off the main dispatcher.
 */
class SqlDelightProcessedEnvelopeRepository(
    private val db: PhantomDatabase,
) : ProcessedEnvelopeRepository {

    override suspend fun exists(envelopeId: String): Boolean =
        withContext(Dispatchers.IO) {
            db.processedEnvelopeQueries.exists(envelopeId).executeAsOne()
        }

    override suspend fun markProcessed(
        envelopeId: String,
        conversationId: String,
        senderPubKeyHex: String,
        payloadType: String,
        status: ProcessedEnvelopeRepository.Status,
        nowMs: Long,
    ): Unit = withContext(Dispatchers.IO) {
        db.processedEnvelopeQueries.markProcessed(
            envelope_id = envelopeId,
            conversation_id = conversationId,
            sender_pubkey_hex = senderPubKeyHex,
            payload_type = payloadType,
            status = status.wire,
            created_at_ms = nowMs,
        )
    }

    override suspend fun deleteOlderThan(olderThanMs: Long): Unit =
        withContext(Dispatchers.IO) {
            db.processedEnvelopeQueries.deleteOlderThan(olderThanMs)
        }

    override suspend fun countByStatus(): Map<ProcessedEnvelopeRepository.Status, Long> =
        withContext(Dispatchers.IO) {
            db.processedEnvelopeQueries.countByStatus().executeAsList()
                .mapNotNull { row ->
                    val status = ProcessedEnvelopeRepository.Status.entries
                        .firstOrNull { it.wire == row.status }
                        ?: return@mapNotNull null
                    status to row.cnt
                }
                .toMap()
        }

    override suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        db.processedEnvelopeQueries.deleteAll()
    }
}
