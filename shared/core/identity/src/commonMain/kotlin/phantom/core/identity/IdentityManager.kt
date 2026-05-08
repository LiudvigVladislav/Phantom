// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.identity

import com.benasher44.uuid.uuid4
import kotlinx.datetime.Clock

class IdentityManager(
    private val crypto: IdentityCrypto,
    private val repository: IdentityRepository,
) {
    /**
     * Returns the loaded or freshly-created identity together with its
     * X25519 DH keypair. New identities (Alpha 2+) ALSO have the Ed25519
     * signing keypair generated and persisted in the same write.
     *
     * Callers that need the Ed25519 keypair (PreKey publish path,
     * SignedPreKeySigner) should call [loadSigningKeyPair] separately —
     * it returns null when the record needs backfill, signalling the
     * MigrationScreen path.
     */
    suspend fun createOrLoad(username: String): Pair<IdentityRecord, IdentityKeyPair> {
        val existing = repository.loadIdentity()
        if (existing != null) {
            // Reconstruct key pair from stored private key hex. Note: the
            // Ed25519 signing keypair may or may not be present on this
            // record (Alpha 1 records lack it). Caller checks
            // existing.needsSigningKeyBackfill to detect that case.
            val keyPair = IdentityKeyPair(
                publicKey  = PublicKey(existing.publicKeyHex.hexToByteArray()),
                privateKey = PrivateKey(existing.dhPrivateKeyHex.hexToByteArray()),
            )
            return existing to keyPair
        }
        val dhKeyPair = crypto.generateKeyPair()
        val signingKeyPair = crypto.generateSigningKeyPair()
        val record = IdentityRecord(
            id                   = uuid4().toString(),
            username             = username,
            publicKeyHex         = crypto.publicKeyToHex(dhKeyPair.publicKey),
            dhPrivateKeyHex      = dhKeyPair.privateKey.bytes.toHexString(),
            createdAt            = Clock.System.now().toEpochMilliseconds(),
            signingPublicKeyHex  = crypto.signingPublicKeyToHex(signingKeyPair.publicKey),
            signingPrivateKeyHex = signingKeyPair.privateKey.bytes.toHexString(),
        )
        repository.saveIdentity(record)
        return record to dhKeyPair
    }

    suspend fun getIdentity(): IdentityRecord? = repository.loadIdentity()

    /**
     * Returns the Ed25519 signing keypair from the persisted identity, or
     * null when the record is an Alpha 1 record that has not yet been
     * backfilled. Callers that get null should route through the
     * MigrationScreen and call [backfillSigningKeyPair] on Continue.
     */
    suspend fun loadSigningKeyPair(): IdentitySigningKeyPair? {
        val record = repository.loadIdentity() ?: return null
        if (record.needsSigningKeyBackfill) return null
        val pubHex = record.signingPublicKeyHex!!
        val privHex = record.signingPrivateKeyHex!!
        return IdentitySigningKeyPair(
            publicKey  = SigningPublicKey(pubHex.hexToByteArray()),
            privateKey = SigningPrivateKey(privHex.hexToByteArray()),
        )
    }

    /**
     * F11 + F26: produce a 64-byte Ed25519 detached signature over [nonce]
     * using the local signing keypair. Returns null when the signing key has
     * not been provisioned yet (Alpha 1 record awaiting migration backfill);
     * the relay-auth caller treats null as "abort this connect attempt and
     * back off" so the WS reconnect loop simply retries after the migration
     * runs.
     */
    suspend fun signRelayChallenge(nonce: ByteArray): ByteArray? {
        val pair = loadSigningKeyPair() ?: return null
        return crypto.signWithIdentity(message = nonce, privateKey = pair.privateKey)
    }

    /**
     * Generate a fresh Ed25519 signing keypair and atomically attach it to
     * the existing IdentityRecord. Idempotent: returns the existing keypair
     * if one is already present (so a double-tap on "Continue" or a crash
     * mid-migration doesn't burn through fresh randomness on every retry).
     *
     * Throws [IllegalStateException] if no IdentityRecord exists yet — the
     * caller flow is supposed to ensure [createOrLoad] ran first.
     */
    suspend fun backfillSigningKeyPair(): IdentitySigningKeyPair {
        val existing = repository.loadIdentity()
            ?: error("backfillSigningKeyPair called before createOrLoad")

        if (!existing.needsSigningKeyBackfill) {
            // Idempotent — return what's already there.
            return IdentitySigningKeyPair(
                publicKey  = SigningPublicKey(existing.signingPublicKeyHex!!.hexToByteArray()),
                privateKey = SigningPrivateKey(existing.signingPrivateKeyHex!!.hexToByteArray()),
            )
        }

        val signing = crypto.generateSigningKeyPair()
        val updated = existing.copy(
            signingPublicKeyHex  = crypto.signingPublicKeyToHex(signing.publicKey),
            signingPrivateKeyHex = signing.privateKey.bytes.toHexString(),
        )
        repository.saveIdentity(updated)
        return signing
    }

    fun exportPublicKeyHex(keyPair: IdentityKeyPair): String = crypto.publicKeyToHex(keyPair.publicKey)
}

private fun ByteArray.toHexString(): String =
    joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
