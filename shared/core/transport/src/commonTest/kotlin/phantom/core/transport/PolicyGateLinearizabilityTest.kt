// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R-N1.16 P1 / R-N1.17 - the publication gate has to be linearizable.
 *
 * A gate that re-reads the mode and then publishes is check-then-act
 * across a thread boundary: a request landing between the two publishes
 * a forbidden transport anyway. "There is no suspension point between
 * them" says nothing about another thread.
 *
 * So validate-and-publish is one critical section inside
 * [PrivacyModeCoordinator], the same section [PrivacyModeCoordinator.
 * requestMode] takes to bump the epoch.
 *
 * ## What changed in R-N1.17
 *
 * Three fixtures here used to call `TransportManager.isStillCurrent()` -
 * a read-check performed before opening a socket. That method is gone,
 * and deliberately not replaced: asking "is this still valid" and then
 * acting on the answer is the shape this round removed. They now assert
 * the same safety property through the PERMIT, which registers the
 * intent with the authority instead of asking it a question.
 */
class PolicyGateLinearizabilityTest {

    private fun coordinator(prefs: InMemoryTransportPreferences) =
        PrivacyModeCoordinator(
            initialMode = prefs.privacyMode,
            persistRequested = { mode -> prefs.privacyMode = mode },
        )

    private fun TestScope.manager(prefs: TransportPreferences, policy: PrivacyModeCoordinator) =
        TransportManager(
            torServiceProvider = { fakeTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = prefs,
            probe = TransportProbe { kind, _ -> kind == TransportKind.Direct },
            nowMs = { 0L },
            policy = policy,
            // The teardown attempts must share this test's clock: they are
            // issued into a scope of their own so a bounded wait cannot cancel
            // them, and a scope on a real dispatcher would make every bounded
            // wait here expire against virtual time.
            teardownWorkers = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )

    // -- half one: the publication loses -------------------------------

    @Test
    fun aSwitchThatCompletesBeforePublicationMakesThePublicationLose() = runTest {
        // The interleaving review named: Direct is already judged
        // reachable under Standard, the mode then becomes Ghost, and the
        // walk goes on to publish. It must not.
        //
        // The pause sits after the probe succeeds and before the
        // validate-and-publish section, so the request completes ENTIRELY
        // - including the epoch bump, which takes the same lock the
        // section takes - while the walk is held.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy)
        val atSeam = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        mgr.onPolicyPublishSeam = {
            atSeam.complete(Unit)
            release.await()
        }

        var result: Result<ConnectedTransport>? = null
        launch { result = runCatching { mgr.connect() } }
        runCurrent()
        assertTrue(atSeam.isCompleted, "precondition: Direct was judged reachable")
        assertFalse(mgr.state.value is ManagerState.Connected, "and nothing is published yet")

        policy.requestMode(PrivacyMode.Ghost)

        release.complete(Unit)
        runCurrent()

        assertTrue(
            result?.exceptionOrNull() is TransportPolicyChangedException,
            "the publication must lose to the switch; got ${result?.exceptionOrNull()}",
        )
        assertNull(result?.getOrNull(), "and hand back nothing")
        assertFalse(
            mgr.state.value is ManagerState.Connected,
            "nor publish Connected: state=${mgr.state.value}",
        )
        assertNull(prefs.lastWorkingTransport, "nor record a hint pointing at Direct")
    }

    @Test
    fun anEpochBumpAloneIsEnoughToLoseEvenIfTheModeLooksUnchanged() = runTest {
        // The discriminator against a bare mode comparison. The mode
        // changes and changes back, so a re-read sees Standard and would
        // publish; the epoch has moved twice and says otherwise.
        //
        // Not hypothetical tidiness: Standard -> Ghost -> Standard is two
        // teardowns, and a walk that started before the first has had its
        // subsystems released underneath it.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy)
        val atSeam = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        mgr.onPolicyPublishSeam = {
            atSeam.complete(Unit)
            release.await()
        }

        var result: Result<ConnectedTransport>? = null
        launch { result = runCatching { mgr.connect() } }
        runCurrent()
        assertTrue(atSeam.isCompleted)

        policy.requestMode(PrivacyMode.Ghost)
        policy.requestMode(PrivacyMode.Standard)

        release.complete(Unit)
        runCurrent()

        assertTrue(
            result?.exceptionOrNull() is TransportPolicyChangedException,
            "a walk that spans two switches is stale even though the mode it reads " +
                "matches the one it started under; got ${result?.exceptionOrNull()}",
        )
    }

    @Test
    fun aWalkThatSpansNoSwitchPublishesNormally() = runTest {
        // The control for both fixtures above.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy)
        val atSeam = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        mgr.onPolicyPublishSeam = {
            atSeam.complete(Unit)
            release.await()
        }

        var result: Result<ConnectedTransport>? = null
        launch { result = runCatching { mgr.connect() } }
        runCurrent()
        assertTrue(atSeam.isCompleted)
        release.complete(Unit)
        runCurrent()

        assertEquals(TransportKind.Direct, result?.getOrNull()?.kind)
        assertEquals(TransportKind.Direct, prefs.lastWorkingTransport)
        assertTrue(mgr.state.value is ManagerState.Connected)
    }

    // -- half two: the socket is refused a PERMIT ----------------------

    @Test
    fun aResultValidatedUnderAnOlderEpochIsRefusedAPermit() = runTest {
        // R-N1.17: was `isStillCurrent(connected)`, a read-check before
        // the socket. Now the authority refuses to REGISTER the socket at
        // all, which is a decision rather than an answer somebody has to
        // act on in time.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy)

        val connected = mgr.connect()
        assertEquals(TransportKind.Direct, connected.kind)
        val first = policy.acquirePermit(connected.kind, connected.policyEpoch) { }
        assertTrue(first != null, "fresh out of connect() a permit is granted")
        first?.release()

        policy.requestMode(PrivacyMode.Ghost)

        assertNull(
            policy.acquirePermit(connected.kind, connected.policyEpoch) { },
            "after the switch the result gets no permit - opening a Direct socket " +
                "here is the silent downgrade with extra steps",
        )
    }

    @Test
    fun aResultIsRefusedAPermitAfterAModeRoundTripToo() = runTest {
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy)
        val connected = mgr.connect()

        policy.requestMode(PrivacyMode.Ghost)
        policy.requestMode(PrivacyMode.Standard)

        assertNull(
            policy.acquirePermit(connected.kind, connected.policyEpoch) { },
            "the mode reads Standard again, but two teardowns have run since this " +
                "result was validated",
        )
    }

    @Test
    fun aHandBuiltResultIsNeverGrantedAPermit() = runTest {
        // The default epoch is -1 so an instance nobody validated is
        // inert rather than silently trusted.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)

        val handBuilt = ConnectedTransport(TransportKind.Direct, socksPort = null)
        assertNull(
            policy.acquirePermit(handBuilt.kind, handBuilt.policyEpoch) { },
            "a ConnectedTransport that never went through the gate must not be usable",
        )
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
