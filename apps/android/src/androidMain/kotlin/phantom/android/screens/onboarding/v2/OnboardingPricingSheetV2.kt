// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.R
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2FontDisplay
import phantom.android.ui.designv2.DesignV2FontMono
import phantom.android.ui.designv2.DesignV2Tokens

/**
 * OnboardingPricingSheetV2 — Bottom sheet with Plus / Pro / Business
 * pricing tier cards. Opens when the user taps the Ghost Mode
 * segment on Step 3 (Privacy) or the "Unlock with Phantom Pro" CTA
 * inside the Ghost tier card.
 *
 * Layout matches handoff `Onboarding.dc.html` §buildPricing lines
 * 460-502 (round-1 REDLINE on Commit 4 §P1-2 pins the visual to the
 * exact handoff shape after the initial approximation diverged):
 *
 *   - Backdrop dims underlying flow at ~66 % opacity (`rgba(3,4,7,.66)`).
 *   - Panel bottom-anchored, max-height 82 % of the parent
 *     (`_sheetBox` shape); radius 26 dp top corners; padding
 *     16 dp horizontal / 22 dp bottom.
 *   - Header row: `PHANTOM PREMIUM` monospace label (11 sp / 2.8 sp
 *     tracking / TextQuaternary) on the left, circular 30 dp close-X
 *     button on the right.
 *   - Content is a vertical scroll region up to ~56 vh:
 *       - Plus tier — $3.99 · "More control."
 *       - Pro tier  — $9.99 · "Full control." — recommended, with
 *         GHOST MODE callout box.
 *       - Business tier — $19.99 · "For teams & organizations."
 *   - Footer: "Cancel any time. No data sold, ever." centered, 11.5 sp,
 *     TextQuaternary.
 *
 * Handoff visual features implemented in round-3 (was carved out
 * of round-2 amend; architect reinstated in round-3 REDLINE §P1-3):
 *
 *   - **Backdrop blur (3 dp)** per handoff `Onboarding.dc.html`
 *     line 496 (`backdropFilter:'blur(3px)'`). Applied via
 *     `Modifier.blur(3.dp)` on the flow content (the a11y-shrouded
 *     Box in `OnboardingFlowV2`). Compose's `Modifier.blur()` blurs
 *     the modifier's own subtree — since the flow content IS what
 *     sits beneath the sheet in Z-order, blurring it produces the
 *     same visual as CSS `backdrop-filter` on the sheet's backdrop.
 *   - **Panel top-shadow** per handoff line 337 (`boxShadow:
 *     '0 -24px 60px rgba(0,0,0,.55)'`). Applied via
 *     `Modifier.shadow(elevation = 12.dp)` on the panel Column.
 *     Because the panel is bottom-anchored, only the top edge is
 *     visible above the panel, which matches the handoff's
 *     directional "-24 px top" specification.
 *   - **Upward-drag resistance (-60 px cap, 0.35 dampening)** per
 *     handoff `_sheetDrag` line 320
 *     (`Math.max(-60, raw*.35)`). Grab-strip drag callback dampens
 *     upward motion to 35 % of raw and caps at -60 px so the user
 *     gets a small elastic pull without launching the panel off
 *     the top.
 *
 * Motion contract (round-1 REDLINE on Commit 4 §P1-2 pt 2): the
 * production shape uses a slide-up + fade-in enter transition and a
 * mirrored exit — via [AnimatedVisibility]. Compose semantics tests
 * that render this composable directly can pass
 * [animationsEnabled] = false to disable both, avoiding
 * `AppNotIdleException` under Robolectric (whose virtual animation
 * clock does not advance during `waitForIdle()`). The runtime
 * always leaves it enabled.
 *
 * Modal semantics (round-1 REDLINE on Commit 4 §P1-3): the panel
 * itself installs a pointer-consuming Box so a coordinate tap inside
 * the panel area DOES NOT fall through to the backdrop's dismiss
 * click. Backdrop taps dismiss; grab-strip taps dismiss; anywhere on
 * the panel body is intercepted and consumed. Caller composes the
 * sheet at the top of the flow's Z-stack; when [open] is true, the
 * caller also gates back-nav (BackHandler) and disables background
 * a11y traversal (via `Modifier.semantics(mergeDescendants = true) {
 * invisibleToUser() }` on the flow's content — see [OnboardingFlowV2]).
 *
 * Accessibility: backdrop is a Role.Button with click-action label
 * "Close pricing"; grab-strip is a separate Role.Button with the
 * same label; close-X is a Role.Button with label "Close pricing".
 * Tier CTAs each carry Role.Button + text "Upgrade to Plus/Pro/Business".
 * Decorative bits (tier hex icons, feature check rondels, close-X
 * path, grab-strip handle) are cleared via
 * `Modifier.clearAndSetSemantics { }`.
 */
