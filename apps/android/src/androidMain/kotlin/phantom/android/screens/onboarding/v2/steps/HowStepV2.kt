// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.R
import phantom.android.screens.onboarding.v2.OnboardingStepDotsV2
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2FontDisplay
import phantom.android.ui.designv2.DesignV2Tokens
import phantom.android.ui.designv2.components.PhantomButton

/**
 * HowStepV2 — Step 1 / "How Phantom protects you". Chrome: top bar
 * "STEP 1 OF 4" (drawn by the shared host frame) + step-dots at
 * position 0 (drawn INSIDE this step body, above the primary CTA, per
 * round-1 REDLINE P1-3).
 *
 * Three info cards. Copy for cards 2 and 3 comes verbatim from handoff
 * `Onboarding.dc.html`. Card 1 copy is REWRITTEN per redline §C3 to
 * remove the "Nothing to leak, nothing to subpoena" overclaim (subpoena
 * language is legal marketing, not a technical property; and the public
 * half of the keypair IS shared for verification, so "never leaves the
 * device" is inaccurate).
 *
 * Title carries a FORCED line break per round-1 REDLINE P1-3:
 *   "How Phantom\nprotects you"
 * Without the hard newline, at Pixel 5 width (411 dp) with Geist
 * SemiBold 29 sp the title wraps naturally as
 *   "How Phantom protects\nyou"
 * which diverges from handoff `Onboarding.dc.html` (line 84).
 *
 * Corrected copy per redline:
 *
 *   Card 1 "Keys, not phone numbers":
 *     "An Ed25519 keypair is created here. The private key stays on this
 *      device; the public key is shared for verification."
 *
 *   Card 2 "Sealed end to end" (UNCHANGED from handoff):
 *     "Every message is encrypted before it leaves you. Relays forward
 *      ciphertext they cannot read."
 *
 *   Card 3 "You control visibility" (UNCHANGED from handoff — UX claim,
 *     not security claim):
 *     "Presence, read receipts, and discovery are switches — not defaults
 *      you have to fight."
 */
@Composable
fun HowStepV2(
    dotsIndex: Int,
    onContinueClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            // top: below the top-bar (~64 dp effective); bottom: system nav margin
            .padding(top = 72.dp, bottom = 24.dp),
    ) {
        // Hard newline per REDLINE P1-3 — matches handoff line-break.
        // letterSpacing = 0.sp per round-2 REDLINE P2-5: the UI contract
        // (2026-08-01) prohibits negative tracking; the prior -0.29 sp
        // was carried over from an early handoff transcription and is
        // now zeroed out. Wrap and visual weight are unchanged at this
        // font size.
        Text(
            text = "How Phantom\nprotects you",
            color = DesignV2Tokens.Colors.TextPrimary,
            style = TextStyle(
                fontFamily = DesignV2FontDisplay,
                fontSize = 29.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
                lineHeight = 34.sp,
            ),
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Three things work differently here. Worth thirty seconds.",
            color = DesignV2Tokens.Colors.TextTertiary,
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 14.5.sp,
                lineHeight = 20.sp,
            ),
        )

        Spacer(Modifier.height(28.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HowInfoCard(
                iconResId = R.drawable.ic_dv2_ed25519_key,
                title = "Keys, not phone numbers",
                body = "An Ed25519 keypair is created here. The private key stays on this device; the public key is shared for verification.",
            )
            HowInfoCard(
                iconResId = R.drawable.ic_dv2_encrypted,
                title = "Sealed end to end",
                body = "Every message is encrypted before it leaves you. Relays forward ciphertext they cannot read.",
            )
            HowInfoCard(
                iconResId = R.drawable.ic_dv2_ghost_signal,
                title = "You control visibility",
                body = "Presence, read receipts, and discovery are switches — not defaults you have to fight.",
            )
        }

        // Step dots ABOVE the CTA per REDLINE P1-3. Previously the dots
        // were drawn as a frame-level BottomCenter overlay which put them
        // BELOW the CTA at ~y=(screen-24 dp) while the CTA sat higher up
        // at ~y=(screen-96 dp). Handoff `Onboarding.dc.html` line 84
        // has dots stacked directly above the primary CTA.
        Spacer(Modifier.height(20.dp))
        OnboardingStepDotsV2(dotsIndex = dotsIndex)
        Spacer(Modifier.height(12.dp))

        PhantomButton(
            text = "Create my identity",
            onClick = onContinueClick,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HowInfoCard(
    iconResId: Int,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DesignV2Tokens.Colors.SurfaceInset)
            .border(1.dp, DesignV2Tokens.Colors.Border, RoundedCornerShape(18.dp))
            .padding(horizontal = 15.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(DesignV2Tokens.Colors.Cyan.copy(alpha = 0.09f))
                .border(1.dp, DesignV2Tokens.Colors.Cyan.copy(alpha = 0.20f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // Decorative icon (P2-5): contentDescription = null so the
            // card's title Text (below) is the only announced label.
            Icon(
                painter = painterResource(iconResId),
                contentDescription = null,
                tint = DesignV2Tokens.Colors.Cyan,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DesignV2Tokens.Colors.TextPrimary,
                style = TextStyle(
                    fontFamily = DesignV2FontBody,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                color = DesignV2Tokens.Colors.TextTertiary,
                style = TextStyle(
                    fontFamily = DesignV2FontBody,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                ),
            )
        }
    }
}
