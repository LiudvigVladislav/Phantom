// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * JVM actual of [VoiceFileStore] — used in unit tests / desktop builds (PR-M1w).
 *
 * Writes audio bytes to a system temp directory so tests that exercise the
 * download orchestrator path can verify the file was written without needing
 * an Android runtime.
 */
actual class VoiceFileStore {

    private val voiceDir get() = File(System.getProperty("java.io.tmpdir"), "phantom_voice_test")

    actual suspend fun save(
        mediaId: String,
        audioBytes: ByteArray,
        mime: String,
    ): String = withContext(Dispatchers.IO) {
        val dir = voiceDir
        dir.mkdirs()
        val file = File(dir, "$mediaId.audio")
        file.writeBytes(audioBytes)
        file.absolutePath
    }

    actual suspend fun deleteStored(mediaId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        if (mediaId.isBlank() || !mediaId.all { it.isLetterOrDigit() || it == '-' || it == '_' } ||
            !file.name.startsWith("$mediaId.") ||
            file.absoluteFile.parentFile != voiceDir.absoluteFile ||
            file.canonicalFile.parentFile != voiceDir.canonicalFile) return@withContext false
        !file.exists() || file.delete()
    }

    actual suspend fun pruneOrphans(referencedPaths: Set<String>): Int = withContext(Dispatchers.IO) {
        val dir = voiceDir
        if (!dir.exists()) return@withContext 0
        val references = referencedPaths.map { File(it).absolutePath }.toSet()
        var removed = 0
        dir.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.absolutePath !in references &&
                file.canonicalFile.parentFile == dir.canonicalFile && file.delete()) removed++
        }
        removed
    }
}
