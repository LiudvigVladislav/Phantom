// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * QUIESCENCE-VALIDATION-MC-HALF-MINI-LOCK §13.1 / §13.5 structural test
 * floor for the gate-only carve-out (2026-06-30).
 *
 * Tests the surface that [WsReconnectGate.kt] ITSELF defines, in pure
 * isolation. Instantiates ZERO production classes from outside
 * `WsReconnectGate.kt`. The state-transition logic that lives on
 * `RestStateMachine` (Direct-only fence, sticky-arm on Mode-2-detection
 * `Event.WsSessionEnded`, probe atomicity under concurrency, the
 * `routeEpoch` / `ownerGeneration` interlocks, etc.) is integration-
 * scoped per §13.5 and lands on the MC implementation PR alongside
 * `WsReconnectGateTest.kt` (PR #330's 1172-LOC integration suite) +
 * `RestFallbackOrchestratorQuiescenceWiringTest.kt`.
 *
 * Test floor:
 *   - sealed-class distinctness for the 5 [WsReconnectGate] states
 *     including compile-time exhaustiveness pinning via `when`
 *   - [ProbeToken.toString] redaction lock (returns `[REDACTED]`)
 *   - [WsReconnectGate.ProbeAvailable.toString] / [WsReconnectGate.ProbeClaimed.toString]
 *     / [WsReconnectPermit.ClaimedProbe.toString] redaction wraps the
 *     token via `[REDACTED]` not raw [Long]
 *   - [WsReconnectGate.CandidateProving] type-level absence of `token`
 *   - [simpleKind] coverage for each of the 5 states
 *   - [ProbeBudget.MAX_ATTEMPTS_LOCKED] / [ProbeBudget.MAX_ELAPSED_MS_LOCKED]
 *     locked constants
 *   - sealed-hierarchy distinctness + compile-time exhaustiveness for
 *     [WsReconnectPermit] / [ClaimResult] / [ProbeIssueResult] /
 *     [RouteChangeOutcome]
 *   - enum exhaustiveness for [ClaimFailureReason] / [ProbeIssueRejectReason]
 */
class WsReconnectGateStructuralTest {

    // ── 5 WsReconnectGate states are distinct identities ─────────────

    @Test
    fun open_is_singleton_object() {
        val a = WsReconnectGate.Open
        val b = WsReconnectGate.Open
        assertSame(a, b)
        // toString() is NOT pinned here — `object Open` has no `toString()`
        // override and falls back to the Kotlin runtime's synthetic-class
        // identity string. The telemetry-safe label comes from
        // `simpleKind()` (covered separately).
    }

    @Test
    fun quiesced_is_data_class_distinct_from_open() {
        val q = WsReconnectGate.Quiesced(stickyGen = 7)
        val o: WsReconnectGate = WsReconnectGate.Open
        assertNotEquals<WsReconnectGate>(o, q)
        assertEquals(7, q.stickyGen)
    }

    @Test
    fun five_states_round_trip_through_sealed_type() {
        val token = ProbeToken(0xCAFEL)
        val budget = ProbeBudget(
            budgetStartedAtMs = 0L,
            maxAttempts = ProbeBudget.MAX_ATTEMPTS_LOCKED,
            maxElapsedMs = ProbeBudget.MAX_ELAPSED_MS_LOCKED,
        )
        val states: List<WsReconnectGate> = listOf(
            WsReconnectGate.Open,
            WsReconnectGate.Quiesced(stickyGen = 1),
            WsReconnectGate.ProbeAvailable(
                stickyGen = 1,
                routeEpoch = 2L,
                token = token,
                budget = budget,
                generationFloor = 3L,
            ),
            WsReconnectGate.ProbeClaimed(
                stickyGen = 1,
                routeEpoch = 2L,
                token = token,
                ownerGeneration = 4L,
                budget = budget,
            ),
            WsReconnectGate.CandidateProving(stickyGen = 1, sessionEpoch = 5L),
        )
        assertEquals(5, states.size)
        // Pair-wise distinct subtypes
        val classes = states.map { it::class }.toSet()
        assertEquals(5, classes.size)
    }

    @Test
    fun gate_state_exhaustive_when_pins_member_count() {
        // Compile-time-exhaustive `when` over the sealed hierarchy.
        // Adding a state to WsReconnectGate without updating this test
        // makes the `when` non-exhaustive (compile error). Dropping a
        // state removes the matching arm — also surfaced at compile time.
        val g: WsReconnectGate = WsReconnectGate.Open
        val arms: Int = when (g) {
            WsReconnectGate.Open -> 1
            is WsReconnectGate.Quiesced -> 2
            is WsReconnectGate.ProbeAvailable -> 3
            is WsReconnectGate.ProbeClaimed -> 4
            is WsReconnectGate.CandidateProving -> 5
        }
        assertEquals(1, arms) // g is Open → arm 1
    }

    // ── ProbeToken redaction lock ────────────────────────────────────

    @Test
    fun probe_token_to_string_is_redacted_never_value() {
        val raw = 0xDEAD_BEEF_CAFE_BABEuL.toLong()
        val token = ProbeToken(raw)
        assertEquals("[REDACTED]", token.toString())
        // Even with a different raw value the redaction holds.
        val token2 = ProbeToken(0x1234L)
        assertEquals("[REDACTED]", token2.toString())
        // Two tokens with the same value compare equal — the redaction
        // is `toString()` only, not value-erasure.
        assertEquals(token, ProbeToken(raw))
    }

    @Test
    fun probe_available_to_string_wraps_token_via_redacted_label() {
        val token = ProbeToken(0xABCDL)
        val budget = ProbeBudget(
            budgetStartedAtMs = 0L,
            maxAttempts = ProbeBudget.MAX_ATTEMPTS_LOCKED,
            maxElapsedMs = ProbeBudget.MAX_ELAPSED_MS_LOCKED,
        )
        val pa = WsReconnectGate.ProbeAvailable(
            stickyGen = 1,
            routeEpoch = 2L,
            token = token,
            budget = budget,
            generationFloor = 3L,
        )
        val s = pa.toString()
        assertTrue(s.contains("token=[REDACTED]"), "ProbeAvailable.toString must redact token; got: $s")
        assertTrue(!s.contains("0xABCD"), "raw token value must not appear in toString; got: $s")
        assertTrue(!s.contains("43981"), "raw token decimal value (0xABCD) must not appear in toString; got: $s")
    }

    @Test
    fun probe_claimed_to_string_wraps_token_via_redacted_label() {
        val token = ProbeToken(0xABCDL)
        val budget = ProbeBudget(
            budgetStartedAtMs = 0L,
            maxAttempts = ProbeBudget.MAX_ATTEMPTS_LOCKED,
            maxElapsedMs = ProbeBudget.MAX_ELAPSED_MS_LOCKED,
        )
        val pc = WsReconnectGate.ProbeClaimed(
            stickyGen = 1,
            routeEpoch = 2L,
            token = token,
            ownerGeneration = 4L,
            budget = budget,
        )
        val s = pc.toString()
        assertTrue(s.contains("token=[REDACTED]"), "ProbeClaimed.toString must redact token; got: $s")
        assertTrue(!s.contains("0xABCD"), "raw token value must not appear in toString; got: $s")
        assertTrue(!s.contains("43981"), "raw token decimal value (0xABCD) must not appear in toString; got: $s")
    }

    @Test
    fun claimed_probe_permit_to_string_wraps_token_via_redacted_label() {
        val token = ProbeToken(0xABCDL)
        val budget = ProbeBudget(
            budgetStartedAtMs = 0L,
            maxAttempts = ProbeBudget.MAX_ATTEMPTS_LOCKED,
            maxElapsedMs = ProbeBudget.MAX_ELAPSED_MS_LOCKED,
        )
        val cp = WsReconnectPermit.ClaimedProbe(
            stickyGen = 1,
            routeEpoch = 2L,
            token = token,
            ownerGeneration = 4L,
            budget = budget,
        )
        val s = cp.toString()
        assertTrue(s.contains("token=[REDACTED]"), "ClaimedProbe.toString must redact token; got: $s")
        assertTrue(!s.contains("0xABCD"), "raw token value must not appear in toString; got: $s")
        assertTrue(!s.contains("43981"), "raw token decimal value (0xABCD) must not appear in toString; got: $s")
    }

    // ── CandidateProving type-level absence of `token` field ─────────

    @Test
    fun candidate_proving_has_no_token_field_in_toString() {
        // The KDoc on CandidateProving says: "Type-level invariant:
        // CandidateProving HAS NO `token` field. Once the probe
        // converts to a connected session, the one-shot token is
        // consumed and erased."
        //
        // Data classes auto-derive `toString()` from their declared
        // properties. Asserting `token=` is absent from
        // `CandidateProving.toString()` pins the type-level
        // invariant: adding a `token` property would break this test
        // before any runtime path can leak a value.
        val cp = WsReconnectGate.CandidateProving(stickyGen = 1, sessionEpoch = 5L)
        val s = cp.toString()
        assertTrue(!s.contains("token"), "CandidateProving.toString must NOT carry any `token` substring; got: $s")
    }

    // ── simpleKind() coverage ────────────────────────────────────────

    @Test
    fun simple_kind_returns_label_per_state() {
        assertEquals("Open", WsReconnectGate.Open.simpleKind())
        assertEquals("Quiesced", WsReconnectGate.Quiesced(stickyGen = 1).simpleKind())

        val token = ProbeToken(0x1L)
        val budget = ProbeBudget(
            budgetStartedAtMs = 0L,
            maxAttempts = ProbeBudget.MAX_ATTEMPTS_LOCKED,
            maxElapsedMs = ProbeBudget.MAX_ELAPSED_MS_LOCKED,
        )
        assertEquals(
            "ProbeAvailable",
            WsReconnectGate.ProbeAvailable(
                stickyGen = 1,
                routeEpoch = 1L,
                token = token,
                budget = budget,
                generationFloor = 0L,
            ).simpleKind(),
        )
        assertEquals(
            "ProbeClaimed",
            WsReconnectGate.ProbeClaimed(
                stickyGen = 1,
                routeEpoch = 1L,
                token = token,
                ownerGeneration = 1L,
                budget = budget,
            ).simpleKind(),
        )
        assertEquals(
            "CandidateProving",
            WsReconnectGate.CandidateProving(stickyGen = 1, sessionEpoch = 1L).simpleKind(),
        )
    }

    @Test
    fun simple_kind_never_contains_raw_token_value() {
        val token = ProbeToken(0xCAFEL)
        val budget = ProbeBudget(
            budgetStartedAtMs = 0L,
            maxAttempts = ProbeBudget.MAX_ATTEMPTS_LOCKED,
            maxElapsedMs = ProbeBudget.MAX_ELAPSED_MS_LOCKED,
        )
        val pa: WsReconnectGate = WsReconnectGate.ProbeAvailable(
            stickyGen = 1,
            routeEpoch = 1L,
            token = token,
            budget = budget,
            generationFloor = 0L,
        )
        val pc: WsReconnectGate = WsReconnectGate.ProbeClaimed(
            stickyGen = 1,
            routeEpoch = 1L,
            token = token,
            ownerGeneration = 1L,
            budget = budget,
        )
        // simpleKind() is the telemetry-safe label; the raw token
        // value must not leak through it.
        assertTrue(!pa.simpleKind().contains("CAFE", ignoreCase = true))
        assertTrue(!pc.simpleKind().contains("CAFE", ignoreCase = true))
    }

    // ── ProbeBudget locked constants ─────────────────────────────────

    @Test
    fun probe_budget_max_attempts_locked_at_5() {
        assertEquals(5, ProbeBudget.MAX_ATTEMPTS_LOCKED)
    }

    @Test
    fun probe_budget_max_elapsed_ms_locked_at_120_000() {
        assertEquals(120_000L, ProbeBudget.MAX_ELAPSED_MS_LOCKED)
    }

    @Test
    fun probe_budget_is_data_class_with_equality_by_value() {
        val a = ProbeBudget(
            budgetStartedAtMs = 100L,
            maxAttempts = ProbeBudget.MAX_ATTEMPTS_LOCKED,
            maxElapsedMs = ProbeBudget.MAX_ELAPSED_MS_LOCKED,
        )
        val b = ProbeBudget(
            budgetStartedAtMs = 100L,
            maxAttempts = ProbeBudget.MAX_ATTEMPTS_LOCKED,
            maxElapsedMs = ProbeBudget.MAX_ELAPSED_MS_LOCKED,
        )
        val c = ProbeBudget(
            budgetStartedAtMs = 200L,
            maxAttempts = ProbeBudget.MAX_ATTEMPTS_LOCKED,
            maxElapsedMs = ProbeBudget.MAX_ELAPSED_MS_LOCKED,
        )
        assertEquals(a, b)
        assertNotSame(a, b)
        assertNotEquals(a, c)
    }

    // ── WsReconnectPermit sealed-hierarchy distinctness ──────────────

    @Test
    fun ws_reconnect_permit_three_subtypes_distinct() {
        val token = ProbeToken(0x1L)
        val budget = ProbeBudget(
            budgetStartedAtMs = 0L,
            maxAttempts = ProbeBudget.MAX_ATTEMPTS_LOCKED,
            maxElapsedMs = ProbeBudget.MAX_ELAPSED_MS_LOCKED,
        )
        val permits: List<WsReconnectPermit> = listOf(
            WsReconnectPermit.OpenPermit(routeEpoch = 1L, ownerGeneration = 1L),
            WsReconnectPermit.ClaimedProbe(
                stickyGen = 1,
                routeEpoch = 1L,
                token = token,
                ownerGeneration = 1L,
                budget = budget,
            ),
            WsReconnectPermit.LoopRetired(reason = "owner_generation_superseded"),
        )
        assertEquals(3, permits.map { it::class }.toSet().size)
    }

    @Test
    fun ws_reconnect_permit_exhaustive_when_pins_member_count() {
        val token = ProbeToken(0x1L)
        val budget = ProbeBudget(
            budgetStartedAtMs = 0L,
            maxAttempts = ProbeBudget.MAX_ATTEMPTS_LOCKED,
            maxElapsedMs = ProbeBudget.MAX_ELAPSED_MS_LOCKED,
        )
        val p: WsReconnectPermit = WsReconnectPermit.ClaimedProbe(
            stickyGen = 1,
            routeEpoch = 1L,
            token = token,
            ownerGeneration = 1L,
            budget = budget,
        )
        val arms: Int = when (p) {
            is WsReconnectPermit.OpenPermit -> 1
            is WsReconnectPermit.ClaimedProbe -> 2
            is WsReconnectPermit.LoopRetired -> 3
        }
        assertEquals(2, arms)
    }

    // ── ClaimResult sealed-hierarchy distinctness ────────────────────

    @Test
    fun claim_result_two_subtypes_distinct() {
        val token = ProbeToken(0x1L)
        val budget = ProbeBudget(
            budgetStartedAtMs = 0L,
            maxAttempts = ProbeBudget.MAX_ATTEMPTS_LOCKED,
            maxElapsedMs = ProbeBudget.MAX_ELAPSED_MS_LOCKED,
        )
        val results: List<ClaimResult> = listOf(
            ClaimResult.Claimed(
                probe = WsReconnectPermit.ClaimedProbe(
                    stickyGen = 1,
                    routeEpoch = 1L,
                    token = token,
                    ownerGeneration = 1L,
                    budget = budget,
                ),
            ),
            ClaimResult.Failure(reason = ClaimFailureReason.GATE_NOT_PROBE_AVAILABLE),
        )
        assertEquals(2, results.map { it::class }.toSet().size)
    }

    @Test
    fun claim_result_exhaustive_when_pins_member_count() {
        val r: ClaimResult = ClaimResult.Failure(reason = ClaimFailureReason.GATE_NOT_PROBE_AVAILABLE)
        val arms: Int = when (r) {
            is ClaimResult.Claimed -> 1
            is ClaimResult.Failure -> 2
        }
        assertEquals(2, arms)
    }

    // ── ProbeIssueResult sealed-hierarchy distinctness ───────────────

    @Test
    fun probe_issue_result_two_subtypes_distinct() {
        val token = ProbeToken(0x1L)
        val results: List<ProbeIssueResult> = listOf(
            ProbeIssueResult.ProbeIssued(token = token),
            ProbeIssueResult.Rejected(reason = ProbeIssueRejectReason.GATE_NOT_QUIESCED),
        )
        assertEquals(2, results.map { it::class }.toSet().size)
    }

    @Test
    fun probe_issue_result_issued_to_string_redacts_token() {
        val token = ProbeToken(0xABCDL)
        val issued = ProbeIssueResult.ProbeIssued(token = token)
        val s = issued.toString()
        assertTrue(s.contains("[REDACTED]"), "ProbeIssued.toString must redact token; got: $s")
        assertTrue(!s.contains("0xABCD"), "raw hex token must not appear; got: $s")
        assertTrue(!s.contains("43981"), "raw decimal token must not appear; got: $s")
    }

    @Test
    fun probe_issue_result_exhaustive_when_pins_member_count() {
        val r: ProbeIssueResult = ProbeIssueResult.Rejected(reason = ProbeIssueRejectReason.GATE_NOT_QUIESCED)
        val arms: Int = when (r) {
            is ProbeIssueResult.ProbeIssued -> 1
            is ProbeIssueResult.Rejected -> 2
        }
        assertEquals(2, arms)
    }

    // ── RouteChangeOutcome sealed-hierarchy distinctness ─────────────

    @Test
    fun route_change_outcome_three_subtypes_distinct() {
        val outcomes: List<RouteChangeOutcome> = listOf(
            RouteChangeOutcome.OpenReconnect(routeEpoch = 1L),
            RouteChangeOutcome.StickyRecovery(routeEpoch = 2L),
            RouteChangeOutcome.QuiescencePreserved(routeEpoch = 3L),
        )
        assertEquals(3, outcomes.map { it::class }.toSet().size)
        // routeEpoch accessor exists on every subtype (sealed interface contract).
        assertEquals(listOf(1L, 2L, 3L), outcomes.map { it.routeEpoch })
    }

    @Test
    fun route_change_outcome_exhaustive_when_pins_member_count() {
        val o: RouteChangeOutcome = RouteChangeOutcome.StickyRecovery(routeEpoch = 2L)
        val arms: Int = when (o) {
            is RouteChangeOutcome.OpenReconnect -> 1
            is RouteChangeOutcome.StickyRecovery -> 2
            is RouteChangeOutcome.QuiescencePreserved -> 3
        }
        assertEquals(2, arms)
    }

    // ── ClaimFailureReason enum exhaustiveness ───────────────────────

    @Test
    fun claim_failure_reason_has_four_locked_values() {
        // Order-sensitive on purpose — a re-order is a contract change
        // that surfaces here too.
        val values = ClaimFailureReason.values().map { it.name }
        assertEquals(
            listOf(
                "GATE_NOT_PROBE_AVAILABLE",
                "ROUTE_EPOCH_STALE",
                "PROBE_ALREADY_CLAIMED",
                "OWNER_GENERATION_STALE",
            ),
            values,
        )
    }

    @Test
    fun claim_failure_reason_exhaustive_when_pins_member_count() {
        val r: ClaimFailureReason = ClaimFailureReason.GATE_NOT_PROBE_AVAILABLE
        val arms: Int = when (r) {
            ClaimFailureReason.GATE_NOT_PROBE_AVAILABLE -> 1
            ClaimFailureReason.ROUTE_EPOCH_STALE -> 2
            ClaimFailureReason.PROBE_ALREADY_CLAIMED -> 3
            ClaimFailureReason.OWNER_GENERATION_STALE -> 4
        }
        assertEquals(1, arms)
    }

    // ── ProbeIssueRejectReason enum exhaustiveness ───────────────────

    @Test
    fun probe_issue_reject_reason_has_three_locked_values() {
        val values = ProbeIssueRejectReason.values().map { it.name }
        assertEquals(
            listOf(
                "GATE_NOT_QUIESCED",
                "ROUTE_EPOCH_STALE",
                "REVOKED_BY_CONCURRENT_PATH",
            ),
            values,
        )
    }

    @Test
    fun probe_issue_reject_reason_exhaustive_when_pins_member_count() {
        val r: ProbeIssueRejectReason = ProbeIssueRejectReason.GATE_NOT_QUIESCED
        val arms: Int = when (r) {
            ProbeIssueRejectReason.GATE_NOT_QUIESCED -> 1
            ProbeIssueRejectReason.ROUTE_EPOCH_STALE -> 2
            ProbeIssueRejectReason.REVOKED_BY_CONCURRENT_PATH -> 3
        }
        assertEquals(1, arms)
    }
}
