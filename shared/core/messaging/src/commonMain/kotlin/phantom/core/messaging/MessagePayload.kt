package phantom.core.messaging

import kotlinx.serialization.Serializable

@Serializable
data class MessagePayload(
    val text: String,
    val sentAt: Long,
)
