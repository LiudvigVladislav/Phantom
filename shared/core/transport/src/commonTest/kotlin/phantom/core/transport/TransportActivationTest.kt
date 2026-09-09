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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R-N1.16 P1 / R-N1.17 - the service-to-socket boundary, driven through
 * the same components production uses.
 *
 * Review's objection to an earlier round was exact: a fixture that
 * exercises `TransportUsePermit` with an arbitrary lambda, plus a source
 * tripwire saying the service calls something, proves the boundary in
 * text only. [TransportActivation] is that boundary as a component and
 * [PrivacyModeCoordinator] is the authority it registers with, so these
 * fixtures and production run the same code.
 *
 * R-N1.17 folded `TransportUsePermitTest` in here. Five of its six
 * fixtures asserted the same properties against the permit directly,
 * with a lambda in place of the production path; keeping both would have
 * meant two fixtures per property, one of which could pass while the
 * real boundary was broken. The sixth (a mode round-trip) had no
 * production-path equivalent and is
 * `aResultIsRefusedAfterAModeRoundTripToo` below.
 */
class TransportActivationTest {

    /** Stands in for the WSS. */
    private class Socket {
        var opens = 0
            private set
        var closes = 0
            private set

        fun open() { opens += 1 }
        fun close() { closes += 1 }
        val isLive: Boolean get() = opens > closes
    }

    private fun coordinator(prefs: InMemoryTransportPreferences) =
        PrivacyModeCoordinator(
            initialMode = prefs.privacyMode,
            persistRequested = { mode -> prefs.privacyMode = mode },
        )

    private fun manager(
        prefs: TransportPreferences,
        policy: PrivacyModeCoordinator,
        directOnly: Boolean = true,
    ) = TransportManager(
        torServiceProvider = { fakeTor() },
        xrayServiceProvider = { fakeXray() },
        preferences = prefs,
        probe = TransportProbe { kind, _ -> !directOnly || kind == TransportKind.Direct },
        nowMs = { 0L },
        policy = policy,
    )

    private fun activation(mgr: TransportManager, socket: Socket) =
        TransportActivation(
            manager = mgr,
            openSocket = { socket.open() },
            closeSocket = { socket.close() },
        )

    @Test
    fun aSwitchLandingAfterThePermitButBeforeTheSocketRefusesTheOpen() = runTest {
        // The sequence review specified, through the production path:
        //   1. the permit is granted for a Direct result;
        //   2. execution parks immediately before the socket opens;
        //   3. Standard -> Ghost completes;
        //   4. the parked path resumes;
        //   5. no Direct socket is opened.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy)
        val socket = Socket()
        val act = activation(mgr, socket)
        // Park BETWEEN the permit and the open. Parking inside the open
        // would hold the permit's lock, the revocation would queue behind
        // it and the open would win - a different branch, covered by
        // anAlreadyOpenSocketIsStillFoundAndTornDownByALaterSwitch.
        val atSeam = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        act.onBeforeOpenSeam = {
            atSeam.complete(Unit)
            resume.await()
        }

        val connected = mgr.connect()
        assertEquals(TransportKind.Direct, connected.kind)

        var outcome: TransportActivation.Outcome? = null
        launch { outcome = act.activate(connected) }
        runCurrent()
        assertTrue(atSeam.isCompleted, "precondition: permission in hand, socket not open")
        assertFalse(socket.isLive)

        policy.requestMode(PrivacyMode.Ghost)

        resume.complete(Unit)
        runCurrent()

        assertIs<TransportActivation.Outcome.RevokedBeforeOpen>(
            outcome,
            "a permit granted under Standard must not survive the switch to Ghost",
        )
        assertEquals(0, socket.opens, "no Direct socket may be opened")
        assertFalse(socket.isLive)
    }

    @Test
    fun anAlreadyOpenSocketIsStillFoundAndTornDownByALaterSwitch() = runTest {
        // The permit must stay REGISTERED after a successful open - until
        // the socket closes - or a switch arriving afterwards finds
        // nothing to revoke and leaves an open Direct WSS behind it.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy)
        val socket = Socket()
        val act = activation(mgr, socket)

        val opened = act.activate(mgr.connect())
        assertIs<TransportActivation.Outcome.Opened>(opened)
        assertTrue(socket.isLive, "precondition: a Direct socket is up")

        policy.requestMode(PrivacyMode.Ghost)

        assertEquals(
            1,
            socket.closes,
            "the switch must find the open socket and close it - a permit dropped on " +
                "a successful open would make this socket invisible",
        )
        assertFalse(socket.isLive)
        assertTrue(opened.permit.isRevoked())
    }

