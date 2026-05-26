# Track: PR-UI-CHAT-THREAD-STATE1 — Flow-backed thread state + bottom-anchored render

**Status:** queued (mini-lock authored before code per `docs/WORKING_RULES.md` rule 3; this track replaces PR-UI-CHAT-BOTTOM-ANCHOR1 which was parked per rule 4 after THREE failed implementation attempts).
**Branch (not yet opened):** `feat/pr-ui-chat-thread-state1` (cut fresh from `origin/master` at session start).
**Layer:** Android UI + KMP shared (MessageRepository interface + SqlDelight impl + ChatScreen).
**Authored:** 2026-05-25, immediately after PR #226 was parked.
**Replaces:** `docs/tracks/chat-bottom-anchor.md` (PR #226, closed without merge after Test #80 / #80.1 / #80.2).
**Architect-chosen variant:** A+ (observeMessages Flow source + bottom-anchored render combined in one PR per Vladislav 2026-05-25 — "data-source fix alone doesn't guarantee bottom-anchor placement; both are needed together").

## Goal

Replace `ChatScreen`'s pull-style local `messages` state with a Flow-backed reactive source from the database, combined with bottom-anchored LazyColumn rendering, so that:

1. Opening a chat with existing history shows the latest message **immediately** — no black wait, no mid-history flash, no delayed scroll-to-bottom jump.
2. Incoming messages (whether received while chat is open or while chat is closed) flow into the visible list automatically, without manual `reloadMessages()` triggers.
3. Send / delete / edit / pin / save operations refresh the list automatically via DB change observation, without manual `reloadMessages()` after each operation.
4. Initial history (messages already present at chat open) does NOT animate in as if newly arrived.

This is the architecturally correct foundation for the chat list. PR #226 proved that any LazyColumn-layer-only fix (whether `scrollToItem`-after-load or `reverseLayout`-without-Flow) cannot work — Compose anchors to the empty pre-load state and the visible result is always wrong.

## MANDATORY pre-implementation checklist (per Vladislav 2026-05-25 + lessons from PR #217 + #226)

Before writing any code, the builder MUST commit the following in the PR description and as a code comment block at the top of the relevant file:

1. **Item-order mapping** (carried forward from PR #226 mini-lock — still needed even with Flow source):
   - Which Kotlin collection is **oldest-first** vs **newest-first** at each layer.
   - Whether `LazyColumn(reverseLayout = …)` is `true` or `false`.
   - Which item appears at the **visual bottom**.
   - Where E2EE row + pinned banner + day separators are placed and what their final visual position is.
2. **Side-effect inventory.** For each of the existing 12 `reloadMessages()` call-sites in `ChatScreen.kt`, the builder must classify:
   - **Type A (delete-able):** the only purpose of this call is to refresh `messages` state. With Flow source, this disappears automatically.
   - **Type B (separate side-effect):** this call ran some other logic alongside the refresh — `markConversationRead`, `sendProfileCard`, scroll-anchor-after-own-send, mark-as-read for tap-on-message. These side-effects MUST be preserved as standalone calls, not deleted along with `reloadMessages()`.
   - **Type C (legitimate one-shot pull):** edge cases where Flow doesn't fit — e.g. reading a one-time DB state before navigation, expiry sweep, or anything not part of the live "what's in the chat right now" view. Keep `getMessages()` for these.
3. **Anti-pattern signatures** (from `feedback_scroll_to_bottom_not_chat_ux.md`) — verify NOT present in the diff:
   - ❌ `LaunchedEffect(conversationId) { … scrollToItem(...) }` for initial-open positioning.
   - ❌ `snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }` as a render gate.
   - ❌ `withFrameNanos { }` followed by `scrollToItem` to "catch up" to layout.
   - ❌ `initialMessagesLoaded` gate that hides LazyColumn behind a `Box` placeholder.
   - ❌ Large spacer (>16dp) at the visual bottom to "make room for composer".

## Scope (do this, only this)

### A. `MessageRepository.observeMessages(conversationId)` — KMP common interface

Add to `shared/core/storage/src/commonMain/.../MessageRepository.kt`:

```kotlin
fun observeMessages(conversationId: String): Flow<List<MessageEntity>>
```

Note: NOT `suspend fun`. Returns a cold Flow that subscribes on collect and emits on each DB write to the `messages` table for this conversation. The existing `suspend fun getMessages(conversationId)` stays — it is still needed for one-shot pulls (Type C above) and for any test path that doesn't want a Flow subscription.

### B. `SqlDelightMessageRepository.observeMessages` — implementation

```kotlin
override fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
    db.messageQueries.getMessages(conversationId)
        .asFlow()
        .mapToList(Dispatchers.IO)
        .map { rows -> rows.map { it.toEntity() } }
```

Dependencies: `sqldelight-coroutines-extensions` is already in `shared/core/storage/build.gradle.kts` (verified by audit, 2026-05-25). No new dependency needed. Imports:

```kotlin
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
```

### C. `ChatScreen.kt` reactive collection

Replace:

```kotlin
var messages by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }

suspend fun reloadMessages() {
    messages = container.messageRepo.getMessages(conversationId)
    Log.i("PhantomUI", "ChatScreen reloadMessages: conv=… loaded=${messages.size}")
}
```

with:

```kotlin
val messages: List<MessageEntity> by container.messageRepo
    .observeMessages(conversationId)
    .collectAsState(initial = emptyList())
```

This delivers messages as a reactive Compose `State<List<MessageEntity>>` that emits on every DB change for this conversation.

### D. Side-effect preservation (Type B sites only)

Walk through the 12 existing `reloadMessages()` call-sites. For Type B sites, extract the side-effect into its own standalone call:

- `LaunchedEffect(conversationId)` entry (line ~417) — `reloadMessages()` is Type A (pure list refresh now handled by Flow). The `markConversationRead` + `sendProfileCard` side-effects below stay, but no longer depend on the reload.
- Voice send (~line 399) — Type A; the message was DB-inserted by `sendAudio`, Flow will pick up. No separate side-effect.
- Incoming-active (~line 516) — Type A; remove. `markConversationRead` already runs separately in the block.
- Text send (~line 1013, 1031) — Type A; remove.
- Pin/unpin/save/saved (~line 1234, 1245, 1291) — Type A; remove.
- Edit, delete, reaction handlers — walk through them; almost all Type A (DB write fires DB-change emission).

### E. Bottom-anchored render

After `messages` becomes Flow-backed, the LazyColumn's anchor problem from PR #226 disappears: first emission is the loaded list, not `emptyList`. Combine with the same bottom-anchor approach PR #226 v1.2 used:

- `displayItems = chatItems.asReversed()` (newest-first).
- `LazyColumn(state = listState, reverseLayout = true, …)`.
- `item("__bottom_anchor__") { Spacer(Modifier.height(8.dp)) }` as first source item (8dp, not 72dp, per PR #226 v1.2 lesson).
- `lazyItems(displayItems, key = …)` in the middle.
- `item("__e2ee__") { E2EENoteRow(…) }` LAST in source order (visual top via reverseLayout).
- Pinned banner OUTSIDE LazyColumn (carried forward from PR #226 — Option 2).
- For own text/voice send: `listState.scrollToItem(0)` (== visual bottom in reverseLayout) — ensures the user sees their just-sent message even if they had scrolled up.
- For incoming-active: NO `scrollToItem` call. Compose item-key anchor keeps user position; new message naturally at visual bottom when user is at bottom.

### F. Initial-history animation suppression

`initialMessageIds` snapshot pattern from PR #226 v1.2 stays, with the wrinkle that Flow emission timing is different from `suspend fun reloadMessages()`:

```kotlin
var initialMessageIds by remember(conversationId) { mutableStateOf<Set<String>?>(null) }

LaunchedEffect(conversationId, messages) {
    if (initialMessageIds == null && messages.isNotEmpty()) {
        initialMessageIds = messages.map { it.id }.toSet()
    }
}

// per-row, inside lazyItems:
val isNew = initialMessageIds?.let { msg.id !in it } ?: false
```

The `LaunchedEffect` keyed on `conversationId + messages` fires once when `messages` first becomes non-empty for this conversation. Pre-snapshot, `isNew = false` (no animation). Post-snapshot, only message-ids NOT in the snapshot animate — i.e. messages that arrived AFTER chat open.

### G. Logs

Three new logs once per chat open (gated by `initialMessageIds != null`):

```
CHAT_THREAD observe_started conv=<8>
CHAT_THREAD observe_emit conv=<8> count=<n> firstId=<8> lastId=<8>
CHAT_LIST bottom_anchor_enabled conv=<8> total=<n+2> sourceFirst=__bottom_anchor__ sourceSecond=<id> sourceLast=__e2ee__
CHAT_SCROLL source=initial_open_skipped reason=bottom_anchor conv=<8>
```

`observe_started` fires the first time the Flow is subscribed for this conversation. `observe_emit` fires every time the Flow emits with a non-empty list. The other two fire once per chat open after `initialMessageIds` settles.

### H. Migration safety — `getMessages` stays, `reloadMessages` mostly disappears

`MessageRepository.getMessages` remains. Other call-sites in the codebase (ChatListScreen preview generation, SavedMessagesScreen, ArchiveScreen, GroupChatScreen, etc.) continue to use it. They will be migrated in follow-up PRs at their own pace.

`ChatScreen.reloadMessages` — the local helper at line 299 — can be deleted entirely after the 12 call-sites are walked (it has no other consumers). If the builder finds even one Type C call-site that needs it, keep the helper and document why.

## Out of scope (do NOT touch)

- ❌ `ChatListScreen` Flow migration. The same `observeMessages`-style pattern could apply to `conversationRepo.observeActiveConversations()`, but that's a follow-up PR. ChatListScreen pull-style is currently not broken — only ChatScreen is.
- ❌ Floating "↓ N new messages" chip with tap-to-jump (deferred indefinitely).
- ❌ Preserve-scroll-position when user scrolls up to read history (deferred indefinitely).
- ❌ `NotificationManager.cancel` on chat open (PR-NOTIF-POLICY1's responsibility).
- ❌ `MessageBubble` / `AudioBubble` / day-separator rendering (only their data source changes, not visual code).
- ❌ `_incomingMessages` flow shape (it stays as-is; ChatScreen no longer needs to consume it for refresh, but ChatListScreen does).
- ❌ `GroupChatScreen.kt` (group path: not configured in production; same pull-style pattern lives there, can migrate later).
- ❌ Database schema changes / new tables / new queries beyond the trivial `asFlow()` wrapper.
- ❌ AppContainer DI changes / new lifecycle / new cache holder (Variant C from the audit was rejected).
- ❌ `initialMessages` navigation parameter / pre-load in ChatListScreen click handler (Variant B from the audit was rejected).
- ❌ Scroll performance / `Modifier.animateItemPlacement()` / heavy bubble recomposition fixes — separate track `PR-UI-CHAT-SCROLL-PERF`.
- ❌ Transport / crypto / voice / recorder / relay / OkHttp — unrelated.

## Test acceptance (Test #81 — Vladislav-locked 8 scenarios)

`assembleDebug` green. APK SHA256 + MD5 in PR body. **MANDATORY: Vladislav verifies MD5 on the installed device before running tests** (after PR #217 install-mismatch lesson).

1. **Open chat with existing history → messages visible without black wait.** No `Box(BgDeep)` placeholder ever shows; LazyColumn renders the loaded list from the first emission of the Flow.
2. **Receive 3+ messages outside chat → open chat → latest message visible.** No delayed jump, no mid-history flash.
3. **No delayed `scrollToItem` jump after opening.** Logs must NOT contain `CHAT_SCROLL source=initial_open …` (without the `_skipped` suffix). The bottom-anchor approach replaces the scroll command entirely.
4. **No mid-history flash.** The user never sees the conversation rendered at any position other than the bottom on initial open.
5. **Initial history does NOT animate as new.** The 23 / 43 / N existing messages do not pile-up slide-in via the `AnimatedVisibility` wrapper when the chat opens. Only messages that arrive AFTER the initial snapshot animate in.
6. **Incoming while chat open updates automatically from DB.** No manual `reloadMessages()` call required — the Flow emission propagates through `collectAsState` and Compose recomposes the LazyColumn with the new row at index 0 (visual bottom).
7. **Own text/voice appears without manual reload.** Sending writes to DB → Flow emits → LazyColumn updates → `scrollToItem(0)` puts the user at the bottom showing their just-sent message.
8. **Date separators / E2EE row remain correct.** Multi-day chat shows separator visually above each day's messages; E2EE row at the very top of the history view (above the oldest day); pinned banner above the scroll area (unchanged from PR #226 placement).

**PASS only if NO:**

- black wait
- mid-history flash
- delayed scroll correction / 1–2 sec jump
- reversed-chronological-order timeline (still a possible failure mode of reverseLayout if asReversed mapping is wrong)
- pile-up animation of initial history
- `CHAT_SCROLL source=initial_open …` log line without `_skipped`

**Bonus:** verify the new logs are present once per chat open:
```
CHAT_THREAD observe_started conv=<8>
CHAT_THREAD observe_emit conv=<8> count=<n> firstId=<8> lastId=<8>
CHAT_LIST bottom_anchor_enabled …
```

## Parking conditions

- If after Flow migration the chat STILL shows black wait on initial open (i.e. first Flow emission still happens after first Compose frame for some reason) → park per WORKING_RULES rule 4 (this would be the THIRD architectural attempt at chat-list lifecycle). Escalate to Vladislav-architect for a Variant C decision (ChatThreadStateHolder cache).
- If reversed item ordering breaks date separators or E2EE row placement after `asReversed()` → re-derive `displayItems` from scratch (build newest-first directly from `messages.reversed()` with date separators in the new direction); don't fall back to `asReversed()` of the existing `chatItems` if it produces visible bugs.
- If `mapToList(Dispatchers.IO)` produces noticeable jank on the IO dispatcher → switch to `Dispatchers.Default` or expose dispatcher choice; benchmark before architectural change.
- If walk-through of the 12 `reloadMessages()` call-sites reveals a Type C site that needs the one-shot pull AND a side-effect that depends on `messages` being the just-pulled snapshot rather than the Flow's latest emission → preserve `reloadMessages()` helper for that one site, document why.

## Last hand-off

### 2026-05-26 — v1.1: defer chat-open side-effects past first Flow emit

**Test #81 FAIL (v1).** Tecno log order on chat open:
```
22:41:56.079  ChatScreen subscribed
22:41:57.420  SEND_TRACE encrypt_lock_wait        ← +1.341 s
22:41:57.423  CHAT_THREAD observe_started        ← +0.003 s
22:41:57.424  CHAT_THREAD observe_emit count=30  ← +0.001 s  (Flow itself is fast)
```
Vladislav report: "Тест 81. Пока вроде все так же, черный экран есть, и листание истории сообщений так же идет рывками". Architect root-cause (independent reading of the same logs): the `LaunchedEffect(conversationId)` ran `markConversationRead` + `sendMessage(profileCard)` IMMEDIATELY on chat open. Both take the ratchet `session_lookup` + DB write/encrypt locks; on a writer-priority SQLCipher these serialised AHEAD of the SQLDelight `observeMessages` Flow's first query. ~1.3 s of black wait between Compose entering ChatScreen and the bottom-anchored list painting.

**v1.1 fix.** Split the original `LaunchedEffect(conversationId)`:
- KEEP the immediate `ChatScreen subscribed` log (still runs the moment Compose enters the screen — for log-order traceability).
- MOVE both heavy side-effects (markConversationRead + profile-card-send) into a separate `LaunchedEffect(conversationId, firstFlowEmitReceived)` that early-returns until the Flow has actually emitted.
- INTRODUCE `firstFlowEmitReceived` boolean state — distinct from `initialMessageIds != null` because a brand-new conversation legitimately emits `emptyList()` (no messages yet) and the profile-card-send must still fire in that case. The flag is set by a separate `messagesFlow.first()` collector that subscribes, captures the very first emission, and exits (one extra cheap SqlDelight SELECT at chat open — acceptable for unblocking first-paint).

**Required log order after v1.1:**
```
ChatScreen subscribed
CHAT_THREAD observe_started conv=<8> initialMessageIdsSize=<n>
CHAT_LIST bottom_anchor_enabled …
CHAT_THREAD deferred_side_effects_start conv=<8>     ← new log; v1.1
PROFILE_CARD send_attempt / SEND_TRACE …             ← only after this point
```

**CI fix.** `:shared:core:storage:compileTestKotlinJvm` failed in v1 because two test fakes (`InMemoryRepositoryTest.FakeMessageRepository` and `DefaultMessagingServiceTest.FakeMessageRepository`) didn't override the new `observeMessages` interface method. v1.1 adds:
- Storage fake: `MutableStateFlow<Int>` tick bumped on every write, `observeMessages` maps tick → filtered list (real reactive behaviour, lets tests exercise the Flow path).
- Messaging fake: `emptyFlow()` (existing tests don't exercise the Flow path, only one-shot `getMessages`).

APK MD5 (Test #81.1): `5d471fb1e15973f1bdbca2c00f465abe`.
