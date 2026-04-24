package phantom.core.messaging

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import phantom.core.crypto.DoubleRatchet
import phantom.core.crypto.DhKeyPair
import phantom.core.crypto.DhPublicKey
import phantom.core.crypto.DhPrivateKey
import phantom.core.crypto.EncryptedMessage
import phantom.core.crypto.RatchetState
import phantom.core.crypto.X3DHProtocol
import phantom.core.identity.IdentityRecord
import phantom.core.storage.ConversationEntity
import phantom.core.storage.ConversationRepository
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageRepository
import phantom.core.storage.MessageStatus
import phantom.core.storage.RatchetStateRepository
import phantom.core.storage.ReactionEntry
import phantom.core.storage.ReactionRepository
import phantom.core.transport.RelayMessage
import phantom.core.transport.RelayTransport
import phantom.core.transport.TransportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// ── Fakes ────────────────────────────────────────────────────────────────────

private class FakeMessageRepository : MessageRepository {
    val messages = mutableListOf<MessageEntity>()
    val statusUpdates = mutableMapOf<String, MessageStatus>()

    override suspend fun getMessages(conversationId: String) =
        messages.filter { it.conversationId == conversationId }

    override suspend fun insertMessage(entity: MessageEntity) { messages += entity }
    override suspend fun updateStatus(messageId: String, status: MessageStatus) {
        statusUpdates[messageId] = status
    }
    override suspend fun updateMessageText(messageId: String, text: String) {
        val i = messages.indexOfFirst { it.id == messageId }
        if (i != -1) messages[i] = messages[i].copy(plaintextCache = text)
    }
    override suspend fun deleteMessage(messageId: String) { messages.removeAll { it.id == messageId } }
    override suspend fun deleteMessagesForConversation(conversationId: String) {
        messages.removeAll { it.conversationId == conversationId }
    }
    override suspend fun setExpiresAt(messageId: String, expiresAtMs: Long) {
        val i = messages.indexOfFirst { it.id == messageId }
        if (i != -1) messages[i] = messages[i].copy(expiresAtMs = expiresAtMs)
    }
    override suspend fun getNextExpiry(): Long? =
        messages.mapNotNull { it.expiresAtMs }.minOrNull()
    override suspend fun deleteExpiredMessages() {
        val now = System.currentTimeMillis()
        messages.removeAll { msg -> msg.expiresAtMs?.let { it <= now } == true }
    }
    override suspend fun pinMessage(messageId: String, pinned: Boolean) {
        val i = messages.indexOfFirst { it.id == messageId }
        if (i != -1) messages[i] = messages[i].copy(pinned = pinned)
    }
    override suspend fun getPinnedMessages(conversationId: String): List<MessageEntity> =
        messages.filter { it.conversationId == conversationId && it.pinned }
    override suspend fun saveMessage(id: String) {
        val i = messages.indexOfFirst { it.id == id }
        if (i != -1) messages[i] = messages[i].copy(saved = true)
    }
    override suspend fun unsaveMessage(id: String) {
        val i = messages.indexOfFirst { it.id == id }
        if (i != -1) messages[i] = messages[i].copy(saved = false)
    }
    override suspend fun getSavedMessages(): List<MessageEntity> = messages.filter { it.saved }
}

private class FakeConversationRepository : ConversationRepository {
    val store = mutableMapOf<String, ConversationEntity>()

