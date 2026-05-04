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
