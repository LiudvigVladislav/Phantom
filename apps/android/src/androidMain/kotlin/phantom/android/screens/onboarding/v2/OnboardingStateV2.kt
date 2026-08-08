// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import phantom.core.transport.PrivacyMode

/**
 * Round-10 REDLINE on Commit 5 §P1 pin — Savers used by
 * `OnboardingFlowV2Internal`'s `rememberSaveable` blocks so a
 * config change (rotation, dark-mode toggle, font-scale change,
 * Activity recreation on low memory) preserves the entire
 * onboarding state end-to-end.
 *
 * `OnboardingStepV2Saver` — serializes the current step enum by
 * name; the enum is closed and stable across process launches,
 * so `valueOf(name)` is safe.
 *
 * `OnboardingFormStateV2Saver` — serializes the 2-field data
 * class as a String list: `username` + `privacyMode` enum name.
 * C6-a: the former `signingPublicKeyHex` slot moved OUT of the
 * form state into the sealed finalize holder — see
 * [OnboardingFinalizeStateHolder] + [OnboardingFinalizeStateSaver].
 */
public val OnboardingStepV2Saver: Saver<OnboardingStepV2, String> = Saver(
    save = { it.name },
    restore = { OnboardingStepV2.valueOf(it) },
)

/**
 * Coarse three-phase enum derived from the sealed
 * [OnboardingFinalizeState]. Kept public for composable code that
 * only needs the phase (Back-lock check, phase-specific KDoc
 * references) without pattern-matching the sealed variant.
 *
 * C6-a: the durable saveable slot moved to the sealed model
 * ([OnboardingFinalizeStateSaver]). No `OnboardingFinalizePhaseSaver`
 * exists any more — this enum is a read-only projection derived
 * inside the holder.
 *
 * Three phases:
 *   - `NotStarted` — no finalize has been kicked off yet, OR
 *     the last attempt failed BEFORE any disk write. Back is
 *     unlocked; user can fix username/privacy and retry.
 *   - `InFlight` — Done has fired and the finalize coroutine is
 *     still working (either in the original composition or
 *     resumed after rotation). Back is locked.
 *   - `Completed` — all three phases succeeded, identity is on
 *     disk, Finale is showing. Back stays locked.
 */
public enum class OnboardingFinalizePhase {
    NotStarted,
    InFlight,
    Completed,
    // C6-a round-2 REDLINE §P1 pin: coarse projection of the
    // sealed `MissingKeyRepairRequired` state — the flow
    // reached Complete but the persisted record's signing key
    // hex failed the Ed25519 contract. Rendered as a repair-
    // required screen; safe-exit action resets the sealed
    // holder to NotStarted.
    MissingKeyRepair,
    ;
}

public val OnboardingFormStateV2Saver: Saver<OnboardingFormStateV2, Any> = listSaver(
    save = {
        listOf(
            it.username,
            it.privacyMode.name,
        )
    },
    restore = {
        OnboardingFormStateV2(
            username = it[0] as String,
            privacyMode = PrivacyMode.valueOf(it[1] as String),
        )
    },
)

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
 *   - Permissions writes nothing (round-1 REDLINE §P1-2: OS is
 *     the source of truth for notifications; Mic/Nearby are info
 *     rows per §A4).
 *   - FinaleConfirmation receives its `signingPublicKeyHex` from
 *     the sealed [OnboardingFinalizeStateHolder] directly (C6-a).
 *     The form state no longer carries the hex — it lives in the
 *     holder's `Completed(hex)` variant, and the composable passes
 *     `holder.signingPublicKeyHex` as a parameter to
 *     [phantom.android.screens.onboarding.v2.steps.FinaleConfirmationStepV2].
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
 *   - Commit 3 : `username` is filled by IdentityKeyStep.
 *                The signing public key hex used to live here — it
 *                now lives in the sealed
 *                [OnboardingFinalizeStateHolder]'s `Completed`
 *                variant (C6-a). The finalize path writes it as
 *                part of the atomic sealed-state transition.
 *   - Commit 4 : `privacyMode` is filled by PrivacyLevelStep.
 *   - Commit 5 : no form-state writes (round-1 REDLINE §P1-2).
 *                Notifications state is derived from the OS via
 *                `OnboardingNotificationPermissionCoordinator`;
 *                Microphone + Nearby are static info rows.
 *
 * Marked as a plain `data class` (no @Serializable) — the state is entirely
 * in-memory for the duration of the onboarding flow. If process dies mid-flow,
 * MainActivity's `identityRepo.loadIdentity() == null` check lands us right
 * back on `Screen.Onboarding` and the user restarts from Step 0.
 */
