// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

class SqlDelightSenderKeyRepository(
    private val db: PhantomDatabase,
) : SenderKeyRepository {

    override suspend fun get(groupId: String, memberPubkeyHex: String): SenderKeyEntity? =
        withContext(Dispatchers.IO) {
            db.senderKeyStoreQueries.getSenderKey(
                group_id          = groupId,
                member_pubkey_hex = memberPubkeyHex,
            ).executeAsOneOrNull()?.toEntity()
        }

    override suspend fun upsert(entity: SenderKeyEntity): Unit =
        withContext(Dispatchers.IO) {
            db.senderKeyStoreQueries.upsertSenderKey(
                group_id          = entity.groupId,
                member_pubkey_hex = entity.memberPubkeyHex,
                chain_key_hex     = entity.chainKeyHex,
                iteration         = entity.iteration,
                signing_pub_hex   = entity.signingPubHex,
                signing_priv_hex  = entity.signingPrivHex,
            )
        }

    override suspend fun deleteForGroup(groupId: String): Unit =
        withContext(Dispatchers.IO) {
            db.senderKeyStoreQueries.deleteSenderKeysForGroup(groupId)
        }

    // ---------------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------------

    private fun Sender_key_store.toEntity() = SenderKeyEntity(
        groupId          = group_id,
        memberPubkeyHex  = member_pubkey_hex,
        chainKeyHex      = chain_key_hex,
        iteration        = iteration,
        signingPubHex    = signing_pub_hex,
        signingPrivHex   = signing_priv_hex,
    )
}
