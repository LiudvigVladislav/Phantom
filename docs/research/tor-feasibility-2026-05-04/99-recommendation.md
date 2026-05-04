# Recommendation — Tor + UnifiedPush as PHANTOM transport

Status: feasibility synthesis 2026-05-04
Pending: Vladislav GO / NO-GO / ALTERNATIVE decision before any
implementation work.

## Decision summary

**GO — but with a hard condition.** Adopt Tor as transport **only as
half of a Tor + UnifiedPush hybrid architecture**. Tor alone is not
viable because of battery cost (R3). The combination is what every
agent independently arrived at — and what no shipped FOSS messenger
has actually deployed yet (Briar = pure Tor with battery complaints
since 2017; Cwtch = Tor with simpler design but same battery cost;
Signal = battery-friendly via FCM but US-corporate metadata leak).

**Voice messages: yes through Tor.** 1.6 MB burst lands in 2-10 s on
typical hidden-service circuits per Tor 0.4.7+ measurements. Above
acceptable threshold.

**Voice/video calls: no through Tor.** Tor is TCP-only; WebRTC needs
UDP. All three Tor messengers checked (Briar, Cwtch, Ricochet
Refresh) deliberately do not ship voice — this is architectural, not
oversight. Calls remain on direct WSS in our model.

**iOS deferred.** No blocker, but deserves separate design pass when
iOS port begins.

## Recommended architecture

### Transport layer

```
Default mode (Auto, recommended for most users):
    incoming push (UnifiedPush, ~2% battery/day passive listening)
        ↓ wakes app
    direct WSS to relay.phntm.pro (fast path, works on most networks)
        ↓ if direct WSS fails repeatedly:
    Tor onion service via SOCKS5 (privacy fallback)
        ↓ if Tor bridges blocked:
    user notified, can switch to manual bridge or accept VPN advice
```

```
Always-Tor mode (opt-in, power users):
    incoming push (UnifiedPush, same)
        ↓ wakes app
    Tor onion service from start
    direct WSS never used
    calls disabled in this mode
```

```
Never mode (legacy / debugging):
    direct WSS only, no Tor
    same as today
```

### Crypto / identity

Per `06-security-analysis.md` and `04-precedents-analysis.md`:

- **Identity stays X25519.** No change. Onion address is **transport
  metadata**, never bound to identity. Migration is non-event for
  existing users.
- **Single relay onion** + per-user `client authorization` (random
  short keys) for enumeration resistance.
- **WSS-over-Tor**, not Tor-over-WSS. Client opens Tor SOCKS5,
  connects to onion-served WSS endpoint. Same WebSocket protocol
  inside.
- **Application-layer padding** extended to all envelope types (voice
  chunks already padded to 8 KB; extend to text, ack, signalling).

### Push wakeup (UnifiedPush, self-hosted)

Per `07-unified-push-integration.md`:

- **Self-host ntfy** on existing Hetzner VPS at `ntfy.phntm.pro`.
- **Bundle ntfy embedded distributor** in PHANTOM APK (no Google
  Play Services dependency).
- **Push payload = single null byte.** No metadata leakage via
  payload size. Payload existence wakes app; app pulls actual
  message from relay over Tor.
- **Distributor sees**: opaque per-install token + push timing.
  Never sees identity, content, peer identity, message content.
- **Privacy gradient** — PHANTOM positioned at level 2 (self-hosted
  UnifiedPush + Tor data). Stronger than Signal/WhatsApp/Telegram on
  metadata.

### Bridge / first-connect bootstrap

Per `03-bridges-and-censorship.md`:

- Bundle `bridges.json` in every release. CI workflow rotates every
  4-6 weeks (same cadence as Tor Browser).
- Snowflake primary, WebTunnel secondary, obfs4 dropped.
- Moat (BridgeDB over domain-fronting) for in-app refresh. Domain-
  fronting target = explicitly NOT Cloudflare (per directive).
  Candidates: Fastly, Bunny CDN, Microsoft Azure Front Door
  (research deeper before commit).
- Connection wizard: clear progress, honest failure messages,
  fallback chain visible.
- Manual recovery: deep-link to `@GetBridgesBot` Telegram from
  failure screen.

## Effort estimate (realistic)

Building on `kmp-tor 2.6.0` (Android+iOS+JVM ready) and self-hosted
ntfy:

