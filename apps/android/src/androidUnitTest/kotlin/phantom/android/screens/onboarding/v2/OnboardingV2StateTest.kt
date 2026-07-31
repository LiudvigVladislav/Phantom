// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import org.junit.Test
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
    fun permissions_always_advances() {
        assertTrue(
            canAdvanceFromV2(
                OnboardingStepV2.Permissions,
                OnboardingFormStateV2(notificationsEnabled = false),
            ),
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
}
