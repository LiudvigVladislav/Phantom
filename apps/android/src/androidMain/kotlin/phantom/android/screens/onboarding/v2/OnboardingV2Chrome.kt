// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import phantom.android.ui.designv2.DesignV2Tokens

/**
 * Onboarding V2 chrome — host frame, edge-swipe back surface,
 * pricing-sheet a11y shroud, and the test-only shroud preview.
 *
 * Extracted from `OnboardingFlowV2.kt` in round-3 REDLINE §P2-5
 * file-split to keep both files below the 500-line CLAUDE.md
 * ceiling. The flow file owns the state machine + wiring; this
 * file owns the reusable chrome pieces the flow (and Paparazzi
 * showcases) mount.
 *
 * See individual composables' KDoc for their contract:
 *   - [OnboardingV2HostFrame]       — cipher background + top bar +
 *                                      toast + edge-swipe overlay +
 *                                      pricing-a11y shroud.
 *   - [OnboardingLeftEdgeSwipeSurface] — 34-dp left-edge back gesture.
 *   - [pricingSheetA11yShroudModifier] — invisibleToUser + blur while
 *                                         the pricing sheet is
 *                                         present.
 *   - [PricingA11yShroudPreviewFor]   — test-only preview so
 *                                         `OnboardingV2SemanticsTest`
 *                                         can pin the shroud
 *                                         invariant without wiring
 *                                         the full controller +
 *                                         AppContainer stack.
 */

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun pricingSheetA11yShroudModifier(pricingSheetPresent: Boolean): Modifier =
    if (pricingSheetPresent) {
        Modifier
            .semantics(mergeDescendants = true) { invisibleToUser() }
            .blur(
                radius = 3.dp,
                edgeTreatment = androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded,
            )
    } else {
        Modifier
    }

/**
 * OnboardingV2HostFrame — the shared visual frame (P1-2 REDLINE fix).
 *
 * BOTH the runtime [OnboardingFlowV2] and the debug showcase (used by
 * the Paparazzi goldens) call this composable. That guarantees:
 *
 *   1. Status-bar inset is applied the same way in both — [topInset]
 *      is a plain Dp so the runtime can pass
 *      `WindowInsets.statusBars.asPaddingValues().calculateTopPadding()`
 *      while the showcase passes a hard-coded 24 dp (Pixel 5 default
 *      status bar height, matching the Paparazzi device profile).
 *      Without this frame, showcase composables previously skipped
 *      windowInsetsPadding entirely and the golden rendered content
 *      starting at y=0 — misleading vs on-device layout.
 *   2. Cipher background, top bar, edge-swipe-back gesture, and toast
 *      overlay all live here, so a step body only worries about its
 *      own content + its dots + its CTA — nothing else.
 *
 * Step dots are NOT rendered here (P1-3 REDLINE fix); each step body
 * renders its own dots directly above its primary CTA per handoff
 * `Onboarding.dc.html`. This composable exposes `currentStep` so the
 * top bar knows which step number to display and the caller can key
 * behaviour off it, but does not itself paint any per-step widget
 * below the top bar other than the [content] slot.
 */
