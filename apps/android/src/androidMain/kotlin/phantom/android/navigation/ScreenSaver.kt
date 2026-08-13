// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.navigation

import androidx.compose.runtime.saveable.Saver

/**
 * Onboarding-stabilization block 2026-08-11 — Profile rotation fix.
 *
 * `MainActivity.currentScreen` used `remember { mutableStateOf<Screen?>(null) }`,
 * so an Activity recreation (rotation, dark-mode toggle, split-screen) wiped
 * the current route to `null` and the startup `LaunchedEffect` walked disk
 * back to `Screen.ChatList` — dropping the user out of Profile (or any other
 * screen) into Chats on every rotation. `rememberSaveable` with this saver
 * preserves the route across recreation.
 *
 * Each Screen variant serialises as a `List<Any?>` whose first element is the
 * variant tag; data-class variants trail their fields verbatim. `null` and
 * transient states (`StartupError`) round-trip too — `StartupError` carries a
 * transient reason name that is safe to survive a rotation because the caller
 * re-derives it on the next startup pass anyway.
 */
internal val ScreenSaver: Saver<Screen?, List<Any?>> = Saver(
    save = { screen ->
        when (screen) {
            null -> listOf("null")
            Screen.Onboarding -> listOf("Onboarding")
            Screen.Migration -> listOf("Migration")
            Screen.ChatList -> listOf("ChatList")
            is Screen.StartupError -> listOf("StartupError", screen.reasonName)
            Screen.Calls -> listOf("Calls")
            Screen.Nearby -> listOf("Nearby")
            Screen.Premium -> listOf("Premium")
            Screen.Settings -> listOf("Settings")
            Screen.PrivacyModeDetail -> listOf("PrivacyModeDetail")
            Screen.MessageRequests -> listOf("MessageRequests")
            Screen.Profile -> listOf("Profile")
            Screen.AddContact -> listOf("AddContact")
            Screen.QrScan -> listOf("QrScan")
            Screen.SavedMessages -> listOf("SavedMessages")
            Screen.Archive -> listOf("Archive")
            Screen.CreateGroup -> listOf("CreateGroup")
            Screen.CreateChannel -> listOf("CreateChannel")
            is Screen.Chat -> listOf("Chat", screen.conversationId, screen.theirUsername)
            is Screen.ContactProfile ->
                listOf("ContactProfile", screen.conversationId, screen.theirUsername)
            is Screen.Verify -> listOf("Verify", screen.conversationId, screen.theirUsername)
            is Screen.GroupChat ->
                listOf("GroupChat", screen.groupId, screen.groupName, screen.isChannel)
            is Screen.ActiveCall -> listOf("ActiveCall", screen.conversationId, screen.username)
            is Screen.IncomingCall ->
                listOf("IncomingCall", screen.conversationId, screen.username)
        }
    },
    restore = { list ->
        when (val tag = list[0] as String) {
            "null" -> null
            "Onboarding" -> Screen.Onboarding
            "Migration" -> Screen.Migration
            "ChatList" -> Screen.ChatList
            "StartupError" -> Screen.StartupError(list[1] as String)
            "Calls" -> Screen.Calls
            "Nearby" -> Screen.Nearby
            "Premium" -> Screen.Premium
            "Settings" -> Screen.Settings
            "PrivacyModeDetail" -> Screen.PrivacyModeDetail
            "MessageRequests" -> Screen.MessageRequests
            "Profile" -> Screen.Profile
            "AddContact" -> Screen.AddContact
            "QrScan" -> Screen.QrScan
            "SavedMessages" -> Screen.SavedMessages
            "Archive" -> Screen.Archive
            "CreateGroup" -> Screen.CreateGroup
            "CreateChannel" -> Screen.CreateChannel
            "Chat" -> Screen.Chat(list[1] as String, list[2] as String)
            "ContactProfile" -> Screen.ContactProfile(list[1] as String, list[2] as String)
            "Verify" -> Screen.Verify(list[1] as String, list[2] as String)
            "GroupChat" ->
                Screen.GroupChat(list[1] as String, list[2] as String, list[3] as Boolean)
            "ActiveCall" -> Screen.ActiveCall(list[1] as String, list[2] as String)
            "IncomingCall" -> Screen.IncomingCall(list[1] as String, list[2] as String)
            else -> error("Unknown Screen tag in saved state: $tag")
        }
    },
)
