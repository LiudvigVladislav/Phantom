// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.benasher44.uuid.uuid4
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import phantom.core.crypto.SenderKey
import phantom.core.storage.GroupEntity
import phantom.core.storage.GroupMemberEntity
import phantom.core.storage.GroupRepository
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageRepository
import phantom.core.storage.MessageStatus
import phantom.core.storage.SenderKeyEntity
import phantom.core.storage.SenderKeyRepository

/**
 * Default implementation of [GroupMessagingService].
 *
 * Encryption model:
 *   - Each group member holds a [SenderKey.Bundle] for every other member.
 *   - On send the local bundle advances once; the resulting ciphertext (the
 *     group's inner SenderKey envelope) is broadcast to every other member,
 *     wrapped per-recipient in a Double Ratchet + Sealed Sender envelope by
 *     [MessagingService.sendGroupControlMessage] (ADR-026). The relay sees
 *     only opaque sealed blobs.
 *   - Control messages (invite, SKD, add-member, leave) take the same path
 *     so SenderKey chain keys and member rosters never touch the relay in
 *     plaintext.
 *
 * Threading: all suspend functions are safe to call from any coroutine context.
 * The [groupMessageFlow] MutableSharedFlow uses tryEmit (fire-and-forget) so it
 * never suspends the hot path.
 */
