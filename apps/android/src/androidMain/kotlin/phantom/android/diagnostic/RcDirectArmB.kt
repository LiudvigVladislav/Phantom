// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import phantom.android.BuildConfig
import phantom.core.identity.IdentityManager

/**
 * PR-RC-DIRECT-WS-DEATH1 Phase 1 Arm B — raw OkHttp `newWebSocket(...)`
 * sequential diagnostic arm.
 *
 * Design locked in [docs/tracks/rc-direct-ws-death1.md] § Commit 3.2b
 * (rev4, master `99cb1d6f`). This class implements §4 Arm B and §7
 * step 1.
 *
 * **Sequential, not parallel.** Per Inv-ParallelArmIsolation, this arm
 * runs only when [phantom.android.BuildConfig.DEBUG_RC_DIRECT_ARM] is
 * `"B"`. The wire-up site (`PhantomMessagingService.onStartCommand`)
 * short-circuits the production Hybrid Ktor `transport.connect(...)`
 * path in that case, so production and diagnostic WS never share
 * `state.clients[identity]` at the relay.
 *
 * **Own `OkHttpClient`.** Per rev2 P2, the arm does NOT extract a
 * client from [phantom.core.transport.createHttpClientFactory] (which
 * returns a Ktor `HttpClient` wrapper; the raw `OkHttpClient` is
 * lambda-closure-internal and intentionally not exposed). The arm
 * constructs its own client with identical builder parameters to
 * production: `pingInterval(15s)`, `readTimeout(60s)`,
 * `connectTimeout(5s)`, `callTimeout(10s)` (for `/auth/challenge`),
 * `protocols(HTTP_1_1)`. The only structural difference vs Arm A is
 * the absence of Ktor's `install(WebSockets) { pingIntervalMillis = 0L }`
 * wrapper around the same OkHttp engine.
 *
 * **Read-only.** Per Inv-RawArmReadOnly the arm never calls
 * `webSocket.send(...)`. It does not register for ack receipts. It
 * does not participate in REST fallback. It exists solely to log
 * what its own [WebSocketListener] sees with the `RC_DIRECT_ARM_B_*`
 * tag during its own session window.
 *
 * **Reconnect loop.** Phase 1 §6 gate #1 requires Arm A to reach at
 * least 5 WS death cycles within a 15-minute window and Arm B to
 * either reproduce the same rhythm or survive materially longer
 * (defined as ≥ 2 × Arm A median session lifetime). To gather that
 * data, the arm reconnects after each session ends — same identity,
 * same auth flow, same OkHttp client. A small backoff (1 s) between
 * sessions matches the production `RECONNECT_BASE_DELAY_MS`.
 *
 * **No production behaviour change.** Wire-up sites (AppContainer +
 * PhantomMessagingService) gate every reference to this class behind
 * `BuildConfig.DEBUG && BuildConfig.DEBUG_RC_DIRECT_ARM == "B"`.
 * Release builds (`!BuildConfig.DEBUG`) ignore the flag entirely.
 * The class is `internal` to the diagnostic package so production code
 * cannot accidentally reference it.
 */
