// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R-N1.17 P1 - a permit in flight is still a permit.
 *
 * `requestMode` and `retryPending` took the permits out of the register,
 * swept them, and put the failures back afterwards. Between those two
 * moments the register looked EMPTY, so a new socket could be authorised
 * beside one that was still up: the two-concurrent-sockets defect,
 * reached through the bookkeeping rather than through a race.
 *
 * These park the sweep and look at the gap.
 */
class PrivacyModeInFlightRevokeTest {

    private class Socket {
        var opens = 0
            private set
        var closes = 0
            private set

        fun open() { opens += 1 }
        fun close() { closes += 1 }
        val isLive: Boolean get() = opens > closes
    }

    @Test
    fun noNewPermitIsIssuedWhileASweepIsStillRunning() = runTest {
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val socket = Socket()
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val permit = policy.acquirePermit(TransportKind.Direct, 0L) {
            parked.complete(Unit)
            release.await()
            socket.close()
        }
        assertNotNull(permit)
        permit.useToOpen { socket.open() }

        launch { policy.requestMode(PrivacyMode.Ghost) }
        runCurrent()
        assertTrue(parked.isCompleted, "precondition: the sweep is parked in the teardown")
        assertTrue(socket.isLive, "and the old socket is still up")

        // The register is momentarily empty of both lists - and must
        // still refuse.
        assertNull(
            policy.acquirePermit(TransportKind.Tor, policy.currentEpoch()) { },
            "a new socket must not be authorised beside one still being torn down",
        )
        assertTrue(policy.hasUnclosed(), "the in-flight permit is still visible")

        release.complete(Unit)
        runCurrent()
        assertEquals(1, socket.closes)
    }

    @Test
    fun aSecondRequestDuringASweepDoesNotLetTheFirstApplyOverIt() = runTest {
        // Rewritten: the previous version released the park BEFORE making
        // the second request, so there was no concurrency in it at all -
        // the name promised more than the body checked, which is the very
        // defect this round keeps finding, arriving in a test.
        //
        // Here the second request really lands while the first sweep is
        // parked.
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val permit = policy.acquirePermit(TransportKind.Direct, 0L) {
            parked.complete(Unit)
            release.await()
        }
        assertNotNull(permit)
        permit.useToOpen { }

        var ghostEpoch: Long? = null
        launch { ghostEpoch = policy.requestMode(PrivacyMode.Ghost) }
        runCurrent()
        assertTrue(parked.isCompleted, "precondition: the Ghost sweep is parked")

        // The second request lands WHILE the first sweep is still in it.
        var privateEpoch: Long? = null
        launch { privateEpoch = policy.requestMode(PrivacyMode.Private) }
        runCurrent()

        release.complete(Unit)
        runCurrent()

        assertNotNull(ghostEpoch)
        assertNotNull(privateEpoch)
        assertEquals(
            PrivacyMode.Private,
            policy.state.value.requested,
            "the newer request governs",
        )

        // The older switch completing must change nothing.
        policy.complete(
            epoch = ghostEpoch,
            policyChangeComplete = true,
            walkQuiesced = true,
        )
        assertEquals(PrivacyMode.Private, policy.state.value.requested)
        assertEquals(
            PrivacyMode.Standard,
            policy.state.value.effective,
            "a superseded switch commits no effective mode",
        )
    }

    @Test
    fun aRequestThatOverlapsAnEarlierSweepDoesNotReportItselfClean() = runTest {
        // R-N1.17 P1. The second request's own sweep finds nothing to do,
        // but the FIRST sweep's permits are still in flight. Reporting
        // stillOpen=0 would make this switch look complete, its caller
        // would not arm recovery, and the earlier sockets would be left
        // with nothing watching them.
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val permit = policy.acquirePermit(TransportKind.Direct, 0L) {
            parked.complete(Unit)
            release.await()
        }
        assertNotNull(permit)
        permit.useToOpen { }

        launch { policy.requestMode(PrivacyMode.Ghost) }
        runCurrent()
        assertTrue(parked.isCompleted)

        var privateEpoch: Long? = null
        launch { privateEpoch = policy.requestMode(PrivacyMode.Private) }
        runCurrent()

        assertTrue(
            policy.state.value.stillOpen > 0,
            "the overlapping request must count the earlier sweep's permits, not " +
                "report itself clean: stillOpen=${policy.state.value.stillOpen}",
        )
        assertEquals(PrivacyModeStatus.Blocked, policy.state.value.status)

        release.complete(Unit)
        runCurrent()
    }

    @Test
    fun aRetryFromASupersededEpochDoesNotApplyTheNewerMode() = runTest {
        // The subtler half: `retryPending` folds its result into the
        // snapshot. A retry that STARTED under one epoch must not, on
        // succeeding, publish Applied for a request that arrived while it
        // ran.
        //
        // The first version of this fixture did not model that: its retry
        // began after the newer request, so it legitimately belonged to
        // the newer epoch and applying it was correct. The retry has to
        // be parked mid-sweep for the question to arise at all.
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var wedged = true
        val permit = policy.acquirePermit(TransportKind.Direct, 0L) {
            if (wedged) error("still wedged")
            parked.complete(Unit)
            release.await()
        }
        assertNotNull(permit)
        permit.useToOpen { }

        policy.requestMode(PrivacyMode.Ghost)
        assertEquals(PrivacyModeStatus.Blocked, policy.state.value.status)

        // The retry starts under the Ghost epoch and parks inside it.
        wedged = false
        launch { policy.retryPending() }
        runCurrent()
        assertTrue(parked.isCompleted, "precondition: the retry is parked mid-sweep")

        // A newer request lands while it is parked.
        policy.requestMode(PrivacyMode.Private)
        val afterPrivate = policy.state.value

        release.complete(Unit)
        runCurrent()

        assertEquals(
            PrivacyMode.Private,
            policy.state.value.requested,
            "the newer request still stands",
        )
        assertEquals(
            afterPrivate.effective,
            policy.state.value.effective,
            "and a sweep that began under the older epoch publishes nothing",
        )
    }

    @Test
    fun anUndisturbedSweepStillReleasesTheRegister() = runTest {
        // The control: without it, a mutation that refused every permit
        // for ever would satisfy the fixtures above.
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val socket = Socket()
        val permit = policy.acquirePermit(TransportKind.Direct, 0L) { socket.close() }
        assertNotNull(permit)
        permit.useToOpen { socket.open() }

        policy.requestMode(PrivacyMode.Ghost)

        assertEquals(1, socket.closes, "the old socket closed")
        assertTrue(!policy.hasUnclosed(), "and the register is clear")
        assertNotNull(
            policy.acquirePermit(TransportKind.Tor, policy.currentEpoch()) { },
            "so the next socket may be authorised",
        )
    }
}
