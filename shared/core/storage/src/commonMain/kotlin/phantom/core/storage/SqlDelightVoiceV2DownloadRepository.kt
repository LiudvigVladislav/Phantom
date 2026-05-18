// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

/**
 * SQLDelight-backed [VoiceV2DownloadRepository] (PR-M1w).
 *
 * All writes go to [Dispatchers.IO]. The [insert] call uses
 * INSERT OR IGNORE so relay redelivery of the same manifest
 * before the download completes is a safe no-op.
 */
class SqlDelightVoiceV2DownloadRepository(
    private val db: PhantomDatabase,
) : VoiceV2DownloadRepository {

    override suspend fun insert(task: VoiceV2DownloadRepository.Task): Unit =
        withContext(Dispatchers.IO) {
            db.voiceV2DownloadQueries.insert(
                media_id          = task.mediaId,
                conversation_id   = task.conversationId,
                sender_pubkey_hex = task.senderPubKeyHex,
                manifest_json     = task.manifestJson,
                status            = task.status,
                chunk_count       = task.chunkCount.toLong(),
                last_attempt_at_ms = task.lastAttemptAtMs,
                failure_reason    = task.failureReason,
                created_at_ms     = task.createdAtMs,
            )
        }

    override suspend fun find(mediaId: String): VoiceV2DownloadRepository.Task? =
        withContext(Dispatchers.IO) {
            db.voiceV2DownloadQueries.find(mediaId).executeAsOneOrNull()?.let { row ->
                VoiceV2DownloadRepository.Task(
                    mediaId          = row.media_id,
                    conversationId   = row.conversation_id,
                    senderPubKeyHex  = row.sender_pubkey_hex,
                    manifestJson     = row.manifest_json,
                    status           = row.status,
                    chunkCount       = row.chunk_count.toInt(),
                    lastAttemptAtMs  = row.last_attempt_at_ms,
                    failureReason    = row.failure_reason,
                    createdAtMs      = row.created_at_ms,
                )
            }
        }

    override suspend fun findPending(): List<VoiceV2DownloadRepository.Task> =
        withContext(Dispatchers.IO) {
            db.voiceV2DownloadQueries.findPending().executeAsList().map { row ->
                VoiceV2DownloadRepository.Task(
                    mediaId          = row.media_id,
                    conversationId   = row.conversation_id,
                    senderPubKeyHex  = row.sender_pubkey_hex,
                    manifestJson     = row.manifest_json,
                    status           = row.status,
                    chunkCount       = row.chunk_count.toInt(),
                    lastAttemptAtMs  = row.last_attempt_at_ms,
                    failureReason    = row.failure_reason,
                    createdAtMs      = row.created_at_ms,
                )
            }
        }

    override suspend fun update(
        mediaId: String,
        status: String,
        failureReason: String?,
        nowMs: Long,
    ): Unit = withContext(Dispatchers.IO) {
        db.voiceV2DownloadQueries.update(
            status             = status,
            failure_reason     = failureReason,
            last_attempt_at_ms = nowMs,
            media_id           = mediaId,
        )
    }

    override suspend fun delete(mediaId: String): Unit =
        withContext(Dispatchers.IO) {
            db.voiceV2DownloadQueries.delete(mediaId)
        }

    override suspend fun deleteAll(): Unit =
        withContext(Dispatchers.IO) {
            db.voiceV2DownloadQueries.deleteAll()
        }
}
