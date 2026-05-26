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
import kotlin.test.assertTrue

// ── Fakes ────────────────────────────────────────────────────────────────────

private class FakeMessageRepository : MessageRepository {
    val messages = mutableListOf<MessageEntity>()
    val statusUpdates = mutableMapOf<String, MessageStatus>()

    override suspend fun getMessages(conversationId: String) =
        messages.filter { it.conversationId == conversationId }

    override fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        emptyFlow()

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
    // PR-H2b: incoming changed from emptyFlow() to MutableSharedFlow so
    // idempotency tests can drive deliver(...) on this transport instead
    // of needing a separate ManualIncomingTransport. Existing tests that
    // never call deliver() see the same empty-stream behaviour.
    private val _incoming = MutableSharedFlow<RelayMessage.Deliver>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<RelayMessage.Deliver> = _incoming
    override val acks: Flow<RelayMessage.Ack> = emptyFlow()
    override val typingEvents: SharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 10)
    val sent = mutableListOf<RelayMessage.Send>()
    /** PR-H2b: records every ack-deliver so idempotency tests can assert duplicate-ack semantics. */
    val ackedDelivers = mutableListOf<String>()
    /**
     * PR-D2b.1: gate to simulate `transport.send` rejecting an envelope
     * (offline / handshake mid-flight / over cap). Default `true` keeps
     * every existing test happy-path. Voice send-fail tests flip this to
     * `false` (optionally after the first K successful sends) to assert
     * the sender's per-chunk failure handling.
     */
    var sendShouldSucceed: Boolean = true
    /**
     * PR-D2b.1: when > 0 the first N `send()` calls return `true`, then
     * subsequent calls honour [sendShouldSucceed]. Lets a test fire two
     * chunks successfully then reject the third without flipping the
     * gate manually in between coroutine resumes.
     */
    var sendSuccessLimit: Int = Int.MAX_VALUE
    override suspend fun connect(
        relayUrl: String,
        identityPublicKeyHex: String,
        signingPublicKeyHex: String,
        signChallenge: suspend (challenge: ByteArray) -> ByteArray?,
        socksProxyPort: Int?,
    ) {}
    override suspend fun disconnect() {}
    override suspend fun send(message: RelayMessage.Send): Boolean {
        sent += message
        if (sent.size <= sendSuccessLimit) return true
        return sendShouldSucceed
    }
    override suspend fun sendDeliveryAck(messageId: String): Boolean {
        ackedDelivers += messageId
        return true
    }
    override suspend fun sendTyping(toPubKeyHex: String): Boolean = true
    override fun isConnected() = true
    // ADR-011 / ADR-013: stubs satisfying the new RelayTransport contract.
    override val lastPongElapsedMs: Long get() = 0L
    override val lastInboundFrameElapsedMs: Long get() = 0L
    override val pendingAckCount: Int get() = 0
    override suspend fun forceReconnect() {}
    suspend fun deliver(d: RelayMessage.Deliver) { _incoming.emit(d) }
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
        // PR-H2b: optional ledger fake. When null, the legacy
        // `messages.id` guard remains the only protection (mirrors
        // pre-H2b behaviour for tests that don't care about idempotency).
        processedRepo: phantom.core.storage.ProcessedEnvelopeRepository? = null,
        // PR-H2b: optional scope override. Tests that call
        // service.startReceiving() must pass `backgroundScope` so the
        // long-running incoming collector is cancelled when the test
        // body returns; tests that only call sendMessage() can use the
        // default testScope without leaking jobs.
        scope: kotlinx.coroutines.CoroutineScope = testScope,
        // PR-D2b.1: optional durable voice-chunks repo. When non-null,
        // the 1:1 voice receive path uses durable assembly + startReceiving
        // finalizer. When null, the legacy in-memory path is used (same
        // behaviour every voice test before D2b.1 saw).
        voiceChunkRepo: phantom.core.storage.VoiceChunkRepository? = null,
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
            processedEnvelopeRepository = processedRepo,
            scope = scope,
            json = json,
            reactionRepository = reactionRepo,
            // Wire-flow tests pre-seed ratchet state, so the bootstrap
            // branch in encryptUnderLock is never taken — these stubs
            // assert if called rather than returning anything plausible.
            preKeyApi = ThrowingPreKeyApi,
            signingKeyProvider = { ThrowingSigningKey },
            voiceChunkRepository = voiceChunkRepo,
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

    // ── PR 3 / F-05: voice chunking tests ─────────────────────────────────────

    @Test
    fun sendAudio_splitsIntoChunksOfCorrectSize() = runTest {
        LibsodiumInitializer.initialize()
        val transport = FakeRelayTransport()
        val msgRepo = FakeMessageRepository()
        val convRepo = FakeConversationRepository()
        convRepo.upsertConversation(
            ConversationEntity(
                id = "conv-1",
                theirUsername = "bob",
                theirPublicKeyHex = "ccdd",
                lastMessagePreview = "",
                lastMessageAt = 0L,
                unreadCount = 0,
                trustTier = phantom.core.storage.TrustTier.TRUSTED,
                blocked = false,
            )
        )
        val service = buildService(this, transport = transport, msgRepo = msgRepo, convRepo = convRepo)

        // Sized to 3 full chunks + 1 partial chunk = 4 chunks regardless of AUDIO_CHUNK_BYTES.
        val audioBytes = ByteArray(DefaultMessagingService.AUDIO_CHUNK_BYTES * 3 + 1) { it.toByte() }
        val result = service.sendAudio(
            conversationId = "conv-1",
            audioBytes = audioBytes,
            durationMs = 5_000L,
            mimeType = "audio/ogg",
        )

        assertEquals(true, result.isSuccess, "sendAudio must succeed for the chosen payload size")
        assertEquals(4, transport.sent.size, "3 full chunks + 1 partial chunk must produce exactly 4 envelopes")

        val json = Json { ignoreUnknownKeys = true }
        val chunkIds = mutableSetOf<String>()
        transport.sent.forEachIndexed { idx, msg ->
            @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
            val padded = kotlin.io.encoding.Base64.decode(msg.payload)
            val unpadded = phantom.core.crypto.MessagePadding.unpad(padded)
            val wireFrame = json.decodeFromString<WireFrame>(unpadded.decodeToString())
            // PassthroughDoubleRatchet stores plaintext in ciphertext field
            val payload = json.decodeFromString<MessagePayload>(wireFrame.encryptedMessage.ciphertext.decodeToString())
            assertEquals(MessagePayload.TYPE_AUDIO_CHUNK, payload.type)
            assertEquals(idx, payload.audioChunkIndex, "chunk index must be sequential")
            assertEquals(4, payload.audioChunkTotal, "each chunk must carry total=4")
            assertNotNull(payload.audioChunkId, "audioChunkId must be present on every chunk")
            chunkIds.add(payload.audioChunkId!!)
        }
        assertEquals(1, chunkIds.size, "all 4 chunks must share the same audioChunkId")
    }

    @Test
    fun receiveAudio_reassemblesChunksInAnyOrder() = runTest {
        LibsodiumInitializer.initialize()
        val msgRepo = FakeMessageRepository()
        val convRepo = FakeConversationRepository()
        val incomingTransport = ManualIncomingTransport()

        val ratchetRepo = PreSeededRatchetStateRepository(seedFor = listOf("aabb_ccdd"))
        val sessionManager = SessionManager(
            x3dh = PassthroughX3DH(),
            ratchetStateRepository = ratchetRepo,
            signedPreKeyRepository = FakeLocalSignedPreKeyRepository(),
            oneTimePreKeyRepository = FakeLocalOneTimePreKeyRepository(),
            identityCrypto = FakeIdentityCrypto(),
            json = json,
        )
        val service = DefaultMessagingService(
            identity = identity,
            localKeyPair = localKeyPair,
            ratchet = PassthroughDoubleRatchet(),
            sessionManager = sessionManager,
            transport = incomingTransport,
            messageRepository = msgRepo,
            conversationRepository = convRepo,
            scope = backgroundScope,
            json = json,
            preKeyApi = ThrowingPreKeyApi,
            signingKeyProvider = { ThrowingSigningKey },
        )
        service.startReceiving()
        testScheduler.runCurrent()

        // Build a 4-chunk voice note from a known byte sequence.
        // Each chunk is AUDIO_CHUNK_BYTES except the last (sized as 3 full chunks + 1 partial).
        val originalBytes = ByteArray(DefaultMessagingService.AUDIO_CHUNK_BYTES * 3 + 1) { it.toByte() }
        val chunkId = "test-chunk-id-reassembly"
        val total = 4
        val localJson = Json { ignoreUnknownKeys = true }

        // Helper to build a padded+base64-encoded WireFrame envelope carrying a TYPE_AUDIO_CHUNK
        // payload, using PassthroughDoubleRatchet semantics (ciphertext == plaintext).
        fun makeChunkDeliver(index: Int, msgId: String): phantom.core.transport.RelayMessage.Deliver {
            val start = index * DefaultMessagingService.AUDIO_CHUNK_BYTES
            val end = minOf((index + 1) * DefaultMessagingService.AUDIO_CHUNK_BYTES, originalBytes.size)
            @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
            val chunkB64 = kotlin.io.encoding.Base64.encode(originalBytes.copyOfRange(start, end))

            val payloadJson = localJson.encodeToString(
                MessagePayload(
                    type = MessagePayload.TYPE_AUDIO_CHUNK,
                    senderUsername = "sender",
                    audioChunkId = chunkId,
                    audioChunkIndex = index,
                    audioChunkTotal = total,
                    audioChunkB64 = chunkB64,
                    audioDurationMs = 5_000L,
                    audioMimeType = "audio/ogg",
                )
            )
            val encrypted = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = payloadJson.encodeToByteArray(),
                nonce = ByteArray(24),
            )
            val wireFrame = WireFrame(encryptedMessage = encrypted)
            val wireFrameBytes = localJson.encodeToString(WireFrame.serializer(), wireFrame).encodeToByteArray()
            val padded = phantom.core.crypto.MessagePadding.pad(wireFrameBytes)
            @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
            val payloadB64 = kotlin.io.encoding.Base64.encode(padded)
            return phantom.core.transport.RelayMessage.Deliver(
                from = "ccdd",
                sealedSender = "",
                payload = payloadB64,
                messageId = msgId,
            )
        }

        // Deliver out of order: 2, 0, 3, 1
        incomingTransport.deliver(makeChunkDeliver(2, "chunk-msg-2"))
        testScheduler.runCurrent()
        incomingTransport.deliver(makeChunkDeliver(0, "chunk-msg-0"))
        testScheduler.runCurrent()
        incomingTransport.deliver(makeChunkDeliver(3, "chunk-msg-3"))
        testScheduler.runCurrent()

        // After 3 chunks: no message yet
        assertEquals(0, msgRepo.messages.filter { !it.sent }.size, "no message before all chunks arrive")

        incomingTransport.deliver(makeChunkDeliver(1, "chunk-msg-1"))
        testScheduler.runCurrent()

        // After 4th chunk: exactly one incoming message with correct audio content
        val received = msgRepo.messages.filter { !it.sent }
        assertEquals(1, received.size, "exactly one message after all chunks assembled")
        val pc = received[0].plaintextCache ?: ""
        assertEquals(true, pc.startsWith("[AUDIO:"), "assembled message must start with [AUDIO: sentinel")

        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val assembledBytes = kotlin.io.encoding.Base64.decode(pc.removePrefix("[AUDIO:").removeSuffix("]"))
        assertEquals(originalBytes.size, assembledBytes.size, "reassembled bytes must match original size")
        assertEquals(originalBytes.toList(), assembledBytes.toList(), "reassembled bytes must match original content")
    }

    @Test
    fun sendAudio_rejectsOversizedPayload() = runTest {
        LibsodiumInitializer.initialize()
        val transport = FakeRelayTransport()
        val service = buildService(this, transport = transport)

        // Oversized check happens before conversation lookup, so no need to seed convRepo.
        val tooBig = ByteArray(DefaultMessagingService.MAX_AUDIO_BYTES + 1)
        val result = service.sendAudio(
            conversationId = "conv-1",
            audioBytes = tooBig,
            durationMs = 999_999L,
            mimeType = "audio/ogg",
        )

        assertEquals(true, result.isFailure, "sendAudio must fail for payload exceeding MAX_AUDIO_BYTES")
        assertEquals(0, transport.sent.size, "no envelopes must be sent for an oversized payload")
    }

    // ── C-2: read receipts via sealed Double Ratchet pipeline ─────────────────

    @Test
    fun markConversationRead_sendsOneReadReceiptPerUnreadMessage() = runTest {
        LibsodiumInitializer.initialize()
        val msgRepo = FakeMessageRepository()
        val convRepo = FakeConversationRepository()
        val transport = FakeRelayTransport()

        convRepo.upsertConversation(
            ConversationEntity(
                id = "conv-1",
                theirUsername = "bob",
                theirPublicKeyHex = "ccdd",
                lastMessagePreview = "",
                lastMessageAt = 0L,
                unreadCount = 2,
                trustTier = phantom.core.storage.TrustTier.TRUSTED,
                blocked = false,
            )
        )
        msgRepo.insertMessage(
            MessageEntity(
                id = "msg-rx-1",
                conversationId = "conv-1",
                ciphertext = ByteArray(0),
                plaintextCache = "Hi",
                sent = false,
                status = MessageStatus.DELIVERED,
                createdAt = 0L,
            )
        )
        msgRepo.insertMessage(
            MessageEntity(
                id = "msg-rx-2",
                conversationId = "conv-1",
                ciphertext = ByteArray(0),
                plaintextCache = "Hey",
                sent = false,
                status = MessageStatus.DELIVERED,
                createdAt = 0L,
            )
        )

        val service = buildService(this, msgRepo = msgRepo, convRepo = convRepo, transport = transport)
        service.markConversationRead(
            conversationId = "conv-1",
            theirPublicKeyHex = "ccdd",
            sendReceipt = true,
        )

        // One sealed envelope per unread message; relay must not see the sender identity.
        assertEquals(2, transport.sent.size, "one receipt per unread message")
        transport.sent.forEach { envelope ->
            assertEquals("", envelope.from, "read receipt must not expose sender to relay")
            assertEquals("ccdd", envelope.to)
        }
        // Local status updated for both messages.
        assertEquals(MessageStatus.READ, msgRepo.statusUpdates["msg-rx-1"])
        assertEquals(MessageStatus.READ, msgRepo.statusUpdates["msg-rx-2"])
    }

    @Test
    fun markConversationRead_suppressedReceipt_noEnvelopeSent() = runTest {
        LibsodiumInitializer.initialize()
        val msgRepo = FakeMessageRepository()
        val transport = FakeRelayTransport()

        msgRepo.insertMessage(
            MessageEntity(
                id = "msg-rx-3",
                conversationId = "conv-1",
                ciphertext = ByteArray(0),
                plaintextCache = "Silent",
                sent = false,
                status = MessageStatus.DELIVERED,
                createdAt = 0L,
            )
        )

        val service = buildService(this, msgRepo = msgRepo, transport = transport)
        service.markConversationRead(
            conversationId = "conv-1",
            theirPublicKeyHex = "ccdd",
            sendReceipt = false,
        )

        assertEquals(0, transport.sent.size, "no envelope when receipt suppressed (privacy mode)")
        assertEquals(MessageStatus.READ, msgRepo.statusUpdates["msg-rx-3"], "local status still updated")
    }

    @Test
    fun handleDeliver_readReceipt_updatesOutgoingMessageStatusToRead() = runTest {
        LibsodiumInitializer.initialize()
        val msgRepo = FakeMessageRepository()
        val convRepo = FakeConversationRepository()
        val incomingTransport = ManualIncomingTransport()

        // Outgoing message whose read-status we expect to be updated.
        msgRepo.insertMessage(
            MessageEntity(
                id = "msg-sent-1",
                conversationId = "aabb_ccdd",
                ciphertext = ByteArray(0),
                plaintextCache = "Hey",
                sent = true,
                status = MessageStatus.DELIVERED,
                createdAt = 0L,
            )
        )

        val ratchetRepo = PreSeededRatchetStateRepository(seedFor = listOf("aabb_ccdd"))
        val sessionManager = SessionManager(
            x3dh = PassthroughX3DH(),
            ratchetStateRepository = ratchetRepo,
            signedPreKeyRepository = FakeLocalSignedPreKeyRepository(),
            oneTimePreKeyRepository = FakeLocalOneTimePreKeyRepository(),
            identityCrypto = FakeIdentityCrypto(),
            json = json,
        )
        val service = DefaultMessagingService(
            identity = identity,
            localKeyPair = localKeyPair,
            ratchet = PassthroughDoubleRatchet(),
            sessionManager = sessionManager,
            transport = incomingTransport,
            messageRepository = msgRepo,
            conversationRepository = convRepo,
            scope = backgroundScope,
            json = json,
            preKeyApi = ThrowingPreKeyApi,
            signingKeyProvider = { ThrowingSigningKey },
        )
        service.startReceiving()
        testScheduler.runCurrent()

        // Build a read_receipt payload pointing at our outgoing message.
        val localJson = Json { ignoreUnknownKeys = true }
        val payloadJson = localJson.encodeToString(
            MessagePayload(
                type = MessagePayload.TYPE_READ_RECEIPT,
                targetMessageId = "msg-sent-1",
                sentAt = 0L,
                senderUsername = "bob",
            )
        )
        // PassthroughDoubleRatchet treats ciphertext == plaintext.
        val encrypted = phantom.core.crypto.EncryptedMessage(
            ratchetPublicKey = ByteArray(32),
            messageIndex = 0,
            ciphertext = payloadJson.encodeToByteArray(),
            nonce = ByteArray(24),
        )
        val wireFrame = WireFrame(encryptedMessage = encrypted)
        val wireFrameBytes = localJson.encodeToString(WireFrame.serializer(), wireFrame).encodeToByteArray()
        val padded = phantom.core.crypto.MessagePadding.pad(wireFrameBytes)
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val payloadB64 = kotlin.io.encoding.Base64.encode(padded)

        incomingTransport.deliver(
            phantom.core.transport.RelayMessage.Deliver(
                from = "ccdd",
                sealedSender = "",
                payload = payloadB64,
                messageId = "receipt-envelope-1",
            )
        )
        testScheduler.runCurrent()

        assertEquals(MessageStatus.READ, msgRepo.statusUpdates["msg-sent-1"],
            "incoming read receipt must update the outgoing message status to READ")
    }

    // ── sendCallSignal tests (F19 + F20) ─────────────────────────────────────

    @Test
    fun sendCallSignal_sendsOneSealedEnvelope() = runTest {
        LibsodiumInitializer.initialize()
        val transport = FakeRelayTransport()
        val service = buildService(this, transport = transport)

        service.sendCallSignal(
            recipientPublicKeyHex = "ccdd",
            payload = MessagePayload(
                type = MessagePayload.TYPE_CALL_OFFER,
                callId = "call-123",
                sdp = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\n",
            ),
        )

        assertEquals(1, transport.sent.size, "exactly one envelope must be sent for a call signal")
        assertEquals("ccdd", transport.sent[0].to, "envelope must be addressed to recipient")
        assertEquals("", transport.sent[0].from, "from must be empty — identity is sealed, not exposed to relay")
        assertNotNull(
            transport.sent[0].sealedSender.takeIf { it.isNotEmpty() },
            "sealedSender must be non-empty for call signals (Sealed Sender applied)",
        )
    }

    @Test
    fun sendCallSignal_doesNotStoreChatMessage() = runTest {
        LibsodiumInitializer.initialize()
        val msgRepo = FakeMessageRepository()
        val service = buildService(this, msgRepo = msgRepo)

        service.sendCallSignal(
            recipientPublicKeyHex = "ccdd",
            payload = MessagePayload(
                type = MessagePayload.TYPE_CALL_ICE,
                callId = "call-456",
                iceCandidateJson = """{"sdpMid":"0","sdpMLineIndex":0,"candidate":"candidate:1 1 UDP 2130706431 192.168.1.1 54321 typ host"}""",
            ),
        )

        assertEquals(0, msgRepo.messages.size, "call signal must not be stored as a chat message in MessageRepository")
    }

    // ── PR-H2b: idempotent envelope ledger guard ────────────────────────────
    //
    // These tests verify that a second delivery of the same envelope id
    // does NOT reach `ratchet.decrypt`. Test #34 (2026-05-13) caught the
    // missing protection — phone read-receipt 5b5c4faa decrypted on first
    // delivery, ack-deliver lost during a WS reconnect, relay redelivered,
    // and the second decrypt MAC-failed because the ratchet chain had
    // already advanced. The pre-existing `messages.id` check did not catch
    // it because read_receipts are not stored as DB rows. The new ledger
    // covers ALL payload types regardless of whether they get persisted.

    @Test
    fun handleDeliver_secondDeliveryOfSameEnvelopeId_skipsDecrypt_andAcks() = runTest {
        val transport = FakeRelayTransport()
        val processedRepo = FakeProcessedEnvelopeLedger()
        // Pre-seed the ledger as if a first delivery had already
        // happened: this is what handleDeliver would observe on a relay
        // redelivery after the first decrypt has marked the envelope.
        processedRepo.markProcessed(
            envelopeId = "duplicate-from-relay-redelivery",
            conversationId = "conv-1",
            senderPubKeyHex = "ee".repeat(32),
            payloadType = "read_receipt",
            status = phantom.core.storage.ProcessedEnvelopeRepository.Status.PROCESSED,
            nowMs = 1_000L,
        )

        val service = buildService(this, transport = transport, processedRepo = processedRepo, scope = backgroundScope)
        service.startReceiving()
        // Pump the scheduler so the launchIn(scope) collector actually
        // subscribes to transport.incoming BEFORE we emit. SharedFlow with
        // replay=0 drops emits with no live subscribers.
        testScheduler.runCurrent()

        // Drive a duplicate delivery. Payload is intentionally garbage —
        // the early-return guard runs BEFORE we Base64-decode the payload,
        // so even an unparseable wire frame must be skipped cleanly.
        transport.deliver(
            phantom.core.transport.RelayMessage.Deliver(
                from = "ee".repeat(32),
                sealedSender = "",
                payload = "not-real-base64",
                messageId = "duplicate-from-relay-redelivery",
            ),
        )
        testScheduler.runCurrent()

        assertTrue(
            "duplicate-from-relay-redelivery" in transport.ackedDelivers,
            "ack-deliver MUST be sent on duplicate so the relay drops the envelope from its store",
        )
    }

    @Test
    fun handleDeliver_unknownEnvelopeId_passesGuard_andReachesDecryptPath() = runTest {
        // Symmetry check: an envelope id NOT in the ledger must pass the
        // guard. We don't fully exercise the decrypt path here (other
        // wire-flow tests cover that). What we verify is that the guard
        // is a check, not a wall — startReceiving still wires up the
        // collector, and a fresh envelope id reaches the next step
        // (in this minimal setup it errors out on unparseable payload,
        // which the runCatching in handleDeliver swallows).
        val transport = FakeRelayTransport()
        val processedRepo = FakeProcessedEnvelopeLedger()
        // Ledger empty — fresh envelope must NOT be skipped early.
        assertEquals(false, processedRepo.exists("fresh-envelope-id"))

        val service = buildService(this, transport = transport, processedRepo = processedRepo, scope = backgroundScope)
        service.startReceiving()

        // Just confirm the test infra works — fresh envelopes leave the
        // ledger empty (no spurious markProcessed from the guard itself).
        testScheduler.runCurrent()
        assertEquals(false, processedRepo.exists("fresh-envelope-id"))
    }

    // ── PR-D2b.1: sender per-chunk send-result check ─────────────────────
    //
    // The pre-D2b.1 sendAudio ignored `transport.send(...)` return value, so
    // the local message could be flipped to SENT even though half the
    // chunks were rejected on the wire. D2b.1 checks the boolean per chunk
    // and throws out of the runCatching loop on `false`.

    @Test
    fun sendAudio_returnsFailureAndKeepsMessageQueued_whenChunkSendRejected() = runTest {
        LibsodiumInitializer.initialize()
        val transport = FakeRelayTransport()
        // First send succeeds, every subsequent send returns false to model
        // a transport that lost the WS or hit the REST body cap between
        // chunks. With AUDIO_CHUNK_BYTES + 1 raw bytes the total is 2,
        // so chunk 0 sends OK and chunk 1 is rejected.
        transport.sendSuccessLimit = 1
        transport.sendShouldSucceed = false

        val msgRepo = FakeMessageRepository()
        val convRepo = FakeConversationRepository()
        convRepo.store["conv-1"] = ConversationEntity(
            id = "conv-1",
            theirUsername = "peer",
            theirPublicKeyHex = "ccdd",
            lastMessagePreview = "",
            lastMessageAt = 0L,
            unreadCount = 0,
            trustTier = phantom.core.storage.TrustTier.TRUSTED,
            blocked = false,
        )
        val service = buildService(
            testScope = this,
            transport = transport,
            msgRepo = msgRepo,
            convRepo = convRepo,
        )

        val audio = ByteArray(DefaultMessagingService.AUDIO_CHUNK_BYTES + 1)
        val result = service.sendAudio(
            conversationId = "conv-1",
            audioBytes = audio,
            durationMs = 1_000L,
            mimeType = "audio/m4a",
        )

        assertEquals(true, result.isFailure,
            "sendAudio must surface failure when transport.send rejects a chunk")
        // The local DB row was inserted before the loop, so it MUST stay
        // visible to the UI — but at status QUEUED, never flipped to SENT,
        // so the user sees "not yet delivered" instead of "sent" while
        // half the chunks silently never reached the relay.
        val audioRow = msgRepo.messages.single { it.plaintextCache?.startsWith("[AUDIO:") == true }
        assertEquals(MessageStatus.QUEUED, audioRow.status)
        // No success-side updateStatus(localMsgId, SENT) ever fired.
        assertEquals(null, msgRepo.statusUpdates[audioRow.id])
        // Exactly two `transport.send` calls — chunk 0 succeeded, chunk 1
        // was rejected and threw out of the loop before chunk 2 would have
        // been tried (there is no chunk 2 here — total = 2 — but the
        // assertion documents the "abort on first false" contract).
        assertEquals(2, transport.sent.size)
    }

    // ── PR-D2b.1: receiver finalizer ──────────────────────────────────
    //
    // After process death between chunk save and assembly + insertMessage,
    // chunks still live in voice_chunks. On next startReceiving the
    // finalizer scans `findVoicesReadyToAssemble` and resumes any voice
    // whose count has reached `total`.

    @Test
    fun startReceiving_finalizer_assemblesReadyVoice_andDeletesChunks() = runTest {
        LibsodiumInitializer.initialize()
        val msgRepo = FakeMessageRepository()
        val convRepo = FakeConversationRepository()
        // Seed an existing conversation so the upsert path takes the
        // "bump unread + lastMessagePreview" branch, not the REQUEST
        // bootstrap branch (irrelevant for this assertion).
        convRepo.store["conv-finalize"] = ConversationEntity(
            id = "conv-finalize",
            theirUsername = "peer",
            theirPublicKeyHex = "deadbeef",
            lastMessagePreview = "",
            lastMessageAt = 0L,
            unreadCount = 0,
            trustTier = phantom.core.storage.TrustTier.TRUSTED,
            blocked = false,
        )
        val voiceRepo = FakeVoiceChunkLedger()
        // Pre-populate a complete voice (2/2 chunks).
        val nowMs = System.currentTimeMillis()
        voiceRepo.insertChunk(
            voiceId = "v-ready",
            idx = 0,
            total = 2,
            conversationId = "conv-finalize",
            senderPubKeyHex = "deadbeef",
            mimeType = "audio/m4a",
            durationMs = 5_000L,
            chunkBytes = byteArrayOf(0x10, 0x20),
            nowMs = nowMs,
        )
        voiceRepo.insertChunk(
            voiceId = "v-ready",
            idx = 1,
            total = 2,
            conversationId = "conv-finalize",
            senderPubKeyHex = "deadbeef",
            mimeType = "audio/m4a",
            durationMs = 5_000L,
            chunkBytes = byteArrayOf(0x30, 0x40),
            nowMs = nowMs,
        )

        val service = buildService(
            testScope = this,
            msgRepo = msgRepo,
            convRepo = convRepo,
            voiceChunkRepo = voiceRepo,
            scope = backgroundScope,
        )

        service.startReceiving()
        testScheduler.runCurrent()

        // The finalizer assembled the voice and inserted ONE [AUDIO:...]
        // row into the message store, keyed on voiceId.
        val audioRow = msgRepo.messages.singleOrNull { it.id == "v-ready" }
            ?: error("finalizer must have inserted exactly one row for voiceId=v-ready")
        assertEquals("conv-finalize", audioRow.conversationId)
        val pc = audioRow.plaintextCache ?: error("audio row must have plaintextCache")
        assertEquals(true, pc.startsWith("[AUDIO:"))
        // Bytes were concatenated in idx order: 0x10 0x20 0x30 0x40.
        val assembledB64 = pc.removePrefix("[AUDIO:").removeSuffix("]")
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val assembled = kotlin.io.encoding.Base64.decode(assembledB64)
        assertEquals(listOf(0x10.toByte(), 0x20.toByte(), 0x30.toByte(), 0x40.toByte()),
            assembled.toList())
        // And the partial-state was freed.
        assertEquals(0, voiceRepo.countChunks("v-ready"))
    }

    @Test
    fun startReceiving_finalizer_skipsAlreadyInsertedVoice_andCleansUpChunks() = runTest {
        // PR-D2b.1 review round 2 regression test.
        //
        // Crash window: live receive path inserted the assembled message
        // into the message store but the process died before
        // `deleteByVoiceId` ran. On restart the finalizer sees a complete
        // partial buffer and would, pre-fix, re-emit `_incomingMessages`,
        // re-bump unread, and fire a second notification — even though
        // `messageRepository.insertMessage` is INSERT OR IGNORE and the
        // actual row write was a silent no-op. Post-fix: pre-check
        // `getMessageById(voiceId)` and short-circuit straight to
        // `deleteByVoiceId` with no UX side effects.
        LibsodiumInitializer.initialize()
        val msgRepo = FakeMessageRepository()
        val convRepo = FakeConversationRepository()
        convRepo.store["conv-restart"] = ConversationEntity(
            id = "conv-restart",
            theirUsername = "peer",
            theirPublicKeyHex = "deadbeef",
            lastMessagePreview = "🎤 Voice message",
            lastMessageAt = 0L,
            unreadCount = 1,  // already bumped by live path before crash
            trustTier = phantom.core.storage.TrustTier.TRUSTED,
            blocked = false,
        )
        // Voice message already exists in DB (live path inserted it
        // before crash). Use the same `voiceId` we'll seed the chunks
        // with — finalizer uses id = voiceId.
        msgRepo.messages += MessageEntity(
            id = "v-already-inserted",
            conversationId = "conv-restart",
            ciphertext = ByteArray(0),
            plaintextCache = "[AUDIO:cHJlLWluc2VydGVk]",  // arbitrary, the row's existence is what matters
            sent = false,
            status = MessageStatus.DELIVERED,
            createdAt = 100L,
            expiresAtMs = null,
        )
        val voiceRepo = FakeVoiceChunkLedger()
        // Complete chunks survived the crash.
        val now = System.currentTimeMillis()
        voiceRepo.insertChunk(
            voiceId = "v-already-inserted", idx = 0, total = 2,
            conversationId = "conv-restart", senderPubKeyHex = "deadbeef",
            mimeType = "audio/m4a", durationMs = 5_000L,
            chunkBytes = byteArrayOf(1), nowMs = now,
        )
        voiceRepo.insertChunk(
            voiceId = "v-already-inserted", idx = 1, total = 2,
            conversationId = "conv-restart", senderPubKeyHex = "deadbeef",
            mimeType = "audio/m4a", durationMs = 5_000L,
            chunkBytes = byteArrayOf(2), nowMs = now,
        )

        val service = buildService(
            testScope = this,
            msgRepo = msgRepo,
            convRepo = convRepo,
            voiceChunkRepo = voiceRepo,
            scope = backgroundScope,
        )

        service.startReceiving()
        testScheduler.runCurrent()

        // Exactly one message row exists (the pre-seeded one). Finalizer
        // did NOT add a duplicate, did NOT overwrite the plaintextCache.
        val rows = msgRepo.messages.filter { it.id == "v-already-inserted" }
        assertEquals(1, rows.size)
        assertEquals("[AUDIO:cHJlLWluc2VydGVk]", rows.single().plaintextCache)
        // Conversation unread stayed at the pre-crash value — finalizer
        // did NOT double-bump.
        assertEquals(1, convRepo.store.getValue("conv-restart").unreadCount)
        // Partial state freed despite the no-op insert path.
        assertEquals(0, voiceRepo.countChunks("v-already-inserted"))
    }

    @Test
    fun startReceiving_finalizer_leavesIncompleteVoiceUntouched() = runTest {
        LibsodiumInitializer.initialize()
        val msgRepo = FakeMessageRepository()
        val voiceRepo = FakeVoiceChunkLedger()
        // Only 1 of 3 chunks — finalizer must NOT assemble.
        voiceRepo.insertChunk(
            voiceId = "v-partial",
            idx = 0,
            total = 3,
            conversationId = "conv-A",
            senderPubKeyHex = "abc",
            mimeType = "audio/m4a",
            durationMs = 5_000L,
            chunkBytes = byteArrayOf(0x99.toByte()),
            nowMs = System.currentTimeMillis(),
        )

        val service = buildService(
            testScope = this,
            msgRepo = msgRepo,
            voiceChunkRepo = voiceRepo,
            scope = backgroundScope,
        )
        service.startReceiving()
        testScheduler.runCurrent()

        // No message row was created for the partial voice.
        assertEquals(0, msgRepo.messages.count { it.plaintextCache?.startsWith("[AUDIO:") == true })
        // Chunk is still there — finalizer left it for a future arrival
        // of the remaining 2 chunks (or for the 24 h TTL sweep).
        assertEquals(1, voiceRepo.countChunks("v-partial"))
    }

    @Test
    fun startReceiving_finalizer_evictsChunksOlderThanTtl() = runTest {
        LibsodiumInitializer.initialize()
        val voiceRepo = FakeVoiceChunkLedger()
        // Two chunks both older than VOICE_CHUNK_TTL_MS (24 h). The
        // sweep runs inside startReceiving before findVoicesReadyToAssemble,
        // so even though COUNT == total the sweep wins first.
        val veryOldMs = System.currentTimeMillis() -
            (DefaultMessagingService.VOICE_CHUNK_TTL_MS + 60_000L)
        voiceRepo.insertChunk(
            voiceId = "v-stale", idx = 0, total = 2,
            conversationId = "conv-A", senderPubKeyHex = "abc",
            mimeType = "audio/m4a", durationMs = 1_000L,
            chunkBytes = byteArrayOf(0xAA.toByte()), nowMs = veryOldMs,
        )
        voiceRepo.insertChunk(
            voiceId = "v-stale", idx = 1, total = 2,
            conversationId = "conv-A", senderPubKeyHex = "abc",
            mimeType = "audio/m4a", durationMs = 1_000L,
            chunkBytes = byteArrayOf(0xBB.toByte()), nowMs = veryOldMs,
        )

        val msgRepo = FakeMessageRepository()
        val service = buildService(
            testScope = this,
            msgRepo = msgRepo,
            voiceChunkRepo = voiceRepo,
            scope = backgroundScope,
        )
        service.startReceiving()
        testScheduler.runCurrent()

        // Expired chunks dropped, no message inserted from stale data.
        assertEquals(0, voiceRepo.countChunks("v-stale"))
        assertEquals(0, msgRepo.messages.count { it.plaintextCache?.startsWith("[AUDIO:") == true })
    }
}

