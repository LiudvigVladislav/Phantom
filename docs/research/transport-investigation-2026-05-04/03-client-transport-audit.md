# Client transport audit — 2026-05-04

## Summary

The client-side WebSocket configuration is mostly stock-Ktor + stock-OkHttp
with three deliberate deviations: (1) HTTP/1.1 forced for the upgrade path,
(2) all transport-level pings disabled, (3) per-reconnect-generation
HttpClient lifecycle (ADR-010 fix). There is **no obvious DPI-bait
misconfiguration** — no exotic headers, no compression, no permessage-deflate,
no custom TLS — but the configuration is also **missing every mobile-network
hardening setting** that a production-grade messenger would set: no TCP
keepalive on the socket, no explicit `connectTimeout`, no `writeTimeout`, no
SO_KEEPALIVE, no SNI override (relies on OkHttp default which IS correct for
`relay.phntm.pro`), and the 3-second app-level ping is unusual enough that
it could plausibly trip a CGN/DPI heuristic that flags "rapid small frames
on a long-lived TLS connection". The 50-60s reset cadence on non-VPN networks
is more consistent with an upstream stateful timeout (CGN/middlebox) than
with a client-side bug, but two client choices make the situation fragile:
(a) we do not set TCP keepalive on the socket so the kernel stack will not
help us survive a NAT idle, and (b) the 3 s app-level ping replaced 10 s
without any A/B comparison and may itself be the heuristic trigger that
caused regression on stock-emulator (Test 1 in the 4-test matrix).

## Current configuration map

All paths relative to repo root `d:\VL Stories Studio\Phantom\`.

| Setting | Value | File:line | Comment |
|---|---|---|---|
| App-level Ping interval | `3_000 ms` | `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/RelayTransportConfig.kt:16` | Was 10 s. Reduced 2026-05-03 as Wi-Fi-radio-parking experiment. Unverified whether 3 s is the cause of stock-emulator regression observed in 4-test matrix. |
| Pong timeout | `60_000 ms` | `RelayTransportConfig.kt:24` | Tolerance for app-level silence before forced reconnect. |
| ACK timeout | `60_000 ms` | `RelayTransportConfig.kt:33` | Bumped from 15 s for slow-uplink large envelopes. |
| ACK watchdog scan | `5_000 ms` | `RelayTransportConfig.kt:37` | |
| Reconnect base delay | `1_000 ms` | `RelayTransportConfig.kt:45` | Halved from 2 s after QA. |
| Reconnect max delay | `30_000 ms` | `RelayTransportConfig.kt:49` | Exponential cap. |
| Reconnect attempts | `Infinite` | `RelayTransportConfig.kt:55` | |
| OkHttp `pingInterval` | `0 SECONDS` (disabled) | `shared/core/transport/src/androidMain/kotlin/phantom/core/transport/RelayTransportFactory.kt:30` | Correctly disabled — app-level Ping/Pong replaces it. |
| OkHttp `readTimeout` | `60 SECONDS` | `RelayTransportFactory.kt:36` | OS backstop. |
| OkHttp `writeTimeout` | NOT SET (default 10 s) | n/a | Default OkHttp is 10 s. |
| OkHttp `connectTimeout` | NOT SET (default 10 s) | n/a | Default OkHttp is 10 s. |
| OkHttp `callTimeout` | NOT SET (default 0 = none) | n/a | Default; correct. |
| OkHttp `protocols` | `[HTTP_1_1]` only | `RelayTransportFactory.kt:42` | ALPN locks h2 out — intentional per ADR-010. |
| OkHttp `connectionPool` | DEFAULT (`5 max idle, 5 min keepalive`) | n/a | Default; mostly irrelevant since we recreate the client per reconnect. |
| OkHttp `dispatcher` | DEFAULT | n/a | Default; we destroy it via `shutdownNow()` on pong timeout. |
| OkHttp `socketFactory` | NOT SET (default) | n/a | No `SO_KEEPALIVE`, no `TCP_NODELAY`, no `SO_LINGER` — all kernel defaults. |
| OkHttp `sslSocketFactory` | NOT SET (default Conscrypt) | n/a | Whatever Android's default Conscrypt picks. SNI is the destination hostname `relay.phntm.pro` automatically. |
| OkHttp `connectionSpecs` | NOT SET (default `MODERN_TLS, CLEARTEXT`) | n/a | Default supports TLS 1.3 + TLS 1.2 with modern cipher suites only. |
| OkHttp `interceptors` | NONE | n/a | No User-Agent override, no logging interceptor, no retry interceptor. |
| Ktor WebSockets `pingIntervalMillis` | `0L` (disabled) | `RelayTransportFactory.kt:56` | Correctly disabled — app-level handles liveness. |
| Ktor WebSockets `maxFrameSize` | NOT SET (default `Long.MAX_VALUE`) | n/a | No frame-size cap. |
| Ktor WebSockets `extensions` | NOT INSTALLED | n/a | **No permessage-deflate.** Frames go on the wire as plain UTF-8 text without compression. |
| Connect URL pattern | `wss://relay.phntm.pro/ws?id=<hex>&token=<token>` | `KtorRelayTransport.kt:171-172` | Identity in query string is fine for `?id=` registration; no other unusual headers. |
| WebSocket subprotocol | NONE declared | n/a | No `Sec-WebSocket-Protocol` requested. Server does not require one. |
| Origin header | NOT SET | n/a | OkHttp does not send `Origin:` for non-browser clients. Acceptable; most middleboxes do not gate on Origin for `wss://`. |
| User-Agent | DEFAULT (`okhttp/<version>`) | n/a | **Generic OkHttp UA.** A small fraction of CGN heuristics fingerprint on `okhttp/...` — not common, but real. |
| AlarmManager wakeup | every 30 s, `setExactAndAllowWhileIdle` | `apps/android/src/androidMain/kotlin/phantom/android/service/PhantomWakeupReceiver.kt` | Per ADR-011. |
| `forceReconnect` strategy | Abandon stuck loop, launch fresh `runReconnectLoop` on `transportScope` | `KtorRelayTransport.kt:603-641` | Per ADR-013. Old loop becomes a zombie thread until kernel releases recv. |

