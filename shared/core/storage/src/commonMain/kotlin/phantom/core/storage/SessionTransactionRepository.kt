// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

/**
 * Cross-table atomic operations on the session lifecycle tables —
 * Sprint 2b-B L4 (commit-bootstrap) + Sprint 2b-C (pending->active
 * promotion).
 *
 * Each method runs as a single SQLDelight transaction so a process
 * kill mid-operation cannot leave the active / pending / reservation /
 * local OPK rows out of sync.
 *
 * Sprint 2b-B introduces ONLY [commitBootstrap]. The pending->active
 * promotion method is added in Sprint 2b-C alongside the runtime
 * wiring (the M-2bC-1 and M-2bC-4 cells); the interface here is left
 * deliberately narrow to keep Sprint 2b-B atomic and reviewable.
 *
 * See [`docs/tracks/sprint-2b-opk-pending-session-scope.md`](../../../../../../docs/tracks/sprint-2b-opk-pending-session-scope.md)
 * L4 + ADR-029 for the full state machine.
 */
interface SessionTransactionRepository {

    /**
     * L4 phase 3 success — commit the candidate bootstrap into the
     * pending slot.
     *
     * Atomically:
     *  - READ the `opk_reservation` row keyed by [opkKeyIdHex]; if
     *    no row exists (the reservation was released by an L7 cap
     *    eviction or L6 sweep between phase 1 and phase 3), return
     *    `false` and DO NOT write the pending row.
     *  - UPSERT the `pending_ratchet_state` row for [conversationId]
     *    with the reservation's `reserved_at_ms` (so pending and
     *    reservation share a single timestamp — a precondition for
     *    the L7 LRU eviction ordering to be unambiguous).
     *
     * Does NOT delete the `opk_reservation` row. Does NOT touch the
     * `local_one_time_pre_key` row. The amended L4 contract defers
     * OPK consumption to Sprint 2b-C pending->active promotion; at
     * the success of this call the OPK is still in the pool.
     *
     * [stateBlob] is plaintext JSON; implementations wrap it through
     * [RatchetStateStorageCodec] before persisting (same envelope as
     * the active `ratchet_state` table, reusing the same Keystore
     * alias per the L3 lock).
     *
     * [bootstrapArtifactsBlob] is the opaque messaging-layer JSON
     * (the Sprint 2b-C `BootstrapArtifacts` typed class). Sprint 2b-B
     * production callers pass NULL; Sprint 2b-C introduces non-NULL
     * writes for INITIATOR pending rows.
     *
     * @return true if the reservation was present and the pending
     *         row was upserted; false if the reservation was missing
     *         (the candidate state is dropped, the active session
     *         row is untouched, the caller falls through to the
     *         hold/fail branch). Cell M-2bB-5 pins the success path;
     *         cell M-2bB-3 indirectly covers the rare missing-
     *         reservation case via the failure path.
     */
    suspend fun commitBootstrap(
        opkKeyIdHex: String,
        conversationId: String,
        stateBlob: String,
        bootstrapArtifactsBlob: String? = null,
    ): Boolean
}