@OptIn(ExperimentalEncodingApi::class)
class DefaultGroupMessagingService(
    private val myPubKeyHex: String,
    private val myUsername: String,
    private val groupRepo: GroupRepository,
    private val senderKeyRepo: SenderKeyRepository,
    private val messageRepo: MessageRepository,
    private val messagingService: MessagingService,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : GroupMessagingService {

    private val _groupMessageFlow = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val groupMessageFlow: Flow<String> = _groupMessageFlow.asSharedFlow()

    // ── Public API ────────────────────────────────────────────────────────────

    override suspend fun createGroup(
        name: String,
        members: List<Pair<String, String>>,
        isChannel: Boolean,
    ): String {
        val groupId = uuid4().toString()
        val now = Clock.System.now().toEpochMilliseconds()

        groupRepo.insertGroup(
            GroupEntity(
                id = groupId,
                name = name,
                myRole = "admin",
                isChannel = isChannel,
                createdAt = now,
            )
        )

        // Self
        groupRepo.insertMember(GroupMemberEntity(groupId, myPubKeyHex, myUsername, now))
        // Others
        members.forEach { (pubkey, username) ->
            groupRepo.insertMember(GroupMemberEntity(groupId, pubkey, username, now))
        }

        // Generate local SenderKey
        val myBundle = SenderKey.generate()
        senderKeyRepo.upsert(
            SenderKeyEntity(
                groupId = groupId,
                memberPubkeyHex = myPubKeyHex,
                chainKeyHex = myBundle.chainKeyHex,
                iteration = myBundle.iteration.toLong(),
            )
        )

        val allMembers = members.map { GroupMemberInfo(it.first, it.second) } +
            GroupMemberInfo(myPubKeyHex, myUsername)

        // Send group_invite + SKD to each member
        members.forEach { (pubkey, _) ->
            val invitePayload = MessagePayload(
                type = MessagePayload.TYPE_GROUP_INVITE,
                groupId = groupId,
                groupName = name,
                isChannel = isChannel,
                groupMembers = allMembers,
                senderKeyChainHex = myBundle.chainKeyHex,
                senderKeyIter = myBundle.iteration,
            )
            sendControlMessage(pubkey, invitePayload)
        }

        return groupId
    }

    override suspend fun sendGroupMessage(groupId: String, text: String) {
        sendGroupPayload(groupId = groupId, text = text, audioB64 = null, audioDurationMs = null)
    }

    override suspend fun sendGroupAudio(
        groupId: String,
        audioBytes: ByteArray,
        durationMs: Long,
        mimeType: String,
    ): Result<Unit> {
        if (audioBytes.size > DefaultMessagingService.MAX_AUDIO_BYTES) {
            return Result.failure(IllegalArgumentException(
                "Group audio payload ${audioBytes.size} bytes exceeds MAX_AUDIO_BYTES cap " +
                    "(${DefaultMessagingService.MAX_AUDIO_BYTES}). Recording must be shorter."
            ))
        }

        val chunkId = uuid4().toString()
        val total = kotlin.math.ceil(audioBytes.size.toDouble() / DefaultMessagingService.AUDIO_CHUNK_BYTES).toInt()
            .coerceAtLeast(1)

        return runCatching {
            for (i in 0 until total) {
                val start = i * DefaultMessagingService.AUDIO_CHUNK_BYTES
                val end = minOf((i + 1) * DefaultMessagingService.AUDIO_CHUNK_BYTES, audioBytes.size)
                val slice = audioBytes.copyOfRange(start, end)
                @OptIn(ExperimentalEncodingApi::class)
                val chunkBase64 = Base64.encode(slice)

                sendGroupChunk(
                    groupId = groupId,
                    chunkId = chunkId,
                    chunkIndex = i,
                    chunkTotal = total,
                    chunkBase64 = chunkBase64,
                    durationMs = durationMs,
                    mimeType = mimeType,
                )
            }

            // Store one outgoing message row locally so the sender's UI shows
            // the voice note immediately. The full reassembled base64 is stored
            // so AudioBubble can play it back without waiting for chunk acks.
            @OptIn(ExperimentalEncodingApi::class)
            val fullBase64 = Base64.encode(audioBytes)
            val now = Clock.System.now().toEpochMilliseconds()
            val msgId = uuid4().toString()
            messageRepo.insertMessage(
                MessageEntity(
                    id = msgId,
                    conversationId = groupId,
                    ciphertext = ByteArray(0),
                    plaintextCache = "[AUDIO:$fullBase64]",
                    sent = true,
                    status = MessageStatus.QUEUED,
                    createdAt = now,
                    expiresAtMs = null,
                )
            )
            groupRepo.updateLastMessage(groupId, "[voice]", now)
        }
    }

    private suspend fun sendGroupChunk(
        groupId: String,
        chunkId: String,
        chunkIndex: Int,
        chunkTotal: Int,
        chunkBase64: String,
        durationMs: Long,
        mimeType: String,
    ) {
        val members = groupRepo.getMembers(groupId)
        val bundleEntity = senderKeyRepo.get(groupId, myPubKeyHex)
        var bundle = if (bundleEntity != null) {
            SenderKey.Bundle(
                bundleEntity.chainKeyHex, bundleEntity.iteration.toInt(),
            )
        } else {
            val b = SenderKey.generate()
            senderKeyRepo.upsert(
                SenderKeyEntity(groupId, myPubKeyHex, b.chainKeyHex, b.iteration.toLong())
            )
            b
        }

        val innerPayload = MessagePayload(
            type = MessagePayload.TYPE_AUDIO_CHUNK,
            groupId = groupId,
            audioChunkId = chunkId,
            audioChunkIndex = chunkIndex,
            audioChunkTotal = chunkTotal,
            audioChunkB64 = chunkBase64,
            audioDurationMs = durationMs,
            audioMimeType = mimeType,
        )
        val plainBytes = json.encodeToString(innerPayload).encodeToByteArray()
        val (newBundle, cipherBytes) = SenderKey.encrypt(plainBytes, bundle)
        bundle = newBundle

        senderKeyRepo.upsert(
            SenderKeyEntity(
                groupId = groupId,
                memberPubkeyHex = myPubKeyHex,
                chainKeyHex = bundle.chainKeyHex,
                iteration = bundle.iteration.toLong(),
            )
        )

        @OptIn(ExperimentalEncodingApi::class)
        val ciphertextB64 = Base64.encode(cipherBytes)
        val outerPayload = MessagePayload(
            type = MessagePayload.TYPE_AUDIO_CHUNK,
            groupId = groupId,
            groupCiphertextB64 = ciphertextB64,
        )

        // ADR-026: per-recipient group audio chunks go through the Double
        // Ratchet + Sealed Sender pipeline like every other group envelope.
        members.filter { it.pubkeyHex != myPubKeyHex }.forEach { member ->
            messagingService.sendGroupControlMessage(member.pubkeyHex, outerPayload)
        }
    }

    override suspend fun addMember(groupId: String, pubkeyHex: String, username: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        groupRepo.insertMember(GroupMemberEntity(groupId, pubkeyHex, username, now))

        // Send our own SenderKey to the new member
        val myEntity = senderKeyRepo.get(groupId, myPubKeyHex)
        val myBundle = if (myEntity != null) {
            SenderKey.Bundle(
                myEntity.chainKeyHex, myEntity.iteration.toInt(),
            )
        } else {
            val b = SenderKey.generate()
            senderKeyRepo.upsert(
                SenderKeyEntity(groupId, myPubKeyHex, b.chainKeyHex, b.iteration.toLong())
            )
            b
        }
        sendSenderKeyDistribution(groupId, pubkeyHex, myBundle)

        // Notify all existing members about the new member
        val notifyPayload = MessagePayload(
            type = MessagePayload.TYPE_GROUP_ADD_MEMBER,
            groupId = groupId,
            groupMembers = listOf(GroupMemberInfo(pubkeyHex, username)),
        )
        val members = groupRepo.getMembers(groupId)
        members.filter { it.pubkeyHex != myPubKeyHex && it.pubkeyHex != pubkeyHex }.forEach { member ->
            sendControlMessage(member.pubkeyHex, notifyPayload)
        }
    }

    override suspend fun leaveGroup(groupId: String) {
        val members = groupRepo.getMembers(groupId)
        val leavePayload = MessagePayload(
            type = MessagePayload.TYPE_GROUP_LEAVE,
            groupId = groupId,
        )
        members.filter { it.pubkeyHex != myPubKeyHex }.forEach { member ->
            sendControlMessage(member.pubkeyHex, leavePayload)
        }
        groupRepo.deleteGroup(groupId)
        senderKeyRepo.deleteForGroup(groupId)
    }

    override suspend fun handleIncoming(payload: MessagePayload, fromPubKeyHex: String) {
        when (payload.type) {
            MessagePayload.TYPE_GROUP_INVITE            -> handleGroupInvite(payload, fromPubKeyHex)
            MessagePayload.TYPE_SENDER_KEY_DISTRIBUTION -> handleSenderKeyDistribution(payload, fromPubKeyHex)
            MessagePayload.TYPE_GROUP_MESSAGE,
            MessagePayload.TYPE_CHANNEL_POST            -> handleGroupMessage(payload, fromPubKeyHex)
            MessagePayload.TYPE_GROUP_ADD_MEMBER        -> handleAddMember(payload)
            MessagePayload.TYPE_GROUP_LEAVE             -> handleLeave(payload, fromPubKeyHex)
        }
    }

    // ── Incoming handlers ─────────────────────────────────────────────────────

    private suspend fun handleGroupInvite(payload: MessagePayload, fromPubKeyHex: String) {
        val groupId = payload.groupId ?: return
        val name = payload.groupName ?: return
        val now = Clock.System.now().toEpochMilliseconds()

        groupRepo.insertGroup(
            GroupEntity(
                id = groupId,
                name = name,
                myRole = "member",
                isChannel = payload.isChannel ?: false,
                createdAt = now,
            )
        )

        payload.groupMembers?.forEach { member ->
            groupRepo.insertMember(GroupMemberEntity(groupId, member.pubkeyHex, member.username, now))
        }

        // Store inviter's SenderKey if it arrived with the invite
        if (payload.senderKeyChainHex != null) {
            senderKeyRepo.upsert(
                SenderKeyEntity(
                    groupId = groupId,
                    memberPubkeyHex = fromPubKeyHex,
                    chainKeyHex = payload.senderKeyChainHex,
                    iteration = (payload.senderKeyIter ?: 0).toLong(),
                )
            )
        }

        // Reply with our own SenderKey so the inviter can decrypt our future messages
        val myEntity = senderKeyRepo.get(groupId, myPubKeyHex)
        val myBundle = if (myEntity != null) {
            SenderKey.Bundle(
                myEntity.chainKeyHex, myEntity.iteration.toInt(),
            )
        } else {
            val b = SenderKey.generate()
            senderKeyRepo.upsert(
                SenderKeyEntity(groupId, myPubKeyHex, b.chainKeyHex, b.iteration.toLong())
            )
            b
        }
        sendSenderKeyDistribution(groupId, fromPubKeyHex, myBundle)

        _groupMessageFlow.tryEmit(groupId)
    }

    private suspend fun handleSenderKeyDistribution(payload: MessagePayload, fromPubKeyHex: String) {
        val groupId = payload.groupId ?: return
        senderKeyRepo.upsert(
            SenderKeyEntity(
                groupId = groupId,
                memberPubkeyHex = fromPubKeyHex,
                chainKeyHex = payload.senderKeyChainHex ?: return,
                iteration = (payload.senderKeyIter ?: 0).toLong(),
            )
        )
    }

    private suspend fun handleGroupMessage(payload: MessagePayload, fromPubKeyHex: String) {
        val groupId = payload.groupId ?: return
        val ciphertextB64 = payload.groupCiphertextB64 ?: return

        val keyEntity = senderKeyRepo.get(groupId, fromPubKeyHex) ?: return
        val bundle = SenderKey.Bundle(
            keyEntity.chainKeyHex, keyEntity.iteration.toInt(),
        )

        val cipherBytes = Base64.decode(ciphertextB64)
        val (newBundle, plainBytes) = SenderKey.decrypt(cipherBytes, bundle) ?: return

        senderKeyRepo.upsert(
            SenderKeyEntity(
                groupId = groupId,
                memberPubkeyHex = fromPubKeyHex,
                chainKeyHex = newBundle.chainKeyHex,
                iteration = newBundle.iteration.toLong(),
            )
        )

        val inner = runCatching {
            json.decodeFromString<MessagePayload>(plainBytes.decodeToString())
        }.getOrNull() ?: return

        val text = inner.text.ifBlank { if (inner.audioDataB64 != null) "[voice]" else "" }

        val now = Clock.System.now().toEpochMilliseconds()
        val msgId = uuid4().toString()
        messageRepo.insertMessage(
            MessageEntity(
                id = msgId,
                conversationId = groupId,
                ciphertext = cipherBytes,
                plaintextCache = text,
                sent = false,
                status = MessageStatus.DELIVERED,
                createdAt = now,
                expiresAtMs = null,
            )
        )
        groupRepo.updateLastMessage(groupId, text, now)

        _groupMessageFlow.tryEmit(groupId)
    }

    private suspend fun handleAddMember(payload: MessagePayload) {
        val groupId = payload.groupId ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        payload.groupMembers?.forEach { member ->
            groupRepo.insertMember(GroupMemberEntity(groupId, member.pubkeyHex, member.username, now))

            // Send our SenderKey to the newly added member
            val myEntity = senderKeyRepo.get(groupId, myPubKeyHex) ?: return@forEach
            val myBundle = SenderKey.Bundle(
                myEntity.chainKeyHex, myEntity.iteration.toInt(),
            )
            sendSenderKeyDistribution(groupId, member.pubkeyHex, myBundle)
        }
    }

    private suspend fun handleLeave(payload: MessagePayload, fromPubKeyHex: String) {
        val groupId = payload.groupId ?: return
        groupRepo.deleteMember(groupId, fromPubKeyHex)

        // F4 fix: the leaver retained a copy of our previous SenderKey from
        // every SKD they received, so any group message we send under the old
        // chain remains decryptable by them indefinitely. Rotate our own
        // SenderKey now and broadcast a fresh SKD to all remaining members
        // BEFORE the next group send. Forward secrecy from this point on is
        // restored: the leaver's stored chain key is no longer the one we
        // use to encrypt new messages, and SenderKey.advance is one-way so
        // they cannot derive the new chain key from the old one.
        //
        // Other members' SenderKeys are intentionally NOT deleted here —
        // those are owned by their respective senders, and only those senders
        // can rotate them. Each remaining member runs this same handler when
        // they observe the LEAVE, so the network converges on fresh keys.
        val newBundle = SenderKey.generate()
        senderKeyRepo.upsert(
            SenderKeyEntity(
                groupId = groupId,
                memberPubkeyHex = myPubKeyHex,
                chainKeyHex = newBundle.chainKeyHex,
                iteration = newBundle.iteration.toLong(),
            )
        )
        val remainingMembers = groupRepo.getMembers(groupId)
        remainingMembers.filter { it.pubkeyHex != myPubKeyHex }.forEach { member ->
            sendSenderKeyDistribution(groupId, member.pubkeyHex, newBundle)
        }
    }

    // ── Send helpers ──────────────────────────────────────────────────────────

    private suspend fun sendGroupPayload(
        groupId: String,
        text: String?,
        audioB64: String?,
        audioDurationMs: Long?,
    ) {
        val group = groupRepo.getGroup(groupId) ?: return
        val members = groupRepo.getMembers(groupId)

        // Resolve or generate local SenderKey
        val bundleEntity = senderKeyRepo.get(groupId, myPubKeyHex)
        var bundle = if (bundleEntity != null) {
            SenderKey.Bundle(
                bundleEntity.chainKeyHex, bundleEntity.iteration.toInt(),
            )
        } else {
            val b = SenderKey.generate()
            senderKeyRepo.upsert(
                SenderKeyEntity(groupId, myPubKeyHex, b.chainKeyHex, b.iteration.toLong())
            )
            b
        }

        val type = if (group.isChannel) MessagePayload.TYPE_CHANNEL_POST else MessagePayload.TYPE_GROUP_MESSAGE
        val innerPayload = MessagePayload(
            type = type,
            text = text ?: "",
            groupId = groupId,
            audioDataB64 = audioB64,
            audioDurationMs = audioDurationMs,
        )
        val plainBytes = json.encodeToString(innerPayload).encodeToByteArray()
        val (newBundle, cipherBytes) = SenderKey.encrypt(plainBytes, bundle)
        bundle = newBundle

        senderKeyRepo.upsert(
            SenderKeyEntity(
                groupId = groupId,
                memberPubkeyHex = myPubKeyHex,
                chainKeyHex = bundle.chainKeyHex,
                iteration = bundle.iteration.toLong(),
            )
        )

        val plaintextPreview = text ?: if (audioB64 != null) "[voice]" else ""
        val now = Clock.System.now().toEpochMilliseconds()
        val msgId = uuid4().toString()
        messageRepo.insertMessage(
            MessageEntity(
                id = msgId,
                conversationId = groupId,
                ciphertext = cipherBytes,
                plaintextCache = plaintextPreview,
                sent = true,
                status = MessageStatus.QUEUED,
                createdAt = now,
                expiresAtMs = null,
            )
        )
        groupRepo.updateLastMessage(groupId, plaintextPreview, now)

        val ciphertextB64 = Base64.encode(cipherBytes)
        val outerPayload = MessagePayload(
            type = type,
            groupId = groupId,
            groupCiphertextB64 = ciphertextB64,
        )

        // ADR-026: per-recipient envelopes go through the Double Ratchet +
        // Sealed Sender pipeline so the relay cannot link the broadcast to a
        // single sender or correlate the per-recipient sends as one event.
        members.filter { it.pubkeyHex != myPubKeyHex }.forEach { member ->
            messagingService.sendGroupControlMessage(member.pubkeyHex, outerPayload)
        }
    }

    /**
     * Send a control payload (invite / SKD / leave / add-member) to one peer
     * through the Double Ratchet + Sealed Sender pipeline (ADR-026, F1 fix).
     * The relay sees only an opaque sealed envelope; chain keys and the member
     * roster never touch it in plaintext. The recipient's
     * [DefaultMessagingService.handleDeliver] decrypts and routes back here
     * via [handleIncoming].
     */
    private suspend fun sendControlMessage(toPubKeyHex: String, payload: MessagePayload) {
        messagingService.sendGroupControlMessage(toPubKeyHex, payload)
    }

    private suspend fun sendSenderKeyDistribution(
        groupId: String,
        toPubKeyHex: String,
        bundle: SenderKey.Bundle,
    ) {
        val skdPayload = MessagePayload(
            type = MessagePayload.TYPE_SENDER_KEY_DISTRIBUTION,
            groupId = groupId,
            senderKeyChainHex = bundle.chainKeyHex,
            senderKeyIter = bundle.iteration,
        )
        sendControlMessage(toPubKeyHex, skdPayload)
    }
}
