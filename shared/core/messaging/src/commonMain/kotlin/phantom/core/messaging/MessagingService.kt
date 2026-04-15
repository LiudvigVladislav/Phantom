package phantom.core.messaging

import kotlinx.coroutines.flow.Flow

interface MessagingService {
    // Stream of decrypted incoming messages
    val incomingMessages: Flow<IncomingMessage>

    // Send a plaintext message — encrypts, stores, transmits
    suspend fun sendMessage(message: OutgoingMessage): Result<Unit>

    // Start listening for relay messages (call after transport is connected)
    suspend fun startReceiving()
}
