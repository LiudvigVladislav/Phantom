// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.profile

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Onboarding-stabilization block 2026-08-11 — focused tests for the
 * simplified `ProfileScreen.QrKeyCard` shape.
 *
 * Pins the architect verdict on the dual-key-labels shape:
 *   - Main surface reads "My Phantom QR", one primary "Share my
 *     Phantom contact" action, no raw key material visible.
 *   - "Advanced cryptographic details" section is collapsed by
 *     default. Public keys only surface after the user explicitly
 *     expands it.
 *   - When expanded, both public keys are labelled "Public key ·
 *     Ed25519 (signing)" and "Public key · X25519 (encryption)" so
 *     no reader can mistake them for secrets. Copy actions carry
 *     "Copy public key" — no "Ed25519 signing key" naming that
 *     could imply the value is signable material.
 *   - QR/Share payload stays byte-exact `${username}:${publicKeyHex}`
 *     (X25519 only); Ed25519 hex never leaves through Share.
 *
 * Contract sheet:
 * `docs/tracks/android-onboarding/onboarding-stabilization-block-2026-08-11.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileQrKeyCardSimplifiedTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val fixtureUsername = "alice"
    private val fixtureSignHex = "a".repeat(64)
    private val fixtureEncHex  = "b".repeat(64)

    // QrKeyCard sits inside a scrollable Column in production
    // ProfileScreen — mirror that here so `performScrollTo` on the
    // Advanced-expanded content has a scrollable ancestor.
    @Composable
    private fun Host(content: @Composable () -> Unit) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            content()
        }
    }

    @Test
    fun main_surface_shows_my_phantom_qr_header_and_share_cta_and_no_raw_keys() {
        composeTestRule.setContent {
            Host {
                QrKeyCard(
                    username = fixtureUsername,
                    signingPublicKeyHex = fixtureSignHex,
                    publicKeyHex = fixtureEncHex,
                    onShare = {},
                    onCopySigningKey = {},
                    onCopyEncryptionKey = {},
                    // Default (collapsed): Advanced hidden.
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("My Phantom QR").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share my Phantom contact")
            .performScrollTo().assertIsDisplayed()

        // Advanced toggle present but collapsed → no raw key text visible.
        composeTestRule.onNodeWithText(
            "Advanced cryptographic details", substring = true,
        ).performScrollTo().assertIsDisplayed()
        for (banned in listOf(
            fixtureSignHex, fixtureEncHex,
            "Copy public key", "Ed25519 (signing)", "X25519 (encryption)",
        )) {
            composeTestRule.onAllNodesWithText(
                text = banned, substring = true,
            ).assertCountEquals(
                expectedSize = 0,
            )
        }

        // Also prove the OLD naming that made keys look like secrets
        // is gone from the main surface AND from the collapsed
        // Advanced toggle.
        for (banned in listOf(
            "Connection QR", "Share connection info",
            "Copy Ed25519 signing key", "Copy X25519 encryption key",
            "Identity signing key", "Messaging encryption key",
        )) {
            composeTestRule.onAllNodesWithText(
                text = banned, substring = true,
            ).assertCountEquals(
                expectedSize = 0,
            )
        }
    }

    @Test
    fun expanding_advanced_reveals_both_public_keys_labelled_public_key() {
        composeTestRule.setContent {
            Host {
                QrKeyCard(
                    username = fixtureUsername,
                    signingPublicKeyHex = fixtureSignHex,
                    publicKeyHex = fixtureEncHex,
                    onShare = {},
                    onCopySigningKey = {},
                    onCopyEncryptionKey = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(
            "Advanced cryptographic details", substring = true,
        ).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(
            "Public key · Ed25519 (signing)",
        ).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Public key · X25519 (encryption)",
        ).performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodesWithText(
            "Copy public key", substring = true,
        ).assertCountEquals(
            expectedSize = 2,
        )
        // Final Stabilization Mini-Block 2026-08-11 §P2 pin:
        // architect-exact explainer text. `safe to share` was too
        // absolute — the copy now states the actual guarantee
        // (identity, verification), the actual boundary (they
        // cannot unlock the account), and the actual warning
        // (never share a private key or recovery backup). This
        // assertion pins the FULL string verbatim so any drift
        // fails-red.
        composeTestRule.onNodeWithText(
            "These public keys identify your Phantom account and " +
                "may be shared for verification. They cannot unlock it. " +
                "Never share a private key or recovery backup.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun share_callback_receives_byte_exact_payload_username_colon_x25519() {
        var captured: String? = null
        composeTestRule.setContent {
            Host {
                QrKeyCard(
                    username = fixtureUsername,
                    signingPublicKeyHex = fixtureSignHex,
                    publicKeyHex = fixtureEncHex,
                    onShare = { captured = it },
                    onCopySigningKey = {},
                    onCopyEncryptionKey = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Share my Phantom contact")
            .performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            expected = "$fixtureUsername:$fixtureEncHex",
            actual = captured,
            message = "Share must fire byte-exact `\${username}:\${X25519 hex}` " +
                "payload — same wire format as pre-simplification. Got: $captured",
        )
        assertFalse(
            actual = captured?.contains(fixtureSignHex) == true,
            message = "Ed25519 signing hex MUST NEVER appear in the Share payload.",
        )
    }

    @Test
    fun expanded_copy_public_key_ed25519_returns_full_signing_hex() {
        var captured: String? = null
        composeTestRule.setContent {
            Host {
                QrKeyCard(
                    username = fixtureUsername,
                    signingPublicKeyHex = fixtureSignHex,
                    publicKeyHex = fixtureEncHex,
                    onShare = {},
                    onCopySigningKey = { captured = it },
                    onCopyEncryptionKey = {},
                    initialAdvancedExpanded = true,
                )
            }
        }
        composeTestRule.waitForIdle()

        // Two Copy public key buttons — the Ed25519 (signing) one
        // sits right after its label, so `useUnmergedTree = false`
        // (default) sees them as siblings ordered like the render.
        composeTestRule.onAllNodesWithText("Copy public key")[0]
            .performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            expected = fixtureSignHex,
            actual = captured,
            message = "First Copy public key (Ed25519 signing row) must return full signing hex",
        )
    }

    @Test
    fun expanded_copy_public_key_x25519_returns_full_encryption_hex() {
        var captured: String? = null
        composeTestRule.setContent {
            Host {
                QrKeyCard(
                    username = fixtureUsername,
                    signingPublicKeyHex = fixtureSignHex,
                    publicKeyHex = fixtureEncHex,
                    onShare = {},
                    onCopySigningKey = {},
                    onCopyEncryptionKey = { captured = it },
                    initialAdvancedExpanded = true,
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("Copy public key")[1]
            .performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            expected = fixtureEncHex,
            actual = captured,
            message = "Second Copy public key (X25519 encryption row) must return full encryption hex",
        )
    }
}
