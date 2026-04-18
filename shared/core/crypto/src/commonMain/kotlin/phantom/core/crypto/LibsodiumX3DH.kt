package phantom.core.crypto

import com.ionspin.kotlin.crypto.box.Box
import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.scalarmult.ScalarMultiplication

/**
 * X3DH implementation backed by libsodium primitives.
 *
 * DH primitive: X25519 via [ScalarMultiplication.scalarMultiplication].
 *   scalarMultiplication(secretKeyN = myPrivate, publicKeyP = theirPublic) → 32-byte shared secret.
 *   (Box.beforeNm is not available in ionspin 0.9.x; ScalarMultiplication is equivalent.)
 *
 * KDF: SHA-256(DH1 || DH2 || DH3) — acceptable for Alpha-0.
 * Post-Alpha-0: replace with HKDF-SHA256 (RFC 5869).
 */
@OptIn(ExperimentalUnsignedTypes::class)
class LibsodiumX3DH : X3DHProtocol {

    override fun generateDhKeyPair(): DhKeyPair {
        val kp = Box.keypair()
        return DhKeyPair(
            publicKey  = DhPublicKey(kp.publicKey.toByteArray()),
            privateKey = DhPrivateKey(kp.secretKey.toByteArray()),
        )
    }

    override fun initiatorHandshake(
        initiatorIdentityKeyPair: DhKeyPair,
        recipientIdentityPublicKey: DhPublicKey,
        recipientSignedPreKey: DhPublicKey,
    ): RatchetState = initiatorHandshakeWithEphemeral(
        initiatorIdentityKeyPair   = initiatorIdentityKeyPair,
        recipientIdentityPublicKey = recipientIdentityPublicKey,
        recipientSignedPreKey      = recipientSignedPreKey,
        ephemeralKeyPair           = generateDhKeyPair(),
    )

    fun initiatorHandshakeWithEphemeral(
        initiatorIdentityKeyPair: DhKeyPair,
        recipientIdentityPublicKey: DhPublicKey,
        recipientSignedPreKey: DhPublicKey,
        ephemeralKeyPair: DhKeyPair,
    ): RatchetState {
        val dh1 = dh(initiatorIdentityKeyPair.privateKey, recipientSignedPreKey)
        val dh2 = dh(ephemeralKeyPair.privateKey, recipientIdentityPublicKey)
        val dh3 = dh(ephemeralKeyPair.privateKey, recipientSignedPreKey)
        val masterSecret = kdf(dh1, dh2, dh3)
        val sendingRatchet = generateDhKeyPair()
        return RatchetState(
            rootKey                   = masterSecret,
            sendingChainKey           = null,
            receivingChainKey         = null,
            sendingRatchetPublicKey   = sendingRatchet.publicKey.bytes,
            sendingRatchetPrivateKey  = sendingRatchet.privateKey.bytes,
            receivingRatchetPublicKey = recipientSignedPreKey.bytes,
        )
    }

    override fun recipientHandshake(
        recipientIdentityKeyPair: DhKeyPair,
        recipientSignedPreKeyPair: DhKeyPair,
        initiatorIdentityPublicKey: DhPublicKey,
        initiatorEphemeralPublicKey: DhPublicKey,
    ): RatchetState {
        val dh1 = dh(recipientSignedPreKeyPair.privateKey, initiatorIdentityPublicKey)
        val dh2 = dh(recipientIdentityKeyPair.privateKey, initiatorEphemeralPublicKey)
        val dh3 = dh(recipientSignedPreKeyPair.privateKey, initiatorEphemeralPublicKey)
        val masterSecret = kdf(dh1, dh2, dh3)
        return RatchetState(
            rootKey                   = masterSecret,
            sendingChainKey           = null,
            receivingChainKey         = null,
            sendingRatchetPublicKey   = recipientSignedPreKeyPair.publicKey.bytes,
            sendingRatchetPrivateKey  = recipientSignedPreKeyPair.privateKey.bytes,
            receivingRatchetPublicKey = initiatorEphemeralPublicKey.bytes,
        )
    }

    override fun computeSharedSecret(privateKey: DhPrivateKey, publicKey: DhPublicKey): ByteArray =
        ScalarMultiplication.scalarMultiplication(
            secretKeyN = privateKey.bytes.toUByteArray(),
            publicKeyP = publicKey.bytes.toUByteArray(),
        ).toByteArray()

    // --- helpers ---

    private fun dh(myPrivate: DhPrivateKey, theirPublic: DhPublicKey): ByteArray =
        ScalarMultiplication.scalarMultiplication(
            secretKeyN = myPrivate.bytes.toUByteArray(),
            publicKeyP = theirPublic.bytes.toUByteArray(),
        ).toByteArray()

    private fun kdf(dh1: ByteArray, dh2: ByteArray, dh3: ByteArray): ByteArray =
        Hash.sha256((dh1 + dh2 + dh3).toUByteArray()).toByteArray()
}
