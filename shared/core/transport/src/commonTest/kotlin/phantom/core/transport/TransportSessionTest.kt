// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R-N1.17 P1 - the WSS session lifecycle.
 *
 * `824/0` did not see the defects this covers, because there was no
 * fixture for the session at all: the permit machinery was tested, the
 * coordinator was tested, and the thing that actually opens the socket
 * was a bare `launch` in an Android Service with no test around it.
 */
class TransportSessionTest {

    private class Transport {
        var connects = 0
            private set
        var disconnects = 0
            private set

        fun connected() { connects += 1 }
        fun disconnected() { disconnects += 1 }
    }

    @Test
    fun theSessionIsAChildOfItsOwnerSoCancellingTheOwnerEndsIt() = runTest {
        // The sibling defect: a handover could cancel and successfully
        // join the walk while the WSS loop carried on underneath it.
        val transport = Transport()
        val started = CompletableDeferred<Unit>()
        var sessionEnded = false

        val owner = launch {
            val session = TransportSession(
                ownerScope = CoroutineScope(currentCoroutineContext()),
                connectLoop = {
                    transport.connected()
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        sessionEnded = true
                    }
                },
                closeTransport = { transport.disconnected() },
            )
            session.start()
            session.awaitCompletion()
        }
        runCurrent()
        assertTrue(started.isCompleted, "precondition: the loop is running")

        // Cancelling and JOINING the owner must end the session too.
        owner.cancel(CancellationException("ownership_handover"))
        owner.join()

