// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

/**
 * Authenticated-encryption boundary for opaque secret blobs that the
 * client must persist locally but does not want sitting plaintext in
 * SQLite (even though the database file itself is SQLCipher-encrypted —
 * see [DatabasePassphraseManager] on Android).
 *
 * Contract:
 *  - [wrap] takes raw plaintext bytes and returns a self-contained
 *    ciphertext blob that includes whatever IV / nonce / tag the
 *    underlying primitive needs. The returned bytes can be stored
 *    as-is and round-tripped through [unwrap] to recover the original
 *    plaintext byte-for-byte.
 *  - [unwrap] is total on the wrapped output of [wrap] from the SAME
 *    cipher instance (and the same underlying master key). Tampering,
 *    truncation, or wrong key MUST surface as an exception — the
 *    contract is authenticated encryption, not best-effort decoding.
 *  - Concurrency: implementations are safe to call from multiple
 *    coroutines. The Android Keystore implementation is internally
 *    serialised by the JCE Cipher object's own state; callers should
 *    not assume any particular ordering between concurrent wrap calls.
 *
 * Defined in commonMain so non-Android targets (JVM unit tests today,
 * iOS / desktop / web tomorrow) compile against the same interface.
 * The Android implementation is wired in [phantom.core.storage]
 * `AndroidKeystoreBlobCipher`; tests use [IdentityCipher] which is a
 * pass-through with the same byte-for-byte round-trip guarantee.
 *
 * Threat model (see [docs/adr/ADR-023-Local-Prekey-Keystore-Wrap.md](../../../../../docs/adr/ADR-023-Local-Prekey-Keystore-Wrap.md)):
 * this interface closes the gap between SQLCipher's at-rest disk
 * protection (which fails the moment the database key is in process
 * memory) and a memory-imaging or decrypted-export attacker. It does
 * not defend against in-process code execution — an attacker who can
 * call [unwrap] can read the plaintext.
 */
interface KeystoreBlobCipher {
    /**
     * Authenticated-encrypt [plaintext] under the cipher's master key.
     * The returned blob is opaque to callers; [unwrap] is the only
     * supported way to recover the original bytes.
     *
     * Empty plaintexts are valid input. The output is still a
     * non-empty blob (it carries IV + tag).
     */
    fun wrap(plaintext: ByteArray): ByteArray

    /**
     * Inverse of [wrap]. Throws on tampering / wrong key / corrupt
     * input — the implementation must not silently return garbage on
     * a failed authentication tag check.
     */
    fun unwrap(wrappedBlob: ByteArray): ByteArray
}

/**
 * Test-grade pass-through that returns its input unchanged. Defaulted
 * into the prekey repositories so the existing in-memory test rigs
 * (which do not load Android Keystore) keep working without per-test
 * cipher injection.
 *
 * Production code on Android wires the real
 * `AndroidKeystoreBlobCipher` instead — see `AppContainer`.
 *
 * NEVER use this in production: it provides zero confidentiality.
 * Returning it from a `createKeystoreBlobCipher()` factory in shipping
 * code is a security regression.
 */
object IdentityCipher : KeystoreBlobCipher {
    override fun wrap(plaintext: ByteArray): ByteArray = plaintext
    override fun unwrap(wrappedBlob: ByteArray): ByteArray = wrappedBlob
}
