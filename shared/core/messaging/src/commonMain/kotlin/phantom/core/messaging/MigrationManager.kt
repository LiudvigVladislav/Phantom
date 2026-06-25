// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.benasher44.uuid.uuid4
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import kotlinx.datetime.Clock
import phantom.core.crypto.OneTimePreKey
import phantom.core.crypto.SignedPreKey
import phantom.core.crypto.SignedPreKeySigner
import phantom.core.crypto.X3DHProtocol
import phantom.core.identity.IdentityCrypto
import phantom.core.identity.IdentityManager
import phantom.core.identity.IdentitySigningKeyPair
import phantom.core.storage.ConversationRepository
import phantom.core.storage.LocalOneTimePreKeyEntity
import phantom.core.storage.LocalOneTimePreKeyRepository
import phantom.core.storage.LocalSignedPreKeyEntity
import phantom.core.storage.LocalSignedPreKeyRepository
import phantom.core.storage.RatchetStateRepository
import phantom.core.storage.SenderKeyRepository
import phantom.core.transport.PreKeyApi
import phantom.core.transport.PublishRequest
import phantom.core.transport.PublishResult
import phantom.core.transport.WireOneTimePreKey
import phantom.core.transport.WireSignedPreKey

/**
 * Drives the Alpha 1 → Alpha 2 migration described in
 * `docs/project/Alpha2_Migration.md` and ADR-009 supplement.
 *
 * Triggered on first launch of an Alpha 2 build whose local state still
 * looks like Alpha 1 — most reliably, an [phantom.core.identity.IdentityRecord]
 * whose `signingPublicKeyHex` is null (the Alpha 1 schema didn't have
 * the column; the 12.sqm migration left existing rows with the empty-
 * string sentinel that read as null on the Kotlin side).
 *
 * The flow is **idempotent**: each step checks whether it has already
 * run and skips work that's already done. A user who taps Continue,
 * then pulls the battery mid-publish, then re-launches should pick up
 * exactly where they left off without burning fresh randomness on
 * already-generated keys.
 *
 * UI integration:
 *  - The launch path inspects [needsMigration] before any messaging
 *    code runs.
 *  - If true, the platform layer shows MigrationScreen.kt with the
 *    approved copy. The user taps Continue → [runMigration] runs.
 *  - On success the screen dismisses and normal messaging starts.
 *  - On retryable failure (publish refused) the user sees an error
 *    and can tap Continue again — re-runs are safe.
 *
 * Tests live in `MigrationManagerTest.kt` (PR C commit 14).
 */
