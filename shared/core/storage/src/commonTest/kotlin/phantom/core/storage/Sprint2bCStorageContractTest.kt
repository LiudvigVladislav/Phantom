// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sprint 2b-C storage contract tests — cell M-2bC-4 + companion
 * reservation-optional cells.
 *
 * Exercised against in-memory fakes (same pattern as
 * [Sprint2bBStorageContractTest]). The SqlDelight-backed
 * [SqlDelightSessionTransactionRepository] mirrors the contract one-to-
 * one; the [docs/tracks/sprint-2b-opk-pending-session-scope.md] L4 + L7
 * + Sprint 2b-C scope-doc + ADR-029 pin the protocol.
 */
class Sprint2bCStorageContractTest {

    // ── M-2bC-4 ───────────────────────────────────────────────────────────────
    //
    // INBOUND case — pending was created by recipientBootstrapInMemory
    // which placed an opk_reservation in L4 phase 1. Promote-time
    // atomically: upserts active, deletes pending, deletes local OPK,
    // releases reservation. Single transaction.

    @Test
    fun promotePendingToActive_atomicallyConsumesOpkAndPromotesState() = runTest {
        val opkResRepo = FakeOpkReservationRepository()
        val pendingRepo = FakePendingRatchetStateRepository()
        val activeRepo = FakeRatchetStateRepositoryForSprint2bC()
        val localOpkRepo = FakeLocalOneTimePreKeyRepositoryForSprint2bC()
        val sessionTx = FakeSessionTransactionRepositoryWithPromotion(
            opkResRepo = opkResRepo,
            pendingRepo = pendingRepo,
            activeRatchetRepo = activeRepo,
            localOpkRepo = localOpkRepo,
        )

        // Seed the inbound bootstrap end-state (post-commitBootstrap):
        //  - opk_reservation row with opkKey-A bound to conv-A
        //  - local_one_time_pre_key row for opkKey-A
        //  - pending_ratchet_state row for conv-A holding the candidate state
        opkResRepo.reserve(
            opkKeyIdHex = "opkKey-A",
            envelopeId = "env-A",
            conversationId = "conv-A",
            nowMs = 1_000L,
        )
        localOpkRepo.insert(
            LocalOneTimePreKeyEntity(
                keyIdHex = "opkKey-A",
                publicKeyHex = "ab".repeat(32),
                privateKeyHex = "cd".repeat(32),
                uploadedAtMs = 500L,
            ),
        )
        pendingRepo.upsert(
            conversationId = "conv-A",
            stateBlob = "candidate-ratchet-state-json",
            reservedAtMs = 1_000L,
            bootstrapArtifactsBlob = null,
        )

        // Pre-conditions: active empty; pending populated; reservation +
        // OPK in place.
        assertNull(activeRepo.getRatchetState("conv-A"))
        assertNotNull(pendingRepo.get("conv-A"))
        assertNotNull(opkResRepo.get("opkKey-A"))
        assertTrue(localOpkRepo.has("opkKey-A"))

        // Promote.
        val promoted = sessionTx.promotePendingToActive("conv-A")
        assertTrue(promoted, "M-2bC-4: promotePendingToActive MUST return true on a present pending row.")

        // Post-conditions per L4 promotion contract:
        assertEquals(
            "candidate-ratchet-state-json", activeRepo.getRatchetState("conv-A"),
            "M-2bC-4: active ratchet_state upserted with pending's state_blob.",
        )
        assertNull(
            pendingRepo.get("conv-A"),
            "M-2bC-4: pending_ratchet_state row deleted.",
        )
        assertNull(
            opkResRepo.get("opkKey-A"),
            "M-2bC-4: opk_reservation row released (OPK permanently consumed at this site).",
        )
        assertFalse(
            localOpkRepo.has("opkKey-A"),
            "M-2bC-4: local_one_time_pre_key row deleted (OPK permanently consumed).",
        )
    }

    // ── No-pending idempotency ───────────────────────────────────────────────

