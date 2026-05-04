# Tor adoption — risk assessment

Status: feasibility output 2026-05-04
Owners: this synthesis derived from `01-` through `07-` deliverables;
references back where each risk surfaced.

## R1. Tor blocked in Russia (intermittent or sustained)

**Surface.** Roskomnadzor blocks direct Tor since December 2021.
Pluggable transports (PT) churn: obfs4 declining, WebTunnel bridges
enumerated June 2025 (per `03-bridges-and-censorship.md`).

**Probability.** Medium-high that **some specific PT will be blocked
at any given time**. Low that **all PTs simultaneously**.

**Mitigation chain (defence in depth):**
- Bundle fresh `bridges.json` in every PHANTOM release; rotate every
  4-6 weeks (CI workflow). Same pattern Tor Browser uses.
- Snowflake as primary (largest RU cohort per Tor Project Oct-Dec
  2025 ops updates) — WebRTC-fronted, hard to enumerate.
- WebTunnel as secondary (HTTPS-fronted, harder to fingerprint than
  obfs4).
- Moat (BridgeDB over domain-fronting) for in-app refresh when
  bundled bridges age out.
- Manual recovery: deep-link to Telegram `@GetBridgesBot` from the
  in-app "connection failed" screen.
- **Direct WSS remains available as fallback transport** in non-
  restricted networks — `Always-Tor` mode is opt-in, not forced.

**Worst case.** PHANTOM falls back to direct WSS; users on МТС
without VPN see the current voice-delivery problem until either Tor
unblocks or UnifiedPush acceleration ships. Same state as today, no
regression.

## R2. Performance degradation (voice + interactive UX)

**Surface.** Tor adds 280-460 ms one-way delay (`01-tor-architecture.md`
citing Tor Project measurements). Voice burst of 1.6 MB takes 2-10 s
through a typical hidden-service circuit. Calls are architecturally
impossible through Tor (TCP only, WebRTC needs UDP).

**Probability of UX impact.** High for first-time-perceived experience.
Voice messages "feel slow", calls won't work in Always-Tor mode.

