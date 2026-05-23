# Track: PR-NOTIF-POLICY1 — conversation-level notification with unread summary

**Status:** queued (mini-lock authored before code per `docs/WORKING_RULES.md` rule 3).
**Branch (not yet opened):** `feat/pr-notif-policy1` (cut fresh from `origin/master` at session start).
**Layer:** Android — `PhantomNotificationManager.kt` (show path) + likely small touch in the AppContainer wire site to clear notifications on chat-open. NO common-side changes (the source enum + helper already in place from PR-NOTIF-DIAG).
**Authored:** 2026-05-23, at PR-NOTIF-DIAG close.

## Goal

Close the UX finding from Test #78: every incoming notification for a single conversation reuses `notificationId = conversationId.hashCode()` and `tag = null`, so each new arrival **replaces** the previous heads-up rather than stacking. Result: when the user gets 3 messages while away, they see one heads-up that flashes 3 times — and visually it reads as "my notification disappeared". The pipeline is fine (PR-NOTIF-DIAG proved it); the UX is wrong.

Decide and implement a single, consistent notification policy. Architect's recommendation (carried forward from PR-NOTIF-DIAG close): **Variant A — conversation-level notification with `InboxStyle` summary.** This mini-lock is written around Variant A; if Vladislav prefers Variant B at review time, the lock gets rewritten before code.

## Variant choice (decide at mini-lock review, NOT during code)

### Variant A (recommended) — one notification per conversation, updated, with summary

- `notificationId` stays `conversationId.hashCode()` (no change).
- New `NotificationCompat.InboxStyle` carries the last N message previews (N = 3–5 — pick at review).
- `setContentTitle` = `senderName`.
- `setContentText` = newest message preview.
- `setSubText` = `"N new messages"` when N > 1; omitted when N = 1.
- Internal state tracking: per-conversation queue of last N previews + per-conversation unread count. Cleared on `ChatScreen` open or `markConversationRead`.
- On chat open: `NotificationManagerCompat.from(context).cancel(conversationId.hashCode())` clears the heads-up.

### Variant B (alternative) — one notification per message + group summary

- `notificationId = (conversationId + messageId).hashCode()` (or just `messageId.hashCode()` — equivalent for our use).
- `setGroup(conversationId.hashCode().toString())` on every message notification.
- Separate "group summary" notification with `setGroupSummary(true)` rendering an inbox-style list.
- On chat open: `nmc.cancel(per-message ids of this conversation)` + `cancel(group summary id)`.
- More expensive bookkeeping (need to track which message ids are currently posted).

**Architect's argument for A:** messengers traditionally collapse to one notification per chat — matches user expectations from WhatsApp/Telegram/Signal. Variant B can over-fill the notification shade quickly with three friends sending five messages each. **B is the right call only if Vladislav specifically wants the more-cluttered-but-more-granular shade behaviour.**

## Scope (assuming Variant A is selected at mini-lock review)

### A.1. State tracking inside `PhantomNotificationManager`

Add private object-level state (synchronised access — Android may invoke us from any worker thread spun off the messaging coroutine):

```kotlin
private data class ConversationNotifState(
    val senderName: String,
    val unreadCount: Int,
    val previews: ArrayDeque<String>, // last N, oldest-first; size <= MAX_INBOX_LINES
)

private val notifState = mutableMapOf<String, ConversationNotifState>()
private val notifStateLock = Any()

private const val MAX_INBOX_LINES = 4
```

When a new message arrives, push its preview onto `previews`, evict from the head while size > `MAX_INBOX_LINES`, bump `unreadCount`.

### A.2. New `NotificationCompat.InboxStyle`

```kotlin
val style = NotificationCompat.InboxStyle()
    .setBigContentTitle(senderName)
    .also { s -> currentState.previews.forEach { s.addLine(it) } }
.setSummaryText(if (currentState.unreadCount > 1) "${currentState.unreadCount} new messages" else null)
```

`setContentTitle` = `senderName`. `setContentText` = newest preview. `setSubText` = same `"N new messages"` rule.

### A.3. Clear-on-open hook

Add a new public method on `PhantomNotificationManager`:

```kotlin
fun clearConversationNotification(context: Context, conversationId: String) {
    synchronized(notifStateLock) { notifState.remove(conversationId) }
    NotificationManagerCompat.from(context).cancel(conversationId.hashCode())
    Log.i(LOG_TAG, "NOTIF cleared conv=${conversationId.take(8)} reason=chat_opened")
}
```

Call this from `ChatScreen`'s entry `LaunchedEffect(conversationId)` — same place that already calls `markConversationRead`. The clear can be best-effort: if state has already been cleared (user wasn't in the chat list when notification fired, etc.), the call is a no-op.

