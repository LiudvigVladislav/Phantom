// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the Trek 2 Stage 2A (A5) [EnvelopeId] boundary
 * wrapper.
 *
 * Cover both construction paths:
 *
 *   * [EnvelopeId.fromWire] echoes the raw value byte-for-byte — the
 *     only sanctioned construction path for relay-echoed ids on the
 *     receive side. Tested without libsodium because the wrapper is
 *     a thin String pass-through.
 *   * [EnvelopeId.random] generates a fresh id via the upstream
 *     [LibsodiumCsprng]. Tested under a libsodium init guard
 *     (`init()` below) because the production code path materialises
 *     the value via `LibsodiumCsprng.hex(16)`. Round-2 P2
 *     (Vladislav 2026-06-09): even though the same path is exercised
 *     by `CsprngTest.hex(16)` in the crypto module, asserting on the
 *     wrapper directly gives the test name the future failure
 *     signal — a regression to a String-typed id would have to walk
 *     past *this* test to ship.
 *   * Value-class equality is by underlying String — covered by
 *     `two_ids_with_same_value_are_equal` /
 *     `two_ids_with_different_values_are_not_equal` below.
 *
 * The "no public constructor" guarantee is enforced by Kotlin's
 * `internal` visibility on the constructor, which is a compile-time
 * check — any attempt to write `EnvelopeId("...")` from outside the
 * module fails `compileKotlinMetadata` before reaching test runtime.
 */
class EnvelopeIdTest {

    private suspend fun init() {
        if (!LibsodiumInitializer.isInitialized()) {
            LibsodiumInitializer.initialize()
        }
    }

    @Test
    fun fromWire_echoes_value_verbatim() {
        val raw = "deadbeefcafebabe1234567890abcdef"
        val id = EnvelopeId.fromWire(raw)
        assertEquals(raw, id.value)
    }

    @Test
    fun fromWire_preserves_arbitrary_strings() {
        // The wrapper does not validate the input — Stage 3 migration
        // will add format checks if the audit confirms relay-echoed
        // ids are always 32-char hex. For Stage 2A the wrapper is
        // intentionally a thin type witness.
        val cases = listOf(
            "",
            "x",
            "DEADBEEF",
            "12345",
            "0".repeat(64),
        )
        for (raw in cases) {
            assertEquals(raw, EnvelopeId.fromWire(raw).value, "input=$raw")
        }
    }

    @Test
    fun two_ids_with_same_value_are_equal() {
        val a = EnvelopeId.fromWire("abc123")
        val b = EnvelopeId.fromWire("abc123")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun two_ids_with_different_values_are_not_equal() {
        val a = EnvelopeId.fromWire("abc123")
        val b = EnvelopeId.fromWire("abc124")
        assertNotEquals(a, b)
    }

    // ── random() — Round-2 P2 ─────────────────────────────────────────────────

    @Test
    fun random_returns_32_lowercase_hex_chars() = runTest {
        init()
        val id = EnvelopeId.random()
        assertEquals(32, id.value.length, "id=${id.value}")
        // Stage 3 migration audit will tighten this to a `fromWire`
        // validator; until then the alphabet check here pins the
        // wire-format contract that the relay's regex parser expects.
        assertTrue(
            id.value.all { c -> c in '0'..'9' || c in 'a'..'f' },
            "id=${id.value} contains non-hex character",
        )
    }

    @Test
    fun random_two_draws_differ() = runTest {
        init()
        val a = EnvelopeId.random()
        val b = EnvelopeId.random()
        // 128-bit collision probability is ~10⁻³⁸ — well below the
        // flakiness threshold for any production CI run.
        assertNotEquals(a, b)
        assertNotEquals(a.value, b.value)
    }
}
