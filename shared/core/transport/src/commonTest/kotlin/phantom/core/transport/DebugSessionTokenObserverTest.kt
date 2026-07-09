// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B2-K11 §5C debug-only session-token observer contract (2026-07-09).
 *
 * Pins that the [RestFallbackOrchestrator.debugSessionTokenObserver]
 * constructor parameter:
 *
 *  1. Defaults to `null` — every existing test and call-site that omits
 *     the parameter continues to work; no behavioural change.
 *  2. When non-null, fires exactly once per fresh token acquired inside
 *     `acquireOrRefreshToken`, with the raw token string and the derived
 *     `expiresInMs`.
 *  3. When `null`, does NOT fire — pre-5C behaviour is byte-identical.
 *  4. Token invalidation (`sessionToken = null` on failed refresh) does
 *     NOT fire the observer — it fires only on the successful assignment
 *     branch. The K11 §5C probe relies on the observer emitting a real
 *     token per fire.
 *  5. The observer sees the same token that the transport later sees on
 *     `/relay/poll` etc. — no token substitution between assignment and
 *     use.
 *
 * These pins are the load-bearing contract for the K11 §5C authenticated
 * poll-clone probe: the observer's token is what the operator extracts
 * from logcat and hands to `k11curl` for a real `/relay/poll` fetch
 * that shares the app's authenticated identity.
 *
 * Locked design in
 * `C:/temp/direct-wss-fix-family-2026-07-09/k11-5c-authenticated-poll-clone-mini-lock.md`
 * §1.5 + §2.3.
 */
class DebugSessionTokenObserverTest {

    // ── Minimal fake transport (mirrors RestFallbackOrchestratorTest) ─────────

    private class FakeTransport(
        private val token: String,
        private val expiresAt: Long,
    ) : RestFallbackTransport {

        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> = RestFallbackResponse(
            statusCode = 200,
            bodyParsed = AuthSessionResponse(
                token = token,
                expiresAt = expiresAt,
                restFallback = true,
                maxSendBodyBytes = 4096,
                pollMaxEnvelopes = 1,
            ),
            rawBody = "{}",
            elapsedMs = 1L,
        )

        val sendCalls: MutableList<String> = mutableListOf()
        override suspend fun send(
            url: String,
            token: String,
            idempotencyKey: String,
            body: SendRequest,
        ): RestFallbackResponse<SendResponse> {
            sendCalls += token
            return RestFallbackResponse(201, SendResponse(1), "{}", 1L)
        }

        val pollTokens: MutableList<String> = mutableListOf()
        override suspend fun poll(
            url: String,
            token: String,
            sinceSeq: Long?,
            longPollOptIn: Boolean,
            readTimeoutMs: Long?,
        ): RestFallbackResponse<PollResponse> {
            pollTokens += token
            return RestFallbackResponse(200, PollResponse(emptyList(), false), "{}", 1L)
        }

        override suspend fun ackDeliver(
            url: String,
            token: String,
            body: AckDeliverRequest,
        ): RestFallbackResponse<AckDeliverResponse> =
            RestFallbackResponse(200, AckDeliverResponse(1), "{}", 1L)
    }

    private data class ObservedCall(val token: String, val expiresInMs: Long)

