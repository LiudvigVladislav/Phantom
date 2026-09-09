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
     * N1-F2 R-N1.2 — the privacy posture as seen by the fail-closed REST
     * egress policy, which MUST distinguish "no preference stored" from
     * "a preference is stored but cannot be parsed".
     *
     * - A MISSING preference retains the intentional legacy default and
     *   returns [PrivacyMode.Standard].
     * - A PRESENT but malformed/unreadable preference returns `null`, so
     *   [PrivacyModeRestEgressPolicy] maps it to
     *   [RestEgressDecision.AnonymousRequiredButUnavailable] — never
     *   [RestEgressDecision.DirectAllowed]. A corrupted stored value must
     *   not silently authorise Direct egress.
     *
     * The default mirrors [privacyMode] (there is no malformed state in an
     * in-memory or enum-typed store); persistence-backed implementations
     * override this to expose the distinction.
     */
    fun privacyModeForEgress(): PrivacyMode? = privacyMode

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
     * [NoTransportReachableException] since the last success, incremented
     * by `TransportManager.onAllFailed` and reset to 0 on any successful
     * connect.
     *
     * N1-F3: this counter is persisted and is NOT read by anything today.
     * It used to promise a "stuck - check your network" UI state past a
     * `STUCK_FAILURE_THRESHOLD` of 3; no code ever consumed either the
     * counter or the threshold, so the constant was removed rather than
     * left standing as a claim of coverage that did not exist. The doc
     * now describes only what the field actually does.
     *
     * The retry cadence deliberately does NOT read it. Retry backoff is
     * driven by [ConnectRetryScheduler]'s own in-memory streak, which
     * starts from the bottom of the ladder again after a process
     * restart. That is the conservative direction - a restarted process
     * retries sooner, not later - and it avoids asserting a persistence
     * semantics for this field that no test proves.
     */
    var transportFailureCount: Int

    companion object {
        /** 24 h. Past this, [lastWorkingTransport] hint is ignored. */
        const val LAST_SUCCESS_TTL_MS: Long = 24L * 3600L * 1000L

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
