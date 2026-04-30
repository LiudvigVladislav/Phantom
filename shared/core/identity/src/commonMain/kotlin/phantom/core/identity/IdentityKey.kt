// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.identity

import kotlinx.serialization.Serializable

/**
 * X25519 public key — used as the primary identity that other users see
 * (QR codes, contacts list) and as one DH input in the X3DH handshake.
 * 32 bytes.
 */
@JvmInline
value class PublicKey(val bytes: ByteArray)

/**
 * X25519 private key. 32 bytes.
 */
@JvmInline
value class PrivateKey(val bytes: ByteArray)

/**
 * Ed25519 verifying key. Used to verify the SignedPreKey signature on a
 * peer's published prekey bundle. Distinct from [PublicKey] — Ed25519 and
 * X25519 are different cryptographic objects, see ADR-009 supplement
 * "Signing key distribution". 32 bytes.
 */
@JvmInline
value class SigningPublicKey(val bytes: ByteArray)

/**
 * Ed25519 secret key — libsodium stores secret + public concatenated, so
 * the underlying byte array is 64 bytes (32 seed + 32 public). Callers
 * should treat the bytes as opaque; only [LibsodiumIdentityCrypto] reads
 * them.
 */
@JvmInline
value class SigningPrivateKey(val bytes: ByteArray)

data class IdentityKeyPair(
    val publicKey: PublicKey,
    val privateKey: PrivateKey,
)

/**
 * Long-term Ed25519 signing keypair attached to every Alpha 2+ identity.
 * Generated alongside the X25519 [IdentityKeyPair] at onboarding (or
 * backfilled at Alpha 1 → Alpha 2 migration time, see [IdentityManager]).
 *
 * Used only to sign published [phantom.core.crypto.SignedPreKeyPublicBundle]s
 * so peers can verify the bundle came from the right identity. Never used
 * for DH operations (that is the role of the X25519 [IdentityKeyPair]).
 */
data class IdentitySigningKeyPair(
    val publicKey: SigningPublicKey,
    val privateKey: SigningPrivateKey,
)

/**
 * Persistent identity record.
 *
 * The `publicKeyHex` + `dhPrivateKeyHex` pair is the X25519 routing identity
 * — what other users see, what QR codes encode, what the relay routes
 * envelopes by. Unchanged in shape from Alpha 1.
 *
 * The `signingPublicKeyHex` + `signingPrivateKeyHex` pair is the Ed25519
 * signing identity introduced in Alpha 2 (ADR-009 supplement). Both fields
 * are nullable so the same struct can read an Alpha 1 record off disk
 * without a deserialization break — `null` means "this record predates the
 * Alpha 2 migration; the signing keypair has not been backfilled yet."
 *
 * Invariant: either both signing fields are populated together or both are
 * null. The atomic update path in [IdentityManager.backfillSigningKeyPair]
 * preserves this.
 */
@Serializable
data class IdentityRecord(
    val id: String,
    val username: String,
    val publicKeyHex: String,
    val dhPrivateKeyHex: String,
    val createdAt: Long,
    val signingPublicKeyHex: String? = null,
    val signingPrivateKeyHex: String? = null,
) {
    /**
     * `true` when the Ed25519 signing keypair has not been backfilled.
     * Drives the migration trigger in the launch path: any record where
     * this is true forces the MigrationScreen on next foreground.
     */
    val needsSigningKeyBackfill: Boolean
        get() = signingPublicKeyHex.isNullOrEmpty() ||
                signingPrivateKeyHex.isNullOrEmpty()
}
