// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.android.screens.onboarding.v2.steps.FinaleConfirmationStepV2
import phantom.android.screens.onboarding.v2.steps.IdentityKeyStepV2
import phantom.android.screens.onboarding.v2.steps.PrivacyLevelStepV2
import phantom.android.screens.onboarding.v2.steps.WelcomeStepV2
import phantom.core.transport.PrivacyMode

/**
 * Round-7 REDLINE on Commit 5 §P1 pin — reachability tests are
 * load-bearing.
 *
 * Prior round-6 shape started Identity with `username = "alice"`
 * (Continue enabled from the first frame) and never scrolled
 * anything — every CTA was in the fixed-bottom band. A
 * regression that removed every `verticalScroll()` from every
 * step would have kept those four tests GREEN.
 *
 * Round-7 pins:
 *   - Identity starts EMPTY. Test scrolls to the username input
 *     via `performScrollTo`, types "alice", waits, then asserts
 *     Continue is enabled + click fires. If the scrollable body
 *     were removed, `performScrollTo` would fail because the
 *     input has no scrollable ancestor.
 *   - Welcome, Privacy, Finale each `performScrollTo` a
 *     below-fold anchor element (the footer / bullet / copy
 *     button) BEFORE clicking the CTA. If the scrollable body
 *     were removed, the below-fold anchor tests fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingV2ScrollableCtaReachabilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun welcome_scrolls_to_footer_then_taps_cta_at_320dp_fs2() {
        var clicked = false
        renderNarrowFs2 {
            WelcomeStepV2(onContinueClick = { clicked = true })
        }
        // Below-fold anchor: the footer copy sits at the bottom
        // of the scrollable body. If the body isn't scrollable,
        // `performScrollTo` fails.
        composeTestRule.onNodeWithText(
            "No phone number required",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        // CTA lives in the fixed-bottom band — always visible.
        composeTestRule.onNodeWithText("Get started")
            .assertIsDisplayed().performClick()
        check(clicked) { "Welcome 'Get started' CTA did not fire" }
    }

    @Test
    fun identity_starts_empty_scrolls_to_input_types_username_then_taps_cta_at_320dp_fs2() {
        // Round-7 §P1 pin: fresh-user path.
        //   1. Start with `username = ""` → Continue MUST be disabled.
        //   2. Scroll to the username input (which lives inside the
        //      scrollable body — a removed verticalScroll() would
        //      fail this step).
        //   3. Type a valid handle → Continue MUST enable.
        //   4. Assert Continue is displayed + click fires.
        var clicked = false
        val formHolder = androidx.compose.runtime.mutableStateOf(
            OnboardingFormStateV2(username = ""),
        )
        renderNarrowFs2 {
            IdentityKeyStepV2(
                formState = formHolder.value,
                dotsIndex = 1,
                onFormStateChange = { formHolder.value = it },
                onContinueClick = { clicked = true },
            )
        }
        // Continue starts disabled — the CTA's enabled flag is
        // driven by validateUsernameV2, which rejects "".
        composeTestRule.onNodeWithText("Continue").assertIsNotEnabled()
        // Locate the text input (BasicTextField exposes SetText).
        val inputMatcher = SemanticsMatcher.keyIsDefined(
            SemanticsActions.SetText,
        )
        composeTestRule.onAllNodes(inputMatcher).onFirst()
            .performScrollTo()
            .assertIsDisplayed()
            .performTextInput("alice")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Continue")
            .assertIsEnabled()
            .performClick()
        check(clicked) { "Identity 'Continue' did not fire after typing 'alice'" }
    }

    @Test
    fun privacy_scrolls_to_tier_bullet_then_taps_cta_at_320dp_fs2() {
        var clicked = false
        renderNarrowFs2 {
            PrivacyLevelStepV2(
                formState = OnboardingFormStateV2(privacyMode = PrivacyMode.Standard),
                dotsIndex = 2,
                onFormStateChange = {},
                onContinueClick = { clicked = true },
                onGhostLockClick = {},
            )
        }
        // Below-fold anchor: PrivacyTierCard body bullet copy for
        // Standard tier — lives inside the tier card body in the
        // scrollable body.
        composeTestRule.onNodeWithText(
            "Discoverable in Nearby",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue")
            .assertIsDisplayed().performClick()
        check(clicked) { "Privacy 'Continue' CTA did not fire" }
    }

    @Test
    fun finale_scrolls_to_copy_button_then_taps_cta_at_320dp_fs2() {
        var clicked = false
        val syntheticHex = "9f3a8b7d5c1e4f8a2b6d9e7c0a3f5b8d1e4c7a9f3a8b7d5c1e4f8a2b6d9e7c0a3f5b8d1e"
        renderNarrowFs2 {
            FinaleConfirmationStepV2(
                formState = OnboardingFormStateV2(signingPublicKeyHex = syntheticHex),
                onContinueClick = { clicked = true },
                onKeyCopied = {},
            )
        }
        // Below-fold anchor: the Copy public key button lives at
        // the bottom of the scrollable body. If the body isn't
        // scrollable, this fails.
        composeTestRule.onNodeWithText("Copy public key")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue")
            .assertIsDisplayed().performClick()
        check(clicked) { "Finale 'Continue' CTA did not fire" }
    }

    private fun renderNarrowFs2(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeTestRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = base.density,
                    fontScale = 2.0f,
                ),
            ) {
                Box(
                    modifier = Modifier
                        .background(Color.Black)
                        .fillMaxSize()
                        .height(640.dp)
                        .width(320.dp),
                ) {
                    content()
                }
            }
        }
        composeTestRule.waitForIdle()
    }
}