## Known-bad patterns found (if any)

1. **No SO_KEEPALIVE on the socket.** Linux kernel default
   `tcp_keepalive_time` is **7200 seconds** (2 hours). On Android the
   default is similar. This means the kernel does **nothing** to keep the
   TCP entry warm at intermediate NATs. Without SO_KEEPALIVE we are entirely
   dependent on application-level pings to keep the path alive. If a CGN or
   carrier-grade NAT has a 60–90 s idle timeout for stateful TCP entries
   (typical for mobile carriers), and our application-level Pong frame is
   for any reason not making the round-trip, the NAT entry expires and the
   first packet from either side after expiry triggers a RST. This is
   exactly the symptom described in the brief.

2. **3-second app-level ping is unusual.** Industry baseline for
   long-lived WebSocket apps:
   - Signal: 30 s app ping over the encrypted channel.
   - WhatsApp: ~60 s, actively tuned per-network.
   - Element/Matrix: 30 s default.
   - Slack: 10–15 s.
   A 3-second cadence is below what CGNs commonly expect. Some commercial
   "carrier optimisation" middleboxes specifically target frequent small
   frames on long-lived encrypted connections as "mis-behaving streaming
   apps" or as "tunnel-style traffic" and may rate-limit or reset them.
   This is plausible but not proven; would need packet-capture confirmation
   from the relay side to know for sure.

3. **Default OkHttp `User-Agent`.** OkHttp sets `User-Agent: okhttp/4.x` by
   default during the WS upgrade HTTP request. A handful of DPI fingerprints
   flag `okhttp/*` UAs as "non-browser, possibly automated" and apply
   different policies. Negligible signal but free to fix — set a benign
   UA like `Mozilla/5.0 (Linux; Android 12; PHANTOM/0.1)` in an
   interceptor.

