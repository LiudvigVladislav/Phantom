// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * Commit 6 sub-milestone C6-a — single sealed saveable model
 * for the entire finalize terminal state.
 *
 * ## Why a sealed model and not three separate saveable slots
 *
 * A round-19 handoff on Commit 5 shipped writer-only access to
 * three separately-saveable slots. Partial recreation could
 * still leave the durable state inconsistent — e.g. `phase =
 * Completed` restored while `hex` was `null`, or a rotation
 * caught between two of the three writes even inside the
 * writer's method body. The architect ruled that inconsistent
 * snapshots must be un-representable, not merely un-observed.
 *
 * The sealed model does exactly that: each "logically
 * consistent" combination is a closed variant of
 * [OnboardingFinalizeState], and every state transition is a
 * SINGLE `MutableState` assignment. Rotation between "two
 * writes" is impossible because there is exactly one write per
 * transition and the whole snapshot rides on it.
 *
 * ## States
 *
 * ```
 * NotStarted            — flow has never entered finalize, or
 *                         a prior attempt failed BEFORE any
 *                         disk write. Back is unlocked; user
 *                         can freely navigate + retry.
 * InFlight              — Done tap has fired; the finalize
 *                         coroutine is working (either the
 *                         original or a post-rotation resume).
 *                         Back is locked. Displayed step stays
 *                         wherever navigation left it
 *                         (usually Permissions).
 * Completed(hex)        — all three phases succeeded AND the
 *                         controller returned a VALID
 *                         signingPublicKeyHex (exactly 64 hex
 *                         chars, `[0-9a-fA-F]`). hex is a
 *                         non-null String; a malformed hex from
 *                         the controller is filtered out in
 *                         `runFinalize` and routed to the
 *                         `MissingKeyMaterial` outcome, so the
 *                         sealed model NEVER sees a
 *                         `Completed(null)` or
 *                         `Completed("")` or `Completed(garbage)`.
 *                         Displayed step is Finale regardless
 *                         of navigation step. Back is locked.
 *                         TERMINAL for the happy path.
 * MissingKeyRepairRequired
 *                       — controller reached Complete but the
 *                         persisted record's signingPublicKeyHex
 *                         failed the Ed25519 contract
 *                         ([isValidEd25519PublicKeyHex]). The
 *                         identity is on disk but its signing
 *                         key material is broken; the flow MUST
 *                         NOT open Finale (would land the user
 *                         on a "Something went wrong" fallback
 *                         with no exit).
 *                         Back is UNLOCKED — safe exit path the
 *                         user can take. `markInFlight` and
 *                         `applyFinalizeOutcome` are no-ops from
 *                         this state; the ONLY way out is
 *                         [OnboardingFinalizeStateHolder.resetFromRepairRequired]
 *                         (safe-exit reset that returns the
 *                         holder to NotStarted; the persisted
 *                         broken record is NOT cleared — a full
 *                         delete-and-recreate track is deferred
 *                         to a downstream commit).
 * ```
 *
 * ## Derived UI state
 *
 * The composable exposes `displayedStep` via
 *
 *     if (holder.state is Completed) FinaleConfirmation
 *     else navigationStep
 *
 * and short-circuits AnimatedContent into a repair screen when
 * `holder.state is MissingKeyRepairRequired`. Neither Completed
 * nor MissingKeyRepairRequired can be reached via regular Next/
 * Back — only through the sealed holder's transitions.
 *
 * ## Public (module-internal) API — the only mutators
 *
 * - [markInFlight]           — Done tap kicks off finalize.
 *   NotStarted → InFlight; InFlight → InFlight (idempotent);
 *   Completed → NO-OP (terminal); MissingKeyRepairRequired →
 *   NO-OP (needs explicit repair via resetFromRepairRequired).
 * - [applyFinalizeOutcome]   — atomic terminal transition,
 *   invoked from both the Done-tap coroutine AND the resume
 *   LaunchedEffect. Only fires from InFlight; outcomes arriving
 *   in any other state are DROPPED.
 * - [resetFromRepairRequired] — safe-exit action from
 *   MissingKeyRepairRequired. Resets holder to NotStarted so
 *   the user can navigate away or (fruitlessly) retry. Does NOT
 *   clear the persisted identity — that repair capability is a
 *   downstream track.
 */
