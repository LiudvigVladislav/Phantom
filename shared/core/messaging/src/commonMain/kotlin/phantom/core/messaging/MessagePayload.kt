package phantom.core.messaging

import kotlinx.serialization.Serializable

@Serializable
data class GroupMemberInfo(val pubkeyHex: String, val username: String)

@Serializable
data class MessagePayload(
    val text: String = "",
    val sentAt: Long = 0L,
    val senderUsername: String = "",
    val type: String = TYPE_MESSAGE,        // see TYPE_* constants below
    val targetMessageId: String = "",       // delete | edit | reaction | pin
    val disappearingTimerSecs: Long? = null, // disappearing_timer
    val emoji: String? = null,              // reaction; empty string = remove
    val pinned: Boolean? = null,            // pin; true = pin, false = unpin

    // ── Group / Channel fields ────────────────────────────────────────────────
    val groupId: String? = null,
    val groupName: String? = null,
    val isChannel: Boolean? = null,
    val groupMembers: List<GroupMemberInfo>? = null,   // group_invite

    // ── SenderKey distribution ────────────────────────────────────────────────
    val senderKeyChainHex: String? = null,             // sender_key_distribution
    val senderKeyIter: Int? = null,
    val senderKeySignPubHex: String? = null,
    val groupCiphertextB64: String? = null,            // base64 SenderKey ciphertext in group_message

    // ── Voice / Audio ─────────────────────────────────────────────────────────
    val audioDataB64: String? = null,                  // base64 OGG audio for audio type
    val audioDurationMs: Long? = null,
) {
    companion object {
        // 1:1 message types (pre-existing)
        const val TYPE_MESSAGE            = "message"
        const val TYPE_DELETE             = "delete"
        const val TYPE_EDIT               = "edit"
        const val TYPE_DISAPPEARING_TIMER = "disappearing_timer"
        const val TYPE_REACTION           = "reaction"
        const val TYPE_PIN                = "pin"

        // Group / Channel types (new)
        const val TYPE_GROUP_INVITE            = "group_invite"
        const val TYPE_SENDER_KEY_DISTRIBUTION = "sender_key_distribution"
        const val TYPE_GROUP_MESSAGE           = "group_message"
        const val TYPE_GROUP_ADD_MEMBER        = "group_add_member"
        const val TYPE_GROUP_LEAVE             = "group_leave"
        const val TYPE_CHANNEL_POST            = "channel_post"

        // Media types (new)
        const val TYPE_AUDIO = "audio"

        /** All group-related types that must be routed to GroupMessagingService. */
        val GROUP_TYPES = setOf(
            TYPE_GROUP_INVITE,
            TYPE_SENDER_KEY_DISTRIBUTION,
            TYPE_GROUP_MESSAGE,
            TYPE_CHANNEL_POST,
            TYPE_GROUP_ADD_MEMBER,
            TYPE_GROUP_LEAVE,
        )
    }
}
