// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import phantom.core.xray.XrayService
import phantom.core.xray.XrayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies that [TransportManager.connect] emits structured `PROBE_TRACE` log
 * lines at every phase of the chain walk. These tests exist to prove that the
 * next real-device logcat capture (post PR-Diag) will contain enough signal to
 * answer "which phase stalled?" without SSH + Caddy cross-referencing.
 *
 * Approach: wire a [CapturingTransportManagerLog] and assert the sequence of
 * `PROBE_TRACE` prefixed messages. No changes to chain logic — pure logging
 * coverage.
 */
class ProbeTraceTransportManagerTest {

    // ── Success path: Direct connects immediately ─────────────────────────────

    @Test
    fun directSuccessEmitsExpectedProbeTraceLines() = runTest {
        val log = CapturingTransportManagerLog()
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = InMemoryTransportPreferences(PrivacyMode.Standard),
            probe = TransportProbe { kind, _ -> kind == TransportKind.Direct },
            nowMs = { 1_000L },
            log = log,
        )

        mgr.connect()

        val traces = log.probeTraces()
        // Chain start must be the first PROBE_TRACE
        assertTrue(
            traces.any { it.startsWith("chain_start") },
            "expected chain_start; got: $traces",
        )
        // Direct prepare
        assertTrue(traces.any { it.startsWith("prepare_start kind=Direct") }, "prepare_start Direct missing")
        assertTrue(traces.any { it.startsWith("prepare_done kind=Direct socksPort=null") }, "prepare_done Direct missing")
        // Probe called / returned
        assertTrue(traces.any { it.startsWith("probe_called kind=Direct") }, "probe_called Direct missing")
        assertTrue(traces.any { it.startsWith("probe_returned kind=Direct ok=true") }, "probe_returned ok=true missing")
        // Chain success
        assertTrue(
            traces.any { it.startsWith("chain_attempt_success kind=Direct") },
            "chain_attempt_success missing; traces=$traces",
        )
        // No failure lines should appear
        assertTrue(
            traces.none { it.startsWith("chain_all_failed") },
            "unexpected chain_all_failed",
        )
    }

    // ── Failure path: all transports fail ─────────────────────────────────────

    @Test
    fun allFailedEmitsChainAllFailed() = runTest {
        val log = CapturingTransportManagerLog()
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = InMemoryTransportPreferences(PrivacyMode.Ghost),
            probe = TransportProbe { _, _ -> false },
            nowMs = { 0L },
            log = log,
        )

        assertFailsWith<NoTransportReachableException> { mgr.connect() }

        val traces = log.probeTraces()
        assertTrue(
            traces.any { it.startsWith("chain_all_failed") },
            "chain_all_failed missing; traces=$traces",
        )
        // Expect at least one chain_attempt_failed
        assertTrue(
            traces.any { it.startsWith("chain_attempt_failed kind=Tor") },
            "chain_attempt_failed Tor missing",
        )
    }

    // ── Failure path: probe returns false → chain_attempt_failed ─────────────

    @Test
    fun probeReturnedFalseEmitsChainAttemptFailed() = runTest {
        val log = CapturingTransportManagerLog()
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = InMemoryTransportPreferences(PrivacyMode.Standard),
            probe = TransportProbe { kind, _ ->
                // Direct fails, Reality fails, only Tor succeeds
                kind == TransportKind.Tor
            },
            nowMs = { 0L },
            log = log,
        )

        mgr.connect()

        val traces = log.probeTraces()
        // Both Direct and Reality probe_returned ok=false → chain_attempt_failed
        assertTrue(
            traces.any { it.startsWith("probe_returned kind=Direct ok=false") },
            "probe_returned Direct ok=false missing; traces=$traces",
        )
        assertTrue(
            traces.any { it.startsWith("chain_attempt_failed kind=Direct") },
            "chain_attempt_failed Direct missing",
        )
        // Tor must succeed
        assertTrue(
            traces.any { it.startsWith("chain_attempt_success kind=Tor") },
            "chain_attempt_success Tor missing",
        )
    }

    // ── Reality path: xray_prepare_start and xray_prepare_done emitted ────────

    @Test
    fun realityPrepareEmitsXrayTraceLines() = runTest {
        val log = CapturingTransportManagerLog()
        val readyXray = object : XrayService {
            private val flow = MutableStateFlow<XrayState>(XrayState.Ready(socksPort = 10808))
            override val state: StateFlow<XrayState> = flow.asStateFlow()
            override suspend fun start() { /* already Ready */ }
            override suspend fun stop() { flow.value = XrayState.Off }
        }
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { readyXray },
            preferences = InMemoryTransportPreferences(PrivacyMode.Private), // Reality first
            probe = TransportProbe { kind, _ -> kind == TransportKind.Reality },
            nowMs = { 0L },
            log = log,
        )

        mgr.connect()

        val traces = log.probeTraces()
        assertTrue(
            traces.any { it == "xray_prepare_start" },
            "xray_prepare_start missing; traces=$traces",
        )
        assertTrue(
            traces.any { it.startsWith("xray_prepare_done ok=true socksPort=10808") },
            "xray_prepare_done ok=true missing; traces=$traces",
        )
        assertTrue(
            traces.any { it.startsWith("xray_state state=Ready socksPort=10808") },
            "xray_state Ready missing",
        )
    }

    // ── Reality path: xray_prepare_done ok=false when XrayState.Failed ───────

    @Test
    fun realityFailedEmitsXrayPrepareFail() = runTest {
        val log = CapturingTransportManagerLog()
        val failedXray = object : XrayService {
            private val flow = MutableStateFlow<XrayState>(XrayState.Failed("init error"))
            override val state: StateFlow<XrayState> = flow.asStateFlow()
            override suspend fun start() { /* state already Failed */ }
            override suspend fun stop() { flow.value = XrayState.Off }
        }
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { failedXray },
            preferences = InMemoryTransportPreferences(PrivacyMode.Private), // Reality first → Tor fallback
            probe = TransportProbe { kind, _ -> kind == TransportKind.Tor },
            nowMs = { 0L },
            log = log,
        )

        mgr.connect() // Reality fails → falls through to Tor

        val traces = log.probeTraces()
        assertTrue(
            traces.any { it.startsWith("xray_state state=Failed") },
            "xray_state Failed missing; traces=$traces",
        )
        assertTrue(
            traces.any { it.startsWith("xray_prepare_done ok=false reason=failed") },
            "xray_prepare_done failed missing",
        )
        // Reality prepare throws → prepare_fail logged by connect()
        assertTrue(
            traces.any { it.startsWith("prepare_fail kind=Reality") },
            "prepare_fail Reality missing",
        )
    }

    // ── Tor path: tor_rotation_start and tor_rotation_done emitted ────────────

    @Test
    fun torRotationEmitsRotationTraceLines() = runTest {
        val log = CapturingTransportManagerLog()
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = InMemoryTransportPreferences(PrivacyMode.Ghost), // Tor only
            probe = TransportProbe { _, _ -> true },
            nowMs = { 0L },
            log = log,
        )

        mgr.connect()

        val traces = log.probeTraces()
        // First rotation attempt must be logged
        assertTrue(
            traces.any { it.startsWith("tor_rotation_start attempt=1") },
            "tor_rotation_start missing; traces=$traces",
        )
        // The fake Tor is immediately Ready so rotation_done ok=true fires
        assertTrue(
            traces.any { it.startsWith("tor_rotation_done attempt=1") && it.contains("ok=true") },
            "tor_rotation_done ok=true missing; traces=$traces",
        )
    }

    // ── Chain start contains VPN flags ────────────────────────────────────────

    @Test
    fun chainStartContainsVpnFields() = runTest {
        val log = CapturingTransportManagerLog()
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = InMemoryTransportPreferences(PrivacyMode.Standard),
            probe = TransportProbe { _, _ -> true },
            nowMs = { 0L },
            log = log,
            vpnDetector = { false },
        )

        mgr.connect()

        val chainStart = log.probeTraces().first { it.startsWith("chain_start") }
        assertTrue(chainStart.contains("vpnActive=false"), "vpnActive missing: $chainStart")
        assertTrue(chainStart.contains("realityFiltered=false"), "realityFiltered missing: $chainStart")
    }

    // ── VPN active filters Reality and logs the reason ────────────────────────

    @Test
    fun vpnActiveFiltersRealityFromChainAndLogsReason() = runTest {
        // PR-LTE-NETCHANGE1 (2026-05-28) — when the VPN detector reports
        // active, Reality must be removed from the Standard chain AND a
        // dedicated `PROBE_TRACE reality_filtered reason=vpn_active` line
        // must follow `chain_start`, so the log alone explains why the
        // ordered chain is `[Direct, Tor]` instead of `[Direct, Reality, Tor]`.
        // Test #88 Scenario C depends on this attribution.
        val log = CapturingTransportManagerLog()
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = InMemoryTransportPreferences(PrivacyMode.Standard),
            probe = TransportProbe { kind, _ -> kind == TransportKind.Direct },
            nowMs = { 0L },
            log = log,
            vpnDetector = { true }, // VPN ON
        )

        mgr.connect()

        val traces = log.probeTraces()
        // 1. chain_start surfaces both flags and the Reality-stripped ordering.
        val chainStart = traces.first { it.startsWith("chain_start") }
        assertTrue(chainStart.contains("vpnActive=true"), "vpnActive=true missing: $chainStart")
        assertTrue(chainStart.contains("realityFiltered=true"), "realityFiltered=true missing: $chainStart")
        assertTrue(
            chainStart.contains("ordered=[Direct, Tor]"),
            "expected Reality filtered out of ordered chain; got: $chainStart",
        )
        // 2. The dedicated reason line follows chain_start.
        val realityFiltered = traces.firstOrNull { it == "reality_filtered reason=vpn_active" }
        assertTrue(
            realityFiltered != null,
            "expected `reality_filtered reason=vpn_active`; got: $traces",
        )
    }

    @Test
    fun vpnInactiveDoesNotEmitRealityFilteredLine() = runTest {
        // Sanity: when vpnDetector returns false, no reality_filtered line
        // should appear — even though the chain still includes Reality. This
        // guards against a future bug where the line fires for ANY chain
        // containing Reality, regardless of VPN state.
        val log = CapturingTransportManagerLog()
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = InMemoryTransportPreferences(PrivacyMode.Standard),
            probe = TransportProbe { kind, _ -> kind == TransportKind.Direct },
            nowMs = { 0L },
            log = log,
            vpnDetector = { false }, // VPN OFF
        )

        mgr.connect()

        val traces = log.probeTraces()
        assertTrue(
            traces.none { it.startsWith("reality_filtered") },
            "unexpected reality_filtered line when VPN off; traces=$traces",
        )
    }

    // ── Prepare_start/done ordering ───────────────────────────────────────────

    @Test
    fun prepareStartPrecedesProbeCalled() = runTest {
        val log = CapturingTransportManagerLog()
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = InMemoryTransportPreferences(PrivacyMode.Standard),
            probe = TransportProbe { _, _ -> true },
            nowMs = { 0L },
            log = log,
        )

        mgr.connect()

        val traces = log.probeTraces()
        val prepareIdx = traces.indexOfFirst { it.startsWith("prepare_start kind=Direct") }
        val probeIdx   = traces.indexOfFirst { it.startsWith("probe_called kind=Direct") }
        assertTrue(prepareIdx >= 0, "prepare_start not found")
        assertTrue(probeIdx >= 0, "probe_called not found")
        assertTrue(prepareIdx < probeIdx, "prepare_start must precede probe_called")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fakeTor(): TorService = object : TorService {
        private val flow = MutableStateFlow<TorState>(TorState.Ready(socksPort = 9050))
        override val state: StateFlow<TorState> = flow.asStateFlow()
        override suspend fun start(bridgeProfile: BridgeProfile) { /* already Ready */ }
        override suspend fun stop() { flow.value = TorState.Off }
    }

    private fun fakeXray(): XrayService = object : XrayService {
        private val flow = MutableStateFlow<XrayState>(XrayState.Ready(socksPort = 10808))
        override val state: StateFlow<XrayState> = flow.asStateFlow()
        override suspend fun start() { /* already Ready */ }
        override suspend fun stop() { flow.value = XrayState.Off }
    }
}

/**
 * [TransportManagerLog] implementation that captures all messages so tests can
 * assert on `PROBE_TRACE`-prefixed entries without depending on logcat or stdout.
 */
class CapturingTransportManagerLog : TransportManagerLog {
    private val _messages = mutableListOf<String>()

    override fun info(msg: String) {
        _messages += msg
    }

    override fun warn(msg: String) {
        _messages += msg
    }

    /** Returns the body of every message that starts with `"PROBE_TRACE "`. */
    fun probeTraces(): List<String> = _messages
        .filter { it.startsWith("PROBE_TRACE ") }
        .map { it.removePrefix("PROBE_TRACE ") }

    /** All captured messages (for debugging failing assertions). */
    fun all(): List<String> = _messages.toList()
}
