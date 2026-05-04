# ADR-014: Transport TCP keepalive strategy

Status: accepted (2026-05-04, fix/transport-tcp-keepalive)
Layer: shared/core/transport (Android), services/relay, deploy
Supersedes (in part): ADR-013 (revised diagnosis)

## Context

The 4-test matrix on 2026-05-04 (Tecno Spark Go + Pixel 8 Pro emulator,
both on MTS WiFi, identical network path) demonstrated that the
"WebSocket reset every 50-60 seconds" symptom previously attributed to
Tecno HiOS Wi-Fi radio parking (ADR-013) is actually present on the
emulator as well — a stock-Android process running on a stable Windows
PC. Both devices, sharing the same WiFi/ISP/transit path to Hetzner
Helsinki, exhibit identical reconnect cadence. Both stay perfectly
stable when behind any VPN.

This rules out a Tecno-firmware root cause and points at a stateful
network element along the path that idles out connections without
keepalive traffic. The most likely candidates, given Russian
mobile-carrier topology:

- Carrier-Grade NAT on MTS — published timeout values for Russian
  carriers fall in the 50-90 s range
- Russian transit border filtering (TSPU) — possible secondary factor
- Generic stateful firewall along the Hetzner path

VPN tunnels (OpenVPN with `keepalive 10`, WireGuard with
`PersistentKeepalive 25`) bypass this by emitting their own keepalive
packets at the outer-tunnel layer, which refreshes the NAT entry every
10-25 seconds regardless of what the inner application sends.

The previous attempt (3 s application-level WebSocket ping) did not
solve the symptom and introduced a new regression: the emulator
went from stable (10 s ping) to unstable (3 s ping) with `Connection
reset` appearing every ~50 s. This suggests middlebox or server-side
heuristics react badly to sub-5 s frame frequency — either a rate
limit, a DPI flag, or a buggy proxy that buffers frames in a way that
breaks the reset pattern. Either way, "ping more frequently" is not
the right answer.

## Decision

Defend the NAT path at the **TCP layer** with `SO_KEEPALIVE` and
explicit `TCP_KEEPIDLE / TCP_KEEPINTVL / TCP_KEEPCNT` parameters tuned
to beat any plausible CGN idle timeout. Apply identically on both
endpoints so each direction of the NAT entry stays warm.

Timing:

| Parameter         | Value | Rationale                                           |
|-------------------|-------|-----------------------------------------------------|
| `TCP_KEEPIDLE`    | 30 s  | Half of CGN typical 60 s. Probe well before idle    |
|                   |       | timeout fires.                                      |
| `TCP_KEEPINTVL`   | 10 s  | If a probe is dropped, retry quickly. 3 retries     |
|                   |       | over 30 s gives confident dead-detection.           |
| `TCP_KEEPCNT`     | 3     | Total dead-detection window 30 + 30 = 60 s. Enough |
|                   |       | to absorb one transient WiFi glitch but still beat |
|                   |       | most CGN windows.                                  |

Why TCP keepalive instead of more frequent app-level pings:

1. TCP keepalive packets are 0-byte ACKs that the OS kernel emits
   automatically. Middleboxes treat them as normal TCP traffic and
   refresh stateful entries. They do not look like app-level activity
   that DPI heuristics fingerprint.
2. The OS sends them only after `TCP_KEEPIDLE` of *transmit*
   inactivity — they are zero-cost during normal app traffic.
3. Cost is negligible: 0 bytes payload, 40 bytes IP+TCP header per
   probe, sent once every 10 s only when the connection has been
   silent for 30 s.

Application-level ping at 10 s remains in place, but its primary role
shifts from NAT-keepalive to app-layer liveness (detecting a
half-dead WebSocket where TCP says alive but the relay has crashed).
TCP keepalive defends the network path; app ping defends the
WebSocket framing layer.

## Implementation

### Client (Android, OkHttp)

`shared/core/transport/src/androidMain/kotlin/phantom/core/transport/KeepAliveSocketFactory.kt`
implements a `SocketFactory` that returns a `Socket` subclass whose
`connect(...)` override calls `android.system.Os.setsockoptInt` with
`IPPROTO_TCP` and the three keepalive constants immediately after the
underlying connect succeeds.

`Socket.setKeepAlive(true)` (== `SO_KEEPALIVE`) is set via the public
Java API. The three timing parameters are not exposed publicly; we use
the raw integer constants:

- `IPPROTO_TCP = 6`
- `TCP_KEEPIDLE = 4`
- `TCP_KEEPINTVL = 5`
- `TCP_KEEPCNT = 6`

These are ABI-stable in Linux since 2.4 and used by every NDK
networking library. FileDescriptor extraction tries
`Socket.getFileDescriptor$()` (AOSP `@hide`) first, falls back to
reflection on `Socket.impl.fd`. Both failure paths log a warning and
let the connection continue with kernel-default timing.

Wired into `RelayTransportFactory.androidMain.createHttpClientFactory`
via `OkHttpClient.Builder.socketFactory(KeepAliveSocketFactory())`.
Applied only to the WebSocket client; REST traffic
(`createRestHttpClient`) is short-lived and uses kernel defaults.

### Server (Rust relay, axum + tokio)

`services/relay/src/main.rs` introduces `KeepAliveListener`, a thin
wrapper around `tokio::net::TcpListener` implementing axum 0.8's
`Listener` trait. Its `accept()` calls `socket2::SockRef::from(&stream)
.set_tcp_keepalive(...)` with the same 30/10/3 values before returning
the stream to axum.

