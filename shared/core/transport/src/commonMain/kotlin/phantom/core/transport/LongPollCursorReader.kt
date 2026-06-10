// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * Trek 2 Stage 2B-A (B3, L4) — read-only seam for the parallel
 * `wsActivePollJob`'s resume-cursor source.
 *
 * The orchestrator receives a function that yields the persisted
 * `lastSeenSeq` for the given identity hex, but has NO write API —
 * making it STRUCTURALLY IMPOSSIBLE for Stage 2B-A code to advance
 * the cursor. Lock L4 of the scope mini-lock pins this invariant:
 * Stage 2B-A reads from the cursor but writes nothing back. Stage
 * 2B-B will replace this single-method interface with a full
 * read/write seam guarded by `seq_mac` verify + storage dedup,
 * at which point the writes become safe.
 *
 * The interface stays in `shared:core:transport` so the orchestrator
 * does not gain a cross-module dependency on `shared:core:storage`.
 * The wire-up layer (AppContainer) bridges the `SqlDelight`-backed
 * `LastSeenSeqRepository` from storage to this read-only seam with
 * a single lambda — the `getLastSeenSeq` method shape is identical.
 *
 * SAM constructor is intentional: a unit test can supply a one-line
 * fake without declaring a class.
 *
 * @see RestFallbackOrchestrator.start
 */
fun interface LongPollCursorReader {

    /**
     * Reads the persisted resume cursor for [identityHex]. Returns
     * `null` when no cursor has ever been written for this identity
     * (cold start, first run).
     *
     * The Stage 2B-A caller treats `null` identically to `0L` —
     * both mean "request the relay with `since_seq=0`, i.e. send me
     * anything you have in the retention window".
     */
    suspend fun getLastSeenSeq(identityHex: String): Long?
}