data class OnboardingFormStateV2(
    val username: String = "",
    val privacyMode: PrivacyMode = PrivacyMode.Standard,
    // Round-1 REDLINE on Commit 5 §P1-1 + §P1-2: no
    // `notificationsEnabled` / `microphoneEnabled` /
    // `nearbyDiscoveryEnabled` fields.
    //
    // Notifications:
    //   The OS permission state IS the source of truth per
    //   `OnboardingNotificationPermissionCoordinator` — an app-
    //   level bool would trivially desync from Settings changes
    //   made while the app is running, and no runtime notif-
    //   publish gate reads it.
    //
    // Microphone + Nearby discovery:
    //   Non-interactive INFORMATIONAL rows in the Permissions
    //   step per architect §A4 ("Asked when first used"). No
    //   preference is recorded — the RECORD_AUDIO / BLUETOOTH_SCAN
    //   dialogs fire at first actual use (call subsystem / mesh
    //   discovery) via the OS launcher pattern inside those
    //   subsystems.
    //
    // C6-a: `signingPublicKeyHex` moved OUT of this data class.
    // It now lives in the sealed [OnboardingFinalizeStateHolder]'s
    // `Completed(hex)` variant. Callers of
    // FinaleConfirmationStepV2 pass `holder.signingPublicKeyHex`
    // as a parameter directly.
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
    // C6-a round-1 REDLINE §P1 pin: Permissions has NO regular
    // forward-nav Continue button — its Done button routes through
    // the sealed [OnboardingFinalizeStateHolder]
    // (`holder.markInFlight()` + coroutine `runFinalize` +
    // `holder.applyFinalizeOutcome(outcome)`). The predicate MUST
    // reject a regular advance so `goNext` cannot push
    // navigationStep to FinaleConfirmation and bypass the holder.
    // Belt-and-suspenders alongside `computeNextNavigationStep`,
    // which structurally refuses to return FinaleConfirmation as a
    // navigation target regardless of this predicate.
    OnboardingStepV2.Permissions        -> false
    OnboardingStepV2.FinaleConfirmation -> false  // terminal; leaves via onComplete, not via advance
}

/**
 * C6-a round-1 REDLINE §P1 pin — pure helper describing what the
 * next navigation step is after `from`. Extracted out of the
 * composable's `goNext` so a unit test can pin the
 * "Finale is never a regular-nav target" invariant WITHOUT
 * going through Compose UI or the finalize holder.
 *
 * Returns `null` for:
 *   - `FinaleConfirmation` (terminal — no forward nav).
 *   - `Permissions` (Done tap routes through holder, not through
 *     regular nav; there IS no next step reachable via a Continue
 *     CTA on Permissions).
 *   - Any other step whose ordinalInFlow+1 would land on
 *     `FinaleConfirmation` (structural block — Finale can ONLY be
 *     opened by the sealed holder promoting `currentStep` via the
 *     derived expression `if (holder.state is Completed) Finale
 *     else navigationStep`).
 *
 * Returns the next enum entry by `ordinalInFlow` for the other
 * steps (Welcome → How → Identity → Privacy).
 */
fun computeNextNavigationStep(from: OnboardingStepV2): OnboardingStepV2? {
    if (from == OnboardingStepV2.FinaleConfirmation) return null
    val candidate = OnboardingStepV2.entries
        .firstOrNull { it.ordinalInFlow == from.ordinalInFlow + 1 }
        ?: return null
    // Finale is out of reach via regular navigation. The sealed
    // holder is the only path to Finale.
    if (candidate == OnboardingStepV2.FinaleConfirmation) return null
    return candidate
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

/**
 * Format an Ed25519 public-key hex string as chunked groups for legible
 * display in the finale card. The FULL 64-char hex is preserved — this
 * only inserts visual grouping spaces.
 *
 * Format: 8 groups of 8 chars, single space between adjacent groups,
 * double space between groups 4 and 5 (mid-hex "waist"). Matches the
 * handoff `Onboarding.dc.html` finale card layout.
 *
 * Returns the input unchanged if the input length is not exactly 64 —
 * defensive fallback for records that predate the Alpha 2 signing-key
 * backfill (which report null `signingPublicKeyHex`; callers should
 * check upstream before calling this).
 */
fun formatFingerprintForDisplay(hex: String): String {
    if (hex.length != 64) return hex
    return buildString {
        for (i in 0..7) {
            if (i > 0) append(if (i == 4) "  " else " ")
            append(hex.substring(i * 8, i * 8 + 8))
        }
    }
}

/**
 * Short 8-char fingerprint: first 4 + last 4 of the hex separated by
 * an ellipsis. Displayed alongside an explicit `fingerprint · short form`
 * label so the reader knows this is NOT a full key — no truncated
 * key ever appears without the label (per redline §C1).
 *
 * Returns empty string if the input length is not exactly 64.
 */
fun formatFingerprintShort(hex: String): String {
    if (hex.length != 64) return ""
    return "${hex.substring(0, 4)}…${hex.substring(60, 64)}"
}
