# ADR-020: Adaptive Transport Selection

**Status:** Accepted (Phase 1: this PR drops the compile-time mutex; Phase 2 in
follow-up PR ships the runtime TransportManager + Privacy Mode wiring)
**Date:** 2026-05-09
**Deciders:** Vladislav Liudvig (solo author)
**Related:** [ADR-016](ADR-016-tor-unified-push-hybrid-transport.md) (Tor +
UnifiedPush hybrid), [ADR-019](ADR-019-Xray-REALITY-Outer-Transport.md) (Xray
REALITY outer transport)
**Supersedes:** the compile-time `USE_TOR` / `USE_XRAY` mutual-exclusion
introduced in ADR-019 §"Phase 2 deliverables"

---

## Context

PHANTOM currently picks its outer transport at build time:

| BuildConfig | Path |
|---|---|
| `USE_XRAY=true` | embedded libXray SOCKS5 → REALITY tunnel → Hetzner :8443 → Caddy → relay |
| `USE_TOR=true` | embedded kmp-tor → onion circuit → onion address :80 → relay |
| neither | direct `wss://relay.phntm.pro/ws` |

`build.gradle.kts` errors out if both flags are true. Selection happens once
per service start in `PhantomMessagingService.onStartCommand`: a `when` block
starts the chosen subsystem, waits for SOCKS readiness with a timeout, then
hands the port to `KtorRelayTransport.connect`. On failure the service aborts
the whole connect — there is no fallback path.

This worked for the validation milestone (Stage 5E proved REALITY bypasses
TSPU on Russian MTS, 2026-05-07) but it does not scale to the product use case:

* **A Russian user without VPN** needs REALITY first; falling back to direct
  WSS is a waste of time (TSPU drops it). Falling back to Tor onion via
  Hetzner WebTunnel also fails (TSPU 16-KB curtain on Hetzner CIDR).
* **A user on a clean network** wants direct WSS first (lowest latency,
  no per-envelope REALITY framing overhead, smaller battery footprint).
  Falling through REALITY when direct works is overhead they did not ask for.
* **A user opting into maximum unlinkability (Ghost mode in the Privacy Mode
  spec)** needs Tor only — REALITY identifies the user-to-Hetzner correlation,
  direct WSS reveals it to every middlebox.
* **A user moving between networks** (home Wi-Fi → mobile → café) crosses
  multiple of these regimes in one session. The compile-time choice cannot
  follow them.

The choice between transports is therefore not static. It is a function of
(a) the user's stated Privacy Mode preference, (b) the network the device is
currently on, and (c) which paths empirically succeed from the device's
current position. This ADR introduces the runtime mechanism that combines
all three.

## Decision

Replace the compile-time `USE_TOR` / `USE_XRAY` mutex with a runtime
`TransportManager` driven by a Privacy Mode setting and an in-memory
preference for the most recently successful path.

### Privacy Modes (3, mapped to strategies)

| Mode | Strategy | Ordered fallback chain |
|---|---|---|
| **Standard** (default) | `DIRECT_FIRST` | direct WSS → REALITY → Tor onion |
| **Private** | `REALITY_FIRST` | REALITY → Tor onion |
| **Ghost** | `TOR_FIRST` | Tor onion only (no fallback) |

Standard prioritises latency on clean networks while still making the app
usable on TSPU-restricted networks via the REALITY fallback.

Private skips direct WSS entirely — for users who assume any clearnet WSS to
a known relay endpoint is a relay-side correlation surface.

Ghost is the maximum-unlinkability mode. If Tor cannot bootstrap (TSPU
suppression of all bridges, broken relay, etc.) the connect attempt fails
with a user-visible error rather than silently downgrading. **No automatic
fallback out of Ghost** — silent downgrade would be a privacy regression
the user explicitly opted out of.

### TransportManager

