// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

/**
 * Durable buffer for chunked voice receive (PR-D2b.1, 2026-05-17).
 *
 * The receive path saves every incoming voice chunk here BEFORE sending
 * the `ack-deliver` frame to the relay. Once `countChunks(voiceId) ==
 * total`, the receiver concatenates [findOrderedChunks] into a single
 * blob, inserts one `[AUDIO:<base64>]` message into the message store,
 * and only then calls [deleteByVoiceId] to free the partial state.
 *
 * Crash safety:
 *
 * - Crash between [insertChunk] and `ack-deliver`: the relay redelivers
 *   the same chunk on next connect; the repeat `insertChunk` is an
 *   `INSERT OR REPLACE` no-op because the bytes are identical.
 * - Crash between [insertChunk] and assembly: the next `startReceiving`
 *   runs [findVoicesReadyToAssemble] and finishes assembly for every
 *   voice whose chunk count has already reached `total`.
 * - Crash between assembly and `messageRepository.insertMessage`: the
 *   chunks are NOT deleted. The finalizer retries on next startup.
 *
 * TTL:
 *
 * - 24 hours, intentionally generous so a phone that loses connectivity
 *   overnight on Tele2 can still finish receiving a voice the next
 *   morning. Sweep runs (a) opportunistically on every [insertChunk]
 *   so the table cannot grow unbounded, and (b) at `startReceiving`
 *   so a long-idle restart does not start by carrying state from
 *   before the previous Doze window.
 *
 * See `VoiceChunk.sq` for the schema and full rationale.
 */
interface VoiceChunkRepository {

    /**
     * Metadata for a voice that has all its chunks on disk and is ready
     * for assembly + insert into the message store. Returned by
     * [findVoicesReadyToAssemble] so the finalizer can drive
     * `insertMessage` without re-decoding any ciphertext.
     */
    data class ReadyVoice(
        val voiceId: String,
        val total: Int,
        val conversationId: String,
        val senderPubKeyHex: String,
        val mimeType: String,
        val durationMs: Long,
    )

    /**
     * Diagnostic summary used by the receive path before [deleteOlderThan]
     * to log `VOICE_RX partial_expired` per voice that will be swept,
     * so a log reader can see exactly which voice, with what chunk count,
     * and how old, was dropped.
     */
    data class ExpiredSummary(
        val voiceId: String,
        val total: Int,
        val receivedChunks: Int,
        val oldestUpdatedMs: Long,
    )

    /**
     * Save (or overwrite) a chunk. `INSERT OR REPLACE` semantics:
     * a redelivered chunk with the same (voiceId, idx) is a silent
     * no-op because its bytes are identical. The repository does NOT
     * send `ack-deliver` — the caller does that AFTER this returns
     * successfully so the durable save happens-before the ack.
     */
    suspend fun insertChunk(
        voiceId: String,
        idx: Int,
        total: Int,
        conversationId: String,
        senderPubKeyHex: String,
        mimeType: String,
        durationMs: Long,
        chunkBytes: ByteArray,
        nowMs: Long,
    )

    /** Returns the number of distinct chunk indices stored for [voiceId]. */
    suspend fun countChunks(voiceId: String): Int

    /**
     * Returns chunk bytes for [voiceId] in ascending index order so the
     * receiver can concat straight into the assembled blob. The result
     * is empty when no chunks exist for the id.
     */
    suspend fun findOrderedChunks(voiceId: String): List<ByteArray>

    /**
     * Returns every voice whose chunk count has reached `total`. Used by
     * the finalizer at `startReceiving` to retry assembly + insert for
     * voices interrupted by a previous process death.
     */
    suspend fun findVoicesReadyToAssemble(): List<ReadyVoice>

    /**
     * Returns per-voice summaries for chunks whose `updated_at_ms` is
     * older than [cutoffMs]. Caller logs each summary as
     * `VOICE_RX partial_expired` BEFORE calling [deleteOlderThan] so
     * the sweep is observable in production logs.
     */
    suspend fun findExpiredSummaries(cutoffMs: Long): List<ExpiredSummary>

    /** Bulk delete: drops every chunk row older than [cutoffMs]. */
    suspend fun deleteOlderThan(cutoffMs: Long)

    /**
     * Drops every chunk for [voiceId]. Called after `insertMessage` for
     * the assembled voice has been confirmed durable.
     */
    suspend fun deleteByVoiceId(voiceId: String)

    /** Wipes the whole buffer. Used by reset / instrumentation tests. */
    suspend fun deleteAll()
}
