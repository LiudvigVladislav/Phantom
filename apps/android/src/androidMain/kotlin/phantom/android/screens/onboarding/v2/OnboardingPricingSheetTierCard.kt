// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

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
import androidx.compose.foundation.layout.offset
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
 * Tier-card composables for the onboarding pricing sheet.
 *
 * Extracted from `OnboardingPricingSheetV2.kt` in round-3 REDLINE
 * §P2-5 file-split — keeps the main sheet file's modal frame
 * (backdrop / panel / grab-strip / header / footer) focused on
 * lifecycle + modal semantics, and this file focused on the
 * per-tier visual card (icon + name + price + features + CTA)
 * and its decorative subcomponents (RecommendedRibbon,
 * PricingCalloutBox, PricingFeatureRow, PricingCtaButton).
 *
 * All entry points here are `internal` so the sheet file can call
 * `PricingTierCard` while keeping the rest of the module out of
 * reach.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun PricingTierCard(
    tier: PricingTierV2,
    onCtaClick: () -> Unit,
) {
    val cardBg = if (tier.recommended) Color(0xFF0D1015) else Color(0xFF0B0D11)
    val cardBorder = if (tier.recommended) DesignV2Tokens.Colors.Cyan.copy(alpha = 0.35f)
                     else DesignV2Tokens.Colors.Border

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(cardBg)
                .border(1.dp, cardBorder, RoundedCornerShape(18.dp))
                .padding(horizontal = 18.dp, vertical = 20.dp),
        ) {
            // Round-7 REDLINE on Commit 5 §P1 pin: header row
            // now wraps as (icon + name) / (price + /mo). Prior
            // single Row with baseline alignment at narrow width
            // × fontScale 2.0 forced `/mo` to render as a
            // vertical clipped column of letters and crowded the
            // tier name against the price. `FlowRow` keeps the
            // logical grouping (name always with icon; price
            // always with /mo) so the wrap boundary lands at
            // the natural " " between them.
            androidx.compose.foundation.layout.FlowRow(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Icon(
                        painter = painterResource(tier.iconRes),
                        contentDescription = null,
                        tint = DesignV2Tokens.Colors.Cyan,
                        modifier = Modifier
                            .size(18.dp)
                            .clearAndSetSemantics { },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = tier.name,
                        color = DesignV2Tokens.Colors.TextPrimary,
                        style = TextStyle(
                            fontFamily = DesignV2FontDisplay,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = tier.price,
                        color = DesignV2Tokens.Colors.TextPrimary,
                        style = TextStyle(
                            fontFamily = DesignV2FontDisplay,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        text = "/mo",
                        color = DesignV2Tokens.Colors.TextQuaternary,
                        style = TextStyle(
                            fontFamily = DesignV2FontBody,
                            fontSize = 12.sp,
                        ),
                        modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = tier.sub,
                color = DesignV2Tokens.Colors.TextQuaternary,
                style = TextStyle(
                    fontFamily = DesignV2FontBody,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                ),
            )

            if (tier.calloutTitle != null && tier.calloutBody != null) {
                Spacer(Modifier.height(14.dp))
                PricingCalloutBox(title = tier.calloutTitle, body = tier.calloutBody)
            }

            Spacer(Modifier.height(14.dp))
            tier.features.forEach { feature ->
                PricingFeatureRow(text = feature)
            }
            Spacer(Modifier.height(4.dp))
            PricingCtaButton(
                text = tier.cta,
                recommended = tier.recommended,
                onClick = onCtaClick,
            )
        }
        if (tier.recommended) {
            RecommendedRibbon(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp)
                    .offset(y = (-8).dp),
            )
        }
    }
}

@Composable
private fun RecommendedRibbon(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(DesignV2Tokens.Colors.Cyan)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = "RECOMMENDED",
            color = Color(0xFF04222B),
            style = TextStyle(
                fontFamily = DesignV2FontMono,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.08.sp,
                lineHeight = 11.sp,
            ),
        )
    }
}

@Composable
private fun PricingCalloutBox(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF101319))
            .border(1.dp, DesignV2Tokens.Colors.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            color = DesignV2Tokens.Colors.Cyan,
            style = TextStyle(
                fontFamily = DesignV2FontMono,
                fontSize = 10.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 12.sp,
            ),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = body,
            color = DesignV2Tokens.Colors.TextTertiary,
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            ),
        )
    }
}

@Composable
private fun PricingFeatureRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(DesignV2Tokens.Colors.Cyan.copy(alpha = 0.12f))
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_dv2_confirm),
                contentDescription = null,
                tint = DesignV2Tokens.Colors.Cyan,
                modifier = Modifier.size(11.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            color = Color(0xFFD6E0E6),
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
            ),
        )
    }
}

@Composable
private fun PricingCtaButton(
    text: String,
    recommended: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (recommended) DesignV2Tokens.Colors.Cyan else Color(0xFF161A20)
    val fg = if (recommended) Color(0xFF04222B) else DesignV2Tokens.Colors.TextPrimary
    val borderColor = if (recommended) Color.Transparent else Color(0xFF2A2F38)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = text,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = fg,
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 20.sp,
            ),
        )
    }
}
