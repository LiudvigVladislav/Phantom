// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Presentation evidence only; neither value changes routing or probation. */
enum class RestRecoveryCause { InboundSilence, FailureOrUnknown }

/**
 * Mode plus the evidence presentation needs, published together after
 * every event so a screen can never combine a fresh mode with a stale
 * cause or session.
 *
 * Stage 2 (2026-09-13): [liveSessionEpoch] is the machine's live session
 * (B2), [candidateEpoch] the session proving itself in candidate,
 * [provenSessionEpoch] the session whose proof holds `WsActive` (I1), and
 * [reconnectRequested] the B5 flag a network change raises until the next
 * accepted `Connected`.
 */
data class RestModeSnapshot(
    val mode: RestMode,
    val recoveryCause: RestRecoveryCause = RestRecoveryCause.FailureOrUnknown,
    val liveSessionEpoch: Long? = null,
    val candidateEpoch: Long? = null,
    val provenSessionEpoch: Long? = null,
    val reconnectRequested: Boolean = false,
)

/**
 * Pure state machine for WS ↔ REST fallback mode selection — PR-D1,
 * rewritten for Stage 2 (2026-09-13) around a **session identity**.
 *
 * Decides whether outbound envelope traffic and inbound polling should run
 * over the WebSocket path ([RestMode.WsActive]), the REST short-poll
 * fallback path ([RestMode.RestActive]), or the transitional candidate
 * state ([RestMode.WsCandidate]) where a WS session exists but has not
 * yet proved itself while REST polling stays on as a safety net.
 *
 * The machine is **input-driven**: it consumes [Event]s fed in by the
 * Hybrid transport (or by tests) and emits mode transitions via [state]
 * and presentation evidence via [snapshot]. It has no dependency on
 * [RelayTransport] or any I/O.
 *
 * ## Stage 2 rules (contract B2–B5, B10)
 *
 * - **Freshness (B2).** [liveSession] is the newest accepted
 *   `Connected(id)`; `Connected` is accepted iff `liveSession == null ||
 *   id > liveSession`. Every other id-bearing event is handled iff
 *   `id == liveSession`, otherwise it is logged as
 *   `stale_session_signal_ignored` and changes nothing.
 * - **Proof (B3).** `WsActive` is reachable only with `candidate ==
 *   liveSession` and either an outbound ACK on that session or at least
 *   one inbound text frame on it plus [CANDIDATE_COMMIT_MS] since it
 *   connected, confirmed by an alive tick carrying the same id. A pong is
 *   liveness only. Candidate is entered only by an accepted `Connected`
 *   from `RestActive` or by a frame of the live session while in
 *   `RestActive`; the clock is refreshed when the candidate id changes.
 * - **Negative signals of the live session.** A stall or an ACK deadline
 *   of the live session degrades `WsActive` and cancels a candidate's
 *   proof; the mode becomes `RestActive` and [liveSession] is KEPT: the
 *   socket is silent, not gone, and the transport's watchdogs own it.
 * - **Invalidation.** `Ended(live)`, `Invalidated(live)`, an accepted
 *   `Connected(newer)` and `NetworkChanged` clear [liveSession] and the
 *   candidate, so a stale tick can never promote and a dead session can
 *   never hold `WsActive` (I1, I2, S2).
 * - **Trusted first connect.** Before any degradation the machine starts
 *   in `WsActive` with no session; the first accepted `Connected` is taken
 *   as the proven session (cold start showed `Online` on connect before
 *   Stage 2 and still does). Every LATER session must prove itself.
 *
 * The R3.6 sticky window and the RC-RECONNECT-QUIESCENCE1 gate are kept
 * unchanged behind their build flags (all off in the shipped APK); with
 * the flags on, `ws_alive_60s` additionally requires the B3 proof on the
 * same id (B10).
 *
 * Threading: every [onEvent] runs entirely under [eventMutex] and returns
 * the [Transition] it caused, if any. Lock order is fixed and traced for
 * tests: the Hybrid's `stateMachineLock` -> [eventMutex] -> [gateLock];
 * the reverse is forbidden.
 */
