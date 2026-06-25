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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // ── RC-PREKEY-PUBLISH-DEBOUNCE-RACE (2026-06-25) ───────────────
    // Per-identity per-session retry budget for the verify-driven
    // force-republish path. The mini-lock §4 Inv-NoSpinningRetry
    // requires a guard against an unbounded loop where verify_status
    // keeps reporting `spk_age_days=null AND opks_remaining=0` and
    // each call schedules a full publishWithRetry cycle. The verify
    // path itself costs an HTTP round-trip per attempt, so the
    // natural rate is low, but a stuck relay or a persistent network
    // class failure could still produce many attempts per session.
    //
    // The budget is per process lifetime (singleton ctor). Reset
    // on app restart. Initial value 5 covers a healthy reconnect
    // storm without permitting a runaway. Exhaustion logs a
    // structured warning (`verify_republish_budget_exhausted`) and
    // returns without scheduling the publish — the bundle gap is
    // left to a future app session OR an explicit user action.
    private val forceRepublishBudgetMutex = Mutex()
    private var forceRepublishCount: Int = 0

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
        generateAndPersistOpks(REFILL_BATCH_SIZE, replaceExisting = true)
        // Sprint 2b L1: even on the bootstrap path, the publish helper
        // re-snapshots the OPK pool from the DB on every retry attempt —
        // no closure over the freshly-generated `opks` list. If a
        // concurrent inbound bootstrap consumes one of these OPKs locally
        // between publish attempts, the retry sends the up-to-date pool,
        // never the stale 40-OPK snapshot. The scope-lock rejects a
        // bootstrap-path exception (see L1 + M-2bA-5).
        publishBundle(identity.publicKeyHex, signing, spkEntity) { oneTimePreKeyRepository.getAll() }
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

        // RC-PREKEY-PUBLISH-DEBOUNCE-RACE (2026-06-25). Inv-NoSpinningRetry:
        // honour the per-identity per-session budget before running the
        // force-republish path. Exhaustion logs a structured warning
        // and returns; the bundle gap is left to a future app session.
        val withinBudget = forceRepublishBudgetMutex.withLock {
            if (forceRepublishCount >= MAX_FORCE_REPUBLISH_PER_SESSION) {
                false
            } else {
                forceRepublishCount += 1
                true
            }
        }
        if (!withinBudget) {
            messagingLog(
                MessagingLogLevel.WARN,
                "PREKEY_TRACE verify_republish_budget_exhausted identity=$identityTag… " +
                    "count=$MAX_FORCE_REPUBLISH_PER_SESSION " +
                    "— skipping force-republish; bundle gap left to next app session",
            )
            return@runCatching false
        }

        // Sprint 2b L1: factory lambda — every retry re-reads the OPK
        // pool from the DB. This second call site (Round 2 security
        // Blocker 1 / D-R2-2) shares the same publish-snapshot
        // consistency contract as the bootstrap / replenish / rotate
        // sites.
        //
        // RC-PREKEY-PUBLISH-DEBOUNCE-RACE (2026-06-25). Inv-ForcePathOnZeroRecord:
        // pass `forceJoinInFlight = true` so a publishMutex hit (an
        // in-flight publish is already running) does NOT short-circuit
        // with `PublishResult.Deferred`. The verify path just established
        // that the relay has zero record for this identity, so the
        // republish MUST not be silently dropped by the debounce gate.
        publishBundle(
            identity.publicKeyHex,
            signing,
            spkEntity,
            forceJoinInFlight = true,
        ) { oneTimePreKeyRepository.getAll() }
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
        // Sprint 2b L1: factory lambda — retry re-reads the post-refill
        // pool from the DB, picking up any concurrent local consume
        // between attempts.
        publishBundle(identity.publicKeyHex, signing, spkEntity) { oneTimePreKeyRepository.getAll() }
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
        // Sprint 2b L1: factory lambda — retry re-reads the OPK pool from
        // the DB on every attempt.
        publishBundle(identity.publicKeyHex, signing, newEntity) { oneTimePreKeyRepository.getAll() }
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

    /**
     * Build + publish the bundle, retrying transient failures inside
     * [preKeyApi]. The OPK pool is supplied via [opksProvider] (Sprint 2b
     * L1): each retry attempt re-snapshots the pool from the repository,
     * so a local consume between attempts is reflected in the next
     * attempt's wire body. The pre-Sprint-2b shape passed a fixed
     * `List<LocalOneTimePreKeyEntity>` that the retry loop replayed
     * identically — the 2026-06-15 integration smoke root cause.
     *
     * The upload_start log shows an OPK count, sampled from one extra
     * [opksProvider] call before the publish so operator-readable grep
     * shape (`opks=N`) is preserved; the authoritative per-attempt
     * count is in the transport-layer `prekey_publish_start` log lines.
     */
    private suspend fun publishBundle(
        identityX25519Hex: String,
        signing: IdentitySigningKeyPair,
        spk: LocalSignedPreKeyEntity,
        forceJoinInFlight: Boolean = false,
        opksProvider: suspend () -> List<LocalOneTimePreKeyEntity>,
    ) {
        // For onboarding the SPK was just generated in this call; persist
        // it now so a publish failure leaves us with a known-good local
        // state we can republish from later.
        signedPreKeyRepository.upsert(spk)

        val identityTag = identityX25519Hex.take(16)
        // Pre-publish snapshot count for the operator-readable upload_start
        // log only. Authoritative per-attempt counts come from
        // PreKeyApiClient.publishWithRetry's prekey_publish_start lines,
        // each reflecting the L1 re-snapshot at the start of that attempt.
        val preflightOpkCount = opksProvider().size
        messagingLog(
            MessagingLogLevel.INFO,
            "PREKEY_TRACE upload_start identity=$identityTag… opks=$preflightOpkCount spk_key_id=${spk.keyId}",
        )

        val signingPubHex = signing.publicKey.bytes.toHex()
        val wireSpk = WireSignedPreKey(
            key_id = spk.keyId,
            public_key_hex = spk.publicKeyHex,
            created_at_ms = spk.createdAtMs,
            signature_hex = spk.signatureHex,
        )
        val startMs = nowMsProvider()
        val result = try {
            // Sprint 2b L1: factory lambda — preKeyApi.publishBundle
            // invokes this on every retry attempt. opksProvider() is
            // re-called each time so the wire body reflects the
            // current local pool, not a pre-loop snapshot. SPK +
            // signing key + identity hex are stable across retries
            // and are captured by closure once.
            //
            // RC-PREKEY-PUBLISH-DEBOUNCE-RACE (2026-06-25):
            // [forceJoinInFlight] propagates to the transport layer.
            // When true, a debounce hit waits for the in-flight publish
            // to complete and then runs THIS caller's own publish
            // attempt. When false (the default), a debounce hit
            // returns [PublishResult.Deferred] and the consumer below
            // logs `upload_deferred` honestly instead of the previous
            // false-success `upload_ok stored_opks=0`.
            preKeyApi.publishBundle(
                forceJoinInFlight = forceJoinInFlight,
            ) {
                val freshOpks = opksProvider()
                PublishRequest(
                    identity_pubkey_hex = identityX25519Hex,
                    signing_pubkey_hex = signingPubHex,
                    signed_pre_key = wireSpk,
                    one_time_pre_keys = freshOpks.map { e ->
                        WireOneTimePreKey(
                            key_id_hex = e.keyIdHex,
                            public_key_hex = e.publicKeyHex,
                        )
                    },
                )
            }
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
            is PublishResult.Deferred -> {
                // RC-PREKEY-PUBLISH-DEBOUNCE-RACE (2026-06-25). Honest log:
                // the publish was suppressed by the publish mutex because
                // another publish is already in-flight for this identity.
                // We do NOT claim `upload_ok stored_opks=0` here — the
                // in-flight publish may yet fail, in which case the
                // verify-driven republish path will pick it up with
                // `forceJoinInFlight = true`.
                messagingLog(
                    MessagingLogLevel.INFO,
                    "PREKEY_TRACE upload_deferred identity=$identityTag… " +
                        "elapsedMs=$elapsed reason=in_flight_publish",
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
         * RC-PREKEY-PUBLISH-DEBOUNCE-RACE (2026-06-25) Inv-NoSpinningRetry.
         * Per-identity per-session maximum number of verify-driven
         * force-republish attempts. Each attempt costs at minimum an
         * HTTP round-trip for `verify_status` plus a full
         * [PreKeyApiClient.PUBLISH_MAX_ATTEMPTS] publish retry cycle,
         * so the natural rate is already low — this constant is the
         * hard ceiling defending against a stuck relay state or a
         * persistent network class failure that would otherwise let
         * the verify path keep triggering forever. Reset on app
         * restart.
         *
         * Initial value 5 covers a healthy reconnect storm without
         * permitting a runaway. Exhaustion is logged via
         * `PREKEY_TRACE verify_republish_budget_exhausted` and the
         * verify call returns false; the bundle gap is left to a
         * future app session or an explicit user action.
         */
        const val MAX_FORCE_REPUBLISH_PER_SESSION: Int = 5

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
