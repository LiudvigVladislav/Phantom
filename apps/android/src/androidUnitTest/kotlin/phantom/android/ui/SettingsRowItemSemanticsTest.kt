// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsRowItemSemanticsTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun informationalRowHasNoClickAction() {
        composeTestRule.setContent {
            SettingsRowItem(icon = { Text("i") }, label = "Local protection", value = "On")
        }

        composeTestRule.onNodeWithText("Local protection")
            .assert(hasClickAction().not())
    }

    @Test
    fun navigableRowStillClicks() {
        var clicked = false
        composeTestRule.setContent {
            SettingsRowItem(icon = { Text("i") }, label = "Privacy mode", onClick = { clicked = true })
        }

        composeTestRule.onNodeWithText("Privacy mode")
            .assertHasClickAction()
            .performClick()
        assert(clicked)
    }
}
