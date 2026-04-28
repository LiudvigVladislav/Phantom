// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * PHANTOM type system — mirrors `Design/Primary/src/app/tokens.ts` `TS` object.
 *
 * Three font families: Geist (display/brand), Inter (UI text), JetBrains Mono
 * (technical / overlines). Today they fall back to system equivalents because
 * the .ttf files have not been dropped into `res/font/`. To activate the real
 * faces:
 *
 *   1. Drop the variable .ttf files into
 *      `apps/android/src/androidMain/res/font/`
 *      (see `docs/font-setup/README.md` for filenames and download links).
 *   2. Replace the [PhantomFontGeist] / [PhantomFontInter] / [PhantomFontMono]
 *      values below with `FontFamily(Font(R.font.geist_variable, …))` etc.
 *
 * Production mapping (tokens.ts comment):
 *   Geist          → PP Neue Montreal (display / brand)
 *   Inter          → Inter (unchanged for UI text)
 *   JetBrains Mono → Berkeley Mono (Pro tier — premium mono contexts)
 */

// ── FONT FAMILIES ───────────────────────────────────────────────────────────
val PhantomFontGeist: FontFamily = FontFamily.Default      // TODO: swap to R.font.geist
val PhantomFontInter: FontFamily = FontFamily.Default      // TODO: swap to R.font.inter
val PhantomFontMono: FontFamily  = FontFamily.Monospace    // TODO: swap to R.font.jetbrains_mono

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
