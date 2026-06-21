// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlin.jvm.JvmInline

/**
 * RC-RECONNECT-QUIESCENCE1 (2026-06-22) — typed 5-phase gate that
 * controls whether [KtorRelayTransport.runReconnectLoop] is allowed to
 * open a fresh WS session. Backed by a [kotlinx.coroutines.flow.StateFlow]
 * on [RestStateMachine].
 *
 * State machine:
 *
 *   Open
 *     ↓  armSticky() on TransportKind.Direct
 *   Quiesced(stickyGen)
 *     ↓  coordinator: beginRouteChange() → disconnectAndJoin() → release()
 *     ↓  coordinator: issueProbeAfterRewalk(routeEpoch)
 *   ProbeAvailable(stickyGen, routeEpoch, token, budget)
 *     ↓  reconnect-loop: awaitAndClaimProbe(ownerGeneration)  [atomic]
 *   ProbeClaimed(stickyGen, routeEpoch, token, ownerGeneration, budget)
 *     ↓  loop opens WS; relay accepts; Connected(sessionEpoch, ownerGeneration)
 *   CandidateProving(stickyGen, sessionEpoch)
 *     ↓  60s probation succeeds (R3.6 ws_alive_60s → sticky_cleared)
 *   Open
 *
 * Invariants:
 *   - `token` is one-shot. Consumed and removed at the transition from
 *     [ProbeClaimed] to [CandidateProving] (i.e. on first successful
 *     [WsSessionConnected] for that probe with a matching
 *     `ownerGeneration` AND `routeEpoch`).
 *   - `token` is NEVER logged. The carrier types redact via
 *     [ProbeToken.toString] which always prints `ProbeToken(REDACTED)`.
 *     Telemetry emits `route_epoch=` but not `token=`.
 *   - [CandidateProving] type-level absence of `token`: by construction
 *     the data class has no token field, so a collector observing the
 *     gate after a successful proof cannot read a token value.
 *   - `ownerGeneration` is assigned at ATOMIC CLAIM TIME ([ProbeClaimed]),
 *     not at probe issue ([ProbeAvailable]). The coordinator cannot know
 *     the next `connectionGeneration` safely; the reconnect-loop binds
 *     itself on entry.
 *   - `routeEpoch` is monotonic. Older epochs cannot claim newer probes;
 *     newer epochs invalidate older claims (validatePermitAfterAuth
 *     re-checks against the LATEST state-machine routeEpoch). A new
 *     `beginRouteChange` ALSO atomically flips any stuck
 *     [ProbeAvailable] / [ProbeClaimed] / [CandidateProving] back to
 *     [Quiesced] so a subsequent `issueProbeAfterRewalk` can succeed.
 *   - [ProbeBudget] applies ONLY to the pre-Connected attempt budget.
 *     The 60-second R3.6 probation runs on its own clock under
 *     [CandidateProving] and is unrelated.
 */
sealed interface WsReconnectGate {
    /**
     * Default. WS reconnect loop runs as before. Reached on cold start
     * and after a successful recovery proof (sticky_cleared via
     * ws_alive_60s).
     */
    object Open : WsReconnectGate

    /**
     * Sticky armed for [stickyGen]. WS reconnect loop MUST NOT open a
     * fresh session. Long-poll continues running independently.
     */
    data class Quiesced(val stickyGen: Int) : WsReconnectGate

    /**
     * Rewalk transaction committed; a single-use probe is available for
     * claim. The probe is NOT yet bound to a specific reconnect-loop
     * owner — owner binding happens at claim time (see [ProbeClaimed]).
     *
     * @property stickyGen the sticky generation this probe is unlocking.
     * @property routeEpoch monotonic counter incremented by
     *   [RestStateMachine.beginRouteChange]. Older epochs cannot claim
     *   this probe.
     * @property token opaque single-claim ticket from [tokenSource].
     *   Redacted in [toString] via [ProbeToken].
     * @property budget attempt + wall-clock ceiling for pre-Connected
     *   work.
     */
    data class ProbeAvailable(
        val stickyGen: Int,
        val routeEpoch: Long,
        val token: ProbeToken,
        val budget: ProbeBudget,
        /**
         * Generation floor: claimers with
         * `ownerGeneration <= generationFloor` are rejected. Set at
         * issue time to the state-machine's then-current
         * `connectionGenerationCounter` — so only loops allocated
         * AFTER the probe was issued may claim it. Defends against an
         * old loop racing in on a fresh probe issued for the new
         * generation.
         */
        val generationFloor: Long,
    ) : WsReconnectGate {
        override fun toString(): String =
            "ProbeAvailable(stickyGen=$stickyGen, routeEpoch=$routeEpoch, token=$token, " +
                "budget=$budget, generationFloor=$generationFloor)"
    }

