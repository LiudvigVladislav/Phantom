// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.signature.Signature
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Test vectors for the X3DH 4-DH foundation (PR A of the F12 + F15 fix).
 *
 * Every test calls `LibsodiumInitializer.initialize()` first because the JVM
 * target wires libsodium through JNA at runtime. The initializer is
 * idempotent so calling it per-test costs nothing after the first one.
 *
 * Coverage:
 *  - 4-DH round trip with OPK
 *  - 4-DH round trip without OPK (degraded path)
 *  - 3-DH legacy round trip (regression guard — backward compat)
 *  - 4-DH masterSecret distinct from 3-DH masterSecret over the same DH inputs
 *  - F15 invariant: the initiator's sendingRatchet keypair is never the
 *    identity keypair
 *  - mismatched recipient identity → masterSecret diverges
 *  - mismatched OPK → masterSecret diverges
 *  - SignedPreKeySigner round trip (sign / verify)
 *  - tampered SPK pubkey → signature rejected
 *  - tampered timestamp → signature rejected
 *  - wrong verifier identity → signature rejected
 *  - HKDF determinism — same IKM/salt/info yields the same OKM
 */
@OptIn(ExperimentalUnsignedTypes::class)
class X3DH4DHTest {

    private val protocol = LibsodiumX3DH()

    // ── round-trip ───────────────────────────────────────────────────────────

    @Test
    fun fourDh_roundTrip_withOpk_yieldsMatchingMasterSecret() = runTest {
        LibsodiumInitializer.initialize()

        val aliceIdentity = protocol.generateDhKeyPair()
        val bobIdentity   = protocol.generateDhKeyPair()
        val bobSpk        = protocol.generateDhKeyPair()
        val bobOpk        = protocol.generateDhKeyPair()
        val aliceEphemeral = protocol.generateDhKeyPair()

        val initState = protocol.initiatorHandshake4DHWithEphemeral(
            initiatorIdentityKeyPair   = aliceIdentity,
            recipientIdentityPublicKey = bobIdentity.publicKey,
            recipientSignedPreKey      = bobSpk.publicKey,
            recipientOPK               = bobOpk.publicKey,
            ephemeralKeyPair           = aliceEphemeral,
        )
        val recipientState = protocol.recipientHandshake4DH(
            recipientIdentityKeyPair    = bobIdentity,
            recipientSignedPreKeyPair   = bobSpk,
            recipientOPKPair            = bobOpk,
            initiatorIdentityPublicKey  = aliceIdentity.publicKey,
            initiatorEphemeralPublicKey = aliceEphemeral.publicKey,
        )

        assertContentEquals(initState.rootKey, recipientState.rootKey)
        assertEquals(32, initState.rootKey.size)
    }

    @Test
    fun fourDh_roundTrip_withoutOpk_yieldsMatchingMasterSecret() = runTest {
        LibsodiumInitializer.initialize()

        val aliceIdentity = protocol.generateDhKeyPair()
        val bobIdentity   = protocol.generateDhKeyPair()
        val bobSpk        = protocol.generateDhKeyPair()
        val aliceEphemeral = protocol.generateDhKeyPair()

        val initState = protocol.initiatorHandshake4DHWithEphemeral(
            initiatorIdentityKeyPair   = aliceIdentity,
            recipientIdentityPublicKey = bobIdentity.publicKey,
            recipientSignedPreKey      = bobSpk.publicKey,
            recipientOPK               = null,
            ephemeralKeyPair           = aliceEphemeral,
        )
        val recipientState = protocol.recipientHandshake4DH(
            recipientIdentityKeyPair    = bobIdentity,
            recipientSignedPreKeyPair   = bobSpk,
            recipientOPKPair            = null,
            initiatorIdentityPublicKey  = aliceIdentity.publicKey,
            initiatorEphemeralPublicKey = aliceEphemeral.publicKey,
        )

        assertContentEquals(initState.rootKey, recipientState.rootKey)
    }

    @Test
    fun threeDh_legacyRoundTrip_stillWorks() = runTest {
        LibsodiumInitializer.initialize()

        val aliceIdentity = protocol.generateDhKeyPair()
        val bobIdentity   = protocol.generateDhKeyPair()
        val bobSpk        = protocol.generateDhKeyPair()
        val aliceEphemeral = protocol.generateDhKeyPair()

        val initState = protocol.initiatorHandshakeWithEphemeral(
            initiatorIdentityKeyPair   = aliceIdentity,
            recipientIdentityPublicKey = bobIdentity.publicKey,
            recipientSignedPreKey      = bobSpk.publicKey,
            ephemeralKeyPair           = aliceEphemeral,
        )
        val recipientState = protocol.recipientHandshake(
            recipientIdentityKeyPair    = bobIdentity,
            recipientSignedPreKeyPair   = bobSpk,
            initiatorIdentityPublicKey  = aliceIdentity.publicKey,
            initiatorEphemeralPublicKey = aliceEphemeral.publicKey,
        )

        assertContentEquals(initState.rootKey, recipientState.rootKey)
    }

