// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import kotlinx.datetime.Clock
import phantom.core.crypto.SignedPreKeySigner
import phantom.core.crypto.X3DHProtocol
import phantom.core.identity.IdentityManager
import phantom.core.identity.IdentitySigningKeyPair
import phantom.core.storage.LocalOneTimePreKeyEntity
import phantom.core.storage.LocalOneTimePreKeyRepository
import phantom.core.storage.LocalSignedPreKeyEntity
import phantom.core.storage.LocalSignedPreKeyRepository
import phantom.core.transport.PreKeyApi
import phantom.core.transport.PublishRequest
import phantom.core.transport.PublishResult
import phantom.core.transport.WireOneTimePreKey
import phantom.core.transport.WireSignedPreKey

/**
 * Steady-state lifecycle for the user's own published prekey bundle.
 *
 * Three roles:
 *
 *  1. **Onboarding bootstrap** — [bootstrapForNewIdentity] runs once per
 *     install, immediately after the user finishes onboarding.
 *     Generates SignedPreKey + 100 OneTimePreKeys, signs the SPK with
 *     the freshly-generated Ed25519 keypair, persists locally,
 *     publishes to the relay. This is the ONLY path that creates the
 *     first bundle for an Alpha 2 user — the migration flow
 *     ([MigrationManager]) handles Alpha-1 → Alpha-2 backfill.
 *
 *  2. **OPK pool refill** — [maybeReplenishOneTimePreKeys] consults
 *     `local_one_time_pre_key.count()`. If under [REPLENISH_THRESHOLD],
 *     generates [REFILL_BATCH_SIZE] fresh OPKs, ADDS them to the local
 *     pool (does not clear), and publishes a fresh full bundle. The
 *     relay's publish endpoint REPLACES its OPK pool wholesale, so we
 *     ship every OPK we still hold so the relay sees the full pool.
 *     Called from a background coroutine on a 24-hour cadence and on
 *     every WS reconnect.
 *
 *  3. **SPK rotation** — [maybeRotateSignedPreKey] checks
 *     `current_spk.created_at_ms`. If >= [SPK_ROTATION_DAYS] old,
 *     generates a fresh SPK, signs it with the same Ed25519 identity
 *     key (no rotation of the signing key), moves the old SPK into
 *     `previous` (kept locally for the 14-day retention window so
 *     inflight handshakes still resolve), publishes a fresh full
 *     bundle. Called weekly + on every long-offline reconnect.
 *
 * Idempotency: every operation is safe to re-call. Onboarding skips if
 * SPK already exists. Replenish skips if count >= threshold. Rotation
 * skips if SPK age < threshold. A flaky network does not multiply the
 * effort.
 *
 * Wiring: AppContainer constructs one instance during [initMessaging],
 * binds it to the appScope's reconnect signal + a periodic ticker.
 * Tests inject the dependencies directly.
 */
