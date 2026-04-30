// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

class SqlDelightLocalOneTimePreKeyRepository(
    private val db: PhantomDatabase,
) : LocalOneTimePreKeyRepository {

    override suspend fun get(keyIdHex: String): LocalOneTimePreKeyEntity? =
        withContext(Dispatchers.IO) {
            db.localOneTimePreKeyQueries.getByKeyId(keyIdHex).executeAsOneOrNull()?.toEntity()
        }

    override suspend fun getAll(): List<LocalOneTimePreKeyEntity> =
        withContext(Dispatchers.IO) {
            db.localOneTimePreKeyQueries.getAll().executeAsList().map { it.toEntity() }
        }

    override suspend fun count(): Int = withContext(Dispatchers.IO) {
        db.localOneTimePreKeyQueries.countAll().executeAsOne().toInt()
    }

    override suspend fun insert(entity: LocalOneTimePreKeyEntity): Unit =
        withContext(Dispatchers.IO) {
            db.localOneTimePreKeyQueries.insert(
                key_id_hex       = entity.keyIdHex,
                public_key_hex   = entity.publicKeyHex,
                private_key_hex  = entity.privateKeyHex,
                uploaded_at_ms   = entity.uploadedAtMs,
            )
        }

    override suspend fun insertAll(entities: List<LocalOneTimePreKeyEntity>): Unit =
        withContext(Dispatchers.IO) {
            // Single SQLDelight transaction — partial-write protection if
            // the process is killed mid-batch (lifecycle generates 100
            // OPKs at once on replenish).
            db.localOneTimePreKeyQueries.transaction {
                entities.forEach { e ->
                    db.localOneTimePreKeyQueries.insert(
                        key_id_hex       = e.keyIdHex,
                        public_key_hex   = e.publicKeyHex,
                        private_key_hex  = e.privateKeyHex,
                        uploaded_at_ms   = e.uploadedAtMs,
                    )
                }
            }
        }

    override suspend fun deleteByKeyId(keyIdHex: String): Unit =
        withContext(Dispatchers.IO) {
            db.localOneTimePreKeyQueries.deleteByKeyId(keyIdHex)
        }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        db.localOneTimePreKeyQueries.clearAll()
    }

    private fun Local_one_time_pre_key.toEntity() = LocalOneTimePreKeyEntity(
        keyIdHex      = key_id_hex,
        publicKeyHex  = public_key_hex,
        privateKeyHex = private_key_hex,
        uploadedAtMs  = uploaded_at_ms,
    )
}