    @Test
    fun promotePendingToActive_returnsFalse_whenNoPendingRow() = runTest {
        val opkResRepo = FakeOpkReservationRepository()
        val pendingRepo = FakePendingRatchetStateRepository()
        val activeRepo = FakeRatchetStateRepositoryForSprint2bC()
        val localOpkRepo = FakeLocalOneTimePreKeyRepositoryForSprint2bC()
        val sessionTx = FakeSessionTransactionRepositoryWithPromotion(
            opkResRepo, pendingRepo, activeRepo, localOpkRepo,
        )

        val promoted = sessionTx.promotePendingToActive("conv-empty")

        assertFalse(promoted,
            "promotePendingToActive returns false when no pending row exists.")
        assertNull(activeRepo.getRatchetState("conv-empty"),
            "active ratchet_state row is NOT touched when promotion is a no-op.")
    }

    // ── OUTBOUND-INITIATOR case (reservation-optional) ───────────────────────
    //
    // Sprint 2b-C lock (2026-06-15): outbound bootstrap path writes a
    // pending row whose bootstrap_artifacts_blob.x3dhInit.opkKeyIdHex
    // refers to the PEER's local OPK pool, NOT ours. We MUST NOT
    // delete a local OPK row by that id on promote. The
    // reservation-optional shape covers this: promote runs ratchet-
    // state-only when no opk_reservation row exists for the
    // conversation.

    // ── Slice 3 — commitInitiatorPending ─────────────────────────────────────
    //
    // Single-table upsert into pending_ratchet_state with the
    // BootstrapArtifacts blob attached. Does NOT touch ratchet_state /
    // local_one_time_pre_key / opk_reservation. Sprint 2b-C OUTBOUND-
    // INITIATOR path uses this from DMS:434/620 bootstrap branch.

    @Test
    fun commitInitiatorPending_writesPendingRow_doesNotTouchActiveOpkOrReservation() = runTest {
        val opkResRepo = FakeOpkReservationRepository()
        val pendingRepo = FakePendingRatchetStateRepository()
        val activeRepo = FakeRatchetStateRepositoryForSprint2bC()
        val localOpkRepo = FakeLocalOneTimePreKeyRepositoryForSprint2bC()
        val sessionTx = FakeSessionTransactionRepositoryWithPromotion(
            opkResRepo, pendingRepo, activeRepo, localOpkRepo,
        )

        // Seed an unrelated active row + an unrelated local OPK row +
        // an unrelated reservation. commitInitiatorPending MUST NOT
        // touch any of them.
        activeRepo.upsertRatchetState("conv-other", "untouched-active-blob")
        localOpkRepo.insert(
            LocalOneTimePreKeyEntity(
                keyIdHex = "untouched-opk",
                publicKeyHex = "ab".repeat(32),
                privateKeyHex = "cd".repeat(32),
                uploadedAtMs = 100L,
            ),
        )
        opkResRepo.reserve("untouched-opk", "env-other", "conv-other", 100L)

        sessionTx.commitInitiatorPending(
            conversationId = "conv-outbound",
            stateBlob = "initiator-advanced-ratchet-state",
            bootstrapArtifactsBlob = """{"x3dhInit":{"ephemeralPubKeyHex":"aa","spkKeyId":1,"opkKeyIdHex":"bb"},"recipientPubkeyHex":"cc"}""",
            nowMs = 5_000L,
        )

        // Pending row written for the outbound conversation.
        val written = pendingRepo.get("conv-outbound")
        assertNotNull(written, "pending row MUST be written for the outbound conversation.")
        assertEquals("initiator-advanced-ratchet-state", written!!.stateBlob)
        assertEquals(5_000L, written.reservedAtMs)
        assertNotNull(written.bootstrapArtifactsBlob,
            "bootstrap_artifacts_blob MUST be non-null on the outbound-initiator path.")

        // Unrelated rows UNTOUCHED.
        assertEquals(
            "untouched-active-blob", activeRepo.getRatchetState("conv-other"),
            "commitInitiatorPending MUST NOT touch ratchet_state.",
        )
        assertTrue(localOpkRepo.has("untouched-opk"),
            "commitInitiatorPending MUST NOT touch local_one_time_pre_key.")
        assertNotNull(opkResRepo.get("untouched-opk"),
            "commitInitiatorPending MUST NOT touch opk_reservation.")
    }

