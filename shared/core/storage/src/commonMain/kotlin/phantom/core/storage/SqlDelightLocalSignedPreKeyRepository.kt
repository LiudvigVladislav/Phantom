// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

class SqlDelightLocalSignedPreKeyRepository(
    private val db: PhantomDatabase,
) : LocalSignedPreKeyRepository {

    override suspend fun get(): LocalSignedPreKeyEntity? = withContext(Dispatchers.IO) {
        db.localSignedPreKeyQueries.getCurrent().executeAsOneOrNull()?.toEntity()
    }

    override suspend fun upsert(entity: LocalSignedPreKeyEntity): Unit =
        withContext(Dispatchers.IO) {
            val prev = entity.previous
            db.localSignedPreKeyQueries.upsertCurrent(
                key_id                   = entity.keyId,
                public_key_hex           = entity.publicKeyHex,
                private_key_hex          = entity.privateKeyHex,
                created_at_ms            = entity.createdAtMs,
                signature_hex            = entity.signatureHex,
                previous_key_id          = prev?.keyId,
                previous_public_key_hex  = prev?.publicKeyHex,
                previous_private_key_hex = prev?.privateKeyHex,
                previous_signature_hex   = prev?.signatureHex,
                previous_created_at_ms   = prev?.createdAtMs,
                previous_retired_at_ms   = prev?.retiredAtMs,
            )
        }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        db.localSignedPreKeyQueries.clearAll()
    }

    private fun Local_signed_pre_key.toEntity(): LocalSignedPreKeyEntity {
        // SQLDelight gives us nullable fields for `previous_*` columns. They
        // are populated as a group: if `previous_key_id` is non-null, all
        // other previous_* fields are non-null too (set together by
        // upsertCurrent when rotating). Defensive-decode the group.
        val previous = previous_key_id?.let { pid ->
            LocalSignedPreKeyEntity.PreviousSignedPreKey(
                keyId         = pid,
                publicKeyHex  = previous_public_key_hex
                    ?: error("previous_key_id set but previous_public_key_hex null"),
                privateKeyHex = previous_private_key_hex
                    ?: error("previous_key_id set but previous_private_key_hex null"),
                signatureHex  = previous_signature_hex
                    ?: error("previous_key_id set but previous_signature_hex null"),
                createdAtMs   = previous_created_at_ms
                    ?: error("previous_key_id set but previous_created_at_ms null"),
                retiredAtMs   = previous_retired_at_ms
                    ?: error("previous_key_id set but previous_retired_at_ms null"),
            )
        }
        return LocalSignedPreKeyEntity(
            keyId         = key_id,
            publicKeyHex  = public_key_hex,
            privateKeyHex = private_key_hex,
            createdAtMs   = created_at_ms,
            signatureHex  = signature_hex,
            previous      = previous,
        )
    }
}
