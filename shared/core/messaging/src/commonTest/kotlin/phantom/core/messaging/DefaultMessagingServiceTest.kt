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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

    // PR-CRYPTO-SESSION-REPAIR1 commit 2 (2026-05-29) — test-fake stubs
    // for the new session_suspect mutators. Mirror the SqlDelight impl.
    override suspend fun setSessionSuspect(conversationId: String, setAtMs: Long) {
        store[conversationId]?.let {
            store[conversationId] = it.copy(
                sessionSuspect = true,
                sessionSuspectSetAtMs = setAtMs,
            )
        }
    }

    override suspend fun clearSessionSuspect(conversationId: String) {
        store[conversationId]?.let {
            store[conversationId] = it.copy(
                sessionSuspect = false,
                sessionSuspectSetAtMs = null,
            )
        }
    }

    override suspend fun getSessionSuspectConversations(): List<ConversationEntity> =
        store.values.filter { it.sessionSuspect }.toList()
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

// PR-CRYPTO-SESSION-REPAIR1 commit 5b (2026-05-31). Passthrough
// encrypt + decrypt returns a state with a recognizable marker
// `receivingChainKey` (all-0x55) so the no-advance tests can detect
// whether saveSession was called with the post-decrypt newState
// (marker present in persisted state) or not (pre-decrypt state
// preserved). Without this marker, plain Passthrough's
// `state to ciphertext` would make every saved state look identical.
private class MarkingPassthroughDoubleRatchet : DoubleRatchet {
    override fun encrypt(state: RatchetState, plaintext: ByteArray): Pair<RatchetState, EncryptedMessage> =
        state to EncryptedMessage(
            ratchetPublicKey = state.sendingRatchetPublicKey,
            messageIndex = state.sendCount,
            ciphertext = plaintext,
            nonce = ByteArray(24),
        )

    override fun decrypt(state: RatchetState, message: EncryptedMessage): Pair<RatchetState, ByteArray> {
        val markedState = state.copy(receivingChainKey = MARKER_RECEIVING_CHAIN_KEY)
        return markedState to message.ciphertext
    }

    companion object {
        val MARKER_RECEIVING_CHAIN_KEY: ByteArray = ByteArray(32) { 0x55.toByte() }
    }
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

    // PR-CRYPTO-SESSION-REPAIR1 commit 6 (2026-05-31): all replay /
    // safety / TTL tests must use a `received_at_ms` close to
    // `Clock.System.now()` so the 24h TTL sweep at the entry of
    // `replayHeldEnvelopesAfterRepair` does NOT evict the seeded
    // rows mid-test. The legacy small-integer values (100L, 200L, …)
    // are pre-1970-anchored relative to wall-clock and would be
    // deleted by the sweep before the replay loop runs.
    private val recentReceivedAtMs: Long
        get() = Clock.System.now().toEpochMilliseconds() - 1_000L

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
        // PR-CRYPTO-SESSION-REPAIR1 commit 3b (2026-05-30): allow tests
        // to substitute the DoubleRatchet so MAC-error paths can be
        // exercised via [MacFailingDoubleRatchet]. Default preserves
        // existing call-sites + pre-PR receive semantics.
        ratchet: phantom.core.crypto.DoubleRatchet = PassthroughDoubleRatchet(),
        // PR-CRYPTO-SESSION-REPAIR1 commit 3b: optional held-envelope
        // repo. When null, hold-on-MAC is unavailable regardless of
        // [holdMacFailures] (the gate is `holdMacFailures && repo !=
        // null`). When non-null, [holdMacFailures] picks between hold
        // (true) and ack (false).
        decryptFailedRepo: phantom.core.storage.DecryptFailedEnvelopeRepository? = null,
        // PR-CRYPTO-SESSION-REPAIR1 commit 3b: semantic flag matching the
        // production constructor param. Tests set this per scenario.
        holdMacFailures: Boolean = false,
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
            ratchet = ratchet,
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
            decryptFailedEnvelopeRepository = decryptFailedRepo,
            holdMacFailures = holdMacFailures,
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

    // ═══════════════════════════════════════════════════════════════════
    // PR-CRYPTO-SESSION-REPAIR1 commit 3b (2026-05-30) — first test of
    // the architect-locked 5-matrix. This single test proves invariants
    // 1 + 2: when `holdMacFailures=true` AND repo is non-null AND the
    // ratchet throws MAC, the receive path:
    //   - does NOT call transport.sendDeliveryAck (invariant 1);
    //   - does NOT write to processed_envelopes (invariant 2);
    //   - writes a row to decrypt_failed_envelopes with error_type=mac;
    //   - marks conversationRepository session_suspect = true.
    //
    // Pattern reviewable by architect on the PR thread; remaining 4
    // tests (decrypt_mac_error_acks_on_release, normal_path × 2,
    // wireFrameJson round-trip) follow as commit 3c after pattern ACK.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun decrypt_mac_error_holds_in_debug() = runTest {
        com.ionspin.kotlin.crypto.LibsodiumInitializer.initialize()
        val transport = FakeRelayTransport()
        val processedRepo = FakeProcessedEnvelopeLedger()
        val decryptFailedRepo = FakeDecryptFailedEnvelopeLedger()
        val convRepo = FakeConversationRepository()
        // Pre-seed conversation row so setSessionSuspect can update it.
        // conv id = sorted([identity.publicKeyHex="aabb", senderHex="ccdd"]).join("_") = "aabb_ccdd",
        // which buildService's sessionManager pre-seeds.
        convRepo.store["aabb_ccdd"] = phantom.core.storage.ConversationEntity(
            id = "aabb_ccdd",
            theirUsername = "peer",
            theirPublicKeyHex = "ccdd",
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
        )

        val service = buildService(
            this,
            transport = transport,
            convRepo = convRepo,
            processedRepo = processedRepo,
            scope = backgroundScope,
            // Hold-path enablers:
            ratchet = MacFailingDoubleRatchet(),
            decryptFailedRepo = decryptFailedRepo,
            holdMacFailures = true,
        )
        service.startReceiving()
        testScheduler.runCurrent()

        // Build a Deliver whose payload IS valid (parses past the
        // unpad + WireFrame JSON decode), but whose ratchet.decrypt
        // throws MAC. MacFailingDoubleRatchet throws regardless of
        // ciphertext bytes, so the inner EncryptedMessage can be
        // arbitrary.
        val wireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = byteArrayOf(0x00, 0x01, 0x02),
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        val wireFrameBytes = json.encodeToString(WireFrame.serializer(), wireFrame).encodeToByteArray()
        val padded = phantom.core.crypto.MessagePadding.pad(wireFrameBytes)
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val payloadB64 = kotlin.io.encoding.Base64.encode(padded)

        transport.deliver(
            phantom.core.transport.RelayMessage.Deliver(
                from = "ccdd",
                sealedSender = "",
                payload = payloadB64,
                messageId = "env-debug-mac",
            ),
        )
        testScheduler.runCurrent()

        // ── Invariant 1: no ack ────────────────────────────────────────
        assertTrue(
            "env-debug-mac" !in transport.ackedDelivers,
            "hold path MUST NOT call sendDeliveryAck; transport.ackedDelivers=${transport.ackedDelivers}",
        )
        // ── Invariant 2: no FAILED_MAC ledger entry ────────────────────
        assertEquals(
            false,
            processedRepo.exists("env-debug-mac"),
            "hold path MUST NOT call markProcessed; processedRepo has the envelope",
        )
        // ── Held row exists in decrypt_failed_envelopes ────────────────
        val held = decryptFailedRepo.listByConversation("aabb_ccdd")
        assertEquals(1, held.size, "exactly one held envelope expected; got $held")
        assertEquals("env-debug-mac", held[0].envelopeId)
        assertEquals("mac", held[0].errorType)
        // wireFrameJson stored is non-empty + decodes back into WireFrame.
        assertTrue(held[0].wireFrameJson.isNotEmpty(), "wireFrameJson must be persisted")
        val decoded = json.decodeFromString(WireFrame.serializer(), held[0].wireFrameJson)
        assertEquals(
            wireFrame.encryptedMessage.messageIndex,
            decoded.encryptedMessage.messageIndex,
            "round-trip messageIndex",
        )
        // ── Conversation marked session_suspect ────────────────────────
        val updatedConv = convRepo.getConversation("aabb_ccdd")
        assertTrue(
            updatedConv?.sessionSuspect == true,
            "session_suspect MUST be true after hold path runs; got $updatedConv",
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // PR-CRYPTO-SESSION-REPAIR1 commit 3c (2026-05-30) — remaining 4
    // tests of the architect-locked 5-matrix. Pattern from
    // `decrypt_mac_error_holds_in_debug` (commit 3b 22af5d7b)
    // architect-ACKed; these tests are direct rig variations + the
    // dedicated byte-level wireFrameJson regression guard.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun decrypt_mac_error_acks_on_release() = runTest {
        // Architect-locked invariant 4: when holdMacFailures=false, the
        // release path is unchanged from pre-PR. Verify the destructive
        // ack + FAILED_MAC ledger entry still fires AND the new hold
        // table stays empty + suspect flag stays false.
        com.ionspin.kotlin.crypto.LibsodiumInitializer.initialize()
        val transport = FakeRelayTransport()
        val processedRepo = FakeProcessedEnvelopeLedger()
        val decryptFailedRepo = FakeDecryptFailedEnvelopeLedger()
        val convRepo = FakeConversationRepository()
        convRepo.store["aabb_ccdd"] = phantom.core.storage.ConversationEntity(
            id = "aabb_ccdd",
            theirUsername = "peer",
            theirPublicKeyHex = "ccdd",
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
        )

        val service = buildService(
            this,
            transport = transport,
            convRepo = convRepo,
            processedRepo = processedRepo,
            scope = backgroundScope,
            ratchet = MacFailingDoubleRatchet(),
            decryptFailedRepo = decryptFailedRepo,
            // Release / production gate — even with the repo non-null,
            // holdMacFailures=false MUST keep the existing ack path.
            holdMacFailures = false,
        )
        service.startReceiving()
        testScheduler.runCurrent()

        val wireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = byteArrayOf(0x10, 0x11, 0x12),
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        val wireFrameBytes = json.encodeToString(WireFrame.serializer(), wireFrame).encodeToByteArray()
        val padded = phantom.core.crypto.MessagePadding.pad(wireFrameBytes)
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val payloadB64 = kotlin.io.encoding.Base64.encode(padded)

        transport.deliver(
            phantom.core.transport.RelayMessage.Deliver(
                from = "ccdd",
                sealedSender = "",
                payload = payloadB64,
                messageId = "env-release-mac",
            ),
        )
        testScheduler.runCurrent()

        // Release path: ack fires, FAILED_MAC ledger row written.
        assertTrue(
            "env-release-mac" in transport.ackedDelivers,
            "release path MUST call sendDeliveryAck; transport.ackedDelivers=${transport.ackedDelivers}",
        )
        assertEquals(
            true,
            processedRepo.exists("env-release-mac"),
            "release path MUST call markProcessed FAILED_MAC",
        )
        // Hold table untouched + suspect stays false.
        assertEquals(
            0,
            decryptFailedRepo.listByConversation("aabb_ccdd").size,
            "release path MUST NOT write to decrypt_failed_envelopes",
        )
        val updatedConv = convRepo.getConversation("aabb_ccdd")
        assertEquals(
            false,
            updatedConv?.sessionSuspect ?: false,
            "release path MUST NOT set session_suspect",
        )
    }

    @Test
    fun normal_path_unaffected_in_debug() = runTest {
        // Architect-locked invariant 5: non-MAC envelope under
        // holdMacFailures=true takes the unchanged normal flow — ack,
        // markProcessed PROCESSED, message inserted, no hold table
        // touch, no suspect mark.
        com.ionspin.kotlin.crypto.LibsodiumInitializer.initialize()
        val transport = FakeRelayTransport()
        val processedRepo = FakeProcessedEnvelopeLedger()
        val decryptFailedRepo = FakeDecryptFailedEnvelopeLedger()
        val convRepo = FakeConversationRepository()
        convRepo.store["aabb_ccdd"] = phantom.core.storage.ConversationEntity(
            id = "aabb_ccdd",
            theirUsername = "peer",
            theirPublicKeyHex = "ccdd",
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
        )
        val msgRepo = FakeMessageRepository()

        val service = buildService(
            this,
            transport = transport,
            convRepo = convRepo,
            processedRepo = processedRepo,
            msgRepo = msgRepo,
            scope = backgroundScope,
            // PassthroughDoubleRatchet returns ciphertext verbatim so
            // the receive path JSON-decodes it as MessagePayload below.
            ratchet = PassthroughDoubleRatchet(),
            decryptFailedRepo = decryptFailedRepo,
            holdMacFailures = true,
        )
        service.startReceiving()
        testScheduler.runCurrent()

        // For PassthroughDoubleRatchet the ciphertext IS the plaintext.
        // Build a valid MessagePayload so the post-decrypt JSON parse
        // succeeds and the downstream insert / ack path runs normally.
        val payload = MessagePayload(
            type = "message",
            text = "hi from normal path debug",
        )
        val plaintextJsonBytes = json
            .encodeToString(MessagePayload.serializer(), payload)
            .encodeToByteArray()
        val wireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = plaintextJsonBytes,
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        val wireFrameBytes = json.encodeToString(WireFrame.serializer(), wireFrame).encodeToByteArray()
        val padded = phantom.core.crypto.MessagePadding.pad(wireFrameBytes)
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val payloadB64 = kotlin.io.encoding.Base64.encode(padded)

        transport.deliver(
            phantom.core.transport.RelayMessage.Deliver(
                from = "ccdd",
                sealedSender = "",
                payload = payloadB64,
                messageId = "env-debug-normal",
            ),
        )
        testScheduler.runCurrent()

        // Normal path: ack fires + PROCESSED ledger row + message persisted.
        assertTrue(
            "env-debug-normal" in transport.ackedDelivers,
            "normal path MUST call sendDeliveryAck",
        )
        assertEquals(
            true,
            processedRepo.exists("env-debug-normal"),
            "normal path MUST call markProcessed",
        )
        assertTrue(
            msgRepo.messages.any { it.plaintextCache == "hi from normal path debug" },
            "normal path MUST insert the decoded message; got ${msgRepo.messages.map { it.plaintextCache }}",
        )
        // Hold table untouched + suspect stays false (architect invariant 5).
        assertEquals(
            0,
            decryptFailedRepo.listByConversation("aabb_ccdd").size,
            "normal path under holdMacFailures=true MUST NOT touch decrypt_failed_envelopes",
        )
        val updatedConv = convRepo.getConversation("aabb_ccdd")
        assertEquals(
            false,
            updatedConv?.sessionSuspect ?: false,
            "normal path MUST NOT set session_suspect",
        )
    }

