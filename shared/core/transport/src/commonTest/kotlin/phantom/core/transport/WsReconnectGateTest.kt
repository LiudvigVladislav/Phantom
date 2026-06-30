// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RC-RECONNECT-QUIESCENCE1 Commit 2a (2026-06-22) — load-bearing tests for
 * the [WsReconnectGate] state machine on [RestStateMachine]. Each test
 * pins a single invariant the upcoming transport-side integration relies
 * on. The transport-side behavioural tests (forceReconnect respects
 * quiescence; runReconnectLoop observes gate; full Tele2 → Wi-Fi
 * end-to-end) land in Commit 2b alongside the integration code.
 */
class WsReconnectGateTest {

    private fun newSm(
        nowProvider: () -> Long = { 0L },
        currentKind: TransportKind? = TransportKind.Direct,
        tokenSequence: List<Long> = listOf(0xDEAD_BEEF_CAFE_BABEuL.toLong()),
    ): RestStateMachine {
        var tokenIdx = 0
        return RestStateMachine(
            now = nowProvider,
            log = { /* silent */ },
            mode2FastPathEnabled = true,
            mode2StickyEnabled = true,
            reconnectQuiescenceEnabled = true,
            currentKindProvider = { currentKind },
            tokenSource = { tokenSequence[tokenIdx++ % tokenSequence.size] },
        )
    }

    /** Drive the SM from Open → Quiesced via the mode-2-fast-path sticky-arm. */
    private suspend fun armSticky(sm: RestStateMachine) {
        sm.onEvent(
            RestStateMachine.Event.WsSessionEnded(
                durationMs = 31_000,
                inboundFrames = 0,
                pendingAcksAtClose = 0,
                okhttpPingTimeoutDetected = true,
                sessionEpoch = 1L,
            ),
        )
    }

    // ── Direct-only fence ────────────────────────────────────────────────────

    @Test
    fun gate_engages_only_on_TransportKind_Direct() = runBlocking {
        val smDirect = newSm(currentKind = TransportKind.Direct)
        armSticky(smDirect)
        val g = smDirect.gate.value
        assertTrue(g is WsReconnectGate.Quiesced, "Direct ⇒ gate Quiesced; got $g")

        val smReality = newSm(currentKind = TransportKind.Reality)
        armSticky(smReality)
        assertEquals(
            WsReconnectGate.Open, smReality.gate.value,
            "Reality ⇒ gate stays Open even though sticky armed",
        )

        val smTor = newSm(currentKind = TransportKind.Tor)
        armSticky(smTor)
        assertEquals(
            WsReconnectGate.Open, smTor.gate.value,
            "Tor ⇒ gate stays Open even though sticky armed",
        )
    }

    // ── Probe atomicity: single-use under concurrency ────────────────────────

    @Test
    fun probe_is_single_use_under_concurrent_claim() = runBlocking {
        val sm = newSm()
        armSticky(sm)
        val routeEpoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        val issue = sm.issueProbeAfterRewalk(routeEpoch)
        assertTrue(issue is ProbeIssueResult.ProbeIssued, "probe issued; got $issue")

        // Two concurrent claimers — only the latest-allocated owner
        // can win (P1 #3 seventh round: strict currency check). The
        // earlier-allocated caller fails with OWNER_GENERATION_STALE.
        val ownerEarly = sm.allocateConnectionGeneration()   // counter=1
        val ownerLatest = sm.allocateConnectionGeneration()  // counter=2
        val results = listOf(ownerEarly, ownerLatest).map { gen ->
            async { sm.awaitAndClaimProbe(ownerGeneration = gen) }
        }.awaitAll()
        val winners = results.filterIsInstance<ClaimResult.Claimed>()
        val losers = results.filterIsInstance<ClaimResult.Failure>()
        assertEquals(1, winners.size, "exactly one claimer wins; results=$results")
        assertEquals(1, losers.size, "exactly one claimer loses; results=$results")
        // The loser is the stale (older) owner OR the second observer
        // of an already-claimed probe — both are correct outcomes
        // under the strict-currency contract.
        val loserReason = (losers.single() as ClaimResult.Failure).reason
        assertTrue(
            loserReason == ClaimFailureReason.OWNER_GENERATION_STALE ||
                loserReason == ClaimFailureReason.PROBE_ALREADY_CLAIMED,
            "loser reason must be OWNER_GENERATION_STALE or PROBE_ALREADY_CLAIMED; got $loserReason",
        )
    }

    @Test
    fun second_attempt_to_claim_after_first_win_returns_PROBE_ALREADY_CLAIMED() = runBlocking {
        val sm = newSm()
        armSticky(sm)
        val routeEpoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(routeEpoch)
        val owner = sm.allocateConnectionGeneration()
        val first = sm.awaitAndClaimProbe(ownerGeneration = owner)
        assertTrue(first is ClaimResult.Claimed, "first claim succeeds")
        // A SECOND attempt by the same latest-counter owner observes
        // ProbeClaimed and fails with PROBE_ALREADY_CLAIMED.
        val second = sm.awaitAndClaimProbe(ownerGeneration = owner)
        assertTrue(second is ClaimResult.Failure, "second claim fails")
        assertEquals(ClaimFailureReason.PROBE_ALREADY_CLAIMED, (second as ClaimResult.Failure).reason)
    }

    // ── Route-epoch staleness ────────────────────────────────────────────────

    @Test
    fun issueProbeAfterRewalk_rejects_stale_routeEpoch() = runBlocking {
        val sm = newSm()
        armSticky(sm)
        val firstEpoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        val secondEpoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        // Coordinator calls issueProbeAfterRewalk with the OLD epoch.
        val result = sm.issueProbeAfterRewalk(firstEpoch)
        assertTrue(result is ProbeIssueResult.Rejected)
        assertEquals(ProbeIssueRejectReason.ROUTE_EPOCH_STALE, (result as ProbeIssueResult.Rejected).reason)
        // Newer epoch issues successfully.
        val ok = sm.issueProbeAfterRewalk(secondEpoch)
        assertTrue(ok is ProbeIssueResult.ProbeIssued)
    }

    // ── Type-level token absence in CandidateProving ─────────────────────────

