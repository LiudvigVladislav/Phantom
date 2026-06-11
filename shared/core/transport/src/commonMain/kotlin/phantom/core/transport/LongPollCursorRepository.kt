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
     * Called only by the orchestrator's `ackInboundAndAdvanceCursor`
     * after the relay's `/relay/ack-deliver` has returned 2xx. The
     * call site bounds retry via the orchestrator's
     * `CURSOR_WRITE_MAX_ATTEMPTS` / `CURSOR_WRITE_RETRY_BACKOFF_MS`
     * companion constants; this interface does not retry internally.
     */
    suspend fun upsertLastSeenSeq(identityHex: String, seq: Long, nowMs: Long)
}