    @Test
    fun fourDh_and_threeDh_produceDifferentMasterSecrets_overSameInputs() = runTest {
        LibsodiumInitializer.initialize()

        val aliceIdentity = protocol.generateDhKeyPair()
        val bobIdentity   = protocol.generateDhKeyPair()
        val bobSpk        = protocol.generateDhKeyPair()
        val aliceEphemeral = protocol.generateDhKeyPair()

        val threeDh = protocol.initiatorHandshakeWithEphemeral(
            initiatorIdentityKeyPair   = aliceIdentity,
            recipientIdentityPublicKey = bobIdentity.publicKey,
            recipientSignedPreKey      = bobSpk.publicKey,
            ephemeralKeyPair           = aliceEphemeral,
        )
        // 4-DH without OPK still differs from 3-DH because the KDF salt is
        // different (HKDF with phantom-x3dh-v2 vs raw SHA-256). That is the
        // domain separation guarantee we depend on for the migration cutover.
        val fourDhNoOpk = protocol.initiatorHandshake4DHWithEphemeral(
            initiatorIdentityKeyPair   = aliceIdentity,
            recipientIdentityPublicKey = bobIdentity.publicKey,
            recipientSignedPreKey      = bobSpk.publicKey,
            recipientOPK               = null,
            ephemeralKeyPair           = aliceEphemeral,
        )

        assertFalse(
            threeDh.rootKey.contentEquals(fourDhNoOpk.rootKey),
            "Legacy 3-DH and 4-DH-no-OPK must produce distinct rootKeys " +
                "via salt domain separation",
        )
    }

    // ── F15 invariant ────────────────────────────────────────────────────────

    @Test
    fun fourDh_initiatorRatchetSeed_isNotIdentityKey() = runTest {
        LibsodiumInitializer.initialize()

        val aliceIdentity = protocol.generateDhKeyPair()
        val bobIdentity   = protocol.generateDhKeyPair()
        val bobSpk        = protocol.generateDhKeyPair()
        val aliceEphemeral = protocol.generateDhKeyPair()

        val state = protocol.initiatorHandshake4DHWithEphemeral(
            initiatorIdentityKeyPair   = aliceIdentity,
            recipientIdentityPublicKey = bobIdentity.publicKey,
            recipientSignedPreKey      = bobSpk.publicKey,
            recipientOPK               = null,
            ephemeralKeyPair           = aliceEphemeral,
        )

        // F15: the ratchet seed MUST be a fresh ephemeral, never the identity
        // public/private key. This is the structural guard that prevents
        // identity-key compromise from also compromising every session.
        assertFalse(
            state.sendingRatchetPublicKey.contentEquals(aliceIdentity.publicKey.bytes),
            "F15: sending ratchet pubkey must NOT equal identity pubkey",
        )
        assertFalse(
            state.sendingRatchetPrivateKey.contentEquals(aliceIdentity.privateKey.bytes),
            "F15: sending ratchet privkey must NOT equal identity privkey",
        )
    }

    // ── divergence on mismatch ───────────────────────────────────────────────

    @Test
    fun fourDh_mismatchedRecipientIdentity_yieldsDifferentSecret() = runTest {
        LibsodiumInitializer.initialize()

        val aliceIdentity = protocol.generateDhKeyPair()
        val bobIdentity   = protocol.generateDhKeyPair()
        val malloryIdentity = protocol.generateDhKeyPair()
        val bobSpk        = protocol.generateDhKeyPair()
        val aliceEphemeral = protocol.generateDhKeyPair()

        val initVsBob = protocol.initiatorHandshake4DHWithEphemeral(
            initiatorIdentityKeyPair   = aliceIdentity,
            recipientIdentityPublicKey = bobIdentity.publicKey,
            recipientSignedPreKey      = bobSpk.publicKey,
            recipientOPK               = null,
            ephemeralKeyPair           = aliceEphemeral,
        )
        // Bob runs the recipient side but with Mallory's identity in place
        // of his own — must diverge.
        val recipientWithWrongIdentity = protocol.recipientHandshake4DH(
            recipientIdentityKeyPair    = malloryIdentity,
            recipientSignedPreKeyPair   = bobSpk,
            recipientOPKPair            = null,
            initiatorIdentityPublicKey  = aliceIdentity.publicKey,
            initiatorEphemeralPublicKey = aliceEphemeral.publicKey,
        )

        assertFalse(initVsBob.rootKey.contentEquals(recipientWithWrongIdentity.rootKey))
    }

