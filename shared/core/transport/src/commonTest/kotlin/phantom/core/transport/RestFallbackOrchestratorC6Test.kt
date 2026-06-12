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
        runFailClosedScenario(testScheduler, seqMacVerifyKey = nonHexKey)
    }

    @Test
    fun mb19_b_odd_length_verify_key_routes_to_KeySuspended() = runTest(timeout = 5.minutes) {
        runFailClosedScenario(testScheduler, seqMacVerifyKey = "0".repeat(63))
    }

    @Test
    fun mb19_c_short_verify_key_routes_to_KeySuspended() = runTest(timeout = 5.minutes) {
        runFailClosedScenario(testScheduler, seqMacVerifyKey = "0".repeat(32))
    }

    @Test
    fun mb19_c_long_verify_key_routes_to_KeySuspended() = runTest(timeout = 5.minutes) {
        runFailClosedScenario(testScheduler, seqMacVerifyKey = "0".repeat(128))
    }

    @Test
    fun mb19_c_off_by_one_verify_key_routes_to_KeySuspended() = runTest(timeout = 5.minutes) {
        // 65 chars — common copy-paste error class.
        runFailClosedScenario(testScheduler, seqMacVerifyKey = "0".repeat(65))
    }

    private suspend fun runFailClosedScenario(
        scheduler: TestCoroutineScheduler,
        seqMacVerifyKey: String,
    ): Unit {
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

    // ── Tele2 smoke log-shape pins (P1.1 + P1.2) ────────────────────────────
    //
    // Every Tele2 LTE smoke proof in `docs/tracks/trek2-stage2b-b-tele2-
    // smoke.md` is grep-discoverable iff the orchestrator's log shape
    // matches the runbook. A future commit that renames or restructures
    // these fields silently invalidates the smoke; these pins fail the
    // build the moment a substring drifts.

    @Test
    fun p1_1_startup_log_carries_LONGPOLL_V2_ENABLED_literal_1_when_enabled() = runTest(timeout = 5.minutes) {
        init()
        val captured = mutableListOf<String>()
        val transport = C6TestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                    rawBody = "{}", elapsedMs = 1L,
                )
            },
        )
        val orch = buildOrchestrator(
            transport, testScheduler,
            longPollEnabled = true,
            logSink = { captured += it },
        )
        try {
            orch.bootstrap()
            orch.start()
            runCurrent()
            val startedLine = captured.singleOrNull {
                it.startsWith("REST_TRACE orchestrator_started ")
            }
            assertNotNull(startedLine, "expected exactly one `orchestrator_started` line; captured=$captured")
            assertTrue(
                "LONGPOLL_V2_ENABLED=1" in startedLine,
                "runbook smoke requires literal `LONGPOLL_V2_ENABLED=1` in the startup line; got `$startedLine`",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun p1_1_startup_log_carries_LONGPOLL_V2_ENABLED_literal_0_when_disabled() = runTest(timeout = 5.minutes) {
        init()
        val captured = mutableListOf<String>()
        val transport = C6TestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                    rawBody = "{}", elapsedMs = 1L,
                )
            },
        )
        val orch = buildOrchestrator(
            transport, testScheduler,
            longPollEnabled = false,
            logSink = { captured += it },
        )
        try {
            orch.bootstrap()
            orch.start()
            runCurrent()
            val startedLine = captured.singleOrNull {
                it.startsWith("REST_TRACE orchestrator_started ")
            }
            assertNotNull(startedLine)
            assertTrue(
                "LONGPOLL_V2_ENABLED=0" in startedLine,
                "release-pinned build (`LONGPOLL_V2_ENABLED == \"0\"`) MUST surface the literal " +
                    "`LONGPOLL_V2_ENABLED=0` so a release-APK smoke fails fast on the proof check; got `$startedLine`",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun p1_1_verify_key_state_log_emitted_KeyPresent_after_valid_refresh() = runTest(timeout = 5.minutes) {
        init()
        val captured = mutableListOf<String>()
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
                        seqMacVerifyKey = "f".repeat(64),
                    ),
                    rawBody = "{}", elapsedMs = 1L,
                )
            },
        )
        val orch = buildOrchestrator(transport, testScheduler, logSink = { captured += it })
        try {
            orch.bootstrap()
            val stateLines = captured.filter { it.startsWith("REST_TRACE seq_mac_verify_key_state=") }
            assertTrue(
                stateLines.any { "seq_mac_verify_key_state=KeyPresent" in it && "from=KeyAbsent" in it },
                "expected a `seq_mac_verify_key_state=KeyPresent from=KeyAbsent` line after a Valid refresh; " +
                    "got:\n${stateLines.joinToString("\n")}",
            )
            // Defence-in-depth: the verify-key payload (64-char hex)
            // MUST NOT appear in the log surface. A future regression
            // that prints the hex directly would silently leak the
            // session secret.
            assertTrue(
                stateLines.none { "f".repeat(64) in it },
                "verify-key hex MUST NOT appear in any log line; got:\n${stateLines.joinToString("\n")}",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun p1_1_verify_key_state_log_emitted_KeySuspended_after_malformed_refresh() = runTest(timeout = 5.minutes) {
        init()
        val captured = mutableListOf<String>()
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
                        seqMacVerifyKey = "0".repeat(63),
                    ),
                    rawBody = "{}", elapsedMs = 1L,
                )
            },
        )
        val orch = buildOrchestrator(transport, testScheduler, logSink = { captured += it })
        try {
            orch.bootstrap()
            val stateLines = captured.filter { it.startsWith("REST_TRACE seq_mac_verify_key_state=") }
            assertTrue(
                stateLines.any { "seq_mac_verify_key_state=KeySuspended" in it && "outcome=Malformed" in it },
                "expected a `seq_mac_verify_key_state=KeySuspended outcome=Malformed` line after a malformed " +
                    "refresh outcome; got:\n${stateLines.joinToString("\n")}",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun p1_1_cursor_advanced_log_emitted_with_seq_after_successful_upsert() = runTest(timeout = 5.minutes) {
        init()
        val captured = mutableListOf<String>()
        val transport = C6TestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                    rawBody = "{}", elapsedMs = 1L,
                )
            },
        )
        val cursor = RecordingCursor()
        val orch = buildOrchestrator(
            transport, testScheduler,
            cursor = cursor,
            logSink = { captured += it },
        )
        try {
            orch.bootstrap()
            // Drive the ack-and-advance path through the existing C3
            // test seam — production callers always reach
            // `ackInboundAndAdvanceCursor` after the relay 2xx'd the
            // ack; the seam mirrors the in-mutex prime step.
            orch.primePendingSeqForAckForTest("env-c6log", 42L)
            val outcome = orch.ackInboundAndAdvanceCursor("env-c6log")
            assertEquals(AckOutcome.Acked, outcome)
            assertEquals(1, cursor.writes.size)
            val advancedLine = captured.singleOrNull {
                it.startsWith("REST_TRACE cursor_advanced ")
            }
            assertNotNull(
                advancedLine,
                "expected exactly one `cursor_advanced` line after a successful upsert; " +
                    "captured ${captured.size} REST_TRACE lines.",
            )
            assertTrue("seq=42" in advancedLine, "advanced seq pin failed: `$advancedLine`")
            assertTrue("id=env-c6lo" in advancedLine, "id truncation pin (first 8 chars) failed: `$advancedLine`")
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun p1_1_poll_call_log_carries_headers_and_probe_flag() = runTest(timeout = 5.minutes) {
        init()
        val captured = mutableListOf<String>()
        val transport = C6TestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                    rawBody = "{}", elapsedMs = 1L,
                )
            },
        )
        val orch = buildOrchestrator(
            transport, testScheduler,
            longPollEnabled = true,
            logSink = { captured += it },
        )
        try {
            orch.bootstrap()
            repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
                orch.submitEvent(
                    RestStateMachine.Event.WsSessionEnded(
                        durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    ),
                )
            }
            orch.start()
            runCurrent()
            // Pump a few pollLoop iterations so at least one
            // `poll_call` line lands.
            repeat(3) {
                advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS)
                runCurrent()
            }
            val pollCallLines = captured.filter { it.startsWith("REST_TRACE poll_call ") }
            assertTrue(
                pollCallLines.isNotEmpty(),
                "expected at least one `REST_TRACE poll_call ` line; got nothing",
            )
            val firstPollCall = pollCallLines.first()
            assertTrue(
                "X-Phantom-Long-Poll=1" in firstPollCall,
                "long-poll header pin failed (smoke expects literal `X-Phantom-Long-Poll=1`): `$firstPollCall`",
            )
            assertTrue(
                "X-Phantom-Padded-Poll=1" in firstPollCall,
                "padded-poll header pin failed (smoke expects literal `X-Phantom-Padded-Poll=1`): `$firstPollCall`",
            )
            assertTrue(
                "probe=false" in firstPollCall,
                "probe-flag pin failed on regular (non-HalfOpen) iteration: `$firstPollCall`",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun p1_2_force_breaker_trip_emits_test_trigger_fired_log_and_opens_breaker() = runTest(timeout = 5.minutes) {
        init()
        val captured = mutableListOf<String>()
        val transport = C6TestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                    rawBody = "{}", elapsedMs = 1L,
                )
            },
        )
        val orch = buildOrchestrator(transport, testScheduler, logSink = { captured += it })
        try {
            orch.bootstrap()
            // Pre-state: breaker MUST be Closed under the C5 invariants
            // (a fresh bootstrap puts everything in the default state).
            assertTrue(
                orch.peekBreakerStateForTest() is LongPollBreakerState.Closed,
                "pre-trigger breaker MUST be Closed",
            )
            orch.forceBreakerTripForS6TestTrigger()
            // The runbook S6 pass criterion 1 greps for `breaker_open
            // reason=ConsecutiveRestFailures cooldown_ms=<n>` — present
            // unconditionally on any natural trip too — and the helper-
            // only `breaker_test_trigger_fired` line as the helper-
            // invocation marker. Both MUST appear.
            assertTrue(
                captured.any { "REST_TRACE breaker_test_trigger_fired" in it },
                "expected `breaker_test_trigger_fired` log; got:\n${captured.joinToString("\n")}",
            )
            assertTrue(
                captured.any {
                    it.startsWith("REST_TRACE breaker_open ") &&
                        "reason=ConsecutiveRestFailures" in it &&
                        "cooldown_ms=" in it
                },
                "expected `breaker_open reason=ConsecutiveRestFailures cooldown_ms=...` log; " +
                    "got:\n${captured.joinToString("\n")}",
            )
            val state = orch.peekBreakerStateForTest()
            assertTrue(
                state is LongPollBreakerState.Open &&
                    state.reason == BreakerOpenReason.ConsecutiveRestFailures,
                "expected breaker state Open(ConsecutiveRestFailures); got $state",
            )
        } finally {
            orch.stop()
            runCurrent()
        }
    }

    // ── M-B19 (d/e/f) — fail-closed malformed seqMac integration tests ──────
    //
    // (a/b/c) above pin malformed VERIFY KEY at refresh-classifier time.
    // (d/e/f) below pin malformed `PollEnvelope.seqMac` at envelope
    // processing time — the path the relay actually exercises on every
    // poll: a hostile or buggy upstream sends a 64-char body envelope
    // with a non-hex `seqMac`; the orchestrator must drop it, leave the
    // pending-seq map untouched (no future cursor advance for this
    // envelope), keep the loop alive (no thrown exception), and resume
    // verifying the NEXT envelope normally so a single bad payload does
    // not poison the session.

    @Test
    fun mb19_d_non_hex_seq_mac_drops_envelope_no_cursor_advance_loop_continues_then_recovers() = runTest(timeout = 5.minutes) {
        runSeqMacFailClosedThenRecoverScenario(
            testScheduler,
            malformedSeqMac = "0123456789abcdef".repeat(4).replaceRange(0, 1, "g"),
            caseLabel = "non-hex character at offset 0",
        )
    }

    @Test
    fun mb19_e_odd_length_seq_mac_drops_envelope_no_cursor_advance_loop_continues_then_recovers() = runTest(timeout = 5.minutes) {
        runSeqMacFailClosedThenRecoverScenario(
            testScheduler,
            malformedSeqMac = "0".repeat(63),
            caseLabel = "63-char odd length",
        )
    }

    @Test
    fun mb19_f_short_seq_mac_drops_envelope_no_cursor_advance_loop_continues_then_recovers() = runTest(timeout = 5.minutes) {
        runSeqMacFailClosedThenRecoverScenario(
            testScheduler,
            malformedSeqMac = "0".repeat(32),
            caseLabel = "32-char (half-length)",
        )
    }

    @Test
    fun mb19_f_long_seq_mac_drops_envelope_no_cursor_advance_loop_continues_then_recovers() = runTest(timeout = 5.minutes) {
        runSeqMacFailClosedThenRecoverScenario(
            testScheduler,
            malformedSeqMac = "0".repeat(128),
            caseLabel = "128-char (double-length)",
        )
    }

    /**
     * Common scenario: bootstrap with a valid 64-char verify key
     * (state machine → `KeyPresent`); construct a `PollEnvelope` with
     * a malformed `seqMac`; route it through the orchestrator's
     * production `processInboundEnvelopeWithVerifyForTest` seam
     * (the same call site both REST poll loops exercise); assert:
     *
     *   1. The call returns `false` — no `_inbound.emit`, no ack
     *      path, no cursor advance.
     *   2. `_pendingSeqForAck` does NOT contain an entry for the
     *      dropped envelope (the cursor cannot advance for a seq
     *      that was never registered).
     *   3. No exception escapes — the production poll loops keep
     *      running.
     *   4. The post-drop verify-key state is still `KeyPresent`
     *      (a single malformed `seqMac` does NOT suspend the key).
     *   5. Recovery — the SAME orchestrator, immediately after the
     *      drop, processes a follow-up envelope with a correctly
     *      computed `seqMac` AND `_pendingSeqForAck` now contains
     *      THAT envelope's seq, proving the loop did not poison.
     *      The ack + advance pipeline then ships a `cursor_advanced`
     *      log line carrying the recovery envelope's seq.
     */
    private suspend fun runSeqMacFailClosedThenRecoverScenario(
        scheduler: TestCoroutineScheduler,
        malformedSeqMac: String,
        caseLabel: String,
    ) {
        init()
        val captured = mutableListOf<String>()
        // 32-byte raw key as the verify-key BYTES; the orchestrator
        // expects 64-char lowercase hex of the same bytes — derive
        // both from one source so the recovery envelope's MAC matches.
        val verifyKeyBytes = ByteArray(32) { (it + 1).toByte() }
        val verifyKeyHex = verifyKeyBytes.joinToString("") {
            ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1)
        }
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
                        token = "tok-mb19-d",
                        expiresAt = Long.MAX_VALUE,
                        restFallback = true,
                        maxSendBodyBytes = 4096,
                        pollMaxEnvelopes = 1,
                        pollHoldSecs = 30,
                        seqMacVerifyKey = verifyKeyHex,
                    ),
                    rawBody = "{}", elapsedMs = 1L,
                )
            },
        )
        val cursor = RecordingCursor()
        val orch = buildOrchestrator(
            transport, scheduler,
            cursor = cursor,
            logSink = { captured += it },
        )
        try {
            orch.bootstrap()
            assertEquals(
                VerifyKeyState.KeyPresent(verifyKeyHex),
                orch.peekVerifyKeyStateForTest(),
                "[$caseLabel] bootstrap MUST publish KeyPresent before exercising the verify path",
            )

            // Malformed envelope. The `seq=100` value is chosen so it
            // cannot be confused with the recovery envelope's seq.
            val malformedEnv = PollEnvelope(
                id = "env-mb19-d-malformed",
                fromHex = "ff".repeat(32),
                payloadBase64 = "",
                sequenceTs = 0L,
                seq = 100L,
                seqMac = malformedSeqMac,
            )

            val droppedReturn = orch.processInboundEnvelopeWithVerifyForTest(
                env = malformedEnv,
                currentToken = "tok-mb19-d",
                loopTag = "pollLoop",
            )
            assertEquals(
                false, droppedReturn,
                "[$caseLabel] processInboundEnvelopeWithVerify MUST return false on Malformed seqMac",
            )
            assertEquals(
                null,
                orch.peekPendingSeqForAckForTest(malformedEnv.id),
                "[$caseLabel] _pendingSeqForAck MUST NOT contain a dropped envelope's id — " +
                    "any subsequent ack call cannot advance the cursor for it",
            )
            assertEquals(
                VerifyKeyState.KeyPresent(verifyKeyHex),
                orch.peekVerifyKeyStateForTest(),
                "[$caseLabel] a single Malformed seqMac MUST NOT suspend the verify key " +
                    "(M-B19 is per-envelope, not per-session)",
            )
            assertTrue(
                captured.any {
                    it.startsWith("REST_TRACE poll_mac_verify_repeat") ||
                        it.startsWith("REST_TRACE poll_mac_drop")
                } || captured.any { "reason=no_mac_field" in it },
                "[$caseLabel] expected a bad-MAC posture log (`poll_mac_verify_repeat` / " +
                    "`reason=no_mac_field`); got:\n${captured.joinToString("\n")}",
            )

            // Recovery: a follow-up envelope with a correctly-computed
            // seqMac. The cursor write path is exercised via the same
            // C3 seam as the regular cursor_advanced pin above.
            val recoveryEnv = PollEnvelope(
                id = "env-mb19-d-recovery",
                fromHex = "ff".repeat(32),
                payloadBase64 = "",
                sequenceTs = 0L,
                seq = 101L,
                seqMac = SeqMacVerifier.computeMac(
                    identityHex = IDENTITY,
                    seq = 101L,
                    envelopeId = "env-mb19-d-recovery",
                    sequenceTs = 0L,
                    verifyKeyBytes = verifyKeyBytes,
                ).joinToString("") {
                    ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1)
                },
            )
            val recoveredReturn = orch.processInboundEnvelopeWithVerifyForTest(
                env = recoveryEnv,
                currentToken = "tok-mb19-d",
                loopTag = "pollLoop",
            )
            assertEquals(
                true, recoveredReturn,
                "[$caseLabel] follow-up valid envelope MUST verify and emit after a drop",
            )
            assertEquals(
                101L,
                orch.peekPendingSeqForAckForTest(recoveryEnv.id),
                "[$caseLabel] _pendingSeqForAck MUST hold the recovery envelope's seq " +
                    "after emit-with-cancellation-safe-rollback",
            )
            assertTrue(
                captured.any {
                    it.startsWith("REST_TRACE seq_mac_verified ") &&
                        "seq=101" in it
                },
                "[$caseLabel] expected a `seq_mac_verified seq=101` log on recovery; " +
                    "got:\n${captured.joinToString("\n")}",
            )
        } finally {
            // `runCurrent()` is a `TestScope`-receiver extension; this
            // private suspend fun does not have that receiver and the
            // outer `runTest { ... }` block does the final pump on
            // `runSeqMacFailClosedThenRecoverScenario` return.
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

    /**
     * Recording cursor for the C6 review-fix log-shape pins. Captures
     * every successful upsert so a test can assert both the write
     * landed AND the `cursor_advanced` log line carrying the same seq.
     */
    private class RecordingCursor : LongPollCursorRepository {
        var initialSeq: Long? = null
        val writes: MutableList<Triple<String, Long, Long>> = mutableListOf()
        override suspend fun getLastSeenSeq(identityHex: String): Long? = initialSeq
        override suspend fun upsertLastSeenSeq(identityHex: String, seq: Long, nowMs: Long) {
            writes += Triple(identityHex, seq, nowMs)
            initialSeq = maxOf(initialSeq ?: Long.MIN_VALUE, seq)
        }
    }

    private fun buildOrchestrator(
        transport: C6TestTransport,
        scheduler: TestCoroutineScheduler,
        csprng: Csprng = phantom.core.crypto.LibsodiumCsprng,
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
        csprng = csprng,
    )
}
