// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import phantom.core.xray.XrayService
import phantom.core.xray.XrayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the manager does with a teardown result, and what a teardown result
 * is allowed to say out loud.
 *
 * Three separate properties live here, and each has a control that fails if
 * the property is implemented by accident:
 *
 *  1. the per-profile budget covers the LAUNCH, not just the wait after it;
 *  2. an unsettled lifecycle leaves the walk as itself rather than joining
 *     the ladder of unreachable transports;
 *  3. nothing renders a library cause into a log line.
 */
class TorTeardownContractTest {

    // ── 1. the budget covers the launch ──────────────────────────────────

    /**
     * A launch that takes far longer than the profile is given must not
     * spend that time: the budget has to start before `start()`, not after
     * it returns.
     *
     * The fake's launch is finite on purpose. A launch that never returned
     * would make an unbudgeted implementation HANG, and a hang is a much
     * weaker signal than a number — it cannot tell "the budget is missing"
     * from "the fixture is wrong". With a finite, over-budget launch both
     * versions finish and the elapsed virtual time separates them.
     */
    @Test
    fun the_profile_budget_covers_the_launch_and_not_only_the_wait() = runTest {
        val tor = SlowLaunchTor(launchMs = LAUNCH_MS)
        val manager = manager(tor)

        assertFailsWith<NoTransportReachableException> { manager.connect() }

        // Every profile was tried and every one was stopped afterwards.
        assertEquals(TransportManager.BRIDGE_ROTATION_ORDER.size, tor.starts)
        assertTrue(
            tor.stops >= tor.starts,
            "a profile was abandoned without a stop: ${tor.starts} starts, ${tor.stops} stops",
        )

        // The whole rotation cost no more than the sum of its budgets plus
        // slack. Without the launch inside the budget it would cost
        // `starts * LAUNCH_MS`, which is an order of magnitude more.
        val budgets = TransportManager.BRIDGE_ROTATION_ORDER.sumOf { it.budgetMs }
        assertTrue(
            testScheduler.currentTime <= budgets + SLACK_MS,
            "the rotation took ${testScheduler.currentTime}ms against a total budget of ${budgets}ms: " +
                "the launch is outside the budget",
        )
        assertTrue(
            testScheduler.currentTime < LAUNCH_MS,
            "the rotation outlasted a single launch, so no budget bounded it",
        )
    }

    // ── 2. an unsettled lifecycle is not an unreachable transport ────────

    @Test
    fun a_teardown_that_never_confirmed_leaves_the_walk_as_itself() = runTest {
        val tor = UnsettledTeardownTor(response = { unconfirmedStopResponse(cause = SECRET) })

        val unsettled = assertFailsWith<TorLifecycleUnsettled> { manager(tor).connect() }
        assertUnknown(unsettled.result)

        // The control: had it been caught as an ordinary profile failure,
        // the rotation would have gone on and the walk would have ended in
        // the chain's own exhaustion instead.
        assertEquals(1, tor.starts, "the rotation continued past an unsettled lifecycle")
    }

    @Test
    fun a_host_that_did_not_let_go_also_leaves_the_walk() = runTest {
        val tor = UnsettledTeardownTor(response = { releaseFailedStopResponse(cause = SECRET) })

        val unsettled = assertFailsWith<TorLifecycleUnsettled> { manager(tor).connect() }
        assertTrue(unsettled.result is TorStopResult.ReleaseFailed)
        assertEquals(1, tor.starts)
    }

    /**
     * The blocker this contract was rewritten around. Inheriting
     * cancellation would let this ride every path built for a caller
     * changing its mind — including the one that arms an ordinary connect
     * retry, which is the single thing that must not happen after an
     * unconfirmed daemon.
     */
    @Test
    fun an_unsettled_lifecycle_is_not_a_cancellation() {
        val response = unconfirmedStopResponse(cause = SECRET)
        val unsettled = TorLifecycleUnsettled(response.attempt, response.result)

        assertFalse(
            unsettled is CancellationException,
            "TorLifecycleUnsettled inherits CancellationException",
        )
    }

    // ── 3. release() reports which half is unfinished ────────────────────

