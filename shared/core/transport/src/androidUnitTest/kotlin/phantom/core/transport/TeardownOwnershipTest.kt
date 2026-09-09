// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
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
import kotlin.test.assertTrue

/**
 * R-3. Who a deferred teardown belongs to, and how many of them there are.
 *
 * Giving the attempts a scope that outlives the wait fixed the budget, and
 * introduced two ownership questions with it. Both are about the same thing:
 * work that is issued now and runs later must carry its obligation with it.
 *
 *  1. A worker that runs late must FINISH the attempt it was given. If the
 *     claim is made inside the worker instead, it binds to whatever is live
 *     when the worker finally runs — and by then this generation may have
 *     been stopped by someone else and a successor started, so the late
 *     worker would stop the successor.
 *  2. A budget elapsing ends a WAIT, not an attempt. If each expired wait
 *     issues another close, the count of held attempts grows with the number
 *     of calls; a mutex inside the real subsystem serialises them but does
 *     nothing about the queue of coroutines waiting to run.
 *
 * Real threads throughout: both properties are about ordering between a
 * caller and a worker, which virtual time cannot express.
 */
class TeardownOwnershipTest {

    // ── 1. a late worker finishes its own attempt and picks no other ─────

    @Test
    fun a_worker_that_runs_late_does_not_stop_the_generation_that_replaced_it() {
        val tor = GenerationTrackingTor()
        val xray = QuietXray()
        // A teardown scope with ONE thread, which the test then occupies. Any
        // part of the teardown that is merely dispatched cannot run until the
        // thread is handed back -- which is how "the worker runs late" is
        // arranged here rather than hoped for.
        val executor = Executors.newSingleThreadExecutor()
        val occupied = CountDownLatch(1)
        val workers = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        try {
            runBlocking { tor.start(BridgeProfile.Obfs4Only) }
            assertEquals(1L, tor.liveGeneration)
            executor.execute { occupied.await() }

            // The wait elapses with the worker thread still occupied.
            val first = runBlocking { manager(tor, xray, workers).release() }
            assertEquals(
                "tor",
                (first.torFailure as? SubsystemStopNotConfirmed)?.subsystem,
                "the wait should have elapsed; got ${first.torFailure}",
            )
            // The registration happened anyway, because it does not wait for a
            // dispatcher: it begins on the calling thread.
            assertEquals(
                listOf(1L),
                tor.registrations.toList(),
                "the registration was left to a dispatcher instead of binding here",
            )

            // Generation 1 is finished by some other route and generation 2 starts.
            runBlocking { tor.settleAndStart() }
            assertEquals(2L, tor.liveGeneration)

            // Only now may the deferred half run at all.
            occupied.countDown()
            tor.park.countDown()
            assertTrue(
                tor.awaitFinished(10, TimeUnit.SECONDS),
                "the late worker never finished its own attempt",
            )

            assertEquals(
                listOf(1L),
                tor.registrations.toList(),
                "a late worker registered a stop against a generation it was " +
                    "never issued for",
            )
            assertEquals(
                listOf(1L),
                tor.awaited.toList(),
                "the late worker waited on an attempt that was not its own",
            )
            assertEquals(2L, tor.liveGeneration, "generation 2 was torn down by a stale worker")
        } finally {
            occupied.countDown()
            tor.park.countDown()
            workers.cancel()
            executor.shutdownNow()
        }
    }

    /**
     * R-4 P1. A request to stop a NEW generation must not be answered by an
     * attempt that belongs to the old one.
     *
     * Joining on "the worker is still running" alone is not enough: an
     * attempt for A can still be running long after A has finished and B has
     * started. Joining it would hand B's caller A's `Free` -- a clean
     * teardown reported for a daemon that was never asked to stop.
     */
    @Test
    fun a_request_for_a_new_generation_does_not_join_the_old_ones_attempt() {
        val tor = GenerationTrackingTor()
        val xray = QuietXray()
        try {
            runBlocking { tor.start(BridgeProfile.Obfs4Only) }
            val manager = manager(tor, xray)

            // Generation 1's teardown is registered and its wait is parked.
            runBlocking { manager.release() }
            assertTrue(
                tor.enteredAwait.await(10, TimeUnit.SECONDS),
                "the deferred half never began",
            )

            // Generation 1 finishes by another route and generation 2 starts,
            // while generation 1's attempt is still running.
            runBlocking { tor.settleAndStart() }
            val outcome = runBlocking { manager.release() }

            assertEquals(
                listOf(1L, 2L),
                tor.registrations.toList(),
                "generation 2 was never asked to stop: its request was answered " +
                    "by generation 1's attempt",
            )
            assertEquals(
                2L,
                outcome.torResult?.generation,
                "the result reported for generation 2 belongs to $outcome",
            )
        } finally {
            tor.park.countDown()
        }
    }

