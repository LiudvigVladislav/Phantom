// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the Trek 2 Stage 2A (A7) [Csprng] helper backed by
 * [LibsodiumCsprng].
 *
 * These tests do NOT cover randomness *quality* in any cryptographic
 * sense — that belongs to the upstream libsodium audit. They cover the
 * helper's API contract:
 *
 *   * [Csprng.bytes] returns exactly the requested byte count
 *   * [Csprng.hex] returns lowercase hex of length `byteCount * 2`
 *   * [Csprng.uniformLong] stays within the requested bound and
 *     rejects `boundExclusive <= 0`
 *   * Two consecutive draws of the same shape return distinct values
 *     (sanity ceiling — collision under 10⁻³⁸ for 16 bytes)
 */
class CsprngTest {

    private suspend fun init() {
        if (!LibsodiumInitializer.isInitialized()) {
            LibsodiumInitializer.initialize()
        }
    }

    // ── bytes ─────────────────────────────────────────────────────────────────

    @Test
    fun bytes_returns_exact_size() = runTest {
        init()
        for (n in listOf(1, 8, 16, 32, 64, 128)) {
            val out = LibsodiumCsprng.bytes(n)
            assertEquals(n, out.size, "bytes($n) returned ${out.size}")
        }
    }

    @Test
    fun bytes_rejects_non_positive() = runTest {
        init()
        assertFails { LibsodiumCsprng.bytes(0) }
        assertFails { LibsodiumCsprng.bytes(-1) }
    }

    @Test
    fun bytes_two_draws_differ() = runTest {
        init()
        val a = LibsodiumCsprng.bytes(16)
        val b = LibsodiumCsprng.bytes(16)
        // 128-bit collision probability is ~10⁻³⁸ — well below the
        // flakiness threshold for any production CI run.
        assertNotEquals(a.toList(), b.toList())
    }

    // ── hex ───────────────────────────────────────────────────────────────────

    @Test
    fun hex_length_is_byte_count_times_two() = runTest {
        init()
        for (n in listOf(1, 16, 32)) {
            val out = LibsodiumCsprng.hex(n)
            assertEquals(n * 2, out.length, "hex($n) returned len=${out.length}")
        }
    }

    @Test
    fun hex_uses_lowercase_hex_alphabet_only() = runTest {
        init()
        val out = LibsodiumCsprng.hex(64)
        assertTrue(out.all { c -> c in '0'..'9' || c in 'a'..'f' }, "out=$out")
    }

    @Test
    fun hex_two_draws_differ() = runTest {
        init()
        val a = LibsodiumCsprng.hex(16)
        val b = LibsodiumCsprng.hex(16)
        assertNotEquals(a, b)
    }

    // ── uniformLong ──────────────────────────────────────────────────────────

    @Test
    fun uniformLong_within_bound() = runTest {
        init()
        val bound = 5_000L
        repeat(200) {
            val v = LibsodiumCsprng.uniformLong(bound)
            assertTrue(v in 0L until bound, "draw=$v out of [0,$bound)")
        }
    }

    @Test
    fun uniformLong_small_bound() = runTest {
        init()
        repeat(100) {
            val v = LibsodiumCsprng.uniformLong(1L)
            assertEquals(0L, v, "bound=1 must always yield 0; got $v")
        }
    }

    @Test
    fun uniformLong_rejects_non_positive_bound() = runTest {
        init()
        assertFails { LibsodiumCsprng.uniformLong(0L) }
        assertFails { LibsodiumCsprng.uniformLong(-1L) }
    }

    @Test
    fun uniformLong_two_draws_differ_for_large_bound() = runTest {
        init()
        // With a 5_000 bound and 2 draws, collision probability is
        // 1/5000 = 0.02% — well above any reasonable flakiness floor.
        // Loop 10 times and require at least one differing pair.
        var differs = false
        repeat(10) {
            if (LibsodiumCsprng.uniformLong(5_000L) != LibsodiumCsprng.uniformLong(5_000L)) {
                differs = true
                return@repeat
            }
        }
        assertTrue(differs, "10 consecutive draws all collided — RNG broken")
    }
}
