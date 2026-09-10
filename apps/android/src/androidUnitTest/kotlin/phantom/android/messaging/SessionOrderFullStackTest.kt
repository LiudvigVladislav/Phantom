// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.messaging

import android.app.Application
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import phantom.android.di.runPrivacyModeTeardown
import phantom.android.transport.HybridRelayTransport
import phantom.core.crypto.EncryptedMessage
import phantom.core.crypto.LibsodiumDoubleRatchet
import phantom.core.crypto.LibsodiumX3DH
import phantom.core.crypto.RatchetState
import phantom.core.messaging.DefaultMessagingService
import phantom.core.messaging.MessagePayload
import phantom.core.messaging.PreKeyBundle
import phantom.core.messaging.SessionManager
import phantom.core.messaging.WireFrame
import phantom.core.storage.ConversationEntity
import phantom.core.storage.LocalOneTimePreKeyEntity
import phantom.core.storage.LocalSignedPreKeyEntity
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageRepository
import phantom.core.storage.SqlDelightConversationRepository
import phantom.core.storage.SqlDelightDecryptFailedEnvelopeRepository
import phantom.core.storage.SqlDelightLocalOneTimePreKeyRepository
import phantom.core.storage.SqlDelightLocalSignedPreKeyRepository
import phantom.core.storage.SqlDelightMessageRepository
import phantom.core.storage.SqlDelightOpkReservationRepository
import phantom.core.storage.SqlDelightPendingRatchetStateRepository
import phantom.core.storage.SqlDelightProcessedEnvelopeRepository
import phantom.core.storage.SqlDelightRatchetStateRepository
import phantom.core.storage.SqlDelightSessionTransactionRepository
import phantom.core.storage.db.PhantomDatabase
import phantom.core.transport.AckDeliverRequest
import phantom.core.transport.AckDeliverResponse
import phantom.core.transport.AuthSessionRequest
import phantom.core.transport.AuthSessionResponse
import phantom.core.transport.ConnectOwnership
import phantom.core.transport.ConnectWalkHandle
import phantom.core.transport.Handover
import phantom.core.transport.KtorRelayTransport
import phantom.core.transport.PollEnvelope
import phantom.core.transport.PollResponse
import phantom.core.transport.PreKeyApi
import phantom.core.transport.PreKeyStatus
import phantom.core.transport.PublishRequest
import phantom.core.transport.PublishResult
import phantom.core.transport.RestFallbackOrchestrator
import phantom.core.transport.RestFallbackResponse
import phantom.core.transport.RestEgressGate
import phantom.core.transport.RestFallbackTransport
import phantom.core.transport.RestStateMachine
import phantom.core.transport.SendRequest
import phantom.core.transport.SendResponse
import phantom.core.transport.TransportKind
import phantom.core.transport.TransportManager
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Session ordering, full composition.
 *
 * The component rig in `DefaultMessagingServiceTest` proved the crypto
 * half and the hold branch, but fed envelopes straight into the receive
 * path through a fake transport and substituted every repository. Two
 * things it therefore could not reach are exactly where the field run
 * ended up:
 *
 *  1. the REAL [HybridRelayTransport] and its REST deduplicator, which
 *     decides whether a redelivered envelope is handed to the receiver at
 *     all (`inbound_skip_pending` in the phone logs);
 *  2. the REAL SqlDelight repositories, including the pending/active
 *     session tables and the one-time-pre-key consumption that belongs to
 *     the promotion path.
 *
 * This rig uses both: a relay-shaped fake that keeps redelivering until
 * acked, the real orchestrator, the real hybrid transport, and a real
 * [PhantomDatabase] on JDBC (in-memory or reopened file). Initial delivery
 * comes from the production poll loop; recovery also exercises the owned
 * local replay worker. The relay removes copies only on the ACK path.
 *
 * The receiver starts with an EXISTING but unusable session, which is the
 * state the phone was in. That is what sends the first envelope down the
 * inbound-repair branch: candidate bootstrap in memory, commit, and
 * atomic promotion with message completion. First-contact variants also
 * require pre-key consumption to travel in the message transaction.
 *
 * No production behaviour is changed by these tests, and no MAC check or
 * key handling is relaxed anywhere.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric hosts this composition for one reason: `android.util.Log`
