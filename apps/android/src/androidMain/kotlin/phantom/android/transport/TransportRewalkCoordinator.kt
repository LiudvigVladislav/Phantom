// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import phantom.core.transport.RelayTransportConfig
import phantom.core.transport.TransportManager
import phantom.core.transport.TransportPreferences

/**
 * PR-LTE-NETCHANGE1 (2026-05-28) — coordinator that performs the
 * full chain-rewalk sequence when [NetworkChangeObserver] reports a
 * meaningful network change (Wi-Fi ↔ cellular, VPN appear/disappear,
 * validated-capability change, network lost/regained).
 *
 * **Ownership separation** (architect guardrail #1, 2026-05-28):
 *
 * - **This coordinator** owns the four reset actions: notify state
 *   machine, clear sticky hint, `disconnect()`, `transportManager.release()`.
 * - **[phantom.android.service.PhantomMessagingService]** owns the
 *   re-entry: clear its `connectStarted` CAS flag and re-enter
 *   `onStartCommand` via an `Intent`. The coordinator triggers re-entry
 *   via the [requestServiceRestart] lambda, which is wired by the
 *   service when the coordinator is constructed.
 *
 * Splitting it this way prevents double-disconnect races (only the
 * coordinator calls `disconnect()`; only the service calls `connect()`)
 * and keeps each component's responsibilities locally explainable.
 *
 * **Rate-limit semantics** (architect guardrail #3, 2026-05-28):
 *
 * - Default: minimum [RelayTransportConfig.NETWORK_REWALK_MIN_INTERVAL_MS]
 *   between two consecutive rewalks. Inside the window, the request is
 *   dropped with `NETWORK_TRACE rate_limited reason=interval ageMs=<n>`.
 * - **Exception:** a transition from `networkPresent=false` to
 *   `networkPresent=true` (architect-named `network_available` reason)
 *   ALWAYS bypasses the rate-limit. If the network had genuinely
 *   disappeared and just came back, suppressing the rewalk would leave
 *   the app stuck on stale transport decisions; we always re-walk in
 *   that case.
 *
 * The coordinator is process-scoped: a single instance is held by
 * [phantom.android.di.AppContainer] for the lifetime of the app, and
 * the observer fires into it for every meaningful change. It serialises
 * concurrent rewalk attempts behind an internal [Mutex] so two callbacks
 * arriving within the same coroutine scope cannot interleave their reset
 * steps.
 *
 * Logs under tag `PhantomHybrid` (consistent with `REST_TRACE` lines
 * already emitted from this layer):
 *
 * ```
 * NETWORK_TRACE rewalk_start reason=<NetworkChangeReason>
 * NETWORK_TRACE rate_limited reason=interval ageMs=<n>
 * NETWORK_TRACE rewalk_done elapsedMs=<n>
 * ```
 */
