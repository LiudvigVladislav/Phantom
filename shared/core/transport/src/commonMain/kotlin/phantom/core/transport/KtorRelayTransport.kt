package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.min

class KtorRelayTransport(
    private val httpClient: HttpClient,
) : RelayTransport {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<RelayMessage.Deliver>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val incoming: Flow<RelayMessage.Deliver> = _incoming.asSharedFlow()

    private val _acks = MutableSharedFlow<RelayMessage.Ack>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val acks: Flow<RelayMessage.Ack> = _acks.asSharedFlow()

    private val _readReceipts = MutableSharedFlow<RelayMessage.ReadReceipt>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val readReceipts: Flow<RelayMessage.ReadReceipt> = _readReceipts.asSharedFlow()

    // Typing events are ephemeral and never stored or encrypted.
    // extraBufferCapacity = 10 ensures rapid keystrokes never block the read loop.
    private val _typingEvents = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 10,
    )
    override val typingEvents: SharedFlow<String> = _typingEvents.asSharedFlow()

    private var session: WebSocketSession? = null
    private var scope: CoroutineScope? = null
    private var pingJob: Job? = null
    private var relayUrl: String = ""
    private var identityHex: String = ""
    private var relayToken: String? = null

    override suspend fun connect(relayUrl: String, identityPublicKeyHex: String, token: String?) {
        this.relayUrl = relayUrl
        this.identityHex = identityPublicKeyHex
        this.relayToken = token
        relayLog(
            RelayLogLevel.INFO,
            "connect() called: url=$relayUrl identity=${identityPublicKeyHex.take(16)}… tokenSet=${token != null}",
        )
        openWithRetry(attempt = 0)
    }

    private suspend fun openWithRetry(attempt: Int) {
        _state.value = TransportState.Connecting
        val urlWithId = if (identityHex.isNotEmpty()) "$relayUrl?id=$identityHex" else relayUrl
        val urlWithToken = if (relayToken != null) "$urlWithId&token=$relayToken" else urlWithId
        // Redact the id + token query params from the logged URL so we do not leak them to logcat
        // but still confirm the endpoint, scheme, and port the client actually tries to reach.
        val redactedUrl = urlWithToken
            .replace(Regex("""id=[^&]+"""),    "id=<redacted>")
            .replace(Regex("""token=[^&]+"""), "token=<redacted>")
        relayLog(
            RelayLogLevel.INFO,
            "Attempting WebSocket connect (attempt=$attempt): $redactedUrl",
        )
        try {
            httpClient.webSocket(urlWithToken) {
                session = this
                _state.value = TransportState.Connected
                relayLog(RelayLogLevel.INFO, "WebSocket connected successfully")
                val transportScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                scope = transportScope
                startPing(transportScope)
                readLoop()
                // readLoop() returns when the server closes the connection cleanly.
                // Cancel ping so it doesn't try to write to a dead session.
                transportScope.cancel()
                relayLog(RelayLogLevel.WARN, "WebSocket closed by remote (clean)")
            }
            // Clean close — reconnect immediately from attempt 0.
            _state.value = TransportState.Disconnected
            delay(RelayTransportConfig.RECONNECT_BASE_DELAY_MS)
            relayLog(RelayLogLevel.INFO, "Reconnecting after clean close")
            openWithRetry(0)
        } catch (e: Exception) {
            _state.value = TransportState.Error(e)
            relayLog(
                RelayLogLevel.ERROR,
                "WebSocket connect FAILED (attempt=$attempt, type=${e::class.simpleName}): ${e.message}",
                e,
            )
            if (attempt < RelayTransportConfig.RECONNECT_MAX_ATTEMPTS) {
                val delayMs = min(
                    RelayTransportConfig.RECONNECT_BASE_DELAY_MS * (1 shl attempt),
                    RelayTransportConfig.RECONNECT_MAX_DELAY_MS,
                )
                relayLog(RelayLogLevel.INFO, "Retry attempt #${attempt + 1} in ${delayMs}ms")
                delay(delayMs)
                openWithRetry(attempt + 1)
            } else {
                relayLog(
                    RelayLogLevel.ERROR,
                    "Max retry attempts (${RelayTransportConfig.RECONNECT_MAX_ATTEMPTS}) reached — giving up",
                )
                _state.value = TransportState.Disconnected
            }
        }
    }

    private fun startPing(scope: CoroutineScope) {
        pingJob = scope.launch {
            while (isActive) {
                delay(RelayTransportConfig.PING_INTERVAL_MS)
                sendRaw(RelayMessage.Ping)
            }
        }
    }

    private suspend fun WebSocketSession.readLoop() {
        for (frame in incoming) {
            if (frame is Frame.Text) {
                val text = frame.readText()
                try {
                    // Typing events are ephemeral and are NOT part of the RelayMessage sealed
                    // class (no E2EE, not stored). Handle them before sealed-class decoding.
                    val rawType = json.parseToJsonElement(text)
                        .let { it as? kotlinx.serialization.json.JsonObject }
                        ?.get("type")
                        ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                        ?.content
                    if (rawType == "typing") {
                        val from = json.parseToJsonElement(text)
                            .let { it as? kotlinx.serialization.json.JsonObject }
                            ?.get("from")
                            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                            ?.content
                        if (!from.isNullOrEmpty()) {
                            _typingEvents.emit(from)
                        }
                        continue
                    }

                    when (val msg = json.decodeFromString<RelayMessage>(text)) {
                        is RelayMessage.Deliver -> {
                            relayLog(
                                RelayLogLevel.INFO,
                                "Received envelope: id=${msg.messageId.take(12)}… sealed=${msg.sealedSender.isNotEmpty()} payloadBytes=${msg.payload.length}",
                            )
                            _incoming.emit(msg)
                        }
                        is RelayMessage.Ack -> {
                            relayLog(
                                RelayLogLevel.INFO,
                                "Ack from relay: id=${msg.messageId.take(12)}… status=${msg.status}",
                            )
                            _acks.emit(msg)
                        }
                        is RelayMessage.ReadReceipt -> {
                            relayLog(
                                RelayLogLevel.INFO,
                                "ReadReceipt: messageId=${msg.messageId.take(12)}…",
                            )
                            _readReceipts.emit(msg)
                        }
                        is RelayMessage.Pong -> Unit
                        else -> Unit
                    }
                } catch (e: Exception) {
                    // Malformed frame — log but do not crash the read loop.
                    relayLog(
                        RelayLogLevel.WARN,
                        "Malformed frame dropped (${e::class.simpleName}): ${e.message}",
                    )
                }
            }
        }
        relayLog(RelayLogLevel.WARN, "readLoop exited — connection lost")
        _state.value = TransportState.Disconnected
    }

    override suspend fun send(message: RelayMessage.Send): Boolean {
        if (!isConnected()) {
            relayLog(
                RelayLogLevel.WARN,
                "send() skipped — not connected. state=${_state.value::class.simpleName} to=${message.to.take(16)}… id=${message.messageId.take(12)}…",
            )
            return false
        }
        relayLog(
            RelayLogLevel.INFO,
            "Sending envelope: to=${message.to.take(16)}… id=${message.messageId.take(12)}… payloadBytes=${message.payload.length} sealed=${message.sealedSender.isNotEmpty()}",
        )
        val ok = sendRaw(message)
        if (!ok) relayLog(RelayLogLevel.ERROR, "Envelope send returned false (frame write failed)")
        return ok
    }

    override suspend fun sendReadReceipt(message: RelayMessage.ReadReceipt): Boolean {
        if (!isConnected()) return false
        return sendRaw(message)
    }

    /**
     * Sends an ephemeral typing notification over the existing WebSocket session.
     * The relay is responsible for live forwarding; if the recipient is offline the
     * relay drops the frame silently — no storage, no queue.
     */
    override suspend fun sendTyping(toPubKeyHex: String): Boolean {
        if (!isConnected()) return false
        return try {
            session?.send(Frame.Text("""{"type":"typing","to":"$toPubKeyHex"}"""))
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun sendRaw(message: RelayMessage): Boolean {
        return try {
            session?.send(Frame.Text(json.encodeToString(message)))
            true
        } catch (e: Exception) {
            relayLog(
                RelayLogLevel.ERROR,
                "sendRaw failed (${e::class.simpleName}): ${e.message}",
                e,
            )
            false
        }
    }

    override suspend fun disconnect() {
        relayLog(RelayLogLevel.INFO, "disconnect() called")
        pingJob?.cancel()
        scope?.cancel()
        session?.close()
        session = null
        _state.value = TransportState.Disconnected
    }

    override fun isConnected(): Boolean = _state.value is TransportState.Connected
}
