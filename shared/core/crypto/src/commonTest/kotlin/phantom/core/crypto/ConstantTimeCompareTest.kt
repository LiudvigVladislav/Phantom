// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the Trek 2 Stage 2B-B (L1) [constantTimeEquals] helper.
 *
 * The tests cover the functional contract — same content returns true,
 * any single-byte difference returns false, length mismatch returns
 * false. The tests deliberately do NOT measure timing; constant-time
 * properties are a code-shape obligation enforced by reading
 * [ConstantTimeCompare.kt], not a runtime invariant that JUnit can
 * meaningfully assert.
 */
class ConstantTimeCompareTest {

    @Test
    fun equal_empty_arrays_are_equal() {
        assertTrue(constantTimeEquals(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun equal_single_byte_arrays_are_equal() {
        assertTrue(constantTimeEquals(byteArrayOf(0), byteArrayOf(0)))
        assertTrue(constantTimeEquals(byteArrayOf(0x7F), byteArrayOf(0x7F)))
        assertTrue(constantTimeEquals(byteArrayOf(0xFF.toByte()), byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun equal_32_byte_arrays_are_equal() {
        val a = ByteArray(32) { i -> i.toByte() }
        val b = ByteArray(32) { i -> i.toByte() }
        assertTrue(constantTimeEquals(a, b))
    }

    @Test
    fun length_mismatch_returns_false() {
        assertFalse(constantTimeEquals(ByteArray(31), ByteArray(32)))
        assertFalse(constantTimeEquals(ByteArray(32), ByteArray(31)))
        assertFalse(constantTimeEquals(ByteArray(0), ByteArray(1)))
    }

    @Test
    fun single_byte_difference_at_position_zero_returns_false() {
        val a = ByteArray(32) { 0 }
        val b = ByteArray(32) { 0 }
        b[0] = 1
        assertFalse(constantTimeEquals(a, b))
    }

    @Test
    fun single_byte_difference_at_last_position_returns_false() {
        val a = ByteArray(32) { 0 }
        val b = ByteArray(32) { 0 }
        b[31] = 1
        assertFalse(constantTimeEquals(a, b))
    }

    @Test
    fun single_bit_difference_returns_false() {
        // Single high-bit flip in the middle byte.
        val a = ByteArray(32) { 0 }
        val b = ByteArray(32) { 0 }
        b[16] = 0x80.toByte()
        assertFalse(constantTimeEquals(a, b))
    }

    @Test
    fun every_byte_position_difference_returns_false() {
        // Sweep every byte position to confirm no off-by-one in the
        // XOR-accumulate loop.
        for (pos in 0 until 32) {
            val a = ByteArray(32) { 0 }
            val b = ByteArray(32) { 0 }
            b[pos] = 0xFF.toByte()
            assertFalse(
                constantTimeEquals(a, b),
                "constantTimeEquals returned true with diff at position $pos",
            )
        }
    }

    @Test
    fun bytes_outside_signed_range_are_handled() {
        // Java byte is signed; a naive `a[i] xor b[i]` returning Int could
        // sign-extend negative bytes inconsistently. Verify that high-bit
        // bytes treat equality correctly.
        val a = byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0x00, 0x7F)
        val b = byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0x00, 0x7F)
        assertTrue(constantTimeEquals(a, b))

        val c = byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0x00, 0x7F)
        val d = byteArrayOf(0x80.toByte(), 0xFE.toByte(), 0x00, 0x7F)
        assertFalse(constantTimeEquals(c, d))
    }
}
