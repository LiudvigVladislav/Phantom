// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

/**
 * SqlDelight-backed [SessionTransactionRepository] (Sprint 2b-B L4).
 *
 * Each method opens a single SQLDelight transaction so a process
 * kill mid-operation cannot leave the active / pending / reservation /
 * local OPK rows out of sync.
 *
 * Pass [IdentityCipher] (the default) in unit tests; production code
 * passes the same Keystore-backed cipher the active
 * [SqlDelightRatchetStateRepository] receives, per the L3 alias-reuse
 * lock (`phantom_ratchet_wrap_v1`).
 */
class SqlDelightSessionTransactionRepository(
    private val db: PhantomDatabase,
    private val blobCipher: KeystoreBlobCipher = IdentityCipher,
) : SessionTransactionRepository {

    override suspend fun commitBootstrap(
        opkKeyIdHex: String,
        conversationId: String,
        stateBlob: String,
        bootstrapArtifactsBlob: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        // Single SQLDelight transaction — atomic against a concurrent
        // release of the reservation (e.g. an L7 cap eviction or L6
        // sweep that fires between L4 phase 1 reserve and L4 phase 3
        // commit). The transaction reads the reservation, decides
        // whether to write the pending row, and writes (or skips)
        // before releasing the lock.
        db.transactionWithResult {
            val reservation = db.opkReservationQueries
                .getByOpkKeyId(opkKeyIdHex)
                .executeAsOneOrNull()
            if (reservation == null) {
                // No reservation → false. The orphan-reservation
                // cleanup below would have no `opkKeyIdHex` to spare
                // anyway; skip it.
                // The reservation we set in L4 phase 1 has been
                // released between then and now (e.g. an L7 cap
                // eviction or L6 sweep raced with this callback).
                // Drop the candidate from the pending slot; return
                // false to the caller.
                //
                // PR #316 review P2-1 (2026-06-15): we do NOT touch
                // `ratchet_state` at all on either branch of this
                // transaction. Under the current Sprint 2b-B DMS:2569
                // wiring the caller has ALREADY written the advanced
                // state to `ratchet_state` via `saveSession` BEFORE
                // invoking `commitBootstrap`, so a false return here
                // leaves the active row holding whatever `saveSession`
                // wrote — typically the advanced state. The DMS
                // caller logs `inbound_repair_commit_skip` and still
                // emits `inbound_repair_ok`; the decrypted plaintext
                // is returned to the user. The only observable effect
                // of false is that Sprint 2b-C will not find a
                // pending row for this conversation until the next
                // inbound envelope re-derives. See
                // [SessionTransactionRepository.commitBootstrap] KDoc
                // for the binding contract.
                return@transactionWithResult false
            }
            // PR #317 review P1-1 (2026-06-15) — cleanup-on-commit.
            // Before overwriting the pending row for this conversation
            // (INSERT OR REPLACE), release ANY prior reservation for
            // this conversation whose `opk_key_id_hex` is NOT the one
            // we're about to commit. Such priors are orphan: their
            // pending row is about to be overwritten with the new
            // (opkKeyIdHex, advancedState) pair; the L6 sweep would
            // not pick them up because pending_ratchet_state.conversation_id
            // still matches.
            //
            // After this loop runs, the invariant "at most one
            // opk_reservation row per (conversation_id) AND that row's
            // opk_key_id_hex equals pending_ratchet_state's bootstrap-
            // backing OPK" holds. Both promotePendingToActive's and
            // evictPendingCandidate's `getByConversationId(...).executeAsOneOrNull()`
            // calls are therefore well-defined.
            val priorReservations = db.opkReservationQueries
                .getByConversationId(conversationId)
                .executeAsList()
                .filter { it.opk_key_id_hex != opkKeyIdHex }
            for (orphan in priorReservations) {
                db.opkReservationQueries.release(orphan.opk_key_id_hex)
            }
            // L4 reserves and pending UPSERT share the same
            // reserved_at_ms — gives the L7 LRU eviction ordering an
            // unambiguous ordering key. The blob is wrapped through
            // RatchetStateStorageCodec — the same `rs1:` + Base64
            // envelope the active ratchet_state table uses.
            db.pendingRatchetStateQueries.upsert(
                conversation_id          = conversationId,
                state_blob               = RatchetStateStorageCodec.encodeForStorage(stateBlob, blobCipher),
                reserved_at_ms           = reservation.reserved_at_ms,
                bootstrap_artifacts_blob = bootstrapArtifactsBlob,
            )
            true
        }
    }

    override suspend fun evictPendingCandidate(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            // Single SQLDelight transaction so a crash between the
            // reservation release and the pending row delete cannot
            // leave a pending row without its companion reservation.
            // PR #316 review P1-2 (2026-06-15).
            db.transaction {
                val reservation = db.opkReservationQueries
                    .getByConversationId(conversationId)
                    .executeAsOneOrNull()
                if (reservation != null) {
                    db.opkReservationQueries.release(reservation.opk_key_id_hex)
                }
                db.pendingRatchetStateQueries.deleteByConversationId(conversationId)
            }
        }

    override suspend fun commitInitiatorPending(
        conversationId: String,
        stateBlob: String,
        bootstrapArtifactsBlob: String,
        nowMs: Long,
    ): Unit = withContext(Dispatchers.IO) {
        // Single-table upsert. No race condition with cross-table
        // state because:
        //  - we do not read `opk_reservation` (outbound-initiator has
        //    none);
        //  - we do not read `ratchet_state` (outbound bootstrap no
        //    longer touches the active slot);
        //  - the upsert itself is atomic at the SQLDelight statement
        //    level.
        // See [SessionTransactionRepository.commitInitiatorPending]
        // KDoc for the binding contract.
        db.pendingRatchetStateQueries.upsert(
            conversation_id          = conversationId,
            state_blob               = RatchetStateStorageCodec.encodeForStorage(stateBlob, blobCipher),
            reserved_at_ms           = nowMs,
            bootstrap_artifacts_blob = bootstrapArtifactsBlob,
        )
    }

    override suspend fun promotePendingToActive(conversationId: String): Boolean =
        withContext(Dispatchers.IO) {
            // Single SQLDelight transaction. See
            // [SessionTransactionRepository.promotePendingToActive] KDoc
            // for the reservation-optional contract — INBOUND case
            // (reservation present) consumes the local OPK at
            // promote-time; OUTBOUND-INITIATOR case (no reservation)
            // just promotes the ratchet row, with ZERO touches to
            // `local_one_time_pre_key` (the bootstrap_artifacts_blob's
            // opkKeyId refers to the peer's pool, not ours).
            db.transactionWithResult {
                val pending = db.pendingRatchetStateQueries
                    .getByConversationId(conversationId)
                    .executeAsOneOrNull()
                    ?: return@transactionWithResult false

                // Copy the encrypted state_blob verbatim — both tables
                // share the same `rs1:` + Base64 + Keystore-wrap
                // envelope per the L3 alias-reuse lock, so a
                // decode/re-encode round-trip would be pure overhead.
                db.ratchetStateQueries.upsertRatchetState(
                    conversation_id = conversationId,
                    state_blob = pending.state_blob,
                )
                db.pendingRatchetStateQueries.deleteByConversationId(conversationId)

                val reservation = db.opkReservationQueries
                    .getByConversationId(conversationId)
                    .executeAsOneOrNull()
                if (reservation != null) {
                    // INBOUND case — the pending was created by
                    // recipientBootstrapInMemory which placed a
                    // reservation in L4 phase 1. Promote-time is the
                    // SOLE site where the OPK is permanently
                    // consumed: delete the local row + release the
                    // reservation atomically with the ratchet
                    // promotion above.
                    db.localOneTimePreKeyQueries.deleteByKeyId(
                        reservation.opk_key_id_hex,
                    )
                    db.opkReservationQueries.release(reservation.opk_key_id_hex)
                }
                // OUTBOUND-INITIATOR case: no reservation row means
                // no local OPK to delete. The x3dhInit.opkKeyIdHex
                // referenced in bootstrap_artifacts_blob points at
                // the peer's local pool, not ours; deleting our
                // local row by that id would corrupt our pool.
                true
            }
        }
}
