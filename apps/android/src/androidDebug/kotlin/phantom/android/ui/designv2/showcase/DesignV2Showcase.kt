// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2.showcase

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import phantom.android.R
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2FontMono
import phantom.android.ui.designv2.DesignV2Tokens
import phantom.android.ui.designv2.components.PhantomAvatar
import phantom.android.ui.designv2.components.PhantomBadge
import phantom.android.ui.designv2.components.PhantomButton
import phantom.android.ui.designv2.components.PhantomButtonVariant
import phantom.android.ui.designv2.components.PhantomFilterChip
import phantom.android.ui.designv2.components.PhantomInput
import phantom.android.ui.designv2.components.PhantomSegmentedControl
import phantom.android.ui.designv2.components.PhantomToggle

/**
 * DesignV2 component showcase — lives in the debug variant only.
 *
 * Purpose:
 *   - Reference gallery so a live debug APK can preview components as
 *     they evolve.
 *   - Deterministic composable inputs for Paparazzi matrix goldens in
 *     `androidUnitTest`. Tests reference these composables so a change
 *     to a matrix cell is a single-file edit.
 *
 * Layout convention:
 *   - Each matrix is a `Column` wrapped in a `DesignV2SurfaceFrame` that
 *     supplies the app canvas background so PNG diffs are stable.
 *   - Every cell has an inline caption above it (JetBrains Mono, TextTertiary)
 *     so the golden is self-documenting when a reviewer opens the PNG.
 *
 * NOT in production APK: this file lives in `src/androidDebug/kotlin/`
 * which AGP includes only in the debug variant. The corresponding release
 * build has no reference to any showcase composable, and no R.drawable
 * reference to these composables exists in release code.
 */

// ── Test helpers ───────────────────────────────────────────────────────────
// `MutableInteractionSource()` uses `replay=0`, so pre-emitting a Press or
// Focus before Compose collects is a lost message. These pre-loaded variants
// use `replay=8` so `collectIsPressedAsState()` / `collectIsFocusedAsState()`
// pick up the buffered emission on first collect — deterministic input for
// Paparazzi's one-shot render.

/** Interaction source that always reports pressed=true. */
class ForcedPressedInteractionSource : MutableInteractionSource {
    private val flow = MutableSharedFlow<Interaction>(
        replay = 8,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).apply { tryEmit(PressInteraction.Press(Offset.Zero)) }
    override val interactions: Flow<Interaction> = flow.asSharedFlow()
    override suspend fun emit(interaction: Interaction) = flow.emit(interaction)
    override fun tryEmit(interaction: Interaction): Boolean = flow.tryEmit(interaction)
}

/** Interaction source that always reports focused=true. */
class ForcedFocusedInteractionSource : MutableInteractionSource {
    private val flow = MutableSharedFlow<Interaction>(
        replay = 8,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).apply { tryEmit(FocusInteraction.Focus()) }
    override val interactions: Flow<Interaction> = flow.asSharedFlow()
    override suspend fun emit(interaction: Interaction) = flow.emit(interaction)
    override fun tryEmit(interaction: Interaction): Boolean = flow.tryEmit(interaction)
}

// ── Frame ──────────────────────────────────────────────────────────────────

private val CanvasBg = DesignV2Tokens.Colors.Background

@Composable
fun DesignV2SurfaceFrame(
    width: Int = 360,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(CanvasBg)
            .padding(16.dp)
            .width(width.dp),
    ) {
        content()
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        color = DesignV2Tokens.Colors.TextTertiary,
        style = TextStyle(
            fontFamily = DesignV2FontMono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 14.sp,
        ),
    )
}

@Composable
private fun Row(caption: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Caption(caption)
        Spacer(Modifier.height(6.dp))
        content()
        Spacer(Modifier.height(16.dp))
    }
}

// ── Button matrix — 3 variants × 4 states = 12 cells ────────────────────────

@Composable
fun ShowcaseButtonMatrix() {
    DesignV2SurfaceFrame(width = 360) {
        Column {
            listOf(
                "Primary"   to PhantomButtonVariant.Primary,
                "Secondary" to PhantomButtonVariant.Secondary,
                "Ghost"     to PhantomButtonVariant.Ghost,
            ).forEach { (label, variant) ->
                Row("$label / normal") {
                    PhantomButton(text = "Continue", onClick = {}, variant = variant)
                }
                Row("$label / focused") {
                    PhantomButton(
                        text = "Continue",
                        onClick = {},
                        variant = variant,
                        interactionSource = remember { ForcedFocusedInteractionSource() },
                    )
                }
                Row("$label / pressed") {
                    PhantomButton(
                        text = "Continue",
                        onClick = {},
                        variant = variant,
                        interactionSource = remember { ForcedPressedInteractionSource() },
                    )
                }
                Row("$label / disabled") {
                    PhantomButton(
                        text = "Continue",
                        onClick = {},
                        variant = variant,
                        enabled = false,
                    )
                }
            }
        }
    }
}

