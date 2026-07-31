// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import phantom.android.R
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2FontMono
import phantom.android.ui.designv2.DesignV2Tokens

/**
 * OnboardingV2 chrome — shared frame elements around the step content.
 *
 * Four composables merged into one file per architect ask A1 (2026-08-01):
 * "объединить мелкие chrome-компоненты в OnboardingChrome.kt". Each is small
 * and thematically related (rendering the frame around the step body); a
 * one-file-per-composable split would push the module past the 10-14 file
 * target without adding readability.
 *
 * Contents:
 *   - [OnboardingCipherBackground] — drifting hex columns behind everything.
 *   - [OnboardingTopBarV2]         — Back pill + "STEP N OF 4" label.
 *   - [OnboardingStepDotsV2]       — 4 dots at the bottom (elongated selected).
 *   - [OnboardingToastV2]          — 2-second auto-dismiss overlay.
 *
 * All values sourced from handoff `Onboarding.dc.html` inline styles + the
 * REDLINE amendments (2026-08-01 §§C1..C4).
 */

// ── 1. Cipher background ─────────────────────────────────────────────────────

/**
 * Ambient background — three independent drifting cipher streams,
 * masked by an elliptical alpha layer that reduces the cipher's
 * visibility from a modest 55 % at the center-upper focal point to
 * 0 % at 72 % of the mask radius. Handoff `Onboarding.dc.html` §345.
 *
 * Round-5 REDLINE P1-1 rewrite. Previous round-4 shape used a single
 * 58 s animation with static phase offsets (`12/58`, `26/58`); the
 * handoff instead defines THREE independent animations at 58 s / 74 s
 * / 64 s with delays 0 / -12 s / -26 s. Phase fractions are therefore
 * `0/58`, `12/74`, `26/64` respectively, and the animations advance
 * independently — after time t the columns are at fractional phases
 * `(t)/58`, `(t+12)/74`, `(t+26)/64`, which is what three separate
 * `animateFloat` calls with the correct periods produce.
 *
 * Mask: previously we painted a SurfaceDeep overlay with alpha
 * 0 → 0.85 gradient — that added DARK on top of the cipher, changing
 * its perceived colour. Handoff uses an alpha MASK applied to the
 * cipher content (offscreen layer + `BlendMode.DstIn`) with stops
 * (0 % → 0.55 alpha, 72 % → 0 alpha). The result: cipher shows at
 * a max of 55 % of its own alpha near the mask centre and fades to
 * complete transparency at the mask boundary — no dark tint added.
 *
 * Font: fontSize = 11 sp (was 12 sp); vertical rhythm 27 px between
 * line starts = 11 sp font + 16 px CSS gap. Both values from the
 * handoff source.
 *
 * Paparazzi renders at t=0 so the golden captures the initial static
 * frame — on device the three columns drift with independent periods.
 *
 * Not decorative in the a11y sense: this is background chrome and does
 * not need a contentDescription (there's no reachable node at all — we
 * draw directly to the canvas). Screen readers skip it entirely.
 */
