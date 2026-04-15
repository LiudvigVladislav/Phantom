package phantom.core.messaging

import phantom.core.crypto.DhKeyPair
import phantom.core.crypto.RatchetState
import phantom.core.crypto.X3DHProtocol
import phantom.core.storage.RatchetStateRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class SessionManager(
    private val x3dh: X3DHProtocol,
    private val ratchetStateRepository: RatchetStateRepository,
    private val json: Json,
) {
    suspend fun getOrCreateSession(
        conversationId: String,
        localIdentityKeyPair: DhKeyPair,
        remoteIdentityPublicKeyHex: String,
    ): RatchetState {
        val existing = ratchetStateRepository.getRatchetState(conversationId)
        if (existing != null) {
            return json.decodeFromString(existing)
        }
        val remotePublicKey = phantom.core.crypto.DhPublicKey(
            remoteIdentityPublicKeyHex.hexToByteArray()
        )
        val signedPreKey = x3dh.generateDhKeyPair()
        val state = x3dh.initiatorHandshake(
            initiatorIdentityKeyPair = localIdentityKeyPair,
            recipientIdentityPublicKey = remotePublicKey,
            recipientSignedPreKey = signedPreKey.publicKey,
        )
        saveSession(conversationId, state)
        return state
    }

    suspend fun saveSession(conversationId: String, state: RatchetState) {
        ratchetStateRepository.upsertRatchetState(conversationId, json.encodeToString(state))
    }

    suspend fun deleteSession(conversationId: String) {
        ratchetStateRepository.deleteRatchetState(conversationId)
    }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
