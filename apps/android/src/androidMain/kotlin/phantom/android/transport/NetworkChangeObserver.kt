// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import phantom.core.transport.RelayTransportConfig

/**
 * PR-LTE-NETCHANGE1 (2026-05-28) — Android observer for meaningful
 * network changes (Wi-Fi ↔ cellular, VPN appear/disappear, validated
 * capability change, network lost/regained).
 *
 * **Design notes** (per architect guardrail #2, 2026-05-28):
 *
 * - One `ConnectivityManager.NetworkCallback` instance per process,
 *   registered in [register] and unregistered in [unregister]. Held
 *   for the lifetime of [phantom.android.service.PhantomMessagingService]
 *   (or [phantom.android.di.AppContainer]; the owner is configurable).
 *
 * - **Debounce + fresh snapshot.** Android emits a flurry of callbacks
 *   (5–8 over ~500 ms) during a single real transition. Instead of
 *   acting on each callback's payload, the observer:
 *   1. On ANY callback (`onAvailable` / `onLost` / `onCapabilitiesChanged`),
 *      cancel the pending debounce job and schedule a new one with
 *      delay [RelayTransportConfig.NETWORK_CHANGE_DEBOUNCE_MS].
 *   2. When the debounce elapses, read the CURRENT state via
 *      `ConnectivityManager.activeNetwork` + capabilities — NOT the
 *      payload of the triggering callback. Android has settled by
 *      then, and the active-network read returns the post-transition
 *      truth.
 *   3. Diff against [lastAcceptedSnapshot]; if meaningful (per the
 *      classifier below), call [coordinator.onMeaningfulChange]; if
 *      not, drop silently.
 *
 * - **Meaningful-change classifier.** A change is meaningful if AT
 *   LEAST ONE of the following differs between the previous accepted
 *   snapshot and the current one:
 *   - `networkPresent` (false ↔ true)
 *   - `transportClass` (WIFI / CELLULAR / ETHERNET / OTHER / NONE)
 *   - `vpnActive` (true ↔ false)
 *   - `validated` (true ↔ false)
 *
 *   Trivial changes — signal strength, link bandwidth jitter, MTU —
 *   do not differ on these four axes and are therefore dropped.
 *
 * - **First snapshot** is always sent as `FIRST_SNAPSHOT` so the
 *   coordinator can initialise its `lastNetworkPresent` mark and
 *   (for the very first run) bypass the rate-limit if the snapshot
 *   shows `networkPresent=true`.
 *
 * Logs under tag `PhantomHybrid`:
 *
 * ```
 * NETWORK_TRACE changed old=<...> new=<...> validated=<bool> vpnActive=<bool>
 * NETWORK_TRACE callback_ignored reason=trivial_change
 * ```
 */
