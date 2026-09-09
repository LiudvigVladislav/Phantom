// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R-N1.17 P1 - the startup read of two storage keys, and the capability
 * that replaces reading the mode twice.
 */
class PrivacyModeStartupLoadTest {

    // -- the five-case order -------------------------------------------

    @Test
    fun aValidCanonicalValueWins() {
        val load = loadPrivacyModeAtStartup(canonicalRaw = "Ghost", legacyRaw = "Standard")
        assertEquals(PrivacyMode.Ghost, load.mode)
        assertFalse(load.migrateLegacy)
        assertEquals("canonical", load.reason)
    }

    @Test
    fun aCorruptCanonicalValueFailsClosedAndDoesNotFallBackToLegacy() {
        // The dangerous shape: a laxer mirror overriding a corrupt
        // canonical key would be a downgrade caused by data corruption.
        val load = loadPrivacyModeAtStartup(canonicalRaw = "Gh0st", legacyRaw = "Standard")
        assertEquals(
            PrivacyMode.Ghost,
            load.mode,
            "unreadable means the most restrictive posture, not the mirror's opinion",
        )
        assertFalse(load.migrateLegacy)
        assertEquals("canonical_corrupt_fail_closed", load.reason)
    }

    @Test
    fun aLegacyOnlyValueIsUsedAndMigrated() {
        // The upgrade path. Reading Standard here would turn a Ghost user
        // into a Direct-first one at the moment they upgrade - the exact
        // silent downgrade this round exists to prevent, arriving through
        // storage rather than through a race.
        val load = loadPrivacyModeAtStartup(canonicalRaw = null, legacyRaw = "Ghost")
        assertEquals(PrivacyMode.Ghost, load.mode)
        assertTrue(load.migrateLegacy, "and the canonical key must be written before use")
        assertEquals("legacy_migrated", load.reason)
    }

    @Test
    fun aCorruptLegacyOnlyValueFailsClosed() {
        val load = loadPrivacyModeAtStartup(canonicalRaw = null, legacyRaw = "!!!")
        assertEquals(
            PrivacyMode.Ghost,
            load.mode,
            "something is stored and cannot be read; that is not the same as nothing",
        )
        assertFalse(load.migrateLegacy)
    }

    @Test
    fun bothAbsentIsAGenuinelyNewInstall() {
        val load = loadPrivacyModeAtStartup(canonicalRaw = null, legacyRaw = null)
        assertEquals(PrivacyMode.Standard, load.mode)
        assertFalse(load.migrateLegacy)
        assertEquals("new_install", load.reason)
    }

    @Test
    fun aValidCanonicalValueIsNotMigratedEvenWhenTheKeysDisagree() {
        // Two keys, one fact: the canonical one decides, and the mirror
        // is not consulted for a second opinion.
        val load = loadPrivacyModeAtStartup(canonicalRaw = "Private", legacyRaw = "Ghost")
        assertEquals(PrivacyMode.Private, load.mode)
        assertFalse(load.migrateLegacy)
    }

    // -- the capability ------------------------------------------------

    @Test
    fun readReceiptsFollowTheModeWhenNothingIsInFlight() = runTest {
        val standard = PrivacyModeCoordinator(PrivacyMode.Standard)
        assertTrue(standard.state.value.maySendReadReceipts)

        val ghost = PrivacyModeCoordinator(PrivacyMode.Ghost)
        assertFalse(ghost.state.value.maySendReadReceipts)

        val private = PrivacyModeCoordinator(PrivacyMode.Private)
        assertFalse(private.state.value.maySendReadReceipts)
    }

    @Test
    fun tighteningAppliesImmediatelyEvenWhileTheSwitchIsBlocked() = runTest {
        // Standard -> Ghost. The restriction must bite at once; waiting
        // for `effective` would keep leaking receipts for as long as the
        // teardown took.
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val permit = policy.acquirePermit(TransportKind.Direct, 0L) { error("wedged") }
        checkNotNull(permit).useToOpen { }

        policy.requestMode(PrivacyMode.Ghost)

        val snapshot = policy.state.value
        assertEquals(PrivacyModeStatus.Blocked, snapshot.status, "precondition: mid-switch")
        assertEquals(PrivacyMode.Standard, snapshot.effective)
        assertFalse(
            snapshot.maySendReadReceipts,
            "the Ghost restriction applies from the moment it is requested",
        )
    }

    @Test
    fun looseningWaitsForTheSwitchToActuallyComplete() = runTest {
        // Ghost -> Standard. Acting on `requested` would start leaking
        // receipts while the Ghost socket was still up.
        val policy = PrivacyModeCoordinator(PrivacyMode.Ghost)
        val permit = policy.acquirePermit(TransportKind.Tor, 0L) { error("wedged") }
        checkNotNull(permit).useToOpen { }

        policy.requestMode(PrivacyMode.Standard)

        val blocked = policy.state.value
        assertEquals(PrivacyModeStatus.Blocked, blocked.status, "precondition: mid-switch")
        assertEquals(PrivacyMode.Standard, blocked.requested)
        assertFalse(
            blocked.maySendReadReceipts,
            "relaxing must wait for Applied - the old posture is still live",
        )
    }

    @Test
    fun theCapabilityBecomesTrueOnlyOnceTheSwitchIsApplied() = runTest {
        val policy = PrivacyModeCoordinator(PrivacyMode.Ghost)
        policy.requestMode(PrivacyMode.Standard)
        policy.complete(
            epoch = policy.currentEpoch(),
            policyChangeComplete = true,
            walkQuiesced = true,
        )

        val applied = policy.state.value
        assertEquals(PrivacyModeStatus.Applied, applied.status)
        assertTrue(applied.maySendReadReceipts, "and only now may receipts be sent")
    }
}
