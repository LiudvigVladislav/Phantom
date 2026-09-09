package phantom.android.messaging

import android.app.Application
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
import org.robolectric.shadows.ShadowLog
import phantom.core.crypto.*
import phantom.core.identity.*
import phantom.core.messaging.DefaultMessagingService
import phantom.core.messaging.OutgoingMessage
import phantom.core.messaging.SessionManager
import phantom.core.messaging.WireFrame
import phantom.core.storage.*
import phantom.core.storage.db.PhantomDatabase
import phantom.core.transport.*
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.*

/**
 * Two real messaging services, SQLite repositories, X3DH and ratchets. Only
 * relay I/O and the prekey lock policy are controlled. This does not execute
 * Android hardware Keystore or establish the field exception's exact class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalUnsignedTypes::class, ExperimentalEncodingApi::class)
class PendingExpiryReceiveTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private class PrekeyAccess : KeystoreBlobCipher {
        @Volatile var locked = false
        @Volatile var allowReadsWhileLocked = 0
        @Volatile var cancellation: CancellationException? = null
        private val lockedReads = AtomicInteger()
        val reads = AtomicInteger()
        override fun wrap(plaintext: ByteArray): ByteArray = plaintext.copyOf()
        override fun unwrap(wrappedBlob: ByteArray): ByteArray {
            reads.incrementAndGet()
            cancellation?.let { throw it }
            if (locked && lockedReads.getAndIncrement() >= allowReadsWhileLocked)
                throw InvalidKeyException("fixture prekey locked", KeyStoreException("fixture policy"))
            return wrappedBlob.copyOf()
        }
    }

    private inner class Peer(val name: String, val driver: JdbcSqliteDriver, val scope: CoroutineScope) {
        val db: PhantomDatabase
        init { PhantomDatabase.Schema.create(driver); db = PhantomDatabase(driver) }
        val x3dh = LibsodiumX3DH()
        val identity = x3dh.generateDhKeyPair()
        val signing = Signature.keypair()
        val spk = x3dh.generateDhKeyPair()
        val access = PrekeyAccess()
        val spks = SqlDelightLocalSignedPreKeyRepository(db, access)
        val opks = SqlDelightLocalOneTimePreKeyRepository(db, access)
        val active = SqlDelightRatchetStateRepository(db)
        val pending = SqlDelightPendingRatchetStateRepository(db)
        val ledger = SqlDelightProcessedEnvelopeRepository(db)
        val reservations = SqlDelightOpkReservationRepository(db)
        val sessions = SessionManager(x3dh, active, spks, opks, LibsodiumIdentityCrypto(), json,
            opkReservationRepository = reservations)
        val held = SqlDelightDecryptFailedEnvelopeRepository(db)
        val messages = SqlDelightMessageRepository(db)
        val incoming = MutableSharedFlow<RelayMessage.Deliver>()
        val acked = ConcurrentLinkedQueue<String>()
        val parked = ConcurrentLinkedQueue<String>()
        val scans = AtomicInteger()
        val bundles = ArrayDeque<phantom.core.transport.PreKeyBundle>()
        val fetches = AtomicInteger()
        var now = System.currentTimeMillis()
        lateinit var other: Peer
        lateinit var service: DefaultMessagingService
        val publicHex get() = identity.publicKey.bytes.hex()
        val conversation get() = listOf(publicHex, other.publicHex).sorted().joinToString("_")

        suspend fun start() {
            val signature = SignedPreKeySigner.sign(spk.publicKey, 1_000L, signing.secretKey.toByteArray()).hex()
            spks.upsert(LocalSignedPreKeyEntity(7L, spk.publicKey.bytes.hex(),
                spk.privateKey.bytes.hex(), 1_000L, signature, null))
            repeat(4) { ordinal ->
                val opk = x3dh.generateDhKeyPair()
                val id = (ordinal + 1).toString(16).padStart(32, '0')
                opks.insert(LocalOneTimePreKeyEntity(id, opk.publicKey.bytes.hex(), opk.privateKey.bytes.hex(), 1_000L))
                bundles.addLast(phantom.core.transport.PreKeyBundle(publicHex,
                    signing.publicKey.toByteArray().hex(),
                    WireSignedPreKey(7L, spk.publicKey.bytes.hex(), 1_000L, signature),
                    WireOneTimePreKey(id, opk.publicKey.bytes.hex())))
            }
            val transport = object : RelayTransport by KtorRelayTransport(httpClientFactory = { error("no network") }) {
                override val incoming = this@Peer.incoming
                override suspend fun send(message: RelayMessage.Send) = true
                override suspend fun sendDeliveryAck(messageId: String): Boolean {
                    check(ledger.exists(messageId))
                    acked.add(messageId)
                    return true
                }
                override suspend fun parkInbound(messageId: String) { parked.add(messageId) }
            }
            val api = object : PreKeyApi {
                override suspend fun fetchBundle(identityPubkeyHex: String, requesterPubkeyHex: String?): phantom.core.transport.PreKeyBundle {
                    assertEquals(other.publicHex, identityPubkeyHex)
                    fetches.incrementAndGet()
                    return other.bundles.removeFirst()
                }
                override suspend fun publishBundle(forceJoinInFlight: Boolean, requestProvider: suspend () -> PublishRequest): PublishResult = error("unexpected publish")
                override suspend fun fetchStatus(identityPubkeyHex: String, requesterPubkeyHex: String?): PreKeyStatus = error("unexpected status")
            }
            val observedHeld = object : DecryptFailedEnvelopeRepository by held {
                override suspend fun countByConversation(): Map<String, Long> {
                    scans.incrementAndGet()
                    return held.countByConversation()
                }
            }
            service = DefaultMessagingService(
                identity = IdentityRecord(name, name, publicHex, identity.privateKey.bytes.hex(), 0L),
                localKeyPair = identity, ratchet = LibsodiumDoubleRatchet(),
                sessionManager = sessions,
                transport = transport, messageRepository = messages,
                conversationRepository = SqlDelightConversationRepository(db), scope = scope, json = json,
                preKeyApi = api,
                signingKeyProvider = { IdentitySigningKeyPair(SigningPublicKey(signing.publicKey.toByteArray()),
                    SigningPrivateKey(signing.secretKey.toByteArray())) },
                pendingRatchetStateRepository = pending,
                sessionTransactionRepository = SqlDelightSessionTransactionRepository(db),
                processedEnvelopeRepository = ledger, decryptFailedEnvelopeRepository = observedHeld,
                opkReservationRepository = reservations,
                holdMacFailures = true, nowMsProvider = { now },
            )
            service.startReceiving()
            withTimeout(5_000L) { incoming.subscriptionCount.first { it > 0 } }
        }

        suspend fun send(id: String): WireFrame {
            service.sendMessage(OutgoingMessage(id, conversation, other.publicHex, id)).getOrThrow()
            return json.decodeFromString(assertNotNull(messages.getMessageById(id)).ciphertext.decodeToString())
        }

        suspend fun deliver(frame: WireFrame, id: String) {
            val priorParks = parked.count { it == id }
            incoming.emit(RelayMessage.Deliver(from = other.publicHex, sealedSender = "",
                payload = Base64.encode(MessagePadding.pad(json.encodeToString(frame).encodeToByteArray())),
                messageId = id))
            withTimeout(5_000L) { while (id !in acked && parked.count { it == id } == priorParks) delay(10L) }
        }

        suspend fun assertDelivered(id: String) {
            assertEquals(id, messages.getMessageById(id)?.plaintextCache)
            assertTrue(ledger.exists(id))
            assertTrue(id in acked)
        }
    }

    private suspend fun peers(block: suspend (Peer, Peer) -> Unit) {
        LibsodiumInitializer.initialize()
        ShadowLog.clear()
        val resources = mutableListOf<Pair<JdbcSqliteDriver, CoroutineScope>>()
        fun createPeer(name: String): Peer {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            resources.add(driver to scope)
            return Peer(name, driver, scope)
        }
        var bodyFailure: Throwable? = null
        try {
            val a = createPeer("alice")
            val b = createPeer("bob")
            a.other = b; b.other = a
            a.start(); b.start()
            block(a, b)
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            val failures = mutableListOf<Throwable>()
            withContext(NonCancellable) {
                resources.forEach { it.second.cancel() }
                for ((driver, scope) in resources) {
                    try {
                        withTimeout(5_000L) { scope.coroutineContext[Job]!!.join() }
                        driver.close()
                    } catch (failure: Throwable) { failures.add(failure) }
                }
            }
            if (failures.isNotEmpty()) {
                val failure = bodyFailure ?: AssertionError("fixture cleanup failed")
                failures.forEach(failure::addSuppressed)
                if (bodyFailure == null) throw failure
            }
        }
    }

    private suspend fun establish(a: Peer, b: Peer): WireFrame {
        val first = a.send("first")
        b.deliver(first, "first"); b.assertDelivered("first")
        assertNull(b.opks.get(requireNotNull(first.x3dhInit?.opkKeyIdHex)))
        val reply = b.send("reply")
        a.deliver(reply, "reply"); a.assertDelivered("reply")
        val next = a.send("next")
        b.deliver(next, "next"); b.assertDelivered("next")
        return next
    }

    @Test fun expiry_with_available_prekeys_delivers_and_consumes_a_new_key_once() = runBlocking {
        peers { a, b ->
            val before = establish(a, b)
            a.now += DefaultMessagingService.PENDING_TTL_MS
            val after = a.send("after-expiry")
            assertNotEquals(before.x3dhInit, after.x3dhInit)
            val opk = requireNotNull(after.x3dhInit?.opkKeyIdHex)
            assertNotNull(b.opks.get(opk))
            b.deliver(after, "after-expiry"); b.assertDelivered("after-expiry")
            assertNull(b.opks.get(opk))
            val continuation = a.send("continuation")
            assertEquals(after.x3dhInit, continuation.x3dhInit)
            b.deliver(continuation, "continuation"); b.assertDelivered("continuation")
        }
    }

    @Test fun an_existing_chain_delivers_while_prekeys_are_locked() = runBlocking {
        peers { a, b ->
            val before = establish(a, b)
            val reads = b.access.reads.get()
            b.access.locked = true
            val next = a.send("while-locked")
            assertEquals(before.x3dhInit, next.x3dhInit)
            b.deliver(next, "while-locked"); b.assertDelivered("while-locked")
            assertEquals(reads, b.access.reads.get(), "continuation must not unwrap a prekey")
        }
    }

    @Test fun a_locked_bootstrap_recovers_without_new_inbound_when_prekey_access_returns() = runBlocking {
        peers { a, b ->
            establish(a, b)
            a.now += DefaultMessagingService.PENDING_TTL_MS
            val frame = a.send("locked-bootstrap")
            val opk = requireNotNull(frame.x3dhInit?.opkKeyIdHex)
            val activeBefore = b.active.getRatchetState(b.conversation)
            val reads = b.access.reads.get()
            b.access.locked = true
            b.deliver(frame, "locked-bootstrap")
            assertTrue(b.access.reads.get() > reads)
            assertNull(b.messages.getMessageById("locked-bootstrap"))
            assertFalse(b.ledger.exists("locked-bootstrap"))
            assertFalse("locked-bootstrap" in b.acked)
            val held = b.held.listByConversation(b.conversation).single { it.envelopeId == "locked-bootstrap" }
            assertEquals("prekey", held.errorType)
            assertNotNull(held.lastReceiveVersion)
            assertEquals(activeBefore, b.active.getRatchetState(b.conversation))
            assertTrue(ShadowLog.getLogsForTag("PhantomMessaging").any {
                "repair_candidate_failed" in it.msg && "stage=bootstrap errorClass=PreKeyReadFailed causeClass=InvalidKeyException" in it.msg
            })
            b.access.locked = false
            assertNotNull(b.opks.get(opk), "the key survived and is accessible again")
            withTimeout(12_000L) { while ("locked-bootstrap" !in b.acked) delay(20L) }
            b.assertDelivered("locked-bootstrap")
            assertNull(b.opks.get(opk))
            assertTrue(b.held.listByConversation(b.conversation).none { it.envelopeId == "locked-bootstrap" })
            b.deliver(a.send("after-recovery"), "after-recovery"); b.assertDelivered("after-recovery")
        }
    }

    @Test fun a_locked_opk_read_and_first_contact_recover_without_spending_the_key_early() = runBlocking {
        peers { a, b ->
            val frame = a.send("first-locked")
            val key = requireNotNull(frame.x3dhInit?.opkKeyIdHex)
            b.access.allowReadsWhileLocked = 1 // SPK unwrap succeeds; OPK unwrap fails.
            b.access.locked = true
            b.deliver(frame, "first-locked")
            assertEquals("prekey", b.held.listByConversation(b.conversation).single().errorType)
            assertNull(b.active.getRatchetState(b.conversation))
            assertFalse(b.ledger.exists("first-locked"))
            assertFalse("first-locked" in b.acked)
            b.access.locked = false
            assertNotNull(b.opks.get(key))
            withTimeout(12_000L) { while ("first-locked" !in b.acked) delay(20L) }
            b.assertDelivered("first-locked")
            assertNull(b.opks.get(key))
        }
    }

    @Test fun a_legacy_mac_classification_gets_one_compatibility_recheck() = runBlocking {
        peers { a, b ->
            establish(a, b)
            a.now += DefaultMessagingService.PENDING_TTL_MS
            val frame = a.send("legacy-held")
            b.access.locked = true
            b.deliver(frame, "legacy-held")
            val entry = b.held.listByConversation(b.conversation).single { it.envelopeId == "legacy-held" }
            val current = requireNotNull(entry.lastReceiveVersion)
            // Before this fix, receive versions contained only this same digest.
            b.held.recordFailure("legacy-held", "mac", current.removePrefix("prekey-read-v1:"), System.currentTimeMillis())
            b.access.locked = false
            withTimeout(12_000L) { while ("legacy-held" !in b.acked) delay(20L) }
            b.assertDelivered("legacy-held")
        }
    }

    @Test fun restored_prekey_access_does_not_make_a_forgery_authentic() = runBlocking {
        peers { a, b ->
            establish(a, b)
            a.now += DefaultMessagingService.PENDING_TTL_MS
            val real = a.send("genuine")
            val forged = real.copy(encryptedMessage = real.encryptedMessage.copy(
                ciphertext = real.encryptedMessage.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
            b.access.locked = true
            b.deliver(forged, "forged")
            assertEquals("prekey", b.held.listByConversation(b.conversation).single { it.envelopeId == "forged" }.errorType)
            b.access.locked = false
            withTimeout(12_000L) {
                while (b.held.listByConversation(b.conversation).single { it.envelopeId == "forged" }.errorType != "mac") delay(20L)
            }
            assertNull(b.messages.getMessageById("forged"))
            assertFalse(b.ledger.exists("forged"))
            assertFalse("forged" in b.acked)
            val reads = b.access.reads.get()
            val scans = b.scans.get()
            withTimeout(12_000L) { while (b.scans.get() < scans + 2) delay(20L) }
            assertEquals(reads, b.access.reads.get(), "a proved MAC failure must not become a paced prekey retry")
            b.deliver(real, "genuine"); b.assertDelivered("genuine")
        }
    }

    @Test fun cancellation_of_a_prekey_read_remains_cancellation() = runBlocking {
        peers { a, b ->
            val frame = a.send("cancelled")
            val cancellation = CancellationException("fixture cancellation")
            b.access.cancellation = cancellation
            val thrown = assertFailsWith<CancellationException> {
                b.sessions.recipientBootstrapInMemory(b.conversation, "cancelled", b.identity,
                    a.publicHex, requireNotNull(frame.x3dhInit))
            }
            // Coroutine stack-trace recovery may copy the exception at withContext.
            assertEquals(cancellation.message, thrown.message)
            b.access.cancellation = null
            b.deliver(frame, "cancelled"); b.assertDelivered("cancelled")
        }
    }
}
