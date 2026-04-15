package phantom.core.messaging

data class IncomingMessage(
    val id: String,
    val conversationId: String,
    val senderPublicKeyHex: String,
    val text: String,
    val receivedAt: Long,
)
