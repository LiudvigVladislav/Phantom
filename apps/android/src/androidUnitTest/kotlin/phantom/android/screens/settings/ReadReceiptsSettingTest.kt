// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.android.screens.settings

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.android.privacy.ReadReceiptPreference
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class ReadReceiptsSettingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity

    @Before fun prepare() {
        context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun default_retains_standard_behavior_but_never_overrides_privacy() {
        assertTrue(ReadReceiptPreference.allowed(context, true))
        assertFalse(ReadReceiptPreference.allowed(context, false))
        assertTrue(ReadReceiptPreference.write(context, false))
        assertFalse(ReadReceiptPreference.allowed(context.applicationContext, true))
    }

    @Test fun legacy_opt_out_and_malformed_value_fail_closed() {
        val prefs = context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("read_receipts", false).commit()
        assertFalse(ReadReceiptPreference.enabled(context))
        prefs.edit().putString("read_receipts", "invalid").commit()
        assertFalse(ReadReceiptPreference.enabled(context))
    }

    @Test fun dedicated_dialog_changes_persisted_preference_without_changing_privacy() {
        compose.setContent { ReadReceiptsSetting(privacyAllows = true, onError = { error("write failed") }) }
        compose.onNodeWithText("Read Receipts").performClick()
        compose.onNode(isToggleable()).assertIsOn().performClick()
        compose.waitUntil(5_000) { !ReadReceiptPreference.enabled(context) }
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Read Receipts").performClick()
        compose.onNode(isToggleable()).assertIsOff()
    }

    @Test fun restrictive_mode_explains_why_enabled_preference_does_not_send() {
        compose.setContent { ReadReceiptsSetting(privacyAllows = false, onError = {}) }
        compose.onNodeWithText("Read Receipts").performClick()
        compose.onNodeWithText("Your privacy mode currently blocks read receipts. This preference applies only when the privacy mode permits them.").assertExists()
        compose.onNode(isToggleable()).assertIsOn()
        assertFalse(ReadReceiptPreference.allowed(context, false))
    }

    @Test fun failed_write_reports_error_and_retains_observed_value() {
        var errors = 0
        compose.setContent { ReadReceiptsSetting(true, { errors++ }, write = { _, _ -> false }) }
        compose.onNodeWithText("Read Receipts").performClick()
        compose.onNode(isToggleable()).performClick()
        compose.waitUntil(5_000) { errors == 1 }
        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test @Config(qualifiers = "ru-w360dp-h640dp") fun russian_large_font_keeps_control_and_confirmation_reachable() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                ReadReceiptsSetting(false, {})
            }
        }
        compose.onNodeWithText("Отчёты о прочтении").performClick()
        compose.onNode(isToggleable()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ОК").assertIsDisplayed()
    }
}
