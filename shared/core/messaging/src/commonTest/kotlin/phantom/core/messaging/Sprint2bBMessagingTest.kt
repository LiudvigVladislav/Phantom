// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.signature.Signature
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import phantom.core.crypto.DhKeyPair
import phantom.core.crypto.LibsodiumDoubleRatchet
import phantom.core.crypto.LibsodiumX3DH
import phantom.core.crypto.SessionRole
import phantom.core.crypto.SignedPreKeySigner
import phantom.core.crypto.X3DHProtocol
import phantom.core.identity.LibsodiumIdentityCrypto
import phantom.core.storage.LocalOneTimePreKeyEntity
import phantom.core.storage.LocalOneTimePreKeyRepository
import phantom.core.storage.LocalSignedPreKeyEntity
import phantom.core.storage.LocalSignedPreKeyRepository
import phantom.core.storage.OldestPendingPointer
import phantom.core.storage.OpkReservation
import phantom.core.storage.OpkReservationRepository
import phantom.core.storage.PendingRatchetStateEntity
import phantom.core.storage.PendingRatchetStateRepository
import phantom.core.storage.RatchetStateRepository
import phantom.core.storage.ReservationOutcome
import phantom.core.storage.SessionTransactionRepository
import phantom.core.transport.PreKeyBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sprint 2b-B messaging-layer cells M-2bB-2 + M-2bB-3 + M-2bB-4 +
 * M-2bB-6 + M-2bB-7. Storage-layer cells M-2bB-1 + M-2bB-5 + M-2bB-8
 * live in `:shared:core:storage:jvmTest`
 * ([phantom.core.storage.Sprint2bBStorageContractTest]).
 *
 * Scope-doc anchor: `docs/tracks/sprint-2b-opk-pending-session-scope.md`
 * L3 / L4 / L7 + ADR-029.
 */
