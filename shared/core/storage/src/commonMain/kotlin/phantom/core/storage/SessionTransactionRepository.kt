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
     * `local_one_time_pre_key` row. Does NOT read or write the active
     * `ratchet_state` row. The amended L4 contract defers OPK
     * consumption to Sprint 2b-C pending->active promotion; at the
     * success of this call the OPK is still in the pool.
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
     * **Active row interaction (PR #316 review P2-1 — 2026-06-15).**
     * This method does NOT touch `ratchet_state` at all. In the
     * current Sprint 2b-B DMS:2569 wiring the caller has already
     * written the advanced state to `ratchet_state` via
     * `sessionManager.saveSession(conversationId, advancedState)`
     * BEFORE invoking `commitBootstrap`. The dual-write is
     * intentional Sprint 2b-B-only scaffolding: the active row
     * preserves decrypt continuity for the next inbound envelope on
     * the same chain while the pending row is the seed Sprint 2b-C
     * promotion will read. A `false` return from this method
     * therefore leaves the active row in whatever state the caller's
     * prior `saveSession` left it — typically the advanced state
     * just written. The DMS caller does NOT roll the active write
     * back on a `false` here; the inbound-repair flow still emits
     * `inbound_repair_ok` and returns the decrypted plaintext (the
     * decrypt itself was a correctness win regardless of the pending
     * commit). The only observable effect of `false` is that
     * Sprint 2b-C will not find a pending row for this conversation
     * until the next inbound envelope triggers another bootstrap
     * derivation.
     *
     * @return true if the reservation was present and the pending
     *         row was upserted; false if the reservation was missing.
     *         Cell M-2bB-5 pins the success path; the race branch
     *         test `commitBootstrap_returnsFalse_whenReservationReleasedBetweenPhases`
     *         covers the false return.
     */
    suspend fun commitBootstrap(
        opkKeyIdHex: String,
        conversationId: String,
        stateBlob: String,
        bootstrapArtifactsBlob: String? = null,
    ): Boolean

    /**
     * L7 cap-8 eviction — atomic cross-table deletion. PR #316 review
     * P1-2 (2026-06-15): the pre-fix `PendingSessionCapEnforcer.enforce`
     * issued the reservation release and the pending row delete as
     * two independent repository calls, so a process kill between
     * them could leave a pending row whose `opk_reservation`
     * companion was already released. The L6 sweep would skip such
     * a row (its join semantics see the pending row and treat the
     * reservation as live), the L7 enforcer would count the row
     * against the cap, and the Sprint 2b-C promotion would later
     * fail to consume the matching OPK / reservation atomically. The
     * row would sit there forever.
     *
     * This method runs both deletes in a single SQLDelight
     * transaction so a crash mid-eviction leaves either both rows in
     * place (caller retries on the next commitBootstrap) or both
     * rows gone (the OPK row is preserved by the L4 contract — eager
     * delete would have happened only at Sprint 2b-C promotion,
     * which evicted candidates never reach).
     *
     * The implementation:
     *   1. Looks up the `opk_reservation` row by `conversation_id`
     *      (uses the [OpkReservationRepository.getByConversationId]
     *      query in production; the index
     *      `idx_opk_reservation_conversation_id` backs the lookup).
     *   2. If a reservation exists, releases it (deletes the
     *      `opk_reservation` row only — the `local_one_time_pre_key`
     *      row is preserved per the L4 deferred-consume contract).
     *   3. Deletes the `pending_ratchet_state` row.
     *
     * Idempotent: a re-call after a successful eviction is a no-op
     * (no reservation row, no pending row). Cell M-2bB-6 + M-2bB-7
     * + a new M-2bB-7-crash test pin this atomicity contract.
     */
    suspend fun evictPendingCandidate(conversationId: String)
}