    @Test
    fun commitInitiatorPending_overwritesExistingExpiredRow() = runTest {
        val opkResRepo = FakeOpkReservationRepository()
        val pendingRepo = FakePendingRatchetStateRepository()
        val activeRepo = FakeRatchetStateRepositoryForSprint2bC()
        val localOpkRepo = FakeLocalOneTimePreKeyRepositoryForSprint2bC()
        val sessionTx = FakeSessionTransactionRepositoryWithPromotion(
            opkResRepo, pendingRepo, activeRepo, localOpkRepo,
        )

        // First bootstrap.
        sessionTx.commitInitiatorPending(
            conversationId = "conv-outbound",
            stateBlob = "first-state",
            bootstrapArtifactsBlob = """{"first":true}""",
            nowMs = 1_000L,
        )
        // Second bootstrap (after first's PENDING_TTL expired and
        // outbound encrypt path re-runs fresh).
        sessionTx.commitInitiatorPending(
            conversationId = "conv-outbound",
            stateBlob = "second-state",
            bootstrapArtifactsBlob = """{"second":true}""",
            nowMs = 700_000L,
        )

        val current = pendingRepo.get("conv-outbound")
        assertNotNull(current)
        assertEquals("second-state", current!!.stateBlob,
            "commitInitiatorPending is INSERT OR REPLACE — the second bootstrap overwrites.")
        assertEquals(700_000L, current.reservedAtMs)
        assertEquals("""{"second":true}""", current.bootstrapArtifactsBlob)
    }

    @Test
    fun promotePendingToActive_promotesWithoutOpkDelete_whenNoReservation() = runTest {
        val opkResRepo = FakeOpkReservationRepository()
        val pendingRepo = FakePendingRatchetStateRepository()
        val activeRepo = FakeRatchetStateRepositoryForSprint2bC()
        val localOpkRepo = FakeLocalOneTimePreKeyRepositoryForSprint2bC()
        val sessionTx = FakeSessionTransactionRepositoryWithPromotion(
            opkResRepo, pendingRepo, activeRepo, localOpkRepo,
        )

        // Outbound-initiator end-state: pending row populated; NO
        // opk_reservation row; the local OPK pool holds an OPK that
        // happens to share the SAME hex id as the peer's referenced
        // OPK (worst case for the bug we're guarding against).
        val opkKeyIdHexBothSides = "00".repeat(16)
        localOpkRepo.insert(
            LocalOneTimePreKeyEntity(
                keyIdHex = opkKeyIdHexBothSides,
                publicKeyHex = "ee".repeat(32),
                privateKeyHex = "ff".repeat(32),
                uploadedAtMs = 600L,
            ),
        )
        pendingRepo.upsert(
            conversationId = "conv-outbound",
            stateBlob = "initiator-ratchet-state-json",
            reservedAtMs = 2_000L,
            bootstrapArtifactsBlob = """{"opkKeyIdHex":"$opkKeyIdHexBothSides"}""",
        )
        assertNull(opkResRepo.get(opkKeyIdHexBothSides),
            "pre-condition: no reservation for the outbound-initiator path.")

        val promoted = sessionTx.promotePendingToActive("conv-outbound")
        assertTrue(promoted,
            "outbound-initiator promotion returns true when a pending row exists.")

        // Active upsert + pending delete — same as the inbound case.
        assertEquals(
            "initiator-ratchet-state-json", activeRepo.getRatchetState("conv-outbound"),
            "active ratchet_state upserted with pending's state_blob.",
        )
        assertNull(pendingRepo.get("conv-outbound"),
            "pending_ratchet_state row deleted.")

        // LOAD-BEARING (reservation-optional invariant): the local OPK
        // row that shares the hex id with the peer's referenced OPK
        // MUST NOT be deleted. Deleting it would corrupt our pool —
        // the peer's pool is not our concern.
        assertTrue(
            localOpkRepo.has(opkKeyIdHexBothSides),
            "outbound-initiator promotion MUST NOT delete a local_one_time_pre_key row " +
                "when no opk_reservation exists for the conversation. The peer's OPK " +
                "id in bootstrap_artifacts_blob refers to the peer's local pool, NOT ours.",
        )
    }
}

