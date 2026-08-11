// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import phantom.core.messaging.IncomingMessage
import phantom.core.messaging.MessagePayload
import phantom.core.messaging.MessagingService
import phantom.core.messaging.OutgoingMessage
import phantom.core.storage.ConversationEntity
import phantom.core.storage.ConversationRepository
import phantom.core.storage.TrustTier

/**
 * Direct WSS Yota-First — §11 lock 4 test #2:
 * `DiagnosticSendCoordinator` invokes production `sendMessage`
 * exactly once with derived text; refuses when the paired-conversation
 * assumption is broken.
 *
 * §11 lock 3 (automatic conversation selection): no `contact_alias`
 * argument, no operator-supplied peer.
 */
class DiagnosticSendCoordinatorTest {

    /**
     * Minimal ConversationRepository fake — implements every abstract
     * member (a KMP interface with 30+ methods) but throws on any
     * method the coordinator does not call. This makes accidental
     * scope creep in the coordinator fail-red immediately.
     */
    private class MinimalConversationRepository(
        private val entities: List<ConversationEntity>,
    ) : ConversationRepository {
        override suspend fun getActiveConversations(): List<ConversationEntity> = entities
        override suspend fun getAllConversations(): List<ConversationEntity> = error("must not be called: "+"getAllConversations")
        override suspend fun getMessageRequests(): List<ConversationEntity> = error("must not be called: "+"getMessageRequests")
        override suspend fun getConversation(id: String): ConversationEntity? = error("must not be called: "+"getConversation")
        override suspend fun upsertConversation(entity: ConversationEntity) { error("must not be called: "+"upsertConversation") }
        override suspend fun incrementUnread(conversationId: String) { error("must not be called: "+"incrementUnread") }
        override suspend fun resetUnread(conversationId: String) { error("must not be called: "+"resetUnread") }
        override suspend fun updateNotes(conversationId: String, notes: String?) { error("must not be called: "+"updateNotes") }
        override suspend fun getBlockedConversations(): List<ConversationEntity> = error("must not be called: "+"getBlockedConversations")
        override suspend fun blockConversation(conversationId: String) { error("must not be called: "+"blockConversation") }
        override suspend fun unblockConversation(conversationId: String) { error("must not be called: "+"unblockConversation") }
        override suspend fun acceptRequest(conversationId: String) { error("must not be called: "+"acceptRequest") }
        override suspend fun deleteConversation(id: String) { error("must not be called: "+"deleteConversation") }
        override suspend fun setVerified(conversationId: String, verified: Boolean) { error("must not be called: "+"setVerified") }
        override suspend fun setDisappearingTimer(conversationId: String, secs: Long) { error("must not be called: "+"setDisappearingTimer") }
        override suspend fun getDisappearingTimer(conversationId: String): Long = error("must not be called: "+"getDisappearingTimer")
        override suspend fun archiveConversation(id: String) { error("must not be called: "+"archiveConversation") }
        override suspend fun unarchiveConversation(id: String) { error("must not be called: "+"unarchiveConversation") }
        override suspend fun getArchivedConversations(): List<ConversationEntity> = error("must not be called: "+"getArchivedConversations")
        override suspend fun setIdentityKeyChangedAt(conversationId: String, ts: Long) { error("must not be called: "+"setIdentityKeyChangedAt") }
        override suspend fun clearIdentityKeyChangedAt(conversationId: String) { error("must not be called: "+"clearIdentityKeyChangedAt") }
        override suspend fun setMutedUntil(conversationId: String, until: Long?) { error("must not be called: "+"setMutedUntil") }
        override suspend fun setPinned(conversationId: String, pinned: Boolean) { error("must not be called: "+"setPinned") }
        override suspend fun setNeedsRehandshake(conversationId: String, needs: Boolean) { error("must not be called: "+"setNeedsRehandshake") }
        override suspend fun markAllNeedsRehandshake() { error("must not be called: "+"markAllNeedsRehandshake") }
        override suspend fun setSessionSuspect(conversationId: String, setAtMs: Long) { error("must not be called: "+"setSessionSuspect") }
        override suspend fun clearSessionSuspect(conversationId: String) { error("must not be called: "+"clearSessionSuspect") }
        override suspend fun getSessionSuspectConversations(): List<ConversationEntity> = error("must not be called: "+"getSessionSuspectConversations")

    }

