// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import phantom.core.xray.XrayService
import phantom.core.xray.XrayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R-N1.16 review item 5 - two kinds of `CancellationException` meet in
 * `TransportManager`, and they must be treated as opposites.
 *
 * A `TimeoutCancellationException` from an internal `withTimeout` is a
 * DOMAIN outcome: this transport did not come up in its budget, so the
 * chain advances. An external cancellation is a STOP: the walk leaves
 * without touching the failure ladder.
 *
 * The hazard is that the first is a subclass of the second, so the catch
 * order is load-bearing and a single fixture asserting "cancellation
 * propagates" would silently accept a version that also aborted the
 * chain on every ordinary transport timeout. These are deliberately
 * separate fixtures for that reason.
 */
class CancellationVersusBudgetTest {

    private fun manager(
        prefs: TransportPreferences,
        probe: TransportProbe,
        xray: () -> XrayService = { readyXray() },
    ) = TransportManager(
        torServiceProvider = { readyTor() },
        xrayServiceProvider = xray,
        preferences = prefs,
        probe = probe,
        nowMs = { 0L },
        policy = PrivacyModeCoordinator(prefs.privacyMode),
    )

    // -- the domain case: a budget elapsing advances the chain ---------

    @Test
    fun aProbeBudgetElapsingAdvancesTheChainInsteadOfStoppingTheWalk() = runTest {
        // Direct's probe never answers. Its per-kind budget fires, that
        // is an ordinary failure, and the walk moves on to Reality.
        val attempted = mutableListOf<TransportKind>()
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val probe = TransportProbe { kind, _ ->
            attempted += kind
            if (kind == TransportKind.Direct) {
                delay(Long.MAX_VALUE / 4)
                true
            } else {
                true
            }
        }

        val connected = manager(prefs, probe).connect()

        assertEquals(
            TransportKind.Reality,
            connected.kind,
            "a probe timeout must NOT be mistaken for a stop signal -- it is the " +
                "ordinary way a transport fails",
        )
        assertEquals(listOf(TransportKind.Direct, TransportKind.Reality), attempted)
    }

    @Test
    fun aPrepareBudgetElapsingAdvancesTheChainInsteadOfStoppingTheWalk() = runTest {
        // Reality's subsystem never reaches Ready, so its prepare budget
        // fires and is rethrown out of prepareTransport. That timeout
        // reaches the chain loop as a TimeoutCancellationException and
        // must be read as "Reality failed", not "stop walking".
        val attempted = mutableListOf<TransportKind>()
        val prefs = InMemoryTransportPreferences(PrivacyMode.Private)
        val probe = TransportProbe { kind, _ ->
            attempted += kind
            true
        }

        val connected = manager(prefs, probe, xray = { stuckXray() }).connect()

        assertEquals(
            TransportKind.Tor,
            connected.kind,
            "the chain must advance past a subsystem that never came up",
        )
        assertEquals(
            listOf(TransportKind.Tor),
            attempted,
            "and Reality never reached the probe at all, because prepare failed",
        )
    }

    // -- the stop case: an external cancellation leaves ----------------

    @Test
    fun anExternalCancellationLeavesTheChainAndTouchesNoFailureState() = runTest {
        // The same exception hierarchy, the opposite meaning. This is
        // the ownership handover cancelling a displaced walk.
        val attempted = mutableListOf<TransportKind>()
        val parked = CompletableDeferred<Unit>()
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val probe = TransportProbe { kind, _ ->
            attempted += kind
            parked.complete(Unit)
            delay(Long.MAX_VALUE / 4)
            true
        }
        val mgr = manager(prefs, probe)

        val job = launch { runCatching { mgr.connect() } }
        runCurrent()
        assertTrue(parked.isCompleted, "precondition: the walk is inside the probe")

        job.cancel(CancellationException("ownership_handover"))
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals(
            listOf(TransportKind.Direct),
            attempted,
            "a cancelled walk does not go on to try Reality and Tor",
        )
        assertEquals(
            0,
            prefs.transportFailureCount,
            "and it records no failure: it never observed one",
        )
        assertFalse(mgr.state.value is ManagerState.AllFailed)
    }

    @Test
    fun theTwoAreDistinguishedWithinASingleWalk() = runTest {
        // The discriminating case. Direct times out (domain: advance),
        // and only then is the walk cancelled while probing Reality. If
        // the catch order were wrong, the first event would already have
        // aborted the walk and Reality would never be reached.
        val attempted = mutableListOf<TransportKind>()
        val atReality = CompletableDeferred<Unit>()
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val probe = TransportProbe { kind, _ ->
            attempted += kind
            when (kind) {
                TransportKind.Direct -> {
                    delay(Long.MAX_VALUE / 4)
                    true
                }
                else -> {
                    atReality.complete(Unit)
                    delay(Long.MAX_VALUE / 4)
                    true
                }
            }
        }
        val mgr = manager(prefs, probe)

        val job = launch { runCatching { mgr.connect() } }
        while (!atReality.isCompleted) {
            runCurrent()
            advanceTimeByOneProbeBudget()
        }

        assertEquals(
            listOf(TransportKind.Direct, TransportKind.Reality),
            attempted,
            "the budget was a domain failure and the chain advanced",
        )

        job.cancel(CancellationException("ownership_handover"))
        runCurrent()

        assertTrue(job.isCompleted, "and the cancellation stopped it there")
        assertEquals(
            listOf(TransportKind.Direct, TransportKind.Reality),
            attempted,
            "Tor is never attempted, because a stop is not a failure",
        )
        assertEquals(0, prefs.transportFailureCount)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.advanceTimeByOneProbeBudget() {
        testScheduler.advanceTimeBy(30_000L)
    }

    private fun readyTor(): TorService = object : TorService {
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

    private fun readyXray(): XrayService = object : XrayService {
        private val flow = MutableStateFlow<XrayState>(XrayState.Ready(socksPort = 10808))
        override val state: StateFlow<XrayState> = flow.asStateFlow()
        override suspend fun start() { /* already Ready */ }
        override suspend fun stop() { flow.value = XrayState.Off }
    }

    /** Never leaves Starting, so the Reality prepare budget must fire. */
    private fun stuckXray(): XrayService = object : XrayService {
        private val flow = MutableStateFlow<XrayState>(XrayState.Starting)
        override val state: StateFlow<XrayState> = flow.asStateFlow()
        override suspend fun start() { /* stays Starting for ever */ }
        override suspend fun stop() { /* nothing to stop */ }
    }
}
