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
import phantom.core.xray.OperatorXrayConfig
import phantom.core.xray.XrayService
import phantom.core.xray.XrayState
import phantom.core.xray.createXrayService

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
 * **Per-session libXray restart (PR-G2 fixup v6, 2026-06-06 Wi-Fi
 * smoke discriminator).** The original §14 design reused the
 * production `AppContainer.xrayService` singleton across all sessions
 * of an Arm G run. The Wi-Fi smoke on 2026-06-06 found that the first
 * Reality WS session opens cleanly (relay reaches Caddy, echo round-
 * trip succeeds), but subsequent reconnect handshakes from the same
 * long-running Android libXray process fall into REALITY fallback
 * (xray-server forwards to `dest = www.microsoft.com:443` instead of
 * unwrapping to phantom-caddy) — the Mode 2 ping/pong timeout kills
 * the inner WS, then every retry from the same long-running
 * gomobile-bound libXray instance is rejected as not-a-valid-REALITY-
 * peer. To discriminate whether this is (A) a state issue in the
 * long-running Android libXray process AFTER the first tunnel, or
 * (B) a deeper Reality protocol incompatibility, this class now
 * restarts libXray AND constructs a fresh `OkHttpClient` between
 * every Arm G session — independent of the production singleton.
 *
 * Implementation:
 *   - Constructor takes [xrayDataDir] (absolute path) instead of an
 *     [XrayService] instance.
 *   - Each session in the reconnect loop calls `createXrayService(
 *     OperatorXrayConfig.toConfig(xrayDataDir))` to construct a FRESH
 *     [XrayService] with a FRESH `socksPort` (via the default
 *     `pickFreeLoopbackPort()` allocator), then `start()`s it,
 *     `withTimeout` waits for [XrayState.Ready], builds a fresh
 *     [OkHttpClient] bound to that session's SOCKS port, runs one
 *     session, then `stop()`s the libXray instance AND
 *     `client.connectionPool.evictAll()` to clear OkHttp's per-host
 *     connection pool before the next session.
 *   - The production `AppContainer.xrayService` lazy field is no
 *     longer touched by Arm G — the lazy never materialises in Arm G
 *     mode (the Service short-circuit fires before any production
 *     transport path that would consume it).
 *
 * Expected discriminator after this fixup:
 *   - **(A) every restarted Wi-Fi session opens again** → state issue
 *     in the long-running gomobile libXray process strongly confirmed.
 *     Reality leg of the per-mode-chain becomes viable WITH per-session
 *     restart as the workaround until upstream libXray fixes the state
 *     issue.
 *   - **(B) only first session opens, even after full Xray restart**
 *     → deeper Android/libXray/Reality issue. Architecture pivot per
 *     §13 T2 lesson: REST + Matrix-style long-poll primary; Reality
 *     stays for HTTP-only fallback. PR-G3 outcome captures this as
 *     BLOCKED-by-upstream-libXray for WS path specifically.
 *   - **(C) Tele2 LTE still never opens** → Tele2 case remains
 *     separate/inconclusive until the Wi-Fi reconnect bug is isolated.
 *     PR-G3 wording for Tele2 stays as "Reality WS path: inconclusive
 *     on RU mobile carrier within the Arm G time-box."
 *
 * **No production transport change.** Arm G manages its OWN ephemeral
 * libXray instances inside the diagnostic class. The Service
 * `onDestroy` teardown still calls `container.xrayService.stop()` as
 * a defensive backstop, but in Arm G mode that production singleton
 * is never materialised — the call is idempotent no-op.
 *
 * **v7 amendments (2026-06-06, after Wi-Fi PARTIAL evidence + two-
 * architect review):**
 *   - **OkHttp `.pingInterval(0L, ...)`** disables OkHttp WS-protocol
 *     auto-Ping. v6 evidence: app Text echo seq=2 also went silent
 *     before OkHttp Ping noticed at +45s, so OkHttp Ping is framed as
 *     a TRIGGER/ACCELERANT discriminator, NOT a "false-killer fix."
 *   - **Heartbeat sender continues after a missing echo.** Counts
 *     consecutive misses; first miss per session emits
 *     `RC_DIRECT_ARM_G_silence_onset s=N seq=M cumulative_bytes=K
 *     wall_clock_ms=T mono_ms=T`. Recovery (later echo arrives)
 *     resets counter and emits `RC_DIRECT_ARM_G_silence_broken`.
 *     Session ends at [MAX_CONSECUTIVE_MISSES] (8) OR
 *     [SESSION_WALL_CLOCK_CAP_MS] (15 min) — whichever fires first.
 *   - **`cumulative_bytes` on every `echo_sent`.** Sum of
 *     `payload.encodeToByteArray().size` (exact UTF-8 length), NOT
 *     `seq * 50`. Lower-bound app-payload metric — Reality/TLS/WS
 *     framing is on top of this. Lets §13 T2 byte-budget comparison
 *     (~5 KB onset on Tele2 LTE) be apples-to-apples with T2's
 *     POST-body counter.
 *   - **`mono_ms` on every `echo_sent` / `echo_received`** — session-
 *     start anchor, so timeline reconstruction does not need to
 *     subtract `openAtMs` per line.
 *   - **`RC_DIRECT_ARM_G_locks` session-start anchor.** v6 already
 *     ran with foreground notification + WIFI_MODE_FULL_HIGH_PERF +
 *     PARTIAL_WAKE_LOCK + MulticastLock all held by Service
 *     `onCreate` line 147 → `acquireKeepAliveLocks()`. WifiLock
 *     matrix was therefore DROPPED from v7 spec (would have been
 *     no-op; gating onCreate locks was out of v7 scope). This log
 *     line anchors the evidence per session.
 *   - **`ws.close(1000, "...")` bounded by [WS_CLOSE_HANDSHAKE_BOUND_MS]
 *     then `ws.cancel()`.** Both in [stop] (Service.onDestroy path)
 *     and in `runOneSession.finally` (per-session teardown). Clean
 *     Close lets relay emit `session_summary` promptly; cancel()
 *     bound prevents half-open zombie from hanging teardown.
 *
 * **v7 primary discriminator signals (NOT WifiLock):**
 *   (a) `pingInterval(0)` permanent-vs-transient onset — do echoes
 *       survive >= 10 min, or stop after seq=1/2?
 *   (b) Relay per-seq `heartbeat_echo_received` direction (live debug-
 *       tail during run) — uplink vs downlink silence.
 *   (c) Tele2 LTE byte-onset vs ~5 KB cumulative — does §13 T2 byte-
 *       budget class survive Reality?
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
    private val xrayDataDir: String,
    private val scope: CoroutineScope,
) {

    @Volatile private var runJob: Job? = null
    @Volatile private var currentWebSocket: WebSocket? = null
    // PR-G2 fixup v6 (2026-06-06): track the per-session ephemeral
    // libXray instance so [stop] (called from Service onDestroy) can
    // cancel the currently-running session's gomobile process even if
    // the runJob is cancelled mid-session. The reconnect loop nulls
    // this after each session's stop_done event so stop() doesn't
    // double-call on an already-stopped instance.
    @Volatile private var currentXrayService: XrayService? = null

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

            // ── v10 amendment 2026-06-08 (Council apprоval, amends mini-lock decision #2) ──
            //
            // **Persistent-single-instance libXray lifecycle.** v9 wide-logcat
            // evidence empirically refuted the v6 per-session-restart approach:
            //
            //   - s=1 (first Xray instance, clean start) reached canonical
            //     Reality success on Android Tecno Wi-Fi: ClientHello 517 →
            //     TLS 1.3 1163 → `CopyRawConn splice` → `/health 200 OK`
            //     elapsed_ms=656 → `auth_done` elapsed_ms=828.
            //   - s=2 through s=6 (after `xray.stop()` + `xray.start()`)
            //     uniformly degraded: dial to :8443 succeeded but
            //     `XtlsFilterTls found tls client hello` did NOT appear,
            //     Reality handshake stalled, `/health` threw timeout after
            //     10 s.
            //
            // Council resolution 2026-06-08: per-session Xray restart is a
            // suspected CAUSE of failure rather than an isolation tool.
            // Replaced with one persistent XrayService per Arm G run.
            // SOCKS port (dynamic per `pickFreeLoopbackPort()`) fixed once
            // before the reconnect loop; reused for every session. Xray
            // stopped once in the outer finally block (and additionally
            // safety-stopped in [stop] for mid-run interruption).
            //
            // What stays per-session: OkHttp client rebuild + connection
            // pool eviction. Vladislav's 2026-06-06 guardrail ("OkHttp
            // client must not reuse connections across sessions; either
            // build a fresh OkHttpClient per Arm G session or explicitly
            // evictAll + dispatcher cleanup before reconnect") still holds
            // — only the libXray instance is now persistent.
            val xrayWaitStartMs = System.currentTimeMillis()
            val xrayConfig = OperatorXrayConfig.toConfig(xrayDataDir)
                .copy(loglevel = "debug")
            val xrayService = createXrayService(xrayConfig)
            currentXrayService = xrayService

            Log.i(TAG, "RC_DIRECT_ARM_G_xray_start_requested s=0 reason=persistent_run_setup")
            val startResult = runCatching { xrayService.start() }
            if (startResult.isFailure) {
                val t = startResult.exceptionOrNull()
                Log.w(
                    TAG,
                    "RC_DIRECT_ARM_G_xray_start_threw s=0 " +
                        "message_class=start_call_threw t=${t?.let { it::class.simpleName }} " +
                        "outcome=run_aborted_before_loop",
                )
                runCatching { xrayService.stop() }
                currentXrayService = null
                return@launch
            }

            val socksPortOrNull: Int? = try {
                withTimeout(XRAY_READY_TIMEOUT_MS) {
                    val ready = xrayService.state.first {
                        it is XrayState.Ready || it is XrayState.Failed
                    }
                    when (ready) {
                        is XrayState.Ready -> ready.socksPort
                        is XrayState.Failed -> null
                        else -> error("unreachable")
                    }
                }
            } catch (t: TimeoutCancellationException) {
                Log.w(
                    TAG,
                    "RC_DIRECT_ARM_G_xray_ready_timeout s=0 " +
                        "outcome=xray_not_ready elapsed_ms=$XRAY_READY_TIMEOUT_MS",
                )
                null
            }

            if (socksPortOrNull == null) {
                Log.w(
                    TAG,
                    "RC_DIRECT_ARM_G_xray_failed s=0 " +
                        "message_class=ready_wait_observed_failed " +
                        "elapsed_ms=${System.currentTimeMillis() - xrayWaitStartMs} " +
                        "outcome=run_aborted_before_loop",
                )
                runCatching { xrayService.stop() }
                currentXrayService = null
                return@launch
            }

            val socksPort: Int = socksPortOrNull
            val xrayReadyElapsedMs = System.currentTimeMillis() - xrayWaitStartMs
            Log.i(
                TAG,
                "RC_DIRECT_ARM_G_xray_ready s=0 socksPort=$socksPort elapsed_ms=$xrayReadyElapsedMs " +
                    "reason=persistent_run_setup",
            )

            var sessionEpoch = 0L
            // v8 amendment 2026-06-08: cap consecutive auth_aborted sessions
            // so a chronic SOCKS/Reality path failure does not spin the
            // reconnect loop indefinitely. v7 Wi-Fi sanity produced 20+
            // identical `challenge_threw timeout` sessions in 6 minutes.
            // Counter resets to 0 on any non-auth_aborted outcome (echo
            // success, WS-level Mode 2, etc.) so the cap only fires on a
            // chronic pre-WS failure pattern.
            var consecutiveAuthAborted = 0
            try {
            while (isActive) {
                sessionEpoch++

                // v7 amendment 2026-06-06: lock-state diagnostic anchor.
                // Confirmed via code-read on master + PR #296 head:
                // `PhantomMessagingService.onCreate()` line 147 calls
                // `acquireKeepAliveLocks()`, which acquires (at lines
                // 282 / 291 / 300) WIFI_MODE_FULL_HIGH_PERF WifiLock +
                // PARTIAL_WAKE_LOCK + MulticastLock for the WHOLE
                // service lifetime, BEFORE the Arm G short-circuit at
                // `onStartCommand` fires. Released only in
                // `onDestroy` line 997. So v6/v7 ran with all four
                // anti-parking measures (foreground notification +
                // FULL_HIGH_PERF WifiLock + WakeLock + MulticastLock)
                // already held, which the architect deep-dive said
                // largely refutes the HiOS Wi-Fi radio-parking
                // hypothesis. WifiLock matrix was therefore DROPPED
                // from v7 spec; this log line anchors the evidence so
                // the outcome doc can cite "v6/v7 both ran with all
                // locks held" without needing to re-grep the service.
                Log.i(
                    TAG,
                    "RC_DIRECT_ARM_G_locks s=$sessionEpoch " +
                        "wifilock=held wakelock=held multicastlock=held " +
                        "mode=FULL_HIGH_PERF source=service_onCreate_acquireKeepAliveLocks",
                )

                // v10 amendment 2026-06-08: persistent Xray. Inner-loop
                // Xray construction / start / Ready-wait / stop has
                // been removed per Council resolution that amended
                // mini-lock decision #2. The single XrayService set up
                // BEFORE this loop holds the SOCKS listener on the same
                // `socksPort` for every session; the gomobile libXray
                // runtime is no longer cycled. v9 wide-logcat evidence
                // showed per-session restart degraded Reality handshake
                // in s=2+ after s=1 successfully reached splice +
                // /health 200 + auth_done on Tecno Wi-Fi — so the
                // restart was itself the suspected cause, not the
                // isolation tool.
                //
                // Fresh OkHttp client per session bound to the
                // persistent `socksPort`. Building per session still
                // matters: WS lifecycle state (listener, completion
                // deferred, sendTimeMap) must not bleed across
                // sessions. Vladislav's 2026-06-06 OkHttp guardrail
                // (fresh client + explicit `evictAll()` on the old
                // client) is preserved.
                //
                // v7 amendment 2026-06-06: `pingInterval(0)` DISABLES
                // OkHttp's WS-protocol auto-Ping. v6 evidence: app
                // Text echo seq=2 ALSO went silent before the OkHttp
                // Ping noticed at +45s. So OkHttp auto-Ping is now
                // framed as a TRIGGER/ACCELERANT discriminator, NOT
                // a "false-killer fix". With ping(0), if Text echoes
                // survive ≥ 10 min → control-frame path was the
                // trigger. If echoes still stop after seq=1/2 →
                // real Reality/Android inbound silence and OkHttp
                // Ping was irrelevant. The 8-consecutive-miss
                // counter + 15-min wall-clock cap below replace
                // OkHttp's automatic socket termination so the
                // diagnostic harness still bounds session length.
                val client = OkHttpClient.Builder()
                    .pingInterval(0L, TimeUnit.MILLISECONDS)
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
                    // v8 amendment 2026-06-08: per-session phase listener
                    // so the operator can see where exactly the
                    // 10-second callTimeout window is spent on preflight
                    // and challenge calls (SOCKS connect? TLS handshake
                    // through Reality? response wait?). The listener
                    // reads each call's request tag to distinguish
                    // op=preflight vs op=challenge; the WebSocket open
                    // request is left untagged because Arm G already
                    // emits a dedicated `RC_DIRECT_ARM_G_ws_open` line
                    // from the WebSocket listener.
                    .eventListener(ArmGPhaseListener(sessionEpoch))
                    .build()

                val outcome = runOneSession(
                    client = client,
                    identityHex = identityHex,
                    signingPubKeyHex = signingPubKeyHex,
                    sessionEpoch = sessionEpoch,
                )
                Log.i(TAG, "RC_DIRECT_ARM_G_session_finished s=$sessionEpoch outcome=$outcome")

                // v10 amendment 2026-06-08: per-session Xray teardown
                // REMOVED per Council resolution. The persistent
                // XrayService stays Ready for the next iteration; only
                // the per-session OkHttp pool is evicted (Vladislav's
                // 2026-06-06 guardrail preserved).
                runCatching {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }.onFailure { t ->
                    Log.d(
                        TAG,
                        "RC_DIRECT_ARM_G_okhttp_evict_threw s=$sessionEpoch " +
                            "t=${t::class.simpleName}",
                    )
                }

                // v8 amendment 2026-06-08: cap consecutive auth_aborted
                // sessions. Counter is updated AFTER OkHttp pool
                // eviction (above) so the cap-exceeded path leaves the
                // diagnostic in a clean state — no live pool to tear
                // down again. Counter resets to 0 on any other outcome
                // (echo success, Mode 2, etc.). The persistent Xray
                // is stopped in the outer finally block, not here.
                if (outcome == OUTCOME_AUTH_ABORTED) {
                    consecutiveAuthAborted++
                    if (consecutiveAuthAborted >= MAX_CONSECUTIVE_AUTH_ABORTED) {
                        Log.w(
                            TAG,
                            "RC_DIRECT_ARM_G_auth_abort_cap_exceeded " +
                                "s=$sessionEpoch " +
                                "consecutive=$consecutiveAuthAborted " +
                                "max=$MAX_CONSECUTIVE_AUTH_ABORTED " +
                                "outcome=$OUTCOME_AUTH_ABORT_CAP_EXCEEDED",
                        )
                        break
                    }
                } else {
                    consecutiveAuthAborted = 0
                }

                if (outcome == OUTCOME_AUTH_ABORTED) {
                    delay(5_000L)
                } else {
                    delay(1_000L)
                }
            }
            } finally {
                // v10 amendment 2026-06-08: persistent Xray stops ONCE
                // after the reconnect loop exits (cancellation, cap
                // exceeded, or wall-clock cap). The [stop] entrypoint
                // ALSO stops Xray (mid-run interruption path via
                // Service.onDestroy) — both paths are idempotent
                // because `currentXrayService` is nulled at the first
                // successful stop AND `XrayService.stop()` is itself
                // idempotent (short-circuits on `state == Off`).
                Log.i(TAG, "RC_DIRECT_ARM_G_xray_stop_requested s=$sessionEpoch reason=run_complete")
                runCatching { xrayService.stop() }
                    .onSuccess {
                        Log.i(TAG, "RC_DIRECT_ARM_G_xray_stop_done s=$sessionEpoch reason=run_complete")
                    }
                    .onFailure { t ->
                        Log.w(
                            TAG,
                            "RC_DIRECT_ARM_G_xray_stop_failed s=$sessionEpoch " +
                                "message_class=stop_call_threw t=${t::class.simpleName} " +
                                "reason=run_complete",
                        )
                    }
                if (currentXrayService === xrayService) {
                    currentXrayService = null
                }
            }
        }
    }

    fun stop() {
        val ws = currentWebSocket
        val xray = currentXrayService
        runJob?.cancel()
        runJob = null
        currentWebSocket = null
        currentXrayService = null
        if (ws != null) {
            // v7 amendment per architect 2026-06-06: bounded clean
            // Close before cancel. `ws.close(1000, "arm_g_stop")`
            // queues a Close frame so relay sees the disconnect and
            // emits session_summary promptly (otherwise relay holds
            // the half-open socket until TCP reset — the 17-minute
            // "half-open zombie" pattern seen on v6 conn_id=1/2).
            // After WS_CLOSE_HANDSHAKE_BOUND_MS (~2.5 s) we force
            // teardown via `ws.cancel()` so a half-open dead socket
            // cannot hang stop() — Service.onDestroy has bounded time
            // before SIGKILL; we must not exceed it.
            runCatching { ws.close(1000, "arm_g_stop") }
                .onFailure { t ->
                    Log.d(
                        TAG,
                        "RC_DIRECT_ARM_G_ws_close_threw t=${t::class.simpleName}",
                    )
                }
            // Blocking delay in stop() — runBlocking is safe here
            // because stop() is sync (called from Service.onDestroy
            // teardown block) and the bound is conservative against
            // the Android-imposed onDestroy time budget.
            runCatching {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.delay(WS_CLOSE_HANDSHAKE_BOUND_MS)
                }
            }
            runCatching { ws.cancel() }
                .onFailure { t ->
                    Log.w(
                        TAG,
                        "RC_DIRECT_ARM_G_ws_cancel_threw t=${t::class.simpleName} " +
                            "msg=${t.message?.take(160)}",
                    )
                }
        }
        // PR-G2 fixup v6 (2026-06-06): stop the per-session libXray
        // instance if one is currently live. The reconnect loop nulls
        // `currentXrayService` after a clean session-finished
        // teardown, so this path only fires when stop() is called
        // mid-session (i.e. Service onDestroy interrupted a running
        // session). We use `runBlocking` because we have no
        // CoroutineScope here and XrayService.stop() is suspend; the
        // Service teardown path tolerates the brief block (it already
        // does the same for the production singleton fallback below).
        if (xray != null) {
            Log.i(TAG, "RC_DIRECT_ARM_G_xray_stop_requested reason=arm_g_stop_called_mid_session")
            runCatching {
                kotlinx.coroutines.runBlocking { xray.stop() }
            }.onSuccess {
                Log.i(TAG, "RC_DIRECT_ARM_G_xray_stop_done reason=arm_g_stop_called_mid_session")
            }.onFailure { t ->
                Log.w(
                    TAG,
                    "RC_DIRECT_ARM_G_xray_stop_failed reason=arm_g_stop_called_mid_session " +
                        "message_class=stop_call_threw t=${t::class.simpleName}",
                )
            }
        }
        // NOTE: this stop() handles its OWN per-session libXray
        // instance via the [currentXrayService] tracking above. The
        // Service onDestroy block ALSO calls
        // `container.xrayService.stop()` on the production singleton
        // as a defensive backstop — but in Arm G mode the production
        // singleton is never materialised (the lazy field is only
        // materialised by the production TransportManager path, which
        // Arm G short-circuits before). So that Service-level call is
        // an idempotent no-op in practice. Original §14 hard gate 8
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
        // v8 amendment 2026-06-08: SOCKS-proxied /health preflight BEFORE
        // challenge. The v7 Wi-Fi sanity 20+ sessions all died with
        // `challenge_threw timeout`; relay saw no Arm G traffic at all.
        // The discriminator question is: does any HTTP request through
        // the per-session SOCKS-Reality path reach the relay? /health is
        // the cheapest probe (200 OK with `{"status":"ok"}` from a
        // healthy relay, no body parse needed) and shares the same
        // OkHttpClient (same SOCKS proxy + same callTimeout=10s + same
        // event listener) as the challenge call so any wiring
        // difference is removed as a confound. Result is logged but
        // NOT used to short-circuit the session — we run challenge
        // regardless so the operator gets BOTH signals per session
        // and can read the matrix (preflight PASS + challenge FAIL =
        // challenge-specific; both FAIL = SOCKS-Reality broken on
        // Tecno; preflight slow + challenge timeout = callTimeout
        // too short for Tecno-side Reality handshake latency).
        runHealthPreflight(client, sessionEpoch)
        val authedUrl = buildAuthedWsUrl(client, identityHex, signingPubKeyHex, sessionEpoch)
            ?: return OUTCOME_AUTH_ABORTED
        val authDoneMs = System.currentTimeMillis()
        Log.i(
            TAG,
            "RC_DIRECT_ARM_G_auth_done s=$sessionEpoch auth_elapsed_ms=${authDoneMs - sessionStartMs}",
        )

        // v10 amendment 2026-06-08: WS upgrade goes through a SEPARATE
        // OkHttpClient with ConnectionPool(0, ...) so the WS upgrade
        // CANNOT reuse the keep-alive TLS connection that preflight and
        // challenge just used over the Reality splice. This is Architect 1's
        // Bug A discriminator — v9 evidence showed preflight (200 OK) +
        // challenge (auth_done) prevailed through the same Reality splice
        // but the immediately-following WS upgrade through that same
        // (likely-reused) HTTP/1.1 keep-alive connection failed with
        // `response_code=null` after 10 s. If a fresh-pool WS client now
        // PASSES the upgrade, pool-reuse-over-splice was the proximal
        // cause. If it still FAILS, the cause is downstream (relay-side
        // `/ws` handler OR Reality-Vision-splice + WS framing
        // interaction). Tag the request so [ArmGPhaseListener] surfaces
        // its phases as `op=ws_upgrade phase=...` rather than `op=untagged`,
        // and emit `RC_DIRECT_ARM_G_ws_about_to_connect` immediately before
        // the blocking `newWebSocket(...)` call so the operator can
        // distinguish "WS upgrade never started" from "WS upgrade started
        // but timed out".
        val request = Request.Builder()
            .url(authedUrl)
            .tag(String::class.java, "ws_upgrade")
            .build()
        val wsClient = client.newBuilder()
            .connectionPool(
                okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS),
            )
            .build()
        Log.i(
            TAG,
            "RC_DIRECT_ARM_G_ws_about_to_connect s=$sessionEpoch " +
                "url=$authedUrl call_timeout_ms=10000 " +
                "pool_policy=no_reuse_zero_idle " +
                "discriminator=bug_a_pool_reuse_over_splice",
        )
        val completion = CompletableDeferred<String>()
        val sendTimeMap = LinkedHashMap<Long, Long>(SEND_TIME_MAP_CAPACITY, 0.75f, true)
        val listener = ArmGListener(sessionEpoch, authDoneMs, completion, sendTimeMap)
        val ws = wsClient.newWebSocket(request, listener)
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
                // v7 amendment 2026-06-06: session-start anchor for
                // mono_ms timestamps. Logs that follow carry
                // `mono_ms=T` (= now - sessionStartMonoMs) so timeline
                // reconstruction does not require subtracting from
                // openAtMs / sessionEpoch wall_clock_ms.
                val sessionStartMonoMs = System.currentTimeMillis()
                var cumulativeBytes = 0L
                var consecutiveMisses = 0
                var silenceOnsetLogged = false

                delay(HEARTBEAT_INTERVAL_MS)
                while (isActive && listener.sessionAlive) {
                    val nowMs = System.currentTimeMillis()
                    val monoMs = nowMs - sessionStartMonoMs

                    // v7 amendment: 15-min wall-clock cap per session.
                    // OkHttp's automatic socket-kill on ping timeout is
                    // disabled (pingInterval(0) above); this cap takes
                    // over the bounded-session-length contract that
                    // the operator runbook relies on.
                    if (monoMs >= SESSION_WALL_CLOCK_CAP_MS) {
                        Log.i(
                            TAG,
                            "RC_DIRECT_ARM_G_v7_walltime_cap s=$sessionEpoch " +
                                "mono_ms=$monoMs cap_ms=$SESSION_WALL_CLOCK_CAP_MS " +
                                "consecutive_misses=$consecutiveMisses " +
                                "cumulative_bytes=$cumulativeBytes",
                        )
                        completion.complete("v7_walltime_cap_15min")
                        break
                    }

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
                    val clientMs = nowMs
                    val payload = "$HEARTBEAT_ECHO_PREFIX$seq:$clientMs"
                    // v7 amendment per architect: cumulative_bytes =
                    // exact summed UTF-8 length per Text payload
                    // (payload.encodeToByteArray().size), NOT seq*50.
                    // Labelled "app-payload lower bound" — Reality/TLS/
                    // WS framing is on top of this, invisible from the
                    // app layer. Lets outcome-doc compare like-for-like
                    // with §13 T2's POST-body byte counter when reading
                    // for "T2 byte-budget class survived Reality" on
                    // Tele2 LTE (~5 KB cumulative onset signal).
                    val payloadBytes = payload.encodeToByteArray().size
                    cumulativeBytes += payloadBytes
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
                                "reason=enqueue_returned_false " +
                                "cumulative_bytes=$cumulativeBytes",
                        )
                        // §14 hard gate 9 fail-fast (PR-G2 v2 fixup per
                        // Vladislav P2(b) review): completing the
                        // `completion` deferred terminates `runOneSession`,
                        // which cancels the heartbeat coroutine via finally
                        // AND closes the WS via close(1000)→bounded→cancel.
                        completion.complete("heartbeat_send_failed_enqueue")
                        break
                    }
                    listener.echoSent.set(seq)
                    Log.i(
                        TAG,
                        "RC_DIRECT_ARM_G_echo_sent s=$sessionEpoch seq=$seq " +
                            "client_ms=$clientMs mono_ms=$monoMs " +
                            "payload_bytes=$payloadBytes cumulative_bytes=$cumulativeBytes",
                    )

                    delay(HEARTBEAT_INTERVAL_MS)

                    // v7 amendment per architect: after the delay, check
                    // whether the seq we sent ONE FULL INTERVAL AGO came
                    // back (the listener removes from sendTimeMap on
                    // echo receipt). At this point seq sent 30 s ago
                    // (= 2 × HEARTBEAT_INTERVAL_MS) has had a full
                    // ECHO_TIMEOUT_MS window to round-trip. If still
                    // pending, count as missed. consecutiveMisses tracks
                    // the contiguous tail of most-recent missed seqs.
                    // First miss in a session emits `silence_onset` with
                    // cumulative_bytes anchor; an echo that comes back
                    // later resets the counter and emits
                    // `silence_broken` so the analysis grep can tell
                    // transient from permanent silence.
                    if (seq >= 2) {
                        val seqToCheck = seq - 1
                        val checkNowMs = System.currentTimeMillis()
                        val pendingState = synchronized(sendTimeMap) {
                            val sentAt = sendTimeMap[seqToCheck]
                            if (sentAt == null) {
                                "received"
                            } else if (checkNowMs - sentAt >= ECHO_TIMEOUT_MS) {
                                "missed"
                            } else {
                                "in_flight"
                            }
                        }
                        when (pendingState) {
                            "received" -> {
                                if (consecutiveMisses > 0) {
                                    Log.i(
                                        TAG,
                                        "RC_DIRECT_ARM_G_silence_broken s=$sessionEpoch " +
                                            "seq=$seqToCheck after_misses=$consecutiveMisses " +
                                            "mono_ms=${checkNowMs - sessionStartMonoMs}",
                                    )
                                }
                                consecutiveMisses = 0
                            }
                            "missed" -> {
                                consecutiveMisses++
                                if (consecutiveMisses == 1 && !silenceOnsetLogged) {
                                    silenceOnsetLogged = true
                                    Log.w(
                                        TAG,
                                        "RC_DIRECT_ARM_G_silence_onset s=$sessionEpoch " +
                                            "seq=$seqToCheck " +
                                            "cumulative_bytes=$cumulativeBytes " +
                                            "wall_clock_ms=$checkNowMs " +
                                            "mono_ms=${checkNowMs - sessionStartMonoMs}",
                                    )
                                }
                                if (consecutiveMisses >= MAX_CONSECUTIVE_MISSES) {
                                    Log.w(
                                        TAG,
                                        "RC_DIRECT_ARM_G_v7_eight_consecutive_misses " +
                                            "s=$sessionEpoch last_seq_sent=$seq " +
                                            "last_seq_missed=$seqToCheck " +
                                            "cumulative_bytes=$cumulativeBytes " +
                                            "mono_ms=${checkNowMs - sessionStartMonoMs}",
                                    )
                                    completion.complete("v7_eight_consecutive_misses")
                                    break
                                }
                            }
                            "in_flight" -> {
                                // Within ECHO_TIMEOUT_MS — do not count
                                // as miss yet. Will be re-checked next
                                // iteration when (seq - 1) becomes
                                // (newSeq - 1) one cycle later.
                            }
                        }
                    }
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
            // v7 amendment per architect 2026-06-06: bounded clean
            // Close before cancel. `ws.close(1000, "...")` queues a
            // Close frame to relay so the server-side session_summary
            // line emits promptly. After `WS_CLOSE_HANDSHAKE_BOUND_MS`,
            // force teardown via `ws.cancel()` so a half-open dead
            // socket (relay never receives the Close frame) cannot
            // hang the reconnect loop. Architect note (b): cancel() →
            // close() moves the terminal callback from `onFailure` to
            // `onClosing/onClosed`; the listener already completes the
            // `completion` deferred from both paths so the await above
            // returns either way.
            runCatching {
                ws.close(1000, "v7_session_end")
            }.onFailure { t ->
                Log.d(
                    TAG,
                    "RC_DIRECT_ARM_G_ws_close_in_finally_threw s=$sessionEpoch " +
                        "t=${t::class.simpleName}",
                )
            }
            kotlinx.coroutines.delay(WS_CLOSE_HANDSHAKE_BOUND_MS)
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

    /**
     * v8 amendment 2026-06-08: SOCKS-proxied `/health` preflight. Runs
     * BEFORE the signed-challenge call on every reconnect loop iteration
     * using the same [client] (same SOCKS proxy on the current session's
     * `socksPort`, same `callTimeout=10s`, same [ArmGPhaseListener]).
     * Result is logged via `RC_DIRECT_ARM_G_preflight_result` (200 OK)
     * or `RC_DIRECT_ARM_G_preflight_threw` (timeout / IOException) but
     * NEVER short-circuits the caller — the session continues to the
     * challenge phase regardless so the operator gets both signals per
     * session and can read the matrix described in [runOneSession].
     *
     * Why `/health` specifically: relay returns a fixed
     * `{"status":"ok"}` body with no auth required, so the success path
     * is the cheapest possible probe of the SOCKS → Reality → relay
     * pipe. Failure modes map cleanly to discriminator outcomes:
     *   - `200 OK + elapsed_ms < 5000` → SOCKS-Reality-relay pipe
     *     works, challenge failure (if it follows) is challenge-
     *     specific.
     *   - `200 OK + elapsed_ms ≥ 8000` → callTimeout=10s is too tight
     *     for Tecno-side Reality handshake latency; preflight winning
     *     by a hair foreshadows challenge losing by a similar margin.
     *   - `IOException / timeout` → SOCKS-Reality path itself is
     *     broken on Tecno (refutes the WSL2-Linux-Xray equivalence
     *     inference; opens libXray-gomobile-specific investigation).
     *
     * Tag `"preflight"` is read by [ArmGPhaseListener] from
     * `request.tag(String::class.java)` so the per-phase event lines
     * carry `op=preflight` next to the wall-clock-elapsed
     * `RC_DIRECT_ARM_G_preflight_*` lines.
     */
    private suspend fun runHealthPreflight(
        client: OkHttpClient,
        sessionEpoch: Long,
    ) {
        val httpScheme = when {
            relayUrl.startsWith("wss://") -> "https://"
            relayUrl.startsWith("ws://")  -> "http://"
            else                          -> "https://"
        }
        val hostAndPath = relayUrl.removePrefix("wss://").removePrefix("ws://")
        val hostOnly = hostAndPath.substringBefore("/")
        val healthUrl = "$httpScheme$hostOnly/health"
        val started = System.currentTimeMillis()
        Log.i(
            TAG,
            "RC_DIRECT_ARM_G_preflight_started s=$sessionEpoch " +
                "url=$healthUrl call_timeout_ms=10000",
        )
        val req = Request.Builder()
            .url(healthUrl)
            .tag(String::class.java, "preflight")
            .build()
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(req).execute().use { resp ->
                    val elapsed = System.currentTimeMillis() - started
                    Log.i(
                        TAG,
                        "RC_DIRECT_ARM_G_preflight_result s=$sessionEpoch " +
                            "status=${resp.code} elapsed_ms=$elapsed",
                    )
                }
            }.onFailure { t ->
                val elapsed = System.currentTimeMillis() - started
                Log.w(
                    TAG,
                    "RC_DIRECT_ARM_G_preflight_threw s=$sessionEpoch " +
                        "t=${t::class.simpleName} " +
                        "msg=${t.message?.take(200)} " +
                        "elapsed_ms=$elapsed",
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

        val challengeReq = Request.Builder()
            .url(challengeUrl)
            .tag(String::class.java, "challenge")
            .build()
        // v8 amendment 2026-06-08: log immediately before the blocking
        // `.execute()` call so the operator can distinguish "challenge
        // never started" (no about_to_call line) from "challenge started
        // but timed out" (about_to_call present, threw line follows
        // ~10s later). The previous v7 evidence was ambiguous on this
        // axis because the only challenge line was the terminal
        // `challenge_threw` — we did not know whether the request had
        // actually left the device.
        Log.i(
            TAG,
            "RC_DIRECT_ARM_G_challenge_about_to_call s=$sessionEpoch " +
                "url=$challengeUrl call_timeout_ms=10000",
        )
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
                // v7 amendment: `mono_ms` = sinceOpenMs (same anchor —
                // openAtMs is when the WS opened; the heartbeat sender's
                // `sessionStartMonoMs` is the moment after
                // `openedAt.await()` returns, which is the same instant
                // up to coroutine scheduling jitter). Provided as an
                // explicit field on this log line so the analysis grep
                // can correlate echo_received with the echo_sent's
                // `mono_ms` field without subtracting openAtMs.
                Log.i(
                    TAG,
                    "RC_DIRECT_ARM_G_echo_received s=$sessionEpoch " +
                        "seq=$seq " +
                        "rtt_ms=$rttMs " +
                        "since_open_ms=$sinceOpenMs " +
                        "mono_ms=$sinceOpenMs " +
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

    /**
     * v8 amendment 2026-06-08: per-session OkHttp [okhttp3.EventListener]
     * that surfaces the wall-clock latency of each HTTP-layer phase for
     * preflight + challenge calls on the SOCKS-Reality pipe. Reads the
     * per-call request tag (`request.tag(String::class.java)`) so the
     * lines carry `op=preflight` / `op=challenge`; calls without the
     * tag (e.g. the WebSocket open) are labelled `op=untagged` and can
     * be ignored — Arm G already emits `RC_DIRECT_ARM_G_ws_open` from
     * the WebSocket listener.
     *
     * Why only a few phases: the goal is to localise where in the
     * 10-second `callTimeout` window the call dies, not to instrument
     * every internal step. `connectStart` / `secureConnectStart` /
     * `secureConnectEnd` / `responseHeadersStart` / `callEnd` /
     * `callFailed` are the six phase boundaries that map cleanly to
     * SOCKS connect, TLS-through-Reality handshake, and response wait.
     * DNS phases are skipped because OkHttp with a SOCKS proxy still
     * resolves the URL host locally (so DNS latency is felix-side and
     * already covered by other diagnostics) and would just add noise.
     *
     * Level=INFO so the operator can `findstr / grep` for it; under
     * the v8 Xray `loglevel=debug` flag the logcat is verbose anyway.
     */
    private inner class ArmGPhaseListener(
        private val sessionEpoch: Long,
    ) : okhttp3.EventListener() {
        private fun op(call: okhttp3.Call): String =
            call.request().tag(String::class.java) ?: "untagged"

        override fun connectStart(
            call: okhttp3.Call,
            inetSocketAddress: java.net.InetSocketAddress,
            proxy: java.net.Proxy,
        ) {
            Log.i(
                TAG,
                "RC_DIRECT_ARM_G_phase s=$sessionEpoch op=${op(call)} " +
                    "phase=connectStart addr=$inetSocketAddress proxy=$proxy",
            )
        }

        override fun secureConnectStart(call: okhttp3.Call) {
            Log.i(
                TAG,
                "RC_DIRECT_ARM_G_phase s=$sessionEpoch op=${op(call)} " +
                    "phase=secureConnectStart",
            )
        }

        override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) {
            Log.i(
                TAG,
                "RC_DIRECT_ARM_G_phase s=$sessionEpoch op=${op(call)} " +
                    "phase=secureConnectEnd " +
                    "tlsVersion=${handshake?.tlsVersion} " +
                    "cipherSuite=${handshake?.cipherSuite}",
            )
        }

        override fun responseHeadersStart(call: okhttp3.Call) {
            Log.i(
                TAG,
                "RC_DIRECT_ARM_G_phase s=$sessionEpoch op=${op(call)} " +
                    "phase=responseHeadersStart",
            )
        }

        override fun callEnd(call: okhttp3.Call) {
            Log.i(
                TAG,
                "RC_DIRECT_ARM_G_phase s=$sessionEpoch op=${op(call)} " +
                    "phase=callEnd",
            )
        }

        override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) {
            Log.w(
                TAG,
                "RC_DIRECT_ARM_G_phase s=$sessionEpoch op=${op(call)} " +
                    "phase=callFailed t=${ioe::class.simpleName} " +
                    "msg=${ioe.message?.take(160)}",
            )
        }
    }

    companion object {
        private const val TAG = "RC_DIRECT_ARM_G"
        private val NONCE_REGEX = Regex("\"nonce_hex\"\\s*:\\s*\"([a-fA-F0-9]+)\"")
        private const val OUTCOME_AUTH_ABORTED = "auth_aborted"

        // v8 amendment 2026-06-08: cap consecutive auth-aborted sessions so
        // a chronic SOCKS/Reality path failure does not spin the reconnect
        // loop indefinitely (the v7 Wi-Fi sanity run produced 20+ identical
        // `challenge_threw timeout` sessions in 6 minutes, drowning logcat
        // and burning device battery without new information). After
        // [MAX_CONSECUTIVE_AUTH_ABORTED] consecutive auth_aborted outcomes,
        // the diagnostic terminates the reconnect loop with outcome
        // [OUTCOME_AUTH_ABORT_CAP_EXCEEDED] so the operator can read the
        // existing evidence cleanly and trigger a code change rather than
        // wait for the user to force-stop.
        const val MAX_CONSECUTIVE_AUTH_ABORTED: Int = 5
        const val OUTCOME_AUTH_ABORT_CAP_EXCEEDED: String = "auth_abort_cap_exceeded"

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

        // ── v7 amended plan 2026-06-06 (architect ACK after v6 Wi-Fi PARTIAL evidence) ──

        // Wall-clock cap per session. v6 reconnect-loop had no per-session
        // wall-clock cap. v7 spec point 4: "End session at 15 min wall-clock
        // OR 8 consecutive missing echoes." Whichever fires first.
        const val SESSION_WALL_CLOCK_CAP_MS: Long = 15 * 60 * 1000L

        // Maximum consecutive missing echoes before terminating a session.
        // v7 spec point 4. Eight missed × 15-sec heartbeat ≈ 2 min of one-way
        // silence → classify as permanent one-way and end session so the
        // reconnect-loop can move on.
        const val MAX_CONSECUTIVE_MISSES: Int = 8

        // Echo timeout per seq. After sending seq=N, if seq=N has not been
        // observed in the listener's echoReceived path within this window,
        // count it as missed. 2× HEARTBEAT_INTERVAL_MS covers normal RTT
        // variance (Stage 5E worst-case observed ~5 s; Reality + Tele2 LTE
        // may be slower); shorter would risk false misses on slow networks.
        const val ECHO_TIMEOUT_MS: Long = HEARTBEAT_INTERVAL_MS * 2

        // Bounded wait between `ws.close(1000, "...")` and `ws.cancel()`
        // in [stop] (and in `runOneSession.finally`). v7 spec point 9 (and
        // architect amendment): close gives relay a chance to receive the
        // Close frame and emit `session_summary` promptly, but a half-open
        // dead socket will never complete the close handshake — cancel()
        // forces teardown so stop() does not hang.
        const val WS_CLOSE_HANDSHAKE_BOUND_MS: Long = 2_500L

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
