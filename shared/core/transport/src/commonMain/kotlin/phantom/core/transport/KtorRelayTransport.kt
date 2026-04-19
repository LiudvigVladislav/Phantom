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
        openWithRetry(attempt = 0)
    }

    private suspend fun openWithRetry(attempt: Int) {
        _state.value = TransportState.Connecting
        val urlWithId = if (identityHex.isNotEmpty()) "$relayUrl?id=$identityHex" else relayUrl
        val urlWithToken = if (relayToken != null) "$urlWithId&token=$relayToken" else urlWithId
        try {
            httpClient.webSocket(urlWithToken) {
                session = this
                _state.value = TransportState.Connected
                val transportScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                scope = transportScope
                startPing(transportScope)
                readLoop()
                // readLoop() returns when the server closes the connection cleanly.
                // Cancel ping so it doesn't try to write to a dead session.
                transportScope.cancel()
            }
            // Clean close — reconnect immediately from attempt 0.
            _state.value = TransportState.Disconnected
            delay(RelayTransportConfig.RECONNECT_BASE_DELAY_MS)
            openWithRetry(0)
        } catch (e: Exception) {
            _state.value = TransportState.Error(e)
            if (attempt < RelayTransportConfig.RECONNECT_MAX_ATTEMPTS) {
                val delayMs = min(
                    RelayTransportConfig.RECONNECT_BASE_DELAY_MS * (1 shl attempt),
                    RelayTransportConfig.RECONNECT_MAX_DELAY_MS,
                )
                delay(delayMs)
                openWithRetry(attempt + 1)
            } else {
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
                    when (val msg = json.decodeFromString<RelayMessage>(text)) {
                        is RelayMessage.Deliver -> _incoming.emit(msg)
                        is RelayMessage.Ack -> _acks.emit(msg)
                        is RelayMessage.ReadReceipt -> _readReceipts.emit(msg)
                        is RelayMessage.Pong -> Unit
                        else -> Unit
                    }
                } catch (_: Exception) { /* malformed frame — skip */ }
            }
        }
        _state.value = TransportState.Disconnected
    }

    override suspend fun send(message: RelayMessage.Send): Boolean {
        if (!isConnected()) return false
        return sendRaw(message)
    }

    override suspend fun sendReadReceipt(message: RelayMessage.ReadReceipt): Boolean {
        if (!isConnected()) return false
        return sendRaw(message)
    }

    private suspend fun sendRaw(message: RelayMessage): Boolean {
        return try {
            session?.send(Frame.Text(json.encodeToString(message)))
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun disconnect() {
        pingJob?.cancel()
        scope?.cancel()
        session?.close()
        session = null
        _state.value = TransportState.Disconnected
    }

    override fun isConnected(): Boolean = _state.value is TransportState.Connected
}