    /**
     * Atomic transition from [ProbeAvailable] via
     * [RestStateMachine.awaitAndClaimProbe]. The [ownerGeneration] is
     * captured at claim time from the calling transport's
     * `connectionGeneration` — NOT at probe issue. Defends against:
     *
     *   - Old transport generation observing a [ProbeAvailable] issued for
     *     a newer routeEpoch and trying to consume it. The
     *     [RestStateMachine.awaitAndClaimProbe] check rejects owner /
     *     epoch mismatches.
     *   - Two concurrent reconnect-loops both observing [ProbeAvailable].
     *     The claim runs under an internal mutex; exactly one wins.
     *
     * Auth + handshake may retry with normal exponential backoff while
     * [ProbeClaimed] is the current gate, up to [budget].
     */
    data class ProbeClaimed(
        val stickyGen: Int,
        val routeEpoch: Long,
        val token: ProbeToken,
        val ownerGeneration: Long,
        val budget: ProbeBudget,
    ) : WsReconnectGate {
        override fun toString(): String =
            "ProbeClaimed(stickyGen=$stickyGen, routeEpoch=$routeEpoch, token=$token, " +
                "ownerGeneration=$ownerGeneration, budget=$budget)"
    }

    /**
     * Handshake succeeded; WS session opened with [sessionEpoch]. The
     * R3.6 `sticky_recovery_started` event has fired (no change). New
     * WS connections forbidden until either:
     *
     *   - `sticky_cleared proof=ws_alive_60s` → gate flips to [Open];
     *   - candidate session dies (matching [sessionEpoch]) → gate flips
     *     back to [Quiesced]; sticky stays armed; R3.6
     *     `sticky_recovery_failed` fires (existing path);
     *   - new route change → [Quiesced] then new [ProbeAvailable].
     *
     * **Type-level invariant:** [CandidateProving] HAS NO `token` field.
     * Once the probe converts to a connected session, the one-shot token
     * is consumed and erased. A gate observer that reads
     * [CandidateProving] cannot retrieve the token by any means; the
     * sealed-class compiler check pins this.
     *
     * The [ProbeBudget] does NOT apply here. The 60-second R3.6
     * probation runs on its own clock and is unrelated to the
     * pre-Connected attempt budget.
     */
    data class CandidateProving(
        val stickyGen: Int,
        val sessionEpoch: Long,
    ) : WsReconnectGate
}

/**
 * Opaque single-claim recovery ticket. The underlying [value] is held
 * for equality checks (atomic claim, owner validation) but is REDACTED
 * in [toString] so no log line, debugger frame, or `data class` auto-
 * derived `toString()` carrier can leak it. The lock "token NEVER
 * logged" is enforced at the type level by this wrapper.
 */
@JvmInline
value class ProbeToken(val value: Long) {
    override fun toString(): String = "[REDACTED]"
}

/**
 * Attempt + wall-clock ceiling for pre-Connected reconnect work under
 * [WsReconnectGate.ProbeClaimed]. Locked at 5 attempts / 120 s; the
 * 60-second R3.6 probation that follows a successful Connected is
 * separate.
 */
data class ProbeBudget(
    val budgetStartedAtMs: Long,
    val maxAttempts: Int,
    val maxElapsedMs: Long,
) {
    companion object {
        const val MAX_ATTEMPTS_LOCKED = 5
        const val MAX_ELAPSED_MS_LOCKED = 120_000L
    }
}

/**
 * Permit returned by [RestStateMachine.awaitReconnectPermit]. Either the
 * gate was [WsReconnectGate.Open] at observation, in which case the loop
 * may dial as usual, OR the loop atomically claimed a probe.
 */
