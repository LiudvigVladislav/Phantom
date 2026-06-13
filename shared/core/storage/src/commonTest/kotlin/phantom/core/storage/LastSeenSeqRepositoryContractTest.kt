// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val first = repo.upsertLastSeenSeq(idA, seq = 10L, nowMs = 1_000L)
        // Cold-start advance: nothing persisted, the write committed.
        assertNull(first, "cold-start write must return null (Advanced)")
        val second = repo.upsertLastSeenSeq(idA, seq = 20L, nowMs = 2_000L)
        assertNull(second, "monotonic forward write must return null (Advanced)")
        assertEquals(20L, repo.getLastSeenSeq(idA))
    }

    @Test
    fun monotonic_upsert_rejects_equal_seq_silently() = runTest {
        val repo: LastSeenSeqRepository = FakeLastSeenSeqRepository()
        repo.upsertLastSeenSeq(idA, seq = 100L, nowMs = 1_000L)
        val noop = repo.upsertLastSeenSeq(idA, seq = 100L, nowMs = 2_000L)
        // C6 review-fix round 3 — the equal-seq no-op MUST be
        // discriminated atomically. Returning `null` here is a smoke
        // proof lie: the orchestrator's `cursor_advanced` log would
        // fire on every relay redelivery.
        assertEquals(
            100L, noop,
            "equal-seq no-op must return the existing persisted seq (NoChange witness)",
        )
        assertEquals(100L, repo.getLastSeenSeq(idA))
    }

    @Test
    fun monotonic_upsert_rejects_lower_seq_silently() = runTest {
        val repo: LastSeenSeqRepository = FakeLastSeenSeqRepository()
        repo.upsertLastSeenSeq(idA, seq = 42L, nowMs = 1_000L)
        val noop = repo.upsertLastSeenSeq(idA, seq = 30L, nowMs = 2_000L)
        // Cursor must not regress — protects against stale-poll
        // writes that arrive out of order. The discriminator MUST
        // be the existing persisted seq.
        assertEquals(
            42L, noop,
            "lower-seq no-op must return the existing persisted seq (NoChange witness)",
        )
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

    @Test
    fun concurrent_writers_share_atomic_outcome_no_false_Advanced_witness() = runTest {
        // C6 review-fix round 4 P2.2 — regression test for the
        // round-2 AppContainer bridge race. The bridge previously
        // did a `getLastSeenSeq` read followed by a SEPARATE
        // `upsertLastSeenSeq` write. Two writers that both observed
        // the same `current` could each go through the "advance"
        // branch, but only the storage transaction enforced
        // monotonicity — the second writer's bridge would still
        // return `Advanced(seq)` while the actual SQLDelight write
        // silently no-opped.
        //
        // With the outcome derived INSIDE the same atomic critical
        // section that decides the write, this race cannot produce
        // a false `Advanced`. The test fires 50 concurrent writers
        // each requesting `seq=N` (1..50) against a cold-start
        // repository.
        //
        // The contract asserted is NOT "exactly one Advanced" — that
        // shape was wrong in round 3's first iteration of this
        // test. With 50 strictly-increasing seqs and arbitrary
        // serialisation order, the number of Advanced outcomes
        // depends on the order: if writers serialise in
        // strictly-increasing order, every call advances (50
        // Advanced); if they serialise in strictly-decreasing
        // order, only one call advances (1 Advanced). The
        // load-bearing claim is:
        //
        //   (a) the final persisted seq is exactly the maximum
        //       requested (50L), proving the highest writer's
        //       transaction landed;
        //   (b) every NoChange witness is `<=` the final persisted
        //       seq (a witness greater than the final stored value
        //       would mean the repo at some point reported a value
        //       it never actually held — impossible under
        //       monotonicity);
        //   (c) at least one Advanced fires (the cold-start
        //       advance always commits).
        //
        // **Coverage split.** This test exercises
        // [FakeLastSeenSeqRepository] only. The SQLDelight-backed
        // production impl is exercised by
        // `SqlDelightLastSeenSeqRepositoryInstrumentedTest` in
        // `androidInstrumentedTest` against a real
        // `AndroidSqliteDriver`. Run with
        // `./gradlew :shared:core:storage:connectedDebugAndroidTest`.
        val repo: LastSeenSeqRepository = FakeLastSeenSeqRepository()
        val outcomes: List<Long?> = coroutineScope {
            (1..50).map { seq ->
                async {
                    repo.upsertLastSeenSeq(idA, seq = seq.toLong(), nowMs = seq * 1_000L)
                }
            }.awaitAll()
        }
        // Cold-start window: the FIRST write that lands always
        // commits (the persisted cell is null). The Mutex
        // serialisation means writes land in some unspecified
        // order. With a sequence of strictly-increasing requested
        // seqs across 50 concurrent calls, EVERY call ends up
        // advancing the cursor IF it sees a current value strictly
        // less than its requested seq, OR no-ops otherwise. There
        // is no general bound on the number of advances vs
        // no-changes for arbitrary input shapes; but `current`
        // monotonically grows so once a write of seq=K commits, all
        // subsequent writes of seq < K no-op. Asserting on the
        // FINAL state + the consistency of outcomes is the right
        // contract:
        val finalSeq = repo.getLastSeenSeq(idA)
        assertEquals(50L, finalSeq, "final persisted seq must be the maximum requested (50)")
        // Every Advanced outcome (null) MUST correspond to a write
        // that genuinely committed the next forward value at the
        // moment of its critical section. The aggregate witness:
        // there is exactly one transition from `null` (cold start)
        // to the final 50L, so the number of Advanced outcomes
        // equals the number of strictly-increasing forward writes
        // that hit the storage in order. Two assertions express
        // that:
        //
        //   (a) At least one Advanced (someone wrote the value 50
        //       since `finalSeq == 50L` AND no path to that value
        //       exists without an Advanced).
        //   (b) Every NoChange witness must be `<= 50` (the running
        //       persisted value at the time of decision).
        val advancedCount = outcomes.count { it == null }
        val noChangeWitnesses: List<Long> = outcomes.filterNotNull()
        assertTrue(
            advancedCount >= 1,
            "expected at least one Advanced outcome; got $advancedCount " +
                "across ${outcomes.size} concurrent writers",
        )
        assertTrue(
            noChangeWitnesses.all { witness -> witness <= 50L },
            "every NoChange witness must be a value the repo had observed; " +
                "found witnesses out of range: " +
                "${noChangeWitnesses.filter { witness -> witness > 50L }}",
        )
        // No witness can exceed the final stored value — this is
        // the load-bearing pin: a witness greater than `finalSeq`
        // would mean the repo at some point reported "persisted=K"
        // while the actual final state was less than K, which is
        // impossible under monotonicity.
        val finalSeqValue = finalSeq ?: Long.MIN_VALUE
        assertTrue(
            noChangeWitnesses.all { witness -> witness <= finalSeqValue },
            "every NoChange witness must be <= the final stored seq " +
                "($finalSeqValue); got witnesses out of range: " +
                "${noChangeWitnesses.filter { witness -> witness > finalSeqValue }}",
        )
    }

    @Test
    fun seeded_high_then_concurrent_lower_writers_yield_zero_advanced() = runTest {
        // C6 review-fix round 6 — corrected race-coverage pin
        // (replaces the round-5 cold-descending shape which
        // admitted up-to-50 legitimate Advanced events as long as
        // each commit observed `current < requested`).
        //
        // **Deterministic shape A** — seed the cursor at 50, then
        // fire 49 concurrent writers requesting (49 downTo 1). The
        // atomicity contract says: EVERY one of them MUST observe
        // `current >= requested` and return `NoChange(50)`. Zero
        // Advanced. Any Advanced fired here is a false-Advanced
        // bug — exactly the regression Vladislav flagged as the
        // round-2 race shape: a bridge-style read-before-write
        // could let an outdated `current` reach the "advance"
        // branch.
        val repo: LastSeenSeqRepository = FakeLastSeenSeqRepository()
        // Seed the high value.
        val seedOutcome = repo.upsertLastSeenSeq(idA, seq = 50L, nowMs = 1L)
        assertEquals(null, seedOutcome, "seed must commit Advanced on a cold cursor")
        val outcomes: List<Long?> = coroutineScope {
            (49 downTo 1).map { seq ->
                async {
                    repo.upsertLastSeenSeq(idA, seq = seq.toLong(), nowMs = seq * 1_000L)
                }
            }.awaitAll()
        }
        assertEquals(
            0, outcomes.count { it == null },
            "with cursor seeded at 50, EVERY one of the 49 lower-seq writers MUST return " +
                "NoChange. Any Advanced here is a false-Advanced from a bridge race.",
        )
        val witnesses = outcomes.filterNotNull()
        assertEquals(49, witnesses.size, "expected 49 NoChange outcomes")
        assertTrue(
            witnesses.all { it == 50L },
            "every NoChange witness MUST equal the seeded value (50). " +
                "Out-of-range: ${witnesses.filter { it != 50L }}",
        )
        assertEquals(50L, repo.getLastSeenSeq(idA), "persisted cursor unchanged")
    }

    @Test
    fun seeded_just_below_then_concurrent_equal_writers_yield_exactly_one_advanced() = runTest {
        // **Deterministic shape B** — seed the cursor at 49, then
        // fire 50 concurrent writers ALL requesting seq=50. The
        // atomicity contract says: EXACTLY ONE writer transitions
        // 49 → 50 (returns null / Advanced); the other 49 see
        // `current == 50 >= requested` and return `NoChange(50)`.
        // Order of execution doesn't matter — only one transaction
        // can be the strict-greater-than winner.
        //
        // A false-Advanced bug surfaces directly: if two writers
        // each observed `current=49` and each branched into the
        // write, the bridge would report two Advanced outcomes
        // even though only one transaction actually committed the
        // 49 → 50 transition.
        val repo: LastSeenSeqRepository = FakeLastSeenSeqRepository()
        val seedOutcome = repo.upsertLastSeenSeq(idA, seq = 49L, nowMs = 1L)
        assertEquals(null, seedOutcome, "seed must commit Advanced on a cold cursor")
        val outcomes: List<Long?> = coroutineScope {
            (1..50).map { _ ->
                async {
                    repo.upsertLastSeenSeq(idA, seq = 50L, nowMs = 2L)
                }
            }.awaitAll()
        }
        assertEquals(
            1, outcomes.count { it == null },
            "all-equal seq=50 against cursor seeded at 49 MUST yield exactly ONE Advanced. " +
                "A higher count is a false-Advanced from a bridge race: multiple writers " +
                "each saw stale `current=49` and each was reported as Advanced.",
        )
        val witnesses = outcomes.filterNotNull()
        assertEquals(49, witnesses.size, "expected 49 NoChange outcomes")
        assertTrue(
            witnesses.all { it == 50L },
            "every NoChange witness MUST equal the post-commit value (50). " +
                "Out-of-range: ${witnesses.filter { it != 50L }}",
        )
        assertEquals(50L, repo.getLastSeenSeq(idA))
    }
}
