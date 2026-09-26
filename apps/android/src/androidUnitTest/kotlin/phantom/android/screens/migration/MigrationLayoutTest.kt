// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.migration

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import phantom.android.R
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MigrationLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrow_screen_with_large_font_keeps_copy_and_actions_readable() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Box(Modifier.width(320.dp)) {
                    MigrationContent({ Result.failure(IllegalStateException("not for display")) }, {}, {})
                }
            }
        }
        val strings = listOf(R.string.migration_title, R.string.migration_explanation,
            R.string.migration_data_preservation, R.string.migration_keep_installed,
            R.string.migration_quit, R.string.migration_continue)
        for (id in strings) assertReadable(context.getString(id))
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        assertReadable(context.getString(R.string.migration_error_generic))
        assertReadable(context.getString(R.string.migration_retry))
    }

    private fun assertReadable(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        val layout = layouts.single()
        assertFalse(layout.hasVisualOverflow,
            "$text size=${layout.size} widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight} " +
                "lines=${layout.lineCount} lastEnd=${layout.getLineEnd(layout.lineCount - 1)} constraints=${layout.layoutInput.constraints}")
    }
}