    /**
     * R-4 P1. The same instance, restarted.
     *
     * `XrayService` has no generation, and production keeps ONE instance and
     * starts a new resource through it. So capturing the instance binds
     * nothing: a claim issued for the old resource would close the new one
     * just as readily. The rule is that nothing may be started on that
     * subsystem while a claim can still reach it.
     */
    @Test
    fun nothing_is_started_on_xray_while_a_teardown_claim_can_still_reach_it() {
        val park = CountDownLatch(1)
        val xray = RestartableXray(park)
        val tor = QuietTor()
        try {
            // Direct must fail so the walk actually reaches Reality: a probe
            // that lets Direct through would never start xray at all, and
            // this case would pass without exercising anything.
            val manager = manager(FailingTor(), xray, probe = realityOnly())

            // A teardown claim that has not returned.
            runBlocking { manager.release() }
            assertEquals(1, xray.closes)
            assertTrue(xray.stopEntered.await(10, TimeUnit.SECONDS))

            // A walk now reaches Reality and would start the SAME instance.
            val started = runCatching { runBlocking { manager.connect() } }

            assertEquals(
                0,
                xray.starts,
                "a new resource was started under a claim that can still close " +
                    "it; connect returned $started",
            )
        } finally {
            park.countDown()
        }
    }

    /**
     * The positive control for the gate above: once the claim has finished,
     * the subsystem starts normally. Without this, an Xray that could never
     * be started would satisfy the case above.
     */
    @Test
    fun the_subsystem_starts_once_its_teardown_claim_has_finished() {
        val park = CountDownLatch(0)
        val xray = RestartableXray(park)
        val tor = QuietTor()
        val manager = manager(FailingTor(), xray, probe = realityOnly())

        runBlocking { manager.release() }
        assertTrue(xray.awaitClosed(10, TimeUnit.SECONDS))
        runCatching { runBlocking { manager.connect() } }

        assertTrue(xray.starts > 0, "the subsystem never started again")
    }

    /**
     * R-5 P2. A registration delayed on its way to the manager must serve
     * its own waiters WITHOUT displacing a newer record.
     *
     * The publication was unconditional, so a late arrival could overwrite
     * the record of a generation that is already running an attempt. The
     * next request for that generation would then find nothing and start a
     * second wait -- not a second native teardown, the owner stays
     * idempotent, but the guarantee of one waiting worker is gone.
     */
    @Test
    fun a_late_registration_does_not_displace_a_newer_record() {
        // Generation 2 holds its wait open, so the record under test is still
        // there when the late arrival tries to displace it.
        val tor = GenerationTrackingTor().apply {
            holdFirstRegistration = true
            parked = setOf(1L, 2L)
        }
        val xray = QuietXray()
        val manager = manager(tor, xray)
        val late = Thread { runCatching { runBlocking { manager.release() } } }
        try {
            runBlocking { tor.start(BridgeProfile.Obfs4Only) }

            // Generation 1 registers and is held before it reaches the manager.
            late.start()
            assertTrue(
                tor.awaitRegistered(1L, 10, TimeUnit.SECONDS),
                "generation 1 never registered",
            )

            // Generation 2 arrives, registers and records its attempt.
            runBlocking { tor.settleAndStart() }
            runBlocking { manager.release() }
            assertEquals(listOf(1L, 2L), tor.registrations.toList())

            // The delayed one now completes its journey.
            tor.registrationGate.countDown()
            late.join(TimeUnit.SECONDS.toMillis(10))
            assertTrue(!late.isAlive, "the delayed request never finished")

            // Generation 2 asks again. Its attempt is still there, so it is
            // joined -- no third call into the service.
            runBlocking { manager.release() }
            // The property is ONE waiting worker, not one call: registering
            // again for a generation that already has a stop is idempotent by
            // contract and starts nothing.
            assertEquals(
                1,
                tor.awaitEntries.toList().count { it == 2L },
                "the newer record was displaced, so a second wait was started " +
                    "for a generation that already had one: " +
                    "${tor.awaitEntries.toList()}",
            )
        } finally {
            tor.registrationGate.countDown()
            tor.park.countDown()
            late.join(TimeUnit.SECONDS.toMillis(10))
        }
    }

