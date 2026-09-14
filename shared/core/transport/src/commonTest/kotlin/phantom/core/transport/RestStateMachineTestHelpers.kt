// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.runBlocking

/**
 * RC-RECONNECT-QUIESCENCE1 (2026-06-22) commonTest helper.
 *
 * [RestStateMachine.onEvent] became `suspend` so the gate-mutating
 * event handlers can acquire the gateLock atomically. Most existing
 * tests are written as plain non-suspend `@Test fun` bodies. To avoid
 * a mass rewrite of those tests, this helper wraps each call in a
 * `runBlocking`. Use only in tests that are NOT already inside a
 * coroutine context (most pre-2026-06-22 tests fall in this bucket).
 *
 * Tests that already use `runTest` / `runBlocking` should call
 * `sm.onEvent(...)` directly.
 */
internal fun RestStateMachine.onEventNow(event: RestStateMachine.Event) {
    runBlocking { onEvent(event) }
}

/**
 * Same shape for [RestFallbackOrchestrator.submitEvent], which also
 * became `suspend` as the gate machinery propagated up.
 */
internal fun RestFallbackOrchestrator.submitEventNow(event: RestStateMachine.Event) {
    runBlocking { submitEvent(event) }
}

/**
 * Stage 2 (2026-09-13): the canonical way to put the machine into
 * [RestMode.RestActive] for a test whose subject is something else.
 *
 * It is a `Connected` followed by that same session's `Ended`, because
 * Stage 2 decides by session identity: a close of a session the machine
 * never saw connect is stale and is ignored, and one live session end
 * degrades (the two- and three-strike counters are gone -- a close IS
 * the evidence the socket is not carrying traffic).
 *
 * Returns the epoch used, so a caller can keep addressing the same
 * session or deliberately address another.
 */
internal suspend fun RestStateMachine.driveToRestActive(sessionEpoch: Long = 1L): Long {
    onEvent(RestStateMachine.Event.WsSessionConnected(sessionEpoch = sessionEpoch))
    onEvent(
        RestStateMachine.Event.WsSessionEnded(
            durationMs = 1_000L,
            inboundFrames = 0,
            pendingAcksAtClose = 1,
            sessionEpoch = sessionEpoch,
        ),
    )
    check(state.value == RestMode.RestActive) {
        "expected RestActive after a live session ended; was ${state.value}"
    }
    return sessionEpoch
}

/** Non-suspend variant of [driveToRestActive] for plain `@Test fun` bodies. */
internal fun RestStateMachine.driveToRestActiveNow(sessionEpoch: Long = 1L): Long =
    runBlocking { driveToRestActive(sessionEpoch) }

/** Drive the orchestrator's machine into [RestMode.RestActive]. */
internal suspend fun RestFallbackOrchestrator.driveToRestActive(sessionEpoch: Long = 1L): Long {
    submitEvent(RestStateMachine.Event.WsSessionConnected(sessionEpoch = sessionEpoch))
    submitEvent(
        RestStateMachine.Event.WsSessionEnded(
            durationMs = 1_000L,
            inboundFrames = 0,
            pendingAcksAtClose = 1,
            sessionEpoch = sessionEpoch,
        ),
    )
    check(stateMachine.state.value == RestMode.RestActive) {
        "expected RestActive after a live session ended; was ${stateMachine.state.value}"
    }
    return sessionEpoch
}

/** Non-suspend variant for plain `@Test fun` bodies. */
internal fun RestFallbackOrchestrator.driveToRestActiveNow(sessionEpoch: Long = 1L): Long =
    runBlocking { driveToRestActive(sessionEpoch) }

/**
 * The other direction: drive the orchestrator's machine INTO
 * [RestMode.WsActive].
 *
 * Review round 7 (2026-09-13) moved the machine's initial mode from
 * `WsActive` to `RestActive`, because a handshake is no longer read as
 * proof. `WsActive` is the mode in which the LEGACY `pollLoop` does not
 * run, so every fixture whose subject is the parallel `wsActivePollLoop`
 * alone used to get its isolation for free and now has to ask for it.
 *
 * The mode is established by the proof the contract names (B3): a live
 * session, then an ACK round-trip on that same session. Nothing here
 * bypasses the state machine.
 */
internal suspend fun RestFallbackOrchestrator.driveToWsActive(sessionEpoch: Long = 1L): Long {
    submitEvent(RestStateMachine.Event.WsSessionConnected(sessionEpoch = sessionEpoch))
    submitEvent(RestStateMachine.Event.WsOutboundAckReceived(sessionEpoch = sessionEpoch))
    check(stateMachine.state.value == RestMode.WsActive) {
        "expected WsActive after a proven session; was ${stateMachine.state.value}"
    }
    return sessionEpoch
}

/** Non-suspend variant for plain `@Test fun` bodies. */
internal fun RestFallbackOrchestrator.driveToWsActiveNow(sessionEpoch: Long = 1L): Long =
    runBlocking { driveToWsActive(sessionEpoch) }

/**
 * Stage 2: prove a candidate session the way the contract allows --
 * one inbound frame plus the dwell, confirmed by a tick that names the
 * same session. [advanceClock] must move the injected clock past
 * [RestStateMachine.CANDIDATE_COMMIT_MS].
 */
internal suspend fun RestStateMachine.proveCandidateByDwell(
    sessionEpoch: Long,
    advanceClock: () -> Unit,
) {
    onEvent(RestStateMachine.Event.WsFrameTextReceived(sessionEpoch = sessionEpoch))
    advanceClock()
    onEvent(RestStateMachine.Event.WsAliveTickElapsed(sessionEpoch = sessionEpoch))
}
