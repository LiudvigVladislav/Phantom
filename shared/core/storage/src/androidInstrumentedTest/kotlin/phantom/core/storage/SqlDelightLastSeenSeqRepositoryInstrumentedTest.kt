// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import phantom.core.storage.db.PhantomDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 4 P2.2) — instrumented test
 * for [SqlDelightLastSeenSeqRepository] proving the atomic
 * `Long?` outcome contract holds against a REAL SQLDelight
 * `transactionWithResult` block (not the in-memory
 * `mutex.withLock` of the Fake).
 *
 * The cross-module Fake-side contract test in
 * `LastSeenSeqRepositoryContractTest` exercises the same atomicity
 * invariants on the in-memory `FakeLastSeenSeqRepository`. This
 * instrumented test closes the round-3 review-fix gap by exercising
 * the production storage path end-to-end on a real device or
 * emulator.
 *
 * Invoke with:
 *   ./gradlew :shared:core:storage:connectedDebugAndroidTest
 *
 * Uses an unencrypted in-memory `AndroidSqliteDriver` (no SQLCipher
 * passphrase, no on-disk file) so the test does not interact with the
 * production database file and leaves no residue between runs.
 */
@RunWith(AndroidJUnit4::class)
class SqlDelightLastSeenSeqRepositoryInstrumentedTest {

    private lateinit var driver: AndroidSqliteDriver
    private lateinit var db: PhantomDatabase
    private lateinit var repo: SqlDelightLastSeenSeqRepository

    private val idA = "aa".repeat(32)
    private val idB = "bb".repeat(32)

    @BeforeTest
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        driver = AndroidSqliteDriver(
            schema = PhantomDatabase.Schema,
            context = context,
            // Null name = in-memory database; nothing touches disk.
            name = null,
        )
        db = PhantomDatabase(driver)
        repo = SqlDelightLastSeenSeqRepository(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun cold_start_advance_returns_null() = runTest {
        val outcome = repo.upsertLastSeenSeq(idA, seq = 10L, nowMs = 1_000L)
        assertNull(
            outcome,
            "cold-start advance MUST return null (Advanced); got $outcome",
        )
        assertEquals(10L, repo.getLastSeenSeq(idA))
    }

    @Test
    fun forward_advance_returns_null() = runTest {
        repo.upsertLastSeenSeq(idA, seq = 10L, nowMs = 1_000L)
        val outcome = repo.upsertLastSeenSeq(idA, seq = 20L, nowMs = 2_000L)
        assertNull(
            outcome,
            "monotonic forward advance MUST return null (Advanced); got $outcome",
        )
        assertEquals(20L, repo.getLastSeenSeq(idA))
    }

    @Test
    fun equal_seq_returns_existing_value_NoChange() = runTest {
        repo.upsertLastSeenSeq(idA, seq = 100L, nowMs = 1_000L)
        val noop = repo.upsertLastSeenSeq(idA, seq = 100L, nowMs = 2_000L)
        // The atomic outcome MUST be the existing persisted seq
        // (NoChange witness) — a `null` return here would mean the
        // SQLDelight implementation lost its in-transaction
        // monotonicity discriminator, allowing the orchestrator to
        // emit a false `cursor_advanced` smoke proof.
        assertEquals(
            100L, noop,
            "equal-seq no-op MUST return the existing persisted seq (NoChange witness)",
        )
        assertEquals(100L, repo.getLastSeenSeq(idA))
    }

    @Test
    fun lower_seq_returns_existing_value_NoChange() = runTest {
        repo.upsertLastSeenSeq(idA, seq = 42L, nowMs = 1_000L)
        val noop = repo.upsertLastSeenSeq(idA, seq = 30L, nowMs = 2_000L)
        assertEquals(
            42L, noop,
            "lower-seq no-op MUST return the existing persisted seq (NoChange witness)",
        )
        assertEquals(42L, repo.getLastSeenSeq(idA))
    }

    @Test
    fun identities_are_isolated_atomic_outcomes_per_identity() = runTest {
        // idA at seq=10, idB at seq=20. A no-op on idA's seq=5 MUST
        // witness idA's seq=10 — NOT leak idB's seq=20.
        repo.upsertLastSeenSeq(idA, seq = 10L, nowMs = 1_000L)
        repo.upsertLastSeenSeq(idB, seq = 20L, nowMs = 1_000L)
        val noopA = repo.upsertLastSeenSeq(idA, seq = 5L, nowMs = 2_000L)
        assertEquals(
            10L, noopA,
            "NoChange witness must be the SAME identity's persisted seq, not another's",
        )
        // Cross-check the other identity is untouched.
        assertEquals(20L, repo.getLastSeenSeq(idB))
    }

    @Test
    fun seeded_high_then_concurrent_lower_writers_yield_zero_advanced() = runTest {
        // C6 review-fix round 6 — corrected race-coverage pin
        // against the real SQLDelight `transactionWithResult`. See
        // the JVM contract test's commentary for the rationale: a
        // cold-descending shape admits up-to-50 legitimate Advanced
        // events (each commit could observe `current < requested`
        // and honestly advance), so the round-5 "exactly one
        // Advanced" claim was structurally wrong.
        //
        // Deterministic shape A: seed at 50, fire (49 downTo 1)
        // concurrent. The atomicity contract pins zero Advanced
        // because every writer observes `current=50 >= requested`.
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
                "NoChange. Any Advanced here is a false-Advanced bug.",
        )
        val witnesses = outcomes.filterNotNull()
        assertEquals(49, witnesses.size, "expected 49 NoChange outcomes")
        assertTrue(
            witnesses.all { it == 50L },
            "every NoChange witness MUST equal the seeded value (50). " +
                "Out-of-range: ${witnesses.filter { it != 50L }}",
        )
        assertEquals(50L, repo.getLastSeenSeq(idA))
    }

