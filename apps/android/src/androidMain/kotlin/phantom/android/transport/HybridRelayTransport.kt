// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import phantom.core.storage.ProcessedEnvelopeRepository
import phantom.core.transport.AckOutcome
import phantom.core.transport.KtorRelayTransport
import phantom.core.transport.PollEnvelope
import phantom.core.transport.RelayMessage
import phantom.core.transport.RelayTransport
import phantom.core.transport.RestFallbackOrchestrator
import phantom.core.transport.RestInboundDeduplicator
import phantom.core.transport.RestMode
import phantom.core.transport.RestStateMachine
import phantom.core.transport.SendOutcome
import phantom.core.transport.SessionSignalSubscription
import phantom.core.transport.TransportKind
import phantom.core.transport.TransportState
import phantom.core.transport.WsDegradationDetector
import phantom.core.transport.WsSessionEndedEvent
import phantom.core.transport.WsSessionLifecycleEvent
import phantom.core.transport.WsSessionSignal
import phantom.core.transport.epochOrUnknown

/**
 * PR-D1b (2026-05-16): wraps the existing [KtorRelayTransport] (WS path)
 * and the [RestFallbackOrchestrator] (REST short-poll fallback) behind the
 * same [RelayTransport] interface that the rest of the app consumes.
 *
 * **Why this lives in the Android app layer (not commonMain transport):**
 * the wrapper needs to dedup REST-sourced envelopes against
 * [ProcessedEnvelopeRepository], which lives in `shared/core/storage`.
 * Pulling that dependency into `shared/core/transport` would invert the
 * existing module graph (transport currently does not know about storage).
 * Keeping the wrapper at the app layer preserves the boundary.
 *
 * **Routing rules** (locked 2026-05-16, contract-reviewed by Vladislav):
 *
 * - Outbound `send(...)`:
 *     - [RestMode.WsActive] → delegate to `wsTransport.send(...)` (WS path
 *       unchanged from pre-D1b behaviour).
 *     - [RestMode.RestActive] OR [RestMode.WsCandidate] → route via
 *       `orchestrator.sendEnvelope(...)` (REST POST `/relay/send`).
 *   On `SendOutcome.DisabledByCapability` (relay does not advertise
 *   `rest_fallback=true`) we fall back to `wsTransport.send(...)` — that is
 *   the inert-passthrough mode that keeps the app behaving exactly like
 *   pre-D1b against old relays.
 *
 * - Inbound `incoming` flow (WS passthrough is ALWAYS active, regardless of
 *   REST capability — DMS must keep receiving WS frames even when bootstrap
 *   failed or the relay is on an old build):
 *     - Frame.Text from WS → forwarded verbatim into the merged flow and,
 *       if REST capability is on, fed into the state machine as
 *       `WsFrameTextReceived`.
 *     - `PollEnvelope` from REST poll → 3-layer dedup check:
 *         1. **Persistent ledger** ([ProcessedEnvelopeRepository]) — WS-then-REST
 *            race where DMS already processed the envelope. On hit, ack and drop.
 *         2. **In-memory tracker** ([RestInboundDeduplicator]) returns one of:
 *             - [RestInboundDeduplicator.Action.Emit] — first time we've seen this id;
 *               translate to [RelayMessage.Deliver] and emit, mark `pendingAck`.
 *             - [RestInboundDeduplicator.Action.SkipNoAck] — duplicate while DMS is
 *               still processing the previous emission. Do NOT emit and do NOT
 *               ack — the envelope may not yet be persisted, and acking now
 *               would let the relay drop it before DMS commits to storage.
 *             - [RestInboundDeduplicator.Action.ReAck] — duplicate after DMS already
 *               called `sendDeliveryAck` (i.e. envelope is durably stored, but
 *               the relay never observed the ack). Safe to re-ack and drop.
 *   This is the ACK-after-persistence discipline locked in the
 *   2026-05-16 contract review.
 *
 * - `sendDeliveryAck(messageId)`:
 *     - If the id is currently tracked in the REST dedup tracker, route the
 *       ack to `orchestrator.ackInbound(...)` AND call `markAcknowledged()`
 *       on the tracker so subsequent duplicates flip to `ReAck`.
 *     - Else delegate to `wsTransport.sendDeliveryAck(messageId)`.
 *
 * **State-machine feeds** (Stage 2, 2026-09-13): ONE ordered stream.
 * [startWsPassthroughCollectors] attaches the transport's single
 * [phantom.core.transport.SessionSignalSubscription] and runs exactly one
 * consumer that maps every [phantom.core.transport.WsSessionSignal]
 * (Connected / Ended / Activity / Stalled / AckDeadlineExpired /
 * Invalidated) to exactly one [RestStateMachine.Event] carrying the same
 * session id. `wsTransport.incoming` is only forwarded into `_incoming`;
 * it no longer feeds the machine. This instance OWNS the subscription
 * and every job it launches, and [closeAndJoin] releases all of them.
 *
 * `NetworkChanged` events are deliberately NOT wired in D1b
 * (deferred to PR-D1d).
 *
 * **Capability gate.** [bootstrapAndStart] starts WS-passthrough collectors
 * unconditionally so DMS still sees inbound messages even when the REST
 * capability is unavailable. If the relay does not advertise
 * `rest_fallback=true`, no REST traffic ever fires; on every subsequent
 * `WsSessionEnded` event the bootstrap is retried (rate-limited) so a
 * transient session-failure does not permanently disable REST fallback.
 *
 * **PR-D1c (2026-05-16) pending WS outbox migration.** Test #49 on Tele2
 * LTE Иркутская reproduced an X3DH ordering bug: the first envelope was a
 * bootstrap (with x3dhInit) put on the WS wire; the relay's Ack got
 * silently dropped by the Tele2 middlebox; the state machine switched
 * WS → REST; the NEXT send (`session_existing`, no x3dhInit) went via
 * REST 201; the recipient dropped it as a "legacy envelope: no session".
 * The fix: every WS → RestActive transition arms a migration job that
 * snapshots [KtorRelayTransport.snapshotPendingOutbound], re-sends every
 * pending envelope via [orchestrator.sendEnvelope] (server is idempotent
 * by `Idempotency-Key = envelope_id` since PR-D0r), and calls
 * [KtorRelayTransport.markPendingOutboundAcceptedByFallback] on success.
 * New REST sends suspend on [restOutboundOrderMutex] AND
 * [pendingMigration] until the migration completes, so bootstrap-first
 * encrypt-time order is preserved.
 *
 * **Thread safety.** Every event submission -- this instance's consumer
 * and, through [RestFallbackOrchestrator.eventRouter], the orchestrator's
 * own producers -- goes through [stateMachineLock]. The WS → REST
 * transition is returned by the machine as a
 * [RestStateMachine.Transition] and migration is armed (both
 * [migrationJob] and [pendingMigration] written in that order) under the
 * same lock so a concurrent [send] that sees the new mode is guaranteed
 * to see the migration in flight and suspend on
 * [awaitPendingMigrationIfNeeded].
 */

/**
 * R3.6 (2026-06-20): mapper from the ordered lifecycle stream event
 * [WsSessionLifecycleEvent.Ended] to the state-machine-facing
 * [RestStateMachine.Event.WsSessionEnded].
 *
 * Carries all legacy fields PLUS [WsSessionLifecycleEvent.Ended.sessionEpoch]
 * so the sticky-recovery epoch filter in [RestStateMachine.onWsSessionEnded]
 * can distinguish the active recovery candidate's close from stale closes of
 * older sessions.
 *
 * Unit-tested in `apps/android/src/androidUnitTest/.../HybridRelayTransportMapperTest.kt`
 * (L8/L9 cases): `sessionEpoch`, `okhttpPingTimeoutDetected`, `durationMs`,
 * `inboundFrames`, `pendingAcksAtClose` all survive the boundary unchanged.
 */
internal fun WsSessionLifecycleEvent.Ended.toRestStateMachineEvent(): RestStateMachine.Event.WsSessionEnded =
    RestStateMachine.Event.WsSessionEnded(
        durationMs = durationMs,
        inboundFrames = inboundFrames,
        pendingAcksAtClose = pendingAcksAtClose,
        okhttpPingTimeoutDetected = okhttpPingTimeoutDetected,
        sessionEpoch = sessionEpoch,
    )

/**
 * Legacy mapper kept for [WsDegradationCollectorBindings] compatibility.
 * The degradation detector still takes [WsSessionEndedEvent] parameters
 * directly; the lifecycle channel's [WsSessionLifecycleEvent.Ended] is
 * converted here for the collector's telemetry path.
 */
