# Track: PR-UI-CHAT-THREAD-CACHE1 — hot StateFlow message cache (ChatThreadStateHolder)

**Status:** queued (mini-lock authored before code per `docs/WORKING_RULES.md` rule 3; replaces PR-UI-CHAT-THREAD-STATE1 which was parked per rule 4 after Tests #81 and #81.1 FAIL).
**Branch (not yet opened):** `feat/pr-ui-chat-thread-cache1` (cut fresh from `origin/master` at session start).
**Layer:** KMP shared (new `ChatThreadStateHolder` in `shared/core/storage` or `shared/core/messaging`) + Android UI (ChatScreen wiring + AppContainer + ChatListScreen preload trigger).
**Authored:** 2026-05-26, immediately after PR #228 was parked.
**Replaces:** `docs/tracks/chat-thread-state.md` (PR #228, closed without merge after Tests #81 / #81.1 BOTH FAIL on Tecno real device).
**Architect-chosen variant:** C (hot StateFlow holder, "ChatThreadStateHolder / MessageThreadStore") — explicit pivot from cold-Flow approach.

## Goal

Eliminate the 0.8–1.3 s black wait on chat open by giving ChatScreen a **hot `StateFlow<List<MessageEntity>>` whose `.value` is already populated** when Compose enters the screen — instead of a cold Flow that emits ~1 second after subscription.

**Concrete UX target:** opening a chat with 30+ messages on Tecno (real device, no emulator) must paint the latest message at the visual bottom within ONE compose frame (16 ms typical, 32 ms acceptable). NO `Box(BgDeep)` placeholder. NO `emptyList`-anchored LazyColumn frame.

## Root-cause recap (from PR #228 logs)

```
ChatScreen subscribed     → 0 ms
CHAT_THREAD observe_started → +22 ms   (Compose effects entered)
CHAT_THREAD observe_emit  → +810–1341 ms (SqlDelight asFlow first query result)
```

The gap between `observe_started` and `observe_emit` is the SqlDelight `Query<T>.asFlow().mapToList(Dispatchers.IO)` round-trip for 30+ rows on a real device. Compose has already rendered the empty-initial frame by the time the Flow emits. The fix is NOT to make the Flow faster — it is to ensure ChatScreen never renders against `emptyList` in the first place.

## Architecture — answers to the 6 builder questions

### Q1: Where to store ChatThreadStateHolder?

**Answer:** AppContainer-scoped singleton in `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/ChatThreadStateHolder.kt`. Lives next to `DefaultMessagingService` so it can subscribe to the same `MessageRepository.observeMessages` Flow that PR #228 introduced, and so it can be reset cleanly on logout via `AppContainer.reset()` / `clearChatState()`.

NOT a Compose state, NOT in `ChatScreen`'s composition. The holder MUST survive ChatScreen disposal — that's the whole point.

KMP common code (no Android-specific dependencies). Construction in `AppContainer`:

```kotlin
val chatThreadStateHolder = ChatThreadStateHolder(
    messageRepo = messageRepo,
    scope = appScope,                // AppContainer-owned CoroutineScope
    cachePolicy = ChatThreadStateHolder.CachePolicy.default(),
)
```

### Q2: Public API surface

```kotlin
class ChatThreadStateHolder(
    private val messageRepo: MessageRepository,
    private val scope: CoroutineScope,
    private val cachePolicy: CachePolicy,
) {
    /**
     * Hot StateFlow for a conversation. First subscriber triggers a one-shot
     * `messageRepo.getMessages(conversationId)` to fill `.value` synchronously
     * via `runBlocking` on a background thread, AND starts a long-lived
     * `messageRepo.observeMessages(conversationId)` collector that keeps the
     * StateFlow up to date on every DB change. Subsequent subscribers see the
     * cached `.value` immediately (no DB hit).
     *
     * Returns the same StateFlow instance for the same conversationId across
     * multiple calls — this is what makes `.value` immediately readable from
     * ChatScreen's first compose.
     */
    fun observe(conversationId: String): StateFlow<List<MessageEntity>>

    /**
     * Synchronous read of the current cached snapshot. Returns emptyList if
     * the conversation has never been loaded. NOT a suspend fun — callable
     * from Composable / Main thread without coroutine. ChatScreen uses this
     * as `collectAsStateWithLifecycle(initial = holder.snapshot(conversationId))`.
     */
    fun snapshot(conversationId: String): List<MessageEntity>

    /**
     * Eagerly load a conversation into the cache. Idempotent — safe to call
     * before navigation (from ChatList row tap, notification tap, contact
     * profile, etc.). If already cached, no-op. If not cached, kicks off
     * the same one-shot load that `observe()` would, on the holder's scope.
     */
    fun preload(conversationId: String)

    /**
     * Drop a conversation from the cache (cancels its observer Job, frees
     * its slot). Called by the LRU eviction policy and by logout.
     */
    fun evict(conversationId: String)

    /** Drop ALL cached state. Called on logout / account switch. */
    fun clear()

    data class CachePolicy(
        val maxConversations: Int,                  // default 8
        val evictionStrategy: EvictionStrategy,     // default LRU
    ) {
        companion object {
            fun default() = CachePolicy(
                maxConversations = 8,
                evictionStrategy = EvictionStrategy.LRU,
            )
        }
    }

    enum class EvictionStrategy { LRU }
}
```

### Q3: Who calls `preload(conversationId)`?

Three eager call-sites at minimum (Vladislav-confirm before code):

1. **ChatList row tap** — in `ChatListScreen` row click handler, immediately before `navController.navigate(ChatScreen(conversationId))`. By the time ChatScreen's `LaunchedEffect` runs, the holder's `.value` is already populated.

2. **Incoming message event** — in `DefaultMessagingService.processIncomingMessage` (or wherever the DB insert for an incoming message happens), call `holder.preload(conversationId)` AFTER the DB insert. This ensures that if the user taps the notification or opens the chat from a different entry point, the cache is warm.

3. **Notification tap** — in `MainActivity.onCreate` / `onNewIntent` when the notification deep-link is resolved to a `conversationId`, call `holder.preload(conversationId)` BEFORE the navigation event. Same as ChatList tap.

NOT eager for: every conversation in ChatList (would defeat the cache size limit and waste DB queries for chats the user won't open). Only the conversation the user is about to navigate to or has just received from.

If `preload` was not called for some entry point (e.g. a new entry we forgot to wire), `observe()` still works — it just takes the same 0.8–1.3 s on first observe. Worst-case fallback is no worse than PR #228.

### Q4: Cache policy

**Default `CachePolicy(maxConversations = 8, evictionStrategy = LRU)`.**

Rationale for 8: a typical user has 1–3 active conversations they cycle through ("recent contacts" bucket); 8 covers the common bursty case (group of friends, 2–3 family members, 1–2 work) without holding the full conversation list in memory. Each cached conversation holds N×MessageEntity objects; 30 messages of ~500 bytes each = ~15 KB; 8 conversations = ~120 KB. Order-of-magnitude rough — confirm with a real measurement after the first prototype.

Eviction triggers:
- New conversation observed AND cache is at `maxConversations` → evict least-recently-touched.
- `holder.clear()` on logout / account switch.
- `holder.evict(conversationId)` on `deleteConversation`.

NOT triggered by: memory pressure callbacks (deferred — adds complexity, validate the simple policy first).

### Q5: How to avoid DB / schema changes?

Zero DB / schema changes. The holder is pure in-memory state in front of the existing `MessageRepository` interface. It uses the EXACT same `getMessages` (one-shot) and `observeMessages` (Flow) methods that PR #228 already defined and that already work on the relay-merged production database.

No new SqlDelight queries. No new tables. No new columns. No new migration step. Risk surface is bounded to the new holder class + the wire-up sites.

### Q6: Minimum scope for first PR

**In scope (this PR only):**

1. `ChatThreadStateHolder` class — new file, ~150 LoC including KDoc.
2. `AppContainer` wire-up — instantiate the holder once, expose as `container.chatThreadStateHolder` (read-only val).
3. `ChatScreen` migration — replace the PR #228 `collectAsState(initial = emptyList())` wrap with `collectAsStateWithLifecycle(initial = container.chatThreadStateHolder.snapshot(conversationId))`. Bottom-anchor render + `firstFlowEmitReceived` defer pattern + `initialMessageIds` animation suppression — ALL carry forward unchanged from PR #228's last working state. The diff against `feat/pr-ui-chat-thread-state1` is small.
4. `ChatListScreen` row tap — single-line `container.chatThreadStateHolder.preload(conversationId)` immediately before navigation.
5. `MessageRepository.observeMessages` + `SqlDelightMessageRepository.observeMessages` — copy the implementation from `feat/pr-ui-chat-thread-state1` (already shipped on that closed branch, just not merged). 1 interface method + ~5 LoC implementation.
6. Test fake updates: same as PR #228 v1.1 (storage `InMemoryRepositoryTest.FakeMessageRepository` MutableStateFlow tick; messaging `DefaultMessagingServiceTest.FakeMessageRepository` emptyFlow).
7. New holder-level unit tests in `shared/core/messaging/src/commonTest/.../ChatThreadStateHolderTest.kt`:
   - `observe_returnsSameInstanceForSameConversationId`
   - `snapshot_returnsEmptyForUncachedConversation`
   - `snapshot_returnsCachedValueAfterPreload`
   - `preload_isIdempotent`
   - `observe_emitsOnRepositoryChange`
   - `lru_evictsLeastRecentlyTouchedWhenAtCapacity`
   - `clear_dropsAllCached`
8. New logs (Test #82 verification): `CHAT_CACHE preload_start conv=<8>`, `CHAT_CACHE preload_complete conv=<8> count=<n> dur_ms=<n>`, `CHAT_CACHE observe_attach conv=<8>`, `CHAT_CACHE evict conv=<8> reason=<lru|logout|delete>`.

**Out of scope (defer to separate PRs):**

- ❌ Incoming message preload from `DefaultMessagingService` — wire only ChatList tap path in this PR. Add notification + incoming preload in `PR-UI-CHAT-THREAD-CACHE-PRELOAD-EXPANDED` (small follow-up).
- ❌ Notification tap preload — same reason.
- ❌ `ChatListScreen` Flow migration (conversation list itself becoming Flow-backed). Separate track.
- ❌ Floating "↓ N new messages" chip with tap-to-jump (`PR-UI-CHAT-NEW-MSG-CHIP1`).
- ❌ Preserve-scroll-position when user scrolls up to read history (deferred indefinitely — covered by "no auto-scroll" decision 2026-05-25).
- ❌ Scroll performance / `Modifier.animateItemPlacement()` / heavy bubble recomposition (`PR-UI-CHAT-SCROLL-PERF1`).
- ❌ Memory-pressure-driven eviction.
- ❌ Multi-account / account-switch cache partitioning (current model: 1 account in-process at a time, `clear()` on logout sufficient).
- ❌ Group chat / `GroupChatScreen.kt` migration (not in production).
- ❌ DB schema / SQL / new queries.
- ❌ Transport / crypto / voice / recorder / relay / OkHttp.

## Anti-pattern signatures — verify NOT present in the diff (from `feedback_scroll_to_bottom_not_chat_ux.md` + `feedback_chatscreen_pull_style_root_cause.md`)

Repeat from PR #226 / #228 mini-locks plus one new entry specific to this track:

- ❌ `LaunchedEffect(conversationId) { … scrollToItem(...) }` for initial-open positioning.
- ❌ `snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }` as a render gate.
- ❌ `withFrameNanos { }` followed by `scrollToItem` to "catch up" to layout.
- ❌ `initialMessagesLoaded` gate that hides LazyColumn behind a `Box` placeholder.
- ❌ Large spacer (>16dp) at the visual bottom to "make room for composer".
- ❌ `var messages by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }` — Compose-local state replacing the holder's hot StateFlow. Defeats the entire point.
- ❌ `collectAsState(initial = emptyList())` without a `holder.snapshot()` seed. **NEW** — this is the THREAD-STATE1 anti-pattern.
- ❌ `holder.observe(...).first()` inside ChatScreen to "wait" for cache fill before render. Renders synchronously from `.value` or not at all.

## Test acceptance (Test #82 — Vladislav to refine)

`assembleDebug` green. APK SHA256 + MD5 in PR body. MANDATORY: Vladislav verifies MD5 on the installed device before running tests.

**Critical-path scenarios (must PASS):**

1. **Cold open (first chat in session)** — ChatList → tap row → ChatScreen renders. Expected: `CHAT_CACHE preload_start` immediately on tap, `CHAT_CACHE preload_complete count=N dur_ms=…` before ChatScreen's `LaunchedEffect`, ChatScreen renders with N messages visible at the visual bottom in the same frame. Black wait <50 ms (one Compose frame budget).
2. **Warm open (returning to a recent chat)** — back out of ChatScreen, tap same row in ChatList → expected: holder.snapshot returns the cached value, NO `preload_start` log (already cached), ChatScreen renders instantly.
3. **Incoming while chat is open** — emu sends a message → expected: holder's observer collects the DB change, StateFlow emits, ChatScreen recomposes with the new row at visual bottom (if user is at bottom).
4. **Send text** — type & send → expected: DB insert → holder collector emits → ChatScreen updates → `scrollToItem(0)` lands the user at the new message.
5. **Voice send** — short voice → DB insert (and chunk uploads) → cache updates → bubble appears.
6. **Open a brand-new conversation (zero history)** — expected: `preload_complete count=0`, ChatScreen renders the empty thread (only the E2EE header + the composer); profile-card-send fires via the deferred LaunchedEffect.
7. **Open 9th distinct conversation in one session** — expected: cache at capacity, LRU evicts the least-recently-touched, `CHAT_CACHE evict reason=lru` log. Opening that evicted conversation later behaves like a cold open (`preload_start` log fires again).
8. **Logout / account switch** — `clear()` empties the cache; opening any chat afterwards is a cold open.

**PASS only if NO:**
- Black wait > 50 ms on chat open after preload completes.
- `var messages by remember` or `collectAsState(initial = emptyList())` in the diff.
- Cache-miss path that hangs ChatScreen on `holder.observe().first()`.
- Eviction during the active chat (cache MUST never evict the currently-viewing conversation).
- Memory growth unbounded across many chat opens (eviction must actually drop refs).

## Parking conditions

- If after THREAD-CACHE1 lands the chat STILL shows >50 ms black wait → diagnose whether the bottleneck is `holder.snapshot()` returning stale-empty (preload not wired for the entry point used by the test), Compose recomposition cost, or LazyColumn `reverseLayout` re-measure — DO NOT immediately escalate to a fourth architectural variant. The cache approach is fundamentally correct; sub-bugs are fixable in-track.
- If the holder Flow collector leaks (Job not cancelled on `evict`) and crashes / hangs → fix the collector lifecycle within the track, do not park.
- If the LRU policy turns out to evict the wrong thing (e.g. the open conversation) → fix the touch-tracking, do not park.
- Park ONLY if a fundamentally new architectural problem surfaces (e.g. SQLCipher write-lock contention with the holder's eager loader causing send-path delays — then we'd need a separate write-queue mini-lock).

## Logs (new)

```
CHAT_CACHE preload_start conv=<8>
CHAT_CACHE preload_complete conv=<8> count=<n> dur_ms=<n>
CHAT_CACHE observe_attach conv=<8>  ← StateFlow first observer attached
CHAT_CACHE observe_emit conv=<8> count=<n>  ← downstream DB-change emission
CHAT_CACHE evict conv=<8> reason=<lru|logout|delete> remainingSlots=<n>
CHAT_CACHE clear total_evicted=<n>
```

Existing PR #228 logs that carry forward (kept in ChatScreen):
- `ChatScreen subscribed`
- `CHAT_THREAD observe_started` — first observer attached at composition (semantics shift: now means "holder.observe() returned" rather than "Flow subscribed")
- `CHAT_THREAD observe_emit` — DB emission seen at ChatScreen level
- `CHAT_LIST bottom_anchor_enabled`
- `CHAT_THREAD deferred_side_effects_start`

## Migration safety

`MessageRepository.observeMessages` carries forward as-is from PR #228 (interface + SqlDelight impl). No second migration of the interface — the previous PR's storage-layer work is salvaged into this track via cherry-pick / re-apply.

`ChatScreen` migrates IN-PLACE from the PR #228 `collectAsState(initial = emptyList())` wrap to `collectAsStateWithLifecycle(initial = holder.snapshot(conversationId))`. The bottom-anchor render + side-effect defer pattern + animation-suppression snapshot all stay.

No `MessageRepository` shape change. No SqlDelight schema change. No new DB column / index / table. No new transport / crypto / voice surface touched.

## Last hand-off

(empty — track queued, awaiting Vladislav greenlight before code begins)
