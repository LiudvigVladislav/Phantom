// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle facade for an embedded Tor client (ADR-016 Stage 2).
 *
 * Stage 2A (this file) defines the contract only — Stage 2B implements the
 * Android side with kmp-tor's `TorRuntime`, and Stage 2C wires the SOCKS
 * proxy into [KtorRelayTransport] so the WebSocket can be tunnelled through
 * the relay's v3 onion service.
 *
 * The implementation owns its own bootstrap-and-keep-alive lifecycle. It is
 * NOT bound to a particular foreground service — callers (typically
 * `PhantomMessagingService` on Android) are expected to invoke [start] when
 * Tor traffic is required and [stop] on app shutdown / Privacy-Mode change.
 *
 * Thread-safety: all methods are safe to call from any coroutine context;
 * the implementation marshals to its own internal scope. [state] is a
 * conflated [StateFlow] suitable for direct UI observation.
 */
interface TorService {
    /**
     * Current bootstrap and connection state. Observers receive an immediate
     * replay of the latest value on collect; transitions are conflated, so
     * fast progress jumps may be coalesced.
     */
    val state: StateFlow<TorState>

    /**
     * Start the embedded tor daemon and bootstrap a circuit. Idempotent — if
     * already running, this is a no-op. Returns when the daemon process
     * (or in-process JNI runtime) has been launched; [state] then transitions
     * through [TorState.Bootstrapping] to [TorState.Ready] asynchronously.
     */
    suspend fun start()

    /**
     * Stop the embedded tor daemon and tear down all circuits. Idempotent.
     * Returns when the daemon has fully exited; [state] becomes
     * [TorState.Off]. The persisted DataDirectory is preserved across stops
     * so guard caching survives the next [start].
     */
    suspend fun stop()
}
