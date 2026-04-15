package phantom.core.crypto

/**
 * Extended Triple Diffie-Hellman (X3DH) key agreement.
 *
 * Produces an initial [RatchetState] that both parties share without
 * requiring simultaneous online presence (asynchronous key agreement).
 *
 * Alpha-0 simplifications:
 * - No one-time prekeys (OPKs). DH3 uses the signed pre-key only.
 * - No prekey bundle signature verification at this layer — the transport
 *   layer is responsible for authenticating the bundle delivery.
 *
 * Reference: https://signal.org/docs/specifications/x3dh/
 */
interface X3DHProtocol {

    /**
     * Called by the session initiator (Alice).
     *
     * Computes:
     *   DH1 = DH(initiatorIdentity,  recipientSignedPreKey)
     *   DH2 = DH(ephemeral,          recipientIdentity)
     *   DH3 = DH(ephemeral,          recipientSignedPreKey)
     *   masterSecret = KDF(DH1 || DH2 || DH3)
     *
     * Returns a [RatchetState] where:
     * - rootKey = masterSecret
     * - sendingRatchetKeyPair is a freshly generated ephemeral DH keypair
     * - receivingRatchetPublicKey = recipientSignedPreKey (bootstrap ratchet step)
     *
     * The initiator's ephemeral public key must be transmitted to the recipient
     * out-of-band (e.g. in the initial message header) so they can call
     * [recipientHandshake].
     */
    fun initiatorHandshake(
        initiatorIdentityKeyPair: DhKeyPair,
        recipientIdentityPublicKey: DhPublicKey,
        recipientSignedPreKey: DhPublicKey,
    ): RatchetState

    /**
     * Called by the session recipient (Bob).
     *
     * Mirrors [initiatorHandshake]: recomputes the same DH1/DH2/DH3 from
     * Bob's side, producing an identical masterSecret.
     *
     * Returns a [RatchetState] where:
     * - rootKey = masterSecret
     * - sendingRatchetKeyPair = recipientSignedPreKeyPair (ready to reply)
     * - receivingRatchetPublicKey = initiatorEphemeralPublicKey
     */
    fun recipientHandshake(
        recipientIdentityKeyPair: DhKeyPair,
        recipientSignedPreKeyPair: DhKeyPair,
        initiatorIdentityPublicKey: DhPublicKey,
        initiatorEphemeralPublicKey: DhPublicKey,
    ): RatchetState

    /** Generates a fresh Curve25519 DH keypair. */
    fun generateDhKeyPair(): DhKeyPair
}
