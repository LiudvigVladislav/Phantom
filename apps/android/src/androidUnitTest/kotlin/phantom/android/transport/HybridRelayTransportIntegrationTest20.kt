// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import phantom.core.transport.AckDeliverRequest
import phantom.core.transport.AckDeliverResponse
import phantom.core.transport.AuthSessionRequest
import phantom.core.transport.AuthSessionResponse
import phantom.core.transport.KtorRelayTransport
import phantom.core.transport.MediaCapabilities
import phantom.core.transport.PollResponse
import phantom.core.transport.ProbeIssueResult
import phantom.core.transport.RelayMessage
import phantom.core.transport.RestFallbackOrchestrator
import phantom.core.transport.RestFallbackResponse
import phantom.core.transport.RestFallbackTransport
import phantom.core.transport.RestMode
import phantom.core.transport.RouteChangeOutcome
import phantom.core.transport.SendRequest
import phantom.core.transport.SendResponse
import phantom.core.transport.TransportKind
import phantom.core.transport.WsReconnectGate
import phantom.core.transport.WsReconnectPermit
import phantom.core.transport.WsSessionLifecycleEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RC-RECONNECT-QUIESCENCE1 closing-test-package — full integration
 * Test 20 (fix-round-2: real wire-recorder + actual reconnect-side
 * merge/flush + explicit transport teardown).
 *
 * Phase 1 — migration:
 *
 *   Mode 2 silent-drop session
 *   → state machine fast-paths to RestActive
 *   → quiescence gate flips to Quiesced (Direct-fence engaged)
 *   → `HybridRelayTransport.maybeArmMigrationLocked` launches migration
 *   → `migratePendingWsToRest` snapshots WS pending stores via
 *     `KtorRelayTransport.snapshotPendingOutbound`
 *   → re-sends each envelope through the real
 *     `RestFallbackOrchestrator.sendEnvelope` path (fake REST transport
 *     returning 201 Accepted)
 *   → on `SendOutcome.Accepted` calls
 *     `KtorRelayTransport.markPendingOutboundAcceptedByFallback` to
 *     clear `pendingAcks` / `pendingOutbox`.
 *
 * Phase 2 — recovery (the half external review flagged in the
 * `cc98d8f8` round):
 *
 *   beginRouteChange(clearsMode2Sticky = true) ⇒ StickyRecovery
 *   → issueProbeAfterRewalk ⇒ ProbeIssued
 *   → reconnect-loop side: allocateConnectionGeneration +
 *     awaitReconnectPermit ⇒ ClaimedProbe
 *   → dispatch `WsSessionLifecycleEvent.Connected(epoch=2, gen=owner)`
 *     through `HybridRelayTransport.dispatchWsSessionLifecycleEvent`
 *     (the SAME path production uses)
 *   → state machine consumes ProbeClaimed → CandidateProving
 *   → `KtorRelayTransport.runReconnectMergeAndFlushForTest(epoch=2)`
 *     drives the REAL production reconnect-side merge/flush sequence
 *     (`mergeUnackedIntoOutboxOrdered` + `flushPendingOutbox`) — the
 *     same two methods `runReconnectLoop` invokes once a fresh WS
 *     session is up.
 *   → a wire-recorder installed via `sendRawAttemptForTest` observes
 *     every `RelayMessage` the production `sendRaw` path tries to
 *     push onto the WS, BEFORE the null-session guard. Absence in
 *     the recording means the production code path NEVER tried to
 *     put the migrated envelopes on the wire.
 *
 * Assertions across BOTH phases:
 *
 *   Phase 1 (migration):
 *     1. REST mock received exactly the seeded envelopes (no loss).
 *     2. pendingAcks cleared after migration (via reflection seam).
 *     3. pendingOutbox cleared after migration (via reflection seam).
 *     4. snapshotPendingOutbound() is empty.
 *     5. Each envelope observed EXACTLY ONCE (idempotency — no REST dup).
 *     6. REST request bodies carry the seeded payloads verbatim.
 *
 *   Phase 2 (recovery):
 *     7. Route change returns StickyRecovery.
 *     8. issueProbeAfterRewalk returns ProbeIssued.
 *     9. Reconnect-loop side claims a `ClaimedProbe` permit.
 *    10. Gate observed at ProbeClaimed between claim and Connected.
 *    11. Post-Connected gate is CandidateProving.
 *    12. Real `runReconnectMergeAndFlushForTest(epoch=2)` ran without
 *        throwing. (Method body is `mergeUnackedIntoOutboxOrdered` then
 *        `flushPendingOutbox`; both are early-exit no-ops when their
 *        respective stores are empty.)
 *    13. The wire-recorder observed ZERO migrated messageIds (no
 *        envelope reached `sendRaw` from the real reconnect-side
 *        merge/flush sequence).
 *    14. REST mock counter UNCHANGED at 3 — no envelope re-sent via REST.
 *    15. snapshotPendingOutbound() STILL empty.
 *    16. Every messageId in the REST observation log appears exactly
 *        once across both phases.
 *
 * Cross-module access to `internal` test seams on `KtorRelayTransport`
 * uses the reflection bridge in [KtorRelayTransportInternalTestSeams]
 * — the seams stay `internal` so they do NOT appear in the Kotlin
 * source-level API a sibling module sees.
 *
 * Lifecycle: every constructed `KtorRelayTransport` is registered for
 * teardown in [closeAllOpenResources], which calls
 * `closeForIntegrationTest()` on each so the per-instance
 * `cleanupScope` is cancelled and its worker thread freed. Without
 * this, sweep runs could hang on accumulated cleanup-scope tasks.
 */
