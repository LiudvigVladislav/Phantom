// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
        override val acks: Flow<RelayMessage.Ack> = emptyFlow()

        private val _typingEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
        override val typingEvents: SharedFlow<String> = _typingEvents.asSharedFlow()

        val outbox = Channel<RelayMessage.Send>(capacity = Channel.UNLIMITED)
        val typingOutbox = Channel<String>(capacity = Channel.UNLIMITED)

        override suspend fun connect(
            relayUrl: String,
            identityPublicKeyHex: String,
            signingPublicKeyHex: String,
            signChallenge: suspend (challenge: ByteArray) -> ByteArray?,
            socksProxyPort: Int?,
        ) {
            _state.value = TransportState.Connected
        }

        override suspend fun disconnect() {
            _state.value = TransportState.Disconnected
        }

        override suspend fun disconnectAndJoin(timeoutMs: Long): Boolean {
            disconnect()
            return true
        }

        override suspend fun send(message: RelayMessage.Send): Boolean {
            if (!isConnected()) return false
            outbox.trySend(message)
            return true
        }

        override suspend fun sendDeliveryAck(messageId: String): Boolean = isConnected()

        override suspend fun sendTyping(toPubKeyHex: String): Boolean {
            if (!isConnected()) return false
            typingOutbox.trySend(toPubKeyHex)
            _typingEvents.tryEmit(toPubKeyHex)
            return true
        }

        override fun isConnected(): Boolean = _state.value is TransportState.Connected

        // ADR-011 / ADR-013 additions to RelayTransport. The fake does not need
        // a real reconnect machinery for unit-test purposes; expose stable
        // values that won't trigger the alarm receiver's stale-pong path.
        override val lastPongElapsedMs: Long get() = 0L
        // PR-H1c: same fixed-fresh value as lastPongElapsedMs so unit tests
        // never trigger the alarm receiver's proactive-reconnect path.
        override val lastInboundFrameElapsedMs: Long get() = 0L

        override val pendingAckCount: Int get() = 0

        override suspend fun forceReconnect() {
            _state.value = TransportState.Disconnected
            _state.value = TransportState.Connected
        }
    }

    @Test
    fun connect_setsStateToConnected() = runTest {
        val transport = FakeRelayTransport()
        assertEquals(TransportState.Disconnected, transport.state.value)
        transport.connect("ws://relay", "pubkey", signingPublicKeyHex = "sig", signChallenge = { ByteArray(64) })
        assertEquals(TransportState.Connected, transport.state.value)
    }

    @Test
    fun send_appearsInOutbox() = runTest {
        val transport = FakeRelayTransport()
        transport.connect("ws://relay", "pubkey", signingPublicKeyHex = "sig", signChallenge = { ByteArray(64) })
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
        transport.connect("ws://relay", "pubkey", signingPublicKeyHex = "sig", signChallenge = { ByteArray(64) })
        transport.disconnect()
        assertEquals(TransportState.Disconnected, transport.state.value)
    }

    // ── Typing indicator tests ────────────────────────────────────────────────

    @Test
    fun sendTyping_appearsInTypingOutbox_whenConnected() = runTest {
        val transport = FakeRelayTransport()
        transport.connect("ws://relay", "pubkey", signingPublicKeyHex = "sig", signChallenge = { ByteArray(64) })
        val result = transport.sendTyping("bob-pubkey-hex")
        assertTrue(result)
        assertEquals("bob-pubkey-hex", transport.typingOutbox.receive())
    }

    @Test
    fun sendTyping_returnsFalse_whenDisconnected() = runTest {
        val transport = FakeRelayTransport()
        val result = transport.sendTyping("bob-pubkey-hex")
        assertFalse(result)
        assertTrue(transport.typingOutbox.isEmpty)
    }

    @Test
    fun typingEvents_emitsRecipientKey_whenSendTypingCalled() = runTest {
        val transport = FakeRelayTransport()
        transport.connect("ws://relay", "pubkey", signingPublicKeyHex = "sig", signChallenge = { ByteArray(64) })

        val collected = mutableListOf<String>()
        // `launch` is a CoroutineScope extension; inside runTest the
        // lambda receiver is the TestScope so the unqualified call
        // resolves correctly.
        val job = launch {
            transport.typingEvents.collect { collected.add(it) }
        }

        // CRITICAL ordering: pump the scheduler once BEFORE emitting so
        // the collector actually subscribes to the SharedFlow. If we emit
        // first, MutableSharedFlow(replay = 0) drops the values because
        // it has no subscribers yet — that's how the assertion came up
        // with `expected:<2> but was:<0>`.
        testScheduler.runCurrent()

        transport.sendTyping("alice-key")
        transport.sendTyping("alice-key")

        // Drain the now-pending emissions to the live collector.
        testScheduler.runCurrent()
        job.cancel()

        assertEquals(2, collected.size)
        assertTrue(collected.all { it == "alice-key" })
    }
}
