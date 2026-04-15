package phantom.core.messaging

import com.benasher44.uuid.uuid4
import io.ktor.util.encodeBase64
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
import phantom.core.storage.ConversationRepository
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageRepository
import phantom.core.storage.MessageStatus
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

    override suspend fun sendMessage(message: OutgoingMessage): Result<Unit> = runCatching {
        val state = sessionManager.getOrCreateSession(
            conversationId = message.conversationId,
            localIdentityKeyPair = localKeyPair,
            remoteIdentityPublicKeyHex = message.recipientPublicKeyHex,
        )

        val payload = json.encodeToString(
            MessagePayload(text = message.text, sentAt = Clock.System.now().toEpochMilliseconds())
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
                    lastMessagePreview = message.text.take(60),
                    lastMessageAt = Clock.System.now().toEpochMilliseconds(),
                )
                ?: phantom.core.storage.ConversationEntity(
                    id = message.conversationId,
                    theirUsername = message.recipientPublicKeyHex.take(8),
                    theirPublicKeyHex = message.recipientPublicKeyHex,
                    lastMessagePreview = message.text.take(60),
                    lastMessageAt = Clock.System.now().toEpochMilliseconds(),
                    unreadCount = 0,
                )
        )
    }

    override suspend fun startReceiving() {
        transport.incoming
            .onEach { deliver -> handleDeliver(deliver) }
            .launchIn(scope)
    }

    private suspend fun handleDeliver(deliver: RelayMessage.Deliver) {
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

            val (newState, plainBytes) = ratchet.decrypt(state, encrypted)
            sessionManager.saveSession(conversationId, newState)

            val payload = json.decodeFromString<MessagePayload>(plainBytes.decodeToString())

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

            conversationRepository.incrementUnread(conversationId)

            _incomingMessages.emit(
                IncomingMessage(
                    id = deliver.messageId,
                    conversationId = conversationId,
                    senderPublicKeyHex = deliver.from,
                    text = payload.text,
                    receivedAt = Clock.System.now().toEpochMilliseconds(),
                )
            )
        }
    }

    private fun deriveConversationId(theirPublicKeyHex: String): String {
        val keys = listOf(identity.publicKeyHex, theirPublicKeyHex).sorted()
        return "${keys[0]}_${keys[1]}"
    }
}

private fun ByteArray.encodeBase64(): String = io.ktor.util.encodeBase64(this)
private fun String.decodeBase64Bytes(): ByteArray = io.ktor.util.decodeBase64Bytes(this)