// ── Fakes (file-local; do NOT collide with Sprint2bBStorageContractTest ones) ──

private class FakeRatchetStateRepositoryForSprint2bC : RatchetStateRepository {
    private val store = mutableMapOf<String, String>()
    override suspend fun getRatchetState(conversationId: String): String? = store[conversationId]
    override suspend fun upsertRatchetState(conversationId: String, stateBlob: String) {
        store[conversationId] = stateBlob
    }
    override suspend fun deleteRatchetState(conversationId: String) { store.remove(conversationId) }
    override suspend fun deleteAll() { store.clear() }
}

private class FakeLocalOneTimePreKeyRepositoryForSprint2bC : LocalOneTimePreKeyRepository {
    private val store = mutableMapOf<String, LocalOneTimePreKeyEntity>()
    override suspend fun get(keyIdHex: String): LocalOneTimePreKeyEntity? = store[keyIdHex]
    override suspend fun getAll(): List<LocalOneTimePreKeyEntity> = store.values.toList()
    override suspend fun count(): Int = store.size
    override suspend fun insert(entity: LocalOneTimePreKeyEntity) {
        store[entity.keyIdHex] = entity
    }
    override suspend fun insertAll(entities: List<LocalOneTimePreKeyEntity>) {
        entities.forEach { insert(it) }
    }
    override suspend fun deleteByKeyId(keyIdHex: String) { store.remove(keyIdHex) }
    override suspend fun clear() { store.clear() }
    fun has(keyIdHex: String): Boolean = store.containsKey(keyIdHex)
}

/**
 * Promotion-aware fake — mirrors the SqlDelight impl's promote
 * algorithm. Reservation-optional: when no reservation row exists
 * for the conversation, the local OPK pool is NOT touched (the
 * outbound-initiator invariant).
 */
private class FakeSessionTransactionRepositoryWithPromotion(
    private val opkResRepo: OpkReservationRepository,
    private val pendingRepo: PendingRatchetStateRepository,
    private val activeRatchetRepo: RatchetStateRepository,
    private val localOpkRepo: LocalOneTimePreKeyRepository,
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
        val reservation = opkResRepo.getByConversationId(conversationId)
        if (reservation != null) {
            opkResRepo.release(reservation.opkKeyIdHex)
        }
        pendingRepo.delete(conversationId)
    }

    override suspend fun promotePendingToActive(conversationId: String): Boolean {
        val pending = pendingRepo.get(conversationId) ?: return false
        activeRatchetRepo.upsertRatchetState(conversationId, pending.stateBlob)
        pendingRepo.delete(conversationId)
        val reservation = opkResRepo.getByConversationId(conversationId)
        if (reservation != null) {
            localOpkRepo.deleteByKeyId(reservation.opkKeyIdHex)
            opkResRepo.release(reservation.opkKeyIdHex)
        }
        return true
    }

    override suspend fun commitInitiatorPending(
        conversationId: String,
        stateBlob: String,
        bootstrapArtifactsBlob: String,
        nowMs: Long,
    ) {
        pendingRepo.upsert(
            conversationId = conversationId,
            stateBlob = stateBlob,
            reservedAtMs = nowMs,
            bootstrapArtifactsBlob = bootstrapArtifactsBlob,
        )
    }
}
