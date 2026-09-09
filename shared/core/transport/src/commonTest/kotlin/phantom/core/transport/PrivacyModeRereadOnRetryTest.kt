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
 * N1-F3 - the privacy invariant that survives a retry.
 *
 * F-3 gives the service a retry cadence after `AllFailed`. That cadence
 * would be a privacy regression rather than a fix if a retry reused the
 * chain the FAILED attempt was built from: a user who switches to Ghost
 * while the app is stuck would get a Direct or Reality probe on the next
 * attempt, which is exactly the silent downgrade Ghost exists to prevent.
 *
 * The re-read is structural - `TransportManager.connect()` reads
 * `preferences.privacyMode` on entry, and the service's retry re-enters
 * through `onStartCommand` rather than reusing anything - but structural
 * is not the same as tested. These fixtures walk the chain twice with a
 * mode change in between and assert on the kinds actually probed.
 *
 * `MUT-F3-CACHE-STRATEGY` introduces the cache these fixtures exist to
 * forbid. It has to be introduced, because there is no cache in the
 * product to switch off - which is the point, and is why asserting the
 * absence needs a mutation that adds one.
 */
class PrivacyModeRereadOnRetryTest {

    private fun fakeTor(): TorService = object : TorService {
        private val flow = MutableStateFlow<TorState>(TorState.Ready(socksPort = 9050))
        override val state: StateFlow<TorState> = flow.asStateFlow()
        override suspend fun start(bridgeProfile: BridgeProfile) { /* already Ready */ }
        override suspend fun stop(budget: TorBudget): TorStopResponse {
            flow.value = TorState.Off
            return freeStopResponse()
        }

        override suspend fun awaitRelease(
            attempt: TorStopAttempt,
            budget: TorBudget,
        ): TorStopResult = freeStopResponse().result
    }

    private fun fakeXray(): XrayService = object : XrayService {
        private val flow = MutableStateFlow<XrayState>(XrayState.Ready(socksPort = 10808))
        override val state: StateFlow<XrayState> = flow.asStateFlow()
        override suspend fun start() { /* already Ready */ }
        override suspend fun stop() { flow.value = XrayState.Off }
    }

    /** The kinds a walk actually prepared, in order, from the trace. */
    private fun kindsPrepared(traces: List<String>): List<String> =
        traces.filter { it.startsWith("prepare_start kind=") }
            .map { it.removePrefix("prepare_start kind=").substringBefore(' ') }

    @Test
    fun aRetryAfterSwitchingToGhostWalksTorOnly() = runTest {
        val log = CapturingTransportManagerLog()
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = PrivacyModeCoordinator(
            initialMode = prefs.privacyMode,
            persistRequested = { mode -> prefs.privacyMode = mode },
        )
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = prefs,
            probe = TransportProbe { _, _ -> false },   // every kind fails
            nowMs = { 0L },
            log = log,
            policy = policy,
        )

        // First walk: Standard. It exhausts the chain and lands in
        // AllFailed -- the state this whole round exists to recover from.
        assertFailsWith<NoTransportReachableException> { mgr.connect() }
        val first = kindsPrepared(log.probeTraces())
        assertTrue(
            "Direct" in first,
            "precondition: a Standard walk tries Direct; got $first",
        )
        assertTrue(mgr.state.value is ManagerState.AllFailed, "precondition: AllFailed")

        // The user switches to Ghost while the app is stuck, BEFORE the
        // retry fires. This is the window the retry cadence creates and
        // the one a cached strategy would get wrong.
        val before = log.probeTraces().size
        policy.requestMode(PrivacyMode.Ghost)

        assertFailsWith<NoTransportReachableException> { mgr.connect() }
        val second = kindsPrepared(log.probeTraces().drop(before))

        assertEquals(
            listOf("Tor"),
            second,
            "the retry must walk the CURRENT mode's chain. Ghost is TOR_FIRST, a " +
                "single-element chain by design; anything else here is a silent " +
                "downgrade of the mode the user just chose. Got: $second",
        )
        assertTrue(
            "Direct" !in second,
            "a retry must never probe Direct in Ghost mode",
        )
        assertTrue(
            "Reality" !in second,
            "nor Reality -- Ghost is Tor-only",
        )
    }

    @Test
    fun aRetryAfterSwitchingToPrivateDropsDirect() = runTest {
        // The mirror case: Private is Reality-then-Tor, so Direct must
        // disappear from the retry even though the failed walk used it.
        val log = CapturingTransportManagerLog()
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = PrivacyModeCoordinator(
            initialMode = prefs.privacyMode,
            persistRequested = { mode -> prefs.privacyMode = mode },
        )
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = prefs,
            probe = TransportProbe { _, _ -> false },
            nowMs = { 0L },
            log = log,
            policy = policy,
        )

        assertFailsWith<NoTransportReachableException> { mgr.connect() }
        val before = log.probeTraces().size
        policy.requestMode(PrivacyMode.Private)

        assertFailsWith<NoTransportReachableException> { mgr.connect() }
        val second = kindsPrepared(log.probeTraces().drop(before))

        assertTrue(
            "Direct" !in second,
            "Private must not fall back to Direct on a retry; got $second",
        )
        assertTrue("Tor" in second, "and Tor must still be reachable in the chain; got $second")
    }

    @Test
    fun aRetryInTheSameModeWalksTheSameChain() = runTest {
        // The control. Without it, a mutation that simply broke the
        // second walk would satisfy the two fixtures above for the wrong
        // reason -- they would pass because nothing was probed at all.
        val log = CapturingTransportManagerLog()
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = PrivacyModeCoordinator(
            initialMode = prefs.privacyMode,
            persistRequested = { mode -> prefs.privacyMode = mode },
        )
        val mgr = TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = prefs,
            probe = TransportProbe { _, _ -> false },
            nowMs = { 0L },
            log = log,
            policy = policy,
        )

        assertFailsWith<NoTransportReachableException> { mgr.connect() }
        val first = kindsPrepared(log.probeTraces())
        val before = log.probeTraces().size

        assertFailsWith<NoTransportReachableException> { mgr.connect() }
        val second = kindsPrepared(log.probeTraces().drop(before))

        assertEquals(
            first,
            second,
            "an unchanged mode must walk an unchanged chain, and the second walk " +
                "must actually happen",
        )
        assertTrue(second.isNotEmpty(), "the second walk probed nothing at all")
    }
}