    /**
     * R-5 P1. A start admitted while no claim existed must still not be
     * closed by a claim issued a moment later.
     *
     * Checking and then starting is two steps, and a claim arriving between
     * them is admitted by neither. So the two take one gate: whichever gets
     * there first excludes the other, and their library calls can never
     * overlap or run in the wrong order.
     *
     * The fixture reports an overlap directly, which is what "no window"
     * means here -- not merely that the outcome happened to be right.
     */
    @Test
    fun a_start_and_a_teardown_never_overlap_on_the_xray_subsystem() {
        val xray = OrderTrackingXray()
        val manager = manager(FailingTor(), xray, probe = realityOnly())
        try {
            // A start is in flight and holding the gate.
            val walking = Thread { runCatching { runBlocking { manager.connect() } } }
            walking.start()
            assertTrue(
                xray.startEntered.await(10, TimeUnit.SECONDS),
                "the walk never reached the xray start",
            )

            // A teardown is claimed in the middle of it.
            val tearing = Thread { runCatching { runBlocking { manager.release() } } }
            tearing.start()
            Thread.sleep(CLAIM_WINDOW_MS)

            xray.releaseStart()
            walking.join(TimeUnit.SECONDS.toMillis(20))
            tearing.join(TimeUnit.SECONDS.toMillis(20))

            assertTrue(
                xray.overlaps == 0,
                "a start and a teardown ran at the same time on one subsystem",
            )
            assertTrue(
                xray.order.toList() == listOf("start", "stop"),
                "the teardown did not follow the start it arrived under: " +
                    "${xray.order.toList()}",
            )
        } finally {
            xray.releaseStart()
        }
    }

    /**
     * The reverse order: a claim first, then a start. The start must not
     * run at all while the claim can still reach it.
     */
    @Test
    fun a_start_arriving_after_a_claim_does_not_run_before_it() {
        val xray = OrderTrackingXray(parkStop = true)
        val manager = manager(FailingTor(), xray, probe = realityOnly())
        try {
            runBlocking { manager.release() }
            assertTrue(xray.stopEntered.await(10, TimeUnit.SECONDS))

            runCatching { runBlocking { manager.connect() } }

            assertTrue(
                xray.order.toList() == listOf("stop"),
                "a start ran under a claim that had not finished: ${xray.order.toList()}",
            )
            assertTrue(xray.overlaps == 0)
        } finally {
            xray.releaseStop()
        }
    }

    /**
     * R-6 P1. A refused start must not cost anyone the gate.
     *
     * Waiting for the gate with a timeout wrapped around the acquisition can
     * take it and then report failure: the caller returns believing it owns
     * nothing, and the gate is held for ever. So the acquisition does not
     * suspend -- there is no moment between holding it and being protected
     * by the block that releases it.
     *
     * What that buys is checked from the outside: after a refusal, the next
     * legitimate operation still gets through.
     */
    @Test
    fun a_refused_start_does_not_lose_the_gate() {
        val xray = OrderTrackingXray(parkStop = true)
        val manager = manager(FailingTor(), xray, probe = realityOnly())
        try {
            runBlocking { manager.release() }
            assertTrue(xray.stopEntered.await(10, TimeUnit.SECONDS))
            runCatching { runBlocking { manager.connect() } }
            assertEquals(0, xray.starts, "the start was admitted under a claim")

            xray.releaseStop()
            assertTrue(
                xray.awaitStopReturned(10, TimeUnit.SECONDS),
                "the teardown never returned",
            )

            runCatching { runBlocking { manager.connect() } }
            assertTrue(
                xray.starts > 0,
                "the gate was never handed back: a refusal lost it",
            )
        } finally {
            xray.releaseStop()
        }
    }

    /**
     * R-6 P1. A teardown that FAILS hands the gate back too. The release
     * sits in a `finally` opened immediately after the gate is taken, so an
     * exception on the way out is no different from a return.
     */
    @Test
    fun a_teardown_that_throws_still_hands_the_gate_back() {
        val xray = OrderTrackingXray(failStop = true)
        val manager = manager(FailingTor(), xray, probe = realityOnly())

        val outcome = runBlocking { manager.release() }
        assertTrue(outcome.xrayFailure != null, "the failure was swallowed")

        runCatching { runBlocking { manager.connect() } }
        assertTrue(xray.starts > 0, "a failed teardown kept the gate")
    }

