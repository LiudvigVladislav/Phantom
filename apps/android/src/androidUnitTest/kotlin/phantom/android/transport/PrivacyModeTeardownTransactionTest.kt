// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import phantom.android.di.runPrivacyModeTeardown
import phantom.core.transport.RestEgressGate
import phantom.core.transport.TransportManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * N1-F2 R-N1.4/R-N1.5 — the privacy-mode transition.
 *
 * R-N1.3 re-asserted the caller's cancellation BETWEEN the REST
 * revocation and the socket teardown, so leaving the screen mid-switch
 * could leave the old Direct WSS connection up while Settings already
 * showed Private/Ghost. R-N1.4 made the three steps one `NonCancellable`
 * transaction.
 *
 * R-N1.5 adds the half R-N1.4 missed: the transaction could report a
 * clean switch it had not achieved, because `disconnect()` discarded its
 * join result and `release()` swallowed subsystem-stop failures. Both
 * now return structured outcomes, and `clean` reflects them.
 */
class PrivacyModeTeardownTransactionTest {

    private fun revocation(joinedCleanly: Boolean = true) = RestEgressGate.RevocationResult(
        generation = 1L,
        nativeCallsCancelled = 0,
        dispatchesCancelled = 0,
        joinedCleanly = joinedCleanly,
    )

    private fun released(
        xray: Throwable? = null,
        tor: Throwable? = null,
    ) = TransportManager.ReleaseOutcome(xrayFailure = xray, torFailure = tor)

    // ── Cancellation must not escape mid-transition ───────────────────

    @Test
    fun caller_cancellation_during_revocation_still_disconnects_and_releases() = runTest {
        val revokeEntered = CompletableDeferred<Unit>()
        val releaseRevoke = CompletableDeferred<Unit>()
        var disconnected = false
        var releasedCalled = false

        val caller = launch(StandardTestDispatcher(testScheduler)) {
            runPrivacyModeTeardown(
                revoke = {
                    revokeEntered.complete(Unit)
                    releaseRevoke.await()
                    revocation()
                },
                // Real teardown steps suspend. A cancelled scope aborts
                // them at their first suspension point, so the yield()
                // here is what lets this fixture tell a NonCancellable
                // transaction from an ordinary one.
                disconnectAndJoin = { yield(); disconnected = true; true },
                handOverWalk = { true },
                release = { yield(); releasedCalled = true; released() },
            )
        }
        runCurrent()
        assertTrue(revokeEntered.isCompleted, "the teardown must have started")

        caller.cancel()
        releaseRevoke.complete(Unit)
        runCurrent()
        caller.join()

        assertTrue(
            disconnected,
            "the old Direct WSS connection was never disconnected: the caller's cancellation " +
                "escaped mid-transition, so Settings shows Private/Ghost while the old socket " +
                "is still up",
        )
        assertTrue(releasedCalled, "the transport manager was never released")
    }

    @Test
    fun caller_cancellation_between_revoke_and_disconnect_still_disconnects() = runTest {
        var disconnected = false
        var releasedCalled = false
        val job = Job()

        val caller = launch(StandardTestDispatcher(testScheduler) + job) {
            runPrivacyModeTeardown(
                revoke = {
                    // Cancel exactly where R-N1.3 re-asserted the caller's
                    // cancellation: after the revocation, before teardown.
                    job.cancel()
                    yield()
                    revocation()
                },
                disconnectAndJoin = { yield(); disconnected = true; true },
                handOverWalk = { true },
                release = { yield(); releasedCalled = true; released() },
            )
        }
        runCurrent()
        caller.join()

        assertTrue(disconnected, "disconnect must run even though the caller was cancelled")
        assertTrue(releasedCalled, "release must run even though the caller was cancelled")
    }

    // ── Every step runs even when an earlier one throws ───────────────

    @Test
    fun a_failing_revocation_does_not_skip_the_socket_teardown() = runTest {
        var disconnected = false
        var releasedCalled = false
        val outcome = runPrivacyModeTeardown(
            revoke = { error("gate exploded") },
            disconnectAndJoin = { disconnected = true; true },
            handOverWalk = { true },
            release = { releasedCalled = true; released() },
        )
        assertTrue(disconnected, "a throwing revocation must not leave the socket connected")
        assertTrue(releasedCalled)
        assertNotNull(outcome.revokeFailure, "the failure must be reported, not swallowed")
        assertFalse(outcome.clean)
    }

    @Test
    fun a_failing_disconnect_does_not_skip_the_release() = runTest {
        var releasedCalled = false
        val outcome = runPrivacyModeTeardown(
            revoke = { revocation() },
            disconnectAndJoin = { error("socket stuck") },
            handOverWalk = { true },
            release = { releasedCalled = true; released() },
        )
        assertTrue(releasedCalled, "a throwing disconnect must not skip the release")
        assertNotNull(outcome.disconnectFailure)
        assertFalse(outcome.clean)
    }

    // ── R-N1.5: a switch must not report clean when it was not ────────

    @Test
    fun a_wss_join_timeout_makes_the_transition_non_clean() = runTest {
        // disconnect() used to DISCARD this Boolean, so a teardown that
        // timed out was indistinguishable from a clean one and the switch
        // was reported clean while the old socket could still be alive.
        val outcome = runPrivacyModeTeardown(
            revoke = { revocation() },
            disconnectAndJoin = { false },
            handOverWalk = { true },
            release = { released() },
        )
        assertEquals(false, outcome.disconnectJoinedCleanly)
        assertFalse(
            outcome.clean,
            "a WSS teardown that did not join within its budget must not be reported as a " +
                "clean privacy switch",
        )
    }

