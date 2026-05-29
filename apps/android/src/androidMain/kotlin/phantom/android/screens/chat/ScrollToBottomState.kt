// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.chat

import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import phantom.core.messaging.IncomingMessage

/**
 * PR-UI-CHAT-NEW-MSG-CHIP1 — state holder for the floating scroll-to-bottom
 * + new-messages chip.
 *
 * Visibility-counting model (Vladislav-locked 2026-05-26 in
 * `docs/tracks/chat-new-msg-chip.md` § "Count semantics"):
 *
 * - Chat open from `ChatList` (CACHE1 preload + snapshot path) →
 *   `count = 0`, button hidden (user at visual bottom).
 * - Incoming message arrives while `visible == true` → `count++`,
 *   `badgeBumpKey++` (re-trigger the badge bump animation). Tracked
 *   in [unreadIds] as a `LinkedHashSet<String>` so insertion order
 *   = arrival order = "first unread first".
 * - Incoming message arrives while `visible == false` → ignored.
 *   The user is already at the bottom and will see it via natural
 *   LazyColumn recomposition from CACHE1's hot StateFlow.
 * - User sends own text or voice while `visible == true` →
 *   [onOwnSend] resets count + animates scroll to source index 0
 *   (the visual bottom in `LazyColumn(reverseLayout = true)`).
 * - User taps the button → [scrollToFirstUnreadOrLatest] scrolls to
 *   the first unread message (or to source index 0 if count == 0)
 *   then clears the unread set.
 * - User scrolls back to the bottom by hand → button transitions
 *   `visible == true → false`, count resets in the visibility
 *   `LaunchedEffect` below.
 *
 * `visible` is derived from [LazyListState] via `derivedStateOf` —
 * batched so the chip composable does NOT recompose on every scroll
 * pixel, only when the visible boolean flips.
 *
 * NO setSessionSuspect, NO transport touch, NO database, NO crypto —
 * pure Compose-side state.
 */
class ScrollToBottomState internal constructor() {

    /** True iff the user is scrolled away from the visual bottom. */
    var visible by mutableStateOf(false)
        internal set

    /** Count of incoming messages received WHILE the button was visible. */
    var count by mutableIntStateOf(0)
        internal set

    /**
     * Monotonic key whose change re-triggers the badge bump animation in
     * [ScrollToBottomButton]. Starts at 0; incremented on every counted
     * incoming. The button skips the bump animation when key == 0 so the
     * first paint of an empty chat doesn't fire a spurious animation.
     */
    var badgeBumpKey by mutableIntStateOf(0)
        internal set

    /**
     * Insertion-ordered set of envelope ids of incoming messages received
     * while the button was visible. The OLDEST entry is the "first
     * unread" target for the tap-to-scroll action.
     *
     * Kept package-private (not exposed in public API) so callers can't
     * accidentally mutate it; modifications happen only inside the
     * holder's own `LaunchedEffect` collector + the public reset paths.
     */
    internal val unreadIds: LinkedHashSet<String> = LinkedHashSet()

    /**
     * Animated scroll to the first unread message (or to the visual
     * bottom if `count == 0`), then clears the unread set.
     *
     * [resolveSourceIndex] converts an envelope id to its source index
     * inside the LazyColumn (e.g. via `displayItems.indexOfFirst`). If
     * the id can't be resolved (the message rolled off the in-memory
     * window, defensive), falls back to source index 0 = visual bottom.
     *
     * Per mini-lock § "Count semantics": "User taps the button while
     * `count >= 1` → animated scroll to FIRST UNREAD … 280 ms ease-out,
     * no overshoot." Compose's `LazyListState.animateScrollToItem(...)`
     * uses an internal animation spec (~300 ms with a smooth
     * non-overshooting curve). Visually equivalent to the spec; the
     * deviation is documented at the call site rather than fought with
     * a manual `listState.scroll { ... }` loop.
     */
    suspend fun scrollToFirstUnreadOrLatest(
        listState: LazyListState,
        resolveSourceIndex: (messageId: String) -> Int?,
        convTag: String,
    ) {
        val firstUnread = unreadIds.firstOrNull()
        val targetIndex = firstUnread
            ?.let(resolveSourceIndex)
            ?.takeIf { it >= 0 }
            ?: 0
        val countSnapshot = count
        val targetLabel = if (firstUnread != null) "first_unread" else "latest"
        Log.i(
            "PhantomUI",
            "CHAT_CHIP tap conv=$convTag count=$countSnapshot target=$targetLabel",
        )
        listState.animateScrollToItem(index = targetIndex)
        unreadIds.clear()
        count = 0
        Log.i("PhantomUI", "CHAT_CHIP hidden conv=$convTag reason=tap")
    }