    /**
     * R-6 P1. A new request must not be answered by a teardown whose
     * resource operation has already ended.
     *
     * The gate is handed back when the close returns, and a start can be
     * admitted through it at once, while the coroutine that did the close is
     * still finishing. Joining on "the coroutine is still active" would hand
     * a new caller that old success and report a clean teardown for a
     * subsystem started since. The record is withdrawn when the OPERATION
     * ends, not when the coroutine does.
     */
    @Test
    fun a_request_after_a_close_is_not_answered_by_it() {
        val xray = OrderTrackingXray()
        val manager = manager(FailingTor(), xray, probe = realityOnly())

        runBlocking { manager.release() }
        assertTrue(xray.awaitStopReturned(10, TimeUnit.SECONDS))
        assertEquals(1, xray.closes)

        runCatching { runBlocking { manager.connect() } }
        assertTrue(xray.starts > 0, "nothing was started, so nothing is at risk")

        runBlocking { manager.release() }
        assertEquals(
            2,
            xray.closes,
            "the new request was answered by the previous close, so what was " +
                "started since was never stopped",
        )
    }

    /**
     * R-7 P2. A start admitted must be admitted WITHIN its budget.
     *
     * Reading the deadline only before each attempt lets a waiter that was
     * slow to resume find the gate free and start long after its budget ran
     * out: the decision is made on a check from a different second. Here the
     * waiter is prevented from resuming until the budget has passed, and the
     * gate is freed meanwhile.
     */
    @Test
    fun a_start_is_not_admitted_after_its_budget_has_run_out() {
        val xray = OrderTrackingXray(parkStop = true)
        val executor = Executors.newSingleThreadExecutor()
        val walkScope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        val busy = CountDownLatch(1)
        val manager = manager(FailingTor(), xray, probe = realityOnly())
        try {
            // The gate is held by a parked teardown.
            runBlocking { manager.release() }
            assertTrue(xray.stopEntered.await(10, TimeUnit.SECONDS))

            // A walk reaches the admission and starts waiting for the gate.
            val walk = walkScope.launch { runCatching { manager.connect() } }
            Thread.sleep(SETTLE_MS)

            // Its only thread is taken, so it cannot resume while the budget
            // runs out and the gate becomes free.
            executor.execute { busy.await(PARK_HOLD_MS, TimeUnit.MILLISECONDS) }
            Thread.sleep(TransportManager.SUBSYSTEM_STOP_TIMEOUT_MS + SETTLE_MS)
            xray.releaseStop()
            assertTrue(xray.awaitStopReturned(10, TimeUnit.SECONDS))
            busy.countDown()

            runBlocking { walk.join() }
            assertEquals(
                0,
                xray.starts,
                "a start was admitted after its budget had run out",
            )

            // And the gate it briefly took is back.
            runCatching { runBlocking { manager.connect() } }
            assertTrue(xray.starts > 0, "the refused waiter kept the gate")
        } finally {
            busy.countDown()
            xray.releaseStop()
            walkScope.cancel()
            executor.shutdownNow()
        }
    }

    /**
     * R-7. A teardown worker CANCELLED while it owns the gate must still
     * hand it back.
     *
     * A close that throws does not test this: `runCatching` absorbs it and
     * the worker returns normally. Only cancellation unwinds through the
     * `finally`, and only cancellation can strand the gate.
     */
    @Test
    fun a_worker_cancelled_while_holding_the_gate_hands_it_back() {
        val xray = OrderTrackingXray()
        val executor = Executors.newSingleThreadExecutor()
        val workers = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        val busy = CountDownLatch(1)
        val manager = manager(FailingTor(), xray, workers = workers, probe = realityOnly())
        try {
            // The worker's only thread is taken, so the claim takes the gate
            // undispatched and then parks at its `yield`.
            executor.execute { busy.await(PARK_HOLD_MS, TimeUnit.MILLISECONDS) }
            runBlocking { manager.release() }
            assertEquals(0, xray.closes, "the close ran, so nothing was parked")

            // Cancelled while it owns the gate and before it can close.
            workers.cancel()
            busy.countDown()
            Thread.sleep(SETTLE_MS)

            runCatching { runBlocking { manager.connect() } }
            assertTrue(
                xray.starts > 0,
                "a cancelled worker kept the gate: nothing can be started again",
            )
        } finally {
            busy.countDown()
            workers.cancel()
            executor.shutdownNow()
        }
    }

