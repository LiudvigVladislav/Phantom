// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R-N1.16 P1-3 - the recovery chain, as behaviour rather than as
 * statement order.
 *
 * Review was right that a source tripwire showing `recoverIfBlocked`
 * above `retryScheduler.claim` proves nothing about whether a blocked
 * lease with an empty retry slot actually recovers. These fixtures drive
 * the real chain: blocked ownership, a signal, a confirmed join, and
 * exactly one ordinary connect.
 */
class HandoffRecoveryTest {

    private class FakeWalk(var finishes: Boolean = true) : ConnectWalkHandle {
        var cancelCalls = 0
            private set

        override suspend fun cancelAndJoin(timeoutMs: Long): Boolean {
            cancelCalls += 1
            return finishes
        }
    }

    /** Records every ordinary connect the recovery decides to start. */
    private class Starts {
        val reasons = mutableListOf<String>()
        val count: Int get() = reasons.size
    }

    private suspend fun blockedOwnership(walk: FakeWalk): Pair<ConnectOwnership, Long> {
        var next = 0L
        val own = ConnectOwnership(nextToken = { ++next }, handoverTimeoutMs = 1_000L)
        val token = own.claim("generation_A", walk)
        assertNotNull(token)
        assertTrue(
            own.handOver("network_rewalk") is Handover.TimedOut,
            "precondition: the handover could not confirm quiescence",
        )
        assertNotNull(own.blockedReason(), "precondition: the lease is fail-closed")
        return own to token
    }

    // -- the whole chain -----------------------------------------------

    @Test
    fun aNudgeOnABlockedLeaseRecoversItAndStartsExactlyOneConnect() = runTest {
        // The scenario review asked for, end to end:
        //   manager Idle + ownership blocked + retry slot empty
        //     -> nudge
        //     -> confirmed join
        //     -> exactly one ordinary connect.
        //
        // Note what is NOT consulted anywhere in this path: the retry
        // scheduler. That is the point - after a rewalk the retry slot is
        // invalidated, so any recovery that went through the scheduler
        // would be refused and the lease would stay blocked for good.
        val walk = FakeWalk(finishes = false)
        val (own, token) = blockedOwnership(walk)
        val starts = Starts()
        val recovery = HandoffRecovery(
            ownership = own,
            scope = this,
            startOneOrdinaryConnect = { starts.reasons += it; true },
        )

        // The displaced walk has since finished; the join can confirm it.
        walk.finishes = true
        val signal = recovery.onSignal("alarm_heartbeat")

        assertEquals(Handover.Quiesced(token).previousOwner, (signal as HandoffRecovery.Signal.Recovered).previousOwner)
        assertNull(own.blockedReason(), "the block is lifted")
        assertEquals(1, starts.count, "exactly one ordinary connect is started")
        assertEquals(listOf("alarm_heartbeat"), starts.reasons)
        assertNotNull(
            own.claim("the_connect_that_was_started", FakeWalk()),
            "and that connect can actually claim the lease",
        )
    }

    @Test
    fun aNudgeThatCannotConfirmQuiescenceStartsNothing() = runTest {
        val walk = FakeWalk(finishes = false)
        val (own, token) = blockedOwnership(walk)
        val starts = Starts()
        val recovery = HandoffRecovery(own, this, { starts.reasons += it; true })

        val signal = recovery.onSignal("alarm_heartbeat")

        assertEquals(HandoffRecovery.Signal.StillBlocked, signal)
        assertEquals(0, starts.count, "nothing may be started while the walk may run")
        assertEquals(token, own.currentOwner(), "and the lease stays held")
        assertNotNull(own.blockedReason())
    }

    @Test
    fun aSignalOnAHealthyLeaseDoesNothingAtAll() = runTest {
        // onSignal is called on every allowed signal, so the common path
        // must be free of side effects.
        var next = 0L
        val own = ConnectOwnership(nextToken = { ++next })
        val walk = FakeWalk()
        val token = own.claim("generation_A", walk)
        assertNotNull(token)
        val starts = Starts()
        val recovery = HandoffRecovery(own, this, { starts.reasons += it; true })

        assertEquals(HandoffRecovery.Signal.NotBlocked, recovery.onSignal("onStartCommand"))
        assertEquals(0, starts.count, "a healthy lease must not be given a second connect")
        assertEquals(token, own.currentOwner())
        assertEquals(0, walk.cancelCalls, "nor may its walk be disturbed")
    }

    @Test
    fun theTimerRecoversWithoutAnyExternalSignalAtAll() = runTest {
        // The primary route. On a stable network no nudge ever arrives,
        // which is exactly why the timer exists.
        val walk = FakeWalk(finishes = false)
        val (own, _) = blockedOwnership(walk)
        val starts = Starts()
        val recovery = HandoffRecovery(
            ownership = own,
            scope = this,
            startOneOrdinaryConnect = { starts.reasons += it; true },
            intervalMs = 1_000L,
        )

        recovery.arm("handoff_timeout")
        advanceTimeBy(1_100L)
        runCurrent()
        assertEquals(0, starts.count, "still blocked, so still nothing started")
        assertTrue(recovery.isArmed(), "and the timer keeps trying")

        walk.finishes = true
        advanceTimeBy(1_100L)
        runCurrent()

        assertEquals(1, starts.count, "one ordinary connect once the join confirms")
        assertNull(own.blockedReason())
        assertFalse(recovery.isArmed(), "and the timer stands down afterwards")
    }

