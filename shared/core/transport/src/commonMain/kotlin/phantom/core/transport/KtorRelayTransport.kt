// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
    // F11 + F26: per-user signed-challenge auth replaces the shared relay token.
    private var signingPubKeyHex: String = ""
    private var challengeSigner: (suspend (ByteArray) -> ByteArray?)? = null
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
        signingPublicKeyHex: String,
        signChallenge: suspend (challenge: ByteArray) -> ByteArray?,
        socksProxyPort: Int?,
    ) {
        this.relayUrl = relayUrl
        this.identityHex = identityPublicKeyHex
        this.signingPubKeyHex = signingPublicKeyHex
        this.challengeSigner = signChallenge
        this.socksProxyPort = socksProxyPort
        disconnectRequested = false
        // Reset the pong staleness mark so the AlarmManager keepalive does
        // not see a stale value inherited from the previous WS session
        // (e.g. after a Privacy-mode switch the new connect runs an outer
        // chain walk + auth-handshake that can take 30-90 s; without this
        // reset `lastPongElapsedMs` would already report 100s+ at the very
        // first alarm and trigger forceReconnect that tears down the
        // in-flight handshake — observed cross-device test 2026-05-10).
        lastPongMark = timeSource.markNow()
        relayLog(
            RelayLogLevel.INFO,
            "connect() called: url=$relayUrl identity=${identityPublicKeyHex.take(16)}… signing=${signingPublicKeyHex.take(16)}… socks=${socksProxyPort ?: "direct"}",
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
            // F11 + F26: every reconnect generation does a fresh signed-challenge
            // handshake. Failing the handshake aborts THIS attempt only — the
            // outer loop backs off and retries.
            val authedWsUrl = try {
                buildAuthedWsUrl(generationClient)
            } catch (t: Throwable) {
                relayLog(
                    RelayLogLevel.ERROR,
                    "Auth handshake failed (attempt=$attempt): ${t::class.simpleName} ${t.message}",
                )
                runCatching { generationClient.close() }
                if (disconnectRequested) break
                val delayMs = min(
                    RelayTransportConfig.RECONNECT_BASE_DELAY_MS * (1L shl min(attempt, 16)),
                    RelayTransportConfig.RECONNECT_MAX_DELAY_MS,
                )
                relayLog(RelayLogLevel.INFO, "Retry attempt #${attempt + 1} in ${delayMs}ms")
                delay(delayMs)
                attempt++
                continue
            }
            if (authedWsUrl == null) {
                relayLog(
                    RelayLogLevel.WARN,
                    "Auth handshake aborted (attempt=$attempt) — signing key not ready or relay returned no challenge",
                )
                runCatching { generationClient.close() }
                if (disconnectRequested) break
                val delayMs = min(
                    RelayTransportConfig.RECONNECT_BASE_DELAY_MS * (1L shl min(attempt, 16)),
                    RelayTransportConfig.RECONNECT_MAX_DELAY_MS,
                )
                delay(delayMs)
                attempt++
                continue
            }
            val redactedUrl = authedWsUrl
                .replace(Regex("""id=[^&]+"""),             "id=<redacted>")
                .replace(Regex("""signing_pubkey=[^&]+"""), "signing_pubkey=<redacted>")
                .replace(Regex("""challenge=[^&]+"""),      "challenge=<redacted>")
                .replace(Regex("""signature=[^&]+"""),      "signature=<redacted>")
            relayLog(
                RelayLogLevel.INFO,
                "Attempting WebSocket connect (attempt=$attempt): $redactedUrl",
            )
            try {
                generationClient.webSocket(authedWsUrl) {
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

    /**
     * F11 + F26 signed-challenge handshake. Fetches a fresh nonce from the
     * relay's `/auth/challenge` endpoint, asks the caller-supplied
     * [challengeSigner] to sign it with the local Ed25519 signing private
     * key, and returns the WS URL with `?id=&signing_pubkey=&challenge=
     * &signature=` ready for [HttpClient.webSocket].
     *
     * Returns `null` when the signer declines (typically because the signing
     * key has not been provisioned yet — onboarding race; the caller backs
     * off and retries). Throws on HTTP / parse errors so the caller's
     * outer catch path logs and counts the attempt.
     */
    private suspend fun buildAuthedWsUrl(client: HttpClient): String? {
        val signer = challengeSigner ?: return null
        if (identityHex.isEmpty() || signingPubKeyHex.isEmpty()) return null

        val httpScheme = when {
            relayUrl.startsWith("wss://") -> "https://"
            relayUrl.startsWith("ws://")  -> "http://"
            else                          -> "https://"
        }
        val hostAndPath = relayUrl.removePrefix("wss://").removePrefix("ws://")
        val hostOnly = hostAndPath.substringBefore("/")
        val challengeUrl = "$httpScheme$hostOnly/auth/challenge?identity=$identityHex"

        val body = client.get(challengeUrl).bodyAsText()
        val nonceHex = json.parseToJsonElement(body)
            .let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("nonce_hex")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.content
            ?: error("auth/challenge response missing nonce_hex: $body")

        val nonceBytes = hexToBytes(nonceHex)
        val signature = signer(nonceBytes) ?: return null
        if (signature.size != 64) error("signer returned ${signature.size}-byte signature, expected 64")

        return relayUrl +
            "?id=$identityHex" +
            "&signing_pubkey=$signingPubKeyHex" +
            "&challenge=$nonceHex" +
            "&signature=${bytesToHex(signature)}"
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { ((it.toInt() and 0xFF) or 0x100).toString(16).substring(1) }

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
                // Per-envelope trace so a multi-chunk upload (voice messages,
                // PR-F1 2026-05-12) shows in logs which slices got requeued.
                expired.forEach {
                    relayLog(
                        RelayLogLevel.WARN,
                        "ACK watchdog requeue: id=${it.message.messageId.take(12)}…, pendingOutboxHead=true",
                    )
                }
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
     * Stops at the first sendRaw failure and re-enqueues all unsent items at
     * the front so the next reconnect cycle picks them up in order.
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
        var sentCount = 0
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
                val remaining = toFlush.size - sentCount
                relayLog(
                    RelayLogLevel.ERROR,
                    "Flush failed — re-enqueuing $remaining item(s) for next reconnect",
                )
                // Re-enqueue from the failed item onwards, preserving order.
                outboxMutex.withLock {
                    toFlush.subList(sentCount, toFlush.size)
                        .asReversed()
                        .forEach { pendingOutbox.addFirst(it) }
                }
                return
            }
            sentCount++
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
        // Best-effort flush of any pending outbox items before tearing down the
        // scope. Bounded to 3 s so disconnect never blocks indefinitely.
        withTimeoutOrNull(3_000L) {
            if (session != null) flushPendingOutbox()
        }
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

    // Lock-free read against pendingAcks. The map is mutated only under
    // pendingAcksLock, but .size is a single primitive int field on the
    // backing HashMap and racing the read against put/remove cannot return
    // a torn value — at worst the count is off by one mid-mutation, which
    // does not change the wakeup gate decision (the gate trips on > 0).
    override val pendingAckCount: Int
        get() = pendingAcks.size

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
        // the same relayUrl/identityHex/signingPubKeyHex/challengeSigner
        // stored on the instance, fetches a fresh challenge per generation,
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
