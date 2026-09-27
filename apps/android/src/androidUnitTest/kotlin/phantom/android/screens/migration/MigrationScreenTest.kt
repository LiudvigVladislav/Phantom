// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.migration

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.android.R
import phantom.core.messaging.MigrationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MigrationScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun progress_disables_actions_and_success_completes_once() {
        val finish = CompletableDeferred<Result<Unit>>()
        var calls = 0
        var completed = 0
        compose.setContent { MigrationContent({ calls++; finish.await() }, { completed++ }, {}) }
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Quit app").assertIsNotEnabled()
        assertEquals(0, completed)
        compose.runOnIdle { finish.complete(Result.success(Unit)) }
        compose.waitForIdle()
        assertEquals(1, calls)
        assertEquals(1, completed)
    }

    @Test fun retryable_failure_can_retry_without_completing_early() {
        var calls = 0
        var completed = 0
        compose.setContent {
            MigrationContent({
                calls++
                if (calls == 1) Result.failure(MigrationException.PublishRateLimited("secret"))
                else Result.success(Unit)
            }, { completed++ }, {})
        }
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.onNodeWithText("The server is busy. Please wait a moment and retry.").assertExists()
        assertEquals(0, completed)
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(2, calls)
        assertEquals(1, completed)
    }

    @Test fun key_mismatch_offers_exit_not_a_retry_or_raw_exception() {
        var quit = false
        compose.setContent {
            MigrationContent({ Result.failure(MigrationException.SigningKeyMismatch("private-details")) },
                { error("Must not complete") }, { quit = true })
        }
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.onAllNodesWithText("Retry").assertCountEquals(0)
        compose.onAllNodesWithText("private-details", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Quit app").performScrollTo().performClick()
        assertTrue(quit)
    }

    @Test fun every_error_is_resource_backed_and_does_not_include_raw_details() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val cases = listOf(
            MigrationException.PublishRateLimited("secret"),
            MigrationException.PublishUnexpected(503, "secret"),
            MigrationException.PublishBadRequest("secret"),
            MigrationException.SigningKeyMismatch("secret"),
            MigrationException.NoIdentity,
            IllegalStateException("secret"), null,
        )
        cases.forEach { assertFalse(context.getString(migrationFailure(it).messageRes).contains("secret")) }
        assertFalse(migrationFailure(MigrationException.NoIdentity).canRetry)
        assertFalse(migrationFailure(MigrationException.PublishBadRequest("secret")).canRetry)
        assertTrue(context.getString(R.string.migration_error_identity).contains("Do not reinstall"))
    }
}
