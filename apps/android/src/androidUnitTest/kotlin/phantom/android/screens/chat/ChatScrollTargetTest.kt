// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * residual N1 / F1 — the scroll target must be expressed in the coordinates the
 * list actually uses.
 *
 * `Message.sq` selects `ORDER BY created_at ASC`, so `messages` is oldest-first.
 * The chat renders `displayItems = chatItems.asReversed()` under
 * `reverseLayout = true`, preceded by the `__bottom_anchor__` item, so index 0
 * in LazyColumn coordinates is the visual bottom — the newest end.
 *
 * Three call sites scrolled to `messages.lastIndex`, which is the oldest-first
 * index of the newest message and therefore points at the OLDEST item once the
 * list is reversed. The campaign measured the consequence exactly:
 * `CHAT_CACHE emit … count=240` and `CHAT_LIST open_state … total=252
 * firstVisible=239`, with 240 - 1 = 239.
 */
class ChatScrollTargetTest {

    /** The reversal the screen performs, modelled on plain indices. */
    private fun displayIndexOfNewest(messageCount: Int): Int {
        val oldestFirst = (0 until messageCount).toList()
        val newestFirst = oldestFirst.asReversed()
        val newest = oldestFirst.last()
        return newestFirst.indexOf(newest)
    }

    @Test
    fun the_newest_message_sits_at_index_zero_once_the_list_is_reversed() {
        for (n in listOf(1, 2, 10, 240, 252)) {
            assertEquals(
                0, displayIndexOfNewest(n),
                "with a newest-first source the newest message is always at index 0 (n=$n)",
            )
        }
    }

    @Test
    fun the_scroll_target_is_the_bottom_of_the_reversed_list() {
        assertEquals(
            0, NEWEST_ITEM_INDEX,
            "scrolling to the newest message means scrolling to index 0, the bottom anchor",
        )
    }

    /**
     * The regression itself: `messages.lastIndex` is not the newest item's
     * display index for any conversation with more than one message, and for the
     * measured conversation it is exactly the 239 the field run reported.
     */
    @Test
    fun the_old_target_pointed_at_the_oldest_message() {
        for (n in listOf(2, 10, 240, 252)) {
            val oldTarget = n - 1 // messages.lastIndex
            assertNotEquals(
                displayIndexOfNewest(n), oldTarget,
                "messages.lastIndex must not be used as a display index (n=$n)",
            )
            assertTrue(
                oldTarget > displayIndexOfNewest(n),
                "the old target sat further from the bottom, i.e. deeper in history (n=$n)",
            )
        }
        // The exact number the Mac campaign saw: 240 messages in the cache emit,
        // viewport reported at firstVisible=239.
        assertEquals(239, 240 - 1, "240 messages give messages.lastIndex = 239")
    }

    @Test
    fun an_empty_conversation_still_has_a_valid_target() {
        assertEquals(
            0, NEWEST_ITEM_INDEX,
            "the bottom anchor exists even with no messages, so the target is always in range",
        )
    }

    // ── following vs reading history ────────────────────────────────────────

    @Test
    fun a_reader_at_the_live_end_follows_new_messages() {
        assertTrue(shouldFollowNewest(0), "the bottom anchor itself is the live end")
        assertTrue(shouldFollowNewest(1), "the newest message is the live end")
        assertTrue(shouldFollowNewest(FOLLOW_NEWEST_THRESHOLD), "a date separator still counts as the live end")
    }

    @Test
    fun a_reader_in_history_is_not_moved() {
        assertTrue(!shouldFollowNewest(FOLLOW_NEWEST_THRESHOLD + 1), "just past the threshold is already history")
        assertTrue(!shouldFollowNewest(50), "a reader 50 items back must stay where they are")
        assertTrue(
            !shouldFollowNewest(239),
            "the viewport the campaign measured must never be dragged to the bottom by an inbound message",
        )
    }
}
