// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import kotlin.test.assertEquals
import org.junit.Test
import phantom.android.screens.onboarding.v2.steps.KEY_PREVIEW_GLYPH_COUNT
import phantom.android.screens.onboarding.v2.steps.KEY_PREVIEW_TARGET_CHARS
import phantom.android.screens.onboarding.v2.steps.KeyPreviewAnimationPhase
import phantom.android.screens.onboarding.v2.steps.nextKeyPreviewPhase
import phantom.android.screens.onboarding.v2.steps.renderShuffleGlyphs
import phantom.android.screens.onboarding.v2.steps.settledCountFor

/**
 * C6-b — pure JVM state + rendering-helper tests for the animated
 * key-preview card. Contract sheet
 * `docs/tracks/android-onboarding/c6-b-key-preview-animation.md`
 * §6.1-6.4 + §4.
 *
 * These four tests pin the reducer transitions the Compose composable
 * relies on and prove the deterministic-target invariant that keeps
 * Paparazzi frames byte-stable.
 */
class KeyPreviewAnimationPhaseTest {

    // ── Reducer transitions (contract §6.1-6.4) ──────────────────

    @Test
    fun phase_stays_Idle_when_username_invalid() {
        // No matter what the caller reports as "progress", if
        // usernameValid is false the phase MUST be Idle. This is
        // the load-bearing invariant behind the cancellation-mid-
        // animation contract (§5 row 4).
        assertEquals(
            KeyPreviewAnimationPhase.Idle,
            nextKeyPreviewPhase(
                current = KeyPreviewAnimationPhase.Idle,
                usernameValid = false,
                progressReachedOne = false,
            ),
        )
        assertEquals(
            KeyPreviewAnimationPhase.Idle,
            nextKeyPreviewPhase(
                current = KeyPreviewAnimationPhase.Running,
                usernameValid = false,
                progressReachedOne = false,
            ),
            "Running + username invalid MUST transition to Idle (cancellation)",
        )
        assertEquals(
            KeyPreviewAnimationPhase.Idle,
            nextKeyPreviewPhase(
                current = KeyPreviewAnimationPhase.Terminal,
                usernameValid = false,
                progressReachedOne = true,
            ),
            "Terminal + username flipped invalid MUST transition to Idle",
        )
    }

    @Test
    fun phase_transitions_Idle_to_Running_on_username_becoming_valid() {
        // Idle + usernameValid = true + progress not yet 1f
        // → Running. This is the trigger point.
        assertEquals(
            KeyPreviewAnimationPhase.Running,
            nextKeyPreviewPhase(
                current = KeyPreviewAnimationPhase.Idle,
                usernameValid = true,
                progressReachedOne = false,
            ),
        )
    }

    @Test
    fun phase_transitions_Running_to_Idle_on_username_becoming_invalid_mid_run() {
        // Explicit second-line pin for the cancellation-mid-flight
        // path — Running + usernameValid = false → Idle.
        assertEquals(
            KeyPreviewAnimationPhase.Idle,
            nextKeyPreviewPhase(
                current = KeyPreviewAnimationPhase.Running,
                usernameValid = false,
                progressReachedOne = false,
            ),
        )
    }

    @Test
    fun phase_reaches_Terminal_once_and_stays_Terminal_across_repeated_valid_flips() {
        // Terminal MUST be absorbing under usernameValid = true.
        // Contract §2.3: after a completed shuffle, subsequent
        // invalid→valid flips do NOT restart. The reducer's
        // `current == Terminal` short-circuit enforces this.
        //
        // The composable pairs this with `Animatable.value >= 1f`
        // → no `animateTo(1f)` re-fires (LaunchedEffect no-ops).
        var phase = KeyPreviewAnimationPhase.Idle

        phase = nextKeyPreviewPhase(phase, usernameValid = true, progressReachedOne = false)
        assertEquals(KeyPreviewAnimationPhase.Running, phase)

        phase = nextKeyPreviewPhase(phase, usernameValid = true, progressReachedOne = true)
        assertEquals(KeyPreviewAnimationPhase.Terminal, phase)

        // Flip invalid → Idle (cancellation).
        phase = nextKeyPreviewPhase(phase, usernameValid = false, progressReachedOne = false)
        assertEquals(KeyPreviewAnimationPhase.Idle, phase)

        // Flip back valid. In the reducer's view, current is Idle
        // (not Terminal), so it transitions to Running — that is
        // correct: the user typed something new and the composable
        // fires a fresh shuffle. Composable-level guarantee (that
        // the visible state stays Terminal after a completed
        // shuffle) is enforced by the Animatable's persistent
        // 1f-progress, not by this reducer.
        phase = nextKeyPreviewPhase(phase, usernameValid = true, progressReachedOne = false)
        assertEquals(KeyPreviewAnimationPhase.Running, phase)

        // Immediately reaching 1f (composable would trigger this
        // via the Animatable already sitting at 1f) → Terminal.
        phase = nextKeyPreviewPhase(phase, usernameValid = true, progressReachedOne = true)
        assertEquals(KeyPreviewAnimationPhase.Terminal, phase)

        // From Terminal, staying valid with no fresh progress
        // change stays Terminal — absorbing.
        phase = nextKeyPreviewPhase(phase, usernameValid = true, progressReachedOne = false)
        assertEquals(
            KeyPreviewAnimationPhase.Terminal,
            phase,
            "Terminal MUST be absorbing under usernameValid = true — contract §2.3.",
        )
    }

