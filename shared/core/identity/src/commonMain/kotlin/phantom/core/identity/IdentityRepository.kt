package phantom.core.identity

interface IdentityRepository {
    suspend fun createIdentity(username: String): IdentityKeyPair
    suspend fun loadIdentity(): IdentityRecord?
    suspend fun saveIdentity(record: IdentityRecord)
    suspend fun deleteIdentity()
}