    @Test
    fun theSwitchDoesNotCompleteUntilTheSocketIsActuallyClosed() = runTest {
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy)
        val order = mutableListOf<String>()
        val act = TransportActivation(
            manager = mgr,
            openSocket = { order += "open" },
            closeSocket = { order += "close" },
        )
        assertIs<TransportActivation.Outcome.Opened>(act.activate(mgr.connect()))

        policy.requestMode(PrivacyMode.Ghost)
        order += "switch_returned"

        assertEquals(
            listOf("open", "close", "switch_returned"),
            order,
            "the teardown must precede the switch returning",
        )
    }

    @Test
    fun aReleasedPermitIsNoLongerRevokedByLaterSwitches() = runTest {
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy)
        val socket = Socket()
        val act = activation(mgr, socket)

        val opened = act.activate(mgr.connect())
        assertIs<TransportActivation.Outcome.Opened>(opened)
        // R-N1.17 P1: an owner that opened a socket hands the permit back
        // by CLOSING it through the permit. `release()` alone used to
        // write `closed = true` on the owner's word, and that word is
        // exactly what cannot be checked from there.
        assertTrue(
            opened.permit.closeAndRelease("ordinary_end"),
            "a socket that really closes releases its permit",
        )
        assertEquals(1, socket.closes, "and it closed once, through the permit")

        policy.requestMode(PrivacyMode.Ghost)

        assertEquals(1, socket.closes, "a released permit has nothing left to tear down")
    }

    @Test
    fun aStaleResultIsRefusedWithoutOpeningAnything() = runTest {
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy)
        val socket = Socket()
        val connected = mgr.connect()

        policy.requestMode(PrivacyMode.Ghost)

        assertIs<TransportActivation.Outcome.RefusedStale>(
            activation(mgr, socket).activate(connected),
        )
        assertEquals(0, socket.opens)
    }

    @Test
    fun aResultIsRefusedAfterAModeRoundTripToo() = runTest {
        // Migrated from TransportUsePermitTest, now through the
        // production path. Standard -> Ghost -> Standard leaves the mode
        // reading the same and the epoch two ahead. The result must still
        // be refused: two teardowns have run and the subsystems behind it
        // are gone. A bare mode comparison would have let this through.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy)
        val socket = Socket()
        val connected = mgr.connect()

        policy.requestMode(PrivacyMode.Ghost)
        policy.requestMode(PrivacyMode.Standard)

        assertIs<TransportActivation.Outcome.RefusedStale>(
            activation(mgr, socket).activate(connected),
        )
        assertEquals(0, socket.opens)
    }

    @Test
    fun anUndisturbedActivationOpensAndStaysOpen() = runTest {
        // The control for every fixture above.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy)
        val socket = Socket()

        val outcome = activation(mgr, socket).activate(mgr.connect())

        assertIs<TransportActivation.Outcome.Opened>(outcome)
        assertTrue(socket.isLive)
        assertEquals(0, socket.closes)
        assertFalse(outcome.permit.isRevoked())
    }

    // -- a failed teardown is not a completed switch --------------------

    @Test
    fun aWedgedTeardownReportsIncompleteAndKeepsTheSocketTracked() = runTest {
        // Bounding the wait stops the switch hanging; it must NOT turn
        // the failure into a success. A switch that reported complete
        // over a live Direct socket is the same violation by the failure
        // path instead of the happy one.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy, directOnly = false)
        val socket = Socket()
        val stuck = CompletableDeferred<Unit>()
        val act = TransportActivation(
            manager = mgr,
            openSocket = { socket.open() },
            closeSocket = { stuck.await(); socket.close() },
        )
        assertIs<TransportActivation.Outcome.Opened>(act.activate(mgr.connect()))
        assertTrue(socket.isLive)

        policy.requestMode(PrivacyMode.Ghost)

        val snapshot = policy.state.value
        assertEquals(
            PrivacyModeStatus.Blocked,
            snapshot.status,
            "a switch that could not close the old socket is NOT applied",
        )
        assertEquals(
            PrivacyMode.Standard,
            snapshot.effective,
            "and the effective mode stays where it was",
        )
        assertEquals(1, snapshot.stillOpen, "the socket stays tracked")
        assertTrue(socket.isLive, "it really is still up")

        // And nothing new may be opened beside it - even a perfectly
        // valid Ghost result, because the refusal is about the unclosed
        // socket rather than about this walk.
        assertIs<TransportActivation.Outcome.RefusedStale>(act.activate(mgr.connect()))
        assertEquals(1, socket.opens, "no second socket while the first is unclosed")
        stuck.complete(Unit)
    }

    @Test
    fun oneThrowingTeardownStillLeavesTheSwitchIncomplete() = runTest {
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy)
        val good = Socket()
        val connected = mgr.connect()

        val exploding = policy.acquirePermit(connected.kind, connected.policyEpoch) {
            error("teardown blew up")
        }
        assertNotNull(exploding)
        exploding.useToOpen { }
        val healthy = policy.acquirePermit(connected.kind, connected.policyEpoch) {
            good.close()
        }
        assertNotNull(healthy)
        healthy.useToOpen { good.open() }

        policy.requestMode(PrivacyMode.Ghost)

        assertEquals(1, good.closes, "the healthy socket is closed regardless")
        assertEquals(
            PrivacyModeStatus.Blocked,
            policy.state.value.status,
            "but the switch is not a success",
        )
        assertEquals(1, policy.state.value.stillOpen, "and the failed one stays tracked")
    }

    @Test
    fun onceTheWedgeClearsARetryClosesItAndThenExactlyOneWalkIsAllowed() = runTest {
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy, directOnly = false)
        val socket = Socket()
        var wedged = true
        val act = TransportActivation(
            manager = mgr,
            openSocket = { socket.open() },
            closeSocket = { if (wedged) error("still wedged") else socket.close() },
        )
        assertIs<TransportActivation.Outcome.Opened>(act.activate(mgr.connect()))

        val epoch = policy.requestMode(PrivacyMode.Ghost)
        assertEquals(PrivacyModeStatus.Blocked, policy.state.value.status)
        assertTrue(socket.isLive)

        // R-N1.17: the handover is attempted whatever the sockets did, so
        // the walk blocker is settled here. A new epoch inherits no
        // confirmation, and without this the socket retry alone could not
        // finish the switch - which is the point of keeping the two
        // blockers apart.
        policy.complete(epoch = epoch, policyChangeComplete = false, walkQuiesced = true)

        // The wedge clears; the retry closes the old socket AND folds the
        // result into the one snapshot the UI observes.
        wedged = false
        val retry = policy.retryPending()

        // R-N1.17: the retry CLOSES sockets; it does not complete the
        // switch. Publishing Applied here used to make the deferred
        // settlement - which still owed a release and a successor -
        // conclude there was nothing left to do. Completion has one
        // owner now, and this stands in for it.
        policy.complete(epoch = epoch, policyChangeComplete = true, walkQuiesced = true)

        assertTrue(retry.isComplete, "the retry closes what the switch could not")
        assertFalse(socket.isLive)
        assertEquals(1, socket.closes)
        val after = policy.state.value
        assertEquals(
            PrivacyModeStatus.Applied,
            after.status,
            "and the switch becomes Applied without any further user action",
        )
        assertEquals(PrivacyMode.Ghost, after.effective)
        assertEquals(0, after.stillOpen)

        // Only now may exactly one new walk open a socket.
        assertIs<TransportActivation.Outcome.Opened>(act.activate(mgr.connect()))
        assertEquals(2, socket.opens, "exactly one new socket, after the old one closed")
    }

    @Test
    fun aTeardownThatThrowsCancellationIsAFailedCloseNotOurCancellation() = runTest {
        // A teardown lambda that raises CancellationException is somebody
        // else's problem, not the caller going away. Rethrowing it would
        // let a badly written teardown masquerade as our cancellation.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy)
        val socket = Socket()
        val act = TransportActivation(
            manager = mgr,
            openSocket = { socket.open() },
            closeSocket = { throw kotlinx.coroutines.CancellationException("teardown quirk") },
        )
        assertIs<TransportActivation.Outcome.Opened>(act.activate(mgr.connect()))

        policy.requestMode(PrivacyMode.Ghost)

        assertEquals(PrivacyModeStatus.Blocked, policy.state.value.status)
        assertEquals(1, policy.state.value.stillOpen, "the socket stays tracked")
        assertTrue(socket.isLive, "it really is still up")
    }

    @Test
    fun aCancelledRevocationStillClosesTheOtherSocketsBeforeItPropagates() = runTest {
        // "Catch the cancellation separately and rethrow" usually means
        // leaving the loop at once - and then the permits behind the
        // first one keep their sockets open. Here the CALLER is cancelled
        // while parked in the first teardown.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy, directOnly = false)
        val second = Socket()
        val connected = mgr.connect()
        val parked = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()

        val stuck = policy.acquirePermit(connected.kind, connected.policyEpoch) {
            parked.complete(Unit)
            neverCompletes.await()
        }
        assertNotNull(stuck)
        stuck.useToOpen { }

        val healthy = policy.acquirePermit(connected.kind, connected.policyEpoch) {
            second.close()
        }
        assertNotNull(healthy)
        healthy.useToOpen { second.open() }
        assertTrue(second.isLive, "precondition: the second socket is up")

        var thrown: Throwable? = null
        val caller = launch {
            try {
                policy.requestMode(PrivacyMode.Ghost)
            } catch (t: Throwable) {
                thrown = t
            }
        }
        runCurrent()
        assertTrue(parked.isCompleted, "precondition: parked inside the first teardown")

        caller.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(
            1,
            second.closes,
            "the socket behind the cancelled one must still be closed, under the " +
                "non-cancellable sweep",
        )
        assertFalse(second.isLive)
        assertTrue(stuck.needsClose(), "and the unconfirmed one stays open - and tracked")
        assertTrue(
            thrown is kotlinx.coroutines.CancellationException,
            "and the caller's cancellation reaches it only after the sweep: $thrown",
        )
    }

    @Test
    fun oneFailingTeardownDoesNotStopTheOthersFromBeingRevoked() = runTest {
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy)
        val good = Socket()
        val connected = mgr.connect()

        val exploding = policy.acquirePermit(connected.kind, connected.policyEpoch) {
            error("teardown blew up")
        }
        assertNotNull(exploding)
        exploding.useToOpen { }
        val healthy = policy.acquirePermit(connected.kind, connected.policyEpoch) {
            good.close()
        }
        assertNotNull(healthy)
        healthy.useToOpen { good.open() }

        policy.requestMode(PrivacyMode.Ghost)

        assertEquals(
            1,
            good.closes,
            "a throwing teardown must not skip the sockets behind it in the list",
        )
        assertFalse(good.isLive)
    }

    @Test
    fun aWedgedTeardownCannotHangThePrivacySwitchForEver() = runTest {
        // Bounded, because the switch waits on purpose. Without a budget
        // a single stuck teardown would hang every privacy switch.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy)
        val connected = mgr.connect()
        val wedged = policy.acquirePermit(connected.kind, connected.policyEpoch) {
            kotlinx.coroutines.delay(Long.MAX_VALUE / 4)
        }
        assertNotNull(wedged)
        wedged.useToOpen { }

        policy.requestMode(PrivacyMode.Ghost)

        // Reaching here at all is the assertion: an unbounded await would
        // never have returned.
        assertEquals(PrivacyMode.Ghost, policy.state.value.requested)
    }

    @Test
    fun permitsTheDeadlineNeverReachedAreStillTrackedAndRegistered() = runTest {
        // The overall deadline can expire with permits still unattempted;
        // those must land in stillOpen and stay in the register, or
        // nothing could ever close them and the next switch would not
        // know they existed.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, policy, directOnly = false)
        val connected = mgr.connect()
        val untouched = Socket()

        repeat(4) {
            val wedged = policy.acquirePermit(connected.kind, connected.policyEpoch) {
                kotlinx.coroutines.delay(Long.MAX_VALUE / 4)
            }
            assertNotNull(wedged)
            wedged.useToOpen { }
        }
        val last = policy.acquirePermit(connected.kind, connected.policyEpoch) {
            untouched.close()
        }
        assertNotNull(last)
        last.useToOpen { untouched.open() }

        policy.requestMode(PrivacyMode.Ghost)

        assertEquals(PrivacyModeStatus.Blocked, policy.state.value.status)
        assertTrue(
            policy.state.value.stillOpen >= 4,
            "every permit the sweep could not confirm is reported: " +
                "${policy.state.value.stillOpen}",
        )
        assertTrue(policy.hasUnclosed(), "and the register still holds them")
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
