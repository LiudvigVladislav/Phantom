// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * RC-RECONNECT-QUIESCENCE1 commit 2d second-round amend (2026-06-22)
 * — production-wiring regression tests.
 *
 * The previous Test 20 created [RestStateMachine] DIRECTLY with
 * `TransportKind.Direct` and a deterministic token. That bypassed the
 * `RestFallbackOrchestrator` ctor surface and missed the fact that
 * `currentKindProvider` + `tokenSource` were not forwarded — so
 * production would have defaulted to `{ null }` and `{ 0L }`,
 * silently breaking quiescence:
 *
 *   - `armSticky` would never flip the gate to `Quiesced` because the
 *     Direct-fence sees `currentKindProvider() == null`.
 *   - Any probe path that DID complete would have used a constant
 *     token `0L` (trivially guessable across processes).
 *
 * These tests construct [RestFallbackOrchestrator] via the ctor with
 * production-style providers and assert that the wiring reaches the
 * state machine end-to-end.
 */
class RestFallbackOrchestratorQuiescenceWiringTest {

    /**
     * The tests never call any [RestFallbackTransport] method — they
     * only exercise the orchestrator's ctor surface and read its
     * `stateMachine` field — so an always-throwing fake is sufficient
     * and avoids carrying the full transport contract.
     */
    private class AlwaysThrowTransport : RestFallbackTransport {
        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> =
            error("test must not invoke authSession")

        override suspend fun send(
            url: String,
            token: String,
            idempotencyKey: String,
            body: SendRequest,
        ): RestFallbackResponse<SendResponse> = error("test must not invoke send")

        override suspend fun poll(
            url: String,
            token: String,
            sinceSeq: Long?,
            longPollOptIn: Boolean,
            readTimeoutMs: Long?,
        ): RestFallbackResponse<PollResponse> = error("test must not invoke poll")

        override suspend fun ackDeliver(
            url: String,
            token: String,
            body: AckDeliverRequest,
        ): RestFallbackResponse<AckDeliverResponse> = error("test must not invoke ackDeliver")
    }

    private fun newOrchestrator(
        kindProvider: () -> TransportKind?,
        tokens: () -> Long,
    ): RestFallbackOrchestrator = RestFallbackOrchestrator(
        baseUrl = "https://relay.test",
        identityHex = "aa".repeat(32),
        signingPubkeyHex = "bb".repeat(32),
        getChallenge = { _ -> "cc".repeat(32) },
        signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
        transport = AlwaysThrowTransport(),
        now = { 0L },
        log = { },
        mode2FastPathEnabled = true,
        mode2StickyEnabled = true,
        reconnectQuiescenceEnabled = true,
        currentKindProvider = kindProvider,
        tokenSource = tokens,
    )

    // ── P1: currentKindProvider must reach the state machine ────────────────

    @Test
    fun armSticky_engages_quiescence_when_currentKindProvider_returns_Direct() = runBlocking {
        val orch = newOrchestrator(
            kindProvider = { TransportKind.Direct },
            tokens = { 0L },
        )
        // Drive the Mode-2 fast-path event into the state machine.
        orch.stateMachine.onEvent(
            RestStateMachine.Event.WsSessionEnded(
                durationMs = 31_000,
                inboundFrames = 0,
                pendingAcksAtClose = 0,
                okhttpPingTimeoutDetected = true,
                sessionEpoch = 1L,
            ),
        )
        val gate = orch.stateMachine.gate.value
        assertTrue(
            gate is WsReconnectGate.Quiesced,
            "currentKindProvider=Direct + Mode 2 fast-path ⇒ gate MUST flip to Quiesced; " +
                "got $gate. If this asserts as Open, the production-wiring of " +
                "RestFallbackOrchestrator's currentKindProvider through to RestStateMachine " +
                "is broken — the Direct-fence in armSticky() will see null and skip the " +
                "gate transition.",
        )
    }

    @Test
    fun armSticky_does_NOT_engage_quiescence_when_currentKindProvider_returns_null() = runBlocking {
        // Sanity check: when the provider returns null (e.g. transport
        // manager is not yet connected), the Direct fence correctly
        // skips the gate transition. Validates that the wiring is
        // CONDITIONAL on the snapshot, not unconditional.
        val orch = newOrchestrator(
            kindProvider = { null },
            tokens = { 0L },
        )
        orch.stateMachine.onEvent(
            RestStateMachine.Event.WsSessionEnded(
                durationMs = 31_000,
                inboundFrames = 0,
                pendingAcksAtClose = 0,
                okhttpPingTimeoutDetected = true,
                sessionEpoch = 1L,
            ),
        )
        assertEquals(
            WsReconnectGate.Open, orch.stateMachine.gate.value,
            "currentKindProvider=null ⇒ gate stays Open even though sticky armed",
        )
    }

    // ── P1: tokenSource must reach the state machine ────────────────────────

    @Test
    fun issueProbeAfterRewalk_consumes_token_from_wired_tokenSource() = runBlocking {
        // Use a deterministic non-zero token source so we can pin
        // identity: the wiring MUST forward this exact source. A bug
        // in the orchestrator that defaulted to `{ 0L }` would issue
        // `ProbeAvailable(token=ProbeToken(0L))` instead of the wired
        // source's value.
        val expectedToken = 0xC0FFEE_BABEL
        val orch = newOrchestrator(
            kindProvider = { TransportKind.Direct },
            tokens = { expectedToken },
        )
        // Sticky-arm and then run the begin → issue cycle directly.
        orch.stateMachine.onEvent(
            RestStateMachine.Event.WsSessionEnded(
                durationMs = 31_000,
                inboundFrames = 0,
                pendingAcksAtClose = 0,
                okhttpPingTimeoutDetected = true,
                sessionEpoch = 1L,
            ),
        )
        val outcome = orch.stateMachine.beginRouteChange(clearsMode2Sticky = true)
        assertTrue(outcome is RouteChangeOutcome.StickyRecovery)
        val result = orch.stateMachine.issueProbeAfterRewalk(outcome.routeEpoch)
        assertTrue(result is ProbeIssueResult.ProbeIssued, "probe issued; got $result")
        assertEquals(
            ProbeToken(expectedToken), result.token,
            "issued token must equal the wired tokenSource's value; " +
                "got ${result.token}. If this asserts as ProbeToken(0), the " +
                "production-wiring of RestFallbackOrchestrator's tokenSource through to " +
                "RestStateMachine is broken — issued tokens would be constant 0L " +
                "(trivially guessable across processes).",
        )
        // Defense in depth: assert the constant `0L` default is NOT
        // what we observed (a misleading equality with 0xC0FFEEBABE
        // alone could pass even if the wiring forwarded a different
        // source — but our token is deliberately non-zero).
        assertNotEquals(0L, result.token.value, "wired token MUST NOT be the default 0L")
    }
}
