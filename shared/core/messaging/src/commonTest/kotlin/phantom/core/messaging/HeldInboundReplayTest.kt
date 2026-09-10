package phantom.core.messaging

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import phantom.core.storage.DecryptFailedEnvelopeRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HeldInboundReplayTest {
    private class Held : DecryptFailedEnvelopeRepository {
        private val entry = DecryptFailedEnvelopeRepository.Entry("e", "c", "s", "commit", 0L, false, "{}", 0L, null)
        override suspend fun insert(envelopeId: String, conversationId: String, senderPubKeyHex: String,
            errorType: String, receivedAtMs: Long, x3dhInitPresent: Boolean, wireFrameJson: String) = Unit
        override suspend fun listByConversation(conversationId: String) = listOf(entry)
        override suspend fun deleteByEnvelopeId(envelopeId: String) = Unit
        override suspend fun recordReplayAttempt(envelopeId: String, nowMs: Long) = Unit
        override suspend fun deleteOlderThan(olderThanMs: Long) = Unit
        override suspend fun count() = 1L
        override suspend fun countByConversation() = mapOf("c" to 1L)
        override suspend fun deleteAll() = Unit
    }

    @Test fun commit_requests_are_immediate_coalesced_and_never_recursive() = runTest {
        val parked = CompletableDeferred<Unit>()
        var calls = 0
        var active = 0
        var maximumActive = 0
        val replay = HeldInboundReplay(Held(), {
            active++
            maximumActive = maxOf(active, maximumActive)
            calls++
            if (calls == 1) parked.await()
            active--
        }, { throw it })
        replay.start(backgroundScope)
        runCurrent()
        assertEquals(1, calls, "startup sweep")
        repeat(20) { replay.request() }
        runCurrent()
        assertEquals(1, calls, "the current pass must not overlap another")
        parked.complete(Unit)
        runCurrent()
        assertEquals(2, calls, "twenty commits request only one next pass")
        assertEquals(1, maximumActive)
        assertEquals(0L, testScheduler.currentTime, "no timer was needed")
    }

    @Test fun storage_recovery_is_paced_without_a_new_commit() = runTest {
        var calls = 0
        val failures = mutableListOf<Throwable>()
        val replay = HeldInboundReplay(Held(), { calls++; error("storage unavailable") }, { failures += it })
        replay.start(backgroundScope)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(HeldInboundReplay.RETRY_INTERVAL_MS - 1)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, calls)
        assertTrue(failures.size == 2)
    }

    @Test fun housekeeping_runs_on_startup_and_on_the_owned_periodic_pass() = runTest {
        val events = mutableListOf<String>()
        val replay = HeldInboundReplay(Held(), { events += "receive" }, { throw it },
            beforePass = { events += "cleanup" })
        replay.start(backgroundScope)
        runCurrent()
        assertEquals(listOf("cleanup", "receive"), events)
        advanceTimeBy(HeldInboundReplay.RETRY_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf("cleanup", "receive", "cleanup", "receive"), events)
    }
}
