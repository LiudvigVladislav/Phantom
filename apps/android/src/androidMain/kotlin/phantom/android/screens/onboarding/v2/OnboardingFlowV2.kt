// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
import phantom.core.identity.IdentityRecord
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

/**
 * Round-14 REDLINE §P1 pin — typed finalize outcome returned to
 * the single caller coroutine. The caller then performs the
 * atomic success-transition (seed hex → advance step → set
 * phase = Completed) in one uninterruptible sequence, so no
 * intermediate `phase = Completed && currentStep = Permissions`
 * state can be observed by a rotation between two independent
 * effect writes (round-13 shape suffered from that gap).
 *
 * Terminal resolution:
 *   - `FinalizeState.Complete(record)` → [FinalizeOutcome.Completed(record)]
 *   - `FinalizeState.Idle`             → [FinalizeOutcome.FailedBeforePersistence]
 *       (finalize returned back to Idle → phase-0 error before
 *        any disk write; user should be able to fix
 *        username/privacy and retry from a clean slate.)
 *   - `FinalizeState.Persisted`        → [FinalizeOutcome.FailedAfterPersistence]
 *       (identity + mode already on disk; initMessaging failed;
 *        caller keeps phase = InFlight so Back stays locked and
 *        a Done re-tap hits the controller's Persisted → Complete
 *        short-circuit.)
 *   - `FinalizeState.Working`          → [FinalizeOutcome.FailedAfterPersistence]
 *       (defensive; the suspend function shouldn't return while
 *        still Working. Same handling as Persisted-error — keep
 *        Back locked, wait for user re-tap.)
 */
internal sealed interface FinalizeOutcome {
    data class Completed(val record: IdentityRecord) : FinalizeOutcome
    object FailedBeforePersistence : FinalizeOutcome
    object FailedAfterPersistence : FinalizeOutcome
}

/**
 * Round-16 REDLINE §P1 pin — pure reducer describing the atomic
 * state advance from a [FinalizeOutcome]. Extracted out of the
 * caller coroutine so a Kotlin unit test can assert the exact
 * transition for each outcome without going through Compose UI.
 * A refactor that splits the writes across parallel effects
 * would either stop calling this reducer or feed the wrong
 * fields into it — both cases fail
 * `OnboardingV2FinalizeOutcomeContractTest`.
 *
 * Fields carry `null` when the outcome does NOT change that
 * particular slot (Failed*Persistence outcomes preserve
 * `signingPublicKeyHex` and `currentStep` in-place; only the
 * phase transitions).
 */
internal data class FinalizeAdvance(
    val newSigningPublicKeyHex: String?,
    val newStep: OnboardingStepV2?,
    val newPhase: OnboardingFinalizePhase,
)

internal fun applyFinalizeOutcome(outcome: FinalizeOutcome): FinalizeAdvance = when (outcome) {
    is FinalizeOutcome.Completed -> FinalizeAdvance(
        newSigningPublicKeyHex = outcome.record.signingPublicKeyHex,
        newStep = OnboardingStepV2.FinaleConfirmation,
        newPhase = OnboardingFinalizePhase.Completed,
    )
    FinalizeOutcome.FailedBeforePersistence -> FinalizeAdvance(
        newSigningPublicKeyHex = null,
        newStep = null,
        newPhase = OnboardingFinalizePhase.NotStarted,
    )
    FinalizeOutcome.FailedAfterPersistence -> FinalizeAdvance(
        newSigningPublicKeyHex = null,
        newStep = null,
        newPhase = OnboardingFinalizePhase.InFlight,
    )
}