    @Test
    fun token_is_consumed_and_absent_from_CandidateProving() = runBlocking {
        val sm = newSm(tokenSequence = listOf(0xABCDL))
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        // Need a network change to enter sticky-recovery pending state
        // before the claim → Connected transition becomes a CandidateProving.
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        val owner = sm.allocateConnectionGeneration(); val claim = sm.awaitAndClaimProbe(ownerGeneration = owner)
        assertTrue(claim is ClaimResult.Claimed)
        // Candidate session opens.
        sm.onEvent(
            RestStateMachine.Event.WsSessionConnected(
                sessionEpoch = 42L,
                connectionGeneration = 1L,
            )
        )
        val g = sm.gate.value
        assertTrue(g is WsReconnectGate.CandidateProving, "expected CandidateProving; got $g")
        // Type-level check: CandidateProving has NO `token` field. Use
        // reflection (Kotlin: the data class has only stickyGen +
        // sessionEpoch). We assert by destructuring component count.
        val candidateProving = g as WsReconnectGate.CandidateProving
        assertEquals(42L, candidateProving.sessionEpoch)
        // Compile-time absence: WsReconnectGate.CandidateProving.token
        // does not exist. If it ever did, the data-class field count
        // would change. Pinning componentN:
        val c1 = candidateProving.component1()  // stickyGen
        val c2 = candidateProving.component2()  // sessionEpoch
        assertEquals(1, c1)
        assertEquals(42L, c2)
    }

    // ── Dead recovery candidate returns to Quiesced ──────────────────────────

    @Test
    fun candidate_death_returns_gate_to_Quiesced_with_same_stickyGen() = runBlocking {
        val sm = newSm()
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        sm.awaitAndClaimProbe(ownerGeneration = sm.allocateConnectionGeneration())
        sm.onEvent(
            RestStateMachine.Event.WsSessionConnected(
                sessionEpoch = 7L,
                connectionGeneration = 1L,
            )
        )
        assertTrue(sm.gate.value is WsReconnectGate.CandidateProving)
        // Candidate dies.
        sm.onEvent(
            RestStateMachine.Event.WsSessionEnded(
                durationMs = 4_000,
                inboundFrames = 0,
                pendingAcksAtClose = 0,
                okhttpPingTimeoutDetected = false,
                sessionEpoch = 7L,
            ),
        )
        val g = sm.gate.value
        assertTrue(g is WsReconnectGate.Quiesced, "candidate death ⇒ Quiesced; got $g")
        // sticky generation preserved.
        assertEquals(1, (g as WsReconnectGate.Quiesced).stickyGen)
    }

    // ── sticky_cleared → Open ────────────────────────────────────────────────

    @Test
    fun ws_alive_60s_flips_gate_to_Open() = runBlocking {
        var t = 0L
        val sm = newSm(nowProvider = { t })
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        sm.awaitAndClaimProbe(ownerGeneration = sm.allocateConnectionGeneration())
        sm.onEvent(
            RestStateMachine.Event.WsSessionConnected(
                sessionEpoch = 11L,
                connectionGeneration = 1L,
            )
        )
        // 60s tick.
        t = 60_001L
        sm.onEvent(RestStateMachine.Event.WsAliveTickElapsed)
        assertEquals(WsReconnectGate.Open, sm.gate.value)
    }

    // ── Budget exhaustion via attempt count ──────────────────────────────────

    @Test
    fun probe_budget_exhausts_via_attempt_count() = runBlocking {
        val sm = newSm()
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        val owner = sm.allocateConnectionGeneration()
        val claim = sm.awaitAndClaimProbe(ownerGeneration = owner) as ClaimResult.Claimed
        // Record 5 failed attempts; 5th must flip to Quiesced.
        repeat(5) { i ->
            sm.recordProbeAttemptFailed(permit = claim.probe, reason = "auth_failed_$i")
        }
        val g = sm.gate.value
        assertTrue(g is WsReconnectGate.Quiesced, "budget exhausted ⇒ Quiesced; got $g")
    }

    @Test
    fun probe_budget_exhausts_via_wall_clock() = runBlocking {
        var t = 0L
        val sm = newSm(nowProvider = { t })
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        val owner = sm.allocateConnectionGeneration()
        val claim = sm.awaitAndClaimProbe(ownerGeneration = owner) as ClaimResult.Claimed
        // Advance clock past 120s; one attempt is enough to trip.
        t = 120_001L
        sm.recordProbeAttemptFailed(permit = claim.probe, reason = "slow_handshake")
        val g = sm.gate.value
        assertTrue(g is WsReconnectGate.Quiesced, "wall-clock budget ⇒ Quiesced; got $g")
    }

    // ── Permit acquisition: Open returns OpenPermit ─────────────────────────

    @Test
    fun awaitReconnectPermit_returns_OpenPermit_when_gate_is_Open() = runBlocking {
        val sm = newSm()
        val permit = sm.awaitReconnectPermit(ownerGeneration = 7L)
        assertTrue(permit is WsReconnectPermit.OpenPermit, "expected OpenPermit; got $permit")
        val open = permit as WsReconnectPermit.OpenPermit
        assertEquals(0L, open.routeEpoch, "routeEpoch stamped at observe time (untouched)")
        assertEquals(7L, open.ownerGeneration, "ownerGeneration carries through")
    }

    @Test
    fun validatePermitAfterAuth_returns_false_after_routeEpoch_rolls() = runBlocking {
        val sm = newSm()
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        val owner = sm.allocateConnectionGeneration(); val claim = sm.awaitAndClaimProbe(ownerGeneration = owner) as ClaimResult.Claimed
        // Concurrent rewalk rolls routeEpoch.
        sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        assertFalse(
            sm.validatePermitAfterAuth(claim.probe),
            "claim must invalidate after concurrent routeEpoch roll",
        )
    }

    // ── lastRewalkAtMs rate-limit invariant (locked 2026-06-22):
    //    failed teardown does NOT eat the rate-limit budget. The actual
    //    rate-limit ENFORCEMENT lives in TransportRewalkCoordinator;
    //    state-machine-side this manifests as `revokeRouteChange` NOT
    //    advancing any internal clock the coordinator would see. We pin
    //    that revokeRouteChange has NO side effect on issueProbeAfterRewalk
    //    or claim mechanics — i.e. a future rewalk on a fresh epoch
    //    succeeds normally even after multiple revocations.

