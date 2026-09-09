// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N1-F3 - the interleavings that the old check-then-act ownership got
 * wrong, the ones that revoking a token alone still got wrong, and the
 * one that survived splitting the claim from the registration.
 *
 * These are deterministic: the operations are called in the order the
 * race would have produced them, so nothing depends on thread timing.
 * A racing-threads test for this would pass almost always and prove
 * almost nothing.
 */
class ConnectOwnershipTest {

    private fun ownership(
        log: MutableList<String>? = null,
        handoverTimeoutMs: Long = ConnectOwnership.DEFAULT_HANDOVER_TIMEOUT_MS,
    ): ConnectOwnership {
        var next = 0L
        return ConnectOwnership(
            nextToken = { ++next },
            handoverTimeoutMs = handoverTimeoutMs,
            log = log?.let { sink -> { line -> sink += line } },
        )
    }

    /** A walk whose join throws, to exercise the failure settle. */
    private class ThrowingWalk(var throwable: Throwable?) : ConnectWalkHandle {
        override suspend fun cancelAndJoin(timeoutMs: Long): Boolean {
            throwable?.let { throw it }
            return true
        }
    }

    /** A walk that finishes on demand, so joins are deterministic. */
    private class FakeWalk(
        /** Flipped by a test to model a walk that has since finished. */
        var finishes: Boolean = true,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : ConnectWalkHandle {
        var cancelCalls = 0
            private set
        var lastTimeoutMs: Long? = null
            private set

        override suspend fun cancelAndJoin(timeoutMs: Long): Boolean {
            cancelCalls += 1
            lastTimeoutMs = timeoutMs
            gate?.await()
            return finishes
        }
    }

    // -- the first race: a displaced owner clobbering its successor ----

    @Test
    fun aDisplacedOwnerCannotReleaseTheSlotItNoLongerHolds() = runTest {
        val own = ownership()

        val a = own.claim("generation_A", FakeWalk())
        assertNotNull(a)

        assertTrue(own.handOver("network_rewalk") is Handover.Quiesced)
        val b = own.claim("generation_B", FakeWalk())
        assertNotNull(b)

        val released = own.release(a, "A_cleanup_after_displacement")

        assertFalse(released, "a displaced owner's release must be refused")
        assertEquals(b, own.currentOwner(), "and B must still hold the slot")
        assertNull(
            own.claim("third_start", FakeWalk()),
            "so a third start cannot slip in and open a second chain walk",
        )
    }

    @Test
    fun theOwnerReleasesSuccessfullyWhenNothingIntervened() = runTest {
        val own = ownership()
        val a = own.claim("generation_A", FakeWalk())
        assertNotNull(a)

        assertTrue(own.release(a, "normal_cleanup"), "the true owner may release")
        assertNull(own.currentOwner(), "and the slot is free afterwards")
        assertNotNull(own.claim("next_start", FakeWalk()), "so the next start can take it")
    }

    // -- single flight -------------------------------------------------

    @Test
    fun onlyOneClaimSucceedsWhileTheSlotIsHeld() = runTest {
        val own = ownership()
        val first = own.claim("first", FakeWalk())
        assertNotNull(first)

        assertNull(own.claim("second", FakeWalk()), "a walk is running; the second start is refused")
        assertNull(own.claim("third", FakeWalk()), "and so is the third")
        assertEquals(first, own.currentOwner())
    }

    @Test
    fun arefusedClaimDoesNotConsumeAToken() = runTest {
        val own = ownership()
        val first = own.claim("first", FakeWalk())
        assertNotNull(first)
        own.claim("refused", FakeWalk())
        own.claim("refused_again", FakeWalk())
        assertTrue(own.release(first, "cleanup"))

        assertEquals(
            first + 1,
            own.claim("next", FakeWalk()),
            "the next successful claim takes the very next token; refused claims " +
                "minted nothing",
        )
    }

    @Test
    fun arefusedClaimDoesNotRegisterItsWalkEither() = runTest {
        // A refused claim must leave the incumbent's registration alone.
        // If it overwrote `walk`, a later handover would cancel a walk
        // that never started and let the real one run on unowned.
        val own = ownership()
        val incumbent = FakeWalk()
        val a = own.claim("generation_A", incumbent)
        assertNotNull(a)

        val loser = FakeWalk()
        assertNull(own.claim("generation_B", loser))

        assertTrue(own.handOver("network_rewalk") is Handover.Quiesced)
        assertEquals(1, incumbent.cancelCalls, "the handover stopped the walk that ran")
        assertEquals(0, loser.cancelCalls, "and not the one that never started")
    }

    // -- the claim/attach window (review R-N1.16 P1-1) -----------------

    @Test
    fun aLeaseIsNeverHeldByAWalkTheHandoverCannotSee() = runTest {
        // The window review found: claiming the lease and registering the
        // walk used to be two calls. A handover could capture `walk ==
        // null` from a lease claimed a moment earlier, conclude there was
        // nothing to stop, hand the slot to a successor - and the first
        // walk would then register itself and keep running, owned by
        // nobody, beside its successor.
        //
        // There is no interleaving to reproduce any more, because there
        // is no gap: claim takes the handle. What this pins is the
        // property that made the gap dangerous - a handover issued at the
        // very first opportunity after a claim still sees the walk.
        val own = ownership()
        val walk = FakeWalk()
        val a = own.claim("generation_A", walk)
        assertNotNull(a)

        val outcome = own.handOver("rewalk_immediately_after_claim")

        assertEquals(1, walk.cancelCalls, "the handover must see and stop the walk")
        assertEquals(Handover.Quiesced(a), outcome)
    }

    @Test
    fun everyGrantedLeaseCarriesItsWalkFromTheFirstInstant() = runTest {
        // The same property across a handover chain: no generation ever
        // holds the lease with nothing registered under it.
        val own = ownership()
        val first = FakeWalk()
        val a = own.claim("A", first)
        assertNotNull(a)
        assertTrue(own.handOver("rewalk_1") is Handover.Quiesced)

        val second = FakeWalk()
        val b = own.claim("B", second)
        assertNotNull(b)
        assertTrue(own.handOver("rewalk_2") is Handover.Quiesced)

        assertEquals(1, first.cancelCalls, "A's walk was visible to its handover")
        assertEquals(1, second.cancelCalls, "and so was B's")
    }

    // -- the second race: the token moved but the work did not ---------

    @Test
    fun aHandoverStopsTheWalkBeforeTheSlotIsReleased() = runTest {
        // R-N1.16 P1-1. Revoking the token is not stopping the work.
        // The handover must cancel and JOIN the displaced walk, and only
        // then let the slot go.
        val own = ownership()
        val walk = FakeWalk()
        val a = own.claim("generation_A", walk)
        assertNotNull(a)

        val outcome = own.handOver("network_rewalk")

        assertEquals(1, walk.cancelCalls, "the displaced walk must be cancelled")
        assertEquals(
            ConnectOwnership.DEFAULT_HANDOVER_TIMEOUT_MS,
            walk.lastTimeoutMs,
            "and joined under a bounded budget",
        )
        assertEquals(Handover.Quiesced(a), outcome)
        assertNull(own.currentOwner(), "only then is the slot free")
        assertNotNull(own.claim("rewalk_restart", FakeWalk()))
    }

    @Test
    fun noSuccessorCanClaimWhileTheHandoverIsStillJoining() = runTest {
        // The window R-N1.16 found: between "cancel" and "finished" the
        // old code had already published a free slot.
        val gate = CompletableDeferred<Unit>()
        val own = ownership()
        val a = own.claim("generation_A", FakeWalk(gate = gate))
        assertNotNull(a)

        var outcome: Handover? = null
        launch { outcome = own.handOver("network_rewalk") }
        runCurrent()

        assertNull(outcome, "precondition: the handover is parked inside the join")
        assertNull(
            own.claim("successor_during_join", FakeWalk()),
            "a successor must not enter while the displaced walk may still be " +
                "running - that is two concurrent chain walks",
        )
        assertEquals(a, own.currentOwner(), "the lease is not published as free yet")

        gate.complete(Unit)
        runCurrent()

        assertEquals(Handover.Quiesced(a), outcome)
        assertNotNull(
            own.claim("successor_after_join", FakeWalk()),
            "and only now may B enter",
        )
    }

    @Test
    fun theDisplacedWalkCannotFreeTheSlotOnItsWayOut() = runTest {
        // A cancelled walk runs its own cleanup, which calls release().
        // If that release freed the slot, a successor could start before
        // the walk had finished - the defect, reached by another path.
        val gate = CompletableDeferred<Unit>()
        val own = ownership()
        val a = own.claim("generation_A", FakeWalk(gate = gate))
        assertNotNull(a)

        launch { own.handOver("network_rewalk") }
        runCurrent()

        assertFalse(
            own.release(a, "cancelled_walk_cleanup"),
            "the displaced walk's own cleanup must not release the slot while the " +
                "handover is deciding",
        )
        assertEquals(a, own.currentOwner())
        assertNull(own.claim("successor", FakeWalk()), "so no successor slips through that door")

        gate.complete(Unit)
        runCurrent()
        assertNull(own.currentOwner())
    }

    // -- fail-closed on a handover timeout ------------------------------

    @Test
    fun aHandoverThatTimesOutKeepsTheLeaseAndRefusesEverySuccessor() = runTest {
        // Availability yields to transport integrity: if we cannot prove
        // the old walk stopped, we do not start a new one.
        val log = mutableListOf<String>()
        val own = ownership(log, handoverTimeoutMs = 1_500L)
        val a = own.claim("generation_A", FakeWalk(finishes = false))
        assertNotNull(a)

        val outcome = own.handOver("network_rewalk")

        assertEquals(Handover.TimedOut(a, 1_500L), outcome)
        assertEquals(a, own.currentOwner(), "the lease is KEPT, not force-released")
        assertNull(
            own.claim("successor_after_timeout", FakeWalk()),
            "and no successor may start",
        )
        assertNull(own.claim("another_successor", FakeWalk()), "not on a later signal either")
        assertEquals("network_rewalk", own.blockedReason())
        assertTrue(
            log.any { it.startsWith("OWNERSHIP handoff_timeout") },
            "the refusal must be a structured, greppable event: $log",
        )
    }

    @Test
    fun aTimedOutHandoverDoesNotLetTheOwnerReleaseItsWayOut() = runTest {
        val own = ownership(handoverTimeoutMs = 1_000L)
        val a = own.claim("generation_A", FakeWalk(finishes = false))
        assertNotNull(a)
        assertTrue(own.handOver("network_rewalk") is Handover.TimedOut)

        assertFalse(
            own.release(a, "late_cleanup"),
            "if release could still free the slot, fail-closed would be decorative",
        )
        assertNull(own.claim("successor", FakeWalk()))
    }

    @Test
    fun aSecondHandoverDuringAJoinIsRefusedRatherThanStacked() = runTest {
        val gate = CompletableDeferred<Unit>()
        val own = ownership()
        val a = own.claim("generation_A", FakeWalk(gate = gate))
        assertNotNull(a)

        launch { own.handOver("first_rewalk") }
        runCurrent()

        assertEquals(Handover.AlreadyInProgress, own.handOver("second_rewalk"))

        gate.complete(Unit)
        runCurrent()
        assertNull(own.currentOwner())
    }

    // -- recovery from fail-closed (review item 3) ---------------------

    @Test
    fun aBlockedLeaseIsRecoveredByTheNextSignalOnceTheWalkHasFinished() = runTest {
        // Fail-closed is only defensible if it is recoverable. Before
        // this path existed, the ONLY thing that could lift the block was
        // another network rewalk - so on a stable network the app would
        // have stayed dark permanently rather than briefly.
        val log = mutableListOf<String>()
        val own = ownership(log, handoverTimeoutMs = 1_000L)
        val stubborn = FakeWalk(finishes = false)
        val a = own.claim("generation_A", stubborn)
        assertNotNull(a)
        assertTrue(own.handOver("network_rewalk") is Handover.TimedOut)
        assertNull(own.claim("blocked", FakeWalk()), "precondition: the lease is fail-closed")

        // The walk has since finished; an ordinary signal now proves it,
        // because the join returns at once instead of timing out.
        stubborn.finishes = true
        val recovery = own.recoverIfBlocked("alarm_heartbeat")

        assertEquals(Handover.Quiesced(a), recovery)
        assertEquals(2, stubborn.cancelCalls, "the retry re-checks rather than assuming")
        assertNull(own.blockedReason())
        assertNotNull(own.claim("recovered", FakeWalk()), "and the app can connect again")
        assertTrue(
            log.any { it.startsWith("OWNERSHIP recovery_attempt") },
            "the attempt must be visible, not silent: $log",
        )
    }

    @Test
    fun recoveryStillRequiresAConfirmedJoinAndNeverJustClearsTheBlock() = runTest {
        // The block is not a flag that any signal may clear. If the walk
        // is still unstoppable, recovery must fail closed all over again.
        val own = ownership(handoverTimeoutMs = 1_000L)
        val a = own.claim("generation_A", FakeWalk(finishes = false))
        assertNotNull(a)
        assertTrue(own.handOver("network_rewalk") is Handover.TimedOut)

        assertTrue(own.recoverIfBlocked("alarm_heartbeat") is Handover.TimedOut)
        assertEquals(a, own.currentOwner(), "the lease is still held")
        assertNull(own.claim("successor", FakeWalk()), "and no successor starts")
        assertNotNull(own.blockedReason())
    }

    @Test
    fun recoverIfBlockedDoesNothingWhenNothingIsBlocked() = runTest {
        // It is called on every allowed signal, so the common path must
        // be free of side effects.
        val own = ownership()
        assertNull(own.recoverIfBlocked("onStartCommand"), "nothing was blocked")

        val walk = FakeWalk()
        val a = own.claim("generation_A", walk)
        assertNotNull(a)
        assertNull(
            own.recoverIfBlocked("onStartCommand"),
            "a healthy in-flight walk must not be disturbed by a recovery probe",
        )
        assertEquals(a, own.currentOwner(), "and it certainly must not be handed over")
        assertEquals(0, walk.cancelCalls, "nor cancelled")
    }

    // -- concurrent handovers (review item 4) --------------------------

    @Test
    fun aRefusedConcurrentHandoverActsOnNothingAtAll() = runTest {
        // Two handovers must not both stop walks or both release leases.
        // The refused one must be inert - not merely late.
        val gate = CompletableDeferred<Unit>()
        val own = ownership()
        val walk = FakeWalk(gate = gate)
        val a = own.claim("generation_A", walk)
        assertNotNull(a)

        launch { own.handOver("first_rewalk") }
        runCurrent()

        assertEquals(Handover.AlreadyInProgress, own.handOver("second_rewalk"))
        assertEquals(
            1,
            walk.cancelCalls,
            "the refused handover must not cancel the walk a second time",
        )
        assertEquals(a, own.currentOwner(), "nor release a lease it does not control")

        gate.complete(Unit)
        runCurrent()
        assertNull(own.currentOwner())
    }

    @Test
    fun aHandoverCannotStopAGenerationThatClaimedAfterItWasRefused() = runTest {
        // The dangerous shape of the same question: a refused handover
        // must not come back later and act on whoever holds the lease
        // now. It returned AlreadyInProgress, so it is finished - B is
        // reached only by a handover of its own.
        val gate = CompletableDeferred<Unit>()
        val own = ownership()
        val a = own.claim("generation_A", FakeWalk(gate = gate))
        assertNotNull(a)

        launch { own.handOver("first_rewalk") }
        runCurrent()
        assertEquals(Handover.AlreadyInProgress, own.handOver("second_rewalk"))
        gate.complete(Unit)
        runCurrent()

        val bWalk = FakeWalk()
        val b = own.claim("generation_B", bWalk)
        assertNotNull(b)

        assertEquals(b, own.currentOwner(), "B holds the lease")
        assertEquals(
            0,
            bWalk.cancelCalls,
            "and nothing from the earlier refused handover reached across to stop it",
        )
    }

    // -- a join that throws (review R-N1.16 P2) ------------------------

    @Test
    fun aJoinThatThrowsLeavesTheLeaseFailClosedRatherThanStuck() = runTest {
        // The join runs outside the lock and can throw. Before this was
        // handled, handoverInProgress stayed true for ever: every claim
        // refused, handoverBlocked never set, so recoverIfBlocked()
        // returned null and NOTHING could resolve it. Worse than
        // fail-closed, because fail-closed is recoverable.
        val own = ownership()
        val a = own.claim("generation_A", ThrowingWalk(IllegalStateException("teardown blew up")))
        assertNotNull(a)

        assertFailsWith<IllegalStateException> { own.handOver("network_rewalk") }

        assertEquals(a, own.currentOwner(), "the lease is kept")
        assertEquals("network_rewalk", own.blockedReason(), "and it is fail-CLOSED, not stuck")
        assertNull(own.claim("successor", FakeWalk()), "so no successor starts")
        // blockedReason() being non-null is precisely what makes this
        // recoverable: recoverIfBlocked() reads it and returns null when
        // it is absent, which would mean nothing could ever act.
        // aFailedHandoverIsResolvedOnceTheWalkCanBeJoined drives that
        // through; retrying here would just re-throw the same failure.
    }

    @Test
    fun aCancelledJoinAlsoLeavesTheLeaseRecoverable() = runTest {
        // The most likely throwable is a CancellationException - the
        // caller cancelled mid-handover. The settle must still run,
        // which is why it is NonCancellable.
        val own = ownership()
        val a = own.claim(
            "generation_A",
            ThrowingWalk(kotlinx.coroutines.CancellationException("caller cancelled")),
        )
        assertNotNull(a)

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            own.handOver("network_rewalk")
        }

        assertEquals(a, own.currentOwner())
        assertNotNull(own.blockedReason(), "a cancelled handover must not lose the block")
        assertNull(own.claim("successor", FakeWalk()))
    }

