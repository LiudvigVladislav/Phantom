// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * What the startup read of the two storage keys decided.
 */
data class PrivacyModeLoad(
    val mode: PrivacyMode,
    /**
     * True when the value came from the legacy mirror and the canonical
     * key must be written before anything connects.
     */
    val migrateLegacy: Boolean,
    /** Why, for the log. Not a UI string. */
    val reason: String,
)

/**
 * R-N1.17 P1 - decide the startup privacy mode from BOTH storage keys.
 *
 * The mode is mirrored in two SharedPreferences entries: the canonical
 * `transport.privacy_mode` and a legacy `privacy_mode` kept for a reader
 * that has not been migrated. Two keys holding one fact can disagree, so
 * the order between them has to be written down rather than left to
 * whichever getter runs first.
 *
 * The order, and why each step is what it is:
 *
 *  1. **Canonical present and valid** - it wins. It is the key every
 *     current writer maintains.
 *  2. **Canonical present but corrupt** - [PrivacyMode.Ghost], and
 *     explicitly NO fallback to the legacy value. Falling back would let
 *     a corrupted canonical key be overridden by a staler, laxer mirror,
 *     which is a downgrade triggered by data corruption.
 *  3. **Canonical absent, legacy valid** - use the legacy value and
 *     migrate it. This is the upgrade path from a build that only wrote
 *     the mirror; reading Standard there would silently turn a Ghost
 *     user into a Direct-first one at the exact moment they upgrade.
 *  4. **Canonical absent, legacy corrupt** - Ghost. Something is stored
 *     and cannot be read; that is not the same as nothing stored.
 *  5. **Both absent** - a genuinely new install, so the product default.
 *
 * Steps 2 and 4 are the fail-closed ones: unreadable means the most
 * restrictive posture, never the most convenient. The existing
 * `privacyModeForEgress()` already takes that line for REST; this is the
 * same rule for the transport chain, which never had it.
 */
fun loadPrivacyModeAtStartup(
    canonicalRaw: String?,
    legacyRaw: String?,
    newInstallDefault: PrivacyMode = PrivacyMode.Standard,
): PrivacyModeLoad {
    if (canonicalRaw != null) {
        val parsed = runCatching { PrivacyMode.valueOf(canonicalRaw) }.getOrNull()
        return if (parsed != null) {
            PrivacyModeLoad(parsed, migrateLegacy = false, reason = "canonical")
        } else {
            // No fallback to legacy on purpose: a corrupt canonical key
            // must not be overridden by a laxer mirror.
            PrivacyModeLoad(
                PrivacyMode.Ghost,
                migrateLegacy = false,
                reason = "canonical_corrupt_fail_closed",
            )
        }
    }
    if (legacyRaw != null) {
        val parsed = runCatching { PrivacyMode.valueOf(legacyRaw) }.getOrNull()
        return if (parsed != null) {
            PrivacyModeLoad(parsed, migrateLegacy = true, reason = "legacy_migrated")
        } else {
            PrivacyModeLoad(
                PrivacyMode.Ghost,
                migrateLegacy = false,
                reason = "legacy_corrupt_fail_closed",
            )
        }
    }
    return PrivacyModeLoad(newInstallDefault, migrateLegacy = false, reason = "new_install")
}