sealed interface WsReconnectPermit {
    /**
     * Gate was [WsReconnectGate.Open] at observation time. Validation
     * (`validatePermitAfterAuth`) requires that the gate is STILL Open
     * AND the state-machine `routeEpoch` AND `connectionGenerationCounter`
     * have not advanced since this permit was issued. Otherwise:
     *
     *   - a concurrent sticky-arm has flipped the gate to Quiesced;
     *   - or a concurrent rewalk has rolled `routeEpoch`;
     *   - or a newer loop has allocated a fresh `ownerGeneration`,
     *     making this caller no longer the single live reconnect loop;
     *
     * any of which means this OpenPermit is stale and must NOT proceed
     * to a `webSocket(...)` dial. The caller MUST abort and re-permit
     * at the loop top.
     */
    data class OpenPermit(
        val routeEpoch: Long,
        val ownerGeneration: Long,
    ) : WsReconnectPermit

    /** Atomic claim of a [WsReconnectGate.ProbeAvailable]. */
    data class ClaimedProbe(
        val stickyGen: Int,
        val routeEpoch: Long,
        val token: ProbeToken,
        val ownerGeneration: Long,
        val budget: ProbeBudget,
    ) : WsReconnectPermit {
        override fun toString(): String =
            "ClaimedProbe(stickyGen=$stickyGen, routeEpoch=$routeEpoch, token=$token, " +
                "ownerGeneration=$ownerGeneration, budget=$budget)"
    }

    /**
     * P1 (seventh round): the caller's loop has been retired because
     * its [ownerGeneration] has been superseded by a newer allocation
     * OR is below the current probe's generation floor. The reconnect-
     * loop MUST exit cleanly on this permit kind — re-entering
     * [RestStateMachine.awaitReconnectPermit] in a tight loop would
     * spin against [WsReconnectGate.ProbeAvailable] forever (gate
     * doesn't change because the old loop cannot claim).
     */
    data class LoopRetired(val reason: String) : WsReconnectPermit
}

/**
 * Result of [RestStateMachine.awaitAndClaimProbe]. Either a successful
 * atomic claim or a typed failure reason.
 */
sealed interface ClaimResult {
    data class Claimed(val probe: WsReconnectPermit.ClaimedProbe) : ClaimResult
    data class Failure(val reason: ClaimFailureReason) : ClaimResult
}

enum class ClaimFailureReason {
    /** Gate was Open / Quiesced / CandidateProving at claim time. */
    GATE_NOT_PROBE_AVAILABLE,
    /** Probe was for a newer routeEpoch than the caller. */
    ROUTE_EPOCH_STALE,
    /** Another caller won the mutex race. */
    PROBE_ALREADY_CLAIMED,
    /**
     * Caller's `ownerGeneration` is ≤ the probe's `generationFloor` —
     * the caller is an old loop trying to consume a probe meant for a
     * fresher generation.
     */
    OWNER_GENERATION_STALE,
}

/**
 * Result of [RestStateMachine.issueProbeAfterRewalk]. The coordinator
 * needs to know whether the probe was actually issued before calling
 * `requestServiceRestart` — fire-and-forget would be wrong here.
 *
 * The [ProbeIssued.token] field exists so the coordinator can correlate
 * the issued probe with its own bookkeeping; the [ProbeToken.toString]
 * redacts the underlying value so a stray log statement cannot leak it.
 */
sealed interface ProbeIssueResult {
    data class ProbeIssued(val token: ProbeToken) : ProbeIssueResult {
        override fun toString(): String = "ProbeIssued(token=$token)"
    }
    data class Rejected(val reason: ProbeIssueRejectReason) : ProbeIssueResult
}

/**
 * Single-token kind label used by telemetry so [WsReconnectGate]
 * subclasses can be logged WITHOUT leaking opaque token values via
 * their `toString()` rendering.
 */
fun WsReconnectGate.simpleKind(): String = when (this) {
    is WsReconnectGate.Open -> "Open"
    is WsReconnectGate.Quiesced -> "Quiesced"
    is WsReconnectGate.ProbeAvailable -> "ProbeAvailable"
    is WsReconnectGate.ProbeClaimed -> "ProbeClaimed"
    is WsReconnectGate.CandidateProving -> "CandidateProving"
}

enum class ProbeIssueRejectReason {
    /** Gate was Open / ProbeAvailable / ProbeClaimed / CandidateProving. */
    GATE_NOT_QUIESCED,
    /** A newer routeEpoch has been issued; this caller lost the race. */
    ROUTE_EPOCH_STALE,
    /** Another rewalk concurrently revoked this routeEpoch. */
    REVOKED_BY_CONCURRENT_PATH,
}
