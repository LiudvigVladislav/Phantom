// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

/**
 * Shared public-key hex formatters — neutral design-v2 layer.
 *
 * Dual-key labels track 2026-08-10 (architect §2): Finale AND
 * Profile MUST use the SAME formatter for corresponding keys
 * so values visually match across screens. This file is the
 * shared home — neither onboarding nor Profile owns the
 * helpers, so nothing has to reach across track boundaries.
 *
 * The two functions here work identically for Ed25519 signing
 * keys and X25519 encryption keys (both are 32-byte hex strings
 * → 64 chars). No key-type specialisation.
 *
 * Word "fingerprint" is deliberately absent from both names +
 * KDoc + return values — the earlier `formatFingerprintForDisplay`
 * / `formatFingerprintShort` names in `OnboardingStateV2.kt`
 * were factually inaccurate (neither result is a cryptographic
 * fingerprint of the key; both are chunked or truncated
 * displays of the raw public-key hex). Renamed here per
 * architect direction 2026-08-10.
 */

/**
 * Full 64-char public-key hex, formatted as 8 groups of 8
 * separated by a single space, with a double space between the
 * first 4 groups and the last 4 groups. Used inside the Finale
 * and Profile card bodies where the full key is displayed.
 *
 * Layout: `AAAAAAAA AAAAAAAA AAAAAAAA AAAAAAAA  BBBBBBBB BBBBBBBB BBBBBBBB BBBBBBBB`
 *
 * If [hex] is not exactly 64 chars, the raw string is returned
 * unmodified (defensive — upstream validators already reject
 * malformed hex, but the formatter must not throw at the UI
 * boundary).
 */
fun formatFullKeyForDisplay(hex: String): String {
    if (hex.length != 64) return hex
    return buildString {
        for (i in 0..7) {
            if (i > 0) append(if (i == 4) "  " else " ")
            append(hex.substring(i * 8, i * 8 + 8))
        }
    }
}

/**
 * Short 8-char public-key identifier: first 4 + last 4 chars of
 * the hex, separated by a horizontal ellipsis. Displayed inside
 * a `ShortKeyIdChip` alongside an explicit `Ed25519 short ID` /
 * `X25519 short ID` label so the reader knows this is NOT the
 * full key — no truncated value ever appears without the label
 * (per redline §C1 + dual-key labels track 2026-08-10).
 *
 * Returns empty string on malformed input (length != 64).
 * `ShortKeyIdChip` treats an empty return as "no short ID
 * available" and skips rendering.
 */
fun formatShortKeyIdForDisplay(hex: String): String {
    if (hex.length != 64) return ""
    return "${hex.substring(0, 4)}…${hex.substring(60, 64)}"
}
