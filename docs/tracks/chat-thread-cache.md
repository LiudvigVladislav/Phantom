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

**Vladislav-locked 2026-05-26: CACHE1 wires ONLY one preload call-site — `ChatList row tap`. Other sites deferred to follow-up tracks.**

Rationale (Vladislav 2026-05-26): "если сразу добавим ChatList + notification + incoming, снова появится много входных путей и сложно понять, что реально помогло." Keep CACHE1 minimal and falsifiable — measure black-wait elimination on a single, instrumented entry point.

**CACHE1 (this PR) — single preload site:**

1. **ChatList row tap** — in `ChatListScreen` row click handler, **call `holder.preload(conversationId)` immediately before `navController.navigate(ChatScreen(conversationId))`. `preload` is NON-SUSPEND and fire-and-forget** — the holder's own `scope` owns the load coroutine. Navigation MUST NOT wait for the load to finish (Vladislav-locked 2026-05-26: "если `preload()` suspend и мы ждём его полностью, может появиться микропаузa на клике" — anti-pattern). Best case: preload completes before ChatScreen's first `remember { snapshot(...) }` runs → `snapshot_hit`. Worst case: preload still in flight → `snapshot_miss`, ChatScreen renders empty for the same ~1 s the THREAD-STATE1 cold-Flow path would, then the holder's StateFlow emits when the load completes. The worst case is no worse than today's behaviour; the best case (and the happy path on warm starts after the first chat open) is the UX target.

**CACHE2 (follow-up track, NOT this PR) — expanded preload sites:**

2. **Incoming message event** — in `DefaultMessagingService.processIncomingMessage`, call `holder.preload(conversationId)` AFTER the DB insert. Keeps cache warm if user navigates from a non-ChatList entry.
3. **Notification tap** — in `MainActivity.onCreate` / `onNewIntent` when the notification deep-link is resolved to a `conversationId`, call `holder.preload(conversationId)` BEFORE the navigation event.

