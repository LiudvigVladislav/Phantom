package phantom.core.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Tests for [LibsodiumDoubleRatchet].
 *
 * All tests perform a real X3DH handshake first to obtain a shared [RatchetState],
 * then exercise the ratchet. This mirrors the actual usage flow and avoids
 * constructing RatchetState manually (which would require exact byte-length knowledge
 * of internal keys that may change).
 *
 * VERIFY AT FIRST BUILD:
 * - LibsodiumInitializer.initialize() — see note in LibsodiumX3DHTest.
 * - SecretBox.openEasy throws a typed exception on MAC failure; the catch clause
 *   in LibsodiumDoubleRatchet maps it to IllegalArgumentException. Confirm the
 *   exception type thrown by the library and adjust the catch if needed.
 */
class LibsodiumDoubleRatchetTest {

    private val x3dh = LibsodiumX3DH()
    private val ratchet = LibsodiumDoubleRatchet()

    // --- Shared session setup helpers ---

    private suspend fun sharedSession(): Pair<RatchetState, RatchetState> {
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
        return Pair(aliceState, bobState)
    }

    // --- Tests ---

    @Test
    fun singleMessage_aliceEncryptsBobDecrypts_plaintextMatches() = runTest {
        val (aliceState0, bobState0) = sharedSession()
        val plaintext = "Hello, Bob!".encodeToByteArray()

        val (_, encryptedMessage) = ratchet.encrypt(aliceState0, plaintext)
        val (_, decrypted) = ratchet.decrypt(bobState0, encryptedMessage)

        assertContentEquals(plaintext, decrypted, "Decrypted plaintext must match original")
    }

    @Test
    fun threeSequentialMessages_allDecryptCorrectly() = runTest {
        val (aliceState0, bobState0) = sharedSession()
        val messages = listOf("msg1", "msg2", "msg3").map { it.encodeToByteArray() }

        var aliceState = aliceState0
        val encrypted = messages.map { plaintext ->
            val (nextState, msg) = ratchet.encrypt(aliceState, plaintext)
            aliceState = nextState
            msg
        }

        var bobState = bobState0
        val decrypted = encrypted.map { msg ->
            val (nextState, plaintext) = ratchet.decrypt(bobState, msg)
            bobState = nextState
            plaintext
        }

        messages.zip(decrypted).forEachIndexed { index, (expected, actual) ->
            assertContentEquals(expected, actual, "Message $index plaintext must match")
        }
    }

    @Test
    fun bidirectionalRatchet_aliceAndBobBothSend() = runTest {
        val (aliceState0, bobState0) = sharedSession()

        val alicePlaintext = "Hello from Alice".encodeToByteArray()
        val bobPlaintext = "Hello from Bob".encodeToByteArray()

        // Alice sends to Bob
        val (aliceState1, aliceMsg) = ratchet.encrypt(aliceState0, alicePlaintext)
        val (bobState1, aliceDecrypted) = ratchet.decrypt(bobState0, aliceMsg)
        assertContentEquals(alicePlaintext, aliceDecrypted, "Bob must decrypt Alice's message")

        // Bob replies to Alice
        val (_, bobMsg) = ratchet.encrypt(bobState1, bobPlaintext)
        val (_, bobDecrypted) = ratchet.decrypt(aliceState1, bobMsg)
        assertContentEquals(bobPlaintext, bobDecrypted, "Alice must decrypt Bob's reply")
    }

    @Test
    fun ciphertext_doesNotContainPlaintext() = runTest {
        val (aliceState0, _) = sharedSession()
        val plaintext = "secret message".encodeToByteArray()

        val (_, encryptedMessage) = ratchet.encrypt(aliceState0, plaintext)

        // The ciphertext should not contain the raw plaintext bytes as a subsequence.
        // This is a sanity check, not a cryptographic proof.
        val ciphertextStr = encryptedMessage.ciphertext.decodeToString()
        assertFalse(
            ciphertextStr.contains("secret message"),
            "Ciphertext must not contain plaintext in cleartext form",
        )
    }

    @Test
    fun tampered_ciphertext_throwsOnDecrypt() = runTest {
        val (aliceState0, bobState0) = sharedSession()
        val plaintext = "tamper test".encodeToByteArray()

        val (_, encryptedMessage) = ratchet.encrypt(aliceState0, plaintext)

        // Flip a byte in the ciphertext to simulate tampering.
        val tamperedCiphertext = encryptedMessage.ciphertext.copyOf().also { it[0] = it[0].xor(0xFF.toByte()) }
        val tampered = encryptedMessage.copy(ciphertext = tamperedCiphertext)

        assertFailsWith<IllegalArgumentException>(
            message = "Decrypting tampered ciphertext must throw IllegalArgumentException",
        ) {
            ratchet.decrypt(bobState0, tampered)
        }
    }
}