/**
 * Round-17 REDLINE §P1 pin — production-side writer interface used
 * by [applyAndCommitFinalizeOutcome]. Production `Flow` provides an
 * anonymous impl that pokes the composable's three
 * `rememberSaveable` slots (signingPublicKeyHex, currentStep,
 * finalizePhase). Tests provide a capturing impl that records the
 * write ORDER and VALUES.
 *
 * Ownership scope: for the terminal finalize transition, these
 * methods own the currently present direct assignment forms of
 * `signingPublicKeyHex`, `currentStep = FinaleConfirmation`, and
 * `finalizePhase`. Regular navigation (e.g. Next/Back reassigning
 * `currentStep`) writes those fields elsewhere and is NOT
 * governed by this interface.
 *
 * `OnboardingV2FinalizeOutcomeContractTest`'s source-contract
 * test is a NON-EXHAUSTIVE tripwire: it fails-red on the
 * straight-forward assignment shapes any reasonable refactor
 * would produce, not on determined indirections through
 * intermediate vals or reflected setters. Total ownership
 * requires the planned sealed state holder (Commit 6).
 */
internal interface FinalizeStateWriter {
    fun writeSigningPublicKeyHex(hex: String)
    fun advanceToFinaleConfirmation()
    fun setFinalizePhase(phase: OnboardingFinalizePhase)
}

/**
 * Round-17 REDLINE §P1 pin — single production helper that Done
 * tap AND resume LaunchedEffect both invoke. Reads outcome via
 * `applyFinalizeOutcome` reducer, then commits via [writer].
 * Guarantees identical write order across both flows.
 */
internal fun applyAndCommitFinalizeOutcome(
    outcome: FinalizeOutcome,
    writer: FinalizeStateWriter,
) {
    val advance = applyFinalizeOutcome(outcome)
    advance.newSigningPublicKeyHex?.let { writer.writeSigningPublicKeyHex(it) }
    if (advance.newStep == OnboardingStepV2.FinaleConfirmation) {
        writer.advanceToFinaleConfirmation()
    }
    writer.setFinalizePhase(advance.newPhase)
}

/**
 * `internal` (was `private`) so
 * `OnboardingV2FinalizeOutcomeContractTest` can pin the terminal-
 * state → outcome mapping without going through Compose UI. The
 * outcome contract is the load-bearing invariant round-14 shipped
 * for atomic success-transition; a regression to the round-13
 * split-writer shape would silently reintroduce the "stranded on
 * Permissions with Back locked" recreation bug otherwise.
 */