    override suspend fun getAllConversations() = store.values.toList()
    override suspend fun getActiveConversations() = store.values.filter { !it.blocked }.toList()
    override suspend fun getMessageRequests() = store.values.filter { it.trustTier == phantom.core.storage.TrustTier.REQUEST }.toList()
    override suspend fun getConversation(id: String) = store[id]
    override suspend fun upsertConversation(entity: ConversationEntity) { store[entity.id] = entity }
    override suspend fun incrementUnread(conversationId: String) {
        store[conversationId]?.let { store[conversationId] = it.copy(unreadCount = it.unreadCount + 1) }
    }
    override suspend fun resetUnread(conversationId: String) {
        store[conversationId]?.let { store[conversationId] = it.copy(unreadCount = 0) }
    }
    override suspend fun updateNotes(conversationId: String, notes: String?) {
        store[conversationId]?.let { store[conversationId] = it.copy(notes = notes) }
    }
    override suspend fun blockConversation(conversationId: String) {
        store[conversationId]?.let { store[conversationId] = it.copy(blocked = true) }
    }
    override suspend fun acceptRequest(conversationId: String) {
        store[conversationId]?.let { store[conversationId] = it.copy(trustTier = phantom.core.storage.TrustTier.TRUSTED) }
    }
    override suspend fun deleteConversation(id: String) { store.remove(id) }
    override suspend fun getBlockedConversations() = store.values.filter { it.blocked }.toList()
    override suspend fun unblockConversation(conversationId: String) {
        store[conversationId]?.let { store[conversationId] = it.copy(blocked = false) }
    }
    override suspend fun setVerified(conversationId: String, verified: Boolean) {
        store[conversationId]?.let { store[conversationId] = it.copy(isVerified = verified) }
    }
    override suspend fun setDisappearingTimer(conversationId: String, secs: Long) {
        store[conversationId]?.let { store[conversationId] = it.copy(disappearingTimerSecs = secs) }
    }
    override suspend fun getDisappearingTimer(conversationId: String): Long =
        store[conversationId]?.disappearingTimerSecs ?: 0L
    override suspend fun archiveConversation(id: String) {
        store[id]?.let { store[id] = it.copy(archived = true) }
    }
    override suspend fun unarchiveConversation(id: String) {
        store[id]?.let { store[id] = it.copy(archived = false) }
    }
    override suspend fun getArchivedConversations() = store.values.filter { it.archived }.toList()
    override suspend fun setIdentityKeyChangedAt(conversationId: String, ts: Long) {
        store[conversationId]?.let { store[conversationId] = it.copy(identityKeyChangedAt = ts) }
    }
    override suspend fun clearIdentityKeyChangedAt(conversationId: String) {
        store[conversationId]?.let { store[conversationId] = it.copy(identityKeyChangedAt = null) }
    }
}

private class FakeReactionRepository : ReactionRepository {
    data class Key(val messageId: String, val senderKeyHex: String)
    val store = mutableMapOf<Key, ReactionEntry>()

    override suspend fun upsertReaction(messageId: String, senderKeyHex: String, emoji: String, createdAt: Long) {
        store[Key(messageId, senderKeyHex)] = ReactionEntry(emoji = emoji, senderKeyHex = senderKeyHex)
    }
    override suspend fun deleteReaction(messageId: String, senderKeyHex: String) {
        store.remove(Key(messageId, senderKeyHex))
    }
    override suspend fun getReactions(messageId: String): List<ReactionEntry> =
        store.entries
            .filter { (k, _) -> k.messageId == messageId }
            .map { (_, v) -> v }
}

private class FakeRatchetStateRepository : RatchetStateRepository {
    val store = mutableMapOf<String, String>()
    override suspend fun getRatchetState(conversationId: String) = store[conversationId]
    override suspend fun upsertRatchetState(conversationId: String, stateBlob: String) { store[conversationId] = stateBlob }
    override suspend fun deleteRatchetState(conversationId: String) { store.remove(conversationId) }
}

private class FakeRelayTransport : RelayTransport {
    private val _state = MutableStateFlow<TransportState>(TransportState.Connected)
    override val state: StateFlow<TransportState> = _state
    override val incoming: Flow<RelayMessage.Deliver> = emptyFlow()
    override val acks: Flow<RelayMessage.Ack> = emptyFlow()
    override val readReceipts: Flow<RelayMessage.ReadReceipt> = emptyFlow()
    override val typingEvents: SharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 10)
    val sent = mutableListOf<RelayMessage.Send>()
    override suspend fun connect(relayUrl: String, identityPublicKeyHex: String, token: String?) {}
    override suspend fun disconnect() {}
    override suspend fun send(message: RelayMessage.Send): Boolean { sent += message; return true }
    override suspend fun sendReadReceipt(message: RelayMessage.ReadReceipt): Boolean = true
    override suspend fun sendTyping(toPubKeyHex: String): Boolean = true
    override fun isConnected() = true
}

