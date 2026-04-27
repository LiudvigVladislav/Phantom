// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

class SqlDelightRatchetStateRepository(
    private val db: PhantomDatabase,
) : RatchetStateRepository {

    override suspend fun getRatchetState(conversationId: String): String? =
        withContext(Dispatchers.IO) {
            db.ratchetStateQueries.getRatchetState(conversationId).executeAsOneOrNull()
        }

    override suspend fun upsertRatchetState(conversationId: String, stateBlob: String): Unit =
        withContext(Dispatchers.IO) {
            db.ratchetStateQueries.upsertRatchetState(
                conversation_id = conversationId,
                state_blob = stateBlob,
            )
        }

    override suspend fun deleteRatchetState(conversationId: String): Unit =
        withContext(Dispatchers.IO) {
            db.ratchetStateQueries.deleteRatchetState(conversationId)
        }
}