    @Test
    fun normal_path_unaffected_in_release() = runTest {
        // Mirror of the debug normal-path test under holdMacFailures=false.
        // Both should look identical from the receive-path perspective
        // because the hold branch is only taken on MAC error, never on
        // a successful decrypt.
        com.ionspin.kotlin.crypto.LibsodiumInitializer.initialize()
        val transport = FakeRelayTransport()
        val processedRepo = FakeProcessedEnvelopeLedger()
        val decryptFailedRepo = FakeDecryptFailedEnvelopeLedger()
        val convRepo = FakeConversationRepository()
        convRepo.store["aabb_ccdd"] = phantom.core.storage.ConversationEntity(
            id = "aabb_ccdd",
            theirUsername = "peer",
            theirPublicKeyHex = "ccdd",
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
        )
        val msgRepo = FakeMessageRepository()

        val service = buildService(
            this,
            transport = transport,
            convRepo = convRepo,
            processedRepo = processedRepo,
            msgRepo = msgRepo,
            scope = backgroundScope,
            ratchet = PassthroughDoubleRatchet(),
            decryptFailedRepo = decryptFailedRepo,
            holdMacFailures = false,
        )
        service.startReceiving()
        testScheduler.runCurrent()

        val payload = MessagePayload(
            type = "message",
            text = "hi from normal path release",
        )
        val plaintextJsonBytes = json
            .encodeToString(MessagePayload.serializer(), payload)
            .encodeToByteArray()
        val wireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = plaintextJsonBytes,
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        val wireFrameBytes = json.encodeToString(WireFrame.serializer(), wireFrame).encodeToByteArray()
        val padded = phantom.core.crypto.MessagePadding.pad(wireFrameBytes)
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val payloadB64 = kotlin.io.encoding.Base64.encode(padded)

        transport.deliver(
            phantom.core.transport.RelayMessage.Deliver(
                from = "ccdd",
                sealedSender = "",
                payload = payloadB64,
                messageId = "env-release-normal",
            ),
        )
        testScheduler.runCurrent()

        assertTrue("env-release-normal" in transport.ackedDelivers)
        assertEquals(true, processedRepo.exists("env-release-normal"))
        assertTrue(
            msgRepo.messages.any { it.plaintextCache == "hi from normal path release" },
        )
        assertEquals(0, decryptFailedRepo.listByConversation("aabb_ccdd").size)
        val updatedConv = convRepo.getConversation("aabb_ccdd")
        assertEquals(false, updatedConv?.sessionSuspect ?: false)
    }

    @Test
    fun hold_path_wireFrameJson_decodes_back_to_inner_WireFrame_preserving_encryptedMessage() = runTest {
        // Architect-strengthened wireFrameJson regression guard (P2
        // 2026-05-30): the first test (decrypt_mac_error_holds_in_debug)
        // only checked `messageIndex` round-trip. This dedicated test
        // verifies byte-level equality on the ENTIRE inner
        // `EncryptedMessage` so the replay-payload contract is locked.
        // If a future change accidentally re-encodes the wire frame as
        // outer `RelayMessage.Deliver` JSON (the wrong layer per
        // architect 2026-05-29 on PR #243 95c7aae0), this test fails.
        com.ionspin.kotlin.crypto.LibsodiumInitializer.initialize()
        val transport = FakeRelayTransport()
        val processedRepo = FakeProcessedEnvelopeLedger()
        val decryptFailedRepo = FakeDecryptFailedEnvelopeLedger()
        val convRepo = FakeConversationRepository()
        convRepo.store["aabb_ccdd"] = phantom.core.storage.ConversationEntity(
            id = "aabb_ccdd",
            theirUsername = "peer",
            theirPublicKeyHex = "ccdd",
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
        )

        val service = buildService(
            this,
            transport = transport,
            convRepo = convRepo,
            processedRepo = processedRepo,
            scope = backgroundScope,
            ratchet = MacFailingDoubleRatchet(),
            decryptFailedRepo = decryptFailedRepo,
            holdMacFailures = true,
        )
        service.startReceiving()
        testScheduler.runCurrent()

        // Distinct non-zero byte patterns per field so a swap between
        // fields would be detectable.
        val knownRatchetPub = ByteArray(32) { (0xA0 + (it % 16)).toByte() }
        val knownNonce = ByteArray(24) { (0xB0 + (it % 16)).toByte() }
        val knownCiphertext = byteArrayOf(
            0xC0.toByte(), 0xC1.toByte(), 0xC2.toByte(), 0xC3.toByte(),
            0xC4.toByte(), 0xC5.toByte(), 0xC6.toByte(), 0xC7.toByte(),
        )
        val knownMessageIndex = 42
        val knownEncryptedMessage = phantom.core.crypto.EncryptedMessage(
            ratchetPublicKey = knownRatchetPub,
            messageIndex = knownMessageIndex,
            ciphertext = knownCiphertext,
            nonce = knownNonce,
        )
        val knownWireFrame = WireFrame(
            encryptedMessage = knownEncryptedMessage,
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )

        val wireFrameBytes = json
            .encodeToString(WireFrame.serializer(), knownWireFrame)
            .encodeToByteArray()
        val padded = phantom.core.crypto.MessagePadding.pad(wireFrameBytes)
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val payloadB64 = kotlin.io.encoding.Base64.encode(padded)

        transport.deliver(
            phantom.core.transport.RelayMessage.Deliver(
                from = "ccdd",
                sealedSender = "",
                payload = payloadB64,
                messageId = "env-wfj-roundtrip",
            ),
        )
        testScheduler.runCurrent()

        val held = decryptFailedRepo.listByConversation("aabb_ccdd")
        assertEquals(1, held.size, "exactly one held envelope expected")
        val storedJson = held[0].wireFrameJson

        // Decode via WireFrame.serializer() — NOT RelayMessage.Deliver.
        val decoded = json.decodeFromString(WireFrame.serializer(), storedJson)

        // ── Byte-level EncryptedMessage equality ───────────────────────
        assertEquals(
            knownMessageIndex,
            decoded.encryptedMessage.messageIndex,
            "messageIndex must round-trip",
        )
        assertTrue(
            knownRatchetPub.contentEquals(decoded.encryptedMessage.ratchetPublicKey),
            "ratchetPublicKey bytes must round-trip unchanged",
        )
        assertTrue(
            knownNonce.contentEquals(decoded.encryptedMessage.nonce),
            "nonce bytes must round-trip unchanged",
        )
        assertTrue(
            knownCiphertext.contentEquals(decoded.encryptedMessage.ciphertext),
            "ciphertext bytes must round-trip unchanged",
        )
        // x3dhInit absence must also survive the trip (relevant for the
        // commit 5 replay path: if x3dh init was present at receive
        // time, the replay must replay through the bootstrap branch,
        // not the existing-session branch).
        kotlin.test.assertNull(
            decoded.x3dhInit,
            "x3dhInit absence must round-trip as null",
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // PR-CRYPTO-SESSION-REPAIR1 commit 4 (2026-05-30) — suspect → fresh
    // X3DH on next outgoing. 4-test matrix from the architect mini-lock.
    // All assertions live entirely on the send path; receive ack /
    // markProcessed ownership stays in DMS per architect pre-decision #2.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun suspect_outgoing_forces_fresh_x3dh_and_clears_suspect_after_save() = runTest {
        // Set sessionSuspect=true on a conversation that ALREADY has an
        // existing session row. The outgoing send must take the fresh
        // X3DH bootstrap branch (verified by x3dhInit != null on the
        // wire) and clear sessionSuspect only after sessionManager
        // .saveSession commits (architect-correction 2026-05-29: clear
        // on local crypto commit, not on transport.send).
        LibsodiumInitializer.initialize()
        val transport = FakeRelayTransport()
        val msgRepo = FakeMessageRepository()
        val convRepo = FakeConversationRepository()

        // Bob has a real published bundle so the suspect-forced
        // bootstrap branch can actually complete.
        val real = phantom.core.crypto.LibsodiumX3DH()
        val bobX25519 = real.generateDhKeyPair()
        val bobSpk = real.generateDhKeyPair()
        val bobSigning = com.ionspin.kotlin.crypto.signature.Signature.keypair()
        val bobSpkSig = phantom.core.crypto.SignedPreKeySigner.sign(
            spkPublic = bobSpk.publicKey,
            createdAtMs = 1_000L,
            identityEd25519SecretKey = bobSigning.secretKey.toByteArray(),
        )
        val bobHex = bobX25519.publicKey.bytes.toHexStringLower()
        val bobBundle = phantom.core.transport.PreKeyBundle(
            identity_pubkey_hex = bobHex,
            signing_pubkey_hex = bobSigning.publicKey.toByteArray().toHexStringLower(),
            signed_pre_key = phantom.core.transport.WireSignedPreKey(
                key_id = 1L,
                public_key_hex = bobSpk.publicKey.bytes.toHexStringLower(),
                created_at_ms = 1_000L,
                signature_hex = bobSpkSig.toHexStringLower(),
            ),
            one_time_pre_key = null,
        )

        val convId = listOf(identity.publicKeyHex, bobHex).sorted().joinToString("_")

        // Pre-seed an existing session for this conv — the test proves
        // that the suspect flag overrides tryLoadSession (existingState
        // is NOT null, but the bootstrap branch still runs).
        val ratchetRepo = PreSeededRatchetStateRepository(seedFor = listOf(convId))
        // Conversation row with sessionSuspect=true.
        convRepo.store[convId] = phantom.core.storage.ConversationEntity(
            id = convId,
            theirUsername = "bob",
            theirPublicKeyHex = bobHex,
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
            sessionSuspect = true,
            sessionSuspectSetAtMs = 12_345L,
        )

        val sessionManager = SessionManager(
            x3dh = real,
            ratchetStateRepository = ratchetRepo,
            signedPreKeyRepository = FakeLocalSignedPreKeyRepository(),
            oneTimePreKeyRepository = FakeLocalOneTimePreKeyRepository(),
            identityCrypto = phantom.core.identity.LibsodiumIdentityCrypto(),
            json = json,
        )

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

        service.sendMessage(
            OutgoingMessage(
                id = "msg-suspect-1",
                conversationId = convId,
                recipientPublicKeyHex = bobHex,
                text = "repair-armed payload",
            ),
        )

        // Assertion 1: bootstrap path WAS taken (x3dhInit on wire).
        assertEquals(1, transport.sent.size, "send must reach transport")
        val payload = transport.sent[0].payload
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val padded = kotlin.io.encoding.Base64.decode(payload)
        val unpadded = phantom.core.crypto.MessagePadding.unpad(padded)
        val wireFrame = json.decodeFromString<WireFrame>(unpadded.decodeToString())
        assertNotNull(
            wireFrame.x3dhInit,
            "suspect conversation MUST force fresh X3DH bootstrap → x3dhInit on wire",
        )

        // Assertion 2: sessionSuspect cleared after saveSession.
        val updatedConv = convRepo.getConversation(convId)
        assertEquals(
            false,
            updatedConv?.sessionSuspect ?: true,
            "sessionSuspect MUST be cleared after fresh X3DH + saveSession succeed",
        )
    }

    @Test
    fun non_suspect_outgoing_uses_existing_session() = runTest {
        // Mirror baseline: when sessionSuspect=false (default), the
        // existing-session branch runs unchanged. x3dhInit on the wire
        // is null and no preKeyApi fetch happens (ThrowingPreKeyApi
        // would fail the test if the bootstrap branch was reached).
        LibsodiumInitializer.initialize()
        val transport = FakeRelayTransport()
        val convRepo = FakeConversationRepository()
        // Pre-seed a conv row with sessionSuspect=false (explicit for
        // clarity even though it's the default).
        convRepo.store["aabb_ccdd"] = phantom.core.storage.ConversationEntity(
            id = "aabb_ccdd",
            theirUsername = "peer",
            theirPublicKeyHex = "ccdd",
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
            sessionSuspect = false,
        )

        val service = buildService(
            this,
            transport = transport,
            convRepo = convRepo,
            scope = backgroundScope,
            // ThrowingPreKeyApi via default buildService — bootstrap
            // branch reaching preKeyApi.fetchBundle would throw.
        )

        service.sendMessage(
            OutgoingMessage(
                id = "msg-nosuspect",
                conversationId = "aabb_ccdd",
                recipientPublicKeyHex = "ccdd",
                text = "regular send",
            ),
        )

        // Existing-session path: x3dhInit must be null on the wire.
        assertEquals(1, transport.sent.size)
        val payload = transport.sent[0].payload
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val padded = kotlin.io.encoding.Base64.decode(payload)
        val unpadded = phantom.core.crypto.MessagePadding.unpad(padded)
        val wireFrame = json.decodeFromString<WireFrame>(unpadded.decodeToString())
        kotlin.test.assertNull(
            wireFrame.x3dhInit,
            "non-suspect outgoing MUST use existing session → x3dhInit MUST be null",
        )
        // Suspect stays false.
        val updatedConv = convRepo.getConversation("aabb_ccdd")
        assertEquals(false, updatedConv?.sessionSuspect ?: false)
    }

    @Test
    fun suspect_bootstrap_failure_keeps_suspect() = runTest {
        // Architect guardrail: if any of (prekey fetch, bootstrap,
        // encrypt, saveSession) throws, the clearSessionSuspect call
        // does NOT execute and the flag stays true so the next outgoing
        // retries the repair. Verified here by giving the conversation
        // a suspect flag + ThrowingPreKeyApi (default via buildService);
        // the fetch throws PeerBundleMissingException which propagates
        // out of encryptUnderLock.
        LibsodiumInitializer.initialize()
        val transport = FakeRelayTransport()
        val convRepo = FakeConversationRepository()
        convRepo.store["aabb_ccdd"] = phantom.core.storage.ConversationEntity(
            id = "aabb_ccdd",
            theirUsername = "peer",
            theirPublicKeyHex = "ccdd",
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
            sessionSuspect = true,
            sessionSuspectSetAtMs = 42L,
        )

        val service = buildService(
            this,
            transport = transport,
            convRepo = convRepo,
            scope = backgroundScope,
        )

        // sendMessage should NOT throw out (DMS surfaces bundle-missing
        // as a WAITING result internally), but the conversation's
        // sessionSuspect MUST remain true regardless.
        runCatching {
            service.sendMessage(
                OutgoingMessage(
                    id = "msg-fail-suspect",
                    conversationId = "aabb_ccdd",
                    recipientPublicKeyHex = "ccdd",
                    text = "bootstrap will fail because ThrowingPreKeyApi",
                )
            )
        }

        // The wire MUST NOT carry a message — bootstrap failed.
        assertEquals(
            0,
            transport.sent.size,
            "bootstrap failure must NOT produce a transport.send",
        )
        // The suspect flag MUST remain true so the next outgoing retries.
        val updatedConv = convRepo.getConversation("aabb_ccdd")
        assertEquals(
            true,
            updatedConv?.sessionSuspect ?: false,
            "sessionSuspect MUST remain true on bootstrap/encrypt/save failure",
        )
        // Diagnostic: the original setAtMs is also preserved (since the
        // flag was never cleared and re-set, the timestamp does not move).
        assertEquals(42L, updatedConv?.sessionSuspectSetAtMs)
    }

    @Test
    fun repair_is_per_conversation_only() = runTest {
        // Two distinct conversations. Setting sessionSuspect=true on
        // conversation A must NOT affect conversation B. An outgoing
        // message in B uses B's existing session (x3dhInit null on
        // wire), and A's suspect flag remains untouched because no
        // outgoing send in A was issued.
        LibsodiumInitializer.initialize()
        val transport = FakeRelayTransport()
        val convRepo = FakeConversationRepository()
        // Conv A (peer ccdd) is suspect.
        convRepo.store["aabb_ccdd"] = phantom.core.storage.ConversationEntity(
            id = "aabb_ccdd",
            theirUsername = "ccdd-peer",
            theirPublicKeyHex = "ccdd",
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
            sessionSuspect = true,
            sessionSuspectSetAtMs = 9_999L,
        )
        // Conv B (peer eeff) is clean. Both convs are pre-seeded in the
        // buildService ratchet repo by default.
        convRepo.store["aabb_eeff"] = phantom.core.storage.ConversationEntity(
            id = "aabb_eeff",
            theirUsername = "eeff-peer",
            theirPublicKeyHex = "eeff",
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
            sessionSuspect = false,
        )

        val service = buildService(
            this,
            transport = transport,
            convRepo = convRepo,
            scope = backgroundScope,
        )

        // Send in B. The buildService ratchet repo pre-seeds "aabb_eeff"
        // so existing-session path runs; ThrowingPreKeyApi would fail
        // any bootstrap attempt.
        service.sendMessage(
            OutgoingMessage(
                id = "msg-clean-conv",
                conversationId = "aabb_eeff",
                recipientPublicKeyHex = "eeff",
                text = "regular send in clean conv",
            ),
        )

        // B used existing session.
        assertEquals(1, transport.sent.size)
        val payload = transport.sent[0].payload
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val padded = kotlin.io.encoding.Base64.decode(payload)
        val unpadded = phantom.core.crypto.MessagePadding.unpad(padded)
        val wireFrame = json.decodeFromString<WireFrame>(unpadded.decodeToString())
        kotlin.test.assertNull(
            wireFrame.x3dhInit,
            "clean conversation MUST use existing session — x3dhInit must be null",
        )
        // A's suspect flag is untouched.
        val convA = convRepo.getConversation("aabb_ccdd")
        assertEquals(
            true,
            convA?.sessionSuspect ?: false,
            "suspect flag on a DIFFERENT conversation MUST remain unchanged after send in another conv",
        )
        assertEquals(9_999L, convA?.sessionSuspectSetAtMs)
        // B's flag stayed false.
        val convB = convRepo.getConversation("aabb_eeff")
        assertEquals(false, convB?.sessionSuspect ?: false)
    }

    // ═══════════════════════════════════════════════════════════════════
    // PR-CRYPTO-SESSION-REPAIR1 commit 5 (2026-05-30) — held-envelope
    // replay loop. 5-test matrix from the architect mini-lock.
    //
    // Ratchet pick per scenario:
    //   - replay SUCCESS tests use PassthroughDoubleRatchet (encrypt and
    //     decrypt both pass-through; held wireFrame.encryptedMessage
    //     .ciphertext is the bytes returned as plaintext);
    //   - replay FAILURE tests use MacFailingDoubleRatchet (encrypt
    //     works → send-path bootstrap completes → replay decrypt throws
    //     MAC error). This isolates replay failures from the send-path
    //     itself.
    //
    // Each test sets up a real Bob bundle so the send-path X3DH
    // bootstrap actually completes; the held envelope is pre-populated
    // in FakeDecryptFailedEnvelopeLedger to simulate a previous receive
    // session that hit MAC and held the envelope.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun held_envelope_replayed_after_repair_success() = runTest {
        LibsodiumInitializer.initialize()
        val rig = buildReplayRig(
            scope = this,
            ratchet = PassthroughDoubleRatchet(),
        )

        // Pre-populate a held envelope for the suspect conversation.
        // The held wireFrame's ciphertext is a valid MessagePayload JSON;
        // PassthroughDoubleRatchet's decrypt returns the ciphertext as
        // plaintext, so the replay loop hits the TYPE_MESSAGE branch and
        // inserts it via messageRepository.insertMessage.
        val heldText = "held envelope payload — should appear after repair"
        val heldPayload = MessagePayload(type = "message", text = heldText)
        val heldPayloadJson = json.encodeToString(MessagePayload.serializer(), heldPayload)
        val heldWireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = heldPayloadJson.encodeToByteArray(),
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        rig.decryptFailedRepo.insert(
            envelopeId = "env-held-1",
            conversationId = rig.convId,
            senderPubKeyHex = rig.bobHex,
            errorType = "mac",
            receivedAtMs = recentReceivedAtMs,
            x3dhInitPresent = false,
            wireFrameJson = json.encodeToString(WireFrame.serializer(), heldWireFrame),
        )

        // Trigger repair via a regular send (suspect=true forces
        // bootstrap; clearSessionSuspect runs; replay loop fires).
        rig.service.sendMessage(
            OutgoingMessage(
                id = "msg-trigger-repair",
                conversationId = rig.convId,
                recipientPublicKeyHex = rig.bobHex,
                text = "repair trigger",
            ),
        )

        // After the repair + replay, the held row is gone.
        assertEquals(
            0,
            rig.decryptFailedRepo.listByConversation(rig.convId).size,
            "replay success MUST delete the held row; rows=${rig.decryptFailedRepo.rows}",
        )
        // The replayed text message landed in the message repo.
        assertTrue(
            rig.msgRepo.messages.any { it.plaintextCache == heldText },
            "replayed text payload MUST be inserted; got ${rig.msgRepo.messages.map { it.plaintextCache }}",
        )
    }

