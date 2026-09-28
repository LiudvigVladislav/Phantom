package phantom.android.screens.saved

import android.app.Application
import android.Manifest
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU")
class SavedComposerTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()
    private class PermissionRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        var request: Int? = null
        var permission: Any? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            request = requestCode
            permission = input
        }
    }

    @Test fun permissionDenialNeverStartsRecordingAndShowsExplanation() {
        val registry = PermissionRegistry()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val draft = SavedVoiceDraft({ error("must not open microphone") }, { 0 })
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                SavedComposer("", {}, false, {}, {}, { _, _ -> error("must not save") }, draft)
            }
        }
        compose.onNodeWithContentDescription("Записать голосовое сообщение").performTouchInput { down(center); up() }
        compose.runOnIdle { registry.dispatchResult(requireNotNull(registry.request), false) }
        assertEquals(Manifest.permission.RECORD_AUDIO, registry.permission)
        compose.onNodeWithText("Для записи голосовой заметки нужен доступ к микрофону.").assertExists()
        assertNull(draft.state)
        assertFalse(draft.pending)
    }

    @Test fun backgroundingCancelsAnActiveRecordingWithoutSaving() {
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        val file = temp.newFile()
        var released = false
        val draft = SavedVoiceDraft({ file to object : LocalVoiceRecorder {
            override fun stop() {}
            override fun release() { released = true }
            override fun pause() {}
            override fun resume() {}
            override fun amplitude() = 0
        } }, { 0 })
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED; draft.start() }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                SavedComposer("", {}, false, {}, {}, { _, _ -> error("must not save") }, draft)
            }
        }
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        assertNull(draft.state)
        assertFalse(file.exists())
        assertTrue(released)
        compose.onNodeWithText("Запись отменена при переходе приложения в фон.").assertExists()
    }
    @Test fun textFailureRetainsDraftAndSuccessfulRetryClearsIt() {
        var text by mutableStateOf("Заметка")
        var fail = true
        var saves = 0
        compose.setContent {
            SavedComposer(text, { text = it }, false, {}, onSaveText = {
                saves++
                if (fail) error("disk full")
                text = ""
            }, onSaveVoice = { _, _ -> error("not voice") })
        }
        compose.onNodeWithContentDescription("Сохранить заметку").performClick()
        compose.waitForIdle()
        assertEquals("Заметка", text)
        compose.onNodeWithText("Не удалось сохранить.", substring = true).assertExists()
        fail = false
        compose.onNodeWithContentDescription("Сохранить заметку").performClick()
        compose.waitForIdle()
        assertEquals("", text)
        assertEquals(2, saves)
    }
    @Test fun emojiAppendsToDraftAndCancelEditingDoesNotSave() {
        var text by mutableStateOf("Привет ")
        var cancelled = false
        compose.setContent {
            SavedComposer(text, { text = it }, true, { cancelled = true },
                onSaveText = { error("must not save") }, onSaveVoice = { _, _ -> error("must not save") })
        }
        compose.onNodeWithContentDescription("Эмодзи").performClick()
        compose.onNodeWithText("Смайлики").assertExists()
        assertEquals(
            phantom.android.screens.chat.EMOJI_CATEGORIES.map { it.id }.toSet(),
            phantom.android.screens.chat.EMOJI_CATEGORY_LABELS.keys,
        )
        compose.onNodeWithText("😀").performClick()
        assertEquals("Привет 😀", text)
        compose.onNodeWithText("Отменить редактирование").performClick()
        assertTrue(cancelled)
    }
    @Test fun pendingVoiceSurvivesFailedSaveAndRetriesWithoutNewId() {
        val file = temp.newFile().apply { writeText("recording") }
        val recorder = object : LocalVoiceRecorder {
            override fun stop() {}
            override fun release() {}
            override fun pause() {}
            override fun resume() {}
            override fun amplitude() = 0
        }
        val draft = SavedVoiceDraft({ file to recorder }, { 1000L })
        draft.start()
        draft.finish()
        val ids = mutableListOf<String>()
        var fail = true
        compose.setContent {
            SavedComposer("", {}, false, {}, onSaveText = {}, onSaveVoice = { id, body ->
                ids += id
                assertTrue(body.startsWith("[AUDIO:"))
                if (fail) error("disk full")
            }, recording = draft)
        }
        compose.onNodeWithText("Повторить сохранение").performClick()
        compose.waitUntil(5000) { ids.size == 1 }
        compose.waitForIdle()
        assertTrue(file.exists())
        assertTrue(draft.pending)
        fail = false
        compose.onNodeWithText("Повторить сохранение").performClick()
        compose.waitUntil(5000) { !draft.pending }
        assertFalse(file.exists())
        assertEquals(listOf(draft.id, draft.id), ids)
    }
}
