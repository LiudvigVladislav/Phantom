// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

data class SenderKeyEntity(
    val groupId: String,
    val memberPubkeyHex: String,
    val chainKeyHex: String,
    val iteration: Long,
    val signingPubHex: String,
    val signingPrivHex: String,
)
