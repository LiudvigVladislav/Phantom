// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.identity

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * See `LibsodiumIdentityCryptoTest` header comment for why every test
 * here begins with `LibsodiumInitializer.initialize()`. The JVM target
 * uses JNA and the lateinit `sodiumJna` property must be populated
 * before any `Box.keypair()` call.
 */
class IdentityManagerTest {

    private fun makeManager(repo: FakeIdentityRepository = FakeIdentityRepository()): Pair<IdentityManager, FakeIdentityRepository> {
        val crypto = LibsodiumIdentityCrypto()
        val manager = IdentityManager(crypto = crypto, repository = repo)
        return manager to repo
    }

    @Test
    fun createOrLoad_createsNewIdentityWhenNoneExists() = runTest {
        LibsodiumInitializer.initialize()
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
        LibsodiumInitializer.initialize()
        val (manager, _) = makeManager()
        val (firstRecord, _) = manager.createOrLoad("alice")

        val (secondRecord, _) = manager.createOrLoad("alice")

        assertEquals(firstRecord.id, secondRecord.id)
        assertEquals(firstRecord.publicKeyHex, secondRecord.publicKeyHex)
    }

    @Test
    fun getIdentity_returnsNullBeforeCreation() = runTest {
        LibsodiumInitializer.initialize()
        val (manager, _) = makeManager()
        assertNull(manager.getIdentity())
    }

    @Test
    fun getIdentity_returnsRecordAfterCreation() = runTest {
        LibsodiumInitializer.initialize()
        val (manager, _) = makeManager()
        manager.createOrLoad("bob")
        val identity = manager.getIdentity()
        assertNotNull(identity)
        assertEquals("bob", identity.username)
    }

    // ── ADR-009 supplement: Ed25519 signing keypair ─────────────────────────

    @Test
    fun newIdentity_hasSigningKeyPair_populated() = runTest {
        LibsodiumInitializer.initialize()
        val (manager, _) = makeManager()
        val (record, _) = manager.createOrLoad("carol")

        // New (Alpha 2+) identities ship with both keypairs populated atomically.
        assertNotNull(record.signingPublicKeyHex)
        assertNotNull(record.signingPrivateKeyHex)
        assertEquals(false, record.needsSigningKeyBackfill)

        // 32-byte Ed25519 verifying key → 64 hex chars; 64-byte secret → 128 hex.
        assertEquals(64, record.signingPublicKeyHex!!.length)
        assertEquals(128, record.signingPrivateKeyHex!!.length)
    }

    @Test
    fun loadSigningKeyPair_returnsKeypairForNewIdentity() = runTest {
        LibsodiumInitializer.initialize()
        val (manager, _) = makeManager()
        manager.createOrLoad("dave")

        val signing = manager.loadSigningKeyPair()
        assertNotNull(signing)
        assertEquals(32, signing.publicKey.bytes.size)
        assertEquals(64, signing.privateKey.bytes.size)
    }

    @Test
    fun loadSigningKeyPair_returnsNullForAlpha1Record() = runTest {
        LibsodiumInitializer.initialize()
        val repo = FakeIdentityRepository()
        // Seed the repo with an Alpha-1-shaped record (no signing fields).
        repo.saveIdentity(
            IdentityRecord(
                id = "alpha1-id",
                username = "old-user",
                publicKeyHex = "00".repeat(32),
                dhPrivateKeyHex = "11".repeat(32),
                createdAt = 0L,
                signingPublicKeyHex = null,
                signingPrivateKeyHex = null,
            )
        )
        val (manager, _) = makeManager(repo)

        // Migration trigger: loadSigningKeyPair returns null, telling the
        // launch path to route through MigrationScreen.
        val signing = manager.loadSigningKeyPair()
        assertNull(signing)
        val record = manager.getIdentity()!!
        assertTrue(record.needsSigningKeyBackfill)
    }

    @Test
    fun backfillSigningKeyPair_populatesFieldsAtomically() = runTest {
        LibsodiumInitializer.initialize()
        val repo = FakeIdentityRepository()
        repo.saveIdentity(
            IdentityRecord(
                id = "alpha1-id",
                username = "old-user",
                publicKeyHex = "aa".repeat(32),
                dhPrivateKeyHex = "bb".repeat(32),
                createdAt = 100L,
                signingPublicKeyHex = null,
                signingPrivateKeyHex = null,
            )
        )
        val (manager, _) = makeManager(repo)

        val signing = manager.backfillSigningKeyPair()
        assertEquals(32, signing.publicKey.bytes.size)
        assertEquals(64, signing.privateKey.bytes.size)

        // Persisted record now has both signing fields and X25519 fields
        // are byte-for-byte unchanged (the Alpha-1 identity is preserved).
        val updated = manager.getIdentity()!!
        assertEquals(false, updated.needsSigningKeyBackfill)
        assertEquals("aa".repeat(32), updated.publicKeyHex)
        assertEquals("bb".repeat(32), updated.dhPrivateKeyHex)
        assertEquals(100L, updated.createdAt)
        assertEquals("old-user", updated.username)
    }

    @Test
    fun backfillSigningKeyPair_isIdempotent() = runTest {
        LibsodiumInitializer.initialize()
        val (manager, _) = makeManager()
        manager.createOrLoad("eve")  // ships with signing keys already

        // Calling backfill on a record that already has signing keys
        // returns the EXACT keypair without rotating it. Otherwise a
        // crash-and-retry mid-migration would burn through fresh
        // randomness on every wake.
        val first = manager.backfillSigningKeyPair()
        val second = manager.backfillSigningKeyPair()
        assertContentEquals(first.publicKey.bytes, second.publicKey.bytes)
        assertContentEquals(first.privateKey.bytes, second.privateKey.bytes)
    }

    @Test
    fun signWithIdentity_thenVerifyWithIdentity_succeeds() = runTest {
        LibsodiumInitializer.initialize()
        val crypto = LibsodiumIdentityCrypto()
        val signing = crypto.generateSigningKeyPair()

        val message = "phantom-x3dh-test-payload".encodeToByteArray()
        val sig = crypto.signWithIdentity(message, signing.privateKey)
        assertEquals(64, sig.size)
        assertTrue(crypto.verifyWithIdentity(message, sig, signing.publicKey))

        // Tampered message must fail verification.
        val tampered = message.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        kotlin.test.assertFalse(crypto.verifyWithIdentity(tampered, sig, signing.publicKey))
    }
}

private fun assertContentEquals(a: ByteArray, b: ByteArray) =
    kotlin.test.assertContentEquals(a, b)

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
