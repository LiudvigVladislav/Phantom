// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R-N1.17 - what `complete()` is allowed to commit.
 *
 * These exist because two mutations went UNOBSERVED without them:
 * `MUT-F3-EFFECTIVE-COMMITTED-DESPITE-INCOMPLETE` and
 * `MUT-F3-STALE-COMPLETION-COMMITS-OLD-MODE`. The pure decision function
 * was covered and the happy path was covered; the two ways a switch can
 * commit something it must not were covered by nothing.
 *
 * That is what the mutations are for, and it is worth recording that
 * they earned their place here rather than confirming what was already
 * known.
 */
class PrivacyModeCoordinatorCompleteTest {

    @Test
    fun anIncompleteSwitchDoesNotMoveTheEffectiveMode() = runTest {
        // The app must not present Ghost as active while something from
        // the previous posture may still be carrying traffic.
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)

        val result = policy.complete(
            epoch = epoch,
            policyChangeComplete = false,
            walkQuiesced = true,
        )

        assertIs<PrivacyModeChangeResult.Blocked>(result)
        val snapshot = policy.state.value
        assertEquals(PrivacyModeStatus.Blocked, snapshot.status)
        assertEquals(
            PrivacyMode.Standard,
            snapshot.effective,
            "the effective mode stays where it was until the switch really finished",
        )
        assertEquals(PrivacyMode.Ghost, snapshot.requested, "the request still stands")
    }

    @Test
    fun anUnquiescedWalkAlsoBlocksTheCommit() = runTest {
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)

        val result = policy.complete(
            epoch = epoch,
            policyChangeComplete = true,
            walkQuiesced = false,
        )

        assertIs<PrivacyModeChangeResult.Blocked>(result)
        assertEquals(PrivacyMode.Standard, policy.state.value.effective)
        assertEquals("chain_walk_not_quiesced", policy.state.value.reason)
    }

    @Test
    fun anUnclosedSocketBlocksTheCommitEvenIfTheCallerSaysComplete() = runTest {
        // The caller's own view is not the last word: the authority holds
        // the register, and a socket still in it means the switch is not
        // finished whatever the caller believes.
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val permit = policy.acquirePermit(TransportKind.Direct, 0L) { error("wedged") }
        assertNotNull(permit)
        permit.useToOpen { }
        val epoch = policy.requestMode(PrivacyMode.Ghost)

        val result = policy.complete(
            epoch = epoch,
            policyChangeComplete = true,
            walkQuiesced = true,
        )

        assertIs<PrivacyModeChangeResult.Blocked>(result)
        assertEquals(PrivacyMode.Standard, policy.state.value.effective)
        assertTrue(policy.state.value.stillOpen > 0)
    }

    @Test
    fun aCompletionForASupersededEpochCommitsNothing() = runTest {
        // Standard -> Ghost -> Private, with the Ghost switch finishing
        // last. Its own success is what makes it dangerous: it would
        // install a mode the user has already replaced.
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val ghostEpoch = policy.requestMode(PrivacyMode.Ghost)
        policy.requestMode(PrivacyMode.Private)
        val before = policy.state.value

        val result = policy.complete(
            epoch = ghostEpoch,
            policyChangeComplete = true,
            walkQuiesced = true,
        )

        val superseded = assertIs<PrivacyModeChangeResult.Superseded>(result)
        assertEquals(ghostEpoch, superseded.changeEpoch)
        assertEquals(before.epoch, superseded.liveEpoch)
        assertEquals(
            before,
            policy.state.value,
            "a superseded completion changes nothing at all - not the mode, not the " +
                "status, not the reason",
        )
        assertEquals(PrivacyMode.Private, policy.state.value.requested)
    }

    @Test
    fun theCurrentEpochStillCommitsNormally() = runTest {
        // The control. Without it a mutation that refused every
        // completion would satisfy all four fixtures above.
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val epoch = policy.requestMode(PrivacyMode.Ghost)

        val result = policy.complete(
            epoch = epoch,
            policyChangeComplete = true,
            walkQuiesced = true,
        )

        assertIs<PrivacyModeChangeResult.Applied>(result)
        assertEquals(PrivacyMode.Ghost, policy.state.value.effective)
        assertEquals(PrivacyModeStatus.Applied, policy.state.value.status)
        assertFalse(policy.state.value.maySendReadReceipts)
    }
}