    @Test
    fun an_xray_stop_failure_makes_the_transition_non_clean() = runTest {
        // release() used to swallow this in a bare runCatching.
        val outcome = runPrivacyModeTeardown(
            revoke = { revocation() },
            disconnectAndJoin = { true },
            handOverWalk = { true },
            release = { released(xray = IllegalStateException("xray refused to stop")) },
        )
        assertEquals(false, outcome.release?.clean)
        assertFalse(
            outcome.clean,
            "a proxy subsystem that refused to stop must not be reported as a clean switch",
        )
    }

    @Test
    fun a_tor_stop_failure_makes_the_transition_non_clean() = runTest {
        val outcome = runPrivacyModeTeardown(
            revoke = { revocation() },
            disconnectAndJoin = { true },
            handOverWalk = { true },
            release = { released(tor = IllegalStateException("tor refused to stop")) },
        )
        assertEquals(false, outcome.release?.clean)
        assertFalse(outcome.clean)
    }

    @Test
    fun a_non_clean_revocation_makes_the_transition_non_clean() = runTest {
        val outcome = runPrivacyModeTeardown(
            revoke = { revocation(joinedCleanly = false) },
            disconnectAndJoin = { true },
            handOverWalk = { true },
            release = { released() },
        )
        assertEquals(false, outcome.revocation?.joinedCleanly)
        assertFalse(outcome.clean)
    }

    // ── Non-vacuity control ───────────────────────────────────────────

    @Test
    fun the_ordinary_path_runs_every_step_once_and_reports_clean() = runTest {
        var revokes = 0
        var disconnects = 0
        var releases = 0
        val outcome = runPrivacyModeTeardown(
            revoke = { revokes++; revocation() },
            disconnectAndJoin = { disconnects++; true },
            handOverWalk = { true },
            release = { releases++; released() },
        )
        assertEquals(1, revokes)
        assertEquals(1, disconnects)
        assertEquals(1, releases)
        assertTrue(
            outcome.clean,
            "the healthy path must report clean — otherwise every assertion above would hold " +
                "for a transaction that can never succeed",
        )
        assertEquals(null, outcome.revokeFailure)
        assertEquals(null, outcome.disconnectFailure)
        assertEquals(null, outcome.releaseFailure)
        assertEquals(true, outcome.disconnectJoinedCleanly)
        assertEquals(true, outcome.release?.clean)
    }

    // ------------------------------------------------------------------
    // R-N1.17 P1 - which teardowns may be published as a finished switch
    // ------------------------------------------------------------------

    @Test
    fun a_failing_revocation_is_not_a_confirmed_teardown() = runTest {
        // The switch decision used to consult the subsystem release
        // alone. A REST egress revocation that threw still reached
        // Applied plus a successor, with old Direct REST leases possibly
        // still dispatching under the new posture.
        val outcome = runPrivacyModeTeardown(
            revoke = { error("revoke blew up") },
            disconnectAndJoin = { true },
            handOverWalk = { true },
            release = { released() },
        )
        assertFalse(outcome.confirmed, "a revocation that threw is not a finished switch")
    }

    @Test
    fun a_revocation_that_did_not_join_is_not_a_confirmed_teardown() = runTest {
        val outcome = runPrivacyModeTeardown(
            revoke = { revocation(joinedCleanly = false) },
            disconnectAndJoin = { true },
            handOverWalk = { true },
            release = { released() },
        )
        assertFalse(
            outcome.confirmed,
            "leases that may still be in flight are old-policy I/O outliving the switch",
        )
    }

    @Test
    fun a_failing_disconnect_is_not_a_confirmed_teardown() = runTest {
        val outcome = runPrivacyModeTeardown(
            revoke = { revocation() },
            disconnectAndJoin = { error("disconnect blew up") },
            handOverWalk = { true },
            release = { released() },
        )
        assertFalse(outcome.confirmed)
    }

    @Test
    fun a_wss_join_timeout_is_not_a_confirmed_teardown() = runTest {
        val outcome = runPrivacyModeTeardown(
            revoke = { revocation() },
            disconnectAndJoin = { false },
            handOverWalk = { true },
            release = { released() },
        )
        assertFalse(
            outcome.confirmed,
            "an unjoined WSS teardown means the old socket may still be up",
        )
    }

    @Test
    fun a_skipped_release_is_not_a_confirmed_teardown() = runTest {
        // The walk would not quiesce, so the teardown correctly refused
        // to release. A null release passes `clean` on a null check and
        // must not pass this one.
        val outcome = runPrivacyModeTeardown(
            revoke = { revocation() },
            disconnectAndJoin = { true },
            handOverWalk = { false },
            release = { null },
        )
        assertFalse(outcome.confirmed, "a release that never ran is not a confirmed one")
    }

    @Test
    fun the_ordinary_path_is_a_confirmed_teardown() = runTest {
        // The control: the rule must not refuse everything.
        val outcome = runPrivacyModeTeardown(
            revoke = { revocation() },
            disconnectAndJoin = { true },
            handOverWalk = { true },
            release = { released() },
        )
        assertTrue(outcome.confirmed)
    }
}
