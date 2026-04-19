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
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import phantom.core.crypto.DoubleRatchet
import phantom.core.crypto.DhKeyPair
import phantom.core.identity.IdentityRecord
import phantom.core.storage.ConversationEntity
import phantom.core.storage.ConversationRepository
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageRepository
import phantom.core.storage.MessageStatus
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
) : MessagingService {

    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val incomingMessages: Flow<IncomingMessage> = _incomingMessages.asSharedFlow()

    @Volatile private var receiving = false

    /**
     * Platform hook for local push notifications.
     * Set by the Android AppContainer after [initMessaging]; null on other platforms.
     * Called on the scope's thread after a message is successfully decrypted and stored.
     * The callback must be non-blocking (fire-and-forget on the platform side).
     */
    @Volatile var onNewMessageNotification: ((conversationId: String, senderName: String, preview: String) -> Unit)? = null

    override suspend fun sendMessage(message: OutgoingMessage): Result<Unit> = runCatching {
        val state = sessionManager.getOrCreateSession(
            conversationId = message.conversationId,
            localIdentityKeyPair = localKeyPair,
            remoteIdentityPublicKeyHex = message.recipientPublicKeyHex,
        )

        val payload = json.encodeToString(
            MessagePayload(
                text = message.text,
                sentAt = Clock.System.now().toEpochMilliseconds(),
                senderUsername = identity.username,
            )
        ).encodeToByteArray()

        val (newState, encrypted) = ratchet.encrypt(state, payload)
        sessionManager.saveSession(message.conversationId, newState)

        val ciphertext = json.encodeToString(encrypted).encodeToByteArray()

        messageRepository.insertMessage(
            MessageEntity(
                id = message.id,
                conversationId = message.conversationId,
                ciphertext = ciphertext,
                plaintextCache = message.text,
                sent = true,
                status = MessageStatus.QUEUED,
                createdAt = Clock.System.now().toEpochMilliseconds(),
            )
        )

        val sent = transport.send(
            RelayMessage.Send(
                to = message.recipientPublicKeyHex,
                from = identity.publicKeyHex,
                payload = ciphertext.encodeBase64(),
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
        println("[PHANTOM] handleDeliver from=${deliver.from.take(12)} msgId=${deliver.messageId}")
        runCatching {
            val ciphertext = deliver.payload.decodeBase64Bytes()
            val encrypted = json.decodeFromString<phantom.core.crypto.EncryptedMessage>(
                ciphertext.decodeToString()
            )

            val conversationId = deriveConversationId(deliver.from)
            val state = sessionManager.getOrCreateSession(
                conversationId = conversationId,
                localIdentityKeyPair = localKeyPair,
                remoteIdentityPublicKeyHex = deliver.from,
            )

            println("[PHANTOM] decrypting convId=${conversationId.take(16)}")
            val (newState, plainBytes) = ratchet.decrypt(state, encrypted)
            sessionManager.saveSession(conversationId, newState)

            val payload = json.decodeFromString<MessagePayload>(plainBytes.decodeToString())
            println("[PHANTOM] decrypted OK type=${payload.type} text=${payload.text.take(30)}")

            // Handle control messages — do not store as chat messages
            if (payload.type == "delete" && payload.targetMessageId.isNotEmpty()) {
                messageRepository.deleteMessage(payload.targetMessageId)
                _incomingMessages.emit(
                    IncomingMessage(
                        id = payload.targetMessageId,
                        conversationId = conversationId,
                        senderPublicKeyHex = deliver.from,
                        text = "",
                        receivedAt = Clock.System.now().toEpochMilliseconds(),
                    )
                )
                return@runCatching
            }
            if (payload.type == "edit" && payload.targetMessageId.isNotEmpty()) {
                messageRepository.updateMessageText(payload.targetMessageId, payload.text)
                _incomingMessages.emit(
                    IncomingMessage(
                        id = payload.targetMessageId,
                        conversationId = conversationId,
                        senderPublicKeyHex = deliver.from,
                        text = payload.text,
                        receivedAt = Clock.System.now().toEpochMilliseconds(),
                    )
                )
                return@runCatching
            }

            messageRepository.insertMessage(
                MessageEntity(
                    id = deliver.messageId,
                    conversationId = conversationId,
                    ciphertext = ciphertext,
                    plaintextCache = payload.text,
                    sent = false,
                    status = MessageStatus.DELIVERED,
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                )
            )

            // Create conversation as REQUEST if unknown sender, keep TRUSTED if already known.
            val existing = conversationRepository.getConversation(conversationId)
            if (existing == null) {
                val senderName = payload.senderUsername.ifBlank { deliver.from.take(8) }
                conversationRepository.upsertConversation(
                    ConversationEntity(
                        id = conversationId,
                        theirUsername = senderName,
                        theirPublicKeyHex = deliver.from,
                        lastMessagePreview = previewText(payload.text),
                        lastMessageAt = Clock.System.now().toEpochMilliseconds(),
                        unreadCount = 1,
                        trustTier = TrustTier.REQUEST,
                        blocked = false,
                    )
                )
            } else {
                conversationRepository.upsertConversation(
                    existing.copy(
                        lastMessagePreview = previewText(payload.text),
                        lastMessageAt = Clock.System.now().toEpochMilliseconds(),
                        unreadCount = existing.unreadCount + 1,
                    )
                )
            }

            _incomingMessages.emit(
                IncomingMessage(
                    id = deliver.messageId,
                    conversationId = conversationId,
                    senderPublicKeyHex = deliver.from,
                    text = payload.text,
                    receivedAt = Clock.System.now().toEpochMilliseconds(),
                )
            )

            val senderName = payload.senderUsername.ifBlank { deliver.from.take(8) }
            onNewMessageNotification?.invoke(conversationId, senderName, previewText(payload.text))
        }.onFailure { e ->
            println("[PHANTOM] handleDeliver FAILED: ${e::class.simpleName}: ${e.message}")
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
        val state = sessionManager.getOrCreateSession(
            conversationId = conversationId,
            localIdentityKeyPair = localKeyPair,
            remoteIdentityPublicKeyHex = recipientPublicKeyHex,
        )
        val payload = json.encodeToString(
            MessagePayload(
                text = "",
                sentAt = Clock.System.now().toEpochMilliseconds(),
                senderUsername = identity.username,
                type = "delete",
                targetMessageId = messageId,
            )
        ).encodeToByteArray()
        val (newState, encrypted) = ratchet.encrypt(state, payload)
        sessionManager.saveSession(conversationId, newState)
        val ciphertext = json.encodeToString(encrypted).encodeToByteArray()
        transport.send(
            RelayMessage.Send(
                to = recipientPublicKeyHex,
                from = identity.publicKeyHex,
                payload = ciphertext.encodeBase64(),
                messageId = uuid4().toString(),
            )
        )
        messageRepository.deleteMessage(messageId)
    }

    override suspend fun editMessageForBoth(
        messageId: String,
        newText: String,
        conversationId: String,
        recipientPublicKeyHex: String,
    ): Result<Unit> = runCatching {
        val state = sessionManager.getOrCreateSession(
            conversationId = conversationId,
            localIdentityKeyPair = localKeyPair,
            remoteIdentityPublicKeyHex = recipientPublicKeyHex,
        )
        val payload = json.encodeToString(
            MessagePayload(
                text = newText,
                sentAt = Clock.System.now().toEpochMilliseconds(),
                senderUsername = identity.username,
                type = "edit",
                targetMessageId = messageId,
            )
        ).encodeToByteArray()
        val (newState, encrypted) = ratchet.encrypt(state, payload)
        sessionManager.saveSession(conversationId, newState)
        val ciphertext = json.encodeToString(encrypted).encodeToByteArray()
        transport.send(
            RelayMessage.Send(
                to = recipientPublicKeyHex,
                from = identity.publicKeyHex,
                payload = ciphertext.encodeBase64(),
                messageId = uuid4().toString(),
            )
        )
        messageRepository.updateMessageText(messageId, newText)
    }

    /** Strip reply prefix "> quote\n" so chat list shows the actual message. */
    private fun previewText(text: String): String {
        if (!text.startsWith("> ")) return text.take(60)
        val nl = text.indexOf('\n')
        return if (nl in 1 until text.length) text.substring(nl + 1).take(60) else text.take(60)
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun ByteArray.encodeBase64(): String = Base64.encode(this)

@OptIn(ExperimentalEncodingApi::class)
private fun String.decodeBase64Bytes(): ByteArray = Base64.decode(this)
