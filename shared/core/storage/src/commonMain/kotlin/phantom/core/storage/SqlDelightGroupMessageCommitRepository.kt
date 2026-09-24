// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

class SqlDelightGroupMessageCommitRepository(
    private val db: PhantomDatabase,
) : GroupMessageCommitRepository {
    override suspend fun commit(
        senderKey: SenderKeyEntity,
        effect: GroupMessageCommitRepository.Effect,
        envelope: GroupEnvelopeMetadata,
    ): Unit = withContext(Dispatchers.IO) {
        db.transaction {
            db.senderKeyStoreQueries.upsertSenderKey(
                group_id = senderKey.groupId,
                member_pubkey_hex = senderKey.memberPubkeyHex,
                chain_key_hex = senderKey.chainKeyHex,
                iteration = senderKey.iteration,
            )
            when (effect) {
                GroupMessageCommitRepository.Effect.Ignored -> Unit
                is GroupMessageCommitRepository.Effect.AudioChunk ->
                    db.voiceChunkQueries.insertChunk(
                        voice_id = effect.voiceId,
                        idx = effect.idx.toLong(),
                        total = effect.total.toLong(),
                        conversation_id = effect.groupId,
                        sender_pubkey_hex = effect.senderPubKeyHex,
                        mime_type = effect.mimeType,
                        duration_ms = effect.durationMs,
                        chunk_bytes = effect.chunkBytes,
                        updated_at_ms = effect.nowMs,
                    )
                is GroupMessageCommitRepository.Effect.Message -> {
                    val message = effect.entity
                    db.messageQueries.insertMessage(
                        id = message.id,
                        conversation_id = message.conversationId,
                        ciphertext = message.ciphertext,
                        plaintext_cache = message.plaintextCache,
                        sent = if (message.sent) 1L else 0L,
                        status = message.status.name.lowercase(),
                        created_at = message.createdAt,
                        expires_at_ms = message.expiresAtMs,
                    )
                    db.groupQueries.updateGroupLastMessage(
                        preview = effect.preview,
                        at = effect.previewAtMs,
                        id = message.conversationId,
                    )
                }
            }
            db.processedEnvelopeQueries.markProcessed(
                envelope_id = envelope.envelopeId,
                conversation_id = envelope.conversationId,
                sender_pubkey_hex = envelope.senderPubKeyHex,
                payload_type = envelope.payloadType,
                status = ProcessedEnvelopeRepository.Status.PROCESSED.wire,
                created_at_ms = envelope.nowMs,
            )
        }
    }
}
