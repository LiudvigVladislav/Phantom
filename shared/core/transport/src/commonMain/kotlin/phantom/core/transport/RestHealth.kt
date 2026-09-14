// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/** Why [RestHealth.usable] has the value it has. */
enum class RestHealthReason {
    /** No authenticated poll has answered yet (or the orchestrator restarted). */
    Unknown,

    /** An authenticated `/relay/poll` answered `2xx` on the current network generation. */
    PollOk,

    /** No `2xx` poll within [RestFallbackOrchestrator.restHealthWindowMs] of the last one. */
    Expired,

    /** The poll breaker is open; nothing is being asked. */
    BreakerOpen,

    /** The breaker is half-open: one probe is allowed, nothing is proven until it answers `2xx`. */
    HalfOpen,

    /** A network change invalidated every result produced on the old network. */
    NetworkChanged,

    /** The egress policy refuses direct REST (Private / Ghost). */
    EgressBlocked,

    /** The orchestrator is stopped or closed. */
    Stopped,
}

/**
 * Stage 2 B8 (2026-09-13): whether REST is a usable path right now, for
 * presentation only.
 *
 * The ONLY positive source is an authenticated `/relay/poll` response
 * classified `Ok200` -- including an empty body (product decision: an
 * empty authenticated poll `2xx` is usable REST). `/relay/ack-deliver`
 * and `/relay/send` do not count. A `2xx` counts only if the poll was
 * dispatched under the current [networkGeneration]; a poll that started
 * on the old network cannot prove the new one. Health expires on its own
 * after one full failed cycle plus the next successful poll could have
 * completed, so it can never stay `usable` on a dead link.
 *
 * Routing never reads this value; the state machine never reads it. It
 * feeds `ConnectionUiState` and the recovery coordinator's vacuum
 * predicate (B7a: through this value only, never through the egress
 * gate).
 */
data class RestHealth(
    val usable: Boolean,
    val lastPollOkAtMs: Long?,
    val networkGeneration: Long,
    val reason: RestHealthReason,
) {
    companion object {
        val UNKNOWN: RestHealth = RestHealth(
            usable = false,
            lastPollOkAtMs = null,
            networkGeneration = 0L,
            reason = RestHealthReason.Unknown,
        )
    }
}
