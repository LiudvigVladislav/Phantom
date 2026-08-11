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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Typed finalize outcome returned to the single caller coroutine.
 * The caller feeds the outcome to
 * `finalizeHolder.applyFinalizeOutcome` — a single
 * sealed-state assignment.
 *
 * C6-a round-1 REDLINE §P1: `Completed` carries the
 * `signingPublicKeyHex` as a NON-NULL String (not the whole
 * `IdentityRecord`). Round-2 REDLINE §P1 tightened the filter:
 * ANY malformed hex (null / empty / wrong length / non-hex) is
 * routed to a NEW outcome [MissingKeyMaterial]. The sealed state
 * model never sees a `Completed` with anything other than a
 * valid Ed25519 hex; the null/malformed-hex case lands the
 * holder at
 * [OnboardingFinalizeState.MissingKeyRepairRequired] instead,
 * which surfaces a visible repair-required screen with an
 * "Exit onboarding" action.
 *
 * Terminal resolution (round-3 REDLINE §P2 pin — this table
 * matches the actual runFinalize + holder wiring):
 *   - `FinalizeState.Complete(record)` with a VALID Ed25519
 *     `signingPublicKeyHex` → [FinalizeOutcome.Completed(hex)]
 *     → holder → `Completed(hex)` → Finale opens.
 *   - `FinalizeState.Complete(record)` with a MALFORMED
 *     `signingPublicKeyHex` (null / empty / wrong length /
 *     non-hex) → [FinalizeOutcome.MissingKeyMaterial] → holder
 *     → `MissingKeyRepairRequired`. Controller state stays at
 *     `Complete(brokenRecord)` — the composable's early return
 *     into `OnboardingRepairRequiredScreen` (with its own
 *     BackHandler wired to `Activity.finishAffinity()`) exits
 *     the flow rather than trying to unwind the controller.
 *     Round-1 shape routed this to `FailedAfterPersistence`,
 *     which stranded the user at InFlight/Permissions with
 *     Back locked — that path is no longer used for null-hex.
 *   - `FinalizeState.Idle` → [FinalizeOutcome.FailedBeforePersistence]
 *     (phase-0 error before any disk write; holder reverts to
 *     NotStarted so the user can fix username/privacy and
 *     retry from a clean slate.)
 *   - `FinalizeState.Persisted` → [FinalizeOutcome.FailedAfterPersistence]
 *     (identity + mode on disk; initMessaging failed; holder
 *     stays InFlight so Back stays locked and a Done re-tap
 *     hits the controller's `Persisted → Complete`
 *     short-circuit.)
 *   - `FinalizeState.Working` → [FinalizeOutcome.FailedAfterPersistence]
 *     (defensive; suspend function shouldn't return while still
 *     Working. Same handling as Persisted-error.)
 */
internal sealed interface FinalizeOutcome {
    /**
     * Both keys landed successfully:
     *   - [signingPublicKeyHex] — Ed25519 identity signing key (64 hex).
     *   - [publicKeyHex]        — X25519 messaging encryption key (64 hex).
     *
     * Dual-key labels track (2026-08-10): the atomic Completed
     * outcome now carries BOTH keys per architect §3.1. Either
     * hex being missing / malformed collapses to
     * [MissingKeyMaterial] upstream in [runFinalize].
     */
    data class Completed(
        val signingPublicKeyHex: String,
        val publicKeyHex: String,
    ) : FinalizeOutcome {
        init {
            require(isValidEd25519PublicKeyHex(signingPublicKeyHex)) {
                "FinalizeOutcome.Completed requires a valid Ed25519 signingPublicKeyHex " +
                    "(exactly 64 hex chars, [0-9a-fA-F]); got length " +
                    "${signingPublicKeyHex.length}. Malformed hex must be filtered upstream " +
                    "in runFinalize and routed to MissingKeyMaterial " +
                    "(round-2 REDLINE §P1 pin)."
            }
            require(isValidX25519PublicKeyHex(publicKeyHex)) {
                "FinalizeOutcome.Completed requires a valid X25519 publicKeyHex " +
                    "(exactly 64 hex chars, [0-9a-fA-F]); got length " +
                    "${publicKeyHex.length}. Malformed hex must be filtered upstream " +
                    "in runFinalize and routed to MissingKeyMaterial " +
                    "(dual-key-labels track 2026-08-10 §3.1)."
            }
        }
    }
    object FailedBeforePersistence : FinalizeOutcome
    object FailedAfterPersistence : FinalizeOutcome

