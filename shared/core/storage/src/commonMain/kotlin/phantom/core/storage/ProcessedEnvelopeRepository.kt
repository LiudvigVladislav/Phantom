// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

/**
 * Idempotent receive ledger (PR-H2b).
 *
 * Records every envelope id that has been fed to `ratchet.decrypt` —
 * regardless of payload type — so that a duplicate redelivery of the
 * same ciphertext (relay → recipient redelivery loop after a lost
 * ack-deliver) cannot reach the ratchet a second time and trigger a
 * MAC failure on the now-advanced chain key.
 *
 * See `ProcessedEnvelope.sq` for the table schema and full rationale.
 *
 * The repository is intentionally narrow: a single `exists` check on
 * the receive hot path, a single `markProcessed` write after decrypt,
 * and a TTL-driven cleanup. Anything richer belongs in a separate
 * audit/diagnostics module.
 */
interface ProcessedEnvelopeRepository {

    /** Status of a recorded envelope. Stored as the literal string in the DB. */
    enum class Status(val wire: String) {
        /** Decrypt succeeded, payload was acted on, ack-deliver was sent. */
        PROCESSED("processed"),

        /**
         * Decrypt failed MAC verification on the FIRST attempt — chain
         * already diverged before we ever saw this envelope (legacy
         * pre-migration ciphertext, future skip-key gap, etc). The
         * envelope is ack-delivered to the relay and recorded so a
         * subsequent redelivery of the same id no-ops cleanly.
         */
        FAILED_MAC("failed_mac"),
    }

    /**
     * Hot-path check: has this envelope id ever been fed to `ratchet.decrypt`
     * (successfully or not)? Returns `true` when the receive path should
     * send ack-deliver and skip without touching the ratchet.
     */
    suspend fun exists(envelopeId: String): Boolean

    /**
     * Records that we have processed (or attempted to process) the given
     * envelope. Idempotent — a second call with the same `envelopeId`
     * is a silent no-op (INSERT OR IGNORE) so a rare race between two
     * concurrent receive coroutines does not propagate a unique-constraint
     * exception up the messaging error path.
     */
    suspend fun markProcessed(
        envelopeId: String,
        conversationId: String,
        senderPubKeyHex: String,
        payloadType: String,
        status: Status,
        nowMs: Long,
    )

    /** Evicts ledger rows older than [olderThanMs] (epoch ms). Returns nothing. */
    suspend fun deleteOlderThan(olderThanMs: Long)

    /**
     * Diagnostics — total processed-vs-failed_mac counts across the entire
     * ledger. Not used in any hot path; intended for the future
     * "X messages failed to decrypt" Settings indicator (deferred to Beta).
     */
    suspend fun countByStatus(): Map<Status, Long>

    /** Wipes the ledger. Used by reset-app flows and instrumentation tests. */
    suspend fun deleteAll()
}
