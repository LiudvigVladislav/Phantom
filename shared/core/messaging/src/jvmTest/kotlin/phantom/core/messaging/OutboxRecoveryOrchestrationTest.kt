// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import phantom.core.crypto.DhKeyPair
import phantom.core.crypto.DhPrivateKey
import phantom.core.crypto.DhPublicKey
import phantom.core.crypto.DoubleRatchet
import phantom.core.crypto.EncryptedMessage
import phantom.core.crypto.MessagePadding
import phantom.core.crypto.RatchetState
import phantom.core.crypto.SignedPreKeySigner
import phantom.core.crypto.X3DHProtocol
import phantom.core.identity.IdentityRecord
import phantom.core.identity.IdentitySigningKeyPair
import phantom.core.identity.LibsodiumIdentityCrypto
import phantom.core.identity.SigningPrivateKey
import phantom.core.identity.SigningPublicKey
import phantom.core.storage.ConversationEntity
import phantom.core.storage.ConversationRepository
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageRepository
import phantom.core.storage.MessageStatus
import phantom.core.storage.PendingOpkBinding
import phantom.core.storage.PendingRatchetStateEntity
import phantom.core.storage.PendingRatchetStateRepository
import phantom.core.storage.ProcessedEnvelopeRepository
import phantom.core.storage.RatchetStateRepository
import phantom.core.storage.SessionTransactionRepository
import phantom.core.transport.RelayMessage
import phantom.core.transport.RelayTransport
import phantom.core.transport.TransportState
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * residual N1 Revision 3 — the outbox drives the real service, per source of
 * ratchet state, per crash window.
 *
 * Every cell runs `DefaultMessagingService.sendMessage` and
 * `retryWaitingMessages` with a fault injected at the exact commit point the
 * production code has, then checks the artifacts that matter: what went on the
 * wire, what stayed on disk, and whether a peer running the same index rules
 * accepts what follows.
 *
 * Sources of sending state: the **active** session (committed by
 * `saveSession`), the **initiator-pending** candidate on reuse (committed by
     * `commitInitiatorPending`), and a **fresh bootstrap** (same commit, after a
     * prekey fetch). The fresh-bootstrap fixture signs its prekey through the
     * production signer so the production verification path remains intact.
     * When the initial pending-state commit fails, no sending chain exists on
     * disk and transport has not run, so recovery safely replaces that queued
     * envelope from a new bootstrap rather than adopting uncommitted state.
 *
 * This file lives in `jvmTest` rather than `commonTest` for one reason: it
 * constructs a real `DefaultMessagingService`, whose constructor logs, and the
 * Android unit-test JVM has no implementation behind `android.util.Log`. The
 * rules under test are platform-independent; running them once, on a JVM that
 * can execute them, is what makes the result mean something.
 *
 * Crypto is a fake ratchet with the real index arithmetic
 * (`messageIndex = sendCount`, commit `sendCount + 1`, message key derived
 * from the chain and never from the plaintext) and a receiver that rejects a
 * reused index the way a MAC failure does. Sealed sender is injected; it
 * carries no ratchet state.
 */
