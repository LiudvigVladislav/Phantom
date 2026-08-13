// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.android.ui.designv2.components.PhantomButton
import phantom.android.ui.designv2.components.PhantomFilterChip
import phantom.android.ui.designv2.components.PhantomInput
import phantom.android.ui.designv2.components.PhantomSegmentedControl
import phantom.android.ui.designv2.components.PhantomToggle

/**
 * Semantics tests for DesignV2 components — Robolectric-backed
 * `createComposeRule()` in a JVM unit test.
 *
 * Coverage per Vladislav's F2b gate (2026-07-30):
 *   - PhantomButton               : Role.Button, click action, enabled/disabled.
 *   - PhantomToggle               : Role.Switch, on/off, disabled.
 *   - PhantomSegmentedControl     : Role.Tab per segment, selected/not-selected.
 *   - PhantomFilterChip           : Role.Tab, selected/not-selected.
 *   - PhantomInput (error state)  : Semantics.Error carries the helper text
 *                                   as the error description.
 */
// application = Application::class forces Robolectric to install a bare
// android.app.Application in place of `phantom.android.PhantomApplication`,
// which calls System.loadLibrary("sqlcipher") in its onCreate. sqlcipher's
// native library is not on the JVM test host's library path (it's an .so
// bundled into the APK), so the real Application fails to instantiate under
// Robolectric. DesignV2 components have zero dependency on the app
// container, so the bare Application is safe for these tests.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhantomComponentsSemanticsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Button ────────────────────────────────────────────────────────────

    @Test
    fun button_enabled_has_click_action_and_button_role() {
        var clicked = false
        composeTestRule.setContent {
            PhantomButton(text = "Continue", onClick = { clicked = true })
        }
        composeTestRule.onNodeWithText("Continue")
            .assertHasClickAction()
            .assertIsEnabled()
            .performClick()
        assert(clicked) { "PhantomButton.onClick did not fire on performClick()" }
    }

    @Test
    fun button_disabled_reports_not_enabled() {
        composeTestRule.setContent {
            PhantomButton(text = "Continue", onClick = {}, enabled = false)
        }
        composeTestRule.onNodeWithText("Continue")
            .assertIsNotEnabled()
    }

    // ── Toggle ────────────────────────────────────────────────────────────

    @Test
    fun toggle_on_reports_switch_role_and_is_on() {
        composeTestRule.setContent {
            PhantomToggle(checked = true, onCheckedChange = {})
        }
        composeTestRule.onNode(hasSwitchRole())
            .assertIsOn()
            .assertIsEnabled()
    }

    @Test
    fun toggle_off_reports_is_off() {
        composeTestRule.setContent {
            PhantomToggle(checked = false, onCheckedChange = {})
        }
        composeTestRule.onNode(hasSwitchRole())
            .assertIsOff()
    }

    @Test
    fun toggle_disabled_reports_not_enabled() {
        composeTestRule.setContent {
            PhantomToggle(checked = true, onCheckedChange = {}, enabled = false)
        }
        composeTestRule.onNode(hasSwitchRole())
            .assertIsNotEnabled()
    }

    // ── SegmentedControl ─────────────────────────────────────────────────

    @Test
    fun segmented_control_selected_segment_reports_tab_role_and_selected() {
        composeTestRule.setContent {
            PhantomSegmentedControl(
                options = listOf("All", "Incoming", "Missed"),
                selectedIndex = 1,
                onSelect = {},
            )
        }
        composeTestRule.onNodeWithText("Incoming")
            .assert(hasTabRole())
            .assertIsSelected()
        composeTestRule.onNodeWithText("All")
            .assert(hasTabRole())
            .assertIsNotSelected()
    }

    // ── FilterChip ───────────────────────────────────────────────────────

    @Test
    fun filter_chip_selected_reports_tab_role_and_selected() {
        composeTestRule.setContent {
            PhantomFilterChip(text = "Verified", selected = true, onSelectedChange = {})
        }
        composeTestRule.onNodeWithText("Verified")
            .assert(hasTabRole())
            .assertIsSelected()
    }

    @Test
    fun filter_chip_unselected_reports_not_selected() {
        composeTestRule.setContent {
            PhantomFilterChip(text = "Verified", selected = false, onSelectedChange = {})
        }
        composeTestRule.onNodeWithText("Verified")
            .assertIsNotSelected()
    }

    // ── Input error ───────────────────────────────────────────────────────

    @Test
    fun input_error_state_carries_error_description() {
        val helper = "Only letters, digits and underscore"
        composeTestRule.setContent {
            PhantomInput(
                value = "@invalid#name",
                onValueChange = {},
                isError = true,
                helperText = helper,
            )
        }
        composeTestRule.onNode(hasErrorDescription(helper))
            .assertExists()
    }

    @Test
    fun input_no_error_has_no_error_semantic() {
        composeTestRule.setContent {
            PhantomInput(
                value = "@alice",
                onValueChange = {},
                isError = false,
            )
        }
        composeTestRule.onAllNodes(hasAnyErrorSemantic())
            .assertCountEquals(0)
    }
}

// ── Matchers ─────────────────────────────────────────────────────────────

private fun hasSwitchRole(): SemanticsMatcher =
    SemanticsMatcher("Role == Switch") { node ->
        val role = node.config.getOrNull(SemanticsProperties.Role)
        role?.toString() == "Switch"
    }

private fun hasTabRole(): SemanticsMatcher =
    SemanticsMatcher("Role == Tab") { node ->
        val role = node.config.getOrNull(SemanticsProperties.Role)
        role?.toString() == "Tab"
    }

private fun hasErrorDescription(expected: String): SemanticsMatcher =
    SemanticsMatcher("Error description == \"$expected\"") { node ->
        val e = node.config.getOrNull(SemanticsProperties.Error)
        e == expected
    }

private fun hasAnyErrorSemantic(): SemanticsMatcher =
    SemanticsMatcher("Has any Error semantic") { node ->
        node.config.getOrNull(SemanticsProperties.Error) != null
    }

private fun <T> androidx.compose.ui.semantics.SemanticsConfiguration.getOrNull(
    key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>,
): T? = try {
    this[key]
} catch (_: IllegalStateException) {
    null
}
