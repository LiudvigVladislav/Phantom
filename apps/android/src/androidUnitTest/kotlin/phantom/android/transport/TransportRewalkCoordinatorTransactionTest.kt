// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import phantom.core.transport.PrivacyMode
import phantom.core.transport.ProbeBudget
import phantom.core.transport.ProbeIssueRejectReason
import phantom.core.transport.ProbeIssueResult
import phantom.core.transport.ProbeToken
import phantom.core.transport.RelayTransportConfig
import phantom.core.transport.RewalkCoordinatorGateProvider
import phantom.core.transport.RouteChangeOutcome
import phantom.core.transport.TransportKind
import phantom.core.transport.TransportPreferences
import phantom.core.transport.WsReconnectGate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RC-RECONNECT-QUIESCENCE1 commit 2c (2026-06-22) — behavioural tests
 * for the [TransportRewalkCoordinator.performRewalk] typed transaction.
 *
 * Each test pins one of the locked invariants. End-to-end coverage of a
 * real chain walk (Test 20) is deferred per the locked schedule; here
 * we drive the coordinator with deterministic fakes for the four
 * collaborators ([RewalkHybridFacade], [TransportPreferences],
 * release lambda, [RewalkCoordinatorGateProvider]) and observe the
 * call order + side effects.
 */
class TransportRewalkCoordinatorTransactionTest {

    private val livingScopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun closeScopes() {
        livingScopes.forEach { runCatching { it.cancel() } }
        livingScopes.clear()
    }

    /** Chronological shared call-log. */
    private class CallLog {
        private val entries = mutableListOf<String>()
        fun add(s: String) { entries.add(s) }
        fun snapshot(): List<String> = entries.toList()
        fun contains(s: String) = entries.contains(s)
        fun count(prefix: String) = entries.count { it.startsWith(prefix) }
        fun firstIndex(s: String) = entries.indexOf(s)
        fun any(predicate: (String) -> Boolean) = entries.any(predicate)
        fun none(predicate: (String) -> Boolean) = entries.none(predicate)
    }

    private class TracingGate(
        private val log: CallLog,
        initialGate: WsReconnectGate = WsReconnectGate.Quiesced(stickyGen = 1),
    ) : RewalkCoordinatorGateProvider {
        private val _gate = MutableStateFlow(initialGate)
        override val gate: StateFlow<WsReconnectGate> = _gate.asStateFlow()
        var routeEpoch = 0L
            private set
        var issueResult: ProbeIssueResult = ProbeIssueResult.ProbeIssued(ProbeToken(0xCAFEL))

        override suspend fun beginRouteChange(clearsMode2Sticky: Boolean): RouteChangeOutcome {
            val current = _gate.value
            return when {
                current is WsReconnectGate.Open -> {
                    routeEpoch += 1
                    log.add("beginRouteChange:$routeEpoch")
                    log.add("outcome:OpenReconnect:cms=$clearsMode2Sticky")
                    RouteChangeOutcome.OpenReconnect(routeEpoch)
                }
                !clearsMode2Sticky -> {
                    // No bump.
                    log.add("beginRouteChange:$routeEpoch")
                    log.add("outcome:QuiescencePreserved:cms=$clearsMode2Sticky")
                    RouteChangeOutcome.QuiescencePreserved(routeEpoch)
                }
                else -> {
                    routeEpoch += 1
                    log.add("beginRouteChange:$routeEpoch")
                    log.add("outcome:StickyRecovery:cms=$clearsMode2Sticky")
                    RouteChangeOutcome.StickyRecovery(routeEpoch)
                }
            }
        }
        override suspend fun revokeRouteChange(routeEpoch: Long, reason: String) {
            log.add("revokeRouteChange:$routeEpoch:$reason")
        }
        override suspend fun issueProbeAfterRewalk(routeEpoch: Long): ProbeIssueResult {
            log.add("issueProbeAfterRewalk:$routeEpoch")
            return issueResult
        }
        override suspend fun revokeProbe(routeEpoch: Long, reason: String) {
            log.add("revokeProbe:$routeEpoch:$reason")
        }
    }

    private class TracingHybrid(private val log: CallLog) : RewalkHybridFacade {
        /** Behaviour of disconnectAndJoin: null=cleanReturnTrue, false=timeout, "ce"=CE, "ex"=throw. */
        var disconnectBehaviour: String? = null
        var submitBehaviour: String? = null

