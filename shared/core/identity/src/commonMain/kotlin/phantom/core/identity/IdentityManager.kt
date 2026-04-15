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
            return existing to crypto.generateKeyPair()
        }
        val keyPair = crypto.generateKeyPair()
        val record = IdentityRecord(
            id = uuid4().toString(),
            username = username,
            publicKeyHex = crypto.publicKeyToHex(keyPair.publicKey),
            createdAt = Clock.System.now().toEpochMilliseconds(),
        )
        repository.saveIdentity(record)
        return record to keyPair
    }

    suspend fun getIdentity(): IdentityRecord? = repository.loadIdentity()

    fun exportPublicKeyHex(keyPair: IdentityKeyPair): String = crypto.publicKeyToHex(keyPair.publicKey)
}
