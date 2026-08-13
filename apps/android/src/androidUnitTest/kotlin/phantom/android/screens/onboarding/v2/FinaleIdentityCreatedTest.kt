// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.android.screens.onboarding.v2.steps.FinaleConfirmationStepV2

/**
 * Onboarding-stabilization block 2026-08-11 — focused tests for the
 * simplified Finale confirmation surface.
 *
 * Pins the architect verdict on the prior dual-key-labels shape:
 * Finale no longer exposes raw key material, Copy affordances, or
 * short IDs in the main onboarding flow. The surface confirms
 * identity creation and offers a single Continue CTA; anything
 * cryptographic surfaces later under Profile → Advanced.
 *
 * Contract sheet:
 * `docs/tracks/android-onboarding/onboarding-stabilization-block-2026-08-11.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FinaleIdentityCreatedTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun renders_identity_created_title_and_on_device_explanation_and_continue_only() {
        composeTestRule.setContent {
            FinaleConfirmationStepV2(onContinueClick = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Identity created").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Your identity is created and stored on this device.",
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue").assertIsDisplayed()
    }

    @Test
    fun continue_click_fires_exactly_once() {
        var clicks = 0
        composeTestRule.setContent {
            FinaleConfirmationStepV2(onContinueClick = { clicks += 1 })
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Continue").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, clicks, "Continue must fire onContinueClick exactly once")
    }

    @Test
    fun surface_contains_no_raw_key_material_or_copy_affordance() {
        // Explicit negative pins — a regression that re-adds the
        // per-key cards, short IDs, or Copy buttons would fail this
        // test red. We check the load-bearing tokens the prior
        // shape used: "Copy", "short ID", "Ed25519", "X25519",
        // "fingerprint", and any hex-looking uppercase key badge
        // ("ED25519", "X25519 · CREATED", etc.).
        composeTestRule.setContent {
            FinaleConfirmationStepV2(onContinueClick = {})
        }
        composeTestRule.waitForIdle()

        for (banned in listOf(
            "Copy", "short ID", "Ed25519", "X25519",
            "fingerprint", "Public key",
        )) {
            composeTestRule.onAllNodesWithText(
                text = banned, substring = true, ignoreCase = true,
            ).assertCountEquals(
                expectedSize = 0,
            )
        }
    }
}
