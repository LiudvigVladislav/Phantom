// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.identity

interface IdentityRepository {
    suspend fun createIdentity(username: String): IdentityKeyPair
    suspend fun loadIdentity(): IdentityRecord?
    suspend fun saveIdentity(record: IdentityRecord)
    suspend fun deleteIdentity()
}
