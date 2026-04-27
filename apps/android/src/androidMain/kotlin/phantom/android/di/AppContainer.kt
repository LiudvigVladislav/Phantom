package phantom.android.di

import android.content.Context
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import phantom.android.calls.CallManager
import phantom.android.notifications.PhantomNotificationManager
import phantom.android.service.DisappearingMessageScheduler
import phantom.android.security.KeystoreManager
import phantom.core.crypto.LibsodiumDoubleRatchet
import phantom.core.crypto.LibsodiumX3DH
import phantom.core.identity.IdentityManager
import phantom.core.identity.IdentityRecord
import phantom.core.identity.IdentityRepository
import phantom.core.messaging.DefaultGroupMessagingService
import phantom.core.messaging.DefaultMessagingService
import phantom.core.messaging.GroupMessagingService
import phantom.core.messaging.MessagePayload.Companion.TYPE_CALL_ANSWER
import phantom.core.messaging.MessagePayload.Companion.TYPE_CALL_HANGUP
import phantom.core.messaging.MessagePayload.Companion.TYPE_CALL_ICE
import phantom.core.messaging.MessagePayload.Companion.TYPE_CALL_OFFER
import phantom.core.messaging.MessagePayload.Companion.TYPE_CALL_REJECT
import phantom.core.messaging.MessagingService
import phantom.core.messaging.SessionManager
import phantom.core.storage.DatabaseDriverFactory
import phantom.core.storage.PhantomDatabaseHolder
import phantom.core.storage.SqlDelightConversationRepository
import phantom.core.storage.SqlDelightGroupRepository
import phantom.core.storage.SqlDelightIdentityRepository
import phantom.core.storage.SqlDelightMessageRepository
import phantom.core.storage.SqlDelightRatchetStateRepository
import phantom.core.storage.SqlDelightReactionRepository
import phantom.core.storage.SqlDelightSenderKeyRepository
import phantom.core.transport.KtorRelayTransport
import phantom.core.transport.createHttpClient

/** Alpha-0 DI container — manual wiring, no framework. */
class AppContainer(private val context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val json = Json { ignoreUnknownKeys = true }

    // ── Storage ───────────────────────────────────────────────────────────────
    private val driverFactory = DatabaseDriverFactory(context)
    private val dbHolder = PhantomDatabaseHolder(driverFactory)

    /** Identity repo wrapped with Android Keystore encryption for the DH private key. */
    val identityRepo: IdentityRepository = KeystoreIdentityRepository(
        SqlDelightIdentityRepository(dbHolder.database)
    )
    val conversationRepo = SqlDelightConversationRepository(dbHolder.database)
    val messageRepo      = SqlDelightMessageRepository(dbHolder.database)
    private val ratchetRepo = SqlDelightRatchetStateRepository(dbHolder.database)
    val reactionRepo     = SqlDelightReactionRepository(dbHolder.database)
    val groupRepo        = SqlDelightGroupRepository(dbHolder.database)
    val senderKeyRepo    = SqlDelightSenderKeyRepository(dbHolder.database)

    // Starts immediately — deletes expired messages while the app is alive.
    private val disappearingMessageScheduler = DisappearingMessageScheduler(messageRepo, appScope)
        .also { it.start() }

    // ── Crypto ────────────────────────────────────────────────────────────────
    private val x3dh    = LibsodiumX3DH()
    private val ratchet = LibsodiumDoubleRatchet()

    // ── Identity ──────────────────────────────────────────────────────────────
    val identityManager = IdentityManager(
        crypto = phantom.core.identity.LibsodiumIdentityCrypto(),
        repository = identityRepo,
    )

    // ── Transport ─────────────────────────────────────────────────────────────
    val transport = KtorRelayTransport(createHttpClient())

    // ── Messaging (initialised after identity is loaded) ──────────────────────
    // Nullable until onboarding completes and we have a real identity.
    var messagingService: MessagingService? = null
        private set

    // Initialised together with messagingService — requires identity for myPubKeyHex.
    var groupMessagingService: GroupMessagingService? = null
        private set

    // Initialised in initMessaging — requires identity.publicKeyHex and transport.
    var callManager: CallManager? = null
        private set

    // ── FCM token (read-only reference — registration is a TODO) ─────────────
    // Once google-services.json is added and the plugin is active, the token is
    // stored by PhantomFirebaseMessagingService.onNewToken under "fcm_token".
    // Wire relay registration here after initMessaging() completes:
    //
    //   val fcmToken = context
    //       .getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
    //       .getString("fcm_token", null)
    //   if (fcmToken != null) {
    //       TODO: POST fcmToken to relay /register-fcm so offline pushes are addressed
    //             to this device. Requires: authenticated relay session (token + identity).
    //   }

    fun initMessaging(
        identity: phantom.core.identity.IdentityRecord,
        localKeyPair: phantom.core.crypto.DhKeyPair,
    ) {
        val sessionManager = SessionManager(x3dh, ratchetRepo, json)
        val service = DefaultMessagingService(
            identity = identity,
            localKeyPair = localKeyPair,
            ratchet = ratchet,
            sessionManager = sessionManager,
            transport = transport,
            messageRepository = messageRepo,
            conversationRepository = conversationRepo,
            scope = appScope,
            json = json,
            reactionRepository = reactionRepo,
        )
        // Wire local notification callback — Android-only side-effect, not part of the KMP interface.
        service.onNewMessageNotification = { convId, sender, preview, senderPubKeyHex ->
            try {
                PhantomNotificationManager.showMessageNotification(context, convId, sender, preview, senderPubKeyHex)
            } catch (e: Throwable) {
                android.util.Log.e(
                    "PhantomMessaging",
                    "showMessageNotification threw (${e::class.simpleName}): ${e.message}",
                    e,
                )
            }
        }

        // Wire group messaging delegate so DefaultMessagingService can route
        // group-type payloads to DefaultGroupMessagingService after decryption.
        val groupService = DefaultGroupMessagingService(
            myPubKeyHex = identity.publicKeyHex,
            myUsername = identity.username,
            groupRepo = groupRepo,
            senderKeyRepo = senderKeyRepo,
            messageRepo = messageRepo,
            transport = transport,
            json = json,
        )
        service.groupMessagingService = groupService
        groupMessagingService = groupService

        // Initialise CallManager and wire call-signalling routing.
        val cm = CallManager(
            context = context,
            myPubKeyHex = identity.publicKeyHex,
            transport = transport,
        )
        cm.initialize()
        callManager = cm

        service.onCallMessage = { payload, fromPubKeyHex ->
            appScope.launch {
                val fromUsername = conversationRepo.getConversation(fromPubKeyHex)
                    ?.theirUsername ?: fromPubKeyHex.take(8)
                when (payload.type) {
                    TYPE_CALL_OFFER  -> cm.handleOffer(fromPubKeyHex, fromUsername, payload.callId ?: "", payload.sdp ?: "")
                    TYPE_CALL_ANSWER -> cm.handleAnswer(payload.sdp ?: "")
                    TYPE_CALL_ICE    -> cm.handleIce(payload.iceCandidateJson ?: "")
                    TYPE_CALL_HANGUP -> cm.handleRemoteHangup()
                    TYPE_CALL_REJECT -> cm.handleRemoteReject()
                }
            }
        }

        messagingService = service
    }

    /**
     * Called on app restart when identity already exists — restores messaging from stored keys.
     * Idempotent: no-op if [messagingService] is already initialised (e.g. by the foreground
     * service before the Activity's LaunchedEffect fires).
     */
    suspend fun initMessagingFromStorage() {
        if (messagingService != null) return
        val record = identityRepo.loadIdentity() ?: return
        val dhKeyPair = phantom.core.crypto.DhKeyPair(
            phantom.core.crypto.DhPublicKey(record.publicKeyHex.hexToByteArray()),
            phantom.core.crypto.DhPrivateKey(record.dhPrivateKeyHex.hexToByteArray()),
        )
        initMessaging(record, dhKeyPair)
    }
}