internal sealed interface OnboardingFinalizeState {
    object NotStarted : OnboardingFinalizeState
    object InFlight : OnboardingFinalizeState
    data class Completed(val signingPublicKeyHex: String) : OnboardingFinalizeState {
        init {
            require(isValidEd25519PublicKeyHex(signingPublicKeyHex)) {
                "OnboardingFinalizeState.Completed requires a valid Ed25519 signingPublicKeyHex " +
                    "(exactly 64 hex chars, [0-9a-fA-F]); got length " +
                    "${signingPublicKeyHex.length}. Malformed hex must be filtered upstream in " +
                    "runFinalize and routed to FinalizeOutcome.MissingKeyMaterial " +
                    "(round-2 REDLINE §P1 pin)."
            }
        }
    }
    object MissingKeyRepairRequired : OnboardingFinalizeState
}

/**
 * Ed25519 public-key hex contract: exactly 64 hex characters
 * (case-insensitive). Used by `runFinalize` to filter malformed
 * hex out of the sealed model AND by
 * [OnboardingFinalizeState.Completed.init] to make the invariant
 * structural at construction. Round-2 REDLINE §P1 pin — the
 * prior `isNotEmpty()` guard let malformed hex through the
 * outcome layer and blew up inside the holder as an unhandled
 * IllegalArgumentException in the coroutine.
 */
internal fun isValidEd25519PublicKeyHex(hex: String): Boolean {
    if (hex.length != 64) return false
    return hex.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }
}

/**
 * Saver for [OnboardingFinalizeState]. Serialises the sealed
 * variant as a `[tag, hex?]` list — tag is the variant simple
 * name; hex is the `Completed` payload (empty string for the
 * three variants that carry no hex).
 *
 * On restore, a "Completed" tag with an invalid hex payload
 * (empty, wrong length, non-hex chars) is REJECTED with a
 * localised failure — the same invariant [Completed.init]
 * enforces at construction, but surfaced as a "restore"
 * failure for diagnosability of saved-state corruption.
 */
internal val OnboardingFinalizeStateSaver: Saver<OnboardingFinalizeState, Any> = listSaver(
    save = { state ->
        when (state) {
            OnboardingFinalizeState.NotStarted -> listOf("NotStarted", "")
            OnboardingFinalizeState.InFlight -> listOf("InFlight", "")
            is OnboardingFinalizeState.Completed -> listOf(
                "Completed",
                state.signingPublicKeyHex,
            )
            OnboardingFinalizeState.MissingKeyRepairRequired -> listOf("MissingKeyRepair", "")
        }
    },
    restore = {
        when (it[0] as String) {
            "NotStarted" -> OnboardingFinalizeState.NotStarted
            "InFlight" -> OnboardingFinalizeState.InFlight
            "Completed" -> {
                val hex = it[1] as String
                if (!isValidEd25519PublicKeyHex(hex)) {
                    error(
                        "OnboardingFinalizeStateSaver restore: Completed payload had " +
                            "invalid Ed25519 hex (length=${hex.length}) — saved state " +
                            "is corrupt (round-2 REDLINE §P1 pin).",
                    )
                }
                OnboardingFinalizeState.Completed(hex)
            }
            "MissingKeyRepair" -> OnboardingFinalizeState.MissingKeyRepairRequired
            else -> error("Unknown OnboardingFinalizeState tag: ${it[0]}")
        }
    },
)

/**
 * Sealed state holder. Sole public (module-internal) API for the
 * terminal finalize transition. See file KDoc for the ownership
 * contract.
 *
 * Round-1 REDLINE §P2 pin: both `markInFlight` and
 * `applyFinalizeOutcome` enforce state-machine guards
 * (Completed is terminal; NotStarted rejects stale outcomes).
 *
 * Round-2 REDLINE §P1 pin: adds the [OnboardingFinalizeState.MissingKeyRepairRequired]
 * state + [resetFromRepairRequired] mutator so the "identity
 * persisted with malformed signing key" case has a visible,
 * safe-exit representation rather than being an invisible
 * infinite retry loop.
 */
