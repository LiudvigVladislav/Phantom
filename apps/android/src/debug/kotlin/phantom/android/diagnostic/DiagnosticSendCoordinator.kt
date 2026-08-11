// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import com.benasher44.uuid.uuid4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import phantom.core.messaging.MessagingService
import phantom.core.messaging.OutgoingMessage
import phantom.core.storage.ConversationEntity
import phantom.core.storage.ConversationRepository

/**
 * Direct WSS Yota-First diagnostic — debug-only send coordinator.
 *
 * §11 lock 3 (architect FINAL GREEN): conversation selection is
 * automatic. After a clean bootstrap, each device has EXACTLY ONE
 * paired conversation whose peer is the other device in the
 * matrix. The coordinator queries [ConversationRepository]
 * (`getActiveConversations()`), filters to non-blocked, non-archived
 * peers, and requires exactly one match; otherwise it fails-red
 * without touching [MessagingService.sendMessage].
 *
 * §11 lock 3 also forbids an operator-supplied `contact_alias` —
 * the ADB caller cannot influence which peer receives the send.
 *
 * §11 additional test (2): the coordinator invokes
 * `MessagingService.sendMessage` exactly ONCE per send subcommand,
 * with text derived internally as `YOTA-WSS-${cellId}-${sequence}`.
 * No external text extra is accepted; the receiver has already
 * rejected any unknown key.
 *
 * The coordinator is entirely debug-only — this file lives under
 * `src/debug/` and is physically absent from the release APK.
 */
internal class DiagnosticSendCoordinator(
    private val conversationRepository: ConversationRepository,
    private val messagingService: MessagingService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    sealed interface Outcome {
        data class Sent(val correlationId: String, val conversationId: String) : Outcome
        data class NoPairedConversation(val activeCount: Int) : Outcome
        data class MultiplePairedConversations(val ids: List<String>) : Outcome
    }

    /**
     * Trigger exactly one production `sendMessage` call. Runs the
     * repository lookup + send on Dispatchers.IO to avoid touching
     * the receiver's onReceive thread.
     *
     * `correlationId` is generated via `uuid4()` — same code path as
     * production `ChatScreen.kt:1074`. Retries within the same cell
     * (out of scope for this coordinator) would reuse the returned
     * id; each invocation of [triggerSend] produces a fresh id.
     */
    suspend fun resolveAndSend(cellId: String, sequence: Int): Outcome {
        val paired = eligiblePairedConversations()
        return when {
            paired.isEmpty() -> Outcome.NoPairedConversation(activeCount = 0)
            paired.size > 1 -> Outcome.MultiplePairedConversations(
                ids = paired.map { it.id },
            )
            else -> {
                val peer = paired.single()
                val correlationId = uuid4().toString()
                val message = OutgoingMessage(
                    id = correlationId,
                    conversationId = peer.id,
                    recipientPublicKeyHex = peer.theirPublicKeyHex,
                    text = derivedTextFor(cellId, sequence),
                )
                messagingService.sendMessage(message)
                Outcome.Sent(correlationId = correlationId, conversationId = peer.id)
            }
        }
    }

    private suspend fun eligiblePairedConversations(): List<ConversationEntity> =
        conversationRepository.getActiveConversations()
            .filterNot { it.blocked || it.archived }

    /**
     * §11 lock 3 + additional test 2: send text is derived
     * internally. Operator cannot inject arbitrary text; no `text`
     * extra is defined in the receiver whitelist. Regenerating a
     * cell's text is deterministic from its `cell_id` + `sequence`.
     */
    private fun derivedTextFor(cellId: String, sequence: Int): String =
        "YOTA-WSS-$cellId-$sequence"

    /** Test seam: expose the derivation for the focused test. */
    internal fun derivedTextForTest(cellId: String, sequence: Int): String =
        derivedTextFor(cellId, sequence)

    fun asyncTrigger(cellId: String, sequence: Int, onDone: (Outcome) -> Unit) {
        scope.launch {
            val outcome = resolveAndSend(cellId, sequence)
            onDone(outcome)
        }
    }
}
