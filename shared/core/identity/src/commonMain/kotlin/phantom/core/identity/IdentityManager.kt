package phantom.core.identity

import com.benasher44.uuid.uuid4
import kotlinx.datetime.Clock

class IdentityManager(
    private val crypto: IdentityCrypto,
    private val repository: IdentityRepository,
) {
    suspend fun createOrLoad(username: String): Pair<IdentityRecord, IdentityKeyPair> {
        val existing = repository.loadIdentity()
        if (existing != null) {
            // Reconstruct key pair from stored private key hex
            val keyPair = IdentityKeyPair(
                publicKey  = PublicKey(existing.publicKeyHex.hexToByteArray()),
                privateKey = PrivateKey(existing.dhPrivateKeyHex.hexToByteArray()),
            )
            return existing to keyPair
        }
        val keyPair = crypto.generateKeyPair()
        val record = IdentityRecord(
            id              = uuid4().toString(),
            username        = username,
            publicKeyHex    = crypto.publicKeyToHex(keyPair.publicKey),
            dhPrivateKeyHex = keyPair.privateKey.bytes.toHexString(),
            createdAt       = Clock.System.now().toEpochMilliseconds(),
        )
        repository.saveIdentity(record)
        return record to keyPair
    }

    suspend fun getIdentity(): IdentityRecord? = repository.loadIdentity()

    fun exportPublicKeyHex(keyPair: IdentityKeyPair): String = crypto.publicKeyToHex(keyPair.publicKey)
}

private fun ByteArray.toHexString(): String =
    joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