// ── Input matrix — 5 cells (default, focused, error, disabled, mono) ────────

@Composable
fun ShowcaseInputMatrix() {
    DesignV2SurfaceFrame(width = 360) {
        Column {
            Row("Default (empty)") {
                PhantomInput(
                    value = "",
                    onValueChange = {},
                    placeholder = "Username or identity key",
                )
            }
            Row("Focused") {
                val src = remember { ForcedFocusedInteractionSource() }
                // The Input component holds its own MutableInteractionSource — for
                // showcase we render with a value to make focus visually obvious
                // instead of pre-emitting focus (Input does not expose its source).
                PhantomInput(
                    value = "@alice",
                    onValueChange = {},
                    placeholder = "Username or identity key",
                )
                // note the focused variant relies on caret + border; a matching
                // Paparazzi golden is captured via a Focus-emitting wrapper in
                // the test file.
            }
            Row("Error") {
                PhantomInput(
                    value = "@invalid#name",
                    onValueChange = {},
                    isError = true,
                    helperText = "Only letters, digits and underscore",
                )
            }
            Row("Disabled") {
                PhantomInput(
                    value = "@alice",
                    onValueChange = {},
                    enabled = false,
                )
            }
            Row("Mono (identity key)") {
                PhantomInput(
                    value = "ed25519:9f3a8b7d",
                    onValueChange = {},
                    useMonoFont = true,
                )
            }
        }
    }
}

// ── Toggle matrix — 4 cells (on / off / disabled-on / disabled-off) ─────────

@Composable
fun ShowcaseToggleMatrix() {
    DesignV2SurfaceFrame(width = 240) {
        Column {
            Row("On") {
                PhantomToggle(checked = true, onCheckedChange = {})
            }
            Row("Off") {
                PhantomToggle(checked = false, onCheckedChange = {})
            }
            Row("Disabled / on") {
                PhantomToggle(checked = true, onCheckedChange = {}, enabled = false)
            }
            Row("Disabled / off") {
                PhantomToggle(checked = false, onCheckedChange = {}, enabled = false)
            }
        }
    }
}

// ── Segmented control matrix — 2 cells (2-tab, 3-tab) ───────────────────────

