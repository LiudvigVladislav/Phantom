// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

/**
 * Slices an encrypted voice blob into upload-safe chunks for the relay's
 * /media/upload-chunk endpoint (PR-M1r).
 *
 * Chunk size rationale:
 *   TARGET_RAW_CHUNK_BYTES = 1700 bytes (pre-base64 ciphertext)
 *   base64(1700) ≈ 2268 chars
 *   JSON wrapper ≈ 120 bytes (field names + mediaId + idx/total fields)
 *   Total body ≈ 2388 bytes < CLIENT_MAX_CHUNK_BODY_BYTES (2600)
 *               < relay hard cap (3072)
 *
 *   Each chunk includes a 16-byte Poly1305 tag, so plaintext per chunk is
 *   up to TARGET_RAW_CHUNK_BYTES - 16 = 1684 bytes. For a 15-second voice
 *   note at ~12 kbps Opus bitrate (≈ 22 KB), this produces about 13 chunks.
 */
object MediaChunker {

    /** Maximum ciphertext bytes per chunk (post-encryption, pre-base64). */
    const val TARGET_RAW_CHUNK_BYTES = 1700

    /**
     * Splits [encrypted] into a list of byte slices, each of at most
     * [chunkSize] bytes. The last slice may be shorter.
     *
     * @throws IllegalArgumentException if [encrypted] is empty (voice notes
     *   must not be zero-length; empty input indicates a caller bug).
     */
    fun chunk(
        encrypted: ByteArray,
        chunkSize: Int = TARGET_RAW_CHUNK_BYTES,
    ): List<ByteArray> {
        require(encrypted.isNotEmpty()) {
            "MediaChunker.chunk: encrypted blob is empty — voice audio must not be zero-length"
        }
        require(chunkSize > 0) {
            "MediaChunker.chunk: chunkSize must be positive, got $chunkSize"
        }

        val result = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < encrypted.size) {
            val end = minOf(offset + chunkSize, encrypted.size)
            result.add(encrypted.copyOfRange(offset, end))
            offset = end
        }
        return result
    }
}
