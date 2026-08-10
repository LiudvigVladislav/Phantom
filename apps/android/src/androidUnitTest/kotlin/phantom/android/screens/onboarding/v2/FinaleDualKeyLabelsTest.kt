// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.android.screens.onboarding.v2.steps.FinaleConfirmationStepV2
import phantom.android.ui.designv2.formatFullKeyForDisplay
import phantom.android.ui.designv2.formatShortKeyIdForDisplay

/**
 * Dual-key labels track 2026-08-10 — Phase 2 focused UI tests for
 * `FinaleConfirmationStepV2`.
 *
 * Contract sheet:
 * `docs/tracks/android-onboarding/dual-key-labels-contract.md`.
 *
 * Architect refinement 2026-08-10 pinned by this suite:
 *   - Two exact label ↔ full-hex pairs (Ed25519 signing + X25519
 *     encryption).
 *   - Two distinct short IDs computed from each key's hex.
 *   - Each Copy button copies the FULL correct key to the clipboard.
 *   - The word `fingerprint` MUST NOT appear anywhere in the
 *     composable's visible text (removed with the formatter
 *     rename to `formatShortKeyIdForDisplay`).
 *   - Continue disabled when EITHER hex is null OR malformed;
 *     enabled ONLY when BOTH hexes pass the validators (defence-
 *     in-depth at UI boundary).
 *   - Shared formatters (`formatFullKeyForDisplay`,
 *     `formatShortKeyIdForDisplay`) live in the neutral design-v2
 *     package — Finale and Profile use the SAME formatter for the
 *     SAME key, guaranteeing visual match across screens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FinaleDualKeyLabelsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // Deterministic hex fixtures — visually distinguishable across
    // cards. All-a's for Ed25519, all-b's for X25519 (both 64
    // chars, valid).
    private val signHex = "a".repeat(64)
    private val encHex  = "b".repeat(64)

    // ── §6.8 — both cards render with exact labels + full hexes ──

    @Test
    fun finale_renders_both_key_cards_with_exact_labels_and_full_hexes() {
        composeTestRule.setContent {
            FinaleConfirmationStepV2(
                signingPublicKeyHex = signHex,
                publicKeyHex        = encHex,
                onContinueClick = {},
            )
        }
        composeTestRule.waitForIdle()

        // Compose text-matcher finds nodes anywhere in the
        // scroll region; `performScrollTo` brings a node into
        // view before the `assertIsDisplayed` check. The two
        // cards + chips + copy buttons + warning banner extend
        // past a first-frame viewport; scroll-then-assert is
        // the pattern used by the existing scrollable-CTA
        // reachability suite.

        // Card 1 — Ed25519 signing key.
        composeTestRule.onNodeWithText("IDENTITY SIGNING KEY")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("ED25519 · CREATED")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(formatFullKeyForDisplay(signHex))
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Ed25519 short ID")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(formatShortKeyIdForDisplay(signHex))
            .performScrollTo().assertIsDisplayed()

        // Card 2 — X25519 encryption key.
        composeTestRule.onNodeWithText("MESSAGING ENCRYPTION KEY")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("X25519 · CREATED")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(formatFullKeyForDisplay(encHex))
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("X25519 short ID")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(formatShortKeyIdForDisplay(encHex))
            .performScrollTo().assertIsDisplayed()

        // Both short IDs are DISTINCT — proves the formatter is
        // called per-key, not once with a shared value.
        assertTrue(
            actual = formatShortKeyIdForDisplay(signHex) !=
                formatShortKeyIdForDisplay(encHex),
            message = "Short IDs of two distinct keys MUST differ. Regression that " +
                "computes one short ID and reuses it across both chips would fail here.",
        )
    }

    // ── §6.9 + architect pin — each Copy button copies FULL correct hex ──

    @Test
    fun copy_ed25519_button_copies_full_signing_hex() {
        // `onKeyCopied(hex)` fires with the copied hex value —
        // wired through the `copyFullHexToClipboard` call in the
        // production Copy onClick lambda. Testing via the
        // composable's own callback (rather than reaching into
        // Robolectric's shadowed `ClipboardManager`) is more
        // robust: it pins the semantic invariant "the button
        // labelled Ed25519 copies the Ed25519 hex" without
        // depending on Robolectric shadow behaviour.
        var copiedHex: String? = null
        composeTestRule.setContent {
            FinaleConfirmationStepV2(
                signingPublicKeyHex = signHex,
                publicKeyHex        = encHex,
                onContinueClick = {},
                onKeyCopied = { copiedHex = it },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Copy Ed25519 signing key")
            .performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            expected = signHex,
            actual = copiedHex,
            message = "Copy Ed25519 signing key MUST copy the full 64-char " +
                "signing hex (NOT the formatted display, NOT the short ID, " +
                "NOT the other key). Got: $copiedHex",
        )
    }

    @Test
    fun copy_x25519_button_copies_full_encryption_hex() {
        var copiedHex: String? = null
        composeTestRule.setContent {
            FinaleConfirmationStepV2(
                signingPublicKeyHex = signHex,
                publicKeyHex        = encHex,
                onContinueClick = {},
                onKeyCopied = { copiedHex = it },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Copy X25519 encryption key")
            .performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            expected = encHex,
            actual = copiedHex,
            message = "Copy X25519 encryption key MUST copy the full 64-char " +
                "encryption hex (NOT the signing hex). A regression that " +
                "swapped `signingPublicKeyHex` and `publicKeyHex` in the two " +
                "Copy button onClick lambdas would fail here. Got: $copiedHex",
        )
    }

    // ── §6.10 + architect pin — no `fingerprint` in visible text ──

    @Test
    fun finale_visible_text_contains_no_fingerprint_word() {
        composeTestRule.setContent {
            FinaleConfirmationStepV2(
                signingPublicKeyHex = signHex,
                publicKeyHex        = encHex,
                onContinueClick = {},
            )
        }
        composeTestRule.waitForIdle()

        // Dual-key labels track 2026-08-10 architect pin: word
        // "fingerprint" is factually inaccurate for either the
        // full-hex chunked display or the first-4…last-4 short
        // form. The Ed25519/X25519 "short ID" language replaced
        // "fingerprint". A regression that re-added the old
        // "fingerprint · short form" chip label would surface
        // here as a non-zero count.
        composeTestRule.onAllNodesWithText(
            "fingerprint", substring = true, ignoreCase = true,
        ).assertCountEquals(0)
    }

    // ── Architect pin — Continue disabled on null/malformed either key ──

    @Test
    fun continue_disabled_when_signing_hex_is_null() {
        composeTestRule.setContent {
            FinaleConfirmationStepV2(
                signingPublicKeyHex = null,
                publicKeyHex        = encHex,
                onContinueClick = {},
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun continue_disabled_when_encryption_hex_is_null() {
        composeTestRule.setContent {
            FinaleConfirmationStepV2(
                signingPublicKeyHex = signHex,
                publicKeyHex        = null,
                onContinueClick = {},
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun continue_disabled_when_signing_hex_is_malformed() {
        // 63-char hex (off-by-one truncation) — non-null but
        // fails `isValidEd25519PublicKeyHex`. Defence-in-depth
        // at UI boundary: the state-model validators already
        // reject upstream, but the composable MUST NOT trust
        // its inputs blindly.
        composeTestRule.setContent {
            FinaleConfirmationStepV2(
                signingPublicKeyHex = "a".repeat(63),
                publicKeyHex        = encHex,
                onContinueClick = {},
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun continue_disabled_when_encryption_hex_is_malformed() {
        composeTestRule.setContent {
            FinaleConfirmationStepV2(
                signingPublicKeyHex = signHex,
                publicKeyHex        = "z" + "b".repeat(63),  // 64 chars but non-hex
                onContinueClick = {},
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun continue_enabled_only_when_both_hexes_valid() {
        var advanced = false
        composeTestRule.setContent {
            FinaleConfirmationStepV2(
                signingPublicKeyHex = signHex,
                publicKeyHex        = encHex,
                onContinueClick = { advanced = true },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Continue")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertTrue(
            actual = advanced,
            message = "Continue MUST fire onContinueClick when BOTH hexes are valid.",
        )
    }

    // ── Architect pin — shared formatter produces same output regardless of screen ──

    @Test
    fun shared_formatter_produces_identical_result_for_same_hex_across_screens() {
        // The shared formatters live in the neutral design-v2
        // package, so ANY caller (Finale, Profile, future
        // Contact-share dialog) gets byte-identical output for
        // the same key hex. This is what guarantees the "values
        // must visually match across Finale and Profile"
        // architect §2 requirement — without a single shared
        // formatter, two screens could accidentally chunk the
        // same key differently.
        val hex = "1234" + "fedcba9876543210".repeat(3) + "1234abcdef56"
        require(hex.length == 64)
        // Two independent calls (simulating Finale-side call +
        // Profile-side call) — MUST produce byte-identical output.
        val fromFinaleContext = formatFullKeyForDisplay(hex)
        val fromProfileContext = formatFullKeyForDisplay(hex)
        assertEquals(
            expected = fromFinaleContext,
            actual = fromProfileContext,
            message = "formatFullKeyForDisplay MUST be a pure function of hex — " +
                "identical output regardless of call site (Finale, Profile, etc).",
        )
        val shortFromFinale = formatShortKeyIdForDisplay(hex)
        val shortFromProfile = formatShortKeyIdForDisplay(hex)
        assertEquals(
            expected = shortFromFinale,
            actual = shortFromProfile,
            message = "formatShortKeyIdForDisplay MUST be a pure function of hex — " +
                "identical output regardless of call site.",
        )
    }
}
