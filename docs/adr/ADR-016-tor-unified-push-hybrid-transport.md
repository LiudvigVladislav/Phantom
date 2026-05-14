# ADR-016: Tor + UnifiedPush hybrid transport architecture

Status: accepted (2026-05-04, feat/tor-unified-push-transport)
Layer: shared/core/transport (KMP), apps/android, services/relay,
services/ntfy (new), deploy
Supersedes / extends: ADR-001 (System Boundaries),
ADR-014 (TCP keepalive — kept as defense-in-depth on direct WSS)

## Context

PHANTOM's design goal is "works without VPN, anywhere" — including
restrictive networks (Russia, Iran, China). Two engineering problems
have so far blocked that goal:

1. **МТС WiFi silent drops.** Russian carrier path drops the WSS
   connection silently every ~50-120 s, breaking voice messages
   without VPN. ADR-014 TCP keepalive helped (50 s → 120 s) but did
   not solve. Cause is stateful network element along the path that
   VPN bypasses via tunnel keepalives. See
   `docs/research/transport-investigation-2026-05-04/`.
2. **Battery cost of always-on connection.** Without push wakeup,
   the foreground service must hold a TCP socket all the time. On
   aggressive-OEM Android (Tecno HiOS + Russian carriers) this
   produces the radio-parking and reconnect-storm symptoms documented
   in ADR-010 / ADR-011 / ISSUE-013.

Three architectures considered, three rejected explicitly:

- **Cloudflare Tunnel.** Rejected. Threat-Model conflict (US
  corporate gatekeeper sees per-connection metadata), Russia
  reliability (Roskomnadzor blocks Cloudflare ranges
  intermittently since 2022), public-positioning conflict with
  the project's "no third-party metadata" claim.
- **Russian VPS relay.** Rejected. Out of policy scope.
- **More aggressive app-level WSS pings (3 s).** Tried, rejected.
  Made the Pixel emulator regress to the same 50 s break cycle
  on the same network — ping frequency past a threshold triggers
  middlebox heuristics. See ADR-014 background.

The fourth option — Tor + self-hosted UnifiedPush — went through
feasibility research with three parallel agents
(`docs/research/tor-feasibility-2026-05-04/`) and three NO-GO checks
(`docs/research/tor-feasibility-2026-05-04/no-go-checks/`). All
viability gates passed. This ADR records the decision.

## Decision

PHANTOM adopts a **two-channel transport architecture**:

- **Wakeup channel** — UnifiedPush over WSS, served by a self-hosted
  ntfy distributor at `ntfy.phntm.pro`. Distributor knows only an
  opaque per-install token and delivery timing. Push payload is a
  single null byte; no metadata leaks via size.
- **Data channel** — Tor v3 onion service serving the existing
  PHANTOM relay protocol. Client uses kmp-tor 2.6.0 with bundled
  Tor 0.4.9.5; connects via SOCKS5 to the relay onion address;
  speaks the same WSS protocol on top.

Three Privacy Modes selectable in app settings:

| Mode | Wakeup | Data path | Calls | Default |
|---|---|---|---|---|
| **Auto** (default) | UnifiedPush | direct WSS first; Tor on fallback | direct WSS only | yes |
| **Always-Tor** | UnifiedPush | Tor onion service from connect | disabled with explicit UX | opt-in |
| **Never** (legacy) | UnifiedPush | direct WSS only, no Tor | direct WSS only | opt-in |

### Why hybrid (not Tor-only)

Briar shows pure-Tor: works for messaging, but the always-on Tor
daemon costs 5-15 % battery / 24 h on Wi-Fi and 20-30 % on flaky
cellular (open issue since 2017). Unacceptable as default.

UnifiedPush solves the wakeup problem without any third party (we
self-host ntfy on the same Hetzner VPS as the relay). The
distributor sees push timing only; never identity, never peer,
never message content.

Combined: PHANTOM gets metadata-privacy property of Tor on the
data plane, plus battery efficiency comparable to Signal/WhatsApp
(2-3 % / 24 h passive baseline), without any commercial gatekeeper.
This is the unbuilt-yet combination that no production messenger
ships today — Briar = Tor, no push; Signal = push via FCM, no Tor.

### Why calls remain direct WSS

Tor is TCP-only. WebRTC (DTLS-SRTP, ICE, RTP) is fundamentally UDP.
Briar, Cwtch, Ricochet Refresh — three FOSS Tor messengers, three
deliberate decisions to ship no voice/video. This is architectural,
not a choice we can engineer around at this layer.

