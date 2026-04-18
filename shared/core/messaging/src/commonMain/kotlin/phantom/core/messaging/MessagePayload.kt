package phantom.core.messaging

import kotlinx.serialization.Serializable

@Serializable
data class MessagePayload(
    val text: String,
    val sentAt: Long,
    val senderUsername: String = "",
    val type: String = "message",       // "message" | "delete"
    val targetMessageId: String = "",   // used when type = "delete"
)
