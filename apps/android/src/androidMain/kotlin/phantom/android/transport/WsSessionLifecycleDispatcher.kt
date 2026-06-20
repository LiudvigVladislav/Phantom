// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import kotlinx.coroutines.CancellationException
import phantom.core.transport.RestStateMachine
import phantom.core.transport.WsSessionEndedEvent
import phantom.core.transport.WsSessionLifecycleEvent

/**
 * R3.6 lifecycle dispatcher — single ordered consumer of the
 * [phantom.core.transport.KtorRelayTransport.wsSessionLifecycle] flow.
 *
 * Extracted from [HybridRelayTransport] so unit tests drive the exact
 * production code path with mock callbacks, instead of re-implementing the
 * `when` arms or asserting type-shape proxies.
 *
 * Each event arm is small enough to inline at the call site, but the contract
 * the class enforces is larger than any single arm:
 *
 * - **Connected**: forwards a [RestStateMachine.Event.WsSessionConnected]
 *   via [submitStateEvent]. No bootstrap retry. No detector feed.
 *
 * - **Ended**: routes to [submitStateEvent] (when [restCapabilityActiveProvider]
 *   returns `true`) or [maybeRetryBootstrap] (when it returns `false`). Then
 *   **always** invokes [feedDegradationDetector] so the calibration denominator
 *   stays honest from cold start. The mutex-and-null-detector guarding is the
 *   caller's responsibility, scoped inside the [feedDegradationDetector] lambda.
 *
 * - **IMPL-LOCK #4 supervised body**: [CancellationException] always rethrows
 *   for structured concurrency. Any other [Throwable] is reported via
 *   [errorLogger] with event kind + epoch + error class, and the dispatch
 *   returns normally so the upstream collector keeps consuming subsequent
 *   events. Continue-on-error is preferred to a silently-dead consumer that
 *   leaves the underlying channel accumulating events forever.
 */
internal class WsSessionLifecycleDispatcher(
    private val submitStateEvent: suspend (RestStateMachine.Event) -> Unit,
    private val maybeRetryBootstrap: suspend () -> Unit,
    private val feedDegradationDetector: suspend (WsSessionEndedEvent) -> Unit,
    private val errorLogger: (String) -> Unit,
    private val restCapabilityActiveProvider: () -> Boolean,
) {
    suspend fun dispatch(event: WsSessionLifecycleEvent) {
        try {
            when (event) {
                is WsSessionLifecycleEvent.Connected -> {
                    submitStateEvent(
                        RestStateMachine.Event.WsSessionConnected(event.sessionEpoch)
                    )
                }
                is WsSessionLifecycleEvent.Ended -> {
                    if (restCapabilityActiveProvider()) {
                        submitStateEvent(event.toRestStateMachineEvent())
                    } else {
                        maybeRetryBootstrap()
                    }
                    feedDegradationDetector(event.toLegacyEndedEvent())
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val (kind, epoch) = when (event) {
                is WsSessionLifecycleEvent.Connected -> "Connected" to event.sessionEpoch
                is WsSessionLifecycleEvent.Ended -> "Ended" to event.sessionEpoch
            }
            errorLogger(
                "LIFECYCLE_CONSUMER_ERROR event_kind=$kind " +
                    "epoch=$epoch " +
                    "error=${t::class.simpleName} msg=${t.message?.take(120)}"
            )
        }
    }
}
