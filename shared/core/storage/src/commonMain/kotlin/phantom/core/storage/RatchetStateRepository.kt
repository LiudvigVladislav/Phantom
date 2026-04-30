// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

interface RatchetStateRepository {
    /** Returns the serialized RatchetState JSON for the given conversation, or null if absent. */
    suspend fun getRatchetState(conversationId: String): String?

    /** Inserts or replaces the serialized RatchetState JSON for the given conversation. */
    suspend fun upsertRatchetState(conversationId: String, stateBlob: String)

    suspend fun deleteRatchetState(conversationId: String)

    /**
     * Wipe every persisted ratchet state. Called by the Alpha 1 →
     * Alpha 2 migration after the user confirms on MigrationScreen —
     * Alpha 1 ratchet states were rooted in the F12/F15-vulnerable
     * bootstrap and must not survive into Alpha 2 sessions. Idempotent.
     */
    suspend fun deleteAll()
}
