// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

interface GroupRepository {
    suspend fun insertGroup(entity: GroupEntity)
    suspend fun getGroups(): List<GroupEntity>
    suspend fun getGroup(groupId: String): GroupEntity?
    suspend fun deleteGroup(groupId: String)
    suspend fun updateLastMessage(groupId: String, preview: String, at: Long)
    suspend fun resetUnread(groupId: String)

    suspend fun insertMember(member: GroupMemberEntity)
    suspend fun getMembers(groupId: String): List<GroupMemberEntity>
    suspend fun getMemberCount(groupId: String): Long
    suspend fun deleteMember(groupId: String, pubkeyHex: String)
}
