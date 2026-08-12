// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import phantom.core.crypto.LibsodiumX3DH
import phantom.core.identity.IdentityKeyPair
import phantom.core.identity.IdentityManager
import phantom.core.identity.IdentityRecord
import phantom.core.identity.IdentityRepository
import phantom.core.identity.LibsodiumIdentityCrypto
import phantom.core.storage.LocalOneTimePreKeyEntity
import phantom.core.storage.LocalOneTimePreKeyRepository
import phantom.core.storage.LocalSignedPreKeyEntity
import phantom.core.storage.LocalSignedPreKeyRepository
import phantom.core.transport.FetchStatusDeadlineExceededException
import phantom.core.transport.PreKeyApi
import phantom.core.transport.PreKeyBodyTruncatedException
import phantom.core.transport.PreKeyBundle
import phantom.core.transport.PreKeyStatus
import phantom.core.transport.PublishRequest
import phantom.core.transport.PublishResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.fail

/**
 * CLIENT-PREKEY-SELFHEAL — service-layer test rows from
 * `docs/tracks/client-prekey-selfheal.md` §7:
 *
 *   T3, T5, T6a, T6b, T8-service, T17, T18,
 *   T-P0-4, T-F1a (messaging half), T-F1b,
 *   T-F3-service, T-F9, T-F18
 *
 * Exercises the [PreKeyLifecycleService] orchestration surface: verify
 * single-flight ([D3]), [VerifyOutcome] classification, the per-session
 * force-republish budget ([D7]), CancellationException propagation
 * discipline ([D4]), and the periodic ticker survival guarantee ([D2]).
 *
 * Every marker assertion goes through the injected `logObserver` seam
 * ([D8]); no direct log-sink access. All virtual time via `runTest` —
 * no real-time waits.
 */
class PreKeyLifecycleServiceSelfHealTest {

    // ── Test rig ────────────────────────────────────────────────────────────

    private class InMemoryIdentityRepository(seed: IdentityRecord?) : IdentityRepository {
        private var stored: IdentityRecord? = seed
        override suspend fun createIdentity(username: String): IdentityKeyPair =
            error("unused")
        override suspend fun loadIdentity(): IdentityRecord? = stored
        override suspend fun saveIdentity(record: IdentityRecord) { stored = record }
        override suspend fun deleteIdentity() { stored = null }
    }

    private class InMemorySignedPreKeyRepo : LocalSignedPreKeyRepository {
        private var stored: LocalSignedPreKeyEntity? = null
        override suspend fun get() = stored
        override suspend fun upsert(entity: LocalSignedPreKeyEntity) { stored = entity }
        override suspend fun clear() { stored = null }
    }

    private class InMemoryOneTimePreKeyRepo : LocalOneTimePreKeyRepository {
        val store = mutableMapOf<String, LocalOneTimePreKeyEntity>()
        override suspend fun get(keyIdHex: String) = store[keyIdHex]
        override suspend fun getAll() = store.values.toList()
        override suspend fun count() = store.size
        override suspend fun insert(entity: LocalOneTimePreKeyEntity) {
            store[entity.keyIdHex] = entity
        }
        override suspend fun insertAll(entities: List<LocalOneTimePreKeyEntity>) {
            entities.forEach { store[it.keyIdHex] = it }
        }
        override suspend fun deleteByKeyId(keyIdHex: String) { store.remove(keyIdHex) }
        override suspend fun clear() { store.clear() }
    }

    /**
     * Self-heal test fake with the knobs the CLIENT-PREKEY-SELFHEAL §7
     * test infra prereqs enumerate:
     *
     *  - `fetchStatusThrower(attempt) -> Throwable?` — inject controlled
     *    throws (per D8 marker taxonomy tests).
     *  - `fetchStatusGate` — suspend fetchStatus on a gate so the test
     *    can arrange the concurrent-trigger race (T3).
     *  - `publishThrower` — force publishBundle to throw so the budget
     *    stays at 0 (T5, T17).
     *  - `publishGate` + `publishStarted` — suspend publish inside a
     *    NonCancellable region so we can cancel the parent job between
     *    Stored and the budget commit (T-F9).
     *  - Per-call replayable `fetchStatusQueue` — for T6* where the
     *    Nth verify sees a different status than the (N+1)th.
     */
    private class SelfHealFakePreKeyApi : PreKeyApi {
        var publishCount: Int = 0
        val capturedRequests: MutableList<PublishRequest> = mutableListOf()
        var lastForceJoinInFlight: Boolean = false
        var publishResult: PublishResult = PublishResult.Stored(storedOpks = 42)
        var publishThrower: ((attempt: Int) -> Throwable?)? = null
        var publishGate: CompletableDeferred<Unit>? = null
        val publishStarted: CompletableDeferred<Unit> = CompletableDeferred()
        // NonCancellable wrap the gate wait so the parent test can cancel
        // the outer job while the fake is suspended without the fake
        // itself receiving CE (T-F9).
        var publishInsideNonCancellable: Boolean = false

