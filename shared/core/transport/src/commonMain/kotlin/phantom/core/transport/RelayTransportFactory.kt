// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient

/**
 * Returns a factory that builds a fresh [HttpClient] each time it is invoked.
 *
 * KtorRelayTransport calls the factory once per reconnect generation and
 * closes the resulting client in the generation's `finally` block. Per-
 * generation clients let us recover from a deadlocked WebSocket reader on
 * Tecno HiOS (and any OEM with similar Wi-Fi-radio parking behaviour) by
 * destroying the entire OkHttp engine on pong/ack timeout. See ADR-010
 * "Updated 2026-05-01" for the full reasoning.
 *
 * Factory parameter (ADR-016 Stage 2C):
 *  - `socksProxyPort = null` → direct connection (existing behaviour).
 *  - `socksProxyPort = N` → route through `127.0.0.1:N` SOCKS5 (the
 *    embedded tor's auto-bound port discovered via [TorService.state]).
 *
 * The port is taken at invocation time, not at factory creation, so a
 * single transport instance can switch between direct and Tor modes
 * across reconnect generations. Privacy-Mode changes therefore translate
 * to a `disconnect()` + `connect(... socksProxyPort = newPort)` rather
 * than re-instantiating the transport.
 *
 * This is the WebSocket-side factory only. REST traffic uses
 * [createRestHttpClient] which returns a long-lived shared client suitable
 * for connection pooling across short HTTP calls.
 */
expect fun createHttpClientFactory(): (socksProxyPort: Int?) -> HttpClient

/**
 * Returns a long-lived [HttpClient] for REST traffic (PreKey publish /
 * fetch, future /me endpoints). One instance per app lifetime — connection
 * pooling and TLS session reuse across short HTTP calls is desirable.
 *
 * Distinct from [createHttpClientFactory] because the WebSocket path
 * deliberately recreates the engine on every reconnect and the REST path
 * does not benefit from that.
 */
expect fun createRestHttpClient(): HttpClient

/**
 * Returns a dedicated [HttpClient] for POST /prekeys/publish only.
 *
 * WHY A SEPARATE CLIENT: Tele2 LTE Иркутская 2026-05-14 Test #42 showed
 * POST /prekeys/publish consistently failing with Caddy `bytes_read=0`,
 * `status=408`, `duration=30s` and client-side `SocketTimeoutException`
 * from `Http1ExchangeCodec.readResponseHeaders`. The 13.9 KB request body
 * never reached the wire despite the shared `createRestHttpClient()` already
 * being pinned to HTTP/1.1 (PR-G4). Root cause: OkHttp's connection pool
 * reuses an existing TCP connection that a carrier-side NAT or transparent
 * proxy silently half-closed. The server sees no body bytes and times out
 * after 30 s. The fix is to give publish its own client with:
 *
 *   * `ConnectionPool(0)` — no pooling; every publish gets a fresh
 *     TCP+TLS handshake to rule out reuse of a half-closed connection.
 *   * `Connection: close` interceptor — instructs the server (and any
 *     transparent proxy) to tear down the TCP connection after the
 *     response, preventing silent reuse further down the chain.
 *   * Large `writeTimeout` (60 s) — the 13.9 KB body upload needs a
 *     fair per-byte budget on a lossy mobile connection.
 *   * Large `callTimeout` (120 s) — outer ceiling; prevents a stall from
 *     consuming a coroutine slot indefinitely.
 *
 * Only POST /prekeys/publish uses this client. GET /prekeys/status and
 * GET /prekeys/bundle/{peer} keep the shared `createRestHttpClient()`
 * client (small GETs, connection reuse is fine for them).
 *
 * @deprecated Replaced by [createPreKeyPublishHttpTransport] in PR-R0.1.
 *             This Ktor-based path hangs at 8192 bytes on body upload due
 *             to a Ktor→OkHttp ByteChannel streaming bug (Test #43, 2026-05-15).
 *             The function is kept to avoid breaking the public expect/actual
 *             surface; production DI no longer calls it.
 *
 * PR-R0, 2026-05-15.
 */
@Deprecated(
    message = "Replaced by createPreKeyPublishHttpTransport (PR-R0.1). " +
        "This Ktor-based path stalls at 8192 bytes on Android body upload. " +
        "Production DI uses createPreKeyPublishHttpTransport instead.",
    replaceWith = ReplaceWith("createPreKeyPublishHttpTransport()"),
)
expect fun createPreKeyPublishHttpClient(): HttpClient