@Composable
fun ShowcaseSegmentedMatrix() {
    DesignV2SurfaceFrame(width = 360) {
        Column {
            Row("2 segments, index=0") {
                PhantomSegmentedControl(
                    options = listOf("List", "Grid"),
                    selectedIndex = 0,
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row("3 segments, index=1") {
                PhantomSegmentedControl(
                    options = listOf("All", "Incoming", "Missed"),
                    selectedIndex = 1,
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row("3 segments, index=2") {
                PhantomSegmentedControl(
                    options = listOf("Standard", "Private", "Ghost"),
                    selectedIndex = 2,
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ── FilterChip matrix — selected/unselected + row of chips ──────────────────

@Composable
fun ShowcaseFilterChipMatrix() {
    DesignV2SurfaceFrame(width = 360) {
        Column {
            Row("Unselected") {
                PhantomFilterChip(text = "All", selected = false, onSelectedChange = {})
            }
            Row("Selected") {
                PhantomFilterChip(text = "Incoming", selected = true, onSelectedChange = {})
            }
            Row("Row (typical call filter)") {
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhantomFilterChip(text = "All", selected = true, onSelectedChange = {})
                    PhantomFilterChip(text = "Incoming", selected = false, onSelectedChange = {})
                    PhantomFilterChip(text = "Missed", selected = false, onSelectedChange = {})
                }
            }
        }
    }
}

// ── Avatar matrix — 4 sizes × (plain / online / verified) = 12 cells ────────

@Composable
fun ShowcaseAvatarMatrix() {
    DesignV2SurfaceFrame(width = 360) {
        Column {
            listOf(32.dp, 40.dp, 48.dp, 64.dp).forEach { size ->
                Row("${size.value.toInt()}dp plain / online / verified") {
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhantomAvatar(
                            initials = "PA",
                            size = size,
                            gradient = Color(0xFF00D4FF) to Color(0xFF7C3AED),
                        )
                        PhantomAvatar(
                            initials = "PA",
                            size = size,
                            gradient = Color(0xFF00D4FF) to Color(0xFF7C3AED),
                            online = true,
                        )
                        PhantomAvatar(
                            initials = "PA",
                            size = size,
                            gradient = Color(0xFF00D4FF) to Color(0xFF7C3AED),
                            online = true,
                            verified = true,
                        )
                    }
                }
            }
            Row("Flat fill (no gradient), 48dp") {
                PhantomAvatar(initials = "SB", size = 48.dp)
            }
        }
    }
}

// ── Badge matrix — count 1/3/12/99+/dot ────────────────────────────────────

@Composable
fun ShowcaseBadgeMatrix() {
    DesignV2SurfaceFrame(width = 240) {
        Column {
            listOf(1, 3, 12, 99, 250).forEach { n ->
                Row("count=$n") {
                    PhantomBadge(count = n)
                }
            }
            Row("dotOnly") {
                PhantomBadge(dotOnly = true)
            }
        }
    }
}

// ── Icon contact sheet — all 21 ic_dv2_* drawables in a grid ───────────────

/**
 * Contact-sheet golden of all dv2 vector drawables. Catches
 * regressions in the SVG → AVD conversion pipeline: clipped viewports,
 * blank paths, primitive-to-pathData translation errors.
 */
val Dv2Icons: List<Pair<String, Int>> = listOf(
    "add"              to R.drawable.ic_dv2_add,
    "alert"            to R.drawable.ic_dv2_alert,
    "archive"          to R.drawable.ic_dv2_archive,
    "attach"           to R.drawable.ic_dv2_attach,
    "back"             to R.drawable.ic_dv2_back,
    "calls"            to R.drawable.ic_dv2_calls,
    "close"            to R.drawable.ic_dv2_close,
    "confirm"          to R.drawable.ic_dv2_confirm,
    "delete_msg"       to R.drawable.ic_dv2_delete_msg,
    "edit"             to R.drawable.ic_dv2_edit,
    "encrypted"        to R.drawable.ic_dv2_encrypted,
    "menu_h"           to R.drawable.ic_dv2_menu_h,
    "messages"         to R.drawable.ic_dv2_messages,
    "mic_on"           to R.drawable.ic_dv2_mic_on,
    "nearby"           to R.drawable.ic_dv2_nearby,
    "pin"              to R.drawable.ic_dv2_pin,
    "reaction"         to R.drawable.ic_dv2_reaction,
    "search"           to R.drawable.ic_dv2_search,
    "send"             to R.drawable.ic_dv2_send,
    "settings"         to R.drawable.ic_dv2_settings,
    "verified_shield"  to R.drawable.ic_dv2_verified_shield,
)

@Composable
fun ShowcaseIconContactSheet() {
    // Non-lazy grid: Paparazzi's one-shot render can't drive LazyVerticalGrid
    // to compose all items in a single frame, and we WANT every icon rendered
    // so the golden PNG catches per-icon regressions. Manual 4-column layout.
    val columns = 4
    val rows = Dv2Icons.chunked(columns)
    DesignV2SurfaceFrame(width = 360) {
        Column(modifier = Modifier.fillMaxWidth()) {
            rows.forEach { rowItems ->
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    rowItems.forEach { (name, resId) ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(DesignV2Tokens.Colors.Surface)
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(resId),
                                    contentDescription = null,
                                    tint = DesignV2Tokens.Colors.Cyan,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = name,
                                color = DesignV2Tokens.Colors.TextTertiary,
                                style = TextStyle(
                                    fontFamily = DesignV2FontMono,
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                ),
                            )
                        }
                    }
                    // Pad the last row so column widths stay uniform.
                    if (rowItems.size < columns) {
                        repeat(columns - rowItems.size) {
                            Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// ── Stress golden — narrow width + long strings ────────────────────────────

/**
 * Stress composable — narrow width forces truncation / wrap decisions;
 * long strings verify no unexpected clipping; combined with a large
 * `fontScale` at the Paparazzi test site (see the stress test) it also
 * checks accessibility scaling.
 */
@Composable
fun ShowcaseStress() {
    DesignV2SurfaceFrame(width = 220) {
        Column {
            Row("Long primary button label") {
                PhantomButton(
                    text = "Verify your identity key first before sending",
                    onClick = {},
                )
            }
            Row("Input, long placeholder") {
                PhantomInput(
                    value = "",
                    onValueChange = {},
                    placeholder = "Enter a username, identity key, or paste a full ed25519 fingerprint",
                )
            }
            Row("Input, long value + error") {
                PhantomInput(
                    value = "ed25519:9f3a8b7d5c1e4f8a2b6d9e7c0a3f5b8d1e4c7a9",
                    onValueChange = {},
                    isError = true,
                    helperText = "Fingerprint has 62 hex characters; you pasted 39.",
                    useMonoFont = true,
                )
            }
            Row("Filter chip row (overflow)") {
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PhantomFilterChip("Verified only", true, {})
                    PhantomFilterChip("Recent contacts", false, {})
                }
            }
            Row("Segmented, cramped") {
                PhantomSegmentedControl(
                    options = listOf("Standard", "Private", "Ghost Mode"),
                    selectedIndex = 2,
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
