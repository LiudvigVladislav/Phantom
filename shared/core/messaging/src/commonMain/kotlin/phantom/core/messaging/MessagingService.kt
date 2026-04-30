// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.coroutines.flow.Flow

interface MessagingService {
    // Stream of decrypted incoming messages
    val incomingMessages: Flow<IncomingMessage>

    // Send a plaintext message — encrypts, stores, transmits
    suspend fun sendMessage(message: OutgoingMessage): Result<Unit>

    // Start listening for relay messages (call after transport is connected)
    suspend fun startReceiving()

    /**
     * Re-attempt every locally-stored message that's currently sitting
     * in [phantom.core.storage.MessageStatus.WAITING_FOR_RECIPIENT_BUNDLE].
     * Returns the number of messages re-attempted (success OR continued
     * failure — both count). Caller wires this into the WS reconnect
     * signal and a periodic ticker. PR C-followup-3.
     */
    suspend fun retryWaitingMessages(): Result<Int>

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
}
