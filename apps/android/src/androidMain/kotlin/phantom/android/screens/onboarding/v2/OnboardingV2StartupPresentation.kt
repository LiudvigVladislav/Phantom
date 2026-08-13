// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * C6-a round-8 REDLINE §P0/§P1 pin — testable orchestrator that
 * turns a [StartupRouteDecision] into a presentation the caller
 * (MainActivity) can render, with the marker side-effect
 * INJECTED so its discipline is testable without touching real
 * SharedPreferences.
 *
 * Contract (from architect's round-8 scope-lock):
 *   - `RepairQuarantine` writes the durable marker off-Main
 *     (`Dispatchers.IO`); on write failure the presentation
 *     STILL renders repair (in-memory fallback).
 *   - `TransientStartupFailure` writes NOTHING. Ever. The
 *     presentation is a dedicated startup error surface with a
 *     Retry action.
 *   - `FreshOnboarding`, `Migration`, `ChatList` write NOTHING.
 *   - Cancellation from the marker write MUST NOT change the
 *     presentation — the caller re-runs from the fresh scope
 *     on Activity recreation.
 *
 * The pure decision function [decideStartupRoute] handles the
 * routing math; this orchestrator handles the durable side-
 * effects. Split so tests can assert marker discipline
 * (which decisions write? which don't?) without composing the
 * whole MainActivity.
 */
internal sealed interface StartupPresentation {
    /** Show fresh-install onboarding (Terms → Welcome → …). */
    data object FreshOnboarding : StartupPresentation

    /**
     * Show onboarding wrapper with the finalize holder seeded at
     * `MissingKeyRepairRequired` (Terms bypassed via
     * [OnboardingScreenV2Host]'s OR-clause). This is used for
     * both durable Repair AND in-memory Repair-fallback (when
     * the marker write failed).
     */
    data class OnboardingRepair(val reason: RepairReason) : StartupPresentation

    /** Show migration screen (identity + init messaging succeeded, migration needed). */
    data object Migration : StartupPresentation

    /** Show main chat list. */
    data object ChatList : StartupPresentation

    /**
     * Show retryable startup error surface. The user taps Retry
     * → MainActivity re-runs the startup effect (single-flight).
     */
    data class StartupError(val reason: TransientReason) : StartupPresentation
}

/**
 * Convert a decision into a presentation, performing side-
 * effects (marker write for Repair) with fail-loud logging.
 *
 * @param decision the routing outcome
 * @param markerWriter injected `suspend () -> Boolean` returning
 *   whether the durable write reached disk. Tests pass a
 *   controllable stub; production passes
 *   `withContext(Dispatchers.IO) { IdentityRepairMarker.markRepairRequiredBlocking(ctx) }`.
 */
internal suspend fun applyStartupDecision(
    decision: StartupRouteDecision,
    markerWriter: suspend () -> Boolean,
): StartupPresentation = when (decision) {
    StartupRouteDecision.FreshOnboarding -> StartupPresentation.FreshOnboarding
    StartupRouteDecision.Migration -> StartupPresentation.Migration
    StartupRouteDecision.ChatList -> StartupPresentation.ChatList
    is StartupRouteDecision.RepairQuarantine -> {
        val ok = try {
            markerWriter()
        } catch (ce: CancellationException) {
            // Round-8 §P0/§P1 pin: cancellation from marker
            // write MUST rethrow — the caller re-runs from
            // fresh scope. If we caught+fell-through, the
            // partial-side-effect state would silently persist
            // depending on how far the write got.
            throw ce
        } catch (t: Throwable) {
            Log.e(
                "MainActivity",
                "IdentityRepairMarker.markRepairRequiredBlocking threw during " +
                    "RepairQuarantine orchestration (reason=${decision.reason}). " +
                    "Falling back to in-memory repair presentation for THIS session; " +
                    "next launch re-detects via decideStartupRoute's malformed-hex " +
                    "or null-hex branch.",
                t,
            )
            false
        }
        if (!ok) {
            Log.e(
                "MainActivity",
                "markerWriter returned false in RepairQuarantine branch " +
                    "(reason=${decision.reason}). In-memory repair presentation " +
                    "wins for this session.",
            )
        }
        StartupPresentation.OnboardingRepair(decision.reason)
    }
    is StartupRouteDecision.TransientStartupFailure -> {
        // Round-8 §P0 pin: TransientStartupFailure NEVER writes
        // the marker, NEVER uses the finalize holder, NEVER
        // shows repair text.
        //
        // Mini-round §P2 pin: log the FULL Throwable stack trace
        // via `Log.w(tag, msg, cause)`. Prior shape only logged
        // the reason enum name, dropping every stack trace of
        // the underlying operational failure — impossible to
        // diagnose in field logs. If `decision.cause` is null
        // (InitMessagingReturnedFalse — the lambda returned
        // `false` cleanly), only the reason is logged.
        if (decision.cause != null) {
            Log.w(
                "MainActivity",
                "Transient startup failure (reason=${decision.reason}). " +
                    "Showing retryable error screen; marker NOT written.",
                decision.cause,
            )
        } else {
            Log.w(
                "MainActivity",
                "Transient startup failure (reason=${decision.reason}, no cause). " +
                    "Showing retryable error screen; marker NOT written.",
            )
        }
        StartupPresentation.StartupError(decision.reason)
    }
}

/**
 * Convenience: the production marker-writer, off-Main via
 * `Dispatchers.IO` per architect's round-8 §P0 guidance.
 * Extracted so tests can inject a stub instead of running IO.
 */
internal suspend fun productionMarkerWriter(
    context: android.content.Context,
): Boolean = withContext(Dispatchers.IO) {
    IdentityRepairMarker.markRepairRequiredBlocking(context)
}