class HybridRelayTransportIntegrationTest20 {

    private val livingScopes = mutableListOf<CoroutineScope>()
    private val openTransports = mutableListOf<KtorRelayTransport>()

    /**
     * Fail-loud teardown (fix-round-3 2026-06-22). Each registered
     * transport's [closeForIntegrationTest] returns `true` iff its
     * `cleanupScope` was cancelled AND `cleanupInflight` reached 0
     * within 6 s. `@AfterTest` collects the failures (stuck cleanup
     * OR thrown exception) and rethrows them so a teardown stall
     * fails the test by name rather than silently leaking a
     * cleanup-scope worker into the next test run.
     */
    @AfterTest
    fun closeAllOpenResources() = runBlocking {
        // fix-round-5 (2026-06-23): the forensic `cleanupInflight` read
        // takes the same `cleanupCounterMutex` that the close path
        // contends on, so a stall in the close suspend could also stall
        // the forensic read and re-hang `@AfterTest`. Wrap each
        // diagnostic read in its own short bounded timeout.
        suspend fun forensicInflightOrUnavailable(t: KtorRelayTransport): String =
            withTimeoutOrNull(500L) {
                t.cleanupInflightCountForIntegrationTest().toString()
            } ?: "unavailable"
        val failures = mutableListOf<String>()
        openTransports.forEachIndexed { idx, t ->
            val outcome = runCatching {
                withTimeoutOrNull(6_000L) {
                    t.closeForIntegrationTest(awaitInflightTimeoutMs = 5_000L)
                }
            }
            when {
                outcome.isFailure ->
                    failures.add(
                        "transport[$idx] closeForIntegrationTest threw " +
                            outcome.exceptionOrNull(),
                    )
                outcome.getOrNull() == null ->
                    failures.add(
                        "transport[$idx] closeForIntegrationTest did not " +
                            "return within 6 s (outer timeout); " +
                            "cleanupInflight=${forensicInflightOrUnavailable(t)}",
                    )
                outcome.getOrNull() == false ->
                    failures.add(
                        "transport[$idx] closeForIntegrationTest reported " +
                            "stuck cleanupInflight after the 5 s drain " +
                            "window; cleanupInflight=" +
                            forensicInflightOrUnavailable(t),
                    )
            }
        }
        openTransports.clear()
        livingScopes.forEach { runCatching { it.cancel() } }
        livingScopes.clear()
        if (failures.isNotEmpty()) {
            error(
                "Test 20 teardown failed (${failures.size} failure(s)):\n" +
                    failures.joinToString("\n") { "  - $it" },
            )
        }
    }

