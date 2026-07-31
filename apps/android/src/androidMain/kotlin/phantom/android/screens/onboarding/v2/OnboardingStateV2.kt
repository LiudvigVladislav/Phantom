// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import phantom.core.transport.PrivacyMode

/**
 * OnboardingV2 state model — enum + form-state + validation.
 *
 * Additive alongside the pre-existing single-file [phantom.android.screens.onboarding.OnboardingScreen].
 * The old flow lives on until the entry-point switch lands in Commit 5; both
 * co-exist until then. See the memo lock (2026-08-01) §"Delivery Shape" for
 * the full 5-commit sequence.
 *
 * The step enum drives the flow's chrome visibility + step-dots position +
 * "STEP N OF 4" label. Welcome and FinaleConfirmation are both chromeless
 * per handoff `Onboarding.dc.html`: Welcome has no top bar / no dots (it's
 * a prelude), FinaleConfirmation appears AFTER the numbered 4 (post-finalize)
 * and also has no chrome.
 *
 * The [FormState] is filled progressively:
 *   - Welcome and How touch nothing (Commit 2 — this commit).
 *   - Identity fills [FormState.username] (Commit 3, gated by [validateUsername]).
 *   - Privacy fills [FormState.privacyMode] (Commit 4).
 *   - Permissions fills [FormState.notificationsEnabled] (Commit 5).
 *   - FinaleConfirmation reads [FormState.signingPublicKeyHex] set by
 *     the finalize path (Commit 5).
 */

enum class OnboardingStepV2(
    val ordinalInFlow: Int,     // 0..5 — internal navigation index
    val stepNumber: Int,        // Number shown as "STEP N OF 4"; 0 for chromeless prelude/finale
    val showTopBar: Boolean,
    val showStepDots: Boolean,
    val dotsIndex: Int,         // 0..3 — index into the 4-dot indicator; -1 when hidden
) {
    Welcome(              ordinalInFlow = 0, stepNumber = 0, showTopBar = false, showStepDots = false, dotsIndex = -1),
    How(                  ordinalInFlow = 1, stepNumber = 1, showTopBar = true,  showStepDots = true,  dotsIndex = 0),
    Identity(             ordinalInFlow = 2, stepNumber = 2, showTopBar = true,  showStepDots = true,  dotsIndex = 1),
    Privacy(              ordinalInFlow = 3, stepNumber = 3, showTopBar = true,  showStepDots = true,  dotsIndex = 2),
    Permissions(          ordinalInFlow = 4, stepNumber = 4, showTopBar = true,  showStepDots = true,  dotsIndex = 3),
    FinaleConfirmation(   ordinalInFlow = 5, stepNumber = 0, showTopBar = false, showStepDots = false, dotsIndex = -1);

    /** Total numbered steps shown as "OF 4" in the top-bar counter. */
    val totalNumberedSteps: Int get() = 4
}

/**
 * Progressively-filled form state for the whole onboarding flow. All fields
 * default to empty/neutral so intermediate commits (2..4) can construct this
 * without knowing about later-commit fields. Fields land in the commit they
 * become writable:
 *
 *   - Commit 2 : (nothing — Welcome / How are read-only)
 *   - Commit 3 : `username` is filled by IdentityKeyStep;
 *                `signingPublicKeyHex` is written by the finalize path after
 *                `IdentityManager.createOrLoad` succeeds.
 *   - Commit 4 : `privacyMode` is filled by PrivacyLevelStep.
 *   - Commit 5 : `notificationsEnabled` is filled by PermissionsStep
 *                (only affects whether the POST_NOTIFICATIONS launcher fires;
 *                the enabled toggle DOES trigger the launcher on Android 13+;
 *                a runtime denial flips this field back to false).
 *
 * Marked as a plain `data class` (no @Serializable) — the state is entirely
 * in-memory for the duration of the onboarding flow. If process dies mid-flow,
 * MainActivity's `identityRepo.loadIdentity() == null` check lands us right
 * back on `Screen.Onboarding` and the user restarts from Step 0.
 */
data class OnboardingFormStateV2(
    val username: String = "",
    val privacyMode: PrivacyMode = PrivacyMode.Standard,
    val notificationsEnabled: Boolean = false,
    val signingPublicKeyHex: String? = null,
)

