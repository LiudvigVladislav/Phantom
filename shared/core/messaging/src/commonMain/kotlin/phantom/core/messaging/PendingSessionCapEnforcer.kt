// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import phantom.core.storage.OpkReservationRepository
import phantom.core.storage.PendingRatchetStateRepository

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
    private val opkReservationRepository: OpkReservationRepository,
) {

    /**
     * Enforce the cap after a successful [phantom.core.storage.SessionTransactionRepository.commitBootstrap].
     *
     * Behavior:
     *  - Counts pending rows; if `count <= PENDING_SESSION_CANDIDATE_CAP`
     *    returns 0 (the common case).
     *  - On overflow, walks the LRU until the count is at or under the
     *    cap, evicting one pending row per iteration. Each eviction:
     *      - Resolves the `opk_reservation` row that backs the evicted
     *        pending candidate via [OpkReservationRepository.getByConversationId].
     *      - Releases the reservation. The `local_one_time_pre_key`
     *        row is preserved (the OPK was never consumed because L4
     *        defers consumption to promotion).
     *      - Deletes the pending row via
     *        [PendingRatchetStateRepository.delete].
     *
     * Returns the number of pending rows evicted in this call.
     */
    suspend fun enforce(): Int {
        var evicted = 0
        while (pendingRatchetStateRepository.count() > PENDING_SESSION_CANDIDATE_CAP) {
            val oldest = pendingRatchetStateRepository.getOldestConversationId() ?: break
            // Resolve and release the matching reservation BEFORE
            // deleting the pending row. Order is intentional: a
            // concurrent L6 sweep would skip this reservation while
            // the pending row still exists (the L6 join semantics);
            // releasing first widens the no-op window for that sweep
            // by an immeasurable amount. Either order is correct under
            // the L4 deferred-consume contract.
            val reservation = opkReservationRepository.getByConversationId(oldest.conversationId)
            if (reservation != null) {
                opkReservationRepository.release(reservation.opkKeyIdHex)
            }
            pendingRatchetStateRepository.delete(oldest.conversationId)
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
