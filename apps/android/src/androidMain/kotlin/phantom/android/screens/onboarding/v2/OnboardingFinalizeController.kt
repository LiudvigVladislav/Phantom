// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import phantom.core.identity.IdentityKeyPair
import phantom.core.identity.IdentityRecord
import phantom.core.transport.PrivacyMode

/**
 * State machine for the three-phase onboarding finalize:
 *
 *   0. Privacy-mode persistence — `savePrivacyMode(mode)` writes the
 *      user's selected mode to `TransportPreferences` so
 *      `TransportManager` picks the matching strategy on the first
 *      connect after onboarding. Round-1 REDLINE on Commit 4 §P1-1:
 *      the previous shape only threaded `privacyMode` through
 *      `OnboardingFormState` and never called any setter, so Private /
 *      Standard selections were silently discarded — the first
 *      connect always used the default. This step now runs FIRST,
 *      before identity persistence.
 *   1. Identity persistence — `IdentityManager.createOrLoad(username)`
 *      writes the identity to disk. On success, disk is authoritative
 *      from this point forward.
 *   2. Runtime initialisation — `AppContainer.initMessaging(record,
 *      dhKeyPair)` boots the SessionManager + session-repair services.
 *      If this fails, disk still holds the persisted identity — the
 *      user cannot un-create it by editing the username; retry MUST
 *      re-run initMessaging with the persisted record + persisted
 *      mode, NOT call createOrLoad or savePrivacyMode again.
 *
 * Round-1 REDLINE on Commit 3 §P1-1: previous shape called both phases
 * back-to-back inside a single `runCatching { ... }` block and treated
 * any failure as "nothing happened". A second Done tap after such a
 * failure would call `createOrLoad(possibly-different-username)`; the
 * production `createOrLoad` returns the ALREADY-PERSISTED identity
 * regardless of the requested username, so the second attempt would
 * silently ignore the user's change. The [Persisted] terminal here
 * makes that impossible: once we reach [Persisted], retry ignores the
 * `username` argument entirely and re-runs only initMessaging.
 */
sealed interface FinalizeState {
    /** Nothing running, nothing persisted. Freely navigable. */
    object Idle : FinalizeState
    /**
     * A finalize coroutine is in flight (any phase). Second Done
     * tap short-circuits via the double-tap guard while in this state.
     */
    object Working : FinalizeState
    /**
     * Phases 0 + 1 completed — mode + identity have been written to
     * disk. Phase 2 (`initMessaging`) has not completed (either never
     * ran, cancelled, or errored). Retry MUST re-use these frozen
     * values and re-run initMessaging only:
     *
     *   - Calling createOrLoad again would ignore the caller's
     *     username because production createOrLoad is idempotent-by-
     *     existence.
     *   - Calling savePrivacyMode with a fresh argument would let a
     *     retry silently rewrite the user's committed mode (e.g. if
     *     the flow's UI state were reconstructed with a different
     *     default while the controller was Persisted).
     *
     * Back navigation to Privacy / Identity is locked in this state —
     * see [isBackNavigationLockedByFinalize].
     */
    data class Persisted(
        val record: IdentityRecord,
        val keyPair: IdentityKeyPair,
        val privacyMode: PrivacyMode,
    ) : FinalizeState
    /** All three phases done. Flow can advance to FinaleConfirmation. */
    data class Complete(val record: IdentityRecord) : FinalizeState
}

/**
 * Back-navigation lock: once persistence has occurred, the flow MUST
 * NOT let the user return to Identity to change the username OR to
 * Privacy to change the mode — both are immutable at that point, so
 * the caller's intent would be silently dropped. Callers gate their
 * BackHandler / edge-swipe predicates through this helper.
 */
fun isBackNavigationLockedByFinalize(state: FinalizeState): Boolean =
    state !is FinalizeState.Idle

/**
 * The finalize orchestrator. Owns the three-phase state machine plus
 * a transient user-facing error message. Composed at the flow level;
 * unit-tested directly without going through Compose UI.
 *
 * Contract, pinned by `OnboardingV2FinalizeContractTest`:
 *
 *   - First `finalize(username, mode)` from [FinalizeState.Idle]
 *     invokes [savePrivacyMode] exactly once with `mode`, then
 *     [createOrLoad] exactly once with `username`, then
 *     [initMessaging] exactly once. State advances Idle → Working →
 *     Persisted → Complete.
 *   - Second `finalize(...)` while state is [FinalizeState.Working]
 *     short-circuits with no side effect. No lambda is invoked.
 *   - After phase-1 failure at [createOrLoad] (the mode was already
 *     persisted, but disk holds no identity), state returns to Idle,
 *     transientErrorMessage is set to a stable user-facing string
 *     (never the throwable's own message — that may carry internal
 *     paths or diagnostic details), the throwable is forwarded to
 *     [onError] for logging. Note the mode write from phase 0
 *     survives — it is a preference toggle whose effect is scoped to
 *     the first connect, and the retry path always starts with a
 *     fresh phase 0.
 *   - After phase-2 failure at [initMessaging] (mode + identity
 *     ALREADY persisted), state stays at [FinalizeState.Persisted].
 *     Retry re-runs only [initMessaging] with the persisted record;
 *     the `username` and `privacyMode` arguments to `finalize` are
 *     ignored (documented via the Persisted contract).
 *   - Cancellation ([CancellationException]) is RE-THROWN so
 *     structured concurrency sees it. The state is repaired to
 *     reflect what actually happened on disk (Idle if cancelled
 *     before phases 0-1 completed; Persisted if cancelled during
 *     initMessaging).
 *   - Complete state is terminal — further `finalize` calls no-op.
 */
