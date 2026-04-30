// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

interface SenderKeyRepository {
    suspend fun get(groupId: String, memberPubkeyHex: String): SenderKeyEntity?
    suspend fun upsert(entity: SenderKeyEntity)
    suspend fun deleteForGroup(groupId: String)

    /**
     * Wipe every persisted SenderKey state. Called by the Alpha 1 →
     * Alpha 2 migration: SenderKey state seeded against Alpha 1 1:1
     * sessions can't be reused in Alpha 2 group sessions. Group members
     * re-bootstrap their SenderKey on the first group_message after
     * migration. Idempotent.
     */
    suspend fun deleteAll()
}