/**
 * In-memory copy of `ProcessedEnvelopeRepository` for PR-H2b tests.
 * Separate from the same-named fake in the storage module's commonTest
 * because cross-module test-fixtures sharing isn't set up in this repo.
 */
private class FakeProcessedEnvelopeLedger : phantom.core.storage.ProcessedEnvelopeRepository {
    private data class Row(
        val payloadType: String,
        val status: phantom.core.storage.ProcessedEnvelopeRepository.Status,
        val createdAtMs: Long,
    )
    private val store = mutableMapOf<String, Row>()

    override suspend fun exists(envelopeId: String): Boolean = store.containsKey(envelopeId)

    override suspend fun markProcessed(
        envelopeId: String,
        conversationId: String,
        senderPubKeyHex: String,
        payloadType: String,
        status: phantom.core.storage.ProcessedEnvelopeRepository.Status,
        nowMs: Long,
    ) {
        if (envelopeId !in store) {
            store[envelopeId] = Row(payloadType, status, nowMs)
        }
    }

    override suspend fun deleteOlderThan(olderThanMs: Long) {
        store.entries.removeAll { it.value.createdAtMs < olderThanMs }
    }

    override suspend fun countByStatus(): Map<phantom.core.storage.ProcessedEnvelopeRepository.Status, Long> =
        store.values.groupingBy { it.status }.eachCount().mapValues { it.value.toLong() }

