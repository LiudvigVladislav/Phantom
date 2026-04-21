package phantom.core.storage

interface SenderKeyRepository {
    suspend fun get(groupId: String, memberPubkeyHex: String): SenderKeyEntity?
    suspend fun upsert(entity: SenderKeyEntity)
    suspend fun deleteForGroup(groupId: String)
}