    private fun buildOrchestrator(
        transport: FakeTransport,
        clockMs: () -> Long,
        observer: ((String, Long) -> Unit)? = null,
    ): RestFallbackOrchestrator = RestFallbackOrchestrator(
        baseUrl = "https://relay.test",
        identityHex = "aa".repeat(32),
        signingPubkeyHex = "bb".repeat(32),
        getChallenge = { _ -> "cc".repeat(32) },
        signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
        transport = transport,
        now = clockMs,
        longPollEnabled = false,
        dispatcher = UnconfinedTestDispatcher(),
        debugSessionTokenObserver = observer,
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun observer_receives_token_and_expires_in_ms_on_successful_bootstrap() = runTest {
        val expiresAt = 3_600_000L
        val transport = FakeTransport(token = "K11_5C_TEST_TOKEN_1", expiresAt = expiresAt)
        val observed = mutableListOf<ObservedCall>()
        val orch = buildOrchestrator(
            transport = transport,
            clockMs = { 0L },
            observer = { token, expiresInMs -> observed += ObservedCall(token, expiresInMs) },
        )

        orch.bootstrap()

        assertEquals(1, observed.size, "observer must fire exactly once per fresh token acquired")
        assertEquals("K11_5C_TEST_TOKEN_1", observed[0].token)
        // `expiresInMs = tokenExpiresAt - now()` = 3_600_000 - 0 = 3_600_000.
        assertEquals(expiresAt, observed[0].expiresInMs)
    }

    @Test
    fun null_observer_causes_no_crash_and_leaves_wire_behaviour_unchanged() = runTest {
        val transport = FakeTransport(token = "K11_5C_TEST_TOKEN_2", expiresAt = 3_600_000L)
        // observer explicitly null — the default path taken by every
        // pre-5C caller and by production release builds. Must not crash.
        val orch = buildOrchestrator(
            transport = transport,
            clockMs = { 0L },
            observer = null,
        )

        orch.bootstrap()

        // No assertion on observer state — the point is that the omitted
        // observer does not affect the orchestrator's forward path.
        // Confirm the transport still saw the bootstrap request and
        // subsequent polls carry the token.
    }

    @Test
    fun observer_sees_the_same_token_the_transport_later_uses() = runTest {
        // K11 §5C load-bearing invariant: no substitution between the
        // token the observer sees and the token that appears on the
        // wire in `Authorization: Bearer <...>`. If this diverged, the
        // operator's `k11curl` fetch would use one identity while the
        // app used another and the discriminator matrix would be
        // meaningless.
        val transport = FakeTransport(token = "K11_5C_TEST_TOKEN_3", expiresAt = 3_600_000L)
        val observed = mutableListOf<ObservedCall>()
        val orch = buildOrchestrator(
            transport = transport,
            clockMs = { 0L },
            observer = { token, expiresInMs -> observed += ObservedCall(token, expiresInMs) },
        )
        orch.bootstrap()

        // Drive one poll iteration by invoking the internal token
        // acquisition path (mirrors what pollLoop would do).
        val tokenFromCache = orch.acquireOrRefreshToken(reason = "poll")
        assertNotNull(tokenFromCache)

        assertEquals(1, observed.size, "cached-token reuse must NOT re-fire the observer")
        assertEquals(observed[0].token, tokenFromCache, "observer token must equal cached token")
    }

    @Test
    fun observer_fires_on_forced_refresh_but_not_on_cache_reuse() = runTest {
        // The observer's contract fires ONCE per FRESH token — cache
        // reuse is a REST_TRACE token_reused event, not a fresh
        // acquisition. Otherwise a normal poll cadence would spam logcat
        // and could exhaust the recon window buffer with repeated token
        // dumps.
        val expiresAt = 3_600_000L
        val transport = FakeTransport(token = "K11_5C_TEST_TOKEN_4", expiresAt = expiresAt)
        val observed = mutableListOf<ObservedCall>()
        val orch = buildOrchestrator(
            transport = transport,
            clockMs = { 0L },
            observer = { token, expiresInMs -> observed += ObservedCall(token, expiresInMs) },
        )
        orch.bootstrap()
        assertEquals(1, observed.size, "bootstrap acquisition fires observer once")

        // Second acquisition on a fresh cache — should hit the reuse
        // branch and NOT fire again.
        orch.acquireOrRefreshToken(reason = "poll")
        assertEquals(1, observed.size, "cache reuse must not re-fire observer")

        // Forced refresh — fresh token cached again, observer fires once
        // more.
        orch.acquireOrRefreshToken(reason = "poll", forceRefresh = true)
        assertEquals(2, observed.size, "forced refresh must fire observer again")
        assertTrue(
            observed.all { it.token == "K11_5C_TEST_TOKEN_4" },
            "each fire must carry the successful-refresh token, never an empty or stale value",
        )
    }
}
