// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android actual of [VoiceFileStore] (PR-M1w, Q6).
 *
 * Writes decrypted audio bytes to `context.filesDir/voice/<mediaId>.<ext>`.
 * Uses `filesDir` (not `cacheDir`) — persistent, not evicted by the OS.
 *
 * File extension is derived from the MIME type:
 *   "audio/ogg*"  → .ogg
 *   "audio/opus*" → .opus
 *   other         → .audio
 *
 * This class has no dependency on Okio — standard Java file I/O is sufficient
 * and avoids adding a new external dependency (hard guardrail §9).
 */
actual class VoiceFileStore(private val context: Context) {

    actual suspend fun save(
        mediaId: String,
        audioBytes: ByteArray,
        mime: String,
    ): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "voice").also { it.mkdirs() }
        val ext = mimeToExtension(mime)
        // mediaId is base64url (URL-safe, no padding) — safe as a filename.
        val file = File(dir, "$mediaId.$ext")
        file.writeBytes(audioBytes)
        file.absolutePath
    }

    private fun mimeToExtension(mime: String): String = when {
        mime.startsWith("audio/ogg")  -> "ogg"
        mime.startsWith("audio/opus") -> "opus"
        // audio/m4a is what the AAC-fallback sender sets (PR-M2a). audio/mp4
        // is the IANA-registered form — accept both so receiver always lands
        // on the .m4a extension MediaPlayer expects.
        mime.startsWith("audio/m4a")  -> "m4a"
        mime.startsWith("audio/mp4")  -> "m4a"
        mime.startsWith("audio/3gpp") -> "3gp"
        else                          -> "audio"
    }
}