        var statusCount: Int = 0
        var fetchStatusResult: PreKeyStatus =
            PreKeyStatus(remaining_opks = 0, signed_prekey_age_days = null)
        var fetchStatusQueue: ArrayDeque<PreKeyStatus>? = null
        var fetchStatusThrower: ((attempt: Int) -> Throwable?)? = null
        var fetchStatusGate: CompletableDeferred<Unit>? = null

        override suspend fun publishBundle(
            forceJoinInFlight: Boolean,
            requestProvider: suspend () -> PublishRequest,
        ): PublishResult {
            publishCount++
            lastForceJoinInFlight = forceJoinInFlight
            val request = requestProvider()
            capturedRequests += request
            publishThrower?.invoke(publishCount)?.let { throw it }
            val g = publishGate
            if (g != null) {
                if (publishInsideNonCancellable) {
                    withContext(NonCancellable) {
                        publishStarted.complete(Unit)
                        g.await()
                    }
                } else {
                    publishStarted.complete(Unit)
                    g.await()
                }
            }
            return publishResult
        }

        override suspend fun fetchBundle(
            identityPubkeyHex: String,
            requesterPubkeyHex: String?,
        ): PreKeyBundle? = null

        override suspend fun fetchStatus(
            identityPubkeyHex: String,
            requesterPubkeyHex: String?,
        ): PreKeyStatus {
            statusCount++
            fetchStatusThrower?.invoke(statusCount)?.let { throw it }
            fetchStatusGate?.await()
            val q = fetchStatusQueue
            return if (q != null && q.isNotEmpty()) q.removeFirst() else fetchStatusResult
        }
    }

    private data class Rig(
        val service: PreKeyLifecycleService,
        val spkRepo: InMemorySignedPreKeyRepo,
        val opkRepo: InMemoryOneTimePreKeyRepo,
        val api: SelfHealFakePreKeyApi,
        val markers: MutableList<String>,
        val nowMsHolder: Array<Long>,
    )

    private suspend fun makeAlpha2Identity(): IdentityManager {
        val identityRepo = InMemoryIdentityRepository(seed = null)
        val manager = IdentityManager(LibsodiumIdentityCrypto(), identityRepo)
        manager.createOrLoad("alice")
        return manager
    }

    private suspend fun makeRig(nowMs: Long = 1_700_000_000_000L): Rig {
        LibsodiumInitializer.initialize()
        val identityManager = makeAlpha2Identity()
        val spkRepo = InMemorySignedPreKeyRepo()
        val opkRepo = InMemoryOneTimePreKeyRepo()
        val api = SelfHealFakePreKeyApi()
        val markers = mutableListOf<String>()
        val nowHolder = arrayOf(nowMs)
        val service = PreKeyLifecycleService(
            identityManager = identityManager,
            signedPreKeyRepository = spkRepo,
            oneTimePreKeyRepository = opkRepo,
            preKeyApi = api,
            x3dh = LibsodiumX3DH(),
            nowMsProvider = { nowHolder[0] },
            logObserver = { markers += it },
        )
        return Rig(service, spkRepo, opkRepo, api, markers, nowHolder)
    }

    private suspend fun bootstrapAndReset(rig: Rig) {
        rig.service.bootstrapForNewIdentity().getOrThrow()
        rig.api.publishCount = 0
        rig.api.statusCount = 0
        rig.api.lastForceJoinInFlight = false
        rig.api.capturedRequests.clear()
        // Bootstrap emits messagingLog lines directly, but only
        // trace()-routed markers appear in `markers`. Clear anyway so
        // downstream assertions read only post-bootstrap noise.
        rig.markers.clear()
    }

