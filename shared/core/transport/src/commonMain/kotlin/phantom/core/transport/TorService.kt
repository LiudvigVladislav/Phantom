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
     * Start the embedded tor daemon and bootstrap a circuit using
     * [bridgeProfile] as the pluggable-transport mix. Idempotent — if
     * already running, this is a no-op (the existing profile is kept;
     * callers must [stop] first to switch profiles).
     *
     * The default is [BridgeProfile.Mixed] for backward compatibility
     * with code that does not yet care about per-profile rotation
     * (PR-C bridge-rotation lives in TransportManager).
     */
    suspend fun start(bridgeProfile: BridgeProfile = BridgeProfile.Mixed)

    /**
     * Stop the embedded tor daemon and tear down all circuits. Idempotent.
     * Returns when the daemon has fully exited; [state] becomes
     * [TorState.Off]. The persisted DataDirectory is preserved across stops
     * so guard caching survives the next [start].
     */
    suspend fun stop()
}

/**
 * Pluggable-transport mix for [TorService.start]. PR-C (2026-05-11)
 * uses this to walk one censorship-resistance profile at a time:
 *
 *   1. [Obfs4Only]      — uniform-random byte stream, no TLS shape;
 *                         best on networks where DPI flags TLS-like
 *                         transports (TSPU 16-KB curtain).
 *   2. [WebtunnelOnly]  — looks like vanilla HTTPS on port 443 to
 *                         our operator-controlled bridge; works where
 *                         obfs4 ports are reset.
 *   3. [SnowflakeOnly]  — WebRTC DataChannel via volunteer browser
 *                         proxies; best where the broker fronting
 *                         domain is reachable (often unreliable on
 *                         RU carriers without a VPN).
 *   4. [Mixed]          — full obfs4 + webtunnel + snowflake stack
 *                         in declared order; the all-of-the-above
 *                         fallback that matches pre-PR-C behaviour.
 *
 * The androidMain `TorServiceAndroid.start()` maps each profile to a
 * concrete `List<String>` of bridge lines built from `OperatorBridges`
 * and `SnowflakeBridges`. Profiles that resolve to an empty list (e.g.
 * `Obfs4Only` if no obfs4 bridge is provisioned) fall back to running
 * tor with `disableBridges()` rather than starting with no bridges at
 * all — that path is then equivalent to the direct-guards path and
 * will fail fast on censored networks, which is the right signal for
 * the rotation walker to advance to the next profile.
 */
enum class BridgeProfile(val displayName: String) {
    Obfs4Only("obfs4"),
    WebtunnelOnly("webtunnel"),
    SnowflakeOnly("snowflake"),
    Mixed("mixed"),
}
