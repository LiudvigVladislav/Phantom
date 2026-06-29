// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * QUIESCENCE-VALIDATION-L1-SYNTHETIC-MINI-LOCK §6 contract test
 * (2026-06-30). Exhaustively pins the [SyntheticTriggerResult] sealed
 * hierarchy so a future refactor that adds, drops, renames, or
 * re-types a member fails loudly here before the L1 synthetic trigger
 * surface ships a regression.
 *
 * Five `object` singletons + one [SyntheticTriggerResult.RefusedDurationOutOfRange]
 * `data class` per the L1 mini-lock §6 spec. The data class carries
 * three diagnostic fields (`requestedMs`, `minMs`, `maxMs`) so the
 * caller diagnoses the rejection without re-reading constants.
 */
class SyntheticTriggerResultTest {

    @Test
    fun fired_is_singleton_object() {
        val a = SyntheticTriggerResult.Fired
        val b = SyntheticTriggerResult.Fired
        assertSame(a, b)
        assertEquals("Fired", a.toString())
    }

    @Test
    fun refused_disabled_is_singleton_object() {
        val a = SyntheticTriggerResult.RefusedDisabled
        val b = SyntheticTriggerResult.RefusedDisabled
        assertSame(a, b)
        assertEquals("RefusedDisabled", a.toString())
    }

    @Test
    fun refused_not_connected_is_singleton_object() {
        val a = SyntheticTriggerResult.RefusedNotConnected
        val b = SyntheticTriggerResult.RefusedNotConnected
        assertSame(a, b)
        assertEquals("RefusedNotConnected", a.toString())
    }

    @Test
    fun refused_already_fired_is_singleton_object() {
        val a = SyntheticTriggerResult.RefusedAlreadyFired
        val b = SyntheticTriggerResult.RefusedAlreadyFired
        assertSame(a, b)
        assertEquals("RefusedAlreadyFired", a.toString())
    }

    @Test
    fun refused_already_armed_is_singleton_object() {
        val a = SyntheticTriggerResult.RefusedAlreadyArmed
        val b = SyntheticTriggerResult.RefusedAlreadyArmed
        assertSame(a, b)
        assertEquals("RefusedAlreadyArmed", a.toString())
    }

    @Test
    fun refused_duration_out_of_range_carries_three_diagnostic_fields() {
        val r = SyntheticTriggerResult.RefusedDurationOutOfRange(
            requestedMs = 12_000L,
            minMs = 25_000L,
            maxMs = 65_000L,
        )
        assertEquals(12_000L, r.requestedMs)
        assertEquals(25_000L, r.minMs)
        assertEquals(65_000L, r.maxMs)
    }

    @Test
    fun refused_duration_out_of_range_is_data_class_with_equality_by_value() {
        val a = SyntheticTriggerResult.RefusedDurationOutOfRange(12_000L, 25_000L, 65_000L)
        val b = SyntheticTriggerResult.RefusedDurationOutOfRange(12_000L, 25_000L, 65_000L)
        val c = SyntheticTriggerResult.RefusedDurationOutOfRange(80_000L, 25_000L, 65_000L)
        assertEquals(a, b)
        assertNotSame(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun all_six_members_are_distinct_subtypes_of_sealed_hierarchy() {
        val results: List<SyntheticTriggerResult> = listOf(
            SyntheticTriggerResult.Fired,
            SyntheticTriggerResult.RefusedDisabled,
            SyntheticTriggerResult.RefusedNotConnected,
            SyntheticTriggerResult.RefusedDurationOutOfRange(0L, 25_000L, 65_000L),
            SyntheticTriggerResult.RefusedAlreadyFired,
            SyntheticTriggerResult.RefusedAlreadyArmed,
        )
        assertEquals(6, results.size)
        // Each instance round-trips through the sealed type
        for (r in results) {
            assertIs<SyntheticTriggerResult>(r)
        }
        // Distinct identities for the five `object` members
        val objects = results.filterNot { it is SyntheticTriggerResult.RefusedDurationOutOfRange }
        assertEquals(5, objects.size)
        assertEquals(5, objects.toSet().size)
    }

    @Test
    fun exhaustive_when_arm_count_pins_member_count() {
        // Compile-time-exhaustive `when` over the sealed hierarchy.
        // Adding a new member to SyntheticTriggerResult without
        // updating this test causes the `when` to not be exhaustive
        // (compile error). Dropping a member causes the corresponding
        // arm to be removed manually — this test pins the count.
        val result: SyntheticTriggerResult = SyntheticTriggerResult.Fired
        val arms: Int = when (result) {
            SyntheticTriggerResult.Fired -> 1
            SyntheticTriggerResult.RefusedDisabled -> 2
            SyntheticTriggerResult.RefusedNotConnected -> 3
            is SyntheticTriggerResult.RefusedDurationOutOfRange -> 4
            SyntheticTriggerResult.RefusedAlreadyFired -> 5
            SyntheticTriggerResult.RefusedAlreadyArmed -> 6
        }
        assertEquals(1, arms) // result is Fired → arm 1
    }
}
