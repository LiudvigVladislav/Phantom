// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R-N1.17 P1 - a teardown that can be confirmed, and that knows whose
 * connection it is tearing down.
 *
 * Two defects, one shape. `disconnectAndJoin` confirms that the reconnect
 * LOOP ended; the session and HTTP-client closes are dispatched to a
 * cleanup scope and may finish later, or be refused when that scope's
 * budget is exhausted. Treating its `true` as "the socket is closed" let a
 * permit be released over a live WSS.
 *
 * And a teardown scheduled by a service instance that has since been
 * replaced, running unqualified, captures whatever reconnect job is
 * current - which is the SUCCESSOR's - and cancels a connection it has
 * never seen.
 *
 * The transport under test here is a fake, because KtorRelayTransport
 * cannot be stood up in a unit test. It models the two properties that
 * matter and nothing else: closes that outlive the join, and an identity
 * that advances when a new connection is made.
 */
class TeardownConfirmationTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun scope(): CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes.add(it) }

    /**
     * A transport shaped like the real one in the two ways that matter.
     *
     * `disconnectAndConfirm` joins the loop within the budget and then -
     * only when asked to confirm - waits for the closes it dispatched.
     * Those closes run in their own scope, exactly as the real cleanup
     * scope does, so they can and do outlive the join.
     */
    private class FakeTransport(private val cleanup: CoroutineScope) {
        val identity = AtomicLong(1)
        val closeHeld = CompletableDeferred<Unit>()
        val closeRefused = AtomicBoolean(false)
        val closeThrows = AtomicBoolean(false)
        val closesStarted = AtomicLong(0)
        val closesFinished = AtomicLong(0)
        val loopCancelled = AtomicBoolean(false)
        val steps = SafeSteps()

        fun reconnectAsANewGeneration() {
            identity.incrementAndGet()
            loopCancelled.set(false)
            steps.add("connected:${identity.get()}")
        }

        suspend fun disconnectAndConfirm(
            timeoutMs: Long,
            onlyIfIdentity: Long? = null,
            confirmCloses: Boolean = true,
        ): TransportTeardownResult {
            val now = identity.get()
            if (onlyIfIdentity != null && onlyIfIdentity != now) {
                steps.add("refused:$onlyIfIdentity/now=$now")
                return TransportTeardownResult(now, ran = false)
            }
            steps.add("teardown:$now")
            loopCancelled.set(true)
            // The dispatched close, as the real cleanup scope runs it.
            val dispatched: Deferred<Boolean>? = if (closeRefused.get()) {
                null
            } else {
                closesStarted.incrementAndGet()
                // As the real cleanup does: the exception is swallowed, so
                // the coroutine ENDS either way. Only the value says
                // whether the socket actually closed.
                cleanup.async {
                    withContext(NonCancellable) { closeHeld.await() }
                    if (closeThrows.get()) {
                        steps.add("close_threw")
                        false
                    } else {
                        closesFinished.incrementAndGet()
                        steps.add("socket_closed")
                        true
                    }
                }
            }
            val closesConfirmed = if (!confirmCloses) {
                false
            } else {
                dispatched != null && withTimeoutOrNull(timeoutMs) {
                    dispatched.await()
                } == true
            }
            return TransportTeardownResult(
                identity = now,
                ran = true,
                loopJoined = true,
                closesConfirmed = closesConfirmed,
            )
        }
    }

    // ----------------------------------------------------------------
    // The join is not the close
    // ----------------------------------------------------------------

    @Test
    fun aJoinedLoopWithAnUnfinishedCloseIsNotAConfirmedTeardown() = runBlocking {
        val transport = FakeTransport(scope())

        val result = transport.disconnectAndConfirm(timeoutMs = 200)

        assertTrue(result.ran)
        assertTrue(result.loopJoined, "the loop really did stop")
        assertFalse(
            result.closesConfirmed,
            "but the socket close was dispatched and has not finished: ${transport.steps}",
        )
        assertFalse(
            result.confirmed,
            "so the teardown is NOT confirmed - releasing a permit on this would " +
                "leave a live socket in no register: ${transport.steps}",
        )
        assertEquals(0, transport.closesFinished.get())

        assertTrue(transport.closeHeld.complete(Unit), "housekeeping: unpark the close")
    }

    @Test
    fun aCloseThatFinishesInsideTheBudgetIsConfirmed() = runBlocking {
        // The control: confirmation must not become refusal of everything.
        val transport = FakeTransport(scope())
        scope().launch { delay(30); transport.closeHeld.complete(Unit) }

        val result = transport.disconnectAndConfirm(timeoutMs = 3_000)

        assertTrue(result.confirmed, "${transport.steps}")
        assertEquals(1, transport.closesFinished.get())
    }

    @Test
    fun aCloseTheCleanupBudgetRefusedIsNeverConfirmed() = runBlocking {
        // The cap is real: when the cleanup scope is full the close is not
        // dispatched at all. Waiting for it would wait forever, and
        // reporting success would be a lie about a socket nobody closed.
        val transport = FakeTransport(scope())
        transport.closeRefused.set(true)

        val result = transport.disconnectAndConfirm(timeoutMs = 200)

        assertTrue(result.loopJoined)
        assertFalse(
            result.closesConfirmed,
            "a refused cleanup is a close that will never happen: ${transport.steps}",
        )
        assertEquals(0, transport.closesStarted.get())
    }

    // ----------------------------------------------------------------
    // A teardown belongs to the connection that scheduled it
    // ----------------------------------------------------------------

    @Test
    fun aShutdownScheduledBeforeARecreationDoesNotStopTheSuccessor() = runBlocking {
        // The deterministic sequence: the old instance's onDestroy reads
        // the identity it owns and hands the teardown to a scope that
        // outlives it; a new instance comes up and connects; only then
        // does the old cleanup wake.
        val transport = FakeTransport(scope())
        val outliving = scope()

        // 1. The old instance captures what it owns.
        val ownedByTheOldInstance = transport.identity.get()
        val wake = CompletableDeferred<Unit>()
        val cleanup = outliving.launch {
            wake.await()
            transport.disconnectAndConfirm(
                timeoutMs = 200,
                onlyIfIdentity = ownedByTheOldInstance,
            )
        }

        // 2. A new instance connects: a new generation, a new identity.
        transport.reconnectAsANewGeneration()
        assertTrue(transport.identity.get() != ownedByTheOldInstance)

        // 3. The old cleanup finally runs.
        wake.complete(Unit)
        cleanup.join()

        assertFalse(
            transport.loopCancelled.get(),
            "the stale cleanup cancelled the successor's connection: ${transport.steps}",
        )
        assertTrue(
            transport.steps.any { it.startsWith("refused:") },
            "it must refuse rather than tear down what it does not own: ${transport.steps}",
        )
        assertEquals(
            0,
            transport.closesStarted.get(),
            "and it must not close the successor's socket: ${transport.steps}",
        )
    }

    @Test
    fun aShutdownWhoseConnectionIsStillItsOwnDoesTearItDown() = runBlocking {
        // The control. Identity binding must not become a way to never
        // tear anything down.
        val transport = FakeTransport(scope())
        val owned = transport.identity.get()
        scope().launch { delay(30); transport.closeHeld.complete(Unit) }

        val result = transport.disconnectAndConfirm(
            timeoutMs = 3_000,
            onlyIfIdentity = owned,
        )

        assertTrue(result.ran, "${transport.steps}")
        assertTrue(result.confirmed, "${transport.steps}")
        assertTrue(transport.loopCancelled.get())
    }

    @Test
    fun aSupersededTeardownReportsItselfRatherThanFailing() = runBlocking {
        // The caller has to be able to tell "I did not own this" from "I
        // owned it and could not close it". The first is correct and
        // quiet; the second owes a retry.
        val transport = FakeTransport(scope())
        val stale = transport.identity.get()
        transport.reconnectAsANewGeneration()

        val result = transport.disconnectAndConfirm(200, onlyIfIdentity = stale)

        assertTrue(result.supersededIdentity, "${transport.steps}")
        assertFalse(result.confirmed)
        assertFalse(
            result.loopJoined,
            "nothing was joined, because nothing was torn down",
        )
    }

    @Test
    fun aCloseThatThrewIsNotAConfirmedTeardownEvenThoughItsJobEnded() = runBlocking {
        // R-N1.17 P1. The cleanup swallows its exceptions on purpose - a
        // failing close must not propagate into the transport's scope - so
        // the coroutine ends normally whether the socket closed or threw.
        //
        // Confirmation built on joining that coroutine would therefore
        // confirm nothing at all. It has to be built on the RESULT.
        val transport = FakeTransport(scope())
        transport.closeThrows.set(true)
        scope().launch { delay(20); transport.closeHeld.complete(Unit) }

        val result = transport.disconnectAndConfirm(timeoutMs = 3_000)

        assertTrue(result.loopJoined, "the loop stopped: ${transport.steps}")
        assertTrue(
            transport.steps.contains("close_threw"),
            "the close really did run and fail: ${transport.steps}",
        )
        assertFalse(
            result.closesConfirmed,
            "a close whose coroutine ended and whose socket did not is not a " +
                "confirmed close: ${transport.steps}",
        )
        assertFalse(result.confirmed)
        assertEquals(0, transport.closesFinished.get())
    }

    @Test
    fun theClosesShareOneDeadlineWithTheJoin() = runBlocking {
        // The budgets must not stack. A caller that allowed 300 ms for the
        // whole teardown must not find itself waiting 600 because the join
        // and the closes each took a fresh copy of it.
        val transport = FakeTransport(scope())
        val began = System.currentTimeMillis()

        val result = transport.disconnectAndConfirm(timeoutMs = 300)
        val waited = System.currentTimeMillis() - began

        assertFalse(result.closesConfirmed, "${transport.steps}")
        assertTrue(
            waited < 900,
            "one deadline, not one per phase: waited ${waited}ms for a 300ms budget",
        )
        assertTrue(transport.closeHeld.complete(Unit))
    }
}