    // ── T3 ──────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun verifyBundleOnRelay_concurrentTriggers_producesOneActiveVerify_otherReturnsSkippedInFlight() =
        runTest {
            val rig = makeRig()
            bootstrapAndReset(rig)

            // The first verify holds the mutex while suspended on the gate
            // inside fetchStatus. The second verify must observe tryLock()
            // failure and return SkippedInFlight without touching the wire.
            val gate = CompletableDeferred<Unit>()
            rig.api.fetchStatusGate = gate
            rig.api.fetchStatusResult =
                PreKeyStatus(remaining_opks = 50, signed_prekey_age_days = 3)

            val call1 = async { rig.service.verifyBundleOnRelay(VerifyTrigger.Connected) }
            advanceUntilIdle()
            assertTrue(call1.isActive, "call1 must be suspended on the fetch gate")

            val call2 = async { rig.service.verifyBundleOnRelay(VerifyTrigger.Periodic) }
            advanceUntilIdle()
            assertTrue(call2.isCompleted, "call2 must short-circuit via SkippedInFlight")

            val r2 = call2.await()
            assertTrue(r2.isSuccess)
            assertEquals(
                VerifyOutcome.SkippedInFlight, r2.getOrNull(),
                "second concurrent trigger MUST see SkippedInFlight (single-flight D3)",
            )
            assertTrue(
                rig.markers.any { "verify_skip_single_flight trigger=Periodic" in it },
                "SkippedInFlight path MUST emit the verify_skip_single_flight marker " +
                    "with the trigger field; got markers=${rig.markers}",
            )

            gate.complete(Unit)
            val r1 = call1.await()
            assertTrue(r1.isSuccess)
            assertEquals(
                VerifyOutcome.AlreadyPublished, r1.getOrNull(),
                "call1 must have run verifyLocked and reached AlreadyPublished",
            )
            assertEquals(
                1, rig.api.statusCount,
                "exactly one fetchStatus round-trip — the second trigger short-circuited",
            )
            assertEquals(
                0, rig.api.publishCount,
                "no publish — relay reports the bundle is already present",
            )
        }

    // ── T5 ──────────────────────────────────────────────────────────────────

    @Test
    fun verifyBundleOnRelay_transientPublishFailure_doesNotConsumeBudget() = runTest {
        val rig = makeRig()
        bootstrapAndReset(rig)

        // Zero-record status → verify tries the force-republish path.
        // The publish helper throws a transient class every attempt, so
        // it never reaches PublishExecutionOutcome.Stored → the D7
        // NonCancellable increment never runs.
        rig.api.fetchStatusResult =
            PreKeyStatus(remaining_opks = 0, signed_prekey_age_days = null)
        rig.api.publishThrower = { throw IllegalStateException("transient — never reaches Stored") }

        repeat(6) { i ->
            val r = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
            assertTrue(
                r.isFailure,
                "iteration ${i + 1}: publish throws → verifyLocked propagates → Result.failure; got $r",
            )
        }

        // Observable proof that the counter never advanced past 0:
        // switch to a confirmed status (age != null) and run one more
        // verify. If ANY of the six prior failures had committed a slot,
        // this call would emit `verify_bundle_confirmed_reset_budget
        // prior=N` (N ≥ 1). It must NOT.
        rig.api.publishThrower = null
        rig.api.fetchStatusResult =
            PreKeyStatus(remaining_opks = 50, signed_prekey_age_days = 1)
        rig.markers.clear()

        val confirmed = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
        assertTrue(confirmed.isSuccess)
        assertEquals(VerifyOutcome.AlreadyPublished, confirmed.getOrNull())
        assertFalse(
            rig.markers.any { "verify_bundle_confirmed_reset_budget" in it },
            "the reset marker MUST NOT fire — the counter was never > 0 because " +
                "every transient publish failure short-circuited BEFORE the D7 increment; " +
                "got markers=${rig.markers}",
        )
    }

    // ── T6a ─────────────────────────────────────────────────────────────────

