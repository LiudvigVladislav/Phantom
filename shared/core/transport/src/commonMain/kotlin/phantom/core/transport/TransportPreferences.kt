// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * Persisted state owned by [TransportManager]. Backed by SharedPreferences on
 * Android (`TransportPreferencesAndroid`). Exposed as an interface so the
 * manager itself stays platform-neutral and unit-testable with an in-memory
 * fake.
 *
 * Schema is non-destructive: missing keys read as defaults so existing
 * installations pick up [PrivacyMode.Standard] + null hints automatically
 * (ADR-020 §"Schema migration").
 */
interface TransportPreferences {
    var privacyMode: PrivacyMode

    /**
     * The transport [TransportManager] last successfully reached the relay
     * through. Used to reorder the strategy chain so a known-good path is
     * tried first on subsequent connects, avoiding the worst-case
     * 5 s × chain-length warm-up. Reset to null after [LAST_SUCCESS_TTL_MS].
     */
    var lastWorkingTransport: TransportKind?

    /** Wall-clock millisecond at which `lastWorkingTransport` was recorded. */
    var lastSuccessAt: Long?

    /**
     * Count of consecutive chain-walks that ended in
     * [NoTransportReachableException] since the last success. Surfaces a
     * "stuck — check your network" UI state once it crosses
     * [STUCK_FAILURE_THRESHOLD]. Reset to 0 on any successful connect.
     */
    var transportFailureCount: Int

    companion object {
        /** 24 h. Past this, [lastWorkingTransport] hint is ignored. */
        const val LAST_SUCCESS_TTL_MS: Long = 24L * 3600L * 1000L

        /** 3. Once `transportFailureCount` reaches this, surface stuck-state UI. */
        const val STUCK_FAILURE_THRESHOLD: Int = 3
    }
}

/**
 * Pure-Kotlin in-memory implementation. Used by unit tests; production code
 * uses the SharedPreferences-backed Android implementation in `androidMain`.
 */
class InMemoryTransportPreferences(
    initialMode: PrivacyMode = PrivacyMode.Standard,
) : TransportPreferences {
    override var privacyMode: PrivacyMode = initialMode
    override var lastWorkingTransport: TransportKind? = null
    override var lastSuccessAt: Long? = null
    override var transportFailureCount: Int = 0
}
