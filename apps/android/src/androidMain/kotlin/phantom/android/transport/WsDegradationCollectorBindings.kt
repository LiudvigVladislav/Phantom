// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import phantom.core.transport.TransportKind
import phantom.core.transport.WsDegradationDetector
import phantom.core.transport.WsSessionEndedEvent

/**
 * PR-WS-HEALTH-STATE1 Commit 3.2a (architect P2-2 Gate 6b, 2026-06-01) —
 * pure-logic collector→detector binding helpers extracted from
 * [HybridRelayTransport.startWsPassthroughCollectors].
 *
 * **Why these are extracted.** The 3.2a design note rev3 § §7 acceptance
 * Gate 6b requires that the collector wiring (specifically, the mapping
 * from `WsSessionEndedEvent.okhttpPingTimeoutDetected = true` to a
 * `WsDegradationDetector.Event.PingTimeout` call) be tested OUTSIDE
 * commonTest. Inlining the wiring inside [HybridRelayTransport] would
 * force the test to instantiate the full `HybridRelayTransport`
 * (`android.util.Log` dependency, ctor parameters, coroutine scope).
 * Extracting the routing into top-level functions keeps Hybrid's call
 * sites a single thin line and gives `WsDegradationCollectorBindingsTest`
 * (androidUnitTest) a clean target.
 *
 * The functions intentionally do NOT take a mutex — they are pure
 * routing. The caller ([HybridRelayTransport.startWsPassthroughCollectors])
 * acquires `wsDegradationMutex` around each invocation, per architect
 * P2-1 (2026-06-01).
 */

/**
 * Routes a [WsSessionEndedEvent] into the detector per design note §5:
 *
 *  - **MANDATORY** [WsDegradationDetector.emitSessionTotal] on every
 *    `WsSessionEnded` close, regardless of any other field — provides
 *    the denominator for calibration ratios (rev2 P2-3).
 *  - If [WsSessionEndedEvent.okhttpPingTimeoutDetected] is `true`,
 *    additionally records a [WsDegradationDetector.Event.PingTimeout]
 *    (the canonical strong signal from Test #83 v7).
 *
 * Order matters: record-and-emit fires FIRST so the verdict log line
 * sees the up-to-date counter; the session_total line follows and
 * resets the per-session counters. A clean close with
 * `okhttpPingTimeoutDetected = false` results in exactly one log
 * (`session_total`), no `WS_DEGRADED detected ...` line. A ping-timeout
 * close results in two (`counter`, `session_total`) plus a verdict
 * line on the rising edge.
 */
internal fun feedDegradationDetectorOnWsSessionEnded(
    detector: WsDegradationDetector,
    event: WsSessionEndedEvent,
    currentKind: TransportKind?,
) {
    if (event.okhttpPingTimeoutDetected) {
        detector.recordAndEmit(
            WsDegradationDetector.Event.PingTimeout,
            currentKind,
        )
    }
    detector.emitSessionTotal(
        sessionDurationMs = event.durationMs,
        closeKind = event.closeOrigin,
    )
}

/**
 * Routes an outbound-ack-deadline expiry into the detector as a
 * [WsDegradationDetector.Event.AckTimeout]. The caller has already
 * verified `restCapabilityActive` and `stateMachine.current == WsActive`
 * before invoking this — those gates remain in [HybridRelayTransport].
 */
internal fun feedDegradationDetectorOnAckTimeout(
    detector: WsDegradationDetector,
    currentKind: TransportKind?,
) {
    detector.recordAndEmit(
        WsDegradationDetector.Event.AckTimeout,
        currentKind,
    )
}

/**
 * Routes an inbound-stall event into the detector as a
 * [WsDegradationDetector.Event.IdleTimeout]. The caller has already
 * verified `restCapabilityActive` and `stateMachine.current == WsActive`
 * before invoking this — those gates remain in [HybridRelayTransport].
 */
internal fun feedDegradationDetectorOnInboundStalled(
    detector: WsDegradationDetector,
    currentKind: TransportKind?,
) {
    detector.recordAndEmit(
        WsDegradationDetector.Event.IdleTimeout,
        currentKind,
    )
}
