package phantom.core.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class RelayMessage {

    @Serializable
    @SerialName("send")
    data class Send(
        val to: String,
        val from: String = "",          // empty when using sealed sender
        val sealedSender: String = "",  // base64 blob (eph_pub||nonce||ct); when set, `from` is empty
        val payload: String,            // base64-encoded ciphertext (ISO 7816-4 padded)
        val messageId: String,
    ) : RelayMessage()

    @Serializable
    @SerialName("deliver")
    data class Deliver(
        val from: String = "",          // empty when using sealed sender
        val sealedSender: String = "",  // base64 blob (eph_pub||nonce||ct); when set, `from` is empty
        val payload: String,            // base64-encoded ciphertext (ISO 7816-4 padded)
        val messageId: String,
    ) : RelayMessage()

    @Serializable
    @SerialName("ack")
    data class Ack(
        val messageId: String,
        val status: String,    // "relayed" | "delivered"
    ) : RelayMessage()

    @Serializable
    @SerialName("read")
    data class ReadReceipt(
        val to: String,         // recipient public key hex
        val from: String,       // sender public key hex
        val messageId: String,  // the message that was read
    ) : RelayMessage()

    /**
     * Sent by the recipient AFTER an inbound envelope has been fully processed
     * (Sealed-Sender unseal → Double-Ratchet decrypt → DB insert succeeded).
     * The relay removes the corresponding envelope from its per-recipient
     * store on receipt, so reconnect-redelivery does not endlessly replay
     * already-processed messages. The recipient identity is the
     * authenticated WS connection identity, so a client can only ack-deliver
     * envelopes addressed to itself.
     */
    @Serializable
    @SerialName("ack-deliver")
    data class AckDelivery(
        val messageId: String,
    ) : RelayMessage()

    @Serializable
    @SerialName("ping")
    object Ping : RelayMessage()

    @Serializable
    @SerialName("pong")
    object Pong : RelayMessage()
}