@Composable
fun OnboardingV2HostFrame(
    currentStep: OnboardingStepV2,
    topInset: Dp,
    onBackClick: () -> Unit,
    edgeSwipeBackEnabled: Boolean,
    onEdgeSwipeBack: () -> Unit,
    toastMessage: String?,
    onToastDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DesignV2Tokens.Colors.SurfaceDeep)
            .padding(PaddingValues(top = topInset)),
    ) {
        OnboardingCipherBackground()

        content()

        if (currentStep.showTopBar) {
            OnboardingTopBarV2(
                stepNumber = currentStep.stepNumber,
                totalSteps = currentStep.totalNumberedSteps,
                onBackClick = onBackClick,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }

        // Left-edge swipe-back gesture (round-3 REDLINE P1-1 +
        // round-4 REDLINE P1-2).
        //
        // Round-3 constrained the gesture surface to a 34-dp overlay
        // Box (no more full-frame pointerInput). Round-4 additionally
        // moves the ACTIVE gesture zone below the top-bar area so it
        // never overlaps with the Back pill.
        //
        // Back-pill layout: 20 dp horizontal padding from the frame's
        // TopStart + Row of pill height ~36 dp + 12 dp vertical
        // padding. So Back pill occupies roughly x = 20..85 dp, y =
        // 12..48 dp. A 34-dp × full-height overlay at TopStart would
        // sit ON TOP of Back's leftmost ~14 dp column and steal any
        // horizontal drag starting there.
        //
        // Fix: pass a `topOffset` to the overlay so its active
        // pointer-input region starts BELOW the top bar. Content in
        // the 34 × topOffset area at TopStart is not consumed by the
        // overlay — taps and drags on the Back pill's leftmost pixels
        // reach the pill's clickable normally.
        //
        // topOffset chosen as 64 dp: top-bar Row is Modifier.padding
        // (horizontal=20, vertical=12) → 12 + ~36 + 12 = 60 dp. 64 dp
        // gives a 4-dp margin so any anti-aliasing / pointer slop at
        // the boundary doesn't steal the Back pill's tap.
        //
        // Pinned by OnboardingV2SemanticsTest:
        //   - edge_swipe_from_left_triggers_back
        //   - center_horizontal_drag_does_not_trigger_back
        //   - finale_has_no_edge_swipe_surface
        //   - overlay_does_not_overlap_top_bar_back_area   (round-4)
        //   - back_pill_click_survives_edge_overlay_layout (round-4)
        if (edgeSwipeBackEnabled) {
            OnboardingLeftEdgeSwipeSurface(
                triggerWidth = 34.dp,
                thresholdPx = 62.dp,
                topOffset = 64.dp,
                onEdgeSwipeBack = onEdgeSwipeBack,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }

        OnboardingToastV2(
            message = toastMessage,
            onDismiss = onToastDismiss,
        )
    }
}

/**
 * Test hook — renders a minimal top-bar-only host with the same
 * pricing-open a11y shroud logic as [OnboardingFlowV2Internal], so
 * `OnboardingV2SemanticsTest` can pin the shroud invariant
 * without wiring the full controller + AppContainer stack.
 *
 * When [pricingSheetOpen] is true, wraps the top-bar Box in the
 * same `Modifier.semantics(mergeDescendants = true) { invisibleToUser() }`
 * envelope the flow uses. TalkBack sees no Back pill; the semantics
 * tree exposes no "Back" text node.
 */
@Composable
internal fun PricingA11yShroudPreviewFor(pricingSheetOpen: Boolean) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = pricingSheetA11yShroudModifier(pricingSheetOpen)) {
            OnboardingTopBarV2(
                stepNumber = 3,
                totalSteps = 4,
                onBackClick = {},
            )
        }
    }
}

/**
 * Left-edge horizontal-drag catcher for the flow's back navigation.
 *
 * Overlay Box positioned at TopStart with a `topOffset` reserving the
 * top-bar area (so it does not overlap with the Back pill). The
 * pointer-input Row inside the outer box spans
 *   x = 0..triggerWidth  (default 34 dp)
 *   y = topOffset..parentHeight
 * A rightward drag total that exceeds `thresholdPx` (62 dp) fires
 * [onEdgeSwipeBack].
 *
 * Round-4 REDLINE P1-2 introduced [topOffset]. Round-3 shape put the
 * detector on the full height at TopStart, which overlapped the Back
 * pill's leftmost ~14 dp column and stole any drag starting there.
 *
 * Kept as a private-package composable so [OnboardingV2SemanticsTest]
 * can setContent on it directly and exercise
 * `performTouchInput { swipeRight(...) }` / coordinate taps without
 * needing the whole flow.
 */
@Composable
internal fun OnboardingLeftEdgeSwipeSurface(
    triggerWidth: Dp,
    thresholdPx: Dp,
    topOffset: Dp,
    onEdgeSwipeBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val thresholdInPx = with(density) { thresholdPx.toPx() }

    // Outer transparent Box reserves the layout slot but installs NO
    // pointer handler, so touches in the top `topOffset` × triggerWidth
    // area fall through to any Composable drawn earlier in Z-order
    // (e.g. the Back pill on the top bar).
    Box(
        modifier = modifier
            .width(triggerWidth)
            .fillMaxHeight(),
    ) {
        // Inner Box: shifted down by topOffset via padding-top; owns
        // the pointer detector. Compose's chained modifier semantics
        // make the pointer-input region equal to the inner Box's
        // layout box after the padding is applied — i.e., strictly
        // below `topOffset`.
        Box(
            modifier = Modifier
                .padding(top = topOffset)
                .fillMaxHeight()
                .fillMaxWidth()
                .pointerInput(Unit) {
                    var totalDx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDx = 0f },
                        onHorizontalDrag = { _, dx -> totalDx += dx },
                        onDragEnd = {
                            if (totalDx > thresholdInPx) onEdgeSwipeBack()
                            totalDx = 0f
                        },
                        onDragCancel = { totalDx = 0f },
                    )
                },
        )
    }
}
