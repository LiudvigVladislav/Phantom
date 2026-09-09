// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R-N1.17 CORE - three invariants the round established and no fixture
 * observed.
 *
 * Found by the campaign rather than by review: each of these had a
 * mutation that no test failed on, which means the guard could have been
 * deleted and every suite would still have been green. They are gathered
 * here because they belong to three different components and share only
 * their provenance.
 */
class CampaignGapTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun scope(): CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes.add(it) }

    /** Thread-safe: these fixtures read while other threads write. */
    private fun steps() = SafeSteps()

    /** Wait for a condition, with a deadline. A fixture must never hang. */
    private suspend fun await(
        what: String,
        budgetMs: Long = 5_000,
        cond: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline && !cond()) delay(10)
        assertTrue(cond(), "timed out waiting for $what")
    }

    /**
     * Drive the lease into the fail-closed BLOCKED state, then let it
     * recover. A merely owned lease is not blocked, and a signal on one
     * never reaches the start path at all.
     */
    private suspend fun blockedLease(ownership: ConnectOwnership): () -> Unit {
        val stops = AtomicBoolean(false)
        assertNotNull(
            ownership.claim("old_walk", ConnectWalkHandle { stops.get() }),
            "precondition: the walk owns the lease",
        )
        val outcome = ownership.handOver("wedged_walk")
        assertTrue(outcome is Handover.TimedOut, "precondition: fail-closed, was $outcome")
        return { stops.set(true) }
    }

    // ----------------------------------------------------------------
    // A retry may not run beside a sweep
    // ----------------------------------------------------------------

    @Test
    fun aRetryRefusesWhileASweepIsStillRunning() = runBlocking {
        // `retryPending` takes everything out of `unclosed` and closes it.
        // A sweep already in flight has ALSO taken its permits out of that
        // list, so a retry starting beside it finds an empty list, reports
        // that nothing is open, and publishes Applied over sockets that
        // are still being torn down.
        //
        // The permit path has its own fixture for the same window; this is
        // the retry path, which had none.
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val closing = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()

        val permit = policy.acquirePermit(TransportKind.Direct, 0L) {
            entered.complete(Unit)
            withContext(NonCancellable) { closing.await() }
        }
        assertNotNull(permit)
        permit.useToOpen { }

        // A switch starts a sweep, which parks inside the socket close.
        val sweep = scope().launch { policy.requestMode(PrivacyMode.Ghost) }
        entered.await()

        val outcome = policy.retryPending()

        assertFalse(
            outcome.isComplete,
            "a retry beside a live sweep must not report the policy complete",
        )
        assertEquals(
            1,
            outcome.stillOpen.size,
            "and it must report the sweep's socket as still open, not an empty list",
        )
        assertEquals(
            PrivacyModeStatus.Blocked,
            policy.state.value.status,
            "the mode must not be published while a socket is being torn down",
        )

        closing.complete(Unit)
        sweep.join()
    }

    // ----------------------------------------------------------------
    // A settlement re-checks its epoch after the teardown
    // ----------------------------------------------------------------

    @Test
    fun aSettlementSupersededDuringItsTeardownCompletesNothing() = runBlocking {
        // The teardown suspends - it revokes, disconnects, hands over and
        // releases - and a new request can land while it runs. Completing
        // afterwards would install a mode the user has already moved past,
        // which is the silent downgrade written into the UI.
        //
        // The epoch is checked before the teardown too, and that check has
        // fixtures; this is the re-check after it, which had none.
        val steps = SafeSteps()
        val inTeardown = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)

        val settling = scope().launch {
            val outcome = settleDeferredPrivacySwitch(
                epoch = epoch,
                coordinator = policy,
                raiseClaimFence = { ownership.raiseClaimFence("deferred", epoch) },
                lowerClaimFence = { ownership.lowerClaimFence("deferred", epoch) },
                retryTeardown = {
                    inTeardown.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    TeardownAttempt(walkQuiesced = true, confirmed = true)
                },
                startSuccessor = {
                    steps.add("successor_started")
                    HandoffRecovery.SuccessorOutcome.Started
                },
            )
            steps.add("phase:${outcome.phase}")
        }
        inTeardown.await()

        // The user switches again while the teardown is running.
        val newer = policy.requestMode(PrivacyMode.Private)
        assertTrue(newer > epoch, "precondition: a newer epoch exists")

        release.complete(Unit)
        settling.join()

        assertTrue(
            steps.contains("phase:${SettlementPhase.Superseded}"),
            "a settlement overtaken during its teardown must complete nothing: $steps",
        )
        assertEquals(
            PrivacyMode.Standard,
            policy.state.value.effective,
            "and must not publish the mode its own epoch wanted: $steps",
        )
        assertTrue(
            steps.none { it == "successor_started" },
            "nor start a successor for a switch that has been overtaken: $steps",
        )
    }

    // ----------------------------------------------------------------
    // A start debt that never belonged to a policy epoch
    // ----------------------------------------------------------------

    @Test
    fun anOrdinaryStartDebtIsNotStampedWithAStaleEpoch() = runBlocking {
        // R-N1.17 P1. An ordinary connect that failed to start is not tied
        // to a privacy switch. It used to be stamped with whatever epoch
        // some earlier `arm` had recorded, and the timer then compared
        // that against the live policy epoch and discarded the debt as
        // stale - without ever retrying it.
        //
        // The sequence is deterministic: a debt from epoch 1, a later
        // switch to epoch 2 that starts its own successor and discharges
        // that debt, and then a fresh ordinary start that fails. The new
        // debt inherited 1, and epoch 2 was live, so it was deleted
        // unretried and the app sat with no transport.
        val steps = steps()
        val livePolicyEpoch = AtomicLong(1)
        val startWorks = AtomicBoolean(false)
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { reason ->
                steps.add("start:$reason")
                startWorks.get()
            },
            intervalMs = 60L,
            currentPolicyEpoch = { livePolicyEpoch.get() },
        )

        // A switch at epoch 1 could not start its successor.
        recovery.armStartRetry("successor_start_failed", epoch = 1L)
        // A later switch at epoch 2 starts one successfully.
        livePolicyEpoch.set(2)
        recovery.noteSuccessorStarted(epoch = 2L)
        // Discharging the record does not cancel the timer on the spot -
        // it stands itself down on its next tick, finding nothing owed.
        await("the discharged debt's driver to stand down") { !recovery.isArmed() }
        steps.clear()

        // Now an ORDINARY connect - a nudge on a blocked lease - fails.
        val letItStop = blockedLease(ownership)
        letItStop()
        recovery.onSignal("nudge_alarm")
        assertTrue(steps.any { it.startsWith("start:") }, "precondition: it tried: $steps")

        // That failure is owed, and it must survive: it belongs to no
        // epoch, so no epoch can make it stale.
        assertTrue(recovery.isArmed(), "a failed ordinary start must be owed: $steps")
        startWorks.set(true)
        await("the ordinary start to be retried: $steps") {
            steps.count { it.startsWith("start:") } >= 2
        }
        await("the debt to be discharged once it worked: $steps") { !recovery.isArmed() }
    }

    // ----------------------------------------------------------------
    // A session is only as good as the close it is given
    // ----------------------------------------------------------------

    @Test
    fun aSessionCannotSeePastACloseThatDoesNotStopTheLoop() = runBlocking {
        // Not a defect in TransportSession - a statement of its limit, and
        // the reason the wiring around it is checked at source.
        //
        // The real transport runs its reconnect loop in ITS OWN scope; the
        // connect call merely joins it. So cancelling the session ends the
        // WAIT and nothing else, and a close that does not cancel and join
        // that loop leaves it running. The session has no way to know:
        // it reports a clean stop, `afterCompletion` releases the permit,
        // and a live socket is left in no register at all.
        val outer = scope()
        val loopAlive = CompletableDeferred<Unit>()
        val loopRan = CompletableDeferred<Unit>()
        val independent = outer.launch {
            loopRan.complete(Unit)
            withContext(NonCancellable) { loopAlive.await() }
        }
        loopRan.await()

        var stopped: Boolean? = null
        val owner = launch {
            val session = TransportSession(
                ownerScope = CoroutineScope(currentCoroutineContext()),
                // Exactly the shape of the production connect: wait on a
                // loop that lives somewhere else.
                connectLoop = { independent.join() },
                // A close that does not stop that loop - which is what
                // `disconnect()` was here.
                closeTransport = { },
            )
            session.start()
            stopped = session.stop("privacy_mode_changed")
        }
        owner.join()

        assertEquals(
            true,
            stopped,
            "the session reports what its close told it, and a close that stops " +
                "nothing tells it nothing - which is why the close passed in must be " +
                "the one that cancels and joins the loop",
        )
        assertTrue(independent.isActive, "and the loop really is still running")

        loopAlive.complete(Unit)
        independent.join()
    }

    // ----------------------------------------------------------------
    // A socket that outlives its waiter keeps its permit
    // ----------------------------------------------------------------

    @Test
    fun composedSocketNotConfirmedClosedKeepsItsPermitAndGetsADriver() = runBlocking {
        // R-N1.17 P1, as a COMPOSITION of the core components - not an
        // end-to-end run. The permit hand-back and the arming are invoked
        // here directly; what holds them together in production is the
        // service, and that link is a source tripwire in
        // ConnectRetryWiringTest. The production chain itself is driven by
        // `aSessionStopThatRanOutOfBudgetStrandsThePermitAndASweepFinishesIt`.
        //
        // The transport runs its reconnect loop in its OWN scope; the
        // connect call merely joins it. So cancelling the owner ends the
        // waiter and nothing else, `afterCompletion` runs, and the release
        // that followed used to claim `closed = true` on a socket nobody
        // had closed - dropping it out of every register while it was
        // possibly still up.
        //
        // Now: the release is refused, the permit moves to the set a sweep
        // retries, a driver is armed, and a late successful close is what
        // finally discharges it. Nothing is started, because the recovery
        // is stood down.
        val steps = steps()
        val socketAlive = AtomicBoolean(true)
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)

        val permit = policy.acquirePermit(TransportKind.Direct, 0L) {
            steps.add("close_attempt")
            // The join the shutdown could not confirm.
            if (socketAlive.get()) error("reconnect loop not joined")
            steps.add("socket_closed")
        }
        assertNotNull(permit)
        permit.useToOpen { }

        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { steps.add("bare_start"); true },
            intervalMs = 60L,
            sweepPending = { !policy.retryPending().isComplete },
        )
        // The service is stopping, so nothing may be started from here on.
        recovery.standDown("service_destroyed")

        // The owner is cancelled and the session's afterCompletion runs.
        // The socket is NOT confirmed closed.
        val released = permit.closeAndRelease("owner_cancelled")
        assertFalse(
            released,
            "a permit over a socket nobody confirmed closed must not be released: $steps",
        )
        assertTrue(
            policy.hasUnclosed(),
            "and it must be visible to the next switch and to a sweep: $steps",
        )
        // Not asserted on the snapshot's `stillOpen`: that field records
        // what the last SWITCH found, and no switch has happened here. The
        // live register is what a switch and a sweep consult.

        // The shutdown's own disconnect could not confirm the join either,
        // so it arms a sweep. That is the autonomous driver.
        recovery.arm("shutdown_socket_not_closed")
        assertTrue(recovery.isArmed(), "the obligation must have a driver: $steps")

        await("the sweep to keep trying: $steps") {
            steps.count { it == "close_attempt" } >= 2
        }
        assertTrue(policy.hasUnclosed(), "still owed while the close keeps failing: $steps")

        // The loop finally goes away and a retry closes it.
        socketAlive.set(false)
        await("the late close to discharge the permit: $steps") { !policy.hasUnclosed() }

        assertTrue(steps.contains("socket_closed"), "$steps")
        assertTrue(
            steps.none { it == "bare_start" },
            "the service is stopped, so nothing may be started: $steps",
        )
    }

    @Test
    fun aDirectReleaseOverAnUnconfirmedCloseIsRefused() = runBlocking {
        // `release()` is public API, and its guard is reached only by a
        // caller that has not gone through `closeAndRelease`. Production
        // no longer has such a caller - which is exactly why the contract
        // needs stating here rather than being left to the one call site
        // that happens to exist today.
        //
        // The two guards are deliberately redundant: `closeAndRelease`
        // refuses on a failed close, and `release` refuses on an
        // unconfirmed one. Either alone keeps the socket visible.
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val closes = AtomicInteger()
        val permit = policy.acquirePermit(TransportKind.Direct, 0L) { closes.incrementAndGet() }
        assertNotNull(permit)
        permit.useToOpen { }

        assertFalse(
            permit.release(),
            "a permit whose socket was opened and never confirmed closed must not be " +
                "released on the owner's word",
        )
        assertEquals(0, closes.get(), "and nothing was closed by asking")
        assertTrue(
            policy.hasUnclosed(),
            "it must land where a sweep will retry the close",
        )
    }

    @Test
    fun aConfirmedCloseStillReleasesItsPermit() = runBlocking {
        // The control. Refusing a release must not become refusing every
        // release: an ordinary session end, with the socket really closed,
        // still deregisters.
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val permit = policy.acquirePermit(TransportKind.Direct, 0L) { }
        assertNotNull(permit)
        permit.useToOpen { }
        // A confirmed close, as a revoke performs.
        assertTrue(policy.requestMode(PrivacyMode.Ghost) > 0)
        assertFalse(policy.hasUnclosed(), "a clean close leaves nothing owed")

        val fresh = PrivacyModeCoordinator(PrivacyMode.Standard)
        val unused = fresh.acquirePermit(TransportKind.Direct, 0L) { }
        assertNotNull(unused)
        // Never opened: there is no socket to be unsure about.
        assertTrue(unused.release(), "an unused permit releases normally")
        assertFalse(fresh.hasUnclosed())
    }

    @Test
    fun aSessionStopThatRanOutOfBudgetStrandsThePermitAndASweepFinishesIt() = runBlocking {
        // R-N1.17 P1, through the production chain rather than beside it.
        //
        // The budgets nest: `TransportSession.stop` gives up first, the
        // transport join it calls is allowed longer, and a per-permit
        // revoke attempt longer still. The session's budget therefore
        // always wins - which is fail-closed only if losing it KEEPS the
        // socket visible.
        //
        // An earlier version of this fixture cancelled `closeAndRelease`
        // from outside and never reached `onUnclosed` at all, so the
        // permit stayed in `livePermits` and was only found because the
        // test then performed a switch by hand. That proved nothing about
        // the sequence it claimed. This one drives it: a slow close makes
        // `stop()` return false, the permit's own close callback turns
        // that into a failure, and everything after it is the machinery's
        // own doing.
        val steps = steps()
        val slowClose = AtomicBoolean(true)
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val sessionScope = scope()

        val session = TransportSession(
            ownerScope = sessionScope,
            connectLoop = { steps.add("loop_running"); awaitCancellation() },
            closeTransport = {
                // The join that outlasts the session's budget.
                if (slowClose.get()) delay(600)
                steps.add("transport_closed")
            },
            stopTimeoutMs = 150L,
        )

        // The permit's close is the production one: stop the session, and
        // an unconfirmed stop is an exception, not a quiet success.
        val permit = policy.acquirePermit(TransportKind.Direct, 0L) {
            steps.add("close_attempt")
            if (!session.stop("privacy_mode_changed")) {
                throw SessionNotStoppedException("privacy_mode_changed")
            }
        }
        assertNotNull(permit)
        permit.useToOpen { session.start() }
        await("the loop to be running: $steps") { steps.contains("loop_running") }

        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { steps.add("bare_start"); true },
            intervalMs = 60L,
            sweepPending = { !policy.retryPending().isComplete },
        )
        // The service is stopping: nothing may be started from here on.
        recovery.standDown("service_destroyed")

        // 1-3. The owner hands the permit back. The stop runs out of
        // budget, so the close fails and the permit is stranded.
        assertFalse(
            permit.closeAndRelease("transport_loop_exited"),
            "a stop that ran out of budget is not a release: $steps",
        )
        assertTrue(
            policy.hasUnclosed(),
            "the permit must be in the set a sweep retries, not left in the live " +
                "set that nothing sweeps: $steps",
        )

        // 4. The driver, as the service arms it on a refused release.
        recovery.arm("socket_not_confirmed_closed")

        // 5. No switch, no nudge: the sweep alone keeps trying, and the
        // late close is what discharges it.
        await("the sweep to retry the close: $steps") {
            steps.count { it == "close_attempt" } >= 2
        }
        assertTrue(policy.hasUnclosed(), "still owed while the close keeps failing: $steps")

        slowClose.set(false)
        await("the sweep to finish it: $steps") { !policy.hasUnclosed() }
        assertTrue(steps.contains("transport_closed"), "$steps")

        // 6. And nothing was started, because the service is stopped.
        assertTrue(steps.none { it == "bare_start" }, "$steps")
    }
}