    @Test
    fun aFailedHandoverIsResolvedOnceTheWalkCanBeJoined() = runTest {
        val own = ownership()
        val walk = ThrowingWalk(IllegalStateException("first attempt blew up"))
        val a = own.claim("generation_A", walk)
        assertNotNull(a)
        assertFailsWith<IllegalStateException> { own.handOver("network_rewalk") }

        walk.throwable = null
        assertEquals(Handover.Quiesced(a), own.recoverIfBlocked("alarm_heartbeat"))
        assertNull(own.blockedReason())
        assertNotNull(own.claim("recovered", FakeWalk()))
    }

    // -- process-scoped lease across service instances ------------------

    @Test
    fun aWalkFromAPreviousServiceInstanceCannotFreeTheLeaseOfTheNewOne() = runTest {
        // R-N1.16: the lease lives in the service's companion, so it
        // survives Android destroying and recreating the service. That is
        // what lets a new instance see a walk the old one started - but
        // it also means the old instance's cleanup is still out there.
        //
        // The old walk's release must be refused, or the recreated
        // service's lease would be freed under it and a third start could
        // open a second chain walk.
        val own = ownership()
        val oldWalk = FakeWalk()
        val old = own.claim("instance_1_onStartCommand", oldWalk)
        assertNotNull(old)

        // The service is destroyed and recreated; the new instance hands
        // the lease over and claims it.
        assertTrue(own.handOver("service_recreated") is Handover.Quiesced)
        val fresh = own.claim("instance_2_onStartCommand", FakeWalk())
        assertNotNull(fresh)
        assertEquals(1, oldWalk.cancelCalls, "the previous instance's walk was stopped")

        // The old instance's cleanup finally runs.
        assertFalse(
            own.release(old, "instance_1_cleanup"),
            "a walk from a destroyed service instance must not free the lease its " +
                "successor holds",
        )
        assertEquals(fresh, own.currentOwner())
        assertNull(
            own.claim("third_start", FakeWalk()),
            "so no third start can open a second chain walk",
        )
    }

