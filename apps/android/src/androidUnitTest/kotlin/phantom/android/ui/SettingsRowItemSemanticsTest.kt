// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.*
import org.robolectric.RuntimeEnvironment
import phantom.android.locale.AppLanguage
import phantom.android.locale.AppLanguageStore
import phantom.android.locale.LanguagePicker
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsRowItemSemanticsTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun overflowOracleRejectsAClippedRussianLabel() {
        RuntimeEnvironment.setFontScale(2f)
        composeTestRule.setContent {
            Text("Применить", modifier = Modifier.width(12.dp), softWrap = false)
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeTestRule.onNodeWithText("Применить").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        assertTrue(layouts.isNotEmpty())
        assertTrue(layouts.all { it.hasVisualOverflow })
    }

    @Test
    @Config(qualifiers = "ru-rRU")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun russianLanguagePickerLabelsDoNotOverflowAtLargeFontScale() {
        RuntimeEnvironment.setFontScale(2f)
        composeTestRule.setContent {
            LanguagePicker(onDismiss = {}, onError = {})
        }
        listOf("Язык", "Русский", "Английский", "Применить", "Отмена").forEach { label ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            composeTestRule.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                it(layouts)
            }
            assertTrue(layouts.isNotEmpty(), label)
            assertTrue(layouts.all { it.layoutInput.density.fontScale == 2f }, "Font scale was not applied: $label")
            assertTrue(layouts.none { it.hasVisualOverflow }, "$label: ${layouts.map { "size=${it.size} paragraph=${it.multiParagraph.width}x${it.multiParagraph.height} density=${it.layoutInput.density} overflowWidth=${it.didOverflowWidth} overflowHeight=${it.didOverflowHeight}" }}\n${composeTestRule.onAllNodes(isRoot(), useUnmergedTree = true)[1].printToString(maxDepth = 10)}")
        }
    }

    @Test
    fun languagePickerOffersOnlyTwoLanguagesAndDefersChangesUntilApply() {
        val context = RuntimeEnvironment.getApplication()
        AppLanguageStore.set(context, AppLanguage.SYSTEM)
        var open by mutableStateOf(true)
        var failed = false
        composeTestRule.setContent {
            if (open) LanguagePicker(onDismiss = { open = false }, onError = { failed = true })
        }
        composeTestRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertCountEquals(2)
        composeTestRule.onNodeWithText("System default").assertDoesNotExist()
        composeTestRule.onNodeWithText("English").assertIsSelected()
        composeTestRule.onNodeWithText("Русский").performClick().assertIsSelected()
        composeTestRule.onNodeWithText("English").assertIsNotSelected()
        composeTestRule.runOnIdle { assertEquals(AppLanguage.SYSTEM, AppLanguageStore.selected(context)) }
        composeTestRule.onNodeWithText("Apply").performClick()
        composeTestRule.runOnIdle {
            assertEquals(AppLanguage.RUSSIAN, AppLanguageStore.selected(context))
            assertFalse(open)
            assertFalse(failed)
        }
    }

    @Test
    @Config(qualifiers = "ru-rRU")
    fun russianSystemPreselectsRussianButDoesNotPersistOnOpening() {
        val context = RuntimeEnvironment.getApplication()
        AppLanguageStore.set(context, AppLanguage.SYSTEM)
        composeTestRule.setContent { LanguagePicker(onDismiss = {}, onError = {}) }
        composeTestRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertCountEquals(2)
        composeTestRule.onNodeWithText("Как в системе").assertDoesNotExist()
        composeTestRule.onNodeWithText("Русский").assertIsSelected()
        composeTestRule.onNodeWithText("Английский").assertIsNotSelected()
        composeTestRule.runOnIdle { assertEquals(AppLanguage.SYSTEM, AppLanguageStore.selected(context)) }
        composeTestRule.onNodeWithText("Английский").performClick()
        composeTestRule.onNodeWithText("Применить").performClick()
        composeTestRule.runOnIdle { assertEquals(AppLanguage.ENGLISH, AppLanguageStore.selected(context)) }
    }

    @Test
    fun applyingTheAlreadyDisplayedLanguageStillMakesItAnExplicitChoice() {
        val context = RuntimeEnvironment.getApplication()
        AppLanguageStore.set(context, AppLanguage.SYSTEM)
        composeTestRule.setContent { LanguagePicker(onDismiss = {}, onError = {}) }
        composeTestRule.onNodeWithText("English").assertIsSelected()
        composeTestRule.onNodeWithText("Apply").performClick()
        composeTestRule.runOnIdle { assertEquals(AppLanguage.ENGLISH, AppLanguageStore.selected(context)) }
    }

    @Test
    fun cancellingLanguagePickerKeepsTheCurrentChoice() {
        val context = RuntimeEnvironment.getApplication()
        AppLanguageStore.set(context, AppLanguage.SYSTEM)
        var dismissed = false
        composeTestRule.setContent { LanguagePicker(onDismiss = { dismissed = true }, onError = {}) }
        composeTestRule.onNodeWithText("English").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.runOnIdle {
            assertTrue(dismissed)
            assertEquals(AppLanguage.SYSTEM, AppLanguageStore.selected(context))
        }
    }

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