internal class RcDirectArmB(
    private val identityManager: IdentityManager,
    private val relayUrl: String,
    private val scope: CoroutineScope,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15_000L, TimeUnit.MILLISECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    @Volatile private var runJob: Job? = null

    /**
     * Reference to the WebSocket of the current in-flight session. Set
     * inside [runOneSession] before `completion.await()` suspends and
     * cleared in the session's `finally` block. Read by [stop] so a
     * cancellation can `cancel(...)` the live OkHttp WebSocket — OkHttp
     * runs its WS reader on its own dispatcher pool thread, which a
     * coroutine cancellation alone does NOT interrupt. Without this
     * explicit `WebSocket.cancel()` the diagnostic socket would
     * continue holding the relay's `state.clients[identity]` mapping
     * past the service lifecycle, contaminating any subsequent Arm A
     * or Arm B run (Inv-ParallelArmIsolation).
     */
    @Volatile private var currentWebSocket: WebSocket? = null

    /**
     * Starts the diagnostic reconnect loop. Idempotent — a second call
     * while a previous run is active is a no-op (logged for visibility).
     * Safe to call from a non-coroutine context; the actual I/O runs on
     * the supplied [scope] under [Dispatchers.IO] for the `/auth/challenge`
     * GET and on OkHttp's own dispatcher pool for the WebSocket reader.
     */
    fun start(identityHex: String, signingPubKeyHex: String) {
        if (runJob?.isActive == true) {
            Log.w(TAG, "RC_DIRECT_ARM_B_start_ignored already_running")
            return
        }
        runJob = scope.launch {
            Log.i(
                TAG,
                "RC_DIRECT_ARM_B_armed " +
                    "identity_prefix=${identityHex.take(16)} " +
                    "signing_prefix=${signingPubKeyHex.take(16)} " +
                    "relay_url=$relayUrl " +
                    "ping_interval_ms=15000 " +
                    "read_timeout_ms=60000 " +
                    "call_timeout_ms=10000 " +
                    "protocols=HTTP_1_1",
            )
            var sessionEpoch = 0L
            while (isActive) {
                sessionEpoch++
                val outcome = runOneSession(identityHex, signingPubKeyHex, sessionEpoch)
                Log.i(TAG, "RC_DIRECT_ARM_B_session_finished s=$sessionEpoch outcome=$outcome")
                if (outcome == OUTCOME_AUTH_ABORTED) {
                    // signRelayChallenge returned null — typically because the
                    // signing key is not provisioned yet. Re-arm with a longer
                    // backoff so we don't busy-loop the identity store.
                    delay(5_000L)
                } else {
                    delay(1_000L)
                }
            }
        }
    }

    /**
     * Cancels the reconnect loop AND force-closes the live OkHttp
     * WebSocket if any. Idempotent. Provided so the wire-up site can
     * stop the arm without disposing the whole `appScope`.
     *
     * Why both `runJob?.cancel()` AND `currentWebSocket?.cancel()`:
     * OkHttp's WS reader runs on its own dispatcher pool thread, not on
     * the coroutine. A coroutine cancellation cancels the suspending
     * `completion.await()` but leaves the OkHttp socket alive (reader
     * thread continues, ping scheduler continues, `state.clients[identity]`
     * on the relay stays mapped to this socket). The explicit
     * `WebSocket.cancel()` tears down the OkHttp side immediately so the
     * next Arm A or Arm B run starts with a clean relay-side state.
     */
    fun stop() {
        val ws = currentWebSocket
        runJob?.cancel()
        runJob = null
        currentWebSocket = null
        if (ws != null) {
            runCatching { ws.cancel() }
                .onFailure { t ->
                    Log.w(
                        TAG,
                        "RC_DIRECT_ARM_B_ws_cancel_threw t=${t::class.simpleName} " +
                            "msg=${t.message?.take(160)}",
                    )
                }
        }
        Log.i(TAG, "RC_DIRECT_ARM_B_stopped ws_cancelled=${ws != null}")
    }

    private suspend fun runOneSession(
        identityHex: String,
        signingPubKeyHex: String,
        sessionEpoch: Long,
    ): String {
        val sessionStartMs = System.currentTimeMillis()
        Log.i(TAG, "RC_DIRECT_ARM_B_session_start s=$sessionEpoch wall_clock_ms=$sessionStartMs")
        // PR-RC-DIRECT-WS-DEATH1 Phase 2: wall-clock anchor for
        // Inv-WallClockAlignment (mini-lock §21). The line is emitted
        // before any session I/O so PCAPdroid pcap timestamps and
        // relay-side `ws_protocol_pong_sent` UTC stamps can be aligned
        // against this `utc=$sessionStartMs` for the same session epoch.
        // Inv-NoTrafficBeyondTelemetry (mini-lock §21): the marker is the
        // ONLY new emission for Phase 2 — no WS frame is ever sent for
        // capture-marking. Class itself is gated by
        // `BuildConfig.DEBUG && BuildConfig.DEBUG_RC_DIRECT_ARM == "B"`
        // at the AppContainer wire-up site (only that flag value
        // constructs RcDirectArmB), so reaching this line implies the
        // arm-level gate already held; defence-in-depth lives there.
        Log.i(
            TAG,
            "PHASE2_CAPTURE_MARKER mode=${BuildConfig.DEBUG_PHASE2_MODE} " +
                "utc=$sessionStartMs s=$sessionEpoch",
        )
        val authedUrl = buildAuthedWsUrl(identityHex, signingPubKeyHex, sessionEpoch)
            ?: return OUTCOME_AUTH_ABORTED
        val authDoneMs = System.currentTimeMillis()
        Log.i(
            TAG,
            "RC_DIRECT_ARM_B_auth_done s=$sessionEpoch auth_elapsed_ms=${authDoneMs - sessionStartMs}",
        )

        val request = Request.Builder().url(authedUrl).build()
        val completion = CompletableDeferred<String>()
        val listener = ArmBListener(sessionEpoch, authDoneMs, completion)
        val ws = client.newWebSocket(request, listener)
        // Read-only: do NOT call ws.send(...) under any condition.
        currentWebSocket = ws
        return try {
            completion.await()
        } catch (t: Throwable) {
            // Includes CancellationException when stop() is called or the
            // appScope is cancelled. The finally below force-closes the
            // OkHttp socket regardless of which path we take here.
            Log.w(
                TAG,
                "RC_DIRECT_ARM_B_completion_await_failed s=$sessionEpoch " +
                    "t=${t::class.simpleName} msg=${t.message?.take(200)}",
            )
            "completion_await_threw"
        } finally {
            // Always tear down the OkHttp socket on the way out — onClosed
            // / onFailure paths null `currentWebSocket` themselves only
            // after the relay has flushed the close handshake, and the
            // cancellation path goes straight through here without that
            // callback. Calling cancel() on an already-closed socket is
            // documented as a no-op (OkHttp WebSocket.cancel KDoc:
            // "Immediately and violently release resources held by this
            // web socket, discarding any enqueued messages.").
            if (currentWebSocket === ws) {
                currentWebSocket = null
            }
            runCatching { ws.cancel() }
                .onFailure { t ->
                    Log.w(
                        TAG,
                        "RC_DIRECT_ARM_B_ws_cancel_in_finally_threw s=$sessionEpoch " +
                            "t=${t::class.simpleName} msg=${t.message?.take(160)}",
                    )
                }
        }
    }

    private suspend fun buildAuthedWsUrl(
        identityHex: String,
        signingPubKeyHex: String,
        sessionEpoch: Long,
    ): String? {
        val httpScheme = when {
            relayUrl.startsWith("wss://") -> "https://"
            relayUrl.startsWith("ws://")  -> "http://"
            else                          -> "https://"
        }
        val hostAndPath = relayUrl.removePrefix("wss://").removePrefix("ws://")
        val hostOnly = hostAndPath.substringBefore("/")
        val challengeUrl = "$httpScheme$hostOnly/auth/challenge?identity=$identityHex"

        val challengeReq = Request.Builder().url(challengeUrl).build()
        val nonceHex = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(challengeReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(
                            TAG,
                            "RC_DIRECT_ARM_B_challenge_non_2xx s=$sessionEpoch " +
                                "status=${resp.code}",
                        )
                        return@use null
                    }
                    val body = resp.body?.string().orEmpty()
                    NONCE_REGEX.find(body)?.groupValues?.get(1)
                        ?: run {
                            Log.w(
                                TAG,
                                "RC_DIRECT_ARM_B_challenge_no_nonce_hex s=$sessionEpoch " +
                                    "body_head=${body.take(120).replace('\n', ' ')}",
                            )
                            null
                        }
                }
            }.onFailure { t ->
                Log.w(
                    TAG,
                    "RC_DIRECT_ARM_B_challenge_threw s=$sessionEpoch " +
                        "t=${t::class.simpleName} msg=${t.message?.take(200)}",
                )
            }.getOrNull()
        } ?: return null

        val nonceBytes = hexToBytes(nonceHex)
        val signature = identityManager.signRelayChallenge(nonceBytes) ?: run {
            Log.w(TAG, "RC_DIRECT_ARM_B_signer_returned_null s=$sessionEpoch")
            return null
        }
        if (signature.size != 64) {
            Log.w(
                TAG,
                "RC_DIRECT_ARM_B_signature_bad_size s=$sessionEpoch size=${signature.size}",
            )
            return null
        }
        return relayUrl +
            "?id=$identityHex" +
            "&signing_pubkey=$signingPubKeyHex" +
            "&challenge=$nonceHex" +
            "&signature=${bytesToHex(signature)}"
    }

    private inner class ArmBListener(
        private val sessionEpoch: Long,
        private val authDoneMs: Long,
        private val completion: CompletableDeferred<String>,
    ) : WebSocketListener() {
        @Volatile private var inboundTextFrames = 0L
        @Volatile private var inboundBinaryFrames = 0L
        @Volatile private var openAtMs: Long = 0L

        override fun onOpen(webSocket: WebSocket, response: Response) {
            openAtMs = System.currentTimeMillis()
            Log.i(
                TAG,
                "RC_DIRECT_ARM_B_ws_open s=$sessionEpoch " +
                    "auth_to_open_ms=${openAtMs - authDoneMs} " +
                    "status=${response.code}",
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            inboundTextFrames++
            val sinceOpenMs = if (openAtMs > 0L) System.currentTimeMillis() - openAtMs else -1L
            Log.i(
                TAG,
                "RC_DIRECT_ARM_B_ws_text s=$sessionEpoch " +
                    "seq=$inboundTextFrames " +
                    "since_open_ms=$sinceOpenMs " +
                    "len=${text.length}",
            )
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            inboundBinaryFrames++
            val sinceOpenMs = if (openAtMs > 0L) System.currentTimeMillis() - openAtMs else -1L
            Log.i(
                TAG,
                "RC_DIRECT_ARM_B_ws_bytes s=$sessionEpoch " +
                    "seq=$inboundBinaryFrames " +
                    "since_open_ms=$sinceOpenMs " +
                    "len=${bytes.size}",
            )
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            val sinceOpenMs = if (openAtMs > 0L) System.currentTimeMillis() - openAtMs else -1L
            Log.i(
                TAG,
                "RC_DIRECT_ARM_B_ws_closing s=$sessionEpoch " +
                    "code=$code " +
                    "reason=${reason.take(160)} " +
                    "since_open_ms=$sinceOpenMs",
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val nowMs = System.currentTimeMillis()
            val lifetimeMs = if (openAtMs > 0L) nowMs - openAtMs else -1L
            Log.i(
                TAG,
                "RC_DIRECT_ARM_B_ws_closed s=$sessionEpoch " +
                    "code=$code " +
                    "reason=${reason.take(160)} " +
                    "lifetime_ms=$lifetimeMs " +
                    "inbound_text_frames=$inboundTextFrames " +
                    "inbound_binary_frames=$inboundBinaryFrames",
            )
            completion.complete("closed_code_$code")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val nowMs = System.currentTimeMillis()
            val lifetimeMs = if (openAtMs > 0L) nowMs - openAtMs else -1L
            Log.w(
                TAG,
                "RC_DIRECT_ARM_B_ws_failure s=$sessionEpoch " +
                    "t=${t::class.simpleName} " +
                    "msg=${t.message?.take(200)} " +
                    "response_code=${response?.code} " +
                    "lifetime_ms=$lifetimeMs " +
                    "inbound_text_frames=$inboundTextFrames " +
                    "inbound_binary_frames=$inboundBinaryFrames",
            )
            completion.complete("failure_${t::class.simpleName}")
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { ((it.toInt() and 0xFF) or 0x100).toString(16).substring(1) }

    companion object {
        private const val TAG = "RC_DIRECT_ARM_B"
        private val NONCE_REGEX = Regex("\"nonce_hex\"\\s*:\\s*\"([a-fA-F0-9]+)\"")
        private const val OUTCOME_AUTH_ABORTED = "auth_aborted"
    }
}
