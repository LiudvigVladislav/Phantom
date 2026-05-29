// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.signature.Signature
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import phantom.core.crypto.DhKeyPair
import phantom.core.crypto.DhPublicKey
import phantom.core.crypto.LibsodiumX3DH
import phantom.core.crypto.RatchetState
import phantom.core.crypto.SignedPreKeySigner
import phantom.core.crypto.X3DHProtocol
import phantom.core.identity.IdentityCrypto
import phantom.core.identity.IdentityKeyPair
import phantom.core.identity.IdentitySigningKeyPair
import phantom.core.identity.LibsodiumIdentityCrypto
import phantom.core.identity.PrivateKey
import phantom.core.identity.PublicKey
import phantom.core.identity.SigningPrivateKey
import phantom.core.identity.SigningPublicKey
import phantom.core.storage.LocalOneTimePreKeyEntity
import phantom.core.storage.LocalOneTimePreKeyRepository
import phantom.core.storage.LocalSignedPreKeyEntity
import phantom.core.storage.LocalSignedPreKeyRepository
import phantom.core.storage.RatchetStateRepository
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Closure tests for the F12 + F15 audit findings, plus end-to-end 4-DH
 * round-trip through SessionManager.
 *
 * F12 (X3DH bypass) closure:
 *  - Old SessionManager.getOrCreateSession derived the root key directly
 *    from `X3DHProtocol.computeSharedSecret(my_identity_priv,
 *    their_identity_pub)`. That method is gone — this test class spies
 *    on the X3DHProtocol implementation and asserts that
 *    `initiatorHandshake4DH` / `recipientHandshake4DH` are the only
 *    code paths SessionManager invokes.
 *
 * F15 (identity-as-ratchet-seed) invariant:
 *  - The freshly-derived RatchetState's sendingRatchetPublicKey /
 *    sendingRatchetPrivateKey must NEVER equal the local user's
 *    identity DH keypair. SessionManager has explicit `require(...)`
 *    guards; these tests prove they fire on a deliberately-broken stub
 *    AND that the legitimate LibsodiumX3DH path produces a fresh
 *    ephemeral.
 *
 * Round-trip:
 *  - Alice runs `initiatorBootstrap` against Bob's bundle, Bob runs
 *    `recipientBootstrap` against Alice's x3dhInit header. The two
 *    ratchet rootKeys must match (both sides deriving the same shared
 *    secret is the whole point of the handshake).
 */
class SessionManagerTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── Fakes ────────────────────────────────────────────────────────────────

    /**
     * In-memory [RatchetStateRepository] that just stores blobs by
     * conversation id. Same shape as the production SQLDelight impl
     * uses — the repository contract is just "give me a string back".
     */
    private class InMemoryRatchetRepo : RatchetStateRepository {
        private val store = mutableMapOf<String, String>()
        override suspend fun getRatchetState(conversationId: String): String? =
            store[conversationId]
        override suspend fun upsertRatchetState(conversationId: String, stateBlob: String) {
            store[conversationId] = stateBlob
        }
        override suspend fun deleteRatchetState(conversationId: String) {
            store.remove(conversationId)
        }
        override suspend fun deleteAll() { store.clear() }
        fun isPersisted(conversationId: String): Boolean = store.containsKey(conversationId)
    }

    private class InMemorySignedPreKeyRepo : LocalSignedPreKeyRepository {
        private var stored: LocalSignedPreKeyEntity? = null
        override suspend fun get(): LocalSignedPreKeyEntity? = stored
        override suspend fun upsert(entity: LocalSignedPreKeyEntity) { stored = entity }
        override suspend fun clear() { stored = null }
    }

    private class InMemoryOneTimePreKeyRepo : LocalOneTimePreKeyRepository {
        private val store = mutableMapOf<String, LocalOneTimePreKeyEntity>()
        override suspend fun get(keyIdHex: String): LocalOneTimePreKeyEntity? = store[keyIdHex]
        override suspend fun getAll(): List<LocalOneTimePreKeyEntity> =
            store.values.sortedBy { it.uploadedAtMs }
        override suspend fun count(): Int = store.size
        override suspend fun insert(entity: LocalOneTimePreKeyEntity) {
            store[entity.keyIdHex] = entity
        }
        override suspend fun insertAll(entities: List<LocalOneTimePreKeyEntity>) {
            entities.forEach { insert(it) }
        }
        override suspend fun deleteByKeyId(keyIdHex: String) { store.remove(keyIdHex) }
        override suspend fun clear() { store.clear() }
        fun has(keyIdHex: String): Boolean = store.containsKey(keyIdHex)
    }

    /**
     * X3DH spy — wraps a real LibsodiumX3DH and counts how many times
     * each method is called. Used to prove F12 closure (initiatorHandshake4DH
     * / recipientHandshake4DH are called, computeSharedSecret is NOT
     * called from SessionManager paths).
     */
    private class CountingX3DH(private val real: X3DHProtocol) : X3DHProtocol {
        var initiatorHandshake4DHCalls = 0
        var recipientHandshake4DHCalls = 0
        var legacyInitiatorHandshakeCalls = 0
        var legacyRecipientHandshakeCalls = 0
        var computeSharedSecretCalls = 0
        var generateDhKeyPairCalls = 0

        override fun generateDhKeyPair(): DhKeyPair {
            generateDhKeyPairCalls++
            return real.generateDhKeyPair()
        }

        override fun computeSharedSecret(
            privateKey: phantom.core.crypto.DhPrivateKey,
            publicKey: DhPublicKey,
        ): ByteArray {
            computeSharedSecretCalls++
            return real.computeSharedSecret(privateKey, publicKey)
        }

        override fun initiatorHandshake(
            initiatorIdentityKeyPair: DhKeyPair,
            recipientIdentityPublicKey: DhPublicKey,
            recipientSignedPreKey: DhPublicKey,
        ): RatchetState {
            legacyInitiatorHandshakeCalls++
            return real.initiatorHandshake(
                initiatorIdentityKeyPair,
                recipientIdentityPublicKey,
                recipientSignedPreKey,
            )
        }

        override fun recipientHandshake(
            recipientIdentityKeyPair: DhKeyPair,
            recipientSignedPreKeyPair: DhKeyPair,
            initiatorIdentityPublicKey: DhPublicKey,
            initiatorEphemeralPublicKey: DhPublicKey,
        ): RatchetState {
            legacyRecipientHandshakeCalls++
            return real.recipientHandshake(
                recipientIdentityKeyPair,
                recipientSignedPreKeyPair,
                initiatorIdentityPublicKey,
                initiatorEphemeralPublicKey,
            )
        }

        override fun initiatorHandshake4DH(
            initiatorIdentityKeyPair: DhKeyPair,
            recipientIdentityPublicKey: DhPublicKey,
            recipientSignedPreKey: DhPublicKey,
            recipientOPK: DhPublicKey?,
        ): RatchetState {
            initiatorHandshake4DHCalls++
            return real.initiatorHandshake4DH(
                initiatorIdentityKeyPair,
                recipientIdentityPublicKey,
                recipientSignedPreKey,
                recipientOPK,
            )
        }

        override fun recipientHandshake4DH(
            recipientIdentityKeyPair: DhKeyPair,
            recipientSignedPreKeyPair: DhKeyPair,
            recipientOPKPair: DhKeyPair?,
            initiatorIdentityPublicKey: DhPublicKey,
            initiatorEphemeralPublicKey: DhPublicKey,
        ): RatchetState {
            recipientHandshake4DHCalls++
            return real.recipientHandshake4DH(
                recipientIdentityKeyPair,
                recipientSignedPreKeyPair,
                recipientOPKPair,
                initiatorIdentityPublicKey,
                initiatorEphemeralPublicKey,
            )
        }

        var initiatorHandshake4DHWithEphemeralCalls = 0
        override fun initiatorHandshake4DHWithEphemeral(
            initiatorIdentityKeyPair: DhKeyPair,
            recipientIdentityPublicKey: DhPublicKey,
            recipientSignedPreKey: DhPublicKey,
            recipientOPK: DhPublicKey?,
            ephemeralKeyPair: DhKeyPair,
        ): RatchetState {
            initiatorHandshake4DHWithEphemeralCalls++
            return real.initiatorHandshake4DHWithEphemeral(
                initiatorIdentityKeyPair,
                recipientIdentityPublicKey,
                recipientSignedPreKey,
                recipientOPK,
                ephemeralKeyPair,
            )
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeManager(
        x3dh: X3DHProtocol,
        ratchetRepo: RatchetStateRepository,
        spkRepo: LocalSignedPreKeyRepository = InMemorySignedPreKeyRepo(),
        opkRepo: LocalOneTimePreKeyRepository = InMemoryOneTimePreKeyRepo(),
        identityCrypto: IdentityCrypto = LibsodiumIdentityCrypto(),
    ): SessionManager = SessionManager(
        x3dh = x3dh,
        ratchetStateRepository = ratchetRepo,
        signedPreKeyRepository = spkRepo,
        oneTimePreKeyRepository = opkRepo,
        identityCrypto = identityCrypto,
        json = json,
    )

    /**
     * Build a Bob-style bundle that Alice can pass to initiatorBootstrap.
     * The bundle is signed by Bob's Ed25519 signing key; Alice's verifier
     * runs against bundle.signing_pubkey_hex which Alice trusts via the
     * channel that delivered Bob's identity (QR code in production).
     */
    private fun buildBundleFromBob(
        bobX25519IdentityHex: String,
        bobEd25519SigningPub: ByteArray,
        bobEd25519SigningSecret: ByteArray,
        bobSpkPair: DhKeyPair,
        bobSpkKeyId: Long,
        bobSpkCreatedAtMs: Long,
        bobOpkPair: DhKeyPair? = null,
        bobOpkIdHex: String? = null,
    ): PreKeyBundle {
        val signature = SignedPreKeySigner.sign(
            spkPublic = bobSpkPair.publicKey,
            createdAtMs = bobSpkCreatedAtMs,
            identityEd25519SecretKey = bobEd25519SigningSecret,
        )
        return PreKeyBundle(
            identityPubkeyHex = bobX25519IdentityHex,
            signingPubkeyHex = bobEd25519SigningPub.toHexString(),
            signedPreKeyId = bobSpkKeyId,
            signedPreKeyPublicHex = bobSpkPair.publicKey.bytes.toHexString(),
            signedPreKeyCreatedAtMs = bobSpkCreatedAtMs,
            signedPreKeySignatureHex = signature.toHexString(),
            oneTimePreKeyIdHex = bobOpkIdHex,
            oneTimePreKeyPublicHex = bobOpkPair?.publicKey?.bytes?.toHexString(),
        )
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it.toInt().and(0xFF)) }

    // ── Tests: load / save / delete plumbing ─────────────────────────────────

    @Test
    fun tryLoadSession_returnsNull_whenNoSession() = runTest {
        LibsodiumInitializer.initialize()
        val mgr = makeManager(LibsodiumX3DH(), InMemoryRatchetRepo())
        assertNull(mgr.tryLoadSession("never-bootstrapped"))
    }

    @Test
    fun saveSession_persists_andTryLoadSession_decodes() = runTest {
        LibsodiumInitializer.initialize()
        val ratchetRepo = InMemoryRatchetRepo()
        val mgr = makeManager(LibsodiumX3DH(), ratchetRepo)

        val state = RatchetState(
            rootKey = ByteArray(32) { it.toByte() },
            sendingChainKey = null,
            receivingChainKey = null,
            sendingRatchetPublicKey = ByteArray(32) { (it + 1).toByte() },
            sendingRatchetPrivateKey = ByteArray(32) { (it + 2).toByte() },
            receivingRatchetPublicKey = null,
        )
        mgr.saveSession("conv-x", state)
        val loaded = mgr.tryLoadSession("conv-x")
        assertNotNull(loaded)
        assertContentEquals(state.rootKey, loaded.rootKey)
        assertContentEquals(state.sendingRatchetPublicKey, loaded.sendingRatchetPublicKey)

        mgr.deleteSession("conv-x")
        assertNull(mgr.tryLoadSession("conv-x"))
    }

    // ── Tests: F12 closure (4-DH path is the only path) ─────────────────────

    @Test
    fun initiatorBootstrap_callsInitiatorHandshake4DH_andNotComputeSharedSecret() = runTest {
        LibsodiumInitializer.initialize()
        val real = LibsodiumX3DH()
        val spy = CountingX3DH(real)
        val ratchetRepo = InMemoryRatchetRepo()
        val mgr = makeManager(spy, ratchetRepo)

        val aliceIdentity = real.generateDhKeyPair()
        val bobIdentity = real.generateDhKeyPair()
        val bobSpk = real.generateDhKeyPair()
        val bobOpk = real.generateDhKeyPair()
        val bobSigning = Signature.keypair()

        val bundle = buildBundleFromBob(
            bobX25519IdentityHex = bobIdentity.publicKey.bytes.toHexString(),
            bobEd25519SigningPub = bobSigning.publicKey.toByteArray(),
            bobEd25519SigningSecret = bobSigning.secretKey.toByteArray(),
            bobSpkPair = bobSpk,
            bobSpkKeyId = 1L,
            bobSpkCreatedAtMs = 1_000L,
            bobOpkPair = bobOpk,
            bobOpkIdHex = "00112233445566778899aabbccddeeff",
        )

        val result = mgr.initiatorBootstrap(
            conversationId = "conv-1",
            localIdentityKeyPair = aliceIdentity,
            bundle = bundle,
        )

        // F12: 4-DH variant was called exactly once. SessionManager goes
        // through `initiatorHandshake4DHWithEphemeral` so it can carry
        // the X3DH ephemeral on the wire — the convenience method
        // `initiatorHandshake4DH` is not on the SessionManager path.
        // The legacy 3-DH path AND the computeSharedSecret bypass were
        // NOT touched.
        assertEquals(1, spy.initiatorHandshake4DHWithEphemeralCalls)
        assertEquals(0, spy.initiatorHandshake4DHCalls)
        assertEquals(0, spy.legacyInitiatorHandshakeCalls)
        assertEquals(
            0,
            spy.computeSharedSecretCalls,
            "F12 closure: SessionManager must not call X3DHProtocol.computeSharedSecret. " +
                "Any non-zero count here means the Alpha 1 bypass has crept back in.",
        )

        // The state was persisted under the conversation id.
        assertTrue(ratchetRepo.isPersisted("conv-1"))
        assertNotNull(result.x3dhInit.opkKeyIdHex, "OPK was supplied so header should reference it")
        assertEquals(1L, result.x3dhInit.spkKeyId)
    }

    @Test
    fun initiatorBootstrap_succeedsWithoutOpk_andHeaderHasNullOpkId() = runTest {
        LibsodiumInitializer.initialize()
        val real = LibsodiumX3DH()
        val spy = CountingX3DH(real)
        val mgr = makeManager(spy, InMemoryRatchetRepo())

        val aliceIdentity = real.generateDhKeyPair()
        val bobIdentity = real.generateDhKeyPair()
        val bobSpk = real.generateDhKeyPair()
        val bobSigning = Signature.keypair()

        val bundle = buildBundleFromBob(
            bobX25519IdentityHex = bobIdentity.publicKey.bytes.toHexString(),
            bobEd25519SigningPub = bobSigning.publicKey.toByteArray(),
            bobEd25519SigningSecret = bobSigning.secretKey.toByteArray(),
            bobSpkPair = bobSpk,
            bobSpkKeyId = 7L,
            bobSpkCreatedAtMs = 1_000L,
            // Bob's OPK pool was empty when Alice fetched — degraded 3-DH path.
        )

        val result = mgr.initiatorBootstrap(
            conversationId = "conv-2",
            localIdentityKeyPair = aliceIdentity,
            bundle = bundle,
        )
        assertEquals(1, spy.initiatorHandshake4DHWithEphemeralCalls)
        assertNull(result.x3dhInit.opkKeyIdHex)
    }

    // ── Tests: F15 invariant (identity NEVER in RatchetState) ────────────────

    @Test
    fun initiatorBootstrap_F15_freshEphemeralIsNotIdentity() = runTest {
        LibsodiumInitializer.initialize()
        val real = LibsodiumX3DH()
        val mgr = makeManager(real, InMemoryRatchetRepo())

        val aliceIdentity = real.generateDhKeyPair()
        val bobIdentity = real.generateDhKeyPair()
        val bobSpk = real.generateDhKeyPair()
        val bobSigning = Signature.keypair()

        val bundle = buildBundleFromBob(
            bobX25519IdentityHex = bobIdentity.publicKey.bytes.toHexString(),
            bobEd25519SigningPub = bobSigning.publicKey.toByteArray(),
            bobEd25519SigningSecret = bobSigning.secretKey.toByteArray(),
            bobSpkPair = bobSpk,
            bobSpkKeyId = 1L,
            bobSpkCreatedAtMs = 1_000L,
        )

        val result = mgr.initiatorBootstrap(
            conversationId = "conv-3",
            localIdentityKeyPair = aliceIdentity,
            bundle = bundle,
        )

        // F15: the persisted ratchet seed must not be the identity keypair.
        // Compromise of the identity must not propagate into the session.
        assertNotEquals(
            aliceIdentity.publicKey.bytes.toList(),
            result.ratchetState.sendingRatchetPublicKey.toList(),
            "F15 violated: sendingRatchetPublicKey equals identity public key",
        )
        assertNotEquals(
            aliceIdentity.privateKey.bytes.toList(),
            result.ratchetState.sendingRatchetPrivateKey.toList(),
            "F15 violated: sendingRatchetPrivateKey equals identity private key",
        )
    }

    @Test
    fun initiatorBootstrap_F15_throwsOnRegressedX3DH_thatReusesIdentity() = runTest {
        LibsodiumInitializer.initialize()

        // Rigged X3DH that deliberately returns an Alpha-1-style state
        // (identity reused as ratchet seed). SessionManager's F15 require
        // block must catch this before any state hits the repository.
        val real = LibsodiumX3DH()
        val regressed = object : X3DHProtocol by real {
            // SessionManager calls initiatorHandshake4DHWithEphemeral —
            // override that variant to emit an Alpha-1-style state where
            // the identity keypair is reused as the ratchet seed. The
            // require() in SessionManager.initiatorBootstrap must catch
            // this before any state is persisted.
            override fun initiatorHandshake4DHWithEphemeral(
                initiatorIdentityKeyPair: DhKeyPair,
                recipientIdentityPublicKey: DhPublicKey,
                recipientSignedPreKey: DhPublicKey,
                recipientOPK: DhPublicKey?,
                ephemeralKeyPair: DhKeyPair,
            ): RatchetState = RatchetState(
                rootKey = ByteArray(32),
                sendingChainKey = null,
                receivingChainKey = null,
                // F15 violation: identity reused.
                sendingRatchetPublicKey = initiatorIdentityKeyPair.publicKey.bytes,
                sendingRatchetPrivateKey = initiatorIdentityKeyPair.privateKey.bytes,
                receivingRatchetPublicKey = recipientSignedPreKey.bytes,
            )
        }
        val ratchetRepo = InMemoryRatchetRepo()
        val mgr = makeManager(regressed, ratchetRepo)

        val aliceIdentity = real.generateDhKeyPair()
        val bobIdentity = real.generateDhKeyPair()
        val bobSpk = real.generateDhKeyPair()
        val bobSigning = Signature.keypair()
        val bundle = buildBundleFromBob(
            bobX25519IdentityHex = bobIdentity.publicKey.bytes.toHexString(),
            bobEd25519SigningPub = bobSigning.publicKey.toByteArray(),
            bobEd25519SigningSecret = bobSigning.secretKey.toByteArray(),
            bobSpkPair = bobSpk,
            bobSpkKeyId = 1L,
            bobSpkCreatedAtMs = 1_000L,
        )

        assertFails {
            mgr.initiatorBootstrap("conv-4", aliceIdentity, bundle)
        }

        // Critical: the regressed state must NOT have been persisted.
        // The require() in SessionManager is the gate — a regression that
        // skips the gate would silently write F15-violating state to disk.
        assertTrue(
            !ratchetRepo.isPersisted("conv-4"),
            "Regressed state must not hit storage when F15 require fires",
        )
    }

    // ── Tests: bundle signature verification ────────────────────────────────

    @Test
    fun initiatorBootstrap_rejectsBundle_withInvalidSpkSignature() = runTest {
        LibsodiumInitializer.initialize()
        val real = LibsodiumX3DH()
        val mgr = makeManager(real, InMemoryRatchetRepo())

        val aliceIdentity = real.generateDhKeyPair()
        val bobIdentity = real.generateDhKeyPair()
        val bobSpk = real.generateDhKeyPair()
        val bobSigning = Signature.keypair()
        val attackerSigning = Signature.keypair()

        // Bundle signed by attacker but published claiming Bob's signing key.
        val attackerSig = SignedPreKeySigner.sign(
            spkPublic = bobSpk.publicKey,
            createdAtMs = 1_000L,
            identityEd25519SecretKey = attackerSigning.secretKey.toByteArray(),
        )
        val bundle = PreKeyBundle(
            identityPubkeyHex = bobIdentity.publicKey.bytes.toHexString(),
            signingPubkeyHex = bobSigning.publicKey.toByteArray().toHexString(),
            signedPreKeyId = 1L,
            signedPreKeyPublicHex = bobSpk.publicKey.bytes.toHexString(),
            signedPreKeyCreatedAtMs = 1_000L,
            signedPreKeySignatureHex = attackerSig.toHexString(),
        )

        try {
            mgr.initiatorBootstrap("conv-5", aliceIdentity, bundle)
            fail("expected SessionBootstrapException.InvalidSpkSignature")
        } catch (e: SessionBootstrapException.InvalidSpkSignature) {
            // pass
        }
    }

    // ── Tests: end-to-end 4-DH round-trip via SessionManager ────────────────

    @Test
    fun initiatorAndRecipient_deriveMatchingRootKey_endToEnd() = runTest {
        LibsodiumInitializer.initialize()
        val real = LibsodiumX3DH()

        val aliceIdentity = real.generateDhKeyPair()
        val bobIdentity = real.generateDhKeyPair()
        val bobSpk = real.generateDhKeyPair()
        val bobOpk = real.generateDhKeyPair()
        val bobOpkIdHex = "00112233445566778899aabbccddeeff"
        val bobSigning = Signature.keypair()

        // Alice runs initiatorBootstrap.
        val aliceMgr = makeManager(real, InMemoryRatchetRepo())
        val bundle = buildBundleFromBob(
            bobX25519IdentityHex = bobIdentity.publicKey.bytes.toHexString(),
            bobEd25519SigningPub = bobSigning.publicKey.toByteArray(),
            bobEd25519SigningSecret = bobSigning.secretKey.toByteArray(),
            bobSpkPair = bobSpk,
            bobSpkKeyId = 42L,
            bobSpkCreatedAtMs = 1_000L,
            bobOpkPair = bobOpk,
            bobOpkIdHex = bobOpkIdHex,
        )
        val initiatorResult = aliceMgr.initiatorBootstrap(
            conversationId = "alice-bob",
            localIdentityKeyPair = aliceIdentity,
            bundle = bundle,
        )

        // Bob receives Alice's first message: x3dhInit header tells him
        // which SPK + OPK to load. He pre-stored both keypairs locally
        // so recipientBootstrap can find them.
        val bobSpkRepo = InMemorySignedPreKeyRepo().also {
            it.upsert(
                LocalSignedPreKeyEntity(
                    keyId = 42L,
                    publicKeyHex = bobSpk.publicKey.bytes.toHexString(),
                    privateKeyHex = bobSpk.privateKey.bytes.toHexString(),
                    createdAtMs = 1_000L,
                    signatureHex = "00".repeat(64), // signature already verified by Alice; not re-checked here
                ),
            )
        }
        val bobOpkRepo = InMemoryOneTimePreKeyRepo().also {
            it.insert(
                LocalOneTimePreKeyEntity(
                    keyIdHex = bobOpkIdHex,
                    publicKeyHex = bobOpk.publicKey.bytes.toHexString(),
                    privateKeyHex = bobOpk.privateKey.bytes.toHexString(),
                    uploadedAtMs = 500L,
                ),
            )
        }
        val bobMgr = makeManager(
            x3dh = real,
            ratchetRepo = InMemoryRatchetRepo(),
            spkRepo = bobSpkRepo,
            opkRepo = bobOpkRepo,
        )

        val bobState = bobMgr.recipientBootstrap(
            conversationId = "alice-bob",
            localIdentityKeyPair = bobIdentity,
            senderIdentityPublicKeyHex = aliceIdentity.publicKey.bytes.toHexString(),
            x3dhInit = initiatorResult.x3dhInit,
        )

        // The whole point: both sides derive the same root key.
        assertContentEquals(
            initiatorResult.ratchetState.rootKey,
            bobState.rootKey,
            "Initiator and recipient must derive the same root key from the same bundle inputs",
        )

        // The OPK was consumed: Bob's pool no longer contains it.
        assertTrue(
            !bobOpkRepo.has(bobOpkIdHex),
            "OPK must be deleted from Bob's pool after consumption (single-use)",
        )
    }

    // ── Tests: recipient-side error paths ───────────────────────────────────

    @Test
    fun recipientBootstrap_throwsSpkNotFound_whenLocalSpkAbsent() = runTest {
        LibsodiumInitializer.initialize()
        val real = LibsodiumX3DH()
        val mgr = makeManager(real, InMemoryRatchetRepo()) // empty SPK repo by default

        val aliceIdentity = real.generateDhKeyPair()
        val bobIdentity = real.generateDhKeyPair()
        val header = X3dhInitHeader(
            ephemeralPubKeyHex = real.generateDhKeyPair().publicKey.bytes.toHexString(),
            spkKeyId = 999L,  // Bob has no SPK at all
            opkKeyIdHex = null,
        )
        try {
            mgr.recipientBootstrap(
                conversationId = "alice-bob",
                localIdentityKeyPair = bobIdentity,
                senderIdentityPublicKeyHex = aliceIdentity.publicKey.bytes.toHexString(),
                x3dhInit = header,
            )
            fail("expected SessionBootstrapException.SpkNotFound")
        } catch (e: SessionBootstrapException.SpkNotFound) {
            assertEquals(999L, e.spkKeyId)
        }
    }

    @Test
    fun recipientBootstrap_throwsOpkNotFound_whenInitiatorReferencesUnknownOpk() = runTest {
        LibsodiumInitializer.initialize()
        val real = LibsodiumX3DH()
        val bobIdentity = real.generateDhKeyPair()
        val aliceIdentity = real.generateDhKeyPair()
        val bobSpk = real.generateDhKeyPair()

        val spkRepo = InMemorySignedPreKeyRepo().also {
            it.upsert(
                LocalSignedPreKeyEntity(
                    keyId = 5L,
                    publicKeyHex = bobSpk.publicKey.bytes.toHexString(),
                    privateKeyHex = bobSpk.privateKey.bytes.toHexString(),
                    createdAtMs = 0L,
                    signatureHex = "00".repeat(64),
                ),
            )
        }
        // Empty OPK repo — initiator claims an OPK we don't have.
        val mgr = makeManager(
            x3dh = real,
            ratchetRepo = InMemoryRatchetRepo(),
            spkRepo = spkRepo,
            opkRepo = InMemoryOneTimePreKeyRepo(),
        )
        val header = X3dhInitHeader(
            ephemeralPubKeyHex = real.generateDhKeyPair().publicKey.bytes.toHexString(),
            spkKeyId = 5L,
            opkKeyIdHex = "deadbeef".repeat(4),
        )
        try {
            mgr.recipientBootstrap(
                conversationId = "x",
                localIdentityKeyPair = bobIdentity,
                senderIdentityPublicKeyHex = aliceIdentity.publicKey.bytes.toHexString(),
                x3dhInit = header,
            )
            fail("expected SessionBootstrapException.OpkNotFound")
        } catch (e: SessionBootstrapException.OpkNotFound) {
            assertTrue(e.opkKeyIdHex.startsWith("deadbeef"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PR-CRYPTO-INBOUND-X3DH-REPAIR1 commit 1 (2026-05-29)
    //
    // Tests for the new `recipientBootstrapInMemory` API surface. The
    // method is the crypto-only sibling of `recipientBootstrap` —
    // SAME derivation path, SAME OPK consumption (eager), SAME F15
    // invariants, but does NOT call `saveSession`. The caller (Commit
    // 2 receive-path repair branch) commits the candidate state via
    // `saveSession` ONLY AFTER candidate-decrypt succeeds.
    //
    // The 3 commit-1 acceptance gates (mini-lock §Implementation plan
    // + Vladislav 2026-05-29):
    //
    //   1. API returns non-null `RatchetState`; failure throws typed
    //      exception so `DECRYPT_TRACE inbound_repair_fail errorClass=…`
    //      preserves diagnostic specificity.
    //   2. Old ratchet session row preserved on derivation failure (no
    //      `upsertRatchetState` call before the caller's saveSession).
    //   3. OPK consumption documented as explicit decision (eager
    //      consume; tested for byte-identity with `recipientBootstrap`).
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun recipientBootstrapInMemory_success_returnsCandidateAndDoesNotPersist() = runTest {
        LibsodiumInitializer.initialize()
        val real = LibsodiumX3DH()
        val aliceIdentity = real.generateDhKeyPair()
        val bobIdentity = real.generateDhKeyPair()
        val bobSpk = real.generateDhKeyPair()
        val bobOpk = real.generateDhKeyPair()
        val bobOpkIdHex = "ffeeddccbbaa99887766554433221100"
        val bobSigning = Signature.keypair()

        // Alice runs initiatorBootstrap so we have a real x3dhInit
        // header to feed Bob's in-memory recipient bootstrap.
        val aliceMgr = makeManager(real, InMemoryRatchetRepo())
        val bundle = buildBundleFromBob(
            bobX25519IdentityHex = bobIdentity.publicKey.bytes.toHexString(),
            bobEd25519SigningPub = bobSigning.publicKey.toByteArray(),
            bobEd25519SigningSecret = bobSigning.secretKey.toByteArray(),
            bobSpkPair = bobSpk,
            bobSpkKeyId = 7L,
            bobSpkCreatedAtMs = 1_000L,
            bobOpkPair = bobOpk,
            bobOpkIdHex = bobOpkIdHex,
        )
        val initiatorResult = aliceMgr.initiatorBootstrap(
            conversationId = "alice-bob",
            localIdentityKeyPair = aliceIdentity,
            bundle = bundle,
        )

        val bobSpkRepo = InMemorySignedPreKeyRepo().also {
            it.upsert(
                LocalSignedPreKeyEntity(
                    keyId = 7L,
                    publicKeyHex = bobSpk.publicKey.bytes.toHexString(),
                    privateKeyHex = bobSpk.privateKey.bytes.toHexString(),
                    createdAtMs = 1_000L,
                    signatureHex = "00".repeat(64),
                ),
            )
        }
        val bobOpkRepo = InMemoryOneTimePreKeyRepo().also {
            it.insert(
                LocalOneTimePreKeyEntity(
                    keyIdHex = bobOpkIdHex,
                    publicKeyHex = bobOpk.publicKey.bytes.toHexString(),
                    privateKeyHex = bobOpk.privateKey.bytes.toHexString(),
                    uploadedAtMs = 500L,
                ),
            )
        }
        val bobRatchetRepo = InMemoryRatchetRepo()
        val bobMgr = makeManager(
            x3dh = real,
            ratchetRepo = bobRatchetRepo,
            spkRepo = bobSpkRepo,
            opkRepo = bobOpkRepo,
        )

        val candidate = bobMgr.recipientBootstrapInMemory(
            conversationId = "alice-bob",
            localIdentityKeyPair = bobIdentity,
            senderIdentityPublicKeyHex = aliceIdentity.publicKey.bytes.toHexString(),
            x3dhInit = initiatorResult.x3dhInit,
        )

        // Gate 1: returned a non-null RatchetState that derives the
        // SAME root key as Alice's initiator state (proving the
        // crypto path is byte-identical to existing recipientBootstrap).
        assertContentEquals(
            initiatorResult.ratchetState.rootKey,
            candidate.rootKey,
            "recipientBootstrapInMemory must derive the same root key as initiatorBootstrap " +
                "(byte-identical crypto path with recipientBootstrap)",
        )

        // Gate 2 (CENTRAL): no saveSession was called — the on-disk
        // ratchet state row for "alice-bob" is NOT populated.
        assertTrue(
            !bobRatchetRepo.isPersisted("alice-bob"),
            "recipientBootstrapInMemory MUST NOT persist the candidate state. " +
                "The caller commits via saveSession() ONLY AFTER candidate-decrypt succeeds. " +
                "Mini-lock §Scope item 5: OLD RATCHET SESSION MUST BE PRESERVED on failure.",
        )

        // Gate 3: OPK was eagerly consumed (matches existing
        // recipientBootstrap behaviour; explicit implementation decision
        // documented in mini-lock §Scope item 5).
        assertTrue(
            !bobOpkRepo.has(bobOpkIdHex),
            "OPK must be deleted from Bob's pool after eager consumption (explicit " +
                "implementation decision matching recipientBootstrap pattern)",
        )
    }

    @Test
    fun recipientBootstrapInMemory_throwsSpkNotFound_andDoesNotPersist() = runTest {
        LibsodiumInitializer.initialize()
        val real = LibsodiumX3DH()
        val bobRatchetRepo = InMemoryRatchetRepo()
        // Empty SPK repo by default — Bob has no SPK at all.
        val mgr = makeManager(real, bobRatchetRepo)
        val aliceIdentity = real.generateDhKeyPair()
        val bobIdentity = real.generateDhKeyPair()
        val header = X3dhInitHeader(
            ephemeralPubKeyHex = real.generateDhKeyPair().publicKey.bytes.toHexString(),
            spkKeyId = 777L,
            opkKeyIdHex = null,
        )

        try {
            mgr.recipientBootstrapInMemory(
                conversationId = "alice-bob-spk-missing",
                localIdentityKeyPair = bobIdentity,
                senderIdentityPublicKeyHex = aliceIdentity.publicKey.bytes.toHexString(),
                x3dhInit = header,
            )
            fail("expected SessionBootstrapException.SpkNotFound (typed exception per gate 1)")
        } catch (e: SessionBootstrapException.SpkNotFound) {
            // Gate 1 satisfied: typed exception (not a nullable return,
            // not an IllegalStateException). The caller can log
            // errorClass=SpkNotFound directly.
            assertEquals(777L, e.spkKeyId)
        }

        // Gate 2 (CENTRAL): the on-disk ratchet state row for
        // "alice-bob-spk-missing" was NEVER touched. This is the
        // invariant the whole PR exists to guarantee.
        assertTrue(
            !bobRatchetRepo.isPersisted("alice-bob-spk-missing"),
            "OLD RATCHET SESSION MUST BE PRESERVED on bootstrap failure (SpkNotFound). " +
                "Mini-lock §Scope item 5.",
        )
    }

    @Test
    fun recipientBootstrapInMemory_throwsOpkNotFound_andDoesNotPersist() = runTest {
        LibsodiumInitializer.initialize()
        val real = LibsodiumX3DH()
        val bobIdentity = real.generateDhKeyPair()
        val aliceIdentity = real.generateDhKeyPair()
        val bobSpk = real.generateDhKeyPair()

        val spkRepo = InMemorySignedPreKeyRepo().also {
            it.upsert(
                LocalSignedPreKeyEntity(
                    keyId = 11L,
                    publicKeyHex = bobSpk.publicKey.bytes.toHexString(),
                    privateKeyHex = bobSpk.privateKey.bytes.toHexString(),
                    createdAtMs = 0L,
                    signatureHex = "00".repeat(64),
                ),
            )
        }
        // OPK repo is intentionally empty — initiator referenced an
        // OPK Bob never had (or already consumed).
        val bobOpkRepo = InMemoryOneTimePreKeyRepo()
        val bobRatchetRepo = InMemoryRatchetRepo()
        val mgr = makeManager(
            x3dh = real,
            ratchetRepo = bobRatchetRepo,
            spkRepo = spkRepo,
            opkRepo = bobOpkRepo,
        )

        val header = X3dhInitHeader(
            ephemeralPubKeyHex = real.generateDhKeyPair().publicKey.bytes.toHexString(),
            spkKeyId = 11L,
            opkKeyIdHex = "deadbeefcafef00d1234567890abcdef",
        )

        try {
            mgr.recipientBootstrapInMemory(
                conversationId = "alice-bob-opk-missing",
                localIdentityKeyPair = bobIdentity,
                senderIdentityPublicKeyHex = aliceIdentity.publicKey.bytes.toHexString(),
                x3dhInit = header,
            )
            fail("expected SessionBootstrapException.OpkNotFound (typed exception per gate 1)")
        } catch (e: SessionBootstrapException.OpkNotFound) {
            // Gate 1: typed exception preserves errorClass=OpkNotFound
            // for `DECRYPT_TRACE inbound_repair_fail`.
            assertTrue(e.opkKeyIdHex.startsWith("deadbeef"))
        }

        // Gate 2 (CENTRAL): the on-disk ratchet state row was NEVER
        // touched on OPK lookup failure either.
        assertTrue(
            !bobRatchetRepo.isPersisted("alice-bob-opk-missing"),
            "OLD RATCHET SESSION MUST BE PRESERVED on bootstrap failure (OpkNotFound). " +
                "Mini-lock §Scope item 5.",
        )
    }

    @Test
    fun recipientBootstrap_stillPersistsAfterRefactor_regressionCheck() = runTest {
        // After PR-CRYPTO-INBOUND-X3DH-REPAIR1 commit 1, the existing
        // `recipientBootstrap` is a thin wrapper over
        // `recipientBootstrapInMemory` + `saveSession`. This regression
        // test proves the wrapper still persists the state on success —
        // the existing no-session bootstrap path in
        // `DefaultMessagingService.handleDeliver` continues to see the
        // same persistence behaviour as before the refactor.
        LibsodiumInitializer.initialize()
        val real = LibsodiumX3DH()
        val aliceIdentity = real.generateDhKeyPair()
        val bobIdentity = real.generateDhKeyPair()
        val bobSpk = real.generateDhKeyPair()
        val bobOpk = real.generateDhKeyPair()
        val bobOpkIdHex = "aaaaaaaaaaaaaaaa0000000000000000"
        val bobSigning = Signature.keypair()

        val aliceMgr = makeManager(real, InMemoryRatchetRepo())
        val bundle = buildBundleFromBob(
            bobX25519IdentityHex = bobIdentity.publicKey.bytes.toHexString(),
            bobEd25519SigningPub = bobSigning.publicKey.toByteArray(),
            bobEd25519SigningSecret = bobSigning.secretKey.toByteArray(),
            bobSpkPair = bobSpk,
            bobSpkKeyId = 13L,
            bobSpkCreatedAtMs = 1_000L,
            bobOpkPair = bobOpk,
            bobOpkIdHex = bobOpkIdHex,
        )
        val initiatorResult = aliceMgr.initiatorBootstrap(
            conversationId = "alice-bob-regression",
            localIdentityKeyPair = aliceIdentity,
            bundle = bundle,
        )

        val bobSpkRepo = InMemorySignedPreKeyRepo().also {
            it.upsert(
                LocalSignedPreKeyEntity(
                    keyId = 13L,
                    publicKeyHex = bobSpk.publicKey.bytes.toHexString(),
                    privateKeyHex = bobSpk.privateKey.bytes.toHexString(),
                    createdAtMs = 1_000L,
                    signatureHex = "00".repeat(64),
                ),
            )
        }
        val bobOpkRepo = InMemoryOneTimePreKeyRepo().also {
            it.insert(
                LocalOneTimePreKeyEntity(
                    keyIdHex = bobOpkIdHex,
                    publicKeyHex = bobOpk.publicKey.bytes.toHexString(),
                    privateKeyHex = bobOpk.privateKey.bytes.toHexString(),
                    uploadedAtMs = 500L,
                ),
            )
        }
        val bobRatchetRepo = InMemoryRatchetRepo()
        val bobMgr = makeManager(
            x3dh = real,
            ratchetRepo = bobRatchetRepo,
            spkRepo = bobSpkRepo,
            opkRepo = bobOpkRepo,
        )

        val bobState = bobMgr.recipientBootstrap(
            conversationId = "alice-bob-regression",
            localIdentityKeyPair = bobIdentity,
            senderIdentityPublicKeyHex = aliceIdentity.publicKey.bytes.toHexString(),
            x3dhInit = initiatorResult.x3dhInit,
        )

        // Crypto behaviour unchanged: same root key as Alice's
        // initiator state.
        assertContentEquals(
            initiatorResult.ratchetState.rootKey,
            bobState.rootKey,
            "recipientBootstrap refactor must preserve byte-identical crypto path",
        )

        // Persistence behaviour unchanged: state IS persisted (this is
        // the difference from recipientBootstrapInMemory).
        assertTrue(
            bobRatchetRepo.isPersisted("alice-bob-regression"),
            "recipientBootstrap MUST still persist after refactor (regression check)",
        )

        // OPK was consumed (same as pre-refactor).
        assertTrue(
            !bobOpkRepo.has(bobOpkIdHex),
            "recipientBootstrap MUST still consume OPK after refactor (regression check)",
        )
    }
}