@OptIn(ExperimentalEncodingApi::class)
class OutboxRecoveryOrchestrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── fakes ──────────────────────────────────────────────────────────────

    /** Real index arithmetic, no libsodium. Decrypt rejects a reused index like a MAC failure. */
    private class R3Ratchet : DoubleRatchet {
        var failEncrypt = false
        var encrypts = 0
        var decrypts = 0
        override fun encrypt(state: RatchetState, plaintext: ByteArray): Pair<RatchetState, EncryptedMessage> {
            if (failEncrypt) throw IllegalStateException("injected encrypt failure")
            encrypts++
            return state.copy(sendCount = state.sendCount + 1) to EncryptedMessage(
                ratchetPublicKey = state.sendingRatchetPublicKey,
                messageIndex = state.sendCount,
                ciphertext = plaintext,
                nonce = ByteArray(24),
            )
        }
        override fun decrypt(state: RatchetState, message: EncryptedMessage): Pair<RatchetState, ByteArray> {
            decrypts++
            if (message.messageIndex < state.receiveCount) {
                throw IllegalArgumentException("MAC verification error: reused index ${message.messageIndex}")
            }
            return state.copy(receiveCount = message.messageIndex + 1) to message.ciphertext
        }
    }

    private class R3RatchetRepo(seed: Map<String, RatchetState>, private val json: Json) : RatchetStateRepository {
        val store = seed.mapValues { json.encodeToString(RatchetState.serializer(), it.value) }.toMutableMap()
        var failNextUpsert = false
        fun state(conv: String): RatchetState? = store[conv]?.let { json.decodeFromString(RatchetState.serializer(), it) }
        fun put(conv: String, state: RatchetState) { store[conv] = json.encodeToString(RatchetState.serializer(), state) }
        override suspend fun getRatchetState(conversationId: String) = store[conversationId]
        override suspend fun upsertRatchetState(conversationId: String, stateBlob: String) {
            if (failNextUpsert) { failNextUpsert = false; throw IllegalStateException("process died before saveSession") }
            store[conversationId] = stateBlob
        }
        override suspend fun deleteRatchetState(conversationId: String) { store.remove(conversationId) }
        override suspend fun deleteAll() { store.clear() }
    }

    private class R3PendingRepo : PendingRatchetStateRepository {
        val store = mutableMapOf<String, PendingRatchetStateEntity>()
        override suspend fun get(conversationId: String) = store[conversationId]
        override suspend fun upsert(
            conversationId: String,
            stateBlob: String,
            reservedAtMs: Long,
            bootstrapArtifactsBlob: String?,
            opkBinding: PendingOpkBinding,
        ) {
            store[conversationId] = PendingRatchetStateEntity(
                conversationId, stateBlob, reservedAtMs, bootstrapArtifactsBlob, opkBinding,
            )
        }
        override suspend fun delete(conversationId: String) { store.remove(conversationId) }
        override suspend fun getAll() = store.values.toList()
        override suspend fun count() = store.size
        override suspend fun getOldestConversationId() = null
        override suspend fun deleteAll() { store.clear() }
    }

    private class R3SessionTx(private val pending: R3PendingRepo) : SessionTransactionRepository {
        var failNextCommit = false
        private var inboundRatchetRepo: R3RatchetRepo? = null
        private var inboundMessages: R3MessageRepo? = null
        private var inboundLedger: R3Ledger? = null

        fun bindInbound(
            ratchetRepo: R3RatchetRepo,
            messages: R3MessageRepo,
            ledger: R3Ledger,
        ) {
            inboundRatchetRepo = ratchetRepo
            inboundMessages = messages
            inboundLedger = ledger
        }
        override suspend fun commitInitiatorPending(
            conversationId: String,
            stateBlob: String,
            bootstrapArtifactsBlob: String,
            nowMs: Long,
        ) {
            if (failNextCommit) {
                failNextCommit = false
                throw IllegalStateException("process died before commitInitiatorPending")
            }
            pending.upsert(conversationId, stateBlob, nowMs, bootstrapArtifactsBlob)
        }
        override suspend fun commitBootstrap(
            opkKeyIdHex: String,
            conversationId: String,
            stateBlob: String,
            bootstrapArtifactsBlob: String?,
        ): Boolean = error("commitBootstrap is not on the outbound path")
        override suspend fun evictPendingCandidate(conversationId: String) { pending.delete(conversationId) }
        override suspend fun promotePendingToActive(conversationId: String): Boolean =
            error("promotePendingToActive is not on the outbound path")
        override suspend fun commitInboundMessage(
            conversationId: String,
            envelopeId: String,
            senderPubKeyHex: String,
            payloadType: String,
            nowMs: Long,
            message: MessageEntity,
            advancedStateBlob: String?,
            promotePending: Boolean,
            expectedOpkKeyIdHex: String?,
            stateTarget: phantom.core.storage.InboundStateTarget,
        ): phantom.core.storage.InboundCommitOutcome {
            inboundMessages?.insertMessage(message) ?: error("inbound message repo not bound")
            advancedStateBlob?.let { state ->
                inboundRatchetRepo?.upsertRatchetState(conversationId, state)
                    ?: error("inbound ratchet repo not bound")
            }
            inboundLedger?.markProcessed(
                envelopeId = envelopeId,
                conversationId = conversationId,
                senderPubKeyHex = senderPubKeyHex,
                payloadType = payloadType,
                status = ProcessedEnvelopeRepository.Status.PROCESSED,
                nowMs = nowMs,
            ) ?: error("inbound ledger not bound")
            return phantom.core.storage.InboundCommitOutcome.Committed
        }
    }

    private class R3Transport : RelayTransport {
        override val state: StateFlow<TransportState> = MutableStateFlow(TransportState.Connected)
        private val _incoming = MutableSharedFlow<RelayMessage.Deliver>(replay = 0, extraBufferCapacity = 64)
        override val incoming: Flow<RelayMessage.Deliver> = _incoming
        override val acks: Flow<RelayMessage.Ack> = emptyFlow()
        override val typingEvents: SharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 10)
        val sent = mutableListOf<RelayMessage.Send>()
        val ackedDelivers = mutableListOf<String>()
        var sendShouldSucceed = true
        var beforeSend: suspend () -> Unit = {}
        override suspend fun connect(
            relayUrl: String,
            identityPublicKeyHex: String,
            signingPublicKeyHex: String,
            signChallenge: suspend (ByteArray) -> ByteArray?,
            socksProxyPort: Int?,
        ) {}
        override suspend fun disconnect() {}
        override suspend fun send(message: RelayMessage.Send): Boolean {
            beforeSend()
            sent += message
            return sendShouldSucceed
        }
        override suspend fun sendDeliveryAck(messageId: String): Boolean { ackedDelivers += messageId; return true }
        override suspend fun sendTyping(toPubKeyHex: String) = true
        override fun isConnected() = true
        override val lastPongElapsedMs: Long get() = 0L
        override val lastInboundFrameElapsedMs: Long get() = 0L
        override val pendingAckCount: Int get() = 0
        override suspend fun forceReconnect() {}
        override suspend fun disconnectAndJoin(timeoutMs: Long) = true
        suspend fun deliver(d: RelayMessage.Deliver) { _incoming.emit(d) }
    }

    private class R3MessageRepo : MessageRepository {
        val messages = mutableListOf<MessageEntity>()
        var fixedCreatedAt: Long? = null
        var replaceCalls = 0
        var deleteCalls = 0
        var updateTextCalls = 0
        var pinCalls = 0
        fun mutate(id: String, block: (MessageEntity) -> MessageEntity) {
            val i = messages.indexOfFirst { it.id == id }
            if (i != -1) messages[i] = block(messages[i])
        }
        /**
         * Revision 4: `getMessages` is `created_at ASC` in production, and a
         * list read back from SQLite may present equal timestamps in any
         * order. Flipping this makes the repository hand settlement the rows
         * in the order that would give the WRONG answer if list position were
         * trusted.
         */
        var reverseOrder = false
        override suspend fun getMessages(conversationId: String): List<MessageEntity> {
            val rows = messages.filter { it.conversationId == conversationId }
                .sortedBy { it.createdAt }
            return if (reverseOrder) rows.reversed() else rows
        }
        override fun observeMessages(conversationId: String): Flow<List<MessageEntity>> = emptyFlow()
        override suspend fun getMessageById(id: String) = messages.firstOrNull { it.id == id }
        override suspend fun insertMessage(entity: MessageEntity) {
            if (messages.none { it.id == entity.id }) {
                messages += fixedCreatedAt?.let { entity.copy(createdAt = it) } ?: entity
            }
        }
        var failNextReplace = false
        override suspend fun replaceMessage(entity: MessageEntity) {
            replaceCalls++
            if (failNextReplace) {
                failNextReplace = false
                throw IllegalStateException("injected replaceMessage failure")
            }
            // residual N1 Revision 4: in-place envelope rewrite. Identity and
            // user-state columns are not touched, and a missing row throws
            // rather than being created, matching the SQLDelight contract.
            val i = messages.indexOfFirst { it.id == entity.id }
            if (i == -1) throw NoSuchElementException("replaceMessage: no row with id=${entity.id}")
            messages[i] = messages[i].copy(
                ciphertext = entity.ciphertext,
                plaintextCache = entity.plaintextCache,
                sent = entity.sent,
                status = entity.status,
                expiresAtMs = entity.expiresAtMs,
            )
        }
        override suspend fun updateStatus(messageId: String, status: MessageStatus) {
            mutate(messageId) { it.copy(status = status) }
        }
        override suspend fun updateMessageText(messageId: String, text: String) {
            updateTextCalls++
            mutate(messageId) { it.copy(plaintextCache = text) }
        }
        override suspend fun deleteMessage(messageId: String) {
            deleteCalls++
            messages.removeAll { it.id == messageId }
        }
        override suspend fun deleteMessagesForConversation(conversationId: String) {
            messages.removeAll { it.conversationId == conversationId }
        }
        override suspend fun setExpiresAt(messageId: String, expiresAtMs: Long) {}
        override suspend fun getNextExpiry(): Long? = null
        override suspend fun deleteExpiredMessages() {}
        override suspend fun pinMessage(messageId: String, pinned: Boolean, pinnedByPubkey: String?) {
            pinCalls++
            mutate(messageId) { it.copy(pinned = pinned, pinnedByPubkey = pinnedByPubkey) }
        }
        override suspend fun getPinnedMessages(conversationId: String) = emptyList<MessageEntity>()
        override suspend fun saveMessage(id: String) {}
        override suspend fun unsaveMessage(id: String) {}
        override suspend fun getSavedMessages() = emptyList<MessageEntity>()
    }

    private class R3ConversationRepo : ConversationRepository {
        val store = mutableMapOf<String, ConversationEntity>()
        private fun mod(id: String, f: (ConversationEntity) -> ConversationEntity) {
            store[id]?.let { store[id] = f(it) }
        }
        override suspend fun getAllConversations() = store.values.toList()
        override suspend fun getActiveConversations() = store.values.filter { !it.blocked }
        override suspend fun getMessageRequests() = emptyList<ConversationEntity>()
        override suspend fun getConversation(id: String) = store[id]
        override suspend fun upsertConversation(entity: ConversationEntity) { store[entity.id] = entity }
        override suspend fun incrementUnread(conversationId: String) =
            mod(conversationId) { it.copy(unreadCount = it.unreadCount + 1) }
        override suspend fun resetUnread(conversationId: String) = mod(conversationId) { it.copy(unreadCount = 0) }
        override suspend fun updateNotes(conversationId: String, notes: String?) =
            mod(conversationId) { it.copy(notes = notes) }
        override suspend fun getBlockedConversations() = store.values.filter { it.blocked }
        override suspend fun blockConversation(conversationId: String) = mod(conversationId) { it.copy(blocked = true) }
        override suspend fun unblockConversation(conversationId: String) = mod(conversationId) { it.copy(blocked = false) }
        override suspend fun acceptRequest(conversationId: String) {}
        override suspend fun deleteConversation(id: String) { store.remove(id) }
        override suspend fun setVerified(conversationId: String, verified: Boolean) =
            mod(conversationId) { it.copy(isVerified = verified) }
        override suspend fun setDisappearingTimer(conversationId: String, secs: Long) =
            mod(conversationId) { it.copy(disappearingTimerSecs = secs) }
        override suspend fun getDisappearingTimer(conversationId: String) =
            store[conversationId]?.disappearingTimerSecs ?: 0L
        override suspend fun archiveConversation(id: String) = mod(id) { it.copy(archived = true) }
        override suspend fun unarchiveConversation(id: String) = mod(id) { it.copy(archived = false) }
        override suspend fun getArchivedConversations() = store.values.filter { it.archived }
        override suspend fun setIdentityKeyChangedAt(conversationId: String, ts: Long) =
            mod(conversationId) { it.copy(identityKeyChangedAt = ts) }
        override suspend fun clearIdentityKeyChangedAt(conversationId: String) =
            mod(conversationId) { it.copy(identityKeyChangedAt = null) }
        override suspend fun setMutedUntil(conversationId: String, until: Long?) =
            mod(conversationId) { it.copy(mutedUntil = until) }
        override suspend fun setPinned(conversationId: String, pinned: Boolean) =
            mod(conversationId) { it.copy(pinned = pinned) }
        override suspend fun setNeedsRehandshake(conversationId: String, needs: Boolean) =
            mod(conversationId) { it.copy(needsRehandshake = needs) }
        override suspend fun markAllNeedsRehandshake() {}
        override suspend fun setSessionSuspect(conversationId: String, setAtMs: Long) {}
        override suspend fun clearSessionSuspect(conversationId: String) {}
        override suspend fun getSessionSuspectConversations() = emptyList<ConversationEntity>()
    }

    private class R3Ledger : ProcessedEnvelopeRepository {
        val rows = mutableMapOf<String, ProcessedEnvelopeRepository.Status>()
        override suspend fun exists(envelopeId: String) = rows.containsKey(envelopeId)
        override suspend fun markProcessed(
            envelopeId: String,
            conversationId: String,
            senderPubKeyHex: String,
            payloadType: String,
            status: ProcessedEnvelopeRepository.Status,
            nowMs: Long,
        ) {
            if (!rows.containsKey(envelopeId)) rows[envelopeId] = status
        }
        override suspend fun deleteOlderThan(olderThanMs: Long) {}
        override suspend fun countByStatus() = emptyMap<ProcessedEnvelopeRepository.Status, Long>()
        override suspend fun deleteAll() { rows.clear() }
    }

    private class R3X3DH : X3DHProtocol {
        private fun state() = RatchetState(
            rootKey = ByteArray(32), sendingChainKey = ByteArray(32), receivingChainKey = ByteArray(32),
            sendingRatchetPublicKey = ByteArray(32) { 0x42 }, sendingRatchetPrivateKey = ByteArray(32) { 0x43 },
            receivingRatchetPublicKey = ByteArray(32),
        )
        override fun generateDhKeyPair() = DhKeyPair(DhPublicKey(ByteArray(32) { 1 }), DhPrivateKey(ByteArray(32) { 2 }))
        override fun computeSharedSecret(privateKey: DhPrivateKey, publicKey: DhPublicKey) = ByteArray(32)
        override fun initiatorHandshake(
            initiatorIdentityKeyPair: DhKeyPair,
            recipientIdentityPublicKey: DhPublicKey,
            recipientSignedPreKey: DhPublicKey,
        ) = state()
        override fun recipientHandshake(
            recipientIdentityKeyPair: DhKeyPair,
            recipientSignedPreKeyPair: DhKeyPair,
            initiatorIdentityPublicKey: DhPublicKey,
            initiatorEphemeralPublicKey: DhPublicKey,
        ) = state()
        override fun initiatorHandshake4DH(
            initiatorIdentityKeyPair: DhKeyPair,
            recipientIdentityPublicKey: DhPublicKey,
            recipientSignedPreKey: DhPublicKey,
            recipientOPK: DhPublicKey?,
        ) = state()
        override fun recipientHandshake4DH(
            recipientIdentityKeyPair: DhKeyPair,
            recipientSignedPreKeyPair: DhKeyPair,
            recipientOPKPair: DhKeyPair?,
            initiatorIdentityPublicKey: DhPublicKey,
            initiatorEphemeralPublicKey: DhPublicKey,
        ) = state()
        override fun initiatorHandshake4DHWithEphemeral(
            initiatorIdentityKeyPair: DhKeyPair,
            recipientIdentityPublicKey: DhPublicKey,
            recipientSignedPreKey: DhPublicKey,
            recipientOPK: DhPublicKey?,
            ephemeralKeyPair: DhKeyPair,
        ) = state()
    }

    private class R3IdentityCrypto : phantom.core.identity.IdentityCrypto {
        override fun generateKeyPair() = phantom.core.identity.IdentityKeyPair(
            phantom.core.identity.PublicKey(ByteArray(32)), phantom.core.identity.PrivateKey(ByteArray(32)),
        )
        override fun generateSigningKeyPair() =
            IdentitySigningKeyPair(SigningPublicKey(ByteArray(32)), SigningPrivateKey(ByteArray(64)))
        override fun sign(message: ByteArray, privateKey: phantom.core.identity.PrivateKey) = error("unused")
        override fun verify(message: ByteArray, signature: ByteArray, publicKey: phantom.core.identity.PublicKey) =
            error("unused")
        override fun signWithIdentity(message: ByteArray, privateKey: SigningPrivateKey) = ByteArray(64)
        override fun verifyWithIdentity(message: ByteArray, signature: ByteArray, publicKey: SigningPublicKey) = true
        override fun publicKeyToHex(key: phantom.core.identity.PublicKey) =
            key.bytes.joinToString("") { "%02x".format(it.toInt().and(0xFF)) }
        override fun hexToPublicKey(hex: String) = phantom.core.identity.PublicKey(ByteArray(0))
        override fun signingPublicKeyToHex(key: SigningPublicKey) =
            key.bytes.joinToString("") { "%02x".format(it.toInt().and(0xFF)) }
        override fun hexToSigningPublicKey(hex: String) = SigningPublicKey(ByteArray(0))
    }

    private class R3SpkRepo(private val stored: phantom.core.storage.LocalSignedPreKeyEntity?) :
        phantom.core.storage.LocalSignedPreKeyRepository {
        override suspend fun get() = stored
        override suspend fun upsert(entity: phantom.core.storage.LocalSignedPreKeyEntity) {}
        override suspend fun clear() {}
    }

    private class R3OpkRepo : phantom.core.storage.LocalOneTimePreKeyRepository {
        override suspend fun get(keyIdHex: String): phantom.core.storage.LocalOneTimePreKeyEntity? = null
        override suspend fun getAll() = emptyList<phantom.core.storage.LocalOneTimePreKeyEntity>()
        override suspend fun count() = 0
        override suspend fun insert(entity: phantom.core.storage.LocalOneTimePreKeyEntity) {}
        override suspend fun insertAll(entities: List<phantom.core.storage.LocalOneTimePreKeyEntity>) {}
        override suspend fun deleteByKeyId(keyIdHex: String) {}
        override suspend fun clear() {}
    }

    private class R3PreKeyApi(private val bundle: phantom.core.transport.PreKeyBundle?) :
        phantom.core.transport.PreKeyApi {
        override suspend fun publishBundle(
            forceJoinInFlight: Boolean,
            requestProvider: suspend () -> phantom.core.transport.PublishRequest,
        ) = phantom.core.transport.PublishResult.Stored(0)
        override suspend fun fetchBundle(identityPubkeyHex: String, requesterPubkeyHex: String?) = bundle
        override suspend fun fetchStatus(identityPubkeyHex: String, requesterPubkeyHex: String?) =
            phantom.core.transport.PreKeyStatus(remaining_opks = 0, signed_prekey_age_days = null)
    }

    // ── fixture ────────────────────────────────────────────────────────────

    private enum class Source { ACTIVE, PENDING_REUSE, FRESH_BOOTSTRAP }

    private class Side(
        val service: DefaultMessagingService,
        val ratchet: R3Ratchet,
        val ratchetRepo: R3RatchetRepo,
        val pendingRepo: R3PendingRepo,
        val sessionTx: R3SessionTx,
        val transport: R3Transport,
        val messages: R3MessageRepo,
        val ledger: R3Ledger,
    )

    private class PausingVoiceUploader : VoiceUploadSender {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun uploadVoice(
            audioBytes: ByteArray,
            durationMs: Long,
            mime: String,
            onSplit: ((total: Int) -> Unit)?,
            onChunkUploaded: ((sent: Int, total: Int) -> Unit)?,
            onEarlyManifest: (suspend (manifest: VoiceManifestV2) -> Unit)?,
        ): Result<VoiceManifestV2> {
            entered.complete(Unit)
            release.await()
            return Result.success(testVoiceManifest(durationMs, mime, audioBytes.size))
        }
    }

    private class ImmediateVoiceUploader : VoiceUploadSender {
        override suspend fun uploadVoice(
            audioBytes: ByteArray,
            durationMs: Long,
            mime: String,
            onSplit: ((total: Int) -> Unit)?,
            onChunkUploaded: ((sent: Int, total: Int) -> Unit)?,
            onEarlyManifest: (suspend (manifest: VoiceManifestV2) -> Unit)?,
        ): Result<VoiceManifestV2> = Result.success(
            testVoiceManifest(durationMs, mime, audioBytes.size),
        )
    }

    private val activeChainKey = ByteArray(32) { 0x11 }
    private val pendingChainKey = ByteArray(32) { 0x22 }
    private val rotatedChainKey = ByteArray(32) { 0x33 }
    private val signingKey = IdentitySigningKeyPair(SigningPublicKey(ByteArray(32)), SigningPrivateKey(ByteArray(64)))
    private val freshBootstrapSigningKey by lazy {
        runBlocking { LibsodiumInitializer.initialize() }
        LibsodiumIdentityCrypto().generateSigningKeyPair()
    }
    private val nowMs = 1_000_000L

    private fun seedState(chainKey: ByteArray) = RatchetState(
        rootKey = ByteArray(32), sendingChainKey = ByteArray(32), receivingChainKey = ByteArray(32),
        sendingRatchetPublicKey = chainKey, sendingRatchetPrivateKey = ByteArray(32),
        receivingRatchetPublicKey = ByteArray(32),
    )

    private fun bundle(): phantom.core.transport.PreKeyBundle {
        val signedPreKey = DhPublicKey(ByteArray(32) { 0xEE.toByte() })
        val signature = SignedPreKeySigner.sign(
            spkPublic = signedPreKey,
            createdAtMs = 1_000L,
            identityEd25519SecretKey = freshBootstrapSigningKey.privateKey.bytes,
        )
        return phantom.core.transport.PreKeyBundle(
            identity_pubkey_hex = "cc".repeat(32),
            signing_pubkey_hex = freshBootstrapSigningKey.publicKey.bytes.toHex(),
            signed_pre_key = phantom.core.transport.WireSignedPreKey(
                key_id = 7L,
                public_key_hex = signedPreKey.bytes.toHex(),
                created_at_ms = 1_000L,
                signature_hex = signature.toHex(),
            ),
            one_time_pre_key = null,
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun sender(
        scope: CoroutineScope,
        source: Source,
        voiceUploadSender: VoiceUploadSender? = null,
        sealSender: (String, ByteArray) -> ByteArray = { _, _ -> ByteArray(16) },
    ): Side {
        val ratchet = R3Ratchet()
        val ratchetRepo = R3RatchetRepo(
            if (source == Source.ACTIVE) mapOf(CONV to seedState(activeChainKey)) else emptyMap(), json,
        )
        val pendingRepo = R3PendingRepo()
        if (source == Source.PENDING_REUSE) {
            pendingRepo.store[CONV] = PendingRatchetStateEntity(
                conversationId = CONV,
                stateBlob = json.encodeToString(RatchetState.serializer(), seedState(pendingChainKey)),
                reservedAtMs = nowMs,
                bootstrapArtifactsBlob = BootstrapArtifacts(
                    x3dhInit = X3dhInitHeader(ephemeralPubKeyHex = "01".repeat(32), spkKeyId = 7L, opkKeyIdHex = null),
                    recipientPubkeyHex = PEER,
                ).toBlob(json),
            )
        }
        val sessionTx = R3SessionTx(pendingRepo)
        val transport = R3Transport()
        val messages = R3MessageRepo()
        val conversations = R3ConversationRepo().apply {
            store[CONV] = ConversationEntity(CONV, "bob", PEER, null, null, 0)
        }
        val ledger = R3Ledger()
        sessionTx.bindInbound(ratchetRepo, messages, ledger)
        val sessionManager = SessionManager(
            x3dh = R3X3DH(), ratchetStateRepository = ratchetRepo,
            signedPreKeyRepository = R3SpkRepo(null), oneTimePreKeyRepository = R3OpkRepo(),
            identityCrypto = R3IdentityCrypto(), json = json,
        )
        val service = DefaultMessagingService(
            identity = IdentityRecord("id-a", "alice", ME, "ab", 0L),
            localKeyPair = DhKeyPair(
                DhPublicKey(ByteArray(32) { 0xAA.toByte() }), DhPrivateKey(ByteArray(32) { 0xBB.toByte() }),
            ),
            ratchet = ratchet, sessionManager = sessionManager, transport = transport,
            sealSender = sealSender,
            messageRepository = messages, conversationRepository = conversations,
            processedEnvelopeRepository = ledger, scope = scope, json = json,
            preKeyApi = R3PreKeyApi(if (source == Source.FRESH_BOOTSTRAP) bundle() else null),
            signingKeyProvider = {
                if (source == Source.FRESH_BOOTSTRAP) freshBootstrapSigningKey else signingKey
            },
            pendingRatchetStateRepository = pendingRepo, sessionTransactionRepository = sessionTx,
            nowMsProvider = { nowMs },
            voiceV2Sender = voiceUploadSender,
        )
        return Side(service, ratchet, ratchetRepo, pendingRepo, sessionTx, transport, messages, ledger)
    }

    /**
     * A peer with the same index rules, holding an active session for the
     * conversation. The receive path decrypts with the active session whenever
     * one is loaded, so this peer accepts envelopes from all three sender
     * sources and rejects a reused index exactly as a MAC failure would.
     */
    private fun receiver(scope: CoroutineScope): Side {
        val ratchet = R3Ratchet()
        val ratchetRepo = R3RatchetRepo(mapOf(CONV to seedState(ByteArray(32) { 0x55 })), json)
        val pendingRepo = R3PendingRepo()
        val sessionTx = R3SessionTx(pendingRepo)
        val transport = R3Transport()
        val messages = R3MessageRepo()
        val ledger = R3Ledger()
        sessionTx.bindInbound(ratchetRepo, messages, ledger)
        val sessionManager = SessionManager(
            x3dh = R3X3DH(), ratchetStateRepository = ratchetRepo,
            signedPreKeyRepository = R3SpkRepo(
                phantom.core.storage.LocalSignedPreKeyEntity(
                    keyId = 7L, publicKeyHex = "11".repeat(32), privateKeyHex = "22".repeat(32),
                    createdAtMs = 1_000L, signatureHex = "33".repeat(64),
                ),
            ),
            oneTimePreKeyRepository = R3OpkRepo(), identityCrypto = R3IdentityCrypto(), json = json,
        )
        val service = DefaultMessagingService(
            identity = IdentityRecord("id-b", "bob", PEER, "cd", 0L),
            localKeyPair = DhKeyPair(
                DhPublicKey(ByteArray(32) { 0xCC.toByte() }), DhPrivateKey(ByteArray(32) { 0xDD.toByte() }),
            ),
            ratchet = ratchet, sessionManager = sessionManager, transport = transport,
            sealSender = { _, _ -> ByteArray(16) },
            messageRepository = messages, conversationRepository = R3ConversationRepo(),
            processedEnvelopeRepository = ledger, scope = scope, json = json,
            preKeyApi = R3PreKeyApi(null), signingKeyProvider = { signingKey },
            nowMsProvider = { nowMs },
        )
        return Side(service, ratchet, ratchetRepo, pendingRepo, sessionTx, transport, messages, ledger)
    }

    private fun outgoing(id: String, text: String) =
        OutgoingMessage(id = id, conversationId = CONV, recipientPublicKeyHex = PEER, text = text)

    private companion object {
        const val ME = "aabb"
        const val PEER = "ccdd"
        const val CONV = "aabb_ccdd"

        private fun testVoiceManifest(durationMs: Long, mime: String, plainSize: Int) = VoiceManifestV2(
            mediaId = "test-media",
            mediaKey = "test-key",
            nonce = "test-nonce",
            durationMs = durationMs,
            mime = mime,
            chunkCount = 1,
            encryptedSizeBytes = plainSize.toLong() + 16L,
            plainSizeBytes = plainSize.toLong(),
            sha256 = "test-sha256",
        )
    }

    private fun envelopeOfBytes(ciphertext: ByteArray): EncryptedMessage =
        json.decodeFromString(WireFrame.serializer(), ciphertext.decodeToString()).encryptedMessage

    private fun envelopeOf(send: RelayMessage.Send): EncryptedMessage =
        envelopeOfBytes(MessagePadding.unpad(Base64.decode(send.payload)))

    private fun payloadTextOf(send: RelayMessage.Send): String =
        json.decodeFromString(
            MessagePayload.serializer(), envelopeOf(send).ciphertext.decodeToString(),
        ).text

    private fun asDeliver(send: RelayMessage.Send) =
        RelayMessage.Deliver(from = ME, sealedSender = "", payload = send.payload, messageId = send.messageId)

    private fun Side.persistedChainOrNull(source: Source): RatchetState? = when (source) {
        Source.ACTIVE -> ratchetRepo.state(CONV)!!
        Source.PENDING_REUSE, Source.FRESH_BOOTSTRAP ->
            pendingRepo.store[CONV]?.let {
                json.decodeFromString(RatchetState.serializer(), it.stateBlob)
            }
    }

    private fun Side.persistedChain(source: Source): RatchetState =
        requireNotNull(persistedChainOrNull(source)) { "no persisted chain for $source" }

    private fun Side.row(id: String) = messages.messages.single { it.id == id }

    /** Put the sender into a crash window on one send and return the queued row. */
    private suspend fun Side.arm(
        source: Source,
        window: Int,
        id: String = "m1",
        text: String = "queued while broken",
    ): MessageEntity {
        when (window) {
            1 -> if (source == Source.ACTIVE) ratchetRepo.failNextUpsert = true else sessionTx.failNextCommit = true
            2 -> transport.beforeSend = { throw IllegalStateException("process died before transport.send") }
            3 -> transport.sendShouldSucceed = false
        }
        val outcome = service.sendMessage(outgoing(id, text))
        transport.beforeSend = {}
        transport.sendShouldSucceed = true
        if (messages.messages.none { it.id == id }) {
            // No row means the send never reached `afterEncrypt`. Name the real
            // reason rather than letting a lookup throw over it.
            fail(
                "W$window: no row was written for $id — sendMessage failed before encryption with " +
                    "${outcome.exceptionOrNull()?.let { it::class.simpleName + ": " + it.message }}",
            )
        }
        val row = row(id)
        assertEquals(MessageStatus.QUEUED, row.status, "W$window leaves the row QUEUED")
        assertTrue(row.ciphertext.isNotEmpty(), "W$window leaves the exact envelope on the row")
        return row
    }

    private suspend fun Side.recover(): Int = service.retryWaitingMessages("test").getOrThrow()

    // ── the six cells, parameterised by source of state ────────────────────

    /**
     * W1 — the row was written and the commit never followed. The state on
     * disk is still the one that produced the envelope, so recovery adopts the
     * step it missed and sends the ORIGINAL bytes. Re-encrypting here would
     * burn a second index for one message.
     */
    private suspend fun w1_adopts_the_uncommitted_step(scope: TestScope, source: Source) {
        val s = sender(scope, source)
        val row = s.arm(source, window = 1)
        val stale = envelopeOfBytes(row.ciphertext)
        val before = s.persistedChainOrNull(source)
        if (source == Source.FRESH_BOOTSTRAP) {
            assertEquals(null, before, "fresh W1: the failed first commit left no chain on disk")
        } else {
            assertEquals(before!!.sendCount, stale.messageIndex, "W1: the envelope sits at the uncommitted index")
        }
        assertTrue(s.transport.sent.isEmpty(), "W1: nothing reached the wire")

        assertEquals(1, s.recover())

        val sent = s.transport.sent.single()
        assertEquals(row.id, sent.messageId)
        if (source == Source.FRESH_BOOTSTRAP) {
            assertEquals(
                Base64.encode(MessagePadding.pad(s.row(row.id).ciphertext)), sent.payload,
                "fresh W1 sends the replacement envelope that was committed on recovery",
            )
            assertEquals(1, s.messages.replaceCalls, "fresh W1 has no saved chain, so it replaces in place")
            assertEquals(1, s.persistedChain(source).sendCount)
        } else {
            assertEquals(
                Base64.encode(MessagePadding.pad(row.ciphertext)), sent.payload,
                "the original envelope went on the wire, unchanged",
            )
            assertEquals(
                before!!.sendCount + 1, s.persistedChain(source).sendCount,
                "and the commit that the crash skipped has now happened",
            )
            assertEquals(0, s.messages.replaceCalls, "no re-encryption was needed")
        }
        assertEquals(0, s.messages.deleteCalls, "and the row was never deleted")
        assertEquals(MessageStatus.SENT, s.row(row.id).status)
    }

    /** W2 — the commit happened, the send died before the relay saw it. */
    private suspend fun w2_resends_the_stored_bytes(scope: TestScope, source: Source) {
        val s = sender(scope, source)
        val row = s.arm(source, window = 2)
        assertEquals(1, s.persistedChain(source).sendCount, "W2: the commit happened before the send died")
        assertTrue(s.transport.sent.isEmpty(), "W2: nothing reached the relay")

        assertEquals(1, s.recover())

        val sent = s.transport.sent.single()
        assertEquals(Base64.encode(MessagePadding.pad(row.ciphertext)), sent.payload, "the stored bytes, unchanged")
        assertEquals(1, s.persistedChain(source).sendCount, "no second ratchet step")
        assertEquals(1, s.ratchet.encrypts, "encrypt ran once, on the original send")
        assertEquals(MessageStatus.SENT, s.row(row.id).status)
    }

    /** W3 — the relay refused the send. */
    private suspend fun w3_resends_after_a_refused_send(scope: TestScope, source: Source) {
        val s = sender(scope, source)
        val row = s.arm(source, window = 3)
        assertEquals(1, s.transport.sent.size, "W3: the send was attempted and refused")

        assertEquals(1, s.recover())

        assertEquals(2, s.transport.sent.size)
        assertEquals(s.transport.sent[0].payload, s.transport.sent[1].payload, "the same envelope again")
        assertEquals(s.transport.sent[0].messageId, s.transport.sent[1].messageId, "under the same message id")
        assertEquals(1, s.ratchet.encrypts, "no second ratchet step")
        assertEquals(MessageStatus.SENT, s.row(row.id).status)
    }

    /**
     * Each row takes the branch its own state proves, and a new send adopts a
     * crashed step BEFORE it takes an index — otherwise both messages would
     * claim the same step and the peer would drop one.
     */
    private suspend fun the_retry_uses_the_branch_the_state_proves(scope: TestScope, source: Source) {
        val s = sender(scope, source)
        val first = s.arm(source, window = 1, id = "first", text = "first")
        val firstIndex = envelopeOfBytes(first.ciphertext).messageIndex

        s.service.sendMessage(outgoing("second", "second")).getOrThrow()

        val secondIndex = envelopeOfBytes(s.row("second").ciphertext).messageIndex
        assertEquals(firstIndex + 1, secondIndex, "the new send took the NEXT index, not the crashed one")
        assertEquals(
            firstIndex + 2, s.persistedChain(source).sendCount,
            "both steps are committed: the adopted one and the new one",
        )
        assertEquals(listOf("first", "second"), s.transport.sent.map { it.messageId })
        assertEquals(
            listOf(firstIndex, secondIndex), s.transport.sent.map { envelopeOf(it).messageIndex },
            "the predecessor drains before the successor is submitted",
        )
        assertEquals(
            if (source == Source.FRESH_BOOTSTRAP) 1 else 0,
            s.messages.replaceCalls,
            "only a fresh W1 row lacks any persisted chain and needs in-place replacement",
        )
        assertEquals(0, s.messages.deleteCalls)
    }

    /** The peer accepts the recovered message and the one after it, in order. */
    private suspend fun the_next_message_is_accepted_by_the_peer(scope: TestScope, source: Source) {
        val s = sender(scope, source)
        val r = receiver(scope.backgroundScope)
        r.service.startReceiving()
        scope.testScheduler.runCurrent()

        s.arm(source, window = 1)
        assertEquals(1, s.recover())
        s.service.sendMessage(outgoing("m2", "after recovery")).getOrThrow()
        assertEquals(2, s.transport.sent.size, "the recovered message and the next one")

        for (send in s.transport.sent) {
            r.transport.deliver(asDeliver(send))
            scope.testScheduler.runCurrent()
        }

        assertEquals(
            listOf("queued while broken", "after recovery"),
            r.messages.messages.map { it.plaintextCache },
            "the peer accepted both, in order, with no MAC failure",
        )
        assertEquals(
            listOf(ProcessedEnvelopeRepository.Status.PROCESSED, ProcessedEnvelopeRepository.Status.PROCESSED),
            r.ledger.rows.values.toList(), r.ledger.rows.toString(),
        )
    }

    /** A recovery that fails loses neither the row nor its envelope. */
    private suspend fun a_failed_recovery_keeps_the_row(scope: TestScope, source: Source) {
        val s = sender(scope, source)
        val row = s.arm(source, window = 1)
        val stateBefore = s.persistedChainOrNull(source)

        s.ratchet.failEncrypt = true
        val outcome = s.service.retryWaitingMessages("test")
        s.ratchet.failEncrypt = false

        if (source == Source.FRESH_BOOTSTRAP) {
            assertEquals(
                0,
                outcome.getOrThrow(),
                "a failed in-place replacement stops this sweep without claiming an attempt",
            )
        } else {
            assertTrue(outcome.isFailure, "an adoption failure is surfaced")
        }
        val kept = s.row(row.id)
        assertTrue(kept.ciphertext.contentEquals(row.ciphertext), "the original envelope is still on the row")
        assertEquals(MessageStatus.QUEUED, kept.status)
        assertTrue(s.transport.sent.isEmpty(), "nothing was sent")
        assertEquals(0, s.messages.deleteCalls)
        assertEquals(0, s.messages.replaceCalls)
        assertEquals(
            stateBefore?.sendCount,
            s.persistedChainOrNull(source)?.sendCount,
            "and no step was committed",
        )

        // The same row recovers once the fault is gone.
        assertEquals(1, s.recover())
        assertEquals(MessageStatus.SENT, s.row(row.id).status)
    }

    // ── ACTIVE ─────────────────────────────────────────────────────────────

    @Test fun active_w1() = runTest { w1_adopts_the_uncommitted_step(this, Source.ACTIVE) }
    @Test fun active_w2() = runTest { w2_resends_the_stored_bytes(this, Source.ACTIVE) }
    @Test fun active_w3() = runTest { w3_resends_after_a_refused_send(this, Source.ACTIVE) }
    @Test fun active_branch() = runTest { the_retry_uses_the_branch_the_state_proves(this, Source.ACTIVE) }
    @Test fun active_next_accepted() = runTest { the_next_message_is_accepted_by_the_peer(this, Source.ACTIVE) }
    @Test fun active_failure_keeps_row() = runTest { a_failed_recovery_keeps_the_row(this, Source.ACTIVE) }

    // ── INITIATOR PENDING REUSE ────────────────────────────────────────────

    @Test fun pending_reuse_w1() = runTest { w1_adopts_the_uncommitted_step(this, Source.PENDING_REUSE) }
    @Test fun pending_reuse_w2() = runTest { w2_resends_the_stored_bytes(this, Source.PENDING_REUSE) }
    @Test fun pending_reuse_w3() = runTest { w3_resends_after_a_refused_send(this, Source.PENDING_REUSE) }
    @Test fun pending_reuse_branch() = runTest { the_retry_uses_the_branch_the_state_proves(this, Source.PENDING_REUSE) }
    @Test fun pending_reuse_next_accepted() = runTest { the_next_message_is_accepted_by_the_peer(this, Source.PENDING_REUSE) }
    @Test fun pending_reuse_failure_keeps_row() = runTest { a_failed_recovery_keeps_the_row(this, Source.PENDING_REUSE) }

    // ── FRESH BOOTSTRAP ────────────────────────────────────────────────────
    // `SessionManager.initiatorBootstrapInMemory` verifies the fetched
    // bundle's signed prekey through the static `SignedPreKeySigner.verify`.
    // Where the libsodium binding does not initialise, these six stop at that
    // check before any outbox code runs. The declared host limitation.

    @Test fun fresh_bootstrap_w1() = runTest { w1_adopts_the_uncommitted_step(this, Source.FRESH_BOOTSTRAP) }
    @Test fun fresh_bootstrap_w2() = runTest { w2_resends_the_stored_bytes(this, Source.FRESH_BOOTSTRAP) }
    @Test fun fresh_bootstrap_w3() = runTest { w3_resends_after_a_refused_send(this, Source.FRESH_BOOTSTRAP) }
    @Test fun fresh_bootstrap_branch() = runTest { the_retry_uses_the_branch_the_state_proves(this, Source.FRESH_BOOTSTRAP) }
    @Test fun fresh_bootstrap_next_accepted() = runTest { the_next_message_is_accepted_by_the_peer(this, Source.FRESH_BOOTSTRAP) }
    @Test fun fresh_bootstrap_failure_keeps_row() = runTest { a_failed_recovery_keeps_the_row(this, Source.FRESH_BOOTSTRAP) }

    // ── controls ───────────────────────────────────────────────────────────

    /**
     * Negative control for every "the peer accepted it" assertion above: the
     * peer must reject a repeat of an index it has already consumed. Without
     * this, a cell that re-sent a stale envelope would look green.
     */
    @Test
    fun control_the_peer_rejects_a_reused_index() = runTest {
        val s = sender(this, Source.ACTIVE)
        val r = receiver(backgroundScope)
        r.service.startReceiving(); testScheduler.runCurrent()

        s.service.sendMessage(outgoing("m1", "one")).getOrThrow()
        val first = s.transport.sent.single()
        r.transport.deliver(asDeliver(first)); testScheduler.runCurrent()
        // The same ratchet step under a different envelope id: what re-sending
        // an already-consumed envelope would look like to the peer.
        r.transport.deliver(asDeliver(first).copy(messageId = "m1-reused")); testScheduler.runCurrent()

        assertEquals(listOf("one"), r.messages.messages.map { it.plaintextCache })
        assertEquals(ProcessedEnvelopeRepository.Status.FAILED_MAC, r.ledger.rows["m1-reused"])
    }

    /** Negative control for the adoption cells: adoption must commit exactly one step. */
    @Test
    fun control_adoption_commits_exactly_one_step() = runTest {
        val s = sender(this, Source.ACTIVE)
        s.arm(Source.ACTIVE, window = 1)
        assertEquals(0, s.ratchetRepo.state(CONV)!!.sendCount, "the crash left the count where it was")
        assertEquals(1, s.recover())
        assertEquals(1, s.ratchetRepo.state(CONV)!!.sendCount)
        assertEquals(2, s.ratchet.encrypts, "the original encryption and the adoption step")
    }

    // ── D02: failures leave the row where it is ────────────────────────────

    /**
     * A row whose chain no persisted state remembers and whose plaintext is
     * gone cannot be recovered by anything. It must not be deleted, must not
     * be sent as empty text, and must not block the queue for ever.
     */
    @Test
    fun a_row_without_plaintext_on_a_forgotten_chain_is_kept_and_never_sent_empty() = runTest {
        val s = sender(this, Source.ACTIVE)
        val row = s.arm(Source.ACTIVE, window = 3)
        s.ratchetRepo.put(CONV, seedState(rotatedChainKey))   // the chain moved on
        s.messages.mutate(row.id) { it.copy(plaintextCache = null) }

        assertEquals(0, s.recover())

        assertTrue(s.transport.sent.size == 1, "only the original refused send; nothing new on the wire")
        assertEquals(0, s.messages.deleteCalls)
        assertEquals(0, s.messages.replaceCalls)
        val kept = s.row(row.id)
        assertTrue(kept.ciphertext.contentEquals(row.ciphertext), "the bytes are still there for a post-mortem")
        assertEquals(MessageStatus.FAILED, kept.status, "and the user is told, instead of the queue stalling silently")
    }

    /**
     * A row on a forgotten chain WITH its plaintext is re-encrypted in place:
     * one transaction, no delete, and it goes out under its own id.
     */
    @Test
    fun a_row_on_a_forgotten_chain_is_reencrypted_in_place() = runTest {
        val s = sender(this, Source.ACTIVE)
        val row = s.arm(Source.ACTIVE, window = 3)
        s.ratchetRepo.put(CONV, seedState(rotatedChainKey))

        assertEquals(1, s.recover())

        assertEquals(1, s.messages.replaceCalls, "replaced in one transaction")
        assertEquals(0, s.messages.deleteCalls, "never deleted")
        val sent = s.transport.sent.last()
        assertEquals(row.id, sent.messageId, "under its own id")
        assertEquals("queued while broken", payloadTextOf(sent), "with the user's text, not an empty string")
        assertEquals(
            rotatedChainKey.toList(), envelopeOf(sent).ratchetPublicKey.toList(),
            "on the chain that actually exists now",
        )
        assertEquals(MessageStatus.SENT, s.row(row.id).status)
    }

    /** An unparsable envelope holds the queue; the row and its bytes survive. */
    @Test
    fun a_corrupt_envelope_with_no_plaintext_is_kept_and_holds_nothing_hostage() = runTest {
        val s = sender(this, Source.ACTIVE)
        val row = s.arm(Source.ACTIVE, window = 3)
        s.messages.mutate(row.id) { it.copy(ciphertext = byteArrayOf(9, 9, 9), plaintextCache = null) }

        assertEquals(0, s.recover())

        assertEquals(1, s.transport.sent.size, "only the original refused send")
        assertEquals(0, s.messages.deleteCalls)
        val kept = s.row(row.id)
        assertTrue(kept.ciphertext.contentEquals(byteArrayOf(9, 9, 9)), "the bytes are untouched")
        assertEquals(MessageStatus.FAILED, kept.status)
    }

    /** A re-encryption that throws leaves the row and its old envelope intact. */
    @Test
    fun a_failed_reencryption_keeps_the_row() = runTest {
        val s = sender(this, Source.ACTIVE)
        val row = s.arm(Source.ACTIVE, window = 3)
        s.ratchetRepo.put(CONV, seedState(rotatedChainKey))
        s.ratchet.failEncrypt = true

        assertEquals(0, s.recover())

        s.ratchet.failEncrypt = false
        assertEquals(1, s.transport.sent.size, "nothing new was sent")
        assertEquals(0, s.messages.replaceCalls)
        assertEquals(0, s.messages.deleteCalls)
        val kept = s.row(row.id)
        assertTrue(kept.ciphertext.contentEquals(row.ciphertext))
        assertEquals(MessageStatus.QUEUED, kept.status, "still queued, so the next sweep tries again")
    }

    // ── W1C: the decision is re-made per row ───────────────────────────────

    /**
     * The drain must not decide once for the whole sweep: every row is judged
     * against the state as it stands after the previous row's settlement.
     */
    @Test
    fun recovery_state_is_reread_between_rows() = runTest {
        val s = sender(this, Source.ACTIVE)
        s.twoForgottenRows()
        assertEquals(0, s.ratchetRepo.state(CONV)!!.sendCount)

        assertEquals(2, s.recover())

        assertEquals(2, s.ratchetRepo.state(CONV)!!.sendCount, "the state was re-read after each replacement")
        assertEquals(listOf("first", "second"), s.transport.sent.map { it.messageId }, "drained in ratchet order")
        assertEquals(listOf(0, 1), s.transport.sent.map { envelopeOf(it).messageIndex })
        assertTrue(s.messages.messages.all { it.status == MessageStatus.SENT })
    }

    // ── O02 through the service ────────────────────────────────────────────

    /**
     * Two messages written in the same millisecond, the later one with the
     * lexically smaller id. An order built from `createdAt` and the id would
     * reverse them on the wire.
     */
    @Test
    fun a_lexically_smaller_later_id_does_not_overtake() = runTest {
        val s = sender(this, Source.ACTIVE)
        s.messages.fixedCreatedAt = 500L
        s.messages.insertMessage(
            MessageEntity(
                id = "zzz", conversationId = CONV,
                ciphertext = craftedEnvelope(activeChainKey, 0, "first"), plaintextCache = "first",
                sent = true, status = MessageStatus.QUEUED, createdAt = 500L,
            ),
        )
        s.messages.insertMessage(
            MessageEntity(
                id = "aaa", conversationId = CONV,
                ciphertext = craftedEnvelope(activeChainKey, 1, "second"), plaintextCache = "second",
                sent = true, status = MessageStatus.QUEUED, createdAt = 500L,
            ),
        )
        s.ratchetRepo.put(CONV, seedState(activeChainKey).copy(sendCount = 2))

        assertEquals(
            setOf(500L), s.messages.messages.map { it.createdAt }.toSet(),
            "both rows really do share a millisecond",
        )
        assertEquals(
            listOf("aaa", "zzz"),
            s.messages.messages.sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.id },
            "control: createdAt + id really would reverse them",
        )

        assertEquals(2, s.recover())

        assertEquals(listOf("zzz", "aaa"), s.transport.sent.map { it.messageId }, "the ratchet order stands")
        assertEquals(listOf(0, 1), s.transport.sent.map { envelopeOf(it).messageIndex })
    }

    // ── W4 full path ───────────────────────────────────────────────────────

    /**
     * The owner's W4 policy: delivery is preferred over loss, and a retry under
     * the same message id must still produce exactly one user-visible message
     * on the peer, one decrypt, and an ack for each delivery.
     */
    @Test
    fun w4_duplicate_delivery_is_one_message_one_decrypt_two_acks() = runTest {
        val s = sender(this, Source.ACTIVE)
        val r = receiver(backgroundScope)
        r.service.startReceiving(); testScheduler.runCurrent()

        s.arm(Source.ACTIVE, window = 3)
        assertEquals(1, s.recover())
        val refused = s.transport.sent[0]
        val retried = s.transport.sent[1]
        assertEquals(refused.messageId, retried.messageId, "the retry reuses the message id")
        assertEquals(refused.payload, retried.payload, "and the envelope")

        // The relay had in fact accepted the first attempt, and the retry too.
        r.transport.deliver(asDeliver(refused)); testScheduler.runCurrent()
        r.transport.deliver(asDeliver(retried)); testScheduler.runCurrent()

        assertEquals(1, r.messages.messages.size, "exactly one stored, user-visible message")
        assertEquals(1, r.ratchet.decrypts, "the second delivery was not decrypted again")
        assertEquals(
            2, r.transport.ackedDelivers.count { it == refused.messageId },
            "both deliveries were acked, so the relay stops redelivering",
        )
        assertFalse(r.messages.messages.single().plaintextCache.isNullOrEmpty())
    }

    // ── Revision 4: the order settlement may re-encrypt in ─────────────────

    /**
     * A stored envelope on an arbitrary chain, built the way the service
     * builds one. Used to put the repository into states a crash sequence can
     * produce but a single test run cannot reach through the send path alone.
     */
    private fun craftedEnvelope(chainKey: ByteArray, index: Int, text: String): ByteArray =
        json.encodeToString(
            WireFrame.serializer(),
            WireFrame(
                encryptedMessage = EncryptedMessage(
                    ratchetPublicKey = chainKey,
                    messageIndex = index,
                    ciphertext = json.encodeToString(
                        MessagePayload.serializer(),
                        MessagePayload(text = text, sentAt = 500L, senderUsername = "alice"),
                    ).encodeToByteArray(),
                    nonce = ByteArray(24),
                ),
            ),
        ).encodeToByteArray()

    /**
     * Two queued rows whose chain the persisted state no longer remembers,
     * both written in the same millisecond, handed to settlement in the order
     * that would give the wrong answer if list position were trusted.
     */
    private suspend fun Side.twoForgottenRows(
        chainA: ByteArray = ByteArray(32) { 0x77 },
        chainB: ByteArray = ByteArray(32) { 0x77 },
        indexFirst: Int = 0,
        indexSecond: Int = 1,
    ) {
        messages.fixedCreatedAt = 500L
        messages.insertMessage(
            MessageEntity(
                id = "first", conversationId = CONV,
                ciphertext = craftedEnvelope(chainA, indexFirst, "first"), plaintextCache = "first",
                sent = true, status = MessageStatus.QUEUED, createdAt = 500L,
            ),
        )
        messages.insertMessage(
            MessageEntity(
                id = "second", conversationId = CONV,
                ciphertext = craftedEnvelope(chainB, indexSecond, "second"), plaintextCache = "second",
                sent = true, status = MessageStatus.QUEUED, createdAt = 500L,
            ),
        )
        // The chain both rows claim is gone; the session moved on.
        ratchetRepo.put(CONV, seedState(rotatedChainKey))
        messages.reverseOrder = true
    }

    /** O01 — one forgotten chain, chosen by envelope index, not by list order. */
    @Test
    fun a_single_forgotten_chain_is_recovered_in_index_order() = runTest {
        val s = sender(this, Source.ACTIVE)
        s.twoForgottenRows()
        assertEquals(
            listOf("second", "first"), s.messages.getMessages(CONV).map { it.id },
            "control: the repository hands settlement the reversed order",
        )

        assertEquals(2, s.recover())

        assertEquals(
            listOf("first", "second"), s.transport.sent.map { it.messageId },
            "the envelope index decided, not the position in the list",
        )
        assertEquals(
            listOf(0, 1), s.transport.sent.map { envelopeOf(it).messageIndex },
            "and each took the next index on the surviving chain, in that order",
        )
        assertEquals(listOf("first", "second"), s.transport.sent.map { payloadTextOf(it) })
    }

    /** O02 — equal timestamps, reversed repository order, indices 0 and 1. */
    @Test
    fun equal_timestamps_and_reversed_repository_order_still_recover_index_zero_first() = runTest {
        val s = sender(this, Source.ACTIVE)
        s.twoForgottenRows()
        val rows = s.messages.getMessages(CONV)
        assertEquals(setOf(500L), rows.map { it.createdAt }.toSet(), "both rows share a millisecond")
        assertEquals(
            listOf("second", "first"),
            rows.sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.id }.reversed(),
            "control: with equal timestamps the id is the only tiebreak left, and it is not the answer",
        )

        assertEquals(2, s.recover())

        assertEquals(listOf("first", "second"), s.transport.sent.map { it.messageId })
    }

    /** O03 — two forgotten chains: no provable order, so nothing is touched. */
    @Test
    fun two_forgotten_chains_stop_settlement_without_mutation_or_send() = runTest {
        val s = sender(this, Source.ACTIVE)
        s.twoForgottenRows(chainA = ByteArray(32) { 0x77 }, chainB = ByteArray(32) { 0x66 })
        val before = s.messages.getMessages(CONV).map { it.id to it.ciphertext.toList() }
        val replacesBefore = s.messages.replaceCalls

        assertEquals(0, s.recover(), "nothing was attempted")

        assertTrue(s.transport.sent.isEmpty(), "nothing went on the wire")
        assertEquals(replacesBefore, s.messages.replaceCalls, "no row was rewritten")
        assertEquals(0, s.messages.deleteCalls)
        assertEquals(before, s.messages.getMessages(CONV).map { it.id to it.ciphertext.toList() }, "every row is untouched")
        assertTrue(s.messages.getMessages(CONV).all { it.status == MessageStatus.QUEUED }, "and none was marked failed")
    }

    /** O04a — two rows claiming one step. */
    @Test
    fun a_duplicate_index_stops_settlement_without_mutation_or_send() = runTest {
        val s = sender(this, Source.ACTIVE)
        s.twoForgottenRows(indexFirst = 3, indexSecond = 3)
        val before = s.messages.getMessages(CONV).map { it.id to it.ciphertext.toList() }

        assertEquals(0, s.recover())

        assertTrue(s.transport.sent.isEmpty())
        assertEquals(0, s.messages.replaceCalls)
        assertEquals(before, s.messages.getMessages(CONV).map { it.id to it.ciphertext.toList() })
        assertTrue(s.messages.getMessages(CONV).all { it.status == MessageStatus.QUEUED })
    }

    /** O04b — an unparsable row sharing the set with another unresolved one. */
    @Test
    fun an_unparsable_row_among_others_stops_settlement_without_mutation_or_send() = runTest {
        val s = sender(this, Source.ACTIVE)
        s.twoForgottenRows()
        s.messages.mutate("second") { it.copy(ciphertext = byteArrayOf(9, 9, 9), plaintextCache = null) }
        val before = s.messages.getMessages(CONV).map { it.id to it.ciphertext.toList() }

        assertEquals(0, s.recover())

        assertTrue(s.transport.sent.isEmpty(), "nothing went on the wire")
        assertEquals(0, s.messages.replaceCalls, "no row was rewritten")
        assertEquals(before, s.messages.getMessages(CONV).map { it.id to it.ciphertext.toList() })
        assertTrue(
            s.messages.getMessages(CONV).all { it.status == MessageStatus.QUEUED },
            "not even the unparsable row may be marked failed while another is unresolved",
        )
    }

    /**
     * The single-row case stays workable: an unparsable row with no plaintext,
     * alone, is marked failed so the queue is not blocked for ever.
     */
    @Test
    fun a_lone_unparsable_row_is_still_resolved() = runTest {
        val s = sender(this, Source.ACTIVE)
        val row = s.arm(Source.ACTIVE, window = 3)
        s.messages.mutate(row.id) { it.copy(ciphertext = byteArrayOf(9, 9, 9), plaintextCache = null) }

        assertEquals(0, s.recover())

        assertEquals(MessageStatus.FAILED, s.row(row.id).status)
        assertEquals(1, s.transport.sent.size, "only the original refused send")
    }

    /**
     * Negative control for the whole ordering contract: if settlement picked
     * the first row the repository returned, the reversed fixture would send
     * "second" first. This asserts the fixture really is reversed, so the
     * tests above cannot pass by accident.
     */
    @Test
    fun control_the_fixture_really_reverses_repository_order() = runTest {
        val s = sender(this, Source.ACTIVE)
        s.twoForgottenRows()
        val repoOrder = s.messages.getMessages(CONV).map { it.id }
        assertEquals(listOf("second", "first"), repoOrder)
        val indexOrder = s.messages.getMessages(CONV)
            .sortedBy { envelopeOfBytes(it.ciphertext).messageIndex }
            .map { it.id }
        assertEquals(listOf("first", "second"), indexOrder)
        assertNotEquals(repoOrder, indexOrder, "the two orders must disagree or the control is vacuous")
    }

    // ── Revision 5: the shared outbound barrier ────────────────────────────

    @Test
    fun same_conversation_outbound_callers_serialize_through_transport_completion() = runTest {
        val s = sender(this, Source.ACTIVE)
        val firstAtTransport = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var transportCalls = 0
        s.transport.beforeSend = {
            if (transportCalls++ == 0) {
                firstAtTransport.complete(Unit)
                releaseFirst.await()
            }
        }

        val first = async { s.service.sendMessage(outgoing("first", "first")) }
        firstAtTransport.await()
        val second = async { s.service.sendMessage(outgoing("second", "second")) }
        testScheduler.runCurrent()

        assertEquals(1, s.ratchet.encrypts, "the second caller must not encrypt while the first owns the outer permit")
        releaseFirst.complete(Unit)
        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)
        assertEquals(listOf("first", "second"), s.transport.sent.map { it.messageId })
        assertEquals(listOf(0, 1), s.transport.sent.map { envelopeOf(it).messageIndex })
    }

    @Test
    fun text_message_does_not_encrypt_until_predecessor_drain_succeeds() = runTest {
        val s = sender(this, Source.ACTIVE)
        s.arm(Source.ACTIVE, window = 1, id = "predecessor")
        s.transport.sendShouldSucceed = false

        val blocked = s.service.sendMessage(outgoing("successor", "successor"))

        assertTrue(blocked.isFailure)
        assertNull(s.messages.getMessageById("successor"), "a refused drain must not create a successor row")
        assertEquals(
            2,
            s.ratchet.encrypts,
            "only the original encryption and W1 adoption ran; the successor took no ratchet step",
        )
        assertEquals(listOf("predecessor"), s.transport.sent.map { it.messageId })

        s.transport.sendShouldSucceed = true
        assertTrue(s.service.sendMessage(outgoing("successor", "successor")).isSuccess)
        assertEquals(listOf("predecessor", "predecessor", "successor"), s.transport.sent.map { it.messageId })
        assertEquals(MessageStatus.SENT, s.row("successor").status)
    }

    @Test
    fun inbound_delivery_progresses_while_outbound_holds_only_the_outer_barrier() = runTest {
        val local = sender(backgroundScope, Source.ACTIVE)
        val peer = receiver(backgroundScope)
        local.service.startReceiving()
        testScheduler.runCurrent()

        val outboundAtTransport = CompletableDeferred<Unit>()
        val releaseOutbound = CompletableDeferred<Unit>()
        local.transport.beforeSend = {
            outboundAtTransport.complete(Unit)
            releaseOutbound.await()
        }
        val outbound = async { local.service.sendMessage(outgoing("blocked-outbound", "outbound")) }
        outboundAtTransport.await()

        peer.service.sendMessage(
            OutgoingMessage(
                id = "inbound",
                conversationId = CONV,
                recipientPublicKeyHex = ME,
                text = "inbound while outer is held",
            ),
        ).getOrThrow()
        val peerSend = peer.transport.sent.single()
        local.transport.deliver(
            RelayMessage.Deliver(
                from = PEER,
                sealedSender = "",
                payload = peerSend.payload,
                messageId = peerSend.messageId,
            ),
        )
        testScheduler.runCurrent()

        assertEquals(
            "inbound while outer is held",
            local.messages.getMessageById("inbound")?.plaintextCache,
            "inbound owns only the inner mutex and must not wait for the outbound transport",
        )
        assertEquals(listOf("inbound"), local.transport.ackedDelivers)

        releaseOutbound.complete(Unit)
        assertTrue(outbound.await().isSuccess)
    }

    @Test
    fun retry_waiting_messages_uses_the_under_permit_core_without_recursive_locking() = runTest {
        val s = sender(this, Source.ACTIVE)
        s.messages.insertMessage(
            MessageEntity(
                id = "waiting",
                conversationId = CONV,
                ciphertext = byteArrayOf(),
                plaintextCache = "retry me",
                sent = true,
                status = MessageStatus.WAITING_FOR_RECIPIENT_BUNDLE,
                createdAt = 1L,
            ),
        )

        assertEquals(1, s.service.retryWaitingMessages("test").getOrThrow())
        assertEquals(listOf("waiting"), s.transport.sent.map { it.messageId })
        assertEquals(MessageStatus.SENT, s.row("waiting").status)
    }

    @Test
    fun an_exception_releases_the_outbound_barrier() = runTest {
        val s = sender(this, Source.ACTIVE)
        var first = true
        s.transport.beforeSend = {
            if (first) {
                first = false
                throw IllegalStateException("injected transport crash")
            }
        }

        assertTrue(s.service.sendMessage(outgoing("broken", "broken")).isFailure)
        s.transport.beforeSend = {}
        assertTrue(s.service.sendMessage(outgoing("next", "next")).isSuccess)
        assertEquals(2, s.ratchet.encrypts, "the second caller acquired the released permit")
        assertEquals(listOf("broken", "next"), s.transport.sent.map { it.messageId })
    }

    @Test
    fun call_and_group_controls_fail_before_new_ratchet_mutation_when_drain_is_blocked() = runTest {
        val s = sender(this, Source.ACTIVE)
        s.transport.sendShouldSucceed = false
        s.service.sendMessage(outgoing("predecessor", "queued")).getOrThrow()
        val encryptsBeforeControls = s.ratchet.encrypts

        val call = s.service.sendCallSignal(
            PEER,
            MessagePayload(type = MessagePayload.TYPE_CALL_HANGUP, callId = "call"),
        )
        val group = s.service.sendGroupControlMessage(
            PEER,
            MessagePayload(type = MessagePayload.TYPE_GROUP_LEAVE, groupId = "group"),
        )

        assertTrue(call.exceptionOrNull() is OutboundNotAttemptedException)
        assertTrue(group.exceptionOrNull() is OutboundNotAttemptedException)
        assertEquals(encryptsBeforeControls, s.ratchet.encrypts, "neither control may consume a new ratchet step")
        assertTrue(s.transport.sent.all { it.messageId == "predecessor" }, "only predecessor drain attempts are allowed")
    }

    private fun assertPredecessorThenImmediateOperation(s: Side) {
        assertEquals(2, s.transport.sent.size, "predecessor and immediate operation must both be submitted")
        assertEquals("predecessor", s.transport.sent.first().messageId)
        assertEquals(listOf(0, 1), s.transport.sent.map { envelopeOf(it).messageIndex })
    }

    @Test
    fun receipt_call_group_and_local_controls_follow_the_w1_predecessor() = runTest {
        suspend fun exercise(operation: suspend (Side) -> Unit) {
            val s = sender(this, Source.ACTIVE)
            s.arm(Source.ACTIVE, window = 1, id = "predecessor")
            operation(s)
            assertPredecessorThenImmediateOperation(s)
        }

        exercise { side ->
            side.messages.insertMessage(
                MessageEntity(
                    id = "unread",
                    conversationId = CONV,
                    ciphertext = byteArrayOf(),
                    plaintextCache = "incoming",
                    sent = false,
                    status = MessageStatus.DELIVERED,
                    createdAt = 2L,
                ),
            )
            side.service.markConversationRead(CONV, PEER, sendReceipt = true)
            assertEquals(MessageStatus.READ, side.row("unread").status)
        }
        exercise { side ->
            assertTrue(
                side.service.sendCallSignal(
                    PEER,
                    MessagePayload(type = MessagePayload.TYPE_CALL_HANGUP, callId = "call"),
                ).isSuccess,
            )
        }
        exercise { side ->
            assertTrue(
                side.service.sendGroupControlMessage(
                    PEER,
                    MessagePayload(type = MessagePayload.TYPE_GROUP_LEAVE, groupId = "group"),
                ).isSuccess,
            )
        }
        exercise { side ->
            side.messages.insertMessage(targetMessage("delete-target", "delete me"))
            assertTrue(side.service.deleteMessageForBoth("delete-target", CONV, PEER).isSuccess)
            assertTrue(side.messages.getMessageById("delete-target") == null)
        }
        exercise { side ->
            assertTrue(side.service.sendDisappearingTimerUpdate(30L, CONV, PEER).isSuccess)
        }
        exercise { side ->
            side.messages.insertMessage(targetMessage("edit-target", "before"))
            assertTrue(side.service.editMessageForBoth("edit-target", "after", CONV, PEER).isSuccess)
            assertEquals("after", side.row("edit-target").plaintextCache)
        }
        exercise { side ->
            assertTrue(side.service.sendReaction("reaction-target", CONV, PEER, "ok").isSuccess)
        }
        exercise { side ->
            side.messages.insertMessage(targetMessage("pin-target", "pin me"))
            assertTrue(side.service.pinMessageForBoth("pin-target", CONV, PEER, pinned = true).isSuccess)
            assertTrue(side.row("pin-target").pinned)
        }
    }

    @Test
    fun barrier_failure_preserves_local_control_state_and_drops_receipt_best_effort() = runTest {
        val s = sender(this, Source.ACTIVE)
        s.arm(Source.ACTIVE, window = 1, id = "predecessor")
        s.messages.insertMessage(targetMessage("target", "before"))
        s.messages.insertMessage(
            targetMessage("unread", "incoming").copy(sent = false, status = MessageStatus.DELIVERED),
        )
        s.transport.sendShouldSucceed = false
        val encryptsBefore = s.ratchet.encrypts

        s.service.markConversationRead(CONV, PEER, sendReceipt = true)
        assertEquals(MessageStatus.READ, s.row("unread").status, "local read state remains best-effort independent")
        assertEquals(
            encryptsBefore + 1,
            s.ratchet.encrypts,
            "only W1 adoption may advance; the receipt itself must not encrypt after a failed drain",
        )
        val encryptsAfterSettlement = s.ratchet.encrypts

        val delete = s.service.deleteMessageForBoth("target", CONV, PEER)
        val edit = s.service.editMessageForBoth("target", "after", CONV, PEER)
        val pin = s.service.pinMessageForBoth("target", CONV, PEER, pinned = true)
        assertTrue(delete.exceptionOrNull() is OutboundNotAttemptedException)
        assertTrue(edit.exceptionOrNull() is OutboundNotAttemptedException)
        assertTrue(pin.exceptionOrNull() is OutboundNotAttemptedException)
        assertEquals("before", s.row("target").plaintextCache)
        assertFalse(s.row("target").pinned)
        assertEquals(0, s.messages.updateTextCalls)
        assertEquals(0, s.messages.pinCalls)
        assertEquals(encryptsAfterSettlement, s.ratchet.encrypts)
        assertTrue(s.transport.sent.all { it.messageId == "predecessor" })
    }

    @Test
    fun legacy_voice_follows_predecessor_and_creates_no_ghost_row_on_barrier_failure() = runTest {
        val success = sender(this, Source.ACTIVE)
        success.arm(Source.ACTIVE, window = 1, id = "predecessor")
        assertTrue(success.service.sendAudio(CONV, byteArrayOf(1, 2, 3), 20L, "audio/ogg").isSuccess)
        assertPredecessorThenImmediateOperation(success)

        val blocked = sender(this, Source.ACTIVE)
        blocked.arm(Source.ACTIVE, window = 1, id = "predecessor")
        blocked.transport.sendShouldSucceed = false
        val rowsBefore = blocked.messages.messages.map { it.id }
        val encryptsBefore = blocked.ratchet.encrypts
        val result = blocked.service.sendAudio(CONV, byteArrayOf(1, 2, 3), 20L, "audio/ogg")

        assertTrue(result.isFailure)
        assertEquals(rowsBefore, blocked.messages.messages.map { it.id })
        assertEquals(
            encryptsBefore + 1,
            blocked.ratchet.encrypts,
            "W1 adoption is allowed, but the voice chunk must not consume another step",
        )
        assertTrue(blocked.transport.sent.all { it.messageId == "predecessor" })
    }

    private fun targetMessage(id: String, text: String) = MessageEntity(
        id = id,
        conversationId = CONV,
        ciphertext = byteArrayOf(),
        plaintextCache = text,
        sent = true,
        status = MessageStatus.SENT,
        createdAt = 3L,
    )

    @Test
    fun control_transport_false_is_reported_without_claiming_delivery() = runTest {
        val s = sender(this, Source.ACTIVE)
        s.transport.sendShouldSucceed = false

        val outcome = s.service.sendCallSignal(
            PEER,
            MessagePayload(type = MessagePayload.TYPE_CALL_HANGUP, callId = "call"),
        )

        assertTrue(outcome.exceptionOrNull() is OutboundSubmissionException)
        assertEquals(1, s.ratchet.encrypts, "the control was encrypted before transport queued/refused it")
        assertEquals(1, s.transport.sent.size, "one transport submission was attempted")
    }

    @Test
    fun sealed_sender_failure_is_not_attempted_and_does_not_advance_the_ratchet() = runTest {
        val s = sender(this, Source.ACTIVE) { _, _ ->
            throw IllegalStateException("injected sealed-sender failure")
        }

        val outcome = s.service.sendGroupControlMessage(
            PEER,
            MessagePayload(type = MessagePayload.TYPE_GROUP_LEAVE, groupId = "group"),
        )

        assertTrue(outcome.exceptionOrNull() is OutboundNotAttemptedException)
        assertEquals(0, s.ratchet.encrypts)
        assertTrue(s.transport.sent.isEmpty())
    }

    @Test
    fun cancellation_releases_the_outbound_barrier() = runTest {
        val s = sender(this, Source.ACTIVE)
        var cancelFirst = true
        s.transport.beforeSend = {
            if (cancelFirst) {
                cancelFirst = false
                throw CancellationException("cancel control submission")
            }
        }

        assertFailsWith<CancellationException> {
            s.service.sendCallSignal(
                PEER,
                MessagePayload(type = MessagePayload.TYPE_CALL_HANGUP, callId = "cancelled"),
            )
        }
        s.transport.beforeSend = {}
        val next = s.service.sendCallSignal(
            PEER,
            MessagePayload(type = MessagePayload.TYPE_CALL_HANGUP, callId = "next"),
        )

        assertTrue(next.isSuccess, "the caller after cancellation must acquire the released barrier")
        assertEquals(2, s.ratchet.encrypts)
    }

    @Test
    fun voice_v2_upload_holds_neither_barrier_and_manifest_acquires_it_after_upload() = runTest {
        val uploader = PausingVoiceUploader()
        val s = sender(this, Source.ACTIVE, voiceUploadSender = uploader)

        assertTrue(
            s.service.sendAudio(CONV, byteArrayOf(1, 2, 3), 750L, "audio/ogg").isSuccess,
        )
        testScheduler.runCurrent()
        uploader.entered.await()

        val duringUpload = async {
            s.service.sendCallSignal(
                PEER,
                MessagePayload(type = MessagePayload.TYPE_CALL_HANGUP, callId = "during-upload"),
            )
        }
        testScheduler.runCurrent()
        assertTrue(duringUpload.isCompleted, "voice upload must hold neither outer nor inner mutex")
        assertTrue(duringUpload.await().isSuccess)
        assertEquals(1, s.transport.sent.size, "the concurrent call signal submits while upload is paused")

        uploader.release.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(2, s.transport.sent.size, "the voice manifest submits only after upload completes")
        assertEquals(
            listOf(0, 1),
            s.transport.sent.map { envelopeOf(it).messageIndex },
            "the manifest acquires the outer barrier and follows the concurrent signal",
        )
    }

    @Test
    fun voice_v2_manifest_barrier_failure_marks_existing_row_failed_without_encrypt_or_send() = runTest {
        val s = sender(this, Source.ACTIVE, voiceUploadSender = ImmediateVoiceUploader())
        val predecessor = s.arm(Source.ACTIVE, window = 1, id = "predecessor")
        s.messages.insertMessage(predecessor.copy(id = "duplicate-index"))
        val encryptsBeforeVoice = s.ratchet.encrypts

        assertTrue(
            s.service.sendAudio(CONV, byteArrayOf(4, 5, 6), 900L, "audio/ogg").isSuccess,
        )
        testScheduler.runCurrent()

        val voiceRow = s.messages.messages.single { it.plaintextCache?.startsWith("[AUDIO:") == true }
        assertEquals(MessageStatus.FAILED, voiceRow.status)
        assertEquals(encryptsBeforeVoice, s.ratchet.encrypts, "manifest failure cannot advance the ratchet")
        assertTrue(s.transport.sent.isEmpty(), "manifest failure cannot submit anything")
    }

    @Test
    fun cancelling_a_paused_voice_v2_upload_does_not_deadlock_or_keep_the_barrier() = runTest {
        val uploader = PausingVoiceUploader()
        val s = sender(this, Source.ACTIVE, voiceUploadSender = uploader)

        assertTrue(
            s.service.sendAudio(CONV, byteArrayOf(7, 8, 9), 1_100L, "audio/ogg").isSuccess,
        )
        testScheduler.runCurrent()
        uploader.entered.await()
        val localVoiceId = s.messages.messages.single { it.status == MessageStatus.UPLOADING }.id

        val cancelled = async { s.service.cancelVoiceUpload(CONV, localVoiceId) }
        testScheduler.runCurrent()
        assertTrue(cancelled.isCompleted, "cancellation must not wait for either conversation mutex")
        assertTrue(cancelled.await().isSuccess)
        assertTrue(s.messages.messages.none { it.id == localVoiceId })

        val next = s.service.sendCallSignal(
            PEER,
            MessagePayload(type = MessagePayload.TYPE_CALL_HANGUP, callId = "after-cancel"),
        )
        assertTrue(next.isSuccess, "a caller after voice cancellation must acquire the barrier")
        assertEquals(1, s.transport.sent.size)
    }
}
