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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
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
            // Round-2 REDLINE on Commit 4 §P1-1: dual-write the
            // user's selected privacy mode to BOTH storage surfaces
            // `AppContainer.setPrivacyMode` mirrors — the canonical
            // `TransportPreferences.privacyMode` (read by
            // `TransportManager`) AND the legacy `phantom_prefs`
            // SharedPreferences key `privacy_mode` (read by
            // `ChatScreen`'s read-receipt gate). Round-1 amend wrote
            // only the canonical store, so a Private-selecting user
            // got Private transport but kept sending read receipts as
            // Standard. `applyPrivacyModeToFirstRunStores` does both
            // writes WITHOUT the socket teardown / hint clearing that
            // `setPrivacyMode` also does — first-run onboarding has
            // no active transport to tear down.
            savePrivacyMode = { mode -> container.applyPrivacyModeFromOnboarding(mode) },
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
    // Commit 4: Pricing bottom sheet visibility. Opens when the user
    // taps the Ghost Mode segment on Privacy step (or the Unlock CTA
    // inside the Ghost tier card). Closes via backdrop / grab-strip /
    // any tier CTA (which also fires a "coming soon" toast).
    //
    // Round-2 REDLINE on Commit 4 §P1-2: track TWO states —
    //   `pricingSheetVisible` : user intent — true while the sheet
    //                            should be shown; flips to false the
    //                            instant the user dismisses.
    //   `pricingSheetPresent` : sheet is in the composition — stays
    //                            true through the ~220 ms exit
    //                            animation, then flips to false when
    //                            the sheet fires `onFullyDismissed`.
    //
    // The BackHandler, edge-swipe overlay, and a11y shroud MUST gate
    // on `pricingSheetPresent` — otherwise the modal contract breaks
    // during exit (Back would reach the flow, background nodes would
    // become TalkBack-reachable mid-fade, etc.).
    var pricingSheetVisible by remember { mutableStateOf(false) }
    var pricingSheetPresent by remember { mutableStateOf(false) }
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
    //   PricingSheet visible: highest priority — start the sheet's
    //     exit animation. Round-2 REDLINE §P1-2: setting
    //     pricingSheetVisible = false starts fade/slide-out; the
    //     sheet fires onFullyDismissed when the animation completes,
    //     flipping pricingSheetPresent to false so subsequent Back
    //     presses use the ordinary flow routing.
    //   PricingSheet in exit animation (present && !visible): absorb
    //     Back — the user has already asked to dismiss; another Back
    //     press mid-fade would be surprising.
    //   Welcome: no handler → OS default (exit).
    //   FinaleConfirmation OR back-nav-locked-by-finalize: install a
    //     no-op handler that ABSORBS the OS Back. On Finale, the user
    //     leaves via the Continue CTA. When the finalize controller
    //     is Persisted (or later), backward navigation from Permissions
    //     would try to unwind an already-committed identity — no-op.
    //   Other steps: goBack decrements currentStep.
    if (pricingSheetVisible) {
        BackHandler(enabled = true) { pricingSheetVisible = false }
    } else if (pricingSheetPresent) {
        BackHandler(enabled = true) { /* mid-exit-animation — absorbed */ }
    } else if (currentStep == OnboardingStepV2.FinaleConfirmation || backLocked) {
        BackHandler(enabled = true) { /* intentionally absorbed */ }
    } else if (currentStep != OnboardingStepV2.Welcome) {
        BackHandler(enabled = true) { goBack() }
    }

    // Runtime uses real system-bar insets. Showcase passes the same
    // Dp value derived from a fixed device profile so goldens match
    // on-device layout.
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(modifier = Modifier.fillMaxSize()) {
    // Round-1 REDLINE on Commit 4 §P1-3: when the pricing sheet is
    // present (rendered), the flow content beneath it MUST be
    // invisible to accessibility services — screen readers would
    // otherwise announce the Back pill / Continue button / segment
    // tabs sitting behind the modal, giving the impression they are
    // interactive when they are not.
    //
    // Round-2 REDLINE on Commit 4 §P1-3: the shroud gates on
    // `pricingSheetPresent` (stays true through the exit animation),
    // not `pricingSheetVisible` — otherwise TalkBack would suddenly
    // start traversing background nodes the moment the user starts
    // dismissing, mid-fade.
    //
    // Round-2 REDLINE on Commit 4 §P1/P2-5: shroud logic extracted
    // into [pricingSheetA11yShroudModifier] so the test-only
    // `PricingA11yShroudPreviewFor` composable and the real flow
    // share ONE code path.
    Box(modifier = pricingSheetA11yShroudModifier(pricingSheetPresent)) {
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
        // Also disabled while the pricing sheet is present so a
        // left-edge swipe under the modal doesn't sneak back through it.
        edgeSwipeBackEnabled = isEdgeSwipeBackFromEnabled(currentStep) && !backLocked && !pricingSheetPresent,
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
                    // Commit 4: Ghost Mode tap opens the Pricing sheet
                    // instead of firing a toast. The controller in the
                    // step composable BOTH intercepts the Ghost
                    // segment tap AND the "Unlock with Phantom Pro"
                    // CTA inside the Ghost tier card — both surfaces
                    // funnel through this callback.
                    onGhostLockClick = {
                        pricingSheetPresent = true   // mount + shroud immediately
                        pricingSheetVisible = true   // start enter animation
                    },
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
                            // Round-1 REDLINE on Commit 4 §P1-1: pass
                            // the user's chosen privacyMode so the
                            // controller can persist it before
                            // initMessaging fires.
                            controller.finalize(formState.username, formState.privacyMode)
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
    }  // close the a11y-shroud Box wrapping the host frame
        // Commit 4: Pricing bottom sheet — modal overlay above the
        // host frame + top bar + toast. Wrapped in the outer Box so
        // it lives on top of everything painted by the host frame.
        //
        // Round-2 REDLINE on Commit 4 §P1-2 lifecycle:
        //   Open: set BOTH present + visible → mount + enter animation.
        //   Dismiss (backdrop / grab-strip / close-X / Back / CTA):
        //     set visible = false → exit animation runs.
        //   onFullyDismissed: set present = false → unmount + lift
        //     shroud / BackHandler / edge-swipe overlay guards.
        //
        // Any CTA tap closes the sheet AND fires a "coming soon"
        // toast per handoff. The toast uses the same slot as the
        // controller's transient-error surface — safe because the
        // sheet closes before the toast renders (the toast is
        // subscribed via a LaunchedEffect on toastMessage which
        // handles the update on the next frame).
        OnboardingPricingSheetV2(
            visible = pricingSheetVisible,
            onDismiss = { pricingSheetVisible = false },
            onCtaSelected = { cta ->
                pricingSheetVisible = false
                toastMessage = "$cta — coming soon."
            },
            onFullyDismissed = { pricingSheetPresent = false },
        )
    }
}

/**
 * Round-2 REDLINE on Commit 4 §P1/P2-5: single source of truth for
 * the "background is invisible to a11y while the pricing sheet is
 * present" invariant. Both [OnboardingFlowV2Internal] AND the
 * test-only [PricingA11yShroudPreviewFor] compose this modifier so
 * a semantics test on the preview reflects the real flow's shape.
 *
 * Round-3 REDLINE on Commit 4 §P1-3 handoff feature 2: while the
 * pricing sheet is present, the flow content beneath it is BLURRED
 * (per handoff `Onboarding.dc.html` line 496 —
 * `backdropFilter:'blur(3px)'`). Compose's `Modifier.blur()` blurs
 * the modifier's OWN subtree — applied here to the flow content
 * (which IS the content sitting beneath the sheet in Z-order), this
 * produces the same visual as CSS `backdrop-filter` on the sheet's
 * backdrop overlay would. The blur radius matches the handoff's
 * 3 dp spec. `BlurredEdgeTreatment.Unbounded` prevents a hard blur
 * cutoff at the flow-content edge.
 */
