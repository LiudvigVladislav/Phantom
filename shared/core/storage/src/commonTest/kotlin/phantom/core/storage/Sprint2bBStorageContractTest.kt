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
 * Sprint 2b-B storage contract tests — cells M-2bB-1 + M-2bB-5 +
 * M-2bB-8.
 *
 * Exercised against in-memory fakes (see [FakeOpkReservationRepository] +
 * [FakePendingRatchetStateRepository] + [FakeSessionTransactionRepository]
 * below) — the same pattern [ProcessedEnvelopeRepositoryContractTest]
 * follows. The SqlDelight-backed implementations mirror the contract
 * one-to-one (the queries do nothing the fakes do not); the
 * androidInstrumentedTest schema-migration cell exercises the real
 * SQLite layer separately.
 *
 * Scope-doc anchor:
 * `docs/tracks/sprint-2b-opk-pending-session-scope.md` L3 / L4 / L5 /
 * L6 / ADR-029.
 */
class Sprint2bBStorageContractTest {

    // ── M-2bB-1 ───────────────────────────────────────────────────────────────
    //
    // Isolation invariant: writing to the pending companion row MUST
    // NOT update the active `ratchet_state` row, NOR vice versa. The
    // Sprint 2a outbound role guard reads ratchet_state via
    // RatchetStateRepository; a pending row leaking into that read
    // path would silently break the guard's RESPONDER / INITIATOR
    // discrimination. The boundary is mechanical (different repository,
    // different table); this test pins it.

    @Test
    fun pendingRatchetStateRepository_writes_areIsolatedFromActive() = runTest {
        val activeRepo: RatchetStateRepository = FakeRatchetStateRepositoryForSprint2bB()
        val pendingRepo: PendingRatchetStateRepository = FakePendingRatchetStateRepository()

        // Empty initial state.
        assertNull(activeRepo.getRatchetState("conv-A"))
        assertNull(pendingRepo.get("conv-A"))

        // Write to pending only.
        pendingRepo.upsert(
            conversationId = "conv-A",
            stateBlob = "pending-state-blob-A",
            reservedAtMs = 1_000L,
            bootstrapArtifactsBlob = null,
        )

        // Pending row populated; active row UNTOUCHED.
        assertEquals("pending-state-blob-A", pendingRepo.get("conv-A")?.stateBlob)
        assertNull(activeRepo.getRatchetState("conv-A"),
            "M-2bB-1: writing to pending MUST NOT touch active ratchet_state.")

        // Now write to active.
        activeRepo.upsertRatchetState("conv-A", "active-state-blob-A")

        // Both rows live in parallel; neither overwrites the other.
        assertEquals("active-state-blob-A", activeRepo.getRatchetState("conv-A"))
        assertEquals("pending-state-blob-A", pendingRepo.get("conv-A")?.stateBlob,
            "M-2bB-1: writing to active MUST NOT touch pending companion row.")
    }

    // ── M-2bB-5 ───────────────────────────────────────────────────────────────
    //
    // commitBootstrap atomicity: reads the opk_reservation row,
    // upserts the pending row with the same reserved_at_ms, in a
    // single transaction. Preserves opk_reservation + local OPK row.

    @Test
    fun commitBootstrap_atomicallyUpsertsPendingAndPreservesOpkAndReservation() = runTest {
        val opkResRepo: OpkReservationRepository = FakeOpkReservationRepository()
        val pendingRepo: PendingRatchetStateRepository = FakePendingRatchetStateRepository()
        val sessionTx: SessionTransactionRepository =
            FakeSessionTransactionRepository(opkResRepo, pendingRepo)

        // L4 phase 1 — reserve.
        val reservedAt = 5_000L
        opkResRepo.reserve(
            opkKeyIdHex = "opk-XYZ",
            envelopeId = "env-1",
            conversationId = "conv-1",
            nowMs = reservedAt,
        )
        assertNotNull(opkResRepo.get("opk-XYZ"))

        // L4 phase 3 success.
        val committed = sessionTx.commitBootstrap(
            opkKeyIdHex = "opk-XYZ",
            conversationId = "conv-1",
            stateBlob = "advanced-ratchet-state-json",
            bootstrapArtifactsBlob = null,
        )
        assertTrue(committed,
            "M-2bB-5: commitBootstrap MUST return true when the reservation exists.")

        // Pending row upserted with the SAME reserved_at_ms as the reservation.
        val pending = pendingRepo.get("conv-1")
        assertNotNull(pending,
            "M-2bB-5: commitBootstrap MUST upsert pending_ratchet_state on success.")
        assertEquals("advanced-ratchet-state-json", pending!!.stateBlob)
        assertEquals(reservedAt, pending.reservedAtMs,
            "M-2bB-5: pending.reserved_at_ms MUST match opk_reservation.reserved_at_ms.")
        assertNull(pending.bootstrapArtifactsBlob,
            "M-2bB-5: Sprint 2b-B production callers pass NULL bootstrap_artifacts_blob.")

        // Reservation PRESERVED — OPK consumption is deferred to Sprint 2b-C promotion.
        assertNotNull(opkResRepo.get("opk-XYZ"),
            "M-2bB-5: opk_reservation row MUST be preserved at L4 phase 3 success " +
                "(amended L4 deferred-consume contract).")
    }