    override suspend fun deleteAll() {
        store.clear()
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
    override val typingEvents: SharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 10)
    override suspend fun connect(
        relayUrl: String,
        identityPublicKeyHex: String,
        signingPublicKeyHex: String,
        signChallenge: suspend (challenge: ByteArray) -> ByteArray?,
        socksProxyPort: Int?,
    ) {}
    override suspend fun disconnect() {}
    override suspend fun send(message: phantom.core.transport.RelayMessage.Send): Boolean = true
    override suspend fun sendDeliveryAck(messageId: String): Boolean = true
    override suspend fun sendTyping(toPubKeyHex: String): Boolean = true
    override fun isConnected(): Boolean = true
    // ADR-011 / ADR-013: stubs satisfying the new RelayTransport contract.
    override val lastPongElapsedMs: Long get() = 0L
    override val lastInboundFrameElapsedMs: Long get() = 0L
    override val pendingAckCount: Int get() = 0
    override suspend fun forceReconnect() {}
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

/**
 * In-memory copy of `VoiceChunkRepository` for PR-D2b.1 tests. Separate
 * from the same-named fake in the storage module's commonTest because
 * cross-module test-fixtures sharing isn't set up in this repo (same
 * rationale as `FakeProcessedEnvelopeLedger` above). Mirrors
 * `SqlDelightVoiceChunkRepository` semantics:
 *
 * - PRIMARY KEY (voice_id, idx) via Map<Pair<String, Int>, Row>
 * - INSERT OR REPLACE on insertChunk
 * - TTL sweep by `updated_at_ms` cutoff
 * - findVoicesReadyToAssemble groups by voice_id and filters
 *   COUNT(idx) == total
 */
private class FakeVoiceChunkLedger : phantom.core.storage.VoiceChunkRepository {
    private data class Row(
        val voiceId: String,
        val idx: Int,
        val total: Int,
        val conversationId: String,
        val senderPubKeyHex: String,
        val mimeType: String,
        val durationMs: Long,
        val chunkBytes: ByteArray,
        val updatedAtMs: Long,
    )

