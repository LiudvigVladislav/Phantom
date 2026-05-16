// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
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
 *
 * Structured observability: every probe emits `PROBE_TRACE` log lines at
 * each phase so logcat captures exactly where a probe failed on a real
 * device without requiring SSH + Caddy log cross-referencing (Test #42
 * Tele2 LTE diagnosis 2026-05-14). The OkHttp [ProbeEventListener] hooks
 * fire for DNS, TCP connect, TLS handshake, HTTP headers, and call end —
 * enough to distinguish "TCP refused" from "TLS reset" from "HTTP timeout".
 */
class KtorTransportProbe(
    private val healthUrl: String,
) : TransportProbe {

    override suspend fun reachable(kind: TransportKind, socksPort: Int?): Boolean {
        // PR-R0.3: Direct clearnet probe uses native OkHttp to avoid
        // Ktor engine false-negatives on LTE cold-radio (Test #47).
        if (kind == TransportKind.Direct && socksPort == null) {
            return AndroidNativeOkHttpDirectProbe(healthUrl).run()
        }

        val callTimeoutMs = callTimeoutFor(kind)
        val startMs = System.currentTimeMillis()
        Log.i(
            TAG,
            "PROBE_TRACE probe_start kind=$kind socks=${socksPort ?: "direct"} " +
                "healthUrl=$healthUrl callTimeoutMs=$callTimeoutMs",
        )
        val client = buildClient(kind, socksPort, callTimeoutMs)
        return try {
            Log.i(TAG, "PROBE_TRACE probe_tcp_open kind=$kind")
            val response: HttpResponse = client.get(healthUrl)
            val elapsedMs = System.currentTimeMillis() - startMs
            val ok = response.status.value == 200
            Log.i(
                TAG,
                "PROBE_TRACE probe_http_done kind=$kind status=${response.status.value} elapsedMs=$elapsedMs",
            )
            Log.i(
                TAG,
                "PROBE_TRACE probe_result kind=$kind ok=$ok totalMs=$elapsedMs",
            )
            ok
        } catch (t: Throwable) {
            val elapsedMs = System.currentTimeMillis() - startMs
            Log.w(
                TAG,
                "PROBE_TRACE probe_fail kind=$kind exception=${t::class.simpleName} " +
                    "message=${t.message} elapsedMs=$elapsedMs",
            )
            Log.w(
                TAG,
                "PROBE_TRACE probe_result kind=$kind ok=false totalMs=$elapsedMs",
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

    private fun buildClient(kind: TransportKind, socksPort: Int?, callTimeoutMs: Long): HttpClient =
        HttpClient(OkHttp) {
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
                    // Attach the per-call event listener so every probe emits
                    // fine-grained PROBE_TRACE lines for DNS, TCP, TLS, and HTTP
                    // header phases. Zero runtime cost when logcat is filtered out;
                    // invaluable when diagnosing which phase stalls on a given network.
                    eventListener(ProbeEventListener(kind))
                }
            }
        }

    private companion object {
        private const val TAG = "TransportProbe"
    }
}

/**
 * OkHttp [EventListener] that emits one `PROBE_TRACE probe_event` log line per
 * connection-lifecycle event. Attached once per probe client in
 * [KtorTransportProbe.buildClient] and discarded when the client is closed.
 *
 * Only the events relevant to diagnosing transport failures are wired:
 * DNS resolution, TCP connect, TLS handshake, HTTP header exchange, call
 * end, and the three failure callbacks (DNS / TCP / call). Request/response
 * body streaming events are deliberately omitted — the /health response body
 * is empty and those events add noise without diagnostic value.
 *
 * Elapsed-ms values are measured from [callStart] to keep them comparable
 * across events in the same probe. The [ProbeEventListener] is not thread-safe
 * but OkHttp calls all events on the same OkHttp dispatcher thread for a
 * given [Call], so no synchronisation is needed.
 */
internal class ProbeEventListener(private val kind: TransportKind) : EventListener() {

    private var callStartMs: Long = 0L
    private var dnsStartMs: Long = 0L
    private var connectStartMs: Long = 0L
    private var secureConnectStartMs: Long = 0L
    private var responseHeadersStartMs: Long = 0L

    private fun elapsed(): Long = System.currentTimeMillis() - callStartMs

    private fun log(msg: String) = Log.i(TAG, "PROBE_TRACE probe_event kind=$kind $msg")
    private fun logW(msg: String) = Log.w(TAG, "PROBE_TRACE probe_event kind=$kind $msg")

    override fun callStart(call: Call) {
        callStartMs = System.currentTimeMillis()
        log("event=callStart")
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsStartMs = System.currentTimeMillis()
        log("event=dnsStart domain=$domainName")
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        val elapsedMs = System.currentTimeMillis() - dnsStartMs
        val addresses = inetAddressList.map { it.hostAddress }.joinToString(",", "[", "]")
        log("event=dnsEnd domain=$domainName addresses=$addresses elapsedMs=$elapsedMs")
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStartMs = System.currentTimeMillis()
        log("event=connectStart host=${inetSocketAddress.address?.hostAddress ?: inetSocketAddress.hostName} port=${inetSocketAddress.port}")
    }

    override fun secureConnectStart(call: Call) {
        secureConnectStartMs = System.currentTimeMillis()
        log("event=secureConnectStart")
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        val elapsedMs = System.currentTimeMillis() - secureConnectStartMs
        val tlsVersion = handshake?.tlsVersion?.javaName ?: "unknown"
        val cipher = handshake?.cipherSuite?.javaName ?: "unknown"
        log("event=secureConnectEnd handshake=$tlsVersion cipher=$cipher elapsedMs=$elapsedMs")
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        val elapsedMs = System.currentTimeMillis() - connectStartMs
        log("event=connectEnd elapsedMs=$elapsedMs")
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        val elapsedMs = System.currentTimeMillis() - connectStartMs
        logW(
            "event=connectFailed exception=${ioe::class.simpleName} " +
                "message=${ioe.message} elapsedMs=$elapsedMs",
        )
    }

    override fun requestHeadersStart(call: Call) {
        log("event=requestHeadersStart")
    }

    override fun responseHeadersStart(call: Call) {
        responseHeadersStartMs = System.currentTimeMillis()
        val elapsedMs = elapsed()
        log("event=responseHeadersStart elapsedMs=$elapsedMs")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        val elapsedMs = System.currentTimeMillis() - responseHeadersStartMs
        log("event=responseHeadersEnd status=${response.code} elapsedMs=$elapsedMs")
    }

    override fun callEnd(call: Call) {
        val totalMs = elapsed()
        log("event=callEnd totalMs=$totalMs")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        val totalMs = elapsed()
        logW("event=callFailed exception=${ioe::class.simpleName} totalMs=$totalMs")
    }

    private companion object {
        private const val TAG = "TransportProbe"
    }
}
