// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [RcDirectArmG] companion-level invariants locked in
 * `docs/tracks/rc-direct-stability1.md` §14 Arm G mini-lock (PR #294
 * squash `f0b436a5` master 2026-06-05).
 *
 * Test coverage strategy (per implementation default #6 ACKed
 * 2026-06-05):
 *
 *   * **T1-T5 — companion-constant invariants.** Pure assertions against
 *     the constants the wire-up sites depend on. Catches an accidental
 *     drift in any of the locked numbers (heartbeat interval, send-time
 *     map cap, echo-prefix string, max-payload-len, xray-ready timeout)
 *     before it lands.
 *   * **T6 — `parseHeartbeatEcho` accepts valid + rejects 5 invalid
 *     shapes.** Catches an accidental drift in the echo-classification
 *     rules (which would inflate `echo_received` counters with foreign
 *     Text frames per the `{"type":"pong"}` regression class) before it
 *     lands. The parser lives in the companion so this test does not
 *     need to construct an `RcDirectArmG` instance (which would require
 *     mocking `IdentityManager` + `XrayService` + a `CoroutineScope`).
 *
 * Deferred per implementation default #6 (covered empirically by PR-G3
 * field test):
 *
 *   * `xray_not_ready` 15-second timeout fail-fast path (requires
 *     `XrayService` test double + coroutine timing harness)
 *   * `stop()` idempotence (requires class instance + scope)
 *   * Wire-up gates `BuildConfig.DEBUG && DEBUG_RC_DIRECT_ARM_G_VIA_REALITY
 *     == "1"` (immutable at build time; tested via the release-block
 *     pin + the runtime gate code-read in PR review, not via unit test)
 *
 * Tests run on the JVM (androidUnitTest source set). No Android runtime
 * is required.
 */
class RcDirectArmGTest {

    // ── T1 — HEARTBEAT_ECHO_PREFIX is byte-for-byte canonical ─────────────

    @Test
    fun heartbeatEchoPrefix_is_canonical() {
        // Locked in mini-lock §4 Arm D and reused byte-for-byte across
        // Arm D / Arm A.2 / Arm G. Must match relay-side
        // `HEARTBEAT_ECHO_PREFIX` constant
        // (services/relay/src/routes.rs PR #279) so the relay's
        // `parse_heartbeat_echo_payload` classifies Arm G's Text frames
        // as heartbeats.
        assertEquals(
            "phantom:diagnostic:heartbeat-echo:v1:",
            RcDirectArmG.HEARTBEAT_ECHO_PREFIX,
        )
    }

    // ── T2 — HEARTBEAT_ECHO_MAX_LEN matches relay-side cap ────────────────

    @Test
    fun heartbeatEchoMaxLen_matches_relay_cap() {
        // Mirrors relay-side `HEARTBEAT_ECHO_MAX_LEN` (256). The cap
        // protects the parser from a runaway-allocation class of attack
        // on a corrupted frame; the relay-side cap is normative because
        // Arm G is the client and the relay is the source of truth for
        // the echo handler.
        assertEquals(256, RcDirectArmG.HEARTBEAT_ECHO_MAX_LEN)
    }

    // ── T3 — HEARTBEAT_INTERVAL_MS == production pingInterval ─────────────

    @Test
    fun heartbeatIntervalMs_matches_production_ping_interval() {
        // Same value as production `pingInterval(15s)` per implementation
        // default #2 (ACK 2026-06-05). The heartbeat anchor must line up
        // with the OkHttp WS Ping/Pong anchor on the same session so the
        // W/X/Y discriminator can read both frame classes at the relay
        // app layer against the Arm A.2 / Arm D baseline.
        assertEquals(15_000L, RcDirectArmG.HEARTBEAT_INTERVAL_MS)
    }

    // ── T4 — SEND_TIME_MAP_CAPACITY matches Arm A.2 / Arm D baseline ──────

    @Test
    fun sendTimeMapCapacity_matches_baseline() {
        // Heartbeat at 15 s cadence over a 15 min capture yields ~60
        // sends total; 32-entry cap covers ~8 min of in-flight
        // outstanding echoes. Same value as Arm A.2 / Arm D so RTT
        // statistics are comparable across the three arms.
        assertEquals(32, RcDirectArmG.SEND_TIME_MAP_CAPACITY)
    }

    // ── T5 — XRAY_READY_TIMEOUT_MS == locked 15 s ─────────────────────────

    @Test
    fun xrayReadyTimeoutMs_matches_locked_value() {
        // Per §14 hard gate 9 + implementation default #4 ACKed
        // 2026-06-05: `withTimeout(15_000L) { state.first { it is Ready } }`.
        // Stage 5E observed time-to-Ready ~2-3 s on Tecno Tele2 LTE;
        // 15 s gives a 5× margin. A drift in this value would either
        // make Arm G fail-fast too quickly on a slow libXray cold start
        // (false negative) or hang too long before terminating with
        // `outcome=xray_not_ready` (operator wait time).
        assertEquals(15_000L, RcDirectArmG.XRAY_READY_TIMEOUT_MS)
    }

