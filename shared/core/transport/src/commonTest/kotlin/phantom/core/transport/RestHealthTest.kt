// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import phantom.core.crypto.Csprng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stage 2 B8 (2026-09-13): REST health for presentation.
 *
 * Before Stage 2 presentation had no REST input at all — no
 * `RestPollSucceeded` event existed, and a 200 mutated breaker fields
 * only. So "WSS down, REST fine" and "nothing works" were the same
 * banner, which is what the phone showed at 19:08: `Offline — messages
 * queued` while `/relay/poll` was answering 200.
 *
 * The rules these cases pin:
 *  - the ONLY positive source is an authenticated poll classified
 *    `Ok200`, including an empty body; `/relay/ack-deliver` and
 *    `/relay/send` prove nothing;
 *  - a `2xx` proves health only under the network generation it was
 *    dispatched on;
 *  - health expires on its own, so it can never stay `usable` over a
 *    dead link;
 *  - an egress REVOCATION is not a policy block by itself.
 *
 * Review round 7 (2026-09-13) fixed two fixture defects that made the
 * expiry case both non-deterministic and non-discriminating:
 *
 *  - the orchestrator's backoff jitter is drawn from a [Csprng], and the
 *    default is libsodium-backed. In a plain JVM unit test the native
 *    library is not initialised, so the first backoff draw threw and the
 *    case failed for a reason that had nothing to do with REST health.
 *    Every orchestrator built here now takes [FixedJitterCsprng];
 *  - the scripted transport fell back to an empty `200 OK` once its
 *    script ran out, so "64 scripted failures" meant "fail 64 times, then
 *    answer perfectly". A window longer than 64 failed cycles was
 *    therefore re-proved from inside itself, and the case would have
 *    passed over a link that healed. Failure is now a MODE of the
 *    fixture ([ScriptedTransport.failEveryPoll]) that holds for the whole
 *    window.
 *
 * Review round 8 (2026-09-13) localised why the expiry case then stopped
 * finishing at all. Two distinct causes, both fixed here:
 *
 *  - **The job that would not end.** `orch.close()` was the last statement
 *    of each case, so a case that FAILED an assertion skipped it and left
 *    `RestFallbackOrchestrator.pollLoop` and `wsActivePollLoop` --
 *    `pollJob` and `wsActivePollJob` -- rescheduling themselves every
 *    `POLL_FAIL_BACKOFF_MS` on the test scheduler for ever. `runTest`
 *    drains that scheduler during teardown, never reaches idle, and
 *    reports a 60-second timeout instead of the assertion that actually
 *    failed. Every case now runs through [healthTest], which closes what
 *    the case built while the scheduler is still live, so a failure can
 *    surface as a failure.
 *  - **The assertion that could not hold.** A link that throws on every
 *    poll trips the breaker after
 *    `BREAKER_CONSECUTIVE_FAIL_THRESHOLD` = 5 consecutive failures, long
 *    before the 58-second window elapses, and an open breaker publishes
 *    `BreakerOpen`. The expiry timer then declines to overwrite it
 *    (it only replaces `PollOk`), so `Expired` never appeared. The two
 *    properties are now separate cases: the expiry TIMER is pinned with a
 *    relay that answers and proves nothing, which leaves the breaker
 *    closed; the dead LINK is pinned by health becoming unusable and
 *    staying so, which is what the user's banner depends on.
 */
class RestHealthTest {

    private val identity = "aa".repeat(32)