    /**
     * R-7. The window between handing the gate back and completing.
     *
     * From outside, a close returning and its worker finishing look
     * simultaneous, so a sequential test cannot separate them. The manager
     * carries a parking seam for exactly this gap -- the same shape as the
     * lifecycle owner's. With the worker held there, a new request must
     * perform a NEW close rather than be answered by the finished one.
     */
    @Test
    fun a_request_in_the_completion_window_is_not_answered_by_the_old_close() {
        val released = CountDownLatch(1)
        val reached = CountDownLatch(1)
        val xray = OrderTrackingXray()
        val manager = manager(
            FailingTor(),
            xray,
            probe = realityOnly(),
            afterXrayStopReleased = {
                reached.countDown()
                released.await(PARK_HOLD_MS, TimeUnit.MILLISECONDS)
            },
        )
        try {
            runBlocking { manager.release() }
            assertTrue(
                reached.await(10, TimeUnit.SECONDS),
                "the worker never reached the completion window",
            )
            assertEquals(1, xray.closes)

            // The gate is already back, so a resource can be started under
            // a worker that has not finished.
            //
            // The start must NOT park here. Its first version left the
            // fixture's start gate shut, so `connect` sat in `start()` for
            // twelve seconds -- past the parked worker's own hold -- and by
            // the second request the window this case is named for had
            // closed. The mutation that removes the withdrawal then changed
            // nothing, which is how that was found.
            xray.releaseStart()
            runCatching { runBlocking { manager.connect() } }
            assertTrue(xray.starts > 0, "nothing was started, so nothing is at risk")

            runBlocking { manager.release() }
            assertEquals(
                2,
                xray.closes,
                "the request was answered by a close that had already finished, " +
                    "so what was started since was never stopped",
            )
        } finally {
            released.countDown()
        }
    }

    // ── 3. a provider that throws is its own subsystem's failure ────────

    /**
     * R-4 P1. Resolving a subsystem is part of THAT subsystem's attempt.
     *
     * The resolution had drifted outside any handler, so a provider that
     * threw ended `release()` before the other subsystem was reached -- the
     * rule that both get an attempt, broken again from a new direction.
     */
    @Test
    fun a_throwing_xray_provider_does_not_cost_tor_its_attempt() {
        val tor = QuietTor()
        val boom = IllegalStateException("no xray today")
        val manager = TransportManager(
            torServiceProvider = { tor },
            xrayServiceProvider = { throw boom },
            preferences = InMemoryTransportPreferences(PrivacyMode.Standard),
            probe = TransportProbe { _, _ -> true },
            nowMs = { 0L },
            policy = PrivacyModeCoordinator(
                InMemoryTransportPreferences(PrivacyMode.Standard).privacyMode,
            ),
        )

        val outcome = runBlocking { manager.release() }

        assertEquals(boom, outcome.xrayFailure, "the provider failure was lost")
        assertTrue(tor.stopped, "tor was never asked to stop")
        assertTrue(!outcome.clean)
    }

    @Test
    fun a_throwing_tor_provider_does_not_lose_the_xray_result() {
        val xray = RestartableXray(CountDownLatch(0))
        val boom = IllegalStateException("no tor today")
        val manager = TransportManager(
            torServiceProvider = { throw boom },
            xrayServiceProvider = { xray },
            preferences = InMemoryTransportPreferences(PrivacyMode.Standard),
            probe = TransportProbe { _, _ -> true },
            nowMs = { 0L },
            policy = PrivacyModeCoordinator(
                InMemoryTransportPreferences(PrivacyMode.Standard).privacyMode,
            ),
        )

        val outcome = runBlocking { manager.release() }

        assertEquals(boom, outcome.torFailure, "the provider failure was lost")
        assertTrue(xray.awaitClosed(10, TimeUnit.SECONDS), "xray was never asked to stop")
        assertEquals(null, outcome.xrayFailure, "xray reported a failure it did not have")
    }

