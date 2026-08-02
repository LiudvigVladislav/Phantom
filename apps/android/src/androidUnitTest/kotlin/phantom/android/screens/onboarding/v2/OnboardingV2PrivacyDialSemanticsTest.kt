// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Privacy dial semantics — 3-segment tab bar + tier card CTAs +
 * Role.Tab / contentDescription / selected-state contract.
 *
 * Extracted from `OnboardingV2SemanticsTest.kt` in round-4 REDLINE
 * §P2-1 test-file split. Shared matchers (`hasTabRole`,
 * `hasContentDescriptionExact`, `hasSelectedState`, etc.) live in
 * `OnboardingV2SemanticsMatchers.kt`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingV2PrivacyDialSemanticsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Commit 4 · Privacy dial semantics ──────────────────────────────

    @Test
    fun privacy_dial_standard_segment_selects_standard_mode() {
        var lastState: OnboardingFormStateV2? = null
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.PrivacyLevelStepV2(
                formState = OnboardingFormStateV2(
                    privacyMode = phantom.core.transport.PrivacyMode.Private,
                ),
                dotsIndex = 2,
                onFormStateChange = { lastState = it },
                onContinueClick = {},
                onGhostLockClick = {},
            )
        }
        composeTestRule.waitForIdle()
        // The Standard segment is discoverable by its click-action
        // label. TalkBack users navigate here via that label.
        composeTestRule.onNode(
            SemanticsMatcher("Has click action + Standard label") { node ->
                val label = node.config.getOrNull(SemanticsActions.OnClick)?.label
                label == "Select Standard privacy"
            },
        ).performClick()
        composeTestRule.waitForIdle()
        assert(lastState?.privacyMode == phantom.core.transport.PrivacyMode.Standard) {
            "Expected Standard mode after Standard segment tap, got ${lastState?.privacyMode}"
        }
    }

    @Test
    fun privacy_dial_private_segment_selects_private_mode() {
        var lastState: OnboardingFormStateV2? = null
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.PrivacyLevelStepV2(
                formState = OnboardingFormStateV2(),
                dotsIndex = 2,
                onFormStateChange = { lastState = it },
                onContinueClick = {},
                onGhostLockClick = {},
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNode(
            SemanticsMatcher("Has click action + Private label") { node ->
                val label = node.config.getOrNull(SemanticsActions.OnClick)?.label
                label == "Select Private privacy"
            },
        ).performClick()
        composeTestRule.waitForIdle()
        assert(lastState?.privacyMode == phantom.core.transport.PrivacyMode.Private) {
            "Expected Private mode after Private segment tap, got ${lastState?.privacyMode}"
        }
    }

    @Test
    fun privacy_dial_ghost_segment_fires_lock_click_and_does_not_change_mode() {
        var formChangeCount = 0
        var ghostLockCount = 0
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.PrivacyLevelStepV2(
                formState = OnboardingFormStateV2(),
                dotsIndex = 2,
                onFormStateChange = { formChangeCount++ },
                onContinueClick = {},
                onGhostLockClick = { ghostLockCount++ },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNode(
            SemanticsMatcher("Has click action + Ghost lock label") { node ->
                val label = node.config.getOrNull(SemanticsActions.OnClick)?.label
                label == "Ghost Mode requires Phantom Pro"
            },
        ).performClick()
        composeTestRule.waitForIdle()
        // Ghost segment tap MUST NOT change privacyMode — it can only
        // OPEN the Pricing sheet. Setting Ghost silently from
        // onboarding would let a user commit to a Pro-locked mode
        // without a subscription and break the runtime downstream.
        assert(formChangeCount == 0) {
            "Ghost segment tap must NOT change form state; got $formChangeCount changes."
        }
        assert(ghostLockCount == 1) {
            "Ghost segment tap must fire onGhostLockClick exactly once; got $ghostLockCount."
        }
    }

    @Test
    fun privacy_dial_unlock_cta_visible_only_when_ghost_selected() {
        // Only when Ghost is the currently-selected tier is the
        // "Unlock with Phantom Pro" CTA rendered. On Standard /
        // Private the CTA MUST NOT exist in the semantic tree.
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.PrivacyLevelStepV2(
                formState = OnboardingFormStateV2(
                    privacyMode = phantom.core.transport.PrivacyMode.Standard,
                ),
                dotsIndex = 2,
                onFormStateChange = {},
                onContinueClick = {},
                onGhostLockClick = {},
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText("Unlock with Phantom Pro").assertCountEquals(0)
    }

    @Test
    fun privacy_dial_unlock_cta_fires_ghost_lock_click_when_present() {
        // Simulating the Ghost-selected state visually (though runtime
        // would never actually persist Ghost from onboarding). The
        // Ghost-tier card is what renders under this synthetic state,
        // and its Unlock CTA must funnel through onGhostLockClick.
        var ghostLockCount = 0
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.PrivacyLevelStepV2(
                formState = OnboardingFormStateV2(
                    privacyMode = phantom.core.transport.PrivacyMode.Ghost,
                ),
                dotsIndex = 2,
                onFormStateChange = {},
                onContinueClick = {},
                onGhostLockClick = { ghostLockCount++ },
            )
        }
        composeTestRule.waitForIdle()
        // Direct semantics-action invocation — same reasoning as the
        // pricing sheet CTA tests below: coordinate hit-testing on a
        // Row nested inside a card that may lay out below the
        // Pixel-5 viewport (411 × 891 dp) misses the click, but the
        // OnClick semantic is present and works fine when invoked
        // directly.
        composeTestRule.onNodeWithText("Unlock with Phantom Pro")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
        assert(ghostLockCount == 1) {
            "Unlock CTA must fire onGhostLockClick exactly once; got $ghostLockCount."
        }
    }

    // ── Round-2 REDLINE on Commit 4 §P2-6 · Privacy segments Role.Tab

    @Test
    fun privacy_segments_carry_role_tab_content_description_and_selected_state() {
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.PrivacyLevelStepV2(
                formState = OnboardingFormStateV2(
                    privacyMode = phantom.core.transport.PrivacyMode.Private,
                ),
                dotsIndex = 2,
                onFormStateChange = {},
                onContinueClick = {},
                onGhostLockClick = {},
            )
        }
        composeTestRule.waitForIdle()

        // Exactly three Role.Tab nodes on the Privacy step — one per
        // segment. If a future refactor drops the Tab role (e.g.,
        // reverts to Role.Button), this count goes to 0.
        composeTestRule.onAllNodes(hasTabRole()).assertCountEquals(3)

        // Each segment's accessible name matches its short label.
        composeTestRule.onNode(
            hasTabRole() and hasContentDescriptionExact("STANDARD"),
        ).assertExists()
        composeTestRule.onNode(
            hasTabRole() and hasContentDescriptionExact("PRIVATE"),
        ).assertExists()
        composeTestRule.onNode(
            hasTabRole() and hasContentDescriptionExact("GHOST"),
        ).assertExists()

        // Selected-state reflects `privacyMode = Private` — Private
        // segment is Selected, Standard + Ghost are Not selected.
        composeTestRule.onNode(hasContentDescriptionExact("PRIVATE"))
            .assert(hasSelectedState(true))
        composeTestRule.onNode(hasContentDescriptionExact("STANDARD"))
            .assert(hasSelectedState(false))
        composeTestRule.onNode(hasContentDescriptionExact("GHOST"))
            .assert(hasSelectedState(false))
    }

    // Round-3 REDLINE on Commit 4 §P2-6 cleanup: the weak round-1
    // `pricing_sheet_panel_body_tap_target_is_not_a_close_pricing_action`
    // test was removed. It asserted `label != "Close pricing"` which
    // passed even when `label == null` — it also referenced the
    // pre-round-2 "no-op clickable" absorber that no longer exists.
    // Its stronger round-2 replacement
    // (`pricing_sheet_panel_body_exposes_no_interactive_semantics_of_its_own`,
    // asserting the OnClick action is EXACTLY null) already lives
    // higher up in this file.

}
