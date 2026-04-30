// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import com.ionspin.kotlin.crypto.signature.Signature
import kotlinx.serialization.Serializable

/**
 * Pre-key types for the X3DH 4-DH handshake (ADR-009).
 *
 * Pre-keys are short-lived asymmetric keypairs that the recipient publishes
 * ahead of time so the initiator can complete the handshake without the
 * recipient being online. Two flavours:
 *
 *  - SignedPreKey (SPK)        — long-lived (rotated weekly). Signed by the
 *                                identity key so the initiator can verify
 *                                the bundle came from the right account.
 *  - OneTimePreKey (OPK)       — single-use, consumed on first contact.
 *                                Provides forward secrecy if the SPK leaks.
 *
 * Wire format: data classes are kotlinx.serialization @Serializable so they
 * round-trip cleanly through the relay's prekey-bundle endpoint.
 *
 * NOTE: structures here only define the *shape*. The relay-side endpoints
 * that store and serve bundles arrive in PR B. The SessionManager rewrite
 * that actually consumes them lands in PR C.
 */

// ── SignedPreKey ─────────────────────────────────────────────────────────────

/**
 * A signed pre-key keypair held privately by the recipient. Only the public
 * half is published — the private half stays on-device until the matching
 * 4-DH handshake completes, then is retained for one rotation window so
 * inflight handshakes still resolve.
 */
data class SignedPreKey(
    /** Monotonic id used by the relay to route incoming sessions to the
     *  right keypair. Survives rotation (old ids are kept until expiry). */
    val keyId: Long,
    /** Curve25519 keypair. */
    val keyPair: DhKeyPair,
    /** Wall-clock creation time in ms — used in the signing payload as an
     *  anti-replay binding so a stolen-but-stale signature can't be reused. */
    val createdAtMs: Long,
)

/**
 * Public half of a SignedPreKey, ready to publish.
 *
 * The signature is a detached Ed25519 signature over
 *   "phantom-spk-v1" || publicKey.bytes || createdAtMs (8-byte big-endian)
 *
 * produced with the identity Ed25519 secret key. Verifiers MUST recompute
 * the same payload — never trust a publicly-supplied "message" field.
 */
@Serializable
data class SignedPreKeyPublicBundle(
    val keyId: Long,
    /** 32-byte Curve25519 public key. */
    val publicKey: ByteArray,
    val createdAtMs: Long,
    /** 64-byte Ed25519 detached signature, see [SignedPreKey] doc. */
    val signature: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignedPreKeyPublicBundle) return false
        return keyId == other.keyId &&
            publicKey.contentEquals(other.publicKey) &&
            createdAtMs == other.createdAtMs &&
            signature.contentEquals(other.signature)
    }
    override fun hashCode(): Int {
        var r = keyId.hashCode()
        r = 31 * r + publicKey.contentHashCode()
        r = 31 * r + createdAtMs.hashCode()
        r = 31 * r + signature.contentHashCode()
        return r
    }
}

// ── OneTimePreKey ────────────────────────────────────────────────────────────

/**
 * A one-time pre-key. Consumed on first 4-DH handshake — the relay deletes
 * it after delivery so a future initiator cannot reuse it.
 */
data class OneTimePreKey(
    val keyId: Long,
    val keyPair: DhKeyPair,
)

@Serializable
data class OneTimePreKeyPublicBundle(
    val keyId: Long,
    /** 32-byte Curve25519 public key. */
    val publicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OneTimePreKeyPublicBundle) return false
        return keyId == other.keyId && publicKey.contentEquals(other.publicKey)
    }
    override fun hashCode(): Int =
        31 * keyId.hashCode() + publicKey.contentHashCode()
}

// ── PreKeyBundle ─────────────────────────────────────────────────────────────

/**
 * The full bundle the relay returns when an initiator asks for a recipient's
 * pre-keys. The OPK slot is optional — if the recipient ran out of OPKs the
 * handshake falls back to 3-DH.
 *
 * `identityPublicKey` is the X25519 *DH* identity key (the key used inside
 * the X3DH DH operations). The Ed25519 *signing* identity is what verifies
 * the SPK signature; it lives in IdentityRecord and is fetched separately.
 *
 * If the future ADR-017 work removes the dual-key model, the bundle gains a
 * single `identityPublicKey` (Ed25519) and the verifier converts it via
 * `Signature.ed25519PkToCurve25519` for the DH ops. PR A intentionally does
 * not commit to that choice yet.
 */
