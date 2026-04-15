package phantom.core.identity

import com.ionspin.kotlin.crypto.signature.Signature

class LibsodiumIdentityCrypto : IdentityCrypto {

    override fun generateKeyPair(): IdentityKeyPair {
        val kp = Signature.keypair()
        return IdentityKeyPair(
            publicKey = PublicKey(kp.publicKey.toByteArray()),
            privateKey = PrivateKey(kp.secretKey.toByteArray()),
        )
    }

    override fun sign(message: ByteArray, privateKey: PrivateKey): ByteArray {
        return Signature.signDetached(
            message = message.toUByteArray(),
            secretKey = privateKey.bytes.toUByteArray(),
        ).toByteArray()
    }

    override fun verify(message: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean {
        return try {
            Signature.verifyDetached(
                message = message.toUByteArray(),
                signature = signature.toUByteArray(),
                publicKey = publicKey.bytes.toUByteArray(),
            )
        } catch (_: Exception) {
            false
        }
    }

    override fun publicKeyToHex(key: PublicKey): String = key.bytes.toHexString()

    override fun hexToPublicKey(hex: String): PublicKey = PublicKey(hex.hexToByteArray())
}

private fun ByteArray.toHexString(): String = joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) { i ->
        substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
