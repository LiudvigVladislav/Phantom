// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N1-F3 - the retry policy that gets the app out of
 * [ManagerState.AllFailed].
 *
 * Time and jitter are injected, so every delay here is exact rather than
 * approximate: a fixture that asserted "roughly 30 s" could not tell a
 * correct ladder from a broken one.
 */
class ConnectRetrySchedulerTest {

    /** Controllable clock. Nothing in these tests waits on wall time. */
    private class FakeClock(var nowMs: Long = 1_000_000L) {
        fun advance(ms: Long) { nowMs += ms }
    }

    private fun scheduler(
        clock: FakeClock,
        jitter: () -> Double = { 1.0 },
        log: MutableList<String>? = null,
    ) = ConnectRetryScheduler(
        nowMs = { clock.nowMs },
        jitterFactor = jitter,
        log = log?.let { sink -> { line -> sink += line } },
    )

    /**
     * Arm as the CURRENT generation. `armAfterFailure` returns null when
     * the arming generation has been superseded -- review finding P1-2 --
     * which every fixture here except the stale-generation ones relies on
     * NOT happening.
     */
    private suspend fun ConnectRetryScheduler.arm(generation: Long): ConnectRetryScheduler.Arm =
        armAfterFailure(generation, generation)
            ?: error("expected the arm to be granted for generation $generation")

    // ── the defect itself ────────────────────────────────────────────

    @Test
    fun allFailedWithNoNetworkChange_stillProducesANextAttempt() = runTest {
        // The whole point. Nothing here simulates a network transition:
        // the network is assumed unchanged, which is exactly the case the
        // observer's classify() calls "not meaningful" and skips. The
        // retry must happen anyway.
        val clock = FakeClock()
        val s = scheduler(clock)

        val arm = s.arm(7L)
        assertEquals(30_000L, arm.delayMs, "first retry is the first rung of the ladder")

        assertTrue(
            s.claim(arm.epoch, "timer") is ConnectRetryScheduler.Claim.NotDue,
            "not claimable before the delay elapses",
        )

        clock.advance(30_000L)
        assertIs<ConnectRetryScheduler.Claim.Granted>(
            s.claim(arm.epoch, "timer"),
            "once due, the attempt is granted -- this is the recovery that did not exist",
        )
    }

    @Test
    fun theDelayIsMeasuredFromWhenTheAttemptEnded() = runTest {
        // A chain walk has no proven upper bound: Tor prepare walks
        // BRIDGE_ROTATION_ORDER, 600 + 420 + 180 + 60 = 1260 s, before its
        // 90 s probe, and tor.start() is outside the timeout that bounds
        // the state wait. So the schedule must start when the attempt
        // RETURNS.
        val clock = FakeClock()
        val s = scheduler(clock)

        clock.advance(1_410_000L) // a Private walk at its nominal ceiling
        val arm = s.arm(1L)

        clock.advance(29_999L)
        assertTrue(
            s.claim(arm.epoch, "timer") is ConnectRetryScheduler.Claim.NotDue,
            "the walk's own duration must not count towards the backoff",
        )
        clock.advance(1L)
        assertIs<ConnectRetryScheduler.Claim.Granted>(s.claim(arm.epoch, "timer"))
    }

    // ── the ladder ───────────────────────────────────────────────────

    @Test
    fun theBackoffLadderClimbsAndThenHolds() = runTest {
        val clock = FakeClock()
        val s = scheduler(clock)
        val seen = mutableListOf<Long>()

        repeat(6) {
            val arm = s.arm(it.toLong())
            seen += arm.delayMs
            clock.advance(arm.delayMs)
            s.claim(arm.epoch, "timer")
        }

        assertEquals(
            listOf(30_000L, 120_000L, 300_000L, 900_000L, 900_000L, 900_000L),
            seen,
            "30 s, 2 min, 5 min, then 15 min held as the ceiling",
        )
    }

    @Test
    fun jitterMovesTheDelayWithinTwentyPercentInBothDirections() = runTest {
        val clock = FakeClock()

        val low = scheduler(clock, jitter = { ConnectRetryScheduler.JITTER_MIN_FACTOR })
            .arm(1L).delayMs
        val high = scheduler(FakeClock(), jitter = { ConnectRetryScheduler.JITTER_MAX_FACTOR })
            .arm(1L).delayMs

        assertEquals(24_000L, low, "-20 % of 30 s")
        assertEquals(36_000L, high, "+20 % of 30 s")
    }

