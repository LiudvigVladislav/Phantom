// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MessagingService {
    /**
     * Becomes true once the initial prekey-bundle bootstrap succeeds (or
     * definitively fails). False only during the brief window between app
     * start and the first network attempt to publish the user's bundle.
     * The UI can observe this to show a "setting up keys…" indicator;
     * it does not gate sends (which work regardless — bootstrap affects
     * incoming session establishment, not outgoing messages).
     */
    val bootstrapReady: StateFlow<Boolean>

    // Stream of decrypted incoming messages
    val incomingMessages: Flow<IncomingMessage>

    // Send a plaintext message — encrypts, stores, transmits
    suspend fun sendMessage(message: OutgoingMessage): Result<Unit>

    /**
     * Chunk [audioBytes] into 64 KB envelopes and send each through the Double Ratchet
     * pipeline. Returns failure immediately (no send) if [audioBytes] exceeds
     * [DefaultMessagingService.MAX_AUDIO_BYTES].
     */
    suspend fun sendAudio(
        conversationId: String,
        audioBytes: ByteArray,
        durationMs: Long,
        mimeType: String,
    ): Result<Unit>

    /**
     * PR-MEDIA-UPLOAD-CANCEL1 — cancel an in-flight outgoing voice upload by
     * its local message id. Tapping the X on the uploading voice bubble
     * (architect Test #76.3 verdict) reaches this entry point. The
     * implementation cancels the per-upload [kotlinx.coroutines.Job], clears
     * the progress entry on `mediaProgressBus`, releases the per-conversation
     * voice-send guard, and either deletes the local row or marks it
     * `FAILED` depending on what state it was in when the cancel arrived.
     *
     * The default no-op exists so platform fakes / tests do not have to
     * implement it; [DefaultMessagingService] overrides with the real
     * behaviour.
     */
    suspend fun cancelVoiceUpload(
        conversationId: String,
        localMsgId: String,
    ): Result<Unit> = Result.success(Unit)

    // Start listening for relay messages (call after transport is connected)
    suspend fun startReceiving()

    /**
     * Release the per-conversation [kotlinx.coroutines.sync.Mutex] entry held for
     * [conversationId]. Call this whenever a conversation is permanently deleted so
     * the entry is not retained in the session-mutex map indefinitely.
     * Default is a no-op; [DefaultMessagingService] overrides.
     */
    suspend fun removeConversationMutex(conversationId: String) {}

    /**
     * Re-attempt every locally-stored message that's currently sitting
     * in [phantom.core.storage.MessageStatus.WAITING_FOR_RECIPIENT_BUNDLE].
     * Returns the number of messages re-attempted (success OR continued
     * failure — both count). Caller wires this into the WS reconnect
     * signal and a periodic ticker. PR C-followup-3.
     *
     * DWS-UX.1 (2026-06-17): callers MUST pass a short [source] label
     * (e.g. `"ticker"`, `"ws_reconnect"`, `"manual"`) so the
     * `RETRY_TRACE` log line discriminates the trigger path. The
     * first-message yellow-dot UX investigation needs this field to
     * distinguish whether a recovery came from the 60 s ticker or
     * from a WS reconnect collector. Defaults to `"unknown"` so
     * legacy call sites and unit tests keep working unchanged.
     */
    suspend fun retryWaitingMessages(source: String = "unknown"): Result<Int>

    /**
     * Mark all unread messages in a conversation as READ locally.
     *
     * @param sendReceipt when true (default) also broadcasts a ReadReceipt
     *   envelope to the sender so their UI can display the blue tick. Pass
     *   false from privacy modes that suppress outgoing receipt metadata
     *   (Settings → Privacy Mode → Private / Ghost). Local read state is
     *   updated either way; only the wire signal is gated.
     */
    suspend fun markConversationRead(
        conversationId: String,
        theirPublicKeyHex: String,
        sendReceipt: Boolean = true,
    )

    // Send encrypted delete-control message and delete locally
    suspend fun deleteMessageForBoth(messageId: String, conversationId: String, recipientPublicKeyHex: String): Result<Unit>

    // Send encrypted edit-control message and update locally
    suspend fun editMessageForBoth(
        messageId: String,
        newText: String,
        conversationId: String,
        recipientPublicKeyHex: String,
    ): Result<Unit>

    // Notify the peer of the new disappearing-message timer for this conversation
    suspend fun sendDisappearingTimerUpdate(
        timerSecs: Long,
        conversationId: String,
        recipientPublicKeyHex: String,
    ): Result<Unit>

    // Send an emoji reaction to a specific message; emoji="" means remove the reaction
    suspend fun sendReaction(
        messageId: String,
        conversationId: String,
        recipientPublicKeyHex: String,
        emoji: String,
    ): Result<Unit>

    // Pin or unpin a message for both parties; propagated as an encrypted control message
    suspend fun pinMessageForBoth(
        messageId: String,
        conversationId: String,
        recipientPublicKeyHex: String,
        pinned: Boolean,
    ): Result<Unit>

    /**
     * Send a call-signalling payload (offer / answer / ice / hangup / reject)
     * through the Double Ratchet + Sealed Sender pipeline.
     *
     * The payload is never stored as a chat message. The relay sees only an
     * opaque sealed blob — indistinguishable from any other encrypted envelope.
     * The callee's ring screen still shows the caller's identity because it is
     * recovered from the decrypted payload via [onCallMessage], not from the
     * relay `from` field.
     */
    suspend fun sendCallSignal(
        recipientPublicKeyHex: String,
        payload: MessagePayload,
    ): Result<Unit>

    /**
     * Send a group control payload (invite / SKD / add-member / leave / group
     * message envelope) to one peer through the Double Ratchet + Sealed Sender
     * pipeline. Used by [GroupMessagingService] for every outgoing group-related
     * relay envelope so chain keys and member rosters never touch the relay in
     * plaintext (ADR-026, closes F1).
     *
     * The payload is not stored as a chat message; the generated relay
     * messageId is ephemeral and used only as the deduplication key.
     */
    suspend fun sendGroupControlMessage(
        toPubKeyHex: String,
        payload: MessagePayload,
    ): Result<Unit>
}
