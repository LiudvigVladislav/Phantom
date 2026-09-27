// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
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

    private class InMemoryProgressStore : MigrationProgressStore {
        val states = mutableMapOf<String, MigrationProgress>()
        var beforeWrite: (MigrationProgress) -> Unit = {}
        var afterWrite: (MigrationProgress) -> Unit = {}
        var beforeRead: () -> Unit = {}
        override suspend fun read(identityId: String): MigrationProgress {
            beforeRead()
            return states[identityId] ?: MigrationProgress.NOT_STARTED
        }
        override suspend fun write(identityId: String, progress: MigrationProgress) {
            beforeWrite(progress)
            states[identityId] = progress
            afterWrite(progress)
        }
    }

    // ── Fakes ────────────────────────────────────────────────────────────────

    private class InMemoryIdentityRepository(seed: IdentityRecord? = null) : IdentityRepository {
        private var stored: IdentityRecord? = seed
        var afterSave: () -> Unit = {}
        override suspend fun createIdentity(username: String): IdentityKeyPair =
            error("not used in MigrationManagerTest")
        override suspend fun loadIdentity(): IdentityRecord? = stored
        override suspend fun saveIdentity(record: IdentityRecord) { stored = record; afterSave() }
        override suspend fun deleteIdentity() { stored = null }
    }

    private class InMemorySignedPreKeyRepo : LocalSignedPreKeyRepository {
        var afterUpsert: () -> Unit = {}
        private var stored: LocalSignedPreKeyEntity? = null
        var upsertCount = 0
        override suspend fun get(): LocalSignedPreKeyEntity? = stored
        override suspend fun upsert(entity: LocalSignedPreKeyEntity) {
            stored = entity
            upsertCount++
            afterUpsert()
        }
        override suspend fun clear() { stored = null }
    }

    private class InMemoryOneTimePreKeyRepo : LocalOneTimePreKeyRepository {
        var afterInsert: () -> Unit = {}
        var afterClear: () -> Unit = {}
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
            afterInsert()
        }
        override suspend fun deleteByKeyId(keyIdHex: String) { store.remove(keyIdHex) }
        override suspend fun clear() { clearCalls++; store.clear(); afterClear() }
    }

    private class InMemoryRatchetStateRepo : RatchetStateRepository {
        var afterDelete: () -> Unit = {}
        val store = mutableMapOf<String, String>()
        var deleteAllCalls = 0
        override suspend fun getRatchetState(conversationId: String) = store[conversationId]
        override suspend fun upsertRatchetState(conversationId: String, stateBlob: String) {
            store[conversationId] = stateBlob
        }
        override suspend fun deleteRatchetState(conversationId: String) {
            store.remove(conversationId)
        }
        override suspend fun deleteAll() { deleteAllCalls++; store.clear(); afterDelete() }
    }

    private class InMemorySenderKeyRepo : SenderKeyRepository {
        var afterDelete: () -> Unit = {}
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
        override suspend fun deleteAll() { deleteAllCalls++; store.clear(); afterDelete() }
    }

    private class InMemoryConversationRepo : ConversationRepository {
        var afterMark: () -> Unit = {}
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
            afterMark()
        }

        // PR-CRYPTO-SESSION-REPAIR1 commit 2 (2026-05-29) — suspect-flag stubs.
        override suspend fun setSessionSuspect(conversationId: String, setAtMs: Long) {
            store[conversationId]?.let {
                store[conversationId] = it.copy(
                    sessionSuspect = true,
                    sessionSuspectSetAtMs = setAtMs,
                )
            }
        }
        override suspend fun clearSessionSuspect(conversationId: String) {
            store[conversationId]?.let {
                store[conversationId] = it.copy(
                    sessionSuspect = false,
                    sessionSuspectSetAtMs = null,
                )
            }
        }
        override suspend fun getSessionSuspectConversations(): List<ConversationEntity> =
            store.values.filter { it.sessionSuspect }.toList()
    }

    private class FakePreKeyApi(
        var publishResult: PublishResult = PublishResult.Stored(0),
    ) : PreKeyApi {
        var afterPublish: () -> Unit = {}
        var duringPublish: suspend () -> Unit = {}
        var publishCount = 0
        var lastRequest: PublishRequest? = null
        // Sprint 2b L1: PreKeyApi.publishBundle takes a factory lambda
        // invoked once per retry attempt. The migration path is single-
        // shot in production, and this fake invokes the lambda exactly
        // once and records the resulting request — same shape as before.
        override suspend fun publishBundle(
            forceJoinInFlight: Boolean,
            requestProvider: suspend () -> PublishRequest,
        ): PublishResult {
            publishCount++
            lastRequest = requestProvider()
            duringPublish()
            afterPublish()
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
        val progressStore = InMemoryProgressStore()
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
            progressStore = progressStore,
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
            progressStore = progressStore,
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
        val progressStore: InMemoryProgressStore,
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

        // Completed migration must not publish or wipe again.
        assertEquals(1, rig.preKeyApi.publishCount)
        assertEquals(1, rig.ratchetRepo.deleteAllCalls)
        assertEquals(1, rig.senderKeyRepo.deleteAllCalls)
        assertEquals(1, rig.convRepo.markAllNeedsRehandshakeCalls)
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

    private fun TestRig.restarted(): MigrationManager {
        val crypto = LibsodiumIdentityCrypto()
        return MigrationManager(
            IdentityManager(crypto, identityRepo), crypto, spkRepo, opkRepo,
            ratchetRepo, senderKeyRepo, convRepo, preKeyApi, LibsodiumX3DH(), progressStore,
            nowMsProvider = { 1_700_000_000_000L },
        )
    }

    @Test
    fun failed_publish_still_requires_migration_after_restart_and_reuses_keys() = runTest {
        LibsodiumInitializer.initialize()
        for (reason in listOf(PublishResult.Reason.RateLimited, PublishResult.Reason.SigningKeyMismatch)) {
            val rig = makeManager(publishResult = PublishResult.Failure(reason, "synthetic failure"))
            rig.ratchetRepo.upsertRatchetState("old", "session")
            assertTrue(rig.mgr.runMigration().isFailure)
            assertTrue(rig.restarted().needsMigration())
            assertEquals("session", rig.ratchetRepo.getRatchetState("old"))
            val identity = rig.identityRepo.loadIdentity()
            val spk = rig.spkRepo.get()
            val opks = rig.opkRepo.getAll()
            rig.preKeyApi.publishResult = PublishResult.Stored(40)
            rig.restarted().runMigration().getOrThrow()
            assertEquals(identity, rig.identityRepo.loadIdentity())
            assertEquals(spk, rig.spkRepo.get())
            assertEquals(opks, rig.opkRepo.getAll())
            assertFalse(rig.restarted().needsMigration())
        }
    }

    @Test
    fun every_persisted_boundary_resumes_after_cancellation_without_replacing_identity() = runTest {
        LibsodiumInitializer.initialize()
        for (boundary in 0..8) {
            val rig = makeManager()
            val stop: () -> Unit = { throw CancellationException("synthetic interruption") }
            when (boundary) {
                0 -> rig.progressStore.afterWrite = { if (it == MigrationProgress.IN_PROGRESS) stop() }
                1 -> rig.identityRepo.afterSave = stop
                2 -> rig.spkRepo.afterUpsert = stop
                3 -> rig.opkRepo.afterClear = stop
                4 -> rig.opkRepo.afterInsert = stop
                5 -> rig.preKeyApi.afterPublish = stop
                6 -> rig.ratchetRepo.afterDelete = stop
                7 -> rig.senderKeyRepo.afterDelete = stop
                8 -> rig.convRepo.afterMark = stop
            }
            try {
                rig.mgr.runMigration()
                fail("Cancellation swallowed at boundary $boundary")
            } catch (_: CancellationException) { }
            assertTrue(rig.restarted().needsMigration(), "boundary $boundary")
            val signing = rig.identityRepo.loadIdentity()!!.signingPublicKeyHex
            rig.progressStore.afterWrite = {}
            rig.identityRepo.afterSave = {}
            rig.spkRepo.afterUpsert = {}
            rig.opkRepo.afterClear = {}
            rig.opkRepo.afterInsert = {}
            rig.preKeyApi.afterPublish = {}
            rig.ratchetRepo.afterDelete = {}
            rig.senderKeyRepo.afterDelete = {}
            rig.convRepo.afterMark = {}
            rig.restarted().runMigration().getOrThrow()
            assertFalse(rig.restarted().needsMigration())
            assertEquals(alpha1Record().publicKeyHex, rig.identityRepo.loadIdentity()!!.publicKeyHex)
            assertEquals(alpha1Record().dhPrivateKeyHex, rig.identityRepo.loadIdentity()!!.dhPrivateKeyHex)
            if (signing != null) assertEquals(signing, rig.identityRepo.loadIdentity()!!.signingPublicKeyHex)
        }
    }

    @Test
    fun failed_begin_write_changes_no_keys_or_sessions() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager()
        rig.progressStore.beforeWrite = { error("disk failure") }
        assertTrue(rig.mgr.runMigration().isFailure)
        assertEquals(alpha1Record(), rig.identityRepo.loadIdentity())
        assertEquals(0, rig.spkRepo.upsertCount)
        assertEquals(0, rig.preKeyApi.publishCount)
        assertEquals(0, rig.ratchetRepo.deleteAllCalls)
        assertTrue(rig.restarted().needsMigration())
    }

    @Test
    fun failed_completion_write_keeps_pending_and_does_not_return_success() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager()
        rig.progressStore.beforeWrite = { if (it == MigrationProgress.COMPLETE) error("disk failure") }
        assertTrue(rig.mgr.runMigration().isFailure)
        assertTrue(rig.restarted().needsMigration())
        rig.progressStore.beforeWrite = {}
        rig.restarted().runMigration().getOrThrow()
        assertFalse(rig.restarted().needsMigration())
    }

    @Test
    fun healthy_existing_identity_without_marker_is_never_wiped() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager(identity = alpha2Record())
        rig.ratchetRepo.upsertRatchetState("healthy", "keep")
        rig.mgr.runMigration().getOrThrow()
        assertFalse(rig.restarted().needsMigration())
        assertEquals("keep", rig.ratchetRepo.getRatchetState("healthy"))
        assertEquals(0, rig.preKeyApi.publishCount)
        assertEquals(0, rig.senderKeyRepo.deleteAllCalls)
        assertEquals(0, rig.convRepo.markAllNeedsRehandshakeCalls)
        assertTrue(rig.progressStore.states.isEmpty())
    }

    @Test
    fun completed_marker_does_not_affect_another_identity() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager()
        rig.progressStore.states["another-identity"] = MigrationProgress.COMPLETE
        assertTrue(rig.mgr.needsMigration())
        rig.mgr.runMigration().getOrThrow()
        assertEquals(MigrationProgress.COMPLETE, rig.progressStore.states["id-1"])
    }

    @Test
    fun completed_migration_preserves_sessions_created_after_it() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager()
        rig.mgr.runMigration().getOrThrow()
        rig.ratchetRepo.upsertRatchetState("new", "keep")
        rig.restarted().runMigration().getOrThrow()
        assertEquals("keep", rig.ratchetRepo.getRatchetState("new"))
        assertEquals(1, rig.ratchetRepo.deleteAllCalls)
        assertEquals(1, rig.preKeyApi.publishCount)
    }

    @Test
    fun missing_identity_returns_typed_failure_without_writes() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager(identity = null)
        assertTrue(rig.mgr.runMigration().exceptionOrNull() is MigrationException.NoIdentity)
        assertTrue(rig.progressStore.states.isEmpty())
    }

    @Test
    fun concurrent_calls_share_one_migration_and_one_cleanup() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        rig.preKeyApi.duringPublish = { entered.complete(Unit); release.await() }
        val first = async { rig.mgr.runMigration() }
        entered.await()
        val second = async { rig.mgr.runMigration() }
        val detection = async { rig.mgr.needsMigration() }
        yield()
        assertFalse(second.isCompleted)
        assertFalse(detection.isCompleted)
        release.complete(Unit)
        first.await().getOrThrow()
        second.await().getOrThrow()
        assertFalse(detection.await())
        assertEquals(1, rig.preKeyApi.publishCount)
        assertEquals(1, rig.ratchetRepo.deleteAllCalls)
    }

    @Test
    fun interrupted_return_after_completion_does_not_wipe_again() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager()
        rig.progressStore.afterWrite = {
            if (it == MigrationProgress.COMPLETE) throw CancellationException("after commit")
        }
        try { rig.mgr.runMigration(); fail("Cancellation swallowed") } catch (_: CancellationException) { }
        assertFalse(rig.restarted().needsMigration())
        rig.ratchetRepo.upsertRatchetState("new", "keep")
        rig.restarted().runMigration().getOrThrow()
        assertEquals("keep", rig.ratchetRepo.getRatchetState("new"))
        assertEquals(1, rig.preKeyApi.publishCount)
    }

    @Test
    fun unreadable_progress_fails_closed_before_mutation() = runTest {
        LibsodiumInitializer.initialize()
        val rig = makeManager()
        rig.progressStore.beforeRead = { error("corrupt marker") }
        try { rig.mgr.needsMigration(); fail("Must fail closed") } catch (_: IllegalStateException) { }
        assertTrue(rig.mgr.runMigration().isFailure)
        assertEquals(alpha1Record(), rig.identityRepo.loadIdentity())
        assertEquals(0, rig.preKeyApi.publishCount)
    }
}