    @Test
    fun Stored_consumesOneSlot_confirmedStatusResetsIt() = runTest {
        val rig = makeRig()
        bootstrapAndReset(rig)

        // First verify: zero-record → Stored → count becomes 1.
        rig.api.fetchStatusResult =
            PreKeyStatus(remaining_opks = 0, signed_prekey_age_days = null)
        val first = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
        assertTrue(first.isSuccess)
        assertTrue(
            first.getOrNull() is VerifyOutcome.Republished,
            "first verify MUST Republish; got ${first.getOrNull()}",
        )
        assertEquals(1, rig.api.publishCount)
        assertFalse(
            rig.markers.any { "verify_bundle_confirmed_reset_budget" in it },
            "first (Stored) verify MUST NOT emit reset marker",
        )

        // Second verify: relay confirms the record. Count MUST reset
        // and emit `verify_bundle_confirmed_reset_budget prior=1`.
        rig.api.fetchStatusResult =
            PreKeyStatus(remaining_opks = 50, signed_prekey_age_days = 0)
        rig.markers.clear()
        val second = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
        assertTrue(second.isSuccess)
        assertEquals(VerifyOutcome.AlreadyPublished, second.getOrNull())
        assertTrue(
            rig.markers.any { "verify_bundle_confirmed_reset_budget prior=1" in it },
            "confirmed status with count>0 MUST emit the reset marker with prior=1; " +
                "got markers=${rig.markers}",
        )
    }

    // ── T6b ─────────────────────────────────────────────────────────────────

    @Test
    fun `5consecutiveUnconfirmed_exhaustBudget_recoveredOnConfirm`() = runTest {
        val rig = makeRig()
        bootstrapAndReset(rig)

        rig.api.fetchStatusResult =
            PreKeyStatus(remaining_opks = 0, signed_prekey_age_days = null)

        val max = PreKeyLifecycleService.MAX_FORCE_REPUBLISH_PER_SESSION
        repeat(max) { i ->
            val r = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
            assertTrue(
                r.getOrNull() is VerifyOutcome.Republished,
                "iteration ${i + 1}: within budget must Republish; got ${r.getOrNull()}",
            )
        }
        assertEquals(max, rig.api.publishCount, "each of $max in-budget calls published")

        // (max + 1)th call: budget exhausted, no publish attempted.
        val overflow = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
        assertTrue(overflow.isSuccess)
        assertEquals(VerifyOutcome.BudgetExhausted, overflow.getOrNull())
        assertEquals(
            max, rig.api.publishCount,
            "budget-exhausted call MUST NOT publish (D7 Inv-NoSpinningRetry)",
        )

        // Confirmed status → reset budget with prior=$max.
        rig.api.fetchStatusResult =
            PreKeyStatus(remaining_opks = 50, signed_prekey_age_days = 2)
        rig.markers.clear()
        val confirmed = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
        assertTrue(confirmed.isSuccess)
        assertEquals(VerifyOutcome.AlreadyPublished, confirmed.getOrNull())
        assertTrue(
            rig.markers.any { "verify_bundle_confirmed_reset_budget prior=$max" in it },
            "confirmed status MUST emit reset marker with prior=$max; got markers=${rig.markers}",
        )

        // After reset, zero-record verify Republishes again.
        rig.api.fetchStatusResult =
            PreKeyStatus(remaining_opks = 0, signed_prekey_age_days = null)
        val recovered = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
        assertTrue(
            recovered.getOrNull() is VerifyOutcome.Republished,
            "post-reset zero-record verify MUST Republish; got ${recovered.getOrNull()}",
        )
        assertEquals(max + 1, rig.api.publishCount, "one more publish after budget reset")
    }

    // ── T8-service ──────────────────────────────────────────────────────────

    @Test
    fun verifyBundleOnRelay_CancellationExceptionPropagates_notWrappedInResult() = runTest {
        val rig = makeRig()
        bootstrapAndReset(rig)

        // Attempt 1: fetchStatus throws CE. Attempt 2 (follow-up after CE):
        // fetchStatus returns cleanly, proving verifyMutex was released.
        rig.api.fetchStatusThrower = { attempt ->
            if (attempt == 1) CancellationException("simulated external cancel") else null
        }
        rig.api.fetchStatusResult =
            PreKeyStatus(remaining_opks = 50, signed_prekey_age_days = 4)

        var thrown: Throwable? = null
        try {
            rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
            fail("verifyBundleOnRelay MUST throw CE — never wrap it in Result.failure")
        } catch (ce: CancellationException) {
            thrown = ce
        }
        assertNotNull(thrown, "CE must have been thrown")

        // Mutex released: a follow-up verify must NOT return SkippedInFlight.
        val followup = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
        assertTrue(followup.isSuccess, "follow-up call must succeed; got $followup")
        assertNotEquals(
            VerifyOutcome.SkippedInFlight, followup.getOrNull(),
            "follow-up MUST NOT be SkippedInFlight — verifyMutex must have released in finally",
        )
        assertEquals(
            VerifyOutcome.AlreadyPublished, followup.getOrNull(),
            "follow-up sees confirmed relay status; got ${followup.getOrNull()}",
        )
    }