    @Test
    fun commitBootstrap_returnsFalse_whenReservationReleasedBetweenPhases() = runTest {
        val opkResRepo: OpkReservationRepository = FakeOpkReservationRepository()
        val pendingRepo: PendingRatchetStateRepository = FakePendingRatchetStateRepository()
        val sessionTx: SessionTransactionRepository =
            FakeSessionTransactionRepository(opkResRepo, pendingRepo)

        // Race scenario: reservation was released between L4 phase 1
        // and L4 phase 3 (e.g. an L7 cap eviction or L6 sweep). The
        // commit MUST NOT write a pending row.
        val committed = sessionTx.commitBootstrap(
            opkKeyIdHex = "opk-GHOST",
            conversationId = "conv-ghost",
            stateBlob = "would-be-discarded",
            bootstrapArtifactsBlob = null,
        )
        assertFalse(committed,
            "M-2bB-5 race branch: commitBootstrap MUST return false when no reservation exists.")
        assertNull(pendingRepo.get("conv-ghost"),
            "M-2bB-5 race branch: pending_ratchet_state MUST stay empty when commit returns false.")
    }

    // ── M-2bB-8 ───────────────────────────────────────────────────────────────
    //
    // L6 join-based sweep: deletes ONLY orphan reservations whose
    // conversation_id has NO matching pending row, regardless of
    // reservation age. A reservation backing a live pending candidate
    // is preserved even if older than the threshold — eviction of
    // stale pending candidates is the L7 cap-8 LRU's responsibility,
    // not this sweep's.

    @Test
    fun sweepOrphanReservations_skipsReservationsWithMatchingPendingRow() = runTest {
        // L6 join semantics need the reservation repo to know about the
        // pending repo. Production wires both against the same db; the
        // helper here mirrors that pairing.
        val (opkResRepo, pendingRepo) = createSprint2bBFakePair()

        // Two reservations, both OLDER than the threshold.
        // Orphan: conv-orphan has NO matching pending row.
        opkResRepo.reserve(
            opkKeyIdHex = "opk-orphan",
            envelopeId = "env-orphan",
            conversationId = "conv-orphan",
            nowMs = 100L,
        )
        // Pending-backed: conv-live has a matching pending row.
        opkResRepo.reserve(
            opkKeyIdHex = "opk-live",
            envelopeId = "env-live",
            conversationId = "conv-live",
            nowMs = 100L,
        )
        pendingRepo.upsert(
            conversationId = "conv-live",
            stateBlob = "live-pending-state",
            reservedAtMs = 100L,
            bootstrapArtifactsBlob = null,
        )

        // Sweep with a threshold that EXCEEDS both reservations' ages.
        val sweptCount = opkResRepo.sweepOrphanReservations(thresholdMs = 1_000L)

        assertEquals(1, sweptCount,
            "M-2bB-8: sweep deletes exactly the orphan reservation (the matching-pending one is skipped).")
        assertNull(opkResRepo.get("opk-orphan"),
            "M-2bB-8: orphan reservation MUST be deleted.")
        assertNotNull(opkResRepo.get("opk-live"),
            "M-2bB-8: reservation with matching pending row MUST be preserved regardless of age.")
        assertNotNull(pendingRepo.get("conv-live"),
            "M-2bB-8: pending row is not touched by the sweep.")
    }

    @Test
    fun sweepOrphanReservations_skipsReservationsYoungerThanThreshold() = runTest {
        val opkResRepo: OpkReservationRepository = FakeOpkReservationRepository()

        // Orphan but YOUNGER than the threshold — must NOT be swept.
        opkResRepo.reserve(
            opkKeyIdHex = "opk-young",
            envelopeId = "env-young",
            conversationId = "conv-young",
            nowMs = 900L,
        )

        val sweptCount = opkResRepo.sweepOrphanReservations(thresholdMs = 500L)

        assertEquals(0, sweptCount,
            "M-2bB-8: young reservations are not swept regardless of pending-row status.")
        assertNotNull(opkResRepo.get("opk-young"))
    }
}

// ── Fakes ───────────────────────────────────────────────────────────────────

/**
 * Minimal in-memory [RatchetStateRepository] for the M-2bB-1 isolation
 * test. The production fake lives in the messaging module's test code;
 * duplicating a focused fake here keeps the storage contract test
 * self-contained.
 */
private class FakeRatchetStateRepositoryForSprint2bB : RatchetStateRepository {
    private val store = mutableMapOf<String, String>()
    override suspend fun getRatchetState(conversationId: String): String? = store[conversationId]
    override suspend fun upsertRatchetState(conversationId: String, stateBlob: String) {
        store[conversationId] = stateBlob
    }
    override suspend fun deleteRatchetState(conversationId: String) {
        store.remove(conversationId)
    }
    override suspend fun deleteAll() {
        store.clear()
    }
}

