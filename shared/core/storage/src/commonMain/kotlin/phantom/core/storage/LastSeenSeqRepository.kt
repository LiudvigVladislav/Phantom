// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

/**
 * Persisted long-poll resume cursor for the client's `/relay/poll`
 * `?since_seq=<n>` query — Trek 2 Stage 2A (A3).
 *
 * The Stage 1 server (master `dc6f52df`) assigns every envelope queued
 * for a recipient a monotonic `seq` counter. The client uses
 * [getLastSeenSeq] at cold start to seed its in-memory cursor, and
 * [upsertLastSeenSeq] after every successful poll that observed a new
 * `seq` to persist the increment.
 *
 * KEY SHAPE
 *
 * Single-key `(identity_hex)` per Vladislav OQ6 lock 2026-06-09. The
 * relay's `rest_seq` counter is per-recipient (not per-conversation),
 * so the client cursor mirrors that shape exactly. Composite
 * `(identity, conversationId)` would over-specify and create a mismatch
 * with the server's `?since_seq` semantics.
 *
 * MONOTONICITY
 *
 * The [upsertLastSeenSeq] contract is "store the *new* value, but never
 * regress". Implementations enforce a strict greater-than check at the
 * Kotlin layer (inside a database transaction in the SQLDelight impl,
 * inside a `Mutex` in the in-memory test fake) so the SQL stays simple
 * (`INSERT OR REPLACE` without a WHERE-cas-on-seq subquery).
 *
 * STAGE 2A vs 2B
 *
 * Stage 2A (this commit) introduces the interface + a SQLDelight-backed
 * implementation + a test fake. The repository is NOT yet wired into
 * `RestFallbackOrchestrator.pollLoop` at runtime — that wire-up is a
 * Stage 2B deliverable. This commit is purely additive plumbing.
 *
 * STORAGE
 *
 * Backed by the `transport_seq_state` table in the existing
 * SQLCipher-protected `phantom.db` per OQ5 lock 2026-06-09. See
 * `TransportSeqState.sq` for the schema and `20.sqm` for the migration.
 */
interface LastSeenSeqRepository {

    /**
     * Reads the persisted resume cursor for [identityHex]. Returns
     * `null` when no cursor has ever been written for this identity
     * (cold start, first run). The Stage 2B caller treats `null`
     * identically to `0L` — both mean "request the relay with
     * `since_seq=0`, i.e. send me anything you have in the retention
     * window".
     */
    suspend fun getLastSeenSeq(identityHex: String): Long?

    /**
     * Persists [seq] as the latest observed cursor for [identityHex].
     * Monotonic: a call with [seq] less than or equal to the current
     * persisted value is a silent no-op. The Stage 2B caller invokes
     * this after every poll that observed a new server-assigned `seq`,
     * passing the wall-clock [nowMs] for diagnostics.
     */
    suspend fun upsertLastSeenSeq(identityHex: String, seq: Long, nowMs: Long)

    /**
     * Diagnostics — returns the count of rows (typically one per
     * identity on the device). Stage 2A integration tests use this
     * to assert the migration created the table at all.
     */
    suspend fun count(): Long

    /** Wipes the table. Test-reset paths only. */
    suspend fun deleteAll()
}
