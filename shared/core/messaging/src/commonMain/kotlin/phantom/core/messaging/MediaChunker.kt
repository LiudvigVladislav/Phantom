// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

/**
 * Slices an encrypted voice blob into upload-safe chunks for the relay's
 * /media/upload-chunk endpoint (PR-M1r) and `/media/v3/{mediaId}/{idx}`
 * (PR-M2f).
 *
 * Chunk size rationale (PR-M2f.2):
 *   TARGET_RAW_CHUNK_BYTES = 7000 bytes (pre-base64 ciphertext)
 *
 *   On the binary v3 path the wire body IS the raw ciphertext. Production LTE
 *   validation after durable relay storage showed that connection reuse is
 *   unavailable on the tested carrier route, making request count the dominant
 *   cost. 7000 stays 2000 bytes below the relay's 9000-byte body ceiling while
 *   cutting request count by more than half versus 3200.
 *
 *   The previous 1700 baseline was tuned for v2 JSON+Base64, where 1700
 *   raw inflated to ~2388 wire bytes — the Tele2 v2 full-roundtrip ceiling
 *   was ~2400 (M2c.0 probe). On v3 binary that 33 % JSON inflation is
 *   gone, so the effective binary-v3 ceiling is the relay body limit.
 *
 *   Each chunk includes a 16-byte Poly1305 tag, so plaintext per chunk is
 *   up to TARGET_RAW_CHUNK_BYTES - 16 = 6984 bytes. For a 15-second voice
 *   note at the production Opus bitrate, this roughly halves the request count
 *   relative to the previous 3200-byte baseline.
 *
 *   The legacy `/media/upload-chunk` (v2) JSON path still uses Base64
 *   inflation, so a 7000-byte chunk produces a larger JSON body. The
 *   relay's default `max_media_upload_body_bytes = 9000` cap accommodates
 *   this; older relays with a lower cap should still receive the binary
 *   v3 path because `media_capabilities.binary_v3` lights up. Clients
 *   that fall back to v2 will get 413 on bumped chunks, which is the
 *   correct signal that they must update to a relay that supports v3.
 */
object MediaChunker {

    /**
     * Maximum ciphertext bytes per chunk (post-encryption, pre-base64).
     *
     * 7000 is the binary-v3 production value validated on physical LTE with
     * encrypted 5 / 30 / 120-second voice messages. It leaves a
     * fixed 2000-byte margin below the relay's 9000-byte request-body ceiling.
     */
    const val TARGET_RAW_CHUNK_BYTES = 7000

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