/**
 * Username validation states — display-time labels driven by [validateUsername].
 *
 * Per architect lock (2026-08-01 Commit 1 clarification §2): the raw string
 * is stored as-typed (only case-normalised); illegal characters are NOT
 * silently stripped by `.filter { ... }` (which would render the invalid
 * state unreachable). Format validation runs against the stored string and
 * the result drives helper-text + right-slot icon + Continue-CTA gating.
 *
 * `Valid` reports "Handle format is valid." — NOT "is available." No
 * backend availability check exists.
 */
enum class UsernameValidationV2 {
    Empty,          // no input yet
    Short,          // 1..2 chars
    InvalidChars,   // any character outside [a-z0-9_]
    Valid,          // 3..20 chars, all in [a-z0-9_]
}

/**
 * Validate a case-normalised username string. Caller normalises to lowercase
 * before calling (the input path lowercases as the user types but does NOT
 * strip characters, so the raw string can carry non-alphanumeric symbols that
 * this validator flags as `InvalidChars`).
 *
 * Rules (matches the pre-existing offline filter in the current OnboardingScreen):
 *   - Empty              → Empty
 *   - Length in 1..2     → Short
 *   - Any char not in [a-z0-9_] → InvalidChars
 *   - Length in 3..20 AND all in [a-z0-9_] → Valid
 *   - Length > 20        → InvalidChars (technically length-invalid, folded into
 *                          InvalidChars so the caller shows a single helper string
 *                          when the length is out of range for a reason unrelated
 *                          to "too short"; Commit 3 will tune the helper wording).
 */
fun validateUsernameV2(input: String): UsernameValidationV2 {
    if (input.isEmpty()) return UsernameValidationV2.Empty
    if (input.length < 3) return UsernameValidationV2.Short
    if (input.length > 20) return UsernameValidationV2.InvalidChars
    val allowed = input.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' }
    return if (allowed) UsernameValidationV2.Valid else UsernameValidationV2.InvalidChars
}

/**
 * Per-step gate for the flow's forward navigation. Returns `true` iff the
 * caller can advance from [step] given the current [state]. The flow's
 * "Continue" CTA uses this predicate; back-navigation is NEVER gated.
 *
 * Called by [OnboardingFlowV2] on every render — must stay pure and cheap.
 * Steps whose implementation lands in later commits gate on the placeholder
 * shape they'll enforce then: Identity gates on username validity (Commit 3),
 * Privacy always advances (Commit 4 — user always picks something), Permissions
 * always advances (Commit 5 — toggles are optional). Welcome / How always
 * advance too — they're read-only.
 *
 * FinaleConfirmation has no forward transition — it terminates the flow via
 * onComplete().
 */
fun canAdvanceFromV2(step: OnboardingStepV2, state: OnboardingFormStateV2): Boolean = when (step) {
    OnboardingStepV2.Welcome            -> true
    OnboardingStepV2.How                -> true
    OnboardingStepV2.Identity           -> validateUsernameV2(state.username) == UsernameValidationV2.Valid
    OnboardingStepV2.Privacy            -> true
    OnboardingStepV2.Permissions        -> true
    OnboardingStepV2.FinaleConfirmation -> false  // terminal; leaves via onComplete, not via advance
}

/**
 * Is the left-edge swipe-back gesture enabled from [step] ?
 *
 * FALSE for both chromeless states — Welcome (start of flow, nothing to go
 * back to; OS handles as "exit app") and FinaleConfirmation (post-finalize;
 * identity has been created and going back to Permissions would be
 * misleading). TRUE for the four numbered steps.
 *
 * Extracted as a predicate so unit tests can pin the Finale rule
 * independently of the flow composable (round-2 REDLINE 2026-08-01
 * P1-1: previous shape enabled the swipe on Finale even though the
 * BackHandler absorbed the OS Back button — architect asked to close
 * that surface too).
 */
fun isEdgeSwipeBackFromEnabled(step: OnboardingStepV2): Boolean =
    step != OnboardingStepV2.Welcome &&
    step != OnboardingStepV2.FinaleConfirmation