/**
 * Wraps SqlDelightIdentityRepository and transparently encrypts the DH private key
 * with Android Keystore (AES-256-GCM) before persisting.
 *
 * On save  → raw hex private key  →  AES-GCM encrypt  →  Base64 → stored in DB
 * On load  → Base64 from DB       →  AES-GCM decrypt  →  raw hex → returned to caller
 *
 * If the stored value looks like plain hex (migration from unencrypted builds),
 * it is used as-is — so existing installs keep working after upgrade.
 */
@OptIn(ExperimentalEncodingApi::class)
private class KeystoreIdentityRepository(
    private val delegate: IdentityRepository,
) : IdentityRepository by delegate {

    override suspend fun saveIdentity(record: IdentityRecord) {
        val encryptedPrivKey = record.dhPrivateKeyHex
            .hexToBytes()
            .let { KeystoreManager.encrypt(it) }
            .let { Base64.encode(it) }
        delegate.saveIdentity(record.copy(dhPrivateKeyHex = encryptedPrivKey))
    }

    override suspend fun loadIdentity(): IdentityRecord? {
        val raw = delegate.loadIdentity() ?: return null
        val decryptedHex = runCatching {
            Base64.decode(raw.dhPrivateKeyHex)
                .let { KeystoreManager.decrypt(it) }
                .toHexString()
        }.getOrElse { cause ->
            // Only fall back if value looks like legacy plain hex (pre-Keystore migration).
            // Base64 strings always contain +/= chars or uppercase; plain hex never does.
            val isPlainHex = raw.dhPrivateKeyHex.all { it in '0'..'9' || it in 'a'..'f' }
            if (isPlainHex) {
                raw.dhPrivateKeyHex  // one-time migration path
            } else {
                throw SecurityException("Keystore decryption failed — identity keys are inaccessible", cause)
            }
        }
        return raw.copy(dhPrivateKeyHex = decryptedHex)
    }

    override suspend fun deleteIdentity() {
        KeystoreManager.deleteKey()
        delegate.deleteIdentity()
    }
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

private fun String.hexToByteArray(): ByteArray = hexToBytes()

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
