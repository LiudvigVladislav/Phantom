package phantom.core.storage

data class GroupMemberEntity(
    val groupId: String,
    val pubkeyHex: String,
    val username: String,
    val joinedAt: Long,
)
