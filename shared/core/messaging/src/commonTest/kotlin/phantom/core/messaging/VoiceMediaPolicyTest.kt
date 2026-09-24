// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VoiceMediaPolicyTest {
    private val measuredEncodedBytesPerSecond = 4_476

    @Test
    fun measuredFiveThirtyAndOneTwentySecondNotesFitRelayBounds() {
        listOf(5 to 4, 30 to 20, 120 to 77).forEach { (seconds, expectedChunks) ->
            val bytes = measuredEncodedBytesPerSecond * seconds
            VoiceMediaPolicy.validatePlaintext(seconds * 1_000L, bytes)
            assertEquals(
                expectedChunks,
                VoiceMediaPolicy.chunkCount(
                    bytes + VoiceMediaPolicy.AEAD_TAG_BYTES,
                    MediaChunker.TARGET_RAW_CHUNK_BYTES,
                ),
            )
        }
    }

    @Test
    fun measuredFiveMinuteNoteFitsWithoutChangingCodecQuality() {
        val bytes = measuredEncodedBytesPerSecond * 300
        VoiceMediaPolicy.validatePlaintext(VoiceMediaPolicy.MAX_DURATION_MS, bytes)
        assertEquals(
            192,
            VoiceMediaPolicy.chunkCount(bytes + 16, MediaChunker.TARGET_RAW_CHUNK_BYTES),
        )
    }

    @Test
    fun noteLongerThanFiveMinutesFailsBeforeUpload() {
        assertFailsWith<IllegalArgumentException> {
            VoiceMediaPolicy.validatePlaintext(301_000L, measuredEncodedBytesPerSecond * 301)
        }
    }

    @Test
    fun relayByteAndChunkLimitsFailClosed() {
        assertFailsWith<IllegalArgumentException> {
            VoiceMediaPolicy.validatePlaintext(
                durationMs = 300_000L,
                plaintextBytes = VoiceMediaPolicy.MAX_PLAINTEXT_BYTES + 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VoiceMediaPolicy.validateCiphertext(
                ciphertextBytes = VoiceMediaPolicy.MAX_CIPHERTEXT_BYTES,
                chunkSizeBytes = 1,
            )
        }
    }

    @Test
    fun receiverManifestUsesTheSameBoundsAsSenderAndRelay() {
        assertEquals(
            null,
            VoiceMediaPolicy.manifestBoundsFailure(
                durationMs = VoiceMediaPolicy.MAX_DURATION_MS,
                encryptedSizeBytes = VoiceMediaPolicy.MAX_CIPHERTEXT_BYTES.toLong(),
                chunkCount = VoiceMediaPolicy.MAX_CHUNKS,
            ),
        )
        assertEquals(
            "chunkCount",
            VoiceMediaPolicy.manifestBoundsFailure(
                durationMs = VoiceMediaPolicy.MAX_DURATION_MS,
                encryptedSizeBytes = VoiceMediaPolicy.MAX_CIPHERTEXT_BYTES.toLong(),
                chunkCount = VoiceMediaPolicy.MAX_CHUNKS + 1,
            ),
        )
        assertEquals(
            "encryptedSizeBytes",
            VoiceMediaPolicy.manifestBoundsFailure(
                durationMs = VoiceMediaPolicy.MAX_DURATION_MS,
                encryptedSizeBytes = VoiceMediaPolicy.MAX_CIPHERTEXT_BYTES.toLong() + 1,
                chunkCount = VoiceMediaPolicy.MAX_CHUNKS,
            ),
        )
        assertEquals(
            "durationMs",
            VoiceMediaPolicy.manifestBoundsFailure(
                durationMs = VoiceMediaPolicy.MAX_DURATION_MS + 1,
                encryptedSizeBytes = VoiceMediaPolicy.MAX_CIPHERTEXT_BYTES.toLong(),
                chunkCount = VoiceMediaPolicy.MAX_CHUNKS,
            ),
        )
    }

    @Test
    fun groupAudioCountsItsActualThreeKibibyteChunks() {
        val largestAccepted = DefaultMessagingService.AUDIO_CHUNK_BYTES * VoiceMediaPolicy.MAX_CHUNKS
        VoiceMediaPolicy.validatePlaintext(
            durationMs = VoiceMediaPolicy.MAX_DURATION_MS,
            plaintextBytes = largestAccepted,
            chunkSizeBytes = DefaultMessagingService.AUDIO_CHUNK_BYTES,
            ciphertextOverheadBytes = 0,
        )
        assertFailsWith<IllegalArgumentException> {
            VoiceMediaPolicy.validatePlaintext(
                durationMs = VoiceMediaPolicy.MAX_DURATION_MS,
                plaintextBytes = largestAccepted + 1,
                chunkSizeBytes = DefaultMessagingService.AUDIO_CHUNK_BYTES,
                ciphertextOverheadBytes = 0,
            )
        }
    }

    @Test
    fun plausibleContainerMetadataWinsOverTheCoarserTicker() {
        assertEquals(
            119_998L,
            VoiceMediaPolicy.resolveDurationMs(
                metadataDurationMs = 119_998L,
                tickerDurationMs = 118_900L,
            ),
        )
    }

    @Test
    fun impossibleContainerMetadataFallsBackToBoundedTicker() {
        assertEquals(
            299_500L,
            VoiceMediaPolicy.resolveDurationMs(
                metadataDurationMs = 972_658L,
                tickerDurationMs = 299_500L,
            ),
        )
        assertEquals(
            VoiceMediaPolicy.MAX_DURATION_MS,
            VoiceMediaPolicy.resolveDurationMs(
                metadataDurationMs = null,
                tickerDurationMs = VoiceMediaPolicy.MAX_DURATION_MS + 5_000L,
            ),
        )
    }
}