// is a native method on the stub android.jar, so the receive path's own
// logging would kill a plain JVM unit test before any assertion runs. The
// real PhantomApplication is not used -- it loads the SQLCipher native
// library on create, which a host JVM does not have.
@Config(sdk = [35], application = Application::class)
class SessionOrderFullStackTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val trace: MutableList<String> =
        Collections.synchronizedList(mutableListOf<String>())

    /**
     * A consistent copy of the orchestrator trace. The list is synchronized
     * per operation, which makes `add` safe from the orchestrator's threads
     * but does NOT make iteration safe: `any`, `takeLast` and `joinToString`
     * walk the list while those threads keep appending, and that raced into a
     * `ConcurrentModificationException` once in a 708-test batch. Every read
     * below goes through this snapshot; the assertions themselves are
     * unchanged.
     */
    private fun traceSnapshot(): List<String> = synchronized(trace) { trace.toList() }

    // Torn down in reverse order of construction. The orchestrator owns a
    // SupervisorJob that is NOT part of the rig's scope, so it has to be
    // closed explicitly, and everything has to be quiet before the
    // database is taken away underneath it. Registration happens as each
    // resource is created, so a rig that fails half-way is still released.
    private val orchestrators = mutableListOf<RestFallbackOrchestrator>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val drivers = mutableListOf<JdbcSqliteDriver>()

    /**
     * Owns the close ATTEMPTS. They are issued here rather than run
     * inline so the fixture can bound its WAIT on them without pretending
     * it can bound the attempt itself: `RestFallbackOrchestrator.close`
     * runs its whole body inside `withContext(NonCancellable)`, so a
     * timeout around the call site cannot interrupt it. Wrapping the call
     * in a timeout only makes the caller lie about having a bound.
     *
     * Never cancelled inside [releaseResources]: an attempt that outlives
     * its budget must still be allowed to finish, and must still be
     * owned by something.
     */
    private val closeAttempts = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = runBlocking {
        val problems = releaseResources(
            closeActions = orchestrators.map { orchestrator -> suspend { orchestrator.close() } },
            scopes = scopes.toList(),
            drivers = drivers.toList(),
        )
        orchestrators.clear()
        scopes.clear()
        drivers.clear()
        if (problems.isNotEmpty()) {
            throw AssertionError("the rig did not release cleanly: " + problems.joinToString(" | "))
        }
    }

    /**
     * Release everything the rig owns, in the only order that is safe, and
     * report what went wrong rather than hiding it.
     *
     *  1. Each close action -- in production, one orchestrator's `close`,
     *     which cancels and joins its own `SupervisorJob` -- is ISSUED
     *     into [closeAttempts] and then WAITED on under the budget. The
     *     budget bounds the wait, never the attempt: `close` is
     *     non-cancellable by construction, so an attempt that wedges
     *     keeps running, owned, while the fixture reports that release
     *     was not confirmed.
     *  2. Each scope's Job is cancelled AND JOINED. Joining is the point:
     *     a cancelled coroutine reports `isActive == false` while its
     *     `finally` is still running, so polling `children.any { isActive }`
     *     returns "quiet" over code that is still touching the database.
     *  3. The database is closed ONLY when every step above is CONFIRMED
     *     finished. An unconfirmed release leaves the driver open on
     *     purpose -- closing it under something still running is the
     *     failure this guard exists to prevent.
     */
    private suspend fun releaseResources(
        closeActions: List<suspend () -> Unit>,
        scopes: List<CoroutineScope>,
        drivers: List<JdbcSqliteDriver>,
        quiesceMs: Long = TEARDOWN_QUIESCE_MS,
        /**
         * Filled with the attempts this call issued, so a caller can
         * wait for the SAME ones later. An attempt that outlived its
         * budget is still running and still owned; whoever wants to
         * close the database afterwards has to join it, not re-issue it.
         */
        issuedCloseAttempts: MutableList<Job>? = null,
    ): List<String> {
        val problems = mutableListOf<String>()

        val issued = closeActions.map { action ->
            closeAttempts.async { runCatching { action() } }
        }
        issuedCloseAttempts?.addAll(issued)
        val outcomes = withTimeoutOrNull(quiesceMs) { issued.map { it.await() } }
        var closesConfirmed = outcomes != null
        if (outcomes == null) {
            problems += "a close attempt did not answer within ${quiesceMs}ms; " +
                "it is still running and still owned"
        } else {
            outcomes.forEachIndexed { index, outcome ->
                outcome.onFailure {
                    closesConfirmed = false
                    problems += "close[$index] threw ${it::class.simpleName}: ${it.message}"
                }
            }
        }

        val scopesQuiesced = withTimeoutOrNull(quiesceMs) {
            scopes.forEach { scope ->
                val job = scope.coroutineContext[Job]
                if (job == null) {
                    scope.cancel()
                } else {
                    job.cancelAndJoin()
                }
            }
            true
        } ?: false
        if (!scopesQuiesced) problems += "scopes did not finish within ${quiesceMs}ms"

        if (!closesConfirmed || !scopesQuiesced) {
            problems += "database left open on purpose: release was not confirmed"
            return problems
        }

        drivers.forEach { driver ->
            runCatching { driver.close() }.onFailure {
                problems += "driver.close threw ${it::class.simpleName}: ${it.message}"
            }
        }
        return problems
    }

    // ── A relay that behaves like the real one about redelivery ─────────

    /**
     * Holds envelopes until they are acked and keeps offering the unacked
     * ones, round-robin, one per poll -- the documented server contract
     * ("the server retains the envelope until the client sends
     * ackDeliver; subsequent poll calls keep returning the same envelope
     * until acked").
     *
     * Round-robin rather than strict head-of-line so a later envelope can
     * arrive while an earlier one is outstanding, which is what the field
     * run showed.
     *
     * [onAck] runs INSIDE `ackDeliver`, so the rig can observe what was
     * already durable at the instant the ack left the client. That is the
     * only place where ACK-after-persistence can be measured: asserting
     * "the row appeared" and then "the ack appeared" passes even when the
     * ack came first.
     */
    private class FakeRelay(
        private val onAck: suspend (String) -> Unit = {},
    ) : RestFallbackTransport {
        private val queued = Collections.synchronizedList(mutableListOf<PollEnvelope>())
        val acked: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        /** When true the ack request is lost in transit: the commit stands without it. */
        val dropAcks = java.util.concurrent.atomic.AtomicBoolean(false)
        val polls = AtomicInteger(0)
        val withheld = Collections.synchronizedSet(mutableSetOf<String>())
        private var cursor = 0

        fun enqueue(env: PollEnvelope) {
            synchronized(queued) { queued.add(env) }
        }

        fun outstanding(): List<String> = synchronized(queued) { queued.map { it.id } }

        private fun nextEnvelope(): PollEnvelope? = synchronized(queued) {
            val available = queued.filter { it.id !in withheld }
            if (available.isEmpty()) return null
            if (cursor >= available.size) cursor = 0
            available[cursor++]
        }

        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> = RestFallbackResponse(
            statusCode = 200,
            bodyParsed = AuthSessionResponse(
                token = "test-token",
                expiresAt = Long.MAX_VALUE / 2,
                restFallback = true,
                maxSendBodyBytes = 1_000_000,
                pollMaxEnvelopes = 1,
            ),
            rawBody = "{}",
            elapsedMs = 1L,
        )

        override suspend fun send(
            url: String,
            token: String,
            idempotencyKey: String,
            body: SendRequest,
        ): RestFallbackResponse<SendResponse> =
            error("the receive-side rig never sends")

        override suspend fun poll(
            url: String,
            token: String,
            sinceSeq: Long?,
            longPollOptIn: Boolean,
            readTimeoutMs: Long?,
        ): RestFallbackResponse<PollResponse> {
            polls.incrementAndGet()
            val env = nextEnvelope()
            return RestFallbackResponse(
                statusCode = 200,
                bodyParsed = PollResponse(envelopes = listOfNotNull(env), more = false),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        }

        override suspend fun ackDeliver(
            url: String,
            token: String,
            body: AckDeliverRequest,
        ): RestFallbackResponse<AckDeliverResponse> {
            onAck(body.id)
            if (dropAcks.get()) {
                return RestFallbackResponse(
                    statusCode = 500,
                    bodyParsed = null,
                    rawBody = "{}",
                    elapsedMs = 1L,
                )
            }
            acked.add(body.id)
            synchronized(queued) {
                queued.removeAll { it.id == body.id }
                cursor = 0
            }
            return RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AckDeliverResponse(ok = 1),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        }
    }

    private object NoPreKeyApi : PreKeyApi {
        override suspend fun publishBundle(
            forceJoinInFlight: Boolean,
            requestProvider: suspend () -> PublishRequest,
        ): PublishResult = error("the receive-side rig never publishes")
        override suspend fun fetchBundle(
            identityPubkeyHex: String,
            requesterPubkeyHex: String?,
        ): phantom.core.transport.PreKeyBundle? =
            error("the receive-side rig never fetches a bundle")
        override suspend fun fetchStatus(
            identityPubkeyHex: String,
            requesterPubkeyHex: String?,
        ): PreKeyStatus = error("the receive-side rig never fetches status")
    }

    /**
     * Wraps the real repository so one envelope's insert fails. Kept for
     * the legacy (non-transactional) path.
     */
    private class FailingInsertFor(
        private val delegate: MessageRepository,
        private val failForId: String,
    ) : MessageRepository by delegate {
        override suspend fun insertMessage(message: MessageEntity) {
            if (message.id == failForId) error("storage refused this row")
            delegate.insertMessage(message)
        }
    }

    /**
     * Makes the ATOMIC commit fail for one envelope, and lets the test
     * clear the failure again. Storage outages end; the contract has to
     * survive both halves of that.
     */
    private class FailingCommitFor(
        private val delegate: phantom.core.storage.SessionTransactionRepository,
        val failForId: java.util.concurrent.atomic.AtomicReference<String?>,
        /** Make `commitBootstrap` answer false, as a lost reservation would. */
        val refuseBootstrapCommit: java.util.concurrent.atomic.AtomicBoolean =
            java.util.concurrent.atomic.AtomicBoolean(false),
        /** Runs just before the commit, to make a reservation vanish under it. */
        val beforeCommit: java.util.concurrent.atomic.AtomicReference<(suspend () -> Unit)?> =
            java.util.concurrent.atomic.AtomicReference(null),
        val repeatBeforeCommit: java.util.concurrent.atomic.AtomicBoolean =
            java.util.concurrent.atomic.AtomicBoolean(false),
        val afterCommit: java.util.concurrent.atomic.AtomicReference<(suspend () -> Unit)?> =
            java.util.concurrent.atomic.AtomicReference(null),
    ) : phantom.core.storage.SessionTransactionRepository by delegate {
        val failArchiveReads = java.util.concurrent.atomic.AtomicBoolean(false)
        override suspend fun listReceiveArchives(conversationId: String, nowMs: Long): List<phantom.core.storage.ReceiveSessionArchive> {
            if (failArchiveReads.get()) error("archive cipher unavailable")
            return delegate.listReceiveArchives(conversationId, nowMs)
        }
        override suspend fun commitBootstrap(
            opkKeyIdHex: String,
            conversationId: String,
            stateBlob: String,
            bootstrapArtifactsBlob: String?,
        ): Boolean {
            if (refuseBootstrapCommit.get()) return false
            return delegate.commitBootstrap(
                opkKeyIdHex = opkKeyIdHex,
                conversationId = conversationId,
                stateBlob = stateBlob,
                bootstrapArtifactsBlob = bootstrapArtifactsBlob,
            )
        }

        override suspend fun commitInboundMessage(
            conversationId: String,
            envelopeId: String,
            senderPubKeyHex: String,
            payloadType: String,
            nowMs: Long,
            message: MessageEntity,
            advancedStateBlob: String?,
            promotePending: Boolean,
            expectedOpkKeyIdHex: String?,
            stateTarget: phantom.core.storage.InboundStateTarget,
        ): phantom.core.storage.InboundCommitOutcome {
            if (envelopeId == failForId.get()) error("storage is unavailable")
            (if (repeatBeforeCommit.get()) beforeCommit.get() else beforeCommit.getAndSet(null))?.invoke()
            return delegate.commitInboundMessage(
                conversationId = conversationId,
                envelopeId = envelopeId,
                senderPubKeyHex = senderPubKeyHex,
                payloadType = payloadType,
                nowMs = nowMs,
                message = message,
                advancedStateBlob = advancedStateBlob,
                promotePending = promotePending,
                expectedOpkKeyIdHex = expectedOpkKeyIdHex,
                stateTarget = stateTarget,
            ).also { afterCommit.get()?.invoke() }
        }
    }

    // ── The rig ─────────────────────────────────────────────────────────

    /**
     * Everything a second instance of the receive stack needs in order to
     * be the SAME receiver over the SAME database: identities, the
     * sender's frames, and where the file lives. Restarting is what makes
     * a recovery claim measurable -- a new service object over a database
     * that already holds committed state.
     */
    private class RigSeed(
        val bobIdentity: phantom.core.identity.IdentityRecord,
        val bobKp: phantom.core.crypto.DhKeyPair,
        val convId: String,
        val opkIdHex: String,
        val payloads: Map<String, String>,
        val sealedSender: String,
        val dbFile: java.io.File?,
    )

    private class Rig(
        val seed: RigSeed,
        val relay: FakeRelay,
        val convId: String,
        val messages: SqlDelightMessageRepository,
        val held: SqlDelightDecryptFailedEnvelopeRepository,
        val processed: SqlDelightProcessedEnvelopeRepository,
        val conversations: SqlDelightConversationRepository,
        val opks: SqlDelightLocalOneTimePreKeyRepository,
        val pending: SqlDelightPendingRatchetStateRepository,
        val reservations: SqlDelightOpkReservationRepository,
        val opkIdHex: String,
        val tx: FailingCommitFor,
        /** false = the privacy authority refuses REST egress for this receiver. */
        val egressAllowed: java.util.concurrent.atomic.AtomicBoolean,
        /** The real gate, so a case can revoke it the way a privacy switch does. */
        val egressGate: RestEgressGate,
        /** Set to an envelope id to make its atomic commit fail; null to heal. */
        val failCommitFor: java.util.concurrent.atomic.AtomicReference<String?>,
        /**
         * Set to a probe point name (`after_state`, `after_message`) to
         * make the REAL transaction throw there, after it has already
         * issued some of its writes. Null disarms.
         */
        val failInTransactionAt: java.util.concurrent.atomic.AtomicReference<String?>,
        val failedTransactionPoints: java.util.concurrent.ConcurrentLinkedQueue<String>,
        val ratchetStates: SqlDelightRatchetStateRepository,
        /** envelopeId -> was the row already durable when the ack was sent. */
        val ackObservations: MutableList<Pair<String, Boolean>>,
        val failHeldStorage: (Boolean) -> Unit,
        val transport: phantom.core.transport.RelayTransport,
        val deliver: (which: String, messageId: String) -> Unit,
    ) {
        suspend fun texts(): List<String?> =
            messages.getMessages(convId).map { it.plaintextCache }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun buildRig(
        failInsertForEnvelope: String? = null,
        failCommitForEnvelope: String? = null,
        failTransactionAt: String? = null,
        /** Leave the conversation with no session at all: first contact. */
        freshContact: Boolean = false,
        withSecondChain: Boolean = false,
        /** Null = a private in-memory database; a file = one that survives a restart. */
        dbFile: java.io.File? = null,
        /** Non-null = reopen an existing database as the same receiver. */
        reuse: RigSeed? = null,
    ): Rig {
        com.ionspin.kotlin.crypto.LibsodiumInitializer.initialize()
        ShadowLog.clear()
        val x3dh = LibsodiumX3DH()
        val ratchet = LibsodiumDoubleRatchet()

        val file = dbFile ?: reuse?.dbFile
        val driver = if (file == null) {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        } else {
            JdbcSqliteDriver("jdbc:sqlite:" + file.absolutePath)
        }
        drivers += driver
        if (reuse == null) PhantomDatabase.Schema.create(driver)
        val db = PhantomDatabase(driver)

        val realMessages = SqlDelightMessageRepository(db)
        val conversations = SqlDelightConversationRepository(db)
        val processed = SqlDelightProcessedEnvelopeRepository(db)
        val held = SqlDelightDecryptFailedEnvelopeRepository(db)
        val ratchetStates = SqlDelightRatchetStateRepository(db)
        val pending = SqlDelightPendingRatchetStateRepository(db)
        val reservations = SqlDelightOpkReservationRepository(db)
        val failCommitFor =
            java.util.concurrent.atomic.AtomicReference<String?>(failCommitForEnvelope)
        val failInTransactionAt = java.util.concurrent.atomic.AtomicReference<String?>(failTransactionAt)
        val failedTransactionPoints = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val sessionTx = FailingCommitFor(
            SqlDelightSessionTransactionRepository(
                db,
                transactionProbe = { point ->
                    if (point == failInTransactionAt.get()) {
                        failedTransactionPoints.add(point)
                        error("storage failed inside the transaction at $point")
                    }
                },
            ),
            failCommitFor,
        )
        val spks = SqlDelightLocalSignedPreKeyRepository(db)
        val opks = SqlDelightLocalOneTimePreKeyRepository(db)

        // First open generates the identities and the frames and seeds
        // the tables; a reopen takes them from the seed and leaves the
        // database exactly as the previous instance committed it.
        val seed: RigSeed = reuse ?: run {
            // ── Bob's published key material, in the real tables ────────────
            val aliceKp = x3dh.generateDhKeyPair()
            val bobKp = x3dh.generateDhKeyPair()
            val bobSpk = x3dh.generateDhKeyPair()
            val bobOpk = x3dh.generateDhKeyPair()
            val bobSigning = com.ionspin.kotlin.crypto.signature.Signature.keypair()
            val opkIdHex = "0a1b2c3d4e5f60718293a4b5c6d7e8f9"

            spks.upsert(
                LocalSignedPreKeyEntity(
                    keyId = 7L,
                    publicKeyHex = bobSpk.publicKey.bytes.toHexLower(),
                    privateKeyHex = bobSpk.privateKey.bytes.toHexLower(),
                    createdAtMs = 1_000L,
                    signatureHex = "00".repeat(64),
                ),
            )
            opks.insert(
                LocalOneTimePreKeyEntity(
                    keyIdHex = opkIdHex,
                    publicKeyHex = bobOpk.publicKey.bytes.toHexLower(),
                    privateKeyHex = bobOpk.privateKey.bytes.toHexLower(),
                    uploadedAtMs = 0L,
                ),
            )

            // ── Alice bootstraps against that bundle ────────────────────────
            val aliceDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            drivers += aliceDriver
            PhantomDatabase.Schema.create(aliceDriver)
            val aliceSessions = SessionManager(
                x3dh = x3dh,
                ratchetStateRepository = SqlDelightRatchetStateRepository(PhantomDatabase(aliceDriver)),
                signedPreKeyRepository = spks,
                oneTimePreKeyRepository = opks,
                identityCrypto = phantom.core.identity.LibsodiumIdentityCrypto(),
                json = json,
            )
            val bundle = PreKeyBundle(
                identityPubkeyHex = bobKp.publicKey.bytes.toHexLower(),
                signingPubkeyHex = bobSigning.publicKey.toByteArray().toHexLower(),
                signedPreKeyId = 7L,
                signedPreKeyPublicHex = bobSpk.publicKey.bytes.toHexLower(),
                signedPreKeyCreatedAtMs = 1_000L,
                signedPreKeySignatureHex = phantom.core.crypto.SignedPreKeySigner.sign(
                    bobSpk.publicKey,
                    1_000L,
                    bobSigning.secretKey.toByteArray(),
                ).toHexLower(),
                oneTimePreKeyIdHex = opkIdHex,
                oneTimePreKeyPublicHex = bobOpk.publicKey.bytes.toHexLower(),
            )
            val bootstrap = aliceSessions.initiatorBootstrap(
                conversationId = "alice-side-full-stack",
                localIdentityKeyPair = aliceKp,
                bundle = bundle,
            )

            // Four messages on ONE sending chain, encrypted once and kept, so
            // a tampered copy can be built for a chosen position without
            // consuming another position.
            var aliceState = bootstrap.ratchetState
            fun encrypt(text: String, at: Long): EncryptedMessage {
                val payload = json.encodeToString(
                    MessagePayload.serializer(),
                    MessagePayload(text = text, sentAt = at, senderUsername = "alice"),
                ).encodeToByteArray()
                val (advanced, encrypted) = ratchet.encrypt(aliceState, payload)
                aliceState = advanced
                return encrypted
            }
            val encryptedS = encrypt(TEXT_S, 1_700_000_000_000L)
            val encryptedA = encrypt(TEXT_A, 1_700_000_001_000L)
            val encryptedB = encrypt(TEXT_B, 1_700_000_002_000L)
            val encryptedC = encrypt(TEXT_C, 1_700_000_003_000L)
            val encryptedD = encrypt("D", 1_700_000_004_000L)
            val encryptedE = encrypt("E", 1_700_000_005_000L)

            fun frameOf(encrypted: EncryptedMessage): String {
                val wire = WireFrame(
                    encryptedMessage = encrypted,
                    x3dhInit = bootstrap.x3dhInit,
                    senderSigningPublicKeyHex = "ee".repeat(32),
                )
                val bytes = json.encodeToString(WireFrame.serializer(), wire).encodeToByteArray()
                return Base64.encode(phantom.core.crypto.MessagePadding.pad(bytes))
            }
            // A forgery of A: same chain position, same header, one flipped
            // ciphertext byte. Delivered where A belongs, so its rejection can
            // only be the authenticity check and never the ordering defect.
            val forgedA = encryptedA.copy(
                ciphertext = encryptedA.ciphertext.copyOf()
                    .also { it[0] = (it[0].toInt() xor 0x01).toByte() },
            )
            val forgedS = encryptedS.copy(ciphertext = encryptedS.ciphertext.copyOf()
                .also { it[0] = (it[0].toInt() xor 0x01).toByte() })
            // The same ciphertext offered WITHOUT an X3DH header: the
            // shape a peer sends once it believes its bootstrap is done.
            fun headerlessFrameOf(encrypted: EncryptedMessage): String {
                val wire = WireFrame(
                    encryptedMessage = encrypted,
                    x3dhInit = null,
                    senderSigningPublicKeyHex = "ee".repeat(32),
                )
                val bytes = json.encodeToString(WireFrame.serializer(), wire).encodeToByteArray()
                return Base64.encode(phantom.core.crypto.MessagePadding.pad(bytes))
            }
            val payloads = mutableMapOf(
                "A_NO_HEADER" to headerlessFrameOf(encryptedA),
                "S" to frameOf(encryptedS),
                "A" to frameOf(encryptedA),
                "B" to frameOf(encryptedB),
                "C" to frameOf(encryptedC),
                "D" to frameOf(encryptedD),
                "E" to frameOf(encryptedE),
                "A_FORGED" to frameOf(forgedA),
                "S_FORGED" to frameOf(forgedS),
            )
            if (withSecondChain) {
                val secondOpk = x3dh.generateDhKeyPair()
                val secondOpkId = "102132435465768798a9bacbdcedfe0f"
                opks.insert(LocalOneTimePreKeyEntity(
                    keyIdHex = secondOpkId,
                    publicKeyHex = secondOpk.publicKey.bytes.toHexLower(),
                    privateKeyHex = secondOpk.privateKey.bytes.toHexLower(),
                    uploadedAtMs = 0L,
                ))
                val secondBootstrap = aliceSessions.initiatorBootstrap(
                    conversationId = "alice-side-second-chain",
                    localIdentityKeyPair = aliceKp,
                    bundle = bundle.copy(
                        oneTimePreKeyIdHex = secondOpkId,
                        oneTimePreKeyPublicHex = secondOpk.publicKey.bytes.toHexLower(),
                    ),
                )
                var secondState = secondBootstrap.ratchetState
                for (index in 0..2) {
                    val plaintext = json.encodeToString(MessagePayload.serializer(),
                        MessagePayload(text = "NEW$index", sentAt = 1_700_001_000_000L + index,
                            senderUsername = "alice")).encodeToByteArray()
                    val (advanced, encrypted) = ratchet.encrypt(secondState, plaintext)
                    secondState = advanced
                    val wire = WireFrame(encryptedMessage = encrypted,
                        x3dhInit = secondBootstrap.x3dhInit,
                        senderSigningPublicKeyHex = "ee".repeat(32))
                    payloads["NEW$index"] = Base64.encode(phantom.core.crypto.MessagePadding.pad(
                        json.encodeToString(WireFrame.serializer(), wire).encodeToByteArray()))
                    if (index == 1) {
                        payloads["NEW1_NO_HEADER"] = Base64.encode(phantom.core.crypto.MessagePadding.pad(
                            json.encodeToString(WireFrame.serializer(), wire.copy(x3dhInit = null)).encodeToByteArray()))
                        val forged = wire.copy(encryptedMessage = encrypted.copy(
                            ciphertext = encrypted.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
                        payloads["NEW1_FORGED"] = Base64.encode(phantom.core.crypto.MessagePadding.pad(
                            json.encodeToString(WireFrame.serializer(), forged).encodeToByteArray()))
                    }
                }
            }
            val sealedSender = Base64.encode(
                phantom.core.crypto.SealedSender.seal(
                    fromPubKeyHex = aliceKp.publicKey.bytes.toHexLower(),
                    toPublicKeyBytes = bobKp.publicKey.bytes,
                ),
            )

            // ── Bob's real receive stack ────────────────────────────────────
            val bobIdentity = phantom.core.identity.IdentityRecord(
                id = "bob-full-stack",
                username = "bob",
                publicKeyHex = bobKp.publicKey.bytes.toHexLower(),
                dhPrivateKeyHex = bobKp.privateKey.bytes.toHexLower(),
                createdAt = 0L,
            )
            val convId = listOf(bobIdentity.publicKeyHex, aliceKp.publicKey.bytes.toHexLower())
                .sorted().let { "${it[0]}_${it[1]}" }
            conversations.upsertConversation(
                ConversationEntity(
                    id = convId,
                    theirUsername = "alice",
                    theirPublicKeyHex = aliceKp.publicKey.bytes.toHexLower(),
                    lastMessagePreview = "",
                    lastMessageAt = 0L,
                    unreadCount = 0,
                    sessionSuspect = false,
                    sessionSuspectSetAtMs = null,
                ),
            )
            // A session that EXISTS and cannot decrypt anything from this
            // sender. This is what the phone had, and it is what makes the
            // first envelope take the inbound-repair branch -- candidate
            // bootstrap in memory, commitBootstrap, promotePendingToActive --
            // instead of the first-contact bootstrap, which deletes the
            // one-time pre-key itself and never touches the pending tables.
            if (!freshContact) ratchetStates.upsertRatchetState(
                convId,
                json.encodeToString(
                    RatchetState.serializer(),
                    RatchetState(
                        rootKey = ByteArray(32) { 0x11 },
                        sendingChainKey = ByteArray(32) { 0x22 },
                        receivingChainKey = ByteArray(32) { 0x33 },
                        sendingRatchetPublicKey = ByteArray(32) { 0x44 },
                        sendingRatchetPrivateKey = ByteArray(32) { 0x55 },
                        receivingRatchetPublicKey = ByteArray(32) { 0x66 },
                    ),
                ),
            )

            RigSeed(
                bobIdentity = bobIdentity,
                bobKp = bobKp,
                convId = convId,
                opkIdHex = opkIdHex,
                payloads = payloads,
                sealedSender = sealedSender,
                dbFile = file,
            )
        }
        val bobIdentity = seed.bobIdentity
        val bobKp = seed.bobKp
        val convId = seed.convId
        val opkIdHex = seed.opkIdHex
        val payloads = seed.payloads
        val sealedSender = seed.sealedSender

        val bobSessions = SessionManager(
            x3dh = x3dh,
            ratchetStateRepository = ratchetStates,
            signedPreKeyRepository = spks,
            oneTimePreKeyRepository = opks,
            identityCrypto = phantom.core.identity.LibsodiumIdentityCrypto(),
            json = json,
            opkReservationRepository = reservations,
        )

        val ackObservations: MutableList<Pair<String, Boolean>> =
            Collections.synchronizedList(mutableListOf())
        // Starts allowed: the existing completion cases must read exactly
        // as they did before the privacy authority joined this rig.
        val egressAllowed = java.util.concurrent.atomic.AtomicBoolean(true)
        // The privacy authority of the accepted privacy line, wired into
        // the composition rather than stubbed away. It answers "allowed"
        // by default, so every completion case reads exactly as it did
        // before the integration; the revocation cases flip the policy
        // and, where the contract asks for it, revoke the gate itself
        // through the production teardown sequence.
        val egressGate = RestEgressGate(
            phantom.core.transport.RestEgressPolicy {
                if (egressAllowed.get()) {
                    phantom.core.transport.RestEgressDecision.DirectAllowed
                } else {
                    phantom.core.transport.RestEgressDecision.GhostUserTrafficBlocked
                }
            },
            log = { line -> trace.add(line) },
        )
        val relay = FakeRelay(
            onAck = { id ->
                // The durable state read at the instant of the ack.
                ackObservations.add(id to (realMessages.getMessageById(id) != null))
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val ws = KtorRelayTransport(
            httpClientFactory = { error("the rig must never open a websocket") },
        )
        val orchestrator = RestFallbackOrchestrator(
            baseUrl = "https://relay.test",
            identityHex = bobIdentity.publicKeyHex,
            signingPubkeyHex = "bb".repeat(32),
            getChallenge = { "cc".repeat(32) },
            signChallenge = { ByteArray(64) { 0xDD.toByte() } },
            transport = relay,
            now = { 0L },
            log = { line -> trace.add(line) },
            // Same flags the existing hybrid integration test uses.
            // Without them the Mode-2 signature is only observed and the
            // poll loop stays stopped with `reason=ws_active`.
            mode2FastPathEnabled = true,
            mode2StickyEnabled = true,
            reconnectQuiescenceEnabled = true,
            currentKindProvider = { TransportKind.Direct },
            egressGate = egressGate,
        ).also { orchestrators += it }
        val hybrid = HybridRelayTransport(
            wsTransport = ws,
            orchestrator = orchestrator,
            processedEnvelopeRepository = processed,
            scope = scope,
            nowMs = { 0L },
        )
        val service = DefaultMessagingService(
            identity = bobIdentity,
            localKeyPair = bobKp,
            ratchet = LibsodiumDoubleRatchet(),
            sessionManager = bobSessions,
            transport = hybrid,
            messageRepository = failInsertForEnvelope
                ?.let { FailingInsertFor(realMessages, it) }
                ?: realMessages,
            conversationRepository = conversations,
            processedEnvelopeRepository = processed,
            scope = scope,
            json = json,
            preKeyApi = NoPreKeyApi,
            signingKeyProvider = { null },
            decryptFailedEnvelopeRepository = held,
            holdMacFailures = true,
            sessionTransactionRepository = sessionTx,
            opkReservationRepository = reservations,
            pendingRatchetStateRepository = pending,
        )
        service.startReceiving()
        hybrid.bootstrapAndStart()
        // The poll loop only runs while the state machine believes the
        // websocket is not carrying traffic. The rig has no websocket at
        // all, so it reports the Mode-2 close signature, which is the
        // production path into `RestMode.RestActive`.
        orchestrator.submitEvent(
            RestStateMachine.Event.WsSessionEnded(
                durationMs = 30_000L,
                inboundFrames = 0,
                pendingAcksAtClose = 0,
                okhttpPingTimeoutDetected = true,
                sessionEpoch = 1L,
            ),
        )

        var seq = 1L
        return Rig(
            seed = seed,
            relay = relay,
            convId = convId,
            messages = realMessages,
            held = held,
            processed = processed,
            conversations = conversations,
            opks = opks,
            pending = pending,
            reservations = reservations,
            opkIdHex = opkIdHex,
            tx = sessionTx,
            egressAllowed = egressAllowed,
            egressGate = egressGate,
            failCommitFor = failCommitFor,
            failInTransactionAt = failInTransactionAt,
            failedTransactionPoints = failedTransactionPoints,
            ratchetStates = ratchetStates,
            ackObservations = ackObservations,
            transport = hybrid,
            failHeldStorage = { fail ->
                driver.execute(null, if (fail)
                    "CREATE TEMP TRIGGER refuse_held BEFORE INSERT ON decrypt_failed_envelopes " +
                        "BEGIN SELECT RAISE(ABORT, 'held storage unavailable'); END"
                else "DROP TRIGGER IF EXISTS refuse_held", 0)
            },
            deliver = { which, messageId ->
                relay.enqueue(
                    PollEnvelope(
                        id = messageId,
                        fromHex = "",
                        sealedSenderBase64 = sealedSender,
                        payloadBase64 = payloads.getValue(which),
                        sequenceTs = 1_000L + seq,
                        seq = seq++,
                        seqMac = "",
                    ),
                )
            },
        )
    }

    // ── Waiting helpers. Real time, because the poll loop is real. ──────

    private suspend fun awaitTrue(
        what: String,
        timeoutMs: Long = AWAIT_TIMEOUT_MS,
        predicate: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(50L)
        }
        val dump = traceSnapshot().joinToString(separator = " ~ ").take(3000)
        throw AssertionError(
            "timed out after " + timeoutMs + "ms waiting for: " + what +
                " | orchestrator trace: " + dump +
                " | receiver log tail: " + receiverLogTail(),
        )
    }

    /**
     * Diagnostic only, read at the point of failure. The orchestrator trace
     * above stops at the transport's poll loop; everything after it -- the
     * Hybrid dedup decision, the receive collector, decrypt, commit and the
     * ACK request -- is written through Android `Log`, which Robolectric
     * keeps in `ShadowLog` and never copies into the JUnit XML. Without this
     * tail a timeout cannot say where the envelope stopped.
     */
    private fun receiverLogTail(limit: Int = 80): String =
        ShadowLog.getLogs()
            .filter { item ->
                item.tag == "PhantomHybrid" || item.tag == "PhantomMessaging" ||
                    (item.msg?.let { m ->
                        m.contains("REST_TRACE") || m.contains("DECRYPT_TRACE") ||
                            m.contains("RECV_DIAG") || m.contains("CONTROL_SETTLE")
                    } ?: false)
            }
            .takeLast(limit)
            .joinToString(separator = " ~ ") { item -> item.tag + ": " + (item.msg ?: "") }
            .take(6000)

    /** Let the poll loop run for at least [polls] more rounds. */
    private suspend fun letPollsElapse(rig: Rig, polls: Int) {
        val target = rig.relay.polls.get() + polls
        awaitTrue("$polls more polls", timeoutMs = POLL_INTERVAL_MS * (polls + 3)) {
            rig.relay.polls.get() >= target
        }
    }

    private fun logLines(marker: String): List<String> =
        ShadowLog.getLogs().mapNotNull { it.msg }.filter { it.contains(marker) }

    // ── 1. The promotion path, and the ordered positive control ─────────

    /**
     * The first envelope lands through the inbound-repair branch on a
     * session that exists but cannot decrypt: candidate bootstrap,
     * commit, and promotion. The one-time pre-key is consumed by THAT
     * path, with its reservation cleared and its pending row gone --
     * which "the pre-key is missing" alone could not tell apart from a
     * first-contact bootstrap.
     */
    @Test
    fun full_stack_first_envelope_takes_the_promotion_path() = runBlocking {
        val rig = buildRig()
        rig.deliver("S", "env-S")
        awaitTrue("S stored") { rig.texts().contains(TEXT_S) }
        awaitTrue("S acked") { "env-S" in rig.relay.acked }

        assertTrue(
            logLines("inbound_repair_ok").isNotEmpty(),
            "the envelope must be recovered by the inbound-repair branch, not by a " +
                "first-contact bootstrap; decrypt lines: " + logLines("DECRYPT_TRACE").take(8),
        )
        assertTrue(
            logLines("promotion=true").isNotEmpty(),
            "the pending session must be promoted to active; repair lines: " +
                logLines("inbound_repair_ok"),
        )
        assertNull(rig.opks.get(rig.opkIdHex), "promotion consumes the one-time pre-key")
        assertNull(
            rig.reservations.get(rig.opkIdHex),
            "promotion clears the reservation it took",
        )
        assertNull(
            rig.pending.get(rig.convId),
            "no pending row survives a completed promotion",
        )
    }

    /** In order, through the whole stack: both messages stored and acked. */
    @Test
    fun full_stack_in_order_delivery_stores_and_acks() = runBlocking {
        val rig = buildRig()
        rig.deliver("S", "env-S")
        awaitTrue("S stored") { rig.texts().contains(TEXT_S) }

        rig.deliver("A", "env-A")
        awaitTrue("A stored") { rig.texts().contains(TEXT_A) }
        awaitTrue("A acked") { "env-A" in rig.relay.acked }

        assertEquals(listOf(TEXT_S, TEXT_A), rig.texts())
        assertEquals(0, rig.held.count(), "nothing is held on the ordered path")
        assertTrue(rig.processed.exists("env-S"))
        assertTrue(rig.processed.exists("env-A"))
        assertEquals(emptyList(), rig.relay.outstanding())
    }

    // ── 2. ACK after persistence, measured at the ack ───────────────────

    @Test
    fun a_second_bootstrap_chain_continues_without_old_replay() = runBlocking {
        val rig = buildRig(withSecondChain = true)
        rig.deliver("NEW0", "env-new-0")
        awaitTrue("new chain first message acked") { "env-new-0" in rig.relay.acked }
        rig.deliver("NEW1", "env-new-1")
        awaitTrue("new chain second message acked") { "env-new-1" in rig.relay.acked }
        assertTrue(rig.texts().containsAll(listOf("NEW0", "NEW1")))
    }

    @Test
    fun an_old_held_bootstrap_replay_does_not_erase_the_new_receive_chain() = runBlocking {
        val rig = buildRig(withSecondChain = true, failCommitForEnvelope = "env-old-0")
        rig.deliver("S", "env-old-0")
        awaitTrue("old bootstrap held after failed commit") { rig.held.count() == 1L }
        assertFalse("env-old-0" in rig.relay.acked)
        // Let the production commit-triggered replay recover the older
        // session only after NEW0 has committed. No manual replay call.
        var activeBeforeReplay: String? = null
        rig.tx.afterCommit.set {
            activeBeforeReplay = rig.ratchetStates.getRatchetState(rig.convId)
            rig.tx.afterCommit.set(null)
            rig.failCommitFor.set(null)
        }
        rig.deliver("NEW0", "env-new-0")
        awaitTrue("both session beginnings acked") {
            "env-new-0" in rig.relay.acked && "env-old-0" in rig.relay.acked
        }
        assertTrue(rig.texts().containsAll(listOf(TEXT_S, "NEW0")))
        assertNotNull(activeBeforeReplay)
        assertEquals(activeBeforeReplay, rig.ratchetStates.getRatchetState(rig.convId),
            "a held bootstrap must not select itself over the live session")
        rig.deliver("NEW1", "env-new-1")
        awaitTrue("new continuation processed or held") {
            "env-new-1" in rig.relay.acked || rig.held.count() > 0
        }
        assertTrue(rig.texts().contains("NEW1"),
            "old replay erased the new receive chain: " +
                logLines("repair_candidate_failed").joinToString())
        awaitTrue("new continuation acked") { "env-new-1" in rig.relay.acked }
        assertTrue(rig.ackObservations.all { it.second })
    }

    @Test
    fun interleaved_sessions_continue_in_both_directions_without_reusing_a_pre_key() = runBlocking {
        val rig = buildRig(withSecondChain = true)
        rig.deliver("S", "env-old-0")
        awaitTrue("old first acked") { "env-old-0" in rig.relay.acked }
        rig.deliver("NEW0", "env-new-0")
        awaitTrue("new first acked") { "env-new-0" in rig.relay.acked }
        for ((frame, id) in listOf("A" to "env-old-1", "NEW1" to "env-new-1",
            "B" to "env-old-2", "NEW2" to "env-new-2")) {
            rig.deliver(frame, id)
            awaitTrue("$id acked") { id in rig.relay.acked }
        }
        assertTrue(rig.texts().containsAll(listOf(TEXT_S, TEXT_A, TEXT_B, "NEW0", "NEW1", "NEW2")))
        assertNull(rig.opks.get(rig.opkIdHex))
        assertNull(rig.opks.get("102132435465768798a9bacbdcedfe0f"))
        assertEquals(0L, rig.held.count())
        assertTrue(rig.ackObservations.all { it.second })
    }

    @Test
    fun an_archive_rejects_a_forgery_and_a_failed_commit_preserves_its_receive_position() = runBlocking {
        val rig = buildRig(withSecondChain = true)
        rig.deliver("NEW0", "env-new-0")
        awaitTrue("new first acked") { "env-new-0" in rig.relay.acked }
        rig.deliver("S", "env-old-0")
        awaitTrue("old first acked") { "env-old-0" in rig.relay.acked }
        val activeBefore = rig.ratchetStates.getRatchetState(rig.convId)
        val archivesBefore = rig.tx.listReceiveArchives(rig.convId, System.currentTimeMillis())
        rig.deliver("NEW1_FORGED", "env-forged")
        awaitTrue("forgery held") { rig.held.count() == 1L }
        assertFalse("env-forged" in rig.relay.acked)
        assertFalse(rig.processed.exists("env-forged"))
        assertEquals(activeBefore, rig.ratchetStates.getRatchetState(rig.convId))
        assertEquals(archivesBefore, rig.tx.listReceiveArchives(rig.convId, System.currentTimeMillis()))
        rig.failInTransactionAt.set("after_message")
        rig.deliver("NEW1_NO_HEADER", "env-new-1")
        awaitTrue("archive commit rolled back") { rig.failedTransactionPoints.contains("after_message") }
        assertFalse("env-new-1" in rig.relay.acked)
        assertFalse(rig.processed.exists("env-new-1"))
        assertEquals(activeBefore, rig.ratchetStates.getRatchetState(rig.convId))
        assertEquals(archivesBefore, rig.tx.listReceiveArchives(rig.convId, System.currentTimeMillis()))
        rig.failInTransactionAt.set(null)
        awaitTrue("same headerless envelope recovered without a new message") { "env-new-1" in rig.relay.acked }
        assertTrue(rig.texts().contains("NEW1"))
        assertFalse("env-forged" in rig.relay.acked)
    }

    @Test
    fun reopening_the_database_preserves_an_archived_headerless_continuation() = runBlocking {
        val file = java.io.File.createTempFile("receive-archives-", ".db")
        try {
            val first = buildRig(withSecondChain = true, dbFile = file)
            first.deliver("NEW0", "env-new-0")
            awaitTrue("new first acked") { "env-new-0" in first.relay.acked }
            first.deliver("S", "env-old-0")
            awaitTrue("old first acked") { "env-old-0" in first.relay.acked }
            tearDown()
            val second = buildRig(reuse = first.seed)
            second.deliver("NEW1_NO_HEADER", "env-new-1")
            awaitTrue("headerless continuation after reopen") { "env-new-1" in second.relay.acked }
            assertTrue(second.texts().contains("NEW1"))
            assertNull(second.opks.get("102132435465768798a9bacbdcedfe0f"))
            assertTrue(second.ackObservations.all { it.second })
        } finally {
            tearDown()
            file.delete()
        }
    }

    @Test
    fun expired_archives_are_swept_without_a_new_message_or_a_held_envelope() = runBlocking {
        val rig = buildRig()
        val current = requireNotNull(rig.ratchetStates.getRatchetState(rig.convId))
        rig.tx.replaceActiveSession(rig.convId, current, System.currentTimeMillis() -
            phantom.core.storage.ReceiveSessionArchivePolicy.RETENTION_MS - 1_000L)
        assertEquals(0L, rig.held.count())
        awaitTrue("owned housekeeping removes expired state", timeoutMs = 12_000L) {
            rig.tx.listReceiveArchives(rig.convId, 0L).isEmpty()
        }
    }

    @Test
    fun an_unreadable_archive_does_not_block_the_healthy_active_chain() = runBlocking {
        val rig = buildRig(withSecondChain = true)
        rig.deliver("NEW0", "env-new-0")
        awaitTrue("first chain acked") { "env-new-0" in rig.relay.acked }
        rig.deliver("S", "env-old-0")
        awaitTrue("active chain established") { "env-old-0" in rig.relay.acked }
        rig.tx.failArchiveReads.set(true)
        rig.deliver("A", "env-old-1")
        awaitTrue("healthy active chain bypasses unreadable archives") { "env-old-1" in rig.relay.acked }
        assertTrue(rig.texts().contains(TEXT_A))
    }

    /**
     * Every ack is checked AT THE MOMENT it is sent: the row it
     * acknowledges must already be readable from the database.
     */
    @Test
    fun full_stack_every_ack_is_sent_after_its_row_is_durable() = runBlocking {
        val rig = buildRig()
        rig.deliver("S", "env-S")
        awaitTrue("S acked") { "env-S" in rig.relay.acked }
        rig.deliver("A", "env-A")
        awaitTrue("A acked") { "env-A" in rig.relay.acked }

        val observed = rig.ackObservations.toList()
        assertEquals(
            listOf("env-S", "env-A"), observed.map { it.first },
            "both envelopes were acked",
        )
        assertTrue(
            observed.all { it.second },
            "an ack was sent while its row was not yet durable: $observed",
        )
    }

    /**
     * The inverted case. Stage 1 of the fix makes the message row, the
     * chain advance and the completion entry one transaction, so a
     * storage failure now leaves NOTHING behind -- and the envelope is
     * still recoverable when storage comes back.
     *
     * Before the fix this same scenario ended with the envelope acked as
     * a duplicate on its redelivery, the relay dropping its copy, and no
     * message anywhere at the receiver: the ledger had been written
     * before the row, so the retry was mistaken for a duplicate.
     *
     * The second half is the case the contract calls out separately: a
     * storage failure that is later cleared must deliver at the
     * UNCHANGED chain position, with no new inbound message and no new
     * session.
     */
    @Test
    fun full_stack_a_failed_commit_writes_nothing_and_recovers_when_storage_returns() =
        runBlocking {
            val rig = buildRig(failCommitForEnvelope = "env-A")
            rig.deliver("S", "env-S")
            awaitTrue("S stored") { rig.texts().contains(TEXT_S) }
            awaitTrue("S acked") { "env-S" in rig.relay.acked }

            // ── storage is down for this envelope ────────────────────
            rig.deliver("A", "env-A")
            letPollsElapse(rig, 3)

            assertEquals(listOf(TEXT_S), rig.texts(), "a failed commit stores nothing")
            assertFalse(
                rig.processed.exists("env-A"),
                "a failed commit must not mark the envelope processed",
            )
            assertFalse(
                "env-A" in rig.relay.acked,
                "a failed commit must not be acknowledged",
            )
            assertTrue(
                "env-A" in rig.relay.outstanding(),
                "the relay keeps the only copy",
            )

            // ── storage comes back ──────────────────────────────────
            rig.relay.withheld.add("env-A")
            rig.failCommitFor.set(null)

            awaitTrue("A delivered after storage recovered") { rig.texts().contains(TEXT_A) }
            awaitTrue("the SAME id is acked") { "env-A" in rig.relay.acked }

            assertEquals(
                listOf(TEXT_S, TEXT_A), rig.texts(),
                "the message survives a storage outage",
            )
            assertTrue(rig.processed.exists("env-A"))
            assertEquals(0, rig.held.count(), "completion removes the held row atomically")

            // And the chain is exactly where it should be: the next
            // message decrypts too.
            rig.deliver("B", "env-B")
            awaitTrue("B stored") { rig.texts().contains(TEXT_B) }
            assertEquals(listOf(TEXT_S, TEXT_A, TEXT_B), rig.texts())
        }

    /**
     * A storage failure must not be mistaken for a broken session.
     *
     * The commit sits inside the same `try` whose
     * `catch (IllegalArgumentException)` runs the inbound-repair branch,
     * and a storage layer is entitled to throw exactly that type. If the
     * two were not separated, a database error would arm a session
     * repair -- consuming a one-time pre-key to fix a session that was
     * never broken, and then holding the envelope when the key turned
     * out to be gone.
     */
    @Test
    fun full_stack_a_commit_failure_does_not_arm_a_session_repair() = runBlocking {
        val rig = buildRig(failCommitForEnvelope = "env-A")
        rig.deliver("S", "env-S")
        awaitTrue("S stored") { rig.texts().contains(TEXT_S) }
        val repairsAfterS = logLines("inbound_repair_armed").size

        rig.deliver("A", "env-A")
        letPollsElapse(rig, 3)

        assertEquals(
            repairsAfterS, logLines("inbound_repair_armed").size,
            "a failed commit armed a session repair: " + logLines("inbound_repair_armed"),
        )
        assertTrue(
            logLines("inbound_commit_failed").any { it.contains("env-A") },
            "the failure must be reported as a commit failure: " +
                logLines("DECRYPT_TRACE").takeLast(6),
        )
        assertEquals("commit", rig.held.listByConversation(rig.convId).single().errorType,
            "storage failure is held separately from cryptographic rejection")
        assertEquals(listOf(TEXT_S), rig.texts())
        assertFalse("env-A" in rig.relay.acked)
    }

    /**
     * Atomicity, measured inside the real transaction.
     *
     * The failure is injected at a probe point the repository reaches
     * AFTER it has already issued the chain write and the message
     * insert. Only a real transaction makes those disappear again.
     */
    @Test
    fun full_stack_a_failure_inside_the_transaction_leaves_no_partial_write() = runBlocking {
        val rig = buildRig()
        rig.deliver("S", "env-S")
        awaitTrue("S stored") { rig.texts().contains(TEXT_S) }
        awaitTrue("S acked") { "env-S" in rig.relay.acked }

        val stateBeforeA = rig.ratchetStates.getRatchetState(rig.convId)
        assertNotNull(stateBeforeA, "the session is established")

        // Fail after the message row has been inserted, inside the
        // transaction.
        rig.failInTransactionAt.set("after_message")
        rig.deliver("A", "env-A")
        letPollsElapse(rig, 3)

        assertEquals(listOf(TEXT_S), rig.texts(), "the message row was rolled back")
        assertFalse(rig.processed.exists("env-A"), "the ledger entry was rolled back")
        assertEquals(
            stateBeforeA, rig.ratchetStates.getRatchetState(rig.convId),
            "the chain advance was rolled back with the rest",
        )
        assertFalse("env-A" in rig.relay.acked)
        assertTrue("env-A" in rig.relay.outstanding())

        // Storage recovers; retry the same id, not an alias for the ciphertext.
        rig.failInTransactionAt.set(null)
        awaitTrue("A stored after recovery") { rig.texts().contains(TEXT_A) }
        awaitTrue("original A acked") { "env-A" in rig.relay.acked }
        assertEquals(listOf(TEXT_S, TEXT_A), rig.texts())
        assertEquals(0, rig.held.count())
    }

    /**
     * The same, for the promotion path: a failure inside the transaction
     * must leave the pending session, its reservation and the one-time
     * pre-key exactly as they were. Consuming a pre-key for a message
     * that never became durable is the defect this stage removes.
     */
    @Test
    fun full_stack_a_failed_promotion_commit_consumes_no_pre_key() = runBlocking {
        val rig = buildRig()
        rig.failInTransactionAt.set("after_message")

        rig.deliver("S", "env-S")
        letPollsElapse(rig, 3)

        assertEquals(emptyList(), rig.texts(), "nothing was stored")
        assertFalse(rig.processed.exists("env-S"), "nothing was recorded as processed")
        assertFalse("env-S" in rig.relay.acked, "nothing was acknowledged")
        assertNotNull(
            rig.opks.get(rig.opkIdHex),
            "the one-time pre-key must survive a failed commit",
        )
        assertNotNull(
            rig.pending.get(rig.convId),
            "the pending session must survive it too",
        )
        assertNotNull(
            rig.reservations.get(rig.opkIdHex),
            "and so must its reservation",
        )

        // Storage recovers. The retry re-derives a candidate and
        // decrypts the envelope under it -- the ciphertext is proven
        // again, not trusted because a pending row happens to exist --
        // and only then does the promotion consume the pre-key, inside
        // the same commit as the message.
        rig.failInTransactionAt.set(null)
        awaitTrue("S stored after recovery") { rig.texts().contains(TEXT_S) }
        awaitTrue("original S ACK") { "env-S" in rig.relay.acked }

        assertEquals(listOf(TEXT_S), rig.texts())
        assertNull(rig.opks.get(rig.opkIdHex), "now the pre-key is consumed")
        assertNull(rig.pending.get(rig.convId), "and the pending row is gone")
        assertNull(rig.reservations.get(rig.opkIdHex), "and so is the reservation")
        assertTrue(
            logLines("pending_fallback_ok").isNotEmpty(),
            "the retry authenticates under the unadvanced pending candidate",
        )
        assertTrue(
            rig.processed.exists("env-S"),
            "the promotion travelled with the message",
        )
        assertTrue("env-S" in rig.relay.acked)
    }

    /**
     * The recovery above must not become a way in for a forgery.
     *
     * With a pending session sitting there from a rolled-back attempt, a
     * TAMPERED envelope is offered. Promotion must not happen: the
     * candidate decrypt is what authenticates an envelope, and a pending
     * row is not a substitute for it.
     */
    @Test
    fun full_stack_a_forged_envelope_cannot_ride_a_surviving_pending_session() = runBlocking {
        val rig = buildRig()
        rig.failInTransactionAt.set("after_message")
        rig.deliver("S", "env-S")
        letPollsElapse(rig, 3)
        assertNotNull(rig.pending.get(rig.convId), "a pending row survived the rollback")

        // A forgery, offered while that pending row exists.
        rig.failCommitFor.set("env-S")
        rig.failInTransactionAt.set(null)
        rig.deliver("S_FORGED", "env-forged")
        awaitTrue("the forgery is held") {
            rig.held.listByConversation(rig.convId).any { it.envelopeId == "env-forged" }
        }

        assertEquals(emptyList(), rig.texts(), "a forged envelope never reaches the chat")
        assertFalse("env-forged" in rig.relay.acked)
        assertFalse(rig.processed.exists("env-forged"))
        assertTrue(logLines("repair_candidate_failed").any {
            "stage=decrypt" in it && "errorClass=IllegalArgumentException" in it
        }, "the authenticated-candidate decrypt stage must be identified")
        assertNotNull(
            rig.opks.get(rig.opkIdHex),
            "and it must not consume the one-time pre-key",
        )
        assertTrue(
            logLines("inbound_commit_promoted").isEmpty(),
            "nothing was promoted on a forgery",
        )

        // The load-bearing part: the forgery must not have stripped the
        // protection of the candidate that was already waiting. Pending
        // and its reservation stay consistent...
        assertNotNull(
            rig.pending.get(rig.convId),
            "the earlier candidate is still there",
        )
        assertNotNull(
            rig.reservations.get(rig.opkIdHex),
            "and so is the reservation that guards its pre-key",
        )

        // ...and the genuine envelope still completes, consuming the
        // pre-key exactly when its message becomes durable.
        rig.failCommitFor.set(null)
        awaitTrue("the genuine retry lands") { rig.texts().contains(TEXT_S) }
        awaitTrue("genuine ACK") { "env-S" in rig.relay.acked }
        assertEquals(listOf(TEXT_S), rig.texts())
        assertNull(rig.opks.get(rig.opkIdHex), "now the pre-key is consumed")
        assertNull(rig.reservations.get(rig.opkIdHex), "and the reservation is released")
        assertNull(rig.pending.get(rig.convId), "and the pending row is gone")
        assertTrue("env-S" in rig.relay.acked)
    }

    /**
     * The first-contact bootstrap path is text too, and it used to save
     * the chain before the message. A failure inside the commit must
     * leave no row, no ledger entry and no advanced chain -- and the
     * envelope must still be recoverable afterwards.
     */
    @Test
    fun full_stack_a_first_contact_bootstrap_commits_atomically() = runBlocking {
        val rig = buildRig(freshContact = true)
        rig.failInTransactionAt.set("after_message")
        rig.deliver("S", "env-S")
        letPollsElapse(rig, 3)

        assertEquals(emptyList(), rig.texts(), "nothing was stored")
        assertFalse(rig.processed.exists("env-S"), "nothing was recorded as processed")
        assertFalse("env-S" in rig.relay.acked, "nothing was acknowledged")
        // The window that used to exist here is gone. The bootstrap no
        // longer releases the reservation, deletes the pre-key and saves
        // the session in separate steps before the message: it derives in
        // memory, and the commit spends the key together with the row. A
        // commit that fails therefore leaves NOTHING -- not even a
        // session -- and the envelope is still recoverable.
        assertNull(
            rig.ratchetStates.getRatchetState(rig.convId),
            "no session was written by a commit that did not happen",
        )
        assertNotNull(rig.opks.get(rig.opkIdHex), "the one-time pre-key is untouched")
        assertNotNull(rig.reservations.get(rig.opkIdHex), "and its reservation still guards it")

        rig.failInTransactionAt.set(null)
        awaitTrue("delivered once storage returns") { rig.texts().contains(TEXT_S) }
        assertEquals(listOf(TEXT_S), rig.texts())
        assertTrue(rig.processed.exists("env-S"))
        assertNull(rig.opks.get(rig.opkIdHex), "and only now is the pre-key spent")
        assertNull(rig.reservations.get(rig.opkIdHex), "with its reservation released")
    }

    // ── 2b. Recovery, on a database that outlives the instance ──────────

    /**
     * A failure inside the promotion transaction, then a RESTART: a new
     * service instance over the same file finishes the SAME envelope, and
     * the one-time pre-key is spent exactly once.
     *
     * This is a recovery measurement, not a process-kill measurement: the
     * failure is injected into the transaction, and what is reopened is
     * the database, by a second instance built over the same file.
     */
    @Test
    fun restart_completes_the_same_envelope_and_spends_the_pre_key_once() = runBlocking {
        val dbFile = java.io.File.createTempFile("session-order-restart", ".db")
        dbFile.delete()
        try {
            val first = buildRig(dbFile = dbFile)
            first.failInTransactionAt.set("after_message")
            first.deliver("S", "env-S")
            letPollsElapse(first, 3)

            assertEquals(emptyList(), first.texts(), "the failed commit stored nothing")
            assertNotNull(first.opks.get(first.opkIdHex), "the pre-key survived")
            assertNotNull(first.pending.get(first.convId), "so did the pending row")

            // The instance goes away; the file stays.
            val problems = releaseResources(
                closeActions = orchestrators.map { o -> suspend { o.close() } },
                scopes = scopes.toList(),
                drivers = drivers.toList(),
            )
            orchestrators.clear(); scopes.clear(); drivers.clear()
            assertEquals(emptyList(), problems, "the first instance released cleanly")

            // A new instance over the same database, offered the SAME
            // envelope id again -- which is what the relay would do, and
            // what makes this a test of the surviving reservation rather
            // than of a fresh arrival.
            val second = buildRig(reuse = first.seed)
            second.deliver("S", "env-S")
            awaitTrue("delivered after the restart") { second.texts().contains(TEXT_S) }

            assertEquals(listOf(TEXT_S), second.texts())
            // Spent exactly once: the failed attempt left it in the pool
            // (asserted above), and the successful one removed it. There
            // is no second key and no second removal to make.
            assertNull(second.opks.get(second.opkIdHex), "the pre-key is spent")
            assertNull(second.pending.get(second.convId))
            assertNull(second.reservations.get(second.opkIdHex))
        } finally {
            dbFile.delete()
        }
    }

    /**
     * Committed before the ack was sent. A new instance must answer the
     * relay from the COMMITTED record and must not decrypt anything a
     * second time.
     */
    @Test
    fun restart_acks_from_the_committed_record_without_decrypting_again() = runBlocking {
        val dbFile = java.io.File.createTempFile("session-order-ack", ".db")
        dbFile.delete()
        try {
            val first = buildRig(dbFile = dbFile)
            // The ack never leaves: the commit stands without it, which is
            // exactly the state a crash between the two would leave.
            first.relay.dropAcks.set(true)
            first.deliver("S", "env-S")
            awaitTrue("committed") { first.processed.exists("env-S") }
            awaitTrue("stored") { first.texts().contains(TEXT_S) }
            assertFalse("env-S" in first.relay.acked, "the ack was dropped")

            val problems = releaseResources(
                closeActions = orchestrators.map { o -> suspend { o.close() } },
                scopes = scopes.toList(),
                drivers = drivers.toList(),
            )
            orchestrators.clear(); scopes.clear(); drivers.clear()
            assertEquals(emptyList(), problems)

            val second = buildRig(reuse = first.seed)
            second.deliver("S", "env-S")
            awaitTrue("acked after the restart") { "env-S" in second.relay.acked }

            assertEquals(
                listOf(TEXT_S), second.texts(),
                "the message is not stored a second time",
            )
            // The committed record is what answers: the transport's own
            // guard reads the processed-envelope ledger and acks from it,
            // so the envelope never reaches the receive path again.
            assertTrue(
                logLines("inbound_skip_already_processed").any { it.contains("env-S") },
                "the ack must come from the committed record: " +
                    ShadowLog.getLogs().mapNotNull { it.msg }.takeLast(8),
            )
            assertTrue(
                logLines("DECRYPT_TRACE attempt").isEmpty(),
                "the second instance did not attempt a decrypt at all: " +
                    logLines("DECRYPT_TRACE"),
            )
        } finally {
            dbFile.delete()
        }
    }

    /**
     * A forgery replayed under the ORIGINAL envelope id, after a restart.
     *
     * This is the case an id comparison cannot answer: the reservation
     * names this very envelope, so "is this mine?" is true by id and
     * false in fact -- the row was created by an attempt that is gone.
     * Ownership is taken from the reserve outcome instead, so the failing
     * forgery releases nothing.
     */
    @Test
    fun full_stack_a_forgery_under_the_original_id_after_restart_keeps_the_reservation() =
        runBlocking {
            val dbFile = java.io.File.createTempFile("session-order-forgery", ".db")
            dbFile.delete()
            try {
                val first = buildRig(dbFile = dbFile)
                first.failInTransactionAt.set("after_message")
                first.deliver("S", "env-S")
                letPollsElapse(first, 3)
                assertNotNull(first.pending.get(first.convId), "a candidate is waiting")
                assertNotNull(first.reservations.get(first.opkIdHex), "guarded by a reservation")

                // Isolate the reservation test from startup replay of the
                // genuine stored frame (the state before Stage 2 retained it).
                first.held.deleteByEnvelopeId("env-S")

                val problems = releaseResources(
                    closeActions = orchestrators.map { o -> suspend { o.close() } },
                    scopes = scopes.toList(),
                    drivers = drivers.toList(),
                )
                orchestrators.clear(); scopes.clear(); drivers.clear()
                assertEquals(emptyList(), problems)

                // A new instance, and a FORGED frame under the id the
                // reservation names.
                val second = buildRig(reuse = first.seed)
                second.deliver("S_FORGED", "env-S")
                awaitTrue("the forgery is held") {
                    second.held.listByConversation(second.convId).any { it.envelopeId == "env-S" }
                }

                assertEquals(emptyList(), second.texts(), "a forgery reaches nobody")
                assertFalse("env-S" in second.relay.acked)
                assertNotNull(
                    second.reservations.get(second.opkIdHex),
                    "the forgery must not release a reservation it never created",
                )
                assertNotNull(second.opks.get(second.opkIdHex), "and must not spend the key")
                assertNotNull(second.pending.get(second.convId), "the candidate is still guarded")
            } finally {
                dbFile.delete()
            }
            Unit
        }

    /**
     * The pending fallback, which is the only route left for an envelope
     * that carries no X3DH header at all. A commit that fails there must
     * leave the pending row exactly as it was -- for such an envelope no
     * fresh bootstrap can put it back -- and the retry must land.
     */
    @Test
    fun full_stack_a_pending_candidate_does_not_skip_its_uncommitted_first_message() =
        runBlocking {
            val rig = buildRig()
            rig.failInTransactionAt.set("after_message")
            rig.deliver("S", "env-S")
            letPollsElapse(rig, 3)
            val pendingBefore = rig.pending.get(rig.convId)
            assertNotNull(pendingBefore, "a pending candidate survived, still before S")
            val retainedS = rig.held.listByConversation(rig.convId).single { it.envelopeId == "env-S" }
            val retainedFrame = json.decodeFromString(WireFrame.serializer(), retainedS.wireFrameJson)
            val (_, recoveredS) = LibsodiumDoubleRatchet().decrypt(
                json.decodeFromString(RatchetState.serializer(), pendingBefore.stateBlob),
                retainedFrame.encryptedMessage,
            )
            assertEquals(TEXT_S, json.decodeFromString(MessagePayload.serializer(),
                recoveredS.decodeToString()).text, "pending must still authenticate the uncommitted S")

            // A headerless successor cannot consume the position of S,
            // which has been decrypted but has not become durable.
            rig.deliver("A_NO_HEADER", "env-A-plain")
            letPollsElapse(rig, 3)

            assertEquals(emptyList(), rig.texts(), "the failed commit stored nothing")
            assertFalse(rig.processed.exists("env-A-plain"))
            assertFalse("env-A-plain" in rig.relay.acked)
            assertEquals(
                pendingBefore.stateBlob,
                rig.pending.get(rig.convId)?.stateBlob,
                "the pending row must be byte-identical: nothing else can recover " +
                    "an envelope that carries no header",
            )

            rig.failInTransactionAt.set(null)
            awaitTrue("the headerless retry lands") { rig.texts().contains(TEXT_A) }
            assertEquals(listOf(TEXT_S, TEXT_A), rig.texts())
            assertNull(rig.pending.get(rig.convId), "the candidate was promoted by the commit")
            assertNull(
                rig.opks.get(rig.opkIdHex),
                "the pending candidate's key is consumed even without a frame header",
            )
            assertNull(rig.reservations.get(rig.opkIdHex))
            Unit
        }

    @Test
    fun restart_a_headerless_pending_retry_keeps_its_position_on_commit_failure() = runBlocking {
        val dbFile = java.io.File.createTempFile("headerless-pending", ".db")
        dbFile.delete()
        try {
            val first = buildRig(dbFile = dbFile, failTransactionAt = "after_message")
            first.deliver("S", "env-S")
            awaitTrue("failed S retained") { first.held.existsByEnvelopeId("env-S") }
            val pendingBefore = first.pending.get(first.convId)
            assertNotNull(pendingBefore)
            val entry = first.held.listByConversation(first.convId).single()
            val frame = json.decodeFromString(WireFrame.serializer(), entry.wireFrameJson)
            // Same authenticated ciphertext and envelope id, without the
            // optional bootstrap hint. Only the stored candidate can read it.
            first.held.deleteByEnvelopeId(entry.envelopeId)
            first.held.insert(entry.envelopeId, entry.conversationId, entry.senderPubKeyHex,
                "commit", entry.receivedAtMs, false,
                json.encodeToString(WireFrame.serializer(), frame.copy(x3dhInit = null)))
            val problems = releaseResources(
                closeActions = orchestrators.map { o -> suspend { o.close() } },
                scopes = scopes.toList(), drivers = drivers.toList(),
            )
            orchestrators.clear(); scopes.clear(); drivers.clear()
            assertEquals(emptyList(), problems)
            val second = buildRig(reuse = first.seed, failTransactionAt = "after_message")
            awaitTrue("headerless decrypt reached the failed transaction") {
                logLines("inbound_commit_failed").isNotEmpty()
            }
            assertTrue(logLines("pending_fallback_ok").isNotEmpty())
            assertEquals(pendingBefore.stateBlob, second.pending.get(second.convId)?.stateBlob)
            assertEquals(emptyList(), second.texts())
            assertFalse(second.processed.exists("env-S"))
            assertNotNull(second.opks.get(second.opkIdHex))
            assertNotNull(second.reservations.get(second.opkIdHex))
            second.failInTransactionAt.set(null)
            awaitTrue("same headerless frame commits locally") { second.processed.exists("env-S") }
            assertEquals(listOf(TEXT_S), second.texts())
            assertNull(second.pending.get(second.convId))
            assertNull(second.opks.get(second.opkIdHex))
            assertNull(second.reservations.get(second.opkIdHex))
        } finally {
            dbFile.deleteOnExit()
        }
    }

    @Test
    fun full_stack_a_first_contact_with_a_missing_reservation_never_falls_back_to_ack() = runBlocking {
        val rig = buildRig(freshContact = true)
        rig.tx.repeatBeforeCommit.set(true)
        rig.tx.beforeCommit.set { rig.reservations.release(rig.opkIdHex) }
        rig.deliver("S", "env-S")
        letPollsElapse(rig, 3)

        assertTrue(logLines("inbound_commit").any { it.contains("outcome=ReservationMissing") })
        assertEquals(emptyList(), rig.texts())
        assertNull(rig.ratchetStates.getRatchetState(rig.convId))
        assertFalse(rig.processed.exists("env-S"))
        assertFalse("env-S" in rig.relay.acked)
        assertTrue("env-S" in rig.relay.outstanding())
        assertNotNull(rig.opks.get(rig.opkIdHex))

        rig.tx.beforeCommit.set(null)
        awaitTrue("first contact recovers without an advanced chain") { rig.texts().isNotEmpty() }
        assertNull(rig.opks.get(rig.opkIdHex))
    }

    /**
     * A candidate that could not be committed leaves the envelope
     * unfinished: no ledger entry, no ack, nothing stored.
     */
    @Test
    fun full_stack_a_failed_candidate_commit_does_not_finish_the_envelope() = runBlocking {
        val rig = buildRig()
        rig.tx.refuseBootstrapCommit.set(true)

        rig.deliver("S", "env-S")
        letPollsElapse(rig, 3)

        assertEquals(emptyList(), rig.texts(), "nothing was stored")
        assertFalse(rig.processed.exists("env-S"), "and nothing was recorded as processed")
        assertFalse("env-S" in rig.relay.acked, "and nothing was acknowledged")
        assertTrue("env-S" in rig.relay.outstanding(), "the relay keeps its copy")
        assertNotNull(rig.opks.get(rig.opkIdHex), "no pre-key was spent")
        assertTrue(
            logLines("inbound_commit_not_taken").any { it.contains("candidate_commit_failed") },
            "the refusal must be visible: " + logLines("DECRYPT_TRACE").takeLast(6),
        )
    }

    /**
     * The reservation disappears between deriving the candidate and
     * committing it. The transaction is bound to the key the candidate
     * used, so it writes nothing at all rather than storing a message and
     * a session whose pre-key was never spent.
     */
    @Test
    fun full_stack_a_commit_whose_reservation_vanished_writes_nothing() = runBlocking {
        val rig = buildRig()
        rig.tx.repeatBeforeCommit.set(true)
        rig.tx.beforeCommit.set {
            rig.reservations.release(rig.opkIdHex)
        }

        rig.deliver("S", "env-S")
        letPollsElapse(rig, 3)

        assertEquals(emptyList(), rig.texts(), "nothing was stored")
        assertFalse(rig.processed.exists("env-S"))
        assertFalse("env-S" in rig.relay.acked)
        assertTrue(
            logLines("inbound_commit_pending_missing").isNotEmpty(),
            "the commit must refuse the bound key it could not find: " +
                logLines("DECRYPT_TRACE").takeLast(6),
        )
        assertNotNull(rig.pending.get(rig.convId), "the candidate is still waiting")
        assertNotNull(
            rig.opks.get(rig.opkIdHex),
            "the pre-key stays in the pool -- it was never consumed",
        )
        Unit
    }

    // ── 3. The field sequence, end to end ───────────────────────────────

    /**
     * B before A, with the relay redelivering B exactly as the real one
     * does. Once A has landed and the chain has reached B's position,
     * does the redelivered B ever reach the receiver again?
     */
    @Test
    fun full_stack_A_commit_replays_B_without_redelivery_or_an_outgoing_message() =
        runBlocking {
            val rig = buildRig()
            rig.deliver("S", "env-S")
            awaitTrue("S stored") { rig.texts().contains(TEXT_S) }
            awaitTrue("S acked") { "env-S" in rig.relay.acked }

            rig.deliver("B", "env-B")
            awaitTrue("B held") {
                rig.held.listByConversation(rig.convId).any { it.envelopeId == "env-B" }
            }
            assertFalse("env-B" in rig.relay.acked, "a held envelope is never acked")
            assertFalse(rig.processed.exists("env-B"), "a held envelope is not marked processed")
            // Keep the relay copy, but stop offering it. Only local replay
            // can now deliver B; the rig's outbound API throws if called.
            rig.relay.withheld.add("env-B")
            rig.deliver("A", "env-A")
            awaitTrue("B recovered locally") { rig.texts().contains(TEXT_B) }
            awaitTrue("local completion routes B's ACK over REST") { "env-B" in rig.relay.acked }
            assertEquals(listOf(TEXT_S, TEXT_A, TEXT_B), rig.texts())
            assertFalse(rig.held.existsByEnvelopeId("env-B"))
            assertTrue(rig.processed.exists("env-B"))
            assertTrue(rig.ackObservations.all { it.second })
        }

    /**
     * With B unconsumed, the newly typed message is held too, and the
     * repair it attempts meets a one-time pre-key that the promotion
     * already consumed -- the `OpkNotFound` shape from the phone.
     */
    /**
     * N1 integration -- a durable completion does not buy permission to
     * reach the network.
     *
     * The message is committed while the authority still allows egress,
     * but its ACK is lost in transit, so the relay keeps its copy and
     * nothing is acknowledged. The authority is then revoked. From that
     * point NO request may leave: the privacy line gates the poll loop
     * and the ACK path alike, so the refusal is visible on both.
     *
     * Nothing already written may be undone to resolve that, and when the
     * authority returns the relay's redelivery is acknowledged from the
     * record that already exists -- no second decryption, no second row,
     * and the receive chain does not move again.
     */
    @Test
    fun full_stack_a_revoked_authority_blocks_the_ack_and_the_committed_row_survives() = runBlocking {
        val rig = buildRig()
        // The commit lands; the acknowledgement does not reach the relay.
        rig.relay.dropAcks.set(true)
        rig.deliver("S", "env-S")
        awaitTrue("the message is stored") { rig.texts().contains(TEXT_S) }
        awaitTrue("the completion is durable") { rig.processed.exists("env-S") }
        assertTrue("env-S" in rig.relay.outstanding(), "the relay still holds its copy")
        assertFalse("env-S" in rig.relay.acked, "no acknowledgement reached the relay yet")
        val chainAfterCommit = rig.ratchetStates.getRatchetState(rig.convId)
        assertNotNull(chainAfterCommit, "the receive advance is durable")

        // The authority is revoked. The transport would now be free to
        // retry the acknowledgement -- the record says the envelope is
        // complete -- and that is exactly what must not happen.
        rig.egressAllowed.set(false)
        rig.relay.dropAcks.set(false)
        // Waiting for polls here would hang forever, and that is the point:
        // the authority refuses the poll loop as well, so the receiver goes
        // quiet in both directions. The refusal itself is the signal.
        awaitTrue("the authority actually refuses REST traffic") {
            traceSnapshot().any { it.startsWith("REST_EGRESS blocked") }
        }
        delay(1_000L)

        assertFalse(
            "env-S" in rig.relay.acked,
            "a request crossed a revoked authority: durable local completion is not egress permission",
        )
        assertTrue(
            traceSnapshot().any { it.startsWith("REST_EGRESS blocked") },
            "the authority never actually refused anything: " + traceSnapshot().takeLast(6),
        )
        assertEquals(listOf(TEXT_S), rig.texts(), "and nothing was rolled back to compensate")
        assertEquals(
            chainAfterCommit, rig.ratchetStates.getRatchetState(rig.convId),
            "the receive chain was not rewound either",
        )
        // Asked for directly as well, so the silence above is a refusal and
        // not merely the absence of a retry.
        assertFalse(
            rig.transport.sendDeliveryAck("env-S"),
            "the transport reported success for an acknowledgement it never sent",
        )
        assertFalse("env-S" in rig.relay.acked, "and still nothing reached the relay")

        // The narrow control: same rig, same envelope, authority restored.
        // If the silence above had come from anything other than the
        // authority, this call would stay silent too.
        //
        // What this does NOT exercise: automatic resumption of POLLING.
        // The orchestrator leaves its poll loop when the authority refuses
        // it, and re-entry to Standard restores polling through the state
        // machine and bootstrap retry -- driven by the privacy coordinator,
        // which this rig does not build.
        rig.egressAllowed.set(true)
        assertTrue(
            rig.transport.sendDeliveryAck("env-S"),
            "the acknowledgement was still refused after the authority returned: " +
                traceSnapshot().takeLast(8),
        )
        assertTrue("env-S" in rig.relay.acked, "and it reached the relay")
        assertEquals(listOf(TEXT_S), rig.texts(), "exactly one row, not a second copy")
        assertEquals(
            chainAfterCommit, rig.ratchetStates.getRatchetState(rig.convId),
            "the redelivery was acknowledged from the record: nothing was decrypted again",
        )
        assertEquals(
            1, rig.relay.acked.count { it == "env-S" }, "and it was acknowledged exactly once",
        )
    }

    /**
     * N1 integration -- a gap is drained by the owned replay worker with
     * every acknowledgement lost in transit, and the authority is revoked
     * AFTER that drain has finished.
     *
     * What this case pins: a completed drain leaves exactly-once rows, the
     * held rows are removed by the same transactions that store them, and
     * a later revocation stops the pending acknowledgements from leaving.
     * The overlap of a LIVE replay with a privacy teardown and a successor
     * transport is a different property and has its own case below,
     * `full_stack_a_privacy_switch_during_a_live_replay_...`.
     *
     * The single-worker property itself is pinned by
     * `HeldInboundReplayTest`; here it shows up as exactly-once rows.
     */
    @Test
    fun full_stack_a_drained_replay_is_not_acknowledged_after_a_later_revocation() = runBlocking {
        val rig = buildRig()
        rig.deliver("S", "env-S")
        awaitTrue("S stored") { rig.texts().contains(TEXT_S) }
        awaitTrue("S acked while the authority still allows it") { "env-S" in rig.relay.acked }

        // B arrives out of order and is held; C follows it as a successor
        // that must not skip the gap either.
        rig.deliver("B", "env-B")
        awaitTrue("B held behind the gap") { rig.held.existsByEnvelopeId("env-B") }
        rig.deliver("C", "env-C")
        awaitTrue("C held behind the same gap") { rig.held.existsByEnvelopeId("env-C") }

        // From here nothing may reach the relay: first the acks are lost
        // in transit, then the authority itself is revoked.
        rig.relay.dropAcks.set(true)
        rig.deliver("A", "env-A")
        awaitTrue("the gap drains locally") {
            rig.texts().containsAll(listOf(TEXT_A, TEXT_B, TEXT_C))
        }
        rig.egressAllowed.set(false)
        rig.relay.dropAcks.set(false)
        awaitTrue("the authority actually refuses REST traffic") {
            traceSnapshot().any { it.startsWith("REST_EGRESS blocked") }
        }
        delay(1_000L)

        assertEquals(
            listOf(TEXT_S, TEXT_A, TEXT_B, TEXT_C), rig.texts(),
            "every message landed exactly once, in order",
        )
        assertEquals(0, rig.held.count(), "the held rows were removed by their own commits")
        for (id in listOf("env-A", "env-B", "env-C")) {
            assertTrue(rig.processed.exists(id), id + " completed durably")
            assertFalse(id in rig.relay.acked, id + " was acknowledged across a revoked authority")
        }
        assertTrue(
            traceSnapshot().any { it.startsWith("REST_EGRESS blocked") },
            "the authority never actually refused anything: " + traceSnapshot().takeLast(6),
        )

        // Control: the same drained envelopes acknowledge as soon as the
        // authority returns, so the silence was the authority and not a
        // replay worker that had stopped. The acknowledgements are asked
        // for directly, for the same reason as in the case above.
        rig.egressAllowed.set(true)
        for (id in listOf("env-A", "env-B", "env-C")) {
            assertTrue(
                rig.transport.sendDeliveryAck(id),
                id + " was still refused after the authority returned: " + traceSnapshot().takeLast(8),
            )
        }
        assertTrue(
            listOf("env-A", "env-B", "env-C").all { it in rig.relay.acked },
            "the drained envelopes reached the relay once the authority returned",
        )
        assertEquals(
            listOf(TEXT_S, TEXT_A, TEXT_B, TEXT_C), rig.texts(),
            "the redeliveries were acknowledged from their records, not decrypted again",
        )
    }

    /**
     * The barrier every overlap case below is built on.
     *
     * `FailingCommitFor.beforeCommit` runs under the receive lock, just
     * BEFORE the delegate opens the SQL transaction. Parking there parks
     * the replay worker with the envelope decrypted but nothing durable
     * yet, and no other receive can proceed. That is what an unfinished
     * receive overlapping a revocation looks like; the hook says nothing
     * about SQL atomicity, which has its own cases. The first commit
     * through the hook is A (the message that opens the gap); the second
     * is B, replayed by the owned worker. That one signals [entered] and
     * waits for [release]. Ordering is proven by the barrier, not by time.
     */
    private class ReplayBarrier(rig: Rig) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private val commits = AtomicInteger(0)

        init {
            rig.tx.repeatBeforeCommit.set(true)
            rig.tx.beforeCommit.set {
                if (commits.incrementAndGet() == 2) {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
    }

    /**
     * One owned transport walk and the successor that replaces it,
     * modelled at the ownership layer the privacy line actually uses: one
     * token, one RUNNING walk, one atomic handover, and a claim fence that
     * shuts the lease across the transition.
     *
     * Both walks are real coroutines owned by this fixture. Each records
     * when it entered, and completes [oldFinished] / [successorFinished]
     * from its `finally`, so "stopped" means the job actually finished,
     * not that a callback was invoked. The handle registered with
     * [ConnectOwnership] cancels the job and joins it within the budget,
     * exactly as the service's handle does. [close] cancels whatever is
     * left, so a failing assertion cannot leak a walk.
     */
    private class OwnedWalks {
        private val nextToken = java.util.concurrent.atomic.AtomicLong(0L)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ownership = ConnectOwnership(nextToken = { nextToken.incrementAndGet() }, handoverTimeoutMs = 5_000L)

        val oldEntered = CompletableDeferred<Unit>()
        val oldFinished = CompletableDeferred<Unit>()
        val oldJoined = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var oldJob: Job
        var oldToken: Long = -1L

        val successorEntered = CompletableDeferred<Unit>()
        val successorFinished = CompletableDeferred<Unit>()
        val successorCancelRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        var successorJob: Job? = null
        var successorToken: Long? = null

        /** The epoch this switch runs under; the fence is bound to it. */
        val epoch = 7L

        private suspend fun runWalk(entered: CompletableDeferred<Unit>, finished: CompletableDeferred<Unit>) {
            try {
                entered.complete(Unit)
                delay(60_000L) // a walk that would keep running unless stopped
            } finally {
                finished.complete(Unit)
            }
        }

        /** Start the old walk and give it the lease; returns once it is running. */
        suspend fun startOldWalk(): Long {
            val claimed = CompletableDeferred<Long>()
            oldJob = scope.launch {
                val me = checkNotNull(currentCoroutineContext()[Job])
                val token = ownership.claim(
                    "old_walk",
                    ConnectWalkHandle { timeoutMs ->
                        me.cancel(CancellationException("ownership_handover"))
                        val joined = withTimeoutOrNull(timeoutMs) { me.join() } != null
                        oldJoined.set(joined)
                        joined
                    },
                )
                claimed.complete(checkNotNull(token))
                runWalk(oldEntered, oldFinished)
            }
            oldToken = withTimeout(5_000L) { claimed.await() }
            withTimeout(5_000L) { oldEntered.await() }
            return oldToken
        }

        /** The privacy switch shuts the lease for its epoch before tearing anything down. */
        suspend fun raiseFence() = ownership.raiseClaimFence("privacy_mode_change", epoch)

        /**
         * The production admission rule, and nothing looser: a successor
         * may start only after a CONFIRMED teardown (`clean && release != null`,
         * which includes the socket join and the egress revocation), and
         * only through a lowered fence. On an unconfirmed teardown the fence
         * stays up and the claim is refused by [ConnectOwnership] itself.
         */
        suspend fun admitSuccessorIfConfirmed(
            teardown: phantom.android.di.PrivacyTeardownOutcome,
        ): Long? {
            if (!teardown.confirmed) return null
            check(ownership.lowerClaimFence("privacy_mode_change", epoch)) { "the fence was not ours to lower" }
            return claimSuccessor()
        }

        /** A claim attempt for the successor, as the service would make it. */
        suspend fun claimSuccessor(): Long? {
            val claimed = CompletableDeferred<Long?>()
            val job = scope.launch {
                val me = checkNotNull(currentCoroutineContext()[Job])
                val token = ownership.claim(
                    "successor",
                    ConnectWalkHandle { timeoutMs ->
                        successorCancelRequested.set(true)
                        me.cancel(CancellationException("ownership_handover"))
                        withTimeoutOrNull(timeoutMs) { me.join() } != null
                    },
                )
                claimed.complete(token)
                if (token == null) return@launch
                runWalk(successorEntered, successorFinished)
            }
            val token = withTimeout(5_000L) { claimed.await() }
            if (token == null) {
                job.join()
                return null
            }
            successorJob = job
            successorToken = token
            withTimeout(5_000L) { successorEntered.await() }
            return token
        }

        /**
         * Cancel every walk this fixture still owns AND wait for it, within
         * a bound, so cleanup is a fact about this test and not something
         * left for the next one to absorb. Returns whether the join landed.
         */
        suspend fun close(): Boolean {
            val parent = checkNotNull(scope.coroutineContext[Job])
            parent.cancel(CancellationException("fixture_closed"))
            return withTimeoutOrNull(5_000L) { parent.join() } != null
        }
    }

    /**
     * N1 integration -- a privacy switch lands while the owned replay
     * worker is parked under the receive lock just BEFORE its commit, and a
     * successor transport is admitted before that commit is allowed to run.
     *
     * Sequence, proven by the barrier rather than by waiting: S lands and
     * is acked; B is held behind a gap; A opens the gap; the replay of B
     * reaches the point just before its commit and parks there. While it
     * is parked, the privacy line's own transition runs -- the claim
     * fence is raised for the switch epoch, the policy flips,
     * `revokeAndJoin` invalidates the REST lease generation, the socket is
     * torn down and joined, the lease is handed over (cancelling and
     * JOINING the old walk, a real coroutine that finishes through its
     * `finally`), and the subsystem release returns a structured clean
     * result. Only a CONFIRMED teardown lowers the fence, and only then
     * does the successor claim the lease with its own token and run. Only
     * now is the replay released.
     *
     * Required outcome: the replayed message completes durably exactly
     * once (holding what the receiver can already read would lose it),
     * but nothing is dispatched through the revoked authority -- no
     * acknowledgement leaves, and the gate reports the refusal. The old
     * walk is finished; the successor's walk is still running and was
     * never asked to stop; a stale release from the old token is refused
     * while the successor owns the lease. With the authority restored the
     * same acknowledgement is accepted from the record.
     *
     * Boundary, stated rather than hidden: the transport's socket-level
     * `teardownIdentity` does not advance here because this rig never
     * opens a socket, so the identity-based refusal in
     * `disconnectAndConfirm(onlyIfIdentity)` is pinned by the transport's
     * own tests, not by this case. The successor is modelled at the
     * ownership layer, which is the layer the privacy switch hands over.
     */
    @Test
    fun full_stack_a_privacy_switch_during_a_live_replay_dispatches_nothing_and_leaves_the_successor_alone() = runBlocking {
        val rig = buildRig()
        val walks = OwnedWalks()
        try {
        walks.startOldWalk()
        rig.deliver("S", "env-S")
        awaitTrue("S stored") { rig.texts().contains(TEXT_S) }
        awaitTrue("S acked while the authority still allows it") { "env-S" in rig.relay.acked }
        rig.deliver("B", "env-B")
        awaitTrue("B held behind the gap") { rig.held.existsByEnvelopeId("env-B") }
        // Whatever the poll loop does from here must not consume A twice:
        // the relay keeps B only for the ack, A drives the drain.
        rig.relay.withheld.add("env-B")

        val barrier = ReplayBarrier(rig)
        rig.deliver("A", "env-A")
        awaitTrue("A stored, which is what requests the replay") { rig.texts().contains(TEXT_A) }
        withTimeout(AWAIT_TIMEOUT_MS) { barrier.entered.await() }
        // Replay is in progress: B is being committed and is not durable.
        assertFalse(rig.processed.exists("env-B"), "barrier reached before B's completion entry")
        assertTrue(rig.held.existsByEnvelopeId("env-B"), "and before its held row was removed")

        // The privacy switch, using the privacy line's own teardown and
        // ownership APIs, while the replay is parked. The fence goes up
        // first: nothing may claim the lease while the transition runs.
        walks.raiseFence()
        assertNull(walks.claimSuccessor(), "a claim under the raised fence must be refused")
        rig.egressAllowed.set(false)
        val teardown = runPrivacyModeTeardown(
            revoke = { rig.egressGate.revokeAndJoin("privacy_mode_change") },
            disconnectAndJoin = { rig.transport.disconnectAndJoin(2_000L) },
            handOverWalk = { walks.ownership.handOver("privacy_mode_change") is Handover.Quiesced },
            // No Tor and no Xray in this rig: the structured answer a
            // release gives when both subsystems confirm they stopped.
            release = { TransportManager.ReleaseOutcome(xrayFailure = null, torFailure = null) },
        )
        val revocation = assertNotNull(teardown.revocation, "the gate was revoked")
        assertTrue(revocation.joinedCleanly, "every lease under the old authority was joined: $revocation")
        assertEquals(true, teardown.disconnectJoinedCleanly, "the socket teardown joined")
        assertTrue(teardown.walkQuiesced, "the old walk was cancelled and joined before the release")
        assertTrue(walks.oldJoined.get(), "the handover joined the OLD walk within its budget")
        withTimeout(5_000L) { walks.oldFinished.await() }
        assertTrue(walks.oldJob.isCompleted, "and that walk actually finished, through its finally")
        assertTrue(teardown.confirmed, "the teardown is CONFIRMED: clean, joined, released: $teardown")
        assertEquals(0, rig.egressGate.activeDispatchCount(), "no lease survives the revocation")
        val successor = assertNotNull(
            walks.admitSuccessorIfConfirmed(teardown),
            "a confirmed teardown lowers the fence and admits the successor",
        )
        assertFalse(walks.ownership.isFenced(), "the fence is down for the successor")
        assertTrue(successor != walks.oldToken, "the successor is a different transport identity")
        assertEquals(successor, walks.ownership.currentOwner(), "and it owns the lease")

        // Now the parked replay may finish.
        barrier.release.complete(Unit)
        awaitTrue("the replayed message completes durably") { rig.processed.exists("env-B") }
        awaitTrue("its held row is removed by the same commit") { !rig.held.existsByEnvelopeId("env-B") }
        // Give the post-commit acknowledgement attempt time to be refused;
        // the refusal itself is the signal, so this is not an ordering proof.
        awaitTrue("the authority refused the replay's acknowledgement") {
            traceSnapshot().any { it.startsWith("REST_EGRESS blocked operation=ack") }
        }

        assertEquals(listOf(TEXT_S, TEXT_A, TEXT_B), rig.texts(), "exactly one row per message, in order")
        assertFalse("env-B" in rig.relay.acked, "an acknowledgement crossed the revoked authority")
        assertFalse(
            rig.transport.sendDeliveryAck("env-B"),
            "the transport reported success for an acknowledgement it never sent",
        )
        assertFalse(walks.successorCancelRequested.get(), "a stale stop touched the successor walk")
        assertTrue(checkNotNull(walks.successorJob).isActive, "the successor walk is still running")
        assertFalse(walks.successorFinished.isCompleted, "and has not finished")
        assertFalse(
            walks.ownership.release(walks.oldToken, "stale_switch"),
            "a release from the old token must be refused while the successor owns the lease",
        )
        assertEquals(successor, walks.ownership.currentOwner(), "the successor still owns the lease")

        // Continued authorized progress: the same record acknowledges once
        // the authority is back, without a second decryption or row.
        rig.egressAllowed.set(true)
        assertTrue(
            rig.transport.sendDeliveryAck("env-B"),
            "the acknowledgement was still refused after the authority returned: " + traceSnapshot().takeLast(8),
        )
        assertTrue("env-B" in rig.relay.acked, "and it reached the relay")
        assertEquals(listOf(TEXT_S, TEXT_A, TEXT_B), rig.texts(), "still exactly one row per message")
        } finally {
            assertTrue(walks.close(), "fixture walks did not finish within the cleanup budget")
        }
    }

    /**
     * The narrow refused path of the admission rule above: the same live
     * replay, the same fence, the same handover -- and a release that does
     * NOT confirm. In the privacy line an unconfirmed teardown is one whose
     * old-policy I/O may have outlived the switch, so it must not produce
     * a successor: the fence stays up, the claim is refused by the
     * ownership itself, and the old walk is still stopped (stopping it is
     * never the wrong thing to do). Local completion still finishes and,
     * with the authority revoked, still dispatches nothing.
     */
    @Test
    fun full_stack_an_unconfirmed_release_during_a_live_replay_admits_no_successor() = runBlocking {
        val rig = buildRig()
        val walks = OwnedWalks()
        try {
        walks.startOldWalk()
        rig.deliver("S", "env-S")
        awaitTrue("S stored") { rig.texts().contains(TEXT_S) }
        awaitTrue("S acked") { "env-S" in rig.relay.acked }
        rig.deliver("B", "env-B")
        awaitTrue("B held behind the gap") { rig.held.existsByEnvelopeId("env-B") }
        rig.relay.withheld.add("env-B")

        val barrier = ReplayBarrier(rig)
        rig.deliver("A", "env-A")
        awaitTrue("A stored") { rig.texts().contains(TEXT_A) }
        withTimeout(AWAIT_TIMEOUT_MS) { barrier.entered.await() }
        assertFalse(rig.processed.exists("env-B"), "barrier reached before B's completion entry")

        walks.raiseFence()
        rig.egressAllowed.set(false)
        val teardown = runPrivacyModeTeardown(
            revoke = { rig.egressGate.revokeAndJoin("privacy_mode_change") },
            disconnectAndJoin = { rig.transport.disconnectAndJoin(2_000L) },
            handOverWalk = { walks.ownership.handOver("privacy_mode_change") is Handover.Quiesced },
            // One subsystem reports it could not stop.
            release = {
                TransportManager.ReleaseOutcome(
                    xrayFailure = IllegalStateException("xray stop failed"),
                    torFailure = null,
                )
            },
        )
        assertTrue(teardown.walkQuiesced && walks.oldJoined.get(), "the old walk was still handed over")
        withTimeout(5_000L) { walks.oldFinished.await() }
        assertFalse(teardown.confirmed, "an unclean release is not a confirmed teardown: $teardown")
        assertNull(walks.admitSuccessorIfConfirmed(teardown), "no successor on an unconfirmed teardown")
        assertTrue(walks.ownership.isFenced(), "the fence stays up")
        assertNull(walks.claimSuccessor(), "and the ownership itself refuses a direct claim")
        assertNull(walks.ownership.currentOwner(), "nobody owns the lease")

        barrier.release.complete(Unit)
        awaitTrue("the replayed message still completes durably") { rig.processed.exists("env-B") }
        awaitTrue("the authority refused its acknowledgement") {
            traceSnapshot().any { it.startsWith("REST_EGRESS blocked operation=ack") }
        }
        assertFalse("env-B" in rig.relay.acked, "nothing crossed the revoked authority")
        assertEquals(listOf(TEXT_S, TEXT_A, TEXT_B), rig.texts(), "exactly one row per message")
        } finally {
            assertTrue(walks.close(), "fixture walks did not finish within the cleanup budget")
        }
    }

    /**
     * Positive control for the case above: the same barrier, the same
     * teardown sequence and the same successor admission, with the
     * authority left ALLOWED. If the barrier or the handover machinery
     * were themselves what silenced the acknowledgement, this case would
     * be silent too. It is not: the replayed message completes and its
     * acknowledgement reaches the relay on its own, with the successor
     * owning the lease and untouched.
     */
    @Test
    fun full_stack_the_same_switch_with_the_authority_allowed_acknowledges_the_replay_on_its_own() = runBlocking {
        val rig = buildRig()
        val walks = OwnedWalks()
        try {
        walks.startOldWalk()
        rig.deliver("S", "env-S")
        awaitTrue("S stored") { rig.texts().contains(TEXT_S) }
        awaitTrue("S acked") { "env-S" in rig.relay.acked }
        rig.deliver("B", "env-B")
        awaitTrue("B held behind the gap") { rig.held.existsByEnvelopeId("env-B") }
        rig.relay.withheld.add("env-B")

        val barrier = ReplayBarrier(rig)
        rig.deliver("A", "env-A")
        awaitTrue("A stored") { rig.texts().contains(TEXT_A) }
        withTimeout(AWAIT_TIMEOUT_MS) { barrier.entered.await() }
        assertFalse(rig.processed.exists("env-B"), "barrier reached before B's completion entry")

        // Same fence, same handover, same revocation call and the same
        // confirmed release; only the policy stays Standard.
        walks.raiseFence()
        val teardown = runPrivacyModeTeardown(
            revoke = { rig.egressGate.revokeAndJoin("privacy_mode_change") },
            disconnectAndJoin = { rig.transport.disconnectAndJoin(2_000L) },
            handOverWalk = { walks.ownership.handOver("privacy_mode_change") is Handover.Quiesced },
            release = { TransportManager.ReleaseOutcome(xrayFailure = null, torFailure = null) },
        )
        assertTrue(teardown.walkQuiesced && walks.oldJoined.get(), "the old walk was handed over")
        withTimeout(5_000L) { walks.oldFinished.await() }
        assertTrue(teardown.confirmed, "confirmed teardown: $teardown")
        val successor = assertNotNull(walks.admitSuccessorIfConfirmed(teardown))
        assertTrue(successor != walks.oldToken)

        barrier.release.complete(Unit)
        awaitTrue("the replayed message completes durably") { rig.processed.exists("env-B") }
        awaitTrue("and is acknowledged without any help") { "env-B" in rig.relay.acked }
        assertEquals(listOf(TEXT_S, TEXT_A, TEXT_B), rig.texts(), "exactly one row per message")
        assertFalse(walks.successorCancelRequested.get(), "the successor walk was never touched")
        assertTrue(checkNotNull(walks.successorJob).isActive, "and is still running")
        assertEquals(successor, walks.ownership.currentOwner())
        assertFalse(
            traceSnapshot().any { it.startsWith("REST_EGRESS blocked operation=ack") },
            "nothing was refused: the silence in the revoked case is the authority, not the rig",
        )
        } finally {
            assertTrue(walks.close(), "fixture walks did not finish within the cleanup budget")
        }
    }

    @Test
    fun full_stack_a_new_message_is_held_behind_the_gap() = runBlocking {
        val rig = buildRig()
        rig.deliver("S", "env-S")
        awaitTrue("S stored") { rig.texts().contains(TEXT_S) }

        rig.deliver("B", "env-B")
        awaitTrue("B held") { rig.held.existsByEnvelopeId("env-B") }
        rig.deliver("C", "env-C")
        awaitTrue("C held") {
            rig.held.listByConversation(rig.convId).any { it.envelopeId == "env-C" }
        }

        assertEquals(listOf(TEXT_S), rig.texts(), "neither successor can skip A")
        assertEquals(
            setOf("env-B", "env-C"),
            rig.held.listByConversation(rig.convId).map { it.envelopeId }.toSet(),
            "both the out-of-order message and the new one are held",
        )
        assertFalse("env-C" in rig.relay.acked)
        assertFalse(rig.processed.exists("env-C"))
        assertNull(rig.opks.get(rig.opkIdHex), "the pre-key stays consumed")
        rig.relay.withheld.addAll(listOf("env-B", "env-C"))
        rig.deliver("A", "env-A")
        awaitTrue("the whole gap drains locally") { rig.texts().contains(TEXT_C) }
        awaitTrue("C ACK") { "env-C" in rig.relay.acked }
        assertEquals(listOf(TEXT_S, TEXT_A, TEXT_B, TEXT_C), rig.texts())
        assertEquals(0, rig.held.count())
    }

    @Test
    fun full_stack_storage_and_hold_failure_releases_the_same_id_without_ack() = runBlocking {
        val rig = buildRig(failCommitForEnvelope = "env-A")
        rig.deliver("S", "env-S")
        awaitTrue("S ACK") { "env-S" in rig.relay.acked }
        val before = rig.ratchetStates.getRatchetState(rig.convId)
        rig.failHeldStorage(true)
        rig.deliver("A", "env-A")
        letPollsElapse(rig, 4)
        assertTrue(logLines("inbound_commit_failed").size >= 2,
            "the same id must return, not remain in pendingAck")
        assertEquals(listOf(TEXT_S), rig.texts())
        assertEquals(before, rig.ratchetStates.getRatchetState(rig.convId))
        assertFalse(rig.processed.exists("env-A"))
        assertEquals(0, rig.held.count())
        assertFalse("env-A" in rig.relay.acked)
        assertTrue("env-A" in rig.relay.outstanding())
        rig.failHeldStorage(false)
        rig.failCommitFor.set(null)
        awaitTrue("the surviving relay copy lands") { "env-A" in rig.relay.acked }
        assertEquals(listOf(TEXT_S, TEXT_A), rig.texts())
        assertTrue(rig.ackObservations.all { it.second })
    }

    @Test
    fun full_stack_an_ack_request_for_a_parked_uncommitted_envelope_is_refused() = runBlocking {
        val rig = buildRig()
        rig.deliver("S", "env-S")
        awaitTrue("S ACK") { "env-S" in rig.relay.acked }
        rig.deliver("B", "env-B")
        awaitTrue("B held") { rig.held.existsByEnvelopeId("env-B") }
        assertFalse(rig.transport.sendDeliveryAck("env-B"))
        assertFalse("env-B" in rig.relay.acked)
        assertTrue("env-B" in rig.relay.outstanding())
        assertFalse(rig.ackObservations.any { it.first == "env-B" })
        assertFalse(rig.processed.exists("env-B"))
    }

    @Test
    fun full_stack_an_out_of_order_first_contact_is_held_until_its_predecessors_commit() = runBlocking {
        val rig = buildRig(freshContact = true)
        rig.deliver("B", "env-B")
        awaitTrue("first-contact successor held") { rig.held.existsByEnvelopeId("env-B") }
        assertEquals(emptyList(), rig.texts())
        assertNull(rig.ratchetStates.getRatchetState(rig.convId))
        assertNotNull(rig.opks.get(rig.opkIdHex))
        assertFalse(rig.processed.exists("env-B"))
        assertFalse("env-B" in rig.relay.acked)
        rig.relay.withheld.add("env-B")
        rig.deliver("S", "env-S")
        awaitTrue("first contact commits") { "env-S" in rig.relay.acked }
        rig.deliver("A", "env-A")
        awaitTrue("held first arrival recovers") { "env-B" in rig.relay.acked }
        assertEquals(listOf(TEXT_S, TEXT_A, TEXT_B), rig.texts())
        assertNull(rig.opks.get(rig.opkIdHex))
        assertEquals(0, rig.held.count())
    }

    @Test
    fun full_stack_receive_progress_remains_eligible_beyond_three_attempts() = runBlocking {
        val rig = buildRig()
        rig.deliver("S", "env-S")
        awaitTrue("S ACK") { "env-S" in rig.relay.acked }
        rig.deliver("E", "env-E")
        awaitTrue("E held") { rig.held.existsByEnvelopeId("env-E") }
        rig.relay.withheld.add("env-E")
        for ((index, predecessor) in listOf("A", "B", "C").withIndex()) {
            rig.deliver(predecessor, "env-$predecessor")
            awaitTrue("progress retried E after $predecessor") {
                rig.held.listByConversation(rig.convId).single { it.envelopeId == "env-E" }
                    .replayAttemptCount >= index + 2L
            }
        }
        assertFalse("env-E" in rig.relay.acked)
        rig.deliver("D", "env-D")
        awaitTrue("E recovers after more than three failures") { "env-E" in rig.relay.acked }
        assertEquals(listOf(TEXT_S, TEXT_A, TEXT_B, TEXT_C, "D", "E"), rig.texts())
        assertFalse(rig.held.existsByEnvelopeId("env-E"))
    }

    @Test
    fun full_stack_a_forgery_is_not_decrypted_again_at_the_same_receive_version() = runBlocking {
        val rig = buildRig()
        rig.deliver("S", "env-S")
        awaitTrue("S ACK") { "env-S" in rig.relay.acked }
        rig.deliver("A_FORGED", "env-forged")
        awaitTrue("forgery retained with retry metadata") {
            rig.held.listByConversation(rig.convId).any { it.lastReceiveVersion != null }
        }
        val attempts = logLines("DECRYPT_TRACE attempt").size
        val state = rig.ratchetStates.getRatchetState(rig.convId)
        letPollsElapse(rig, 4)
        assertEquals(attempts, logLines("DECRYPT_TRACE attempt").size)
        assertEquals(state, rig.ratchetStates.getRatchetState(rig.convId))
        assertFalse(rig.processed.exists("env-forged"))
        assertFalse("env-forged" in rig.relay.acked)
        rig.deliver("A", "env-A")
        awaitTrue("authentic A still works") { "env-A" in rig.relay.acked }
        assertEquals(listOf(TEXT_S, TEXT_A), rig.texts())
    }

    @Test
    fun full_stack_held_removal_rolls_back_with_chain_message_and_completion() = runBlocking {
        val rig = buildRig(failCommitForEnvelope = "env-A")
        rig.deliver("S", "env-S")
        awaitTrue("S ACK") { "env-S" in rig.relay.acked }
        val before = rig.ratchetStates.getRatchetState(rig.convId)
        rig.deliver("A", "env-A")
        awaitTrue("A held") { rig.held.existsByEnvelopeId("env-A") }
        rig.failInTransactionAt.set("after_completion")
        rig.failCommitFor.set(null)
        awaitTrue("the retry actually fails after all transactional writes") {
            "after_completion" in rig.failedTransactionPoints &&
                rig.held.listByConversation(rig.convId).single().replayAttemptCount >= 2
        }
        assertTrue(rig.held.existsByEnvelopeId("env-A"), "the deletion must roll back too")
        assertEquals(before, rig.ratchetStates.getRatchetState(rig.convId))
        assertFalse(rig.processed.exists("env-A"))
        assertEquals(listOf(TEXT_S), rig.texts())
        assertFalse("env-A" in rig.relay.acked)
        rig.failInTransactionAt.set(null)
        awaitTrue("A completes") { "env-A" in rig.relay.acked }
        assertFalse(rig.held.existsByEnvelopeId("env-A"))
    }

    @Test
    fun restart_replays_a_held_successor_after_commit_without_any_new_inbound() = runBlocking {
        val dbFile = java.io.File.createTempFile("held-restart", ".db")
        dbFile.delete()
        try {
            val first = buildRig(dbFile = dbFile)
            first.deliver("S", "env-S")
            awaitTrue("S ACK") { "env-S" in first.relay.acked }
            first.deliver("B", "env-B")
            awaitTrue("B held") { first.held.existsByEnvelopeId("env-B") }
            first.relay.withheld.add("env-B")
            val committed = kotlinx.coroutines.CompletableDeferred<Unit>()
            first.tx.afterCommit.set {
                committed.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            first.deliver("A", "env-A")
            withTimeout(AWAIT_TIMEOUT_MS) { committed.await() }
            assertTrue(first.processed.exists("env-A"))
            assertTrue(first.held.existsByEnvelopeId("env-B"))
            val problems = releaseResources(
                closeActions = orchestrators.map { o -> suspend { o.close() } },
                scopes = scopes.toList(), drivers = drivers.toList(),
            )
            orchestrators.clear(); scopes.clear(); drivers.clear()
            assertEquals(emptyList(), problems)
            val second = buildRig(reuse = first.seed)
            // No enqueue, send, or manual replay call on this instance.
            awaitTrue("startup drains B") { second.texts().contains(TEXT_B) }
            assertEquals(listOf(TEXT_S, TEXT_A, TEXT_B), second.texts())
            assertTrue(second.processed.exists("env-B"))
            assertFalse(second.held.existsByEnvelopeId("env-B"))
        } finally {
            // @After confirms resource completion before cleaning up files.
            dbFile.deleteOnExit()
        }
    }

    // ── 4. Controls a fix must keep ─────────────────────────────────────

    /**
     * Authenticity, isolated from ordering: a forged copy of A is
     * delivered where A belongs, so the only thing that can reject it is
     * the MAC. It is held, never acked, never marked processed -- and it
     * consumes nothing, because the genuine A decrypts straight after it.
     */
    @Test
    fun full_stack_a_forged_copy_of_the_next_message_is_held_and_the_genuine_one_still_lands() =
        runBlocking {
            val rig = buildRig()
            rig.deliver("S", "env-S")
            awaitTrue("S stored") { rig.texts().contains(TEXT_S) }
            awaitTrue("S acked") { "env-S" in rig.relay.acked }

            rig.deliver("A_FORGED", "env-A-forged")
            awaitTrue("forgery held") {
                rig.held.listByConversation(rig.convId).any { it.envelopeId == "env-A-forged" }
            }
            assertEquals(listOf(TEXT_S), rig.texts(), "a forged envelope never reaches the chat")
            assertFalse("env-A-forged" in rig.relay.acked)
            assertFalse(rig.processed.exists("env-A-forged"))

            // The genuine A, at the very position the forgery claimed.
            rig.deliver("A", "env-A")
            awaitTrue("A stored") { rig.texts().contains(TEXT_A) }
            assertEquals(
                listOf(TEXT_S, TEXT_A), rig.texts(),
                "the forgery consumed nothing: A still decrypts at its position",
            )
        }

    /**
     * A duplicate of an envelope that was already delivered and acked is
     * re-acked without being decrypted again, so it cannot advance the
     * chain a second time.
     */
    @Test
    fun full_stack_a_duplicate_of_an_acked_envelope_is_reacked_not_reprocessed() = runBlocking {
        val rig = buildRig()
        rig.deliver("S", "env-S")
        awaitTrue("S stored") { rig.texts().contains(TEXT_S) }
        awaitTrue("S acked") { "env-S" in rig.relay.acked }
        val acksAfterFirst = rig.relay.acked.count { it == "env-S" }

        // The relay lost the ack and offers the same envelope again.
        rig.deliver("S", "env-S")
        awaitTrue("S re-acked") { rig.relay.acked.count { it == "env-S" } > acksAfterFirst }
        letPollsElapse(rig, 2)

        assertEquals(listOf(TEXT_S), rig.texts(), "the duplicate is not stored a second time")
        assertEquals(0, rig.held.count(), "a duplicate is not held")

        // And the chain was not disturbed: A still decrypts.
        rig.deliver("A", "env-A")
        awaitTrue("A stored") { rig.texts().contains(TEXT_A) }
        assertEquals(listOf(TEXT_S, TEXT_A), rig.texts())
    }

    // ── 5. The fixture's own release, under control ─────────────────────

    /**
     * A cancelled consumer whose `finally` still uses the database must
     * finish before the driver is closed.
     *
     * The previous version of this teardown polled `children.any {
     * it.isActive }`, which reports "quiet" the moment cancellation is
     * requested -- while the `finally` is still running. This case is
     * built so that a poll-based wait passes and only a real join does:
     * the consumer spends its whole `finally` in a NonCancellable block,
     * reading the database at the end of it.
     */
    @Test
    fun teardown_waits_for_a_consumer_that_lingers_in_its_finally() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val park = java.util.concurrent.CountDownLatch(1)
        try {
            PhantomDatabase.Schema.create(driver)
            val ledger = SqlDelightProcessedEnvelopeRepository(PhantomDatabase(driver))
            val started = java.util.concurrent.CountDownLatch(1)
            val finallyFinished = java.util.concurrent.atomic.AtomicBoolean(false)
            val databaseUsableInFinally = java.util.concurrent.atomic.AtomicBoolean(false)

            scope.launch {
                try {
                    started.countDown()
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        delay(300L)
                        databaseUsableInFinally.set(
                            runCatching { ledger.exists("nothing") }.isSuccess,
                        )
                        finallyFinished.set(true)
                    }
                }
            }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))

            val problems = releaseResources(
                closeActions = emptyList(),
                scopes = listOf(scope),
                drivers = listOf(driver),
            )

            assertEquals(emptyList(), problems, "a consumer that finishes in time is not a problem")
            assertTrue(
                finallyFinished.get(),
                "release returned before the cancelled consumer had finished its finally",
            )
            assertTrue(
                databaseUsableInFinally.get(),
                "the database was closed while a cancelled consumer was still reading it",
            )
        } finally {
            forceRelease(scope, driver, park)
        }
    }

    /**
     * The other half: if a consumer will not finish inside the budget,
     * the fixture must say so and must NOT take the database away from
     * it.
     */
    @Test
    fun teardown_reports_and_keeps_the_database_when_a_consumer_will_not_finish() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val park = java.util.concurrent.CountDownLatch(1)
        try {
            PhantomDatabase.Schema.create(driver)
            val ledger = SqlDelightProcessedEnvelopeRepository(PhantomDatabase(driver))
            val started = java.util.concurrent.CountDownLatch(1)

            scope.launch {
                try {
                    started.countDown()
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        // Holds well past the budget below.
                        park.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
            }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))

            val problems = releaseResources(
                closeActions = emptyList(),
                scopes = listOf(scope),
                drivers = listOf(driver),
                quiesceMs = 400L,
            )

            assertTrue(problems.any { it.contains("did not finish") }, "problems: $problems")
            assertTrue(
                problems.any { it.contains("database left open") },
                "the fixture must say why it kept the database: $problems",
            )
            assertTrue(
                runCatching { ledger.exists("nothing") }.isSuccess,
                "the database must stay open while a consumer is still running",
            )
        } finally {
            forceRelease(scope, driver, park)
        }
    }

    /**
     * A close that will not come back inside the budget.
     *
     * `RestFallbackOrchestrator.close` runs its whole body inside
     * `withContext(NonCancellable)` and waits there for its own jobs, so
     * a `withTimeoutOrNull` around the CALL cannot end it. The fixture
     * therefore issues the attempt separately and bounds only its own
     * wait. This case models exactly that shape: a close that parks in a
     * NonCancellable block.
     *
     * What must happen: the waiter returns unconfirmed on budget, the
     * database is NOT closed, and when the park is released the SAME
     * attempt finishes -- it was never abandoned or cancelled.
     */
    @Test
    fun a_close_that_does_not_return_leaves_the_release_unconfirmed() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val park = java.util.concurrent.CountDownLatch(1)
        val closeEntered = java.util.concurrent.CountDownLatch(1)
        val closeFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        // The handle on the attempt this case issued. The release itself
        // hands it back, so the cleanup below can wait for the SAME one
        // instead of starting another.
        val attempts = mutableListOf<Job>()
        var bodyFailed = false
        try {
            PhantomDatabase.Schema.create(driver)
            val ledger = SqlDelightProcessedEnvelopeRepository(PhantomDatabase(driver))

            val wedgedClose: suspend () -> Unit = {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    closeEntered.countDown()
                    park.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    closeFinished.set(true)
                }
            }

            val problems = releaseResources(
                closeActions = listOf(wedgedClose),
                scopes = emptyList(),
                drivers = listOf(driver),
                quiesceMs = 400L,
                issuedCloseAttempts = attempts,
            )

            assertTrue(
                closeEntered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "the close attempt never started",
            )
            assertTrue(
                problems.any { it.contains("did not answer") },
                "the fixture must report an unanswered close: $problems",
            )
            assertTrue(
                problems.any { it.contains("database left open") },
                "and must say why it kept the database: $problems",
            )
            assertFalse(
                closeFinished.get(),
                "the close was still parked when the waiter gave up",
            )
            assertTrue(
                runCatching { ledger.exists("nothing") }.isSuccess,
                "the database must stay open while a close attempt is still running",
            )
            assertEquals(1, attempts.size, "the release must hand back what it issued")
        } catch (t: Throwable) {
            bodyFailed = true
            throw t
        } finally {
            // Unconditional, and in this order: unpark, WAIT for the same
            // attempt's Job to complete -- a flag set inside the action is
            // not the Job being finished -- and only then take the
            // database away. If it never finishes, the driver stays open
            // and the fixture says so, unless a real failure is already
            // on its way up.
            park.countDown()
            val finished = withTimeoutOrNull(TEARDOWN_QUIESCE_MS) {
                attempts.forEach { it.join() }
                true
            } ?: false
            if (finished) runCatching { driver.close() }
            if (!bodyFailed && !finished) {
                throw AssertionError(
                    "the unparked close attempt never completed; it was dropped rather " +
                        "than owned, and the database is left open deliberately",
                )
            }
        }
    }

    /**
     * Unconditional release for the two cases above, including the path
     * where an assertion threw before the body could release anything.
     * Unparks the consumer, waits for the job to be COMPLETE (not merely
     * cancelled), and only then closes the driver -- and says so loudly
     * if the job never finished, because a leaked consumer would poison
     * every later case in this class.
     */
    private suspend fun forceRelease(
        scope: CoroutineScope,
        driver: JdbcSqliteDriver,
        park: java.util.concurrent.CountDownLatch,
    ) {
        park.countDown()
        val job = scope.coroutineContext[Job]
        val finished = withTimeoutOrNull(TEARDOWN_QUIESCE_MS) {
            job?.cancelAndJoin() ?: scope.cancel()
            true
        } ?: false
        if (finished) {
            runCatching { driver.close() }
        }
        assertTrue(
            finished && job?.isCompleted != false,
            "a control consumer never finished; the database is left open deliberately",
        )
    }

    private companion object {
        const val TEXT_S = "S: the message that established the session"
        const val TEXT_A = "A: sent first"
        const val TEXT_B = "B: sent second, delivered first"
        const val TEXT_C = "C: typed after the failure"

        /** Matches `RestFallbackOrchestrator.POLL_ACTIVE_MS`. */
        const val POLL_INTERVAL_MS = 2_000L
        const val AWAIT_TIMEOUT_MS = 30_000L

        /** Long enough for cancelled collectors to unwind before the DB closes. */
        const val TEARDOWN_QUIESCE_MS = 2_000L

        fun ByteArray.toHexLower(): String =
            joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
