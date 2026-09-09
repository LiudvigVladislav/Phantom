// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N1-F2 R-N1.5 P1 — the completion/revocation window.
 *
 * R-N1.4 read the lease generation at the end of `dispatch` WITHOUT the
 * registry lock, and only removed the job from `active` in the `finally`
 * that ran afterwards. Those two steps were not atomic, so a whole
 * `revokeAndJoin` could land between them:
 *
 * ```
 * block() returns a result
 *   generation check passes            (no lock held)
 *                                      revokeAndJoin: bumps generation,
 *                                        finds the job still registered,
 *                                        cancels and joins it — the block
 *                                        is already done so the join
 *                                        returns at once — and reports a
 *                                        CLEAN revocation
 *   return result                      <-- stale result reaches the caller
 * ```
 *
 * Acceptance and deregistration now happen in one hold of the same lock
 * `revokeAndJoin` takes, so only two orders exist and both are correct.
 *
 * These fixtures drive the real [RestEgressGate]; the window is entered
 * deterministically through `beforeCompletionAcceptanceHook`, not by
 * timing.
 *
 * Which case proves what, stated plainly because it is easy to
 * mis-attribute: the parked-revocation case pins the "revocation wins"
 * order, but it passes under the R-N1.4 shape too, since parking BEFORE
 * the check means the check still sees the bumped generation. The case
 * that actually discriminates is
 * [an_accepted_completion_is_deregistered_before_a_later_revocation]
 * together with the `activeDispatchCount` assertions: without the lock
 * the accepted path never leaves `active`, so a later revocation cancels
 * and joins a dispatch whose result was already handed to the caller,
 * and reports that switch as clean.
 */
class CompletionRevocationWindowTest {

    private class Mode(var value: PrivacyMode?)

    // ── The headline: a revocation completing inside the window must
    //    discard the result, not let it through.
    @Test
    fun a_revocation_inside_the_completion_window_discards_the_result() = runTest {
        val mode = Mode(PrivacyMode.Standard)
        val gate = RestEgressGate(PrivacyModeRestEgressPolicy { mode.value })

        val inWindow = CompletableDeferred<Unit>()
        val leaveWindow = CompletableDeferred<Unit>()
        gate.beforeCompletionAcceptanceHook = {
            // The block has produced its result; acceptance has not
            // happened yet. This is the exact R-N1.4 gap.
            withContext(NonCancellable) {
                inWindow.complete(Unit)
                leaveWindow.await()
            }
        }

        var observed: String? = null
        var cancelled = false
        val worker = launch(StandardTestDispatcher(testScheduler)) {
            try {
                observed = gate.dispatch("send") { "direct-response-body" }
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelled = true
            }
        }
        runCurrent()
        assertTrue(inWindow.isCompleted, "the dispatch must be parked inside the window")

        // A complete privacy switch runs while the frame sits in the gap.
        mode.value = PrivacyMode.Private
        val revocation = gate.revokeAndJoin("privacy_mode_change", joinTimeoutMs = 50L)

        leaveWindow.complete(Unit)
        runCurrent()
        worker.join()

        assertNull(
            observed,
            "a stale result escaped to the caller: the revocation completed between the " +
                "generation check and the deregistration, so the caller observed a Direct " +
                "response produced under a posture the user had already left",
        )
        assertTrue(cancelled, "the dispatch must fail rather than return a revoked result")
        assertEquals(0, gate.activeDispatchCount(), "the lease must not be left registered")
        assertEquals(
            1, revocation.dispatchesCancelled,
            "this is the 'revocation wins' order: the dispatch must still have been in the " +
                "snapshot and cancelled",
        )
    }

    // ── Non-vacuity control: without a revocation the identical parking
    //    returns the result normally.
    @Test
    fun without_a_revocation_the_same_window_returns_the_result() = runTest {
        val gate = RestEgressGate(RestEgressPolicy { RestEgressDecision.DirectAllowed })
        val inWindow = CompletableDeferred<Unit>()
        val leaveWindow = CompletableDeferred<Unit>()
        gate.beforeCompletionAcceptanceHook = {
            withContext(NonCancellable) {
                inWindow.complete(Unit)
                leaveWindow.await()
            }
        }

        var observed: String? = null
        val worker = launch(StandardTestDispatcher(testScheduler)) {
            observed = gate.dispatch("send") { "direct-response-body" }
        }
        runCurrent()
        assertTrue(inWindow.isCompleted)
        leaveWindow.complete(Unit)
        runCurrent()
        worker.join()

        assertEquals(
            "direct-response-body", observed,
            "the control path must deliver the result — otherwise the test above would pass " +
                "for a gate that discards everything",
        )
        assertEquals(0, gate.activeDispatchCount())
    }

