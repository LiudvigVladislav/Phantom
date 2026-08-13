// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2.steps

import androidx.annotation.VisibleForTesting
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random
import phantom.android.R
import phantom.android.ui.designv2.DesignV2FontMono
import phantom.android.ui.designv2.DesignV2Tokens

/**
 * C6-b — pure-render frame + deterministic decorative-glyph
 * generators for the animated key-preview card. See
 * [IdentityKeyPreviewAnimatedCard] for the live-render composable
 * that drives progress via `Animatable`.
 *
 * This file houses render-time surface only: no `Animatable`, no
 * `LaunchedEffect`, no state saveable. Split out from
 * `IdentityKeyPreviewAnimation.kt` in Round-1 mini-round (2026-08-10
 * — P2-1) so both files remain under the 500-line project cap.
 *
 * NO crypto surface reachable from here. Glyphs are decorative only:
 * NOT persisted, NOT copyable, hidden from accessibility (see
 * [KEY_PREVIEW_CARD_A11Y_TAG]).
 *
 * ## UX correction (2026-08-10, post-LOGICAL-GREEN device test)
 *
 * Prior shape rendered 32 deterministic hex characters (seeded
 * pattern). Architect on-device review flagged that even with
 * `WILL BE GENERATED / READY TO CREATE` labels the visual read
 * as a real key — misleading. Corrected here: the shuffle
 * alphabet is `·•○.` (four non-hex decorative glyphs) and the
 * terminal state renders 32 solid bullets `•` — visually
 * unambiguous "masked / not-yet-generated" pattern. No slot layout
 * or spacing change; only the character source flipped from hex
 * to bullets.
 */

/**
 * Number of decorative glyph slots revealed during shuffle.
 * Source: `_target` structure at `Onboarding.dc.html` L250-L252 —
 * 8 groups × 4 slots. The slot count is preserved verbatim from
 * canonical; only the glyph identity changed from hex to bullets
 * in the 2026-08-10 UX correction. Prior name
 * `KEY_PREVIEW_HEX_GLYPH_COUNT` renamed to
 * `KEY_PREVIEW_GLYPH_COUNT` in the naming-hygiene pass after
 * that correction (no hex left in the visual).
 */
internal const val KEY_PREVIEW_GLYPH_COUNT: Int = 32

/**
 * Idle placeholder body — retained verbatim from the pre-C6-b static
 * card. Kept byte-identical so the existing
 * `onboarding_v2_identity_key_{empty,short,invalid_chars}` goldens
 * do NOT change (contract §3.4).
 */
internal const val KEY_PREVIEW_IDLE_PLACEHOLDER: String = "— — — —  — — — —\n— — — —  — — — —"

/**
 * Card-outer semantics tag. Used both as the merged card's
 * [contentDescription] AND as a stable test-matcher key.
 */
internal const val KEY_PREVIEW_CARD_A11Y_TAG: String = "Identity key preview"

/**
 * Canonical CSS `ease` keyword — used for border, glyph text, and
 * status-dot colour transitions in the design source
 * (`Onboarding.dc.html` L367, L369, L377). Compose's default tween
 * uses `FastOutSlowInEasing` which differs perceptibly from CSS
 * `ease`; hard-coding here matches canonical.
 */
private val CssEaseEasing: CubicBezierEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/**
 * Fixed seed for the deterministic target + shuffle glyph generators.
 * Chosen so goldens are byte-stable across machines. NOT a real key,
 * NOT derived from any crypto material — purely a decorative pattern
 * seed.
 */
private const val KEY_PREVIEW_DETERMINISTIC_SEED: Long = 0x50_48_41_4E_54_4F_4D_5FL // "PHANTOM_"

/**
 * Decorative glyph alphabet for the shuffle animation (UX
 * correction 2026-08-10). Four bullet-style characters — visually
 * clearly NOT hex, so a user reading the card during shuffle
 * cannot mistake the content for a real key.
 *
 * Chosen for JetBrains Mono equal-width rendering: `·` (middle
 * dot U+00B7), `•` (bullet U+2022), `○` (white circle U+25CB),
 * `.` (period U+002E). All four render at the same monospace cell
 * width, so slot layout stays byte-stable across shuffle ticks.
 */
private const val DECORATIVE_ALPHABET = "·•○."