    /**
     * Fake REST transport returning 201 Accepted for every `send` and
     * recording the envelope ids it observed. `authSession` returns a
     * valid token + `restFallback=true` so the orchestrator's real
     * [RestFallbackOrchestrator.bootstrap] flips `_capabilities` via
     * the production path — no test backdoor invoked.
     */
    private class Test20RestTransport : RestFallbackTransport {
        val sentEnvelopeIds = mutableListOf<String>()
        val sentBodies = mutableListOf<SendRequest>()

        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> = RestFallbackResponse(
            statusCode = 200,
            bodyParsed = AuthSessionResponse(
                token = "test-20-token",
                expiresAt = Long.MAX_VALUE,
                restFallback = true,
                maxSendBodyBytes = 4096,
                pollMaxEnvelopes = 1,
                mediaCapabilities = MediaCapabilities(),
                pollHoldSecs = 0,
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
            sentEnvelopeIds.add(body.envelopeId)
            sentBodies.add(body)
            return RestFallbackResponse(
                statusCode = 201,
                bodyParsed = SendResponse(ok = 1),
                rawBody = "{\"ok\":1}",
                elapsedMs = 1L,
            )
        }

        override suspend fun poll(
            url: String,
            token: String,
            sinceSeq: Long?,
            longPollOptIn: Boolean,
            readTimeoutMs: Long?,
        ): RestFallbackResponse<PollResponse> =
            RestFallbackResponse(
                statusCode = 200,
                bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                rawBody = "{\"envelopes\":[]}",
                elapsedMs = 1L,
            )

        override suspend fun ackDeliver(
            url: String,
            token: String,
            body: AckDeliverRequest,
        ): RestFallbackResponse<AckDeliverResponse> = error("ackDeliver not used in Test 20")
    }

    private fun newWsTransport(): KtorRelayTransport = KtorRelayTransport(
        httpClientFactory = { error("test must not invoke httpClientFactory") },
    ).also { openTransports.add(it) }

    private fun newOrchestrator(transport: RestFallbackTransport): RestFallbackOrchestrator =
        RestFallbackOrchestrator(
            baseUrl = "https://relay.test",
            identityHex = "aa".repeat(32),
            signingPubkeyHex = "bb".repeat(32),
            getChallenge = { _ -> "cc".repeat(32) },
            signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
            transport = transport,
            now = { 0L },
            log = { },
            mode2FastPathEnabled = true,
            mode2StickyEnabled = true,
            reconnectQuiescenceEnabled = true,
            currentKindProvider = { TransportKind.Direct },
            tokenSource = { 0xCAFE_BABEL },
        )

