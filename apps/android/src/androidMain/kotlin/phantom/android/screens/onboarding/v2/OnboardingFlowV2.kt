// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import android.util.Log
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.screens.onboarding.v2.steps.FinaleConfirmationStepV2
import phantom.android.screens.onboarding.v2.steps.HowStepV2
import phantom.android.screens.onboarding.v2.steps.IdentityKeyStepV2
import phantom.android.screens.onboarding.v2.steps.PermissionsStepV2
import phantom.android.screens.onboarding.v2.steps.PrivacyLevelStepV2
import phantom.android.screens.onboarding.v2.steps.WelcomeStepV2
import phantom.android.ui.designv2.DesignV2Tokens
import phantom.core.crypto.DhKeyPair
import phantom.core.crypto.DhPrivateKey
import phantom.core.crypto.DhPublicKey

/**
 * OnboardingFlowV2 — 5-step gated flow with chrome + finale confirmation.
 *
 * Structure (per round-1 REDLINE 2026-08-01, P1-2 + P1-3):
 *
 *   Runtime chrome (top bar + cipher background + status-bar inset) is
 *   provided by [OnboardingV2HostFrame], which BOTH this composable AND
 *   the debug showcase use. That guarantees the Paparazzi goldens
 *   render inside the SAME frame the on-device runtime uses. Step dots
 *   are NOT a frame-level overlay any more — each step body renders its
 *   own dots ABOVE its primary CTA per handoff `Onboarding.dc.html`
 *   (previous "overlay at BottomCenter" put dots BELOW the CTA which
 *   diverged from the design).
 *
 * Navigation contract (per redline C4, "Forward navigation is gated"):
 *
 *   - Forward: only via each step's `onContinueClick` — which itself is
 *     gated by [canAdvanceFromV2]. No free forward swipe. The
 *     [AnimatedContent] here is a stateless renderer of whatever
 *     `currentStep` says; it does NOT expose a bidirectional gesture.
 *   - Back: system back button ([BackHandler]) + Back-pill click on
 *     [OnboardingTopBarV2] + left-edge horizontal drag > 62 dp with
 *     the drag origin inside the leftmost 34 dp of the screen. All
 *     three decrement `currentStep` without validation.
 *
 * BackHandler contract (round-1 REDLINE P1-4):
 *   - Welcome         : NOT installed → back bubbles to OS ("exit app").
 *   - How / Identity / Privacy / Permissions : installed with body =
 *     goBack() → decrement currentStep.
 *   - FinaleConfirmation : installed with EMPTY body → back is
 *     INTERCEPTED and CONSUMED, no navigation. Previous shape (enabled=
 *     false → bubble to OS) let the OS pop the activity from a
 *     post-finalize state, which is misleading because the identity has
 *     been created; the user must acknowledge via the Continue CTA.
 *
 * Commit-2 shape: [WelcomeStepV2] and [HowStepV2] render real content.
 * Identity / Privacy / Permissions / FinaleConfirmation render
 * placeholder bodies (see their file KDoc) with a disabled Continue CTA
 * — so the navigation gate design is exercisable end-to-end today, but
 * nothing accidentally advances past a not-yet-built step. Commits 3-5
 * fill each placeholder with its real body.
 *
 * [container] and [onComplete] are received so the finalize path in
 * Commit 5 has the same signature the caller (MainActivity) already
 * passes to the old [phantom.android.screens.onboarding.OnboardingScreen].
 * Commit 2 does not use either — they're forwarded to whichever step
 * becomes the terminal transition point (Permissions "Done" in Commit 5).
 */
@Composable
fun OnboardingFlowV2(
    container: AppContainer,
    onComplete: () -> Unit,
) {
    // Real production wiring for the two-phase finalize. See
    // [OnboardingFinalizeController] for the state-machine contract.
    val controller = remember(container) {
        OnboardingFinalizeController(
            createOrLoad = { username -> container.identityManager.createOrLoad(username) },
            initMessaging = { record, keyPair ->
                container.initMessaging(
                    record,
                    DhKeyPair(
                        DhPublicKey(keyPair.publicKey.bytes),
                        DhPrivateKey(keyPair.privateKey.bytes),
                    ),
                )
            },
            onError = { throwable ->
                // Log details for diagnostics; the user-visible message
                // is a stable string produced by the controller.
                Log.w("OnboardingV2", "finalize error", throwable)
            },
        )
    }
    OnboardingFlowV2Internal(
        onComplete = onComplete,
        controller = controller,
    )
}

/**
 * Test-facing overload — accepts a pre-constructed
 * [OnboardingFinalizeController]. Contract tests exercise the
 * controller directly for its state-machine assertions (double-tap,
 * cancellation, retry after phase-1 vs phase-2 failure); this UI
 * overload exists so a future integration test can inject a fake
 * controller and assert flow-level wiring.
 */