class RestStateMachine(
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
    // PR-WS-HEALTH-STATE1 Commit 3.2a (architect P2-2, 2026-06-01):
    // observation hook for every mode-switch. Fires AFTER `_state.value`
    // has been assigned the new mode and AFTER the `REST_TRACE
    // mode_switched ...` log line has been emitted. Used by
    // [phantom.core.transport.WsDegradationDetector] to emit
    // `WS_DEGRADED_TELEMETRY state_transition_seen reason=<reason>` so
    // calibration can correlate detector verdicts with state-machine
    // transitions per design note §6. Optional and defaults to no-op
    // for backward compatibility with existing tests.
    private val onModeSwitched: ((from: RestMode, to: RestMode, reason: String) -> Unit)? = null,
    /**
     * Trek 2 Stage 2B-B (C5, L9; review-fix P2 added the actual
     * invocation surface) — telemetry hook fired when the REST
     * poll breaker enters [LongPollBreakerState.Open]. Receives
     * the [BreakerOpenReason] that triggered the opening so the
     * AppContainer wire-up can mirror the signal into the
     * existing [phantom.core.transport.WsDegradationDetector] stream.
     *
     * Per scope §L9 the handler is read-only — it does NOT
     * cause a `RestMode` transition. Optional; defaults to no-op
     * for backward compatibility with existing tests.
     */
    private val onRestPollDegraded: ((BreakerOpenReason) -> Unit)? = null,
    /**
     * 3.6 Fast REST degradation gate (2026-06-18). When `true`, the
     * first [Event.WsSessionEnded] of the live session in
     * [RestMode.WsActive] that matches the Mode-2 signature
     * (`inboundFrames == 0` AND `okhttpPingTimeoutDetected == true` AND
     * `durationMs in MODE_2_MIN_DURATION_MS..MODE_2_MAX_DURATION_MS`)
     * transitions to [RestMode.RestActive] via the `mode_2_fast_path`
     * reason and arms the sticky window when [mode2StickyEnabled].
     *
     * Stage 2: every live session end degrades `WsActive` (I1), so the
     * flag no longer changes WHETHER the mode drops -- it selects the
     * reason and the sticky arming. The matched-signature telemetry line
     * fires regardless of this gate (action=fast_path vs
     * action=observe_only).
     *
     * Default `false`. Wired through [RestFallbackOrchestrator] from
     * `BuildConfig.MODE_2_FAST_PATH_ENABLED` in `AppContainer`.
     */
    private val mode2FastPathEnabled: Boolean = false,
    /**
     * R3.6 Sticky-per-route Fast REST degradation (2026-06-20). When `true`,
     * a Mode-2 fast-path transition arms a sticky REST window that suppresses
     * any frame-driven exit from [RestMode.RestActive] until the WS layer
     * proves recovery via `ws_alive_60s` on a brand-new session after a
     * route change that sets [Event.NetworkChanged.clearsMode2Sticky].
     *
     * Build-time invariant: `mode2StickyEnabled` requires `mode2FastPathEnabled`.
     * Enforced in `init { require(...) }`.
     *
     * Default `false`. Wired through [RestFallbackOrchestrator] from
     * `BuildConfig.MODE_2_STICKY_ENABLED` in `AppContainer`.
     */
    private val mode2StickyEnabled: Boolean = false,
    /**
     * RC-RECONNECT-QUIESCENCE1 (2026-06-22). When `true`, [armSticky] also
     * transitions the [gate] StateFlow from [WsReconnectGate.Open] to
     * [WsReconnectGate.Quiesced] — provided the current transport kind is
     * [TransportKind.Direct] (see [currentKindProvider]). The
     * reconnect-loop in [KtorRelayTransport] observes the gate and
     * suspends new WS dials while Quiesced.
     *
     * Build-time invariant: `reconnectQuiescenceEnabled` requires
     * `mode2StickyEnabled` (the gate is meaningless without sticky armed).
     *
     * Default `false`. Wired through [RestFallbackOrchestrator] from
     * `BuildConfig.RECONNECT_QUIESCENCE_ENABLED`.
     */
    private val reconnectQuiescenceEnabled: Boolean = false,
    /**
     * RC-RECONNECT-QUIESCENCE1: snapshot of the current [TransportKind].
     * The quiescence gate engages ONLY when this returns
     * [TransportKind.Direct] at the moment sticky arms. Reality / Tor
     * have their own failure modes and reconnect cadences that the gate
     * MUST NOT alter. Default returns `null` (gate disengaged) for
     * tests that don't care.
     */
    private val currentKindProvider: () -> TransportKind? = { null },
    /**
     * RC-RECONNECT-QUIESCENCE1: token source for one-shot probe tokens.
     * Production wires [phantom.core.crypto.LibsodiumCsprng.uniformLong]
     * via the AppContainer; tests inject a deterministic source. The
     * raw [Long] is immediately wrapped in [ProbeToken] which redacts
     * its `toString` so the value cannot leak through carrier-type
     * auto-derived `toString()`.
     */
    private val tokenSource: () -> Long = { 0L },
) : WsReconnectGateProvider, RewalkCoordinatorGateProvider {
    /**
     * Review round 7 (2026-09-13): the machine starts in [RestMode.RestActive]
     * with no live session and no proof.
     *
     * It used to start in [RestMode.WsActive] and take the first `Connected`
     * as proof, which is the false-online status this stage exists to
     * remove: `WsActive` is the mode that STOPS REST polling and routes
     * outbound over the socket, and a handshake alone has never been
     * evidence that the socket carries traffic. Presentation is not made
     * worse by this -- B9 shows `Connecting` / `Reconnecting` while an
     * attempt is in flight, and `Offline` only when nothing is.
     */
    private val _state = MutableStateFlow<RestMode>(RestMode.RestActive)
    val state: StateFlow<RestMode> = _state.asStateFlow()
    private val _snapshot = MutableStateFlow(RestModeSnapshot(RestMode.RestActive))
    /** Mode and its presentation evidence are published together after each event. */
    val snapshot: StateFlow<RestModeSnapshot> = _snapshot.asStateFlow()
    private var recoveryCause = RestRecoveryCause.FailureOrUnknown
    private var recoveryRequired = false

    /**
     * One mode change, as returned by [onEvent]. The Hybrid arms the
     * pending-outbox migration from THIS value under its own lock, so the
     * event, the mode change and the arming are one indivisible step.
     */
    data class Transition(val from: RestMode, val to: RestMode, val reason: String)

    // ── Stage 2 session identity ────────────────────────────────────────────

    /**
     * Whole-handler mutex (B4). Every [onEvent] runs entirely inside it,
     * so two producers can never interleave handlers; [gateLock] is
     * strictly inner.
     */
    private val eventMutex = Mutex()

    /**
     * Stage 2 test seam (row 24): lock acquire / release trace for the
     * lock-order fixture. Null in production.
     */
    @Volatile internal var lockTraceForTest: ((String) -> Unit)? = null

    /** The newest accepted `Connected`; null after any invalidation. */
    @Volatile private var liveSession: WsSessionId? = null

    /** The session currently proving itself in [RestMode.WsCandidate]. */
    @Volatile private var candidate: WsSessionId? = null

    /** Whether [candidate] produced at least one inbound text frame (B3 b). */
    private var candidateHasFrame: Boolean = false

    /** The session whose proof holds [RestMode.WsActive]; equals [liveSession] there (I1). */
    @Volatile private var proofSession: WsSessionId? = null

    /** B5: set by `NetworkChanged`, cleared by the next accepted `Connected`. */
    private var reconnectRequested: Boolean = false

    /** Reason of the most recent mode switch, carried into [Transition]. */
    private var lastSwitchReason: String = ""

    // ── RC-RECONNECT-QUIESCENCE1 gate surface (2026-06-22) ──────────────────

    private val _gate = MutableStateFlow<WsReconnectGate>(WsReconnectGate.Open)
    /** Reconnect quiescence gate; observed by [KtorRelayTransport.runReconnectLoop]. */
    override val gate: StateFlow<WsReconnectGate> = _gate.asStateFlow()

    /**
     * Monotonic route-change counter. Bumped by [beginRouteChange]; older
     * epochs cannot claim newer probes. Never decremented (revocation
     * leaves the counter at the bumped value to defeat stale-epoch
     * attacks).
     */
    private var routeEpoch: Long = 0L

    /**
     * Monotonic per-loop generation counter. Allocated once at the start
     * of each [KtorRelayTransport.runReconnectLoop] invocation via
     * [allocateConnectionGeneration]; bound to a probe atomically at
     * claim time. Distinct from the per-WS-session epoch
     * (`wsSessionEpoch` in [KtorRelayTransport]).
     */
    private var connectionGenerationCounter: Long = 0L

    /**
     * Single mutex covering ALL gate-related mutations:
     *   - `_gate` writes (from suspend coordinator API AND from
     *     suspend [onEvent] event-driven transitions);
     *   - `routeEpoch` reads + writes;
     *   - `connectionGenerationCounter` reads + writes;
     *   - `probeAttemptCount` reads + writes.
     *
     * Held for the COMPUTATION of a transition only — NOT during
     * `log(...)`, telemetry callbacks, `delay`, network, or any
     * external side effect. Each gate-mutating method uses the
     * two-phase pattern:
     *
     *   val computed = withGateLock { compute + assign state }
     *   // ... release lock ...
     *   emitTelemetry(computed)
     *
     * to avoid self-deadlock when a callback would (transitively) want
     * to acquire the same lock, and to keep the critical section short.
     * Stage 2: strictly INNER to [eventMutex]; traced by [withGateLock].
     */
    private val gateLock = Mutex()

    private suspend inline fun <T> withGateLock(block: () -> T): T {
        lockTraceForTest?.invoke("gateLock:acquire")
        return gateLock.withLock {
            try {
                block()
            } finally {
                lockTraceForTest?.invoke("gateLock:release")
            }
        }
    }

    /**
     * Pre-Connected attempt counter for the in-flight [WsReconnectGate.ProbeClaimed].
     * Incremented every time the reconnect-loop exits an iteration
     * without a [Event.WsSessionConnected]. When attempts ≥ budget OR
     * elapsed ≥ budget, gate flips to [WsReconnectGate.Quiesced] with
     * reason `probe_exhausted`.
     */
    private var probeAttemptCount: Int = 0

    /**
     * Test gap #2 strengthening (2026-06-22). Direct seam to mutate
     * `probeAttemptCount` and `_gate.value` without going through
     * the public API. Used by the residual-count regression
     * test: the existing
     * `new_probe_gets_full_budget_after_partially_used_previous_probe`
     * passes through `WsSessionConnected` which auto-resets the
     * counter, so it cannot prove that the `issueProbeAfterRewalk`
     * reset actually fires. This seam constructs the exact residual
     * state the reset defends against.
     */
    internal suspend fun setResidualProbeStateForTest(
        gate: WsReconnectGate,
        probeAttemptCount: Int,
    ) {
        withGateLock {
            this.probeAttemptCount = probeAttemptCount
            _gate.value = gate
        }
    }

    internal suspend fun probeAttemptCountForTest(): Int = withGateLock { probeAttemptCount }

    /** Convenience accessor. */
    val current: RestMode
        get() = _state.value

    /** Stage 2 read seams for fixtures and invariant checks. */
    val liveSessionEpoch: Long? get() = liveSession?.sessionEpoch
    val candidateSessionEpoch: Long? get() = candidate?.sessionEpoch
    val provenSessionEpoch: Long? get() = proofSession?.sessionEpoch

    private var candidateEnteredAtMs: Long? = null

    // ── R3.6 Sticky-recovery fields ─────────────────────────────────────────

    /**
     * R3.6 (2026-06-20): monotonic floor for session epoch observations.
     * Updated on every observed [Event.WsSessionConnected], regardless of
     * [stickyRecovery] state. Defends against delayed stale `Connected(N)`
     * events with `N <= lastObservedEpoch` being processed out of order.
     * Initial value -1L because valid epochs start at 1 (incremented from 0
     * before first use in `wsSessionEpoch`).
     *
     * Stage 2 B2: this IS the freshness floor for `Connected` -- an
     * accepted session is always `> lastObservedEpoch` at acceptance, and
     * an invalidated one keeps the floor, so a late `Connected` of an
     * older attempt can never become live.
     */
    private var lastObservedEpoch: Long = -1L

    /**
     * R3.6: tracks the recovery lifecycle state for the sticky REST window.
     * Set to [StickyRecoveryState.PendingNewSession] by [armSticky] when a
     * Mode-2 fast-path fires and [mode2StickyEnabled] is true.
     *
     * `@Volatile` (PR #353 round 2, 2026-06-30) — read from outside the
     * owning coroutine context via [isStickyOrRecoveryActive]. The
     * annotation provides JVM memory-visibility guarantees so the
     * cross-thread reader sees the most recent write. Mutations remain
     * confined to the owning coroutine per the class-level threading
     * contract; under RC-RECONNECT-QUIESCENCE1 they additionally run
     * under [gateLock] so this annotation is defence-in-depth for the
     * lockless reader path the L1 synthetic-trigger surface relies on.
     */
    @Volatile private var stickyRecovery: StickyRecoveryState = StickyRecoveryState.None

    /** R3.6: epoch of the WS session currently serving as the recovery candidate. */
    private var recoveryWsEpoch: Long = -1L

    /** R3.6: wall-clock ms at which the current recovery attempt started. */
    private var recoveryStartedAtMs: Long = 0L

    /**
     * R3.6: true while sticky REST suppression is active (mode2StickyEnabled && was armed).
     *
     * `@Volatile` (PR #353 round 2, 2026-06-30) — same cross-thread
     * reader justification as [stickyRecovery] above.
     */
    @Volatile private var mode2StickyRestActive: Boolean = false

    /** R3.6: wall-clock ms at which [armSticky] last fired. */
    private var stickyArmedAtMs: Long = 0L

    /** R3.6: monotonically increasing generation counter; incremented each time [armSticky] fires. */
    private var stickyGen: Int = 0

    /** R3.6: whether the first suppressed frame-text log for this gen has been emitted. */
    private var stickyFrameSuppressedLogged: Boolean = false

    /** R3.6: count of frame-text suppression events in this gen. */
    private var stickySuppressedCount: Int = 0

    /** R3.6: sticky-recovery lifecycle states. */
    private enum class StickyRecoveryState { None, PendingNewSession, InFlight }

    /**
     * QUIESCENCE-VALIDATION-L1-SYNTHETIC-MINI-LOCK §7.2 D-1 edge-case
     * accessor (PR #353 round 2, 2026-06-30). Returns `true` if either
     * (a) the sticky REST window is currently armed
     * ([mode2StickyRestActive] == true) or (b) recovery is pending or
     * in flight ([stickyRecovery] != [StickyRecoveryState.None]).
     *
     * Used by the Android-side `AppContainer.triggerDebugForceMode2`
     * wrapper to refuse an operator-initiated L1 synthetic trigger
     * while a previous Mode 2 actuation's sticky window or recovery
     * probation is in flight.
     *
     * Threading: pure-read snapshot accessor; the two backing fields are
     * `@Volatile`. The read is a best-effort snapshot -- see the L1
     * mini-lock §7.2 D-1 verdict for why the race is benign.
     */
    val isStickyOrRecoveryActive: Boolean
        get() = mode2StickyRestActive ||
            stickyRecovery != StickyRecoveryState.None

    init {
        // R3.6 build-time invariant: sticky requires fast-path to be enabled.
        require(!(mode2StickyEnabled && !mode2FastPathEnabled)) {
            "MODE_2_STICKY_ENABLED requires MODE_2_FAST_PATH_ENABLED"
        }
        // RC-RECONNECT-QUIESCENCE1: gate requires sticky.
        require(!(reconnectQuiescenceEnabled && !mode2StickyEnabled)) {
            "RECONNECT_QUIESCENCE_ENABLED requires MODE_2_STICKY_ENABLED"
        }
    }

    /**
     * Submit an [Event] to the state machine.
     *
     * Stage 2 B4: the whole handler runs under [eventMutex] and the call
     * returns the [Transition] it caused, or `null` when the mode did not
     * change. Idempotent w.r.t. duplicate events (a second frame while in
     * candidate is a no-op; a stale signal is ignored by freshness).
     */
    suspend fun onEvent(event: Event): Transition? {
        lockTraceForTest?.invoke("eventMutex:acquire")
        return eventMutex.withLock {
            try {
                val from = current
                lastSwitchReason = ""
                try {
                    when (event) {
                        is Event.WsSessionConnected -> onWsSessionConnected(event)
                        is Event.WsSessionEnded -> onWsSessionEnded(event)
                        is Event.WsFrameTextReceived -> onWsFrameText(event)
                        is Event.NetworkChanged -> onNetworkChanged(event)
                        is Event.WsOutboundAckReceived -> onWsOutboundAck(event)
                        is Event.WsPongReceived -> onWsPong(event)
                        is Event.WsCandidateProbeRoundTrip -> onWsCandidateProbeRoundTrip(event)
                        is Event.WsAliveTickElapsed -> onAliveTick(event)
                        is Event.ActiveOutboundAckTimeout -> onActiveOutboundAckTimeout(event)
                        is Event.InboundIdleTimeout -> onInboundIdleTimeout(event)
                        is Event.WsSessionInvalidated -> onWsSessionInvalidated(event)
                        is Event.RestPollDegraded -> onRestPollDegraded(event)
                    }
                } finally {
                    publishSnapshot()
                }
                val to = current
                if (from != to) Transition(from, to, lastSwitchReason) else null
            } finally {
                lockTraceForTest?.invoke("eventMutex:release")
            }
        }
    }

    private fun publishSnapshot() {
        val next = RestModeSnapshot(
            mode = current,
            recoveryCause = recoveryCause,
            liveSessionEpoch = liveSession?.sessionEpoch,
            candidateEpoch = candidate?.sessionEpoch,
            provenSessionEpoch = proofSession?.sessionEpoch,
            reconnectRequested = reconnectRequested,
        )
        if (_snapshot.value != next) {
            _snapshot.value = next
            log(
                "REST_TRACE presentation mode=${next.mode} cause=${next.recoveryCause} " +
                    "live=${next.liveSessionEpoch ?: "none"} candidate=${next.candidateEpoch ?: "none"} " +
                    "proven=${next.provenSessionEpoch ?: "none"} reconnect_requested=${next.reconnectRequested}",
            )
        }
    }

    /** B2: an id-bearing event is handled iff it names the live session. */
    private fun isLive(sessionEpoch: Long, kind: String): Boolean {
        val live = liveSession
        if (live != null && live.sessionEpoch == sessionEpoch) return true
        log(
            "REST_TRACE stale_session_signal_ignored kind=$kind event_epoch=$sessionEpoch " +
                "live_epoch=${live?.sessionEpoch ?: "none"}",
        )
        return false
    }

    /**
     * Trek 2 Stage 2B-B (C5, L9) — read-only telemetry signal that
     * the REST poll breaker entered [LongPollBreakerState.Open].
     * The state machine does NOT transition `RestMode` on this event
     * (scope §L9: "RestStateMachine does NOT transition RestMode
     * purely because of breaker state"). The handler logs for
     * diagnostic separation and forwards through the existing
     * [onModeSwitched] surface so the AppContainer wire-up can mirror
     * the reason into the
     * [phantom.core.transport.WsDegradationDetector] stream.
     */
    private fun onRestPollDegraded(event: Event.RestPollDegraded) {
        log("REST_TRACE rest_poll_degraded reason=${event.reason}")
        // Trek 2 Stage 2B-B (C5-C review-fix P2) — fire the
        // observation hook. Previous shape only logged, so the
        // wire-up's typed degradation surface never received the
        // signal. The callback fires AFTER the log line so the
        // log remains the audit-trail anchor.
        onRestPollDegraded?.invoke(event.reason)
    }

    /**
     * Sealed outcome of the single-lock `onWsSessionConnected`
     * critical section. Computed atomically inside [gateLock]; logs +
     * `transitionToCandidate` happen AFTER unlock so the lock does
     * not bracket any side effect.
     */
    private sealed interface ConnectedOutcome {
        /** Stale-epoch dedup hit; ignore without further work. */
        data class StaleEpochIgnored(val ignoredEpoch: Long, val lastObservedEpochAtCheck: Long) :
            ConnectedOutcome
        /** Quiescence gate held a ProbeClaimed for a different owner / stale routeEpoch. */
        data class OwnerMismatch(val logLine: String) : ConnectedOutcome
        /** Sticky recovery advanced (no gate transition needed — gate is not ProbeClaimed). */
        data class StickyAdvancedNoGate(val log: String?, val transitionToCandidate: Boolean) :
            ConnectedOutcome
        /** Sticky recovery advanced AND the legitimate owner's probe was consumed → CandidateProving. */
        data class StickyAdvancedAndGateConsumed(
            val log: String?,
            val transitionToCandidate: Boolean,
            val gate: WsReconnectGate.CandidateProving,
        ) : ConnectedOutcome
    }

    private suspend fun onWsSessionConnected(event: Event.WsSessionConnected) {
        // ─── Single-lock atomic transaction ──────────────────────────
        // Holds [gateLock] across:
        //   1. stale-epoch dedup (this is the B2 freshness floor);
        //   2. quiescence gate-bypass reject;
        //   3. lastObservedEpoch update;
        //   4. stickyRecovery state advance;
        //   5. gate transition (ProbeClaimed → CandidateProving when
        //      the owner matches).
        // Logs + `transitionToCandidate` + `onModeSwitched` callback run
        // AFTER unlock — keeping the critical section short and
        // preventing a logging callback from self-deadlocking.
        val outcome: ConnectedOutcome = withGateLock {
            if (event.sessionEpoch <= lastObservedEpoch) {
                return@withGateLock ConnectedOutcome.StaleEpochIgnored(
                    ignoredEpoch = event.sessionEpoch,
                    lastObservedEpochAtCheck = lastObservedEpoch,
                )
            }

            // P1 (seventh round): quiescence gate-bypass guard. When
            // [reconnectQuiescenceEnabled], a `WsSessionConnected`
            // event is ONLY accepted when:
            //   - gate is [WsReconnectGate.Open] — no sticky engaged,
            //     ordinary reconnect flow;
            //   - gate is [WsReconnectGate.ProbeClaimed] AND the event's
            //     `connectionGeneration` matches `ownerGeneration` AND
            //     the gate's `routeEpoch` matches the state's.
            //
            // All other gate states ([Quiesced], [ProbeAvailable],
            // [CandidateProving]) MUST reject the event.
            if (reconnectQuiescenceEnabled) {
                val gateSnapshot = _gate.value
                val acceptedShape: Boolean = when (gateSnapshot) {
                    is WsReconnectGate.Open -> true
                    is WsReconnectGate.ProbeClaimed ->
                        gateSnapshot.ownerGeneration == event.connectionGeneration &&
                            gateSnapshot.routeEpoch == this.routeEpoch
                    is WsReconnectGate.Quiesced,
                    is WsReconnectGate.ProbeAvailable,
                    is WsReconnectGate.CandidateProving -> false
                }
                if (!acceptedShape) {
                    return@withGateLock ConnectedOutcome.OwnerMismatch(
                        logLine = "REST_TRACE ws_recovery_connect_rejected " +
                            "reason=gate_bypass " +
                            "gate=${gateSnapshot.simpleKind()} " +
                            "event_owner=${event.connectionGeneration} " +
                            "event_session_epoch=${event.sessionEpoch} " +
                            "state_route_epoch=${this.routeEpoch}",
                    )
                }
            }

            // Commit the monotonic-floor advance ONLY after the gate
            // validation passes — a rejected event must NOT advance
            // `lastObservedEpoch`.
            lastObservedEpoch = event.sessionEpoch

            // Sticky-recovery state advance.
            var stickyAdvanceLog: String? = null
            var doTransitionToCandidate = false
            when (stickyRecovery) {
                StickyRecoveryState.None -> Unit
                StickyRecoveryState.PendingNewSession -> {
                    stickyRecovery = StickyRecoveryState.InFlight
                    recoveryWsEpoch = event.sessionEpoch
                    recoveryStartedAtMs = now()
                    doTransitionToCandidate = true
                    stickyAdvanceLog =
                        "REST_TRACE sticky_recovery_started gen=$stickyGen " +
                            "reason=new_ws_session ws_epoch=${event.sessionEpoch}"
                }
                StickyRecoveryState.InFlight -> {
                    stickyAdvanceLog =
                        "REST_TRACE sticky_recovery_restarted gen=$stickyGen " +
                            "reason=new_ws_session_during_recovery " +
                            "old_epoch=$recoveryWsEpoch new_epoch=${event.sessionEpoch}"
                    recoveryWsEpoch = event.sessionEpoch
                    recoveryStartedAtMs = now()
                }
            }

            // Gate consumption runs AFTER the sticky branch and INDEPENDENTLY:
            // a legitimate owner-validated connect must consume its
            // ProbeClaimed → CandidateProving even when the
            // sticky-recovery state machine is already InFlight.
            val gateConsumed: WsReconnectGate.CandidateProving? = run {
                val current = _gate.value
                if (reconnectQuiescenceEnabled && current is WsReconnectGate.ProbeClaimed) {
                    val next = WsReconnectGate.CandidateProving(
                        stickyGen = current.stickyGen,
                        sessionEpoch = event.sessionEpoch,
                    )
                    probeAttemptCount = 0
                    _gate.value = next
                    next
                } else {
                    null
                }
            }

            if (gateConsumed != null) {
                ConnectedOutcome.StickyAdvancedAndGateConsumed(
                    log = stickyAdvanceLog,
                    transitionToCandidate = doTransitionToCandidate,
                    gate = gateConsumed,
                )
            } else {
                ConnectedOutcome.StickyAdvancedNoGate(
                    log = stickyAdvanceLog,
                    transitionToCandidate = doTransitionToCandidate,
                )
            }
        }
        // ─── End atomic transaction; effects run OUTSIDE the lock ────
        when (outcome) {
            is ConnectedOutcome.StaleEpochIgnored -> {
                log(
                    "REST_TRACE sticky_recovery_stale_connect_ignored " +
                        "gen=$stickyGen last_observed_epoch=${outcome.lastObservedEpochAtCheck} " +
                        "event_epoch=${outcome.ignoredEpoch}"
                )
                log(
                    "REST_TRACE stale_connect_ignored event_epoch=${outcome.ignoredEpoch} " +
                        "live_epoch=${liveSession?.sessionEpoch ?: "none"}",
                )
                return
            }
            is ConnectedOutcome.OwnerMismatch -> {
                log(outcome.logLine)
                return
            }
            is ConnectedOutcome.StickyAdvancedNoGate -> Unit
            is ConnectedOutcome.StickyAdvancedAndGateConsumed -> Unit
        }

        // ─── Stage 2: the accepted session becomes live ──────────────
        val accepted = WsSessionId(event.sessionEpoch)
        val previous = liveSession
        if (previous != null && accepted > previous) {
            // B3 invalidation by `Connected(newer)`: the session that held
            // the proof (or the candidate) is gone before its `Ended`
            // arrived; that late `Ended` will be stale.
            invalidateLive(reason = "session_replaced")
        }
        liveSession = accepted
        reconnectRequested = false
        if (current != RestMode.WsActive) recoveryCause = RestRecoveryCause.FailureOrUnknown

        val stickyEffect = when (outcome) {
            is ConnectedOutcome.StickyAdvancedNoGate -> outcome.transitionToCandidate
            is ConnectedOutcome.StickyAdvancedAndGateConsumed -> outcome.transitionToCandidate
            else -> false
        }
        if (stickyEffect) transitionToCandidate(accepted, "ws_recovery_probation")
        when (outcome) {
            is ConnectedOutcome.StickyAdvancedNoGate -> outcome.log?.let(log)
            is ConnectedOutcome.StickyAdvancedAndGateConsumed -> {
                outcome.log?.let(log)
                emitGateTelemetry(outcome.gate, reason = "candidate_connected")
            }
            else -> Unit
        }

        when (current) {
            RestMode.RestActive -> {
                // A sticky window that is armed and NOT yet in flight holds
                // RestActive until a route change (R3.6); otherwise a new
                // session is a candidate that must prove itself (B3).
                val stickyHolds = mode2StickyEnabled && mode2StickyRestActive &&
                    stickyRecovery != StickyRecoveryState.InFlight
                if (!stickyHolds) transitionToCandidate(accepted, "ws_connected")
            }
            RestMode.WsCandidate -> transitionToCandidate(accepted, "ws_connected")
            RestMode.WsActive ->
                // Unreachable by construction: a proven session that is
                // replaced was invalidated above, and there is no other way
                // into `WsActive` without a live session. Kept as a
                // candidate transition rather than an error so a future
                // path cannot silently inherit a proof.
                transitionToCandidate(accepted, "ws_connected")
        }
    }

    /**
     * Drop the live session and everything that referred to it. The mode
     * follows: a proof held by that session cannot survive it (I1), a
     * candidate cannot be proven by it (S2).
     */
    private fun invalidateLive(reason: String) {
        recoveryRequired = true
        recoveryCause = RestRecoveryCause.FailureOrUnknown
        when (current) {
            RestMode.WsActive -> transitionToRest("session_invalidated:$reason")
            RestMode.WsCandidate -> transitionToRest("candidate_session_regression")
            RestMode.RestActive -> Unit
        }
        liveSession = null
        candidate = null
        candidateHasFrame = false
        proofSession = null
    }

    private suspend fun onWsSessionEnded(event: Event.WsSessionEnded) {
        // R3.6: during sticky InFlight recovery, the recovery candidate's
        // own close fails the recovery; a close of any other session is a
        // stale close and is ignored with the R3.6 log line.
        if (current == RestMode.WsCandidate &&
            mode2StickyEnabled && stickyRecovery == StickyRecoveryState.InFlight
        ) {
            if (event.sessionEpoch != recoveryWsEpoch) {
                log(
                    "REST_TRACE sticky_recovery_stale_close_ignored " +
                        "gen=$stickyGen recovery_epoch=$recoveryWsEpoch " +
                        "event_epoch=${event.sessionEpoch}"
                )
                return
            }
            val elapsedMs = now() - recoveryStartedAtMs
            log(
                "REST_TRACE sticky_recovery_failed gen=$stickyGen " +
                    "reason=candidate_session_regression " +
                    "ws_epoch=${event.sessionEpoch} " +
                    "elapsed_ms_since_recovery=$elapsedMs"
            )
            stickyRecovery = StickyRecoveryState.None
            recoveryWsEpoch = -1L
            invalidateLive(reason = "ws_session_ended")
            // RC-RECONNECT-QUIESCENCE1: the candidate session that was
            // proving recovery died. Gate flips back to Quiesced; sticky
            // stays armed. Coordinator will issue a fresh probe on the
            // next route change.
            if (reconnectQuiescenceEnabled) {
                val deadEpoch = event.sessionEpoch
                applyGateUpdate { current ->
                    if (current is WsReconnectGate.CandidateProving &&
                        current.sessionEpoch == deadEpoch
                    ) {
                        WsReconnectGate.Quiesced(stickyGen = current.stickyGen) to
                            "candidate_died"
                    } else {
                        null
                    }
                }
            }
            return
        }
        if (!isLive(event.sessionEpoch, kind = "Ended")) return

        // Review round 7: the signature describes how the SESSION died --
        // zero inbound frames, a ping-timeout close, a duration inside the
        // Mode-2 window. Since a session must now prove itself before the
        // mode can be `WsActive`, a Mode-2 death most often lands while
        // that session is still the candidate; reading the signature only
        // in `WsActive` would have made the fast path unreachable on the
        // very shape it was written for.
        if (current != RestMode.RestActive) {
            // 3.6 Fast REST degradation (2026-06-18). The Mode-2 signature
            // telemetry ALWAYS fires when the signature matches; with the
            // flag on the transition carries the `mode_2_fast_path` reason
            // and arms the sticky window. Stage 2: with the flag off the
            // session end still degrades (I1) -- the counters that used to
            // wait for two or three zero-frame sessions are gone.
            val signatureMatches = event.inboundFrames == 0 &&
                event.okhttpPingTimeoutDetected &&
                event.durationMs in MODE_2_MIN_DURATION_MS..MODE_2_MAX_DURATION_MS
            if (signatureMatches) {
                val action = if (mode2FastPathEnabled) "fast_path" else "observe_only"
                log(
                    "REST_TRACE mode_2_signature_matched action=$action " +
                        "duration_ms=${event.durationMs} " +
                        "inbound_frames=${event.inboundFrames} " +
                        "pending_acks=${event.pendingAcksAtClose}",
                )
                if (mode2FastPathEnabled) {
                    recoveryRequired = true
                    recoveryCause = RestRecoveryCause.FailureOrUnknown
                    transitionToRest("mode_2_fast_path")
                    liveSession = null
                    candidate = null
                    candidateHasFrame = false
                    proofSession = null
                    // R3.6: arm sticky AFTER the transition so the state is
                    // already RestActive when armSticky fires.
                    if (mode2StickyEnabled) {
                        armSticky()
                    }
                    return
                }
            }
            log(
                "REST_TRACE session_end_degrades inbound_frames=${event.inboundFrames} " +
                    "pending_acks=${event.pendingAcksAtClose} duration_ms=${event.durationMs}",
            )
        }
        invalidateLive(reason = "ws_session_ended")
    }

    private fun onWsFrameText(event: Event.WsFrameTextReceived) {
        if (!isLive(event.sessionEpoch, kind = "Frame")) return
        when (current) {
            RestMode.RestActive -> {
                // R3.6: while sticky is armed AND we are in RestActive, suppress the
                // frame-text-received upgrade signal. Raw Frame.Text is NOT evidence
                // of a healthy Direct WSS (locked invariant #1). Only ws_alive_60s on
                // the recovery candidate session can clear the sticky window.
                if (mode2StickyEnabled && mode2StickyRestActive) {
                    if (!stickyFrameSuppressedLogged) {
                        stickyFrameSuppressedLogged = true
                        val elapsedMs = now() - stickyArmedAtMs
                        log(
                            "REST_TRACE sticky_frame_suppressed gen=$stickyGen " +
                                "elapsed_ms=$elapsedMs"
                        )
                    }
                    stickySuppressedCount++
                    return
                }
                transitionToCandidate(WsSessionId(event.sessionEpoch), "ws_frame_text_received")
                candidateHasFrame = true
            }
            RestMode.WsCandidate -> {
                if (candidate?.sessionEpoch == event.sessionEpoch) candidateHasFrame = true
            }
            RestMode.WsActive -> Unit // frames are expected
        }
    }

    private fun onNetworkChanged(event: Event.NetworkChanged) {
        // B5: the proof, if any, is lost -- a new network's behaviour is
        // unknown. Never enters candidate; the next accepted Connected
        // does. Applies before the sticky bookkeeping so a sticky window
        // that is armed sees the same invalidation.
        recoveryRequired = true
        recoveryCause = RestRecoveryCause.FailureOrUnknown
        reconnectRequested = true
        if (liveSession != null || current != RestMode.RestActive) {
            invalidateLive(reason = "network_changed")
        }
        log("REST_TRACE network_changed generation=${event.networkGeneration} clears_sticky=${event.clearsMode2Sticky}")

        // R3.6: sticky branching on clearsMode2Sticky flag.
        if (mode2StickyEnabled && mode2StickyRestActive) {
            if (!event.clearsMode2Sticky) {
                // Route-change that does NOT clear sticky (e.g. VALIDATED_CHANGED).
                // Keep sticky armed; do NOT lift to WsCandidate.
                log("REST_TRACE sticky_kept gen=$stickyGen reason=validated_change")
                return
            }
            // Route change that clears sticky — arm recovery.
            when (stickyRecovery) {
                StickyRecoveryState.None -> {
                    stickyRecovery = StickyRecoveryState.PendingNewSession
                    log(
                        "REST_TRACE sticky_recovery_pending gen=$stickyGen " +
                            "reason=route_change"
                    )
                }
                StickyRecoveryState.PendingNewSession -> {
                    // Already pending — restart the pending phase.
                    log(
                        "REST_TRACE sticky_recovery_pending_restarted gen=$stickyGen " +
                            "reason=route_change_during_pending"
                    )
                }
                StickyRecoveryState.InFlight -> {
                    // Already in flight — restart recovery on route change.
                    log(
                        "REST_TRACE sticky_recovery_restarted gen=$stickyGen " +
                            "reason=route_change_during_recovery"
                    )
                    stickyRecovery = StickyRecoveryState.PendingNewSession
                    recoveryWsEpoch = -1L
                }
            }
        }
    }

    private suspend fun onWsOutboundAck(event: Event.WsOutboundAckReceived) {
        if (!isLive(event.sessionEpoch, kind = "Ack")) return
        // R3.6: during sticky InFlight recovery, ws_outbound_ack is NOT a proof
        // signal (locked invariant #9). Short-circuit before the existing
        // transitionToWsActive call so the candidate's probe timer still runs.
        if (mode2StickyEnabled && stickyRecovery == StickyRecoveryState.InFlight &&
            current == RestMode.WsCandidate
        ) {
            log(
                "REST_TRACE sticky_recovery_ack_ignored gen=$stickyGen " +
                    "reason=outbound_routes_via_rest_no_epoch"
            )
            return
        }
        val id = WsSessionId(event.sessionEpoch)
        when (current) {
            RestMode.RestActive -> {
                // An ACK round-trip on the live session is stronger than a
                // frame: it enters candidate and proves it in one step,
                // unless a sticky window holds RestActive.
                if (mode2StickyEnabled && mode2StickyRestActive) {
                    log("REST_TRACE sticky_ack_suppressed gen=$stickyGen")
                    return
                }
                transitionToCandidate(id, "ws_outbound_ack")
                candidateHasFrame = true
                if (candidate == liveSession) transitionToWsActive("ws_outbound_ack")
            }
            RestMode.WsCandidate -> {
                candidateHasFrame = true
                if (candidate == liveSession && candidate == id) transitionToWsActive("ws_outbound_ack")
            }
            RestMode.WsActive -> recoveryRequired = false
        }
    }

    private fun onWsPong(event: Event.WsPongReceived) {
        // Liveness only (product decision): never proof, never candidate entry.
        isLive(event.sessionEpoch, kind = "Pong")
    }

    private fun onWsCandidateProbeRoundTrip(event: Event.WsCandidateProbeRoundTrip) {
        if (!isLive(event.sessionEpoch, kind = "CandidateProof")) return
        if (current != RestMode.WsCandidate) return
        val proving = candidate ?: return
        if (proving.sessionEpoch != event.sessionEpoch || proving != liveSession) return

        // The transport correlated this reply with the one probe sent for
        // this exact session. It proves bidirectional application traffic,
        // but it does not waive the stability dwell: onAliveTick remains the
        // only path that commits a quiet candidate after CANDIDATE_COMMIT_MS.
        candidateHasFrame = true
        log("REST_TRACE candidate_probe_round_trip epoch=${event.sessionEpoch}")
    }

    private suspend fun onAliveTick(event: Event.WsAliveTickElapsed) {
        if (current != RestMode.WsCandidate) return
        val proving = candidate ?: return
        if (proving.sessionEpoch != event.sessionEpoch || proving != liveSession) {
            log(
                "REST_TRACE stale_alive_tick_ignored tick_epoch=${event.sessionEpoch} " +
                    "candidate=${proving.sessionEpoch} live=${liveSession?.sessionEpoch ?: "none"}",
            )
            return
        }
        val entered = candidateEnteredAtMs ?: return
        val elapsed = now() - entered
        if (elapsed < CANDIDATE_COMMIT_MS) return
        if (!candidateHasFrame) {
            log("REST_TRACE candidate_dwell_without_frame epoch=${proving.sessionEpoch} elapsed_ms=$elapsed")
            return
        }
        transitionToWsActive("ws_alive_60s")
    }

    /**
     * PR-D1d: a per-envelope ACK deadline expired on the live session.
     *
     * - In [RestMode.WsActive]: immediate mode switch to REST; the session
     *   is silent, not gone, so [liveSession] is kept.
     * - In [RestMode.WsCandidate]: the candidate's proof is cancelled
     *   (B3); the session is kept for the same reason.
     * - In [RestMode.RestActive]: evidence only -- a late failure replaces
     *   a silence-only cause.
     */
    private fun onActiveOutboundAckTimeout(event: Event.ActiveOutboundAckTimeout) {
        if (!isLive(event.sessionEpoch, kind = "AckDeadline")) return
        recoveryRequired = true
        recoveryCause = RestRecoveryCause.FailureOrUnknown
        when (current) {
            RestMode.WsActive -> transitionToRest("active_outbound_ack_timeout")
            RestMode.WsCandidate -> transitionToRest("candidate_ack_timeout")
            RestMode.RestActive -> Unit
        }
    }

    /**
     * PR-RECV-DIAG1 v1.6 — inbound-stall fallback, keyed by session.
     * The WS handshake succeeded but no server-pushed frame arrived for
     * the stall threshold. In [RestMode.WsActive] the mode drops to REST
     * with the `InboundSilence` cause when no failure was recorded; in
     * [RestMode.WsCandidate] the proof is cancelled the same way (B3).
     * The session is kept: the transport's watchdogs own a silent socket.
     */
    private fun onInboundIdleTimeout(event: Event.InboundIdleTimeout) {
        if (!isLive(event.sessionEpoch, kind = "Stalled")) return
        when (current) {
            RestMode.WsActive -> transitionToRest("inbound_idle_timeout")
            RestMode.WsCandidate -> transitionToRest("inbound_idle_timeout")
            RestMode.RestActive -> Unit
        }
    }

    private fun onWsSessionInvalidated(event: Event.WsSessionInvalidated) {
        if (!isLive(event.sessionEpoch, kind = "Invalidated")) return
        log("REST_TRACE ws_session_invalidated epoch=${event.sessionEpoch} reason=${event.reason}")
        invalidateLive(reason = event.reason)
    }

    // ── Transition helpers ───────────────────────────────────────────────────

    /**
     * R3.6: arm the sticky REST window. NOT idempotent — must only be called
     * once per Mode-2 fast-path actuation, AFTER [transitionToRest] has moved
     * the state to [RestMode.RestActive]. Asserts [stickyRecovery] is [StickyRecoveryState.None].
     */
    private suspend fun armSticky() {
        check(stickyRecovery == StickyRecoveryState.None) {
            "armSticky called when stickyRecovery=$stickyRecovery (must be None)"
        }
        stickyGen++
        mode2StickyRestActive = true
        stickyArmedAtMs = now()
        stickyFrameSuppressedLogged = false
        stickySuppressedCount = 0
        log("REST_TRACE sticky_armed gen=$stickyGen reason=mode_2_fast_path")

        // RC-RECONNECT-QUIESCENCE1: engage the gate iff the current
        // transport kind is Direct. Reality / Tor have their own failure
        // modes; this gate MUST NOT engage on those transports.
        if (reconnectQuiescenceEnabled && currentKindProvider() == TransportKind.Direct) {
            val targetStickyGen = stickyGen
            applyGateUpdate { current ->
                // Always engage from Open. If already Quiesced or in any
                // probe state (race with a stale path), the new
                // mode_2_fast_path actuation overrides and resets to the
                // current stickyGen.
                if (current is WsReconnectGate.Quiesced && current.stickyGen == targetStickyGen) {
                    null
                } else {
                    WsReconnectGate.Quiesced(targetStickyGen) to "mode_2_fast_path"
                }
            }
        }
    }

    /**
     * Two-phase gate update:
     *   1. Acquire [gateLock]; compute next state via [transform] which
     *      may read/write `routeEpoch`, `connectionGenerationCounter`,
     *      `probeAttemptCount`, etc. Return `null` to skip the update.
     *   2. Release the lock.
     *   3. Emit the typed telemetry log AFTER unlock, so a logging
     *      callback that (transitively) wants the lock cannot
     *      self-deadlock and so a slow log writer cannot extend the
     *      critical section.
     *
     * The transition reset (probe-attempt counter on exit from
     * [WsReconnectGate.ProbeClaimed]) runs INSIDE the lock so the
     * counter reset is observed atomically with the gate change.
     *
     * Returns the published gate (the same value [transform] returned).
     * Returns null if [transform] returned null (no-op).
     */
    private suspend fun applyGateUpdate(
        transform: (current: WsReconnectGate) -> Pair<WsReconnectGate, String>?,
    ): Pair<WsReconnectGate, String>? {
        val published = withGateLock {
            val current = _gate.value
            val pair = transform(current) ?: return@withGateLock null
            val (next, _) = pair
            if (next == current) return@withGateLock null
            if (current is WsReconnectGate.ProbeClaimed && next !is WsReconnectGate.ProbeClaimed) {
                probeAttemptCount = 0
            }
            _gate.value = next
            pair
        } ?: return null

        val (next, reason) = published
        emitGateTelemetry(next, reason)
        return published
    }

    /**
     * Telemetry emission for a gate transition. Runs OUTSIDE [gateLock]
     * so the log callback cannot self-deadlock and so a slow writer
     * does not extend the critical section. Reads only the already-
     * published [next] state; does not touch shared mutables.
     */
    private fun emitGateTelemetry(next: WsReconnectGate, reason: String) {
        when (next) {
            is WsReconnectGate.Quiesced ->
                log("REST_TRACE ws_reconnect_quiesced gen=${next.stickyGen} reason=$reason")
            is WsReconnectGate.ProbeAvailable ->
                log(
                    "REST_TRACE ws_recovery_probe_granted gen=${next.stickyGen} " +
                        "route_epoch=${next.routeEpoch} " +
                        "budget_attempts=${next.budget.maxAttempts} " +
                        "budget_ms=${next.budget.maxElapsedMs}",
                )
            is WsReconnectGate.ProbeClaimed -> Unit // probe-internal; no telemetry
            is WsReconnectGate.CandidateProving ->
                log(
                    "REST_TRACE ws_reconnect_resumed sticky_gen=${next.stickyGen} " +
                        "session_epoch=${next.sessionEpoch}",
                )
            WsReconnectGate.Open ->
                log("REST_TRACE ws_reconnect_open proof=$reason")
        }
    }

    private fun transitionToRest(reason: String) {
        val from = _state.value
        if (from == RestMode.RestActive) return
        recoveryCause = if (reason == "inbound_idle_timeout" && !recoveryRequired) {
            RestRecoveryCause.InboundSilence
        } else {
            RestRecoveryCause.FailureOrUnknown
        }
        candidateEnteredAtMs = null
        candidate = null
        candidateHasFrame = false
        proofSession = null
        _state.value = RestMode.RestActive
        lastSwitchReason = reason
        log("REST_TRACE mode_switched from=$from to=REST_ACTIVE reason=$reason")
        onModeSwitched?.invoke(from, RestMode.RestActive, reason)
    }

    /**
     * B3 candidate entry. Re-entry with the SAME id is a no-op (the
     * clock keeps running); a different id refreshes the clock and the
     * frame evidence, because proof belongs to one session.
     */
    private fun transitionToCandidate(id: WsSessionId, reason: String) {
        val from = _state.value
        if (from == RestMode.WsCandidate && candidate == id) return
        candidate = id
        candidateHasFrame = false
        candidateEnteredAtMs = now()
        proofSession = null
        if (from == RestMode.WsCandidate) {
            log("REST_TRACE candidate_refreshed epoch=${id.sessionEpoch} reason=$reason")
            return
        }
        _state.value = RestMode.WsCandidate
        lastSwitchReason = reason
        log("REST_TRACE mode_switched from=$from to=WS_CANDIDATE reason=$reason epoch=${id.sessionEpoch}")
        onModeSwitched?.invoke(from, RestMode.WsCandidate, reason)
    }

    /**
     * Three-valued result of the `ws_alive_60s` proof step inside
     * [transitionToWsActive].
     *
     *   - [NotApplicable] — this `transitionToWsActive` call is not a
     *     sticky-recovery proof (e.g. reason is `ws_outbound_ack` or
     *     sticky is not engaged). The Stage 2 `WsActive` transition
     *     runs unchanged.
     *   - [Committed] — sticky was cleared, gate flipped to Open, the
     *     `WsActive` transition fires.
     *   - [Rejected] — the proof was rejected (gate or sticky-epoch
     *     advanced before the probation tick landed). The
     *     `transitionToWsActive` call returns IMMEDIATELY without
     *     touching `_state` or the mode-switched callback.
     */
    private sealed interface ProofOutcome {
        object NotApplicable : ProofOutcome
        object Committed : ProofOutcome
        object Rejected : ProofOutcome
    }

    private suspend fun transitionToWsActive(reason: String) {
        val from = _state.value
        if (from == RestMode.WsActive) return
        val proving = candidate
        check(proving != null && proving == liveSession) {
            "transitionToWsActive without a live candidate (candidate=$candidate live=$liveSession)"
        }
        // R3.6 cleanup hook + RC-RECONNECT-QUIESCENCE1 ws_alive_60s
        // proof transition.
        //
        // P1 (eighth round): if the proof is rejected (gate or epoch
        // advanced), the whole `transitionToWsActive` call MUST be a
        // no-op — return immediately, no `_state` mutation, no
        // `onModeSwitched` callback.
        val outcome: ProofOutcome =
            if (mode2StickyEnabled && mode2StickyRestActive &&
                stickyRecovery == StickyRecoveryState.InFlight &&
                reason == "ws_alive_60s"
            ) {
                var clearedLog: String? = null
                val inLock: ProofOutcome = withGateLock {
                    if (stickyRecovery != StickyRecoveryState.InFlight) {
                        return@withGateLock ProofOutcome.NotApplicable
                    }
                    if (!reconnectQuiescenceEnabled) {
                        // Quiescence not engaged — the R3.6 contract
                        // commits the sticky cleanup unconditionally.
                        ProofOutcome.Committed
                    } else {
                        val currentGate = _gate.value
                        val match = currentGate is WsReconnectGate.CandidateProving &&
                            currentGate.stickyGen == stickyGen &&
                            currentGate.sessionEpoch == recoveryWsEpoch
                        if (!match) {
                            clearedLog =
                                "REST_TRACE ws_recovery_proof_rejected " +
                                    "reason=gate_or_epoch_advanced " +
                                    "gate=${currentGate.simpleKind()} " +
                                    "expected_sticky_gen=$stickyGen " +
                                    "expected_session_epoch=$recoveryWsEpoch"
                            ProofOutcome.Rejected
                        } else {
                            val elapsedMs = now() - stickyArmedAtMs
                            clearedLog =
                                "REST_TRACE sticky_cleared gen=$stickyGen " +
                                    "reason=ws_recovery_proved proof=ws_alive_60s " +
                                    "total_suppressed=$stickySuppressedCount " +
                                    "elapsed_ms_since_arm=$elapsedMs"
                            // Atomic gate transition + sticky cleanup,
                            // committed together inside the lock.
                            mode2StickyRestActive = false
                            stickyRecovery = StickyRecoveryState.None
                            recoveryWsEpoch = -1L
                            recoveryStartedAtMs = 0L
                            stickyFrameSuppressedLogged = false
                            stickySuppressedCount = 0
                            _gate.value = WsReconnectGate.Open
                            ProofOutcome.Committed
                        }
                    }
                }
                clearedLog?.let(log)
                if (inLock == ProofOutcome.Committed && !reconnectQuiescenceEnabled) {
                    // Non-quiescence path: cleanup runs OUTSIDE the
                    // lock since there is no gate to mutate.
                    val elapsedMs = now() - stickyArmedAtMs
                    log(
                        "REST_TRACE sticky_cleared gen=$stickyGen " +
                            "reason=ws_recovery_proved proof=ws_alive_60s " +
                            "total_suppressed=$stickySuppressedCount " +
                            "elapsed_ms_since_arm=$elapsedMs"
                    )
                    mode2StickyRestActive = false
                    stickyRecovery = StickyRecoveryState.None
                    recoveryWsEpoch = -1L
                    recoveryStartedAtMs = 0L
                    stickyFrameSuppressedLogged = false
                    stickySuppressedCount = 0
                }
                if (inLock == ProofOutcome.Committed && reconnectQuiescenceEnabled) {
                    // Telemetry emission OUTSIDE the lock per the
                    // existing `emitGateTelemetry` discipline.
                    emitGateTelemetry(WsReconnectGate.Open, "ws_alive_60s")
                }
                inLock
            } else {
                ProofOutcome.NotApplicable
            }

        // P1 (eighth round): on Rejected the whole transition is a
        // no-op. `RestMode` stays where it was (RestActive or
        // WsCandidate); long-poll continues; sticky recovery keeps
        // waiting for a fresh probe.
        if (outcome == ProofOutcome.Rejected) {
            return
        }

        recoveryRequired = false
        recoveryCause = RestRecoveryCause.FailureOrUnknown
        candidateEnteredAtMs = null
        proofSession = proving
        candidate = null
        candidateHasFrame = false
        _state.value = RestMode.WsActive
        lastSwitchReason = reason
        log("REST_TRACE mode_switched from=$from to=WS_ACTIVE reason=$reason epoch=${proving.sessionEpoch}")
        onModeSwitched?.invoke(from, RestMode.WsActive, reason)
    }

    /**
     * Inputs the state machine consumes. Stage 2: every event that is
     * about a WS session carries the session's epoch, and the machine
     * handles it only if that epoch is the live session's (B2).
     */
    sealed class Event {
        /**
         * R3.6 (2026-06-20) + RC-RECONNECT-QUIESCENCE1 (2026-06-22).
         *
         * [sessionEpoch] matches the per-session counter in
         * [KtorRelayTransport]. Stage 2: accepted iff newer than every
         * session observed so far; the accepted session becomes the live
         * session.
         *
         * [connectionGeneration] is the reconnect-loop's owner generation
         * (allocated once per `runReconnectLoop` invocation via
         * [allocateConnectionGeneration]) and carried unchanged through
         * every auth-retry inside that loop. When a connect event arrives
         * while the gate is [WsReconnectGate.ProbeClaimed], the gate
         * transitions to [WsReconnectGate.CandidateProving] ONLY if the
         * event's [connectionGeneration] matches the claim's
         * `ownerGeneration` AND the claim's `routeEpoch` matches the
         * state-machine's current `routeEpoch`.
         *
         * Default `-1L` keeps legacy producers source-compatible.
         */
        data class WsSessionConnected(
            val sessionEpoch: Long,
            val connectionGeneration: Long = -1L,
        ) : Event()

        /**
         * Emitted once per WS session at the moment the WebSocket closes
         * (any reason: protocol error, RST, server close, OS network drop).
         * [inboundFrames] is the count of Frame.Text the client received
         * over the just-ended session; [pendingAcksAtClose] is the number
         * of envelopes the client had sent but not yet received an ACK for
         * when the session ended.
         *
         * [sessionEpoch] names the session; an end of any other session
         * than the live one is stale and ignored (B2).
         */
        data class WsSessionEnded(
            val durationMs: Long,
            val inboundFrames: Int,
            val pendingAcksAtClose: Int,
            /**
             * 3.6 Fast REST degradation (2026-06-18). When `true`, the WS
             * session ended via OkHttp WS-Ping watchdog (parser-confirmed
             * "after N successful ping/pongs" pattern in the throwable
             * message). Required field of the Mode-2 signature alongside
             * `inboundFrames == 0` and a duration in
             * [MODE_2_MIN_DURATION_MS]..[MODE_2_MAX_DURATION_MS].
             */
            val okhttpPingTimeoutDetected: Boolean = false,
            val sessionEpoch: Long,
        ) : Event()

        /**
         * The relay pushed a `Deliver` text frame on [sessionEpoch]. In
         * [RestMode.RestActive] the live session becomes the candidate;
         * in candidate it records the frame evidence the dwell proof
         * needs (B3 b).
         */
        data class WsFrameTextReceived(val sessionEpoch: Long) : Event()

        /**
         * Emitted on Android `ConnectivityManager` capability changes
         * (Wi-Fi ↔ cellular, network gained/lost). Stage 2 B5: invalidates
         * the WSS proof, requests a reconnect, carries the observer's
         * monotonic [networkGeneration]; never enters candidate.
         *
         * R3.6: [clearsMode2Sticky] lifts a sticky window into recovery
         * mode when `true`; `false` (VALIDATED_CHANGED) keeps it armed.
         */
        data class NetworkChanged(
            val clearsMode2Sticky: Boolean,
            val networkGeneration: Long = 0L,
        ) : Event()

        /**
         * An outbound envelope completed its ACK round-trip over the WS
         * session [sessionEpoch]. Strongest signal that the WS data plane
         * works bidirectionally; proves the live candidate (B3 a).
         */
        data class WsOutboundAckReceived(val sessionEpoch: Long) : Event()

        /** An application-level pong on [sessionEpoch]. Liveness only; never proof. */
        data class WsPongReceived(val sessionEpoch: Long) : Event()

        /**
         * The correlated response to the transport's single candidate probe
         * on [sessionEpoch]. Unlike an unsolicited pong, this proves the
         * candidate's bidirectional application data plane. The normal
         * candidate dwell is still required before promotion.
         */
        data class WsCandidateProbeRoundTrip(val sessionEpoch: Long) : Event()

        /**
         * Periodic timer tick (driven by the orchestrator's tick loop) for
         * the candidate [sessionEpoch]. Commits into [RestMode.WsActive]
         * only when that session is still the live candidate, has produced
         * a frame and [CANDIDATE_COMMIT_MS] elapsed since it connected.
         */
        data class WsAliveTickElapsed(val sessionEpoch: Long) : Event()

        /**
         * PR-D1d: a per-envelope ACK deadline expired. [sessionEpoch] is
         * the session that wrote the frame, captured when the deadline
         * was armed. [msgId] is the envelope's messageId; [ageMs] the
         * elapsed time at expiry.
         */
        data class ActiveOutboundAckTimeout(
            val sessionEpoch: Long,
            val msgId: String,
            val ageMs: Long,
        ) : Event()

        /**
         * PR-RECV-DIAG1 v1.6 — the session [sessionEpoch] is open but no
         * inbound Frame.Text arrived for [sinceLastInboundMs] (at least
         * `INBOUND_STALL_THRESHOLD_MS`). Degrades `WsActive` and cancels a
         * candidate's proof; the session is kept.
         */
        data class InboundIdleTimeout(
            val sessionEpoch: Long,
            val sinceLastInboundMs: Long,
        ) : Event()

        /**
         * Stage 2 B3/B6: the transport abandoned or tore down the session
         * [sessionEpoch] on purpose (`force_reconnect`, a teardown). Same
         * effect as its `Ended`, delivered before the socket is closed.
         */
        data class WsSessionInvalidated(
            val sessionEpoch: Long,
            val reason: String,
        ) : Event()

        /**
         * Trek 2 Stage 2B-B (C5, L9) — REST poll path entered
         * [LongPollBreakerState.Open]. Read-only telemetry signal:
         * the state machine does NOT change [RestMode] in response.
         *
         * @param reason Discriminator for the open trigger.
         */
        data class RestPollDegraded(val reason: BreakerOpenReason) : Event()
    }

    // ── RC-RECONNECT-QUIESCENCE1 coordinator + claim API (2026-06-22) ───────

    /**
     * Allocate a fresh per-loop generation counter, called once at the
     * top of each [KtorRelayTransport.runReconnectLoop] invocation. The
     * value is carried unchanged through every auth-retry inside that
     * loop and is bound to a probe atomically at claim time.
     */
    override suspend fun allocateConnectionGeneration(): Long = withGateLock {
        connectionGenerationCounter += 1
        connectionGenerationCounter
    }

    /**
     * Snapshot of the current monotonic [routeEpoch] under [gateLock].
     * Suspending because a non-locked read could tear (64-bit Long
     * reads are not JMM-atomic without `volatile`) and could observe
     * a stale value relative to an in-flight write inside another
     * gate-lock critical section.
     */
    suspend fun currentRouteEpoch(): Long = withGateLock { routeEpoch }

    /**
     * Snapshot of the current [connectionGenerationCounter] under
     * [gateLock] — same rationale as [currentRouteEpoch].
     */
    suspend fun currentConnectionGenerationCounter(): Long =
        withGateLock { connectionGenerationCounter }

    /**
     * Coordinator: increment [routeEpoch] AND atomically flip any
     * stuck in-flight probe state ([WsReconnectGate.ProbeAvailable],
     * [WsReconnectGate.ProbeClaimed], [WsReconnectGate.CandidateProving])
     * back to [WsReconnectGate.Quiesced] preserving the same `stickyGen`.
     * After this call a subsequent [issueProbeAfterRewalk] with the
     * returned new epoch is guaranteed to find a Quiesced gate (unless
     * a concurrent path advanced things again — in which case the new
     * `issueProbeAfterRewalk` returns [ProbeIssueResult.Rejected]).
     *
     * Subsequent [issueProbeAfterRewalk] calls must use this exact
     * returned value.
     *
     * Held under [gateLock] so the routeEpoch bump and the stuck-state
     * flip are observed as a single atomic step.
     */
    override suspend fun beginRouteChange(clearsMode2Sticky: Boolean): RouteChangeOutcome {
        // Commit 2c second-round amend (2026-06-22): typed outcome.
        // See [RewalkCoordinatorGateProvider.beginRouteChange] kdoc for
        // branch semantics.
        //   - Open ⇒ OpenReconnect (bump routeEpoch; ordinary rewalk).
        //   - non-Open + clearsMode2Sticky=false ⇒ QuiescencePreserved
        //     (NO bump, NO flip; sticky window stays armed).
        //   - non-Open + clearsMode2Sticky=true ⇒ StickyRecovery
        //     (bump routeEpoch; flip in-flight Probe* / CandidateProving
        //     to Quiesced so issueProbeAfterRewalk can hand out a fresh
        //     probe).
        var publishedGate: WsReconnectGate? = null
        var publishedReason: String? = null
        val outcome: RouteChangeOutcome = withGateLock {
            val current = _gate.value
            when {
                current is WsReconnectGate.Open -> {
                    routeEpoch += 1
                    RouteChangeOutcome.OpenReconnect(routeEpoch)
                }
                !clearsMode2Sticky -> {
                    // VALIDATED_CHANGED or similar non-route event under
                    // quiescence: no bump, no flip. The current routeEpoch
                    // is returned for telemetry only.
                    RouteChangeOutcome.QuiescencePreserved(routeEpoch)
                }
                else -> {
                    routeEpoch += 1
                    if (current !is WsReconnectGate.Quiesced) {
                        val stickyGen = when (current) {
                            is WsReconnectGate.Quiesced -> current.stickyGen
                            is WsReconnectGate.ProbeAvailable -> current.stickyGen
                            is WsReconnectGate.ProbeClaimed -> current.stickyGen
                            is WsReconnectGate.CandidateProving -> current.stickyGen
                            is WsReconnectGate.Open -> error("unreachable: Open handled above")
                        }
                        val next = WsReconnectGate.Quiesced(stickyGen)
                        probeAttemptCount = 0
                        _gate.value = next
                        publishedGate = next
                        publishedReason = when (current) {
                            is WsReconnectGate.ProbeAvailable -> "route_change_invalidates_probe"
                            is WsReconnectGate.ProbeClaimed -> "route_change_invalidates_claim"
                            is WsReconnectGate.CandidateProving -> "route_change_invalidates_candidate"
                            else -> "route_change"
                        }
                    }
                    RouteChangeOutcome.StickyRecovery(routeEpoch)
                }
            }
        }
        publishedGate?.let { g -> publishedReason?.let { r -> emitGateTelemetry(g, r) } }
        return outcome
    }

    /**
     * Coordinator: log a rewalk substep abort. Does NOT decrement
     * [routeEpoch] — leaving the counter at its bumped value defeats
     * stale-epoch attacks AND keeps the per-attempt rate-limit budget
     * intact on the coordinator side (`lastRewalkAtMs` is only advanced
     * by a SUCCESSFUL rewalk; a failed teardown does not eat the
     * budget).
     */
    override suspend fun revokeRouteChange(routeEpoch: Long, reason: String) {
        log("REST_TRACE ws_recovery_route_change_revoked route_epoch=$routeEpoch reason=$reason")
    }

    /**
     * Coordinator: after a successful disconnectAndJoin + release,
     * atomically transition the gate from [WsReconnectGate.Quiesced] to
     * [WsReconnectGate.ProbeAvailable] with the requested [routeEpoch]
     * and a fresh single-use token from [tokenSource].
     *
     * Returns a typed [ProbeIssueResult] — never fire-and-forget.
     */
    override suspend fun issueProbeAfterRewalk(routeEpoch: Long): ProbeIssueResult {
        var issuedToken: ProbeToken? = null
        var publishedReason: String? = null
        var publishedGate: WsReconnectGate? = null
        val result: ProbeIssueResult = withGateLock {
            if (routeEpoch != this.routeEpoch) {
                return@withGateLock ProbeIssueResult.Rejected(ProbeIssueRejectReason.ROUTE_EPOCH_STALE)
            }
            val current = _gate.value
            if (current !is WsReconnectGate.Quiesced) {
                return@withGateLock ProbeIssueResult.Rejected(ProbeIssueRejectReason.GATE_NOT_QUIESCED)
            }
            val token = ProbeToken(tokenSource())
            val budget = ProbeBudget(
                budgetStartedAtMs = now(),
                maxAttempts = ProbeBudget.MAX_ATTEMPTS_LOCKED,
                maxElapsedMs = ProbeBudget.MAX_ELAPSED_MS_LOCKED,
            )
            val next = WsReconnectGate.ProbeAvailable(
                stickyGen = current.stickyGen,
                routeEpoch = routeEpoch,
                token = token,
                budget = budget,
                // P1 (sixth round): generation floor pinned to the
                // CURRENT counter. Only loops allocated AFTER this
                // probe was issued (ownerGeneration > floor) may claim.
                generationFloor = connectionGenerationCounter,
            )
            // P1 (ninth round, 2026-06-22): defensively reset the
            // per-probe attempt counter when a NEW probe is issued.
            // Without this, a residual count from a prior probe (e.g.
            // a race where the ProbeClaimed → CandidateProving reset
            // did not fire due to gate-shape concurrency, or any
            // future path that leaves the counter > 0 across a
            // Quiesced state) would silently shorten the new probe's
            // budget. Reset is atomic with the gate transition under
            // the same lock acquisition.
            probeAttemptCount = 0
            _gate.value = next
            issuedToken = token
            publishedGate = next
            publishedReason = "rewalk_committed"
            ProbeIssueResult.ProbeIssued(token)
        }
        val g = publishedGate
        val r = publishedReason
        if (g != null && r != null) emitGateTelemetry(g, r)
        return result
    }

    /**
     * Coordinator: revoke an issued probe if a downstream rewalk substep
     * (`requestServiceRestart`) failed AFTER [issueProbeAfterRewalk]
     * returned [ProbeIssueResult.ProbeIssued]. Transitions
     * [WsReconnectGate.ProbeAvailable] back to [WsReconnectGate.Quiesced].
     * No-op if the gate has already transitioned away.
     */
    override suspend fun revokeProbe(routeEpoch: Long, reason: String) {
        var publishedGate: WsReconnectGate? = null
        var publishedReason: String? = null
        withGateLock {
            val current = _gate.value
            if (current is WsReconnectGate.ProbeAvailable && current.routeEpoch == routeEpoch) {
                val next = WsReconnectGate.Quiesced(current.stickyGen)
                _gate.value = next
                publishedGate = next
                publishedReason = "probe_revoked:$reason"
            }
        }
        val g = publishedGate
        val r = publishedReason
        if (g != null && r != null) emitGateTelemetry(g, r)
    }

    /**
     * Reconnect-loop: atomic claim of [WsReconnectGate.ProbeAvailable].
     * On success, transitions to [WsReconnectGate.ProbeClaimed] and
     * returns [ClaimResult.Claimed]; otherwise returns a typed
     * [ClaimResult.Failure].
     *
     * Throws [kotlinx.coroutines.CancellationException] when the calling
     * coroutine is cancelled.
     */
    suspend fun awaitAndClaimProbe(ownerGeneration: Long): ClaimResult {
        var publishedGate: WsReconnectGate? = null
        var publishedReason: String? = null
        val result: ClaimResult = withGateLock {
            val current = _gate.value
            when (current) {
                is WsReconnectGate.ProbeAvailable -> {
                    if (current.routeEpoch != this.routeEpoch) {
                        return@withGateLock ClaimResult.Failure(ClaimFailureReason.ROUTE_EPOCH_STALE)
                    }
                    // P1 (seventh round): strict currency check —
                    // `ownerGeneration` MUST equal the latest
                    // `connectionGenerationCounter`. Combined with the
                    // floor (always ≤ counter), this collapses the
                    // claim-vs-floor + claim-vs-counter ambiguity into
                    // a single invariant: only the latest-allocated
                    // loop can claim. An arbitrary unallocated number
                    // (e.g. 999) used to slip through the strict-greater-
                    // than-floor check.
                    if (ownerGeneration != this.connectionGenerationCounter) {
                        return@withGateLock ClaimResult.Failure(ClaimFailureReason.OWNER_GENERATION_STALE)
                    }
                    if (ownerGeneration <= current.generationFloor) {
                        return@withGateLock ClaimResult.Failure(ClaimFailureReason.OWNER_GENERATION_STALE)
                    }
                    val claimed = WsReconnectGate.ProbeClaimed(
                        stickyGen = current.stickyGen,
                        routeEpoch = current.routeEpoch,
                        token = current.token,
                        ownerGeneration = ownerGeneration,
                        budget = current.budget,
                    )
                    _gate.value = claimed
                    publishedGate = claimed
                    publishedReason = "owner_bound"
                    ClaimResult.Claimed(
                        WsReconnectPermit.ClaimedProbe(
                            stickyGen = claimed.stickyGen,
                            routeEpoch = claimed.routeEpoch,
                            token = claimed.token,
                            ownerGeneration = claimed.ownerGeneration,
                            budget = claimed.budget,
                        )
                    )
                }
                is WsReconnectGate.ProbeClaimed ->
                    ClaimResult.Failure(ClaimFailureReason.PROBE_ALREADY_CLAIMED)
                else ->
                    ClaimResult.Failure(ClaimFailureReason.GATE_NOT_PROBE_AVAILABLE)
            }
        }
        val g = publishedGate
        val r = publishedReason
        if (g != null && r != null) emitGateTelemetry(g, r)
        return result
    }

    /**
     * Reconnect-loop: high-level permit acquisition. Suspends until the
     * gate is [WsReconnectGate.Open] or [WsReconnectGate.ProbeAvailable];
     * for [WsReconnectGate.ProbeAvailable] runs the atomic claim and
     * returns the corresponding [WsReconnectPermit]; for
     * [WsReconnectGate.Open] returns an [WsReconnectPermit.OpenPermit]
     * stamped with the CURRENT `routeEpoch` and
     * `connectionGenerationCounter` so the post-auth re-validation can
     * detect any intervening gate / epoch / generation transition.
     *
     * For [WsReconnectGate.Quiesced] / [WsReconnectGate.ProbeClaimed] /
     * [WsReconnectGate.CandidateProving] suspends until the gate
     * transitions to one of the actionable states.
     *
     * [kotlinx.coroutines.CancellationException] propagates.
     */
    override suspend fun awaitReconnectPermit(ownerGeneration: Long): WsReconnectPermit {
        while (true) {
            // Snapshot read of gate state (atomic).
            val current = _gate.value
            when (current) {
                is WsReconnectGate.Open -> {
                    // Stamp OpenPermit with snapshotted routeEpoch +
                    // connectionGenerationCounter UNDER [gateLock] so the
                    // post-auth validate compares against consistent
                    // snapshots — otherwise a concurrent route change
                    // between stamp and validate could produce a false
                    // pass.
                    return withGateLock {
                        val stillOpen = _gate.value is WsReconnectGate.Open
                        if (!stillOpen) {
                            // Gate moved while we were suspended; loop.
                            null
                        } else {
                            WsReconnectPermit.OpenPermit(
                                routeEpoch = this.routeEpoch,
                                ownerGeneration = ownerGeneration,
                            )
                        }
                    } ?: continue
                }
                is WsReconnectGate.ProbeAvailable -> {
                    val claim = awaitAndClaimProbe(ownerGeneration)
                    when (claim) {
                        is ClaimResult.Claimed -> return claim.probe
                        is ClaimResult.Failure -> when (claim.reason) {
                            // P1 (seventh round): on OWNER_GENERATION_STALE
                            // the gate stays at ProbeAvailable forever from
                            // this caller's perspective — the obsolete loop
                            // cannot claim, gate cannot transition, and a
                            // tight `continue` would CPU-spin holding
                            // `gateLock`. Retire the loop instead.
                            ClaimFailureReason.OWNER_GENERATION_STALE ->
                                return WsReconnectPermit.LoopRetired(
                                    reason = "owner_generation_stale",
                                )
                            // ROUTE_EPOCH_STALE and PROBE_ALREADY_CLAIMED
                            // are also terminal for THIS observation — gate
                            // has moved. Suspend until the gate transitions
                            // before re-evaluating (no tight CPU loop).
                            ClaimFailureReason.ROUTE_EPOCH_STALE,
                            ClaimFailureReason.PROBE_ALREADY_CLAIMED,
                            ClaimFailureReason.GATE_NOT_PROBE_AVAILABLE -> {
                                _gate.first { snapshot ->
                                    snapshot !== current &&
                                        (snapshot is WsReconnectGate.Open ||
                                            snapshot is WsReconnectGate.ProbeAvailable)
                                }
                            }
                        }
                    }
                }
                else -> {
                    // Suspend until something actionable shows up.
                    _gate.first { it is WsReconnectGate.Open || it is WsReconnectGate.ProbeAvailable }
                }
            }
        }
    }

    /**
     * Reconnect-loop: re-check a previously issued permit after the
     * auth handshake completes but BEFORE `webSocket(...)` dial.
     *
     * - [WsReconnectPermit.OpenPermit] valid iff: gate is STILL [WsReconnectGate.Open]
     *   AND `routeEpoch` unchanged AND `connectionGenerationCounter`
     *   unchanged. Any sticky-arm, rewalk, or fresh-loop allocation
     *   invalidates the permit so the caller must re-enter the loop top.
     * - [WsReconnectPermit.ClaimedProbe] valid iff: gate is [WsReconnectGate.ProbeClaimed]
     *   with identical `routeEpoch`, `token`, `ownerGeneration` AND the
     *   state-machine `routeEpoch` has not advanced since the claim.
     */
    override suspend fun validatePermitAfterAuth(permit: WsReconnectPermit): Boolean = withGateLock {
        when (permit) {
            is WsReconnectPermit.OpenPermit -> {
                val current = _gate.value
                current is WsReconnectGate.Open &&
                    permit.routeEpoch == this.routeEpoch &&
                    permit.ownerGeneration == this.connectionGenerationCounter
            }
            is WsReconnectPermit.ClaimedProbe -> {
                val current = _gate.value
                // P1 (seventh round): the owner's lease persists until
                // the claim is mutated by route-change, revoke, or
                // owner-matching Connected. A spurious
                // `allocateConnectionGeneration` that advanced the
                // counter must NOT invalidate the in-flight claim —
                // doing so deadlocked the gate (claim held by old
                // owner, new loop blocked by PROBE_ALREADY_CLAIMED).
                current is WsReconnectGate.ProbeClaimed &&
                    permit.routeEpoch == this.routeEpoch &&
                    current.routeEpoch == permit.routeEpoch &&
                    current.token == permit.token &&
                    current.ownerGeneration == permit.ownerGeneration
            }
            is WsReconnectPermit.LoopRetired -> false
        }
    }

    /**
     * Reconnect-loop: called when an iteration in [WsReconnectGate.ProbeClaimed]
     * exits WITHOUT producing a [Event.WsSessionConnected]. Increments the
     * internal attempt counter; when attempts ≥ budget OR elapsed ≥
     * budget, flips the gate to [WsReconnectGate.Quiesced] with reason
     * `probe_exhausted` and emits telemetry. Held under [gateLock] so
     * the counter increment and the budget-exhaustion check are atomic
     * with any concurrent suspend-side gate writes.
     *
     * P1 (sixth round): owner-bound. Takes the caller's
     * [WsReconnectPermit.ClaimedProbe] and consumes budget ONLY if the
     * permit identifies the SAME claim currently held in the gate
     * (matching routeEpoch + token + ownerGeneration). An old loop
     * holding a stale permit cannot drain another loop's budget.
     */
    override suspend fun recordProbeAttemptFailed(permit: WsReconnectPermit.ClaimedProbe, reason: String) {
        var attemptLog: String? = null
        var exhaustLog: String? = null
        var publishedGate: WsReconnectGate? = null
        withGateLock {
            val current = _gate.value
            if (current !is WsReconnectGate.ProbeClaimed) return@withGateLock
            // Owner-bound budget consumption.
            if (current.routeEpoch != permit.routeEpoch ||
                current.token != permit.token ||
                current.ownerGeneration != permit.ownerGeneration
            ) {
                attemptLog =
                    "REST_TRACE ws_recovery_probe_attempt_ignored " +
                        "reason=permit_owner_mismatch event_owner=${permit.ownerGeneration} " +
                        "claim_owner=${current.ownerGeneration}"
                return@withGateLock
            }
            probeAttemptCount += 1
            val elapsed = now() - current.budget.budgetStartedAtMs
            attemptLog =
                "REST_TRACE ws_recovery_probe_attempt_failed gen=${current.stickyGen} " +
                    "route_epoch=${current.routeEpoch} attempt=$probeAttemptCount " +
                    "elapsed_ms=$elapsed reason=$reason"
            if (probeAttemptCount >= current.budget.maxAttempts ||
                elapsed >= current.budget.maxElapsedMs
            ) {
                exhaustLog =
                    "REST_TRACE ws_recovery_probe_exhausted gen=${current.stickyGen} " +
                        "attempts=$probeAttemptCount elapsed_ms=$elapsed"
                val next = WsReconnectGate.Quiesced(current.stickyGen)
                probeAttemptCount = 0
                _gate.value = next
                publishedGate = next
            }
        }
        attemptLog?.let(log)
        exhaustLog?.let(log)
        publishedGate?.let { emitGateTelemetry(it, "probe_exhausted") }
    }

    companion object {
        /**
         * From WsCandidate → WsActive: the candidate WS session must hold
         * for at least this many ms since it connected AND have produced
         * at least one inbound text frame, OR receive an outbound ACK
         * round-trip, before committing fully (Stage 2 B3). Test #48
         * showed isolated successful Frame.Text on otherwise-broken
         * sessions, so a single Frame.Text is not enough to commit.
         */
        /**
         * residual N1 / F3 — the external recovery oracle: `WS_ACTIVE` must be
         * reached within this long after the last effective route change. This
         * is the product limit and is NOT to be raised to make a run pass.
         */
        const val ROUTE_RECOVERY_EXTERNAL_LIMIT_MS: Long = 60_000L

        /**
         * residual N1 / F3 — the part of the external limit reserved for the
         * work that happens before the candidate is even entered: socket
         * reconnect, handshake and scheduling. The field run measured 1 817 ms
         * of reconnect latency after the route change; the dwell below must
         * leave at least that much room, or promotion can never fit the limit.
         */
        const val ROUTE_RECOVERY_MARGIN_MS: Long = 5_000L

        const val CANDIDATE_COMMIT_MS: Long =
            ROUTE_RECOVERY_EXTERNAL_LIMIT_MS - ROUTE_RECOVERY_MARGIN_MS

        /**
         * 3.6 Fast REST degradation (2026-06-18) — lower bound of the
         * Mode-2 signature duration window. Sessions ending below this
         * floor are short failures (TLS handshake fail, immediate carrier
         * NACK, etc.) and do NOT match the signature.
         *
         * 25 000 ms is comfortably below the dominant Mode-2 death point
         * on Tele2 LTE (~31 s = `2 × pingInterval(15s) + ~1 s overhead`)
         * to absorb minor jitter while excluding pre-Ping failures.
         */
        const val MODE_2_MIN_DURATION_MS: Long = 25_000L

        /**
         * 3.6 Fast REST degradation (2026-06-18) — upper bound of the
         * Mode-2 signature duration window. Sessions ending above this
         * ceiling are healthy Mode-1 rhythm (120-170 s on Wi-Fi per
         * `project_arm_c_lifetime_linear_in_interval_2026_06_04`) and
         * MUST NOT trip the fast-path.
         *
         * 65 000 ms is just above the warm-window Mode-2 death point
         * (~61 s = `4 × pingInterval(15s) + ~1 s overhead` per pool
         * isolation canary v2 distribution) so warm-window Mode-2
         * sessions are still caught. The Mode-1 8-pong rhythm has a
         * floor of 120 s; 65 000 ms is safely below it.
         */
        const val MODE_2_MAX_DURATION_MS: Long = 65_000L
    }
}

/** Current REST fallback mode — fed back to UI for honest state labels. */
enum class RestMode {
    /** WebSocket healthy. Outbound and inbound flow over WS. REST polling stopped. */
    WsActive,

    /** WebSocket broken under current network. REST short-poll is the active path. */
    RestActive,

    /**
     * Transitional state: WS just produced a Frame.Text, but we're still
     * keeping REST polling alive as a safety net until WS proves stable
     * ([RestStateMachine.CANDIDATE_COMMIT_MS] of uptime OR an outbound ACK).
     */
    WsCandidate,
}
