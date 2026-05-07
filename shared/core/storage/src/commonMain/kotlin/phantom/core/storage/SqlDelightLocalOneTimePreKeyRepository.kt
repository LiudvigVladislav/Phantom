// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

/**
 * SqlDelight-backed [LocalOneTimePreKeyRepository].
 *
 * As of F22 (see [docs/adr/ADR-023-Local-Prekey-Keystore-Wrap.md](../../../../../docs/adr/ADR-023-Local-Prekey-Keystore-Wrap.md))
 * the `private_key_hex` column value is wrapped through
 * [PrivateKeyStorageCodec] before it touches SQLite. The schema column
 * type stays `TEXT`; the value stored is the codec's `v1:` + Base64
 * form. Reads transparently accept both the new wrapped form and any
 * legacy raw-hex value still present from before the F22 PR landed
 * (lazy migration — see codec docstring).
 *
 * The cipher is injected so non-Android callers (today: unit tests
 * via [IdentityCipher]; tomorrow: a JVM desktop port) construct the
 * repository against an implementation that matches their platform's
 * key-storage facility.
 */
class SqlDelightLocalOneTimePreKeyRepository(
    private val db: PhantomDatabase,
    private val privateKeyCipher: KeystoreBlobCipher = IdentityCipher,
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
                private_key_hex  = PrivateKeyStorageCodec.encodeForStorage(
                    entity.privateKeyHex, privateKeyCipher,
                ),
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
                        private_key_hex  = PrivateKeyStorageCodec.encodeForStorage(
                            e.privateKeyHex, privateKeyCipher,
                        ),
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
        privateKeyHex = PrivateKeyStorageCodec.decodeFromStorage(
            private_key_hex, privateKeyCipher,
        ),
        uploadedAtMs  = uploaded_at_ms,
    )
}
