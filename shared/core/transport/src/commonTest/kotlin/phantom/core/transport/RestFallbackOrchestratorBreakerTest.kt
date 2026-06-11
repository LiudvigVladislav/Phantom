// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C5) — behavioural tests for the L8 410 reauth
 * dance + L9 circuit breaker + D11 hard timer caps + typed
 * `Retry-After` plumbing.
 *
 * Ships scope-doc cells:
 *
 *   M-B18  — Numeric contract pins for the eight breaker constants
 *            + `RETRY_AFTER_HARD_CAP_SECONDS`. A future tuning of
 *            any single constant surfaces here.
 *   M-B24  — HTTP 429 `Retry-After` hard cap, four sub-cells:
 *            (a) typed `retryAfterSeconds: Long? = null` populated
 *                from the `Response.header("Retry-After")` parse path;
 *            (b) malformed `Retry-After` (non-numeric, negative,
 *                zero, HTTP-date form, empty) → treated as `null`;
 *            (c) overflow-safe clamp — `Retry-After: 86400` →
 *                effective delay 120_000 ms; `Retry-After: Long.MAX_VALUE`
 *                → effective delay 120_000 ms (no overflow);
 *            (d) the clamp applies in BOTH REST poll loops AND in
 *                the send-path retry that consumes the header.
 *
 * M13 (breaker state transitions), M14 (410 reauth dance), and
 * M-B28 (half-open probe singleton with cancellation safety) ship
 * in C5-B / C5-C and live in this file or its split sibling.
 */
class RestFallbackOrchestratorBreakerTest {

    // ── M-B18 — numeric contract pins ───────────────────────────────────────

    @Test
    fun mb18_breaker_constants_match_scope_locked_values() {
        // Pin each constant to its scope-locked value verbatim. A
        // future tuning that drifts any value without updating this
        // pin (and the scope-doc) surfaces here, not silently in
        // production.
        assertEquals(
            5,
            RestFallbackOrchestrator.BREAKER_CONSECUTIVE_FAIL_THRESHOLD,
            "BREAKER_CONSECUTIVE_FAIL_THRESHOLD must equal scope-locked N = 5",
        )
        assertEquals(
            5_000L,
            RestFallbackOrchestrator.BREAKER_INITIAL_COOLDOWN_MS,
            "BREAKER_INITIAL_COOLDOWN_MS must equal scope-locked 5_000",
        )
        assertEquals(
            2.0,
            RestFallbackOrchestrator.BREAKER_COOLDOWN_GROWTH_FACTOR,
            "BREAKER_COOLDOWN_GROWTH_FACTOR must equal scope-locked 2.0",
        )
        assertEquals(
            120_000L,
            RestFallbackOrchestrator.BREAKER_COOLDOWN_CEILING_MS,
            "BREAKER_COOLDOWN_CEILING_MS must equal scope-locked 120_000 (D11 hard cap)",
        )
        assertEquals(
            3,
            RestFallbackOrchestrator.BREAKER_410_STORM_THRESHOLD,
            "BREAKER_410_STORM_THRESHOLD must equal scope-locked K = 3",
        )
        assertEquals(
            30_000L,
            RestFallbackOrchestrator.BREAKER_410_STORM_WINDOW_MS,
            "BREAKER_410_STORM_WINDOW_MS must equal scope-locked W = 30_000",
        )
        assertEquals(
            60_000L,
            RestFallbackOrchestrator.BREAKER_410_STORM_COOLDOWN_MS,
            "BREAKER_410_STORM_COOLDOWN_MS must equal scope-locked 60_000",
        )
        assertEquals(
            1,
            RestFallbackOrchestrator.BREAKER_HALFOPEN_PROBE_BUDGET,
            "BREAKER_HALFOPEN_PROBE_BUDGET must equal scope-locked 1 — exactly one probe per HalfOpen entry",
        )
        assertEquals(
            120L,
            RestFallbackOrchestrator.RETRY_AFTER_HARD_CAP_SECONDS,
            "RETRY_AFTER_HARD_CAP_SECONDS must equal scope-locked 120 (D11 hard cap, M-B24 overflow safety)",
        )
    }

    // ── M-B24 sub-cell (a) — typed retryAfterSeconds populated from header ──

