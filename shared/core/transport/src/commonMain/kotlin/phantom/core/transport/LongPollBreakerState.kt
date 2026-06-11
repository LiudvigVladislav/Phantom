// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * Trek 2 Stage 2B-B (L9) — REST long-poll breaker state.
 *
 * Separate from [RestStateMachine]: this breaker does NOT alter
 * `RestMode` — it controls REST poll cadence and bad-MAC
 * suspension only. The orchestrator's `wsActivePollLoop` and the
 * legacy `pollLoop` both observe this state and gate their
 * ingestion accordingly.
 *
 * **C4 surface.** The L7 bad-MAC posture lands the
 * [SuspendedOnPoison] state and the [Closed] default. Both REST
 * poll loops observe the breaker via a snapshot under the
 * orchestrator's `_inboundStateMutex`; when the breaker is
 * `SuspendedOnPoison`, both loops drop all envelopes (no emit, no
 * ack, no cursor advance) until the orchestrator restarts. Direct
 * WSS and non-REST transports stay operational — `LongPollBreakerState`
 * controls ONLY the REST poll path.
 *
 * **C5 surface.** The L9 circuit breaker [Open] and [HalfOpen]
 * states cap the REST poll cadence under sustained transport
 * failure or `410 Gone` storms. Quantitative parameters are
 * pinned in `RestFallbackOrchestrator`'s companion (see
 * `BREAKER_*` constants). Per scope §L9 the breaker MUST NOT
 * become a MAC verify downgrade — a loop running with the
 * breaker `Open` or `HalfOpen` still gates ingestion on verify,
 * still observes the verify-key state machine, still uses the
 * same `SeqMacVerifier`.
 */
sealed class LongPollBreakerState {

    /**
     * Normal operating state. REST poll loops poll at their
     * adaptive cadence; verify path runs per envelope.
     */
    object Closed : LongPollBreakerState()

    /**
     * Trek 2 Stage 2B-B (C5, L9) — both REST poll loops back off
     * for [cooldownMs] before the breaker transitions to
     * [HalfOpen] for a single probe.
     *
     * @param reason Why the breaker opened — surfaced via
     *   `RestStateMachine.Event.RestPollDegraded` and in
     *   `REST_TRACE breaker_open reason=...` log lines for
     *   diagnostic separation between sustained network failure
     *   and a `410 Gone` rotation loop.
     * @param cooldownMs Wall-clock cooldown the breaker timer
     *   waits before transitioning to [HalfOpen]. Initial open
     *   uses `BREAKER_INITIAL_COOLDOWN_MS`; subsequent
     *   [HalfOpen]-fail-back-to-[Open] cycles double the cooldown
     *   per `BREAKER_COOLDOWN_GROWTH_FACTOR`, capped at
     *   `BREAKER_COOLDOWN_CEILING_MS`. The [Status410Storm] reason
     *   pins the cooldown to `BREAKER_410_STORM_COOLDOWN_MS` so
     *   the L8 410-reauth dance and the breaker timer recover on
     *   the same beat.
     */
    data class Open(
        val reason: BreakerOpenReason,
        val cooldownMs: Long,
    ) : LongPollBreakerState()

    /**
     * Trek 2 Stage 2B-B (C5, L9) — cooldown expired; the next
     * REST poll iteration claims a probe permit, issues exactly
     * one `/relay/poll`, and resolves the breaker to [Closed]
     * (probe 200/2xx) or back to [Open] with the doubled cooldown
     * (probe transport failure / 5xx / timeout).
     *
     * @param probeInFlight Atomic permit guarded by
     *   `_inboundStateMutex`. On entering [HalfOpen] the value is
     *   `false`. The first poll loop to observe [HalfOpen] under
     *   the mutex flips the value to `true` and proceeds; any
     *   other loop observing `HalfOpen(probeInFlight = true)`
     *   short-circuits and delays. The claimant's probe call is
     *   wrapped in
     *   `try { ... } finally { withContext(NonCancellable) { _inboundStateMutex.withLock { if (state is HalfOpen && state.probeInFlight) state = HalfOpen(probeInFlight = false) } } }`
     *   so a mid-probe cancellation releases the permit and the
     *   next iteration can re-attempt. If the probe already
     *   resolved into [Closed] or [Open] before the `finally`,
     *   the reset is a no-op.
     */
    data class HalfOpen(
        val probeInFlight: Boolean = false,
    ) : LongPollBreakerState()

    /**
     * Entered exclusively by the L7 bad-MAC posture: a `mac_mismatch`
     * or `no_mac_field` outcome from the same `envelope_id` after
     * the orchestrator's one-shot forced session/key refresh.
     *
     * Behaviour: both REST poll loops drop ALL envelopes (no emit,
     * no ack, no cursor advance). Direct WSS, Reality, and Tor
     * transports stay operational — the messenger remains usable
     * over those paths.
     *
     * Exit conditions: orchestrator restart OR an explicit
     * out-of-band recovery path. The breaker timer does NOT
     * auto-recover this state (it is qualitatively different from
     * [Open]'s cooldown-driven recovery).
     */
    object SuspendedOnPoison : LongPollBreakerState()
}

/**
 * Trek 2 Stage 2B-B (C5, L9) — taxonomy of breaker-open triggers.
 * Surfaced in [LongPollBreakerState.Open.reason] and in the
 * `RestStateMachine.Event.RestPollDegraded` event so the
 * AppContainer wire-up and the existing `WsDegradationDetector`
 * telemetry can discriminate sustained network failure from a
 * `410 Gone` rotation loop.
 *
 * @see LongPollBreakerState.Open
 */
enum class BreakerOpenReason {
    /**
     * `BREAKER_CONSECUTIVE_FAIL_THRESHOLD` consecutive REST poll
     * failures of network-class type: `IOException` (DNS, connect,
     * TLS, read), HTTP 5xx response, or response timeout
     * (`SocketTimeoutException` raised by the L2-gated read
     * budget). Status 401 / 410 / 429 are NOT counted here —
     * they have their own paths (401 → token refresh, 410 → L8
     * reauth dance + [Status410Storm], 429 → respect
     * `Retry-After` clamped at `RETRY_AFTER_HARD_CAP_SECONDS`).
     */
    ConsecutiveRestFailures,

    /**
     * `BREAKER_410_STORM_THRESHOLD` consecutive `410 Gone`
     * responses within `BREAKER_410_STORM_WINDOW_MS` wall-clock
     * milliseconds. Transitions to [LongPollBreakerState.Open]
     * immediately on the threshold-th 410 with the cooldown set
     * to `BREAKER_410_STORM_COOLDOWN_MS` so the L8 410-reauth
     * dance and the breaker dance do not race; they fire on the
     * same condition with the same exit timer.
     */
    Status410Storm,
}