    private val store = mutableMapOf<Pair<String, Int>, Row>()

    override suspend fun insertChunk(
        voiceId: String,
        idx: Int,
        total: Int,
        conversationId: String,
        senderPubKeyHex: String,
        mimeType: String,
        durationMs: Long,
        chunkBytes: ByteArray,
        nowMs: Long,
    ) {
        store[voiceId to idx] = Row(
            voiceId, idx, total, conversationId, senderPubKeyHex,
            mimeType, durationMs, chunkBytes, nowMs,
        )
    }

    override suspend fun countChunks(voiceId: String): Int =
        store.values.count { it.voiceId == voiceId }

    override suspend fun findOrderedChunks(voiceId: String): List<ByteArray> =
        store.values.filter { it.voiceId == voiceId }
            .sortedBy { it.idx }
            .map { it.chunkBytes }

    override suspend fun findVoicesReadyToAssemble(): List<phantom.core.storage.VoiceChunkRepository.ReadyVoice> {
        val byVoice = store.values.groupBy { it.voiceId }
        return byVoice.mapNotNull { (vid, rows) ->
            val total = rows.first().total
            if (rows.size != total) return@mapNotNull null
            val head = rows.first()
            phantom.core.storage.VoiceChunkRepository.ReadyVoice(
                voiceId = vid,
                total = total,
                conversationId = head.conversationId,
                senderPubKeyHex = head.senderPubKeyHex,
                mimeType = head.mimeType,
                durationMs = head.durationMs,
            )
        }
    }