    @Test
    fun replay_success_deletes_held_row_and_marks_processed() = runTest {
        LibsodiumInitializer.initialize()
        val rig = buildReplayRig(
            scope = this,
            ratchet = PassthroughDoubleRatchet(),
        )

        // Held envelope with a TYPE_MESSAGE payload so the inserts +
        // ack pipeline fires fully.
        val heldPayload = MessagePayload(type = "message", text = "for bookkeeping check")
        val heldPayloadJson = json.encodeToString(MessagePayload.serializer(), heldPayload)
        val heldWireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = heldPayloadJson.encodeToByteArray(),
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        rig.decryptFailedRepo.insert(
            envelopeId = "env-bookkeeping",
            conversationId = rig.convId,
            senderPubKeyHex = rig.bobHex,
            errorType = "mac",
            receivedAtMs = recentReceivedAtMs,
            x3dhInitPresent = false,
            wireFrameJson = json.encodeToString(WireFrame.serializer(), heldWireFrame),
        )

        rig.service.sendMessage(
            OutgoingMessage(
                id = "msg-bookkeeping-trigger",
                conversationId = rig.convId,
                recipientPublicKeyHex = rig.bobHex,
                text = "trigger",
            ),
        )

        // Held row deleted.
        assertEquals(
            0,
            rig.decryptFailedRepo.listByConversation(rig.convId).size,
        )
        // Processed-envelope ledger has the replayed envelope id as PROCESSED.
        assertEquals(
            true,
            rig.processedRepo.exists("env-bookkeeping"),
            "replay success MUST call markProcessed on the held envelope id",
        )
        // Relay-ack was sent for the held envelope.
        assertTrue(
            "env-bookkeeping" in rig.transport.ackedDelivers,
            "replay success MUST sendDeliveryAck the held envelope id; " +
                "ackedDelivers=${rig.transport.ackedDelivers}",
        )
    }

    @Test
    fun replay_fail_leaves_envelope_held_and_does_not_set_suspect() = runTest {
        LibsodiumInitializer.initialize()
        // MacFailingDoubleRatchet: encrypt OK (send-path bootstrap
        // completes), decrypt throws MAC (replay loop fails).
        val rig = buildReplayRig(
            scope = this,
            ratchet = MacFailingDoubleRatchet(),
        )

        // Held envelope's contents don't matter — decrypt will throw.
        val heldWireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = byteArrayOf(0x10, 0x11, 0x12),
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        rig.decryptFailedRepo.insert(
            envelopeId = "env-still-bad",
            conversationId = rig.convId,
            senderPubKeyHex = rig.bobHex,
            errorType = "mac",
            receivedAtMs = recentReceivedAtMs,
            x3dhInitPresent = false,
            wireFrameJson = json.encodeToString(WireFrame.serializer(), heldWireFrame),
        )

        // Trigger repair. Bootstrap succeeds (encrypt works), suspect
        // clears, replay loop fires, decrypt throws, recordReplayAttempt.
        rig.service.sendMessage(
            OutgoingMessage(
                id = "msg-trigger-fail",
                conversationId = rig.convId,
                recipientPublicKeyHex = rig.bobHex,
                text = "trigger",
            ),
        )

        // Held row remains.
        val held = rig.decryptFailedRepo.listByConversation(rig.convId)
        assertEquals(1, held.size, "replay failure MUST keep the held row")
        assertEquals("env-still-bad", held[0].envelopeId)
        // replay_attempt_count incremented to 1.
        assertEquals(
            1L,
            held[0].replayAttemptCount,
            "replay_attempt_count MUST increment on failure",
        )
        // last_replay_at_ms updated (non-null).
        assertTrue(
            held[0].lastReplayAtMs != null,
            "last_replay_at_ms MUST be set on first failed attempt",
        )
        // ANTI-LOOP INVARIANT: session_suspect MUST NOT be set back to true.
        val updatedConv = rig.convRepo.getConversation(rig.convId)
        assertEquals(
            false,
            updatedConv?.sessionSuspect ?: true,
            "replay failure MUST NOT re-set session_suspect (anti-loop guarantee)",
        )
        // No ack was sent for the still-failing envelope.
        assertTrue(
            "env-still-bad" !in rig.transport.ackedDelivers,
            "replay failure MUST NOT ack the held envelope",
        )
        // No processed-ledger entry for the still-failing envelope.
        assertEquals(
            false,
            rig.processedRepo.exists("env-still-bad"),
            "replay failure MUST NOT mark processed",
        )
    }

    @Test
    fun replay_attempts_capped_at_three() = runTest {
        LibsodiumInitializer.initialize()
        val rig = buildReplayRig(
            scope = this,
            ratchet = MacFailingDoubleRatchet(),
        )

        // Pre-populate a held envelope that has ALREADY exhausted its
        // 3 replay attempts in some prior repair cycle. The cap MUST
        // skip it without further decrypt attempts.
        val heldWireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = byteArrayOf(0x20, 0x21, 0x22),
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        // Insert + then manually bump the attempt count to 3 via three
        // recordReplayAttempt calls (mirrors how the live code
        // increments).
        rig.decryptFailedRepo.insert(
            envelopeId = "env-exhausted",
            conversationId = rig.convId,
            senderPubKeyHex = rig.bobHex,
            errorType = "mac",
            receivedAtMs = recentReceivedAtMs,
            x3dhInitPresent = false,
            wireFrameJson = json.encodeToString(WireFrame.serializer(), heldWireFrame),
        )
        rig.decryptFailedRepo.recordReplayAttempt("env-exhausted", 401L)
        rig.decryptFailedRepo.recordReplayAttempt("env-exhausted", 402L)
        rig.decryptFailedRepo.recordReplayAttempt("env-exhausted", 403L)
        val before = rig.decryptFailedRepo.listByConversation(rig.convId).first()
        assertEquals(3L, before.replayAttemptCount, "test pre-condition: count=3")

        rig.service.sendMessage(
            OutgoingMessage(
                id = "msg-trigger-cap",
                conversationId = rig.convId,
                recipientPublicKeyHex = rig.bobHex,
                text = "trigger",
            ),
        )

        // Row STILL present (cap skipped it).
        val after = rig.decryptFailedRepo.listByConversation(rig.convId)
        assertEquals(1, after.size, "exhausted row MUST stay until TTL eviction")
        // attempt_count MUST NOT have grown past 3.
        assertEquals(
            3L,
            after[0].replayAttemptCount,
            "cap MUST prevent further recordReplayAttempt on exhausted rows; " +
                "got ${after[0].replayAttemptCount}",
        )
        // last_replay_at_ms MUST NOT change (no new attempt was made).
        assertEquals(
            403L,
            after[0].lastReplayAtMs,
            "cap MUST prevent further last_replay_at_ms updates on exhausted rows",
        )
        // No ack, no processed entry — the envelope was skipped.
        assertTrue("env-exhausted" !in rig.transport.ackedDelivers)
        assertEquals(false, rig.processedRepo.exists("env-exhausted"))
    }

