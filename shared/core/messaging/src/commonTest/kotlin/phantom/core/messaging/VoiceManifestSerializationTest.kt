// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** JSON round-trip guards for [VoiceManifestV2]. */
class VoiceManifestSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun sampleManifest() = VoiceManifestV2(
        mediaId = "aGVsbG8gd29ybGQgZm9yIHRlc3Rpbmcgb25seQ",
        mediaKey = "AAECBAUGB".repeat(4),
        nonce = "AAECBAUGB".repeat(3),
        durationMs = 12_345L,
        mime = "audio/ogg; codecs=opus",
        chunkCount = 13,
        encryptedSizeBytes = 22_100L,
        plainSizeBytes = 21_900L,
        sha256 = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoK",
    )

    @Test
    fun roundTrip_preservesAllFields() {
        val original = sampleManifest()
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<VoiceManifestV2>(encoded)

        assertEquals(original.type, decoded.type)
        assertEquals(original.mediaId, decoded.mediaId)
        assertEquals(original.mediaKey, decoded.mediaKey)
        assertEquals(original.nonce, decoded.nonce)
        assertEquals(original.alg, decoded.alg)
        assertEquals(original.durationMs, decoded.durationMs)
        assertEquals(original.mime, decoded.mime)
        assertEquals(original.chunkCount, decoded.chunkCount)
        assertEquals(original.encryptedSizeBytes, decoded.encryptedSizeBytes)
        assertEquals(original.plainSizeBytes, decoded.plainSizeBytes)
        assertEquals(original.sha256, decoded.sha256)
    }

    @Test
    fun defaultType_isVoiceV2() {
        val m = sampleManifest()
        assertEquals(VoiceManifestV2.TYPE, m.type)
    }

    @Test
    fun defaultAlg_isXchacha20Poly1305V1() {
        val m = sampleManifest()
        assertEquals(VoiceManifestV2.ALG, m.alg)
    }

    @Test
    fun encodedJson_containsSnakeCaseKeys() {
        val encoded = json.encodeToString(sampleManifest())
        // Verify wire field names match the relay contract
        assert(encoded.contains("\"mediaId\"")) { "Missing mediaId: $encoded" }
        assert(encoded.contains("\"mediaKey\"")) { "Missing mediaKey: $encoded" }
        assert(encoded.contains("\"durationMs\"")) { "Missing durationMs: $encoded" }
        assert(encoded.contains("\"chunkCount\"")) { "Missing chunkCount: $encoded" }
        assert(encoded.contains("\"encryptedSizeBytes\"")) { "Missing encryptedSizeBytes: $encoded" }
        assert(encoded.contains("\"plainSizeBytes\"")) { "Missing plainSizeBytes: $encoded" }
    }

    @Test
    fun ignoreUnknownKeys_doesNotThrow() {
        // Simulates a relay or future sender adding extra fields
        val withExtra = """
            {
              "type": "voice_v2",
              "mediaId": "abc",
              "mediaKey": "key",
              "nonce": "nonce",
              "alg": "xchacha20poly1305-v1",
              "durationMs": 1000,
              "mime": "audio/ogg",
              "chunkCount": 1,
              "encryptedSizeBytes": 100,
              "plainSizeBytes": 84,
              "sha256": "digest",
              "futureField": "should be ignored"
            }
        """.trimIndent()
        val decoded = json.decodeFromString<VoiceManifestV2>(withExtra)
        assertEquals("voice_v2", decoded.type)
        assertEquals(1000L, decoded.durationMs)
    }
}
