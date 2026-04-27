package phantom.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Instrumented tests for [LibsodiumDoubleRatchet].
 *
 * All tests perform a real X3DH handshake first, then exercise the ratchet.
 * Runs on Android runtime where libsodium.so is loaded from the test APK.
 *
 * Invoke with: ./gradlew :shared:core:crypto:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class LibsodiumDoubleRatchetTest {

    private val x3dh = LibsodiumX3DH()
    private val ratchet = LibsodiumDoubleRatchet()

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

        val (aliceState1, aliceMsg) = ratchet.encrypt(aliceState0, alicePlaintext)
        val (bobState1, aliceDecrypted) = ratchet.decrypt(bobState0, aliceMsg)
        assertContentEquals(alicePlaintext, aliceDecrypted, "Bob must decrypt Alice's message")

        val (_, bobMsg) = ratchet.encrypt(bobState1, bobPlaintext)
        val (_, bobDecrypted) = ratchet.decrypt(aliceState1, bobMsg)
        assertContentEquals(bobPlaintext, bobDecrypted, "Alice must decrypt Bob's reply")
    }

    @Test
    fun ciphertext_doesNotContainPlaintext() = runTest {
        val (aliceState0, _) = sharedSession()
        val plaintext = "secret message".encodeToByteArray()

        val (_, encryptedMessage) = ratchet.encrypt(aliceState0, plaintext)

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

        val tamperedCiphertext = encryptedMessage.ciphertext.copyOf().also {
            it[0] = (it[0].toInt() xor 0xFF).toByte()
        }
        val tampered = encryptedMessage.copy(ciphertext = tamperedCiphertext)

        assertFailsWith<IllegalArgumentException>(
            message = "Decrypting tampered ciphertext must throw IllegalArgumentException",
        ) {
            ratchet.decrypt(bobState0, tampered)
        }
    }
}
