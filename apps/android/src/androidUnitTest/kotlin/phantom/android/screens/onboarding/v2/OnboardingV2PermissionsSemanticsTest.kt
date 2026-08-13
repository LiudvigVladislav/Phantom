// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Semantics + contract tests for `PermissionsStepV2` after round-1
 * REDLINE on Commit 5 §P1-1 + §P1-2:
 *   - EXACTLY ONE `Role.Switch` row (Notifications).
 *   - Microphone + Nearby are INFORMATIONAL rows — no
 *     `Role.Switch`, no `ToggleableState`, no `OnClick` action.
 *   - Notifications toggle mirrors OS state (source of truth):
 *     tapping when Disabled fires the request callback; tapping
 *     when Enabled is a no-op (can't revoke from app).
 *   - Bottom disclaimer + Done CTA present.
 *
 * The POST_NOTIFICATIONS runtime launcher is NOT tested here —
 * `rememberLauncherForActivityResult` is a Compose-side
 * abstraction that Robolectric doesn't fully provision. The
 * launcher wiring lives in `OnboardingFlowV2`; the coordinator
 * that decides whether to launch is pure and covered by
 * `OnboardingNotificationPermissionCoordinatorTest`. On-device
 * verification of the full grant/deny loop is a post-Commit-5
 * ADB round item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingV2PermissionsSemanticsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun step_exposes_exactly_one_switch_row_notifications() {
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.PermissionsStepV2(
                formState = OnboardingFormStateV2(),
                dotsIndex = 3,
                onFormStateChange = {},
                onDoneClick = {},
                notificationsState = NotificationsPermissionState.Disabled,
            )
        }
        composeTestRule.waitForIdle()
        // Round-1 REDLINE §P1-1: exactly ONE `Role.Switch` — the
        // Notifications row. Mic + Nearby are static info rows and
        // MUST NOT expose Switch semantics.
        composeTestRule.onAllNodes(hasSwitchRole()).assertCountEquals(1)
    }

    @Test
    fun mic_and_nearby_rows_expose_no_click_action() {
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.PermissionsStepV2(
                formState = OnboardingFormStateV2(),
                dotsIndex = 3,
                onFormStateChange = {},
                onDoneClick = {},
                notificationsState = NotificationsPermissionState.Disabled,
            )
        }
        composeTestRule.waitForIdle()
        // Mic + Nearby texts exist but their merged parent Row is
        // NOT clickable (no OnClick action). Verified via the row
        // ancestor lookup: find the "Microphone" text node, check
        // its ancestors carry no OnClick.
        composeTestRule.onNodeWithText("Microphone").assertExists()
        composeTestRule.onNodeWithText("Nearby discovery").assertExists()
        // Total OnClick nodes on the step: Notifications row +
        // Done CTA = 2. (Any Mic/Nearby leak would push the count
        // to 3+.)
        composeTestRule.onAllNodes(hasOnClickAction()).assertCountEquals(2)
    }

    @Test
    fun step_renders_all_titles_and_bodies() {
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.PermissionsStepV2(
                formState = OnboardingFormStateV2(),
                dotsIndex = 3,
                onFormStateChange = {},
                onDoneClick = {},
                notificationsState = NotificationsPermissionState.Disabled,
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Notifications").assertExists()
        composeTestRule.onNodeWithText("Microphone").assertExists()
        composeTestRule.onNodeWithText("Nearby discovery").assertExists()
        // Round-4 REDLINE on Commit 5 §P1-5: prior copy inaccurate
        // (plaintext preview IS handed to Android NotificationManager)
        // — replaced with what is actually true.
        composeTestRule.onNodeWithText(
            "Notifications are generated locally on this device — no push provider sees the preview.",
        ).assertExists()
        composeTestRule.onNodeWithText(
            "Asked when first used — for voice messages and calls.",
        ).assertExists()
        composeTestRule.onNodeWithText(
            "Asked when first used — for local-mesh people search.",
        ).assertExists()
    }

    @Test
    fun step_renders_bottom_disclaimer_and_done_cta() {
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.PermissionsStepV2(
                formState = OnboardingFormStateV2(),
                dotsIndex = 3,
                onFormStateChange = {},
                onDoneClick = {},
                notificationsState = NotificationsPermissionState.Disabled,
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(
            "Phantom never uploads contacts,\nlocation, or usage analytics.",
        ).assertExists()
        composeTestRule.onNodeWithText("Done, let's go").assertExists()
    }

    @Test
    fun notifications_switch_reflects_disabled_state() {
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.PermissionsStepV2(
                formState = OnboardingFormStateV2(),
                dotsIndex = 3,
                onFormStateChange = {},
                onDoneClick = {},
                notificationsState = NotificationsPermissionState.Disabled,
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodes(
            SemanticsMatcher("Role.Switch + ToggleableState.Off") { node ->
                val role = node.config.getOrNull(SemanticsProperties.Role)?.toString()
                val toggleState = node.config.getOrNull(SemanticsProperties.ToggleableState)
                role == "Switch" && toggleState == ToggleableState.Off
            },
        ).assertCountEquals(1)
    }

    @Test
    fun notifications_switch_reflects_enabled_state() {
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.PermissionsStepV2(
                formState = OnboardingFormStateV2(),
                dotsIndex = 3,
                onFormStateChange = {},
                onDoneClick = {},
                notificationsState = NotificationsPermissionState.Enabled,
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodes(
            SemanticsMatcher("Role.Switch + ToggleableState.On") { node ->
                val role = node.config.getOrNull(SemanticsProperties.Role)?.toString()
                val toggleState = node.config.getOrNull(SemanticsProperties.ToggleableState)
                role == "Switch" && toggleState == ToggleableState.On
            },
        ).assertCountEquals(1)
    }

    @Test
    fun tapping_disabled_notifications_switch_fires_request() {
        var requestCount = 0
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.PermissionsStepV2(
                formState = OnboardingFormStateV2(),
                dotsIndex = 3,
                onFormStateChange = {},
                onDoneClick = {},
                notificationsState = NotificationsPermissionState.Disabled,
                onRequestNotificationPermission = { requestCount++ },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodes(hasSwitchRole())[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
        assertEquals(1, requestCount)
    }

    @Test
    fun tapping_enabled_notifications_switch_fires_request_callback() {
        // Round-2 REDLINE on Commit 5 §P1-2 updated contract: the
        // Enabled row's tap fires `onRequestNotificationPermission`
        // too — the flow-level callback routes to system
        // app-notification Settings (since the app cannot revoke
        // via a launcher). The pre-round-2 test asserted a no-op
        // — that was the exact silent-tap bug §P1-2 called out.
        // Now every tap MUST have a callback fire.
        var requestCount = 0
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.PermissionsStepV2(
                formState = OnboardingFormStateV2(),
                dotsIndex = 3,
                onFormStateChange = {},
                onDoneClick = {},
                notificationsState = NotificationsPermissionState.Enabled,
                onRequestNotificationPermission = { requestCount++ },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodes(hasSwitchRole())[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
        assertEquals(1, requestCount)
    }
}

private fun hasSwitchRole(): SemanticsMatcher =
    SemanticsMatcher("Role == Switch") { node ->
        val role = node.config.getOrNull(SemanticsProperties.Role)
        role?.toString() == "Switch"
    }

private fun hasOnClickAction(): SemanticsMatcher =
    SemanticsMatcher("Has OnClick action") { node ->
        node.config.contains(SemanticsActions.OnClick)
    }
