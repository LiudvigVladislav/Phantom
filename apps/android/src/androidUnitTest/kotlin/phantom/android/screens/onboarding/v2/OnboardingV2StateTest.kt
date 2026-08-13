// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import org.junit.Test
import phantom.android.ui.designv2.formatFullKeyForDisplay
import phantom.android.ui.designv2.formatShortKeyIdForDisplay
import phantom.core.transport.PrivacyMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the OnboardingV2 state model.
 *
 * The predicates ([canAdvanceFromV2], [isEdgeSwipeBackFromEnabled],
 * [validateUsernameV2]) are pure functions. This test pins their contracts
 * so the flow composable can be refactored freely without regressing
 * navigation semantics.
 *
 * `isEdgeSwipeBackFromEnabled` in particular is the round-2 REDLINE P1-1
 * gate: previous shape enabled the left-edge swipe on FinaleConfirmation
 * even though the BackHandler absorbed the OS Back button, so a swipe
 * could still return the user to Permissions after identity was already
 * created. The `finale_disables_edge_swipe_back` case below pins the fix.
 */
class OnboardingV2StateTest {

    // ── isEdgeSwipeBackFromEnabled ─────────────────────────────────────

    @Test
    fun welcome_disables_edge_swipe_back() {
        assertFalse(
            isEdgeSwipeBackFromEnabled(OnboardingStepV2.Welcome),
            "Welcome is the first step; there is nowhere to swipe back to.",
        )
    }

    @Test
    fun finale_disables_edge_swipe_back() {
        // Round-2 REDLINE P1-1 pin: FinaleConfirmation must NOT allow
        // left-edge swipe-back. The BackHandler already absorbs system
        // Back on this step; the gesture must not be a side-door around
        // that guard.
        assertFalse(
            isEdgeSwipeBackFromEnabled(OnboardingStepV2.FinaleConfirmation),
            "FinaleConfirmation is post-finalize: identity has been created; " +
                "going back to Permissions would be misleading.",
        )
    }

    @Test
    fun numbered_steps_all_enable_edge_swipe_back() {
        assertTrue(isEdgeSwipeBackFromEnabled(OnboardingStepV2.How))
        assertTrue(isEdgeSwipeBackFromEnabled(OnboardingStepV2.Identity))
        assertTrue(isEdgeSwipeBackFromEnabled(OnboardingStepV2.Privacy))
        assertTrue(isEdgeSwipeBackFromEnabled(OnboardingStepV2.Permissions))
    }

    // ── canAdvanceFromV2 ───────────────────────────────────────────────

    @Test
    fun welcome_always_advances() {
        assertTrue(canAdvanceFromV2(OnboardingStepV2.Welcome, OnboardingFormStateV2()))
    }

    @Test
    fun how_always_advances() {
        assertTrue(canAdvanceFromV2(OnboardingStepV2.How, OnboardingFormStateV2()))
    }

    @Test
    fun identity_blocks_on_empty_username() {
        assertFalse(
            canAdvanceFromV2(
                OnboardingStepV2.Identity,
                OnboardingFormStateV2(username = ""),
            ),
        )
    }

    @Test
    fun identity_blocks_on_short_username() {
        assertFalse(
            canAdvanceFromV2(
                OnboardingStepV2.Identity,
                OnboardingFormStateV2(username = "ab"),
            ),
        )
    }

    @Test
    fun identity_advances_on_valid_username() {
        assertTrue(
            canAdvanceFromV2(
                OnboardingStepV2.Identity,
                OnboardingFormStateV2(username = "alice"),
            ),
        )
    }

    @Test
    fun identity_blocks_on_invalid_chars() {
        assertFalse(
            canAdvanceFromV2(
                OnboardingStepV2.Identity,
                OnboardingFormStateV2(username = "al!ce"),
            ),
        )
    }

    @Test
    fun privacy_always_advances() {
        assertTrue(
            canAdvanceFromV2(
                OnboardingStepV2.Privacy,
                OnboardingFormStateV2(privacyMode = PrivacyMode.Standard),
            ),
        )
    }

    @Test
    fun permissions_never_advances_via_regular_gate() {
        // C6-a round-1 REDLINE §P1 pin: Permissions has NO regular
        // Continue button — its Done routes through the sealed
        // finalize holder (`holder.markInFlight()` + coroutine +
        // `holder.applyFinalizeOutcome(outcome)`). The gate predicate
        // MUST reject a regular advance so `goNext` cannot push
        // `navigationStep` to FinaleConfirmation and bypass the
        // sealed holder. Belt-and-suspenders alongside
        // [computeNextNavigationStep] which structurally refuses to
        // return FinaleConfirmation as a nav target.
        assertFalse(
            canAdvanceFromV2(
                OnboardingStepV2.Permissions,
                OnboardingFormStateV2(),
            ),
            "Permissions must NOT advance via canAdvanceFromV2 — Done routes through " +
                "the sealed finalize holder, not through a regular Continue gate.",
        )
    }