    // ── single flight ────────────────────────────────────────────────

    @Test
    fun manySignalsProduceExactlyOneGrantedAttempt() = runTest {
        // The alarm heartbeat fires every 30 s, onStartCommand can arrive
        // at any time, and the armed timer is also live. All three are
        // signals for the same retry; exactly one may proceed.
        val clock = FakeClock()
        val s = scheduler(clock)
        val arm = s.arm(3L)
        clock.advance(arm.delayMs)

        val outcomes = listOf(
            s.claim(arm.epoch, "timer"),
            s.claim(null, "alarm_nudge"),
            s.claim(null, "alarm_nudge_duplicate"),
            s.claim(arm.epoch, "timer_again"),
        )

        assertEquals(
            1,
            outcomes.count { it is ConnectRetryScheduler.Claim.Granted },
            "exactly one claim may be granted: $outcomes",
        )
        assertEquals(
            3,
            outcomes.count { it == ConnectRetryScheduler.Claim.NotPending },
            "every later signal finds the slot already taken",
        )
    }

    @Test
    fun anAlarmNudgeCanClaimWithoutKnowingTheEpoch() = runTest {
        // The receiver has no epoch to present. It must still be able to
        // deliver a due retry -- otherwise the heartbeat is decorative
        // again, which is how the original defect looked.
        val clock = FakeClock()
        val s = scheduler(clock)
        val arm = s.arm(1L)

        assertTrue(s.claim(null, "nudge") is ConnectRetryScheduler.Claim.NotDue)
        clock.advance(arm.delayMs)
        assertIs<ConnectRetryScheduler.Claim.Granted>(s.claim(null, "nudge"))
    }

    // ── invalidation ─────────────────────────────────────────────────

    @Test
    fun aNetworkRewalkInvalidatesThePendingRetry() = runTest {
        val clock = FakeClock()
        val s = scheduler(clock)
        val arm = s.arm(1L)

        s.invalidate("network_rewalk")
        clock.advance(arm.delayMs * 10)

        val claim = s.claim(arm.epoch, "timer")
        assertTrue(
            claim is ConnectRetryScheduler.Claim.Stale,
            "the rewalk owns recovery now; the retry must not also fire: $claim",
        )
        assertNull(s.snapshot().dueAtMs, "and nothing is left scheduled")
        assertTrue(
            s.claim(null, "nudge") is ConnectRetryScheduler.Claim.NotPending,
            "an epoch-less nudge finds nothing to claim either",
        )
    }

    @Test
    fun aStaleTimerCannotClaimAfterANewerArm() = runTest {
        // Generation A arms, something invalidates, generation B arms.
        // A's timer must not be able to start a connect.
        val clock = FakeClock()
        val s = scheduler(clock)
        val armA = s.arm(1L)
        s.invalidate("privacy_mode_changed")
        val armB = s.arm(2L)
        clock.advance(armB.delayMs * 4)

        val stale = s.claim(armA.epoch, "stale_timer")
        assertTrue(
            stale is ConnectRetryScheduler.Claim.Stale,
            "the old epoch must be refused outright: $stale",
        )
        assertIs<ConnectRetryScheduler.Claim.Granted>(
            s.claim(armB.epoch, "current_timer"),
            "while the current generation's timer still works",
        )
    }

    @Test
    fun reArmingSupersedesTheEarlierTimer() = runTest {
        val clock = FakeClock()
        val s = scheduler(clock)
        val first = s.arm(1L)
        val second = s.arm(1L)

        clock.advance(second.delayMs * 2)
        assertTrue(
            s.claim(first.epoch, "superseded") is ConnectRetryScheduler.Claim.Stale,
            "an arm replaced by a later arm cannot fire",
        )
        assertIs<ConnectRetryScheduler.Claim.Granted>(s.claim(second.epoch, "current"))
    }

    @Test
    fun shutdownCancelsThePendingRetryAndNothingRevivesIt() = runTest {
        val clock = FakeClock()
        val s = scheduler(clock)
        val arm = s.arm(1L)

        s.invalidate("service_destroyed")
        clock.advance(arm.delayMs * 100)

        assertTrue(s.claim(arm.epoch, "timer_after_destroy") is ConnectRetryScheduler.Claim.Stale)
        assertTrue(s.claim(null, "nudge_after_destroy") is ConnectRetryScheduler.Claim.NotPending)
        assertNull(s.snapshot().dueAtMs, "no retry may survive a shutdown")
    }