        override suspend fun submitNetworkChangedEvent(clearsMode2Sticky: Boolean) {
            log.add("submitNetworkChangedEvent:$clearsMode2Sticky")
            when (submitBehaviour) {
                "ce" -> throw CancellationException("submit CE")
                "ex" -> throw IllegalStateException("submit error")
                else -> Unit
            }
        }
        override suspend fun disconnect() {
            log.add("disconnect")
        }
        override suspend fun disconnectAndJoin(timeoutMs: Long): Boolean {
            log.add("disconnectAndJoin:$timeoutMs")
            return when (disconnectBehaviour) {
                "false" -> false
                "ce" -> throw CancellationException("dj CE")
                "ex" -> throw IllegalStateException("dj error")
                else -> true
            }
        }
    }

    private class InMemoryPrefs : TransportPreferences {
        override var privacyMode: PrivacyMode = PrivacyMode.Standard
        override var lastWorkingTransport: TransportKind? = TransportKind.Direct
        override var lastSuccessAt: Long? = 12345L
        override var transportFailureCount: Int = 0
    }

    private fun newCoordinator(
        log: CallLog,
        gate: RewalkCoordinatorGateProvider?,
        hybrid: TracingHybrid?,
        releaseBehaviour: String? = null,
        restartBehaviour: String? = null,
        nowMs: () -> Long = { 1_000L },
    ): Fixture {
        val prefs = InMemoryPrefs()
        val restartLog = mutableListOf<NetworkChangeReason>()
        val coord = TransportRewalkCoordinator(
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { livingScopes.add(it) },
            transportPreferences = prefs,
            releaseTransport = {
                log.add("release")
                when (releaseBehaviour) {
                    "ce" -> throw CancellationException("release CE")
                    "ex" -> throw IllegalStateException("release error")
                    else -> Unit
                }
            },
            hybridTransportProvider = { hybrid },
            requestServiceRestart = { reason ->
                log.add("requestServiceRestart:${reason.name}")
                restartLog.add(reason)
                when (restartBehaviour) {
                    "ce" -> throw CancellationException("restart CE")
                    "ex" -> throw IllegalStateException("restart error")
                    else -> Unit
                }
            },
            nowMs = nowMs,
            gateCoordinator = gate,
        )
        return Fixture(coord, prefs, restartLog)
    }

    private data class Fixture(
        val coord: TransportRewalkCoordinator,
        val prefs: InMemoryPrefs,
        val restartLog: MutableList<NetworkChangeReason>,
    )

    private fun snapshot(networkPresent: Boolean = true) = NetworkSnapshot(
        networkPresent = networkPresent,
        transportClass = NetworkTransportClass.WIFI,
        vpnActive = false,
        validated = true,
    )

