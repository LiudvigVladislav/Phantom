// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * R-N1.16 P1 - what a privacy switch may claim.
 *
 * The rule is conjunctive on purpose. A switch may present the new mode
 * as active only when the sockets from the old policy are confirmed
 * closed AND the chain walk that owned the connect lease is confirmed
 * stopped. Either one outstanding means something from the previous
 * posture may still be running, and calling that a completed switch is
 * the silent downgrade written into the UI rather than into the socket.
 *
 * The decision is a function so it can be driven directly: review kept
 * finding that a source tripwire proves a call exists, not that the
 * boundary behaves.
 *
 * NOT covered here, and deliberately: whether any screen reads the
 * result. That is UI wiring, outside this round's authorisation, and it
 * is a blocker rather than a limitation - see the package notes.
 */
class PrivacyModeChangeDecisionTest {

    @Test
    fun aSwitchThatClosedEverythingIsApplied() {
        val result = decidePrivacyModeChange(
            requested = PrivacyMode.Ghost,
            effective = PrivacyMode.Standard,
            policyChangeComplete = true,
            walkQuiesced = true,
            stillOpen = 0,
        )
        assertEquals(PrivacyModeChangeResult.Applied(PrivacyMode.Ghost), result)
    }

    @Test
    fun anUnclosedSocketBlocksTheSwitchAndKeepsTheOldEffectiveMode() {
        val result = decidePrivacyModeChange(
            requested = PrivacyMode.Ghost,
            effective = PrivacyMode.Standard,
            policyChangeComplete = false,
            walkQuiesced = true,
            stillOpen = 1,
        )
        val blocked = assertIs<PrivacyModeChangeResult.Blocked>(result)
        assertEquals(PrivacyMode.Ghost, blocked.requested, "Ghost is what was asked for")
        assertEquals(
            PrivacyMode.Standard,
            blocked.effective,
            "but Standard is what is still in force - presenting Ghost as active over " +
                "a live Direct socket is the downgrade this decision exists to refuse",
        )
        assertEquals(1, blocked.stillOpen)
        assertEquals("sockets_from_previous_policy_still_open", blocked.reason)
    }

    @Test
    fun anUnquiescedWalkAlsoBlocksTheSwitch() {
        val result = decidePrivacyModeChange(
            requested = PrivacyMode.Ghost,
            effective = PrivacyMode.Standard,
            policyChangeComplete = true,
            walkQuiesced = false,
            stillOpen = 0,
        )
        val blocked = assertIs<PrivacyModeChangeResult.Blocked>(result)
        assertEquals(PrivacyMode.Standard, blocked.effective)
        assertEquals("chain_walk_not_quiesced", blocked.reason)
    }

    @Test
    fun bothOutstandingReportsTheSocketReasonFirst() {
        // Not arbitrary: an open socket is the more specific and more
        // actionable of the two, and it is the one the recovery sweep
        // acts on.
        val blocked = assertIs<PrivacyModeChangeResult.Blocked>(
            decidePrivacyModeChange(
                requested = PrivacyMode.Ghost,
                effective = PrivacyMode.Standard,
                policyChangeComplete = false,
                walkQuiesced = false,
                stillOpen = 2,
            ),
        )
        assertEquals("sockets_from_previous_policy_still_open", blocked.reason)
        assertEquals(2, blocked.stillOpen)
    }

    // -- lateness (review: Standard -> Ghost -> Private) ----------------

    @Test
    fun aSwitchOvertakenByANewerRequestCommitsNothing() {
        // The dangerous case is a LATE SUCCESS. A slow Standard -> Ghost
        // that finishes cleanly after the user has asked for Private
        // would otherwise install Ghost - a mode nobody currently wants,
        // written by an arrival that lost the race.
        val result = decidePrivacyModeChange(
            requested = PrivacyMode.Ghost,
            effective = PrivacyMode.Standard,
            policyChangeComplete = true,
            walkQuiesced = true,
            stillOpen = 0,
            changeEpoch = 4L,
            liveEpoch = 5L,
        )
        val superseded = assertIs<PrivacyModeChangeResult.Superseded>(result)
        assertEquals(PrivacyMode.Ghost, superseded.requested)
        assertEquals(
            PrivacyMode.Standard,
            superseded.effective,
            "the effective mode is left for the newer request to decide",
        )
        assertEquals(4L, superseded.changeEpoch)
        assertEquals(5L, superseded.liveEpoch)
    }

    @Test
    fun latenessIsCheckedBeforeCompleteness() {
        // A superseded switch that ALSO failed must still report
        // superseded: reporting Blocked would invite a retry of a mode
        // nobody asked for any more.
        val result = decidePrivacyModeChange(
            requested = PrivacyMode.Ghost,
            effective = PrivacyMode.Standard,
            policyChangeComplete = false,
            walkQuiesced = false,
            stillOpen = 1,
            changeEpoch = 1L,
            liveEpoch = 3L,
        )
        assertIs<PrivacyModeChangeResult.Superseded>(result)
    }

    @Test
    fun aSwitchThatIsStillTheLatestRequestAppliesNormally() {
        // The control for both lateness fixtures.
        val result = decidePrivacyModeChange(
            requested = PrivacyMode.Private,
            effective = PrivacyMode.Standard,
            policyChangeComplete = true,
            walkQuiesced = true,
            stillOpen = 0,
            changeEpoch = 7L,
            liveEpoch = 7L,
        )
        assertEquals(PrivacyModeChangeResult.Applied(PrivacyMode.Private), result)
    }
}
