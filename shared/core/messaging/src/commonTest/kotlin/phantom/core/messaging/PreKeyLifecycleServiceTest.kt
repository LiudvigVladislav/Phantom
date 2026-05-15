// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.ionspin.kotlin.crypto.LibsodiumInitializer
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
        override suspend fun publishBundle(request: PublishRequest): PublishResult {
            publishCount++
            lastRequest = request
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
}
