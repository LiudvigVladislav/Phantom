// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.identity

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IdentityManagerTest {

    private fun makeManager(repo: FakeIdentityRepository = FakeIdentityRepository()): Pair<IdentityManager, FakeIdentityRepository> {
        val crypto = LibsodiumIdentityCrypto()
        val manager = IdentityManager(crypto = crypto, repository = repo)
        return manager to repo
    }

    @Test
    fun createOrLoad_createsNewIdentityWhenNoneExists() = runTest {
        val (manager, repo) = makeManager()
        assertNull(repo.loadIdentity())

        val (record, keyPair) = manager.createOrLoad("alice")

        assertEquals("alice", record.username)
        assertNotNull(record.id)
        assertNotNull(record.publicKeyHex)
        assertTrue(keyPair.publicKey.bytes.isNotEmpty())
        assertNotNull(repo.loadIdentity())
    }

    @Test
    fun createOrLoad_returnsExistingIdentityWhenAlreadyCreated() = runTest {
        val (manager, _) = makeManager()
        val (firstRecord, _) = manager.createOrLoad("alice")

        val (secondRecord, _) = manager.createOrLoad("alice")

        assertEquals(firstRecord.id, secondRecord.id)
        assertEquals(firstRecord.publicKeyHex, secondRecord.publicKeyHex)
    }

    @Test
    fun getIdentity_returnsNullBeforeCreation() = runTest {
        val (manager, _) = makeManager()
        assertNull(manager.getIdentity())
    }

    @Test
    fun getIdentity_returnsRecordAfterCreation() = runTest {
        val (manager, _) = makeManager()
        manager.createOrLoad("bob")
        val identity = manager.getIdentity()
        assertNotNull(identity)
        assertEquals("bob", identity.username)
    }
}

private fun assertTrue(value: Boolean) = kotlin.test.assertTrue(value)

private class FakeIdentityRepository : IdentityRepository {
    private var stored: IdentityRecord? = null

    override suspend fun createIdentity(username: String): IdentityKeyPair {
        val crypto = LibsodiumIdentityCrypto()
        return crypto.generateKeyPair()
    }

    override suspend fun loadIdentity(): IdentityRecord? = stored

    override suspend fun saveIdentity(record: IdentityRecord) {
        stored = record
    }

    override suspend fun deleteIdentity() {
        stored = null
    }
}