@Composable
fun OnboardingCipherBackground(
    modifier: Modifier = Modifier,
) {
    // Round-5 REDLINE P1-1: three independent animations at the
    // handoff periods 58 s / 74 s / 64 s. Phase offsets applied to
    // each stream individually via `(progress + delay/period) % 1f`.
    val transition = rememberInfiniteTransition(label = "cipher-drift")
    val progressCol1 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 58_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "drift-58s",
    )
    val progressCol2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 74_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "drift-74s",
    )
    val progressCol3 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 64_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "drift-64s",
    )

    // Single shared 26-line pool per handoff.
    val cipherLines = remember { generateCipherLines(count = 26, seed = 0x5EEDL) }

    val textMeasurer = rememberTextMeasurer()
    // Round-5 REDLINE P1-1: font-size = 11 sp per handoff (was 12).
    val styleColumn1 = TextStyle(
        fontFamily = DesignV2FontMono,
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal,
        color = DesignV2Tokens.Colors.Cyan.copy(alpha = 0.11f),
        letterSpacing = 1.5.sp,
    )
    val styleColumn2 = styleColumn1.copy(
        color = DesignV2Tokens.Colors.Cyan.copy(alpha = 0.08f),
    )
    val styleColumn3 = styleColumn1.copy(
        color = DesignV2Tokens.Colors.Cyan.copy(alpha = 0.10f),
    )

    // Round-6 REDLINE P1-2: density-safe line pitch. Previously
    // `lineHeightPx = 27f` mixed sp text with a raw physical-pixel
    // step — on a 3x device 27 px is much smaller than the intended
    // spacing. Derive the pitch from the ACTUAL measured text height
    // (which honours density × fontScale) plus a density-converted
    // 16 dp gap. All three columns share the same text metrics
    // (identical style except colour/alpha), so measuring `styleColumn1`
    // is sufficient.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cipherPitchPx = remember(density.density, density.fontScale, styleColumn1) {
        val sampleLayout = textMeasurer.measure(
            "XXXX XXXX  XXXX XXXX  XXXX",
            style = styleColumn1,
        )
        val gapPx = with(density) { 16.dp.toPx() }
        sampleLayout.size.height.toFloat() + gapPx
    }

    // Round-5 REDLINE P1-1: draw cipher onto an offscreen layer so the
    // subsequent alpha mask (BlendMode.DstIn) applies only to the
    // cipher content, not to the underlying background — otherwise
    // DstIn would clear the SurfaceDeep colour along with the cipher.
    //
    // Structure:
    //   Outer Box:
    //     - fills viewport
    //     - background = SurfaceDeep (drawn OUTSIDE the layer)
    //   Inner Box:
    //     - graphicsLayer(compositingStrategy = Offscreen) → own layer
    //     - drawWithContent draws cipher, then draws mask with DstIn
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DesignV2Tokens.Colors.SurfaceDeep),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawBehind {
                    // Cipher: three independent-period streams.
                    // Delays 0 / -12 / -26 s divided by their own
                    // periods 58 / 74 / 64 s give phase fractions
                    // 0.000 / 12/74 ≈ 0.162 / 26/64 ≈ 0.406.
                    drawCipherColumn(
                        lines = cipherLines,
                        drift = progressCol1,
                        phaseOffsetFraction = 0.000f,
                        columnXFraction = -0.06f,
                        lineHeightPx = cipherPitchPx,
                        measurer = textMeasurer,
                        style = styleColumn1,
                    )
                    drawCipherColumn(
                        lines = cipherLines,
                        drift = progressCol2,
                        phaseOffsetFraction = 12f / 74f,
                        columnXFraction = 0.34f,
                        lineHeightPx = cipherPitchPx,
                        measurer = textMeasurer,
                        style = styleColumn2,
                    )
                    drawCipherColumn(
                        lines = cipherLines,
                        drift = progressCol3,
                        phaseOffsetFraction = 26f / 64f,
                        columnXFraction = 0.72f,
                        lineHeightPx = cipherPitchPx,
                        measurer = textMeasurer,
                        style = styleColumn3,
                    )
                    // Alpha mask via DstIn: dst = cipher content just
                    // drawn; src = the mask brush; result = dst pixels
                    // with alpha multiplied by src alpha. Cipher is
                    // 55 % visible at centre, 0 % at 72 % radius, 0
                    // beyond (extrapolated).
                    drawEllipticalAlphaMask()
                },
        )
    }
}

private fun DrawScope.drawCipherColumn(
    lines: List<String>,
    drift: Float,
    phaseOffsetFraction: Float,
    columnXFraction: Float,
    lineHeightPx: Float,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
) {
    val poolHeightPx = lines.size * lineHeightPx
    // drift + phase offset, wrapped so the loop is seamless.
    val combined = (drift + phaseOffsetFraction) % 1f
    val yShift = combined * poolHeightPx
    val columnX = size.width * columnXFraction
    // Render 2 pool cycles so wraparound is invisible.
    for (cycle in 0..1) {
        val baseY = -yShift + cycle * poolHeightPx
        lines.forEachIndexed { i, line ->
            val y = baseY + i * lineHeightPx
            if (y > -lineHeightPx && y < size.height + lineHeightPx) {
                val layout = measurer.measure(line, style)
                // Left-anchor the line at columnX (may extend past
                // the right edge for column 3; that's the intended
                // trailing-off effect from the handoff).
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(columnX, y),
                )
            }
        }
    }
}

