// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import phantom.android.R

/**
 * PHANTOM type system — mirrors `Design/Primary/src/app/tokens.ts` `TS` object.
 *
 * Three font families, all bundled as variable .ttf binaries in
 * `apps/android/src/androidMain/res/font/`:
 *
 *   - **Geist** (display / brand) — variable weight 100–900 in
 *     `geist_variable.ttf`.
 *   - **Inter** (UI text) — variable weight 100–900 in `inter_variable.ttf`.
 *   - **JetBrains Mono** (technical / overlines) — variable weight 100–800
 *     in `jetbrains_mono_variable.ttf`.
 *
 * Each `Font(...)` declaration pins the wght axis with [FontVariation.weight]
 * so Compose's text engine pulls the right outline from the variable file
 * instead of synthesising bold/medium from a single weight cut.
 *
 * Production mapping (tokens.ts comment):
 *   Geist          → PP Neue Montreal (display / brand) — paid licence,
 *                    bundled separately when activated.
 *   Inter          → unchanged.
 *   JetBrains Mono → Berkeley Mono (Pro tier — premium mono contexts).
 */

// ── FONT FAMILIES ───────────────────────────────────────────────────────────

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun geistFont(weight: FontWeight) = Font(
    R.font.geist_variable,
    weight = weight,
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun interFont(weight: FontWeight, italic: Boolean = false) = Font(
    R.font.inter_variable,
    weight = weight,
    style = if (italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun jetbrainsMonoFont(weight: FontWeight) = Font(
    R.font.jetbrains_mono_variable,
    weight = weight,
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val PhantomFontGeist: FontFamily = FontFamily(
    geistFont(FontWeight.Light),
    geistFont(FontWeight.Normal),
    geistFont(FontWeight.Medium),
    geistFont(FontWeight.SemiBold),
    geistFont(FontWeight.Bold),
)

val PhantomFontInter: FontFamily = FontFamily(
    interFont(FontWeight.Normal),
    interFont(FontWeight.Medium),
    interFont(FontWeight.SemiBold),
    interFont(FontWeight.Bold),
    interFont(FontWeight.Normal, italic = true),
    interFont(FontWeight.Medium, italic = true),
)

val PhantomFontMono: FontFamily = FontFamily(
    jetbrainsMonoFont(FontWeight.Normal),
    jetbrainsMonoFont(FontWeight.Medium),
    jetbrainsMonoFont(FontWeight.SemiBold),
    jetbrainsMonoFont(FontWeight.Bold),
)

// ── TYPE SCALE ──────────────────────────────────────────────────────────────
// Letter-spacing in TS is given in em (e.g. -0.01em). Compose accepts sp:
// the equivalent pre-computed value at the corresponding font size is stored
// here so a refactor of font size also bumps letter-spacing automatically.
object PhantomType {
    val displayLarge = TextStyle(
        fontFamily = PhantomFontGeist,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.32).sp,  // -0.01em × 32sp
    )
    val display = TextStyle(
        fontFamily = PhantomFontGeist,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.24).sp,
    )
    val headline = TextStyle(
        fontFamily = PhantomFontGeist,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Medium,
    )
    val title = TextStyle(
        fontFamily = PhantomFontInter,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val body = TextStyle(
        fontFamily = PhantomFontInter,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    )
    val caption = TextStyle(
        fontFamily = PhantomFontInter,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    )
    val overline = TextStyle(
        fontFamily = PhantomFontMono,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.88.sp,  // 0.08em × 11sp; consumers should also call .uppercase()
    )
    val monoSm = TextStyle(
        fontFamily = PhantomFontMono,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Normal,
    )
    val monoMd = TextStyle(
        fontFamily = PhantomFontMono,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    )
}

// ── MATERIAL3 TYPOGRAPHY MAP ────────────────────────────────────────────────
// Bridges the PHANTOM type scale into Material3's Typography slots so any
// component that resolves typography via MaterialTheme.typography (Buttons,
// AlertDialog, etc.) inherits the right look without per-call wiring.
internal val PhantomMaterial3Typography = Typography(
    displayLarge = PhantomType.displayLarge,
    displayMedium = PhantomType.display,
    displaySmall = PhantomType.headline,
    headlineLarge = PhantomType.headline,
    headlineMedium = PhantomType.title,
    headlineSmall = PhantomType.title,
    titleLarge = PhantomType.title,
    titleMedium = PhantomType.title,
    titleSmall = PhantomType.body.copy(fontWeight = FontWeight.Medium),
    bodyLarge = PhantomType.body,
    bodyMedium = PhantomType.body,
    bodySmall = PhantomType.caption,
    labelLarge = PhantomType.caption.copy(fontWeight = FontWeight.Medium),
    labelMedium = PhantomType.overline,
    labelSmall = PhantomType.monoSm,
)
