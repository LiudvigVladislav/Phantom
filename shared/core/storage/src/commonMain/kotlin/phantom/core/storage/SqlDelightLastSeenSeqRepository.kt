// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

/**
 * SQLDelight-backed [LastSeenSeqRepository] — Trek 2 Stage 2A (A3).
 *
 * Hot-path footprint per call: one PK lookup + (for upsert) one
 * INSERT OR REPLACE inside a single transaction. Both run on
 * `Dispatchers.IO` to keep the receive coroutine off the main
 * dispatcher, mirroring the [SqlDelightProcessedEnvelopeRepository]
 * shape.
 *
 * MONOTONICITY
 *
 * [upsertLastSeenSeq] runs the read-then-write inside a single
 * `db.transaction { ... }` block so two concurrent producers cannot
 * race past the guard check and silently regress the cursor. In
 * practice the long-poll loop is single-coroutine per identity so
 * the contention is rare; the transaction is defence-in-depth.
 *
 * C6 REVIEW-FIX ROUND 3 — ATOMIC OUTCOME
 *
 * The discriminator (`null` for advance, `Long(existingSeq)` for the
 * monotonicity no-op) is derived INSIDE the same
 * `transactionWithResult` block that decides the conditional write.
 * A bridge-level read-before-write was the round-2 race that this
 * landing closes.
 *
 * TEST COVERAGE (round-4 P2.2)
 *
 * Tested in two layers:
 *
 *   1. **JVM cross-module contract.** `LastSeenSeqRepositoryContractTest`
 *      exercises the same atomicity contract on the in-memory
 *      `FakeLastSeenSeqRepository` (the `mutex.withLock` analogue
 *      of `transactionWithResult`).
 *   2. **Android-runtime SQLDelight contract.**
 *      `SqlDelightLastSeenSeqRepositoryInstrumentedTest` in
 *      `androidInstrumentedTest` exercises THIS class against an
 *      in-memory `AndroidSqliteDriver`, pinning the cold-start
 *      advance / forward advance / equal-seq no-op / lower-seq
 *      no-op / identity-isolation / concurrent-writers cases on
 *      the real SQLDelight path. Invoke with
 *      `./gradlew :shared:core:storage:connectedDebugAndroidTest`
 *      against a connected device or emulator.
 */
class SqlDelightLastSeenSeqRepository(
    private val db: PhantomDatabase,
) : LastSeenSeqRepository {

    override suspend fun getLastSeenSeq(identityHex: String): Long? =
        withContext(Dispatchers.IO) {
            db.transportSeqStateQueries
                .getLastSeenSeq(identityHex)
                .executeAsOneOrNull()
        }

    override suspend fun upsertLastSeenSeq(
        identityHex: String,
        seq: Long,
        nowMs: Long,
    ): Long? = withContext(Dispatchers.IO) {
        // C6 review-fix round 3 — atomic outcome derived INSIDE the
        // transaction. A concurrent writer that races between the
        // `getLastSeenSeq` read and the `upsertLastSeenSeq` write
        // still produces the correct discriminator at the call site
        // because both the read and the conditional write live in
        // one transactional unit.
        db.transactionWithResult<Long?> {
            val current = db.transportSeqStateQueries
                .getLastSeenSeq(identityHex)
                .executeAsOneOrNull()
            if (current != null && current >= seq) {
                // Monotonicity guard — silent no-op when the caller
                // tries to regress (or no-op) the cursor. Returns the
                // currently-persisted value as the NoChange witness.
                return@transactionWithResult current
            }
            db.transportSeqStateQueries.upsertLastSeenSeq(
                identity_hex = identityHex,
                last_seen_seq = seq,
                updated_at_ms = nowMs,
            )
            null
        }
    }

    override suspend fun count(): Long =
        withContext(Dispatchers.IO) {
            db.transportSeqStateQueries.countRows().executeAsOne()
        }

    override suspend fun deleteAll(): Unit =
        withContext(Dispatchers.IO) {
            db.transportSeqStateQueries.deleteAll()
        }
}
