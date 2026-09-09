// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import phantom.core.xray.XrayService
import phantom.core.xray.XrayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N1-F2 R-N1.5 P2 — `release()` must report what actually happened.
 *
 * It used to wrap both subsystem stops in bare `runCatching` and return
 * `Unit`, so a Xray or Tor that refused to stop was invisible and a
 * privacy-mode switch could be reported clean while a proxy subsystem
 * was still running.
 *
 * `PrivacyModeTeardownTransactionTest` covers what the TRANSACTION does
 * with the outcome, but it supplies the outcome itself. These cases
 * cover the other half: that `release()` populates it truthfully.
 * Without them, `release()` could return a permanently-clean value and
 * every teardown assertion would still pass.
 */
class TransportManagerReleaseOutcomeTest {

    private fun TestScope.manager(
        tor: TorService,
        xray: XrayService,
    ) = TransportManager(
        torServiceProvider = { tor },
        xrayServiceProvider = { xray },
        preferences = InMemoryTransportPreferences(PrivacyMode.Standard),
        probe = TransportProbe { _, _ -> true },
        nowMs = { 0L },
        policy = PrivacyModeCoordinator(InMemoryTransportPreferences(PrivacyMode.Standard).privacyMode),
        // The teardown attempts must share this test's clock: they are
        // issued into a scope of their own so a bounded wait cannot cancel
        // them, and a scope on a real dispatcher would make every bounded
        // wait here expire against virtual time.
        teardownWorkers = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
    )

    private fun okTor(stopped: MutableList<String>? = null): TorService = object : TorService {
        private val flow = MutableStateFlow<TorState>(TorState.Ready(socksPort = 9050))
        override val state: StateFlow<TorState> = flow.asStateFlow()
        override suspend fun start(bridgeProfile: BridgeProfile) = Unit
        override suspend fun stop(budget: TorBudget): TorStopResponse {
            stopped?.add("tor")
            flow.value = TorState.Off
            return freeStopResponse()
        }

        override suspend fun awaitRelease(
            attempt: TorStopAttempt,
            budget: TorBudget,
        ): TorStopResult = freeStopResponse().result
    }

    private fun okXray(stopped: MutableList<String>? = null): XrayService = object : XrayService {
        private val flow = MutableStateFlow<XrayState>(XrayState.Ready(socksPort = 10808))
        override val state: StateFlow<XrayState> = flow.asStateFlow()
        override suspend fun start() = Unit
        override suspend fun stop() {
            stopped?.add("xray")
            flow.value = XrayState.Off
        }
    }

    private fun failingTor(): TorService = object : TorService {
        private val flow = MutableStateFlow<TorState>(TorState.Ready(socksPort = 9050))
        override val state: StateFlow<TorState> = flow.asStateFlow()
        override suspend fun start(bridgeProfile: BridgeProfile) = Unit
        // A tor that will not confirm it stopped. Under the result-based
        // contract that is an answer, not a thrown error: the manager has to
        // read it rather than catch it.
        override suspend fun stop(budget: TorBudget): TorStopResponse =
            unconfirmedStopResponse(cause = IllegalStateException("tor refused to stop"))

        override suspend fun awaitRelease(
            attempt: TorStopAttempt,
            budget: TorBudget,
        ): TorStopResult = unconfirmedStopResponse().result
    }

    private fun failingXray(): XrayService = object : XrayService {
        private val flow = MutableStateFlow<XrayState>(XrayState.Ready(socksPort = 10808))
        override val state: StateFlow<XrayState> = flow.asStateFlow()
        override suspend fun start() = Unit
        override suspend fun stop(): Unit = throw IllegalStateException("xray refused to stop")
    }

    // ── Positive control ──────────────────────────────────────────────
    @Test
    fun a_healthy_release_stops_both_subsystems_and_reports_clean() = runTest {
        val stopped = mutableListOf<String>()
        val outcome = manager(okTor(stopped), okXray(stopped)).release()

        assertTrue(outcome.clean, "a healthy release must report clean")
        assertNull(outcome.torFailure)
        assertNull(outcome.xrayFailure)
        assertEquals(
            setOf("tor", "xray"), stopped.toSet(),
            "both subsystems must actually be stopped",
        )
    }

    @Test
    fun release_returns_the_manager_to_idle() = runTest {
        val mgr = manager(okTor(), okXray())
        mgr.release()
        assertIs<ManagerState.Idle>(mgr.state.value)
    }

    // ── Negative: each failure is surfaced ────────────────────────────
    @Test
    fun a_failing_xray_stop_is_reported() = runTest {
        val outcome = manager(okTor(), failingXray()).release()
        assertFalse(outcome.clean, "a failed Xray stop must not report clean")
        assertIs<IllegalStateException>(outcome.xrayFailure)
        assertNull(outcome.torFailure)
    }

    @Test
    fun a_failing_tor_stop_is_reported() = runTest {
        val outcome = manager(failingTor(), okXray()).release()
        assertFalse(outcome.clean)
        assertIs<TorLifecycleUnsettled>(outcome.torFailure)
        assertEquals("daemon_unknown", outcome.torIncomplete)
        assertNull(outcome.xrayFailure)
    }

    @Test
    fun both_failures_are_reported_independently() = runTest {
        val outcome = manager(failingTor(), failingXray()).release()
        assertFalse(outcome.clean)
        assertIs<TorLifecycleUnsettled>(outcome.torFailure)
        assertEquals("daemon_unknown", outcome.torIncomplete)
        // Xray still reports the way it always did: it throws, and the throw
        // is carried. The two subsystems are independent in HOW they fail as
        // well as in WHETHER they do.
        assertIs<IllegalStateException>(outcome.xrayFailure)
    }

    // ── A failing subsystem must not stop the other one ───────────────
    @Test
    fun a_failing_xray_stop_does_not_skip_the_tor_stop() = runTest {
        val stopped = mutableListOf<String>()
        manager(okTor(stopped), failingXray()).release()
        assertTrue(
            "tor" in stopped,
            "a throwing Xray stop must not leave Tor running: both subsystems have to be " +
                "attempted, or a privacy switch can leave a proxy up",
        )
    }

    // ── The manager still reaches Idle even when a stop fails ─────────
    @Test
    fun a_failed_stop_still_returns_the_manager_to_idle() = runTest {
        // Deliberate: a subsystem that refuses to stop is not a reason to
        // pin the manager in a stale state. The failure is reported
        // instead of being hidden by the state.
        val mgr = manager(failingTor(), failingXray())
        val outcome = mgr.release()
        assertIs<ManagerState.Idle>(mgr.state.value)
        assertFalse(outcome.clean, "but the outcome must still say it was not clean")
    }
}
