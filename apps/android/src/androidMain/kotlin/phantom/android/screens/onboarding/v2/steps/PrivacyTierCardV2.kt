// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.R
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2FontDisplay
import phantom.android.ui.designv2.DesignV2FontMono
import phantom.android.ui.designv2.DesignV2Tokens

/**
 * Privacy-tier description card + its decorative subcomponents.
 *
 * Extracted from `PrivacyLevelStepV2.kt` in round-3 REDLINE §P2-5
 * file-split — the step file owns layout + segment bar, this file
 * owns the tier card visual (icon block + name + tag pill +
 * description + bullets + optional Unlock CTA) and its
 * decorative helpers.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun PrivacyTierCard(
    tier: PrivacyTierV2,
    onUnlockClick: () -> Unit,
) {
    val borderColor = if (tier.locked) DesignV2Tokens.Colors.Border
                      else DesignV2Tokens.Colors.Cyan.copy(alpha = 0.26f)
    val iconBg = if (tier.locked) DesignV2Tokens.Colors.TextTertiary.copy(alpha = 0.10f)
                 else DesignV2Tokens.Colors.Cyan.copy(alpha = 0.10f)
    val iconBorder = if (tier.locked) DesignV2Tokens.Colors.Border
                     else DesignV2Tokens.Colors.Cyan.copy(alpha = 0.22f)
    val iconTint = if (tier.locked) DesignV2Tokens.Colors.TextTertiary
                   else DesignV2Tokens.Colors.Cyan

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(DesignV2Tokens.Colors.SurfaceInset)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(iconBg)
                    .border(1.dp, iconBorder, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(tier.iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier
                        .size(22.dp)
                        .clearAndSetSemantics { },
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Round-6 REDLINE on Commit 5 §P1 visual pin: use
                // `FlowRow` so at `fontScale = 2.0` on a narrow
                // phone the tag pill wraps to a new line UNDER the
                // tier name instead of being crammed alongside it.
                // Prior `Row` shape forced the layout to fit name +
                // pill on one line — "STANDARD" wrapped mid-word
                // and "BALANCED" pill fell apart vertically as
                // `BA / LA / NC / ED`.
                androidx.compose.foundation.layout.FlowRow(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = tier.name,
                        color = DesignV2Tokens.Colors.TextPrimary,
                        style = TextStyle(
                            fontFamily = DesignV2FontDisplay,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    TierTagPill(text = tier.tag, locked = tier.locked)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = tier.description,
                    color = DesignV2Tokens.Colors.TextTertiary,
                    style = TextStyle(
                        fontFamily = DesignV2FontBody,
                        fontSize = 13.sp,
                        lineHeight = 19.5.sp,
                    ),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DesignV2Tokens.Colors.Border),
        )
        Spacer(Modifier.height(6.dp))

        tier.bullets.forEach { bullet ->
            PrivacyTierBullet(text = bullet)
        }

        if (tier.locked) {
            Spacer(Modifier.height(16.dp))
            UnlockWithPhantomProCta(onClick = onUnlockClick)
        }
    }
}

@Composable
private fun TierTagPill(text: String, locked: Boolean) {
    // Locked tier ("PRO"): cyan text on translucent cyan bg with cyan
    // border. Free tiers ("BALANCED" / "RECOMMENDED"): dark text on
    // solid cyan bg.
    val bg = if (locked) DesignV2Tokens.Colors.Cyan.copy(alpha = 0.12f)
             else DesignV2Tokens.Colors.Cyan
    val textColor = if (locked) DesignV2Tokens.Colors.Cyan
                    else Color(0xFF04222B)
    val border = if (locked) DesignV2Tokens.Colors.Cyan.copy(alpha = 0.3f)
                 else Color.Transparent
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = textColor,
            style = TextStyle(
                fontFamily = DesignV2FontMono,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.02.sp,
                lineHeight = 10.sp,
            ),
            // Round-6 REDLINE on Commit 5 §P1 visual pin: pill label
            // MUST stay single-line. At fontScale = 2.0 the prior
            // shape let "BALANCED" break down to `BA / LA / NC / ED`
            // vertically inside the pill, which read as accessibility-
            // hostile visual damage. Single-line + no-wrap + clip
            // keeps the tag legible; the FlowRow parent above
            // guarantees the whole pill wraps under the name if
            // horizontal space is tight, so clipping is unlikely in
            // practice.
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
        )
    }
}

@Composable
private fun PrivacyTierBullet(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(DesignV2Tokens.Colors.Cyan.copy(alpha = 0.12f))
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_dv2_confirm),
                contentDescription = null,
                tint = DesignV2Tokens.Colors.Cyan,
                modifier = Modifier.size(10.dp),
            )
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            color = DesignV2Tokens.Colors.TextTertiary,
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 13.sp,
                lineHeight = 19.5.sp,
            ),
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

@Composable
private fun UnlockWithPhantomProCta(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(DesignV2Tokens.Colors.Cyan)
            .clickable(
                role = Role.Button,
                onClickLabel = "Unlock with Phantom Pro",
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_dv2_phantom_premium),
            contentDescription = null,
            tint = Color(0xFF04222B),
            modifier = Modifier
                .size(16.dp)
                .clearAndSetSemantics { },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Unlock with Phantom Pro",
            color = Color(0xFF04222B),
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 20.sp,
            ),
            // NOTE: Text is intentionally NOT wrapped in
            // clearAndSetSemantics — its content merges upward into
            // the parent Row's clickable node so screen readers +
            // Compose test matchers can locate the button by its
            // "Unlock with Phantom Pro" text (same shape as
            // PhantomButton). The `onClickLabel` on the Row is
            // redundant belt-and-braces for TalkBack; the tree looks
            // like one merged Role.Button with Text + OnClick.
        )
    }
}

// ── Static tier data ─────────────────────────────────────────────────

