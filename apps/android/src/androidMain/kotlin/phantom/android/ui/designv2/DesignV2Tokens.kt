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
 * `README.md §Design Tokens`, delivered on 2026-07-30. They are NOT the same
 * as the existing [phantom.android.ui.theme.PhantomTokens] — several hex
 * values differ meaningfully (see the token diff in the F1 handoff report).
 *
 * Because the existing PhantomTokens has 400+ call-sites through top-level
 * aliases in [phantom.android.ui.theme.PhantomTheme], the migration is
 * INCREMENTAL: DesignV2Tokens ships additively, F2 components reach into
 * it explicitly, and existing screens continue to use PhantomTokens
 * unchanged. There is no plan to auto-migrate the 400+ call-sites.
 *
 * DO NOT reference DesignV2Tokens from existing screens or from
 * PhantomTokens/PhantomTheme itself. Only new DesignV2 components (F2) may
 * consume this file. See project doctrine on additive design-system
 * evolution — a single global switch would ship a 25-screen visual
 * regression in one commit, which is exactly what we are avoiding.
 */
object DesignV2Tokens {

    // ── COLOR ────────────────────────────────────────────────────────────────
    // Values sourced verbatim from
    // design_handoff_phantom_messenger/README.md §Design Tokens.
    object Colors {

        // Surfaces — six layers from app canvas outward.
        //
        // The two lightest hover variants (SurfaceHover / SurfaceHoverAlt)
        // are BOTH listed in the handoff. Handoff spec: "Surface Hover
        // #0c0f14 / #0b0e13". Committed both; F2 components pick per
        // context (row hover vs pressed state — TBD in F2 review).
        val Background         = Color(0xFF05060A)  // App canvas (outside the "device" chrome). Not in existing PhantomTokens.
        val SurfaceDeep        = Color(0xFF08090C)  // Screen background
        val Surface            = Color(0xFF12151B)  // Inputs, chips  (differs from existing PhantomTokens.Surface = 0x0E1014)
        val SurfaceElevated    = Color(0xFF161A20)  // Cards, secondary buttons
        val SurfaceHover       = Color(0xFF0C0F14)  // Row hover
        val SurfaceHoverAlt    = Color(0xFF0B0E13)  // Alternate hover from the handoff (context TBD in F2)

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
        val Danger             = Color(0xFFFF5C5C)  // Handoff softer red — differs from existing #EF4444
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