// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

/**
 * SqlDelight-backed [LocalSignedPreKeyRepository].
 *
 * As of F22 (see [docs/adr/ADR-023-Local-Prekey-Keystore-Wrap.md](../../../../../docs/adr/ADR-023-Local-Prekey-Keystore-Wrap.md))
 * the `private_key_hex` and `previous_private_key_hex` column values
 * are wrapped through [PrivateKeyStorageCodec] before they touch
 * SQLite. The schema column type stays `TEXT`; the value stored is
 * the codec's `v1:` + Base64 form. Reads transparently accept both
 * the new wrapped form and any legacy raw-hex value still present
 * from before the F22 PR landed (lazy migration).
 *
 * The cipher is injected so non-Android callers (today: unit tests
 * via [IdentityCipher]; tomorrow: a JVM desktop port) construct the
 * repository against an implementation that matches their platform's
 * key-storage facility.
 */
class SqlDelightLocalSignedPreKeyRepository(
    private val db: PhantomDatabase,
    private val privateKeyCipher: KeystoreBlobCipher = IdentityCipher,
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
                private_key_hex          = PrivateKeyStorageCodec.encodeForStorage(
                    entity.privateKeyHex, privateKeyCipher,
                ),
                created_at_ms            = entity.createdAtMs,
                signature_hex            = entity.signatureHex,
                previous_key_id          = prev?.keyId,
                previous_public_key_hex  = prev?.publicKeyHex,
                previous_private_key_hex = prev?.privateKeyHex?.let {
                    PrivateKeyStorageCodec.encodeForStorage(it, privateKeyCipher)
                },
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
                privateKeyHex = PrivateKeyStorageCodec.decodeFromStorage(
                    previous_private_key_hex
                        ?: error("previous_key_id set but previous_private_key_hex null"),
                    privateKeyCipher,
                ),
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
            privateKeyHex = PrivateKeyStorageCodec.decodeFromStorage(
                private_key_hex, privateKeyCipher,
            ),
            createdAtMs   = created_at_ms,
            signatureHex  = signature_hex,
            previous      = previous,
        )
    }
}
