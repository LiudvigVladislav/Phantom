// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font as GoogleFontFont
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import phantom.android.R

/**
 * PHANTOM type system — mirrors `Design/Primary/src/app/tokens.ts` `TS` object.
 *
 * Three font families:
 *   - **Geist** (display / brand) — open-source variable font from Vercel.
 *   - **Inter** (UI text) — open-source variable font.
 *   - **JetBrains Mono** (technical / overlines) — open-source variable font.
 *
 * Fonts load via Google's Downloadable Fonts mechanism through Google Play
 * Services. The first time the app needs Geist (or Inter, or JetBrains Mono),
 * Play Services downloads and caches it system-wide; subsequent launches and
 * other apps share that cache. No .ttf binaries are bundled with the apk, so
 * the binary stays small and the licence does not have to travel with us.
 *
 * Production mapping (tokens.ts comment):
 *   Geist          → PP Neue Montreal (display / brand)
 *   Inter          → Inter (unchanged for UI text)
 *   JetBrains Mono → Berkeley Mono (Pro tier — premium mono contexts)
 */

// ── GOOGLE FONTS PROVIDER ───────────────────────────────────────────────────
private val PhantomFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage   = "com.google.android.gms",
    certificates      = R.array.com_google_android_gms_fonts_certs,
)

private fun phantomGoogleFont(
    name: String,
    weight: FontWeight,
    style: FontStyle = FontStyle.Normal,
) = GoogleFontFont(
    googleFont   = GoogleFont(name),
    fontProvider = PhantomFontProvider,
    weight       = weight,
    style        = style,
)

// ── FONT FAMILIES ───────────────────────────────────────────────────────────
val PhantomFontGeist: FontFamily = FontFamily(
    phantomGoogleFont("Geist", FontWeight.Normal),
    phantomGoogleFont("Geist", FontWeight.Medium),
    phantomGoogleFont("Geist", FontWeight.SemiBold),
    phantomGoogleFont("Geist", FontWeight.Bold),
)

val PhantomFontInter: FontFamily = FontFamily(
    phantomGoogleFont("Inter", FontWeight.Normal),
    phantomGoogleFont("Inter", FontWeight.Medium),
    phantomGoogleFont("Inter", FontWeight.SemiBold),
    phantomGoogleFont("Inter", FontWeight.Bold),
    phantomGoogleFont("Inter", FontWeight.Normal, FontStyle.Italic),
)

val PhantomFontMono: FontFamily = FontFamily(
    phantomGoogleFont("JetBrains Mono", FontWeight.Normal),
    phantomGoogleFont("JetBrains Mono", FontWeight.Medium),
    phantomGoogleFont("JetBrains Mono", FontWeight.SemiBold),
    phantomGoogleFont("JetBrains Mono", FontWeight.Normal, FontStyle.Italic),
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
