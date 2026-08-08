// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C6-a round-3 REDLINE §P2 pin — Compose-rendered tests for
 * [OnboardingRepairRequiredScreen] and its integration with
 * `OnboardingFlowV2Internal`.
 *
 * Round-2 shipped a holder-only end-to-end test that DIRECTLY
 * called `holder.resetFromRepairRequired()` (skipping the
 * Compose UI entirely). Architect flagged: "тест не тестирует
 * экран … coordinate-click по CTA выполняет заявленное действие,
 * а Back действительно выводит из состояния".
 *
 * The tests here render REAL Compose UI, tap the CTA via
 * `performClick()`, dispatch a system Back via the
 * `OnBackPressedDispatcher`, and verify the exact behaviour
 * fires in both cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingV2RepairRequiredScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // ── Renders ──────────────────────────────────────────────────────

    @Test
    fun screen_renders_error_heading_body_and_exit_button() {
        composeTestRule.setContent {
            OnboardingRepairRequiredScreen(onExit = { /* ignored */ })
        }
        composeTestRule.onNodeWithText("Identity repair required").assertIsDisplayed()
        // Exact match on the button label — the body copy also
        // mentions "Exit onboarding" as instruction text, so a
        // substring match would return two nodes.
        composeTestRule.onNodeWithText("Exit onboarding").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Your identity was created on disk but the signing key material",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun screen_has_content_description_for_semantics_pinning() {
        // Distinct contentDescription tag ("OnboardingRepairRequiredScreen")
        // makes the screen findable from an outer integration test
        // that drives the full flow — outer test doesn't need to
        // know the exact copy text, only the semantic tag.
        composeTestRule.setContent {
            OnboardingRepairRequiredScreen(onExit = { /* ignored */ })
        }
        composeTestRule.onNodeWithContentDescription("OnboardingRepairRequiredScreen")
            .assertIsDisplayed()
    }

    @Test
    fun exit_button_has_click_action() {
        composeTestRule.setContent {
            OnboardingRepairRequiredScreen(onExit = { /* ignored */ })
        }
        composeTestRule.onNodeWithText("Exit onboarding").assertHasClickAction()
    }

    // ── CTA click fires onExit ───────────────────────────────────────

    @Test
    fun cta_click_invokes_onExit_exactly_once() {
        var exitCount = 0
        composeTestRule.setContent {
            OnboardingRepairRequiredScreen(onExit = { exitCount++ })
        }
        composeTestRule.onNodeWithText("Exit onboarding").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            1, exitCount,
            "Tap on the Exit onboarding CTA MUST fire onExit exactly once — the round-2 " +
                "shape wired the button to holder.resetFromRepairRequired() which " +
                "produced a visible retap loop instead of exiting.",
        )
    }

    // ── System Back fires onExit via the screen's own BackHandler ────

    @Test
    fun system_back_press_invokes_onExit_via_screen_backhandler() {
        var exitCount = 0
        composeTestRule.setContent {
            OnboardingRepairRequiredScreen(onExit = { exitCount++ })
        }
        // Round-3 §P1 pin: the screen installs its OWN BackHandler
        // wired to onExit. Prior round-2 shape relied on the flow's
        // outer BackHandler routing to unlock Back — which it
        // silently absorbed via
        // `isBackNavigationLockedByFinalize(controller.state) == true`.
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        assertEquals(
            1, exitCount,
            "System Back MUST invoke onExit via the screen's own BackHandler — the " +
                "round-2 shape silently absorbed Back and claimed 'system Back becomes " +
                "a safe exit' incorrectly.",
        )
    }
}

