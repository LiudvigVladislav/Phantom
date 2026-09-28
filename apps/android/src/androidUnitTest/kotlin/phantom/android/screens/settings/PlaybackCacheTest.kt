// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.settings

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file(root: File, name: String, text: String = "keep") = File(root, name).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    @Test fun deletesOnlyRebuildableCopiesAndPreservesMessagesKeysRecordingsAndTor() {
        val root = temp.newFolder("cache")
        val copies = listOf("play_123.audio", "play_123_-456.audio", "play_124.3gp", "play_125.ogg", "play_126.m4a")
            .map { file(root, it) }
        val protected = listOf("audio_123.ogg", "audio_456.m4a", "phantom_qr.png", "other", "tor-cache/state",
            "play_777.audio/nested", "nested/play_333.audio", "play_bad.audio")
            .map { file(root, it) } + listOf("databases/messages.db", "files/keys", "files/voice/original.ogg")
            .map { file(temp.root, it) }
        val cache = PlaybackCache(root)
        assertEquals(20, cache.sizeBytes())
        assertEquals(CacheCleanupResult(20, 0), cache.clear())
        assertTrue(copies.none { it.exists() })
        protected.forEach { assertEquals("keep", it.readText(), it.path) }
        assertEquals(0, cache.sizeBytes())
    }

    @Test fun symlinksAndLinkedRootsAreNeverFollowed() {
        val root = temp.newFolder("cache")
        val original = file(temp.root, "original", "important")
        val link = File(root, "play_1.audio").toPath()
        Files.createSymbolicLink(link, original.toPath())
        assertEquals(CacheCleanupResult(0, 0), PlaybackCache(root).clear())
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("important", original.readText())
        val linkedRoot = File(temp.root, "linked").toPath()
        Files.createSymbolicLink(linkedRoot, root.toPath())
        assertFailsWith<IOException> { PlaybackCache(linkedRoot.toFile()).clear() }
    }

    @Test fun emptyAndMissingCachesAreIdempotent() {
        val cache = PlaybackCache(File(temp.root, "missing"))
        repeat(2) {
            assertEquals(0, cache.sizeBytes())
            assertEquals(CacheCleanupResult(0, 0), cache.clear())
        }
    }

    @Test fun failedDeleteIsReportedAndStillCounted() {
        val root = temp.newFolder("cache")
        file(root, "play_1.audio")
        val cache = PlaybackCache(root) { false }
        assertEquals(CacheCleanupResult(0, 1), cache.clear())
        assertEquals(4, cache.sizeBytes())
    }

    @Test fun unreadableRootIsNotReportedAsEmptySuccess() {
        val root = file(temp.root, "not-a-directory")
        assertFailsWith<IOException> { PlaybackCache(root).sizeBytes() }
        assertFailsWith<IOException> { PlaybackCache(root).clear() }
    }
}
