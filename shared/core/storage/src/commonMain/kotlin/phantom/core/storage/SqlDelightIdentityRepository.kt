// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.identity.IdentityKeyPair
import phantom.core.identity.IdentityRecord
import phantom.core.storage.db.PhantomDatabase

class SqlDelightIdentityRepository(
    private val db: PhantomDatabase,
) : IdentityStorageRepository {

    override suspend fun createIdentity(username: String): IdentityKeyPair =
        throw UnsupportedOperationException(
            "Key generation is not the storage layer's responsibility. Use IdentityManager."
        )

    override suspend fun loadIdentity(): IdentityRecord? = withContext(Dispatchers.IO) {
        db.identityQueries.getIdentity().executeAsOneOrNull()?.toRecord()
    }

    override suspend fun saveIdentity(record: IdentityRecord): Unit = withContext(Dispatchers.IO) {
        db.identityQueries.insertIdentity(
            id                 = record.id,
            username           = record.username,
            public_key_hex     = record.publicKeyHex,
            dh_private_key_hex = record.dhPrivateKeyHex,
            created_at         = record.createdAt,
        )
    }

    override suspend fun deleteIdentity(): Unit = withContext(Dispatchers.IO) {
        db.identityQueries.deleteIdentity()
    }

    private fun Identity.toRecord() = IdentityRecord(
        id              = id,
        username        = username,
        publicKeyHex    = public_key_hex,
        dhPrivateKeyHex = dh_private_key_hex,
        createdAt       = created_at,
    )
}
