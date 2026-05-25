# Track: PR-UI-CHAT-BOTTOM-ANCHOR1 — bottom-anchored chat list (replace scroll-after-layout)

**Status:** queued (mini-lock authored before code per `docs/WORKING_RULES.md` rule 3; this track replaces PR-UI-CHAT-AUTOSCROLL1 which was parked per rule 4 after two failed architectural attempts).
**Branch (not yet opened):** `feat/pr-ui-chat-bottom-anchor1` (cut fresh from `origin/master` at session start).
**Layer:** Android UI only — `ChatScreen.kt` LazyColumn refactor.
**Authored:** 2026-05-24, immediately after PR #217 (AUTOSCROLL1) was parked.
**Replaces:** `docs/tracks/chat-autoscroll.md` (PR #217, closed without merge).

## Goal

Make `ChatScreen`'s message list a **proper chat list**: latest message visible immediately on chat open, new incoming messages appear at the bottom edge naturally, no post-layout scroll correction, no 2-second visible jump. This is the architectural fix that AUTOSCROLL1 attempted to bandaid with `scrollToItem`-after-layout.

**Standard pattern in mainstream messengers** (Telegram, WhatsApp, Signal): `LazyColumn(reverseLayout = true)` + reversed item order so that visual bottom = list index 0 = most recent message. The user opening a chat lands on the latest message because that's where the LazyColumn's natural rendering position is, not because a scroll command corrected the position after layout.

## MANDATORY pre-implementation step — item-order mapping

**Per Vladislav 2026-05-24:** before writing any LazyColumn refactor code, the builder MUST commit (in the PR body or as a comment in code) a written item-order mapping declaring:

1. Which Kotlin collection is **oldest-first** vs **newest-first** at each layer (DB query result, `messages`, `chatItems`, `displayItems`).
2. Whether `LazyColumn(reverseLayout = …)` is `true` or `false`.
3. Which item appears at the **visual bottom** of the LazyColumn (this is the latest message in messenger UX) — explicitly state which LazyColumn item index that corresponds to (`0` for reverseLayout=true with newest-first collection, `lastIndex` for reverseLayout=false with oldest-first collection, etc).
4. Where E2EE row + pinned banner are placed in the LazyColumn item-stream and what their final visual position is.
5. Where day separators are placed in the LazyColumn item-stream and what their final visual position is (in particular: with reverseLayout=true, the separator must appear ABOVE the day's messages, not below).

**Reason this is mandatory:** reverseLayout is easy to get half-right and ship a build where messages render in reverse chronological order, or where date separators flip to the wrong side. Vladislav-architect's explicit warning: "не гадать". Writing the mapping down first forces the builder to think it through.

The mini-lock acceptance criteria below check the *visible* result; the mapping check catches the bug class *before* test.

## Scope (do this, only this)

### A. LazyColumn refactor

1. Change the existing `LazyColumn { … }` block (around line 1095, `ChatScreen.kt`) to `LazyColumn(state = listState, reverseLayout = true, …) { … }`.
2. Reverse the order of items inside the LazyColumn so the newest message is at LazyColumn index 0 (visual bottom).
3. Re-derive `chatItems` (or a new `displayItems` list) in reversed order so the existing item rendering code (`is ChatItem.Msg` / `is ChatItem.DateSep`) continues to work, just from the reversed source.

### B. Prefix-row positioning (E2EE row, pinned banner)

The current LazyColumn renders these BEFORE the message stream:
- `item(key = "__e2ee__") { E2EENoteRow(…) }` (always)
- `item(key = "pinned_banner") { … }` (conditional on `pinnedMessages`)

With `reverseLayout = true`, items rendered FIRST in source order appear at the visual BOTTOM. That's wrong for an E2EE row and a pinned banner — they belong at the visual TOP of the conversation (above the oldest message).

**Two ways to fix this**, decide at mini-lock-review-time:

- **Option 1 — render E2EE row + pinned banner LAST in LazyColumn source order.** With `reverseLayout = true`, last-in-source = visual top. Keeps the items as `LazyColumn item { … }` blocks.
- **Option 2 — render E2EE row + pinned banner OUTSIDE LazyColumn**, as separate composables at the top of the parent Column. Simpler reasoning (their position no longer depends on LazyColumn's reverse semantics) but a refactor of the Scaffold body structure.

Recommendation: Option 1 first (smaller change). If it produces ugly interactions with scroll-to-pinned (`listState.animateScrollToItem(pinnedIndex)` at line ~1117), revisit and consider Option 2.

### C. Scroll-related code removal

Remove (or simplify) the following pieces of AUTOSCROLL1 that are no longer needed:

1. The new `LaunchedEffect(conversationId, chatItems.size, pinnedMessages.isNotEmpty())` block that calls `snapshotFlow + first + scrollToItem` for initial-open — DELETE entirely; bottom-anchored list doesn't need it.
2. The `var initialScrollDone by remember(conversationId)` flag — DELETE.
3. The three `withFrameNanos { } + scrollToItem(layoutInfo.totalItemsCount - 1)` blocks for voice-send / text-send / incoming-active — these are still needed because **with reversed list**, "send a new message" puts the new item at the top of the data source (which maps to the BOTTOM visually). But `listState.firstVisibleItemIndex` could be > 0 if the user scrolled away. So we still want to scroll back to index 0 after sending or receiving — only the target index simplifies: always `scrollToItem(0)` (= visual bottom in reversed layout).
4. The `imports` for `kotlinx.coroutines.flow.first` — DELETE if no other call site uses it (probably none).

The four `CHAT_SCROLL` log lines should be retained but updated to reflect the new semantics:
```
CHAT_SCROLL source=initial_open_skipped reason=bottom_anchor       (once per conv enter, optional)
CHAT_SCROLL source=voice_send target=0 reverseLayout=true
CHAT_SCROLL source=text_send  target=0 reverseLayout=true
CHAT_SCROLL source=incoming_active target=0 reverseLayout=true
```

Plus one startup log per chat open:
```
CHAT_LIST bottom_anchor_enabled conv=<8> total=<n>
```

### D. Scroll-to-pinned

`listState.animateScrollToItem(pinnedIndex)` at line ~1117 currently scrolls to the chatItems-index of the most-recently-pinned message. With reversed layout, `pinnedIndex` becomes the reversed index in `displayItems`. Either:

- Recompute `pinnedIndex` against the reversed list, OR
- Use the LazyColumn's `scrollToItem` with the original index, knowing that reverseLayout doesn't change the index semantics — it only changes the visual direction. (Investigate at code time which is correct.)

This is in scope because not fixing it breaks an existing feature.

### E. Date separator placement

`chatItems` currently injects `ChatItem.DateSep` BEFORE the first message of each day. With reverseLayout=true, the rendering order is bottom-up. Either:

- Inject `DateSep` AFTER the last message of each day (in the new reversed order), so visually the separator still appears above the day's messages, OR
- Keep the existing `chatItems` and just reverse-iterate it for `displayItems`. The `DateSep` ordering may need to be re-derived; verify at code time.

### F. Initial composition: starting position

With `reverseLayout = true`, `LazyColumn` starts with `firstVisibleItemIndex = 0` by default, which IS the visual bottom. So opening a chat **immediately** lands on the newest message — no `LaunchedEffect`, no `awaitFrame`, no `snapshotFlow`. **This is the whole point of the refactor.**

## Out of scope (do NOT touch)

- ❌ Floating "↓ N new messages" chip with tap-to-jump (deferred to a future track once basic chat UX is right)
- ❌ `isNearBottom` / preserve-scroll-position when user reads history (same deferral)
- ❌ `NotificationManager.cancel` on chat open (PR-NOTIF-POLICY1)
- ❌ `MessageBubble` / `AudioBubble` / day-separator visual content (only their **position** in the list changes, not their rendering)
- ❌ `_incomingMessages` flow / repos / `markConversationRead` semantics
- ❌ `GroupChatScreen.kt` (group path not configured in production)
- ❌ Any change to message data model, DB queries, transport, voice, recorder

## Test acceptance (Test #80) — Vladislav-locked 8 scenarios

`assembleDebug` green. APK SHA256 + MD5 in PR body. Test on Tecno (real device, Wi-Fi) + emulator pair. Vladislav verified MD5 of installed APK matches built APK before testing.

1. **Open chat with existing history → latest message visible immediately, no delayed jump.**
2. **Receive 3 messages while outside chat → open chat → latest message visible immediately.** (No black wait, no mid-history flash, no 2-second correction.)
3. **Receive messages while chat open and user is at bottom → messages appear naturally at bottom.** (No jerky scroll, no overlapping animations.)
4. **Send text → message appears naturally at bottom.**
5. **Send / receive voice → bubble appears naturally at bottom.** (Uploading N/M ticker visible; row flips to SENT.)
6. **Date separators remain visually correct.** With reverseLayout, the separator must appear ABOVE the messages of that day, not below. Multi-day chat scrolled top-to-bottom shows: [E2EE row at top] · [pinned banner if any] · [oldest day separator] · [oldest messages] · [next day separator] · … · [newest day separator] · [newest messages].
7. **E2EE banner / profile / pinned elements do not break index/order.** Tap pinned banner → LazyColumn animates to that message position correctly.
8. **No black wait / no mid-history flash / no 2-second scroll correction** anywhere.

**Bonus** (non-blocking but valuable):
- Log `CHAT_LIST bottom_anchor_enabled conv=<8> total=<n>` fires exactly once per chat open.
- No `CHAT_SCROLL source=initial_open …` lines because bottom-anchored list doesn't scroll on open.

**Failure mode to watch for during test:** reverseLayout half-applied → messages render in reverse chronological order (newest at top, oldest at bottom). If this happens, the item-order mapping at the top of this lock was wrong — re-derive before any code change.

## Parking conditions

- If `reverseLayout = true` interacts badly with `imePadding` or `WindowInsets` and the input bar overlaps the latest message → revisit with Option 2 (E2EE + pinned banner outside LazyColumn) and re-test. If that ALSO fails → park per rule 4, escalate to Vladislav-architect for a `ConstraintLayout`-style fix.
- If reversed `chatItems` breaks date separators in a way that's not fixable in 30 minutes → park, derive `displayItems` differently (e.g. drop the separator pre-injection and render separators in `itemsIndexed` based on neighbor dates).
- If scroll-to-pinned breaks and isn't fixable in 30 minutes → park, leave pinned-banner-tap dormant in this PR and document as a follow-up.

## Last hand-off

**PARKED 2026-05-25.** PR #226 closed without merge per `docs/WORKING_RULES.md` rule 4. Three implementation attempts (v1, v1.1, v1.2) all FAIL on Test #80, #80.1, #80.2 UX criteria. The `reverseLayout = true` direction was architecturally **not wrong** — it solved item-ordering. But it could not solve the underlying root cause: `ChatScreen` starts from an empty local `messages` state and only loads history AFTER first render. Anchoring an empty LazyColumn at "visual bottom" still produces black wait + delayed materialization + jerky history scroll. **Track replaced by PR-UI-CHAT-THREAD-STATE1** (`docs/tracks/chat-thread-state.md`).

**Three attempts and what each proved:**

1. **PR #226 v1 (`14307829`)** — `LazyColumn(reverseLayout = true)` + `displayItems = chatItems.asReversed()` + E2EE row last-in-source + pinned banner outside LazyColumn + `scrollToItem(0)` for own send + no scroll for incoming. **Test #80 FAIL:** "опен сразу не внизу, не показывает сразу последнее сообщение, новые сообщения появляются снизу но рывками, видны не полностью". Logs showed `CHAT_LIST bottom_anchor_enabled total=1 sourceFirst=empty sourceLast=__e2ee__` — the LazyColumn anchored to the empty state, before reloadMessages() returned.

2. **PR #226 v1.1 (`4ae6f8ba`)** — added `initialMessagesLoaded` gate (hide LazyColumn behind `Box(BgDeep)` until reloadMessages finishes) + `initialMessageIds` snapshot after load + 72dp `__bottom_input_spacer__` for composer clearance + logs moved after gate. **Test #80.1 FAIL:** "так же когда заходишь в чат, черный экран, идет прогрузка, а потом в самый низ прокручивает. Так же рывками листается чат. И появилось пустое пространство после полученного сообщения". Gate replaced "mid-history flash" with "black wait"; 72dp spacer created visible blank gap.

3. **PR #226 v1.2 (`813484dc`)** — removed black gate (LazyColumn renders immediately) + shrunk spacer 72dp → 8dp + renamed `__bottom_input_spacer__` → `__bottom_anchor__` + `initialMessageIds` used only for animation suppression (not gate). **Test #80.2 FAIL:** "Уже лучше, скрол есть, но все так же черный экран и скрол по истории так же рывками". Log-side bug fixed (`total=1 sourceFirst=empty` gone), spacer no longer over-correcting, but **the black wait persisted** because Compose still rendered LazyColumn first with `messages = emptyList()` for ~1 frame, then animated all 23+ loaded messages in as "new" via the AnimatedVisibility slide-in wrapper.

**Architectural lesson (Vladislav-architect, 2026-05-25, captured in agent memory as `feedback_chatscreen_pull_style_root_cause.md`):**

`scrollToItem`-after-load AND `reverseLayout = true` are both **symptom-level fixes**. The real root cause is **`ChatScreen` opening with empty local `messages` state and pulling history via `reloadMessages()` in `LaunchedEffect(conversationId)` AFTER first composition.** Compose anchors LazyColumn's `firstVisibleItemIndex` to the empty / e2ee-only state on the first frame; when messages stream in later, the anchor either refuses to follow (mid-history opening) OR Compose animates all history items as if they were brand-new (slide-in pile-up). No amount of LazyColumn-layer tweaks can fix this — the data source has to be reactive (Flow-backed), not pull-style.

**Architectural decision: Variant A+ (Vladislav 2026-05-25).** Replace pull-style `var messages by remember + reloadMessages()` with `MessageRepository.observeMessages(conversationId): Flow<List<MessageEntity>>` (SQLDelight `asFlow().mapToList`, infrastructure already in repo via `sqldelight-coroutines-extensions`). ChatScreen `collectAsState`. Combined with bottom-anchored render in the same PR (because data-source fix alone doesn't guarantee bottom-anchor placement; both are needed together).

**Replacement track: PR-UI-CHAT-THREAD-STATE1.** Mini-lock at `docs/tracks/chat-thread-state.md`. Scope: observeMessages Flow source + bottom-anchored render + initial-history animation suppression + removal of 12 `reloadMessages()` call-sites (where they only refresh the list) with preservation of separate side-effects (markRead, sendProfile, anchor-after-own-send).

**Diagnostic value retained.** PR #226 commits stay closed-but-visible on GitHub (`14307829`, `4ae6f8ba`, `813484dc`) for the diagnostic trail. Future LazyColumn / chat-list work can grep for `CHAT_LIST bottom_anchor_enabled` + the `__bottom_anchor__` pattern.

**Out-of-scope items inherited by THREAD-STATE1:**

- "↓ N new messages" floating chip (still deferred, separate track when needed).
- Preserve-scroll-position when user scrolls up to read history (same deferral).
- `NotificationManager.cancel` on chat open (PR-NOTIF-POLICY1's responsibility, unchanged).
- ChatListScreen Flow migration (kept as pull-style; can migrate in a follow-up after THREAD-STATE1 proves the pattern).
