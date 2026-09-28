// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.contact

import java.io.File
import kotlinx.coroutines.test.runTest
import phantom.core.messaging.LocalConversationDeletionOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactProfileLocalDeletionTest {
    @Test
    fun profile_uses_guarded_action_and_never_deletes_repository_directly() {
        var root = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            if (File(root, "apps/android/src/androidMain/kotlin/phantom/android/screens/contact/ContactProfileScreen.kt").exists()) {
                val source = File(root, "apps/android/src/androidMain/kotlin/phantom/android/screens/contact/ContactProfileScreen.kt").readText()
                val dialog = File(root, "apps/android/src/androidMain/kotlin/phantom/android/screens/contact/LocalConversationDeletionDialog.kt").readText()
                val chatList = File(root, "apps/android/src/androidMain/kotlin/phantom/android/screens/chatlist/ChatListScreen.kt").readText()
                assertTrue("LocalConversationDeletionDialog(" in source)
                assertTrue("LocalConversationDeletionDialog(" in chatList)
                assertTrue("val result = deleteLocalConversationAndNavigate(" in dialog)
                assertTrue("messagingService.deleteConversationLocally(conversationId)" in dialog)
                assertTrue("messagingService.clearConversationHistoryLocally(conversationId)" in dialog)
                assertTrue("DeleteStep.CONFIRM_CONTACT" in dialog)
                assertTrue("step = DeleteStep.CONFIRM_CONTACT" in dialog)
                assertTrue("performDeletion(removeContact = true)" in dialog)
                assertTrue("if (result.isFailure)" in dialog)
                assertFalse("container.conversationRepo.deleteConversation(" in source)
                return
            }
            root = root.parentFile ?: root
        }
        error("ContactProfileScreen.kt not found")
    }

    @Test
    fun success_navigates_once() = runTest {
        var calls = 0
        var navigations = 0
        val result = deleteLocalConversationAndNavigate(
            delete = { calls++; Result.success(LocalConversationDeletionOutcome(false)) },
            onDeleted = { navigations++ },
        )
        assertTrue(result.isSuccess)
        assertEquals(1, calls)
        assertEquals(1, navigations)
    }

    @Test
    fun refusal_does_not_navigate() = runTest {
        var navigations = 0
        val result = deleteLocalConversationAndNavigate(
            delete = { Result.failure(IllegalStateException("outbox busy")) },
            onDeleted = { navigations++ },
        )
        assertFalse(result.isSuccess)
        assertEquals(0, navigations)
    }
}
