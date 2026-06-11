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

    // ── Fixtures ────────────────────────────────────────────────────────────

    private val IDENTITY: String = "aa".repeat(32)

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
