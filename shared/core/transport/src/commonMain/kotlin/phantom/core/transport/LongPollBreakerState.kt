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
 * **C4 surface (this commit).** The L7 bad-MAC posture lands the
 * [SuspendedOnPoison] state and the [Closed] default. Both REST
 * poll loops observe the breaker via a snapshot under the
 * orchestrator's `_inboundStateMutex`; when the breaker is
 * `SuspendedOnPoison`, both loops drop all envelopes (no emit, no
 * ack, no cursor advance) until the orchestrator restarts. Direct
 * WSS and non-REST transports stay operational — `LongPollBreakerState`
 * controls ONLY the REST poll path.
 *
 * **C5 surface (next commit).** The L9 circuit breaker (`Open`,
 * `HalfOpen`, the eight numeric constants) extends this sealed
 * hierarchy. C5 will add `Open(reason, cooldownMs)` +
 * `HalfOpen(probeInFlight)` cases; this file's existing cases are
 * preserved without semantic change.
 */
sealed class LongPollBreakerState {

    /**
     * Normal operating state. REST poll loops poll at their
     * adaptive cadence; verify path runs per envelope.
     */
    object Closed : LongPollBreakerState()

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
     * `Open`'s cooldown-driven recovery).
     */
    object SuspendedOnPoison : LongPollBreakerState()
}