internal suspend fun runFinalize(
    controller: OnboardingFinalizeController,
    username: String,
    privacyMode: phantom.core.transport.PrivacyMode,
): FinalizeOutcome {
    controller.finalize(username, privacyMode)
    return when (val end = controller.state) {
        is FinalizeState.Complete -> FinalizeOutcome.Completed(end.record)
        is FinalizeState.Idle -> FinalizeOutcome.FailedBeforePersistence
        is FinalizeState.Persisted -> FinalizeOutcome.FailedAfterPersistence
        is FinalizeState.Working -> FinalizeOutcome.FailedAfterPersistence
    }
}

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
    // Round-10 REDLINE §P1 pin: rememberSaveable everywhere so a
    // config change (rotation, dark-mode toggle, font-scale change)
    // preserves the flow's state. Prior plain `remember` reset
    // currentStep to Welcome + wiped formState.username on every
    // rotation.
    var currentStep by rememberSaveable(stateSaver = OnboardingStepV2Saver) {
        mutableStateOf(OnboardingStepV2.Welcome)
    }
    var formState by rememberSaveable(stateSaver = OnboardingFormStateV2Saver) {
        mutableStateOf(OnboardingFormStateV2())
    }
    var toastMessage by rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.autoSaver(),
    ) { mutableStateOf<String?>(null) }
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
    // Round-10 REDLINE §P1 pin: pricing sheet visibility also
    // survives rotation — if the user opens the sheet then rotates,
    // it stays open. `rememberSaveable` on the primitive Boolean
    // is a one-line change.
    var pricingSheetVisible by rememberSaveable { mutableStateOf(false) }
    var pricingSheetPresent by rememberSaveable { mutableStateOf(false) }
    // Round-12 REDLINE §P1 pin: durable three-phase finalize state.
    // Prior round-11 was a single Boolean set on Done and never
    // cleared — a rotation on Finale replayed the entire finalize
    // path against a fresh Idle controller (doubled initMessaging),
    // and error-before-persistence left Back locked while the
    // controller had reverted to Idle.
    //
    // Round-12 rules (see OnboardingFinalizePhase KDoc):
    //   - NotStarted → Back unlocked; Done can re-fire.
    //   - InFlight   → Back locked; resume LaunchedEffect replays
    //                  finalize IFF controller state is Idle.
    //   - Completed  → Back locked; resume NEVER re-invokes finalize.
    //
    // Transitions:
    //   Done tap                → InFlight
    //   controller Complete     → Completed
    //   controller error before
    //     persistence (state
    //     ends up back at Idle) → NotStarted (allow user to fix
    //                              username/privacy and retry)
    var finalizePhase by rememberSaveable(stateSaver = OnboardingFinalizePhaseSaver) {
        mutableStateOf(OnboardingFinalizePhase.NotStarted)
    }
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

    // Round-14 REDLINE §P1 pin: atomic success-transition owned
    // by the caller coroutine. Round-13 shape split responsibility
    // between the coroutine (wrote phase=Completed on Complete)
    // and a separate Complete-observer LaunchedEffect (advanced
    // currentStep + seeded hex). A rotation between those two
    // writes left `phase = Completed && currentStep = Permissions`
    // — post-restart the resume effect skipped (only InFlight
    // triggers replay) and the Complete-observer no longer had
    // a Complete controller state to observe. User stranded on
    // Permissions with Back locked.
    //
    // Round-14 collapses the transition into ONE atomic block
    // inside the coroutine (seed hex → advance step → set phase =
    // Completed). No Compose observer touches the transition; no
    // gap between the two writes is possible.
    //
    // Post-recreation resume — fires EXACTLY ONCE per composition
    // on `LaunchedEffect(Unit)`, which for post-rotation IS the
    // newly-composed instance. Runs the SAME atomic block as the
    // Done tap.
    // Round-17 REDLINE §P1 pin: single production writer for the
    // terminal finalize transition. Both call sites (Done tap AND
    // this resume LaunchedEffect) delegate to the helper defined
    // above — the writer owns the direct terminal-assignment
    // shapes for `formState.signingPublicKeyHex`, `currentStep =
    // FinaleConfirmation`, and `finalizePhase`. Guarded by
    // `OnboardingV2FinalizeOutcomeContractTest`'s source-contract
    // test — a non-exhaustive tripwire (fails-red on straight-
    // forward assignments; determined indirections through
    // intermediate vals can bypass). Total ownership will come
    // with the sealed state holder in Commit 6.
    val finalizeStateWriter = object : FinalizeStateWriter {
        override fun writeSigningPublicKeyHex(hex: String) {
            formState = formState.copy(signingPublicKeyHex = hex)
        }
        override fun advanceToFinaleConfirmation() {
            currentStep = OnboardingStepV2.FinaleConfirmation
        }
        override fun setFinalizePhase(phase: OnboardingFinalizePhase) {
            finalizePhase = phase
        }
    }

    LaunchedEffect(Unit) {
        if (finalizePhase == OnboardingFinalizePhase.InFlight &&
            controller.state is FinalizeState.Idle &&
            currentStep != OnboardingStepV2.FinaleConfirmation
        ) {
            val outcome = runFinalize(
                controller = controller,
                username = formState.username,
                privacyMode = formState.privacyMode,
            )
            applyAndCommitFinalizeOutcome(outcome, finalizeStateWriter)
        }
    }

    // Round-1 REDLINE Commit-3 §P1-1: back navigation is locked once
    // the finalize controller reaches Persisted or later — the
    // persisted record is immutable, so returning to Identity to
    // "change" the username would be silently ignored.
    // Round-12 REDLINE §P1 pin: OR-include the durable phase.
    // InFlight OR Completed → Back locked. NotStarted defers to
    // the controller state (Idle → unlocked; Persisted/Working →
    // locked via `isBackNavigationLockedByFinalize`). The
    // error-before-persistence LaunchedEffect above returns
    // phase to NotStarted on Idle so Back unlocks for user
    // recovery.
    val backLocked =
        isBackNavigationLockedByFinalize(controller.state) ||
            finalizePhase != OnboardingFinalizePhase.NotStarted
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
        // Round-11 REDLINE §P1 pin: replaced the full-screen slide±50%
        // + fade cross-fade with a lighter fade-only transition. Prior
        // shape moved every pixel of both outgoing and incoming step
        // through a 50%-width horizontal translation for 220 ms — with
        // the cipher background's 3 infinite animations + offscreen
        // DstIn layer still ticking, the frame budget on Tecno spiked
        // during Welcome → How and the user perceived "проваливающийся
        // экран". A pure fade retains a felt-transition without the
        // per-pixel translation cost.
        //
        // Direction (goingForward/back) no longer needs a bespoke curve
        // — a fade reads correctly in both directions. The step-dots +
        // top-bar chrome carry the "which way is forward" cue.
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                fadeIn(tween(180)) togetherWith fadeOut(tween(160))
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
                OnboardingStepV2.Permissions -> {
                    // Round-4 REDLINE on Commit 5:
                    //   §P1-1 real toggle contract — tap can turn
                    //         notifications OFF as well as ON.
                    //   §P1-2 fail-loud commit on Dispatchers.IO,
                    //         in-flight guard, launcher callback
                    //         captures `granted` and records the
                    //         "requested-before" bit + persists
                    //         opt-in on grant.
                    //   §P1-3 full 4-signal gate incl. per-channel
                    //         importance + permanent-denial fallback.
                    val notifStateHolder = phantom.android.screens.onboarding.v2
                        .rememberNotificationsPermissionState()
                    val currentGate = notifStateHolder.gateState.value
                    val currentNotifState = phantom.android.notifications
                        .deriveNotificationsToggleState(currentGate)

                    val currentContext = androidx.compose.ui.platform.LocalContext.current

                    // In-flight guard — blocks re-tap while the
                    // suspend commit is running or the launcher
                    // callback is pending. Any tap during the
                    // window is dropped (documented no-op — the
                    // toggle is disabled visually via `enabled`
                    // hoisting if needed by design; for now the
                    // guard just no-ops in the handler).
                    var tapInFlight by remember { mutableStateOf(false) }

                    val notificationsLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission(),
                    ) { granted ->
                        // Round-4 §P1-2 pin: capture `granted`.
                        // Always mark "requested-before" so future
                        // taps can infer permanent-denial via
                        // shouldShowRequestPermissionRationale.
                        phantom.android.screens.onboarding.v2
                            .markNotificationPermissionAsRequested(currentContext)
                        if (granted) {
                            // On grant, persist opt-in synchronously
                            // via the suspend fail-loud writer.
                            // If the write fails, DO NOT flip the
                            // in-memory holder — refresh will read
                            // the stale-false pref and the toggle
                            // stays OFF; log surfaces the failure.
                            scope.launch {
                                val ok = phantom.android.screens.onboarding.v2
                                    .writeUserOptedInToNotifications(
                                        context = currentContext,
                                        value = true,
                                    )
                                if (!ok) {
                                    Log.e(
                                        "OnboardingV2",
                                        "NOTIF opt_in_write_failed after runtime grant — " +
                                            "state stays OFF pending user retry",
                                    )
                                }
                                notifStateHolder.refresh()
                                tapInFlight = false
                            }
                        } else {
                            // Grant refused — leave opt-in as-is,
                            // just refresh so permanent-denial hint
                            // updates via the marked-requested bit.
                            notifStateHolder.refresh()
                            tapInFlight = false
                        }
                    }

                    PermissionsStepV2(
                    formState = formState,
                    dotsIndex = step.dotsIndex,
                    onFormStateChange = { formState = it },
                    notificationsState = currentNotifState,
                    onRequestNotificationPermission = {
                        if (tapInFlight) return@PermissionsStepV2
                        val action = phantom.android.notifications
                            .decideNotificationsTapAction(
                                gate = currentGate,
                                sdkInt = Build.VERSION.SDK_INT,
                            )
                        when (action) {
                            phantom.android.notifications
                                .NotificationsTapAction.OptOutAppLevelOnly -> {
                                // Tap while ON → turn OFF. Persist
                                // false via the suspend fail-loud
                                // writer; block re-tap during the
                                // window; surface failure if the
                                // commit is rejected.
                                tapInFlight = true
                                scope.launch {
                                    val ok = phantom.android.screens.onboarding.v2
                                        .writeUserOptedInToNotifications(
                                            context = currentContext,
                                            value = false,
                                        )
                                    if (!ok) {
                                        Log.e(
                                            "OnboardingV2",
                                            "NOTIF opt_out_write_failed — state stays ON",
                                        )
                                    }
                                    notifStateHolder.refresh()
                                    tapInFlight = false
                                }
                            }
                            phantom.android.notifications
                                .NotificationsTapAction.OptInAppLevelOnly -> {
                                // Tap while OFF and all OS gates
                                // already ready → flip opt-in true.
                                tapInFlight = true
                                scope.launch {
                                    val ok = phantom.android.screens.onboarding.v2
                                        .writeUserOptedInToNotifications(
                                            context = currentContext,
                                            value = true,
                                        )
                                    if (!ok) {
                                        Log.e(
                                            "OnboardingV2",
                                            "NOTIF opt_in_write_failed — state stays OFF",
                                        )
                                    }
                                    notifStateHolder.refresh()
                                    tapInFlight = false
                                }
                            }
                            phantom.android.notifications
                                .NotificationsTapAction.LaunchRuntimePermission -> {
                                // In-flight guard set here too;
                                // the launcher callback clears it.
                                tapInFlight = true
                                notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            phantom.android.notifications
                                .NotificationsTapAction.OpenAppNotificationSettings ->
                                phantom.android.screens.onboarding.v2
                                    .openAppNotificationSettings(currentContext)
                            phantom.android.notifications
                                .NotificationsTapAction.OpenMessageChannelSettings ->
                                phantom.android.screens.onboarding.v2
                                    .openMessageChannelSettings(currentContext)
                        }
                    },
                    onDoneClick = {
                        // Delegate the THREE-phase state machine
                        // (double-tap guard, savePrivacyMode / createOrLoad
                        // / initMessaging with retry semantics + cancellation
                        // handling) to the controller. Round-14 REDLINE §P1:
                        // advancement to FinaleConfirmation is owned by this
                        // coroutine's atomic outcome-match block below — the
                        // previous LaunchedEffect(controller.state) Compose
                        // observer was removed to avoid the observable gap
                        // between finalize's return and the step advance.
                        // Round-18 REDLINE §P1 pin: pre-launch
                        // InFlight transition ALSO goes through the
                        // writer — no direct assignment of
                        // `finalizePhase` in the currently present
                        // shapes outside the writer's
                        // `setFinalizePhase` body. Guarded by the
                        // source-contract test (non-exhaustive
                        // tripwire — see FinalizeStateWriter KDoc).
                        finalizeStateWriter.setFinalizePhase(
                            OnboardingFinalizePhase.InFlight,
                        )
                        scope.launch {
                            val outcome = runFinalize(
                                controller = controller,
                                username = formState.username,
                                privacyMode = formState.privacyMode,
                            )
                            applyAndCommitFinalizeOutcome(outcome, finalizeStateWriter)
                        }
                    },
                    )  // PermissionsStepV2 close
                }  // OnboardingStepV2.Permissions block close
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
