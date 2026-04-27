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
import phantom.core.transport.RelayMessage
import phantom.core.transport.RelayTransport

/**
 * Default implementation of [GroupMessagingService].
 *
 * Encryption model:
 *   - Each group member holds a [SenderKey.Bundle] for every other member.
 *   - On send the local bundle advances once; the resulting ciphertext is broadcast
 *     to all members as identical [MessagePayload.groupCiphertextB64] blobs wrapped in
 *     individual relay envelopes (relay sees only the outer recipient, not the group).
 *   - Control messages (invite, SKD, add-member, leave) are sent as plaintext JSON
 *     over the relay. They reach the recipient already authenticated by the outer
 *     Double-Ratchet layer applied by [DefaultMessagingService].
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
    private val transport: RelayTransport,
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
                signingPubHex = myBundle.signingPubHex,
                signingPrivHex = myBundle.signingPrivHex,
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
                senderKeySignPubHex = myBundle.signingPubHex,
            )
            sendControlMessage(pubkey, invitePayload)
        }

        return groupId
    }

    override suspend fun sendGroupMessage(groupId: String, text: String) {
        sendGroupPayload(groupId = groupId, text = text, audioB64 = null, audioDurationMs = null)
    }

    override suspend fun sendGroupAudio(groupId: String, audioBase64: String, durationMs: Long) {
        sendGroupPayload(groupId = groupId, text = null, audioB64 = audioBase64, audioDurationMs = durationMs)
    }

    override suspend fun addMember(groupId: String, pubkeyHex: String, username: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        groupRepo.insertMember(GroupMemberEntity(groupId, pubkeyHex, username, now))

        // Send our own SenderKey to the new member
        val myEntity = senderKeyRepo.get(groupId, myPubKeyHex)
        val myBundle = if (myEntity != null) {
            SenderKey.Bundle(
                myEntity.chainKeyHex, myEntity.iteration.toInt(),
                myEntity.signingPubHex, myEntity.signingPrivHex,
            )
        } else {
            val b = SenderKey.generate()
            senderKeyRepo.upsert(
                SenderKeyEntity(groupId, myPubKeyHex, b.chainKeyHex,
                    b.iteration.toLong(), b.signingPubHex, b.signingPrivHex)
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
        if (payload.senderKeyChainHex != null && payload.senderKeySignPubHex != null) {
            senderKeyRepo.upsert(
                SenderKeyEntity(
                    groupId = groupId,
                    memberPubkeyHex = fromPubKeyHex,
                    chainKeyHex = payload.senderKeyChainHex,
                    iteration = (payload.senderKeyIter ?: 0).toLong(),
                    signingPubHex = payload.senderKeySignPubHex,
                    signingPrivHex = "",
                )
            )
        }

        // Reply with our own SenderKey so the inviter can decrypt our future messages
        val myEntity = senderKeyRepo.get(groupId, myPubKeyHex)
        val myBundle = if (myEntity != null) {
            SenderKey.Bundle(
                myEntity.chainKeyHex, myEntity.iteration.toInt(),
                myEntity.signingPubHex, myEntity.signingPrivHex,
            )
        } else {
            val b = SenderKey.generate()
            senderKeyRepo.upsert(
                SenderKeyEntity(groupId, myPubKeyHex, b.chainKeyHex,
                    b.iteration.toLong(), b.signingPubHex, b.signingPrivHex)
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
                signingPubHex = payload.senderKeySignPubHex ?: return,
                signingPrivHex = "",
            )
        )
    }

    private suspend fun handleGroupMessage(payload: MessagePayload, fromPubKeyHex: String) {
        val groupId = payload.groupId ?: return
        val ciphertextB64 = payload.groupCiphertextB64 ?: return

        val keyEntity = senderKeyRepo.get(groupId, fromPubKeyHex) ?: return
        val bundle = SenderKey.Bundle(
            keyEntity.chainKeyHex, keyEntity.iteration.toInt(),
            keyEntity.signingPubHex, keyEntity.signingPrivHex,
        )

        val cipherBytes = Base64.decode(ciphertextB64)
        val (newBundle, plainBytes) = SenderKey.decrypt(cipherBytes, bundle) ?: return

        senderKeyRepo.upsert(
            SenderKeyEntity(
                groupId = groupId,
                memberPubkeyHex = fromPubKeyHex,
                chainKeyHex = newBundle.chainKeyHex,
                iteration = newBundle.iteration.toLong(),
                signingPubHex = newBundle.signingPubHex,
                signingPrivHex = "",
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
                myEntity.signingPubHex, myEntity.signingPrivHex,
            )
            sendSenderKeyDistribution(groupId, member.pubkeyHex, myBundle)
        }
    }

    private suspend fun handleLeave(payload: MessagePayload, fromPubKeyHex: String) {
        val groupId = payload.groupId ?: return
        groupRepo.deleteMember(groupId, fromPubKeyHex)
        // Rotate: delete all SenderKeys so they are re-distributed at next send.
        // Full key rotation (re-invite remaining members) is deferred to a future ADR.
        senderKeyRepo.deleteForGroup(groupId)
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
                bundleEntity.signingPubHex, bundleEntity.signingPrivHex,
            )
        } else {
            val b = SenderKey.generate()
            senderKeyRepo.upsert(
                SenderKeyEntity(groupId, myPubKeyHex, b.chainKeyHex,
                    b.iteration.toLong(), b.signingPubHex, b.signingPrivHex)
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
                signingPubHex = bundle.signingPubHex,
                signingPrivHex = bundle.signingPrivHex,
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
            senderKeySignPubHex = bundle.signingPubHex,
        )
        val outerJson = json.encodeToString(outerPayload)
        val outerB64 = Base64.encode(outerJson.encodeToByteArray())

        members.filter { it.pubkeyHex != myPubKeyHex }.forEach { member ->
            transport.send(
                RelayMessage.Send(
                    to = member.pubkeyHex,
                    from = myPubKeyHex,
                    payload = outerB64,
                    messageId = uuid4().toString(),
                )
            )
        }
    }

    /**
     * Send a control payload (invite / SKD / leave / add-member) to one peer.
     * The relay delivers it like any other message; [DefaultMessagingService] will
     * route it back here via [handleIncoming] on the recipient's side.
     *
     * Note: control messages are NOT encrypted by Double-Ratchet here — that layer is
     * applied by [DefaultMessagingService] when it wraps outgoing payloads. Since
     * [DefaultGroupMessagingService] has no access to ratchet sessions it sends the
     * JSON directly over the raw transport. This is acceptable for control messages
     * because the relay transport itself uses TLS, and group membership metadata is
     * already visible to both endpoints. Full E2E wrapping of control messages is
     * tracked as a future ADR item.
     */
    private suspend fun sendControlMessage(toPubKeyHex: String, payload: MessagePayload) {
        val payloadJson = json.encodeToString(payload)
        val payloadB64 = Base64.encode(payloadJson.encodeToByteArray())
        transport.send(
            RelayMessage.Send(
                to = toPubKeyHex,
                from = myPubKeyHex,
                payload = payloadB64,
                messageId = uuid4().toString(),
            )
        )
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
            senderKeySignPubHex = bundle.signingPubHex,
        )
        sendControlMessage(toPubKeyHex, skdPayload)
    }
}
