// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import kotlinx.coroutines.runBlocking
import org.junit.Test
import phantom.core.transport.RestEgressDecision
import phantom.core.transport.RestEgressGate
import phantom.core.transport.RestEgressPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * N1-F2 R-N1.3 P1-2 — the linearizability witness for the
 * decision-to-registration race.
 *
 * `RestEgressGateLinearizationTest` pins the observable behaviour of the
 * fixed gate, but it does NOT distinguish the fixed gate from the broken
 * R-N1.2 one: its test seam parks a dispatch after the lease is already
 * registered, and both versions register before that point. Re-applying
 * the R-N1.2 ordering leaves that suite entirely green. A test that
 * cannot fail on the defect it was written for is not evidence, so this
 * one exists to supply the sensitivity that suite lacks.
 *
 * The distinguishing property is stated directly:
 *
 * > A revocation must not be able to COMPLETE while an authorization
 * > decision is in flight.
 *
 * If it can, the decision was taken outside the lock the revocation
 * uses, so the dispatch goes on to capture the already-incremented
 * generation, compares it against itself, passes, and runs on a stale
 * `DirectAllowed` — which is exactly what R-N1.2 did.
 *
 * The gate now evaluates the policy and registers the lease together
 * under that lock, so a revocation racing an in-flight policy read
 * blocks until the authorization has resolved one way or the other.
 *
 * This runs on real threads: the property is about mutual exclusion, and
 * a virtual-time scheduler with a single thread cannot exhibit it.
 */
class EgressAuthorizationLinearizabilityTest {

    private val latchTimeoutSecs = 10L

    /** How long we let the revocation try to finish before judging it. */
    private val observationWindowMs = 400L

    @Test
    fun a_revocation_cannot_complete_while_a_policy_read_is_in_flight() {
        val enteredDecide = CountDownLatch(1)
        val releaseDecide = CountDownLatch(1)
        val decideCalls = java.util.concurrent.atomic.AtomicInteger(0)

        val gate = RestEgressGate(
            RestEgressPolicy {
                // Only the first read is held open; later reads (the
                // revocation's own bookkeeping, retries) must not block.
                if (decideCalls.getAndIncrement() == 0) {
                    enteredDecide.countDown()
                    releaseDecide.await(latchTimeoutSecs, TimeUnit.SECONDS)
                }
                RestEgressDecision.DirectAllowed
            },
        )

        val dispatchDone = AtomicBoolean(false)
        val dispatcher = thread(name = "dispatch") {
            runBlocking {
                runCatching { gate.dispatch("op") { "sent" } }
                dispatchDone.set(true)
            }
        }

        assertTrue(
            enteredDecide.await(latchTimeoutSecs, TimeUnit.SECONDS),
            "the policy read never started — the harness is not exercising the path under test",
        )

        val revokeCompleted = AtomicBoolean(false)
        val revoker = thread(name = "revoke") {
            runBlocking {
                gate.revokeAndJoin("privacy_mode_change")
                revokeCompleted.set(true)
            }
        }

        // Give the revocation a generous window to finish. On the fixed
        // gate it cannot: the authorization holds the same lock.
        Thread.sleep(observationWindowMs)

        val completedEarly = revokeCompleted.get()

        // Let everything unwind before asserting, so a failure does not
        // leave threads parked on the latch.
        releaseDecide.countDown()
        dispatcher.join(TimeUnit.SECONDS.toMillis(latchTimeoutSecs))
        revoker.join(TimeUnit.SECONDS.toMillis(latchTimeoutSecs))

        assertFalse(
            completedEarly,
            "revokeAndJoin completed while an authorization decision was still in flight. " +
                "The decision is therefore taken outside the revocation's lock, so a dispatch " +
                "can be granted Direct egress on a reading the revocation has already " +
                "superseded — the R-N1.2 defect.",
        )
        assertTrue(dispatchDone.get(), "the dispatch thread must finish")
        assertTrue(revokeCompleted.get(), "the revocation must complete once the read resolves")
    }

    /**
     * Non-vacuity control. With no authorization in flight, a revocation
     * completes well inside the same observation window — so the
     * assertion above is measuring contention, not a slow revocation.
     */
    @Test
    fun an_uncontended_revocation_completes_promptly() {
        val gate = RestEgressGate(RestEgressPolicy { RestEgressDecision.DirectAllowed })
        val completed = AtomicBoolean(false)
        val revoker = thread(name = "revoke-idle") {
            runBlocking {
                gate.revokeAndJoin("privacy_mode_change")
                completed.set(true)
            }
        }
        Thread.sleep(observationWindowMs)
        assertTrue(
            completed.get(),
            "an uncontended revocation must finish inside ${observationWindowMs}ms, otherwise " +
                "the contention test above proves nothing",
        )
        revoker.join(TimeUnit.SECONDS.toMillis(latchTimeoutSecs))
    }

    /**
     * The mutual exclusion must not become a deadlock: after the read
     * resolves, both the dispatch and the revocation finish, and the
     * registry drains to zero.
     */
    @Test
    fun contended_authorization_and_revocation_both_terminate() {
        val enteredDecide = CountDownLatch(1)
        val releaseDecide = CountDownLatch(1)
        val first = AtomicBoolean(true)

        val gate = RestEgressGate(
            RestEgressPolicy {
                if (first.getAndSet(false)) {
                    enteredDecide.countDown()
                    releaseDecide.await(latchTimeoutSecs, TimeUnit.SECONDS)
                }
                RestEgressDecision.DirectAllowed
            },
        )

        val dispatcher = thread { runBlocking { runCatching { gate.dispatch("op") { 1 } } } }
        assertTrue(enteredDecide.await(latchTimeoutSecs, TimeUnit.SECONDS))
        val revoker = thread { runBlocking { gate.revokeAndJoin("switch") } }

        releaseDecide.countDown()
        dispatcher.join(TimeUnit.SECONDS.toMillis(latchTimeoutSecs))
        revoker.join(TimeUnit.SECONDS.toMillis(latchTimeoutSecs))

        assertFalse(dispatcher.isAlive, "the dispatch thread deadlocked")
        assertFalse(revoker.isAlive, "the revocation thread deadlocked")
        runBlocking {
            assertTrue(gate.activeDispatchCount() == 0, "the lease registry must drain to zero")
        }
    }
}