4. **`writeTimeout` defaults to 10 s.** OkHttp's default `writeTimeout` is
   10 seconds. For an 8 KB voice chunk on slow uplink (Tecno on 3G, EDGE
   fallback) this can fire before the chunk reaches the wire, producing
   a `SocketTimeoutException` mid-write. The `readTimeout` was bumped to
   60 s but `writeTimeout` was not. Asymmetric timeout is a footgun.

5. **No `connectionSpec` pinning.** OkHttp's default `MODERN_TLS` is fine
   today, but it includes a fallback to `COMPATIBLE_TLS` (TLS 1.0/1.1)
   that some old middleboxes prefer. We should pin to `RESTRICTED_TLS` or
   `[MODERN_TLS]` explicitly to ensure we never downgrade. (Probably not
   the cause of the current symptom; mentioning for completeness.)

## TCP/OS-level settings

We set NONE. Every TCP socket option used by the WebSocket is whatever
Android's kernel defaults to:

- `SO_KEEPALIVE`: OFF by default. We do not enable it.
- `TCP_KEEPIDLE`: 7200 s (would only matter if we enabled SO_KEEPALIVE).
- `TCP_KEEPINTVL`: 75 s.
- `TCP_KEEPCNT`: 9.
- `TCP_NODELAY`: ON by default in modern OkHttp (yes, this one is fine).
- `SO_LINGER`: OFF by default (correct).
- `SO_RCVBUF` / `SO_SNDBUF`: kernel auto-tuned (correct).

OkHttp does not expose a public API for setting `SO_KEEPALIVE` on the
WebSocket socket. To do this you have to plug in a custom
`SocketFactory` that sets the option before the socket is connected, or
use the (private) `Internal.instance` reflection trick. A third option is
to wrap the SSL socket factory and call `setKeepAlive(true)` on the
underlying socket. None of these are particularly clean, but if NAT idle
is the cause they are necessary.

For comparison: VPN clients (OpenVPN, WireGuard) set their own KEEPALIVE
at the protocol level — typically 5-30 s. This is why the user's working
VPN test (Test 4) is stable: the VPN's own keepalive packets reset the
NAT entry on every layer, even if our application pings are not making
the round-trip cleanly.

## TLS/HTTPS settings

Nothing custom. We rely on Android's default `SSLSocketFactory`, which on
Android 8+ is Conscrypt. Conscrypt picks TLS 1.3 if both peers support it,
otherwise TLS 1.2.

- **SNI**: OkHttp passes the destination hostname (`relay.phntm.pro`) as
  SNI automatically. No SNI override is configured. Correct.
- **ALPN**: We pin `[HTTP_1_1]` so the ALPN extension advertises only
  `http/1.1`. Correct for our use case (avoids h2 multiplexing
  complications on the WebSocket upgrade — see ADR-010).
- **Cipher suites**: Whatever Conscrypt's default `MODERN_TLS` set is.
  No restriction or reorder.
- **Certificate validation**: System trust store. No custom pinning, no
  `Network Security Config` override (need to confirm — could be a
  separate XML resource).
- **Session tickets / 0-RTT**: Conscrypt default. Probably enabled.
  Should not be load-bearing for the symptom.

The relay terminates TLS at Caddy, which is itself well-configured (per
the agent A audit, presumably). No TLS-level oddity is visible from the
client.

## WebSocket protocol details

The HTTP upgrade request will be (approximately):

```
GET /ws?id=<hex>&token=<token> HTTP/1.1
Host: relay.phntm.pro
Connection: Upgrade
Upgrade: websocket
Sec-WebSocket-Key: <random base64>
Sec-WebSocket-Version: 13
User-Agent: okhttp/4.x
Accept: */*
```

Notable absences (all of which are normal but worth listing):

- **No `Sec-WebSocket-Protocol`** — we do not request a subprotocol. The
  server (`routes.rs`) does not require one. Fine.