    // ── success ──────────────────────────────────────────────────────

    @Test
    fun aSuccessfulOuterConnectResetsTheBackoff() = runTest {
        val clock = FakeClock()
        val s = scheduler(clock)
        repeat(3) {
            val a = s.arm(it.toLong())
            clock.advance(a.delayMs)
            s.claim(a.epoch, "timer")
        }
        assertEquals(3, s.snapshot().streak)

        s.onOuterConnectSucceeded()
        assertEquals(0, s.snapshot().streak)
        assertNull(s.snapshot().dueAtMs)

        assertEquals(
            30_000L,
            s.arm(9L).delayMs,
            "the next failure starts the ladder again from the bottom",
        )
    }

    @Test
    fun aSuccessInvalidatesAnyTimerStillOutstanding() = runTest {
        val clock = FakeClock()
        val s = scheduler(clock)
        val arm = s.arm(1L)

        s.onOuterConnectSucceeded()
        clock.advance(arm.delayMs * 5)

        assertTrue(
            s.claim(arm.epoch, "timer") is ConnectRetryScheduler.Claim.Stale,
            "a retry armed before the connection came up must not tear it down",
        )
    }

    @Test
    fun invalidationKeepsTheStreakSoRepeatedRewalksCannotPinTheBackoffLow() = runTest {
        val clock = FakeClock()
        val s = scheduler(clock)
        s.arm(1L)
        s.arm(1L)

        s.invalidate("network_rewalk")

        assertEquals(
            2,
            s.snapshot().streak,
            "an invalidation is not evidence the network recovered",
        )
        assertEquals(
            300_000L,
            s.arm(1L).delayMs,
            "streak 3 takes the third rung -- the ladder keeps climbing instead " +
                "of resetting to 30 s",
        )
    }


    // -- review R-N1.16: the interleavings the first attempt missed ----

    @Test
    fun aSupersededGenerationCannotArmAtAll() = runTest {
        // Review P1-2. Generation 1's walk ends in AllFailed AFTER
        // generation 2 has claimed the token. If 1 were allowed to arm,
        // its timer would carry the NEWEST epoch -- so epoch checking
        // alone would let it through and put a retry on top of live work.
        val clock = FakeClock()
        val s = scheduler(clock)

        val stale = s.armAfterFailure(generation = 1L, currentGeneration = 2L)

        assertNull(stale, "a superseded generation must not be able to schedule anything")
        assertNull(s.snapshot().dueAtMs, "and must leave nothing pending")
    }

    @Test
    fun aPendingRetryArmedByASupersededGenerationCannotBeClaimed() = runTest {
        // The same defect one step later: the arm was legitimate when it
        // happened, but a newer connect generation has since taken over.
        val clock = FakeClock()
        val s = scheduler(clock)
        val arm = s.arm(1L)
        clock.advance(arm.delayMs)

        val claim = s.claim(arm.epoch, "timer", currentGeneration = 2L)
        assertTrue(
            claim is ConnectRetryScheduler.Claim.StaleGeneration,
            "generation is authoritative, not just epoch: $claim",
        )

        assertIs<ConnectRetryScheduler.Claim.Granted>(
            s.claim(arm.epoch, "timer", currentGeneration = 1L),
            "while the generation that armed it may still claim",
        )
    }

    @Test
    fun aFailedServiceStartDoesNotSwallowTheRetry() = runTest {
        // Review P2-4. claim() clears the slot before the start is
        // attempted. If the start throws and nothing puts the claim back,
        // every later nudge sees NotPending -- the original latch, through
        // the back door.
        val clock = FakeClock()
        val s = scheduler(clock)
        val arm = s.arm(1L)
        clock.advance(arm.delayMs)

        val granted = s.claim(arm.epoch, "timer")
        assertIs<ConnectRetryScheduler.Claim.Granted>(granted)
        assertTrue(
            s.claim(null, "nudge") is ConnectRetryScheduler.Claim.NotPending,
            "precondition: the slot really was cleared",
        )

        assertTrue(
            s.restoreAfterFailedStart(granted.token, "start_threw"),
            "the token is current, so the restore is accepted",
        )

        assertIs<ConnectRetryScheduler.Claim.Granted>(
            s.claim(null, "nudge_after_restore"),
            "a start that failed must leave the retry claimable again",
        )
    }