    /**
     * A second teardown request while a tor attempt is still in flight must
     * not REGISTER a second stop.
     *
     * Registration is the act that binds an obligation to a generation. Doing
     * it again while the first attempt is unfinished binds a second one to
     * whatever is live at that moment -- which is the same defect as claiming
     * inside a late worker, arriving from the other direction.
     *
     * This case exists because a control found the gap: removing the join and
     * leaving the rest intact changed nothing that any test could see.
     */
    @Test
    fun a_second_request_while_an_attempt_is_in_flight_registers_no_second_stop() {
        val tor = GenerationTrackingTor()
        val xray = QuietXray()
        try {
            runBlocking { tor.start(BridgeProfile.Obfs4Only) }
            val manager = manager(tor, xray)

            runBlocking { manager.release() }
            assertTrue(
                tor.enteredAwait.await(10, TimeUnit.SECONDS),
                "the deferred half never began",
            )
            runBlocking { manager.release() }

            assertEquals(
                listOf(1L),
                tor.registrations.toList(),
                "a second stop was registered over an attempt still in flight",
            )
        } finally {
            tor.park.countDown()
        }
    }

    // ── 2. an expired wait joins the attempt; it does not add one ────────

    @Test
    fun repeated_waits_join_one_attempt_rather_than_issuing_more() {
        val park = CountDownLatch(1)
        val xray = ParkedXray(park)
        val tor = QuietTor()
        try {
            val manager = manager(tor, xray)

            repeat(3) { runBlocking { manager.release() } }

            assertEquals(
                1,
                xray.closes,
                "each expired wait queued another close behind a subsystem that " +
                    "had not finished the first",
            )
        } finally {
            park.countDown()
        }
    }

