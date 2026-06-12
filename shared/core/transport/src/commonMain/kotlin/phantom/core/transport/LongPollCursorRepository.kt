// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * Trek 2 Stage 2B-B (L4) — full read/write cursor seam used by both
 * REST poll loops in [RestFallbackOrchestrator].
 *
 * Replaces the Stage 2B-A read-only [LongPollCursorReader] SAM seam as
 * the orchestrator's primary constructor parameter. [LongPollCursorReader]
 * is RETAINED in the codebase as the canonical read-only contract for
 * callers that genuinely only need to read (e.g. diagnostic tooling,
 * legacy bridge clients that have not yet been migrated). The
 * orchestrator itself owns writes through this interface once Stage
 * 2B-B's cursor-advance pipeline lands.
 *
 * **Why a regular `interface`, not a `fun interface`.** Kotlin SAM
 * constructors model exactly one method; the read/write seam carries
 * two. The AppContainer bridge lambda upgrades from the Stage 2B-A
 * single-arrow SAM to a regular object literal implementing both
 * methods.
 *
 * **Concurrency.** Read and write are both `suspend` and called only
 * from the orchestrator's coroutine scope. The orchestrator serialises
 * writes through its own `_inboundStateMutex` for the in-memory
 * pending-seq mapping, but the persistent write itself happens
 * OUTSIDE that mutex (storage I/O must never hold a contended
 * orchestrator-scoped lock). The underlying SQLDelight implementation
 * (`SqlDelightLastSeenSeqRepository`) enforces the monotonicity
 * invariant inside its own transaction — see [upsertLastSeenSeq].
 *
 * **Lock L4 (scope-doc).** Both REST poll loops share the persisted
 * cursor via this seam: the legacy `pollLoop`'s in-memory
 * `lastSeenSeq` variable is decommissioned and replaced by a
 * `getLastSeenSeq(identityHex)` call at the start of every iteration.
 */
interface LongPollCursorRepository {

    /**
     * Read the persisted resume cursor for [identityHex]. Returns
     * `null` when no cursor has ever been written for this identity
     * (cold start, first run).
     *
     * Both poll loops treat `null` identically to `0L` — both mean
     * "request the relay with `since_seq=0`, i.e. send me anything
     * you have in the retention window".
     */
    suspend fun getLastSeenSeq(identityHex: String): Long?

    /**
     * Persist the resume cursor for [identityHex] at value [seq] with
     * the wall-clock observation timestamp [nowMs]. Idempotent on
     * monotonic values: a `seq` less than or equal to the currently-
     * persisted value is a no-op (the underlying repository enforces
     * monotonicity per Stage 2A D5).
     *
     * **C6 review-fix round 2 — typed outcome.** The call returns a
     * [CursorUpsertOutcome] so the orchestrator can discriminate
     *
     *   * [CursorUpsertOutcome.Advanced] — the persisted value
     *     genuinely changed; the underlying storage now holds [seq].
     *   * [CursorUpsertOutcome.NoChange] — the monotonicity guard
     *     short-circuited the write because the persisted value was
     *     already `>= seq`. The Tele2 smoke runbook treats this
     *     case as NOT proof of cursor advance — the
     *     `REST_TRACE cursor_advanced seq=<n>` log MUST NOT fire
     *     on this branch or the field evidence becomes unfalsifiable.
     *
     * Called only by the orchestrator's `ackInboundAndAdvanceCursor`
     * after the relay's `/relay/ack-deliver` has returned 2xx. The
     * call site bounds retry via the orchestrator's
     * `CURSOR_WRITE_MAX_ATTEMPTS` / `CURSOR_WRITE_RETRY_BACKOFF_MS`
     * companion constants; this interface does not retry internally.
     *
     * Implementations MAY throw on unrelated I/O errors (database
     * locked, disk full, etc.). The caller's retry loop catches
     * those and routes them through the `poll_cursor_write_attempt_fail`
     * telemetry path; they are NOT [CursorUpsertOutcome] cases.
     */
    suspend fun upsertLastSeenSeq(identityHex: String, seq: Long, nowMs: Long): CursorUpsertOutcome
}

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 2 P1.2; atomic in round 3)
 * — outcome of a [LongPollCursorRepository.upsertLastSeenSeq] call.
 * Discriminates a genuine cursor advance from the silent no-op a
 * monotonicity guard produces when the caller tries to write a `seq`
 * less than or equal to the persisted value.
 *
 * The Tele2 LTE smoke runbook (`docs/tracks/trek2-stage2b-b-tele2-
 * smoke.md`) requires the `REST_TRACE cursor_advanced seq=<n>` log
 * line as a proof point that envelopes are being persistently
 * dequeued in the field. Before this outcome existed, the
 * orchestrator emitted the line on every successful return from
 * `upsertLastSeenSeq` — including the silent-no-op branch — which
 * meant the smoke proof was a lie any time a relay redelivered an
 * envelope already past the cursor (the legitimate dedup path).
 *
 * **Atomicity (round 3).** The discriminator is derived INSIDE the
 * storage layer's transactional critical section (SQLDelight
 * `transactionWithResult` / Fake `mutex.withLock`). A concurrent-
 * writer race cannot produce a false `Advanced` because the same
 * critical section that decides the conditional write returns the
 * outcome value. With this discriminated outcome, the smoke proof
 * "cursor is persisted with strictly-monotonic seq" is genuine: a
 * `cursor_advanced` line in logcat means the underlying row in
 * `transport_seq_state` actually changed in this transaction.
 */
sealed class CursorUpsertOutcome {

    /**
     * The repository stored [storedSeq] as the new persisted cursor
     * value; the previous value was strictly less than [storedSeq]
     * (or absent for a cold-start identity).
     */
    data class Advanced(val storedSeq: Long) : CursorUpsertOutcome()

    /**
     * The repository observed a persisted value of [existingSeq]
     * that was `>=` the caller's requested seq, and short-circuited
     * the write per the monotonicity contract. The persisted row
     * is unchanged.
     */
    data class NoChange(val existingSeq: Long) : CursorUpsertOutcome()
}
