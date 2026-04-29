// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.navigation

sealed class Screen {
    object Onboarding : Screen()
    object ChatList : Screen()
    object Calls : Screen()
    object Nearby : Screen()
    object Premium : Screen()
    object Settings : Screen()
    object MessageRequests : Screen()
    object Profile : Screen()
    object QrScan : Screen()
    object SavedMessages : Screen()
    object Archive : Screen()
    object CreateGroup : Screen()
    object CreateChannel : Screen()
    data class Chat(val conversationId: String, val theirUsername: String) : Screen()
    data class ContactProfile(val conversationId: String, val theirUsername: String) : Screen()
    data class GroupChat(val groupId: String, val groupName: String, val isChannel: Boolean) : Screen()
    data class ActiveCall(val conversationId: String, val username: String) : Screen()
    data class IncomingCall(val conversationId: String, val username: String) : Screen()
}
