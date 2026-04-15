package phantom.core.crypto

import com.ionspin.kotlin.crypto.box.Box
import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.secretbox.SecretBox
import com.ionspin.kotlin.crypto.util.LibsodiumRandom

/**
 * Double Ratchet implementation backed by libsodium primitives.
 *
 * KDF chain:
 *   MessageKey  = SHA-256(chainKey || 0x01)  — 32-byte symmetric key
 *   nextChain   = SHA-256(chainKey || 0x02)  — 32-byte next chain key
 *
 * Encryption: XSalsa20-Poly1305 via SecretBox.
 *   SecretBox.easy(message, nonce, key) → ciphertext (plaintext + 16-byte MAC)
 *   SecretBox.openEasy(ciphertext, nonce, key) → plaintext
 *   Nonce: 24 bytes, randomly generated per message.
 *
 * DH ratchet: X25519 via Box.beforeNm, same as LibsodiumX3DH.
 *   New shared secret feeds into rootKey / chainKey update via SHA-256(rootKey || dhOutput).
 *
 * VERIFY AT FIRST BUILD:
 * - [SecretBox.easy] — parameter order: (message: UByteArray, nonce: UByteArray, key: UByteArray)
 * - [SecretBox.openEasy] — same order; throws on authentication failure (catch to map to IAE).
 * - [LibsodiumRandom.buf] — confirm it returns UByteArray of requested length.
 *   Import: com.ionspin.kotlin.crypto.util.LibsodiumRandom
 * - [Box.keypair] — confirm return type has .publicKey / .secretKey as UByteArray.
 * - [Hash.sha256] — confirm signature: (UByteArray) -> UByteArray.
 */
class LibsodiumDoubleRatchet : DoubleRatchet {

    override fun encrypt(
        state: RatchetState,
        plaintext: ByteArray,
    ): Pair<RatchetState, EncryptedMessage> {
        // If no sending chain key exists yet, perform the first DH ratchet step
        // to derive one. This happens on the initiator's very first send.
        val stateWithChain = if (state.sendingChainKey == null) {
            dhRatchetStepForSend(state)
        } else {
            state
        }

        val (messageKey, nextChainKey) = advanceChain(stateWithChain.sendingChainKey!!)
        val nonce = LibsodiumRandom.buf(NONCE_BYTES).toByteArray()

        val ciphertext = SecretBox.easy(
            message = plaintext.toUByteArray(),
            nonce = nonce.toUByteArray(),
            key = messageKey.toUByteArray(),
        ).toByteArray()

        val newState = stateWithChain.copy(
            sendingChainKey = nextChainKey,
            sendCount = stateWithChain.sendCount + 1,
        )
        val message = EncryptedMessage(
            ratchetPublicKey = newState.sendingRatchetPublicKey,
            messageIndex = stateWithChain.sendCount,
            ciphertext = ciphertext,
            nonce = nonce,
        )
        return Pair(newState, message)
    }

    override fun decrypt(
        state: RatchetState,
        message: EncryptedMessage,
    ): Pair<RatchetState, ByteArray> {
        // Detect whether the remote party has advanced their ratchet.
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
                nonce = message.nonce.toUByteArray(),
                key = messageKey.toUByteArray(),
            ).toByteArray()
        } catch (e: Exception) {
            throw IllegalArgumentException("Decryption failed: MAC verification error", e)
        }

        val newState = stateAfterDh.copy(
            receivingChainKey = nextChainKey,
            receiveCount = stateAfterDh.receiveCount + 1,
        )
        return Pair(newState, plaintext)
    }

    // --- DH ratchet steps ---

    /**
     * Performs a DH ratchet step in the sending direction.
     *
     * Used when the initiator has no sending chain yet:
     * 1. Generate a new DH ratchet keypair.
     * 2. Compute DH with the stored receivingRatchetPublicKey.
     * 3. Derive new rootKey and sendingChainKey from SHA-256(rootKey || dhOutput).
     */
    private fun dhRatchetStepForSend(state: RatchetState): RatchetState {
        val newSendingKp = Box.keypair()
        val remotePublic = requireNotNull(state.receivingRatchetPublicKey) {
            "Cannot perform DH ratchet: no receiving ratchet public key is known"
        }
        val dhOutput = Box.beforeNm(
            recipientPublicKey = remotePublic.toUByteArray(),
            senderSecretKey = newSendingKp.secretKey,
        ).toByteArray()

        val (newRoot, newChain) = kdfRatchet(state.rootKey, dhOutput)

        return state.copy(
            rootKey = newRoot,
            sendingChainKey = newChain,
            sendingRatchetPublicKey = newSendingKp.publicKey.toByteArray(),
            sendingRatchetPrivateKey = newSendingKp.secretKey.toByteArray(),
        )
    }

    /**
     * Performs a DH ratchet step in the receiving direction.
     *
     * Triggered when an incoming [EncryptedMessage.ratchetPublicKey] differs
     * from the stored receivingRatchetPublicKey:
     * 1. Compute DH(mySendingPrivate, newRemotePublic).
     * 2. Derive new rootKey and receivingChainKey.
     * 3. Record the new remotePublic.
     */
    private fun dhRatchetStepForReceive(
        state: RatchetState,
        newRemotePublic: DhPublicKey,
    ): RatchetState {
        val dhOutput = Box.beforeNm(
            recipientPublicKey = newRemotePublic.bytes.toUByteArray(),
            senderSecretKey = state.sendingRatchetPrivateKey.toUByteArray(),
        ).toByteArray()

        val (newRoot, newReceivingChain) = kdfRatchet(state.rootKey, dhOutput)

        return state.copy(
            rootKey = newRoot,
            receivingChainKey = newReceivingChain,
            receivingRatchetPublicKey = newRemotePublic.bytes,
        )
    }

    // --- KDF helpers ---

    /**
     * KDF chain step: returns (messageKey, nextChainKey).
     *
     * messageKey  = SHA-256(chainKey || 0x01)
     * nextChain   = SHA-256(chainKey || 0x02)
     */
    private fun advanceChain(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val messageKey = Hash.sha256((chainKey + byteArrayOf(0x01)).toUByteArray()).toByteArray()
        val nextChain = Hash.sha256((chainKey + byteArrayOf(0x02)).toUByteArray()).toByteArray()
        return Pair(messageKey, nextChain)
    }

    /**
     * DH ratchet KDF: produces (newRootKey, newChainKey) from current rootKey and dhOutput.
     *
     * Alpha-0: SHA-256(rootKey || dhOutput), split at 16 bytes.
     * Post-Alpha-0: replace with HKDF-SHA256(rootKey, dhOutput, info="PHANTOM_RATCHET").
     */
    private fun kdfRatchet(rootKey: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
        val derived = Hash.sha256((rootKey + dhOutput).toUByteArray()).toByteArray()
        // 32-byte SHA-256 output split into two 16-byte halves.
        val newRoot = derived.copyOfRange(0, 16)
        val newChain = derived.copyOfRange(16, 32)
        return Pair(newRoot, newChain)
    }

    companion object {
        private const val NONCE_BYTES = 24 // crypto_secretbox_NONCEBYTES
    }
}
