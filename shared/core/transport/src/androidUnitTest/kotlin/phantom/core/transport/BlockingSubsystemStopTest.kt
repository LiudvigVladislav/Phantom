// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import phantom.core.xray.XrayService
import phantom.core.xray.XrayState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * R-2 P1. The budget bounds the WAIT, never the work — for the subsystem
 * teardowns as well as for tor.
 *
 * A timeout wrapped around the teardown itself gets this wrong in both
 * directions. Around a suspending stop it CANCELS the teardown, possibly
 * before the resource operation ever runs, leaving nothing that will finish
 * it. Around a BLOCKING one it bounds nothing at all: `LibXray.stopXray()`
 * does not return because a coroutine was cancelled, so the caller waits on
 * the library anyway, the other subsystem is never even asked, and the
 * shutdown path — which calls this inside `runBlocking` — sits on the main
 * thread while it happens.
 *
 * So the fixture here BLOCKS a thread on a latch. That is deliberate: a fake
 * that parks on `awaitCancellation()` ends precisely because it was
 * cancelled, and would have proved the opposite of what is claimed.
 */
class BlockingSubsystemStopTest {

    @Test
    fun a_blocking_close_neither_holds_the_waiter_nor_costs_tor_its_attempt() {
        val entered = CountDownLatch(1)
        val park = CountDownLatch(1)
        val xray = BlockingXray(entered, park)
        val tor = RecordingTor()
        try {
            val startedAt = System.nanoTime()
            val outcome = runBlocking { manager(tor, xray).release() }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue(
                entered.await(10, TimeUnit.SECONDS),
                "the teardown never reached the blocking close",
            )
            assertFalse(
                xray.stopped,
                "the close returned on its own, so nothing was ever parked and " +
                    "this says nothing about a blocking library",
            )

            // The waiter came back on its own budget, not on the library.
            assertTrue(
                elapsedMs < WAITED_TOO_LONG_MS,
                "release() waited on the blocking close for ${elapsedMs}ms",
            )
            val notConfirmed = assertIs<SubsystemStopNotConfirmed>(outcome.xrayFailure)
            assertEquals("xray", notConfirmed.subsystem)
            assertFalse(outcome.clean, "an unconfirmed stop is not a clean release")

            // And tor still got its attempt. Waiting on a wedged Xray used to
            // mean tor was never asked at all.
            assertTrue(tor.stopped, "tor never got its attempt")

            // The attempt was not cancelled and not abandoned: the SAME work
            // is still there and finishes when the library finally returns.
            park.countDown()
            assertTrue(
                xray.awaitStopped(10, TimeUnit.SECONDS),
                "the parked teardown never completed, so it had been torn out",
            )
        } finally {
            park.countDown()
        }
    }

    /**
     * The control: the same fixture with a close that returns promptly. If
     * this teardown could never be confirmed at all, the case above would be
     * asserting on a fixture that simply does not work.
     */
    @Test
    fun a_close_that_returns_is_confirmed_and_clean() {
        val park = CountDownLatch(0)
        val xray = BlockingXray(CountDownLatch(1), park)
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

    /**
     * A close that blocks its thread. Nothing a coroutine does makes it
     * return; only the latch does.
     */
    private class BlockingXray(
        private val entered: CountDownLatch,
        private val park: CountDownLatch,
    ) : XrayService {
        private val flow = MutableStateFlow<XrayState>(XrayState.Ready(socksPort = 10808))
        private val returned = CountDownLatch(1)
        override val state: StateFlow<XrayState> = flow.asStateFlow()

        @Volatile
        var stopped = false
            private set

        override suspend fun start() = Unit

        override suspend fun stop() {
            entered.countDown()
            park.await(PARK_CEILING_MS, TimeUnit.MILLISECONDS)
            stopped = true
            flow.value = XrayState.Off
            returned.countDown()
        }

        fun awaitStopped(timeout: Long, unit: TimeUnit): Boolean = returned.await(timeout, unit)
    }

    private class RecordingTor : TorService {
        private val flow = MutableStateFlow<TorState>(TorState.Ready(socksPort = 9050))
        override val state: StateFlow<TorState> = flow.asStateFlow()

        @Volatile
        var stopped = false
            private set

        override suspend fun start(bridgeProfile: BridgeProfile) = Unit

        override suspend fun stop(budget: TorBudget): TorStopResponse {
            stopped = true
            flow.value = TorState.Off
            return freeStopResponse()
        }

        override suspend fun awaitRelease(
            attempt: TorStopAttempt,
            budget: TorBudget,
        ): TorStopResult = freeStopResponse().result
    }

    private companion object {
        /**
         * Comfortably above the two budgets the waiter may legitimately
         * spend, and far below "waited on the library".
         */
        const val WAITED_TOO_LONG_MS =
            TransportManager.SUBSYSTEM_STOP_TIMEOUT_MS * 3

        /**
         * The park has a ceiling of its own so that a version which waits on
         * the library finishes and FAILS on the elapsed time. Without it that
         * version would hang, and a hang cannot tell a missing bound from a
         * broken fixture.
         */
        const val PARK_CEILING_MS =
            TransportManager.SUBSYSTEM_STOP_TIMEOUT_MS * 8
    }
}
