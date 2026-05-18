// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for [VoiceV2DownloadRepository] (PR-M1w).
 *
 * Exercised via [FakeVoiceV2DownloadRepository] — in-memory fake that
 * mirrors the SQL semantics (INSERT OR IGNORE, status filter, etc.)
 * without requiring a real SQLDelight driver in commonTest.
 */
class VoiceV2DownloadRepositoryContractTest {

    private fun newRepo(): VoiceV2DownloadRepository = FakeVoiceV2DownloadRepository()

    private fun task(
        mediaId: String = "media-1",
        status: String = VoiceV2DownloadRepository.STATUS_PENDING,
        failureReason: String? = null,
        chunkCount: Int = 3,
    ) = VoiceV2DownloadRepository.Task(
        mediaId          = mediaId,
        conversationId   = "conv-A",
        senderPubKeyHex  = "deadbeef",
        manifestJson     = """{"mediaId":"$mediaId"}""",
        status           = status,
        chunkCount       = chunkCount,
        lastAttemptAtMs  = 0L,
        failureReason    = failureReason,
        createdAtMs      = 1_000L,
    )

    @Test
    fun insert_and_find_roundTrip() = runTest {
        val repo = newRepo()
        repo.insert(task("m1"))
        val found = repo.find("m1")
        assertNotNull(found)
        assertEquals("m1", found.mediaId)
        assertEquals("conv-A", found.conversationId)
        assertEquals(3, found.chunkCount)
        assertEquals(VoiceV2DownloadRepository.STATUS_PENDING, found.status)
        assertNull(found.failureReason)
    }

    @Test
    fun find_returnsNull_whenAbsent() = runTest {
        val repo = newRepo()
        assertNull(repo.find("not-there"))
    }

    @Test
    fun insert_isIdempotent_secondInsertSameIdIsNoOp() = runTest {
        val repo = newRepo()
        repo.insert(task("m1", chunkCount = 3))
        // Second insert with different chunkCount should be ignored (INSERT OR IGNORE)
        repo.insert(task("m1", chunkCount = 99))
        val found = repo.find("m1")
        assertNotNull(found)
        assertEquals(3, found.chunkCount) // first value preserved
    }

    @Test
    fun findPending_returnsOnlyPendingRows() = runTest {
        val repo = newRepo()
        repo.insert(task("pending-1", status = VoiceV2DownloadRepository.STATUS_PENDING))
        repo.insert(task("done-1",    status = VoiceV2DownloadRepository.STATUS_COMPLETE))
        repo.insert(task("fail-1",    status = VoiceV2DownloadRepository.STATUS_FAILED))
        repo.insert(task("pending-2", status = VoiceV2DownloadRepository.STATUS_PENDING))

        val pending = repo.findPending()
        assertEquals(2, pending.size)
        assertTrue(pending.all { it.status == VoiceV2DownloadRepository.STATUS_PENDING })
    }

    @Test
    fun update_toFailed_setsStatusAndReason() = runTest {
        val repo = newRepo()
        repo.insert(task("m1"))
        repo.update("m1", VoiceV2DownloadRepository.STATUS_FAILED, "sha256_mismatch", nowMs = 12345L)

        val updated = repo.find("m1")
        assertNotNull(updated)
        assertEquals(VoiceV2DownloadRepository.STATUS_FAILED, updated.status)
        assertEquals("sha256_mismatch", updated.failureReason)
    }

    @Test
    fun delete_removesRow() = runTest {
        val repo = newRepo()
        repo.insert(task("m1"))
        repo.delete("m1")
        assertNull(repo.find("m1"))
    }

    @Test
    fun delete_nonExistent_isNoop() = runTest {
        val repo = newRepo()
        repo.delete("never-existed") // must not throw
    }

    @Test
    fun deleteAll_wipesEverything() = runTest {
        val repo = newRepo()
        repo.insert(task("m1"))
        repo.insert(task("m2"))
        repo.deleteAll()
        assertNull(repo.find("m1"))
        assertNull(repo.find("m2"))
        assertTrue(repo.findPending().isEmpty())
    }
}

// ── In-memory fake ─────────────────────────────────────────────────────────────

/**
 * In-memory fake exposed at module level so messaging-module tests can
 * import it. Mirrors [SqlDelightVoiceV2DownloadRepository] semantics:
 * - INSERT OR IGNORE: second insert for the same mediaId is a no-op.
 * - findPending filters by status == 'pending'.
 * - update replaces status + failureReason.
 */
class FakeVoiceV2DownloadRepository : VoiceV2DownloadRepository {

    private val store = mutableMapOf<String, VoiceV2DownloadRepository.Task>()

    override suspend fun insert(task: VoiceV2DownloadRepository.Task) {
        // INSERT OR IGNORE: do nothing if key already present.
        if (store.containsKey(task.mediaId)) return
        store[task.mediaId] = task
    }

    override suspend fun find(mediaId: String): VoiceV2DownloadRepository.Task? =
        store[mediaId]

    override suspend fun findPending(): List<VoiceV2DownloadRepository.Task> =
        store.values
            .filter { it.status == VoiceV2DownloadRepository.STATUS_PENDING }
            .sortedBy { it.createdAtMs }

    override suspend fun update(mediaId: String, status: String, failureReason: String?, nowMs: Long) {
        val existing = store[mediaId] ?: return
        store[mediaId] = existing.copy(
            status          = status,
            failureReason   = failureReason,
            lastAttemptAtMs = nowMs,
        )
    }

    override suspend fun delete(mediaId: String) {
        store.remove(mediaId)
    }

    override suspend fun deleteAll() {
        store.clear()
    }
}
