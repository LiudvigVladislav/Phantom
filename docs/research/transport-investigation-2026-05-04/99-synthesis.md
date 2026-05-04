# Synthesis — Transport Investigation 2026-05-04

## Verdict

The 4 parallel research agents (server audit, industry research, client
audit, network diagnostic plan) **converged on the same primary
diagnosis**: the 50-60 second `Connection reset` cycle observed for
non-VPN clients is a **stateful idle timeout** somewhere in the network
path that is reset by VPN's own keepalive traffic. The two most likely
candidates are:

A. **VPS Caddy** still running an older `read_timeout` config (pre-fix
   `f8696c27`) — fixable in 5 minutes if true
B. **Carrier-Grade NAT (CGN) or stateful firewall** in the path
   between client ISP and Hetzner — fixable with TCP keepalive
   tuning + correct app-level pings

Both are addressable. Neither requires architectural change.

The previous diagnosis in `ADR-013` (Tecno HiOS firmware Wi-Fi radio
parking) is **not the root cause** for this symptom. Test 1 of the
4-test matrix shows the Pixel emulator on a stable PC also receives
`Connection reset` every 50 seconds — the firmware-radio explanation
cannot account for emulator behaviour. ADR-013 should be revised.

## Convergence map

| Finding | Agent A (server) | Agent B (industry) | Agent C (client) | Agent D (forensics) |
|---|---|---|---|---|
| 50-60s = stateful idle timeout signature | yes | yes (cited Richter IMC'16, Wireless Moves, websocket.org) | yes | designed experiment to confirm |
| 3s ping interval unusual / unverified | flagged | yes — industry sweet spot 25-30s | yes — comment on the line marks it experimental | n/a |
| TCP `SO_KEEPALIVE` is the standard mitigation for CGN | implied (sysctl note) | yes — Linux default 7200s is useless | yes — primary recommendation | implied in decision tree |
| VPS Caddy may not have fix `f8696c27` deployed | direct hypothesis | n/a | n/a | direct experiment in plan |
| FCM is what makes Signal/WhatsApp survive — PHANTOM cannot inherit this | n/a | yes | n/a | n/a |
| UnifiedPush is the right Phase 5 path (already planned) | n/a | yes | n/a | n/a |

## Why VPN appears to "fix" everything

This was the original puzzle. All four agents independently arrived at
the same explanation:

- **OpenVPN** sends keepalive every **10 seconds** by default
- **WireGuard** sends `PersistentKeepalive` every **25 seconds** (when
  configured; 0 by default)
- These keepalives are sent on the VPN's UDP/TCP tunnel **regardless of
  whether the inner app sends anything**
- A NAT/firewall idle timer along the path sees the steady stream of
  outer keepalive packets and never expires the entry
- When app traffic flows through the tunnel, it travels under the same
  outer source IP/port, so the inner connection inherits the warmed
  NAT entry

Without VPN, PHANTOM's only defence against the same idle timer is its
application-level WebSocket ping. If Caddy idles at 60s, a 3s app-ping
should keep the connection alive — unless the path also drops the
connection for **another** reason. If the cause is CGN, 3s ping would
also reset the CGN timer — unless CGN inspects sub-layer TCP (it does)
and the actual TCP RST originates from somewhere else entirely.

This is exactly the question the tcpdump experiments in
`04-network-diagnostic-plan.md` are designed to answer.

## Why 3s ping made things worse, not better

Test 1 (no VPN both, 3s ping) shows the **Pixel emulator regressed**
from previously-stable to 50s reconnect cycle. The only change between
the 2026-05-02 stable test and 2026-05-04 unstable test is
`PING_INTERVAL_MS` from 10s to 3s.

Two non-mutually-exclusive explanations:

1. **Frequent small frames trigger middlebox heuristics.** Some DPI
   appliances flag "long-lived connection with frequent tiny encrypted
   payloads" as suspicious (this is exactly the WebSocket-ping
   signature) and start dropping/RST-ing such connections more
   aggressively. The 3s frequency makes the pattern more obvious.
2. **Server-side rate limiting.** If Caddy or Hetzner has any per-IP
   rate limit on connection bytes/packets, 3s ping triples the
   baseline rate. This is unconfirmed by Agent A — relay rate limiter
   exists but only drops frames silently, doesn't close.

Either way the conclusion is the same: **3s ping is not the right
answer**. Industry consensus is 25-30s for cellular WSS (websocket.org
guides; Element 30s; Signal effectively 30s via FCM heartbeat
inheritance).

## Recommended action plan

Three layers, executed in order. Most likely the first layer alone
fixes the issue.

### Layer 1 — Quick wins (1-2 hours)

