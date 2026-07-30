// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * DesignV2 tokens — additive alongside the existing PhantomTokens.
 *
 * These tokens mirror the palette in
 * `design_handoff_phantom_messenger/design-system-notes.md` +
 * `README.md §Design Tokens`, delivered on 2026-07-30.
 *
 * Colours: 18 total.
 *   - 15 are the tokens the handoff explicitly enumerates (with the two
 *     dark near-blacks split by usage — hover vs inset — into
 *     SurfaceHover / SurfaceInset, and the two reds split into
 *     Error / Danger per handoff review).
 *   - 3 are additional Cyan-family and background tokens the handoff
 *     uses but does not list in its top-level table
 *     (Background, CyanDeepActive, CyanDeepDisabled).
 *
 * Values are NOT the same as the existing [phantom.android.ui.theme.PhantomTokens]:
 *   - 7 tokens have different hex than PhantomTokens' same-named counterparts
 *     (Surface, SurfaceHover, TextSecondary, TextTertiary, CyanDark ⇢ CyanDeepActive,
 *     Danger, plus TextQuaternary which doesn't exist in PhantomTokens at all).
 *   - The rest either match or fill roles PhantomTokens lacks.
 *
 * Do NOT reference DesignV2Tokens from existing screens or from
 * PhantomTokens/PhantomTheme itself. Only new DesignV2 components (F2)
 * may consume this file.
 *
 * Because the existing PhantomTokens has 400+ call-sites through top-level
 * aliases in [phantom.android.ui.theme.PhantomTheme], the migration is
 * INCREMENTAL: DesignV2Tokens ships additively, F2 components reach into
 * it explicitly, and existing screens continue to use PhantomTokens
 * unchanged. There is no plan to auto-migrate the 400+ call-sites.
 * A single global switch would ship a 25-screen visual regression in one
 * commit, which is exactly what we are avoiding.
 */
object DesignV2Tokens {

    // ── COLOR ────────────────────────────────────────────────────────────────
    // Values sourced verbatim from
    // design_handoff_phantom_messenger/README.md §Design Tokens.
    object Colors {

        // Surfaces — six layers from app canvas outward.
        //
        // The handoff lists two dark near-black tones with distinct usage:
        //   #0C0F14 — appears only in pointer / row hover states  → SurfaceHover
        //   #0B0E13 — appears 21× as a nested inset surface     → SurfaceInset
        // Committed both under their semantic role names so F2 components
        // can pick unambiguously.
        val Background         = Color(0xFF05060A)  // App canvas (outside the "device" chrome). Not in existing PhantomTokens.
        val SurfaceDeep        = Color(0xFF08090C)  // Screen background
        val Surface            = Color(0xFF12151B)  // Inputs, chips  (differs from existing PhantomTokens.Surface = 0x0E1014)
        val SurfaceElevated    = Color(0xFF161A20)  // Cards, secondary buttons
        val SurfaceHover       = Color(0xFF0C0F14)  // Pointer hover state on rows / interactive surfaces
        val SurfaceInset       = Color(0xFF0B0E13)  // Nested inset surface (recessed panels inside cards) — 21 uses in handoff

        // Borders
        val Border             = Color(0xFF1F242C)  // Default border for inputs, cards
        val BorderHover        = Color(0xFF2A2F38)  // Focused / hovered border

        // Text — four readability levels, handoff naming.
        //
        // Note: existing PhantomTokens has TextTertiary #6B7385 which the
        // handoff calls Text QUATERNARY. Different meanings under the same
        // label. That's part of why we ship additively — the two systems'
        // "tertiary" labels no longer mean the same colour.
        val TextPrimary        = Color(0xFFF5F7FA)  // Headlines, body — matches existing
        val TextSecondary      = Color(0xFFCBD2DC)  // Subtitles, metadata — handoff is lighter than existing #A4ACBA
        val TextTertiary       = Color(0xFF8B97A8)  // Labels — handoff is lighter than existing #6B7385
        val TextQuaternary     = Color(0xFF6B7385)  // Timestamps, muted — handoff has an extra tier existing lacks

        // Cyan (primary accent) — locked triplet.
        //
        // Deep-cyan disabled-fill (#0D5F73) is the handoff-specific token
        // for disabled primary-button state. Existing PhantomTokens has
        // no equivalent; component states in F2 will use it explicitly.
        val Cyan               = Color(0xFF00D4FF)  // Primary — matches existing
        val CyanDeepActive     = Color(0xFF00A8CC)  // Active (pressed) — handoff-specific, differs from existing CyanDark #0099BB
        val CyanDeepDisabled   = Color(0xFF0D5F73)  // Disabled-fill deep cyan — not in existing PhantomTokens

        // Status
        val Success            = Color(0xFF22C55E)  // Matches existing
        //
        // Two reds, distinct roles per handoff review:
        //   Error   #EF4444 — input validation errors, "activity, no count"
        //                     red dot on chips/rows. Neutral-tech red.
        //                     (Existing PhantomTokens.Danger is #EF4444 —
        //                     same hex, different role.)
        //   Danger  #FF5C5C — destructive user actions (Delete for Everyone),
        //                     failed message ticks, End-call button.
        //                     Softer, more visually salient at large sizes.
        val Error              = Color(0xFFEF4444)  // Input validation, activity dot
        val Danger             = Color(0xFFFF5C5C)  // Destructive actions, failed states, end call
    }

    // ── SPACING ──────────────────────────────────────────────────────────────
    // The handoff explicitly says "No fixed 4/8px scale was imposed — spacing
    // and radii are set per-component to match the reference screenshots."
    //
    // We therefore ship a very small set of canonical spacing values
    // matching the most common inline-style values seen across the 9 .dc.html
    // reference files. Components in F2 are free to inline per-element sizes
    // where the handoff calls for a non-canonical value. This is NOT an
    // 8pt-grid tightening — it is a matching-the-handoff design decision.
    object Spacing {
        val xs   = 4.dp    // Inline gap inside compact rows
        val sm   = 8.dp    // Standard small gap
        val md   = 12.dp   // Internal padding of compact cards
        val lg   = 16.dp   // Default padding inside cards / sheets
        val xl   = 24.dp   // Between adjacent sections
    }

    // ── RADIUS ───────────────────────────────────────────────────────────────
    // Handoff §Spacing / Radii: "common radii: 12–14px inputs, 16–20px
    // cards, 999px pills/avatars." Ship the discrete values the handoff
    // enumerates; component-specific radii (e.g. 14dp on some inputs vs
    // 12dp on others) are inlined by the F2 component.
    object Radius {
        val input    = 12.dp
        val inputLg  = 14.dp
        val card     = 16.dp
        val cardLg   = 20.dp
        val pill     = 9999.dp   // Fully-rounded pill (buttons, chips, avatars)
    }
}