    /**
     * Round-2 REDLINE §P1 pin: controller returned Complete but
     * the persisted record's signingPublicKeyHex failed the
     * Ed25519 contract ([isValidEd25519PublicKeyHex] — 64 hex
     * chars, `[0-9a-fA-F]`). The identity is on disk but its
     * signing key material is malformed; the flow MUST NOT open
     * Finale (would land the user on a "Something went wrong"
     * fallback with no exit) and MUST NOT stay at InFlight (was
     * the round-1 amend shape: silent infinite retry). Holder
     * transitions to [OnboardingFinalizeState.MissingKeyRepairRequired]
     * so the flow can render a visible repair-required screen
     * with a safe-exit action.
     */
    object MissingKeyMaterial : FinalizeOutcome
}

// C6-a: the `FinalizeAdvance` data class + `applyFinalizeOutcome`
// reducer + `FinalizeStateWriter` interface +
// `applyAndCommitFinalizeOutcome` helper that round-16..18 shipped
// have been retired. Their combined ownership contract (atomic
// three-slot terminal transition, writer-only mutation surface)
// now lives inside a single sealed [OnboardingFinalizeState] whose
// three variants — NotStarted / InFlight / Completed(hex) —
// replace the former three separate slots. See
// [OnboardingFinalizeStateHolder]'s KDoc for the sealed-model
// rationale (partial recreation between two independent writes is
// now un-representable, not merely un-observed).
//
// The only remaining coordinator in this file is [runFinalize]
// below: it drives the controller and returns a typed
// [FinalizeOutcome]. Both call sites (Done tap + resume
// LaunchedEffect) feed the outcome to
// `holder.applyFinalizeOutcome(outcome)` — a SINGLE
// `MutableState` assignment inside the holder. There is no
// external `finalizePhase` field, no external
// `signingPublicKeyHex` field on the form state, and the only
// direct `currentStep = FinaleConfirmation` transition is the
// derived-value expression at the top of the composable:
//
//     val currentStep = if (holder.state is Completed)
//         OnboardingStepV2.FinaleConfirmation
//     else navigationStep
//
// Regular Next/Back writes `navigationStep`, never Finale
// directly.

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
        is FinalizeState.Complete -> {
            // C6-a round-2 REDLINE §P1 pin: validate hex against the
            // Ed25519 public-key contract (exactly 64 hex chars,
            // `[0-9a-fA-F]`) HERE, before constructing any outcome.
            //   - Valid hex → `Completed(hex)`; sealed model lands on
            //     Completed(hex) via holder → Finale opens with the
            //     real key.
            //   - Null / empty / wrong length / non-hex char →
            //     `MissingKeyMaterial`; sealed model lands on
            //     `MissingKeyRepairRequired` via holder → the flow
            //     renders a visible repair-required screen with a
            //     safe-exit action. The round-1 amend routed null
            //     hex to `FailedAfterPersistence`, which trapped the
            //     user at InFlight on Permissions with Back locked
            //     and no error surface — silent infinite retry. The
            //     round-2 shape makes that state visible + exitable.
            //   - Empty-string hex specifically: prior round-1 code
            //     only checked `hex == null`, so an empty string
            //     reached the `Completed(hex)` constructor and threw
            //     an unhandled IllegalArgumentException inside the
            //     coroutine. Fixed here by the isValidEd25519PublicKeyHex
            //     check upfront (length != 64 fails empty too).
            // Dual-key labels track (2026-08-10) — architect §3.3:
            // Extract BOTH the Ed25519 signing key AND the X25519
            // messaging encryption key from the record. Both must
            // pass validation atomically; either malformed / missing
            // collapses to MissingKeyMaterial (same repair path).
            val signHex = end.record.signingPublicKeyHex
            val encHex  = end.record.publicKeyHex
            val signValid = signHex != null && isValidEd25519PublicKeyHex(signHex)
            val encValid  = isValidX25519PublicKeyHex(encHex)
            if (signValid && encValid) {
                FinalizeOutcome.Completed(
                    signingPublicKeyHex = signHex!!,
                    publicKeyHex        = encHex,
                )
            } else {
                FinalizeOutcome.MissingKeyMaterial
            }
        }
        is FinalizeState.Idle -> FinalizeOutcome.FailedBeforePersistence
        is FinalizeState.Persisted -> FinalizeOutcome.FailedAfterPersistence
        is FinalizeState.Working -> FinalizeOutcome.FailedAfterPersistence
    }
}