    // ── T17 ─────────────────────────────────────────────────────────────────

    @Test
    fun budget_documented_via_unittest_semantics_matchesKDoc() = runTest {
        // Property-style: transient×N + Stored×M + confirmed×K.
        // Formula (per D7 KDoc):
        //   count = M   when K == 0
        //   count = 0   when K >= 1 (any confirmed status resets)
        // Observed via `verify_bundle_confirmed_reset_budget prior=<count>`
        // when a confirmed status finally runs after N + M unconfirmed cycles.
        val rig = makeRig()
        bootstrapAndReset(rig)

        rig.api.fetchStatusResult =
            PreKeyStatus(remaining_opks = 0, signed_prekey_age_days = null)

        // N = 3 transient publish failures → force-republish count stays at 0
        // (transient throw occurs BEFORE the D7 NonCancellable increment).
        rig.api.publishThrower = { throw IllegalStateException("transient N=$it") }
        repeat(3) {
            val r = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
            assertTrue(r.isFailure, "transient iteration ${it + 1} must fail; got $r")
        }
        val publishAttemptsAfterTransient = rig.api.publishCount
        assertEquals(
            3, publishAttemptsAfterTransient,
            "3 publish invocations attempted (each threw before D7 increment)",
        )

        // M = 2 successful zero-record Stored publishes → count becomes 2.
        rig.api.publishThrower = null
        rig.api.publishResult = PublishResult.Stored(storedOpks = 100)
        repeat(2) {
            val r = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
            assertTrue(
                r.getOrNull() is VerifyOutcome.Republished,
                "Stored iteration ${it + 1} must Republish; got ${r.getOrNull()}",
            )
        }
        assertEquals(
            publishAttemptsAfterTransient + 2, rig.api.publishCount,
            "2 additional publish invocations, both Stored",
        )

        // K = 1 confirmed → reset marker prior=2, count → 0.
        rig.api.fetchStatusResult =
            PreKeyStatus(remaining_opks = 50, signed_prekey_age_days = 3)
        rig.markers.clear()
        val confirm = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
        assertEquals(VerifyOutcome.AlreadyPublished, confirm.getOrNull())
        assertTrue(
            rig.markers.any { "verify_bundle_confirmed_reset_budget prior=2" in it },
            "K=1 confirmed after M=2 Stored MUST emit reset with prior=2; " +
                "got markers=${rig.markers}",
        )

        // Post-reset: another confirmed must NOT re-emit reset (count is 0).
        rig.markers.clear()
        val postReset = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
        assertEquals(VerifyOutcome.AlreadyPublished, postReset.getOrNull())
        assertFalse(
            rig.markers.any { "verify_bundle_confirmed_reset_budget" in it },
            "post-reset confirmed MUST NOT re-emit reset marker (count==0)",
        )
    }

    // ── T18 ─────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun existing_raceRepro_test_still_passes_under_new_budget_semantics() = runTest {
        // Regression guard mirroring
        // PreKeyLifecycleServiceTest.raceRepro_bootstrapFails_verifyTriggersForceRepublish_bundleReachesRelay.
        // Two publishes: bootstrap #1 holds the fake's publishMutex and
        // dies; verify #2 (force-join) waits on that mutex, then runs.
        // Invariants: Inv-RetryAfterFail + Inv-ForcePathOnZeroRecord —
        // must survive the budget-accounting rewrite that moved the
        // counter mutation into the NonCancellable post-Stored block.
        LibsodiumInitializer.initialize()
        val identityManager = makeAlpha2Identity()

        val firstPublishGate = CompletableDeferred<PublishResult>()
        val api = RaceReproFakePreKeyApi(
            firstPublishGate = firstPublishGate,
            secondPublishResult = PublishResult.Stored(storedOpks = 100),
            statusResult = PreKeyStatus(remaining_opks = 0, signed_prekey_age_days = null),
        )
        val spkRepo = InMemorySignedPreKeyRepo()
        val opkRepo = InMemoryOneTimePreKeyRepo()
        val service = PreKeyLifecycleService(
            identityManager = identityManager,
            signedPreKeyRepository = spkRepo,
            oneTimePreKeyRepository = opkRepo,
            preKeyApi = api,
            x3dh = LibsodiumX3DH(),
            nowMsProvider = { 1_700_000_000_000L },
        )