These are low-risk, independent of root cause, and recoverable. Do
all three regardless.

1. **Revert `PING_INTERVAL_MS` to 10000ms.** The 3s experiment is
   refuted by Test 1 emulator regression. Update the comment.
2. **SSH to VPS and confirm Caddy is running with `read_timeout 0`.**
   `docker exec phantom-caddy grep read_timeout /etc/caddy/Caddyfile`.
   If empty or non-zero, redeploy Caddy:
   `docker compose up -d --force-recreate caddy`. This is the
   highest-probability single fix.
3. **Rebuild APK, retest 4-test matrix on the Tecno + emulator pair.**
   If voice now delivers without VPN, layer 1 was sufficient. Stop
   here.

### Layer 2 — TCP keepalive defense (1-2 days)

If Layer 1 does not fix it, add TCP-level keepalive at the client
socket. This survives middlebox idle timeouts that app-level pings
don't reach (e.g., transparent proxies that re-buffer WS frames).

1. **Custom SocketFactory in OkHttp** that sets `SO_KEEPALIVE=true`,
   `TCP_KEEPIDLE=30`, `TCP_KEEPINTVL=15`, `TCP_KEEPCNT=4` on Android.
   Requires NDK setsockopt JNI call (Android does not expose
   TCP_KEEPIDLE through Java sockets API).
2. **VPS-side**: add `sysctls: net.ipv4.tcp_keepalive_time: 30` to the
   relay service in `deploy/docker-compose.yml`. Two-way keepalive
   defends both directions of the NAT entry.
3. **Verify**: rerun matrix. If voice now works, layer 2 was
   sufficient.

### Layer 3 — Diagnostics if layers 1+2 fail (1 day)

If reset cycle persists, run the tcpdump experiments in
`04-network-diagnostic-plan.md`. The decision tree there will identify
exactly who is sending the RST and why. Likely outcomes and follow-ups:

- **TSPU / Russian DPI involvement** → fake-TLS or
  Cloudflare-fronted transport (Telegram MTProto plays this game)
- **Hetzner upstream filtering** → contact support with PCAP
- **CGN that ignores keepalives** → push UnifiedPush into Phase 5
  acceleration; this is the architectural answer

### Layer 4 — Architectural changes (Phase 5, ~Feb 2027)

Already planned: **UnifiedPush integration**. This is the
production-correct answer regardless of the layer 1-3 outcome,
because it eliminates the requirement for a persistent WebSocket
during background idle. Not blocking on this for Alpha 2.

## Rejected hypotheses

- **H: Tecno HiOS firmware radio parking is the cause.** Refuted by
  Test 1 emulator regression. Tecno firmware can amplify the symptom
  but is not the root cause. ADR-013 needs revision.
- **H: 3s ping prevents radio parking.** Refuted by data — 3s ping
  made emulator regress without affecting Tecno favourably enough to
  enable voice delivery.
- **H: Need full transport protocol change (HTTP/2 long-poll, QUIC).**
  Too aggressive for current symptom. Reserve for if Layers 1-3 fail.

## Open questions for Vladislav

1. **Mobile carrier identity.** What ISP does the Tecno phone use? If
   Russian carrier (Megafon, MTS, Beeline, Tele2, Yota), the NAT
   timeout values vary and TSPU may be in path. This affects layer 3
   choice.
2. **VPS access.** Vladislav has SSH to `relay.phntm.pro` per memory
   note. Confirm before scheduling layer 1 step 2 and layer 3
   experiments.
3. **Approval for layer 2.** Adding NDK setsockopt JNI is real
   engineering work (~1 day). Should wait for layer 1 result first.

## Action items — proposed order

1. **(now)** Revert `PING_INTERVAL_MS` to 10000ms locally, rebuild APK,
   commit on a branch
2. **(Vladislav)** SSH to VPS, run the Caddy verification commands from
   `01-server-audit.md` priority 1
3. **(Vladislav)** Re-run 4-test matrix with reverted ping
4. **(if not fixed)** Execute `04-network-diagnostic-plan.md`
   experiments 1, 2, 4, 5 (skip 3 unless reset is confirmed correlated
   with bursts)
5. **(if confirmed CGN/middlebox)** Implement layer 2 TCP keepalive on
   client + VPS sysctls
6. **(if all of above fail)** Architectural review: accelerate
   UnifiedPush, or evaluate HTTP/2 long-poll fallback

## Status

- Phase 1 research **complete** as of 2026-05-04
- 4 deliverable docs written under `docs/research/transport-investigation-2026-05-04/`
- Awaiting Vladislav approval on action plan and VPS access for layer 1 step 2
