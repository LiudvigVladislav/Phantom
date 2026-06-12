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
     *
     * **C6 review-fix round 3 — atomic outcome.** The return value
     * discriminates a genuine advance from the monotonicity no-op
     * INSIDE the SQLDelight transaction (or the in-memory mutex for
     * the test fake) so the orchestrator's `cursor_advanced` smoke
     * proof cannot lie under a concurrent-writer race:
     *
     *   * `null` — the write committed; the persisted row now holds
     *     [seq]. The orchestrator emits `REST_TRACE cursor_advanced
     *     seq=<n>`.
     *   * `Long` — the monotonicity guard short-circuited the write;
     *     the persisted row holds the returned value (which is
     *     `>= seq`) and is unchanged. The orchestrator emits
     *     `REST_TRACE cursor_noop existing_seq=<n>`.
     *
     * Atomicity is load-bearing: read-then-write split across two
     * suspending calls outside the transaction (the round-2
     * AppContainer bridge) gave the wrong answer under a
     * concurrent-writer race where two coroutines each read the
     * same `current`, both decided to advance, but the SECOND
     * INSERT-OR-REPLACE silently no-opped at the storage layer
     * while the bridge still returned `Advanced(seq)`. With this
     * outcome derived inside the same transaction that decides the
     * write, the contract holds regardless of contention.
     */
    suspend fun upsertLastSeenSeq(identityHex: String, seq: Long, nowMs: Long): Long?

    /**
     * Diagnostics — returns the count of rows (typically one per
     * identity on the device). Stage 2A integration tests use this
     * to assert the migration created the table at all.
     */
    suspend fun count(): Long

    /** Wipes the table. Test-reset paths only. */
    suspend fun deleteAll()
}
