// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Contract tests for [LastSeenSeqRepository] — Trek 2 Stage 2A (A3
 * + A8).
 *
 * Targets the in-memory [FakeLastSeenSeqRepository]; the SQLDelight
 * implementation is exercised separately by an Android
 * instrumented test once that source set lands. The contract is
 * intentionally minimal:
 *
 *   * Reads return `null` for an unknown identity (cold start).
 *   * Reads after `upsertLastSeenSeq` return the persisted value.
 *   * `upsertLastSeenSeq` is monotonic — a write with `seq` less
 *     than or equal to the current persisted value is a silent
 *     no-op (the SQLDelight impl enforces this in a transaction;
 *     the fake enforces it in a `Mutex`).
 *   * Different identities are isolated.
 *   * `count` and `deleteAll` work as labelled.
 */
class LastSeenSeqRepositoryContractTest {

    private val idA = "aa".repeat(32)
    private val idB = "bb".repeat(32)

    @Test
    fun cold_start_returns_null() = runTest {
        val repo: LastSeenSeqRepository = FakeLastSeenSeqRepository()
        assertNull(repo.getLastSeenSeq(idA))
    }

    @Test
    fun round_trip_persists_value() = runTest {
        val repo: LastSeenSeqRepository = FakeLastSeenSeqRepository()
        repo.upsertLastSeenSeq(idA, seq = 42L, nowMs = 1_000L)
        assertEquals(42L, repo.getLastSeenSeq(idA))
    }

    @Test
    fun monotonic_upsert_accepts_higher_seq() = runTest {
        val repo: LastSeenSeqRepository = FakeLastSeenSeqRepository()
        repo.upsertLastSeenSeq(idA, seq = 10L, nowMs = 1_000L)
        repo.upsertLastSeenSeq(idA, seq = 20L, nowMs = 2_000L)
        assertEquals(20L, repo.getLastSeenSeq(idA))
    }

    @Test
    fun monotonic_upsert_rejects_equal_seq_silently() = runTest {
        val repo: LastSeenSeqRepository = FakeLastSeenSeqRepository()
        repo.upsertLastSeenSeq(idA, seq = 100L, nowMs = 1_000L)
        repo.upsertLastSeenSeq(idA, seq = 100L, nowMs = 2_000L)
        // Value unchanged — equal seqs are silent no-ops per contract.
        assertEquals(100L, repo.getLastSeenSeq(idA))
    }

    @Test
    fun monotonic_upsert_rejects_lower_seq_silently() = runTest {
        val repo: LastSeenSeqRepository = FakeLastSeenSeqRepository()
        repo.upsertLastSeenSeq(idA, seq = 42L, nowMs = 1_000L)
        repo.upsertLastSeenSeq(idA, seq = 30L, nowMs = 2_000L)
        // Cursor must not regress — protects against stale-poll
        // writes that arrive out of order.
        assertEquals(42L, repo.getLastSeenSeq(idA))
    }

    @Test
    fun identities_are_isolated() = runTest {
        val repo: LastSeenSeqRepository = FakeLastSeenSeqRepository()
        repo.upsertLastSeenSeq(idA, seq = 11L, nowMs = 1_000L)
        repo.upsertLastSeenSeq(idB, seq = 22L, nowMs = 1_000L)
        assertEquals(11L, repo.getLastSeenSeq(idA))
        assertEquals(22L, repo.getLastSeenSeq(idB))
    }

    @Test
    fun count_reflects_row_total() = runTest {
        val repo: LastSeenSeqRepository = FakeLastSeenSeqRepository()
        assertEquals(0L, repo.count())
        repo.upsertLastSeenSeq(idA, seq = 1L, nowMs = 1_000L)
        assertEquals(1L, repo.count())
        repo.upsertLastSeenSeq(idB, seq = 2L, nowMs = 1_000L)
        assertEquals(2L, repo.count())
    }

    @Test
    fun delete_all_clears_every_row() = runTest {
        val repo: LastSeenSeqRepository = FakeLastSeenSeqRepository()
        repo.upsertLastSeenSeq(idA, seq = 1L, nowMs = 1_000L)
        repo.upsertLastSeenSeq(idB, seq = 2L, nowMs = 1_000L)
        repo.deleteAll()
        assertEquals(0L, repo.count())
        assertNull(repo.getLastSeenSeq(idA))
        assertNull(repo.getLastSeenSeq(idB))
    }
}