```
class TransportManager(
    private val direct:  () -> Transport,
    private val reality: () -> Transport,   // wraps XrayService
    private val tor:     () -> Transport,   // wraps TorService
    private val config:  LocalConfig,
)
```

`connect(strategy: Strategy): Result<ConnectedTransport>` walks the strategy's
ordered chain:

1. Build the next transport in the chain.
2. Start it; wait for SOCKS readiness with a per-kind budget that allows for
   cold-start native-init time (split timeout — see §"Per-kind timeouts" below).
3. Probe the relay's `/health` endpoint through the resulting SOCKS port
   (cheapest-possible round trip that proves the path works end-to-end —
   not just that the local SOCKS listener bound). Probe budget is uniform
   `PROBE_TIMEOUT_MS = 5_000` ms.
4. Success → record `lastWorkingTransport` + `lastSuccessAt` in
   `LocalConfig`; return the connected transport.
5. Failure → tear down, log the reason, advance to the next entry in the chain.
6. Chain exhausted → return `Result.failure(NoTransportReachableException)`.

#### Per-kind timeouts (split prepare vs probe)

The chain walk uses a split timeout: cold subsystem boot needs much longer
than the steady-state probe. A previous draft used a single 5 s budget
covering both — that would have aborted the chain on first launch under
MTS Tecno because libXray cold init takes ~30 s; regression caught in
review before merge.

| Kind | Prepare budget | Reason |
|---|---|---|
| `Direct` | 0 ms (no subsystem) | nothing to start |
| `Reality` | `REALITY_PREPARE_TIMEOUT_MS = 30_000` ms | libXray gomobile JNI cold init (~30 s) + REALITY handshake; warm restarts return in ms |
| `Tor` | `TOR_PREPARE_TIMEOUT_MS = 600_000` ms | Briar wrapper + bridge bootstrap on a censored network (5–8 min on a fresh operator-controlled bridge); warm restarts in seconds |

Probe budget is uniform 5 s for every kind — once the path is up, the
relay's `/health` round-trip is sub-second.

### Memory of last working transport

`LocalConfig.lastWorkingTransport` is a hint, not a binding. On the next
connect, if the hint is still valid for the current strategy, the chain is
re-ordered to put `lastWorkingTransport` first — saves a 5-second timeout
when nothing has changed. The hint resets after `LAST_SUCCESS_TTL_MS =
24 * 3600 * 1000` ms (24 hours) so a long-stale value cannot pin the user
to a transport that is now broken.

### Schema migration

`LocalConfig` (existing schema, persisted via `SharedPreferences`) gains four
fields:

| Field | Type | Default | Purpose |
|---|---|---|---|
| `privacyMode` | enum (Standard/Private/Ghost) | Standard | Drives strategy choice |
| `lastWorkingTransport` | enum (Direct/Reality/Tor) nullable | null | Reorder hint |
| `lastSuccessAt` | Long (epoch ms) nullable | null | Hint TTL |
| `transportFailureCount` | Int | 0 | Accumulated failures since last success — surfaces stuck-state UI when > N |

Migration is non-destructive: missing fields are read as defaults so existing
installations get `Standard` mode + null hints automatically.

### Connection state machine

`TransportState` already exists (`Disconnected`, `Connecting`, `Connected`).
This ADR adds two:

| State | When |
|---|---|
| `Reconnecting` | After a `Connected` session drops; the manager is walking the chain again starting from the previous `lastWorkingTransport` |
| `Failed(reason)` | Whole chain exhausted with no success; the user-facing UI surfaces a message — currently chosen transport, what was tried, and a retry CTA |

`Failed` is reached only in Ghost mode (no fallback chain) or when every
transport in the strategy is genuinely unreachable. The retry CTA bumps
`transportFailureCount` and re-runs the chain.

### Mode-switch handling

Changing `privacyMode` while connected:

1. Persist the new mode to `LocalConfig`.
2. Tear down the active transport gracefully (existing `disconnect()` flush
   path from F-09 stays in force).