internal class TransportRewalkCoordinator(
    private val scope: CoroutineScope,
    private val transportPreferences: TransportPreferences,
    private val transportManager: TransportManager,
    private val hybridTransportProvider: () -> HybridRelayTransport?,
    private val requestServiceRestart: (reason: NetworkChangeReason) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /** Serialises concurrent rewalk attempts within the same JVM. */
    private val rewalkMutex = Mutex()

    /**
     * Wall-clock millis at the most recent rewalk start. `null` until
     * the first rewalk completes. Used by [shouldRateLimit] to enforce
     * [RelayTransportConfig.NETWORK_REWALK_MIN_INTERVAL_MS].
     */
    @Volatile private var lastRewalkAtMs: Long? = null

    /**
     * Track of the most recently observed `networkPresent` value so
     * that a `false → true` transition can bypass the rate-limit per
     * guardrail #3. Initial value `true` reflects the assumption that
     * the app starts with network present (the observer will correct
     * it on first callback if false).
     */
    @Volatile private var lastNetworkPresent: Boolean = true

    /**
     * Active rewalk coroutine, if any. The observer's debounce job
     * collapses bursts into one accepted change, but if a slower
     * rewalk is still running when a new debounce fires, we don't
     * preempt it; the mutex serialises and the second one waits.
     */
    @Volatile private var currentRewalkJob: Job? = null

    /**
     * Fired by [NetworkChangeObserver] after debounce + meaningful-
     * change classification has decided this snapshot represents a
     * real change.
     *
     * Idempotent under repeated identical reasons (the rate-limit
     * absorbs the duplicates). Non-blocking — schedules the rewalk on
     * [scope] and returns.
     */
    fun onMeaningfulChange(reason: NetworkChangeReason, snapshot: NetworkSnapshot) {
        currentRewalkJob = scope.launch {
            performRewalk(reason, snapshot)
        }
    }

    /**
     * PR-LTE-NETCHANGE1 P1 fix (architect 2026-05-28): seed
     * [lastNetworkPresent] from the FIRST snapshot the observer reads
     * after `register()`, WITHOUT running the destructive rewalk path.
     *
     * Why: the observer would otherwise classify the first snapshot as
     * meaningful (no previous to diff against) and call
     * [onMeaningfulChange], which would clear the sticky hint,
     * disconnect, release, and restart the connect generation on every
     * cold start — burning a `chain_start` even when no network change
     * happened. That would also pollute Test #88 Scenarios A/B/C/D
     * with an extra rewalk attributable to nothing.
     *
     * Correct semantics: on `register()`, the observer reads the
     * current snapshot, calls this method to update the coordinator's
     * `lastNetworkPresent` mark (so future `networkPresent=false→true`
     * detection works correctly), logs `NETWORK_TRACE initial_snapshot`,
     * and stops — no rewalk.
     */
    fun seedNetworkPresent(networkPresent: Boolean) {
        lastNetworkPresent = networkPresent
        Log.i(
            TAG,
            "NETWORK_TRACE coordinator_seeded networkPresent=$networkPresent",
        )
    }

    private suspend fun performRewalk(reason: NetworkChangeReason, snapshot: NetworkSnapshot) {
        rewalkMutex.withLock {
            val now = nowMs()

            // Guardrail #3: `network_available` always bypasses the
            // rate-limit. A network reappearance is the case where
            // suppressing the rewalk would leave us stuck.
            val isForcedReason = reason == NetworkChangeReason.NETWORK_AVAILABLE ||
                (!lastNetworkPresent && snapshot.networkPresent)

            if (!isForcedReason) {
                val last = lastRewalkAtMs
                if (last != null) {
                    val ageMs = now - last
                    if (ageMs < RelayTransportConfig.NETWORK_REWALK_MIN_INTERVAL_MS) {
                        Log.i(
                            TAG,
                            "NETWORK_TRACE rate_limited reason=interval ageMs=$ageMs " +
                                "minIntervalMs=${RelayTransportConfig.NETWORK_REWALK_MIN_INTERVAL_MS} " +
                                "skippedReason=$reason",
                        )
                        return@withLock
                    }
                }
            }

            lastRewalkAtMs = now
            lastNetworkPresent = snapshot.networkPresent

            val startMs = now
            Log.i(
                TAG,
                "NETWORK_TRACE rewalk_start reason=$reason vpnActive=${snapshot.vpnActive} " +
                    "validated=${snapshot.validated} networkPresent=${snapshot.networkPresent} " +
                    "forced=$isForcedReason",
            )

            // Step 3.1 — clear sticky hint. Done first because the
            // state-machine event submission and disconnect would
            // otherwise race against a hint that is about to become
            // invalid.
            transportPreferences.lastWorkingTransport = null
            transportPreferences.lastSuccessAt = null

            // Step 3.2 — notify state machine via the narrow Hybrid
            // method. Short-circuits internally if REST capability is
            // off, so this is safe before bootstrap has succeeded.
            val hybrid = hybridTransportProvider()
            if (hybrid != null) {
                runCatching { hybrid.submitNetworkChangedEvent() }
                    .onFailure { e ->
                        Log.w(
                            TAG,
                            "NETWORK_TRACE rewalk_substep_error step=submitNetworkChangedEvent " +
                                "errorClass=${e::class.simpleName} message=${e.message?.take(120)}",
                        )
                    }
                // Step 3.3 — tear down current WS generation.
                runCatching { hybrid.disconnect() }
                    .onFailure { e ->
                        Log.w(
                            TAG,
                            "NETWORK_TRACE rewalk_substep_error step=hybrid.disconnect " +
                                "errorClass=${e::class.simpleName} message=${e.message?.take(120)}",
                        )
                    }
            } else {
                Log.i(
                    TAG,
                    "NETWORK_TRACE rewalk_substep_skip step=hybrid reason=hybrid_not_initialized",
                )
            }

            // Step 3.4 — release the cached probe/select state and
            // stop Xray/Tor providers if running. The next connect()
            // will re-walk the chain against the new network.
            runCatching { transportManager.release() }
                .onFailure { e ->
                    Log.w(
                        TAG,
                        "NETWORK_TRACE rewalk_substep_error step=transportManager.release " +
                            "errorClass=${e::class.simpleName} message=${e.message?.take(120)}",
                    )
                }

            // Step 3.5 — request the service to restart its connect
            // generation. The service owns the `connectStarted` CAS
            // flag and the `Intent` re-entry; we just tell it to go.
            //
            // Architect guardrail #1: coordinator does NOT call
            // connect() directly. Single source of truth for entering
            // the connect lifecycle stays in `PhantomMessagingService
            // .onStartCommand`.
            runCatching { requestServiceRestart(reason) }
                .onFailure { e ->
                    Log.w(
                        TAG,
                        "NETWORK_TRACE rewalk_substep_error step=requestServiceRestart " +
                            "errorClass=${e::class.simpleName} message=${e.message?.take(120)}",
                    )
                }

            val elapsedMs = nowMs() - startMs
            Log.i(TAG, "NETWORK_TRACE rewalk_done reason=$reason elapsedMs=$elapsedMs")
        }
    }

    companion object {
        private const val TAG = "PhantomHybrid"
    }
}

