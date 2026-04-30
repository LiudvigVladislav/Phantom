// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.di

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // In-memory cache of the current identity. Populated eagerly at startup and
    // by initMessaging*; readers (ProfileScreen, top-bar avatar, etc.) collect
    // this StateFlow instead of calling identityRepo.loadIdentity() per screen,
    // which avoids the Keystore-decrypt round-trip on every navigation.
    private val _identityState = MutableStateFlow<IdentityRecord?>(null)
    val identityState: StateFlow<IdentityRecord?> = _identityState.asStateFlow()

    // Self-avatar shared across screens (top-bar, chat list, settings, profile).
    // Single source of truth; ProfileScreen calls refreshSelfAvatar() after the
    // user picks a new photo, and any screen that observes selfAvatar updates.
    private val avatarFile: File = File(context.filesDir, "profile_avatar.jpg")
    private val _selfAvatar = MutableStateFlow<Bitmap?>(null)
    val selfAvatar: StateFlow<Bitmap?> = _selfAvatar.asStateFlow()

    fun refreshSelfAvatar() {
        appScope.launch(Dispatchers.IO) {
            _selfAvatar.value = if (avatarFile.exists()) {
                BitmapFactory.decodeFile(avatarFile.absolutePath)
            } else null
        }
    }

    init {
        appScope.launch {
            if (_identityState.value == null) {
                _identityState.value = identityRepo.loadIdentity()
            }
        }
        refreshSelfAvatar()
    }

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
        _identityState.value = identity
        // PR C commit 10: SessionManager rewrite — see ADR-009 supplement
        // and Alpha2_Migration.md. Constructor expanded to accept the
        // local SignedPreKey + OneTimePreKey repositories (recipient
        // bootstrap path looks up own keypairs by id) and IdentityCrypto
        // (verifies peer's published SPK signature). The full bundle-
        // fetch + first-message bootstrap wiring lands in commit 11.
        val signedPreKeyRepo = phantom.core.storage.SqlDelightLocalSignedPreKeyRepository(dbHolder.database)
        val oneTimePreKeyRepo = phantom.core.storage.SqlDelightLocalOneTimePreKeyRepository(dbHolder.database)
        val sessionManagerIdentityCrypto = phantom.core.identity.LibsodiumIdentityCrypto()
        val sessionManager = SessionManager(
            x3dh = x3dh,
            ratchetStateRepository = ratchetRepo,
            signedPreKeyRepository = signedPreKeyRepo,
            oneTimePreKeyRepository = oneTimePreKeyRepo,
            identityCrypto = sessionManagerIdentityCrypto,
            json = json,
        )
        // PR C commit 11: DMS gains the prekey REST client (for the
        // first-message bundle-fetch path) plus a signing-key provider
        // that resolves the local Ed25519 keypair on demand. The HTTP
        // base URL is derived from BuildConfig.RELAY_URL (which is
        // wss:// or ws://) — same pattern as the report-endpoint flow
        // in ContactProfileScreen.
        val relayHttpBase = phantom.android.BuildConfig.RELAY_URL
            .replace("wss://", "https://")
            .replace("ws://", "http://")
            .removeSuffix("/ws")
            .removeSuffix("/")
        val preKeyApi = phantom.core.transport.PreKeyApiClient(
            httpClient = phantom.core.transport.createHttpClient(),
            relayBaseUrl = relayHttpBase,
        )
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
            preKeyApi = preKeyApi,
            // Signing-key provider — looks up the Ed25519 keypair from
            // the IdentityManager. Returns null on Alpha 1 records that
            // haven't yet been backfilled by the migration flow
            // (PR C commit 12). DMS surfaces null as a hard send error.
            signingKeyProvider = { identityManager.loadSigningKeyPair() },
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
        val record = _identityState.value
            ?: identityRepo.loadIdentity()?.also { _identityState.value = it }
            ?: return
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
