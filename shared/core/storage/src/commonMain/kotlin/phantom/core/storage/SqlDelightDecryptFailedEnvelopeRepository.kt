// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

/**
 * SQLDelight-backed [DecryptFailedEnvelopeRepository].
 *
 * Mirrors the [SqlDelightProcessedEnvelopeRepository] pattern: thin
 * delegation to generated queries, all I/O dispatched on
 * [Dispatchers.IO] to keep the receive coroutine off the main
 * dispatcher.
 *
 * Hot-path footprint per held envelope: one INSERT OR IGNORE on the
 * receive MAC path, one SELECT-by-conversation after session repair
 * (typically zero matches; small N), one DELETE per successful
 * replay. The table is bounded by the 24h TTL and the architectural
 * fact that a healthy session never produces MAC errors.
 */
class SqlDelightDecryptFailedEnvelopeRepository(
    private val db: PhantomDatabase,
) : DecryptFailedEnvelopeRepository {

    override suspend fun insert(
        envelopeId: String,
        conversationId: String,
        senderPubKeyHex: String,
        errorType: String,
        receivedAtMs: Long,
        x3dhInitPresent: Boolean,
        wireFrameJson: String,
    ): Unit = withContext(Dispatchers.IO) {
        db.decryptFailedEnvelopeQueries.insert(
            envelope_id = envelopeId,
            conversation_id = conversationId,
            sender_pubkey_hex = senderPubKeyHex,
            error_type = errorType,
            received_at_ms = receivedAtMs,
            x3dh_init_present = if (x3dhInitPresent) 1L else 0L,
            wire_frame_json = wireFrameJson,
        )
    }

    override suspend fun listByConversation(
        conversationId: String,
    ): List<DecryptFailedEnvelopeRepository.Entry> = withContext(Dispatchers.IO) {
        db.decryptFailedEnvelopeQueries.listByConversation(conversationId)
            .executeAsList()
            .map { row ->
                DecryptFailedEnvelopeRepository.Entry(
                    envelopeId = row.envelope_id,
                    conversationId = row.conversation_id,
                    senderPubKeyHex = row.sender_pubkey_hex,
                    errorType = row.error_type,
                    receivedAtMs = row.received_at_ms,
                    x3dhInitPresent = row.x3dh_init_present != 0L,
                    wireFrameJson = row.wire_frame_json,
                    replayAttemptCount = row.replay_attempt_count,
                    lastReplayAtMs = row.last_replay_at_ms,
                )
            }
    }

    override suspend fun deleteByEnvelopeId(envelopeId: String): Unit =
        withContext(Dispatchers.IO) {
            db.decryptFailedEnvelopeQueries.deleteByEnvelopeId(envelopeId)
        }

    override suspend fun recordReplayAttempt(envelopeId: String, nowMs: Long): Unit =
        withContext(Dispatchers.IO) {
            db.decryptFailedEnvelopeQueries.recordReplayAttempt(
                last_replay_at_ms = nowMs,
                envelope_id = envelopeId,
            )
        }

    override suspend fun deleteOlderThan(olderThanMs: Long): Unit =
        withContext(Dispatchers.IO) {
            db.decryptFailedEnvelopeQueries.deleteOlderThan(olderThanMs)
        }

    override suspend fun count(): Long = withContext(Dispatchers.IO) {
        db.decryptFailedEnvelopeQueries.count().executeAsOne()
    }

    override suspend fun countByConversation(): Map<String, Long> = withContext(Dispatchers.IO) {
        db.decryptFailedEnvelopeQueries.countByConversation().executeAsList()
            .associate { row -> row.conversation_id to row.cnt }
    }

    override suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        db.decryptFailedEnvelopeQueries.deleteAll()
    }
}