- **No `Sec-WebSocket-Extensions: permessage-deflate`** — Ktor's default
  WebSockets plugin does not install permessage-deflate. The client
  therefore neither offers nor accepts compression. Frames go on the
  wire as plain text bytes. Good for middlebox compatibility (some old
  middleboxes mis-handle the deflate extension and corrupt the stream).
- **No `Origin`** — non-browser clients commonly omit this. Servers
  generally do not require it for `wss://` from native apps.
- **No custom token handling** — the auth token is in the query string
  rather than an `Authorization:` header. This is unusual for production
  apps (usually `Authorization: Bearer <token>` is preferred so it does
  not appear in proxy access logs). For our setup this is acceptable but
  worth noting — some middleboxes log the entire URL line, including the
  token. Not a reset cause.

After upgrade, we send only `Frame.Text(<json>)` — no binary frames, no
ping/pong control frames at the WebSocket protocol level (since
`pingInterval(0)` disables them). The application-level Ping is itself a
text frame containing JSON `{"type":"ping"}` plus pong response.

## Reconnect strategy review

Per ADR-013, `forceReconnect` no longer waits for the stuck `webSocket{}`
block to exit; it abandons the old reconnect job and launches a fresh
`runReconnectLoop` on `transportScope`, leaving the old reader thread as
a zombie that the kernel will release eventually. This was the right fix
for the Tecno HiOS Wi-Fi parking case, but three issues remain:

1. **Zombie threads accumulate.** Each `forceReconnect` that hits a
   parked-radio scenario leaves an OkHttp dispatcher thread blocked in
   kernel `recv()`. They do release eventually (when the kernel times the
   socket out at ~7-30 minutes typically), but if `forceReconnect` is
   called rapidly (e.g. AlarmManager fires every 30 s while the radio
   stays parked for 5 min), 10 zombie threads can accumulate. Each
   carries an OkHttp engine (~5 MB heap) and a dispatcher pool. This is
   not the cause of the observed symptom but is a memory pressure source
   on long sessions.

2. **`forceReconnect` does NOT call `forceShutdownActiveEngine` on the
   abandoned generation's client.** Reading lines 622-624: it does call
   `forceShutdownActiveEngine()` and `currentGenerationClient?.close()`,
   then cancels the scope, then launches a new loop. Good — but
   `forceShutdownActiveEngine` shutdown the engine of `activeOkHttp`,
   which by design is the single tracked engine; if multiple zombie
   loops have accumulated, only the most recent one is tracked, and the
   earlier zombies' engines were tracked-then-overwritten when the next
   generation client was created. So the earlier zombies' kernel reads
   are NOT interrupted — they wait for the kernel timeout. ADR-013
   acknowledges this; mentioning for completeness.

3. **`scope?.cancel()` at line 625 (forceReconnect) cancels the OLD
   generation's scope.** If a brand-new generation has just started
   between the time `forceReconnect` was scheduled and now, this could
   cancel the new one's pingJob. Tight timing window; probably not a
   real issue but worth a code review.

## Best-practice gaps

What we are missing that production messenger apps usually have:

1. **TCP keepalive at the socket level** (SO_KEEPALIVE, TCP_KEEPIDLE
   30-60 s). Industry standard for mobile.
2. **Explicit `writeTimeout`** matched to the largest expected upload
   chunk on the slowest expected uplink. With 8 KB chunks on EDGE
   (~30 KB/s), 60 s is comfortable; the default 10 s could fire
   spuriously.
3. **A retry / backoff jitter.** Our backoff is purely exponential
   (`1s, 2s, 4s, 8s, 16s, 30s, 30s...`). Industry uses
   "exponential + jitter" so that a fleet-wide outage does not produce
   thundering-herd reconnect storms when the relay comes back. Not the
   cause of the symptom but a nice-to-have.
