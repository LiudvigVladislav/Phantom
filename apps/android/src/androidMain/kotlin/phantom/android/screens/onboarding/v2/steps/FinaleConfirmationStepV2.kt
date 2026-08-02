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
import androidx.compose.foundation.shape.CircleShape
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
import phantom.android.screens.onboarding.v2.OnboardingFormStateV2
import phantom.android.screens.onboarding.v2.formatFingerprintForDisplay
import phantom.android.screens.onboarding.v2.formatFingerprintShort
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2FontDisplay
import phantom.android.ui.designv2.DesignV2FontMono
import phantom.android.ui.designv2.DesignV2Tokens
import phantom.android.ui.designv2.components.PhantomButton

/**
 * FinaleConfirmationStepV2 — post-Permissions confirmation state
 * (Commit 3 real body). Chromeless: no top bar, no dots.
 *
 * Renders when the flow's `finalize()` succeeds with a real
 * `IdentityRecord`. Per REDLINE §C1 + §C6:
 *
 *   - Shows the REAL `signingPublicKeyHex` (full 64-char Ed25519
 *     public key) chunked as 8 groups of 8 hex chars via
 *     [formatFingerprintForDisplay]. Header "ED25519 · CREATED"
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
 * If `formState.signingPublicKeyHex` is null (defensive — should not
 * happen since finalize populates it before advancing to this step),
 * the step renders a fallback "Something went wrong" state and
 * disables Continue. UI-level safety net; the flow's finalize path
 * guarantees the field is populated on success.
 */
@Composable
fun FinaleConfirmationStepV2(
    formState: OnboardingFormStateV2,
    onContinueClick: () -> Unit,
    onKeyCopied: () -> Unit = {},
) {
    val context = LocalContext.current
    val hex = formState.signingPublicKeyHex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 72.dp, bottom = 24.dp),
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
            text = "Your Ed25519 key",
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
            text = "Generated on device. Your public key can be shared for verification.",
            color = DesignV2Tokens.Colors.TextTertiary,
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
            ),
        )

        Spacer(Modifier.height(20.dp))

        if (hex != null) {
            FinaleKeyCard(hex = hex)
            Spacer(Modifier.height(12.dp))
            FingerprintChip(hex = hex)
            Spacer(Modifier.height(12.dp))
            // Round-1 REDLINE Commit-3 §P2-1: warning banner.
            // The "Lose this key and the account is gone" message
            // was promised on Step 2 (deferred there per redline §C1
            // because the key didn't yet exist). Now that it exists,
            // the warning belongs here.
            KeyLossWarningBanner()
            Spacer(Modifier.height(16.dp))
            CopyKeyButton(
                onClick = {
                    copyFullHexToClipboard(context, hex)
                    onKeyCopied()
                },
            )
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

        Spacer(Modifier.weight(1f))

        PhantomButton(
            text = "Continue",
            onClick = onContinueClick,
            enabled = hex != null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FinaleKeyCard(hex: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DesignV2Tokens.Colors.SurfaceInset)
            .border(1.dp, DesignV2Tokens.Colors.Cyan.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
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
                text = "ED25519 · CREATED",
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
            text = formatFingerprintForDisplay(hex),
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
            text = "Generated on device. Your public key can be shared for verification.",
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
private fun FingerprintChip(hex: String) {
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
            text = formatFingerprintShort(hex),
            color = DesignV2Tokens.Colors.TextPrimary,
            style = TextStyle(
                fontFamily = DesignV2FontMono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
            ),
        )
        // Explicit label per REDLINE §C1: a truncated form must NEVER
        // appear without this label. Prevents the reader from mistaking
        // the short form for a trustworthy value.
        Text(
            text = "fingerprint · short form",
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
private fun CopyKeyButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DesignV2Tokens.Colors.Surface)
            .border(1.dp, DesignV2Tokens.Colors.Border, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Copy public key",
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
            text = "Copy public key",
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
 * Copy the FULL 64-char hex to the system clipboard. Redline §C1
 * requires the copied value equal the entire key — never a truncated
 * display slice. Pinned by
 * `OnboardingV2FinalizeContractTest.copy_writes_full_64_char_hex_to_clipboard`.
 */
internal fun copyFullHexToClipboard(context: Context, fullHex: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Phantom public key", fullHex)
    clipboard.setPrimaryClip(clip)
}