    @Test
    fun restoringDoesNotResurrectAnInvalidatedRetry() = runTest {
        // Restore must not undo a shutdown or a rewalk.
        //
        // The first version of this fixture was VACUOUS for exactly that
        // property: after the restore it claimed with the old arm epoch
        // and got `Stale` from the epoch check, which is true whether or
        // not the slot was resurrected. A restore that reinstated a
        // cancelled retry stayed invisible. It now inspects the slot
        // directly and asks an epoch-less nudge, which is what a real
        // alarm sends.
        val clock = FakeClock()
        val s = scheduler(clock)
        val arm = s.arm(1L)
        clock.advance(arm.delayMs)
        val granted = s.claim(arm.epoch, "timer")
        assertIs<ConnectRetryScheduler.Claim.Granted>(granted)

        s.invalidate("service_destroyed")
        val restored = s.restoreAfterFailedStart(granted.token, "start_threw_after_destroy")

        assertFalse(restored, "a restore after an invalidation must be refused")
        assertNull(
            s.snapshot().dueAtMs,
            "and must leave the slot EMPTY -- this is what the old fixture could not see",
        )
        assertTrue(
            s.claim(null, "nudge_after_destroy") is ConnectRetryScheduler.Claim.NotPending,
            "an epoch-less nudge, which is what the alarm actually sends, must find " +
                "nothing to claim",
        )
    }

    @Test
    fun restoringIsRefusedAfterANewerArmReplacedTheSlot() = runTest {
        // The other way a late restore could corrupt state: the claim was
        // granted, then a fresh failure armed again. Putting the old
        // claim back would overwrite a live schedule.
        val clock = FakeClock()
        val s = scheduler(clock)
        val first = s.arm(1L)
        clock.advance(first.delayMs)
        val granted = s.claim(first.epoch, "timer")
        assertIs<ConnectRetryScheduler.Claim.Granted>(granted)

        val second = s.arm(1L)
        val dueOfSecond = s.snapshot().dueAtMs

        val restored = s.restoreAfterFailedStart(granted.token, "late_start_failure")

        assertFalse(restored, "the old claim must not overwrite a newer schedule")
        assertEquals(dueOfSecond, s.snapshot().dueAtMs, "the newer schedule is untouched")
        assertIs<ConnectRetryScheduler.Claim.NotDue>(s.claim(second.epoch, "timer"))
    }

    @Test
    fun restoreIsIdempotentAndNeverOverwritesALiveSchedule() = runTest {
        // Two failed starts in a row must not stack, and a restore must
        // not shorten a retry that is already scheduled further out.
        val clock = FakeClock()
        val s = scheduler(clock)
        val arm = s.arm(1L)

        val dueBefore = s.snapshot().dueAtMs
        s.restoreAfterFailedStart(
            ConnectRetryScheduler.ClaimToken(epoch = arm.epoch, generation = 1L),
            "spurious",
        )
        assertEquals(
            dueBefore,
            s.snapshot().dueAtMs,
            "a restore must not pull a live schedule forward",
        )

        clock.advance(arm.delayMs)
        val g = s.claim(arm.epoch, "timer")
        assertIs<ConnectRetryScheduler.Claim.Granted>(g)
        s.restoreAfterFailedStart(g.token, "first")
        val after = s.snapshot().dueAtMs
        s.restoreAfterFailedStart(g.token, "second")
        assertEquals(after, s.snapshot().dueAtMs, "restoring twice changes nothing")
    }

    // ── observability ────────────────────────────────────────────────

    @Test
    fun everyDecisionIsTraceable() = runTest {
        val clock = FakeClock()
        val log = mutableListOf<String>()
        val s = scheduler(clock, log = log)

        val arm = s.arm(5L)
        s.claim(arm.epoch, "timer")
        clock.advance(arm.delayMs)
        s.claim(arm.epoch, "timer")
        s.invalidate("test")

        assertTrue(log.any { it.startsWith("RETRY_TRACE armed") })
        assertTrue(log.any { it.contains("claim_rejected") && it.contains("not_due") })
        assertTrue(log.any { it.startsWith("RETRY_TRACE claim_granted") })
        assertTrue(log.any { it.startsWith("RETRY_TRACE invalidated") })
    }
}
