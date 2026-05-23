# Track: PR-UI-CHAT-AUTOSCROLL1 — auto-scroll to bottom on chat open

**Status:** queued (mini-lock authored before code per `docs/WORKING_RULES.md` rule 3).
**Branch (not yet opened):** `feat/pr-ui-chat-autoscroll1` (cut fresh from `origin/master` at session start).
**Layer:** Android UI only — `ChatScreen.kt` (`InputBar` / `ChatScreen` composable scope; LazyListState handling).
**Authored:** 2026-05-23, at PR-NOTIF-DIAG close.

## Goal

Fix the user-visible UX bug Vladislav reported during Test #78: when unread messages arrive while the user is not in a given chat, opening that chat lands the LazyColumn somewhere in the middle of the loaded list, not at the bottom. The user has to scroll down manually to see the new message(s) — which defeats the purpose of opening the notification.

**Scope is intentionally minimal.** This is one specific path: "chat just opened from outside, there are messages, scroll to last." All the harder cases (preserve scroll position when the user is reading history, "↓ New messages" floating chip, animation tuning) are NOT in this PR — they're queued as a richer **auto-scroll policy** track later if the simple fix proves insufficient.

## Scope (do this, only this)

1. **First-open autoscroll.** When `ChatScreen` is composed for a `conversationId` and `reloadMessages()` populates `messages` for the first time during that composition, scroll the `LazyListState` to `messages.lastIndex` (or `messages.size - 1`, same thing). This is the case Vladislav hit.
2. **New incoming message while chat IS the active screen.** If `_incomingMessages` emits for the conversation that's currently displayed, scroll to bottom after the LazyColumn updates. (This case may already work via the existing `if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)` after `reloadMessages()` in some paths — verify and unify.)
3. **Own-message send.** When the local user sends a text or voice message, scroll to bottom after the new row appears. (Already mostly true; the `finalizeAndSendVoice` path already animates after `reloadMessages` per Test #76 evidence — verify and don't regress.)

That is the entire scope. The behaviour switch is: **on chat open, on incoming message in active chat, on own send → scroll to bottom**.

## Out of scope (do NOT touch)

- ❌ Preserve-scroll-position when the user has manually scrolled up to read history. Out of scope intentionally — wider auto-scroll policy is a follow-up PR. The simple fix here always scrolls to bottom; if this regresses anyone's "I was reading old messages and got interrupted" workflow, log it and we'll revisit. Vladislav's reported pain point is the more common case.
- ❌ "↓ N new messages" floating chip with tap-to-jump.
- ❌ Animation curve tuning (`animateScrollToItem` default easing is fine).
- ❌ `NotificationManager.cancel(notificationId)` on chat open — that's PR-NOTIF-POLICY1's responsibility, not this one.
- ❌ Any change to `MessageBubble`, `AudioBubble`, day-separators, or the `LazyColumn` layout itself.
- ❌ Any change to `_incomingMessages` flow, conversation repository, message repository, or anything upstream of the `messages: List<MessageRow>` snapshot the LazyColumn consumes.
- ❌ Read receipts logic (`markConversationRead` already runs on chat open per the existing `LaunchedEffect`).
- ❌ `ChatScreen` composables for the **group** chat path (`apps/android/.../screens/group/GroupChatScreen.kt`) — same bug class probably exists, separate track per the group-feature-not-yet-configured note in PR-NOTIF-DIAG mini-lock.
- ❌ Other screens (`ChatsListScreen`, `OnboardingScreen`, `SettingsScreen`).

## Test acceptance (Test #79)

`assembleDebug` green; APK SHA256 published in the PR body. Test on Tecno (real device, Wi-Fi) + emulator pair.

**Scenarios (all must pass before merge):**

1. **First-open autoscroll.** From a state where ChatScreen is closed and there are 10+ messages in the conversation, including 3 unread arrivals from the emu while PHANTOM was in the background. Tap the heads-up notification → ChatScreen opens → LazyColumn is at the bottom showing the latest message, no manual scroll required.
2. **Active-chat incoming.** Chat is already open. Emu sends 2 messages. LazyColumn auto-scrolls to bottom each time so the new message is visible.
3. **Own-message send.** From the bottom of the chat, type a new text and tap Send. LazyColumn stays at the bottom showing the sent row.
4. **Own voice send.** Same as 3 but record + release a 3-sec voice. LazyColumn stays at the bottom showing the uploading bubble (`Uploading N/M` ticker) and stays there as the row flips to SENT.
5. **No regression on existing send chain.** Send 5 messages in quick succession. No flicker, no jump to a previous position between messages.

**Non-blocking observation (not a scenario to grade):** if Vladislav happens to scroll up to read history while messages are coming in, the simple fix will rubber-band him back to the bottom on every new incoming. This is a known limitation of the simple fix — surface it in the Test #79 verdict so we know whether to fast-track the richer policy track.

## Parking conditions

- If the simple scroll-on-`reloadMessages` approach turns out to flicker or fight the LazyColumn's restore-scroll-on-recomposition behaviour after two architectural attempts → park per WORKING_RULES rule 4, redesign with `LaunchedEffect(conversationId)` driving a one-shot scroll + a separate `LaunchedEffect(messages.size)` driving the incremental scroll, or pull the logic into a `rememberSaveable`-backed view-model side.
- If the fix requires changing the message-load order (e.g. loading a smaller initial page from the bottom up rather than loading everything and scrolling) → out of scope; log as follow-up.

## Last hand-off

(empty — track queued, not yet active)
