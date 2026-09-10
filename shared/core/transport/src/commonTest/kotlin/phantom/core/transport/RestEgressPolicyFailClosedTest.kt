// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * N1-F2 (2026-08-29) — the owner-approved "No Silent Downgrade"
 * invariant, driven through the REAL orchestrator with a recording
 * transport.
 *
 * Private and Ghost MUST NEVER let a REST operation reach HTTP over
 * the Direct network. Every test drives the actual choke points
 * (bootstrap, token funnel, send, ack, the poll loops) and asserts the
 * recording transport observed ZERO dispatches and that the
 * identity-bearing `getChallenge` hop was never invoked.
 *
 * The policy is consulted live per operation, which is what these
 * tests pin for the runtime-transition semantics: a mode switch takes
 * effect on the next request/iteration, and a session token minted
 * under Standard is unusable afterwards.
 */
class RestEgressPolicyFailClosedTest {

    private val identity = "aa11bb22cc33dd44ee55ff66" + "00".repeat(20)

    /** Recording fake — counts every HTTP-boundary call. */
    private class RecordingTransport : RestFallbackTransport {
        var authSessionCalls = 0
        var sendCalls = 0
        var pollCalls = 0
        var ackCalls = 0
        val totalCalls get() = authSessionCalls + sendCalls + pollCalls + ackCalls

        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> {
            authSessionCalls++
            return RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AuthSessionResponse(
                    token = "standard-token",
                    expiresAt = 3_600_000L,
                    restFallback = true,
                    maxSendBodyBytes = 65_536,
                    pollMaxEnvelopes = 10,
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
            sendCalls++
            return RestFallbackResponse(201, SendResponse(1), "{}", 1L)
        }

        override suspend fun poll(
            url: String,
            token: String,
            sinceSeq: Long?,
            longPollOptIn: Boolean,
            readTimeoutMs: Long?,
        ): RestFallbackResponse<PollResponse> {
            pollCalls++
            return RestFallbackResponse(200, PollResponse(emptyList(), false), "{}", 1L)
        }

        override suspend fun ackDeliver(
            url: String,
            token: String,
            body: AckDeliverRequest,
        ): RestFallbackResponse<AckDeliverResponse> {
            ackCalls++
            return RestFallbackResponse(200, AckDeliverResponse(1), "{}", 1L)
        }
    }

    private class Harness(initialMode: PrivacyMode?, val transport: RecordingTransport) {
        var mode: PrivacyMode? = initialMode
        var throwOnRead = false
        var challengeCalls = 0
        val logs = mutableListOf<String>()
    }

    private fun harness(mode: PrivacyMode?): Harness = Harness(mode, RecordingTransport())

    private fun gate(h: Harness): RestEgressGate = RestEgressGate(
        PrivacyModeRestEgressPolicy {
            if (h.throwOnRead) error("prefs backend unavailable") else h.mode
        },
        log = { line -> h.logs.add(line) },
    )

    private fun orchestrator(
        h: Harness,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        egressGate: RestEgressGate = gate(h),
    ): RestFallbackOrchestrator = RestFallbackOrchestrator(
        egressGate = egressGate,
        baseUrl = "https://relay.test",
        identityHex = identity,
        signingPubkeyHex = "bb".repeat(32),
        getChallenge = { _ ->
            h.challengeCalls++
            "cc".repeat(32)
        },
        signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
        transport = h.transport,
        now = { 0L },
        log = { line -> h.logs.add(line) },
        dispatcher = dispatcher,
    )

    // ── Mandatory 1: Private cold start performs zero REST HTTP ───────
    @Test
    fun private_cold_start_bootstrap_performs_zero_rest_http() = runTest {
        val h = harness(PrivacyMode.Private)
        val orch = orchestrator(h, UnconfinedTestDispatcher(testScheduler))
        val caps = orch.bootstrap()
        assertEquals(0, h.transport.totalCalls, "no HTTP may leave in Private")
        assertEquals(0, h.challengeCalls, "auth/challenge must not be fetched")
        assertFalse(caps.restFallback, "capabilities must collapse to SAFE_DEFAULTS")
        assertEquals(RelayCapabilities.SAFE_DEFAULTS, orch.capabilities.value)
    }