### A.4. Logging additions (extending PR-NOTIF-DIAG's logs, not replacing)

- On state update: `NOTIF state_update conv=<8> unreadCount=<n> previewsSize=<m>`.
- On clear: `NOTIF cleared conv=<8> reason=chat_opened`.
- Inside `show_entry`: extend to include `unreadCountAtShow=<n>` and `inboxLines=<m>` so logcat shows the per-event state without re-deriving.

### A.5. Behaviour invariants

- **Privacy:** previews still truncate to 30 chars (per existing `safePreview` logic). No change in what's logged or shown.
- **Importance:** channel importance stays `IMPORTANCE_HIGH` so the FIRST notification still triggers heads-up. Subsequent updates may or may not re-trigger heads-up depending on Android's "alert only once" semantics — pick at code time (`setOnlyAlertOnce(false)` if we want every new message to re-vibrate; `true` if we want one alert per "session" of incoming).
- **Quick-reply action:** the existing `RemoteInput`-based "Reply…" action stays. It's per-conversation, so unified with the new state.
- **`source` field:** keep the PR-NOTIF-DIAG attribution but don't drive any policy decision off it. A voice and a text both bump `unreadCount` by one.

## Out of scope (do NOT touch)

- ❌ Variant B (unless Vladislav explicitly switches at mini-lock review).
- ❌ Per-message tap navigation that opens at a specific message in the LazyColumn (we tap-open at the bottom — that's PR-UI-CHAT-AUTOSCROLL1's job).
- ❌ Sound / vibration / lights configuration (channel-level, separate concern).
- ❌ `PhantomMessagingService` foreground-service notification.
- ❌ Permission re-ask UX for POST_NOTIFICATIONS.
- ❌ `notificationId` strategy migration beyond what Variant A requires.
- ❌ Group / channel notification paths (still not configured in production).
- ❌ The KMP-side `onNewMessageNotification` signature (already updated in PR-NOTIF-DIAG; no further changes needed for Variant A).
- ❌ `MessageBubble` / `AudioBubble` rendering / day-separators / unread badge in the Chats list (separate UI tracks).

## Test acceptance (Test #80)

`assembleDebug` green; APK SHA256 in PR body.

**Scenarios:**

1. **First message arrives.** Emu sends one text while PHANTOM is in the background. One heads-up appears showing `senderName` + the message preview. No `setSubText`. Inbox style not visible at heads-up level (Android collapses to single-line preview for heads-up); expanding reveals the same single line.
2. **Second message arrives 5 seconds later.** Same notification slot updates. Title still `senderName`. Content text shows the newer message. `setSubText` now reads `"2 new messages"`. Expanding the notification reveals an inbox-style list showing both messages, oldest at the top.
3. **Third + fourth messages.** Same slot updates again. `setSubText` shows `"3 new messages"` then `"4 new messages"`. Inbox shows the last 3 (or 4 — depends on `MAX_INBOX_LINES` chosen at review). Oldest beyond N is dropped.
4. **Open chat.** Tap the notification (or open ChatScreen by any means). The notification disappears from the shade. Log shows `NOTIF cleared conv=<8> reason=chat_opened`. Internal state for this conversation is cleared.
5. **Background again, new message.** After scenario 4, send one more text from emu. New heads-up appears with `unreadCount = 1` (no `setSubText`), confirming state was reset on the earlier clear.
6. **Two different conversations.** Emu sends 1 text in conv A and 1 text in conv B. Two separate heads-ups (different `notificationId`), each with their own state. Opening A clears only A's notification; B's stays.
7. **Voice message arrives.** Same `source=voice_v2_manifest` path now also bumps the conversation's `unreadCount` and adds `"🎤 Voice message"` to the inbox lines. PR-NOTIF-DIAG already proved the callback delivers — this PR just changes what's shown.
8. **Reply-from-notification still works.** Inline `Reply…` action on the heads-up still functional (PR-NOTIF-POLICY1 mustn't break the existing `QuickReplyReceiver` plumbing).

## Parking conditions

- If `NotificationCompat.InboxStyle` doesn't honour all our existing `Builder` configuration (icon, content intent, reply action) → log the gap and switch to `MessagingStyle` (designed for chat apps; harder migration, more correct long-term). Single architectural fall-back; if that also doesn't fit, park per rule 4 and redesign.
- If the `notifState` map grows unbounded (user has 100+ active conversations with notifications never cleared) → add an LRU cap (50 conversations) and evict oldest. Note this in the close.
- If `setOnlyAlertOnce(true|false)` choice produces user-visible flicker that fights with our `setAutoCancel(true)` config → revert to keeping the existing alert-every-update behaviour and revisit.

## Last hand-off

(empty — track queued, not yet active)
