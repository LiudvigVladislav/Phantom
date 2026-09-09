// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import phantom.core.xray.XrayService
import phantom.core.xray.XrayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R-N1.16 P1-1 - handing over the lease must hand over the EXECUTION.
 *
 * The ownership unit tests prove the state machine in isolation. These
 * drive the real [TransportManager] through it, because the defect was
 * never in either piece alone: it was that revoking a token said nothing
 * about the coroutine still walking the chain.
 *
 * Everything here is deterministic. The walk parks inside the probe on a
 * gate rather than on a timer, so no assertion depends on scheduling.
 */
class ConnectHandoverQuiescenceTest {

    /** Counts how many walks are inside the probe at the same time. */
    private class ConcurrencyMeter {
        var live = 0
            private set
        var max = 0
            private set

        fun enter() {
            live += 1
            if (live > max) max = live
        }

        fun exit() {
            live -= 1
        }
    }

    private fun manager(
        prefs: TransportPreferences,
        probe: TransportProbe,
    ) = TransportManager(
        torServiceProvider = { fakeTor() },
        xrayServiceProvider = { fakeXray() },
        preferences = prefs,
        probe = probe,
        nowMs = { 0L },
        policy = PrivacyModeCoordinator(prefs.privacyMode),
    )

    /**
     * Start a walk that owns [token] and registers itself for handover,
     * exactly the way the service does.
     */
    /**
     * A walk handle shaped exactly like the service's.
     *
     * R-N1.16 P1-1: the lease and the handle are taken together, so
     * there is no window in which a lease is held by a walk ownership
     * cannot see. The Job is filled in once the coroutine starts.
     */
    private class WalkHandle : ConnectWalkHandle {
        @Volatile
        var job: Job? = null

        override suspend fun cancelAndJoin(timeoutMs: Long): Boolean {
            val j = job ?: return true
            j.cancel(CancellationException("ownership_handover"))
            return withTimeoutOrNull(timeoutMs) { j.join() } != null
        }
    }

    /**
     * Claim the lease and run a walk under it, the way the service does.
     *
     * Returns null when the claim was refused - and then starts nothing.
     * The previous version of this helper ignored the registration
     * result and entered `connect()` regardless, which production never
     * does; review R-N1.16 was right that a fixture which cannot refuse
     * proves less than it appears to.
     */
    private class OwnedWalk(val job: Job) {
        var token: Long? = null
        var result: Result<ConnectedTransport>? = null
        var enteredConnect = false
    }

    private fun kotlinx.coroutines.CoroutineScope.startOwnedWalk(
        ownership: ConnectOwnership,
        source: String,
        mgr: TransportManager,
    ): OwnedWalk {
        val handle = WalkHandle()
        lateinit var owned: OwnedWalk
        val job = launch {
            handle.job = currentCoroutineContext()[Job]
            val t = ownership.claim(source, handle)
            owned.token = t
            if (t == null) return@launch
            owned.enteredConnect = true
            owned.result = runCatching { mgr.connect() }
        }
        owned = OwnedWalk(job)
        return owned
    }

    @Test
    fun aSuccessorNeverEntersTheChainWhileTheDisplacedWalkIsStillInIt() = runTest {
        // The interleaving R-N1.16 rejected:
        //   A is inside connect() -> rewalk revokes A's token -> B claims
        //   -> A is still inside connect().
        // The old handover made every step of that possible. Here the
        // handover must cancel A, wait for it, and only then release.
        val meter = ConcurrencyMeter()
        val aParked = CompletableDeferred<Unit>()
        var calls = 0
        val probe = TransportProbe { _, _ ->
            val n = ++calls
            meter.enter()
            try {
                if (n == 1) {
                    aParked.complete(Unit)
                    awaitCancellation()
                }
                true
            } finally {
                meter.exit()
            }
        }
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val mgr = manager(prefs, probe)
        var next = 0L
        val ownership = ConnectOwnership(nextToken = { ++next })

        val a = startOwnedWalk(ownership, "generation_A", mgr)
        runCurrent()
        val aToken = a.token
        assertNotNull(aToken)
        assertTrue(a.enteredConnect, "A claimed the lease and entered the chain")

        assertTrue(aParked.isCompleted, "precondition: A is parked inside the probe")
        assertEquals(1, meter.live, "and it is the only walk in there")
        assertNull(
            ownership.claim("generation_B_too_early") { true },
            "B must not be able to claim while A holds the lease",
        )

        var outcome: Handover? = null
        launch { outcome = ownership.handOver("network_rewalk") }
        runCurrent()

        assertEquals(
            Handover.Quiesced(aToken),
            outcome,
            "the handover must confirm A finished, not merely revoke its token",
        )
        assertTrue(a.job.isCompleted, "A is quiescent before the slot is published as free")
        assertEquals(0, meter.live, "and it has left the chain")

        val b = startOwnedWalk(ownership, "generation_B", mgr)
        runCurrent()
        assertNotNull(b.token)
        assertTrue(b.enteredConnect, "the successor is admitted only now")

        assertEquals(
            TransportKind.Direct,
            b.result?.getOrNull()?.kind,
            "and it then completes its own walk normally",
        )
        assertEquals(
            1,
            meter.max,
            "maxConcurrentConnectCalls must be 1 for the whole scenario -- two " +
                "walks over one set of subsystems is the defect this round closes",
        )
    }

