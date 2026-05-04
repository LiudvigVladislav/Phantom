# Server-side audit -- 2026-05-04

## Summary

The Caddyfile in the repository has the correct directives (`read_timeout 0`,
`write_timeout 0`, `flush_interval -1`) that disable all proxy-side idle close logic.
The relay itself has zero timeout code on the WebSocket path. The most plausible
server-side cause is that the live VPS Caddy container is running a stale Caddyfile
that predates the `read_timeout 0` fix -- which would reproduce the observed ~60-second
RST cycle exactly. The "VPN cures it" pattern is consistent with this: VPN clients
typically send their own keepalive traffic (WireGuard/OpenVPN heartbeats every 10-25s),
which keeps the Caddy backend connection from reaching any idle threshold, masking the
misconfiguration entirely.

---

## Caddyfile review

File: `deploy/Caddyfile`

### Lines 53-58 -- transport http block (THE KEY SETTING)

```
transport http {
    read_timeout  0
    write_timeout 0
    dial_timeout  10s
}
```

`read_timeout 0` and `write_timeout 0` disable Caddy's proxy-side idle close on the
backend connection. Without these, Caddy's default is a 60-second read timeout on the
backend socket. A WebSocket session with no frame in that window gets a TCP RST from
Caddy toward the relay, which propagates a `Connection reset` to the client.

**This is the exact symptom in the test matrix.** The Caddyfile comment at lines 47-52
explicitly calls out: "Caddy must not second-guess them with a 60-second idle close."

**Risk:** This is the repository version of the file. The question is whether the live
VPS container has this version mounted. See "Smoking guns" and "Recommended next steps"
below.

### Line 59 -- flush_interval -1

```
flush_interval -1
```

Tells Caddy to flush each WebSocket frame to the upstream immediately rather than
buffering. Not a connection-termination risk, but necessary for low-latency delivery.
Absence of this would cause delivery stalls, not RSTs.

### Lines 12-19 -- global block

No `servers { idle_timeout }` override exists. Caddy 2.8 default server-level idle
timeout is 5 minutes. Not relevant to the 50-60s pattern.

### No Cloudflare proxy on relay.phntm.pro

Comment at lines 3-6: relay.phntm.pro is set to DNS-only in Cloudflare. Caddy handles
TLS directly. This rules out Cloudflare's 100-second proxy timeout as a factor -- there
is no Cloudflare hop in the relay path.

### dial_timeout 10s

Controls only how long Caddy waits to OPEN the backend connection, not how long it
holds it. Not a disconnect risk.

---

## Relay routes.rs review

File: `services/relay/src/routes.rs`

### Lines 44-64 -- TimeoutLayer scoping

