// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.secretbox.SecretBox
import com.ionspin.kotlin.crypto.util.LibsodiumRandom

object SenderKey {
    private val DOMAIN_MSG   = "phantom_sk_msg_v1".encodeToByteArray().toUByteArray()
    private val DOMAIN_CHAIN = "phantom_sk_chain_v1".encodeToByteArray().toUByteArray()

    data class Bundle(
        val chainKeyHex: String,
        val iteration: Int,
    )

    fun generate(): Bundle {
        val chainKey = LibsodiumRandom.buf(32)
        return Bundle(
            chainKeyHex = chainKey.toHex(),
            iteration   = 0,
        )
    }

    private fun advance(bundle: Bundle): Pair<Bundle, UByteArray> {
        val chainKey  = bundle.chainKeyHex.fromHex()
        val msgKey    = Hash.sha256(chainKey + DOMAIN_MSG)
        val newChain  = Hash.sha256(chainKey + DOMAIN_CHAIN)
        val newBundle = bundle.copy(chainKeyHex = newChain.toHex(), iteration = bundle.iteration + 1)
        return newBundle to msgKey
    }

    fun encrypt(plaintext: ByteArray, bundle: Bundle): Pair<Bundle, ByteArray> {
        val (newBundle, msgKey) = advance(bundle)
        val nonce  = LibsodiumRandom.buf(24)
        val cipher = SecretBox.easy(plaintext.toUByteArray(), nonce, msgKey)
        return newBundle to (nonce.toByteArray() + cipher.toByteArray())
    }

    fun decrypt(ciphertext: ByteArray, bundle: Bundle): Pair<Bundle, ByteArray>? {
        if (ciphertext.size < 24) return null
        val (newBundle, msgKey) = advance(bundle)
        val nonce  = ciphertext.copyOfRange(0, 24).toUByteArray()
        val cipher = ciphertext.copyOfRange(24, ciphertext.size).toUByteArray()
        val plain  = runCatching { SecretBox.openEasy(cipher, nonce, msgKey) }.getOrNull() ?: return null
        return newBundle to plain.toByteArray()
    }

    private fun UByteArray.toHex() = joinToString("") { "%02x".format(it.toByte()) }
    private fun String.fromHex()   = chunked(2).map { it.toInt(16).toUByte() }.toUByteArray()
    private operator fun UByteArray.plus(other: UByteArray): UByteArray {
        val result = UByteArray(size + other.size)
        copyInto(result); other.copyInto(result, size); return result
    }
}
