// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RC-RECONNECT-QUIESCENCE1 commit 2b (2026-06-22) — observable-boundary
 * tests for the [KtorRelayTransport.runReconnectLoop] integration with
 * [WsReconnectGateProvider]. End-to-end behavioural coverage of a real
 * WS dial is deferred (Test 20) per the locked schedule; here we pin
 * the contract at the gate-provider boundary across the 9 mandatory
 * invariants documented in the commit body.
 */
class KtorRelayTransportRunReconnectLoopTest {

    private val livingTransports = mutableListOf<KtorRelayTransport>()
    private val livingScopes = mutableListOf<CoroutineScope>()

    private fun newTransport(
        gateProvider: WsReconnectGateProvider?,
        clientFactory: (Int?) -> Any = { error("test must not invoke httpClientFactory") },
    ): KtorRelayTransport {
        @Suppress("UNCHECKED_CAST")
        val factory = clientFactory as (Int?) -> io.ktor.client.HttpClient
        return KtorRelayTransport(
            httpClientFactory = factory,
            initialGateProvider = gateProvider,
        ).also { livingTransports.add(it) }
    }

    @AfterTest
    fun closeAllTransports() = runBlocking {
        // RC-RECONNECT-QUIESCENCE1 commit 2e fix-round-4 P2 (2026-06-23).
        // See `KtorRelayTransportDisconnectAndJoinTest.closeAllTransports`
        // for the same teardown rationale: `closeForTest(): Boolean`
        // must fail loudly when `cleanupInflight` does not drain.
        //
        // fix-round-5 (2026-06-23): forensic `cleanupInflight` read also
        // bounded so a stuck mutex cannot re-hang `@AfterTest` during
        // diagnostic message construction.
        suspend fun forensicInflightOrUnavailable(t: KtorRelayTransport): String =
            withTimeoutOrNull(500L) { t.cleanupInflightForTest().toString() }
                ?: "unavailable"
        val failures = mutableListOf<String>()
        livingTransports.forEachIndexed { idx, t ->
            val outcome = runCatching {
                withTimeoutOrNull(6_000L) {
                    t.closeForTest(awaitInflightTimeoutMs = 5_000L)
                }
            }
            when {
                outcome.isFailure ->
                    failures.add(
                        "transport[$idx] closeForTest threw " +
                            outcome.exceptionOrNull(),
                    )
                outcome.getOrNull() == null ->
                    failures.add(
                        "transport[$idx] closeForTest did not return within " +
                            "6 s outer timeout; cleanupInflight=" +
                            forensicInflightOrUnavailable(t),
                    )
                outcome.getOrNull() == false ->
                    failures.add(
                        "transport[$idx] closeForTest reported stuck " +
                            "cleanupInflight after the 5 s drain window; " +
                            "cleanupInflight=${forensicInflightOrUnavailable(t)}",
                    )
            }
        }
        livingTransports.clear()
        livingScopes.forEach { runCatching { it.cancel() } }
        livingScopes.clear()
        if (failures.isNotEmpty()) {
            error(
                "@AfterTest teardown failed (${failures.size} failure(s)):\n" +
                    failures.joinToString("\n") { "  - $it" },
            )
        }
    }

    /** Counts allocate / await / validate / recordFailed calls. */
    private class TracingGateProvider(
        private val permitForAwait: (awaitIndex: Int, ownerGen: Long) -> WsReconnectPermit,
        private val validateResult: (callIndex: Int, permit: WsReconnectPermit) -> Boolean = { _, _ -> true },
        initialGate: WsReconnectGate = WsReconnectGate.Open,
    ) : WsReconnectGateProvider {
        private val _gate = MutableStateFlow<WsReconnectGate>(initialGate)
        override val gate: StateFlow<WsReconnectGate> = _gate.asStateFlow()
        fun setGateForTest(g: WsReconnectGate) { _gate.value = g }
        var allocateCount = 0
            private set
        var awaitCount = 0
            private set
        val validateCalls = mutableListOf<WsReconnectPermit>()
        val recordFailedCalls = mutableListOf<Pair<WsReconnectPermit.ClaimedProbe, String>>()
        val allocatedOwners = mutableListOf<Long>()

        override suspend fun allocateConnectionGeneration(): Long {
            allocateCount++
            val owner = allocateCount.toLong()
            allocatedOwners.add(owner)
            return owner
        }

        override suspend fun awaitReconnectPermit(ownerGeneration: Long): WsReconnectPermit {
            awaitCount++
            return permitForAwait(awaitCount, ownerGeneration)
        }

        override suspend fun validatePermitAfterAuth(permit: WsReconnectPermit): Boolean {
            validateCalls.add(permit)
            return validateResult(validateCalls.size, permit)
        }

        override suspend fun recordProbeAttemptFailed(
            permit: WsReconnectPermit.ClaimedProbe,
            reason: String,
        ) {
            recordFailedCalls.add(permit to reason)
        }
    }

