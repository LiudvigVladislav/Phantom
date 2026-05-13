// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for [ProcessedEnvelopeRepository] (PR-H2b, 2026-05-13).
 *
 * Exercised via [FakeProcessedEnvelopeRepository] — the same in-memory
 * fake the messaging-module tests use to assert idempotency behaviour
 * without standing up a real SQLDelight driver in commonTest. The
 * production [SqlDelightProcessedEnvelopeRepository] mirrors this
 * contract one-to-one (the SQL queries do nothing the fake doesn't).
 */
class ProcessedEnvelopeRepositoryContractTest {

    private fun newRepo(): ProcessedEnvelopeRepository = FakeProcessedEnvelopeRepository()

    @Test
    fun exists_returnsFalse_whenLedgerEmpty() = runTest {
        val repo = newRepo()
        assertFalse(repo.exists("never-seen-id"))
    }

    @Test
    fun markProcessed_thenExists_returnsTrue() = runTest {
        val repo = newRepo()
        repo.markProcessed(
            envelopeId = "envelope-A",
            conversationId = "conv-X",
            senderPubKeyHex = "abc123",
            payloadType = "message",
            status = ProcessedEnvelopeRepository.Status.PROCESSED,
            nowMs = 1_000L,
        )
        assertTrue(repo.exists("envelope-A"))
        assertFalse(repo.exists("envelope-B"))
    }

    @Test
    fun markProcessed_isIdempotent_secondCallNoOp() = runTest {
        val repo = newRepo()
        repo.markProcessed(
            envelopeId = "envelope-dup",
            conversationId = "conv-X",
            senderPubKeyHex = "abc123",
            payloadType = "read_receipt",
            status = ProcessedEnvelopeRepository.Status.PROCESSED,
            nowMs = 1_000L,
        )
        // Second call with the same id MUST NOT throw and MUST NOT
        // overwrite — represents the "two concurrent receive coroutines
        // collided on the same envelope id" race that the SQLite
        // INSERT OR IGNORE clause models.
        repo.markProcessed(
            envelopeId = "envelope-dup",
            conversationId = "conv-X",
            senderPubKeyHex = "abc123",
            payloadType = "control",  // would-be different type
            status = ProcessedEnvelopeRepository.Status.FAILED_MAC,  // would-be different status
            nowMs = 2_000L,
        )
        assertTrue(repo.exists("envelope-dup"))
        // First write wins per INSERT OR IGNORE semantics.
        val counts = repo.countByStatus()
        assertEquals(1L, counts[ProcessedEnvelopeRepository.Status.PROCESSED])
        assertEquals(null, counts[ProcessedEnvelopeRepository.Status.FAILED_MAC])
    }

    @Test
    fun deleteOlderThan_removesOnlyExpiredRows() = runTest {
        val repo = newRepo()
        repo.markProcessed("old-1", "c", "p", "message", ProcessedEnvelopeRepository.Status.PROCESSED, nowMs = 1_000L)
        repo.markProcessed("old-2", "c", "p", "message", ProcessedEnvelopeRepository.Status.PROCESSED, nowMs = 2_000L)
        repo.markProcessed("fresh", "c", "p", "message", ProcessedEnvelopeRepository.Status.PROCESSED, nowMs = 9_000L)

        repo.deleteOlderThan(olderThanMs = 5_000L)

        assertFalse(repo.exists("old-1"))
        assertFalse(repo.exists("old-2"))
        assertTrue(repo.exists("fresh"))
    }

    @Test
    fun countByStatus_groupsByStatus() = runTest {
        val repo = newRepo()
        repo.markProcessed("a", "c", "p", "message", ProcessedEnvelopeRepository.Status.PROCESSED, 1L)
        repo.markProcessed("b", "c", "p", "message", ProcessedEnvelopeRepository.Status.PROCESSED, 2L)
        repo.markProcessed("c", "c", "p", "message", ProcessedEnvelopeRepository.Status.FAILED_MAC, 3L)
        val counts = repo.countByStatus()
        assertEquals(2L, counts[ProcessedEnvelopeRepository.Status.PROCESSED])
        assertEquals(1L, counts[ProcessedEnvelopeRepository.Status.FAILED_MAC])
    }

    @Test
    fun deleteAll_emptiesLedger() = runTest {
        val repo = newRepo()
        repo.markProcessed("a", "c", "p", "message", ProcessedEnvelopeRepository.Status.PROCESSED, 1L)
        repo.markProcessed("b", "c", "p", "message", ProcessedEnvelopeRepository.Status.FAILED_MAC, 2L)
        repo.deleteAll()
        assertFalse(repo.exists("a"))
        assertFalse(repo.exists("b"))
        assertTrue(repo.countByStatus().isEmpty())
    }
}

/**
 * In-memory fake exposed at module level so messaging-module tests can
 * import it. Mirrors [SqlDelightProcessedEnvelopeRepository] semantics:
 * - PRIMARY KEY on envelope_id (Map<String, Row>)
 * - INSERT OR IGNORE on markProcessed (putIfAbsent)
 * - TTL sweep by created_at_ms cutoff
 */
class FakeProcessedEnvelopeRepository : ProcessedEnvelopeRepository {
    private data class Row(
        val envelopeId: String,
        val conversationId: String,
        val senderPubKeyHex: String,
        val payloadType: String,
        val status: ProcessedEnvelopeRepository.Status,
        val createdAtMs: Long,
    )

    private val store = mutableMapOf<String, Row>()

    override suspend fun exists(envelopeId: String): Boolean = store.containsKey(envelopeId)

    override suspend fun markProcessed(
        envelopeId: String,
        conversationId: String,
        senderPubKeyHex: String,
        payloadType: String,
        status: ProcessedEnvelopeRepository.Status,
        nowMs: Long,
    ) {
        // INSERT OR IGNORE: putIfAbsent rather than put.
        if (envelopeId !in store) {
            store[envelopeId] = Row(envelopeId, conversationId, senderPubKeyHex, payloadType, status, nowMs)
        }
    }

    override suspend fun deleteOlderThan(olderThanMs: Long) {
        store.entries.removeAll { it.value.createdAtMs < olderThanMs }
    }

    override suspend fun countByStatus(): Map<ProcessedEnvelopeRepository.Status, Long> =
        store.values.groupingBy { it.status }.eachCount().mapValues { it.value.toLong() }

    override suspend fun deleteAll() {
        store.clear()
    }
}
