// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * On-disk encoding for private-key TEXT columns in the local prekey
 * tables ([phantom.core.storage.SqlDelightLocalSignedPreKeyRepository],
 * [phantom.core.storage.SqlDelightLocalOneTimePreKeyRepository]).
 *
 * Two formats coexist behind a single column type:
 *
 *  - **Wrapped (current).** `"v1:" + Base64( cipher.wrap( hexBytes ) )`.
 *    The `v1:` prefix is a forward-compatibility marker; future formats
 *    pick a different prefix and the decoder branches on it.
 *  - **Legacy (pre-F22).** Raw lowercase hex of the private-key bytes,
 *    no prefix. Older databases were written this way; the decoder
 *    falls through to a hex parse when no recognised prefix is present.
 *
 * The migration is lazy by design (see `ADR-023`): there is no
 * one-shot rewrite pass over the table. Each row migrates the next
 * time it is `upsert`-ed, which for OPKs happens on every
 * `maybeReplenishOneTimePreKeys` cycle (relay-side bundle replacement
 * always rewrites the entire local pool wholesale) and for the SPK on
 * every `maybeRotateSignedPreKey` (weekly). A device that only ever
 * reads its bundle from the relay's perspective will keep its existing
 * rows in legacy form, which is fine — both formats decode to the
 * same plaintext.
 *
 * The codec is a stateless companion to [KeystoreBlobCipher] and is
 * defined in commonMain so the same wrap/unwrap contract holds across
 * all platform repositories. The only platform-dependent piece is the
 * cipher itself.
 */
@OptIn(ExperimentalEncodingApi::class)
object PrivateKeyStorageCodec {

    /** Prefix for the current wrapped format. Change it to bump the format. */
    private const val PREFIX_V1 = "v1:"

    /**
     * Encode [plaintextHex] (the hex string the rest of the codebase
     * speaks) for storage. Always produces the current wrapped format
     * — there is no path that writes legacy hex from this codec.
     */
    fun encodeForStorage(plaintextHex: String, cipher: KeystoreBlobCipher): String {
        val plaintextBytes = hexToBytes(plaintextHex)
        val wrapped = cipher.wrap(plaintextBytes)
        return PREFIX_V1 + Base64.encode(wrapped)
    }

    /**
     * Decode a value read from storage and return the plaintext hex
     * the rest of the codebase expects. Accepts both the current
     * wrapped format and the legacy raw-hex format. On a wrapped
     * value, [cipher].unwrap is called; on a legacy value, the cipher
     * is not invoked.
     *
     * Throws if the value carries a recognised prefix but the
     * underlying cipher rejects the blob (tamper / wrong key) — that
     * is the authenticated-encryption contract from
     * [KeystoreBlobCipher.unwrap] surfacing.
     */
    fun decodeFromStorage(stored: String, cipher: KeystoreBlobCipher): String {
        if (stored.startsWith(PREFIX_V1)) {
            val wrapped = Base64.decode(stored.substring(PREFIX_V1.length))
            return bytesToHex(cipher.unwrap(wrapped))
        }
        // Legacy: the column was written before F22 landed. Return
        // the bytes unchanged — they are already plaintext hex.
        return stored
    }

    // ── tiny internal hex codec — keeps this file dependency-free ────────────
    // (the rest of the codebase has its own toHex / parseHex helpers but they
    // live in modules that we don't want to pull into :shared:core:storage)

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have even length, got ${hex.length}" }
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val hi = hexNibble(hex[i])
            val lo = hexNibble(hex[i + 1])
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }

    private fun hexNibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("not a hex digit: $c")
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
