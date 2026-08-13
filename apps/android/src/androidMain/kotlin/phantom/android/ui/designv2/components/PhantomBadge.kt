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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.ui.designv2.DesignV2Tokens

/**
 * PhantomBadge — DesignV2 unread/count badge.
 *
 * Handoff `design-system-notes.md §Badges / unread counts`:
 *   - Cyan fill, Surface Deep numeral, line-height 1.
 *   - min-width 20pt, circle/pill.
 *   - Plain red dot (Error) variant for "activity, no count" (dotOnly = true).
 *
 * The badge is decorative; it carries no touch interaction, so no 48dp
 * touch-target requirement applies. Callers wrapping this in a clickable
 * row are responsible for the target size on that row.
 */
@Composable
fun PhantomBadge(
    count: Int? = null,
    dotOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val fill = if (dotOnly) DesignV2Tokens.Colors.Error else DesignV2Tokens.Colors.Cyan
    val numeralColor = DesignV2Tokens.Colors.SurfaceDeep

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
                color = numeralColor,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}