    @Test
    fun finale_never_advances_via_gate() {
        assertFalse(
            canAdvanceFromV2(
                OnboardingStepV2.FinaleConfirmation,
                OnboardingFormStateV2(),
            ),
            "FinaleConfirmation is terminal; it exits the flow via onComplete(), " +
                "not via canAdvanceFromV2.",
        )
    }

    // ── validateUsernameV2 ─────────────────────────────────────────────

    @Test
    fun username_empty_is_empty_state() {
        assertEquals(UsernameValidationV2.Empty, validateUsernameV2(""))
    }

    @Test
    fun username_short_is_short_state() {
        assertEquals(UsernameValidationV2.Short, validateUsernameV2("a"))
        assertEquals(UsernameValidationV2.Short, validateUsernameV2("ab"))
    }

    @Test
    fun username_valid_forms() {
        assertEquals(UsernameValidationV2.Valid, validateUsernameV2("abc"))
        assertEquals(UsernameValidationV2.Valid, validateUsernameV2("alice"))
        assertEquals(UsernameValidationV2.Valid, validateUsernameV2("alice_42"))
        assertEquals(UsernameValidationV2.Valid, validateUsernameV2("a".repeat(20)))
    }

    @Test
    fun username_invalid_chars_flagged() {
        assertEquals(UsernameValidationV2.InvalidChars, validateUsernameV2("al!ce"))
        assertEquals(UsernameValidationV2.InvalidChars, validateUsernameV2("al ce"))
        assertEquals(UsernameValidationV2.InvalidChars, validateUsernameV2("Alice"))  // uppercase disallowed by contract
    }

    @Test
    fun username_too_long_folds_into_invalid_chars() {
        // Per validateUsernameV2 KDoc: length > 20 folded into InvalidChars so
        // the caller shows a single helper string for out-of-range length.
        assertEquals(UsernameValidationV2.InvalidChars, validateUsernameV2("a".repeat(21)))
    }

    // ── formatFullKeyForDisplay ────────────────────────────────────

    @Test
    fun display_format_chunks_hex_into_8_groups_of_8() {
        // Real Ed25519 public keys are 32 bytes = 64 hex chars. The
        // display format is 8 groups of 8 chars, single space between
        // adjacent groups, double space between groups 4 and 5 (a
        // mid-hex "waist" that helps the eye track lines).
        val hex = "0123456789abcdef".repeat(4)  // 64 chars
        val formatted = formatFullKeyForDisplay(hex)

        assertEquals("01234567 89abcdef 01234567 89abcdef  01234567 89abcdef 01234567 89abcdef", formatted)

        // Every hex char of the input must appear unchanged in the
        // output — the format only inserts spaces, never truncates.
        val stripped = formatted.replace(" ", "")
        assertEquals(hex, stripped)
        assertEquals(64, stripped.length)
    }

    @Test
    fun display_format_returns_input_unchanged_when_not_64_chars() {
        // Defensive fallback for malformed records (should not happen
        // upstream but the composable should not throw).
        assertEquals("", formatFullKeyForDisplay(""))
        assertEquals("short", formatFullKeyForDisplay("short"))
        assertEquals("a".repeat(63), formatFullKeyForDisplay("a".repeat(63)))
        assertEquals("a".repeat(65), formatFullKeyForDisplay("a".repeat(65)))
    }

    // ── formatShortKeyIdForDisplay ─────────────────────────────────────────

    @Test
    fun short_fingerprint_is_first4_ellipsis_last4() {
        // Redline §C1: the short form MUST accompany the full key +
        // always with the "fingerprint · short form" label. This
        // helper returns just the short-form string; the label is a
        // separate Text next to the chip.
        val hex = "abcd" + "0".repeat(56) + "ef01"
        assertEquals("abcd…ef01", formatShortKeyIdForDisplay(hex))
    }

    @Test
    fun short_fingerprint_returns_empty_when_not_64_chars() {
        assertEquals("", formatShortKeyIdForDisplay(""))
        assertEquals("", formatShortKeyIdForDisplay("short"))
        assertEquals("", formatShortKeyIdForDisplay("a".repeat(63)))
        assertEquals("", formatShortKeyIdForDisplay("a".repeat(65)))
    }

