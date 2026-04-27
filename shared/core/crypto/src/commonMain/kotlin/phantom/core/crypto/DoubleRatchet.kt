// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

/**
 * Signal Double Ratchet algorithm.
 *
 * All operations are pure: they consume a [RatchetState] and return a new
 * [RatchetState]. No mutable state is held inside the implementation.
 * Callers are responsible for persisting the returned state.
 *
 * Alpha-0 constraints:
 * - No skipped-message-key cache — out-of-order messages will fail to decrypt.
 * - No header encryption.
 * Both are tracked as post-Alpha-0 work.
 *
 * Reference: https://signal.org/docs/specifications/doubleratchet/
 */
interface DoubleRatchet {

    /**
     * Encrypts [plaintext] using the current sending chain of [state].
     *
     * Steps:
     * 1. Derive a [MessageKey] from the current sendingChainKey.
     * 2. Advance the sendingChainKey (KDF step).
     * 3. Encrypt with XSalsa20-Poly1305 using a fresh random nonce.
     * 4. Return the updated state and the [EncryptedMessage].
     */
    fun encrypt(state: RatchetState, plaintext: ByteArray): Pair<RatchetState, EncryptedMessage>

    /**
     * Decrypts [message] using the current receiving chain of [state].
     *
     * Steps:
     * 1. If [message.ratchetPublicKey] differs from the stored
     *    receivingRatchetPublicKey, perform a DH ratchet step first:
     *    compute a new shared secret, update rootKey and chain keys.
     * 2. Derive a [MessageKey] from the current receivingChainKey.
     * 3. Advance the receivingChainKey (KDF step).
     * 4. Decrypt the ciphertext; throw [IllegalArgumentException] on MAC failure.
     * 5. Return the updated state and the plaintext.
     */
    fun decrypt(state: RatchetState, message: EncryptedMessage): Pair<RatchetState, ByteArray>
}
