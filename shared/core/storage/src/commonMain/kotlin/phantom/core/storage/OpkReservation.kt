// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

/**
 * One row of the `opk_reservation` table (Sprint 2b-B L5). Pins a
 * single OPK in flight for a single inbound bootstrap derivation
 * tied to one envelope_id + conversation_id pair.
 *
 * Row lifecycle (Sprint 2b L4 three-phase protocol):
 *  - Created by [OpkReservationRepository.reserve] BEFORE the X3DH
 *    4-DH derivation runs.
 *  - Released by [OpkReservationRepository.release] on candidate-
 *    decrypt failure OR on Sprint 2b L7 cap-8 eviction; the
 *    associated `local_one_time_pre_key` row is **preserved** in
 *    both cases.
 *  - Deleted atomically alongside the `local_one_time_pre_key` row
 *    and the `pending_ratchet_state` row at Sprint 2b-C
 *    pending->active promotion — the SOLE site where the OPK is
 *    permanently consumed.
 *  - Swept on process startup by [OpkReservationRepository.sweepOrphanReservations]
 *    if older than the threshold AND no matching `pending_ratchet_state`
 *    row exists (the L6 join-based semantics).
 */
data class OpkReservation(
    val opkKeyIdHex: String,
    val envelopeId: String,
    val conversationId: String,
    val reservedAtMs: Long,
)

/**
 * Outcome of [OpkReservationRepository.reserve].
 *
 * `INSERT OR IGNORE` semantics: an existing reservation for the same
 * `opk_key_id_hex` (e.g. a crash-recovered mid-derive carcass that
 * the L6 startup sweep happened to leave in place because the
 * threshold had not yet elapsed) causes the insert to no-op. The
 * caller distinguishes the two outcomes for diagnostics — both
 * outcomes mean "the reservation is now in place; safe to derive."
 */
sealed class ReservationOutcome {
    /** A fresh reservation row was created. The common case. */
    data object Created : ReservationOutcome()

    /**
     * A reservation already existed for this `opk_key_id_hex`. The
     * insert was a no-op; the existing row is returned for log
     * correlation.
     */
    data class AlreadyReserved(val existing: OpkReservation) : ReservationOutcome()
}

/**
 * Storage interface for the Sprint 2b L5 `opk_reservation` table.
 *
 * The pre-Sprint-2b lifecycle deleted the `local_one_time_pre_key`
 * row eagerly inside `SessionManager.recipientBootstrapInMemory`
 * (line 357), which made a mid-derive crash unrecoverable: the OPK
 * was gone and any later inbound envelope referencing the same
 * `opk_key_id_hex` produced `OpkNotFound`.
 *
 * Sprint 2b L4 replaces that eager-delete with a reservation: the
 * OPK row stays, a reservation row is created, derivation runs, and
 * the reservation is either kept (on success — OPK consumption is
 * deferred to pending->active promotion in Sprint 2b-C) or released
 * (on failure). The L6 startup sweep mops up reservations whose
 * derivation crashed mid-flight, leaving the OPK row intact.
 *
 * See [`docs/tracks/sprint-2b-opk-pending-session-scope.md`](../../../../../../docs/tracks/sprint-2b-opk-pending-session-scope.md)
 * L4 / L5 / L6 / L7 + ADR-029 for the full state machine.
 */
interface OpkReservationRepository {

    /**
     * L4 phase 1 — RESERVE an OPK for the given envelope. Idempotent
     * under `INSERT OR IGNORE`: a pre-existing reservation for the
     * same `opkKeyIdHex` yields [ReservationOutcome.AlreadyReserved]
     * with the existing row attached for log correlation.
     *
     * Does NOT touch `local_one_time_pre_key`. Does NOT touch
     * `pending_ratchet_state`. Single-table atomic insert.
     */
    suspend fun reserve(
        opkKeyIdHex: String,
        envelopeId: String,
        conversationId: String,
        nowMs: Long,
    ): ReservationOutcome

    /**
     * L4 phase 3 failure path + L7 cap-8 eviction path — RELEASE a
     * reservation. The associated `local_one_time_pre_key` row is
     * **preserved**; the OPK returns to the pool for future use.
     * Idempotent (no-op if no row exists).
     */
    suspend fun release(opkKeyIdHex: String)

    suspend fun get(opkKeyIdHex: String): OpkReservation?

    /**
     * L7 cap-8 eviction support — given a `conversation_id` (returned
     * by [PendingRatchetStateRepository.getOldestConversationId]),
     * resolve the matching reservation so the enforcer can issue
     * [release] before deleting the pending row in the same logical
     * step. Returns null when no reservation exists for that
     * conversation (e.g. it was already released by an L6 sweep).
     */
    suspend fun getByConversationId(conversationId: String): OpkReservation?

    suspend fun getAll(): List<OpkReservation>

    suspend fun count(): Int

    /**
     * L6 startup sweep — join-based.
     *
     * For each `opk_reservation` row with `reserved_at_ms < thresholdMs`,
     * check whether a `pending_ratchet_state` row exists with the same
     * `conversation_id`. If NO matching pending row exists, the
     * reservation is an orphan (a mid-derive crash carcass) and is
     * DELETED. If a matching pending row exists, the reservation is
     * part of a live pending candidate (a state-(b) row per the L6
     * lock) and is SKIPPED regardless of age — eviction of stale
     * pending candidates is the cap-8 LRU's responsibility, not this
     * sweep's.
     *
     * The threshold lifetime cap is 5 minutes per the L6 lock
     * (well above expected derivation latency, well below any
     * user-facing reconnect cadence). Returns the count of
     * reservations that were swept.
     */
    suspend fun sweepOrphanReservations(thresholdMs: Long): Int

    /**
     * Wipe every reservation row. Used by the Alpha 1 → Alpha 2
     * migration path and by test fixtures. NOT a runtime operation.
     */
    suspend fun deleteAll()
}

/**
 * No-op default for [OpkReservationRepository] consumers that do not
 * track reservations (legacy unit-test fixtures + the
 * [phantom.core.messaging.SessionManager] no-arg constructor default
 * preserved for source-compat with pre-Sprint-2b-B test rigs).
 *
 * Every method is a no-op: [reserve] returns a synthetic
 * [ReservationOutcome.Created], [release] / [deleteAll] do nothing,
 * the read methods return empty / null, and [sweepOrphanReservations]
 * returns 0.
 *
 * Production code wires [SqlDelightOpkReservationRepository] via
 * [phantom.android.di.AppContainer]; tests that exercise the L4 / L6
 * / L7 contracts inject a real fake. The no-op preserves the
 * pre-Sprint-2b-B "no reservation tracking" shape for tests that
 * predate the L4 wiring.
 */
object NoOpOpkReservationRepository : OpkReservationRepository {
    override suspend fun reserve(
        opkKeyIdHex: String,
        envelopeId: String,
        conversationId: String,
        nowMs: Long,
    ): ReservationOutcome = ReservationOutcome.Created

    override suspend fun release(opkKeyIdHex: String) {}
    override suspend fun get(opkKeyIdHex: String): OpkReservation? = null
    override suspend fun getByConversationId(conversationId: String): OpkReservation? = null
    override suspend fun getAll(): List<OpkReservation> = emptyList()
    override suspend fun count(): Int = 0
    override suspend fun sweepOrphanReservations(thresholdMs: Long): Int = 0
    override suspend fun deleteAll() {}
}
