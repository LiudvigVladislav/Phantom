// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.launch
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

    override suspend fun getMessageById(id: String): MessageEntity? =
        messages.firstOrNull { it.id == id }

    override suspend fun insertMessage(entity: MessageEntity) {
        if (messages.any { it.id == entity.id }) return
        messages += entity
    }
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
    override suspend fun pinMessage(messageId: String, pinned: Boolean, pinnedByPubkey: String?) {
        val i = messages.indexOfFirst { it.id == messageId }
        if (i != -1) messages[i] = messages[i].copy(pinned = pinned, pinnedByPubkey = pinnedByPubkey)
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
    override suspend fun setMutedUntil(conversationId: String, until: Long?) {
        store[conversationId]?.let { store[conversationId] = it.copy(mutedUntil = until) }
    }
    override suspend fun setPinned(conversationId: String, pinned: Boolean) {
        store[conversationId]?.let { store[conversationId] = it.copy(pinned = pinned) }
    }
    override suspend fun setNeedsRehandshake(conversationId: String, needs: Boolean) {
        store[conversationId]?.let {
            store[conversationId] = it.copy(needsRehandshake = needs)
        }
    }
    override suspend fun markAllNeedsRehandshake() {
        store.keys.toList().forEach { id ->
            store[id]?.let { store[id] = it.copy(needsRehandshake = true) }
        }
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
    override suspend fun deleteAll() { store.clear() }
}

/**
 * PR C commit 10: SessionManager.getOrCreateSession is gone. The send/
 * receive wire-flow tests still want to exercise a real DMS, so they
 * pre-seed this repo with a constant Alpha-2-shaped RatchetState for
 * each conversation they exercise.
 *
 * The seed value is the same shape PassthroughX3DH produces from its
 * stub initiatorHandshake4DH — all-zero-byte fields. PassthroughDoubleRatchet
 * doesn't care about state contents, so any well-formed JSON works.
 */
private class PreSeededRatchetStateRepository(
    seedFor: List<String>,
) : RatchetStateRepository {
    private val seedJson: String = run {
        val state = phantom.core.crypto.RatchetState(
            rootKey = ByteArray(32),
            sendingChainKey = ByteArray(32),
            receivingChainKey = ByteArray(32),
            sendingRatchetPublicKey = ByteArray(32),
            sendingRatchetPrivateKey = ByteArray(32),
            receivingRatchetPublicKey = ByteArray(32),
        )
        kotlinx.serialization.json.Json.encodeToString(
            phantom.core.crypto.RatchetState.serializer(),
            state,
        )
    }
    private val store = mutableMapOf<String, String>().also { m ->
        seedFor.forEach { m[it] = seedJson }
    }
    override suspend fun getRatchetState(conversationId: String) = store[conversationId]
    override suspend fun upsertRatchetState(conversationId: String, stateBlob: String) {
        store[conversationId] = stateBlob
    }
    override suspend fun deleteRatchetState(conversationId: String) { store.remove(conversationId) }
    override suspend fun deleteAll() { store.clear() }
}

/**
 * No-op stub — tests don't drive the SessionManager bootstrap path
 * directly (they pre-seed RatchetState instead), so signedPreKeyRepository
 * and oneTimePreKeyRepository are never consulted. Provide minimal
 * implementations so SessionManager construction succeeds.
 */
private class FakeLocalSignedPreKeyRepository :
    phantom.core.storage.LocalSignedPreKeyRepository {
    override suspend fun get(): phantom.core.storage.LocalSignedPreKeyEntity? = null
    override suspend fun upsert(entity: phantom.core.storage.LocalSignedPreKeyEntity) {}
    override suspend fun clear() {}
}

private class FakeLocalOneTimePreKeyRepository :
    phantom.core.storage.LocalOneTimePreKeyRepository {
    override suspend fun get(keyIdHex: String): phantom.core.storage.LocalOneTimePreKeyEntity? = null
    override suspend fun getAll(): List<phantom.core.storage.LocalOneTimePreKeyEntity> = emptyList()
    override suspend fun count(): Int = 0
    override suspend fun insert(entity: phantom.core.storage.LocalOneTimePreKeyEntity) {}
    override suspend fun insertAll(entities: List<phantom.core.storage.LocalOneTimePreKeyEntity>) {}
    override suspend fun deleteByKeyId(keyIdHex: String) {}
    override suspend fun clear() {}
}

/**
 * IdentityCrypto stub — SessionManager only invokes `verifyWithIdentity`
 * on the bootstrap path, which the wire-flow tests don't exercise.
 */
private class FakeIdentityCrypto : phantom.core.identity.IdentityCrypto {
    override fun generateKeyPair(): phantom.core.identity.IdentityKeyPair =
        phantom.core.identity.IdentityKeyPair(
            phantom.core.identity.PublicKey(ByteArray(32)),
            phantom.core.identity.PrivateKey(ByteArray(32)),
        )
    override fun generateSigningKeyPair(): phantom.core.identity.IdentitySigningKeyPair =
        phantom.core.identity.IdentitySigningKeyPair(
            phantom.core.identity.SigningPublicKey(ByteArray(32)),
            phantom.core.identity.SigningPrivateKey(ByteArray(64)),
        )
    override fun sign(message: ByteArray, privateKey: phantom.core.identity.PrivateKey): ByteArray =
        error("sign not used in tests")
    override fun verify(message: ByteArray, signature: ByteArray, publicKey: phantom.core.identity.PublicKey): Boolean =
        error("verify not used in tests")
    override fun signWithIdentity(message: ByteArray, privateKey: phantom.core.identity.SigningPrivateKey): ByteArray =
        ByteArray(64)
    override fun verifyWithIdentity(
        message: ByteArray,
        signature: ByteArray,
        publicKey: phantom.core.identity.SigningPublicKey,
    ): Boolean = true
    override fun publicKeyToHex(key: phantom.core.identity.PublicKey): String =
        key.bytes.joinToString("") { "%02x".format(it.toInt().and(0xFF)) }
    override fun hexToPublicKey(hex: String): phantom.core.identity.PublicKey =
        phantom.core.identity.PublicKey(ByteArray(0))
    override fun signingPublicKeyToHex(key: phantom.core.identity.SigningPublicKey): String =
        key.bytes.joinToString("") { "%02x".format(it.toInt().and(0xFF)) }
    override fun hexToSigningPublicKey(hex: String): phantom.core.identity.SigningPublicKey =
        phantom.core.identity.SigningPublicKey(ByteArray(0))
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
    override suspend fun sendDeliveryAck(messageId: String): Boolean = true
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
    // 4-DH stubs added for PR A so the interface compiles. SessionManager
    // does not yet call into them — that's PR C work.
    override fun initiatorHandshake4DH(
        initiatorIdentityKeyPair: DhKeyPair,
        recipientIdentityPublicKey: DhPublicKey,
        recipientSignedPreKey: DhPublicKey,
        recipientOPK: DhPublicKey?,
    ) = initiatorHandshake(initiatorIdentityKeyPair, recipientIdentityPublicKey, recipientSignedPreKey)
    override fun recipientHandshake4DH(
        recipientIdentityKeyPair: DhKeyPair,
        recipientSignedPreKeyPair: DhKeyPair,
        recipientOPKPair: DhKeyPair?,
        initiatorIdentityPublicKey: DhPublicKey,
        initiatorEphemeralPublicKey: DhPublicKey,
    ) = initiatorHandshake(recipientIdentityKeyPair, initiatorIdentityPublicKey, initiatorEphemeralPublicKey)
    override fun initiatorHandshake4DHWithEphemeral(
        initiatorIdentityKeyPair: DhKeyPair,
        recipientIdentityPublicKey: DhPublicKey,
        recipientSignedPreKey: DhPublicKey,
        recipientOPK: DhPublicKey?,
        ephemeralKeyPair: DhKeyPair,
    ) = initiatorHandshake(initiatorIdentityKeyPair, recipientIdentityPublicKey, recipientSignedPreKey)
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

    private suspend fun buildService(
        testScope: TestScope,
        msgRepo: FakeMessageRepository = FakeMessageRepository(),
        convRepo: FakeConversationRepository = FakeConversationRepository(),
        transport: FakeRelayTransport = FakeRelayTransport(),
        reactionRepo: FakeReactionRepository? = null,
    ): DefaultMessagingService {
        // sendMessage paths reach SealedSender.seal which uses libsodium.
        // On JVM the lib is loaded via JNA; calling Box.keypair() before
        // LibsodiumInitializer.initialize() throws lateinit-property-not-
        // initialized. Tests previously relied on Alpha0IntegrationTest
        // running first to initialise the lib for the JVM process, which
        // is order-dependent. Initialise unconditionally here — idempotent
        // after the first call.
        LibsodiumInitializer.initialize()
        // PR C commit 10: SessionManager dropped getOrCreateSession (F12
        // closure). DefaultMessagingService now expects a session to
        // already exist on encrypt/decrypt — the bundle-fetch + bootstrap
        // path lands in commit 11. To keep the existing send/receive
        // wire-flow tests meaningful, we pre-seed `ratchetRepo` with a
        // constant state for the conversation each test exercises.
        val ratchetRepo = PreSeededRatchetStateRepository(seedFor = listOf(
            // Tests pass conversationId values directly to OutgoingMessage
            // (sendMessage uses message.conversationId verbatim, not the
            // derived id) and use deriveConversationId on the receive path
            // (sorts identity.publicKeyHex + senderPubKeyHex). Pre-seed
            // every shape currently exercised so tryLoadSession returns
            // non-null on every test path.
            "conv-1",
            "conv-2",
            "aabb_ccdd",
            "ccdd_aabb",
            "aabb_eeff",
            "eeff_aabb",
        ))
        val sessionManager = SessionManager(
            x3dh = PassthroughX3DH(),
            ratchetStateRepository = ratchetRepo,
            signedPreKeyRepository = FakeLocalSignedPreKeyRepository(),
            oneTimePreKeyRepository = FakeLocalOneTimePreKeyRepository(),
            identityCrypto = FakeIdentityCrypto(),
            json = json,
        )
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
            // Wire-flow tests pre-seed ratchet state, so the bootstrap
            // branch in encryptUnderLock is never taken — these stubs
            // assert if called rather than returning anything plausible.
            preKeyApi = ThrowingPreKeyApi,
            signingKeyProvider = { ThrowingSigningKey },
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

    // ── PR C commit 11: bootstrap-on-first-message tests ────────────────────

    /**
     * Send to a brand-new contact: DMS fetches Bob's bundle from
     * PreKeyApi, runs initiatorBootstrap, and the resulting
     * WireFrame on the wire carries `x3dhInit` + `senderSigningPublicKeyHex`.
     */
    @Test
    fun sendMessage_freshConversation_bootstrapsAndCarriesX3dhInitOnWire() = runTest {
        LibsodiumInitializer.initialize()
        val transport = FakeRelayTransport()
        val msgRepo = FakeMessageRepository()
        val convRepo = FakeConversationRepository()

        // No ratchet pre-seed. encryptUnderLock falls into the bootstrap
        // branch, which fetches Bob's bundle and runs SessionManager
        // initiatorBootstrap → real signature verify against the bundle.
        val ratchetRepo = FakeRatchetStateRepository()

        // Build a real-signed bundle for Bob — SessionManager's
        // initiatorBootstrap calls SignedPreKeySigner.verify, so we need
        // a real Ed25519 signature.
        val bobX25519 = phantom.core.crypto.LibsodiumX3DH().generateDhKeyPair()
        val bobSpk = phantom.core.crypto.LibsodiumX3DH().generateDhKeyPair()
        val bobSigning = com.ionspin.kotlin.crypto.signature.Signature.keypair()
        val bobSpkSig = phantom.core.crypto.SignedPreKeySigner.sign(
            spkPublic = bobSpk.publicKey,
            createdAtMs = 1_000L,
            identityEd25519SecretKey = bobSigning.secretKey.toByteArray(),
        )
        val bobBundle = phantom.core.transport.PreKeyBundle(
            identity_pubkey_hex = bobX25519.publicKey.bytes.toHexStringLower(),
            signing_pubkey_hex = bobSigning.publicKey.toByteArray().toHexStringLower(),
            signed_pre_key = phantom.core.transport.WireSignedPreKey(
                key_id = 1L,
                public_key_hex = bobSpk.publicKey.bytes.toHexStringLower(),
                created_at_ms = 1_000L,
                signature_hex = bobSpkSig.toHexStringLower(),
            ),
            one_time_pre_key = null,
        )

        // Build a Real X3DH SessionManager — PassthroughX3DH would skip
        // the actual handshake math, but our assertions are about the
        // WireFrame shape, not the rootKey value, so either works. Pick
        // the real one to also exercise the F15 require() path.
        val real = phantom.core.crypto.LibsodiumX3DH()
        val sessionManager = SessionManager(
            x3dh = real,
            ratchetStateRepository = ratchetRepo,
            signedPreKeyRepository = FakeLocalSignedPreKeyRepository(),
            oneTimePreKeyRepository = FakeLocalOneTimePreKeyRepository(),
            identityCrypto = phantom.core.identity.LibsodiumIdentityCrypto(),
            json = json,
        )

        // Local Ed25519 signing keypair — the FIRST-message wire frame
        // carries our public half so Bob can cache it.
        val ourSigningKp = com.ionspin.kotlin.crypto.signature.Signature.keypair()
        val ourSigning = phantom.core.identity.IdentitySigningKeyPair(
            publicKey = phantom.core.identity.SigningPublicKey(ourSigningKp.publicKey.toByteArray()),
            privateKey = phantom.core.identity.SigningPrivateKey(ourSigningKp.secretKey.toByteArray()),
        )

        val service = DefaultMessagingService(
            identity = identity,
            localKeyPair = localKeyPair,
            ratchet = PassthroughDoubleRatchet(),
            sessionManager = sessionManager,
            transport = transport,
            messageRepository = msgRepo,
            conversationRepository = convRepo,
            scope = this,
            json = json,
            preKeyApi = StubPreKeyApi(bundle = bobBundle),
            signingKeyProvider = { ourSigning },
        )

        // Local identity uses the same X25519 keypair we generated above
        // for "Alice"; recipient_pubkey_hex is Bob's identity hex.
        service.sendMessage(
            OutgoingMessage(
                id = "msg-fresh-1",
                conversationId = "fresh-conv",
                recipientPublicKeyHex = bobX25519.publicKey.bytes.toHexStringLower(),
                text = "first hello",
            ),
        )

        assertEquals(1, transport.sent.size, "WireFrame must reach transport on first send")
        val payload = transport.sent[0].payload
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val padded = kotlin.io.encoding.Base64.decode(payload)
        val unpadded = phantom.core.crypto.MessagePadding.unpad(padded)
        val wireFrame = json.decodeFromString<WireFrame>(unpadded.decodeToString())

        assertNotNull(
            wireFrame.x3dhInit,
            "WireFrame on first send MUST carry x3dhInit so the recipient can bootstrap",
        )
        assertEquals(1L, wireFrame.x3dhInit!!.spkKeyId)
        assertNotNull(
            wireFrame.senderSigningPublicKeyHex,
            "WireFrame on first send MUST carry senderSigningPublicKeyHex so the recipient can " +
                "cache the binding peer_x25519_identity → peer_ed25519_signing_key",
        )
        assertEquals(
            ourSigningKp.publicKey.toByteArray().toHexStringLower(),
            wireFrame.senderSigningPublicKeyHex,
        )
    }

    /**
     * Receive a first message that carries `x3dhInit`: DMS routes through
     * SessionManager.recipientBootstrap, derives the matching ratchet
     * state, decrypts the encrypted body. The plaintext payload appears
     * on `incomingMessages`.
     */
    @Test
    fun handleDeliver_freshConversation_bootstrapsRecipientFromX3dhInit() = runTest {
        LibsodiumInitializer.initialize()
        // Bob is the local user receiving Alice's first message.
        // Alice generates everything for the bundle she'd have published;
        // Bob's local SPK + OPK repo are pre-populated to match the
        // ids Alice references in her x3dhInit header.
        val real = phantom.core.crypto.LibsodiumX3DH()

        val aliceX25519 = real.generateDhKeyPair()
        val bobX25519 = real.generateDhKeyPair()  // bob's local DH identity
        val bobSpkPair = real.generateDhKeyPair()
        val bobOpkPair = real.generateDhKeyPair()
        val bobOpkIdHex = "00112233445566778899aabbccddeeff"

        // Bob's local repos hold his own SPK + OPK private halves.
        val bobSpkRepo = object : phantom.core.storage.LocalSignedPreKeyRepository {
            private var stored: phantom.core.storage.LocalSignedPreKeyEntity? =
                phantom.core.storage.LocalSignedPreKeyEntity(
                    keyId = 99L,
                    publicKeyHex = bobSpkPair.publicKey.bytes.toHexStringLower(),
                    privateKeyHex = bobSpkPair.privateKey.bytes.toHexStringLower(),
                    createdAtMs = 0L,
                    signatureHex = "00".repeat(64),
                )
            override suspend fun get() = stored
            override suspend fun upsert(entity: phantom.core.storage.LocalSignedPreKeyEntity) {
                stored = entity
            }
            override suspend fun clear() { stored = null }
        }
        val bobOpkRepo = object : phantom.core.storage.LocalOneTimePreKeyRepository {
            private val store = mutableMapOf(
                bobOpkIdHex to phantom.core.storage.LocalOneTimePreKeyEntity(
                    keyIdHex = bobOpkIdHex,
                    publicKeyHex = bobOpkPair.publicKey.bytes.toHexStringLower(),
                    privateKeyHex = bobOpkPair.privateKey.bytes.toHexStringLower(),
                    uploadedAtMs = 0L,
                ),
            )
            override suspend fun get(keyIdHex: String) = store[keyIdHex]
            override suspend fun getAll() = store.values.toList()
            override suspend fun count() = store.size
            override suspend fun insert(entity: phantom.core.storage.LocalOneTimePreKeyEntity) {
                store[entity.keyIdHex] = entity
            }
            override suspend fun insertAll(entities: List<phantom.core.storage.LocalOneTimePreKeyEntity>) {
                entities.forEach { insert(it) }
            }
            override suspend fun deleteByKeyId(keyIdHex: String) { store.remove(keyIdHex) }
            override suspend fun clear() { store.clear() }
            fun has(keyIdHex: String): Boolean = store.containsKey(keyIdHex)
        }

        // Run Alice's initiatorBootstrap on a parallel SessionManager so
        // we can capture the resulting WireFrame and feed it as Bob's
        // incoming envelope.
        val aliceSpkSig = phantom.core.crypto.SignedPreKeySigner.sign(
            spkPublic = bobSpkPair.publicKey,
            createdAtMs = 1_000L,
            identityEd25519SecretKey = com.ionspin.kotlin.crypto.signature.Signature.keypair().secretKey.toByteArray(),
        )
        // Alice runs initiatorBootstrap to get her X3DH ephemeral.
        val aliceSessionMgr = SessionManager(
            x3dh = real,
            ratchetStateRepository = FakeRatchetStateRepository(),
            signedPreKeyRepository = FakeLocalSignedPreKeyRepository(),
            oneTimePreKeyRepository = FakeLocalOneTimePreKeyRepository(),
            identityCrypto = phantom.core.identity.LibsodiumIdentityCrypto(),
            json = json,
        )
        val bobSigning = com.ionspin.kotlin.crypto.signature.Signature.keypair()
        val aliceBundleForBob = PreKeyBundle(
            identityPubkeyHex = bobX25519.publicKey.bytes.toHexStringLower(),
            signingPubkeyHex = bobSigning.publicKey.toByteArray().toHexStringLower(),
            signedPreKeyId = 99L,
            signedPreKeyPublicHex = bobSpkPair.publicKey.bytes.toHexStringLower(),
            signedPreKeyCreatedAtMs = 1_000L,
            signedPreKeySignatureHex = phantom.core.crypto.SignedPreKeySigner.sign(
                bobSpkPair.publicKey,
                1_000L,
                bobSigning.secretKey.toByteArray(),
            ).toHexStringLower(),
            oneTimePreKeyIdHex = bobOpkIdHex,
            oneTimePreKeyPublicHex = bobOpkPair.publicKey.bytes.toHexStringLower(),
        )
        val aliceBootstrap = aliceSessionMgr.initiatorBootstrap(
            conversationId = "alice-side",
            localIdentityKeyPair = aliceX25519,
            bundle = aliceBundleForBob,
        )

        // Now build Bob's DMS. Bob's identity = bobX25519 (NOT the
        // testfixture default — different DH keys for Alice & Bob).
        val bobIdentity = phantom.core.identity.IdentityRecord(
            id = "bob-id",
            username = "bob",
            publicKeyHex = bobX25519.publicKey.bytes.toHexStringLower(),
            dhPrivateKeyHex = bobX25519.privateKey.bytes.toHexStringLower(),
            createdAt = 0L,
        )
        val ratchetRepo = FakeRatchetStateRepository()
        val bobSessionMgr = SessionManager(
            x3dh = real,
            ratchetStateRepository = ratchetRepo,
            signedPreKeyRepository = bobSpkRepo,
            oneTimePreKeyRepository = bobOpkRepo,
            identityCrypto = phantom.core.identity.LibsodiumIdentityCrypto(),
            json = json,
        )
        val bobIncomingTransport = ManualIncomingTransport()
        val msgRepo = FakeMessageRepository()
        val convRepo = FakeConversationRepository()
        val service = DefaultMessagingService(
            identity = bobIdentity,
            localKeyPair = bobX25519,
            ratchet = phantom.core.crypto.LibsodiumDoubleRatchet(),
            sessionManager = bobSessionMgr,
            transport = bobIncomingTransport,
            messageRepository = msgRepo,
            conversationRepository = convRepo,
            // backgroundScope cancels the long-running receive collector
            // when the test body returns; using `this` (TestScope) makes
            // runTest wait forever for the collector to finish.
            scope = backgroundScope,
            json = json,
            preKeyApi = ThrowingPreKeyApi,
            signingKeyProvider = { ThrowingSigningKey },
        )
        service.startReceiving()

        // Alice now encrypts her first message under her ratchet state
        // and ships it to Bob. WireFrame carries the x3dhInit header.
        val realRatchet = phantom.core.crypto.LibsodiumDoubleRatchet()
        val plaintextPayload = json.encodeToString(
            phantom.core.messaging.MessagePayload.serializer(),
            phantom.core.messaging.MessagePayload(
                text = "hello, bob",
                sentAt = 1_700_000_000_000L,
                senderUsername = "alice",
            ),
        ).encodeToByteArray()
        val (_, encrypted) = realRatchet.encrypt(aliceBootstrap.ratchetState, plaintextPayload)
        val wireFrame = WireFrame(
            encryptedMessage = encrypted,
            x3dhInit = aliceBootstrap.x3dhInit,
            senderSigningPublicKeyHex = "ee".repeat(32),
        )
        val wireFrameBytes = json.encodeToString(WireFrame.serializer(), wireFrame).encodeToByteArray()
        val padded = phantom.core.crypto.MessagePadding.pad(wireFrameBytes)

        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val sealedSenderHex = phantom.core.crypto.SealedSender.seal(
            fromPubKeyHex = aliceX25519.publicKey.bytes.toHexStringLower(),
            toPublicKeyBytes = bobX25519.publicKey.bytes,
        )
        // Subscribe BEFORE delivery so the SharedFlow (replay = 0) emit
        // doesn't drop the value. Pump the scheduler once so the
        // subscription is live before we publish the event.
        val collected = mutableListOf<IncomingMessage>()
        val collectJob = launch { service.incomingMessages.collect { collected.add(it) } }
        testScheduler.runCurrent()

        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        bobIncomingTransport.deliver(
            phantom.core.transport.RelayMessage.Deliver(
                from = "",
                sealedSender = kotlin.io.encoding.Base64.encode(sealedSenderHex),
                payload = kotlin.io.encoding.Base64.encode(padded),
                messageId = "msg-bootstrap-1",
            ),
        )
        testScheduler.runCurrent()
        collectJob.cancel()

        // Bob's session got bootstrapped + decrypt should have produced
        // an incoming message. The OPK Alice referenced is gone from
        // Bob's pool (single-use lifecycle).
        assertEquals(1, collected.size, "exactly one decrypted incoming message")
        assertEquals("hello, bob", collected.first().text)
        assertEquals(false, bobOpkRepo.has(bobOpkIdHex), "OPK consumed on bootstrap")
    }
}

private fun ByteArray.toHexStringLower(): String =
    joinToString("") { "%02x".format(it.toInt().and(0xFF)) }

/**
 * PreKeyApi stub that returns a single canned bundle for any request.
 * Used by the bootstrap-on-first-send test.
 */
private class StubPreKeyApi(
    private val bundle: phantom.core.transport.PreKeyBundle,
) : phantom.core.transport.PreKeyApi {
    override suspend fun publishBundle(
        request: phantom.core.transport.PublishRequest,
    ): phantom.core.transport.PublishResult =
        phantom.core.transport.PublishResult.Stored(0)

    override suspend fun fetchBundle(
        identityPubkeyHex: String,
        requesterPubkeyHex: String?,
    ): phantom.core.transport.PreKeyBundle? = bundle

    override suspend fun fetchStatus(
        identityPubkeyHex: String,
        requesterPubkeyHex: String?,
    ): phantom.core.transport.PreKeyStatus =
        phantom.core.transport.PreKeyStatus(remaining_opks = 0, signed_prekey_age_days = null)
}

/**
 * Test transport that exposes a `deliver(...)` hook so tests can drive
 * the receive path with hand-crafted [phantom.core.transport.RelayMessage.Deliver]
 * frames.
 */
private class ManualIncomingTransport : phantom.core.transport.RelayTransport {
    private val _state = MutableStateFlow<phantom.core.transport.TransportState>(
        phantom.core.transport.TransportState.Connected,
    )
    override val state: StateFlow<phantom.core.transport.TransportState> = _state
    private val _incoming = MutableSharedFlow<phantom.core.transport.RelayMessage.Deliver>(
        replay = 0, extraBufferCapacity = 64,
    )
    override val incoming: Flow<phantom.core.transport.RelayMessage.Deliver> = _incoming
    override val acks: Flow<phantom.core.transport.RelayMessage.Ack> = emptyFlow()
    override val readReceipts: Flow<phantom.core.transport.RelayMessage.ReadReceipt> = emptyFlow()
    override val typingEvents: SharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 10)
    override suspend fun connect(relayUrl: String, identityPublicKeyHex: String, token: String?) {}
    override suspend fun disconnect() {}
    override suspend fun send(message: phantom.core.transport.RelayMessage.Send): Boolean = true
    override suspend fun sendReadReceipt(message: phantom.core.transport.RelayMessage.ReadReceipt): Boolean = true
    override suspend fun sendDeliveryAck(messageId: String): Boolean = true
    override suspend fun sendTyping(toPubKeyHex: String): Boolean = true
    override fun isConnected(): Boolean = true
    suspend fun deliver(d: phantom.core.transport.RelayMessage.Deliver) { _incoming.emit(d) }
}

// ── Stubs for the bootstrap-path dependencies that wire-flow tests
//    intentionally do not exercise. If a test reaches these, it means
//    the pre-seeded session was missing for that conversation id and
//    DMS fell into the bootstrap branch — the test setup needs to add
//    the missing seed entry, not stub these out. ─────────────────────

private object ThrowingPreKeyApi : phantom.core.transport.PreKeyApi {
    override suspend fun publishBundle(
        request: phantom.core.transport.PublishRequest,
    ): phantom.core.transport.PublishResult =
        error("ThrowingPreKeyApi: wire-flow tests must pre-seed ratchet state, not call publishBundle")

    override suspend fun fetchBundle(
        identityPubkeyHex: String,
        requesterPubkeyHex: String?,
    ): phantom.core.transport.PreKeyBundle? =
        error("ThrowingPreKeyApi: wire-flow tests must pre-seed ratchet state, not call fetchBundle")

    override suspend fun fetchStatus(
        identityPubkeyHex: String,
        requesterPubkeyHex: String?,
    ): phantom.core.transport.PreKeyStatus =
        error("ThrowingPreKeyApi: wire-flow tests must pre-seed ratchet state, not call fetchStatus")
}

private val ThrowingSigningKey: phantom.core.identity.IdentitySigningKeyPair =
    phantom.core.identity.IdentitySigningKeyPair(
        publicKey = phantom.core.identity.SigningPublicKey(ByteArray(32)),
        privateKey = phantom.core.identity.SigningPrivateKey(ByteArray(64)),
    )
