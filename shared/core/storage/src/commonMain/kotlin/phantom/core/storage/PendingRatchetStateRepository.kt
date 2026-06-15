// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

/**
 * One row of the `pending_ratchet_state` table (Sprint 2b-B L3).
 *
 * The pending slot is a candidate Double Ratchet state derived by
 * the L4 three-phase protocol, kept in a separate companion table
 * (NOT a column on the active `ratchet_state` row) so the Sprint 2a
 * outbound role guard at `DefaultMessagingService:434` — which reads
 * [RatchetStateRepository] — can NEVER accidentally see a pending row
 * when deciding whether to take the bootstrap path.
 *
 * Promotion to active happens in Sprint 2b-C (the M-2bC-1 and
 * M-2bC-4 cells), in a single SQLDelight cross-table transaction
 * that DELETES the corresponding `local_one_time_pre_key` row and
 * `opk_reservation` row, REPLACES the active `ratchet_state` row,
 * and DELETES this pending row. That is the SOLE site where the OPK
 * is permanently consumed.
 *
 * [stateBlob] holds the PLAINTEXT JSON form. The storage layer
 * wraps and unwraps it via [RatchetStateStorageCodec] (the same
 * `rs1:` + Base64 frame the active `ratchet_state` table uses,
 * reusing the existing Keystore alias `phantom_ratchet_wrap_v1`
 * per the L3 lock).
 *
 * [bootstrapArtifactsBlob] is the serialized
 * `BootstrapArtifacts` JSON (carrying the cached `X3dhInitHeader`
 * plus recipient identity hex) needed for Sprint 2b-C OUTBOUND
 * reuse of an INITIATOR pending slot. NULL for RESPONDER pending
 * rows (created by the inbound L4 path — no outbound reuse
 * needed). The blob is opaque to this layer — messaging owns the
 * typed `BootstrapArtifacts` class and the JSON serialization;
 * storage sees a TEXT column. Sprint 2b-B production callers
 * always pass NULL; Sprint 2b-C introduces non-NULL writes.
 */
data class PendingRatchetStateEntity(
    val conversationId: String,
    val stateBlob: String,
    val reservedAtMs: Long,
    val bootstrapArtifactsBlob: String?,
)

/**
 * Storage interface for the Sprint 2b-B L3 `pending_ratchet_state`
 * companion table.
 *
 * Implements the boundary between active and pending session slots:
 * a distinct table accessed via a distinct repository so no caller
 * can accidentally read a pending state while resolving an outbound
 * decision through the active [RatchetStateRepository] (M-2bB-1 +
 * M-2bB-2 cells pin this isolation invariant).
 *
 * See [`docs/tracks/sprint-2b-opk-pending-session-scope.md`](../../../../../../docs/tracks/sprint-2b-opk-pending-session-scope.md)
 * L3 + ADR-029 for the full state machine.
 */
interface PendingRatchetStateRepository {

    /**
     * Read the pending slot for the given conversation. Returns null
     * if no pending row exists.
     *
     * Implementations decrypt `state_blob` through
     * [RatchetStateStorageCodec] before returning, so the entity's
     * `stateBlob` field is always plaintext JSON.
     */
    suspend fun get(conversationId: String): PendingRatchetStateEntity?

    /**
     * L4 phase 3 success path — UPSERT the pending row. The
     * `state_blob` is the plaintext JSON; implementations wrap it
     * through [RatchetStateStorageCodec] before persisting.
     *
     * The `local_one_time_pre_key` row and the `opk_reservation` row
     * are NOT touched here. Those rows are preserved on success per
     * the amended L4 deferred-consume contract (OPK consumption
     * happens at pending->active promotion in Sprint 2b-C).
     */
    suspend fun upsert(
        conversationId: String,
        stateBlob: String,
        reservedAtMs: Long,
        bootstrapArtifactsBlob: String? = null,
    )

    /**
     * L7 cap-8 eviction path + Sprint 2b-C pending->active promotion
     * path. Deletes ONLY the pending row; the L7 enforcer separately
     * releases the corresponding [OpkReservationRepository] entry
     * (preserves the OPK row); the Sprint 2b-C promotion separately
     * deletes the OPK row and the reservation row atomically.
     */
    suspend fun delete(conversationId: String)

    suspend fun getAll(): List<PendingRatchetStateEntity>

    suspend fun count(): Int

    /**
     * L7 cap-8 enforcement — returns the oldest pending row by
     * `reserved_at_ms` (the LRU eviction target) or null if no rows
     * exist. The Sprint 2b L7 [phantom.core.messaging.PendingSessionCapEnforcer]
     * issues this query when the count exceeds
     * `PENDING_SESSION_CANDIDATE_CAP`, then issues `delete(...)` for
     * the returned conversation plus a `release(...)` on the
     * companion [OpkReservationRepository] row.
     */
    suspend fun getOldestConversationId(): OldestPendingPointer?

    /**
     * Wipe every pending row. Used by Alpha 1 → Alpha 2 migration
     * test fixtures and the [RatchetStateRepository.deleteAll]
     * companion path. NOT a runtime operation.
     */
    suspend fun deleteAll()
}

/** Pointer returned by [PendingRatchetStateRepository.getOldestConversationId]. */
data class OldestPendingPointer(
    val conversationId: String,
    val reservedAtMs: Long,
)