    @Test
    fun test_20_mode2_quiescence_migration_recovery_no_loss_no_duplicate() = runBlocking {
        // ── Setup ────────────────────────────────────────────────────
        val ws = newWsTransport()
        val rest = Test20RestTransport()
        val orch = newOrchestrator(rest)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            .also { livingScopes.add(it) }
        val hybrid = HybridRelayTransport(
            wsTransport = ws,
            orchestrator = orch,
            processedEnvelopeRepository = null,
            scope = scope,
            nowMs = { 10_000L },
            degradationCurrentKindProvider = { TransportKind.Direct },
        )

        // ── Wire-recorder: every RelayMessage the production sendRaw
        //    is asked to push onto the WS gets appended here. Empty
        //    list at assertion time = nothing reached the WS path.
        val wireRecording = java.util.Collections.synchronizedList(mutableListOf<String>())
        ws.installSendRawRecorderForIntegrationTest { msg ->
            // Record the messageId (or a class label for non-Send
            // variants like AckDelivery).
            val id = when (msg) {
                is RelayMessage.Send -> msg.messageId
                is RelayMessage.AckDelivery -> "ack:${msg.messageId}"
                else -> "other:${msg::class.simpleName}"
            }
            wireRecording.add(id)
        }

        // ── Positive control (fix-round-3 2026-06-22) ───────────────
        // Prove the recorder is actually wired BEFORE the negative
        // assertion runs at the end. Without this, accidental deletion
        // of `sendRawAttemptForTest?.invoke(message)` from `sendRaw`
        // would leave the final "wire recording empty" check green
        // for the wrong reason.
        val sentinelId = "sentinel-recorder-${java.util.UUID.randomUUID()}"
        val sentinelMsg = RelayMessage.Send(
            to = "aa".repeat(32),
            sealedSender = "",
            payload = "c2VudGluZWw=",
            messageId = sentinelId,
        )
        ws.sendRawForIntegrationTest(sentinelMsg)
        assertTrue(
            sentinelId in wireRecording,
            "positive control: the wire-recorder MUST observe the sentinel " +
                "messageId after a direct `sendRaw` invocation. If this " +
                "assertion fails, `sendRaw` is no longer calling " +
                "`sendRawAttemptForTest?.invoke(message)` and the Phase-2 " +
                "negative assertion is meaningless. Got wireRecording=" +
                "${wireRecording.toList()}",
        )
        // Clear so the negative assertion at the end measures only
        // recovery-side activity.
        wireRecording.clear()

        // ── Real bootstrap ──────────────────────────────────────────
        // The fake transport returns the right `AuthSessionResponse`
        // so the orchestrator's REAL `bootstrap()` path flips
        // `_capabilities.value` via
        // `acquireOrRefreshToken → response.toCapabilities()` — no
        // test backdoor invoked.
        val caps = orch.bootstrap()
        assertTrue(
            caps.restFallback,
            "real bootstrap() must produce restFallback=true (fake auth response carries it)",
        )

        // Same-module `internal` seam: turn the production write-once
        // flag ON without standing up the full bootstrapAndStart.
        hybrid.setRestCapabilityActiveForTest(true)

        // ── Seed pending WS outbox + pendingAcks (via reflection) ───
        val seededIds = mutableListOf<String>()
        val msg1 = RelayMessage.Send(
            to = "ee".repeat(32),
            sealedSender = "",
            payload = "cGF5MQ==",
            messageId = "envid-1-${java.util.UUID.randomUUID()}",
        ).also { seededIds.add(it.messageId) }
        val msg2 = RelayMessage.Send(
            to = "ff".repeat(32),
            sealedSender = "",
            payload = "cGF5Mg==",
            messageId = "envid-2-${java.util.UUID.randomUUID()}",
        ).also { seededIds.add(it.messageId) }
        val msg3 = RelayMessage.Send(
            to = "11".repeat(32),
            sealedSender = "",
            payload = "cGF5Mw==",
            messageId = "envid-3-${java.util.UUID.randomUUID()}",
        ).also { seededIds.add(it.messageId) }

        ws.seedAckPendingForIntegrationTest(msg1, sequenceTs = 100L, queuedAtMs = 0L)
        ws.seedAckPendingForIntegrationTest(msg2, sequenceTs = 101L, queuedAtMs = 0L)
        ws.seedOutboxForIntegrationTest(msg3, sequenceTs = 102L, queuedAtMs = 0L)

        assertEquals(
            3, ws.snapshotPendingOutbound().size,
            "precondition: three envelopes seeded across pendingAcks+pendingOutbox",
        )

        // ── Phase 1: drive Mode 2 fast-path → RestActive → migration ─
        hybrid.dispatchWsSessionLifecycleEvent(
            WsSessionLifecycleEvent.Ended(
                durationMs = 31_000L,
                inboundFrames = 0,
                pendingAcksAtClose = 2,
                closeOrigin = "error",
                closeError = null,
                okhttpPingTimeoutDetected = true,
                sessionEpoch = 1L,
            )
        )

        assertEquals(RestMode.RestActive, orch.stateMachine.current, "Mode 2 ⇒ RestActive")
        assertTrue(
            orch.stateMachine.gate.value is WsReconnectGate.Quiesced,
            "armSticky ⇒ Quiesced; got ${orch.stateMachine.gate.value}",
        )

        withTimeoutOrNull(10_000L) {
            hybrid.awaitMigrationDoneForTest()
            true
        } ?: error("migration did not complete within 10s")

        // ── Phase 1 assertions ──────────────────────────────────────

        // (1) REST mock observed exactly the seeded envelopes — no loss.
        assertEquals(
            seededIds.toSet(), rest.sentEnvelopeIds.toSet(),
            "REST received exactly the three seeded envelopes; got ${rest.sentEnvelopeIds}",
        )
        // (5) Each envelope observed EXACTLY ONCE (no REST duplicate).
        assertEquals(
            3, rest.sentEnvelopeIds.size,
            "no REST duplicates; got ${rest.sentEnvelopeIds.size} sends",
        )
        // (2) pendingAcks cleared.
        assertEquals(
            0, ws.snapshotPendingAcksCountForIntegrationTest(),
            "pendingAcks must be empty after migration",
        )
        // (3) pendingOutbox cleared.
        assertEquals(
            0, ws.snapshotOutboxCountForIntegrationTest(),
            "pendingOutbox must be empty after migration",
        )
        // (4) snapshotPendingOutbound (public union view) empty.
        assertTrue(
            ws.snapshotPendingOutbound().isEmpty(),
            "snapshotPendingOutbound must be empty after migration; got ${ws.snapshotPendingOutbound()}",
        )
        // (6) REST request bodies carry the seeded payloads verbatim.
        val sentPayloads = rest.sentBodies.map { it.payloadBase64 }.toSet()
        assertEquals(
            setOf("cGF5MQ==", "cGF5Mg==", "cGF5Mw=="), sentPayloads,
            "REST request bodies carry the seeded payloads verbatim",
        )

        // Snapshot for cross-phase REST-counter invariant.
        val restSendsAfterMigration = rest.sentEnvelopeIds.toList()
        assertEquals(3, restSendsAfterMigration.size)

        // ── Phase 2: recovery — drive the gate lifecycle ────────────

        // (7) Route change with clearsMode2Sticky=true returns
        //     StickyRecovery (gate was Quiesced after migration).
        val routeOutcome = orch.stateMachine.beginRouteChange(clearsMode2Sticky = true)
        assertTrue(
            routeOutcome is RouteChangeOutcome.StickyRecovery,
            "post-migration route change ⇒ StickyRecovery; got $routeOutcome",
        )
        val newRouteEpoch = (routeOutcome as RouteChangeOutcome.StickyRecovery).routeEpoch

        // (8) Issue a fresh probe.
        val probeResult = orch.stateMachine.issueProbeAfterRewalk(newRouteEpoch)
        assertTrue(
            probeResult is ProbeIssueResult.ProbeIssued,
            "issueProbeAfterRewalk ⇒ ProbeIssued; got $probeResult",
        )
        assertTrue(
            orch.stateMachine.gate.value is WsReconnectGate.ProbeAvailable,
            "post-issue gate ⇒ ProbeAvailable; got ${orch.stateMachine.gate.value}",
        )

        // (9) Reconnect-loop side: allocate owner gen + claim probe
        //     through `awaitReconnectPermit` (the public single entry
        //     point production calls).
        val ownerGen = orch.stateMachine.allocateConnectionGeneration()
        val permit = withTimeoutOrNull(2_000L) {
            orch.stateMachine.awaitReconnectPermit(ownerGen)
        } ?: error("awaitReconnectPermit timed out — gate did not transition to actionable state")

        assertTrue(
            permit is WsReconnectPermit.ClaimedProbe,
            "permit must be ClaimedProbe (gate was ProbeAvailable); got $permit",
        )

        // (10) Post-claim gate snapshot.
        assertTrue(
            orch.stateMachine.gate.value is WsReconnectGate.ProbeClaimed,
            "post-claim gate ⇒ ProbeClaimed; got ${orch.stateMachine.gate.value}",
        )

        // ── Recovery handshake — dispatch through production path ───
        // `WsSessionLifecycleEvent.Connected` is mapped to
        // `RestStateMachine.Event.WsSessionConnected` which inside
        // `onWsSessionConnected`'s single-lock transaction consumes
        // ProbeClaimed → CandidateProving when owner+routeEpoch match.
        val recoveryEpoch = 2L
        hybrid.dispatchWsSessionLifecycleEvent(
            WsSessionLifecycleEvent.Connected(
                sessionEpoch = recoveryEpoch,
                connectionGeneration = ownerGen,
            )
        )

        // Poll-await the CandidateProving transition.
        withTimeoutOrNull(2_000L) {
            while (orch.stateMachine.gate.value !is WsReconnectGate.CandidateProving) {
                delay(10)
            }
            true
        } ?: error(
            "gate did not advance to CandidateProving within 2s; " +
                "currently ${orch.stateMachine.gate.value}",
        )

        // (11) Gate advanced through the recovery transition.
        assertTrue(
            orch.stateMachine.gate.value is WsReconnectGate.CandidateProving,
            "post-Connected gate ⇒ CandidateProving; got ${orch.stateMachine.gate.value}",
        )

        // ── Real reconnect-side merge/flush ─────────────────────────
        // (12) Drive the production `mergeUnackedIntoOutboxOrdered` +
        //      `flushPendingOutbox` sequence. These are the SAME two
        //      methods `KtorRelayTransport.runReconnectLoop` invokes
        //      once a fresh WS session is up. Each is early-exit when
        //      its store is empty (and BOTH stores ARE empty after
        //      migration), but the production code path runs.
        ws.runReconnectMergeAndFlushForIntegrationTest(mySession = recoveryEpoch)

        // ── Phase 2 assertions ──────────────────────────────────────

        // (13) The wire-recorder observed ZERO migrated messageIds
        //      (no envelope reached sendRaw from the real
        //      reconnect-side merge/flush sequence).
        val wireSnapshot = wireRecording.toList()
        val migratedIdsInWire = wireSnapshot.filter { it in seededIds }
        assertTrue(
            migratedIdsInWire.isEmpty(),
            "post-recovery: NO migrated messageId may appear in the WS " +
                "wire-recording; got migrated-in-wire=$migratedIdsInWire " +
                "(full wire recording=$wireSnapshot)",
        )
        // Stronger invariant: the recording is ENTIRELY empty — the
        // real reconnect-side merge/flush wrote nothing at all to the
        // wire under the post-migration state.
        assertTrue(
            wireSnapshot.isEmpty(),
            "post-recovery: WS wire-recording must be empty after " +
                "runReconnectMergeAndFlushForTest under empty pending " +
                "stores; got $wireSnapshot",
        )

        // (14) REST observation log UNCHANGED across recovery.
        assertEquals(
            restSendsAfterMigration, rest.sentEnvelopeIds.toList(),
            "post-recovery: REST log unchanged (no envelope re-sent through REST)",
        )

        // (15) The WS retry queue (the union of pendingOutbox +
        //      pendingAcks) STILL empty across the recovery
        //      lifecycle. A future `flushPendingOutbox(epoch)` on
        //      the recovered session would have NOTHING to push.
        val unionAfterRecovery = ws.snapshotPendingOutbound()
        assertTrue(
            unionAfterRecovery.isEmpty(),
            "post-recovery: snapshotPendingOutbound STILL empty; got $unionAfterRecovery",
        )
        assertEquals(
            0, ws.snapshotPendingAcksCountForIntegrationTest(),
            "post-recovery: pendingAcks STILL empty",
        )
        assertEquals(
            0, ws.snapshotOutboxCountForIntegrationTest(),
            "post-recovery: pendingOutbox STILL empty",
        )

        // (16) Every messageId in the REST observation log appears
        //      exactly once across BOTH phases.
        val idCounts = rest.sentEnvelopeIds.groupingBy { it }.eachCount()
        assertTrue(
            idCounts.values.all { it == 1 },
            "every messageId observed exactly once across both phases; got $idCounts",
        )
    }
}