    @Test
    fun release_keeps_the_two_unfinished_halves_apart() = runTest {
        val unconfirmed = manager(UnsettledTeardownTor { unconfirmedStopResponse(cause = SECRET) })
            .release()
        val unreleased = manager(UnsettledTeardownTor { releaseFailedStopResponse(cause = SECRET) })
            .release()

        assertFalse(unconfirmed.clean)
        assertFalse(unreleased.clean)
        assertNotEquals(
            unconfirmed.torIncomplete,
            unreleased.torIncomplete,
            "a daemon nobody confirmed gone reads the same as a host that kept its threads",
        )
        assertEquals("daemon_unknown", unconfirmed.torIncomplete)
        assertEquals("host_not_released", unreleased.torIncomplete)
    }

    @Test
    fun a_clean_release_names_nothing_as_unfinished() = runTest {
        val outcome = manager(FreeTor()).release()

        assertTrue(outcome.clean)
        assertNull(outcome.torIncomplete)
    }

    @Test
    fun an_unsettled_tor_does_not_cost_xray_its_teardown() = runTest {
        val xray = RecordingXray()
        val outcome = manager(UnsettledTeardownTor { unconfirmedStopResponse(cause = SECRET) }, xray)
            .release()

        assertTrue(xray.stopped, "xray never got its attempt")
        assertFalse(outcome.clean)
    }

    /**
     * R-1 P2. `clean` is what every consumer downstream reads. The type is
     * public and constructible by anyone, so an outcome carrying a failed
     * result and no failure object must not be able to call itself clean:
     * the structured answer is the one that cannot be forgotten.
     */
    @Test
    fun clean_cannot_disagree_with_the_structured_result() {
        val contradictory = TransportManager.ReleaseOutcome(
            xrayFailure = null,
            torFailure = null,
            torResult = releaseFailedStopResponse(cause = SECRET).result,
        )

        assertFalse(contradictory.clean, "an unreleased host reported itself clean")
        assertEquals("host_not_released", contradictory.torIncomplete)

        // The positive control: a free result with no failures IS clean, so
        // this is not just a flag that is always false.
        assertTrue(
            TransportManager.ReleaseOutcome(
                xrayFailure = null,
                torFailure = null,
                torResult = TorStopResult.Free(1L, quiesceFailure = null),
            ).clean,
        )
    }

    // ── 4. nothing renders a library cause ───────────────────────────────

    /**
     * `ReleaseOutcome` is documented to carry exception CLASSES only, so a
     * subsystem error cannot leak configuration or endpoint detail. The
     * results carry causes, so anything that renders one WHOLE — a data
     * class `toString`, an exception message built from it — puts the tor
     * library's own text into a log line. Those texts name paths, ports and
     * bridge configuration.
     */
    @Test
    fun nothing_a_caller_logs_carries_the_cause_text() = runTest {
        val results = listOf(
            unconfirmedStopResponse(cause = SECRET).result,
            releaseFailedStopResponse(cause = SECRET).result,
            notConfirmedYet(1L),
            TorStopResult.Free(1L, quiesceFailure = SECRET),
        )

        for (result in results) {
            assertFalse(
                SECRET_TEXT in result.label,
                "the label of ${result::class.simpleName} carries the cause text",
            )
        }

        val response = unconfirmedStopResponse(cause = SECRET)
        assertFalse(
            SECRET_TEXT in TorLifecycleUnsettled(response.attempt, response.result).message.orEmpty(),
            "the exception message carries the cause text",
        )
        assertFalse(
            SECRET_TEXT in manager(UnsettledTeardownTor { response }).release().torIncomplete.orEmpty(),
            "the outcome's rendering carries the cause text",
        )
    }

