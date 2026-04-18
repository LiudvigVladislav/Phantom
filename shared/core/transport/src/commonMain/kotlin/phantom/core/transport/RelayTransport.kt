package phantom.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface RelayTransport {
    val state: StateFlow<TransportState>
    val incoming: Flow<RelayMessage.Deliver>
    val acks: Flow<RelayMessage.Ack>
    val readReceipts: Flow<RelayMessage.ReadReceipt>

    suspend fun connect(relayUrl: String, identityPublicKeyHex: String, token: String? = null)
    suspend fun disconnect()
    suspend fun send(message: RelayMessage.Send): Boolean
    suspend fun sendReadReceipt(message: RelayMessage.ReadReceipt): Boolean
    fun isConnected(): Boolean
}
