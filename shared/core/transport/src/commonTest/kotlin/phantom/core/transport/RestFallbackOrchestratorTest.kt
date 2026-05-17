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

    // ── PR-D1c.1 token-lifecycle regression tests ────────────────────────────

    /**
     * Helper: build a session script that hands out `T1`, `T2`, … and
     * lets the caller observe how many `/auth/session` round-trips really
     * happened. Each invocation produces a fresh, healthy
     * `restFallback=true` capability response.
     */
    private class CountingAuth {
        var count: Int = 0
        val script: (AuthSessionRequest) -> RestFallbackResponse<AuthSessionResponse> = { _ ->
            count++
            RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AuthSessionResponse(
                    token = "T$count",
                    expiresAt = 3_600_000L,
                    restFallback = true,
                    maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1,
                ),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        }
    }

    @Test
    fun bootstrap_caches_token_no_extra_auth_on_first_send() = runTest {
        // PR-D1c.1 fix #1: the old bootstrap() called authSessionOnce() but
        // never wrote the returned token/expiresAt into cache, so the first
        // sendEnvelope re-did /auth/session. Test #50 paid 700 ms of TSPU
        // latency for this. After the fix, bootstrap must prime the cache.
        val auth = CountingAuth()
        val transport = FakeTransport().apply { sessionScript = auth.script }
        val orch = orchestrator(transport)
        orch.bootstrap()
        assertEquals(1, auth.count, "bootstrap should issue exactly one /auth/session")

        val outcome = orch.sendEnvelope(
            envelopeId = "env-1",
            toHex = "01".repeat(32),
            payloadBase64 = "AAAA",
            sequenceTs = 0L,
        )
        assertIs<SendOutcome.Accepted>(outcome)
        assertEquals(
            1, auth.count,
            "sendEnvelope after bootstrap must reuse the cached token, no extra /auth/session",
        )
        assertEquals(
            "T1", transport.sendCalls.single().token,
            "sendEnvelope must use the token cached during bootstrap",
        )
    }

    @Test
    fun send_envelope_401_refreshes_and_retries_with_new_token() = runTest {
        // PR-D1c.1 fix #3: the old sendEnvelope captured `token` once before
        // the retry loop, so even after the 401-branch called ensureToken()
        // the next attempt re-used the stale token. After the fix, the token
        // is acquired inside the loop, with staleToken CAS.
        val auth = CountingAuth()
        val transport = FakeTransport().apply { sessionScript = auth.script }
        transport.sendScripts.addLast { _ -> RestFallbackResponse(401, null, "unauth", 1L) }
        transport.sendScripts.addLast { _ -> RestFallbackResponse(201, SendResponse(1), "{}", 1L) }

        val orch = orchestrator(transport)
        orch.bootstrap()
        assertEquals(1, auth.count)

        val outcome = orch.sendEnvelope(
            envelopeId = "env-1",
            toHex = "01".repeat(32),
            payloadBase64 = "AAAA",
            sequenceTs = 0L,
        )
        assertIs<SendOutcome.Accepted>(outcome)
        assertEquals(
            2, auth.count,
            "401 must trigger exactly one /auth/session refresh (bootstrap + 1)",
        )
        assertEquals(2, transport.sendCalls.size, "send must be retried after the 401 refresh")
        assertEquals(
            "T1", transport.sendCalls[0].token,
            "first attempt uses the originally-cached token",
        )
        assertEquals(
            "T2", transport.sendCalls[1].token,
            "retry must use the refreshed token, NOT the stale one captured before the loop",
        )
    }

    @Test
    fun acquire_or_refresh_token_cas_reuses_when_another_caller_refreshed() = runTest {
        // PR-D1c.1 fix #2: without CAS, two coroutines that each receive 401
        // for the same stale token would both forceRefresh, the server would
        // replace token A with token B, the first coroutine's "refreshed"
        // token gets invalidated immediately, and the next attempt hits 401
        // again. CAS path: if cached != staleToken, reuse cached.
        val auth = CountingAuth()
        val transport = FakeTransport().apply { sessionScript = auth.script }
        val orch = orchestrator(transport)

        // Fresh acquire → T1
        val t1 = orch.acquireOrRefreshToken(reason = "test")
        assertEquals("T1", t1)
        assertEquals(1, auth.count)

        // Coroutine A's token went stale → forced refresh → T2
        val t2 = orch.acquireOrRefreshToken(reason = "test", staleToken = "T1")
        assertEquals("T2", t2)
        assertEquals(2, auth.count, "stale-token caller must refresh when cached==stale")

        // Coroutine B was also holding T1 when its 401 happened. By the time
        // B enters the mutex, cache already holds T2. CAS path must reuse.
        val t2cas = orch.acquireOrRefreshToken(reason = "test", staleToken = "T1")
        assertEquals("T2", t2cas)
        assertEquals(
            2, auth.count,
            "CAS path must NOT issue a fresh /auth/session when cached != staleToken",
        )
    }

    @Test
    fun capabilities_updated_on_token_refresh() = runTest {
        // PR-D1c.1 side benefit: every successful auth-session response now
        // re-publishes capabilities. If the relay flips restFallback=false
        // at runtime (e.g. operator disables REST), the orchestrator picks
        // that up on the next refresh rather than holding a stale "true".
        var count = 0
        val transport = FakeTransport().apply {
            sessionScript = { _ ->
                count++
                val restEnabled = (count == 1)
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = AuthSessionResponse(
                        token = "T$count",
                        expiresAt = 3_600_000L,
                        restFallback = restEnabled,
                        maxSendBodyBytes = if (restEnabled) 4096 else 0,
                        pollMaxEnvelopes = if (restEnabled) 1 else 0,
                    ),
                    rawBody = "{}",
                    elapsedMs = 1L,
                )
            }
        }
        val orch = orchestrator(transport)
        orch.bootstrap()
        assertEquals(true, orch.capabilities.value.restFallback)
        assertEquals(4096, orch.capabilities.value.maxSendBodyBytes)

        // Drive a second auth (stale-token path) and check capabilities follow.
        val refreshed = orch.acquireOrRefreshToken(reason = "test", staleToken = "T1")
        assertEquals("T2", refreshed)
        assertEquals(
            false, orch.capabilities.value.restFallback,
            "refreshed capability snapshot must propagate to _capabilities.value",
        )
        assertEquals(0, orch.capabilities.value.maxSendBodyBytes)
    }
}