    @Test
    fun fourDh_mismatchedOpk_yieldsDifferentSecret() = runTest {
        LibsodiumInitializer.initialize()

        val aliceIdentity = protocol.generateDhKeyPair()
        val bobIdentity   = protocol.generateDhKeyPair()
        val bobSpk        = protocol.generateDhKeyPair()
        val bobOpkA       = protocol.generateDhKeyPair()
        val bobOpkB       = protocol.generateDhKeyPair()
        val aliceEphemeral = protocol.generateDhKeyPair()

        val initWithOpkA = protocol.initiatorHandshake4DHWithEphemeral(
            initiatorIdentityKeyPair   = aliceIdentity,
            recipientIdentityPublicKey = bobIdentity.publicKey,
            recipientSignedPreKey      = bobSpk.publicKey,
            recipientOPK               = bobOpkA.publicKey,
            ephemeralKeyPair           = aliceEphemeral,
        )
        // Bob completes the handshake but pulled the WRONG OPK private key
        // from disk (e.g. id mismatch). Result must diverge.
        val recipientWithOpkB = protocol.recipientHandshake4DH(
            recipientIdentityKeyPair    = bobIdentity,
            recipientSignedPreKeyPair   = bobSpk,
            recipientOPKPair            = bobOpkB,
            initiatorIdentityPublicKey  = aliceIdentity.publicKey,
            initiatorEphemeralPublicKey = aliceEphemeral.publicKey,
        )

        assertFalse(initWithOpkA.rootKey.contentEquals(recipientWithOpkB.rootKey))
    }

    // ── SignedPreKeySigner ───────────────────────────────────────────────────

    @Test
    fun signedPreKeySigner_roundTrip_verifies() = runTest {
        LibsodiumInitializer.initialize()

        val signingKp = Signature.keypair()
        val identitySecret = signingKp.secretKey.toByteArray()
        val identityPublic = signingKp.publicKey.toByteArray()

        val spkPub = protocol.generateDhKeyPair().publicKey
        val createdAtMs = 1_700_000_000_000L

        val sig = SignedPreKeySigner.sign(spkPub, createdAtMs, identitySecret)
        assertEquals(64, sig.size, "Ed25519 detached signature must be 64 bytes")
        assertTrue(SignedPreKeySigner.verify(spkPub, createdAtMs, sig, identityPublic))
    }

    @Test
    fun signedPreKeySigner_tamperedPubkey_isRejected() = runTest {
        LibsodiumInitializer.initialize()

        val signingKp = Signature.keypair()
        val secret = signingKp.secretKey.toByteArray()
        val pub    = signingKp.publicKey.toByteArray()

        val realSpk = protocol.generateDhKeyPair().publicKey
        val sig = SignedPreKeySigner.sign(realSpk, 100L, secret)

        // Flip a single bit in the SPK public key — verifier must reject.
        val tamperedBytes = realSpk.bytes.copyOf()
        tamperedBytes[0] = (tamperedBytes[0].toInt() xor 0x01).toByte()
        val tampered = DhPublicKey(tamperedBytes)

        assertFalse(SignedPreKeySigner.verify(tampered, 100L, sig, pub))
    }

    @Test
    fun signedPreKeySigner_tamperedTimestamp_isRejected() = runTest {
        LibsodiumInitializer.initialize()

        val signingKp = Signature.keypair()
        val secret = signingKp.secretKey.toByteArray()
        val pub    = signingKp.publicKey.toByteArray()

        val spkPub = protocol.generateDhKeyPair().publicKey
        val sig = SignedPreKeySigner.sign(spkPub, 100L, secret)

        // Same SPK, different timestamp — must reject (anti-replay).
        assertFalse(SignedPreKeySigner.verify(spkPub, 101L, sig, pub))
    }

    @Test
    fun signedPreKeySigner_wrongVerifierIdentity_isRejected() = runTest {
        LibsodiumInitializer.initialize()

        val signerKp = Signature.keypair()
        val attackerKp = Signature.keypair()
        val spkPub = protocol.generateDhKeyPair().publicKey
        val sig = SignedPreKeySigner.sign(spkPub, 100L, signerKp.secretKey.toByteArray())

        // Verify with the attacker's public key — must reject.
        assertFalse(
            SignedPreKeySigner.verify(spkPub, 100L, sig, attackerKp.publicKey.toByteArray()),
        )
    }

    // ── HKDF determinism ─────────────────────────────────────────────────────

    @Test
    fun hkdf_isDeterministic_overSameInputs() = runTest {
        LibsodiumInitializer.initialize()

        val ikm  = ByteArray(48) { it.toByte() }
        val salt = ByteArray(32) { (255 - it).toByte() }
        val info = "phantom-test".encodeToByteArray()

        val a = LibsodiumX3DH.hkdfSha256L32(ikm, salt, info)
        val b = LibsodiumX3DH.hkdfSha256L32(ikm, salt, info)
        assertContentEquals(a, b)
        assertEquals(32, a.size)

        // Different info → different OKM
        val c = LibsodiumX3DH.hkdfSha256L32(ikm, salt, "phantom-other".encodeToByteArray())
        assertNotEquals(a.toList(), c.toList())
    }
}
