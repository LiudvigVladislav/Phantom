# Track: PR-UI-CHAT-NEW-MSG-CHIP1 — floating scroll-to-bottom + new-messages chip

**Status:** queued (mini-lock authored before code per `docs/WORKING_RULES.md` rule 3). Item #3 in the Stabilization Sprint locked queue (`feedback_android_stabilization_sprint.md`).
**Branch (not yet opened):** `feat/pr-ui-chat-new-msg-chip1` (cut fresh from master AFTER this mini-lock merges).
**Layer:** Android UI only (Compose). NO data layer, NO DB, NO transport, NO crypto.
**Authored:** 2026-05-26, immediately after PR #231 CACHE1 merged.
**Design source:** Vladislav-designer handoff bundle `phantom-messengers/project/Scroll-to-bottom.html` + `scroll-bottom.jsx` + `scroll-bottom-tokens.css`. **Vladislav-locked 2026-05-26: implementation MUST be 1:1 with the design handoff including all animations.**

## Goal

After CACHE1 closed the chat-open black wait, Vladislav noted during Test #82.1: when the user has scrolled up to read history and an incoming message arrives, no auto-scroll is correct UX (Vladislav-locked 2026-05-25), but the user has no signal that new messages arrived. The previous behaviour effectively hid incoming messages from a reading user.

**Fix:** a floating circular scroll-to-bottom button with a new-messages count badge. Sits 14px above the composer. Visible only when the user is scrolled away from the visual bottom. Badge counts incoming messages received while the button is visible. Tap behaviour: scroll to first unread (count ≥ 1) or to latest (count = 0).

## 1:1 design constraints (Vladislav-locked 2026-05-26)

The Compose implementation MUST reproduce the design exactly. Any deviation (colour, position, animation duration, animation curve) is a defect, not a creative choice.

### Button geometry

| Token | Value | Source |
|---|---|---|
| Diameter | 44 × 44 dp | `--stb width/height: 44px` |
| Corner radius | 50% (perfect circle) | `border-radius: 50%` |
| Background | `surface-elevated` @ 92 % alpha + 12 px backdrop-blur | `--btn-bg: rgba(22,26,32,0.92)` + `backdrop-filter: blur(12px)` |
| Border | 1 dp `border-subtle` | `border: 1px solid var(--btn-border)` |
| Elevation shadow | `0 12 32 dp rgba(0,0,0,0.55)` | `--btn-shadow` |
| Glyph | chevron-down, 20 × 20 dp, stroke 1.75 dp | `<polyline points="6 9 12 15 18 9" />` |
| Glyph colour | `text-secondary` (idle) → `text-primary` (hover) | `--text-secondary` / `--text-primary` |
| Anchor — vertical | 14 dp above the 56 dp composer (i.e. `bottom: 70 dp`) | `.float-anchor { bottom: 70px }` |
| Anchor — horizontal | 14 dp from the trailing edge | `.float-anchor { right: 14px }` |

**Note on backdrop-blur:** Compose 1.6+ supports `Modifier.blur(...)` but not behind-content blur on Android < 12. On Android 12+ use `Modifier.hazeChild`/`RenderEffect.createBlurEffect`; on Android < 12 fall back to a higher-alpha solid `surface-elevated` (no blur). Visual difference acceptable — the button must remain readable in both cases.

### Badge

