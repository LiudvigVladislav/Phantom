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
 * RC-DIRECT-STABILITY1 Arm D — data-frame heartbeat echo diagnostic arm.
 *
 * Design locked in [docs/tracks/rc-direct-stability1.md] §4 Arm D. This
 * class is a sibling of [RcDirectArmA], [RcDirectArmB], and [RcDirectArmC]
 * — same raw OkHttp pattern, same builder parameters (production
 * `pingInterval(15s)`), same `WebSocketListener` telemetry shape, same
 * reconnect loop. The structural difference: an additional outbound
 * heartbeat sender that emits one Text frame matching
 * `phantom:diagnostic:heartbeat-echo:v1:<seq>:<client_ms>` every 15 s
 * after WS open and counts inbound Text frames matching the same prefix
 * as echoes returned by the relay's PR #279 handler (gated by
 * `RELAY_ENABLE_HEARTBEAT_ECHO=1` on the VPS).
 *
 * **Discriminator goal (H-D).** Does the same path that kills WS
 * control-plane Ping/Pong on Tele2 LTE also kill application data frames?
 * If echo-frames return reliably while OkHttp Ping/Pong dies at the Mode 2
 * "first inbound after WS upgrade" anchor → the kill is WS-control-frame-
 * specific. If echo-frames also lose at the first-inbound anchor → the
 * kill is at packet layer, broader than control frames.
 *
 * **Read-only outbound carve-out from Inv-RawArmReadOnly.** This arm is
 * the single intentional exception to the "diagnostic arms never call
 * `webSocket.send(...)`" rule established by Phase 1 [RcDirectArmB]. The
 * carve-out is **narrow**: the only payload Arm D sends is the canonical
 * heartbeat prefix `phantom:diagnostic:heartbeat-echo:v1:<seq>:<client_ms>`
 * — never an app envelope, never a control frame, never JSON, never
 * arbitrary text. The single `webSocket.send(...)` call site is inside
 * the heartbeat sender and is reachable only from the heartbeat loop;
 * the payload is constructed via the [HEARTBEAT_ECHO_PREFIX] constant +
 * counter formatting (no caller-controlled input). PR-H1e regression
 * class (app-level WS Ping halved WS lifetime) is avoided because the
 * payload is WS Text (opcode 0x1), never WS Ping/Pong.
 *
 * **Sequential, not parallel.** Per Inv-ParallelArmIsolation, this arm
 * runs only when [BuildConfig.DEBUG_RC_DIRECT_HEARTBEAT_ECHO] is `"1"`
 * AND [BuildConfig.DEBUG] is true. The wire-up site
 * (`PhantomMessagingService.onStartCommand`) short-circuits the
 * production Hybrid Ktor `transport.connect(...)` path in that case,
 * so production and diagnostic WS never share `state.clients[identity]`
 * at the relay.
 *
 * **Own `OkHttpClient`.** Identical builder parameters to production +
 * other arms: `pingInterval(15s)`, `readTimeout(60s)`, `connectTimeout(5s)`,
 * `callTimeout(10s)` (for `/auth/challenge`), `protocols(HTTP_1_1)`.
 *
 * **Target network.** Connects against production [BuildConfig.RELAY_URL]
 * (`wss://relay.phntm.pro/ws`) — through Caddy, through Tele2 LTE radio,
 * through carrier middleboxes. No bypass, no USB tunnel. The diagnostic
 * is on the device side (sends a different payload), not the path side.
 *
 * **Heartbeat timing (Vladislav-locked 2026-06-04 PR-7 OQ-1):** first
 * heartbeat fires at `openAt + 15s`, not immediately at `openAt`. This
 * mirrors OkHttp's first Ping timing (after the first interval, not
 * immediately) so heartbeat does not race with WS upgrade and confuse
 * Mode 2 anchor analysis.
 *
 * **Send-time tracking.** Outbound seq counter increments per send.
 * Send-time map is bounded at [SEND_TIME_MAP_CAPACITY] entries; oldest
 * eviction is logged at debug level (`echo_send_time_evicted seq=N`) so
 * a post-mortem can explain why an echo arrived without an RTT (echo
 * came back after its send-time was evicted from the map).
 *
 * **Stop sending on close/failure (Vladislav-locked PR-7 hard-point):**
 * the heartbeat sender loop terminates when the listener signals close
 * or failure via [sessionAlive] flag flipping to `false`. The loop also
 * exits on coroutine cancellation. A stale [currentWebSocket] reference
 * is never used for sending — the send is gated by `sessionAlive` AND
 * `ws === currentWebSocket` identity check.
 *
 * **Echo parsing rigour (Vladislav-locked PR-7 hard-point):** inbound
 * Text frames are classified as echoes only when ALL of these hold:
 *   * raw length ≤ [HEARTBEAT_ECHO_MAX_LEN]
 *   * raw starts with the exact [HEARTBEAT_ECHO_PREFIX]
 *   * after the prefix, exactly two `:`-separated fields parse as `Long`
 *
 * Any other inbound Text is logged as `RC_DIRECT_ARM_D_ws_text` and NOT
 * counted as an echo. This prevents foreign text (envelope JSON,
 * `{"type":"pong"}`, etc.) from inflating the echo counters.
 *
 * **Lifecycle shields from PR #276 baked in:** `onClosed` and `onFailure`
 * null `currentWebSocket` before logging + completion.complete, so a
 * concurrent `stop()` call cannot read a stale reference. The
 * `runOneSession` finally block remains as shield #2.
 *
 * **No production behaviour change.** Wire-up sites (AppContainer +
 * PhantomMessagingService) gate every reference behind
 * `BuildConfig.DEBUG && BuildConfig.DEBUG_RC_DIRECT_HEARTBEAT_ECHO == "1"`.
 * Release builds (`!BuildConfig.DEBUG`) ignore the flag entirely; the
 * release BuildConfig block pins `DEBUG_RC_DIRECT_HEARTBEAT_ECHO` to
 * `"0"` as defence-in-depth. The class is `internal` to the diagnostic
 * package so production code cannot accidentally reference it.
 */
