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
 * This is the WebSocket-side factory only. REST traffic uses
 * [createRestHttpClient] which returns a long-lived shared client suitable
 * for connection pooling across short HTTP calls.
 */
expect fun createHttpClientFactory(): () -> HttpClient

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