/**
 * Returns a [PreKeyPublishHttpTransport] for POST /prekeys/publish.
 *
 * PR-R0.1 (2026-05-15): replaces [createPreKeyPublishHttpClient].
 *
 * Test #43 on real device showed that the Ktor→OkHttp engine adapter stalls
 * the request body deterministically at exactly 8192 bytes — matching the
 * JVM ByteChannel default write-buffer size — on both Tele2 LTE Иркутская
 * and emulator Direct. After 30 s Caddy returns HTTP 408. The bug is in
 * the Ktor body streaming path, not in network filtering.
 *
 * The Android actual bypasses Ktor entirely and uses native OkHttp with a
 * pre-built ByteArray body, which OkHttp writes in one shot via
 * okio.Buffer.writeAll() without any streaming. All PR-R0 reliability
 * machinery (mutex, retry, backoff, PREKEY_TRACE logging) is preserved
 * in [PreKeyApiClient]; this factory only supplies the transport.
 *
 * Non-Android actuals (iOS, JVM) throw [NotImplementedError] because the
 * streaming bug is Android-only and iOS/desktop are not production paths.
 */
expect fun createPreKeyPublishHttpTransport(): PreKeyPublishHttpTransport

/**
 * Returns a [RestFallbackTransport] for the new REST short-poll fallback
 * endpoints (`/auth/session`, `/relay/send`, `/relay/poll`, `/relay/ack-deliver`)
 * introduced in PR-D0r / PR-D1.
 *
 * PR-D1 (2026-05-16): the Android actual is the native-OkHttp transport
 * mirroring [createPreKeyPublishHttpTransport]'s design — fresh client per
 * call, HTTP/1.1 pinned, `Connection: close`, no connection pool sharing,
 * client-owned retry semantics. Non-Android actuals throw
 * [NotImplementedError] because REST fallback is currently an
 * Android-only production path (parity with PR-R0.1's posture).
 *
 * The orchestrator ([RestFallbackOrchestrator]) is the higher-level
 * consumer of this transport — it adds token management, capability
 * gating, retry loop, and the adaptive poll loop on top of the bare
 * I/O surface this factory exposes.
 */
/**
 * Trek 2 Stage 2A (A4) — `socksProxyPort` is the local TCP port on which a
 * SOCKS5 proxy listens for the call. When non-null, the Android actual
 * binds the OkHttp client to `Proxy.Type.SOCKS @ 127.0.0.1:<port>`; when
 * null (the Stage 2 Standard mode default), the client connects directly.
 * Stages 3 / 4 will plumb in the live Reality / Tor ports from the
 * transport-strategy layer. iOS / JVM actuals throw [NotImplementedError]
 * regardless of the parameter — REST fallback remains Android-only for
 * the current production path.
 *
 * Default `null` preserves byte-identical behaviour for every existing
 * call site that constructs the transport without specifying the port.
 */
expect fun createRestFallbackTransport(
    socksProxyPort: Int? = null,
    /**
     * Round 12 step 2 — debug-only diagnostic toggle for per-chunk
     * response-body byte accounting on the REST `/relay/poll` path.
     * Wired by the application module (Android) from
     * `BuildConfig.DEBUG`. Defaults to `false` so every existing call
     * site preserves byte-identical behaviour. iOS / JVM actuals
     * accept the parameter but ignore it — they throw
     * [NotImplementedError] regardless, identical to the existing
     * `socksProxyPort` posture.
     */
    debugBodyLogging: Boolean = false,
): RestFallbackTransport

/**
 * Force-interrupts every thread in the active WebSocket engine's pool.
 *
 * Why this exists: Ktor's `HttpClient.close()` for the OkHttp engine
 * calls `OkHttpClient.dispatcher.executorService.shutdown()` — a
 * GRACEFUL shutdown that waits for running tasks to finish on their
 * own. The WebSocket reader thread is parked in a kernel `recv()`
 * syscall on a TCP socket whose Wi-Fi radio has been parked by the
 * OEM. Graceful shutdown does not interrupt kernel syscalls; the
 * reader stays parked until the OS resumes the radio (observed:
 * 60+ seconds on Tecno HiOS).
 *
 * `shutdownNow()` sends `InterruptedException` to every pool thread,
 * which propagates `SocketException: Socket closed` from the kernel
 * read and unblocks the reader within milliseconds. After this call
 * the engine is dead — no further requests can be served — but that
 * is fine because we are about to close the client anyway in
 * `runReconnectLoop`'s finally block.
 *
 * Implementations: best-effort, never throw. Engines without a
 * comparable mechanism (Darwin) may be a no-op — they don't have the
 * Tecno HiOS problem because iOS doesn't park Wi-Fi the same way.
 */
expect fun forceShutdownActiveEngine()
