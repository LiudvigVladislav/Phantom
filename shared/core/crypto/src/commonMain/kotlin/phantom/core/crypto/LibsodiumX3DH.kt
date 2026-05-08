// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import com.ionspin.kotlin.crypto.box.Box
import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.scalarmult.ScalarMultiplication

/**
 * X3DH implementation backed by libsodium primitives.
 *
 * DH primitive: X25519 via [ScalarMultiplication.scalarMultiplication].
 *   scalarMultiplication(secretKeyN = myPrivate, publicKeyP = theirPublic) → 32-byte shared secret.
 *   (Box.beforeNm is not available in ionspin 0.9.x; ScalarMultiplication is equivalent.)
 *
 * Two KDF paths:
 *  - Legacy 3-DH ([initiatorHandshake] / [recipientHandshake]) keeps the
 *    Alpha-0/1 SHA-256 KDF unchanged — purely for backward-compat with
 *    ratchet states already on disk.
 *  - 4-DH per ADR-009 uses HKDF-SHA256 (RFC 5869) with explicit domain
 *    separation: salt = SHA256("phantom-x3dh-v2"),
 *                info = "phantom-x3dh-rootkey-v1", L = 32.
 *    The salt is intentionally distinct from anything the legacy path
 *    could produce, so a 4-DH session and a 3-DH session derived from
 *    the same DH inputs would still yield different rootKeys.
 *
 * F15 fix (in this implementation): the initiator's sendingRatchetKeyPair
 * is `generateDhKeyPair()` — a fresh ephemeral, NEVER the identity key.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class LibsodiumX3DH : X3DHProtocol {

    override fun generateDhKeyPair(): DhKeyPair {
        val kp = Box.keypair()
        return DhKeyPair(
            publicKey  = DhPublicKey(kp.publicKey.toByteArray()),
            privateKey = DhPrivateKey(kp.secretKey.toByteArray()),
        )
    }

    // ── 3-DH legacy ──────────────────────────────────────────────────────────

    override fun initiatorHandshake(
        initiatorIdentityKeyPair: DhKeyPair,
        recipientIdentityPublicKey: DhPublicKey,
        recipientSignedPreKey: DhPublicKey,
    ): RatchetState = initiatorHandshakeWithEphemeral(
        initiatorIdentityKeyPair   = initiatorIdentityKeyPair,
        recipientIdentityPublicKey = recipientIdentityPublicKey,
        recipientSignedPreKey      = recipientSignedPreKey,
        ephemeralKeyPair           = generateDhKeyPair(),
    )

    fun initiatorHandshakeWithEphemeral(
        initiatorIdentityKeyPair: DhKeyPair,
        recipientIdentityPublicKey: DhPublicKey,
        recipientSignedPreKey: DhPublicKey,
        ephemeralKeyPair: DhKeyPair,
    ): RatchetState {
        val dh1 = dh(initiatorIdentityKeyPair.privateKey, recipientSignedPreKey)
        val dh2 = dh(ephemeralKeyPair.privateKey, recipientIdentityPublicKey)
        val dh3 = dh(ephemeralKeyPair.privateKey, recipientSignedPreKey)
        val masterSecret = kdfSha256(dh1, dh2, dh3)
        dh1.zeroize(); dh2.zeroize(); dh3.zeroize()
        val sendingRatchet = generateDhKeyPair()
        return RatchetState(
            rootKey                   = masterSecret,
            sendingChainKey           = null,
            receivingChainKey         = null,
            sendingRatchetPublicKey   = sendingRatchet.publicKey.bytes,
            sendingRatchetPrivateKey  = sendingRatchet.privateKey.bytes,
            receivingRatchetPublicKey = recipientSignedPreKey.bytes,
        )
    }

    override fun recipientHandshake(
        recipientIdentityKeyPair: DhKeyPair,
        recipientSignedPreKeyPair: DhKeyPair,
        initiatorIdentityPublicKey: DhPublicKey,
        initiatorEphemeralPublicKey: DhPublicKey,
    ): RatchetState {
        val dh1 = dh(recipientSignedPreKeyPair.privateKey, initiatorIdentityPublicKey)
        val dh2 = dh(recipientIdentityKeyPair.privateKey, initiatorEphemeralPublicKey)
        val dh3 = dh(recipientSignedPreKeyPair.privateKey, initiatorEphemeralPublicKey)
        val masterSecret = kdfSha256(dh1, dh2, dh3)
        dh1.zeroize(); dh2.zeroize(); dh3.zeroize()
        return RatchetState(
            rootKey                   = masterSecret,
            sendingChainKey           = null,
            receivingChainKey         = null,
            sendingRatchetPublicKey   = recipientSignedPreKeyPair.publicKey.bytes,
            sendingRatchetPrivateKey  = recipientSignedPreKeyPair.privateKey.bytes,
            receivingRatchetPublicKey = initiatorEphemeralPublicKey.bytes,
        )
    }

    // ── 4-DH per ADR-009 ─────────────────────────────────────────────────────

    override fun initiatorHandshake4DH(
        initiatorIdentityKeyPair: DhKeyPair,
        recipientIdentityPublicKey: DhPublicKey,
        recipientSignedPreKey: DhPublicKey,
        recipientOPK: DhPublicKey?,
    ): RatchetState = initiatorHandshake4DHWithEphemeral(
        initiatorIdentityKeyPair   = initiatorIdentityKeyPair,
        recipientIdentityPublicKey = recipientIdentityPublicKey,
        recipientSignedPreKey      = recipientSignedPreKey,
        recipientOPK               = recipientOPK,
        ephemeralKeyPair           = generateDhKeyPair(),
    )

    /**
     * Caller-supplied EK_a path. Used by SessionManager to carry EK_a's
     * public key on the wire, AND by tests to pin the ephemeral for
     * deterministic vector verification. The ratchet seed remains
     * generated-fresh per F15 inside this method.
     */
    override fun initiatorHandshake4DHWithEphemeral(
        initiatorIdentityKeyPair: DhKeyPair,
        recipientIdentityPublicKey: DhPublicKey,
        recipientSignedPreKey: DhPublicKey,
        recipientOPK: DhPublicKey?,
        ephemeralKeyPair: DhKeyPair,
    ): RatchetState {
        val dh1 = dh(initiatorIdentityKeyPair.privateKey, recipientSignedPreKey)
        val dh2 = dh(ephemeralKeyPair.privateKey, recipientIdentityPublicKey)
        val dh3 = dh(ephemeralKeyPair.privateKey, recipientSignedPreKey)
        val dh4 = recipientOPK?.let { dh(ephemeralKeyPair.privateKey, it) }
        val ikm = if (dh4 != null) dh1 + dh2 + dh3 + dh4 else dh1 + dh2 + dh3
        val masterSecret = hkdfSha256L32(
            ikm  = ikm,
            salt = SALT_PHANTOM_X3DH_V2,
            info = INFO_ROOTKEY,
        )
        dh1.zeroize(); dh2.zeroize(); dh3.zeroize(); dh4?.zeroize(); ikm.zeroize()
        // F15 fix: ratchet seed is a FRESH ephemeral, not the identity key.
        val sendingRatchet = generateDhKeyPair()
        return RatchetState(
            rootKey                   = masterSecret,
            sendingChainKey           = null,
            receivingChainKey         = null,
            sendingRatchetPublicKey   = sendingRatchet.publicKey.bytes,
            sendingRatchetPrivateKey  = sendingRatchet.privateKey.bytes,
            receivingRatchetPublicKey = recipientSignedPreKey.bytes,
        )
    }

    override fun recipientHandshake4DH(
        recipientIdentityKeyPair: DhKeyPair,
        recipientSignedPreKeyPair: DhKeyPair,
        recipientOPKPair: DhKeyPair?,
        initiatorIdentityPublicKey: DhPublicKey,
        initiatorEphemeralPublicKey: DhPublicKey,
    ): RatchetState {
        val dh1 = dh(recipientSignedPreKeyPair.privateKey, initiatorIdentityPublicKey)
        val dh2 = dh(recipientIdentityKeyPair.privateKey, initiatorEphemeralPublicKey)
        val dh3 = dh(recipientSignedPreKeyPair.privateKey, initiatorEphemeralPublicKey)
        val dh4 = recipientOPKPair?.let { dh(it.privateKey, initiatorEphemeralPublicKey) }
        val ikm = if (dh4 != null) dh1 + dh2 + dh3 + dh4 else dh1 + dh2 + dh3
        val masterSecret = hkdfSha256L32(
            ikm  = ikm,
            salt = SALT_PHANTOM_X3DH_V2,
            info = INFO_ROOTKEY,
        )
        dh1.zeroize(); dh2.zeroize(); dh3.zeroize(); dh4?.zeroize(); ikm.zeroize()
        return RatchetState(
            rootKey                   = masterSecret,
            sendingChainKey           = null,
            receivingChainKey         = null,
            sendingRatchetPublicKey   = recipientSignedPreKeyPair.publicKey.bytes,
            sendingRatchetPrivateKey  = recipientSignedPreKeyPair.privateKey.bytes,
            receivingRatchetPublicKey = initiatorEphemeralPublicKey.bytes,
        )
    }

    override fun computeSharedSecret(privateKey: DhPrivateKey, publicKey: DhPublicKey): ByteArray =
        ScalarMultiplication.scalarMultiplication(
            secretKeyN = privateKey.bytes.toUByteArray(),
            publicKeyP = publicKey.bytes.toUByteArray(),
        ).toByteArray()

    // --- helpers ---

    private fun dh(myPrivate: DhPrivateKey, theirPublic: DhPublicKey): ByteArray =
        ScalarMultiplication.scalarMultiplication(
            secretKeyN = myPrivate.bytes.toUByteArray(),
            publicKeyP = theirPublic.bytes.toUByteArray(),
        ).toByteArray()

    /** Legacy 3-DH KDF — SHA-256 over concatenation. Do not use for new code. */
    private fun kdfSha256(dh1: ByteArray, dh2: ByteArray, dh3: ByteArray): ByteArray =
        Hash.sha256((dh1 + dh2 + dh3).toUByteArray()).toByteArray()

    companion object {
        /**
         * Domain-separation salt for HKDF in the 4-DH path.
         *
         * Computed via SHA-256("phantom-x3dh-v2"). Lazy because the libsodium
         * native bridge isn't initialised at class-load time on JVM — eager
         * `val = run { Hash.sha256(...) }` would crash the moment any test
         * touches the class before `LibsodiumInitializer.initialize()` ran.
         * `by lazy` defers the call until the first 4-DH handshake fires,
         * by which point the test harness has run the initializer.
         */
        internal val SALT_PHANTOM_X3DH_V2: ByteArray by lazy {
            Hash.sha256("phantom-x3dh-v2".encodeToByteArray().toUByteArray()).toByteArray()
        }

        internal val INFO_ROOTKEY: ByteArray =
            "phantom-x3dh-rootkey-v1".encodeToByteArray()

        /** Delegates to the shared [Hkdf.sha256L32]; kept for test compatibility. */
        internal fun hkdfSha256L32(
            ikm: ByteArray,
            salt: ByteArray,
            info: ByteArray,
        ): ByteArray = Hkdf.sha256L32(ikm = ikm, salt = salt, info = info)
    }
}
