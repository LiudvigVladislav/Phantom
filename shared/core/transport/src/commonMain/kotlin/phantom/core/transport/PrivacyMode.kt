// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * User-facing privacy posture, persisted in [TransportPreferences].
 * Drives the [TransportStrategy] selected by [TransportManager].
 *
 * Defined per ADR-020 §"Privacy Modes".
 */
enum class PrivacyMode {
    /**
     * Lowest latency on clean networks; falls through to REALITY then Tor onion
     * if direct WSS is blocked. Default for new installations.
     */
    Standard,

    /**
     * Skip direct WSS entirely. The relay never sees the user's source IP —
     * REALITY origin is the Hetzner box. Falls through to Tor onion if REALITY
     * is unreachable.
     */
    Private,

    /**
     * Tor onion only. **Never silently downgrades.** If Tor cannot bootstrap
     * (TSPU suppression, broken bridges) the connect fails with a user-visible
     * `Failed` state — the alternative would be a privacy regression the user
     * explicitly opted out of.
     */
    Ghost,
}
