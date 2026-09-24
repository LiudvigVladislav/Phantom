// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

/** Existing-row settlement data for one decrypted group-message envelope. */
data class GroupEnvelopeMetadata(
    val envelopeId: String,
    val conversationId: String,
    val senderPubKeyHex: String,
    val payloadType: String,
    val nowMs: Long,
)

/**
 * Atomically advances the incoming SenderKey, persists the decoded group
 * effect, and records the outer envelope as processed. No schema is added:
 * every write targets an existing SQLDelight table.
 */
interface GroupMessageCommitRepository {
    sealed class Effect {
        data object Ignored : Effect()

        data class AudioChunk(
            val voiceId: String,
            val idx: Int,
            val total: Int,
            val groupId: String,
            val senderPubKeyHex: String,
            val mimeType: String,
            val durationMs: Long,
            val chunkBytes: ByteArray,
            val nowMs: Long,
        ) : Effect()

        data class Message(
            val entity: MessageEntity,
            val preview: String,
            val previewAtMs: Long,
        ) : Effect()
    }

    suspend fun commit(
        senderKey: SenderKeyEntity,
        effect: Effect,
        envelope: GroupEnvelopeMetadata,
    )
}
