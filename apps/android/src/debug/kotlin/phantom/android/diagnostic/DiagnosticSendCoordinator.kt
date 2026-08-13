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
 * with text derived internally as `WSS-DIAG-${cellId}-${sequence}`.
 * The prefix is carrier-neutral (was `YOTA-WSS-` in the WSS-Round-1
 * scope; renamed for the WSS-2 Yota+Tele2 matrix so the derived
 * text does not falsely imply a Yota-only diagnostic). No external
 * text extra is accepted; the receiver has already rejected any
 * unknown key.
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
        data class Sent(
            val correlationId: String,
            val conversationId: String,
            val sendResult: SendResult,
        ) : Outcome
        data class NoPairedConversation(val activeCount: Int) : Outcome
        data class MultiplePairedConversations(val ids: List<String>) : Outcome
    }

    /**
     * §12 Round-7 audit P0-1: capture what `MessagingService.sendMessage`
     * actually returned so the receiver can emit
     * `diagnostic_send_command_completed result=handled|exception`.
     *
     * `Handled` (was `Accepted` in Round-6) is deliberately NEUTRAL —
     * `sendMessage` returned without throwing, but that alone does
     * NOT prove the envelope reached the network: e.g. a
     * `PeerBundleMissingException` on a fresh pair is caught inside
     * `DefaultMessagingService`, a WAITING placeholder is written,
     * and `Result.success(Unit)` returned. The verifier separately
     * looks for `sender_prekey_deferred` on the same correlation id
     * to distinguish "coordinator saw a definitive result" from
     * "envelope actually reached the transport". `deferred` was
     * reserved in the Round-7 closed schema but §12 Round-9 audit
     * P2 removed it — production only emits handled | rejected |
     * exception; `sender_prekey_deferred` is a distinct sender
     * event, not a command_completed result value.
     *
     * No exception text, message text, usernames, keys, tokens or
     * PII — only the exception class simple name.
     */
    sealed interface SendResult {
        object Handled : SendResult
        data class Failed(val exceptionClassName: String) : SendResult
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
        when {
            paired.isEmpty() -> {
                // §12 Round-8 audit P0: emit BOTH the rejected event
                // and the closed-schema completed event from HERE, not
                // from the receiver. Rejected paths never touch
                // MessagingService.sendMessage so no hang is possible;
                // the completed event carries no correlation_id.
                WssDiag.emit(
                    event = "diagnostic_send_rejected_no_paired_conversation",
                    role = WssDiag.Role.MATRIX,
                    sequence = sequence,
                )
                WssDiag.emit(
                    event = "diagnostic_send_command_completed",
                    role = WssDiag.Role.MATRIX,
                    sequence = sequence,
                    result = "rejected",
                )
                return Outcome.NoPairedConversation(activeCount = 0)
            }
            paired.size > 1 -> {
                WssDiag.emit(
                    event = "diagnostic_send_rejected_multiple_paired_conversations",
                    role = WssDiag.Role.MATRIX,
                    sequence = sequence,
                )
                WssDiag.emit(
                    event = "diagnostic_send_command_completed",
                    role = WssDiag.Role.MATRIX,
                    sequence = sequence,
                    result = "rejected",
                )
                return Outcome.MultiplePairedConversations(ids = paired.map { it.id })
            }
        }
        val peer = paired.single()
        val correlationId = uuid4().toString()

        // §12 Round-8 audit P0: emit `diagnostic_send_dispatched`
        // BEFORE calling `messagingService.sendMessage`. If the
        // production WSS/REST call hangs indefinitely the runner
        // still sees the CID and can either poll delivery for it
        // (real product signal) or, if all four gate signals miss,
        // abort as tooling failure. In Round-7 this event fired
        // AFTER sendMessage returned; a hung send would leave the
        // runner with no CID and the gate could not distinguish
        // "network dropped" from "receiver never dispatched".
        WssDiag.emit(
            event = "diagnostic_send_dispatched",
            role = WssDiag.Role.MATRIX,
            correlationId = correlationId,
            sequence = sequence,
        )

        val message = OutgoingMessage(
            id = correlationId,
            conversationId = peer.id,
            recipientPublicKeyHex = peer.theirPublicKeyHex,
            text = derivedTextFor(cellId, sequence),
        )
        // §12 Round-7 audit P0-1: capture what sendMessage actually
        // returned. sendMessage is `runCatching`-wrapped so it
        // should never throw, but an outer try/catch defends
        // against a bug in that contract without leaking any
        // exception payload — only the class simple name flows into
        // diagnostics.
        val sendResult: SendResult = try {
            val r = messagingService.sendMessage(message)
            if (r.isSuccess) {
                SendResult.Handled
            } else {
                SendResult.Failed(
                    r.exceptionOrNull()?.let { it::class.simpleName } ?: "UnknownFailure",
                )
            }
        } catch (t: Throwable) {
            SendResult.Failed(t::class.simpleName ?: "UnknownThrowable")
        }
        // §12 Round-8 audit P0: emit `diagnostic_send_command_completed`
        // AFTER return/exception. The result value plus the
        // separate `sender_prekey_deferred` event let the verifier
        // distinguish "coordinator saw a definitive result" from
        // "envelope actually reached transport".
        val (result, exception) = when (sendResult) {
            is SendResult.Handled -> "handled" to null
            is SendResult.Failed -> "exception" to sendResult.exceptionClassName
        }
        WssDiag.emit(
            event = "diagnostic_send_command_completed",
            role = WssDiag.Role.MATRIX,
            correlationId = correlationId,
            sequence = sequence,
            result = result,
            outcomeFlag = if (exception != null) {
                WssDiag.OutcomeFlag.SEND_ERROR
            } else {
                WssDiag.OutcomeFlag.NONE
            },
        )
        return Outcome.Sent(
            correlationId = correlationId,
            conversationId = peer.id,
            sendResult = sendResult,
        )
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
        "WSS-DIAG-$cellId-$sequence"

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
