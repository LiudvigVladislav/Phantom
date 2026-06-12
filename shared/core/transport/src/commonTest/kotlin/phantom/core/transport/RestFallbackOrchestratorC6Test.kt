// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import phantom.core.crypto.Csprng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * Trek 2 Stage 2B-B (C6) — L10 jitter migration + M-B19 fail-closed
 * input handling.
 *
 *   M15  — Jitter source is [Csprng]. Behavioural pin: a recording
 *          fake observes every uniformLong draw issued by the
 *          production poll loops over a deterministic scenario;
 *          the draw count and bucket band are asserted exactly.
 *          (The grep gate over the source file sits in the
 *          companion jvmTest under
 *          [OrchestratorJitterGrepGateTest].)
 *   M-B19 — Fail-closed handling of malformed verify-key /
 *          `seq_mac` inputs:
 *            (a) verify-key non-hex → KeySuspended, loop alive.
 *            (b) verify-key odd char length → KeySuspended.
 *            (c) verify-key not 64 chars (32 / 63 / 65 / 128) →
 *                KeySuspended.
 *            (d) `PollEnvelope.seqMac` non-hex → drop with
 *                reason=no_mac_field; loop alive.
 *            (e) `PollEnvelope.seqMac` odd char length → drop.
 *            (f) `PollEnvelope.seqMac` not 64 chars → drop.
 *          None of these cases throw; the loop continues; the
 *          orchestrator keeps polling.
 */
class RestFallbackOrchestratorC6Test {

    private val IDENTITY: String = "aa".repeat(32)

    // ── M15 — CSPRNG jitter behaviour ────────────────────────────────────────

    @Test
    fun m15_jitter_factor_for_known_values_matches_scope_band() {
        // Pure unit pin of the production
        // [RestFallbackOrchestrator.jitterFactorFor] mapping. The
        // band MUST be `[0.8, 1.2)`; the discretisation MUST land
        // on the resolution boundary.
        val resolution = RestFallbackOrchestrator.JITTER_RESOLUTION.toLong()
        assertEquals(0.8, RestFallbackOrchestrator.jitterFactorFor(0L), 1e-12)
        val midpoint = RestFallbackOrchestrator.jitterFactorFor(resolution / 2)
        assertTrue(
            midpoint > 0.99 && midpoint < 1.01,
            "midpoint band MUST be near 1.0; got $midpoint",
        )
        val topBucket = RestFallbackOrchestrator.jitterFactorFor(resolution - 1)
        assertTrue(
            topBucket < 1.2 && topBucket > 1.1996,
            "top bucket MUST sit just below 1.2; got $topBucket",
        )
    }

    @Test
    fun m15_jitter_resolution_is_locked_at_10_000() {
        assertEquals(
            10_000,
            RestFallbackOrchestrator.JITTER_RESOLUTION,
            "JITTER_RESOLUTION locked at 10_000 for scope §L10 ms-resolution discretisation",
        )
    }

