// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

/**
 * Extended Triple Diffie-Hellman (X3DH) key agreement.
 *
 * Produces an initial [RatchetState] that both parties share without
 * requiring simultaneous online presence (asynchronous key agreement).
 *
 * Two variants live side by side during the Alpha-1 → Alpha-2 transition:
 *
 *  - **3-DH legacy** ([initiatorHandshake] / [recipientHandshake]) — the
 *    Alpha-0/1 path. Kept for backward-compat while sessions established
 *    against pre-ADR-009 builds drain. Will be removed in PR C once the
 *    migration window closes.
 *
 *  - **4-DH per ADR-009** ([initiatorHandshake4DH] / [recipientHandshake4DH])
 *    — the new path used for every fresh session. Adds a one-time pre-key
 *    (OPK) round when one is published, falling back to 3-DH composition
 *    when the recipient's OPK pool is empty. KDF is HKDF-SHA256 with
 *    explicit domain separation.
 *
 * Reference: https://signal.org/docs/specifications/x3dh/
 */
interface X3DHProtocol {

    // ── 3-DH legacy ──────────────────────────────────────────────────────────

    /**
     * Legacy 3-DH initiator path.
     *
     * Computes:
     *   DH1 = DH(initiatorIdentity,  recipientSignedPreKey)
     *   DH2 = DH(ephemeral,          recipientIdentity)
     *   DH3 = DH(ephemeral,          recipientSignedPreKey)
     *   masterSecret = SHA256(DH1 || DH2 || DH3)
     *
     * NOTE: kept only so PR A is purely additive. Use [initiatorHandshake4DH]
     * for any new code.
     */
    fun initiatorHandshake(
        initiatorIdentityKeyPair: DhKeyPair,
        recipientIdentityPublicKey: DhPublicKey,
        recipientSignedPreKey: DhPublicKey,
    ): RatchetState

    /**
     * Legacy 3-DH recipient path. Mirrors [initiatorHandshake].
     */
    fun recipientHandshake(
        recipientIdentityKeyPair: DhKeyPair,
        recipientSignedPreKeyPair: DhKeyPair,
        initiatorIdentityPublicKey: DhPublicKey,
        initiatorEphemeralPublicKey: DhPublicKey,
    ): RatchetState

    // ── 4-DH per ADR-009 ─────────────────────────────────────────────────────

    /**
     * 4-DH initiator handshake.
     *
     * Computes (when [recipientOPK] is present):
     *   DH1 = DH(initiatorIdentity, recipientSignedPreKey)
     *   DH2 = DH(ephemeral,         recipientIdentity)
     *   DH3 = DH(ephemeral,         recipientSignedPreKey)
     *   DH4 = DH(ephemeral,         recipientOPK)
     *   masterSecret = HKDF-SHA256(IKM = DH1||DH2||DH3||DH4,
     *                              salt = SHA256("phantom-x3dh-v2"),
     *                              info = "phantom-x3dh-rootkey-v1",
     *                              L = 32)
     *
     * When [recipientOPK] is null, the IKM is `DH1||DH2||DH3` and the
     * resulting session lacks the OPK round (still HKDF-derived, still
     * domain-separated from the legacy SHA256 path).
     *
     * Returns a [RatchetState] where:
     * - rootKey = masterSecret
     * - sendingRatchetKeyPair is a freshly generated ephemeral DH keypair
     *   (this is the F15 fix — never the identity key)
     * - receivingRatchetPublicKey = recipientSignedPreKey (bootstrap step)
     *
     * The initiator's ephemeral public key MUST be transmitted to the
     * recipient out-of-band (e.g. in the initial message header) along
     * with the OPK keyId (when used) so [recipientHandshake4DH] can pull
     * the right private key.
     */
    fun initiatorHandshake4DH(
        initiatorIdentityKeyPair: DhKeyPair,
        recipientIdentityPublicKey: DhPublicKey,
        recipientSignedPreKey: DhPublicKey,
        recipientOPK: DhPublicKey?,
    ): RatchetState

    /**
     * 4-DH recipient handshake.
     *
     * Mirrors [initiatorHandshake4DH] from Bob's side. Same IKM, same HKDF
     * params, same [RatchetState] shape — masterSecret matches what the
     * initiator computed.
     *
     * When [recipientOPKPair] is null the recipient must have advertised
     * a bundle without an OPK; otherwise the initiator chose to skip OPK
     * and that's a protocol violation the caller (SessionManager in PR C)
     * is responsible for rejecting *before* calling this method.
     *
     * The OPK private key MUST be deleted from local storage immediately
     * after this call — that is what makes the OPK round one-time.
     */
    fun recipientHandshake4DH(
        recipientIdentityKeyPair: DhKeyPair,
        recipientSignedPreKeyPair: DhKeyPair,
        recipientOPKPair: DhKeyPair?,
        initiatorIdentityPublicKey: DhPublicKey,
        initiatorEphemeralPublicKey: DhPublicKey,
    ): RatchetState

    // ── primitives ───────────────────────────────────────────────────────────

    /** Generates a fresh Curve25519 DH keypair. */
    fun generateDhKeyPair(): DhKeyPair

    /**
     * Raw X25519 scalar multiplication.
     * Alpha-0 bootstrap: both parties independently derive the same shared
     * secret from their identity keys without a prekey server.
     */
    fun computeSharedSecret(privateKey: DhPrivateKey, publicKey: DhPublicKey): ByteArray
}