    @Test
    fun aCancelledWalkLeavesTheChainInsteadOfMarchingOnToTheNextTransport() = runTest {
        // The cancellation half of P1-1. TransportManager caught
        // Throwable around prepare, probe and Tor rotation, so a
        // cancelled walk did not stop: it recorded a failure and tried
        // the next transport. A cancel-and-join handover would then wait
        // for a walk that had quietly decided to keep going.
        //
        // The observable is deliberately a side effect rather than a
        // log: reaching the end of the chain calls onAllFailed(), which
        // bumps the persisted failure counter and publishes AllFailed. A
        // cancelled walk must do neither.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val attempted = mutableListOf<TransportKind>()
        val parked = CompletableDeferred<Unit>()
        val probe = TransportProbe { kind, _ ->
            attempted += kind
            parked.complete(Unit)
            awaitCancellation()
        }
        val mgr = manager(prefs, probe)

        val job = launch { runCatching { mgr.connect() } }
        runCurrent()
        assertTrue(parked.isCompleted, "precondition: the walk is inside the probe")

        job.cancel(CancellationException("ownership_handover"))
        runCurrent()

        assertTrue(job.isCompleted, "the walk stops")
        assertEquals(
            listOf(TransportKind.Direct),
            attempted,
            "and it does NOT advance to Reality and Tor after being cancelled",
        )
        assertEquals(
            0,
            prefs.transportFailureCount,
            "a cancelled walk is not a failed chain: recording AllFailed here " +
                "would corrupt the backoff ladder with a failure nobody observed",
        )
        assertFalse(
            mgr.state.value is ManagerState.AllFailed,
            "nor may it publish AllFailed, which is the retry trigger",
        )
    }

    @Test
    fun aHandoverThatCannotStopTheWalkRefusesToStartASuccessor() = runTest {
        // Fail-closed, end to end. The walk here ignores cancellation
        // the way a wedged native teardown would, so the join cannot
        // confirm quiescence. Availability yields: no successor starts.
        val meter = ConcurrencyMeter()
        var calls = 0
        val probe = TransportProbe { _, _ ->
            calls += 1
            meter.enter()
            try {
                awaitCancellation()
            } finally {
                meter.exit()
            }
        }
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val mgr = manager(prefs, probe)
        var next = 0L
        val log = mutableListOf<String>()
        val ownership = ConnectOwnership(
            nextToken = { ++next },
            handoverTimeoutMs = 1_000L,
            log = { log += it },
        )

        // A walk that will not stop: the handle reports failure without
        // ever confirming the join.
        val aToken = ownership.claim("generation_A") { false }
        assertNotNull(aToken)
        launch { runCatching { mgr.connect() } }
        runCurrent()

        val outcome = ownership.handOver("network_rewalk")

        assertTrue(outcome is Handover.TimedOut, "the handover cannot confirm quiescence")
        assertEquals(aToken, ownership.currentOwner(), "so the lease stays held")
        assertNull(
            ownership.claim("successor") { true },
            "and no successor may start a walk",
        )
        assertEquals(1, calls, "the chain was never entered a second time")
        assertEquals(1, meter.max)
        assertTrue(
            log.any { it.startsWith("OWNERSHIP handoff_timeout") },
            "and the refusal is a structured event, not silence: $log",
        )
    }

    @Test
    fun ourOwnTeardownCannotBeTheReasonAHandoverTimesOut() = runTest {
        // R-N1.16 review item 6. Stopping Tor during teardown has to be
        // NonCancellable, or a cancelled walk leaves the wrapper running
        // -- a privacy leak. But an UNBOUNDED NonCancellable block is
        // worse in the other direction: nothing can interrupt it, so a
        // wedged native stop() would hang the walk for ever, the join
        // would time out, and the lease would go fail-closed because of
        // our own cleanup rather than anything the transport did.
        //
        // Here stop() never returns. The walk must still finish.
        val lines = mutableListOf<String>()
        val log = object : TransportManagerLog {
            override fun info(msg: String) { lines += msg }
            override fun warn(msg: String) { lines += msg }
        }
        val prefs = InMemoryTransportPreferences(PrivacyMode.Ghost)
        val mgr = TransportManager(
            torServiceProvider = { wedgedTor() },
            xrayServiceProvider = { fakeXray() },
            preferences = prefs,
            probe = TransportProbe { _, _ -> true },
            nowMs = { 0L },
            log = log,
            policy = PrivacyModeCoordinator(prefs.privacyMode),
        )

        val job = launch { runCatching { mgr.connect() } }
        runCurrent()
        job.cancel(CancellationException("ownership_handover"))
        testScheduler.advanceUntilIdle()

        assertTrue(
            job.isCompleted,
            "a teardown that cannot be cancelled must still be bounded, or the walk " +
                "never becomes joinable",
        )
        assertTrue(
            lines.any { it.startsWith("PROBE_TRACE tor_stop_timeout") },
            "and giving up on the stop must be recorded, because the wrapper may " +
                "still be running: $lines",
        )
    }

    /** Never becomes Ready and never finishes stopping. */
    private fun wedgedTor(): TorService = object : TorService {
        private val flow = MutableStateFlow<TorState>(TorState.Bootstrapping(percent = 10))
        override val state: StateFlow<TorState> = flow.asStateFlow()
        override suspend fun start(bridgeProfile: BridgeProfile) { /* never Ready */ }
        override suspend fun stop(budget: TorBudget): TorStopResponse {
            kotlinx.coroutines.delay(Long.MAX_VALUE / 4)
            return freeStopResponse()
        }

        override suspend fun awaitRelease(
            attempt: TorStopAttempt,
            budget: TorBudget,
        ): TorStopResult = freeStopResponse().result
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