class OnboardingFinalizeController(
    private val savePrivacyMode: suspend (PrivacyMode) -> Unit,
    private val createOrLoad: suspend (username: String) -> Pair<IdentityRecord, IdentityKeyPair>,
    private val initMessaging: suspend (IdentityRecord, IdentityKeyPair) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    var state: FinalizeState by mutableStateOf(FinalizeState.Idle)
        private set

    var transientErrorMessage: String? by mutableStateOf(null)
        private set

    suspend fun finalize(username: String, privacyMode: PrivacyMode) {
        val previousState = state
        // Double-tap guard: a second entry while Working (or Complete) is
        // a no-op. Persisted allows re-entry — that's the retry path.
        if (previousState is FinalizeState.Working) return
        if (previousState is FinalizeState.Complete) return

        state = FinalizeState.Working
        transientErrorMessage = null

        try {
            val (record, keyPair) = when (previousState) {
                is FinalizeState.Persisted -> {
                    // Retry path — reuse the persisted mode + record.
                    // DO NOT call savePrivacyMode or createOrLoad
                    // again: on retry the caller's fresh arguments
                    // MUST NOT overwrite the values committed on the
                    // first success. Same shape as the username
                    // invariant pinned in Commit 3's REDLINE.
                    previousState.record to previousState.keyPair
                }
                is FinalizeState.Idle -> {
                    // Round-2 REDLINE §P1: `createOrLoad` performs a
                    // SQLite insert inside `withContext(Dispatchers.IO)`.
                    // Without NonCancellable, cancellation could land on
                    // the IO→calling-dispatcher return suspension AFTER
                    // the row was committed but BEFORE we captured the
                    // returned pair — the caller would never see the
                    // pair, the catch branch would observe `state ==
                    // Working` and revert to Idle, and disk would then
                    // hold an identity the UI thinks doesn't exist.
                    //
                    // Round-1 REDLINE on Commit 4 §P1-1 extends the
                    // guarded window to also include `savePrivacyMode`
                    // and the subsequent state assignment — so a
                    // cancellation landing anywhere in the phase-0 /
                    // phase-1 pair cannot leave disk in an inconsistent
                    // state relative to the UI's understanding of it.
                    //
                    // Ordering inside the guard: mode first, THEN
                    // identity. If phase 0 succeeds but phase 1 fails,
                    // the mode preference is set to the user's choice
                    // — harmless because no identity exists yet, and
                    // the retry path always re-runs phase 0 with the
                    // caller's fresh mode argument (retry starts from
                    // Idle after a phase-1 failure). If we did phase 1
                    // first and then phase 0, a phase-0 failure would
                    // leave an identity persisted with the default
                    // (Standard) mode — a worse outcome.
                    //
                    // NonCancellable defers cancellation delivery until
                    // the block exits, guaranteeing that whenever
                    // phases 0 + 1 both complete, the matching
                    // `state = Persisted` assignment also runs. Any
                    // pending cancellation is re-thrown on the very
                    // next suspension point (inside `initMessaging`
                    // below), so structured concurrency is preserved.
                    withContext(NonCancellable) {
                        savePrivacyMode(privacyMode)
                        val pair = createOrLoad(username)
                        state = FinalizeState.Persisted(pair.first, pair.second, privacyMode)
                        pair
                    }
                }
                // Working / Complete guarded above.
                else -> return
            }

            initMessaging(record, keyPair)
            state = FinalizeState.Complete(record)
        } catch (ce: CancellationException) {
            // Structured concurrency: cancellation MUST propagate.
            // Repair state to reflect on-disk reality: if state is
            // still Working, cancellation hit during phases 0-1 →
            // nothing (or only mode preference) persisted → restore
            // previous. If state is Persisted, both phases already
            // committed → keep that state.
            if (state is FinalizeState.Working) {
                state = previousState
            }
            throw ce
        } catch (t: Throwable) {
            // Log details separately — never surface throwable.message
            // to the user, it may contain internal paths / diagnostic
            // information.
            onError(t)
            transientErrorMessage = "Couldn't complete setup. Please try again."
            // Same state-repair logic as the cancellation branch.
            if (state is FinalizeState.Working) {
                state = previousState
            }
            // else state is Persisted from before initMessaging call —
            // keep it so retry re-runs phase 2 only.
        }
    }

    /** Consume the transient error message — call from UI once shown. */
    fun dismissTransientError() {
        transientErrorMessage = null
    }
}
