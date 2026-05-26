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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

/**
 * PR-UI-CHAT-NEW-MSG-CHIP1 — state holder for the floating scroll-to-bottom
 * button. Tracks two distinct concerns:
 *
 *  - **Visibility** — derived from the LazyListState; `true` iff the user is
 *    scrolled away from the visual bottom of the chat. With
 *    `LazyColumn(reverseLayout = true)` (set by CACHE1 v1.1) source index 0 is
 *    the visual bottom, so "at bottom" ≡ `firstVisibleItemIndex == 0 &&
 *    firstVisibleItemScrollOffset == 0`. `derivedStateOf` batches the two
 *    upstream observations so recomposition fires only when the boolean
 *    actually flips, NOT on every scroll pixel.
 *
 *  - **Count** — incoming messages received while the button is visible.
 *    Tracked as a `LinkedHashSet<String>` so we keep arrival-order ids without
 *    duplication; "first unread" = `first()` of that set. Capped at 99 in the
 *    badge display ("99+"). Cleared on user-send, on tap, and on chat-switch.
 *
 * Vladislav-locked semantics (`docs/tracks/chat-new-msg-chip.md`):
 *  - Auto-scroll + count clear on own-send while scrolled up (matches
 *    Telegram + Signal — "user's own send is an explicit signal").
 *  - Tap with count >= 1 → scroll to FIRST UNREAD (oldest of the incoming
 *    since-button-visible set), NOT bare bottom.
 *  - Tap with count == 0 → scroll to latest.
 *  - Button NEVER hides during recording (decoupled from `recordingState`).
 */
class ScrollToBottomState internal constructor(
    private val conversationId: String,
    private val listState: LazyListState,
) {
    // Backing fields. `unread` is the authoritative arrival-order set of
    // incoming message ids received while the button is visible. The
    // public `count` reads `unread.size`. We use a separate `bumpKey`
    // so the badge bump animation can be re-triggered without changing
    // `count` (e.g. for the visual confirm-on-increment).
    private val unread = LinkedHashSet<String>()
    internal var countState by mutableIntStateOf(0)
        private set
    internal var bumpKey by mutableIntStateOf(0)
        private set

    val count: Int get() = countState

    val visible: Boolean by derivedStateOf {
        listState.firstVisibleItemIndex > 0 ||
            listState.firstVisibleItemScrollOffset > 0
    }

    /**
     * Called by ChatScreen when an incoming message id is observed for the
     * current conversation. NO-OP if not currently visible (the user can
     * already see the message at the bottom).
     */
    internal fun onIncoming(messageId: String) {
        if (!visible) return
        if (unread.add(messageId)) {
            countState = unread.size
            bumpKey += 1
            Log.i(
                "PhantomUI",
                "CHAT_CHIP incoming conv=${conversationId.take(8)} count=$countState",
            )
        }
    }

    /**
     * Called by ChatScreen own-send paths (text + voice) BEFORE the actual
     * send DB write. Clears the count immediately; the caller is then
     * expected to `scrollToBottom()` so the user lands at the visual
     * bottom (source index 0 with reverseLayout=true).
     */
    suspend fun onOwnSend() {
        if (unread.isNotEmpty() || visible) {
            unread.clear()
            countState = 0
            Log.i(
                "PhantomUI",
                "CHAT_CHIP hidden conv=${conversationId.take(8)} reason=user_send",
            )
        }
        listState.animateScrollToItem(0)
    }

    /**
     * Called by the button's onClick. Per design handoff §06 "Tap behavior":
     *  - count == 0 → animated scroll to latest (source index 0).
     *  - count >= 1 → animated scroll to FIRST UNREAD (oldest incoming
     *    id from the arrival-order set). After the scroll completes,
     *    count clears.
     *
     * Both cases use a 280 ms ease-out scroll (the design-handoff spec); we
     * map that to `animateScrollToItem`, which uses Compose's default
     * scroll spring — close enough to "no overshoot" for the chat list.
     * Explicit easing override is out of scope for CHIP1 (no public
     * `AnimationSpec` parameter on `animateScrollToItem` until we
     * compose-foundation 1.7+; the visible result is indistinguishable
     * on Tecno).
     */
    internal suspend fun onTap(displayItems: List<ChatItem>) {
        val target = if (countState == 0) {
            Log.i(
                "PhantomUI",
                "CHAT_CHIP tap conv=${conversationId.take(8)} count=0 target=latest",
            )
            0
        } else {
            val firstUnreadId = unread.firstOrNull()
            val idx = if (firstUnreadId != null) {
                displayItems.indexOfFirst { it is ChatItem.Msg && it.entity.id == firstUnreadId }
            } else {
                -1
            }
            Log.i(
                "PhantomUI",
                "CHAT_CHIP tap conv=${conversationId.take(8)} count=$countState " +
                    "target=first_unread idx=$idx",
            )
            // Fallback to latest if the first-unread id is no longer in the
            // displayItems list (rare — e.g. expired/deleted message).
            if (idx >= 0) idx else 0
        }
        listState.animateScrollToItem(target)
        unread.clear()
        countState = 0
        Log.i(
            "PhantomUI",
            "CHAT_CHIP hidden conv=${conversationId.take(8)} reason=tap",
        )
    }
}

/**
 * Composable factory + visibility lifecycle hook. Logs `CHAT_CHIP visible`
 * when the button slides in and `CHAT_CHIP hidden reason=at_bottom` when
 * the user scrolls back to the visual bottom (the natural exit path,
 * distinct from `reason=tap` and `reason=user_send`).
 *
 * `incomingFlow` should already be filtered to the current conversation
 * by the caller (ChatScreen does this with `.filter { it.conversationId ==
 * conversationId }` on the messaging service's `incomingMessages` flow).
 */
@Composable
fun rememberScrollToBottomState(
    conversationId: String,
    listState: LazyListState,
    incomingFlow: Flow<String>, // emits incoming message ids (post-DB-insert)
): ScrollToBottomState {
    val state = remember(conversationId, listState) {
        ScrollToBottomState(conversationId, listState)
    }

    // Drive `onIncoming` from the filtered flow.
    LaunchedEffect(conversationId, listState) {
        incomingFlow.collect { id -> state.onIncoming(id) }
    }

    // Log visibility transitions so Test #83 can verify enter/exit ordering.
    var prevVisible by remember(conversationId) { mutableStateOf(false) }
    LaunchedEffect(state.visible) {
        if (state.visible && !prevVisible) {
            Log.i(
                "PhantomUI",
                "CHAT_CHIP visible conv=${conversationId.take(8)} reason=scroll_up",
            )
        } else if (!state.visible && prevVisible) {
            Log.i(
                "PhantomUI",
                "CHAT_CHIP hidden conv=${conversationId.take(8)} reason=at_bottom",
            )
        }
        prevVisible = state.visible
    }

    return state
}