@Serializable
data class PreKeyBundle(
    /** 32-byte Curve25519 identity DH key. */
    val identityPublicKey: ByteArray,
    val signedPreKey: SignedPreKeyPublicBundle,
    /** Null when the recipient's OPK pool was empty — handshake degrades
     *  to 3-DH. The handshake still works, it just loses the OPK round
     *  of forward secrecy. */
    val oneTimePreKey: OneTimePreKeyPublicBundle?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreKeyBundle) return false
        return identityPublicKey.contentEquals(other.identityPublicKey) &&
            signedPreKey == other.signedPreKey &&
            oneTimePreKey == other.oneTimePreKey
    }
    override fun hashCode(): Int {
        var r = identityPublicKey.contentHashCode()
        r = 31 * r + signedPreKey.hashCode()
        r = 31 * r + (oneTimePreKey?.hashCode() ?: 0)
        return r
    }
}

// ── SignedPreKeySigner ───────────────────────────────────────────────────────

/**
 * Builds and verifies the Ed25519 signature attached to a signed pre-key
 * bundle.
 *
 * The signing payload is built deterministically from
 *   - a 14-byte ASCII domain label "phantom-spk-v1"
 *   - the 32-byte Curve25519 public key bytes
 *   - the 8-byte big-endian millisecond timestamp
 *
 * The domain label binds the signature to this exact protocol step, so a
 * signature produced for some other use (e.g. a future "phantom-otpk-v1"
 * bundle) cannot be replayed here. The timestamp binds the signature to a
 * point in time, which lets the verifier reject signatures that travel
 * through an old SPK after rotation.
 *
 * The verifier must reconstruct the payload identically — never accept a
 * preformed message buffer from the wire.
 */
@OptIn(ExperimentalUnsignedTypes::class)
object SignedPreKeySigner {

    private const val DOMAIN_LABEL = "phantom-spk-v1"

    /**
     * @param identityEd25519SecretKey 64-byte Ed25519 secret key (libsodium
     *        stores secret + public concatenated; that's what `Signature.keypair`
     *        returns and what `detached` consumes).
     */
    fun sign(
        spkPublic: DhPublicKey,
        createdAtMs: Long,
        identityEd25519SecretKey: ByteArray,
    ): ByteArray {
        val payload = signingPayload(spkPublic, createdAtMs)
        return Signature.detached(
            message   = payload.toUByteArray(),
            secretKey = identityEd25519SecretKey.toUByteArray(),
        ).toByteArray()
    }

    /**
     * Returns true iff the signature is valid for the given identity public
     * key. Wraps the libsodium [Signature.verifyDetached] (which throws on
     * mismatch) into a boolean so callers don't need to catch.
     */
    fun verify(
        spkPublic: DhPublicKey,
        createdAtMs: Long,
        signature: ByteArray,
        identityEd25519PublicKey: ByteArray,
    ): Boolean {
        // Ed25519 signature is fixed 64 bytes; reject early on wrong size
        // before passing to native code (defence-in-depth, libsodium also checks).
        if (signature.size != 64) return false
        if (identityEd25519PublicKey.size != 32) return false
        val payload = signingPayload(spkPublic, createdAtMs)
        return runCatching {
            Signature.verifyDetached(
                signature = signature.toUByteArray(),
                message   = payload.toUByteArray(),
                publicKey = identityEd25519PublicKey.toUByteArray(),
            )
        }.isSuccess
    }

    /**
     * Canonical signing payload. Visible for tests so we can confirm the
     * exact bytes that go through Ed25519. Production callers should never
     * touch this directly.
     */
    fun signingPayload(spkPublic: DhPublicKey, createdAtMs: Long): ByteArray {
        val label = DOMAIN_LABEL.encodeToByteArray()
        val ts = ByteArray(8)
        var v = createdAtMs
        for (i in 7 downTo 0) {
            ts[i] = (v and 0xFFL).toByte()
            v = v ushr 8
        }
        return label + spkPublic.bytes + ts
    }
}