    // ── Mandatory 2: Ghost cold start performs zero REST HTTP ─────────
    @Test
    fun ghost_cold_start_bootstrap_performs_zero_rest_http() = runTest {
        val h = harness(PrivacyMode.Ghost)
        val orch = orchestrator(h, UnconfinedTestDispatcher(testScheduler))
        val caps = orch.bootstrap()
        assertEquals(0, h.transport.totalCalls, "no HTTP may leave in Ghost")
        assertEquals(0, h.challengeCalls, "unconditional bootstrap is forbidden in Ghost")
        assertFalse(caps.restFallback)
    }

    // ── Mandatory 3: no /auth/challenge or /auth/session, any op ──────
    @Test
    fun private_and_ghost_never_call_auth_challenge_or_auth_session() = runTest {
        for (mode in listOf(PrivacyMode.Private, PrivacyMode.Ghost)) {
            val h = harness(mode)
            val orch = orchestrator(h, UnconfinedTestDispatcher(testScheduler))
            orch.bootstrap()
            orch.sendEnvelope("env-1", "aa".repeat(32), "cGF5bG9hZA==", 1L)
            orch.ackInbound("env-1")
            orch.ackInboundAndAdvanceCursor("env-1")
            assertEquals(0, h.challengeCalls, "mode=$mode leaked /auth/challenge")
            assertEquals(0, h.transport.authSessionCalls, "mode=$mode leaked /auth/session")
            assertEquals(0, h.transport.totalCalls, "mode=$mode leaked HTTP")
        }
    }

    // ── Mandatory 4: send/poll/ack fail BEFORE transport dispatch ─────
    @Test
    fun private_send_and_ack_fail_before_transport_dispatch() = runTest {
        val h = harness(PrivacyMode.Standard)
        val orch = orchestrator(h, UnconfinedTestDispatcher(testScheduler))
        // Arm capabilities under Standard so the capability gate cannot
        // mask the egress gate.
        assertTrue(orch.bootstrap().restFallback)
        val baseline = h.transport.totalCalls

        h.mode = PrivacyMode.Private

        val send = orch.sendEnvelope("env-2", "bb".repeat(32), "cGF5bG9hZA==", 2L)
        assertIs<SendOutcome.Failed>(send)
        assertTrue(send.reason.startsWith("egress_policy_"), send.reason)

        val ack = orch.ackInbound("env-2")
        assertIs<AckOutcome.Failed>(ack)
        assertTrue(ack.reason.startsWith("egress_policy_"), ack.reason)

        assertIs<AckOutcome.Failed>(orch.ackInboundAndAdvanceCursor("env-2"))

        assertEquals(
            baseline, h.transport.totalCalls,
            "a blocked operation must not reach the transport at all",
        )
        assertEquals(0, h.transport.sendCalls)
        assertEquals(0, h.transport.pollCalls)
        assertEquals(0, h.transport.ackCalls)
    }

    // ── Mandatory 5: Standard behaviour unchanged ─────────────────────
    @Test
    fun standard_bootstrap_send_ack_behave_as_before() = runTest {
        val h = harness(PrivacyMode.Standard)
        val orch = orchestrator(h, UnconfinedTestDispatcher(testScheduler))
        val caps = orch.bootstrap()
        assertTrue(caps.restFallback, "Standard bootstrap must still enable REST")
        assertEquals(1, h.transport.authSessionCalls)
        assertEquals(1, h.challengeCalls)

        assertIs<SendOutcome.Accepted>(
            orch.sendEnvelope("env-3", "cc".repeat(32), "cGF5bG9hZA==", 3L),
        )
        assertEquals(1, h.transport.sendCalls)

        assertIs<AckOutcome.Acked>(orch.ackInbound("env-3"))
        assertEquals(1, h.transport.ackCalls)
    }

    // ── Mandatory 6+7: Standard -> Private stops polling and a
    //    Standard-minted session cannot be used afterwards ────────────
    @Test
    fun standard_to_private_switch_stops_polling_and_invalidates_session() = runTest {
        val h = harness(PrivacyMode.Standard)
        val orch = orchestrator(h, StandardTestDispatcher(testScheduler))
        orch.bootstrap() // mints a session token under Standard
        runCurrent()
        assertEquals(1, h.transport.authSessionCalls)

        // Drive the machine out of WsActive so the poll loops spawn.
        repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
            orch.submitEventNow(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 1_000L,
                    inboundFrames = 0,
                    pendingAcksAtClose = 1,
                    sessionEpoch = 0L,
                ),
            )
        }
        assertEquals(RestMode.RestActive, orch.stateMachine.state.value)
        orch.start()
        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()
        val pollsUnderStandard = h.transport.pollCalls
        assertTrue(pollsUnderStandard > 0, "poll loop must run under Standard")

