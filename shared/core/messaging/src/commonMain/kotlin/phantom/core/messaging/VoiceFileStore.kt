// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

/**
 * Platform-specific persistent audio file store for received voice_v2 messages (PR-M1w, Q6).
 *
 * Android actual writes to `context.filesDir/voice/<mediaId>.<ext>` (persistent,
 * survives reboot, not evicted by the OS unlike cacheDir).
 * JVM actual (used in commonTest / unit tests) writes to a system temp directory.
 *
 * The returned path is embedded in [phantom.core.storage.MessageEntity.plaintextCache]
 * as `[AUDIO_LOCAL:<path>]` so [AudioBubble] can read the file directly.
 *
 * Cleanup (delete on message delete / TTL) is out of scope for PR-M1w round 1.
 */
expect class VoiceFileStore {

    /**
     * Writes [audioBytes] to a persistent location keyed on [mediaId].
     *
     * @param mediaId Unique media ID (base64url, 32 bytes). Used as the filename.
     * @param audioBytes Decrypted plaintext audio bytes.
     * @param mime       MIME type of the audio (e.g. "audio/ogg; codecs=opus").
     *                   Used to choose the file extension.
     * @return Absolute path to the written file.
     */
    suspend fun save(mediaId: String, audioBytes: ByteArray, mime: String): String
}
