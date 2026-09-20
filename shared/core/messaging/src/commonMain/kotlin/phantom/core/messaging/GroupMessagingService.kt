// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.coroutines.flow.Flow

enum class GroupRecipientOutcome {
    SUBMITTED,
    FAILED_TO_SUBMIT,
    NOT_ATTEMPTED,
}

data class GroupChunkFailure(
    val recipientPublicKeyHex: String,
    val chunkIndex: Int,
    val outcome: GroupRecipientOutcome,
    val reason: String,
)

data class GroupSendReport(
    val recipientOutcomes: Map<String, GroupRecipientOutcome>,
    val chunkFailures: List<GroupChunkFailure>,
) {
    val recipientCount: Int get() = recipientOutcomes.size
    val submittedCount: Int get() = recipientOutcomes.values.count { it == GroupRecipientOutcome.SUBMITTED }
    val failedToSubmitCount: Int get() = recipientOutcomes.values.count { it == GroupRecipientOutcome.FAILED_TO_SUBMIT }
    val notAttemptedCount: Int get() = recipientOutcomes.values.count { it == GroupRecipientOutcome.NOT_ATTEMPTED }
    val incompleteCount: Int get() = failedToSubmitCount + notAttemptedCount

    // Owner-selected GA-S2 contract. These names describe the transport
    // submission boundary only; they make no relay, delivery, decrypt or ACK
    // claim. The per-recipient map and chunk details remain available for
    // diagnostics and deterministic short-circuit tests.
    val attempted: Int get() = submittedCount + failedToSubmitCount
    val submitted: Int get() = submittedCount
    val failedToSubmit: Int get() = failedToSubmitCount
    val notAttempted: Int get() = notAttemptedCount
    val failures: List<GroupChunkFailure> get() = chunkFailures
}

internal class GroupAudioReportAccumulator(recipientPublicKeys: List<String>) {
    private val outcomes = recipientPublicKeys.distinct()
        .associateWith { GroupRecipientOutcome.SUBMITTED }
        .toMutableMap()
    private val failures = mutableListOf<GroupChunkFailure>()

    fun activeRecipientPublicKeys(): Set<String> = outcomes
        .filterValues { it == GroupRecipientOutcome.SUBMITTED }
        .keys

    fun recordFailure(
        recipientPublicKeyHex: String,
        chunkIndex: Int,
        failure: Throwable,
    ) {
        check(recipientPublicKeyHex in outcomes) { "unknown group recipient" }
        check(outcomes[recipientPublicKeyHex] == GroupRecipientOutcome.SUBMITTED) {
            "group recipient was already classified"
        }
        val outcome = if (failure is OutboundNotAttemptedException) {
            GroupRecipientOutcome.NOT_ATTEMPTED
        } else {
            GroupRecipientOutcome.FAILED_TO_SUBMIT
        }
        outcomes[recipientPublicKeyHex] = outcome
        failures += GroupChunkFailure(
            recipientPublicKeyHex = recipientPublicKeyHex,
            chunkIndex = chunkIndex,
            outcome = outcome,
            reason = failure.message.orEmpty(),
        )
    }

    fun build(): GroupSendReport = GroupSendReport(
        recipientOutcomes = outcomes.toMap(),
        chunkFailures = failures.toList(),
    )
}

internal suspend fun collectGroupAudioReport(
    recipientPublicKeys: List<String>,
    chunkCount: Int,
    sendChunk: suspend (
        chunkIndex: Int,
        activeRecipientPublicKeys: Set<String>,
    ) -> Map<String, Result<Unit>>,
): GroupSendReport {
    val reports = GroupAudioReportAccumulator(recipientPublicKeys)
    for (chunkIndex in 0 until chunkCount) {
        val active = reports.activeRecipientPublicKeys()
        if (active.isEmpty()) break
        val results = sendChunk(chunkIndex, active)
        check(results.keys == active) {
            "group audio fan-out must classify every active recipient exactly once per chunk"
        }
        results.forEach { (recipientPublicKeyHex, result) ->
            result.exceptionOrNull()?.let { failure ->
                reports.recordFailure(recipientPublicKeyHex, chunkIndex, failure)
            }
        }
    }
    return reports.build()
}

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

    /**
     * Chunk [audioBytes] into 64 KB envelopes and fan-out each chunk to every member.
     * Signature accepts raw bytes (not base64) so the chunking logic in
     * [DefaultGroupMessagingService] controls slice sizes — callers no longer
     * pre-encode, which avoids the ~33 % base64 expansion before the size cap check.
     */
    suspend fun sendGroupAudio(
        groupId: String,
        audioBytes: ByteArray,
        durationMs: Long,
        mimeType: String,
    ): Result<GroupSendReport>

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
