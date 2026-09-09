// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import phantom.core.xray.XrayService
import phantom.core.xray.XrayState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * R-1 P1. `TransportManager.release()` against a cancellation that arrives
 * while the teardown is in flight.
 *
 * This lives on real threads rather than on `runTest`'s virtual time, and
 * deliberately so. The property is about what a cancelled coroutine may and
 * may not interrupt, and `runTest` does not resume a `NonCancellable` block
 * after its caller is cancelled — measured, in this very round, with a
 * two-line isolation case that used no production code at all. A fixture
 * that cannot resume the block cannot say anything about whether the block
 * protects the teardown.
 *
 * So: a real dispatcher, a real cancellation, and a real suspension inside
 * the resource operation. Remembering an exception object is not the same
 * as cancelling a coroutine, and a double that only threw would prove
 * nothing about a lifecycle mutex or a dispatcher hop.
 */
class ReleaseUnderCancellationTest {

    @Test
    fun a_cancellation_during_the_teardown_does_not_abort_it() {
        val entered = CountDownLatch(1)
        val park = CompletableDeferred<Unit>()
        val xray = ParkedXray(entered, park)
        val tor = RecordingTor()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            var escaped: Throwable? = null
            val job = scope.launch {
                escaped = runCatching { manager(tor, xray).release() }.exceptionOrNull()
            }

            assertTrue(
                entered.await(10, TimeUnit.SECONDS),
                "the teardown never reached the resource operation",
            )

            // The real shape: an outer budget elapses and cancels the caller
            // while the teardown is suspended inside Xray's stop.
            job.cancel(CancellationException("subsystem stop budget elapsed"))
            park.complete(Unit)
            runBlocking { job.join() }

            assertTrue(xray.stopped, "the cancellation tore the xray teardown out")
            assertTrue(
                xray.crossedSuspension,
                "the fake answered without crossing a suspension boundary, so this " +
                    "says nothing about the real one",
            )
            assertTrue(
                tor.stopped,
                "tor never got its attempt: the cancellation that interrupted one " +
                    "subsystem took the other's teardown with it",
            )

            // Honoured, not swallowed — after both attempts, not instead of them.
            assertIs<CancellationException>(
                escaped,
                "release() absorbed the caller's cancellation",
            )
        } finally {
            park.complete(Unit)
            scope.cancel()
        }
    }

    /**
     * The control for the case above: the same fakes, nothing cancelled. If
     * this fixture could not produce a clean release at all, the test above
     * would be passing on a teardown that never works.
     */
    @Test
    fun the_same_teardown_without_a_cancellation_is_clean() {
        val park = CompletableDeferred(Unit)
        val xray = ParkedXray(CountDownLatch(1), park)
        val tor = RecordingTor()

        val outcome = runBlocking { manager(tor, xray).release() }

        assertTrue(outcome.clean, "the fixture cannot produce a clean release")
        assertTrue(xray.stopped && tor.stopped)
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private fun manager(tor: TorService, xray: XrayService) = TransportManager(
        torServiceProvider = { tor },
        xrayServiceProvider = { xray },
        preferences = InMemoryTransportPreferences(PrivacyMode.Standard),
        probe = TransportProbe { _, _ -> true },
        nowMs = { 0L },
        policy = PrivacyModeCoordinator(
            InMemoryTransportPreferences(PrivacyMode.Standard).privacyMode,
        ),
    )

    /** Suspends inside its teardown, where a cancellation would land. */
    private class ParkedXray(
        private val entered: CountDownLatch,
        private val park: CompletableDeferred<Unit>,
    ) : XrayService {
        private val flow = MutableStateFlow<XrayState>(XrayState.Ready(socksPort = 10808))
        override val state: StateFlow<XrayState> = flow.asStateFlow()

        @Volatile
        var stopped = false
            private set

        @Volatile
        var crossedSuspension = false
            private set

        override suspend fun start() = Unit

        override suspend fun stop() {
            entered.countDown()
            park.await()
            yield()
            crossedSuspension = true
            stopped = true
            flow.value = XrayState.Off
        }
    }

    private class RecordingTor : TorService {
        private val flow = MutableStateFlow<TorState>(TorState.Ready(socksPort = 9050))
        override val state: StateFlow<TorState> = flow.asStateFlow()

        @Volatile
        var stopped = false
            private set

        override suspend fun start(bridgeProfile: BridgeProfile) = Unit

        override suspend fun stop(budget: TorBudget): TorStopResponse {
            yield()
            stopped = true
            flow.value = TorState.Off
            return freeStopResponse()
        }

        override suspend fun awaitRelease(
            attempt: TorStopAttempt,
            budget: TorBudget,
        ): TorStopResult = freeStopResponse().result
    }
}
