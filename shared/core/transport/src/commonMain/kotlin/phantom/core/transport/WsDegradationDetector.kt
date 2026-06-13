// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * PR-WS-HEALTH-STATE1 Commit 3.2a (2026-06-01) — telemetry-first WS
 * degradation detector.
 *
 * Counts weighted degradation events in a sliding window and emits
 * structured dry-run logs about WHEN it WOULD have triggered a chain
 * rewalk and a suspect mark, **without taking any action**. Calibration
 * for 3.2b action thresholds runs against the logs this detector emits
 * in the field.
 *
 * Design note: `docs/tracks/ws-health-state.md` § Commit 3.2a (rev3).
 *
 * Why telemetry-first: Test #83 v7 proved the Tecno Tele2 LTE noise
 * floor is two `okhttp_ping_timeout_detected=true` sessions in a
 * 7-minute window (`test83-v7-tecno.log:164/:165` and `:426/:427`).
 * Shipping action against the candidate `2 strong / 5 min` threshold
 * would therefore fire on routine carrier behaviour, not on Direct
 * degrading relative to the other chain links.
 *
 * Rev3-locked semantics (do NOT alter without updating the design note):
 *
 *  - **Direct-only suspect at verdict construction.** `wouldMarkSuspect`
 *    is forced to `false` whenever the caller-supplied `currentKind` is
 *    not [TransportKind.Direct], regardless of any counter value. This
 *    locks D2 at the type-system boundary so a downstream 3.2b PR
 *    cannot accidentally interpret a Reality / Tor verdict as
 *    "would suspect Reality / Tor".
 *
 *  - **WsCandidate gate.** When [stateProvider] reports
 *    [RestMode.WsCandidate], `wouldRewalk` is forced to `false` and
 *    `gatedByWsCandidate` is set to `true`. WsCandidate means Direct is
 *    actively attempting recovery; firing a rewalk into that recovery
 *    window is the same pathology PR-R0.4b removed under a new name.
 *
 *  - **`stateProvider` is bound to `restOrchestrator.stateMachine.current`
 *    directly** at construction time in [phantom.android.di.AppContainer].
 *    Not to a nullable `hybridTransport?.stateMachine?.current` —
 *    that shape would re-introduce the "elvis-once" class of bug
 *    Commit 3.1 rev3 already paid for.
 *
 *  - **No persistence.** All state is in-memory. Process death resets
 *    the sliding window and per-session counters; that is intentional
 *    per the Tor lock-in foot-gun rationale in the design note rev2 D2.
 *
 * Threading: not thread-safe. Caller serialises all mutating calls
 * onto a single coroutine context (the Hybrid collector serialises
 * by `scope.launch` per source; the three sources flow through a
 * single mutex at the wire-up site). [evaluate] is a pure read and
 * is safe to call concurrently with itself but not with [record] or
 * the emit methods.
 *
 * Out of scope (deferred to 3.2b code PR, NOT in this class):
 *
 *  - No `markSuspect` action. This detector only logs.
 *  - No call to any `TransportRewalkCoordinator` method.
 *  - No `NetworkChangeReason.WS_DEGRADED` interaction.
 *  - No `TransportPreferences` writes.
 *  - No `reorderChain` filter wiring.
 *  - No `ConnectionUiState` changes (U2 dropped from 3.2a per
 *    design note §9 precedence audit).
 *  - No U1 (network-generation-aware suspect clear) — recorded
 *    decision only, no code, because the suspect store itself does
 *    not exist yet.
 */