    // ── The other order: a dispatch that accepts first must leave the
    //    registry, so a later revocation neither sees nor joins it.
    @Test
    fun an_accepted_completion_is_deregistered_before_a_later_revocation() = runTest {
        val gate = RestEgressGate(RestEgressPolicy { RestEgressDecision.DirectAllowed })

        val result = gate.dispatch("send") { "ok" }
        assertEquals("ok", result)
        assertEquals(0, gate.activeDispatchCount())

        val revocation = gate.revokeAndJoin("privacy_mode_change", joinTimeoutMs = 50L)
        assertEquals(
            0, revocation.dispatchesCancelled,
            "an already-accepted dispatch must not appear in the revocation snapshot. This is " +
                "the assertion that actually discriminates the atomic acceptance: under the " +
                "R-N1.4 shape the result is delivered while the lease stays registered, so a " +
                "later revocation cancels and joins a dispatch whose Direct response the " +
                "caller has already been given, and still reports the switch clean.",
        )
        assertTrue(
            revocation.joinedCleanly,
            "a revocation with nothing in flight must join cleanly",
        )
    }

    // ── THE discriminator: a revocation cannot complete while an
    //    acceptance is in progress.
    @Test
    fun a_revocation_cannot_complete_while_an_acceptance_is_in_progress() = runTest {
        // This is the linearization property, stated as something a test
        // can watch. Under R-N1.4 the generation check was unlocked and
        // the deregistration was a separate step, so a whole
        // revokeAndJoin could run between them: it cancelled and joined a
        // dispatch whose result the caller was about to receive, and
        // reported a clean switch. Under the atomic shape the revocation
        // must BLOCK on the same mutex until acceptance finishes.
        //
        // Parking before the check cannot show this — both shapes then
        // see the bumped generation and discard. The seam therefore sits
        // inside the acceptance lock.
        val gate = RestEgressGate(RestEgressPolicy { RestEgressDecision.DirectAllowed })

        val inAcceptance = CompletableDeferred<Unit>()
        val leaveAcceptance = CompletableDeferred<Unit>()
        gate.duringAcceptanceHook = {
            withContext(NonCancellable) {
                inAcceptance.complete(Unit)
                leaveAcceptance.await()
            }
        }

        var delivered: String? = null
        val dispatcher = launch(StandardTestDispatcher(testScheduler)) {
            runCatching { delivered = gate.dispatch("send") { "direct-response-body" } }
        }
        runCurrent()
        assertTrue(inAcceptance.isCompleted, "the dispatch must be inside the acceptance lock")

        var revocation: RestEgressGate.RevocationResult? = null
        val revoker = launch(StandardTestDispatcher(testScheduler)) {
            revocation = gate.revokeAndJoin("privacy_mode_change", joinTimeoutMs = 50L)
        }
        runCurrent()

        // Snapshot the observation BEFORE releasing, and assert only
        // after every coroutine has finished. Asserting while a
        // NonCancellable coroutine is parked would hang the whole suite
        // on failure instead of reporting it.
        val revokedDuringAcceptance = revocation

        leaveAcceptance.complete(Unit)
        runCurrent()
        dispatcher.join()
        revoker.join()

        assertNull(
            revokedDuringAcceptance,
            "revokeAndJoin completed while an acceptance was still in progress. Acceptance " +
                "and deregistration are therefore not one linearizable step: the revocation " +
                "can claim a dispatch whose Direct result is already on its way to the " +
                "caller, and still report the switch clean.",
        )

        assertEquals(
            "direct-response-body", delivered,
            "the dispatch won the race and must deliver its result",
        )
        assertEquals(
            0, revocation?.dispatchesCancelled,
            "a delivered result and a cancelled-by-revocation report are mutually exclusive " +
                "for the same dispatch",
        )
        assertEquals(0, gate.activeDispatchCount())
    }

    // ── A dispatch that throws must also leave the registry, or the
    //    next revocation would wait on a dead lease.
    @Test
    fun a_failing_dispatch_is_deregistered_too() = runTest {
        val gate = RestEgressGate(RestEgressPolicy { RestEgressDecision.DirectAllowed })
        runCatching { gate.dispatch<Unit>("send") { error("network went away") } }
        assertEquals(
            0, gate.activeDispatchCount(),
            "a failed dispatch must not leak its lease registration",
        )
    }
}
