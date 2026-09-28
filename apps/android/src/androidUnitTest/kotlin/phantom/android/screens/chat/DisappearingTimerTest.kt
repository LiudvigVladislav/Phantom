package phantom.android.screens.chat

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU")
class DisappearingTimerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun localCommitPrecedesSubmissionAndPreservesAllExistingChoices() = runTest {
        assertEquals(listOf(0L, 30L, 300L, 3600L, 86400L, 604800L), DISAPPEARING_TIMER_OPTIONS)
        for (option in DISAPPEARING_TIMER_OPTIONS) {
            val events = mutableListOf<String>()
            assertTrue(applyDisappearingTimer(option, { events += "local:$it" }, { events += "submit"; Result.success(Unit) }))
            assertEquals(listOf("local:$option", "submit"), events)
        }
    }
    @Test fun localFailureNeverSendsAndInvalidOptionsNeverWrite() = runTest {
        assertFails { applyDisappearingTimer(30, { error("disk full") }, { error("must not send") }) }
        assertFailsWith<IllegalArgumentException> {
            applyDisappearingTimer(1, { error("must not write") }, { error("must not send") })
        }
    }
    @Test fun failedResultAndThrownSubmissionAreBothLocalOnly() = runTest {
        var local = -1L
        assertFalse(applyDisappearingTimer(30, { local = it }, { Result.failure(IllegalStateException("offline")) }))
        assertEquals(30, local)
        assertFalse(applyDisappearingTimer(300, { local = it }, { error("offline") }))
        assertEquals(300, local)
        assertFailsWith<CancellationException> { applyDisappearingTimer(0, {}, { throw CancellationException() }) }
    }
    @Test fun openingAndCancellingDoesNotWriteOrSend() {
        var closed = false
        compose.setContent { DisappearingTimerDialog({ 0 }, { error("must not apply") }, { closed = true }) }
        compose.waitForIdle()
        compose.onNodeWithText("Отмена").performClick()
        assertTrue(closed)
    }
    @Test fun failedSubmissionStaysOpenWithLocalOnlyWarningAndRetry() {
        var attempts = 0
        var closed = false
        compose.setContent {
            DisappearingTimerDialog({ 0 }, { attempts++; attempts > 1 }, { closed = true })
        }
        compose.waitForIdle()
        compose.onNodeWithText("30 секунд").performClick()
        compose.waitForIdle()
        assertFalse(closed)
        compose.onNodeWithText("На этом устройстве сохранено", substring = true).assertExists()
        compose.onNodeWithText("30 секунд").performClick()
        compose.waitForIdle()
        assertTrue(closed)
        assertEquals(2, attempts)
    }
}
