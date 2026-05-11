// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import phantom.core.xray.XrayService
import phantom.core.xray.XrayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportManagerTest {

    @Test
    fun strategyMappingMatchesPrivacyMode() {
        assertEquals(TransportStrategy.DIRECT_FIRST,  TransportStrategy.from(PrivacyMode.Standard))
        assertEquals(TransportStrategy.REALITY_FIRST, TransportStrategy.from(PrivacyMode.Private))
        assertEquals(TransportStrategy.TOR_FIRST,     TransportStrategy.from(PrivacyMode.Ghost))
    }

    @Test
    fun chainsHaveExpectedShape() {
        assertEquals(
            listOf(TransportKind.Direct, TransportKind.Reality, TransportKind.Tor),
            TransportStrategy.DIRECT_FIRST.chain,
        )
        assertEquals(
            listOf(TransportKind.Reality, TransportKind.Tor),
            TransportStrategy.REALITY_FIRST.chain,
        )
        assertEquals(
            listOf(TransportKind.Tor),
            TransportStrategy.TOR_FIRST.chain,
        )
    }

    @Test
    fun connectReturnsFirstReachableTransport() = runTest {
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = prefs,
            probe = TransportProbe { kind, _ -> kind == TransportKind.Direct }, // direct works
            nowMs = { 1_000L },
        )
        val connected = mgr.connect()
        assertEquals(TransportKind.Direct, connected.kind)
        assertNull(connected.socksPort)
        assertEquals(TransportKind.Direct, prefs.lastWorkingTransport)
        assertEquals(1_000L, prefs.lastSuccessAt)
        assertEquals(0, prefs.transportFailureCount)
    }

    @Test
    fun connectFallsThroughToNextOnProbeFailure() = runTest {
        val prefs = InMemoryTransportPreferences(PrivacyMode.Private)
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = prefs,
            probe = TransportProbe { kind, _ -> kind == TransportKind.Tor }, // only tor works
            nowMs = { 5_000L },
        )
        val connected = mgr.connect()
        assertEquals(TransportKind.Tor, connected.kind)
        assertEquals(9050, connected.socksPort)
        assertEquals(TransportKind.Tor, prefs.lastWorkingTransport)
    }

    @Test
    fun connectThrowsWhenChainExhausted() = runTest {
        val prefs = InMemoryTransportPreferences(PrivacyMode.Ghost)
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = prefs,
            probe = TransportProbe { _, _ -> false },
            nowMs = { 0L },
        )
        val ex = assertFailsWith<NoTransportReachableException> { mgr.connect() }
        assertEquals(1, ex.attempts.size)
        assertEquals(TransportKind.Tor, ex.attempts.first().kind)
        assertEquals(1, prefs.transportFailureCount)
        assertNull(prefs.lastWorkingTransport)
    }

    @Test
    fun ghostNeverFallsThroughOutOfTor() = runTest {
        // Sanity: even with REALITY+Direct probes returning true, Ghost mode
        // must only ever try Tor — and fail if Tor fails.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Ghost)
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = prefs,
            probe = TransportProbe { kind, _ ->
                kind == TransportKind.Direct || kind == TransportKind.Reality
            },
            nowMs = { 0L },
        )
        val ex = assertFailsWith<NoTransportReachableException> { mgr.connect() }
        assertEquals(listOf(TransportKind.Tor), ex.attempts.map { it.kind })
    }

    @Test
    fun lastWorkingHintReordersChain() = runTest {
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard).apply {
            lastWorkingTransport = TransportKind.Reality
            lastSuccessAt = 0L
        }
        val probedKinds = mutableListOf<TransportKind>()
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = prefs,
            probe = TransportProbe { kind, _ ->
                probedKinds += kind
                kind == TransportKind.Reality
            },
            nowMs = { TransportPreferences.LAST_SUCCESS_TTL_MS / 2 }, // hint is fresh
        )
        val connected = mgr.connect()
        assertEquals(TransportKind.Reality, connected.kind)
        // Reality should be the FIRST probed kind even though the chain
        // would naturally start with Direct.
        assertEquals(TransportKind.Reality, probedKinds.first())
    }

    @Test
    fun staleHintIsClearedAndChainReturnsToBaseOrder() = runTest {
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard).apply {
            lastWorkingTransport = TransportKind.Tor
            lastSuccessAt = 0L
        }
        val probedKinds = mutableListOf<TransportKind>()
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = prefs,
            probe = TransportProbe { kind, _ ->
                probedKinds += kind
                kind == TransportKind.Direct
            },
            nowMs = { TransportPreferences.LAST_SUCCESS_TTL_MS + 1 }, // hint is stale
        )
        mgr.connect()
        // Stale hint was cleared inside reorderChain so the walk started
        // from the base order (Direct first), not from the stale Tor hint.
        assertEquals(TransportKind.Direct, probedKinds.first())
        // The success path then rewrote the hint to the actually-working
        // transport, so it is non-null again — this is the steady state.
        assertEquals(TransportKind.Direct, prefs.lastWorkingTransport)
    }

    @Test
    fun successResetsFailureCounter() = runTest {
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard).apply {
            transportFailureCount = 5
        }
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = prefs,
            probe = TransportProbe { kind, _ -> kind == TransportKind.Direct },
            nowMs = { 0L },
        )
        mgr.connect()
        assertEquals(0, prefs.transportFailureCount)
    }

    @Test
    fun realityColdStartUsesLongerPrepareTimeout() = runTest {
        // Regression for the merge-time fix: a single 5 s budget covering
        // both prepare + probe would have aborted libXray cold-init (~30 s
        // on first launch), making MTS Tecno users unable to reach the
        // relay. The split timeout (REALITY_PREPARE_TIMEOUT_MS = 30 s,
        // PROBE_TIMEOUT_MS = 5 s) lets a slow init succeed.
        val slowXray = object : XrayService {
            private val flow = MutableStateFlow<XrayState>(XrayState.Off)
            override val state: StateFlow<XrayState> = flow.asStateFlow()
            override suspend fun start() {
                // Simulate ~10 s cold start. PROBE_TIMEOUT_MS (5 s) alone
                // would have failed; REALITY_PREPARE_TIMEOUT_MS (30 s) lets
                // the cold start finish.
                delay(10_000)
                flow.value = XrayState.Ready(socksPort = 10808)
            }
            override suspend fun stop() { flow.value = XrayState.Off }
        }
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { slowXray },
            preferences = InMemoryTransportPreferences(PrivacyMode.Private),
            probe = TransportProbe { kind, _ -> kind == TransportKind.Reality },
            nowMs = { 0L },
        )
        val connected = mgr.connect()
        assertEquals(TransportKind.Reality, connected.kind)
        assertEquals(10808, connected.socksPort)
    }

    @Test
    fun stateFlowReachesConnectedOnSuccess() = runTest {
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = InMemoryTransportPreferences(PrivacyMode.Standard),
            probe = TransportProbe { _, _ -> true },
            nowMs = { 0L },
        )
        mgr.connect()
        val state = mgr.state.value
        assertTrue(state is ManagerState.Connected, "expected Connected, got $state")
        assertEquals(TransportKind.Direct, (state as ManagerState.Connected).kind)
    }

    // ── Fakes ─────────────────────────────────────────────────────────────────

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