| Token | Value | Source |
|---|---|---|
| Min width | 18 dp (pill) | `.stb-badge { min-width: 18px }` |
| Height | 18 dp | `.stb-badge { height: 18px }` |
| Horizontal padding | 0 / 5 dp | `padding: 0 5px` |
| Position relative to button | top: −5 dp, right: −5 dp | `.stb-badge { top: -5px; right: -5px }` |
| Background | `cyan` #00D4FF | `--cyan: #00D4FF` |
| Text colour | `surface-deep` (#08090C on dark; #08090C on light) | `--surface-deep` |
| Typography | JetBrains Mono, 10 sp, weight 500, tabular figures | `.stb-badge { font-family: JetBrains Mono; font-size: 10px; font-weight: 500; font-variant-numeric: tabular-nums }` |
| Separator from button | 2 dp ring in chat-surface colour via shadow | `box-shadow: 0 0 0 2px var(--surface)` |
| Cap value | "99+" beyond 99 | `count > 99 ? "99+" : count` |

### Animations (DURATION + CURVE both Vladislav-locked)

| Animation | Duration | Property | Easing | Source |
|---|---|---|---|---|
| **Enter** | 180 ms | opacity 0 → 1, translateY 6 dp → 0 dp | `cubic-bezier(0.22, 1, 0.36, 1)` | `@keyframes stb-enter` |
| **Exit** | 140 ms | opacity 1 → 0, translateY 0 dp → 4 dp | `cubic-bezier(0.55, 0, 1, 1)` | `@keyframes stb-exit` |
| **Badge bump** | 220 ms | scale 1 → 1.12 → 1 (single tween) | `cubic-bezier(0.22, 1, 0.36, 1)` | `@keyframes badge-bump` |

In Compose terms:

```kotlin
private val EnterEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val ExitEasing  = CubicBezierEasing(0.55f, 0f, 1f, 1f)

AnimatedVisibility(
    visible = scrolledUp,
    enter = fadeIn(animationSpec = tween(180, easing = EnterEasing)) +
            slideInVertically(animationSpec = tween(180, easing = EnterEasing)) {
                with(density) { 6.dp.roundToPx() }
            },
    exit  = fadeOut(animationSpec = tween(140, easing = ExitEasing)) +
            slideOutVertically(animationSpec = tween(140, easing = ExitEasing)) {
                with(density) { 4.dp.roundToPx() }
            },
)
```

Badge bump in Compose:

```kotlin
val bumpScale = remember(badgeBumpKey) { Animatable(1f) }
LaunchedEffect(badgeBumpKey) {
    bumpScale.snapTo(1f)
    bumpScale.animateTo(1.12f, animationSpec = tween(110, easing = EnterEasing))
    bumpScale.animateTo(1f,    animationSpec = tween(110, easing = EnterEasing))
}
Modifier.graphicsLayer { scaleX = bumpScale.value; scaleY = bumpScale.value }
```

The 220 ms total budget splits 110 / 110 for a clean linear approach to peak and back — visually equivalent to the CSS `scale(1) → scale(1.12) → scale(1)` 3-keyframe at the same total duration.

### NOT in the design (banned)

- ❌ No glow / no pulse / no colour change on the badge — the bump is a single quick scale.
- ❌ No press-scale on the button (hover lifts glyph colour, no transform). On touch, "hover" is ignored.
- ❌ No counter showing absolute total — the badge always shows "messages received WHILE button visible", never lifetime unread.
- ❌ No persistence across chat sessions — opening a chat from ChatList resets count to 0 (CACHE1 preload + snapshot path naturally start the user at visual bottom).

## Visibility model

The button is visible iff the LazyListState indicates the user is scrolled away from the visual bottom.

With `LazyColumn(reverseLayout = true)` (carried forward from CACHE1 v1.1):
- `source[0]` = `__bottom_anchor__` = visual BOTTOM.
- User is "at the bottom" iff `firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0`.
- Any other state = "scrolled up".

Compose:

```kotlin
val scrolledUp by remember {
    derivedStateOf {
        listState.firstVisibleItemIndex > 0 ||
            listState.firstVisibleItemScrollOffset > 0
    }
}
```

`derivedStateOf` is critical — it batches the two upstream observations into a single recomposition trigger and only emits when the boolean actually flips. Without it the button-host would recompose on every scroll pixel.

## Count semantics

| Event | Action |
|---|---|
| Chat open from ChatList (CACHE1 preload + snapshot path) | `count = 0`, button hidden (user at bottom). |
| Incoming message arrives while `scrolledUp == true` | `count++`. If `count` was previously >0 the visible badge bumps. |
| Incoming message arrives while `scrolledUp == false` | `count` unchanged (still 0). User can see the message at the bottom anyway. |
| User sends own text/voice while `scrolledUp == true` | **Auto-scroll to bottom + `count = 0`**. Vladislav-locked from design handoff: "User's own send is an explicit signal they want to be at the latest. Matches Telegram and Signal; deviating would feel broken." |
| User taps the button while `count >= 1` | Animated scroll to FIRST UNREAD (the oldest of the incoming messages received while button was visible). 280 ms ease-out, no overshoot. `count = 0` after scroll completes. |
| User taps the button while `count == 0` | Animated scroll to latest (source index 0 in reverseLayout). 280 ms ease-out. |
| User scrolls back to the bottom by hand | Button disappears via exit animation. `count = 0` after exit. |

"First unread" = the message whose id is the OLDEST in the in-memory list of "incoming-since-button-visible" ids. Tracked in ChatScreen-local state (a `LinkedHashSet<String>` so insertion order = arrival order = "first unread first").

## State management

A single `@Composable` helper:

```kotlin
@Composable
fun rememberScrollToBottomState(
    listState: LazyListState,
    incomingFlow: Flow<IncomingMessage>,
    onUserSend: () -> Unit, // hook so ChatScreen can call this on text/voice send
    scope: CoroutineScope,
): ScrollToBottomState
```

Returns a state object:

```kotlin
class ScrollToBottomState {
    val visible: Boolean
    val count: Int
    val badgeBumpKey: Int   // incremented to trigger badge bump animation
    suspend fun scrollToFirstUnreadOrLatest(listState: LazyListState)
    fun onOwnSend()          // resets count + auto-scroll
}
```

ChatScreen wires:
- `visible` → `AnimatedVisibility` around the button.
- `count` → badge text.
- `badgeBumpKey` → trigger the bump `LaunchedEffect`.
- `onClick` → `scope.launch { state.scrollToFirstUnreadOrLatest(listState) }`.
- ChatScreen's existing own-send paths (text + voice) → `state.onOwnSend()`.

## Recording-panel coexistence (Vladislav-locked from design handoff)

> "The recording panel replaces the input bar in the same 56 px slot, so the button's anchor point is unchanged — it stays visible while recording. This is intentional: a user may need to scroll back to the most recent incoming message mid-recording. Hiding the button would punish that flow."

→ Button visibility is decoupled from `recordingState`. Tapping the button while recording does NOT interrupt or change recording state.

## Scope (do this, only this)

### In scope (this PR only)

1. New file `apps/android/src/androidMain/kotlin/phantom/android/screens/chat/ScrollToBottomButton.kt` — Composable matching the 1:1 design tokens above.
2. New file `apps/android/src/androidMain/kotlin/phantom/android/screens/chat/ScrollToBottomState.kt` — state holder (count tracking, first-unread set, own-send reset).
3. ChatScreen integration:
   - Wire `rememberScrollToBottomState(...)` next to the existing `listState`.
   - Place `Box` containing the button anchored at `.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 70.dp)`.
   - Track incoming messages via the existing `container.messagingService?.incomingMessages` flow filtered by `conversationId`.
   - Call `state.onOwnSend()` from the existing text-send and voice-send paths.
4. New logs:
   - `CHAT_CHIP visible conv=<8> reason=<scroll_up|incoming>`
   - `CHAT_CHIP hidden conv=<8> reason=<at_bottom|user_send|tap>`
   - `CHAT_CHIP incoming conv=<8> count=<n>`
   - `CHAT_CHIP tap conv=<8> count=<n> target=<first_unread|latest>`
5. New design tokens added to the existing PHANTOM theme file (CyanAccent already exists as the primary; surface-elevated and surface-deep already exist; only the chevron-down icon may need adding to the project's icon set if not already there).

### Out of scope (deferred to other tracks)

- ❌ Persistence of unread count across chat sessions or across app restarts.
- ❌ NotificationManager / system-tray notification policy (that's `PR-NOTIF-POLICY1`, queue item 4).
- ❌ Manual scroll perf / MessageBubble jerk (that's `PR-UI-CHAT-RENDER-PERF1`, queue item 2 conditional).
- ❌ Reaction / read-receipt UX changes.
- ❌ Group chat path (`GroupChatScreen.kt` — not in production).
- ❌ Notification tap → preload (that's `PR-UI-CHAT-THREAD-CACHE2`).
- ❌ DB / SQL / schema.
- ❌ Transport / crypto / voice / recorder beyond the existing `onOwnSend()` hook.

## Anti-pattern signatures — verify NOT present in the diff

Carried forward from the chat-list lifecycle history:

- ❌ Delayed `scrollToItem(...)` outside the explicit tap handler (the AUTOSCROLL1 anti-pattern from `feedback_scroll_to_bottom_not_chat_ux.md`).
- ❌ `snapshotFlow { ... }.first { ... }` as a render gate.
- ❌ `withFrameNanos { scrollToItem(...) }`.
- ❌ `LaunchedEffect(messages.size) { listState.animateScrollToItem(...) }` — that would force-scroll on every incoming, defeating the entire point of this PR.
- ❌ Any visibility logic that hard-couples the button to recording state (button must stay visible during recording per design).
- ❌ Auto-scroll on incoming when `scrolledUp == false` — Vladislav-locked: keep user at bottom but DO NOT force jump. The natural Compose recomposition handles it via the holder's StateFlow emission.

## Test acceptance (Test #83 — Vladislav-finalize before APK)

Must PASS on Tecno real device:

1. **Open chat from ChatList → no button visible** (user at bottom). `CHAT_CHIP visible` log NOT present.
2. **Scroll up by 1+ screen → button slides in from below + fades** (180 ms enter animation visible to the eye). `CHAT_CHIP visible reason=scroll_up` logged.
3. **Receive 1 incoming while scrolled up → badge appears with "1"**. No scroll. `CHAT_CHIP incoming count=1` logged.
4. **Receive 4 more incoming → badge updates to "5" with bump animation visible** on each increment. `CHAT_CHIP incoming count=5` logged.
5. **Receive 100 incoming → badge caps at "99+"** (visually stable width thanks to tabular figures).
6. **Tap button with count=5 → animated 280 ms scroll to first unread → button fades out (140 ms exit) → count = 0**. `CHAT_CHIP tap target=first_unread` then `CHAT_CHIP hidden reason=tap`.
7. **Tap button with count=0 (after scrolling up by hand → no incoming yet → tap) → scroll to latest → button hides**.
8. **Send text/voice while scrolled up → auto-scroll to bottom + button hides + count clears**. `CHAT_CHIP hidden reason=user_send`.
9. **Recording active → button still visible if scrolled up + new incoming → badge updates + bump fires + tapping button does NOT interrupt recording**.
10. **Re-open chat from ChatList → fresh state**, button NOT visible, count = 0. (Verifies the state holder doesn't leak across conversation switches.)

## Parking conditions

Stay in track if:
- Badge bump animation timing is off → tune the easing/duration within the PR.
- Position math doesn't match design (off by a few pixels) → adjust offsets within the PR.
- Recording-panel anchor breaks → coexistence test is mandatory; fix the layout within the PR.

Escalate to a NEW track only if:
- A fundamentally new architectural problem surfaces (e.g. the incoming-message flow doesn't actually fire for chat-foreground state on certain Android versions — separate diagnostics PR).
- Compose-runtime issue (badge animation interferes with LazyColumn jank — would be `RENDER-PERF1`'s domain).

## Logs

```
CHAT_CHIP visible    conv=<8> reason=<scroll_up|incoming>
CHAT_CHIP hidden     conv=<8> reason=<at_bottom|user_send|tap>
CHAT_CHIP incoming   conv=<8> count=<n>
CHAT_CHIP tap        conv=<8> count=<n> target=<first_unread|latest>
```

All under PhantomUI tag (consistent with `CHAT_CACHE` from CACHE1).

## Last hand-off

(empty — track queued, awaiting Vladislav greenlight on this mini-lock before code begins)
