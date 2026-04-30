// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.serialization.Serializable
import phantom.core.crypto.EncryptedMessage

/**
 * Per-message wire frame transmitted between PHANTOM clients.
 *
 * The relay sees this as an opaque base64-encoded JSON blob in its
 * `payload` field — see `services/relay/src/routes.rs::handle_message`.
 * The relay never inspects nor mutates the contents.
 *
 * Layout reasoning. The X3DH 4-DH bootstrap requires a recipient to know:
 *  - which ephemeral public key the initiator generated
 *  - which signed pre-key id was targeted
 *  - which one-time pre-key was consumed (or that none was)
 *  - the initiator's Ed25519 signing key (to verify FUTURE bundle rotations)
 *
 * The recipient must learn all of these BEFORE attempting to decrypt the
 * first message — there is no session yet, and the session keys are
 * derived from the bootstrap inputs. So the bootstrap header travels in
 * cleartext alongside the encrypted ciphertext, never inside it.
 *
 * After bootstrap, every subsequent message in the session has both
 * `x3dhInit` and `senderSigningPublicKeyHex` = `null`. The recipient
 * cached them on first contact; resending wastes bytes.
 *
 * Backward compatibility. Alpha 1 clients shipped only the bare
 * [EncryptedMessage] in this slot. The Alpha 1 → Alpha 2 migration wipes
 * every session, so the first message after migration is always a fresh
 * X3DH 4-DH bootstrap — there is no Alpha-1-to-Alpha-2 cross traffic to
 * worry about. Field defaults are nullable so a JSON parser still
 * succeeds on a slimmed-down frame, but the SessionManager rejects
 * frames missing both `x3dhInit` and an existing session as a malformed
 * bootstrap.
 */
@Serializable
data class WireFrame(
    /**
     * The Double Ratchet ciphertext + ratchet header, exactly as in
     * Alpha 1. Layout unchanged; only the WireFrame wrapping is new.
     */
    val encryptedMessage: EncryptedMessage,

    /**
     * Set on the FIRST message of a freshly-bootstrapped session by the
     * initiator. The recipient uses these inputs to call
     * `X3DHProtocol.recipientHandshake4DH` and derive the matching
     * RatchetState before attempting to decrypt [encryptedMessage].
     *
     * Null on every message after bootstrap (recipient already has the
     * session) and on every message in a session that re-bootstrapped
     * via the migration path (also handled as a fresh bootstrap, just
     * with `needs_rehandshake` cleared after success).
     */
    val x3dhInit: X3dhInitHeader? = null,

    /**
     * The initiator's Ed25519 signing public key (hex-encoded, 64 chars).
     * Set on the FIRST message of a session so the recipient can cache
     * it under the peer's X25519 identity for verifying FUTURE published
     * SignedPreKey rotations.
     *
     * Null on every subsequent message once the recipient has cached it.
     * The recipient stores the binding `peer_x25519_identity →
     * peer_ed25519_signing_key` keyed on the X25519 identity (which is
     * what the rest of the app addresses peers by). A peer that ever
     * re-bootstraps with a DIFFERENT signing key surfaces as a key-change
     * warning the same way Alpha 1's identity-key-changed flow does.
     */
    val senderSigningPublicKeyHex: String? = null,
)

/**
 * Cleartext bootstrap inputs that travel alongside the first message of a
 * fresh X3DH 4-DH session. See [WireFrame] for layout reasoning.
 */
@Serializable
data class X3dhInitHeader(
    /**
     * The initiator's freshly-generated X25519 ephemeral public key (32
     * bytes, hex-encoded). Used as the EK_a input in
     * `X3DHProtocol.initiatorHandshake4DH`. The matching private half
     * lives only in the initiator's [phantom.core.crypto.RatchetState]
     * and never travels.
     */
    val ephemeralPubKeyHex: String,

    /**
     * The recipient's Signed PreKey id that the initiator targeted.
     * Lets the recipient look up the matching private half: usually the
     * "current" SPK from `local_signed_pre_key`, but during the 14-day
     * retention window after rotation it could be the "previous" SPK.
     */
    val spkKeyId: Long,

    /**
     * The recipient's One-Time PreKey id that the initiator consumed
     * (the relay deleted it from the recipient's pool atomically on the
     * bundle-fetch path). Null when the recipient's pool was empty at
     * fetch time — handshake degrades gracefully to 3-DH composition.
     *
     * 16-byte hex (32 chars) when present.
     */
    val opkKeyIdHex: String? = null,
)
