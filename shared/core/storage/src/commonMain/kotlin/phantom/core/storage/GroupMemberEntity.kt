// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

data class GroupMemberEntity(
    val groupId: String,
    val pubkeyHex: String,
    val username: String,
    val joinedAt: Long,
)