class PreKeyLifecycleService(
    private val identityManager: IdentityManager,
    private val signedPreKeyRepository: LocalSignedPreKeyRepository,
    private val oneTimePreKeyRepository: LocalOneTimePreKeyRepository,
    private val preKeyApi: PreKeyApi,
    private val x3dh: X3DHProtocol,
    private val nowMsProvider: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    /**
     * Runs the onboarding bootstrap. Returns Result.success if the
     * bundle is now published; Result.failure carrying a
     * [MigrationException] (reused — same publish-error variants) on
     * any non-2xx from the relay.
     *
     * Idempotent: if a SignedPreKey already exists locally, this is a
     * no-op (assume the previous launch successfully published).
     */
    suspend fun bootstrapForNewIdentity(): Result<Unit> = runCatching {
        if (signedPreKeyRepository.get() != null) {
            // Already done — onboarding ran on a previous launch.
            messagingLog(
                MessagingLogLevel.INFO,
                "PREKEY_TRACE bootstrap_skip_existing_spk — local SPK already present, no publish",
            )
            return Result.success(Unit)
        }
        val identity = identityManager.getIdentity()
            ?: throw MigrationException.NoIdentity
        val signing = identityManager.loadSigningKeyPair()
            ?: throw MigrationException.NoIdentity

        val identityTag = identity.publicKeyHex.take(16)
        messagingLog(
            MessagingLogLevel.INFO,
            "PREKEY_TRACE bootstrap_start identity=$identityTag…",
        )
        val spkEntity = generateAndPersistSpk(signing)
        val opks = generateAndPersistOpks(REFILL_BATCH_SIZE, replaceExisting = true)
        publishBundle(identity.publicKeyHex, signing, spkEntity, opks)
        messagingLog(
            MessagingLogLevel.INFO,
            "PREKEY_TRACE bootstrap_done identity=$identityTag…",
        )
    }

    /**
     * Verifies the relay actually has our published bundle, and republishes
     * if it does not. Fixes the long-offline / silent-publish-failure case
     * where local state says "SPK exists, OPK pool full" but the relay
     * has no record of this identity at all (so any peer who tries to
     * fetch our bundle gets 404 and cannot start a session with us).
     *
     * The relay's `/prekeys/status/{identity}` endpoint returns
     * `signed_prekey_age_days = null` when the identity has never
     * published — that is the canonical signal we test for. Any other
     * value (including 0 for a freshly-published bundle) means the relay
     * has a record and no republish is needed.
     *
     * Returns true if a republish ran, false if the relay already had
     * our bundle. Failures bubble as [Result.failure] (rate-limit on
     * `/prekeys/status` is rare — 60/min per requester — and a status
     * failure itself does not warrant a republish, since we cannot tell
     * whether the relay is missing the bundle or just refusing to answer).
     *
     * Idempotent: repeated calls when the relay already has the bundle
     * are read-only HEAD-equivalent traffic. Safe to call on every
     * successful WS reconnect as a defence-in-depth net.
     */
    suspend fun verifyBundleOnRelay(): Result<Boolean> = runCatching {
        val identity = identityManager.getIdentity()
            ?: throw MigrationException.NoIdentity
        val signing = identityManager.loadSigningKeyPair()
            ?: throw MigrationException.NoIdentity
        val identityTag = identity.publicKeyHex.take(16)
        val spkEntity = signedPreKeyRepository.get()
            ?: run {
                // No local SPK — onboarding has not run yet for this install.
                // verifyBundleOnRelay is not the right place to drive onboarding;
                // bootstrapForNewIdentity is. Skip silently.
                messagingLog(
                    MessagingLogLevel.INFO,
                    "PREKEY_TRACE verify_skip_no_local_spk identity=$identityTag…",
                )
                return Result.success(false)
            }

        messagingLog(
            MessagingLogLevel.INFO,
            "PREKEY_TRACE verify_start identity=$identityTag…",
        )
        val status = preKeyApi.fetchStatus(
            identityPubkeyHex = identity.publicKeyHex,
            requesterPubkeyHex = identity.publicKeyHex,
        )
        messagingLog(
            MessagingLogLevel.INFO,
            "PREKEY_TRACE verify_status identity=$identityTag… " +
                "spk_age_days=${status.signed_prekey_age_days} opks_remaining=${status.remaining_opks}",
        )
        // signed_prekey_age_days == null is the relay's signal that no
        // entry exists for this identity. If an entry exists but the OPK
        // pool is empty, the relay still returns Some(age_days) and the
        // SPK-only fallback covers session bootstrap, so we do not need
        // to republish in that case (maybeReplenishOneTimePreKeys handles
        // the pool refill independently).
        if (status.signed_prekey_age_days != null) {
            return Result.success(false)
        }

        // Relay has lost (or never received) our bundle. Republish the
        // full local state — same wire shape as the onboarding bootstrap
        // and the steady-state replenish, so the relay's atomic publish
        // restores us to a known-good state in one round-trip.
        messagingLog(
            MessagingLogLevel.WARN,
            "PREKEY_TRACE verify_republish_triggered identity=$identityTag… — relay has no record",
        )
        val allOpks = oneTimePreKeyRepository.getAll()
        publishBundle(identity.publicKeyHex, signing, spkEntity, allOpks)
        true
    }

    /**
     * Refills the OPK pool when it dips below [REPLENISH_THRESHOLD].
     * Returns true if a refill ran, false if the pool was sufficiently
     * stocked. Failures bubble as [Result.failure].
     */
    suspend fun maybeReplenishOneTimePreKeys(): Result<Boolean> = runCatching {
        val remaining = oneTimePreKeyRepository.count()
        if (remaining >= REPLENISH_THRESHOLD) return Result.success(false)

        val identity = identityManager.getIdentity()
            ?: throw MigrationException.NoIdentity
        val signing = identityManager.loadSigningKeyPair()
            ?: throw MigrationException.NoIdentity
        val spkEntity = signedPreKeyRepository.get()
            ?: throw IllegalStateException(
                "OPK refill called before SPK exists — onboarding must run first",
            )

        // Mint enough new OPKs to bring us back above the threshold;
        // generate a full REFILL_BATCH_SIZE so we don't refill again
        // for a long time (each generation is one HTTP round-trip).
        generateAndPersistOpks(REFILL_BATCH_SIZE, replaceExisting = false)

        // Republish the FULL pool. The relay's POST /prekeys/publish
        // replaces its OPK list wholesale, so we have to ship every
        // local OPK we still hold (after the new ones were added).
        val allLocal = oneTimePreKeyRepository.getAll()
        publishBundle(identity.publicKeyHex, signing, spkEntity, allLocal)
        true
    }

    /**
     * Rotates the SignedPreKey when its age exceeds [SPK_ROTATION_DAYS].
     * Returns true if rotation ran, false if the current SPK is still
     * fresh.
     *
     * The previous SPK is retained on the local entity (under
     * [LocalSignedPreKeyEntity.previous]) so the recipient bootstrap
     * path can still resolve handshakes initiated against the old SPK
     * during the 14-day retention window. The relay also retains the
     * previous SPK on its side per its own policy
     * ([phantom-relay::SPK_PREVIOUS_RETENTION_DAYS]).
     */
    suspend fun maybeRotateSignedPreKey(): Result<Boolean> = runCatching {
        val current = signedPreKeyRepository.get()
            ?: throw IllegalStateException(
                "SPK rotation called before SPK exists — onboarding must run first",
            )
        val ageDays = (nowMsProvider() - current.createdAtMs).coerceAtLeast(0L) / DAY_MS
        if (ageDays < SPK_ROTATION_DAYS) return Result.success(false)

        val identity = identityManager.getIdentity()
            ?: throw MigrationException.NoIdentity
        val signing = identityManager.loadSigningKeyPair()
            ?: throw MigrationException.NoIdentity

        // Generate fresh SPK and shift current → previous.
        val newSpkPair = x3dh.generateDhKeyPair()
        val newCreatedAt = nowMsProvider()
        val newKeyId = newCreatedAt
        val newSig = SignedPreKeySigner.sign(
            spkPublic = newSpkPair.publicKey,
            createdAtMs = newCreatedAt,
            identityEd25519SecretKey = signing.privateKey.bytes,
        )
        val newEntity = LocalSignedPreKeyEntity(
            keyId = newKeyId,
            publicKeyHex = newSpkPair.publicKey.bytes.toHex(),
            privateKeyHex = newSpkPair.privateKey.bytes.toHex(),
            createdAtMs = newCreatedAt,
            signatureHex = newSig.toHex(),
            previous = LocalSignedPreKeyEntity.PreviousSignedPreKey(
                keyId = current.keyId,
                publicKeyHex = current.publicKeyHex,
                privateKeyHex = current.privateKeyHex,
                signatureHex = current.signatureHex,
                createdAtMs = current.createdAtMs,
                retiredAtMs = newCreatedAt,
            ),
        )
        signedPreKeyRepository.upsert(newEntity)

        // Republish bundle with the rotated SPK + the existing OPK pool.
        val allOpks = oneTimePreKeyRepository.getAll()
        publishBundle(identity.publicKeyHex, signing, newEntity, allOpks)
        true
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun generateAndPersistSpk(signing: IdentitySigningKeyPair): LocalSignedPreKeyEntity {
        val pair = x3dh.generateDhKeyPair()
        val createdAt = nowMsProvider()
        val sig = SignedPreKeySigner.sign(
            spkPublic = pair.publicKey,
            createdAtMs = createdAt,
            identityEd25519SecretKey = signing.privateKey.bytes,
        )
        val entity = LocalSignedPreKeyEntity(
            keyId = createdAt,
            publicKeyHex = pair.publicKey.bytes.toHex(),
            privateKeyHex = pair.privateKey.bytes.toHex(),
            createdAtMs = createdAt,
            signatureHex = sig.toHex(),
        )
        // Persist immediately so the private key survives a crash that occurs
        // between this call and the publishBundle network round-trip.
        signedPreKeyRepository.upsert(entity)
        return entity
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private suspend fun generateAndPersistOpks(
        size: Int,
        replaceExisting: Boolean,
    ): List<LocalOneTimePreKeyEntity> {
        if (replaceExisting) {
            oneTimePreKeyRepository.clear()
        }
        val now = nowMsProvider()
        val newEntities = List(size) {
            val pair = x3dh.generateDhKeyPair()
            LocalOneTimePreKeyEntity(
                keyIdHex = LibsodiumRandom.buf(16).toByteArray().toHex(),
                publicKeyHex = pair.publicKey.bytes.toHex(),
                privateKeyHex = pair.privateKey.bytes.toHex(),
                uploadedAtMs = now,
            )
        }
        oneTimePreKeyRepository.insertAll(newEntities)
        return newEntities
    }

    private suspend fun publishBundle(
        identityX25519Hex: String,
        signing: IdentitySigningKeyPair,
        spk: LocalSignedPreKeyEntity,
        opks: List<LocalOneTimePreKeyEntity>,
    ) {
        // For onboarding the SPK was just generated in this call; persist
        // it now so a publish failure leaves us with a known-good local
        // state we can republish from later.
        signedPreKeyRepository.upsert(spk)

        val identityTag = identityX25519Hex.take(16)
        val opkCount = opks.size
        messagingLog(
            MessagingLogLevel.INFO,
            "PREKEY_TRACE upload_start identity=$identityTag… opks=$opkCount spk_key_id=${spk.keyId}",
        )

        val request = PublishRequest(
            identity_pubkey_hex = identityX25519Hex,
            signing_pubkey_hex = signing.publicKey.bytes.toHex(),
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
        val startMs = nowMsProvider()
        val result = try {
            preKeyApi.publishBundle(request)
        } catch (t: Throwable) {
            val elapsed = nowMsProvider() - startMs
            messagingLog(
                MessagingLogLevel.WARN,
                "PREKEY_TRACE upload_fail identity=$identityTag… reason=throwable " +
                    "type=${t::class.simpleName} elapsedMs=$elapsed message=${t.message ?: "<null>"}",
                t,
            )
            throw t
        }
        val elapsed = nowMsProvider() - startMs
        when (result) {
            is PublishResult.Stored -> {
                messagingLog(
                    MessagingLogLevel.INFO,
                    "PREKEY_TRACE upload_ok identity=$identityTag… " +
                        "stored_opks=${result.storedOpks} elapsedMs=$elapsed",
                )
            }
            is PublishResult.Failure -> {
                val reason = result.reason
                val reasonTag = when (reason) {
                    is PublishResult.Reason.SigningKeyMismatch -> "signing_key_mismatch"
                    is PublishResult.Reason.RateLimited -> "rate_limited"
                    is PublishResult.Reason.BadRequest -> "bad_request"
                    is PublishResult.Reason.Unexpected -> "http${reason.httpStatus}"
                }
                messagingLog(
                    MessagingLogLevel.WARN,
                    "PREKEY_TRACE upload_fail identity=$identityTag… reason=$reasonTag " +
                        "elapsedMs=$elapsed server=\"${result.serverMessage.take(160)}\"",
                )
                throw when (reason) {
                    is PublishResult.Reason.SigningKeyMismatch ->
                        MigrationException.SigningKeyMismatch(result.serverMessage)
                    is PublishResult.Reason.RateLimited ->
                        MigrationException.PublishRateLimited(result.serverMessage)
                    is PublishResult.Reason.BadRequest ->
                        MigrationException.PublishBadRequest(result.serverMessage)
                    is PublishResult.Reason.Unexpected ->
                        MigrationException.PublishUnexpected(reason.httpStatus, result.serverMessage)
                }
            }
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt().and(0xFF)) }

    companion object {
        /**
         * Pool size threshold below which [maybeReplenishOneTimePreKeys]
         * fires. Mirrors ADR-009's "<20 remaining" policy.
         */
        const val REPLENISH_THRESHOLD: Int = 20

        /**
         * Number of OPKs generated per refill. Sized to keep
         * `POST /prekeys/publish` body well under the 8192-byte
         * middlebox cut observed on Tele2 LTE Иркутская (2026-05-15
         * Test #44 — see docs/PROJECT_LOG.md). With ~135 bytes per
         * OPK JSON-encoded plus SPK/identity overhead, 40 OPKs
         * produces a body of ~5.5–6.5 KB, with comfortable headroom.
         * The relay's `MAX_OPKS_PER_PUBLISH = 100` server-side cap
         * is unchanged; 40 still fits inside it. If we later need
         * larger batches, we either route the publish through SOCKS
         * (Tor/Reality) or add a server-side append/batch endpoint.
         */
        const val REFILL_BATCH_SIZE: Int = 40

        /**
         * SignedPreKey rotation cadence (per ADR-009: weekly).
         */
        const val SPK_ROTATION_DAYS: Long = 7

        const val DAY_MS: Long = 24L * 3600L * 1000L
    }
}
