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
 * RC-DIRECT-STABILITY1 Arm C — OkHttp ping interval matrix diagnostic arm.
 *
 * Design locked in [docs/tracks/rc-direct-stability1.md] §4 Arm C (refined
 * scope after PR-4 review). This class is a sibling of [RcDirectArmA]
 * (Caddy bypass) and [RcDirectArmB] (raw OkHttp at production cadence) —
 * same raw OkHttp pattern, same builder parameters except the single
 * `pingInterval(...)` value, same WebSocketListener telemetry shape,
 * same reconnect loop. The only diagnostic variable is the ping
 * interval, sourced from [BuildConfig.DEBUG_RC_DIRECT_PING_INTERVAL_MS].
 *
 * **Strictly diagnostic.** This class is a **diagnostic cadence arm**,
 * not a production-fix candidate. Its purpose is to measure cadence
 * sensitivity of the OkHttp ping path on the production carrier route
 * (Tecno → Tele2 → Caddy → relay) — answer the question "does varying
 * pingInterval change WS lifetime?" with empirical numbers. Any value
 * Arm C finds materially improves WS lifetime ships only via a separate
 * named PR with its own mini-lock per Inv-OnlyDiagnosticCadenceChange
 * — never via this track. The production
 * `RelayTransportFactory.kt:71 pingInterval(15_000L, MILLISECONDS)` line
 * is read-only for the entire RC-DIRECT-STABILITY1 track.
 *
 * **Target network.** Unlike Arm A which bypasses Caddy via loopback,
 * Arm C connects against the production `BuildConfig.RELAY_URL`
 * (`wss://relay.phntm.pro/ws`) — through Caddy, through Tele2 LTE radio,
 * through carrier middleboxes. The diagnostic is on the device side
 * (which OkHttp Builder parameter), not the path side. This avoids the
 * architectural limitation that Arm A's adb-reverse tunnel hit (see
 * Arm A Outcome in the mini-lock).
 *
 * **Sequential, not parallel.** Per Inv-ParallelArmIsolation, this arm
 * runs only when [phantom.android.BuildConfig.DEBUG_RC_DIRECT_PING_INTERVAL_MS]
 * is non-"0" AND [phantom.android.BuildConfig.DEBUG] is true. The
 * wire-up site (`PhantomMessagingService.onStartCommand`) short-circuits
 * the production Hybrid Ktor `transport.connect(...)` path in that case,
 * so production and diagnostic WS never share `state.clients[identity]`
 * at the relay.
 *
 * **Own `OkHttpClient`.** Identical builder parameters to production and
 * to RcDirectArmB EXCEPT the `pingInterval(...)` value, which is read
 * from the BuildConfig field at construction time and parsed to Long
 * milliseconds: `readTimeout(60s)`, `connectTimeout(5s)`, `callTimeout(10s)`
 * (for `/auth/challenge`), `protocols(HTTP_1_1)`. Field values are
 * "10000" / "20000" / "30000" per mini-lock §4 Arm C. Value "0" is
 * the disabled / baseline state and the AppContainer wire-up never
 * constructs this class for value "0" (run RcDirectArmB or production
 * for the 15 s baseline).
 *
 * **Read-only.** Per Inv-RawArmReadOnly (inherited from Phase 1) the
 * arm never calls `webSocket.send(...)`. It does not register for ack
 * receipts. It does not participate in REST fallback. It exists solely
 * to log what its own [WebSocketListener] sees with the
 * `RC_DIRECT_ARM_C_*` tag during its own session window.
 *
 * **Reconnect loop.** The §6 ship criterion is "zero
 * `SocketTimeoutException: sent ping but didn't receive pong` events
 * in a 15-minute capture on the target problematic network." To reach
 * that, the arm must sustain multiple sessions over the capture window.
 * Same reconnect cadence as RcDirectArmA / RcDirectArmB: 1 s backoff
 * between sessions (5 s after `OUTCOME_AUTH_ABORTED` so the identity
 * store is not busy-looped).
 *
 * **Lifecycle shields from PR #276 baked in.** `onClosed` and
 * `onFailure` listener overrides null `currentWebSocket` before logging
 * + completion.complete, so a concurrent `stop()` call from `onDestroy`
 * cannot read a stale reference and log a spurious `ws_cancelled=true`.
 * The `runOneSession` finally block remains as shield #2.
 *
 * **No production behaviour change.** Wire-up sites (AppContainer +
 * PhantomMessagingService) gate every reference to this class behind
 * `BuildConfig.DEBUG && BuildConfig.DEBUG_RC_DIRECT_PING_INTERVAL_MS != "0"`.
 * Release builds (`!BuildConfig.DEBUG`) ignore the flag entirely; the
 * release BuildConfig block pins `DEBUG_RC_DIRECT_PING_INTERVAL_MS` to
 * `"0"` as defence-in-depth. The class is `internal` to the diagnostic
 * package so production code cannot accidentally reference it.
 */
