// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

/**
 * Durable store for in-flight voice_v2 download tasks (PR-M1w).
 *
 * One row per received VoiceManifestV2. Survives process death so the
 * download orchestrator can resume on the next startReceiving call.
 * Completed tasks are deleted immediately; failed tasks are kept so the
 * UI can surface a failure reason.
 *
 * Status values are plain strings rather than an enum to avoid a
 * cross-module dependency on a typed enum. Use the companion constants
 * [STATUS_PENDING], [STATUS_COMPLETE], [STATUS_FAILED].
 */
interface VoiceV2DownloadRepository {

    data class Task(
        val mediaId: String,
        val conversationId: String,
        val senderPubKeyHex: String,
        val manifestJson: String,
        val status: String,
        val chunkCount: Int,
        val lastAttemptAtMs: Long,
        val failureReason: String?,
        val createdAtMs: Long,
    )

    /** Insert a new task. [INSERT OR IGNORE] — second insert for the same mediaId is a no-op. */
    suspend fun insert(task: Task)

    /** Look up a task by [mediaId]. Returns null if not present. */
    suspend fun find(mediaId: String): Task?

    /** All tasks whose status is [STATUS_PENDING], ordered by [Task.createdAtMs] ascending. */
    suspend fun findPending(): List<Task>

    /** Update status and optional failure reason. Also records [lastAttemptAtMs]. */
    suspend fun update(mediaId: String, status: String, failureReason: String?)

    /** Delete a single task (called after successful download). */
    suspend fun delete(mediaId: String)

    /** Wipe all rows (test/reset helper). */
    suspend fun deleteAll()

    companion object {
        const val STATUS_PENDING  = "pending"
        const val STATUS_COMPLETE = "complete"
        const val STATUS_FAILED   = "failed"
    }
}