    /**
     * A caller that JOINS an attempt still in flight receives that attempt's
     * result when it finishes.
     *
     * Note what is deliberately NOT asserted: that a caller arriving after
     * the attempt has COMPLETED sees no new close. A completed teardown is
     * finished business and the next `release()` is a new request -- closing
     * again is right, because the subsystem may have been started since.
     * Reusing a finished attempt would report a stop that never happened.
     *
     * The second caller is given time to claim before the park is released.
     * If it had not claimed in time it would issue its own close, and the
     * count below would say so -- a mis-timing fails this test, it cannot
     * pass it.
     */
    @Test
    fun a_waiter_that_joins_an_attempt_in_flight_receives_its_result() {
        val park = CountDownLatch(1)
        val xray = ParkedXray(park)
        val tor = QuietTor()
        val manager = manager(tor, xray)
        try {
            // One elapsed wait leaves the close in flight and owned.
            val first = runBlocking { manager.release() }
            assertTrue(first.xrayFailure is SubsystemStopNotConfirmed)
            assertEquals(1, xray.closes)

            // A second caller joins that same attempt rather than closing again.
            var joined: TransportManager.ReleaseOutcome? = null
            val waiter = Thread { joined = runBlocking { manager.release() } }
            waiter.start()
            Thread.sleep(CLAIM_WINDOW_MS)
            assertEquals(
                1,
                xray.closes,
                "a second close was issued over one that had not returned",
            )

            // The library returns; the joined waiter gets that result.
            park.countDown()
            waiter.join(TimeUnit.SECONDS.toMillis(10))
            assertTrue(!waiter.isAlive, "the joined waiter never returned")
            assertTrue(
                joined?.clean == true,
                "the attempt finished but its result never reached the waiter: $joined",
            )
            assertEquals(1, xray.closes, "the joined waiter closed a second time")
        } finally {
            park.countDown()
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private fun manager(
        tor: TorService,
        xray: XrayService,
        workers: CoroutineScope = teardownScope(),
        probe: TransportProbe = TransportProbe { _, _ -> true },
        afterXrayStopReleased: suspend () -> Unit = {},
    ) = TransportManager(
        torServiceProvider = { tor },
        xrayServiceProvider = { xray },
        preferences = InMemoryTransportPreferences(PrivacyMode.Standard),
        probe = probe,
        nowMs = { 0L },
        policy = PrivacyModeCoordinator(
            InMemoryTransportPreferences(PrivacyMode.Standard).privacyMode,
        ),
        teardownWorkers = workers,
        afterXrayStopReleased = afterXrayStopReleased,
    )

    /**
     * Records which generation each half of a teardown addressed, and parks
     * the deferred half so a test can arrange what happens meanwhile.
     */
    private class GenerationTrackingTor : TorService {
        private val flow = MutableStateFlow<TorState>(TorState.Off)
        override val state: StateFlow<TorState> = flow.asStateFlow()

        val registrations = SafeList<Long>()
        /** Every call, not just every distinct generation. */
        val stopCalls = SafeList<Long>()
        /** Every entry into the WAIT, which is the thing that must be single. */
        val awaitEntries = SafeList<Long>()
        /** Which generations hold their wait open. */
        @Volatile
        var parked: Set<Long> = setOf(1L)
        /** Holds a registration AFTER it is recorded and before it returns. */
        val registrationGate = CountDownLatch(1)
        @Volatile
        var holdFirstRegistration = false
        val awaited = SafeList<Long>()
        val enteredAwait = CountDownLatch(1)
        val park = CountDownLatch(1)
        private val finished = CountDownLatch(1)

        @Volatile
        var liveGeneration = 0L
            private set

        override suspend fun start(bridgeProfile: BridgeProfile) {
            liveGeneration += 1
            flow.value = TorState.Ready(socksPort = 9050)
        }

        /** Generation 1 finishes by some other route, and a successor starts. */
        suspend fun settleAndStart() {
            flow.value = TorState.Off
            start(BridgeProfile.Obfs4Only)
        }

        override suspend fun stop(budget: TorBudget): TorStopResponse {
            // Registration binds to whatever is live at THIS moment, and is
            // idempotent for a generation that already has one -- the owner
            // neither starts a second teardown nor queues anything.
            val generation = liveGeneration
            stopCalls.add(generation)
            if (!registrations.contains(generation)) registrations.add(generation)
            // The seam: this registration is recorded but has not yet reached
            // the manager, which is where a newer one can overtake it.
            if (holdFirstRegistration && generation == 1L) {
                registrationGate.await(10, TimeUnit.SECONDS)
            }
            val observation = TorStopObservation(
                generation = generation,
                stop = StopOutcome.Confirmed(generation),
                release = ReleaseObservation.NotStarted,
            )
            return TorStopResponse(
                TorStopAttempt(generation, CompletableDeferredOf(observation)) { observation },
                observation.toResult(),
            )
        }

        override suspend fun awaitRelease(
            attempt: TorStopAttempt,
            budget: TorBudget,
        ): TorStopResult {
            awaitEntries.add(attempt.generation)
            enteredAwait.countDown()
            // Only the generations a case names hold their wait open. One that
            // needs a later generation to ANSWER must not be waiting on the
            // fixture; one that needs a record to stay current must be.
            if (attempt.generation in parked) {
                park.await(PARK_HOLD_MS, TimeUnit.MILLISECONDS)
            }
            awaited.add(attempt.generation)
            finished.countDown()
            return TorStopResult.Free(attempt.generation, quiesceFailure = null)
        }

        fun awaitFinished(timeout: Long, unit: TimeUnit): Boolean = finished.await(timeout, unit)

        fun awaitRegistered(generation: Long, timeout: Long, unit: TimeUnit): Boolean {
            val deadline = System.nanoTime() + unit.toNanos(timeout)
            while (System.nanoTime() < deadline) {
                if (registrations.contains(generation)) return true
                Thread.sleep(10)
            }
            return false
        }
    }

    private class QuietTor : TorService {
        private val flow = MutableStateFlow<TorState>(TorState.Off)
        override val state: StateFlow<TorState> = flow.asStateFlow()

        @Volatile
        var stopped = false
            private set

        override suspend fun start(bridgeProfile: BridgeProfile) = Unit
        override suspend fun stop(budget: TorBudget): TorStopResponse {
            stopped = true
            return torStopNothingToStop()
        }
        override suspend fun awaitRelease(
            attempt: TorStopAttempt,
            budget: TorBudget,
        ): TorStopResult = torStopNothingToStop().result
    }

    /** A close that blocks until the test lets it return, and counts itself. */
    private class ParkedXray(private val park: CountDownLatch) : XrayService {
        private val flow = MutableStateFlow<XrayState>(XrayState.Ready(socksPort = 10808))
        private val closed = CountDownLatch(1)
        override val state: StateFlow<XrayState> = flow.asStateFlow()

        @Volatile
        var closes = 0
            private set

        override suspend fun start() = Unit

        override suspend fun stop() {
            closes += 1
            park.await()
            flow.value = XrayState.Off
            closed.countDown()
        }

        fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean = closed.await(timeout, unit)
    }

    /**
     * One instance, stopped and started again -- the production shape. It
     * counts both, so a start under an outstanding claim is visible.
     */
    private class RestartableXray(private val park: CountDownLatch) : XrayService {
        private val flow = MutableStateFlow<XrayState>(XrayState.Ready(socksPort = 10808))
        private val closed = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        override val state: StateFlow<XrayState> = flow.asStateFlow()

        @Volatile
        var closes = 0
            private set

        @Volatile
        var starts = 0
            private set

        override suspend fun start() {
            starts += 1
            flow.value = XrayState.Ready(socksPort = 10808)
        }

        override suspend fun stop() {
            closes += 1
            stopEntered.countDown()
            park.await()
            flow.value = XrayState.Off
            closed.countDown()
        }

        fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean = closed.await(timeout, unit)
    }

    /**
     * Reports a failed daemon at once. These cases walk the whole chain on
     * the real clock, and a tor leg that never reached a terminal state
     * would spend the rotation budgets -- minutes of them.
     */
    private class FailingTor : TorService {
        private val flow = MutableStateFlow<TorState>(TorState.Failed("no tor here"))
        override val state: StateFlow<TorState> = flow.asStateFlow()
        override suspend fun start(bridgeProfile: BridgeProfile) {
            flow.value = TorState.Failed("no tor here")
        }
        override suspend fun stop(budget: TorBudget): TorStopResponse = torStopNothingToStop()
        override suspend fun awaitRelease(
            attempt: TorStopAttempt,
            budget: TorBudget,
        ): TorStopResult = torStopNothingToStop().result
    }

    /**
     * Records the order of its library calls and whether any two ever ran at
     * the same time. One instance, started and stopped -- the production
     * shape.
     */
    private class OrderTrackingXray(
        private val parkStop: Boolean = false,
        private val failStop: Boolean = false,
    ) : XrayService {
        private val flow = MutableStateFlow<XrayState>(XrayState.Ready(socksPort = 10808))
        private val startGate = CountDownLatch(1)
        private val stopGate = CountDownLatch(1)
        private val inFlight = java.util.concurrent.atomic.AtomicInteger()
        override val state: StateFlow<XrayState> = flow.asStateFlow()

        val order = SafeList<String>()
        val startEntered = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        private val stopReturned = CountDownLatch(1)

        @Volatile
        var closes = 0
            private set

        @Volatile
        var starts = 0
            private set

        @Volatile
        var overlaps = 0
            private set

        override suspend fun start() {
            starts += 1
            enter("start")
            startEntered.countDown()
            startGate.await(PARK_HOLD_MS, TimeUnit.MILLISECONDS)
            flow.value = XrayState.Ready(socksPort = 10808)
            leave()
        }

        override suspend fun stop() {
            closes += 1
            enter("stop")
            stopEntered.countDown()
            try {
                if (parkStop) stopGate.await(PARK_HOLD_MS, TimeUnit.MILLISECONDS)
                if (failStop) throw IllegalStateException("close refused")
                flow.value = XrayState.Off
            } finally {
                leave()
                stopReturned.countDown()
            }
        }

        fun awaitStopReturned(timeout: Long, unit: TimeUnit): Boolean =
            stopReturned.await(timeout, unit)

        fun releaseStart() = startGate.countDown()
        fun releaseStop() = stopGate.countDown()

        private fun enter(name: String) {
            order.add(name)
            if (inFlight.incrementAndGet() > 1) overlaps += 1
        }

        private fun leave() {
            inFlight.decrementAndGet()
        }
    }

    private class QuietXray : XrayService {
        private val flow = MutableStateFlow<XrayState>(XrayState.Off)
        override val state: StateFlow<XrayState> = flow.asStateFlow()
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
    }

    private companion object {
        /**
         * Long enough for a second caller to claim, and a small fraction of
         * the budget it is claiming inside.
         */
        const val CLAIM_WINDOW_MS = 300L

        /**
         * How long a parked fixture holds. Comfortably past the budgets it
         * has to outlive, and short enough that a forgotten release does
         * not become a hang.
         */
        const val PARK_HOLD_MS = 12_000L

        /** Long enough for a launched coroutine to reach its next suspension. */
        const val SETTLE_MS = 400L
    }
}

/** A completed [kotlinx.coroutines.Deferred] holding [value]. */
private fun <T> CompletableDeferredOf(value: T) =
    kotlinx.coroutines.CompletableDeferred(value)

/**
 * A teardown scope with a pool of its own.
 *
 * The fakes here block their thread deliberately -- a blocking library
 * close is exactly what several of these cases are about. On a shared
 * fixed-size dispatcher a few of those exhaust it and everything scheduled
 * there stops, which is how this suite first hung.
 */
private fun teardownScope() = CoroutineScope(
    SupervisorJob() + Executors.newCachedThreadPool().asCoroutineDispatcher(),
)

/** Only Reality answers, so a walk has to get that far. */
private fun realityOnly() = TransportProbe { kind, _ -> kind == TransportKind.Reality }
