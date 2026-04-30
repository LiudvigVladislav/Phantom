// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.identity

import com.ionspin.kotlin.crypto.box.Box
import com.ionspin.kotlin.crypto.signature.Signature

/**
 * X25519 (DH identity) generated via libsodium [Box.keypair].
 * Ed25519 (signing identity) generated via libsodium [Signature.keypair].
 *
 * The X25519 [sign]/[verify] entry points still throw because the X25519
 * primitive cannot sign. The Ed25519 entry points
 * ([signWithIdentity]/[verifyWithIdentity]) are the real implementation.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class LibsodiumIdentityCrypto : IdentityCrypto {

    override fun generateKeyPair(): IdentityKeyPair {
        val kp = Box.keypair()
        return IdentityKeyPair(
            publicKey  = PublicKey(kp.publicKey.toByteArray()),
            privateKey = PrivateKey(kp.secretKey.toByteArray()),
        )
    }

    override fun generateSigningKeyPair(): IdentitySigningKeyPair {
        // libsodium's `crypto_sign_keypair`: 32-byte verifying key + 64-byte
        // secret (containing seed + verifying key concatenated, so signing
        // doesn't need the public half passed separately).
        val kp = Signature.keypair()
        return IdentitySigningKeyPair(
            publicKey  = SigningPublicKey(kp.publicKey.toByteArray()),
            privateKey = SigningPrivateKey(kp.secretKey.toByteArray()),
        )
    }

    // X25519 keys cannot sign — the Ed25519 path lives below.
    override fun sign(message: ByteArray, privateKey: PrivateKey): ByteArray =
        throw NotImplementedError(
            "X25519 identity key cannot sign — use signWithIdentity with " +
                "the Ed25519 IdentitySigningKeyPair instead",
        )

    override fun verify(message: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean =
        throw NotImplementedError(
            "X25519 identity key cannot verify signatures — use " +
                "verifyWithIdentity with the Ed25519 SigningPublicKey instead",
        )

    override fun signWithIdentity(
        message: ByteArray,
        privateKey: SigningPrivateKey,
    ): ByteArray =
        Signature.detached(
            message   = message.toUByteArray(),
            secretKey = privateKey.bytes.toUByteArray(),
        ).toByteArray()

    override fun verifyWithIdentity(
        message: ByteArray,
        signature: ByteArray,
        publicKey: SigningPublicKey,
    ): Boolean {
        if (signature.size != 64) return false
        if (publicKey.bytes.size != 32) return false
        return runCatching {
            Signature.verifyDetached(
                signature = signature.toUByteArray(),
                message   = message.toUByteArray(),
                publicKey = publicKey.bytes.toUByteArray(),
            )
        }.isSuccess
    }

    override fun publicKeyToHex(key: PublicKey): String = key.bytes.toHexString()

    override fun hexToPublicKey(hex: String): PublicKey = PublicKey(hex.hexToByteArray())

    override fun signingPublicKeyToHex(key: SigningPublicKey): String =
        key.bytes.toHexString()

    override fun hexToSigningPublicKey(hex: String): SigningPublicKey =
        SigningPublicKey(hex.hexToByteArray())
}

private fun ByteArray.toHexString(): String =
    joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) { i ->
        substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
