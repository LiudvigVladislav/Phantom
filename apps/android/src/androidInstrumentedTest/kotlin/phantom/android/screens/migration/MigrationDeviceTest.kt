// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.migration

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import phantom.android.PhantomApplication
import phantom.android.di.AppContainer
import phantom.core.crypto.LibsodiumX3DH
import phantom.core.identity.IdentityRecord
import phantom.core.identity.LibsodiumIdentityCrypto
import phantom.core.messaging.MigrationManager
import phantom.core.messaging.MigrationProgress
import phantom.core.storage.*
import phantom.core.storage.db.PhantomDatabase
import phantom.core.transport.*
import kotlin.test.*

/**
 * Multi-process fixture driven explicitly by the isolated-emulator runbook.
 * Never runs implicitly on a user's device: requires an opt-in argument,
 * an emulator, no network, and an empty app database before seeding.
 * Only PreKeyApi is controlled; SQLCipher, Keystore, crypto and migration are real.
 */
@RunWith(AndroidJUnit4::class)
class MigrationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val witness get() = context.getSharedPreferences("migration-device-witness", Context.MODE_PRIVATE)
    private val signal get() = File(context.noBackupFilesDir, "migration-device-at-publish")

    private suspend fun container(): AppContainer {
        val optIn = InstrumentationRegistry.getArguments().getString("isolatedMigration")
        assumeTrue("Run only through the disposable-emulator migration runbook", optIn != null)
        assertEquals("yes", optIn)
        assertTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        assertNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork,
            "Synthetic identities must never contact production")
        val app = context.applicationContext as PhantomApplication
        withTimeout(30_000) { app.ready.await() }
        return app.container
    }

    private val conversation = ConversationEntity("migration-device-chat", "Synthetic contact",
        "ab".repeat(32), "Saved before update", 1_000L, 0L, notes = "Keep contact notes")
    private val message = MessageEntity("migration-device-message", conversation.id,
        byteArrayOf(1, 2, 3), "Saved before update", true, MessageStatus.READ, 1_000L)
    private val oldSender = SenderKeyEntity("migration-device-group", conversation.theirPublicKeyHex,
        "cd".repeat(32), 7L)

    private fun remembered(key: String) = checkNotNull(witness.getString(key, null))

    private suspend fun preserved(c: AppContainer) {
        val identity = assertNotNull(c.identityRepo.loadIdentity())
        assertEquals(remembered("id"), identity.id)
        assertEquals(remembered("public"), identity.publicKeyHex)
        assertEquals(remembered("private"), identity.dhPrivateKeyHex)
        val chat = assertNotNull(c.conversationRepo.getConversation(conversation.id))
        assertEquals(conversation, chat.copy(needsRehandshake = false))
        assertEquals(listOf(message), c.messageRepo.getMessages(conversation.id))
    }

    private class Stores(context: Context) : AutoCloseable {
        val driver = DatabaseDriverFactory(context).createDriver()
        val db = PhantomDatabase(driver)
        val prekeyCipher = createAndroidPrekeyKeystoreCipher()
        val spk = SqlDelightLocalSignedPreKeyRepository(db, prekeyCipher)
        val opk = SqlDelightLocalOneTimePreKeyRepository(db, prekeyCipher)
        val ratchet = SqlDelightRatchetStateRepository(db, createAndroidRatchetKeystoreCipher())
        override fun close() = driver.close()
    }

    private fun manager(c: AppContainer, s: Stores, publish: suspend (PublishRequest) -> PublishResult) =
        MigrationManager(c.identityManager, LibsodiumIdentityCrypto(), s.spk, s.opk, s.ratchet,
            c.senderKeyRepo, c.conversationRepo, object : PreKeyApi {
                override suspend fun publishBundle(forceJoinInFlight: Boolean,
                    requestProvider: suspend () -> PublishRequest) = publish(requestProvider())
                override suspend fun fetchBundle(identityPubkeyHex: String, requesterPubkeyHex: String?): PreKeyBundle? =
                    error("Unexpected fetch")
                override suspend fun fetchStatus(identityPubkeyHex: String, requesterPubkeyHex: String?): PreKeyStatus =
                    error("Unexpected status")
            }, LibsodiumX3DH(), AndroidMigrationProgressStore(context))

    @Test fun seedLegacy() = runBlocking {
        val c = container()
        assertNull(c.identityRepo.loadIdentity(), "Do not seed over an existing identity")
        assertTrue(c.conversationRepo.getAllConversations().isEmpty())
        val pair = LibsodiumIdentityCrypto().generateKeyPair()
        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        val record = IdentityRecord("migration-device-identity", "Synthetic legacy user",
            pair.publicKey.bytes.hex(), pair.privateKey.bytes.hex(), 1_000L)
        c.identityRepo.saveIdentity(record)
        assertTrue(witness.edit().putString("id", record.id).putString("public", record.publicKeyHex)
            .putString("private", record.dhPrivateKeyHex).commit())
        c.conversationRepo.upsertConversation(conversation)
        c.messageRepo.insertMessage(message)
        c.senderKeyRepo.upsert(oldSender)
        Stores(context).use { it.ratchet.upsertRatchetState(conversation.id, "legacy-session-sentinel") }
        preserved(c)
        assertEquals(MigrationProgress.NOT_STARTED, AndroidMigrationProgressStore(context).read(record.id))
    }

    @Test fun interruptAtPublish() = runBlocking {
        val c = container()
        preserved(c)
        c.initMessagingFromStorage()
        assertEquals(AppContainer.MessagingInit.AwaitingMigration, c.messagingInit)
        assertNull(c.messagingService)
        Stores(context).use { s ->
            manager(c, s) { request ->
                val identity = assertNotNull(c.identityRepo.loadIdentity())
                assertFalse(identity.needsSigningKeyBackfill)
                assertEquals(MigrationProgress.IN_PROGRESS, AndroidMigrationProgressStore(context).read(identity.id))
                assertEquals(40, request.one_time_pre_keys.size)
                assertTrue(witness.edit().putString("signing", identity.signingPublicKeyHex)
                    .putString("spk", s.spk.get()!!.publicKeyHex)
                    .putString("opks", s.opk.getAll().map { it.publicKeyHex }.sorted().joinToString(",")).commit())
                signal.outputStream().use { it.write("ready\n".toByteArray()); it.fd.sync() }
                // Host force-stops this process while publish is suspended, not after failure.
                awaitCancellation()
            }.runMigration().getOrThrow()
        }
    }

    @Test fun verifyPendingAfterDeath() = runBlocking {
        val c = container()
        preserved(c)
        assertEquals(remembered("signing"), c.identityRepo.loadIdentity()!!.signingPublicKeyHex)
        c.initMessagingFromStorage()
        assertEquals(AppContainer.MessagingInit.AwaitingMigration, c.messagingInit)
        assertTrue(c.migrationManager!!.needsMigration())
        assertNull(c.messagingService)
        assertNull(c.groupMessagingService)
        assertNull(c.callManager)
        Stores(context).use { s ->
            assertEquals("legacy-session-sentinel", s.ratchet.getRatchetState(conversation.id))
            assertEquals(oldSender, c.senderKeyRepo.get(oldSender.groupId, oldSender.memberPubkeyHex))
            assertEquals(remembered("spk"), s.spk.get()!!.publicKeyHex)
            assertEquals(remembered("opks"), s.opk.getAll().map { it.publicKeyHex }.sorted().joinToString(","))
            assertEquals(MigrationProgress.IN_PROGRESS, AndroidMigrationProgressStore(context).read(remembered("id")))
        }
    }

    @Test fun finishWithControlledRelayResponse() = runBlocking {
        val c = container()
        preserved(c)
        Stores(context).use { s ->
            var publishes = 0
            val migration = manager(c, s) { request ->
                publishes++
                assertEquals(remembered("spk"), request.signed_pre_key.public_key_hex)
                assertEquals(remembered("opks"), request.one_time_pre_keys.map { it.public_key_hex }.sorted().joinToString(","))
                PublishResult.Stored(40)
            }
            assertTrue(migration.needsMigration())
            migration.runMigration().getOrThrow()
            assertEquals(1, publishes)
            assertFalse(migration.needsMigration())
            assertNull(s.ratchet.getRatchetState(conversation.id))
            assertNull(c.senderKeyRepo.get(oldSender.groupId, oldSender.memberPubkeyHex))
            assertTrue(c.conversationRepo.getConversation(conversation.id)!!.needsRehandshake)
            preserved(c)
            s.ratchet.upsertRatchetState(conversation.id, "new-session-must-survive")
            c.senderKeyRepo.upsert(oldSender.copy(iteration = 99L))
            c.conversationRepo.setNeedsRehandshake(conversation.id, false)
        }
    }

    @Test fun verifyCompletedAfterReplacement() = runBlocking {
        val c = container()
        preserved(c)
        Stores(context).use { s ->
            val migration = manager(c, s) { error("Completed migration must not publish") }
            assertEquals(MigrationProgress.COMPLETE, AndroidMigrationProgressStore(context).read(remembered("id")))
            assertFalse(migration.needsMigration())
            migration.runMigration().getOrThrow()
            assertEquals("new-session-must-survive", s.ratchet.getRatchetState(conversation.id))
            assertEquals(99L, c.senderKeyRepo.get(oldSender.groupId, oldSender.memberPubkeyHex)!!.iteration)
            assertFalse(c.conversationRepo.getConversation(conversation.id)!!.needsRehandshake)
            assertEquals(remembered("signing"), c.identityRepo.loadIdentity()!!.signingPublicKeyHex)
            preserved(c)
        }
        withTimeout(60_000) { c.initMessagingFromStorage() }
        assertEquals(AppContainer.MessagingInit.Ready, c.messagingInit)
        assertNotNull(c.messagingService)
        assertFalse(c.migrationManager!!.needsMigration())
        preserved(c)
    }
}
