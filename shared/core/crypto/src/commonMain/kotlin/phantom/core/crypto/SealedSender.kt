package phantom.core.crypto

import com.ionspin.kotlin.crypto.box.Box
import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.scalarmult.ScalarMultiplication
import com.ionspin.kotlin.crypto.secretbox.SecretBox
import com.ionspin.kotlin.crypto.util.LibsodiumRandom

/**
 * Sealed-Sender: encrypts the sender's identity so the relay sees only `to` (the
 * routing key) and never `from`.
 *
 * Wire format (raw bytes):
 *   eph_pub (32) || nonce (24) || xsalsa20poly1305_ciphertext
 *
 * KDF: SHA-256(X25519(eph_priv, to_pub) || "phantom_sealed_sender_v1")
 *
 * The caller base64-encodes the returned ByteArray before putting it on the wire.
 */
@OptIn(ExperimentalUnsignedTypes::class)
object SealedSender {

    private const val EPH_PUB_SIZE = 32
    private const val NONCE_SIZE = 24
    private const val DOMAIN = "phantom_sealed_sender_v1"

    /**
     * Seals the sender's identity.
     *
     * @param fromPubKeyHex Sender's identity public key as a hex string.
     * @param toPublicKeyBytes Recipient's identity public key as raw bytes (32 bytes).
     * @return Raw blob: eph_pub (32) || nonce (24) || ciphertext
     */
    fun seal(fromPubKeyHex: String, toPublicKeyBytes: ByteArray): ByteArray {
        // 1. Generate ephemeral X25519 keypair.
        //    Box.keypair() in ionspin exposes .secretKey and .publicKey as UByteArray.
        val ephKeypair = Box.keypair()

        // 2. ECDH: shared = eph_priv × to_pub
        val shared = ScalarMultiplication.scalarMultiplication(
            secretKeyN = ephKeypair.secretKey,
            publicKeyP = toPublicKeyBytes.toUByteArray(),
        )

        // 3. KDF: SHA-256(shared || domain)
        val key = Hash.sha256(shared + DOMAIN.encodeToByteArray().toUByteArray())

        // 4. Encrypt the sender's hex public key string.
        val nonce = LibsodiumRandom.buf(NONCE_SIZE)
        val ciphertext = SecretBox.easy(
            message = fromPubKeyHex.encodeToByteArray().toUByteArray(),
            nonce = nonce,
            key = key,
        )

        // 5. Return: eph_pub || nonce || ciphertext
        return (ephKeypair.publicKey + nonce + ciphertext).toByteArray()
    }

    /**
     * Recovers the sender's identity public key from a sealed blob.
     *
     * @param sealedBytes Raw sealed blob (eph_pub || nonce || ciphertext).
     * @param myPrivateKeyBytes Recipient's identity private key as raw bytes (32 bytes).
     * @return Sender's public key hex string, or null if decryption fails (MAC error,
     *         truncated input, or wrong private key).
     */
    fun unseal(sealedBytes: ByteArray, myPrivateKeyBytes: ByteArray): String? {
        if (sealedBytes.size <= EPH_PUB_SIZE + NONCE_SIZE) return null

        val ephPub = sealedBytes.sliceArray(0 until EPH_PUB_SIZE).toUByteArray()
        val nonce = sealedBytes.sliceArray(EPH_PUB_SIZE until EPH_PUB_SIZE + NONCE_SIZE).toUByteArray()
        val ciphertext = sealedBytes.sliceArray(EPH_PUB_SIZE + NONCE_SIZE until sealedBytes.size).toUByteArray()

        return try {
            val shared = ScalarMultiplication.scalarMultiplication(
                secretKeyN = myPrivateKeyBytes.toUByteArray(),
                publicKeyP = ephPub,
            )
            val key = Hash.sha256(shared + DOMAIN.encodeToByteArray().toUByteArray())
            val plain = SecretBox.openEasy(
                ciphertext = ciphertext,
                nonce = nonce,
                key = key,
            )
            plain.toByteArray().decodeToString()
        } catch (_: Exception) {
            null
        }
    }
}
