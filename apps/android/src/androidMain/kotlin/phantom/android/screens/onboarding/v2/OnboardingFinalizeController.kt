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

/**
 * State machine for the two-phase onboarding finalize:
 *
 *   1. Persistence — `IdentityManager.createOrLoad(username)` writes the
 *      identity to disk. On success, disk is authoritative from this
 *      point forward.
 *   2. Runtime initialisation — `AppContainer.initMessaging(record,
 *      dhKeyPair)` boots the SessionManager + session-repair services.
 *      If this fails, disk still holds the persisted identity — the
 *      user cannot un-create it by editing the username; retry MUST
 *      re-run initMessaging with the persisted record, NOT createOrLoad.
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
     * A finalize coroutine is in flight (either phase). Second Done
     * tap short-circuits via the double-tap guard while in this state.
     */
    object Working : FinalizeState
    /**
     * Phase 1 completed — identity has been written to disk. Phase 2
     * has not completed (either never ran, cancelled, or errored).
     * Retry MUST re-use these values and re-run initMessaging only —
     * calling createOrLoad again would ignore the caller's username
     * because production createOrLoad is idempotent-by-existence.
     *
     * Back navigation to Identity is locked in this state — see
     * [isBackNavigationLockedByFinalize].
     */
    data class Persisted(
        val record: IdentityRecord,
        val keyPair: IdentityKeyPair,
    ) : FinalizeState
    /** Both phases done. Flow can advance to FinaleConfirmation. */
    data class Complete(val record: IdentityRecord) : FinalizeState
}

/**
 * Back-navigation lock: once persistence has occurred, the flow MUST
 * NOT let the user return to Identity to change the username — the
 * persisted record is immutable, so the caller's intent would be
 * silently dropped. Callers gate their BackHandler / edge-swipe
 * predicates through this helper.
 */
fun isBackNavigationLockedByFinalize(state: FinalizeState): Boolean =
    state !is FinalizeState.Idle

/**
 * The finalize orchestrator. Owns the two-phase state machine plus
 * a transient user-facing error message. Composed at the flow level;
 * unit-tested directly without going through Compose UI.
 *
 * Contract, pinned by `OnboardingV2FinalizeContractTest`:
 *
 *   - First `finalize(username)` from [FinalizeState.Idle] invokes
 *     [createOrLoad] exactly once, then invokes [initMessaging]
 *     exactly once. State advances Idle → Working → Persisted →
 *     Complete.
 *   - Second `finalize(username)` while state is [FinalizeState.Working]
 *     short-circuits with no side effect. Neither lambda invoked.
 *   - After phase-1 failure at [createOrLoad], state returns to
 *     Idle, transientErrorMessage is set to a stable user-facing
 *     string (never the throwable's own message — that may carry
 *     internal paths or diagnostic details), the throwable is
 *     forwarded to [onError] for logging.
 *   - After phase-2 failure at [initMessaging] (identity ALREADY
 *     persisted), state stays at [FinalizeState.Persisted]. Retry
 *     re-runs only [initMessaging] with the persisted record; the
 *     `username` argument to `finalize` is ignored (documented via
 *     the Persisted contract).
 *   - Cancellation ([CancellationException]) is RE-THROWN so
 *     structured concurrency sees it. The state is repaired to
 *     reflect what actually happened on disk (Idle if cancelled
 *     during createOrLoad; Persisted if cancelled during
 *     initMessaging).
 *   - Complete state is terminal — further `finalize` calls no-op.
 */
class OnboardingFinalizeController(
    private val createOrLoad: suspend (username: String) -> Pair<IdentityRecord, IdentityKeyPair>,
    private val initMessaging: suspend (IdentityRecord, IdentityKeyPair) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    var state: FinalizeState by mutableStateOf(FinalizeState.Idle)
        private set

    var transientErrorMessage: String? by mutableStateOf(null)
        private set

    suspend fun finalize(username: String) {
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
                    // Retry path — reuse the persisted record. DO NOT
                    // call createOrLoad again: production createOrLoad
                    // returns whatever identity is on disk regardless
                    // of the requested username, so a second call would
                    // silently ignore any username change.
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
                    // NonCancellable defers cancellation delivery until
                    // the block exits, guaranteeing that whenever
                    // `createOrLoad` returns a pair, the matching
                    // `state = Persisted` assignment also runs. Any
                    // pending cancellation is re-thrown on the very
                    // next suspension point (inside `initMessaging`
                    // below), so structured concurrency is preserved.
                    withContext(NonCancellable) {
                        val pair = createOrLoad(username)
                        state = FinalizeState.Persisted(pair.first, pair.second)
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
            // still Working, cancellation hit during phase 1 → nothing
            // persisted → restore previous. If state is Persisted,
            // phase 1 already committed to disk → keep that state.
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
