// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import kotlinx.serialization.Serializable

/**
 * Role of a session record relative to the X3DH handshake that created
 * it. Diagnostic-only tag in this iteration; downstream callers may
 * use it to distinguish an INITIATOR-bootstrapped session (created on
 * the device that first ran `initiatorBootstrap`) from a RESPONDER-
 * bootstrapped session (created when an inbound `x3dhInit` was
 * processed via `recipientBootstrap` or `recipientBootstrapInMemory`).
 *
 * The Double Ratchet's sending and receiving chains are oriented
 * opposite ways for the two roles: an INITIATOR's sending chain
 * corresponds to a RESPONDER's receiving chain and vice versa. A
 * RESPONDER session is therefore not safe to use as an outbound
 * existing-session — the remote side will see a ciphertext from the
 * wrong chain and fail MAC verification. Marking the role at bootstrap
 * time lets a future outbound guard distinguish the two cases without
 * inspecting the ratchet keys themselves.
 *
 * Default for unmarked / legacy serialized records is [INITIATOR] so
 * blobs that were persisted before this field existed deserialize with
 * the historical pre-marking behaviour (no guard, treated as a normal
 * outbound-capable session). New records created via
 * [phantom.core.messaging.SessionManager] carry an explicit role.
 */
@Serializable
enum class SessionRole {
    /** Session was created by this device running `initiatorBootstrap`. */
    INITIATOR,

    /**
     * Session was created by this device running `recipientBootstrap`
     * or `recipientBootstrapInMemory` in response to an inbound
     * `x3dhInit` from a peer that initiated the X3DH exchange.
     */
    RESPONDER,
}

/**
 * Full serializable state of a Double Ratchet session for one party.
 *
 * Alpha-0 constraints:
 * - No skipped-message-key cache.
 * - No out-of-order delivery support.
 * Both are deferred to a later iteration.
 *
 * ByteArray fields are stored as raw bytes; serialization encodes them as
 * Base64 via the default kotlinx.serialization ByteArray encoder.
 */
@Serializable
data class RatchetState(
    /** Shared root key, updated on every DH ratchet step. */
    val rootKey: ByteArray,

    /** KDF chain key for the sending direction. Null before first send. */
    val sendingChainKey: ByteArray?,

    /** KDF chain key for the receiving direction. Null before first receive. */
    val receivingChainKey: ByteArray?,

    /** Our current DH ratchet public key (sent in every message header). */
    val sendingRatchetPublicKey: ByteArray,

    /** Our current DH ratchet private key (kept secret). */
    val sendingRatchetPrivateKey: ByteArray,

    /** The last DH ratchet public key we received from the remote party. */
    val receivingRatchetPublicKey: ByteArray?,

    /** Number of messages sent on the current sending chain. */
    val sendCount: Int = 0,

    /** Number of messages received on the current receiving chain. */
    val receiveCount: Int = 0,

    /**
     * Role of this session relative to its originating X3DH handshake.
     *
     * Default [SessionRole.INITIATOR] preserves backwards-compatible
     * behaviour for legacy serialized blobs (`rs1:` encoding) that
     * were written before this field existed — they deserialize with
     * the historical pre-marking semantics. New sessions created via
     * [phantom.core.messaging.SessionManager] tag the role explicitly
     * at bootstrap time. See [SessionRole] KDoc for the protocol
     * background.
     *
     * Consumed by the Sprint 2a outbound role guard in
     * `DefaultMessagingService.encryptUnderLock` — the existing-
     * session branch fires only when the loaded state's role is
     * [SessionRole.INITIATOR] and `sessionSuspect` is false. A
     * [SessionRole.RESPONDER]-tagged session is redirected into the
     * bootstrap branch (fresh X3DH 4-DH + outbound `x3dhInit`) so
     * the peer's inbound X3DH repair path can re-key their ratchet
     * to match. Legacy `rs1:` blobs without the role field
     * deserialize as [SessionRole.INITIATOR] by the default below,
     * so the guard is a no-op for any session row written before
     * the tag existed — pre-Sprint-1 broken RESPONDER pairs are
     * NOT auto-healed and require user-driven reset or re-pair.
     */
    val role: SessionRole = SessionRole.INITIATOR,
) {
    // ByteArray equals/hashCode must be structural for data class correctness.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RatchetState) return false
        return rootKey.contentEquals(other.rootKey) &&
            sendingChainKey.contentEqualsNullable(other.sendingChainKey) &&
            receivingChainKey.contentEqualsNullable(other.receivingChainKey) &&
            sendingRatchetPublicKey.contentEquals(other.sendingRatchetPublicKey) &&
            sendingRatchetPrivateKey.contentEquals(other.sendingRatchetPrivateKey) &&
            receivingRatchetPublicKey.contentEqualsNullable(other.receivingRatchetPublicKey) &&
            sendCount == other.sendCount &&
            receiveCount == other.receiveCount &&
            role == other.role
    }

    override fun hashCode(): Int {
        var result = rootKey.contentHashCode()
        result = 31 * result + (sendingChainKey?.contentHashCode() ?: 0)
        result = 31 * result + (receivingChainKey?.contentHashCode() ?: 0)
        result = 31 * result + sendingRatchetPublicKey.contentHashCode()
        result = 31 * result + sendingRatchetPrivateKey.contentHashCode()
        result = 31 * result + (receivingRatchetPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + sendCount
        result = 31 * result + receiveCount
        result = 31 * result + role.hashCode()
        return result
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> this.contentEquals(other)
    }
