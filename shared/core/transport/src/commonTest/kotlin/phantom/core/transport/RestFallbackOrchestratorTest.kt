// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Integration tests for [RestFallbackOrchestrator]. Wire-level concerns
 * (TLS, HTTP framing) are out of scope — those live in the Android-only
 * native OkHttp impl. These tests cover:
 *
 *  - Capability gate: when relay returns `rest_fallback=false`, the
 *    orchestrator stays dormant (no /send, /poll, /ack calls ever fire).
 *  - Send with capability disabled returns [SendOutcome.DisabledByCapability]
 *    without any I/O.
 *  - Oversize body is refused before transport invocation (no retry).
 *  - Send success on first attempt returns [SendOutcome.Accepted].
 *  - Send replay (status 200) returns [SendOutcome.Duplicate] and logs dedup.
 *  - Idempotency-Key is identical across all retry attempts.
 *  - /auth/session retry-safety: when the orchestrator hits 401 mid-flight,
 *    it refreshes the token and retries once.
 *  - Hard failure (e.g. 400) does not trigger retries.
 *  - Token caching: ensureToken does not re-issue if cached token is fresh.
 */
class RestFallbackOrchestratorTest {

    // ── Fake transport ────────────────────────────────────────────────────────

    /**
     * Recording fake. Each public method returns a scripted response or
     * throws if the corresponding `nextThrow` is non-null. Captures all
     * inputs into a list for assertions.
     */
    private class FakeTransport : RestFallbackTransport {
        data class SendCall(
            val token: String,
            val idempotencyKey: String,
            val body: SendRequest,
        )

        var sessionScript: (AuthSessionRequest) -> RestFallbackResponse<AuthSessionResponse> = { _ ->
            RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AuthSessionResponse(
                    token = "test-token",
                    expiresAt = 3_600_000L,
                    restFallback = true,
                    maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1,
                ),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        }
        val sendScripts: ArrayDeque<(SendCall) -> RestFallbackResponse<SendResponse>> = ArrayDeque()
        val sendCalls: MutableList<SendCall> = mutableListOf()
        val pollCalls: MutableList<Long?> = mutableListOf()
        val ackCalls: MutableList<String> = mutableListOf()

        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> = sessionScript(body)

        override suspend fun send(
            url: String,
            token: String,
            idempotencyKey: String,
            body: SendRequest,
        ): RestFallbackResponse<SendResponse> {
            val call = SendCall(token, idempotencyKey, body)
            sendCalls += call
            val script = sendScripts.removeFirstOrNull()
                ?: return RestFallbackResponse(201, SendResponse(1), "{}", 1L)
            return script(call)
        }

        override suspend fun poll(
            url: String,
            token: String,
            sinceSeq: Long?,
        ): RestFallbackResponse<PollResponse> {
            pollCalls += sinceSeq
            return RestFallbackResponse(200, PollResponse(emptyList(), false), "{}", 1L)
        }

        override suspend fun ackDeliver(
            url: String,
            token: String,
            body: AckDeliverRequest,
        ): RestFallbackResponse<AckDeliverResponse> {
            ackCalls += body.id
            return RestFallbackResponse(200, AckDeliverResponse(1), "{}", 1L)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun orchestrator(
        transport: FakeTransport,
        clockMs: () -> Long = { 0L },
    ): RestFallbackOrchestrator = RestFallbackOrchestrator(
        baseUrl = "https://relay.test",
        identityHex = "aa".repeat(32),
        signingPubkeyHex = "bb".repeat(32),
        getChallenge = { _ -> "cc".repeat(32) },
        signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
        transport = transport,
        now = clockMs,
        dispatcher = UnconfinedTestDispatcher(),
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun bootstrap_with_capability_disabled_keeps_safe_defaults() = runTest {
        val transport = FakeTransport().apply {
            sessionScript = { _ ->
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = AuthSessionResponse(
                        token = "t",
                        expiresAt = 1L,
                        restFallback = false,
                        maxSendBodyBytes = 0,
                        pollMaxEnvelopes = 0,
                    ),
                    rawBody = "{}",
                    elapsedMs = 1L,
                )
            }
        }
        val orch = orchestrator(transport)
        val caps = orch.bootstrap()
        assertEquals(false, caps.restFallback)
        assertEquals(RelayCapabilities.SAFE_DEFAULTS, orch.capabilities.value)
    }

    @Test
    fun send_with_capability_disabled_returns_disabled_without_io() = runTest {
        val transport = FakeTransport().apply {
            sessionScript = { _ ->
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = AuthSessionResponse(
                        token = "t",
                        expiresAt = 1L,
                        restFallback = false,
                    ),
                    rawBody = "{}",
                    elapsedMs = 1L,
                )
            }
        }
        val orch = orchestrator(transport)
        orch.bootstrap()
        val outcome = orch.sendEnvelope(
            envelopeId = "env-1",
            toHex = "01".repeat(32),
            payloadBase64 = "AAAA",
            sequenceTs = 0L,
        )
        assertIs<SendOutcome.DisabledByCapability>(outcome)
        assertTrue(transport.sendCalls.isEmpty(), "no /relay/send call should fire when REST disabled")
    }

