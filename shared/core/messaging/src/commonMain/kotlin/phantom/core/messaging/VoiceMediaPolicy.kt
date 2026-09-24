// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

/** Shared client/relay bounds for voice-note uploads. */
object VoiceMediaPolicy {
    const val MAX_DURATION_MS = 5L * 60L * 1_000L
    const val MAX_METADATA_TICKER_DRIFT_MS = 2_000L
    const val MAX_CIPHERTEXT_BYTES = 4 * 1024 * 1024
    const val AEAD_TAG_BYTES = 16
    const val MAX_PLAINTEXT_BYTES = MAX_CIPHERTEXT_BYTES - AEAD_TAG_BYTES
    const val MAX_CHUNKS = 1_024

    /**
     * Choose a duration that is safe to publish with the voice manifest.
     *
     * Android's container metadata is normally more accurate than the 100 ms
     * UI ticker, but some recorder/codec combinations can emit an impossible
     * duration for an otherwise valid file. Trust metadata only when it is
     * inside the product limit and close to the active-recording ticker.
     */
    fun resolveDurationMs(metadataDurationMs: Long?, tickerDurationMs: Long): Long {
        val boundedTicker = tickerDurationMs.coerceIn(0L, MAX_DURATION_MS)
        return metadataDurationMs?.takeIf { metadata ->
            metadata in 1..MAX_DURATION_MS &&
                kotlin.math.abs(metadata - boundedTicker) <= MAX_METADATA_TICKER_DRIFT_MS
        } ?: boundedTicker
    }

    fun validatePlaintext(
        durationMs: Long,
        plaintextBytes: Int,
        chunkSizeBytes: Int = MediaChunker.TARGET_RAW_CHUNK_BYTES,
        ciphertextOverheadBytes: Int = AEAD_TAG_BYTES,
    ) {
        require(durationMs in 1..MAX_DURATION_MS) {
            "Voice duration $durationMs ms exceeds the five-minute limit"
        }
        require(plaintextBytes in 1..MAX_PLAINTEXT_BYTES) {
            "Voice payload $plaintextBytes bytes exceeds the relay media limit"
        }
        require(ciphertextOverheadBytes >= 0)
        val ciphertextBytes = plaintextBytes + ciphertextOverheadBytes
        val chunks = chunkCount(ciphertextBytes, chunkSizeBytes)
        require(chunks <= MAX_CHUNKS) {
            "Voice requires $chunks chunks, above the relay limit $MAX_CHUNKS"
        }
    }

    fun validateCiphertext(ciphertextBytes: Int, chunkSizeBytes: Int) {
        require(ciphertextBytes in 1..MAX_CIPHERTEXT_BYTES) {
            "Encrypted voice payload $ciphertextBytes bytes exceeds the relay media limit"
        }
        val chunks = chunkCount(ciphertextBytes, chunkSizeBytes)
        require(chunks <= MAX_CHUNKS) {
            "Encrypted voice requires $chunks chunks, above the relay limit $MAX_CHUNKS"
        }
    }

    fun manifestBoundsFailure(
        durationMs: Long,
        encryptedSizeBytes: Long,
        chunkCount: Int,
    ): String? = when {
        durationMs !in 1..MAX_DURATION_MS -> "durationMs"
        encryptedSizeBytes !in 1..MAX_CIPHERTEXT_BYTES.toLong() -> "encryptedSizeBytes"
        chunkCount !in 1..MAX_CHUNKS -> "chunkCount"
        else -> null
    }

    fun chunkCount(bytes: Int, chunkSizeBytes: Int): Int {
        require(bytes > 0)
        require(chunkSizeBytes > 0)
        return (bytes + chunkSizeBytes - 1) / chunkSizeBytes
    }
}
