// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

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
import kotlinx.coroutines.cancelAndJoin
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.min
import kotlin.time.TimeSource

class KtorRelayTransport(
    /**
     * Factory that builds a fresh [HttpClient] each call, optionally
     * routed through a SOCKS5 proxy at `127.0.0.1:<port>`. Per ADR-010
     * "Updated 2026-05-01" the WebSocket transport must own a per-
     * reconnect-generation HttpClient so it can call .close() to destroy
     * the OkHttp engine and force-release the active socket on pong/ack
     * timeout; ADR-016 Stage 2C extends the factory with a SOCKS port
     * parameter so the same transport instance can switch between direct
     * WSS and Tor onion routing across reconnect generations without
     * re-instantiation. Null port = direct.
     */
    private val httpClientFactory: (socksProxyPort: Int?) -> HttpClient,
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
    private var ackWatchdogJob: Job? = null
    private var relayUrl: String = ""
    private var identityHex: String = ""
    private var relayToken: String? = null
    private var disconnectRequested: Boolean = false
    // ADR-016 Stage 2C: Tor SOCKS port for the active connect lifetime.
    // Null = direct WSS. Set by [connect] before runReconnectLoop launches;
    // each generation's HttpClient is built with this value so a privacy-
    // mode change is just a disconnect + reconnect with a new port.
    private var socksProxyPort: Int? = null

    // The HttpClient owned by the currently-active reconnect generation.
    // Promoted from a runReconnectLoop-local var so that disconnect() and
    // the pong/ack watchdogs can close it from the outside. Volatile because
    // it's mutated by runReconnectLoop on Dispatchers.Default and read by
    // startPing's pong-timeout branch on the same dispatcher's other workers.
    @Volatile private var currentGenerationClient: HttpClient? = null

    // In-memory outbox for envelopes and read receipts that were enqueued while
    // the WebSocket was not in the Connected state. Drained in FIFO order when
    // the session next becomes Connected. Not persisted to disk — on process
    // restart, MessageRepository (status = QUEUED) is the source of truth and
    // DefaultMessagingService re-submits via send().
    private val outboxMutex = Mutex()
    private val pendingOutbox: ArrayDeque<RelayMessage> = ArrayDeque()

    // Pong timestamp tracking — drives the heartbeat / dead-peer detection.
    // Updated every time the relay emits a Pong frame. If the gap exceeds
    // RelayTransportConfig.PONG_TIMEOUT_MS, the client closes the session,
    // which wakes openWithRetry() and triggers a reconnect.
    private val timeSource = TimeSource.Monotonic
    @Volatile private var lastPongMark: TimeSource.Monotonic.ValueTimeMark = timeSource.markNow()

    // Sent-but-unacknowledged envelopes. Frame.send() can succeed against a
    // half-dead socket without throwing — the bytes sit in the OkHttp buffer
    // and never reach the wire. The ACK watchdog promotes that silent loss
    // to an explicit retry: every entry older than ACK_TIMEOUT_MS is moved
    // back to the front of pendingOutbox and the session is force-closed,
    // which makes runReconnectLoop open a fresh socket and flushPendingOutbox
    // re-send the envelope on top of the new session.
    private data class AckPending(
        val message: RelayMessage.Send,
        val sentAt: TimeSource.Monotonic.ValueTimeMark,
    )
    private val pendingAcksLock = Mutex()
    private val pendingAcks = mutableMapOf<String, AckPending>()

    // ADR-013: scope owning the runReconnectLoop coroutine. Distinct
    // from the per-generation scope (which is reset every reconnect).
    // forceReconnect() abandons the current reconnectJob and launches
    // a new one on this scope, leaving the old loop's stuck webSocket{}
    // block as a zombie. The zombie holds one OkHttp reader thread
    // until the kernel eventually releases recv(); it does not block
    // the new loop from making progress.
    private val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var reconnectJob: Job? = null

    override suspend fun connect(
        relayUrl: String,
        identityPublicKeyHex: String,
        token: String?,
        socksProxyPort: Int?,
    ) {
        this.relayUrl = relayUrl
        this.identityHex = identityPublicKeyHex
        this.relayToken = token
        this.socksProxyPort = socksProxyPort
        disconnectRequested = false
        relayLog(
            RelayLogLevel.INFO,
            "connect() called: url=$relayUrl identity=${identityPublicKeyHex.take(16)}… tokenSet=${token != null} socks=${socksProxyPort ?: "direct"}",
        )
        // Cancel any prior reconnect job (typically a noop on cold start;
        // matters when connect() is called twice from the service double-
        // start path). Then launch a fresh loop on transportScope.
        reconnectJob?.cancel()
        reconnectJob = transportScope.launch {
            runReconnectLoop()
        }
        // Suspend forever (or until disconnectRequested is observed by
        // the loop and runReconnectLoop returns) so existing callers
        // that `await` connect() — PhantomMessagingService — keep their
        // structured-concurrency expectations.
        reconnectJob?.join()
    }

    /**
     * Reconnect loop. Retries forever with exponential backoff up to
     * RelayTransportConfig.RECONNECT_MAX_DELAY_MS. The loop exits only when
     * the caller explicitly asks for disconnect() — a messenger transport
     * that "gives up" after N attempts is worse than one that keeps trying
     * quietly in the background.
     */
    private suspend fun runReconnectLoop() {
        var attempt = 0
        while (!disconnectRequested) {
            _state.value = TransportState.Connecting
            val urlWithId = if (identityHex.isNotEmpty()) "$relayUrl?id=$identityHex" else relayUrl
            val urlWithToken = if (relayToken != null) "$urlWithId&token=$relayToken" else urlWithId
            val redactedUrl = urlWithToken
                .replace(Regex("""id=[^&]+"""),    "id=<redacted>")
                .replace(Regex("""token=[^&]+"""), "token=<redacted>")
            relayLog(
                RelayLogLevel.INFO,
                "Attempting WebSocket connect (attempt=$attempt): $redactedUrl",
            )
            // Per-generation scope. Hoisted out of the webSocket{} block so the
            // finally below can cancelAndJoin it whether the block returned
            // cleanly OR threw.
            var generationScope: CoroutineScope? = null
            // Per-generation HttpClient. ADR-010 (Updated 2026-05-01): the
            // only way to force-close an active WebSocket on Tecno HiOS is
            // to destroy the OkHttp engine entirely via HttpClient.close().
            // Each iteration of this loop allocates a fresh client so the
            // pong/ack watchdogs can close it when they detect liveness
            // failure, and the finally block closes it on every exit path
            // (clean close, exception, disconnect requested).
            val generationClient: HttpClient = httpClientFactory(socksProxyPort)
            currentGenerationClient = generationClient
            try {
                generationClient.webSocket(urlWithToken) {
                    session = this
                    lastPongMark = timeSource.markNow()
                    _state.value = TransportState.Connected
                    relayLog(RelayLogLevel.INFO, "WebSocket connected successfully")
                    attempt = 0 // reset backoff on successful connect

                    val transportScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                    generationScope = transportScope
                    scope = transportScope
                    startPing(transportScope, generationClient)
                    startAckWatchdog(transportScope, generationClient)

                    // Move every still-unacknowledged envelope back to the
                    // head of the outbox. They were sent on the previous
                    // session and never confirmed — most likely lost in
                    // transit. flushPendingOutbox below will re-send them
                    // before any new outbound traffic.
                    requeueUnackedToOutboxFront()

                    // Drain anything the app queued while the socket was down.
                    flushPendingOutbox()

                    readLoop()
                    relayLog(RelayLogLevel.WARN, "WebSocket closed by remote (clean)")
                }
                _state.value = TransportState.Disconnected
                if (disconnectRequested) break
                delay(RelayTransportConfig.RECONNECT_BASE_DELAY_MS)
                relayLog(RelayLogLevel.INFO, "Reconnecting after clean close")
                // attempt was reset to 0 above; restart backoff from base.
                continue
            } catch (e: Exception) {
                _state.value = TransportState.Error(e)
                relayLog(
                    RelayLogLevel.ERROR,
                    "WebSocket connect FAILED (attempt=$attempt, type=${e::class.simpleName}): ${e.message}",
                    e,
                )
                if (disconnectRequested) break
                val delayMs = min(
                    RelayTransportConfig.RECONNECT_BASE_DELAY_MS * (1L shl min(attempt, 16)),
                    RelayTransportConfig.RECONNECT_MAX_DELAY_MS,
                )
                relayLog(RelayLogLevel.INFO, "Retry attempt #${attempt + 1} in ${delayMs}ms")
                delay(delayMs)
                attempt++
            } finally {
                // Cancel the previous generation's coroutines and close its
                // HttpClient. ADR-010 (Updated 2026-05-01) — closing the
                // HttpClient destroys the OkHttp dispatcher and connection
                // pool, releasing any active socket the reader thread is
                // parked on. This is what unblocks Tecno HiOS post-radio-park.
                relayLog(
                    RelayLogLevel.INFO,
                    "webSocket{} block exited — entering finally block, cancelling scope and closing generation client",
                )
                val joined = withTimeoutOrNull(5_000) {
                    generationScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                    Unit
                }
                if (joined == null) {
                    relayLog(
                        RelayLogLevel.WARN,
                        "Generation scope cancelAndJoin timed out (>5s) — proceeding anyway",
                    )
                }
                runCatching { generationClient.close() }
                    .onFailure {
                        relayLog(
                            RelayLogLevel.WARN,
                            "generationClient.close() threw: ${it::class.simpleName}: ${it.message}",
                        )
                    }
                if (currentGenerationClient === generationClient) {
                    currentGenerationClient = null
                }
                relayLog(
                    RelayLogLevel.INFO,
                    "Generation client closed — looping for next reconnect attempt",
                )
            }
        }
        relayLog(RelayLogLevel.INFO, "Reconnect loop exited (disconnect requested)")
        _state.value = TransportState.Disconnected
    }

    private fun startPing(scope: CoroutineScope, generationClient: HttpClient) {
        pingJob = scope.launch {
            while (isActive) {
                delay(RelayTransportConfig.PING_INTERVAL_MS)
                val sinceLastPong = lastPongMark.elapsedNow().inWholeMilliseconds
                if (sinceLastPong > RelayTransportConfig.PONG_TIMEOUT_MS) {
                    relayLog(
                        RelayLogLevel.WARN,
                        "Pong timeout (${sinceLastPong}ms without Pong) — shutting down active engine and closing client",
                    )
                    // ADR-010 Updated 2026-05-01 (round 2): even
                    // HttpClient.close() is not enough on Tecno HiOS —
                    // Ktor's OkHttp close path does
                    // executor.shutdown() (graceful) which does NOT
                    // interrupt threads parked in kernel recv() on a
                    // dead socket. forceShutdownActiveEngine calls
                    // executor.shutdownNow() which sends
                    // InterruptedException to those threads and
                    // unblocks the reader within milliseconds.
                    forceShutdownActiveEngine()
                    runCatching { generationClient.close() }
                    scope.cancel()
                    break
                }
                sendRaw(RelayMessage.Ping)
            }
        }
    }

    private fun startAckWatchdog(scope: CoroutineScope, generationClient: HttpClient) {
        ackWatchdogJob = scope.launch {
            while (isActive) {
                delay(RelayTransportConfig.ACK_WATCHDOG_INTERVAL_MS)
                val expired = pendingAcksLock.withLock {
                    val toExpire = pendingAcks.values.filter {
                        it.sentAt.elapsedNow().inWholeMilliseconds > RelayTransportConfig.ACK_TIMEOUT_MS
                    }
                    toExpire.forEach { pendingAcks.remove(it.message.messageId) }
                    toExpire
                }
                if (expired.isEmpty()) continue
                relayLog(
                    RelayLogLevel.WARN,
                    "ACK timeout: ${expired.size} envelope(s) unacknowledged after ${RelayTransportConfig.ACK_TIMEOUT_MS}ms — requeuing and closing generation client. " +
                        "First id=${expired.first().message.messageId.take(12)}…",
                )
                outboxMutex.withLock {
                    expired.asReversed().forEach { pendingOutbox.addFirst(it.message) }
                }
                forceShutdownActiveEngine()
                runCatching { generationClient.close() }
                scope.cancel()
                break
            }
        }
    }

    private suspend fun requeueUnackedToOutboxFront() {
        val drained = pendingAcksLock.withLock {
            val list = pendingAcks.values.map { it.message }
            pendingAcks.clear()
            list
        }
        if (drained.isEmpty()) return
        relayLog(
            RelayLogLevel.INFO,
            "Re-queueing ${drained.size} unacknowledged envelope(s) from previous session",
        )
        outboxMutex.withLock {
            drained.asReversed().forEach { pendingOutbox.addFirst(it) }
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
                            // Clear the watchdog entry — relay confirms the
                            // envelope landed. Whether it was live-delivered
                            // or queued is an upper-layer concern; for our
                            // purposes any Ack tells us the wire roundtrip
                            // worked.
                            pendingAcksLock.withLock { pendingAcks.remove(msg.messageId) }
                            relayLog(
                                RelayLogLevel.INFO,
                                "Ack from relay: id=${msg.messageId.take(12)}… status=${msg.status}",
                            )
                            _acks.emit(msg)
                        }
                        is RelayMessage.Pong -> {
                            lastPongMark = timeSource.markNow()
                        }
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
            outboxMutex.withLock { pendingOutbox.addLast(message) }
            relayLog(
                RelayLogLevel.INFO,
                "Queued until reconnect: id=${message.messageId.take(12)}… to=${message.to.take(16)}… " +
                    "state=${_state.value::class.simpleName} outboxSize=${pendingOutbox.size}",
            )
            // Returns false so MessageRepository keeps status = QUEUED and the
            // UI can render a pending indicator. The relay's Ack will promote
            // status to RELAYED once the envelope actually reaches the server.
            return false
        }
        relayLog(
            RelayLogLevel.INFO,
            "Sending envelope: to=${message.to.take(16)}… id=${message.messageId.take(12)}… payloadBytes=${message.payload.length} sealed=${message.sealedSender.isNotEmpty()}",
        )
        // Track BEFORE the wire write so the ACK watchdog covers the case
        // where sendRaw silently writes into a half-dead socket (no exception
        // on the local buffer, no frame on the wire). The relay will
        // eventually emit its own Ack frame when the envelope reaches it; if
        // that Ack does not arrive within ACK_TIMEOUT_MS the watchdog
        // requeues this entry on a fresh socket.
        pendingAcksLock.withLock {
            pendingAcks[message.messageId] = AckPending(message, timeSource.markNow())
        }
        val ok = sendRaw(message)
        if (!ok) {
            relayLog(RelayLogLevel.ERROR, "Envelope send returned false (frame write failed)")
            // sendRaw threw and was logged. The frame did not go out, so the
            // pendingAcks entry is meaningless — drop it and re-enqueue at
            // the front of the outbox.
            pendingAcksLock.withLock { pendingAcks.remove(message.messageId) }
            outboxMutex.withLock { pendingOutbox.addLast(message) }
        }
        return ok
    }

    override suspend fun sendDeliveryAck(messageId: String): Boolean {
        val msg = RelayMessage.AckDelivery(messageId = messageId)
        if (!isConnected()) {
            // Queue the ack: it MUST eventually reach the relay or the envelope
            // will be redelivered forever. The outbox is FIFO, so by the time
            // the next connect drains it the relay will see this ack right
            // after the envelope it concerns.
            outboxMutex.withLock { pendingOutbox.addLast(msg) }
            relayLog(
                RelayLogLevel.INFO,
                "Queued delivery ack until reconnect: messageId=${messageId.take(12)}…",
            )
            return false
        }
        relayLog(
            RelayLogLevel.INFO,
            "Sending delivery ack: messageId=${messageId.take(12)}…",
        )
        return sendRaw(msg)
    }

    /**
     * Sends an ephemeral typing notification over the existing WebSocket session.
     * The relay is responsible for live forwarding; if the recipient is offline the
     * relay drops the frame silently — no storage, no queue. Typing events are
     * deliberately NOT queued: a stale "typing…" indicator that arrives after the
     * message itself is noise.
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

    /**
     * Drains the in-memory outbox in FIFO order after a successful reconnect.
     * Each entry goes through sendRaw directly; failures leave the remaining
     * items in the queue only if we add explicit re-enqueue logic — for now a
     * single write failure per entry is accepted (an immediate reconnect will
     * cover the case where the session dies mid-flush).
     */
    private suspend fun flushPendingOutbox() {
        val toFlush = outboxMutex.withLock {
            if (pendingOutbox.isEmpty()) return
            val snapshot = pendingOutbox.toList()
            pendingOutbox.clear()
            snapshot
        }
        relayLog(
            RelayLogLevel.INFO,
            "Flushing ${toFlush.size} queued item(s) after reconnect",
        )
        for (msg in toFlush) {
            when (msg) {
                is RelayMessage.Send -> relayLog(
                    RelayLogLevel.INFO,
                    "Flush → send envelope: id=${msg.messageId.take(12)}… to=${msg.to.take(16)}…",
                )
                is RelayMessage.AckDelivery -> relayLog(
                    RelayLogLevel.INFO,
                    "Flush → send delivery ack: id=${msg.messageId.take(12)}…",
                )
                else -> Unit
            }
            val ok = sendRaw(msg)
            if (!ok) {
                relayLog(
                    RelayLogLevel.ERROR,
                    "Flush failed for one queued item — message will not be retried in this cycle",
                )
            }
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
        disconnectRequested = true
        pingJob?.cancel()
        ackWatchdogJob?.cancel()
        scope?.cancel()
        session?.close()
        session = null
        currentGenerationClient?.close()
        currentGenerationClient = null
        // ADR-013: cancel the reconnect job and the parent transportScope
        // so any zombie reconnect loops from previous forceReconnect()
        // calls are also signalled to exit. Their webSocket{} blocks may
        // still be parked in kernel recv(), but JVM teardown will release
        // them eventually.
        reconnectJob?.cancel()
        reconnectJob = null
        _state.value = TransportState.Disconnected
    }

    override fun isConnected(): Boolean = _state.value is TransportState.Connected

    // ADR-011: AlarmManager wakeup uses this to decide whether to force a
    // reconnect. lastPongMark starts at the construction-time markNow() so
    // the very first alarm after cold start (~30-60 s in) will see a
    // staleness > 25 s if no pong has arrived yet, triggering a reconnect.
    // That is the desired behaviour — an idle process that never received
    // a pong should be treated as needing a reconnect, not as healthy.
    override val lastPongElapsedMs: Long
        get() = lastPongMark.elapsedNow().inWholeMilliseconds

    override suspend fun forceReconnect() {
        relayLog(
            RelayLogLevel.WARN,
            "forceReconnect() called — abandoning current reconnect loop and launching a fresh one (ADR-013 hard reset)",
        )
        // ADR-013: scope-cancel + close-client does NOT unblock a kernel-
        // parked WebSocket reader on Tecno HiOS. Confirmed empirically:
        // forceReconnect() previously logged every 30s with zero progress
        // for 7+ minutes. The structural fix is to abandon the stuck loop
        // entirely.
        //
        // The old reconnectJob's coroutine is cancelled at the JVM scheduler
        // level — its current webSocket{} suspension may still be parked in
        // kernel recv(), but that is a leaked thread, not a blocking
        // dependency for us. Once we launch a new loop below, all new
        // outbound traffic uses a fresh OkHttp engine and a fresh socket.
        //
        // Belt-and-suspenders: kill the current generation's engine and
        // client first so the abandoned loop has no resources to use even
        // if it does eventually unpark.
        forceShutdownActiveEngine()
        runCatching { currentGenerationClient?.close() }
        scope?.cancel()
        val oldJob = reconnectJob
        // Launch a brand-new reconnect loop on transportScope. It captures
        // the same relayUrl/identityHex/relayToken stored on the instance,
        // and starts its own runReconnectLoop iteration from attempt 0.
        reconnectJob = transportScope.launch {
            runReconnectLoop()
        }
        // Best-effort: ask the old job to cancel after the new one has
        // started (so any read-loop epilogue can drain queued frames),
        // but do not wait for it — it may never complete.
        oldJob?.cancel()
        relayLog(
            RelayLogLevel.INFO,
            "forceReconnect: new reconnect loop launched; previous loop abandoned (may remain as zombie thread)",
        )
    }
}