    @Test
    fun multiple_route_change_revocations_do_not_block_subsequent_probe_issuance() = runBlocking {
        val sm = newSm()
        armSticky(sm)
        val first = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.revokeRouteChange(first, reason = "disconnect_join_timeout")
        val second = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.revokeRouteChange(second, reason = "release_failed")
        val third = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        val ok = sm.issueProbeAfterRewalk(third)
        assertTrue(ok is ProbeIssueResult.ProbeIssued, "third attempt succeeds; got $ok")
    }

    // ── revokeProbe flips ProbeAvailable back to Quiesced ────────────────────

    @Test
    fun revokeProbe_returns_gate_to_Quiesced() = runBlocking {
        val sm = newSm()
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        assertTrue(sm.gate.value is WsReconnectGate.ProbeAvailable)
        sm.revokeProbe(epoch, reason = "service_restart_failed")
        val g = sm.gate.value
        assertTrue(g is WsReconnectGate.Quiesced, "revokeProbe ⇒ Quiesced; got $g")
    }

    @Test
    fun revokeProbe_no_op_if_gate_already_advanced() = runBlocking {
        val sm = newSm()
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        sm.awaitAndClaimProbe(ownerGeneration = sm.allocateConnectionGeneration())
        assertTrue(sm.gate.value is WsReconnectGate.ProbeClaimed)
        sm.revokeProbe(epoch, reason = "late_revoke")
        // Still ProbeClaimed — revokeProbe must not yank an already-bound claim.
        assertTrue(sm.gate.value is WsReconnectGate.ProbeClaimed)
    }

    // ── Mandatory regression tests (locked 2026-06-22) ───────────────────────

    @Test
    fun OpenPermit_rejected_after_gate_then_routeEpoch_then_gate_ABA_cycle() = runBlocking {
        var t = 0L
        val sm = newSm(nowProvider = { t })
        // Loop A allocates and observes Open.
        val ownerA = sm.allocateConnectionGeneration()
        val permitA = sm.awaitReconnectPermit(ownerGeneration = ownerA)
        assertTrue(permitA is WsReconnectPermit.OpenPermit)

        // Full ABA cycle: Open → Quiesced → ProbeAvailable → ProbeClaimed →
        // CandidateProving → Open.
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        val ownerB = sm.allocateConnectionGeneration()
        sm.awaitAndClaimProbe(ownerGeneration = ownerB)
        sm.onEvent(
            RestStateMachine.Event.WsSessionConnected(
                sessionEpoch = 99L,
                connectionGeneration = ownerB,
            )
        )
        t = 60_001L
        sm.onEvent(RestStateMachine.Event.WsAliveTickElapsed)
        assertEquals(WsReconnectGate.Open, sm.gate.value, "gate is Open again post-recovery")

        // Loop A finishes auth and tries to validate its stale OpenPermit.
        // routeEpoch has advanced + connectionGenerationCounter has advanced.
        assertFalse(
            sm.validatePermitAfterAuth(permitA),
            "stale OpenPermit MUST be rejected after a full ABA cycle (routeEpoch " +
                "and/or connectionGenerationCounter have advanced)",
        )
    }

    @Test
    fun new_probe_issues_after_routeEpoch_reset_from_ProbeAvailable() = runBlocking {
        val sm = newSm()
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        assertTrue(sm.gate.value is WsReconnectGate.ProbeAvailable)

        // A second beginRouteChange MUST flip the stuck ProbeAvailable back
        // to Quiesced atomically with the routeEpoch bump.
        val freshEpoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        assertTrue(
            sm.gate.value is WsReconnectGate.Quiesced,
            "beginRouteChange must atomically flip ProbeAvailable → Quiesced",
        )
        val ok = sm.issueProbeAfterRewalk(freshEpoch)
        assertTrue(ok is ProbeIssueResult.ProbeIssued, "fresh probe issues; got $ok")
    }

    @Test
    fun new_probe_issues_after_routeEpoch_reset_from_ProbeClaimed() = runBlocking {
        val sm = newSm()
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        sm.awaitAndClaimProbe(ownerGeneration = sm.allocateConnectionGeneration())
        assertTrue(sm.gate.value is WsReconnectGate.ProbeClaimed)

        val freshEpoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        assertTrue(sm.gate.value is WsReconnectGate.Quiesced)
        assertTrue(sm.issueProbeAfterRewalk(freshEpoch) is ProbeIssueResult.ProbeIssued)
    }

    @Test
    fun new_probe_issues_after_routeEpoch_reset_from_CandidateProving() = runBlocking {
        val sm = newSm()
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        sm.awaitAndClaimProbe(ownerGeneration = sm.allocateConnectionGeneration())
        sm.onEvent(
            RestStateMachine.Event.WsSessionConnected(
                sessionEpoch = 5L,
                connectionGeneration = 1L,
            )
        )
        assertTrue(sm.gate.value is WsReconnectGate.CandidateProving)

        val freshEpoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        assertTrue(
            sm.gate.value is WsReconnectGate.Quiesced,
            "beginRouteChange must atomically flip CandidateProving → Quiesced",
        )
        assertTrue(sm.issueProbeAfterRewalk(freshEpoch) is ProbeIssueResult.ProbeIssued)
    }

    @Test
    fun two_concurrent_beginRouteChange_do_not_lose_routeEpoch() = runBlocking {
        val sm = newSm()
        armSticky(sm)
        // Even if two paths call beginRouteChange concurrently, the
        // counter must advance by exactly the number of calls (no loss).
        val results = (0 until 50).map {
            async { sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch }
        }.awaitAll()
        assertEquals(50, results.size)
        val maxEpoch = results.max()
        // The maximum returned epoch must equal the current state-machine
        // epoch (the LATEST call's value).
        assertEquals(maxEpoch, sm.currentRouteEpoch())
        // The set of returned epochs must be exactly the contiguous range
        // [1, 50] — every concurrent call observed a unique counter value.
        assertEquals((1L..50L).toSet(), results.toSet())
    }

