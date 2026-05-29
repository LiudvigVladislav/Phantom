// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import phantom.core.crypto.DhKeyPair
import phantom.core.crypto.DhPrivateKey
import phantom.core.crypto.DhPublicKey
import phantom.core.crypto.RatchetState
import phantom.core.crypto.SignedPreKeySigner
import phantom.core.crypto.X3DHProtocol
import phantom.core.identity.IdentityCrypto
import phantom.core.identity.SigningPublicKey
import phantom.core.storage.LocalOneTimePreKeyRepository
import phantom.core.storage.LocalSignedPreKeyRepository
import phantom.core.storage.RatchetStateRepository

/**
 * Manages per-conversation Double Ratchet state and the X3DH 4-DH
 * bootstrap that seeds it.
 *
 * Three flows:
 *
 *  - [tryLoadSession] — returns the persisted state for an existing
 *    session, or null if the conversation has never had one. The DMS
 *    layer uses this to decide whether the next outbound message must
 *    bootstrap or just encrypt under the current state.
 *
 *  - [initiatorBootstrap] — given a peer's [PreKeyBundle] (fetched from
 *    the relay's `/prekeys/bundle/{x25519}` endpoint), verifies the SPK
 *    signature against the bundle's Ed25519 `signing_pubkey_hex`,
 *    generates a fresh ephemeral keypair, runs
 *    `X3DHProtocol.initiatorHandshake4DH`, and returns
 *    [InitiatorBootstrapResult] containing the new [RatchetState] plus
 *    the [X3dhInitHeader] the caller must attach to the first outbound
 *    [WireFrame].
 *
 *  - [recipientBootstrap] — given the cleartext [X3dhInitHeader] the
 *    initiator put on the wire alongside the first message, looks up
 *    the matching local Signed PreKey + One-Time PreKey (consuming the
 *    OPK on success), runs `X3DHProtocol.recipientHandshake4DH`, and
 *    returns the new [RatchetState].
 *
 * Both bootstrap paths enforce two invariants explicitly before
 * persisting state:
 *
 *  - **F12 closure** — the X3DH 4-DH primitive is the ONLY way a
 *    [RatchetState] gets created. The Alpha 1 path that derived the root
 *    key from `X3DHProtocol.computeSharedSecret(my_identity_priv,
 *    their_identity_pub)` is gone. There is no fallback.
 *
 *  - **F15 invariant** — the freshly-created [RatchetState] must have
 *    `sendingRatchetPublicKey != localIdentity.publicKey.bytes`. The
 *    [X3DHProtocol] implementation in [phantom.core.crypto.LibsodiumX3DH]
 *    already mints a fresh ephemeral DH keypair as the ratchet seed
 *    (see PR A); this check is defence-in-depth so a future regression
 *    in the crypto layer surfaces here before any state is written.
 */
