// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.identity

import com.ionspin.kotlin.crypto.box.Box

/**
 * Alpha-0: uses X25519 (Box.keypair) for both identity and DH.
 * Ed25519 signing is deferred to Beta when secure key storage is available.
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

    // sign/verify not used in Alpha-0 — deferred to Beta
    override fun sign(message: ByteArray, privateKey: PrivateKey): ByteArray =
        throw NotImplementedError("Signing deferred to Beta")

    override fun verify(message: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean =
        throw NotImplementedError("Verification deferred to Beta")

    override fun publicKeyToHex(key: PublicKey): String = key.bytes.toHexString()

    override fun hexToPublicKey(hex: String): PublicKey = PublicKey(hex.hexToByteArray())
}

private fun ByteArray.toHexString(): String =
    joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) { i ->
        substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