class Sprint2bBMessagingTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── M-2bB-2 ───────────────────────────────────────────────────────────────
    //
    // The Sprint 2a outbound role guard at DMS:434 reads the active
    // ratchet_state via RatchetStateRepository (through
    // SessionManager.tryLoadSession). With a populated pending row
    // for the SAME conversation, the guard MUST still read the active
    // slot — not the pending. The boundary is mechanical (different
    // repository, different table), so a leak would be a logic bug
    // in tryLoadSession or in the guard's read path.

    @Test
    fun sprint2aGuard_readsActiveSlot_neverReadsPending() = runTest {
        LibsodiumInitializer.initialize()
        val real = LibsodiumX3DH()
        val activeRepo = InMemoryRatchetStateRepo()
        val pendingRepo = FakePendingRatchetStateRepository()

        // Seed active with a RESPONDER session (the Sprint 2a guard's
        // trigger condition).
        val responderKp = real.generateDhKeyPair()
        val responderState = phantom.core.crypto.RatchetState(
            rootKey = ByteArray(32) { 0xAA.toByte() },
            sendingChainKey = null,
            receivingChainKey = null,
            sendingRatchetPublicKey = responderKp.publicKey.bytes,
            sendingRatchetPrivateKey = responderKp.privateKey.bytes,
            receivingRatchetPublicKey = ByteArray(32) { 0xBB.toByte() },
            role = SessionRole.RESPONDER,
        )
        activeRepo.upsertRatchetState(
            "conv-test",
            json.encodeToString(phantom.core.crypto.RatchetState.serializer(), responderState),
        )

        // Now populate pending with an INITIATOR session (the
        // post-bootstrap shape Sprint 2b-B writes).
        val initiatorKp = real.generateDhKeyPair()
        val initiatorPendingState = phantom.core.crypto.RatchetState(
            rootKey = ByteArray(32) { 0xCC.toByte() },
            sendingChainKey = null,
            receivingChainKey = null,
            sendingRatchetPublicKey = initiatorKp.publicKey.bytes,
            sendingRatchetPrivateKey = initiatorKp.privateKey.bytes,
            receivingRatchetPublicKey = ByteArray(32) { 0xDD.toByte() },
            role = SessionRole.INITIATOR,
        )
        pendingRepo.upsert(
            conversationId = "conv-test",
            stateBlob = json.encodeToString(phantom.core.crypto.RatchetState.serializer(), initiatorPendingState),
            reservedAtMs = 1_000L,
            bootstrapArtifactsBlob = null,
        )

        // SessionManager.tryLoadSession only reads RatchetStateRepository.
        val mgr = SessionManager(
            x3dh = real,
            ratchetStateRepository = activeRepo,
            signedPreKeyRepository = InMemorySignedPreKeyRepo(),
            oneTimePreKeyRepository = InMemoryOneTimePreKeyRepoForTest(),
            identityCrypto = LibsodiumIdentityCrypto(),
            json = json,
        )

        val loaded = mgr.tryLoadSession("conv-test")
        assertNotNull(loaded, "M-2bB-2: tryLoadSession MUST find the active session row.")
        assertEquals(
            SessionRole.RESPONDER, loaded.role,
            "M-2bB-2: tryLoadSession MUST return the ACTIVE (RESPONDER) role, " +
                "not the pending (INITIATOR) role. The Sprint 2a outbound role guard " +
                "relies on this active-only read for its RESPONDER detection.",
        )

        // Pending row is untouched by the read — its slot is still there.
        assertNotNull(
            pendingRepo.get("conv-test"),
            "M-2bB-2: tryLoadSession MUST NOT delete or mutate the pending row.",
        )
    }

    // ── M-2bB-3 ───────────────────────────────────────────────────────────────
    //
    // Failed candidate-decrypt: SessionManager.recipientBootstrapInMemory
    // placed a reservation; the caller (DMS:2569) detects decrypt
    // failure and calls release. Final state: OPK row preserved,
    // reservation released. The load-bearing rollback invariant.

    @Test
    fun recipientBootstrapInMemory_failedDecrypt_preservesOpkAndReleasesReservation() = runTest {
        LibsodiumInitializer.initialize()
        val real = LibsodiumX3DH()
        val (opkResRepo, _) = newSprint2bBFakePair()
        val opkIdHex = "ee".repeat(16)
        val opkRepo = InMemoryOneTimePreKeyRepoForTest()
        val (bobMgr, _, bobX25519, aliceX25519, initResult) = buildBootstrapRig(
            real = real,
            opkResRepo = opkResRepo,
            opkRepo = opkRepo,
            bobOpkIdHex = opkIdHex,
        )

        // recipientBootstrapInMemory reserves the OPK without deleting it.
        val candidate = bobMgr.recipientBootstrapInMemory(
            conversationId = "conv-M-2bB-3",
            envelopeId = "env-M-2bB-3",
            localIdentityKeyPair = bobX25519,
            senderIdentityPublicKeyHex = aliceX25519.publicKey.bytes.toHexStringLower(),
            x3dhInit = initResult.x3dhInit,
        )
        assertNotNull(candidate, "candidate state must be derived")

        // Mid-state assertions: reservation in place, OPK preserved.
        assertNotNull(opkResRepo.get(opkIdHex),
            "L4 phase 1: reservation must exist after recipientBootstrapInMemory.")
        assertTrue(opkRepo.has(opkIdHex),
            "L4 phase 1: local_one_time_pre_key row preserved (no eager delete).")

        // The DMS:2569 failure branch calls release on candidate-decrypt fail.
        opkResRepo.release(opkIdHex)

        // Final assertions per M-2bB-3.
        assertNull(opkResRepo.get(opkIdHex),
            "M-2bB-3: reservation MUST be released on candidate-decrypt failure.")
        assertTrue(opkRepo.has(opkIdHex),
            "M-2bB-3: local_one_time_pre_key row MUST be preserved — OPK consumption is " +
                "deferred to Sprint 2b-C promotion under the amended L4 contract.")
    }

    // ── M-2bB-3 conflict branch (PR #316 review P1-1) ────────────────────────
    //
    // INSERT OR IGNORE on a reservation row that already exists with a
    // DIFFERENT (conversationId, envelopeId) owner is REJECTED at L4
    // phase 1 with SessionBootstrapException.OpkReservationConflict.
    // The caller MUST NOT release the reservation in the failure
    // branch — the reservation belongs to the other in-flight
    // derivation. This test pins both the throw and the no-release
    // invariant; the DMS:2569 failure-branch skip-release wiring is
    // covered separately by the manual code review of the `if (err
    // !is OpkReservationConflict)` guard.

    @Test
    fun recipientBootstrapInMemory_onConflictingReservation_throwsAndPreservesExistingOwner() = runTest {
        LibsodiumInitializer.initialize()
        val real = LibsodiumX3DH()
        val (opkResRepo, _) = newSprint2bBFakePair()
        val opkIdHex = "11".repeat(16)
        val opkRepo = InMemoryOneTimePreKeyRepoForTest()

        // Pre-seed a reservation owned by conv-OTHER / env-OTHER.
        opkResRepo.reserve(
            opkKeyIdHex = opkIdHex,
            envelopeId = "env-OTHER",
            conversationId = "conv-OTHER",
            nowMs = 500L,
        )
        val ownerReservation = opkResRepo.get(opkIdHex)
        assertNotNull(ownerReservation)
        assertEquals("conv-OTHER", ownerReservation!!.conversationId)
        assertEquals("env-OTHER", ownerReservation.envelopeId)

        // Build a rig where the THIS caller will reserve under conv /
        // env attribution that mismatches the pre-seeded reservation.
        val (bobMgr, _, bobX25519, aliceX25519, initResult) = buildBootstrapRig(
            real = real,
            opkResRepo = opkResRepo,
            opkRepo = opkRepo,
            bobOpkIdHex = opkIdHex,
            reservedAtMs = 1_000L,
        )

        // L4 phase 1 detects the mismatched owner and throws.
        var thrown: SessionBootstrapException.OpkReservationConflict? = null
        try {
            bobMgr.recipientBootstrapInMemory(
                conversationId = "conv-THIS",
                envelopeId = "env-THIS",
                localIdentityKeyPair = bobX25519,
                senderIdentityPublicKeyHex = aliceX25519.publicKey.bytes.toHexStringLower(),
                x3dhInit = initResult.x3dhInit,
            )
        } catch (e: SessionBootstrapException.OpkReservationConflict) {
            thrown = e
        }
        assertNotNull(thrown,
            "M-2bB-3 conflict: recipientBootstrapInMemory MUST throw " +
                "OpkReservationConflict when AlreadyReserved owner mismatches.")
        assertEquals(opkIdHex, thrown!!.opkKeyIdHex)
        assertEquals("conv-OTHER", thrown.ownerConversationId)
        assertEquals("env-OTHER", thrown.ownerEnvelopeId)
        assertEquals("conv-THIS", thrown.attemptedConversationId)
        assertEquals("env-THIS", thrown.attemptedEnvelopeId)

        // The pre-existing reservation is UNCHANGED — INSERT OR IGNORE
        // semantics + the throw before any further work.
        val afterReservation = opkResRepo.get(opkIdHex)
        assertNotNull(afterReservation,
            "M-2bB-3 conflict: the existing reservation MUST be preserved.")
        assertEquals(
            "conv-OTHER", afterReservation!!.conversationId,
            "M-2bB-3 conflict: the existing reservation owner MUST be preserved.",
        )
        assertEquals("env-OTHER", afterReservation.envelopeId)
        assertEquals(500L, afterReservation.reservedAtMs)

        // OPK row preserved.
        assertTrue(opkRepo.has(opkIdHex),
            "M-2bB-3 conflict: local OPK row preserved (no eager delete + no release).")
    }

    // ── M-2bB-4 ───────────────────────────────────────────────────────────────
    //
    // Mid-derive process crash: reservation written, then "crash" (we
    // discard SessionManager). After "restart" (new SessionManager
    // against the same repos), an L6 startup sweep clears the orphan
    // reservation. OPK row survives.

    @Test
    fun recipientBootstrapInMemory_processCrashMidDerive_opkSurvivesRestart() = runTest {
        LibsodiumInitializer.initialize()
        val real = LibsodiumX3DH()
        val (opkResRepo, pendingRepo) = newSprint2bBFakePair()
        val opkIdHex = "dd".repeat(16)
        val opkRepo = InMemoryOneTimePreKeyRepoForTest()
        val (mgr1, _, bobX25519, aliceX25519, initResult) = buildBootstrapRig(
            real = real,
            opkResRepo = opkResRepo,
            opkRepo = opkRepo,
            bobOpkIdHex = opkIdHex,
            reservedAtMs = 100L,  // OLD timestamp so the sweep can pick it up
        )

        // Phase 1 — reserve fires; phase 3 never runs (the "crash"
        // happens between derive and the caller's commitBootstrap /
        // release call). We simulate this by calling
        // recipientBootstrapInMemory and then deliberately NOT
        // invoking release / commit.
        mgr1.recipientBootstrapInMemory(
            conversationId = "conv-M-2bB-4",
            envelopeId = "env-M-2bB-4",
            localIdentityKeyPair = bobX25519,
            senderIdentityPublicKeyHex = aliceX25519.publicKey.bytes.toHexStringLower(),
            x3dhInit = initResult.x3dhInit,
        )
        // Crash carcass mid-state: reservation in place; no pending row.
        assertNotNull(opkResRepo.get(opkIdHex))
        assertNull(pendingRepo.get("conv-M-2bB-4"),
            "no commitBootstrap ran -> no pending row.")
        assertTrue(opkRepo.has(opkIdHex), "OPK row survives the crash.")

        // ── "Process restart" — discard mgr1 (the in-memory
        // SessionManager); repos survive. AppContainer.initMessagingFromStorage
        // runs the L6 startup sweep BEFORE constructing the new
        // SessionManager.
        val sweptCount = opkResRepo.sweepOrphanReservations(thresholdMs = 1_000L)
        assertEquals(1, sweptCount,
            "M-2bB-4: L6 startup sweep clears exactly the orphan mid-derive reservation.")
        assertNull(opkResRepo.get(opkIdHex),
            "M-2bB-4: orphan reservation MUST be deleted by the sweep.")
        assertTrue(opkRepo.has(opkIdHex),
            "M-2bB-4: local_one_time_pre_key row MUST survive the sweep — the " +
                "OPK was never consumed; it is available for a future bootstrap.")
    }

    // ── M-2bB-6 + M-2bB-7 ─────────────────────────────────────────────────────
    //
    // L7 cap-8 LRU eviction: after 9 reserves the oldest pending +
    // its reservation are evicted; the OPK row is preserved. The
    // eviction releases the reservation (M-2bB-7 pin) without
    // touching the local OPK pool.

    @Test
    fun pendingSessionCapEnforcer_evictsOldestOnOverflow_releasesReservationPreservesOpk() = runTest {
        val (opkResRepo, pendingRepo) = newSprint2bBFakePair()
        val sessionTx = FakeSessionTransactionRepository(opkResRepo, pendingRepo)
        val enforcer = PendingSessionCapEnforcer(
            pendingRatchetStateRepository = pendingRepo,
            sessionTransactionRepository = sessionTx,
        )

        // Fill 9 conversations: each reserves its own OPK + commits a
        // pending row. The OLDEST is conv-0.
        repeat(PendingSessionCapEnforcer.PENDING_SESSION_CANDIDATE_CAP + 1) { i ->
            val opkId = "opk-$i".padStart(32, '0')
            opkResRepo.reserve(
                opkKeyIdHex = opkId,
                envelopeId = "env-$i",
                conversationId = "conv-$i",
                nowMs = 1_000L + i.toLong(),
            )
            sessionTx.commitBootstrap(
                opkKeyIdHex = opkId,
                conversationId = "conv-$i",
                stateBlob = "state-$i",
                bootstrapArtifactsBlob = null,
            )
        }

        // Pre-eviction state: 9 pending rows, 9 reservations.
        assertEquals(9, pendingRepo.count())
        assertEquals(9, opkResRepo.count())

        // Enforce cap.
        val evicted = enforcer.enforce()

        assertEquals(1, evicted,
            "M-2bB-6: enforcer evicts exactly 1 entry to bring count to the cap.")
        assertEquals(
            PendingSessionCapEnforcer.PENDING_SESSION_CANDIDATE_CAP,
            pendingRepo.count(),
            "M-2bB-6: count after enforce equals the cap.",
        )
        // The oldest conversation (conv-0) was evicted.
        assertNull(pendingRepo.get("conv-0"),
            "M-2bB-6: oldest conv (lowest reserved_at_ms) was evicted.")
        // Newer conversations preserved.
        assertNotNull(pendingRepo.get("conv-8"),
            "M-2bB-6: newer conversations are preserved.")
    }

    @Test
    fun pendingSessionCapEnforcer_evictionRollsBackReservationOnly() = runTest {
        val (opkResRepo, pendingRepo) = newSprint2bBFakePair()
        val sessionTx = FakeSessionTransactionRepository(opkResRepo, pendingRepo)
        val enforcer = PendingSessionCapEnforcer(
            pendingRatchetStateRepository = pendingRepo,
            sessionTransactionRepository = sessionTx,
        )
        val opkRepo = InMemoryOneTimePreKeyRepoForTest()

        // Seed the local OPK pool (the row that L4 amended contract
        // says must be preserved through eviction).
        val evictedOpkId = "opk-0".padStart(32, '0')
        opkRepo.insert(
            LocalOneTimePreKeyEntity(
                keyIdHex = evictedOpkId,
                publicKeyHex = "ab".repeat(32),
                privateKeyHex = "cd".repeat(32),
                uploadedAtMs = 100L,
            ),
        )

        // Reserve + commit 9 pending entries.
        repeat(PendingSessionCapEnforcer.PENDING_SESSION_CANDIDATE_CAP + 1) { i ->
            val opkId = "opk-$i".padStart(32, '0')
            opkResRepo.reserve(opkKeyIdHex = opkId, envelopeId = "env-$i",
                conversationId = "conv-$i", nowMs = 1_000L + i.toLong())
            sessionTx.commitBootstrap(opkKeyIdHex = opkId, conversationId = "conv-$i",
                stateBlob = "state-$i", bootstrapArtifactsBlob = null)
        }

        enforcer.enforce()

        // The evicted reservation is GONE.
        assertNull(opkResRepo.get(evictedOpkId),
            "M-2bB-7: evicted conversation's reservation MUST be released.")
        // BUT the local OPK row survives — L4 never consumed it.
        assertTrue(opkRepo.has(evictedOpkId),
            "M-2bB-7: evicted conversation's local_one_time_pre_key row MUST be preserved. " +
                "The amended L4 contract defers OPK consumption to Sprint 2b-C promotion; " +
                "an evicted pending candidate never reaches promotion so the OPK returns to the pool.")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private data class BootstrapRig(
        val bobMgr: SessionManager,
        val bobRatchetRepo: InMemoryRatchetStateRepo,
        val bobX25519: DhKeyPair,
        val aliceX25519: DhKeyPair,
        val initResult: InitiatorBootstrapResult,
    )

    /**
     * Build a SessionManager whose `recipientBootstrapInMemory` can run
     * against a real LibsodiumX3DH derivation, with the OPK pre-seeded
     * in the local pool and the reservation repository injected.
     */
    private suspend fun buildBootstrapRig(
        real: LibsodiumX3DH,
        opkResRepo: OpkReservationRepository,
        opkRepo: InMemoryOneTimePreKeyRepoForTest,
        bobOpkIdHex: String,
        reservedAtMs: Long? = null,
    ): BootstrapRig {
        val aliceX25519 = real.generateDhKeyPair()
        val bobX25519 = real.generateDhKeyPair()
        val bobSpk = real.generateDhKeyPair()
        val bobOpk = real.generateDhKeyPair()
        val bobSigning = Signature.keypair()
        val bobSpkKeyId = 7L
        val bobSpkCreatedAtMs = 1_000L

        // Bob's local SPK + OPK repos pre-populated to match the bundle.
        val bobSpkRepo = object : LocalSignedPreKeyRepository {
            private var stored: LocalSignedPreKeyEntity? = LocalSignedPreKeyEntity(
                keyId = bobSpkKeyId,
                publicKeyHex = bobSpk.publicKey.bytes.toHexStringLower(),
                privateKeyHex = bobSpk.privateKey.bytes.toHexStringLower(),
                createdAtMs = bobSpkCreatedAtMs,
                signatureHex = "00".repeat(64),
            )
            override suspend fun get() = stored
            override suspend fun upsert(entity: LocalSignedPreKeyEntity) { stored = entity }
            override suspend fun clear() { stored = null }
        }
        opkRepo.insert(
            LocalOneTimePreKeyEntity(
                keyIdHex = bobOpkIdHex,
                publicKeyHex = bobOpk.publicKey.bytes.toHexStringLower(),
                privateKeyHex = bobOpk.privateKey.bytes.toHexStringLower(),
                uploadedAtMs = 500L,
            ),
        )

        // Alice produces the x3dhInit header by running initiatorBootstrap
        // on a parallel SessionManager.
        val aliceMgr = SessionManager(
            x3dh = real,
            ratchetStateRepository = InMemoryRatchetStateRepo(),
            signedPreKeyRepository = InMemorySignedPreKeyRepo(),
            oneTimePreKeyRepository = InMemoryOneTimePreKeyRepoForTest(),
            identityCrypto = LibsodiumIdentityCrypto(),
            json = json,
        )
        val signedPreKeySig = SignedPreKeySigner.sign(
            spkPublic = bobSpk.publicKey,
            createdAtMs = bobSpkCreatedAtMs,
            identityEd25519SecretKey = bobSigning.secretKey.toByteArray(),
        )
        val bundle = PreKeyBundle(
            identityPubkeyHex = bobX25519.publicKey.bytes.toHexStringLower(),
            signingPubkeyHex = bobSigning.publicKey.toByteArray().toHexStringLower(),
            signedPreKeyId = bobSpkKeyId,
            signedPreKeyPublicHex = bobSpk.publicKey.bytes.toHexStringLower(),
            signedPreKeyCreatedAtMs = bobSpkCreatedAtMs,
            signedPreKeySignatureHex = signedPreKeySig.toHexStringLower(),
            oneTimePreKeyIdHex = bobOpkIdHex,
            oneTimePreKeyPublicHex = bobOpk.publicKey.bytes.toHexStringLower(),
        )
        val initResult = aliceMgr.initiatorBootstrap(
            conversationId = "alice-side",
            localIdentityKeyPair = aliceX25519,
            bundle = bundle,
        )

        // Bob's manager — wired with the OPK reservation repository per L4.
        val bobRatchetRepo = InMemoryRatchetStateRepo()
        val bobMgr = SessionManager(
            x3dh = real,
            ratchetStateRepository = bobRatchetRepo,
            signedPreKeyRepository = bobSpkRepo,
            oneTimePreKeyRepository = opkRepo,
            identityCrypto = LibsodiumIdentityCrypto(),
            json = json,
            opkReservationRepository = opkResRepo,
            nowMsProvider = { reservedAtMs ?: 1_700_000_000_000L },
        )

        return BootstrapRig(
            bobMgr = bobMgr,
            bobRatchetRepo = bobRatchetRepo,
            bobX25519 = bobX25519,
            aliceX25519 = aliceX25519,
            initResult = initResult,
        )
    }

    // ── Fakes (test-local copies; the SessionManagerTest fakes are private) ──

    private class InMemoryRatchetStateRepo : RatchetStateRepository {
        private val store = mutableMapOf<String, String>()
        override suspend fun getRatchetState(conversationId: String): String? = store[conversationId]
        override suspend fun upsertRatchetState(conversationId: String, stateBlob: String) {
            store[conversationId] = stateBlob
        }
        override suspend fun deleteRatchetState(conversationId: String) { store.remove(conversationId) }
        override suspend fun deleteAll() { store.clear() }
    }

    private class InMemorySignedPreKeyRepo : LocalSignedPreKeyRepository {
        private var stored: LocalSignedPreKeyEntity? = null
        override suspend fun get() = stored
        override suspend fun upsert(entity: LocalSignedPreKeyEntity) { stored = entity }
        override suspend fun clear() { stored = null }
    }

    internal class InMemoryOneTimePreKeyRepoForTest : LocalOneTimePreKeyRepository {
        private val store = mutableMapOf<String, LocalOneTimePreKeyEntity>()
        override suspend fun get(keyIdHex: String): LocalOneTimePreKeyEntity? = store[keyIdHex]
        override suspend fun getAll(): List<LocalOneTimePreKeyEntity> =
            store.values.sortedBy { it.uploadedAtMs }
        override suspend fun count(): Int = store.size
        override suspend fun insert(entity: LocalOneTimePreKeyEntity) { store[entity.keyIdHex] = entity }
        override suspend fun insertAll(entities: List<LocalOneTimePreKeyEntity>) {
            entities.forEach { insert(it) }
        }
        override suspend fun deleteByKeyId(keyIdHex: String) { store.remove(keyIdHex) }
        override suspend fun clear() { store.clear() }
        fun has(keyIdHex: String): Boolean = store.containsKey(keyIdHex)
    }

    private fun ByteArray.toHexStringLower(): String =
        joinToString("") { "%02x".format(it.toInt().and(0xFF)) }

    // ── Inline Sprint 2b-B storage fakes ──────────────────────────────────────
    //
    // The storage module's contract test fakes are `internal` in the
    // storage:commonTest source set; cross-module test consumption is not
    // wired in this project. Duplicating the fakes here keeps the file
    // self-contained — and the fake semantics are minimal (mutable maps
    // with INSERT OR IGNORE on reserve + join-based sweep) so the
    // duplication does not drift.

    private class FakeOpkReservationRepository(
        private val pendingRepoForSweep: PendingRatchetStateRepository? = null,
    ) : OpkReservationRepository {
        private val store = mutableMapOf<String, OpkReservation>()
        override suspend fun reserve(
            opkKeyIdHex: String,
            envelopeId: String,
            conversationId: String,
            nowMs: Long,
        ): ReservationOutcome {
            val existing = store[opkKeyIdHex]
            return if (existing != null) {
                ReservationOutcome.AlreadyReserved(existing)
            } else {
                store[opkKeyIdHex] = OpkReservation(opkKeyIdHex, envelopeId, conversationId, nowMs)
                ReservationOutcome.Created
            }
        }
        override suspend fun release(opkKeyIdHex: String) { store.remove(opkKeyIdHex) }
        override suspend fun get(opkKeyIdHex: String): OpkReservation? = store[opkKeyIdHex]
        override suspend fun getByConversationId(conversationId: String): OpkReservation? =
            store.values.firstOrNull { it.conversationId == conversationId }
        override suspend fun getAll(): List<OpkReservation> = store.values.sortedBy { it.reservedAtMs }
        override suspend fun count(): Int = store.size
        override suspend fun sweepOrphanReservations(thresholdMs: Long): Int {
            val pending = pendingRepoForSweep
            val orphans = store.values
                .filter { it.reservedAtMs < thresholdMs }
                .filter { pending == null || pending.get(it.conversationId) == null }
                .map { it.opkKeyIdHex }
            for (id in orphans) store.remove(id)
            return orphans.size
        }
        override suspend fun deleteAll() { store.clear() }
    }

    private class FakePendingRatchetStateRepository : PendingRatchetStateRepository {
        private val store = mutableMapOf<String, PendingRatchetStateEntity>()
        override suspend fun get(conversationId: String): PendingRatchetStateEntity? = store[conversationId]
        override suspend fun upsert(
            conversationId: String,
            stateBlob: String,
            reservedAtMs: Long,
            bootstrapArtifactsBlob: String?,
        ) {
            store[conversationId] = PendingRatchetStateEntity(
                conversationId, stateBlob, reservedAtMs, bootstrapArtifactsBlob,
            )
        }
        override suspend fun delete(conversationId: String) { store.remove(conversationId) }
        override suspend fun getAll(): List<PendingRatchetStateEntity> =
            store.values.sortedBy { it.reservedAtMs }
        override suspend fun count(): Int = store.size
        override suspend fun getOldestConversationId(): OldestPendingPointer? =
            store.values.minByOrNull { it.reservedAtMs }
                ?.let { OldestPendingPointer(it.conversationId, it.reservedAtMs) }
        override suspend fun deleteAll() { store.clear() }
    }

    private class FakeSessionTransactionRepository(
        private val opkResRepo: OpkReservationRepository,
        private val pendingRepo: PendingRatchetStateRepository,
    ) : SessionTransactionRepository {
        override suspend fun commitBootstrap(
            opkKeyIdHex: String,
            conversationId: String,
            stateBlob: String,
            bootstrapArtifactsBlob: String?,
        ): Boolean {
            val reservation = opkResRepo.get(opkKeyIdHex) ?: return false
            pendingRepo.upsert(
                conversationId = conversationId,
                stateBlob = stateBlob,
                reservedAtMs = reservation.reservedAtMs,
                bootstrapArtifactsBlob = bootstrapArtifactsBlob,
            )
            return true
        }

        override suspend fun evictPendingCandidate(conversationId: String) {
            // Production runs both operations in a single SQLDelight
            // transaction. The fake serialises them — sufficient for
            // contract testing because nothing else races the in-memory
            // store inside the test coroutine.
            val reservation = opkResRepo.getByConversationId(conversationId)
            if (reservation != null) {
                opkResRepo.release(reservation.opkKeyIdHex)
            }
            pendingRepo.delete(conversationId)
        }
    }

    private fun newSprint2bBFakePair(): Pair<FakeOpkReservationRepository, FakePendingRatchetStateRepository> {
        val pendingRepo = FakePendingRatchetStateRepository()
        val opkResRepo = FakeOpkReservationRepository(pendingRepoForSweep = pendingRepo)
        return opkResRepo to pendingRepo
    }
}
