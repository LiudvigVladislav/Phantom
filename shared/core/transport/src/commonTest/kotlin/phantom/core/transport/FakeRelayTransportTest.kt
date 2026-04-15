package phantom.core.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeRelayTransportTest {

    private class FakeRelayTransport : RelayTransport {
        private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
        override val state: StateFlow<TransportState> = _state.asStateFlow()
        override val incoming: Flow<RelayMessage.Deliver> = emptyFlow()

        val outbox = Channel<RelayMessage.Send>(capacity = Channel.UNLIMITED)

        override suspend fun connect(relayUrl: String, identityPublicKeyHex: String) {
            _state.value = TransportState.Connected
        }

        override suspend fun disconnect() {
            _state.value = TransportState.Disconnected
        }

        override suspend fun send(message: RelayMessage.Send): Boolean {
            if (!isConnected()) return false
            outbox.trySend(message)
            return true
        }

        override fun isConnected(): Boolean = _state.value is TransportState.Connected
    }

    @Test
    fun connect_setsStateToConnected() = runTest {
        val transport = FakeRelayTransport()
        assertEquals(TransportState.Disconnected, transport.state.value)
        transport.connect("ws://relay", "pubkey")
        assertEquals(TransportState.Connected, transport.state.value)
    }

    @Test
    fun send_appearsInOutbox() = runTest {
        val transport = FakeRelayTransport()
        transport.connect("ws://relay", "pubkey")
        val msg = RelayMessage.Send(to = "bob", from = "alice", payload = "abc", messageId = "1")
        val result = transport.send(msg)
        assertTrue(result)
        assertEquals(msg, transport.outbox.receive())
    }

    @Test
    fun send_returnsFalseWhenDisconnected() = runTest {
        val transport = FakeRelayTransport()
        val msg = RelayMessage.Send(to = "bob", from = "alice", payload = "abc", messageId = "2")
        val result = transport.send(msg)
        assertFalse(result)
    }

    @Test
    fun disconnect_setsStateToDisconnected() = runTest {
        val transport = FakeRelayTransport()
        transport.connect("ws://relay", "pubkey")
        transport.disconnect()
        assertEquals(TransportState.Disconnected, transport.state.value)
    }
}