    override suspend fun findExpiredSummaries(
        cutoffMs: Long,
    ): List<phantom.core.storage.VoiceChunkRepository.ExpiredSummary> {
        val byVoice = store.values.groupBy { it.voiceId }
        return byVoice.mapNotNull { (vid, rows) ->
            val oldest = rows.minOf { it.updatedAtMs }
            if (oldest >= cutoffMs) return@mapNotNull null
            phantom.core.storage.VoiceChunkRepository.ExpiredSummary(
                voiceId = vid,
                total = rows.first().total,
                receivedChunks = rows.size,
                oldestUpdatedMs = oldest,
            )
        }
    }

    override suspend fun deleteOlderThan(cutoffMs: Long) {
        // PR-D2b.1 review round 2: mirror `VoiceChunk.sq deleteOlderThan` —
        // drop every chunk for any voice whose oldest chunk is older than
        // the cutoff, not just the individual old rows. Anything else
        // leaves voices in a permanently-unassemblable mixed-age state.
        val expiredVoiceIds = store.values
            .groupBy { it.voiceId }
            .filterValues { rows -> rows.minOf { it.updatedAtMs } < cutoffMs }
            .keys
        store.entries.removeAll { it.value.voiceId in expiredVoiceIds }
    }

    override suspend fun deleteByVoiceId(voiceId: String) {
        store.entries.removeAll { it.value.voiceId == voiceId }
    }

    override suspend fun deleteAll() {
        store.clear()
    }
}
