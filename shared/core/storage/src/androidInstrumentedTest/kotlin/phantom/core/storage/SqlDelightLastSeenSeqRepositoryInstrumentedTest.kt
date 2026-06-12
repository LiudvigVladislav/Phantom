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
    fun descending_concurrent_writers_yield_exactly_one_advanced() = runTest {
        // C6 review-fix round 5 (tester P1) — strong race-coverage pin
        // against the real SQLDelight `transactionWithResult` block.
        // See the JVM contract test's commentary for the rationale:
        // descending input against a cold cursor is the
        // discriminating shape because every loser sees `current`
        // strictly greater than its requested seq and MUST return a
        // NoChange witness equal to the persisted winning seq.
        val outcomes: List<Long?> = coroutineScope {
            (50 downTo 1).map { seq ->
                async {
                    repo.upsertLastSeenSeq(idA, seq = seq.toLong(), nowMs = seq * 1_000L)
                }
            }.awaitAll()
        }
        val advancedCount = outcomes.count { it == null }
        assertEquals(
            1, advancedCount,
            "descending shape MUST yield exactly one Advanced outcome; got $advancedCount",
        )
        val winningSeq = repo.getLastSeenSeq(idA) ?: error("repo must hold the winner's seq")
        val witnesses = outcomes.filterNotNull()
        assertEquals(49, witnesses.size, "expected 49 NoChange outcomes")
        assertTrue(
            witnesses.all { witness -> witness == winningSeq },
            "every NoChange witness MUST equal the persisted winning seq ($winningSeq); " +
                "out-of-range: ${witnesses.filter { it != winningSeq }}",
        )
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
