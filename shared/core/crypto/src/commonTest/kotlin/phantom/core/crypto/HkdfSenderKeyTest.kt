// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalUnsignedTypes::class)
class HkdfSenderKeyTest {

    @Test
    fun hkdf_shortSalt_matchesRfc5869CaseOne() = runTest {
        LibsodiumInitializer.initialize()

        val okm = Hkdf.sha256L32(
            ikm = ByteArray(22) { 0x0b },
            salt = "000102030405060708090a0b0c".hexToBytes(),
            info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes(),
        )

        assertContentEquals(
            "3cb25f25faacd57a90434f64d0362f2a".hexToBytes() +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf".hexToBytes(),
            okm,
        )
    }

    @Test
    fun hkdf_rejectsSaltWiderThanTheNativeHmacKey() = runTest {
        LibsodiumInitializer.initialize()

        assertFailsWith<IllegalArgumentException> {
            Hkdf.sha256L32(ByteArray(32), ByteArray(33), byteArrayOf(1))
        }
    }

    @Test
    fun hmac_rejectsAKeyThatTheNativePrimitiveWouldOverread() = runTest {
        LibsodiumInitializer.initialize()

        assertFailsWith<IllegalArgumentException> {
            Hmac.sha256(ByteArray(31), byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun senderKey_sameState_roundTripsAndAdvancesDeterministically() = runTest {
        LibsodiumInitializer.initialize()
        val bundle = SenderKey.Bundle(
            chainKeyHex = (0 until 32).joinToString("") { "%02x".format(it) },
            iteration = 7,
        )
        val plaintext = "group audio chunk".encodeToByteArray()

        val first = SenderKey.encrypt(plaintext, bundle)
        val second = SenderKey.encrypt("different payload".encodeToByteArray(), bundle)
        val opened = SenderKey.decrypt(first.second, bundle)

        assertEquals(first.first, second.first, "chain advance must not depend on plaintext or nonce")
        assertNotNull(opened)
        assertEquals(first.first, opened.first)
        assertContentEquals(plaintext, opened.second)
        assertNull(
            SenderKey.decrypt(first.second, bundle.copy(iteration = bundle.iteration + 1)),
            "a different SenderKey position must fail MAC verification",
        )
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
