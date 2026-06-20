// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.min
import kotlin.time.TimeSource

/**
 * PR-D1b (2026-05-16): per-WS-session post-mortem record emitted on
 * [KtorRelayTransport.wsSessionEnded] exactly once per session close.
 *
 * Observation-only. The REST fallback state machine (in
 * `HybridRelayTransport` at the Android app layer) consumes this to feed
 * [RestStateMachine.Event.WsSessionEnded] and decide whether to leave
 * `WS_ACTIVE` for `REST_ACTIVE`. The WS path itself takes no action
 * based on this event and continues reconnecting as before.
 *
 * Field semantics match the existing `session_summary` log line:
 *   - [durationMs]          — wall-clock lifetime of the session, ms
 *   - [inboundFrames]       — count of Frame.Text observed
 *   - [pendingAcksAtClose]  — outbound envelopes awaiting ACK at the moment
 *                              the session ended (the state machine uses
 *                              this to pick the 2-fail "active" trigger vs
 *                              the 3-fail "idle" trigger)
 *   - [closeOrigin]         — `"local" | "remote" | "error" | "unknown"`
 *                              (lowercased name of internal `CloseOrigin`)
 *   - [closeError]          — exception message if the session ended on a
 *                              throwable, else null
 */
data class WsSessionEndedEvent(
    val durationMs: Long,
    val inboundFrames: Int,
    val pendingAcksAtClose: Int,
    val closeOrigin: String,
    val closeError: String?,
    // PR-WS-HEALTH-STATE1 Commit 3.2a (2026-06-01): propagated so
    // [phantom.android.transport.HybridRelayTransport] can feed
    // [WsDegradationDetector.Event.PingTimeout] without re-parsing the
    // close-error message. Computed in the same finally block that
    // emits this event via [PingTimeoutTextParser.parseSuccessfulPingPongs]
    // — see the `tryEmit` site below. Default `false` for backward
    // compatibility with any commonTest construction.
    val okhttpPingTimeoutDetected: Boolean = false,
)

/**
 * R3.6 Fast REST degradation (2026-06-20) — ordered, guaranteed-delivery
 * lifecycle stream. Replaces the former [WsSessionEndedEvent] SharedFlow
 * (which used `tryEmit` semantics and could silently drop events on a full
 * buffer) with a single [Channel.UNLIMITED] channel that preserves emission
 * order and guarantees delivery as long as the channel is open.
 *
 * Two event subtypes:
 *   - [Connected] — emitted immediately after the WebSocket handshake
 *     succeeds (same timing as the `"WebSocket connected successfully"` log
 *     line). Carries the per-session epoch so the consumer can correlate
 *     the connected session with its eventual [Ended] event.
 *   - [Ended] — replaces the former [WsSessionEndedEvent] emission. Carries
 *     all legacy fields PLUS [Ended.sessionEpoch] for the sticky-recovery
 *     epoch filter in [RestStateMachine].
 *
 * Channel lifetime equals the transport object lifetime. The channel is
 * never explicitly closed; it is garbage-collected with the transport.
 * IMPL-LOCK #1 (scope memo): exposed via [receiveAsFlow], NOT
 * [consumeAsFlow], so collector termination does NOT close the channel.
 */
sealed interface WsSessionLifecycleEvent {
    /**
     * Emitted after the WS handshake succeeds, before any frames are read.
     * [sessionEpoch] matches the value of `wsSessionEpoch` for this session.
     */
    data class Connected(val sessionEpoch: Long) : WsSessionLifecycleEvent

    /**
     * Emitted in the `finally` block of each per-session iteration,
     * after [WsSessionEndedEvent] telemetry fields are assembled.
     * Carries the same semantic fields as the legacy [WsSessionEndedEvent]
     * plus [sessionEpoch] for sticky-recovery epoch correlation.
     */
    data class Ended(
        val durationMs: Long,
        val inboundFrames: Int,
        val pendingAcksAtClose: Int,
        val closeOrigin: String,
        val closeError: String?,
        val okhttpPingTimeoutDetected: Boolean,
        val sessionEpoch: Long,
    ) : WsSessionLifecycleEvent {
        /** Helper for IMPL-LOCK #4 error logging — returns epoch as string. */
        fun epochOrUnknown(): String = sessionEpoch.toString()
    }
}

/** Returns the session epoch of any lifecycle event, or "unknown" for new variants. */
fun WsSessionLifecycleEvent.epochOrUnknown(): String = when (this) {
    is WsSessionLifecycleEvent.Connected -> sessionEpoch.toString()
    is WsSessionLifecycleEvent.Ended -> sessionEpoch.toString()
}

/**
 * PR-D1d (2026-05-17): emitted when a sent envelope's per-envelope ACK
 * deadline elapses without a relay AckDeliver being received.
 *
 * [msgId] is the envelope's messageId (first 12 chars are logged;
 * the full value is carried here so the orchestrator can correlate
 * without truncation). [ageMs] is the elapsed time since the envelope
 * was inserted into pendingAcks (i.e. since it was handed to the WS
 * layer) — always >= [RelayTransportConfig.ACK_DEADLINE_MS].
 *
 * The transport emits this at most once per envelope. It does NOT
 * remove the envelope from pendingAcks — removal is still owned by
 * the regular ACK path (on relay AckDeliver) and session-end cleanup,
 * so the existing ACK watchdog / reconnect retry continues working
 * in parallel. This event is a pure signal to the REST state machine.
 */
data class OutboundAckDeadlineExpiredEvent(val msgId: String, val ageMs: Long)

/**
 * PR-RECV-DIAG1 v1.6 — emitted by [KtorRelayTransport.startIdleWatchdog]
 * once per WS session when `sinceLastInbound >=
 * [RelayTransportConfig.INBOUND_STALL_THRESHOLD_MS]` (60 s by default).
 * Signals the "half-dead inbound" case where WS handshake succeeded but
 * no server-pushed frames arrive.
 */