    @Test
    fun aRecreatedServiceDoesNotMintAFreshLeaseBesideALiveWalk() = runTest {
        // The failure this scoping prevents: a per-instance lease would
        // have given the recreated service an empty slot to claim while
        // the previous instance's coroutine was still walking the chain.
        val own = ownership()
        val live = FakeWalk(finishes = false)
        val first = own.claim("instance_1", live)
        assertNotNull(first)

        assertNull(
            own.claim("instance_2_after_recreation", FakeWalk()),
            "a recreated service must see the live walk and be refused, not handed " +
                "a fresh lease beside it",
        )
        assertEquals(first, own.currentOwner())
    }

    // -- handover edge cases -------------------------------------------

    @Test
    fun aHandoverOnAnEmptySlotIsHarmless() = runTest {
        val own = ownership()
        assertEquals(Handover.NothingToStop, own.handOver("rewalk_with_nothing_running"))
        assertNull(own.currentOwner())
        assertNotNull(own.claim("start", FakeWalk()), "the slot is still usable")
    }

    @Test
    fun releasingATokenThatNeverOwnedAnythingIsRefused() = runTest {
        val own = ownership()
        val a = own.claim("generation_A", FakeWalk())
        assertNotNull(a)

        assertFalse(
            own.release(a + 999, "fabricated_token"),
            "a token nobody minted must not free someone else's slot",
        )
        assertEquals(a, own.currentOwner())
    }

