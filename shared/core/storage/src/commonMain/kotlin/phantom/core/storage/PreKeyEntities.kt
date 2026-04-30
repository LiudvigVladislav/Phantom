// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

/**
 * Local SignedPreKey state — the user's own current SPK plus an optional
 * previous-generation kept for the 14-day rotation retention window.
 *
 * One row per device (singleton). Mirrors the relay's StoredPreKeyState
 * shape so a publish round-trip is just `current` fields → wire format.
 *
 * `private*` fields are deliberately exposed: the bootstrap path needs
 * the X25519 secret to run `x3dh.recipientHandshake4DH` when a peer's
 * first message references our SPK. PR E (Keystore-wrap) will introduce
 * a wrapper type that decrypts on access; for now they're plain hex
 * (SQLCipher-encrypted at rest on Android, same as identity private key).
 */
data class LocalSignedPreKeyEntity(
    val keyId: Long,
    val publicKeyHex: String,
    val privateKeyHex: String,
    val createdAtMs: Long,
    /**
     * Ed25519 detached signature over
     *   "phantom-spk-v1" || publicKey || createdAtMs (8-byte big-endian)
     * produced by the identity's signing keypair. Cached so a re-publish
     * does not require re-signing the same SPK.
     */
    val signatureHex: String,
    val previous: PreviousSignedPreKey? = null,
) {
    /**
     * Previous-generation SPK, retained for `SPK_PREVIOUS_RETENTION_DAYS`
     * after rotation. Used by the recipient bootstrap path when a peer
     * initiated a session against an SPK we just rotated — we still hold
     * the matching private key for the grace period.
     */
    data class PreviousSignedPreKey(
        val keyId: Long,
        val publicKeyHex: String,
        val privateKeyHex: String,
        val signatureHex: String,
        val createdAtMs: Long,
        val retiredAtMs: Long,
    )
}

/**
 * Local OneTimePreKey — one row per OPK still in the user's pool.
 *
 * `keyIdHex` is the 16-byte server-assigned ID (32 hex chars) the relay
 * returned at publish time. The recipient bootstrap path looks up the
 * matching private half by this ID when a peer's `x3dhInit.opkKeyIdHex`
 * references it; on successful 4-DH the entry is deleted (single-use,
 * which is the whole point of OPKs).
 */
data class LocalOneTimePreKeyEntity(
    val keyIdHex: String,
    val publicKeyHex: String,
    val privateKeyHex: String,
    val uploadedAtMs: Long,
)
