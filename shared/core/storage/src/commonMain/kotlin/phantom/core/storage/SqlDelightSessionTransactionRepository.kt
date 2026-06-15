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
                // released between then and now. Drop the candidate
                // state; the caller treats this as a soft failure
                // and falls through to the existing hold path. The
                // active ratchet_state row is byte-identical to its
                // pre-call content.
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
}