    // ── Shuffle-helper invariants (contract §4.2 non-crypto guarantee) ──

    @Test
    fun target_is_exactly_32_terminal_bullets() {
        // Contract §2.1 (UX correction 2026-08-10): 32 masked
        // bullets `•`, all identical (not per-position seeded hex).
        // Precondition for the "cannot be mistaken for a real
        // key" architect-flagged UX rule + Paparazzi frame
        // byte-stability.
        assertEquals(
            KEY_PREVIEW_GLYPH_COUNT,
            KEY_PREVIEW_TARGET_CHARS.size,
            "Target MUST contain exactly ${KEY_PREVIEW_GLYPH_COUNT} slots.",
        )
        val allTerminalBullet = KEY_PREVIEW_TARGET_CHARS.all { it == '•' }
        check(allTerminalBullet) {
            "All target chars MUST be terminal bullet `•` (masked pattern); " +
                "got ${KEY_PREVIEW_TARGET_CHARS.joinToString("")}"
        }
        val anyHex = KEY_PREVIEW_TARGET_CHARS.any { it in '0'..'9' || it in 'A'..'F' }
        check(!anyHex) {
            "Target MUST NOT contain any hex characters — architect UX rule " +
                "(post-LOGICAL-GREEN device review 2026-08-10): the terminal " +
                "state must be visually indistinguishable from a masked " +
                "password field, not a real hex key. Got: " +
                KEY_PREVIEW_TARGET_CHARS.joinToString("")
        }
    }

    @Test
    fun settledCountFor_maps_progress_to_discrete_32_slots() {
        // Progress = 0 → 0 settled. Progress = 0.5 → 16 settled.
        // Progress = 1 → 32 settled. Guards against off-by-one
        // in the frame render helper (used by all 3 Paparazzi
        // frames).
        assertEquals(0,  settledCountFor(0f),      "progress 0 → 0 settled")
        assertEquals(16, settledCountFor(0.5f),    "progress 0.5 → 16 settled")
        assertEquals(32, settledCountFor(1f),      "progress 1 → 32 settled")
        assertEquals(0,  settledCountFor(-0.5f),   "progress <0 clamps to 0")
        assertEquals(32, settledCountFor(2f),      "progress >1 clamps to 32")
    }

    @Test
    fun renderShuffleGlyphs_at_progress_1_shows_only_terminal_bullets_and_never_hex() {
        // Contract §5 Terminal-state row (UX correction 2026-08-10):
        // at progress 1f, every glyph position shows terminal `•`.
        // Layout separators (spaces + `·` + newline) are always
        // present at the same offsets. The rendered string MUST
        // NOT contain any hex character — architect on-device UX
        // rule that the terminal state cannot be mistaken for a
        // real key.
        val rendered = renderShuffleGlyphs(progress = 1f)
        // 32 bullet-slot chars (excluding separators).
        val slotChars = rendered.filter { it != ' ' && it != '\n' && it != '·' }
        assertEquals(
            KEY_PREVIEW_TARGET_CHARS.joinToString(""),
            slotChars,
            "At progress 1f, all 32 slot positions MUST render terminal `•` bullets in order.",
        )
        val anyHex = rendered.any { it in '0'..'9' || it in 'A'..'F' }
        check(!anyHex) {
            "Rendered body MUST NOT contain any hex character at any progress " +
                "— UX rule: masked visual only. Got: '$rendered'"
        }
        // Layout: 21 chars per line × 2 lines + 1 newline.
        assertEquals(
            43,
            rendered.length,
            "Rendered body MUST be 43 chars (21 per line + 1 newline) at all progress values — no layout shift.",
        )
        assertEquals(
            1,
            rendered.count { it == '\n' },
            "Rendered body MUST contain exactly one newline.",
        )
    }
}
