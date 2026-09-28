package phantom.android.screens.chatlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import phantom.android.R
import phantom.core.storage.ConversationEntity
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageRepository
import kotlinx.coroutines.CancellationException

internal fun isVoiceBody(body: String?): Boolean = body != null && (
    body.startsWith("[AUDIO:") || body.startsWith("[AUDIO_LOCAL:") ||
        body.startsWith("[AUDIO_DOWNLOADING]") || body.startsWith("[AUDIO_FAILED:")
    )

internal fun voicePreviewHasEvidence(conversation: ConversationEntity, messages: List<MessageEntity>): Boolean {
    val timestamp = conversation.lastMessageAt ?: return false
    val own = messages.filter { it.conversationId == conversation.id }
    val latest = own.maxOfOrNull { it.createdAt } ?: return false
    // Outgoing voice previews are dated after upload, not at local-row insertion.
    if (latest > timestamp) return false
    val matching = own.filter { it.createdAt == latest }
    // The preview has no message ID. Mixed timestamp ties cannot identify its author reliably.
    return matching.isNotEmpty() && matching.all { isVoiceBody(it.plaintextCache) }
}

@Composable
internal fun conversationPreview(conversation: ConversationEntity, repository: MessageRepository): String {
    val preview = conversation.lastMessagePreview.orEmpty()
    val voiceLabel = stringResource(R.string.chat_list_voice_message)
    val mayBeVoice = preview == "Voice message" || preview == "🎤 Voice message" || isVoiceBody(preview)
    val provenVoice by produceState(false, conversation, repository) {
        value = false
        val timestamp = conversation.lastMessageAt
        if (mayBeVoice && timestamp != null) {
            value = try {
                voicePreviewHasEvidence(conversation, repository.getLatestMessages(conversation.id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }
    return if (provenVoice) voiceLabel else preview
}