    // ── T6 — parseHeartbeatEcho accepts valid + rejects 5 invalid shapes ──

    @Test
    fun parseHeartbeatEcho_accepts_canonical_payload() {
        // Canonical shape: prefix + seq + ":" + clientMs, both fields
        // parse as Long.
        val raw = "${RcDirectArmG.HEARTBEAT_ECHO_PREFIX}42:1700000000000"
        val parsed = RcDirectArmG.parseHeartbeatEcho(raw)
        assertNotNull(parsed, "canonical payload must parse")
        assertEquals(42L, parsed.first, "seq must round-trip")
        assertEquals(1700000000000L, parsed.second, "clientMs must round-trip")
    }

    @Test
    fun parseHeartbeatEcho_round_trips_constructed_payload() {
        // The Arm G sender constructs payloads via
        // `"$HEARTBEAT_ECHO_PREFIX$seq:$clientMs"`. The parser must
        // recover both fields exactly — this round-trip is the contract
        // the echo statistics rely on for RTT computation.
        val seq = 99L
        val clientMs = System.currentTimeMillis()
        val constructed = "${RcDirectArmG.HEARTBEAT_ECHO_PREFIX}$seq:$clientMs"
        val parsed = RcDirectArmG.parseHeartbeatEcho(constructed)
        assertNotNull(parsed, "constructed payload must round-trip")
        assertEquals(seq, parsed.first)
        assertEquals(clientMs, parsed.second)
    }

    @Test
    fun parseHeartbeatEcho_rejects_payload_above_max_len() {
        // Reject 1: payload over HEARTBEAT_ECHO_MAX_LEN (defence against
        // a corrupted frame allocating an unbounded buffer).
        val oversized = "${RcDirectArmG.HEARTBEAT_ECHO_PREFIX}1:" +
            "0".repeat(RcDirectArmG.HEARTBEAT_ECHO_MAX_LEN)
        assertNull(RcDirectArmG.parseHeartbeatEcho(oversized))
    }

    @Test
    fun parseHeartbeatEcho_rejects_wrong_prefix() {
        // Reject 2: foreign Text frame not starting with the canonical
        // prefix. Catches `{"type":"pong"}` and envelope JSON.
        assertNull(RcDirectArmG.parseHeartbeatEcho("{\"type\":\"pong\"}"))
        // Reject 3: prefix-as-substring (NOT prefix-as-start) — catches
        // a hypothetical `prefix-in-middle` regression where the parser
        // would scan rather than anchor at the start.
        assertNull(
            RcDirectArmG.parseHeartbeatEcho(
                "leading-garbage${RcDirectArmG.HEARTBEAT_ECHO_PREFIX}1:1",
            ),
        )
    }

    @Test
    fun parseHeartbeatEcho_rejects_wrong_field_count() {
        // Reject 4: too few `:`-separated fields after the prefix.
        val tooFew = "${RcDirectArmG.HEARTBEAT_ECHO_PREFIX}42"
        assertNull(RcDirectArmG.parseHeartbeatEcho(tooFew))
        // Reject 5: too many `:`-separated fields after the prefix
        // (catches an accidental extra-colon regression in the sender).
        val tooMany = "${RcDirectArmG.HEARTBEAT_ECHO_PREFIX}1:2:3"
        assertNull(RcDirectArmG.parseHeartbeatEcho(tooMany))
    }

    @Test
    fun parseHeartbeatEcho_rejects_non_long_fields() {
        // Reject 6: one or both fields not a `Long`. Catches a sender
        // regression that would emit `seq=` empty or `clientMs=NaN`.
        val seqNotLong = "${RcDirectArmG.HEARTBEAT_ECHO_PREFIX}abc:1700000000000"
        assertNull(RcDirectArmG.parseHeartbeatEcho(seqNotLong))
        val clientMsNotLong = "${RcDirectArmG.HEARTBEAT_ECHO_PREFIX}42:not-a-number"
        assertNull(RcDirectArmG.parseHeartbeatEcho(clientMsNotLong))
        val bothEmpty = "${RcDirectArmG.HEARTBEAT_ECHO_PREFIX}:"
        assertNull(RcDirectArmG.parseHeartbeatEcho(bothEmpty))
    }

    // ── Sanity: prefix + canonical payload always re-parses ───────────────

    @Test
    fun heartbeatEchoPrefix_constant_round_trip_through_parser() {
        // Anchor test: regardless of how the prefix is constructed at
        // the sender, the constant + valid integer suffix must always
        // re-parse cleanly. Acts as a guard against an accidental
        // trailing-whitespace or unicode-escape regression in the
        // companion constant.
        val payload = "${RcDirectArmG.HEARTBEAT_ECHO_PREFIX}1:2"
        val parsed = RcDirectArmG.parseHeartbeatEcho(payload)
        assertNotNull(parsed)
        assertEquals(1L, parsed.first)
        assertEquals(2L, parsed.second)
    }
}
