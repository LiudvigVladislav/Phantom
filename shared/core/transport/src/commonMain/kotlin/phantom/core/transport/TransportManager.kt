// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    // Diagnostic-only for now (PR-A1): caller reports whether a system VPN is
    // active at the moment we walk the chain. We just log it. Behavioural
    // changes (e.g. skipping Reality when a VPN is active) are deferred to
    // PR-A2 once the audit confirms whether Reality+VPN is genuinely broken
    // by upstream / Hetzner exit-IP filtering or just slow.
    private val vpnDetector: () -> Boolean = { false },
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
        val baseChain = reorderChain(strategy)
        val vpnActive = runCatching { vpnDetector() }.getOrDefault(false)
        // Reality runs over the system network stack, so when an Android VPN
        // is active the REALITY-mirrored TLS traffic is tunnelled through the
        // VPN egress before reaching Hetzner. The 2026-05-11 audit cycle
        // (Test #3 + Caddy access-log review) confirmed those packets do not
        // arrive at the relay's edge — Caddy logged zero requests in the
        // probe window. The cause sits below us (VPN-side DPI / MTU /
        // Hetzner ingress policy), and the symptom is a 20 s timeout that
        // adds latency without ever succeeding.
        //
        // Drop Reality from the walked chain when a VPN is active so we
        // skip straight to the next viable transport (Tor for Private/Ghost,
        // Direct→Tor for Standard). The non-VPN path is unchanged and
        // Reality remains the privacy-preferred default there.
        val orderedChain = if (vpnActive) {
            baseChain.filterNot { it == TransportKind.Reality }
                .ifEmpty { baseChain } // safety net; current chains always include Tor
        } else {
            baseChain
        }
        val realityFiltered = vpnActive && baseChain.contains(TransportKind.Reality)
        log.info(
            "connect: mode=${preferences.privacyMode} strategy=$strategy " +
                "ordered=$orderedChain vpnActive=$vpnActive realityFiltered=$realityFiltered",
        )
        log.info(
            "PROBE_TRACE chain_start strategy=$strategy ordered=$orderedChain " +
                "vpnActive=$vpnActive realityFiltered=$realityFiltered",
        )
        // PR-LTE-NETCHANGE1 (2026-05-28): explicit attribution line when
        // Reality was removed from the chain. The chain_start log above
        // surfaces `realityFiltered=true` as a boolean, but a dedicated
        // line with the reason keeps Test #88 Scenario D readable: when
        // "Tor on LTE" happens, the immediate next log line explains
        // WHY Reality was not even attempted. Today the only reason is
        // VPN; if more reasons emerge later (carrier-side block, ADR-
        // motivated suspension), the same line format extends.
        if (realityFiltered) {
            log.info("PROBE_TRACE reality_filtered reason=vpn_active")
        }
        _state.value = ManagerState.Probing(orderedChain.first())

        val failures = mutableListOf<TransportAttemptFailure>()
        for (kind in orderedChain) {
            _state.value = ManagerState.Probing(kind)
            val prepareStartMs = nowMs()
            log.info("PROBE_TRACE prepare_start kind=$kind")
            val socksPort = try {
                val port = prepareTransport(kind)
                val elapsedMs = nowMs() - prepareStartMs
                log.info("PROBE_TRACE prepare_done kind=$kind socksPort=$port elapsedMs=$elapsedMs")
                port
            } catch (t: Throwable) {
                val elapsedMs = nowMs() - prepareStartMs
                val reason = "${t::class.simpleName}: ${t.message ?: "<no message>"}"
                log.warn(
                    "PROBE_TRACE prepare_fail kind=$kind exception=${t::class.simpleName} " +
                        "message=${t.message ?: "<no message>"} elapsedMs=$elapsedMs",
                )
                log.warn("$kind subsystem prepare failed: $reason")
                failures += TransportAttemptFailure(kind, reason)
                continue
            }
            val outerTimeoutMs = probeTimeoutFor(kind)
            log.info("PROBE_TRACE probe_called kind=$kind socksPort=$socksPort outerTimeoutMs=$outerTimeoutMs")
            val probeStartMs = nowMs()
            val probeOk = try {
                val ok = withTimeout(outerTimeoutMs) { probe.reachable(kind, socksPort) }
                val elapsedMs = nowMs() - probeStartMs
                log.info("PROBE_TRACE probe_returned kind=$kind ok=$ok elapsedMs=$elapsedMs")
                ok
            } catch (_: TimeoutCancellationException) {
                val elapsedMs = nowMs() - probeStartMs
                log.warn("PROBE_TRACE probe_outer_timeout kind=$kind outerTimeoutMs=$outerTimeoutMs elapsedMs=$elapsedMs")
                false
            } catch (t: Throwable) {
                val elapsedMs = nowMs() - probeStartMs
                log.warn(
                    "PROBE_TRACE probe_threw kind=$kind exception=${t::class.simpleName} " +
                        "message=${t.message} elapsedMs=$elapsedMs",
                )
                log.warn("$kind probe threw: ${t::class.simpleName}: ${t.message}")
                false
            }
            if (probeOk) {
                val totalMs = nowMs() - prepareStartMs
                onSuccess(kind, strategy)
                _state.value = ManagerState.Connected(kind)
                log.info("PROBE_TRACE chain_attempt_success kind=$kind socksPort=$socksPort totalMs=$totalMs")
                return ConnectedTransport(kind, socksPort)
            }
            log.warn("$kind probe returned false")
            log.warn("PROBE_TRACE chain_attempt_failed kind=$kind reason=probe_failed")
            failures += TransportAttemptFailure(kind, "probe failed")
        }

        val failureSummary = failures.joinToString(",") { "${it.kind}:${it.reason.substringBefore(':')}" }
        log.warn("PROBE_TRACE chain_all_failed attempts=${failures.size} failures=[$failureSummary]")
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
     * Recover the strategy's preferred chain order, optionally honouring a
     * recent successful-transport hint.
     *
     * PR-RECV-DIAG1 v1.7 (Vladislav-architect 2026-05-27): hint hoisting is
     * RESTRICTED to the primary transport for the active strategy. A fallback
     * transport that previously succeeded must NOT become sticky-primary,
     * because that turns a single network glitch into a 24h Tor lock-in:
     *
     *   1. Wi-Fi has a transient issue → Direct fails → Reality fails → Tor wins.
     *   2. onSuccess saves `lastWorkingTransport=Tor`, `lastSuccessAt=now`.
     *   3. Network recovers (or user switches back to a healthy Wi-Fi).
     *   4. Next app start: reorderChain hoists Tor to the front of the
     *      Standard chain → ordered=[Tor, Direct, Reality] → Tor probe
     *      succeeds (it always does) → Direct is never attempted.
     *   5. onSuccess re-saves Tor → cycle repeats for up to 24 hours.
     *
     * Test #84.8 reproduced this exactly: Tecno on a Wi-Fi where Direct
     * worked yesterday went immediately to Tor onion today because the
     * previous test session had left Tor as the cached hint.
     *
     * New rule: a hint is only kept if it matches the strategy's primary
     * (chain[0]). A primary-success hint just skips the no-op of probing
     * something we already know works. Any non-primary hint (fallback)
     * is treated as stale and cleared, so the next attempt always walks
     * the policy chain from its declared primary.
     */
    private fun reorderChain(strategy: TransportStrategy): List<TransportKind> {
        val baseChain = strategy.chain
        val primary = baseChain.first()
        val hint = preferences.lastWorkingTransport
        val hintAt = preferences.lastSuccessAt
        log.info(
            "PROBE_TRACE hint_read hint=$hint hintAt=$hintAt primary=$primary " +
                "privacyMode=${preferences.privacyMode} base=$baseChain",
        )
        if (hint == null || hintAt == null) return baseChain

        val ageMs = nowMs() - hintAt
        val isFresh = ageMs in 0 until TransportPreferences.LAST_SUCCESS_TTL_MS
        if (!isFresh) {
            log.info(
                "PROBE_TRACE hint_ignored hint=$hint reason=stale ageMs=$ageMs",
            )
            preferences.lastWorkingTransport = null
            preferences.lastSuccessAt = null
            return baseChain
        }
        if (hint !in baseChain) {
            log.info(
                "PROBE_TRACE hint_ignored hint=$hint reason=out_of_chain base=$baseChain",
            )
            preferences.lastWorkingTransport = null
            preferences.lastSuccessAt = null
            return baseChain
        }
        if (hint != primary) {
            log.info(
                "PROBE_TRACE hint_ignored hint=$hint reason=fallback_hint " +
                    "primary=$primary ageMs=$ageMs",
            )
            preferences.lastWorkingTransport = null
            preferences.lastSuccessAt = null
            return baseChain
        }
        log.info(
            "PROBE_TRACE hint_kept hint=$hint reason=primary_match ageMs=$ageMs",
        )
        return baseChain
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
     *  - Tor (Briar wrapper + bridges): walked through the
     *    [BRIDGE_ROTATION_ORDER] (PR-C) — each profile gets its own
     *    per-attempt budget (180 s / 120 s / 180 s / 240 s = 720 s
     *    worst-case if every profile must be tried). Warm restarts
     *    return inside the first profile in seconds.
     *
     * The probe phase that follows has its own short [PROBE_TIMEOUT_MS] (5 s)
     * — we prove the path works end-to-end fast even after a slow init.
     */
    private suspend fun prepareTransport(kind: TransportKind): Int? = when (kind) {
        TransportKind.Direct -> null
        TransportKind.Tor -> prepareTorWithRotation()
        TransportKind.Reality -> {
            val xray = xrayServiceProvider()
            val xrayStartMs = nowMs()
            log.info("PROBE_TRACE xray_prepare_start")
            xray.start()
            log.info(
                "PROBE_TRACE xray_state state=Starting elapsedMs=${nowMs() - xrayStartMs}",
            )
            val terminal = try {
                withTimeout(REALITY_PREPARE_TIMEOUT_MS) {
                    xray.state.first { st ->
                        when (st) {
                            is XrayState.Starting -> {
                                // Emit state transitions as they arrive so we can see
                                // how long the native init phase takes.
                                log.info(
                                    "PROBE_TRACE xray_state state=Initialising " +
                                        "elapsedMs=${nowMs() - xrayStartMs}",
                                )
                                false
                            }
                            is XrayState.Ready, is XrayState.Failed -> true
                            else -> false
                        }
                    }
                }
            } catch (t: TimeoutCancellationException) {
                val elapsedMs = nowMs() - xrayStartMs
                log.warn(
                    "PROBE_TRACE xray_prepare_done ok=false reason=timeout totalMs=$elapsedMs",
                )
                throw t
            }
            when (terminal) {
                is XrayState.Ready -> {
                    val elapsedMs = nowMs() - xrayStartMs
                    log.info(
                        "PROBE_TRACE xray_state state=Ready socksPort=${terminal.socksPort} " +
                            "elapsedMs=$elapsedMs",
                    )
                    log.info(
                        "PROBE_TRACE xray_prepare_done ok=true socksPort=${terminal.socksPort} " +
                            "totalMs=$elapsedMs",
                    )
                    terminal.socksPort
                }
                is XrayState.Failed -> {
                    val elapsedMs = nowMs() - xrayStartMs
                    log.warn(
                        "PROBE_TRACE xray_state state=Failed message=${terminal.message} " +
                            "elapsedMs=$elapsedMs",
                    )
                    log.warn(
                        "PROBE_TRACE xray_prepare_done ok=false reason=failed totalMs=$elapsedMs",
                    )
                    error("Xray Failed: ${terminal.message}")
                }
                else -> error("Xray returned unexpected state: $terminal")
            }
        }
    }

    // ── Tor bridge rotation (PR-C, 2026-05-11) ────────────────────────────────

    /**
     * Walk [BRIDGE_ROTATION_ORDER] sequentially, calling [prepareTorOnce]
     * for each profile with its per-profile budget. Return the first
     * profile that reaches [TorState.Ready] within its budget. Between
     * attempts, fully stop tor so each profile starts from a clean
     * wrapper state (Briar's `enableBridges` can be called on a running
     * wrapper but the bridge re-selection only takes effect after a
     * full restart of tor's network state — stop+start is the safe
     * primitive).
     *
     * Why per-profile timeouts instead of one big budget:
     *   The 2026-05-11 audit cycle showed Tor on МТС can sit on a single
     *   percent for 3-4 minutes mid-bootstrap. With one 600 s budget we
     *   would burn the entire window on a stuck obfs4 attempt before
     *   noticing snowflake might have worked in 60 s. Splitting into
     *   shorter per-profile slots gives a stuck profile time to make a
     *   serious attempt while still letting the next profile try inside
     *   the user's patience window.
     *
     * Why obfs4 first (not webtunnel as the architect originally proposed):
     *   Empirical: Test 13 (2026-05-06) confirmed our WebTunnel handshakes
     *   trip the TSPU 16-KB curtain on Hetzner-hosted bridges. obfs4's
     *   uniform-random byte stream wire signature dodges that classifier
     *   entirely. Observed 2026-05-09 onwards on МТС: obfs4 to FlokiNET
     *   is the most reliable single-PT path. Webtunnel/snowflake follow
     *   as fallbacks for networks where obfs4 ports are blocked.
     */
    private suspend fun prepareTorWithRotation(): Int {
        val tor = torServiceProvider()
        val total = BRIDGE_ROTATION_ORDER.size
        var lastError: Throwable? = null
        for ((index, attempt) in BRIDGE_ROTATION_ORDER.withIndex()) {
            val attemptNum = index + 1
            log.info(
                "Tor rotation: attempt=$attemptNum/$total profile=${attempt.profile.displayName} " +
                    "budgetMs=${attempt.budgetMs}",
            )
            log.info(
                "PROBE_TRACE tor_rotation_start attempt=$attemptNum/$total " +
                    "profile=${attempt.profile.displayName} budgetMs=${attempt.budgetMs}",
            )
            // Defensive: ensure no leftover tor from a previous profile.
            // First iteration the service is already Off (chain walker
            // calls release() between connect generations); later
            // iterations need this stop to flip the wrapper out of any
            // half-bootstrapped state from the previous profile.
            if (index > 0) {
                runCatching { tor.stop() }
            }
            val socksPort = try {
                prepareTorOnce(
                    tor = tor,
                    profile = attempt.profile,
                    perProfileBudgetMs = attempt.budgetMs,
                    attemptNum = attemptNum,
                    totalAttempts = total,
                )
            } catch (t: Throwable) {
                log.warn(
                    "Tor rotation: attempt=$attemptNum/$total profile=${attempt.profile.displayName} " +
                        "raised ${t::class.simpleName}: ${t.message}",
                )
                log.warn(
                    "PROBE_TRACE tor_rotation_done attempt=$attemptNum " +
                        "profile=${attempt.profile.displayName} ok=false reason=${t::class.simpleName}",
                )
                lastError = t
                runCatching { tor.stop() }
                null
            }
            if (socksPort != null) {
                log.info(
                    "Tor rotation: attempt=$attemptNum/$total profile=${attempt.profile.displayName} " +
                        "READY socksPort=$socksPort",
                )
                log.info(
                    "PROBE_TRACE tor_rotation_done attempt=$attemptNum " +
                        "profile=${attempt.profile.displayName} ok=true socksPort=$socksPort",
                )
                return socksPort
            }
            log.warn(
                "Tor rotation: attempt=$attemptNum/$total profile=${attempt.profile.displayName} " +
                    "did not reach Ready in ${attempt.budgetMs} ms",
            )
            log.warn(
                "PROBE_TRACE tor_rotation_done attempt=$attemptNum " +
                    "profile=${attempt.profile.displayName} ok=false reason=budget_exhausted",
            )
        }
        // All profiles exhausted — surface a diagnostic error so the
        // outer chain walker logs "Tor subsystem prepare failed" with
        // a useful reason. Last error (if any) gives the most recent
        // upstream signal.
        val msg = "All ${total} bridge profiles exhausted" +
            (lastError?.let { " (last: ${it::class.simpleName}: ${it.message})" } ?: "")
        error(msg)
    }

    /**
     * Try one bridge profile. Returns the SOCKS port on success, or
     * null when the per-profile budget elapses without reaching Ready.
     * Throws on hard failure (Tor reported `Failed`, prepare-coroutine
     * cancelled, etc.) so the rotation walker can decide whether to
     * propagate or move on.
     *
     * The percent-streaming + time-keyed stage poller from PR-B is kept
     * here verbatim — it now also publishes the active [profile] +
     * [attemptNum] / [totalAttempts] into [TorProbingStatus] so the
     * notification text can show "Trying webtunnel… 50% (2/4) · Ghost".
     */
    private suspend fun prepareTorOnce(
        tor: TorService,
        profile: BridgeProfile,
        perProfileBudgetMs: Long,
        attemptNum: Int,
        totalAttempts: Int,
    ): Int? {
        log.info("Tor: start(profile=${profile.displayName})")
        tor.start(profile)
        val torStartMs = nowMs()
        log.info(
            "PROBE_TRACE tor_state profile=${profile.displayName} state=Starting elapsedMs=0",
        )
        val terminal: TorState = try {
            coroutineScope {
                var lastPercent = 0
                val pollerJob: Job = launch {
                    while (isActive) {
                        publishTorProbing(
                            percent = lastPercent,
                            torStartMs = torStartMs,
                            profile = profile,
                            attemptNum = attemptNum,
                            totalAttempts = totalAttempts,
                        )
                        delay(TOR_STAGE_POLL_INTERVAL_MS)
                    }
                }
                try {
                    withTimeout(perProfileBudgetMs) {
                        tor.state.first { st ->
                            when (st) {
                                is TorState.Bootstrapping -> {
                                    if (st.percent != lastPercent) {
                                        val elapsedMs = nowMs() - torStartMs
                                        log.info(
                                            "Tor bootstrap: profile=${profile.displayName} percent=${st.percent}",
                                        )
                                        log.info(
                                            "PROBE_TRACE tor_state profile=${profile.displayName} " +
                                                "state=Bootstrap percent=${st.percent} elapsedMs=$elapsedMs",
                                        )
                                        lastPercent = st.percent
                                        publishTorProbing(
                                            percent = st.percent,
                                            torStartMs = torStartMs,
                                            profile = profile,
                                            attemptNum = attemptNum,
                                            totalAttempts = totalAttempts,
                                        )
                                    }
                                    false
                                }
                                is TorState.Ready -> {
                                    val elapsedMs = nowMs() - torStartMs
                                    log.info(
                                        "Tor bootstrap: profile=${profile.displayName} Ready " +
                                            "socksPort=${st.socksPort}",
                                    )
                                    log.info(
                                        "PROBE_TRACE tor_state profile=${profile.displayName} " +
                                            "state=Ready socksPort=${st.socksPort} elapsedMs=$elapsedMs",
                                    )
                                    true
                                }
                                is TorState.Failed -> {
                                    val elapsedMs = nowMs() - torStartMs
                                    log.warn(
                                        "Tor bootstrap: profile=${profile.displayName} Failed " +
                                            "message=${st.message}",
                                    )
                                    log.warn(
                                        "PROBE_TRACE tor_state profile=${profile.displayName} " +
                                            "state=Failed message=${st.message} elapsedMs=$elapsedMs",
                                    )
                                    true
                                }
                                else -> false
                            }
                        }
                    }
                } finally {
                    pollerJob.cancel()
                }
            }
        } catch (_: TimeoutCancellationException) {
            // Per-profile budget elapsed. Not an error — caller advances
            // to the next profile. Defensive stop() so the next profile
            // does not inherit a half-bootstrapped wrapper.
            val elapsedMs = nowMs() - torStartMs
            log.warn(
                "Tor: profile=${profile.displayName} budget elapsed (${perProfileBudgetMs} ms)",
            )
            log.warn(
                "PROBE_TRACE tor_state profile=${profile.displayName} " +
                    "state=BudgetElapsed elapsedMs=$elapsedMs",
            )
            runCatching { tor.stop() }
            return null
        } catch (t: Throwable) {
            log.warn(
                "Tor: profile=${profile.displayName} prepare aborted " +
                    "(${t::class.simpleName}: ${t.message})",
            )
            runCatching { tor.stop() }
            throw t
        }
        return when (terminal) {
            is TorState.Ready -> terminal.socksPort
            is TorState.Failed -> {
                runCatching { tor.stop() }
                // Hard failure for this profile but not for the rotation
                // — return null so the walker can advance.
                null
            }
            else -> {
                runCatching { tor.stop() }
                null
            }
        }
    }

    /**
     * Push a fresh [ManagerState.Probing] with the current Tor probe
     * snapshot. Centralised so the percent-update path and the time-tick
     * path build the [TorProbingStatus] identically.
     */
    private fun publishTorProbing(
        percent: Int,
        torStartMs: Long,
        profile: BridgeProfile,
        attemptNum: Int,
        totalAttempts: Int,
    ) {
        val elapsedMs = nowMs() - torStartMs
        _state.value = ManagerState.Probing(
            kind = TransportKind.Tor,
            torStatus = TorProbingStatus(
                percent = percent,
                stage = TorBootstrapStage.forElapsedMs(elapsedMs),
                elapsedMs = elapsedMs,
                bridgeProfile = profile,
                attempt = attemptNum,
                totalAttempts = totalAttempts,
            ),
        )
    }

    // ── Hint maintenance ──────────────────────────────────────────────────────

    /**
     * Persist a hint about the just-succeeded transport — but ONLY if it
     * matches the strategy's primary. PR-RECV-DIAG1 v1.7
     * (Vladislav-architect 2026-05-27): a fallback success must not
     * become sticky-primary on subsequent app starts; see [reorderChain]
     * KDoc for the full Tor-lock-in cycle this prevents.
     *
     * Always resets `transportFailureCount` regardless of which transport
     * succeeded — any working transport is proof that we recovered from
     * whatever the failure streak was tracking.
     */
    private fun onSuccess(kind: TransportKind, strategy: TransportStrategy) {
        val primary = strategy.chain.first()
        if (kind == primary) {
            preferences.lastWorkingTransport = kind
            preferences.lastSuccessAt = nowMs()
            log.info("PROBE_TRACE hint_saved kind=$kind reason=primary_success")
        } else {
            preferences.lastWorkingTransport = null
            preferences.lastSuccessAt = null
            log.info(
                "PROBE_TRACE hint_not_saved kind=$kind reason=fallback_success primary=$primary",
            )
        }
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
     *   Direct  inner=10 s × 2 attempts + 400ms backoff  outer=25 s
     *   Reality inner=20 s  outer=30 s
     *   Tor     inner=60 s  outer=90 s
     */
    private fun probeTimeoutFor(kind: TransportKind): Long = when (kind) {
        TransportKind.Direct  -> 25_000L
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
         * Tor stage-poller tick (PR-B). Re-emits ManagerState.Probing with
         * the current [TorBootstrapStage] every 5 s so the foreground
         * notification + UI advance from "Connecting…" to "Searching for a
         * reachable route…" / "Slow…" / "Throttled…" even when the
         * underlying Tor percent is stalled. 5 s is fast enough to feel
         * responsive without flooding the StateFlow.
         */
        const val TOR_STAGE_POLL_INTERVAL_MS: Long = 5_000L

        /**
         * Bridge rotation order for [prepareTorWithRotation].
         *
         * Re-tuned 2026-05-12 (PR-E) after the Briar bridge-strategy
         * audit. The new ordering puts the kitchen-sink profile first
         * — Briar's empirical winning strategy — followed by the two
         * single-PT profiles whose bridge pools we just expanded with
         * Briar's `bridges-s-ru` (snowflake with Google AMP cache
         * front) and `bridges-n-zz` (9 non-default obfs4 entries).
         *
         * Why this beats PR-D's order:
         *
         *   - KitchenSink hands tor every bridge entry across every
         *     transport in one `enableBridges` call. Tor's own
         *     path-selection logic picks whichever bridge the network
         *     does not block — strictly better than serializing
         *     per-PT attempts because a network that allows e.g.
         *     snowflake-AMP-cache but blocks obfs4 bypasses the wait.
         *   - Snowflake (now Briar's RU-tuned set) is the second best
         *     stand-alone shot — the AMP-cache fronts on
         *     `www.google.com`, which a censor cannot block without
         *     breaking the local internet.
         *   - Obfs4 (now PHANTOM FlokiNET + 9 Briar non-default) gets
         *     a longer 180 s budget because there are now 10 obfs4
         *     bridges to walk through — empirically each takes ~10 s
         *     to fail-and-move-on.
         *   - MeekLite is the wholly-different-wire-signature
         *     fallback (HTTPS to phpmyadmin.net front via cdn77).
         *     Slow latency-wise but censors that block all of the
         *     above sometimes leave HTTPS-to-CDNs alone.
         *
         * WebTunnel is dropped from the rotation entirely. Test #5
         * showed it stalls at 10 % on TSPU-active networks, and
         * Briar deliberately does not configure it. It remains in the
         * `BridgeProfile` enum and `bridgesFor()` for future use /
         * manual testing but is not in the default walk.
         *
         * Mixed (the pre-PR-E historical "all our PHANTOM-controlled
         * bridges" profile) is dropped from the default walk in
         * favour of the strictly larger KitchenSink set.
         *
         * Total worst-case walk = 600 + 420 + 180 + 60 = 1260 s
         * (21 min). On healthy networks the kitchen-sink reaches
         * Ready in 60-180 s and the rotation ends.
         */
        val BRIDGE_ROTATION_ORDER: List<BridgeRotationAttempt> = listOf(
            BridgeRotationAttempt(BridgeProfile.KitchenSink,   budgetMs = 600_000L),
            BridgeRotationAttempt(BridgeProfile.SnowflakeOnly, budgetMs = 420_000L),
            BridgeRotationAttempt(BridgeProfile.Obfs4Only,     budgetMs = 180_000L),
            BridgeRotationAttempt(BridgeProfile.MeekLite,      budgetMs = 60_000L),
        )
    }
}

/**
 * One step in the [TransportManager.BRIDGE_ROTATION_ORDER] walk: which
 * [BridgeProfile] to try and how long to give it before advancing to
 * the next profile. Per-profile budgets keep a single stuck profile
 * from monopolising the user's patience window.
 */
data class BridgeRotationAttempt(
    val profile: BridgeProfile,
    val budgetMs: Long,
)

/** [TransportManager] state for foreground-service notification + UI. */
sealed class ManagerState {
    object Idle : ManagerState()

    /**
     * Currently probing [kind]. For Tor, [torStatus] carries the
     * bootstrap percent + the time-based [TorBootstrapStage] so the UI
     * can render a meaningful message during the multi-minute bridge
     * negotiation. Null for Direct / Reality probes (they are sub-second
     * and do not need staged messaging) and for the very first emission
     * before the poller has run.
     */
    data class Probing(
        val kind: TransportKind,
        val torStatus: TorProbingStatus? = null,
    ) : ManagerState()

    data class Connected(val kind: TransportKind) : ManagerState()
    data class AllFailed(val attempts: List<TransportAttemptFailure>) : ManagerState()
}

/**
 * Tor bootstrap snapshot for [ManagerState.Probing]. PR-B (2026-05-11)
 * lets the UI surface what Tor is actually doing during the long
 * bridge-negotiation window instead of a silent "Connecting via Tor…".
 *
 * `percent` is the last value reported by the Tor wrapper (0–100).
 * `stage` is derived purely from `elapsedMs` so it advances even when
 * the underlying percent stalls — that is the situation we most want to
 * surface ("This network is slowing Tor connections…" after 2 min, etc).
 * `elapsedMs` is the ms since the prepare-Tor branch started, useful
 * for the UI to show "Tor • 1:34 • 50 %" if it wants finer-grained
 * timing without re-deriving it from logs.
 */
data class TorProbingStatus(
    val percent: Int,
    val stage: TorBootstrapStage,
    val elapsedMs: Long,
    /**
     * Which bridge profile is currently being attempted. PR-C
     * (2026-05-11) walks profiles sequentially with their own per-
     * profile timeout; surfacing the active profile in the UI lets
     * the user see "Trying webtunnel… 50%" → "Trying snowflake…" so
     * the rotation is not invisible during a long bootstrap.
     */
    val bridgeProfile: BridgeProfile,
    /**
     * 1-based attempt index for the current rotation walk (1 = first
     * profile, 2 = second, …). Surfaced in logs / notification copy
     * so a user / reviewer can see "attempt 3 of 4" without re-deriving
     * it from the profile order. Useful in support diagnostics.
     */
    val attempt: Int,
    /** Total number of profiles in the rotation order (typically 4). */
    val totalAttempts: Int,
)

/**
 * Time-keyed Tor bootstrap stages for user-facing messages. Thresholds
 * picked from the architect-suggested staging (PR-B 2026-05-11):
 *
 *   0–15 s    Initial         "Connecting to Tor network…"
 *   15–45 s   Negotiating     "Negotiating censorship-resistant bridge…"
 *   45–120 s  Searching       "Searching for a reachable route…"
 *   120–240 s Slow            "This network is slowing Tor connections…"
 *   ≥ 240 s   Throttled       "Tor is heavily throttled on this network. VPN may improve connection speed."
 *
 * Wording is deliberate: never say "blocked" without certainty (we do
 * not have certainty without explicit DPI fingerprints), prefer "slow",
 * "restricted", "throttled", "difficult network". The "VPN may improve"
 * line on the Throttled stage is the one piece of actionable advice we
 * have for the user — it is consistent with what the 2026-05-11 audit
 * cycle empirically observed (Tor under VPN: ~2-7 min; without VPN on
 * МТС: 10+ min and frequent timeout).
 */
enum class TorBootstrapStage(val userText: String) {
    Initial("Connecting to Tor network…"),
    Negotiating("Negotiating censorship-resistant bridge…"),
    Searching("Searching for a reachable route…"),
    Slow("This network is slowing Tor connections…"),
    Throttled("Tor is heavily throttled on this network. VPN may improve connection speed."),
    ;

    companion object {
        fun forElapsedMs(elapsedMs: Long): TorBootstrapStage = when {
            elapsedMs < 15_000L -> Initial
            elapsedMs < 45_000L -> Negotiating
            elapsedMs < 120_000L -> Searching
            elapsedMs < 240_000L -> Slow
            else -> Throttled
        }
    }
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