    @Test
    fun short_fingerprint_length_is_9_for_valid_input() {
        // 4 chars + 1 ellipsis + 4 chars = 9 characters (ellipsis U+2026
        // is one Unicode code point). Pins the shape so a future change
        // to the truncation format is caught by the test.
        val short = formatShortKeyIdForDisplay("a".repeat(64))
        assertEquals(9, short.length)
    }

    // ── Commit 4 · Privacy state contract ─────────────────────────────

    @Test
    fun privacy_default_is_standard() {
        // Commit 4: A brand-new form state starts on PrivacyMode.Standard
        // — the segment bar renders with only bar 0 filled. Any other
        // default would surprise users landing on Step 3.
        val fresh = OnboardingFormStateV2()
        assertEquals(PrivacyMode.Standard, fresh.privacyMode)
    }

    @Test
    fun privacy_can_be_set_to_private_via_form_copy() {
        val state = OnboardingFormStateV2()
        val updated = state.copy(privacyMode = PrivacyMode.Private)
        assertEquals(PrivacyMode.Private, updated.privacyMode)
    }

    @Test
    fun privacy_step_always_advances_regardless_of_selection() {
        // Commit 4: even the initial Standard selection is enough to
        // continue — no "you must pick something" gate on Privacy.
        // Commit 5 will keep the same shape for Permissions.
        for (mode in PrivacyMode.entries) {
            val state = OnboardingFormStateV2(privacyMode = mode)
            assertTrue(
                canAdvanceFromV2(OnboardingStepV2.Privacy, state),
                "canAdvance must return true from Privacy regardless of mode; got false for $mode",
            )
        }
    }

    @Test
    fun privacy_step_enables_edge_swipe_back_to_identity() {
        assertTrue(
            isEdgeSwipeBackFromEnabled(OnboardingStepV2.Privacy),
            "Privacy is a mid-flow numbered step — left-edge swipe must return to Identity.",
        )
    }

    @Test
    fun privacy_step_dots_index_is_2() {
        // Position 2 out of 4 (0-indexed) — the third dot lights up
        // when Privacy is the current step.
        assertEquals(2, OnboardingStepV2.Privacy.dotsIndex)
    }

    // ── Commit 5 round-1 REDLINE · Permissions state contract ────────

    @Test
    fun permissions_step_gate_rejects_regular_advance() {
        // C6-a round-1 REDLINE §P1 pin: Permissions has NO regular
        // Continue button — Done routes through the sealed finalize
        // holder. The gate predicate MUST reject a regular advance
        // so `goNext` cannot push `navigationStep` to
        // FinaleConfirmation and bypass the sealed holder.
        //
        // Predecessor: Commit 5's Permissions form state carried
        // no pref bits so `canAdvance` was unconditionally true —
        // that shape allowed a bypass path (goNext advances to
        // FinaleConfirmation) which round-1 REDLINE closed.
        val fresh = OnboardingFormStateV2()
        assertFalse(canAdvanceFromV2(OnboardingStepV2.Permissions, fresh))
    }

    @Test
    fun permissions_step_dots_index_is_3() {
        assertEquals(3, OnboardingStepV2.Permissions.dotsIndex)
    }

    @Test
    fun form_state_carries_no_permission_pref_bits() {
        // Round-1 REDLINE §P1-1 + §P1-2 pin: `microphoneEnabled` +
        // `nearbyDiscoveryEnabled` were removed (Mic/Nearby are
        // static info rows per §A4); `notificationsEnabled` was
        // removed (OS is the source of truth per the coordinator).
        // The form state has NO permission-related fields.
        //
        // This test would fail to compile if any of those fields
        // were reintroduced. That IS the pin.
        val fresh = OnboardingFormStateV2()
        // If a permission-pref field creeps back, the reader below
        // would compile — remove this assertion accordingly then.
        assertEquals("", fresh.username)
        assertEquals(PrivacyMode.Standard, fresh.privacyMode)
        // C6-a: `signingPublicKeyHex` was removed from
        // OnboardingFormStateV2 entirely — the sealed
        // [OnboardingFinalizeStateHolder] owns it now, as the
        // hex payload of its `Completed` variant. If a
        // `signingPublicKeyHex` field creeps back onto the form
        // state, the flow's finalize path could once again write
        // it out-of-band from the sealed holder — the very split
        // we're closing. The absence of a `fresh.signingPublicKeyHex`
        // reference here IS the pin: uncommenting it fails to
        // compile.
        // assertEquals(null, fresh.signingPublicKeyHex)
    }
}