// Passthrough ratchet — identity transform for testing
private class PassthroughDoubleRatchet : DoubleRatchet {
    private val json = Json { ignoreUnknownKeys = true }
    override fun encrypt(state: RatchetState, plaintext: ByteArray): Pair<RatchetState, EncryptedMessage> =
        state to EncryptedMessage(
            ratchetPublicKey = state.sendingRatchetPublicKey,
            messageIndex = state.sendCount,
            ciphertext = plaintext,
            nonce = ByteArray(24),
        )
    override fun decrypt(state: RatchetState, message: EncryptedMessage): Pair<RatchetState, ByteArray> =
        state to message.ciphertext
}

private class PassthroughX3DH : X3DHProtocol {
    override fun generateDhKeyPair() = DhKeyPair(
        DhPublicKey(ByteArray(32) { 1 }),
        DhPrivateKey(ByteArray(32) { 2 }),
    )
    override fun computeSharedSecret(privateKey: phantom.core.crypto.DhPrivateKey, publicKey: DhPublicKey): ByteArray =
        ByteArray(32)
    override fun initiatorHandshake(
        initiatorIdentityKeyPair: DhKeyPair,
        recipientIdentityPublicKey: DhPublicKey,
        recipientSignedPreKey: DhPublicKey,
    ) = RatchetState(
        rootKey = ByteArray(32),
        sendingChainKey = ByteArray(32),
        receivingChainKey = ByteArray(32),
        sendingRatchetPublicKey = ByteArray(32),
        sendingRatchetPrivateKey = ByteArray(32),
        receivingRatchetPublicKey = ByteArray(32),
    )
    override fun recipientHandshake(
        recipientIdentityKeyPair: DhKeyPair,
        recipientSignedPreKeyPair: DhKeyPair,
        initiatorIdentityPublicKey: DhPublicKey,
        initiatorEphemeralPublicKey: DhPublicKey,
    ) = initiatorHandshake(recipientIdentityKeyPair, initiatorIdentityPublicKey, initiatorEphemeralPublicKey)
}

// ── Tests ─────────────────────────────────────────────────────────────────────

class DefaultMessagingServiceTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val identity = IdentityRecord(
        id = "id-1",
        username = "alice",
        publicKeyHex = "aabb",
        dhPrivateKeyHex = "ccdd",
        createdAt = 0L,
    )
    private val localKeyPair = DhKeyPair(
        DhPublicKey(ByteArray(32) { 0xAA.toByte() }),
        DhPrivateKey(ByteArray(32) { 0xBB.toByte() }),
    )

    private fun buildService(
        testScope: TestScope,
        msgRepo: FakeMessageRepository = FakeMessageRepository(),
        convRepo: FakeConversationRepository = FakeConversationRepository(),
        transport: FakeRelayTransport = FakeRelayTransport(),
        reactionRepo: FakeReactionRepository? = null,
    ): DefaultMessagingService {
        val ratchetRepo = FakeRatchetStateRepository()
        val sessionManager = SessionManager(PassthroughX3DH(), ratchetRepo, json)
        return DefaultMessagingService(
            identity = identity,
            localKeyPair = localKeyPair,
            ratchet = PassthroughDoubleRatchet(),
            sessionManager = sessionManager,
            transport = transport,
            messageRepository = msgRepo,
            conversationRepository = convRepo,
            scope = testScope,
            json = json,
            reactionRepository = reactionRepo,
        )
    }

    @Test
    fun sendMessage_storesMessageInRepository() = runTest {
        val msgRepo = FakeMessageRepository()
        val service = buildService(this, msgRepo = msgRepo)
        service.sendMessage(
            OutgoingMessage(
                id = "msg-1",
                conversationId = "conv-1",
                recipientPublicKeyHex = "ccdd",
                text = "Hello!",
            )
        )
        assertEquals(1, msgRepo.messages.size)
        assertEquals("msg-1", msgRepo.messages[0].id)
        assertEquals("Hello!", msgRepo.messages[0].plaintextCache)
    }

    @Test
    fun sendMessage_sendsViaTransport() = runTest {
        val transport = FakeRelayTransport()
        val service = buildService(this, transport = transport)
        service.sendMessage(
            OutgoingMessage(id = "msg-2", conversationId = "conv-1", recipientPublicKeyHex = "ccdd", text = "Hi")
        )
        assertEquals(1, transport.sent.size)
        // With sealed sender, `from` is empty — identity is hidden from the relay.
        assertEquals("", transport.sent[0].from)
        assertEquals("ccdd", transport.sent[0].to)
        // sealedSender blob must be present (identity is sealed, not omitted).
        assertNotNull(transport.sent[0].sealedSender.takeIf { it.isNotEmpty() },
            "sealedSender must be non-empty when sealed sender is used")
    }

    @Test
    fun sendMessage_updatesConversationPreview() = runTest {
        val convRepo = FakeConversationRepository()
        val service = buildService(this, convRepo = convRepo)
        service.sendMessage(
            OutgoingMessage(id = "msg-3", conversationId = "conv-2", recipientPublicKeyHex = "eeff", text = "Hey there")
        )
        val conv = convRepo.getConversation("conv-2")
        assertNotNull(conv)
        assertEquals("Hey there", conv.lastMessagePreview)
    }

    @Test
    fun sendMessage_marksStatusSentWhenTransportSucceeds() = runTest {
        val msgRepo = FakeMessageRepository()
        val service = buildService(this, msgRepo = msgRepo)
        service.sendMessage(
            OutgoingMessage(id = "msg-4", conversationId = "conv-1", recipientPublicKeyHex = "ccdd", text = "x")
        )
        assertEquals(MessageStatus.SENT, msgRepo.statusUpdates["msg-4"])
    }

    @Test
    fun sendReaction_storesReactionLocallyAndSendsViaTransport() = runTest {
        val transport = FakeRelayTransport()
        val reactionRepo = FakeReactionRepository()
        val service = buildService(this, transport = transport, reactionRepo = reactionRepo)
        service.sendReaction(
            messageId = "msg-1",
            conversationId = "conv-1",
            recipientPublicKeyHex = "ccdd",
            emoji = "👍",
        )
        // Reaction stored locally under the sender's own identity key
        val reactions = reactionRepo.getReactions("msg-1")
        assertEquals(1, reactions.size)
        assertEquals("👍", reactions[0].emoji)
        // Control message sent to the relay
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun pinMessageForBoth_storesLocallyAndSendsViaTransport() = runTest {
        val msgRepo = FakeMessageRepository()
        val transport = FakeRelayTransport()
        val service = buildService(this, msgRepo = msgRepo, transport = transport)

        // Seed a message so pinMessage has a target to update
        msgRepo.insertMessage(
            MessageEntity(
                id = "msg-pin-1",
                conversationId = "conv-1",
                ciphertext = ByteArray(0),
                plaintextCache = "Hello",
                sent = false,
                status = MessageStatus.DELIVERED,
                createdAt = 0L,
            )
        )

        val result = service.pinMessageForBoth(
            messageId = "msg-pin-1",
            conversationId = "conv-1",
            recipientPublicKeyHex = "ccdd",
            pinned = true,
        )

        // Result must be successful
        assertEquals(true, result.isSuccess)
        // Local pin state updated
        val pinned = msgRepo.getPinnedMessages("conv-1")
        assertEquals(1, pinned.size)
        assertEquals("msg-pin-1", pinned[0].id)
        assertEquals(true, pinned[0].pinned)
        // Exactly one control message sent to relay
        assertEquals(1, transport.sent.size)
        assertEquals("ccdd", transport.sent[0].to)
    }

    @Test
    fun pinMessageForBoth_unpin_clearsLocalPinState() = runTest {
        val msgRepo = FakeMessageRepository()
        val service = buildService(this, msgRepo = msgRepo)

        msgRepo.insertMessage(
            MessageEntity(
                id = "msg-pin-2",
                conversationId = "conv-1",
                ciphertext = ByteArray(0),
                plaintextCache = "World",
                sent = false,
                status = MessageStatus.DELIVERED,
                createdAt = 0L,
                pinned = true,
            )
        )

        service.pinMessageForBoth(
            messageId = "msg-pin-2",
            conversationId = "conv-1",
            recipientPublicKeyHex = "ccdd",
            pinned = false,
        )

        val pinned = msgRepo.getPinnedMessages("conv-1")
        assertEquals(0, pinned.size)
    }
}
