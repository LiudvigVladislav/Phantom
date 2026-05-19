// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Unit tests for [MediaChunker]. */
class MediaChunkerTest {

    @Test
    fun chunk_38KbBlob_producesExpectedChunksOfTargetSizeExceptLast() {
        val inputSize = 38 * 1024  // 38 KB
        val blob = ByteArray(inputSize) { it.toByte() }

        val chunks = MediaChunker.chunk(blob)

        val expectedFull = inputSize / MediaChunker.TARGET_RAW_CHUNK_BYTES
        val remainder = inputSize % MediaChunker.TARGET_RAW_CHUNK_BYTES
        val expectedCount = if (remainder == 0) expectedFull else expectedFull + 1

        assertEquals(expectedCount, chunks.size, "expected $expectedCount chunks for $inputSize bytes")

        // All but last must be exactly TARGET_RAW_CHUNK_BYTES
        for (i in 0 until chunks.size - 1) {
            assertEquals(
                MediaChunker.TARGET_RAW_CHUNK_BYTES, chunks[i].size,
                "chunk[$i] size mismatch"
            )
        }

        // Last chunk holds the remainder
        if (remainder > 0) {
            assertEquals(remainder, chunks.last().size, "last chunk size mismatch")
        }
    }

    @Test
    fun chunk_singleChunkInput_returns1Chunk() {
        val blob = ByteArray(MediaChunker.TARGET_RAW_CHUNK_BYTES) { 0x42 }
        val chunks = MediaChunker.chunk(blob)
        assertEquals(1, chunks.size)
        assertEquals(MediaChunker.TARGET_RAW_CHUNK_BYTES, chunks[0].size)
    }

    @Test
    fun chunk_smallerThanChunkSize_returns1Chunk() {
        val blob = ByteArray(500) { 0x01 }
        val chunks = MediaChunker.chunk(blob)
        assertEquals(1, chunks.size)
        assertEquals(500, chunks[0].size)
    }

    @Test
    fun chunk_exactMultipleOfChunkSize_noEmptyTrailingChunk() {
        val blob = ByteArray(MediaChunker.TARGET_RAW_CHUNK_BYTES * 5) { 0xFF.toByte() }
        val chunks = MediaChunker.chunk(blob)
        assertEquals(5, chunks.size)
        chunks.forEach { assertEquals(MediaChunker.TARGET_RAW_CHUNK_BYTES, it.size) }
    }

    @Test
    fun chunk_emptyInput_throwsIllegalArgumentException() {
        assertFailsWith<IllegalArgumentException> {
            MediaChunker.chunk(ByteArray(0))
        }
    }

    @Test
    fun chunk_reassembled_matchesOriginal() {
        val original = ByteArray(5_000) { (it % 256).toByte() }
        val chunks = MediaChunker.chunk(original)
        val reassembled = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        assertTrue(original.contentEquals(reassembled), "reassembled blob does not match original")
    }

    @Test
    fun chunk_customChunkSize_respectsSize() {
        val blob = ByteArray(100) { it.toByte() }
        val chunks = MediaChunker.chunk(blob, chunkSize = 30)
        // 100 / 30 = 3 full + 1 partial = 4 chunks
        assertEquals(4, chunks.size)
        assertEquals(30, chunks[0].size)
        assertEquals(30, chunks[1].size)
        assertEquals(30, chunks[2].size)
        assertEquals(10, chunks[3].size)
    }
}
