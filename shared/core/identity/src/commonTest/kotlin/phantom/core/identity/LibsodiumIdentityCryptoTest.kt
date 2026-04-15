package phantom.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibsodiumIdentityCryptoTest {

    private val crypto = LibsodiumIdentityCrypto()

    @Test
    fun generateKeyPair_returnsNonEmptyKeys() {
        val kp = crypto.generateKeyPair()
        assertTrue(kp.publicKey.bytes.isNotEmpty())
        assertTrue(kp.privateKey.bytes.isNotEmpty())
    }

    @Test
    fun signAndVerify_roundTrip() {
        val kp = crypto.generateKeyPair()
        val message = "hello phantom".encodeToByteArray()
        val signature = crypto.sign(message, kp.privateKey)
        assertTrue(crypto.verify(message, signature, kp.publicKey))
    }

    @Test
    fun verify_failsWithWrongPublicKey() {
        val kp = crypto.generateKeyPair()
        val wrongKp = crypto.generateKeyPair()
        val message = "hello phantom".encodeToByteArray()
        val signature = crypto.sign(message, kp.privateKey)
        assertFalse(crypto.verify(message, signature, wrongKp.publicKey))
    }

    @Test
    fun publicKeyHex_roundTrip() {
        val kp = crypto.generateKeyPair()
        val hex = crypto.publicKeyToHex(kp.publicKey)
        val recovered = crypto.hexToPublicKey(hex)
        assertEquals(hex, crypto.publicKeyToHex(recovered))
    }
}