@Composable
internal fun OnboardingFlowV2(
    container: AppContainer,
    onComplete: () -> Unit,
    explicitInitialFinalizeState: OnboardingFinalizeState? = null,
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
        explicitInitialFinalizeState = explicitInitialFinalizeState,
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
    /**
     * C6-a round-6 REDLINE §P1 pin — optional explicit override
     * for the sealed finalize holder's initial state. When
     * non-null, WINS over the disk-marker read. `MainActivity`
     * passes `MissingKeyRepairRequired` here when the durable
     * marker write from the quarantine startup branch returned
     * `false` — that lets the current session still show the
     * repair-required screen even when SharedPreferences
     * rejected the write. Without this override, the flow
     * would read the disk marker as `false` (because the write
     * failed) and seed the holder at `NotStarted`, silently
     * dropping the user on Welcome despite the identity being
     * broken.
     *
     * Defaults to `null` so existing test call-sites
     * (`OnboardingV2NextLaunchQuarantineTest`,
     * `OnboardingV2RepairRequiredIntegrationTest`) keep their
     * marker-driven seeding behaviour unchanged.
     */
    explicitInitialFinalizeState: OnboardingFinalizeState? = null,
) {
    // Round-10 REDLINE §P1 pin: rememberSaveable everywhere so a
    // config change (rotation, dark-mode toggle, font-scale change)
    // preserves the flow's state. Prior plain `remember` reset
    // currentStep to Welcome + wiped formState.username on every
    // rotation.
    //
    // C6-a rename: the mutable slot is `navigationStep` (what the
    // Next / Back gestures write). The composable reads `currentStep`
    // — a DERIVED value that becomes FinaleConfirmation the instant
    // the sealed finalize holder reports `Completed`, otherwise
    // returns navigationStep verbatim. This means:
    //   - `navigationStep = FinaleConfirmation` is IMPOSSIBLE from
    //     regular navigation (the derived value ignores writes here
    //     for the Finale case; only holder.state = Completed can
    //     promote UI to Finale).
    //   - Rotation cannot land between "finalize returned" and
    //     "currentStep advanced" — those are the SAME single
    //     `holder.applyFinalizeOutcome(outcome)` assignment.
    var navigationStep by rememberSaveable(stateSaver = OnboardingStepV2Saver) {
        mutableStateOf(OnboardingStepV2.Welcome)
    }
    var formState by rememberSaveable(stateSaver = OnboardingFormStateV2Saver) {
        mutableStateOf(OnboardingFormStateV2())
    }
    // C6-a: sealed finalize state holder. Sole owner of the three
    // former slots (`finalizePhase` + `currentStep = Finale` +
    // `formState.signingPublicKeyHex`). See
    // [OnboardingFinalizeStateHolder] file KDoc for the sealed
    // model rationale — impossible combinations are now
    // un-representable, not just un-observed.
    //
    // Round-4 REDLINE §P1 pin: seed the holder from the durable
    // [IdentityRepairMarker] on FIRST composition of this
    // rememberSaveable slot. If a prior session wrote the
    // marker (via the repair-screen exit action), the flow opens
    // directly on the repair-required screen instead of
    // Welcome → How → … → Done → repair. MainActivity's startup
    // gate ALSO reads the marker and routes to Onboarding
    // regardless of `identity != null`, so a broken identity on
    // disk cannot bypass this quarantine by taking the ChatList
    // shortcut. Uninstall (which wipes SharedPreferences) is
    // the sole way to clear the marker until a downstream commit
    // adds `IdentityRepairMarker.clearRepairRequiredBlocking`
    // callers alongside a real repair capability.
    // Round-7 REDLINE: `decideStartupRoute` (invoked in
    // MainActivity) is the SINGLE source of truth for quarantine
    // detection. MainActivity ALWAYS passes
    // `MissingKeyRepairRequired` as `explicitInitialFinalizeState`
    // when the decision is RepairQuarantine (or
    // InitializationFailed). Prior round-4..6 shape had this
    // flow independently reading the disk marker as fallback —
    // that duplicated the quarantine decision across two layers
    // and let the "marker-present + explicit override null" case
    // slip past MainActivity's Terms bypass (round-6 §P1).
    // Round-7 removes the fallback: the flow trusts the caller's
    // explicit override; if null, seeds `NotStarted`.
    val holderContext = androidx.compose.ui.platform.LocalContext.current
    val holderInitialState: OnboardingFinalizeState = remember(
        explicitInitialFinalizeState,
    ) {
        explicitInitialFinalizeState ?: OnboardingFinalizeState.NotStarted
    }
    val finalizeHolder = rememberOnboardingFinalizeStateHolder(holderInitialState)
    // Derived displayed step. `Completed` → Finale, regardless of
    // navigationStep. Every other state → navigationStep verbatim.
    // A regular Next/Back tap can never promote UI to Finale from
    // this expression alone; only the holder can.
    val currentStep: OnboardingStepV2 =
        if (finalizeHolder.state is OnboardingFinalizeState.Completed)
            OnboardingStepV2.FinaleConfirmation
        else navigationStep
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
    // C6-a: `finalizePhase` durable slot retired. Its role is
    // subsumed by `finalizeHolder.state` above — the sealed model
    // covers all three phases (NotStarted / InFlight /
    // Completed(hex)) with a single write per transition.
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

    // C6-a: post-recreation resume. Runs EXACTLY ONCE per
    // composition on `LaunchedEffect(Unit)` — which for
    // post-rotation IS the newly-composed instance. If we come
    // back with `state = InFlight` (Done had fired before the
    // rotation) AND the controller is fresh (Idle after
    // recreation), replay finalize and apply the outcome
    // atomically inside the holder.
    //
    // Why the holder writes the whole terminal state in ONE
    // assignment: prior split-writer shapes (round-13..18) could
    // leave the three durable slots (`phase`, `currentStep`,
    // `hex`) partially updated between two independent effects or
    // between two lines of a helper method. A rotation caught in
    // that gap stranded the user on Permissions with Back locked.
    // The sealed model closes that gap: the whole snapshot rides
    // on a single `MutableState` write; there is no "between two
    // writes" to catch.
    LaunchedEffect(Unit) {
        if (finalizeHolder.state is OnboardingFinalizeState.InFlight &&
            controller.state is FinalizeState.Idle
        ) {
            val outcome = runFinalize(
                controller = controller,
                username = formState.username,
                privacyMode = formState.privacyMode,
            )
            if (!persistMissingKeyMarkerIfNeeded(outcome, holderContext)) return@LaunchedEffect
            finalizeHolder.applyFinalizeOutcome(outcome)
        }
    }

    // Back navigation lock:
    //   - Controller Persisted/Working → locked (identity already on
    //     disk, going back would silently drop user edits).
    //   - Holder NotStarted → defers entirely to controller state.
    //   - Holder InFlight  → locked (Done coroutine still running).
    //   - Holder Completed → locked (Finale is showing; nothing to
    //     unwind).
    //   - Holder MissingKeyRepairRequired → UNLOCKED regardless of
    //     controller state (round-3 REDLINE §P1 pin: the round-2
    //     shape leaked `isBackNavigationLockedByFinalize(controller.state)`
    //     into this predicate — when we reach MissingKeyRepairRequired
    //     the controller stays at `Complete(brokenRecord)` and that
    //     predicate returns true, so Back was silently absorbed even
    //     though we CLAIMED it was a safe exit. The when-branch
    //     below explicitly overrides the controller-side lock for
    //     this holder state — the repair-required screen ALSO
    //     installs its OWN BackHandler wired to the exit action,
    //     so the flow's outer BackHandler routing intentionally
    //     skips this branch too).
    // Error-before-persistence flips the holder back to NotStarted
    // via `applyFinalizeOutcome`, so Back unlocks for user recovery.
    val backLocked = when {
        finalizeHolder.state is OnboardingFinalizeState.MissingKeyRepairRequired -> false
        else ->
            isBackNavigationLockedByFinalize(controller.state) ||
                finalizeHolder.state is OnboardingFinalizeState.InFlight ||
                finalizeHolder.state is OnboardingFinalizeState.Completed
    }
    // goBack / goNext operate on `navigationStep` — the mutable
    // slot. Reads look up the previous/next OnboardingStepV2 by
    // ordinalInFlow relative to navigationStep, NOT the derived
    // currentStep — otherwise, in the Completed state (where
    // currentStep == Finale but navigationStep == Permissions), a
    // stale gesture could try to advance past Finale.
    val goBack: () -> Unit = {
        if (!backLocked) {
            val prev = OnboardingStepV2.entries
                .firstOrNull { it.ordinalInFlow == navigationStep.ordinalInFlow - 1 }
            if (prev != null) navigationStep = prev
        }
    }
    val goNext: () -> Unit = {
        if (canAdvanceFromV2(navigationStep, formState)) {
            // C6-a round-1 REDLINE §P1 pin: pure helper enforces the
            // "Finale is never a regular-nav target" invariant.
            // Returns null if the next candidate would be
            // FinaleConfirmation OR if `from` itself is a step with
            // no forward-nav target (Permissions / Finale).
            val next = computeNextNavigationStep(navigationStep)
            if (next != null) navigationStep = next
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
    // C6-a round-3 REDLINE §P1 pin: early return to render the
    // repair-required screen BEFORE the regular BackHandler routing
    // + host frame. The repair screen installs its OWN BackHandler
    // wired to the same `onExit` action its "Exit onboarding"
    // button uses, so both system-Back and CTA-tap produce the
    // SAME honest exit (Activity.finishAffinity()). Placed after
    // all rememberSaveable slots + the resume LaunchedEffect so
    // state + resume gating remain correct across rotation.
    //
    // Prior round-2 shape put this short-circuit INSIDE the outer
    // Box and let the regular BackHandler routing install
    // `BackHandler { intentionally absorbed }` (because
    // `isBackNavigationLockedByFinalize(controller.state) == true`
    // when the controller reached Complete with a broken record).
    // System Back was silently swallowed while we claimed it was a
    // safe exit. Round-3 closes that surface via BOTH the
    // `backLocked` when-branch above AND this early return.
    if (finalizeHolder.state is OnboardingFinalizeState.MissingKeyRepairRequired) {
        val exitContext = androidx.compose.ui.platform.LocalContext.current
        val onExit: () -> Unit = remember(exitContext, finalizeHolder) {
            {
                // C6-a round-5 REDLINE §P1 pin: Exit is now
                // IDEMPOTENT — the durable
                // `identity_repair_required` marker was already
                // written the moment `runFinalize` returned
                // `MissingKeyMaterial` (see
                // `persistMissingKeyMarkerIfNeeded` in the
                // Done-tap coroutine + resume LaunchedEffect
                // above). Cold-restart via `MainActivity`'s
                // startup gate already routes back to
                // Onboarding, and this composable's
                // `holderInitialState` re-seeds
                // MissingKeyRepairRequired from that marker.
                //
                // The Exit CTA's sole job is to close the current
                // task via `Activity.finishAffinity()`. Round-4
                // wrote the marker HERE too — that write was
                // needed then because there was no detection-time
                // write. Round-5 removed it: the detection-time
                // write is guaranteed BEFORE any user interaction
                // with the repair screen, so backgrounding /
                // process-kill between MissingKeyMaterial and
                // Exit is now handled by the durable marker
                // rather than requiring the user to reach the
                // Exit CTA at all.
                //
                // Reliable Activity lookup still uses
                // `findActivityOrNull()` (unchanged round-4 fix)
                // — `(context as? Activity)?.finishAffinity()`
                // would silently no-op on ContextWrapper.
                val activity = exitContext.findActivityOrNull()
                if (activity == null) {
                    // No Activity in the Context chain. Should
                    // never happen inside a rendered composable,
                    // but if the Compose runtime is somehow
                    // hosting us via a raw ApplicationContext,
                    // fail-loud instead of pretending the exit
                    // succeeded. Holder stays; marker is on disk
                    // so a fresh cold-start will still route to
                    // the quarantine.
                    android.util.Log.e(
                        "OnboardingV2",
                        "OnboardingRepairRequiredScreen.onExit: no Activity in LocalContext " +
                            "chain — finishAffinity() cannot be called. " +
                            "Holder stays at MissingKeyRepairRequired. " +
                            "Durable IdentityRepairMarker was written, so the next " +
                            "launch will route back to quarantine even without " +
                            "the current-task finish.",
                    )
                    return@remember
                }
                activity.finishAffinity()
                // NB: intentionally NOT calling
                // finalizeHolder.resetFromRepairRequired() —
                // the durable marker is now on disk (the true
                // quarantine anchor), the Activity is finishing
                // (the composable tears down), and if for any
                // reason the Activity survives finishAffinity
                // the flow must STAY at MissingKeyRepairRequired
                // rather than silently transitioning to
                // NotStarted (which would let a subsequent Done
                // tap loop through the same broken controller).
            }
        }
        OnboardingRepairRequiredScreen(onExit = onExit)
        return
    }

    if (pricingSheetVisible) {
        BackHandler(enabled = true) { pricingSheetVisible = false }
    } else if (pricingSheetPresent) {
        BackHandler(enabled = true) { /* mid-exit-animation — absorbed */ }
    } else if (backLocked) {
        // Absorbs OS Back on:
        //   - Finale (holder = Completed → currentStep derived to
        //     FinaleConfirmation → backLocked true).
        //   - Any InFlight finalize (Done fired, coroutine running).
        //   - Any state where the controller is Persisted/Working
        //     (identity already committed).
        BackHandler(enabled = true) { /* intentionally absorbed */ }
    } else if (navigationStep != OnboardingStepV2.Welcome) {
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
        //
        // Onboarding-stabilization block 2026-08-11 — structural
        // logo-flash fix. Prior shape kept Welcome as one branch of
        // `AnimatedContent { when(step) { ... } }`; even with
        // `EnterTransition.None togetherWith ExitTransition.None`,
        // Compose's `KeepUntilTransitionsFinished` machinery held
        // the outgoing Welcome tree in composition for one extra
        // frame after the tap, and on-device users still perceived
        // the PHANTOM logo flash. The fix here is STRUCTURAL: when
        // `currentStep == Welcome`, render `WelcomeStepV2` OUTSIDE
        // `AnimatedContent` entirely — the moment state flips to
        // How, the `if` branch disposes Welcome in the SAME frame
        // and the `else` branch mounts `AnimatedContent` for the
        // first time with initial state = How (no fade-in on
        // first-mount). Zero overlap, zero logo flash. Every other
        // forward/back transition (How ↔ Identity, Identity ↔
        // Privacy, etc.) still runs the symmetric crossfade inside
        // `AnimatedContent`.
        //
        // See `docs/tracks/android-onboarding/onboarding-stabilization-block-2026-08-11.md`.
        if (currentStep == OnboardingStepV2.Welcome) {
            WelcomeStepV2(
                // Guard against a double-fire on Get started —
                // `canAdvanceFromV2` returns true for BOTH Welcome
                // and How, so without the guard a rapid second fire
                // would advance nav past How to Identity in the
                // same gesture window. Pinned by
                // `OnboardingFlowV2TransitionTest.get_started_double_tap_ends_at_how_not_identity`.
                onContinueClick = {
                    if (navigationStep == OnboardingStepV2.Welcome) goNext()
                },
            )
        } else AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                fadeIn(tween(180)) togetherWith fadeOut(tween(160))
            },
            label = "onboarding-step",
            modifier = Modifier.fillMaxSize(),
        ) { step ->
            when (step) {
                // Welcome is handled OUTSIDE this AnimatedContent by
                // the enclosing `if` guard — it is unreachable here.
                // A defensive no-op keeps the `when` exhaustive so
                // future step additions still fail-red at compile
                // time.
                OnboardingStepV2.Welcome -> Unit
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
                        // C6-a: kick off the atomic finalize.
                        //   1. `finalizeHolder.markInFlight` — single
                        //      write, sealed slot flips to InFlight.
                        //      Back locks immediately; resume
                        //      LaunchedEffect can now replay from a
                        //      post-rotation composition if this
                        //      coroutine dies with the process.
                        //   2. `runFinalize` — drive the two-phase
                        //      controller state machine (double-tap
                        //      guard, savePrivacyMode / createOrLoad /
                        //      initMessaging with retry + cancellation
                        //      handling).
                        //   3. `finalizeHolder.applyFinalizeOutcome`
                        //      — single sealed-slot write that atomically
                        //      lands NotStarted, InFlight, or
                        //      Completed(hex) as appropriate. There is
                        //      NO longer a separate `currentStep =
                        //      FinaleConfirmation` line or a separate
                        //      `formState.copy(signingPublicKeyHex =
                        //      hex)` line — both are implicit in the
                        //      Completed(hex) variant + the derived
                        //      `currentStep` at the top of the
                        //      composable.
                        finalizeHolder.markInFlight()
                        scope.launch {
                            val outcome = runFinalize(
                                controller = controller,
                                username = formState.username,
                                privacyMode = formState.privacyMode,
                            )
                            if (!persistMissingKeyMarkerIfNeeded(outcome, holderContext))
                                return@launch
                            finalizeHolder.applyFinalizeOutcome(outcome)
                        }
                    },
                    )  // PermissionsStepV2 close
                }  // OnboardingStepV2.Permissions block close
                OnboardingStepV2.FinaleConfirmation -> FinaleConfirmationStepV2(
                    // Onboarding-stabilization block 2026-08-11:
                    // Finale is now a plain "Identity created +
                    // Continue" surface — no raw hexes, no Copy
                    // affordance, no toast. The keys still travel
                    // through `finalizeHolder` into the persisted
                    // `IdentityRecord`; they surface in Profile
                    // under "Advanced cryptographic details".
                    onContinueClick = onComplete,
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

/**
 * Safe-exit repair-required screen (round-3 REDLINE §P1 pin).
 *
 * Rendered when the sealed finalize holder reports
 * [OnboardingFinalizeState.MissingKeyRepairRequired] — the
 * persisted identity's signingPublicKeyHex failed the Ed25519
 * contract (null / empty / wrong length / non-hex).
 *
 * The screen contains:
 *   - A clear error message so the user KNOWS what happened.
 *   - An "Exit onboarding" button whose click calls [onExit].
 *     Round-3 wires this to `Activity.finishAffinity()` +
 *     `holder.resetFromRepairRequired()` in the composable, so
 *     the tap ACTUALLY exits the flow (round-2 shape wired
 *     "Start over" to holder-reset ONLY, which left the user in
 *     a visible retap loop — architect flagged it as "надпись
 *     Start over при текущем поведении недопустима").
 *   - Its OWN [BackHandler] wired to the same [onExit] action,
 *     so system Back produces the SAME honest exit as the CTA
 *     tap. Prior round-2 shape had the outer BackHandler
 *     routing silently absorb system Back via
 *     `isBackNavigationLockedByFinalize(controller.state)`
 *     (which was true because the controller stays at Complete).
 *     Round-3 short-circuits ABOVE that routing so this
 *     BackHandler wins.
 *
 * The `onExit` action:
 *   - Resets the sealed holder to NotStarted (housekeeping — if
 *     the Activity somehow survives finishAffinity, the flow is
 *     left in a coherent state).
 *   - Calls `Activity.finishAffinity()` on the enclosing
 *     Activity so the whole onboarding task closes.
 * A downstream commit will replace this with an ATOMIC repair
 * (`IdentityManager.delete()` + controller reset + fresh identity
 * generation) so the button can genuinely "start over" instead
 * of just exiting.
 *
 * Marked `internal` (was `private`) so the Compose semantics
 * test at
 * `OnboardingV2RepairRequiredScreenTest` can render it directly
 * and pin the CTA-click + Back-press behaviour without needing
 * to drive the full flow.
 *
 * Deliberately spartan visual — the state-model boundary, not a
 * user-facing polished surface. Downstream commit upgrades the
 * visual alongside the real repair action.
 */
@Composable
internal fun OnboardingRepairRequiredScreen(onExit: () -> Unit) {
    // Round-3 §P1 pin: BackHandler wired directly to onExit so
    // system Back == "Exit onboarding" tap. Enabled=true wins
    // over any ambient handler up the composition tree because
    // this screen's BackHandler is installed later (inside this
    // composable's slot) than the flow's outer routing — AND
    // because the flow's outer routing intentionally does not
    // install anything for the MissingKeyRepairRequired state
    // (see the early-return short-circuit in OnboardingFlowV2Internal).
    BackHandler(enabled = true) { onExit() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DesignV2Tokens.Colors.Surface)
            .padding(24.dp)
            .semantics { contentDescription = "OnboardingRepairRequiredScreen" },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.Text(
                text = "Identity repair required",
                color = DesignV2Tokens.Colors.TextPrimary,
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = phantom.android.ui.designv2.DesignV2FontDisplay,
                    fontSize = 22.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                ),
            )
            androidx.compose.foundation.layout.Spacer(
                Modifier.height(12.dp),
            )
            androidx.compose.material3.Text(
                text = "Your identity was created on disk but the signing key material " +
                    "is missing or malformed. This should not happen. Tap Exit onboarding " +
                    "to close the app; then reinstall to try again.",
                color = DesignV2Tokens.Colors.TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = phantom.android.ui.designv2.DesignV2FontBody,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
            )
            androidx.compose.foundation.layout.Spacer(
                Modifier.height(24.dp),
            )
            phantom.android.ui.designv2.components.PhantomButton(
                text = "Exit onboarding",
                onClick = onExit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * C6-a round-5 REDLINE §P1 pin — write the durable
 * [IdentityRepairMarker] AT THE MOMENT the finalize coroutine
 * detects [FinalizeOutcome.MissingKeyMaterial], BEFORE feeding
 * the outcome to `holder.applyFinalizeOutcome`.
 *
 * Prior round-4 shape wrote the marker only from the Exit-tap
 * handler inside `OnboardingRepairRequiredScreen`. That was too
 * late: between the coroutine reaching `MissingKeyMaterial` and
 * the user tapping Exit, the flow could be backgrounded / the
 * process killed. Next launch would see a broken identity on
 * disk with NO marker → MainActivity's `identity != null →
 * ChatList` path → user landed on the main app with a malformed
 * signing key.
 *
 * Round-5 semantics:
 *   - Only fires for [FinalizeOutcome.MissingKeyMaterial].
 *   - Round-6 §P2 pin: synchronous `.commit()` on the caller's
 *     dispatcher (the flow's Compose main coroutine scope, i.e.
 *     `Dispatchers.Main`). NOT `withContext(Dispatchers.IO)` —
 *     that would dispatch to a real IO pool whose completion
 *     is invisible to `composeTestRule.waitForIdle`, and the
 *     detection-time-marker load-bearing test would flake.
 *     StrictMode may flag the main-thread SharedPreferences
 *     write in DEBUG; a downstream commit can introduce a
 *     testable dispatcher / await model to lift the write off
 *     Main without losing the test's correctness guarantee.
 *   - Returns `true` on success (caller proceeds to
 *     `applyFinalizeOutcome`) OR when the outcome is not
 *     `MissingKeyMaterial` (nothing to persist, caller proceeds).
 *   - Returns `false` when the marker write itself failed. The
 *     caller MUST NOT apply the outcome to the holder in that
 *     case — leaving the holder at `InFlight` means Back stays
 *     locked and the user can retry Done, which will hit the
 *     controller's Persisted → Complete short-circuit and try
 *     the marker write again. Better a retry loop the user
 *     initiates than a silent transition into
 *     MissingKeyRepairRequired with no durable anchor.
 */
private suspend fun persistMissingKeyMarkerIfNeeded(
    outcome: FinalizeOutcome,
    context: android.content.Context,
): Boolean {
    if (outcome !is FinalizeOutcome.MissingKeyMaterial) return true
    // C6-a round-5 §P1-1: SharedPreferences `.commit()` is a
    // synchronous durable write. We deliberately do NOT wrap in
    // `withContext(Dispatchers.IO)`: that would dispatch to a
    // real IO thread pool, and Compose test's `waitForIdle`
    // (which awaits only main-dispatcher tasks) would return
    // before the write settled — the marker-was-written-at-
    // detection assertion in
    // `OnboardingV2NextLaunchQuarantineTest.broken_flow_writes_marker_at_MissingKeyMaterial_detection_not_at_Exit`
    // would flake. Calling `.commit()` directly from the
    // Compose main-scope coroutine is on the same dispatcher
    // as `holder.applyFinalizeOutcome` below, so `waitForIdle`
    // covers both writes atomically. A one-key SharedPreferences
    // `.commit()` is fast enough to keep on Main; StrictMode
    // may flag it in DEBUG but the trade-off is correctness of
    // the durable-quarantine ordering.
    val ok = IdentityRepairMarker.markRepairRequiredBlocking(context)
    if (!ok) {
        android.util.Log.e(
            "OnboardingV2",
            "persistMissingKeyMarkerIfNeeded: IdentityRepairMarker.markRepairRequiredBlocking " +
                "returned false — durable marker write to SharedPreferences failed. " +
                "NOT applying outcome to holder; holder stays InFlight; user can retry " +
                "Done which will re-attempt the marker write.",
        )
    }
    return ok
}

// Onboarding-stabilization block 2026-08-11: the prior
// `onboardingStepContentTransform` extension (with the Welcome→How
// scoped no-transition branch) is deleted. Welcome is now rendered
// OUTSIDE `AnimatedContent` (see the `if (currentStep == Welcome)`
// guard in `OnboardingFlowV2Internal`), so no cross-step transition
// ever involves the Welcome branch — the special case is gone. All
// remaining forward/back transitions run the symmetric
// `fadeIn(180) togetherWith fadeOut(160)` inline inside
// `AnimatedContent.transitionSpec` at the call site.