    @Test
    fun wrong_generation_Connected_does_NOT_consume_token_or_change_gate() = runBlocking {
        val sm = newSm()
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        val ownerCorrect = sm.allocateConnectionGeneration()
        val claim = sm.awaitAndClaimProbe(ownerGeneration = ownerCorrect) as ClaimResult.Claimed
        val tokenBefore = claim.probe.token

        // A stale connect event from an orphan loop (different generation).
        // Under the seventh-round gate-bypass guard this rejects with
        // `ws_recovery_connect_rejected reason=gate_bypass`.
        sm.onEvent(
            RestStateMachine.Event.WsSessionConnected(
                sessionEpoch = 999L,
                connectionGeneration = ownerCorrect + 41L, // != ownerCorrect
            )
        )

        // Gate must STILL be ProbeClaimed — the token is intact.
        val g = sm.gate.value
        assertTrue(
            g is WsReconnectGate.ProbeClaimed,
            "wrong-generation Connected must NOT consume the token; gate=$g",
        )
        assertEquals(tokenBefore, (g as WsReconnectGate.ProbeClaimed).token)

        // Correct-generation Connected now succeeds.
        sm.onEvent(
            RestStateMachine.Event.WsSessionConnected(
                sessionEpoch = 1000L,
                connectionGeneration = ownerCorrect,
            )
        )
        assertTrue(
            sm.gate.value is WsReconnectGate.CandidateProving,
            "correct-generation Connected DOES transition; gate=${sm.gate.value}",
        )
    }

    @Test
    fun stale_routeEpoch_Connected_does_NOT_consume_token() = runBlocking {
        val sm = newSm()
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        sm.awaitAndClaimProbe(ownerGeneration = sm.allocateConnectionGeneration())
        // Concurrent rewalk rolls routeEpoch (and flips the gate to Quiesced).
        sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        // A stale connect for the OLD probe arrives. Gate is now Quiesced;
        // the connect handler MUST be a no-op (not ProbeClaimed any more).
        sm.onEvent(
            RestStateMachine.Event.WsSessionConnected(
                sessionEpoch = 50L,
                connectionGeneration = 1L,
            )
        )
        assertTrue(
            sm.gate.value is WsReconnectGate.Quiesced,
            "stale-epoch Connected must not flip Quiesced into CandidateProving",
        )
    }

    @Test
    fun ProbeToken_toString_redacts_value() = runBlocking {
        val rawToken = 0xCAFE_BABE_DEAD_BEEFuL.toLong()
        val token = ProbeToken(rawToken)
        val s = token.toString()
        assertEquals("[REDACTED]", s)
        // The raw long must not appear in any data-class toString that
        // carries the token.
        val probe = WsReconnectGate.ProbeAvailable(
            stickyGen = 1,
            routeEpoch = 7L,
            token = token,
            budget = ProbeBudget(
                budgetStartedAtMs = 0L,
                maxAttempts = 5,
                maxElapsedMs = 120_000L,
            ),
            generationFloor = 0L,
        )
        assertFalse(
            probe.toString().contains(rawToken.toString()),
            "ProbeAvailable.toString() leaked the raw token: ${probe.toString()}",
        )
        // ProbeIssued + ClaimedProbe + ProbeClaimed all must redact too.
        val issued = ProbeIssueResult.ProbeIssued(token)
        assertFalse(
            issued.toString().contains(rawToken.toString()),
            "ProbeIssued.toString() leaked the raw token: $issued",
        )
        val claimed = WsReconnectPermit.ClaimedProbe(
            stickyGen = 1,
            routeEpoch = 7L,
            token = token,
            ownerGeneration = 3L,
            budget = probe.budget,
        )
        assertFalse(
            claimed.toString().contains(rawToken.toString()),
            "ClaimedProbe.toString() leaked the raw token: $claimed",
        )
        val probeClaimed = WsReconnectGate.ProbeClaimed(
            stickyGen = 1,
            routeEpoch = 7L,
            token = token,
            ownerGeneration = 3L,
            budget = probe.budget,
        )
        assertFalse(
            probeClaimed.toString().contains(rawToken.toString()),
            "ProbeClaimed.toString() leaked the raw token: $probeClaimed",
        )
    }

    @Test
    fun awaitAndClaimProbe_rejects_old_owner_generation_with_OWNER_GENERATION_STALE() = runBlocking {
        val sm = newSm()
        val oldOwner = sm.allocateConnectionGeneration()       // counter=1
        val secondAllocation = sm.allocateConnectionGeneration() // counter=2
        assertEquals(1L, oldOwner)
        assertEquals(2L, secondAllocation)
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch) // floor = counter = 2

        val claim = sm.awaitAndClaimProbe(ownerGeneration = oldOwner)
        assertTrue(claim is ClaimResult.Failure, "old owner must NOT claim; got $claim")
        assertEquals(
            ClaimFailureReason.OWNER_GENERATION_STALE,
            (claim as ClaimResult.Failure).reason,
        )

