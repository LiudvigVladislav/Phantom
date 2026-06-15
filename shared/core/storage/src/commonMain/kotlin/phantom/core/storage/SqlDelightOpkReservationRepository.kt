// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

/**
 * SqlDelight-backed [OpkReservationRepository] (Sprint 2b-B L5).
 *
 * The L6 startup sweep semantics are implemented in [sweepOrphanReservations]
 * via a join: the underlying `selectOrphansOlderThan` query already
 * filters out reservations whose `conversation_id` matches an existing
 * `pending_ratchet_state` row, so only true mid-derive orphans surface
 * for deletion. The delete loop runs inside a single SQLDelight
 * transaction so a process kill mid-sweep cannot leave the table in a
 * half-cleaned state.
 */
class SqlDelightOpkReservationRepository(
    private val db: PhantomDatabase,
) : OpkReservationRepository {

    override suspend fun reserve(
        opkKeyIdHex: String,
        envelopeId: String,
        conversationId: String,
        nowMs: Long,
    ): ReservationOutcome = withContext(Dispatchers.IO) {
        // `INSERT OR IGNORE` semantics: the underlying query is
        // INSERT OR IGNORE INTO opk_reservation(...) VALUES (?, ?, ?, ?).
        // Wrap the insert + post-check in a single transaction so a
        // concurrent reserver cannot squeeze in between the INSERT and
        // the SELECT we use to disambiguate the outcome.
        db.transactionWithResult {
            db.opkReservationQueries.reserve(
                opk_key_id_hex  = opkKeyIdHex,
                envelope_id     = envelopeId,
                conversation_id = conversationId,
                reserved_at_ms  = nowMs,
            )
            val row = db.opkReservationQueries.getByOpkKeyId(opkKeyIdHex).executeAsOne()
            if (row.envelope_id == envelopeId &&
                row.conversation_id == conversationId &&
                row.reserved_at_ms == nowMs
            ) {
                ReservationOutcome.Created
            } else {
                ReservationOutcome.AlreadyReserved(row.toEntity())
            }
        }
    }

    override suspend fun release(opkKeyIdHex: String): Unit = withContext(Dispatchers.IO) {
        db.opkReservationQueries.release(opkKeyIdHex)
    }

    override suspend fun get(opkKeyIdHex: String): OpkReservation? =
        withContext(Dispatchers.IO) {
            db.opkReservationQueries.getByOpkKeyId(opkKeyIdHex).executeAsOneOrNull()?.toEntity()
        }

    override suspend fun getByConversationId(conversationId: String): OpkReservation? =
        withContext(Dispatchers.IO) {
            db.opkReservationQueries.getByConversationId(conversationId).executeAsOneOrNull()?.toEntity()
        }

    override suspend fun getAll(): List<OpkReservation> = withContext(Dispatchers.IO) {
        db.opkReservationQueries.getAll().executeAsList().map { it.toEntity() }
    }

    override suspend fun count(): Int = withContext(Dispatchers.IO) {
        db.opkReservationQueries.countAll().executeAsOne().toInt()
    }

    override suspend fun sweepOrphanReservations(thresholdMs: Long): Int =
        withContext(Dispatchers.IO) {
            // selectOrphansOlderThan already excludes rows whose
            // conversation_id has a matching pending_ratchet_state row,
            // so this loop only ever deletes true mid-derive orphans
            // (state (a) per the L6 lock). Single transaction so a
            // process kill mid-sweep cannot leave the table half-cleaned.
            db.transactionWithResult {
                val orphans = db.opkReservationQueries
                    .selectOrphansOlderThan(thresholdMs)
                    .executeAsList()
                for (opkKeyIdHex in orphans) {
                    db.opkReservationQueries.release(opkKeyIdHex)
                }
                orphans.size
            }
        }

    override suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        db.opkReservationQueries.deleteAll()
    }

    private fun Opk_reservation.toEntity() = OpkReservation(
        opkKeyIdHex     = opk_key_id_hex,
        envelopeId      = envelope_id,
        conversationId  = conversation_id,
        reservedAtMs    = reserved_at_ms,
    )
}