4. **Connection state telemetry.** No `ConnectivityManager.NetworkCallback`
   wired up to proactively reconnect on network transitions (Wi-Fi to
   LTE roaming, etc.). ADR-010 mentioned this as an Alpha-2 hardening
   item; still missing.
5. **NetworkSecurityConfig with an explicit cleartext policy and pinning
   for `relay.phntm.pro`.** Currently we rely on the system trust store.
   For a production messenger app, we should pin the relay's certificate
   chain.
6. **A user-visible "diagnostic" mode** that surfaces ping RTT, last pong
   age, current backoff attempt. Would have made the current
   investigation trivially easy. Worth doing for Alpha-2 QA.

## Concrete fix candidates (ranked)

### 1. Set `SO_KEEPALIVE` with `TCP_KEEPIDLE = 30 s` via custom SocketFactory.

Rationale: this is the single most likely real cause of the 50-60 s
reset cadence on non-VPN networks. CGN/middlebox idle timeouts are
typically 60-300 s. Without SO_KEEPALIVE, the kernel does nothing to
defend the TCP entry; only our app-level Pong does. If even one Pong
round trip fails (lost UDP keepalive on a NAT64 path, brief radio gap,
DPI buffering jitter), the entry expires and the next outbound or
inbound segment triggers a RST from the middlebox.

Implementation sketch:

```kotlin
private class KeepAliveSocketFactory(
    private val delegate: SocketFactory = SocketFactory.getDefault()
) : SocketFactory() {
    override fun createSocket() = delegate.createSocket().apply { configure() }
    override fun createSocket(host: String?, port: Int) =
        delegate.createSocket(host, port).apply { configure() }
    // ... other createSocket overloads
    private fun Socket.configure() {
        keepAlive = true
        // Android does not expose TCP_KEEPIDLE via Socket API; need NDK.
        // Alternative: rely on default 7200 s and use app-level ping for
        // the short-cycle case. Even just SO_KEEPALIVE gives the kernel
        // permission to send keepalives.
    }
}
```

Note: **plain `Socket.setKeepAlive(true)` only enables the option; the
intervals come from the global kernel setting (`tcp_keepalive_time`
which is 7200 s on Android).** To override the interval per socket you
need either NDK `setsockopt(TCP_KEEPIDLE, ...)` or upgrade to API 24+
`SocketOptions.SO_KEEPALIVE` plus newer extension constants. There is
a Conscrypt-specific path and an OkHttp internals path; both need a bit
of glue. **However:** on aggressive-OEM Android devices we often need
sub-30-s keepalives; the global default of 7200 s is useless for our
case. So this fix needs the NDK setsockopt or a clever workaround.

Diagnostic value: if we set SO_KEEPALIVE=true with default intervals and
the symptom disappears, NAT idle was the cause. If it persists with
SO_KEEPALIVE on, NAT idle is ruled out.

### 2. Restore `PING_INTERVAL_MS` to 10 s and capture pcap on relay during a non-VPN session.

Rationale: the 4-test matrix explicitly notes the 3 s ping is an
**unverified experiment** (from comment at `RelayTransportConfig.kt:9`)
and the README notes it caused regression on stock-emulator. Reverting
isolates the variable. Best-practice industry baseline is 10-30 s. If
the 50-60 s cadence persists after reverting, the 3 s ping was not the
cause; if it changes (better or worse), we have a definitive signal.

This is the **cheapest and fastest experiment** of all the candidates.

### 3. Set explicit `writeTimeout(60, SECONDS)` on the OkHttp builder.

Rationale: voice-chunk writes on slow uplinks could fire the default 10 s
write timeout, producing a pseudo-`SocketTimeoutException` that looks
like a server reset in the logs but is actually a client-side abort.
The relay would then see the client close the connection. Asymmetric
timeouts are a footgun; align them.

```kotlin
.readTimeout(60, TimeUnit.SECONDS)
.writeTimeout(60, TimeUnit.SECONDS)
.connectTimeout(15, TimeUnit.SECONDS)
```

### 4. Set a non-default `User-Agent` via an interceptor.