    @Test
    fun mb24a_parseRetryAfterHeader_populates_typed_field_for_numeric_values() {
        // The transport-side parse helper normalises every wire
        // value to either a non-negative Long or null. The
        // OkHttp transport's `execute()` block calls this exact
        // function on `response.header("Retry-After")`, so a
        // numeric value flows verbatim into
        // `RestFallbackResponse.retryAfterSeconds`.
        assertEquals(1L, RestFallbackOrchestrator.parseRetryAfterHeader("1"))
        assertEquals(30L, RestFallbackOrchestrator.parseRetryAfterHeader("30"))
        assertEquals(120L, RestFallbackOrchestrator.parseRetryAfterHeader("120"))
        assertEquals(86_400L, RestFallbackOrchestrator.parseRetryAfterHeader("86400"))
        // Whitespace tolerated — header values often come with
        // surrounding whitespace.
        assertEquals(30L, RestFallbackOrchestrator.parseRetryAfterHeader("  30  "))
        assertEquals(30L, RestFallbackOrchestrator.parseRetryAfterHeader("\t30\t"))
        // Long.MAX_VALUE survives parsing (clamp lands downstream).
        assertEquals(
            Long.MAX_VALUE,
            RestFallbackOrchestrator.parseRetryAfterHeader(Long.MAX_VALUE.toString()),
        )
    }

    @Test
    fun mb24a_RestFallbackResponse_carries_retryAfterSeconds_field() {
        // The Stage 2B-B addition: the wire response carries a typed
        // optional Long. Pin the default (null) AND the populated
        // case so a future field-rename or default-flip surfaces.
        val withoutHeader = RestFallbackResponse<Unit>(
            statusCode = 200, bodyParsed = Unit, rawBody = "{}", elapsedMs = 1L,
        )
        assertNull(
            withoutHeader.retryAfterSeconds,
            "default value must be null so existing Stage 2B-A fakes remain wire-compatible",
        )
        val withHeader = RestFallbackResponse<Unit>(
            statusCode = 429, bodyParsed = null, rawBody = "rate-limited", elapsedMs = 1L,
            retryAfterSeconds = 30L,
        )
        assertEquals(30L, withHeader.retryAfterSeconds)
    }

    // ── M-B24 sub-cell (b) — malformed input → null ─────────────────────────

    @Test
    fun mb24b_parseRetryAfterHeader_normalises_malformed_input_to_null() {
        // Per scope §L8 the parser treats every non-numeric form as
        // null so the orchestrator can fall back to its own backoff.
        // Specifically: empty / whitespace / null / negative / zero /
        // HTTP-date form / alphabetic / mixed.
        assertNull(
            RestFallbackOrchestrator.parseRetryAfterHeader(null),
            "null header → null",
        )
        assertNull(
            RestFallbackOrchestrator.parseRetryAfterHeader(""),
            "empty header → null",
        )
        assertNull(
            RestFallbackOrchestrator.parseRetryAfterHeader("   "),
            "whitespace-only header → null",
        )
        assertNull(
            RestFallbackOrchestrator.parseRetryAfterHeader("0"),
            "zero → null (caller falls back rather than poll relay immediately)",
        )
        assertNull(
            RestFallbackOrchestrator.parseRetryAfterHeader("-1"),
            "negative → null",
        )
        assertNull(
            RestFallbackOrchestrator.parseRetryAfterHeader("-86400"),
            "large-negative → null",
        )
        assertNull(
            RestFallbackOrchestrator.parseRetryAfterHeader("abc"),
            "alphabetic → null",
        )
        assertNull(
            RestFallbackOrchestrator.parseRetryAfterHeader("30s"),
            "numeric with unit suffix → null",
        )
        assertNull(
            RestFallbackOrchestrator.parseRetryAfterHeader("Fri, 31 Dec 1999 23:59:59 GMT"),
            "HTTP-date form → null (RFC 7231 allows this; client treats it as fallback signal)",
        )
        assertNull(
            RestFallbackOrchestrator.parseRetryAfterHeader("Wed, 21 Oct 2015 07:28:00 GMT"),
            "another HTTP-date form → null",
        )
        assertNull(
            RestFallbackOrchestrator.parseRetryAfterHeader("3.14"),
            "decimal → null (Retry-After is an integer-seconds field)",
        )
        assertNull(
            RestFallbackOrchestrator.parseRetryAfterHeader("1e3"),
            "scientific notation → null",
        )
    }

    // ── M-B24 sub-cell (c) — overflow-safe clamp ────────────────────────────