3. Walk the new strategy's chain.
4. Surface the active transport in the foreground service notification so
   the user can verify the switch took effect.

### What this ADR does not do

* It does not introduce new transports. The three already exist
  (direct WSS, REALITY via libXray, onion via kmp-tor).
* It does not change the WebSocket auth (still per-user signed challenge,
  ADR-027) or the message envelope shape (still sealed sender + Double
  Ratchet).
* It does not change the relay. The relay sees the same WSS connection;
  the only new traffic shape is repeated `/auth/challenge` probes during
  fallback walks (cheap, idempotent).
* It does not implement multi-server REALITY fan-out (ADR-021 territory)
  or pluggable transports beyond the three modes (ADR-015 territory).

## Consequences

**Positive:**
* The user's Privacy Mode preference is honoured at runtime, not baked into
  the APK at build time. One APK works for clean-network users and
  TSPU-restricted users.
* Network changes mid-session no longer permanently strand a user on a path
  that stopped working; the chain probes again.
* Ghost mode delivers on its privacy promise: no silent downgrade out of Tor,
  ever.

**Costs:**
* Each connect generation may now do up to three SOCKS-port-bind +
  `/auth/challenge` round trips before reaching the relay's WS upgrade.
  Worst case: ~15 s on initial connect (3 × 5 s timeout). Memory of last
  successful transport reduces this to ~1 s on the steady state.
* The build matrix collapses: there is no longer a "Tor build" or
  "Xray build". Both subsystems compile in always. APK size grows by the
  Tor binary size (~25 MB in the kmp-tor native bundle); REALITY libXray
  is already always-bundled.

**Migration impact:**
* `BuildConfig.USE_TOR` and `BuildConfig.USE_XRAY` survive this PR (Phase 1)
  for backward compatibility with PhantomMessagingService's existing
  selection logic. Phase 2 (next PR) replaces that logic with
  TransportManager and removes the BuildConfig fields.
* No wire-format change. No schema-breaking change. Existing installations
  pick up `privacyMode = Standard` on first launch after the upgrade.

**Threat-model deltas:**
* DIRECT_FIRST in Standard mode means a relay observer sees the user's IP
  on the first probe. This was already true for non-Tor / non-REALITY
  builds. Documented in `docs/threat-model/Threat_Model_v0.md`.
* REALITY_FIRST in Private mode hides the IP from the relay (REALITY
  origin is the Hetzner box) but the Hetzner box correlates user-to-relay.
  Same trust posture as the always-on Xray builds shipped today.
* TOR_FIRST in Ghost mode places trust in the Tor consensus + the relay's
  onion service. No clearnet exposure of the user IP.

## Implementation phases

### Phase 1 (this PR)

* This ADR document.
* `build.gradle.kts`: drop the `USE_TOR && USE_XRAY` mutual-exclusion error.
  Both can now be true at build time. Default flags unchanged.
* `PhantomMessagingService.onStartCommand`: a TODO comment identifies the
  exact lines to be replaced by `TransportManager` in Phase 2. No behaviour
  change (the existing `when` block still runs and still picks the single
  enabled transport).

### Phase 2 (follow-up PR)

* `LocalConfig` schema migration (4 new fields).
* `TransportManager` + `Strategy` enum + `ConnectedTransport` data class.
* `PhantomMessagingService` rewires to delegate to `TransportManager`.
* Connection state machine adds `Reconnecting` + `Failed(reason)`.
* `BuildConfig.USE_TOR` and `USE_XRAY` removed.

### Phase 3 (follow-up PR)

* Privacy Mode UI in Settings (selector + first-time explainer dialog +
  status indicator in the foreground notification).
* Mode-switch graceful reconnect.
* Test matrix (8 scenarios from the original Track 2B plan).
* `PRIVACY_MODE_BEHAVIOR.md` + `KNOWN_ISSUES.md` + `README.md` updates.
