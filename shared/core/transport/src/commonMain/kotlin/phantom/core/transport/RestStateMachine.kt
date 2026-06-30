// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pure state machine for WS ↔ REST fallback mode selection — PR-D1.
 *
 * Decides whether outbound envelope traffic and inbound polling should run
 * over the WebSocket path ([RestMode.WsActive]), the REST short-poll
 * fallback path ([RestMode.RestActive]), or the transitional candidate
 * state ([RestMode.WsCandidate]) where WS appears recovered but we still
 * keep REST polling as a safety net until WS proves stable.
 *
 * The state machine is **input-driven**: it consumes events fed in by the
 * orchestrator (or by tests) and emits state transitions via [state].
 * It has no direct dependency on [RelayTransport] or any I/O — that makes
 * the logic fully deterministic and unit-testable under [kotlinx.coroutines.test.runTest].
 *
 * Locked triggers (2026-05-16):
 *
 * From [RestMode.WsActive]:
 *   - 2 consecutive [Event.WsSessionEnded] with `inboundFrames == 0` AND
 *     `pendingAcksAtClose > 0` (active outbound, pessimistic trigger) → REST
 *   - 3 consecutive [Event.WsSessionEnded] with `inboundFrames == 0` AND
 *     `pendingAcksAtClose == 0` (passive idle, conservative trigger) → REST
 *   - Any [Event.WsSessionEnded] with `inboundFrames > 0` → reset counters
 *   - [Event.NetworkChanged] → reset counters (new path may behave differently)
 *
 * From [RestMode.RestActive]:
 *   - [Event.WsFrameTextReceived] (relay pushed any Frame.Text — Deliver, Ack,
 *     application Pong-as-text) → [RestMode.WsCandidate], REST polling continues
 *   - [Event.NetworkChanged] → [RestMode.WsCandidate], REST polling continues
 *
 * From [RestMode.WsCandidate]:
 *   - [Event.WsAliveTickElapsed] with `elapsedSinceCandidateEnteredMs >= 60_000`
 *     → [RestMode.WsActive], REST polling stops
 *   - [Event.WsOutboundAckReceived] → [RestMode.WsActive], REST polling stops
 *   - [Event.WsSessionEnded] (any close) → [RestMode.RestActive] (regression),
 *     REST polling continues, both counters reset
 *
 * Anti-flapping rationale:
 *   The two-phase WsCandidate state exists because a single Frame.Text under
 *   broken Tele2 conditions does not prove the link is healthy — Test #48
 *   showed isolated messages occasionally proxime even on heavily-degraded
 *   sessions. We require either 60 seconds of WS uptime OR an outbound ACK
 *   round-trip before fully committing back to WS.
 *
 * Threading: not thread-safe. The orchestrator should serialise calls onto a
 * single coroutine context (typically `Dispatchers.Default` or the same
 * dispatcher that owns the polling loop).
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
     * existing [phantom.core.transport.WsDegradationDetector] stream
     * (`ConsecutiveRestFailures` vs `Status410Storm`
     * discriminator surfacing as a typed value, not just a log
     * substring).
     *
     * Per scope §L9 the handler is read-only — it does NOT
     * cause a `RestMode` transition. Optional; defaults to no-op
     * for backward compatibility with existing tests.
     */
    private val onRestPollDegraded: ((BreakerOpenReason) -> Unit)? = null,
    /**
     * 3.6 Fast REST degradation gate (2026-06-18). When `true`, the
     * first [Event.WsSessionEnded] in [RestMode.WsActive] that matches
     * the Mode-2 signature (`inboundFrames == 0` AND
     * `okhttpPingTimeoutDetected == true` AND
     * `durationMs in MODE_2_MIN_DURATION_MS..MODE_2_MAX_DURATION_MS`)
     * transitions straight to [RestMode.RestActive] via the
     * `mode_2_fast_path` reason — skipping the
     * [ACTIVE_FAIL_THRESHOLD] / [IDLE_FAIL_THRESHOLD] counters.
     *
     * Detection latency drops from ~93 s (3 × ~31 s sessions under the
     * existing idle counter) to ~30 s (one matching session lifetime +
     * sub-second event handling). When `false`, the existing counter
     * logic remains the only RestActive promotion path.
     *
     * The matched-signature telemetry line fires regardless of this
     * gate (action=fast_path vs action=observe_only) so a production
     * post-mortem can count how often Mode-2 fires even on builds where
     * we choose not to act on it.
     *
     * Default `false` for backwards compatibility with existing tests
     * and for production safety. Wired through
     * [phantom.core.transport.RestFallbackOrchestrator] from
     * `BuildConfig.MODE_2_FAST_PATH_ENABLED` in `AppContainer`.
     */
    private val mode2FastPathEnabled: Boolean = false,
    /**
     * R3.6 Sticky-per-route Fast REST degradation (2026-06-20). When `true`,
     * a Mode-2 fast-path transition arms a sticky REST window that suppresses
     * any [Event.WsFrameTextReceived]-driven exit from [RestMode.RestActive]
     * until the WS layer proves recovery via `ws_alive_60s` on a brand-new
     * session after a route change that sets [Event.NetworkChanged.clearsMode2Sticky].
     *
     * Build-time invariant: `mode2StickyEnabled` requires `mode2FastPathEnabled`.
     * Enforced in `init { require(...) }`.
     *
     * Default `false` for backward compatibility with existing tests and for
     * production safety. Wired through [RestFallbackOrchestrator] from
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
     * Default `false` for backward compatibility. Wired through
     * [RestFallbackOrchestrator] from `BuildConfig.RECONNECT_QUIESCENCE_ENABLED`.
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
    private val _state = MutableStateFlow<RestMode>(RestMode.WsActive)
    val state: StateFlow<RestMode> = _state.asStateFlow()

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
     *   val computed = gateLock.withLock { compute + assign state }
     *   // ... release lock ...
     *   emitTelemetry(computed)
     *
     * to avoid self-deadlock when a callback would (transitively) want
     * to acquire the same lock, and to keep the critical section short.
     */
    private val gateLock = Mutex()

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
        gateLock.withLock {
            this.probeAttemptCount = probeAttemptCount
            _gate.value = gate
        }
    }

    internal suspend fun probeAttemptCountForTest(): Int = gateLock.withLock { probeAttemptCount }

    /** Convenience accessor. */
    val current: RestMode
        get() = _state.value

    // Mutable bookkeeping. Reset rules are documented inline at the reset call sites.
    private var activeFailCount: Int = 0
    private var idleFailCount: Int = 0
    private var candidateEnteredAtMs: Long? = null

    // ── R3.6 Sticky-recovery fields ─────────────────────────────────────────

    /**
     * R3.6 (2026-06-20): monotonic floor for session epoch observations.
     * Updated on every observed [Event.WsSessionConnected], regardless of
     * [stickyRecovery] state. Defends against delayed stale `Connected(N)`
     * events with `N <= lastObservedEpoch` being processed out of order.
     * Initial value -1L because valid epochs start at 1 (incremented from 0
     * before first use in `wsSessionEpoch`).
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
     * probation is in flight. Without this check, a synthetic
     * `WsSessionLifecycleEvent.Ended` enqueued during recovery would
     * enter the `WsCandidate` arm of [onWsSessionEnded] and falsely
     * trigger the `sticky_recovery_stale_close` or
     * `candidate_session_regression` branch, regressing the in-flight
     * recovery.
     *
     * Returns [phantom.core.transport.SyntheticTriggerResult.RefusedAlreadyArmed]
     * when surfaced as the wrapper's typed result.
     *
     * Threading: pure-read snapshot accessor. The caller
     * (AppContainer.triggerDebugForceMode2) reads from the Android
     * main thread (invoked from `DebugForceMode2Activity.onCreate`)
     * while the state machine's mutations are confined to a different
     * coroutine context (the orchestrator's dispatcher). The two
     * backing fields ([mode2StickyRestActive] and [stickyRecovery])
     * are annotated `@Volatile` so the cross-thread read sees the
     * most recent write per the JVM memory model.
     *
     * Under RC-RECONNECT-QUIESCENCE1 (PR #330) the same two backing
     * fields are additionally mutated under [gateLock]. The
     * `@Volatile` markers stay as defence-in-depth for this lockless
     * cross-thread reader path.
     *
     * The read is a best-effort snapshot — by the time the caller
     * acts on the returned Boolean, the underlying state may have
     * changed. That race is benign: if `true` is returned and recovery
     * actually just finished, the synthetic refuses with
     * `SyntheticTriggerResult.RefusedAlreadyArmed`; the operator
     * retries after the next Connected. If `false` is returned and
     * sticky armed in the same window, the synthetic's enqueued Ended
     * still flows through the dispatcher and the state machine's own
     * sequential consumption (RestActive arm of [onWsSessionEnded])
     * absorbs any race outcome correctly per the L1 mini-lock §7.2
     * D-1 verdict.
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
     * Submit an [Event] to the state machine. Idempotent w.r.t. duplicate
     * events: e.g. multiple [Event.WsFrameTextReceived] in [RestMode.RestActive]
     * only fire the first transition into [RestMode.WsCandidate]; later ones
     * are no-ops while in candidate.
     *
     * RC-RECONNECT-QUIESCENCE1 (2026-06-22): SUSPEND so the
     * gate-mutating event handlers can acquire [gateLock] for atomic
     * compute-then-publish transitions that must be serialised with
     * the coordinator-side suspend API ([beginRouteChange],
     * [issueProbeAfterRewalk], [awaitAndClaimProbe], etc.).
     */
    suspend fun onEvent(event: Event) {
        when (event) {
            is Event.WsSessionConnected -> onWsSessionConnected(event)
            is Event.WsSessionEnded -> onWsSessionEnded(event)
            is Event.WsFrameTextReceived -> onWsFrameText()
            is Event.NetworkChanged -> onNetworkChanged(event)
            is Event.WsOutboundAckReceived -> onWsOutboundAck()
            is Event.WsAliveTickElapsed -> onAliveTick()
            is Event.ActiveOutboundAckTimeout -> onActiveOutboundAckTimeout(event)
            is Event.InboundIdleTimeout -> onInboundIdleTimeout(event)
            is Event.RestPollDegraded -> onRestPollDegraded(event)
        }
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
    private suspend fun onRestPollDegraded(event: Event.RestPollDegraded) {
        log("REST_TRACE rest_poll_degraded reason=${event.reason}")
        // Trek 2 Stage 2B-B (C5-C review-fix P2) — fire the
        // observation hook. Previous shape only logged, so the
        // wire-up's typed degradation surface never received the
        // signal. The callback fires AFTER the log line so the
        // log remains the audit-trail anchor.
        onRestPollDegraded?.invoke(event.reason)
    }

    /**
     * R3.6: handle a new WS session coming online. Updates [lastObservedEpoch] as a
     * monotonic floor, then — if [mode2StickyEnabled] and we are in
     * [StickyRecoveryState.PendingNewSession] — opens an InFlight probation window.
     */
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
        //   1. stale-epoch dedup;
        //   2. quiescence gate-bypass reject;
        //   3. lastObservedEpoch update;
        //   4. stickyRecovery state advance;
        //   5. gate transition (ProbeClaimed → CandidateProving when
        //      the owner matches).
        // Logs + `transitionToCandidate` + `onModeSwitched` callback run
        // AFTER unlock — keeping the critical section short and
        // preventing a logging callback from self-deadlocking.
        val outcome: ConnectedOutcome = gateLock.withLock {
            if (event.sessionEpoch <= lastObservedEpoch) {
                return@withLock ConnectedOutcome.StaleEpochIgnored(
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
            // [CandidateProving]) MUST reject the event. Allowing it
            // through used to let a stale orphan connect advance
            // `lastObservedEpoch`, transition sticky to InFlight, and
            // (after 60 s of probation) flip the gate to Open through
            // `ws_alive_60s` — entirely bypassing the probe claim.
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
                    return@withLock ConnectedOutcome.OwnerMismatch(
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
            // `lastObservedEpoch` (otherwise a stale event would
            // shadow a subsequent legitimate event with the same
            // epoch range).
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
                    candidateEnteredAtMs = now()
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
                    candidateEnteredAtMs = now()
                }
            }

            // Gate consumption runs AFTER the sticky branch and INDEPENDENTLY:
            // a legitimate owner-validated connect must consume its
            // ProbeClaimed → CandidateProving even when the
            // sticky-recovery state machine is already InFlight (e.g.
            // from a prior orphan connect that didn't carry a valid
            // owner). Owner+epoch were already validated above; safe
            // to consume here.
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
            is ConnectedOutcome.StaleEpochIgnored ->
                log(
                    "REST_TRACE sticky_recovery_stale_connect_ignored " +
                        "gen=$stickyGen last_observed_epoch=${outcome.lastObservedEpochAtCheck} " +
                        "event_epoch=${outcome.ignoredEpoch}"
                )
            is ConnectedOutcome.OwnerMismatch -> log(outcome.logLine)
            is ConnectedOutcome.StickyAdvancedNoGate -> {
                if (outcome.transitionToCandidate) transitionToCandidate("ws_recovery_probation")
                outcome.log?.let(log)
            }
            is ConnectedOutcome.StickyAdvancedAndGateConsumed -> {
                if (outcome.transitionToCandidate) transitionToCandidate("ws_recovery_probation")
                outcome.log?.let(log)
                emitGateTelemetry(outcome.gate, reason = "candidate_connected")
            }
        }
    }

    private suspend fun onWsSessionEnded(event: Event.WsSessionEnded) {
        when (_state.value) {
            RestMode.WsActive -> {
                // 3.6 Fast REST degradation (2026-06-18). Check the Mode-2
                // signature BEFORE the existing counter logic. Three
                // conditions joined by AND:
                //   1. zero inbound Text frames during the session (idle
                //      death, not a healthy close)
                //   2. parser-confirmed OkHttp WS-Ping watchdog fired
                //      (rules out server-initiated close, auth failure,
                //      manual disconnect)
                //   3. session duration inside the locked Mode-2 window
                //      ([MODE_2_MIN_DURATION_MS]..[MODE_2_MAX_DURATION_MS]
                //      inclusive) — rules out very-short failures AND
                //      healthy Mode-1 8-pong rhythm at 120-170 s
                //
                // The matched-signature telemetry line ALWAYS fires when
                // the signature matches, regardless of whether the gate
                // is on. This lets a post-mortem count how often Mode-2
                // fires on production even on builds where we choose not
                // to actuate. `action=fast_path` ⇒ flag on,
                // `action=observe_only` ⇒ flag off.
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
                        transitionToRest("mode_2_fast_path")
                        // R3.6: arm sticky AFTER the transition so the state is
                        // already RestActive when armSticky fires.
                        if (mode2StickyEnabled) {
                            armSticky()
                        }
                        return
                    }
                    // Flag off — fall through to existing counter logic
                    // so the build with telemetry-only observation does
                    // not silently lose the close event's counter tick.
                }
                if (event.inboundFrames > 0) {
                    // Healthy session despite close — reset both counters.
                    if (activeFailCount > 0 || idleFailCount > 0) {
                        log(
                            "REST_TRACE counters_reset reason=healthy_session_close " +
                                "inbound_frames=${event.inboundFrames}",
                        )
                    }
                    activeFailCount = 0
                    idleFailCount = 0
                    return
                }
                // Zero inbound frames — classify by pending outbound and increment.
                if (event.pendingAcksAtClose > 0) {
                    activeFailCount += 1
                    log(
                        "REST_TRACE counter_tick kind=active count=$activeFailCount " +
                            "pending_acks=${event.pendingAcksAtClose} duration_ms=${event.durationMs}",
                    )
                    if (activeFailCount >= ACTIVE_FAIL_THRESHOLD) {
                        transitionToRest("active_outbound_threshold")
                    }
                } else {
                    idleFailCount += 1
                    log(
                        "REST_TRACE counter_tick kind=idle count=$idleFailCount " +
                            "duration_ms=${event.durationMs}",
                    )
                    if (idleFailCount >= IDLE_FAIL_THRESHOLD) {
                        transitionToRest("idle_threshold")
                    }
                }
            }
            RestMode.WsCandidate -> {
                // R3.6: during sticky InFlight recovery, check the epoch so a stale
                // close from the OLD session (which preceded the recovery candidate)
                // does not falsely fail the recovery.
                if (mode2StickyEnabled && stickyRecovery == StickyRecoveryState.InFlight) {
                    if (event.sessionEpoch != recoveryWsEpoch) {
                        // Stale close from a session that is no longer the recovery candidate.
                        log(
                            "REST_TRACE sticky_recovery_stale_close_ignored " +
                                "gen=$stickyGen recovery_epoch=$recoveryWsEpoch " +
                                "event_epoch=${event.sessionEpoch}"
                        )
                        return
                    }
                    // The recovery candidate itself died — sticky stays armed.
                    val elapsedMs = now() - recoveryStartedAtMs
                    log(
                        "REST_TRACE sticky_recovery_failed gen=$stickyGen " +
                            "reason=candidate_session_regression " +
                            "ws_epoch=${event.sessionEpoch} " +
                            "elapsed_ms_since_recovery=$elapsedMs"
                    )
                    stickyRecovery = StickyRecoveryState.None
                    recoveryWsEpoch = -1L
                    transitionToRest("candidate_session_regression")
                    // RC-RECONNECT-QUIESCENCE1: the candidate session that
                    // was proving recovery died. Gate flips back to
                    // Quiesced; sticky stays armed. Coordinator will
                    // issue a fresh probe on the next route change.
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
                // Non-sticky regression: a candidate session closed before we could commit
                // back to WsActive. Drop back to RestActive and reset counters
                // so the next round of WS sessions starts fresh.
                transitionToRest("candidate_session_regression")
            }
            RestMode.RestActive -> {
                // Already in REST mode — a WS session close is expected
                // and irrelevant. We do continue counting nothing here:
                // counters only matter while in WsActive.
            }
        }
    }

    private suspend fun onWsFrameText() {
        if (_state.value == RestMode.RestActive) {
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
            transitionToCandidate("ws_frame_text_received")
        }
        // In WsActive: no-op (frames are expected).
        // In WsCandidate: no-op (we already saw the upgrade signal).
    }

    private suspend fun onNetworkChanged(event: Event.NetworkChanged) {
        // Reset counters in any mode — a new network's behaviour is unknown
        // and stale counter state should not influence the next decisions.
        if (activeFailCount > 0 || idleFailCount > 0) {
            log("REST_TRACE counters_reset reason=network_changed")
        }
        activeFailCount = 0
        idleFailCount = 0

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
            // While sticky is armed AND RestActive, do NOT lift to WsCandidate
            // directly — wait for the new WS session to arrive (PendingNewSession).
            return
        }

        if (_state.value == RestMode.RestActive) {
            transitionToCandidate("network_changed")
        }
    }

    private suspend fun onWsOutboundAck() {
        // R3.6: during sticky InFlight recovery, ws_outbound_ack is NOT a proof
        // signal (locked invariant #9). Short-circuit before the existing
        // transitionToWsActive call so the candidate's probe timer still runs.
        if (mode2StickyEnabled && stickyRecovery == StickyRecoveryState.InFlight &&
            _state.value == RestMode.WsCandidate
        ) {
            log(
                "REST_TRACE sticky_recovery_ack_ignored gen=$stickyGen " +
                    "reason=outbound_routes_via_rest_no_epoch"
            )
            return
        }
        if (_state.value == RestMode.WsCandidate) {
            transitionToWsActive("ws_outbound_ack")
        }
        // Any outbound ACK on WS counts as a healthy round-trip: reset
        // accumulated failure counters even when already in WsActive.
        activeFailCount = 0
        idleFailCount = 0
    }

    private suspend fun onAliveTick() {
        if (_state.value != RestMode.WsCandidate) return
        val entered = candidateEnteredAtMs ?: return
        val elapsed = now() - entered
        if (elapsed >= CANDIDATE_COMMIT_MS) {
            transitionToWsActive("ws_alive_60s")
        }
    }

    /**
     * PR-D1d: a per-envelope ACK deadline expired — the relay did not
     * AckDeliver within [RelayTransportConfig.ACK_DEADLINE_MS] (10 s).
     *
     * - In [RestMode.WsActive]: immediate mode switch to REST. This is the
     *   fast-path that avoids waiting for two full session deaths
     *   (~60 s each) as the existing [ACTIVE_FAIL_THRESHOLD] requires.
     * - In [RestMode.RestActive] or [RestMode.WsCandidate]: no-op. The mode
     *   has already been switched or is in a transitional state; the timer
     *   event is stale and would add no information.
     */
    private suspend fun onActiveOutboundAckTimeout(event: Event.ActiveOutboundAckTimeout) {
        when (_state.value) {
            RestMode.WsActive -> transitionToRest("active_outbound_ack_timeout")
            RestMode.RestActive, RestMode.WsCandidate -> {
                // Already migrated or in candidate — timer is stale. No-op,
                // no log (the spec says do NOT log in these states).
            }
        }
    }

    /**
     * PR-RECV-DIAG1 v1.6 — inbound-stall fallback. Test #84.7 proved a
     * real production-class gap: Tecno-side WS handshake succeeds, but
     * NO server-pushed frames arrive (no ack, no pong, no deliver). The
     * existing fallback triggers (WsSessionEnded count, outbound ACK
     * deadline) only fire after the user TRIES to send. If the user
     * only WAITS for incoming, the device stays stuck in WsActive
     * forever — for 60+120+180+ seconds with `inbound_frames=0`,
     * confirmed in test84_7-tecno.log idle_watchdog lines.
     *
     * This event is emitted by [KtorRelayTransport.startIdleWatchdog]
     * once per session when `sinceLastInbound >= INBOUND_STALL_THRESHOLD_MS`.
     * The handler forces the same WsActive → RestActive transition the
     * outbound ack timeout uses, so REST poll picks up the envelope
     * relay already mirrored via `mirror_envelope_to_rest_store`.
     *
     * In RestActive or WsCandidate it's a no-op: those modes already
     * have an active poll loop or are transitioning out, so the
     * inbound-stall signal carries no additional information.
     */
    private suspend fun onInboundIdleTimeout(event: Event.InboundIdleTimeout) {
        when (_state.value) {
            RestMode.WsActive -> transitionToRest("inbound_idle_timeout")
            RestMode.RestActive, RestMode.WsCandidate -> {
                // Already migrated or in candidate — no-op.
            }
        }
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
        val published = gateLock.withLock {
            val current = _gate.value
            val pair = transform(current) ?: return@withLock null
            val (next, _) = pair
            if (next == current) return@withLock null
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

    private suspend fun transitionToRest(reason: String) {
        val from = _state.value
        if (from == RestMode.RestActive) return
        activeFailCount = 0
        idleFailCount = 0
        candidateEnteredAtMs = null
        _state.value = RestMode.RestActive
        log("REST_TRACE mode_switched from=$from to=REST_ACTIVE reason=$reason")
        onModeSwitched?.invoke(from, RestMode.RestActive, reason)
    }

    private suspend fun transitionToCandidate(reason: String) {
        val from = _state.value
        if (from == RestMode.WsCandidate) return
        candidateEnteredAtMs = now()
        _state.value = RestMode.WsCandidate
        log("REST_TRACE mode_switched from=$from to=WS_CANDIDATE reason=$reason")
        onModeSwitched?.invoke(from, RestMode.WsCandidate, reason)
    }

    /**
     * Three-valued result of the `ws_alive_60s` proof step inside
     * [transitionToWsActive].
     *
     *   - [NotApplicable] — this `transitionToWsActive` call is not a
     *     sticky-recovery proof (e.g. reason is `ws_outbound_ack` or
     *     sticky is not engaged). The legacy R3.6 `WsActive` transition
     *     runs unchanged.
     *   - [Committed] — sticky was cleared, gate flipped to Open, the
     *     `WsActive` transition fires.
     *   - [Rejected] — the proof was rejected (gate or sticky-epoch
     *     advanced before the probation tick landed). The
     *     `transitionToWsActive` call returns IMMEDIATELY without
     *     touching counters, `_state`, or the mode-switched callback —
     *     so the long-poll path stays armed and a stale tick cannot
     *     silently cancel the in-flight route change by promoting
     *     `RestMode` to `WsActive`.
     */
    private sealed interface ProofOutcome {
        object NotApplicable : ProofOutcome
        object Committed : ProofOutcome
        object Rejected : ProofOutcome
    }

    private suspend fun transitionToWsActive(reason: String) {
        val from = _state.value
        if (from == RestMode.WsActive) return
        // R3.6 cleanup hook + RC-RECONNECT-QUIESCENCE1 ws_alive_60s
        // proof transition.
        //
        // P1 (eighth round): if the proof is rejected (gate or epoch
        // advanced), the whole `transitionToWsActive` call MUST be a
        // no-op — return immediately, no `_state` mutation, no
        // counters reset, no `onModeSwitched` callback. The previous
        // shape proved the gate atomically but still flipped
        // `RestMode → WsActive` unconditionally, so a stale probation
        // tick during a route change could stop REST polling even
        // though the gate correctly remained Quiesced.
        val outcome: ProofOutcome =
            if (mode2StickyEnabled && mode2StickyRestActive &&
                stickyRecovery == StickyRecoveryState.InFlight &&
                reason == "ws_alive_60s"
            ) {
                var clearedLog: String? = null
                val inLock: ProofOutcome = gateLock.withLock {
                    if (stickyRecovery != StickyRecoveryState.InFlight) {
                        return@withLock ProofOutcome.NotApplicable
                    }
                    if (!reconnectQuiescenceEnabled) {
                        // Quiescence not engaged — the R3.6 contract
                        // commits the sticky cleanup unconditionally.
                        // We still mark `Committed` here so the caller
                        // proceeds with the `WsActive` transition.
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

        activeFailCount = 0
        idleFailCount = 0
        candidateEnteredAtMs = null
        _state.value = RestMode.WsActive
        log("REST_TRACE mode_switched from=$from to=WS_ACTIVE reason=$reason")
        onModeSwitched?.invoke(from, RestMode.WsActive, reason)
    }

    /**
     * Inputs the state machine consumes. Wire-up code in the orchestrator (and
     * tests) constructs these from observed transport events.
     */
    sealed class Event {
        /**
         * R3.6 (2026-06-20): emitted immediately after the WS handshake succeeds,
         * before any frames are read. [sessionEpoch] matches the per-session counter
         * in [KtorRelayTransport] so the state machine can correlate `Connected(N)`
         * with its eventual `Ended(N, ...)` for sticky-recovery epoch filtering.
         */
        /**
         * R3.6 (2026-06-20) + RC-RECONNECT-QUIESCENCE1 (2026-06-22).
         *
         * [sessionEpoch] matches the per-session counter in
         * [KtorRelayTransport] so the state machine can correlate
         * `Connected(N)` with its eventual `Ended(N, ...)` for sticky-
         * recovery epoch filtering.
         *
         * [connectionGeneration] is the reconnect-loop's owner generation
         * (allocated once per `runReconnectLoop` invocation via
         * [allocateConnectionGeneration]) and carried unchanged through
         * every auth-retry inside that loop. When a connect event arrives
         * while the gate is [WsReconnectGate.ProbeClaimed], the gate
         * transitions to [WsReconnectGate.CandidateProving] ONLY if the
         * event's [connectionGeneration] matches the claim's
         * `ownerGeneration` AND the claim's `routeEpoch` matches the
         * state-machine's current `routeEpoch`. A mismatch on either is
         * a stale connect from an orphan loop: it is logged and ignored,
         * the token is NOT consumed, and the gate state is NOT changed.
         *
         * Default `-1L` keeps legacy producers source-compatible (the
         * orchestrator's own ack-tick path; tests that don't exercise
         * the gate). Production [KtorRelayTransport] passes its real
         * `connectionGeneration`.
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
         * R3.6: [sessionEpoch] carries the per-session counter so the
         * sticky-recovery state machine can filter stale close events from
         * sessions that are no longer the active recovery candidate.
         * No default — callers MUST supply it (production mapper updated).
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
             *
             * Default `false` keeps prior callers and tests source-compatible.
             * The production producer in `HybridRelayTransport` propagates
             * the value from
             * `phantom.core.transport.WsSessionEndedEvent.okhttpPingTimeoutDetected`
             * via a file-level `internal` extension mapper in
             * `HybridRelayTransport.kt` (`androidMain`) — that mapper is
             * unit-tested in `androidUnitTest` against the exact
             * propagation invariant.
             */
            val okhttpPingTimeoutDetected: Boolean = false,
            /**
             * R3.6 (2026-06-20): per-session epoch from [KtorRelayTransport].
             * Used by the sticky-recovery state machine to distinguish the
             * active recovery candidate's close from stale closes of older
             * sessions. Required; the production producer in [KtorRelayTransport]
             * always supplies the current `mySession` epoch.
             */
            val sessionEpoch: Long,
        ) : Event()

        /**
         * Emitted each time the WS layer surfaces ANY Frame.Text from the
         * relay — Deliver, Ack, application-level Pong-as-Text, malformed
         * payloads, anything. Used to detect "WS is alive enough to push
         * something" and exit [RestMode.RestActive] into the candidate
         * state. The actual content is irrelevant.
         */
        object WsFrameTextReceived : Event()

        /**
         * Emitted on Android `ConnectivityManager` capability changes
         * (Wi-Fi ↔ cellular, network gained/lost). Resets counters because
         * the new path's behaviour is unknown.
         *
         * R3.6 (2026-06-20): converted from `object` to `data class` to carry
         * [clearsMode2Sticky]. When `true`, a sticky REST window armed by Mode-2
         * fast-path actuation enters recovery mode — waiting for a new WS session
         * to prove itself via `ws_alive_60s`. When `false` (e.g. VALIDATED_CHANGED),
         * the sticky window stays armed and the route change is logged as
         * `sticky_kept`.
         *
         * Callers that previously sent `Event.NetworkChanged` (object) must be
         * updated to `Event.NetworkChanged(clearsMode2Sticky = ...)`. All call
         * sites must supply the flag explicitly — no default, so the compiler
         * catches every missing argument.
         */
        data class NetworkChanged(val clearsMode2Sticky: Boolean) : Event()

        /**
         * Emitted when an outbound envelope completes its ACK round-trip
         * over the WS path. Strongest signal that the WS data plane works
         * bidirectionally; commits a candidate session into [RestMode.WsActive]
         * immediately.
         */
        object WsOutboundAckReceived : Event()

        /**
         * Periodic timer tick (driven by the orchestrator's polling/timer
         * loop) used by [RestMode.WsCandidate] to commit into
         * [RestMode.WsActive] after [CANDIDATE_COMMIT_MS] of continuous
         * uptime without a session close.
         */
        object WsAliveTickElapsed : Event()

        /**
         * PR-D1d: a per-envelope ACK deadline expired. [msgId] is the
         * messageId of the envelope that was not acknowledged by the relay
         * within [RelayTransportConfig.ACK_DEADLINE_MS] (10 s). [ageMs] is
         * the actual elapsed time at expiry (always >= ACK_DEADLINE_MS).
         *
         * Emitted by [KtorRelayTransport.outboundAckDeadlineExpired] and
         * forwarded here by [HybridRelayTransport]. Triggers an immediate
         * [RestMode.WsActive] → [RestMode.RestActive] transition, bypassing
         * the two-session-death requirement of the existing
         * [ACTIVE_FAIL_THRESHOLD] counter. The threshold counter continues
         * accumulating independently as a safety net.
         */
        data class ActiveOutboundAckTimeout(val msgId: String, val ageMs: Long) : Event()

        /**
         * PR-RECV-DIAG1 v1.6 — WS is open and was healthy enough for
         * handshake, but no inbound Frame.Text has been received for
         * [sinceLastInboundMs] >= [INBOUND_STALL_THRESHOLD_MS] (60 s).
         * This is the "half-dead inbound" production case observed in
         * test #84.7: Tecno's WS stays connected indefinitely, but
         * relay→Tecno frames (ack, pong, deliver) never arrive. The
         * existing fail-counter and outbound-ack-timeout paths can't
         * detect this case because no session-end happens and no
         * outbound is in flight.
         *
         * Emitted by [KtorRelayTransport.startIdleWatchdog] once per WS
         * session (re-armed on each new session via the per-session
         * pingJob restart). Forwarded by [HybridRelayTransport] into
         * the state machine. Handler: see [onInboundIdleTimeout].
         */
        data class InboundIdleTimeout(val sinceLastInboundMs: Long) : Event()

        /**
         * Trek 2 Stage 2B-B (C5, L9) — REST poll path entered
         * [LongPollBreakerState.Open]. Read-only telemetry signal:
         * the state machine does NOT change [RestMode] in response.
         * Existing [RestMode.{WsActive, WsCandidate, RestActive}]
         * semantics remain governed by their existing inputs.
         *
         * Surfaced so the AppContainer wire-up and the existing
         * [phantom.core.transport.WsDegradationDetector] telemetry
         * can discriminate sustained network failure
         * ([BreakerOpenReason.ConsecutiveRestFailures]) from a
         * `410 Gone` rotation loop
         * ([BreakerOpenReason.Status410Storm]) without parsing log
         * lines.
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
    override suspend fun allocateConnectionGeneration(): Long = gateLock.withLock {
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
    suspend fun currentRouteEpoch(): Long = gateLock.withLock { routeEpoch }

    /**
     * Snapshot of the current [connectionGenerationCounter] under
     * [gateLock] — same rationale as [currentRouteEpoch].
     */
    suspend fun currentConnectionGenerationCounter(): Long =
        gateLock.withLock { connectionGenerationCounter }

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
        val outcome: RouteChangeOutcome = gateLock.withLock {
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
        val result: ProbeIssueResult = gateLock.withLock {
            if (routeEpoch != this.routeEpoch) {
                return@withLock ProbeIssueResult.Rejected(ProbeIssueRejectReason.ROUTE_EPOCH_STALE)
            }
            val current = _gate.value
            if (current !is WsReconnectGate.Quiesced) {
                return@withLock ProbeIssueResult.Rejected(ProbeIssueRejectReason.GATE_NOT_QUIESCED)
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
        gateLock.withLock {
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
        val result: ClaimResult = gateLock.withLock {
            val current = _gate.value
            when (current) {
                is WsReconnectGate.ProbeAvailable -> {
                    if (current.routeEpoch != this.routeEpoch) {
                        return@withLock ClaimResult.Failure(ClaimFailureReason.ROUTE_EPOCH_STALE)
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
                        return@withLock ClaimResult.Failure(ClaimFailureReason.OWNER_GENERATION_STALE)
                    }
                    if (ownerGeneration <= current.generationFloor) {
                        return@withLock ClaimResult.Failure(ClaimFailureReason.OWNER_GENERATION_STALE)
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
                    return gateLock.withLock {
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
    override suspend fun validatePermitAfterAuth(permit: WsReconnectPermit): Boolean = gateLock.withLock {
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
        gateLock.withLock {
            val current = _gate.value
            if (current !is WsReconnectGate.ProbeClaimed) return@withLock
            // Owner-bound budget consumption.
            if (current.routeEpoch != permit.routeEpoch ||
                current.token != permit.token ||
                current.ownerGeneration != permit.ownerGeneration
            ) {
                attemptLog =
                    "REST_TRACE ws_recovery_probe_attempt_ignored " +
                        "reason=permit_owner_mismatch event_owner=${permit.ownerGeneration} " +
                        "claim_owner=${current.ownerGeneration}"
                return@withLock
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
         * From WsActive → RestActive when the client has pending outbound
         * acks (the user just sent something and is waiting for delivery
         * confirmation). Two consecutive zero-inbound sessions are enough
         * because the user-perceived delay of waiting for a third failure
         * is already 60-90 s of bad UX.
         */
        const val ACTIVE_FAIL_THRESHOLD: Int = 2

        /**
         * From WsActive → RestActive when the client has no pending outbound
         * acks (passive idle). Higher threshold because zero-inbound on idle
         * is less load-bearing — it might just mean nobody is sending us
         * anything. Three consecutive failures is the locked compromise.
         */
        const val IDLE_FAIL_THRESHOLD: Int = 3

        /**
         * From WsCandidate → WsActive: the candidate WS session must hold
         * for at least this many ms without a session close, OR receive an
         * outbound ACK round-trip, before committing fully. Test #48 showed
         * isolated successful Frame.Text on otherwise-broken sessions, so a
         * single Frame.Text is not enough to commit.
         */
        const val CANDIDATE_COMMIT_MS: Long = 60_000L

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
