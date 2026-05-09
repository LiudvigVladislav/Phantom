// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * End-to-end reachability probe for [TransportManager]. Issues `GET <healthUrl>`
 * through the supplied SOCKS port (or directly when null). 200 → reachable.
 *
 * Uses a fresh single-request OkHttp engine per probe so the probe does not
 * leak connections or share state with the real WebSocket client. Each probe
 * builds its own `Proxy` if a SOCKS port is supplied — Ktor / OkHttp do not
 * expose per-call proxy switching otherwise.
 *
 * `healthUrl` is derived from the build's relay endpoint (`RELAY_URL` in
 * `BuildConfig`) by stripping `/ws` and rewriting the scheme to `https://`.
 * The relay's `/health` endpoint always returns 200 with no auth required,
 * so the probe is identity-agnostic and replay-safe.
 */
class KtorTransportProbe(
    private val healthUrl: String,
    /** Per-probe HTTP-call timeout; gated above by [TransportManager.PER_ATTEMPT_TIMEOUT_MS]. */
    private val perCallTimeoutMs: Long = 4_000L,
) : TransportProbe {

    override suspend fun reachable(kind: TransportKind, socksPort: Int?): Boolean {
        val client = buildClient(socksPort)
        return try {
            val response: HttpResponse = client.get(healthUrl)
            response.status == HttpStatusCode.OK
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { client.close() }
        }
    }

    private fun buildClient(socksPort: Int?): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                callTimeout(perCallTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (socksPort != null) {
                    proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
                }
            }
        }
    }
}
