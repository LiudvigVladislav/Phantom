// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * residual N1 Revision 4 / owner decision A1 — the composer keeps the user's
 * message when a send fails.
 *
 * Before Revision 4, `ChatScreen` cleared the text and the reply target the
 * instant the user tapped send, then ignored the returned `Result`. Revision 3
 * gave `sendMessage` a reason to fail before writing any row: an outbox that
 * cannot be settled must not let a new message take a ratchet index. On that
 * path the message existed nowhere — not on screen, not in the database, not
 * on the wire.
 *
 * What this file proves is the decision, not the screen. It pins the rules
 * `ChatScreen` calls: when a tap starts a send, and what the composer holds
 * once that send settles. Whether Compose renders it that way is a device
 * question and is not claimed here; the Mac validation phase owns that.
 */
class ComposerSendStateTest {

    private fun composer(text: String, replyToId: String? = null) = ComposerState(text, replyToId)

    // ── when a tap starts a send ───────────────────────────────────────────

    @Test
    fun a_tap_with_text_starts_a_send_for_exactly_what_was_typed() {
        val decision = composerSendDecision(composer("  hello  ", "m-7"), sendInFlight = false)
        val send = assertIs<ComposerSendDecision.Send>(decision)
        assertEquals("hello", send.captured.text, "the captured text is trimmed, as the sent text is")
        assertEquals("m-7", send.captured.replyToId)
    }

    @Test
    fun an_empty_composer_sends_nothing() {
        assertIs<ComposerSendDecision.Ignore>(composerSendDecision(composer(""), sendInFlight = false))
        assertIs<ComposerSendDecision.Ignore>(composerSendDecision(composer("   "), sendInFlight = false))
        assertIs<ComposerSendDecision.Ignore>(composerSendDecision(composer("\n\t "), sendInFlight = false))
    }

    /** U04 — a second tap while the first send is running must not enqueue a second message. */
    @Test
    fun a_second_tap_while_a_send_is_in_flight_is_ignored() {
        val decision = composerSendDecision(composer("hello"), sendInFlight = true)
        val ignored = assertIs<ComposerSendDecision.Ignore>(decision)
        assertEquals("send_already_in_flight", ignored.reason)
    }

    @Test
    fun the_in_flight_guard_is_what_stops_the_second_tap_and_not_the_text() {
        // Control: identical composer, guard down, really would send. Without
        // this the test above could pass for the wrong reason.
        assertIs<ComposerSendDecision.Send>(composerSendDecision(composer("hello"), sendInFlight = false))
        assertIs<ComposerSendDecision.Ignore>(composerSendDecision(composer("hello"), sendInFlight = true))
    }

    // ── U01: a failed send keeps everything ────────────────────────────────

    @Test
    fun a_failed_send_keeps_the_text_and_the_reply_target() {
        val captured = composer("hello", "m-7")
        val after = composerAfterSend(current = composer("hello", "m-7"), captured = captured, succeeded = false)
        assertEquals("hello", after.text, "the user's message is still there to retry")
        assertEquals("m-7", after.replyToId, "and it is still a reply to the same message")
    }

    @Test
    fun a_failed_send_keeps_what_the_user_typed_while_it_was_in_flight() {
        // Restoring the captured text here would silently discard the newer one.
        val after = composerAfterSend(
            current = composer("hello, and more", "m-9"),
            captured = composer("hello", "m-7"),
            succeeded = false,
        )
        assertEquals("hello, and more", after.text)
        assertEquals("m-9", after.replyToId)
    }

    // ── U02: a successful send clears only what was sent ───────────────────

    @Test
    fun a_successful_send_clears_the_text_and_the_reply_it_sent() {
        val captured = composer("hello", "m-7")
        val after = composerAfterSend(current = composer("hello", "m-7"), captured = captured, succeeded = true)
        assertEquals("", after.text)
        assertNull(after.replyToId)
    }

    // ── U03: typing during the send is never lost ──────────────────────────

    @Test
    fun a_successful_send_does_not_clear_a_message_the_user_started_meanwhile() {
        val after = composerAfterSend(
            current = composer("a new thought", null),
            captured = composer("hello", null),
            succeeded = true,
        )
        assertEquals("a new thought", after.text, "the next message survives the previous one being sent")
    }

    @Test
    fun a_reply_target_chosen_during_the_send_survives_it() {
        val after = composerAfterSend(
            current = composer("hello", "m-99"),
            captured = composer("hello", "m-7"),
            succeeded = true,
        )
        assertEquals("", after.text, "the text that was sent is cleared")
        assertEquals("m-99", after.replyToId, "the reply the user picked meanwhile is not")
    }

    @Test
    fun the_text_and_the_reply_are_judged_separately() {
        val after = composerAfterSend(
            current = composer("something else", "m-7"),
            captured = composer("hello", "m-7"),
            succeeded = true,
        )
        assertEquals("something else", after.text, "changed, so kept")
        assertNull(after.replyToId, "unchanged, so cleared")
    }

    @Test
    fun clearing_a_reply_during_the_send_is_respected() {
        val after = composerAfterSend(
            current = composer("hello", null),
            captured = composer("hello", "m-7"),
            succeeded = true,
        )
        assertNull(after.replyToId, "the user already dropped it; nothing to restore")
    }

    // ── controls ───────────────────────────────────────────────────────────

    /**
     * The rule must be "clear only what is unchanged", not "clear on success".
     * A regression to unconditional clearing passes every success test above
     * except these two.
     */
    @Test
    fun control_unconditional_clearing_is_distinguishable() {
        val current = composer("a new thought", "m-99")
        val captured = composer("hello", "m-7")
        val after = composerAfterSend(current, captured, succeeded = true)
        assertTrue(after.text.isNotEmpty(), "unconditional clearing would empty this")
        assertEquals("m-99", after.replyToId, "unconditional clearing would null this")
    }

    /** And failure must not be a disguised clear-and-restore. */
    @Test
    fun control_failure_is_identity_on_the_current_state() {
        for (current in listOf(composer("x"), composer("", "m-1"), composer("y", "m-2"))) {
            assertEquals(
                current, composerAfterSend(current, captured = composer("hello", "m-7"), succeeded = false),
                "a failed send changes nothing at all",
            )
        }
    }
}