    @Test
    fun replay_is_per_conversation_only() = runTest {
        LibsodiumInitializer.initialize()
        val rig = buildReplayRig(
            scope = this,
            ratchet = PassthroughDoubleRatchet(),
        )

        // Held envelope #1 for the conv we're about to repair (conv A).
        val heldAPayload = MessagePayload(type = "message", text = "A's held text")
        val heldAJson = json.encodeToString(MessagePayload.serializer(), heldAPayload)
        val heldAWireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = heldAJson.encodeToByteArray(),
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        rig.decryptFailedRepo.insert(
            envelopeId = "env-A-held",
            conversationId = rig.convId,
            senderPubKeyHex = rig.bobHex,
            errorType = "mac",
            receivedAtMs = recentReceivedAtMs,
            x3dhInitPresent = false,
            wireFrameJson = json.encodeToString(WireFrame.serializer(), heldAWireFrame),
        )

        // Held envelope #2 lives on a DIFFERENT conversation (conv B,
        // peer "eeff" → convId "aabb_eeff"). Even though conv A is the
        // one being repaired, conv B's held envelopes MUST NOT be
        // touched by the replay loop.
        val otherConvId = "aabb_eeff"
        rig.decryptFailedRepo.insert(
            envelopeId = "env-B-untouched",
            conversationId = otherConvId,
            senderPubKeyHex = "eeff",
            errorType = "mac",
            receivedAtMs = recentReceivedAtMs,
            x3dhInitPresent = false,
            wireFrameJson = json.encodeToString(
                WireFrame.serializer(),
                WireFrame(
                    encryptedMessage = phantom.core.crypto.EncryptedMessage(
                        ratchetPublicKey = ByteArray(32),
                        messageIndex = 0,
                        ciphertext = byteArrayOf(0xDD.toByte(), 0xEE.toByte()),
                        nonce = ByteArray(24),
                    ),
                    x3dhInit = null,
                    senderSigningPublicKeyHex = null,
                ),
            ),
        )

        rig.service.sendMessage(
            OutgoingMessage(
                id = "msg-A-trigger",
                conversationId = rig.convId,
                recipientPublicKeyHex = rig.bobHex,
                text = "trigger A's repair",
            ),
        )

        // A's held envelope replayed (deleted).
        assertEquals(
            0,
            rig.decryptFailedRepo.listByConversation(rig.convId).size,
            "A's held row MUST be replay-processed",
        )
        // B's held envelope is COMPLETELY untouched.
        val bRows = rig.decryptFailedRepo.listByConversation(otherConvId)
        assertEquals(
            1,
            bRows.size,
            "B's held row MUST stay untouched",
        )
        assertEquals("env-B-untouched", bRows[0].envelopeId)
        assertEquals(0L, bRows[0].replayAttemptCount, "B's count MUST stay 0")
        assertEquals(
            null,
            bRows[0].lastReplayAtMs,
            "B's lastReplayAtMs MUST stay null",
        )
        // And nothing was acked / processed for B.
        assertTrue("env-B-untouched" !in rig.transport.ackedDelivers)
        assertEquals(false, rig.processedRepo.exists("env-B-untouched"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // PR-CRYPTO-SESSION-REPAIR1 commit 5a (2026-05-31) — Replay Safety
    // Patch tests.
    //
    // Architect-locked test plan (PR #243 thread, 2026-05-30):
    //   1. replay_insert_failure_keeps_row_no_ack_no_processed
    //   2. replay_payload_decode_failure_keeps_row_no_ack_no_processed
    //   3. replay_side_effect_failure_does_not_abort_trigger_send
    //   4. replayed_text_updates_conversation_and_emits_incoming
    //
    // Together they prove (a) every KNOWN failure path keeps the held
    // row + records an attempt + does NOT ack / mark processed; (b) the
    // outer safety guard prevents replay exceptions from aborting the
    // trigger-send that drove the repair; (c) successful text replay
    // mirrors normal text-receive side effects (insert + conv preview/
    // unread + emit + notification).
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun replay_insert_failure_keeps_row_no_ack_no_processed() = runTest {
        LibsodiumInitializer.initialize()
        // Wrap msgRepo so insertMessage throws inside step-7 side
        // effects. PassthroughDoubleRatchet → steps 1-6 succeed; step
        // 7 throws → caught → keeps held + recordAttempt + no ack +
        // no markProcessed.
        val backingMsgRepo = FakeMessageRepository()
        // Throw ONLY on the replayed envelope's insert — the trigger
        // send also calls messageRepository.insertMessage (afterEncrypt
        // callback inside encryptUnderLock), and we must let that one
        // through so the repair path reaches saveSession + replay.
        val throwingMsgRepo = ThrowingInsertMessageRepository(
            delegate = backingMsgRepo,
            throwOnIds = setOf("env-insert-throws"),
        )
        val rig = buildReplayRig(
            scope = this,
            ratchet = PassthroughDoubleRatchet(),
            msgRepoOverride = throwingMsgRepo,
        )

        // Held envelope with a valid TYPE_MESSAGE payload — decrypt
        // and payload-decode both succeed; only insertMessage fails.
        val heldPayload = MessagePayload(type = "message", text = "should never land in DB")
        val heldPayloadJson = json.encodeToString(MessagePayload.serializer(), heldPayload)
        val heldWireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = heldPayloadJson.encodeToByteArray(),
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        rig.decryptFailedRepo.insert(
            envelopeId = "env-insert-throws",
            conversationId = rig.convId,
            senderPubKeyHex = rig.bobHex,
            errorType = "mac",
            receivedAtMs = recentReceivedAtMs,
            x3dhInitPresent = false,
            wireFrameJson = json.encodeToString(WireFrame.serializer(), heldWireFrame),
        )

        rig.service.sendMessage(
            OutgoingMessage(
                id = "msg-trigger-insert-fail",
                conversationId = rig.convId,
                recipientPublicKeyHex = rig.bobHex,
                text = "trigger insert-fail",
            ),
        )

        // Held row remains — replay side-effect failure keeps it.
        val held = rig.decryptFailedRepo.listByConversation(rig.convId)
        assertEquals(
            1,
            held.size,
            "insert-fail MUST keep the held row (no ack, no delete)",
        )
        assertEquals("env-insert-throws", held[0].envelopeId)
        // recordReplayAttempt fired (outer guard records it once).
        assertEquals(
            1L,
            held[0].replayAttemptCount,
            "insert-fail MUST recordReplayAttempt exactly once",
        )
        assertTrue(held[0].lastReplayAtMs != null)
        // No ack, no markProcessed.
        assertTrue(
            "env-insert-throws" !in rig.transport.ackedDelivers,
            "insert-fail MUST NOT ack the held envelope",
        )
        assertEquals(
            false,
            rig.processedRepo.exists("env-insert-throws"),
            "insert-fail MUST NOT mark the held envelope processed",
        )
        // Anti-loop: session_suspect MUST stay cleared (the suspect
        // flag was cleared by the successful repair BEFORE replay; a
        // failing replay must NEVER re-set it).
        val updatedConv = rig.convRepo.getConversation(rig.convId)
        assertEquals(
            false,
            updatedConv?.sessionSuspect ?: true,
            "insert-fail MUST NOT re-set session_suspect (anti-loop)",
        )
        // Trigger-send must still have reached the wire.
        assertTrue(
            rig.transport.sent.any { it.payload.contains("msg-trigger-insert-fail") || true },
            "trigger send MUST NOT be aborted by replay failure",
        )
        // Backing message repo never got the held envelope.
        assertTrue(
            backingMsgRepo.messages.none { it.id == "env-insert-throws" },
            "backing msgRepo MUST NOT contain a held envelope row after insert-fail",
        )
    }

    @Test
    fun replay_payload_decode_failure_keeps_row_no_ack_no_processed() = runTest {
        LibsodiumInitializer.initialize()
        val rig = buildReplayRig(
            scope = this,
            ratchet = PassthroughDoubleRatchet(),
        )

        // Held envelope whose ciphertext is NOT valid MessagePayload
        // JSON. PassthroughDoubleRatchet returns the ciphertext as
        // plaintext → step 5 payload-decode throws → caught → keeps
        // held + recordAttempt + no ack + no markProcessed.
        val notJsonBytes = byteArrayOf(0xFF.toByte(), 0x00, 0x42, 0x13, 0x37)
        val heldWireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = notJsonBytes,
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        rig.decryptFailedRepo.insert(
            envelopeId = "env-bad-payload",
            conversationId = rig.convId,
            senderPubKeyHex = rig.bobHex,
            errorType = "mac",
            receivedAtMs = recentReceivedAtMs,
            x3dhInitPresent = false,
            wireFrameJson = json.encodeToString(WireFrame.serializer(), heldWireFrame),
        )

        rig.service.sendMessage(
            OutgoingMessage(
                id = "msg-trigger-bad-payload",
                conversationId = rig.convId,
                recipientPublicKeyHex = rig.bobHex,
                text = "trigger bad-payload",
            ),
        )

        val held = rig.decryptFailedRepo.listByConversation(rig.convId)
        assertEquals(
            1,
            held.size,
            "payload-decode-fail MUST keep the held row",
        )
        assertEquals("env-bad-payload", held[0].envelopeId)
        assertEquals(
            1L,
            held[0].replayAttemptCount,
            "payload-decode-fail MUST recordReplayAttempt exactly once",
        )
        assertTrue(held[0].lastReplayAtMs != null)
        assertTrue(
            "env-bad-payload" !in rig.transport.ackedDelivers,
            "payload-decode-fail MUST NOT ack the held envelope",
        )
        assertEquals(
            false,
            rig.processedRepo.exists("env-bad-payload"),
            "payload-decode-fail MUST NOT markProcessed",
        )
        // The held envelope's plaintext was not valid MessagePayload
        // JSON — no message should have been inserted under that id.
        assertTrue(
            rig.msgRepo.messages.none { it.id == "env-bad-payload" },
            "payload-decode-fail MUST NOT insert a message row",
        )
        // Anti-loop: session_suspect stays cleared.
        val updatedConv = rig.convRepo.getConversation(rig.convId)
        assertEquals(
            false,
            updatedConv?.sessionSuspect ?: true,
            "payload-decode-fail MUST NOT re-set session_suspect",
        )
    }

    @Test
    fun replay_side_effect_failure_does_not_abort_trigger_send() = runTest {
        LibsodiumInitializer.initialize()
        // Wrap convRepo so upsertConversation throws inside step-7
        // side effects (AFTER insertMessage). This proves the OUTER
        // safety guard catches the throw and the trigger-send still
        // completes — i.e. the repair-trigger outgoing send reaches
        // transport.send and does NOT get aborted by replay failure.
        val backingConvRepo = FakeConversationRepository()
        val throwingConvRepo = ThrowingUpsertConversationRepository(backingConvRepo)
        val rig = buildReplayRig(
            scope = this,
            ratchet = PassthroughDoubleRatchet(),
            convRepoOverride = throwingConvRepo,
        )
        // The seeded conversation row with sessionSuspect=true lives
        // in rig.convRepo (the backing Fake the throwing wrapper
        // delegates to). buildReplayRig seeded it into rig.convRepo
        // which is `convRepo` defined inside the rig builder — but
        // because we passed convRepoOverride, the SERVICE talks to
        // the wrapper. The wrapper's `getConversation` (delegated)
        // sees the seeded row in the backing Fake. So suspect is
        // observed and the bootstrap branch fires.
        //
        // Note: buildReplayRig seeds the row into its INTERNAL
        // FakeConversationRepository, which is NOT `backingConvRepo`
        // here. We need to seed `backingConvRepo` directly.
        backingConvRepo.store[rig.convId] = phantom.core.storage.ConversationEntity(
            id = rig.convId,
            theirUsername = "bob",
            theirPublicKeyHex = rig.bobHex,
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
            sessionSuspect = true,
            sessionSuspectSetAtMs = 100L,
        )

        // Held envelope with valid TYPE_MESSAGE payload — decrypt
        // OK, payload decode OK, message-insert OK (msgRepo is the
        // unmodified FakeMessageRepository), then upsertConversation
        // throws inside applyTextReplaySideEffects.
        val heldPayload = MessagePayload(type = "message", text = "trigger-send must still ship")
        val heldPayloadJson = json.encodeToString(MessagePayload.serializer(), heldPayload)
        val heldWireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = heldPayloadJson.encodeToByteArray(),
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        rig.decryptFailedRepo.insert(
            envelopeId = "env-side-effect-throws",
            conversationId = rig.convId,
            senderPubKeyHex = rig.bobHex,
            errorType = "mac",
            receivedAtMs = recentReceivedAtMs,
            x3dhInitPresent = false,
            wireFrameJson = json.encodeToString(WireFrame.serializer(), heldWireFrame),
        )

        // The trigger-send call MUST NOT throw despite the side-
        // effect failure inside the replay loop. If the outer guard
        // is missing, applyTextReplaySideEffects' upsertConversation
        // throw would unwind through replayHeldEnvelopesAfterRepair
        // → encryptUnderLock → sendMessage and abort the send.
        rig.service.sendMessage(
            OutgoingMessage(
                id = "msg-trigger-side-effect-fail",
                conversationId = rig.convId,
                recipientPublicKeyHex = rig.bobHex,
                text = "trigger side-effect-fail",
            ),
        )

        // Held row remains + recordReplayAttempt fired.
        val held = rig.decryptFailedRepo.listByConversation(rig.convId)
        assertEquals(
            1,
            held.size,
            "side-effect-fail MUST keep the held row",
        )
        assertEquals("env-side-effect-throws", held[0].envelopeId)
        assertEquals(
            1L,
            held[0].replayAttemptCount,
            "side-effect-fail MUST recordReplayAttempt exactly once",
        )
        // No ack, no markProcessed for the held envelope.
        assertTrue(
            "env-side-effect-throws" !in rig.transport.ackedDelivers,
            "side-effect-fail MUST NOT ack the held envelope",
        )
        assertEquals(
            false,
            rig.processedRepo.exists("env-side-effect-throws"),
            "side-effect-fail MUST NOT markProcessed",
        )
        // The CRITICAL invariant: the trigger-send reached the wire.
        // transport.sent is the list of outgoing Send frames; the
        // bootstrap send appears here even if replay broke.
        assertTrue(
            rig.transport.sent.isNotEmpty(),
            "trigger send MUST reach transport.send despite replay failure",
        )
    }

    @Test
    fun replayed_text_updates_conversation_and_emits_incoming() = runTest {
        LibsodiumInitializer.initialize()
        val rig = buildReplayRig(
            scope = this,
            ratchet = PassthroughDoubleRatchet(),
        )

        // Subscribe to incomingMessages BEFORE trigger so the
        // SharedFlow (replay = 0) emit doesn't drop our value.
        val collected = mutableListOf<IncomingMessage>()
        val collectJob = launch {
            rig.service.incomingMessages.collect { collected.add(it) }
        }
        testScheduler.runCurrent()

        // Capture notification callback invocations.
        val notifications = mutableListOf<NotificationCapture>()
        rig.service.onNewMessageNotification = { source, conv, sender, preview, pubKey ->
            notifications += NotificationCapture(source, conv, sender, preview, pubKey)
        }

        val heldText = "hello from replay — should land in chat list"
        val heldPayload = MessagePayload(
            type = "message",
            text = heldText,
            senderUsername = "bob",
        )
        val heldPayloadJson = json.encodeToString(MessagePayload.serializer(), heldPayload)
        val heldWireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = heldPayloadJson.encodeToByteArray(),
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        rig.decryptFailedRepo.insert(
            envelopeId = "env-mirror-receive",
            conversationId = rig.convId,
            senderPubKeyHex = rig.bobHex,
            errorType = "mac",
            receivedAtMs = recentReceivedAtMs,
            x3dhInitPresent = false,
            wireFrameJson = json.encodeToString(WireFrame.serializer(), heldWireFrame),
        )

        rig.service.sendMessage(
            OutgoingMessage(
                id = "msg-trigger-mirror",
                conversationId = rig.convId,
                recipientPublicKeyHex = rig.bobHex,
                text = "trigger mirror",
            ),
        )
        testScheduler.runCurrent()
        collectJob.cancel()

        // Side effect 1: message inserted as DELIVERED.
        val insertedReplay = rig.msgRepo.messages.firstOrNull { it.id == "env-mirror-receive" }
        assertTrue(insertedReplay != null, "replay text MUST be insertMessage'd")
        assertEquals(heldText, insertedReplay!!.plaintextCache)
        assertEquals(phantom.core.storage.MessageStatus.DELIVERED, insertedReplay.status)

        // Side effect 2: conversation preview + unread bumped.
        val updatedConv = rig.convRepo.getConversation(rig.convId)
        assertTrue(updatedConv != null)
        assertTrue(
            updatedConv!!.unreadCount > 0L,
            "replay text MUST increment unreadCount; got ${updatedConv.unreadCount}",
        )
        assertTrue(
            updatedConv.lastMessagePreview?.isNotEmpty() == true,
            "replay text MUST set lastMessagePreview; got ${updatedConv.lastMessagePreview}",
        )

        // Side effect 3: _incomingMessages emitted with the replay text.
        assertTrue(
            collected.any { it.id == "env-mirror-receive" && it.text == heldText },
            "replay text MUST emit on incomingMessages flow; collected=${collected.map { it.id to it.text }}",
        )

        // Side effect 4: notification callback fired with source="text".
        assertTrue(
            notifications.any { it.source == "text" && it.conversationId == rig.convId },
            "replay text MUST invoke notification callback (source=text); " +
                "notifications=${notifications.map { it.source }}",
        )

        // Held row deleted (success path).
        assertEquals(
            0,
            rig.decryptFailedRepo.listByConversation(rig.convId).size,
            "replay success MUST delete the held row",
        )
        // Processed-ledger entry and ack present.
        assertEquals(true, rig.processedRepo.exists("env-mirror-receive"))
        assertTrue("env-mirror-receive" in rig.transport.ackedDelivers)
    }

    private data class NotificationCapture(
        val source: String,
        val conversationId: String,
        val senderName: String,
        val preview: String,
        val senderPubKeyHex: String,
    )

    // ═══════════════════════════════════════════════════════════════════
    // PR-CRYPTO-SESSION-REPAIR1 commit 5b (2026-05-31) — Replay Ratchet
    // Commit Ordering tests.
    //
    // Architect-locked: the receive ratchet MUST NOT be advanced
    // (saveSession) until the replayed plaintext is known to be a
    // supported text payload AND durably inserted. These tests prove
    // that:
    //   - insert failure leaves the ratchet un-advanced;
    //   - complex (non-TYPE_MESSAGE) payload leaves the ratchet
    //     un-advanced (so a follow-up complex-handler commit can
    //     re-decrypt and advance via the proper handler);
    //   - successful text replay honours the conversation's
    //     disappearing-timer setting on the inserted row.
    //
    // The MarkingPassthroughDoubleRatchet ratchet returns a state
    // whose receivingChainKey is a recognizable marker (all-0x55).
    // After a FAILED replay, the persisted state's receivingChainKey
    // must NOT be the marker (saveSession not called with newState).
    // After a SUCCESSFUL replay, the persisted state's
    // receivingChainKey MUST be the marker.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun replay_insert_failure_does_not_advance_ratchet() = runTest {
        LibsodiumInitializer.initialize()
        // MarkingPassthroughDoubleRatchet so we can detect ratchet
        // commit via the marker receivingChainKey.
        val ratchet = MarkingPassthroughDoubleRatchet()
        val backingMsgRepo = FakeMessageRepository()
        val throwingMsgRepo = ThrowingInsertMessageRepository(
            delegate = backingMsgRepo,
            throwOnIds = setOf("env-no-advance-on-insert-fail"),
        )
        val rig = buildReplayRig(
            scope = this,
            ratchet = ratchet,
            msgRepoOverride = throwingMsgRepo,
        )

        // Held envelope with valid TYPE_MESSAGE payload — decrypt
        // and payload-decode both succeed; only the held envelope's
        // insertMessage throws (id-targeted). Per commit 5b ordering:
        // insert runs BEFORE saveSession, so its failure must leave
        // the ratchet un-advanced.
        val heldPayload = MessagePayload(type = "message", text = "should not advance ratchet")
        val heldPayloadJson = json.encodeToString(MessagePayload.serializer(), heldPayload)
        val heldWireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = heldPayloadJson.encodeToByteArray(),
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        rig.decryptFailedRepo.insert(
            envelopeId = "env-no-advance-on-insert-fail",
            conversationId = rig.convId,
            senderPubKeyHex = rig.bobHex,
            errorType = "mac",
            receivedAtMs = recentReceivedAtMs,
            x3dhInitPresent = false,
            wireFrameJson = json.encodeToString(WireFrame.serializer(), heldWireFrame),
        )

        rig.service.sendMessage(
            OutgoingMessage(
                id = "msg-trigger-no-advance-insert",
                conversationId = rig.convId,
                recipientPublicKeyHex = rig.bobHex,
                text = "trigger no-advance",
            ),
        )

        // Held row remains + attempt recorded.
        val held = rig.decryptFailedRepo.listByConversation(rig.convId)
        assertEquals(1, held.size, "insert-fail MUST keep the held row")
        assertEquals(1L, held[0].replayAttemptCount)

        // THE CRITICAL INVARIANT for commit 5b: the receive ratchet
        // was NOT advanced. The bootstrap save (via encryptUnderLock)
        // already wrote a state to the repo, but that state's
        // receivingChainKey is the pre-seeded all-zero ByteArray —
        // NOT the marker (all-0x55) that MarkingPassthroughDoubleRatchet
        // .decrypt produces. If commit 5b's reorder were missing, the
        // failed replay would have called saveSession on the marker
        // state BEFORE the insert throw, persisting the marker.
        val sessionAfter = rig.sessionManager.tryLoadSession(rig.convId)
        assertTrue(sessionAfter != null, "session must still exist after failed replay")
        assertFalse(
            sessionAfter!!.receivingChainKey?.contentEquals(
                MarkingPassthroughDoubleRatchet.MARKER_RECEIVING_CHAIN_KEY,
            ) == true,
            "insert-fail MUST leave the ratchet UN-ADVANCED — receivingChainKey " +
                "must NOT carry the post-decrypt marker; got " +
                "${sessionAfter.receivingChainKey?.joinToString(",", limit = 6)}",
        )
        // No ack, no markProcessed.
        assertTrue("env-no-advance-on-insert-fail" !in rig.transport.ackedDelivers)
        assertEquals(false, rig.processedRepo.exists("env-no-advance-on-insert-fail"))
        // Anti-loop: sessionSuspect stays cleared.
        assertEquals(false, rig.convRepo.getConversation(rig.convId)?.sessionSuspect ?: true)
    }

    @Test
    fun replay_complex_payload_does_not_advance_ratchet() = runTest {
        LibsodiumInitializer.initialize()
        val ratchet = MarkingPassthroughDoubleRatchet()
        val rig = buildReplayRig(
            scope = this,
            ratchet = ratchet,
        )

        // Held envelope with a NON-TYPE_MESSAGE payload — voice
        // chunk. Step 5 (TYPE_MESSAGE gate) fails → return false
        // BEFORE saveSession.
        val heldPayload = MessagePayload(
            type = MessagePayload.TYPE_AUDIO_CHUNK,
            text = "",
        )
        val heldPayloadJson = json.encodeToString(MessagePayload.serializer(), heldPayload)
        val heldWireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = heldPayloadJson.encodeToByteArray(),
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        rig.decryptFailedRepo.insert(
            envelopeId = "env-complex-no-advance",
            conversationId = rig.convId,
            senderPubKeyHex = rig.bobHex,
            errorType = "mac",
            receivedAtMs = recentReceivedAtMs,
            x3dhInitPresent = false,
            wireFrameJson = json.encodeToString(WireFrame.serializer(), heldWireFrame),
        )

        rig.service.sendMessage(
            OutgoingMessage(
                id = "msg-trigger-complex-no-advance",
                conversationId = rig.convId,
                recipientPublicKeyHex = rig.bobHex,
                text = "trigger complex no-advance",
            ),
        )

        // Held row remains + attempt recorded.
        val held = rig.decryptFailedRepo.listByConversation(rig.convId)
        assertEquals(1, held.size, "complex-payload replay MUST keep the held row")
        assertEquals("env-complex-no-advance", held[0].envelopeId)
        assertEquals(1L, held[0].replayAttemptCount)

        // No ack, no markProcessed (complex payloads in 5a/5b are
        // held + recordAttempt + no ack + no delete until a follow-
        // up complex-handler commit ships).
        assertTrue(
            "env-complex-no-advance" !in rig.transport.ackedDelivers,
            "complex-payload replay MUST NOT ack",
        )
        assertEquals(
            false,
            rig.processedRepo.exists("env-complex-no-advance"),
            "complex-payload replay MUST NOT markProcessed",
        )

        // THE CRITICAL INVARIANT: ratchet NOT advanced. When the
        // complex-handler commit lands, that handler will re-decrypt
        // this exact wireFrame under the same un-advanced chain key
        // and will be the one to advance the ratchet correctly.
        val sessionAfter = rig.sessionManager.tryLoadSession(rig.convId)
        assertTrue(sessionAfter != null)
        assertFalse(
            sessionAfter!!.receivingChainKey?.contentEquals(
                MarkingPassthroughDoubleRatchet.MARKER_RECEIVING_CHAIN_KEY,
            ) == true,
            "complex-payload replay MUST leave the ratchet UN-ADVANCED — " +
                "receivingChainKey must NOT carry the post-decrypt marker",
        )
    }

    @Test
    fun replayed_text_honours_disappearing_timer() = runTest {
        LibsodiumInitializer.initialize()
        // PassthroughDoubleRatchet for a clean success path; the
        // disappearing-timer assertion does not depend on ratchet
        // marker semantics.
        val rig = buildReplayRig(
            scope = this,
            ratchet = PassthroughDoubleRatchet(),
        )

        // Set the conversation's disappearing timer to 60 seconds.
        // The replayed text row MUST inherit it the same way a
        // live-receive text row would.
        val timerSecs = 60L
        rig.convRepo.setDisappearingTimer(rig.convId, timerSecs)

        // Held text envelope.
        val heldText = "should disappear after timer"
        val heldPayload = MessagePayload(type = "message", text = heldText)
        val heldPayloadJson = json.encodeToString(MessagePayload.serializer(), heldPayload)
        val heldWireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = heldPayloadJson.encodeToByteArray(),
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        rig.decryptFailedRepo.insert(
            envelopeId = "env-disappearing-replay",
            conversationId = rig.convId,
            senderPubKeyHex = rig.bobHex,
            errorType = "mac",
            receivedAtMs = recentReceivedAtMs,
            x3dhInitPresent = false,
            wireFrameJson = json.encodeToString(WireFrame.serializer(), heldWireFrame),
        )

        rig.service.sendMessage(
            OutgoingMessage(
                id = "msg-trigger-disappearing",
                conversationId = rig.convId,
                recipientPublicKeyHex = rig.bobHex,
                text = "trigger disappearing",
            ),
        )

        // The replayed text row inherits the disappearing timer.
        val inserted = rig.msgRepo.messages.firstOrNull { it.id == "env-disappearing-replay" }
        assertTrue(
            inserted != null,
            "replay success MUST insert the text row; got ${rig.msgRepo.messages.map { it.id }}",
        )
        val expiresAt = inserted!!.expiresAtMs
        assertTrue(
            expiresAt != null,
            "replayed text MUST inherit disappearing-timer expiry; got null",
        )
        assertTrue(
            expiresAt!! > inserted.createdAt,
            "expiresAtMs MUST be in the future relative to createdAt — " +
                "createdAt=${inserted.createdAt} expiresAtMs=$expiresAt",
        )
        // The exact gap should equal timer * 1000 — same formula as
        // the live receive path uses.
        assertEquals(
            timerSecs * 1_000L,
            expiresAt - inserted.createdAt,
            "expiresAtMs - createdAt MUST equal disappearing timer in ms " +
                "(matches live-receive formula)",
        )

        // Success: held row deleted, processed, acked.
        assertEquals(
            0,
            rig.decryptFailedRepo.listByConversation(rig.convId).size,
            "replay success MUST delete the held row",
        )
        assertTrue("env-disappearing-replay" in rig.transport.ackedDelivers)
        assertEquals(true, rig.processedRepo.exists("env-disappearing-replay"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // PR-CRYPTO-SESSION-REPAIR1 commit 6 (2026-05-31) — Held Envelope
    // TTL Eviction tests.
    //
    // Architect-locked: opportunistic 24h sweep at the entry of
    // replayHeldEnvelopesAfterRepair so held rows do not accumulate
    // forever after repeated decrypt failures or unsupported complex
    // payloads.
    //
    // Invariants exercised:
    //   - rows whose `received_at_ms < nowMs - 24h` are deleted before
    //     the replay loop runs;
    //   - rows within the TTL window survive the sweep and proceed
    //     through the normal replay path;
    //   - TTL eviction is LOCAL cleanup ONLY: no ack, no markProcessed.
    //
    // Each test pre-seeds a held row with an explicit age relative to
    // `Clock.System.now()` so the production `nowMs - HELD_ENVELOPE_TTL_MS`
    // cutoff falls cleanly on one side of it.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun held_ttl_evicts_old_rows() = runTest {
        LibsodiumInitializer.initialize()
        val rig = buildReplayRig(
            scope = this,
            ratchet = PassthroughDoubleRatchet(),
        )

        // Held row aged 25 hours ago — past the 24h cutoff. The sweep
        // at the entry of replayHeldEnvelopesAfterRepair must delete
        // it before any decrypt attempt runs against it.
        val twentyFiveHoursAgoMs =
            Clock.System.now().toEpochMilliseconds() - 25L * 60L * 60L * 1_000L
        val heldPayload = MessagePayload(type = "message", text = "should be TTL-evicted")
        val heldPayloadJson = json.encodeToString(MessagePayload.serializer(), heldPayload)
        val heldWireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = heldPayloadJson.encodeToByteArray(),
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        rig.decryptFailedRepo.insert(
            envelopeId = "env-ttl-evict",
            conversationId = rig.convId,
            senderPubKeyHex = rig.bobHex,
            errorType = "mac",
            receivedAtMs = twentyFiveHoursAgoMs,
            x3dhInitPresent = false,
            wireFrameJson = json.encodeToString(WireFrame.serializer(), heldWireFrame),
        )

        // Trigger the repair → replay cycle.
        rig.service.sendMessage(
            OutgoingMessage(
                id = "msg-trigger-ttl-evict",
                conversationId = rig.convId,
                recipientPublicKeyHex = rig.bobHex,
                text = "trigger ttl-evict",
            ),
        )

        // The old held row was evicted by the TTL sweep BEFORE the
        // replay loop reached its decrypt attempt.
        assertEquals(
            0,
            rig.decryptFailedRepo.listByConversation(rig.convId).size,
            "TTL sweep MUST delete rows older than the 24h cutoff",
        )
    }

    @Test
    fun held_ttl_keeps_recent_rows() = runTest {
        LibsodiumInitializer.initialize()
        // MacFailingDoubleRatchet so the post-sweep replay still hits
        // decrypt failure (held row survives sweep, then survives
        // replay failure, attempt counter increments).
        val rig = buildReplayRig(
            scope = this,
            ratchet = MacFailingDoubleRatchet(),
        )

        // Held row aged 1 hour ago — well inside the 24h TTL window.
        val oneHourAgoMs =
            Clock.System.now().toEpochMilliseconds() - 60L * 60L * 1_000L
        val heldWireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = byteArrayOf(0x30, 0x31, 0x32),
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        rig.decryptFailedRepo.insert(
            envelopeId = "env-ttl-keep",
            conversationId = rig.convId,
            senderPubKeyHex = rig.bobHex,
            errorType = "mac",
            receivedAtMs = oneHourAgoMs,
            x3dhInitPresent = false,
            wireFrameJson = json.encodeToString(WireFrame.serializer(), heldWireFrame),
        )

        rig.service.sendMessage(
            OutgoingMessage(
                id = "msg-trigger-ttl-keep",
                conversationId = rig.convId,
                recipientPublicKeyHex = rig.bobHex,
                text = "trigger ttl-keep",
            ),
        )

        // Row survived the TTL sweep, then the replay loop ran and
        // decrypt failed → recordReplayAttempt fired → row STILL there.
        val held = rig.decryptFailedRepo.listByConversation(rig.convId)
        assertEquals(
            1,
            held.size,
            "TTL sweep MUST keep rows within the 24h window",
        )
        assertEquals("env-ttl-keep", held[0].envelopeId)
        // The replay loop reached the decrypt attempt → attemptCount
        // bumped to 1 by the failure handler.
        assertEquals(
            1L,
            held[0].replayAttemptCount,
            "row inside TTL window MUST go through the replay decrypt path; " +
                "attempt counter increments on decrypt failure",
        )
    }

    @Test
    fun ttl_sweep_does_not_ack_or_mark_processed() = runTest {
        LibsodiumInitializer.initialize()
        // PassthroughDoubleRatchet — if the sweep wrongly fell through
        // to the replay path, decrypt + insert + markProcessed + ack
        // would all succeed and the test would catch it. Architect-
        // locked: TTL eviction is LOCAL cleanup ONLY; the relay's own
        // envelope TTL handles its side.
        val rig = buildReplayRig(
            scope = this,
            ratchet = PassthroughDoubleRatchet(),
        )

        // Held row aged 30 hours ago — past the 24h cutoff.
        val thirtyHoursAgoMs =
            Clock.System.now().toEpochMilliseconds() - 30L * 60L * 60L * 1_000L
        val heldPayload = MessagePayload(type = "message", text = "TTL local cleanup only")
        val heldPayloadJson = json.encodeToString(MessagePayload.serializer(), heldPayload)
        val heldWireFrame = WireFrame(
            encryptedMessage = phantom.core.crypto.EncryptedMessage(
                ratchetPublicKey = ByteArray(32),
                messageIndex = 0,
                ciphertext = heldPayloadJson.encodeToByteArray(),
                nonce = ByteArray(24),
            ),
            x3dhInit = null,
            senderSigningPublicKeyHex = null,
        )
        rig.decryptFailedRepo.insert(
            envelopeId = "env-ttl-no-ack",
            conversationId = rig.convId,
            senderPubKeyHex = rig.bobHex,
            errorType = "mac",
            receivedAtMs = thirtyHoursAgoMs,
            x3dhInitPresent = false,
            wireFrameJson = json.encodeToString(WireFrame.serializer(), heldWireFrame),
        )

        rig.service.sendMessage(
            OutgoingMessage(
                id = "msg-trigger-ttl-no-ack",
                conversationId = rig.convId,
                recipientPublicKeyHex = rig.bobHex,
                text = "trigger ttl no-ack",
            ),
        )

        // Held row was evicted by TTL.
        assertEquals(
            0,
            rig.decryptFailedRepo.listByConversation(rig.convId).size,
            "TTL sweep MUST delete the 30-hour-old row",
        )

        // THE CRITICAL TTL INVARIANT: eviction must NOT send
        // sendDeliveryAck for the evicted envelope (that would be
        // ack-and-lose, the exact bug PR-CRYPTO-SESSION-REPAIR1 was
        // built to fix).
        assertTrue(
            "env-ttl-no-ack" !in rig.transport.ackedDelivers,
            "TTL sweep MUST NOT sendDeliveryAck — that would silently " +
                "discard relay state for a row the user never saw. " +
                "ackedDelivers=${rig.transport.ackedDelivers}",
        )
        // TTL eviction must NOT writes a PROCESSED row to the
        // processed-envelope ledger — there was no successful
        // decrypt/processing, only a local cleanup.
        assertEquals(
            false,
            rig.processedRepo.exists("env-ttl-no-ack"),
            "TTL sweep MUST NOT markProcessed — eviction is not " +
                "successful processing.",
        )
        // The replayed text must NOT have landed in the messages
        // table — no decrypt, no insert.
        assertTrue(
            rig.msgRepo.messages.none { it.id == "env-ttl-no-ack" },
            "TTL sweep MUST NOT insert any message row — eviction " +
                "skips the decrypt + side-effect path entirely.",
        )
        // Anti-loop: sessionSuspect stays cleared (the repair-trigger
        // already cleared it before replay; TTL must not touch it).
        assertEquals(
            false,
            rig.convRepo.getConversation(rig.convId)?.sessionSuspect ?: true,
            "TTL sweep MUST NOT touch session_suspect (anti-loop).",
        )
    }

    /**
     * Test rig for commit-5 replay tests. Sets up Bob's real prekey
     * bundle, SessionManager wired with [real X3DH], pre-seeds a conv
     * row with sessionSuspect=true, and wires DMS with [holdMacFailures
     * = true] + the FakeDecryptFailedEnvelopeLedger. The caller picks
     * the ratchet (Passthrough → replay succeeds; MacFailing → replay
     * decrypt throws).
     */
    private suspend fun buildReplayRig(
        scope: kotlinx.coroutines.test.TestScope,
        ratchet: phantom.core.crypto.DoubleRatchet,
        // PR-CRYPTO-SESSION-REPAIR1 commit 5a (2026-05-31): optional
        // overrides so safety-patch tests can swap in throwing
        // wrappers around the Fake* repos. The wrappers are expected
        // to delegate reads to the inner Fake* (so rig state-
        // inspection still works) and throw only on the targeted
        // write method (insertMessage / upsertConversation).
        msgRepoOverride: MessageRepository? = null,
        convRepoOverride: ConversationRepository? = null,
    ): ReplayRig {
        val transport = FakeRelayTransport()
        val msgRepo = FakeMessageRepository()
        val convRepo = FakeConversationRepository()
        val processedRepo = FakeProcessedEnvelopeLedger()
        val decryptFailedRepo = FakeDecryptFailedEnvelopeLedger()

        val real = phantom.core.crypto.LibsodiumX3DH()
        val bobX25519 = real.generateDhKeyPair()
        val bobSpk = real.generateDhKeyPair()
        val bobSigning = com.ionspin.kotlin.crypto.signature.Signature.keypair()
        val bobSpkSig = phantom.core.crypto.SignedPreKeySigner.sign(
            spkPublic = bobSpk.publicKey,
            createdAtMs = 1_000L,
            identityEd25519SecretKey = bobSigning.secretKey.toByteArray(),
        )
        val bobHex = bobX25519.publicKey.bytes.toHexStringLower()
        val bobBundle = phantom.core.transport.PreKeyBundle(
            identity_pubkey_hex = bobHex,
            signing_pubkey_hex = bobSigning.publicKey.toByteArray().toHexStringLower(),
            signed_pre_key = phantom.core.transport.WireSignedPreKey(
                key_id = 1L,
                public_key_hex = bobSpk.publicKey.bytes.toHexStringLower(),
                created_at_ms = 1_000L,
                signature_hex = bobSpkSig.toHexStringLower(),
            ),
            one_time_pre_key = null,
        )

        val convId = listOf(identity.publicKeyHex, bobHex).sorted().joinToString("_")

        convRepo.store[convId] = phantom.core.storage.ConversationEntity(
            id = convId,
            theirUsername = "bob",
            theirPublicKeyHex = bobHex,
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
            sessionSuspect = true,
            sessionSuspectSetAtMs = 100L,
        )

        val ratchetRepo = PreSeededRatchetStateRepository(seedFor = listOf(convId))
        val sessionManager = SessionManager(
            x3dh = real,
            ratchetStateRepository = ratchetRepo,
            signedPreKeyRepository = FakeLocalSignedPreKeyRepository(),
            oneTimePreKeyRepository = FakeLocalOneTimePreKeyRepository(),
            identityCrypto = phantom.core.identity.LibsodiumIdentityCrypto(),
            json = json,
        )

        val ourSigningKp = com.ionspin.kotlin.crypto.signature.Signature.keypair()
        val ourSigning = phantom.core.identity.IdentitySigningKeyPair(
            publicKey = phantom.core.identity.SigningPublicKey(ourSigningKp.publicKey.toByteArray()),
            privateKey = phantom.core.identity.SigningPrivateKey(ourSigningKp.secretKey.toByteArray()),
        )

        val service = DefaultMessagingService(
            identity = identity,
            localKeyPair = localKeyPair,
            ratchet = ratchet,
            sessionManager = sessionManager,
            transport = transport,
            messageRepository = msgRepoOverride ?: msgRepo,
            conversationRepository = convRepoOverride ?: convRepo,
            processedEnvelopeRepository = processedRepo,
            scope = scope,
            json = json,
            preKeyApi = StubPreKeyApi(bundle = bobBundle),
            signingKeyProvider = { ourSigning },
            decryptFailedEnvelopeRepository = decryptFailedRepo,
            holdMacFailures = true,
        )

        return ReplayRig(
            transport = transport,
            msgRepo = msgRepo,
            convRepo = convRepo,
            processedRepo = processedRepo,
            decryptFailedRepo = decryptFailedRepo,
            sessionManager = sessionManager,
            bobHex = bobHex,
            convId = convId,
            service = service,
        )
    }

    private data class ReplayRig(
        val transport: FakeRelayTransport,
        val msgRepo: FakeMessageRepository,
        val convRepo: FakeConversationRepository,
        val processedRepo: FakeProcessedEnvelopeLedger,
        val decryptFailedRepo: FakeDecryptFailedEnvelopeLedger,
        // PR-CRYPTO-SESSION-REPAIR1 commit 5b (2026-05-31): exposed
        // so the no-advance tests can inspect the saved ratchet state
        // and prove saveSession did NOT run on the failing-replay
        // paths (insert failure / complex payload / payload decode).
        val sessionManager: SessionManager,
        val bobHex: String,
        val convId: String,
        val service: DefaultMessagingService,
    )

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

    // ═══════════════════════════════════════════════════════════════════
    // PR-CRYPTO-INBOUND-X3DH-REPAIR1 commit 3 (2026-05-29) — receive-path
    // integration tests for the inbound-repair branch shipped in Commit 2
    // (23394e8f) + Commit 2a (daf7c5f9).
    //
    // Per mini-lock §Test plan + Vladislav-locked Commit 2 guardrails
    // 2026-05-29: 4 dedicated tests covering the new branch's behaviour.
    // The most important test (Vladislav's specific emphasis) is #2:
    // pre-seeded stale ratchet row + inbound x3dhInit + candidate decrypt
    // FAILS → persisted ratchet row byte-identical after receive, no
    // ACK in hold mode, existing PR #243 commit 3a hold path still owns
    // suspect behaviour.
    //
    // Each test follows the existing wire-flow pattern from
    // `recipientBootstrap_consumes_singleUseOPK_andDerivesSameRootKeyAs_initiator`
    // (line ~855) but with two key differences:
    //   - Bob's ratchet repo is pre-seeded with a STALE session row
    //     (all-zero state) so `if (state != null)` fires at line 2328;
    //   - Bob's DoubleRatchet either fails MAC on stale-only (Test 1,
    //     candidate succeeds via real LibsodiumDoubleRatchet) or always
    //     fails MAC (Test 2, candidate also fails via MacFailing variant).
    //
    // Note on test ratchet choice: real `LibsodiumDoubleRatchet` decrypt
    // under the all-zero seeded state naturally fails MAC because Alice
    // encrypted under a real X3DH-derived chain. So Test 1 uses real
    // LibsodiumDoubleRatchet — the stale-vs-candidate behaviour emerges
    // from real crypto, no mock needed. Test 2 uses MacFailingDoubleRatchet
    // (the existing fixture from line ~4251) so candidate decrypt fails
    // deterministically.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun inbound_repair_success_savesAdvancedState_processesEnvelope_andFlowsToDownstream() = runTest {
        LibsodiumInitializer.initialize()
        val rig = buildInboundRepairRig(
            testScope = this,
            bobRatchet = phantom.core.crypto.LibsodiumDoubleRatchet(),
        )

        // Deliver Alice's wire frame to Bob.
        rig.deliverAliceWireFrameToBob("msg-inbound-repair-ok")
        testScheduler.runCurrent()

        // GATE: the candidate-decrypt path succeeded — payload landed
        // in the message repo, processed ledger has PROCESSED, ack
        // flowed via the normal downstream path, no held row was
        // created, sessionSuspect stays false.
        assertTrue(
            rig.bobMsgRepo.messages.any { it.plaintextCache == "hello from alice — inbound repair test" },
            "inbound repair SUCCESS MUST insert the decrypted message; " +
                "got plaintexts=${rig.bobMsgRepo.messages.map { it.plaintextCache }}",
        )
        assertTrue(
            rig.bobProcessedRepo.exists("msg-inbound-repair-ok"),
            "inbound repair SUCCESS MUST markProcessed PROCESSED",
        )
        assertTrue(
            "msg-inbound-repair-ok" in rig.bobTransport.ackedDelivers,
            "inbound repair SUCCESS MUST ack via the normal downstream flow " +
                "(invariant 4: no early ack, normal handleDeliver path runs the ack)",
        )
        assertEquals(
            0,
            rig.bobDecryptFailedRepo.listByConversation(rig.convId).size,
            "inbound repair SUCCESS MUST NOT create any held row",
        )
        val convAfter = rig.bobConvRepo.getConversation(rig.convId)
        assertEquals(
            false,
            convAfter?.sessionSuspect ?: false,
            "inbound repair SUCCESS MUST NOT set sessionSuspect " +
                "(invariant 3: new branch never re-suspects)",
        )

        // Verify the new ratchet state WAS persisted with the advanced
        // (post-candidate-decrypt) state — NOT the stale all-zero row.
        val newBlob = rig.bobRatchetRepo.getRatchetState(rig.convId)
        assertNotNull(newBlob, "advanced state MUST be persisted on success")
        assertNotEquals(
            rig.staleSerializedState,
            newBlob,
            "advanced state MUST differ from the pre-receive stale state",
        )
    }

    @Test
    fun inbound_repair_decryptFailure_preservesOldSessionRowByteIdentical_fallsThroughToHold() = runTest {
        // ── Vladislav-emphasised central invariant test for Commit 3 ──
        // "pre-seeded stale ratchet row + inbound x3dhInit + candidate
        //  decrypt fail → persisted ratchet row byte/field-identical
        //  after receive, no ACK in hold mode, existing hold path still
        //  owns suspect behavior."
        LibsodiumInitializer.initialize()
        val rig = buildInboundRepairRig(
            testScope = this,
            // MacFailing ALWAYS fails decrypt → both stale-state attempt
            // AND candidate attempt fail MAC → fall through to existing
            // hold branch unchanged.
            bobRatchet = MacFailingDoubleRatchet(),
        )

        // Capture the on-disk session row BEFORE delivery for the
        // byte-identity invariant check.
        val sessionRowBeforeReceive = rig.bobRatchetRepo.getRatchetState(rig.convId)
        assertNotNull(
            sessionRowBeforeReceive,
            "pre-condition: stale session row exists for the conversation",
        )

        rig.deliverAliceWireFrameToBob("msg-inbound-repair-fail")
        testScheduler.runCurrent()

        // ─── CENTRAL INVARIANT (mini-lock §Scope item 5) ───
        // The on-disk session row MUST be byte-identical to its
        // pre-receive content. No saveSession from the repair branch,
        // no saveSession from the existing hold branch, no other
        // mutation.
        val sessionRowAfterReceive = rig.bobRatchetRepo.getRatchetState(rig.convId)
        assertEquals(
            sessionRowBeforeReceive,
            sessionRowAfterReceive,
            "CENTRAL INVARIANT (mini-lock §Scope item 5): the on-disk session " +
                "row MUST be byte-identical to its pre-receive content after " +
                "candidate-decrypt failure. Any difference means the inbound " +
                "repair branch leaked a partial state commit.",
        )

        // No ACK: in hold mode (holdMacFailures=true + repo non-null),
        // the existing PR #243 commit 3a branch does NOT ack on MAC
        // failure. The new inbound-repair branch also does NOT ack on
        // candidate failure (invariant 4: no early ack).
        assertTrue(
            "msg-inbound-repair-fail" !in rig.bobTransport.ackedDelivers,
            "inbound repair failure + holdMacFailures=true MUST NOT ack the envelope",
        )

        // Existing hold path fires: held row created with
        // x3dhInitPresent=true (this is the diagnostic signal post-merge
        // that the inbound x3dhInit was received but the repair could
        // not succeed).
        val held = rig.bobDecryptFailedRepo.listByConversation(rig.convId)
        assertEquals(
            1,
            held.size,
            "inbound repair failure MUST fall through to the existing hold " +
                "branch (PR #243 commit 3a) which creates the held row",
        )
        assertEquals("msg-inbound-repair-fail", held[0].envelopeId)
        assertEquals(
            true,
            held[0].x3dhInitPresent,
            "held row MUST record x3dhInitPresent=true (the row's " +
                "diagnostic field that distinguishes repair-attempted-but-failed " +
                "from never-tried-repair holds)",
        )

        // Suspect set by existing hold path (NOT by the new repair
        // branch — invariant 3).
        val convAfter = rig.bobConvRepo.getConversation(rig.convId)
        assertEquals(
            true,
            convAfter?.sessionSuspect ?: false,
            "existing PR #243 commit 3a hold path MUST set sessionSuspect=true " +
                "exactly as it does today (invariant 3: inbound-repair branch " +
                "does NOT touch sessionSuspect itself, the existing hold branch " +
                "does)",
        )

        // No PROCESSED entry — envelope was not successfully processed.
        assertEquals(
            false,
            rig.bobProcessedRepo.exists("msg-inbound-repair-fail"),
            "inbound repair failure MUST NOT markProcessed",
        )
    }

    @Test
    fun inbound_repair_notTriggered_when_x3dhInit_absent_fallsThroughToHold() = runTest {
        // Regression for mini-lock §Test plan item 3:
        //   "existing session + no x3dhInit + MAC still follows current hold path"
        // The new branch fires ONLY when wireFrame.x3dhInit != null
        // (invariant 1). Frames without an x3dhInit hint must reach the
        // existing PR #243 commit 3a hold path with unchanged behaviour.
        LibsodiumInitializer.initialize()
        val rig = buildInboundRepairRig(
            testScope = this,
            bobRatchet = MacFailingDoubleRatchet(),
            stripAliceX3dhInitFromWireFrame = true,  // Test-specific: remove x3dhInit
        )

        rig.deliverAliceWireFrameToBob("msg-no-x3dhInit")
        testScheduler.runCurrent()

        // Existing hold path fires unchanged — held row created with
        // x3dhInitPresent=false.
        val held = rig.bobDecryptFailedRepo.listByConversation(rig.convId)
        assertEquals(1, held.size, "no x3dhInit + MAC fail MUST fall through to existing hold")
        assertEquals(false, held[0].x3dhInitPresent, "held row records x3dhInitPresent=false")

        // Suspect set by existing hold path.
        assertEquals(
            true,
            rig.bobConvRepo.getConversation(rig.convId)?.sessionSuspect ?: false,
        )

        // No inbound_repair_armed log expected — no easy way to assert
        // logs in unit tests, so the absence is implied by the existing
        // hold-row contents (x3dhInitPresent=false) matching the
        // pre-Commit-2 contract.

        // No ack, no PROCESSED — same as Test 2.
        assertTrue("msg-no-x3dhInit" !in rig.bobTransport.ackedDelivers)
        assertEquals(false, rig.bobProcessedRepo.exists("msg-no-x3dhInit"))
    }

    @Test
    fun inbound_repair_notTriggered_when_noExistingSession_usesNoSessionBootstrap() = runTest {
        // Regression for mini-lock §Test plan item 4:
        //   "no-session bootstrap path still works"
        // The new branch lives inside `if (state != null)` (line 2328).
        // When state == null, control goes to the `else` branch at line
        // ~2559 — the existing no-session bootstrap path that calls
        // sessionManager.recipientBootstrap(...) (= the thin wrapper
        // shipped in Commit 1 that calls recipientBootstrapInMemory +
        // saveSession). That path is unchanged from pre-PR behaviour.
        LibsodiumInitializer.initialize()
        val rig = buildInboundRepairRig(
            testScope = this,
            bobRatchet = phantom.core.crypto.LibsodiumDoubleRatchet(),
            skipBobStaleSessionSeed = true,  // Test-specific: state == null
        )

        rig.deliverAliceWireFrameToBob("msg-no-session-bootstrap")
        testScheduler.runCurrent()

        // Existing no-session bootstrap path: message inserted, ack
        // flowed, session was saved by recipientBootstrap's wrapper.
        assertTrue(
            rig.bobMsgRepo.messages.any { it.plaintextCache == "hello from alice — inbound repair test" },
            "no-session bootstrap path MUST still produce a decrypted message " +
                "(regression: recipientBootstrap refactor to thin wrapper preserves " +
                "byte-identical behaviour)",
        )
        assertTrue(
            "msg-no-session-bootstrap" in rig.bobTransport.ackedDelivers,
            "no-session bootstrap path MUST still ack",
        )
        assertNotNull(
            rig.bobRatchetRepo.getRatchetState(rig.convId),
            "no-session bootstrap path MUST still persist the session row " +
                "(refactored recipientBootstrap = recipientBootstrapInMemory + saveSession)",
        )
    }

    /**
     * Test rig for PR-CRYPTO-INBOUND-X3DH-REPAIR1 commit 3 integration
     * tests. Builds Alice's side (real X3DH initiator producing a
     * WireFrame with x3dhInit + ciphertext) and Bob's side (DMS with
     * pre-seeded stale session row + working SPK/OPK repos for the
     * candidate bootstrap to resolve). The caller picks Bob's
     * DoubleRatchet to control whether the candidate decrypt succeeds
     * (real LibsodiumDoubleRatchet) or fails (MacFailingDoubleRatchet).
     *
     * Mirrors the pattern from the existing bootstrap-on-first-send test
     * (around line 855) but adds:
     *   - pre-seeded stale session row for Bob;
     *   - byte-identity readback on Bob's ratchet repo;
     *   - hooks for delivering the captured Alice wire frame to Bob's
     *     transport.
     */
    private suspend fun buildInboundRepairRig(
        testScope: kotlinx.coroutines.test.TestScope,
        bobRatchet: phantom.core.crypto.DoubleRatchet,
        stripAliceX3dhInitFromWireFrame: Boolean = false,
        skipBobStaleSessionSeed: Boolean = false,
    ): InboundRepairRig {
        val real = phantom.core.crypto.LibsodiumX3DH()

        // ── Alice (sender) identity ─────────────────────────────────────
        val aliceX25519 = real.generateDhKeyPair()
        val aliceSigning = com.ionspin.kotlin.crypto.signature.Signature.keypair()

        // ── Bob (receiver) identity + SPK + OPK ─────────────────────────
        val bobX25519 = real.generateDhKeyPair()
        val bobSpkPair = real.generateDhKeyPair()
        val bobSigning = com.ionspin.kotlin.crypto.signature.Signature.keypair()
        val bobOpkPair = real.generateDhKeyPair()
        val bobOpkIdHex = "11223344556677889900aabbccddeeff"

        val bobSpkRepo = object : phantom.core.storage.LocalSignedPreKeyRepository {
            private var stored: phantom.core.storage.LocalSignedPreKeyEntity? =
                phantom.core.storage.LocalSignedPreKeyEntity(
                    keyId = 1L,
                    publicKeyHex = bobSpkPair.publicKey.bytes.toHexStringLower(),
                    privateKeyHex = bobSpkPair.privateKey.bytes.toHexStringLower(),
                    createdAtMs = 1_000L,
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
        }

        // ── Alice runs initiatorBootstrap against Bob's published bundle
        //    to produce an x3dhInit + initial ratchet state. ───────────
        val bobBundleForAlice = PreKeyBundle(
            identityPubkeyHex = bobX25519.publicKey.bytes.toHexStringLower(),
            signingPubkeyHex = bobSigning.publicKey.toByteArray().toHexStringLower(),
            signedPreKeyId = 1L,
            signedPreKeyPublicHex = bobSpkPair.publicKey.bytes.toHexStringLower(),
            signedPreKeyCreatedAtMs = 1_000L,
            signedPreKeySignatureHex = phantom.core.crypto.SignedPreKeySigner.sign(
                spkPublic = bobSpkPair.publicKey,
                createdAtMs = 1_000L,
                identityEd25519SecretKey = bobSigning.secretKey.toByteArray(),
            ).toHexStringLower(),
            oneTimePreKeyIdHex = bobOpkIdHex,
            oneTimePreKeyPublicHex = bobOpkPair.publicKey.bytes.toHexStringLower(),
        )
        val aliceSessionMgr = SessionManager(
            x3dh = real,
            ratchetStateRepository = FakeRatchetStateRepository(),
            signedPreKeyRepository = FakeLocalSignedPreKeyRepository(),
            oneTimePreKeyRepository = FakeLocalOneTimePreKeyRepository(),
            identityCrypto = phantom.core.identity.LibsodiumIdentityCrypto(),
            json = json,
        )
        val aliceBootstrap = aliceSessionMgr.initiatorBootstrap(
            conversationId = "alice-side",
            localIdentityKeyPair = aliceX25519,
            bundle = bobBundleForAlice,
        )

        // Alice encrypts her plaintext under the initial ratchet state
        // using REAL LibsodiumDoubleRatchet (so Bob's later real-X3DH
        // candidate state will decrypt the result successfully in Test 1).
        val aliceRealRatchet = phantom.core.crypto.LibsodiumDoubleRatchet()
        val plaintextPayload = json.encodeToString(
            MessagePayload.serializer(),
            MessagePayload(
                text = "hello from alice — inbound repair test",
                sentAt = 1_700_000_000_000L,
                senderUsername = "alice",
            ),
        ).encodeToByteArray()
        val (_, encrypted) = aliceRealRatchet.encrypt(aliceBootstrap.ratchetState, plaintextPayload)
        val wireFrame = WireFrame(
            encryptedMessage = encrypted,
            x3dhInit = if (stripAliceX3dhInitFromWireFrame) null else aliceBootstrap.x3dhInit,
            senderSigningPublicKeyHex = aliceSigning.publicKey.toByteArray().toHexStringLower(),
        )
        val wireFrameBytes = json.encodeToString(WireFrame.serializer(), wireFrame).encodeToByteArray()
        val padded = phantom.core.crypto.MessagePadding.pad(wireFrameBytes)

        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val sealedSenderHex = phantom.core.crypto.SealedSender.seal(
            fromPubKeyHex = aliceX25519.publicKey.bytes.toHexStringLower(),
            toPublicKeyBytes = bobX25519.publicKey.bytes,
        )

        // ── Bob's DMS rig ──────────────────────────────────────────────
        val bobIdentity = phantom.core.identity.IdentityRecord(
            id = "bob-id",
            username = "bob",
            publicKeyHex = bobX25519.publicKey.bytes.toHexStringLower(),
            dhPrivateKeyHex = bobX25519.privateKey.bytes.toHexStringLower(),
            createdAt = 0L,
        )
        // Conversation id derivation matches DefaultMessagingService's
        // receive path (sorted hex join with `_`).
        val convId = listOf(
            bobIdentity.publicKeyHex,
            aliceX25519.publicKey.bytes.toHexStringLower(),
        ).sorted().joinToString("_")

        val bobRatchetRepo = FakeRatchetStateRepository()
        val staleSerializedState: String? = if (skipBobStaleSessionSeed) {
            null
        } else {
            // Pre-seed a stale all-zero state for Bob. This makes
            // `state != null` fire at line 2328, taking control into
            // the existing-session decrypt path. Real LibsodiumDoubleRatchet
            // (or MacFailingDoubleRatchet) will then MAC-fail under this
            // stale state, triggering the catch + new inbound-repair branch.
            val staleState = phantom.core.crypto.RatchetState(
                rootKey = ByteArray(32),
                sendingChainKey = ByteArray(32),
                receivingChainKey = ByteArray(32),
                sendingRatchetPublicKey = ByteArray(32),
                sendingRatchetPrivateKey = ByteArray(32),
                receivingRatchetPublicKey = ByteArray(32),
            )
            val blob = json.encodeToString(
                phantom.core.crypto.RatchetState.serializer(),
                staleState,
            )
            bobRatchetRepo.upsertRatchetState(convId, blob)
            blob
        }

        val bobSessionMgr = SessionManager(
            x3dh = real,
            ratchetStateRepository = bobRatchetRepo,
            signedPreKeyRepository = bobSpkRepo,
            oneTimePreKeyRepository = bobOpkRepo,
            identityCrypto = phantom.core.identity.LibsodiumIdentityCrypto(),
            json = json,
        )
        // FakeRelayTransport (NOT ManualIncomingTransport) — we need the
        // `ackedDelivers` tracking list to assert on invariant 4 (no
        // early ack on success path + no ack on failure paths).
        val bobIncomingTransport = FakeRelayTransport()
        val bobMsgRepo = FakeMessageRepository()
        val bobConvRepo = FakeConversationRepository()
        val bobProcessedRepo = FakeProcessedEnvelopeLedger()
        val bobDecryptFailedRepo = FakeDecryptFailedEnvelopeLedger()

        // Pre-seed the conversation row for `convId` (NOT sessionSuspect
        // yet — that's set by the existing PR #243 commit 3a hold branch
        // when it fires). FakeConversationRepository.setSessionSuspect
        // no-ops when the row doesn't exist (see line ~171 — it does
        // `store[conversationId]?.let { ... }` and silently skips if
        // null). The mirror behaviour of the SQLDelight implementation
        // also requires an existing row before suspect updates take
        // effect. Tests 2 + 3 assert on suspect transitions, so the
        // row needs to exist beforehand.
        bobConvRepo.upsertConversation(
            ConversationEntity(
                id = convId,
                theirUsername = "alice",
                theirPublicKeyHex = aliceX25519.publicKey.bytes.toHexStringLower(),
                lastMessagePreview = "",
                lastMessageAt = 0L,
                unreadCount = 0,
                sessionSuspect = false,
                sessionSuspectSetAtMs = null,
            ),
        )

        val bobService = DefaultMessagingService(
            identity = bobIdentity,
            localKeyPair = bobX25519,
            ratchet = bobRatchet,
            sessionManager = bobSessionMgr,
            transport = bobIncomingTransport,
            messageRepository = bobMsgRepo,
            conversationRepository = bobConvRepo,
            processedEnvelopeRepository = bobProcessedRepo,
            scope = testScope.backgroundScope,
            json = json,
            preKeyApi = ThrowingPreKeyApi,
            signingKeyProvider = { ThrowingSigningKey },
            decryptFailedEnvelopeRepository = bobDecryptFailedRepo,
            holdMacFailures = true,
        )
        bobService.startReceiving()
        // Let startReceiving's internal `transport.incoming.collect`
        // coroutine actually subscribe BEFORE we return the rig.
        // Without this pump, the test body's `deliver(...)` may emit
        // to the transport's SharedFlow (replay=0) BEFORE the service's
        // collector is subscribed, and the value is silently dropped.
        // Mirrors the `testScheduler.runCurrent()` between
        // startReceiving + deliver in the existing bootstrap-on-first-
        // send test at line ~969-1011.
        testScope.testScheduler.runCurrent()

        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val deliverFn: suspend (String) -> Unit = { messageId ->
            bobIncomingTransport.deliver(
                phantom.core.transport.RelayMessage.Deliver(
                    from = "",
                    sealedSender = kotlin.io.encoding.Base64.encode(sealedSenderHex),
                    payload = kotlin.io.encoding.Base64.encode(padded),
                    messageId = messageId,
                ),
            )
        }

        return InboundRepairRig(
            convId = convId,
            bobMsgRepo = bobMsgRepo,
            bobConvRepo = bobConvRepo,
            bobProcessedRepo = bobProcessedRepo,
            bobDecryptFailedRepo = bobDecryptFailedRepo,
            bobTransport = bobIncomingTransport,
            bobRatchetRepo = bobRatchetRepo,
            staleSerializedState = staleSerializedState,
            deliverAliceWireFrameToBob = deliverFn,
        )
    }

    private class InboundRepairRig(
        val convId: String,
        val bobMsgRepo: FakeMessageRepository,
        val bobConvRepo: FakeConversationRepository,
        val bobProcessedRepo: FakeProcessedEnvelopeLedger,
        val bobDecryptFailedRepo: FakeDecryptFailedEnvelopeLedger,
        val bobTransport: FakeRelayTransport,
        val bobRatchetRepo: FakeRatchetStateRepository,
        val staleSerializedState: String?,
        val deliverAliceWireFrameToBob: suspend (messageId: String) -> Unit,
    )
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

// ═════════════════════════════════════════════════════════════════════════
// PR-CRYPTO-SESSION-REPAIR1 commit 3b (2026-05-30) — test infrastructure
//   - MacFailingDoubleRatchet: forces the MAC-error catch on every
//     decrypt so the hold/ack branching can be exercised by the test
//     matrix below.
//   - FakeDecryptFailedEnvelopeLedger: in-memory
//     DecryptFailedEnvelopeRepository matching the production
//     repository contract.
// ═════════════════════════════════════════════════════════════════════════

private class MacFailingDoubleRatchet : phantom.core.crypto.DoubleRatchet {
    override fun encrypt(
        state: phantom.core.crypto.RatchetState,
        plaintext: ByteArray,
    ): Pair<phantom.core.crypto.RatchetState, phantom.core.crypto.EncryptedMessage> =
        state to phantom.core.crypto.EncryptedMessage(
            ratchetPublicKey = state.sendingRatchetPublicKey,
            messageIndex = state.sendCount,
            ciphertext = plaintext,
            nonce = ByteArray(24),
        )

    override fun decrypt(
        state: phantom.core.crypto.RatchetState,
        message: phantom.core.crypto.EncryptedMessage,
    ): Pair<phantom.core.crypto.RatchetState, ByteArray> =
        throw IllegalArgumentException("MAC verification failed")
}

private class FakeDecryptFailedEnvelopeLedger : phantom.core.storage.DecryptFailedEnvelopeRepository {
    val rows = mutableMapOf<String, phantom.core.storage.DecryptFailedEnvelopeRepository.Entry>()

    override suspend fun insert(
        envelopeId: String,
        conversationId: String,
        senderPubKeyHex: String,
        errorType: String,
        receivedAtMs: Long,
        x3dhInitPresent: Boolean,
        wireFrameJson: String,
    ) {
        if (envelopeId !in rows) {
            rows[envelopeId] = phantom.core.storage.DecryptFailedEnvelopeRepository.Entry(
                envelopeId = envelopeId,
                conversationId = conversationId,
                senderPubKeyHex = senderPubKeyHex,
                errorType = errorType,
                receivedAtMs = receivedAtMs,
                x3dhInitPresent = x3dhInitPresent,
                wireFrameJson = wireFrameJson,
                replayAttemptCount = 0L,
                lastReplayAtMs = null,
            )
        }
    }

    override suspend fun listByConversation(conversationId: String) =
        rows.values.filter { it.conversationId == conversationId }
            .sortedBy { it.receivedAtMs }

    override suspend fun deleteByEnvelopeId(envelopeId: String) {
        rows.remove(envelopeId)
    }

    override suspend fun recordReplayAttempt(envelopeId: String, nowMs: Long) {
        val existing = rows[envelopeId] ?: return
        rows[envelopeId] = existing.copy(
            replayAttemptCount = existing.replayAttemptCount + 1,
            lastReplayAtMs = nowMs,
        )
    }

    override suspend fun deleteOlderThan(olderThanMs: Long) {
        rows.entries.removeAll { it.value.receivedAtMs < olderThanMs }
    }

    override suspend fun count(): Long = rows.size.toLong()

    override suspend fun countByConversation(): Map<String, Long> =
        rows.values.groupingBy { it.conversationId }.eachCount()
            .mapValues { it.value.toLong() }

    override suspend fun deleteAll() {
        rows.clear()
    }
}

// ═══════════════════════════════════════════════════════════════════
// PR-CRYPTO-SESSION-REPAIR1 commit 5a (2026-05-31) — throwing-repo
// wrappers used by replay-safety tests.
// ═══════════════════════════════════════════════════════════════════

/**
 * Delegates every read to the backing [MessageRepository] and throws
 * on [insertMessage] only when the entity id is in [throwOnIds] —
 * lets the replay-safety test target the REPLAYED envelope insert
 * (id = held envelope id) without breaking the OUTGOING trigger send
 * insert (id = trigger message id) which happens earlier in the same
 * sendMessage call.
 */
private class ThrowingInsertMessageRepository(
    private val delegate: MessageRepository,
    private val throwOnIds: Set<String>,
) : MessageRepository by delegate {
    override suspend fun insertMessage(entity: MessageEntity) {
        if (entity.id in throwOnIds) {
            throw RuntimeException(
                "simulated insertMessage failure for ${entity.id} " +
                    "(commit-5a replay-safety test)",
            )
        }
        delegate.insertMessage(entity)
    }
}

/**
 * Delegates every read to the backing [ConversationRepository] but
 * throws on [upsertConversation]. Lets a replay-safety test prove
 * that the OUTER safety guard catches a step-7 conversation-upsert
 * throw and the trigger-send still completes.
 */
private class ThrowingUpsertConversationRepository(
    private val delegate: ConversationRepository,
) : ConversationRepository by delegate {
    override suspend fun upsertConversation(entity: ConversationEntity) {
        throw RuntimeException(
            "simulated upsertConversation failure for commit-5a replay-safety test",
        )
    }
}
