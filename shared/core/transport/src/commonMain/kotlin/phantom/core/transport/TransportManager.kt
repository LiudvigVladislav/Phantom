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
            log.info("Tor: start() called, current state=${tor.state.value}")
            tor.start()
            // Diagnostic: stream Bootstrapping percent + terminal state to
            // the log. Architect review 2026-05-11 noted that with only
            // `Probing(Tor)` on the wire we could not tell whether Tor
            // was stalling at 14 % vs 95 %, vs never starting at all.
            // Coroutine-launched on the same scope as the await below so
            // both finish together (cancelled by the outer withTimeout
            // when the budget elapses).
            val terminal = try {
                withTimeout(TOR_PREPARE_TIMEOUT_MS) {
                    var lastPercent = -1
                    tor.state.first { st ->
                        when (st) {
                            is TorState.Bootstrapping -> {
                                if (st.percent != lastPercent) {
                                    log.info("Tor bootstrap: percent=${st.percent}")
                                    lastPercent = st.percent
                                }
                                false
                            }
                            is TorState.Ready -> {
                                log.info("Tor bootstrap: Ready socksPort=${st.socksPort}")
                                true
                            }
                            is TorState.Failed -> {
                                log.warn("Tor bootstrap: Failed message=${st.message}")
                                true
                            }
                            else -> false
                        }
                    }
                }
            } catch (t: Throwable) {
                // Defensive cleanup per architect review: a stuck Tor
                // service must be torn down before the next chain walk
                // (Private → Tor → Ghost) so we don't await a corpse
                // descriptor / circuit pool. tor.stop() is idempotent.
                log.warn("Tor: prepare aborted (${t::class.simpleName}: ${t.message}) — calling tor.stop()")
                runCatching { tor.stop() }
                throw t
            }
            when (terminal) {
                is TorState.Ready -> terminal.socksPort
                is TorState.Failed -> {
                    runCatching { tor.stop() }
                    error("Tor Failed: ${terminal.message}")
                }
                else -> {
                    runCatching { tor.stop() }
                    error("Tor returned unexpected state: $terminal")
                }
            }
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
     * Outer probe budget per kind. Each entry MUST be at least as large
     * as the corresponding `KtorTransportProbe.callTimeoutFor()` value
     * — otherwise the outer `withTimeout` here cancels the inner OkHttp
     * `callTimeout` and the inner-budget bump never gets to fire.
     *
     * 2026-05-11 regression: Track A bumped Reality call-timeout to
     * 20 s but the outer budget here was still 10 s, so the inner bump
     * silently had no effect — every Reality probe under VPN failed
     * with `CancellationException: Timed out waiting for 10000 ms`.
     *
     * Buffers (outer = inner + 5 s) so a slow probe gets the inner
     * budget plus a small grace window for response decode + flow
     * resumption.
     *
     *   Direct  inner=4 s  outer=5 s
     *   Reality inner=20 s outer=30 s
     *   Tor     inner=60 s outer=90 s
     */
    private fun probeTimeoutFor(kind: TransportKind): Long = when (kind) {
        TransportKind.Direct  -> 5_000L
        TransportKind.Reality -> 30_000L
        TransportKind.Tor     -> 90_000L
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