private fun DrawScope.drawEllipticalAlphaMask() {
    val cx = size.width * 0.5f
    val cy = size.height * 0.42f
    // Round-6 REDLINE P1-1: the CSS `radial-gradient(120% 78% ...)`
    // percentages are RADII, not diameters (per CSS Images Level 3
    // §Radial Gradients). Round-5 shape halved them to 60% × 39%,
    // producing a mask half the intended size and squeezing the
    // cipher into a narrow central patch. Corrected radii:
    //   ellipseRx = width  × 1.20  (extends 20% past the viewport
    //                                horizontally on each side)
    //   ellipseRy = height × 0.78  (fades to alpha=0 at 72% × 0.78
    //                                = 56% of height from centre)
    // The radial gradient's `radius` value refers to the position
    // in the scaled coord system at which stop 100% is reached;
    // `withTransform { scale(1, ry/rx, pivot=centre) }` stretches
    // the circular gradient into an ellipse of the desired shape.
    val ellipseRx = size.width * 1.20f
    val ellipseRy = size.height * 0.78f
    withTransform({
        scale(scaleX = 1f, scaleY = ellipseRy / ellipseRx, pivot = Offset(cx, cy))
    }) {
        // Alpha mask: opaque (alpha = 0.55) at centre, transparent
        // (alpha = 0) at 72 % of the ellipse radius. Beyond 72 % the
        // last stop is extrapolated → stays at alpha 0. Colour of
        // the stops is irrelevant when combined via BlendMode.DstIn
        // (only alpha matters); using pure white for clarity.
        //
        // Overscan must be large enough to cover the entire viewport
        // after the scale transform (scaleY > 1 in portrait means a
        // screen y=0 maps back to a smaller scaled y, so we oversize
        // the drawn rect vertically to cover any residual space).
        val overscanTop = size.height * 2f
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to Color.White.copy(alpha = 0.55f),
                    0.72f to Color.White.copy(alpha = 0.00f),
                ),
                center = Offset(cx, cy),
                radius = ellipseRx,
            ),
            blendMode = BlendMode.DstIn,
            topLeft = Offset(0f, -overscanTop),
            size = Size(size.width, size.height + overscanTop * 2f),
        )
    }
}

// Deterministic 5-quartet cipher-line generator. Each line is
// `XXXX XXXX  XXXX XXXX  XXXX` (single space between quartets 1-2 and
// 3-4; double space between 2-3 and 4-5) per handoff Onboarding.dc.html.
private fun generateCipherLines(count: Int, seed: Long): List<String> {
    val rng = java.util.Random(seed)
    fun quartet(): String {
        val sb = StringBuilder(4)
        repeat(4) {
            val n = rng.nextInt(16)
            sb.append(if (n < 10) ('0' + n) else ('A' + (n - 10)))
        }
        return sb.toString()
    }
    return List(count) {
        "${quartet()} ${quartet()}  ${quartet()} ${quartet()}  ${quartet()}"
    }
}

// ── 2. Top bar — Back pill + STEP N OF 4 label ──────────────────────────────

/**
 * Top bar: 40 dp tall row. Back pill on the left (Modifier.clickable → back
 * navigation), "STEP N OF 4" label on the right in JBMono. Both hidden on
 * Welcome (per handoff `Onboarding.dc.html` ambient scene) — the caller
 * decides not to render this composable when the step is Welcome; this
 * composable itself is unconditional.
 */