@Composable
fun OnboardingPricingSheetV2(
    visible: Boolean,
    onDismiss: () -> Unit,
    onCtaSelected: (String) -> Unit,
    onFullyDismissed: () -> Unit = {},
    modifier: Modifier = Modifier,
    animationsEnabled: Boolean = true,
    scrollToBottomForShowcase: Boolean = false,
) {
    // Round-2 REDLINE on Commit 4 §P1-2: the pre-REDLINE shape used
    // `if (!open) return` to unmount immediately when the caller
    // flipped open to false — but that lifted the composable BEFORE
    // any exit animation could run. Because both `AnimatedVisibility`
    // calls received `visible = true` as a literal (inside the
    // `if (open)` gate), their `visible` state never flipped false,
    // so `fadeOut` and `slideOutVertically` NEVER executed.
    //
    // Fix: drive both AnimatedVisibility instances from a shared
    // `MutableTransitionState<Boolean>` that starts at false and
    // transitions to true / false based on the `visible` parameter.
    // The sheet stays in the composition until the transition is
    // fully idle at `false` (i.e., the exit animation has completed).
    // When that happens, [onFullyDismissed] fires so the caller can
    // lift its BackHandler + a11y shroud.
    if (animationsEnabled) {
        val transitionState = remember { MutableTransitionState(initialState = false) }
        // Sync target state from parameter on every recomposition.
        transitionState.targetState = visible

        // Fire onFullyDismissed exactly once when the exit
        // transition has fully settled at `false` (both target
        // and current == false).
        LaunchedEffect(
            transitionState.currentState,
            transitionState.targetState,
            transitionState.isIdle,
        ) {
            if (
                !transitionState.targetState &&
                !transitionState.currentState &&
                transitionState.isIdle
            ) {
                onFullyDismissed()
            }
        }

        // Only compose the sheet if either target OR current is
        // true — the composition unmounts only after exit fully
        // finishes.
        val needsComposition =
            transitionState.currentState || transitionState.targetState
        if (needsComposition) {
            Box(modifier = modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visibleState = transitionState,
                    enter = fadeIn(animationSpec = tween(durationMillis = 260)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 220)),
                ) {
                    Backdrop(onDismiss = onDismiss)
                }
                AnimatedVisibility(
                    visibleState = transitionState,
                    enter = slideInVertically(
                        animationSpec = tween(durationMillis = 300),
                        initialOffsetY = { it },
                    ),
                    exit = slideOutVertically(
                        animationSpec = tween(durationMillis = 220),
                        targetOffsetY = { it },
                    ),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Panel(
                        onDismiss = onDismiss,
                        onCtaSelected = onCtaSelected,
                        scrollToBottomForShowcase = scrollToBottomForShowcase,
                    )
                }
            }
        }
    } else {
        // Test / Paparazzi shape: no motion. `visible = false`
        // synchronously unmounts and fires `onFullyDismissed`;
        // `visible = true` renders panel + backdrop at rest.
        // Robolectric's `waitForIdle()` completes normally because
        // there is no pending animation.
        if (!visible) {
            LaunchedEffect(Unit) { onFullyDismissed() }
            return
        }
        Box(modifier = modifier.fillMaxSize()) {
            Backdrop(onDismiss = onDismiss)
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                Panel(
                    onDismiss = onDismiss,
                    onCtaSelected = onCtaSelected,
                    scrollToBottomForShowcase = scrollToBottomForShowcase,
                )
            }
        }
    }
}

@Composable
private fun Backdrop(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xA803040A)) // ~66 % opacity per handoff
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Close pricing",
                onClick = onDismiss,
            ),
    )
}

