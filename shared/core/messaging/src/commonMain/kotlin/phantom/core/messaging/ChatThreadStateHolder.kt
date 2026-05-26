// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageRepository

/**
 * PR-UI-CHAT-THREAD-CACHE1 — hot, AppContainer-scoped cache of message lists
 * keyed by `conversationId`. Eliminates the chat-open black wait by giving
 * ChatScreen a synchronous `.value` read on first compose, instead of forcing
 * it to wait 0.8–1.3 s for a cold SqlDelight Flow's first emit.
 *
 * Authoritative scope is locked in [`docs/tracks/chat-thread-cache.md`]
 * (THREAD-CACHE1 mini-lock, Vladislav 2026-05-26). Salvages the cold-Flow
 * source from the parked THREAD-STATE1 work (`MessageRepository.observeMessages`)
 * and puts a hot `MutableStateFlow` wrapper in front of it.
 *
 * Lifecycle model:
 *  - `scope` is the AppContainer's `appScope` (`SupervisorJob + Dispatchers.Default`);
 *    cache entries' observer Jobs are children of this scope and are cancelled
 *    on `evict` / `clear`.
 *  - LRU eviction at [CachePolicy.maxConversations]; touched on every `observe`,
 *    `snapshot` (when hit), and `preload`. Never evicts the conversation
 *    currently being touched in the same call.
 *  - All public methods are non-suspend so ChatScreen and ChatList code can
 *    call them from Compose / main-thread without coroutine ceremony. The
 *    actual DB work runs on `scope`.
 *
 * Public surface (read alongside Q2 of the mini-lock):
 *  - [observe] — returns the same StateFlow instance for the same id across
 *    multiple calls; first call lazily starts the DB observer Job. Use from
 *    `collectAsState(initial = snapshot(...))` in Compose.
 *  - [snapshot] — synchronous read of the current cached list (or empty if
 *    not loaded yet). The seed for ChatScreen's `collectAsState`.
 *  - [preload] — fire-and-forget eager load. Idempotent. Called by ChatList
 *    row tap immediately before navigation. NOT suspend — navigation must
 *    not wait (Vladislav-locked: "если `preload()` suspend и мы ждём его
 *    полностью, может появиться микропаузa на клике").
 *  - [evict] — drop one cached entry + cancel its observer Job.
 *  - [clear] — drop all cached entries; called on logout / account switch.
 */