internal class OnboardingFinalizeStateHolder internal constructor(
    private val stateSlot: MutableState<OnboardingFinalizeState>,
) {
    /** Full sealed state — read-only. */
    val state: OnboardingFinalizeState
        get() = stateSlot.value

    /**
     * Backward-compatible phase enum derived from [state].
     * Composable code that only needs the coarse phase can
     * read this without pattern-matching the sealed variant.
     */
    val phase: OnboardingFinalizePhase
        get() = when (state) {
            OnboardingFinalizeState.NotStarted -> OnboardingFinalizePhase.NotStarted
            OnboardingFinalizeState.InFlight -> OnboardingFinalizePhase.InFlight
            is OnboardingFinalizeState.Completed -> OnboardingFinalizePhase.Completed
            OnboardingFinalizeState.MissingKeyRepairRequired ->
                OnboardingFinalizePhase.MissingKeyRepair
        }

    /**
     * The persisted identity's signingPublicKeyHex — only non-
     * null once state is [OnboardingFinalizeState.Completed],
     * and enforced-valid Ed25519 hex by [Completed.init].
     */
    val signingPublicKeyHex: String?
        get() = (state as? OnboardingFinalizeState.Completed)?.signingPublicKeyHex

    /**
     * Set state = InFlight. Called from the Done-tap handler
     * BEFORE launching the finalize coroutine.
     *
     * NO-OP guards:
     *   - Completed: terminal happy path.
     *   - MissingKeyRepairRequired: the user MUST explicitly
     *     call [resetFromRepairRequired] first (safe-exit
     *     acknowledgment). A silent Done-tap loop is exactly
     *     the round-1 REDLINE §P1 tupik.
     * Idempotent for InFlight.
     */
    fun markInFlight() {
        stateSlot.value = when (state) {
            OnboardingFinalizeState.NotStarted -> OnboardingFinalizeState.InFlight
            OnboardingFinalizeState.InFlight -> OnboardingFinalizeState.InFlight
            is OnboardingFinalizeState.Completed -> state  // terminal, no-op
            OnboardingFinalizeState.MissingKeyRepairRequired -> state  // needs explicit repair
        }
    }

    /**
     * Atomic terminal transition. Single write replaces the
     * whole snapshot. Only fires from state InFlight; outcomes
     * arriving from NotStarted, Completed, or
     * MissingKeyRepairRequired are DROPPED (stale-outcome guard).
     */
    fun applyFinalizeOutcome(outcome: FinalizeOutcome) {
        if (state !is OnboardingFinalizeState.InFlight) return
        stateSlot.value = when (outcome) {
            is FinalizeOutcome.Completed ->
                OnboardingFinalizeState.Completed(outcome.signingPublicKeyHex)
            FinalizeOutcome.FailedBeforePersistence ->
                OnboardingFinalizeState.NotStarted
            FinalizeOutcome.FailedAfterPersistence ->
                OnboardingFinalizeState.InFlight
            FinalizeOutcome.MissingKeyMaterial ->
                OnboardingFinalizeState.MissingKeyRepairRequired
        }
    }

    /**
     * Safe-exit action from [OnboardingFinalizeState.MissingKeyRepairRequired].
     * Transitions the holder back to NotStarted so the user can
     * navigate away from the repair-required screen without
     * being trapped in an invisible loop.
     *
     * NO-OP for every other state (defensive: only the
     * repair-required screen wires this to a user action).
     *
     * IMPORTANT: this does NOT clear the persisted identity on
     * disk. The controller will still short-circuit to
     * Complete(brokenRecord) on the next Done tap, and
     * runFinalize will loop back to MissingKeyMaterial →
     * MissingKeyRepairRequired. The reset is a "safe-exit"
     * acknowledgment, not a repair. A downstream commit needs to
     * add IdentityManager.delete() + controller reset for a full
     * repair action.
     */
    fun resetFromRepairRequired() {
        if (state !is OnboardingFinalizeState.MissingKeyRepairRequired) return
        stateSlot.value = OnboardingFinalizeState.NotStarted
    }
}

/**
 * Composable factory. Owns the `rememberSaveable` slot backing
 * the sealed state so rotation preserves the full snapshot
 * atomically without exposing the [MutableState] to the
 * surrounding composable.
 *
 * The [initialState] parameter is consulted ONLY on the very
 * first composition of a given `rememberSaveable` slot — after
 * that, the saved value takes over on every subsequent
 * composition + rotation. C6-a round-4 REDLINE §P1 pin uses
 * this to seed the holder with
 * [OnboardingFinalizeState.MissingKeyRepairRequired] on
 * next-launch when [IdentityRepairMarker.isRepairRequired] is
 * true — MainActivity's startup gate routes to Onboarding
 * regardless of `identity != null` in that case, and the flow
 * needs to open the repair screen immediately rather than
 * driving the user through Welcome → How → Identity → Privacy
 * → Permissions → Done first.
 */
@Composable
internal fun rememberOnboardingFinalizeStateHolder(
    initialState: OnboardingFinalizeState = OnboardingFinalizeState.NotStarted,
): OnboardingFinalizeStateHolder {
    val slot = rememberSaveable(stateSaver = OnboardingFinalizeStateSaver) {
        mutableStateOf<OnboardingFinalizeState>(initialState)
    }
    return remember(slot) { OnboardingFinalizeStateHolder(slot) }
}
