// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import phantom.core.storage.PendingRatchetStateRepository
import phantom.core.storage.SessionTransactionRepository

/**
 * Sprint 2b-B L7 — caps the number of in-flight pending session
 * candidates per device + evicts the OLDEST candidate when the cap
 * is exceeded.
 *
 * The cap is a **defense-in-depth** mitigation on top of the server-
 * side rate limits in scope-doc server-contract pins #2 / #3 / #4
 * (publish 10/hour, fetch 60/min, WS message 60/min). The relay's
 * existing rate limits already bound the realistic attack rate; this
 * client-side cap bounds local memory + storage growth from a peer
 * that happens to spam pending bootstraps within those rate envelopes.
 *
 * **L4-amended eviction contract.** Sprint 2b-B amended L4 to defer
 * OPK consumption to Sprint 2b-C pending->active promotion. Under
 * that amendment, an evicted pending candidate's reservation is
 * still in place, so eviction releases the reservation — the
 * `local_one_time_pre_key` row is preserved and the OPK returns to
 * the pool for future bootstrap candidates. Pre-amendment L4
 * (consume at decrypt success) would have made this contradiction
 * physical: the reservation would already be gone, the OPK row
 * would already be gone, and "rollback to reserve" would not exist.
 *
 * The cap value `8` is an Alpha-locked design choice, NOT a derived
 * constant. It is retunable post-merge based on field telemetry per
 * scope-doc L7. Cells M-2bB-6 + M-2bB-7 pin the enforcement contract.
 *
 * See [`docs/tracks/sprint-2b-opk-pending-session-scope.md`](../../../../../../docs/tracks/sprint-2b-opk-pending-session-scope.md)
 * L7 + ADR-029 for the full state machine.
 */
class PendingSessionCapEnforcer(
    private val pendingRatchetStateRepository: PendingRatchetStateRepository,
    private val sessionTransactionRepository: SessionTransactionRepository,
) {

    /**
     * Enforce the cap after a successful [phantom.core.storage.SessionTransactionRepository.commitBootstrap].
     *
     * Behavior:
     *  - Counts pending rows; if `count <= PENDING_SESSION_CANDIDATE_CAP`
     *    returns 0 (the common case).
     *  - On overflow, walks the LRU until the count is at or under the
     *    cap, evicting one pending row per iteration via
     *    [SessionTransactionRepository.evictPendingCandidate] — a
     *    single SQLDelight cross-table transaction that releases the
     *    `opk_reservation` row + deletes the `pending_ratchet_state`
     *    row atomically. The `local_one_time_pre_key` row is preserved
     *    in the same transaction (the OPK returns to the pool because
     *    L4 defers consumption to Sprint 2b-C promotion).
     *
     * Returns the number of pending rows evicted in this call.
     *
     * PR #316 review P1-2 (2026-06-15): the pre-fix shape made two
     * independent repository calls (release reservation, then delete
     * pending). A crash between them could leave a pending row
     * whose reservation had already been released — silently
     * orphaned because the L6 sweep's join semantics treat the
     * pending row as live. The atomic
     * [SessionTransactionRepository.evictPendingCandidate] closes
     * this gap.
     */
    suspend fun enforce(): Int {
        var evicted = 0
        while (pendingRatchetStateRepository.count() > PENDING_SESSION_CANDIDATE_CAP) {
            val oldest = pendingRatchetStateRepository.getOldestConversationId() ?: break
            sessionTransactionRepository.evictPendingCandidate(oldest.conversationId)
            evicted++
        }
        return evicted
    }

    companion object {
        /**
         * Default cap on in-flight pending session candidates.
         *
         * Per scope-doc L7: "The default value `8` is an Alpha-locked
         * design choice, NOT a derived constant. It is retunable
         * post-merge based on field telemetry. The lock is on the
         * existence of the cap, not on the specific value." Cells
         * M-2bB-6 + M-2bB-7 pin enforcement behaviour; if telemetry
         * shows a different value is appropriate, this constant
         * changes alone without any contract amendment.
         */
        const val PENDING_SESSION_CANDIDATE_CAP: Int = 8
    }
}
