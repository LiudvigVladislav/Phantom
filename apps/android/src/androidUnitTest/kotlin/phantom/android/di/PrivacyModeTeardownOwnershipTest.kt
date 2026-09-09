// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.di

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import phantom.core.transport.BridgeProfile
import phantom.core.transport.ConnectOwnership
import phantom.core.transport.ConnectWalkHandle
import phantom.core.transport.HandoffRecovery
import phantom.core.transport.Handover
import phantom.core.transport.InMemoryTransportPreferences
import phantom.core.transport.PrivacyModeCoordinator
import phantom.core.transport.PrivacyMode
import phantom.core.transport.TorService
import phantom.core.transport.torStopNothingToStop
import phantom.core.transport.TorStopResult
import phantom.core.transport.TorStopResponse
import phantom.core.transport.TorStopAttempt
import phantom.core.transport.TorBudget
import phantom.core.transport.TorState
import phantom.core.transport.TransportKind
import phantom.core.transport.TransportManager
import phantom.core.transport.TransportProbe
import phantom.core.xray.XrayService
import phantom.core.xray.XrayState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R-N1.16 P1 - a privacy switch and a shutdown must obey the same
 * cancel -> confirmed join -> release contract as a network rewalk.
 *
 * The defect these pin is a No Silent Downgrade violation, not an
 * ordering nicety. `disconnectAndJoin` tears down the WSS socket and
 * says nothing about the OUTER chain walk, which reads the privacy mode
 * once at its start and is not serialised against `release()`. So a
 * Standard -> Ghost switch could leave a Direct walk in flight that went
 * on to open a Direct WSS after the user had already been shown Ghost.
 */
class PrivacyModeTeardownOwnershipTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun scope(): CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes.add(it) }

    private fun coordinator(prefs: InMemoryTransportPreferences) =
        PrivacyModeCoordinator(
            initialMode = prefs.privacyMode,
            persistRequested = { mode -> prefs.privacyMode = mode },
        )

    private fun manager(
        prefs: InMemoryTransportPreferences,
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
    fun a_privacy_switch_stops_the_old_walk_before_it_releases() = runBlocking {
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val attempted = java.util.Collections.synchronizedList(mutableListOf<TransportKind>())
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val parked = CompletableDeferred<Unit>()
        val probe = TransportProbe { kind, _ ->
            attempted.add(kind)
            parked.complete(Unit)
            delay(60_000)
            true
        }
        val policy = coordinator(prefs)
        val mgr = manager(prefs, probe, policy)
        val ownership = ConnectOwnership(nextToken = { 1L }, handoverTimeoutMs = 5_000L)

        // A Standard walk is in flight, parked inside the Direct probe.
        val walkScope = scope()
        val walk = walkScope.launch {
            val me = currentCoroutineContext()[Job]
            ownership.claim(
                "standard_walk",
                ConnectWalkHandle { timeoutMs: Long ->
                    order.add("cancel")
                    me?.cancel(CancellationException("ownership_handover"))
                    val joined = me == null || withTimeoutOrNull(timeoutMs) { me.join() } != null
                    order.add("joined")
                    joined
                },
            )
            runCatching { mgr.connect() }
        }
        parked.await()
        assertEquals(listOf(TransportKind.Direct), synchronized(attempted) { attempted.toList() })

        // The user switches to Ghost.
        policy.requestMode(PrivacyMode.Ghost)
        val outcome = runPrivacyModeTeardown(
            revoke = { order.add("revoke"); null },
            disconnectAndJoin = { order.add("disconnectAndJoin"); true },
            handOverWalk = {
                when (ownership.handOver("privacy_mode_change")) {
                    is Handover.Quiesced, Handover.NothingToStop -> true
                    else -> false
                }
            },
            release = { order.add("release"); mgr.release() },
        )

        assertTrue(outcome.walkQuiesced, "the old walk must be confirmed stopped")
        assertTrue(walk.isCompleted, "and it really is finished")
        // Iterating a synchronizedList requires holding its own monitor.
        val seen = synchronized(order) { order.toList() }
        assertTrue(
            seen.indexOf("cancel") < seen.indexOf("joined"),
            "cancel precedes the join; order=$seen",
        )
        assertTrue(
            seen.indexOf("joined") < seen.indexOf("release"),
            "and the join is confirmed BEFORE the release; order=$seen",
        )
        assertEquals(
            listOf(TransportKind.Direct),
            synchronized(attempted) { attempted.toList() },
            "the old walk never reached another transport after being cancelled",
        )

        // The connect that follows the switch walks the Ghost chain only.
        attempted.clear()
        val after = manager(prefs, TransportProbe { kind, _ -> attempted.add(kind); true }, policy)
        val connected = after.connect()
        assertEquals(
            listOf(TransportKind.Tor),
            synchronized(attempted) { attempted.toList() },
            "after switching to Ghost the chain is [Tor] - a Direct attempt here " +
                "would be the silent downgrade this fixture exists to prevent",
        )
        assertEquals(TransportKind.Tor, connected.kind)
    }

    @Test
    fun a_privacy_switch_that_cannot_stop_the_walk_does_not_release() = runBlocking {
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val mgr = manager(prefs, TransportProbe { _, _ -> true }, policy)
        val ownership = ConnectOwnership(nextToken = { 1L }, handoverTimeoutMs = 200L)
        val token = ownership.claim("standard_walk", ConnectWalkHandle { false })
        assertNotNull(token)

        policy.requestMode(PrivacyMode.Ghost)
        val outcome = runPrivacyModeTeardown(
            revoke = { order.add("revoke"); null },
            disconnectAndJoin = { order.add("disconnectAndJoin"); true },
            handOverWalk = {
                when (ownership.handOver("privacy_mode_change")) {
                    is Handover.Quiesced, Handover.NothingToStop -> true
                    else -> false
                }
            },
            release = { order.add("release"); mgr.release() },
        )

        assertFalse(outcome.walkQuiesced)
        assertNull(outcome.release, "no release outcome, because release never ran")
        assertFalse(
            order.contains("release"),
            "releasing under a walk we could not stop is the defect; order=$order",
        )
        assertEquals(token, ownership.currentOwner(), "the lease stays held")
        assertNull(
            ownership.claim("successor", ConnectWalkHandle { true }),
            "and no successor starts",
        )
    }

    @Test
    fun recovery_after_a_failed_privacy_switch_uses_the_new_mode() = runBlocking {
        // The switch already persisted Ghost before the teardown ran, so
        // whatever connects later must read Ghost - recovery must not
        // resurrect the mode the blocked walk was using.
        val prefs = InMemoryTransportPreferences(PrivacyMode.Standard)
        val policy = coordinator(prefs)
        val ownership = ConnectOwnership(nextToken = { 1L }, handoverTimeoutMs = 200L)
        val stubborn = java.util.concurrent.atomic.AtomicBoolean(true)
        assertNotNull(
            ownership.claim("standard_walk", ConnectWalkHandle { !stubborn.get() }),
        )
        val starts = java.util.Collections.synchronizedList(mutableListOf<String>())
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { starts.add(it) },
            intervalMs = 50L,
        )

        policy.requestMode(PrivacyMode.Ghost)
        val outcome = runPrivacyModeTeardown(
            revoke = { null },
            disconnectAndJoin = { true },
            handOverWalk = { recovery.handOverOrArm("privacy_mode_change") },
            release = { error("release must not run") },
        )
        assertFalse(outcome.walkQuiesced)
        assertTrue(recovery.isArmed(), "recovery is armed after a failed switch")

        stubborn.set(false)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && starts.isEmpty()) {
            delay(25)
        }
        assertEquals(1, starts.size, "exactly one ordinary connect follows; got $starts")

        val attempted = mutableListOf<TransportKind>()
        val after = manager(prefs, TransportProbe { kind, _ -> attempted.add(kind); true }, policy)
        after.connect()
        assertEquals(
            listOf(TransportKind.Tor),
            attempted,
            "the recovered connect reads the NEW privacy mode, not the one the " +
                "blocked walk started under",
        )
        assertEquals(
            HandoffRecovery.StandDown.Clear,
            recovery.standDown("test_teardown"),
            "no settlement was owed here",
        )
    }

    private fun fakeTor(): TorService = object : TorService {
        private val flow = MutableStateFlow<TorState>(TorState.Ready(socksPort = 9050))
        override val state: StateFlow<TorState> = flow.asStateFlow()
        override suspend fun start(bridgeProfile: BridgeProfile) { /* already Ready */ }
        override suspend fun stop(budget: TorBudget): TorStopResponse {
            flow.value = TorState.Off
            return torStopNothingToStop()
        }

        override suspend fun awaitRelease(
            attempt: TorStopAttempt,
            budget: TorBudget,
        ): TorStopResult = torStopNothingToStop().result
    }

    private fun fakeXray(): XrayService = object : XrayService {
        private val flow = MutableStateFlow<XrayState>(XrayState.Ready(socksPort = 10808))
        override val state: StateFlow<XrayState> = flow.asStateFlow()
        override suspend fun start() { /* already Ready */ }
        override suspend fun stop() { flow.value = XrayState.Off }
    }
}
