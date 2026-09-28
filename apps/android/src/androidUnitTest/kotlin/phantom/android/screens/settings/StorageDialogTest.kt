// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.settings

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import java.io.File
import kotlin.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU")
class StorageDialogTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun openingAndClosingNeverClearsFiles() {
        val copy = File(temp.root, "play_123.audio").apply { writeText("copy") }
        var open by mutableStateOf(true)
        var size: Long? = null
        val cache = PlaybackCache(temp.root)
        compose.setContent {
            if (open) StorageDialog(cache, onDismiss = { open = false }, onSizeChanged = { size = it })
        }
        compose.waitUntil(5_000) { size == 4L }
        assertTrue(copy.exists())
        compose.onNodeWithText("Закрыть").performClick()
        compose.runOnIdle { assertFalse(open); assertTrue(copy.exists()) }
    }

    @Test fun explicitConfirmationClearsCopiesButNotRecordingsAndUpdatesSize() {
        val copy = File(temp.root, "play_123.audio").apply { writeText("copy") }
        val recording = File(temp.root, "audio_123.ogg").apply { writeText("original") }
        var size: Long? = null
        val cache = PlaybackCache(temp.root)
        compose.setContent { StorageDialog(cache, onDismiss = {}, onSizeChanged = { size = it }) }
        compose.waitUntil(5_000) { size == 4L }
        compose.onNodeWithText("Очистить кэш").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { size == 0L }
        compose.onNodeWithText("Очистить кэш").assertIsNotEnabled()
        assertFalse(copy.exists())
        assertEquals("original", recording.readText())
    }

    @Test fun deleteFailureIsVisibleAndAllowsRetry() {
        val copy = File(temp.root, "play_123.audio").apply { writeText("copy") }
        var size: Long? = null
        val cache = PlaybackCache(temp.root) { false }
        compose.setContent { StorageDialog(cache, onDismiss = {}, onSizeChanged = { size = it }) }
        compose.waitUntil(5_000) { size == 4L }
        compose.onNodeWithText("Очистить кэш").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Не все файлы удалось очистить.", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Очистить кэш").assertIsEnabled()
        assertTrue(copy.exists())
        assertEquals(4L, size)
    }

    @Test fun inaccessibleCacheShowsErrorNotSuccess() {
        val invalidRoot = File(temp.root, "file").apply { writeText("not a directory") }
        val cache = PlaybackCache(invalidRoot)
        compose.setContent { StorageDialog(cache, onDismiss = {}, onSizeChanged = {}) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Не удалось получить доступ к кэшу.", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Очистить кэш").assertIsNotEnabled()
        compose.onNodeWithText("Закрыть").assertIsEnabled()
    }
}
