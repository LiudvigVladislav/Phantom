// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import com.ionspin.kotlin.crypto.secretbox.SecretBox
import com.ionspin.kotlin.crypto.util.LibsodiumRandom

/**
 * Group SenderKey ratchet — derives per-message symmetric keys from a
 * forward-only chain key.
 *
 * F3 (audit 2026-04-29): KDF was bare `SHA256(chainKey || tag)`, which is a
 * non-standard construction that fails any external review. Replaced with
 * RFC 5869 HKDF-SHA256 (`Hkdf.sha256L32`), with the iteration counter bound
 * into the `salt` field so adjacent positions in the chain produce
 * cryptographically independent outputs even when the tag is identical.
 *
 * The `_v2` suffix on the info strings makes the protocol version explicit and
 * rules out cross-version replay should a v1 SenderKey ever be re-introduced
 * during migration.
 */
object SenderKey {
    private val DOMAIN_MSG   = "phantom_sk_msg_v2".encodeToByteArray()
    private val DOMAIN_CHAIN = "phantom_sk_chain_v2".encodeToByteArray()

    data class Bundle(
        val chainKeyHex: String,
        val iteration: Int,
    )

    fun generate(): Bundle {
        val chainKey = LibsodiumRandom.buf(32)
        return Bundle(
            chainKeyHex = chainKey.toByteArray().toHexString(),
            iteration   = 0,
        )
    }

    /**
     * Derive `(msgKey_i, chainKey_{i+1})` from `(chainKey_i, i)` via HKDF-SHA256.
     * The iteration counter is the HKDF salt, so the same chainKey at different
     * positions produces unrelated outputs — and an attacker who learns one
     * msgKey gains nothing about chainKey or any other position.
     */
    private fun advance(bundle: Bundle): Pair<Bundle, ByteArray> {
        val chainKey = bundle.chainKeyHex.fromHexToByteArray()
        val iterationSalt = bundle.iteration.toBigEndianBytes()
        val msgKey   = Hkdf.sha256L32(ikm = chainKey, salt = iterationSalt, info = DOMAIN_MSG)
        val newChain = Hkdf.sha256L32(ikm = chainKey, salt = iterationSalt, info = DOMAIN_CHAIN)
        chainKey.zeroize()
        val newBundle = bundle.copy(
            chainKeyHex = newChain.toHexString(),
            iteration   = bundle.iteration + 1,
        )
        newChain.zeroize()
        return newBundle to msgKey
    }

    fun encrypt(plaintext: ByteArray, bundle: Bundle): Pair<Bundle, ByteArray> {
        val (newBundle, msgKey) = advance(bundle)
        val nonce  = LibsodiumRandom.buf(24)
        val cipher = SecretBox.easy(plaintext.toUByteArray(), nonce, msgKey.toUByteArray())
        msgKey.zeroize()
        return newBundle to (nonce.toByteArray() + cipher.toByteArray())
    }

    fun decrypt(ciphertext: ByteArray, bundle: Bundle): Pair<Bundle, ByteArray>? {
        if (ciphertext.size < 24) return null
        val (newBundle, msgKey) = advance(bundle)
        val nonce  = ciphertext.copyOfRange(0, 24).toUByteArray()
        val cipher = ciphertext.copyOfRange(24, ciphertext.size).toUByteArray()
        val plain  = runCatching { SecretBox.openEasy(cipher, nonce, msgKey.toUByteArray()) }.getOrNull()
        msgKey.zeroize()
        if (plain == null) return null
        return newBundle to plain.toByteArray()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun String.fromHexToByteArray(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun Int.toBigEndianBytes(): ByteArray = byteArrayOf(
        (this ushr 24 and 0xFF).toByte(),
        (this ushr 16 and 0xFF).toByte(),
        (this ushr 8  and 0xFF).toByte(),
        (this         and 0xFF).toByte(),
    )
}