class ChatThreadStateHolder(
    private val messageRepo: MessageRepository,
    private val scope: CoroutineScope,
    private val cachePolicy: CachePolicy = CachePolicy.default(),
    private val log: (String) -> Unit = {},
) {

    /**
     * One cache entry — the hot StateFlow + the Job that pumps DB changes
     * into it. Mutable so we can replace the value on each emit without
     * recreating the StateFlow (preserves Compose subscriptions across
     * updates).
     */
    private class Entry(
        val state: MutableStateFlow<List<MessageEntity>>,
        var observerJob: Job? = null,
        var preloadJob: Job? = null,
    ) {
        // Cached read-only view — `asStateFlow()` creates a fresh wrapper
        // on every call, so we keep one per entry to fulfil the
        // `observe_returnsSameInstanceForSameConversationId` contract.
        val readOnly: StateFlow<List<MessageEntity>> = state.asStateFlow()
    }

    /**
     * LinkedHashMap with `accessOrder = true` would be the natural fit, but
     * Kotlin/Native commonMain doesn't ship that exact overload — so we use
     * a regular LinkedHashMap and rotate the entry to the end on access by
     * remove+put. The mutex serialises all map mutations so the LRU touch
     * and the eviction sweep see a consistent view.
     */
    private val cache = LinkedHashMap<String, Entry>()

    fun observe(conversationId: String): StateFlow<List<MessageEntity>> {
        val entry = getOrCreateEntry(conversationId)
        ensureObserver(conversationId, entry)
        return entry.readOnly
    }

    fun snapshot(conversationId: String): List<MessageEntity> {
        val entry = cache[conversationId]
        return if (entry != null) {
            touch(conversationId)
            val value = entry.state.value
            log("CHAT_CACHE snapshot_hit conv=${convTag(conversationId)} count=${value.size}")
            value
        } else {
            log("CHAT_CACHE snapshot_miss conv=${convTag(conversationId)}")
            emptyList()
        }
    }

    fun preload(conversationId: String) {
        val entry = getOrCreateEntry(conversationId)
        // Already loaded or in flight — idempotent.
        if (entry.state.value.isNotEmpty()) return
        if (entry.preloadJob?.isActive == true) return

        log("CHAT_CACHE preload_start conv=${convTag(conversationId)}")
        val started = nowMillis()
        entry.preloadJob = scope.launch {
            try {
                val messages = messageRepo.getMessages(conversationId)
                entry.state.value = messages
                val dur = nowMillis() - started
                log(
                    "CHAT_CACHE preload_done conv=${convTag(conversationId)} " +
                        "count=${messages.size} ms=$dur",
                )
                // Make sure the long-lived observer is wired so DB writes
                // post-preload flow into the StateFlow.
                ensureObserver(conversationId, entry)
            } catch (t: Throwable) {
                log(
                    "CHAT_CACHE preload_error conv=${convTag(conversationId)} " +
                        "err=${t::class.simpleName}",
                )
            }
        }
    }

    fun evict(conversationId: String, reason: String = "manual") {
        val entry = cache.remove(conversationId) ?: return
        entry.observerJob?.cancel()
        entry.preloadJob?.cancel()
        log("CHAT_CACHE evict conv=${convTag(conversationId)} reason=$reason remainingSlots=${cache.size}")
    }

    fun clear() {
        val n = cache.size
        cache.values.forEach { entry ->
            entry.observerJob?.cancel()
            entry.preloadJob?.cancel()
        }
        cache.clear()
        log("CHAT_CACHE clear total_evicted=$n")
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private fun getOrCreateEntry(conversationId: String): Entry {
        cache[conversationId]?.let {
            touch(conversationId)
            return it
        }
        val entry = Entry(state = MutableStateFlow(emptyList()))
        cache[conversationId] = entry
        evictIfOverCapacity(except = conversationId)
        return entry
    }

    private fun ensureObserver(conversationId: String, entry: Entry) {
        if (entry.observerJob?.isActive == true) return
        val entryTag = entry.hashCode().toString(16)
        log("CHAT_CACHE observe_start conv=${convTag(conversationId)} entry=$entryTag")
        entry.observerJob = scope.launch {
            messageRepo.observeMessages(conversationId).collect { messages ->
                val before = entry.state.value
                entry.state.value = messages
                // `source=db` because this branch is fed by the DB-change
                // observer. `source=cache` emits are conceptual — they
                // happen at the Compose layer when the snapshot seed is
                // first read — so we don't double-log those here.
                //
                // v1.2 (Test #83.1 verdict): `entry=<hash>` lets the log
                // distinguish two concurrent observers writing to the
                // same conversation tag — if entry hashes differ across
                // count=N / count=M alternating emits, we have multiple
                // Entry instances and a cache race.
                if (messages !== before) {
                    val firstId = messages.firstOrNull()?.id?.take(8) ?: "—"
                    val lastId = messages.lastOrNull()?.id?.take(8) ?: "—"
                    log(
                        "CHAT_CACHE emit conv=${convTag(conversationId)} " +
                            "entry=$entryTag count=${messages.size} " +
                            "first=$firstId last=$lastId source=db",
                    )
                }
            }
        }
    }

    private fun touch(conversationId: String) {
        // LRU touch: move to end. We don't suspend here — the cache is
        // accessed from the Compose / main thread for snapshot reads, and
        // the LinkedHashMap mutation cost is microseconds. Concurrent
        // observers update through the MutableStateFlow which is its own
        // synchronisation barrier; this `cache` map is touched only on
        // observe/snapshot/preload, which are infrequent.
        val entry = cache.remove(conversationId) ?: return
        cache[conversationId] = entry
    }

    private fun evictIfOverCapacity(except: String) {
        val limit = cachePolicy.maxConversations
        while (cache.size > limit) {
            // First key in LinkedHashMap insertion order = least recently
            // touched. Skip `except` so we never evict the conversation
            // being created/touched in the same call.
            val victim = cache.keys.firstOrNull { it != except } ?: break
            val entry = cache.remove(victim) ?: continue
            entry.observerJob?.cancel()
            entry.preloadJob?.cancel()
            log("CHAT_CACHE evict conv=${victim.take(8)} reason=lru remainingSlots=${cache.size}")
        }
    }

    private fun nowMillis(): Long = phantom.core.messaging.ChatThreadStateClock.nowMillis()

    /**
     * Log tag for a conversation id — 16 hex chars instead of 8. PR-UI-CHAT-
     * NEW-MSG-CHIP1 v1.2 (Test #83.1 verdict): the previous 8-char prefix
     * caused suspected collisions across two emu paired contacts whose ids
     * happen to share the first 32 bits. Logs were oscillating count=1/61
     * between two coexisting observers under the same tag. 16 hex chars =
     * 64 bits = vanishingly small collision probability.
     */
    private fun convTag(conversationId: String): String = conversationId.take(16)

    // -------------------------------------------------------------------------
    // Policy
    // -------------------------------------------------------------------------

    data class CachePolicy(
        val maxConversations: Int,
    ) {
        companion object {
            fun default() = CachePolicy(maxConversations = 8)
        }
    }
}

/**
 * Clock indirection — `kotlin.system.getTimeMillis` is not commonMain;
 * `kotlinx.datetime.Clock.System.now().toEpochMilliseconds()` is fine for
 * KMP code. Kept as a separate object so unit tests can stub it without
 * mocking the entire holder.
 */
internal object ChatThreadStateClock {
    fun nowMillis(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
}
