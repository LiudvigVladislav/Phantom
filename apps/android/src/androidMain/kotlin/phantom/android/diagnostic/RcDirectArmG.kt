// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.util.Log
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import phantom.android.BuildConfig
import phantom.core.identity.IdentityManager
import phantom.core.xray.XrayService
import phantom.core.xray.XrayState

/**
 * RC-DIRECT-STABILITY1 §14 Arm G — Reality-tunneled WS heartbeat diagnostic.
 *
 * Design locked in [docs/tracks/rc-direct-stability1.md] §14 Arm G mini-lock
 * (PR #294 squash `f0b436a5` master 2026-06-05). This class is a near-clone
 * of [RcDirectArmA2] / [RcDirectArmD] — same heartbeat payload format
 * (`phantom:diagnostic:heartbeat-echo:v1:<seq>:<client_ms>`), same
 * [WebSocketListener] telemetry shape, same reconnect loop, same lifecycle
 * fixes carried in from PR #276 (shield #1 + shield #2) and PR #280
 * (`openedAt.completeExceptionally` on pre-`onOpen` failure + heartbeat
 * coroutine `CancellationException` rethrow). The **single structural
 * variable** that changes vs Arm D baseline is the outer transport: where
 * Arm D's OkHttp client connects directly to production `relay.phntm.pro:443`
 * through Caddy on bare TLS, Arm G's OkHttp client connects through a
 * SOCKS5 proxy at `127.0.0.1:<Ready.socksPort>` provided by the embedded
 * libXray daemon, which wraps the outbound stream in VLESS+REALITY to the
 * Stage 5E production endpoint (`OperatorXrayConfig.SERVER_HOST:8443`)
 * and forwards the decrypted inner stream from the server side to
 * production `relay.phntm.pro:443` (i.e. still through Caddy on the inner
 * side, but originating from the operator VPS IP rather than the mobile
 * Tele2 LTE radio).
 *
 * **Discriminator goal (PASS / PARTIAL / FAIL per §14 Discriminator).**
 *
 *   * **PASS — WS lifetime ≥ 10 minutes (single session or across reconnects
 *     without dropping below threshold at any point) AND echo round-trips
 *     succeeding (relay logs `event=heartbeat_echo_received` per outbound
 *     Text frame, client logs `RC_DIRECT_ARM_G_echo_received`) AND no Mode 2
 *     signature.** Architectural verdict: Reality-tunneled realtime is
 *     viable for RU mobile. Architecture pivot: Reality becomes the primary
 *     outer transport for realtime in private mode; 3.2b.1 becomes the
 *     safety-net trigger that promotes a stuck Direct WS session to Reality
 *     without user intervention. Cost ~3-4 weeks.
 *
 *   * **PARTIAL — WS lifetime ≥ 10 minutes BUT echo round-trips fail.**
 *     Reality keeps TCP alive end-to-end but inner WS+Text payload hits
 *     the same control/application asymmetry Arm A.2 / Arm D documented.
 *     Reality is necessary-but-not-sufficient. REST + Matrix-style long-
 *     poll primary; Reality retained as REST-fallback safety net.
 *     Cost ~6-8 weeks.
 *
 *   * **FAIL — WS dies in same Mode 2 signature OR same byte-budget class
 *     as T2.** Kill is below all transport layers PHANTOM can stack short
 *     of UDP+QUIC (blocked on RU per Independent Audit). Pure REST +
 *     Matrix-style 25-second long-poll (Option A); abandon Direct WS
 *     concept; Reality retained ONLY as outer transport for HTTP fallback.
 *     Cost ~6-8 weeks.
 *
 * **Read-only outbound carve-out from Inv-RawArmReadOnly.** Same narrow
 * carve-out as Arm A.2 / Arm D: the only payload sent is the canonical
 * heartbeat prefix [HEARTBEAT_ECHO_PREFIX] + counter formatting. Single
 * `webSocket.send(...)` call site inside the heartbeat sender, reachable
 * only from the heartbeat loop. WS Text (opcode 0x1), never WS Ping/Pong
 * — `Inv-DataFrameNotControlFrame` preserved.
 *
 * **Transport isolation, NOT structural bootstrap isolation (per §14
 * Hard Gate 6 + fixup commit `06486195`).** Arm G short-circuits in
 * [phantom.android.service.PhantomMessagingService.onStartCommand] BEFORE
 * production `transport.connect(...)` — no production `KtorRelayTransport`
 * WS to relay in parallel with Arm G's WS. **However**,
 * `container.initMessagingFromStorage()` and `service.startReceiving()`
 * have already run by the time Arm G's short-circuit slot fires (see
 * [phantom.android.service.PhantomMessagingService] lines around 344-393).
 * MessagingService internal state may therefore still generate short-
 * lived `prekey_publish` / `rest_session_issued` REST traffic on its own
 * connections during the Arm G capture window. This is the same surface
 * §13 T2 hit per the T2 Outcome isolation caveat. Mitigation: PR-G3
 * outcome capture grep-verifies absence (or, if present, counts and
 * timings) of `PREKEY_TRACE` / `REST_TRACE` / `prekey_publish` /
 * `rest_session_issued` in both the UTF-8-decoded Tecno logcat (per §13
 * T2 Outcome UTF-16-vs-ASCII grep-mismatch lesson) and the relay log
 * over the Arm G window. Absent → unqualified isolation wording in the
 * outcome verdict. Present → verdict carries the same concurrent-
 * bootstrap caveat T2 did.
 *
 * **Sequential, not parallel.** Per Inv-ParallelArmIsolation, this arm
 * runs only when [BuildConfig.DEBUG_RC_DIRECT_ARM_G_VIA_REALITY] is
 * exactly `"1"` AND [BuildConfig.DEBUG] is true (strict `== "1"` parse,
 * mirrors `RELAY_ENABLE_HEARTBEAT_ECHO` / `RELAY_ENABLE_SLOW_POST_DIAG`
 * patterns). Precedence per §14 hard gate 7: Arm A → Arm A.2 → T2 →
 * Arm B → Arm C → Arm D → **Arm G** → production. Only ONE arm runs per
 * build because the BuildConfig gates are sequential `if` blocks in the
 * Service.
 *
 * **Own `OkHttpClient` built AFTER `Ready(socksPort)` arrives.** Identical
 * timeout parameters to Arm A.2 / Arm D (`pingInterval(15s)`,
 * `readTimeout(60s)`, `connectTimeout(5s)`, `callTimeout(10s)`,
 * `protocols(HTTP_1_1)`) PLUS `.proxy(Proxy(SOCKS,
 * InetSocketAddress("127.0.0.1", socksPort)))` where `socksPort` is the
 * dynamic loopback port reported by [XrayState.Ready.socksPort] — NEVER
 * the hardcoded `10808` (see [phantom.core.xray.OperatorXrayConfig] line
 * 43-49 comment for the cross-device V2RayNG collision risk that
 * originally motivated `pickFreeLoopbackPort()`). Keeping the WS timeout
 * profile identical to Arm A.2 / Arm D means the W/X/Y discriminator
 * reads against the same baseline behaviour; the only variable changed
 * is the SOCKS proxy line.
 *
 * **Target endpoint stays `BuildConfig.RELAY_URL`.** Inner WS endpoint
 * is production `wss://relay.phntm.pro/ws` — same URL Arm D used. Server-
 * side, after libXray's outbound xray-server decrypts Reality, the inner
 * TCP stream is forwarded plaintext to the same Caddy endpoint
 * production hits — just from the operator VPS IP rather than from the
 * mobile Tele2 LTE radio. This is the locked single-variable change:
 * "outer transport bare TLS" → "outer transport Reality-wrapped TLS".
 *
 * **Heartbeat timing (mirrors Arm A.2 / Arm D OQ-1):** first heartbeat
 * fires at `openAt + 15s`, not immediately at `openAt`. This mirrors
 * OkHttp's first Ping timing so heartbeat does not race with WS upgrade
 * and confuse Mode 2 anchor analysis.
 *
 * **Send-time tracking + echo parsing rigour:** identical to Arm A.2 /
 * Arm D — bounded [SEND_TIME_MAP_CAPACITY] entries, oldest eviction
 * logged at debug, echoes classified only when length ≤
 * [HEARTBEAT_ECHO_MAX_LEN], starts with exact [HEARTBEAT_ECHO_PREFIX],
 * exactly two `:`-separated `Long`s follow. Mirrors the relay-side
 * `parse_heartbeat_echo_payload` validation locked in PR #279.
 *
 * **Lifecycle shields:** `onClosed` / `onFailure` null
 * [currentWebSocket] before logging + `completion.complete`, so a
 * concurrent [stop] call cannot read a stale reference (shield #1 from
 * PR #276). The `runOneSession` `finally` block is shield #2. The
 * heartbeat coroutine rethrows `CancellationException` so the expected
 * `heartbeatJob.cancel()` in `finally` does not log a spurious warning
 * per session close (PR #280 P2 fix). `openedAt` deferred completes
 * exceptionally from `onClosed` / `onFailure` so the heartbeat
 * coroutine's `openedAt.await()` unblocks cleanly if the session failed
 * before `onOpen` ever fired (PR #280 P1 fix).
 *
 * **Reuse of production `xrayService` singleton.** Per §14 code-state
 * note (a) verified against master `d0f41fbe`: the production
 * `AppContainer.xrayService` lazy field is safe to materialise from a
 * debug-only Arm G harness because the only other materialiser is the
 * `xrayServiceProvider = { xrayService }` lambda passed to
 * `TransportManager` — and `TransportManager` is reached only via
 * production `transport.connect(...)`, which Arm G short-circuits
 * before. Conditions enforced by this class + the Service teardown
 * pair: (i) Arm G's [start] short-circuits before production transport;
 * (ii) Service `onDestroy` calls BOTH `container.rcDirectArmG?.stop()`
 * AND `container.xrayService.stop()` (Arm G's [stop] cancels only the
 * Arm G runJob + WS; the Service is the explicit owner of the
 * `xrayService.stop()` call per §14 hard gate 8).
 *
 * **Xray lifecycle wait.** [start] calls `xrayService.start()`
 * (idempotent per [XrayService] contract — no-op if already Ready),
 * then `withTimeout(XRAY_READY_TIMEOUT_MS) { xrayService.state.first
 * { it is XrayState.Ready } }`. Stage 5E observed time-to-Ready ~2-3
 * seconds on Tecno Tele2 LTE; 15 seconds gives a 5× margin. If the wait
 * times out, [start] logs `RC_DIRECT_ARM_G_xray_ready_timeout
 * outcome=xray_not_ready elapsed_ms=15000` and terminates WITHOUT
 * attempting any WS connect (no `runJob` launched). If the state
 * transitions to [XrayState.Failed] within the wait window, [start]
 * logs `RC_DIRECT_ARM_G_xray_failed` and terminates the same way.
 *
 * **Xray log redaction (per §14 hard gate 3).** UUID, shortId,
 * publicKey, signed-challenge token are NEVER logged. Only
 * `xray_start_requested`, `xray_ready socksPort=N elapsed_ms=T`,
 * `xray_ready_timeout`, `xray_failed message_class=N` (generic enum
 * rather than the libXray error string which may embed credentials),
 * `xray_stop_requested` (logged from the Service teardown side).
 *
 * **Server-side dependency state.** Reuses Stage 5E.B.5 production
 * Reality endpoint on `:8443` exactly as deployed — zero server-side
 * code change. Relay-side `RELAY_ENABLE_HEARTBEAT_ECHO=1` must be
 * flipped on the VPS for the PR-G2 field test (same flag Arm A.2 / Arm
 * D used); revert is the operator runbook step before PR-G3 outcome
 * merges. Locked in `docs/tracks/rc-direct-stability1.md` §14 Setup
 * step 3 (VPS operator pre-merge step).
 *
 * **No production behaviour change.** Wire-up sites (AppContainer +
 * PhantomMessagingService) gate every reference behind
 * `BuildConfig.DEBUG && BuildConfig.DEBUG_RC_DIRECT_ARM_G_VIA_REALITY == "1"`.
 * Release builds (`!BuildConfig.DEBUG`) ignore the flag entirely; the
 * release BuildConfig block pins `DEBUG_RC_DIRECT_ARM_G_VIA_REALITY`
 * to `""` as defence-in-depth. The class is `internal` to the diagnostic
 * package so production code cannot accidentally reference it.
 * Production `BuildConfig.RELAY_URL`, `RelayTransportFactory.kt`,
 * `TransportManager`, `KtorRelayTransport`, `TransportPreferences`
 * remain read-only for the entire Arm G lifecycle.
 */