        val bootstrapJob = async { service.bootstrapForNewIdentity() }
        api.publishOneStarted.await()
        assertEquals(1, api.publishStartCount)
        assertEquals(listOf(false), api.forceFlags)

        val verifyJob = async { service.verifyBundleOnRelay(VerifyTrigger.Connected) }
        advanceUntilIdle()
        assertTrue(
            verifyJob.isActive,
            "verify-driven publish #2 MUST suspend on publishMutex while #1 holds it",
        )
        assertEquals(1, api.publishStartCount)

        // Bootstrap publish dies.
        firstPublishGate.completeExceptionally(
            RuntimeException("simulated ECONNREFUSED — in-flight publish died"),
        )

        val verifyResult = verifyJob.await()
        val bootstrapResult = bootstrapJob.await()

        assertEquals(
            2, api.publishStartCount,
            "publish #2 MUST reach the work section after #1 released the mutex",
        )
        assertEquals(
            listOf(false, true), api.forceFlags,
            "publish #1 defaults to force=false; verify-driven #2 force=true (Inv-ForcePathOnZeroRecord)",
        )
        assertTrue(verifyResult.isSuccess, "verify MUST succeed; got $verifyResult")
        assertTrue(
            verifyResult.getOrNull() is VerifyOutcome.Republished,
            "verify #2 MUST report Republished; got ${verifyResult.getOrNull()}",
        )
        assertTrue(bootstrapResult.isFailure, "bootstrap MUST surface publish #1's failure")
    }

    private class RaceReproFakePreKeyApi(
        private val firstPublishGate: CompletableDeferred<PublishResult>,
        private val secondPublishResult: PublishResult,
        private val statusResult: PreKeyStatus,
    ) : PreKeyApi {
        private val publishMutex: Mutex = Mutex()
        val publishOneStarted: CompletableDeferred<Unit> = CompletableDeferred()
        var publishStartCount: Int = 0
        val forceFlags: MutableList<Boolean> = mutableListOf()

        override suspend fun publishBundle(
            forceJoinInFlight: Boolean,
            requestProvider: suspend () -> PublishRequest,
        ): PublishResult {
            if (!publishMutex.tryLock()) {
                if (!forceJoinInFlight) return PublishResult.Deferred
                publishMutex.lock()
            }
            return try {
                publishStartCount += 1
                forceFlags += forceJoinInFlight
                requestProvider()
                if (publishStartCount == 1) {
                    publishOneStarted.complete(Unit)
                    firstPublishGate.await()
                } else {
                    secondPublishResult
                }
            } finally {
                publishMutex.unlock()
            }
        }

        override suspend fun fetchBundle(
            identityPubkeyHex: String,
            requesterPubkeyHex: String?,
        ): PreKeyBundle? = null

        override suspend fun fetchStatus(
            identityPubkeyHex: String,
            requesterPubkeyHex: String?,
        ): PreKeyStatus = statusResult
    }

    // ── T-P0-4 ──────────────────────────────────────────────────────────────

    @Test
    fun bootstrapReplenishRotate_Stored_doNOTConsumeForceRepublishBudget() = runTest {
        // D7 invariant: only verifyLocked's force-join Stored branch
        // touches forceRepublishCount. Bootstrap / replenish / rotate all
        // publish with forceJoinInFlight=false and MUST leave the counter
        // untouched — observable via the absence of the reset marker on
        // a subsequent confirmed verify (which would fire only if
        // count > 0).
        val rig = makeRig()
        rig.service.bootstrapForNewIdentity().getOrThrow()
        val publishCountAfterBootstrap = rig.api.publishCount
        assertEquals(1, publishCountAfterBootstrap, "bootstrap published once")

        // Drain to force replenish. Then replenish publishes.
        val toDelete = rig.opkRepo.getAll()
            .take(PreKeyLifecycleService.REFILL_BATCH_SIZE - 5)
        toDelete.forEach { rig.opkRepo.deleteByKeyId(it.keyIdHex) }
        val replenish = rig.service.maybeReplenishOneTimePreKeys()
        assertTrue(replenish.isSuccess)
        assertTrue(replenish.getOrNull()!!, "replenish must run")

        // Advance nowMs past SPK_ROTATION_DAYS so rotate publishes.
        rig.nowMsHolder[0] = rig.nowMsHolder[0] + 8L * 24L * 3600L * 1000L
        val rotate = rig.service.maybeRotateSignedPreKey()
        assertTrue(rotate.isSuccess)
        assertTrue(rotate.getOrNull()!!, "rotate must run after 8 days")

        assertEquals(3, rig.api.publishCount, "3 publishes total: bootstrap+replenish+rotate")
        // Every one of them must use forceJoinInFlight=false — the D7
        // "budget-neutral" contract.
        assertFalse(
            rig.api.lastForceJoinInFlight,
            "bootstrap/replenish/rotate publishes MUST default to force=false",
        )

        // Confirmed verify: NO reset marker (counter was never > 0).
        rig.api.fetchStatusResult =
            PreKeyStatus(remaining_opks = 50, signed_prekey_age_days = 2)
        rig.markers.clear()
        val verify = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
        assertTrue(verify.isSuccess)
        assertEquals(VerifyOutcome.AlreadyPublished, verify.getOrNull())
        assertFalse(
            rig.markers.any { "verify_bundle_confirmed_reset_budget" in it },
            "bootstrap/replenish/rotate MUST NOT advance the force-republish counter; " +
                "confirmed verify MUST NOT emit the reset marker; got markers=${rig.markers}",
        )
    }

    // ── T-F1a (messaging half) ──────────────────────────────────────────────

    @Test
    fun callerObservesResultFailure_andLogsClassOnly() = runTest {
        val rig = makeRig()
        bootstrapAndReset(rig)

        rig.api.fetchStatusThrower = { IllegalStateException("simulated transport blow-up") }

        val result = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
        assertTrue(
            result.isFailure,
            "non-CE throwable in fetchStatus MUST surface as Result.failure; got $result",
        )
        assertTrue(
            result.exceptionOrNull() is IllegalStateException,
            "wrapped throwable MUST be the injected one; got ${result.exceptionOrNull()}",
        )
        // D8: marker records only the class simpleName + trigger — no
        // message, no throwable.
        assertTrue(
            rig.markers.any {
                "verify_result_failure" in it &&
                    "trigger=Connected" in it &&
                    "type=IllegalStateException" in it
            },
            "verify_result_failure MUST include trigger + type=<simpleName>; " +
                "got markers=${rig.markers}",
        )
        // D8 further: no marker should carry the exception message.
        assertFalse(
            rig.markers.any { "simulated transport blow-up" in it },
            "D8: message content MUST NOT leak into markers; got markers=${rig.markers}",
        )
    }

    // ── T-F1b ───────────────────────────────────────────────────────────────

    @Test
    fun serviceNeverReturnsResultFailureOfCancellationException() = runTest {
        val rig = makeRig()
        bootstrapAndReset(rig)

        rig.api.fetchStatusThrower = { CancellationException("cancel me") }

        var caught: Boolean = false
        try {
            rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
            fail("MUST throw CE — never wrap it in Result.failure")
        } catch (ce: CancellationException) {
            caught = true
        }
        assertTrue(caught, "CancellationException MUST propagate; not wrapped in Result.failure")
    }

    // ── T-F3-service ────────────────────────────────────────────────────────

    @Test
    fun verifyBundleOnRelay_transportTerminal_returnsResultFailure() = runTest {
        val rig = makeRig()
        bootstrapAndReset(rig)

        val terminal = PreKeyBodyTruncatedException(attempts = 3)
        rig.api.fetchStatusThrower = { terminal }

        val result = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
        assertTrue(
            result.isFailure,
            "transport-terminal (TerminalOther class) MUST surface as Result.failure; got $result",
        )
        assertTrue(
            result.exceptionOrNull() is PreKeyBodyTruncatedException,
            "wrapped throwable class MUST be PreKeyBodyTruncatedException; " +
                "got ${result.exceptionOrNull()?.let { it::class.simpleName }}",
        )
        assertTrue(
            rig.markers.any {
                "verify_result_failure" in it &&
                    "type=PreKeyBodyTruncatedException" in it
            },
            "verify_result_failure marker MUST include the terminal class; " +
                "got markers=${rig.markers}",
        )
    }

    // ── T-F9 ────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationAfterStored_stillCommitsExactlyOneBudgetSlot() = runTest {
        val rig = makeRig()
        bootstrapAndReset(rig)

        // Arrange: fetchStatus zero-record → verify enters the force
        // path. publishBundle suspends inside a NonCancellable region so
        // we can cancel the parent job WITHOUT interrupting the fake —
        // then release the gate; publish returns Stored; verifyLocked
        // runs `withContext(NonCancellable) { counter += 1 }`; then
        // `ensureActive()` throws CE. Finally-block unlocks the mutex.
        rig.api.fetchStatusResult =
            PreKeyStatus(remaining_opks = 0, signed_prekey_age_days = null)
        val gate = CompletableDeferred<Unit>()
        rig.api.publishGate = gate
        rig.api.publishInsideNonCancellable = true
        rig.api.publishResult = PublishResult.Stored(storedOpks = 100)

        val job = launch {
            try {
                rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
                fail("expected CE to propagate after ensureActive()")
            } catch (ce: CancellationException) {
                // expected — the NonCancellable increment ran, then
                // ensureActive() threw CE.
            }
        }
        advanceUntilIdle()
        assertTrue(
            rig.api.publishStarted.isCompleted,
            "publishBundle MUST have entered its NonCancellable gate",
        )
        assertEquals(1, rig.api.publishCount)

        // Cancel the parent job. Fake stays alive inside NonCancellable.
        job.cancel(CancellationException("test cancels during force-republish"))
        gate.complete(Unit)
        job.join()

        // Now the observable proof: subsequent verify with age != null.
        // If the D7 NonCancellable increment ran exactly once, count==1
        // and reset marker fires with prior=1. If it ran zero times
        // (bug), no reset marker. If it ran twice (bug), prior=2.
        rig.api.publishGate = null
        rig.api.publishInsideNonCancellable = false
        rig.api.fetchStatusResult =
            PreKeyStatus(remaining_opks = 50, signed_prekey_age_days = 5)
        rig.markers.clear()
        val confirmed = rig.service.verifyBundleOnRelay(VerifyTrigger.Connected)
        assertTrue(confirmed.isSuccess, "follow-up MUST succeed (mutex released in finally)")
        assertEquals(VerifyOutcome.AlreadyPublished, confirmed.getOrNull())
        assertTrue(
            rig.markers.any { "verify_bundle_confirmed_reset_budget prior=1" in it },
            "D7 NonCancellable commit MUST commit exactly ONE slot despite parent " +
                "cancellation; got markers=${rig.markers}",
        )
    }

    // ── T-F18 ───────────────────────────────────────────────────────────────

    @Test
    fun deadline_exhaustion_returns_Result_failure_not_CE_and_ticker_survives() = runTest {
        val rig = makeRig()
        bootstrapAndReset(rig)

        // fetchStatus throws the transport's per-attempt deadline signal
        // (which is deliberately NOT a CancellationException — see
        // FetchStatusDeadlineExceededException KDoc). Every call throws.
        rig.api.fetchStatusThrower = { FetchStatusDeadlineExceededException() }

        val result = rig.service.verifyBundleOnRelay(VerifyTrigger.Periodic)
        assertTrue(
            result.isFailure,
            "deadline exhaustion MUST surface as Result.failure; got $result",
        )
        val wrapped = result.exceptionOrNull()
        assertTrue(
            wrapped is FetchStatusDeadlineExceededException,
            "wrapped throwable MUST be FetchStatusDeadlineExceededException, " +
                "NOT CancellationException; got ${wrapped?.let { it::class.simpleName }}",
        )
        assertFalse(
            wrapped is CancellationException,
            "FetchStatusDeadlineExceededException MUST NOT be a CE subclass " +
                "(otherwise it would kill the periodic ticker's while(isActive) loop)",
        )
        val statusCountBeforeTick = rig.api.statusCount
        assertEquals(
            1, statusCountBeforeTick,
            "one fetchStatus round-trip through the failed verify",
        )

        // The ticker mechanism survives: onPeriodicTick still fires
        // fetchStatus (proof the deadline exception did NOT cancel the
        // ticker scope).
        rig.service.onPeriodicTick()
        assertTrue(
            rig.api.statusCount > statusCountBeforeTick,
            "onPeriodicTick MUST still invoke fetchStatus after a prior deadline " +
                "exhaustion (Ticker-Survives-Non-CE-Terminal contract); " +
                "statusCount=${rig.api.statusCount}",
        )
        assertTrue(
            rig.markers.any { "verify_periodic_tick trigger=periodic" in it },
            "onPeriodicTick MUST emit its own tick marker; got markers=${rig.markers}",
        )
    }
}