/**
 * In-memory [OpkReservationRepository] mirroring
 * [SqlDelightOpkReservationRepository] semantics: `INSERT OR IGNORE`
 * on reserve, idempotent release, join-based sweep against the
 * pending repo passed to the fake transaction wrapper. Used by both
 * the storage contract tests here and the messaging tests for the
 * cap enforcer + DMS callsite cells.
 */
internal class FakeOpkReservationRepository(
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
            store[opkKeyIdHex] = OpkReservation(
                opkKeyIdHex = opkKeyIdHex,
                envelopeId = envelopeId,
                conversationId = conversationId,
                reservedAtMs = nowMs,
            )
            ReservationOutcome.Created
        }
    }

    override suspend fun release(opkKeyIdHex: String) {
        store.remove(opkKeyIdHex)
    }

    override suspend fun get(opkKeyIdHex: String): OpkReservation? = store[opkKeyIdHex]

    override suspend fun getByConversationId(conversationId: String): OpkReservation? =
        store.values.firstOrNull { it.conversationId == conversationId }

    override suspend fun getAll(): List<OpkReservation> =
        store.values.sortedBy { it.reservedAtMs }

    override suspend fun count(): Int = store.size

    override suspend fun sweepOrphanReservations(thresholdMs: Long): Int {
        val pending = pendingRepoForSweep
        val orphanIds = store.values
            .filter { it.reservedAtMs < thresholdMs }
            .filter { res ->
                // L6 join — skip reservations whose conversation_id
                // has a matching pending_ratchet_state row.
                pending == null || pending.get(res.conversationId) == null
            }
            .map { it.opkKeyIdHex }
        for (id in orphanIds) {
            store.remove(id)
        }
        return orphanIds.size
    }

    override suspend fun deleteAll() {
        store.clear()
    }
}

/**
 * Helper that builds a fake reservation repo + fake pending repo so the
 * sweep's join semantics work against the same store the test asserts
 * on. Used by tests that need a tightly-coupled pair.
 */
internal fun createSprint2bBFakePair(): Pair<FakeOpkReservationRepository, FakePendingRatchetStateRepository> {
    val pendingRepo = FakePendingRatchetStateRepository()
    val opkResRepo = FakeOpkReservationRepository(pendingRepoForSweep = pendingRepo)
    return opkResRepo to pendingRepo
}

internal class FakePendingRatchetStateRepository : PendingRatchetStateRepository {
    private val store = mutableMapOf<String, PendingRatchetStateEntity>()

    override suspend fun get(conversationId: String): PendingRatchetStateEntity? =
        store[conversationId]

    override suspend fun upsert(
        conversationId: String,
        stateBlob: String,
        reservedAtMs: Long,
        bootstrapArtifactsBlob: String?,
    ) {
        store[conversationId] = PendingRatchetStateEntity(
            conversationId = conversationId,
            stateBlob = stateBlob,
            reservedAtMs = reservedAtMs,
            bootstrapArtifactsBlob = bootstrapArtifactsBlob,
        )
    }

    override suspend fun delete(conversationId: String) {
        store.remove(conversationId)
    }

    override suspend fun getAll(): List<PendingRatchetStateEntity> =
        store.values.sortedBy { it.reservedAtMs }

    override suspend fun count(): Int = store.size

    override suspend fun getOldestConversationId(): OldestPendingPointer? =
        store.values
            .minByOrNull { it.reservedAtMs }
            ?.let { OldestPendingPointer(it.conversationId, it.reservedAtMs) }

    override suspend fun deleteAll() {
        store.clear()
    }
}

internal class FakeSessionTransactionRepository(
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

    override suspend fun promotePendingToActive(conversationId: String): Boolean =
        // Sprint 2b-B contract tests do not exercise promotion (added in
        // Sprint 2b-C). This fake is intentionally not promotion-aware
        // so an accidental call from a 2b-B cell fails loudly. The
        // Sprint 2b-C variant lives in
        // [Sprint2bCStorageContractTest.FakeSessionTransactionRepositoryWithPromotion]
        // with the full reservation-optional algorithm.
        error(
            "FakeSessionTransactionRepository (Sprint 2b-B variant) does not " +
                "support promotePendingToActive. Use the Sprint 2b-C variant in " +
                "Sprint2bCStorageContractTest.kt.",
        )

    override suspend fun commitInitiatorPending(
        conversationId: String,
        stateBlob: String,
        bootstrapArtifactsBlob: String,
        nowMs: Long,
    ): Unit = error(
        "FakeSessionTransactionRepository (Sprint 2b-B variant) does not " +
            "support commitInitiatorPending. Use the Sprint 2b-C variant in " +
            "Sprint2bCStorageContractTest.kt.",
    )
}