PHANTOM calls in **Always-Tor** mode are explicitly disabled with
a clear UX message ("calls require direct connection — switch out
of Privacy Mode to call"). Calls in **Auto** mode use the same
direct WSS code path as today. A separate research stream (post-
ADR-016) will explore QUIC/HTTP3 or other transport for calls in
restrictive networks.

### Why WSS-over-Tor (not Tor-over-WSS)

WSS-over-Tor: client opens Tor SOCKS5, tunnels TCP to the relay's
.onion address, runs WSS *inside* the Tor circuit. Relay code
unchanged on the wire — same axum WebSocket handler accepts both
direct and Tor-arrived connections.

Tor-over-WSS would mean WSS to the relay carrying inner Tor
traffic. Adds a layer for no privacy gain, breaks onion-address
authentication, increases header overhead. No precedent.

### Why single relay onion + client authorization

Per-user onions buy little at our scale and add operational
complexity (per-user keys, descriptor publication). Single relay
onion + Tor's `client authorization` feature gives enumeration
resistance: only clients holding the per-conversation auth key can
fetch the descriptor and connect. Same model SecureDrop uses.

### Why self-host ntfy (not third-party UnifiedPush)

Per `07-unified-push-integration.md`: privacy gradient places PHANTOM
at level 2 (self-hosted UP + Tor data). Third-party distributor
(public ntfy.sh, NextPush instance, etc.) sees push metadata of
every PHANTOM user — unacceptable concentration.

Self-host on existing Hetzner VPS at `ntfy.phntm.pro`. Audit per
`no-go-checks/03-ntfy-audit.md` confirmed:

- No telemetry / phone-home from server binary
- FCM/APNs SDKs linked but **dormant by default** (no key file → no
  invocation). Not removable without source fork; accepted for Alpha;
  fork-and-strip path documented for production hardening if a future
  auditor objects.
- Web UI Google-Fonts dependency mitigated by `web-root: "disable"`
  in server config (we use only the HTTP push API, not the UI).
- Egress firewall on host drops outbound to FCM/APNs/ntfy.sh so
  even if the dormant code ever activated, the packet would not
  leave.
- License Apache 2.0 / GPLv2 — compatible with PHANTOM AGPL.

### Why kmp-tor 2.6.0 (not Arti)

Per `no-go-checks/01-kmp-tor-arm32.md`: kmp-tor 2.6.0 (Apache-2.0,
05nelsonm, Feb 2026 release) ships native KMP bindings for
Android+iOS+JVM, includes binaries for all four Android ABIs
(`x86, armeabi-v7a, arm64-v8a, x86_64`) — covers Tecno/Itel ARM32
devices. Bundled Tor 0.4.9.5 + OpenSSL 3.5.5 + libevent 2.1.12.

Arti (Rust, Tor Project's official rewrite) landed onion-service
support in 1.4.x (2025) and Vanguards in 1.2.2 (August 2024), but
mobile binary size is still flagged unresolved by Tor Project.
Track Arti every 6 months; migrate when binary size and HS-hosting
maturity both pass thresholds.

API boundary in PHANTOM transport layer: the Tor client is a
plug-in (uses kmp-tor today, can swap to Arti later) so the
migration is cleanly isolated.

### Threat model consequences

Documented in detail in ADR-018 (Threat Model v0.1 revision).
Summary:

- **Removed** "VPN required for cellular Russia" caveat from
  Privacy Policy + Threat Model + KNOWN_ISSUES.md.
- **Added** explicit honesty: "Tor mode does not protect against a
  global passive adversary correlating message-arrival timing
  across networks." No oversell.
- **Added** Vanguards-Lite (Proposal 333) as built-in default
  defence against guard discovery. Full Prop 292 not deployable;
  see `no-go-checks/02-vanguards-deployability.md`.
- **Added** application-layer padding requirement for all envelope
  types (text, voice, signalling, ack) — defends against SUMo
  flow correlation by removing size-based fingerprinting.
- **Added** circuit-padding-machines reference (already shipped in
  Tor as `circpadding-builtin`) as the actual SUMo defence at the
  network layer.

## Implementation plan (~3-4 weeks single-developer for Alpha)

| Component | Effort |
|---|---|
| Server: tor daemon + onion service in docker-compose | 0.5 day |
| Server: client_authorization (Tor config) | 0.5 day |
| Server: ntfy.phntm.pro deployment + hardened config + egress firewall | 1 day |
| Server: relay → ntfy push integration on incoming envelope | 1 day |
| Android: kmp-tor integration + transport plug-in interface | 4-5 days |
| Android: UnifiedPush registration + bundled ntfy distributor | 3 days |
| Android: Privacy Modes UI (Auto / Always-Tor / Never) | 2 days |
| Android: connection wizard + bridge management UI | 2-3 days |
| Android: bundled bridges.json + CI rotation workflow (4 weeks) | 1 day |
| Android: padding all envelope types | 0.5 day |
| iOS: placeholder + design pass | deferred (post-Beta) |
| Threat Model + ADR-017 rewrites | 1-2 days |
| Test plan + Tecno trial measurements | 2 days |

Comparable to existing UnifiedPush Phase 5 estimate. Solves both
problems (network resistance + battery) in one architecture.

## Bridges and bootstrap

For first-connect in Russia / blocked networks:

1. **Bundle `bridges.json` in every release.** CI workflow rotates
   every 4 weeks (Monday cadence), pulls latest from Tor Project Moat
   API + manual @GetBridgesBot supplements, commits to
   `apps/android/src/androidMain/assets/bridges.json`, triggers fresh
   build. This is the same cadence Tor Browser uses.
2. **Snowflake primary** (largest RU cohort per Tor Project Oct-Dec
   2025 ops updates). WebRTC-fronted, hard to enumerate.
3. **WebTunnel secondary** (HTTPS-fronted). Distribution moved to
   `@GetBridgesBot` Telegram after June 2025 enumeration of bulk
   bridges.
4. **obfs4 dropped** (declining, blocked on МТС 4G in 2025).
5. **Moat (BridgeDB over domain-fronting)** for in-app refresh when
   bundled bridges age out. Domain-fronting target deferred — research
   at implementation start. **Not Cloudflare** per directive.
6. **Manual recovery:** deep-link to `@GetBridgesBot` from in-app
   "connection failed" screen.

## Calls future research

Post-ADR-016 separate research stream (not blocking implementation):

- QUIC / HTTP3 over Tor unlikely (Tor TCP-only) — but QUIC over
  direct path has its own censorship-resistance properties on UDP
- Native UDP through Tor Project's experimental UDP-in-Tor work
- WebRTC media path direct + signalling over Tor (privacy regression,
  publishes user IP for media — analyze if acceptable for restrictive
  networks where direct media may still work even when WSS doesn't)
- Federation / multi-region calls relay

Current ADR-016 explicitly does **not** solve calls in restrictive
networks. Documented honestly in user-facing copy.

## Decommission criteria

- If Arti reaches HS-hosting parity with C-tor and mobile binary
  size lands acceptable: migrate kmp-tor → Arti
- If a different transport solves both metadata privacy and
  restrictive-network reliability: re-evaluate
- ADR-016 is not a permanent commitment to Tor specifically; the
  hybrid pattern (data plane separated from wakeup plane) is the
  durable architectural choice

## Test plan

Defined in `docs/research/tor-feasibility-2026-05-04/99-recommendation.md`
and tracked in MASTER_TIMELINE_2026.md. Pass criteria:

- Voice messages of all sizes (5/15/30 s) deliver on МТС WiFi
  without VPN, in Auto mode, in <10 seconds
- Battery on Tecno Spark Go 2023 stays under 5 % / 24 h on Wi-Fi
  in Auto mode
- First-connect in simulated blocked network (manual obfs4/direct
  Tor blocking) succeeds in <90 seconds via bundled bridges
- Always-Tor mode functional on stable Pixel emulator + Tecno
  Spark Go for at least 1 hour idle without forceReconnect

Fail criteria → roll back to direct-WSS-only (current state) and
escalate.

## References

- `docs/research/tor-feasibility-2026-05-04/` — full research
  (10 docs + 3 no-go checks)
- `docs/research/tor-feasibility-2026-05-04/99-recommendation.md` —
  synthesis with seven decisions Vladislav signed off
- `no-go-checks/01-kmp-tor-arm32.md` — kmp-tor coverage GO
- `no-go-checks/02-vanguards-deployability.md` — Vanguards-Lite GO,
  full Prop 292 not deployable
- `no-go-checks/03-ntfy-audit.md` — ntfy self-host CONDITIONAL GO
  with hardened config
- ADR-014 — TCP keepalive (kept as defense-in-depth on direct WSS)
- ADR-001 — System Boundaries (extended; updated for Tor + UP)
- ADR-018 — Threat Model v0.1 revision (companion ADR; renumbered from ADR-017 in 2026-05-14 cleanup)
- KNOWN_ISSUES.md ISSUE-013 — reclassified to "resolved by ADR-016
  pending Beta validation"
