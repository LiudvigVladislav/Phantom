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
            id                       = record.id,
            username                 = record.username,
            public_key_hex           = record.publicKeyHex,
            dh_private_key_hex       = record.dhPrivateKeyHex,
            created_at               = record.createdAt,
            // The schema column is NOT NULL DEFAULT '' (12.sqm). On the
            // Kotlin side we treat empty string as "not yet backfilled"
            // and surface that as null on IdentityRecord. orEmpty() here
            // closes the round-trip so a write with null produces an
            // Alpha-1-shaped row that subsequent reads detect as
            // needsSigningKeyBackfill = true.
            signing_public_key_hex   = record.signingPublicKeyHex.orEmpty(),
            signing_private_key_hex  = record.signingPrivateKeyHex.orEmpty(),
        )
    }

    override suspend fun deleteIdentity(): Unit = withContext(Dispatchers.IO) {
        db.identityQueries.deleteIdentity()
    }

    private fun Identity.toRecord() = IdentityRecord(
        id                   = id,
        username             = username,
        publicKeyHex         = public_key_hex,
        dhPrivateKeyHex      = dh_private_key_hex,
        createdAt            = created_at,
        // SQL-side empty string → Kotlin null. This is what makes
        // IdentityRecord.needsSigningKeyBackfill return true for both
        // a freshly-migrated Alpha 1 row (NOT NULL DEFAULT '') and a
        // hypothetically-malformed write that ended up with empty
        // strings. The migration path treats both the same way.
        signingPublicKeyHex  = signing_public_key_hex.takeIf { it.isNotEmpty() },
        signingPrivateKeyHex = signing_private_key_hex.takeIf { it.isNotEmpty() },
    )
}
