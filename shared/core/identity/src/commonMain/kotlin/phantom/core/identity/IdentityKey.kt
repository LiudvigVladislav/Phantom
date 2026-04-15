package phantom.core.identity

import kotlinx.serialization.Serializable

@JvmInline
value class PublicKey(val bytes: ByteArray)

@JvmInline
value class PrivateKey(val bytes: ByteArray)

data class IdentityKeyPair(
    val publicKey: PublicKey,
    val privateKey: PrivateKey,
)

@Serializable
data class IdentityRecord(
    val id: String,
    val username: String,
    val publicKeyHex: String,
    val createdAt: Long,
)