/**
 * Terminal-state glyph. All 32 slots settle to this character —
 * a solid bullet, universally read as "masked / hidden value"
 * (like a password field's dots).
 */
private const val TERMINAL_GLYPH = '•'

// ── Frame composable ──────────────────────────────────────────────

/**
 * Fixed-progress card frame. Used by Paparazzi goldens (3 frames at
 * 0% / 50% / 100%) and by the `LocalInspectionMode` short-circuit
 * in [IdentityKeyPreviewAnimatedCard]. Renders exactly one frame,
 * no `Animatable` subscription.
 *
 * @param mountProgress `[0f, 1f]` — applied as alpha + upward slide
 *   on the card outer, so tests / previews can render the mount
 *   fade at any progress. Frame helper defaults to 1f (fully
 *   mounted); callers that want to snapshot the mid-mount state
 *   pass a specific value.
 */
@VisibleForTesting
@Composable
internal fun IdentityKeyPreviewCardFrame(
    progress: Float,
    phase: KeyPreviewAnimationPhase,
    mountProgress: Float = 1f,
) {
    val statusText = when (phase) {
        KeyPreviewAnimationPhase.Terminal -> "ED25519 · READY TO CREATE"
        else -> "ED25519 · WILL BE GENERATED"
    }

    // Border colour — Idle/Running keep neutral border; Terminal
    // fades to cyan-tinted. Source L377 `.4s ease`.
    val borderColor by animateColorAsState(
        targetValue = when (phase) {
            KeyPreviewAnimationPhase.Terminal -> DesignV2Tokens.Colors.Cyan.copy(alpha = 0.28f)
            else -> DesignV2Tokens.Colors.Border
        },
        animationSpec = tween(durationMillis = 400, easing = CssEaseEasing),
        label = "IdentityKeyPreviewCardBorder",
    )
    // Status dot — Idle: neutral grey. Running: cyan. Terminal:
    // green. Source L369 `.3s ease`.
    val dotColor by animateColorAsState(
        targetValue = when (phase) {
            KeyPreviewAnimationPhase.Idle -> DesignV2Tokens.Colors.TextQuaternary
            KeyPreviewAnimationPhase.Running -> DesignV2Tokens.Colors.Cyan
            KeyPreviewAnimationPhase.Terminal -> DesignV2Tokens.Colors.Success
        },
        animationSpec = tween(durationMillis = 300, easing = CssEaseEasing),
        label = "IdentityKeyPreviewCardDot",
    )
    // Glyph text colour — grey during Idle/Running, white at
    // Terminal. Source L367 `.3s ease`.
    val glyphColor by animateColorAsState(
        targetValue = when (phase) {
            KeyPreviewAnimationPhase.Terminal -> DesignV2Tokens.Colors.TextPrimary
            else -> DesignV2Tokens.Colors.TextTertiary
        },
        animationSpec = tween(durationMillis = 300, easing = CssEaseEasing),
        label = "IdentityKeyPreviewCardGlyphColor",
    )

    // Body content — Idle keeps the dash placeholder verbatim;
    // Running/Terminal renders the 32-slot bullet layout with
    // settled + shuffling decorative glyphs.
    val bodyText = when (phase) {
        KeyPreviewAnimationPhase.Idle -> KEY_PREVIEW_IDLE_PLACEHOLDER
        else -> renderShuffleGlyphs(progress = progress)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                // `phFadeUp` mount fade + upward slide via
                // graphicsLayer so no card-size layout shift
                // occurs (contract §4.3). The alpha + Y translation
                // animate the card into position; layout tree is
                // stable throughout.
                alpha = mountProgress.coerceIn(0f, 1f)
                translationY = (1f - mountProgress.coerceIn(0f, 1f)) * 10.dp.toPx()
            }
            .clip(RoundedCornerShape(18.dp))
            .background(DesignV2Tokens.Colors.SurfaceInset)
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .semantics(mergeDescendants = true) {
                // Stable a11y summary. Mid-shuffle glyph body is
                // cleared via `clearAndSetSemantics { }` below —
                // header status label + footer copy still merge
                // into the parent's Text so TalkBack reads status
                // + summary (contract §4.5).
                contentDescription = KEY_PREVIEW_CARD_A11Y_TAG
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
                text = statusText,
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
                    .background(dotColor),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DesignV2Tokens.Colors.Border),
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                text = bodyText,
                color = glyphColor,
                style = TextStyle(
                    fontFamily = DesignV2FontMono,
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 1.6.sp,
                    lineHeight = 24.sp,
                ),
                // Decorative content: clear a11y so mid-shuffle
                // glyph strings never enter the accessibility
                // tree (contract §4.5).
                modifier = Modifier.clearAndSetSemantics { },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Generated on device when you finish onboarding.",
                color = DesignV2Tokens.Colors.TextQuaternary,
                style = TextStyle(
                    fontFamily = DesignV2FontMono,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.4.sp,
                ),
            )
        }
    }
}

