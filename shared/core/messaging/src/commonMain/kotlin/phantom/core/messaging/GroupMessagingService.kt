package phantom.core.messaging

import kotlinx.coroutines.flow.Flow

/**
 * Handles group and channel messaging using the Sender Key protocol.
 *
 * Architecture decision (ADR-pending): the relay is dumb and knows nothing about groups.
 * Each group message is sent as N individual envelopes — one per member — all carrying the
 * same [MessagePayload.groupCiphertextB64] encrypted once with the sender's SenderKey.
 * Control messages (invite, SKD, leave, add-member) are sent the same way, piggy-backed
 * on the existing 1:1 transport path so the relay never sees group membership.
 */
interface GroupMessagingService {

    /**
     * Create a new group or channel. [members] is a list of (pubkeyHex, username) pairs
     * excluding the local user. Returns the new groupId (UUID).
     *
     * Side-effects: persists group + members locally, generates a SenderKey for the local
     * user, and sends a group_invite + sender_key_distribution to every member.
     */
    suspend fun createGroup(
        name: String,
        members: List<Pair<String, String>>,
        isChannel: Boolean = false,
    ): String

    /** Encrypt [text] with the local SenderKey and fan-out to every member. */
    suspend fun sendGroupMessage(groupId: String, text: String)

    /** Encrypt OGG audio (base64) with the local SenderKey and fan-out to every member. */
    suspend fun sendGroupAudio(groupId: String, audioBase64: String, durationMs: Long)

    /**
     * Add a new member to the group (admin only).
     * Sends a sender_key_distribution to the new member and a group_add_member notice
     * to all existing members so they can add the new member to their local state.
     */
    suspend fun addMember(groupId: String, pubkeyHex: String, username: String)

    /**
     * Leave the group. Sends a group_leave notice to all remaining members, then
     * deletes the group and all SenderKeys from local storage.
     */
    suspend fun leaveGroup(groupId: String)

    /**
     * Hot flow that emits [groupId] whenever a new message (or control event that
     * changes visible state) arrives for a group. UI layers collect this to trigger
     * a re-fetch of the message list.
     */
    val groupMessageFlow: Flow<String>

    /**
     * Entry point for incoming group-related payloads.
     * Called by [DefaultMessagingService.handleDeliver] after decryption, when
     * [MessagePayload.type] is in [MessagePayload.GROUP_TYPES].
     */
    suspend fun handleIncoming(payload: MessagePayload, fromPubKeyHex: String)
}
