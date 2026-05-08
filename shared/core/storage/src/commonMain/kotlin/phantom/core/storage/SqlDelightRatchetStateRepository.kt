// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

/**
 * Stores Double Ratchet session state blobs with optional
 * authenticated-encryption via [KeystoreBlobCipher].
 *
 * The on-disk encoding is delegated to [RatchetStateStorageCodec]:
 * wrapped rows carry the `rs1:` prefix; legacy plaintext rows are
 * read as-is and re-wrapped on the next upsert.
 *
 * Pass [IdentityCipher] (the default) in unit tests and environments
 * without Android Keystore. Production code passes
 * [createAndroidRatchetKeystoreCipher] from [AppContainer].
 */
class SqlDelightRatchetStateRepository(
    private val db: PhantomDatabase,
    private val blobCipher: KeystoreBlobCipher = IdentityCipher,
) : RatchetStateRepository {

    override suspend fun getRatchetState(conversationId: String): String? =
        withContext(Dispatchers.IO) {
            db.ratchetStateQueries.getRatchetState(conversationId)
                .executeAsOneOrNull()
                ?.let { RatchetStateStorageCodec.decodeFromStorage(it, blobCipher) }
        }

    override suspend fun upsertRatchetState(conversationId: String, stateBlob: String): Unit =
        withContext(Dispatchers.IO) {
            db.ratchetStateQueries.upsertRatchetState(
                conversation_id = conversationId,
                state_blob = RatchetStateStorageCodec.encodeForStorage(stateBlob, blobCipher),
            )
        }

    override suspend fun deleteRatchetState(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            db.ratchetStateQueries.deleteRatchetState(conversationId)
        }

    override suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        db.ratchetStateQueries.deleteAllRatchetStates()
    }
}
