// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Trek 2 Stage 2B-B (C2) — unit tests for [VerifyKeyState] /
 * [RefreshOutcome] / [classifyVerifyKeyResponse] / [transition].
 *
 * Ships test cell **M-B11** — verify-key state machine transitions:
 * `KeyAbsent → KeyPresent → KeySuspended → KeyPresent` paths exercised;
 * the corollary path (failed refresh → `KeySuspended`) is exercised
 * explicitly. The 12 cells of the locked matrix are covered as
 * separate test methods so a future drift on any single cell surfaces
 * by name.
 *
 * Additionally covers:
 *
 *   * [classifyVerifyKeyResponse] empty / valid / malformed branches,
 *     including the discriminators between `Empty` and `Malformed`
 *     (empty string ≠ malformed; the relay distinguishes "I do not
 *     have a verify key for this identity" from "my verify key is
 *     somehow not 64 lowercase-hex characters").
 *   * Construction-site invariants on [VerifyKeyState.KeyPresent] and
 *     [RefreshOutcome.Valid] — `init` blocks throw for malformed hex
 *     so the type itself carries the validity property and downstream
 *     verify paths can trust the shape.
 *   * Load-bearing property assertions: no `KeyPresent → KeyAbsent`
 *     transition exists (closes R2-S-B5 MAC verify downgrade); the
 *     forced-refresh-failure corollary path lands at `KeySuspended`.
 */
class VerifyKeyStateMachineTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** 64-char lowercase hex value, `"00" × 32`. */
    private val WELL_FORMED_HEX_A: String = "0".repeat(64)

    /** Different 64-char lowercase hex value, `"a1b2c3d4..."` repeated. */
    private val WELL_FORMED_HEX_B: String = "a1b2c3d4e5f60718".repeat(4)

    private fun keyPresent(hex: String): VerifyKeyState.KeyPresent =
        VerifyKeyState.KeyPresent(hex)

    // ── classifyVerifyKeyResponse ───────────────────────────────────────────

    @Test
    fun classify_empty_string_returns_Empty() {
        assertEquals(RefreshOutcome.Empty, classifyVerifyKeyResponse(""))
    }

    @Test
    fun classify_well_formed_64_char_lowercase_hex_returns_Valid_with_hex() {
        val outcome = classifyVerifyKeyResponse(WELL_FORMED_HEX_A)
        assertEquals(RefreshOutcome.Valid(WELL_FORMED_HEX_A), outcome)
    }

    @Test
    fun classify_uppercase_hex_returns_Malformed() {
        // Server contract emits lowercase only. Uppercase is a strict
        // rejection so a hostile injection cannot use casing tricks.
        val uppercase = "A".repeat(64)
        assertEquals(RefreshOutcome.Malformed, classifyVerifyKeyResponse(uppercase))
    }

    @Test
    fun classify_mixed_case_hex_returns_Malformed() {
        val mixed = "0".repeat(32) + "A".repeat(32)
        assertEquals(RefreshOutcome.Malformed, classifyVerifyKeyResponse(mixed))
    }

    @Test
    fun classify_non_hex_chars_returns_Malformed() {
        assertEquals(RefreshOutcome.Malformed, classifyVerifyKeyResponse("g".repeat(64)))
        assertEquals(RefreshOutcome.Malformed, classifyVerifyKeyResponse("z".repeat(64)))
        assertEquals(RefreshOutcome.Malformed, classifyVerifyKeyResponse("?".repeat(64)))
    }

    @Test
    fun classify_short_hex_returns_Malformed() {
        assertEquals(RefreshOutcome.Malformed, classifyVerifyKeyResponse("0".repeat(63)))
        assertEquals(RefreshOutcome.Malformed, classifyVerifyKeyResponse("0".repeat(32)))
        assertEquals(RefreshOutcome.Malformed, classifyVerifyKeyResponse("0"))
    }

    @Test
    fun classify_long_hex_returns_Malformed() {
        assertEquals(RefreshOutcome.Malformed, classifyVerifyKeyResponse("0".repeat(65)))
        assertEquals(RefreshOutcome.Malformed, classifyVerifyKeyResponse("0".repeat(128)))
    }

    @Test
    fun classify_odd_length_hex_returns_Malformed() {
        assertEquals(RefreshOutcome.Malformed, classifyVerifyKeyResponse("0".repeat(63)))
        assertEquals(RefreshOutcome.Malformed, classifyVerifyKeyResponse("0".repeat(31)))
    }

    @Test
    fun classify_empty_is_distinct_from_malformed() {
        // The discriminator matters: empty is the old-relay shape
        // (legacy unverified pass-through OK), malformed is a
        // protocol violation (fail-closed to KeySuspended). The two
        // routes diverge in the transition matrix.
        val emptyOutcome = classifyVerifyKeyResponse("")
        val malformedOutcome = classifyVerifyKeyResponse("not hex")
        // Concrete sealed type discrimination.
        assertEquals(RefreshOutcome.Empty, emptyOutcome)
        assertEquals(RefreshOutcome.Malformed, malformedOutcome)
        // And: transition routes diverge — from KeyAbsent, Empty stays
        // KeyAbsent, but Malformed transitions to KeySuspended.
        assertEquals(VerifyKeyState.KeyAbsent, transition(VerifyKeyState.KeyAbsent, emptyOutcome))
        assertEquals(
            VerifyKeyState.KeySuspended,
            transition(VerifyKeyState.KeyAbsent, malformedOutcome),
        )
    }

    // ── Sealed-type construction invariants ─────────────────────────────────

    @Test
    fun KeyPresent_rejects_empty_hex_at_construction() {
        assertFails { VerifyKeyState.KeyPresent("") }
    }

    @Test
    fun KeyPresent_rejects_malformed_hex_at_construction() {
        assertFails { VerifyKeyState.KeyPresent("nothex") }
        assertFails { VerifyKeyState.KeyPresent("0".repeat(63)) }
        assertFails { VerifyKeyState.KeyPresent("0".repeat(65)) }
        assertFails { VerifyKeyState.KeyPresent("A".repeat(64)) }
    }

    @Test
    fun KeyPresent_accepts_well_formed_64_char_lowercase_hex() {
        // No exception expected.
        VerifyKeyState.KeyPresent(WELL_FORMED_HEX_A)
        VerifyKeyState.KeyPresent(WELL_FORMED_HEX_B)
    }

    @Test
    fun Valid_outcome_rejects_empty_hex_at_construction() {
        assertFails { RefreshOutcome.Valid("") }
    }

    @Test
    fun Valid_outcome_rejects_malformed_hex_at_construction() {
        assertFails { RefreshOutcome.Valid("nothex") }
        assertFails { RefreshOutcome.Valid("0".repeat(63)) }
        assertFails { RefreshOutcome.Valid("A".repeat(64)) }
    }

    @Test
    fun Valid_outcome_accepts_well_formed_hex() {
        RefreshOutcome.Valid(WELL_FORMED_HEX_A)
        RefreshOutcome.Valid(WELL_FORMED_HEX_B)
    }

    // ── M-B11 — 12-cell transition matrix (one test per cell) ──────────────

    // Row group 1 — KeyAbsent + each outcome.

    @Test
    fun transition_KeyAbsent_Empty_stays_KeyAbsent() {
        assertEquals(
            VerifyKeyState.KeyAbsent,
            transition(VerifyKeyState.KeyAbsent, RefreshOutcome.Empty),
        )
    }

    @Test
    fun transition_KeyAbsent_Valid_becomes_KeyPresent_with_hex() {
        val next = transition(VerifyKeyState.KeyAbsent, RefreshOutcome.Valid(WELL_FORMED_HEX_A))
        assertEquals(VerifyKeyState.KeyPresent(WELL_FORMED_HEX_A), next)
    }

    @Test
    fun transition_KeyAbsent_Malformed_becomes_KeySuspended() {
        assertEquals(
            VerifyKeyState.KeySuspended,
            transition(VerifyKeyState.KeyAbsent, RefreshOutcome.Malformed),
        )
    }

    @Test
    fun transition_KeyAbsent_Failure_stays_KeyAbsent() {
        // Bootstrap-failure asymmetry: from KeyAbsent, Failure stays
        // KeyAbsent (no relay observed yet, the existing
        // `sessionToken == null` discipline pauses ingestion). From
        // KeyPresent, Failure transitions to KeySuspended (the L2
        // corollary). The asymmetry is intentional.
        assertEquals(
            VerifyKeyState.KeyAbsent,
            transition(VerifyKeyState.KeyAbsent, RefreshOutcome.Failure),
        )
    }

    // Row group 2 — KeyPresent + each outcome.

    @Test
    fun transition_KeyPresent_Empty_becomes_KeySuspended_downgrade_protected() {
        // Load-bearing downgrade protection: a previously-good session
        // observing an empty key from the relay MUST NOT regress to
        // KeyAbsent (legacy unverified pass-through). KeySuspended
        // halts ingestion until a non-empty key reappears.
        assertEquals(
            VerifyKeyState.KeySuspended,
            transition(keyPresent(WELL_FORMED_HEX_A), RefreshOutcome.Empty),
        )
    }

    @Test
    fun transition_KeyPresent_Valid_rotates_key_in_KeyPresent() {
        // Normal rotation: the new value replaces the old. Both cells
        // under `_inboundStateMutex` see only one consistent value
        // (M-B25 in C4 will exercise the concurrency property under
        // contention; here we just pin the data flow).
        val next = transition(keyPresent(WELL_FORMED_HEX_A), RefreshOutcome.Valid(WELL_FORMED_HEX_B))
        assertEquals(VerifyKeyState.KeyPresent(WELL_FORMED_HEX_B), next)
    }

    @Test
    fun transition_KeyPresent_Malformed_becomes_KeySuspended_downgrade_protected() {
        assertEquals(
            VerifyKeyState.KeySuspended,
            transition(keyPresent(WELL_FORMED_HEX_A), RefreshOutcome.Malformed),
        )
    }

    @Test
    fun transition_KeyPresent_Failure_becomes_KeySuspended_corollary_path() {
        // L2 forced-refresh-failure corollary path — the load-bearing
        // assertion: if a forced session/key refresh under L7 itself
        // fails, the state transitions to KeySuspended immediately.
        // No REST ingestion happens until a subsequent successful
        // refresh produces Valid(hex).
        assertEquals(
            VerifyKeyState.KeySuspended,
            transition(keyPresent(WELL_FORMED_HEX_A), RefreshOutcome.Failure),
        )
    }

    // Row group 3 — KeySuspended + each outcome.

    @Test
    fun transition_KeySuspended_Empty_stays_KeySuspended() {
        assertEquals(
            VerifyKeyState.KeySuspended,
            transition(VerifyKeyState.KeySuspended, RefreshOutcome.Empty),
        )
    }

    @Test
    fun transition_KeySuspended_Valid_recovers_to_KeyPresent_with_new_hex() {
        // Normal recovery path: a fresh valid key arrives and
        // ingestion resumes.
        val next = transition(VerifyKeyState.KeySuspended, RefreshOutcome.Valid(WELL_FORMED_HEX_A))
        assertEquals(VerifyKeyState.KeyPresent(WELL_FORMED_HEX_A), next)
    }

    @Test
    fun transition_KeySuspended_Malformed_stays_KeySuspended() {
        assertEquals(
            VerifyKeyState.KeySuspended,
            transition(VerifyKeyState.KeySuspended, RefreshOutcome.Malformed),
        )
    }

    @Test
    fun transition_KeySuspended_Failure_stays_KeySuspended() {
        // L2 corollary's "no REST ingestion until successful
        // re-authorisation" rule continues — a failure while already
        // suspended does not produce a regression to KeyAbsent.
        assertEquals(
            VerifyKeyState.KeySuspended,
            transition(VerifyKeyState.KeySuspended, RefreshOutcome.Failure),
        )
    }

    // ── M-B11 — named path tests ───────────────────────────────────────────

    @Test
    fun mb11_path_KeyAbsent_to_KeyPresent_to_KeySuspended_to_KeyPresent() {
        // Drives the named path from the scope-doc M-B11 spec:
        // KeyAbsent → KeyPresent → KeySuspended → KeyPresent.
        var state: VerifyKeyState = VerifyKeyState.KeyAbsent

        // KeyAbsent → KeyPresent via Valid.
        state = transition(state, RefreshOutcome.Valid(WELL_FORMED_HEX_A))
        assertEquals(VerifyKeyState.KeyPresent(WELL_FORMED_HEX_A), state)

        // KeyPresent → KeySuspended via Failure (forced-refresh-failure
        // corollary).
        state = transition(state, RefreshOutcome.Failure)
        assertEquals(VerifyKeyState.KeySuspended, state)

        // KeySuspended → KeyPresent via Valid (recovery).
        state = transition(state, RefreshOutcome.Valid(WELL_FORMED_HEX_B))
        assertEquals(VerifyKeyState.KeyPresent(WELL_FORMED_HEX_B), state)
    }

    @Test
    fun mb11_corollary_failed_refresh_from_KeyPresent_goes_to_KeySuspended() {
        // Explicit-name version of the load-bearing corollary
        // assertion. A future regression that mapped
        // KeyPresent + Failure → KeyAbsent would silently re-open the
        // legacy unverified ingestion bypass; this test fires by name
        // and is the first place a code reviewer should look when
        // touching the matrix.
        val keyPresentState = keyPresent(WELL_FORMED_HEX_A)
        val next = transition(keyPresentState, RefreshOutcome.Failure)
        assertEquals(VerifyKeyState.KeySuspended, next)
    }

    @Test
    fun mb11_corollary_failed_refresh_from_KeySuspended_stays_KeySuspended() {
        // The corollary's "no REST ingestion until successful
        // re-authorisation" rule continues across repeated failures
        // — does not regress to KeyAbsent.
        val next = transition(VerifyKeyState.KeySuspended, RefreshOutcome.Failure)
        assertEquals(VerifyKeyState.KeySuspended, next)
    }

    // ── Load-bearing structural property: NO KeyPresent → KeyAbsent edge ──

    @Test
    fun no_KeyPresent_to_KeyAbsent_transition_for_any_outcome() {
        // Closes R2-S-B5: the relay MUST NOT be able to downgrade an
        // already-active verify path back to unverified pass-through.
        // This property is checked exhaustively against the
        // RefreshOutcome cross-product so a future addition of an
        // outcome cannot accidentally introduce the regression.
        val keyPresentState = keyPresent(WELL_FORMED_HEX_A)
        val outcomes = listOf(
            RefreshOutcome.Empty,
            RefreshOutcome.Valid(WELL_FORMED_HEX_B),
            RefreshOutcome.Malformed,
            RefreshOutcome.Failure,
        )
        for (outcome in outcomes) {
            val next = transition(keyPresentState, outcome)
            check(next !is VerifyKeyState.KeyAbsent) {
                "Forbidden transition KeyPresent + $outcome → KeyAbsent — would reopen " +
                    "the legacy unverified ingestion bypass per R2-S-B5"
            }
        }
    }

    // ── Verify-key redaction in toString (P2 review fix) ────────────────────

    @Test
    fun KeyPresent_toString_does_not_leak_full_hex_payload() {
        // Default `data class` toString would emit the full 64-char
        // hex. The override redacts to an 8-char prefix + "REDACTED"
        // suffix so the verify-key cannot leak via logs, assertion
        // messages, or any caller that prints the state.
        val sensitiveHex = "deadbeef".repeat(8) // 64-char fixture
        val rendered = VerifyKeyState.KeyPresent(sensitiveHex).toString()
        assertFalse(
            sensitiveHex in rendered,
            "verify-key full hex leaked through toString: \"$rendered\"",
        )
        // 8-char prefix retained for diagnostic disambiguation.
        assertTrue(
            "deadbeef" in rendered,
            "expected 8-char hex prefix in redacted toString: \"$rendered\"",
        )
        // Explicit redaction signal so a reviewer scanning logs sees
        // the value was deliberately omitted.
        assertTrue(
            "REDACTED" in rendered,
            "expected REDACTED marker in toString: \"$rendered\"",
        )
    }

    @Test
    fun KeyPresent_toString_with_different_hex_renders_with_different_prefix() {
        // Make sure the redaction isn't so aggressive that two
        // distinct keys collapse to the same string. The 8-char
        // prefix is 32 bits — operationally unique across a session.
        val a = VerifyKeyState.KeyPresent("deadbeef".repeat(8)).toString()
        val b = VerifyKeyState.KeyPresent("cafebabe".repeat(8)).toString()
        assertTrue(a != b, "redacted toString must preserve diagnostic prefix")
        assertTrue("deadbeef" in a)
        assertTrue("cafebabe" in b)
    }

    @Test
    fun Valid_outcome_toString_does_not_leak_full_hex_payload() {
        // Same redaction contract on the RefreshOutcome side. The
        // outcome carries the same verify-key payload as
        // VerifyKeyState.KeyPresent so any code path that prints
        // a RefreshOutcome (logs, assertion messages, exception
        // surfaces) must not leak the secret.
        val sensitiveHex = "deadbeef".repeat(8)
        val rendered = RefreshOutcome.Valid(sensitiveHex).toString()
        assertFalse(
            sensitiveHex in rendered,
            "verify-key full hex leaked through Valid.toString: \"$rendered\"",
        )
        assertTrue("deadbeef" in rendered)
        assertTrue("REDACTED" in rendered)
    }

    @Test
    fun construction_failure_message_does_not_echo_offending_hex() {
        // A failed `require` on KeyPresent or Valid would surface the
        // offending hex if the message used `$hex` interpolation. The
        // value is rejected as malformed but is still attacker- or
        // bug-controllable; echoing it through logs that catch the
        // IllegalArgumentException is an unnecessary disclosure
        // channel. Both init blocks must avoid the echo.
        val malformedSecretishHex = "deadbeef".repeat(7) // 56 chars, malformed length
        val keyPresentErr = runCatching { VerifyKeyState.KeyPresent(malformedSecretishHex) }
            .exceptionOrNull()?.message
            ?: error("KeyPresent did not throw on malformed hex")
        assertFalse(
            malformedSecretishHex in keyPresentErr,
            "KeyPresent.init message leaked the offending hex: \"$keyPresentErr\"",
        )
        val validErr = runCatching { RefreshOutcome.Valid(malformedSecretishHex) }
            .exceptionOrNull()?.message
            ?: error("Valid did not throw on malformed hex")
        assertFalse(
            malformedSecretishHex in validErr,
            "Valid.init message leaked the offending hex: \"$validErr\"",
        )
    }

    @Test
    fun classifier_then_transition_idempotent_for_known_relay_shapes() {
        // End-to-end smoke: feed each known classifier branch through
        // the transition function and confirm the joint behaviour
        // matches the matrix.
        val initial = VerifyKeyState.KeyAbsent
        val pairs: List<Pair<String, VerifyKeyState>> = listOf(
            "" to VerifyKeyState.KeyAbsent,                       // KeyAbsent + Empty
            WELL_FORMED_HEX_A to VerifyKeyState.KeyPresent(WELL_FORMED_HEX_A), // KeyAbsent + Valid
            "nothex" to VerifyKeyState.KeySuspended,              // KeyAbsent + Malformed
        )
        for ((raw, expected) in pairs) {
            val outcome = classifyVerifyKeyResponse(raw)
            val next = transition(initial, outcome)
            assertEquals(expected, next, "classifier+transition for raw=\"$raw\"")
        }
    }
}