data class InboundStalledEvent(val sinceLastInboundMs: Long)

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

    // R3.6 Fast REST degradation (2026-06-20): ordered lifecycle channel.
    // Channel.UNLIMITED-backed stream; trySend on UNLIMITED never fails
    // while the channel is open. The channel is never explicitly closed —
    // its lifetime equals the transport object lifetime. The
    // check(... .isSuccess) wrapper (IMPL-LOCK #2) turns any invariant
    // violation into a loud failure rather than a silent drop.
    //
    // IMPL-LOCK #1: exposed via receiveAsFlow() so collector termination
    // does NOT close the channel. consumeAsFlow() would close it and make
    // subsequent trySend(...).isSuccess return false.
    private val _wsSessionLifecycle: Channel<WsSessionLifecycleEvent> =
        Channel(Channel.UNLIMITED)

    /**
     * Ordered, guaranteed-delivery lifecycle stream. Emits [WsSessionLifecycleEvent.Connected]
     * after each successful WS handshake and [WsSessionLifecycleEvent.Ended] in each
     * session's `finally` block. Single consumer only ([HybridRelayTransport]).
     *
     * IMPL-LOCK #1: backed by [receiveAsFlow] so collector cancellation does
     * NOT close the underlying [Channel]. The producer emits into [_wsSessionLifecycle]
     * for the lifetime of this transport object — independent of how many times the
     * flow is collected and abandoned.
     */
    val wsSessionLifecycle: Flow<WsSessionLifecycleEvent> =
        _wsSessionLifecycle.receiveAsFlow()

    // PR-D1d (2026-05-17): fast per-envelope ACK deadline. Emitted on
    // [transportScope] after [RelayTransportConfig.ACK_DEADLINE_MS] elapses
    // without the relay confirming AckDeliver for a sent envelope. The
    // orchestrator ([HybridRelayTransport]) feeds this into
    // [RestStateMachine.Event.ActiveOutboundAckTimeout] which immediately
    // switches WS_ACTIVE → REST_ACTIVE on the first bad send — without
    // waiting for two full WS session deaths as the existing
    // active_outbound_threshold requires.
    //
    // replay = 0: stale deadline events after a mode switch must not be
    //   replayed to a new subscriber (the state machine is already in
    //   RestActive at that point and would no-op them, but skipping the
    //   replay makes the intent explicit).
    // extraBufferCapacity = 16: typical burst is one expired timer per
    //   concurrent outbound envelope; 16 is ample headroom.
    private val _outboundAckDeadlineExpired =
        MutableSharedFlow<OutboundAckDeadlineExpiredEvent>(
            replay = 0,
            extraBufferCapacity = 16,
        )
    val outboundAckDeadlineExpired: SharedFlow<OutboundAckDeadlineExpiredEvent> =
        _outboundAckDeadlineExpired.asSharedFlow()

    // PR-RECV-DIAG1 v1.6 — emitted once per WS session when the read
    // loop has not seen any Frame.Text for INBOUND_STALL_THRESHOLD_MS
    // (60 s). The orchestrator forwards this into the state machine
    // as Event.InboundIdleTimeout, which transitions WsActive → REST
    // even though no outbound is in flight. Re-armed at each new
    // session via the per-session pingJob restart.
    private val _inboundStalled = MutableSharedFlow<InboundStalledEvent>(
        replay = 0,
        extraBufferCapacity = 4,
    )
    val inboundStalled: SharedFlow<InboundStalledEvent> = _inboundStalled.asSharedFlow()

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
    // the ack watchdog can close it from the outside. Volatile because it's
    // mutated by runReconnectLoop on Dispatchers.Default and read by the
    // ACK watchdog on the same dispatcher's other workers.
    @Volatile private var currentGenerationClient: HttpClient? = null

    // PR-H2a (2026-05-13): strict FIFO outbox via per-envelope monotonic
    // sequence number. Test #33 captured a Double-Ratchet MAC failure caused
    // by reorder during reconnect flush — read-receipt encrypted at chain
    // position N+1 was sent on the wire BEFORE user-message encrypted at
    // chain position N because the requeue/flush path did not preserve
    // encrypt-time order across the live-send → ack-pending → requeue
    // transition. Receiver's chain key advanced past N+1's MAC key before
    // the late-arriving N could decrypt it, MAC failed, envelope was lost.
    //
    // Fix: every envelope gets a `sequenceTs` assigned ONCE on first entry
    // into outbox or pendingAcks (via [nextSequenceTs]). The value is
    // preserved across all re-queue / re-track transitions. Both the
    // ACK-watchdog requeue path and the reconnect requeue path merge
    // pendingAcks + existing outbox, sort by sequenceTs ASC, and rewrite
    // the outbox. flushPendingOutbox snapshots and re-sorts as a defence
    // in depth so any code path that ever inserts out-of-order does not
    // reach the wire.
    //
    // Wire format unchanged — sequenceTs is purely client-side.
    // Not persisted: on app restart, MessageRepository (status=QUEUED) is
    // re-submitted by DefaultMessagingService.send(), which assigns a
    // fresh sequence in the new process; relative order within that
    // re-submission is preserved by the per-conversation encrypt mutex
    // already held by encryptUnderLock.
    internal data class OutboxEntry(
        val message: RelayMessage,
        val sequenceTs: Long,
        val queuedAtMs: Long,
    )

    private val outboxMutex = Mutex()
    private val pendingOutbox: ArrayDeque<OutboxEntry> = ArrayDeque()

    // Per-transport monotonic counter. Guarded by [sequenceCounterLock] —
    // a dedicated mutex (not outboxMutex) so the live-send path can claim
    // a sequence without taking the outbox lock when the WS is up. Backed
    // by Mutex+Long because kotlinx.atomicfu is not on this module's
    // commonMain classpath (KMP common, no Kotlin/Native target here).
    private val sequenceCounterLock = Mutex()
    private var sequenceCounter: Long = 0L

    private suspend fun nextSequenceTs(): Long = sequenceCounterLock.withLock {
        ++sequenceCounter
    }

    // PR-H2a.1 (2026-05-13): serialize all RelayMessage.Send writes so a
    // new live send() cannot slip onto the wire between two flush items
    // mid-drain. Without this guard, the H2a sequenceTs sort would still
    // hold inside the outbox/pendingAcks data structures, but the wire
    // itself would interleave (flush sends seq=2, live send fires
    // seq=10 in parallel, flush sends seq=3 — receiver sees [2, 10, 3]
    // → MAC fail on the late-3 because chain advanced past it). Holding
    // the mutex across the entire flush loop means new sends queue
    // behind it instead.
    //
    // Scope: ONLY RelayMessage.Send goes through this mutex. Pings,
    // pongs, and ack-deliveries pass directly via sendRaw — they are
    // orthogonal to per-conversation ratchet ordering and must not be
    // blocked by a long flush (else the heartbeat dies and we trigger
    // an unnecessary forceReconnect during a normal flush). Ktor's
    // WebSocketSession.send() serializes wire frames internally, so
    // pings interleaving with envelope writes does not corrupt frames;
    // it only affects the encrypt-order vs wire-order invariant, which
    // pings/acks do not participate in.
    private val outboundSendMutex = Mutex()

    // Pong timestamp tracking — drives the heartbeat / dead-peer detection.
    // Updated every time the relay emits a Pong frame. If the gap exceeds
    // RelayTransportConfig.PONG_TIMEOUT_MS, the client closes the session,
    // which wakes openWithRetry() and triggers a reconnect.
    private val timeSource = TimeSource.Monotonic
    @Volatile private var lastPongMark: TimeSource.Monotonic.ValueTimeMark = timeSource.markNow()

    // PR-H1c (2026-05-13): liveness based on ANY inbound WS frame, not just
    // Pong. Refreshed in readLoop on every successfully-decoded frame
    // (Deliver, Ack, Pong) AND inside the Frame.Text branch before parsing
    // (so even malformed frames count as proof the wire is alive). Read by
    // the idle_watchdog diagnostic log (startIdleWatchdog) and by the
    // AlarmManager wakeup receiver diagnostic (PR-R0.4a).
    //
    // Test #35 motivation: server session_summary showed pings_received=2
    // and pongs_sent=2 for a 178 s session, while client sent 11 pings and
    // got 5 pongs back. Half-open TCP socket. Until the socket is genuinely
    // dead the receiver keeps observing other inbound activity, and any of
    // it should reset the watchdog.
    //
    // Also exposed to AlarmManagerKeepalive on Android (via the public
    // [millisSinceLastInboundFrame] accessor below) so the alarm path can
    // make the proactive-reconnect decision without app-level Pong gating.
    @Volatile private var lastInboundFrameMark: TimeSource.Monotonic.ValueTimeMark =
        timeSource.markNow()

    // Sent-but-unacknowledged envelopes. Frame.send() can succeed against a
    // half-dead socket without throwing — the bytes sit in the OkHttp buffer
    // and never reach the wire. The ACK watchdog promotes that silent loss
    // to an explicit retry: every entry older than ACK_TIMEOUT_MS is moved
    // back into pendingOutbox (sorted by sequenceTs) and the session is
    // force-closed, which makes runReconnectLoop open a fresh socket and
    // flushPendingOutbox re-send the envelope on top of the new session.
    //
    // sequenceTs is the SAME value the envelope was given on its first
    // pendingOutbox/pendingAcks insertion; preserved across requeue and
    // flush re-track so the eventual wire order matches encrypt order.
    internal data class AckPending(
        val message: RelayMessage.Send,
        val sentAt: TimeSource.Monotonic.ValueTimeMark,
        val sequenceTs: Long,
        val queuedAtMs: Long,
    )
    private val pendingAcksLock = Mutex()
    private val pendingAcks = mutableMapOf<String, AckPending>()

    // PR-D1d: one deadline Job per in-flight envelope, guarded by the
    // same [pendingAcksLock] so insert / cancel / clear are atomic with
    // the corresponding pendingAcks operations. A Job in this map means
    // the envelope's 10 s deadline timer is either running or has already
    // fired; removal (via ACK, session-end, or migration) cancels the timer.
    private val ackDeadlineJobs = mutableMapOf<String, Job>()

    // PR-D1d test-only override: when set to a TestScope, deadline Jobs
    // are launched on that scope (which runs on a virtual-time scheduler)
    // instead of [transportScope]. Production code never sets this.
    internal var ackDeadlineScopeOverride: CoroutineScope? = null

    // PR-D1d (refactor): single helper for `pendingAcks.remove + cancel timer`.
    // Caller MUST hold [pendingAcksLock]. Centralising this here makes it
    // structurally impossible to remove an envelope from pendingAcks without
    // also cancelling its 10 s deadline job — the original spread-across-4-sites
    // implementation relied on every caller remembering to do both.
    //
    // [reason] becomes the `reason=…` tag in the `outbound_ack_deadline_cancelled`
    // log line. Returns the removed [AckPending] (or null if there was none) so
    // callers that need the entry (e.g. the failed-send re-queue path) get it.
    private fun removePendingAckLocked(msgId: String, reason: String): AckPending? {
        val pending = pendingAcks.remove(msgId)
        ackDeadlineJobs.remove(msgId)?.also { job ->
            job.cancel()
            relayLog(
                RelayLogLevel.INFO,
                "${genTag()} outbound_ack_deadline_cancelled id=${msgId.take(12)}… reason=$reason",
            )
        }
        return pending
    }

    // PR-D1d (refactor): arm a per-envelope ACK deadline timer. Caller MUST
    // hold [pendingAcksLock] AND must have inserted (or be about to insert)
    // [entry] into pendingAcks in the same critical section. Centralised so
    // every new Send insertion site (currently the live-send path and the
    // reconnect-flush replay path) gets identical timer behaviour and identical
    // log format — no risk of one site missing the arm or arming with different
    // timeout values.
    //
    // [logPrefix] is the gen/session tag computed by the caller (`genTag()` or
    // `genTag(mySession)`), passed in so this helper does not need to know
    // about session context.
    private fun armAckDeadlineLocked(entry: AckPending, logPrefix: String) {
        val msgId = entry.message.messageId
        val sentMark = entry.sentAt
        val deadlineScope = ackDeadlineScopeOverride ?: transportScope
        // PR-D1d round-1 hardening: cancel any pre-existing deadline Job for
        // this msgId before replacing the map entry. The production removal
        // paths all route through `removePendingAckLocked` so this is unlikely
        // to fire in practice, but guarantees the invariant:
        //     one pendingAck msgId ⇒ at most one active deadline Job.
        // Without this, a re-arm under an edge-case path would orphan the old
        // coroutine (still running, eventually firing a stale expiry event for
        // an envelope that already has a fresh timer).
        ackDeadlineJobs.remove(msgId)?.cancel()
        ackDeadlineJobs[msgId] = deadlineScope.launch {
            delay(RelayTransportConfig.ACK_DEADLINE_MS)
            // Acquire the lock ONCE for the post-delay check + map cleanup +
            // event emission so they happen atomically w.r.t. any concurrent
            // ACK/migration/session-end path that might be racing.
            val shouldEmit = pendingAcksLock.withLock {
                val stillPending = pendingAcks.containsKey(msgId)
                // Remove the completed Job from the map regardless of whether
                // we emit the event — once we are past the delay, this Job is
                // done and should not occupy a map slot until session-end. The
                // pendingAcks entry itself stays put per the design contract:
                // removal is owned by the regular ACK path, session-end
                // cleanup, send-failed path, and REST migration so the existing
                // ACK watchdog / retry path keeps working in parallel.
                ackDeadlineJobs.remove(msgId)
                stillPending
            }
            if (shouldEmit) {
                val ageMs = sentMark.elapsedNow().inWholeMilliseconds
                relayLog(
                    RelayLogLevel.WARN,
                    "$logPrefix outbound_ack_deadline_expired id=${msgId.take(12)}… ageMs=$ageMs",
                )
                _outboundAckDeadlineExpired.tryEmit(
                    OutboundAckDeadlineExpiredEvent(msgId = msgId, ageMs = ageMs)
                )
            }
        }
        relayLog(
            RelayLogLevel.INFO,
            "$logPrefix outbound_ack_deadline_armed id=${msgId.take(12)}… timeoutMs=${RelayTransportConfig.ACK_DEADLINE_MS}",
        )
    }

    // PR-D1d (refactor): bulk version for session-end cleanup. Caller MUST
    // hold [pendingAcksLock]. Returns the drained list so callers can re-queue
    // into outbox; cancels every in-flight deadline timer with a single summary
    // log line (per-entry logs would be too noisy on session end).
    private fun clearAllPendingAcksLocked(reason: String, sessionEpoch: Long): List<AckPending> {
        val drained = pendingAcks.values.toList()
        pendingAcks.clear()
        val cancelledCount = ackDeadlineJobs.size
        ackDeadlineJobs.values.forEach { it.cancel() }
        ackDeadlineJobs.clear()
        if (cancelledCount > 0) {
            relayLog(
                RelayLogLevel.INFO,
                "${genTag(sessionEpoch)} outbound_ack_deadline_cancelled count=$cancelledCount reason=$reason",
            )
        }
        return drained
    }

    // ADR-013: scope owning the runReconnectLoop coroutine. Distinct
    // from the per-generation scope (which is reset every reconnect).
    // forceReconnect() abandons the current reconnectJob and launches
    // a new one on this scope, leaving the old loop's stuck webSocket{}
    // block as a zombie. The zombie holds one OkHttp reader thread
    // until the kernel eventually releases recv(); it does not block
    // the new loop from making progress.
    private val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var reconnectJob: Job? = null

    // PR-F2 (2026-05-12): serialize connect() and forceReconnect() calls
    // so racing watchdogs / lifecycle callbacks cannot each spawn their own
    // reconnect loop. Test #26 relay log captured 5 simultaneous
    // `event="connect"` for the same identity within 30 ms because three
    // independent paths (in-process pong watchdog, in-process ACK watchdog,
    // external AlarmManager wakeup) all called forceReconnect concurrently
    // and each ran a fresh `transportScope.launch { runReconnectLoop() }`
    // with no synchronization. The result was N parallel WS sessions on
    // the same identity, all but the latest becoming server-side zombies
    // that broke pong routing (relay's identity-keyed mpsc only delivers
    // to the latest registration).
    //
    // The mutex is held only during the brief setup phase (cancel old job,
    // launch new one) — never across the suspending runReconnectLoop body
    // — so it cannot block the connection itself.
    //
    // The generation counter detects "I am the second/third caller in the
    // same burst" and lets later callers no-op once an earlier caller
    // already advanced the generation: forceReconnect requested by three
    // watchdogs in the same 50 ms window collapses to one relaunch.
    private val connectionLifecycleMutex = Mutex()
    @Volatile private var connectionGeneration: Long = 0L

    // PR-H1a (2026-05-13): per-WebSocket-session epoch. Bumped every time
    // runReconnectLoop enters the `webSocket{}` block (a new live socket).
    // Distinct from `connectionGeneration` which only advances on
    // forceReconnect()/connect() lifecycle calls — `wsSessionEpoch`
    // captures every individual WS session, including clean-close +
    // reconnect cycles within a single connectionGeneration.
    //
    // Used as the `s=N` tag in all PhantomRelay logs so we can correlate
    // which session a Sending/Ack/Pong/Flush event belongs to. When a
    // pingJob from gen=2 s=5 still writes after forceReconnect bumped to
    // gen=3 s=7, the log line `[gen=3 s=5]` reveals the zombie precisely.
    @Volatile private var wsSessionEpoch: Long = 0L

    private fun genTag(): String = "[gen=$connectionGeneration s=$wsSessionEpoch]"

    private fun genTag(mySession: Long): String =
        "[gen=$connectionGeneration s=$mySession]"

    // PR-H1b (2026-05-13): per-WebSocket-session counters captured during
    // the lifetime of one `webSocket{}` block and logged as a single
    // `session_summary` line in the finally clause. Lets a post-mortem
    // answer "this session sent 47 pings, got 47 pongs over 612 s" vs
    // "sent 12 pings, got 0 pongs and died at 71 s" without grepping all
    // intermediate log lines and counting by hand.
    //
    // Mutated only from coroutines that belong to the same session
    // (`startIdleWatchdog`, `readLoop`); read once at session-end. No locking —
    // the writer set is single-coroutine per field.
    private class SessionStats(
        val sessionEpoch: Long,
        val startedAtMs: Long,
        var pingsSent: Long = 0,
        var pingSendFailures: Long = 0,
        var pongsReceived: Long = 0,
        var inboundFrames: Long = 0,
        var deliversReceived: Long = 0,
        var acksReceived: Long = 0,
        var lastPingAtMs: Long = 0,
        var lastPongAtMs: Long = 0,
        var lastInboundFrameAtMs: Long = 0,
    )

    @Volatile private var currentSessionStats: SessionStats? = null

    /**
     * PR-H1b: classification of *who* terminated a WebSocket session, used
     * by the client-side `session_summary` log line. Mirrors the relay-
     * side `close_origin` enum so post-mortem grep can correlate close-
     * reason and close-origin from both sides of the wire on the same
     * session.
     *
     * - [Remote]: peer-initiated clean close, identified by a non-null
     *   `closeReason` await result.
     * - [Local]: client-initiated close — `disconnect()` or
     *   `forceReconnect()` requested by a watchdog. Detected via
     *   `disconnectRequested` flag at the time `webSocket{}` exited, or
     *   by a `CancellationException` whose root cause is our cancel.
     * - [Error]: thrown exception from the `webSocket{}` block (transport
     *   I/O failure, parse failure that escaped the read-loop's catch).
     * - [Unknown]: webSocket{} returned without a close reason and no
     *   exception — kernel detected a half-open socket (TCP RST), or
     *   readLoop completed without an explicit close frame.
     */
    private enum class CloseOrigin { Remote, Local, Error, Unknown }

    /**
     * PR-H1b: emit a single `session_summary` log line summarising the
     * lifetime of one WebSocket session. Called once from the finally
     * block of `runReconnectLoop`. Aggregates the per-session counters
     * collected by `startIdleWatchdog` / `readLoop`, computes durations, and
     * tags the close cause so we can answer "did this session die from
     * pong timeout, peer close, or client-side cancel" without
     * stitching together separate log lines.
     */
    private fun emitSessionSummary(
        stats: SessionStats?,
        origin: CloseOrigin,
        closeCode: Short?,
        closeMessage: String?,
        thrown: Throwable?,
    ) {
        if (stats == null) {
            relayLog(
                RelayLogLevel.WARN,
                "${genTag()} session_summary missing — webSocket{} block ended before stats were initialised (origin=$origin)",
            )
            return
        }
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val durationMs = (nowMs - stats.startedAtMs).coerceAtLeast(0)
        val sinceLastPongMs = if (stats.lastPongAtMs == 0L) -1L else nowMs - stats.lastPongAtMs
        val sinceLastPingMs = if (stats.lastPingAtMs == 0L) -1L else nowMs - stats.lastPingAtMs
        val sinceLastInboundMs =
            if (stats.lastInboundFrameAtMs == 0L) -1L else nowMs - stats.lastInboundFrameAtMs
        val missedPongs = (stats.pingsSent - stats.pongsReceived).coerceAtLeast(0)
        val codeStr = closeCode?.toString() ?: "none"
        val reasonStr = closeMessage?.take(120) ?: ""
        val thrownStr = thrown?.let { "${it::class.simpleName}: ${it.message}" } ?: ""
        relayLog(
            RelayLogLevel.INFO,
            "${genTag(stats.sessionEpoch)} session_summary " +
                "duration_ms=$durationMs " +
                "close_origin=${origin.name.lowercase()} " +
                "close_code=$codeStr " +
                "close_reason='$reasonStr' " +
                "thrown='$thrownStr' " +
                "pings_sent=${stats.pingsSent} " +
                "pongs_received=${stats.pongsReceived} " +
                "missed_pongs=$missedPongs " +
                "ping_send_failures=${stats.pingSendFailures} " +
                "inbound_frames=${stats.inboundFrames} " +
                "delivers_received=${stats.deliversReceived} " +
                "acks_received=${stats.acksReceived} " +
                "since_last_ping_ms=$sinceLastPingMs " +
                "since_last_pong_ms=$sinceLastPongMs " +
                "since_last_inbound_ms=$sinceLastInboundMs",
        )

        // PR-WS-HEALTH-STATE1 Commit 3.3 (2026-05-31): second emit with
        // OkHttp-counter-aware fields. The `session_summary` line above
        // is preserved byte-for-byte for operator correlation across
        // historical runs; this new `ws_ping_timeout_diag` line carries
        // the diagnostic fields the design note locked.
        //
        // Findings A/B/C of the design note audit established that
        // `stats.pingsSent` / `stats.pongsReceived` / `lastPongMark`
        // are structurally dead — they would increment only via an
        // app-level RelayMessage.Ping/Pong loop whose sender was
        // removed in PR-H1e. The values are kept on the line for
        // continuity, but `app_level_dead_counter=true` is the
        // explicit warning so future readers don't chase the zeros.
        //
        // `okhttp_successful_ping_pongs` is the only non-lying
        // ping/pong number currently available client-side: the
        // integer OkHttp embeds in the SocketTimeoutException text
        // at close time. Pairs with relay-side `ws_protocol_ping_received`
        // / `ws_protocol_pong_sent` (PR-WS-HEALTH-STATE1 Commit 3.3
        // routes.rs change) for three-source field-test correlation
        // discriminating H-Ping1 / H-Ping2 / H-Ping3 / H-Ping6.
        val okhttpSuccessfulPingPongs =
            PingTimeoutTextParser.parseSuccessfulPingPongs(thrown?.message)
        val okhttpThrowableClass = thrown?.let { it::class.simpleName } ?: "none"
        // Rev2 (architect P3 on PR #259): the emit runs on every session
        // close, not only on OkHttp ping timeouts. For analysis purposes
        // it is important to discriminate "real ping-timeout session"
        // from "clean close where OkHttp didn't emit the after-N idiom".
        // The parser returns -1 only when the regex does not match, and
        // the regex is OkHttp-specific, so `okhttpSuccessfulPingPongs >= 0`
        // is a reliable detector. Field-test grep can filter to
        // `okhttp_ping_timeout_detected=true` to isolate the failure mode.
        val okhttpPingTimeoutDetected = okhttpSuccessfulPingPongs >= 0
        relayLog(
            RelayLogLevel.INFO,
            "${genTag(stats.sessionEpoch)} ws_ping_timeout_diag " +
                "duration_ms=$durationMs " +
                "okhttp_ping_timeout_detected=$okhttpPingTimeoutDetected " +
                "okhttp_successful_ping_pongs=$okhttpSuccessfulPingPongs " +
                "okhttp_throwable_class=$okhttpThrowableClass " +
                "app_level_ping_sent=${stats.pingsSent} " +
                "app_level_pong_received=${stats.pongsReceived} " +
                "app_level_dead_counter=true " +
                "inbound_frames=${stats.inboundFrames} " +
                "acks_received=${stats.acksReceived} " +
                "since_last_inbound_ms=$sinceLastInboundMs " +
                "ping_interval_ms=15000 " +
                "read_timeout_ms=60000",
        )

        // DWS-UX.1 (2026-06-17): raw ping-timeout counter, emitted on
        // every session close whose throwable class is
        // `SocketTimeoutException`, regardless of whether the
        // `PingTimeoutTextParser` regex matched on the exception
        // message. The `ws_ping_timeout_diag` line above gates
        // `okhttp_ping_timeout_detected` on the parser; field smoke on
        // Tele2 LTE 2026-06-16/17 captured 32 ping-timeout sessions
        // with `WS_DEGRADED = 0` because the parser may not match
        // every Tele2-close exception shape. This raw line is the
        // baseline counter that `WS_DEGRADED` was supposed to be, with
        // no parser dependency, so `grep ws_ping_timeout_raw` returns
        // the honest session count for telemetry. NOT wired to any
        // actuation — diagnostic-only, mirroring the
        // `ws_ping_timeout_diag` posture.
        if (okhttpThrowableClass == "SocketTimeoutException") {
            relayLog(
                RelayLogLevel.INFO,
                "${genTag(stats.sessionEpoch)} ws_ping_timeout_raw " +
                    "session_id=${stats.sessionEpoch} " +
                    "duration_ms=$durationMs " +
                    "inbound_frames=${stats.inboundFrames} " +
                    "since_last_inbound_ms=$sinceLastInboundMs " +
                    "parser_detected=$okhttpPingTimeoutDetected",
            )
        }
    }

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
        // PR-H1c: same rationale for the inbound-frame liveness mark, which
        // now drives both the in-process pong-timeout watchdog and the
        // AlarmManager proactive reconnect.
        lastInboundFrameMark = timeSource.markNow()
        relayLog(
            RelayLogLevel.INFO,
            "${genTag()} connect() called: url=$relayUrl identity=${identityPublicKeyHex.take(16)}… signing=${signingPublicKeyHex.take(16)}… socks=${socksProxyPort ?: "direct"}",
        )
        // PR-F2: serialize lifecycle setup. The lock is held only while
        // we cancel the prior reconnect loop and launch a new one — never
        // across the suspending join() below — so racing connect()/
        // forceReconnect() callers serialize on setup but do not block
        // the connection itself.
        val newJob = connectionLifecycleMutex.withLock {
            // If a reconnect loop is already running, do NOT spawn a second
            // one. A second connect() in the wild typically comes from a
            // double-start of the foreground service (AlarmManager wakeup
            // + lifecycle bring-back firing in the same window). Returning
            // the existing job preserves the original "suspend until the
            // loop ends" semantics callers expect.
            val existing = reconnectJob
            if (existing != null && existing.isActive && !disconnectRequested) {
                relayLog(
                    RelayLogLevel.INFO,
                    "${genTag()} connect: reconnect loop already active — joining existing job, no new loop spawned",
                )
                existing
            } else {
                connectionGeneration += 1
                relayLog(
                    RelayLogLevel.INFO,
                    "${genTag()} connect: launching fresh reconnect loop",
                )
                val launched = transportScope.launch { runReconnectLoop() }
                reconnectJob = launched
                launched
            }
        }
        // Suspend forever (or until disconnectRequested is observed by
        // the loop and runReconnectLoop returns) so existing callers
        // that `await` connect() — PhantomMessagingService — keep their
        // structured-concurrency expectations.
        newJob.join()
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
            // PR-H1a: bump per-WS-session epoch ONCE per iteration of the
            // outer while-loop. Captured into a local `mySession` so all
            // per-session jobs (startIdleWatchdog, startAckWatchdog, readLoop,
            // flushPendingOutbox, mergeUnackedIntoOutboxOrdered) tag their
            // logs with this same value even after `wsSessionEpoch` advances
            // again (next iteration / new forceReconnect cycle). That is
            // exactly how we will spot a zombie writer post-forceReconnect.
            val mySession = ++wsSessionEpoch
            val authedWsUrl = try {
                buildAuthedWsUrl(generationClient)
            } catch (t: Throwable) {
                relayLog(
                    RelayLogLevel.ERROR,
                    "${genTag(mySession)} Auth handshake failed (attempt=$attempt): ${t::class.simpleName} ${t.message}",
                )
                runCatching { generationClient.close() }
                if (disconnectRequested) break
                val delayMs = min(
                    RelayTransportConfig.RECONNECT_BASE_DELAY_MS * (1L shl min(attempt, 16)),
                    RelayTransportConfig.RECONNECT_MAX_DELAY_MS,
                )
                relayLog(RelayLogLevel.INFO, "${genTag(mySession)} Retry attempt #${attempt + 1} in ${delayMs}ms")
                delay(delayMs)
                attempt++
                continue
            }
            if (authedWsUrl == null) {
                relayLog(
                    RelayLogLevel.WARN,
                    "${genTag(mySession)} Auth handshake aborted (attempt=$attempt) — signing key not ready or relay returned no challenge",
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
                "${genTag(mySession)} Attempting WebSocket connect (attempt=$attempt): $redactedUrl",
            )
            // PR-H1b: capture-on-exit fields populated inside try/catch and
            // read once from the finally block for `session_summary`. Local
            // vars (not @Volatile fields) because only this coroutine reads
            // and writes them within the iteration.
            var sessionStats: SessionStats? = null
            var closeCode: Short? = null
            var closeMessage: String? = null
            var caughtThrowable: Throwable? = null
            var closeOriginOverride: CloseOrigin? = null
            try {
                generationClient.webSocket(authedWsUrl) {
                    session = this
                    lastPongMark = timeSource.markNow()
                    lastInboundFrameMark = timeSource.markNow()
                    // PR-H1b: stats holder for this WS session. Stored in
                    // `currentSessionStats` so startIdleWatchdog's pingJob
                    // and readLoop can mutate counters without an extra
                    // parameter. Captured locally as `sessionStats` so the
                    // finally block can emit `session_summary` regardless
                    // of how `webSocket{}` exited.
                    val stats = SessionStats(
                        sessionEpoch = mySession,
                        startedAtMs = Clock.System.now().toEpochMilliseconds(),
                    )
                    currentSessionStats = stats
                    sessionStats = stats
                    _state.value = TransportState.Connected
                    relayLog(RelayLogLevel.INFO, "${genTag(mySession)} WebSocket connected successfully")
                    // R3.6: emit Connected into the ordered lifecycle channel so the
                    // sticky-recovery state machine (via HybridRelayTransport) can
                    // open a recovery probation window on this new session.
                    // IMPL-LOCK #2: check isSuccess — trySend on UNLIMITED only
                    // fails if the channel is closed (invariant violation).
                    check(
                        _wsSessionLifecycle.trySend(
                            WsSessionLifecycleEvent.Connected(mySession)
                        ).isSuccess
                    ) { "WS lifecycle channel unexpectedly closed (Connected emission epoch=$mySession)" }
                    attempt = 0 // reset backoff on successful connect

                    val transportScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                    generationScope = transportScope
                    scope = transportScope
                    startIdleWatchdog(transportScope, mySession)
                    startAckWatchdog(transportScope, generationClient, mySession)

                    // PR-H2a: merge every still-unacknowledged envelope from
                    // the previous session into the outbox (sorted by
                    // sequenceTs ASC). They were sent on the previous session
                    // and never confirmed — most likely lost in transit.
                    // flushPendingOutbox below will then re-send everything
                    // in strict encrypt order before any new outbound traffic
                    // can interleave.
                    mergeUnackedIntoOutboxOrdered(mySession)

                    // Drain anything the app queued while the socket was down.
                    flushPendingOutbox(mySession)

                    readLoop(mySession)
                    // PR-H1b: extract close code + reason from the close
                    // frame the peer sent. `closeReason` is a Deferred
                    // completed by Ktor when the WS handshake teardown
                    // observed a Close frame. await() with a short timeout
                    // because some abnormal closes (kernel TCP RST) leave
                    // closeReason uncompleted forever.
                    val cr = withTimeoutOrNull(500) {
                        runCatching { closeReason.await() }.getOrNull()
                    }
                    closeCode = cr?.code
                    closeMessage = cr?.message
                    relayLog(
                        RelayLogLevel.WARN,
                        "${genTag(mySession)} WebSocket closed by remote (clean): code=${closeCode ?: "none"} reason='${closeMessage ?: ""}'",
                    )
                }
                _state.value = TransportState.Disconnected
                if (disconnectRequested) break
                delay(RelayTransportConfig.RECONNECT_BASE_DELAY_MS)
                relayLog(RelayLogLevel.INFO, "${genTag(mySession)} Reconnecting after clean close")
                // attempt was reset to 0 above; restart backoff from base.
                continue
            } catch (e: CancellationException) {
                // PR-H1b: separate from generic Exception so session_summary
                // can tag close_origin=local. CancellationException here
                // means our own scope was cancelled (forceReconnect or
                // disconnect) — semantically a *local* close, not a
                // network failure. Re-thrown so coroutine teardown still
                // happens normally; the finally block runs first.
                caughtThrowable = e
                closeOriginOverride = CloseOrigin.Local
                throw e
            } catch (e: Exception) {
                caughtThrowable = e
                _state.value = TransportState.Error(e)
                relayLog(
                    RelayLogLevel.ERROR,
                    "${genTag(mySession)} WebSocket connect FAILED (attempt=$attempt, type=${e::class.simpleName}): ${e.message}",
                    e,
                )
                if (disconnectRequested) break
                val delayMs = min(
                    RelayTransportConfig.RECONNECT_BASE_DELAY_MS * (1L shl min(attempt, 16)),
                    RelayTransportConfig.RECONNECT_MAX_DELAY_MS,
                )
                relayLog(RelayLogLevel.INFO, "${genTag(mySession)} Retry attempt #${attempt + 1} in ${delayMs}ms")
                delay(delayMs)
                attempt++
            } finally {
                // PR-H1b: classify close cause, then emit the single
                // `session_summary` line summarising the lifetime of this
                // WS session. Done before the scope-cancel below so the
                // counters reflect whatever startIdleWatchdog/readLoop observed
                // (the scope cancel will also stop those coroutines, but
                // they don't update stats after the readLoop exits).
                val origin = closeOriginOverride ?: when {
                    caughtThrowable is CancellationException -> CloseOrigin.Local
                    caughtThrowable != null -> CloseOrigin.Error
                    disconnectRequested -> CloseOrigin.Local
                    closeCode != null -> CloseOrigin.Remote
                    else -> CloseOrigin.Unknown
                }
                emitSessionSummary(
                    stats = sessionStats,
                    origin = origin,
                    closeCode = closeCode,
                    closeMessage = closeMessage,
                    thrown = caughtThrowable,
                )
                // R3.6 (2026-06-20): assemble the common fields once for the
                // ordered lifecycle channel emission below.
                val sessionDurationMs = sessionStats?.let {
                    (Clock.System.now().toEpochMilliseconds() - it.startedAtMs)
                        .coerceAtLeast(0)
                } ?: 0L
                val sessionInboundFrames = sessionStats?.inboundFrames?.toInt() ?: 0
                val sessionCloseOrigin = origin.name.lowercase()
                val sessionCloseError = caughtThrowable?.message
                // PR-WS-HEALTH-STATE1 Commit 3.2a: same detector signal.
                val pingTimeoutDetected =
                    PingTimeoutTextParser.parseSuccessfulPingPongs(caughtThrowable?.message) >= 0

                // R3.6 IMPL-LOCK #2: emit Ended into the ordered channel.
                // trySend on UNLIMITED only fails if the channel is closed;
                // the check enforces the invariant loudly rather than
                // silently dropping a load-bearing lifecycle event.
                check(
                    _wsSessionLifecycle.trySend(
                        WsSessionLifecycleEvent.Ended(
                            durationMs = sessionDurationMs,
                            inboundFrames = sessionInboundFrames,
                            pendingAcksAtClose = pendingAckCount,
                            closeOrigin = sessionCloseOrigin,
                            closeError = sessionCloseError,
                            okhttpPingTimeoutDetected = pingTimeoutDetected,
                            sessionEpoch = mySession,
                        )
                    ).isSuccess
                ) { "WS lifecycle channel unexpectedly closed (Ended emission epoch=$mySession)" }

                if (currentSessionStats === sessionStats) {
                    currentSessionStats = null
                }
                // Cancel the previous generation's coroutines and close its
                // HttpClient. ADR-010 (Updated 2026-05-01) — closing the
                // HttpClient destroys the OkHttp dispatcher and connection
                // pool, releasing any active socket the reader thread is
                // parked on. This is what unblocks Tecno HiOS post-radio-park.
                relayLog(
                    RelayLogLevel.INFO,
                    "${genTag(mySession)} webSocket{} block exited — entering finally block, cancelling scope and closing generation client",
                )
                val joined = withTimeoutOrNull(5_000) {
                    generationScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                    Unit
                }
                if (joined == null) {
                    relayLog(
                        RelayLogLevel.WARN,
                        "${genTag(mySession)} Generation scope cancelAndJoin timed out (>5s) — proceeding anyway",
                    )
                }
                runCatching { generationClient.close() }
                    .onFailure {
                        relayLog(
                            RelayLogLevel.WARN,
                            "${genTag(mySession)} generationClient.close() threw: ${it::class.simpleName}: ${it.message}",
                        )
                    }
                if (currentGenerationClient === generationClient) {
                    currentGenerationClient = null
                }
                relayLog(
                    RelayLogLevel.INFO,
                    "${genTag(mySession)} Generation client closed — looping for next reconnect attempt",
                )
            }
        }
        relayLog(RelayLogLevel.INFO, "${genTag()} Reconnect loop exited (disconnect requested)")
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

    // PR-R0.4b (2026-05-16): renamed from startPing. The stale-inbound
    // forceReconnect branch has been removed unconditionally because
    // `lastInboundFrameMark` is updated only on Frame.Text in readLoop —
    // OkHttp WS Ping/Pong control frames are consumed inside OkHttp's
    // engine and never surface as Frame.Pong in Ktor's incoming channel.
    // On a healthy idle socket OkHttp sends a Ping every 15 s and the
    // peer replies with a Pong, but our counter never advances, so the
    // old predicate (sinceLastInbound > DEAD_SOCKET_TIMEOUT_MS) fired
    // every 60 s and killed a perfectly live session. Test #47 evidence:
    // session_summary duration_ms=60063 inbound_frames=0 pongs_received=0
    // repeating every minute — exactly this pattern.
    //
    // Real dead-socket triggers remain intact:
    //   • OkHttp WS Ping onFailure callback (RelayTransportFactory.kt)
    //   • sendRaw write failure (handled in send() callers)
    //   • ACK watchdog (startAckWatchdog — only fires when pendingAcks > 0)
    //   • Manual forceReconnect() from outside
    //   • Network-change reconnect (ConnectivityManager callback)
    //   • Server-initiated close frame (readLoop close-frame branch)
    //
    // The generationClient parameter is no longer needed (no network calls
    // issued here); the call site passes only scope + mySession.
    private fun startIdleWatchdog(scope: CoroutineScope, mySession: Long) {
        pingJob = scope.launch {
            var lastLoggedAt = TimeSource.Monotonic.markNow()
            // PR-RECV-DIAG1 v1.6 — emit-once flag per session. The
            // half-dead-inbound condition is sticky: once we cross the
            // threshold we want to fire ONE InboundStalledEvent and let
            // the state machine handle it. If we kept emitting every
            // 10 s, the state machine would log redundant transitions
            // and the REST poll loop would get noisier than necessary.
            // Re-armed automatically when a new session calls
            // startIdleWatchdog because pingJob is replaced.
            var inboundStallEmitted = false
            while (isActive) {
                delay(RelayTransportConfig.PING_INTERVAL_MS)  // 10 s poll cadence
                val sinceLastInbound = lastInboundFrameMark.elapsedNow().inWholeMilliseconds
                val sinceLastPong = lastPongMark.elapsedNow().inWholeMilliseconds
                val pending = pendingAckCount

                // Emit a diagnostic log at ~60 s intervals. Piggybacks on the
                // existing 10 s poll loop via a local mark — no second coroutine.
                if (lastLoggedAt.elapsedNow().inWholeMilliseconds >= 60_000L) {
                    lastLoggedAt = TimeSource.Monotonic.markNow()
                    relayLog(
                        RelayLogLevel.INFO,
                        "${genTag(mySession)} idle_watchdog " +
                            "sinceLastInbound=${sinceLastInbound}ms " +
                            "sinceLastPong=${sinceLastPong}ms " +
                            "pendingAcks=${pending} — no reconnect action (R0.4b)",
                    )
                }

                // PR-RECV-DIAG1 v1.6 — fire InboundStalledEvent once per
                // session when the read loop has not seen any Frame.Text
                // for INBOUND_STALL_THRESHOLD_MS. This is the real
                // production-class trigger that test #84.7 isolated:
                // WS open, outbound works, but inbound silently dropped.
                // The orchestrator forwards this into the state machine.
                if (!inboundStallEmitted &&
                    sinceLastInbound >= RelayTransportConfig.INBOUND_STALL_THRESHOLD_MS
                ) {
                    inboundStallEmitted = true
                    relayLog(
                        RelayLogLevel.WARN,
                        "${genTag(mySession)} inbound_stall_detected " +
                            "sinceLastInbound=${sinceLastInbound}ms — emitting " +
                            "InboundStalledEvent (REST fallback should activate)",
                    )
                    _inboundStalled.tryEmit(InboundStalledEvent(sinceLastInbound))
                }

                // Reset emit flag if traffic resumes mid-session (e.g. WS
                // recovers without a session-end + reconnect). Lets us
                // re-fire on the next stall window if the WS goes
                // half-dead again later.
                if (inboundStallEmitted &&
                    sinceLastInbound < RelayTransportConfig.INBOUND_STALL_THRESHOLD_MS / 2
                ) {
                    inboundStallEmitted = false
                }
                // No forceReconnect here. OkHttp Ping onFailure handles real
                // dead sockets. ACK watchdog handles the pending-ack timeout.
            }
        }
    }

    private fun startAckWatchdog(scope: CoroutineScope, generationClient: HttpClient, mySession: Long) {
        ackWatchdogJob = scope.launch {
            while (isActive) {
                delay(RelayTransportConfig.ACK_WATCHDOG_INTERVAL_MS)
                val expired = pendingAcksLock.withLock {
                    val toExpire = pendingAcks.values.filter {
                        it.sentAt.elapsedNow().inWholeMilliseconds > RelayTransportConfig.ACK_TIMEOUT_MS
                    }
                    // PR-D1d: route via helper so a long-pending envelope that
                    // also had its D1d deadline timer fired (or is still arming)
                    // has its Job entry cleared from `ackDeadlineJobs` here too.
                    // Without this the cancelled Job stays in the map until
                    // session end — not a correctness bug (cancelled Jobs are
                    // inert) but a per-envelope leak.
                    toExpire.forEach { removePendingAckLocked(it.message.messageId, reason = "ack_watchdog_timeout") }
                    toExpire
                }
                if (expired.isEmpty()) continue
                relayLog(
                    RelayLogLevel.WARN,
                    "${genTag(mySession)} ACK timeout: ${expired.size} envelope(s) unacknowledged after ${RelayTransportConfig.ACK_TIMEOUT_MS}ms — requeuing and forcing reconnect. " +
                        "First id=${expired.first().message.messageId.take(12)}…",
                )
                // Per-envelope trace so a multi-chunk upload (voice messages,
                // PR-F1 2026-05-12) shows in logs which slices got requeued.
                // PR-H2a: log the original sequenceTs so we can verify in
                // post-mortem that the next flush sorted them back into
                // encrypt order.
                expired.forEach {
                    relayLog(
                        RelayLogLevel.WARN,
                        "${genTag(mySession)} ACK watchdog requeue: id=${it.message.messageId.take(12)}… seq=${it.sequenceTs}",
                    )
                }
                // PR-H2a: merge expired entries with current outbox and
                // sort by sequenceTs ASC so the next flush sees strict
                // encrypt order. Replaces the addFirst-asReversed dance
                // which only worked when no new sends had been queued
                // during the ACK window.
                outboxMutex.withLock {
                    val merged = ArrayList<OutboxEntry>(pendingOutbox.size + expired.size)
                    merged.addAll(pendingOutbox)
                    expired.forEach {
                        merged.add(OutboxEntry(it.message, it.sequenceTs, it.queuedAtMs))
                    }
                    merged.sortBy { it.sequenceTs }
                    pendingOutbox.clear()
                    pendingOutbox.addAll(merged)
                }
                // PR-F1.2 (2026-05-12): same fix as the pong-timeout path —
                // mark the transport disconnected so racing send() calls hit
                // the outbox path, then forceReconnect() to launch a fresh
                // runReconnectLoop. The previous scope.cancel + break left
                // the session zombied on Tecno HiOS.
                //
                // PR-H1c: Reconnecting (not Disconnected) for the same UX
                // reason as the pong-timeout path — outbox sends still
                // queue and flush after recovery; the cue communicates
                // "in-flight, not failed".
                _state.value = TransportState.Reconnecting
                forceReconnect()
                break
            }
        }
    }

    /**
     * PR-H2a: drains pendingAcks, merges those entries with whatever is
     * currently sitting in pendingOutbox, sorts by sequenceTs ASC, and
     * rewrites the outbox. The next flush will then send everything in
     * strict encrypt order regardless of which path each envelope took
     * to get back here (live-send-failed, queued-while-down, ack-watchdog
     * timeout).
     *
     * Replaces the pre-H2a logic which prepended the reversed pendingAcks
     * to outbox.front and relied on Map iteration order — fragile and
     * provably broken by Test #33 (read receipt encrypted at chain pos 2
     * landed on the wire AFTER user message at chain pos 3, MAC-failed
     * on receiver, message lost).
     */
    internal suspend fun mergeUnackedIntoOutboxOrdered(mySession: Long) {
        val drained = pendingAcksLock.withLock {
            // PR-D1d: bulk helper drains pendingAcks AND cancels every in-flight
            // deadline timer atomically. The envelopes are about to be re-queued
            // into the outbox for the next session; their old timers (armed
            // against the just-ended session) are stale and would fire spuriously.
            // Each re-send via send() will arm a fresh 10 s deadline if needed.
            clearAllPendingAcksLocked(reason = "session_ended", sessionEpoch = mySession)
        }
        if (drained.isEmpty()) return
        relayLog(
            RelayLogLevel.INFO,
            "${genTag(mySession)} Re-queueing ${drained.size} unacknowledged envelope(s) from previous session (merging into outbox by sequenceTs ASC)",
        )
        outboxMutex.withLock {
            val merged = ArrayList<OutboxEntry>(pendingOutbox.size + drained.size)
            merged.addAll(pendingOutbox)
            drained.forEach {
                merged.add(OutboxEntry(it.message, it.sequenceTs, it.queuedAtMs))
            }
            merged.sortBy { it.sequenceTs }
            pendingOutbox.clear()
            pendingOutbox.addAll(merged)
        }
    }

    private suspend fun WebSocketSession.readLoop(mySession: Long) {
        for (frame in incoming) {
            if (frame is Frame.Text) {
                // PR-H1b: any text frame counts as inbound activity. Tracked
                // separately from `pongsReceived` so a post-mortem can spot
                // "frames arrived but not pongs" (relay pong-routing bug
                // class) vs "nothing arrived at all" (peer dead / radio
                // parked). Stats stay scoped to this session via the
                // sessionEpoch equality check.
                currentSessionStats?.takeIf { it.sessionEpoch == mySession }?.let { stats ->
                    stats.inboundFrames += 1
                    stats.lastInboundFrameAtMs = Clock.System.now().toEpochMilliseconds()
                }
                // PR-H1c: refresh the instance-level liveness mark consumed
                // by both the idle_watchdog diagnostic log (startIdleWatchdog)
                // and the AlarmManager proactive reconnect path (Android wake-up).
                // Done unconditionally — even malformed frames count as
                // proof the wire is alive; what matters for the watchdog
                // is "did anything come back from the relay", not whether
                // it parsed cleanly.
                lastInboundFrameMark = timeSource.markNow()
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
                            currentSessionStats?.takeIf { it.sessionEpoch == mySession }
                                ?.let { it.deliversReceived += 1 }
                            relayLog(
                                RelayLogLevel.INFO,
                                "${genTag(mySession)} Received envelope: id=${msg.messageId.take(12)}… sealed=${msg.sealedSender.isNotEmpty()} payloadBytes=${msg.payload.length}",
                            )
                            _incoming.emit(msg)
                        }
                        is RelayMessage.Ack -> {
                            // Clear the watchdog entry — relay confirms the
                            // envelope landed. Whether it was live-delivered
                            // or queued is an upper-layer concern; for our
                            // purposes any Ack tells us the wire roundtrip
                            // worked.
                            // PR-D1d: helper guarantees the deadline timer is
                            // cancelled atomically with the pendingAcks removal.
                            pendingAcksLock.withLock {
                                removePendingAckLocked(msg.messageId, reason = "ack_received")
                            }
                            currentSessionStats?.takeIf { it.sessionEpoch == mySession }
                                ?.let { it.acksReceived += 1 }
                            relayLog(
                                RelayLogLevel.INFO,
                                "${genTag(mySession)} Ack from relay: id=${msg.messageId.take(12)}… status=${msg.status}",
                            )
                            _acks.emit(msg)
                        }
                        is RelayMessage.Pong -> {
                            // PR-H1a: explicit pong_received log so we can
                            // confirm in test logs which session the relay
                            // actually replied to. If [s=N] of pong_received
                            // does not match [s=N] of the most recent
                            // ping_send, frames are arriving on a stale
                            // generation's reader.
                            lastPongMark = timeSource.markNow()
                            currentSessionStats?.takeIf { it.sessionEpoch == mySession }?.let { stats ->
                                stats.pongsReceived += 1
                                stats.lastPongAtMs = Clock.System.now().toEpochMilliseconds()
                            }
                            relayLog(
                                RelayLogLevel.INFO,
                                "${genTag(mySession)} pong_received",
                            )
                        }
                        else -> Unit
                    }
                } catch (e: Exception) {
                    // Malformed frame — log but do not crash the read loop.
                    relayLog(
                        RelayLogLevel.WARN,
                        "${genTag(mySession)} Malformed frame dropped (${e::class.simpleName}): ${e.message}",
                    )
                }
            }
        }
        relayLog(RelayLogLevel.WARN, "${genTag(mySession)} readLoop exited — connection lost")
        _state.value = TransportState.Disconnected
    }

    override suspend fun send(message: RelayMessage.Send): Boolean {
        // PR-H2a: claim sequenceTs ONCE here, before any branch. The same
        // value flows into pendingAcks (live-send path) or pendingOutbox
        // (queued path). On any later requeue/flush the value is preserved,
        // so wire order = encrypt order regardless of how many disconnect /
        // reconnect cycles the envelope survives. The per-conversation
        // encrypt mutex in DefaultMessagingService.encryptUnderLock ensures
        // sequenceTs is monotonic per-conversation: two encrypts on the
        // same conversation cannot interleave with each other's send().
        val seq = nextSequenceTs()
        val nowMs = Clock.System.now().toEpochMilliseconds()
        if (!isConnected()) {
            outboxMutex.withLock {
                pendingOutbox.addLast(OutboxEntry(message, seq, nowMs))
            }
            relayLog(
                RelayLogLevel.INFO,
                "${genTag()} Queued until reconnect: id=${message.messageId.take(12)}… to=${message.to.take(16)}… " +
                    "seq=$seq state=${_state.value::class.simpleName} outboxSize=${pendingOutbox.size}",
            )
            // Returns false so MessageRepository keeps status = QUEUED and the
            // UI can render a pending indicator. The relay's Ack will promote
            // status to RELAYED once the envelope actually reaches the server.
            return false
        }
        // PR-H2a.1: serialize against flushPendingOutbox. Holding the
        // outbound mutex here means a flush in progress finishes its
        // entire ordered drain before any new live send hits the wire.
        // Inside the lock we also re-check pendingOutbox: if anything
        // is still queued (flush has not yet started for the current
        // generation, or the outbox accumulated entries during a brief
        // reconnect), we MUST queue rather than live-send — otherwise
        // the new envelope (higher sequenceTs) overtakes older queued
        // entries on the wire. This is the second leg of the H2a fix:
        // the in-memory sort is necessary but not sufficient; the wire
        // itself must also see strict order, which requires that no live
        // send fires while the outbox holds anything older.
        return outboundSendMutex.withLock {
            val outboxNotEmpty = outboxMutex.withLock { pendingOutbox.isNotEmpty() }
            if (outboxNotEmpty) {
                outboxMutex.withLock {
                    pendingOutbox.addLast(OutboxEntry(message, seq, nowMs))
                    // Re-sort defensively — addLast keeps insertion order, but
                    // a future code path that adds entries with smaller
                    // sequenceTs (e.g. a watchdog that re-queues an expired
                    // entry concurrently) would otherwise leave the outbox
                    // unsorted until the next merge. Cheap on small queues.
                    val sorted = pendingOutbox.sortedBy { it.sequenceTs }
                    pendingOutbox.clear()
                    pendingOutbox.addAll(sorted)
                }
                relayLog(
                    RelayLogLevel.INFO,
                    "${genTag()} Deferred to outbox (flush in progress / outbox not drained): id=${message.messageId.take(12)}… seq=$seq",
                )
                return@withLock false
            }
            relayLog(
                RelayLogLevel.INFO,
                "${genTag()} Sending envelope: to=${message.to.take(16)}… id=${message.messageId.take(12)}… seq=$seq payloadBytes=${message.payload.length} sealed=${message.sealedSender.isNotEmpty()}",
            )
            // Track BEFORE the wire write so the ACK watchdog covers the case
            // where sendRaw silently writes into a half-dead socket (no exception
            // on the local buffer, no frame on the wire). The relay will
            // eventually emit its own Ack frame when the envelope reaches it; if
            // that Ack does not arrive within ACK_TIMEOUT_MS the watchdog
            // requeues this entry on a fresh socket. sequenceTs preserved.
            //
            // PR-D1d: arm the per-envelope deadline timer under the same lock
            // so the arm and pendingAcks insertion are atomic. If the relay
            // does not AckDeliver within ACK_DEADLINE_MS the timer emits
            // [outboundAckDeadlineExpired], which the orchestrator forwards to
            // the state machine as Event.ActiveOutboundAckTimeout. This fires
            // on the first bad send, orthogonal to the existing
            // active_outbound_threshold (which requires two session deaths).
            val sentMark = timeSource.markNow()
            val msgId = message.messageId
            val entry = AckPending(message, sentMark, seq, nowMs)
            pendingAcksLock.withLock {
                pendingAcks[msgId] = entry
                armAckDeadlineLocked(entry, logPrefix = genTag())
            }
            val ok = sendRaw(message)
            if (!ok) {
                relayLog(RelayLogLevel.ERROR, "${genTag()} Envelope send returned false (frame write failed) seq=$seq")
                // sendRaw threw and was logged. The frame did not go out, so the
                // pendingAcks entry is meaningless — drop it (helper cancels the
                // deadline timer atomically) and re-enqueue in the outbox with
                // its original sequenceTs (the next reconnect flush will sort it
                // back into encrypt order).
                pendingAcksLock.withLock {
                    removePendingAckLocked(msgId, reason = "send_failed")
                }
                outboxMutex.withLock {
                    pendingOutbox.addLast(OutboxEntry(message, seq, nowMs))
                }
            }
            ok
        }
    }

    override suspend fun sendDeliveryAck(messageId: String): Boolean {
        val msg = RelayMessage.AckDelivery(messageId = messageId)
        if (!isConnected()) {
            // Queue the ack: it MUST eventually reach the relay or the envelope
            // will be redelivered forever. PR-H2a: ack-delivers also get a
            // sequenceTs so they sort into the outbox at the correct position
            // relative to user-message Sends queued in the same window.
            // The outbox is FIFO by sequenceTs, so the next connect drains
            // the ack at its right place in chronological order.
            val seq = nextSequenceTs()
            val nowMs = Clock.System.now().toEpochMilliseconds()
            outboxMutex.withLock {
                pendingOutbox.addLast(OutboxEntry(msg, seq, nowMs))
            }
            relayLog(
                RelayLogLevel.INFO,
                "${genTag()} ack_deliver_send queued until reconnect: messageId=${messageId.take(12)}… seq=$seq",
            )
            return false
        }
        // PR-H1a: explicit ack_deliver_send tag. If the relay's flushing
        // count keeps repeating across reconnects (PR-H1 diagnosis), the
        // ack frames are either not reaching the server, being routed to
        // a stale session, or being lost mid-flight. Tagging both the
        // session and the messageId lets us correlate with server-side
        // ack-deliver removal logs (added in routes.rs in this same PR).
        relayLog(
            RelayLogLevel.INFO,
            "${genTag()} ack_deliver_send messageId=${messageId.take(12)}…",
        )
        val ok = sendRaw(msg)
        if (!ok) {
            relayLog(
                RelayLogLevel.WARN,
                "${genTag()} ack_deliver_send_failed messageId=${messageId.take(12)}… — sendRaw returned false",
            )
        }
        return ok
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
     * Drains the in-memory outbox in strict sequenceTs ASC order after a
     * successful reconnect. Stops at the first sendRaw failure and re-enqueues
     * all unsent items (still preserving sequenceTs) so the next reconnect
     * cycle picks them up in encrypt order.
     *
     * PR-H2a: snapshot is sorted by sequenceTs ASC even though
     * mergeUnackedIntoOutboxOrdered already does this on insert. The
     * defence-in-depth sort here covers any code path that might in the
     * future addLast/addFirst out of order — e.g. a future stage transport
     * (Tor/Reality) that fans an envelope back into the outbox after a
     * route switch. Cheap (O(n log n) on a list that is usually < 50
     * items, run only on reconnect not per-frame).
     */
    private suspend fun flushPendingOutbox(mySession: Long) {
        // PR-H2a.2 (2026-05-13, hotfix on top of merged PR-H2a):
        // outboundSendMutex MUST be held across snapshot+clear AND the
        // entire wire-write loop, as a single critical section. The
        // PR-H2a version held the mutex only across the loop, leaving a
        // window between `pendingOutbox.clear()` (inside outboxMutex)
        // and the subsequent `outboundSendMutex.withLock` entry. During
        // that window:
        //   1. flush has already drained the outbox into `toFlush`
        //   2. live send() can run, see pendingOutbox.isEmpty() == true
        //      under outboxMutex, take outboundSendMutex (it is FREE
        //      because flush has not entered withLock yet), and sendRaw
        //      a fresher sequenceTs straight to the wire
        //   3. flush finally enters its withLock and writes the older
        //      entries — now AFTER the live send on the wire
        // Result: receiver sees [new, old] → MAC fail on old, message
        // lost. Exactly the Test #33 bug, just shifted from the
        // reconnect/merge path to the live-during-flush path.
        // Fix: snapshot+clear inside outboundSendMutex so live send()
        // observes pendingOutbox.isEmpty() == false the whole time
        // flush owns the wire.
        outboundSendMutex.withLock {
            val toFlush = outboxMutex.withLock {
                if (pendingOutbox.isEmpty()) return
                val snapshot = pendingOutbox.toMutableList()
                snapshot.sortBy { it.sequenceTs }
                pendingOutbox.clear()
                snapshot.toList()
            }
            relayLog(
                RelayLogLevel.INFO,
                "${genTag(mySession)} Flushing ${toFlush.size} queued item(s) after reconnect (sorted by sequenceTs ASC)",
            )
            var sentCount = 0
            for (entry in toFlush) {
                val msg = entry.message
                when (msg) {
                    is RelayMessage.Send -> {
                        relayLog(
                            RelayLogLevel.INFO,
                            "${genTag(mySession)} Flush → send envelope: id=${msg.messageId.take(12)}… to=${msg.to.take(16)}… seq=${entry.sequenceTs}",
                        )
                        // PR-F1.1: mirror send() — re-track in pendingAcks BEFORE
                        // sendRaw so the ACK watchdog and the next reconnect's
                        // mergeUnackedIntoOutboxOrdered() can both see this
                        // envelope. Without this, flush was fire-and-forget:
                        // re-flushed envelopes that the relay never acked
                        // silently disappeared from the tracker on the next
                        // reconnect cycle. Test #23 2026-05-12 captured 7 voice
                        // envelopes lost this way.
                        // PR-H2a: preserve sequenceTs across the re-track so a
                        // subsequent merge sorts this envelope back to its
                        // original encrypt-order slot, not after fresh sends.
                        // PR-D1d: arm the deadline timer here too — a flushed
                        // envelope is no different from a fresh send from the
                        // relay's perspective, and Tele2 / similar broken networks
                        // can drop the re-sent frame as easily as the original.
                        // Without arming here, only first-time-send envelopes got
                        // the 10 s deadline benefit; re-sends would silently wait
                        // for the legacy active_outbound_threshold (~40 s) again.
                        val rearmedEntry = AckPending(
                            message = msg,
                            sentAt = timeSource.markNow(),
                            sequenceTs = entry.sequenceTs,
                            queuedAtMs = entry.queuedAtMs,
                        )
                        pendingAcksLock.withLock {
                            pendingAcks[msg.messageId] = rearmedEntry
                            armAckDeadlineLocked(rearmedEntry, logPrefix = genTag(mySession))
                        }
                        relayLog(
                            RelayLogLevel.INFO,
                            "${genTag(mySession)} Flush → tracking pending ACK: id=${msg.messageId.take(12)}… seq=${entry.sequenceTs}",
                        )
                    }
                    is RelayMessage.AckDelivery -> relayLog(
                        RelayLogLevel.INFO,
                        "${genTag(mySession)} Flush → ack_deliver_send (replay): messageId=${msg.messageId.take(12)}… seq=${entry.sequenceTs}",
                    )
                    else -> Unit
                }
                val ok = sendRaw(msg)
                if (!ok) {
                    // Undo the just-added pendingAcks entry — the envelope is
                    // going back to the outbox, it must live in exactly one
                    // place until the next reconnect re-flushes it. Without
                    // this un-track the next mergeUnackedIntoOutboxOrdered()
                    // would also pull it from pendingAcks and we'd have a
                    // duplicate in the outbox (with the same sequenceTs
                    // collision).
                    if (msg is RelayMessage.Send) {
                        // PR-D1d: helper cancels the deadline timer atomically
                        // with the un-track. The flush armed a timer above; if
                        // the wire write failed, the envelope is now going back
                        // to the outbox and its stale timer must not fire.
                        pendingAcksLock.withLock {
                            removePendingAckLocked(msg.messageId, reason = "flush_send_failed")
                        }
                        relayLog(
                            RelayLogLevel.WARN,
                            "${genTag(mySession)} Flush failed → returned to outbox: id=${msg.messageId.take(12)}… seq=${entry.sequenceTs}",
                        )
                    }
                    val remaining = toFlush.size - sentCount
                    relayLog(
                        RelayLogLevel.ERROR,
                        "${genTag(mySession)} Flush failed — re-enqueuing $remaining item(s) for next reconnect (preserving sequenceTs)",
                    )
                    // PR-H2a: re-merge from the failed entry onwards with
                    // whatever already sits in pendingOutbox (a concurrent
                    // send() may have added new entries while we were
                    // flushing), then sort. Don't assume the partial flush
                    // prefix is still correct — the next
                    // mergeUnackedIntoOutboxOrdered on reconnect will
                    // re-merge pendingAcks (the prefix we already moved
                    // there) anyway. The non-local `return` here exits
                    // flushPendingOutbox; outboundSendMutex is released by
                    // the inline withLock's finally.
                    outboxMutex.withLock {
                        val merged = ArrayList<OutboxEntry>(
                            pendingOutbox.size + (toFlush.size - sentCount),
                        )
                        merged.addAll(pendingOutbox)
                        merged.addAll(toFlush.subList(sentCount, toFlush.size))
                        merged.sortBy { it.sequenceTs }
                        pendingOutbox.clear()
                        pendingOutbox.addAll(merged)
                    }
                    return
                }
                sentCount++
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
                "${genTag()} sendRaw failed (${e::class.simpleName}): ${e.message}",
                e,
            )
            false
        }
    }

    override suspend fun disconnect() {
        relayLog(RelayLogLevel.INFO, "${genTag()} disconnect() called")
        disconnectRequested = true
        // Best-effort flush of any pending outbox items before tearing down the
        // scope. Bounded to 3 s so disconnect never blocks indefinitely.
        withTimeoutOrNull(3_000L) {
            if (session != null) flushPendingOutbox(wsSessionEpoch)
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

    // PR-H1c: ANY inbound frame counts. Updated in readLoop (instance-level
    // @Volatile field, distinct from the per-session SessionStats counter).
    // Used by the diagnostic log in PhantomWakeupReceiver (PR-R0.4a) and the
    // passive idle_watchdog log in startIdleWatchdog (PR-R0.4b).
    override val lastInboundFrameElapsedMs: Long
        get() = lastInboundFrameMark.elapsedNow().inWholeMilliseconds

    // Lock-free read against pendingAcks. The map is mutated only under
    // pendingAcksLock, but .size is a single primitive int field on the
    // backing HashMap and racing the read against put/remove cannot return
    // a torn value — at worst the count is off by one mid-mutation, which
    // does not change the wakeup gate decision (the gate trips on > 0).
    override val pendingAckCount: Int
        get() = pendingAcks.size

    override suspend fun forceReconnect() {
        // PR-F2: capture the generation we observed BEFORE acquiring the
        // lock. Three watchdogs (in-process pong, in-process ACK, external
        // AlarmManager wakeup) can all call forceReconnect within the same
        // 50 ms window — without coalescing, each one launched its own new
        // reconnect loop and the relay saw N parallel `event="connect"`
        // for the same identity (Test #26 captured 5 in 30 ms). With the
        // gen check below, the first caller through the lock advances the
        // generation; subsequent callers see their entry-gen is now stale
        // and skip the relaunch — at most one fresh loop per burst.
        val entryGen = connectionGeneration
        connectionLifecycleMutex.withLock {
            if (entryGen != connectionGeneration) {
                relayLog(
                    RelayLogLevel.INFO,
                    "${genTag()} forceReconnect: stale (entry gen=$entryGen, current=$connectionGeneration) — another caller already relaunched, skipping",
                )
                return
            }
            relayLog(
                RelayLogLevel.WARN,
                "${genTag()} forceReconnect() called — abandoning current reconnect loop and launching a fresh one (ADR-013 hard reset, gen=$entryGen→${entryGen + 1})",
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
            connectionGeneration += 1
            // Best-effort: ask the old job to cancel after the new one has
            // started (so any read-loop epilogue can drain queued frames),
            // but do not wait for it — it may never complete.
            oldJob?.cancel()
            relayLog(
                RelayLogLevel.INFO,
                "${genTag()} forceReconnect: new reconnect loop launched; previous loop abandoned (may remain as zombie thread)",
            )
        }
    }

    // ─── PR-H2a test-only hooks ───────────────────────────────────────────
    // These accessors exist solely to let KtorRelayTransportFifoTest verify
    // that the strict-FIFO outbox guarantees actually hold under simulated
    // disconnect/reconnect. They are `internal` so production code in
    // sibling modules cannot reach them, and they only expose snapshots /
    // controlled mutations — the underlying mutable collections are never
    // returned. Removing these is safe; only the test will fail to build.

    // ── PR-D1c: REST fallback migration of pending WS outbound ──────────────
    //
    // Test #49 (2026-05-16, Tele2 LTE Иркутская) reproduced an X3DH bootstrap
    // ordering bug: the very first outbound envelope after onboarding is a
    // bootstrap message carrying the x3dhInit header. If WS delivers the
    // frame on the wire but the relay's Ack never makes it back, that
    // envelope sits in [pendingAcks]. The state machine then switches into
    // RestActive and the NEXT send() routes through REST. Receiver sees the
    // second envelope without ever having seen the bootstrap, drops it as a
    // "legacy envelope: no session and no x3dhInit", and decrypt fails for
    // good. The fix lives one layer up: [HybridRelayTransport] snapshots
    // every pending outbound here and re-sends it over REST in sequenceTs
    // order BEFORE any new REST send is allowed through. The two methods
    // below are the read-side and remove-side of that contract — both
    // public because the wrapper lives in a different Gradle module
    // (`apps/android`, not `shared/core/transport`), so `internal` is
    // unreachable.

    /**
     * Immutable snapshot of one outbound envelope that has not yet been
     * confirmed ACK'd by the relay over WS. Returned by
     * [snapshotPendingOutbound] for [HybridRelayTransport] to re-send over
     * REST when the state machine switches WS → REST. `payloadBase64` and
     * `sealedSenderBase64` are the original WS ciphertext blobs — re-sending
     * requires no re-encrypt and no ratchet advance.
     *
     * `sequenceTs` is the per-transport monotone counter (NOT wall-clock).
     * It exists only to define a stable sort order for the migration; the
     * REST orchestrator passes its own wall-clock `sequence_ts` to the relay.
     */
    data class PendingOutboundEnvelope(
        val id: String,
        val to: String,
        val payloadBase64: String,
        val sealedSenderBase64: String,
        val sequenceTs: Long,
    )

    /**
     * Snapshot of all outbound envelopes that are either (a) on the wire
     * awaiting an ACK ([pendingAcks]) or (b) queued for the next live WS
     * session ([pendingOutbox]). Returned as an immutable list sorted by
     * [PendingOutboundEnvelope.sequenceTs] ASC so the caller re-sends in the
     * exact order the application originally produced — critical for X3DH
     * bootstrap to land before any session-existing follow-up.
     *
     * If the same id appears in both maps (a transient state the
     * flush/merge paths can briefly produce while moving an entry across)
     * it is included once.
     *
     * Atomicity (PR-D1c review round 3 fix): three nested locks —
     * `outboundSendMutex` → `pendingAcksLock` → `outboxMutex`. The
     * outermost `outboundSendMutex` is the key fix: without it,
     * [flushPendingOutbox] has a window where it has cleared
     * `pendingOutbox` and put envelopes into a local `toFlush` list, but
     * has not yet inserted them into `pendingAcks`. During that window a
     * snapshot that only takes the two inner locks would see those
     * envelopes nowhere and silently drop them from migration. Since
     * [flushPendingOutbox] holds `outboundSendMutex` for its entire
     * snapshot+clear+wire-write+re-track cycle (PR-H2a.2), taking
     * `outboundSendMutex` here makes the snapshot wait for the flush to
     * settle before reading. The two inner lock acquisitions match the
     * order used by [mergeUnackedIntoOutboxOrdered] and the ACK
     * watchdog, so no inversion deadlock is possible.
     */
    suspend fun snapshotPendingOutbound(): List<PendingOutboundEnvelope> {
        return outboundSendMutex.withLock {
            pendingAcksLock.withLock {
                outboxMutex.withLock {
                    val seen = HashSet<String>(pendingAcks.size + pendingOutbox.size)
                    val merged = ArrayList<PendingOutboundEnvelope>(
                        pendingAcks.size + pendingOutbox.size
                    )
                    for (entry in pendingAcks.values) {
                        val msg = entry.message
                        if (seen.add(msg.messageId)) {
                            merged.add(
                                PendingOutboundEnvelope(
                                    id = msg.messageId,
                                    to = msg.to,
                                    payloadBase64 = msg.payload,
                                    sealedSenderBase64 = msg.sealedSender,
                                    sequenceTs = entry.sequenceTs,
                                )
                            )
                        }
                    }
                    for (entry in pendingOutbox) {
                        val send = entry.message as? RelayMessage.Send ?: continue
                        if (seen.add(send.messageId)) {
                            merged.add(
                                PendingOutboundEnvelope(
                                    id = send.messageId,
                                    to = send.to,
                                    payloadBase64 = send.payload,
                                    sealedSenderBase64 = send.sealedSender,
                                    sequenceTs = entry.sequenceTs,
                                )
                            )
                        }
                    }
                    merged.sortedBy { it.sequenceTs }
                }
            }
        }
    }

    /**
     * Atomically remove an envelope from BOTH [pendingAcks] and
     * [pendingOutbox]. Called by [HybridRelayTransport] AFTER the REST
     * orchestrator confirms the envelope was accepted (status 201 or 200).
     *
     * Atomicity across both maps AND across an in-progress
     * [flushPendingOutbox] is load-bearing. Without [outboundSendMutex]
     * the flush window (envelope in local `toFlush`, not in any map) would
     * let mark return as a no-op while the flush then inserts the envelope
     * into `pendingAcks`. A subsequent WS reconnect would re-flush via
     * `sendRaw` and the relay would live-deliver a duplicate of an
     * envelope the recipient has already received via REST.
     *
     * Lock order (PR-D1c review round 3 fix): `outboundSendMutex` →
     * `pendingAcksLock` → `outboxMutex`. Matches [snapshotPendingOutbound]
     * and the rest of the file.
     *
     * Safe to call when [id] is in neither map — no-op in that case.
     */
    suspend fun markPendingOutboundAcceptedByFallback(id: String) {
        outboundSendMutex.withLock {
            pendingAcksLock.withLock {
                // PR-D1d: helper drops the envelope AND cancels its deadline
                // timer atomically. The envelope is now owned by REST; the
                // WS-plane timer is moot.
                removePendingAckLocked(id, reason = "migrated_to_rest")
                outboxMutex.withLock {
                    val it = pendingOutbox.iterator()
                    while (it.hasNext()) {
                        val entry = it.next()
                        val send = entry.message as? RelayMessage.Send ?: continue
                        if (send.messageId == id) it.remove()
                    }
                }
            }
        }
    }

    internal suspend fun snapshotOutboxForTest(): List<OutboxEntry> =
        outboxMutex.withLock { pendingOutbox.toList() }

    internal suspend fun snapshotPendingAcksForTest(): List<AckPending> =
        pendingAcksLock.withLock { pendingAcks.values.toList() }

    internal suspend fun seedPendingAckForTest(entry: AckPending) {
        pendingAcksLock.withLock { pendingAcks[entry.message.messageId] = entry }
    }

    internal suspend fun seedOutboxForTest(entry: OutboxEntry) {
        outboxMutex.withLock { pendingOutbox.addLast(entry) }
    }

    internal suspend fun nextSequenceTsForTest(): Long = nextSequenceTs()

    /**
     * PR-H2a.1 test hook. Lets KtorRelayTransportFifoTest exercise the
     * live-send path (the one that races flushes) without standing up a
     * Ktor MockEngine WebSocket. Real production code never sets state
     * to Connected without going through connect()'s WS handshake; this
     * method is `internal` so production callers in sibling modules
     * cannot reach it.
     */
    internal fun setStateConnectedForTest() {
        _state.value = TransportState.Connected
    }

    /**
     * PR-D1d test hook: arm an ACK deadline timer for [entry] using whatever
     * scope is in [ackDeadlineScopeOverride] (set to a TestScope by the test
     * before calling this). This lets deadline tests seed both pendingAcks and
     * the corresponding timer without going through the real send() path (which
     * requires a live WebSocket session).
     *
     * Must only be called from tests. Production code uses [send] which arms
     * the timer under the lock atomically with the pendingAcks insertion.
     */
    internal suspend fun armAckDeadlineForTest(entry: AckPending) {
        pendingAcksLock.withLock {
            pendingAcks[entry.message.messageId] = entry
            armAckDeadlineLocked(entry, logPrefix = "[test]")
        }
    }

    /**
     * PR-D1d test hook: simulate relay AckDeliver for [msgId], removing the
     * entry from pendingAcks and cancelling the deadline timer. Mirrors the
     * production ACK path in readLoop without requiring a live connection.
     */
    internal suspend fun simulateAckReceivedForTest(msgId: String) {
        pendingAcksLock.withLock {
            removePendingAckLocked(msgId, reason = "ack_received")
        }
    }
}