    /**
     * The positive half of the control above: a vocabulary that said the
     * same word for everything would pass every assertion in it while
     * telling a reader nothing.
     */
    @Test
    fun the_vocabulary_distinguishes_every_answer() {
        val labels = listOf(
            notConfirmedYet(1L),
            unconfirmedStopResponse(cause = SECRET).result,
            TorStopResult.Releasing(1L),
            releaseFailedStopResponse(cause = SECRET).result,
            TorStopResult.Free(1L, quiesceFailure = null),
        ).map { it.label }

        assertEquals(labels.size, labels.toSet().size, "two answers share one word: $labels")
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private fun TestScope.manager(
        tor: TorService,
        xray: XrayService = RecordingXray(),
    ) = TransportManager(
        torServiceProvider = { tor },
        xrayServiceProvider = { xray },
        preferences = InMemoryTransportPreferences(PrivacyMode.Ghost),
        probe = TransportProbe { _, _ -> false },
        nowMs = { 0L },
        policy = PrivacyModeCoordinator(InMemoryTransportPreferences(PrivacyMode.Ghost).privacyMode),
        // The teardown attempts must share this test's clock: they are
        // issued into a scope of their own so a bounded wait cannot cancel
        // them, and a scope on a real dispatcher would make every bounded
        // wait here expire against virtual time.
        teardownWorkers = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
    )

    /** A tor whose launch takes longer than any profile is given. */
    private class SlowLaunchTor(private val launchMs: Long) : TorService {
        private val flow = MutableStateFlow<TorState>(TorState.Off)
        override val state: StateFlow<TorState> = flow.asStateFlow()
        var starts = 0
            private set
        var stops = 0
            private set

        override suspend fun start(bridgeProfile: BridgeProfile) {
            starts++
            delay(launchMs)
            flow.value = TorState.Bootstrapping(0)
        }

        override suspend fun stop(budget: TorBudget): TorStopResponse {
            stops++
            flow.value = TorState.Off
            return freeStopResponse()
        }

        override suspend fun awaitRelease(
            attempt: TorStopAttempt,
            budget: TorBudget,
        ): TorStopResult = freeStopResponse().result
    }

    /** A tor that comes up and then will not settle when torn down. */
    private class UnsettledTeardownTor(
        private val response: () -> TorStopResponse,
    ) : TorService {
        private val flow = MutableStateFlow<TorState>(TorState.Off)
        override val state: StateFlow<TorState> = flow.asStateFlow()
        var starts = 0
            private set

        override suspend fun start(bridgeProfile: BridgeProfile) {
            starts++
            // A profile that does not come up. That is the ordinary case
            // the rotation is built for -- and the one whose teardown
            // decides whether the next profile may be tried at all.
            flow.value = TorState.Failed("bootstrap gave up")
        }

        override suspend fun stop(budget: TorBudget): TorStopResponse = response()

        override suspend fun awaitRelease(
            attempt: TorStopAttempt,
            budget: TorBudget,
        ): TorStopResult = response().result
    }

    private class FreeTor : TorService {
        private val flow = MutableStateFlow<TorState>(TorState.Off)
        override val state: StateFlow<TorState> = flow.asStateFlow()
        override suspend fun start(bridgeProfile: BridgeProfile) = Unit
        override suspend fun stop(budget: TorBudget): TorStopResponse = freeStopResponse()
        override suspend fun awaitRelease(
            attempt: TorStopAttempt,
            budget: TorBudget,
        ): TorStopResult = freeStopResponse().result
    }

    private class RecordingXray : XrayService {
        private val flow = MutableStateFlow<XrayState>(XrayState.Off)
        override val state: StateFlow<XrayState> = flow.asStateFlow()
        var stopped = false
            private set

        override suspend fun start() = Unit
        override suspend fun stop() {
            stopped = true
            flow.value = XrayState.Off
        }
    }

    private companion object {
        /**
         * Longer than the largest profile budget by a wide margin, so an
         * unbudgeted launch is unmistakable in the elapsed time.
         */
        const val LAUNCH_MS = 10_000_000L
        const val SLACK_MS = 60_000L
        const val SECRET_TEXT = "bridge obfs4 203.0.113.7:9001 cert=REDACTEDLOOKINGSTRING"
        val SECRET = IllegalStateException(SECRET_TEXT)
    }
}

/** The answer of a teardown whose daemon is gone and whose host is not. */
internal fun releaseFailedStopResponse(
    generation: Long = 0L,
    cause: Throwable? = null,
): TorStopResponse {
    val observation = TorStopObservation(
        generation = generation,
        stop = StopOutcome.Confirmed(generation),
        release = ReleaseObservation.Failed(cause),
    )
    return TorStopResponse(
        TorStopAttempt(generation, kotlinx.coroutines.CompletableDeferred(observation)) { observation },
        observation.toResult(),
    )
}