    @Test
    fun releasingTwiceOnlySucceedsOnce() = runTest {
        val own = ownership()
        val a = own.claim("generation_A", FakeWalk())
        assertNotNull(a)
        assertTrue(own.release(a, "first"))

        val b = own.claim("generation_B", FakeWalk())
        assertNotNull(b)
        assertFalse(
            own.release(a, "second"),
            "a repeated release from the old owner must not free B's slot",
        )
        assertEquals(b, own.currentOwner())
    }

    @Test
    fun isOwnerReportsWithoutChangingAnything() = runTest {
        val own = ownership()
        val a = own.claim("generation_A", FakeWalk())
        assertNotNull(a)

        assertTrue(own.isOwner(a))
        assertFalse(own.isOwner(a + 1))
        assertEquals(a, own.currentOwner(), "asking must not release")
    }

    // -- the boundary itself, not just the owner check -----------------

    @Test
    fun nothingCanEnterTheCriticalSectionWhileAReleaseHoldsIt() = runTest {
        // Review R-N1.16: the sequential fixtures above prove that
        // release CHECKS the owner. They do not prove that the check and
        // the clear are one critical section - an implementation that
        // checked under the lock and cleared outside it would satisfy
        // every one of them while keeping the race, because the window
        // would simply move past the check.
        //
        // This fixture holds the section open. While A is parked between
        // reading the owner and clearing it, a handover and a competing
        // claim must make NO progress.
        val gate = CompletableDeferred<Unit>()
        var next = 0L
        val own = ConnectOwnership(
            nextToken = { ++next },
            onReleaseCriticalSection = { gate.await() },
        )

        val a = own.claim("generation_A", FakeWalk())
        assertNotNull(a)

        var releaseFinished = false
        var releaseResult: Boolean? = null
        var handoverFinished = false
        var bToken: Long? = null
        var bFinished = false

        val scope = this
        scope.launch {
            releaseResult = own.release(a, "A_cleanup")
            releaseFinished = true
        }
        runCurrent()

        scope.launch {
            own.handOver("network_rewalk")
            handoverFinished = true
        }
        scope.launch {
            bToken = own.claim("generation_B", FakeWalk())
            bFinished = true
        }
        runCurrent()

        assertFalse(releaseFinished, "precondition: A is parked inside the critical section")
        assertFalse(
            handoverFinished,
            "a handover must not enter while the section is held -- if it can, " +
                "the clear is not inside the lock and the race is still open",
        )
        assertFalse(
            bFinished,
            "nor may a competing claim take the slot mid-release",
        )
        assertNull(bToken, "and it certainly must not receive a token")

        gate.complete(Unit)
        runCurrent()

        assertTrue(releaseFinished, "A finishes once the section is released")
        assertEquals(
            true,
            releaseResult,
            "and A was still the owner throughout, so its release succeeds",
        )
        assertTrue(handoverFinished && bFinished, "the queued operations then proceed")
    }

