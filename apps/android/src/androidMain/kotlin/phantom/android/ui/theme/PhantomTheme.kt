// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

// ── BACKWARD-COMPAT TOP-LEVEL ALIASES ───────────────────────────────────────
// Every existing reference (~400+ across screens/) reads names like
// `Surface`, `TextDim`, `CyanAccent`. New code should call into
// [PhantomTokens.Colors] directly; these aliases stay until Phase B reskin
// migrates each screen.
//
// Note: a few of these shift slightly during migration to align with the
// design-system palette. Notable changes vs the original Alpha-1 values:
//
//   BgDeep:   #0B0D12 → #08090C  (slightly darker app bg)
//   Surface:  #0F1318 → #0E1014  (slightly darker)
//   Surface2: #141820 → #161A20  (matches surfaceElevated)
//   TextDim:  #6B8A9A → #6B7385  (neutral grey instead of cool grey)
//
// The cyan and primary accents are bit-for-bit identical (#00D4FF).

val CyanAccent    = PhantomTokens.Colors.Cyan
val CyanHover     = PhantomTokens.Colors.CyanHover
val CyanDark      = PhantomTokens.Colors.CyanDark

val BgDeep        = PhantomTokens.Colors.SurfaceDeep
val Surface       = PhantomTokens.Colors.Surface
val Surface2      = PhantomTokens.Colors.SurfaceElevated
val SurfaceHover  = PhantomTokens.Colors.SurfaceHover
val BorderSubtle  = PhantomTokens.Colors.BorderSubtle
val BorderColor   = PhantomTokens.Colors.Border

val TextPrimary   = PhantomTokens.Colors.TextPrimary
val TextSecondary = PhantomTokens.Colors.TextSecondary
val TextDim       = PhantomTokens.Colors.TextTertiary  // legacy name → "tertiary"
val TextDisabled  = PhantomTokens.Colors.TextDisabled

val Success       = PhantomTokens.Colors.Success
val Warning       = PhantomTokens.Colors.Warning
val Danger        = PhantomTokens.Colors.Danger

// ── MATERIAL3 COLOR SCHEME ──────────────────────────────────────────────────
private val PhantomColorScheme = darkColorScheme(
    primary          = PhantomTokens.Colors.Cyan,
    onPrimary        = PhantomTokens.Colors.SurfaceDeep,
    primaryContainer = PhantomTokens.Colors.CyanDark,
    onPrimaryContainer = PhantomTokens.Colors.TextPrimary,

    secondary        = PhantomTokens.Colors.TextSecondary,
    onSecondary      = PhantomTokens.Colors.SurfaceDeep,

    background       = PhantomTokens.Colors.SurfaceDeep,
    onBackground     = PhantomTokens.Colors.TextPrimary,

    surface          = PhantomTokens.Colors.Surface,
    onSurface        = PhantomTokens.Colors.TextPrimary,
    surfaceVariant   = PhantomTokens.Colors.SurfaceElevated,
    onSurfaceVariant = PhantomTokens.Colors.TextSecondary,
    surfaceContainerHighest = PhantomTokens.Colors.SurfaceHover,

    outline          = PhantomTokens.Colors.Border,
    outlineVariant   = PhantomTokens.Colors.BorderSubtle,

    error            = PhantomTokens.Colors.Danger,
    errorContainer   = PhantomTokens.Colors.Danger,
    onError          = PhantomTokens.Colors.TextPrimary,
)

@Composable
fun PhantomTheme(content: @Composable () -> Unit) {
    // Default body text style is the Inter-based PhantomType.body. Bare Text()
    // calls (no fontFamily / fontSize override) inherit this and pick up Inter
    // automatically — without this CompositionLocal the default would still be
    // the platform's system sans-serif on every screen.
    MaterialTheme(
        colorScheme = PhantomColorScheme,
        typography  = PhantomMaterial3Typography,
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides PhantomType.body,
        ) {
            content()
        }
    }
}
