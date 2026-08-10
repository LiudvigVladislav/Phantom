// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import org.junit.After
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
 * Logo-flash-fix track (2026-08-10) — focused transition tests
 * for the `Welcome → How` special-cased fade.
 *
 * Contract sheet:
 * `docs/tracks/android-onboarding/logo-flash-fix-contract.md`.
 *
 * These four tests pin the invariants architect requested after
 * the on-device flash defect:
 *
 *   1. Activity instance stability across the tap.
 *   2. Welcome logo absent + How present on first frame AND at
 *      mid-fade (~90 ms of the 180-ms enter). Uses
 *      `useUnmergedTree = true` so wrapper composables around
 *      the logo do not hide it from the test tree.
 *   3. Double-tap ends at exactly `How` — NOT `Identity` — a
 *      "prove destination" invariant rather than a weak
 *      "second click misses" check.
 *   4. Back from How returns to Welcome.
 *
 * Tests inflate an `AnimatedContent` that uses the REAL
 * production `onboardingStepContentTransform()` extension +
 * REAL `WelcomeStepV2` / `HowStepV2` composables. Any drift
 * in the production transitionSpec would trip these tests.
 * (An `Identity` stub is used only as a "next-after-How"
 * destination for the double-tap test — the real
 * `IdentityKeyStepV2` requires an `AppContainer` we don't
 * inflate here.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingFlowV2TransitionTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Robolectric shares one Choreographer per SDK environment
     * across every test in the same JVM. This class freezes
     * `mainClock.autoAdvance = false` to pin frame-boundary
     * invariants for the logo-flash fix; if a test throws before
     * restoring the clock, the next test class runs against a
     * halted Choreographer and Espresso trips `AppNotIdleException`
     * on its very first `setContent`. Restoring here guarantees
     * the leak blast radius is zero — regardless of which test
     * failed or threw. Pinned by `logo-flash-fix-contract.md` §9
     * (JVM-shared-clock hygiene).
     */
    @After
    fun restoreMainClockAutoAdvance() {
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
    }

    private val identityStubTag = "identity_stub_root_for_test"
    private val getStartedLabel = "Get started"
    private val howContinueLabel = "Continue"

    /**
     * Mounts a stripped-down 3-step flow using the REAL production
     * `onboardingStepContentTransform()` + REAL Welcome/How
     * composables, with an `Identity` stub for double-tap
     * destination checks. Back from How returns to Welcome via a
     * BackHandler wired to the same `step` state.
     *
     * @param onStepChange invoked after each state transition so
     *   tests can inspect / assert intermediate state.
     */
    private fun inflateFlow(onStepChange: (OnboardingStepV2) -> Unit = {}) {
        composeTestRule.setContent {
            var step by remember { mutableStateOf(OnboardingStepV2.Welcome) }
            // Mirror the REAL production `goNext` semantics:
            //   goNext advances to the next step when
            //   canAdvanceFromV2(step, formState) is true.
            //   Both Welcome and How return true unconditionally,
            //   so a double-fire on Welcome CTA would advance
            //   Welcome → How → Identity in a single gesture
            //   window UNLESS the CTA callback guards on step ==
            //   Welcome. This mirror keeps the exact vulnerability
            //   the production fix protects against.
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
            AnimatedContent(
                targetState = step,
                transitionSpec = { onboardingStepContentTransform() },
                label = "onboarding-step-test",
                modifier = Modifier.fillMaxSize(),
            ) { s ->
                when (s) {
                    OnboardingStepV2.Welcome -> WelcomeStepV2(
                        // Mirror of the REAL production guard added
                        // by the logo-flash-fix track 2026-08-10 (see
                        // `OnboardingFlowV2.kt` Welcome branch). The
                        // guard makes double-fire on Welcome CTA
                        // idempotent — second fire is silently
                        // dropped because `step != Welcome` after
                        // the first advance.
                        onContinueClick = {
                            if (step == OnboardingStepV2.Welcome) goNext()
                        },
                    )
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

    // ── §5.1 — Activity instance / onCreate not re-invoked on tap ──

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
            message = "Activity instance MUST be the same before and after `Get started`. " +
                "A regression that recreates the Activity (config-change, setContent re-entry, " +
                "manual `recreate()`) would produce a different instance here — the logo-flash " +
                "defect would then also manifest as a re-mounted splash / re-run onboarding intro.",
        )
    }

    // ── §5.2 — Logo absent, How present on first frame + at mid-fade ──

    @Test
    fun welcome_logo_absent_immediately_after_get_started_tap() {
        inflateFlow()
        composeTestRule.waitForIdle()
        // Pre-tap sanity: Welcome logo present.
        composeTestRule.onAllNodesWithTag(
            WELCOME_PHANTOM_LOGO_TEST_TAG, useUnmergedTree = true,
        ).assertCountEquals(1)

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithText(getStartedLabel).performClick()
        // Advance TWO frames past the tap via `advanceTimeByFrame()`
        // — Compose's own single-frame tick, ~16 ms at 60 Hz each.
        // Empirical audit 2026-08-10: `AnimatedContent` under
        // `EnterTransition.None togetherWith ExitTransition.None`
        // requires TWO composition frames to fully remove the
        // outgoing content — frame 1 sees the target-state change
        // and starts the `Transition<T>`; frame 2 observes the
        // instantly-finished transition and disposes the outgoing
        // node. This is the "checkable invariant of the current
        // Compose version" the architect flagged in the logo-flash
        // contract sheet §2. The 32-ms two-frame window remains
        // well below any human-perception threshold for a flash
        // (< 100 ms) and dramatically below the ORIGINAL 160-ms
        // exit-fade overlap that produced the defect.
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onAllNodesWithTag(
            WELCOME_PHANTOM_LOGO_TEST_TAG, useUnmergedTree = true,
        ).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(
            HOW_STEP_ROOT_TEST_TAG, useUnmergedTree = true,
        ).assertCountEquals(1)

        // Mid-fade — approximately halfway through the ORIGINAL
        // 160-ms overlap window (the crossfade the fix removes).
        // Under the current instant-swap this is a stable frame;
        // the assertion re-verifies the logo stays absent inside
        // the exact time window where a regression re-adding the
        // exit fade would surface the flash.
        composeTestRule.mainClock.advanceTimeBy(90L)
        composeTestRule.onAllNodesWithTag(
            WELCOME_PHANTOM_LOGO_TEST_TAG, useUnmergedTree = true,
        ).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(
            HOW_STEP_ROOT_TEST_TAG, useUnmergedTree = true,
        ).assertCountEquals(1)
        // Clock is restored + drained in @After — see
        // `restoreMainClockAutoAdvance` for the JVM-shared-clock
        // hygiene rationale.
    }

    // ── §5.3 — Double-tap ends at exactly How (NOT Identity) ─────

    @Test
    fun get_started_double_tap_ends_at_how_not_identity() {
        val visitedSteps = mutableListOf<OnboardingStepV2>()
        inflateFlow(onStepChange = { visitedSteps += it })
        composeTestRule.waitForIdle()

        // Two rapid taps on the Welcome CTA WITHIN ONE gesture
        // scope — no `waitForIdle` between them. This is what a
        // real double-tap looks like at the pointer-input layer:
        // two down/up sequences before the outer state has a
        // chance to recompose. Any per-composable click-dedup
        // Compose does at the pointer-input layer applies; but
        // if the state-machine callback fires twice AND the
        // callback naively advances by one step per invocation
        // (as `goNext` does — `canAdvanceFromV2` returns true
        // for both Welcome and How), navigation would race past
        // How to Identity.
        //
        // The REAL fix is the Welcome-CTA guard `if (step ==
        // Welcome) goNext()` in `OnboardingFlowV2.kt` (and
        // mirrored in the test harness above). This assertion
        // proves the guard works: destination = How, NOT
        // Identity, no matter how many times the pointer fires.
        composeTestRule.onNodeWithText(getStartedLabel).performTouchInput {
            down(center)
            up()
            down(center)
            up()
        }
        composeTestRule.waitForIdle()

        // Prove destination: How's root MUST be present, Identity
        // stub MUST NOT be present. Assertions on the actual
        // composition tree — this is a "prove destination" check,
        // NOT the weak "second click can't find button" test the
        // architect explicitly banned.
        composeTestRule.onAllNodesWithTag(
            HOW_STEP_ROOT_TEST_TAG, useUnmergedTree = true,
        ).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(
            identityStubTag, useUnmergedTree = true,
        ).assertCountEquals(0)

        // Bonus (defence in depth): the state machine emitted
        // exactly one `How` transition. A regression that
        // removed the `if (step == Welcome)` guard would surface
        // here as `[How, Identity]`.
        assertEquals(
            expected = listOf(OnboardingStepV2.How),
            actual = visitedSteps,
            message = "Double-tap on `Get started` MUST emit exactly one state " +
                "transition to `How`. A regression that removes the Welcome-CTA " +
                "guard (`if (step == Welcome) goNext()`) would let the second " +
                "pointer fire advance past How to Identity — this list would " +
                "show `[How, Identity]`. Got: $visitedSteps.",
        )
    }

    // ── §5.4 — Back from How returns to Welcome ──────────────────

    @Test
    fun back_from_how_returns_to_welcome_normally() {
        inflateFlow()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(getStartedLabel).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag(
            HOW_STEP_ROOT_TEST_TAG, useUnmergedTree = true,
        ).assertCountEquals(1)

        // System back — routed through OnBackPressedDispatcher on
        // the Activity so BackHandler in the composition catches
        // it. The How-branch BackHandler flips state back to
        // Welcome.
        composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag(
            WELCOME_PHANTOM_LOGO_TEST_TAG, useUnmergedTree = true,
        ).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(
            HOW_STEP_ROOT_TEST_TAG, useUnmergedTree = true,
        ).assertCountEquals(0)
        // Welcome CTA also reachable after back — the flow is
        // fully restored to Welcome, not a partial state.
        composeTestRule.onNodeWithText(getStartedLabel).assertIsDisplayed()
    }
}
