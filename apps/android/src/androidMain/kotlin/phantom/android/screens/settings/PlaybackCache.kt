// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.settings

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal data class CacheCleanupResult(val deletedBytes: Long, val failedFiles: Int)

/** Only decoded playback copies can be rebuilt from the message body.
 * Recordings (audio_*), QR shares, Tor state and persistent voice originals are not cache to delete.
 */
internal class PlaybackCache(
    private val root: File,
    private val deleteFile: (File) -> Boolean = { it.delete() },
) {
    private val playbackName = Regex("play_[0-9]+(?:_-?[0-9]+)?\\.(?:audio|3gp|ogg|m4a)")

    private fun eligible(file: File): Boolean =
        playbackName.matches(file.name) &&
            Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) &&
            file.canonicalFile.parentFile == root.canonicalFile

    private fun candidates(): List<File> {
        if (Files.isSymbolicLink(root.toPath())) throw IOException("Linked cache root")
        if (!root.exists()) return emptyList()
        return (root.listFiles() ?: throw IOException("Cannot list playback cache"))
            .filter(::eligible)
    }

    fun sizeBytes(): Long = candidates().sumOf { it.length() }

    fun clear(): CacheCleanupResult {
        var deletedBytes = 0L
        var failedFiles = 0
        for (file in candidates()) {
            try {
                // Recheck after listing; never recurse into directories or follow links.
                if (!eligible(file)) continue
                val size = file.length()
                if (deleteFile(file)) deletedBytes += size
                else if (file.exists()) failedFiles++
            } catch (_: IOException) {
                failedFiles++
            } catch (_: SecurityException) {
                failedFiles++
            }
        }
        return CacheCleanupResult(deletedBytes, failedFiles)
    }
}
