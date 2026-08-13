// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import androidx.compose.ui.text.font.FontFamily
import phantom.android.ui.theme.PhantomFontGeist
import phantom.android.ui.theme.PhantomFontInter
import phantom.android.ui.theme.PhantomFontMono

/**
 * DesignV2 typography — additive alongside the existing PhantomType.
 *
 * By design this file is minimal. Two decisions drive that:
 *
 *   1. The variable-weight `.ttf` binaries for Geist, Inter and JetBrains Mono
 *      are already loaded by [phantom.android.ui.theme.PhantomTypography] and
 *      exposed as [PhantomFontGeist], [PhantomFontInter], [PhantomFontMono].
 *      Per the F1 lock (2026-07-30) we do NOT duplicate the font binaries —
 *      DesignV2 reuses the same FontFamily instances the existing theme
 *      already registered.
 *
 *   2. The handoff `design-system-notes.md §Typography` names the three
 *      families and their roles but explicitly declines to prescribe a fixed
 *      type scale — "Sizes/weights are set per-element inline in each
 *      .dc.html — read the style attributes for exact px values." So there
 *      is no canonical DesignV2 type scale to mirror. F2 components will
 *      inline per-element sizes as the handoff dictates.
 *
 * All this file provides is a stable per-role alias so F2 component code
 * can read `DesignV2FontDisplay` / `DesignV2FontBody` / `DesignV2FontMono`
 * without having to reach into the existing theme's Font* naming (which
 * is anchored to the FONT FAMILY name — "Geist" / "Inter" / "Mono" — not
 * the semantic ROLE). A future font migration (e.g. Geist → PP Neue Montreal
 * per the note in [phantom.android.ui.theme.PhantomTypography]) can then
 * flip DesignV2FontDisplay to a new family with no F2 component churn.
 *
 * License attribution for the three font families lives in
 * `apps/android/src/androidMain/res/raw/font_license_*.txt` (bundled in
 * APK for a future "About > Open-source licences" surface in F2+).
 */

/** Display face — used for screen titles, hero wordmarks, large numerals. */
val DesignV2FontDisplay: FontFamily = PhantomFontGeist

/** UI body face — used for labels, descriptions, button text. */
val DesignV2FontBody: FontFamily = PhantomFontInter

/** Technical face — used for keys, timestamps, overlines, usernames, fingerprints. */
val DesignV2FontMono: FontFamily = PhantomFontMono