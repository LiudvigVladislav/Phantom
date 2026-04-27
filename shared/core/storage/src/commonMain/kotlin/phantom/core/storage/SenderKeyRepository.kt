// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

interface SenderKeyRepository {
    suspend fun get(groupId: String, memberPubkeyHex: String): SenderKeyEntity?
    suspend fun upsert(entity: SenderKeyEntity)
    suspend fun deleteForGroup(groupId: String)
}
