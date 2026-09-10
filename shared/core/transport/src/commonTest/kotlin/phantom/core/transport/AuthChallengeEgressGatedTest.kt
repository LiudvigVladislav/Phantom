// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * N1-F2 R-N1.3 P1-1 — the auth challenge has its OWN egress boundary.
 *
 * `/auth/challenge` carries the long-term identity in its query string.
 * R-N1.2 wrapped only `/auth/session` in `egressGate.dispatch`; the
 * challenge was covered solely by the cheap `egressBlocked("token_…")`
 * pre-check further up `acquireOrRefreshToken`. That is not a boundary:
 * it is one read, taken once, well before the request. A posture change
 * landing after it sent the identity anyway, and because the challenge
 * held no lease, `revokeAndJoin` could neither cancel nor wait for it.
 *
 * Proving this needs care. In steady-state Private the pre-check
 * already blocks everything, so a test that simply asserts "Private
 * performs zero challenge calls" passes with OR without the fix — it is
 * vacuous, and it is exactly the kind of test that let the gap through.
 *
 * So this test makes the policy flip from Standard to Private after a
 * given number of reads, and searches for an interleaving where the
 * pre-check PASSED (proved by the `token_refresh_start` trace) and the
 * challenge still did not reach the network. Such an interleaving can
 * only exist if the challenge re-reads the policy at its own dispatch.
 * Remove the `dispatch("auth_challenge")` wrapper and no flip point
 * produces it, so this test fails.
 */
class AuthChallengeEgressGatedTest {

    private class CountingTransport : RestFallbackTransport {
        var authSessionCalls = 0

        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> {
            authSessionCalls++
            return RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AuthSessionResponse(
                    token = "tok",
                    expiresAt = 3_600_000L,
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
        ): RestFallbackResponse<SendResponse> =
            RestFallbackResponse(201, SendResponse(1), "{}", 1L)

        override suspend fun poll(
            url: String,
            token: String,
            sinceSeq: Long?,
            longPollOptIn: Boolean,
            readTimeoutMs: Long?,
        ): RestFallbackResponse<PollResponse> =
            RestFallbackResponse(200, PollResponse(emptyList(), false), "{}", 1L)

        override suspend fun ackDeliver(
            url: String,
            token: String,
            body: AckDeliverRequest,
        ): RestFallbackResponse<AckDeliverResponse> =
            RestFallbackResponse(200, AckDeliverResponse(1), "{}", 1L)
    }

    private class Run(
        val preCheckPassed: Boolean,
        val challengeCalls: Int,
        val authSessionCalls: Int,
    )

    /**
     * Drive one bootstrap with a policy that returns Standard for the
     * first [flipAfterReads] reads and Private from then on.
     */
    private suspend fun runWithFlip(flipAfterReads: Int): Run {
        var reads = 0
        var challengeCalls = 0
        val logs = mutableListOf<String>()
        val transport = CountingTransport()

        val gate = RestEgressGate(
            PrivacyModeRestEgressPolicy {
                reads++
                if (reads <= flipAfterReads) PrivacyMode.Standard else PrivacyMode.Private
            },
            log = { logs.add(it) },
        )

        val orchestrator = RestFallbackOrchestrator(
            egressGate = gate,
            baseUrl = "https://relay.test",
            identityHex = "aa".repeat(32),
            signingPubkeyHex = "bb".repeat(32),
            getChallenge = { _ ->
                challengeCalls++
                "cc".repeat(32)
            },
            signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
            transport = transport,
            now = { 0L },
            log = { logs.add(it) },
            dispatcher = UnconfinedTestDispatcher(),
        )

        runCatching { orchestrator.bootstrap() }

        return Run(
            preCheckPassed = logs.any { "token_refresh_start" in it },
            challengeCalls = challengeCalls,
            authSessionCalls = transport.authSessionCalls,
        )
    }

    @Test
    fun the_challenge_is_refused_at_its_own_dispatch_after_the_precheck_passed() = runTest {
        val runs = (0..8).map { it to runWithFlip(it) }

        // The instrument itself must work: at least one flip point has to
        // get past the pre-check, otherwise this test proves nothing.
        assertTrue(
            runs.any { it.second.preCheckPassed },
            "no flip point got past the token pre-check — the harness is not exercising the " +
                "path under test",
        )

        val decisive = runs.filter { it.second.preCheckPassed && it.second.challengeCalls == 0 }
        assertTrue(
            decisive.isNotEmpty(),
            "no interleaving existed where the pre-check passed and the challenge was still " +
                "refused. That means the challenge has no boundary of its own: once the " +
                "pre-check is behind us, the identity-bearing GET goes out regardless of the " +
                "live posture. Flip points examined: " +
                runs.joinToString { "n=${it.first} pre=${it.second.preCheckPassed} " +
                    "challenge=${it.second.challengeCalls}" },
        )

        // Whenever the challenge was refused, nothing downstream may have run.
        for ((_, r) in decisive) {
            assertEquals(
                0, r.authSessionCalls,
                "a refused challenge must not be followed by an auth/session call",
            )
        }
    }

    @Test
    fun steady_state_private_performs_zero_challenge_and_zero_session() = runTest {
        val r = runWithFlip(flipAfterReads = 0)
        assertEquals(0, r.challengeCalls, "Private must not fetch a challenge")
        assertEquals(0, r.authSessionCalls, "Private must not open a session")
    }

    @Test
    fun steady_state_standard_reaches_both_challenge_and_session() = runTest {
        // Non-vacuity control: with the policy pinned to Standard the
        // very same path DOES reach the network.
        val r = runWithFlip(flipAfterReads = Int.MAX_VALUE)
        assertTrue(r.preCheckPassed, "Standard must get past the pre-check")
        assertEquals(1, r.challengeCalls, "Standard must fetch exactly one challenge")
        assertEquals(1, r.authSessionCalls, "Standard must open exactly one session")
    }
}
