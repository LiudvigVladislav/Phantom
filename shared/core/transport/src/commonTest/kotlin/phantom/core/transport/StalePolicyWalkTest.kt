// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R-N1.16 P1 - a walk must not publish under a policy that has since
 * been superseded.
 *
 * `TransportManager.connect()` reads the privacy mode once, at the top
 * of the walk. Everything after that - which chain, which transport,
 * which socks port - is decided under that reading. A privacy switch
 * landing mid-walk does not change it.
 *
 * Fail-closed ownership does not cover this. It refuses a SUCCESSOR and
 * says nothing about the displaced walk's own side effects; on a
 * handover timeout the displaced walk is precisely the one still
 * running. Neither does "recovery uses the new mode": that constrains
 * the NEW walk.
 *
 * The window is small and real. Between `withTimeout { probe.reachable
 * (...) }` returning and the publication below it there is no suspension
 * point: `onSuccess`, `_state.value` and `return` are ordinary calls. A
 * `reachable` that does not itself suspend returns with no cancellation
 * check, and the walk publishes a Direct transport under a policy that
 * now says Tor only.
 *
 * The gate closes it by re-reading the policy at the moment of
 * publication and discarding a result the live policy forbids.
 */
class StalePolicyWalkTest {

    private fun coordinator(prefs: InMemoryTransportPreferences) =
        PrivacyModeCoordinator(
            initialMode = prefs.privacyMode,
            persistRequested = { mode -> prefs.privacyMode = mode },
        )

    private fun manager(
        prefs: TransportPreferences,
        probe: TransportProbe,
        policy: PrivacyModeCoordinator,
    ) = TransportManager(
        torServiceProvider = { fakeTor() },
        xrayServiceProvider = { fakeXray() },
        preferences = prefs,
        probe = probe,
        nowMs = { 0L },
        policy = policy,
    )

    @Test
    fun aWalkThatFinishesUnderASupersededPolicyIsDiscardedNotPublished() = runTest {
        // The property that matters, stated as what the gate guarantees.
        //
        // What this does NOT model - and the distinction is the point:
        // an earlier version of this fixture tried to defeat cancellation
        // with a NonCancellable probe. That failed, and correctly so:
        // leaving a NonCancellable block re-raises the cancellation, so
        // the walk dies before it ever reaches publication.
        //
        // The residual hazard the gate exists for is narrower. Between
        // `withTimeout { probe.reachable(...) }` returning and the
        // publication below it there is NO suspension point -
        // `onSuccess`, `_state.value` and `return` are all ordinary
        // calls. A `reachable` implementation that does not itself
        // suspend (a blocking call in a suspend fun, no `withContext`)
        // therefore returns with no cancellation check at all, and the
        // walk proceeds straight to publishing a transport chosen under
        // a policy that is no longer in force.
        //
        // So this fixture drives the gate on the policy dimension, which
        // is its actual contract, rather than pretending cancellation was
        // beaten.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val reachedProbe = CompletableDeferred<Unit>()
        val letItFinish = CompletableDeferred<Unit>()
        val attempted = mutableListOf<TransportKind>()
        val probe = TransportProbe { kind, _ ->
            attempted += kind
            reachedProbe.complete(Unit)
            letItFinish.await()
            true
        }
        val policy = coordinator(prefs)
        val mgr = manager(prefs, probe, policy)

        var result: Result<ConnectedTransport>? = null
        launch { result = runCatching { mgr.connect() } }
        runCurrent()
        assertTrue(reachedProbe.isCompleted, "precondition: the walk is inside the Direct probe")
        assertEquals(listOf(TransportKind.Direct), attempted)

        // The user switches to Ghost while the walk is still in flight.
        policy.requestMode(PrivacyMode.Ghost)
        letItFinish.complete(Unit)
        runCurrent()

        val outcome = result
        assertNotNull(outcome, "the walk ran to a conclusion")
        assertTrue(
            outcome.exceptionOrNull() is TransportPolicyChangedException,
            "a walk that finished under a superseded policy must be discarded, not " +
                "published; got ${outcome.exceptionOrNull()}",
        )
        assertNull(
            outcome.getOrNull(),
            "and it must not hand back a ConnectedTransport anyone could open a " +
                "socket over",
        )
        assertFalse(
            mgr.state.value is ManagerState.Connected,
            "nor may it publish Connected: state=${mgr.state.value}",
        )
        assertNull(
            prefs.lastWorkingTransport,
            "nor record Direct as the last working transport, which would steer the " +
                "next Ghost walk toward it",
        )
    }

    @Test
    fun aWalkThatFinishesUnderTheSamePolicyPublishesNormally() = runTest {
        // The control. Without it, a gate that refused everything would
        // satisfy the fixture above for the wrong reason.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val connected =
            manager(prefs, TransportProbe { kind, _ -> kind == TransportKind.Direct }, coordinator(prefs))
                .connect()

        assertEquals(TransportKind.Direct, connected.kind)
        assertEquals(TransportKind.Direct, prefs.lastWorkingTransport)
    }

    @Test
    fun aWideningSwitchAlsoDiscardsAnInFlightWalk() = runTest {
        // R-N1.17 replaced an earlier fixture here that asserted the
        // opposite: Ghost -> Standard was said to leave a Tor walk valid,
        // because Tor is in both chains.
        //
        // That reasoning looked only at chain membership and ignored what
        // a switch actually does. Every switch is a real teardown - the
        // permits are revoked and the subsystems released - so a walk
        // that started before it has had the ground taken out from under
        // it whether or not its transport is still nominally allowed.
        //
        // The epoch makes that the rule rather than a judgement call, and
        // it errs in the safe direction: at worst one extra reconnect.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Ghost)
        val policy = coordinator(prefs)
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val probe = TransportProbe { _, _ ->
            reached.complete(Unit)
            release.await()
            true
        }
        val mgr = manager(prefs, probe, policy)

        var result: Result<ConnectedTransport>? = null
        launch { result = runCatching { mgr.connect() } }
        runCurrent()
        assertTrue(reached.isCompleted)

        policy.requestMode(PrivacyMode.Standard)
        release.complete(Unit)
        runCurrent()

        assertTrue(
            result?.exceptionOrNull() is TransportPolicyChangedException,
            "a walk that spans a switch is discarded even when its transport is " +
                "still permitted, because the switch tore its subsystems down; " +
                "got ${result?.exceptionOrNull()}",
        )
        assertNull(prefs.lastWorkingTransport, "and it records no hint")
    }

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
}
