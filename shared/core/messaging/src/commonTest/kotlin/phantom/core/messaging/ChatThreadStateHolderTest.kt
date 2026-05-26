// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageRepository
import phantom.core.storage.MessageStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatThreadStateHolderTest {

    private lateinit var scope: TestScope
    private lateinit var repo: FakeMessageRepo
    private lateinit var holder: ChatThreadStateHolder

    @BeforeTest
    fun setUp() {
        scope = TestScope()
        repo = FakeMessageRepo()
        holder = ChatThreadStateHolder(
            messageRepo = repo,
            scope = scope,
            cachePolicy = ChatThreadStateHolder.CachePolicy(maxConversations = 3),
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun snapshot_returnsEmptyForUncachedConversation() {
        assertEquals(emptyList(), holder.snapshot("conv-1"))
    }

    @Test
    fun observe_returnsSameInstanceForSameConversationId() {
        val a = holder.observe("conv-1")
        val b = holder.observe("conv-1")
        assertSame(a, b, "observe() must return the same StateFlow instance per conversation")
    }

    @Test
    fun preload_isIdempotent_inFlightDedup() = runTest {
        repo.seed("conv-1", listOf(msg("m1", "conv-1")))
        holder.preload("conv-1")
        holder.preload("conv-1") // second call should not re-launch
        scope.testScheduler.advanceUntilIdle()
        assertEquals(1, repo.getMessagesCallCount("conv-1"))
    }

    @Test
    fun preload_populatesSnapshot() = runTest {
        val initial = listOf(msg("m1", "conv-1"), msg("m2", "conv-1"))
        repo.seed("conv-1", initial)
        holder.preload("conv-1")
        scope.testScheduler.advanceUntilIdle()
        assertEquals(initial, holder.snapshot("conv-1"))
    }

    @Test
    fun observe_emitsRepoChangesAfterPreload() = runTest {
        val initial = listOf(msg("m1", "conv-1"))
        repo.seed("conv-1", initial)
        holder.preload("conv-1")
        scope.testScheduler.advanceUntilIdle()

        val state = holder.observe("conv-1")
        assertEquals(initial, state.value)

        val updated = listOf(msg("m1", "conv-1"), msg("m2", "conv-1"))
        repo.update("conv-1", updated)
        scope.testScheduler.advanceUntilIdle()

        assertEquals(updated, state.value)
    }

    @Test
    fun lru_evictsLeastRecentlyTouchedAtCapacity() = runTest {
        repo.seed("conv-1", listOf(msg("a", "conv-1")))
        repo.seed("conv-2", listOf(msg("b", "conv-2")))
        repo.seed("conv-3", listOf(msg("c", "conv-3")))
        repo.seed("conv-4", listOf(msg("d", "conv-4")))

        // Capacity = 3 (configured in setUp). Insertion order = 1,2,3.
        holder.preload("conv-1")
        holder.preload("conv-2")
        holder.preload("conv-3")
        scope.testScheduler.advanceUntilIdle()

        // Touch conv-1 so conv-2 becomes least-recently-touched.
        holder.snapshot("conv-1")

        // Loading conv-4 should evict conv-2 (the LRU now), not conv-1.
        holder.preload("conv-4")
        scope.testScheduler.advanceUntilIdle()

        assertEquals(1, holder.snapshot("conv-1").size, "conv-1 must survive (touched)")
        assertTrue(holder.snapshot("conv-2").isEmpty(), "conv-2 must be evicted (LRU)")
        assertEquals(1, holder.snapshot("conv-3").size, "conv-3 must survive")
        assertEquals(1, holder.snapshot("conv-4").size, "conv-4 must be loaded")
    }

    @Test
    fun lru_neverEvictsTheConversationBeingTouched() = runTest {
        // Fill to capacity (3).
        repo.seed("conv-1", listOf(msg("a", "conv-1")))
        repo.seed("conv-2", listOf(msg("b", "conv-2")))
        repo.seed("conv-3", listOf(msg("c", "conv-3")))
        holder.preload("conv-1")
        holder.preload("conv-2")
        holder.preload("conv-3")
        scope.testScheduler.advanceUntilIdle()

        // Adding a 4th must evict ONE existing entry, never the new one.
        repo.seed("conv-4", listOf(msg("d", "conv-4")))
        holder.preload("conv-4")
        scope.testScheduler.advanceUntilIdle()

        assertEquals(1, holder.snapshot("conv-4").size, "conv-4 (just preloaded) must not be evicted")
    }

    @Test
    fun evict_dropsCachedEntry() = runTest {
        repo.seed("conv-1", listOf(msg("m1", "conv-1")))
        holder.preload("conv-1")
        scope.testScheduler.advanceUntilIdle()
        assertEquals(1, holder.snapshot("conv-1").size)

        holder.evict("conv-1")
        assertTrue(holder.snapshot("conv-1").isEmpty())
    }

    @Test
    fun clear_dropsAllCached() = runTest {
        repo.seed("conv-1", listOf(msg("a", "conv-1")))
        repo.seed("conv-2", listOf(msg("b", "conv-2")))
        holder.preload("conv-1")
        holder.preload("conv-2")
        scope.testScheduler.advanceUntilIdle()

        holder.clear()

        assertTrue(holder.snapshot("conv-1").isEmpty())
        assertTrue(holder.snapshot("conv-2").isEmpty())
    }

    // -------------------------------------------------------------------------
    // Fakes
    // -------------------------------------------------------------------------

    private fun msg(id: String, conv: String) = MessageEntity(
        id = id,
        conversationId = conv,
        ciphertext = byteArrayOf(0x01),
        plaintextCache = null,
        sent = false,
        status = MessageStatus.QUEUED,
        createdAt = 0L,
    )

    private class FakeMessageRepo : MessageRepository {
        private val flows = mutableMapOf<String, MutableStateFlow<List<MessageEntity>>>()
        private val callCounts = mutableMapOf<String, Int>()

        fun seed(conv: String, messages: List<MessageEntity>) {
            flows.getOrPut(conv) { MutableStateFlow(emptyList()) }.value = messages
        }

        fun update(conv: String, messages: List<MessageEntity>) {
            flows[conv]?.value = messages
        }

        fun getMessagesCallCount(conv: String): Int = callCounts[conv] ?: 0

        override suspend fun getMessages(conversationId: String): List<MessageEntity> {
            callCounts[conversationId] = (callCounts[conversationId] ?: 0) + 1
            return flows[conversationId]?.value.orEmpty()
        }

        override fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
            flows.getOrPut(conversationId) { MutableStateFlow(emptyList()) }.asStateFlow()

        override suspend fun getMessageById(id: String): MessageEntity? = null
        override suspend fun insertMessage(entity: MessageEntity) {}
        override suspend fun updateStatus(messageId: String, status: MessageStatus) {}
        override suspend fun updateMessageText(messageId: String, text: String) {}
        override suspend fun deleteMessage(messageId: String) {}
        override suspend fun deleteMessagesForConversation(conversationId: String) {}
        override suspend fun setExpiresAt(messageId: String, expiresAtMs: Long) {}
        override suspend fun getNextExpiry(): Long? = null
        override suspend fun deleteExpiredMessages() {}
        override suspend fun pinMessage(messageId: String, pinned: Boolean, pinnedByPubkey: String?) {}
        override suspend fun getPinnedMessages(conversationId: String): List<MessageEntity> = emptyList()
        override suspend fun saveMessage(id: String) {}
        override suspend fun unsaveMessage(id: String) {}
        override suspend fun getSavedMessages(): List<MessageEntity> = emptyList()
    }
}