/**
 * Reasons the coordinator can fire a rewalk. Maps loosely onto the
 * Android `ConnectivityManager.NetworkCallback` event types, but
 * deduplicated at the meaningful-change classifier level.
 *
 * The string form is what appears in `NETWORK_TRACE rewalk_start reason=<...>`
 * logs and the value the test runbook will grep for.
 */
internal enum class NetworkChangeReason {
    /** `networkPresent=false → true` — always forces a rewalk. */
    NETWORK_AVAILABLE,
    /** `networkPresent=true → false`. Coordinator logs and clears state but no chain walk is meaningful right now. */
    NETWORK_LOST,
    /** Transport class flipped from Wi-Fi to cellular. */
    WIFI_TO_CELLULAR,
    /** Transport class flipped from cellular to Wi-Fi. */
    CELLULAR_TO_WIFI,
    /** A VPN became active. */
    VPN_ADDED,
    /** The previously-active VPN went away. */
    VPN_REMOVED,
    /** `NET_CAPABILITY_VALIDATED` flipped. */
    VALIDATED_CHANGED,
    /** Other meaningful change (covered by snapshot diff but not by one of the named transitions). */
    OTHER,
    // NB: `FIRST_SNAPSHOT` was deliberately removed in the 2026-05-28
    // P1 fix round. The first snapshot after observer registration is
    // handled by `NetworkChangeObserver.evaluate` directly via
    // `TransportRewalkCoordinator.seedNetworkPresent` + the
    // `NETWORK_TRACE initial_snapshot` log; it never enters the
    // `onMeaningfulChange` path. Adding FIRST_SNAPSHOT back would
    // re-introduce the destructive cold-start rewalk that the architect
    // caught in PR #241 review.
}

/**
 * Snapshot of the meaningful network state at the moment the observer's
 * debounce window elapsed. Captured by [NetworkChangeObserver] via
 * `ConnectivityManager.activeNetwork` + capabilities, NOT via the payload
 * of the triggering callback (architect guardrail #2, 2026-05-28).
 */
internal data class NetworkSnapshot(
    val networkPresent: Boolean,
    val transportClass: NetworkTransportClass,
    val vpnActive: Boolean,
    val validated: Boolean,
)

internal enum class NetworkTransportClass {
    WIFI, CELLULAR, ETHERNET, OTHER, NONE,
}
