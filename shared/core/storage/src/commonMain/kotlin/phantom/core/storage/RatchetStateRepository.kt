// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

interface RatchetStateRepository {
    /** Returns the serialized RatchetState JSON for the given conversation, or null if absent. */
    suspend fun getRatchetState(conversationId: String): String?

    /** Inserts or replaces the serialized RatchetState JSON for the given conversation. */
    suspend fun upsertRatchetState(conversationId: String, stateBlob: String)

    suspend fun deleteRatchetState(conversationId: String)
}
