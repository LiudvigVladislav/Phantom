// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2.steps

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.R
import phantom.android.screens.onboarding.v2.isValidEd25519PublicKeyHex
import phantom.android.screens.onboarding.v2.isValidX25519PublicKeyHex
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2FontDisplay
import phantom.android.ui.designv2.DesignV2FontMono
import phantom.android.ui.designv2.DesignV2Tokens
import phantom.android.ui.designv2.components.PhantomButton
import phantom.android.ui.designv2.formatFullKeyForDisplay
import phantom.android.ui.designv2.formatShortKeyIdForDisplay

/**
 * FinaleConfirmationStepV2 — post-Permissions confirmation state
 * (Commit 3 real body). Chromeless: no top bar, no dots.
 *
 * Renders when the flow's `finalize()` succeeds with a real
 * `IdentityRecord`. Per REDLINE §C1 + §C6:
 *
 *   - Shows the REAL `signingPublicKeyHex` (full 64-char Ed25519
 *     public key) chunked as 8 groups of 8 hex chars via
 *     [formatFullKeyForDisplay]. Header "ED25519 · CREATED"
 *     with green status dot.
 *   - `Copy` button copies the FULL 64-char hex to the system
 *     clipboard (not a truncated slice) — [copyFullHexToClipboard].
 *   - Below the key card, a `fingerprint` chip with the short form
 *     `[first 4]…[last 4]` labeled `fingerprint · short form` — the
 *     ONLY place where a truncated form appears, always with the
 *     explicit label so the reader knows it is NOT a full key.
 *   - `Continue` CTA calls `onContinueClick` which routes to
 *     `MainActivity.onComplete()` → transition to ChatList.
 *
 * C6-a: the composable now takes [signingPublicKeyHex] as a
 * direct parameter instead of pulling it out of a form-state
 * field. The caller wires it from the sealed
 * [phantom.android.screens.onboarding.v2.OnboardingFinalizeStateHolder]:
 * non-null iff the holder is in the `Completed(hex)` variant, and
 * that variant is the sole gate that promotes the flow to Finale
 * (via the derived `currentStep` at the top of the composable).
 * So the null branch below is defense-in-depth for genuinely
 * unexpected states, not the normal happy path.
 *
 * If [signingPublicKeyHex] is null (defensive — should not happen
 * since only the Completed variant renders this step), the step
 * renders a fallback "Something went wrong" state and disables
 * Continue.
 */
