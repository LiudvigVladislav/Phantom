// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for [VoiceChunkRepository] (PR-D2b.1, 2026-05-17).
 *
 * Exercised via [FakeVoiceChunkRepository] — the same in-memory fake the
 * messaging-module tests use to assert durable assembly behaviour without
 * standing up a real SQLDelight driver in commonTest. The production
 * [SqlDelightVoiceChunkRepository] mirrors this contract one-to-one
 * (the SQL queries do nothing the fake doesn't).
 */
class VoiceChunkRepositoryContractTest {

    private fun newRepo(): VoiceChunkRepository = FakeVoiceChunkRepository()

    private suspend fun VoiceChunkRepository.insert(
        voiceId: String,
        idx: Int,
        total: Int,
        bytes: ByteArray,
        nowMs: Long = 1_000L,
        conversationId: String = "conv-A",
        senderPubKeyHex: String = "abc",
        mimeType: String = "audio/m4a",
        durationMs: Long = 3_000L,
    ) {
        insertChunk(
            voiceId = voiceId,
            idx = idx,
            total = total,
            conversationId = conversationId,
            senderPubKeyHex = senderPubKeyHex,
            mimeType = mimeType,
            durationMs = durationMs,
            chunkBytes = bytes,
            nowMs = nowMs,
        )
    }

    @Test
    fun count_returnsZero_whenEmpty() = runTest {
        val repo = newRepo()
        assertEquals(0, repo.countChunks("never-seen"))
    }

    @Test
    fun insertChunk_thenCount_andOrderedReturnsByIdx() = runTest {
        val repo = newRepo()
        repo.insert("v1", idx = 2, total = 3, bytes = byteArrayOf(0x22))
        repo.insert("v1", idx = 0, total = 3, bytes = byteArrayOf(0x00))
        repo.insert("v1", idx = 1, total = 3, bytes = byteArrayOf(0x11))

        assertEquals(3, repo.countChunks("v1"))
        val ordered = repo.findOrderedChunks("v1")
        assertContentEquals(byteArrayOf(0x00), ordered[0])
        assertContentEquals(byteArrayOf(0x11), ordered[1])
        assertContentEquals(byteArrayOf(0x22), ordered[2])
    }

    @Test
    fun insertChunk_isIdempotent_acrossDuplicateIdx() = runTest {
        // PRIMARY KEY (voice_id, idx) + INSERT OR REPLACE: a redelivery
        // of the same chunk (same id + idx + identical bytes) must not
        // inflate countChunks above `total`. Identical bytes mean the
        // overwrite is semantically a no-op.
        val repo = newRepo()
        repo.insert("v1", idx = 0, total = 2, bytes = byteArrayOf(0xA))
        repo.insert("v1", idx = 0, total = 2, bytes = byteArrayOf(0xA))
        repo.insert("v1", idx = 0, total = 2, bytes = byteArrayOf(0xA))
        assertEquals(1, repo.countChunks("v1"))
    }

    @Test
    fun findVoicesReadyToAssemble_returnsOnlyComplete() = runTest {
        val repo = newRepo()
        // v1 is complete (2/2)
        repo.insert("v1", idx = 0, total = 2, bytes = byteArrayOf(1))
        repo.insert("v1", idx = 1, total = 2, bytes = byteArrayOf(2))
        // v2 is partial (1/3)
        repo.insert("v2", idx = 0, total = 3, bytes = byteArrayOf(9))
        // v3 is complete (1/1)
        repo.insert("v3", idx = 0, total = 1, bytes = byteArrayOf(7))

        val ready = repo.findVoicesReadyToAssemble().map { it.voiceId }.toSet()
        assertEquals(setOf("v1", "v3"), ready)
    }

    @Test
    fun findExpiredSummaries_groupsByVoice_andReportsOldestStamp() = runTest {
        val repo = newRepo()
        // v1: chunks aged 100ms / 200ms (oldest = 100ms)
        repo.insert("v1", idx = 0, total = 2, bytes = byteArrayOf(1), nowMs = 100L)
        repo.insert("v1", idx = 1, total = 2, bytes = byteArrayOf(2), nowMs = 200L)
        // v2: single chunk aged 500ms (newer than cutoff)
        repo.insert("v2", idx = 0, total = 1, bytes = byteArrayOf(9), nowMs = 500L)

        val expired = repo.findExpiredSummaries(cutoffMs = 300L)
        assertEquals(1, expired.size)
        val summary = expired.single()
        assertEquals("v1", summary.voiceId)
        assertEquals(2, summary.receivedChunks)
        assertEquals(2, summary.total)
        assertEquals(100L, summary.oldestUpdatedMs)
    }

    @Test
    fun deleteOlderThan_dropsOnlyExpiredVoices() = runTest {
        val repo = newRepo()
        repo.insert("old", idx = 0, total = 1, bytes = byteArrayOf(1), nowMs = 100L)
        repo.insert("fresh", idx = 0, total = 1, bytes = byteArrayOf(2), nowMs = 5_000L)

        repo.deleteOlderThan(cutoffMs = 1_000L)

        assertEquals(0, repo.countChunks("old"))
        assertEquals(1, repo.countChunks("fresh"))
    }

    @Test
    fun deleteOlderThan_dropsEntireVoice_whenAnyChunkExpired() = runTest {
        // PR-D2b.1 review round 2 regression test.
        //
        // Pre-fix `deleteOlderThan` was a row-level
        // `DELETE WHERE updated_at_ms < cutoff`. That left a voice in a
        // permanently-broken mixed-age state: some indices old → swept,
        // some indices fresh → still there, `countChunks` never reaches
        // `total`. The post-fix query drops the WHOLE voice (every idx
        // row, fresh or stale) the moment any single chunk crosses the
        // cutoff — matching `findExpiredSummaries`'s
        // `HAVING MIN(updated_at_ms) < cutoff` semantic.
        val repo = newRepo()
        // v1 has chunks at 100ms (old) and 5_000ms (fresh).
        repo.insert("v1", idx = 0, total = 2, bytes = byteArrayOf(0xAA.toByte()), nowMs = 100L)
        repo.insert("v1", idx = 1, total = 2, bytes = byteArrayOf(0xBB.toByte()), nowMs = 5_000L)
        // v2 is fully fresh.
        repo.insert("v2", idx = 0, total = 1, bytes = byteArrayOf(0xCC.toByte()), nowMs = 5_000L)

        repo.deleteOlderThan(cutoffMs = 1_000L)

        // v1 is entirely gone — including the fresh idx=1 — because at
        // least one of its chunks crossed the cutoff.
        assertEquals(0, repo.countChunks("v1"))
        // v2 untouched.
        assertEquals(1, repo.countChunks("v2"))
    }

    @Test
    fun deleteByVoiceId_dropsOnlyThatVoice() = runTest {
        val repo = newRepo()
        repo.insert("v1", idx = 0, total = 1, bytes = byteArrayOf(1))
        repo.insert("v2", idx = 0, total = 1, bytes = byteArrayOf(2))
        repo.insert("v2", idx = 1, total = 2, bytes = byteArrayOf(3))

        repo.deleteByVoiceId("v2")

        assertEquals(1, repo.countChunks("v1"))
        assertEquals(0, repo.countChunks("v2"))
    }

    @Test
    fun deleteAll_wipesBuffer() = runTest {
        val repo = newRepo()
        repo.insert("v1", idx = 0, total = 1, bytes = byteArrayOf(1))
        repo.insert("v2", idx = 0, total = 1, bytes = byteArrayOf(2))
        repo.deleteAll()
        assertEquals(0, repo.countChunks("v1"))
        assertEquals(0, repo.countChunks("v2"))
        assertTrue(repo.findVoicesReadyToAssemble().isEmpty())
    }

    @Test
    fun readyVoice_carriesAllMetadataNeededForFinalizer() = runTest {
        val repo = newRepo()
        repo.insertChunk(
            voiceId = "v",
            idx = 0,
            total = 1,
            conversationId = "conv-finalize",
            senderPubKeyHex = "deadbeef",
            mimeType = "audio/ogg",
            durationMs = 7_500L,
            chunkBytes = byteArrayOf(0xFF.toByte()),
            nowMs = 1L,
        )
        val ready = repo.findVoicesReadyToAssemble().single()
        assertEquals("v", ready.voiceId)
        assertEquals(1, ready.total)
        assertEquals("conv-finalize", ready.conversationId)
        assertEquals("deadbeef", ready.senderPubKeyHex)
        assertEquals("audio/ogg", ready.mimeType)
        assertEquals(7_500L, ready.durationMs)
    }
}

/**
 * In-memory fake exposed at module level so messaging-module tests can
 * import it. Mirrors [SqlDelightVoiceChunkRepository] semantics:
 * - PRIMARY KEY (voice_id, idx) via Map<Pair<String, Int>, Row>
 * - INSERT OR REPLACE on insertChunk
 * - TTL sweep by `updated_at_ms` cutoff
 * - findVoicesReadyToAssemble groups by voice_id and filters COUNT(idx) == total
 */
class FakeVoiceChunkRepository : VoiceChunkRepository {
    private data class Row(
        val voiceId: String,
        val idx: Int,
        val total: Int,
        val conversationId: String,
        val senderPubKeyHex: String,
        val mimeType: String,
        val durationMs: Long,
        val chunkBytes: ByteArray,
        val updatedAtMs: Long,
    )

    private val store = mutableMapOf<Pair<String, Int>, Row>()

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
    ) {
        // INSERT OR REPLACE: overwrite key.
        store[voiceId to idx] = Row(
            voiceId, idx, total, conversationId, senderPubKeyHex,
            mimeType, durationMs, chunkBytes, nowMs,
        )
    }

    override suspend fun countChunks(voiceId: String): Int =
        store.values.count { it.voiceId == voiceId }

    override suspend fun findOrderedChunks(voiceId: String): List<ByteArray> =
        store.values.filter { it.voiceId == voiceId }
            .sortedBy { it.idx }
            .map { it.chunkBytes }

    override suspend fun findVoicesReadyToAssemble(): List<VoiceChunkRepository.ReadyVoice> {
        val byVoice = store.values.groupBy { it.voiceId }
        return byVoice.mapNotNull { (vid, rows) ->
            val total = rows.first().total
            if (rows.size != total) return@mapNotNull null
            val head = rows.first()
            VoiceChunkRepository.ReadyVoice(
                voiceId = vid,
                total = total,
                conversationId = head.conversationId,
                senderPubKeyHex = head.senderPubKeyHex,
                mimeType = head.mimeType,
                durationMs = head.durationMs,
            )
        }
    }

    override suspend fun findExpiredSummaries(cutoffMs: Long): List<VoiceChunkRepository.ExpiredSummary> {
        val byVoice = store.values.groupBy { it.voiceId }
        return byVoice.mapNotNull { (vid, rows) ->
            val oldest = rows.minOf { it.updatedAtMs }
            if (oldest >= cutoffMs) return@mapNotNull null
            VoiceChunkRepository.ExpiredSummary(
                voiceId = vid,
                total = rows.first().total,
                receivedChunks = rows.size,
                oldestUpdatedMs = oldest,
            )
        }
    }

    override suspend fun deleteOlderThan(cutoffMs: Long) {
        // PR-D2b.1 review round 2: mirror `VoiceChunk.sq deleteOlderThan` —
        // drop every chunk for any voice whose oldest chunk is older than
        // the cutoff, not just the individual old rows. Anything else
        // leaves voices in a permanently-unassemblable mixed-age state.
        val expiredVoiceIds = store.values
            .groupBy { it.voiceId }
            .filterValues { rows -> rows.minOf { it.updatedAtMs } < cutoffMs }
            .keys
        store.entries.removeAll { it.value.voiceId in expiredVoiceIds }
    }

    override suspend fun deleteByVoiceId(voiceId: String) {
        store.entries.removeAll { it.value.voiceId == voiceId }
    }

    override suspend fun deleteAll() {
        store.clear()
    }
}
