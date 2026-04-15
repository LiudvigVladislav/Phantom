package phantom.core.crypto

import kotlinx.serialization.Serializable

/**
 * A wire-format encrypted message produced by [DoubleRatchet.encrypt].
 *
 * Layout:
 * - [ratchetPublicKey] — sender's current DH ratchet key (32 bytes, Curve25519).
 *   The receiver uses this to detect whether a DH ratchet step is needed.
 * - [messageIndex] — monotonically increasing counter within the current sending chain.
 *   Alpha-0: used only for debugging; skipped-message handling is not implemented.
 * - [ciphertext] — XSalsa20-Poly1305 authenticated ciphertext (plaintext + 16-byte MAC).
 * - [nonce] — 24-byte random nonce; unique per message.
 */
@Serializable
data class EncryptedMessage(
    val ratchetPublicKey: ByteArray,
    val messageIndex: Int,
    val ciphertext: ByteArray,
    val nonce: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedMessage) return false
        return ratchetPublicKey.contentEquals(other.ratchetPublicKey) &&
            messageIndex == other.messageIndex &&
            ciphertext.contentEquals(other.ciphertext) &&
            nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int {
        var result = ratchetPublicKey.contentHashCode()
        result = 31 * result + messageIndex
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        return result
    }
}