`socket2 = "0.5"` was added to the workspace. The
`with_retries(...)` builder is gated by `cfg!` for Linux/Android/
FreeBSD/macOS so dev-host `cargo check` on Windows still compiles;
production runs in a Linux Docker container where the full timing is
applied.

### Container kernel (docker-compose sysctls)

`deploy/docker-compose.yml` adds:

```yaml
sysctls:
  net.ipv4.tcp_keepalive_time: 30
  net.ipv4.tcp_keepalive_intvl: 10
  net.ipv4.tcp_keepalive_probes: 3
```

to both `relay` and `caddy` services. These are namespaced sysctls
(per network namespace) so they apply to each container's TCP stack
without touching the host kernel.

For Caddy this is the *primary* defence: Caddy on Go enables
`SO_KEEPALIVE` on accepted connections by default with a 15 s
`TCP_KEEPIDLE`, but the interval and probe count come from kernel
sysctls. The default Linux values (75 s / 9 probes) would wait nearly
12 minutes to declare a NAT-dropped connection dead. The override
brings that window into the 60 s range.

For relay, the explicit `apply_keepalive` in code already pins all
three values per-socket; the namespace sysctls are belt-and-braces
for any tokio path that bypasses our wrapper.

### Host kernel (optional)

`deploy/99-phantom-keepalive.conf` provides the same three sysctl
values for installation in `/etc/sysctl.d/`. Optional — affects host
processes outside Docker. Recommended on the production VPS as
defence in depth.

## Rejected alternatives

- **More frequent app-level WebSocket ping (3 s, 5 s).** Tested with
  3 s, broke the emulator. Triggers middlebox / server-side
  heuristics that flag the connection. See diagnostic data in
  `docs/research/transport-investigation-2026-05-04/`.
- **HTTP/2 long-polling fallback.** Architectural change, bigger
  blast radius, deferred unless Layer 2 fails.
- **QUIC / HTTP/3.** Same — long-term direction but not Alpha 2.
- **Fake-TLS / Cloudflare-fronted transport.** Anti-censorship layer,
  warranted only if RST source is identified as TSPU and not CGN.
  Reserve for Layer 3 contingency.

## Consequences

### Positive

- Removes the "VPN required" caveat for cellular Russian carriers if
  the diagnosis (CGN idle timeout) is correct.
- TCP-layer defence is invisible to app code — no surface-area changes
  to messaging, voice, or calls.
- Cost is essentially zero (0-byte probes, sent only when transport
  is idle).
- Aligns with industry practice (Element, SimpleX, ntfy all tune TCP
  keepalive on cellular).

### Negative / risks

- **Not validated yet.** Diagnosis is the consensus of four parallel
  research agents on 2026-05-04 but until Test 5 runs we have not
  proven CGN was the root cause. If it is not, this fix is
  insufficient and we need Layer 3 (tcpdump experiments) or Layer 4
  (UnifiedPush acceleration).
- **Reflection-based FD extraction.** AOSP-internal API; could break
  on a future Android release. Failure mode is graceful (warning
  logged, kernel-default timing applied) so the worst case is the
  pre-fix behaviour, not a crash.
- **Per-container sysctls require Docker 1.12+.** Confirmed available
  on the Hetzner VPS.

### Test plan (Test 5)

After deployment of this branch to VPS and APK install on Tecno +
emulator:

1. Both devices off VPN, on MTS WiFi, 5 + 5 minute idle baseline —
   check connection holds without `forceReconnect`
2. Text exchange — should be instant
3. Voice 5 s - delivered
4. Voice 15 s - delivered
5. Voice 30 s - delivered (29 chunks at 8 KB)
6. Voice call - audio both directions, no asymmetric audio bug, no
   30-second-mid-call drop

Pass criterion: voice messages of all sizes delivered to the
recipient without VPN, with no `Connection reset` events visible in
logcat for at least 5 minutes of idle.

Fail criterion: any `Connection reset` from `WebSocketReader`, or any
voice envelope that does not produce `handleDeliver DONE` on the
recipient side.

If pass: ISSUE-013 reclassified as resolved by ADR-014, voice and
calls move from experimental to production-ready in ALPHA 2 docs.

If fail: proceed to Layer 3 — execute
`docs/research/transport-investigation-2026-05-04/04-network-diagnostic-plan.md`
to capture exact RST origin and refine the diagnosis.

## References

- `docs/research/transport-investigation-2026-05-04/01-server-audit.md`
- `docs/research/transport-investigation-2026-05-04/02-industry-research.md`
- `docs/research/transport-investigation-2026-05-04/03-client-transport-audit.md`
- `docs/research/transport-investigation-2026-05-04/04-network-diagnostic-plan.md`
- `docs/research/transport-investigation-2026-05-04/99-synthesis.md`
- `docs/adr/ADR-013-revised-transport-diagnosis-2026-05-02.md` (superseded
  diagnosis: Tecno firmware radio parking is not the actual cause)
- ADR-010 / ADR-011 / ADR-013 chain — earlier transport-recovery
  patches that remain in place (forceReconnect, AlarmManager wakeup,
  generation-based engine disposal)
- `KNOWN_ISSUES.md` ISSUE-013 (updated to reflect Layer 2 mitigation)
- Linux kernel TCP keepalive: <https://www.kernel.org/doc/Documentation/networking/ip-sysctl.txt>
- Wireless Moves on CGN timeouts: <https://blog.wirelessmoves.com/2020/09/carrier-grade-nat-timeouts-and-how-to-configure-your-xmpp-server.html>
- Richter et al, IMC '16, "A Multi-perspective Analysis of CGNs":
  <https://www.prichter.com/imc176-richterA.pdf>
