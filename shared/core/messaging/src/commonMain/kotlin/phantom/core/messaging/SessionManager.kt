// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import phantom.core.crypto.DhKeyPair
import phantom.core.crypto.DhPublicKey
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
        val remotePublicKey = DhPublicKey(remoteIdentityPublicKeyHex.hexToByteArray())

        // Alpha-0: static ECDH bootstrap. Both parties independently derive the
        // same root key: DH(my_identity_private, their_identity_public).
        // No prekey server required. Forward secrecy begins after the first
        // DH ratchet step on the first message.
        val sharedSecret = x3dh.computeSharedSecret(localIdentityKeyPair.privateKey, remotePublicKey)
        val state = RatchetState(
            rootKey = sharedSecret,
            sendingChainKey = null,
            receivingChainKey = null,
            sendingRatchetPublicKey = localIdentityKeyPair.publicKey.bytes,
            sendingRatchetPrivateKey = localIdentityKeyPair.privateKey.bytes,
            receivingRatchetPublicKey = remotePublicKey.bytes,
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
