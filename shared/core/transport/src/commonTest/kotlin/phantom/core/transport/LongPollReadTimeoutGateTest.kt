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
 * | `"1"` | `1` | raised: `(1 + 5) * 1000 = 6_000` |
 * | `"1"` | `30` | raised: `(30 + 5) * 1000 = 35_000` |
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
    fun flag_on_with_hold_at_lower_bound_raises_timeout() {
        val raised = RestFallbackOrchestrator.computeLongPollReadTimeoutMs(
            longPollEnabled = true,
            pollHoldSecs = RestFallbackOrchestrator.MIN_POLL_HOLD_SECS,
        )
        assertNotNull(raised, "Expected raised timeout at MIN_POLL_HOLD_SECS.")
        assertEquals(
            (RestFallbackOrchestrator.MIN_POLL_HOLD_SECS + margin) * 1000L,
            raised,
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
}
