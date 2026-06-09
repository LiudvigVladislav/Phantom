// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [LastSeenSeqRepository] for commonTest / KMP test fakes —
 * Trek 2 Stage 2A (A3).
 *
 * Mirrors the SQLDelight impl's monotonicity guarantee using a
 * [Mutex] around the in-memory map so contract tests can target the
 * fake and the real impl with identical assertions. Stage 2A test
 * coverage in A8 exercises this fake; the SQLDelight impl is covered
 * by an `androidInstrumentedTest` against a real SQLCipher database.
 */
class FakeLastSeenSeqRepository : LastSeenSeqRepository {

    private val mutex = Mutex()
    private val cursors = mutableMapOf<String, Long>()

    override suspend fun getLastSeenSeq(identityHex: String): Long? =
        mutex.withLock { cursors[identityHex] }

    override suspend fun upsertLastSeenSeq(
        identityHex: String,
        seq: Long,
        @Suppress("UNUSED_PARAMETER") nowMs: Long,
    ): Unit = mutex.withLock {
        val current = cursors[identityHex]
        if (current == null || current < seq) {
            cursors[identityHex] = seq
        }
        // Monotonicity guard — silent no-op when caller tries to
        // regress the cursor. Matches SQLDelight impl semantics.
    }

    override suspend fun count(): Long =
        mutex.withLock { cursors.size.toLong() }

    override suspend fun deleteAll(): Unit =
        mutex.withLock { cursors.clear() }
}