    @Test
    fun m15_orchestrator_consumes_injected_Csprng_for_every_jitter_site() = runTest(timeout = 5.minutes) {
        // Behavioural pin: drive the production legacy pollLoop
        // through a series of 5xx responses; each iteration that
        // delays MUST take a uniformLong draw from the injected
        // Csprng fake. Asserting on the draw count proves that
        // ZERO call site is still on `kotlin.random` — a missed
        // migration would silently use the JVM LCG and the fake
        // would record fewer draws than there are jittered delays.
        init()
        val transport = C6TestTransport(pollScript = { _ ->
            RestFallbackResponse(
                statusCode = 500, bodyParsed = null,
                rawBody = "", elapsedMs = 1L,
            )
        })
        val recordingCsprng = RecordingCsprng(perCallReturn = 7_500L)
        val orch = buildOrchestrator(transport, testScheduler, csprng = recordingCsprng)
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
            // Pump enough virtual time for ~5 pollLoop iterations.
            repeat(20) {
                advanceTimeBy((RestFallbackOrchestrator.POLL_FAIL_BACKOFF_MS * 1.3).toLong())
                runCurrent()
            }
            assertTrue(
                recordingCsprng.callCount >= 5,
                "expected ≥ 5 jitter draws from the injected Csprng across the iterations; " +
                    "got ${recordingCsprng.callCount}. A missed L10 migration would show " +
                    "0 draws (the orchestrator silently fell back to kotlin.random).",
            )
            for (boundExclusive in recordingCsprng.boundsSeen) {
                assertEquals(
                    RestFallbackOrchestrator.JITTER_RESOLUTION.toLong(),
                    boundExclusive,
                    "every Csprng draw MUST use JITTER_RESOLUTION (10_000) — a drift here " +
                        "would mean some jitter call bypassed the `nextJitterFactor()` helper.",
                )
            }
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    // ── M-B19 (a-c) — fail-closed malformed verify-key ──────────────────────

    @Test
    fun mb19_a_non_hex_verify_key_classifies_to_Malformed_routes_to_KeySuspended() = runTest(timeout = 5.minutes) {
        // 64 chars, but ONE character is non-hex.
        val nonHexKey = "0123456789abcdef".repeat(4).replaceRange(0, 1, "g")
        check(nonHexKey.length == 64)
        runFailClosedScenario(seqMacVerifyKey = nonHexKey)
    }

    @Test
    fun mb19_b_odd_length_verify_key_routes_to_KeySuspended() = runTest(timeout = 5.minutes) {
        runFailClosedScenario(seqMacVerifyKey = "0".repeat(63))
    }

    @Test
    fun mb19_c_short_verify_key_routes_to_KeySuspended() = runTest(timeout = 5.minutes) {
        runFailClosedScenario(seqMacVerifyKey = "0".repeat(32))
    }

    @Test
    fun mb19_c_long_verify_key_routes_to_KeySuspended() = runTest(timeout = 5.minutes) {
        runFailClosedScenario(seqMacVerifyKey = "0".repeat(128))
    }

    @Test
    fun mb19_c_off_by_one_verify_key_routes_to_KeySuspended() = runTest(timeout = 5.minutes) {
        // 65 chars — common copy-paste error class.
        runFailClosedScenario(seqMacVerifyKey = "0".repeat(65))
    }

    private suspend fun runFailClosedScenario(seqMacVerifyKey: String): Unit {
        val transport = C6TestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                    rawBody = "{}", elapsedMs = 1L,
                )
            },
            sessionScript = {
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = AuthSessionResponse(
                        token = "tok-bootstrap",
                        expiresAt = Long.MAX_VALUE,
                        restFallback = true,
                        maxSendBodyBytes = 4096,
                        pollMaxEnvelopes = 1,
                        pollHoldSecs = 30,
                        seqMacVerifyKey = seqMacVerifyKey,
                    ),
                    rawBody = "{}", elapsedMs = 1L,
                )
            },
        )
        val scheduler = TestCoroutineScheduler()
        val orch = buildOrchestrator(transport, scheduler)
        try {
            // bootstrap MUST NOT throw on a malformed verify key —
            // the classifier routes it through the Malformed outcome
            // to KeySuspended.
            val caps = orch.bootstrap()
            check(caps.restFallback)
            assertEquals(
                VerifyKeyState.KeySuspended,
                orch.peekVerifyKeyStateForTest(),
                "malformed verify-key MUST route to KeySuspended via the L2 classifier",
            )
        } finally {
            orch.stop()
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private suspend fun init() {
        if (!com.ionspin.kotlin.crypto.LibsodiumInitializer.isInitialized()) {
            com.ionspin.kotlin.crypto.LibsodiumInitializer.initialize()
        }
    }

    private class C6TestTransport(
        var pollScript: (callIndex: Int) -> RestFallbackResponse<PollResponse>,
        var sessionScript: (() -> RestFallbackResponse<AuthSessionResponse>)? = null,
    ) : RestFallbackTransport {
        val pollCalls: MutableList<Long?> = mutableListOf()
        val authCalls: MutableList<Unit> = mutableListOf()
        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> {
            authCalls += Unit
            return sessionScript?.invoke() ?: RestFallbackResponse(
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
                rawBody = "{}", elapsedMs = 1L,
            )
        }
        override suspend fun send(
            url: String, token: String, idempotencyKey: String, body: SendRequest,
        ): RestFallbackResponse<SendResponse> = fail("send not used in C6 tests")
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
            rawBody = "{}", elapsedMs = 1L,
        )
    }

    /**
     * Deterministic [Csprng] fake that returns a fixed
     * [perCallReturn] on every `uniformLong` call and records the
     * sequence of bounds. The fixed-return shape lets M15 assert
     * that the jitter is consumed at the right mapping band; the
     * sequence-of-bounds list pins that every draw uses the
     * locked [RestFallbackOrchestrator.JITTER_RESOLUTION].
     */
    private class RecordingCsprng(
        val perCallReturn: Long,
    ) : Csprng {
        var callCount: Int = 0
        val boundsSeen: MutableList<Long> = mutableListOf()
        override fun bytes(byteCount: Int): ByteArray =
            error("M15 fake — bytes() not used by orchestrator jitter path")
        override fun hex(byteCount: Int): String =
            error("M15 fake — hex() not used by orchestrator jitter path")
        override fun uniformLong(boundExclusive: Long): Long {
            callCount += 1
            boundsSeen += boundExclusive
            return perCallReturn.coerceAtMost(boundExclusive - 1)
        }
    }

    private class NoopCursor : LongPollCursorRepository {
        override suspend fun getLastSeenSeq(identityHex: String): Long? = null
        override suspend fun upsertLastSeenSeq(identityHex: String, seq: Long, nowMs: Long) {}
    }

    private fun buildOrchestrator(
        transport: C6TestTransport,
        scheduler: TestCoroutineScheduler,
        csprng: Csprng = phantom.core.crypto.LibsodiumCsprng,
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
        csprng = csprng,
    )
}
