// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

/**
 * SQLDelight-backed [VoiceChunkRepository].
 *
 * Hot-path footprint per chunk: one INSERT OR REPLACE (PK lookup on
 * (voice_id, idx)), one COUNT, and — on the chunk that completes the
 * voice — one ordered SELECT + one DELETE. All on `Dispatchers.IO`.
 */
class SqlDelightVoiceChunkRepository(
    private val db: PhantomDatabase,
) : VoiceChunkRepository {

    override suspend fun insertChunk(
        voiceId: String,
        idx: Int,
        total: Int,
        conversationId: String,
        senderPubKeyHex: String,
        mimeType: String,
        durationMs: Long,
        chunkBytes: ByteArray,
        nowMs: Long,
    ): Unit = withContext(Dispatchers.IO) {
        db.voiceChunkQueries.insertChunk(
            voice_id = voiceId,
            idx = idx.toLong(),
            total = total.toLong(),
            conversation_id = conversationId,
            sender_pubkey_hex = senderPubKeyHex,
            mime_type = mimeType,
            duration_ms = durationMs,
            chunk_bytes = chunkBytes,
            updated_at_ms = nowMs,
        )
    }

    override suspend fun countChunks(voiceId: String): Int =
        withContext(Dispatchers.IO) {
            db.voiceChunkQueries.countChunks(voiceId).executeAsOne().toInt()
        }

    override suspend fun findOrderedChunks(voiceId: String): List<ByteArray> =
        withContext(Dispatchers.IO) {
            db.voiceChunkQueries.findOrderedChunks(voiceId).executeAsList()
                .map { it.chunk_bytes }
        }

    override suspend fun findVoicesReadyToAssemble(): List<VoiceChunkRepository.ReadyVoice> =
        withContext(Dispatchers.IO) {
            db.voiceChunkQueries.findVoicesReadyToAssemble().executeAsList().map { row ->
                VoiceChunkRepository.ReadyVoice(
                    voiceId = row.voice_id,
                    total = row.total.toInt(),
                    conversationId = row.conversation_id,
                    senderPubKeyHex = row.sender_pubkey_hex,
                    mimeType = row.mime_type,
                    durationMs = row.duration_ms,
                )
            }
        }

    override suspend fun findExpiredSummaries(cutoffMs: Long): List<VoiceChunkRepository.ExpiredSummary> =
        withContext(Dispatchers.IO) {
            db.voiceChunkQueries.findExpiredSummaries(cutoffMs).executeAsList().map { row ->
                VoiceChunkRepository.ExpiredSummary(
                    voiceId = row.voice_id,
                    total = row.total.toInt(),
                    receivedChunks = row.received.toInt(),
                    oldestUpdatedMs = row.oldest_ms ?: 0L,
                )
            }
        }

    override suspend fun deleteOlderThan(cutoffMs: Long): Unit =
        withContext(Dispatchers.IO) {
            db.voiceChunkQueries.deleteOlderThan(cutoffMs)
        }

    override suspend fun deleteByVoiceId(voiceId: String): Unit =
        withContext(Dispatchers.IO) {
            db.voiceChunkQueries.deleteByVoiceId(voiceId)
        }

    override suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        db.voiceChunkQueries.deleteAll()
    }
}