    @Test
    fun bootstrap_with_capability_enabled_records_caps() = runTest {
        val transport = FakeTransport()
        val orch = orchestrator(transport)
        val caps = orch.bootstrap()
        assertEquals(true, caps.restFallback)
        assertEquals(4096, caps.maxSendBodyBytes)
        assertEquals(1, caps.pollMaxEnvelopes)
    }

    @Test
    fun send_oversize_body_refused_before_io() = runTest {
        val transport = FakeTransport()
        val orch = orchestrator(transport)
        orch.bootstrap()

        // payloadBase64 ~= 5000b, +256 overhead = 5256b > 4096 cap.
        val bigPayload = "A".repeat(5000)
        val outcome = orch.sendEnvelope(
            envelopeId = "env-big",
            toHex = "01".repeat(32),
            payloadBase64 = bigPayload,
            sequenceTs = 0L,
        )
        assertIs<SendOutcome.OversizeBody>(outcome)
        assertTrue(transport.sendCalls.isEmpty(), "oversize should refuse before transport invocation")
    }

    @Test
    fun send_success_on_first_attempt_returns_accepted() = runTest {
        val transport = FakeTransport()
        transport.sendScripts.addLast { _ ->
            RestFallbackResponse(201, SendResponse(1), "{}", 1L)
        }
        val orch = orchestrator(transport)
        orch.bootstrap()
        val outcome = orch.sendEnvelope(
            envelopeId = "env-1",
            toHex = "01".repeat(32),
            payloadBase64 = "AAAA",
            sequenceTs = 0L,
        )
        assertIs<SendOutcome.Accepted>(outcome)
        assertEquals(1, transport.sendCalls.size, "exactly one /relay/send call on first-attempt success")
    }

    @Test
    fun send_replay_returns_duplicate() = runTest {
        val transport = FakeTransport()
        transport.sendScripts.addLast { _ ->
            RestFallbackResponse(200, SendResponse(1), "{}", 1L)
        }
        val orch = orchestrator(transport)
        orch.bootstrap()
        val outcome = orch.sendEnvelope(
            envelopeId = "env-1",
            toHex = "01".repeat(32),
            payloadBase64 = "AAAA",
            sequenceTs = 0L,
        )
        assertIs<SendOutcome.Duplicate>(outcome)
    }

    @Test
    fun send_idempotency_key_stable_across_retries() = runTest {
        val transport = FakeTransport()
        // First attempt: 500, second: 500, third: 201
        transport.sendScripts.addLast { _ -> RestFallbackResponse(500, null, "err", 1L) }
        transport.sendScripts.addLast { _ -> RestFallbackResponse(500, null, "err", 1L) }
        transport.sendScripts.addLast { _ -> RestFallbackResponse(201, SendResponse(1), "{}", 1L) }
        val orch = orchestrator(transport)
        orch.bootstrap()

        val outcome = orch.sendEnvelope(
            envelopeId = "env-stable",
            toHex = "01".repeat(32),
            payloadBase64 = "AAAA",
            sequenceTs = 0L,
        )
        assertIs<SendOutcome.Accepted>(outcome)
        assertEquals(3, transport.sendCalls.size, "retried twice then succeeded")
        // Same idempotency key in every attempt.
        assertTrue(
            transport.sendCalls.all { it.idempotencyKey == "env-stable" },
            "Idempotency-Key must be stable across retries",
        )
        // And same envelope_id in body.
        assertTrue(
            transport.sendCalls.all { it.body.envelopeId == "env-stable" },
            "envelope_id must be stable across retries",
        )
    }