internal fun WsSessionLifecycleEvent.Ended.toLegacyEndedEvent(): WsSessionEndedEvent =
    WsSessionEndedEvent(
        durationMs = durationMs,
        inboundFrames = inboundFrames,
        pendingAcksAtClose = pendingAcksAtClose,
        closeOrigin = closeOrigin,
        closeError = closeError,
        okhttpPingTimeoutDetected = okhttpPingTimeoutDetected,
    )

/**
 * Legacy compatibility: kept so the existing [WsDegradationCollectorBindingsTest]
 * call sites (which use [WsSessionEndedEvent] directly) continue to compile.
 * The production [HybridRelayTransport] collector now consumes [WsSessionLifecycleEvent]
 * from the ordered channel and uses [WsSessionLifecycleEvent.Ended.toRestStateMachineEvent].
 *
 * @deprecated Prefer [WsSessionLifecycleEvent.Ended.toRestStateMachineEvent].
 */
internal fun WsSessionEndedEvent.toRestStateMachineEvent(): RestStateMachine.Event.WsSessionEnded =
    RestStateMachine.Event.WsSessionEnded(
        durationMs = durationMs,
        inboundFrames = inboundFrames,
        pendingAcksAtClose = pendingAcksAtClose,
        okhttpPingTimeoutDetected = okhttpPingTimeoutDetected,
        // Legacy path has no epoch — use sentinel -1L which never matches a live
        // recoveryWsEpoch (valid epochs start at 1).
        sessionEpoch = -1L,
    )

