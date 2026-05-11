// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
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
 *
 * Per-kind call timeout: Direct + Reality finish in <1 s on a healthy
 * link, so 4 s catches real outages quickly. Tor needs the first onion
 * roundtrip after Ready (HS-descriptor fetch + rendezvous + relay-side
 * /health roundtrip) which routinely eats 30+ s on cold circuits — the
 * earlier flat 4 s callTimeout was clamping the probe well below the
 * outer 90 s budget, surfacing as `Tor probe returned false` on every
 * cold attempt (cross-device test 2026-05-10). Now the per-kind call
 * timeout matches what the network actually does.
 */
class KtorTransportProbe(
    private val healthUrl: String,
) : TransportProbe {

    override suspend fun reachable(kind: TransportKind, socksPort: Int?): Boolean {
        val callTimeoutMs = callTimeoutFor(kind)
        Log.i(
            TAG,
            "probe start: kind=$kind socks=${socksPort ?: "direct"} url=$healthUrl callTimeoutMs=$callTimeoutMs",
        )
        val client = buildClient(socksPort, callTimeoutMs)
        return try {
            val response: HttpResponse = client.get(healthUrl)
            val ok = response.status.value == 200
            Log.i(
                TAG,
                "probe done: kind=$kind status=${response.status.value} ok=$ok",
            )
            ok
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "probe failed: kind=$kind exception=${t::class.simpleName} message=${t.message}",
            )
            false
        } finally {
            runCatching { client.close() }
        }
    }

    private fun callTimeoutFor(kind: TransportKind): Long = when (kind) {
        TransportKind.Direct  -> 4_000L
        // 20 s lets us distinguish "slow Reality handshake" from "path
        // is hard-blocked by an upstream filter" (e.g. VPN exit IP
        // refused by the Hetzner REALITY endpoint, or REALITY
        // fingerprint mismatched at the CDN-mock). Architect review
        // 2026-05-10 asked for 20-30 s for that exact distinction;
        // 20 s is the lower bound that still keeps Tor fallback fast
        // (so a hard-blocked Reality only adds 20 s, not 30, to the
        // user-perceived connect time).
        TransportKind.Reality -> 20_000L
        TransportKind.Tor     -> 60_000L
    }

    private fun buildClient(socksPort: Int?, callTimeoutMs: Long): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                // callTimeout is the outer ceiling for the entire HTTP exchange.
                // OkHttp also has separate connect/read/write timeouts that
                // default to 10 s each; if any of those fires before callTimeout,
                // the request aborts with a SocketTimeoutException at the lower
                // bound rather than at our intended ceiling.
                //
                // 2026-05-11 Test #2 surfaced exactly that: under VPN the Reality
                // probe reported `callTimeoutMs=20000` but failed at 10.07 s with
                // `SocketTimeoutException [socket_timeout=unknown]` — the inner
                // 10 s read-timeout on the slow VPN-routed REALITY handshake
                // tripped first. Synchronising connect/read/write to match
                // callTimeout means the inner timers no longer override the
                // outer budget, so we get a true measurement of whether the
                // probe ever finishes within `callTimeoutMs`.
                callTimeout(callTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                connectTimeout(callTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                readTimeout(callTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                writeTimeout(callTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (socksPort != null) {
                    proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
                }
            }
        }
    }

    private companion object {
        private const val TAG = "TransportProbe"
    }
}
