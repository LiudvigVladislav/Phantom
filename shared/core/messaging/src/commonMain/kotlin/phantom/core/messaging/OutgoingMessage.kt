package phantom.core.messaging

data class OutgoingMessage(
    val id: String,
    val conversationId: String,
    val recipientPublicKeyHex: String,
    val text: String,
)