    private class ScriptedTransport(
        private val pollHoldSecs: Int,
        private val restFallback: Boolean = true,
    ) : RestFallbackTransport {
        /** Each entry either returns a response or throws (a transport failure). */
        val pollScript: ArrayDeque<() -> RestFallbackResponse<PollResponse>> = ArrayDeque()

        /**
         * What [poll] does once [pollScript] is exhausted. `null` means the
         * historical default, an empty `200 OK`. Set it through
         * [failEveryPoll] to model a link that stays down: a finite script
         * of failures cannot express that, because the fixture starts
         * answering again the moment the script runs out.
         */
        var pollDefault: (() -> RestFallbackResponse<PollResponse>)? = null
        var pollCalls: Int = 0
        var ackCalls: Int = 0
        var sendCalls: Int = 0

        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> = RestFallbackResponse(
            statusCode = 200,
            bodyParsed = AuthSessionResponse(
                token = "token",
                expiresAt = Long.MAX_VALUE,
                restFallback = restFallback,
                maxSendBodyBytes = 4096,
                pollMaxEnvelopes = 1,
                pollHoldSecs = pollHoldSecs,
                seqMacVerifyKey = "",
            ),
            rawBody = "{}",
            elapsedMs = 1L,
        )

        override suspend fun send(
            url: String,
            token: String,
            idempotencyKey: String,
            body: SendRequest,
        ): RestFallbackResponse<SendResponse> {
            sendCalls += 1
            return RestFallbackResponse(
                statusCode = 201,
                bodyParsed = SendResponse(ok = 1),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        }

        override suspend fun ackDeliver(
            url: String,
            token: String,
            body: AckDeliverRequest,
        ): RestFallbackResponse<AckDeliverResponse> {
            ackCalls += 1
            return RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AckDeliverResponse(ok = 1),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        }

        override suspend fun poll(
            url: String,
            token: String,
            sinceSeq: Long?,
            longPollOptIn: Boolean,
            readTimeoutMs: Long?,
        ): RestFallbackResponse<PollResponse> {
            pollCalls += 1
            val next = pollScript.removeFirstOrNull() ?: pollDefault ?: return emptyOk()
            return next()
        }

        /**
         * From now on every poll throws [reason], for as long as the
         * fixture lives. The counterpart of a scripted failure: a window
         * assertion needs the link to be down for the WHOLE window, and
         * the number of poll attempts inside one is a function of the
         * backoff, not something a test should have to predict.
         */
        fun failEveryPoll(reason: String) {
            pollDefault = { throw IllegalStateException(reason) }
        }

        /**
         * From now on every poll is answered with [code], for as long as
         * the fixture lives.
         *
         * The counterpart of [failEveryPoll] for the cases that must NOT
         * trip the breaker: a `4xx-other` is the relay ANSWERING, so it is
         * recorded as a non-failure and the breaker stays closed -- while
         * proving nothing about health, because only `Ok200` does that.
         * That is what makes the expiry timer observable on its own.
         */
        fun answerEveryPollWith(code: Int) {
            pollDefault = { status(code) }
        }

        fun emptyOk(): RestFallbackResponse<PollResponse> = RestFallbackResponse(
            statusCode = 200,
            bodyParsed = PollResponse(),
            rawBody = "{}",
            elapsedMs = 1L,
        )

        fun status(code: Int): RestFallbackResponse<PollResponse> = RestFallbackResponse(
            statusCode = code,
            bodyParsed = null,
            rawBody = "{}",
            elapsedMs = 1L,
        )
    }

    /**
     * Deterministic stand-in for the production [Csprng].
     *
     * Returns the MIDDLE bucket of whatever bound it is given, so
     * `RestFallbackOrchestrator.jitterFactorFor` maps it to exactly 1.0
     * and every backoff in these cases is its nominal value. Two things
     * follow: the virtual-time arithmetic in the assertions is exact, and
     * no case depends on libsodium being initialised in the unit-test JVM.
     */
    private class FixedJitterCsprng : Csprng {
        var draws: Int = 0
        override fun bytes(byteCount: Int): ByteArray =
            error("REST health fixture — bytes() is not on the jitter path")
        override fun hex(byteCount: Int): String =
            error("REST health fixture — hex() is not on the jitter path")
        override fun uniformLong(boundExclusive: Long): Long {
            draws += 1
            return boundExclusive / 2
        }
    }

    /**
     * Every orchestrator this fixture builds, so [healthTest] can close
     * them even when a case fails before its own `close()`.
     */
    private val built = mutableListOf<RestFallbackOrchestrator>()

    /**
     * `runTest`, plus the guarantee that nothing this case built is still
     * running when the case ends.
     *
     * The close has to happen HERE, inside `runTest`: the poll loops live
     * on the test scheduler, and a `close()` issued after `runTest` has
     * returned would wait for a scheduler that is no longer being driven.
     * `close()` is idempotent, so a case that closes explicitly is
     * unaffected.
     */
    private fun healthTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            built.forEach { orch -> runCatching { orch.close() } }
            built.clear()
        }
    }

    private fun orchestrator(
        transport: ScriptedTransport,
        scheduler: TestCoroutineScheduler,
        gate: RestEgressGate = RestEgressGate(RestEgressPolicy { RestEgressDecision.DirectAllowed }),
        longPollEnabled: Boolean = true,
        csprng: Csprng = FixedJitterCsprng(),
    ): RestFallbackOrchestrator = RestFallbackOrchestrator(
        egressGate = gate,
        baseUrl = "https://relay.test",
        identityHex = identity,
        signingPubkeyHex = "bb".repeat(32),
        getChallenge = { _ -> "cc".repeat(32) },
        signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
        transport = transport,
        now = { scheduler.currentTime },
        longPollEnabled = longPollEnabled,
        dispatcher = StandardTestDispatcher(scheduler),
        csprng = csprng,
    ).also { built += it }

    // ── The window ───────────────────────────────────────────────────────────

    @Test
    fun the_window_is_one_failed_cycle_plus_the_next_successful_poll() = healthTest {
        // A call, successful or not, is bounded by the read timeout
        // `max((hold + 5) s, 10 s)`; the gap between polls is bounded by
        // `POLL_LONG_IDLE_MS * 1.2`. One failed cycle followed by a success
        // therefore spans `2 * (18 s + readTimeout)`, plus a 2 s margin.
        for ((hold, expected) in listOf(0 to 58_000L, 30 to 108_000L, 480 to 1_008_000L)) {
            val transport = ScriptedTransport(pollHoldSecs = hold)
            val orch = orchestrator(transport, testScheduler)
            orch.bootstrap()
            assertEquals(
                expected, orch.restHealthWindowMs(),
                "hold=$hold: a window shorter than one failed cycle would expire inside it",
            )
            orch.close()
        }
    }

    // ── What proves health ───────────────────────────────────────────────────

    @Test
    fun an_empty_authenticated_poll_2xx_is_usable_rest() = healthTest {
        val transport = ScriptedTransport(pollHoldSecs = 0)
        val orch = orchestrator(transport, testScheduler)
        orch.bootstrap()
        assertFalse(orch.restHealth.value.usable, "nothing is proven before the first poll")
        assertEquals(RestHealthReason.Unknown, orch.restHealth.value.reason)
        orch.start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(transport.pollCalls > 0, "the loop must have polled")
        assertTrue(orch.restHealth.value.usable, "an empty body is still an answer from the relay")
        assertEquals(RestHealthReason.PollOk, orch.restHealth.value.reason)
        orch.close()
    }

    @Test
    fun an_ack_or_a_send_alone_never_makes_rest_usable() = healthTest {
        val transport = ScriptedTransport(pollHoldSecs = 0)
        val orch = orchestrator(transport, testScheduler, longPollEnabled = false)
        orch.bootstrap()
        // Neither of these is a poll: they prove the relay answered THIS
        // request, not that the inbound path is delivering.
        orch.sendEnvelope(
            envelopeId = "e1",
            toHex = "ff".repeat(32),
            payloadBase64 = "",
            sequenceTs = 1L,
        )
        orch.ackInbound("e1")
        runCurrent()
        assertTrue(transport.sendCalls > 0 || transport.ackCalls > 0)
        assertFalse(
            orch.restHealth.value.usable,
            "health from any REST success would call a relay that refuses to serve us healthy",
        )
        orch.close()
    }

    @Test
    fun a_401_does_not_prove_health_even_though_the_relay_answered() = healthTest {
        val transport = ScriptedTransport(pollHoldSecs = 0)
        // EVERY poll, not a scripted one: the orchestrator runs two poll
        // loops, and a single scripted 401 followed by the empty-OK
        // default would let whichever loop polled second prove health
        // and pass this case over a relay that is refusing us.
        transport.pollDefault = { transport.status(401) }
        val orch = orchestrator(transport, testScheduler)
        orch.bootstrap()
        orch.start()
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertFalse(orch.restHealth.value.usable, "a token problem is not a delivering path")
        orch.close()
    }

    // ── Expiry ───────────────────────────────────────────────────────────────

    @Test
    fun health_expires_without_a_new_poll_ok() = healthTest {
        val transport = ScriptedTransport(pollHoldSecs = 0)
        val orch = orchestrator(transport, testScheduler)
        orch.bootstrap()
        orch.start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(orch.restHealth.value.usable)

        // From here the relay ANSWERS every poll, and proves nothing: a
        // `4xx-other` is recorded as a non-failure, so the breaker stays
        // closed and cannot publish a reason of its own, and only `Ok200`
        // ever proves health. What is left to move the state is the expiry
        // timer, which is what this case is named for.
        //
        // A FINITE script would not pin this: the fixture would answer
        // `200 OK` again as soon as it ran out, and the window would be
        // re-proved from inside itself.
        val pollsBefore = transport.pollCalls
        transport.answerEveryPollWith(418)
        advanceTimeBy(orch.restHealthWindowMs() + 5_000)
        runCurrent()
        assertTrue(
            transport.pollCalls > pollsBefore,
            "the loop must have kept trying across the window; it polled " +
                "${transport.pollCalls - pollsBefore} times",
        )
        assertFalse(
            orch.restHealth.value.usable,
            "usable health over a path that answers but delivers nothing is exactly " +
                "the 19:08 banner, inverted",
        )
        assertEquals(
            RestHealthReason.Expired, orch.restHealth.value.reason,
            "the expiry timer is the thing under test here, so nothing else may have " +
                "published the reason",
        )
        orch.close()
    }

    @Test
    fun health_does_not_survive_a_dead_link() = healthTest {
        // The other half, and the one the user feels: the link is GONE,
        // every poll throws, and it throws for the whole window rather
        // than for a scripted number of cycles.
        val transport = ScriptedTransport(pollHoldSecs = 0)
        val orch = orchestrator(transport, testScheduler)
        orch.bootstrap()
        orch.start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(orch.restHealth.value.usable)

        val pollsBefore = transport.pollCalls
        transport.failEveryPoll("network down")
        advanceTimeBy(orch.restHealthWindowMs() + 5_000)
        runCurrent()

        assertTrue(
            transport.pollCalls > pollsBefore,
            "the loop must have kept trying; health that expired because nothing was " +
                "attempted would prove nothing about a dead link",
        )
        assertFalse(
            orch.restHealth.value.usable,
            "health must not stay usable over a link that answers nothing",
        )
        assertTrue(
            orch.restHealth.value.reason in setOf(
                RestHealthReason.Expired,
                RestHealthReason.BreakerOpen,
                RestHealthReason.HalfOpen,
            ),
            "and the reason must be one of the three that mean `nothing is proving " +
                "this path`. A continuously dead link trips the breaker after " +
                "5 consecutive failures, well inside the window, so `BreakerOpen` is " +
                "the reason in practice -- an open breaker means nothing is even being " +
                "asked. Got ${orch.restHealth.value}",
        )

        // And it must STAY unusable: nothing re-proves a link that is
        // still dead.
        advanceTimeBy(orch.restHealthWindowMs())
        runCurrent()
        assertFalse(orch.restHealth.value.usable, "still dead, still unusable")
        orch.close()
    }

    @Test
    fun one_failed_cycle_does_not_expire_health() = healthTest {
        val transport = ScriptedTransport(pollHoldSecs = 0)
        val orch = orchestrator(transport, testScheduler)
        orch.bootstrap()
        orch.start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(orch.restHealth.value.usable)
        // One failure, then the empty-OK default resumes.
        transport.pollScript += { throw IllegalStateException("one bad cycle") }
        advanceTimeBy(orch.restHealthWindowMs() / 2)
        runCurrent()
        assertTrue(
            orch.restHealth.value.usable,
            "a single failed cycle is inside the window by construction",
        )
        orch.close()
    }

    @Test
    fun stopping_and_closing_publish_stopped() = healthTest {
        val transport = ScriptedTransport(pollHoldSecs = 0)
        val orch = orchestrator(transport, testScheduler)
        orch.bootstrap()
        orch.start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(orch.restHealth.value.usable)
        orch.stop()
        assertFalse(orch.restHealth.value.usable)
        assertEquals(RestHealthReason.Stopped, orch.restHealth.value.reason)
        orch.close()
        assertEquals(RestHealthReason.Stopped, orch.restHealth.value.reason)
    }

    @Test
    fun a_stale_expiry_timer_publishes_nothing_after_a_restart() = healthTest {
        val transport = ScriptedTransport(pollHoldSecs = 0)
        val orch = orchestrator(transport, testScheduler)
        orch.bootstrap()
        orch.start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(orch.restHealth.value.usable)

        // A restart cancels and joins the armed expiry; the fresh run proves
        // health again. The old timer's body must not expire the new proof.
        orch.stop()
        orch.start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(orch.restHealth.value.usable, "the restarted loop proved health again")
        advanceTimeBy(orch.restHealthWindowMs() / 2)
        runCurrent()
        assertTrue(
            orch.restHealth.value.usable,
            "a timer from the previous run must not expire this proof",
        )
        orch.close()
    }

    // ── Network generation ───────────────────────────────────────────────────

    @Test
    fun a_network_change_invalidates_health_at_once() = healthTest {
        val transport = ScriptedTransport(pollHoldSecs = 0)
        val orch = orchestrator(transport, testScheduler)
        orch.bootstrap()
        orch.start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(orch.restHealth.value.usable)

        orch.noteNetworkChanged(networkGeneration = 1L)
        assertFalse(orch.restHealth.value.usable, "nothing about the old network survives it")
        assertEquals(RestHealthReason.NetworkChanged, orch.restHealth.value.reason)
        assertEquals(1L, orch.restHealth.value.networkGeneration)

        // A poll answering on the NEW generation re-proves.
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(orch.restHealth.value.usable)
        assertEquals(1L, orch.restHealth.value.networkGeneration)
        orch.close()
    }

    // ── Breaker ──────────────────────────────────────────────────────────────

    @Test
    fun an_open_breaker_is_not_a_usable_path() = healthTest {
        val transport = ScriptedTransport(pollHoldSecs = 0)
        val orch = orchestrator(transport, testScheduler)
        orch.bootstrap()
        orch.start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(orch.restHealth.value.usable)

        // The link stays down, so consecutive failures trip the breaker and
        // KEEP it tripped — a finite script would have healed underneath the
        // assertion.
        transport.failEveryPoll("5xx-ish")
        advanceTimeBy(120_000)
        runCurrent()
        assertFalse(orch.restHealth.value.usable)
        assertTrue(
            orch.restHealth.value.reason in setOf(
                RestHealthReason.BreakerOpen,
                RestHealthReason.HalfOpen,
                RestHealthReason.Expired,
            ),
            "an open or half-open breaker means nothing is being asked; got ${orch.restHealth.value}",
        )
        orch.close()
    }

    // ── Egress: a revocation is not a policy block ───────────────────────────

    @Test
    fun a_revocation_under_a_policy_block_publishes_egress_blocked() = healthTest {
        var mode: PrivacyMode? = PrivacyMode.Standard
        val gate = RestEgressGate(PrivacyModeRestEgressPolicy { mode })
        val transport = ScriptedTransport(pollHoldSecs = 0)
        val orch = orchestrator(transport, testScheduler, gate = gate)
        orch.bootstrap()
        orch.start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(orch.restHealth.value.usable)

        mode = PrivacyMode.Private
        gate.revokeAndJoin("privacy_mode_change")
        assertFalse(orch.restHealth.value.usable)
        assertEquals(
            RestHealthReason.EgressBlocked, orch.restHealth.value.reason,
            "in Private the gate refuses direct REST, and presentation must say so",
        )
        orch.close()
    }

    @Test
    fun a_revocation_without_a_policy_block_is_merely_unknown() = healthTest {
        val mode = PrivacyMode.Standard
        val gate = RestEgressGate(PrivacyModeRestEgressPolicy { mode })
        val transport = ScriptedTransport(pollHoldSecs = 0)
        val orch = orchestrator(transport, testScheduler, gate = gate)
        orch.bootstrap()
        orch.start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(orch.restHealth.value.usable)

        gate.revokeAndJoin("some_other_reason")
        assertFalse(orch.restHealth.value.usable)
        assertEquals(
            RestHealthReason.Unknown, orch.restHealth.value.reason,
            "a revocation by itself refuses nothing: the policy still allows direct REST",
        )
        // And the next poll proves health again.
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(orch.restHealth.value.usable)
        orch.close()
    }

    @Test
    fun a_generation_invalidate_does_not_touch_health() = healthTest {
        val gate = RestEgressGate(RestEgressPolicy { RestEgressDecision.DirectAllowed })
        val transport = ScriptedTransport(pollHoldSecs = 0)
        val orch = orchestrator(transport, testScheduler, gate = gate)
        orch.bootstrap()
        orch.start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        val before = orch.restHealth.value
        assertTrue(before.usable)

        // `invalidate` bumps the generation without cancelling anything and
        // has no production caller; it must not masquerade as a block.
        gate.invalidate("test")
        assertEquals(before, orch.restHealth.value)
        orch.close()
    }
}
