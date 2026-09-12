// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.messaging

import android.app.Application
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.signature.Signature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import phantom.core.crypto.DhKeyPair
import phantom.core.crypto.DhPublicKey
import phantom.core.crypto.LibsodiumDoubleRatchet
import phantom.core.crypto.LibsodiumX3DH
import phantom.core.crypto.RatchetState
import phantom.core.crypto.SessionRole
import phantom.core.crypto.SignedPreKeySigner
import phantom.core.identity.IdentityRecord
import phantom.core.identity.IdentitySigningKeyPair
import phantom.core.identity.LibsodiumIdentityCrypto
import phantom.core.identity.SigningPrivateKey
import phantom.core.identity.SigningPublicKey
import phantom.core.messaging.DefaultMessagingService
import phantom.core.messaging.OutgoingMessage
import phantom.core.messaging.SessionManager
import phantom.core.storage.ConversationEntity
import phantom.core.storage.KeystoreBlobCipher
import phantom.core.storage.LocalOneTimePreKeyEntity
import phantom.core.storage.LocalSignedPreKeyEntity
import phantom.core.storage.RatchetStateRepository
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
import phantom.core.transport.KtorRelayTransport
import phantom.core.transport.PreKeyApi
import phantom.core.transport.PreKeyBundle
import phantom.core.transport.PreKeyStatus
import phantom.core.transport.PublishRequest
import phantom.core.transport.PublishResult
import phantom.core.transport.RelayMessage
import phantom.core.transport.RelayTransport
import phantom.core.transport.WireOneTimePreKey
import phantom.core.transport.WireSignedPreKey
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression tests for the existing-session admission without the role
 * condition (candidate R1-only, 2026-09-10): a session is used for sending
 * whichever side opened it; `sessionSuspect`, the outbound-pending barrier
 * and the prekey Keystore policy are unchanged. Two real services with real
 * crypto and real SqlDelight storage on both sides; prekey reads are counted
 * at the cipher that wraps private SPK/OPK bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionContinuityRegressionTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    /**
     * Counts every unwrap of prekey private bytes. Production wraps SPK/OPK
     * private bytes with the unlock-gated Keystore key; this cipher stores
     * them in the clear and only counts, so the number is the number of
     * times a locked phone would have had to touch that key.
     */
    private class CountingPrekeyCipher : KeystoreBlobCipher {
        val unwraps = AtomicInteger(0)
        /** When set, models the unlock-gated Keystore key on a locked device: every private read fails. */
        val locked = java.util.concurrent.atomic.AtomicBoolean(false)
        override fun wrap(plaintext: ByteArray): ByteArray = plaintext
        override fun unwrap(wrappedBlob: ByteArray): ByteArray {
            unwraps.incrementAndGet()
            if (locked.get()) throw java.security.InvalidKeyException("model: prekey key requires an unlocked device")
            return wrappedBlob
        }
    }

    /**
     * Model of a peer still running the removed role guard (base
     * `4a669086`: the existing-session admission also required
     * `existingState.role == SessionRole.INITIATOR`). Bound to ONE
     * participant: it wraps that participant's active-session repository
     * as seen by its `SessionManager`, and while one of that participant's
     * sends is in progress it hides a RESPONDER-tagged active row from the
     * session load. The service's own admission then takes the bootstrap
     * branch exactly where the old guard did, and the decision ORDER is the
     * service's own: pending reuse is decided before the active row is
     * loaded, so a reusable outbound pending is reused as at the base.
     * The model writes nothing: the hidden row stays in the database, as it
     * did at the base, and every other read and write goes to the real
     * repository. Verified against the measured base ping-pong in
     * [the_legacy_model_reproduces_the_measured_base_ping_pong].
     *
     * Limits: (1) the forced bootstrap is logged with reason
     * `no_session_row`, not `responder_role_redirected`; (2) it is armed
     * around the test's send call, so it relies on the participant not
     * receiving concurrently -- every send in this class is awaited to
     * completion before the next; (3) the modelled peer's receive path is
     * the candidate's, which is the base's on the receive path (the
     * candidate changes only the send admission).
     */
    private inner class LegacyRoleGuardRepository(private val delegate: RatchetStateRepository) :
        RatchetStateRepository by delegate {
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        override suspend fun getRatchetState(conversationId: String): String? {
            val blob = delegate.getRatchetState(conversationId) ?: return null
            if (armed.get() && json.decodeFromString(RatchetState.serializer(), blob).role == SessionRole.RESPONDER) return null
            return blob
        }
    }

    /**
     * One real participant: own database, own prekeys, own service. The
     * relay hands each `Send` to the other participant as a `Deliver`.
     */
    private inner class Participant(
        val name: String,
        private val scope: CoroutineScope,
        /** This participant runs the [LegacyRoleGuardRepository] model of the removed guard. */
        val legacyRoleGuard: Boolean = false,
        /** Null = private in-memory database; a file = one that survives close/reopen. */
        dbFile: java.io.File? = null,
        /** Reopen: reuse an earlier participant's identity, prekeys and database file. */
        reopenOf: Participant? = null,
    ) {
        val x3dh = LibsodiumX3DH()
        val identityKp: DhKeyPair = reopenOf?.identityKp ?: x3dh.generateDhKeyPair()
        val spk: DhKeyPair = reopenOf?.spk ?: x3dh.generateDhKeyPair()
        val signing: com.ionspin.kotlin.crypto.signature.SignatureKeyPair = reopenOf?.signing ?: Signature.keypair()
        val publicKeyHex: String = identityKp.publicKey.bytes.hex()
        val dbFileUsed: java.io.File? = dbFile ?: reopenOf?.dbFileUsed
        val driver: JdbcSqliteDriver = dbFileUsed?.let { JdbcSqliteDriver("jdbc:sqlite:" + it.absolutePath) }
            ?: JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        init { if (reopenOf == null) PhantomDatabase.Schema.create(driver) }
        val db = PhantomDatabase(driver)
        val prekeyCipher = CountingPrekeyCipher()
        val spks = SqlDelightLocalSignedPreKeyRepository(db, privateKeyCipher = prekeyCipher)
        val opks = SqlDelightLocalOneTimePreKeyRepository(db, privateKeyCipher = prekeyCipher)
        val active = SqlDelightRatchetStateRepository(db)
        private val legacyGuard: LegacyRoleGuardRepository? = if (legacyRoleGuard) LegacyRoleGuardRepository(active) else null
        val pending = SqlDelightPendingRatchetStateRepository(db)
        val messages = SqlDelightMessageRepository(db)
        val conversations = SqlDelightConversationRepository(db)
        val processed = SqlDelightProcessedEnvelopeRepository(db)
        val held = SqlDelightDecryptFailedEnvelopeRepository(db)
        val incoming = MutableSharedFlow<RelayMessage.Deliver>(extraBufferCapacity = 64)
        val acked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val sent: MutableList<RelayMessage.Send> = Collections.synchronizedList(mutableListOf())
        /** When non-null, the next Send from this participant is parked here instead of being delivered. */
        val interceptNext = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<RelayMessage.Deliver>?>(null)
        val opkIds = (1..8).map { "%032x".format(it) }

        /**
         * The PUBLISHED half of this participant's prekeys, kept apart from
         * the private repositories. The relay serves bundles from here only,
         * so handing out a bundle never touches the receiver's private rows
         * and never counts as a prekey unwrap.
         */
        lateinit var publishedSpk: WireSignedPreKey
        val publishedOpks = mutableMapOf<String, WireOneTimePreKey>()
        private val reopenOfExisting = reopenOf != null
        private val reopenPublished: Pair<WireSignedPreKey, Map<String, WireOneTimePreKey>>? =
            reopenOf?.let { it.publishedSpk to it.publishedOpks.toMap() }
        lateinit var peer: Participant
        lateinit var service: DefaultMessagingService
        val conversationId: String get() = listOf(publicKeyHex, peer.publicKeyHex).sorted().joinToString("_")

        suspend fun publishPrekeys() {
            if (reopenOfExisting) { publishedSpk = reopenPublished!!.first; publishedOpks.putAll(reopenPublished.second); return }
            val spkSignature = SignedPreKeySigner.sign(spk.publicKey, 1_000L, signing.secretKey.toByteArray()).hex()
            spks.upsert(LocalSignedPreKeyEntity(keyId = 7L, publicKeyHex = spk.publicKey.bytes.hex(),
                privateKeyHex = spk.privateKey.bytes.hex(), createdAtMs = 1_000L, signatureHex = spkSignature))
            publishedSpk = WireSignedPreKey(7L, spk.publicKey.bytes.hex(), 1_000L, spkSignature)
            for (id in opkIds) {
                val kp = x3dh.generateDhKeyPair()
                opks.insert(LocalOneTimePreKeyEntity(keyIdHex = id, publicKeyHex = kp.publicKey.bytes.hex(),
                    privateKeyHex = kp.privateKey.bytes.hex(), uploadedAtMs = 0L))
                publishedOpks[id] = WireOneTimePreKey(id, kp.publicKey.bytes.hex())
            }
        }

        /**
         * The bundle the OTHER side fetches: this participant's PUBLISHED
         * keys, one OPK at a time, served from the fixture's public copy.
         * The private repositories are not consulted, so a fetch cannot
         * inflate the unwrap count; the test asserts exactly that.
         */
        private val handedOut = AtomicInteger(0)
        fun bundleForPeer(): PreKeyBundle {
            val unwrapsBefore = prekeyCipher.unwraps.get()
            val next = opkIds.getOrNull(handedOut.getAndIncrement())
            val bundle = PreKeyBundle(
                identity_pubkey_hex = publicKeyHex,
                signing_pubkey_hex = signing.publicKey.toByteArray().hex(),
                signed_pre_key = publishedSpk,
                one_time_pre_key = next?.let { publishedOpks.getValue(it) },
            )
            assertEquals(unwrapsBefore, prekeyCipher.unwraps.get(), "$name: serving a public bundle must not unwrap a private prekey")
            return bundle
        }

        fun wire() {
            val api = object : PreKeyApi {
                override suspend fun fetchBundle(identityPubkeyHex: String, requesterPubkeyHex: String?): PreKeyBundle {
                    assertEquals(peer.publicKeyHex, identityPubkeyHex, "$name fetched a bundle for someone else")
                    return peer.bundleForPeer()
                }
                override suspend fun publishBundle(forceJoinInFlight: Boolean, requestProvider: suspend () -> PublishRequest): PublishResult =
                    error("$name must not publish in this test")
                override suspend fun fetchStatus(identityPubkeyHex: String, requesterPubkeyHex: String?): PreKeyStatus =
                    error("$name must not fetch status in this test")
            }
            val self = this
            val transport = object : RelayTransport by KtorRelayTransport(httpClientFactory = { error("no network") }) {
                override val incoming = self.incoming
                override suspend fun send(message: RelayMessage.Send): Boolean {
                    self.sent.add(message)
                    // The relay: the other participant receives exactly this envelope.
                    check(message.to == peer.publicKeyHex) { "$name addressed ${message.to.take(8)}, not its peer" }
                    val deliver = RelayMessage.Deliver(from = "", sealedSender = message.sealedSender,
                        payload = message.payload, messageId = message.messageId)
                    val parked = self.interceptNext.getAndSet(null)
                    if (parked != null) parked.complete(deliver) else peer.incoming.emit(deliver)
                    return true
                }
                override suspend fun sendDeliveryAck(messageId: String): Boolean {
                    check(processed.exists(messageId)) { "$name acked $messageId before its completion entry" }
                    acked.add(messageId)
                    return true
                }
            }
            val sessions = SessionManager(x3dh, legacyGuard ?: active, spks, opks, LibsodiumIdentityCrypto(), json,
                opkReservationRepository = SqlDelightOpkReservationRepository(db))
            service = DefaultMessagingService(
                identity = IdentityRecord(name, name, publicKeyHex, identityKp.privateKey.bytes.hex(), 0L),
                localKeyPair = identityKp, ratchet = LibsodiumDoubleRatchet(), sessionManager = sessions,
                transport = transport, messageRepository = messages, conversationRepository = conversations,
                processedEnvelopeRepository = processed, scope = scope, json = json, preKeyApi = api,
                signingKeyProvider = { IdentitySigningKeyPair(SigningPublicKey(signing.publicKey.toByteArray()),
                    SigningPrivateKey(signing.secretKey.toByteArray())) },
                decryptFailedEnvelopeRepository = held, holdMacFailures = true,
                sessionTransactionRepository = SqlDelightSessionTransactionRepository(db),
                opkReservationRepository = SqlDelightOpkReservationRepository(db),
                pendingRatchetStateRepository = pending,
            )
        }

        suspend fun knowPeer() {
            conversations.upsertConversation(ConversationEntity(id = conversationId, theirUsername = peer.name,
                theirPublicKeyHex = peer.publicKeyHex, lastMessagePreview = "", lastMessageAt = 0L, unreadCount = 0,
                sessionSuspect = false, sessionSuspectSetAtMs = null))
        }

        /** Every send in these tests goes through here, so the legacy model is armed for exactly this send. */
        suspend fun send(id: String, text: String) {
            legacyGuard?.armed?.set(true)
            try {
                service.sendMessage(OutgoingMessage(id, conversationId, peer.publicKeyHex, text)).getOrThrow()
            } finally { legacyGuard?.armed?.set(false) }
        }

        suspend fun texts(): List<String?> = messages.getMessages(conversationId).map { it.plaintextCache }
        suspend fun inboundTexts(): List<String?> = messages.getMessages(conversationId).filter { !it.sent }.map { it.plaintextCache }
        suspend fun activeRole(): SessionRole? = active.getRatchetState(conversationId)
            ?.let { json.decodeFromString(RatchetState.serializer(), it).role }
        suspend fun hasPending(): Boolean = pending.get(conversationId) != null
    }

    private suspend fun awaitTrue(what: String, predicate: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(25L)
        }
        fail("timed out waiting for: $what | SEND_TRACE: " + sendTrace().takeLast(12))
    }

    private fun sendTrace(): List<String> = ShadowLog.getLogs().mapNotNull { it.msg }
        .filter { it.contains("SEND_TRACE bootstrap_path") || it.contains("SEND_TRACE session_existing") || it.contains("SEND_TRACE pending_reuse ") }

    /** One send observed end to end: which branch the sender took, how many prekey unwraps the receiver needed. */
    private data class Step(val label: String, val branch: String, val receiverPrekeyUnwraps: Int,
        val senderRoleAfter: SessionRole?, val receiverRoleAfter: SessionRole?, val receiverPendingAfter: Boolean)

    /** One send, outcome RECORDED rather than asserted: delivered/held/acked, branch, prekey reads. */
    private data class Outcome(val label: String, val branch: String, val delivered: Boolean, val held: Boolean,
        val completion: Boolean, val acked: Boolean, val receiverPrekeyUnwraps: Int)

    /**
     * The three send branches, by their own SEND_TRACE markers for THIS
     * conversation. Anything else is reported as `unknown` and is never
     * counted as a bootstrap.
     */
    private fun classifyBranch(lines: List<String>, convTag: String): String {
        val mine = lines.filter { it.contains("conv=$convTag") }
        return when {
            mine.any { it.contains("SEND_TRACE session_existing") } -> "existing_session"
            mine.any { it.contains("SEND_TRACE pending_reuse ") } -> "pending_reuse"
            mine.any { it.contains("SEND_TRACE bootstrap_path") } ->
                "bootstrap:" + mine.first { it.contains("SEND_TRACE bootstrap_path") }.substringAfter("reason=").substringBefore(' ')
            else -> "unknown"
        }
    }

    /**
     * One send whose RECEIVER-side processing is awaited to completion, not
     * to a first visible effect. Completion of an envelope is observed at
     * the receiver's `finally`: an ACK reaching the relay for a settled
     * envelope, or a `recipient_deliver_failed` bridge event for an
     * unsettled one -- both are emitted after every state write of that
     * receive, including `sessionSuspect`. No fixed sleep.
     */
    private suspend fun attempt(from: Participant, to: Participant, id: String, text: String, waitMs: Long = 10_000L): Outcome {
        val tracesBefore = sendTrace().size
        val unwrapsBefore = to.prekeyCipher.unwraps.get()
        from.send(id, text)
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline && !(id in to.acked) && !bridgeEvents.contains("recipient_deliver_failed:$id")) delay(25L)
        check(id in to.acked || bridgeEvents.contains("recipient_deliver_failed:$id")) {
            "$id: receiver processing did not complete within ${waitMs}ms (acked=${id in to.acked}, bridge=${bridgeEvents.filter { it.endsWith(":$id") }})"
        }
        val branch = classifyBranch(sendTrace().drop(tracesBefore), from.conversationId.take(12))
        return Outcome(id, branch, to.inboundTexts().contains(text), to.held.existsByEnvelopeId(id),
            to.processed.exists(id), id in to.acked, to.prekeyCipher.unwraps.get() - unwrapsBefore)
    }

    /** Bridge events `event:correlationId`, installed per sequence; see [attempt]. */
    private val bridgeEvents: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private fun installBridge() {
        bridgeEvents.clear()
        phantom.core.messaging.WssDiagBridgeHolder.instance = object : phantom.core.messaging.WssDiagBridge {
            override fun emit(event: String, correlationId: String, role: phantom.core.messaging.WssDiagBridge.Role,
                outcomeFlag: phantom.core.messaging.WssDiagBridge.OutcomeFlag,
                dedupGate: phantom.core.messaging.WssDiagBridge.DedupGate?,
                deliverFailure: phantom.core.messaging.WssDiagBridge.DeliverFailure?,
                deliverStage: phantom.core.messaging.WssDiagBridge.DeliverStage?, attempt: Int?) {
                bridgeEvents += "$event:$correlationId"
            }
        }
    }

    private suspend fun exchange(from: Participant, to: Participant, id: String, text: String): Step {
        val tracesBefore = sendTrace().size
        val unwrapsBefore = to.prekeyCipher.unwraps.get()
        from.send(id, text)
        awaitTrue("$id delivered to ${to.name}") { to.inboundTexts().contains(text) }
        awaitTrue("$id acked by ${to.name}") { id in to.acked }
        assertTrue(to.processed.exists(id), "$id: completion entry present at ${to.name}")
        val branchLine = sendTrace().drop(tracesBefore).single { it.contains(from.conversationId.take(12)) }
        val branch = when {
            "bootstrap_path" in branchLine -> "bootstrap:" + branchLine.substringAfter("reason=").trim()
            "session_existing" in branchLine -> "existing_session"
            else -> "pending_reuse"
        }
        return Step(id, branch, to.prekeyCipher.unwraps.get() - unwrapsBefore, from.activeRole(), to.activeRole(), to.hasPending())
    }

    /** Cancels the scope and closes every driver that exists; runs after a failed setup as well. */
    private suspend fun teardown(scope: CoroutineScope, vararg parts: Participant?) {
        withContext(NonCancellable) { withTimeout(10_000L) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
        parts.forEach { p -> p?.let { runCatching { it.driver.close() } } }
        phantom.core.messaging.WssDiagBridgeHolder.instance = null
    }

    private suspend fun establish(a: Participant, b: Participant) {
        a.peer = b; b.peer = a
        a.publishPrekeys(); b.publishPrekeys(); a.wire(); b.wire(); a.knowPeer(); b.knowPeer()
        a.service.startReceiving(); b.service.startReceiving()
    }

    /** R1: after first contact, every change of direction continues on one session; no prekey read on either side. */
    @Test
    fun direction_changes_continue_one_session_without_new_bootstraps() = runBlocking<Unit> {
        LibsodiumInitializer.initialize(); ShadowLog.clear()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val a = Participant("a", scope); val b = Participant("b", scope)
        try {
            establish(a, b)
            val steps = listOf(exchange(a, b, "m1", "A1"), exchange(b, a, "m2", "B1"), exchange(a, b, "m3", "A2"),
                exchange(b, a, "m4", "B2"), exchange(a, b, "m5", "A3"))
            assertEquals("bootstrap:no_session_row", steps[0].branch)
            assertTrue(steps[0].receiverPrekeyUnwraps >= 1, "first contact reads B's prekeys")
            for (step in steps.drop(1)) {
                assertEquals("existing_session", step.branch, "${step.label} continued on the existing session")
                assertEquals(0, step.receiverPrekeyUnwraps, "${step.label}: no private prekey read")
            }
            assertEquals(listOf("A1", "A2", "A3"), b.inboundTexts()); assertEquals(listOf("B1", "B2"), a.inboundTexts())
        } finally { teardown(scope, a, b) }
    }

    /** R1 under the locked-device model: after establishment, private prekeys become unreadable on both sides; the conversation continues. */
    @Test
    fun the_conversation_continues_while_private_prekeys_are_unreadable() = runBlocking<Unit> {
        LibsodiumInitializer.initialize(); ShadowLog.clear()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val a = Participant("a", scope); val b = Participant("b", scope)
        try {
            establish(a, b)
            exchange(a, b, "m1", "A1"); exchange(b, a, "m2", "B1")
            a.prekeyCipher.locked.set(true); b.prekeyCipher.locked.set(true)
            val ua = a.prekeyCipher.unwraps.get(); val ub = b.prekeyCipher.unwraps.get()
            for (s in listOf(exchange(a, b, "m3", "A2"), exchange(b, a, "m4", "B2"), exchange(a, b, "m5", "A3"))) {
                assertEquals("existing_session", s.branch, s.label)
            }
            assertEquals(0, a.prekeyCipher.unwraps.get() - ua); assertEquals(0, b.prekeyCipher.unwraps.get() - ub)
        } finally { teardown(scope, a, b) }
    }

    /**
     * Kept behaviour: a forgery at the awaited position is rejected without
     * advancing the chain, the genuine envelope then lands, and -- because
     * the held MAC failure still marks the conversation suspect -- the
     * receiver's NEXT send bootstraps exactly once, after which both
     * directions continue on the new pair.
     */
    @Test
    fun a_forgery_is_rejected_and_the_kept_suspect_flag_costs_exactly_one_bootstrap() = runBlocking<Unit> {
        LibsodiumInitializer.initialize(); ShadowLog.clear(); installBridge()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val a = Participant("a", scope); val b = Participant("b", scope)
        try {
            establish(a, b)
            exchange(a, b, "m1", "A1"); exchange(b, a, "m2", "B1")
            val parked = java.util.concurrent.CompletableFuture<RelayMessage.Deliver>()
            a.interceptNext.set(parked)
            a.send("m3", "A2")
            val genuine = parked.get(10, java.util.concurrent.TimeUnit.SECONDS)
            val chainBefore = assertNotNull(b.active.getRatchetState(b.conversationId))
            val frameBytes = phantom.core.crypto.MessagePadding.unpad(kotlin.io.encoding.Base64.decode(genuine.payload))
            val frame = json.decodeFromString(phantom.core.messaging.WireFrame.serializer(), frameBytes.decodeToString())
            val forged = frame.copy(encryptedMessage = frame.encryptedMessage.copy(
                ciphertext = frame.encryptedMessage.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }))
            val forgedPayload = kotlin.io.encoding.Base64.encode(phantom.core.crypto.MessagePadding.pad(
                json.encodeToString(phantom.core.messaging.WireFrame.serializer(), forged).encodeToByteArray()))
            b.incoming.emit(genuine.copy(payload = forgedPayload, messageId = "forged-m3"))
            awaitTrue("forgery processed") { bridgeEvents.contains("recipient_deliver_failed:forged-m3") }
            assertTrue(b.held.existsByEnvelopeId("forged-m3")); assertTrue("forged-m3" !in b.acked); assertTrue(!b.processed.exists("forged-m3"))
            assertEquals(chainBefore, b.active.getRatchetState(b.conversationId), "receive chain unchanged by the forgery")
            assertTrue(b.conversations.getConversation(b.conversationId)!!.sessionSuspect, "the kept flag is set by the held MAC failure")
            b.incoming.emit(genuine)
            awaitTrue("genuine m3 lands") { "m3" in b.acked }
            val after = listOf(exchange(b, a, "m4", "B2"), exchange(a, b, "m5", "A3"), exchange(b, a, "m6", "B3"))
            assertEquals("bootstrap:session_suspect", after[0].branch, "exactly one bootstrap, on the suspect side's next send")
            assertEquals("existing_session", after[1].branch); assertEquals("existing_session", after[2].branch)
            assertTrue(!b.conversations.getConversation(b.conversationId)!!.sessionSuspect, "cleared by the bootstrap commit")
            assertEquals(listOf("A1", "A2", "A3"), b.inboundTexts()); assertEquals(listOf("B1", "B2", "B3"), a.inboundTexts())
        } finally { teardown(scope, a, b) }
    }

    /** Kept recovery after a lost position: m3 lost in transit; the peer's reply bootstraps once and the direction recovers. */
    @Test
    fun after_a_lost_envelope_the_reply_bootstraps_once_and_the_direction_recovers() = runBlocking<Unit> {
        LibsodiumInitializer.initialize(); ShadowLog.clear(); installBridge()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val a = Participant("a", scope); val b = Participant("b", scope)
        try {
            establish(a, b)
            exchange(a, b, "m1", "A1"); exchange(b, a, "m2", "B1")
            val lost = java.util.concurrent.CompletableFuture<RelayMessage.Deliver>()
            a.interceptNext.set(lost); a.send("m3", "A2-lost"); lost.get(10, java.util.concurrent.TimeUnit.SECONDS)
            val m4 = attempt(a, b, "m4", "A3"); val m5 = attempt(b, a, "m5", "B2"); val m6 = attempt(a, b, "m6", "A4")
            assertEquals("existing_session", m4.branch); assertTrue(m4.held && !m4.acked, "m4 is held behind the lost position")
            assertEquals("bootstrap:session_suspect", m5.branch, "the reply bootstraps once"); assertTrue(m5.delivered && m5.acked)
            assertEquals("existing_session", m6.branch); assertTrue(m6.delivered && m6.acked, "the direction recovered")
            assertEquals(listOf("A1", "A4"), b.inboundTexts()); assertEquals(1L, b.held.count(), "m4 stays held: no skipped-key cache")
        } finally { teardown(scope, a, b) }
    }

    /**
     * The legacy model checked against the measured base: two modelled
     * participants, five alternating messages from first contact. At the
     * base (control package, 2026-09-10) every one of the five was a
     * bootstrap, the receiver read its private prekeys twice each time, and
     * from the second message on both sides' active rows were RESPONDER.
     * Only the bootstrap reason label differs (see the model's limits).
     */
    @Test
    fun the_legacy_model_reproduces_the_measured_base_ping_pong() = runBlocking<Unit> {
        LibsodiumInitializer.initialize(); ShadowLog.clear()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val a = Participant("a", scope, legacyRoleGuard = true); val b = Participant("b", scope, legacyRoleGuard = true)
        try {
            establish(a, b)
            val steps = listOf(exchange(a, b, "m1", "A1"), exchange(b, a, "m2", "B1"), exchange(a, b, "m3", "A2"),
                exchange(b, a, "m4", "B2"), exchange(a, b, "m5", "A3"))
            assertEquals("bootstrap:no_session_row", steps[0].branch)
            assertNull(steps[0].senderRoleAfter, "m1: the sender holds only its outbound pending")
            for (step in steps) {
                assertTrue(step.branch.startsWith("bootstrap:"), "${step.label}: ${step.branch}")
                assertEquals(2, step.receiverPrekeyUnwraps, "${step.label}: receiver reads SPK and OPK")
                assertEquals(SessionRole.RESPONDER, step.receiverRoleAfter, step.label)
            }
            for (step in steps.drop(1)) assertEquals(SessionRole.RESPONDER, step.senderRoleAfter, "${step.label}: the sender's active row was opened by the peer")
            assertEquals(listOf("A1", "A2", "A3"), b.inboundTexts()); assertEquals(listOf("B1", "B2"), a.inboundTexts())
            println("R1_REGRESSION model-vs-base: " + steps.joinToString { it.label + "=" + it.branch + "/unwraps" + it.receiverPrekeyUnwraps })
        } finally { teardown(scope, a, b) }
    }

    /** Mixed pair, first message from the NEW side: the legacy side bootstraps once on its first reply, then both continue. */
    @Test
    fun a_legacy_peer_replying_to_a_new_peer_bootstraps_once_then_both_continue() = runBlocking<Unit> {
        LibsodiumInitializer.initialize(); ShadowLog.clear()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val new = Participant("new", scope); val old = Participant("old", scope, legacyRoleGuard = true)
        try {
            establish(new, old)
            val steps = listOf(exchange(new, old, "m1", "1"), exchange(old, new, "m2", "2"), exchange(new, old, "m3", "3"),
                exchange(old, new, "m4", "4"), exchange(new, old, "m5", "5"))
            assertEquals(listOf("1", "3", "5"), old.inboundTexts()); assertEquals(listOf("2", "4"), new.inboundTexts())
            assertEquals(listOf("bootstrap:no_session_row", "existing_session", "existing_session"),
                listOf(steps[0], steps[2], steps[4]).map { it.branch }, "new side")
            assertTrue(steps[1].branch.startsWith("bootstrap:"), "old side replies with a bootstrap: ${steps[1].branch}")
            assertEquals(2, steps[1].receiverPrekeyUnwraps, "that bootstrap reads the new side's private prekeys")
            assertEquals("existing_session", steps[3].branch, "old side continues once its own session was answered")
            assertEquals(SessionRole.INITIATOR, steps[3].senderRoleAfter)
            assertEquals(0, steps[2].receiverPrekeyUnwraps + steps[3].receiverPrekeyUnwraps + steps[4].receiverPrekeyUnwraps)
            println("R1_REGRESSION mixed new-first: " + steps.joinToString { it.label + "=" + it.branch + "/unwraps" + it.receiverPrekeyUnwraps })
        } finally { teardown(scope, new, old) }
    }

    /** Mixed pair, first message from the LEGACY side: nobody bootstraps after first contact. */
    @Test
    fun a_new_peer_replying_to_a_legacy_peer_continues_and_the_legacy_peer_keeps_its_own_session() = runBlocking<Unit> {
        LibsodiumInitializer.initialize(); ShadowLog.clear()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val new = Participant("new", scope); val old = Participant("old", scope, legacyRoleGuard = true)
        try {
            establish(new, old)
            val steps = listOf(exchange(old, new, "m1", "1"), exchange(new, old, "m2", "2"), exchange(old, new, "m3", "3"),
                exchange(new, old, "m4", "4"), exchange(old, new, "m5", "5"))
            assertEquals(listOf("1", "3", "5"), new.inboundTexts()); assertEquals(listOf("2", "4"), old.inboundTexts())
            assertEquals("bootstrap:no_session_row", steps[0].branch)
            for (step in steps.drop(1)) {
                assertEquals("existing_session", step.branch, step.label)
                assertEquals(0, step.receiverPrekeyUnwraps, step.label)
            }
            assertEquals(SessionRole.INITIATOR, steps[2].senderRoleAfter, "the legacy side sends on the session it opened")
            println("R1_REGRESSION mixed legacy-first: " + steps.joinToString { it.label + "=" + it.branch + "/unwraps" + it.receiverPrekeyUnwraps })
        } finally { teardown(scope, new, old) }
    }

    /**
     * The case the earlier flag-based model got wrong: the legacy side holds
     * a RESPONDER active row AND a reusable outbound INITIATOR pending (its
     * own bootstrap reply, not yet answered). The base reuses the pending
     * before it ever looks at the role; the model must do the same.
     */
    @Test
    fun a_legacy_peer_with_a_reusable_outbound_pending_reuses_it_before_the_role_is_considered() = runBlocking<Unit> {
        LibsodiumInitializer.initialize(); ShadowLog.clear()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val new = Participant("new", scope); val old = Participant("old", scope, legacyRoleGuard = true)
        try {
            establish(new, old)
            val m1 = exchange(new, old, "m1", "1")
            val m2 = exchange(old, new, "m2", "2")
            assertTrue(m2.branch.startsWith("bootstrap:"), m2.branch)
            assertEquals(SessionRole.RESPONDER, old.activeRole(), "old: active row still the one the new side opened")
            assertTrue(old.hasPending(), "old: its own outbound pending is present")
            val m3 = exchange(old, new, "m3", "3")
            assertEquals("pending_reuse", m3.branch, "a second send before the reply reuses the pending, as at the base")
            assertEquals(0, m3.receiverPrekeyUnwraps, "no new OPK consumed by the reuse")
            val m4 = exchange(new, old, "m4", "4")
            val m5 = exchange(old, new, "m5", "5")
            assertEquals("existing_session", m4.branch); assertEquals("existing_session", m5.branch)
            assertEquals(listOf("1", "4"), old.inboundTexts()); assertEquals(listOf("2", "3", "5"), new.inboundTexts())
            println("R1_REGRESSION mixed pending-reuse: " + listOf(m1, m2, m3, m4, m5).joinToString { it.label + "=" + it.branch + "/unwraps" + it.receiverPrekeyUnwraps })
        } finally { teardown(scope, new, old) }
    }

    /**
     * Continuation after BOTH participants' database files are closed and
     * reopened by fresh services. Nothing is recreated: no schema, no
     * session, no key; the reopened participants reuse the stored files and
     * the same identity, SPK and signing keys, and the published prekeys are
     * the ones already handed out.
     */
    @Test
    fun the_session_continues_after_the_databases_are_closed_and_reopened() = runBlocking<Unit> {
        LibsodiumInitializer.initialize(); ShadowLog.clear()
        val fileA = java.io.File.createTempFile("r1-reopen-a", ".db").also { it.delete() }
        val fileB = java.io.File.createTempFile("r1-reopen-b", ".db").also { it.delete() }
        var a1: Participant? = null; var b1: Participant? = null
        var a2: Participant? = null; var b2: Participant? = null
        val scope1 = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val scope2 = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        try {
            try {
                a1 = Participant("a", scope1, dbFile = fileA); b1 = Participant("b", scope1, dbFile = fileB)
                establish(a1, b1)
                exchange(a1, b1, "m1", "A1"); exchange(b1, a1, "m2", "B1"); exchange(a1, b1, "m3", "A2")
                assertEquals(SessionRole.INITIATOR, a1.activeRole()); assertEquals(SessionRole.RESPONDER, b1.activeRole())
            } finally { teardown(scope1, a1, b1) } // services cancelled, both files closed
            val a1c = checkNotNull(a1); val b1c = checkNotNull(b1)
            a2 = Participant("a", scope2, reopenOf = a1c); b2 = Participant("b", scope2, reopenOf = b1c)
            a2.peer = b2; b2.peer = a2
            a2.publishPrekeys(); b2.publishPrekeys(); a2.wire(); b2.wire()
            assertEquals(listOf("A1", "A2"), b2.inboundTexts(), "b's stored messages survived the reopen")
            assertEquals(listOf("B1"), a2.inboundTexts(), "a's stored messages survived the reopen")
            assertEquals(SessionRole.INITIATOR, a2.activeRole()); assertEquals(SessionRole.RESPONDER, b2.activeRole())
            a2.service.startReceiving(); b2.service.startReceiving()
            val s4 = exchange(b2, a2, "m4", "B2"); val s5 = exchange(a2, b2, "m5", "A3"); val s6 = exchange(b2, a2, "m6", "B3")
            assertEquals("existing_session", s4.branch, "after reopen b continues on its stored session")
            assertEquals("existing_session", s5.branch); assertEquals("existing_session", s6.branch)
            assertEquals(0, s4.receiverPrekeyUnwraps + s5.receiverPrekeyUnwraps + s6.receiverPrekeyUnwraps)
            assertEquals(0, a2.prekeyCipher.unwraps.get() + b2.prekeyCipher.unwraps.get(), "no private prekey read after the reopen")
            assertEquals(listOf("A1", "A2", "A3"), b2.inboundTexts()); assertEquals(listOf("B1", "B2", "B3"), a2.inboundTexts())
        } finally {
            teardown(scope2, a2, b2)
            fileA.delete(); fileB.delete()
        }
    }

    /** An already stored sessionSuspect=true (written by an older version) keeps the old recovery: next send bootstraps and clears it. */
    @Test
    fun a_stored_suspect_flag_still_forces_one_bootstrap_on_the_next_send() = runBlocking<Unit> {
        LibsodiumInitializer.initialize(); ShadowLog.clear()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val a = Participant("a", scope); val b = Participant("b", scope)
        try {
            establish(a, b)
            exchange(a, b, "m1", "A1"); exchange(b, a, "m2", "B1")
            a.conversations.setSessionSuspect(a.conversationId, 1L) // as an older build would have left it
            val s3 = exchange(a, b, "m3", "A2")
            assertEquals("bootstrap:session_suspect", s3.branch)
            assertTrue(s3.receiverPrekeyUnwraps >= 1, "the bootstrap read b's prekeys")
            assertTrue(!a.conversations.getConversation(a.conversationId)!!.sessionSuspect, "cleared by the commit")
            assertEquals("existing_session", exchange(b, a, "m4", "B2").branch)
            assertEquals("existing_session", exchange(a, b, "m5", "A3").branch)
        } finally { teardown(scope, a, b) }
    }
}
