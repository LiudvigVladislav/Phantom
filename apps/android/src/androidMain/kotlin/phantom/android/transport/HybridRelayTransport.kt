// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import phantom.core.transport.TransportState

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
 * **State-machine feeds** wired by [startWsCollectors] (always) and
 * [startRestCollectors] (only after a successful bootstrap):
 * - `wsTransport.wsSessionEnded` → [RestStateMachine.Event.WsSessionEnded]
 *   (REST-only — drives WsCandidate → RestActive degrade).
 * - every `wsTransport.incoming` emission → forward to `_incoming`
 *   (ALWAYS), and if REST is up, also `WsFrameTextReceived`.
 * - every `wsTransport.acks` emission → forward acks via Flow chain
 *   (ALWAYS — `acks` is just the upstream Flow reference) and, if REST is
 *   up, also `WsFrameTextReceived` AND `WsOutboundAckReceived`
 *   (commits a candidate session into `WsActive` immediately).
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
 * **Thread safety.** [RestStateMachine] is NOT thread-safe; every event
 * submission goes through [submitStateEvent] which takes [stateMachineLock].
 * The WS → REST transition is detected synchronously inside that lock,
 * and migration is armed (both [migrationJob] and [pendingMigration]
 * written in that order) under the same lock so a concurrent
 * [send] that sees the new mode is guaranteed to see the migration in
 * flight and suspend on [awaitPendingMigrationIfNeeded].
 */
