// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.R
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2FontMono
import phantom.android.ui.designv2.DesignV2Tokens

/**
 * PhantomInput — DesignV2 text field.
 *
 * Handoff `design-system-notes.md §Inputs`:
 *   - Default : bg Surface (#12151B), 1px Border (#1F242C), radius 12–14.
 *   - Focus   : 1px cyan border + 2px Cyan-8% ring.
 *   - Error   : Error border (#EF4444) + 2px Error-8% ring, red helper text,
 *               `(!)` icon at right.
 *   - Username inputs use JetBrains Mono (opt in via [useMonoFont]).
 *   - Disabled: 38% opacity across the whole surface.
 *
 * State is fully hoisted (stateless). Focus visuals come from an internal
 * MutableInteractionSource so callers don't have to plumb it. Error state is
 * a caller decision — the composable draws the ring but does not compute
 * whether the value is valid.
 *
 * [leadingContent] and [trailingContent] are generic composable slots that
 * render inside the input's outer box, flanking the text/placeholder area.
 * Existing callers pass neither and get the original single-column layout.
 * Onboarding's username field uses [leadingContent] for the `@` glyph +
 * hairline divider and [trailingContent] for a validity status icon; other
 * consumers can use the same slots for anything shaped like an icon-sized
 * adornment (e.g. a currency symbol, unit label, or clear-value button).
 *
 * Slot vs [isError] icon precedence: when [isError] is true AND
 * [trailingContent] is null, the built-in `(!)` alert icon renders on the
 * right — original behaviour preserved. When [trailingContent] is provided,
 * the built-in alert icon is suppressed and the caller's slot content owns
 * the trailing area (semantics still carry the error description via the
 * [helperText] channel — the visual is delegated).
 *
 * Touch-target height: 48dp minimum, even though the visual box is 44dp per
 * handoff — internal padding brings it up. Wraps BasicTextField (foundation)
 * so we don't inherit Material's TextField chrome.
 */
@Composable
fun PhantomInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helperText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    useMonoFont: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val borderColor = when {
        isError -> DesignV2Tokens.Colors.Error
        focused -> DesignV2Tokens.Colors.Cyan
        else -> DesignV2Tokens.Colors.Border
    }
    val ringColor = when {
        isError -> DesignV2Tokens.Colors.Error.copy(alpha = 0.08f)
        focused -> DesignV2Tokens.Colors.Cyan.copy(alpha = 0.08f)
        else -> null
    }
    val textStyle = TextStyle(
        fontFamily = if (useMonoFont) DesignV2FontMono else DesignV2FontBody,
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        color = if (enabled) DesignV2Tokens.Colors.TextPrimary else DesignV2Tokens.Colors.TextQuaternary,
        lineHeight = 20.sp,
    )
    val placeholderStyle = textStyle.copy(color = DesignV2Tokens.Colors.TextTertiary)

    val outerShape = RoundedCornerShape(DesignV2Tokens.Radius.input)

    val errorDescription = if (isError) helperText ?: "invalid input" else null

    Column(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.38f)
            .then(
                if (errorDescription != null) {
                    Modifier.semantics { error(errorDescription) }
                } else Modifier
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .then(
                    if (ringColor != null) {
                        Modifier.border(2.dp, ringColor, outerShape)
                    } else Modifier
                )
                .background(DesignV2Tokens.Colors.Surface, outerShape)
                .border(1.dp, borderColor, outerShape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (leadingContent != null) {
                    leadingContent()
                    Spacer(Modifier.width(8.dp))
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(text = placeholder, style = placeholderStyle)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        enabled = enabled,
                        textStyle = textStyle,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(DesignV2Tokens.Colors.Cyan),
                        interactionSource = interaction,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (trailingContent != null) {
                    Spacer(Modifier.width(8.dp))
                    trailingContent()
                } else if (isError) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_dv2_alert),
                        contentDescription = null,
                        tint = DesignV2Tokens.Colors.Error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (helperText != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = helperText,
                style = TextStyle(
                    fontFamily = DesignV2FontBody,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = if (isError) DesignV2Tokens.Colors.Error else DesignV2Tokens.Colors.TextTertiary,
                ),
            )
        }
    }
}