class WsDegradationDetector(
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
    private val stateProvider: () -> RestMode = { RestMode.WsActive },
    private val windowMs: Long = RelayTransportConfig.WS_DEGRADED_WINDOW_MS,
    private val pingTimeoutThreshold: Int =
        RelayTransportConfig.WS_DEGRADED_CANDIDATE_PING_THRESHOLD,
    private val weightedSumThreshold: Double =
        RelayTransportConfig.WS_DEGRADED_CANDIDATE_WEIGHTED_THRESHOLD,
    private val pingTimeoutWeight: Double =
        RelayTransportConfig.WS_DEGRADED_PING_WEIGHT,
    private val ackTimeoutWeight: Double =
        RelayTransportConfig.WS_DEGRADED_ACK_WEIGHT,
    private val idleTimeoutWeight: Double =
        RelayTransportConfig.WS_DEGRADED_IDLE_WEIGHT,
) {

    /** Inputs the detector consumes. Mapped from existing WS events upstream. */
    sealed class Event {
        /** Maps from `WsSessionEndedEvent.okhttpPingTimeoutDetected = true`. */
        object PingTimeout : Event()

        /** Maps from `InboundStalledEvent`. */
        object IdleTimeout : Event()

        /** Maps from `OutboundAckDeadlineExpiredEvent`. */
        object AckTimeout : Event()
    }

    /**
     * Dry-run verdict. The detector NEVER acts on this — it is logged
     * for calibration and exposed for tests.
     */
    data class Verdict(
        val wouldRewalk: Boolean,
        val wouldMarkSuspect: Boolean,
        val gatedByWsCandidate: Boolean,
        val pingTimeoutCount: Int,
        val idleTimeoutCount: Int,
        val ackTimeoutCount: Int,
        val weightedSum: Double,
        val windowMs: Long,
        val state: RestMode,
        val currentKind: TransportKind?,
    )

    private data class TimedEvent(val tsMs: Long, val kind: Event)

    // Cross-session sliding window. Persists across WS session boundaries
    // so a chronic Direct degradation that spans multiple short sessions
    // accumulates rather than resets to zero each reconnect.
    private val window = ArrayDeque<TimedEvent>()

    // Per-session counters. Reset by [emitSessionTotal] after each
    // `WsSessionEnded` close so the per-session log line reflects only
    // events from that one session.
    private var sessionPingCount: Int = 0
    private var sessionIdleCount: Int = 0
    private var sessionAckCount: Int = 0

    // Rising-edge tracking for `WS_DEGRADED detected` emission. The
    // detector logs only on the rising edge of `wouldRewalk` or
    // `wouldMarkSuspect`, plus on every `gatedByWsCandidate=true`
    // occurrence (those are informative for calibration — we want
    // to see when a trigger was suppressed by the WsCandidate gate).
    private var prevWouldRewalk: Boolean = false
    private var prevWouldMarkSuspect: Boolean = false

    /**
     * Append [event] to the sliding window and bump the per-session
     * counter. Does NOT compute a verdict and does NOT emit logs — the
     * caller invokes [emitCounterTick] and [emitVerdictIfRising]
     * explicitly (or uses the [recordAndEmit] convenience).
     */
    fun record(event: Event) {
        val ts = now().coerceAtLeast(0)
        pruneOldest(ts)
        window.addLast(TimedEvent(ts, event))
        when (event) {
            Event.PingTimeout -> sessionPingCount++
            Event.IdleTimeout -> sessionIdleCount++
            Event.AckTimeout -> sessionAckCount++
        }
    }

    /**
     * Pure verdict computation. No mutation, no log emission. Safe to
     * call from any context. Enforces Direct-only suspect and the
     * WsCandidate gate at construction.
     */
    fun evaluate(currentKind: TransportKind?): Verdict {
        val ts = now().coerceAtLeast(0)
        pruneOldest(ts)
        var ping = 0
        var idle = 0
        var ack = 0
        for (e in window) {
            when (e.kind) {
                Event.PingTimeout -> ping++
                Event.IdleTimeout -> idle++
                Event.AckTimeout -> ack++
            }
        }
        val weighted =
            ping * pingTimeoutWeight +
                ack * ackTimeoutWeight +
                idle * idleTimeoutWeight
        val state = stateProvider()
        val rawWouldRewalk =
            ping >= pingTimeoutThreshold || weighted >= weightedSumThreshold
        val gated = state == RestMode.WsCandidate
        val wouldRewalk = rawWouldRewalk && !gated
        // Rev3 P3-6: Direct-only suspect lock. Forced false for any
        // non-Direct kind regardless of counter state.
        val wouldMarkSuspect = wouldRewalk && currentKind == TransportKind.Direct
        return Verdict(
            wouldRewalk = wouldRewalk,
            wouldMarkSuspect = wouldMarkSuspect,
            gatedByWsCandidate = rawWouldRewalk && gated,
            pingTimeoutCount = ping,
            idleTimeoutCount = idle,
            ackTimeoutCount = ack,
            weightedSum = weighted,
            windowMs = windowMs,
            state = state,
            currentKind = currentKind,
        )
    }

    /**
     * Convenience: [record] + [emitCounterTick] + [emitVerdictIfRising]
     * in one call. The Hybrid collector wiring uses this so the rising-
     * edge log fires immediately after the event lands.
     */
    fun recordAndEmit(event: Event, currentKind: TransportKind?) {
        record(event)
        emitCounterTick(event)
        emitVerdictIfRising(currentKind)
    }

    /**
     * Emit the `WS_DEGRADED_TELEMETRY counter ...` line. One line per
     * [record] call so calibration sees the growth shape across a
     * session, not just terminal values.
     */
    fun emitCounterTick(event: Event) {
        val ts = now().coerceAtLeast(0)
        pruneOldest(ts)
        var weighted = 0.0
        for (e in window) {
            weighted += when (e.kind) {
                Event.PingTimeout -> pingTimeoutWeight
                Event.IdleTimeout -> idleTimeoutWeight
                Event.AckTimeout -> ackTimeoutWeight
            }
        }
        val countNow = when (event) {
            Event.PingTimeout -> sessionPingCount
            Event.IdleTimeout -> sessionIdleCount
            Event.AckTimeout -> sessionAckCount
        }
        val kindLabel = when (event) {
            Event.PingTimeout -> "ping"
            Event.IdleTimeout -> "idle"
            Event.AckTimeout -> "ack"
        }
        log(
            "WS_DEGRADED_TELEMETRY counter kind=$kindLabel count_now=$countNow " +
                "weighted_now=$weighted",
        )
    }

    /**
     * Emit the structured `WS_DEGRADED detected ...` line if the verdict
     * crosses a rising edge (false → true) on `wouldRewalk` or
     * `wouldMarkSuspect`, OR if `gatedByWsCandidate` fired. The previous
     * edge state is updated on every call so a falling edge is silent
     * and a re-rising edge fires once more.
     */
    fun emitVerdictIfRising(currentKind: TransportKind?) {
        val v = evaluate(currentKind)
        val risingRewalk = v.wouldRewalk && !prevWouldRewalk
        val risingSuspect = v.wouldMarkSuspect && !prevWouldMarkSuspect
        val gatedNow = v.gatedByWsCandidate
        if (risingRewalk || risingSuspect || gatedNow) {
            log(
                "WS_DEGRADED detected current_kind=${v.currentKind ?: "unknown"} " +
                    "would_rewalk=${v.wouldRewalk} " +
                    "would_mark_suspect=${v.wouldMarkSuspect} " +
                    "gated_by_ws_candidate=${v.gatedByWsCandidate} " +
                    "ping_timeout_count=${v.pingTimeoutCount} " +
                    "idle_timeout_count=${v.idleTimeoutCount} " +
                    "ack_timeout_count=${v.ackTimeoutCount} " +
                    "weighted_sum=${v.weightedSum} " +
                    "window_ms=${v.windowMs} " +
                    "state_machine=${v.state}",
            )
        }
        prevWouldRewalk = v.wouldRewalk
        prevWouldMarkSuspect = v.wouldMarkSuspect
    }

    /**
     * Emit the MANDATORY per-session-close telemetry line and reset
     * per-session counters. Fired by the WsSessionEnded collector on
     * every close, regardless of trigger state. Provides the denominator
     * for calibration ratios per design note §6 rev2 P2-3.
     */
    fun emitSessionTotal(sessionDurationMs: Long, closeKind: String) {
        log(
            "WS_DEGRADED_TELEMETRY session_total " +
                "ping_in_session=$sessionPingCount " +
                "idle_in_session=$sessionIdleCount " +
                "ack_in_session=$sessionAckCount " +
                "session_duration_ms=$sessionDurationMs " +
                "close_kind=$closeKind " +
                "on_close=true",
        )
        sessionPingCount = 0
        sessionIdleCount = 0
        sessionAckCount = 0
    }

    /**
     * Mirror existing `REST_TRACE mode_switched` reasons into the
     * `WS_DEGRADED_TELEMETRY` stream so calibration can correlate
     * detector verdicts with state-machine transitions.
     */
    fun emitStateTransitionSeen(reason: String) {
        log("WS_DEGRADED_TELEMETRY state_transition_seen reason=$reason")
    }

    /**
     * Trek 2 Stage 2B-B (C5, L9; round-2 review-fix P2) — mirror
     * the typed [BreakerOpenReason] from
     * [RestStateMachine.Event.RestPollDegraded] into the
     * `WS_DEGRADED_TELEMETRY` stream. Lets calibration discriminate
     * sustained network failure
     * ([BreakerOpenReason.ConsecutiveRestFailures]) from a `410 Gone`
     * rotation loop ([BreakerOpenReason.Status410Storm]) without
     * parsing the `REST_TRACE breaker_open` log substring.
     *
     * Action-less per design note §6 — only emits a structured log
     * line; no detector state mutation. Safe to call from any
     * context.
     */
    fun emitRestPollDegradedSeen(reason: BreakerOpenReason) {
        log("WS_DEGRADED_TELEMETRY rest_poll_degraded_seen reason=$reason")
    }

    private fun pruneOldest(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (window.isNotEmpty() && window.first().tsMs < cutoff) {
            window.removeFirst()
        }
    }

    /** Visible for tests. */
    internal fun windowSizeForTest(): Int = window.size

    /** Visible for tests. */
    internal fun sessionCountsForTest(): Triple<Int, Int, Int> =
        Triple(sessionPingCount, sessionIdleCount, sessionAckCount)
}