    @Test
    fun mb24c_clampRetryAfterMs_clamps_overflow_safely() {
        // The clamp pattern is `secs.coerceAtMost(120) * 1_000L`.
        // The coerce runs on the seconds value BEFORE multiplication
        // so the multiplication can never overflow regardless of
        // input. A hostile relay sending `Retry-After: 86400` (one
        // day) cannot lock the client out for a day; `Long.MAX_VALUE`
        // produces 120_000 ms not undefined behaviour.
        assertNull(
            RestFallbackOrchestrator.clampRetryAfterMs(null),
            "null input → null output (caller falls back to own backoff)",
        )
        assertEquals(
            1_000L,
            RestFallbackOrchestrator.clampRetryAfterMs(1L),
            "1 s under the cap → 1_000 ms verbatim",
        )
        assertEquals(
            30_000L,
            RestFallbackOrchestrator.clampRetryAfterMs(30L),
            "30 s under the cap → 30_000 ms verbatim",
        )
        assertEquals(
            120_000L,
            RestFallbackOrchestrator.clampRetryAfterMs(120L),
            "120 s exactly at the cap → 120_000 ms",
        )
        assertEquals(
            120_000L,
            RestFallbackOrchestrator.clampRetryAfterMs(121L),
            "121 s above the cap → 120_000 ms (clamped)",
        )
        assertEquals(
            120_000L,
            RestFallbackOrchestrator.clampRetryAfterMs(86_400L),
            "86400 s (one day) → 120_000 ms (clamped — hostile relay protection)",
        )
        assertEquals(
            120_000L,
            RestFallbackOrchestrator.clampRetryAfterMs(Long.MAX_VALUE),
            "Long.MAX_VALUE → 120_000 ms (overflow-safe — clamp runs BEFORE multiplication)",
        )
        // Sanity: no `Long` arithmetic overflow ever produces a
        // negative result regardless of input. A multiplied-then-
        // clamped implementation would produce a negative on
        // Long.MAX_VALUE × 1_000 (wraps). Our clamp-then-multiply
        // implementation cannot.
        val extreme = RestFallbackOrchestrator.clampRetryAfterMs(Long.MAX_VALUE)
        assertNotNull(extreme)
        assertTrue(
            extreme > 0L,
            "clamp output for Long.MAX_VALUE must be positive (no overflow); got $extreme",
        )
    }

    // ── M-B24 sub-cell (d) — clamp applies in BOTH poll loops + send ────────

