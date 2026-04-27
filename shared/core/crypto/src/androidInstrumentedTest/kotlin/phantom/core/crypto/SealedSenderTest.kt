package phantom.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.box.Box
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class SealedSenderTest {

    private fun initLibsodium() {
        // LibsodiumInitializer is synchronous on JVM/Android test runs.
        if (!LibsodiumInitializer.isInitialized()) {
            LibsodiumInitializer.initializeWithCallback {}
        }
    }

    @Test
    fun sealUnseal_roundTrip() {
        initLibsodium()

        val aliceKeypair = Box.keypair()
        val bobKeypair   = Box.keypair()

        val alicePubHex = aliceKeypair.publicKey.toByteArray()
            .joinToString("") { "%02x".format(it) }
        val bobPubBytes = bobKeypair.publicKey.toByteArray()
        val bobPrivBytes = bobKeypair.secretKey.toByteArray()

        @OptIn(ExperimentalUnsignedTypes::class)
        val sealed = SealedSender.seal(
            fromPubKeyHex = alicePubHex,
            toPublicKeyBytes = bobPubBytes,
        )

        @OptIn(ExperimentalUnsignedTypes::class)
        val recovered = SealedSender.unseal(
            sealedBytes = sealed,
            myPrivateKeyBytes = bobPrivBytes,
        )

        assertNotNull(recovered)
        assertEquals(alicePubHex, recovered)
    }

    @Test
    fun unseal_wrongPrivateKey_returnsNull() {
        initLibsodium()

        val aliceKeypair = Box.keypair()
        val bobKeypair   = Box.keypair()
        val eveKeypair   = Box.keypair()

        val alicePubHex = aliceKeypair.publicKey.toByteArray()
            .joinToString("") { "%02x".format(it) }
        val bobPubBytes  = bobKeypair.publicKey.toByteArray()
        val evePrivBytes = eveKeypair.secretKey.toByteArray()

        @OptIn(ExperimentalUnsignedTypes::class)
        val sealed = SealedSender.seal(
            fromPubKeyHex = alicePubHex,
            toPublicKeyBytes = bobPubBytes,
        )

        @OptIn(ExperimentalUnsignedTypes::class)
        val result = SealedSender.unseal(
            sealedBytes = sealed,
            myPrivateKeyBytes = evePrivBytes,
        )

        assertNull(result)
    }

    @Test
    fun unseal_truncatedBlob_returnsNull() {
        initLibsodium()

        @OptIn(ExperimentalUnsignedTypes::class)
        val result = SealedSender.unseal(
            sealedBytes = ByteArray(10),
            myPrivateKeyBytes = ByteArray(32),
        )

        assertNull(result)
    }
}