@Composable
fun FinaleConfirmationStepV2(
    signingPublicKeyHex: String?,
    publicKeyHex: String?,
    onContinueClick: () -> Unit,
    // Dual-key labels track 2026-08-10: hex parameter added so
    // tests (and any UI observer) can distinguish which key was
    // copied without needing to reach into the ClipboardManager.
    // Existing callers that don't care simply ignore the param.
    onKeyCopied: (hex: String) -> Unit = {},
) {
    val context = LocalContext.current
    // Dual-key labels track 2026-08-10 architect refinement:
    // Continue MUST validate BOTH hexes via Ed25519 / X25519
    // validators (defence-in-depth at UI boundary), NOT just
    // check != null. A regression that passes a malformed but
    // non-null hex through this call site would otherwise render
    // the two cards with garbled content AND leave Continue
    // enabled. Both keys are 32-byte curve-25519 points → 64 hex
    // chars; the validators reject empty / wrong-length / non-hex.
    val bothHexesValid =
        signingPublicKeyHex != null &&
            isValidEd25519PublicKeyHex(signingPublicKeyHex) &&
            publicKeyHex != null &&
            isValidX25519PublicKeyHex(publicKeyHex)

    // Round-6 REDLINE on Commit 5 §P0: scrollable body + fixed CTA.
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                // Round-8 REDLINE §P1: top bar reserved above.
                .padding(top = 4.dp),
        ) {
            Text(
                text = "IDENTITY CREATED",
                color = DesignV2Tokens.Colors.Success,
                style = TextStyle(
                    fontFamily = DesignV2FontMono,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.4.sp,
                ),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Your keys",
                color = DesignV2Tokens.Colors.TextPrimary,
                style = TextStyle(
                    fontFamily = DesignV2FontDisplay,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp,
                    lineHeight = 32.sp,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                // Dual-key labels track 2026-08-10 — architect §2
                // exact footer copy.
                text = "Ed25519 verifies identity signatures. X25519 establishes " +
                    "encrypted messaging sessions.",
                color = DesignV2Tokens.Colors.TextTertiary,
                style = TextStyle(
                    fontFamily = DesignV2FontBody,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                ),
            )

            Spacer(Modifier.height(20.dp))

            if (bothHexesValid) {
                // Card 1 — Ed25519 identity signing key.
                FinaleKeyCard(
                    titleLabel = "IDENTITY SIGNING KEY",
                    typeBadge = "ED25519 · CREATED",
                    hex = signingPublicKeyHex!!,
                )
                Spacer(Modifier.height(12.dp))
                ShortKeyIdChip(
                    chipLabel = "Ed25519 short ID",
                    hex = signingPublicKeyHex,
                )
                Spacer(Modifier.height(12.dp))
                CopyKeyButton(
                    buttonLabel = "Copy Ed25519 signing key",
                    onClick = {
                        copyFullHexToClipboard(
                            context, signingPublicKeyHex,
                            clipLabel = "Phantom Ed25519 signing key",
                        )
                        onKeyCopied(signingPublicKeyHex)
                    },
                )

                Spacer(Modifier.height(24.dp))

                // Card 2 — X25519 messaging encryption key
                // (dual-key labels track 2026-08-10).
                FinaleKeyCard(
                    titleLabel = "MESSAGING ENCRYPTION KEY",
                    typeBadge = "X25519 · CREATED",
                    hex = publicKeyHex!!,
                )
                Spacer(Modifier.height(12.dp))
                ShortKeyIdChip(
                    chipLabel = "X25519 short ID",
                    hex = publicKeyHex,
                )
                Spacer(Modifier.height(12.dp))
                CopyKeyButton(
                    buttonLabel = "Copy X25519 encryption key",
                    onClick = {
                        copyFullHexToClipboard(
                            context, publicKeyHex,
                            clipLabel = "Phantom X25519 encryption key",
                        )
                        onKeyCopied(publicKeyHex)
                    },
                )

                Spacer(Modifier.height(16.dp))

                // Round-1 REDLINE Commit-3 §P2-1: warning banner —
                // now covers loss of EITHER key (dual-key track
                // 2026-08-10; architect chose neutral single copy).
                KeyLossWarningBanner()
            } else {
                Text(
                    text = "Something went wrong — please restart onboarding.",
                    color = DesignV2Tokens.Colors.Error,
                    style = TextStyle(
                        fontFamily = DesignV2FontBody,
                        fontSize = 14.sp,
                    ),
                )
            }

            Spacer(Modifier.height(20.dp))
        }
        // Round-9 REDLINE §P1: safe-bottom navigation-bar inset.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            PhantomButton(
                text = "Continue",
                onClick = onContinueClick,
                enabled = bothHexesValid,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Reusable key-card composable.
 *
 * Dual-key labels track 2026-08-10 §2 — parameterised so both
 * cards (Ed25519 signing key + X25519 encryption key) share
 * identical layout, hex-formatting, and colour scheme. The
 * SHARED [formatFullKeyForDisplay] guarantees the two
 * cards render their hex payloads with visually identical
 * chunking — architect §2 explicit requirement.
 */
@Composable
private fun FinaleKeyCard(
    titleLabel: String,
    typeBadge: String,
    hex: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DesignV2Tokens.Colors.SurfaceInset)
            .border(1.dp, DesignV2Tokens.Colors.Cyan.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        // Title row (dual-key labels track 2026-08-10 §2 —
        // architect exact copy).
        Text(
            text = titleLabel,
            color = DesignV2Tokens.Colors.TextSecondary,
            style = TextStyle(
                fontFamily = DesignV2FontMono,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.6.sp,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_dv2_ed25519_key),
                contentDescription = null,
                tint = DesignV2Tokens.Colors.Cyan,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = typeBadge,
                color = DesignV2Tokens.Colors.Cyan,
                style = TextStyle(
                    fontFamily = DesignV2FontMono,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.6.sp,
                ),
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(DesignV2Tokens.Colors.Success),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = formatFullKeyForDisplay(hex),
            color = DesignV2Tokens.Colors.TextPrimary,
            style = TextStyle(
                fontFamily = DesignV2FontMono,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.8.sp,
                lineHeight = 20.sp,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Generated on device.",
            color = DesignV2Tokens.Colors.TextQuaternary,
            style = TextStyle(
                fontFamily = DesignV2FontMono,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.4.sp,
                lineHeight = 15.sp,
            ),
        )
    }
}

@Composable
private fun ShortKeyIdChip(
    chipLabel: String,
    hex: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(DesignV2Tokens.Colors.Surface)
            .border(
                1.dp,
                DesignV2Tokens.Colors.Border,
                RoundedCornerShape(percent = 50),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = formatShortKeyIdForDisplay(hex),
            color = DesignV2Tokens.Colors.TextPrimary,
            style = TextStyle(
                fontFamily = DesignV2FontMono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
            ),
        )
        // Explicit label — dual-key labels track 2026-08-10
        // architect §2: short values are `Ed25519 short ID` /
        // `X25519 short ID`, NOT "fingerprint".
        Text(
            text = chipLabel,
            color = DesignV2Tokens.Colors.TextQuaternary,
            style = TextStyle(
                fontFamily = DesignV2FontMono,
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.6.sp,
            ),
        )
    }
}

@Composable
private fun KeyLossWarningBanner() {
    // Round-1 REDLINE Commit-3 §P2-1. Amber banner style (matches
    // Commit 1's memo for the moved warning). No `contentDescription`
    // on the icon — the text is the label; the icon is decorative.
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Color(0x14FFB800))
            .border(
                1.dp,
                androidx.compose.ui.graphics.Color(0x2EFFB800),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_dv2_alert),
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color(0xFFC9A465),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Lose this key and the account is gone. There is no reset — that is the point.",
            color = androidx.compose.ui.graphics.Color(0xFFC9A465),
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Normal,
                lineHeight = 18.sp,
            ),
        )
    }
}

@Composable
private fun CopyKeyButton(
    buttonLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DesignV2Tokens.Colors.Surface)
            .border(1.dp, DesignV2Tokens.Colors.Border, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = buttonLabel,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_dv2_copy),
            contentDescription = null,
            tint = DesignV2Tokens.Colors.TextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = buttonLabel,
            color = DesignV2Tokens.Colors.TextSecondary,
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

/**
 * Copy the FULL 64-char hex to the system clipboard with a
 * label that identifies which key type was copied. Redline §C1
 * requires the copied value equal the entire key — never a
 * truncated display slice. Dual-key labels track 2026-08-10:
 * `clipLabel` distinguishes signing vs encryption clipboard
 * entries so paste UIs (Android Clipboard notification, some
 * password managers) can display "Ed25519 signing key" or
 * "X25519 encryption key" instead of a generic
 * "public key".
 */
internal fun copyFullHexToClipboard(
    context: Context,
    fullHex: String,
    clipLabel: String = "Phantom public key",
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(clipLabel, fullHex)
    clipboard.setPrimaryClip(clip)
}