    @Test
    fun mb24d_poll_loop_429_with_Retry_After_consumes_typed_field_and_delays_exactly() = runTest {
        // The orchestrator's legacy `pollLoop` 429 branch reads
        // `response.retryAfterSeconds`, passes it through
        // `clampRetryAfterMs`, and uses the resulting milliseconds
        // as the inter-iteration delay (no jitter — relay
        // scheduling).
        //
        // We use a sub-cap value (30 s) so the clamp is a no-op and
        // the assertion holds inside a normal virtual-time budget.
        // The CLAMP-at-the-ceiling property is pinned independently
        // in `mb24c_clampRetryAfterMs_clamps_overflow_safely` over
        // the pure helper; this test pins the CONSUMPTION path —
        // that the poll loop reads `retryAfterSeconds` and feeds it
        // through `clampRetryAfterMs` rather than ignoring the
        // typed field or using the legacy `POLL_FAIL_BACKOFF_MS`
        // jittered default. The legacy fallback delay is 5_000 ms
        // jittered to roughly `[4_000, 6_000]` ms; the 30-s
        // Retry-After value sits clearly above that band, so a
        // FAILURE to consume the typed field would surface as a
        // second poll call landing well before the 30-s mark.
        val pollCalls = mutableListOf<Int>()
        val transport = RetryAfterTransport(
            pollScript = { _ ->
                pollCalls += 1
                if (pollCalls.size == 1) {
                    RestFallbackResponse(
                        statusCode = 429,
                        bodyParsed = null,
                        rawBody = "",
                        elapsedMs = 1L,
                        retryAfterSeconds = 30L,
                    )
                } else {
                    RestFallbackResponse(
                        statusCode = 200,
                        bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                        rawBody = "{}",
                        elapsedMs = 1L,
                    )
                }
            },
        )
        val orch = buildOrchestrator(transport, testScheduler)
        val caps = orch.bootstrap()
        check(caps.restFallback)
        repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
            orch.submitEvent(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                ),
            )
        }
        check(orch.stateMachine.state.value == RestMode.RestActive)
        orch.start()
        runCurrent()
        // First poll lands; returns 429 + Retry-After: 30.
        advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS + 100L)
        runCurrent()
        val callsAfterFirst = pollCalls.size
        assertTrue(
            callsAfterFirst >= 1,
            "expected at least one poll call after start + a few iterations; got $callsAfterFirst",
        )
        // Advance to a point well past the legacy backoff (5_000 ms
        // jittered to ≤ 6_000) but BEFORE the consumed 30_000 ms
        // Retry-After window expires. A second call landing here
        // would prove the loop ignored `retryAfterSeconds` and fell
        // back to legacy backoff.
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(
            callsAfterFirst,
            pollCalls.size,
            "the pollLoop must consume `retryAfterSeconds` and delay for 30_000 ms; " +
                "a poll landing at the 15-s mark proves it fell back to legacy backoff. " +
                "Got pollCalls.size=${pollCalls.size}, expected $callsAfterFirst.",
        )
        // Cross the 30-s mark plus a small slack; the next call lands.
        advanceTimeBy(16_000L)
        runCurrent()
        assertTrue(
            pollCalls.size > callsAfterFirst,
            "after the Retry-After delay elapses, the pollLoop must resume polling. " +
                "Got pollCalls.size=${pollCalls.size}, expected > $callsAfterFirst.",
        )
        orch.stop()
        runCurrent()
    }

    @Test
    fun mb24d_send_429_with_Retry_After_86400_delays_120_000_ms_clamped() = runTest(timeout = 5.minutes) {
        // Same shape as the pollLoop test, on the send retry path.
        // Send sees 429 + Retry-After: 86400 → next attempt is
        // delayed by exactly 120_000 ms (clamped).
        val sendCalls = mutableListOf<Int>()
        val transport = RetryAfterTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                    rawBody = "{}",
                    elapsedMs = 1L,
                )
            },
            sendScript = { _ ->
                sendCalls += 1
                if (sendCalls.size == 1) {
                    RestFallbackResponse(
                        statusCode = 429,
                        bodyParsed = null,
                        rawBody = "",
                        elapsedMs = 1L,
                        retryAfterSeconds = 86_400L,
                    )
                } else {
                    RestFallbackResponse(
                        statusCode = 201,
                        bodyParsed = SendResponse(ok = 1),
                        rawBody = "{}",
                        elapsedMs = 1L,
                    )
                }
            },
        )
        val orch = buildOrchestrator(transport, testScheduler)
        val caps = orch.bootstrap()
        check(caps.restFallback)
        val sendJob = launch {
            orch.sendEnvelope(
                envelopeId = "env-1",
                toHex = "ff".repeat(32),
                payloadBase64 = "AA==",
                sequenceTs = 1_000L,
                sealedSenderBase64 = "",
            )
        }
        runCurrent()
        // First attempt: lands → 429 + Retry-After: 86400. Backoff
        // begins.
        assertEquals(1, sendCalls.size, "first attempt landed")
        // Advance JUST BEFORE the clamped delay (120_000 ms).
        advanceTimeBy(119_998L)
        runCurrent()
        assertEquals(
            1,
            sendCalls.size,
            "clamped Retry-After delay must hold the send retry for the full 120_000 ms",
        )
        // Cross the 120-s mark; the next attempt lands.
        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue(
            sendCalls.size >= 2,
            "after the clamped Retry-After delay, send must retry. Got sendCalls.size=${sendCalls.size}",
        )
        sendJob.cancel()
        runCurrent()
        orch.stop()
        runCurrent()
    }

    // ── M13 — breaker state transitions ─────────────────────────────────────

    @Test
    fun m13a_closed_to_open_on_N_consecutive_failures() = runTest(timeout = 5.minutes) {
        // Drive `BREAKER_CONSECUTIVE_FAIL_THRESHOLD` consecutive
        // network-class failures through the production pollLoop
        // and assert the breaker transitions from Closed to
        // Open(ConsecutiveRestFailures, BREAKER_INITIAL_COOLDOWN_MS).
        init()
        val transport = BreakerTestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 500,
                    bodyParsed = null,
                    rawBody = "internal-server-error",
                    elapsedMs = 1L,
                )
            },
        )
        val orch = buildOrchestrator(transport, testScheduler)
        try {
            val caps = orch.bootstrap()
            check(caps.restFallback)
            repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
                orch.submitEvent(
                    RestStateMachine.Event.WsSessionEnded(
                        durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    ),
                )
            }
            orch.start()
            runCurrent()
            // The 5xx branch sleeps `intervalMs.coerceAtLeast(POLL_FAIL_BACKOFF_MS)`
            // = 5_000 ms jittered between iterations. Capture the FIRST
            // Open transition (cooldown == BREAKER_INITIAL_COOLDOWN_MS)
            // before the cooldown timer fires and triggers a probe cycle
            // that doubles the cooldown. Sample at fine 500 ms
            // granularity so a long advance does not envelop both the
            // first Open AND the cooldown timer + failed probe within
            // one dispatch.
            var firstOpen: LongPollBreakerState.Open? = null
            repeat(200) {
                if (firstOpen != null) return@repeat
                advanceTimeBy(500L)
                runCurrent()
                val s = orch.peekBreakerStateForTest()
                if (s is LongPollBreakerState.Open && firstOpen == null) {
                    firstOpen = s
                }
            }
            val state = firstOpen
            assertTrue(
                state != null,
                "after ≥ N consecutive 5xx failures the breaker must enter Open at least once. " +
                    "pollCalls=${transport.pollCalls.size}.",
            )
            assertEquals(
                BreakerOpenReason.ConsecutiveRestFailures,
                state.reason,
                "Open reason must be ConsecutiveRestFailures for 5xx burst",
            )
            assertEquals(
                RestFallbackOrchestrator.BREAKER_INITIAL_COOLDOWN_MS,
                state.cooldownMs,
                "first opening uses BREAKER_INITIAL_COOLDOWN_MS verbatim",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun m13b_open_to_halfopen_after_cooldown_window() = runTest(timeout = 5.minutes) {
        // Trip the breaker via a 5xx burst, then advance virtual
        // time by the cooldown so the timer Job's delay fires.
        // Capture the FIRST HalfOpen observation because subsequent
        // cycles oscillate.
        init()
        val transport = BreakerTestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 500, bodyParsed = null,
                    rawBody = "", elapsedMs = 1L,
                )
            },
        )
        val orch = buildOrchestrator(transport, testScheduler)
        try {
            val caps = orch.bootstrap()
            check(caps.restFallback)
            repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
                orch.submitEvent(
                    RestStateMachine.Event.WsSessionEnded(
                        durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    ),
                )
            }
            orch.start()
            runCurrent()
            // Pump enough virtual time to trip Open AND observe the
            // subsequent timer-driven Open → HalfOpen transition.
            // Each 5xx iteration: ~5_000 ms jittered ⇒ 5 iterations to
            // trip ≈ 25_000-30_000 ms; then +cooldown (5_000 ms) ⇒
            // ≈ 35_000 ms total to first HalfOpen. Sample at fine
            // 500 ms granularity for up to 300 checkpoints (150_000 ms
            // virtual time) so a long advance does not envelop both
            // the timer fire AND the subsequent failed probe within
            // one dispatch (which would skip the HalfOpen observable
            // window entirely).
            var firstHalfOpen: LongPollBreakerState.HalfOpen? = null
            repeat(300) {
                if (firstHalfOpen != null) return@repeat
                advanceTimeBy(500L)
                runCurrent()
                val s = orch.peekBreakerStateForTest()
                if (s is LongPollBreakerState.HalfOpen && firstHalfOpen == null) {
                    firstHalfOpen = s
                }
            }
            val state = firstHalfOpen
            assertTrue(
                state != null,
                "after cooldown elapses the timer must transition Open → HalfOpen. " +
                    "pollCalls=${transport.pollCalls.size}.",
            )
            assertEquals(
                false,
                state.probeInFlight,
                "the HalfOpen permit must start at probeInFlight=false so the next iteration on " +
                    "either loop can claim it",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun m13c_halfopen_plus_success_probe_returns_to_closed_and_resets_cooldown() = runTest(timeout = 5.minutes) {
        // Seed HalfOpen(probeInFlight=false). Run a production poll
        // iteration that returns 200 OK. Assert state transitions to
        // Closed AND cooldown resets to BREAKER_INITIAL_COOLDOWN_MS.
        init()
        val transport = BreakerTestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                    rawBody = "{}", elapsedMs = 1L,
                )
            },
        )
        val orch = buildOrchestrator(transport, testScheduler)
        try {
            val caps = orch.bootstrap()
            check(caps.restFallback)
            repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
                orch.submitEvent(
                    RestStateMachine.Event.WsSessionEnded(
                        durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    ),
                )
            }
            orch.start()
            runCurrent()
            // start() resets `_breakerState` to Closed and clears the
            // counters. Seed HalfOpen AFTER start so the first
            // post-seed iteration of the pollLoop observes HalfOpen
            // and claims the probe permit.
            orch.setBreakerStateForTest(LongPollBreakerState.HalfOpen(probeInFlight = false))
            // 200 OK delays `intervalMs` (POLL_ACTIVE_MS = 2_000 ms),
            // so advance by 2_100 ms for each iteration. The first
            // iteration claims the probe permit, polls, gets 200,
            // and resolves to Closed.
            val perIteration = RestFallbackOrchestrator.POLL_ACTIVE_MS + 100L
            var observed: LongPollBreakerState? = null
            repeat(10) {
                if (observed == LongPollBreakerState.Closed) return@repeat
                advanceTimeBy(perIteration)
                runCurrent()
                observed = orch.peekBreakerStateForTest()
            }
            assertEquals(
                LongPollBreakerState.Closed,
                observed,
                "successful probe must transition HalfOpen → Closed. " +
                    "pollCalls=${transport.pollCalls.size}.",
            )
            assertEquals(
                RestFallbackOrchestrator.BREAKER_INITIAL_COOLDOWN_MS,
                orch.peekBreakerCooldownMsForTest(),
                "cooldown must reset to BREAKER_INITIAL_COOLDOWN_MS on success",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun m13d_halfopen_plus_failure_probe_reopens_with_doubled_cooldown() = runTest(timeout = 5.minutes) {
        // Seed HalfOpen(probeInFlight=false). Force the probe to
        // fail (5xx). Assert state re-opens with cooldown doubled
        // per BREAKER_COOLDOWN_GROWTH_FACTOR (capped at
        // BREAKER_COOLDOWN_CEILING_MS).
        init()
        val transport = BreakerTestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 500, bodyParsed = null,
                    rawBody = "internal-server-error", elapsedMs = 1L,
                )
            },
        )
        val orch = buildOrchestrator(transport, testScheduler)
        try {
            val caps = orch.bootstrap()
            check(caps.restFallback)
            repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
                orch.submitEvent(
                    RestStateMachine.Event.WsSessionEnded(
                        durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    ),
                )
            }
            orch.start()
            runCurrent()
            // Same rationale as m13c: seed HalfOpen AFTER start so
            // the first post-seed iteration sees the HalfOpen state
            // (start resets it to Closed). _breakerCurrentCooldownMs
            // is also reset to BREAKER_INITIAL_COOLDOWN_MS by start,
            // so the failed probe doubles 5_000 → 10_000.
            orch.setBreakerStateForTest(LongPollBreakerState.HalfOpen(probeInFlight = false))
            // 5xx iterations delay POLL_FAIL_BACKOFF_MS (5_000 ms)
            // jittered. The FIRST iteration after the seed claims the
            // probe, polls, gets 500, runs recordRestFailure which
            // doubles the cooldown 5_000 → 10_000. Sample at fine
            // 500 ms granularity to capture this transition before
            // the next cooldown timer fires (which would double again
            // 10_000 → 20_000 on the next failed probe).
            var firstOpen: LongPollBreakerState.Open? = null
            repeat(200) {
                if (firstOpen != null) return@repeat
                advanceTimeBy(500L)
                runCurrent()
                val s = orch.peekBreakerStateForTest()
                if (s is LongPollBreakerState.Open && firstOpen == null) {
                    firstOpen = s
                }
            }
            val state = firstOpen
            assertTrue(
                state != null,
                "failed probe must transition HalfOpen → Open at least once. " +
                    "pollCalls=${transport.pollCalls.size}.",
            )
            assertEquals(
                BreakerOpenReason.ConsecutiveRestFailures,
                state.reason,
                "reopen reason after failed probe is ConsecutiveRestFailures",
            )
            val expectedDoubled = (RestFallbackOrchestrator.BREAKER_INITIAL_COOLDOWN_MS.toDouble()
                * RestFallbackOrchestrator.BREAKER_COOLDOWN_GROWTH_FACTOR)
                .toLong()
                .coerceAtMost(RestFallbackOrchestrator.BREAKER_COOLDOWN_CEILING_MS)
            assertEquals(
                expectedDoubled,
                state.cooldownMs,
                "failed probe doubles the cooldown via BREAKER_COOLDOWN_GROWTH_FACTOR " +
                    "(capped at BREAKER_COOLDOWN_CEILING_MS). " +
                    "Got ${state.cooldownMs}, expected $expectedDoubled.",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    // ── M-B28 — half-open probe singleton with cancellation safety ──────────

    @Test
    fun mb28a_atomic_probe_permit_claim_is_exclusive_across_concurrent_callers() = runTest {
        // The atomic claim is the load-bearing property: two REST
        // poll loops waking on the same tick MUST NOT each issue a
        // probe. The CAS lives inside `gateBreakerForIteration`
        // under `_inboundStateMutex`. Drive both calls through the
        // production seam back-to-back; the second call sees
        // probeInFlight=true and skips.
        init()
        val transport = BreakerTestTransport(pollScript = { _ ->
            RestFallbackResponse(statusCode = 200, bodyParsed = null, rawBody = "", elapsedMs = 1L)
        })
        val orch = buildOrchestrator(transport, testScheduler)
        val caps = orch.bootstrap()
        check(caps.restFallback)
        orch.setBreakerStateForTest(LongPollBreakerState.HalfOpen(probeInFlight = false))

        val firstDecision = orch.gateBreakerForIterationForTest()
        assertEquals(
            "probe",
            firstDecision,
            "first caller on HalfOpen(probeInFlight=false) must claim the probe permit",
        )
        val afterFirstClaim = orch.peekBreakerStateForTest()
        assertEquals(
            LongPollBreakerState.HalfOpen(probeInFlight = true),
            afterFirstClaim,
            "after the first claim the state must flip to HalfOpen(probeInFlight=true)",
        )

        val secondDecision = orch.gateBreakerForIterationForTest()
        assertEquals(
            "skip:breaker_half_open_probe_in_flight",
            secondDecision,
            "second caller on HalfOpen(probeInFlight=true) must skip with the probe-in-flight reason",
        )
        val afterSecondCall = orch.peekBreakerStateForTest()
        assertEquals(
            LongPollBreakerState.HalfOpen(probeInFlight = true),
            afterSecondCall,
            "the second caller MUST NOT flip the permit — only the first caller's probe owns it",
        )
    }

    @Test
    fun mb28b_cancellation_safe_release_resets_permit_when_state_is_still_inflight() = runTest {
        // Simulate the cancellation path: the claimant loop was
        // mid-probe (state = HalfOpen(probeInFlight=true)) and gets
        // cancelled. The finally block invokes
        // `releaseProbePermitIfStillHeld` under
        // `withContext(NonCancellable)`. The state must reset to
        // HalfOpen(probeInFlight=false).
        init()
        val transport = BreakerTestTransport(pollScript = { _ -> error("not used") })
        val orch = buildOrchestrator(transport, testScheduler)
        val caps = orch.bootstrap()
        check(caps.restFallback)
        orch.setBreakerStateForTest(LongPollBreakerState.HalfOpen(probeInFlight = true))
        check(orch.peekBreakerStateForTest() == LongPollBreakerState.HalfOpen(probeInFlight = true))

        orch.releaseProbePermitIfStillHeldForTest()
        assertEquals(
            LongPollBreakerState.HalfOpen(probeInFlight = false),
            orch.peekBreakerStateForTest(),
            "release MUST flip HalfOpen(probeInFlight=true) → HalfOpen(probeInFlight=false)",
        )
    }

    @Test
    fun mb28b_release_is_noop_when_state_has_already_resolved() = runTest {
        // The release MUST be idempotent / no-op when the probe
        // already resolved into Closed (via recordRestSuccess) or
        // Open (via recordRestFailure). The finally fires AFTER
        // the resolution; the state is no longer HalfOpen.
        init()
        val transport = BreakerTestTransport(pollScript = { _ -> error("not used") })
        val orch = buildOrchestrator(transport, testScheduler)
        val caps = orch.bootstrap()
        check(caps.restFallback)

        orch.setBreakerStateForTest(LongPollBreakerState.Closed)
        orch.releaseProbePermitIfStillHeldForTest()
        assertEquals(
            LongPollBreakerState.Closed,
            orch.peekBreakerStateForTest(),
            "release MUST NOT touch Closed state (probe already resolved to success)",
        )

        orch.setBreakerStateForTest(LongPollBreakerState.Open(BreakerOpenReason.ConsecutiveRestFailures, 5_000L))
        orch.releaseProbePermitIfStillHeldForTest()
        val openAfterRelease = orch.peekBreakerStateForTest()
        assertTrue(
            openAfterRelease is LongPollBreakerState.Open,
            "release MUST NOT touch Open state (probe already resolved to failure)",
        )
    }

    @Test
    fun mb28c_recovery_after_claimant_cancellation_next_caller_reclaims_permit() = runTest {
        // The cancellation path MUST NOT strand the breaker
        // permanently. After the release flips state back to
        // HalfOpen(probeInFlight=false), the next call to
        // `gateBreakerForIteration` on either loop claims the
        // permit and proceeds with a fresh probe.
        init()
        val transport = BreakerTestTransport(pollScript = { _ -> error("not used") })
        val orch = buildOrchestrator(transport, testScheduler)
        val caps = orch.bootstrap()
        check(caps.restFallback)
        // Seed mid-cancellation state.
        orch.setBreakerStateForTest(LongPollBreakerState.HalfOpen(probeInFlight = true))
        orch.releaseProbePermitIfStillHeldForTest()
        check(orch.peekBreakerStateForTest() == LongPollBreakerState.HalfOpen(probeInFlight = false))

        // Next caller claims fresh.
        val decision = orch.gateBreakerForIterationForTest()
        assertEquals(
            "probe",
            decision,
            "after cancellation+release, the next caller MUST be able to re-claim the probe permit",
        )
        assertEquals(
            LongPollBreakerState.HalfOpen(probeInFlight = true),
            orch.peekBreakerStateForTest(),
            "the re-claim flips state back to HalfOpen(probeInFlight=true)",
        )
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private val IDENTITY: String = "aa".repeat(32)

    private suspend fun init() {
        if (!com.ionspin.kotlin.crypto.LibsodiumInitializer.isInitialized()) {
            com.ionspin.kotlin.crypto.LibsodiumInitializer.initialize()
        }
    }

    /**
     * Scriptable transport for breaker behaviour tests. Each poll
     * call increments the script index; the script can return any
     * response shape. Bootstrap always succeeds with
     * `restFallback=true`.
     */
    private class BreakerTestTransport(
        var pollScript: (callIndex: Int) -> RestFallbackResponse<PollResponse>,
    ) : RestFallbackTransport {
        val pollCalls: MutableList<Long?> = mutableListOf()
        val authCalls: MutableList<Unit> = mutableListOf()
        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> {
            authCalls += Unit
            return RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AuthSessionResponse(
                    token = "tok-${authCalls.size}",
                    expiresAt = Long.MAX_VALUE,
                    restFallback = true,
                    maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1,
                    pollHoldSecs = 30,
                    seqMacVerifyKey = "",
                ),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        }
        override suspend fun send(
            url: String, token: String, idempotencyKey: String, body: SendRequest,
        ): RestFallbackResponse<SendResponse> = fail("send not used in breaker tests")
        override suspend fun poll(
            url: String, token: String, sinceSeq: Long?,
            longPollOptIn: Boolean, readTimeoutMs: Long?,
        ): RestFallbackResponse<PollResponse> {
            pollCalls += sinceSeq
            return pollScript(pollCalls.size - 1)
        }
        override suspend fun ackDeliver(
            url: String, token: String, body: AckDeliverRequest,
        ): RestFallbackResponse<AckDeliverResponse> = RestFallbackResponse(
            statusCode = 200,
            bodyParsed = AckDeliverResponse(ok = 1),
            rawBody = "{}",
            elapsedMs = 1L,
        )
    }

    private fun buildOrchestrator(
        transport: BreakerTestTransport,
        scheduler: TestCoroutineScheduler,
        longPollEnabled: Boolean = false,
        logSink: (String) -> Unit = {},
    ): RestFallbackOrchestrator = RestFallbackOrchestrator(
        baseUrl = "https://relay.test",
        identityHex = IDENTITY,
        signingPubkeyHex = "bb".repeat(32),
        getChallenge = { _ -> "cc".repeat(32) },
        signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
        transport = transport,
        now = { 0L },
        log = logSink,
        longPollEnabled = longPollEnabled,
        cursorRepository = NoopCursor(),
        dispatcher = StandardTestDispatcher(scheduler),
    )

    private class RetryAfterTransport(
        var pollScript: (callIndex: Int) -> RestFallbackResponse<PollResponse>,
        var sendScript: ((callIndex: Int) -> RestFallbackResponse<SendResponse>)? = null,
    ) : RestFallbackTransport {
        val pollCalls: MutableList<Long?> = mutableListOf()
        val sendCalls: MutableList<String> = mutableListOf()
        val authCalls: MutableList<Unit> = mutableListOf()

        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> {
            authCalls += Unit
            return RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AuthSessionResponse(
                    token = "tok-${authCalls.size}",
                    expiresAt = Long.MAX_VALUE,
                    restFallback = true,
                    maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1,
                    pollHoldSecs = 30,
                    seqMacVerifyKey = "",
                ),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        }

        override suspend fun send(
            url: String,
            token: String,
            idempotencyKey: String,
            body: SendRequest,
        ): RestFallbackResponse<SendResponse> {
            sendCalls += body.envelopeId
            return sendScript?.invoke(sendCalls.size - 1)
                ?: fail("send unexpected — no sendScript configured")
        }

        override suspend fun poll(
            url: String,
            token: String,
            sinceSeq: Long?,
            longPollOptIn: Boolean,
            readTimeoutMs: Long?,
        ): RestFallbackResponse<PollResponse> {
            pollCalls += sinceSeq
            return pollScript(pollCalls.size - 1)
        }

        override suspend fun ackDeliver(
            url: String,
            token: String,
            body: AckDeliverRequest,
        ): RestFallbackResponse<AckDeliverResponse> = RestFallbackResponse(
            statusCode = 200,
            bodyParsed = AckDeliverResponse(ok = 1),
            rawBody = "{}",
            elapsedMs = 1L,
        )
    }

    private class NoopCursor : LongPollCursorRepository {
        override suspend fun getLastSeenSeq(identityHex: String): Long? = null
        override suspend fun upsertLastSeenSeq(identityHex: String, seq: Long, nowMs: Long) {}
    }

    private fun buildOrchestrator(
        transport: RetryAfterTransport,
        scheduler: TestCoroutineScheduler,
        logSink: (String) -> Unit = {},
    ): RestFallbackOrchestrator = RestFallbackOrchestrator(
        baseUrl = "https://relay.test",
        identityHex = IDENTITY,
        signingPubkeyHex = "bb".repeat(32),
        getChallenge = { _ -> "cc".repeat(32) },
        signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
        transport = transport,
        now = { 0L },
        log = logSink,
        longPollEnabled = false,
        cursorRepository = NoopCursor(),
        dispatcher = StandardTestDispatcher(scheduler),
    )
}