    @Test
    fun theTimerStartsExactlyOneConnectEvenIfItKeepsTicking() = runTest {
        val walk = FakeWalk(finishes = false)
        val (own, _) = blockedOwnership(walk)
        val starts = Starts()
        val recovery = HandoffRecovery(own, this, { starts.reasons += it; true }, intervalMs = 1_000L)

        recovery.arm("handoff_timeout")
        walk.finishes = true
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(
            1,
            starts.count,
            "a recovery that fired once must not keep starting connects: $starts",
        )
    }

    // -- the two-timer race (review follow-up) --------------------------

    @Test
    fun armingTwiceLeavesExactlyOneLiveTimer() = runTest {
        val walk = FakeWalk(finishes = false)
        val (own, _) = blockedOwnership(walk)
        val starts = Starts()
        val recovery = HandoffRecovery(own, this, { starts.reasons += it; true }, intervalMs = 1_000L)

        recovery.arm("first")
        val first = recovery.liveTimer()
        assertNotNull(first)

        recovery.arm("second")
        val second = recovery.liveTimer()
        assertNotNull(second)

        runCurrent()
        assertTrue(first !== second, "re-arming replaces the timer")
        assertTrue(first.isCancelled, "and cancels the one it replaced")
        assertTrue(second.isActive, "leaving exactly one live")

        // And only one of them ever acts.
        walk.finishes = true
        advanceTimeBy(1_100L)
        runCurrent()
        assertEquals(1, starts.count, "two timers would have started two connects")
    }

    @Test
    fun aCancelledTimerDoesNotClearTheReferenceToItsReplacement() = runTest {
        // The check-then-act shape one layer up from the ownership
        // defect: the outgoing timer's teardown runs AFTER the new one is
        // installed, and an unconditional `timer = null` there would wipe
        // the live reference - leaving isArmed() false while a timer was
        // in fact running, and letting a later arm create a second.
        val walk = FakeWalk(finishes = false)
        val (own, _) = blockedOwnership(walk)
        val recovery = HandoffRecovery(own, this, { true }, intervalMs = 1_000L)

        recovery.arm("first")
        val first = recovery.liveTimer()
        assertNotNull(first)

        // The first timer must actually START before it is replaced.
        // Without this the dispatcher never runs its body, cancellation
        // completes it without executing the `finally`, and the identity
        // check is never exercised at all - the fixture then passes
        // against an implementation that clears unconditionally.
        //
        // MUT-F3-TIMER-CLEARS-UNCONDITIONALLY was NOT OBSERVED until this
        // line was added, which is the whole reason the mutation exists.
        runCurrent()
        assertTrue(first.isActive, "precondition: the first timer is running")

        recovery.arm("second")
        val second = recovery.liveTimer()
        assertNotNull(second)

        // Now let the cancelled timer's teardown actually run.
        runCurrent()
        assertTrue(first.isCompleted, "precondition: the replaced timer has torn down")

        assertTrue(
            recovery.isArmed(),
            "the replacement must still be armed after its predecessor tore down",
        )
        assertTrue(second === recovery.liveTimer(), "and it must still be the live one")

        // The surviving timer is deliberately still waiting - the walk in
        // this fixture never finishes - so stand it down rather than let
        // runTest fail on a live child.
        recovery.standDown("test_teardown")
    }

    @Test
    fun aTimerAlreadyArmedWhenShutdownBeginsStartsNothingDuringIt() = runTest {
        // Review R-N1.16: the presence of a cancel() call in onDestroy is
        // not proof. The timer may already be armed and its interval may
        // elapse WHILE the shutdown handover is running, so the race has
        // to be driven rather than asserted from source.
        //
        // Shape: armed timer, shutdown begins, time advances across
        // several intervals during the handover, and the count of
        // ordinary connects started must be exactly zero.
        val walk = FakeWalk(finishes = false)
        val (own, _) = blockedOwnership(walk)
        val starts = Starts()
        val recovery = HandoffRecovery(own, this, { starts.reasons += it; true }, intervalMs = 1_000L)

        recovery.arm("handoff_timeout")
        advanceTimeBy(1_100L)
        runCurrent()
        assertTrue(recovery.isArmed(), "precondition: a timer is armed and ticking")
        assertEquals(0, starts.count)

        // Shutdown begins. The walk becomes joinable at exactly the wrong
        // moment - during the teardown - which is the whole race.
        walk.finishes = true
        recovery.standDown("service_destroyed")
        advanceTimeBy(10_000L)
        runCurrent()

        assertFalse(recovery.isArmed(), "the timer is down")
        assertEquals(
            0,
            starts.count,
            "an explicit stop must not schedule its own restart, and recovery starts " +
                "the service unconditionally rather than through the scheduler: $starts",
        )
    }

    @Test
    fun cancellingStandsTheTimerDown() = runTest {
        val walk = FakeWalk(finishes = false)
        val (own, _) = blockedOwnership(walk)
        val starts = Starts()
        val recovery = HandoffRecovery(own, this, { starts.reasons += it; true }, intervalMs = 1_000L)

        recovery.arm("handoff_timeout")
        recovery.standDown("service_destroyed")
        assertFalse(recovery.isArmed())

        walk.finishes = true
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(
            0,
            starts.count,
            "a cancelled recovery must not start the service later - that is the " +
                "hazard the retry timer already carries a comment about",
        )
    }
}
