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

// ── Button matrix — 3 variants × 4 states, split into 2 goldens ─────────────
//
// Round-3 REDLINE P1-3: the previous single `ShowcaseButtonMatrix`
// composable produced 12 cells stacked vertically (~72 dp each with
// caption + spacing), which overflowed the Pixel 5 viewport and
// Paparazzi's ~1000 px PNG-height ceiling. Ghost/pressed and
// Ghost/disabled were silently clipped (independent pixel-scan confirmed
// active pixels at y=999). Split into two halves matching the icon-audit
// pattern:
//
//   ShowcaseButtonMatrixA — Primary + Secondary (8 cells, ~576 dp content)
//   ShowcaseButtonMatrixB — Ghost                (4 cells, ~288 dp content)
//
// Both fit comfortably in Paparazzi's viewport. The single legacy
// `ShowcaseButtonMatrix` is kept as a compatibility alias that calls
// ShowcaseButtonMatrixA so the pre-existing PhantomButtonSnapshotTest
// entry point does not silently vanish; the accompanying snapshot test
// bumps to two methods to cover both halves.

private val ButtonVariantsA: List<Pair<String, PhantomButtonVariant>> = listOf(
    "Primary"   to PhantomButtonVariant.Primary,
    "Secondary" to PhantomButtonVariant.Secondary,
)

private val ButtonVariantsB: List<Pair<String, PhantomButtonVariant>> = listOf(
    "Ghost"     to PhantomButtonVariant.Ghost,
)

