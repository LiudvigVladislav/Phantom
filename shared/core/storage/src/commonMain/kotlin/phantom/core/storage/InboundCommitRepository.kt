// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

/**
 * N1-F1 R-N1.8 — atomic settlement of an inbound user message.
 *
 * ## The window this closes
 *
 * The receive path used to write the processed-envelope ledger
 * immediately after `ratchet.decrypt` succeeded, roughly 1350 lines
 * before the message row was inserted. A process death anywhere in
 * between left the ledger saying PROCESSED with no row to show for it.
 * On redelivery the dedupe gate saw the ledger entry, sent
 * `ack-deliver` and skipped — so the relay dropped the envelope and the
 * message was gone permanently, with no error anywhere.
 *
 * The two writes live in the same SQLDelight database
 * (`message` and `processed_envelopes`), so the fix is the ordinary one:
 * commit them in a SINGLE transaction. Either both land or neither
 * does, and the caller may only `ack-deliver` after this returns.
 *
 * There is no ordering to get wrong afterwards, and the "ledger write
 * failed after the row was persisted" case stops being a case at all:
 * it is unreachable by construction rather than handled.
 *
 * ## Idempotency
 *
 * Both statements are `INSERT OR IGNORE`, keyed on `message.id` and
 * `processed_envelopes.envelope_id` — the same envelope id. Re-running
 * this commit for an envelope that already settled is a no-op on both
 * tables, so a redelivery that races the original cannot duplicate the
 * message or corrupt the ledger.
 *
 * ## What this does NOT cover
 *
 * The ratchet state is committed earlier in the receive path, outside
 * this transaction, and moving it is session/ratchet work that is out of
 * scope here. A crash between the ratchet commit and this commit
 * therefore leaves the ratchet advanced with no message row and no
 * ledger entry. That redelivery is NOT silently acked — it re-enters
 * decrypt, fails MAC, and lands in the existing decrypt-failed hold and
 * repair machinery. Recoverable and visible, rather than lost and
 * silent; see the round documentation for the full statement.
 */
interface InboundCommitRepository {

    /**
     * Persist [message] and record [envelopeId] as PROCESSED in one
     * transaction.
     *
     * Throws if the transaction cannot commit. The caller MUST NOT send
     * `ack-deliver` unless this returns normally.
     */
    suspend fun commitInboundMessage(
        message: MessageEntity,
        envelopeId: String,
        conversationId: String,
        senderPubKeyHex: String,
        payloadType: String,
        nowMs: Long,
    )
}
