// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Unit tests for the Trek 2 Stage 2A (A5) [EnvelopeId] boundary
 * wrapper.
 *
 * Scope is intentionally narrow: the random-generation path is
 * exercised indirectly via the upstream `CsprngTest` in the crypto
 * module (which initialises libsodium and proves `Csprng.hex(16)`
 * returns a 32-character lowercase hex string — exactly what
 * `EnvelopeId.random()` returns wrapped in the value class).
 *
 * The tests here cover the value-class semantics that do NOT need
 * a runtime libsodium init:
 *
 *   * [EnvelopeId.fromWire] echoes the raw value byte-for-byte — it
 *     is the only sanctioned construction path for relay-echoed ids
 *     on the receive side.
 *   * The `value` property surfaces the underlying String unchanged.
 *   * Value-class equality is by underlying String.
 *
 * The "no public constructor" guarantee is enforced by Kotlin's
 * `internal` visibility on the constructor, which is a compile-time
 * check — any attempt to write `EnvelopeId("...")` from outside the
 * module fails `compileKotlinMetadata` before reaching test runtime.
 */
class EnvelopeIdTest {

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
}
