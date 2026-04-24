package phantom.core.crypto

import com.ionspin.kotlin.crypto.box.Box
import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.scalarmult.ScalarMultiplication
import com.ionspin.kotlin.crypto.secretbox.SecretBox
import com.ionspin.kotlin.crypto.util.LibsodiumRandom

/**
 * Double Ratchet backed by libsodium primitives.
 *
 * Chain KDF:
 *   MessageKey = SHA-256(chainKey || 0x01)
 *   nextChain  = SHA-256(chainKey || 0x02)
 *
 * Encryption: XSalsa20-Poly1305 via SecretBox.easy / openEasy.
 * DH ratchet:  X25519 via ScalarMultiplication.scalarMultiplication (Box.beforeNm not in ionspin 0.9.x).
 */
@OptIn(ExperimentalUnsignedTypes::class)
class LibsodiumDoubleRatchet : DoubleRatchet {

    override fun encrypt(
        state: RatchetState,
        plaintext: ByteArray,
    ): Pair<RatchetState, EncryptedMessage> {
        val stateWithChain = if (state.sendingChainKey == null) {
            dhRatchetStepForSend(state)
        } else {
            state
        }

        val (messageKey, nextChainKey) = advanceChain(stateWithChain.sendingChainKey!!)
        val nonce = LibsodiumRandom.buf(NONCE_BYTES).toByteArray()

        val ciphertext = SecretBox.easy(
            message = plaintext.toUByteArray(),
            nonce   = nonce.toUByteArray(),
            key     = messageKey.toUByteArray(),
        ).toByteArray()
        messageKey.zeroize()

        val newState = stateWithChain.copy(
            sendingChainKey = nextChainKey,
            sendCount       = stateWithChain.sendCount + 1,
        )
        val message = EncryptedMessage(
            ratchetPublicKey = newState.sendingRatchetPublicKey,
            messageIndex     = stateWithChain.sendCount,
            ciphertext       = ciphertext,
            nonce            = nonce,
        )
        return Pair(newState, message)
    }

    override fun decrypt(
        state: RatchetState,
        message: EncryptedMessage,
    ): Pair<RatchetState, ByteArray> {
        val needsDhRatchet = state.receivingRatchetPublicKey == null ||
            !message.ratchetPublicKey.contentEquals(state.receivingRatchetPublicKey)

        val stateAfterDh = if (needsDhRatchet) {
            dhRatchetStepForReceive(state, DhPublicKey(message.ratchetPublicKey))
        } else {
            state
        }

        val receivingChainKey = requireNotNull(stateAfterDh.receivingChainKey) {
            "receivingChainKey is null after DH ratchet step — state is corrupt"
        }

        val (messageKey, nextChainKey) = advanceChain(receivingChainKey)

        val plaintext = try {
            SecretBox.openEasy(
                ciphertext = message.ciphertext.toUByteArray(),
                nonce      = message.nonce.toUByteArray(),
                key        = messageKey.toUByteArray(),
            ).toByteArray()
        } catch (e: Exception) {
            messageKey.zeroize()
            throw IllegalArgumentException("Decryption failed: MAC verification error", e)
        }
        messageKey.zeroize()

        val newState = stateAfterDh.copy(
            receivingChainKey = nextChainKey,
            receiveCount      = stateAfterDh.receiveCount + 1,
        )
        return Pair(newState, plaintext)
    }

    // --- DH ratchet steps ---

    private fun dhRatchetStepForSend(state: RatchetState): RatchetState {
        val newSendingKp = Box.keypair()
        val remotePublic = requireNotNull(state.receivingRatchetPublicKey) {
            "Cannot perform DH ratchet: no receiving ratchet public key is known"
        }
        val dhOutput = ScalarMultiplication.scalarMultiplication(
            secretKeyN = newSendingKp.secretKey,
            publicKeyP = remotePublic.toUByteArray(),
        ).toByteArray()

        val (newRoot, newChain) = kdfRatchet(state.rootKey, dhOutput)
        dhOutput.zeroize()

        return state.copy(
            rootKey                  = newRoot,
            sendingChainKey          = newChain,
            sendingRatchetPublicKey  = newSendingKp.publicKey.toByteArray(),
            sendingRatchetPrivateKey = newSendingKp.secretKey.toByteArray(),
        )
    }

    private fun dhRatchetStepForReceive(
        state: RatchetState,
        newRemotePublic: DhPublicKey,
    ): RatchetState {
        val dhOutput = ScalarMultiplication.scalarMultiplication(
            secretKeyN = state.sendingRatchetPrivateKey.toUByteArray(),
            publicKeyP = newRemotePublic.bytes.toUByteArray(),
        ).toByteArray()

        val (newRoot, newReceivingChain) = kdfRatchet(state.rootKey, dhOutput)
        dhOutput.zeroize()

        return state.copy(
            rootKey                   = newRoot,
            receivingChainKey         = newReceivingChain,
            receivingRatchetPublicKey = newRemotePublic.bytes,
        )
    }

    // --- KDF helpers ---

    private fun advanceChain(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val messageKey = Hash.sha256((chainKey + byteArrayOf(0x01)).toUByteArray()).toByteArray()
        val nextChain  = Hash.sha256((chainKey + byteArrayOf(0x02)).toUByteArray()).toByteArray()
        return Pair(messageKey, nextChain)
    }

    private fun kdfRatchet(rootKey: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
        val derived = Hash.sha256((rootKey + dhOutput).toUByteArray()).toByteArray()
        return Pair(derived.copyOfRange(0, 16), derived.copyOfRange(16, 32))
    }

    companion object {
        private const val NONCE_BYTES = 24
    }
}
