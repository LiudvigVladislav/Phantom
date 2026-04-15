package phantom.core.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [LibsodiumX3DH].
 *
 * Each test initializes LibsodiumInitializer because the native backing must be
 * loaded before any call. runTest allows the suspend initializer.
 *
 * VERIFY AT FIRST BUILD:
 * - LibsodiumInitializer.initialize() — if it is not a suspend function in the
 *   installed version, remove the suspend call site or use the callback form:
 *   LibsodiumInitializer.initializeWithCallback { }.
 */
class LibsodiumX3DHTest {

    private val x3dh = LibsodiumX3DH()

    @Test
    fun generateDhKeyPair_returnsNonEmptyKeys() = runTest {
        LibsodiumInitializer.initialize()

        val kp = x3dh.generateDhKeyPair()

        assertTrue(kp.publicKey.bytes.isNotEmpty(), "Public key must not be empty")
        assertTrue(kp.privateKey.bytes.isNotEmpty(), "Private key must not be empty")
        assertEquals(32, kp.publicKey.bytes.size, "Curve25519 public key must be 32 bytes")
        assertEquals(32, kp.privateKey.bytes.size, "Curve25519 private key must be 32 bytes")
    }

    @Test
    fun generateDhKeyPair_twoCallsReturnDifferentKeys() = runTest {
        LibsodiumInitializer.initialize()

        val kp1 = x3dh.generateDhKeyPair()
        val kp2 = x3dh.generateDhKeyPair()

        assertNotEquals(
            kp1.publicKey.bytes.toList(),
            kp2.publicKey.bytes.toList(),
            "Two freshly generated keypairs must have different public keys",
        )
    }

    /**
     * Full X3DH handshake: Alice initiates, Bob responds.
     * Both parties must derive the same rootKey.
     *
     * The ephemeral keypair is generated externally so its public key can be
     * forwarded to Bob via [LibsodiumX3DH.initiatorHandshakeWithEphemeral].
     * In production the ephemeral public key travels in the initial message header.
     */
    @Test
    fun fullHandshake_aliceAndBobDeriveTheSameRootKey() = runTest {
        LibsodiumInitializer.initialize()

        val aliceIdentity = x3dh.generateDhKeyPair()
        val bobIdentity = x3dh.generateDhKeyPair()
        val bobSignedPreKey = x3dh.generateDhKeyPair()
        val ephemeral = x3dh.generateDhKeyPair()

        val aliceState = x3dh.initiatorHandshakeWithEphemeral(
            initiatorIdentityKeyPair = aliceIdentity,
            recipientIdentityPublicKey = bobIdentity.publicKey,
            recipientSignedPreKey = bobSignedPreKey.publicKey,
            ephemeralKeyPair = ephemeral,
        )

        val bobState = x3dh.recipientHandshake(
            recipientIdentityKeyPair = bobIdentity,
            recipientSignedPreKeyPair = bobSignedPreKey,
            initiatorIdentityPublicKey = aliceIdentity.publicKey,
            initiatorEphemeralPublicKey = ephemeral.publicKey,
        )

        assertEquals(
            aliceState.rootKey.toList(),
            bobState.rootKey.toList(),
            "Alice and Bob must derive the same root key after X3DH handshake",
        )
    }

    @Test
    fun handshakeRootKey_isNotAllZeroes() = runTest {
        LibsodiumInitializer.initialize()

        val aliceIdentity = x3dh.generateDhKeyPair()
        val bobIdentity = x3dh.generateDhKeyPair()
        val bobSignedPreKey = x3dh.generateDhKeyPair()
        val ephemeral = x3dh.generateDhKeyPair()

        val aliceState = x3dh.initiatorHandshakeWithEphemeral(
            initiatorIdentityKeyPair = aliceIdentity,
            recipientIdentityPublicKey = bobIdentity.publicKey,
            recipientSignedPreKey = bobSignedPreKey.publicKey,
            ephemeralKeyPair = ephemeral,
        )

        assertTrue(
            aliceState.rootKey.any { it != 0.toByte() },
            "Root key must not be all zeroes",
        )
    }
}
