// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
import phantom.core.transport.PreKeyApi
import phantom.core.transport.PreKeyBundle
import phantom.core.transport.PreKeyStatus
import phantom.core.transport.PublishRequest
import phantom.core.transport.PublishResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [PreKeyLifecycleService] — onboarding bootstrap, OPK
 * pool refill at the 20-remaining threshold, weekly SPK rotation.
 *
 * Each test wires a fresh in-memory triplet (signed pre key repo, OPK
 * pool repo, fake PreKeyApi) and a real LibsodiumX3DH so the
 * cryptographic side-effects (real DH keypairs, real Ed25519 signature
 * on SPK) actually flow — these tests double as a smoke test that the
 * crypto wiring stays plumbed correctly across PRs.
 */
class PreKeyLifecycleServiceTest {

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
        var upsertCount = 0
        override suspend fun get() = stored
        override suspend fun upsert(entity: LocalSignedPreKeyEntity) {
            upsertCount++
            stored = entity
        }
        override suspend fun clear() { stored = null }
    }

    private class InMemoryOneTimePreKeyRepo : LocalOneTimePreKeyRepository {
        val store = mutableMapOf<String, LocalOneTimePreKeyEntity>()
        var clearCount = 0
        var insertAllCount = 0
        override suspend fun get(keyIdHex: String) = store[keyIdHex]
        override suspend fun getAll() = store.values.toList()
        override suspend fun count() = store.size
        override suspend fun insert(entity: LocalOneTimePreKeyEntity) {
            store[entity.keyIdHex] = entity
        }
        override suspend fun insertAll(entities: List<LocalOneTimePreKeyEntity>) {
            insertAllCount++
            entities.forEach { store[it.keyIdHex] = it }
        }
        override suspend fun deleteByKeyId(keyIdHex: String) { store.remove(keyIdHex) }
        override suspend fun clear() { clearCount++; store.clear() }
    }

    private class FakePreKeyApi : PreKeyApi {
        var publishCount = 0
        var lastRequest: PublishRequest? = null
        var publishResult: PublishResult = PublishResult.Stored(0)
        var statusCount = 0
        // Default: relay reports the identity is unknown
        // (signed_prekey_age_days = null). Tests that exercise the
        // "relay already has our bundle" branch override this.
        var fetchStatusResult: PreKeyStatus = PreKeyStatus(remaining_opks = 0, signed_prekey_age_days = null)
        // Sprint 2b L1: PreKeyApi.publishBundle takes a factory lambda
        // invoked once per retry attempt. This fake invokes it exactly
        // once and records the resulting request — tests that assert on
        // a single happy-path attempt see the same shape as before.
        // RC-PREKEY-PUBLISH-DEBOUNCE-RACE (2026-06-25): track the force
        // flag so tests can assert that the verify-driven republish path
        // sets it. Default false matches the historical caller shape.
        var lastForceJoinInFlight: Boolean = false
        override suspend fun publishBundle(
            forceJoinInFlight: Boolean,
            requestProvider: suspend () -> PublishRequest,
        ): PublishResult {
            publishCount++
            lastForceJoinInFlight = forceJoinInFlight
            lastRequest = requestProvider()
            return publishResult
        }
        override suspend fun fetchBundle(
            identityPubkeyHex: String,
            requesterPubkeyHex: String?,
        ) = null
        override suspend fun fetchStatus(
            identityPubkeyHex: String,
            requesterPubkeyHex: String?,
        ): PreKeyStatus {
            statusCount++
            return fetchStatusResult
        }
    }

    private suspend fun makeAlpha2Identity(): Triple<IdentityManager, InMemoryIdentityRepository, IdentityRecord> {
        val identityRepo = InMemoryIdentityRepository(seed = null)
        val manager = IdentityManager(LibsodiumIdentityCrypto(), identityRepo)
        val (record, _) = manager.createOrLoad("alice")
        return Triple(manager, identityRepo, record)
    }

    private fun makeService(
        identityManager: IdentityManager,
        spkRepo: LocalSignedPreKeyRepository = InMemorySignedPreKeyRepo(),
        opkRepo: LocalOneTimePreKeyRepository = InMemoryOneTimePreKeyRepo(),
        preKeyApi: FakePreKeyApi = FakePreKeyApi(),
        nowMs: Long = 1_700_000_000_000L,
    ): Rig {
        val service = PreKeyLifecycleService(
            identityManager = identityManager,
            signedPreKeyRepository = spkRepo,
            oneTimePreKeyRepository = opkRepo,
            preKeyApi = preKeyApi,
            x3dh = LibsodiumX3DH(),
            nowMsProvider = { nowMs },
        )
        return Rig(service, spkRepo, opkRepo, preKeyApi)
    }

    private data class Rig(
        val service: PreKeyLifecycleService,
        val spkRepo: LocalSignedPreKeyRepository,
        val opkRepo: LocalOneTimePreKeyRepository,
        val api: FakePreKeyApi,
    )

    // ── Onboarding ───────────────────────────────────────────────────────────

    @Test
    fun bootstrapForNewIdentity_publishesFreshBundle() = runTest {
        LibsodiumInitializer.initialize()
        val (identityManager, _, _) = makeAlpha2Identity()
        val rig = makeService(identityManager)

        val result = rig.service.bootstrapForNewIdentity()
        assertTrue(result.isSuccess, "happy path should succeed; got $result")

        // Post-state: SPK row populated, REFILL_BATCH_SIZE OPKs locally, one publish.
        assertNotNull(rig.spkRepo.get())
        assertEquals(PreKeyLifecycleService.REFILL_BATCH_SIZE, rig.opkRepo.count())
        assertEquals(1, rig.api.publishCount)

        // Published bundle has both X25519 + Ed25519 fields.
        val req = rig.api.lastRequest!!
        assertEquals(64, req.identity_pubkey_hex.length)
        assertEquals(64, req.signing_pubkey_hex.length)
        assertEquals(PreKeyLifecycleService.REFILL_BATCH_SIZE, req.one_time_pre_keys.size)
    }

    @Test
    fun bootstrapForNewIdentity_isIdempotent_whenAlreadyDone() = runTest {
        LibsodiumInitializer.initialize()
        val (identityManager, _, _) = makeAlpha2Identity()
        val rig = makeService(identityManager)

        rig.service.bootstrapForNewIdentity().getOrThrow()
        val firstPublishCount = rig.api.publishCount
        val firstSpk = rig.spkRepo.get()!!

        // Second call must be a no-op when SPK already exists.
        rig.service.bootstrapForNewIdentity().getOrThrow()
        assertEquals(firstPublishCount, rig.api.publishCount, "no second publish")
        assertEquals(firstSpk.keyId, rig.spkRepo.get()!!.keyId, "SPK unchanged")
    }

    // ── Verify-bundle-on-relay ──────────────────────────────────────────────

    @Test
    fun verifyBundleOnRelay_republishesWhenRelayHasNoBundle() = runTest {
        LibsodiumInitializer.initialize()
        val (identityManager, _, _) = makeAlpha2Identity()
        val rig = makeService(identityManager)

        // Onboarding-equivalent local state: SPK + 100 OPKs, but the
        // relay-side publish is simulated to have failed silently
        // (publishCount remains 0 here because we bypass the publish
        // path and seed local state via bootstrap, then reset the
        // publish counter before running verify).
        rig.service.bootstrapForNewIdentity().getOrThrow()
        rig.api.publishCount = 0
        rig.api.lastRequest = null

        // Relay reports the identity is unknown (default
        // FakePreKeyApi.fetchStatusResult). verify must republish.
        val result = rig.service.verifyBundleOnRelay()
        assertTrue(result.isSuccess, "verify must succeed; got $result")
        assertTrue(result.getOrNull()!!, "verify must report a republish ran")
        assertEquals(1, rig.api.statusCount, "exactly one status probe")
        assertEquals(1, rig.api.publishCount, "exactly one republish")
        // The republished bundle is the existing local pool, not a fresh
        // generation: REFILL_BATCH_SIZE OPKs from bootstrap, no new OPK creation.
        assertEquals(PreKeyLifecycleService.REFILL_BATCH_SIZE, rig.api.lastRequest!!.one_time_pre_keys.size)
    }

    @Test
    fun verifyBundleOnRelay_doesNothingWhenRelayHasBundle() = runTest {
        LibsodiumInitializer.initialize()
        val (identityManager, _, _) = makeAlpha2Identity()
        val rig = makeService(identityManager)

        rig.service.bootstrapForNewIdentity().getOrThrow()
        rig.api.publishCount = 0
        rig.api.lastRequest = null

        // Relay says: identity is registered, SPK is 0 days old.
        // verify must NOT republish.
        rig.api.fetchStatusResult = PreKeyStatus(remaining_opks = 50, signed_prekey_age_days = 0)

        val result = rig.service.verifyBundleOnRelay()
        assertTrue(result.isSuccess)
        assertFalse(result.getOrNull()!!, "verify must NOT report a republish")
        assertEquals(1, rig.api.statusCount, "status probed exactly once")
        assertEquals(0, rig.api.publishCount, "no republish when relay already has bundle")
    }

    @Test
    fun verifyBundleOnRelay_skipsSilentlyWhenLocalSpkMissing() = runTest {
        LibsodiumInitializer.initialize()
        val (identityManager, _, _) = makeAlpha2Identity()
        val rig = makeService(identityManager)

        // No bootstrapForNewIdentity call: local SPK absent. verify
        // returns false silently — driving onboarding is
        // bootstrapForNewIdentity's job, not verify's.
        val result = rig.service.verifyBundleOnRelay()
        assertTrue(result.isSuccess)
        assertFalse(result.getOrNull()!!, "verify is a no-op when local SPK missing")
        assertEquals(0, rig.api.statusCount, "no relay round-trip when local has nothing to verify")
        assertEquals(0, rig.api.publishCount)
    }

    // ── Replenish ────────────────────────────────────────────────────────────

    @Test
    fun maybeReplenishOneTimePreKeys_skipsWhenAboveThreshold() = runTest {
        LibsodiumInitializer.initialize()
        val (identityManager, _, _) = makeAlpha2Identity()
        val rig = makeService(identityManager)

        // Bootstrap puts REFILL_BATCH_SIZE OPKs in the pool (>= REPLENISH_THRESHOLD 20).
        rig.service.bootstrapForNewIdentity().getOrThrow()
        val publishCountBefore = rig.api.publishCount

        val refillResult = rig.service.maybeReplenishOneTimePreKeys()
        assertTrue(refillResult.isSuccess)
        assertFalse(refillResult.getOrNull()!!, "refill must NOT run when count >= threshold")
        assertEquals(publishCountBefore, rig.api.publishCount, "no extra publish")
    }

    @Test
    fun maybeReplenishOneTimePreKeys_runsWhenBelowThreshold_andPublishesFullPool() = runTest {
        LibsodiumInitializer.initialize()
        val (identityManager, _, _) = makeAlpha2Identity()
        val rig = makeService(identityManager)

        rig.service.bootstrapForNewIdentity().getOrThrow()
        // Drain the pool down to 5 (below REPLENISH_THRESHOLD 20). Use
        // the repo directly — equivalent to (REFILL_BATCH_SIZE - 5) successful first contacts.
        val toDelete = rig.opkRepo.getAll().take(PreKeyLifecycleService.REFILL_BATCH_SIZE - 5)
        toDelete.forEach { rig.opkRepo.deleteByKeyId(it.keyIdHex) }
        assertEquals(5, rig.opkRepo.count())

        val refillResult = rig.service.maybeReplenishOneTimePreKeys()
        assertTrue(refillResult.isSuccess)
        assertTrue(refillResult.getOrNull()!!, "refill must run when count < threshold")

        // After refill: 5 (existing) + REFILL_BATCH_SIZE (new) in local pool.
        val expectedPool = 5 + PreKeyLifecycleService.REFILL_BATCH_SIZE
        assertEquals(expectedPool, rig.opkRepo.count())
        // Published bundle ships ALL keys — relay replaces wholesale.
        val req = rig.api.lastRequest!!
        assertEquals(expectedPool, req.one_time_pre_keys.size)
    }

    // ── SPK rotation ────────────────────────────────────────────────────────

    @Test
    fun maybeRotateSignedPreKey_skipsWhenSpkIsFresh() = runTest {
        LibsodiumInitializer.initialize()
        val (identityManager, _, _) = makeAlpha2Identity()
        val nowMs = 1_700_000_000_000L
        val rig = makeService(identityManager, nowMs = nowMs)

        rig.service.bootstrapForNewIdentity().getOrThrow()
        val publishCountBefore = rig.api.publishCount

        val rotateResult = rig.service.maybeRotateSignedPreKey()
        assertTrue(rotateResult.isSuccess)
        assertFalse(rotateResult.getOrNull()!!, "rotation must NOT run when SPK is fresh")
        assertEquals(publishCountBefore, rig.api.publishCount)
    }

    @Test
    fun maybeRotateSignedPreKey_rotates_whenSpkIsOlderThan7Days() = runTest {
        LibsodiumInitializer.initialize()
        val (identityManager, _, _) = makeAlpha2Identity()
        val tNow = 1_700_000_000_000L
        val tOld = tNow - 8L * 24L * 3600L * 1000L  // 8 days ago

        // Build the rig with `nowMsProvider` returning tOld so the SPK
        // is timestamped in the past, then swap to tNow when calling
        // maybeRotateSignedPreKey. Easier: a mutable "now" closure.
        var now = tOld
        val spkRepo = InMemorySignedPreKeyRepo()
        val opkRepo = InMemoryOneTimePreKeyRepo()
        val api = FakePreKeyApi()
        val service = PreKeyLifecycleService(
            identityManager = identityManager,
            signedPreKeyRepository = spkRepo,
            oneTimePreKeyRepository = opkRepo,
            preKeyApi = api,
            x3dh = LibsodiumX3DH(),
            nowMsProvider = { now },
        )

        // Onboard at tOld — the SPK is dated 8 days ago.
        service.bootstrapForNewIdentity().getOrThrow()
        val originalSpk = spkRepo.get()!!

        // Advance now to "today".
        now = tNow

        val rotateResult = service.maybeRotateSignedPreKey()
        assertTrue(rotateResult.isSuccess)
        assertTrue(rotateResult.getOrNull()!!, "rotation must run when SPK is 8 days old")

        // Post-state: new SPK has a different key_id, previous slot
        // carries the old SPK with retiredAtMs set to tNow.
        val rotated = spkRepo.get()!!
        assertTrue(rotated.keyId != originalSpk.keyId, "key_id should change")
        assertTrue(rotated.publicKeyHex != originalSpk.publicKeyHex, "public key should change")
        assertNotNull(rotated.previous)
        assertEquals(originalSpk.keyId, rotated.previous!!.keyId)
        assertEquals(tNow, rotated.previous!!.retiredAtMs)

        // A second publish was triggered by the rotation (1 onboarding
        // + 1 rotation publish).
        assertEquals(2, api.publishCount)
    }

    // ── Failure surface ─────────────────────────────────────────────────────

    @Test
    fun bootstrapForNewIdentity_returnsFailure_whenPublishRateLimited() = runTest {
        LibsodiumInitializer.initialize()
        val (identityManager, _, _) = makeAlpha2Identity()
        val rig = makeService(identityManager)
        rig.api.publishResult = PublishResult.Failure(
            reason = PublishResult.Reason.RateLimited,
            serverMessage = "publish rate limit exceeded",
        )

        val result = rig.service.bootstrapForNewIdentity()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MigrationException.PublishRateLimited)
        // Local SPK was persisted before publish — that's fine, a retry
        // will republish from the existing local state.
        assertNotNull(rig.spkRepo.get())
    }

    // ── Sprint 2b M-2bA-5 ───────────────────────────────────────────────────
    //
    // The load-bearing "no bootstrap exception" invariant from scope-doc
    // L1: even on the bootstrap path, the `opksProvider` lambda passed
    // into the publish helper MUST read from the repository on every
    // invocation. It MUST NOT close over the in-memory `generated_opks`
    // list returned by `generateAndPersistOpks`.
    //
    // The 2026-06-15 integration smoke shape: a concurrent inbound
    // bootstrap can locally consume one of the freshly-generated OPKs
    // between publish attempts. If the bootstrap path passed
    // `{ generated_opks }`, the retry would republish the stale 40-OPK
    // snapshot including the just-consumed key. The L1 lock REJECTS
    // that closure (see scope-doc L1 §"No bootstrap-path exception"
    // and the M-2bA-5 cell).
    //
    // Mechanism: a [RetryingFakePreKeyApi] simulates the 3-attempt
    // retry loop directly — it invokes `requestProvider` three times,
    // captures each result, and mutates the OPK repository between
    // invocations. We do NOT exercise `PreKeyApiClient.publishWithRetry`
    // here (M-2bA-1 + M-2bA-2 cover that at the transport layer); the
    // M-2bA-5 invariant is specifically that the lambda the lifecycle
    // service hands to `PreKeyApi.publishBundle` reflects post-consume
    // repository state, not closure state.

    private class RetryingFakePreKeyApi(
        private val mutateBetweenAttempts: suspend () -> Unit,
    ) : PreKeyApi {
        val capturedRequests: MutableList<PublishRequest> = mutableListOf()
        var publishCount: Int = 0
        override suspend fun publishBundle(
            forceJoinInFlight: Boolean,
            requestProvider: suspend () -> PublishRequest,
        ): PublishResult {
            publishCount++
            // Simulate the 3-attempt retry loop. Invoke requestProvider
            // BEFORE the first mutation so attempt 1 sees the initial
            // pool; mutate after each capture so attempts 2 + 3 see
            // post-consume state if (and only if) the lambda reads from
            // the repository.
            capturedRequests.add(requestProvider())
            mutateBetweenAttempts()
            capturedRequests.add(requestProvider())
            mutateBetweenAttempts()
            capturedRequests.add(requestProvider())
            return PublishResult.Stored(storedOpks = capturedRequests.last().one_time_pre_keys.size)
        }
        override suspend fun fetchBundle(
            identityPubkeyHex: String,
            requesterPubkeyHex: String?,
        ): PreKeyBundle? = null
        override suspend fun fetchStatus(
            identityPubkeyHex: String,
            requesterPubkeyHex: String?,
        ): PreKeyStatus = PreKeyStatus(remaining_opks = 0, signed_prekey_age_days = null)
    }

    @Test
    fun bootstrapForNewIdentity_publishRetry_readsFromDbNotGeneratedOpksClosure() = runTest {
        LibsodiumInitializer.initialize()
        val (identityManager, _, _) = makeAlpha2Identity()
        val spkRepo = InMemorySignedPreKeyRepo()
        val opkRepo = InMemoryOneTimePreKeyRepo()

        // The retry-loop fake mutates the OPK repository between
        // invocations — deleting one OPK each time, simulating an
        // inbound bootstrap consume that races the publish retry.
        val api = RetryingFakePreKeyApi(
            mutateBetweenAttempts = {
                val first = opkRepo.store.keys.firstOrNull()
                if (first != null) opkRepo.deleteByKeyId(first)
            },
        )

        val service = PreKeyLifecycleService(
            identityManager = identityManager,
            signedPreKeyRepository = spkRepo,
            oneTimePreKeyRepository = opkRepo,
            preKeyApi = api,
            x3dh = LibsodiumX3DH(),
            nowMsProvider = { 1_700_000_000_000L },
        )

        val result = service.bootstrapForNewIdentity()
        assertTrue(result.isSuccess, "happy path must succeed; got $result")

        // The fake invoked requestProvider 3 times — one captured
        // request per retry attempt.
        assertEquals(3, api.capturedRequests.size,
            "lambda must be invoked once per retry attempt")

        val opksAttempt1 = api.capturedRequests[0].one_time_pre_keys.map { it.key_id_hex }.toSet()
        val opksAttempt2 = api.capturedRequests[1].one_time_pre_keys.map { it.key_id_hex }.toSet()
        val opksAttempt3 = api.capturedRequests[2].one_time_pre_keys.map { it.key_id_hex }.toSet()

        // L1 INVARIANT: lambda reads from the repository on every
        // invocation, so post-consume state IS visible across attempts.
        // Attempt 1: full pool (REFILL_BATCH_SIZE).
        // Attempt 2: pool minus 1 OPK (the one we deleted between 1+2).
        // Attempt 3: pool minus 2 OPKs (delete between 2+3 too).
        assertEquals(PreKeyLifecycleService.REFILL_BATCH_SIZE, opksAttempt1.size,
            "attempt 1 must list the full freshly-generated pool")
        assertEquals(PreKeyLifecycleService.REFILL_BATCH_SIZE - 1, opksAttempt2.size,
            "attempt 2 must reflect the 1-OPK consume since attempt 1 — " +
                "post-Sprint-2b L1 lambda reads from DB, not generated_opks closure")
        assertEquals(PreKeyLifecycleService.REFILL_BATCH_SIZE - 2, opksAttempt3.size,
            "attempt 3 must reflect the 2-OPK consume since attempt 1")

        // The consumed OPKs are GONE from later attempts — closure over
        // the in-memory generated_opks list would replay them.
        val deletedBetween1And2 = opksAttempt1 - opksAttempt2
        val deletedBetween2And3 = opksAttempt2 - opksAttempt3
        assertEquals(1, deletedBetween1And2.size,
            "exactly one OPK must drop out between attempt 1 and 2")
        assertEquals(1, deletedBetween2And3.size,
            "exactly one OPK must drop out between attempt 2 and 3")
        assertFalse(opksAttempt2.containsAll(deletedBetween1And2),
            "consumed OPK from attempt 1 MUST NOT reappear in attempt 2 — " +
                "the no-bootstrap-closure invariant (L1)")
        assertFalse(opksAttempt3.containsAll(deletedBetween2And3),
            "consumed OPK from attempt 2 MUST NOT reappear in attempt 3")

        // Identity + signing hex stay stable across attempts — only the
        // OPK list churns. (Sanity check that the lambda re-constructs
        // PublishRequest correctly per invocation.)
        val identityHex0 = api.capturedRequests[0].identity_pubkey_hex
        assertTrue(api.capturedRequests.all { it.identity_pubkey_hex == identityHex0 },
            "identity hex stable across retries")
        val spkKeyId0 = api.capturedRequests[0].signed_pre_key.key_id
        assertTrue(api.capturedRequests.all { it.signed_pre_key.key_id == spkKeyId0 },
            "SPK key_id stable across retries")
    }

    // ── RC-PREKEY-PUBLISH-DEBOUNCE-RACE (2026-06-25) ────────────────────────
    //
    // Mini-lock invariants under test (`docs/tracks/rc-prekey-publish-debounce-race.md` §4):
    //  - Inv-NoFalseSuccess: verify-driven republish path does NOT accept a
    //    `PublishResult.Deferred` synthetic success — the underlying call MUST
    //    set `forceJoinInFlight = true` so the transport layer waits for the
    //    in-flight publish, then runs its own attempt.
    //  - Inv-ForcePathOnZeroRecord: the `spk_age_days = null AND
    //    opks_remaining = 0` status response is the canonical trigger; the
    //    republish call MUST carry the force flag.
    //  - Inv-NoSpinningRetry: the per-session force-republish budget
    //    ([PreKeyLifecycleService.MAX_FORCE_REPUBLISH_PER_SESSION]) caps the
    //    number of force-republish attempts; the 6th call returns false and
    //    issues no publish.

    @Test
    fun verifyBundleOnRelay_republishOnZeroRecord_setsForceJoinInFlight() = runTest {
        LibsodiumInitializer.initialize()
        val (identityManager, _, _) = makeAlpha2Identity()
        val rig = makeService(identityManager)

        rig.service.bootstrapForNewIdentity().getOrThrow()
        // The bootstrap publish carried the default (false). Reset so we
        // observe only the verify-driven republish.
        rig.api.lastForceJoinInFlight = false
        rig.api.publishCount = 0

        // Default fetchStatusResult is (remaining_opks=0,
        // signed_prekey_age_days=null) — the canonical "relay has no
        // record" signal. verify must republish AND must pass the force
        // flag so a publishMutex debounce doesn't short-circuit the
        // republish with `PublishResult.Deferred`.
        val result = rig.service.verifyBundleOnRelay()
        assertTrue(result.isSuccess, "verify must succeed; got $result")
        assertTrue(result.getOrNull()!!, "verify must report a republish ran")
        assertEquals(1, rig.api.publishCount, "exactly one republish")
        assertTrue(
            rig.api.lastForceJoinInFlight,
            "verify-driven republish MUST set forceJoinInFlight=true so a " +
                "publishMutex debounce is joined (not short-circuited as Deferred) — " +
                "Inv-ForcePathOnZeroRecord",
        )
    }

    @Test
    fun bootstrapPath_publish_doesNotSetForceJoinInFlight() = runTest {
        // Companion to the verify-path test above: the bootstrap /
        // replenish / rotate publish call sites are NOT verify-driven
        // and must NOT escalate to the force-join path. Only the
        // verify-on-relay path sees the relay's "zero record" signal
        // and is responsible for the force-publish escalation.
        LibsodiumInitializer.initialize()
        val (identityManager, _, _) = makeAlpha2Identity()
        val rig = makeService(identityManager)

        rig.service.bootstrapForNewIdentity().getOrThrow()
        assertEquals(1, rig.api.publishCount, "bootstrap publish ran once")
        assertFalse(
            rig.api.lastForceJoinInFlight,
            "bootstrap publish MUST default to forceJoinInFlight=false — " +
                "force-join is reserved for the verify-driven republish path",
        )
    }

    @Test
    fun verifyBundleOnRelay_forceRepublishBudgetExhaustsAfterMaxAttempts() = runTest {
        // Inv-NoSpinningRetry: the per-session budget caps the number of
        // verify-driven force-republish attempts at
        // MAX_FORCE_REPUBLISH_PER_SESSION. The (N+1)th call must NOT
        // issue a publish even though the relay still reports zero
        // record; it returns false and is expected to log
        // `verify_republish_budget_exhausted` (not asserted here — the
        // observable contract is the publishCount ceiling).
        LibsodiumInitializer.initialize()
        val (identityManager, _, _) = makeAlpha2Identity()
        val rig = makeService(identityManager)

        rig.service.bootstrapForNewIdentity().getOrThrow()
        // Reset post-bootstrap so we count only verify-driven publishes.
        rig.api.publishCount = 0

        val max = PreKeyLifecycleService.MAX_FORCE_REPUBLISH_PER_SESSION
        // First `max` calls each consume one budget slot. Relay keeps
        // reporting zero record (default fetchStatusResult), so each
        // call republishes.
        repeat(max) { i ->
            val r = rig.service.verifyBundleOnRelay()
            assertTrue(r.isSuccess, "verify call ${i + 1} should succeed; got $r")
            assertTrue(
                r.getOrNull()!!,
                "verify call ${i + 1} should report a republish (budget remaining)",
            )
        }
        assertEquals(
            max, rig.api.publishCount,
            "first $max verify-driven calls must each publish exactly once",
        )
        assertEquals(
            max, rig.api.statusCount,
            "status probe ran on each in-budget call",
        )

        // (max + 1)th call: budget exhausted. Status probe still runs
        // (so the operator can see the relay is still missing the
        // bundle), but no publish.
        val overflow = rig.service.verifyBundleOnRelay()
        assertTrue(overflow.isSuccess, "budget-exhausted call still succeeds (no throw)")
        assertFalse(
            overflow.getOrNull()!!,
            "budget-exhausted call reports false — no republish ran",
        )
        assertEquals(
            max, rig.api.publishCount,
            "publishCount must NOT advance past $max — Inv-NoSpinningRetry",
        )
        assertEquals(
            max + 1, rig.api.statusCount,
            "status probe still ran on the overflow attempt",
        )
    }

    @Test
    fun verifyBundleOnRelay_doesNotForceRepublish_whenAgeIsNonNullButOpksAreZero() = runTest {
        // Inv-ForcePathOnZeroRecord contract: force-republish ONLY when
        // the relay reports the canonical "no record" signal
        // (`signed_prekey_age_days == null AND remaining_opks == 0`).
        //
        // This negative test pins the AND check: if the relay returns
        // a non-null age (i.e. the identity record exists) but
        // remaining_opks happens to be 0 (e.g. pool freshly drained),
        // verify MUST NOT force-republish. The SPK-only fallback
        // covers session bootstrap and `maybeReplenishOneTimePreKeys`
        // handles the pool refill independently — running an
        // unsolicited force-republish here would waste an HTTP
        // round-trip AND consume one slot of the per-session budget
        // that defends against a true zero-record runaway.
        LibsodiumInitializer.initialize()
        val (identityManager, _, _) = makeAlpha2Identity()
        val rig = makeService(identityManager)

        rig.service.bootstrapForNewIdentity().getOrThrow()
        rig.api.publishCount = 0
        rig.api.lastForceJoinInFlight = false

        // Relay says: identity is registered (age = 3 days), but the
        // OPK pool happens to be drained (remaining_opks = 0).
        rig.api.fetchStatusResult = PreKeyStatus(remaining_opks = 0, signed_prekey_age_days = 3)

        val result = rig.service.verifyBundleOnRelay()
        assertTrue(result.isSuccess)
        assertFalse(
            result.getOrNull()!!,
            "verify MUST return false when age is non-null — record exists, no force-republish",
        )
        assertEquals(0, rig.api.publishCount, "no publish ran")
        assertEquals(1, rig.api.statusCount, "exactly one status probe")
    }

    // ── RC-PREKEY-PUBLISH-DEBOUNCE-RACE 2026-06-25 — deterministic race repro
    //
    // Mini-lock §5 item 1 acceptance gate: a deterministic test that
    // reproduces the exact pre-fix chain reproduced in the 2026-06-25
    // baseline-blocker v2 field smoke:
    //
    //   t0:  bootstrap publish (publish #1) goes in-flight on the
    //        transport — i.e. the publishMutex is held.
    //   t1:  verifyBundleOnRelay fires. Relay reports zero record
    //        (`spk_age_days = null AND opks_remaining = 0`).
    //   t2:  Verify schedules a force-republish (publish #2). The
    //        force flag bypasses the debounce gate — publish #2
    //        SUSPENDS on the publishMutex instead of short-circuiting
    //        with `PublishResult.Deferred`.
    //   t3:  Publish #1 dies on the transport (simulated
    //        `ConnectException ECONNREFUSED`). The transport throwable
    //        propagates from the lifecycle service's bootstrap call.
    //   t4:  Publish #1 releases the publishMutex; publish #2 acquires
    //        it and runs its own attempt against the transport.
    //   t5:  Publish #2 succeeds (relay returns `Stored(N)`). The
    //        verify call returns `Result.success(true)`. The bundle
    //        has reached the relay despite publish #1's failure.
    //
    // Pre-fix observable shape (what we are guarding against): publish
    // #2 short-circuits with synthetic `Stored(0)` (logged as
    // `PREKEY_TRACE upload_ok stored_opks=0`), publish #1 then dies,
    // and no follow-up publish fires — bundle stays missing on the
    // relay until the next app session. The post-fix shape (this
    // test) guarantees a real publish #2 attempt that reaches the
    // relay regardless of publish #1's outcome.
    //
    // Wiring rationale: this test uses a fake that reproduces
    // [PreKeyApiClient]'s publishMutex semantics at the [PreKeyApi]
    // interface surface — same `tryLock → Deferred default; lock on
    // force-join` shape, same body-factory invocation contract.
    // `PreKeyApiClient` itself is already covered separately by
    // [PreKeyPublishReliabilityTest] (`publishBundle_mutex_default_returnsDeferred_not_Stored`
    // + `publishBundle_mutex_forceJoinInFlight_waitsAndPublishes`).
    // Avoiding a direct dependency on the real transport here keeps
    // the messaging:commonTest module free of Ktor MockEngine
    // dependencies.

    private class RaceReproFakePreKeyApi(
        private val firstPublishGate: CompletableDeferred<PublishResult>,
        private val secondPublishResult: PublishResult,
        private val statusResult: PreKeyStatus,
    ) : PreKeyApi {
        // Same mutex contract as `PreKeyApiClient.publishMutex` —
        // at most one in-flight publish per identity at a time.
        private val publishMutex: Mutex = Mutex()
        // Signals to the test once publish #1 has entered the work
        // section and is suspended on the gate. Test awaits this
        // before scheduling the concurrent verify call.
        val publishOneStarted: CompletableDeferred<Unit> = CompletableDeferred()
        var publishStartCount: Int = 0
        val forceFlags: MutableList<Boolean> = mutableListOf()

        override suspend fun publishBundle(
            forceJoinInFlight: Boolean,
            requestProvider: suspend () -> PublishRequest,
        ): PublishResult {
            // Reproduce PreKeyApiClient's debounce semantics: tryLock
            // → Deferred default; lock on force-join. Tests of the
            // real client cover the same semantics in
            // PreKeyPublishReliabilityTest.
            if (!publishMutex.tryLock()) {
                if (!forceJoinInFlight) return PublishResult.Deferred
                publishMutex.lock()
            }
            return try {
                publishStartCount += 1
                forceFlags += forceJoinInFlight
                requestProvider()  // exercise the body factory
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun raceRepro_bootstrapFails_verifyTriggersForceRepublish_bundleReachesRelay() = runTest {
        LibsodiumInitializer.initialize()
        val (identityManager, _, _) = makeAlpha2Identity()

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

        // t0: launch bootstrap — publish #1 enters the API, takes the
        // mutex, and suspends on `firstPublishGate.await()`.
        val bootstrapJob = async {
            service.bootstrapForNewIdentity()
        }
        api.publishOneStarted.await()
        assertTrue(
            bootstrapJob.isActive,
            "bootstrap publish #1 must still be suspended on the gate",
        )
        assertEquals(1, api.publishStartCount, "publish #1 entered the work section")
        assertEquals(listOf(false), api.forceFlags, "bootstrap publish defaults to force=false")

        // t1–t2: verify-on-relay fires. Status returns null age + 0
        // OPKs — canonical zero-record signal. Verify schedules
        // force-republish (publish #2); publish #2 must SUSPEND on
        // the publishMutex (held by publish #1) — NOT short-circuit
        // with Deferred (the pre-fix anti-shape).
        val verifyJob = async {
            service.verifyBundleOnRelay()
        }
        advanceUntilIdle()
        assertTrue(
            verifyJob.isActive,
            "verify-driven publish #2 must suspend on publishMutex while publish #1 holds it",
        )
        assertEquals(
            1, api.publishStartCount,
            "publish #2 must NOT have entered the work section yet — still queued on mutex",
        )

        // t3: publish #1 dies — simulate a hard transport failure
        // (the production shape is `ConnectException ECONNREFUSED`,
        // a `java.net.SocketException`-class throwable on JVM). Use a
        // plain `RuntimeException` here so the test stays in commonTest
        // and so the throwable is NOT a `CancellationException` (which
        // would carry coroutine-cancellation semantics rather than the
        // transport-failure semantics the mini-lock contract pins). The
        // lifecycle service's `publishBundle` helper catches any
        // `Throwable` and rethrows, so the choice of concrete throwable
        // only matters for the assert below: `bootstrapResult.isFailure`.
        firstPublishGate.completeExceptionally(
            RuntimeException("simulated ECONNREFUSED — in-flight publish died"),
        )

        // t4–t5: publish #1 releases the mutex; publish #2 acquires
        // it and runs its attempt against the API, returning
        // Stored(100). The verify call completes with success; the
        // bootstrap call surfaces publish #1's failure.
        val verifyResult = verifyJob.await()
        val bootstrapResult = bootstrapJob.await()

        assertEquals(
            2, api.publishStartCount,
            "publish #2 must reach the work section AFTER publish #1 released the mutex — " +
                "Inv-RetryAfterFail + Inv-ForcePathOnZeroRecord",
        )
        assertEquals(
            listOf(false, true), api.forceFlags,
            "publish #1 force-flag = false (bootstrap), publish #2 force-flag = true (verify-driven)",
        )
        assertTrue(
            verifyResult.isSuccess,
            "verify-on-relay must succeed; got $verifyResult",
        )
        assertTrue(
            verifyResult.getOrNull()!!,
            "verify must report a republish ran",
        )
        assertTrue(
            bootstrapResult.isFailure,
            "bootstrap must surface publish #1's failure; got $bootstrapResult",
        )
    }
}
