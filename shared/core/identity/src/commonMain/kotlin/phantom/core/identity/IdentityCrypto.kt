// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.identity

interface IdentityCrypto {
    /**
     * X25519 keypair used for the routing identity and X3DH DH operations.
     * Generated once at onboarding; never rotated.
     */
    fun generateKeyPair(): IdentityKeyPair

    /**
     * Ed25519 keypair used for signing published prekey bundles. Generated
     * alongside [generateKeyPair] for new identities, or backfilled by
     * [IdentityManager.backfillSigningKeyPair] for Alpha 1 records that
     * predate ADR-009.
     */
    fun generateSigningKeyPair(): IdentitySigningKeyPair

    /**
     * @deprecated Throws on libsodium-backed implementations. The X25519
     * identity key cannot sign — the Ed25519 [IdentitySigningKeyPair] does
     * that. Kept on the interface for source compatibility with one
     * remaining test stub that will be ported to [signWithIdentity] in PR D
     * (ADR-017 SenderKey signing removal).
     */
    fun sign(message: ByteArray, privateKey: PrivateKey): ByteArray

    /**
     * @deprecated See [sign].
     */
    fun verify(message: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean

    /**
     * Sign [message] with the Ed25519 secret half of an
     * [IdentitySigningKeyPair]. Returns a 64-byte detached signature.
     */
    fun signWithIdentity(message: ByteArray, privateKey: SigningPrivateKey): ByteArray

    /**
     * Verify a 64-byte detached Ed25519 [signature] over [message] using
     * an [IdentitySigningKeyPair] public half. Wraps libsodium's
     * throw-on-invalid behaviour into a boolean return so callers don't
     * have to catch.
     */
    fun verifyWithIdentity(
        message: ByteArray,
        signature: ByteArray,
        publicKey: SigningPublicKey,
    ): Boolean

    fun publicKeyToHex(key: PublicKey): String
    fun hexToPublicKey(hex: String): PublicKey

    fun signingPublicKeyToHex(key: SigningPublicKey): String
    fun hexToSigningPublicKey(hex: String): SigningPublicKey
}
