package phantom.android.screens.chatlist

import org.junit.Test
import phantom.core.storage.ConversationEntity
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageStatus
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationPreviewTest {
    private val conversation = ConversationEntity("chat", "peer", "abcd", "Voice message", 42L, 0L)
    private fun message(body: String?) = MessageEntity("message", "chat", byteArrayOf(), body, true, MessageStatus.SENT, 42L)

    @Test fun allExistingVoiceStatesHaveEvidence() {
        listOf("[AUDIO:AA==]", "[AUDIO_LOCAL:voice.enc]", "[AUDIO_DOWNLOADING]", "[AUDIO_FAILED:reason]").forEach {
            assertTrue(voicePreviewHasEvidence(conversation, listOf(message(it))))
        }
    }
    @Test fun literalUserTextIsNeverTranslated() {
        listOf("Voice message", "🎤 Voice message", "Голосовое сообщение", "I said [AUDIO:AA==]", "").forEach {
            assertFalse(voicePreviewHasEvidence(conversation, listOf(message(it))))
        }
    }
    @Test fun missingEvidenceDoesNotInventVoiceType() {
        assertFalse(voicePreviewHasEvidence(conversation, emptyList()))
        assertFalse(voicePreviewHasEvidence(conversation, listOf(message(null))))
        assertFalse(voicePreviewHasEvidence(conversation.copy(lastMessageAt = null), listOf(message("[AUDIO:AA==]"))))
    }
    @Test fun otherConversationOrNewerUnpublishedMessageCannotClassifyPreview() {
        assertFalse(voicePreviewHasEvidence(conversation, listOf(message("[AUDIO:AA==]").copy(conversationId = "other"))))
        assertFalse(voicePreviewHasEvidence(conversation, listOf(message("[AUDIO:AA==]").copy(createdAt = 43L))))
    }
    @Test fun outgoingVoicePreviewTimestampCanBeAfterUpload() {
        assertTrue(voicePreviewHasEvidence(conversation.copy(lastMessageAt = 500L), listOf(message("[AUDIO:AA==]"))))
    }
    @Test fun olderVoiceCannotTranslateTheLatestLiteralText() {
        assertFalse(voicePreviewHasEvidence(conversation, listOf(message("[AUDIO:AA==]").copy(createdAt = 41L), message("Voice message"))))
    }
    @Test fun mixedTimestampTieRemainsUntranslated() {
        val voice = message("[AUDIO:AA==]")
        assertFalse(voicePreviewHasEvidence(conversation, listOf(voice, message("Voice message").copy(id = "second"))))
        assertTrue(voicePreviewHasEvidence(conversation, listOf(voice, voice.copy(id = "second"))))
    }
}
