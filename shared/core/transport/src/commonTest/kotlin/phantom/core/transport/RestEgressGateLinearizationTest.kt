// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * N1-F2 R-N1.3 P1-2 — the decision-to-registration race.
 *
 * R-N1.2 read the policy, THEN captured the generation, then re-checked
 * the generation under the registry lock. A revocation that completed
 * between the policy read and the generation capture was invisible: the
 * dispatch captured the already-incremented generation, the equality
 * check compared it against itself and passed, and the block ran under
 * a stale `DirectAllowed`. The re-check only ever detected a revocation
 * landing in the much narrower capture-to-lock window — not the one
 * that mattered.
 *
 * The gate now takes the decision and the lease together under the same
 * mutex `revokeAndJoin` uses, so a dispatch is either fully authorized
 * before a revocation or refused after it, with no interleaving in
 * between. These tests pin both halves of that claim.
 */
class RestEgressGateLinearizationTest {

    private class Mode(var value: PrivacyMode?)

    // ── A revocation that lands after authorization must still stop the
    //    block from running. The hook parks the dispatch at exactly the
    //    point R-N1.2 left unguarded.
    @Test
    fun revocation_after_authorization_stops_the_block() = runTest {
        val mode = Mode(PrivacyMode.Standard)
        val gate = RestEgressGate(PrivacyModeRestEgressPolicy { mode.value })

        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        gate.afterAuthorizationHook = {
            parked.complete(Unit)
            release.await()
        }

        var blockRan = false
        var cancelled = false
        val worker = launch(StandardTestDispatcher(testScheduler)) {
            try {
                gate.dispatch("op") {
                    blockRan = true
                    "sent"
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelled = true
            } catch (_: RestEgressBlockedException) {
                cancelled = true
            }
        }
        runCurrent()
        assertTrue(parked.isCompleted, "the dispatch must be parked past authorization")
        assertEquals(1, gate.activeDispatchCount(), "the lease must already be registered")

        // The user leaves Standard and the revocation runs to completion
        // while the dispatch is parked.
        mode.value = PrivacyMode.Private
        gate.revokeAndJoin("switch")

        release.complete(Unit)
        runCurrent()
        worker.join()

        assertFalse(blockRan, "a dispatch authorized before a completed revocation must NOT run")
        assertTrue(cancelled, "the parked dispatch must observe the revocation")
        assertEquals(0, gate.activeDispatchCount())
    }

    // ── The lease is visible to revocation the moment it exists: a
    //    dispatch can never be authorized yet absent from the registry.
    @Test
    fun an_authorized_dispatch_is_always_visible_to_revocation() = runTest {
        val gate = RestEgressGate(RestEgressPolicy { RestEgressDecision.DirectAllowed })
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        gate.afterAuthorizationHook = {
            parked.complete(Unit)
            release.await()
        }

        val worker = launch(StandardTestDispatcher(testScheduler)) {
            runCatching { gate.dispatch("op") { "x" } }
        }
        runCurrent()
        assertTrue(parked.isCompleted)

        val result = gate.revokeAndJoin("switch")
        assertEquals(
            1, result.dispatchesCancelled,
            "revocation must see the lease of a dispatch that has been authorized",
        )

        release.complete(Unit)
        runCurrent()
        worker.join()
    }

    // ── Non-vacuity control: without a revocation, the same parked
    //    dispatch completes normally. If it did not, the test above
    //    would prove nothing.
    @Test
    fun without_revocation_the_parked_dispatch_completes() = runTest {
        val gate = RestEgressGate(RestEgressPolicy { RestEgressDecision.DirectAllowed })
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        gate.afterAuthorizationHook = {
            parked.complete(Unit)
            release.await()
        }

        var blockRan = false
        var value: String? = null
        val worker = launch(StandardTestDispatcher(testScheduler)) {
            value = gate.dispatch("op") {
                blockRan = true
                "sent"
            }
        }
        runCurrent()
        assertTrue(parked.isCompleted)
        release.complete(Unit)
        runCurrent()
        worker.join()

        assertTrue(blockRan, "the control path must actually run the block")
        assertEquals("sent", value)
        assertEquals(0, gate.activeDispatchCount())
    }

    // ── A revocation that completes BEFORE the decision refuses the
    //    dispatch outright, through the live policy read.
    @Test
    fun revocation_before_authorization_refuses_the_dispatch() = runTest {
        val mode = Mode(PrivacyMode.Standard)
        val gate = RestEgressGate(PrivacyModeRestEgressPolicy { mode.value })

        mode.value = PrivacyMode.Ghost
        gate.revokeAndJoin("switch")

        var blockRan = false
        val ex = runCatching {
            gate.dispatch("op") { blockRan = true }
        }.exceptionOrNull()

        assertTrue(ex is RestEgressBlockedException, "expected a block, got $ex")
        assertFalse(blockRan)
    }

    // ── A dispatch that ignores cancellation must not hang the switch.
    //    The join is bounded, and the stall is REPORTED, not swallowed:
    //    setPrivacyMode previously discarded the whole outcome inside a
    //    bare runCatching, so a failed teardown left the UI claiming a
    //    restrictive posture with old Direct work still alive.
    @Test
    fun a_stalled_dispatch_bounds_the_join_and_reports_it() = runTest {
        val gate = RestEgressGate(RestEgressPolicy { RestEgressDecision.DirectAllowed })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        // A dispatch that does not observe cancellation, the way a
        // blocking OkHttp call does not.
        val worker = launch(StandardTestDispatcher(testScheduler)) {
            runCatching {
                gate.dispatch("stubborn") {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
        }
        runCurrent()
        assertTrue(entered.isCompleted, "the stubborn dispatch must have started")

        val result = gate.revokeAndJoin("switch", joinTimeoutMs = 50L)

        assertEquals(1, result.dispatchesCancelled)
        assertFalse(
            result.joinedCleanly,
            "a dispatch that ignores cancellation must be reported as a non-clean join, " +
                "not waited on forever and not silently reported as clean",
        )
        assertTrue(
            result.generation > 0,
            "the generation must still advance so any late result is discarded",
        )

        release.complete(Unit)
        runCurrent()
        worker.join()
    }

    // ── revokeAndJoin reports what it did instead of returning Unit, so
    //    a caller can tell a clean teardown from a stalled one.
    @Test
    fun revocation_reports_its_outcome() = runTest {
        val gate = RestEgressGate(RestEgressPolicy { RestEgressDecision.DirectAllowed })
        val idle = gate.revokeAndJoin("no_work")
        assertEquals(0, idle.dispatchesCancelled)
        assertEquals(0, idle.nativeCallsCancelled)
        assertTrue(idle.joinedCleanly, "an idle revocation is trivially clean")
        assertTrue(idle.generation > 0, "revocation must advance the generation")
    }
}