If a non-ChatList entry-point is used (notification tap in CACHE1's lifetime), `observe()` still works — first observe takes 0.8–1.3 s same as today. Acceptable known non-blocker until CACHE2 lands.

NOT eager for: every conversation in ChatList (would defeat the cache size limit and waste DB queries for chats the user won't open). Only the conversation the user is about to navigate to.

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
3. `ChatScreen` migration — replace the PR #228 `collectAsState(initial = emptyList())` wrap with the Vladislav-locked pattern (see "ChatScreen wiring pattern" below). Bottom-anchor render + `firstFlowEmitReceived` defer pattern + `initialMessageIds` animation suppression — ALL carry forward unchanged from PR #228's last working state.
4. `ChatListScreen` row tap — single-line `container.chatThreadStateHolder.preload(conversationId)` immediately before navigation. **ONLY this one preload site for CACHE1** (see Q3 above).
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
8. New logs (Test #82 verification — Vladislav-locked 2026-05-26):
   ```
   CHAT_CACHE preload_start    conv=<8>
   CHAT_CACHE preload_done     conv=<8> count=<n> ms=<n>
   CHAT_CACHE snapshot_hit     conv=<8> count=<n>
   CHAT_CACHE snapshot_miss    conv=<8>
   CHAT_CACHE observe_start    conv=<8>
   CHAT_CACHE emit             conv=<8> count=<n> source=<cache|db>
   CHAT_CACHE evict            conv=<8> reason=<lru|logout|delete>
   CHAT_CACHE clear            total_evicted=<n>
   ```
   `source=cache` means the emit was the immediate StateFlow `.value` read; `source=db` means it came from the underlying `messageRepo.observeMessages` Flow collector. The split makes it trivial to verify in Test #82 that ChatScreen reads cache first and DB updates layer on top.

## ChatScreen wiring pattern (Vladislav-locked 2026-05-26)

```kotlin
// In ChatScreen composable body:

// Snapshot seed — synchronous read from the holder's hot cache.
// `remember(conversationId)` ensures this runs ONCE per chat open,
// not on every recomposition.
val initialSnapshot = remember(conversationId) {
    container.chatThreadStateHolder.snapshot(conversationId)
}

// Hot StateFlow + initial seed — Compose paints `initialSnapshot`
// on the very first frame, then layers DB updates on top as they
// arrive via the holder's observer.
val messages: List<MessageEntity> by container.chatThreadStateHolder
    .observe(conversationId)
    .collectAsState(initial = initialSnapshot)
```

If `snapshot()` returns the cached list — happy path after a ChatList tap — ChatScreen paints with messages on frame 1. Black wait gone.

If `snapshot()` returns `emptyList()` — cache miss (notification tap entry-path before CACHE2 lands, or app cold-start to a notification deep-link) — ChatScreen still works, just with the same 0.8–1.3 s first-emit gap as PR #228. Acceptable known non-blocker for CACHE1.

## Cherry-pick guide — what to take from `feat/pr-ui-chat-thread-state1`

**Take (cherry-pick or re-apply by hand):**

- `MessageRepository.observeMessages` interface method.
- `SqlDelightMessageRepository.observeMessages` implementation (`asFlow().mapToList(Dispatchers.IO)`).
- `LazyColumn(reverseLayout = true)` + `__bottom_anchor__` 8dp Spacer + `displayItems = chatItems.asReversed()` rendering pattern.
- `initialMessageIds` snapshot for per-row animation suppression.
- `firstFlowEmitReceived` boolean + deferred-side-effects `LaunchedEffect` pattern (markConversationRead + profile-card-send post-first-emit).
- Side-effect inventory + Type A / B / C classification comments at each former `reloadMessages()` call-site.
- Test-fake `observeMessages` overrides in `InMemoryRepositoryTest` + `DefaultMessagingServiceTest`.

**Do NOT carry forward:**

- ❌ `collectAsState(initial = emptyList())` as ChatScreen's top-level wiring — replaced by the snapshot-seed pattern above.
- ❌ `messagesFlow.first()` separate collector trick for setting `firstFlowEmitReceived` — under the holder model, `firstFlowEmitReceived` becomes `holder.observe(conversationId).first { ... }` OR is replaced by a derived state from cache hit/miss. Simplify on re-apply.
- ❌ Any `Box(BgDeep)` placeholder gate / `initialMessagesLoaded` boolean / large bottom spacer (>16dp). Anti-pattern signatures stay banned.
- ❌ `LaunchedEffect + scrollToItem(0)` for initial open positioning. Bottom anchor handles it.

## Implementation order (Vladislav-locked 2026-05-26)

1. Merge docs PR #229.
2. Fresh branch `feat/pr-ui-chat-thread-cache1` cut from `master` AFTER #229 merges.
3. Implement `ChatThreadStateHolder` + LRU policy + holder-level unit tests.
4. Wire `AppContainer` singleton (`container.chatThreadStateHolder` val).
5. Wire ChatList row tap `preload(conversationId)` — single line, immediately before navigation.
6. Migrate ChatScreen to snapshot-seed + hot StateFlow pattern (replace #228 `collectAsState(initial = emptyList())` wiring). Carry forward bottom-anchor render + side-effect defer + animation suppression.
7. Re-apply `MessageRepository.observeMessages` + `SqlDelightMessageRepository` impl + test fakes from #228 branch (cherry-pick or re-apply by hand per cherry-pick guide above).
8. Wire all 8 new logs.
9. `./gradlew :apps:android:assembleDebug` + `:shared:core:storage:jvmTest` + `:shared:core:messaging:jvmTest` green.
10. Vladislav Test #82 on Tecno real device.

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
- ❌ `collectAsState(initial = emptyList())` without a `holder.snapshot()` seed. **NEW (Vladislav-locked 2026-05-26):** "Это прямо новый anti-pattern" — this is the THREAD-STATE1 root cause encoded as a banned pattern.
- ❌ `holder.observe(...).first()` inside ChatScreen to "wait" for cache fill before render. Renders synchronously from `.value` or not at all.

## Test acceptance (Test #82 — Vladislav-locked 2026-05-26)

`assembleDebug` green. APK SHA256 + MD5 in PR body. MANDATORY: Vladislav verifies MD5 on the installed device before running tests.

**Critical (must PASS — these are the UX goals):**

1. **Open chat from ChatList with existing history** — messages visible **immediately** (within one Compose frame, ~16 ms; ≤50 ms acceptable). NO black wait. Expected log order:
   ```
   CHAT_CACHE preload_start conv=<8>          ← fired by ChatList row tap
   CHAT_CACHE preload_done conv=<8> count=N ms=<n>   ← BEFORE navigate
   (navigation)
   CHAT_CACHE snapshot_hit conv=<8> count=N   ← ChatScreen reads cache
   ChatScreen subscribed
   CHAT_CACHE observe_start conv=<8>
   CHAT_CACHE emit conv=<8> count=N source=cache   ← initial snapshot
   ```
2. **Receive 3+ messages while outside chat, then open from ChatList** — latest messages visible immediately on chat open. Preload picks up the freshly-written messages from DB.
3. **Open same chat a second time** — instant render from cache snapshot. NO `preload_start` log on the second open (already cached). `snapshot_hit count=N` fires immediately.
4. **Log invariants** — across all chat opens via ChatList tap:
   - `preload_done` precedes ChatScreen `subscribed`.
   - `snapshot_hit` precedes `observe_start` (cache read before observer attach).
   - `emit source=cache` appears as the very first emit; subsequent emits use `source=db`.

**Regression (must continue to PASS):**

5. **Send text** — composer → tap send → bubble appears at visual bottom. Cache emits with new message. `scrollToItem(0)` lands user on the new message.
6. **Send voice** — short hold-to-record → bubble appears.
7. **Receive voice** — emu sends voice → cache updates → bubble appears.
8. **Date separators / E2EE row** — multi-day chat shows separator above each day; E2EE row at the very top of the history view (above the oldest day); pinned banner above the scroll area.

**Edge cases (must PASS but not the headline):**

9. **Open a brand-new conversation (zero history)** — `preload_done count=0`, `snapshot_hit count=0`, ChatScreen renders the empty thread (only E2EE header + composer); profile-card-send fires via the deferred LaunchedEffect once `observe_start` lands.
10. **Open 9th distinct conversation in one session** — cache at capacity, LRU evicts least-recently-touched, `CHAT_CACHE evict reason=lru` log fires. Opening the evicted conversation later behaves like a cold open (`preload_start` fires again).
11. **Logout / account switch** — `clear()` empties the cache; opening any chat afterwards is a cold open.

**Known non-blockers (NOT required to PASS for CACHE1):**

- Notification tap entry-path may still show 0.8–1.3 s black wait — preload not wired for that path until CACHE2.
- Incoming-while-scrolled-up has no "↓ new messages" chip — separate `PR-UI-CHAT-NEW-MSG-CHIP1`.
- Manual scroll jerkiness through history — separate `PR-UI-CHAT-SCROLL-PERF1`.

**PASS only if NO:**
- Black wait > 50 ms on chat open from ChatList tap (the primary path).
- `var messages by remember` or `collectAsState(initial = emptyList())` in the diff.
- Cache-miss path that hangs ChatScreen on `holder.observe().first()`.
- Eviction during the active chat (cache MUST never evict the currently-viewing conversation).
- Memory growth unbounded across many chat opens (eviction must actually drop refs).
- Any anti-pattern signature from the list above present in the diff.

## Parking conditions

**Stay in track (in-PR fixes, NOT a fourth architectural variant):**

- If `holder.snapshot()` returns empty for a path that should have been preloaded → debug the preload wiring on that entry-point. Stays in CACHE1 if the path is ChatList tap; deferred to CACHE2 if it's notification tap or another entry.
- If the holder Flow collector leaks (Job not cancelled on `evict`) and crashes / hangs → fix the collector lifecycle within the track.
- If the LRU policy turns out to evict the wrong thing (e.g. the currently-active conversation) → fix the touch-tracking.

**Escalate to a NEW track (Vladislav-locked 2026-05-26):**

- **If preload + snapshot-seed are verified WORKING (`snapshot_hit count=N` lands BEFORE first Compose frame) but Test #82 STILL shows >50 ms black wait** → the bottleneck is no longer data availability, it's **render cost**. Next track: `PR-UI-CHAT-RENDER-PERF1` (LazyColumn / MessageBubble / AnimatedVisibility / Canvas-gradient measurement and optimisation). Do NOT spin a 5th architectural variant on the data lifecycle.
- If SQLCipher write-lock contention with the holder's eager loader causes send-path delays → separate write-queue mini-lock.
- If a fundamentally new architectural problem surfaces (e.g. Compose-runtime / SQLCipher) → escalate.

**Park ONLY** in the last two bullets — and only with explicit Vladislav confirmation. Three architectural parks on the chat-list lifecycle is the locked ceiling.

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
