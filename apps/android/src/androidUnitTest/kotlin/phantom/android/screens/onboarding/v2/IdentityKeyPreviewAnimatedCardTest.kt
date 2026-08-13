// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.android.screens.onboarding.v2.steps.IdentityKeyPreviewAnimatedCard
import phantom.android.screens.onboarding.v2.steps.IdentityKeyStepV2
import phantom.android.screens.onboarding.v2.steps.KEY_PREVIEW_CARD_A11Y_TAG
import phantom.android.screens.onboarding.v2.steps.KEY_PREVIEW_TARGET_CHARS

/**
 * C6-b Round-1 — Compose UI tests for the animated key-preview card.
 * Contract sheet
 * `docs/tracks/android-onboarding/c6-b-key-preview-animation.md`
 * §6.8-6.13.
 *
 * All six tests target BEHAVIOURAL contracts. NO regex source-shape
 * checks (contract §4).
 *
 * Round-1 test rewrites (per architect REDLINE 2026-08-10):
 *   - `state_restoration_terminal_survives_...`  now freezes
 *     `mainClock.autoAdvance = false` IMMEDIATELY after restore so
 *     the assertion catches the "re-shuffle" regression (previously
 *     `waitForIdle` masked re-shuffle by replaying 1440 ms).
 *   - `reduced_motion_...` now injects a real
 *     `MotionDurationScale { scaleFactor = 0f }` via the composable's
 *     `testMotionDurationScale` param, then advances one frame under
 *     a frozen clock. Terminal on that single frame proves reduced
 *     motion is respected (previously the test only proved the
 *     normal-scale animation completes).
 *   - `card_a11y_...` now inspects the merged semantics config
 *     directly and asserts BOTH the stable `contentDescription` and
 *     the absence of any decorative-glyph substring in the merged text.
 *   - `reachability_full_step_at_320dp_fs2` now renders the WHOLE
 *     `IdentityKeyStepV2` (title + input + CTA + card) so the
 *     narrow-width claim covers all step-level nodes.
 *   - `continue_...` methods renamed to describe what they actually
 *     verify (invalid → disabled, valid across three animation
 *     ticks → enabled throughout).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IdentityKeyPreviewAnimatedCardTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Robolectric shares one Choreographer per SDK environment across
     * every test in the same JVM. Three tests in this class freeze
     * the composeTestRule.mainClock via `autoAdvance = false` to pin
     * frame-boundary invariants; if any of them throws before
     * restoring the clock, the next test class runs against a halted
     * Choreographer and Espresso trips `AppNotIdleException` on its
     * very first `setContent`. Restoring here guarantees the leak
     * blast radius is zero — regardless of which test failed or
     * threw. Pinned by
     * `logo-flash-fix-contract.md` §9 (JVM-shared-clock hygiene).
     */
    @After
    fun restoreMainClockAutoAdvance() {
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
    }

    // ── §6.8 — Continue disabled invariant ───────────────────────

    @Test
    fun continue_disabled_when_username_invalid() {
        // Contract §2.5 invariant: `enabled = validateUsernameV2(...) ==
        // UsernameValidationV2.Valid` — with invalid username the CTA
        // MUST be disabled, regardless of the internal card animation
        // phase (which is Idle for invalid usernames — Terminal +
        // invalid is not a reachable production combination, so we
        // pin the invariant by asserting the disabled state for
        // three canonical invalid inputs via a single-setContent +
        // mutableStateOf swap loop).
        val usernameState = androidx.compose.runtime.mutableStateOf("")
        composeTestRule.setContent {
            IdentityKeyStepV2(
                formState = OnboardingFormStateV2(username = usernameState.value),
                dotsIndex = 1,
                onFormStateChange = { usernameState.value = it.username },
                onContinueClick = {},
            )
        }
        for (invalid in listOf("", "al", "al!ce")) {
            usernameState.value = invalid
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Continue").assertIsNotEnabled()
        }
    }

    // ── §6.9 — Continue enabled across all animation ticks ──────

    @Test
    fun continue_stays_enabled_across_multiple_animation_ticks_when_username_valid() {
        // Contract §2.5 invariant: animation state does NOT enter
        // the CTA-enabled expression. To prove this behaviourally,
        // freeze the clock and sample Continue's enabled state at
        // multiple ticks throughout the shuffle: frame 0
        // (Running-just-started), mid-flight (Running-mid), and
        // Terminal. Continue MUST be enabled at every tick.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            IdentityKeyStepV2(
                formState = OnboardingFormStateV2(username = "alice"),
                dotsIndex = 1,
                onFormStateChange = {},
                onContinueClick = {},
            )
        }
        // Frame 0 — first-composition dispatch has produced the
        // initial Animatable at 0f (Running).
        composeTestRule.mainClock.advanceTimeBy(16L)
        composeTestRule.onNodeWithText("Continue")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
        // Mid-flight (approx 700 ms of 1440 ms).
        composeTestRule.mainClock.advanceTimeBy(700L)
        composeTestRule.onNodeWithText("Continue").assertIsEnabled()
        // Terminal (past 1440 ms).
        composeTestRule.mainClock.advanceTimeBy(1000L)
        composeTestRule.onNodeWithText("Continue").assertIsEnabled()
    }

    // ── §6.10 — State restoration: Terminal survives without re-shuffle ──

    @Test
    fun terminal_state_survives_activity_recreation_without_reshuffling() {
        // Round-1 P1-1 pin: previous shape allowed `waitForIdle`
        // AFTER `emulateSavedInstanceStateRestore` to re-play the
        // entire 1440 ms shuffle, then asserted READY TO CREATE —
        // that assertion passed on re-shuffle. New shape freezes
        // the clock IMMEDIATELY after restore; if the code re-
        // shuffles, no time advances → card renders Running/WILL
        // BE GENERATED on the first post-restore frame, and the
        // assertion fires red.
        composeTestRule.mainClock.autoAdvance = false
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            IdentityKeyPreviewAnimatedCard(usernameValid = true)
        }
        // Drive to Terminal by advancing past the shuffle duration.
        composeTestRule.mainClock.advanceTimeBy(1600L)
        composeTestRule.onNodeWithText("ED25519 · READY TO CREATE")
            .assertIsDisplayed()

        // Restore. Immediately after, do NOT autoAdvance the
        // clock — assert Terminal on the very first post-restore
        // frame.
        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.mainClock.advanceTimeBy(16L)  // one frame

        composeTestRule.onNodeWithText("ED25519 · READY TO CREATE")
            .assertIsDisplayed()
    }

    // ── §6.11 — A11y merged config: stable summary, no glyph noise ──

    @Test
    fun card_a11y_reports_stable_summary_and_no_glyph_content_in_merged_config() {
        // Round-1 P2-5 pin: previous shape only checked for "\n"
        // substring absence, which is a narrow proxy. New shape
        // fetches the actual merged semantics config and asserts:
        //   (a) ContentDescription is exactly the stable summary tag.
        //   (b) Merged text does NOT contain ANY decorative-glyph
        //       substring derived from the target `••••` (any leak
        //       of the body's decorative content would surface here).
        composeTestRule.setContent {
            IdentityKeyPreviewAnimatedCard(usernameValid = true)
        }
        composeTestRule.waitForIdle()
        val cardNode = composeTestRule.onNodeWithContentDescription(
            KEY_PREVIEW_CARD_A11Y_TAG,
        )
        cardNode.assertIsDisplayed()

        val config = cardNode.fetchSemanticsNode().config

        val descriptions = config.getOrElseNullable(
            SemanticsProperties.ContentDescription,
        ) { emptyList<String>() }
        assertTrue(
            actual = descriptions?.any { it == KEY_PREVIEW_CARD_A11Y_TAG } == true,
            message = "Merged card ContentDescription MUST contain the stable summary " +
                "tag `$KEY_PREVIEW_CARD_A11Y_TAG`. Got: $descriptions",
        )

        val texts: List<AnnotatedString> = config.getOrElseNullable(
            SemanticsProperties.Text,
        ) { emptyList<AnnotatedString>() } ?: emptyList()
        val mergedText = texts.joinToString(" | ") { it.text }
        // Header status label ("ED25519 · READY TO CREATE") and
        // footer copy MUST be part of merged text.
        assertTrue(
            actual = "READY TO CREATE" in mergedText,
            message = "Merged card text MUST include the header status " +
                "(READY TO CREATE). Got: '$mergedText'",
        )
        assertTrue(
            actual = "Generated on device" in mergedText,
            message = "Merged card text MUST include the footer copy. " +
                "Got: '$mergedText'",
        )
        // Load-bearing: glyph body (`••••` × 8 groups, from the
        // masked-bullet target) MUST NOT leak into merged text.
        // We assert on the FIRST group of 4 target chars — a
        // regression that removed `clearAndSetSemantics` on the
        // glyph Text would surface these characters here.
        val firstGroup = KEY_PREVIEW_TARGET_CHARS.take(4).joinToString("")
        assertFalse(
            actual = firstGroup in mergedText,
            message = "Merged card text MUST NOT contain any decorative " +
                "glyph substring ('$firstGroup' from target). Got: '$mergedText'",
        )
    }

    // ── §6.12 — Reachability of full Step 2 at 320 × 640 dp fs=2.0 ──

    @Test
    fun full_step2_at_320dp_fs2_card_input_and_cta_all_reachable() {
        // Round-1 P2-6 pin: previous shape rendered only the
        // isolated card, so the "all_elements_present" claim did
        // not cover the input or CTA. New shape renders the WHOLE
        // `IdentityKeyStepV2` and asserts every user-touchable
        // node is displayed at the narrow-width + large-text
        // extreme.
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
                    IdentityKeyStepV2(
                        formState = OnboardingFormStateV2(username = "alice"),
                        dotsIndex = 1,
                        onFormStateChange = {},
                        onContinueClick = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        // Title reachable at first frame (above the fold).
        composeTestRule.onNodeWithText("Your identity key")
            .assertIsDisplayed()
        // Card a11y tag reachable at first frame — top of body.
        composeTestRule.onNodeWithContentDescription(KEY_PREVIEW_CARD_A11Y_TAG)
            .assertIsDisplayed()
        // Username input section — below the fold at 320×640 dp
        // fs=2.0. Assert it becomes reachable via scroll. A
        // regression that removed `verticalScroll()` from the
        // body would fail `performScrollTo` because the input
        // has no scrollable ancestor.
        composeTestRule.onNodeWithText("USERNAME")
            .performScrollTo()
            .assertIsDisplayed()
        // Continue CTA — lives in the fixed bottom band
        // (windowInsetsPadding-guarded). Always displayed
        // regardless of scroll position.
        composeTestRule.onNodeWithText("Continue")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    // ── §6.13 — Reduced motion: real MotionDurationScale(0f) injection ──

    @Test
    fun reduced_motion_scale_zero_renders_visible_terminal_on_first_frame_when_username_valid() {
        // Round-1 P1-2 + mini-round P1 pins:
        //   (a) Shuffle Animatable runs under injected
        //       MotionDurationScale(scaleFactor=0f) → completes on
        //       first dispatch → phase = Terminal on first frame.
        //   (b) Mount Animatable ALSO runs under the same scale
        //       AND expresses its 160 ms lead-in via `tween
        //       (delayMillis = ..)` instead of a plain
        //       `kotlinx.coroutines.delay`. Both facets collapse
        //       under scale=0 → alpha = 1 on first frame → the
        //       card is VISIBLY on-screen (not merely present in
        //       semantics).
        //
        // The pixel assertion below discriminates the regression
        // architect flagged: if the mount kept using
        // `kotlinx.coroutines.delay(160)`, that 160 ms would NOT
        // respect scale=0, `mountProgress` would stay at 0f on
        // frame 0, `graphicsLayer { alpha = mountProgress }` would
        // hide the card behind its white parent background, and
        // the captured centre pixel would be WHITE (parent),
        // not the SurfaceInset dark of the card body.
        val zeroScale = object : MotionDurationScale {
            override val scaleFactor: Float = 0f
            override val key: CoroutineContext.Key<MotionDurationScale>
                get() = MotionDurationScale.Key
        }
        var capturedMountProgress = 0f
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            Box(modifier = Modifier.background(Color.Black)) {
                IdentityKeyPreviewAnimatedCard(
                    usernameValid = true,
                    testMotionDurationScale = zeroScale,
                    testMountProgressSink = { capturedMountProgress = it },
                )
            }
        }
        composeTestRule.mainClock.advanceTimeBy(16L)  // one frame

        // Semantics reachability — necessary but not sufficient
        // (nodes remain in the a11y tree even at alpha=0).
        composeTestRule.onNodeWithText("ED25519 · READY TO CREATE")
            .assertIsDisplayed()

        // Mount-progress probe — the discriminative check for
        // the mini-round P1 fix. The composable exposes
        // `mountAnimatable.value` on every recomposition via the
        // `testMountProgressSink` seam (see
        // `IdentityKeyPreviewAnimation.kt` KDoc). Under scale=0
        // WITH the fixed mount (`tween(delayMillis = 160)`
        // + `withContext(scale)`), the animation collapses to
        // 0 ms → probe receives 1f on the first frame's commit.
        // Under a regression that uses plain
        // `kotlinx.coroutines.delay(160)` (ignores scale), the
        // 160 ms virtual delay would still block the coroutine
        // → probe receives 0f on frame 0.
        //
        // Discrimination is exact and deterministic — no reliance
        // on Robolectric's pixel-capture path, which fails under
        // both `captureToImage` (ComposeTimeoutException from
        // idle-wait under a frozen clock) AND direct
        // `View.draw(Canvas)` (NPE from
        // `Api26Bitmap.createBitmap` while rendering the ED25519
        // vector drawable).
        assertEquals(
            expected = 1f,
            actual = capturedMountProgress,
            absoluteTolerance = 0.001f,
            message = "Under MotionDurationScale(0f), mountAnimatable.value MUST be 1f " +
                "on the first frame — a regression that reverts the mount to a plain " +
                "`kotlinx.coroutines.delay(160)` (does NOT respect scale=0) would leave " +
                "mount at 0f on frame 0 and the card at alpha=0 (visually invisible). " +
                "Got mountProgress = $capturedMountProgress.",
        )
    }
}

// ── Small helper to allow both Text and ContentDescription
//    reads to fall back to empty collections when the property
//    is absent — kept private to this test file.

private fun <T> androidx.compose.ui.semantics.SemanticsConfiguration.getOrElseNullable(
    key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>,
    default: () -> T,
): T? = if (contains(key)) get(key) else default()
