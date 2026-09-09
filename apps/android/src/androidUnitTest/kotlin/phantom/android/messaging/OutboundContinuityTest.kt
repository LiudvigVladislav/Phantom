// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.messaging

import android.app.Application
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.signature.Signature
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.core.crypto.*
import phantom.core.identity.*
import phantom.core.messaging.DefaultMessagingService
import phantom.core.messaging.MessagePayload
import phantom.core.messaging.OutgoingMessage
import phantom.core.messaging.SessionManager
import phantom.core.messaging.WireFrame
import phantom.core.storage.*
import phantom.core.storage.db.PhantomDatabase
import phantom.core.transport.*
import java.io.File
import kotlin.test.*

/** Public outbound path, real SQLDelight persistence and crypto; no real relay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalUnsignedTypes::class)
class OutboundContinuityTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    // JDBC 2.0.2 keeps a file transaction's connection in thread-local storage.
    // Its file-driver close() is a no-op. Retain those connections until all
    // consumers have joined, then close them explicitly without changing SQL.
    private class OwnedFileDriver(
        private val jdbc: JdbcSqliteDriver,
    ) : SqlDriver by jdbc {
        val transactionConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<java.sql.Connection>()

        override fun newTransaction(): QueryResult<Transacter.Transaction> {
            transactionConnections.add(jdbc.getConnection())
            return jdbc.newTransaction()
        }

        override fun close() {
            var failure: Throwable? = null
            for (connection in transactionConnections) {
                try {
                    connection.close()
                } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                }
            }
            jdbc.close()
            failure?.let { throw it }
            check(transactionConnections.all { it.isClosed })
        }
    }

    @Test
    fun file_cleanup_closes_connections_retained_after_a_transaction() = runBlocking<Unit> {
        withDatabaseFile("transaction-ownership") { file ->
            val jdbc = JdbcSqliteDriver("jdbc:sqlite:" + file.absolutePath)
            val driver = OwnedFileDriver(jdbc)
            try {
                PhantomDatabase.Schema.create(driver)
                val db = PhantomDatabase(driver)
                db.transaction {
                    driver.execute(null, "CREATE TABLE fixture_ownership (id INTEGER)", 0)
                }
                assertTrue(driver.transactionConnections.isNotEmpty())
                jdbc.close()
                assertTrue(driver.transactionConnections.any { !it.isClosed },
                    "the library close alone does not release the retained connection")
            } finally {
                driver.close()
            }
            assertTrue(driver.transactionConnections.all { it.isClosed })
        }
    }

    private suspend fun withDatabaseFile(prefix: String, block: suspend (File) -> Unit) {
        val file = File.createTempFile(prefix, ".db")
        var bodyFailure: Throwable? = null
        try {
            block(file)
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            // Jobs and JDBC operations have already joined in withSender.
            // A transient Windows sharing violation must not silently leave a file.
            val cleanup = runCatching {
                withContext(NonCancellable) {
                    var lastFailure: java.nio.file.FileSystemException? = null
                    val removed = withTimeoutOrNull(5_000L) {
                        while (file.exists()) {
                            try {
                                java.nio.file.Files.deleteIfExists(file.toPath())
                            } catch (failure: java.nio.file.FileSystemException) {
                                lastFailure = failure
                                delay(25L)
                            }
                        }
                        true
                    }
                    if (removed != true) throw AssertionError("temporary database was not released", lastFailure)
                }
            }.exceptionOrNull()
            if (cleanup != null) {
                val original = bodyFailure
                if (original == null) throw cleanup else original.addSuppressed(cleanup)
            }
        }
    }

    private inner class Peer {
        val x3dh = LibsodiumX3DH()
        val alice = x3dh.generateDhKeyPair()
        val bob = x3dh.generateDhKeyPair()
        val spk = x3dh.generateDhKeyPair()
        val signing = Signature.keypair()
        val recipient = bob.publicKey.bytes.hex()
        val conversation = listOf(alice.publicKey.bytes.hex(), recipient).sorted().joinToString("_")
        val bundle = phantom.core.transport.PreKeyBundle(
            identity_pubkey_hex = recipient,
            signing_pubkey_hex = signing.publicKey.toByteArray().hex(),
            signed_pre_key = WireSignedPreKey(7L, spk.publicKey.bytes.hex(), 0L,
                SignedPreKeySigner.sign(spk.publicKey, 0L, signing.secretKey.toByteArray()).hex()),
            one_time_pre_key = null,
        )
        var clock = 1_000L
        var fetches = 0
        val api = object : PreKeyApi {
            override suspend fun fetchBundle(identityPubkeyHex: String, requesterPubkeyHex: String?): phantom.core.transport.PreKeyBundle {
                assertEquals(recipient, identityPubkeyHex)
                fetches++
                return bundle
            }
            override suspend fun publishBundle(forceJoinInFlight: Boolean, requestProvider: suspend () -> PublishRequest): PublishResult =
                error("unexpected publish")
            override suspend fun fetchStatus(identityPubkeyHex: String, requesterPubkeyHex: String?): PreKeyStatus =
                error("unexpected status")
        }
        fun receiveState(frame: WireFrame): RatchetState {
            val header = requireNotNull(frame.x3dhInit)
            val ephemeral = header.ephemeralPubKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return x3dh.recipientHandshake4DH(bob, spk, null, alice.publicKey, DhPublicKey(ephemeral))
        }
    }

    private inner class Sender(val db: PhantomDatabase, val service: DefaultMessagingService,
        val incoming: MutableSharedFlow<RelayMessage.Deliver>, val acked: MutableList<String>) {
        val active = SqlDelightRatchetStateRepository(db)
        val pending = SqlDelightPendingRatchetStateRepository(db)
        val messages = SqlDelightMessageRepository(db)
        suspend fun send(peer: Peer, id: String): WireFrame {
            service.sendMessage(OutgoingMessage(id, peer.conversation, peer.recipient, id)).getOrThrow()
            val row = assertNotNull(messages.getMessageById(id))
            return json.decodeFromString(row.ciphertext.decodeToString())
        }
    }

    private suspend fun <T> withSender(file: File, peer: Peer, create: Boolean, block: suspend (Sender) -> T): T {
        val driver = OwnedFileDriver(JdbcSqliteDriver("jdbc:sqlite:" + file.absolutePath))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            if (create) PhantomDatabase.Schema.create(driver)
            val db = PhantomDatabase(driver)
            val active = SqlDelightRatchetStateRepository(db)
            val sessions = SessionManager(peer.x3dh, active, SqlDelightLocalSignedPreKeyRepository(db),
                SqlDelightLocalOneTimePreKeyRepository(db), LibsodiumIdentityCrypto(), json)
            val incoming = MutableSharedFlow<RelayMessage.Deliver>()
            val acked = java.util.Collections.synchronizedList(mutableListOf<String>())
            // Delegation provides the transport shape but connect is never called.
            val transport = object : RelayTransport by KtorRelayTransport(httpClientFactory = { error("no network") }) {
                override val incoming = incoming
                override suspend fun send(message: RelayMessage.Send) = true
                override suspend fun sendDeliveryAck(messageId: String): Boolean {
                    check(SqlDelightProcessedEnvelopeRepository(db).exists(messageId))
                    acked.add(messageId)
                    return true
                }
            }
            val service = DefaultMessagingService(
                identity = IdentityRecord("continuity", "alice", peer.alice.publicKey.bytes.hex(),
                    peer.alice.privateKey.bytes.hex(), 0L),
                localKeyPair = peer.alice, ratchet = LibsodiumDoubleRatchet(), sessionManager = sessions,
                transport = transport, messageRepository = SqlDelightMessageRepository(db),
                conversationRepository = SqlDelightConversationRepository(db), scope = scope, json = json,
                preKeyApi = peer.api,
                signingKeyProvider = { IdentitySigningKeyPair(SigningPublicKey(peer.signing.publicKey.toByteArray()),
                    SigningPrivateKey(peer.signing.secretKey.toByteArray())) },
                pendingRatchetStateRepository = SqlDelightPendingRatchetStateRepository(db),
                sessionTransactionRepository = SqlDelightSessionTransactionRepository(db),
                processedEnvelopeRepository = SqlDelightProcessedEnvelopeRepository(db),
                decryptFailedEnvelopeRepository = SqlDelightDecryptFailedEnvelopeRepository(db),
                holdMacFailures = true,
                nowMsProvider = { peer.clock },
            )
            return block(Sender(db, service, incoming, acked))
        } finally {
            // Closing SQL follows confirmed scope completion, including on assertion failure.
            withContext(NonCancellable) { withTimeout(5_000L) { scope.coroutineContext[Job]!!.cancelAndJoin() } }
            driver.close()
        }
    }

    @Test
    fun a_headerless_reply_can_promote_pending_when_active_is_absent() = runBlocking<Unit> {
        LibsodiumInitializer.initialize()
        val peer = Peer()
        withDatabaseFile("pending-reply") { file ->
            withSender(file, peer, true) { sender ->
                val first = sender.send(peer, "first")
                assertNull(sender.active.getRatchetState(peer.conversation))
                val crypto = LibsodiumDoubleRatchet()
                val bobState = crypto.decrypt(peer.receiveState(first), first.encryptedMessage).first
                val replyPlain = json.encodeToString(MessagePayload(text = "reply", sentAt = 1L)).encodeToByteArray()
                val reply = WireFrame(encryptedMessage = crypto.encrypt(bobState, replyPlain).second)
                sender.service.startReceiving()
                withTimeout(5_000L) { sender.incoming.subscriptionCount.first { it > 0 } }
                @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                fun payload(frame: WireFrame) = kotlin.io.encoding.Base64.encode(
                    MessagePadding.pad(json.encodeToString(frame).encodeToByteArray()))
                val pendingBefore = sender.pending.get(peer.conversation)!!.stateBlob
                val forged = reply.copy(encryptedMessage = reply.encryptedMessage.copy(
                    ciphertext = reply.encryptedMessage.ciphertext.copyOf()
                        .also { it[0] = (it[0].toInt() xor 1).toByte() }))
                sender.incoming.emit(RelayMessage.Deliver(from = peer.recipient, sealedSender = "",
                    payload = payload(forged), messageId = "forged-reply"))
                val held = SqlDelightDecryptFailedEnvelopeRepository(sender.db)
                withTimeout(5_000L) {
                    while (held.listByConversation(peer.conversation).none { it.envelopeId == "forged-reply" }) delay(10L)
                }
                assertTrue(pendingBefore == sender.pending.get(peer.conversation)!!.stateBlob,
                    "forgery must not advance pending")
                assertNull(sender.active.getRatchetState(peer.conversation))
                assertFalse(SqlDelightProcessedEnvelopeRepository(sender.db).exists("forged-reply"))
                assertTrue("forged-reply" !in sender.acked)
                sender.incoming.emit(RelayMessage.Deliver(from = peer.recipient, sealedSender = "",
                    payload = payload(reply), messageId = "reply"))
                withTimeout(5_000L) { while ("reply" !in sender.acked) delay(10L) }
                assertEquals("reply", sender.messages.getMessageById("reply")!!.plaintextCache)
                assertNull(sender.pending.get(peer.conversation))
                assertNotNull(sender.active.getRatchetState(peer.conversation))
                assertTrue("forged-reply" !in sender.acked)
            }
        }
    }

    @Test
    fun pending_survives_reopen_and_expiry_never_restores_initial_active() = runBlocking<Unit> {
        LibsodiumInitializer.initialize()
        val peer = Peer()
        withDatabaseFile("outbound-continuity") { file ->
            val crypto = LibsodiumDoubleRatchet()
            val first = withSender(file, peer, true) { sender ->
                sender.send(peer, "first").also {
                    assertNull(sender.active.getRatchetState(peer.conversation))
                    assertNotNull(sender.pending.get(peer.conversation))
                }
            }
            var receiver = crypto.decrypt(peer.receiveState(first), first.encryptedMessage).first
            peer.clock += DefaultMessagingService.PENDING_TTL_MS - 1L
            withSender(file, peer, false) { sender ->
                val second = sender.send(peer, "second")
                assertEquals(first.x3dhInit, second.x3dhInit)
                val decrypted = crypto.decrypt(receiver, second.encryptedMessage)
                receiver = decrypted.first
                assertEquals("second", json.decodeFromString<MessagePayload>(decrypted.second.decodeToString()).text)
                assertEquals(1_000L, sender.pending.get(peer.conversation)!!.reservedAtMs)
            }
            peer.clock++
            withSender(file, peer, false) { sender ->
                org.robolectric.shadows.ShadowLog.clear()
                val third = sender.send(peer, "after-expiry")
                assertNotNull(third.x3dhInit)
                assertNotEquals(first.x3dhInit, third.x3dhInit)
                val decoded = crypto.decrypt(peer.receiveState(third), third.encryptedMessage).second
                assertEquals("after-expiry", json.decodeFromString<MessagePayload>(decoded.decodeToString()).text)
                assertNull(sender.active.getRatchetState(peer.conversation))
                assertEquals(2, peer.fetches)
                val decision = org.robolectric.shadows.ShadowLog.getLogsForTag("PhantomMessaging")
                    .map { it.msg }.single { "SEND_TRACE pending_reuse_decision" in it }
                assertTrue("withinTtl=false" in decision)
                assertTrue("pendingPresent=true artifactsValid=true" in decision)
                assertTrue("recipientMatches=true suspect=false transactionAvailable=true reusable=false" in decision)
            }
        }
    }

    @Test
    fun a_delayed_headerless_reply_survives_replacement_of_outbound_pending() = runBlocking<Unit> {
        LibsodiumInitializer.initialize()
        val peer = Peer().also { it.clock = System.currentTimeMillis() }
        withDatabaseFile("displaced-outbound-") { file ->
            withSender(file, peer, true) { sender ->
                val crypto = LibsodiumDoubleRatchet()
                val first = sender.send(peer, "first")
                val oldBob = crypto.decrypt(peer.receiveState(first), first.encryptedMessage).first
                peer.clock += DefaultMessagingService.PENDING_TTL_MS
                val second = sender.send(peer, "second-chain")
                assertNotEquals(first.x3dhInit, second.x3dhInit)
                val newBob = crypto.decrypt(peer.receiveState(second), second.encryptedMessage).first
                val pendingBeforeReplies = requireNotNull(sender.pending.get(peer.conversation)).stateBlob
                sender.service.startReceiving()
                withTimeout(5_000L) { sender.incoming.subscriptionCount.first { it > 0 } }
                @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                suspend fun reply(state: RatchetState, id: String) {
                    val plaintext = json.encodeToString(MessagePayload(text = id, sentAt = 1L)).encodeToByteArray()
                    val frame = WireFrame(encryptedMessage = crypto.encrypt(state, plaintext).second)
                    val payload = kotlin.io.encoding.Base64.encode(MessagePadding.pad(json.encodeToString(frame).encodeToByteArray()))
                    sender.incoming.emit(RelayMessage.Deliver(from = peer.recipient,
                        sealedSender = "", payload = payload, messageId = id))
                    withTimeout(5_000L) { while (id !in sender.acked) delay(10L) }
                    assertEquals(id, sender.messages.getMessageById(id)?.plaintextCache)
                }
                reply(oldBob, "late-old-reply")
                assertEquals(pendingBeforeReplies, sender.pending.get(peer.conversation)?.stateBlob,
                    "the old reply must not consume the newer pending slot")
                reply(newBob, "new-reply")
                assertNull(sender.pending.get(peer.conversation))
                assertEquals(2, peer.fetches, "incoming continuations do not fetch another bundle")
            }
        }
    }
}
