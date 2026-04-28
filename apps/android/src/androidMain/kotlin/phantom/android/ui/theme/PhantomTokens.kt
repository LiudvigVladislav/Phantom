// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * PHANTOM design tokens — single source of truth.
 *
 * Mirrors `Design/Primary/src/app/tokens.ts`. When the design file changes,
 * update here in lock-step. Anything that references colors, spacing, or
 * radii in app code must come from this object — never hardcode hex or
 * pixel values directly inside Composables.
 *
 * Backward-compat aliases live at the top level of [PhantomTheme.kt] so that
 * existing 400+ call-sites that read `Surface`, `TextDim`, `CyanAccent`, etc.
 * keep working. New code should reach into the nested objects below.
 */
object PhantomTokens {

    // ── COLOR ────────────────────────────────────────────────────────────────
    object Colors {
        // Surfaces — six layers from deepest (app bg) to most elevated (card
        // borders). Each is one notch lighter than the previous.
        val SurfaceDeep     = Color(0xFF08090C)  // App bg, splash
        val Surface         = Color(0xFF0E1014)  // Default screen bg
        val SurfaceElevated = Color(0xFF161A20)  // Cards, sheets
        val SurfaceHover    = Color(0xFF1C2128)  // Hover / pressed
        val BorderSubtle    = Color(0xFF1F242C)  // Dividers, separators
        val Border          = Color(0xFF2A2F38)  // Card borders, inputs

        // Text — four readability levels.
        val TextPrimary     = Color(0xFFF5F7FA)  // Headlines, body
        val TextSecondary   = Color(0xFFA4ACBA)  // Subtitles, metadata
        val TextTertiary    = Color(0xFF6B7385)  // Timestamps, labels
        val TextDisabled    = Color(0xFF3F4654)  // Disabled states

        // Cyan accent — locked triplet derived from #00D4FF.
        // Primary at 100%, Hover at -8% lightness, Active (pressed) at -24%.
        val Cyan            = Color(0xFF00D4FF)
        val CyanHover       = Color(0xFF00BDDF)
        val CyanDark        = Color(0xFF0099BB)

        // Status colors.
        val Success         = Color(0xFF22C55E)  // Online indicator, ✓ confirmations
        val Warning         = Color(0xFFF59E0B)  // Caution states
        val Danger          = Color(0xFFEF4444)  // Errors, destructive actions
    }

    // ── SPACING ──────────────────────────────────────────────────────────────
    // 8pt grid. Most multiples of 4. The 20.dp slot fills the gap between 16
    // and 24 — used when "comfortable" is between "md" and "lg".
    object Spacing {
        val micro          = 4.dp    // Inline gaps inside compact rows
        val baseUnit       = 8.dp    // Standard small gap
        val tight          = 12.dp   // Internal padding of compact cards
        val comfortable    = 16.dp   // Default padding inside cards / sheets
        val relaxed        = 20.dp   // Comfortable+ for breathing room
        val gap            = 24.dp   // Major gaps inside sections
        val sectionGap     = 32.dp   // Between adjacent sections
        val sectionHeader  = 40.dp   // Above section header
        val pageSection    = 48.dp   // Page-level gap between major regions
        val hero           = 64.dp   // Hero / wide spacing
        val pageTop        = 96.dp   // Top-of-page gap (e.g. above onboarding)
    }

    // ── RADIUS ───────────────────────────────────────────────────────────────
    object Radius {
        val sm   = 8.dp    // Chips, inputs, badges
        val md   = 12.dp   // Cards, bubbles
        val lg   = 16.dp   // Modals, sheets
        val xl   = 24.dp   // Hero cards
        // Compose has no "infinite" radius marker; 9999 dp is canonical for
        // "fully rounded pill". Use this for buttons, nav, fully-rounded chips.
        val pill = 9999.dp
    }
}
