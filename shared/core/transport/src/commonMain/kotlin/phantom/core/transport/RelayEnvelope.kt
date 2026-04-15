package phantom.core.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class RelayMessage {

    @Serializable
    @SerialName("send")
    data class Send(
        val to: String,
        val from: String,
        val payload: String,   // base64-encoded ciphertext
        val messageId: String,
    ) : RelayMessage()

    @Serializable
    @SerialName("deliver")
    data class Deliver(
        val from: String,
        val payload: String,   // base64-encoded ciphertext
        val messageId: String,
    ) : RelayMessage()

    @Serializable
    @SerialName("ack")
    data class Ack(
        val messageId: String,
        val status: String,    // "relayed" | "delivered"
    ) : RelayMessage()

    @Serializable
    @SerialName("ping")
    object Ping : RelayMessage()

    @Serializable
    @SerialName("pong")
    object Pong : RelayMessage()
}
