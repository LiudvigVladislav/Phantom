// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

/**
 * SqlDelight-backed [PendingRatchetStateRepository] (Sprint 2b-B L3).
 *
 * Wraps the `state_blob` TEXT column through [RatchetStateStorageCodec]
 * so pending rows enjoy the same authenticated-encryption envelope as
 * active `ratchet_state` rows. Per the L3 lock the same Keystore alias
 * `phantom_ratchet_wrap_v1` is used — identical threat model, no
 * benefit from a separate alias at Alpha.
 *
 * `bootstrap_artifacts_blob` is stored verbatim: it is an opaque
 * messaging-layer JSON blob (Sprint 2b-C `BootstrapArtifacts`) and the
 * storage layer does not parse it. Sprint 2b-B production callers
 * pass NULL.
 *
 * Pass [IdentityCipher] (the default) in unit tests and on non-Android
 * targets. Production code passes the Android Keystore-backed cipher
 * from [AppContainer] — the same instance the active
 * [SqlDelightRatchetStateRepository] receives, per the L3 alias-reuse
 * lock.
 */
class SqlDelightPendingRatchetStateRepository(
    private val db: PhantomDatabase,
    private val blobCipher: KeystoreBlobCipher = IdentityCipher,
) : PendingRatchetStateRepository {

    override suspend fun get(conversationId: String): PendingRatchetStateEntity? =
        withContext(Dispatchers.IO) {
            db.pendingRatchetStateQueries.getByConversationId(conversationId)
                .executeAsOneOrNull()
                ?.let {
                    PendingRatchetStateEntity(
                        conversationId         = conversationId,
                        stateBlob              = RatchetStateStorageCodec.decodeFromStorage(it.state_blob, blobCipher),
                        reservedAtMs           = it.reserved_at_ms,
                        bootstrapArtifactsBlob = it.bootstrap_artifacts_blob,
                    )
                }
        }

    override suspend fun upsert(
        conversationId: String,
        stateBlob: String,
        reservedAtMs: Long,
        bootstrapArtifactsBlob: String?,
    ): Unit = withContext(Dispatchers.IO) {
        db.pendingRatchetStateQueries.upsert(
            conversation_id          = conversationId,
            state_blob               = RatchetStateStorageCodec.encodeForStorage(stateBlob, blobCipher),
            reserved_at_ms           = reservedAtMs,
            bootstrap_artifacts_blob = bootstrapArtifactsBlob,
        )
    }

    override suspend fun delete(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            db.pendingRatchetStateQueries.deleteByConversationId(conversationId)
        }

    override suspend fun getAll(): List<PendingRatchetStateEntity> =
        withContext(Dispatchers.IO) {
            db.pendingRatchetStateQueries.getAll().executeAsList().map {
                PendingRatchetStateEntity(
                    conversationId         = it.conversation_id,
                    stateBlob              = RatchetStateStorageCodec.decodeFromStorage(it.state_blob, blobCipher),
                    reservedAtMs           = it.reserved_at_ms,
                    bootstrapArtifactsBlob = it.bootstrap_artifacts_blob,
                )
            }
        }

    override suspend fun count(): Int = withContext(Dispatchers.IO) {
        db.pendingRatchetStateQueries.countAll().executeAsOne().toInt()
    }

    override suspend fun getOldestConversationId(): OldestPendingPointer? =
        withContext(Dispatchers.IO) {
            db.pendingRatchetStateQueries.getOldestConversationId()
                .executeAsOneOrNull()
                ?.let { OldestPendingPointer(it.conversation_id, it.reserved_at_ms) }
        }

    override suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        db.pendingRatchetStateQueries.deleteAll()
    }
}