        // Runtime switch: the next iteration must STOP the loops, and
        // every later request must refuse even though a fresh Standard
        // session token is still cached.
        h.mode = PrivacyMode.Private
        advanceTimeBy(60_000L)
        runCurrent()
        val pollsJustAfterSwitch = h.transport.pollCalls
        advanceTimeBy(600_000L)
        runCurrent()
        assertEquals(
            pollsJustAfterSwitch, h.transport.pollCalls,
            "polling must stop after the switch, not continue on the stale client",
        )

        val send = orch.sendEnvelope("env-4", "dd".repeat(32), "cGF5bG9hZA==", 4L)
        assertIs<SendOutcome.Failed>(send)
        assertEquals(
            0, h.transport.sendCalls,
            "a session created under Standard must not be usable after the switch",
        )
        orch.close()
        runCurrent()
    }

    // ── Restoration direction: Private -> Standard may resume ─────────
    @Test
    fun private_to_standard_switch_restores_rest_operations() = runTest {
        val h = harness(PrivacyMode.Private)
        val orch = orchestrator(h, UnconfinedTestDispatcher(testScheduler))
        assertFalse(orch.bootstrap().restFallback)
        assertEquals(0, h.transport.totalCalls)

        h.mode = PrivacyMode.Standard
        val caps = orch.bootstrap()
        assertTrue(caps.restFallback, "Standard restore must be able to bootstrap")
        assertIs<SendOutcome.Accepted>(
            orch.sendEnvelope("env-5", "ee".repeat(32), "cGF5bG9hZA==", 5L),
        )
    }

    // ── Mandatory 9: diagnostics carry no identity and no content ─────
    @Test
    fun egress_diagnostics_carry_no_identity_and_no_content() = runTest {
        val h = harness(PrivacyMode.Private)
        val orch = orchestrator(h, UnconfinedTestDispatcher(testScheduler))
        orch.bootstrap()
        orch.sendEnvelope("env-6", "ff".repeat(32), "c2VjcmV0LXBheWxvYWQ=", 6L)
        orch.ackInbound("env-6")
        val egressLines = h.logs.filter { it.startsWith("REST_EGRESS") }
        assertTrue(egressLines.isNotEmpty(), "refusals must be diagnosable")
        for (line in egressLines) {
            assertFalse(line.contains(identity), "identityHex leaked: $line")
            assertFalse(line.contains("c2VjcmV0"), "payload leaked: $line")
            assertFalse(line.contains("env-6"), "envelope id leaked: $line")
        }
    }

    // ── Mandatory 10: unreadable state fails closed ───────────────────
    @Test
    fun unreadable_policy_state_fails_closed() = runTest {
        val h = harness(PrivacyMode.Standard)
        h.throwOnRead = true
        val orch = orchestrator(h, UnconfinedTestDispatcher(testScheduler))
        val caps = orch.bootstrap()
        assertEquals(0, h.transport.totalCalls, "unreadable state must not egress")
        assertFalse(caps.restFallback)
        val send = orch.sendEnvelope("env-7", "aa".repeat(32), "cGF5bG9hZA==", 7L)
        assertIs<SendOutcome.Failed>(send)
        assertTrue(send.reason.contains("AnonymousRequiredButUnavailable"), send.reason)
    }

    // ── Policy unit surface (documents the exact mapping) ─────────────
    @Test
    fun policy_maps_modes_exactly_and_fails_closed_on_null_or_throw() {
        assertEquals(
            RestEgressDecision.DirectAllowed,
            PrivacyModeRestEgressPolicy { PrivacyMode.Standard }.decide(),
        )
        assertEquals(
            RestEgressDecision.AnonymousRequiredButUnavailable,
            PrivacyModeRestEgressPolicy { PrivacyMode.Private }.decide(),
        )
        assertEquals(
            RestEgressDecision.GhostUserTrafficBlocked,
            PrivacyModeRestEgressPolicy { PrivacyMode.Ghost }.decide(),
        )
        assertEquals(
            RestEgressDecision.AnonymousRequiredButUnavailable,
            PrivacyModeRestEgressPolicy { null }.decide(),
        )
        assertEquals(
            RestEgressDecision.AnonymousRequiredButUnavailable,
            PrivacyModeRestEgressPolicy { error("boom") }.decide(),
        )
    }
}