@Composable
private fun ButtonMatrixForVariants(
    variants: List<Pair<String, PhantomButtonVariant>>,
) {
    DesignV2SurfaceFrame(width = 360) {
        Column {
            variants.forEach { (label, variant) ->
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

@Composable
fun ShowcaseButtonMatrixA() = ButtonMatrixForVariants(ButtonVariantsA)

@Composable
fun ShowcaseButtonMatrixB() = ButtonMatrixForVariants(ButtonVariantsB)

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

// ── Input slots matrix — 4 cells (leading / trailing / both / error-vs-trailing) ─

@Composable
fun ShowcaseInputSlotsMatrix() {
    DesignV2SurfaceFrame(width = 360) {
        Column {
            Row("Leading only (@ prefix)") {
                PhantomInput(
                    value = "alice",
                    onValueChange = {},
                    useMonoFont = true,
                    leadingContent = {
                        Text(
                            text = "@",
                            color = DesignV2Tokens.Colors.TextTertiary,
                            style = TextStyle(
                                fontFamily = DesignV2FontMono,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal,
                            ),
                        )
                    },
                )
            }
            Row("Trailing only (status icon)") {
                PhantomInput(
                    value = "alice",
                    onValueChange = {},
                    useMonoFont = true,
                    trailingContent = {
                        Icon(
                            painter = painterResource(R.drawable.ic_dv2_confirm),
                            contentDescription = null,
                            tint = DesignV2Tokens.Colors.Success,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
            Row("Both slots (username field)") {
                PhantomInput(
                    value = "alice",
                    onValueChange = {},
                    useMonoFont = true,
                    helperText = "Handle format is valid.",
                    leadingContent = {
                        Text(
                            text = "@",
                            color = DesignV2Tokens.Colors.TextTertiary,
                            style = TextStyle(
                                fontFamily = DesignV2FontMono,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal,
                            ),
                        )
                    },
                    trailingContent = {
                        Icon(
                            painter = painterResource(R.drawable.ic_dv2_confirm),
                            contentDescription = null,
                            tint = DesignV2Tokens.Colors.Success,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
            Row("Error + custom trailing (block icon replaces alert)") {
                PhantomInput(
                    value = "ab",
                    onValueChange = {},
                    isError = true,
                    helperText = "A little longer — at least 3 characters.",
                    useMonoFont = true,
                    leadingContent = {
                        Text(
                            text = "@",
                            color = DesignV2Tokens.Colors.TextTertiary,
                            style = TextStyle(
                                fontFamily = DesignV2FontMono,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal,
                            ),
                        )
                    },
                    trailingContent = {
                        Icon(
                            painter = painterResource(R.drawable.ic_dv2_block),
                            contentDescription = null,
                            tint = DesignV2Tokens.Colors.Error,
                            modifier = Modifier.size(18.dp),
                        )
                    },
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

/**
 * The 10 net-new Onboarding-track drawables added in Commit 1 of the
 * Onboarding redesign. Kept separate from [Dv2Icons] so the Onboarding audit
 * golden focuses on just the new surface (10 rows × ~72dp ≈ 720dp, well
 * under Paparazzi's ~1000px PNG ceiling — one golden covers them all).
 *
 * Any post-Commit-1 addition to the Onboarding icon set MUST land here so
 * the audit golden catches regressions in the converter v3 dashing bake-in
 * and per-primitive fill/stroke resolution.
 */
val Dv2OnboardingIcons: List<Pair<String, Int>> = listOf(
    "block"            to R.drawable.ic_dv2_block,
    "copy"             to R.drawable.ic_dv2_copy,
    "ed25519_key"      to R.drawable.ic_dv2_ed25519_key,
    "ghost"            to R.drawable.ic_dv2_ghost,
    "ghost_signal"     to R.drawable.ic_dv2_ghost_signal,
    "notify_off"       to R.drawable.ic_dv2_notify_off,
    "notify_on"        to R.drawable.ic_dv2_notify_on,
    "phantom_premium"  to R.drawable.ic_dv2_phantom_premium,
    "privacy"          to R.drawable.ic_dv2_privacy,
    "standard"         to R.drawable.ic_dv2_standard,
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

// ── Icon side-by-side audit — every icon at 24dp AND 48dp ─────────────────

/**
 * Audit view catching SVG→AVD conversion regressions that only surface at
 * different scale. Each row: icon name (mono, small) | 24dp render on
 * Surface | 48dp render on Surface. Bug that hides at 24dp (a mis-scaled
 * stroke that reads as "1px difference") explodes visibly at 48dp.
 *
 * NOT redundant with `ShowcaseIconContactSheet` — that one shows every icon
 * at ONE scale in a compact grid; this one is the diagnostic view.
 *
 * Split into two halves because 21 rows × ~72dp = 1512dp of vertical content,
 * which overflows Paparazzi's default Pixel 5 viewport (~891dp) AND its
 * ~1000px PNG-height ceiling. Each half fits comfortably; the split is on
 * `Dv2Icons` alphabetical order.
 */
@Composable
fun ShowcaseIconAudit(icons: List<Pair<String, Int>>) {
    DesignV2SurfaceFrame(width = 300) {
        Column(modifier = Modifier.fillMaxWidth()) {
            icons.forEach { (name, resId) ->
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = name,
                        color = DesignV2Tokens.Colors.TextTertiary,
                        style = TextStyle(
                            fontFamily = DesignV2FontMono,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                        ),
                        modifier = Modifier.width(120.dp),
                    )
                    // 24dp cell (production render size for most usages).
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(DesignV2Tokens.Colors.Surface)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(resId),
                            contentDescription = null,
                            tint = DesignV2Tokens.Colors.Cyan,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    // 48dp cell (magnifies sub-pixel conversion errors).
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(DesignV2Tokens.Colors.Surface)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(resId),
                            contentDescription = null,
                            tint = DesignV2Tokens.Colors.Cyan,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Onboarding V2 — full-screen previews (Commit 2 of Onboarding track) ───

/**
 * Full-screen composables for the Onboarding V2 flow's real steps
 * (Welcome + How) plus the preserved Terms gate. Rendered at the actual
 * Pixel-5 viewport (411 dp × 891 dp) so the goldens capture the same
 * layout the user will see.
 *
 * All three go through [phantom.android.screens.onboarding.v2.OnboardingV2HostFrame]
 * — the SAME frame the runtime uses — with a hard-coded
 * `topInset = 24.dp` matching the Pixel 5 status bar height. That way
 * the Paparazzi golden reflects the on-device layout including the
 * status-bar area (P1-2 REDLINE fix: showcase previously skipped
 * `windowInsetsPadding` entirely and produced goldens whose Back-pill
 * position diverged from the on-device runtime by the status-bar
 * height).
 *
 * Terms is a pre-flow gate that lives outside the frame (its own
 * status-bar padding via `windowInsetsPadding(WindowInsets.statusBars)`
 * in TermsScreenV2 itself — which returns zero in Paparazzi, so the
 * golden matches "no chrome" render; that's OK because Terms is
 * preserved from the old flow and its layout has been previously
 * approved).
 *
 * Stubs (Identity / Privacy / Permissions / FinaleConfirmation) are NOT
 * showcased here — their placeholder body is uninteresting for a
 * golden; they land as real goldens in Commits 3-5.
 */

private const val SHOWCASE_STATUS_BAR_INSET_DP = 24  // Pixel 5 default

@Composable
fun ShowcaseOnboardingTermsV2() {
    // Round-3 REDLINE P2-4: pass the same hard-coded status-bar inset
    // to Terms as we do to the other steps (Welcome / How via the host
    // frame). Runtime computes the real inset;
    // Paparazzi (this showcase) receives zero from `WindowInsets.statusBars`
    // so we inject 24.dp to match the Pixel 5 device profile.
    Box(modifier = Modifier.fillMaxWidth()) {
        phantom.android.screens.onboarding.v2.TermsScreenV2(
            onAccept = {},
            topInset = SHOWCASE_STATUS_BAR_INSET_DP.dp,
        )
    }
}

@Composable
fun ShowcaseOnboardingWelcomeV2() {
    // Uses the SAME host frame as the runtime, with a hard-coded status
    // bar inset (see class KDoc). currentStep=Welcome → chrome hidden.
    phantom.android.screens.onboarding.v2.OnboardingV2HostFrame(
        currentStep = phantom.android.screens.onboarding.v2.OnboardingStepV2.Welcome,
        topInset = SHOWCASE_STATUS_BAR_INSET_DP.dp,
        onBackClick = {},
        edgeSwipeBackEnabled = false,
        onEdgeSwipeBack = {},
        toastMessage = null,
        onToastDismiss = {},
    ) {
        phantom.android.screens.onboarding.v2.steps.WelcomeStepV2(onContinueClick = {})
    }
}

@Composable
fun ShowcaseOnboardingHowV2() {
    // currentStep=How → frame renders "STEP 1 OF 4" top bar. Step dots
    // at position 0 are rendered INSIDE HowStepV2 above its CTA per
    // REDLINE P1-3 (not a frame-level overlay).
    phantom.android.screens.onboarding.v2.OnboardingV2HostFrame(
        currentStep = phantom.android.screens.onboarding.v2.OnboardingStepV2.How,
        topInset = SHOWCASE_STATUS_BAR_INSET_DP.dp,
        onBackClick = {},
        edgeSwipeBackEnabled = true,
        onEdgeSwipeBack = {},
        toastMessage = null,
        onToastDismiss = {},
    ) {
        phantom.android.screens.onboarding.v2.steps.HowStepV2(
            dotsIndex = phantom.android.screens.onboarding.v2.OnboardingStepV2.How.dotsIndex,
            onContinueClick = {},
        )
    }
}

// ── Stress goldens — narrow width + long strings, split A + B ──────────────
//
// Round-3 REDLINE P1-3: previous single `ShowcaseStress` composable's
// last row ("Segmented, cramped") landed on y=999 of the PNG under
// fontScale=2.0 — content-touched the encoder ceiling. Split into two
// halves so each fits safely inside Paparazzi's viewport.
//
// A: first three text-heavy rows (Long button, Long placeholder,
//    Long value + error) — the ones whose fontScale-2.0 growth is
//    most likely to overflow.
// B: remaining two chip / segmented rows.

@Composable
fun ShowcaseStressA() {
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
        }
    }
}

@Composable
fun ShowcaseStressB() {
    DesignV2SurfaceFrame(width = 220) {
        Column {
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
