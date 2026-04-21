package phantom.core.messaging

import kotlinx.serialization.Serializable

@Serializable
data class MessagePayload(
    val text: String,
    val sentAt: Long,
    val senderUsername: String = "",
    val type: String = TYPE_MESSAGE,    // "message" | "delete" | "edit" | "disappearing_timer" | "reaction"
    val targetMessageId: String = "",   // used when type = "delete", "edit", or "reaction"
    val disappearingTimerSecs: Long? = null, // used when type = "disappearing_timer"
    val emoji: String? = null,          // used when type = "reaction"; empty string means remove
) {
    companion object {
        const val TYPE_MESSAGE = "message"
        const val TYPE_DELETE = "delete"
        const val TYPE_EDIT = "edit"
        const val TYPE_DISAPPEARING_TIMER = "disappearing_timer"
        const val TYPE_REACTION = "reaction"
    }
}