    private class RecordingMessagingService : MessagingService {
        val sends = mutableListOf<OutgoingMessage>()
        val callCount = AtomicInteger(0)
        override val bootstrapReady: StateFlow<Boolean> = MutableStateFlow(true)
        override val incomingMessages: Flow<IncomingMessage> = MutableSharedFlow()
        override suspend fun sendMessage(message: OutgoingMessage): Result<Unit> {
            callCount.incrementAndGet()
            sends += message
            return Result.success(Unit)
        }
        override suspend fun sendAudio(conversationId: String, audioBytes: ByteArray, durationMs: Long, mimeType: String) = error("must not be called: "+"sendAudio")
        override suspend fun startReceiving() = error("must not be called: "+"startReceiving")
        override suspend fun retryWaitingMessages(source: String): Result<Int> = error("must not be called: "+"retryWaitingMessages")
        override suspend fun markConversationRead(conversationId: String, theirPublicKeyHex: String, sendReceipt: Boolean) = error("must not be called: "+"markConversationRead")
        override suspend fun deleteMessageForBoth(messageId: String, conversationId: String, recipientPublicKeyHex: String) = error("must not be called: "+"deleteMessageForBoth")
        override suspend fun editMessageForBoth(messageId: String, newText: String, conversationId: String, recipientPublicKeyHex: String) = error("must not be called: "+"editMessageForBoth")
        override suspend fun sendDisappearingTimerUpdate(timerSecs: Long, conversationId: String, recipientPublicKeyHex: String) = error("must not be called: "+"sendDisappearingTimerUpdate")
        override suspend fun sendReaction(messageId: String, conversationId: String, recipientPublicKeyHex: String, emoji: String) = error("must not be called: "+"sendReaction")
        override suspend fun pinMessageForBoth(messageId: String, conversationId: String, recipientPublicKeyHex: String, pinned: Boolean) = error("must not be called: "+"pinMessageForBoth")
        override suspend fun sendCallSignal(recipientPublicKeyHex: String, payload: MessagePayload) = error("must not be called: "+"sendCallSignal")
        override suspend fun sendGroupControlMessage(toPubKeyHex: String, payload: MessagePayload) = error("must not be called: "+"sendGroupControlMessage")

    }

    private fun pairedConversation(id: String = "conv-1"): ConversationEntity = ConversationEntity(
        id = id,
        theirUsername = "peer",
        theirPublicKeyHex = "b".repeat(64),
        lastMessagePreview = null,
        lastMessageAt = null,
        unreadCount = 0,
        trustTier = TrustTier.TRUSTED,
        blocked = false,
        notes = null,
        isVerified = false,
        disappearingTimerSecs = 0L,
        archived = false,
    )

    @Test
    fun single_paired_conversation_send_calls_production_api_exactly_once() = runTest {
        val repo = MinimalConversationRepository(listOf(pairedConversation()))
        val svc = RecordingMessagingService()
        val coord = DiagnosticSendCoordinator(repo, svc)

        val outcome = coord.resolveAndSend(cellId = "wss.p2e.after-connect.envelope-1", sequence = 1)

        assertTrue(outcome is DiagnosticSendCoordinator.Outcome.Sent)
        assertEquals(1, svc.callCount.get())
        assertEquals(1, svc.sends.size)
        assertEquals("conv-1", svc.sends.single().conversationId)
        // §11 lock 4 test #2: no external text — text is derived internally.
        assertEquals("YOTA-WSS-wss.p2e.after-connect.envelope-1-1", svc.sends.single().text)
    }

    @Test
    fun no_paired_conversation_returns_failure_without_calling_send() = runTest {
        val repo = MinimalConversationRepository(emptyList())
        val svc = RecordingMessagingService()
        val coord = DiagnosticSendCoordinator(repo, svc)

        val outcome = coord.resolveAndSend(cellId = "wss.p2e.after-connect.envelope-1", sequence = 1)

        assertTrue(outcome is DiagnosticSendCoordinator.Outcome.NoPairedConversation)
        assertEquals(0, svc.callCount.get())
        assertEquals(0, svc.sends.size)
    }

    @Test
    fun multiple_paired_conversations_returns_failure_without_calling_send() = runTest {
        val repo = MinimalConversationRepository(
            listOf(pairedConversation("conv-1"), pairedConversation("conv-2")),
        )
        val svc = RecordingMessagingService()
        val coord = DiagnosticSendCoordinator(repo, svc)

        val outcome = coord.resolveAndSend(cellId = "wss.p2e.after-connect.envelope-1", sequence = 1)

        assertTrue(outcome is DiagnosticSendCoordinator.Outcome.MultiplePairedConversations)
        assertEquals(0, svc.callCount.get())
        assertEquals(0, svc.sends.size)
    }

    @Test
    fun derived_text_is_deterministic_from_cell_id_and_sequence() {
        val repo = MinimalConversationRepository(emptyList())
        val svc = RecordingMessagingService()
        val coord = DiagnosticSendCoordinator(repo, svc)

        assertEquals(
            "YOTA-WSS-rest.e2p.control-3",
            coord.derivedTextForTest("rest.e2p.control", 3),
        )
    }
}