class HybridRelayTransport(
    private val wsTransport: KtorRelayTransport,
    private val orchestrator: RestFallbackOrchestrator,
    private val processedEnvelopeRepository: ProcessedEnvelopeRepository?,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    // PR-WS-HEALTH-STATE1 Commit 3.2a (2026-06-01): telemetry-only WS
    // degradation detector. Nullable so existing JVM/commonTest
    // constructions stay backward-compatible. When non-null, the WS
    // lifecycle collector below feeds [WsDegradationDetector] AFTER
    // its [submitStateEvent] call per design note §8 step 4.
    //
    // The lifecycle dispatcher's `Ended` branch ALWAYS feeds the
    // `WS_DEGRADED_TELEMETRY session_total` line per close, regardless
    // of [restCapabilityActive], so the denominator for calibration
    // ratios is honest from cold start (per design note rev2 P2-3).
    //
    // `degradationCurrentKindProvider` is wired by [AppContainer] to
    // `(transportManager.state.value as? ManagerState.Connected)?.kind`,
    // injected here as a lambda so the Hybrid does not depend on
    // [TransportManager] or [ManagerState] types directly.
    private val wsDegradationDetector: WsDegradationDetector? = null,
    private val degradationCurrentKindProvider: () -> TransportKind? = { null },
) : RelayTransport, RewalkHybridFacade {

    // ── Delegated RelayTransport surface ─────────────────────────────────────

    override val state: StateFlow<TransportState> get() = wsTransport.state
    override val acks: Flow<RelayMessage.Ack> get() = wsTransport.acks
    override val typingEvents: SharedFlow<String> get() = wsTransport.typingEvents
    override val lastPongElapsedMs: Long get() = wsTransport.lastPongElapsedMs
    override val lastInboundFrameElapsedMs: Long get() = wsTransport.lastInboundFrameElapsedMs
    override val pendingAckCount: Int get() = wsTransport.pendingAckCount

    override fun isConnected(): Boolean = wsTransport.isConnected()

    override suspend fun connect(
        relayUrl: String,
        identityPublicKeyHex: String,
        signingPublicKeyHex: String,
        signChallenge: suspend (challenge: ByteArray) -> ByteArray?,
        socksProxyPort: Int?,
    ) = wsTransport.connect(
        relayUrl = relayUrl,
        identityPublicKeyHex = identityPublicKeyHex,
        signingPublicKeyHex = signingPublicKeyHex,
        signChallenge = signChallenge,
        socksProxyPort = socksProxyPort,
    )

    override suspend fun disconnect() = wsTransport.disconnect()

    /**
     * RC-RECONNECT-QUIESCENCE1 MC-2 (2026-07-01) — pass-through to
     * [wsTransport]. The inner [KtorRelayTransport] owns the reconnect
     * loop and the `NonCancellable` cleanup discipline; the hybrid
     * wrapper has no additional teardown of its own beyond the WS
     * path. The REST-side state machine + orchestrator do not need
     * disconnect-and-join handling because they run inside the
     * caller's coroutine scope and stop when that scope cancels.
     *
     * Bounded-join contract is delegated to [KtorRelayTransport.disconnectAndJoin]:
     * returns `true` if the inner reconnect job exits within
     * [timeoutMs]; `false` if the timeout expires and the inner ref
     * is retained for a subsequent call to re-await.
     */
    override suspend fun disconnectAndJoin(timeoutMs: Long): Boolean =
        wsTransport.disconnectAndJoin(timeoutMs, reason = "teardown")

    /**
     * Stage 2 B11: the reason reaches the transport so the session
     * invalidation it enqueues before closing the socket says WHY -- a
     * privacy teardown and a network rewalk are different facts and the
     * state machine's trace should not conflate them.
     */
    override suspend fun disconnectAndJoin(timeoutMs: Long, reason: String): Boolean =
        wsTransport.disconnectAndJoin(timeoutMs, reason)

    override val teardownIdentity: Long get() = wsTransport.teardownIdentity

    override suspend fun disconnectAndConfirm(
        timeoutMs: Long,
        onlyIfIdentity: Long?,
    ): phantom.core.transport.TransportTeardownResult =
        wsTransport.disconnectAndConfirm(timeoutMs, onlyIfIdentity, reason = "teardown")

    override suspend fun disconnectAndConfirm(
        timeoutMs: Long,
        onlyIfIdentity: Long?,
        reason: String,
    ): phantom.core.transport.TransportTeardownResult =
        wsTransport.disconnectAndConfirm(timeoutMs, onlyIfIdentity, reason)

    override suspend fun forceReconnect() = wsTransport.forceReconnect()

    override suspend fun sendTyping(toPubKeyHex: String): Boolean =
        wsTransport.sendTyping(toPubKeyHex)

    // ── Merged inbound flow ──────────────────────────────────────────────────

    private val _incoming = MutableSharedFlow<RelayMessage.Deliver>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val incoming: Flow<RelayMessage.Deliver> = _incoming.asSharedFlow()

    // ── REST routing bookkeeping ─────────────────────────────────────────────

    /**
     * Tracks envelope ids that arrived via REST poll and were emitted into
     * [_incoming]. The tracker distinguishes three states per id (Emit /
     * SkipNoAck / ReAck) so we never ack an envelope DMS is still in the
     * middle of decrypt-and-persisting (see class kdoc).
     */
    private val restDedup = RestInboundDeduplicator(nowMs = nowMs)

    /**
     * Stage 2 B4: the atomic boundary `event -> RestMode change -> migration
     * arming`. Every producer takes it: the single session-signal consumer
     * below and, through [RestFallbackOrchestrator.eventRouter], the
     * orchestrator's own producers (the candidate tick, `RestPollDegraded`).
     * Lock order, fixed: `stateMachineLock -> RestStateMachine.eventMutex ->
     * gateLock`; the reverse is forbidden and [lockTraceForTest] lets a
     * fixture prove the nesting.
     */
    private val stateMachineLock = Mutex()

    /**
     * Stage 2 test seam (row 24): observes `stateMachineLock` acquire /
     * release so a lock-order fixture can assert the nesting together
     * with [RestStateMachine.lockTraceForTest]. Null in production.
     */
    @Volatile internal var lockTraceForTest: ((String) -> Unit)? = null

    /**
     * PR-WS-HEALTH-STATE1 Commit 3.2a (architect P2-1, 2026-06-01): the
     * signal consumer and its telemetry feeds run on [scope]
     * (`Dispatchers.Default`); the dispatcher's Ended branch and the
     * stall / deadline branches are separate call sites. The
     * detector is documented as "not thread-safe" — it owns a mutable
     * `ArrayDeque`, session counters, and rising-edge boolean flags. Without
     * this lock, parallel `recordAndEmit` / `emitSessionTotal` calls from
     * different collectors would race the deque + counters and could miss
     * or duplicate rising-edge log lines.
     *
     * `emitStateTransitionSeen` is intentionally NOT wrapped in this lock —
     * it is a pure pass-through to the log function and mutates no detector
     * state.
     */
    private val wsDegradationMutex = Mutex()

    // ── REST capability + retry ──────────────────────────────────────────────

    /**
     * `true` once [bootstrapAndStart] sees `rest_fallback=true` from the
     * relay and starts the REST collectors + orchestrator. Stays `false`
     * against pre-D0r relays or while bootstrap is still being retried.
     */
    @Volatile private var restCapabilityActive: Boolean = false

    /**
     * Guards [maybeRetryBootstrap] so only one retry runs at a time.
     */
    private val bootstrapRetryLock = Mutex()

    /**
     * Trek 2 Stage 2B-B (C4 review-fix round 3 P1.2) — serialises
     * [activateRestCollectors] so a concurrent call from
     * [bootstrapAndStart] and [maybeRetryBootstrap] cannot both pass
     * the `restCapabilityActive` check, launch two inbound
     * collectors, and call `orchestrator.start()` twice. The check
     * is now re-evaluated UNDER the mutex; rollback of a partial
     * launch is also serialised here.
     */
    private val restActivationMutex = Mutex()

    /**
     * Wall-clock ms of the last bootstrap attempt (success or fail). Used by
     * [maybeRetryBootstrap] for rate-limiting — we do not want to slam the
     * relay with `/auth/session` POSTs every time a WS session flaps on
     * Tele2 LTE.
     */
    @Volatile private var lastBootstrapAttemptMs: Long = 0L

    /**
     * Stage 2 B1: every job this instance launches is kept, so
     * [closeAndJoin] can cancel and join all of them. Before Stage 2 the
     * WS collectors were launched on [scope] without handles and a Hybrid
     * abandoned after a failed initialisation kept consuming the
     * transport's flows for the life of the process.
     */
    private var signalSubscription: SessionSignalSubscription? = null
    private var signalConsumerJob: Job? = null
    private var wsInboundForwardJob: Job? = null
    private var restInboundJob: Job? = null

    /**
     * Serialises the two lifecycle transitions of this instance -- the
     * attach-and-launch, and the close -- so neither can observe the other
     * half-done.
     */
    private val lifecycleMutex = Mutex()

    /** Set by [closeAndJoin]; a closed instance refuses to start anything. */
    @Volatile private var closed: Boolean = false

    /**
     * Idempotence guard for [startWsPassthroughCollectors]. The current
     * caller ([bootstrapAndStart]) only fires once, but the comment-level
     * contract claims idempotency and a future retry/refactor must not
     * accidentally spawn a second WS inbound collector — that would cause
     * duplicate delivery into `_incoming` and double-feed the state machine.
     */
    @Volatile private var wsPassthroughStarted: Boolean = false

    // ── PR-D1c: pending WS outbox migration to REST ──────────────────────────

    /**
     * Serialises outbound REST traffic around a WS → REST mode switch. The
     * migration coroutine holds this mutex while it re-sends every pending
     * envelope via REST; new [sendViaRest] calls suspend on the same mutex
     * so the migration's bootstrap-first ordering is preserved. Without
     * this gate, a fresh send issued the instant the mode flips could jump
     * ahead of an unmigrated X3DH bootstrap envelope and the receiver
     * would decrypt a `session_existing` ciphertext without ever seeing
     * the bootstrap (Test #49 root cause).
     */
    private val restOutboundOrderMutex = Mutex()

    /**
     * Set TRUE the instant a WS → RestActive transition is detected
     * (synchronously inside [stateMachineLock]) and cleared FALSE after
     * [migratePendingWsToRest] returns. Used by
     * [awaitPendingMigrationIfNeeded] in [send] so a new outbound that
     * raced the mode switch suspends on the migration job BEFORE entering
     * [restOutboundOrderMutex].
     *
     * Write order under [stateMachineLock] is load-bearing:
     * [migrationJob] is assigned FIRST, [pendingMigration] is set true
     * SECOND. A reader that volatile-reads [pendingMigration] = true is
     * therefore guaranteed (via JMM happens-before on volatile writes) to
     * see [migrationJob] as non-null in the subsequent read.
     */
    @Volatile private var pendingMigration: Boolean = false

    /**
     * Handle to the migration coroutine. Assigned inside the same
     * [stateMachineLock] critical section that flips [pendingMigration]
     * true, so a concurrent [send] caller that observes
     * [pendingMigration] = true is guaranteed to see a non-null ref here.
     */
    @Volatile private var migrationJob: Job? = null

    // ── State machine accessor ───────────────────────────────────────────────

    /**
     * Exposed so observers ([phantom.android.service.PhantomMessagingService]
     * notification updater) can render an honest "Online via REST fallback"
     * UI label when the state machine is in [RestMode.RestActive].
     */
    val stateMachine: RestStateMachine get() = orchestrator.stateMachine

    /** Convenience accessor for the underlying orchestrator's capabilities. */
    val capabilities get() = orchestrator.capabilities

    // ── Bootstrap + wiring ───────────────────────────────────────────────────

    /**
     * Called once from [phantom.android.di.AppContainer.initMessaging] after
     * the identity and signing keypair are available. Idempotent at the
     * caller's level — AppContainer wraps this in `runCatching` so a failed
     * bootstrap (e.g. the relay is unreachable at app start) does NOT prevent
     * the WS path from functioning.
     *
     * Sequence:
     *  1. Start WS-passthrough collectors UNCONDITIONALLY. These forward
     *     `wsTransport.incoming` into `_incoming` so DMS keeps receiving
     *     messages even if REST fallback never activates. They also feed the
     *     state machine, but the [submitStateEvent] helper short-circuits
     *     when REST is inactive so we don't churn the machine for nothing.
     *  2. Call `orchestrator.bootstrap()` — attempts `POST /auth/session`
     *     against the relay and reads the capability fields.
     *  3. If `restFallback == false` (old relay, or auth-session failed),
     *     keep the WS-passthrough collectors running but do not start the
     *     orchestrator's poll loop or any REST-specific observers.
     *  4. Otherwise start the orchestrator + REST collectors.
     */
    suspend fun bootstrapAndStart() {
        // (1) WS passthrough — ALWAYS, regardless of REST capability.
        startWsPassthroughCollectors()

        // (2) Attempt bootstrap.
        val caps = orchestrator.bootstrap()
        lastBootstrapAttemptMs = nowMs()
        Log.i(TAG, "REST_TRACE bootstrap_ok capability=${caps.restFallback}")
        if (!caps.restFallback) {
            // (3) Stay in WS-passthrough mode. A future WsSessionEnded
            // event may trigger maybeRetryBootstrap.
            return
        }
        // (4) REST is available — start orchestrator + REST-specific
        // observers + state-machine feeds.
        activateRestCollectors()
    }

    /**
     * Stage 2 B1: attach the transport's single session-signal subscription
     * and launch exactly one consumer of it, plus the inbound-envelope
     * forwarder. Idempotent per instance ([wsPassthroughStarted]); a second
     * Hybrid against the same transport fails loudly inside
     * [KtorRelayTransport.attachSessionSignalConsumer] instead of silently
     * stealing signals.
     *
     * The consumer maps each signal to exactly one [RestStateMachine.Event]
     * carrying the same session id and submits it through
     * [submitStateEvent]. The frame / ack collectors that used to submit
     * untagged events from `wsTransport.incoming` / `acks` are gone: the
     * forwarder below only moves envelopes into [_incoming].
     */
    internal suspend fun startWsPassthroughCollectors() = lifecycleMutex.withLock {
        // Review round 7 (2026-09-13): closed is checked FIRST, and the
        // started flag is published only after the attach and both launches
        // have succeeded.
        //
        // The first shape set the flag before attaching, so a Hybrid whose
        // attach was refused -- the expected outcome for a second instance
        // against the same transport -- was left permanently "started" with
        // nothing running, and a later retry returned silently. It also
        // returned silently after `closeAndJoin`, because the flag was still
        // set from the first run. Both are now loud.
        check(!closed) { "HybridRelayTransport is closed" }
        if (wsPassthroughStarted) return@withLock
        android.util.Log.i(
            "PhantomMessaging",
            "RECV_DIAG ws_passthrough_started",
        )
        val subscription = wsTransport.attachSessionSignalConsumer()
        var consumer: Job? = null
        var forwarder: Job? = null
        try {
            consumer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                subscription.signals.collect { signal -> consumeSessionSignal(signal) }
            }
            forwarder = scope.launch {
                wsTransport.incoming.collect { deliver ->
                    // PR-RECV-DIAG1 v1.2 — log every WS frame BEFORE it crosses
                    // into the messaging-service's _incoming SharedFlow.
                    android.util.Log.i(
                        "PhantomMessaging",
                        "RECV_DIAG ws_deliver_in id=${deliver.messageId.take(8)} " +
                            "sealed=${deliver.sealedSender.isNotEmpty()} " +
                            "payloadBytes=${deliver.payload.length}",
                    )
                    _incoming.emit(deliver)
                }
            }
        } catch (t: Throwable) {
            // Roll the whole stage back: a half-launched attach must not
            // hold the transport's only consumer slot.
            withContext(NonCancellable) {
                consumer?.cancel()
                forwarder?.cancel()
                runCatching { consumer?.join() }
                runCatching { forwarder?.join() }
                subscription.detachAndJoin()
            }
            throw t
        }
        signalSubscription = subscription
        signalConsumerJob = consumer
        wsInboundForwardJob = forwarder
        wsPassthroughStarted = true
    }

    /**
     * Stage 2 B1: one signal, one state-machine event, same session id.
     * IMPL-LOCK #4 supervision as in [WsSessionLifecycleDispatcher]: a
     * [CancellationException] rethrows; any other failure is logged and
     * the consumer keeps consuming so the channel never backs up behind a
     * dead collector.
     */
    private suspend fun consumeSessionSignal(signal: WsSessionSignal) {
        try {
            when (signal) {
                is WsSessionLifecycleEvent -> dispatchWsSessionLifecycleEvent(signal)
                is WsSessionSignal.Activity -> when (signal.kind) {
                    WsSessionSignal.ActivityKind.Frame ->
                        submitStateEvent(RestStateMachine.Event.WsFrameTextReceived(signal.sessionId.sessionEpoch))
                    WsSessionSignal.ActivityKind.Ack ->
                        submitStateEvent(RestStateMachine.Event.WsOutboundAckReceived(signal.sessionId.sessionEpoch))
                    WsSessionSignal.ActivityKind.Pong ->
                        submitStateEvent(RestStateMachine.Event.WsPongReceived(signal.sessionId.sessionEpoch))
                }
                is WsSessionSignal.Stalled -> onInboundStalled(signal)
                is WsSessionSignal.AckDeadlineExpired -> onAckDeadlineExpired(signal)
                is WsSessionSignal.Invalidated ->
                    submitStateEvent(
                        RestStateMachine.Event.WsSessionInvalidated(
                            sessionEpoch = signal.sessionId.sessionEpoch,
                            reason = signal.reason,
                        ),
                    )
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "SIGNAL_CONSUMER_ERROR kind=${signal::class.simpleName} " +
                    "epoch=${signal.sessionId.sessionEpoch} error=${t::class.simpleName} " +
                    "msg=${t.message?.take(120)}",
            )
        }
    }

    /**
     * PR-RECV-DIAG1 v1.6 / v1.8 inbound-stall path, now keyed by session.
     * Without REST capability the stall triggers a bootstrap retry (the
     * v1.8 dead-end fix); with it the tagged event goes to the machine,
     * which decides by freshness and mode (Stage 2 B3: in candidate it
     * cancels the proof, in WsActive it degrades, elsewhere it is dropped).
     */
    private suspend fun onInboundStalled(signal: WsSessionSignal.Stalled) {
        if (!restCapabilityActive) {
            Log.i(
                "PhantomHybrid",
                "REST_TRACE inbound_stall_bootstrap_retry " +
                    "sinceLastInboundMs=${signal.sinceLastInboundMs}",
            )
            maybeRetryBootstrap()
            return
        }
        val wasWsActive = stateMachine.current == RestMode.WsActive
        submitStateEvent(
            RestStateMachine.Event.InboundIdleTimeout(
                sessionEpoch = signal.sessionId.sessionEpoch,
                sinceLastInboundMs = signal.sinceLastInboundMs,
            ),
        )
        if (!wasWsActive) return
        // PR-WS-HEALTH-STATE1 Commit 3.2a: telemetry-only.
        wsDegradationDetector?.let { det ->
            wsDegradationMutex.withLock {
                feedDegradationDetectorOnInboundStalled(
                    detector = det,
                    currentKind = degradationCurrentKindProvider(),
                )
            }
        }
    }

    /**
     * PR-D1d per-envelope ACK deadline, now carrying the epoch captured
     * when the deadline was armed. Forwarded in every mode so a late
     * failure replaces silence-only evidence; the detector telemetry keeps
     * its previous WsActive guard.
     */
    private suspend fun onAckDeadlineExpired(signal: WsSessionSignal.AckDeadlineExpired) {
        if (!restCapabilityActive) return
        val wasWsActive = stateMachine.current == RestMode.WsActive
        submitStateEvent(
            RestStateMachine.Event.ActiveOutboundAckTimeout(
                sessionEpoch = signal.sessionId.sessionEpoch,
                msgId = signal.msgId,
                ageMs = signal.ageMs,
            ),
        )
        if (!wasWsActive) return
        wsDegradationDetector?.let { det ->
            wsDegradationMutex.withLock {
                feedDegradationDetectorOnAckTimeout(
                    detector = det,
                    currentKind = degradationCurrentKindProvider(),
                )
            }
        }
    }

    /**
     * Review round 8 (2026-09-13): the ONE completion every caller of
     * [closeAndJoin] waits on.
     *
     * Setting `closed` and returning was not a join. The first caller
     * flipped the flag inside [lifecycleMutex], released it, and only
     * THEN cancelled the jobs and detached the subscription; a second
     * caller saw the flag and returned immediately, while the collector
     * it believed gone was still reading and the transport's consumer
     * slot was still held. Everything after the flag now happens once,
     * and every caller waits for it.
     */
    private var closeCompletion: CompletableDeferred<Unit>? = null

    /**
     * Stage 2 B1: release everything this instance owns -- cancel and join
     * the signal consumer, the inbound forwarder, the REST inbound
     * collector and a migration job, then detach the signal subscription
     * so a successor can attach.
     *
     * Idempotent AND a real join: exactly one caller performs the close,
     * and every other caller -- concurrent or later -- returns only after
     * that same close has finished. Runs under [NonCancellable] so a
     * cancelled caller cannot leave a collector alive. The orchestrator is
     * NOT closed here: it has its own `close()` and the container closes
     * the two in reverse construction order.
     */
    suspend fun closeAndJoin() {
        var mine = false
        val completion = lifecycleMutex.withLock {
            val existing = closeCompletion
            if (existing != null) return@withLock existing
            mine = true
            closed = true
            wsPassthroughStarted = false
            CompletableDeferred<Unit>().also { closeCompletion = it }
        }
        if (!mine) {
            // Someone else owns this close. Wait for THEIR completion, not
            // for the flag they set on the way in.
            withContext(NonCancellable) { completion.await() }
            return
        }
        try {
            withContext(NonCancellable) {
                orchestrator.eventRouter = null
                val jobs = listOfNotNull(
                    signalConsumerJob, wsInboundForwardJob, restInboundJob, migrationJob,
                )
                jobs.forEach { it.cancel() }
                jobs.forEach { job ->
                    try {
                        job.join()
                    } catch (_: Throwable) {
                        // Diagnostic; join() only ensures the body has unwound.
                    }
                }
                signalConsumerJob = null
                wsInboundForwardJob = null
                restInboundJob = null
                pendingMigration = false
                // The detach is itself a join now: it returns only once the
                // collector has unwound and the transport's slot is free, so
                // a successor cannot attach before this point.
                signalSubscription?.detachAndJoin()
                signalSubscription = null
                Log.i(TAG, "REST_TRACE hybrid_closed")
            }
        } catch (t: Throwable) {
            completion.completeExceptionally(t)
            throw t
        }
        completion.complete(Unit)
    }

    /** Whether [closeAndJoin] has run. Diagnostic/test surface. */
    val isClosed: Boolean get() = closed

    /**
     * R3.6 (2026-06-20) IMPL-LOCK #3 + #4: top-level [WsSessionLifecycleDispatcher]
     * constructed once and reused for every event. Lazily initialised so
     * `this` is fully constructed before any reference to private members
     * is captured into the dispatcher's lambdas.
     *
     * Production calls go through `dispatchWsSessionLifecycleEvent`; unit
     * tests construct a fresh [WsSessionLifecycleDispatcher] directly with
     * mock callbacks so the exact same dispatch logic is exercised in
     * tests as in production — no when-arms reimplemented in test code.
     */
    private val lifecycleDispatcher: WsSessionLifecycleDispatcher by lazy {
        WsSessionLifecycleDispatcher(
            submitStateEvent = { event -> submitStateEvent(event); Unit },
            maybeRetryBootstrap = { maybeRetryBootstrap() },
            feedDegradationDetector = { legacyEvent ->
                wsDegradationDetector?.let { det ->
                    wsDegradationMutex.withLock {
                        feedDegradationDetectorOnWsSessionEnded(
                            detector = det,
                            event = legacyEvent,
                            currentKind = degradationCurrentKindProvider(),
                        )
                    }
                }
            },
            errorLogger = { msg -> Log.e(TAG, msg) },
            restCapabilityActiveProvider = { restCapabilityActive },
        )
    }

    /**
     * `internal` visibility so the single collector at [activateRestCollectors]
     * and unit tests can drive the dispatcher through the same surface.
     */
    internal suspend fun dispatchWsSessionLifecycleEvent(event: WsSessionLifecycleEvent) {
        lifecycleDispatcher.dispatch(event)
    }

    /**
     * Integration Test 20 seam (2026-06-22). Force-set the
     * `restCapabilityActive` flag WITHOUT going through the real
     * `activateRestCollectors` path. Used by tests that need to
     * observe pending-outbox migration behaviour under the
     * REST-active branch without spinning up the real orchestrator.
     * Internal so production callers cannot reach it; the flag's
     * production-side write-once contract is unchanged.
     */
    internal fun setRestCapabilityActiveForTest(active: Boolean) {
        restCapabilityActive = active
    }

    /**
     * Integration Test 20 seam (2026-06-22). Awaits the migration
     * coroutine spawned by [maybeArmMigrationLocked] so the test can
     * synchronously observe the post-migration state of the WS
     * pending stores.
     */
    internal suspend fun awaitMigrationDoneForTest() {
        migrationJob?.join()
    }

    /**
     * Switch the wrapper into REST-fallback-aware mode. Idempotent — safe
     * to call from a retry path.
     *
     * Trek 2 Stage 2B-B (C4 review-fix round 3 P1.2) — the body runs
     * under [restActivationMutex] so a concurrent call from
     * [bootstrapAndStart] and [maybeRetryBootstrap] cannot interleave
     * the `restCapabilityActive` check with the actual launch.
     * Without the mutex both callers passed the check, both launched
     * a `restInboundJob`, both called `orchestrator.start()` — two
     * sets of `stateObserverJob` + `wsActivePollJob` were spawned,
     * and the first set was orphaned when the second overwrote
     * the field references.
     *
     * If anything between collector launch and the `restCapabilityActive`
     * flip throws (including `CancellationException`), the launched
     * collector is rolled back under [NonCancellable] so a cancelled
     * caller does not leak a half-activated state.
     */
    private suspend fun activateRestCollectors() {
        restActivationMutex.withLock {
            if (restCapabilityActive) {
                return@withLock
            }
            check(!closed) { "HybridRelayTransport is closed" }
            // Trek 2 Stage 2B-B (C3 review-fix) — register the inbound
            // collector BEFORE starting the orchestrator. `SharedFlow`
            // with `replay=0` does NOT buffer envelopes for retroactive
            // subscribers; a poll-loop emit that lands before the
            // collector is registered is lost. `CoroutineStart.UNDISPATCHED`
            // runs the launched body eagerly on the current thread up to
            // the first suspension point (the `collect` call), so by
            // the time `launch` returns the collector is registered on
            // `inbound` and ready to receive emits.
            val launched: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                orchestrator.inbound.collect { handleRestInbound(it) }
            }
            restInboundJob = launched
            try {
                // Now safe to start the orchestrator — its poll loops
                // can begin emitting and the collector is guaranteed
                // to be listening. Trek 2 Stage 2B-B (C4 review-fix
                // round 2 P1.1) — start() is now suspending so it can
                // `cancelAndJoinAll` prior jobs before resetting state.
                orchestrator.start()
                // Flip flag AFTER the inbound collector is registered
                // so any event already in flight is processed correctly.
                restCapabilityActive = true
            } catch (t: Throwable) {
                // Rollback the half-launched collector under
                // `NonCancellable` so even a `CancellationException` on
                // the caller does not skip the cleanup. Re-throw after
                // rollback (CE included) so the caller observes the
                // original failure.
                withContext(NonCancellable) {
                    launched.cancel()
                    try {
                        launched.join()
                    } catch (_: Throwable) {
                        // Diagnostic; join() is to ensure the body has
                        // fully unwound regardless of completion outcome.
                    }
                    if (restInboundJob === launched) {
                        restInboundJob = null
                    }
                    // Defensive: in case `orchestrator.start()` had
                    // partially armed itself before throwing.
                    orchestrator.stop()
                }
                // Re-throw (CE included) so the caller observes the
                // original failure after rollback completes.
                throw t
            }
        }
    }

    /**
     * Re-attempt `orchestrator.bootstrap()` after a WS session terminates.
     * Rate-limited to [BOOTSTRAP_RETRY_MIN_INTERVAL_MS] between attempts so a
     * pathological flap-loop on Tele2 LTE does not hammer `/auth/session`.
     *
     * If the retry succeeds with `restFallback=true`, REST collectors are
     * started immediately (the existing WS-passthrough collectors keep
     * running unchanged).
     */
    private suspend fun maybeRetryBootstrap() {
        if (restCapabilityActive) return
        val now = nowMs()
        if (now - lastBootstrapAttemptMs < BOOTSTRAP_RETRY_MIN_INTERVAL_MS) {
            return
        }
        if (!bootstrapRetryLock.tryLock()) {
            // Another retry already in flight; let it finish.
            return
        }
        try {
            // Re-check under the lock to avoid duplicate attempts that raced
            // to acquire it.
            if (restCapabilityActive) return
            if (nowMs() - lastBootstrapAttemptMs < BOOTSTRAP_RETRY_MIN_INTERVAL_MS) {
                return
            }
            lastBootstrapAttemptMs = nowMs()
            val caps = runCatching { orchestrator.bootstrap() }.getOrNull()
            Log.i(
                TAG,
                "REST_TRACE bootstrap_retry capability=${caps?.restFallback ?: "fail"}",
            )
            if (caps != null && caps.restFallback) {
                activateRestCollectors()
            }
        } finally {
            bootstrapRetryLock.unlock()
        }
    }

    init {
        // Stage 2 B4: the orchestrator's own producers (candidate tick,
        // RestPollDegraded) reach the machine through this instance's
        // lock, so every mode change -- whoever produced it -- is armed
        // for migration under the same critical section. Not gated on
        // [restCapabilityActive]: those producers only exist after the
        // orchestrator started, and a telemetry event must not be lost
        // in the window before the flag flips.
        orchestrator.eventRouter = { event -> routeThroughLock(event) }
    }

    /**
     * Funnel every [RestStateMachine] event through one mutex so the
     * event, the resulting mode change and the migration arming are one
     * indivisible step relative to every other producer. Short-circuits
     * when REST has not been activated -- feeding events into an
     * unstarted machine has no useful effect.
     *
     * Returns the machine's [RestStateMachine.Transition] (null when the
     * mode did not change) so a caller can act on the SAME transition the
     * migration was armed for.
     */
    private suspend fun submitStateEvent(event: RestStateMachine.Event): RestStateMachine.Transition? {
        if (!restCapabilityActive) return null
        return routeThroughLock(event)
    }

    private suspend fun routeThroughLock(event: RestStateMachine.Event): RestStateMachine.Transition? {
        lockTraceForTest?.invoke("stateMachineLock:acquire")
        return stateMachineLock.withLock {
            try {
                val transition = orchestrator.submitEvent(event)
                maybeArmMigrationLocked(transition)
                transition
            } finally {
                lockTraceForTest?.invoke("stateMachineLock:release")
            }
        }
    }

    /**
     * PR-LTE-NETCHANGE1 (2026-05-28): NARROW public entry-point for
     * submitting `Event.NetworkChanged` into the REST state machine.
     *
     * Stage 2 B5: carries the observer's monotonic [networkGeneration].
     * The orchestrator learns it FIRST so REST health is invalidated and a
     * poll dispatched under the old generation cannot re-prove it; the
     * machine then invalidates the WSS proof and never enters candidate.
     *
     * **Deliberately scoped.** [HybridRelayTransport] does NOT own the
     * rewalk; those steps live in [TransportRewalkCoordinator].
     */
    override suspend fun submitNetworkChangedEvent(clearsMode2Sticky: Boolean, networkGeneration: Long) {
        orchestrator.noteNetworkChanged(networkGeneration)
        submitStateEvent(
            RestStateMachine.Event.NetworkChanged(
                clearsMode2Sticky = clearsMode2Sticky,
                networkGeneration = networkGeneration,
            ),
        )
    }

    /**
     * MUST be called while holding [stateMachineLock]. If the machine just
     * transitioned out of any non-RestActive mode into RestActive, arm the
     * pending-outbox migration:
     *  1. Allocate a LAZY-started migration coroutine (it cannot run yet).
     *  2. Assign [migrationJob] and set [pendingMigration] = true while
     *     the coroutine is still suspended in its NEW state.
     *  3. Call `job.start()` -- only now can the coroutine begin running
     *     (and, eventually, set [pendingMigration] = false in its finally).
     *
     * Why LAZY (PR-D1c review round 2 fix). With a default-start `launch`
     * the dispatcher can pick up the coroutine on another worker thread
     * immediately, run [runMigration] to completion (including its
     * `finally { pendingMigration = false }`), and return -- all before
     * the calling thread reaches `pendingMigration = true` below. LAZY
     * closes the window because no coroutine code runs until `start()`.
     *
     * Write order under the lock is still: [migrationJob] FIRST,
     * [pendingMigration] SECOND, [job.start] LAST.
     *
     * Stage 2: driven by the [RestStateMachine.Transition] the machine
     * returned for this very event, not by a before/after read.
     */
    private fun maybeArmMigrationLocked(transition: RestStateMachine.Transition?) {
        if (transition == null) return
        if (transition.to != RestMode.RestActive) return
        if (transition.from == RestMode.RestActive) return
        if (closed) return
        Log.i(TAG, "REST_TRACE migrate_pending_arm from=${transition.from} to=${transition.to}")
        val job = scope.launch(start = CoroutineStart.LAZY) { runMigration() }
        migrationJob = job
        pendingMigration = true
        job.start()
    }

    /**
     * Wraps [migratePendingWsToRest] in a try/finally that always clears
     * [pendingMigration] when the migration coroutine exits — success,
     * failure, or cancellation. Without the finally clause an exception
     * thrown mid-migration would leave new sends suspended forever on
     * [awaitPendingMigrationIfNeeded].
     */
    private suspend fun runMigration() {
        try {
            restOutboundOrderMutex.withLock {
                migratePendingWsToRest()
            }
        } finally {
            pendingMigration = false
        }
    }

    /**
     * Re-send every pending WS outbound envelope via REST in encrypt-time
     * order, then mark each successfully delivered envelope as accepted on
     * the WS side so a future reconnect does not re-flush a duplicate.
     *
     * Guardrails:
     *  - Skip oversize envelopes (body > `max_send_body_bytes` capability).
     *    Voice chunks > 4096 B currently fall through here; PR-D2 will
     *    introduce a chunking strategy for voice over REST.
     *  - Treat `SendOutcome.Accepted` (201) and `SendOutcome.Duplicate`
     *    (200, server replay) as success — both mean the relay durably
     *    holds the envelope, so we can safely remove from WS pending.
     *  - 409 Conflict is logged separately as `migrate_pending_conflict`
     *    — this is the signal that the server saw the same envelope_id
     *    with a DIFFERENT body. That would be a serious bug and we want
     *    it to be loud in logs. Leave the envelope in WS pending in that
     *    case so the existing WS retry path handles it.
     *  - Any other failure (network, 5xx, 408, 429, exhaustion) — leave
     *    in WS pending. The next WS reconnect re-flushes it.
     *  - If state flips BACK to WsActive mid-migration (WS unexpectedly
     *    recovered), abort the loop. Remaining envelopes stay in WS
     *    pending; the WS path now owns them again.
     */
    private suspend fun migratePendingWsToRest() {
        val pending = wsTransport.snapshotPendingOutbound()
        if (pending.isEmpty()) {
            Log.i(TAG, "REST_TRACE migrate_pending_skip_empty")
            return
        }
        Log.i(TAG, "REST_TRACE migrate_pending_start count=${pending.size}")
        val maxBody = orchestrator.capabilities.value.maxSendBodyBytes
        var ok = 0
        var failed = 0
        var aborted = false
        for (env in pending) {
            if (stateMachine.current == RestMode.WsActive) {
                Log.i(
                    TAG,
                    "REST_TRACE migrate_pending_aborted_state_changed " +
                        "remaining=${pending.size - ok - failed}",
                )
                aborted = true
                break
            }
            val approxBody = env.payloadBase64.length + env.sealedSenderBase64.length +
                APPROX_REST_BODY_OVERHEAD_BYTES
            if (maxBody > 0 && approxBody > maxBody) {
                Log.w(
                    TAG,
                    "REST_TRACE migrate_pending_skip_oversize id=${env.id.take(8)} " +
                        "bodyBytes=$approxBody max=$maxBody",
                )
                failed++
                continue
            }
            Log.i(
                TAG,
                "REST_TRACE migrate_pending_send id=${env.id.take(8)} " +
                    "seq=${env.sequenceTs} bodyBytes=$approxBody",
            )
            val outcome = orchestrator.sendEnvelope(
                envelopeId = env.id,
                toHex = env.to,
                payloadBase64 = env.payloadBase64,
                // Wall-clock for the server's `since_seq` ordering — NOT the
                // internal sequenceTs counter (different namespace; see
                // KtorRelayTransport.PendingOutboundEnvelope kdoc).
                sequenceTs = nowMs(),
                sealedSenderBase64 = env.sealedSenderBase64,
            )
            when (outcome) {
                is SendOutcome.Accepted -> {
                    Log.i(
                        TAG,
                        "REST_TRACE migrate_pending_ok id=${env.id.take(8)} status=201",
                    )
                    wsTransport.markPendingOutboundAcceptedByFallback(env.id)
                    ok++
                }
                is SendOutcome.Duplicate -> {
                    Log.i(
                        TAG,
                        "REST_TRACE migrate_pending_ok id=${env.id.take(8)} status=200",
                    )
                    wsTransport.markPendingOutboundAcceptedByFallback(env.id)
                    ok++
                }
                is SendOutcome.OversizeBody -> {
                    Log.w(
                        TAG,
                        "REST_TRACE migrate_pending_skip_oversize id=${env.id.take(8)} " +
                            "bodyBytes=${outcome.bodyBytes} max=${outcome.maxBytes}",
                    )
                    failed++
                }
                is SendOutcome.Failed -> {
                    if (outcome.statusCode == 409) {
                        Log.w(
                            TAG,
                            "REST_TRACE migrate_pending_conflict id=${env.id.take(8)} " +
                                "reason=${outcome.reason}",
                        )
                    } else {
                        Log.w(
                            TAG,
                            "REST_TRACE migrate_pending_fail id=${env.id.take(8)} " +
                                "status=${outcome.statusCode} reason=${outcome.reason}",
                        )
                    }
                    failed++
                }
                is SendOutcome.DisabledByCapability -> {
                    Log.w(
                        TAG,
                        "REST_TRACE migrate_pending_fail id=${env.id.take(8)} " +
                            "reason=disabled_by_capability",
                    )
                    failed++
                }
            }
        }
        if (!aborted) {
            Log.i(TAG, "REST_TRACE migrate_pending_done ok=$ok failed=$failed")
        }
    }

    /**
     * Suspend the current coroutine until any in-flight pending-outbox
     * migration completes. Polls [pendingMigration] and joins the latest
     * [migrationJob] each iteration so a back-to-back transition (REST →
     * WS → REST) that re-arms migration before the previous waiter
     * resumes is also waited on.
     *
     * Idempotent and cheap when no migration is in flight: a single
     * volatile read of [pendingMigration] returning false and we exit.
     */
    private suspend fun awaitPendingMigrationIfNeeded() {
        while (pendingMigration) {
            val job = migrationJob ?: break
            job.join()
        }
    }

    // ── Outbound routing ─────────────────────────────────────────────────────

    override suspend fun send(message: RelayMessage.Send): Boolean {
        // Direct WSS Yota-First diagnostic — §12 P0-3 fix:
        // outer_transport is now OBSERVED, not hard-coded. The
        // debug boot-init provider installs a reader lambda that
        // reads the LIVE `AppContainer.transportManager.state.value`
        // — the actually-selected outer arm after chain-walk
        // completes (Direct / Reality / Tor / probing / failed /
        // idle). Reading the live manager state (not the
        // user-selected privacy-mode preference) matters because a
        // Standard-policy device that fell through to Reality/Tor
        // returns those values here; the privacy-mode preference
        // alone would misreport the outer arm in that case.
        //
        // In release the reader is null and the observed value is
        // "unknown" (harmless — no pin is ever set in release
        // because the receiver + boot init are absent).
        //
        // §12 P0-3 fail-closed: under any pin, if the observed outer
        // arm is not "direct" the send is refused and stamped
        // dispatched=false + outcome_flag=send_error. The verifier
        // classifies the affected envelope as Unresolved (no
        // sender_rest_post_completed / sender_wss_frame_written /
        // sender_relay_ack_received arrives, so §2's terminal-set
        // criteria fail-red to Unresolved).
        val guardState = phantom.android.diagnostic.DiagnosticTransportGuard.current()
        val pinned = guardState.pin != phantom.android.diagnostic.DiagnosticTransportGuard.Pin.NONE
        val outerArmObserved = phantom.android.diagnostic.DiagnosticTransportGuard.currentOuterArm()
        val outerEnum = when (outerArmObserved) {
            "direct" -> phantom.android.diagnostic.WssDiag.OuterTransport.DIRECT
            "reality" -> phantom.android.diagnostic.WssDiag.OuterTransport.REALITY
            "tor" -> phantom.android.diagnostic.WssDiag.OuterTransport.TOR
            else -> phantom.android.diagnostic.WssDiag.OuterTransport.UNKNOWN
        }

        val innerRoutePlanned: phantom.android.diagnostic.WssDiag.InnerRoute = when {
            !pinned && !restCapabilityActive -> phantom.android.diagnostic.WssDiag.InnerRoute.WSS
            !pinned -> when (stateMachine.current) {
                RestMode.WsActive -> phantom.android.diagnostic.WssDiag.InnerRoute.WSS
                RestMode.RestActive, RestMode.WsCandidate -> phantom.android.diagnostic.WssDiag.InnerRoute.REST
            }
            guardState.pin == phantom.android.diagnostic.DiagnosticTransportGuard.Pin.WSS ->
                phantom.android.diagnostic.WssDiag.InnerRoute.WSS
            else -> phantom.android.diagnostic.WssDiag.InnerRoute.REST
        }

        val outerBlocksPin = pinned && outerEnum != phantom.android.diagnostic.WssDiag.OuterTransport.DIRECT
        phantom.android.diagnostic.WssDiag.emit(
            event = "sender_transport_decision",
            role = phantom.android.diagnostic.WssDiag.Role.SENDER,
            correlationId = message.messageId,
            outerTransport = outerEnum,
            innerRoute = innerRoutePlanned,
            dispatched = !outerBlocksPin,
        )
        if (outerBlocksPin) {
            phantom.android.diagnostic.WssDiag.emit(
                event = "sender_wss_send_returned",
                role = phantom.android.diagnostic.WssDiag.Role.SENDER,
                correlationId = message.messageId,
                innerRoute = innerRoutePlanned,
                outcomeFlag = phantom.android.diagnostic.WssDiag.OutcomeFlag.SEND_ERROR,
                dispatched = false,
            )
            return false
        }

        // §11 lock 2 — pinned inner route branches BEFORE the
        // production state-machine consultation, so pins are strictly
        // enforced without touching the state machine's semantics.
        if (pinned) {
            return when (guardState.pin) {
                phantom.android.diagnostic.DiagnosticTransportGuard.Pin.WSS ->
                    wssSendWithDiag(message)
                phantom.android.diagnostic.DiagnosticTransportGuard.Pin.REST ->
                    restOutboundOrderMutex.withLock { sendViaRest(message, RestMode.RestActive) }
                phantom.android.diagnostic.DiagnosticTransportGuard.Pin.NONE ->
                    error("unreachable — pin==NONE was ruled out above")
            }
        }

        // ── Production path (pin == NONE) ─────────────────────────
        //
        // While REST is not active, every send goes through WS — this is the
        // pre-D1b path. As soon as REST capability is on, the state machine
        // owns the routing decision.
        if (!restCapabilityActive) {
            return wssSendWithDiag(message)
        }
        val mode = stateMachine.current
        return when (mode) {
            RestMode.WsActive -> wssSendWithDiag(message)
            RestMode.RestActive, RestMode.WsCandidate -> {
                // PR-D1c: wait for the WS-pending → REST migration before
                // routing any new envelope. Without this gate a fresh send
                // issued right after the mode switch jumps ahead of an
                // unmigrated bootstrap envelope and the receiver decrypts a
                // session-existing ciphertext without ever seeing the
                // bootstrap (Test #49 root cause). Once migration completes
                // (or the flag was already false), the lock additionally
                // serialises this send against any future migration that
                // might arm while we're in flight.
                awaitPendingMigrationIfNeeded()
                restOutboundOrderMutex.withLock {
                    sendViaRest(message, mode)
                }
            }
        }
    }

    /**
     * Direct WSS Yota-First diagnostic — wraps `wsTransport.send`
     * with a `sender_wss_send_returned` event carrying the
     * boolean-return outcome. Every WSS send path through `HRT.send`
     * routes through this helper so pinned + un-pinned WSS branches
     * share the same observability schema.
     */
    private suspend fun wssSendWithDiag(message: RelayMessage.Send): Boolean {
        val ok = wsTransport.send(message)
        phantom.android.diagnostic.WssDiag.emit(
            event = "sender_wss_send_returned",
            role = phantom.android.diagnostic.WssDiag.Role.SENDER,
            correlationId = message.messageId,
            innerRoute = phantom.android.diagnostic.WssDiag.InnerRoute.WSS,
            outcomeFlag = if (ok) {
                phantom.android.diagnostic.WssDiag.OutcomeFlag.NONE
            } else {
                phantom.android.diagnostic.WssDiag.OutcomeFlag.SEND_ERROR
            },
            dispatched = ok,
        )
        return ok
    }

    private suspend fun sendViaRest(message: RelayMessage.Send, mode: RestMode): Boolean {
        val payloadBase64 = message.payload
        val sealedSender = message.sealedSender
        val approxBodyBytes = payloadBase64.length + sealedSender.length +
            APPROX_REST_BODY_OVERHEAD_BYTES
        Log.i(
            TAG,
            "REST_TRACE route_send mode=$mode id=${message.messageId.take(8)} " +
                "bodyBytes=$approxBodyBytes",
        )
        val outcome = orchestrator.sendEnvelope(
            envelopeId = message.messageId,
            toHex = message.to,
            payloadBase64 = payloadBase64,
            sequenceTs = nowMs(),
            sealedSenderBase64 = sealedSender,
        )
        val restPinned =
            phantom.android.diagnostic.DiagnosticTransportGuard.current().pin ==
                phantom.android.diagnostic.DiagnosticTransportGuard.Pin.REST
        return when (outcome) {
            is SendOutcome.Accepted -> {
                phantom.android.diagnostic.WssDiag.emit(
                    event = "sender_rest_post_completed",
                    role = phantom.android.diagnostic.WssDiag.Role.SENDER,
                    correlationId = message.messageId,
                    innerRoute = phantom.android.diagnostic.WssDiag.InnerRoute.REST,
                    relayAcceptance = phantom.android.diagnostic.WssDiag.RelayAcceptance.ACCEPTED,
                )
                true
            }
            is SendOutcome.Duplicate -> {
                phantom.android.diagnostic.WssDiag.emit(
                    event = "sender_rest_post_completed",
                    role = phantom.android.diagnostic.WssDiag.Role.SENDER,
                    correlationId = message.messageId,
                    innerRoute = phantom.android.diagnostic.WssDiag.InnerRoute.REST,
                    relayAcceptance = phantom.android.diagnostic.WssDiag.RelayAcceptance.DUPLICATE,
                )
                true
            }
            is SendOutcome.DisabledByCapability -> {
                phantom.android.diagnostic.WssDiag.emit(
                    event = "sender_rest_post_completed",
                    role = phantom.android.diagnostic.WssDiag.Role.SENDER,
                    correlationId = message.messageId,
                    innerRoute = phantom.android.diagnostic.WssDiag.InnerRoute.REST,
                    relayAcceptance = phantom.android.diagnostic.WssDiag.RelayAcceptance.DISABLED_BY_CAPABILITY,
                    outcomeFlag = phantom.android.diagnostic.WssDiag.OutcomeFlag.DROPPED_BY_CAPABILITY,
                )
                if (restPinned) {
                    // §11 lock — Pin.REST fail-closed. Do NOT
                    // silently fall back to WSS. The verifier
                    // classifies the affected envelope as Unresolved
                    // for the cell — no terminal send-completion
                    // event arrives, so §2's completion criteria
                    // fail-red to Unresolved.
                    Log.w(
                        TAG,
                        "REST_TRACE pin_rest_fail_closed id=${message.messageId.take(8)} " +
                            "reason=disabled_by_capability",
                    )
                    false
                } else {
                    // Production behaviour unchanged when pin==NONE.
                    Log.w(
                        TAG,
                        "REST_TRACE route_send_fallback_ws id=${message.messageId.take(8)} " +
                            "reason=disabled_by_capability",
                    )
                    wsTransport.send(message)
                }
            }
            is SendOutcome.OversizeBody -> {
                phantom.android.diagnostic.WssDiag.emit(
                    event = "sender_rest_post_completed",
                    role = phantom.android.diagnostic.WssDiag.Role.SENDER,
                    correlationId = message.messageId,
                    innerRoute = phantom.android.diagnostic.WssDiag.InnerRoute.REST,
                    relayAcceptance = phantom.android.diagnostic.WssDiag.RelayAcceptance.FAILED,
                    outcomeFlag = phantom.android.diagnostic.WssDiag.OutcomeFlag.SEND_ERROR,
                )
                Log.w(
                    TAG,
                    "REST_TRACE send_oversize id=${message.messageId.take(8)} " +
                        "bodyBytes=${outcome.bodyBytes} max=${outcome.maxBytes}",
                )
                // Do NOT silently fall back to WS — caller (DMS) sees false,
                // existing retry/queue logic decides what to do. Voice
                // envelopes hit this path until PR-D2 ships a chunking
                // strategy for REST.
                false
            }
            is SendOutcome.Failed -> {
                phantom.android.diagnostic.WssDiag.emit(
                    event = "sender_rest_post_completed",
                    role = phantom.android.diagnostic.WssDiag.Role.SENDER,
                    correlationId = message.messageId,
                    innerRoute = phantom.android.diagnostic.WssDiag.InnerRoute.REST,
                    relayAcceptance = phantom.android.diagnostic.WssDiag.RelayAcceptance.FAILED,
                    outcomeFlag = phantom.android.diagnostic.WssDiag.OutcomeFlag.SEND_ERROR,
                )
                Log.w(
                    TAG,
                    "REST_TRACE send_failed id=${message.messageId.take(8)} " +
                        "status=${outcome.statusCode} reason=${outcome.reason}",
                )
                false
            }
        }
    }

    // ── Inbound merge + dedup ────────────────────────────────────────────────

    private suspend fun handleRestInbound(env: PollEnvelope) {
        // Layer 1: persistent ledger — was this envelope already fully
        // processed (decrypt + insert) by DMS on a previous WS-path
        // delivery? If so, drop and re-ack to clear the relay queue.
        val alreadyProcessed = runCatching {
            processedEnvelopeRepository?.exists(env.id) ?: false
        }.getOrDefault(false)
        if (alreadyProcessed) {
            Log.i(
                TAG,
                "REST_TRACE inbound_skip_already_processed id=${env.id.take(8)}",
            )
            // Trek 2 Stage 2B-B (C3 review-fix) — explicit try/catch
            // with CancellationException rethrow so the orchestrator's
            // three-phase cancellation safety is NOT undermined by a
            // wrapper that catches everything. `runCatching` would
            // silently swallow a CE and turn an in-flight shutdown
            // into a successful return; the catch (Throwable) preserves
            // the non-cancellation defensive behaviour.
            try {
                orchestrator.ackInboundAndAdvanceCursor(env.id)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "REST_TRACE inbound_skip_already_processed_ack_threw " +
                        "id=${env.id.take(8)} reason=${t::class.simpleName}",
                )
            }
            return
        }

        // Layer 2: in-memory tracker distinguishing pending-vs-completed.
        // The tracker enforces the ACK-after-persistence invariant: a
        // duplicate while DMS is still processing returns SkipNoAck (must
        // NOT ack), a duplicate after sendDeliveryAck returns ReAck (safe).
        when (val action = restDedup.resolve(env.id)) {
            RestInboundDeduplicator.Action.Emit -> {
                Log.i(
                    TAG,
                    "REST_TRACE inbound_deliver id=${env.id.take(8)} " +
                        "from=${env.fromHex.take(8)} source=REST",
                )
                _incoming.emit(
                    RelayMessage.Deliver(
                        from = env.fromHex,
                        sealedSender = env.sealedSenderBase64,
                        payload = env.payloadBase64,
                        messageId = env.id,
                    )
                )
            }
            RestInboundDeduplicator.Action.SkipNoAck -> {
                // DMS is still mid-decrypt of this id; another emission
                // would be redundant and acking now would let the relay
                // drop the envelope before DMS commits to storage.
                Log.i(
                    TAG,
                    "REST_TRACE inbound_skip_pending id=${env.id.take(8)}",
                )
            }
            RestInboundDeduplicator.Action.ReAck -> {
                if (processedEnvelopeRepository != null) {
                    // The persistent check above did not find completion.
                    restDedup.park(env.id)
                    return
                }
                // DMS has already called sendDeliveryAck → the envelope is
                // durably stored. The previous /relay/ack-deliver request
                // must have failed at the network layer. Safe to re-ack.
                Log.i(
                    TAG,
                    "REST_TRACE inbound_reack_after_ack id=${env.id.take(8)}",
                )
                // Trek 2 Stage 2B-B (C3, L3 Step 3) — ReAck path uses
                // the combined method so a successful retry not only
                // clears the relay queue but also advances the
                // persisted cursor that the FIRST ack attempt
                // missed. M-B20 pins the success-after-retry path.
                //
                // C3 review-fix — explicit try/catch with CE rethrow
                // (the orchestrator's three-phase cancellation safety
                // is undermined by `runCatching`).
                try {
                    orchestrator.ackInboundAndAdvanceCursor(env.id)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(
                        TAG,
                        "REST_TRACE inbound_reack_ack_threw " +
                            "id=${env.id.take(8)} reason=${t::class.simpleName}",
                    )
                }
            }
        }
    }

    // ── ACK routing ──────────────────────────────────────────────────────────

    override suspend fun parkInbound(messageId: String) {
        restDedup.park(messageId)
    }

    /**
     * Queue-progress fix (2026-09-12). The recipient confirmed a durable
     * held-registry write for [messageId] and did NOT acknowledge it.
     * The processing claim is released exactly as in [parkInbound]; in
     * addition the orchestrator is told, so its poll loops stop asking
     * the strict head-of-line relay for this sequence while the process
     * lives. The relay keeps its copy; the persisted cursor is not
     * touched; no ACK is sent. A hold write that FAILED must not reach
     * this method -- the service calls [parkInbound] for that case.
     */
    override suspend fun deferInboundHeld(messageId: String) {
        // Order matters (review round 2). While the dedup claim is held,
        // a redelivery of this id is `SkipNoAck`; once released it is a
        // fresh `Emit`. The scan position must therefore be recorded
        // BEFORE the claim is released -- releasing first would leave a
        // window in which the relay, still asked from the old
        // `since_seq`, re-offers the envelope and it is processed a
        // second time. The release runs in `finally` so a deferral that
        // throws, and a `NotRestOrigin` outcome, still free the claim.
        val outcome = try {
            orchestrator.deferInboundHeld(messageId)
        } finally {
            restDedup.park(messageId)
        }
        Log.i(
            TAG,
            "REST_TRACE inbound_deferred_held id=${messageId.take(8)} " +
                "outcome=${outcome::class.simpleName}",
        )
    }

    override suspend fun sendDeliveryAck(messageId: String): Boolean {
        // In-memory bookkeeping is not evidence that the recipient committed.
        if (processedEnvelopeRepository != null &&
            !processedEnvelopeRepository.exists(messageId)) {
            restDedup.park(messageId)
            Log.w(TAG, "REST_TRACE ack_refused_uncommitted id=${messageId.take(8)}")
            return false
        }
        val isRestId = orchestrator.hasInboundForAck(messageId)
        if (!isRestId) {
            return wsTransport.sendDeliveryAck(messageId)
        }
        // DMS only calls sendDeliveryAck AFTER markProcessed + insertMessage,
        // so by the time we get here the envelope is durably stored locally.
        // This is the load-bearing ACK-after-persistence invariant.
        //
        // Trek 2 Stage 2B-B (C3, L3 Step 4) — the combined method
        // wraps `ackInbound` + the persistent cursor advance into a
        // single cancellation-safe call. The outcome classification
        // below is unchanged; on `Failed` the entry stays in
        // `_pendingSeqForAck` for the ReAck path's retry. On `Acked`
        // the cursor is already persisted by the time this returns.
        Log.i(TAG, "REST_TRACE ack_after_save id=${messageId.take(8)}")
        // Trek 2 Stage 2B-B (C3 review-fix) — explicit try/catch so a
        // shutdown-time CancellationException propagates to the
        // caller (and the rest of the structured-concurrency tree)
        // instead of being silently turned into `AckOutcome.Failed`.
        val outcome = try {
            orchestrator.ackInboundAndAdvanceCursor(messageId)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AckOutcome.Failed(statusCode = null, reason = t::class.simpleName ?: "Throwable")
        }
        // Mark acknowledged regardless of network outcome — DMS has
        // persisted the envelope. If the relay re-delivers because our ack
        // never reached it, the tracker correctly returns ReAck on the
        // next handleRestInbound and we'll try ackInbound again.
        restDedup.markAcknowledged(messageId)
        return when (outcome) {
            is AckOutcome.Acked -> true
            is AckOutcome.DisabledByCapability -> {
                Log.w(
                    TAG,
                    "REST_TRACE ack_disabled_by_capability id=${messageId.take(8)}",
                )
                false
            }
            is AckOutcome.Failed -> {
                Log.w(
                    TAG,
                    "REST_TRACE ack_failed id=${messageId.take(8)} " +
                        "status=${outcome.statusCode} reason=${outcome.reason}",
                )
                false
            }
        }
    }

    companion object {
        private const val TAG = "PhantomHybrid"

        /**
         * Minimum interval between bootstrap retry attempts. A WS session
         * that ends within this window after the previous bootstrap try
         * does not trigger a new `/auth/session` POST. Set to 60 s so a
         * Tele2 LTE reconnect storm cannot DoS our own relay.
         */
        const val BOOTSTRAP_RETRY_MIN_INTERVAL_MS: Long = 60_000L

        /**
         * Rough overhead approximating JSON keys + Idempotency-Key header
         * + base envelope size on top of `payload.length + sealed_sender.length`.
         * Used only for the `route_send` log line — actual wire size is
         * computed at the orchestrator/transport layer.
         */
        const val APPROX_REST_BODY_OVERHEAD_BYTES: Int = 256
    }
}