    private suspend fun KtorRelayTransport.awaitLoopExit(timeoutMs: Long = 10_000): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            while (reconnectJobForTest()?.isCompleted != true) { delay(20) }
            true
        } ?: false
    }

    private fun runConnect(
        transport: KtorRelayTransport,
        scope: CoroutineScope,
    ) {
        scope.launch {
            runCatching {
                transport.connect(
                    relayUrl = "wss://fake/ws",
                    identityPublicKeyHex = "00".repeat(32),
                    signingPublicKeyHex = "11".repeat(32),
                    signChallenge = { ByteArray(64) },
                    socksProxyPort = null,
                )
            }
        }
    }

    // ── 1: connect() allocates ownerGeneration exactly once ──────────────────

    @Test
    fun connect_allocates_ownerGeneration_exactly_once_per_loop_launch() = runBlocking {
        val provider = TracingGateProvider(
            permitForAwait = { _, _ -> WsReconnectPermit.LoopRetired(reason = "test_loop_exit") },
        )
        val transport = newTransport(provider)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        livingScopes.add(scope)
        runConnect(transport, scope)
        assertTrue(transport.awaitLoopExit(5_000), "loop must exit on LoopRetired")
        assertEquals(1, provider.allocateCount, "connect() must allocate ownerGeneration exactly once")
        assertEquals(1, provider.awaitCount, "loop calls awaitReconnectPermit once on retire")
    }

    // ── 2: forceReconnect allocates a fresh ownerGeneration ─────────────────

    @Test
    fun forceReconnect_allocates_a_fresh_ownerGeneration_for_the_new_loop() = runBlocking {
        val provider = TracingGateProvider(
            permitForAwait = { _, _ -> WsReconnectPermit.LoopRetired(reason = "test_loop_exit") },
        )
        val transport = newTransport(provider)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        livingScopes.add(scope)
        runConnect(transport, scope)
        assertTrue(transport.awaitLoopExit(5_000), "first loop must exit on LoopRetired")
        assertEquals(1, provider.allocateCount, "first loop allocated once")

        transport.forceReconnect()
        // Wait until the SECOND loop has also exited via LoopRetired.
        withTimeoutOrNull(5_000) {
            while (provider.allocateCount < 2) { delay(10) }
        }
        assertEquals(2, provider.allocateCount, "forceReconnect must allocate a fresh ownerGeneration")
        assertEquals(listOf(1L, 2L), provider.allocatedOwners)
    }

    // ── 3: LoopRetired exits the loop BEFORE creating an HttpClient ─────────

    @Test
    fun LoopRetired_exits_loop_before_creating_HttpClient() = runBlocking {
        var clientFactoryInvocations = 0
        val provider = TracingGateProvider(
            permitForAwait = { _, _ -> WsReconnectPermit.LoopRetired(reason = "owner_generation_stale") },
        )
        val transport = newTransport(
            gateProvider = provider,
            clientFactory = {
                clientFactoryInvocations++
                error("LoopRetired path must NOT reach httpClientFactory")
            },
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        livingScopes.add(scope)
        runConnect(transport, scope)
        assertTrue(transport.awaitLoopExit(5_000))
        assertEquals(0, clientFactoryInvocations, "LoopRetired short-circuits before httpClientFactory")
        val job = transport.reconnectJobForTest()
        assertNotNull(job)
        assertTrue(job.isCompleted, "loop exited cleanly")
    }

    // ── 4: ClaimedProbe reused across retries (no re-claim) ─────────────────

    @Test
    fun ClaimedProbe_is_reused_across_retries_until_validate_clears_it() = runBlocking {
        val claim = WsReconnectPermit.ClaimedProbe(
            stickyGen = 1,
            routeEpoch = 1L,
            token = ProbeToken(0xCAFEL),
            ownerGeneration = 1L,
            budget = ProbeBudget(
                budgetStartedAtMs = 0L,
                maxAttempts = ProbeBudget.MAX_ATTEMPTS_LOCKED,
                maxElapsedMs = ProbeBudget.MAX_ELAPSED_MS_LOCKED,
            ),
        )
        var clientFactoryInvocations = 0
        val provider = TracingGateProvider(
            permitForAwait = { idx, _ ->
                if (idx == 1) claim
                else WsReconnectPermit.LoopRetired(reason = "test_post_validate_clear")
            },
            // Validate returns true on the FIRST failure (carrying the
            // claim), false on the SECOND — clears carried claim, next
            // await returns Retired. Keeps the test under the
            // exponential-backoff ceiling.
            validateResult = { callIndex, _ -> callIndex < 2 },
        )
        val transport = newTransport(
            gateProvider = provider,
            clientFactory = {
                clientFactoryInvocations++
                error("simulate http client construction failure")
            },
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        livingScopes.add(scope)
        runConnect(transport, scope)
        assertTrue(transport.awaitLoopExit(20_000), "loop must eventually exit")

        assertEquals(1, provider.allocateCount, "owner allocated exactly once per loop launch")
        assertTrue(
            provider.awaitCount >= 2,
            "loop must re-await after carried claim is cleared; got ${provider.awaitCount}",
        )
        val sameIdentityCalls = provider.recordFailedCalls.takeWhile { it.first === claim }
        assertTrue(
            sameIdentityCalls.isNotEmpty(),
            "at least one recordFailed call must have the carried claim's identity",
        )
        assertTrue(
            clientFactoryInvocations >= 1,
            "loop attempted to create an http client on the ClaimedProbe iteration",
        )
    }

    // ── 5: CancellationException propagates from gate await ─────────────────

    @Test
    fun CancellationException_from_gate_await_propagates_through_runReconnectLoop() = runBlocking {
        val provider = TracingGateProvider(
            permitForAwait = { _, _ -> throw CancellationException("test cancel from gate await") },
        )
        val transport = newTransport(provider)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        livingScopes.add(scope)
        val callerOutcome = CompletableDeferred<String>()
        scope.launch {
            try {
                transport.connect(
                    relayUrl = "wss://fake/ws",
                    identityPublicKeyHex = "00".repeat(32),
                    signingPublicKeyHex = "11".repeat(32),
                    signChallenge = { ByteArray(64) },
                    socksProxyPort = null,
                )
                callerOutcome.complete("returned_normally")
            } catch (_: CancellationException) {
                callerOutcome.complete("ce_propagated")
            } catch (t: Throwable) {
                callerOutcome.complete("other:${t::class.simpleName}")
            }
        }
        assertTrue(transport.awaitLoopExit(5_000), "loop must exit on CE from gate await")
        val job = transport.reconnectJobForTest()
        assertNotNull(job)
        assertTrue(
            job.isCancelled || job.isCompleted,
            "reconnect-loop job must complete (cancelled OR completed); CE not swallowed",
        )
    }

    // ── 7..9: forceReconnect honours quiescence with REAL no-op semantics ────
    //
    // Review amendment: the previous shape allowed forceReconnect() to
    // launch a fresh loop unconditionally; that loop suspended on
    // `awaitReconnectPermit` (no socket opened), but it also cancelled
    // the OLD loop — orphaning a ProbeClaimed because the cancellation
    // exits via CancellationException, NOT via the
    // `recordProbeAttemptFailed` failure path. With no budget debit, the
    // claim is permanently held by a cancelled owner, and the fresh
    // loop suspends forever (PROBE_ALREADY_CLAIMED). The fix: typed
    // no-op at the top of forceReconnect when the gate is not Open.

    @Test
    fun forceReconnect_during_ProbeClaimed_is_a_typed_no_op() = runBlocking {
        val provider = TracingGateProvider(
            permitForAwait = { _, _ -> WsReconnectPermit.LoopRetired(reason = "test_loop_exit") },
            initialGate = WsReconnectGate.ProbeClaimed(
                stickyGen = 1,
                routeEpoch = 1L,
                token = ProbeToken(0xCAFEL),
                ownerGeneration = 1L,
                budget = ProbeBudget(
                    budgetStartedAtMs = 0L,
                    maxAttempts = ProbeBudget.MAX_ATTEMPTS_LOCKED,
                    maxElapsedMs = ProbeBudget.MAX_ELAPSED_MS_LOCKED,
                ),
            ),
        )
        var clientFactoryInvocations = 0
        val transport = newTransport(
            gateProvider = provider,
            clientFactory = {
                clientFactoryInvocations++
                error("forceReconnect in ProbeClaimed must NOT reach httpClientFactory")
            },
        )
        // The original `connect()` allocates ownerGeneration=1 + loop
        // immediately exits via LoopRetired (its purpose here is just
        // to exercise connect/launch path; the gate state was already
        // ProbeClaimed BEFORE connect started, so the loop's first
        // awaitReconnectPermit suspends on the non-actionable state.
        // To keep the test simple we let it Retire instead).
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        livingScopes.add(scope)
        runConnect(transport, scope)
        assertTrue(transport.awaitLoopExit(5_000))
        val allocateCountBefore = provider.allocateCount
        val invocationsBefore = clientFactoryInvocations

        // forceReconnect MUST be a no-op: no allocate, no client.
        transport.forceReconnect()
        // Brief settle.
        delay(80)

        assertEquals(
            allocateCountBefore, provider.allocateCount,
            "forceReconnect in ProbeClaimed must NOT allocate a fresh ownerGeneration; " +
                "before=$allocateCountBefore after=${provider.allocateCount}",
        )
        assertEquals(
            invocationsBefore, clientFactoryInvocations,
            "forceReconnect in ProbeClaimed must NOT reach httpClientFactory",
        )
        // The gate is left untouched — claim still held by the prior owner.
        val g = provider.gate.value
        assertTrue(
            g is WsReconnectGate.ProbeClaimed,
            "gate must stay ProbeClaimed for the prior owner; got $g",
        )
    }

    @Test
    fun forceReconnect_during_Quiesced_is_a_typed_no_op() = runBlocking {
        val provider = TracingGateProvider(
            permitForAwait = { _, _ -> WsReconnectPermit.LoopRetired(reason = "test_loop_exit") },
            initialGate = WsReconnectGate.Quiesced(stickyGen = 1),
        )
        var clientFactoryInvocations = 0
        val transport = newTransport(
            gateProvider = provider,
            clientFactory = {
                clientFactoryInvocations++
                error("forceReconnect in Quiesced must NOT reach httpClientFactory")
            },
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        livingScopes.add(scope)
        runConnect(transport, scope)
        assertTrue(transport.awaitLoopExit(5_000))
        val allocateCountBefore = provider.allocateCount

        transport.forceReconnect()
        delay(80)

        assertEquals(
            allocateCountBefore, provider.allocateCount,
            "forceReconnect in Quiesced must NOT allocate",
        )
        assertEquals(
            0, clientFactoryInvocations,
            "forceReconnect in Quiesced must NOT reach httpClientFactory",
        )
        assertTrue(provider.gate.value is WsReconnectGate.Quiesced)
    }

    @Test
    fun forceReconnect_during_CandidateProving_is_a_typed_no_op() = runBlocking {
        val provider = TracingGateProvider(
            permitForAwait = { _, _ -> WsReconnectPermit.LoopRetired(reason = "test_loop_exit") },
            initialGate = WsReconnectGate.CandidateProving(stickyGen = 1, sessionEpoch = 7L),
        )
        var clientFactoryInvocations = 0
        val transport = newTransport(
            gateProvider = provider,
            clientFactory = {
                clientFactoryInvocations++
                error("forceReconnect in CandidateProving must NOT reach httpClientFactory")
            },
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        livingScopes.add(scope)
        runConnect(transport, scope)
        assertTrue(transport.awaitLoopExit(5_000))
        val allocateCountBefore = provider.allocateCount

        transport.forceReconnect()
        delay(80)

        assertEquals(
            allocateCountBefore, provider.allocateCount,
            "forceReconnect in CandidateProving must NOT allocate",
        )
        assertEquals(
            0, clientFactoryInvocations,
            "forceReconnect in CandidateProving must NOT reach httpClientFactory",
        )
        val g = provider.gate.value
        assertTrue(
            g is WsReconnectGate.CandidateProving,
            "gate must stay CandidateProving; got $g",
        )
        assertEquals(7L, (g as WsReconnectGate.CandidateProving).sessionEpoch)
    }

    // ── 10: WsSessionLifecycleEvent.Connected carries connectionGeneration ──

    @Test
    fun WsSessionLifecycleEvent_Connected_carries_connectionGeneration_field() {
        val connected = WsSessionLifecycleEvent.Connected(
            sessionEpoch = 42L,
            connectionGeneration = 7L,
        )
        assertEquals(42L, connected.sessionEpoch)
        assertEquals(7L, connected.connectionGeneration)
        // Source-compatible default for pre-quiescence emit sites.
        val legacy = WsSessionLifecycleEvent.Connected(sessionEpoch = 1L)
        assertEquals(0L, legacy.connectionGeneration)
    }
}
