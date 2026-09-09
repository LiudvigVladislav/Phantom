// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R-N1.17 P1 - the confirmation contract on the REAL transport.
 *
 * `TeardownConfirmationTest` proves the contract on a fake that models the
 * two properties which matter. This one drives `KtorRelayTransport`
 * itself, for the two facts a fake cannot establish: that its cleanup
 * reports whether the close SUCCEEDED rather than merely that its
 * coroutine ended, and that a teardown quoting an identity the transport
 * no longer owns does nothing at all.
 *
 * The seam used here enqueues a pending close exactly as a teardown does
 * when it detaches a session or client. It is deliberately not a way to
 * invoke a cleanup directly: what goes in is dispatched, awaited, retained
 * and retried by `disconnectAndConfirm` itself, which is the aggregator
 * under test.
 */
class KtorTeardownConfirmationTest {

    private val transports = mutableListOf<KtorRelayTransport>()

    @AfterTest
    fun closeAll() = runBlocking {
        transports.forEach { runCatching { it.closeForTest() } }
    }

    /** Wait, bounded, for the registry to reach a size. Fixtures must not hang. */
    private suspend fun awaitOwed(t: KtorRelayTransport, expected: Int, what: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && t.pendingCloseCount() != expected) {
            delay(10)
        }
        assertEquals(expected, t.pendingCloseCount(), "timed out waiting for $what")
    }

    private fun newTransport(): KtorRelayTransport {
        @Suppress("UNCHECKED_CAST")
        val factory = { _: Int? ->
            error("this fixture must not open a socket")
        } as (Int?) -> io.ktor.client.HttpClient
        return KtorRelayTransport(
            httpClientFactory = factory,
            initialGateProvider = null,
        ).also { transports.add(it) }
    }

    // ----------------------------------------------------------------
    // A cleanup reports success, not completion
    // ----------------------------------------------------------------

    @Test
    fun aCleanupThatThrewReportsFalseEvenThoughItsCoroutineEnded() = runBlocking {
        // The cleanup swallows its exceptions deliberately: a failing
        // close must not propagate into the transport's own scope. So the
        // coroutine ends normally whether the socket closed or blew up,
        // and anything built on joining it would call both a success.
        val transport = newTransport()

        val result = transport.launchCleanupForTest("close.that.throws") {
            error("session.close blew up")
        }

        assertNotNull(result, "the cleanup was dispatched")
        assertFalse(
            result.await(),
            "a close that threw is not a closed socket, however cleanly its " +
                "coroutine finished",
        )
    }

    @Test
    fun aCleanupThatSucceededReportsTrue() = runBlocking {
        // The control: reporting failure must not become reporting failure
        // for everything.
        val transport = newTransport()
        var ran = false

        val result = transport.launchCleanupForTest("close.that.works") { ran = true }

        assertNotNull(result)
        assertTrue(result.await(), "a close that ran to completion is confirmed")
        assertTrue(ran)
    }

    @Test
    fun aCleanupTheCapRefusedIsNotDispatchedAtAll() = runBlocking {
        // The cap is real, and a refused cleanup is a close that will
        // never happen. Confirmation has to be able to tell that apart
        // from one that ran and failed.
        val transport = newTransport()
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        val parked = mutableListOf<kotlinx.coroutines.Deferred<Boolean>>()
        // The `try` starts BEFORE the parkings are created, not after.
        // Filling the cap is itself fallible - the size assertion below
        // is inside it - and a failure part-way through would otherwise
        // leave whatever was already parked waiting on a signal nobody
        // sends. `@AfterTest` cancels the scope, and these wait under
        // NonCancellable, so cancelling reaches none of them.
        //
        // The `finally` releases and awaits the list AS ACCUMULATED, so
        // a partial fill is cleaned up as readily as a full one.
        try {
            repeat(8) { i ->
                transport.launchCleanupForTest("park-$i") {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        held.await()
                    }
                }?.let { parked += it }
            }
            assertEquals(8, parked.size, "the cap is filled")

            val refused = transport.launchCleanupForTest("one-too-many") { }

            assertNull(
                refused,
                "past the cap the cleanup is not dispatched, and no result exists to " +
                    "wait for - waiting would wait for ever",
            )
            held.complete(Unit)
            parked.forEach { it.await() }
        } finally {
            held.complete(Unit)
            withTimeoutOrNull(5_000) { parked.forEach { it.await() } }
        }
    }

    // ----------------------------------------------------------------
    // A teardown belongs to the connection that asked for it
    // ----------------------------------------------------------------

    @Test
    fun aTeardownQuotingAnIdentityTheTransportDoesNotOwnDoesNothing() = runBlocking {
        // The deferred shutdown cleanup quotes the identity its instance
        // owned. If a successor has connected since, the transport owns a
        // different one, and tearing down would cancel a connection this
        // caller has never seen.
        val transport = newTransport()
        val owned = transport.teardownIdentity

        val result = transport.disconnectAndConfirm(
            timeoutMs = 200,
            onlyIfIdentity = owned + 1,
        )

        assertTrue(
            result.supersededIdentity,
            "an identity the transport does not own must be refused outright",
        )
        assertFalse(result.ran)
        assertFalse(result.confirmed)
        assertFalse(
            result.loopJoined,
            "nothing was joined, because nothing was torn down",
        )
        assertEquals(owned, result.identity, "and it reports what is actually owned")
    }

    @Test
    fun aTeardownQuotingTheOwnedIdentityRuns() = runBlocking {
        // The control. Identity binding must not become a way to never
        // tear anything down.
        val transport = newTransport()

        val result = transport.disconnectAndConfirm(
            timeoutMs = 1_000,
            onlyIfIdentity = transport.teardownIdentity,
        )

        assertTrue(result.ran, "the transport still owns this identity")
        assertFalse(result.supersededIdentity)
    }

    @Test
    fun anUnqualifiedTeardownAlwaysRuns() = runBlocking {
        // No identity quoted means no opinion about which connection this
        // is - the ordinary privacy-switch path, where the caller holds
        // the transport for the duration.
        val transport = newTransport()

        val result = transport.disconnectAndConfirm(timeoutMs = 1_000)

        assertTrue(result.ran)
        assertFalse(result.supersededIdentity)
    }

    // ----------------------------------------------------------------
    // A close that failed must be retryable
    // ----------------------------------------------------------------

    @Test
    fun aFailedCloseIsRetainedAndRetriedUntilItSucceeds() = runBlocking {
        // R-N1.17 P1, the sequence that was impossible before.
        //
        // A teardown DETACHES the session and client and hands them to
        // cleanup tasks. Once detached the transport no longer holds them,
        // so a close that failed could not be retried by anyone: the next
        // teardown found both fields null, dispatched nothing, and an
        // empty set of closes reported confirmed. The permit machinery is
        // built on that retry.
        val transport = newTransport()
        val attempts = AtomicInteger()
        val works = AtomicBoolean(false)

        // 1-2. A teardown captured this close, and the close fails.
        transport.enqueuePendingCloseForTest("session.close") {
            attempts.incrementAndGet()
            if (!works.get()) error("close failed")
        }

        val first = transport.disconnectAndConfirm(timeoutMs = 1_000)

        // 3. Not confirmed, and still owed.
        assertFalse(first.closesConfirmed, "a close that threw is not a closed socket")
        assertFalse(first.confirmed)
        assertEquals(1, attempts.get(), "it really was attempted")
        assertEquals(
            1,
            transport.pendingCloseCount(),
            "and it is RETAINED - a detached reference nobody holds cannot be retried",
        )

        // 4-5. A second teardown dispatches the SAME close again, and the
        // switch stays unconfirmed while it keeps failing.
        val second = transport.disconnectAndConfirm(timeoutMs = 1_000)
        assertEquals(2, attempts.get(), "the retry closed the same socket, not a new one")
        assertFalse(second.closesConfirmed, "still owed: $second")
        assertEquals(1, transport.pendingCloseCount())

        // 6. Only a close that actually succeeds discharges it.
        works.set(true)
        val third = transport.disconnectAndConfirm(timeoutMs = 1_000)
        assertEquals(3, attempts.get())
        assertTrue(third.closesConfirmed, "$third")
        assertTrue(third.confirmed)
        assertEquals(0, transport.pendingCloseCount(), "nothing left owed")
    }

    @Test
    fun anOutstandingCloseKeepsALaterTeardownUnconfirmed() = runBlocking {
        // The empty-set defect stated directly: a teardown that dispatches
        // nothing of its own must not report success while a close from an
        // earlier one is still owed.
        val transport = newTransport()
        val hold = CompletableDeferred<Unit>()
        transport.enqueuePendingCloseForTest("wedged.close") {
            withContext(NonCancellable) { hold.await() }
        }

        // Released in a `finally`. The assertions below are what a
        // mutation makes fail, and a failure before the release would
        // leave this parked for the rest of the process - these scopes
        // are the fixture's own, so @AfterTest never reaches them.
        try {
            val result = transport.disconnectAndConfirm(timeoutMs = 200)

            assertFalse(
                result.closesConfirmed,
                "an outstanding close is an unconfirmed teardown, whatever this one " +
                    "did or did not dispatch",
            )
            assertEquals(1, transport.pendingCloseCount())
            assertTrue(hold.complete(Unit))
        } finally {
            hold.complete(Unit)
        }
    }

    @Test
    fun aRetryDoesNotTouchAConnectionMadeSince() = runBlocking {
        // The pending action closes over the reference it was given, so a
        // retry closes THAT socket. A connection made since is untouched
        // by construction rather than by a check.
        val transport = newTransport()
        val closedA = AtomicInteger()
        val closedB = AtomicInteger()
        val aWorks = AtomicBoolean(false)

        transport.enqueuePendingCloseForTest("A.close") {
            closedA.incrementAndGet()
            if (!aWorks.get()) error("A refuses to close")
        }
        transport.disconnectAndConfirm(timeoutMs = 500)
        assertEquals(1, closedA.get())

        // B arrives, with its own close.
        transport.enqueuePendingCloseForTest("B.close") { closedB.incrementAndGet() }
        aWorks.set(true)

        val result = transport.disconnectAndConfirm(timeoutMs = 1_000)

        assertTrue(result.closesConfirmed, "$result")
        assertEquals(2, closedA.get(), "A was retried exactly once more")
        assertEquals(1, closedB.get(), "and B was closed once, by its own entry")
        assertEquals(0, transport.pendingCloseCount())
    }

    @Test
    fun everyCloseIsAccountedForEvenAfterOneFails() = runBlocking {
        // `all { it.await() }` stops at the first false, and the closes it
        // skipped then finish unobserved - a socket that did go away would
        // be missing from the record, and one that did not would be
        // credited to nobody.
        val transport = newTransport()
        val second = AtomicInteger()

        transport.enqueuePendingCloseForTest("first.fails") { error("no") }
        transport.enqueuePendingCloseForTest("second.works") { second.incrementAndGet() }

        val result = transport.disconnectAndConfirm(timeoutMs = 1_000)

        assertFalse(result.closesConfirmed, "one of them failed")
        assertEquals(
            1,
            second.get(),
            "and the other still ran and was accounted for: it must not be dropped " +
                "because a sibling failed first",
        )
        assertEquals(
            1,
            transport.pendingCloseCount(),
            "exactly the failed one is still owed",
        )
    }

    // ----------------------------------------------------------------
    // One deadline for the whole confirmation
    // ----------------------------------------------------------------

    @Test
    fun theJoinAndTheClosesShareOneDeadline() = runBlocking {
        // The budgets must not stack. A caller that allowed 400 ms for the
        // teardown must not wait 800 because the join and the closes each
        // took a fresh copy of it.
        val transport = newTransport()
        val loop = CoroutineScope(Dispatchers.Default + SupervisorJob())
        // A reconnect job that will not end, so the join phase spends the
        // whole budget before the closes are even reached.
        //
        // Awaited into its body first: a job cancelled before it has
        // started never runs, and the join then returns at once - which
        // made an earlier version of this fixture report a full budget
        // remaining and fail for a reason that had nothing to do with the
        // code.
        // Parked on a signal, not on a near-infinite delay. A delay
        // inside NonCancellable ignores `loop.cancel()`, so the earlier
        // version of this fixture left a coroutine running for the rest of
        // the process - a fixture must not outlive itself.
        val running = CompletableDeferred<Unit>()
        val releaseLoop = CompletableDeferred<Unit>()
        val loopJob = loop.launch {
            running.complete(Unit)
            withContext(NonCancellable) { releaseLoop.await() }
        }
        transport.seedReconnectJobForTest(loopJob)
        running.await()
        val hold = CompletableDeferred<Unit>()
        transport.enqueuePendingCloseForTest("wedged.close") {
            withContext(NonCancellable) { hold.await() }
        }

        // Released in a `finally`. The assertions below are what a
        // mutation makes fail, and a failure before the release would
        // leave this parked for the rest of the process - these scopes
        // are the fixture's own, so @AfterTest never reaches them.
        try {
            val result = transport.disconnectAndConfirm(timeoutMs = 400)

            assertFalse(result.confirmed)
            // Asserted on the REPORTED budget, not on the clock. A wall-clock
            // assertion inside a parallel suite measures the machine as much
            // as the code, and an earlier version of this fixture passed the
            // very mutation it was written for because of it.
            assertTrue(
                result.closesBudgetMs <= 0,
                "the join consumed the whole budget, so the closes must get what is " +
                    "LEFT of it - not a fresh copy. Reported budget: " +
                    "${result.closesBudgetMs}ms of 400ms",
            )
            assertTrue(hold.complete(Unit))
            releaseLoop.complete(Unit)
            assertNotNull(
                withTimeoutOrNull(5_000) { loopJob.join() },
                "the parked loop must actually end, not merely be cancelled",
            )
            loop.cancel()
        } finally {
            hold.complete(Unit)
            releaseLoop.complete(Unit)
            // The cancel is itself in a `finally`: a bounded join that
            // throws must not take the scope teardown down with it.
            try {
                withTimeoutOrNull(5_000) { loopJob.join() }
            } finally {
                loop.cancel()
            }
        }
    }

    // ----------------------------------------------------------------
    // One live attempt per pending close
    // ----------------------------------------------------------------

    @Test
    fun aParkedCloseIsNotStartedAgainBesideItself() = runBlocking {
        // R-N1.17 P1. Retaining the entry made the retry possible, and by
        // itself made a DUPLICATE possible too: a close that parks stays
        // owed, so the next teardown would dispatch the same action again
        // beside the one still running. Two concurrent closes of one
        // socket, each holding a cleanup slot, until the cap fills and
        // further closes are refused outright.
        //
        // Retried, not re-entered.
        val transport = newTransport()
        val starts = AtomicInteger()
        val hold = CompletableDeferred<Unit>()

        transport.enqueuePendingCloseForTest("parks.forever") {
            starts.incrementAndGet()
            withContext(NonCancellable) { hold.await() }
        }

        // 1-2. The first teardown dispatches it; the close parks and the
        // wait runs out.
        // Released in a `finally`. The assertions below are what a
        // mutation makes fail, and a failure before the release would
        // leave this parked for the rest of the process - these scopes
        // are the fixture's own, so @AfterTest never reaches them.
        try {
            val first = transport.disconnectAndConfirm(timeoutMs = 200)
            assertFalse(first.closesConfirmed, "it never finished")
            assertEquals(1, starts.get(), "dispatched once")
            assertEquals(1, transport.pendingCloseCount(), "still owed")
            assertEquals(1, transport.pendingCloseInFlightCount(), "and still running")

            // 3-4. A second teardown, while it is still parked.
            val second = transport.disconnectAndConfirm(timeoutMs = 200)
            assertFalse(second.closesConfirmed)
            assertEquals(
                1,
                starts.get(),
                "the same close must NOT be started a second time while the first " +
                    "attempt is still running",
            )
            assertEquals(1, transport.pendingCloseCount(), "and it is still owed")

            // The parked attempt finally finishes and discharges the entry
            // itself - it does not need another teardown to do that.
            assertTrue(hold.complete(Unit))
            awaitOwed(transport, 0, "the parked attempt to discharge its own entry")
            assertEquals(0, transport.pendingCloseInFlightCount())

            // And a teardown after that confirms, with nothing left to do.
            val third = transport.disconnectAndConfirm(timeoutMs = 2_000)
            assertTrue(third.closesConfirmed, "$third")
            assertEquals(1, starts.get(), "still exactly one attempt in total")
        } finally {
            hold.complete(Unit)
            withTimeoutOrNull(5_000) { awaitOwed(transport, 0, "cleanup") }
        }
    }

    @Test
    fun aClaimIsReturnedWhenTheAttemptFailsSoTheNextSweepCanRetry() = runBlocking {
        // The other half: the guard must not become a way to attempt a
        // close exactly once. A failed attempt hands the claim back.
        val transport = newTransport()
        val starts = AtomicInteger()
        val works = AtomicBoolean(false)

        transport.enqueuePendingCloseForTest("fails.then.works") {
            starts.incrementAndGet()
            if (!works.get()) error("not yet")
        }

        transport.disconnectAndConfirm(timeoutMs = 1_000)
        assertEquals(1, starts.get())
        assertEquals(
            0,
            transport.pendingCloseInFlightCount(),
            "a finished attempt holds nothing",
        )

        works.set(true)
        val result = transport.disconnectAndConfirm(timeoutMs = 1_000)
        assertEquals(2, starts.get(), "the next sweep really did retry it")
        assertTrue(result.closesConfirmed, "$result")
        assertEquals(0, transport.pendingCloseCount())
    }

    @Test
    fun aCloseTheCapRefusedIsOwedAndUnclaimed() = runBlocking {
        // A refusal means nothing is running, so the claim must not be
        // left held - the entry would then be owed for ever and never
        // attempted again.
        val transport = newTransport()
        val held = CompletableDeferred<Unit>()
        val parked = mutableListOf<kotlinx.coroutines.Deferred<Boolean>>()
        var started = false
        // The `try` starts BEFORE the parkings are created, not after.
        // Filling the cap is itself fallible - the size assertion below
        // is inside it - and a failure part-way through would otherwise
        // leave whatever was already parked waiting on a signal nobody
        // sends. `@AfterTest` cancels the scope, and these wait under
        // NonCancellable, so cancelling reaches none of them.
        //
        // The `finally` releases and awaits the list AS ACCUMULATED, so
        // a partial fill is cleaned up as readily as a full one.
        try {
            repeat(8) { i ->
                transport.launchCleanupForTest("park-$i") {
                    withContext(NonCancellable) { held.await() }
                }?.let { parked += it }
            }
            assertEquals(8, parked.size, "the cap is filled")

            transport.enqueuePendingCloseForTest("refused.by.cap") { started = true }
            val result = transport.disconnectAndConfirm(timeoutMs = 200)

            assertFalse(started, "the cap refused it")
            assertFalse(result.closesConfirmed)
            assertEquals(1, transport.pendingCloseCount(), "still owed")
            assertEquals(
                0,
                transport.pendingCloseInFlightCount(),
                "and not claimed by an attempt that never started",
            )

            assertTrue(held.complete(Unit))
            parked.forEach { it.await() }
            val retry = transport.disconnectAndConfirm(timeoutMs = 2_000)
            awaitOwed(transport, 0, "the retry to discharge it")
            assertTrue(started, "once the cap frees up it is attempted")
            assertTrue(retry.closesConfirmed, "$retry")
        } finally {
            held.complete(Unit)
            withTimeoutOrNull(5_000) { parked.forEach { it.await() } }
        }
    }

    @Test
    fun anInterruptedWaitLosesNothingFromTheRegistry() = runBlocking {
        // Awaiting every result rather than short-circuiting is about
        // ACCOUNTING for outcomes. What keeps an unfinished close from
        // being lost is the registry: entries leave it only on success, so
        // a deadline or an exception in the wait discharges nothing.
        val transport = newTransport()
        val hold = CompletableDeferred<Unit>()
        transport.enqueuePendingCloseForTest("parks") {
            withContext(NonCancellable) { hold.await() }
        }
        transport.enqueuePendingCloseForTest("fails") { error("no") }

        // Released in a `finally`. The assertions below are what a
        // mutation makes fail, and a failure before the release would
        // leave this parked for the rest of the process - these scopes
        // are the fixture's own, so @AfterTest never reaches them.
        try {
            val result = transport.disconnectAndConfirm(timeoutMs = 150)

            assertFalse(result.closesConfirmed)
            assertEquals(
                2,
                transport.pendingCloseCount(),
                "the parked one and the failed one are both still owed after the " +
                    "wait was cut short",
            )
            assertTrue(hold.complete(Unit))
        } finally {
            hold.complete(Unit)
        }
    }

    // ----------------------------------------------------------------
    // Through the detach, not around it
    // ----------------------------------------------------------------

    @Test
    fun aCloseDetachedByTheTeardownIsRememberedAndRetried() = runBlocking {
        // R-N1.17 P1, walked through the step whose absence WAS the
        // defect. The other retry fixtures put an entry straight into the
        // registry, which skips this line: a close that is dispatched
        // without being remembered cannot be retried, because the teardown
        // has already dropped the reference.
        //
        // Here the closeable is handed to the transport BEFORE the
        // teardown, and the teardown detaches it exactly as it detaches
        // the session and the client - same list, same loop.
        val transport = newTransport()
        val attempts = AtomicInteger()
        val works = AtomicBoolean(false)

        transport.addDetachableForTest("socket.close", close = {
            attempts.incrementAndGet()
            if (!works.get()) error("close failed")
        })
        assertEquals(0, transport.pendingCloseCount(), "nothing owed until it detaches")

        // The teardown detaches it, remembers it, and dispatches it.
        val first = transport.disconnectAndConfirm(timeoutMs = 1_000)
        assertEquals(1, attempts.get(), "detached and attempted")
        assertFalse(first.closesConfirmed, "and it failed")
        assertEquals(
            1,
            transport.pendingCloseCount(),
            "remembered, or the reference would be gone and the close unrepeatable",
        )

        // A later teardown finds it and closes the same socket again.
        works.set(true)
        val second = transport.disconnectAndConfirm(timeoutMs = 1_000)
        assertEquals(2, attempts.get(), "the SAME close, retried")
        assertTrue(second.closesConfirmed, "$second")
        awaitOwed(transport, 0, "the successful retry to discharge it")
    }

    @Test
    fun aDetachedCloseIsTakenOnlyOnce() = runBlocking {
        // The list is drained by the teardown that detaches it: a second
        // teardown must not detach the same closeable again and end up
        // with two registry entries for one socket.
        val transport = newTransport()
        val attempts = AtomicInteger()
        transport.addDetachableForTest("socket.close", close = { attempts.incrementAndGet() })

        transport.disconnectAndConfirm(timeoutMs = 1_000)
        awaitOwed(transport, 0, "the close to succeed and discharge")
        assertEquals(1, attempts.get())

        val second = transport.disconnectAndConfirm(timeoutMs = 1_000)
        assertEquals(1, attempts.get(), "nothing was detached a second time")
        assertTrue(second.closesConfirmed, "and there is nothing left owed: $second")
    }

    // ----------------------------------------------------------------
    // The budget is applied, not merely computed
    // ----------------------------------------------------------------

    @Test
    fun anExhaustedBudgetMeansTheClosesAreNotAwaitedAtAll() = runBlocking {
        // `closesBudgetMs` says what was LEFT. It does not say what the
        // wait was given, and a version that reports the right remainder
        // and then hands the full timeout to the wait would satisfy it.
        //
        // The behaviour, stated so no clock is involved: when the join has
        // consumed the budget there is no further wait, so a close that
        // has ALREADY succeeded is still not confirmed by this teardown -
        // it was never awaited. A version that waits anyway confirms it.
        val transport = newTransport()
        val loop = CoroutineScope(Dispatchers.Default + SupervisorJob())
        // Awaited into its body: a job cancelled before it starts never
        // runs, and the join would return at once with the budget intact.
        val running = CompletableDeferred<Unit>()
        val releaseLoop = CompletableDeferred<Unit>()
        val loopJob = loop.launch {
            running.complete(Unit)
            withContext(NonCancellable) { releaseLoop.await() }
        }
        transport.seedReconnectJobForTest(loopJob)
        running.await()
        // A close that succeeds the moment it is dispatched.
        transport.addDetachableForTest("instant.close", close = { })

        // Released in a `finally`. The assertions below are what a
        // mutation makes fail, and a failure before the release would
        // leave this parked for the rest of the process - these scopes
        // are the fixture's own, so @AfterTest never reaches them.
        try {
            val result = transport.disconnectAndConfirm(timeoutMs = 300)

            assertTrue(
                result.closesBudgetMs <= 0,
                "the join consumed the budget: ${result.closesBudgetMs}ms left",
            )
            assertFalse(
                result.closesConfirmed,
                "with nothing left of the budget the closes are not awaited, so this " +
                    "teardown confirms nothing - even though the close itself succeeded",
            )
            assertFalse(result.confirmed)

            // The close really did succeed; it is the CONFIRMATION that was
            // out of budget, and a later teardown says so.
            awaitOwed(transport, 0, "the close to have discharged itself")
            releaseLoop.complete(Unit)
            assertNotNull(
                withTimeoutOrNull(5_000) { loopJob.join() },
                "the parked loop must actually end",
            )
            loop.cancel()
        } finally {
            releaseLoop.complete(Unit)
            try {
                withTimeoutOrNull(5_000) { loopJob.join() }
            } finally {
                loop.cancel()
            }
        }
    }

    @Test
    fun abudgetThatSurvivesTheJoinDoesAwaitTheCloses() = runBlocking {
        // The control: not awaiting must not become never awaiting.
        val transport = newTransport()
        transport.addDetachableForTest("instant.close", close = { })

        val result = transport.disconnectAndConfirm(timeoutMs = 1_000)

        assertTrue(result.closesBudgetMs > 0, "there was budget left")
        assertTrue(result.closesConfirmed, "so the closes were awaited: $result")
        assertTrue(result.confirmed)
    }

    // ----------------------------------------------------------------
    // `close()` starts a shutdown; it does not finish one
    // ----------------------------------------------------------------

    @Test
    fun aCloseThatReturnedWhileTheResourceLivesIsNotConfirmed() = runBlocking {
        // R-N1.17 P1. `HttpClient.close()` and `WebSocketSession.close()`
        // return while the engine is still winding down. A cleanup that
        // finishes when `close()` returns therefore reports a socket gone
        // that may still be there - and the entry leaves the registry, and
        // the permit with it.
        //
        // The close and the wait for the resource's own completion are
        // composed in one place, so this exercises the same composition
        // production gets.
        val transport = newTransport()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val closeReturned = CompletableDeferred<Unit>()
        val resourceMayFinish = CompletableDeferred<Unit>()
        val resource = scope.launch {
            withContext(NonCancellable) { resourceMayFinish.await() }
        }

        transport.addDetachableForTest(
            label = "engine.close",
            close = { closeReturned.complete(Unit) },
            completion = { resource },
        )

        // Released in a `finally`. The assertions below are what a
        // mutation makes fail, and a failure before the release would
        // leave this parked for the rest of the process - these scopes
        // are the fixture's own, so @AfterTest never reaches them.
        try {
            val first = transport.disconnectAndConfirm(timeoutMs = 300)

            assertTrue(closeReturned.isCompleted, "close() itself returned at once")
            assertFalse(
                first.closesConfirmed,
                "but the resource is still running, so nothing is confirmed",
            )
            assertFalse(first.confirmed)
            assertEquals(
                1,
                transport.pendingCloseCount(),
                "and the entry stays registered, which is what keeps the permit",
            )

            // Only when the resource itself finishes is the close a fact.
            resourceMayFinish.complete(Unit)
            awaitOwed(transport, 0, "the resource to finish and discharge the entry")
            val second = transport.disconnectAndConfirm(timeoutMs = 1_000)
            assertTrue(second.closesConfirmed, "$second")

            assertNotNull(
                withTimeoutOrNull(5_000) { resource.join() },
                "the fixture's own resource must end",
            )
            scope.cancel()
        } finally {
            resourceMayFinish.complete(Unit)
            try {
                withTimeoutOrNull(5_000) { resource.join() }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun aResourceThatFinishesWithItsCloseIsConfirmedAtOnce() = runBlocking {
        // The control: waiting for completion must not become waiting for
        // ever. A resource whose job is already done confirms immediately.
        val transport = newTransport()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val finished = scope.launch { }
        finished.join()

        transport.addDetachableForTest(
            label = "engine.close",
            close = { },
            completion = { finished },
        )

        // Released in a `finally`. The assertions below are what a
        // mutation makes fail, and a failure before the release would
        // leave this parked for the rest of the process - these scopes
        // are the fixture's own, so @AfterTest never reaches them.
        try {
            val result = transport.disconnectAndConfirm(timeoutMs = 1_000)

            assertTrue(result.closesConfirmed, "$result")
            assertEquals(0, transport.pendingCloseCount())
            scope.cancel()
        } finally {
            scope.cancel()
        }
    }
}
