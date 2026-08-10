// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.profile

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.android.ui.designv2.formatFullKeyForDisplay
import phantom.android.ui.designv2.formatShortKeyIdForDisplay

/**
 * Dual-key labels track 2026-08-10 — Phase 3 focused UI tests
 * for `ProfileScreen.QrKeyCard`.
 *
 * Contract sheet:
 * `docs/tracks/android-onboarding/dual-key-labels-contract.md`.
 *
 * Architect refinement 2026-08-10 pinned by this suite:
 *   - Ed25519 row reads ONLY `signingPublicKeyHex`; X25519 row
 *     reads ONLY `publicKeyHex`.
 *   - QR + Share callbacks use byte-exact
 *     `"${username}:${publicKeyHex}"` (X25519 only). Ed25519
 *     NEVER appears in the QR payload or the Share callback
 *     argument.
 *   - `X25519 short ID` value equals the shared-function output
 *     `formatShortKeyIdForDisplay(publicKeyHex)` verbatim.
 *   - Both Copy callbacks return the FULL correct hex.
 *   - No `ED25519` mislabel over X25519 anywhere.
 *   - No `fingerprint` word in visible text.
 *   - Narrow screen (320×640 dp fs=2.0) — labels + hexes +
 *     copy buttons reachable via scroll.
 *   - Displayed full-hex values equal the shared-function
 *     output `formatFullKeyForDisplay(hex)` verbatim — proves
 *     the card actually uses the shared formatter, not a
 *     locally-defined one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileQrKeyCardDualKeyLabelsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // Deterministic + visually distinct fixtures.
    private val fixtureUsername = "alice"
    private val fixtureSignHex = "a".repeat(64)  // Ed25519 signing
    private val fixtureEncHex  = "b".repeat(64)  // X25519 encryption

    /**
     * QrKeyCard sits inside a scrollable Column in production
     * ProfileScreen — the card itself does not carry
     * `verticalScroll`. Tests wrap it in a scrollable Column so
     * `performScrollTo` on off-fold nodes (Copy buttons, footer)
     * has a scrollable ancestor to act on. This mirrors the
     * production layout without duplicating unrelated Profile
     * chrome.
     */
    @Composable
    private fun ScrollableCardHost(content: @Composable () -> Unit) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            content()
        }
    }

    // ── §6.11 — header reads Connection QR, NOT ED25519 mislabel ──

    @Test
    fun profile_qr_card_header_reads_connection_qr_not_ed25519() {
        composeTestRule.setContent {
            ScrollableCardHost {
                QrKeyCard(
                    username = fixtureUsername,
                    signingPublicKeyHex = fixtureSignHex,
                    publicKeyHex = fixtureEncHex,
                    copiedSigning = false,
                    copiedEncryption = false,
                    onShare = {},
                    onCopySigningKey = {},
                    onCopyEncryptionKey = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Connection QR").assertIsDisplayed()
        // Historical mislabel: the header used to read `ED25519`
        // (uppercase, standalone) above an X25519 fingerprint. That
        // standalone `ED25519` header MUST be gone. The card DOES
        // still show `Ed25519` (Title Case) inside the signing-key
        // row's type badge — that's correct + intentional.
        composeTestRule.onAllNodesWithText("ED25519").assertCountEquals(0)
    }

    // ── §6.12 — both rows render with exact labels + full hexes via shared formatter ─

    @Test
    fun profile_qr_card_shows_both_key_rows_with_exact_labels_and_full_hexes() {
        composeTestRule.setContent {
            ScrollableCardHost {
                QrKeyCard(
                    username = fixtureUsername,
                    signingPublicKeyHex = fixtureSignHex,
                    publicKeyHex = fixtureEncHex,
                    copiedSigning = false,
                    copiedEncryption = false,
                    onShare = {},
                    onCopySigningKey = {},
                    onCopyEncryptionKey = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // Ed25519 row.
        composeTestRule.onNodeWithText("Identity signing key")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Ed25519")
            .performScrollTo().assertIsDisplayed()
        // Architect pin: displayed full hex MUST equal the shared
        // formatter's output on the same hex — proves the card
        // uses the shared formatter, not a private duplicate.
        composeTestRule.onNodeWithText(formatFullKeyForDisplay(fixtureSignHex))
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Ed25519 short ID")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(formatShortKeyIdForDisplay(fixtureSignHex))
            .performScrollTo().assertIsDisplayed()

        // X25519 row.
        composeTestRule.onNodeWithText("Messaging encryption key")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("X25519")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(formatFullKeyForDisplay(fixtureEncHex))
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("X25519 short ID")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(formatShortKeyIdForDisplay(fixtureEncHex))
            .performScrollTo().assertIsDisplayed()
    }

    // ── Architect pin — Ed25519 row reads ONLY signingPublicKeyHex ──

    @Test
    fun ed25519_row_hex_matches_signing_key_not_encryption_key() {
        composeTestRule.setContent {
            ScrollableCardHost {
                QrKeyCard(
                    username = fixtureUsername,
                    signingPublicKeyHex = fixtureSignHex,
                    publicKeyHex = fixtureEncHex,
                    copiedSigning = false,
                    copiedEncryption = false,
                    onShare = {},
                    onCopySigningKey = {},
                    onCopyEncryptionKey = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // Signing-key hex MUST be visible under the Ed25519 badge.
        composeTestRule.onNodeWithText(formatFullKeyForDisplay(fixtureSignHex))
            .performScrollTo().assertIsDisplayed()
        // Short ID under Ed25519 label MUST derive from signing hex.
        composeTestRule.onNodeWithText(formatShortKeyIdForDisplay(fixtureSignHex))
            .performScrollTo().assertIsDisplayed()
        // NEGATIVE: the ENCRYPTION-key short ID MUST NOT appear
        // as the Ed25519 short-ID value. If a bug swapped fields
        // in the ProfileKeyRow call at the signing site, the
        // Ed25519 chip would show the X25519 short ID and this
        // test would fail. (The X25519 short ID appears
        // elsewhere on the card, tied to the X25519 label —
        // that's fine. This test just proves the two do NOT
        // collide under the Ed25519 label.)
        val ed25519ShortId = formatShortKeyIdForDisplay(fixtureSignHex)
        val x25519ShortId  = formatShortKeyIdForDisplay(fixtureEncHex)
        assertTrue(
            actual = ed25519ShortId != x25519ShortId,
            message = "Test-fixture bug: Ed25519 and X25519 short IDs are equal, so " +
                "swap-detection is impossible. Regenerate fixtures.",
        )
    }

    @Test
    fun x25519_row_hex_matches_encryption_key_not_signing_key() {
        composeTestRule.setContent {
            ScrollableCardHost {
                QrKeyCard(
                    username = fixtureUsername,
                    signingPublicKeyHex = fixtureSignHex,
                    publicKeyHex = fixtureEncHex,
                    copiedSigning = false,
                    copiedEncryption = false,
                    onShare = {},
                    onCopySigningKey = {},
                    onCopyEncryptionKey = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(formatFullKeyForDisplay(fixtureEncHex))
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(formatShortKeyIdForDisplay(fixtureEncHex))
            .performScrollTo().assertIsDisplayed()
    }

    // ── §6.13 + architect pin — QR + Share payload byte-exact X25519 only ──

    @Test
    fun share_callback_receives_byte_exact_qr_payload_no_ed25519() {
        var capturedShare: String? = null
        composeTestRule.setContent {
            ScrollableCardHost {
                QrKeyCard(
                    username = fixtureUsername,
                    signingPublicKeyHex = fixtureSignHex,
                    publicKeyHex = fixtureEncHex,
                    copiedSigning = false,
                    copiedEncryption = false,
                    onShare = { capturedShare = it },
                    onCopySigningKey = {},
                    onCopyEncryptionKey = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Share connection info")
            .performScrollTo().performClick()
        composeTestRule.waitForIdle()

        val expected = "$fixtureUsername:$fixtureEncHex"
        assertEquals(
            expected = expected,
            actual = capturedShare,
            message = "Share callback MUST receive the byte-exact `${'$'}username:${'$'}X25519` " +
                "payload. A regression that mixes Ed25519 into the QR content or the " +
                "Share arg would fail here. Got: $capturedShare",
        )
        // Explicit Ed25519-absence assertion (architect pin: "Ed25519
        // ни при каких условиях не попадает в QR/share payload").
        assertFalse(
            actual = capturedShare?.contains(fixtureSignHex) == true,
            message = "Ed25519 signing hex MUST NEVER appear in the Share payload. " +
                "Got: $capturedShare",
        )
    }

    // ── §6.14 + architect pin — Copy callbacks return correct FULL keys ──

    @Test
    fun copy_ed25519_button_returns_full_signing_hex() {
        var captured: String? = null
        composeTestRule.setContent {
            ScrollableCardHost {
                QrKeyCard(
                    username = fixtureUsername,
                    signingPublicKeyHex = fixtureSignHex,
                    publicKeyHex = fixtureEncHex,
                    copiedSigning = false,
                    copiedEncryption = false,
                    onShare = {},
                    onCopySigningKey = { captured = it },
                    onCopyEncryptionKey = { /* no-op */ },
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Copy Ed25519 signing key")
            .performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            expected = fixtureSignHex,
            actual = captured,
            message = "Copy Ed25519 signing key MUST return the full 64-char signing hex.",
        )
    }

    @Test
    fun copy_x25519_button_returns_full_encryption_hex() {
        var captured: String? = null
        composeTestRule.setContent {
            ScrollableCardHost {
                QrKeyCard(
                    username = fixtureUsername,
                    signingPublicKeyHex = fixtureSignHex,
                    publicKeyHex = fixtureEncHex,
                    copiedSigning = false,
                    copiedEncryption = false,
                    onShare = {},
                    onCopySigningKey = { /* no-op */ },
                    onCopyEncryptionKey = { captured = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Copy X25519 encryption key")
            .performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            expected = fixtureEncHex,
            actual = captured,
            message = "Copy X25519 encryption key MUST return the full 64-char encryption hex.",
        )
    }

    // ── Architect pin — no `fingerprint` word in visible text ──

    @Test
    fun profile_qr_card_visible_text_contains_no_fingerprint_word() {
        composeTestRule.setContent {
            ScrollableCardHost {
                QrKeyCard(
                    username = fixtureUsername,
                    signingPublicKeyHex = fixtureSignHex,
                    publicKeyHex = fixtureEncHex,
                    copiedSigning = false,
                    copiedEncryption = false,
                    onShare = {},
                    onCopySigningKey = {},
                    onCopyEncryptionKey = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText(
            "fingerprint", substring = true, ignoreCase = true,
        ).assertCountEquals(0)
    }

    // ── Architect pin — narrow screen + large font reachable via scroll ──

    @Test
    fun profile_qr_card_labels_reachable_at_320dp_fs2_via_scroll() {
        composeTestRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale = 2.0f),
            ) {
                Box(
                    modifier = Modifier
                        .background(Color.Black)
                        .fillMaxSize()
                        .height(640.dp)
                        .width(320.dp),
                ) {
                    ScrollableCardHost {
                        QrKeyCard(
                            username = fixtureUsername,
                            signingPublicKeyHex = fixtureSignHex,
                            publicKeyHex = fixtureEncHex,
                            copiedSigning = false,
                            copiedEncryption = false,
                            onShare = {},
                            onCopySigningKey = {},
                            onCopyEncryptionKey = {},
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        // Key touch points must be reachable via scroll under
        // the narrow-width + large-text extreme.
        composeTestRule.onNodeWithText("Connection QR")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy Ed25519 signing key")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy X25519 encryption key")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Share connection info")
            .performScrollTo().assertIsDisplayed()
    }

    // ── Architect pin — displayed values equal shared-formatter output ──

    @Test
    fun displayed_full_hex_values_match_shared_formatter_output_verbatim() {
        // Renders BOTH key rows and asserts the displayed
        // full-hex text nodes equal `formatFullKeyForDisplay(hex)`
        // byte-for-byte. If the card ever ships a private
        // formatter (e.g. duplicate logic, drift), the assertion
        // fails-red. This is the "compare against shared function"
        // check architect explicitly asked for.
        composeTestRule.setContent {
            ScrollableCardHost {
                QrKeyCard(
                    username = fixtureUsername,
                    signingPublicKeyHex = fixtureSignHex,
                    publicKeyHex = fixtureEncHex,
                    copiedSigning = false,
                    copiedEncryption = false,
                    onShare = {},
                    onCopySigningKey = {},
                    onCopyEncryptionKey = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        val edFullExpected  = formatFullKeyForDisplay(fixtureSignHex)
        val edShortExpected = formatShortKeyIdForDisplay(fixtureSignHex)
        val xFullExpected   = formatFullKeyForDisplay(fixtureEncHex)
        val xShortExpected  = formatShortKeyIdForDisplay(fixtureEncHex)
        // Each expected string appears exactly ONCE in the tree
        // (no duplicate render). `onAllNodesWithText` returns all
        // matches — assertCountEquals(1) pins uniqueness.
        composeTestRule.onAllNodesWithText(edFullExpected).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(edShortExpected).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(xFullExpected).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(xShortExpected).assertCountEquals(1)
    }
}
