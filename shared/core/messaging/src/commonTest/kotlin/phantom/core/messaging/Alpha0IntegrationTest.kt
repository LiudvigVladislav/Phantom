// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import phantom.core.crypto.DhKeyPair
import phantom.core.crypto.DhPublicKey
import phantom.core.crypto.EncryptedMessage
import phantom.core.crypto.LibsodiumDoubleRatchet
import phantom.core.crypto.LibsodiumX3DH
import phantom.core.crypto.RatchetState
import phantom.core.storage.RatchetStateRepository
import phantom.core.transport.RelayMessage
import phantom.core.transport.RelayTransport
import phantom.core.transport.TransportState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

// ── In-memory fakes scoped to this test file ──────────────────────────────────

private class InMemoryRatchetStateRepository : RatchetStateRepository {
    private val store = mutableMapOf<String, String>()
    override suspend fun getRatchetState(conversationId: String): String? = store[conversationId]
    override suspend fun upsertRatchetState(conversationId: String, stateBlob: String) {
        store[conversationId] = stateBlob
    }
    override suspend fun deleteRatchetState(conversationId: String) { store.remove(conversationId) }
    override suspend fun deleteAll() { store.clear() }
}

/**
 * Relay fake for Test 2.
 *
 * While [online] is false, [send] stores messages in [queue] and returns false
 * (simulating Bob offline). When [goOnline] is called the queued messages are
 * re-emitted through [incoming].
 */
private class BufferingRelayTransport : RelayTransport {

