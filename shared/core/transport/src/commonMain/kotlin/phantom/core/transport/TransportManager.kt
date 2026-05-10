// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import phantom.core.xray.XrayService
import phantom.core.xray.XrayState

/**
 * Adaptive transport selection per ADR-020. Walks the [TransportStrategy]
 * chain implied by [TransportPreferences.privacyMode], starts the matching
 * subsystem, waits for SOCKS readiness, probes end-to-end reachability, and
 * returns the first [ConnectedTransport] that works. Records a hint so the
 * next connect tries the previously-successful path first.
 *
 * Lifecycle: [connect] is one-shot per call. The caller (foreground service)
 * passes the resulting `socksPort` to [RelayTransport.connect] for the actual
 * WebSocket. On disconnect, the foreground service calls [release] which
 * stops both subsystems.
 *
 * Threading: [connect] is a suspending function safe to call from any
 * coroutine context. Sequential per call — no internal concurrency.
 */
class TransportManager(
    private val torServiceProvider: () -> TorService,
    private val xrayServiceProvider: () -> XrayService,
    private val preferences: TransportPreferences,
    private val probe: TransportProbe,
    private val nowMs: () -> Long = { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() },
    private val log: TransportManagerLog = TransportManagerLog.Noop,
) {
    private val _state = MutableStateFlow<ManagerState>(ManagerState.Idle)
    val state: StateFlow<ManagerState> = _state.asStateFlow()

    /**
     * Walk the strategy chain for the current [PrivacyMode] and return the
     * first working transport. On success records the hint + resets the
     * failure counter. On total failure throws [NoTransportReachableException]
     * (also bumps the failure counter).
     */
    suspend fun connect(): ConnectedTransport {
        val strategy = TransportStrategy.from(preferences.privacyMode)
        val orderedChain = reorderChain(strategy)
        log.info("connect: mode=${preferences.privacyMode} strategy=$strategy ordered=$orderedChain")
        _state.value = ManagerState.Probing(orderedChain.first())

        val failures = mutableListOf<TransportAttemptFailure>()
        for (kind in orderedChain) {
            _state.value = ManagerState.Probing(kind)
            val socksPort = try {
                prepareTransport(kind)
            } catch (t: Throwable) {
                val reason = "${t::class.simpleName}: ${t.message ?: "<no message>"}"
                log.warn("$kind subsystem prepare failed: $reason")
                failures += TransportAttemptFailure(kind, reason)
                continue
            }
            val probeOk = try {
                withTimeout(probeTimeoutFor(kind)) { probe.reachable(kind, socksPort) }
            } catch (_: TimeoutCancellationException) {
                false
            } catch (t: Throwable) {
                log.warn("$kind probe threw: ${t::class.simpleName}: ${t.message}")
                false
            }
            if (probeOk) {
                onSuccess(kind)
                _state.value = ManagerState.Connected(kind)
                return ConnectedTransport(kind, socksPort)
            }
            log.warn("$kind probe returned false")
            failures += TransportAttemptFailure(kind, "probe failed")
        }

        onAllFailed()
        _state.value = ManagerState.AllFailed(failures.toList())
        throw NoTransportReachableException(failures)
    }

    /** Stop both subsystems. Idempotent; safe to call multiple times. */
    suspend fun release() {
        runCatching { xrayServiceProvider().stop() }
        runCatching { torServiceProvider().stop() }
        _state.value = ManagerState.Idle
    }

    // ── Chain ordering ────────────────────────────────────────────────────────

    /**
     * If a recent (`< LAST_SUCCESS_TTL_MS`) hint exists AND it is part of the
     * strategy's chain, hoist it to the front. Otherwise return the chain as
     * declared on [TransportStrategy]. Stale or out-of-chain hints are
     * ignored (and cleared, so they do not pile up).
     */
    private fun reorderChain(strategy: TransportStrategy): List<TransportKind> {
        val baseChain = strategy.chain
        val hint = preferences.lastWorkingTransport ?: return baseChain
        val hintAt = preferences.lastSuccessAt ?: return baseChain
        val isFresh = nowMs() - hintAt < TransportPreferences.LAST_SUCCESS_TTL_MS
        if (!isFresh) {
            preferences.lastWorkingTransport = null
            preferences.lastSuccessAt = null
            return baseChain
        }
        if (hint !in baseChain) return baseChain
        return listOf(hint) + baseChain.filter { it != hint }
    }

    // ── Subsystem lifecycle ───────────────────────────────────────────────────

    /**
     * Start the subsystem matching [kind] and return the SOCKS port the
     * caller should tunnel WSS through. Direct returns null without any
     * subsystem work. Tor / Xray suspend until the underlying state machine
     * reports `Ready`, bounded by a per-kind budget that allows for cold
     * native-init time:
     *
     *  - Reality (libXray gomobile JNI): up to [REALITY_PREPARE_TIMEOUT_MS]
     *    on first launch (~30 s for the 45 MB native lib). Warm restarts
     *    return immediately because the service is already in `Ready`.
     *  - Tor (Briar wrapper + bridges): up to [TOR_PREPARE_TIMEOUT_MS] on
     *    first bridge bootstrap (5–8 min on a censored network without a
     *    cached consensus). Warm restarts return in seconds.
     *
     * The probe phase that follows has its own short [PROBE_TIMEOUT_MS] (5 s)
     * — we prove the path works end-to-end fast even after a slow init.
     */
    private suspend fun prepareTransport(kind: TransportKind): Int? = when (kind) {
        TransportKind.Direct -> null
        TransportKind.Tor -> {
            val tor = torServiceProvider()
            tor.start()
            val ready = withTimeout(TOR_PREPARE_TIMEOUT_MS) {
                tor.state.first { it is TorState.Ready }
            } as TorState.Ready
            ready.socksPort
        }
        TransportKind.Reality -> {
            val xray = xrayServiceProvider()
            xray.start()
            val terminal = withTimeout(REALITY_PREPARE_TIMEOUT_MS) {
                xray.state.first { it is XrayState.Ready || it is XrayState.Failed }
            }
            when (terminal) {
                is XrayState.Ready -> terminal.socksPort
                is XrayState.Failed ->
                    error("Xray Failed: ${terminal.message}")
                else -> error("Xray returned unexpected state: $terminal")
            }
        }
    }

    // ── Hint maintenance ──────────────────────────────────────────────────────

    private fun onSuccess(kind: TransportKind) {
        preferences.lastWorkingTransport = kind
        preferences.lastSuccessAt = nowMs()
        preferences.transportFailureCount = 0
    }

    private fun onAllFailed() {
        preferences.transportFailureCount += 1
    }

    /**
     * Per-kind probe budget. Direct on a healthy network finishes in
     * <100 ms — 5 s covers worst-case cellular RTT with margin, longer
     * means real outage and we want to fall through fast. Reality is
     * already-warm in the libXray pool, but the first /health call after
     * the tunnel comes up still pays one TCP handshake + one TLS+REALITY
     * round-trip — 10 s is comfortable. Tor needs onion HS-descriptor
     * fetch + rendezvous on the second /health call (the first warmed
     * the bootstrap circuit but not the relay path) — 30 s prevents the
     * cold-circuit `Tor probe returned false` we hit on the second
     * Ghost-mode entry in the 2026-05-10 cross-device test.
     */
    private fun probeTimeoutFor(kind: TransportKind): Long = when (kind) {
        TransportKind.Direct  -> 5_000L
        TransportKind.Reality -> 10_000L
        TransportKind.Tor     -> 30_000L
    }

    companion object {
        /**
         * Default probe budget — kept for callers / tests that import the
         * constant directly. The runtime path uses [probeTimeoutFor] so
         * the per-kind budget above is what actually fires.
         */
        const val PROBE_TIMEOUT_MS: Long = 5_000L

        /**
         * Reality (libXray) prepare-phase budget. The libXray gomobile JNI
         * is a ~45 MB native library; first-launch class-init + DNS resolve
         * + REALITY handshake to the operator endpoint can take 15–30 s on
         * slow mobile carriers. After warm-up the same path is sub-second.
         * 30 s is the worst-case cold-start cap from the pre-ADR-020 single-
         * shot path (`XRAY_START_TIMEOUT_MS`) — preserving it ensures no
         * regression for the RU MTS Tecno baseline (Test 14, 2026-05-07).
         */
        const val REALITY_PREPARE_TIMEOUT_MS: Long = 30_000L

        /**
         * Tor (Briar wrapper + WebTunnel bridges) prepare-phase budget. A
         * fresh operator-controlled bridge with no cached descriptors and
         * no guard-relay reputation takes 5–8 minutes to bootstrap on a
         * censored network — `TOR_BOOTSTRAP_TIMEOUT_MS` from the pre-ADR-020
         * code was 600 s for exactly that reason. Sized for cold-start so
         * Ghost-mode users on first launch are not aborted prematurely.
         * Warm restarts return in seconds. After Stage 5D (multi-bridge
         * load-balanced bootstrap) ships this can tighten back to ~180 s.
         */
        const val TOR_PREPARE_TIMEOUT_MS: Long = 600_000L
    }
}

/** [TransportManager] state for foreground-service notification + UI. */
sealed class ManagerState {
    object Idle : ManagerState()
    data class Probing(val kind: TransportKind) : ManagerState()
    data class Connected(val kind: TransportKind) : ManagerState()
    data class AllFailed(val attempts: List<TransportAttemptFailure>) : ManagerState()
}

/**
 * End-to-end reachability probe. Implementations make a cheap GET against the
 * relay (typically `/health`) through the supplied SOCKS port and return true
 * iff the response is 200. Non-200 / network error / timeout → false.
 *
 * Lives behind an interface so the manager stays platform-neutral; the Android
 * wiring builds an OkHttp-via-SOCKS probe.
 */
fun interface TransportProbe {
    suspend fun reachable(kind: TransportKind, socksPort: Int?): Boolean
}

/** Small log abstraction; default is Noop so unit tests stay quiet. */
interface TransportManagerLog {
    fun info(msg: String)
    fun warn(msg: String)

    object Noop : TransportManagerLog {
        override fun info(msg: String) {}
        override fun warn(msg: String) {}
    }
}
