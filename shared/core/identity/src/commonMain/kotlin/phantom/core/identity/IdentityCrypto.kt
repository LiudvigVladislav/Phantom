package phantom.core.identity

interface IdentityCrypto {
    fun generateKeyPair(): IdentityKeyPair
    fun sign(message: ByteArray, privateKey: PrivateKey): ByteArray
    fun verify(message: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean
    fun publicKeyToHex(key: PublicKey): String
    fun hexToPublicKey(hex: String): PublicKey
}