/**
 * Integration test — renders `OnboardingFlowV2Internal` inside a
 * real `ComponentActivity` with a controller that returns a
 * broken record (null signing key), drives the flow through
 * Done, and verifies the Repair-required screen appears + the
 * Exit CTA calls `Activity.finishAffinity()` on the real
 * Activity (asserted via `activity.isFinishing == true`).
 *
 * Round-3 REDLINE §P2 pin: this is the "Compose-тест реального
 * host flow" the architect asked for — it drives the SAME flow
 * a user does and asserts:
 *   - Repair screen becomes visible after Done.
 *   - Controller state is Complete (with the broken record) —
 *     architect wanted this checked explicitly.
 *   - Exit CTA click fires Activity.finishAffinity() → the
 *     Activity's `isFinishing` flag flips to true.
 *   - System Back does the same.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingV2RepairRequiredIntegrationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val brokenRecord = phantom.core.identity.IdentityRecord(
        id = "alice-id",
        username = "alice",
        publicKeyHex = "aa".repeat(32),
        dhPrivateKeyHex = "bb".repeat(32),
        createdAt = 0L,
        signingPublicKeyHex = null,  // broken: no Ed25519 signing key
        signingPrivateKeyHex = null,
    )
    private val syntheticKeyPair = phantom.core.identity.IdentityKeyPair(
        publicKey = phantom.core.identity.PublicKey(ByteArray(32) { it.toByte() }),
        privateKey = phantom.core.identity.PrivateKey(ByteArray(32) { (it + 100).toByte() }),
    )

    @Test
    fun flow_with_broken_controller_reaches_RepairRequired_and_exit_finishes_activity() {
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> brokenRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        composeTestRule.setContent {
            OnboardingFlowV2Internal(
                onComplete = { /* would advance MainActivity — irrelevant here */ },
                controller = controller,
            )
        }

        // Drive through five steps. Welcome / How / Privacy use a
        // "Continue" CTA. Identity needs a valid username + Continue.
        // Permissions has a "Done" CTA.
        // Welcome → How  (CTA: "Get started")
        composeTestRule.onNodeWithText("Get started").performClick()
        composeTestRule.waitForIdle()
        // How → Identity  (CTA: "Create my identity")
        composeTestRule.onNodeWithText("Create my identity").performClick()
        composeTestRule.waitForIdle()
        // Identity: type username + Continue
        typeIntoFirstTextField("alice")
        composeTestRule.onNodeWithText("Continue").performClick()
        composeTestRule.waitForIdle()
        // Privacy → Permissions  (CTA: "Continue")
        composeTestRule.onNodeWithText("Continue").performClick()
        composeTestRule.waitForIdle()
        // Permissions: Done, let's go — fires runFinalize; broken
        // record returns null hex → MissingKeyMaterial → holder →
        // MissingKeyRepairRequired → repair screen visible.
        composeTestRule.onNodeWithText("Done, let's go").performClick()
        composeTestRule.waitForIdle()

        // Repair screen visible.
        composeTestRule.onNodeWithContentDescription("OnboardingRepairRequiredScreen")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Identity repair required").assertIsDisplayed()

        // Controller state check (architect asked for this): the
        // controller reached Complete with the broken record.
        assertTrue(
            controller.state is FinalizeState.Complete,
            "controller should be Complete after runFinalize; " +
                "got ${controller.state}",
        )

        // Exit CTA click fires Activity.finishAffinity() → the
        // Activity's isFinishing flag flips to true. This is the
        // HONEST safe exit (round-3 §P1 pin — replaces the
        // round-2 button that only reset holder without exiting).
        val activity = composeTestRule.activity
        assertEquals(
            false, activity.isFinishing,
            "sanity: activity is not finishing before Exit tap",
        )

        composeTestRule.onNodeWithText("Exit onboarding").performClick()
        composeTestRule.waitForIdle()

        assertTrue(
            activity.isFinishing,
            "Activity.isFinishing MUST become true after Exit onboarding tap — round-3 " +
                "§P1 pin requires the CTA to actually exit the flow, not just reset " +
                "the holder into a visible retap loop.",
        )
    }

    @Test
    fun flow_repair_screen_system_back_also_finishes_activity() {
        val controller = OnboardingFinalizeController(
            savePrivacyMode = { /* ok */ },
            createOrLoad = { _ -> brokenRecord to syntheticKeyPair },
            initMessaging = { _, _ -> /* ok */ },
        )
        composeTestRule.setContent {
            OnboardingFlowV2Internal(
                onComplete = { },
                controller = controller,
            )
        }
        composeTestRule.onNodeWithText("Get started").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Create my identity").performClick()
        composeTestRule.waitForIdle()
        typeIntoFirstTextField("alice")
        composeTestRule.onNodeWithText("Continue").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Continue").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Done, let's go").performClick()
        composeTestRule.waitForIdle()

        // Repair screen visible; system Back MUST behave exactly
        // like the Exit CTA — round-2 claimed this, round-3 tests
        // it end-to-end via the composable rendered in a real
        // Activity.
        val activity = composeTestRule.activity
        assertEquals(false, activity.isFinishing, "sanity: activity not finishing pre-back")

        composeTestRule.activityRule.scenario.onActivity { act ->
            act.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        assertTrue(
            activity.isFinishing,
            "System Back on the repair screen MUST call Activity.finishAffinity() — " +
                "the round-2 shape silently absorbed it via " +
                "isBackNavigationLockedByFinalize(controller.state) == true.",
        )
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun typeIntoFirstTextField(text: String) {
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasSetTextAction()).performTextInput(text)
        composeTestRule.waitForIdle()
    }
}
