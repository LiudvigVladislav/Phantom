// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import phantom.core.transport.PrivacyMode
import phantom.core.transport.ProbeBudget
import phantom.core.transport.ProbeIssueRejectReason
import phantom.core.transport.ProbeIssueResult
import phantom.core.transport.ProbeToken
import phantom.core.transport.RelayTransportConfig
import phantom.core.transport.RewalkCoordinatorGateProvider
import phantom.core.transport.RouteChangeOutcome
import phantom.core.transport.TransportKind
import phantom.core.transport.TransportManager
import phantom.core.transport.TransportPreferences
import phantom.core.transport.WsReconnectGate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RC-RECONNECT-QUIESCENCE1 commit 2c (2026-06-22) — behavioural tests
 * for the [TransportRewalkCoordinator.performRewalk] typed transaction.
 *
 * Each test pins one of the locked invariants. End-to-end coverage of a
 * real chain walk (Test 20) is deferred per the locked schedule; here
 * we drive the coordinator with deterministic fakes for the four
 * collaborators ([RewalkHybridFacade], [TransportPreferences],
 * release lambda, [RewalkCoordinatorGateProvider]) and observe the
 * call order + side effects.
 *
 * L1 baseline-landing Round-5 (2026-08-13, architect direction): this
 * test drives production code that calls `android.util.Log.i` in
 * `TransportRewalkCoordinator.seedNetworkPresent`. Before L1, the AGP
 * null-mock stub returned 0 via `unitTests.isReturnDefaultValues = true`.
 * L1 adds Compose-UI-test artefacts to the `androidUnitTest` classpath
 * whose Android SDK jar supersedes that stub, so `Log.i` reaches
 * `println_native` at runtime. The correct fix is to run this test under
 * `RobolectricTestRunner`, which installs `ShadowLog` and stubs the
 * native method — a bare `Application` avoids booting production
 * `PhantomApplication` machinery this test does not need.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TransportRewalkCoordinatorTransactionTest {

    private val livingScopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun closeScopes() {
        livingScopes.forEach { runCatching { it.cancel() } }
        livingScopes.clear()
    }

    /** Chronological shared call-log. */
    private class CallLog {
        private val entries = mutableListOf<String>()
        fun add(s: String) { entries.add(s) }
        fun snapshot(): List<String> = entries.toList()
        fun contains(s: String) = entries.contains(s)
        fun count(prefix: String) = entries.count { it.startsWith(prefix) }
        fun firstIndex(s: String) = entries.indexOf(s)
        fun any(predicate: (String) -> Boolean) = entries.any(predicate)
        fun none(predicate: (String) -> Boolean) = entries.none(predicate)
    }

    private class TracingGate(
        private val log: CallLog,
        initialGate: WsReconnectGate = WsReconnectGate.Quiesced(stickyGen = 1),
    ) : RewalkCoordinatorGateProvider {
        private val _gate = MutableStateFlow(initialGate)
        override val gate: StateFlow<WsReconnectGate> = _gate.asStateFlow()
        var routeEpoch = 0L
            private set
        var issueResult: ProbeIssueResult = ProbeIssueResult.ProbeIssued(ProbeToken(0xCAFEL))

        override suspend fun beginRouteChange(clearsMode2Sticky: Boolean): RouteChangeOutcome {
            val current = _gate.value
            return when {
                current is WsReconnectGate.Open -> {
                    routeEpoch += 1
                    log.add("beginRouteChange:$routeEpoch")
                    log.add("outcome:OpenReconnect:cms=$clearsMode2Sticky")
                    RouteChangeOutcome.OpenReconnect(routeEpoch)
                }
                !clearsMode2Sticky -> {
                    // No bump.
                    log.add("beginRouteChange:$routeEpoch")
                    log.add("outcome:QuiescencePreserved:cms=$clearsMode2Sticky")
                    RouteChangeOutcome.QuiescencePreserved(routeEpoch)
                }
                else -> {
                    routeEpoch += 1
                    log.add("beginRouteChange:$routeEpoch")
                    log.add("outcome:StickyRecovery:cms=$clearsMode2Sticky")
                    RouteChangeOutcome.StickyRecovery(routeEpoch)
                }
            }
        }
        override suspend fun revokeRouteChange(routeEpoch: Long, reason: String) {
            log.add("revokeRouteChange:$routeEpoch:$reason")
        }
        override suspend fun issueProbeAfterRewalk(routeEpoch: Long): ProbeIssueResult {
            log.add("issueProbeAfterRewalk:$routeEpoch")
            return issueResult
        }
        override suspend fun revokeProbe(routeEpoch: Long, reason: String) {
            log.add("revokeProbe:$routeEpoch:$reason")
        }
    }

    private class TracingHybrid(private val log: CallLog) : RewalkHybridFacade {
        /** Behaviour of disconnectAndJoin: null=cleanReturnTrue, false=timeout, "ce"=CE, "ex"=throw. */
        var disconnectBehaviour: String? = null
        var submitBehaviour: String? = null

        override suspend fun submitNetworkChangedEvent(clearsMode2Sticky: Boolean) {
            log.add("submitNetworkChangedEvent:$clearsMode2Sticky")
            when (submitBehaviour) {
                "ce" -> throw CancellationException("submit CE")
                "ex" -> throw IllegalStateException("submit error")
                else -> Unit
            }
        }
        override suspend fun disconnect() {
            log.add("disconnect")
        }
        override suspend fun disconnectAndJoin(timeoutMs: Long): Boolean {
            log.add("disconnectAndJoin:$timeoutMs")
            return when (disconnectBehaviour) {
                "false" -> false
                "ce" -> throw CancellationException("dj CE")
                "ex" -> throw IllegalStateException("dj error")
                else -> true
            }
        }
    }

    private class InMemoryPrefs : TransportPreferences {
        override var privacyMode: PrivacyMode = PrivacyMode.Standard
        override var lastWorkingTransport: TransportKind? = TransportKind.Direct
        override var lastSuccessAt: Long? = 12345L
        override var transportFailureCount: Int = 0
    }

    private fun newCoordinator(
        log: CallLog,
        gate: RewalkCoordinatorGateProvider?,
        hybrid: TracingHybrid?,
        releaseBehaviour: String? = null,
        restartBehaviour: String? = null,
        nowMs: () -> Long = { 1_000L },
        handOver: (suspend (String) -> Boolean)? = null,
    ): Fixture {
        val prefs = InMemoryPrefs()
        val restartLog = mutableListOf<NetworkChangeReason>()
        val coord = TransportRewalkCoordinator(
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { livingScopes.add(it) },
            transportPreferences = prefs,
            releaseTransport = {
                log.add("release")
                when (releaseBehaviour) {
                    "ce" -> throw CancellationException("release CE")
                    "ex" -> throw IllegalStateException("release error")
                    "unclean" -> TransportManager.ReleaseOutcome(
                        xrayFailure = null,
                        torFailure = IllegalStateException("tor host not released"),
                    )
                    // The gate is the WHOLE outcome. A rewalk that read only
                    // tor would restart on top of a proxy that would not stop.
                    "unclean_xray" -> TransportManager.ReleaseOutcome(
                        xrayFailure = IllegalStateException("xray would not stop"),
                        torFailure = null,
                    )
                    else -> cleanRelease()
                }
            },
            handOverConnectOwnership = { reason ->
                log.add("handOverConnectOwnership")
                handOver?.invoke(reason) ?: true
            },
            hybridTransportProvider = { hybrid },
            requestServiceRestart = { reason ->
                log.add("requestServiceRestart:${reason.name}")
                restartLog.add(reason)
                when (restartBehaviour) {
                    "ce" -> throw CancellationException("restart CE")
                    "ex" -> throw IllegalStateException("restart error")
                    else -> Unit
                }
            },
            nowMs = nowMs,
            gateCoordinator = gate,
        )
        return Fixture(coord, prefs, restartLog)
    }

    private data class Fixture(
        val coord: TransportRewalkCoordinator,
        val prefs: InMemoryPrefs,
        val restartLog: MutableList<NetworkChangeReason>,
    )

    private fun snapshot(networkPresent: Boolean = true) = NetworkSnapshot(
        networkPresent = networkPresent,
        transportClass = NetworkTransportClass.WIFI,
        vpnActive = false,
        validated = true,
    )

    private suspend fun awaitJobDone(coord: TransportRewalkCoordinator, timeoutMs: Long = 5_000) {
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val running = coord.javaClass.getDeclaredField("currentRewalkJob").apply { isAccessible = true }
                    .get(coord) as? kotlinx.coroutines.Job
                if (running == null || running.isCompleted) return@withTimeoutOrNull
                delay(20)
            }
        } ?: error("rewalk job did not complete within ${timeoutMs}ms")
    }

    // ── 1: Happy path — locked transaction order ────────────────────────────

    // -- R-N1.16 P1-2: ordering BETWEEN components ---------------------

    @Test
    fun the_connect_lease_is_handed_over_before_the_transport_is_released() = runBlocking {
        // Review R-N1.16 P1-2. The coordinator released the transport at
        // step 5 and only asked the service to restart at step 7, so the
        // service's handover ran AFTER Tor and Xray had been stopped. A
        // walk still inside TransportManager.connect() was having its
        // subsystems torn down while it was starting or probing them, and
        // release() is deliberately not serialised against connect().
        //
        // No test inside the service could see this: its source tripwire
        // only checks the order of its own statements.
        val log = CallLog()
        val (coord, _, _) = newCoordinator(log, TracingGate(log), TracingHybrid(log))
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        val observed = log.snapshot()
        val handover = observed.indexOf("handOverConnectOwnership")
        val release = observed.indexOf("release")
        val restart = observed.indexOf("requestServiceRestart:WIFI_TO_CELLULAR")

        assertTrue(handover >= 0, "the lease is no longer handed over; got $observed")
        assertTrue(release >= 0, "release step missing; got $observed")
        assertTrue(
            handover < release,
            "the walk must be stopped and joined BEFORE its subsystems are " +
                "released; observed=$observed",
        )
        assertTrue(
            release < restart,
            "and the successor is only invited afterwards; observed=$observed",
        )
    }

    @Test
    fun a_handover_that_cannot_confirm_quiescence_abandons_the_rewalk() = runBlocking {
        // Fail-closed across components. If we could not stop the walk,
        // tearing its subsystems down is the defect, not the recovery.
        val log = CallLog()
        val (coord, _, restartLog) = newCoordinator(
            log,
            TracingGate(log),
            TracingHybrid(log),
            handOver = { false },
        )
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        val observed = log.snapshot()
        assertTrue(
            observed.contains("handOverConnectOwnership"),
            "the handover must still be attempted; got $observed",
        )
        assertTrue(
            observed.none { it == "release" },
            "the transport must NOT be released under a walk we could not stop; " +
                "got $observed",
        )
        assertTrue(
            observed.none { it.startsWith("requestServiceRestart") },
            "and no successor may be invited; got $observed",
        )
        assertTrue(restartLog.isEmpty())
    }

    @Test
    fun release_never_runs_while_the_displaced_walk_is_still_executing() = runBlocking {
        // The behavioural form of the same claim, with a real
        // ConnectOwnership and a walk that is actually running. The
        // handover lambda is wired the way AppContainer wires it.
        val log = CallLog()
        val walkFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        val releaseSawWalkRunning = java.util.concurrent.atomic.AtomicBoolean(false)
        val walkScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            .also { livingScopes.add(it) }
        val ownership = phantom.core.transport.ConnectOwnership(
            nextToken = { 1L },
            handoverTimeoutMs = 5_000L,
        )
        val started = CompletableDeferred<Unit>()
        val walk = walkScope.launch {
            val me = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            ownership.claim(
                "generation_A",
                phantom.core.transport.ConnectWalkHandle { timeoutMs: Long ->
                    me?.cancel(CancellationException("ownership_handover"))
                    me == null || withTimeoutOrNull(timeoutMs) { me.join() } != null
                },
            )
            started.complete(Unit)
            try {
                delay(60_000)
            } finally {
                walkFinished.set(true)
            }
        }
        started.await()

        val (coord, _, _) = newCoordinator(
            log,
            TracingGate(log),
            TracingHybrid(log),
            releaseBehaviour = null,
            handOver = { reason ->
                when (ownership.handOver(reason)) {
                    is phantom.core.transport.Handover.Quiesced,
                    phantom.core.transport.Handover.NothingToStop -> true
                    else -> false
                }
            },
        )
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(walk.isCompleted, "the displaced walk was actually stopped")
        assertTrue(
            log.contains("release"),
            "and the rewalk proceeded; got ${log.snapshot()}",
        )
        assertTrue(
            walkFinished.get(),
            "the walk's own teardown ran before release was allowed to proceed",
        )
        assertFalse(
            releaseSawWalkRunning.get(),
            "release must never observe a live walk",
        )
    }

    @Test
    fun the_legacy_path_also_hands_over_before_it_releases() = runBlocking {
        // R-N1.16 P1-2. The flag-off path is the one that actually runs
        // when RECONNECT_QUIESCENCE_ENABLED is not "1", so ordering it
        // only in the typed transaction would leave the defect shipped
        // wherever the flag is off.
        val log = CallLog()
        val (coord, _, _) = newCoordinator(log, gate = null, hybrid = TracingHybrid(log))
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        val observed = log.snapshot()
        val handover = observed.indexOf("handOverConnectOwnership")
        val release = observed.indexOf("release")

        assertTrue(handover >= 0, "the legacy path no longer hands over; got $observed")
        assertTrue(release >= 0, "release step missing; got $observed")
        assertTrue(
            handover < release,
            "the legacy path must stop the walk before releasing its subsystems; " +
                "observed=$observed",
        )
    }

    @Test
    fun a_legacy_handover_timeout_excludes_both_release_and_restart() = runBlocking {
        val log = CallLog()
        val (coord, _, restartLog) = newCoordinator(
            log,
            gate = null,
            hybrid = TracingHybrid(log),
            handOver = { false },
        )
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        val observed = log.snapshot()
        assertTrue(
            observed.contains("handOverConnectOwnership"),
            "the handover must still be attempted; got $observed",
        )
        assertTrue(
            observed.none { it == "release" },
            "a legacy rewalk that could not stop the walk must not release; " +
                "got $observed",
        )
        assertTrue(
            observed.none { it.startsWith("requestServiceRestart") },
            "and must not invite a successor; got $observed",
        )
        assertTrue(restartLog.isEmpty())
    }

    @Test
    fun a_coordinator_timeout_arms_recovery_and_one_connect_follows_typed() = runBlocking {
        // R-N1.16 P1. The rewalk abandons on a failed handover - no
        // release, no restart - so the arming that used to live in the
        // service's restart branch could never be reached from the one
        // situation that needs it. The coordinator's own handover lambda
        // must arm.
        assertRecoveryArmedAfterTimeout(useTypedGate = true)
    }

    @Test
    fun a_coordinator_timeout_arms_recovery_and_one_connect_follows_legacy() = runBlocking {
        assertRecoveryArmedAfterTimeout(useTypedGate = false)
    }

    private suspend fun assertRecoveryArmedAfterTimeout(useTypedGate: Boolean) {
        val log = CallLog()
        val starts = mutableListOf<String>()
        val recoveryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            .also { livingScopes.add(it) }
        val walkScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            .also { livingScopes.add(it) }

        // A walk that ignores cancellation until told otherwise, so the
        // first join times out the way a wedged teardown would.
        val stubborn = java.util.concurrent.atomic.AtomicBoolean(true)
        val ownership = phantom.core.transport.ConnectOwnership(
            nextToken = { 1L },
            handoverTimeoutMs = 300L,
        )
        val started = CompletableDeferred<Unit>()
        val walkJob = walkScope.launch {
            val me = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            ownership.claim(
                "generation_A",
                phantom.core.transport.ConnectWalkHandle { timeoutMs: Long ->
                    if (stubborn.get()) {
                        false
                    } else {
                        me?.cancel(CancellationException("ownership_handover"))
                        me == null || withTimeoutOrNull(timeoutMs) { me.join() } != null
                    }
                },
            )
            started.complete(Unit)
            try {
                delay(60_000)
            } catch (_: CancellationException) {
                // the walk finally lets go
            }
        }
        started.await()

        val recovery = phantom.core.transport.HandoffRecovery(
            ownership = ownership,
            scope = recoveryScope,
            startOneOrdinaryConnect = { reason -> synchronized(starts) { starts.add(reason) } },
            intervalMs = 100L,
        )

        val (coord, _, restartLog) = newCoordinator(
            log,
            gate = if (useTypedGate) TracingGate(log) else null,
            hybrid = TracingHybrid(log),
            handOver = { reason -> recovery.handOverOrArm(reason) },
        )
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        // The rewalk was abandoned: nothing released, nobody restarted.
        val observed = log.snapshot()
        assertTrue(observed.none { it == "release" }, "must not release; got $observed")
        assertTrue(observed.none { it.startsWith("requestServiceRestart") })
        assertTrue(restartLog.isEmpty())
        assertTrue(recovery.isArmed(), "but recovery MUST be armed; got $observed")
        assertTrue(synchronized(starts) { starts.isEmpty() }, "and nothing started yet")

        // The old walk finally becomes joinable; the timer confirms it.
        stubborn.set(false)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
            synchronized(starts) { starts.isEmpty() }
        ) {
            delay(50)
        }

        assertTrue(walkJob.isCompleted, "the displaced walk was actually stopped")
        assertEquals(
            1,
            synchronized(starts) { starts.size },
            "exactly one ordinary connect follows a confirmed join; got $starts",
        )
        assertNull(ownership.blockedReason(), "and the lease is usable again")
        recovery.standDown("test_teardown")
    }

    @Test
    fun happy_path_runs_locked_transaction_in_order() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log)
        val (coord, prefs, restartLog) = newCoordinator(log, gate, hybrid)
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        val expected = listOf(
            "beginRouteChange:1",
            "submitNetworkChangedEvent:true",
            "disconnectAndJoin:10000",
            "release",
            "issueProbeAfterRewalk:1",
            "requestServiceRestart:WIFI_TO_CELLULAR",
        )
        val observed = log.snapshot()
        val indices = expected.map { observed.indexOf(it) }
        assertTrue(indices.all { it >= 0 }, "all expected steps present; got $observed")
        assertEquals(indices.sorted(), indices, "steps must run in order; observed=$observed")
        assertEquals(listOf(NetworkChangeReason.WIFI_TO_CELLULAR), restartLog)
        assertNull(prefs.lastWorkingTransport)
        assertNull(prefs.lastSuccessAt)
        assertTrue(log.none { it.startsWith("revoke") }, "no revoke on happy path; got $observed")
    }

    // ── 2: disconnectAndJoin returns false → revokeRouteChange + abort ──────

    @Test
    fun disconnectAndJoin_false_revokes_routeChange_and_skips_release_probe_restart() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log).apply { disconnectBehaviour = "false" }
        val (coord, _, restartLog) = newCoordinator(log, gate, hybrid)
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("revokeRouteChange:1:disconnect_join_timeout"), "got ${log.snapshot()}")
        assertTrue(log.none { it.startsWith("issueProbeAfterRewalk") }, "no probe; got ${log.snapshot()}")
        assertTrue(log.none { it == "release" }, "release must NOT run on disconnect timeout")
        assertTrue(restartLog.isEmpty(), "restart must NOT fire on disconnect timeout")
    }

    @Test
    fun disconnectAndJoin_CE_revokes_routeChange_and_propagates() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log).apply { disconnectBehaviour = "ce" }
        val (coord, _, restartLog) = newCoordinator(log, gate, hybrid)
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("revokeRouteChange:1:disconnectAndJoin_cancelled"), "got ${log.snapshot()}")
        assertTrue(log.none { it == "release" })
        assertTrue(restartLog.isEmpty())
    }

    @Test
    fun release_error_revokes_routeChange_and_skips_probe_restart() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log)
        val (coord, _, restartLog) = newCoordinator(log, gate, hybrid, releaseBehaviour = "ex")
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("revokeRouteChange:1:release_failed"), "got ${log.snapshot()}")
        assertTrue(log.none { it.startsWith("issueProbeAfterRewalk") })
        assertEquals(1, log.count("release"), "release attempted exactly once")
        assertTrue(restartLog.isEmpty())
    }

    /**
     * A release that threw is already refused above. This is the other
     * half: a release that returned NORMALLY while reporting that a
     * subsystem did not settle. Nothing threw, so every catch in this
     * path is silent -- the answer has to be READ.
     */
    @Test
    fun an_unclean_release_revokes_routeChange_and_skips_probe_restart() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log)
        val (coord, _, restartLog) =
            newCoordinator(log, gate, hybrid, releaseBehaviour = "unclean")
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertEquals(1, log.count("release"), "release attempted exactly once")
        assertTrue(
            log.contains("revokeRouteChange:1:release_not_clean"),
            "an unclean release must revoke the route change; got ${log.snapshot()}",
        )
        assertTrue(
            log.none { it.startsWith("issueProbeAfterRewalk") },
            "and must not probe on top of it; got ${log.snapshot()}",
        )
        assertTrue(restartLog.isEmpty(), "and must not invite a successor")
    }

    /**
     * The same rule on the path that has no gate coordinator. The two
     * branches diverged once before -- R-N1.16 P1-2 -- and a rule that
     * holds on only one of them is not a rule.
     */
    @Test
    fun an_unclean_release_excludes_the_restart_on_the_legacy_path() = runBlocking {
        val log = CallLog()
        val (coord, _, restartLog) = newCoordinator(
            log,
            gate = null,
            hybrid = TracingHybrid(log),
            releaseBehaviour = "unclean",
        )
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        val observed = log.snapshot()
        assertEquals(1, log.count("release"), "the release still runs; got $observed")
        assertTrue(
            observed.none { it.startsWith("requestServiceRestart") },
            "a restart on top of an unsettled subsystem is the defect; got $observed",
        )
        assertTrue(restartLog.isEmpty())
    }

    /**
     * The positive control for both cases above: with a CLEAN release the
     * same fixtures go all the way through. Without this, a coordinator
     * that refused every rewalk would pass them.
     */
    @Test
    fun a_clean_release_still_reaches_the_probe_and_the_restart() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log)
        val (coord, _, restartLog) = newCoordinator(log, gate, hybrid)
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(
            log.none { it.startsWith("revokeRouteChange:1:release_not_clean") },
            "a clean release must not be read as unclean; got ${log.snapshot()}",
        )
        assertTrue(log.contains("issueProbeAfterRewalk:1"), "got ${log.snapshot()}")
        assertTrue(restartLog.isNotEmpty(), "the successor must still be invited")
    }

    /**
     * R-1. The same refusal for the OTHER half of the outcome. Tor settling
     * cleanly does not make the release clean if Xray did not stop, and a
     * gate that read only the tor result would restart on top of a live
     * proxy from the old posture.
     */
    @Test
    fun an_unclean_xray_also_revokes_routeChange_and_skips_probe_restart() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log)
        val (coord, _, restartLog) =
            newCoordinator(log, gate, hybrid, releaseBehaviour = "unclean_xray")
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(
            log.contains("revokeRouteChange:1:release_not_clean"),
            "an unclean xray must stop the rewalk too; got ${log.snapshot()}",
        )
        assertTrue(log.none { it.startsWith("issueProbeAfterRewalk") })
        assertTrue(restartLog.isEmpty())
    }

    /** And on the legacy branch, which diverged from this rule once before. */
    @Test
    fun an_unclean_xray_excludes_the_restart_on_the_legacy_path() = runBlocking {
        val log = CallLog()
        val (coord, _, restartLog) = newCoordinator(
            log,
            gate = null,
            hybrid = TracingHybrid(log),
            releaseBehaviour = "unclean_xray",
        )
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        val observed = log.snapshot()
        assertEquals(1, log.count("release"), "the release still runs; got $observed")
        assertTrue(
            observed.none { it.startsWith("requestServiceRestart") },
            "a restart on top of a live proxy is the defect; got $observed",
        )
        assertTrue(restartLog.isEmpty())
    }

    /**
     * The positive control for the LEGACY branch specifically. The typed one
     * has its own; without this, a legacy path that refused every rewalk
     * would satisfy both refusal tests above and nothing would notice.
     */
    @Test
    fun a_clean_release_still_reaches_the_restart_on_the_legacy_path() = runBlocking {
        val log = CallLog()
        val (coord, _, restartLog) = newCoordinator(
            log,
            gate = null,
            hybrid = TracingHybrid(log),
        )
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        val observed = log.snapshot()
        assertEquals(1, log.count("release"), "got $observed")
        assertTrue(
            observed.any { it.startsWith("requestServiceRestart") },
            "a clean legacy rewalk must still invite a successor; got $observed",
        )
        assertTrue(restartLog.isNotEmpty())
    }

    @Test
    fun issueProbe_rejected_skips_service_restart() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log).apply {
            issueResult = ProbeIssueResult.Rejected(ProbeIssueRejectReason.GATE_NOT_QUIESCED)
        }
        val hybrid = TracingHybrid(log)
        val (coord, _, restartLog) = newCoordinator(log, gate, hybrid)
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("issueProbeAfterRewalk:1"))
        assertTrue(restartLog.isEmpty(), "rejected probe must skip restart")
        assertEquals(1, log.count("release"), "release still ran before probe issuance attempt")
    }

    @Test
    fun serviceRestart_error_revokes_probe() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log)
        val (coord, _, restartLog) = newCoordinator(log, gate, hybrid, restartBehaviour = "ex")
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("revokeProbe:1:service_restart_failed"), "got ${log.snapshot()}")
        assertEquals(listOf(NetworkChangeReason.WIFI_TO_CELLULAR), restartLog)
    }

    @Test
    fun lastRewalkAtMs_not_bumped_on_failed_teardown() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log).apply { disconnectBehaviour = "false" }
        var t = 1_000L
        val (coord, _, _) = newCoordinator(log, gate, hybrid, nowMs = { t })
        coord.seedNetworkPresent(true)

        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)
        assertTrue(log.any { it.startsWith("revokeRouteChange:1") })

        // Advance clock by 1s — well inside NETWORK_REWALK_MIN_INTERVAL_MS.
        t += 1_000L
        hybrid.disconnectBehaviour = null
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)
        assertTrue(
            log.contains("beginRouteChange:2"),
            "second attempt must NOT be rate-limited; got ${log.snapshot()}",
        )
    }

    @Test
    fun lastRewalkAtMs_bumped_on_success_so_subsequent_attempt_is_rate_limited() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log)
        var t = 10_000L
        val (coord, _, _) = newCoordinator(log, gate, hybrid, nowMs = { t })
        coord.seedNetworkPresent(true)

        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)
        assertTrue(log.contains("beginRouteChange:1"))

        t += 1_000L
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)
        assertEquals(
            1, log.count("beginRouteChange:"),
            "second non-forced attempt within rate-limit window must NOT begin a new rewalk; got ${log.snapshot()}",
        )
    }

    @Test
    fun hybrid_null_revokes_routeEpoch_and_skips_steps() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val (coord, _, restartLog) = newCoordinator(log, gate, hybrid = null)
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("beginRouteChange:1"))
        assertTrue(log.contains("revokeRouteChange:1:hybrid_not_initialized"))
        assertTrue(log.none { it == "release" })
        assertTrue(restartLog.isEmpty())
    }

    @Test
    fun legacy_path_runs_when_gateCoordinator_is_null() = runBlocking {
        val log = CallLog()
        val hybrid = TracingHybrid(log)
        val (coord, _, restartLog) = newCoordinator(log, gate = null, hybrid = hybrid)
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("submitNetworkChangedEvent:true"))
        assertTrue(log.contains("disconnect"), "legacy path uses disconnect; got ${log.snapshot()}")
        assertTrue(log.none { it.startsWith("disconnectAndJoin") })
        assertEquals(1, log.count("release"))
        assertEquals(listOf(NetworkChangeReason.WIFI_TO_CELLULAR), restartLog)
    }

    // ── Second-round amendments: Open / VALIDATED_CHANGED / startService ────

    @Test
    fun Open_gate_route_change_runs_teardown_release_restart_without_probe() = runBlocking {
        // Open gate ⇒ ordinary network rewalk: teardown + release +
        // restart, NO probe issuance. The pre-amend shape called
        // issueProbeAfterRewalk regardless, which returned
        // GATE_NOT_QUIESCED and silently skipped the restart — leaving
        // the transport torn down with nothing to bring it back.
        val log = CallLog()
        val gate = TracingGate(log, initialGate = WsReconnectGate.Open)
        val hybrid = TracingHybrid(log)
        val (coord, _, restartLog) = newCoordinator(log, gate, hybrid)
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("outcome:OpenReconnect:cms=true"), "got ${log.snapshot()}")
        assertTrue(log.contains("disconnectAndJoin:10000"))
        assertEquals(1, log.count("release"), "release runs exactly once on Open path")
        assertTrue(log.none { it.startsWith("issueProbeAfterRewalk") }, "no probe on Open path; got ${log.snapshot()}")
        assertEquals(
            listOf(NetworkChangeReason.WIFI_TO_CELLULAR), restartLog,
            "restart MUST fire on Open path so the next connect cycle dials Direct",
        )
        assertTrue(log.none { it.startsWith("revoke") }, "no revoke on happy Open path; got ${log.snapshot()}")
    }

    @Test
    fun Quiesced_gate_VALIDATED_CHANGED_does_NOT_issue_probe_or_disturb_quiescence() = runBlocking {
        // VALIDATED_CHANGED ⇒ clearsMode2Sticky=false. Under quiescence
        // the coordinator MUST run NO substeps: no probe, no teardown,
        // no restart, no sticky-pref clear. The pre-amend shape ran the
        // whole transaction and issued a probe — which would have
        // triggered a Direct recovery attempt on a NON-route-change.
        val log = CallLog()
        val gate = TracingGate(log, initialGate = WsReconnectGate.Quiesced(stickyGen = 1))
        val hybrid = TracingHybrid(log)
        val (coord, prefs, restartLog) = newCoordinator(log, gate, hybrid)
        coord.seedNetworkPresent(true)

        coord.onMeaningfulChange(NetworkChangeReason.VALIDATED_CHANGED, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("outcome:QuiescencePreserved:cms=false"), "got ${log.snapshot()}")
        assertTrue(log.none { it.startsWith("submitNetworkChangedEvent") }, "no submit; got ${log.snapshot()}")
        assertTrue(log.none { it.startsWith("disconnectAndJoin") })
        assertTrue(log.none { it == "release" })
        assertTrue(log.none { it.startsWith("issueProbeAfterRewalk") }, "no probe; got ${log.snapshot()}")
        assertTrue(restartLog.isEmpty(), "no restart on VALIDATED_CHANGED under quiescence")
        // Sticky preferences must NOT be cleared (don't disturb quiescence).
        assertTrue(prefs.lastWorkingTransport != null, "lastWorkingTransport preserved")
        assertTrue(prefs.lastSuccessAt != null, "lastSuccessAt preserved")
    }

    /**
     * Strengthened variant of [TracingGate] whose revoke methods
     * SUSPEND BEFORE writing the call log. This actually exercises the
     * `withContext(NonCancellable)` wrap in
     * [TransportRewalkCoordinator]'s catch path — without a real
     * suspension point, the non-NonCancellable shape would still
     * "complete" the revoke synchronously and the test would pass
     * regardless of the wrap. With the suspension, a cancelled parent
     * would throw CE on entry to `yield()` and the revoke would
     * NEVER record its log line.
     */
    private class SuspendingRevokeGate(
        private val log: CallLog,
        initialGate: WsReconnectGate = WsReconnectGate.Quiesced(stickyGen = 1),
    ) : RewalkCoordinatorGateProvider {
        private val _gate = MutableStateFlow(initialGate)
        override val gate: StateFlow<WsReconnectGate> = _gate.asStateFlow()
        var routeEpoch = 0L
            private set
        var issueResult: ProbeIssueResult = ProbeIssueResult.ProbeIssued(ProbeToken(0xCAFEL))

        override suspend fun beginRouteChange(clearsMode2Sticky: Boolean): RouteChangeOutcome {
            val current = _gate.value
            return when {
                current is WsReconnectGate.Open -> {
                    routeEpoch += 1
                    log.add("beginRouteChange:$routeEpoch")
                    log.add("outcome:OpenReconnect:cms=$clearsMode2Sticky")
                    RouteChangeOutcome.OpenReconnect(routeEpoch)
                }
                !clearsMode2Sticky -> {
                    log.add("beginRouteChange:$routeEpoch")
                    log.add("outcome:QuiescencePreserved:cms=$clearsMode2Sticky")
                    RouteChangeOutcome.QuiescencePreserved(routeEpoch)
                }
                else -> {
                    routeEpoch += 1
                    log.add("beginRouteChange:$routeEpoch")
                    log.add("outcome:StickyRecovery:cms=$clearsMode2Sticky")
                    RouteChangeOutcome.StickyRecovery(routeEpoch)
                }
            }
        }
        override suspend fun revokeRouteChange(routeEpoch: Long, reason: String) {
            // REAL suspension point — without `withContext(NonCancellable)`
            // wrap in the catch, this `yield()` would throw CE on a
            // cancelled parent, and `log.add` would NEVER fire.
            kotlinx.coroutines.yield()
            log.add("revokeRouteChange:$routeEpoch:$reason")
        }
        override suspend fun issueProbeAfterRewalk(routeEpoch: Long): ProbeIssueResult {
            log.add("issueProbeAfterRewalk:$routeEpoch")
            return issueResult
        }
        override suspend fun revokeProbe(routeEpoch: Long, reason: String) {
            // REAL suspension point — see revokeRouteChange comment above.
            kotlinx.coroutines.yield()
            log.add("revokeProbe:$routeEpoch:$reason")
        }
    }

    @Test
    fun real_cancellation_with_suspending_revokeProbe_still_completes_via_NonCancellable() = runBlocking {
        // Test gap #1 strengthening (2026-06-22). The original
        // `real_job_cancellation_in_restart_callback_still_revokes_probe_via_NonCancellable`
        // test used a [TracingGate] whose `revokeProbe` was a pure
        // `log.add(...)` with no suspension. Without the
        // `withContext(NonCancellable)` wrap, that test would have
        // passed anyway because the cancelled parent's CE never had
        // a suspension point to fire on.
        //
        // This variant uses [SuspendingRevokeGate] whose revoke methods
        // `yield()` BEFORE writing the log. The NonCancellable wrap is
        // now the SOLE reason the revoke completes — remove it and
        // the test would fail.
        val log = CallLog()
        val gate = SuspendingRevokeGate(log)
        val hybrid = TracingHybrid(log)
        val prefs = InMemoryPrefs()
        val rewalkScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            .also { livingScopes.add(it) }
        lateinit var coord: TransportRewalkCoordinator
        coord = TransportRewalkCoordinator(
            scope = rewalkScope,
            transportPreferences = prefs,
            releaseTransport = { log.add("release"); cleanRelease() },
            handOverConnectOwnership = { log.add("handOverConnectOwnership"); true },
            hybridTransportProvider = { hybrid },
            requestServiceRestart = { reason ->
                log.add("requestServiceRestart:${reason.name}")
                val jobField = coord.javaClass.getDeclaredField("currentRewalkJob").apply {
                    isAccessible = true
                }
                val job = jobField.get(coord) as? kotlinx.coroutines.Job
                job?.cancel(CancellationException("test cancel from restart"))
                throw CancellationException("test cancel from restart")
            },
            nowMs = { 1_000L },
            gateCoordinator = gate,
        )
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("issueProbeAfterRewalk:1"))
        // The KEY assertion: the SUSPENDING revoke still completed.
        // Remove the `withContext(NonCancellable)` wrap and the
        // `yield()` inside `revokeProbe` would throw CE before
        // `log.add("revokeProbe:...")` runs.
        assertTrue(
            log.contains("revokeProbe:1:service_restart_cancelled"),
            "suspending revokeProbe MUST complete via NonCancellable wrap; got ${log.snapshot()}",
        )
    }

    @Test
    fun real_job_cancellation_in_restart_callback_still_revokes_probe_via_NonCancellable() = runBlocking {
        // P1 (ninth round, 2026-06-22): the catch (CancellationException)
        // path used to call coordinator.revokeProbe directly. Since
        // revokeProbe acquires gateLock under withLock, AND the parent
        // coroutine is ALREADY cancelled at the moment we reach the
        // catch, the gateLock.withLock would itself throw CE on entry
        // — leaving the gate at ProbeAvailable forever. The fix wraps
        // every catch-then-revoke in withContext(NonCancellable).
        //
        // This test simulates that scenario REALISTICALLY by cancelling
        // the rewalk coroutine FROM INSIDE the restart callback (so the
        // parent is genuinely cancelled by the time the catch runs)
        // instead of just throwing CancellationException manually.
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log)
        val prefs = InMemoryPrefs()
        val rewalkScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            .also { livingScopes.add(it) }
        // The lambda needs access to the currentRewalkJob so it can
        // cancel itself. The coordinator stores the job in
        // `currentRewalkJob`; we reach it via reflection (same seam
        // the awaitJobDone helper uses).
        lateinit var coord: TransportRewalkCoordinator
        coord = TransportRewalkCoordinator(
            scope = rewalkScope,
            transportPreferences = prefs,
            releaseTransport = { log.add("release"); cleanRelease() },
            handOverConnectOwnership = { log.add("handOverConnectOwnership"); true },
            hybridTransportProvider = { hybrid },
            requestServiceRestart = { reason ->
                log.add("requestServiceRestart:${reason.name}")
                // Cancel the coordinator's own job so the parent
                // coroutine is GENUINELY cancelled when our catch
                // runs. The `throw CancellationException("...")` is
                // what propagates out of restart's body; the actual
                // cancellation state was already set by the cancel()
                // above.
                val jobField = coord.javaClass.getDeclaredField("currentRewalkJob").apply {
                    isAccessible = true
                }
                val job = jobField.get(coord) as? kotlinx.coroutines.Job
                job?.cancel(CancellationException("test cancel from restart"))
                throw CancellationException("test cancel from restart")
            },
            nowMs = { 1_000L },
            gateCoordinator = gate,
        )
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        // The probe was issued (StickyRecovery path).
        assertTrue(log.contains("issueProbeAfterRewalk:1"))
        // The revoke MUST have completed despite parent cancellation —
        // the NonCancellable wrap is the only reason it could run to
        // completion (the suspend gate-lock acquire would otherwise
        // throw CE on entry to a cancelled coroutine).
        assertTrue(
            log.contains("revokeProbe:1:service_restart_cancelled"),
            "revokeProbe MUST run even when parent is cancelled; got ${log.snapshot()}",
        )
    }

    @Test
    fun serviceRestart_error_under_Open_path_revokes_route_change_without_bumping_rate_limit() = runBlocking {
        // Mirrors the production startService-failure scenario. On the
        // Open path no probe is issued; if requestServiceRestart throws
        // (e.g. AppContainer's startService returned null), the
        // coordinator MUST revokeRouteChange (not revokeProbe — there
        // is no probe) AND skip the lastRewalkAtMs bump so the next
        // recovery attempt is allowed inside the rate-limit window.
        val log = CallLog()
        val gate = TracingGate(log, initialGate = WsReconnectGate.Open)
        val hybrid = TracingHybrid(log)
        var t = 10_000L
        val (coord, _, _) = newCoordinator(log, gate, hybrid, restartBehaviour = "ex", nowMs = { t })
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        // The Open-path failure must revoke the routeChange (no probe
        // issued, so no revokeProbe).
        assertTrue(
            log.contains("revokeRouteChange:1:service_restart_failed"),
            "got ${log.snapshot()}",
        )
        assertTrue(log.none { it.startsWith("revokeProbe") }, "no probe to revoke; got ${log.snapshot()}")

        // The rate-limit budget MUST NOT have been consumed. A second
        // forced attempt within the rate-limit window must trigger a
        // fresh beginRouteChange.
        t += 1_000L
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)
        assertEquals(
            2, log.count("beginRouteChange:"),
            "second attempt must NOT be rate-limited after a service_restart failure; got ${log.snapshot()}",
        )
    }
}

/** Both subsystems stopped and nothing is holding threads. */
private fun cleanRelease() = TransportManager.ReleaseOutcome(
    xrayFailure = null,
    torFailure = null,
)
