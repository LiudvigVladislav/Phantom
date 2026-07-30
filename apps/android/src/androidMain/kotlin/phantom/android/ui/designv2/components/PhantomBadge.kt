// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PhantomBadge — DesignV2 unread/count badge.
 *
 * Per handoff `design-system-notes.md` §"Badges / unread counts":
 *   - Cyan fill (#00D4FF), Surface Deep numeral (#08090C).
 *   - lineHeight 1.
 *   - min-width 20pt, circle/pill.
 *   - Plain red dot #ef4444 variant for "activity, no count" (dotOnly = true).
 *
 * NOTE: This is the ONLY DesignV2 component in the F0 compatibility spike.
 * It exists to prove the Paparazzi+KMP+Compose Multiplatform toolchain
 * against AGP 9.1.1 / Kotlin 2.2.10. Full component library lands in F2
 * after this spike is signed off. Do NOT reach into `phantom.android.ui.designv2.*`
 * from production screen code until F2 lands.
 *
 * Colors are hardcoded here (not sourced from a DesignV2 token file) —
 * F1 lands the DesignV2Tokens.kt and this file will be updated to
 * reference them then. Isolating the spike to one file keeps the F0
 * change surface small.
 */
@Composable
fun PhantomBadge(
    count: Int? = null,
    dotOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val fill = if (dotOnly) Color(0xFFEF4444) else Color(0xFF00D4FF)
    val textColor = Color(0xFF08090C)

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .background(color = fill, shape = CircleShape)
            .padding(horizontal = if (dotOnly) 0.dp else 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (!dotOnly && count != null) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                color = textColor,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    // lineHeight = fontSize per handoff (line-height:1)
                ),
            )
        }
    }
}