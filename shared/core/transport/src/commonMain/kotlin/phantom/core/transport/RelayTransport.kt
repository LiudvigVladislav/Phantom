package phantom.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface RelayTransport {
    val state: StateFlow<TransportState>
    val incoming: Flow<RelayMessage.Deliver>

    suspend fun connect(relayUrl: String, identityPublicKeyHex: String)
    suspend fun disconnect()
    suspend fun send(message: RelayMessage.Send): Boolean
    fun isConnected(): Boolean
}
