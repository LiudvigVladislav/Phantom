// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import phantom.core.transport.ProbeIssueRejectReason
import phantom.core.transport.ProbeIssueResult
import phantom.core.transport.RelayTransportConfig
import phantom.core.transport.RewalkCoordinatorGateProvider
import phantom.core.transport.RouteChangeOutcome
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
/**
 * RC-RECONNECT-QUIESCENCE1 commit 2c (2026-06-22) — narrow facade
 * exposing the three methods [TransportRewalkCoordinator] needs from
 * [HybridRelayTransport]. Keeps the coordinator independent of the
 * concrete Android transport class so behavioural tests can drive the
 * locked transaction sequence with a deterministic fake.
 */
internal interface RewalkHybridFacade {
    suspend fun submitNetworkChangedEvent(clearsMode2Sticky: Boolean)
    suspend fun disconnect()
    suspend fun disconnectAndJoin(timeoutMs: Long): Boolean
}

internal class TransportRewalkCoordinator(
    private val scope: CoroutineScope,
    private val transportPreferences: TransportPreferences,
    /**
     * Release the cached probe / chain-walk state. Production wires
     * [phantom.core.transport.TransportManager.release]; tests inject a
     * controllable suspend lambda.
     */
    private val releaseTransport: suspend () -> Unit,
    private val hybridTransportProvider: () -> RewalkHybridFacade?,
    private val requestServiceRestart: (reason: NetworkChangeReason) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /**
     * RC-RECONNECT-QUIESCENCE1 commit 2c (2026-06-22). When non-null
     * the rewalk runs as a typed transaction: `beginRouteChange →
     * submitNetworkChangedEvent → disconnectAndJoin → release →
     * issueProbeAfterRewalk → requestServiceRestart` with explicit
     * revocation paths at every substep. When `null` the coordinator
     * falls back to the legacy pre-quiescence sequence (no probe,
     * `hybrid.disconnect()` instead of `disconnectAndJoin()`).
     *
     * Production wires the [phantom.core.transport.RestStateMachine]
     * instance from `AppContainer`. Tests inject a fake.
     */
    private val gateCoordinator: RewalkCoordinatorGateProvider? = null,
    /**
     * Bounded join timeout passed to
     * [phantom.android.transport.HybridRelayTransport.disconnectAndJoin].
     * Defaults to 10_000 ms per the locked
     * [phantom.core.transport.RelayTransport.disconnectAndJoin] kdoc.
     */
    private val disconnectJoinTimeoutMs: Long = 10_000L,
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

            // RC-RECONNECT-QUIESCENCE1 commit 2c (2026-06-22):
            // `lastRewalkAtMs` is NOT advanced here. It is advanced only
            // after the entire transaction commits successfully —
            // failed teardown / release / probe-issue must not eat the
            // rate-limit budget. The legacy unconditional bump shadowed
            // a useful retry slot on every failure.
            lastNetworkPresent = snapshot.networkPresent

            val startMs = now
            Log.i(
                TAG,
                "NETWORK_TRACE rewalk_start reason=$reason vpnActive=${snapshot.vpnActive} " +
                    "validated=${snapshot.validated} networkPresent=${snapshot.networkPresent} " +
                    "forced=$isForcedReason",
            )

            // Legacy pre-quiescence sequence: when the gate coordinator
            // is null we keep the pre-2c behaviour byte-for-byte
            // (cleared sticky hint, submitNetworkChangedEvent,
            // hybrid.disconnect, release, restart).
            val coordinator = gateCoordinator
            if (coordinator == null) {
                runLegacyRewalk(reason, snapshot, startMs)
                lastRewalkAtMs = startMs
                return@withLock
            }

            // Step 1 — begin the typed route-change transaction. The
            // returned [RouteChangeOutcome] selects the downstream path:
            // OpenReconnect (no probe), StickyRecovery (full transaction
            // with probe), QuiescencePreserved (no-op — sticky stays
            // armed). Any failure path between this step and
            // `issueProbeAfterRewalk` calls `revokeRouteChange` so
            // future probes use a fresher epoch.
            val outcome: RouteChangeOutcome = try {
                coordinator.beginRouteChange(clearsMode2Sticky = reason.clearsMode2Sticky)
            } catch (ce: CancellationException) {
                Log.i(TAG, "NETWORK_TRACE rewalk_aborted step=beginRouteChange reason=cancelled")
                throw ce
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "NETWORK_TRACE rewalk_substep_error step=beginRouteChange " +
                        "errorClass=${t::class.simpleName} message=${t.message?.take(120)}",
                )
                return@withLock
            }
            val routeEpoch = outcome.routeEpoch
            Log.i(
                TAG,
                "NETWORK_TRACE rewalk_route_change outcome=${outcome::class.simpleName} " +
                    "route_epoch=$routeEpoch clears_mode_2_sticky=${reason.clearsMode2Sticky}",
            )

            // QuiescencePreserved: VALIDATED_CHANGED or similar under
            // quiescence. Sticky window stays armed; coordinator runs NO
            // substeps. `lastRewalkAtMs` is NOT bumped (no rewalk
            // happened). Sticky preferences are NOT cleared.
            if (outcome is RouteChangeOutcome.QuiescencePreserved) {
                Log.i(
                    TAG,
                    "NETWORK_TRACE rewalk_quiescence_preserved route_epoch=$routeEpoch " +
                        "reason=$reason",
                )
                return@withLock
            }

            // Step 2 — clear sticky preferences. Done for both
            // OpenReconnect and StickyRecovery so the next connect
            // cycle does not see an obsolete `lastWorkingTransport`.
            transportPreferences.lastWorkingTransport = null
            transportPreferences.lastSuccessAt = null

            val hybrid = hybridTransportProvider()
            if (hybrid == null) {
                // No transport to tear down (cold start, hybrid not yet
                // initialised). The route-change bookkeeping has fired;
                // revoke and stop here. The next `requestServiceRestart`
                // will run the normal connect path on top of a fresh
                // routeEpoch.
                coordinator.revokeRouteChange(routeEpoch, reason = "hybrid_not_initialized")
                Log.i(
                    TAG,
                    "NETWORK_TRACE rewalk_substep_skip step=hybrid reason=hybrid_not_initialized",
                )
                return@withLock
            }

            // Step 3 — notify state machine of the network change so the
            // R3.6 sticky-recovery state advances to PendingNewSession
            // (when applicable). CE propagates; other throws revoke the
            // route change.
            try {
                hybrid.submitNetworkChangedEvent(clearsMode2Sticky = reason.clearsMode2Sticky)
            } catch (ce: CancellationException) {
                // P1 (2026-06-22): NonCancellable so the revoke is
                // guaranteed to complete even when the parent
                // coroutine is already cancelled. Without it,
                // revokeRouteChange's gateLock.withLock would itself
                // throw CE on entry, leaving the gate in a transient
                // state (routeEpoch bumped but no follow-up probe).
                withContext(NonCancellable) {
                    coordinator.revokeRouteChange(routeEpoch, reason = "submitNetworkChangedEvent_cancelled")
                }
                throw ce
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "NETWORK_TRACE rewalk_substep_error step=submitNetworkChangedEvent " +
                        "errorClass=${t::class.simpleName} message=${t.message?.take(120)}",
                )
                coordinator.revokeRouteChange(routeEpoch, reason = "submitNetworkChangedEvent_failed")
                return@withLock
            }

            // Step 4 — bounded teardown. Failure here (timeout, CE, or
            // any throw) MUST revoke the route change and skip release
            // / probe / restart: the old reconnect-loop may still be
            // alive and issuing a probe under that condition would
            // orphan it.
            val joined: Boolean = try {
                hybrid.disconnectAndJoin(timeoutMs = disconnectJoinTimeoutMs)
            } catch (ce: CancellationException) {
                withContext(NonCancellable) {
                    coordinator.revokeRouteChange(routeEpoch, reason = "disconnectAndJoin_cancelled")
                }
                throw ce
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "NETWORK_TRACE rewalk_substep_error step=disconnectAndJoin " +
                        "errorClass=${t::class.simpleName} message=${t.message?.take(120)}",
                )
                coordinator.revokeRouteChange(routeEpoch, reason = "disconnect_failed")
                return@withLock
            }
            if (!joined) {
                Log.w(
                    TAG,
                    "NETWORK_TRACE disconnect_join_timeout route_epoch=$routeEpoch",
                )
                coordinator.revokeRouteChange(routeEpoch, reason = "disconnect_join_timeout")
                return@withLock
            }

            // Step 5 — release cached probe/select state. CE propagates;
            // other throws revoke the route change WITHOUT issuing a
            // probe.
            try {
                releaseTransport()
            } catch (ce: CancellationException) {
                withContext(NonCancellable) {
                    coordinator.revokeRouteChange(routeEpoch, reason = "release_cancelled")
                }
                throw ce
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "NETWORK_TRACE rewalk_substep_error step=releaseTransport " +
                        "errorClass=${t::class.simpleName} message=${t.message?.take(120)}",
                )
                coordinator.revokeRouteChange(routeEpoch, reason = "release_failed")
                return@withLock
            }

            // Step 6 — issue the typed probe (StickyRecovery path only).
            // OpenReconnect skips this step: the gate is Open, no
            // sticky-recovery probe is needed, and the next
            // requestServiceRestart will run the normal connect path.
            val probeIssued: Boolean = when (outcome) {
                is RouteChangeOutcome.OpenReconnect -> false
                is RouteChangeOutcome.StickyRecovery -> {
                    val probeResult: ProbeIssueResult = try {
                        coordinator.issueProbeAfterRewalk(routeEpoch)
                    } catch (ce: CancellationException) {
                        withContext(NonCancellable) {
                            coordinator.revokeRouteChange(routeEpoch, reason = "issueProbe_cancelled")
                        }
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(
                            TAG,
                            "NETWORK_TRACE rewalk_substep_error step=issueProbeAfterRewalk " +
                                "errorClass=${t::class.simpleName} message=${t.message?.take(120)}",
                        )
                        coordinator.revokeRouteChange(routeEpoch, reason = "issueProbe_failed")
                        return@withLock
                    }
                    when (probeResult) {
                        is ProbeIssueResult.ProbeIssued -> {
                            Log.i(
                                TAG,
                                "NETWORK_TRACE rewalk_probe_issued route_epoch=$routeEpoch",
                            )
                            true
                        }
                        is ProbeIssueResult.Rejected -> {
                            val rejectReason = when (probeResult.reason) {
                                ProbeIssueRejectReason.GATE_NOT_QUIESCED -> "gate_not_quiesced"
                                ProbeIssueRejectReason.ROUTE_EPOCH_STALE -> "route_epoch_stale"
                                ProbeIssueRejectReason.REVOKED_BY_CONCURRENT_PATH -> "revoked_concurrent"
                            }
                            Log.w(
                                TAG,
                                "NETWORK_TRACE rewalk_probe_rejected route_epoch=$routeEpoch reason=$rejectReason",
                            )
                            return@withLock
                        }
                    }
                }
                is RouteChangeOutcome.QuiescencePreserved -> {
                    // unreachable — already returned above.
                    error("QuiescencePreserved must have returned earlier")
                }
            }

            // Step 7 — request service restart. On failure: if a probe
            // was issued (StickyRecovery path), revoke it so the gate
            // returns to Quiesced. If no probe was issued (OpenReconnect),
            // revoke the route change so future probes use a fresher
            // epoch. Either way `lastRewalkAtMs` is NOT bumped.
            try {
                requestServiceRestart(reason)
            } catch (ce: CancellationException) {
                withContext(NonCancellable) {
                    if (probeIssued) {
                        coordinator.revokeProbe(routeEpoch, reason = "service_restart_cancelled")
                    } else {
                        coordinator.revokeRouteChange(routeEpoch, reason = "service_restart_cancelled")
                    }
                }
                throw ce
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "NETWORK_TRACE rewalk_substep_error step=requestServiceRestart " +
                        "errorClass=${t::class.simpleName} message=${t.message?.take(120)}",
                )
                if (probeIssued) {
                    coordinator.revokeProbe(routeEpoch, reason = "service_restart_failed")
                } else {
                    coordinator.revokeRouteChange(routeEpoch, reason = "service_restart_failed")
                }
                return@withLock
            }

            // Full success — only NOW is the rate-limit budget consumed.
            lastRewalkAtMs = startMs
            val elapsedMs = nowMs() - startMs
            Log.i(
                TAG,
                "NETWORK_TRACE rewalk_done reason=$reason elapsedMs=$elapsedMs route_epoch=$routeEpoch",
            )
        }
    }

    /**
     * Legacy pre-quiescence rewalk sequence preserved verbatim for
     * call sites that haven't wired a [RewalkCoordinatorGateProvider]
     * (cold start, tests). Mirrors the pre-2c body exactly so existing
     * runbooks continue to work.
     */
    private suspend fun runLegacyRewalk(
        reason: NetworkChangeReason,
        snapshot: NetworkSnapshot,
        @Suppress("UNUSED_PARAMETER") startMs: Long,
    ) {
        transportPreferences.lastWorkingTransport = null
        transportPreferences.lastSuccessAt = null
        val hybrid = hybridTransportProvider()
        if (hybrid != null) {
            runCatching { hybrid.submitNetworkChangedEvent(clearsMode2Sticky = reason.clearsMode2Sticky) }
                .onFailure { e ->
                    Log.w(
                        TAG,
                        "NETWORK_TRACE rewalk_substep_error step=submitNetworkChangedEvent " +
                            "errorClass=${e::class.simpleName} message=${e.message?.take(120)}",
                    )
                }
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
        runCatching { releaseTransport() }
            .onFailure { e ->
                Log.w(
                    TAG,
                    "NETWORK_TRACE rewalk_substep_error step=releaseTransport " +
                        "errorClass=${e::class.simpleName} message=${e.message?.take(120)}",
                )
            }
        runCatching { requestServiceRestart(reason) }
            .onFailure { e ->
                Log.w(
                    TAG,
                    "NETWORK_TRACE rewalk_substep_error step=requestServiceRestart " +
                        "errorClass=${e::class.simpleName} message=${e.message?.take(120)}",
                )
            }
        Log.i(TAG, "NETWORK_TRACE rewalk_done reason=$reason (legacy)")
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
 * R3.6 (2026-06-20): whether this network-change reason represents a genuine
 * route change that should lift a sticky REST window into recovery mode.
 *
 * `true` for all reasons that indicate the IP path or carrier has genuinely
 * changed (Wi-Fi ↔ cellular, VPN toggled, network gained/lost, other).
 * `false` for [NetworkChangeReason.VALIDATED_CHANGED] only — the validated-
 * capability flip is an in-path quality signal, NOT a route change; the sticky
 * window should remain armed until a real route change arrives.
 */
internal val NetworkChangeReason.clearsMode2Sticky: Boolean
    get() = this != NetworkChangeReason.VALIDATED_CHANGED

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
