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
}
