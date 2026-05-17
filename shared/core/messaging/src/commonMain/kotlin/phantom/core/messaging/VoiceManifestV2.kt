// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Manifest embedded in a TYPE_VOICE_V2 envelope. The receiver uses this to
 * fetch and decrypt the encrypted voice note stored at the relay's
 * /media/chunk/* endpoints (PR-M1r).
 *
 * Wire field names are camelCase to stay consistent with the existing
 * MessagePayload fields. The manifest is serialized to JSON, encrypted
 * inside the Double Ratchet envelope, and never visible to the relay.
 *
 * Security invariant — AAD binding:
 *   Every ciphertext chunk is encrypted with XChaCha20-Poly1305-IETF where
 *   the Additional Authenticated Data (AAD) is mediaId (UTF-8 bytes).
 *   Changing mediaId without re-encrypting all chunks fails authentication.
 *   This prevents a relay-side swap attack where the relay replaces chunks
 *   of one voice note with chunks from another.
 */
@Serializable
data class VoiceManifestV2(
    /** Discriminator — always "voice_v2". */
    val type: String = "voice_v2",
    /** base64url(random 32 bytes) — unique across all uploads, used as AEAD AAD. */
    @SerialName("mediaId") val mediaId: String,
    /** base64(32-byte XChaCha20 key). */
    @SerialName("mediaKey") val mediaKey: String,
    /** base64(24-byte XChaCha20 nonce). */
    @SerialName("nonce") val nonce: String,
    /** Cipher algorithm identifier — always "xchacha20poly1305-v1". */
    @SerialName("alg") val alg: String = "xchacha20poly1305-v1",
    /** Voice note duration in milliseconds. */
    @SerialName("durationMs") val durationMs: Long,
    /** MIME type of the original audio, e.g. "audio/ogg; codecs=opus". */
    @SerialName("mime") val mime: String,
    /** Number of ciphertext chunks stored at /media/chunk/{mediaId}/{0..chunkCount-1}. */
    @SerialName("chunkCount") val chunkCount: Int,
    /** Total size of the ciphertext blob (sum of all chunk ciphertext bytes). */
    @SerialName("encryptedSizeBytes") val encryptedSizeBytes: Long,
    /** Total size of the original plaintext audio. */
    @SerialName("plainSizeBytes") val plainSizeBytes: Long,
    /** base64(SHA-256(plainAudioBytes)) — receiver verifies after decrypt. */
    @SerialName("sha256") val sha256: String,
) {
    companion object {
        const val TYPE = "voice_v2"
        const val ALG = "xchacha20poly1305-v1"
    }
}