        val freshOwner = sm.allocateConnectionGeneration() // counter=3
        val ok = sm.awaitAndClaimProbe(ownerGeneration = freshOwner)
        assertTrue(ok is ClaimResult.Claimed, "fresh owner must claim; got $ok")
    }

    @Test
    fun validatePermitAfterAuth_persists_lease_when_a_newer_generation_is_allocated() = runBlocking {
        // P1 (seventh round): the sixth-round shape failed validation
        // when a stray `allocateConnectionGeneration` bumped the
        // counter past the claim's owner — deadlocking the gate
        // (owner blocked from dial, gate ProbeClaimed unmovable). The
        // seventh-round contract holds the owner's lease until the
        // gate transitions (revoke / route-change / matching Connected).
        val sm = newSm()
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        val owner = sm.allocateConnectionGeneration()
        val claim = sm.awaitAndClaimProbe(ownerGeneration = owner) as ClaimResult.Claimed
        // A spurious counter bump occurs (no live fresh loop).
        sm.allocateConnectionGeneration()
        assertTrue(
            sm.validatePermitAfterAuth(claim.probe),
            "lease must persist across a spurious counter bump; otherwise the gate deadlocks",
        )
    }

    @Test
    fun recordProbeAttemptFailed_with_stale_permit_does_not_consume_budget() = runBlocking {
        val sm = newSm()
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        val owner = sm.allocateConnectionGeneration()
        val claim = sm.awaitAndClaimProbe(ownerGeneration = owner) as ClaimResult.Claimed

        val newEpoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch  // flips ProbeClaimed → Quiesced
        sm.issueProbeAfterRewalk(newEpoch)
        val freshOwner = sm.allocateConnectionGeneration()
        val freshClaim = sm.awaitAndClaimProbe(ownerGeneration = freshOwner) as ClaimResult.Claimed

        // The STALE permit must NOT debit the fresh claim's budget.
        repeat(5) {
            sm.recordProbeAttemptFailed(permit = claim.probe, reason = "should_be_ignored_$it")
        }
        val g = sm.gate.value
        assertTrue(
            g is WsReconnectGate.ProbeClaimed,
            "stale-permit attempts must NOT drain fresh budget; gate=$g",
        )
        assertEquals(freshOwner, (g as WsReconnectGate.ProbeClaimed).ownerGeneration)

        // Fresh permit CAN drain its OWN budget.
        repeat(5) {
            sm.recordProbeAttemptFailed(permit = freshClaim.probe, reason = "real_$it")
        }
        assertTrue(
            sm.gate.value is WsReconnectGate.Quiesced,
            "fresh permit's 5 attempts must exhaust budget",
        )
    }

    @Test
    fun pre_existing_loop_cannot_claim_probe_issued_for_new_generation() = runBlocking {
        val sm = newSm()
        val ownerA = sm.allocateConnectionGeneration()
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch) // floor = counter = ownerA = 1
        val claim = sm.awaitAndClaimProbe(ownerGeneration = ownerA)
        assertTrue(claim is ClaimResult.Failure)
        assertEquals(
            ClaimFailureReason.OWNER_GENERATION_STALE,
            (claim as ClaimResult.Failure).reason,
        )
        val ownerB = sm.allocateConnectionGeneration()
        val ok = sm.awaitAndClaimProbe(ownerGeneration = ownerB)
        assertTrue(ok is ClaimResult.Claimed)
    }

    @Test
    fun pre_claim_Connected_is_rejected_under_quiescence_gate_bypass_guard() = runBlocking {
        // P1 #1 (seventh round): an orphan WS Connected arriving while
        // gate is ProbeAvailable (no claim yet) must be REJECTED. The
        // previous shape let it through, advancing `lastObservedEpoch`
        // and `stickyRecovery → InFlight`. If WsCandidate then ticked
        // through `ws_alive_60s` later, the gate could flip to Open
        // bypassing the probe claim entirely.
        val sm = newSm()
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))

        // Orphan connect — gate is ProbeAvailable, NOT Open or
        // matching ProbeClaimed → rejected.
        sm.onEvent(
            RestStateMachine.Event.WsSessionConnected(
                sessionEpoch = 5L,
                connectionGeneration = 9999L,
            )
        )

        // Gate is still ProbeAvailable, sticky still PendingNewSession.
        assertTrue(
            sm.gate.value is WsReconnectGate.ProbeAvailable,
            "orphan event must NOT mutate the gate; gate=${sm.gate.value}",
        )

        // Legitimate flow proceeds.
        val owner = sm.allocateConnectionGeneration()
        val claim = sm.awaitAndClaimProbe(ownerGeneration = owner)
        assertTrue(claim is ClaimResult.Claimed)
        sm.onEvent(
            RestStateMachine.Event.WsSessionConnected(
                sessionEpoch = 100L,
                connectionGeneration = owner,
            )
        )
        assertTrue(
            sm.gate.value is WsReconnectGate.CandidateProving,
            "legitimate connect succeeds; gate=${sm.gate.value}",
        )
    }

    @Test
    fun awaitReconnectPermit_returns_LoopRetired_for_obsolete_owner_generation() = runBlocking {
        // P1 #2 (seventh round): a stale loop calling awaitReconnectPermit
        // against a ProbeAvailable for which it cannot claim MUST NOT
        // CPU-spin. The seventh-round contract returns
        // `LoopRetired(owner_generation_stale)` so the reconnect-loop
        // exits cleanly.
        val sm = newSm()
        val obsoleteOwner = sm.allocateConnectionGeneration()  // counter=1
        sm.allocateConnectionGeneration()                       // counter=2 — supersedes
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)  // floor = counter = 2

        // The obsolete owner observes ProbeAvailable but cannot claim;
        // it must be retired, NOT spin.
        val permit = sm.awaitReconnectPermit(ownerGeneration = obsoleteOwner)
        assertTrue(
            permit is WsReconnectPermit.LoopRetired,
            "obsolete owner must be retired; got $permit",
        )
        assertEquals("owner_generation_stale", (permit as WsReconnectPermit.LoopRetired).reason)
    }

    @Test
    fun awaitAndClaimProbe_rejects_unallocated_owner_generation() = runBlocking {
        // P1 #3 (seventh round): the strict-currency rule rejects an
        // arbitrary unallocated `ownerGeneration` (e.g. 999) even if
        // it is numerically greater than the probe's floor.
        val sm = newSm()
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        // No allocate ⇒ counter still 0.
        val claim = sm.awaitAndClaimProbe(ownerGeneration = 999L)
        assertTrue(claim is ClaimResult.Failure)
        assertEquals(
            ClaimFailureReason.OWNER_GENERATION_STALE,
            (claim as ClaimResult.Failure).reason,
        )
    }

    @Test
    fun ws_alive_60s_proof_does_NOT_cancel_a_fresh_route_change() = runBlocking {
        // P1 #4 (seventh round) + P1 (eighth round): a stale
        // `ws_alive_60s` probation tick firing AFTER `beginRouteChange`
        // has flipped CandidateProving → Quiesced (new routeEpoch) MUST:
        //   (a) NOT overwrite the fresh Quiesced with Open;
        //   (b) NOT commit the sticky-cleared cleanup;
        //   (c) NOT promote `RestMode` to `WsActive` (eighth round —
        //       previously `_state.value = WsActive` ran
        //       unconditionally so a stale tick stopped REST polling);
        //   (d) NOT fire `onModeSwitched` with `WsActive`.
        var t = 0L
        val modeSwitchedCapture = mutableListOf<Triple<RestMode, RestMode, String>>()
        val sm = RestStateMachine(
            now = { t },
            log = { /* silent */ },
            onModeSwitched = { from, to, reason ->
                modeSwitchedCapture.add(Triple(from, to, reason))
            },
            mode2FastPathEnabled = true,
            mode2StickyEnabled = true,
            reconnectQuiescenceEnabled = true,
            currentKindProvider = { TransportKind.Direct },
            tokenSource = { 0xDEAD_BEEFL },
        )
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        val owner = sm.allocateConnectionGeneration()
        sm.awaitAndClaimProbe(ownerGeneration = owner)
        sm.onEvent(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 7L, connectionGeneration = owner))
        assertTrue(sm.gate.value is WsReconnectGate.CandidateProving, "precondition: CandidateProving")
        val sessionEpochOfCandidate = (sm.gate.value as WsReconnectGate.CandidateProving).sessionEpoch
        assertEquals(7L, sessionEpochOfCandidate)
        val modeBeforeRouteChange = sm.state.value

        // Concurrent route change — flips CandidateProving → Quiesced
        // (new routeEpoch).
        val newRouteEpoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        assertTrue(sm.gate.value is WsReconnectGate.Quiesced, "route change Quiesced the candidate")
        assertEquals(newRouteEpoch, sm.currentRouteEpoch())
        val modeBeforeStaleTick = sm.state.value

        // Clear any prior mode-switched callbacks captured during setup.
        modeSwitchedCapture.clear()

        // Now the stale 60-s probation tick arrives.
        t = 60_001L
        sm.onEvent(RestStateMachine.Event.WsAliveTickElapsed)

        // (a) gate stays Quiesced.
        assertTrue(
            sm.gate.value is WsReconnectGate.Quiesced,
            "stale proof must NOT flip to Open; gate=${sm.gate.value}",
        )
        // routeEpoch unchanged.
        assertEquals(newRouteEpoch, sm.currentRouteEpoch(), "routeEpoch unchanged by stale proof")
        // (b)+(c) RestMode unchanged — long-poll keeps running.
        assertEquals(
            modeBeforeStaleTick, sm.state.value,
            "stale proof must NOT promote RestMode to WsActive; mode before=$modeBeforeStaleTick after=${sm.state.value}",
        )
        // (d) no WsActive-direction mode-switched callback fired.
        val anyWsActiveCallback = modeSwitchedCapture.any { it.second == RestMode.WsActive }
        assertFalse(
            anyWsActiveCallback,
            "onModeSwitched must NOT be called with to=WsActive; captured=$modeSwitchedCapture",
        )
        // modeBeforeRouteChange retained for context inspection only;
        // referencing it silences any unused-warning analyser.
        @Suppress("UNUSED_VARIABLE") val priorMode = modeBeforeRouteChange
    }

    @Test
    fun ws_alive_60s_proof_commits_when_gate_still_matches_candidate() = runBlocking {
        // Positive companion to the rejected-proof test: when the gate
        // is STILL CandidateProving with matching stickyGen +
        // sessionEpoch when the probation tick lands, the proof
        // commits — sticky cleared, gate flips to Open, RestMode
        // becomes WsActive.
        var t = 0L
        val modeSwitched = mutableListOf<Triple<RestMode, RestMode, String>>()
        val sm = RestStateMachine(
            now = { t },
            log = { /* silent */ },
            onModeSwitched = { from, to, reason -> modeSwitched.add(Triple(from, to, reason)) },
            mode2FastPathEnabled = true,
            mode2StickyEnabled = true,
            reconnectQuiescenceEnabled = true,
            currentKindProvider = { TransportKind.Direct },
            tokenSource = { 0xDEAD_BEEFL },
        )
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        val owner = sm.allocateConnectionGeneration()
        sm.awaitAndClaimProbe(ownerGeneration = owner)
        sm.onEvent(RestStateMachine.Event.WsSessionConnected(sessionEpoch = 7L, connectionGeneration = owner))
        assertTrue(sm.gate.value is WsReconnectGate.CandidateProving)
        modeSwitched.clear()

        t = 60_001L
        sm.onEvent(RestStateMachine.Event.WsAliveTickElapsed)

        assertEquals(WsReconnectGate.Open, sm.gate.value, "proof commits: gate → Open")
        assertEquals(RestMode.WsActive, sm.state.value, "proof commits: RestMode → WsActive")
        val wsActiveCallbacks = modeSwitched.filter { it.second == RestMode.WsActive }
        assertEquals(1, wsActiveCallbacks.size, "exactly one WsActive callback; got $modeSwitched")
        assertEquals("ws_alive_60s", wsActiveCallbacks.single().third)
    }

    // ── State-machine lifecycle anchor (predecessor of integration Test 20) ─

    @Test
    fun state_machine_quiescence_recovery_lifecycle() = runBlocking {
        // State-machine-level anchor for the locked Test 20 lifecycle.
        // The FULL integration Test 20 (HybridRelayTransport pending-WS
        // outbox migration into REST + REST acceptance + pending stores
        // cleared + no duplicate WS re-send) is in
        // `HybridRelayTransportIntegrationTest20.kt`; the test below
        // pins ONLY the state-machine transitions so the lifecycle
        // contract is observable without a full transport fixture.
        //   1. Mode 2 silent-drop session ends → fast-path REST + sticky armed
        //      → gate flips Open → Quiesced.
        //   2. During quiescence the state machine stays in RestActive
        //      (long-poll path continues delivering envelopes —
        //      modeled by asserting RestMode.RestActive holds the
        //      whole window; the REST transport is independent of the
        //      gate and is unaffected).
        //   3. Wi-Fi switch (NetworkChanged(clearsMode2Sticky=true)) →
        //      sticky_recovery_pending. The coordinator (modeled
        //      here by direct calls) runs the locked transaction:
        //      beginRouteChange(true) → StickyRecovery → issueProbeAfterRewalk.
        //      The gate transitions Quiesced → ProbeAvailable.
        //   4. Reconnect loop awaits permit, claims probe → ProbeClaimed.
        //   5. WS session connects with matching ownerGeneration →
        //      ProbeClaimed → CandidateProving.
        //   6. 60-second ws_alive_60s probation tick → CandidateProving
        //      → Open + sticky cleared. RestMode → WsActive.
        //   7. Exactly ONE probe issued during the lifecycle (the
        //      single recovery probe — no spurious re-probing).
        //   8. probeAttemptCount lands back at 0; the gate is Open and
        //      stable.
        //
        // What this test does NOT cover (deferred for real-device smoke):
        //   - actual WebSocket I/O on Tele2 LTE;
        //   - actual REST poll deliveries / outbox/ACK preservation;
        //   - actual Tecno Tele2 LTE Mode 2 reproduction.
        var t = 0L
        val captured = mutableListOf<String>()
        val sm = RestStateMachine(
            now = { t },
            log = { captured.add(it) },
            mode2FastPathEnabled = true,
            mode2StickyEnabled = true,
            reconnectQuiescenceEnabled = true,
            currentKindProvider = { TransportKind.Direct },
            tokenSource = { 0xDEAD_BEEFL },
        )
        // Step 1: Mode 2 silent-drop session — fast-path armed, gate Quiesced.
        sm.onEvent(
            RestStateMachine.Event.WsSessionEnded(
                durationMs = 31_000,
                inboundFrames = 0,
                pendingAcksAtClose = 0,
                okhttpPingTimeoutDetected = true,
                sessionEpoch = 1L,
            ),
        )
        assertEquals(RestMode.RestActive, sm.state.value, "Mode 2 ⇒ RestActive (REST poll picks up)")
        assertTrue(sm.gate.value is WsReconnectGate.Quiesced, "armSticky ⇒ Quiesced; got ${sm.gate.value}")
        val stickyGenAtArm = (sm.gate.value as WsReconnectGate.Quiesced).stickyGen

        // Step 2: simulate REST continuing to deliver — modeled by the
        // state staying in RestActive while we burn some virtual time.
        // (The state machine is event-driven; no event during this
        // window means no transition.) The gate must STAY Quiesced.
        t = 5_000L
        assertEquals(RestMode.RestActive, sm.state.value, "REST poll continues during quiescence")
        assertTrue(sm.gate.value is WsReconnectGate.Quiesced, "gate stays Quiesced during REST delivery")

        // Step 3: Wi-Fi switch ⇒ NetworkChanged(clearsMode2Sticky=true)
        // submits the recovery-pending event, then coordinator runs
        // the locked transaction (begin → issueProbe).
        t = 10_000L
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        val outcome = sm.beginRouteChange(clearsMode2Sticky = true)
        assertTrue(outcome is RouteChangeOutcome.StickyRecovery, "Quiesced + clears=true ⇒ StickyRecovery; got $outcome")
        val routeEpoch = outcome.routeEpoch
        val issueResult = sm.issueProbeAfterRewalk(routeEpoch)
        assertTrue(issueResult is ProbeIssueResult.ProbeIssued, "probe issued; got $issueResult")
        assertTrue(sm.gate.value is WsReconnectGate.ProbeAvailable, "gate ⇒ ProbeAvailable")

        // Step 4: reconnect loop allocates + claims probe.
        val owner = sm.allocateConnectionGeneration()
        val claim = sm.awaitAndClaimProbe(ownerGeneration = owner) as ClaimResult.Claimed
        assertTrue(sm.gate.value is WsReconnectGate.ProbeClaimed, "after claim ⇒ ProbeClaimed")

        // Step 5: WS session connects with matching owner ⇒ CandidateProving.
        t = 11_000L
        sm.onEvent(
            RestStateMachine.Event.WsSessionConnected(
                sessionEpoch = 42L,
                connectionGeneration = owner,
            ),
        )
        assertTrue(sm.gate.value is WsReconnectGate.CandidateProving, "Connected ⇒ CandidateProving; got ${sm.gate.value}")
        assertEquals(RestMode.WsCandidate, sm.state.value, "RestMode ⇒ WsCandidate during probation")
        val candidateSessionEpoch = (sm.gate.value as WsReconnectGate.CandidateProving).sessionEpoch
        assertEquals(42L, candidateSessionEpoch)

        // Step 6: 60-second probation tick ⇒ ws_alive_60s ⇒ Open + sticky cleared.
        t = 11_000L + 60_001L
        sm.onEvent(RestStateMachine.Event.WsAliveTickElapsed)
        assertEquals(WsReconnectGate.Open, sm.gate.value, "after 60s probation ⇒ gate Open")
        assertEquals(RestMode.WsActive, sm.state.value, "after probation ⇒ RestMode WsActive")

        // Step 7: EXACTLY ONE probe issued during the lifecycle.
        val probeIssuedCount = captured.count { it.contains("ws_recovery_probe_granted") }
        assertEquals(1, probeIssuedCount, "exactly one recovery probe across the lifecycle; got=$probeIssuedCount logs=$captured")

        // Step 8: sticky cleared telemetry fired; gate stable.
        assertTrue(captured.any { it.contains("sticky_cleared") }, "sticky_cleared log present; got $captured")
        assertEquals(WsReconnectGate.Open, sm.gate.value, "gate stable at Open at end of lifecycle")

        // Defense in depth: the captured log MUST NOT contain a raw
        // token value (token-redaction invariant from commit 2a).
        val rawTokenStr = 0xDEAD_BEEFL.toString()
        assertTrue(
            captured.none { it.contains(rawTokenStr) },
            "no raw token value in any log line; got entries containing rawToken=${captured.filter { it.contains(rawTokenStr) }}",
        )
    }

    @Test
    fun new_probe_gets_full_budget_when_residual_count_persisted_into_Quiesced() = runBlocking {
        // Test gap #2 strengthening (2026-06-22). The existing
        // `new_probe_gets_full_budget_after_partially_used_previous_probe`
        // passes through `WsSessionConnected → CandidateProving`
        // which auto-resets `probeAttemptCount` to 0 in the
        // ProbeClaimed → CandidateProving transition. That means the
        // assertion at the end (Probe B gets full 5-attempt budget)
        // would PASS even if the `issueProbeAfterRewalk`-side reset
        // were absent.
        //
        // This variant uses [setResidualProbeStateForTest] to construct
        // the EXACT residual state the new defensive reset defends
        // against: gate manually placed in Quiesced with a non-zero
        // `probeAttemptCount`. Without the new
        // `probeAttemptCount = 0` inside `issueProbeAfterRewalk`,
        // Probe B would exhaust after 5 - 3 = 2 attempts and this
        // test would fail.
        val sm = newSm(tokenSequence = listOf(0x1111L))
        armSticky(sm)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        // Construct the residual state: Quiesced + non-zero count.
        sm.setResidualProbeStateForTest(
            gate = WsReconnectGate.Quiesced(stickyGen = 1),
            probeAttemptCount = 3,
        )
        assertEquals(3, sm.probeAttemptCountForTest(), "residual state seeded")

        // Issue a fresh probe. The defensive reset inside
        // `issueProbeAfterRewalk` MUST drop the counter back to 0.
        val routeEpoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(routeEpoch)
        assertEquals(
            0, sm.probeAttemptCountForTest(),
            "issueProbeAfterRewalk MUST reset probeAttemptCount to 0; got ${sm.probeAttemptCountForTest()}",
        )

        // Claim probe and burn the full 5-attempt budget.
        val owner = sm.allocateConnectionGeneration()
        val claim = sm.awaitAndClaimProbe(ownerGeneration = owner) as ClaimResult.Claimed
        repeat(4) { i ->
            sm.recordProbeAttemptFailed(permit = claim.probe, reason = "burn_${i + 1}")
            assertTrue(
                sm.gate.value is WsReconnectGate.ProbeClaimed,
                "attempt ${i + 1} of 5 MUST NOT exhaust budget; got ${sm.gate.value}",
            )
        }
        sm.recordProbeAttemptFailed(permit = claim.probe, reason = "burn_5")
        assertTrue(
            sm.gate.value is WsReconnectGate.Quiesced,
            "5th attempt MUST exhaust the FULL budget (not 5-3=2); got ${sm.gate.value}",
        )
    }

    @Test
    fun new_probe_gets_full_budget_after_partially_used_previous_probe() = runBlocking {
        // P1 (ninth round, 2026-06-22): regression for
        // probeAttemptCount inheritance. A previous probe that
        // partially exhausted its budget (e.g. 2 failures) must NOT
        // shorten the budget of a NEW probe issued after a route
        // change. The fresh `issueProbeAfterRewalk` resets the
        // counter atomically under the gate lock.
        val sm = newSm(tokenSequence = listOf(0x1111L, 0x2222L))
        armSticky(sm)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))

        // Probe A: claim + 2 failures (count = 2).
        val routeA = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(routeA)
        val ownerA = sm.allocateConnectionGeneration()
        val claimA = sm.awaitAndClaimProbe(ownerGeneration = ownerA) as ClaimResult.Claimed
        sm.recordProbeAttemptFailed(permit = claimA.probe, reason = "fail_1")
        sm.recordProbeAttemptFailed(permit = claimA.probe, reason = "fail_2")
        // Gate is still ProbeClaimed (budget = 5, count = 2).
        assertTrue(sm.gate.value is WsReconnectGate.ProbeClaimed)

        // Worst-case race simulation: drop the gate back to Quiesced
        // WITHOUT going through the ProbeClaimed → CandidateProving
        // reset (e.g. a route change kicks in mid-probe). Use
        // beginRouteChange — it Quiesces via the gate-flip path that
        // ALSO resets the counter, but a different path (e.g.
        // candidate-death without prior claim reset) could leave the
        // counter at 2. To pin the issueProbeAfterRewalk-side reset,
        // we run a fresh begin → issue cycle after a manual
        // candidate-died + recovery flow.
        sm.onEvent(
            RestStateMachine.Event.WsSessionConnected(
                sessionEpoch = 7L,
                connectionGeneration = ownerA,
            )
        )
        assertTrue(sm.gate.value is WsReconnectGate.CandidateProving)
        // Candidate dies before the 60s probation.
        sm.onEvent(
            RestStateMachine.Event.WsSessionEnded(
                durationMs = 4_000,
                inboundFrames = 0,
                pendingAcksAtClose = 0,
                okhttpPingTimeoutDetected = false,
                sessionEpoch = 7L,
            )
        )
        assertTrue(sm.gate.value is WsReconnectGate.Quiesced)

        // New route change → Probe B.
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        val routeB = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(routeB)
        val ownerB = sm.allocateConnectionGeneration()
        val claimB = sm.awaitAndClaimProbe(ownerGeneration = ownerB) as ClaimResult.Claimed

        // Probe B MUST get the full locked budget: 5 attempts before
        // exhaustion. Burn 4 failures — gate must still be ProbeClaimed.
        repeat(4) { i ->
            sm.recordProbeAttemptFailed(permit = claimB.probe, reason = "b_fail_${i + 1}")
            assertTrue(
                sm.gate.value is WsReconnectGate.ProbeClaimed,
                "Probe B attempt ${i + 1} of 5 must NOT exhaust budget; got ${sm.gate.value}",
            )
        }
        // 5th failure ⇒ exhaustion ⇒ Quiesced.
        sm.recordProbeAttemptFailed(permit = claimB.probe, reason = "b_fail_5")
        assertTrue(
            sm.gate.value is WsReconnectGate.Quiesced,
            "Probe B fifth attempt MUST exhaust the full budget; got ${sm.gate.value}",
        )
    }

    @Test
    fun no_token_value_appears_in_collected_logs_during_full_lifecycle() = runBlocking {
        val captured = mutableListOf<String>()
        val rawToken = 0x1234_5678_9ABC_DEF0L
        val sm = RestStateMachine(
            now = { 0L },
            log = { captured.add(it) },
            mode2FastPathEnabled = true,
            mode2StickyEnabled = true,
            reconnectQuiescenceEnabled = true,
            currentKindProvider = { TransportKind.Direct },
            tokenSource = { rawToken },
        )
        armSticky(sm)
        val epoch = sm.beginRouteChange(clearsMode2Sticky = true).routeEpoch
        sm.issueProbeAfterRewalk(epoch)
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true))
        val owner = sm.allocateConnectionGeneration()
        val claim = sm.awaitAndClaimProbe(ownerGeneration = owner) as ClaimResult.Claimed
        sm.onEvent(
            RestStateMachine.Event.WsSessionConnected(
                sessionEpoch = 11L,
                connectionGeneration = owner,
            )
        )
        sm.recordProbeAttemptFailed(
            permit = claim.probe,
            reason = "post_connect_anti_pattern_should_be_no_op_now",
        )
        sm.revokeProbe(epoch, reason = "late_revoke_test_path")

        for (line in captured) {
            assertFalse(
                line.contains(rawToken.toString()),
                "log line leaked the raw token: $line",
            )
            assertFalse(
                line.contains(rawToken.toString(16)),
                "log line leaked the hex-encoded raw token: $line",
            )
        }
    }
}