// ── Deterministic target + shuffle glyph generators ────────────────

/**
 * Terminal target: 32 identical [TERMINAL_GLYPH] bullets (`•`).
 * Renders as `•••• •••• · •••• ••••\n•••• •••• · •••• ••••` —
 * unambiguous "masked / hidden value" pattern (2026-08-10 UX
 * correction). Not seeded because there is no per-position
 * variation; all slots share the same terminal glyph.
 */
internal val KEY_PREVIEW_TARGET_CHARS: List<Char> =
    List(KEY_PREVIEW_GLYPH_COUNT) { TERMINAL_GLYPH }

/**
 * Precomputed shuffle glyphs from the decorative alphabet at each
 * tick position for still-unsettled slots. Independent of the
 * terminal glyph so mid-shuffle positions display varied bullets
 * (`·`, `•`, `○`, `.`) rather than all-terminal-`•`, keeping
 * the motion visually alive. Fixed seed keeps frames byte-stable.
 */
private val KEY_PREVIEW_SHUFFLE_TICK_TABLE: Array<CharArray> = run {
    val random = Random(KEY_PREVIEW_DETERMINISTIC_SEED xor 0xA5A5_A5A5L)
    Array(KEY_PREVIEW_GLYPH_COUNT) {
        CharArray(KEY_PREVIEW_GLYPH_COUNT) {
            DECORATIVE_ALPHABET[random.nextInt(DECORATIVE_ALPHABET.length)]
        }
    }
}

/**
 * How many decorative-glyph slots have settled at the given
 * [progress]. Discrete over [KEY_PREVIEW_GLYPH_COUNT] positions.
 */
@VisibleForTesting
internal fun settledCountFor(progress: Float): Int =
    (progress * KEY_PREVIEW_GLYPH_COUNT).toInt()
        .coerceIn(0, KEY_PREVIEW_GLYPH_COUNT)

/**
 * Render the 32-slot bullet body layout at the given progress.
 * Settled positions show the terminal bullet `•`; unsettled
 * positions show a deterministic decorative glyph (`·`, `•`,
 * `○`, or `.`) derived from the current tick.
 *
 * Layout: `SSSS SSSS · SSSS SSSS \n SSSS SSSS · SSSS SSSS`
 * (where `S` is a bullet-family glyph). Separators (spaces +
 * `·` + newline) are always present so the text box dimensions
 * do not depend on progress.
 */
@VisibleForTesting
internal fun renderShuffleGlyphs(progress: Float): String {
    val settled = settledCountFor(progress)
    val tickRow = KEY_PREVIEW_SHUFFLE_TICK_TABLE[
        (settled - 1).coerceIn(0, KEY_PREVIEW_GLYPH_COUNT - 1)
    ]
    val chars = CharArray(KEY_PREVIEW_GLYPH_COUNT) { i ->
        if (i < settled) KEY_PREVIEW_TARGET_CHARS[i]
        else tickRow[i]
    }
    // Layout: chars 0..3 · 4..7 · newline · 8..11 · 12..15 · newline
    // for the four groups per line — canonical L250-L252.
    return buildString(64) {
        appendGroup(chars, 0);  append(' ')
        appendGroup(chars, 4);  append(" · ")
        appendGroup(chars, 8);  append(' ')
        appendGroup(chars, 12); append('\n')
        appendGroup(chars, 16); append(' ')
        appendGroup(chars, 20); append(" · ")
        appendGroup(chars, 24); append(' ')
        appendGroup(chars, 28)
    }
}

private fun StringBuilder.appendGroup(chars: CharArray, start: Int) {
    for (i in 0 until 4) append(chars[start + i])
}
