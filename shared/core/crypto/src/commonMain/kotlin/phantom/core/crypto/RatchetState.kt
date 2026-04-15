package phantom.core.crypto

import kotlinx.serialization.Serializable

/**
 * Full serializable state of a Double Ratchet session for one party.
 *
 * Alpha-0 constraints:
 * - No skipped-message-key cache.
 * - No out-of-order delivery support.
 * Both are deferred to a later iteration.
 *
 * ByteArray fields are stored as raw bytes; serialization encodes them as
 * Base64 via the default kotlinx.serialization ByteArray encoder.
 */
@Serializable
data class RatchetState(
    /** Shared root key, updated on every DH ratchet step. */
    val rootKey: ByteArray,

    /** KDF chain key for the sending direction. Null before first send. */
    val sendingChainKey: ByteArray?,

    /** KDF chain key for the receiving direction. Null before first receive. */
    val receivingChainKey: ByteArray?,

    /** Our current DH ratchet public key (sent in every message header). */
    val sendingRatchetPublicKey: ByteArray,

    /** Our current DH ratchet private key (kept secret). */
    val sendingRatchetPrivateKey: ByteArray,

    /** The last DH ratchet public key we received from the remote party. */
    val receivingRatchetPublicKey: ByteArray?,

    /** Number of messages sent on the current sending chain. */
    val sendCount: Int = 0,

    /** Number of messages received on the current receiving chain. */
    val receiveCount: Int = 0,
) {
    // ByteArray equals/hashCode must be structural for data class correctness.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RatchetState) return false
        return rootKey.contentEquals(other.rootKey) &&
            sendingChainKey.contentEqualsNullable(other.sendingChainKey) &&
            receivingChainKey.contentEqualsNullable(other.receivingChainKey) &&
            sendingRatchetPublicKey.contentEquals(other.sendingRatchetPublicKey) &&
            sendingRatchetPrivateKey.contentEquals(other.sendingRatchetPrivateKey) &&
            receivingRatchetPublicKey.contentEqualsNullable(other.receivingRatchetPublicKey) &&
            sendCount == other.sendCount &&
            receiveCount == other.receiveCount
    }

    override fun hashCode(): Int {
        var result = rootKey.contentHashCode()
        result = 31 * result + (sendingChainKey?.contentHashCode() ?: 0)
        result = 31 * result + (receivingChainKey?.contentHashCode() ?: 0)
        result = 31 * result + sendingRatchetPublicKey.contentHashCode()
        result = 31 * result + sendingRatchetPrivateKey.contentHashCode()
        result = 31 * result + (receivingRatchetPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + sendCount
        result = 31 * result + receiveCount
        return result
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> this.contentEquals(other)
    }
