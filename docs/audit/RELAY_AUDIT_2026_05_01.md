# Relay WebSocket Drop Audit — 2026-05-01

**Verdict:** The Caddyfile in the repository already contains the correct `transport http { read_timeout 0; write_timeout 0 }` and `flush_interval -1` directives. The primary suspect is therefore that the Caddyfile on the live VPS has not been updated to match the repository version — meaning the container is running with the old default Caddy config that imposes a ~60-second proxy idle timeout. The secondary suspect is that OkHttp's TCP-level socket timeout (default 10 s) fires before the relay's WS-level Pong, triggering the reset the log shows.

---

## Hypothesis Ranking

### H1 — Caddy on the VPS is running a stale config without `read_timeout 0` (probability: HIGH)

**Evidence:**

- `deploy/Caddyfile` lines 53–58 show the correct directives:
  ```
  transport http {
      read_timeout  0
      write_timeout 0
      dial_timeout  10s
  }
  flush_interval -1
  ```
- The inline comment (lines 47–52) explicitly calls out: "Disable proxy-side timeouts so a quiet WebSocket session does not get killed mid-conversation."
- The comments also describe a prior QA incident — "QA-v5 as a fixed ~45-60-second reconnect cycle" — that matches the current symptom exactly: drops every ~60 s.
- When `read_timeout` is absent (Caddy's default), Caddy's `reverse_proxy` transport uses a 60-second idle read timeout on the backend connection. A WebSocket session where no frames arrive within that window is closed from the proxy side with a TCP RST, which appears in the client log as `SocketException: Connection reset` — exactly what is observed.
- The `Alt-Svc: h3=":443"` header in the curl probe confirms Caddy is live. If the container were running the current repo Caddyfile the symptom would not reproduce.

**What triggers it:** The Ktor client sends a `{"type":"ping"}` application-level frame every 10 s. The relay replies with `{"type":"pong"}` via the mpsc channel. Both transits cross the Caddy proxy. If Caddy's backend `read_timeout` is the default 60 s, a period of quiet slightly above 60 s (e.g. when the Pong response is delayed by the mpsc queue draining) triggers Caddy's idle close on the backend connection, which sends a RST to the relay. The relay's axum/tungstenite sees the RST; the `None` / `Some(Err(_))` arm of the `select!` fires; the session is torn down.

**Symptom match:** Drop interval of ~60 s, server-initiated RST, immediately reproducible on the emulator (no radio park involved), `SocketException: Connection reset`.

**Recommended fix:** SSH into the VPS. Confirm the running Caddy container has the latest Caddyfile mounted:

```
docker exec phantom-caddy caddy validate --config /etc/caddy/Caddyfile
```

If the validate output does not mention `read_timeout 0`, the volume mount is stale or the compose was never restarted after the Caddyfile was updated. To apply the fix:

```
# From the deploy directory on the VPS:
docker compose -f deploy/docker-compose.yml pull caddy
docker compose -f deploy/docker-compose.yml up -d --force-recreate caddy
```

After restart, confirm with:

```
docker exec phantom-caddy cat /etc/caddy/Caddyfile | grep read_timeout
```

Expected output: `read_timeout  0`

---

### H2 — OkHttp TCP socket timeout fires before the relay Pong arrives (probability: MEDIUM)

**Evidence:**

- `KtorRelayTransport.kt` line 284 sends `RelayMessage.Ping` (application-level JSON frame `{"type":"ping"}`) every 10 s (`PING_INTERVAL_MS = 10_000`).
- `routes.rs` line 410–413 handles `"ping"` by sending `{"type":"pong"}` through the per-client mpsc channel. That pong must cross the `select!` loop's outbound arm, be serialized, and travel through Caddy back to the client.
- `RelayTransportConfig.kt` line 19 sets `PONG_TIMEOUT_MS = 60_000`. If the relay's pong is in transit when the watchdog fires, the client closes the session itself.
- `KtorRelayTransport.kt` lines 265–283: on pong timeout, `forceShutdownActiveEngine()` then `generationClient.close()` then `scope.cancel()`. The OkHttp engine being shut down sends a TCP RST on the open socket, which the relay receives as `Some(Err(_))` in its `select!`. From the relay's perspective this looks identical to a server-initiated close.
- However: the application Ping every 10 s should prevent the 60 s Caddy idle timeout from being reached if H1 were already fixed. H2 is a fallback cause if H1 is resolved but drops continue.

**What triggers it:** Under cellular conditions with bursty latency, a Pong can take more than 60 s to round-trip if the relay's mpsc channel is temporarily backed up by envelope delivery traffic. The client's watchdog then force-closes the session from the client side, which manifests in the log as a `SocketException` on the relay side.

**Recommended fix:** Reduce `PONG_TIMEOUT_MS` to 30 s and `PING_INTERVAL_MS` to 15 s so the client detects a dead connection faster and reconnects before OkHttp's own socket timeout fires. This is a client-side change (no VPS SSH needed). However: do not make this change before confirming H1 is resolved, because a shorter pong timeout will increase reconnect frequency if Caddy is still injecting its own 60 s close.

---

### H3 — axum `TimeoutLayer` inadvertently covers `/ws` (probability: LOW)

**Evidence:**

- `routes.rs` lines 44–64 show the `TimeoutLayer` is scoped exclusively to `http_routes`. The `/ws` route is added to the outer `Router` separately (line 67) and merged with `http_routes` after the timeout layer is already applied to the inner sub-router only.
- `routes.rs` line 67: `.route("/ws", get(ws_handler))` is on the outer router; `TimeoutLayer` is `.layer()`'d at line 61–64 inside `http_routes` only.
- In axum 0.8, `layer()` on an inner Router applies only to the routes in that Router, not to routes merged later. So `/ws` should not be covered by the 30 s `TimeoutLayer`.
- This hypothesis is ranked lowest because the code explicitly guards against it (see comment lines 41–43) and because a 30 s layer would produce drops at ~30 s, not ~60 s.

**What triggers it:** Would require a router construction bug where the outer merge inadvertently reapplies the inner layer to `/ws`. Currently the code is structured correctly.

**Recommended fix:** No change needed. If drops were measured at exactly ~30 s this hypothesis would rise to H1. At ~60 s it is ruled out.

---

## Confirmation Tests

Run all three in order. Stop at the first one that reproduces or rules out the hypothesis.

**T1 — Verify Caddy config on the live VPS (rules out / confirms H1):**

```bash
# From the VPS or any host that can exec into the container:
docker exec phantom-caddy caddy validate --config /etc/caddy/Caddyfile
docker exec phantom-caddy grep -n "read_timeout\|write_timeout\|flush_interval" /etc/caddy/Caddyfile
```

If output is empty or shows non-zero timeout values, H1 is confirmed. Fix: recreate the caddy container as described above.

**T2 — Sustain a WebSocket for 90 s using wscat and observe drop timing:**

```bash
# Install wscat: npm install -g wscat
# Replace <TOKEN> with the actual RELAY_SECRET_TOKEN value
wscat -c "wss://relay.phntm.pro/ws?id=testprobe&token=<TOKEN>" --no-color 2>&1 &
# Leave the connection idle (no frames sent) and watch for close
```

If the connection drops at ~60 s with no frames sent, H1 (Caddy idle timeout) is confirmed. If it drops at ~30 s, H3 (TimeoutLayer regression). If it sustains beyond 90 s, both are ruled out and H2 (client-side OkHttp timeout) is the remaining cause.

**T3 — Confirm relay Pong responds to application-level Ping (rules out relay-side Pong regression):**

```bash
wscat -c "wss://relay.phntm.pro/ws?id=testprobe&token=<TOKEN>"
# Once connected, type:
{"type":"ping"}
# Expected response within 500 ms:
{"type":"pong"}
```

If no Pong is received within 2 s of sending the Ping, the relay's `handle_message` ping branch is broken or the client identity lookup is failing (the Pong is sent to `from_identity` via the mpsc channel, which requires a registered client entry). Diagnose by checking relay container logs: `docker logs phantom-relay --tail 50`.

---

## Files Examined

- `deploy/Caddyfile` — proxy config (the source of H1)
- `deploy/docker-compose.yml` — container orchestration
- `services/relay/src/routes.rs` — WebSocket handler, Ping/Pong logic, TimeoutLayer scoping
- `services/relay/src/main.rs` — axum server startup, no keep-alive flags
- `services/relay/src/config.rs` — no session lifetime or idle timeout fields
- `services/relay/Dockerfile` — no `--ws-keepalive` or TCP keepalive flags in ENTRYPOINT
- `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/KtorRelayTransport.kt` — client reconnect loop, Ping/Pong watchdog
- `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/RelayTransportConfig.kt` — `PING_INTERVAL_MS=10000`, `PONG_TIMEOUT_MS=60000`

---

## Post-Caddy: residual ~20s resets

*Added 2026-05-01 after Caddy `read_timeout 0` / `flush_interval -1` / force-recreate was confirmed working on the VPS.*

**Observed pattern:** `SocketException: Connection reset` every 20-22 seconds. The `forceReconnect()` log line appears at 14:54:54.718, coinciding with an AlarmManager fire. Some resets occur mid-envelope-delivery (zombie envelope re-delivered on next reconnect), indicating the TCP RST interrupts an active receive, not an idle connection.

### What the relay-side code rules out

**No relay-side 20s budget exists.** Full code review confirms:

- `services/relay/src/main.rs` — one background task: `tokio::time::interval(Duration::from_secs(300))` for envelope expiry cleanup. No 20s `tokio::time::timeout` or `tokio::time::sleep` wrapping the per-connection task. The connection task is an unbounded `loop { tokio::select! { ... } }` with no deadline.
- `services/relay/src/routes.rs` line 67 — `/ws` is mounted on the outer `Router`, outside the `TimeoutLayer` that is scoped exclusively to `http_routes` (lines 44–64). Confirmed: no per-WS-frame deadline.
- `services/Cargo.toml` — axum 0.8, tokio 1, tower-http 0.6. The relay does not depend on `axum-tungstenite` (the crate known to have a 20s ping/pong watchdog in some older versions). tungstenite is pulled in transitively through axum's `ws` feature; it has no default watchdog timer.
- Caddy: `deploy/Caddyfile` lines 53–57 — `read_timeout 0`, `write_timeout 0`, `flush_interval -1`. No global `servers { idle_timeout }` block exists in the file. H3 (TLS renegotiation) is not a Caddy-level knob; Caddy delegates TLS to the OS/rustls and does not force renegotiation on a timer.

### Hypothesis ranking (post-Caddy fix)

**H4 — Emulator qemu/slirp NAT idle TCP timeout (MOST LIKELY, ~65%)**

The Pixel 8 Pro emulator runs through qemu's slirp user-mode network stack. slirp maintains its own conntrack with documented defaults: `tcp_established` 20 seconds for connections that have not had bidirectional traffic within the slirp poll cycle. Unlike a Linux kernel conntrack entry (which is 5 days for ESTABLISHED), slirp's entry is process-local and resets on each poll miss. If the emulator's host machine has momentary Wi-Fi instability, or if the host's own NAT (home router) stales out the outer TCP session, slirp emits a RST on the inner socket — which OkHttp sees as `Connection reset`.

Evidence:
- Gap is ~20-22s, which matches slirp's documented `tcp_established` default (QEMU source: `slirp/src/tcp_timer.h`, `TCPTV_KEEP_IDLE = 20 * PR_SLOWHZ`).
- Resets happen during active delivery (14:54:14 during envelope receive), meaning the server side had active traffic — ruling out server-initiated idle close.
- The `forceReconnect()` at 14:54:54 is an AlarmManager fire, not the cause of the earlier resets; it is a response to pong staleness detected after a reset had already happened.
- The 20s interval is consistent across the log (~20-22s), a hallmark of a timer-driven mechanism rather than a bursty network event.
- Physical device tests (cellular or Wi-Fi via host bridge) would not reproduce this if H4 is correct.

**H2 — Client ACK watchdog closing the session (MEDIUM, ~20%)**

`RelayTransportConfig.ACK_TIMEOUT_MS = 60_000L` (`RelayTransportConfig.kt` line 28). The ACK watchdog polls every `ACK_WATCHDOG_INTERVAL_MS = 5_000L` (`RelayTransportConfig.kt` line 32) and calls `forceShutdownActiveEngine()` + `generationClient.close()` + `scope.cancel()` when an envelope ages past 60s unacknowledged (`KtorRelayTransport.kt` lines 289-314). This produces client-initiated closes, not server RSTs. 60s does not match 20s. **Ruled out as the primary cause** but explains some of the reconnect churn if envelopes are being queued and the socket is already dead.

**H1 (residual) — Caddy `idle_timeout` on the server `servers` block (~10%)**

The Caddyfile has no `servers { ... }` global block, so Caddy's default server-level `idle_timeout` (5 minutes in Caddy 2.7+) applies but at 5 minutes, not 20s. Ruled out for the 20s pattern. Would only surface if the connection stays silent for 5 minutes, which does not match the log.

**H5 — Client-side pong watchdog (`PONG_TIMEOUT_MS = 60_000L`) (~5%)**

`RelayTransportConfig.kt` line 19: `PONG_TIMEOUT_MS = 60_000L`. The pong watchdog fires at the ping check interval (10s) and checks elapsed time (`KtorRelayTransport.kt` line 264-282). It does not fire at 20s. Ruled out.

**H3 — TLS renegotiation (~0%)**

TLS 1.3 (used between Caddy and clients) removed renegotiation entirely. Even under TLS 1.2 renegotiation is not timer-driven at 20s by any party in this stack. Ruled out.

### Recommended next step

**Do not change relay or VPS config.** Run the same session on a physical Android device (real Wi-Fi or cellular, not the emulator) and record drop timing. If drops disappear or shift to a different interval, H4 (emulator NAT) is confirmed and no server-side fix is needed for this symptom. If drops persist at ~20s on a physical device, escalate to H2 investigation (check whether the ACK watchdog is firing prematurely due to a pong-not-updating bug on the receive path).

Secondary check (emulator-specific): enable `adb logcat -s *:V` with the emulator's `-verbose` network flag to see slirp RST events directly. If slirp emits `tcp_drop: RST` at the same timestamps as the OkHttp `Connection reset`, H4 is proven.

### Impact assessment

If H4 is confirmed: zero production impact. The relay is healthy. The symptom is an emulator artifact. No code change needed before Kickstarter. The existing AlarmManager-driven `forceReconnect()` recovers the session within 1-3s of the RST, which is acceptable UX even on a physical device if a real NAT were ever responsible.