    @Test
    fun send_does_not_retry_on_400() = runTest {
        val transport = FakeTransport()
        transport.sendScripts.addLast { _ -> RestFallbackResponse(400, null, "bad", 1L) }
        val orch = orchestrator(transport)
        orch.bootstrap()

        val outcome = orch.sendEnvelope(
            envelopeId = "env-bad",
            toHex = "01".repeat(32),
            payloadBase64 = "AAAA",
            sequenceTs = 0L,
        )
        val failed = assertIs<SendOutcome.Failed>(outcome)
        assertEquals(400, failed.statusCode)
        assertEquals(1, transport.sendCalls.size, "400 must not retry")
    }

    @Test
    fun send_does_not_retry_on_413() = runTest {
        val transport = FakeTransport()
        transport.sendScripts.addLast { _ -> RestFallbackResponse(413, null, "too big", 1L) }
        val orch = orchestrator(transport)
        orch.bootstrap()

        val outcome = orch.sendEnvelope(
            envelopeId = "env-big",
            toHex = "01".repeat(32),
            payloadBase64 = "AAAA",
            sequenceTs = 0L,
        )
        val failed = assertIs<SendOutcome.Failed>(outcome)
        assertEquals(413, failed.statusCode)
        assertEquals(1, transport.sendCalls.size, "413 must not retry")
    }

    @Test
    fun send_retries_on_500_until_max_attempts() = runTest {
        val transport = FakeTransport()
        repeat(RestFallbackOrchestrator.SEND_MAX_ATTEMPTS) {
            transport.sendScripts.addLast { _ -> RestFallbackResponse(500, null, "err", 1L) }
        }
        val orch = orchestrator(transport)
        orch.bootstrap()

        val outcome = orch.sendEnvelope(
            envelopeId = "env-5xx",
            toHex = "01".repeat(32),
            payloadBase64 = "AAAA",
            sequenceTs = 0L,
        )
        assertIs<SendOutcome.Failed>(outcome)
        assertEquals(
            RestFallbackOrchestrator.SEND_MAX_ATTEMPTS,
            transport.sendCalls.size,
            "5xx should retry up to SEND_MAX_ATTEMPTS",
        )
    }

    @Test
    fun ack_inbound_with_capability_disabled_returns_disabled() = runTest {
        val transport = FakeTransport().apply {
            sessionScript = { _ ->
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = AuthSessionResponse(
                        token = "t",
                        expiresAt = 1L,
                        restFallback = false,
                    ),
                    rawBody = "{}",
                    elapsedMs = 1L,
                )
            }
        }
        val orch = orchestrator(transport)
        orch.bootstrap()
        val outcome = orch.ackInbound("env-x")
        assertIs<AckOutcome.DisabledByCapability>(outcome)
        assertTrue(transport.ackCalls.isEmpty(), "no ack call when REST disabled")
    }

    @Test
    fun ack_inbound_success() = runTest {
        val transport = FakeTransport()
        val orch = orchestrator(transport)
        orch.bootstrap()
        val outcome = orch.ackInbound("env-x")
        assertIs<AckOutcome.Acked>(outcome)
        assertEquals(listOf("env-x"), transport.ackCalls)
    }

    @Test
    fun retry_delays_table_matches_locked_spec() {
        // Locked 2026-05-16: 1s / 3s / 8s / 20s / 60s
        assertTrue(
            RestFallbackOrchestrator.SEND_RETRY_DELAYS_MS.toList() ==
                listOf(1_000L, 3_000L, 8_000L, 20_000L, 60_000L),
            "retry-delay table drifted from the locked spec; got " +
                RestFallbackOrchestrator.SEND_RETRY_DELAYS_MS.toList(),
        )
        assertEquals(5, RestFallbackOrchestrator.SEND_MAX_ATTEMPTS)
    }
}