class HybridRelayTransport(
    private val wsTransport: KtorRelayTransport,
    private val orchestrator: RestFallbackOrchestrator,
    private val processedEnvelopeRepository: ProcessedEnvelopeRepository?,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : RelayTransport {

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
     * Lock serialising all writes to [RestStateMachine] via
     * [RestFallbackOrchestrator.submitEvent]. Without it, three independent
     * collectors (WS session-end, WS frames, WS acks) can race and corrupt
     * the state machine's transition counters.
     */
    private val stateMachineLock = Mutex()

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
     * Wall-clock ms of the last bootstrap attempt (success or fail). Used by
     * [maybeRetryBootstrap] for rate-limiting — we do not want to slam the
     * relay with `/auth/session` POSTs every time a WS session flaps on
     * Tele2 LTE.
     */
    @Volatile private var lastBootstrapAttemptMs: Long = 0L

    /**
     * Tracking handles for the REST-specific collectors so a future retry
     * can avoid starting duplicates. WS-passthrough collectors are started
     * once at [bootstrapAndStart] entry and never replaced.
     */
    private var wsSessionEndedJob: Job? = null
    private var wsFramesStateJob: Job? = null
    private var wsAcksStateJob: Job? = null
    private var restInboundJob: Job? = null

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
     * WS-passthrough collectors. Started once at [bootstrapAndStart] entry
     * and never stopped. Forward inbound WS frames into [_incoming] so DMS
     * keeps receiving messages independently of REST capability.
     *
     * State-machine event submission via [submitStateEvent] is a no-op when
     * [restCapabilityActive] is false (see [submitStateEvent] for rationale).
     *
     * Idempotent: a second call is a no-op. Guarded by [wsPassthroughStarted]
     * so a future retry path cannot accidentally spawn a second WS inbound
     * collector and cause duplicate delivery into [_incoming].
     */
    private fun startWsPassthroughCollectors() {
        if (wsPassthroughStarted) return
        wsPassthroughStarted = true
        scope.launch {
            wsTransport.incoming.collect { deliver ->
                _incoming.emit(deliver)
                submitStateEvent(RestStateMachine.Event.WsFrameTextReceived)
            }
        }
        scope.launch {
            wsTransport.acks.collect {
                // WS ACK = strongest "bidirectional WS" signal — must commit
                // a WsCandidate session into WsActive atomically. Feeding
                // both events under one lock acquisition prevents a
                // WsSessionEnded from racing in between and reverting the
                // mode before the ack's commit lands.
                submitStateEvents(
                    RestStateMachine.Event.WsFrameTextReceived,
                    RestStateMachine.Event.WsOutboundAckReceived,
                )
            }
        }
        // wsSessionEnded drives BOTH (a) the state machine when REST is
        // active and (b) the bootstrap-retry loop when REST is not. Wire it
        // here so the retry mechanism works even if the very first
        // bootstrap returned capability=false.
        scope.launch {
            wsTransport.wsSessionEnded.collect { event ->
                if (restCapabilityActive) {
                    submitStateEvent(
                        RestStateMachine.Event.WsSessionEnded(
                            durationMs = event.durationMs,
                            inboundFrames = event.inboundFrames,
                            pendingAcksAtClose = event.pendingAcksAtClose,
                        )
                    )
                } else {
                    maybeRetryBootstrap()
                }
            }
        }
    }

    /**
     * Switch the wrapper into REST-fallback-aware mode. Idempotent — safe
     * to call from a retry path.
     */
    private fun activateRestCollectors() {
        if (restCapabilityActive) {
            return
        }
        orchestrator.start()
        // REST poll inbound → dedup → translate → merged inbound.
        restInboundJob = scope.launch {
            orchestrator.inbound.collect { handleRestInbound(it) }
        }
        // Flip flag AFTER the inbound collector is registered so any event
        // already in flight is processed correctly.
        restCapabilityActive = true
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

    /**
     * Funnel every [RestStateMachine] event through one mutex so the
     * machine's internal counters stay consistent under concurrent emit /
     * ack / session-end signals. Short-circuits when REST has not been
     * activated — feeding events into an unstarted machine has no useful
     * effect and we don't want to churn it.
     *
     * Also detects WS → RestActive transitions synchronously and arms the
     * pending-outbox migration under the same critical section (see
     * [maybeArmMigrationLocked]).
     */
    private suspend fun submitStateEvent(event: RestStateMachine.Event) {
        if (!restCapabilityActive) return
        stateMachineLock.withLock {
            val before = stateMachine.current
            orchestrator.submitEvent(event)
            maybeArmMigrationLocked(before, stateMachine.current)
        }
    }

    /**
     * Atomic batch variant: submits the given events under a SINGLE
     * acquisition of [stateMachineLock] so they are applied as one
     * indivisible step relative to other collectors. Use this for event
     * pairs that must observe each other's effect — e.g. WS ACK = (frame
     * received + outbound ack), where a WsSessionEnded racing between them
     * would let WsCandidate revert before commit.
     *
     * Detects WS → RestActive transitions on the COMPOSITE before/after
     * mode pair — if any of the events in the batch produces the
     * transition, migration is armed once at the end.
     */
    private suspend fun submitStateEvents(vararg events: RestStateMachine.Event) {
        if (!restCapabilityActive) return
        stateMachineLock.withLock {
            val before = stateMachine.current
            for (event in events) {
                orchestrator.submitEvent(event)
            }
            maybeArmMigrationLocked(before, stateMachine.current)
        }
    }

    /**
     * MUST be called while holding [stateMachineLock]. If the state
     * machine just transitioned out of any non-RestActive mode into
     * RestActive, arm the pending-outbox migration:
     *  1. Launch a coroutine that will [runMigration] under
     *     [restOutboundOrderMutex] (the launch itself is non-blocking).
     *  2. Mark [pendingMigration] = true.
     *
     * Write order is critical: [migrationJob] FIRST, [pendingMigration]
     * SECOND. A concurrent [send] caller that volatile-reads
     * [pendingMigration] = true is guaranteed to see the new job ref in
     * the subsequent volatile read of [migrationJob] (JMM happens-before
     * on @Volatile writes). Reversing the order opens a window where a
     * waiter sees [pendingMigration] = true but [migrationJob] = null and
     * breaks out of [awaitPendingMigrationIfNeeded] without waiting.
     */
    private fun maybeArmMigrationLocked(before: RestMode, after: RestMode) {
        if (after != RestMode.RestActive) return
        if (before == RestMode.RestActive) return
        Log.i(TAG, "REST_TRACE migrate_pending_arm from=$before to=$after")
        migrationJob = scope.launch { runMigration() }
        pendingMigration = true
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
        // While REST is not active, every send goes through WS — this is the
        // pre-D1b path. As soon as REST capability is on, the state machine
        // owns the routing decision.
        if (!restCapabilityActive) {
            return wsTransport.send(message)
        }
        val mode = stateMachine.current
        return when (mode) {
            RestMode.WsActive -> wsTransport.send(message)
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
        return when (outcome) {
            is SendOutcome.Accepted -> true
            is SendOutcome.Duplicate -> true
            is SendOutcome.DisabledByCapability -> {
                // Capability flipped to false after bootstrap (shouldn't
                // happen in steady state, but defend against it): fall
                // back to WS for this single send.
                Log.w(
                    TAG,
                    "REST_TRACE route_send_fallback_ws id=${message.messageId.take(8)} " +
                        "reason=disabled_by_capability",
                )
                wsTransport.send(message)
            }
            is SendOutcome.OversizeBody -> {
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
            runCatching { orchestrator.ackInbound(env.id) }
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
                // DMS has already called sendDeliveryAck → the envelope is
                // durably stored. The previous /relay/ack-deliver request
                // must have failed at the network layer. Safe to re-ack.
                Log.i(
                    TAG,
                    "REST_TRACE inbound_reack_after_ack id=${env.id.take(8)}",
                )
                runCatching { orchestrator.ackInbound(env.id) }
            }
        }
    }

    // ── ACK routing ──────────────────────────────────────────────────────────

    override suspend fun sendDeliveryAck(messageId: String): Boolean {
        // The dedup tracker is the authoritative source of "is this a REST
        // id?". We optimistically check before WS-delegation so a missing
        // entry (e.g. an id that was never emitted via REST) falls through
        // to the WS path with no extra round-trip.
        val isRestId = restDedup.isPending(messageId)
        if (!isRestId) {
            return wsTransport.sendDeliveryAck(messageId)
        }
        // DMS only calls sendDeliveryAck AFTER markProcessed + insertMessage,
        // so by the time we get here the envelope is durably stored locally.
        // This is the load-bearing ACK-after-persistence invariant.
        Log.i(TAG, "REST_TRACE ack_after_save id=${messageId.take(8)}")
        val outcome = runCatching { orchestrator.ackInbound(messageId) }
            .getOrElse { AckOutcome.Failed(statusCode = null, reason = it::class.simpleName ?: "Throwable") }
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