internal class RcDirectArmG(
    private val identityManager: IdentityManager,
    private val relayUrl: String,
    private val xrayService: XrayService,
    private val scope: CoroutineScope,
) {

    @Volatile private var runJob: Job? = null
    @Volatile private var currentWebSocket: WebSocket? = null

    fun start(identityHex: String, signingPubKeyHex: String) {
        if (runJob?.isActive == true) {
            Log.w(TAG, "RC_DIRECT_ARM_G_start_ignored already_running")
            return
        }
        runJob = scope.launch {
            Log.i(
                TAG,
                "RC_DIRECT_ARM_G_armed " +
                    "identity_prefix=${identityHex.take(16)} " +
                    "signing_prefix=${signingPubKeyHex.take(16)} " +
                    "relay_url=$relayUrl " +
                    "ping_interval_ms=15000 " +
                    "heartbeat_interval_ms=$HEARTBEAT_INTERVAL_MS " +
                    "heartbeat_first_at_ms=$HEARTBEAT_INTERVAL_MS " +
                    "send_time_map_capacity=$SEND_TIME_MAP_CAPACITY " +
                    "read_timeout_ms=60000 " +
                    "call_timeout_ms=10000 " +
                    "protocols=HTTP_1_1 " +
                    "xray_ready_timeout_ms=$XRAY_READY_TIMEOUT_MS",
            )

            // Xray lifecycle wait (§14 hard gate 5 + 9).
            val xrayWaitStartMs = System.currentTimeMillis()
            Log.i(TAG, "RC_DIRECT_ARM_G_xray_start_requested")
            runCatching { xrayService.start() }.onFailure { t ->
                // start() failure here is rare — XrayServiceFactory's
                // start() does not throw; it transitions state to Failed
                // and returns. But guard the call site anyway in case
                // a future implementation breaks that contract.
                Log.w(
                    TAG,
                    "RC_DIRECT_ARM_G_xray_start_threw " +
                        "t=${t::class.simpleName} msg=${t.message?.take(160)}",
                )
                return@launch
            }

            // withTimeout block returns Int? — null signals that the
            // observed terminal state was XrayState.Failed (not Ready)
            // within the timeout window. We can't `return@launch` from
            // inside the suspending withTimeout block (non-local return
            // through a non-inline lambda is prohibited), so we surface
            // the failure as a nullable and act on it at the outer
            // `socksPort == null` check below.
            val socksPortOrNull: Int? = try {
                withTimeout(XRAY_READY_TIMEOUT_MS) {
                    val ready = xrayService.state.first { it is XrayState.Ready || it is XrayState.Failed }
                    when (ready) {
                        is XrayState.Ready -> ready.socksPort
                        is XrayState.Failed -> null
                        else -> error("unreachable") // first { ... } guarantees Ready or Failed
                    }
                }
            } catch (t: TimeoutCancellationException) {
                // §14 hard gate 9 fail-fast: do NOT attempt WS connect on
                // xray_not_ready. Terminate the diagnostic; foreground
                // Service stays alive but Arm G itself is finished.
                Log.w(
                    TAG,
                    "RC_DIRECT_ARM_G_xray_ready_timeout outcome=xray_not_ready " +
                        "elapsed_ms=$XRAY_READY_TIMEOUT_MS",
                )
                return@launch
            }
            if (socksPortOrNull == null) {
                // §14 hard gate 3: redact libXray error message (may
                // embed credentials). Log generic class marker only.
                // Observed XrayState.Failed before XrayState.Ready
                // within the 15-second wait window.
                Log.w(
                    TAG,
                    "RC_DIRECT_ARM_G_xray_failed message_class=ready_wait_observed_failed " +
                        "elapsed_ms=${System.currentTimeMillis() - xrayWaitStartMs}",
                )
                return@launch
            }
            val socksPort: Int = socksPortOrNull

            val xrayReadyElapsedMs = System.currentTimeMillis() - xrayWaitStartMs
            Log.i(
                TAG,
                "RC_DIRECT_ARM_G_xray_ready socksPort=$socksPort elapsed_ms=$xrayReadyElapsedMs",
            )

            // Build the OkHttp client with the SOCKS5 proxy bound to the
            // dynamic Ready socksPort. The .proxy(...) line IS the single
            // structural variable that distinguishes Arm G from Arm A.2 /
            // Arm D — every other builder parameter is identical so the
            // W/X/Y discriminator reads against the same baseline.
            val client = OkHttpClient.Builder()
                .pingInterval(15_000L, TimeUnit.MILLISECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(5, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .protocols(listOf(Protocol.HTTP_1_1))
                .proxy(
                    java.net.Proxy(
                        java.net.Proxy.Type.SOCKS,
                        InetSocketAddress("127.0.0.1", socksPort),
                    ),
                )
                .build()

            // Reconnect loop matching Arm A.2 / Arm D. §14 PASS criterion
            // explicitly allows "single session OR across reconnects
            // without dropping below threshold at any point", so a clean
            // ≥ 10 min session immediately reads as PASS while the loop
            // keeps capturing until the operator cuts the 15-min window.
            var sessionEpoch = 0L
            while (isActive) {
                sessionEpoch++
                val outcome = runOneSession(
                    client = client,
                    identityHex = identityHex,
                    signingPubKeyHex = signingPubKeyHex,
                    sessionEpoch = sessionEpoch,
                )
                Log.i(TAG, "RC_DIRECT_ARM_G_session_finished s=$sessionEpoch outcome=$outcome")
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
                        "RC_DIRECT_ARM_G_ws_cancel_threw t=${t::class.simpleName} " +
                            "msg=${t.message?.take(160)}",
                    )
                }
        }
        // NOTE: this stop() does NOT call xrayService.stop(). Per §14 hard
        // gate 8, the Service onDestroy block owns the xrayService.stop()
        // call AFTER this stop() returns. Ordering is: cancel Arm G's
        // runJob + WS first (so a final reconnect attempt does not hit
        // "connection refused" mid-shutdown of libXray), then stop the
        // xrayService daemon.
        Log.i(TAG, "RC_DIRECT_ARM_G_stopped ws_cancelled=${ws != null}")
    }

    private suspend fun runOneSession(
        client: OkHttpClient,
        identityHex: String,
        signingPubKeyHex: String,
        sessionEpoch: Long,
    ): String {
        val sessionStartMs = System.currentTimeMillis()
        Log.i(TAG, "RC_DIRECT_ARM_G_session_start s=$sessionEpoch wall_clock_ms=$sessionStartMs")
        val authedUrl = buildAuthedWsUrl(client, identityHex, signingPubKeyHex, sessionEpoch)
            ?: return OUTCOME_AUTH_ABORTED
        val authDoneMs = System.currentTimeMillis()
        Log.i(
            TAG,
            "RC_DIRECT_ARM_G_auth_done s=$sessionEpoch auth_elapsed_ms=${authDoneMs - sessionStartMs}",
        )

        val request = Request.Builder().url(authedUrl).build()
        val completion = CompletableDeferred<String>()
        val sendTimeMap = LinkedHashMap<Long, Long>(SEND_TIME_MAP_CAPACITY, 0.75f, true)
        val listener = ArmGListener(sessionEpoch, authDoneMs, completion, sendTimeMap)
        val ws = client.newWebSocket(request, listener)
        currentWebSocket = ws

        val heartbeatJob = scope.launch {
            try {
                // PR #280 P1 fix: gate the first heartbeat on `onOpen`
                // actually firing, not on time elapsed since
                // `newWebSocket(...)`. `openedAt.await()` suspends until
                // the listener completes the deferred in onOpen. If the
                // socket fails before onOpen (auth reject, network failure,
                // handshake error), the listener completes openedAt
                // exceptionally from onClosed / onFailure so this
                // coroutine cancels cleanly via finally.
                listener.openedAt.await()
                delay(HEARTBEAT_INTERVAL_MS)
                while (isActive && listener.sessionAlive) {
                    // Identity guard: if a concurrent stop() or new session
                    // replaced `currentWebSocket`, do not write to this stale
                    // ws. Mirrors the listener shield #1 pattern from PR #276.
                    val live = currentWebSocket
                    if (live !== ws) {
                        Log.d(
                            TAG,
                            "RC_DIRECT_ARM_G_heartbeat_send_skipped_stale_ws s=$sessionEpoch",
                        )
                        break
                    }
                    val seq = listener.nextSeq()
                    val clientMs = System.currentTimeMillis()
                    val payload = "$HEARTBEAT_ECHO_PREFIX$seq:$clientMs"
                    synchronized(sendTimeMap) {
                        if (sendTimeMap.size >= SEND_TIME_MAP_CAPACITY) {
                            val oldest = sendTimeMap.entries.iterator().next()
                            Log.d(
                                TAG,
                                "RC_DIRECT_ARM_G_echo_send_time_evicted s=$sessionEpoch " +
                                    "evicted_seq=${oldest.key} evicted_age_ms=${clientMs - oldest.value}",
                            )
                            sendTimeMap.remove(oldest.key)
                        }
                        sendTimeMap[seq] = clientMs
                    }
                    // The single intentional send call site for Arm G.
                    // Inv-RawArmReadOnly carve-out: payload is the canonical
                    // heartbeat prefix + counter formatting ONLY. PR-H1e
                    // regression class avoided because the frame is WS Text
                    // (opcode 0x1), never WS Ping/Pong.
                    val sent = runCatching { ws.send(payload) }.getOrDefault(false)
                    if (!sent) {
                        Log.w(
                            TAG,
                            "RC_DIRECT_ARM_G_heartbeat_send_failed s=$sessionEpoch seq=$seq " +
                                "reason=enqueue_returned_false",
                        )
                        break
                    }
                    listener.echoSent.set(seq)
                    Log.i(
                        TAG,
                        "RC_DIRECT_ARM_G_echo_sent s=$sessionEpoch seq=$seq client_ms=$clientMs",
                    )
                    delay(HEARTBEAT_INTERVAL_MS)
                }
            } catch (t: Throwable) {
                // PR #280 P2 fix: rethrow CancellationException so the
                // expected `heartbeatJob.cancel()` in `runOneSession.finally`
                // does not log a spurious warning per session close.
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(
                    TAG,
                    "RC_DIRECT_ARM_G_heartbeat_sender_threw s=$sessionEpoch " +
                        "t=${t::class.simpleName} msg=${t.message?.take(160)}",
                )
            }
        }

        return try {
            completion.await()
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "RC_DIRECT_ARM_G_completion_await_failed s=$sessionEpoch " +
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
                        "RC_DIRECT_ARM_G_ws_cancel_in_finally_threw s=$sessionEpoch " +
                            "t=${t::class.simpleName} msg=${t.message?.take(160)}",
                    )
                }
        }
    }

    private suspend fun buildAuthedWsUrl(
        client: OkHttpClient,
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
                            "RC_DIRECT_ARM_G_challenge_non_2xx s=$sessionEpoch " +
                                "status=${resp.code}",
                        )
                        return@use null
                    }
                    val body = resp.body?.string().orEmpty()
                    NONCE_REGEX.find(body)?.groupValues?.get(1)
                        ?: run {
                            Log.w(
                                TAG,
                                "RC_DIRECT_ARM_G_challenge_no_nonce_hex s=$sessionEpoch " +
                                    "body_head=${body.take(120).replace('\n', ' ')}",
                            )
                            null
                        }
                }
            }.onFailure { t ->
                Log.w(
                    TAG,
                    "RC_DIRECT_ARM_G_challenge_threw s=$sessionEpoch " +
                        "t=${t::class.simpleName} msg=${t.message?.take(200)}",
                )
            }.getOrNull()
        } ?: return null

        val nonceBytes = hexToBytes(nonceHex)
        val signature = identityManager.signRelayChallenge(nonceBytes) ?: run {
            Log.w(TAG, "RC_DIRECT_ARM_G_signer_returned_null s=$sessionEpoch")
            return null
        }
        if (signature.size != 64) {
            Log.w(
                TAG,
                "RC_DIRECT_ARM_G_signature_bad_size s=$sessionEpoch size=${signature.size}",
            )
            return null
        }
        return relayUrl +
            "?id=$identityHex" +
            "&signing_pubkey=$signingPubKeyHex" +
            "&challenge=$nonceHex" +
            "&signature=${bytesToHex(signature)}"
    }

    private inner class ArmGListener(
        private val sessionEpoch: Long,
        private val authDoneMs: Long,
        private val completion: CompletableDeferred<String>,
        private val sendTimeMap: LinkedHashMap<Long, Long>,
    ) : WebSocketListener() {
        @Volatile var sessionAlive: Boolean = true
        @Volatile var openAtMs: Long = 0L

        val openedAt: CompletableDeferred<Long> = CompletableDeferred()

        private val seqCounter = java.util.concurrent.atomic.AtomicLong(0L)
        val echoSent = java.util.concurrent.atomic.AtomicLong(0L)
        private val echoReceived = java.util.concurrent.atomic.AtomicLong(0L)
        @Volatile private var lastEchoRttMs: Long = -1L

        @Volatile private var inboundTextFrames = 0L
        @Volatile private var inboundBinaryFrames = 0L

        fun nextSeq(): Long = seqCounter.incrementAndGet()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            openAtMs = System.currentTimeMillis()
            openedAt.complete(openAtMs)
            Log.i(
                TAG,
                "RC_DIRECT_ARM_G_ws_open s=$sessionEpoch " +
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
                    "RC_DIRECT_ARM_G_echo_received s=$sessionEpoch " +
                        "seq=$seq " +
                        "rtt_ms=$rttMs " +
                        "since_open_ms=$sinceOpenMs " +
                        "echo_received_total=${echoReceived.get()}",
                )
            } else {
                Log.i(
                    TAG,
                    "RC_DIRECT_ARM_G_ws_text s=$sessionEpoch " +
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
                "RC_DIRECT_ARM_G_ws_bytes s=$sessionEpoch " +
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
                "RC_DIRECT_ARM_G_ws_closing s=$sessionEpoch " +
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
            openedAt.completeExceptionally(
                IllegalStateException("ws closed before onOpen (code=$code)"),
            )
            val nowMs = System.currentTimeMillis()
            val lifetimeMs = if (openAtMs > 0L) nowMs - openAtMs else -1L
            val sentTotal = echoSent.get()
            val receivedTotal = echoReceived.get()
            val missing = (sentTotal - receivedTotal).coerceAtLeast(0L)
            Log.i(
                TAG,
                "RC_DIRECT_ARM_G_ws_closed s=$sessionEpoch " +
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
            openedAt.completeExceptionally(
                IllegalStateException(
                    "ws failed before onOpen (${t::class.simpleName})",
                ),
            )
            val nowMs = System.currentTimeMillis()
            val lifetimeMs = if (openAtMs > 0L) nowMs - openAtMs else -1L
            val sentTotal = echoSent.get()
            val receivedTotal = echoReceived.get()
            val missing = (sentTotal - receivedTotal).coerceAtLeast(0L)
            Log.w(
                TAG,
                "RC_DIRECT_ARM_G_ws_failure s=$sessionEpoch " +
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

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { ((it.toInt() and 0xFF) or 0x100).toString(16).substring(1) }

    companion object {
        private const val TAG = "RC_DIRECT_ARM_G"
        private val NONCE_REGEX = Regex("\"nonce_hex\"\\s*:\\s*\"([a-fA-F0-9]+)\"")
        private const val OUTCOME_AUTH_ABORTED = "auth_aborted"

        // Canonical heartbeat payload prefix locked in mini-lock §4 Arm D
        // and reused byte-for-byte across Arm D, Arm A.2, and Arm G. Must
        // match the relay-side HEARTBEAT_ECHO_PREFIX constant
        // (services/relay/src/routes.rs PR #279) so the echo handler
        // recognises Arm G's Text frames as heartbeats.
        const val HEARTBEAT_ECHO_PREFIX: String = "phantom:diagnostic:heartbeat-echo:v1:"

        // Mirrors the relay-side HEARTBEAT_ECHO_MAX_LEN cap.
        const val HEARTBEAT_ECHO_MAX_LEN: Int = 256

        // Heartbeat send interval. Same value as production
        // pingInterval(15s) so the heartbeat anchor lines up with the WS
        // Ping/Pong anchor on the same session — preserves the W/X/Y
        // discriminator's ability to read both frame classes at the
        // relay app layer against the Arm A.2 / Arm D baseline.
        const val HEARTBEAT_INTERVAL_MS: Long = 15_000L

        // Bounded send-time map for echo RTT computation. Heartbeat at
        // 15 s cadence over a 15 min capture yields ~60 sends total;
        // 32-entry cap covers ~8 min of in-flight outstanding echoes.
        const val SEND_TIME_MAP_CAPACITY: Int = 32

        // Maximum time to wait for XrayState.Ready after xrayService.start().
        // Stage 5E observed time-to-Ready ~2-3 s on Tecno Tele2 LTE; 15 s
        // gives a 5× margin. On timeout the diagnostic terminates without
        // a WS connect attempt (§14 hard gate 9).
        const val XRAY_READY_TIMEOUT_MS: Long = 15_000L

        /**
         * Parses an inbound Text frame as a heartbeat echo. Returns
         * `Pair(seq, clientMs)` only when ALL of the following hold:
         *   * length ≤ [HEARTBEAT_ECHO_MAX_LEN]
         *   * starts with [HEARTBEAT_ECHO_PREFIX] exactly (not a substring)
         *   * exactly two `:`-separated fields after the prefix
         *   * both fields parse as `Long`
         *
         * Mirrors the relay-side `parse_heartbeat_echo_payload` validation
         * locked in PR #279 + Arm A.2 / Arm D classifier. Lives in the
         * companion (vs Arm A.2's private instance method) so unit tests
         * can exercise it without constructing the whole diagnostic class
         * — the parser is pure and stateless, so companion placement is
         * the correct semantic home for it.
         */
        internal fun parseHeartbeatEcho(raw: String): Pair<Long, Long>? {
            if (raw.length > HEARTBEAT_ECHO_MAX_LEN) return null
            if (!raw.startsWith(HEARTBEAT_ECHO_PREFIX)) return null
            val rest = raw.substring(HEARTBEAT_ECHO_PREFIX.length)
            val parts = rest.split(':')
            if (parts.size != 2) return null
            val seq = parts[0].toLongOrNull() ?: return null
            val clientMs = parts[1].toLongOrNull() ?: return null
            return seq to clientMs
        }
    }
}