    @Test
    fun seeded_just_below_then_concurrent_equal_writers_yield_exactly_one_advanced() = runTest {
        // Deterministic shape B: seed at 49, fire 50 concurrent
        // writers ALL requesting seq=50. The atomicity contract
        // pins exactly one Advanced (the single 49 → 50 transition)
        // independent of execution order.
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
            "all-equal seq=50 against cursor seeded at 49 MUST yield exactly ONE Advanced; " +
                "a higher count is a false-Advanced bridge race.",
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

    @Test
    fun concurrent_writers_no_false_Advanced_witness() = runTest {
        // 50 concurrent writers each request seq=N (1..50) against a
        // cold-start cursor. The SQLDelight `transactionWithResult`
        // block serialises the read-then-write pair atomically; a
        // false `Advanced(N)` for some N < finalSeq would mean the
        // bridge-style read-before-write race had crept back into
        // the storage implementation.
        val outcomes: List<Long?> = coroutineScope {
            (1..50).map { seq ->
                async {
                    repo.upsertLastSeenSeq(idA, seq = seq.toLong(), nowMs = seq * 1_000L)
                }
            }.awaitAll()
        }
        val finalSeq = repo.getLastSeenSeq(idA) ?: error("repo must hold a value")
        assertEquals(50L, finalSeq, "final persisted seq MUST be the maximum requested (50)")
        val advancedCount = outcomes.count { it == null }
        assertTrue(
            advancedCount >= 1,
            "at least one Advanced outcome must fire (cold-start advance); " +
                "got $advancedCount across ${outcomes.size} writers",
        )
        val noChangeWitnesses = outcomes.filterNotNull()
        assertTrue(
            noChangeWitnesses.all { witness -> witness <= finalSeq },
            "every NoChange witness must be <= the final stored seq ($finalSeq); " +
                "out-of-range: ${noChangeWitnesses.filter { it > finalSeq }}",
        )
    }
}