@Composable
fun OnboardingTopBarV2(
    stepNumber: Int,
    totalSteps: Int,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Back pill (round-2 REDLINE P2-4 a11y). Semantic contract:
        //   - Modifier.clickable carries `role = Role.Button` +
        //     `onClickLabel = "Back"` directly — the single node created
        //     by Compose for the clickable target owns the role, the
        //     announced label, AND the click action. A separate
        //     Modifier.semantics(mergeDescendants=true) { role = Button }
        //     is unnecessary here and would produce two nodes advertising
        //     the same button; the clickable-native form is the
        //     idiomatic one-node shape a screen reader expects.
        //   - The chevron Icon is decorative (contentDescription=null).
        //   - The visible "Back" Text is redundant with the a11y label
        //     but stays for sighted users; text-merge inside the
        //     clickable-owned semantics tree collapses everything into
        //     the same node.
        // Pinned by `OnboardingV2SemanticsTest.back_pill_has_role_label_and_click_action`.
        Row(
            modifier = Modifier
                // Round-3 REDLINE P2-5: pill shape (fully rounded)
                // per handoff CSS `border-radius: 999px` on a 36 px
                // pill. `RoundedCornerShape(percent = 50)` produces a
                // 50%-of-shorter-side corner radius, i.e. a full pill
                // for any aspect-ratio ≥ 1:1.
                .clip(RoundedCornerShape(percent = 50))
                .background(DesignV2Tokens.Colors.Surface)
                .border(1.dp, DesignV2Tokens.Colors.Border, RoundedCornerShape(percent = 50))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Back",
                    onClick = onBackClick,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_dv2_back),
                contentDescription = null,
                tint = DesignV2Tokens.Colors.TextSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Back",
                color = DesignV2Tokens.Colors.TextSecondary,
                style = TextStyle(
                    fontFamily = DesignV2FontBody,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Normal,
                ),
            )
        }

        Text(
            text = "STEP $stepNumber OF $totalSteps",
            color = DesignV2Tokens.Colors.TextQuaternary,
            style = TextStyle(
                fontFamily = DesignV2FontMono,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 2.4.sp,   // handoff .22em ≈ 2.4sp at 10.5 sp
            ),
        )
    }
}

// ── 3. Step dots — 4 dots, elongated selected ───────────────────────────────

/**
 * Bottom step indicator: 4 dots, current one elongated 24 dp cyan, past dots
 * cyan-dark #2A6F80, future dots grey #2A2F38. `dotsIndex` is 0..3;
 * `dotsIndex=-1` renders nothing (used for Welcome + FinaleConfirmation).
 *
 * Dots are NON-INTERACTIVE — no clickable, no Role.Button. Screen readers
 * skip them; navigation is controlled solely by Back pill + Continue CTA
 * per architect lock (2026-08-01 §C4).
 */
@Composable
fun OnboardingStepDotsV2(
    dotsIndex: Int,
    totalDots: Int = 4,
    modifier: Modifier = Modifier,
) {
    if (dotsIndex < 0) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .wrapContentSize(Alignment.Center),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(totalDots) { i ->
            val isCurrent = i == dotsIndex
            val isPast    = i <  dotsIndex
            val color = when {
                isCurrent -> DesignV2Tokens.Colors.Cyan
                isPast    -> Color(0xFF2A6F80)              // cyan-dark for completed
                else      -> Color(0xFF2A2F38)              // grey for future
            }
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .width(if (isCurrent) 24.dp else 6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

// ── 4. Toast — 2-second auto-dismiss overlay ────────────────────────────────

/**
 * Toast overlay — surface pill with border, positioned 110 dp from the
 * bottom. Auto-dismiss after 2 seconds. Caller passes a nullable `message`;
 * `null` → no toast rendered. Every change to a non-null message resets the
 * dismiss timer (keyed on the message string).
 *
 * Used by Pricing sheet (Commit 4) and finale confirmation Copy (Commit 3).
 * Placed in Chrome so both the flow-level and step-level composables can
 * emit toasts through the same visual.
 */
@Composable
fun OnboardingToastV2(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message == null) return
    LaunchedEffect(message) {
        delay(2000L)
        onDismiss()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 110.dp)
            .wrapContentSize(Alignment.BottomCenter),
    ) {
        Text(
            text = message,
            color = DesignV2Tokens.Colors.TextPrimary,
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
            ),
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF014181E))
                .border(1.dp, DesignV2Tokens.Colors.Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