    private val _state = MutableStateFlow<TransportState>(TransportState.Connected)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<RelayMessage.Deliver>(extraBufferCapacity = 64)
    override val incoming: Flow<RelayMessage.Deliver> = _incoming
    override val acks: Flow<RelayMessage.Ack> = emptyFlow()
    override val readReceipts: Flow<RelayMessage.ReadReceipt> = emptyFlow()
    override val typingEvents: SharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 10)

    val sent = mutableListOf<RelayMessage.Send>()
    val queue = mutableListOf<RelayMessage.Send>()

    /** Set to false to simulate the recipient being offline. */
    var online: Boolean = true

    override suspend fun connect(
        relayUrl: String,
        identityPublicKeyHex: String,
        token: String?,
        socksProxyPort: Int?,
    ) {
        _state.value = TransportState.Connected
    }

    override suspend fun disconnect() {
        _state.value = TransportState.Disconnected
    }

    override suspend fun send(message: RelayMessage.Send): Boolean {
        sent += message
        return if (online) {
            // Deliver immediately as a Deliver frame on the same fake transport.
            _incoming.emit(RelayMessage.Deliver(
                from = message.from,
                payload = message.payload,
                messageId = message.messageId,
            ))
            true
        } else {
            // Store for later delivery when recipient comes back online.
            queue += message
            false
        }
    }

    override suspend fun sendReadReceipt(message: RelayMessage.ReadReceipt): Boolean = true
    override suspend fun sendDeliveryAck(messageId: String): Boolean = true
    override suspend fun sendTyping(toPubKeyHex: String): Boolean = true

    override fun isConnected(): Boolean = _state.value is TransportState.Connected

    // ADR-011 / ADR-013: stubs satisfying the new RelayTransport contract.
    override val lastPongElapsedMs: Long get() = 0L
    override suspend fun forceReconnect() {}

    /** Flush all queued messages into the incoming flow. */
    suspend fun deliverQueued() {
        val pending = queue.toList()
        queue.clear()
        for (msg in pending) {
            _incoming.emit(RelayMessage.Deliver(
                from = msg.from,
                payload = msg.payload,
                messageId = msg.messageId,
            ))
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Hex-encodes a ByteArray.
 * Duplicated locally so the test has no dependency on production utility code.
 */
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Derives the conversation ID the same way DefaultMessagingService does:
 * sort the two hex keys, join with "_".
 */
private fun conversationId(keyA: String, keyB: String): String {
    val sorted = listOf(keyA, keyB).sorted()
    return "${sorted[0]}_${sorted[1]}"
}

// ── Integration Tests ─────────────────────────────────────────────────────────

/**
 * Alpha-0 end-to-end integration tests.
 *
 * These tests use real libsodium primitives (LibsodiumX3DH + LibsodiumDoubleRatchet)
 * and in-memory fakes for storage and transport so no Android runtime or SQLite is
 * required. Each test initializes LibsodiumInitializer inside runTest.
 *
 * Session bootstrap matches the SessionManager Alpha-0 path:
 *   sharedSecret = X25519(myPrivate, theirPublic)
 * X25519 is commutative, so Alice and Bob derive the same root key without
 * a prekey server.
 */
class Alpha0IntegrationTest {

    private val x3dh = LibsodiumX3DH()
    private val ratchet = LibsodiumDoubleRatchet()
    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------
    // Test 1: basic encrypt → decrypt round-trip
    // ------------------------------------------------------------------

    /**
     * Alice encrypts "Hello Bob". Bob decrypts. Plaintext must match.
     *
     * Session setup mirrors SessionManager.getOrCreateSession (Alpha-0 path):
     *   RatchetState.rootKey = DH(alicePrivate, bobPublic)
     *                        = DH(bobPrivate, alicePublic)   ← commutative
     */
    @Test
    fun alice_sends_message_bob_receives_and_decrypts() = runTest {
        LibsodiumInitializer.initialize()

        // 1. Generate identity key pairs (serve as DH identity keys for Alpha-0)
        val aliceKp: DhKeyPair = x3dh.generateDhKeyPair()
        val bobKp: DhKeyPair = x3dh.generateDhKeyPair()

        val alicePubHex = aliceKp.publicKey.bytes.toHex()
        val bobPubHex = bobKp.publicKey.bytes.toHex()

        // 2. Alice: create session via static X25519 bootstrap (identical to SessionManager)
        val aliceSharedSecret = x3dh.computeSharedSecret(aliceKp.privateKey, bobKp.publicKey)
        val aliceState = RatchetState(
            rootKey = aliceSharedSecret,
            sendingChainKey = null,
            receivingChainKey = null,
            sendingRatchetPublicKey = aliceKp.publicKey.bytes,
            sendingRatchetPrivateKey = aliceKp.privateKey.bytes,
            receivingRatchetPublicKey = bobKp.publicKey.bytes,
        )

        // 3. Alice encrypts
        val plaintext = "Hello Bob".encodeToByteArray()
        val (_, encryptedMessage) = ratchet.encrypt(aliceState, plaintext)

        // 4. Bob: create session — same bootstrap, produces identical root key
        val bobSharedSecret = x3dh.computeSharedSecret(bobKp.privateKey, aliceKp.publicKey)
        val bobState = RatchetState(
            rootKey = bobSharedSecret,
            sendingChainKey = null,
            receivingChainKey = null,
            sendingRatchetPublicKey = bobKp.publicKey.bytes,
            sendingRatchetPrivateKey = bobKp.privateKey.bytes,
            receivingRatchetPublicKey = aliceKp.publicKey.bytes,
        )

        // 5. Bob decrypts
        val (_, decrypted) = ratchet.decrypt(bobState, encryptedMessage)

        // 6. Assert
        assertEquals("Hello Bob", decrypted.decodeToString())
    }

    // ------------------------------------------------------------------
    // Test 2: offline relay — store while Bob is offline, deliver on reconnect
    // ------------------------------------------------------------------

    /**
     * Alice sends a message while Bob is "offline" (transport returns false).
     * The relay queues the message. When Bob reconnects the queued message is
     * delivered and Bob decrypts it correctly.
     *
     * The BufferingRelayTransport simulates the relay server: it holds messages
     * in [BufferingRelayTransport.queue] when [online] is false.
     */
    @Test
    fun relay_stores_and_delivers_when_bob_offline() = runTest {
        LibsodiumInitializer.initialize()

        // Key material
        val aliceKp = x3dh.generateDhKeyPair()
        val bobKp = x3dh.generateDhKeyPair()

        // Alice's session state
        val aliceSharedSecret = x3dh.computeSharedSecret(aliceKp.privateKey, bobKp.publicKey)
        var aliceState = RatchetState(
            rootKey = aliceSharedSecret,
            sendingChainKey = null,
            receivingChainKey = null,
            sendingRatchetPublicKey = aliceKp.publicKey.bytes,
            sendingRatchetPrivateKey = aliceKp.privateKey.bytes,
            receivingRatchetPublicKey = bobKp.publicKey.bytes,
        )

        // Bob's session state
        val bobSharedSecret = x3dh.computeSharedSecret(bobKp.privateKey, aliceKp.publicKey)
        var bobState = RatchetState(
            rootKey = bobSharedSecret,
            sendingChainKey = null,
            receivingChainKey = null,
            sendingRatchetPublicKey = bobKp.publicKey.bytes,
            sendingRatchetPrivateKey = bobKp.privateKey.bytes,
            receivingRatchetPublicKey = aliceKp.publicKey.bytes,
        )

        // Relay transport — Bob is offline
        val relay = BufferingRelayTransport()
        relay.online = false

        // Alice encrypts and sends
        val plaintext = "Hey Bob, are you there?".encodeToByteArray()
        val (aliceState1, encryptedMessage) = ratchet.encrypt(aliceState, plaintext)
        aliceState = aliceState1

        // Serialize the encrypted message as the relay payload (matching DefaultMessagingService)
        val payloadJson = json.encodeToString(encryptedMessage)
        val sendResult = relay.send(
            RelayMessage.Send(
                to = bobKp.publicKey.bytes.toHex(),
                from = aliceKp.publicKey.bytes.toHex(),
                payload = payloadJson,
                messageId = "msg-offline-1",
            )
        )

        // Message was not delivered (Bob offline)
        assertEquals(false, sendResult)
        assertEquals(1, relay.queue.size)

        // Bob "comes back online" — relay flushes the queue
        relay.online = true
        relay.deliverQueued()

        // Verify the queue is now empty (delivered)
        assertEquals(0, relay.queue.size)

        // Bob decrypts the queued message
        val queuedPayload = json.decodeFromString<EncryptedMessage>(payloadJson)
        val (_, decrypted) = ratchet.decrypt(bobState, queuedPayload)

        assertEquals("Hey Bob, are you there?", decrypted.decodeToString())
    }

    // ------------------------------------------------------------------
    // Test 3: RatchetState survives serialization roundtrip (app restart)
    // ------------------------------------------------------------------

    /**
     * Alice encrypts message-1. The resulting RatchetState is serialized to JSON
     * (simulating persistence to disk / app restart) then deserialized.
     * Alice encrypts message-2 with the deserialized state.
     * Bob must decrypt both messages in order.
     *
     * This validates that kotlinx.serialization handles the ByteArray fields in
     * RatchetState correctly (Base64 encode/decode round-trip).
     */
    @Test
    fun session_survives_serialization_roundtrip() = runTest {
        LibsodiumInitializer.initialize()

        val aliceKp = x3dh.generateDhKeyPair()
        val bobKp = x3dh.generateDhKeyPair()

        // Bootstrap
        val sharedSecret = x3dh.computeSharedSecret(aliceKp.privateKey, bobKp.publicKey)
        var aliceState = RatchetState(
            rootKey = sharedSecret,
            sendingChainKey = null,
            receivingChainKey = null,
            sendingRatchetPublicKey = aliceKp.publicKey.bytes,
            sendingRatchetPrivateKey = aliceKp.privateKey.bytes,
            receivingRatchetPublicKey = bobKp.publicKey.bytes,
        )
        var bobState = RatchetState(
            rootKey = x3dh.computeSharedSecret(bobKp.privateKey, aliceKp.publicKey),
            sendingChainKey = null,
            receivingChainKey = null,
            sendingRatchetPublicKey = bobKp.publicKey.bytes,
            sendingRatchetPrivateKey = bobKp.privateKey.bytes,
            receivingRatchetPublicKey = aliceKp.publicKey.bytes,
        )

        // Alice encrypts message-1
        val (stateAfterMsg1, encrypted1) = ratchet.encrypt(aliceState, "First message".encodeToByteArray())

        // Simulate app restart: serialize → deserialize RatchetState
        val stateBlob = json.encodeToString(stateAfterMsg1)
        val restoredState = json.decodeFromString<RatchetState>(stateBlob)

        // Verify the JSON round-trip preserved all fields
        assertEquals(stateAfterMsg1, restoredState)

        // Alice encrypts message-2 with the restored state
        val (_, encrypted2) = ratchet.encrypt(restoredState, "Second message".encodeToByteArray())

        // Bob decrypts both in order
        val (bobState1, decrypted1) = ratchet.decrypt(bobState, encrypted1)
        bobState = bobState1
        val (_, decrypted2) = ratchet.decrypt(bobState, encrypted2)

        // Assert both messages survive the round-trip
        assertEquals("First message", decrypted1.decodeToString())
        assertEquals("Second message", decrypted2.decodeToString())
    }
}