        assertTrue(
            sessionEnded,
            "a session that outlives a joined owner is the sibling defect: the " +
                "handover reports success while the socket is still up",
        )
    }

    @Test
    fun aRevocationBeforeTheLoopStartsPreventsItEntirely() = runTest {
        // The window that made the old shape dangerous rather than
        // untidy: `launch` returns before the body runs, so a switch
        // landing there disconnected a transport that had not connected,
        // and the body then opened a socket under a revoked policy.
        val transport = Transport()
        val owner = launch {
            val session = TransportSession(
                ownerScope = CoroutineScope(currentCoroutineContext()),
                connectLoop = { transport.connected(); awaitCancellation() },
                closeTransport = { transport.disconnected() },
            )
            session.start()
            // Stop before the dispatcher has ever run the body.
            assertTrue(session.stop("privacy_mode_changed"))
            session.awaitCompletion()
        }
        owner.join()

        assertEquals(
            0,
            transport.connects,
            "the loop must never run: cancelling first is what prevents a socket " +
                "opening under a policy that has already been revoked",
        )
    }

    @Test
    fun stopCancelsBeforeItClosesAndConfirmsAfterwards() = runTest {
        // The ordering that matters, stated precisely.
        //
        // `cancel()` is asynchronous: it marks the Job cancelled, and the
        // loop's own teardown runs when the dispatcher next resumes it -
        // which is at the join, AFTER the close. So asserting that the
        // loop finishes before the close asserts a scheduling accident.
        //
        // What must hold is that the cancellation has already been
        // ISSUED when the transport is closed - so the loop cannot start
        // anything new - and that stop() does not return until the loop
        // has actually ended.
        var cancelledWhenClosed: Boolean? = null
        var endedWhenStopReturned: Boolean? = null
        val running = CompletableDeferred<Unit>()
        val owner = launch {
            var loopJob: Job? = null
            var loopEnded = false
            val session = TransportSession(
                ownerScope = CoroutineScope(currentCoroutineContext()),
                connectLoop = {
                    loopJob = currentCoroutineContext()[Job]
                    running.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        loopEnded = true
                    }
                },
                closeTransport = { cancelledWhenClosed = loopJob?.isCancelled },
            )
            session.start()
            running.await()
            assertTrue(session.stop("privacy_mode_changed"))
            endedWhenStopReturned = loopEnded
        }
        owner.join()

        assertEquals(
            true,
            cancelledWhenClosed,
            "the loop must already be cancelled when the transport is closed - " +
                "closing first means waiting on something nobody told to stop",
        )
        assertEquals(
            true,
            endedWhenStopReturned,
            "and stop() must not return until the loop has actually ended",
        )
    }

    @Test
    fun aLoopThatIgnoresCancellationIsReportedAsNotStopped() = runTest {
        // Not confirmed means not confirmed. Reporting success here is
        // what let a privacy switch complete over a live socket.
        val owner = launch {
            val session = TransportSession(
                ownerScope = CoroutineScope(currentCoroutineContext()),
                connectLoop = {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        delay(Long.MAX_VALUE / 4)
                    }
                },
                closeTransport = { },
                stopTimeoutMs = 200L,
            )
            session.start()
            runCurrent()
            assertFalse(
                session.stop("privacy_mode_changed"),
                "a loop that will not stop must be reported as not stopped",
            )
        }
        testScheduler.advanceUntilIdle()
        owner.cancel()
    }

    @Test
    fun aCloseThatThrowsIsNotASuccessfulStopEvenWhenTheJobEnds() = runTest {
        // The join succeeding says the LOOP stopped; it says nothing
        // about the socket teardown. Reporting success on the join alone
        // let a permit count as closed while the close had thrown.
        val running = CompletableDeferred<Unit>()
        var stopResult: Boolean? = null
        val owner = launch {
            val session = TransportSession(
                ownerScope = CoroutineScope(currentCoroutineContext()),
                connectLoop = { running.complete(Unit); awaitCancellation() },
                closeTransport = { error("teardown blew up") },
            )
            session.start()
            running.await()
            stopResult = session.stop("privacy_mode_changed")
        }
        owner.join()

        assertEquals(
            false,
            stopResult,
            "the loop ended, but the socket teardown threw - that is not a stop",
        )
    }

    @Test
    fun aWedgedCloseIsBoundedByTheSessionsOwnBudget() = runTest {
        // The budget used to cover only the join, so a close that hung
        // was bounded by nothing the session controlled.
        val running = CompletableDeferred<Unit>()
        var stopResult: Boolean? = null
        val owner = launch {
            val session = TransportSession(
                ownerScope = CoroutineScope(currentCoroutineContext()),
                connectLoop = { running.complete(Unit); awaitCancellation() },
                closeTransport = { delay(Long.MAX_VALUE / 4) },
                stopTimeoutMs = 200L,
            )
            session.start()
            running.await()
            stopResult = session.stop("privacy_mode_changed")
        }
        testScheduler.advanceUntilIdle()
        owner.join()

        assertEquals(
            false,
            stopResult,
            "a close that never returns must be reported as an unconfirmed stop, " +
                "and must not hang the caller",
        )
    }

    @Test
    fun afterCompletionWaitsForAParkedFinalizerBeforeReleasing() = runTest {
        // The discriminating case for the join inside afterCompletion. A
        // version WITHOUT that join passes the plain "release happened"
        // fixture, because the release still runs - just too early, while
        // the session is still finishing.
        //
        // Shaped like production: afterCompletion is called from the
        // owner's own body, which is what is being cancelled. A child
        // `launch` would not do - a coroutine that has not started when
        // its parent is cancelled never runs at all.
        val releaseHeld = CompletableDeferred<Unit>()
        val running = CompletableDeferred<Unit>()
        var finalizerDone = false
        var releasedWhileParked: Boolean? = null

        val owner = launch {
            val session = TransportSession(
                ownerScope = CoroutineScope(currentCoroutineContext()),
                connectLoop = {
                    running.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        // A teardown that takes a while, as a real socket
                        // close does.
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            releaseHeld.await()
                            finalizerDone = true
                        }
                    }
                },
                closeTransport = { },
            )
            session.start()
            running.await()
            try {
                session.awaitCompletion()
            } finally {
                session.afterCompletion { releasedWhileParked = !finalizerDone }
            }
        }
        runCurrent()
        owner.cancel(CancellationException("ownership_handover"))
        runCurrent()

        assertEquals(
            null,
            releasedWhileParked,
            "nothing may be released while the session's finalizer is still parked",
        )

        releaseHeld.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(
            false,
            releasedWhileParked,
            "and when it does run, the session has genuinely finished",
        )
        assertTrue(finalizerDone)
    }

    @Test
    fun theStopBudgetStaysBelowThePerPermitBudget() {
        // The unreachable-code defect: when the inner budget was LARGER
        // than the outer per-permit one, the outer always fired first and
        // the code after the inner wait could never run.
        assertTrue(
            TransportSession.DEFAULT_STOP_TIMEOUT_MS <
                PrivacyModeCoordinator.PERMIT_ATTEMPT_TIMEOUT_MS,
            "a session stop budget at or above the per-permit budget makes the " +
                "confirmation unreachable",
        )
    }

    @Test
    fun afterCompletionRunsEvenWhenTheOwnerIsBeingCancelled() = runTest {
        // The permit release lives here. An ordinary `finally` runs while
        // the coroutine is being cancelled and can be skipped at a
        // suspension point, leaving a permit registered against a closed
        // socket.
        var released = false
        val running = CompletableDeferred<Unit>()
        val owner = launch {
            val session = TransportSession(
                ownerScope = CoroutineScope(currentCoroutineContext()),
                connectLoop = { running.complete(Unit); awaitCancellation() },
                closeTransport = { },
            )
            session.start()
            running.await()
            try {
                session.awaitCompletion()
            } finally {
                session.afterCompletion { delay(1); released = true }
            }
        }
        runCurrent()
        owner.cancel(CancellationException("ownership_handover"))
        owner.join()

        assertTrue(released, "the release must survive the cancellation that triggered it")
    }

    @Test
    fun anUndisturbedSessionEndsOnItsOwnAndNeedsNoStop() = runTest {
        // The control.
        val transport = Transport()
        val owner = launch {
            val session = TransportSession(
                ownerScope = CoroutineScope(currentCoroutineContext()),
                connectLoop = { transport.connected() },
                closeTransport = { transport.disconnected() },
            )
            session.start()
            session.awaitCompletion()
        }
        owner.join()

        assertEquals(1, transport.connects)
        assertEquals(0, transport.disconnects, "nothing tore it down; it simply finished")
    }
}
