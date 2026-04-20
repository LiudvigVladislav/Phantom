package phantom.core.messaging

import kotlinx.coroutines.flow.Flow

interface MessagingService {
    // Stream of decrypted incoming messages
    val incomingMessages: Flow<IncomingMessage>

    // Send a plaintext message — encrypts, stores, transmits
    suspend fun sendMessage(message: OutgoingMessage): Result<Unit>

    // Start listening for relay messages (call after transport is connected)
    suspend fun startReceiving()

    // Notify sender that their messages in this conversation were read
    suspend fun markConversationRead(conversationId: String, theirPublicKeyHex: String)

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
}
