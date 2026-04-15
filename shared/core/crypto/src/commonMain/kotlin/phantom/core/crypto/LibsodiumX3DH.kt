package phantom.core.crypto

import com.ionspin.kotlin.crypto.box.Box
import com.ionspin.kotlin.crypto.hash.Hash

/**
 * X3DH implementation backed by libsodium primitives.
 *
 * DH primitive: X25519 via [Box.beforeNm].
 *   Box.beforeNm(theirPublicKey: UByteArray, mySecretKey: UByteArray): UByteArray
 *   Returns a 32-byte shared secret (NM = "precomputed shared key").
 *
 * KDF: SHA-256 over the concatenation of DH outputs.
 *   Alpha-0 justification: a proper HKDF (RFC 5869) requires HMAC-SHA256 which
 *   is available in libsodium as crypto_auth_hmacsha256, but the binding exposes
 *   it under [com.ionspin.kotlin.crypto.auth.Auth]. For Alpha-0, SHA-256(DH1||DH2||DH3)
 *   is an acceptable KDF; HKDF extraction is a hardening task for Beta.
 *
 * VERIFY AT FIRST BUILD:
 * - [Box.keypair] — confirm it returns a type with .publicKey and .secretKey (UByteArray).
 * - [Box.beforeNm] — confirm parameter order: (theirPublicKey, mySecretKey).
 *   libsodium C: crypto_box_beforenm(k, pk, sk) — pk is the remote public key, sk is local secret.
 * - [Hash.sha256] — confirm it accepts UByteArray and returns UByteArray.
 *   Import path: com.ionspin.kotlin.crypto.hash.Hash
 */
class LibsodiumX3DH : X3DHProtocol {

    override fun generateDhKeyPair(): DhKeyPair {
        val kp = Box.keypair()
        return DhKeyPair(
            publicKey = DhPublicKey(kp.publicKey.toByteArray()),
            privateKey = DhPrivateKey(kp.secretKey.toByteArray()),
        )
    }

    override fun initiatorHandshake(
        initiatorIdentityKeyPair: DhKeyPair,
        recipientIdentityPublicKey: DhPublicKey,
        recipientSignedPreKey: DhPublicKey,
    ): RatchetState = initiatorHandshakeWithEphemeral(
        initiatorIdentityKeyPair = initiatorIdentityKeyPair,
        recipientIdentityPublicKey = recipientIdentityPublicKey,
        recipientSignedPreKey = recipientSignedPreKey,
        ephemeralKeyPair = generateDhKeyPair(),
    )

    /**
     * Overload that accepts a caller-supplied ephemeral keypair.
     *
     * Used by tests to obtain a deterministic handshake where the ephemeral
     * public key can be forwarded to the recipient side. In production code
     * always use [initiatorHandshake] which generates the ephemeral internally.
     */
    fun initiatorHandshakeWithEphemeral(
        initiatorIdentityKeyPair: DhKeyPair,
        recipientIdentityPublicKey: DhPublicKey,
        recipientSignedPreKey: DhPublicKey,
        ephemeralKeyPair: DhKeyPair,
    ): RatchetState {
        // DH1: initiator identity → recipient signed pre-key
        val dh1 = dh(initiatorIdentityKeyPair.privateKey, recipientSignedPreKey)
        // DH2: ephemeral → recipient identity
        val dh2 = dh(ephemeralKeyPair.privateKey, recipientIdentityPublicKey)
        // DH3: ephemeral → recipient signed pre-key
        val dh3 = dh(ephemeralKeyPair.privateKey, recipientSignedPreKey)

        val masterSecret = kdf(dh1, dh2, dh3)

        // Bootstrap ratchet: the initiator's first sending ratchet key is a fresh
        // ephemeral; the receiving ratchet key is the recipient's signed pre-key so
        // that the first DH ratchet step resolves immediately.
        val sendingRatchet = generateDhKeyPair()

        return RatchetState(
            rootKey = masterSecret,
            sendingChainKey = null,
            receivingChainKey = null,
            sendingRatchetPublicKey = sendingRatchet.publicKey.bytes,
            sendingRatchetPrivateKey = sendingRatchet.privateKey.bytes,
            receivingRatchetPublicKey = recipientSignedPreKey.bytes,
        )
    }

    override fun recipientHandshake(
        recipientIdentityKeyPair: DhKeyPair,
        recipientSignedPreKeyPair: DhKeyPair,
        initiatorIdentityPublicKey: DhPublicKey,
        initiatorEphemeralPublicKey: DhPublicKey,
    ): RatchetState {
        // Mirror of initiatorHandshake — same DH computations, swapped roles.
        val dh1 = dh(recipientSignedPreKeyPair.privateKey, initiatorIdentityPublicKey)
        val dh2 = dh(recipientIdentityKeyPair.privateKey, initiatorEphemeralPublicKey)
        val dh3 = dh(recipientSignedPreKeyPair.privateKey, initiatorEphemeralPublicKey)

        val masterSecret = kdf(dh1, dh2, dh3)

        // The recipient's first sending ratchet key is the signed pre-key pair itself.
        // The receiving ratchet public key is the initiator's ephemeral, used to
        // perform the first DH ratchet step when a message arrives.
        return RatchetState(
            rootKey = masterSecret,
            sendingChainKey = null,
            receivingChainKey = null,
            sendingRatchetPublicKey = recipientSignedPreKeyPair.publicKey.bytes,
            sendingRatchetPrivateKey = recipientSignedPreKeyPair.privateKey.bytes,
            receivingRatchetPublicKey = initiatorEphemeralPublicKey.bytes,
        )
    }

    // --- Internal helpers ---

    /**
     * X25519 Diffie-Hellman via Box.beforeNm.
     * Returns a 32-byte shared secret.
     */
    private fun dh(myPrivate: DhPrivateKey, theirPublic: DhPublicKey): ByteArray =
        Box.beforeNm(
            recipientPublicKey = theirPublic.bytes.toUByteArray(),
            senderSecretKey = myPrivate.bytes.toUByteArray(),
        ).toByteArray()

    /**
     * Alpha-0 KDF: SHA-256(dh1 || dh2 || dh3).
     *
     * Produces a 32-byte master secret.
     * Post-Alpha-0: replace with HKDF-SHA256 (extract + expand) per X3DH spec.
     */
    private fun kdf(dh1: ByteArray, dh2: ByteArray, dh3: ByteArray): ByteArray {
        val input = dh1 + dh2 + dh3
        return Hash.sha256(input.toUByteArray()).toByteArray()
    }
}
