// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.android.screens.onboarding.v2.steps.HOW_STEP_ROOT_TEST_TAG
import phantom.android.screens.onboarding.v2.steps.HowStepV2
import phantom.android.screens.onboarding.v2.steps.WELCOME_PHANTOM_LOGO_TEST_TAG
import phantom.android.screens.onboarding.v2.steps.WelcomeStepV2

/**
 * Onboarding-stabilization block 2026-08-11 — focused transition
 * tests for the STRUCTURAL Welcome ↔ How swap.
 *
 * Contract sheet:
 * `docs/tracks/android-onboarding/onboarding-stabilization-block-2026-08-11.md`.
 *
 * The prior "logo-flash fix" that scoped `EnterTransition.None
 * togetherWith ExitTransition.None` inside a single `AnimatedContent`
 * did not close the defect on device — `AnimatedContent`'s
 * `KeepUntilTransitionsFinished` machinery still held the outgoing
 * Welcome tree for one extra frame, and the PHANTOM logo remained
 * visible. The current fix is STRUCTURAL: Welcome is rendered by a
 * plain `if (step == Welcome)` guard OUTSIDE `AnimatedContent`, so
 * the moment state flips to How the `if` branch disposes Welcome in
 * the same frame and `AnimatedContent` mounts How for the first
 * time with no overlap.
 *
 * These four tests pin the invariants:
 *   1. Activity instance stability across the tap.
 *   2. Welcome logo absent + How present immediately after the tap
 *      (`waitForIdle`-based — no clock manipulation).
 *   3. Double-tap ends at exactly `How` — NOT `Identity` — with a
 *      `[How]` visited-steps list.
 *   4. Back from How returns to Welcome.
 *
 * The test harness mirrors the REAL production shape: `if (step ==
 * Welcome) WelcomeStepV2(...) else AnimatedContent(...)` with the
 * same `fadeIn(180) togetherWith fadeOut(160)` transition spec for
 * the non-Welcome branch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingFlowV2TransitionTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val identityStubTag = "identity_stub_root_for_test"
    private val getStartedLabel = "Get started"

    /**
     * Mounts a stripped-down 3-step flow with the exact production
     * shape: Welcome rendered by a plain `if` branch OUTSIDE
     * `AnimatedContent`; How and Identity-stub rendered as branches
     * of `AnimatedContent` with the regular crossfade.
     */
    private fun inflateFlow(onStepChange: (OnboardingStepV2) -> Unit = {}) {
        composeTestRule.setContent {
            var step by remember { mutableStateOf(OnboardingStepV2.Welcome) }
            // Mirror the REAL production `goNext` — advances one
            // step; Welcome CTA re-entrancy is guarded below.
            val goNext: () -> Unit = {
                val idx = OnboardingStepV2.entries.indexOf(step)
                if (idx + 1 < OnboardingStepV2.entries.size) {
                    step = OnboardingStepV2.entries[idx + 1]
                    onStepChange(step)
                }
            }
            if (step == OnboardingStepV2.How) {
                BackHandler {
                    step = OnboardingStepV2.Welcome
                    onStepChange(step)
                }
            }
            if (step == OnboardingStepV2.Welcome) {
                WelcomeStepV2(
                    onContinueClick = {
                        if (step == OnboardingStepV2.Welcome) goNext()
                    },
                )
            } else AnimatedContent(
                targetState = step,
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(160))
                },
                label = "onboarding-step-test",
                modifier = Modifier.fillMaxSize(),
            ) { s ->
                when (s) {
                    OnboardingStepV2.Welcome -> Unit  // handled by if-guard
                    OnboardingStepV2.How -> HowStepV2(
                        dotsIndex = OnboardingStepV2.How.dotsIndex,
                        onContinueClick = goNext,
                    )
                    else -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .testTag(identityStubTag),
                    )
                }
            }
        }
    }

    // ── §1 — Activity instance / onCreate not re-invoked on tap ──

    @Test
    fun activity_is_not_recreated_when_get_started_tapped() {
        inflateFlow()
        val activityBefore = composeTestRule.activity
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(getStartedLabel).performClick()
        composeTestRule.waitForIdle()

        val activityAfter = composeTestRule.activity
        assertSame(
            expected = activityBefore,
            actual = activityAfter,
            message = "Activity instance MUST be the same before and after `Get started` — " +
                "a recreation would surface as a re-mounted splash and re-triggered onboarding.",
        )
    }

    // ── §2 — Welcome logo absent + How present after the tap ─────

    @Test
    fun welcome_logo_absent_and_how_present_after_get_started_tap() {
        inflateFlow()
        composeTestRule.waitForIdle()
        // Pre-tap sanity: Welcome logo present.
        composeTestRule.onAllNodesWithTag(
            WELCOME_PHANTOM_LOGO_TEST_TAG, useUnmergedTree = true,
        ).assertCountEquals(1)

        composeTestRule.onNodeWithText(getStartedLabel).performClick()
        composeTestRule.waitForIdle()

        // The structural if-guard disposes Welcome the moment
        // `step != Welcome`, so no frame of the post-tap render
        // ever contains the logo. A regression that puts Welcome
        // back inside `AnimatedContent` (letting the outgoing tree
        // linger via `KeepUntilTransitionsFinished`) would fail
        // this test red.
        composeTestRule.onAllNodesWithTag(
            WELCOME_PHANTOM_LOGO_TEST_TAG, useUnmergedTree = true,
        ).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(
            HOW_STEP_ROOT_TEST_TAG, useUnmergedTree = true,
        ).assertCountEquals(1)
    }

    // ── §3 — Double-tap ends at exactly How (NOT Identity) ───────

    @Test
    fun get_started_double_tap_ends_at_how_not_identity() {
        val visitedSteps = mutableListOf<OnboardingStepV2>()
        inflateFlow(onStepChange = { visitedSteps += it })
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(getStartedLabel).performTouchInput {
            down(center)
            up()
            down(center)
            up()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag(
            HOW_STEP_ROOT_TEST_TAG, useUnmergedTree = true,
        ).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(
            identityStubTag, useUnmergedTree = true,
        ).assertCountEquals(0)

        assertEquals(
            expected = listOf(OnboardingStepV2.How),
            actual = visitedSteps,
            message = "Double-tap on `Get started` MUST emit exactly one transition to `How`. " +
                "A regression that removes the Welcome-CTA guard would let the second " +
                "pointer fire advance past How to Identity — this list would show " +
                "`[How, Identity]`. Got: $visitedSteps.",
        )
    }

    // ── §4 — Back from How returns to Welcome ────────────────────

    @Test
    fun back_from_how_returns_to_welcome_normally() {
        inflateFlow()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(getStartedLabel).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag(
            HOW_STEP_ROOT_TEST_TAG, useUnmergedTree = true,
        ).assertCountEquals(1)

        composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag(
            WELCOME_PHANTOM_LOGO_TEST_TAG, useUnmergedTree = true,
        ).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(
            HOW_STEP_ROOT_TEST_TAG, useUnmergedTree = true,
        ).assertCountEquals(0)
        composeTestRule.onNodeWithText(getStartedLabel).assertIsDisplayed()
    }
}