internal class RcDirectArmC(
    private val identityManager: IdentityManager,
    private val relayUrl: String,
    private val pingIntervalMs: Long,
    private val scope: CoroutineScope,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(pingIntervalMs, TimeUnit.MILLISECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    @Volatile private var runJob: Job? = null

    /**
     * Reference to the WebSocket of the current in-flight session. Set
     * inside [runOneSession] before `completion.await()` suspends and
     * cleared in the session's `finally` block AND in the listener's
     * `onClosed` / `onFailure` overrides (lifecycle shield #1 from PR #276).
     * Read by [stop] so a cancellation can `cancel(...)` the live OkHttp
     * WebSocket — OkHttp runs its WS reader on its own dispatcher pool
     * thread, which a coroutine cancellation alone does NOT interrupt.
     */
    @Volatile private var currentWebSocket: WebSocket? = null

    fun start(identityHex: String, signingPubKeyHex: String) {
        if (runJob?.isActive == true) {
            Log.w(TAG, "RC_DIRECT_ARM_C_start_ignored already_running")
            return
        }
        runJob = scope.launch {
            Log.i(
                TAG,
                "RC_DIRECT_ARM_C_armed " +
                    "identity_prefix=${identityHex.take(16)} " +
                    "signing_prefix=${signingPubKeyHex.take(16)} " +
                    "relay_url=$relayUrl " +
                    "ping_interval_ms=$pingIntervalMs " +
                    "read_timeout_ms=60000 " +
                    "call_timeout_ms=10000 " +
                    "protocols=HTTP_1_1",
            )
            var sessionEpoch = 0L
            while (isActive) {
                sessionEpoch++
                val outcome = runOneSession(identityHex, signingPubKeyHex, sessionEpoch)
                Log.i(TAG, "RC_DIRECT_ARM_C_session_finished s=$sessionEpoch outcome=$outcome")
                if (outcome == OUTCOME_AUTH_ABORTED) {
                    delay(5_000L)
                } else {
                    delay(1_000L)
                }
            }
        }
    }

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
                        "RC_DIRECT_ARM_C_ws_cancel_threw t=${t::class.simpleName} " +
                            "msg=${t.message?.take(160)}",
                    )
                }
        }
        Log.i(TAG, "RC_DIRECT_ARM_C_stopped ws_cancelled=${ws != null}")
    }

    private suspend fun runOneSession(
        identityHex: String,
        signingPubKeyHex: String,
        sessionEpoch: Long,
    ): String {
        val sessionStartMs = System.currentTimeMillis()
        Log.i(TAG, "RC_DIRECT_ARM_C_session_start s=$sessionEpoch wall_clock_ms=$sessionStartMs")
        val authedUrl = buildAuthedWsUrl(identityHex, signingPubKeyHex, sessionEpoch)
            ?: return OUTCOME_AUTH_ABORTED
        val authDoneMs = System.currentTimeMillis()
        Log.i(
            TAG,
            "RC_DIRECT_ARM_C_auth_done s=$sessionEpoch auth_elapsed_ms=${authDoneMs - sessionStartMs}",
        )

        val request = Request.Builder().url(authedUrl).build()
        val completion = CompletableDeferred<String>()
        val listener = ArmCListener(sessionEpoch, authDoneMs, completion)
        val ws = client.newWebSocket(request, listener)
        // Read-only: do NOT call ws.send(...) under any condition.
        currentWebSocket = ws
        return try {
            completion.await()
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "RC_DIRECT_ARM_C_completion_await_failed s=$sessionEpoch " +
                    "t=${t::class.simpleName} msg=${t.message?.take(200)}",
            )
            "completion_await_threw"
        } finally {
            if (currentWebSocket === ws) {
                currentWebSocket = null
            }
            runCatching { ws.cancel() }
                .onFailure { t ->
                    Log.w(
                        TAG,
                        "RC_DIRECT_ARM_C_ws_cancel_in_finally_threw s=$sessionEpoch " +
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
                            "RC_DIRECT_ARM_C_challenge_non_2xx s=$sessionEpoch " +
                                "status=${resp.code}",
                        )
                        return@use null
                    }
                    val body = resp.body?.string().orEmpty()
                    NONCE_REGEX.find(body)?.groupValues?.get(1)
                        ?: run {
                            Log.w(
                                TAG,
                                "RC_DIRECT_ARM_C_challenge_no_nonce_hex s=$sessionEpoch " +
                                    "body_head=${body.take(120).replace('\n', ' ')}",
                            )
                            null
                        }
                }
            }.onFailure { t ->
                Log.w(
                    TAG,
                    "RC_DIRECT_ARM_C_challenge_threw s=$sessionEpoch " +
                        "t=${t::class.simpleName} msg=${t.message?.take(200)}",
                )
            }.getOrNull()
        } ?: return null

        val nonceBytes = hexToBytes(nonceHex)
        val signature = identityManager.signRelayChallenge(nonceBytes) ?: run {
            Log.w(TAG, "RC_DIRECT_ARM_C_signer_returned_null s=$sessionEpoch")
            return null
        }
        if (signature.size != 64) {
            Log.w(
                TAG,
                "RC_DIRECT_ARM_C_signature_bad_size s=$sessionEpoch size=${signature.size}",
            )
            return null
        }
        return relayUrl +
            "?id=$identityHex" +
            "&signing_pubkey=$signingPubKeyHex" +
            "&challenge=$nonceHex" +
            "&signature=${bytesToHex(signature)}"
    }

    private inner class ArmCListener(
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
                "RC_DIRECT_ARM_C_ws_open s=$sessionEpoch " +
                    "auth_to_open_ms=${openAtMs - authDoneMs} " +
                    "status=${response.code}",
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            inboundTextFrames++
            val sinceOpenMs = if (openAtMs > 0L) System.currentTimeMillis() - openAtMs else -1L
            Log.i(
                TAG,
                "RC_DIRECT_ARM_C_ws_text s=$sessionEpoch " +
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
                "RC_DIRECT_ARM_C_ws_bytes s=$sessionEpoch " +
                    "seq=$inboundBinaryFrames " +
                    "since_open_ms=$sinceOpenMs " +
                    "len=${bytes.size}",
            )
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            val sinceOpenMs = if (openAtMs > 0L) System.currentTimeMillis() - openAtMs else -1L
            Log.i(
                TAG,
                "RC_DIRECT_ARM_C_ws_closing s=$sessionEpoch " +
                    "code=$code " +
                    "reason=${reason.take(160)} " +
                    "since_open_ms=$sinceOpenMs",
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // Lifecycle shield #1 from PR #276: null currentWebSocket
            // here, before logging or completion.complete, so a concurrent
            // stop() call from onDestroy cannot read a stale reference to
            // an already-dead socket and log a spurious `ws_cancelled=true`.
            // The finally block in runOneSession remains as shield #2.
            if (currentWebSocket === webSocket) {
                currentWebSocket = null
            }
            val nowMs = System.currentTimeMillis()
            val lifetimeMs = if (openAtMs > 0L) nowMs - openAtMs else -1L
            Log.i(
                TAG,
                "RC_DIRECT_ARM_C_ws_closed s=$sessionEpoch " +
                    "code=$code " +
                    "reason=${reason.take(160)} " +
                    "lifetime_ms=$lifetimeMs " +
                    "inbound_text_frames=$inboundTextFrames " +
                    "inbound_binary_frames=$inboundBinaryFrames",
            )
            completion.complete("closed_code_$code")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Lifecycle shield #1 from PR #276 (same rationale as onClosed).
            if (currentWebSocket === webSocket) {
                currentWebSocket = null
            }
            val nowMs = System.currentTimeMillis()
            val lifetimeMs = if (openAtMs > 0L) nowMs - openAtMs else -1L
            Log.w(
                TAG,
                "RC_DIRECT_ARM_C_ws_failure s=$sessionEpoch " +
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
        private const val TAG = "RC_DIRECT_ARM_C"
        private val NONCE_REGEX = Regex("\"nonce_hex\"\\s*:\\s*\"([a-fA-F0-9]+)\"")
        private const val OUTCOME_AUTH_ABORTED = "auth_aborted"
    }
}
