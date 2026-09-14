// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 2 B7b (2026-09-13): [ConnectRetryScheduler.armImmediate].
 *
 * The ladder answers "how long after a failed walk", which is the right
 * question after a walk and the wrong one in a vacuum. In a vacuum
 * nothing is scheduled, so `claim(null, ...)` answers
 * [ConnectRetryScheduler.Claim.NotPending] and every trigger goes home —
 * which is precisely what the 19:07 phone did with each 30 s alarm. The
 * coordinator arms due-now and claims in the same call instead.
 *
 * What must NOT happen is the ladder climbing on a vacuum: a vacuum is
 * not evidence that an attempt failed, and letting it advance the streak
 * would push the next genuine backoff toward the 15 min ceiling.
 */
class ConnectRetrySchedulerArmImmediateTest {

    private class Clock(var nowMs: Long = 0L)

    private fun scheduler(clock: Clock, logs: MutableList<String> = mutableListOf()) =
        ConnectRetryScheduler(nowMs = { clock.nowMs }, log = { logs.add(it) })

    @Test
    fun a_vacuum_arm_is_due_now_and_can_be_claimed_in_the_same_call() = runTest {
        val clock = Clock()
        val s = scheduler(clock)
        assertIs<ConnectRetryScheduler.Claim.NotPending>(
            s.claim(null, "before", currentGeneration = 1L),
            "the pre-Stage-2 shape: nothing scheduled, nothing to claim",
        )
        val arm = assertNotNull(s.armImmediate(generation = 1L, reason = "vacuum"))
        assertEquals(0L, arm.delayMs)
        val claim = s.claim(arm.epoch, "vacuum", currentGeneration = 1L)
        assertIs<ConnectRetryScheduler.Claim.Granted>(claim)
        assertEquals(arm.epoch, claim.token.epoch)
        assertEquals(1L, claim.token.generation)
    }

    @Test
    fun a_vacuum_arm_does_not_advance_the_backoff_ladder() = runTest {
        val clock = Clock()
        val s = scheduler(clock)
        repeat(3) { s.armImmediate(generation = 1L, reason = "vacuum") }
        assertEquals(0, s.snapshot().streak, "a vacuum is not a failed attempt")
        // The first genuine failure still gets the first rung.
        val armed = assertNotNull(s.armAfterFailure(generation = 1L))
        assertEquals(1, armed.streak)
        assertEquals(ConnectRetryScheduler.BACKOFF_LADDER_MS.first(), armed.delayMs)
    }

    @Test
    fun a_stale_generation_may_not_arm_immediately() = runTest {
        val clock = Clock()
        val logs = mutableListOf<String>()
        val s = scheduler(clock, logs)
        assertNull(
            s.armImmediate(generation = 1L, currentGeneration = 2L, reason = "vacuum"),
            "a superseded generation would put a timer on top of live work",
        )
        assertTrue(logs.any { it.contains("arm_immediate_refused") }, "logs=$logs")
        assertNull(s.snapshot().dueAtMs)
    }

    @Test
    fun re_arming_immediately_supersedes_the_previous_timer() = runTest {
        val clock = Clock()
        val s = scheduler(clock)
        val first = assertNotNull(s.armAfterFailure(generation = 1L))
        val second = assertNotNull(s.armImmediate(generation = 1L, reason = "vacuum"))
        assertTrue(second.epoch > first.epoch, "the epoch must advance so the old timer cannot claim")
        assertIs<ConnectRetryScheduler.Claim.Stale>(s.claim(first.epoch, "old_timer", 1L))
        assertIs<ConnectRetryScheduler.Claim.Granted>(s.claim(second.epoch, "vacuum", 1L))
    }

    @Test
    fun a_claimed_vacuum_grant_can_be_restored_and_is_due_at_once() = runTest {
        val clock = Clock()
        val s = scheduler(clock)
        val arm = assertNotNull(s.armImmediate(generation = 1L, reason = "vacuum"))
        val granted = assertIs<ConnectRetryScheduler.Claim.Granted>(s.claim(arm.epoch, "vacuum", 1L))
        assertNull(s.snapshot().dueAtMs, "claiming clears the slot")
        assertTrue(
            s.restoreAfterFailedStart(granted.token, "start_job_ended_unclaimed"),
            "a start that never claimed the lease must be able to put its grant back",
        )
        clock.nowMs += 1
        assertIs<ConnectRetryScheduler.Claim.Granted>(s.claim(null, "next_trigger", 1L))
    }

    @Test
    fun an_invalidated_slot_refuses_a_late_restore_of_a_vacuum_grant() = runTest {
        val clock = Clock()
        val s = scheduler(clock)
        val arm = assertNotNull(s.armImmediate(generation = 1L, reason = "vacuum"))
        val granted = assertIs<ConnectRetryScheduler.Claim.Granted>(s.claim(arm.epoch, "vacuum", 1L))
        s.invalidate("service_destroyed")
        assertTrue(
            !s.restoreAfterFailedStart(granted.token, "late"),
            "a shutdown cancelled this retry deliberately; a late restore must not revive it",
        )
        assertNull(s.snapshot().dueAtMs)
    }
}