| Component | Effort | Owner / dependency |
|---|---|---|
| Server: tor daemon + onion service in docker-compose | 0.5 day | relay-builder |
| Server: client_authorization + vanguards config | 0.5 day | relay-builder + security-reviewer |
| Server: ntfy.phntm.pro deployment | 1 day | relay-builder |
| Server: relay → ntfy push integration on incoming envelope | 1 day | relay-builder |
| Android: kmp-tor integration + transport plug-in interface | 4-5 days | kmp-builder |
| Android: UnifiedPush registration + bundled ntfy distributor | 3 days | kmp-builder |
| Android: Privacy Modes UI (Auto / Always-Tor / Never) | 2 days | ui-prototyper |
| Android: connection wizard + bridge management UI | 2-3 days | ui-prototyper + kmp-builder |
| Android: bundled bridges.json + CI rotation workflow | 1 day | kmp-builder |
| Android: padding all envelope types (extend voice pattern) | 0.5 day | kmp-builder |
| iOS: placeholder + design pass | deferred | (post-Beta) |
| Threat Model + ADR rewrites (ADR-016 + Threat Model v0.1) | 1-2 days | security-reviewer |
| Test plan + Tecno trial measurements (battery, latency, voice) | 2 days | manual QA |

**Total: ~3-4 weeks single-developer time** for Alpha-quality, plus
1-2 weeks production hardening before Beta.

This is **comparable to original UnifiedPush Phase 5 estimate**
(~3-4 weeks) but delivers BOTH the network-resistance and the
battery solution simultaneously.

## Decisions Vladislav needs to make before kickoff

Numbered for clarity. Each one is a checkbox before any
implementation branch is created.

1. **GO on hybrid Tor + UnifiedPush?** Yes / No / Phase further.
2. **Library: kmp-tor 2.6.0 vs Arti.** kmp-tor recommended for
   today (battle-tested, KMP-ready); Arti tracked for 2027
   re-evaluation. Confirm.
3. **Calls policy.** Confirm: calls remain direct-WSS only; "calls
   disabled" UX in Always-Tor mode; Phase 5 does not bring calls
   over Tor.
4. **Domain-fronting target for Moat fallback.** NOT Cloudflare per
   directive. Pick: Fastly / Bunny / Azure Front Door / other.
   Recommend deferring this decision to implementation start —
   research separately at that point.
5. **Bridge rotation cadence + CI ownership.** Recommend: 4-week
   rotation, CI workflow runs every Monday, fetches latest from
   `bridges.torproject.org` Moat API + manual @GetBridgesBot pulls,
   commits to `apps/android/src/androidMain/assets/bridges.json`,
   triggers fresh build. Confirm cadence.
6. **UnifiedPush distributor bundling.** Recommend: bundle ntfy
   embedded; recommend installing standalone ntfy app for power
   users; document in onboarding. Confirm.
7. **Threat Model + ADR scope.** Recommend: ADR-016 (Tor + UP hybrid
   architecture), ADR-017 (revised Threat Model: vanguards, padding,
   correlation honesty), KNOWN_ISSUES.md ISSUE-013 reclassified to
   "resolved by ADR-016 pending Beta validation". Confirm.

## NO-GO scenarios (if any of these surface during kickoff)

Stop and reassess if:

1. `kmp-tor 2.6.0` ARM32 (armeabi-v7a) coverage missing and Tecno/
   Itel users on ARM32 are >5% of target audience. (Verifiable
   before starting — check `kmp-tor-resource` ABI matrix.)
2. Vanguards Proposal 292 not actually deployable on a stable Tor
   release (some features are experimental). Verify in tor 0.4.8+
   release notes.
3. Self-hosted ntfy at ntfy.phntm.pro pulls in any third-party
   service we missed. Audit.
4. Tor Project deprecates kmp-tor's bundled C-tor faster than Arti
   matures — vendor risk crystallizes earlier than expected. Mitigate
   by tracking both.

## Alternatives if Vladislav says NO-GO

Per priority:

A. **Russian VPS relay** — politically rejected per directive. Off
   table.
B. **UnifiedPush only (no Tor)** — solves battery, doesn't solve
   metadata privacy or МТС silent drops. Same as today's planned
   Phase 5. Acceptable as fallback if Tor proves unworkable.
C. **WebTunnel + custom server in friendly jurisdiction** — research
   project, ~2 months work, less precedent.
D. **Continue waiting for UnifiedPush Phase 5 only** — accept VPN-
   required-on-MTS as documented limitation. User-hostile but
   honest.

## Why this recommendation, summarized

Three independent agents, three different research domains, **all
arrived at the same architecture**: Tor for data plane + UnifiedPush
for wakeup, with self-hosted distributor.

This is not because they coordinated — it is because the constraints
(privacy, battery, mobile, Russian network) collapse the design
space to one coherent answer. Briar shows pure Tor is too battery-
expensive. Signal shows FCM is too metadata-leaky. The hybrid is the
unbuilt-yet answer that combines the two FOSS solutions.

Effort is comparable to UnifiedPush-only Phase 5 (already on
roadmap). Outcome is strictly better:
- Solves battery (UnifiedPush)
- Solves МТС silent drops (Tor onion bypasses TSPU/CGN)
- Strengthens privacy positioning (no third-party metadata on
  default-data-path)
- iOS port not blocked
- Calls policy unchanged from today

**Recommendation: GO. Kick off `feat/tor-unified-push-transport`
branch when Vladislav has answered the seven decisions above.**
