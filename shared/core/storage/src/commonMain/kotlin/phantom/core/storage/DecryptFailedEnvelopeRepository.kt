// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

/**
 * PR-CRYPTO-SESSION-REPAIR1 (2026-05-29) — durable hold table for
 * envelopes that hit `Permanent decrypt failure (MAC error)` during
 * `ratchet.decrypt` in builds where `holdMacFailures = true`
 * (debug/beta).
 *
 * Replaces the previous silent destructive ack-on-MAC path in
 * [phantom.core.messaging.DefaultMessagingService.handleDeliver] with
 * a "hold + best-effort replay after session repair" cycle:
 *
 *   1. MAC error → [insert] the envelope (with the original wire
 *      frame so it can be re-fed verbatim) instead of ack-delivering
 *      and writing `processed_envelopes.markProcessed`.
 *   2. The conversation is marked `session_suspect` so the next
 *      outgoing message forces a fresh X3DH 4-DH bootstrap.
 *   3. After the new ratchet is encrypt+save committed, the receive
 *      layer reads [listByConversation] and re-decrypts each held
 *      envelope under the fresh session.
 *   4. On replay success → [deleteByEnvelopeId] removes the row and
 *      the envelope is processed normally.
 *   5. On replay failure → [recordReplayAttempt] increments the
 *      counter and updates the timestamp; the row stays until the
 *      24-hour TTL evicts it via [deleteOlderThan].
 *
 * Honest assumption (Vladislav-architect 2026-05-29): held envelopes
 * were encrypted under the previous (drifted) chain key, so replay
 * is best-effort, not best-bet — most of them will continue to fail.
 * The product win is twofold:
 *   - no silent destructive loss: the row exists, replay attempts
 *     are countable, and the relay's ack still hasn't been sent
 *     (relay's TTL eventually evicts but we don't actively confirm
 *     deletion);
 *   - automatic repair for FUTURE messages: once both sides reconverge
 *     via the next outgoing's fresh X3DH, subsequent envelopes
 *     decrypt cleanly.
 *
 * Release behaviour is preserved when `holdMacFailures = false`: the
 * existing ack-on-MAC + `processed_envelopes.markProcessed` path runs
 * unchanged and this table stays empty.
 */
interface DecryptFailedEnvelopeRepository {

    /** A held envelope row as read from the database. */
    data class Entry(
        val envelopeId: String,
        val conversationId: String,
        val senderPubKeyHex: String,
        val errorType: String,
        val receivedAtMs: Long,
        val x3dhInitPresent: Boolean,
        val wireFrameJson: String,
        val replayAttemptCount: Long,
        val lastReplayAtMs: Long?,
    )

    /**
     * Persist a held envelope. INSERT OR IGNORE — a rare race where two
     * coroutines reach handleDeliver for the same `envelope_id` before
     * either writes will no-op the second insert cleanly.
     *
     * @param wireFrameJson the **inner `WireFrame` JSON**, NOT the outer
     *   `RelayMessage.Deliver` JSON. Produced via
     *   `json.encodeToString(WireFrame.serializer(), wireFrame)` from
     *   the receive path's already-decoded `wireFrame` object (the
     *   one from which `wireFrame.encryptedMessage` is read for
     *   `ratchet.decrypt`).
     *
     *   The replay loop in commit 5 reads this column and decodes back
     *   into `WireFrame`, so the same `wireFrame.encryptedMessage` can
     *   be re-fed into `ratchet.decrypt` under the fresh ratchet.
     *   Storing the outer Deliver-frame JSON would force the replay
     *   path to redo the entire deliver-frame unwrap chain (legacy /
     *   bare-EncryptedMessage / wireFrameErr / etc), which has its own
     *   failure modes unrelated to MAC recovery.
     *
     *   Architect-locked 2026-05-29 in PR #243.
     *
     *   NOT optional — metadata-only rows defeat the PR's replay goal.
     */
    suspend fun insert(
        envelopeId: String,
        conversationId: String,
        senderPubKeyHex: String,
        errorType: String,
        receivedAtMs: Long,
        x3dhInitPresent: Boolean,
        wireFrameJson: String,
    )

    /**
     * Returns held envelopes for a conversation ordered by
     * `received_at_ms ASC`, so the replay loop processes them in
     * original arrival order.
     */
    suspend fun listByConversation(conversationId: String): List<Entry>

    /** Called when a replay attempt succeeds. */
    suspend fun deleteByEnvelopeId(envelopeId: String)

    /**
     * Called when a replay attempt fails. Bumps `replay_attempt_count`
     * + updates `last_replay_at_ms` so the table preserves the audit
     * trail of how many times this envelope was re-tried since being
     * held.
     */
    suspend fun recordReplayAttempt(envelopeId: String, nowMs: Long)

    /** TTL sweep — evicts held rows older than [olderThanMs] (epoch ms). */
    suspend fun deleteOlderThan(olderThanMs: Long)

    /** Diagnostic — total held rows across the table. */
    suspend fun count(): Long

    /** Diagnostic — held row count per conversation id. */
    suspend fun countByConversation(): Map<String, Long>

    /** Wipes the table. Used by reset-app flows and instrumentation tests. */
    suspend fun deleteAll()
}
