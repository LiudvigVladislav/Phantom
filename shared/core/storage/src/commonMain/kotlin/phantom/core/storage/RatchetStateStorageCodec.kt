// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * On-disk encoding for the Double Ratchet session-state TEXT column in
 * the `ratchet_state` table.
 *
 * Two formats coexist behind a single column:
 *
 *  - **Wrapped (current).** `"rs1:" + Base64( cipher.wrap( jsonUtf8 ) )`.
 *    The `rs1:` prefix distinguishes ratchet-state blobs from the
 *    prekey-private-key format (`v1:`) used by [PrivateKeyStorageCodec].
 *  - **Legacy (pre-H-1).** Plain JSON, no prefix. Older rows are decoded
 *    as-is and re-wrapped the next time the state is persisted.
 *
 * The codec is stateless and defined in commonMain so non-Android
 * targets compile against the same interface. The only platform-
 * dependent piece is the cipher itself.
 */
@OptIn(ExperimentalEncodingApi::class)
object RatchetStateStorageCodec {

    private const val PREFIX_RS1 = "rs1:"

    /**
     * Encode a plaintext JSON blob for storage. Always produces the
     * current wrapped format — legacy rows are re-wrapped on next write.
     */
    fun encodeForStorage(jsonBlob: String, cipher: KeystoreBlobCipher): String {
        val wrapped = cipher.wrap(jsonBlob.encodeToByteArray())
        return PREFIX_RS1 + Base64.encode(wrapped)
    }

    /**
     * Decode a value read from storage and return the plaintext JSON.
     * Accepts both the current wrapped format and legacy plaintext JSON.
     * Throws if the blob carries the `rs1:` prefix but the cipher
     * rejects it (tamper / wrong key).
     */
    fun decodeFromStorage(stored: String, cipher: KeystoreBlobCipher): String {
        if (stored.startsWith(PREFIX_RS1)) {
            val wrapped = Base64.decode(stored.removePrefix(PREFIX_RS1))
            return cipher.unwrap(wrapped).decodeToString()
        }
        // Legacy: plaintext JSON written before H-1. Return unchanged;
        // the next upsert will re-wrap under the current cipher.
        return stored
    }
}
