// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * C6-a round-8 mini-round REDLINE — Compose tests for
 * [OnboardingStartupErrorScreen].
 *
 * Mini-round §P2 pins:
 *   - Screen renders STABLE copy only: "Something went wrong"
 *     + "The app could not start …" + "Retry". NO "Reason:"
 *     text, NO internal reason label (round-7 shape leaked
 *     "Reason: identity load failed" etc).
 *   - Retry has a click action.
 *   - Retry click invokes onRetry.
 *   - `enabled = false` disables the Retry button (single-
 *     flight behavioural pin — MainActivity wires this to
 *     `!startupInFlight`).
 *   - Double-tap when the caller-guarded onRetry sets an
 *     in-flight flag SYNCHRONOUSLY fires onRetry EXACTLY ONCE.
 *   - Screen CONTAINS NO text "Identity repair required" —
 *     that phrase is reserved for the proven-corruption screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingV2StartupErrorScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun screen_renders_stable_copy_and_retry_only() {
        composeTestRule.setContent {
            OnboardingStartupErrorScreen(
                reason = TransientReason.InitMessagingThrew,
                onRetry = { },
            )
        }
        composeTestRule.onNodeWithText("Something went wrong").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "The app could not start", substring = true,
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun screen_has_content_description_for_semantics_pinning() {
        composeTestRule.setContent {
            OnboardingStartupErrorScreen(
                reason = TransientReason.LoadIdentityThrew,
                onRetry = { },
            )
        }
        composeTestRule.onNodeWithContentDescription("OnboardingStartupErrorScreen")
            .assertIsDisplayed()
    }

    @Test
    fun retry_button_has_click_action_when_enabled() {
        composeTestRule.setContent {
            OnboardingStartupErrorScreen(
                reason = TransientReason.LoadIdentityThrew,
                enabled = true,
                onRetry = { },
            )
        }
        composeTestRule.onNodeWithText("Retry").assertHasClickAction().assertIsEnabled()
    }

    @Test
    fun retry_button_click_fires_onRetry_exactly_once() {
        var count = 0
        composeTestRule.setContent {
            OnboardingStartupErrorScreen(
                reason = TransientReason.MarkerReadThrew,
                onRetry = { count++ },
            )
        }
        composeTestRule.onNodeWithText("Retry").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, count)
    }

    // ── Mini-round §P2 pin — NO "Reason:" or internal label ──────

    @Test
    fun screen_contains_NO_Reason_prefix_or_internal_label_for_any_reason() {
        // Round-7 shape rendered "Reason: identity load failed"
        // etc. Mini-round §P2 pin removes the "Reason:" line and
        // the internal label. This test iterates every
        // TransientReason via a mutableState swap; if ANY of them
        // leaks either "Reason:" or the internal reason names
        // ("identity load", "messaging init", etc), the
        // assertion fires.
        val reasonState = androidx.compose.runtime.mutableStateOf(
            TransientReason.MarkerReadThrew,
        )
        composeTestRule.setContent {
            OnboardingStartupErrorScreen(
                reason = reasonState.value,
                onRetry = { },
            )
        }
        for (reason in TransientReason.entries) {
            reasonState.value = reason
            composeTestRule.waitForIdle()
            // No "Reason:" prefix.
            composeTestRule.onAllNodesWithText(
                "Reason", substring = true, ignoreCase = true,
            ).assertCountEquals(0)
            // No enum-derived phrases that leaked in round-7.
            composeTestRule.onAllNodesWithText(
                "identity load", substring = true, ignoreCase = true,
            ).assertCountEquals(0)
            composeTestRule.onAllNodesWithText(
                "messaging init", substring = true, ignoreCase = true,
            ).assertCountEquals(0)
            composeTestRule.onAllNodesWithText(
                "migration check", substring = true, ignoreCase = true,
            ).assertCountEquals(0)
        }
    }

    // ── Round-8 §P0 load-bearing pin: no repair text on transient path

    @Test
    fun screen_contains_NO_Identity_repair_required_text_for_any_reason() {
        val reasonState = androidx.compose.runtime.mutableStateOf(
            TransientReason.MarkerReadThrew,
        )
        composeTestRule.setContent {
            OnboardingStartupErrorScreen(
                reason = reasonState.value,
                onRetry = { },
            )
        }
        for (reason in TransientReason.entries) {
            reasonState.value = reason
            composeTestRule.waitForIdle()
            composeTestRule.onAllNodesWithText("Identity repair required")
                .assertCountEquals(0)
            composeTestRule.onAllNodesWithText(
                "repair", substring = true, ignoreCase = true,
            ).assertCountEquals(0)
        }
    }

    // ── Mini-round §P2 pin — Retry disabled during startup-run ────

    @Test
    fun retry_button_is_disabled_when_enabled_param_false() {
        composeTestRule.setContent {
            OnboardingStartupErrorScreen(
                reason = TransientReason.LoadIdentityThrew,
                enabled = false,
                onRetry = { },
            )
        }
        composeTestRule.onNodeWithText("Retry").assertIsNotEnabled()
    }

    @Test
    fun disabled_retry_does_not_fire_onRetry_on_tap() {
        var count = 0
        composeTestRule.setContent {
            OnboardingStartupErrorScreen(
                reason = TransientReason.LoadIdentityThrew,
                enabled = false,
                onRetry = { count++ },
            )
        }
        composeTestRule.onNodeWithText("Retry").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            0, count,
            "Disabled Retry MUST NOT fire onRetry on tap.",
        )
    }

    // ── Mini-round §P2 pin — double-tap → single startup attempt ─

    @Test
    fun double_tap_Retry_with_synchronous_inFlight_flag_fires_onRetry_exactly_once() {
        // Simulates MainActivity's exact contract: the caller
        // sets `startupInFlight = true` SYNCHRONOUSLY inside
        // the callback BEFORE the retry counter increments. On
        // the very next recomposition (same frame), the button
        // becomes disabled. A second tap on the same frame
        // MUST NOT re-fire onRetry.
        var inFlight by mutableStateOf(false)
        var attemptCount = 0
        composeTestRule.setContent {
            OnboardingStartupErrorScreen(
                reason = TransientReason.LoadIdentityThrew,
                enabled = !inFlight,
                onRetry = {
                    // MainActivity's callback shape:
                    //   if (!startupInFlight) {
                    //       startupInFlight = true
                    //       retryTick += 1
                    //   }
                    if (!inFlight) {
                        inFlight = true
                        attemptCount++
                    }
                },
            )
        }
        // Two rapid taps.
        composeTestRule.onNodeWithText("Retry").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Retry").performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            1, attemptCount,
            "Mini-round §P2 pin: double-tap Retry MUST fire onRetry EXACTLY ONCE. " +
                "The synchronous `startupInFlight = true` inside the callback plus " +
                "`enabled = !startupInFlight` combine to disable the button before " +
                "the second tap can be processed.",
        )
        // Button is now disabled.
        composeTestRule.onNodeWithText("Retry").assertIsNotEnabled()
    }
}