internal class NetworkChangeObserver(
    private val context: Context,
    private val scope: CoroutineScope,
    private val coordinator: TransportRewalkCoordinator,
) {

    private val cm: ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile private var registered: Boolean = false

    @Volatile private var debounceJob: Job? = null

    /** Last snapshot that was sent to the coordinator. `null` until first run. */
    @Volatile private var lastAcceptedSnapshot: NetworkSnapshot? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleDebounce(triggerReason = "onAvailable")
        }

        override fun onLost(network: Network) {
            scheduleDebounce(triggerReason = "onLost")
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            scheduleDebounce(triggerReason = "onCapabilitiesChanged")
        }
    }

    /**
     * Register the callback with [ConnectivityManager]. Idempotent — a
     * second register call is a no-op.
     */
    fun register() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess {
                registered = true
                Log.i(TAG, "NETWORK_TRACE observer_registered")
                // Force an initial snapshot send so the coordinator
                // sees the network state even if no callback fires
                // before the first send/receive attempt.
                scheduleDebounce(triggerReason = "initial")
            }
            .onFailure { e ->
                Log.w(
                    TAG,
                    "NETWORK_TRACE observer_register_failed errorClass=${e::class.simpleName} " +
                        "message=${e.message?.take(120)}",
                )
            }
    }

    fun unregister() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
            .onFailure { e ->
                Log.w(
                    TAG,
                    "NETWORK_TRACE observer_unregister_failed errorClass=${e::class.simpleName} " +
                        "message=${e.message?.take(120)}",
                )
            }
        registered = false
        debounceJob?.cancel()
        debounceJob = null
        Log.i(TAG, "NETWORK_TRACE observer_unregistered")
    }

    /**
     * Cancel any pending debounce job and schedule a fresh one. After
     * the debounce window elapses, [evaluate] runs against the CURRENT
     * `ConnectivityManager` snapshot.
     */
    private fun scheduleDebounce(triggerReason: String) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(RelayTransportConfig.NETWORK_CHANGE_DEBOUNCE_MS)
            evaluate(triggerReason)
        }
    }

    private fun evaluate(triggerReason: String) {
        val current = snapshotCurrentState()
        val previous = lastAcceptedSnapshot

        val (isMeaningful, reason) = classify(previous, current)
        if (!isMeaningful) {
            Log.i(
                TAG,
                "NETWORK_TRACE callback_ignored reason=trivial_change trigger=$triggerReason " +
                    "current=$current",
            )
            return
        }

        Log.i(
            TAG,
            "NETWORK_TRACE changed old=${previous?.transportClass ?: "FIRST"} " +
                "new=${current.transportClass} validated=${current.validated} " +
                "vpnActive=${current.vpnActive} networkPresent=${current.networkPresent} " +
                "trigger=$triggerReason resolvedReason=$reason",
        )

        lastAcceptedSnapshot = current
        coordinator.onMeaningfulChange(reason, current)
    }

    /**
     * Read the current network state from [ConnectivityManager]. Per
     * architect guardrail #2 (2026-05-28), this is the post-debounce
     * fresh read — NOT the callback payload — so the snapshot reflects
     * what Android has settled on, not a moment-of-transition partial.
     */
    private fun snapshotCurrentState(): NetworkSnapshot {
        val activeNetwork = cm.activeNetwork
        if (activeNetwork == null) {
            return NetworkSnapshot(
                networkPresent = false,
                transportClass = NetworkTransportClass.NONE,
                vpnActive = false,
                validated = false,
            )
        }
        val caps = cm.getNetworkCapabilities(activeNetwork)
        if (caps == null) {
            return NetworkSnapshot(
                networkPresent = false,
                transportClass = NetworkTransportClass.NONE,
                vpnActive = false,
                validated = false,
            )
        }
        val transportClass = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransportClass.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransportClass.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransportClass.ETHERNET
            else -> NetworkTransportClass.OTHER
        }
        // Detect VPN by capability — robust against vendor variations
        // in how VPN transports are reported. NET_CAPABILITY_NOT_VPN
        // is FALSE when a VPN is active, so we invert.
        val vpnActive = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return NetworkSnapshot(
            networkPresent = true,
            transportClass = transportClass,
            vpnActive = vpnActive,
            validated = validated,
        )
    }

    /**
     * Classify the change between [previous] and [current]. Returns
     * `Pair<isMeaningful, NetworkChangeReason>`. Order of reason
     * resolution matters: a single snapshot diff may flip multiple
     * axes (e.g. Wi-Fi off → cellular on flips both networkPresent
     * sequence AND transportClass); the resolved reason picks the
     * dominant one for the rewalk log.
     */
    private fun classify(
        previous: NetworkSnapshot?,
        current: NetworkSnapshot,
    ): Pair<Boolean, NetworkChangeReason> {
        if (previous == null) {
            return Pair(true, NetworkChangeReason.FIRST_SNAPSHOT)
        }
        // Dominant: network presence transitions.
        if (!previous.networkPresent && current.networkPresent) {
            return Pair(true, NetworkChangeReason.NETWORK_AVAILABLE)
        }
        if (previous.networkPresent && !current.networkPresent) {
            return Pair(true, NetworkChangeReason.NETWORK_LOST)
        }
        // VPN transitions.
        if (!previous.vpnActive && current.vpnActive) {
            return Pair(true, NetworkChangeReason.VPN_ADDED)
        }
        if (previous.vpnActive && !current.vpnActive) {
            return Pair(true, NetworkChangeReason.VPN_REMOVED)
        }
        // Transport-class transitions.
        if (previous.transportClass != current.transportClass) {
            return when {
                previous.transportClass == NetworkTransportClass.WIFI &&
                    current.transportClass == NetworkTransportClass.CELLULAR ->
                    Pair(true, NetworkChangeReason.WIFI_TO_CELLULAR)
                previous.transportClass == NetworkTransportClass.CELLULAR &&
                    current.transportClass == NetworkTransportClass.WIFI ->
                    Pair(true, NetworkChangeReason.CELLULAR_TO_WIFI)
                else -> Pair(true, NetworkChangeReason.OTHER)
            }
        }
        // Validated flipped.
        if (previous.validated != current.validated) {
            return Pair(true, NetworkChangeReason.VALIDATED_CHANGED)
        }
        // No meaningful axis changed.
        return Pair(false, NetworkChangeReason.OTHER)
    }

    companion object {
        private const val TAG = "PhantomHybrid"
    }
}