**Mitigation:**
- Calls **disabled** when Tor mode is active. UI explicitly explains
  why ("calls require direct connection — switch off Privacy Mode to
  call"). Honest, no silent failure.
- Voice messages still work but show progress indicator with
  expected-time-via-Tor messaging.
- Auto mode (recommended default) prefers direct WSS, switches to Tor
  only when direct fails repeatedly.
- Per-circuit `MaxCircuitDirtiness` tuning for stable voice bursts.

**Tradeoff accepted.** Tor mode is for users who explicitly choose
metadata privacy over feature parity. Auto mode protects non-Tor
networks from any regression.

## R3. Battery drain on Android

**Surface.** Per `02-mobile-tor-options.md`: Tor daemon running
constantly costs 5-15% battery/24h on Wi-Fi, 20-30% on flaky cellular
with bridge cycling. Briar's `reduce battery` issue has been open
since 2017 — this is not a fixable problem at the Tor-daemon level.
Compare: FCM/APNs passive listening costs ~2%/24h.

**Probability of user complaint.** Very high if Tor runs always.

**Mitigation — the architectural answer is the killer combination:**

```
UnifiedPush (passive, ~2%/24h) for wake-up signal
       ↓ wakes app on incoming message
Tor on-demand (active connection only when user engaged)
       ↓ pull queued messages from relay
goes back to passive
```

This is `07-unified-push-integration.md`'s "killer architecture"
recommendation. Battery becomes Signal-class (~2-3%/24h), not Briar-
class. PHANTOM gets metadata-privacy property without battery cost.

**Without UnifiedPush:** Always-Tor mode is power-user-only feature,
opt-in, with explicit "this will reduce battery life ~10x" warning.

## R4. iOS implementation challenges

**Surface.** `02-mobile-tor-options.md`: iOS forbids long-running
background daemons. Tor onion service cannot serve while app is
backgrounded. App Store policy is fine (Onion Browser, OnionShare
ship), but architectural patterns differ from Android.

**Probability for current scope.** Zero — PHANTOM Alpha 2 is Android-
only. iOS is post-Beta.

**Mitigation when iOS comes:**
- PHANTOM is relay-mediated, not P2P. Client doesn't need to host an
  onion service; only consume one. Solvable.
- iOS client: Tor as NetworkExtension or VPN-tunneled, integrated
  with OS background fetch + push-driven wakeup.
- Use `kmp-tor` shared component on both platforms (it supports iOS
  through native KMP — confirmed in `02-mobile-tor-options.md`).
- Defer detailed iOS Tor design to iOS-port milestone.

**No blocker for current direction.**

## R5. User experience complexity (first-time bootstrap)

**Surface.** First-time install in a Tor-blocked network. App needs
bridges. Bridge fetch needs working Tor (chicken-and-egg) unless we
bundle fallbacks.

**Probability.** Certain — every Russian user will hit this.

**Mitigation chain (per `03-bridges-and-censorship.md`):**

1. **Bundled `bridges.json` in APK** (4-6 week rotation via CI). New
   user has working bridges out of the box.
2. **Moat (BridgeDB over domain fronting)** for in-app refresh when
   bundled bridges age out. Domain fronting target chosen carefully
   (NOT Cloudflare per Vladislav directive).
3. **Telegram `@GetBridgesBot`** deep-link from the failure screen.
   Telegram is widely available in RU.
4. **Connection wizard UI** — clear "Privacy mode is connecting...
   this can take 30-60 seconds first time" with estimated progress.
   Honest failure messages (which step failed: bridge resolution?
   Tor bootstrap? circuit build?).

**Acceptable cost.** First-connect adds 30-90 seconds vs direct WSS.
Subsequent connects use cached state. Set expectations in UX.

## R6. UnifiedPush distributor unavailability

**Surface.** UnifiedPush requires a distributor app on the user's
device (ntfy app, NextPush, etc). Many users won't have one
pre-installed. Default Android has only Google FCM available.

**Probability.** High — most installs need a distributor decision at
first run.

**Mitigation (per `07-unified-push-integration.md`):**

1. **Bundle a built-in distributor** in PHANTOM (ntfy embedded). User
   gets push working without installing anything else.
2. **Recommend installing ntfy app** for power users (better cross-
   app distributor, batteries more efficient when shared).
3. **Fallback to always-on connection** for users who refuse a
   distributor. Battery cost spelled out clearly.
4. **No FCM dependency.** Hard policy — PHANTOM does not pull in
   Google Play Services even as fallback. This is core to the
   metadata-privacy positioning.

## R7. Library maturity / vendor risk

**Surface.** Two candidate libraries:
- `kmp-tor` 2.6.0 (Apache-2.0, Feb 2026 release, embedded C-tor,
  KMP-native). Recommended by `02-mobile-tor-options.md`.
- Arti (Rust, official Tor Project rewrite, onion service support
  landed 1.4.x in 2025). Recommended by `05-client-architecture.md`.

**Disagreement between agents.** Resolved as follows:

- **kmp-tor for client today** — battle-tested, KMP-ready, supports
  pluggable transports. Tor Project itself flags Arti mobile binary
  size as unresolved.
- **Arti tracking** — re-evaluate every 6 months. Migrate when binary
  size and onion-service maturity both pass acceptable thresholds.
  Plan API boundaries so swap is feasible (transport plug-in
  pattern).

**Risk.** kmp-tor maintenance falls off — single-maintainer project.
Mitigation: vendor audit before commit, contribute upstream as
needed, contingency: Arti when ready.

## R8. Threat model regression (correlation attacks)

**Surface.** Per `06-security-analysis.md`: 2024 SUMo flow correlation
attack against Tor demonstrates that single global passive adversary
can deanonymize hidden services with reasonable accuracy.

**Probability for PHANTOM threat model.** Medium for nation-state
adversaries with global passive observation. Low for the typical
PHANTOM threat model (carrier-level + state-level RU adversaries
without true global tap).

**Mitigation:**
- **Vanguards (Proposal 292) mandatory on relay** — limits guard
  rotation, reduces SUMo applicability.
- **Application-layer padding** — already done for voice 8 KB
  chunks; extend to all envelope types so size-based correlation is
  defeated.
- **Single relay onion + per-user `client authorization`** — relay
  doesn't serve onion descriptor to non-authorized clients, blocks
  enumeration.
- **Threat Model rewrite** to call out specifically: "Tor mode does
  not protect against a global passive adversary correlating
  message-arrival timing across networks." Honesty over promises.

## Cross-risk summary

| Risk | Severity | Mitigatable | Blocker for Tor adoption? |
|---|---|---|---|
| R1 Russia blocking | High | Yes (bridge chain) | No — direct WSS fallback |
| R2 Performance | Medium | Yes (Auto mode + UX) | No — Tor opt-in for messaging |
| R3 Battery | High without UP | Yes (Tor + UnifiedPush) | **Yes if UP not shipped together** |
| R4 iOS | N/A current scope | Yes (deferred) | No — Android-only Alpha 2 |
| R5 Bootstrap UX | High first-time | Yes (bundled bridges + Moat + bot) | No — well-trodden path |
| R6 No distributor | Medium | Yes (bundled in PHANTOM) | No — fallback to always-on |
| R7 Library risk | Medium | Yes (vendor + tracking Arti) | No — kmp-tor is production-ready |
| R8 Correlation | Medium | Yes (vanguards + padding) | No — Threat Model rewrite documents honestly |

**Single dominant risk: R3 battery.** Tor without UnifiedPush is not
viable as default. Tor + UnifiedPush is the architecture, not Tor
alone.
