// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R-N1.17 P1 - exactly ONE successor per privacy switch, and one owner
 * of the record that says so.
 *
 * The deferred half was made a single epoch-bound obligation and given
 * sole ownership of the successor start - and the inline path then
 * started one unconditionally anyway, so the ownership was nominal. The
 * two successors raced: the inline one could win the connect lease and
 * reach a permit that had already been revoked, and the settlement
 * started another behind it.
 *
 * Nothing here drives a callback of its own invention: every fixture
 * goes through [concludePrivacySwitch] and [settleDeferredPrivacySwitch],
 * which are the two halves the Android container calls.
 */
class SwitchSuccessorOwnershipTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun scope(): CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes.add(it) }

    /** Thread-safe: these fixtures read while other threads write. */
    private fun steps() = SafeSteps()

    /** Self-reference for a recovery whose callback consults itself. */
    private var recovery0: HandoffRecovery? = null

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
     * recover.
     *
     * A merely owned lease is not blocked: `recoverIfBlocked` returns
     * null and the signal never reaches the start path at all. The first
     * handover has to fail for there to be anything to recover.
     */
    private suspend fun blockedLease(ownership: ConnectOwnership): () -> Unit {
        val stops = AtomicBoolean(false)
        val claimed = ownership.claim("old_walk", ConnectWalkHandle { stops.get() })
        assertNotNull(claimed, "precondition: the walk owns the lease")
        val outcome = ownership.handOver("wedged_walk")
        assertTrue(
            outcome is Handover.TimedOut,
            "precondition: the lease must be fail-closed, was $outcome",
        )
        return { stops.set(true) }
    }

    // ----------------------------------------------------------------
    // Who owes the successor
    // ----------------------------------------------------------------

    @Test
    fun aFinishedSwitchStartsExactlyOneSuccessorItself() = runBlocking {
        val steps = steps()
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)

        val conclusion = concludePrivacySwitch(
            epoch = epoch,
            coordinator = policy,
            stillOpen = 0,
            walkQuiesced = true,
            teardownConfirmed = true,
            armSettlement = { _, _ -> steps.add("settlement_armed") },
            startSuccessor = { steps.add("successor_started"); HandoffRecovery.SuccessorOutcome.Started },
            armStartRetry = { _, _ -> steps.add("start_retry_armed") },
        )

        assertTrue(conclusion is SwitchConclusion.Finished, "nothing was left over: $steps")
        assertEquals(listOf("successor_started"), steps.toList())
        assertEquals(PrivacyModeStatus.Applied, policy.state.value.status)
    }

    @Test
    fun aSwitchWithAnOpenSocketStartsNoSuccessorOfItsOwn() = runBlocking {
        // The production bypass. The container completed the switch and
        // then started a successor on every path, including the one that
        // had just armed a settlement to start one later.
        val steps = steps()
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)

        val conclusion = concludePrivacySwitch(
            epoch = epoch,
            coordinator = policy,
            stillOpen = 1,
            walkQuiesced = true,
            teardownConfirmed = true,
            armSettlement = { reason, _ -> steps.add("settlement_armed:$reason") },
            startSuccessor = { steps.add("successor_started"); HandoffRecovery.SuccessorOutcome.Started },
            armStartRetry = { _, _ -> steps.add("start_retry_armed") },
        )

        assertTrue(conclusion is SwitchConclusion.Deferred, "a socket is still open: $steps")
        assertTrue(
            steps.none { it == "successor_started" },
            "the settlement owns the start; this transaction must not race it: $steps",
        )
        assertEquals(1, steps.count { it.startsWith("settlement_armed") }, "$steps")
        assertEquals(PrivacyModeStatus.Blocked, policy.state.value.status)
    }

    @Test
    fun aWalkThatWouldNotStopAlsoDefersTheSuccessor() = runBlocking {
        // The case the container got wrong TWICE over: the sockets were
        // all closed, so no settlement was armed at all, and the walk had
        // not quiesced, so the release had been skipped - and it still
        // started a successor into a switch that owed a release and a
        // completion nobody would ever run.
        val steps = steps()
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)

        val conclusion = concludePrivacySwitch(
            epoch = epoch,
            coordinator = policy,
            stillOpen = 0,
            walkQuiesced = false,
            teardownConfirmed = true,
            armSettlement = { reason, _ -> steps.add("settlement_armed:$reason") },
            startSuccessor = { steps.add("successor_started"); HandoffRecovery.SuccessorOutcome.Started },
            armStartRetry = { _, _ -> steps.add("start_retry_armed") },
        )

        assertTrue(conclusion is SwitchConclusion.Deferred, "the walk did not stop: $steps")
        assertEquals(
            listOf("settlement_armed:privacy_switch_walk_not_quiesced"),
            steps.toList(),
            "an unconfirmed walk owes the whole deferred half, and starts nothing",
        )
    }

    @Test
    fun aFailedInlineStartBecomesAnObligationRatherThanALogLine() = runBlocking {
        val steps = steps()
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)

        val conclusion = concludePrivacySwitch(
            epoch = epoch,
            coordinator = policy,
            stillOpen = 0,
            walkQuiesced = true,
            teardownConfirmed = true,
            armSettlement = { _, _ -> steps.add("settlement_armed") },
            startSuccessor = { steps.add("successor_attempted"); HandoffRecovery.SuccessorOutcome.Failed },
            armStartRetry = { reason, _ -> steps.add("start_retry_armed:$reason") },
        )

        assertEquals(
            SwitchConclusion.Finished(conclusion.result, successorStarted = false),
            conclusion,
        )
        assertEquals(
            listOf("successor_attempted", "start_retry_armed:successor_start_failed"),
            steps.toList(),
            "the platform can refuse the start; the app must not sit there with a " +
                "persisted new mode and no transport",
        )
    }

    @Test
    fun theInlineAndDeferredHalvesStartOneSuccessorBetweenThem() = runBlocking {
        // Both halves of the pair, in sequence, as production runs them.
        val steps = steps()
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)

        concludePrivacySwitch(
            epoch = epoch,
            coordinator = policy,
            stillOpen = 1,
            walkQuiesced = true,
            teardownConfirmed = true,
            armSettlement = { _, _ -> steps.add("settlement_armed") },
            startSuccessor = { steps.add("successor_started"); HandoffRecovery.SuccessorOutcome.Started },
            armStartRetry = { _, _ -> steps.add("start_retry_armed") },
        )
        val outcome = settleDeferredPrivacySwitch(
            epoch = epoch,
            coordinator = policy,
            retryTeardown = {
                steps.add("released")
                TeardownAttempt(walkQuiesced = true, confirmed = true)
            },
            startSuccessor = { steps.add("successor_started"); HandoffRecovery.SuccessorOutcome.Started },
        )

        assertTrue(outcome.isSettled, "$steps")
        assertEquals(
            1,
            steps.count { it == "successor_started" },
            "exactly one successor across BOTH halves: $steps",
        )
        assertEquals(PrivacyModeStatus.Applied, policy.state.value.status)
    }

    // ----------------------------------------------------------------
    // A release that was not confirmed
    // ----------------------------------------------------------------

    @Test
    fun aReleaseThatIsNotConfirmedIsNotASettledSwitch() = runBlocking {
        // `Settled` means everything was released. A dirty release used
        // to be logged and stepped over, and the switch then completed
        // and started a successor - a Direct subsystem surviving a switch
        // to Ghost while the UI showed the switch as applied.
        val steps = steps()
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)

        val outcome = settleDeferredPrivacySwitch(
            epoch = epoch,
            coordinator = policy,
            retryTeardown = {
                steps.add("release_attempted")
                TeardownAttempt(walkQuiesced = true, confirmed = false)
            },
            startSuccessor = { steps.add("successor_started"); HandoffRecovery.SuccessorOutcome.Started },
        )

        assertEquals(SettlementPhase.TeardownIncomplete, outcome.phase)
        assertFalse(outcome.isDischarged, "the obligation stands, so it is retried")
        assertTrue(
            steps.none { it == "successor_started" },
            "nothing may start over subsystems that are still up: $steps",
        )
        assertEquals(
            PrivacyModeStatus.Blocked,
            policy.state.value.status,
            "and the switch must not present as applied",
        )
        assertEquals(PrivacyMode.Standard, policy.state.value.effective)
    }

    @Test
    fun aTeardownThatThrowsIsTreatedTheSameWay() = runBlocking {
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)
        var started = false

        val outcome = settleDeferredPrivacySwitch(
            epoch = epoch,
            coordinator = policy,
            retryTeardown = { error("subsystem stop blew up") },
            startSuccessor = { started = true; HandoffRecovery.SuccessorOutcome.Started },
        )

        assertEquals(SettlementPhase.TeardownIncomplete, outcome.phase)
        assertFalse(started, "a thrown release is not a completed phase either")
    }

    // ----------------------------------------------------------------
    // A release must not overlap the next switch
    // ----------------------------------------------------------------

    @Test
    fun aReleaseCannotOverlapTheNextSwitchesStartup() = runBlocking {
        // R-N1.17 P1. Re-reading the epoch between steps does not close
        // this window. The settlement checked the epoch, then SUSPENDED
        // inside the release; a new request could run its whole
        // transaction and bring a transport up in that gap, and the late
        // release would then stop the NEW subsystems. The following
        // `complete()` reported Superseded correctly - after the damage.
        val order = steps()
        val releaseParked = CompletableDeferred<Unit>()
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)

        scope().launch {
            policy.withTransition {
                settleDeferredPrivacySwitch(
                    epoch = epoch,
                    coordinator = policy,
                    retryTeardown = {
                        order.add("release_begin")
                        releaseParked.await()
                        order.add("release_end")
                        TeardownAttempt(walkQuiesced = true, confirmed = true)
                    },
                    startSuccessor = { HandoffRecovery.SuccessorOutcome.Started },
                )
            }
        }
        // Let the settlement reach the parked release.
        await("the release to begin: $order") { order.contains("release_begin") }

        scope().launch {
            policy.withTransition {
                order.add("next_switch_begin")
                policy.requestMode(PrivacyMode.Standard)
                order.add("next_switch_end")
            }
        }
        delay(200)

        assertTrue(
            order.none { it == "next_switch_begin" },
            "a new switch must not start its transaction while a release from the " +
                "previous one is still in flight: $order",
        )

        releaseParked.complete(Unit)
        await("the next switch to run: $order") { order.contains("next_switch_end") }

        assertTrue(
            order.indexOf("release_end") < order.indexOf("next_switch_begin"),
            "the release must finish before the next switch begins: $order",
        )
    }

    // ----------------------------------------------------------------
    // The obligation record itself
    // ----------------------------------------------------------------

    /**
     * Park inside the recovery's own critical section.
     *
     * `startOneOrdinaryConnect` is called while the record lock is held,
     * so blocking here holds that lock - which is what lets a fixture
     * position another operation behind it deterministically. It blocks a
     * thread rather than suspending because the production callback is
     * not a suspend function, and that is the shape being pinned.
     */
    private class Latch {
        private val gate = java.util.concurrent.CountDownLatch(1)
        private val reached = java.util.concurrent.CountDownLatch(1)
        fun park() {
            reached.countDown()
            gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        fun awaitReached() = assertTrue(
            reached.await(10, java.util.concurrent.TimeUnit.SECONDS),
            "the start path was never reached - the lease was not fail-closed",
        )
        fun release() = gate.countDown()
    }

    @Test
    fun aFailedStartCannotDowngradeASettlementThatArrivedBehindIt() = runBlocking {
        // R-N1.17 P1. The start used to run OUTSIDE the record lock, and
        // the result was written back with no token. So a bare start that
        // failed would overwrite a settlement armed while it was running,
        // replacing `needsSettlement = true` with `false` - silently
        // downgrading a full deferred half to a start retry. The release
        // and the completion were then owed to nobody.
        val steps = steps()
        val latch = Latch()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)

        // A lease that is blocked, so the signal reaches the start path.
        val letItStop = blockedLease(ownership)

        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { reason ->
                steps.add("bare_start:$reason")
                latch.park()
                false
            },
            // Deliberately LONGER than the window below. With a short
            // interval the timer settled the obligation before the
            // wedged start ever returned, so the stale write landed on an
            // already-finished switch and the fixture proved nothing.
            intervalMs = 300L,
            finishDeferredSwitch = { owedEpoch ->
                steps.add("settling:$owedEpoch")
                settleDeferredPrivacySwitch(
                    epoch = owedEpoch,
                    coordinator = policy,
                    retryTeardown = {
                        steps.add("released")
                        TeardownAttempt(walkQuiesced = true, confirmed = true)
                    },
                    startSuccessor = { steps.add("successor_started"); HandoffRecovery.SuccessorOutcome.Started },
                ).isDischarged
            },
        )

        letItStop()
        scope().launch { recovery.onSignal("nudge") }
        latch.awaitReached()

        // The settlement lands while the bare start is in flight.
        val armed = scope().launch {
            recovery.armDeferredSettlement("privacy_switch_sockets_open", epoch)
        }
        // Long enough for the arm to land if nothing serialises it,
        // short enough that the recovery timer has not ticked yet - so
        // the stale start's write is the FIRST thing to reach the record
        // after the settlement was armed.
        delay(100)
        latch.release()
        armed.join()

        await("the settlement to finish: $steps", budgetMs = 8_000) {
            policy.state.value.status == PrivacyModeStatus.Applied
        }

        assertEquals(
            PrivacyModeStatus.Applied,
            policy.state.value.status,
            "the settlement must survive the failed start that was already " +
                "running when it was armed: $steps",
        )
        assertTrue(steps.contains("released"), "$steps")
        assertEquals(1, steps.count { it == "successor_started" }, "$steps")
    }

    @Test
    fun aShutdownDuringAnArmLeavesNothingArmed() = runBlocking {
        // R-N1.17 P1. Arming used to take the lock twice: write the
        // record, release, then take it again to install the timer.
        // `cancel()` could land in that gap - clearing the record and
        // stopping the timer - and the arm would then install a FRESH
        // timer after the shutdown. That timer still sweeps, still
        // recovers the lease and can start the service again.
        val latch = Latch()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { latch.park(); true },
            intervalMs = 40L,
        )


        val letItStop = blockedLease(ownership)
        letItStop()

        // Hold the record lock.
        scope().launch { recovery.onSignal("nudge") }
        latch.awaitReached()

        // Queue the arm behind it, then the shutdown behind the arm.
        // A START debt, not a settlement: a settlement obligation owns
        // the claim fence and is deliberately not discarded by a
        // shutdown, which is covered separately. The property under test
        // here is that the record and its timer are written together.
        val arming = scope().launch { recovery.armStartRetry("late_arm", epoch = 7L) }
        delay(150)
        val shutdown = scope().launch { recovery.standDown("service_destroyed") }
        delay(150)

        latch.release()
        arming.join()
        shutdown.join()

        assertFalse(
            recovery.isArmed(),
            "a timer installed after the shutdown outlives the thing that stood it " +
                "down, and can start the service again",
        )
    }

    // ----------------------------------------------------------------
    // The inline half obeys the same release rule as the deferred half
    // ----------------------------------------------------------------

    @Test
    fun anInlineReleaseThatFailedIsNotAnAppliedSwitch() = runBlocking {
        // R-N1.17 P1. The deferred half refuses to call a dirty release a
        // settled switch. The inline half - the path that runs on every
        // ordinary switch - logged the failure and went on to Applied and
        // a successor, so a Direct subsystem could survive a switch to
        // Ghost while the UI showed the switch as done.
        val steps = steps()
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)

        val conclusion = concludePrivacySwitch(
            epoch = epoch,
            coordinator = policy,
            stillOpen = 0,
            walkQuiesced = true,
            teardownConfirmed = false,
            armSettlement = { reason, _ -> steps.add("settlement_armed:$reason") },
            startSuccessor = { steps.add("successor_started"); HandoffRecovery.SuccessorOutcome.Started },
            armStartRetry = { _, _ -> steps.add("start_retry_armed") },
        )

        assertTrue(
            conclusion is SwitchConclusion.Deferred,
            "subsystems that are still up owe a continuation: $steps",
        )
        assertEquals(
            listOf("settlement_armed:privacy_switch_teardown_incomplete"),
            steps.toList(),
            "the switch must arm a continuation and start nothing",
        )
        assertEquals(
            PrivacyModeStatus.Blocked,
            policy.state.value.status,
            "and must not present as applied",
        )
        assertEquals(PrivacyMode.Standard, policy.state.value.effective)
    }

    // ----------------------------------------------------------------
    // The lease stays shut until the release is done
    // ----------------------------------------------------------------

    @Test
    fun anOrdinaryStartCannotClaimTheLeaseWhileTheReleaseRuns() = runBlocking {
        // R-N1.17 P1. A successful handover frees `owner` the instant the
        // old walk is confirmed stopped - and the transition then spends
        // real time stopping the subsystems. In that window the lease is
        // free, so an ordinary onStartCommand (a wakeup, the user opening
        // the app, the platform restarting the service) could claim it,
        // begin a fresh chain walk, and have its brand-new subsystems
        // torn down by the release belonging to a switch that had already
        // let go of the lease.
        //
        // The transition mutex does not cover this: it serialises
        // transitions against each other, and an ordinary start is not a
        // transition.
        val order = steps()
        val releaseParked = CompletableDeferred<Unit>()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)
        var claimDuringRelease: Long? = -1L
        var claimAtSuccessor: Long? = null

        val transition = scope().launch {
            settleDeferredPrivacySwitch(
                epoch = epoch,
                coordinator = policy,
                raiseClaimFence = { ownership.raiseClaimFence("privacy_mode_change", epoch) },
                lowerClaimFence = { ownership.lowerClaimFence("privacy_mode_change", epoch) },
                retryTeardown = {
                    order.add("release_begin")
                    releaseParked.await()
                    order.add("release_end")
                    TeardownAttempt(walkQuiesced = true, confirmed = true)
                },
                startSuccessor = {
                    // The successor MUST be able to claim: the fence is
                    // lifted after the completion, before this.
                    claimAtSuccessor = ownership.claim("successor", ConnectWalkHandle { true })
                    order.add("successor_started")
                    HandoffRecovery.SuccessorOutcome.Started
                },
            )
        }
        await("the release to begin: $order") { order.contains("release_begin") }

        claimDuringRelease = ownership.claim("ordinary_start", ConnectWalkHandle { true })
        assertEquals(
            null,
            claimDuringRelease,
            "an ordinary start claimed the lease while the release was in flight - " +
                "its subsystems would then be stopped by this very transition",
        )

        releaseParked.complete(Unit)
        transition.join()

        assertNotNull(
            claimAtSuccessor,
            "the successor must be able to claim once the release is done: $order",
        )
        assertFalse(ownership.isFenced(), "the fence must not outlive the transition")
    }

    @Test
    fun aDeferredConclusionKeepsTheLeaseShutUntilItSettles() = runBlocking {
        // R-N1.17 P1, and an inversion of what this fixture used to
        // assert. It pinned "a deferred switch lowers the fence", which
        // is fail-OPEN in the one state that is not safe: the switch is
        // Blocked, something from the old policy is still live, and the
        // release that would stop it has not run. An ordinary service
        // start could claim the lease in that gap and begin a fresh chain
        // walk - and the settlement would then release the subsystems
        // underneath it.
        //
        // Staying shut while an obligation stands is the same trade the
        // handover block already makes. The settlement's retry is what
        // keeps the outage brief.
        val steps = steps()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)
        ownership.raiseClaimFence("privacy_mode_change", epoch)

        concludePrivacySwitch(
            epoch = epoch,
            coordinator = policy,
            stillOpen = 1,
            walkQuiesced = true,
            teardownConfirmed = false,
            armSettlement = { _, _ -> steps.add("settlement_armed") },
            lowerClaimFence = { ownership.lowerClaimFence("privacy_mode_change", epoch) },
            startSuccessor = { steps.add("successor_started"); HandoffRecovery.SuccessorOutcome.Started },
            armStartRetry = { _, _ -> steps.add("start_retry_armed") },
        )

        assertTrue(
            ownership.isFenced(),
            "the switch is Blocked and owes a settlement - the lease must stay shut",
        )
        assertEquals(
            null,
            ownership.claim("ordinary_start", ConnectWalkHandle { true }),
            "and an ordinary start must not be able to begin a walk in that state",
        )

        // The settlement, when it finally succeeds, is what reopens it.
        val outcome = settleDeferredPrivacySwitch(
            epoch = epoch,
            coordinator = policy,
            raiseClaimFence = { ownership.raiseClaimFence("deferred_settlement", epoch) },
            lowerClaimFence = { ownership.lowerClaimFence("deferred_settlement", epoch) },
            retryTeardown = {
                steps.add("released")
                TeardownAttempt(walkQuiesced = true, confirmed = true)
            },
            startSuccessor = { steps.add("successor_started"); HandoffRecovery.SuccessorOutcome.Started },
        )

        assertTrue(outcome.isSettled, "$steps")
        assertFalse(
            ownership.isFenced(),
            "once the release is confirmed there is nothing left to protect: $steps",
        )
    }

    @Test
    fun aSettlementThatKeepsFailingKeepsTheLeaseShut() = runBlocking {
        // Fail-closed has to hold across attempts, not just on the first
        // one. A settlement that raises the fence and then cannot confirm
        // its release must not leave the lease open behind it.
        val steps = steps()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)

        val outcome = settleDeferredPrivacySwitch(
            epoch = epoch,
            coordinator = policy,
            raiseClaimFence = { ownership.raiseClaimFence("deferred_settlement", epoch) },
            lowerClaimFence = { ownership.lowerClaimFence("deferred_settlement", epoch) },
            retryTeardown = {
                steps.add("release_attempted")
                TeardownAttempt(walkQuiesced = true, confirmed = false)
            },
            startSuccessor = { steps.add("successor_started"); HandoffRecovery.SuccessorOutcome.Started },
        )

        assertEquals(SettlementPhase.TeardownIncomplete, outcome.phase)
        assertTrue(
            ownership.isFenced(),
            "the release is still owed, so the lease stays shut: $steps",
        )
    }

    @Test
    fun aSupersededSettlementCannotReopenALeaseANewerSwitchHasShut() = runBlocking {
        // Why the fence is OWNED rather than a plain flag. A stale
        // obligation lowers its fence on the way out; if that lower were
        // unconditional it would open the lease for a newer transition
        // whose own subsystems are still coming down.
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val stale = policy.requestMode(PrivacyMode.Ghost)
        val live = policy.requestMode(PrivacyMode.Private)
        ownership.raiseClaimFence("privacy_mode_change", live)

        val outcome = settleDeferredPrivacySwitch(
            epoch = stale,
            coordinator = policy,
            raiseClaimFence = { ownership.raiseClaimFence("deferred_settlement", stale) },
            lowerClaimFence = { ownership.lowerClaimFence("deferred_settlement", stale) },
            retryTeardown = { TeardownAttempt(walkQuiesced = true, confirmed = true) },
            startSuccessor = { HandoffRecovery.SuccessorOutcome.Started },
        )

        assertEquals(SettlementPhase.Superseded, outcome.phase)
        assertTrue(
            ownership.isFenced(),
            "a superseded obligation must not open a lease it no longer owns",
        )
        assertEquals(live, ownership.fenceOwner())
    }

    @Test
    fun anUnknownPolicyEpochLeavesTheDebtStanding() = runBlocking {
        // R-N1.17 P2. The production provider cannot answer while the
        // application container is not up - which is exactly when a start
        // has most likely just failed. Reading "cannot tell" as "stale"
        // discharged the very debt that existed to fix it.
        val steps = steps()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { reason -> steps.add("start:$reason"); true },
            intervalMs = 60L,
            currentPolicyEpoch = { null },
        )

        recovery.armStartRetry("successor_start_failed", epoch = 7L)

        await("the debt to be retried despite an unknown epoch: $steps") {
            steps.any { it.startsWith("start:") }
        }
    }

    // ----------------------------------------------------------------
    // A start debt belongs to its epoch
    // ----------------------------------------------------------------

    @Test
    fun aSuccessfulSwitchDischargesAnOlderStartDebt() = runBlocking {
        // R-N1.17 P2. Start A is refused and leaves a debt. Switch B then
        // starts the service successfully - and nothing told the debt, so
        // A's timer fired a second start, outside the backoff ladder.
        val steps = steps()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { reason -> steps.add("start:$reason"); true },
            intervalMs = 60L,
        )

        recovery.armStartRetry("successor_start_failed", epoch = 1L)
        assertTrue(recovery.isArmed(), "precondition: the debt has a driver")

        // A later switch starts its own successor.
        recovery.noteSuccessorStarted(epoch = 2L)

        delay(400)
        assertTrue(
            steps.none { it.startsWith("start:") },
            "the debt described a successor that switch 2 has now provided: $steps",
        )
    }

    @Test
    fun aStartDebtFromASupersededEpochIsNotRetried() = runBlocking {
        // The same fact from the timer's side: even with nothing to tell
        // it, a debt recorded under an epoch the policy has moved past
        // must not fire.
        val steps = steps()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val stale = policy.requestMode(PrivacyMode.Ghost)
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { reason -> steps.add("start:$reason"); true },
            intervalMs = 60L,
            currentPolicyEpoch = { policy.currentEpoch() },
        )

        recovery.armStartRetry("successor_start_failed", epoch = stale)
        // The policy moves on before the timer gets its turn.
        policy.requestMode(PrivacyMode.Standard)

        delay(400)
        assertTrue(
            steps.none { it.startsWith("start:") },
            "a debt from a superseded epoch describes a successor nobody wants: $steps",
        )
    }

    @Test
    fun aStartDebtFromTheLiveEpochIsStillRetried() = runBlocking {
        // The control. The epoch check must not become a way to drop
        // every obligation.
        val steps = steps()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val live = policy.requestMode(PrivacyMode.Ghost)
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { reason -> steps.add("start:$reason"); true },
            intervalMs = 60L,
            currentPolicyEpoch = { policy.currentEpoch() },
        )

        recovery.armStartRetry("successor_start_failed", epoch = live)

        await("the debt to be retried: $steps") { steps.any { it.startsWith("start:") } }
    }

    // ----------------------------------------------------------------
    // A failed phase is retried, not stepped over
    // ----------------------------------------------------------------

    @Test
    fun aFailedRevocationIsRetriedAndBlocksTheSwitchUntilItSucceeds() = runBlocking {
        // R-N1.17 P1. The inline half correctly refused to call a switch
        // complete when the REST egress revocation threw - and the
        // deferred half then retried only the SUBSYSTEM RELEASE. With a
        // clean permit register and a successful release it published
        // Applied, lowered the fence and started a successor, while the
        // revocation that failed was never attempted again: old REST and
        // media leases could still be dispatching under the new posture.
        //
        // Every teardown phase is idempotent, so the deferred half runs
        // them all and lets a fresh full result decide.
        val steps = steps()
        val revokeWorks = AtomicBoolean(false)
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)
        ownership.raiseClaimFence("privacy_mode_change", epoch)

        // The inline half: the revocation threw, so nothing is confirmed.
        concludePrivacySwitch(
            epoch = epoch,
            coordinator = policy,
            stillOpen = 0,
            walkQuiesced = true,
            teardownConfirmed = false,
            armSettlement = { reason, _ -> steps.add("armed:$reason") },
            lowerClaimFence = { ownership.lowerClaimFence("privacy_mode_change", epoch) },
            startSuccessor = { steps.add("successor_started"); HandoffRecovery.SuccessorOutcome.Started },
            armStartRetry = { _, _ -> steps.add("start_retry_armed") },
        )
        assertEquals(PrivacyModeStatus.Blocked, policy.state.value.status)

        val settle: suspend () -> SettlementOutcome = {
            settleDeferredPrivacySwitch(
                epoch = epoch,
                coordinator = policy,
                raiseClaimFence = { ownership.raiseClaimFence("deferred", epoch) },
                lowerClaimFence = { ownership.lowerClaimFence("deferred", epoch) },
                retryTeardown = {
                    // The whole teardown, exactly as production re-runs
                    // it. The release always succeeds; the REVOCATION is
                    // what is broken.
                    steps.add("revoke_attempted")
                    val revoked = revokeWorks.get()
                    steps.add("released")
                    TeardownAttempt(walkQuiesced = true, confirmed = revoked)
                },
                startSuccessor = { steps.add("successor_started"); HandoffRecovery.SuccessorOutcome.Started },
            )
        }

        val first = settle()
        assertEquals(
            SettlementPhase.TeardownIncomplete,
            first.phase,
            "a release that worked does not make a revocation that did not: $steps",
        )
        assertEquals(
            PrivacyModeStatus.Blocked,
            policy.state.value.status,
            "the switch must not be applied while a phase is still owed: $steps",
        )
        assertEquals(PrivacyMode.Standard, policy.state.value.effective)
        assertTrue(
            steps.none { it == "successor_started" },
            "and no successor may run over leases that were never revoked: $steps",
        )
        assertTrue(ownership.isFenced(), "the lease stays shut: $steps")
        assertEquals(
            1,
            steps.count { it == "revoke_attempted" },
            "the failed phase must actually be re-attempted: $steps",
        )

        // The gate recovers.
        revokeWorks.set(true)
        val second = settle()

        assertTrue(second.isSettled, "$steps")
        assertEquals(2, steps.count { it == "revoke_attempted" }, "$steps")
        assertEquals(PrivacyModeStatus.Applied, policy.state.value.status)
        assertEquals(PrivacyMode.Ghost, policy.state.value.effective)
        assertEquals(1, steps.count { it == "successor_started" }, "$steps")
        assertFalse(ownership.isFenced())
    }

    // ----------------------------------------------------------------
    // A shutdown may not orphan the fence
    // ----------------------------------------------------------------

    /**
     * The shutdown half of production, in one place.
     *
     * `onDestroy` stands recovery down, takes whatever obligation was
     * outstanding, and discharges it inside its OWN teardown. The
     * successor is suppressed for as long as recovery is stood down.
     */
    private suspend fun shutdownDischarge(
        recovery: HandoffRecovery,
        ownership: ConnectOwnership,
        policy: PrivacyModeCoordinator,
        steps: SafeSteps,
        teardownWorks: () -> Boolean = { true },
    ): HandoffRecovery.StandDown {
        val owed = recovery.standDown("service_destroyed")
        if (owed is HandoffRecovery.StandDown.SettlementOwed) {
            val settled = settleDeferredPrivacySwitch(
                epoch = owed.epoch,
                coordinator = policy,
                raiseClaimFence = { ownership.raiseClaimFence("shutdown", owed.epoch) },
                lowerClaimFence = { ownership.lowerClaimFence("shutdown", owed.epoch) },
                retryTeardown = {
                    steps.add("teardown")
                    val ok = teardownWorks()
                    TeardownAttempt(walkQuiesced = ok, confirmed = ok)
                },
                startSuccessor = {
                    recovery.startSuccessorUnlessShutDown {
                        steps.add("successor_started"); true
                    }
                },
            ).isDischarged
            recovery.noteShutdownSettlement(owed.claimToken, settled)
        }
        return owed
    }

    @Test
    fun anExplicitStopFinishesTheSwitchAndStartsNothing() = runBlocking {
        // R-N1.17 P1, and an inversion of what this fixture used to
        // assert. Keeping the obligation's TIMER alive across onDestroy
        // fixed an orphaned fence and broke a contract that was already
        // written down: an explicit stop must not schedule its own
        // restart, and recovery starts the service unconditionally rather
        // than through the scheduler. The previous version deliberately
        // WAITED for that restart, so it pinned the forbidden behaviour.
        //
        // Discharging the obligation and starting something are two
        // different acts. Only the second is forbidden.
        val steps = steps()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)
        ownership.raiseClaimFence("privacy_mode_change", epoch)

        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { steps.add("bare_start"); true },
            intervalMs = 60L,
            finishDeferredSwitch = { steps.add("timer_settled"); true },
        )
        recovery.armDeferredSettlement("privacy_switch_teardown_incomplete", epoch)

        val owed = shutdownDischarge(recovery, ownership, policy, steps)

        assertTrue(
            owed is HandoffRecovery.StandDown.SettlementOwed,
            "the obligation must be handed to the shutdown, not dropped: $steps",
        )
        // The switch really was finished.
        assertEquals(PrivacyModeStatus.Applied, policy.state.value.status)
        assertEquals(PrivacyMode.Ghost, policy.state.value.effective)
        assertFalse(ownership.isFenced(), "the fence must not be orphaned: $steps")
        assertTrue(
            ownership.claim("later_start", ConnectWalkHandle { true }) != null,
            "and the lease must be usable again: $steps",
        )
        // But nothing was restarted.
        assertTrue(
            steps.none { it == "successor_started" || it == "bare_start" },
            "an explicit stop must not schedule its own restart: $steps",
        )
        delay(300)
        assertFalse(recovery.isArmed(), "and no timer may survive the stop: $steps")
        assertTrue(steps.none { it == "timer_settled" }, "$steps")
    }

    @Test
    fun theShutdownsOwnTeardownIsNotRacedByARecoveryTimer() = runBlocking {
        // R-N1.17 P1. `onDestroy` is not inside the privacy transition
        // lock, and the subsystem release is not serialised against
        // connect. A surviving timer could therefore run the whole
        // teardown, lower the fence and start a successor while
        // onDestroy's own release was still running - the old instance
        // then stopping the new walk's subsystems, and two native stops
        // overlapping.
        //
        // The timer is stopped BEFORE the shutdown does anything, and the
        // obligation is carried by the shutdown itself, so there is only
        // ever one worker.
        val steps = steps()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)
        ownership.raiseClaimFence("privacy_mode_change", epoch)

        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { steps.add("bare_start"); true },
            intervalMs = 40L,
            finishDeferredSwitch = { steps.add("timer_teardown"); true },
        )
        recovery.armDeferredSettlement("privacy_switch_teardown_incomplete", epoch)

        val owed = recovery.standDown("service_destroyed")
        assertTrue(owed is HandoffRecovery.StandDown.SettlementOwed)

        // The shutdown now takes its time over its own teardown - several
        // timer intervals' worth.
        delay(300)
        assertTrue(
            steps.isEmpty(),
            "no second worker may touch the teardown while the shutdown holds it: $steps",
        )
        assertTrue(ownership.isFenced(), "and the lease stays shut meanwhile: $steps")
    }

    @Test
    fun aShutdownThatCouldNotFinishKeepsADriverButStillStartsNothing() = runBlocking {
        // The obligation owns the fence. If the shutdown's own attempt
        // fails, something must still be able to lower it - a lease shut
        // for good is worse than the failure that caused it - but that
        // driver must remain silent about successors until an instance is
        // actually up.
        val steps = steps()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)
        ownership.raiseClaimFence("privacy_mode_change", epoch)

        val teardownWorks = AtomicBoolean(false)
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { steps.add("bare_start"); true },
            intervalMs = 60L,
            finishDeferredSwitch = { owedEpoch ->
                settleDeferredPrivacySwitch(
                    epoch = owedEpoch,
                    coordinator = policy,
                    raiseClaimFence = { ownership.raiseClaimFence("deferred", owedEpoch) },
                    lowerClaimFence = { ownership.lowerClaimFence("deferred", owedEpoch) },
                    retryTeardown = {
                        steps.add("timer_teardown")
                        TeardownAttempt(walkQuiesced = teardownWorks.get(), confirmed = teardownWorks.get())
                    },
                    startSuccessor = {
                        recovery0!!.startSuccessorUnlessShutDown {
                            steps.add("successor_started"); true
                        }
                    },
                ).isDischarged
            },
        )
        recovery0 = recovery
        recovery.armDeferredSettlement("privacy_switch_teardown_incomplete", epoch)

        shutdownDischarge(recovery, ownership, policy, steps) { teardownWorks.get() }

        assertTrue(
            recovery.isArmed(),
            "a fence nobody can lower is worse than the failure that caused it: $steps",
        )
        assertTrue(ownership.isFenced(), "$steps")

        // The teardown becomes possible again while the app is still stopped.
        teardownWorks.set(true)
        await("the obligation to be discharged: $steps") { !ownership.isFenced() }

        assertEquals(PrivacyModeStatus.Applied, policy.state.value.status)
        assertTrue(
            steps.none { it == "successor_started" || it == "bare_start" },
            "still stopped, so still no restart: $steps",
        )
    }

    @Test
    fun aRecreatedServiceGetsItsObligationAndItsSuccessorBack() = runBlocking {
        // The other lifecycle event. The instance is gone but another is
        // coming, so the obligation is handed forward - and once an
        // instance is up, a successor is wanted again.
        val steps = steps()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)
        ownership.raiseClaimFence("privacy_mode_change", epoch)

        val teardownWorks = AtomicBoolean(false)
        var live: HandoffRecovery? = null
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { steps.add("bare_start"); true },
            intervalMs = 60L,
            finishDeferredSwitch = { owedEpoch ->
                settleDeferredPrivacySwitch(
                    epoch = owedEpoch,
                    coordinator = policy,
                    raiseClaimFence = { ownership.raiseClaimFence("deferred", owedEpoch) },
                    lowerClaimFence = { ownership.lowerClaimFence("deferred", owedEpoch) },
                    retryTeardown = {
                        TeardownAttempt(walkQuiesced = teardownWorks.get(), confirmed = teardownWorks.get())
                    },
                    startSuccessor = {
                        live!!.startSuccessorUnlessShutDown {
                            steps.add("successor_started"); true
                        }
                    },
                ).isDischarged
            },
        )
        live = recovery
        recovery.armDeferredSettlement("privacy_switch_teardown_incomplete", epoch)

        // The old instance goes down and cannot finish it.
        shutdownDischarge(recovery, ownership, policy, steps) { teardownWorks.get() }
        assertTrue(ownership.isFenced(), "precondition: still owed: $steps")

        // A new instance comes up.
        teardownWorks.set(true)
        recovery.resume("service_created")

        await("the settlement to run for the new instance: $steps") {
            policy.state.value.status == PrivacyModeStatus.Applied
        }
        assertFalse(ownership.isFenced(), "$steps")
        assertEquals(
            1,
            steps.count { it == "successor_started" },
            "an instance is up, so the successor is wanted again: $steps",
        )
    }

    @Test
    fun aNudgeArrivingDuringAShutdownStartsNothing() = runBlocking {
        // The other way into the start path. `onSignal` is called from
        // the alarm heartbeat and from the wakeup receiver, and it does
        // not consult the timer at all - so suppressing the timer says
        // nothing about it. A nudge landing between onDestroy and the
        // next instance would otherwise start the service that was just
        // stopped.
        val steps = steps()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { steps.add("bare_start"); true },
            intervalMs = 60L,
        )

        val letItStop = blockedLease(ownership)
        letItStop()
        recovery.standDown("service_destroyed")

        val signal = recovery.onSignal("nudge_alarm")

        assertEquals(
            HandoffRecovery.Signal.Recovered(1L),
            signal,
            "the lease may still be recovered - that is not a restart",
        )
        assertTrue(
            steps.isEmpty(),
            "but an explicit stop must not be undone by a nudge: $steps",
        )

        // ...and once an instance is up again, the same nudge works.
        recovery.resume("service_created")
        val blockAgain = blockedLease(ownership)
        blockAgain()
        recovery.onSignal("nudge_alarm")
        assertEquals(
            listOf("bare_start"),
            steps.toList(),
            "the suppression must lift with the shutdown, not outlive it",
        )
    }

    @Test
    fun destroyingTheServiceStillStandsDownAnOrdinaryStartDebt() = runBlocking {
        // The control. Refusing to cancel must apply ONLY to an
        // obligation that holds the fence - otherwise a shutdown could
        // never stand the timer down at all.
        val steps = steps()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { steps.add("bare_start"); true },
            intervalMs = 60L,
        )
        recovery.armStartRetry("successor_start_failed", epoch = 3L)
        assertTrue(recovery.isArmed())

        assertEquals(
            HandoffRecovery.StandDown.Clear,
            recovery.standDown("service_destroyed"),
            "a start-only debt holds nothing open and must stand down",
        )
        assertFalse(recovery.isArmed())

        delay(300)
        assertTrue(steps.isEmpty(), "and nothing must fire afterwards: $steps")
    }

    // ----------------------------------------------------------------
    // The shutdown check and the start are one act
    // ----------------------------------------------------------------

    @Test
    fun aStopLandingBetweenTheCheckAndTheStartStillSuppressesIt() = runBlocking {
        // R-N1.17 P1. The settlement used to ask `successorWanted()` and
        // then, separately, call `startSuccessor()`. A `standDown` landing
        // in that gap left an explicit stop restarting the service - the
        // same check-then-act shape this round has removed three times
        // already, in its last remaining place.
        //
        // The fixture parks INSIDE the start, which is where the decision
        // now lives, and stands the recovery down while it is parked. If
        // the decision were made before the lock, the start would already
        // have been authorised and would go ahead.
        val steps = steps()
        val latch = Latch()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { true },
            intervalMs = 10_000L,
        )

        // A first start parks holding the decision lock.
        val parked = scope().launch {
            recovery.startSuccessorUnlessShutDown { latch.park(); steps.add("parked_start"); true }
        }
        latch.awaitReached()

        // The stop arrives while the decision is held.
        val stop = scope().launch { recovery.standDown("service_destroyed") }
        delay(150)
        latch.release()
        parked.join()
        stop.join()

        // The parked one was already authorised; it is the NEXT one that
        // must see the stop.
        val outcome = settleDeferredPrivacySwitch(
            epoch = epoch,
            coordinator = policy,
            raiseClaimFence = { ownership.raiseClaimFence("deferred", epoch) },
            lowerClaimFence = { ownership.lowerClaimFence("deferred", epoch) },
            retryTeardown = { TeardownAttempt(walkQuiesced = true, confirmed = true) },
            startSuccessor = {
                recovery.startSuccessorUnlessShutDown {
                    steps.add("successor_started"); true
                }
            },
        )

        assertTrue(outcome.isSettled, "the switch is still finished: $steps")
        assertEquals(PrivacyModeStatus.Applied, policy.state.value.status)
        assertFalse(ownership.isFenced(), "and the fence still comes down: $steps")
        assertTrue(
            steps.none { it == "successor_started" },
            "a stop that has already been recorded must suppress the start, even " +
                "though the settlement was under way: $steps",
        )
    }

    @Test
    fun noSuccessorMayStartAfterAStopHasBeenRecorded() = runBlocking {
        // R-N1.17 P1, stated as the invariant rather than as an ordering
        // of two calls: once `standDown` has RETURNED, the stop is a fact,
        // and nothing may still start a service afterwards.
        //
        // The earlier version of this fixture asserted only that a start
        // issued after a completed stop is suppressed - which the split
        // implementation also does, so it separated nothing. What the
        // split implementation cannot do is keep the promise above: it
        // reads `shuttingDown`, releases the lock, and a stop recorded in
        // that gap returns while a start is already authorised and still
        // on its way.
        val steps = steps()
        val latch = Latch()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { true },
            intervalMs = 10_000L,
        )

        // A start is in the middle of deciding.
        val inFlight = scope().async {
            recovery.startSuccessorUnlessShutDown { latch.park(); steps.add("started"); true }
        }
        latch.awaitReached()

        val stopJob = scope().async { recovery.standDown("service_destroyed") }
        val stopRecorded = withTimeoutOrNull(400) { stopJob.await() }

        if (stopRecorded != null) {
            // The stop is a fact. Whatever the in-flight start does from
            // here is a service started after an explicit stop.
            latch.release()
            inFlight.await()
            assertTrue(
                steps.none { it == "started" },
                "a successor started after the stop had already been recorded: $steps",
            )
        } else {
            // The decision holds the record, so the stop cannot become a
            // fact until the start it is racing has finished.
            latch.release()
            assertEquals(
                HandoffRecovery.SuccessorOutcome.Started,
                inFlight.await(),
                "a start already inside the decision completes: $steps",
            )
            stopJob.await()
        }

        // Either way, from here on nothing starts.
        assertEquals(
            HandoffRecovery.SuccessorOutcome.Suppressed,
            recovery.startSuccessorUnlessShutDown { steps.add("late"); true },
            "every start after the stop is suppressed",
        )
        assertTrue(steps.none { it == "late" }, "$steps")
    }

    @Test
    fun anInlineSwitchWhoseStartIsSuppressedArmsNoRetry() = runBlocking {
        // The inline half has the same contract. A stop landing during a
        // switch must not leave a start debt behind: the debt's whole
        // purpose is to send another start, which is the one thing a stop
        // forbids. Treating Suppressed like Failed would schedule exactly
        // the restart the stop exists to prevent.
        val steps = steps()
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)

        val conclusion = concludePrivacySwitch(
            epoch = epoch,
            coordinator = policy,
            stillOpen = 0,
            walkQuiesced = true,
            teardownConfirmed = true,
            armSettlement = { _, _ -> steps.add("settlement_armed") },
            startSuccessor = { HandoffRecovery.SuccessorOutcome.Suppressed },
            armStartRetry = { reason, _ -> steps.add("start_retry_armed:$reason") },
            noteSuccessorStarted = { steps.add("start_debt_discharged") },
        )

        assertEquals(
            SwitchConclusion.Finished(conclusion.result, successorStarted = false),
            conclusion,
        )
        assertEquals(
            PrivacyModeStatus.Applied,
            policy.state.value.status,
            "the switch itself still completes: $steps",
        )
        assertTrue(
            steps.isEmpty(),
            "a suppressed start must arm nothing - a retry IS the restart a stop " +
                "forbids - and must not be reported as a successor either: $steps",
        )
    }

    // ----------------------------------------------------------------
    // The shutdown budget must actually bound the shutdown
    // ----------------------------------------------------------------

    @Test
    fun aWedgedTeardownDoesNotHoldTheShutdownPastItsBudget() = runBlocking {
        // R-N1.17 P1. The outer `withTimeoutOrNull` bounded nothing: the
        // settlement and the whole teardown beneath it run inside
        // NonCancellable by design, and a native stop already under way
        // cannot be aborted from Kotlin. So a wedged phase could hold
        // onDestroy past its budget and, in principle, for ever.
        //
        // The WAIT is bounded instead. The obligation runs in a scope
        // that outlives the instance; the shutdown stops waiting, the
        // obligation stands, the cleanup is re-armed, and nothing starts.
        val steps = steps()
        val released = CompletableDeferred<Unit>()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)
        ownership.raiseClaimFence("privacy_mode_change", epoch)
        val outliving = scope()

        var live: HandoffRecovery? = null
        // ONE settlement, called by the timer and by the shutdown alike -
        // production has exactly that shape.
        val settle: suspend (Long) -> Boolean = { owedEpoch ->
            settleDeferredPrivacySwitch(
                epoch = owedEpoch,
                coordinator = policy,
                raiseClaimFence = { ownership.raiseClaimFence("deferred", owedEpoch) },
                lowerClaimFence = { ownership.lowerClaimFence("deferred", owedEpoch) },
                retryTeardown = {
                    // Wedged, exactly as a native stop that will not
                    // return is wedged. NOT cancellable from outside.
                    steps.add("teardown_entered")
                    withContext(NonCancellable) { released.await() }
                    TeardownAttempt(walkQuiesced = true, confirmed = true)
                },
                startSuccessor = {
                    live!!.startSuccessorUnlessShutDown {
                        steps.add("successor_started"); true
                    }
                },
            ).isDischarged
        }
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { steps.add("bare_start"); true },
            intervalMs = 80L,
            finishDeferredSwitch = { owedEpoch -> settle(owedEpoch) },
        )
        live = recovery
        recovery.armDeferredSettlement("privacy_switch_teardown_incomplete", epoch)

        val owed = recovery.standDown("service_destroyed")
        assertTrue(owed is HandoffRecovery.StandDown.SettlementOwed)

        try {
        val began = System.currentTimeMillis()
        // The call under test is itself bounded by the fixture.
        //
        // Without this an unbounded implementation does not FAIL, it
        // HANGS - the parked teardown sits inside NonCancellable and
        // nothing ever returns. A hang is not an observation: it stalls
        // the run instead of reporting the defect, which is what it did
        // the first time this fixture met its own mutation.
        val settled = withTimeoutOrNull(3_000) {
            settleWithinShutdownBudget(
                claim = owed as HandoffRecovery.StandDown.SettlementOwed,
                budgetMs = 400L,
                scope = outliving,
                settle = { e -> settle(e) },
                noteOutcome = { t, ok -> recovery.noteShutdownSettlement(t, ok) },
                noteOverBudget = { t -> recovery.noteShutdownOverBudget(t) },
            )
        }
        val waited = System.currentTimeMillis() - began

        assertNotNull(
            settled,
            "the shutdown never returned: an unbounded wait holds onDestroy for as " +
                "long as the wedged phase lasts, which is the whole defect",
        )
        assertFalse(settled, "a wedged teardown is not a settled switch: $steps")
        assertTrue(
            waited < 2_000,
            "the shutdown must return on its own budget, not on the teardown's: " +
                "waited ${waited}ms",
        )
        assertTrue(steps.contains("teardown_entered"), "$steps")
        assertTrue(
            recovery.isArmed(),
            "the obligation stands and needs a driver: $steps",
        )
        assertTrue(ownership.isFenced(), "and the lease stays shut: $steps")
        assertTrue(steps.none { it == "successor_started" }, "$steps")

        // The wedged phase finally returns; it reports itself, and still
        // starts nothing because the app is stopped.
        released.complete(Unit)
        await("the in-flight attempt to discharge the obligation: $steps") {
            !ownership.isFenced()
        }
        assertEquals(PrivacyModeStatus.Applied, policy.state.value.status)
        assertTrue(
            steps.none { it == "successor_started" || it == "bare_start" },
            "still stopped, so still no restart: $steps",
        )
        } finally {
            // A REAL finally, not a last statement.
            //
            // The teardown parks inside NonCancellable, so cancelling the
            // scope in @AfterTest does not free it. If any assertion above
            // fails, an unreleased park turns the next mutation run into a
            // harness timeout instead of a failing test - which is exactly
            // what this fixture's own mutation did once already.
            released.complete(Unit)
            withTimeoutOrNull(3_000) {
                outliving.coroutineContext.job.children.forEach { it.join() }
            }
        }
    }

    @Test
    fun aShutdownWaitsOnTheAttemptAlreadyRunningRatherThanStartingASecond() = runBlocking {
        // R-N1.17 P1. The obligation already had a claim protocol -
        // token plus in-flight, resolved by CAS - and the shutdown path
        // went round it: `standDown` handed back an epoch, and the
        // shutdown then started a worker on it unconditionally.
        //
        // Cancelling the timer does not stop what it began: the
        // settlement runs inside NonCancellable precisely so a half-run
        // teardown cannot happen. So the timer's attempt kept going while
        // the shutdown started a second one on the same record. The
        // transition mutex serialises those two; it does not collapse
        // them. Once the service resumed, each could complete the same
        // epoch and start its own successor - two successors, which is
        // the defect this whole file exists to prevent.
        val steps = steps()
        val released = CompletableDeferred<Unit>()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)
        ownership.raiseClaimFence("privacy_mode_change", epoch)
        val outliving = scope()

        var live: HandoffRecovery? = null
        val settle: suspend (Long) -> Boolean = { owedEpoch ->
            settleDeferredPrivacySwitch(
                epoch = owedEpoch,
                coordinator = policy,
                raiseClaimFence = { ownership.raiseClaimFence("deferred", owedEpoch) },
                lowerClaimFence = { ownership.lowerClaimFence("deferred", owedEpoch) },
                retryTeardown = {
                    steps.add("teardown_entered")
                    withContext(NonCancellable) { released.await() }
                    TeardownAttempt(walkQuiesced = true, confirmed = true)
                },
                startSuccessor = {
                    live!!.startSuccessorUnlessShutDown {
                        steps.add("successor_started"); true
                    }
                },
            ).isDischarged
        }
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { steps.add("bare_start"); true },
            intervalMs = 60L,
            finishDeferredSwitch = { owedEpoch -> settle(owedEpoch) },
        )
        live = recovery

        try {
            // The TIMER claims the obligation first and parks inside it.
            recovery.armDeferredSettlement("privacy_switch_teardown_incomplete", epoch)
            await("the timer to claim the obligation: $steps") {
                steps.contains("teardown_entered")
            }

            // Now the service is destroyed.
            val owed = recovery.standDown("service_destroyed")
            assertTrue(
                owed is HandoffRecovery.StandDown.SettlementOwed,
                "the obligation is handed to the shutdown: $steps",
            )
            assertNotNull(
                (owed as HandoffRecovery.StandDown.SettlementOwed).inFlight,
                "and it must be handed over as the RUNNING attempt, not as an epoch " +
                    "to start again",
            )

            val settled = withTimeoutOrNull(3_000) {
                settleWithinShutdownBudget(
                    claim = owed,
                    budgetMs = 300L,
                    scope = outliving,
                    settle = { e -> settle(e) },
                    noteOutcome = { t, ok -> recovery.noteShutdownSettlement(t, ok) },
                    noteOverBudget = { t -> recovery.noteShutdownOverBudget(t) },
                )
            }
            assertEquals(false, settled, "the budget expires while it is parked: $steps")
            assertEquals(
                1,
                steps.count { it == "teardown_entered" },
                "the shutdown must WAIT on the attempt already running, not start a " +
                    "second teardown on the same obligation: $steps",
            )

            // A new instance comes up while the first attempt is still parked.
            recovery.resume("service_created")
            delay(250)
            assertEquals(
                1,
                steps.count { it == "teardown_entered" },
                "and the driver armed for the fence must not claim an obligation " +
                    "that is still in flight: $steps",
            )

            // The parked attempt finally returns.
            released.complete(Unit)
            await("the obligation to be discharged: $steps") { !ownership.isFenced() }
            delay(250)

            assertEquals(PrivacyModeStatus.Applied, policy.state.value.status)
            assertEquals(
                1,
                steps.count { it == "teardown_entered" },
                "exactly one teardown owner across the whole sequence: $steps",
            )
            assertEquals(
                1,
                steps.count { it == "successor_started" },
                "and exactly one successor: $steps",
            )
            assertFalse(recovery.isArmed(), "nothing left owed: $steps")
        } finally {
            released.complete(Unit)
            withTimeoutOrNull(3_000) {
                outliving.coroutineContext.job.children.forEach { it.join() }
            }
        }
    }

    @Test
    fun aStaleShutdownReportCannotEraseANewerObligation() = runBlocking {
        // R-N1.17 P1. The shutdown's report is CAS'd on the claim's token,
        // exactly as the timer's resolve is. Without that check a slow
        // attempt could report success against a record that had since
        // been replaced - erasing a newer obligation that owns the claim
        // fence, and leaving nothing to lower it.
        //
        // The other fixtures never exercise this: they always report the
        // token that is still live, so the check is invisible to them.
        val settled = steps()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { true },
            intervalMs = 60L,
            finishDeferredSwitch = { owedEpoch -> settled.add("settled:$owedEpoch"); true },
        )

        val epochA = policy.requestMode(PrivacyMode.Ghost)
        recovery.armDeferredSettlement("switch_a", epochA)

        // The shutdown takes the claim for A and its attempt runs slowly.
        val owed = recovery.standDown("service_destroyed")
        assertTrue(owed is HandoffRecovery.StandDown.SettlementOwed)
        val staleClaim = (owed as HandoffRecovery.StandDown.SettlementOwed).claimToken

        // A newer switch records its own obligation in the meantime.
        val epochB = policy.requestMode(PrivacyMode.Private)
        recovery.armDeferredSettlement("switch_b", epochB)

        // ...and only THEN does the shutdown's slow attempt report.
        recovery.noteShutdownSettlement(staleClaim, settled = true)

        recovery.resume("service_created")
        await("the newer obligation to be settled: $settled") {
            settled.contains("settled:$epochB")
        }
        assertTrue(
            settled.none { it == "settled:$epochA" },
            "the stale attempt belonged to a record that is gone: $settled",
        )
    }

    @Test
    fun anAttemptThatFailsInsideTheBudgetStillLeavesADriver() = runBlocking {
        // R-N1.17 P1. The shutdown waits on an attempt the TIMER already
        // owns. If that attempt returns unsettled while the shutdown is
        // still waiting, the timer's own `finally` releases the claim and
        // the timer then ends - it was cancelled by `standDown` - so it
        // re-arms nothing.
        //
        // The obligation was therefore left standing with the claim fence
        // raised, old subsystems possibly still live, and nothing at all
        // to try again until some later service start happened to call
        // `resume`. Distinct from the over-budget path, which was already
        // covered: there the attempt is still running and still owns the
        // claim; here it has finished and given the claim back.
        val steps = steps()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val teardownWorks = AtomicBoolean(false)
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)
        ownership.raiseClaimFence("privacy_mode_change", epoch)
        val outliving = scope()

        var live: HandoffRecovery? = null
        val settle: suspend (Long) -> Boolean = { owedEpoch ->
            settleDeferredPrivacySwitch(
                epoch = owedEpoch,
                coordinator = policy,
                raiseClaimFence = { ownership.raiseClaimFence("deferred", owedEpoch) },
                lowerClaimFence = { ownership.lowerClaimFence("deferred", owedEpoch) },
                retryTeardown = {
                    val attempt = steps.count { it == "teardown" } + 1
                    steps.add("teardown")
                    if (attempt == 1) {
                        firstEntered.complete(Unit)
                        withContext(NonCancellable) { releaseFirst.await() }
                    }
                    TeardownAttempt(walkQuiesced = teardownWorks.get(), confirmed = teardownWorks.get())
                },
                startSuccessor = {
                    live!!.startSuccessorUnlessShutDown {
                        steps.add("successor_started"); true
                    }
                },
            ).isDischarged
        }
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { steps.add("bare_start"); true },
            intervalMs = 80L,
            finishDeferredSwitch = { owedEpoch -> settle(owedEpoch) },
        )
        live = recovery

        try {
            // The timer claims the obligation and enters the teardown.
            recovery.armDeferredSettlement("privacy_switch_teardown_incomplete", epoch)
            firstEntered.await()

            val owed = recovery.standDown("service_destroyed")
            assertTrue(owed is HandoffRecovery.StandDown.SettlementOwed)
            assertNotNull((owed as HandoffRecovery.StandDown.SettlementOwed).inFlight)

            // The attempt finishes - unsettled - WELL INSIDE the budget.
            outliving.launch { delay(120); releaseFirst.complete(Unit) }
            val settled = withTimeoutOrNull(5_000) {
                settleWithinShutdownBudget(
                    claim = owed,
                    budgetMs = 3_000L,
                    scope = outliving,
                    settle = { e -> settle(e) },
                    noteOutcome = { t, ok -> recovery.noteShutdownSettlement(t, ok) },
                    noteOverBudget = { t -> recovery.noteShutdownOverBudget(t) },
                )
            }
            assertEquals(
                false,
                settled,
                "the attempt returned inside the budget and did not settle: $steps",
            )

            // No `resume` here on purpose: the app is still stopped, and
            // the obligation must not depend on one to be retried.
            assertTrue(
                recovery.isArmed(),
                "an unfinished obligation that owns the claim fence must always have " +
                    "something able to try again: $steps",
            )
            assertTrue(ownership.isFenced(), "and the lease stays shut meanwhile: $steps")

            // The driver's next attempt succeeds, while still stopped.
            teardownWorks.set(true)
            await("the obligation to be discharged by the driver: $steps") {
                !ownership.isFenced()
            }
            assertEquals(PrivacyModeStatus.Applied, policy.state.value.status)
            assertTrue(
                steps.count { it == "teardown" } >= 2,
                "the retry really was a second attempt: $steps",
            )
            assertTrue(
                steps.none { it == "successor_started" || it == "bare_start" },
                "still stopped, so the retry must finish the switch and start " +
                    "nothing: $steps",
            )
        } finally {
            releaseFirst.complete(Unit)
            withTimeoutOrNull(3_000) {
                outliving.coroutineContext.job.children.forEach { it.join() }
            }
        }
    }

    @Test
    fun aStaleReportCannotReleaseTheClaimThatReplacedIt() = runBlocking {
        // R-N1.17 P1. `token` names the OBLIGATION and survives a claim
        // being released and taken again, so it could not tell two
        // attempts on one record apart.
        //
        // The sequence: a failed attempt releases the claim and a driver
        // is armed; that driver claims the SAME obligation - same token -
        // and enters its teardown; the earlier attempt's report then
        // passed the token check and released the new attempt's claim,
        // leaving the record free for a third teardown to start beside a
        // second still running inside NonCancellable.
        //
        // The existing stale-report fixture replaces the obligation
        // outright, so the token check was enough for it; this one keeps
        // the obligation and changes only the attempt.
        val steps = steps()
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)
        ownership.raiseClaimFence("privacy_mode_change", epoch)
        val outliving = scope()

        var live: HandoffRecovery? = null
        val settle: suspend (Long) -> Boolean = { owedEpoch ->
            settleDeferredPrivacySwitch(
                epoch = owedEpoch,
                coordinator = policy,
                raiseClaimFence = { ownership.raiseClaimFence("deferred", owedEpoch) },
                lowerClaimFence = { ownership.lowerClaimFence("deferred", owedEpoch) },
                retryTeardown = {
                    steps.add("teardown")
                    // The SECOND attempt parks, so the stale report from
                    // the first lands while it is holding the claim.
                    if (steps.count { it == "teardown" } == 2) {
                        entered.complete(Unit)
                        withContext(NonCancellable) { hold.await() }
                    }
                    TeardownAttempt(walkQuiesced = false, confirmed = false)
                },
                startSuccessor = {
                    live!!.startSuccessorUnlessShutDown {
                        steps.add("successor_started"); true
                    }
                },
            ).isDischarged
        }
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { steps.add("bare_start"); true },
            intervalMs = 60L,
            finishDeferredSwitch = { owedEpoch -> settle(owedEpoch) },
        )
        live = recovery

        try {
            recovery.armDeferredSettlement("privacy_switch_teardown_incomplete", epoch)
            // The shutdown takes the free claim for itself.
            val owed = recovery.standDown("service_destroyed")
            assertTrue(owed is HandoffRecovery.StandDown.SettlementOwed)
            val staleClaim = (owed as HandoffRecovery.StandDown.SettlementOwed).claimToken
            assertEquals(null, owed.inFlight, "precondition: this claim is the shutdown's")

            // Its attempt fails, which releases the claim and arms a driver.
            recovery.noteShutdownSettlement(staleClaim, settled = false)
            assertTrue(recovery.isArmed(), "the release must leave a driver: $steps")

            // The driver claims the SAME obligation and parks in its teardown.
            recovery.resume("service_created")
            entered.await()

            // Now the first attempt reports again - late.
            recovery.noteShutdownSettlement(staleClaim, settled = false)

            // Asked directly, because counting teardowns cannot see it:
            // the release really happens under the defect, but a driver is
            // only armed once the previous timer has ended, so the extra
            // teardown lands outside any reasonable window. What is
            // immediately true is that the claim has been taken away from
            // the attempt still running in it.
            val second = recovery.standDown("second_shutdown")
            assertTrue(second is HandoffRecovery.StandDown.SettlementOwed, "$steps")
            assertTrue(
                (second as HandoffRecovery.StandDown.SettlementOwed).inFlight != null,
                "a stale report released the claim of the attempt that replaced it: a " +
                    "later shutdown now finds the obligation free while a teardown is " +
                    "still running inside it, and would start another: $steps",
            )
        } finally {
            hold.complete(Unit)
            withTimeoutOrNull(3_000) {
                outliving.coroutineContext.job.children.forEach { it.join() }
            }
        }
    }

    @Test
    fun aStaleResolveCannotReleaseTheClaimThatReplacedIt() = runBlocking {
        // The same defect one layer down, on the timer's own resolve
        // rather than on a shutdown report.
        //
        // A shutdown can declare a running attempt failed - which
        // releases its claim and arms a driver - and that driver then
        // claims the SAME obligation. When the first attempt finally
        // returns, its `finally` resolves the claim it was given. With the
        // CAS on the obligation's token rather than on the attempt's, that
        // resolve released the second attempt's claim while it was still
        // inside its teardown.
        val steps = steps()
        val secondEntered = CompletableDeferred<Unit>()
        val holdFirst = CompletableDeferred<Unit>()
        val holdSecond = CompletableDeferred<Unit>()
        val next = AtomicLong()
        val ownership = ConnectOwnership(nextToken = { next.incrementAndGet() })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)
        ownership.raiseClaimFence("privacy_mode_change", epoch)
        val outliving = scope()

        var live: HandoffRecovery? = null
        val settle: suspend (Long) -> Boolean = { owedEpoch ->
            settleDeferredPrivacySwitch(
                epoch = owedEpoch,
                coordinator = policy,
                raiseClaimFence = { ownership.raiseClaimFence("deferred", owedEpoch) },
                lowerClaimFence = { ownership.lowerClaimFence("deferred", owedEpoch) },
                retryTeardown = {
                    val attempt = steps.count { it == "teardown" } + 1
                    steps.add("teardown")
                    withContext(NonCancellable) {
                        if (attempt == 1) holdFirst.await()
                        if (attempt == 2) { secondEntered.complete(Unit); holdSecond.await() }
                    }
                    TeardownAttempt(walkQuiesced = false, confirmed = false)
                },
                startSuccessor = {
                    live!!.startSuccessorUnlessShutDown {
                        steps.add("successor_started"); true
                    }
                },
            ).isDischarged
        }
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { steps.add("bare_start"); true },
            intervalMs = 60L,
            finishDeferredSwitch = { owedEpoch -> settle(owedEpoch) },
        )
        live = recovery

        try {
            // The timer claims the obligation and parks in its teardown.
            recovery.armDeferredSettlement("privacy_switch_teardown_incomplete", epoch)
            await("the first attempt to start: $steps") { steps.contains("teardown") }

            // A shutdown takes that running attempt and declares it failed,
            // which hands the claim back and arms a driver.
            val owed = recovery.standDown("service_destroyed")
            assertTrue(owed is HandoffRecovery.StandDown.SettlementOwed)
            val firstClaim = (owed as HandoffRecovery.StandDown.SettlementOwed).claimToken
            assertNotNull(owed.inFlight, "precondition: the timer owns this claim")
            recovery.noteShutdownSettlement(firstClaim, settled = false)

            // The driver claims the SAME obligation and parks in its own.
            recovery.resume("service_created")
            secondEntered.await()

            // Only now does the FIRST attempt return and resolve.
            holdFirst.complete(Unit)
            delay(250)

            val third = recovery.standDown("third_shutdown")
            assertTrue(third is HandoffRecovery.StandDown.SettlementOwed, "$steps")
            assertTrue(
                (third as HandoffRecovery.StandDown.SettlementOwed).inFlight != null,
                "the first attempt's resolve released the claim held by the second, " +
                    "which is still inside its teardown: $steps",
            )
        } finally {
            holdFirst.complete(Unit)
            holdSecond.complete(Unit)
            withTimeoutOrNull(3_000) {
                outliving.coroutineContext.job.children.forEach { it.join() }
            }
        }
    }
}