    private suspend fun awaitJobDone(coord: TransportRewalkCoordinator, timeoutMs: Long = 5_000) {
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val running = coord.javaClass.getDeclaredField("currentRewalkJob").apply { isAccessible = true }
                    .get(coord) as? kotlinx.coroutines.Job
                if (running == null || running.isCompleted) return@withTimeoutOrNull
                delay(20)
            }
        } ?: error("rewalk job did not complete within ${timeoutMs}ms")
    }

    // ── 1: Happy path — locked transaction order ────────────────────────────

    @Test
    fun happy_path_runs_locked_transaction_in_order() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log)
        val (coord, prefs, restartLog) = newCoordinator(log, gate, hybrid)
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        val expected = listOf(
            "beginRouteChange:1",
            "submitNetworkChangedEvent:true",
            "disconnectAndJoin:10000",
            "release",
            "issueProbeAfterRewalk:1",
            "requestServiceRestart:WIFI_TO_CELLULAR",
        )
        val observed = log.snapshot()
        val indices = expected.map { observed.indexOf(it) }
        assertTrue(indices.all { it >= 0 }, "all expected steps present; got $observed")
        assertEquals(indices.sorted(), indices, "steps must run in order; observed=$observed")
        assertEquals(listOf(NetworkChangeReason.WIFI_TO_CELLULAR), restartLog)
        assertNull(prefs.lastWorkingTransport)
        assertNull(prefs.lastSuccessAt)
        assertTrue(log.none { it.startsWith("revoke") }, "no revoke on happy path; got $observed")
    }

    // ── 2: disconnectAndJoin returns false → revokeRouteChange + abort ──────

    @Test
    fun disconnectAndJoin_false_revokes_routeChange_and_skips_release_probe_restart() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log).apply { disconnectBehaviour = "false" }
        val (coord, _, restartLog) = newCoordinator(log, gate, hybrid)
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("revokeRouteChange:1:disconnect_join_timeout"), "got ${log.snapshot()}")
        assertTrue(log.none { it.startsWith("issueProbeAfterRewalk") }, "no probe; got ${log.snapshot()}")
        assertTrue(log.none { it == "release" }, "release must NOT run on disconnect timeout")
        assertTrue(restartLog.isEmpty(), "restart must NOT fire on disconnect timeout")
    }

    @Test
    fun disconnectAndJoin_CE_revokes_routeChange_and_propagates() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log).apply { disconnectBehaviour = "ce" }
        val (coord, _, restartLog) = newCoordinator(log, gate, hybrid)
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("revokeRouteChange:1:disconnectAndJoin_cancelled"), "got ${log.snapshot()}")
        assertTrue(log.none { it == "release" })
        assertTrue(restartLog.isEmpty())
    }

    @Test
    fun release_error_revokes_routeChange_and_skips_probe_restart() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log)
        val (coord, _, restartLog) = newCoordinator(log, gate, hybrid, releaseBehaviour = "ex")
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("revokeRouteChange:1:release_failed"), "got ${log.snapshot()}")
        assertTrue(log.none { it.startsWith("issueProbeAfterRewalk") })
        assertEquals(1, log.count("release"), "release attempted exactly once")
        assertTrue(restartLog.isEmpty())
    }

    @Test
    fun issueProbe_rejected_skips_service_restart() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log).apply {
            issueResult = ProbeIssueResult.Rejected(ProbeIssueRejectReason.GATE_NOT_QUIESCED)
        }
        val hybrid = TracingHybrid(log)
        val (coord, _, restartLog) = newCoordinator(log, gate, hybrid)
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("issueProbeAfterRewalk:1"))
        assertTrue(restartLog.isEmpty(), "rejected probe must skip restart")
        assertEquals(1, log.count("release"), "release still ran before probe issuance attempt")
    }

    @Test
    fun serviceRestart_error_revokes_probe() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log)
        val (coord, _, restartLog) = newCoordinator(log, gate, hybrid, restartBehaviour = "ex")
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("revokeProbe:1:service_restart_failed"), "got ${log.snapshot()}")
        assertEquals(listOf(NetworkChangeReason.WIFI_TO_CELLULAR), restartLog)
    }

    @Test
    fun lastRewalkAtMs_not_bumped_on_failed_teardown() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log).apply { disconnectBehaviour = "false" }
        var t = 1_000L
        val (coord, _, _) = newCoordinator(log, gate, hybrid, nowMs = { t })
        coord.seedNetworkPresent(true)

        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)
        assertTrue(log.any { it.startsWith("revokeRouteChange:1") })

        // Advance clock by 1s — well inside NETWORK_REWALK_MIN_INTERVAL_MS.
        t += 1_000L
        hybrid.disconnectBehaviour = null
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)
        assertTrue(
            log.contains("beginRouteChange:2"),
            "second attempt must NOT be rate-limited; got ${log.snapshot()}",
        )
    }

    @Test
    fun lastRewalkAtMs_bumped_on_success_so_subsequent_attempt_is_rate_limited() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log)
        var t = 10_000L
        val (coord, _, _) = newCoordinator(log, gate, hybrid, nowMs = { t })
        coord.seedNetworkPresent(true)

        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)
        assertTrue(log.contains("beginRouteChange:1"))

        t += 1_000L
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)
        assertEquals(
            1, log.count("beginRouteChange:"),
            "second non-forced attempt within rate-limit window must NOT begin a new rewalk; got ${log.snapshot()}",
        )
    }

    @Test
    fun hybrid_null_revokes_routeEpoch_and_skips_steps() = runBlocking {
        val log = CallLog()
        val gate = TracingGate(log)
        val (coord, _, restartLog) = newCoordinator(log, gate, hybrid = null)
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("beginRouteChange:1"))
        assertTrue(log.contains("revokeRouteChange:1:hybrid_not_initialized"))
        assertTrue(log.none { it == "release" })
        assertTrue(restartLog.isEmpty())
    }

    @Test
    fun legacy_path_runs_when_gateCoordinator_is_null() = runBlocking {
        val log = CallLog()
        val hybrid = TracingHybrid(log)
        val (coord, _, restartLog) = newCoordinator(log, gate = null, hybrid = hybrid)
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("submitNetworkChangedEvent:true"))
        assertTrue(log.contains("disconnect"), "legacy path uses disconnect; got ${log.snapshot()}")
        assertTrue(log.none { it.startsWith("disconnectAndJoin") })
        assertEquals(1, log.count("release"))
        assertEquals(listOf(NetworkChangeReason.WIFI_TO_CELLULAR), restartLog)
    }

    // ── Second-round amendments: Open / VALIDATED_CHANGED / startService ────

    @Test
    fun Open_gate_route_change_runs_teardown_release_restart_without_probe() = runBlocking {
        // Open gate ⇒ ordinary network rewalk: teardown + release +
        // restart, NO probe issuance. The pre-amend shape called
        // issueProbeAfterRewalk regardless, which returned
        // GATE_NOT_QUIESCED and silently skipped the restart — leaving
        // the transport torn down with nothing to bring it back.
        val log = CallLog()
        val gate = TracingGate(log, initialGate = WsReconnectGate.Open)
        val hybrid = TracingHybrid(log)
        val (coord, _, restartLog) = newCoordinator(log, gate, hybrid)
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("outcome:OpenReconnect:cms=true"), "got ${log.snapshot()}")
        assertTrue(log.contains("disconnectAndJoin:10000"))
        assertEquals(1, log.count("release"), "release runs exactly once on Open path")
        assertTrue(log.none { it.startsWith("issueProbeAfterRewalk") }, "no probe on Open path; got ${log.snapshot()}")
        assertEquals(
            listOf(NetworkChangeReason.WIFI_TO_CELLULAR), restartLog,
            "restart MUST fire on Open path so the next connect cycle dials Direct",
        )
        assertTrue(log.none { it.startsWith("revoke") }, "no revoke on happy Open path; got ${log.snapshot()}")
    }

    @Test
    fun Quiesced_gate_VALIDATED_CHANGED_does_NOT_issue_probe_or_disturb_quiescence() = runBlocking {
        // VALIDATED_CHANGED ⇒ clearsMode2Sticky=false. Under quiescence
        // the coordinator MUST run NO substeps: no probe, no teardown,
        // no restart, no sticky-pref clear. The pre-amend shape ran the
        // whole transaction and issued a probe — which would have
        // triggered a Direct recovery attempt on a NON-route-change.
        val log = CallLog()
        val gate = TracingGate(log, initialGate = WsReconnectGate.Quiesced(stickyGen = 1))
        val hybrid = TracingHybrid(log)
        val (coord, prefs, restartLog) = newCoordinator(log, gate, hybrid)
        coord.seedNetworkPresent(true)

        coord.onMeaningfulChange(NetworkChangeReason.VALIDATED_CHANGED, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("outcome:QuiescencePreserved:cms=false"), "got ${log.snapshot()}")
        assertTrue(log.none { it.startsWith("submitNetworkChangedEvent") }, "no submit; got ${log.snapshot()}")
        assertTrue(log.none { it.startsWith("disconnectAndJoin") })
        assertTrue(log.none { it == "release" })
        assertTrue(log.none { it.startsWith("issueProbeAfterRewalk") }, "no probe; got ${log.snapshot()}")
        assertTrue(restartLog.isEmpty(), "no restart on VALIDATED_CHANGED under quiescence")
        // Sticky preferences must NOT be cleared (don't disturb quiescence).
        assertTrue(prefs.lastWorkingTransport != null, "lastWorkingTransport preserved")
        assertTrue(prefs.lastSuccessAt != null, "lastSuccessAt preserved")
    }

    /**
     * Strengthened variant of [TracingGate] whose revoke methods
     * SUSPEND BEFORE writing the call log. This actually exercises the
     * `withContext(NonCancellable)` wrap in
     * [TransportRewalkCoordinator]'s catch path — without a real
     * suspension point, the non-NonCancellable shape would still
     * "complete" the revoke synchronously and the test would pass
     * regardless of the wrap. With the suspension, a cancelled parent
     * would throw CE on entry to `yield()` and the revoke would
     * NEVER record its log line.
     */
    private class SuspendingRevokeGate(
        private val log: CallLog,
        initialGate: WsReconnectGate = WsReconnectGate.Quiesced(stickyGen = 1),
    ) : RewalkCoordinatorGateProvider {
        private val _gate = MutableStateFlow(initialGate)
        override val gate: StateFlow<WsReconnectGate> = _gate.asStateFlow()
        var routeEpoch = 0L
            private set
        var issueResult: ProbeIssueResult = ProbeIssueResult.ProbeIssued(ProbeToken(0xCAFEL))

        override suspend fun beginRouteChange(clearsMode2Sticky: Boolean): RouteChangeOutcome {
            val current = _gate.value
            return when {
                current is WsReconnectGate.Open -> {
                    routeEpoch += 1
                    log.add("beginRouteChange:$routeEpoch")
                    log.add("outcome:OpenReconnect:cms=$clearsMode2Sticky")
                    RouteChangeOutcome.OpenReconnect(routeEpoch)
                }
                !clearsMode2Sticky -> {
                    log.add("beginRouteChange:$routeEpoch")
                    log.add("outcome:QuiescencePreserved:cms=$clearsMode2Sticky")
                    RouteChangeOutcome.QuiescencePreserved(routeEpoch)
                }
                else -> {
                    routeEpoch += 1
                    log.add("beginRouteChange:$routeEpoch")
                    log.add("outcome:StickyRecovery:cms=$clearsMode2Sticky")
                    RouteChangeOutcome.StickyRecovery(routeEpoch)
                }
            }
        }
        override suspend fun revokeRouteChange(routeEpoch: Long, reason: String) {
            // REAL suspension point — without `withContext(NonCancellable)`
            // wrap in the catch, this `yield()` would throw CE on a
            // cancelled parent, and `log.add` would NEVER fire.
            kotlinx.coroutines.yield()
            log.add("revokeRouteChange:$routeEpoch:$reason")
        }
        override suspend fun issueProbeAfterRewalk(routeEpoch: Long): ProbeIssueResult {
            log.add("issueProbeAfterRewalk:$routeEpoch")
            return issueResult
        }
        override suspend fun revokeProbe(routeEpoch: Long, reason: String) {
            // REAL suspension point — see revokeRouteChange comment above.
            kotlinx.coroutines.yield()
            log.add("revokeProbe:$routeEpoch:$reason")
        }
    }

    @Test
    fun real_cancellation_with_suspending_revokeProbe_still_completes_via_NonCancellable() = runBlocking {
        // Test gap #1 strengthening (2026-06-22). The original
        // `real_job_cancellation_in_restart_callback_still_revokes_probe_via_NonCancellable`
        // test used a [TracingGate] whose `revokeProbe` was a pure
        // `log.add(...)` with no suspension. Without the
        // `withContext(NonCancellable)` wrap, that test would have
        // passed anyway because the cancelled parent's CE never had
        // a suspension point to fire on.
        //
        // This variant uses [SuspendingRevokeGate] whose revoke methods
        // `yield()` BEFORE writing the log. The NonCancellable wrap is
        // now the SOLE reason the revoke completes — remove it and
        // the test would fail.
        val log = CallLog()
        val gate = SuspendingRevokeGate(log)
        val hybrid = TracingHybrid(log)
        val prefs = InMemoryPrefs()
        val rewalkScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            .also { livingScopes.add(it) }
        lateinit var coord: TransportRewalkCoordinator
        coord = TransportRewalkCoordinator(
            scope = rewalkScope,
            transportPreferences = prefs,
            releaseTransport = { log.add("release") },
            hybridTransportProvider = { hybrid },
            requestServiceRestart = { reason ->
                log.add("requestServiceRestart:${reason.name}")
                val jobField = coord.javaClass.getDeclaredField("currentRewalkJob").apply {
                    isAccessible = true
                }
                val job = jobField.get(coord) as? kotlinx.coroutines.Job
                job?.cancel(CancellationException("test cancel from restart"))
                throw CancellationException("test cancel from restart")
            },
            nowMs = { 1_000L },
            gateCoordinator = gate,
        )
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        assertTrue(log.contains("issueProbeAfterRewalk:1"))
        // The KEY assertion: the SUSPENDING revoke still completed.
        // Remove the `withContext(NonCancellable)` wrap and the
        // `yield()` inside `revokeProbe` would throw CE before
        // `log.add("revokeProbe:...")` runs.
        assertTrue(
            log.contains("revokeProbe:1:service_restart_cancelled"),
            "suspending revokeProbe MUST complete via NonCancellable wrap; got ${log.snapshot()}",
        )
    }

    @Test
    fun real_job_cancellation_in_restart_callback_still_revokes_probe_via_NonCancellable() = runBlocking {
        // P1 (ninth round, 2026-06-22): the catch (CancellationException)
        // path used to call coordinator.revokeProbe directly. Since
        // revokeProbe acquires gateLock under withLock, AND the parent
        // coroutine is ALREADY cancelled at the moment we reach the
        // catch, the gateLock.withLock would itself throw CE on entry
        // — leaving the gate at ProbeAvailable forever. The fix wraps
        // every catch-then-revoke in withContext(NonCancellable).
        //
        // This test simulates that scenario REALISTICALLY by cancelling
        // the rewalk coroutine FROM INSIDE the restart callback (so the
        // parent is genuinely cancelled by the time the catch runs)
        // instead of just throwing CancellationException manually.
        val log = CallLog()
        val gate = TracingGate(log)
        val hybrid = TracingHybrid(log)
        val prefs = InMemoryPrefs()
        val rewalkScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            .also { livingScopes.add(it) }
        // The lambda needs access to the currentRewalkJob so it can
        // cancel itself. The coordinator stores the job in
        // `currentRewalkJob`; we reach it via reflection (same seam
        // the awaitJobDone helper uses).
        lateinit var coord: TransportRewalkCoordinator
        coord = TransportRewalkCoordinator(
            scope = rewalkScope,
            transportPreferences = prefs,
            releaseTransport = { log.add("release") },
            hybridTransportProvider = { hybrid },
            requestServiceRestart = { reason ->
                log.add("requestServiceRestart:${reason.name}")
                // Cancel the coordinator's own job so the parent
                // coroutine is GENUINELY cancelled when our catch
                // runs. The `throw CancellationException("...")` is
                // what propagates out of restart's body; the actual
                // cancellation state was already set by the cancel()
                // above.
                val jobField = coord.javaClass.getDeclaredField("currentRewalkJob").apply {
                    isAccessible = true
                }
                val job = jobField.get(coord) as? kotlinx.coroutines.Job
                job?.cancel(CancellationException("test cancel from restart"))
                throw CancellationException("test cancel from restart")
            },
            nowMs = { 1_000L },
            gateCoordinator = gate,
        )
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        // The probe was issued (StickyRecovery path).
        assertTrue(log.contains("issueProbeAfterRewalk:1"))
        // The revoke MUST have completed despite parent cancellation —
        // the NonCancellable wrap is the only reason it could run to
        // completion (the suspend gate-lock acquire would otherwise
        // throw CE on entry to a cancelled coroutine).
        assertTrue(
            log.contains("revokeProbe:1:service_restart_cancelled"),
            "revokeProbe MUST run even when parent is cancelled; got ${log.snapshot()}",
        )
    }

    @Test
    fun serviceRestart_error_under_Open_path_revokes_route_change_without_bumping_rate_limit() = runBlocking {
        // Mirrors the production startService-failure scenario. On the
        // Open path no probe is issued; if requestServiceRestart throws
        // (e.g. AppContainer's startService returned null), the
        // coordinator MUST revokeRouteChange (not revokeProbe — there
        // is no probe) AND skip the lastRewalkAtMs bump so the next
        // recovery attempt is allowed inside the rate-limit window.
        val log = CallLog()
        val gate = TracingGate(log, initialGate = WsReconnectGate.Open)
        val hybrid = TracingHybrid(log)
        var t = 10_000L
        val (coord, _, _) = newCoordinator(log, gate, hybrid, restartBehaviour = "ex", nowMs = { t })
        coord.seedNetworkPresent(true)
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)

        // The Open-path failure must revoke the routeChange (no probe
        // issued, so no revokeProbe).
        assertTrue(
            log.contains("revokeRouteChange:1:service_restart_failed"),
            "got ${log.snapshot()}",
        )
        assertTrue(log.none { it.startsWith("revokeProbe") }, "no probe to revoke; got ${log.snapshot()}")

        // The rate-limit budget MUST NOT have been consumed. A second
        // forced attempt within the rate-limit window must trigger a
        // fresh beginRouteChange.
        t += 1_000L
        coord.onMeaningfulChange(NetworkChangeReason.WIFI_TO_CELLULAR, snapshot())
        awaitJobDone(coord)
        assertEquals(
            2, log.count("beginRouteChange:"),
            "second attempt must NOT be rate-limited after a service_restart failure; got ${log.snapshot()}",
        )
    }
}
