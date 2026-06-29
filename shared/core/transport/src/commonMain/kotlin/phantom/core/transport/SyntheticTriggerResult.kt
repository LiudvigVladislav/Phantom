// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * QUIESCENCE-VALIDATION-L1-SYNTHETIC-MINI-LOCK §6 contract (2026-06-30).
 *
 * Typed-return contract for the L1 synthetic-trigger debug flag method
 * [KtorRelayTransport.debugForceMode2Synthetic]. Replaces the `Boolean`
 * success-flag shape sketched in `direct-wss-mode2-recon1.md` §12.4 with
 * a sealed hierarchy so the caller can distinguish each refusal cause
 * structurally — diagnosis is structural, not log-pattern-match.
 *
 * Six members:
 *
 *   - [Fired]                          — synthetic event constructed +
 *                                        `_wsSessionLifecycle.trySend(...)` succeeded.
 *                                        Trigger CANNOT fire again until a new
 *                                        [WsSessionLifecycleEvent.Connected] resets
 *                                        the one-shot latch.
 *   - [RefusedDisabled]                — `debugForceMode2Enabled` constructor flag is
 *                                        `false`. Exits immediately without inspecting
 *                                        any state and without logging anything that
 *                                        reveals the synthetic-trigger surface
 *                                        (release-pin defence-in-depth).
 *   - [RefusedNotConnected]            — `_state.value !is TransportState.Connected`
 *                                        at the moment of the precondition check.
 *                                        The race window between this check and the
 *                                        subsequent `trySend` is addressed by the L1
 *                                        mini-lock §7.2 D-1 verdict — duplicate or
 *                                        stale `Ended` events are absorbed by the
 *                                        existing `RestStateMachine` mode transition
 *                                        guard after the first event moves
 *                                        `WsActive → RestActive`.
 *   - [RefusedDurationOutOfRange]      — `durationMs` lies outside the
 *                                        `MODE_2_MIN_DURATION_MS..MODE_2_MAX_DURATION_MS`
 *                                        window enforced by `RestStateMachine`'s
 *                                        signature check. Carries the requested value +
 *                                        the bounds so the caller can diagnose the
 *                                        rejection without re-reading the constants.
 *   - [RefusedAlreadyFired]            — the one-shot latch for the current
 *                                        [WsSessionLifecycleEvent.Connected] epoch
 *                                        has already been consumed. Caller must wait
 *                                        for a new `Connected` event.
 *   - [RefusedAlreadyArmed]            — `RestStateMachine` is already in the
 *                                        sticky-armed state (a previous Mode 2 has
 *                                        actuated; recovery is either pending or in
 *                                        flight). The synthetic refuses to fire so
 *                                        an operator-initiated trigger does not
 *                                        falsely fail an in-progress recovery
 *                                        candidate. Closes the L1 mini-lock §7.2 D-1
 *                                        edge case identified during pre-flight.
 *
 * The sealed shape is preferred over `enum class` because
 * [RefusedDurationOutOfRange] carries diagnostic fields. The other four
 * refusals are `object` singletons.
 */
public sealed class SyntheticTriggerResult {
    /**
     * Synthetic event constructed and successfully enqueued into
     * `_wsSessionLifecycle`. Both Consumer A (state-machine actuation)
     * and Consumer B (telemetry detector) downstream code will run.
     */
    public object Fired : SyntheticTriggerResult() {
        override fun toString(): String = "Fired"
    }

    /**
     * `debugForceMode2Enabled` constructor flag is `false`. The method
     * exits immediately after the gate check, without logging anything
     * that reveals the synthetic-trigger surface.
     */
    public object RefusedDisabled : SyntheticTriggerResult() {
        override fun toString(): String = "RefusedDisabled"
    }

    /**
     * `_state.value !is TransportState.Connected` at the moment of the
     * precondition check. The synthetic does not fire — the L1
     * mini-lock contract requires a real WS session to have been
     * `Connected` before a synthetic `Ended` is meaningful.
     */
    public object RefusedNotConnected : SyntheticTriggerResult() {
        override fun toString(): String = "RefusedNotConnected"
    }

    /**
     * `durationMs` does not satisfy the Mode 2 signature check. The
     * data fields carry the requested value + the bounds enforced by
     * `RestStateMachine` so the caller can diagnose without re-reading
     * the constants.
     */
    public data class RefusedDurationOutOfRange(
        val requestedMs: Long,
        val minMs: Long,
        val maxMs: Long,
    ) : SyntheticTriggerResult()

    /**
     * One-shot latch already consumed for the current `Connected`
     * session epoch. The latch resets on each new
     * [WsSessionLifecycleEvent.Connected] event the transport observes.
     * Closes the L1 mini-lock §13.3.9 Part A repeated-trigger DoS
     * surface.
     */
    public object RefusedAlreadyFired : SyntheticTriggerResult() {
        override fun toString(): String = "RefusedAlreadyFired"
    }

    /**
     * Sticky armed AND recovery either pending or in flight at the
     * moment of the precondition check. The synthetic refuses to fire
     * so an operator-initiated trigger does not falsely fail the
     * in-progress recovery candidate's `sticky_recovery_failed`
     * branch. Closes the L1 mini-lock §7.2 D-1 edge case.
     */
    public object RefusedAlreadyArmed : SyntheticTriggerResult() {
        override fun toString(): String = "RefusedAlreadyArmed"
    }
}
