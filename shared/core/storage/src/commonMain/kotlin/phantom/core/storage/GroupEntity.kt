// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

data class GroupEntity(
    val id: String,
    val name: String,
    val myRole: String,          // "admin" | "member" | "subscriber"
    val isChannel: Boolean,
    val createdAt: Long,
    val lastMessagePreview: String? = null,
    val lastMessageAt: Long? = null,
    val unreadCount: Long = 0,
)
