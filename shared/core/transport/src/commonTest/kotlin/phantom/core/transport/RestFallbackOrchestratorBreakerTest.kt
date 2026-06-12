// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
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

    // ── M14 — 410 reauth dance ──────────────────────────────────────────────

    @Test
    fun m14a_410_then_authSession_refresh_then_200_emits_envelope() = runTest(timeout = 5.minutes) {
        // The poll loop hits 410 on the first call; handle410
        // refreshes the token; the second poll uses the new token
        // and returns 200 with one envelope. Assert: the envelope
        // reaches the orchestrator's `inbound` SharedFlow AND a
        // second authSession call happened.
        init()
        val envelope = PollEnvelope(
            id = "env-m14a",
            fromHex = "ff".repeat(32),
            payloadBase64 = "AA==",
            sequenceTs = 1_000L,
            seq = 1L,
            seqMac = "",
        )
        val transport = BreakerTestTransport(
            pollScript = { i ->
                if (i == 0) {
                    RestFallbackResponse(
                        statusCode = 410, bodyParsed = null,
                        rawBody = "gone", elapsedMs = 1L,
                    )
                } else {
                    RestFallbackResponse(
                        statusCode = 200,
                        bodyParsed = PollResponse(envelopes = listOf(envelope), more = false),
                        rawBody = "{}", elapsedMs = 1L,
                    )
                }
            },
        )
        val orch = buildOrchestrator(transport, testScheduler)
        val received = mutableListOf<PollEnvelope>()
        try {
            val caps = orch.bootstrap()
            check(caps.restFallback)
            val collectJob = launch { orch.inbound.collect { received += it } }
            runCurrent()
            repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
                orch.submitEvent(
                    RestStateMachine.Event.WsSessionEnded(
                        durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    ),
                )
            }
            orch.start()
            runCurrent()
            // Pump enough virtual time for the 410 dance + the
            // 5_000 ms post-410 delay + the 200 follow-up poll.
            repeat(50) {
                if (received.isNotEmpty()) return@repeat
                advanceTimeBy(500L)
                runCurrent()
            }
            assertTrue(
                received.isNotEmpty(),
                "after 410 dance + 200 follow-up, the envelope MUST reach inbound flow. " +
                    "pollCalls=${transport.pollCalls.size} authCalls=${transport.authCalls.size}.",
            )
            assertTrue(
                transport.authCalls.size >= 2,
                "handle410 MUST trigger a session refresh (authSession call). " +
                    "Got authCalls=${transport.authCalls.size} (≥ 2 expected: bootstrap + refresh).",
            )
            collectJob.cancel()
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun m14b_410_then_reauth_fails_publishes_KeySuspended_via_L2_corollary() = runTest(timeout = 5.minutes) {
        // 410 triggers handle410 → acquireOrRefreshToken. The
        // session script returns a NON-200 on the second call so
        // the refresh fails. The L2 corollary
        // (Failure → KeySuspended) fires through the existing
        // classifier inside acquireOrRefreshToken. Assert:
        // peekVerifyKeyStateForTest() == KeySuspended.
        init()
        val transport = BreakerTestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 410, bodyParsed = null,
                    rawBody = "gone", elapsedMs = 1L,
                )
            },
            sessionScript = { i ->
                if (i == 0) {
                    // Bootstrap succeeds.
                    RestFallbackResponse(
                        statusCode = 200,
                        bodyParsed = AuthSessionResponse(
                            token = "tok-bootstrap",
                            expiresAt = Long.MAX_VALUE,
                            restFallback = true,
                            maxSendBodyBytes = 4096,
                            pollMaxEnvelopes = 1,
                            pollHoldSecs = 30,
                            seqMacVerifyKey = "0123456789abcdef".repeat(4),
                        ),
                        rawBody = "{}", elapsedMs = 1L,
                    )
                } else {
                    // Refresh fails.
                    RestFallbackResponse(
                        statusCode = 503, bodyParsed = null,
                        rawBody = "service-unavailable", elapsedMs = 1L,
                    )
                }
            },
        )
        val orch = buildOrchestrator(transport, testScheduler)
        try {
            val caps = orch.bootstrap()
            check(caps.restFallback)
            check(orch.peekVerifyKeyStateForTest() is VerifyKeyState.KeyPresent)
            repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
                orch.submitEvent(
                    RestStateMachine.Event.WsSessionEnded(
                        durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    ),
                )
            }
            orch.start()
            runCurrent()
            // Pump until KeySuspended is observed.
            var observedSuspended = false
            repeat(50) {
                if (observedSuspended) return@repeat
                advanceTimeBy(500L)
                runCurrent()
                if (orch.peekVerifyKeyStateForTest() == VerifyKeyState.KeySuspended) {
                    observedSuspended = true
                }
            }
            assertTrue(
                observedSuspended,
                "after 410 dance triggers a failed refresh, the L2 corollary MUST publish " +
                    "KeySuspended. Got ${orch.peekVerifyKeyStateForTest()}. " +
                    "pollCalls=${transport.pollCalls.size} authCalls=${transport.authCalls.size}.",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun m14c_three_consecutive_410s_trips_breaker_to_Open_with_Status410Storm() = runTest(timeout = 5.minutes) {
        // 3 consecutive 410s within 30 s wall-clock trip the
        // breaker to Open(Status410Storm,
        // BREAKER_410_STORM_COOLDOWN_MS=60_000 ms). With now()
        // mocked to 0 in the test fixture, every timestamp lands
        // at 0 — well within any window — so the threshold-th 410
        // trips deterministically on the 3rd call regardless of
        // virtual time.
        init()
        val transport = BreakerTestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 410, bodyParsed = null,
                    rawBody = "gone", elapsedMs = 1L,
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
            // Capture the FIRST Open observation; assert it carries
            // the Status410Storm reason and the ceiling cooldown.
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
                "3 consecutive 410s within 30 s MUST trip the breaker. " +
                    "pollCalls=${transport.pollCalls.size}.",
            )
            assertEquals(
                BreakerOpenReason.Status410Storm,
                state.reason,
                "Open reason on 410 storm MUST be Status410Storm (NOT ConsecutiveRestFailures)",
            )
            assertEquals(
                RestFallbackOrchestrator.BREAKER_410_STORM_COOLDOWN_MS,
                state.cooldownMs,
                "Storm open cooldown MUST be BREAKER_410_STORM_COOLDOWN_MS (60 s ceiling)",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun m14d_cursor_is_not_advanced_during_410_dance() = runTest(timeout = 5.minutes) {
        // Cursor advancement only happens via
        // ackInboundAndAdvanceCursor (called by the downstream
        // consumer after successful decrypt + persist). The 410
        // dance MUST NOT advance the cursor. Assert: after one 410
        // followed by a 200 with empty body, no cursor write
        // happened.
        init()
        val transport = BreakerTestTransport(
            pollScript = { i ->
                when (i) {
                    0 -> RestFallbackResponse(
                        statusCode = 410, bodyParsed = null,
                        rawBody = "gone", elapsedMs = 1L,
                    )
                    1 -> RestFallbackResponse(
                        statusCode = 200,
                        bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                        rawBody = "{}", elapsedMs = 1L,
                    )
                    else -> RestFallbackResponse(
                        statusCode = 200,
                        bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                        rawBody = "{}", elapsedMs = 1L,
                    )
                }
            },
        )
        val cursor = RecordingCursorRepo()
        cursor.stored = 42L  // pre-seed; assert it stays.
        val orch = buildOrchestrator(transport, testScheduler, cursor = cursor)
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
            // Pump enough time for the 410 + 200 cycle.
            repeat(40) {
                advanceTimeBy(500L)
                runCurrent()
            }
            assertTrue(
                transport.pollCalls.size >= 2,
                "expected at least 2 poll calls (410 + 200 follow-up); got ${transport.pollCalls.size}",
            )
            assertEquals(
                emptyList(),
                cursor.writes,
                "the 410 dance MUST NOT write to the cursor repository. Got writes=${cursor.writes}.",
            )
            assertEquals(
                42L,
                cursor.stored,
                "the pre-seeded cursor value MUST be preserved across the 410 dance",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun m14e_410_on_ack_deliver_does_NOT_trigger_handle410_reauth_dance() = runTest(timeout = 5.minutes) {
        // M14(e): per Stage 1.x Lock-3, 410 on `/relay/ack-deliver`
        // uses the existing self-healing fall-through, NOT the
        // reauth dance. handle410 is wired to the POLL response
        // branches only; the ack-deliver code path runs through
        // `ackInboundAndAdvanceCursor` and classifies the ack
        // outcome via the existing surface. Test pins the
        // asymmetry: a 410 ack response must NOT pump the
        // 410-storm timestamp list.
        init()
        val envelope = PollEnvelope(
            id = "env-m14e",
            fromHex = "ff".repeat(32),
            payloadBase64 = "AA==",
            sequenceTs = 1_000L,
            seq = 1L,
            seqMac = "",
        )
        val transport = BreakerTestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = PollResponse(envelopes = listOf(envelope), more = false),
                    rawBody = "{}", elapsedMs = 1L,
                )
            },
            ackScript = { _, _ ->
                // Ack-deliver returns 410.
                RestFallbackResponse(
                    statusCode = 410, bodyParsed = null,
                    rawBody = "gone", elapsedMs = 1L,
                )
            },
        )
        val cursor = RecordingCursorRepo()
        val orch = buildOrchestrator(transport, testScheduler, cursor = cursor)
        try {
            val caps = orch.bootstrap()
            check(caps.restFallback)
            val received = mutableListOf<PollEnvelope>()
            val collectJob = launch {
                orch.inbound.collect {
                    received += it
                    // Simulate downstream consumer acknowledging.
                    orch.ackInboundAndAdvanceCursor(it.id)
                }
            }
            runCurrent()
            repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
                orch.submitEvent(
                    RestStateMachine.Event.WsSessionEnded(
                        durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    ),
                )
            }
            orch.start()
            runCurrent()
            // Pump until at least one ack-deliver call lands.
            repeat(60) {
                if (transport.ackCalls.isNotEmpty()) return@repeat
                advanceTimeBy(500L)
                runCurrent()
            }
            assertTrue(
                transport.ackCalls.isNotEmpty(),
                "ackInboundAndAdvanceCursor MUST call ack-deliver at least once. " +
                    "Got ackCalls=${transport.ackCalls.size}.",
            )
            // The breaker state MUST NOT have transitioned to
            // Status410Storm from the ack 410. The 410 dance is
            // wired ONLY to the poll response branches.
            val state = orch.peekBreakerStateForTest()
            assertTrue(
                state !is LongPollBreakerState.Open ||
                    state.reason != BreakerOpenReason.Status410Storm,
                "ack-deliver 410 MUST NOT trigger Status410Storm. Got state=$state.",
            )
            collectJob.cancel()
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    // ── M13(e) — cursor preserved across all breaker transitions ────────────

    @Test
    fun m13e_cursor_is_never_written_by_poll_loop_directly_across_all_transitions() = runTest(timeout = 5.minutes) {
        // The cursor advances ONLY via
        // ackInboundAndAdvanceCursor (called by the downstream
        // consumer). The pollLoop's response handling never writes
        // the cursor directly — including across breaker
        // transitions Closed → Open → HalfOpen → Open → HalfOpen.
        // Drive the full transition cycle via 5xx burst and assert
        // the cursor is never written.
        init()
        val transport = BreakerTestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 500, bodyParsed = null,
                    rawBody = "internal-server-error", elapsedMs = 1L,
                )
            },
        )
        val cursor = RecordingCursorRepo()
        cursor.stored = 123L  // pre-seed; assert preservation.
        val orch = buildOrchestrator(transport, testScheduler, cursor = cursor)
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
            // Pump through multiple breaker transitions.
            repeat(200) {
                advanceTimeBy(500L)
                runCurrent()
            }
            assertEquals(
                emptyList(),
                cursor.writes,
                "the pollLoop MUST NOT write the cursor across breaker transitions. " +
                    "Got writes=${cursor.writes}.",
            )
            assertEquals(
                123L,
                cursor.stored,
                "the pre-seeded cursor value MUST be preserved across all breaker transitions",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    // ── M-13e — stop() during Open cancels timer ────────────────────────────

    @Test
    fun m13e_stop_during_Open_cancels_breaker_timer_and_no_refire_on_next_start() = runTest(timeout = 5.minutes) {
        // The fixed sequence pinned by this test (per Vladislav's
        // lock):
        //   1. Drive the breaker to Open via a 5xx burst.
        //   2. Call stop() — the timer Job MUST be cancelled.
        //   3. Flip the fake transport to 200 so the next start
        //      cycle does not immediately re-trip Open.
        //   4. Call start() — the breaker state resets to Closed.
        //   5. Assert Closed AND the pre-seeded cursor preserved
        //      AND polling resumes WITHOUT a fresh Open opening.
        //
        // Without step 3 the test would conflate "stop+start reset"
        // with "the next 5xx burst immediately re-opens" — flipping
        // the transport to 200 isolates the reset semantic from
        // the re-trip semantic.
        init()
        var pollReturns500 = true
        val transport = BreakerTestTransport(
            pollScript = { _ ->
                if (pollReturns500) {
                    RestFallbackResponse(
                        statusCode = 500, bodyParsed = null,
                        rawBody = "", elapsedMs = 1L,
                    )
                } else {
                    RestFallbackResponse(
                        statusCode = 200,
                        bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                        rawBody = "{}", elapsedMs = 1L,
                    )
                }
            },
        )
        val cursor = RecordingCursorRepo()
        cursor.stored = 99L  // pre-seed; assert preservation across stop+start.
        val orch = buildOrchestrator(transport, testScheduler, cursor = cursor)
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
            // Pump until the breaker is Open.
            var observedOpen = false
            repeat(200) {
                if (observedOpen) return@repeat
                advanceTimeBy(500L)
                runCurrent()
                if (orch.peekBreakerStateForTest() is LongPollBreakerState.Open) {
                    observedOpen = true
                }
            }
            check(observedOpen) {
                "expected Open state before stop(); got ${orch.peekBreakerStateForTest()}. " +
                    "pollCalls=${transport.pollCalls.size}."
            }
            check(orch.peekBreakerStateForTest() is LongPollBreakerState.Open) { "expected Open" }
            // Step 2 — stop. Timer Job is cancelled by
            // cancelAndJoinAll Phase 2.
            orch.stop()
            runCurrent()
            // Demonstrate the timer was cancelled: advance past
            // BREAKER_COOLDOWN_CEILING_MS without a start, no
            // Open → HalfOpen transition fires. State stays Open
            // until start() resets it.
            advanceTimeBy(RestFallbackOrchestrator.BREAKER_COOLDOWN_CEILING_MS + 5_000L)
            runCurrent()
            assertTrue(
                orch.peekBreakerStateForTest() is LongPollBreakerState.Open,
                "after stop+advance, state MUST still be Open (the timer was cancelled, " +
                    "so the Open → HalfOpen transition does NOT fire). " +
                    "Got ${orch.peekBreakerStateForTest()}.",
            )
            // Step 3 — flip transport to 200 BEFORE start so we
            // isolate the reset semantic from the re-trip semantic.
            pollReturns500 = false
            val pollsBeforeRestart = transport.pollCalls.size
            // Step 4 — start. Resets state to Closed.
            orch.start()
            runCurrent()
            // Step 5 — assertions: Closed, cursor preserved, no
            // spurious transition, polling actually resumes.
            assertEquals(
                LongPollBreakerState.Closed,
                orch.peekBreakerStateForTest(),
                "after stop+start, breaker MUST be Closed (reset on start per scope §L9)",
            )
            assertEquals(
                99L,
                cursor.stored,
                "the pre-seeded cursor MUST be preserved across the stop+start cycle",
            )
            assertEquals(
                emptyList(),
                cursor.writes,
                "the pollLoop MUST NOT write the cursor during the breaker cycle. " +
                    "Got writes=${cursor.writes}.",
            )
            // Advance over a window much larger than any possible
            // stranded timer's cooldown. With 200 responses + a
            // freshly-Closed state, the only valid trajectory is
            // "stay Closed AND keep polling."
            advanceTimeBy(RestFallbackOrchestrator.BREAKER_COOLDOWN_CEILING_MS + 5_000L)
            runCurrent()
            assertEquals(
                LongPollBreakerState.Closed,
                orch.peekBreakerStateForTest(),
                "no stranded timer Job MUST fire after stop+start. " +
                    "Got ${orch.peekBreakerStateForTest()}.",
            )
            assertTrue(
                transport.pollCalls.size > pollsBeforeRestart,
                "polling MUST resume after start. Got pollCalls before=${pollsBeforeRestart} " +
                    "after=${transport.pollCalls.size}.",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    // ── C5-C review-fix regression pins ─────────────────────────────────────

    @Test
    fun cf_p11_mid_probe_cancellation_releases_permit_and_does_not_open_breaker() = runTest(timeout = 5.minutes) {
        // Round-1 C5-C review (P1.1): the poll loops wrapped
        // `transport.poll(...)` in `runCatching`, which swallows
        // `CancellationException`. A pollJob cancelled mid-suspend
        // in transport.poll would land in `outcome.isFailure` →
        // `recordRestFailure()` would (on a HalfOpen probe)
        // transition the breaker BACK to Open and spawn a NEW
        // timer Job. The new timer would spawn AFTER the Phase-1
        // observer cancellation in `cancelAndJoinAll`, so the
        // Phase-2 tail snapshot would miss it ⇒ orphan timer.
        //
        // Repro: pre-seed HalfOpen(probeInFlight=false), suspend
        // `transport.poll(...)` on a deferred, let the pollLoop
        // claim the probe and suspend mid-poll, then `stop()`.
        // Post-stop assertions:
        //   * Breaker state is `HalfOpen(probeInFlight=false)` —
        //     the `finally` block fired `releaseProbePermitIfStillHeld`,
        //     so the permit cleared. If `recordRestFailure` had
        //     run, state would be Open(...).
        init()
        val probeGate = CompletableDeferred<Unit>()
        val transport = BreakerTestTransport(
            pollScript = { _ ->
                probeGate.await()
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
            // Seed HalfOpen BEFORE runCurrent so the observer's
            // onModeChanged dispatches AFTER state is HalfOpen,
            // and the first pollLoop iteration claims the probe
            // permit (rather than seeing Closed and pretending
            // it's a normal iteration).
            orch.setBreakerStateForTest(LongPollBreakerState.HalfOpen(probeInFlight = false))
            runCurrent()
            // Pump until the pollLoop claims the probe AND
            // suspends in `transport.poll`.
            repeat(20) {
                if (transport.pollCalls.isNotEmpty()) return@repeat
                advanceTimeBy(500L)
                runCurrent()
            }
            check(transport.pollCalls.isNotEmpty()) {
                "expected the pollLoop to have entered transport.poll before the cancellation"
            }
            check(orch.peekBreakerStateForTest() == LongPollBreakerState.HalfOpen(probeInFlight = true)) {
                "expected the probe permit to be claimed; got ${orch.peekBreakerStateForTest()}"
            }
        } finally {
            orch.stop()
            runCurrent()
        }
        // POST-stop assertions: the cancellation flowed through the
        // try/finally and the permit was released. If the round-1
        // C5-C bug were still here, recordRestFailure would have
        // transitioned to Open and the state would NOT be HalfOpen.
        val state = orch.peekBreakerStateForTest()
        assertEquals(
            LongPollBreakerState.HalfOpen(probeInFlight = false),
            state,
            "mid-probe cancellation MUST release the probe permit via the finally " +
                "(state HalfOpen(probeInFlight=false)). If state is Open(...), the bug is back: " +
                "runCatching swallowed CancellationException and recordRestFailure spawned an " +
                "orphan timer Job. Got $state.",
        )
    }

    @Test
    fun cf_p12_first_410_delays_exactly_5_seconds_then_doubles_to_10_seconds() = runTest(timeout = 5.minutes) {
        // Round-1 C5-C review (P1.2): handle410 doubled the backoff
        // BEFORE returning, so the first 410 waited 10 s instead of
        // the scope-locked 5 s floor. Fix returns the current
        // backoff first, then doubles for next.
        //
        // Drive a 410 sequence: 1st poll = 410, 2nd poll = 410,
        // 3rd poll = 200. Use fine virtual-time sampling around
        // 5_000 ms / 15_000 ms ticks to pin the deterministic
        // sequence 5 s → 10 s → 200 (storm trigger would land on
        // 3rd, but we settle on 200 before that).
        init()
        val transport = BreakerTestTransport(
            pollScript = { i ->
                when (i) {
                    0, 1 -> RestFallbackResponse(
                        statusCode = 410, bodyParsed = null,
                        rawBody = "gone", elapsedMs = 1L,
                    )
                    else -> RestFallbackResponse(
                        statusCode = 200,
                        bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                        rawBody = "{}", elapsedMs = 1L,
                    )
                }
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
            // Wait for the 1st poll (returns 410).
            repeat(20) {
                if (transport.pollCalls.size >= 1) return@repeat
                advanceTimeBy(500L)
                runCurrent()
            }
            check(transport.pollCalls.size == 1) {
                "expected 1st poll to have landed; got ${transport.pollCalls.size}"
            }
            // Advance just under the 5 s first-410 floor; assert no
            // 2nd call yet.
            advanceTimeBy(4_500L)
            runCurrent()
            assertEquals(
                1,
                transport.pollCalls.size,
                "first 410 MUST delay 5 s — at the 4.5 s mark no 2nd poll fires. " +
                    "Got pollCalls.size=${transport.pollCalls.size}.",
            )
            // Cross the 5 s mark. The 2nd poll should land within
            // a few hundred ms after the delay fires.
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue(
                transport.pollCalls.size >= 2,
                "first 410 MUST release the loop within the 5 s + 0.5 s slack window. " +
                    "Got pollCalls.size=${transport.pollCalls.size}.",
            )
            // 2nd 410 → backoff doubles to 10 s.
            advanceTimeBy(9_000L)
            runCurrent()
            assertEquals(
                2,
                transport.pollCalls.size,
                "second 410 MUST delay 10 s (post-double) — at the 9 s mark no 3rd poll fires. " +
                    "Got pollCalls.size=${transport.pollCalls.size}.",
            )
            advanceTimeBy(2_000L)
            runCurrent()
            assertTrue(
                transport.pollCalls.size >= 3,
                "second 410 MUST release the loop within the 10 s + 1 s slack window. " +
                    "Got pollCalls.size=${transport.pollCalls.size}.",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun cf_p13_non_consecutive_410s_separated_by_500_do_NOT_trip_storm() = runTest(timeout = 5.minutes) {
        // Round-1 C5-C review (P1.3): scope §L8/§L9 pin the storm
        // trigger as "K CONSECUTIVE 410 Gone responses within W
        // wall-clock seconds." Pre-fix the storm timestamp list
        // was NOT cleared on non-410 responses, so an alternating
        // 410/500/410/500/410 sequence would falsely trip
        // Status410Storm on the third 410. Fix: `recordRestFailure`
        // clears the storm window on every transport-class failure
        // (which includes 5xx). 4xx-other and 200 go through
        // `recordRestSuccess` which already cleared the window.
        //
        // This test drives 7 polls (410, 500, 410, 500, 410, 500,
        // 410) and asserts the breaker is NEVER in
        // Open(Status410Storm, ...). The breaker MAY trip to
        // Open(ConsecutiveRestFailures, ...) if the 5xx fail count
        // reaches the threshold — but Status410Storm specifically
        // MUST NOT fire.
        init()
        val transport = BreakerTestTransport(
            pollScript = { i ->
                val status = if (i % 2 == 0) 410 else 500
                val body = if (status == 410) "gone" else "boom"
                RestFallbackResponse(
                    statusCode = status, bodyParsed = null,
                    rawBody = body, elapsedMs = 1L,
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
            // Pump 7 polls' worth of virtual time; capture whether
            // the breaker EVER entered Status410Storm.
            var observedStorm = false
            repeat(400) {
                if (transport.pollCalls.size >= 7 && observedStorm) return@repeat
                advanceTimeBy(500L)
                runCurrent()
                val s = orch.peekBreakerStateForTest()
                if (s is LongPollBreakerState.Open && s.reason == BreakerOpenReason.Status410Storm) {
                    observedStorm = true
                }
                if (transport.pollCalls.size >= 7 && !observedStorm) return@repeat
            }
            assertTrue(
                transport.pollCalls.size >= 7,
                "expected ≥ 7 poll calls; got ${transport.pollCalls.size}",
            )
            assertEquals(
                false,
                observedStorm,
                "non-consecutive 410s (410/500/410/500/410/500/410) MUST NOT trip " +
                    "Status410Storm — the 500s break the consecutive sequence per scope §L8. " +
                    "Got observedStorm=$observedStorm.",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun cf_p2_onRestPollDegraded_callback_fires_on_breaker_open() = runTest(timeout = 5.minutes) {
        // Round-1 C5-C review (P2): the `RestStateMachine.Event.RestPollDegraded`
        // handler only logged; the callback surface was wired
        // through the constructor but never invoked. Fix: the
        // handler now invokes `onRestPollDegraded?.invoke(reason)`
        // after the log line.
        //
        // Wire a recording callback through the orchestrator's
        // constructor; trip the breaker to Open via a 5xx burst;
        // assert the callback receives reason =
        // ConsecutiveRestFailures.
        init()
        val degradedReasons = mutableListOf<BreakerOpenReason>()
        val transport = BreakerTestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 500, bodyParsed = null,
                    rawBody = "", elapsedMs = 1L,
                )
            },
        )
        val orch = RestFallbackOrchestrator(
            baseUrl = "https://relay.test",
            identityHex = IDENTITY,
            signingPubkeyHex = "bb".repeat(32),
            getChallenge = { _ -> "cc".repeat(32) },
            signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
            transport = transport,
            now = { 0L },
            log = {},
            longPollEnabled = false,
            cursorRepository = NoopCursor(),
            dispatcher = StandardTestDispatcher(testScheduler),
            onRestPollDegraded = { reason -> degradedReasons += reason },
        )
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
            // Pump until the breaker trips at least once.
            repeat(200) {
                if (degradedReasons.isNotEmpty()) return@repeat
                advanceTimeBy(500L)
                runCurrent()
            }
            assertTrue(
                degradedReasons.isNotEmpty(),
                "onRestPollDegraded callback MUST fire when the breaker enters Open. " +
                    "Got degradedReasons=$degradedReasons.",
            )
            assertEquals(
                BreakerOpenReason.ConsecutiveRestFailures,
                degradedReasons.first(),
                "the callback MUST receive the typed BreakerOpenReason — " +
                    "ConsecutiveRestFailures for the 5xx burst trigger",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun mb24d_ws_active_poll_loop_429_with_Retry_After_consumes_typed_field_and_delays_exactly() = runTest(timeout = 5.minutes) {
        // Mirror of `mb24d_poll_loop_429_with_Retry_After_consumes_typed_field_and_delays_exactly`
        // for the parallel `wsActivePollLoop` — round-1 C5-C
        // review noted that the original M-B24(d) only covered
        // the legacy `pollLoop`. The parallel loop's 429 branch
        // was added in C5-A but never exercised end-to-end in a
        // test. This test pins the parallel branch's
        // `clampRetryAfterMs` consumption symmetrically.
        init()
        val pollCalls = mutableListOf<Int>()
        val transport = BreakerTestTransport(
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
                        rawBody = "{}", elapsedMs = 1L,
                    )
                }
            },
        )
        val orch = buildOrchestrator(transport, testScheduler, longPollEnabled = true)
        try {
            val caps = orch.bootstrap()
            check(caps.restFallback)
            // With longPollEnabled=true, the wsActivePollJob runs
            // independent of state-machine mode. Do NOT submit
            // events that would also spawn the legacy pollLoop
            // (we want to isolate the wsActivePollLoop's 429
            // branch).
            orch.start()
            runCurrent()
            advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS + 100L)
            runCurrent()
            val callsAfterFirst = pollCalls.size
            assertTrue(
                callsAfterFirst >= 1,
                "expected at least one poll call after start; got $callsAfterFirst",
            )
            // Advance past the legacy backoff (5_000 ms × 1.2
            // jitter ≤ 6_000) but BEFORE the consumed 30_000 ms
            // window expires. If the wsActivePollLoop ignored the
            // typed field, a second call would land here.
            advanceTimeBy(15_000L)
            runCurrent()
            assertEquals(
                callsAfterFirst,
                pollCalls.size,
                "the wsActivePollLoop MUST consume `retryAfterSeconds` and delay for " +
                    "30_000 ms; a poll at the 15 s mark proves it fell back to legacy backoff. " +
                    "Got pollCalls.size=${pollCalls.size}, expected $callsAfterFirst.",
            )
            // Cross the 30 s mark.
            advanceTimeBy(16_000L)
            runCurrent()
            assertTrue(
                pollCalls.size > callsAfterFirst,
                "after the Retry-After delay elapses, the wsActivePollLoop MUST resume polling. " +
                    "Got pollCalls.size=${pollCalls.size}, expected > $callsAfterFirst.",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    // ── C5 round-2 review-fix regression pins ───────────────────────────────

    @Test
    fun cf2_p11_late_non_probe_success_does_NOT_close_breaker_in_Open() = runTest(timeout = 5.minutes) {
        // Round-2 C5 review (P1.1): when the breaker is `Open`,
        // a late non-probe response (e.g. a normal pollLoop poll
        // that succeeded against a transient relay window before
        // the network broke) MUST NOT force-close the breaker.
        // Cooldown is owned by the breaker timer; a non-probe
        // success short-circuiting it would re-arm polling against
        // a likely-still-broken transport.
        //
        // Seed Open(ConsecutiveRestFailures, 5_000) and the
        // current cooldown via `setBreakerStateForTest`, then call
        // `recordRestSuccess(isProbe = false)` via a stand-in code
        // path: drive a 200 response through the production
        // pollLoop. The pollLoop's outcome handler will hit the
        // `recordRestSuccess(isProbe = isProbe)` site where
        // `isProbe = false` because the pollLoop did NOT claim the
        // probe permit (the state was Open at the gate, so the
        // decision was `Skip` — but we set state to Open AFTER
        // start runs the first iteration in Closed; we then seed
        // a 200 to ensure the late non-probe response lands).
        //
        // Pin: state stays Open after the late success.
        init()
        var pollReturns500 = true
        val transport = BreakerTestTransport(
            pollScript = { _ ->
                if (pollReturns500) {
                    RestFallbackResponse(
                        statusCode = 500, bodyParsed = null,
                        rawBody = "", elapsedMs = 1L,
                    )
                } else {
                    RestFallbackResponse(
                        statusCode = 200,
                        bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                        rawBody = "{}", elapsedMs = 1L,
                    )
                }
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
            // Pump until the breaker trips to Open via the 5xx
            // burst (5 consecutive failures).
            var observedOpen = false
            repeat(200) {
                if (observedOpen) return@repeat
                advanceTimeBy(500L)
                runCurrent()
                if (orch.peekBreakerStateForTest() is LongPollBreakerState.Open) {
                    observedOpen = true
                }
            }
            check(observedOpen) {
                "expected Open state to set up the late-response window; got ${orch.peekBreakerStateForTest()}"
            }
            val openBeforeLateSuccess = orch.peekBreakerStateForTest()
            check(openBeforeLateSuccess is LongPollBreakerState.Open) { "expected Open" }
            // Now flip the transport to 200 — the next pollLoop
            // iteration WILL be skipped by the gate (state Open),
            // but we need to drive a real 200 through
            // recordRestSuccess(isProbe = false). The easiest
            // deterministic way: directly invoke
            // recordRestSuccess via a non-probe code path. We use
            // the gate seam — but gate returns Skip on Open, so
            // recordRestSuccess is NOT called by the production
            // gate. The test scenario the bug describes requires
            // a poll RESPONSE in flight when the breaker tripped.
            // We simulate this by toggling the transport AND
            // setting the breaker state directly to Open via the
            // seam — then exercising the legacy `pollLoop` for
            // one iteration. The gate's Skip path returns
            // immediately without calling record*; we need a
            // direct entry.
            //
            // Use the production-equivalent record entry directly
            // via the seam: setBreakerStateForTest already proves
            // state isolation; here we call recordRestSuccess(isProbe = false)
            // via reflection-free seam (added below).
            orch.recordRestSuccessForTest(isProbe = false, isOkResponse = true)
            assertEquals(
                openBeforeLateSuccess,
                orch.peekBreakerStateForTest(),
                "a non-probe success arriving while the breaker is Open MUST NOT force-close. " +
                    "Got ${orch.peekBreakerStateForTest()}.",
            )
            // A probe owner's success SHOULD close — pin the
            // contrast so the gate semantic is observable.
            orch.setBreakerStateForTest(LongPollBreakerState.HalfOpen(probeInFlight = true))
            orch.recordRestSuccessForTest(isProbe = true, isOkResponse = true)
            assertEquals(
                LongPollBreakerState.Closed,
                orch.peekBreakerStateForTest(),
                "a probe-owner's success on HalfOpen(probeInFlight=true) MUST close the breaker. " +
                    "Got ${orch.peekBreakerStateForTest()}.",
            )
            // Suppress unused-variable warning on the toggle.
            pollReturns500 = false
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun cf2_p11_late_non_probe_failure_during_HalfOpen_does_NOT_reopen_with_doubled_cooldown() = runTest(timeout = 5.minutes) {
        // Round-2 C5 review (P1.1): a stale non-probe failure
        // arriving while the breaker is HalfOpen(probeInFlight=true)
        // MUST NOT be treated as a failed probe. The probe owner
        // is the load-bearing path that resolves HalfOpen → Open.
        // A non-probe failure short-circuiting that to "double
        // the cooldown" would extend the cooldown twice per real
        // failure event.
        init()
        val transport = BreakerTestTransport(pollScript = { _ ->
            RestFallbackResponse(statusCode = 200, bodyParsed = null, rawBody = "", elapsedMs = 1L)
        })
        val orch = buildOrchestrator(transport, testScheduler)
        try {
            val caps = orch.bootstrap()
            check(caps.restFallback)
            // Seed HalfOpen(probeInFlight=true) — as if some other
            // poll loop claimed the probe permit.
            orch.setBreakerStateForTest(LongPollBreakerState.HalfOpen(probeInFlight = true))
            val cooldownBefore = orch.peekBreakerCooldownMsForTest()
            // Call the non-probe failure path. Should be a no-op.
            orch.recordRestFailureForTest(isProbe = false)
            assertEquals(
                LongPollBreakerState.HalfOpen(probeInFlight = true),
                orch.peekBreakerStateForTest(),
                "non-probe failure during HalfOpen(probeInFlight=true) MUST NOT transition state",
            )
            assertEquals(
                cooldownBefore,
                orch.peekBreakerCooldownMsForTest(),
                "non-probe failure during HalfOpen MUST NOT double the cooldown — that is the probe owner's job",
            )
            // Probe owner failure DOES reopen with doubled cooldown.
            orch.recordRestFailureForTest(isProbe = true)
            val state = orch.peekBreakerStateForTest()
            assertTrue(
                state is LongPollBreakerState.Open,
                "probe-owner failure on HalfOpen(probeInFlight=true) MUST transition to Open. " +
                    "Got $state.",
            )
            val expectedDoubled = (cooldownBefore.toDouble() * RestFallbackOrchestrator.BREAKER_COOLDOWN_GROWTH_FACTOR)
                .toLong()
                .coerceAtMost(RestFallbackOrchestrator.BREAKER_COOLDOWN_CEILING_MS)
            assertEquals(
                expectedDoubled,
                state.cooldownMs,
                "probe-owner failure on HalfOpen MUST double the cooldown verbatim",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun cf2_p12_stop_after_buffered_5xx_response_does_not_leak_breaker_timer() = runTest(timeout = 5.minutes) {
        // Round-2 C5 review (P1.2): a cancelled pollLoop that had
        // already received a 5xx response before the cancel signal
        // arrived can call `recordRestFailure(isProbe)` during its
        // unwinding. If the failure trips Closed → Open, a new
        // breaker timer is spawned. Under the previous two-phase
        // teardown the timer field was snapshot AT THE SAME TIME
        // as the poll producers, so the late-spawned timer was an
        // orphan that outlived `stop()`.
        //
        // Round-2 fix: three-phase teardown. Phase 2 fully unwinds
        // the poll producers before Phase 3 reads the timer field
        // — by Phase 3 no spawn path can fire, so the snapshot is
        // the FINAL value.
        //
        // This test cannot use a synchronous transport because we
        // need the poll producer to be mid-iteration (specifically
        // post-recordRestFailure, mid-delay) when stop arrives. We
        // use a sync 5xx transport: the loop iterates, gets 5xx,
        // calls recordRestFailure (which on the 5th iteration
        // trips to Open and spawns a timer), then enters
        // `delay(jittered)`. While in delay, we call stop().
        //
        // Assertions after stop:
        //   * No orphan timer: the breaker state's [_breakerTimerJob]
        //     field is null (seam available via a helper).
        //   * No spurious Open → HalfOpen transition fires after
        //     stop: we advance virtual time well past any possible
        //     cooldown and assert state stays Open (the post-stop
        //     state machine is frozen).
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
            // Pump until the breaker is Open (timer Job spawned).
            var observedOpen = false
            repeat(200) {
                if (observedOpen) return@repeat
                advanceTimeBy(500L)
                runCurrent()
                if (orch.peekBreakerStateForTest() is LongPollBreakerState.Open) {
                    observedOpen = true
                }
            }
            check(observedOpen) {
                "expected Open before stop; got ${orch.peekBreakerStateForTest()}. " +
                    "pollCalls=${transport.pollCalls.size}."
            }
            val openBeforeStop = orch.peekBreakerStateForTest()
            check(openBeforeStop is LongPollBreakerState.Open)
            // Confirm the timer field is non-null at this moment.
            assertTrue(
                orch.peekHasBreakerTimerForTest(),
                "expected _breakerTimerJob to be non-null on Open before stop",
            )
            // Call stop — three-phase teardown should fully drain
            // the poll producers BEFORE snapshotting the timer
            // field, so any late timer spawned during unwinding is
            // captured AND cancelled.
            orch.stop()
            runCurrent()
            // Post-stop the timer field MUST be null.
            assertEquals(
                false,
                orch.peekHasBreakerTimerForTest(),
                "after stop, _breakerTimerJob MUST be null — no orphan timer survived. " +
                    "If non-null, the late spawn during poll-loop unwinding wasn't captured by Phase 3.",
            )
            // Advance virtual time past any possible cooldown
            // ceiling; state MUST stay Open (no spurious
            // Open → HalfOpen from a stranded timer).
            advanceTimeBy(RestFallbackOrchestrator.BREAKER_COOLDOWN_CEILING_MS + 5_000L)
            runCurrent()
            assertTrue(
                orch.peekBreakerStateForTest() is LongPollBreakerState.Open,
                "no stranded timer Job MUST fire after stop. Got ${orch.peekBreakerStateForTest()}.",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    // ── C5 round-3 review-fix regression pins ───────────────────────────────

    @Test
    fun cf3_p1_late_non_probe_success_in_Open_does_not_clear_storm_timestamps() = runTest(timeout = 5.minutes) {
        // Round-3 C5 review (P1): round-2 gated state transitions
        // on probe ownership but still cleared
        // `_status410StormTimestamps` and reset
        // `_current410BackoffMs` unconditionally — so a stale
        // non-probe success in Open still wiped the 410 history.
        // Round-3 moves every mutation INSIDE the authoritative
        // branch; stale non-probe responses in Open / HalfOpen
        // are full no-ops.
        init()
        val transport = BreakerTestTransport(pollScript = { _ ->
            RestFallbackResponse(statusCode = 200, bodyParsed = null, rawBody = "", elapsedMs = 1L)
        })
        val orch = buildOrchestrator(transport, testScheduler)
        try {
            val caps = orch.bootstrap()
            check(caps.restFallback)
            // Seed Open with non-empty storm timestamp list AND a
            // non-floor 410 backoff value. The setBreakerStateForTest
            // seam sets state directly; we use authoritative
            // recordRest paths to populate the 410 bookkeeping
            // before flipping to Open. Hmm — under round-3 each
            // 410 (handle410) bumps timestamps in any state. Easier
            // path: drive the bookkeeping AND set state via seams
            // only. We use the Closed → handle410 path: 1 × 410
            // bumps timestamps + backoff once, then flip Open.
            // Drive ONE 410 via the production pollLoop:
            transport.pollScript = { _ ->
                RestFallbackResponse(statusCode = 410, bodyParsed = null, rawBody = "", elapsedMs = 1L)
            }
            repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
                orch.submitEvent(
                    RestStateMachine.Event.WsSessionEnded(
                        durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    ),
                )
            }
            orch.start()
            runCurrent()
            // Pump until at least one 410 was processed (storm
            // timestamps populated, backoff bumped to 10_000).
            repeat(20) {
                if (transport.pollCalls.size >= 1) return@repeat
                advanceTimeBy(500L)
                runCurrent()
            }
            check(transport.pollCalls.size >= 1) { "expected ≥ 1 poll call" }
            val timestampsBefore = orch.peekStatus410StormTimestampsForTest()
            val backoffBefore = orch.peekCurrent410BackoffMsForTest()
            assertTrue(
                timestampsBefore.isNotEmpty(),
                "expected at least 1 storm timestamp after one 410; got $timestampsBefore",
            )
            assertTrue(
                backoffBefore > RestFallbackOrchestrator.BREAKER_INITIAL_COOLDOWN_MS,
                "expected backoff doubled past the 5 s floor after one 410; got $backoffBefore",
            )
            // Now flip breaker to Open via seam (round-3: stale
            // non-probe success in Open must NOT touch timestamps
            // or backoff).
            orch.setBreakerStateForTest(
                LongPollBreakerState.Open(
                    BreakerOpenReason.ConsecutiveRestFailures,
                    RestFallbackOrchestrator.BREAKER_INITIAL_COOLDOWN_MS,
                ),
            )
            // Call recordRestSuccessForTest(isProbe=false,
            // isOkResponse=true) — represents a stale non-probe
            // 200 response arriving after the breaker tripped.
            orch.recordRestSuccessForTest(isProbe = false, isOkResponse = true)
            // ASSERTIONS — all preserved.
            assertEquals(
                timestampsBefore,
                orch.peekStatus410StormTimestampsForTest(),
                "stale non-probe success in Open MUST NOT clear storm timestamps",
            )
            assertEquals(
                backoffBefore,
                orch.peekCurrent410BackoffMsForTest(),
                "stale non-probe success in Open MUST NOT reset the 410 backoff",
            )
            assertTrue(
                orch.peekBreakerStateForTest() is LongPollBreakerState.Open,
                "stale non-probe success in Open MUST NOT close the breaker",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun cf3_p1_non_OK_success_in_Closed_does_not_reset_410_backoff() = runTest(timeout = 5.minutes) {
        // Round-3 C5 review (P1): scope §L8 pins "The backoff
        // recovers to the 5 s floor after a successful (200-OK)
        // poll." Round-2 reset the floor on any non-failure
        // response (401, 429, 4xx-other) too. Round-3 gates the
        // reset on `isOkResponse = true`.
        //
        // This test seeds the 410 backoff at a non-floor value,
        // calls recordRestSuccessForTest with isOkResponse = false
        // in Closed state, and asserts the backoff is unchanged.
        // Then calls with isOkResponse = true and asserts the
        // backoff resets to the 5 s floor.
        init()
        val transport = BreakerTestTransport(pollScript = { _ ->
            RestFallbackResponse(statusCode = 410, bodyParsed = null, rawBody = "", elapsedMs = 1L)
        })
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
            // Pump one 410 to bump the backoff to 10_000.
            repeat(20) {
                if (transport.pollCalls.size >= 1) return@repeat
                advanceTimeBy(500L)
                runCurrent()
            }
            val backoffAfter410 = orch.peekCurrent410BackoffMsForTest()
            check(backoffAfter410 > RestFallbackOrchestrator.BREAKER_INITIAL_COOLDOWN_MS) {
                "expected backoff doubled past floor; got $backoffAfter410"
            }
            // Closed state is the production loop's normal state
            // here. recordRestSuccessForTest(isProbe=false,
            // isOkResponse=false) represents a 401/429/4xx-other
            // response.
            check(orch.peekBreakerStateForTest() is LongPollBreakerState.Closed) {
                "expected Closed state for the test pin; got ${orch.peekBreakerStateForTest()}"
            }
            orch.recordRestSuccessForTest(isProbe = false, isOkResponse = false)
            assertEquals(
                backoffAfter410,
                orch.peekCurrent410BackoffMsForTest(),
                "non-200 success (401/429/4xx-other) MUST NOT reset the 410 backoff per scope §L8",
            )
            // Now the 200 OK case — backoff should reset.
            orch.recordRestSuccessForTest(isProbe = false, isOkResponse = true)
            assertEquals(
                RestFallbackOrchestrator.BREAKER_INITIAL_COOLDOWN_MS,
                orch.peekCurrent410BackoffMsForTest(),
                "200 OK in Closed state MUST reset the 410 backoff to the 5 s floor per scope §L8",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun cf3_p1_late_non_probe_failure_in_Open_does_not_clear_storm_timestamps() = runTest(timeout = 5.minutes) {
        // Round-3 C5 review (P1): symmetric to the success case
        // above — round-2 cleared storm timestamps from
        // `recordRestFailure` unconditionally too. A stale
        // non-probe 5xx in Open MUST NOT erase the 410 history.
        init()
        val transport = BreakerTestTransport(pollScript = { _ ->
            RestFallbackResponse(statusCode = 410, bodyParsed = null, rawBody = "", elapsedMs = 1L)
        })
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
            // Pump one 410 to populate timestamps.
            repeat(20) {
                if (transport.pollCalls.size >= 1) return@repeat
                advanceTimeBy(500L)
                runCurrent()
            }
            val timestampsBefore = orch.peekStatus410StormTimestampsForTest()
            val backoffBefore = orch.peekCurrent410BackoffMsForTest()
            assertTrue(timestampsBefore.isNotEmpty())
            // Flip state to Open via seam.
            orch.setBreakerStateForTest(
                LongPollBreakerState.Open(
                    BreakerOpenReason.ConsecutiveRestFailures,
                    RestFallbackOrchestrator.BREAKER_INITIAL_COOLDOWN_MS,
                ),
            )
            // Stale non-probe failure.
            orch.recordRestFailureForTest(isProbe = false)
            assertEquals(
                timestampsBefore,
                orch.peekStatus410StormTimestampsForTest(),
                "stale non-probe failure in Open MUST NOT clear storm timestamps",
            )
            assertEquals(
                backoffBefore,
                orch.peekCurrent410BackoffMsForTest(),
                "stale non-probe failure in Open MUST NOT touch the 410 backoff",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun cf3_p2_timer_spawn_during_poll_producer_unwind_is_captured_by_phase3() = runTest(timeout = 5.minutes) {
        // Round-3 C5 review (P2): the round-2 timer-race test
        // started stop AFTER the timer was already alive — both
        // the old two-phase AND the new three-phase teardown
        // captured that case. The genuine race: a poll producer
        // is suspended in transport.poll WHEN stop arrives;
        // cancelAndJoinAll Phase 2 snapshot captures pollJob but
        // NO timer (none spawned yet); after the snapshot the
        // poll producer is released and processes its 5xx
        // response, calling recordRestFailure which trips Open and
        // spawns the timer DURING UNWIND; the new timer was
        // missed by the old two-phase snapshot but captured by
        // the round-2 three-phase teardown's Phase 3 re-read.
        //
        // Repro is deterministic via a transport that suspends
        // inside `withContext(NonCancellable)` so the cancel
        // signal can't propagate. We then release the gate
        // AFTER Phase 2 cancel has been sent but BEFORE join
        // returns. The poll producer processes the 5xx, trips
        // Open, spawns the timer, then delay throws CE on the
        // pending cancellation.
        init()
        val pollEnteredGate = CompletableDeferred<Unit>()
        val gateRelease = CompletableDeferred<Unit>()
        val transport = BreakerTestTransport(pollScript = { i ->
            if (i == 0) {
                pollEnteredGate.complete(Unit)
                // NonCancellable so the cancel signal doesn't
                // throw out of the await; we control the
                // resumption via gateRelease.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    gateRelease.await()
                }
            }
            // Always 500 — the 5th consecutive 5xx will trip the
            // breaker.
            RestFallbackResponse(
                statusCode = 500, bodyParsed = null,
                rawBody = "", elapsedMs = 1L,
            )
        })
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
            // Wait until the poll producer is suspended inside
            // the NonCancellable gate.
            repeat(20) {
                if (pollEnteredGate.isCompleted) return@repeat
                advanceTimeBy(500L)
                runCurrent()
            }
            check(pollEnteredGate.isCompleted) {
                "expected pollLoop to have entered transport.poll's NonCancellable gate"
            }
            // Seed the fail counter to one less than the
            // threshold so the FIRST 5xx response after the gate
            // releases trips the breaker.
            repeat(RestFallbackOrchestrator.BREAKER_CONSECUTIVE_FAIL_THRESHOLD - 1) {
                orch.recordRestFailureForTest(isProbe = false)
            }
            // Sanity: no timer yet, state Closed.
            check(!orch.peekHasBreakerTimerForTest()) {
                "expected no timer alive yet; got ${orch.peekHasBreakerTimerForTest()}"
            }
            check(orch.peekBreakerStateForTest() is LongPollBreakerState.Closed) {
                "expected Closed state pre-trip; got ${orch.peekBreakerStateForTest()}"
            }
            // Launch stop in a coroutine — it'll block on the
            // pollJob join (Phase 2). Snapshot has been taken at
            // a point with NO timer.
            val stopDone = CompletableDeferred<Unit>()
            launch {
                orch.stop()
                stopDone.complete(Unit)
            }
            runCurrent()
            // stop is now waiting on pollJob.join. Release the
            // gate so the poll producer processes the 5xx,
            // increments to threshold, trips Open, spawns timer.
            gateRelease.complete(Unit)
            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()
            // Drain remaining virtual time so stop fully returns.
            advanceTimeBy(10_000L)
            runCurrent()
            check(stopDone.isCompleted) {
                "expected stop to have completed within 10 s virtual time"
            }
            // Round-3 assertion: Phase 3 re-read captured the
            // timer spawned during unwind; the field is now null.
            assertEquals(
                false,
                orch.peekHasBreakerTimerForTest(),
                "after stop, _breakerTimerJob MUST be null — the timer spawned during the " +
                    "poll producer's unwind MUST be captured by Phase 3's re-read of the field. " +
                    "If non-null, the round-2 three-phase teardown is not actually catching this race.",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
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
        var pollScript: suspend (callIndex: Int) -> RestFallbackResponse<PollResponse>,
        var sessionScript: ((callIndex: Int) -> RestFallbackResponse<AuthSessionResponse>)? = null,
        var ackScript: ((callIndex: Int, envelopeId: String) -> RestFallbackResponse<AckDeliverResponse>)? = null,
    ) : RestFallbackTransport {
        val pollCalls: MutableList<Long?> = mutableListOf()
        val authCalls: MutableList<Unit> = mutableListOf()
        val ackCalls: MutableList<String> = mutableListOf()
        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> {
            authCalls += Unit
            return sessionScript?.invoke(authCalls.size - 1) ?: RestFallbackResponse(
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
        ): RestFallbackResponse<AckDeliverResponse> {
            ackCalls += body.id
            return ackScript?.invoke(ackCalls.size - 1, body.id) ?: RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AckDeliverResponse(ok = 1),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        }
    }

    private fun buildOrchestrator(
        transport: BreakerTestTransport,
        scheduler: TestCoroutineScheduler,
        longPollEnabled: Boolean = false,
        cursor: LongPollCursorRepository = NoopCursor(),
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
        cursorRepository = cursor,
        dispatcher = StandardTestDispatcher(scheduler),
    )

    private class RecordingCursorRepo : LongPollCursorRepository {
        val writes: MutableList<Pair<Long, Long>> = mutableListOf()
        var stored: Long? = null
        override suspend fun getLastSeenSeq(identityHex: String): Long? = stored
        override suspend fun upsertLastSeenSeq(identityHex: String, seq: Long, nowMs: Long) {
            writes += seq to nowMs
            stored = maxOf(stored ?: Long.MIN_VALUE, seq)
        }
    }

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
