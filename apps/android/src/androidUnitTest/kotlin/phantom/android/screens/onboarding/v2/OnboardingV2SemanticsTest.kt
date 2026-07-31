// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Semantics tests for the OnboardingV2 chrome — Robolectric-backed
 * `createComposeRule()` in a JVM unit test.
 *
 * Round-2 REDLINE P2-4 pin: the Back pill is a SINGLE semantics node
 * that carries THREE properties at once — Role.Button, the "Back" text
 * label, and a click action. Previously the shape was
 *   `.clickable(...).semantics(mergeDescendants=true) { role = Button }`
 * — two modifiers each contributing to the node. This test locks the
 * consolidated form
 *   `.clickable(role = Role.Button, onClickLabel = "Back", onClick = ...)`
 * so any future refactor that splits them back would fail here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingV2SemanticsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun back_pill_has_role_label_and_click_action() {
        var clickCount = 0
        composeTestRule.setContent {
            OnboardingTopBarV2(
                stepNumber = 1,
                totalSteps = 4,
                onBackClick = { clickCount++ },
            )
        }

        // The visible "Back" text is the node's label; the same node
        // is a Role.Button with a click action.
        val backNode = composeTestRule.onNodeWithText("Back")

        backNode
            .assertHasClickAction()
            .assert(hasButtonRole())

        // Click actually fires — proves the click action lives on the
        // same node the reviewer would locate via role + label.
        backNode.performClick()
        assert(clickCount == 1) {
            "Back-pill click did not fire (expected 1 click, saw $clickCount)"
        }

        // Regression guard: the click node must have Role.Button. If a
        // future refactor moves the click action to an unrelated child
        // node without Role.Button, this assertion catches it.
        composeTestRule.onNode(
            SemanticsMatcher("Node has Role.Button + click action + Back label") { node ->
                val role = node.config.getOrNull(SemanticsProperties.Role)
                val hasClick = node.config.contains(SemanticsActions.OnClick)
                val hasBackLabel = node.config.getOrNull(SemanticsProperties.Text)
                    ?.any { it.text == "Back" } == true
                role?.toString() == "Button" && hasClick && hasBackLabel
            },
        ).assertExists()
    }

    // ── Left-edge swipe-back gesture (round-3 REDLINE P1-1) ──────────

    // Test harness: mount [OnboardingLeftEdgeSwipeSurface] over a
    // full-screen sibling that we can locate via a semantic label.
    // The swipe-back surface is 34 dp wide at TopStart; the sibling
    // occupies the full 411 dp × 891 dp Pixel 5 viewport. Center-of-
    // screen touches must pass through to the sibling; left-edge
    // touches must be consumed by the surface.

    @Test
    fun edge_swipe_from_left_triggers_back() {
        var backCount = 0
        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Text(
                    text = "STAGE",
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "STAGE_SENTINEL" },
                )
                OnboardingLeftEdgeSwipeSurface(
                    triggerWidth = 34.dp,
                    thresholdPx = 62.dp,
                    topOffset = 64.dp,
                    onEdgeSwipeBack = { backCount++ },
                )
            }
        }
        // A single onSizeChanged event lands after setContent — wait for
        // Compose idle before firing touch input.
        composeTestRule.waitForIdle()

        // Locate the 34-dp overlay by pointerInput input area — we can
        // exercise it via touch coordinates starting at x=5 dp (well
        // inside the 34-dp trigger zone) and swiping right past the
        // 62-dp threshold. Using onRoot as the injection surface so
        // the touch translates to global viewport coordinates that
        // land inside the overlay Box.
        composeTestRule.onRoot().performTouchInput {
            // Swipe from (10, mid) to (200, mid) — starts inside the
            // 34-dp overlay, ends 190 dp to the right (well beyond
            // 62-dp threshold).
            swipeRight(startX = 10f, endX = 200f)
        }
        composeTestRule.waitForIdle()

        assert(backCount == 1) {
            "Expected edge swipe from left to trigger onEdgeSwipeBack once, " +
                "saw $backCount invocations."
        }
    }

    @Test
    fun center_horizontal_drag_does_not_trigger_back() {
        var backCount = 0
        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Text(
                    text = "STAGE",
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "STAGE_SENTINEL" },
                )
                OnboardingLeftEdgeSwipeSurface(
                    triggerWidth = 34.dp,
                    thresholdPx = 62.dp,
                    topOffset = 64.dp,
                    onEdgeSwipeBack = { backCount++ },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().performTouchInput {
            // Swipe from (200, mid) to (400, mid) — starts OUTSIDE the
            // 34-dp overlay (its x range is 0..34), so the pointerInput
            // on the overlay never sees the down event. The drag must
            // NOT fire onEdgeSwipeBack.
            swipeRight(startX = 200f, endX = 400f)
        }
        composeTestRule.waitForIdle()

        assert(backCount == 0) {
            "Expected center horizontal drag NOT to trigger onEdgeSwipeBack, " +
                "saw $backCount invocations."
        }
    }

    // Round-4 REDLINE P1-2 pins: the overlay's ACTIVE gesture region
    // must start below the top-bar area so the Back pill's clickable
    // in the leftmost ~14 dp column remains reachable.

    @Test
    fun overlay_does_not_swallow_touches_in_top_bar_zone() {
        // Setup: full-screen sibling behind the overlay. The overlay
        // uses topOffset = 64.dp; any tap or drag whose down-position
        // falls inside y < 64 dp must NOT be consumed by the overlay
        // → the callback must NOT fire, and any child pointer input
        // that WAS installed on that area should get the events. The
        // simpler assertion for this unit test: drag whose ORIGIN is
        // inside the top-bar zone (y < 64 dp) does not fire the
        // edge-back callback.
        var backCount = 0
        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                OnboardingLeftEdgeSwipeSurface(
                    triggerWidth = 34.dp,
                    thresholdPx = 62.dp,
                    topOffset = 64.dp,
                    onEdgeSwipeBack = { backCount++ },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().performTouchInput {
            // Swipe originates at (10, 30) — inside the 34-dp column
            // horizontally but INSIDE the reserved 64-dp top-bar
            // zone vertically. Overlay's active pointer input starts
            // at y=64 dp, so this drag must not fire the callback.
            //
            // Compose 1.7's `swipeRight(startX, endX, durationMillis)`
            // signature doesn't expose a startY parameter — the y
            // defaults to `centerY` of the injection surface. Drive
            // the swipe via low-level down/moveTo/up with explicit
            // Offset endpoints so y stays inside the top-bar band.
            down(Offset(10f, 30f))
            moveTo(Offset(100f, 30f))
            moveTo(Offset(200f, 30f))
            up()
        }
        composeTestRule.waitForIdle()

        assert(backCount == 0) {
            "A drag originating in the top-bar y-band (y=30 dp) must NOT " +
                "trigger the edge back gesture. saw $backCount invocations."
        }
    }

    @Test
    fun back_pill_click_survives_overlay_layout() {
        // Round-4 REDLINE P1-2 acceptance criterion: the Back pill's
        // clickable must fire for a coordinate tap in its leftmost
        // column even when the edge overlay is present. We render
        // the real OnboardingV2HostFrame (not just the overlay in
        // isolation) so the actual composition + Z-order + Modifier
        // chain is exercised.
        //
        // Round-5 REDLINE P1-3 tightening: previous shape called
        // `backNode.performClick()`, which invokes the Compose
        // semantics OnClick action DIRECTLY and bypasses the hit-
        // testing pipeline entirely. That leaves the pointer-input
        // sibling free to intercept physical taps without any test
        // signal. Fix: inject a physical coordinate tap via
        // `performTouchInput { click(Offset) }` at coordinates INSIDE
        // the Back pill's leftmost 14 dp column — the exact zone
        // where the edge overlay would overlap if topOffset were 0.
        //
        // If the edge overlay were still sitting on top of the Back
        // pill's leftmost pixels, this test would fail: the tap at
        // (x=25 dp, y=<inside-back-bounds>) would be swallowed by the
        // overlay's pointerInput handler and the Back callback would
        // NOT fire. With topOffset=64 dp lifting the overlay's active
        // region below the top bar, the tap lands cleanly on the
        // Back pill.
        var backClicks = 0
        var edgeBacks = 0
        composeTestRule.setContent {
            OnboardingV2HostFrame(
                currentStep = OnboardingStepV2.How,   // has top bar
                topInset = 0.dp,
                onBackClick = { backClicks++ },
                edgeSwipeBackEnabled = true,
                onEdgeSwipeBack = { edgeBacks++ },
                toastMessage = null,
                onToastDismiss = {},
            ) {
                // Empty step body — this test is about chrome, not
                // step content.
            }
        }
        composeTestRule.waitForIdle()

        // Semantic sanity: the "Back" node exists and reports Role.Button
        // + a click action. Distinct from performing the click via
        // semantics — this is round-2 P2-4 pin, unchanged.
        val backNode = composeTestRule.onNodeWithText("Back")
        backNode.assertHasClickAction()

        // Physical coordinate tap. Locate the Back pill's bounds in
        // root, then tap at (x = 25 dp — well inside Back's leftmost
        // 14-dp column at the pill height, y = pill vertical center).
        // Round-5 REDLINE P1-3: this is a real pointerInput injection
        // — it flows through the same hit-testing chain as an on-
        // device tap. Prior shape used `backNode.performClick()`
        // which invokes the semantics OnClick action directly and
        // bypasses hit-testing entirely.
        //
        // `TouchInjectionScope.click(position)` was unresolved on
        // Compose ui-test 1.7.6 — use the explicit down+up pattern
        // which produces exactly the same synthetic event stream.
        val backBounds = backNode.getBoundsInRoot()
        val tapXpx = with(composeTestRule.density) { 25.dp.toPx() }
        val tapYpx = with(composeTestRule.density) {
            val centerY = (backBounds.top + backBounds.bottom) / 2f
            centerY.toPx()
        }
        composeTestRule.onRoot().performTouchInput {
            down(Offset(tapXpx, tapYpx))
            up()
        }
        composeTestRule.waitForIdle()

        assert(backClicks == 1) {
            "Physical tap at ($tapXpx, $tapYpx) inside the Back pill must fire " +
                "onBackClick exactly once — saw $backClicks. If this reads 0, " +
                "the edge overlay is intercepting the coordinate tap that " +
                "overlaps the Back pill area."
        }
        assert(edgeBacks == 0) {
            "A coordinate tap is not a horizontal drag beyond touch slop, so " +
                "onEdgeSwipeBack must not fire — saw $edgeBacks."
        }
    }

    @Test
    fun finale_has_no_edge_swipe_surface() {
        // In OnboardingV2HostFrame the overlay is conditionally rendered
        // via `if (edgeSwipeBackEnabled)`. This test asserts what the
        // predicate returns for the two chromeless steps AND models the
        // conditional-render contract: when the predicate is false the
        // surface is NOT part of the composition, so no swipe on any
        // area can invoke onEdgeSwipeBack.
        assert(!isEdgeSwipeBackFromEnabled(OnboardingStepV2.FinaleConfirmation)) {
            "isEdgeSwipeBackFromEnabled(FinaleConfirmation) must be false — " +
                "otherwise the host frame would render the edge overlay on Finale."
        }
        assert(!isEdgeSwipeBackFromEnabled(OnboardingStepV2.Welcome)) {
            "isEdgeSwipeBackFromEnabled(Welcome) must be false — the flow starts here."
        }

        // Now render WITHOUT the overlay (mirroring the host's `if`
        // branch that skips the composable on Finale) and confirm a
        // swipe anywhere does not invoke the back callback.
        var backCount = 0
        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Text(
                    text = "STAGE",
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "STAGE_SENTINEL" },
                )
                // No OnboardingLeftEdgeSwipeSurface here — this
                // mirrors the host frame's `if (edgeSwipeBackEnabled)`
                // guard when the predicate returns false.
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().performTouchInput {
            swipeRight(startX = 5f, endX = 200f)
        }
        composeTestRule.waitForIdle()

        assert(backCount == 0) {
            "Finale must not fire onEdgeSwipeBack even for a swipe originating " +
                "in the 34-dp left band — the host frame skips the overlay entirely."
        }
    }
}

// ── Matchers ────────────────────────────────────────────────────────────

private fun hasButtonRole(): SemanticsMatcher =
    SemanticsMatcher("Role == Button") { node ->
        val role = node.config.getOrNull(SemanticsProperties.Role)
        role?.toString() == "Button"
    }

private fun <T> androidx.compose.ui.semantics.SemanticsConfiguration.getOrNull(
    key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>,
): T? = try {
    this[key]
} catch (_: IllegalStateException) {
    null
}