`TimeoutLayer` with a 30-second budget is applied only to `http_routes` (the inner
Router containing /health, /send, /fetch, /ack, /report, /admin/*, /prekeys/*).

The `/ws` route is mounted on the outer Router at line 67:

```rust
Router::new()
    .route("/ws", get(ws_handler))
    .merge(http_routes)
```

In axum 0.8, `.layer()` applied inside `http_routes` does NOT propagate to routes
added to the outer Router after `.merge()`. The `/ws` route has no timeout layer.
A 30-second drop would appear here if the merge order were reversed; it is not.

### Lines 179-208 -- handle_socket select! loop

The loop is an unbounded `loop { tokio::select! { ... } }` with no `tokio::time::timeout`
wrapper and no `tokio::time::sleep` in any branch. No server-side idle timer exists.
The only exits are:

- `outbound = rx.recv()` returning `None` (mpsc sender dropped -- never happens in
  normal operation)
- `socket.send(...).is_err()` -- write failure
- `Message::Close(_)` received
- `Some(Err(_)) | None` on the read arm -- read failure or stream end

None of these is timer-driven. The relay will hold a WebSocket open indefinitely as
long as the underlying TCP connection is alive from its perspective.

### Lines 194-199 -- explicit Pong on Ping

```rust
Some(Ok(Message::Ping(payload))) => {
    if socket.send(Message::Pong(payload)).await.is_err() {
        break;
    }
}
```

The relay responds to WebSocket-protocol PING frames immediately and synchronously
within the same select! arm. This was fixed in commit `b3983c55` ("respond to WebSocket
PING immediately, fixes 60s reconnect cycle"). The auto-PONG starvation bug described
in the comment at lines 109-124 is resolved.

### Lines 410-413 -- application-level ping handler

```rust
Some("ping") => {
    if let Some((_, tx)) = state.clients.read().await.get(from_identity) {
        let _ = tx.send(r#"{"type":"pong"}"#.to_string());
    }
}
```

Application-level `{"type":"ping"}` from the client triggers a `{"type":"pong"}` via
the mpsc channel. This goes through the same select! outbound arm. No timeout here.
One edge case: if `from_identity` is empty (client connected without an `?id=` param),
the client map lookup returns None and pong is silently dropped. The client's PONG
watchdog (`PONG_TIMEOUT_MS = 60_000L`) would then fire 60 seconds after the last pong.
This is a misconfiguration scenario, not a relay code bug.

### Lines 280-303 -- rate limiter

The rate limiter is a sliding window on message count per sender identity
(`rate_limit_per_window = 60`, `rate_limit_window_secs = 60` by default from
`config.rs` lines 71-77). It applies to `"send"` and `"typing"` frames only. It does
NOT close the WebSocket -- it drops the message silently and returns. The connection
stays open.

Application-level pings (`"ping"` type) are NOT counted against the rate limit.
WebSocket-protocol PING frames are handled before handle_message is called.

No code path in the rate limiter closes the socket.

---

## Other relay files

### services/relay/src/main.rs

Lines 45-63: one background task, a `tokio::time::interval(Duration::from_secs(300))`
(5-minute envelope expiry sweep). This task operates on `app_state.store` only; it
never touches active connections.

Line 65: `axum::serve(listener, app)` -- no `tcp_keepalive_interval`, no
`tcp_keepalive_timeout`, no `connection_timeout` passed. axum::serve uses tokio's
TcpListener defaults, which means TCP keepalive is OFF at the application level. The OS
default (Linux: 7200s) applies, which is far outside the 60s symptom window.

No `tower::ServiceBuilder` with a `timeout` layer wrapping the top-level app.

### services/relay/src/config.rs

No `idle_timeout`, `session_timeout`, or `ws_timeout` fields. The only time-related
fields are `envelope_ttl_secs` (default 7 days) and `rate_limit_window_secs` (default
60s). Neither affects connection lifetime.

### services/relay/src/state.rs (inferred -- not read separately)

No timer-driven connection eviction logic exists in routes.rs or main.rs. The
`conn_id` guard (routes.rs lines 215-230) is a cleanup correctness measure, not a
timeout.

### deploy/docker-compose.yml

No `ulimits`, `sysctls`, or resource limits that could impose a connection cap or
timeout. The relay container runs with `read_only: true` and `cap_drop: ALL`, which
are security hardening measures with no effect on TCP session lifetime.

No systemd unit file exists; the container uses `restart: unless-stopped`.

### Cargo.toml dependency versions

`axum = "0.8"` with `features = ["ws"]` -- uses tungstenite internally via axum's
`extract::ws` module. tungstenite at current versions (0.21.x pulled by axum 0.8) has
no default ping/pong watchdog timer. The relay's explicit Pong handling (routes.rs
lines 194-199) is correct.

`tower-http = "0.6"` -- `TimeoutLayer` is imported but scoped correctly as described
above.

---

## Smoking guns

### Primary: Caddy VPS container may be running stale config

The Caddyfile in the repository has had `read_timeout 0` / `write_timeout 0` since
commit `f8696c27` ("fix(transport): client-side ACK watchdog + protocol ping + Caddy
idle timeout"). The symptom described in the prior RELAY_AUDIT_2026_05_01.md as H1
("Caddy on the VPS is running a stale config") was confirmed as the dominant cause of
the 60-second reconnect cycle at that time.

The current test matrix shows the SAME pattern -- ~50-60s `Connection reset` -- with
PING_INTERVAL_MS now at 3000ms. If the Caddyfile on the VPS is correct (read_timeout 0),
a 3-second ping cadence would produce traffic every 3 seconds and Caddy would never
reach any idle threshold regardless. The fact that drops still occur at ~50-60s is
therefore strong evidence that the live Caddyfile does NOT have `read_timeout 0`.

**Why VPN cures it:** A VPN tunnel (WireGuard: keepalive every 25s by default;
OpenVPN: keepalive every 10s by default) sends UDP/TCP traffic through the same NAT
path that the WebSocket uses, keeping the host NAT entry alive. More importantly, a
VPN client maintains its own heartbeat traffic that passes THROUGH the same Caddy
reverse-proxy path -- the resulting TCP traffic resets Caddy's per-connection idle
timer. Even with `read_timeout` at Caddy's 60s default, the VPN keepalive fires every
10-25s and prevents the timer from expiring. Without VPN, the only traffic on the
WebSocket is our app-level ping frames -- but if Caddy sees those frames as data from
the CLIENT to the UPSTREAM (relay), the proxy's read idle timer (measuring silence from
the UPSTREAM back to the proxy) would only reset when the relay sends a frame. The
relay's pong via the mpsc channel arrives within milliseconds; so even the default
60s timer should not fire. This makes the VPN explanation less clean and points to a
deeper investigation of exactly which Caddy version / default is running on the VPS.

**The 3s ping change:** PING_INTERVAL_MS was 10_000ms during the stable 2026-05-02
twin-emulator run. It is now 3_000ms. The previous audit (RELAY_AUDIT_2026_05_01.md,
"Post-Caddy: residual ~20s resets" section) already resolved the 60s Caddy issue and
documented a separate 20-22s slirp NAT pattern on the emulator. The reappearance of
50-60s drops after the ping cadence change is suspicious and does not fit the 3s ping
producing MORE traffic. The most parsimonious explanation is that the VPS was not
updated after the RELAY_AUDIT fix, or was updated then redeployed from a cached/old
image.

---

## Things that look fine

- `TimeoutLayer` scope: confirmed to NOT cover /ws. Rules out 30-second relay timeout.
- Relay WebSocket loop: zero timer-driven exits. Relay will not close an idle session.
- Rate limiter: does not close connections, only drops frames.
- Background cleanup task: operates every 300s on envelope store, never on connections.
- Cloudflare: DNS-only for relay.phntm.pro, no Cloudflare proxy timeout in the path.
- tungstenite auto-PONG starvation: fixed in commit b3983c55, confirmed in code.
- TCP keepalive: relay does not configure SO_KEEPALIVE; Linux default (7200s) is
  irrelevant to a 60s symptom.
- App-level ping pong: relay correctly responds to {"type":"ping"} as long as the client
  connected with a non-empty ?id= parameter.
- docker-compose: no ulimits or sysctls that would terminate connections.

---

## Recommended next steps

**Step 1 (must do first -- 5 minutes, SSH required):**

Verify what Caddyfile the live container is actually running:

```
docker exec phantom-caddy grep -n "read_timeout\|write_timeout\|flush_interval" /etc/caddy/Caddyfile
```

Expected output if correct:
```
54:        read_timeout  0
55:        write_timeout 0
59:    flush_interval -1
```

If the output is empty or shows non-zero values, redeploy caddy:

```
cd /path/to/deploy
docker compose up -d --force-recreate caddy
```

Then re-run the 4-test matrix. If the 50-60s drops disappear, the stale Caddyfile was
the cause.

**Step 2 (if Step 1 shows correct config):**

Run a passive idle test to distinguish Caddy idle timeout from client-side pong watchdog:

```
wscat -c "wss://relay.phntm.pro/ws?id=testprobe&token=TOKEN" --no-color 2>&1
# Do NOT send any frames. Observe how many seconds until disconnect.
```

- Drops at ~60s with no frames sent => Caddy read_timeout is NOT 0 (stale config)
- Stays open beyond 120s with no frames => Caddy is correct; cause is client-side

**Step 3 (if Step 1 and 2 show Caddy is fine):**

The VPN correlation then points to a NAT/carrier middlebox between the non-VPN client
and relay.phntm.pro. On Hetzner Helsinki, the path from a Russian mobile carrier goes
through carrier-grade NAT (CGNAT) with stateful TCP entry timeouts as low as 30-60s
for connections with low traffic. VPN tunnels bypass CGNAT entirely by encapsulating
TCP in UDP.

To test: install tcpdump on the VPS and capture RST packets during a non-VPN session:

```
tcpdump -i any -n 'tcp[tcpflags] & tcp-rst != 0' -w /tmp/rst.pcap
```

If RST packets arrive from relay.phntm.pro outbound at the 50-60s mark, the relay or
Caddy is the source. If no RST packets are generated by the server but the client
reports `Connection reset`, the RST is injected by an in-path middlebox (CGNAT).

**Step 4 (if middlebox is confirmed):**

Reduce TCP keepalive interval at the OS level on the VPS so that the TCP stack sends
keepalive probes that punch through CGNAT before the NAT entry expires:

```
# In docker-compose.yml, under the relay service:
sysctls:
  net.ipv4.tcp_keepalive_time: 30
  net.ipv4.tcp_keepalive_intvl: 10
  net.ipv4.tcp_keepalive_probes: 3
```

This tells the kernel to send a TCP keepalive probe if the connection is idle for 30s,
then retry every 10s up to 3 times. Most CGNAT boxes reset their entry timer on ANY
TCP segment, including keepalive ACKs. This fix operates below the application layer
and requires no changes to Caddy, relay, or client code.

---

## Open questions

1. **Has `docker compose up -d --force-recreate caddy` been run on the VPS since
   commit f8696c27 (the Caddy fix)?** If not, the live container still has the old
   Caddyfile. This is the single most important question and can be answered in 5
   minutes via SSH.

2. **What is the client's network path?** Specifically: is the Tecno device on a
   Russian mobile carrier that routes through CGNAT? If yes, CGNAT TCP entry timeout
   (commonly 30-60s) is a strong candidate for the VPN-cures-it correlation even if
   Caddy is correctly configured.

3. **What VPN protocol is being used?** WireGuard keepalive fires every 25s by
   default; OpenVPN every 10s. Knowing the VPN type would help quantify how much
   keepalive traffic it generates and whether that traffic specifically bypasses the
   CGNAT path or just keeps the NAT entry alive.

4. **Is relay.phntm.pro behind Hetzner's floating IP or a load balancer?** Hetzner
   Cloud load balancers have their own idle timeout (default 60s for TCP). If a
   Hetzner LB sits in front of the Caddy container, `read_timeout 0` in Caddy would
   not help because the LB terminates the client TCP session.