internal class RcDirectArmD(
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
    @Volatile private var currentWebSocket: WebSocket? = null

    fun start(identityHex: String, signingPubKeyHex: String) {
        if (runJob?.isActive == true) {
            Log.w(TAG, "RC_DIRECT_ARM_D_start_ignored already_running")
            return
        }
        runJob = scope.launch {
            Log.i(
                TAG,
                "RC_DIRECT_ARM_D_armed " +
                    "identity_prefix=${identityHex.take(16)} " +
                    "signing_prefix=${signingPubKeyHex.take(16)} " +
                    "relay_url=$relayUrl " +
                    "ping_interval_ms=15000 " +
                    "heartbeat_interval_ms=$HEARTBEAT_INTERVAL_MS " +
                    "heartbeat_first_at_ms=$HEARTBEAT_INTERVAL_MS " +
                    "send_time_map_capacity=$SEND_TIME_MAP_CAPACITY " +
                    "read_timeout_ms=60000 " +
                    "call_timeout_ms=10000 " +
                    "protocols=HTTP_1_1",
            )
            var sessionEpoch = 0L
            while (isActive) {
                sessionEpoch++
                val outcome = runOneSession(identityHex, signingPubKeyHex, sessionEpoch)
                Log.i(TAG, "RC_DIRECT_ARM_D_session_finished s=$sessionEpoch outcome=$outcome")
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
                        "RC_DIRECT_ARM_D_ws_cancel_threw t=${t::class.simpleName} " +
                            "msg=${t.message?.take(160)}",
                    )
                }
        }
        Log.i(TAG, "RC_DIRECT_ARM_D_stopped ws_cancelled=${ws != null}")
    }

    private suspend fun runOneSession(
        identityHex: String,
        signingPubKeyHex: String,
        sessionEpoch: Long,
    ): String {
        val sessionStartMs = System.currentTimeMillis()
        Log.i(TAG, "RC_DIRECT_ARM_D_session_start s=$sessionEpoch wall_clock_ms=$sessionStartMs")
        val authedUrl = buildAuthedWsUrl(identityHex, signingPubKeyHex, sessionEpoch)
            ?: return OUTCOME_AUTH_ABORTED
        val authDoneMs = System.currentTimeMillis()
        Log.i(
            TAG,
            "RC_DIRECT_ARM_D_auth_done s=$sessionEpoch auth_elapsed_ms=${authDoneMs - sessionStartMs}",
        )

        val request = Request.Builder().url(authedUrl).build()
        val completion = CompletableDeferred<String>()
        // Per-session send-time map for echo RTT computation. Bounded at
        // SEND_TIME_MAP_CAPACITY; oldest seq evicted on overflow (logged
        // at debug level so a post-mortem can explain why a late echo
        // arrived without an RTT value).
        val sendTimeMap = LinkedHashMap<Long, Long>(SEND_TIME_MAP_CAPACITY, 0.75f, true)
        val listener = ArmDListener(sessionEpoch, authDoneMs, completion, sendTimeMap)
        val ws = client.newWebSocket(request, listener)
        currentWebSocket = ws

        // Heartbeat sender — single launch tied to this session. Cancelled
        // automatically when `runOneSession` returns (scope is the
        // outer coroutine's context). The sender checks `listener.sessionAlive`
        // before each send + the ws-identity guard so it never writes to
        // a stale or closed socket (Vladislav PR-7 hard-point #1).
        val heartbeatJob = scope.launch {
            try {
                delay(HEARTBEAT_INTERVAL_MS)
                while (isActive && listener.sessionAlive) {
                    // Identity guard: if a concurrent stop() or new session
                    // replaced `currentWebSocket`, do not write to this stale
                    // ws. Mirrors the listener shield #1 pattern from PR #276.
                    val live = currentWebSocket
                    if (live !== ws) {
                        Log.d(
                            TAG,
                            "RC_DIRECT_ARM_D_heartbeat_send_skipped_stale_ws s=$sessionEpoch",
                        )
                        break
                    }
                    val seq = listener.nextSeq()
                    val clientMs = System.currentTimeMillis()
                    val payload = "$HEARTBEAT_ECHO_PREFIX$seq:$clientMs"
                    // Record send time BEFORE the actual send so a fast
                    // echo (sub-millisecond) cannot race the map insert.
                    synchronized(sendTimeMap) {
                        if (sendTimeMap.size >= SEND_TIME_MAP_CAPACITY) {
                            val oldest = sendTimeMap.entries.iterator().next()
                            Log.d(
                                TAG,
                                "RC_DIRECT_ARM_D_echo_send_time_evicted s=$sessionEpoch " +
                                    "evicted_seq=${oldest.key} evicted_age_ms=${clientMs - oldest.value}",
                            )
                            sendTimeMap.remove(oldest.key)
                        }
                        sendTimeMap[seq] = clientMs
                    }
                    // The single intentional send call site for Arm D.
                    // Inv-RawArmReadOnly carve-out: payload is the canonical
                    // heartbeat prefix + counter formatting ONLY — no
                    // caller-controlled input, no envelope, no JSON, no
                    // control frame. PR-H1e regression class avoided because
                    // the frame is WS Text (opcode 0x1), never WS Ping/Pong.
                    val sent = runCatching { ws.send(payload) }.getOrDefault(false)
                    if (!sent) {
                        Log.w(
                            TAG,
                            "RC_DIRECT_ARM_D_heartbeat_send_failed s=$sessionEpoch seq=$seq " +
                                "reason=enqueue_returned_false",
                        )
                        break
                    }
                    listener.echoSent.set(seq)
                    Log.i(
                        TAG,
                        "RC_DIRECT_ARM_D_echo_sent s=$sessionEpoch seq=$seq client_ms=$clientMs",
                    )
                    delay(HEARTBEAT_INTERVAL_MS)
                }
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "RC_DIRECT_ARM_D_heartbeat_sender_threw s=$sessionEpoch " +
                        "t=${t::class.simpleName} msg=${t.message?.take(160)}",
                )
            }
        }

        return try {
            completion.await()
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "RC_DIRECT_ARM_D_completion_await_failed s=$sessionEpoch " +
                    "t=${t::class.simpleName} msg=${t.message?.take(200)}",
            )
            "completion_await_threw"
        } finally {
            heartbeatJob.cancel()
            if (currentWebSocket === ws) {
                currentWebSocket = null
            }
            runCatching { ws.cancel() }
                .onFailure { t ->
                    Log.w(
                        TAG,
                        "RC_DIRECT_ARM_D_ws_cancel_in_finally_threw s=$sessionEpoch " +
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
                            "RC_DIRECT_ARM_D_challenge_non_2xx s=$sessionEpoch " +
                                "status=${resp.code}",
                        )
                        return@use null
                    }
                    val body = resp.body?.string().orEmpty()
                    NONCE_REGEX.find(body)?.groupValues?.get(1)
                        ?: run {
                            Log.w(
                                TAG,
                                "RC_DIRECT_ARM_D_challenge_no_nonce_hex s=$sessionEpoch " +
                                    "body_head=${body.take(120).replace('\n', ' ')}",
                            )
                            null
                        }
                }
            }.onFailure { t ->
                Log.w(
                    TAG,
                    "RC_DIRECT_ARM_D_challenge_threw s=$sessionEpoch " +
                        "t=${t::class.simpleName} msg=${t.message?.take(200)}",
                )
            }.getOrNull()
        } ?: return null

        val nonceBytes = hexToBytes(nonceHex)
        val signature = identityManager.signRelayChallenge(nonceBytes) ?: run {
            Log.w(TAG, "RC_DIRECT_ARM_D_signer_returned_null s=$sessionEpoch")
            return null
        }
        if (signature.size != 64) {
            Log.w(
                TAG,
                "RC_DIRECT_ARM_D_signature_bad_size s=$sessionEpoch size=${signature.size}",
            )
            return null
        }
        return relayUrl +
            "?id=$identityHex" +
            "&signing_pubkey=$signingPubKeyHex" +
            "&challenge=$nonceHex" +
            "&signature=${bytesToHex(signature)}"
    }

    private inner class ArmDListener(
        private val sessionEpoch: Long,
        private val authDoneMs: Long,
        private val completion: CompletableDeferred<String>,
        private val sendTimeMap: LinkedHashMap<Long, Long>,
    ) : WebSocketListener() {
        @Volatile var sessionAlive: Boolean = true
        @Volatile var openAtMs: Long = 0L

        private val seqCounter = java.util.concurrent.atomic.AtomicLong(0L)
        val echoSent = java.util.concurrent.atomic.AtomicLong(0L)
        private val echoReceived = java.util.concurrent.atomic.AtomicLong(0L)
        @Volatile private var lastEchoRttMs: Long = -1L

        @Volatile private var inboundTextFrames = 0L
        @Volatile private var inboundBinaryFrames = 0L

        fun nextSeq(): Long = seqCounter.incrementAndGet()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            openAtMs = System.currentTimeMillis()
            Log.i(
                TAG,
                "RC_DIRECT_ARM_D_ws_open s=$sessionEpoch " +
                    "auth_to_open_ms=${openAtMs - authDoneMs} " +
                    "status=${response.code}",
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            inboundTextFrames++
            val sinceOpenMs = if (openAtMs > 0L) System.currentTimeMillis() - openAtMs else -1L
            val parsed = parseHeartbeatEcho(text)
            if (parsed != null) {
                val (seq, _) = parsed
                val nowMs = System.currentTimeMillis()
                val sendTime = synchronized(sendTimeMap) { sendTimeMap.remove(seq) }
                val rttMs = if (sendTime != null) nowMs - sendTime else -1L
                echoReceived.incrementAndGet()
                if (rttMs >= 0L) {
                    lastEchoRttMs = rttMs
                }
                Log.i(
                    TAG,
                    "RC_DIRECT_ARM_D_echo_received s=$sessionEpoch " +
                        "seq=$seq " +
                        "rtt_ms=$rttMs " +
                        "since_open_ms=$sinceOpenMs " +
                        "echo_received_total=${echoReceived.get()}",
                )
            } else {
                // Foreign inbound Text — not an echo. Logged as inbound_text
                // (mirrors the existing diagnostic-arm tag shape) but NOT
                // counted as an echo, so echo statistics are not inflated
                // by `{"type":"pong"}` or envelope JSON or stray text.
                Log.i(
                    TAG,
                    "RC_DIRECT_ARM_D_ws_text s=$sessionEpoch " +
                        "seq=$inboundTextFrames " +
                        "since_open_ms=$sinceOpenMs " +
                        "len=${text.length}",
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            inboundBinaryFrames++
            val sinceOpenMs = if (openAtMs > 0L) System.currentTimeMillis() - openAtMs else -1L
            Log.i(
                TAG,
                "RC_DIRECT_ARM_D_ws_bytes s=$sessionEpoch " +
                    "seq=$inboundBinaryFrames " +
                    "since_open_ms=$sinceOpenMs " +
                    "len=${bytes.size}",
            )
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            sessionAlive = false
            val sinceOpenMs = if (openAtMs > 0L) System.currentTimeMillis() - openAtMs else -1L
            Log.i(
                TAG,
                "RC_DIRECT_ARM_D_ws_closing s=$sessionEpoch " +
                    "code=$code " +
                    "reason=${reason.take(160)} " +
                    "since_open_ms=$sinceOpenMs",
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // Lifecycle shield #1 from PR #276.
            if (currentWebSocket === webSocket) {
                currentWebSocket = null
            }
            sessionAlive = false
            val nowMs = System.currentTimeMillis()
            val lifetimeMs = if (openAtMs > 0L) nowMs - openAtMs else -1L
            val sentTotal = echoSent.get()
            val receivedTotal = echoReceived.get()
            val missing = (sentTotal - receivedTotal).coerceAtLeast(0L)
            Log.i(
                TAG,
                "RC_DIRECT_ARM_D_ws_closed s=$sessionEpoch " +
                    "code=$code " +
                    "reason=${reason.take(160)} " +
                    "lifetime_ms=$lifetimeMs " +
                    "echo_sent=$sentTotal " +
                    "echo_received=$receivedTotal " +
                    "echo_missing=$missing " +
                    "last_echo_rtt_ms=$lastEchoRttMs " +
                    "inbound_text_frames=$inboundTextFrames " +
                    "inbound_binary_frames=$inboundBinaryFrames",
            )
            completion.complete("closed_code_$code")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Lifecycle shield #1 from PR #276.
            if (currentWebSocket === webSocket) {
                currentWebSocket = null
            }
            sessionAlive = false
            val nowMs = System.currentTimeMillis()
            val lifetimeMs = if (openAtMs > 0L) nowMs - openAtMs else -1L
            val sentTotal = echoSent.get()
            val receivedTotal = echoReceived.get()
            val missing = (sentTotal - receivedTotal).coerceAtLeast(0L)
            Log.w(
                TAG,
                "RC_DIRECT_ARM_D_ws_failure s=$sessionEpoch " +
                    "t=${t::class.simpleName} " +
                    "msg=${t.message?.take(200)} " +
                    "response_code=${response?.code} " +
                    "lifetime_ms=$lifetimeMs " +
                    "echo_sent=$sentTotal " +
                    "echo_received=$receivedTotal " +
                    "echo_missing=$missing " +
                    "last_echo_rtt_ms=$lastEchoRttMs " +
                    "inbound_text_frames=$inboundTextFrames " +
                    "inbound_binary_frames=$inboundBinaryFrames",
            )
            completion.complete("failure_${t::class.simpleName}")
        }
    }

    /**
     * Parses an inbound Text frame as a heartbeat echo. Returns
     * `Pair(seq, clientMs)` only when ALL of the following hold:
     *   * length ≤ [HEARTBEAT_ECHO_MAX_LEN]
     *   * starts with [HEARTBEAT_ECHO_PREFIX] exactly (not a substring)
     *   * exactly two `:`-separated fields after the prefix
     *   * both fields parse as `Long`
     *
     * Returns `null` for any other shape — foreign Text inbound (envelope
     * JSON, `{"type":"pong"}`, arbitrary text) yields `null` and the
     * listener logs as `RC_DIRECT_ARM_D_ws_text` instead of as an echo.
     *
     * Mirrors the relay-side `parse_heartbeat_echo_payload` validation
     * locked in PR #279.
     */
    private fun parseHeartbeatEcho(raw: String): Pair<Long, Long>? {
        if (raw.length > HEARTBEAT_ECHO_MAX_LEN) return null
        if (!raw.startsWith(HEARTBEAT_ECHO_PREFIX)) return null
        val rest = raw.substring(HEARTBEAT_ECHO_PREFIX.length)
        val parts = rest.split(':')
        if (parts.size != 2) return null
        val seq = parts[0].toLongOrNull() ?: return null
        val clientMs = parts[1].toLongOrNull() ?: return null
        return seq to clientMs
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { ((it.toInt() and 0xFF) or 0x100).toString(16).substring(1) }

    companion object {
        private const val TAG = "RC_DIRECT_ARM_D"
        private val NONCE_REGEX = Regex("\"nonce_hex\"\\s*:\\s*\"([a-fA-F0-9]+)\"")
        private const val OUTCOME_AUTH_ABORTED = "auth_aborted"

        // Canonical heartbeat payload prefix locked in mini-lock §4 Arm D.
        // Must match the relay-side HEARTBEAT_ECHO_PREFIX constant
        // (services/relay/src/routes.rs PR #279) byte-for-byte.
        const val HEARTBEAT_ECHO_PREFIX: String = "phantom:diagnostic:heartbeat-echo:v1:"

        // Mirrors the relay-side HEARTBEAT_ECHO_MAX_LEN cap.
        const val HEARTBEAT_ECHO_MAX_LEN: Int = 256

        // Heartbeat send interval. First heartbeat at openAt + this delay
        // (mirrors OkHttp's first Ping timing); subsequent heartbeats at
        // this cadence. Same value as production pingInterval(15s) so the
        // heartbeat anchor lines up with the WS Ping/Pong anchor on the
        // same session — if echo arrives at the Mode 2 anchor where
        // Pong dies, kill is WS-control-specific.
        const val HEARTBEAT_INTERVAL_MS: Long = 15_000L

        // Bounded send-time map for echo RTT computation. Heartbeat at
        // 15 s cadence over a 15 min capture yields ~60 sends total;
        // 32-entry cap covers ~8 min of in-flight outstanding echoes.
        // Echo not back by eviction → RTT unavailable, logged as -1L
        // at receive site (post-mortem can correlate with the eviction
        // debug log line `echo_send_time_evicted`).
        const val SEND_TIME_MAP_CAPACITY: Int = 32
    }
}
