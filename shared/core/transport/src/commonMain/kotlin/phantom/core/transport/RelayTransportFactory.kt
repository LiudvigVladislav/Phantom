package phantom.core.transport

import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient

/**
 * Force-cancel every in-flight underlying engine call so a hung WebSocket
 * tears down immediately instead of waiting on the engine's graceful-close
 * timeout. KtorRelayTransport calls this when application-level pong times
 * out — Ktor's session.cancel() alone routes through the OkHttp outgoing
 * actor's finally block which calls websocket.close() (graceful) and blocks
 * for up to 60 seconds.
 *
 * Implementations should be best-effort and never throw. For engines without
 * a native equivalent, the implementation may be a no-op.
 */
expect fun forceCancelAllEngineCalls()