class MigrationManager(
    private val identityManager: IdentityManager,
    private val identityCrypto: IdentityCrypto,
    private val signedPreKeyRepository: LocalSignedPreKeyRepository,
    private val oneTimePreKeyRepository: LocalOneTimePreKeyRepository,
    private val ratchetStateRepository: RatchetStateRepository,
    private val senderKeyRepository: SenderKeyRepository,
    private val conversationRepository: ConversationRepository,
    private val preKeyApi: PreKeyApi,
    private val x3dh: X3DHProtocol,
    /**
     * Optional clock injection for tests. Real callers leave this at
     * the default which uses kotlinx.datetime.Clock.System.
     */
    private val nowMsProvider: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    /**
     * Returns true when the local state predates the Alpha 2 schema.
     * Idempotent — safe to call on every launch.
     *
     * Detection uses the simplest observable invariant: the
     * IdentityRecord's signing keypair is missing. Any other Alpha 1
     * leftover (ratchet states, SenderKeys) only exists for users who
     * had at least one onboarded session, so checking the identity
     * record covers both fresh-Alpha-1-installs-that-never-bootstrapped
     * and active Alpha 1 users.
     */
    suspend fun needsMigration(): Boolean {
        val record = identityManager.getIdentity() ?: return false
        // No identity yet means the user is on the onboarding path; the
        // onboarding flow (PR C commit 13 lifecycle service) will
        // generate both keypairs together for new users.
        return record.needsSigningKeyBackfill
    }

    /**
     * Run the migration. Steps in order:
     *
     *  1. Backfill the Ed25519 signing keypair on the IdentityRecord
     *     (preserves X25519 fields verbatim — see
     *     IdentityManager.backfillSigningKeyPair).
     *  2. Generate a SignedPreKey + 100 OneTimePreKeys, sign the SPK
     *     with the freshly-backfilled Ed25519 secret, persist private
     *     halves to the local prekey tables.
     *  3. Publish the bundle to the relay via `POST /prekeys/publish`.
     *     A retryable failure surfaces as [Result.failure]; non-
     *     retryable (BadRequest, SigningKeyMismatch) bubbles as a
     *     [MigrationException].
     *  4. Wipe RatchetStateRepository — Alpha 1 sessions were rooted
     *     in the F12/F15-vulnerable bootstrap and must not survive.
     *  5. Wipe SenderKeyRepository — Alpha 1 SenderKey state can't be
     *     reused under Alpha 2 group sessions.
     *  6. Mark every conversation `needsRehandshake = 1`. The chat list
     *     surfaces a "needs re-handshake" indicator and the next
     *     outbound message in such a conversation triggers the X3DH
     *     4-DH bootstrap path on its own.
     *
     * Idempotency notes:
     *  - Step 1 is idempotent (IdentityManager.backfillSigningKeyPair
     *    returns the existing keypair if already present).
     *  - Steps 2 + 3: if `signedPreKeyRepository.get()` returns non-null
     *    AND `count() >= 100`, we skip generation and re-attempt only
     *    the publish. A relay that reports SigningKeyMismatch on
     *    re-publish indicates the local keys diverged from server-side
     *    — surfaced as MigrationException.SigningKeyMismatch.
     *  - Steps 4–6 are inherently idempotent (DELETEs + UPDATE all).
     */
    suspend fun runMigration(): Result<Unit> = runCatching {
        // ── Step 1: backfill Ed25519 signing keypair ─────────────────
        val signing: IdentitySigningKeyPair = identityManager.backfillSigningKeyPair()
        val identity = identityManager.getIdentity()
            ?: throw MigrationException.NoIdentity

        // ── Step 2: generate (or reuse) prekey material ──────────────
        val existingSpk = signedPreKeyRepository.get()
        val existingOpkCount = oneTimePreKeyRepository.count()
        val (spk, opks) = if (existingSpk != null && existingOpkCount >= OPK_BATCH_SIZE) {
            // Resuming a previous interrupted run — keep what we have.
            // Re-fetch the OPKs so we can re-publish them verbatim.
            val opkEntities = oneTimePreKeyRepository.getAll().take(OPK_BATCH_SIZE)
            existingSpk to opkEntities
        } else {
            // Fresh generation. Mint SPK + OPK_BATCH_SIZE OPKs in one shot, sign
            // SPK with the just-backfilled Ed25519 secret, persist
            // private halves locally before attempting publish (so a
            // mid-publish crash doesn't lose the keypairs we'd have to
            // regenerate).
            val newSpkPair = x3dh.generateDhKeyPair()
            val newSpkKeyId = nowMsProvider() // wall-clock as monotonic id
            val newSpkCreatedAtMs = nowMsProvider()
            val sigBytes = SignedPreKeySigner.sign(
                spkPublic = newSpkPair.publicKey,
                createdAtMs = newSpkCreatedAtMs,
                identityEd25519SecretKey = signing.privateKey.bytes,
            )
            val newSpkEntity = LocalSignedPreKeyEntity(
                keyId = newSpkKeyId,
                publicKeyHex = newSpkPair.publicKey.bytes.toHex(),
                privateKeyHex = newSpkPair.privateKey.bytes.toHex(),
                createdAtMs = newSpkCreatedAtMs,
                signatureHex = sigBytes.toHex(),
            )
            signedPreKeyRepository.upsert(newSpkEntity)

            val newOpkEntities = generateOpkBatch(OPK_BATCH_SIZE)
            // Wipe the existing pool first so a half-populated retry
            // doesn't accumulate keys past the relay's MAX_OPKS_PER_IDENTITY
            // cap. After clear(), insertAll() runs as one transaction.
            oneTimePreKeyRepository.clear()
            oneTimePreKeyRepository.insertAll(newOpkEntities)
            newSpkEntity to newOpkEntities
        }

        // ── Step 3: publish bundle to relay ──────────────────────────
        val publishRequest = PublishRequest(
            identity_pubkey_hex = identity.publicKeyHex,
            signing_pubkey_hex = identity.signingPublicKeyHex
                ?: throw MigrationException.NoIdentity,
            signed_pre_key = WireSignedPreKey(
                key_id = spk.keyId,
                public_key_hex = spk.publicKeyHex,
                created_at_ms = spk.createdAtMs,
                signature_hex = spk.signatureHex,
            ),
            one_time_pre_keys = opks.map { e ->
                WireOneTimePreKey(
                    key_id_hex = e.keyIdHex,
                    public_key_hex = e.publicKeyHex,
                )
            },
        )
        // Sprint 2b L1 (PreKeyApi factory-lambda signature): the migration
        // path is single-shot and runs before any session exists, so no
        // concurrent inbound bootstrap can race the OPK pool. Capturing
        // the pre-built request by closure here satisfies the interface
        // and is operationally safe — retries replay the same body, which
        // matches pre-Sprint-2b behaviour for this isolated path. The
        // load-bearing re-snapshot contract is enforced for the steady-
        // state PreKeyLifecycleService publishes (L1 + M-2bA-1..5).
        when (val result = preKeyApi.publishBundle { publishRequest }) {
            is PublishResult.Stored -> { /* expected */ }
            // RC-PREKEY-PUBLISH-DEBOUNCE-RACE (2026-06-25): the migration
            // path is single-shot and runs before any session exists, so
            // a concurrent in-flight publish on the per-identity client
            // is not architecturally possible here. If we somehow do see
            // a Deferred (e.g. test rig wired the same client across two
            // paths), surface it as an explicit migration failure rather
            // than silently treating it as success — the pre-RC
            // synthetic `Stored(0)` path is what this track removes.
            is PublishResult.Deferred ->
                throw MigrationException.PublishUnexpected(
                    httpStatus = 0,
                    serverMessage = "publishBundle returned Deferred during migration — " +
                        "no in-flight publish is expected on this path",
                )
            is PublishResult.Failure -> {
                val reason = result.reason
                when (reason) {
                    is PublishResult.Reason.SigningKeyMismatch ->
                        throw MigrationException.SigningKeyMismatch(result.serverMessage)
                    is PublishResult.Reason.RateLimited ->
                        throw MigrationException.PublishRateLimited(result.serverMessage)
                    is PublishResult.Reason.BadRequest ->
                        throw MigrationException.PublishBadRequest(result.serverMessage)
                    is PublishResult.Reason.Unexpected ->
                        throw MigrationException.PublishUnexpected(reason.httpStatus, result.serverMessage)
                }
            }
        }

        // ── Steps 4–6: wipe and flag ─────────────────────────────────
        ratchetStateRepository.deleteAll()
        senderKeyRepository.deleteAll()
        conversationRepository.markAllNeedsRehandshake()

        Unit
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun generateOpkBatch(size: Int): List<LocalOneTimePreKeyEntity> {
        val now = nowMsProvider()
        return List(size) {
            val pair = x3dh.generateDhKeyPair()
            LocalOneTimePreKeyEntity(
                // 16 random bytes hex-encoded — same shape the relay
                // expects in WireOneTimePreKey.key_id_hex. Server doesn't
                // care about ordering; any unique-per-publish ID works.
                // ionspin's `LibsodiumRandom.buf(n)` takes an Int.
                keyIdHex = LibsodiumRandom.buf(16).toByteArray().toHex(),
                publicKeyHex = pair.publicKey.bytes.toHex(),
                privateKeyHex = pair.privateKey.bytes.toHex(),
                uploadedAtMs = now,
            )
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt().and(0xFF)) }

    companion object {
        /**
         * OPK batch size for initial publish. Sized to keep
         * `POST /prekeys/publish` body well under the 8192-byte
         * middlebox cut observed on Tele2 LTE Иркутская (2026-05-15
         * Test #44 — see docs/PROJECT_LOG.md). The relay's
         * `MAX_OPKS_PER_PUBLISH = 100` server-side cap is unchanged;
         * 40 fits inside it. Drained naturally by incoming
         * first-contacts; the lifecycle service refills when remaining
         * drops below [phantom.core.messaging.PreKeyLifecycleService.Companion.REPLENISH_THRESHOLD].
         */
        const val OPK_BATCH_SIZE: Int = 40
    }
}

/**
 * Errors surfaced by [MigrationManager.runMigration] — distinct types
 * so the platform UI can decide retry vs hard-fail without parsing
 * server message strings.
 *
 * **Retryable** (user can tap Continue again, same flow re-runs):
 *  - [PublishRateLimited]
 *  - [PublishUnexpected]
 *
 * **Non-retryable** (something is fundamentally wrong — surface a
 * "contact support" error rather than a retry button):
 *  - [NoIdentity]
 *  - [SigningKeyMismatch]
 *  - [PublishBadRequest]
 */
sealed class MigrationException(message: String) : Exception(message) {
    /**
     * Local IdentityRecord is missing entirely. Should be unreachable
     * for users on the migration path — they had to have onboarded
     * once for Alpha 1 to even reach this code.
     */
    data object NoIdentity : MigrationException("local IdentityRecord is missing")

    /**
     * Relay rejected the publish with 409 Conflict — the X25519
     * identity already has a registered Ed25519 signing key that
     * doesn't match the one we just generated. Means a previous
     * device under the same X25519 identity already migrated; this
     * device should NOT overwrite the binding (would invalidate the
     * other device's bundle for our peers).
     */
    class SigningKeyMismatch(serverMessage: String) :
        MigrationException("relay 409 SigningKeyMismatch: $serverMessage")

    class PublishRateLimited(serverMessage: String) :
        MigrationException("relay 429 RateLimited: $serverMessage")

    class PublishBadRequest(serverMessage: String) :
        MigrationException("relay 400 BadRequest: $serverMessage")

    class PublishUnexpected(httpStatus: Int, serverMessage: String) :
        MigrationException("relay HTTP $httpStatus: $serverMessage")
}