@Composable
internal fun OnboardingFlowV2Internal(
    onComplete: () -> Unit,
    controller: OnboardingFinalizeController,
) {
    var currentStep by remember { mutableStateOf(OnboardingStepV2.Welcome) }
    var formState by remember { mutableStateOf(OnboardingFormStateV2()) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Surface controller's transient error via the existing toast slot,
    // then dismiss so it does not re-fire.
    val transient = controller.transientErrorMessage
    LaunchedEffect(transient) {
        if (transient != null) {
            toastMessage = transient
            controller.dismissTransientError()
        }
    }

    // On success, the controller reaches Complete — advance to Finale
    // and seed the signingPublicKeyHex for the confirmation UI.
    val finalizeState = controller.state
    LaunchedEffect(finalizeState) {
        if (finalizeState is FinalizeState.Complete) {
            if (currentStep != OnboardingStepV2.FinaleConfirmation) {
                formState = formState.copy(
                    signingPublicKeyHex = finalizeState.record.signingPublicKeyHex,
                )
                currentStep = OnboardingStepV2.FinaleConfirmation
            }
        }
    }

    // Round-1 REDLINE Commit-3 §P1-1: back navigation is locked once
    // the finalize controller reaches Persisted or later — the
    // persisted record is immutable, so returning to Identity to
    // "change" the username would be silently ignored.
    val backLocked = isBackNavigationLockedByFinalize(controller.state)
    val goBack: () -> Unit = {
        if (!backLocked) {
            val prev = OnboardingStepV2.entries
                .firstOrNull { it.ordinalInFlow == currentStep.ordinalInFlow - 1 }
            if (prev != null) currentStep = prev
        }
    }
    val goNext: () -> Unit = {
        if (canAdvanceFromV2(currentStep, formState)) {
            val next = OnboardingStepV2.entries
                .firstOrNull { it.ordinalInFlow == currentStep.ordinalInFlow + 1 }
            if (next != null) currentStep = next
        }
    }

    // BackHandler routing.
    //   Welcome: no handler → OS default (exit).
    //   FinaleConfirmation OR back-nav-locked-by-finalize: install a
    //     no-op handler that ABSORBS the OS Back. On Finale, the user
    //     leaves via the Continue CTA. When the finalize controller
    //     is Persisted (or later), backward navigation from Permissions
    //     would try to unwind an already-committed identity — no-op.
    //   Other steps: goBack decrements currentStep.
    if (currentStep == OnboardingStepV2.FinaleConfirmation || backLocked) {
        BackHandler(enabled = true) { /* intentionally absorbed */ }
    } else if (currentStep != OnboardingStepV2.Welcome) {
        BackHandler(enabled = true) { goBack() }
    }

    // Runtime uses real system-bar insets. Showcase passes the same
    // Dp value derived from a fixed device profile so goldens match
    // on-device layout.
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    OnboardingV2HostFrame(
        currentStep = currentStep,
        topInset = statusBarTop,
        onBackClick = goBack,
        // Round-2 REDLINE P1-1: predicate excludes both Welcome AND
        // FinaleConfirmation. Previous shape `currentStep != Welcome`
        // left the left-edge swipe active on Finale, so the user
        // could gesture back to Permissions after identity was
        // already created. `isEdgeSwipeBackFromEnabled` is a pure
        // function in OnboardingStateV2.kt and is pinned by a unit
        // test in OnboardingV2StateTest.
        edgeSwipeBackEnabled = isEdgeSwipeBackFromEnabled(currentStep) && !backLocked,
        onEdgeSwipeBack = goBack,
        toastMessage = toastMessage,
        onToastDismiss = { toastMessage = null },
    ) {
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                val goingForward =
                    targetState.ordinalInFlow > initialState.ordinalInFlow
                val duration = 220
                if (goingForward) {
                    slideInHorizontally(tween(duration)) { it / 2 } + fadeIn(tween(duration)) togetherWith
                        slideOutHorizontally(tween(duration)) { -it / 2 } + fadeOut(tween(duration))
                } else {
                    slideInHorizontally(tween(duration)) { -it / 2 } + fadeIn(tween(duration)) togetherWith
                        slideOutHorizontally(tween(duration)) { it / 2 } + fadeOut(tween(duration))
                }
            },
            label = "onboarding-step",
            modifier = Modifier.fillMaxSize(),
        ) { step ->
            when (step) {
                OnboardingStepV2.Welcome -> WelcomeStepV2(onContinueClick = goNext)
                OnboardingStepV2.How -> HowStepV2(
                    dotsIndex = step.dotsIndex,
                    onContinueClick = goNext,
                )
                OnboardingStepV2.Identity -> IdentityKeyStepV2(
                    formState = formState,
                    dotsIndex = step.dotsIndex,
                    onFormStateChange = { formState = it },
                    onContinueClick = goNext,
                )
                OnboardingStepV2.Privacy -> PrivacyLevelStepV2(
                    formState = formState,
                    dotsIndex = step.dotsIndex,
                    onFormStateChange = { formState = it },
                    onContinueClick = goNext,
                    onGhostLockClick = { toastMessage = "Phantom Pro — coming soon." },
                )
                OnboardingStepV2.Permissions -> PermissionsStepV2(
                    formState = formState,
                    dotsIndex = step.dotsIndex,
                    onFormStateChange = { formState = it },
                    onDoneClick = {
                        // Delegate the 2-phase state machine (double-tap
                        // guard, createOrLoad, initMessaging, retry
                        // semantics, cancellation handling) to the
                        // controller. Advancement to FinaleConfirmation
                        // happens via the LaunchedEffect above that
                        // observes `controller.state` transitioning to
                        // Complete.
                        scope.launch {
                            controller.finalize(formState.username)
                        }
                    },
                )
                OnboardingStepV2.FinaleConfirmation -> FinaleConfirmationStepV2(
                    formState = formState,
                    onContinueClick = onComplete,
                    onKeyCopied = {
                        // Round-1 REDLINE Commit-3 §P2-1: Copy needs
                        // acknowledgement per handoff. Route through
                        // the existing onboarding toast slot so the
                        // feedback re-uses the flow's Toast composable.
                        toastMessage = "Key copied to clipboard."
                    },
                )
            }
        }
    }
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
