// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.datetime.Clock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import phantom.core.crypto.DhKeyPair
import phantom.core.crypto.DhPrivateKey
import phantom.core.crypto.DhPublicKey
import phantom.core.crypto.RatchetState
import phantom.core.crypto.SessionRole
import phantom.core.crypto.SignedPreKeySigner
import phantom.core.crypto.X3DHProtocol
import phantom.core.identity.IdentityCrypto
import phantom.core.identity.SigningPublicKey
import phantom.core.storage.LocalOneTimePreKeyRepository
import phantom.core.storage.LocalSignedPreKeyRepository
import phantom.core.storage.NoOpOpkReservationRepository
import phantom.core.storage.OpkReservationRepository
import phantom.core.storage.RatchetStateRepository
import phantom.core.storage.ReservationOutcome

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
    // Sprint 2b-B L4: the reservation repository pins an OPK in flight
    // for an inbound bootstrap derivation so a mid-derive crash leaves
    // the OPK row intact (the L6 startup sweep then mops the orphan
    // reservation up). Pre-Sprint-2b the OPK was deleted eagerly at
    // line 357 of [recipientBootstrapInMemory]; that pattern made the
    // 2026-06-15 integration LTE smoke `OpkNotFound` re-derivation
    // failure unrecoverable.
    //
    // Optional with a no-op default so existing test fixtures and the
    // Alpha 1 migration path that constructs a SessionManager without
    // wiring the new repository continue to compile. AppContainer
    // injects the SqlDelight-backed implementation; in tests, the
    // default no-op + fake LocalOneTimePreKeyRepository combination
    // reproduces the pre-Sprint-2b "no reservation tracking" shape so
    // existing assertions keep their semantics.
    private val opkReservationRepository: OpkReservationRepository = NoOpOpkReservationRepository,
    private val nowMsProvider: () -> Long = { Clock.System.now().toEpochMilliseconds() },
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
        val rawState = x3dh.initiatorHandshake4DHWithEphemeral(
            initiatorIdentityKeyPair = localIdentityKeyPair,
            recipientIdentityPublicKey = recipientIdentity,
            recipientSignedPreKey = recipientSpkPub,
            recipientOPK = recipientOpkPub,
            ephemeralKeyPair = ephemeral,
        )

        // RC-CRYPTO-PAIR-X3DH-INIT Sprint 1 (2026-06-15) — tag the
        // session record with [SessionRole.INITIATOR] explicitly. The
        // crypto layer ([LibsodiumX3DH]) returns a [RatchetState] with
        // the field's default value; the bootstrap call site is where
        // role is semantically known and therefore the right place to
        // record it. See [SessionRole] KDoc for the protocol background.
        val state = rawState.copy(role = SessionRole.INITIATOR)

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
        envelopeId: String,
        localIdentityKeyPair: DhKeyPair,
        senderIdentityPublicKeyHex: String,
        x3dhInit: X3dhInitHeader,
    ): RatchetState {
        // PR-CRYPTO-INBOUND-X3DH-REPAIR1 commit 1 (2026-05-29) — refactored
        // into a thin wrapper over [recipientBootstrapInMemory] + [saveSession]
        // so the crypto-only and crypto+persist paths share byte-identical
        // derivation logic. The existing no-session bootstrap path in
        // `DefaultMessagingService.handleDeliver` continues to call
        // [recipientBootstrap] and see the same persistence + return.
        //
        // Sprint 2b-B (2026-06-15): [recipientBootstrapInMemory] now
        // RESERVES the OPK via [opkReservationRepository] instead of
        // eagerly deleting the local pool row. The wrapper here
        // preserves the wrapper's pre-Sprint-2b-B semantic — active
        // direct save + OPK consume — by releasing the reservation and
        // deleting the local OPK row immediately after derivation. The
        // pending/active two-phase model (where consume is deferred to
        // promotion) applies to the L4 InMemory + commitBootstrap path
        // wired by [phantom.core.messaging.DefaultMessagingService] at
        // line 2569; this wrapper services the legacy no-session path
        // at DMS:2879 unchanged in user-visible behaviour.
        val state = recipientBootstrapInMemory(
            conversationId = conversationId,
            envelopeId = envelopeId,
            localIdentityKeyPair = localIdentityKeyPair,
            senderIdentityPublicKeyHex = senderIdentityPublicKeyHex,
            x3dhInit = x3dhInit,
        )
        x3dhInit.opkKeyIdHex?.let { opkId ->
            // The reservation just created by InMemory is released here
            // because the wrapper writes the active ratchet row directly
            // (no candidate-decrypt gate, no pending slot). The OPK is
            // deleted in the same logical save point — the no-session
            // bootstrap path's atomic-consume guarantee, preserved.
            opkReservationRepository.release(opkId)
            oneTimePreKeyRepository.deleteByKeyId(opkId)
        }
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
     * 2. **OPK lifecycle (Sprint 2b-B L4 — 2026-06-15 amendment).** The
     *    referenced OPK is RESERVED — not deleted — before the X3DH
     *    handshake runs. The `local_one_time_pre_key` row is preserved
     *    here; an [phantom.core.storage.OpkReservationRepository.reserve]
     *    row is created with the `envelopeId` + `conversationId` carry
     *    so the L6 startup sweep can distinguish mid-derive crash
     *    carcasses (orphan reservations) from live pending candidates
     *    (reservations whose `conversation_id` matches a pending row).
     *
     *    OPK consumption is DEFERRED to Sprint 2b-C pending->active
     *    promotion — the SOLE atomic cross-table site where the
     *    `local_one_time_pre_key` row and the `opk_reservation` row are
     *    deleted alongside the active `ratchet_state` upsert.
     *
     *    **Mental-model correction (PR #314 review C-1).** The
     *    pre-Sprint-2b KDoc at the eager-delete site argued "safe
     *    because we hold the only async reference; the pool is
     *    per-device, not concurrent." That was wrong: the
     *    `PreKeyApiClient.publishWithRetry` retry loop and
     *    `recipientBootstrapInMemory` are not concurrent threads but
     *    they DO race on the OPK pool's externally-observable state
     *    via the publish wire body (the relay's atomic replace-wholesale
     *    semantics then restored consumed OPKs to the public bundle —
     *    the 2026-06-15 integration LTE smoke shape). Sprint 2b-A
     *    closed the publish side of that race via the factory-lambda
     *    re-snapshot (L1); this method's L4 reservation closes the
     *    consume side.
     *
     *    The caller is responsible for either committing the candidate
     *    state via
     *    [phantom.core.storage.SessionTransactionRepository.commitBootstrap]
     *    on candidate-decrypt success (L4 phase 3 success) or calling
     *    [phantom.core.storage.OpkReservationRepository.release] on
     *    candidate-decrypt failure (L4 phase 3 failure). The legacy
     *    no-session bootstrap path at
     *    `DefaultMessagingService.kt:2879` uses the [recipientBootstrap]
     *    wrapper above, which preserves the wrapper's pre-Sprint-2b
     *    semantic — release reservation + delete OPK + saveSession in
     *    one logical save point.
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
        envelopeId: String,
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

        // Resolve the OPK keypair if the initiator referenced one.
        //
        // Sprint 2b-B L4 (2026-06-15): REPLACE the pre-Sprint-2b eager
        // delete here with a [OpkReservationRepository.reserve] call.
        // The reservation pins the OPK in flight for this envelope's
        // derivation and protects against:
        //   - a mid-derive crash carcass — the L6 startup sweep
        //     ([OpkReservationRepository.sweepOrphanReservations])
        //     releases reservations with no matching
        //     `pending_ratchet_state` row, leaving the local OPK row
        //     intact;
        //   - the 2026-06-15 integration LTE smoke `OpkNotFound` shape —
        //     the OPK is no longer deleted at this point, so a
        //     subsequent re-receive of an envelope referencing the same
        //     `opk_key_id_hex` re-derives instead of failing.
        // The `local_one_time_pre_key` row is NOT touched here. OPK
        // consumption is DEFERRED to Sprint 2b-C pending->active
        // promotion (the SOLE site where the OPK is permanently consumed
        // via a single SQLDelight cross-table transaction).
        //
        // The caller (currently `DefaultMessagingService.handleDeliver`
        // at line 2569 + [recipientBootstrap] wrapper) is responsible
        // for either:
        //   - committing the candidate state into the pending slot via
        //     [SessionTransactionRepository.commitBootstrap] on
        //     candidate-decrypt success (L4 phase 3 success), or
        //   - calling [OpkReservationRepository.release] on
        //     candidate-decrypt failure (L4 phase 3 failure).
        val opkKeyPair: DhKeyPair? = x3dhInit.opkKeyIdHex?.let { opkId ->
            val opk = oneTimePreKeyRepository.get(opkId)
                ?: throw SessionBootstrapException.OpkNotFound(opkId)
            val outcome = opkReservationRepository.reserve(
                opkKeyIdHex = opkId,
                envelopeId = envelopeId,
                conversationId = conversationId,
                nowMs = nowMsProvider(),
            )
            // ReservationOutcome.AlreadyReserved is treated as a no-op
            // success here — the existing reservation came from this
            // same caller on a previous derivation attempt (idempotent
            // retry). The caller will still issue its own commit /
            // release for the eventual outcome.
            @Suppress("UNUSED_VARIABLE")
            val reservationOutcome = outcome
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
        val rawState = x3dh.recipientHandshake4DH(
            recipientIdentityKeyPair = localIdentityKeyPair,
            recipientSignedPreKeyPair = spkKeyPair,
            recipientOPKPair = opkKeyPair,
            initiatorIdentityPublicKey = initiatorIdentityPub,
            initiatorEphemeralPublicKey = initiatorEphemeralPub,
        )

        // RC-CRYPTO-PAIR-X3DH-INIT Sprint 1 (2026-06-15) — tag this
        // session record with [SessionRole.RESPONDER]. This is the
        // load-bearing tag for the asymmetric-pair lacuna: an inbound
        // X3DH bootstrap produces a session whose sending chain
        // corresponds to the initiator's receiving chain, NOT a
        // generic bidirectional session. Without the tag, a later
        // outbound send call would find this record via
        // [tryLoadSession] and use it as if it were a normal existing
        // session — encrypting on the RESPONDER's sending chain while
        // the remote peer's INITIATOR ratchet expects messages from
        // the INITIATOR's sending chain. The diagnostic tag here is a
        // prerequisite for the outbound guard added in a subsequent
        // iteration; this iteration adds the tag only.
        val state = rawState.copy(role = SessionRole.RESPONDER)

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
