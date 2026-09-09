// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R-N1.17 P1 - the two blockers of a privacy switch are independent, and
 * resolving one must not be taken as resolving the other.
 *
 * The sequence that went wrong:
 *
 *   the socket close fails, the walk is not confirmed stopped
 *     -> complete(false, false)
 *     -> only the SOCKET reason survives in the snapshot
 *     -> a later socket retry succeeds
 *     -> that retry reads the reason, concludes the walk was fine,
 *        and publishes Applied over a walk nobody stopped.
 *
 * Two facts need two fields, and a recovery may only finish the switch
 * when BOTH are confirmed.
 */
class DeferredHandoverAfterLateCloseTest {

    private class Socket {
        var closes = 0
            private set

        fun close() { closes += 1 }
    }

    @Test
    fun aSocketRecoveryDoesNotApplyTheModeWhileTheWalkIsUnconfirmed() = runTest {
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val socket = Socket()
        var wedged = true
        val permit = policy.acquirePermit(TransportKind.Direct, 0L) {
            if (wedged) error("close failed") else socket.close()
        }
        assertNotNull(permit)
        permit.useToOpen { }

        val epoch = policy.requestMode(PrivacyMode.Ghost)
        // BOTH blockers outstanding: the socket did not close and the
        // walk was not handed over.
        policy.complete(epoch = epoch, policyChangeComplete = false, walkQuiesced = false)
        assertEquals(PrivacyModeStatus.Blocked, policy.state.value.status)
        assertFalse(policy.state.value.walkQuiesced, "the walk blocker is remembered")

        // The socket recovery now succeeds.
        wedged = false
        val retry = policy.retryPending()

        assertTrue(retry.isComplete, "the socket really did close")
        assertEquals(1, socket.closes)
        assertEquals(
            PrivacyModeStatus.Blocked,
            policy.state.value.status,
            "but the switch is NOT applied: the walk was never confirmed stopped",
        )
        assertEquals(
            PrivacyMode.Standard,
            policy.state.value.effective,
            "and the effective mode has not moved",
        )
    }

    @Test
    fun theModeIsAppliedOnlyAfterBothBlockersClear() = runTest {
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val socket = Socket()
        var wedged = true
        val permit = policy.acquirePermit(TransportKind.Direct, 0L) {
            if (wedged) error("close failed") else socket.close()
        }
        assertNotNull(permit)
        permit.useToOpen { }

        val epoch = policy.requestMode(PrivacyMode.Ghost)
        policy.complete(epoch = epoch, policyChangeComplete = false, walkQuiesced = false)

        // First the socket, then the walk - in that order, so the second
        // confirmation is the one that finishes the switch.
        wedged = false
        policy.retryPending()

        policy.complete(epoch = epoch, policyChangeComplete = true, walkQuiesced = true)

        val applied = policy.state.value
        assertEquals(PrivacyModeStatus.Applied, applied.status)
        assertEquals(PrivacyMode.Ghost, applied.effective)
        assertEquals(0, applied.stillOpen)
        assertTrue(applied.walkQuiesced)

        // And only now may a new socket be authorised.
        assertNotNull(
            policy.acquirePermit(TransportKind.Tor, applied.epoch) { },
            "the register is clear, so the successor may connect",
        )
    }

    @Test
    fun aSocketRecoveryDoesFinishTheSwitchWhenTheWalkWasAlreadyConfirmed() = runTest {
        // The control, and the case the feature exists for: the walk was
        // handed over cleanly, only the socket lagged. That recovery IS
        // allowed to finish the switch without any further user action.
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val socket = Socket()
        var wedged = true
        val permit = policy.acquirePermit(TransportKind.Direct, 0L) {
            if (wedged) error("close failed") else socket.close()
        }
        assertNotNull(permit)
        permit.useToOpen { }

        val epoch = policy.requestMode(PrivacyMode.Ghost)
        policy.complete(epoch = epoch, policyChangeComplete = false, walkQuiesced = true)
        assertEquals(PrivacyModeStatus.Blocked, policy.state.value.status)
        assertTrue(policy.state.value.walkQuiesced)

        wedged = false
        policy.retryPending()
        // R-N1.17: the retry CLOSES sockets; completion belongs to
        // `complete()`. A retry that published Applied by itself made
        // the deferred settlement - still owing a release and a
        // successor - conclude there was nothing left to do.
        policy.complete(epoch = epoch, policyChangeComplete = true, walkQuiesced = true)

        assertEquals(
            PrivacyModeStatus.Applied,
            policy.state.value.status,
            "the only outstanding blocker cleared, so the switch completes itself",
        )
        assertEquals(PrivacyMode.Ghost, policy.state.value.effective)
        assertEquals(1, socket.closes)
    }

    @Test
    fun aFailedCloseKeepsThePermitAndRefusesANewOne() = runTest {
        // The permit-level half: a revocation that could not confirm the
        // close leaves the permit registered, and nothing new is issued
        // beside it.
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val permit = policy.acquirePermit(TransportKind.Direct, 0L) { error("close failed") }
        assertNotNull(permit)
        permit.useToOpen { }

        policy.requestMode(PrivacyMode.Ghost)

        assertTrue(permit.needsClose(), "the socket is still open")
        assertTrue(policy.hasUnclosed(), "and the permit is still registered")
        assertNull(
            policy.acquirePermit(TransportKind.Tor, policy.currentEpoch()) { },
            "so no second socket is authorised beside it",
        )

        // And an ordinary release cannot paper over it.
        assertFalse(
            permit.release(),
            "releasing a revoked-but-unclosed permit must be refused, or the failed " +
                "close would be silently forgotten",
        )
        assertTrue(policy.hasUnclosed(), "it stays registered for the retry")
    }
}
