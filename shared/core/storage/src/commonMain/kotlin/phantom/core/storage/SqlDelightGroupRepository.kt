// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import phantom.core.storage.db.PhantomDatabase

class SqlDelightGroupRepository(
    private val db: PhantomDatabase,
) : GroupRepository {

    override suspend fun insertGroup(entity: GroupEntity): Unit =
        withContext(Dispatchers.IO) {
            db.groupQueries.insertGroup(
                id         = entity.id,
                name       = entity.name,
                my_role    = entity.myRole,
                is_channel = if (entity.isChannel) 1L else 0L,
                created_at = entity.createdAt,
            )
        }

    override suspend fun getGroups(): List<GroupEntity> =
        withContext(Dispatchers.IO) {
            db.groupQueries.getGroups().executeAsList().map { it.toEntity() }
        }

    override suspend fun getGroup(groupId: String): GroupEntity? =
        withContext(Dispatchers.IO) {
            db.groupQueries.getGroup(groupId).executeAsOneOrNull()?.toEntity()
        }

    override suspend fun deleteGroup(groupId: String): Unit =
        withContext(Dispatchers.IO) {
            db.groupQueries.deleteGroup(groupId)
        }

    override suspend fun updateLastMessage(groupId: String, preview: String, at: Long): Unit =
        withContext(Dispatchers.IO) {
            db.groupQueries.updateGroupLastMessage(
                preview = preview,
                at      = at,
                id      = groupId,
            )
        }

    override suspend fun resetUnread(groupId: String): Unit =
        withContext(Dispatchers.IO) {
            db.groupQueries.resetGroupUnread(groupId)
        }

    override suspend fun insertMember(member: GroupMemberEntity): Unit =
        withContext(Dispatchers.IO) {
            db.groupMemberQueries.insertMember(
                group_id   = member.groupId,
                pubkey_hex = member.pubkeyHex,
                username   = member.username,
                joined_at  = member.joinedAt,
            )
        }

    override suspend fun getMembers(groupId: String): List<GroupMemberEntity> =
        withContext(Dispatchers.IO) {
            db.groupMemberQueries.getGroupMembers(groupId).executeAsList().map { it.toEntity() }
        }

    override suspend fun getMemberCount(groupId: String): Long =
        withContext(Dispatchers.IO) {
            db.groupMemberQueries.getMemberCount(groupId).executeAsOne()
        }

    override suspend fun deleteMember(groupId: String, pubkeyHex: String): Unit =
        withContext(Dispatchers.IO) {
            db.groupMemberQueries.deleteMember(
                group_id   = groupId,
                pubkey_hex = pubkeyHex,
            )
        }

    // ---------------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------------

    private fun Phantom_group.toEntity() = GroupEntity(
        id                  = id,
        name                = name,
        myRole              = my_role,
        isChannel           = is_channel != 0L,
        createdAt           = created_at,
        lastMessagePreview  = last_message_preview,
        lastMessageAt       = last_message_at,
        unreadCount         = unread_count,
    )

    private fun Group_member.toEntity() = GroupMemberEntity(
        groupId    = group_id,
        pubkeyHex  = pubkey_hex,
        username   = username,
        joinedAt   = joined_at,
    )
}