    /**
     * Resets the count + scrolls to visual bottom. Called by ChatScreen
     * from the text-send (`onSend`) and voice-send (`onSendVoiceTap`)
     * code paths.
     *
     * Mini-lock § "Count semantics" line 126: "User sends own text/voice
     * while `scrolledUp == true` → Auto-scroll to bottom + count = 0.
     * Vladislav-locked from design handoff: 'User's own send is an
     * explicit signal they want to be at the latest. Matches Telegram
     * and Signal; deviating would feel broken.'"
     *
     * Idempotent when already at bottom — `animateScrollToItem(0)` is a
     * no-op when `firstVisibleItemIndex == 0 && offset == 0`.
     */
    fun onOwnSend(
        scope: CoroutineScope,
        listState: LazyListState,
        convTag: String,
    ) {
        unreadIds.clear()
        count = 0
        Log.i("PhantomUI", "CHAT_CHIP hidden conv=$convTag reason=user_send")
        scope.launch {
            listState.animateScrollToItem(index = 0)
        }
    }
}

/**
 * Factory + wire-up. Returns a [ScrollToBottomState] keyed to
 * [conversationId] (so re-opening the same chat resets the state, and
 * navigating to a different chat creates an independent instance).
 *
 * Internally launches:
 *
 * 1. A `LaunchedEffect` on the derived `visible` boolean that:
 *    - On `false → true` (scrolled away from bottom): logs
 *      `CHAT_CHIP visible reason=scroll_up`.
 *    - On `true → false` (user manually scrolled back to bottom): logs
 *      `CHAT_CHIP hidden reason=at_bottom` and clears `count` +
 *      `unreadIds` per mini-lock § "Count semantics" line 129.
 *
 * 2. A `LaunchedEffect` on [incomingFlow] that filters by
 *    [conversationId], skips messages whose plaintext starts with
 *    [profileMsgPrefix] (invisible profile-card payloads that ChatScreen
 *    handles silently), and — only when `visible == true` —
 *    increments `count`, `badgeBumpKey`, and adds the envelope id to
 *    `unreadIds`. Logs `CHAT_CHIP incoming count=<n>` on every counted
 *    arrival.
 *
 * Note: this collector is a SECOND subscriber on
 * `messagingService.incomingMessages`. ChatScreen already runs its own
 * collector for the live-receive UI (insertMessage / markRead / reload).
 * `incomingMessages` is a `MutableSharedFlow` with `replay = 0` and
 * `extraBufferCapacity = 64` (verified in `DefaultMessagingService`),
 * so a second subscriber receives the same emissions without back-
 * pressure interaction with the first. The two collectors are
 * independent of each other.
 */
@Composable
fun rememberScrollToBottomState(
    listState: LazyListState,
    incomingFlow: Flow<IncomingMessage>,
    conversationId: String,
    profileMsgPrefix: String,
    scope: CoroutineScope,
): ScrollToBottomState {
    val state = remember(conversationId) { ScrollToBottomState() }
    val convTag = remember(conversationId) { conversationId.take(8) }

    // Derived visibility — batched recomposition trigger. Without
    // derivedStateOf the host would recompose on every scroll pixel.
    val derivedVisible by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > 0
        }
    }

    // Sync the public `visible` field with the derived value + log
    // visibility transitions + reset count on `true → false` (per
    // mini-lock line 129).
    LaunchedEffect(derivedVisible, conversationId) {
        if (state.visible == derivedVisible) return@LaunchedEffect
        state.visible = derivedVisible
        if (derivedVisible) {
            Log.i("PhantomUI", "CHAT_CHIP visible conv=$convTag reason=scroll_up")
        } else {
            Log.i("PhantomUI", "CHAT_CHIP hidden conv=$convTag reason=at_bottom")
            // User scrolled back to bottom by hand — clear unread set.
            state.unreadIds.clear()
            state.count = 0
        }
    }

    // Incoming collector — increments count only when visible == true.
    LaunchedEffect(incomingFlow, conversationId) {
        incomingFlow
            .catch { e ->
                Log.e(
                    "PhantomUI",
                    "CHAT_CHIP incoming flow error conv=$convTag: ${e.message}",
                    e,
                )
            }
            .collect { incoming ->
                if (incoming.conversationId != conversationId) return@collect
                if (incoming.text.startsWith(profileMsgPrefix)) return@collect
                // Mini-lock § "Count semantics" line 125: incoming while
                // visible == false is silently ignored (user already at
                // bottom, sees it via natural LazyColumn recomposition).
                if (!state.visible) return@collect
                val added = state.unreadIds.add(incoming.id)
                if (!added) return@collect  // defensive: duplicate envelope id
                state.count = state.unreadIds.size
                state.badgeBumpKey = state.badgeBumpKey + 1
                Log.i(
                    "PhantomUI",
                    "CHAT_CHIP incoming conv=$convTag count=${state.count}",
                )
            }
    }

    return state
}