class SessionManager(
    private val x3dh: X3DHProtocol,
    private val ratchetStateRepository: RatchetStateRepository,
    private val signedPreKeyRepository: LocalSignedPreKeyRepository,
    private val oneTimePreKeyRepository: LocalOneTimePreKeyRepository,
    private val identityCrypto: IdentityCrypto,
    private val json: Json,
) {

    /**
     * Returns the persisted [RatchetState] for the conversation, or null
     * when no session has been bootstrapped yet. The caller (DMS encrypt
     * path on send, decrypt path on receive) routes a null result into
     * one of the bootstrap methods.
     */
    suspend fun tryLoadSession(conversationId: String): RatchetState? {
        val blob = ratchetStateRepository.getRatchetState(conversationId) ?: return null
        return json.decodeFromString(blob)
    }

    /**
     * Initiator bootstrap. Verifies the bundle's SPK signature, generates
     * a fresh ephemeral DH keypair, runs the 4-DH handshake, asserts the
     * F12 + F15 invariants, persists the [RatchetState], and returns
     * everything the caller needs to construct the first outbound
     * [WireFrame].
     *
     * @param conversationId  conversation key in the ratchet state store
     * @param localIdentityKeyPair  the local user's X25519 identity DH
     *        keypair (from [phantom.core.identity.IdentityRecord]). Used
     *        only as the X25519 input to the 4-DH handshake — never as
     *        the ratchet seed.
     * @param bundle  the peer's [PreKeyBundle] returned by the relay's
     *        `/prekeys/bundle/{x25519}` endpoint
     */
    suspend fun initiatorBootstrap(
        conversationId: String,
        localIdentityKeyPair: DhKeyPair,
        bundle: PreKeyBundle,
    ): InitiatorBootstrapResult {
        // Decode + structural validation of bundle bytes BEFORE running
        // any crypto. A malformed peer publish would otherwise surface
        // as an opaque libsodium error several layers down.
        val recipientIdentity = bundle.recipientIdentityPublicKey
            ?: throw SessionBootstrapException.MalformedBundle(
                "bundle.identity_pubkey_hex did not decode to a 32-byte X25519 public key",
            )
        val recipientSpkPub = bundle.recipientSignedPreKeyPublic
            ?: throw SessionBootstrapException.MalformedBundle(
                "bundle.signed_pre_key.public_key_hex did not decode to 32 bytes",
            )
        val recipientOpkPub = bundle.recipientOneTimePreKeyPublic // null when pool was empty

        // Verify SPK signature against the bundle's Ed25519 signing key.
        // The verifier reconstructs the canonical signing payload itself
        // — the caller does NOT supply a "message" buffer that the
        // peer could lie about. Same domain-separated payload the
        // SignedPreKeySigner produces when the publisher signs.
        val signingPub = bundle.signingPublicKey
            ?: throw SessionBootstrapException.MalformedBundle(
                "bundle.signing_pubkey_hex did not decode to 32 bytes",
            )
        val signatureBytes = bundle.signatureBytes
            ?: throw SessionBootstrapException.MalformedBundle(
                "bundle.signed_pre_key.signature_hex did not decode to 64 bytes",
            )
        val spkValid = SignedPreKeySigner.verify(
            spkPublic = recipientSpkPub,
            createdAtMs = bundle.signedPreKeyCreatedAtMs,
            signature = signatureBytes,
            identityEd25519PublicKey = signingPub.bytes,
        )
        if (!spkValid) {
            throw SessionBootstrapException.InvalidSpkSignature(
                "Ed25519 signature on signed_pre_key did not verify against bundle.signing_pubkey_hex",
            )
        }

        // Run the 4-DH handshake. We mint EK_a (the X3DH ephemeral, used
        // as DH input in DH2/DH3/DH4) ourselves so we can ship its public
        // half in the X3dhInitHeader — without it the recipient cannot
        // recompute the same DH chain on their side. The Double Ratchet
        // sending-ratchet seed is a separate fresh keypair generated
        // INSIDE LibsodiumX3DH (per F15); SessionManager never touches it.
        val ephemeral = x3dh.generateDhKeyPair()
        val state = x3dh.initiatorHandshake4DHWithEphemeral(
            initiatorIdentityKeyPair = localIdentityKeyPair,
            recipientIdentityPublicKey = recipientIdentity,
            recipientSignedPreKey = recipientSpkPub,
            recipientOPK = recipientOpkPub,
            ephemeralKeyPair = ephemeral,
        )

        // F15: the ratchet seed must NEVER be the identity keypair.
        // LibsodiumX3DH mints a fresh ephemeral so this should always
        // hold; the assert catches a silent regression in the crypto
        // layer before broken state hits disk.
        require(
            !state.sendingRatchetPublicKey.contentEquals(localIdentityKeyPair.publicKey.bytes),
        ) {
            "F15 invariant violated: sending ratchet public key equals identity public key. " +
                "RatchetState would leak identity-key compromise into all session keys."
        }
        require(
            !state.sendingRatchetPrivateKey.contentEquals(localIdentityKeyPair.privateKey.bytes),
        ) {
            "F15 invariant violated: sending ratchet private key equals identity private key."
        }

        saveSession(conversationId, state)

        // The header that the caller must attach to the first outbound
        // WireFrame so the recipient can recompute the same root key.
        // ephemeralPubKeyHex is EK_a's public half (the X3DH ephemeral
        // we just minted), NOT the ratchet seed inside [state] — the
        // recipient does DH(spk_priv, EK_a) etc., not DH against the
        // ratchet seed.
        val header = X3dhInitHeader(
            ephemeralPubKeyHex = ephemeral.publicKey.bytes.toHexString(),
            spkKeyId = bundle.signedPreKeyId,
            opkKeyIdHex = bundle.oneTimePreKeyIdHex,
        )
        return InitiatorBootstrapResult(state, header)
    }

    /**
     * Recipient bootstrap. Looks up the matching local SPK + OPK by id,
     * consumes the OPK (atomic delete from local pool — single-use), runs
     * the recipient-side 4-DH handshake, asserts F12 + F15, persists the
     * resulting [RatchetState].
     *
     * @param conversationId  conversation key
     * @param localIdentityKeyPair  the local user's X25519 identity DH
     *        keypair (the recipient half of DH2)
     * @param senderIdentityPublicKeyHex  the X25519 identity hex of the
     *        peer who initiated. Used as the initiator's identity input
     *        to recipientHandshake4DH.
     * @param x3dhInit  the cleartext header that arrived alongside the
     *        first message
     */
    suspend fun recipientBootstrap(
        conversationId: String,
        localIdentityKeyPair: DhKeyPair,
        senderIdentityPublicKeyHex: String,
        x3dhInit: X3dhInitHeader,
    ): RatchetState {
        // PR-CRYPTO-INBOUND-X3DH-REPAIR1 commit 1 (2026-05-29) — refactored
        // into a thin wrapper over [recipientBootstrapInMemory] + [saveSession]
        // so the crypto-only and crypto+persist paths share byte-identical
        // derivation logic. No behaviour change at this call site; the
        // existing no-session bootstrap path in
        // `DefaultMessagingService.handleDeliver` continues to call
        // [recipientBootstrap] and see the same persistence + return.
        val state = recipientBootstrapInMemory(
            conversationId = conversationId,
            localIdentityKeyPair = localIdentityKeyPair,
            senderIdentityPublicKeyHex = senderIdentityPublicKeyHex,
            x3dhInit = x3dhInit,
        )
        saveSession(conversationId, state)
        return state
    }

    /**
     * PR-CRYPTO-INBOUND-X3DH-REPAIR1 commit 1 (2026-05-29) — in-memory
     * recipient-bootstrap variant.
     *
     * Derives a candidate [RatchetState] from an inbound X3DH header
     * WITHOUT persisting it to the ratchet state repository. The intended
     * consumer is `DefaultMessagingService.handleDeliver`'s MAC-failure
     * repair branch (Commit 2 of this PR) — when the existing local
     * session has gone stale and the inbound envelope carries an
     * `x3dhInit` payload, the receive path uses this method to derive
     * a candidate state, attempts `ratchet.decrypt(candidate, …)`, and
     * commits the new state via [saveSession] ONLY AFTER the decrypt
     * succeeds.
     *
     * **Differences from [recipientBootstrap]:**
     *
     * 1. **Does NOT call [saveSession].** The returned candidate state
     *    must be persisted by the caller AFTER candidate-decrypt
     *    succeeds. If candidate-decrypt fails (the inbound `x3dhInit`
     *    was forged, replayed, or the derived ratchet still mismatches
     *    the on-wire ciphertext), the caller MUST NOT persist this
     *    state — the on-disk session row remains byte-identical to its
     *    pre-receive content. This is the central invariant of
     *    PR-CRYPTO-INBOUND-X3DH-REPAIR1
     *    (`docs/tracks/crypto-inbound-x3dh-repair.md` §Scope item 5):
     *    *OLD RATCHET SESSION MUST BE PRESERVED on candidate
     *    bootstrap / candidate-decrypt failure*.
     *
     * 2. **OPK consumption follows the same eager-consume model as the
     *    existing [recipientBootstrap]** — the referenced OPK is
     *    deleted from the local pool BEFORE the X3DH handshake runs,
     *    preserving the F1 single-use invariant.
     *
     *    This is an **explicit implementation decision** (per mini-lock
     *    §Scope item 5: "OPK consumption is left as an implementation
     *    decision at commit-1 review"). The choice here — eager consume,
     *    same as existing — is conservative because:
     *      - it preserves the F1 invariant uniformly across both
     *        bootstrap variants;
     *      - it matches the semantic peers expect: a successful
     *        x3dhInit (even one whose decrypt later fails) was a
     *        legitimate consumption signal at the SessionManager layer,
     *        and the relay's bundle-fetch path has already removed the
     *        OPK from the public store anyway;
     *      - the alternative (defer consumption until candidate-decrypt
     *        succeeds) would need a separate OPK-reservation pathway
     *        that complicates the F1 single-use guarantee and adds
     *        schema-state without a clear win — the peer would still
     *        derive a fresh OPK for any subsequent repair attempt
     *        because the repair-on-suspect flow re-fetches the bundle
     *        upstream.
     *
     *    If Commit 2's receive-path design surfaces a concrete need to
     *    preserve OPK on candidate-decrypt failure, the policy can be
     *    introduced as a parameter on this method at that review or as
     *    a separate follow-up. The §Scope item 5 invariant — preservation
     *    of the *ratchet session row* — holds regardless of OPK
     *    lifecycle.
     *
     * **Failure semantics:** any error propagates as the same typed
     * exception that [recipientBootstrap] would throw:
     *   - [SessionBootstrapException.SpkNotFound] when the inbound
     *     `x3dhInit.spkKeyId` doesn't match the current or previous SPK
     *     in the local store;
     *   - [SessionBootstrapException.OpkNotFound] when the inbound
     *     `x3dhInit.opkKeyIdHex` references an OPK not in the local
     *     pool (already consumed, never published, or local DB wiped);
     *   - whatever `x3dh.recipientHandshake4DH(...)` raises (X3DH-layer
     *     crypto exceptions — typically `IllegalArgumentException` from
     *     libsodium for malformed inputs);
     *   - [IllegalArgumentException] from `require(...)` if the F15
     *     invariant ever surfaces a regression in `LibsodiumX3DH`
     *     (sendingRatchet keypair must not equal the local identity).
     *
     * The caller wraps the resulting [Throwable] into a
     * `DECRYPT_TRACE inbound_repair_fail errorClass=${e::class.simpleName}`
     * log line; the typed exception names preserve diagnostic
     * specificity for triage (per mini-lock §Scope item 3
     * Vladislav-locked 2026-05-29: a nullable return would have erased
     * the `errorClass` to `Unknown`).
     *
     * **Returns:** non-null [RatchetState] candidate. Never returns null.
     */
    suspend fun recipientBootstrapInMemory(
        conversationId: String,
        localIdentityKeyPair: DhKeyPair,
        senderIdentityPublicKeyHex: String,
        x3dhInit: X3dhInitHeader,
    ): RatchetState {
        // Resolve the SPK keypair locally. Either the current SPK or the
        // previous (retained for SPK_PREVIOUS_RETENTION_DAYS days after
        // rotation) must match the targeted keyId. Anything else is an
        // out-of-window message — the local store has rolled past it.
        val storedSpk = signedPreKeyRepository.get()
            ?: throw SessionBootstrapException.SpkNotFound(x3dhInit.spkKeyId)
        val (spkPub, spkPriv) = when (x3dhInit.spkKeyId) {
            storedSpk.keyId -> Pair(
                DhPublicKey(storedSpk.publicKeyHex.hexToByteArray()),
                DhPrivateKey(storedSpk.privateKeyHex.hexToByteArray()),
            )
            storedSpk.previous?.keyId -> {
                val prev = storedSpk.previous!!
                Pair(
                    DhPublicKey(prev.publicKeyHex.hexToByteArray()),
                    DhPrivateKey(prev.privateKeyHex.hexToByteArray()),
                )
            }
            else -> throw SessionBootstrapException.SpkNotFound(x3dhInit.spkKeyId)
        }
        val spkKeyPair = DhKeyPair(spkPub, spkPriv)

        // Resolve the OPK keypair if the initiator referenced one. Atomic
        // consume: delete from local pool BEFORE deriving the secret so a
        // mid-derive crash + retry can't reuse the same OPK twice.
        val opkKeyPair: DhKeyPair? = x3dhInit.opkKeyIdHex?.let { opkId ->
            val opk = oneTimePreKeyRepository.get(opkId)
                ?: throw SessionBootstrapException.OpkNotFound(opkId)
            // Single-use lifecycle: delete first, then use the value.
            // Safe because we hold the only async reference; the pool
            // is per-device, not concurrent. See § "OPK consumption"
            // in this method's KDoc for the explicit implementation
            // decision recorded in commit-1 review of PR-CRYPTO-
            // INBOUND-X3DH-REPAIR1.
            oneTimePreKeyRepository.deleteByKeyId(opkId)
            DhKeyPair(
                publicKey = DhPublicKey(opk.publicKeyHex.hexToByteArray()),
                privateKey = DhPrivateKey(opk.privateKeyHex.hexToByteArray()),
            )
        }

        // Decode the initiator's ephemeral pubkey from the wire header.
        val initiatorEphemeralPub = DhPublicKey(
            x3dhInit.ephemeralPubKeyHex.hexToByteArray(),
        )
        val initiatorIdentityPub = DhPublicKey(
            senderIdentityPublicKeyHex.hexToByteArray(),
        )

        // Run the recipient-side 4-DH handshake. F12 closure: this is
        // the only call into the X3DH layer; no computeSharedSecret bypass.
        val state = x3dh.recipientHandshake4DH(
            recipientIdentityKeyPair = localIdentityKeyPair,
            recipientSignedPreKeyPair = spkKeyPair,
            recipientOPKPair = opkKeyPair,
            initiatorIdentityPublicKey = initiatorIdentityPub,
            initiatorEphemeralPublicKey = initiatorEphemeralPub,
        )

        // F15 invariant — for the recipient side the ratchet seed is the
        // SPK keypair (per Signal X3DH spec, matched by LibsodiumX3DH).
        // The identity DH keypair must NOT appear in the state.
        require(
            !state.sendingRatchetPublicKey.contentEquals(localIdentityKeyPair.publicKey.bytes),
        ) {
            "F15 invariant violated on recipient side: sendingRatchetPublicKey equals " +
                "localIdentity.publicKey. Possible regression in LibsodiumX3DH.recipientHandshake4DH."
        }
        require(
            !state.sendingRatchetPrivateKey.contentEquals(localIdentityKeyPair.privateKey.bytes),
        ) {
            "F15 invariant violated on recipient side: sendingRatchetPrivateKey equals " +
                "localIdentity.privateKey."
        }

        // CRITICAL DIFFERENCE FROM [recipientBootstrap]: no [saveSession]
        // call. The on-disk ratchet state row for `conversationId`
        // remains byte-identical to its pre-call content. The caller
        // commits this state via [saveSession] ONLY AFTER successful
        // candidate-decrypt; on candidate-decrypt failure, the caller
        // discards `state` and the existing session row is preserved.
        return state
    }

    /**
     * Verify a peer's published Ed25519 signing key against an SPK they
     * previously published. Used by the future SPK-rotation path on the
     * recipient side: each time a peer pushes a new bundle, the cached
     * `signingPublicKey` (learned from their first message) must verify
     * the new SPK signature. A mismatch surfaces a key-change warning.
     *
     * Exposed here so DMS doesn't have to reach into the crypto layer
     * directly. PR C only wires the cache-on-first-message path; the
     * rotation-verify path is a Phase 1 Week 5 follow-up.
     */
    fun verifyPeerSpkSignature(
        cachedSigningKey: SigningPublicKey,
        spkPublic: DhPublicKey,
        spkCreatedAtMs: Long,
        signature: ByteArray,
    ): Boolean = SignedPreKeySigner.verify(
        spkPublic = spkPublic,
        createdAtMs = spkCreatedAtMs,
        signature = signature,
        identityEd25519PublicKey = cachedSigningKey.bytes,
    ).also {
        // Surface to the IdentityCrypto interface as well so the caller
        // can swap implementations in tests without affecting the
        // verify path semantics. Intentionally redundant — both lead
        // to the same libsodium primitive.
        @Suppress("UNUSED_VARIABLE")
        val _alsoOk = identityCrypto
    }

    suspend fun saveSession(conversationId: String, state: RatchetState) {
        ratchetStateRepository.upsertRatchetState(conversationId, json.encodeToString(state))
    }

    suspend fun deleteSession(conversationId: String) {
        ratchetStateRepository.deleteRatchetState(conversationId)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun ByteArray.toHexString(): String =
        joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0) { "hex string must have even length" }
        return ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

/**
 * Result of [SessionManager.initiatorBootstrap]. The caller (typically
 * `DefaultMessagingService.encryptUnderLock`) attaches [x3dhInit] to the
 * first outbound [WireFrame] so the recipient can recompute the matching
 * RatchetState; subsequent messages in the session set [WireFrame.x3dhInit]
 * = null.
 */
data class InitiatorBootstrapResult(
    val ratchetState: RatchetState,
    val x3dhInit: X3dhInitHeader,
)

/**
 * Decoded view of a relay-returned [PreKeyBundle] (the wire type lives in
 * `phantom.core.transport.PreKeyApiClient.PreKeyBundle` and uses hex-encoded
 * fields). [SessionManager.initiatorBootstrap] takes this richer shape so
 * the bootstrap path doesn't have to hex-decode in five places, and
 * malformed-bundle errors surface at one well-known boundary.
 *
 * Construct via [PreKeyBundle.fromWire].
 */
data class PreKeyBundle(
    val identityPubkeyHex: String,
    val signingPubkeyHex: String,
    val signedPreKeyId: Long,
    val signedPreKeyPublicHex: String,
    val signedPreKeyCreatedAtMs: Long,
    val signedPreKeySignatureHex: String,
    val oneTimePreKeyIdHex: String? = null,
    val oneTimePreKeyPublicHex: String? = null,
) {
    val recipientIdentityPublicKey: DhPublicKey?
        get() = identityPubkeyHex.tryHexDecode(32)?.let { DhPublicKey(it) }

    val recipientSignedPreKeyPublic: DhPublicKey?
        get() = signedPreKeyPublicHex.tryHexDecode(32)?.let { DhPublicKey(it) }

    val recipientOneTimePreKeyPublic: DhPublicKey?
        get() = oneTimePreKeyPublicHex?.tryHexDecode(32)?.let { DhPublicKey(it) }

    val signingPublicKey: SigningPublicKey?
        get() = signingPubkeyHex.tryHexDecode(32)?.let { SigningPublicKey(it) }

    val signatureBytes: ByteArray?
        get() = signedPreKeySignatureHex.tryHexDecode(64)

    private fun String.tryHexDecode(expectedLen: Int): ByteArray? = runCatching {
        require(length == expectedLen * 2)
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }.getOrNull()

    companion object {
        /**
         * Promote a wire-format [phantom.core.transport.PreKeyBundle] into
         * the SessionManager-facing shape. Field renames document where
         * the wire format and the in-process API drift apart so a
         * downstream consumer doesn't have to translate snake_case →
         * lowerCamelCase at every call site.
         */
        fun fromWire(wire: phantom.core.transport.PreKeyBundle): PreKeyBundle =
            PreKeyBundle(
                identityPubkeyHex = wire.identity_pubkey_hex,
                signingPubkeyHex = wire.signing_pubkey_hex,
                signedPreKeyId = wire.signed_pre_key.key_id,
                signedPreKeyPublicHex = wire.signed_pre_key.public_key_hex,
                signedPreKeyCreatedAtMs = wire.signed_pre_key.created_at_ms,
                signedPreKeySignatureHex = wire.signed_pre_key.signature_hex,
                oneTimePreKeyIdHex = wire.one_time_pre_key?.key_id_hex,
                oneTimePreKeyPublicHex = wire.one_time_pre_key?.public_key_hex,
            )
    }
}

// ── Errors ───────────────────────────────────────────────────────────────────

sealed class SessionBootstrapException(message: String) : Exception(message) {
    /**
     * The peer's published SignedPreKey signature did not verify against
     * the Ed25519 signing key carried alongside it. The bundle is rejected
     * before any session derivation happens.
     */
    class InvalidSpkSignature(detail: String) :
        SessionBootstrapException("invalid SPK signature: $detail")

    /**
     * Recipient-side: the initiator targeted a Signed PreKey id we no
     * longer hold. Either:
     *   - rotation cleanup purged a "previous" generation past its
     *     14-day retention window (legitimate, peer should re-bootstrap)
     *   - replay/forge attempt with a fabricated id
     *   - local DB wipe lost the state
     */
    class SpkNotFound(val spkKeyId: Long) :
        SessionBootstrapException(
            "local SPK keyId=$spkKeyId not in local store; " +
                "rotation cleanup may have purged it or the message is out-of-window",
        )

    /**
     * Recipient-side: the initiator referenced a One-Time PreKey id that
     * isn't in our local pool. The relay deletes a referenced OPK from
     * the public bundle store on the fetch path, so by the time we
     * receive this message any second references would already be
     * impossible at the relay level. A miss here means either:
     *   - local DB wipe (legitimate; peer must re-bootstrap)
     *   - replay from a long time ago (we already consumed and deleted)
     *   - forged keyId
     */
    class OpkNotFound(val opkKeyIdHex: String) :
        SessionBootstrapException(
            "local OPK keyId=${opkKeyIdHex.take(16)}... not in local pool; " +
                "possible replay or out-of-window message",
        )

    /**
     * Bundle hex fields didn't decode to the expected byte length.
     * Almost certainly a relay/wire-format bug rather than an attack.
     */
    class MalformedBundle(detail: String) :
        SessionBootstrapException("malformed bundle: $detail")
}