Rationale: low-probability fix. A small fraction of "carrier
optimisation" middleboxes apply different policies to `okhttp/*` UA
strings. Setting `Mozilla/5.0 (Linux; Android <ver>; PHANTOM/<ver>)` is
free and removes one variable.

```kotlin
.addInterceptor { chain ->
    chain.proceed(
        chain.request().newBuilder()
            .header("User-Agent", "PHANTOM/0.1 (Android)")
            .build()
    )
}
```

### 5. Add an Origin header.

Rationale: even lower probability. Some servers/middleboxes treat
missing `Origin:` differently from `Origin: https://relay.phntm.pro`.
Free to set; should not change anything but eliminates one heuristic.

### 6. Investigate switching to OkHttp's HTTP/2 upgrade with extended CONNECT.

Rationale: might or might not help — h2 has stream multiplexing and a
single TCP entry, which is more middlebox-friendly per RFC 8441 in some
deployments and less so in others. ADR-010 explicitly chose HTTP/1.1 to
make connection lifecycle reasoning easier. Reverting that choice is
NOT recommended without first verifying the simpler hypotheses (1-3).

### 7. Implement variable ping cadence (3 s for first 30 s, 30 s steady-state).

Rationale: if the 3 s ping was effective at unsticking initial radio
parking but is now triggering middlebox reset, a hybrid scheme could
preserve both benefits. Not free to implement; only worth doing after
diagnostics confirm "fast ping is good for first connection, bad after
it stabilises".

## Things to test

In order of "likely to give us new information per hour of effort":

1. **Revert PING_INTERVAL_MS to 10 s, build APK, run the 4-test matrix
   again** — ~30 minutes. If the non-VPN reset cadence changes, the 3 s
   ping is the cause and we can either revert permanently or design a
   variable-cadence scheme.

2. **Set `SO_KEEPALIVE = true` via custom SocketFactory (default
   intervals, no NDK), build APK, run Test 1 (Tecno, no VPN) for 5
   minutes** — ~1 hour. The default kernel interval of 7200 s means the
   first kernel keepalive will not fire for 2 hours, so this test
   alone won't change behavior. **Skip ahead** to also setting a custom
   SocketFactory that calls NDK `setsockopt(IPPROTO_TCP, TCP_KEEPIDLE, 30)`.
   That requires JNI plumbing. Total: 2-4 hours of work.
   Diagnostic value: highest. Confirms or rules out NAT-idle hypothesis
   definitively.

3. **Add `writeTimeout(60s)` and `connectTimeout(15s)`** — 5 minutes.
   Cheap to do. Will not fix the reset cycle but will rule out a
   side hypothesis (silent write timeout).

4. **Capture pcap on the relay during a Test 1 run** — coordinate with
   server-side investigation. Look for: (a) is the FIN/RST coming from
   the client or from upstream of the relay? If a third-party RST with
   a shorter TTL than the relay's IP arrives at the relay, it's a
   middlebox. If the client sends FIN cleanly, it's a client-side abort.
   The current Logcat stack trace points to the middlebox direction
   ("Connection reset by peer" deep in
   `Conscrypt SSLInputStream.socketRead`), but pcap will confirm.

5. **Test with `Origin: https://relay.phntm.pro` set** — 10 minutes.
   Free to do; rules out one heuristic.

6. **Test from a clean Android emulator on the **same** non-VPN network**
   — 30 minutes. The 4-test matrix already shows the emulator is
   affected at ~50 s on no-VPN, which is consistent with the relay /
   middlebox hypothesis rather than a Tecno-specific firmware issue.
   Confirm with one more run and compare RST cadence between Tecno
   (60 s) and emulator (50 s) — if they're close, it's environment;
   if they differ by more than 30 s, device-specific timing matters.

7. **As a last resort, swap OkHttp for OkHttp's `Sec-WebSocket-Extensions:
   permessage-deflate` extension** — should NOT help and might hurt;
   only mention this for completeness as a thing NOT to try yet.
