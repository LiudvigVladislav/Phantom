// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

/**
 * Slices an encrypted voice blob into upload-safe chunks for the relay's
 * /media/upload-chunk endpoint (PR-M1r) and `/media/v3/{mediaId}/{idx}`
 * (PR-M2f).
 *
 * Chunk size rationale (PR-M2f.2):
 *   TARGET_RAW_CHUNK_BYTES = 3200 bytes (pre-base64 ciphertext)
 *
 *   On the binary v3 path the wire body IS the raw ciphertext, so 3200 raw
 *   → 3200 wire. The Test #70 / #70.1 / #70.2 matrix on Tele2 LTE proved
 *   v3 carries chunks up to 3500 bytes both directions without integrity
 *   errors; 3200 was chosen as a "max-1-tier safety margin" production
 *   value over 3500 (Vladislav 2026-05-19 locked policy).
 *
 *   The previous 1700 baseline was tuned for v2 JSON+Base64, where 1700
 *   raw inflated to ~2388 wire bytes — the Tele2 v2 full-roundtrip ceiling
 *   was ~2400 (M2c.0 probe). On v3 binary that 33 % JSON inflation is
 *   gone, so the new effective ceiling is much higher, and 3200 sits
 *   comfortably below the empirically-observed 3500 limit.
 *
 *   Each chunk includes a 16-byte Poly1305 tag, so plaintext per chunk is
 *   up to TARGET_RAW_CHUNK_BYTES - 16 = 3184 bytes. For a 15-second voice
 *   note at ~12 kbps Opus bitrate (≈ 22 KB), this produces about 7 chunks
 *   (was ~13 at the 1700 baseline).
 *
 *   The legacy `/media/upload-chunk` (v2) JSON path still uses Base64
 *   inflation, so a 3200-byte chunk produces a ~4380-byte wire body. The
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
     * PR-M2f.2 (2026-05-19): bumped 1700 → 3200 after Test #70 / #70.1 /
     * #70.2 confirmed the v3 binary path carries chunks up to 3500 on
     * Tele2 LTE both directions with zero integrity errors. 3200 is the
     * locked production value with one safety tier under the observed
     * ceiling.
     */
    const val TARGET_RAW_CHUNK_BYTES = 3200

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
