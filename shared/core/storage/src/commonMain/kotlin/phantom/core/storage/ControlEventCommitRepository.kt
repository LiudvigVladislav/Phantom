// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

/**
 * N1-F1b R-N1.12 — atomic settlement of a DB-backed control event.
 *
 * ## The window this closes
 *
 * The receive path marked the processed-envelope ledger as soon as the
 * payload type was known, before any control handler ran. A repository
 * failure inside the handler therefore left the envelope recorded as
 * processed with the action never applied, and the redelivery was
 * ack-and-skipped by the dedupe gate. A delete that did not delete, an
 * edit that was lost, a reaction that never landed — silently, and for
 * good.
 *
 * This is the same defect F-1 closed for the message row, in the
 * branches that carry no message row. The earlier round justified
 * leaving them by saying nothing was at stake for them; that was wrong,
 * because user state is at stake.
 *
 * ## Scope: six branches, deliberately
 *
 * Only the control types whose whole effect is ONE idempotent statement
 * against [phantom.core.storage.db.PhantomDatabase] are settled here:
 * delete, edit, disappearing-timer, reaction, pin and read-receipt.
 *
 * Five other control branches are NOT handled and keep their existing
 * pre-action ledger write, each for a stated reason:
 *
 *  - `GROUP_TYPES` advances a ratcheting sender key, so re-applying a
 *    redelivered group envelope is not safe. Moving its ledger down
 *    would trade a lost action for a corrupted key chain. Deferred to a
 *    crypto/idempotency round.
 *  - `CALL_TYPES` has no durable state, and duplicates are not benign:
 *    a repeated offer for an active call produces a reject. The
 *    callback also only launches asynchronous handling, so returning
 *    from it does not mean the action happened. Deferred.
 *  - `TYPE_KEY_ROTATION` also deletes a session, which switches
 *    coroutine context and cannot participate in an outer SQLDelight
 *    transaction. Deferred to a session round.
 *  - `TYPE_AUDIO_CHUNK` is durable only for 1:1 with a chunk repository
 *    wired; group audio and the no-repository configuration reassemble
 *    in memory, so the branch is not uniformly idempotent.
 *  - `TYPE_VOICE_V2` is left with it while the media paths are
 *    considered together.
 *
 * ## Why the statements are issued directly
 *
 * Every `SqlDelight*Repository` method wraps its call in
 * `withContext(Dispatchers.IO)`. A SQLDelight transaction is confined to
 * the thread that opened it, so calling those methods from inside
 * `db.transaction { }` would switch context out of the transaction. The
 * implementation therefore issues the generated queries directly, and
 * `ControlEventCommitAtomicityTest` asserts the resulting rows match
 * what the ordinary repositories write.
 */
interface ControlEventCommitRepository {

    /** The control actions that can be settled atomically. */
    sealed class Action {
        /** `TYPE_DELETE` — remove the target message row. */
        data class DeleteMessage(val messageId: String) : Action()

        /** `TYPE_EDIT` — replace the target's cached plaintext. */
        data class EditMessageText(val messageId: String, val text: String) : Action()

        /** `TYPE_DISAPPEARING_TIMER` — set the conversation's timer. */
        data class SetDisappearingTimer(
            val conversationId: String,
            val seconds: Long,
        ) : Action()

        /** `TYPE_REACTION` with a non-empty emoji. */
        data class UpsertReaction(
            val messageId: String,
            val senderKeyHex: String,
            val emoji: String,
            val createdAtMs: Long,
        ) : Action()

        /** `TYPE_REACTION` with an empty emoji — a retraction. */
        data class DeleteReaction(
            val messageId: String,
            val senderKeyHex: String,
        ) : Action()

        /** `TYPE_PIN` — pin or unpin the target. */
        data class PinMessage(
            val messageId: String,
            val pinned: Boolean,
            val pinnedByPubkeyHex: String?,
        ) : Action()

        /** `TYPE_READ_RECEIPT` — mark the target read. */
        data class MarkRead(val messageId: String) : Action()
    }

    /**
     * Apply [action] and record [envelopeId] as processed in one
     * transaction.
     *
     * Throws if the transaction cannot commit. The caller MUST NOT send
     * `ack-deliver` unless this returns normally: an ack tells the relay
     * to drop its only remaining copy.
     */
    suspend fun commitControlEvent(
        action: Action,
        envelopeId: String,
        conversationId: String,
        senderPubKeyHex: String,
        payloadType: String,
        nowMs: Long,
    )
}
