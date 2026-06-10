// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Trek 2 Stage 2B-A (B2) — M2 contract test for the read / call
 * timeout gate.
 *
 * Pins the 9-cell behaviour matrix from
 * [docs/tracks/trek2-stage2b-a-client-shell.md] § Test matrix M2 on
 * the pure helper [RestFallbackOrchestrator.computeLongPollReadTimeoutMs].
 * The helper is deliberately a companion-object function with no I/O so
 * the matrix can be driven without standing up an orchestrator, a
 * transport, or a state machine.
 *
 * The 9 cells are the cross product of
 * `LONGPOLL_V2_ENABLED ∈ {"0", "1"}` and
 * `pollHoldSecs ∈ {null/unset, 0, 1, 30, 480, 481}`. The `null/unset`
 * case is modelled as `pollHoldSecs == 0` because the wire default in
 * [AuthSessionResponse.pollHoldSecs] is `0` and there is no other
 * "unset" sentinel — `0` carries the same semantic ("server kill
 * switch active" OR "old relay does not announce the field"). The
 * matrix therefore has 8 distinct cells; the table below documents
 * the original 9 explicitly so the absence of a separate `null` row
 * is auditable.
 *
 * | `LONGPOLL_V2_ENABLED` | `pollHoldSecs` | Expected read-timeout |
 * |:---:|:---:|:---|
 * | `"0"` | `0` | legacy (null) |
 * | `"0"` | `30` | legacy (null) |
 * | `"0"` | `null` / unset | legacy (null) — wire `0` |
 * | `"1"` | `0` | legacy (null) (server kill switch) |
 * | `"1"` | `1` | `LEGACY_SHORT_POLL_TIMEOUT_MS` (floor — `(1+5)*1000 = 6_000` below floor) |
 * | `"1"` | `30` | raised: `(30 + 5) * 1000 = 35_000` (above floor) |
 * | `"1"` | `480` | raised: `(480 + 5) * 1000 = 485_000` |
 * | `"1"` | `481` | legacy (null) (out of spec) |
 * | `"1"` | `null` / unset | legacy (null) — wire `0` |
 *
 * Safety margin `5` is pinned at the constant declaration; if scope is
 * re-locked to a different value inside `[2, 8]`, this test's
 * expected values must update with it. The test deliberately reads
 * [RestFallbackOrchestrator.POLL_HOLD_SAFETY_MARGIN_SECS] so a
 * harmonised re-lock at a new value passes here automatically while
 * still pinning the formula shape.
 *
 * The floor `LEGACY_SHORT_POLL_TIMEOUT_MS = 10_000` ms is load-bearing:
 * the raw formula `(hold + margin) * 1000` produces values BELOW the
 * legacy short-poll budget for tiny `pollHoldSecs ∈ [1, 4]` (e.g.
 * `(1+5)*1000 = 6_000 ms < 10_000 ms`). Override semantics must be
 * strictly monotonic — enabling long-poll can only LIFT timeouts,
 * never shorten them — so the helper clamps the candidate UP to the
 * floor via `maxOf`. The floor-clamp case is exercised explicitly by
 * [flag_on_with_tiny_hold_is_floored_at_legacy_short_poll_timeout].
 */
class LongPollReadTimeoutGateTest {

    private val margin: Int = RestFallbackOrchestrator.POLL_HOLD_SAFETY_MARGIN_SECS

    // ── Flag OFF — every case must return null ────────────────────────────────

    @Test
    fun flag_off_with_hold_zero_returns_null() {
        assertNull(
            RestFallbackOrchestrator.computeLongPollReadTimeoutMs(
                longPollEnabled = false,
                pollHoldSecs = 0,
            ),
        )
    }

    @Test
    fun flag_off_with_hold_thirty_returns_null() {
        assertNull(
            RestFallbackOrchestrator.computeLongPollReadTimeoutMs(
                longPollEnabled = false,
                pollHoldSecs = 30,
            ),
        )
    }

    @Test
    fun flag_off_with_hold_at_max_returns_null() {
        assertNull(
            RestFallbackOrchestrator.computeLongPollReadTimeoutMs(
                longPollEnabled = false,
                pollHoldSecs = RestFallbackOrchestrator.MAX_POLL_HOLD_SECS_CAP,
            ),
        )
    }

    // ── Flag ON — gate depends on pollHoldSecs ────────────────────────────────

    @Test
    fun flag_on_with_hold_zero_returns_null_server_kill_switch() {
        // pollHoldSecs == 0 means the server kill switch is active OR the
        // relay is too old to announce the field. Either way the client
        // MUST short-poll, regardless of the flag.
        assertNull(
            RestFallbackOrchestrator.computeLongPollReadTimeoutMs(
                longPollEnabled = true,
                pollHoldSecs = 0,
            ),
        )
    }

    @Test
    fun flag_on_with_hold_at_lower_bound_is_floored_at_legacy_timeout() {
        // At MIN_POLL_HOLD_SECS = 1, the raw formula is
        // `(1 + 5) * 1000 = 6_000` ms, BELOW the legacy 10_000 ms floor.
        // The floor wins per monotonicity: long-poll must not be less
        // patient than legacy short-poll.
        val raised = RestFallbackOrchestrator.computeLongPollReadTimeoutMs(
            longPollEnabled = true,
            pollHoldSecs = RestFallbackOrchestrator.MIN_POLL_HOLD_SECS,
        )
        assertNotNull(raised, "Expected non-null override at MIN_POLL_HOLD_SECS.")
        assertEquals(
            RestFallbackOrchestrator.LEGACY_SHORT_POLL_TIMEOUT_MS,
            raised,
            "At MIN_POLL_HOLD_SECS the raw formula is below the legacy floor; the " +
                "result must be clamped to LEGACY_SHORT_POLL_TIMEOUT_MS.",
        )
    }

    @Test
    fun flag_on_with_tiny_hold_is_floored_at_legacy_short_poll_timeout() {
        // Parameterised-style check across `pollHoldSecs ∈ [1, 4]` where
        // the raw formula returns 6_000..9_000 ms — all below the
        // 10_000 ms legacy floor. Each must clamp to the floor.
        for (hold in 1..4) {
            val raised = RestFallbackOrchestrator.computeLongPollReadTimeoutMs(
                longPollEnabled = true,
                pollHoldSecs = hold,
            )
            val rawCandidate = (hold + margin) * 1000L
            assertTrue(
                rawCandidate < RestFallbackOrchestrator.LEGACY_SHORT_POLL_TIMEOUT_MS,
                "Sanity: hold=$hold should produce raw < floor.",
            )
            assertEquals(
                RestFallbackOrchestrator.LEGACY_SHORT_POLL_TIMEOUT_MS,
                raised,
                "hold=$hold: raw $rawCandidate ms is below floor; expected " +
                    "clamp to LEGACY_SHORT_POLL_TIMEOUT_MS.",
            )
        }
    }

    @Test
    fun flag_on_with_hold_at_floor_boundary_uses_formula_not_clamp() {
        // At `pollHoldSecs = 5`, raw formula is `(5 + 5) * 1000 = 10_000`
        // ms — exactly equal to the legacy floor. `maxOf` returns
        // the floor unchanged. Pinning this case structurally so a
        // future refactor that flips `maxOf` to a strict comparator
        // does not silently produce 5_000 ms at the boundary.
        val raised = RestFallbackOrchestrator.computeLongPollReadTimeoutMs(
            longPollEnabled = true,
            pollHoldSecs = 5,
        )
        assertEquals(RestFallbackOrchestrator.LEGACY_SHORT_POLL_TIMEOUT_MS, raised)
        assertEquals(10_000L, raised)
    }

    @Test
    fun flag_on_with_hold_just_above_floor_boundary_uses_formula() {
        // At `pollHoldSecs = 6`, raw formula = 11_000 ms, ABOVE floor.
        // Formula wins — first cell where the raised value is strictly
        // above the legacy floor.
        val raised = RestFallbackOrchestrator.computeLongPollReadTimeoutMs(
            longPollEnabled = true,
            pollHoldSecs = 6,
        )
        assertEquals((6 + margin) * 1000L, raised)
        assertTrue(
            raised!! > RestFallbackOrchestrator.LEGACY_SHORT_POLL_TIMEOUT_MS,
            "hold=6 must produce a value strictly above legacy floor.",
        )
    }

    @Test
    fun flag_on_with_hold_in_typical_range_raises_timeout() {
        val raised = RestFallbackOrchestrator.computeLongPollReadTimeoutMs(
            longPollEnabled = true,
            pollHoldSecs = 30,
        )
        assertEquals((30 + margin) * 1000L, raised)
    }

    @Test
    fun flag_on_with_hold_at_upper_bound_raises_timeout() {
        val raised = RestFallbackOrchestrator.computeLongPollReadTimeoutMs(
            longPollEnabled = true,
            pollHoldSecs = RestFallbackOrchestrator.MAX_POLL_HOLD_SECS_CAP,
        )
        assertEquals(
            (RestFallbackOrchestrator.MAX_POLL_HOLD_SECS_CAP + margin) * 1000L,
            raised,
        )
    }

    @Test
    fun flag_on_with_hold_one_above_cap_returns_null_out_of_spec() {
        // 481 is just above the server's MAX_POLL_HOLD_SECS_CAP. A relay
        // advertising this is out of spec; the client falls back to the
        // legacy short-poll budget rather than honouring an unbounded
        // value.
        assertNull(
            RestFallbackOrchestrator.computeLongPollReadTimeoutMs(
                longPollEnabled = true,
                pollHoldSecs = RestFallbackOrchestrator.MAX_POLL_HOLD_SECS_CAP + 1,
            ),
        )
    }

    // ── Bounds + invariant guards ─────────────────────────────────────────────

    @Test
    fun negative_hold_returns_null_safety_net() {
        // A relay should never announce a negative value, but if it does
        // (corrupted JSON / hostile middlebox / future test), the client
        // must short-poll and not pass a negative timeout into OkHttp.
        assertNull(
            RestFallbackOrchestrator.computeLongPollReadTimeoutMs(
                longPollEnabled = true,
                pollHoldSecs = -1,
            ),
        )
        assertNull(
            RestFallbackOrchestrator.computeLongPollReadTimeoutMs(
                longPollEnabled = true,
                pollHoldSecs = Int.MIN_VALUE,
            ),
        )
    }

    @Test
    fun safety_margin_is_inside_locked_two_to_eight_seconds_band() {
        // Lock L2 pins the safety margin inside `[2, 8]` seconds.
        // Outside that band requires a re-lock. The constant lives in
        // RestFallbackOrchestrator; pin its value here so a future
        // refactor that bumps it past the locked band fails CI before
        // it can reach a release APK.
        assertTrue(
            RestFallbackOrchestrator.POLL_HOLD_SAFETY_MARGIN_SECS in 2..8,
            "POLL_HOLD_SAFETY_MARGIN_SECS must stay in [2, 8] " +
                "(scope L2). Found: ${RestFallbackOrchestrator.POLL_HOLD_SAFETY_MARGIN_SECS}.",
        )
    }

    @Test
    fun bounds_constants_match_server_contract() {
        // Server-side MAX_POLL_HOLD_SECS_CAP in
        // `services/relay/src/rest_fallback.rs` is 480. MIN is 1 (0
        // means server kill switch active). Pin both here so the
        // client's gate stays in lock-step with the server's contract.
        assertEquals(1, RestFallbackOrchestrator.MIN_POLL_HOLD_SECS)
        assertEquals(480, RestFallbackOrchestrator.MAX_POLL_HOLD_SECS_CAP)
    }

    @Test
    fun legacy_floor_matches_android_transport_default() {
        // Single source of truth invariant: the legacy floor used by
        // the gate MUST equal the OkHttp `READ_TIMEOUT_MS` /
        // `CALL_TIMEOUT_MS` defaults in
        // `AndroidNativeOkHttpRestFallbackTransport`. If a future
        // refactor changes the Android default without also changing
        // the floor (or vice versa), the monotonicity invariant
        // `override_ms >= legacy_default_ms` would silently break.
        //
        // The two constants are now wired with the Android defaults
        // referencing `LEGACY_SHORT_POLL_TIMEOUT_MS` directly, so this
        // assert is a structural pin against a future "decoupling"
        // refactor that re-introduces drift.
        assertEquals(10_000L, RestFallbackOrchestrator.LEGACY_SHORT_POLL_TIMEOUT_MS)
    }
}
