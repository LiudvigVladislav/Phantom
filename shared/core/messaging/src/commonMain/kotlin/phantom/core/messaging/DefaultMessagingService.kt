// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.benasher44.uuid.uuid4
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import phantom.core.crypto.DoubleRatchet
import phantom.core.crypto.DhKeyPair
import phantom.core.crypto.MessagePadding
import phantom.core.crypto.SealedSender
import phantom.core.identity.IdentityRecord
import phantom.core.storage.ConversationEntity
import phantom.core.storage.ConversationRepository
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageRepository
import phantom.core.storage.MessageStatus
import phantom.core.storage.ReactionRepository
import phantom.core.storage.TrustTier
import phantom.core.transport.RelayMessage
import phantom.core.transport.RelayTransport

class DefaultMessagingService(
    private val identity: IdentityRecord,
    private val localKeyPair: DhKeyPair,
    private val ratchet: DoubleRatchet,
    private val sessionManager: SessionManager,
    private val transport: RelayTransport,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val reactionRepository: ReactionRepository? = null,
) : MessagingService {

    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val incomingMessages: Flow<IncomingMessage> = _incomingMessages.asSharedFlow()

    @Volatile private var receiving = false

    // Guard against duplicate in-flight delivery: if startReceiving() is somehow
    // called twice a SharedFlow delivers to both collectors simultaneously. The
    // DB-level INSERT OR IGNORE is the last line of defence, but it fires after
    // ratchet.decrypt which irreversibly advances chain state. This set prevents
    // two coroutines from entering handleDeliver for the same messageId at all.
    private val processingLock = Mutex()
    private val activeProcessing = mutableSetOf<String>()

    // Per-conversation Mutex protects every load → encrypt/decrypt → save sequence
    // on the Double Ratchet state. Without this lock two concurrent sendMessage()
    // calls (e.g. user typing + automatic profile-card sync) could both load the
    // same starting RatchetState, both derive the same chain key, and both ship
    // a ciphertext encrypted with that key. The receiver advances its chain on
    // the first message and then fails MAC verification on the second — the
    // exact symptom reported in the 2026-04-25 QA pass.
    private val sessionMutexesLock = Mutex()
    private val sessionMutexes = mutableMapOf<String, Mutex>()

    private suspend fun mutexFor(conversationId: String): Mutex =
        sessionMutexesLock.withLock {
            sessionMutexes.getOrPut(conversationId) { Mutex() }
        }

    /**
     * Loads the ratchet state for the conversation, encrypts [plaintext] with the
     * Double Ratchet, and persists the advanced state — all under the per-
     * conversation mutex so concurrent callers cannot derive the same chain key.
     * Returns the wire-ready EncryptedMessage. The DH keypair argument is
     * threaded through so this helper stays usable from outgoing call sites
     * that already have it on the stack.
     */
    private suspend fun encryptUnderLock(
        conversationId: String,
        recipientPublicKeyHex: String,
        plaintext: ByteArray,
    ): phantom.core.crypto.EncryptedMessage =
        mutexFor(conversationId).withLock {
            val state = sessionManager.getOrCreateSession(
                conversationId = conversationId,
                localIdentityKeyPair = localKeyPair,
                remoteIdentityPublicKeyHex = recipientPublicKeyHex,
            )
            val (newState, encrypted) = ratchet.encrypt(state, plaintext)
            sessionManager.saveSession(conversationId, newState)
            encrypted
        }

    /**
     * Platform hook for local push notifications.
     * Set by the Android AppContainer after [initMessaging]; null on other platforms.
     * Called on the scope's thread after a message is successfully decrypted and stored.
     * The callback must be non-blocking (fire-and-forget on the platform side).
     *
     * Parameters: conversationId, senderName, preview, senderPublicKeyHex
     */
    @Volatile var onNewMessageNotification: ((conversationId: String, senderName: String, preview: String, senderPublicKeyHex: String) -> Unit)? = null

    /**
     * Optional group messaging delegate. When set, any incoming payload whose type is in
     * [MessagePayload.GROUP_TYPES] is forwarded here instead of being processed as a 1:1
     * message. Set by AppContainer after identity is loaded.
     */
    @Volatile var groupMessagingService: GroupMessagingService? = null

    /**
     * Platform hook for call signalling.
     * Set by the Android AppContainer after [initMessaging].
     * Called for any incoming payload whose type is in [MessagePayload.CALL_TYPES].
     * The callback must be non-blocking (delegate processing to a coroutine scope).
     *
     * Parameters: payload, senderPublicKeyHex
     */
    @Volatile var onCallMessage: ((MessagePayload, String) -> Unit)? = null

    override suspend fun sendMessage(message: OutgoingMessage): Result<Unit> = runCatching {
        val payload = json.encodeToString(
            MessagePayload(
                text = message.text,
                sentAt = Clock.System.now().toEpochMilliseconds(),
                senderUsername = identity.username,
            )
        ).encodeToByteArray()

        val encrypted = encryptUnderLock(
            conversationId = message.conversationId,
            recipientPublicKeyHex = message.recipientPublicKeyHex,
            plaintext = payload,
        )

        val ciphertext = json.encodeToString(encrypted).encodeToByteArray()

        val insertedAtMs = Clock.System.now().toEpochMilliseconds()
        val outgoingTimerSecs = conversationRepository.getDisappearingTimer(message.conversationId)
        val outgoingExpiresAtMs = if (outgoingTimerSecs > 0L) insertedAtMs + outgoingTimerSecs * 1_000L else null

        messageRepository.insertMessage(
            MessageEntity(
                id = message.id,
                conversationId = message.conversationId,
                ciphertext = ciphertext,
                plaintextCache = message.text,
                sent = true,
                status = MessageStatus.QUEUED,
                createdAt = insertedAtMs,
                expiresAtMs = outgoingExpiresAtMs,
            )
        )

        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val sealedSenderB64 = Base64.encode(
            SealedSender.seal(
                fromPubKeyHex = identity.publicKeyHex,
                toPublicKeyBytes = hexToBytes(message.recipientPublicKeyHex),
            )
        )

        val paddedCiphertext = MessagePadding.pad(ciphertext)

        val sent = transport.send(
            RelayMessage.Send(
                to = message.recipientPublicKeyHex,
                from = "",
                sealedSender = sealedSenderB64,
                payload = paddedCiphertext.encodeBase64(),
                messageId = message.id,
            )
        )

        val newStatus = if (sent) MessageStatus.SENT else MessageStatus.QUEUED
        messageRepository.updateStatus(message.id, newStatus)

        conversationRepository.upsertConversation(
            conversationRepository.getConversation(message.conversationId)
                ?.copy(
                    lastMessagePreview = previewText(message.text),
                    lastMessageAt = Clock.System.now().toEpochMilliseconds(),
                )
                ?: ConversationEntity(
                    id = message.conversationId,
                    theirUsername = message.recipientPublicKeyHex.take(8),
                    theirPublicKeyHex = message.recipientPublicKeyHex,
                    lastMessagePreview = previewText(message.text),
                    lastMessageAt = Clock.System.now().toEpochMilliseconds(),
                    unreadCount = 0,
                    trustTier = TrustTier.TRUSTED,
                    blocked = false,
                )
        )
    }

    override suspend fun startReceiving() {
        if (receiving) return
        receiving = true
        transport.incoming
            .onEach { deliver -> handleDeliver(deliver) }
            .launchIn(scope)

        transport.acks
            .onEach { ack ->
                val newStatus = when (ack.status) {
                    "delivered" -> MessageStatus.DELIVERED
                    else        -> MessageStatus.RELAYED
                }
                messageRepository.updateStatus(ack.messageId, newStatus)
            }
            .launchIn(scope)

        transport.readReceipts
            .onEach { receipt ->
                messageRepository.updateStatus(receipt.messageId, MessageStatus.READ)
            }
            .launchIn(scope)
    }

    private suspend fun handleDeliver(deliver: RelayMessage.Deliver) {
        val claimed = processingLock.withLock {
            if (deliver.messageId in activeProcessing) false
            else { activeProcessing.add(deliver.messageId); true }
        }
        if (!claimed) {
            messagingLog(
                MessagingLogLevel.WARN,
                "Duplicate in-flight delivery skipped: id=${deliver.messageId.take(12)}…",
            )
            return
        }
        try {
        messagingLog(
            MessagingLogLevel.INFO,
            "handleDeliver start: id=${deliver.messageId.take(12)}… sealed=${deliver.sealedSender.isNotEmpty()} payloadBytes=${deliver.payload.length}",
        )
        runCatching {
            // Recover sender identity: sealed sender hides `from` from the relay.
            @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
            val senderPubKeyHex = if (deliver.sealedSender.isNotEmpty()) {
                val sealedBytes = Base64.decode(deliver.sealedSender)
                SealedSender.unseal(sealedBytes, localKeyPair.privateKey.bytes)
                    ?: run {
                        messagingLog(
                            MessagingLogLevel.WARN,
                            "Sealed Sender unseal returned null — dropping envelope id=${deliver.messageId.take(12)}…",
                        )
                        return@runCatching
                    }
            } else {
                deliver.from // Backward compat: relay-supplied `from` for non-sealed messages.
            }
            messagingLog(
                MessagingLogLevel.INFO,
                "Sender identified: ${senderPubKeyHex.take(16)}…",
            )

            // Idempotent receive. The relay re-delivers every still-stored
            // envelope on reconnect; without this guard a duplicate would
            // fall into ratchet.decrypt with a chain key that has already
            // advanced past it (MAC failure) and the message would never get
            // ack-deliver'd, so the relay would replay it forever.
            // Sending ack-deliver here breaks the loop cleanly.
            val alreadyProcessed = messageRepository.getMessageById(deliver.messageId) != null
            if (alreadyProcessed) {
                messagingLog(
                    MessagingLogLevel.INFO,
                    "Duplicate envelope (already in DB): id=${deliver.messageId.take(12)}… — sending ack-deliver and skipping",
                )
                transport.sendDeliveryAck(deliver.messageId)
                return@runCatching
            }

            // Unpad ISO 7816-4 padding applied by the sender to hide message length.
            val ciphertext = MessagePadding.unpad(deliver.payload.decodeBase64Bytes())
            val encrypted = json.decodeFromString<phantom.core.crypto.EncryptedMessage>(
                ciphertext.decodeToString()
            )

            val conversationId = deriveConversationId(senderPubKeyHex)
            // The same per-conversation mutex protects the receive path so that an
            // outgoing send (which advances the local sending chain and saves) cannot
            // race with an inbound decrypt. Receive itself is already serialised by
            // transport.incoming.onEach, but a parallel sendMessage on the same
            // conversation could still observe a half-saved state.
            val mutex = mutexFor(conversationId)
            val plainBytes = mutex.withLock {
                val state = sessionManager.getOrCreateSession(
                    conversationId = conversationId,
                    localIdentityKeyPair = localKeyPair,
                    remoteIdentityPublicKeyHex = senderPubKeyHex,
                )
                messagingLog(
                    MessagingLogLevel.INFO,
                    "Session loaded: conv=${conversationId.take(24)}… decrypting…",
                )

                val (newState, decrypted) = ratchet.decrypt(state, encrypted)
                sessionManager.saveSession(conversationId, newState)
                messagingLog(
                    MessagingLogLevel.INFO,
                    "Decrypt OK: plaintextBytes=${decrypted.size}",
                )
                decrypted
            }

            val payload = json.decodeFromString<MessagePayload>(plainBytes.decodeToString())
            messagingLog(
                MessagingLogLevel.INFO,
                "Payload parsed: type=${payload.type} textLen=${payload.text.length}",
            )

            // Route group-related messages to GroupMessagingService before 1:1 handling.
            if (payload.type in MessagePayload.GROUP_TYPES) {
                groupMessagingService?.handleIncoming(payload, senderPubKeyHex)
                return@runCatching
            }

            // Route call-signalling messages to CallManager; never store as chat messages.
            if (payload.type in MessagePayload.CALL_TYPES) {
                onCallMessage?.invoke(payload, senderPubKeyHex)
                return@runCatching
            }

            // Handle control messages — do not store as chat messages
            if (payload.type == MessagePayload.TYPE_DELETE && payload.targetMessageId.isNotEmpty()) {
                messageRepository.deleteMessage(payload.targetMessageId)
                _incomingMessages.emit(
                    IncomingMessage(
                        id = payload.targetMessageId,
                        conversationId = conversationId,
                        senderPublicKeyHex = senderPubKeyHex,
                        text = "",
                        receivedAt = Clock.System.now().toEpochMilliseconds(),
                    )
                )
                return@runCatching
            }
            if (payload.type == MessagePayload.TYPE_EDIT && payload.targetMessageId.isNotEmpty()) {
                messageRepository.updateMessageText(payload.targetMessageId, payload.text)
                _incomingMessages.emit(
                    IncomingMessage(
                        id = payload.targetMessageId,
                        conversationId = conversationId,
                        senderPublicKeyHex = senderPubKeyHex,
                        text = payload.text,
                        receivedAt = Clock.System.now().toEpochMilliseconds(),
                    )
                )
                return@runCatching
            }
            if (payload.type == MessagePayload.TYPE_DISAPPEARING_TIMER) {
                val secs = payload.disappearingTimerSecs ?: 0L
                // Only apply non-zero timers from peers — peer cannot silently disable
                // disappearing messages on the local device (local user controls Off via UI).
                if (secs > 0L) {
                    conversationRepository.setDisappearingTimer(conversationId, secs)
                }
                return@runCatching
            }
            if (payload.type == MessagePayload.TYPE_REACTION && payload.targetMessageId.isNotEmpty()) {
                val em = payload.emoji ?: return@runCatching
                val repo = reactionRepository ?: return@runCatching
                if (em.isEmpty()) {
                    repo.deleteReaction(payload.targetMessageId, senderPubKeyHex)
                } else {
                    repo.upsertReaction(
                        messageId = payload.targetMessageId,
                        senderKeyHex = senderPubKeyHex,
                        emoji = em,
                        createdAt = Clock.System.now().toEpochMilliseconds(),
                    )
                }
                return@runCatching
            }
            if (payload.type == MessagePayload.TYPE_PIN && payload.targetMessageId.isNotEmpty()) {
                // Whoever sent the wire message is the pinner. Store their
                // pubkey so the banner can render "Pinned by <username>".
                // On unpin (pinned=false) we clear the column to null so the
                // next pin gets a fresh attribution.
                val pinnedFlag = payload.pinned ?: false
                messageRepository.pinMessage(
                    messageId = payload.targetMessageId,
                    pinned = pinnedFlag,
                    pinnedByPubkey = if (pinnedFlag) senderPubKeyHex else null,
                )
                return@runCatching
            }
            if (payload.type == MessagePayload.TYPE_KEY_ROTATION) {
                val existing = conversationRepository.getConversation(conversationId)
                if (existing != null && existing.theirPublicKeyHex != senderPubKeyHex) {
                    conversationRepository.upsertConversation(
                        existing.copy(
                            theirPublicKeyHex = senderPubKeyHex,
                            isVerified = false,
                            identityKeyChangedAt = Clock.System.now().toEpochMilliseconds(),
                        )
                    )
                    sessionManager.deleteSession(conversationId)
                }
                return@runCatching
            }

            val nowMs = Clock.System.now().toEpochMilliseconds()
            val timerSecs = conversationRepository.getDisappearingTimer(conversationId)
            val expiresAtMs = if (timerSecs > 0L) nowMs + timerSecs * 1_000L else null

            messagingLog(
                MessagingLogLevel.INFO,
                "Inserting message into DB: id=${deliver.messageId.take(12)}… conv=${conversationId.take(24)}…",
            )
            messageRepository.insertMessage(
                MessageEntity(
                    id = deliver.messageId,
                    conversationId = conversationId,
                    ciphertext = ciphertext,
                    plaintextCache = payload.text,
                    sent = false,
                    status = MessageStatus.DELIVERED,
                    createdAt = nowMs,
                    expiresAtMs = expiresAtMs,
                )
            )
            messagingLog(MessagingLogLevel.INFO, "DB insertMessage OK")

            // Create conversation as REQUEST if unknown sender, keep TRUSTED if already known.
            val existing = conversationRepository.getConversation(conversationId)
            if (existing == null) {
                val senderName = payload.senderUsername.ifBlank { senderPubKeyHex.take(8) }
                // Detect key rotation: existing conversation with same username but different key.
                val prevByName = conversationRepository.getActiveConversations()
                    .firstOrNull { it.theirUsername == senderName && it.theirPublicKeyHex != senderPubKeyHex }
                val keyChangedAt = if (prevByName != null) Clock.System.now().toEpochMilliseconds() else null
                messagingLog(
                    MessagingLogLevel.INFO,
                    "Creating new conversation as REQUEST: conv=${conversationId.take(24)}… " +
                        "sender=$senderName — will appear in Message Requests, NOT in main chat list " +
                        "until accepted (this is by design; see qa_report_2026_04_24.md BUG-A).",
                )
                conversationRepository.upsertConversation(
                    ConversationEntity(
                        id = conversationId,
                        theirUsername = senderName,
                        theirPublicKeyHex = senderPubKeyHex,
                        lastMessagePreview = previewText(payload.text),
                        lastMessageAt = Clock.System.now().toEpochMilliseconds(),
                        unreadCount = 1,
                        trustTier = TrustTier.REQUEST,
                        blocked = false,
                        identityKeyChangedAt = keyChangedAt,
                    )
                )
            } else {
                messagingLog(
                    MessagingLogLevel.INFO,
                    "Updating existing conversation: conv=${conversationId.take(24)}… " +
                        "trustTier=${existing.trustTier} (kept) unreadCount=${existing.unreadCount + 1}",
                )
                conversationRepository.upsertConversation(
                    existing.copy(
                        lastMessagePreview = previewText(payload.text),
                        lastMessageAt = Clock.System.now().toEpochMilliseconds(),
                        unreadCount = existing.unreadCount + 1,
                    )
                )
            }

            messagingLog(MessagingLogLevel.INFO, "Emitting IncomingMessage to UI flow")
            _incomingMessages.emit(
                IncomingMessage(
                    id = deliver.messageId,
                    conversationId = conversationId,
                    senderPublicKeyHex = senderPubKeyHex,
                    text = payload.text,
                    receivedAt = Clock.System.now().toEpochMilliseconds(),
                )
            )

            val senderName = payload.senderUsername.ifBlank { senderPubKeyHex.take(8) }
            messagingLog(
                MessagingLogLevel.INFO,
                "Invoking onNewMessageNotification callback (null=${onNewMessageNotification == null})",
            )
            // Defensive: the platform notification callback may throw on pre-signed URLs
            // or when background restrictions kick in. A thrown exception here used to
            // propagate into the silent onFailure below and lose the stack entirely.
            runCatching {
                onNewMessageNotification?.invoke(conversationId, senderName, previewText(payload.text), senderPubKeyHex)
            }.onFailure { notifErr ->
                messagingLog(
                    MessagingLogLevel.ERROR,
                    "onNewMessageNotification threw (${notifErr::class.simpleName}): ${notifErr.message}",
                    notifErr,
                )
            }
            // Tell the relay to drop this envelope from its store now that we
            // have safely persisted + emitted it. Without this the relay will
            // re-deliver the same message on every reconnect for up to
            // RELAY_ENVELOPE_TTL_SECS (7 days). INSERT OR IGNORE in the
            // messages table makes any duplication harmless, but explicit
            // ack-deliver is what actually frees server-side memory.
            transport.sendDeliveryAck(deliver.messageId)

            messagingLog(
                MessagingLogLevel.INFO,
                "handleDeliver DONE for id=${deliver.messageId.take(12)}… (ack-deliver sent)",
            )
        }.onFailure { e ->
            // Previously this branch was silent ("avoids leaking error details"). That policy hides
            // legitimate bugs (decrypt mismatch, DB unique-constraint, parse error, UI callback
            // throwing) and produces crashes with no context. Log with full stack so a future QA
            // run on a physical device yields actionable diagnostics.
            messagingLog(
                MessagingLogLevel.ERROR,
                "handleDeliver FAILED for id=${deliver.messageId.take(12)}… (${e::class.simpleName}): ${e.message}",
                e,
            )
        }
        } finally {
            processingLock.withLock { activeProcessing.remove(deliver.messageId) }
        }
    }

    override suspend fun markConversationRead(conversationId: String, theirPublicKeyHex: String) {
        val unreadMessages = messageRepository.getMessages(conversationId)
            .filter { !it.sent && it.status != MessageStatus.READ }
        unreadMessages.forEach { msg ->
            transport.sendReadReceipt(
                phantom.core.transport.RelayMessage.ReadReceipt(
                    to = theirPublicKeyHex,
                    from = identity.publicKeyHex,
                    messageId = msg.id,
                )
            )
            messageRepository.updateStatus(msg.id, MessageStatus.READ)
        }
        conversationRepository.resetUnread(conversationId)
    }

    private fun deriveConversationId(theirPublicKeyHex: String): String {
        val keys = listOf(identity.publicKeyHex, theirPublicKeyHex).sorted()
        return "${keys[0]}_${keys[1]}"
    }

    override suspend fun deleteMessageForBoth(
        messageId: String,
        conversationId: String,
        recipientPublicKeyHex: String,
    ): Result<Unit> = runCatching {
        val payload = json.encodeToString(
            MessagePayload(
                text = "",
                sentAt = Clock.System.now().toEpochMilliseconds(),
                senderUsername = identity.username,
                type = MessagePayload.TYPE_DELETE,
                targetMessageId = messageId,
            )
        ).encodeToByteArray()
        val encrypted = encryptUnderLock(
            conversationId = conversationId,
            recipientPublicKeyHex = recipientPublicKeyHex,
            plaintext = payload,
        )
        val ciphertext = json.encodeToString(encrypted).encodeToByteArray()
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        transport.send(
            RelayMessage.Send(
                to = recipientPublicKeyHex,
                from = "",
                sealedSender = Base64.encode(
                    SealedSender.seal(identity.publicKeyHex, hexToBytes(recipientPublicKeyHex))
                ),
                payload = MessagePadding.pad(ciphertext).encodeBase64(),
                messageId = uuid4().toString(),
            )
        )
        messageRepository.deleteMessage(messageId)
    }

    override suspend fun sendDisappearingTimerUpdate(
        timerSecs: Long,
        conversationId: String,
        recipientPublicKeyHex: String,
    ): Result<Unit> = runCatching {
        val payload = json.encodeToString(
            MessagePayload(
                text = "",
                sentAt = Clock.System.now().toEpochMilliseconds(),
                senderUsername = identity.username,
                type = MessagePayload.TYPE_DISAPPEARING_TIMER,
                disappearingTimerSecs = timerSecs,
            )
        ).encodeToByteArray()
        val encrypted = encryptUnderLock(
            conversationId = conversationId,
            recipientPublicKeyHex = recipientPublicKeyHex,
            plaintext = payload,
        )
        val ciphertext = json.encodeToString(encrypted).encodeToByteArray()
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        transport.send(
            RelayMessage.Send(
                to = recipientPublicKeyHex,
                from = "",
                sealedSender = Base64.encode(
                    SealedSender.seal(identity.publicKeyHex, hexToBytes(recipientPublicKeyHex))
                ),
                payload = MessagePadding.pad(ciphertext).encodeBase64(),
                messageId = uuid4().toString(),
            )
        )
    }

    override suspend fun editMessageForBoth(
        messageId: String,
        newText: String,
        conversationId: String,
        recipientPublicKeyHex: String,
    ): Result<Unit> = runCatching {
        val payload = json.encodeToString(
            MessagePayload(
                text = newText,
                sentAt = Clock.System.now().toEpochMilliseconds(),
                senderUsername = identity.username,
                type = MessagePayload.TYPE_EDIT,
                targetMessageId = messageId,
            )
        ).encodeToByteArray()
        val encrypted = encryptUnderLock(
            conversationId = conversationId,
            recipientPublicKeyHex = recipientPublicKeyHex,
            plaintext = payload,
        )
        val ciphertext = json.encodeToString(encrypted).encodeToByteArray()
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        transport.send(
            RelayMessage.Send(
                to = recipientPublicKeyHex,
                from = "",
                sealedSender = Base64.encode(
                    SealedSender.seal(identity.publicKeyHex, hexToBytes(recipientPublicKeyHex))
                ),
                payload = MessagePadding.pad(ciphertext).encodeBase64(),
                messageId = uuid4().toString(),
            )
        )
        messageRepository.updateMessageText(messageId, newText)
    }

    override suspend fun sendReaction(
        messageId: String,
        conversationId: String,
        recipientPublicKeyHex: String,
        emoji: String,
    ): Result<Unit> = runCatching {
        val payload = json.encodeToString(
            MessagePayload(
                text = "",
                sentAt = Clock.System.now().toEpochMilliseconds(),
                senderUsername = identity.username,
                type = MessagePayload.TYPE_REACTION,
                targetMessageId = messageId,
                emoji = emoji,
            )
        ).encodeToByteArray()
        val encrypted = encryptUnderLock(
            conversationId = conversationId,
            recipientPublicKeyHex = recipientPublicKeyHex,
            plaintext = payload,
        )
        val ciphertext = json.encodeToString(encrypted).encodeToByteArray()
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        transport.send(
            RelayMessage.Send(
                to = recipientPublicKeyHex,
                from = "",
                sealedSender = Base64.encode(
                    SealedSender.seal(identity.publicKeyHex, hexToBytes(recipientPublicKeyHex))
                ),
                payload = MessagePadding.pad(ciphertext).encodeBase64(),
                messageId = uuid4().toString(),
            )
        )
        // Also apply locally so the sender sees their own reaction immediately
        val repo = reactionRepository
        if (repo != null) {
            if (emoji.isEmpty()) {
                repo.deleteReaction(messageId, identity.publicKeyHex)
            } else {
                repo.upsertReaction(
                    messageId = messageId,
                    senderKeyHex = identity.publicKeyHex,
                    emoji = emoji,
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                )
            }
        }
    }

    override suspend fun pinMessageForBoth(
        messageId: String,
        conversationId: String,
        recipientPublicKeyHex: String,
        pinned: Boolean,
    ): Result<Unit> = runCatching {
        val payload = json.encodeToString(
            MessagePayload(
                text = "",
                sentAt = Clock.System.now().toEpochMilliseconds(),
                senderUsername = identity.username,
                type = MessagePayload.TYPE_PIN,
                targetMessageId = messageId,
                pinned = pinned,
            )
        ).encodeToByteArray()
        val encrypted = encryptUnderLock(
            conversationId = conversationId,
            recipientPublicKeyHex = recipientPublicKeyHex,
            plaintext = payload,
        )
        val ciphertext = json.encodeToString(encrypted).encodeToByteArray()
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        transport.send(
            RelayMessage.Send(
                to = recipientPublicKeyHex,
                from = "",
                sealedSender = Base64.encode(
                    SealedSender.seal(identity.publicKeyHex, hexToBytes(recipientPublicKeyHex))
                ),
                payload = MessagePadding.pad(ciphertext).encodeBase64(),
                messageId = uuid4().toString(),
            )
        )
        // Apply locally so the sender sees the pin state immediately. We
        // are the pinner — record our own pubkey so the banner reads
        // "Pinned by you" on this side.
        messageRepository.pinMessage(
            messageId = messageId,
            pinned = pinned,
            pinnedByPubkey = if (pinned) identity.publicKeyHex else null,
        )
    }

    /** Strip reply prefix "> quote\n" and substitute media markers with
     *  human-readable labels so chat list and notifications never leak the
     *  raw `[AUDIO:base64...]` envelope. */
    private fun previewText(text: String): String {
        // Body to inspect: drop reply quote first.
        val body = if (text.startsWith("> ")) {
            val nl = text.indexOf('\n')
            if (nl in 1 until text.length) text.substring(nl + 1) else text
        } else {
            text
        }
        return when {
            body.startsWith("[AUDIO:") -> "🎤 Voice message"
            body.startsWith("[IMAGE:") -> "🖼️ Photo"
            body.startsWith("[FILE:")  -> "📎 File"
            else                       -> body.take(60)
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun ByteArray.encodeBase64(): String = Base64.encode(this)

@OptIn(ExperimentalEncodingApi::class)
private fun String.decodeBase64Bytes(): ByteArray = Base64.decode(this)

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
