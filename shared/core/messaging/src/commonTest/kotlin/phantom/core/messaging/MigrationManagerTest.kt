// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.test.runTest
import phantom.core.crypto.LibsodiumX3DH
import phantom.core.identity.IdentityCrypto
import phantom.core.identity.IdentityKeyPair
import phantom.core.identity.IdentityManager
import phantom.core.identity.IdentityRecord
import phantom.core.identity.IdentityRepository
import phantom.core.identity.IdentitySigningKeyPair
import phantom.core.identity.LibsodiumIdentityCrypto
import phantom.core.identity.PrivateKey
import phantom.core.identity.PublicKey
import phantom.core.identity.SigningPrivateKey
import phantom.core.identity.SigningPublicKey
import phantom.core.storage.ConversationEntity
import phantom.core.storage.ConversationRepository
import phantom.core.storage.LocalOneTimePreKeyEntity
import phantom.core.storage.LocalOneTimePreKeyRepository
import phantom.core.storage.LocalSignedPreKeyEntity
import phantom.core.storage.LocalSignedPreKeyRepository
import phantom.core.storage.RatchetStateRepository
import phantom.core.storage.SenderKeyEntity
import phantom.core.storage.SenderKeyRepository
import phantom.core.storage.TrustTier
import phantom.core.transport.PreKeyApi
import phantom.core.transport.PreKeyBundle
import phantom.core.transport.PreKeyStatus
import phantom.core.transport.PublishRequest
import phantom.core.transport.PublishResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Verifies the Alpha 1 → Alpha 2 migration flow ends in the right
 * state regardless of the start state, and that the steps are
 * idempotent (a crash in the middle of [MigrationManager.runMigration]
 * does not corrupt the user's keys on the next try).
 *
 * The detection path ([MigrationManager.needsMigration]) is exercised
 * in two shapes: an Alpha-1-shaped IdentityRecord (`signingPublicKeyHex`
 * = null) returns true; a fully-backfilled record returns false.
 */
class MigrationManagerTest {

    // ── Fakes ────────────────────────────────────────────────────────────────

    private class InMemoryIdentityRepository(seed: IdentityRecord? = null) : IdentityRepository {
        private var stored: IdentityRecord? = seed
        override suspend fun createIdentity(username: String): IdentityKeyPair =
            error("not used in MigrationManagerTest")
        override suspend fun loadIdentity(): IdentityRecord? = stored
        override suspend fun saveIdentity(record: IdentityRecord) { stored = record }
        override suspend fun deleteIdentity() { stored = null }
    }

    private class InMemorySignedPreKeyRepo : LocalSignedPreKeyRepository {
        private var stored: LocalSignedPreKeyEntity? = null
        var upsertCount = 0
        override suspend fun get(): LocalSignedPreKeyEntity? = stored
        override suspend fun upsert(entity: LocalSignedPreKeyEntity) {
            stored = entity
            upsertCount++
        }
        override suspend fun clear() { stored = null }
    }

    private class InMemoryOneTimePreKeyRepo : LocalOneTimePreKeyRepository {
        private val store = mutableMapOf<String, LocalOneTimePreKeyEntity>()
        var insertAllCalls = 0
        var clearCalls = 0
        override suspend fun get(keyIdHex: String) = store[keyIdHex]
        override suspend fun getAll() = store.values.toList()
        override suspend fun count() = store.size
        override suspend fun insert(entity: LocalOneTimePreKeyEntity) {
            store[entity.keyIdHex] = entity
        }
        override suspend fun insertAll(entities: List<LocalOneTimePreKeyEntity>) {
            insertAllCalls++
            entities.forEach { store[it.keyIdHex] = it }
        }
        override suspend fun deleteByKeyId(keyIdHex: String) { store.remove(keyIdHex) }
        override suspend fun clear() { clearCalls++; store.clear() }
    }

    private class InMemoryRatchetStateRepo : RatchetStateRepository {
        val store = mutableMapOf<String, String>()
        var deleteAllCalls = 0
        override suspend fun getRatchetState(conversationId: String) = store[conversationId]
        override suspend fun upsertRatchetState(conversationId: String, stateBlob: String) {
            store[conversationId] = stateBlob
        }
        override suspend fun deleteRatchetState(conversationId: String) {
            store.remove(conversationId)
        }
        override suspend fun deleteAll() { deleteAllCalls++; store.clear() }
    }

    private class InMemorySenderKeyRepo : SenderKeyRepository {
        val store = mutableMapOf<Pair<String, String>, SenderKeyEntity>()
        var deleteAllCalls = 0
        override suspend fun get(groupId: String, memberPubkeyHex: String) =
            store[groupId to memberPubkeyHex]
        override suspend fun upsert(entity: SenderKeyEntity) {
            store[entity.groupId to entity.memberPubkeyHex] = entity
        }
        override suspend fun deleteForGroup(groupId: String) {
            store.entries.removeAll { it.key.first == groupId }
        }
        override suspend fun deleteAll() { deleteAllCalls++; store.clear() }
    }

    private class InMemoryConversationRepo : ConversationRepository {
        val store = mutableMapOf<String, ConversationEntity>()
        var markAllNeedsRehandshakeCalls = 0
        override suspend fun getAllConversations(): List<ConversationEntity> = store.values.toList()
        override suspend fun getActiveConversations() = store.values.toList()
        override suspend fun getMessageRequests() = emptyList<ConversationEntity>()
        override suspend fun getConversation(id: String) = store[id]
        override suspend fun upsertConversation(entity: ConversationEntity) { store[entity.id] = entity }
        override suspend fun incrementUnread(conversationId: String) {}
        override suspend fun resetUnread(conversationId: String) {}
        override suspend fun updateNotes(conversationId: String, notes: String?) {}
        override suspend fun getBlockedConversations() = emptyList<ConversationEntity>()
        override suspend fun blockConversation(conversationId: String) {}
        override suspend fun unblockConversation(conversationId: String) {}
        override suspend fun acceptRequest(conversationId: String) {}
        override suspend fun deleteConversation(id: String) { store.remove(id) }
        override suspend fun setVerified(conversationId: String, verified: Boolean) {}
        override suspend fun setDisappearingTimer(conversationId: String, secs: Long) {}
        override suspend fun getDisappearingTimer(conversationId: String) = 0L
        override suspend fun archiveConversation(id: String) {}
        override suspend fun unarchiveConversation(id: String) {}
        override suspend fun getArchivedConversations() = emptyList<ConversationEntity>()
        override suspend fun setIdentityKeyChangedAt(conversationId: String, ts: Long) {}
        override suspend fun clearIdentityKeyChangedAt(conversationId: String) {}
        override suspend fun setMutedUntil(conversationId: String, until: Long?) {}
        override suspend fun setPinned(conversationId: String, pinned: Boolean) {}
        override suspend fun setNeedsRehandshake(conversationId: String, needs: Boolean) {
            store[conversationId]?.let { store[conversationId] = it.copy(needsRehandshake = needs) }
        }
        override suspend fun markAllNeedsRehandshake() {
            markAllNeedsRehandshakeCalls++
            store.keys.toList().forEach { id ->
                store[id]?.let { store[id] = it.copy(needsRehandshake = true) }
            }
        }
    }

    private class FakePreKeyApi(
        var publishResult: PublishResult = PublishResult.Stored(0),
    ) : PreKeyApi {
        var publishCount = 0
        var lastRequest: PublishRequest? = null
        override suspend fun publishBundle(request: PublishRequest): PublishResult {
            publishCount++
            lastRequest = request
            return publishResult
        }
        override suspend fun fetchBundle(
            identityPubkeyHex: String,
            requesterPubkeyHex: String?,
        ): PreKeyBundle? = null
        override suspend fun fetchStatus(
            identityPubkeyHex: String,
            requesterPubkeyHex: String?,
        ): PreKeyStatus = PreKeyStatus(0, null)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun alpha1Record() = IdentityRecord(
        id = "id-1",
        username = "alice",
        publicKeyHex = "11".repeat(32),
        dhPrivateKeyHex = "22".repeat(32),
        createdAt = 1_000L,
        // Both null on Alpha 1 records — the migration trigger key.
        signingPublicKeyHex = null,
        signingPrivateKeyHex = null,
    )

    private fun alpha2Record() = IdentityRecord(
        id = "id-2",
        username = "alice",
        publicKeyHex = "11".repeat(32),
        dhPrivateKeyHex = "22".repeat(32),
        createdAt = 1_000L,
        signingPublicKeyHex = "33".repeat(32),
        signingPrivateKeyHex = "44".repeat(64),
    )

    private fun makeManager(
        identity: IdentityRecord? = alpha1Record(),
        publishResult: PublishResult = PublishResult.Stored(100),
    ): TestRig {
        val identityRepo = InMemoryIdentityRepository(identity)
        val identityCrypto: IdentityCrypto = LibsodiumIdentityCrypto()
        val identityManager = IdentityManager(identityCrypto, identityRepo)
        val spkRepo = InMemorySignedPreKeyRepo()
        val opkRepo = InMemoryOneTimePreKeyRepo()
        val ratchetRepo = InMemoryRatchetStateRepo()
        val senderKeyRepo = InMemorySenderKeyRepo()
        val convRepo = InMemoryConversationRepo()
        val preKeyApi = FakePreKeyApi(publishResult)
        val mgr = MigrationManager(
            identityManager = identityManager,
            identityCrypto = identityCrypto,
            signedPreKeyRepository = spkRepo,
            oneTimePreKeyRepository = opkRepo,
            ratchetStateRepository = ratchetRepo,
            senderKeyRepository = senderKeyRepo,
            conversationRepository = convRepo,
            preKeyApi = preKeyApi,
            x3dh = LibsodiumX3DH(),
            nowMsProvider = { 1_700_000_000_000L },
        )
        return TestRig(
            mgr = mgr,
            identityRepo = identityRepo,
            spkRepo = spkRepo,
            opkRepo = opkRepo,
            ratchetRepo = ratchetRepo,
            senderKeyRepo = senderKeyRepo,
            convRepo = convRepo,
            preKeyApi = preKeyApi,
        )
    }

    private data class TestRig(
        val mgr: MigrationManager,
        val identityRepo: InMemoryIdentityRepository,
        val spkRepo: InMemorySignedPreKeyRepo,
        val opkRepo: InMemoryOneTimePreKeyRepo,
        val ratchetRepo: InMemoryRatchetStateRepo,
        val senderKeyRepo: InMemorySenderKeyRepo,
        val convRepo: InMemoryConversationRepo,
        val preKeyApi: FakePreKeyApi,
    )

    // ── Detection ────────────────────────────────────────────────────────────

    @Test
    fun needsMigration_returnsTrue_forAlpha1Record() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager(identity = alpha1Record())
        assertTrue(rig.mgr.needsMigration())
    }

    @Test
    fun needsMigration_returnsFalse_forAlpha2Record() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager(identity = alpha2Record())
        assertFalse(rig.mgr.needsMigration())
    }

    @Test
    fun needsMigration_returnsFalse_whenNoIdentityYet() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager(identity = null)
        // No identity → user is on the onboarding path; the lifecycle
        // service generates both keypairs there, no migration needed.
        assertFalse(rig.mgr.needsMigration())
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    fun runMigration_alpha1Record_endsInFullyMigratedState() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager(identity = alpha1Record())
        // Pre-existing ratchet states + sender keys + conversations to
        // verify the wipe + flag steps actually execute.
        rig.ratchetRepo.upsertRatchetState("conv-a", "{\"oldAlpha1\":true}")
        rig.senderKeyRepo.upsert(
            SenderKeyEntity(
                groupId = "group-1",
                memberPubkeyHex = "deadbeef",
                chainKeyHex = "00",
                iteration = 0,
            ),
        )
        rig.convRepo.upsertConversation(
            ConversationEntity(
                id = "conv-a",
                theirUsername = "bob",
                theirPublicKeyHex = "33".repeat(32),
                lastMessagePreview = "hi",
                lastMessageAt = 100L,
                unreadCount = 0L,
            ),
        )

        val result = rig.mgr.runMigration()
        assertTrue(result.isSuccess, "happy path should succeed; got $result")

        // Identity record now has both keypairs, X25519 unchanged.
        val updatedIdentity = rig.identityRepo.loadIdentity()!!
        assertFalse(updatedIdentity.needsSigningKeyBackfill)
        assertEquals(alpha1Record().publicKeyHex, updatedIdentity.publicKeyHex)
        assertEquals(alpha1Record().dhPrivateKeyHex, updatedIdentity.dhPrivateKeyHex)
        assertNotNull(updatedIdentity.signingPublicKeyHex)
        assertNotNull(updatedIdentity.signingPrivateKeyHex)
        assertEquals(64, updatedIdentity.signingPublicKeyHex!!.length)
        assertEquals(128, updatedIdentity.signingPrivateKeyHex!!.length)

        // Local prekey state populated with OPK_BATCH_SIZE OPKs + a current SPK.
        assertNotNull(rig.spkRepo.get())
        assertEquals(MigrationManager.OPK_BATCH_SIZE, rig.opkRepo.count())

        // Bundle published exactly once.
        assertEquals(1, rig.preKeyApi.publishCount)
        val req = rig.preKeyApi.lastRequest!!
        assertEquals(updatedIdentity.publicKeyHex, req.identity_pubkey_hex)
        assertEquals(updatedIdentity.signingPublicKeyHex, req.signing_pubkey_hex)
        assertEquals(MigrationManager.OPK_BATCH_SIZE, req.one_time_pre_keys.size)

        // Wipe + flag completed.
        assertEquals(1, rig.ratchetRepo.deleteAllCalls)
        assertEquals(0, rig.ratchetRepo.store.size)
        assertEquals(1, rig.senderKeyRepo.deleteAllCalls)
        assertEquals(0, rig.senderKeyRepo.store.size)
        assertEquals(1, rig.convRepo.markAllNeedsRehandshakeCalls)
        assertTrue(rig.convRepo.store["conv-a"]!!.needsRehandshake)
    }

    // ── Idempotency ──────────────────────────────────────────────────────────

    @Test
    fun runMigration_isIdempotent_onSecondRun() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager(identity = alpha1Record())

        rig.mgr.runMigration().getOrThrow()
        val firstSigningPub = rig.identityRepo.loadIdentity()!!.signingPublicKeyHex
        val firstSpk = rig.spkRepo.get()!!

        rig.mgr.runMigration().getOrThrow()

        // Identity signing keypair must NOT have rotated.
        val secondSigningPub = rig.identityRepo.loadIdentity()!!.signingPublicKeyHex
        assertEquals(firstSigningPub, secondSigningPub, "Ed25519 keypair must be stable on retry")

        // SPK must be the SAME row (we have OPK_BATCH_SIZE OPKs already; resume path
        // should re-publish without regenerating).
        val secondSpk = rig.spkRepo.get()!!
        assertEquals(firstSpk.keyId, secondSpk.keyId)
        assertEquals(firstSpk.publicKeyHex, secondSpk.publicKeyHex)
        // Two upserts total (run 1 generated, run 2 was a no-op
        // because count >= OPK_BATCH_SIZE); confirm via insertAll which only fires
        // on regeneration.
        assertEquals(1, rig.opkRepo.insertAllCalls, "Second run must NOT regenerate OPKs")

        // Publish was retried (idempotent re-publish to relay).
        assertEquals(2, rig.preKeyApi.publishCount)
    }

    // ── Failure paths ────────────────────────────────────────────────────────

    @Test
    fun runMigration_returnsFailure_when_publishHas409Conflict() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager(
            identity = alpha1Record(),
            publishResult = PublishResult.Failure(
                reason = PublishResult.Reason.SigningKeyMismatch,
                serverMessage = "different signing key registered",
            ),
        )

        val result = rig.mgr.runMigration()
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is MigrationException.SigningKeyMismatch)

        // Wipe steps must NOT have run — we don't want to discard a
        // user's session state when their bundle publish was rejected.
        assertEquals(0, rig.ratchetRepo.deleteAllCalls)
        assertEquals(0, rig.senderKeyRepo.deleteAllCalls)
        assertEquals(0, rig.convRepo.markAllNeedsRehandshakeCalls)
    }

    @Test
    fun runMigration_returnsFailure_when_publishRateLimited() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager(
            identity = alpha1Record(),
            publishResult = PublishResult.Failure(
                reason = PublishResult.Reason.RateLimited,
                serverMessage = "publish rate limit exceeded",
            ),
        )
        val result = rig.mgr.runMigration()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MigrationException.PublishRateLimited)

        // Wipe didn't happen — user can retry safely on a later launch.
        assertEquals(0, rig.ratchetRepo.deleteAllCalls)
    }

    // ── Wire format check ───────────────────────────────────────────────────

    @Test
    fun runMigration_publishesBundleWith_signingPubkeyHex() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager(identity = alpha1Record())
        rig.mgr.runMigration().getOrThrow()

        val req = rig.preKeyApi.lastRequest!!
        // ADR-009 supplement contract: PublishRequest carries BOTH
        // X25519 identity and Ed25519 signing key. The relay's verify
        // path uses signing_pubkey_hex against the SPK signature.
        assertEquals(64, req.identity_pubkey_hex.length, "X25519 identity hex")
        assertEquals(64, req.signing_pubkey_hex.length, "Ed25519 signing hex")
        assertTrue(req.signed_pre_key.signature_hex.length == 128, "Ed25519 sig hex (64 bytes)")
        // The two hex strings are different cryptographic objects; if
        // they happen to match, the test rig has a bug.
        assertTrue(
            req.identity_pubkey_hex != req.signing_pubkey_hex,
            "X25519 and Ed25519 keys must be distinct values",
        )
    }
}
