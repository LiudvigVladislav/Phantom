package phantom.core.messaging

import kotlinx.serialization.Serializable

@Serializable
data class MessagePayload(
    val text: String,
    val sentAt: Long,
    val senderUsername: String = "",
    val type: String = TYPE_MESSAGE,    // "message" | "delete" | "edit" | "disappearing_timer" | "reaction" | "pin"
    val targetMessageId: String = "",   // used when type = "delete", "edit", "reaction", or "pin"
    val disappearingTimerSecs: Long? = null, // used when type = "disappearing_timer"
    val emoji: String? = null,          // used when type = "reaction"; empty string means remove
    val pinned: Boolean? = null,        // used when type = "pin"; true = pin, false = unpin
) {
    companion object {
        const val TYPE_MESSAGE = "message"
        const val TYPE_DELETE = "delete"
        const val TYPE_EDIT = "edit"
        const val TYPE_DISAPPEARING_TIMER = "disappearing_timer"
        const val TYPE_REACTION = "reaction"
        const val TYPE_PIN = "pin"
    }
}
