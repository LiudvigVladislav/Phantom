package phantom.core.storage

data class SenderKeyEntity(
    val groupId: String,
    val memberPubkeyHex: String,
    val chainKeyHex: String,
    val iteration: Long,
    val signingPubHex: String,
    val signingPrivHex: String,
)