    @Test
    fun aQueryQueuesBehindAParkedReleaseAndResolvesAfterIt() = runTest {
        // The control for the fixture above: it must fail because the
        // LOCK is held, not because the rig deadlocks on anything it
        // touches. isOwner takes the same lock, so it queues too - the
        // point here is that the whole thing resolves once the gate
        // opens, rather than hanging.
        //
        // R-N1.16 P3: this test used to be named "...DoesNotBlock...",
        // which said the opposite of what it asserts.
        val gate = CompletableDeferred<Unit>()
        var next = 0L
        val own = ConnectOwnership(
            nextToken = { ++next },
            onReleaseCriticalSection = { gate.await() },
        )
        val a = own.claim("A", FakeWalk())
        assertNotNull(a)

        var queried: Boolean? = null
        launch { own.release(a, "cleanup") }
        runCurrent()
        launch { queried = own.isOwner(a) }
        runCurrent()
        assertNull(queried, "the query queues behind the held section")

        gate.complete(Unit)
        runCurrent()
        assertEquals(false, queried, "and resolves afterwards, against the post-release state")
    }

    @Test
    fun everyOwnershipDecisionIsTraceable() = runTest {
        val log = mutableListOf<String>()
        val own = ownership(log)
        val a = own.claim("A", FakeWalk())
        assertNotNull(a)
        own.claim("refused", FakeWalk())
        own.handOver("rewalk")
        val b = own.claim("B", FakeWalk())
        assertNotNull(b)
        own.release(a, "stale")

        assertTrue(log.any { it.startsWith("OWNERSHIP claimed") })
        assertTrue(log.any { it.startsWith("OWNERSHIP claim_refused") })
        assertTrue(log.any { it.startsWith("OWNERSHIP handed_over") })
        assertTrue(
            log.any { it.startsWith("OWNERSHIP release_refused") },
            "a refused release is the signal that the race was averted; it must " +
                "be visible in a log, not silent: $log",
        )
    }
}
