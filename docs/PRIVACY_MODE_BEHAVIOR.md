# Privacy Mode behaviour

PHANTOM has three Privacy Modes. The setting is exposed in **Settings → Privacy
Mode** and is also chosen during onboarding (Step 3 — defaults to **Standard**).
The mode drives two things:

1. **Outer-transport selection** at runtime ([ADR-020](adr/ADR-020-Adaptive-Transport-Selection.md)) —
   `TransportManager` walks a different fallback chain per mode.
2. **Read-receipt suppression** in `ChatScreen` — the legacy
   `markConversationRead(sendReceipt = …)` argument toggles per mode.

Other end-to-end-encryption guarantees (Double Ratchet, Sealed Sender,
signed-challenge WS auth) are always on — they do not depend on the mode.

---

## Mode summary

| Mode | Transport chain | Read receipts | Best for |
|---|---|---|---|
| **Standard** (default) | direct WSS → REALITY → Tor onion | Sent | Clean networks; best latency. App still reaches the relay on TSPU-restricted networks via the REALITY fallback. |
| **Private** | REALITY → Tor onion | Suppressed | Users who treat any clearnet WSS to a known relay endpoint as a relay-side correlation surface. The relay sees only REALITY-tunnelled traffic from the Hetzner endpoint, never the user's IP. |
| **Ghost** | Tor onion only | Suppressed | Maximum unlinkability. **No silent fallback** — if Tor cannot bootstrap (bridge suppression, broken relay), the connect attempt fails with a user-visible "Cannot reach relay" state rather than downgrading to a less-private path. |

The full chain definitions and per-kind timeouts live in
[`TransportStrategy`](../shared/core/transport/src/commonMain/kotlin/phantom/core/transport/TransportStrategy.kt)
and [`TransportManager`](../shared/core/transport/src/commonMain/kotlin/phantom/core/transport/TransportManager.kt)
companion constants.

---

## What changes when you switch modes

Switching modes from the Settings selector triggers:

1. The new mode is persisted to `TransportPreferences.privacyMode` (typed
   enum) AND mirrored into the legacy `privacy_mode` SharedPreferences key
   that `ChatScreen` reads for the read-receipt gate.
2. The active WebSocket is torn down via `KtorRelayTransport.disconnect()`.
3. The active outer-transport subsystem (Tor or Xray) is stopped via
   `TransportManager.release()`.
4. The foreground service is re-started — `onStartCommand` fires again, the
   guarded `connectStarted` flag has flipped back to false, and the next
   `TransportManager.connect()` walks the chain implied by the new mode.

The user briefly sees the foreground notification text transition through
`Connecting via …` → `Online via …`. Total switch time depends on whether
the new mode's first-choice transport needs a cold start (≈30 s for REALITY,
up to several minutes for Tor with fresh bridges) or is already warm.

---

## Ghost-mode confirmation

Picking Ghost from the Settings selector for the first time opens a
confirmation dialog:

> Switch to Ghost mode? Ghost routes every message through Tor only. If Tor
> cannot bootstrap on the current network the app will show "Cannot reach
> relay" instead of silently falling back to REALITY or direct WSS — that
> silent downgrade would defeat the privacy promise you are opting into.

Confirming the dialog applies the mode; cancelling leaves the previous mode
in force. There is no second prompt on subsequent Ghost switches in the same
session.

---

## When the chain walk happens

`TransportManager.connect()` is called once per service connect generation
(cold app start, foreground-service restart, mode switch, network-change
reconnect). It walks the mode's chain in order:

1. **Try the first kind** in the chain. Start the subsystem (Direct = no-op,
   REALITY = libXray cold start ≤ 30 s, Tor = bridge bootstrap ≤ 600 s).
   Probe the relay's `/health` through the resulting SOCKS port (5 s budget).
2. **Probe success** → record `lastWorkingTransport` + `lastSuccessAt`,
   reset `transportFailureCount`, return the connected transport.
3. **Probe fail** → tear down, advance to the next kind in the chain.
4. **Chain exhausted** → throw `NoTransportReachableException`. The
   foreground notification surfaces "Cannot reach relay (tried N)". The
   user can retry from the Settings UI or by restarting the app.

The `lastWorkingTransport` hint reorders the chain on subsequent connects to
put the previously-successful kind first — saving the cold-start cost. Hints
expire after 24 hours so a long-stale value cannot pin the user to a path
that is no longer reachable.

---

## What the relay sees per mode

| Mode | `from` field on envelope | Source IP | Notes |
|---|---|---|---|
| Standard (Direct path active) | empty (Sealed Sender) | user device | Same posture as Alpha 1: relay learns IP from the TCP connection. |
| Standard / Private (REALITY active) | empty (Sealed Sender) | Hetzner Xray VPS | Relay sees the Xray VPS as the TCP origin. The Xray VPS sees the user's IP. Trust posture: same as the always-on Xray builds shipped through Stage 5E. |
| Ghost (Tor active) | empty (Sealed Sender) | Tor exit (or onion routing — no exit when using the relay's onion address) | Onion-address path: relay sees only the rendezvous circuit's local IP. No clearnet exposure. |

The Sealed Sender envelope (ADR-025 / ADR-026 / ADR-027) hides the `from`
identity in every mode, including Standard. The mode choice affects the
IP-layer correlation, not the application-layer crypto.

---

## Storage

`TransportPreferences` is persisted in the existing `phantom_prefs`
SharedPreferences (the same file the username + identity row already use)
under keys prefixed with `transport.`:

| Key | Type | Default | Set by |
|---|---|---|---|
| `transport.privacy_mode` | enum name string | `Standard` | Onboarding step 3, Settings selector |
| `transport.last_working` | enum name string, nullable | null | `TransportManager.connect` on success |
| `transport.last_success_at_ms` | long, nullable | null | same |
| `transport.failure_count` | int | 0 | reset on success, bumped on chain exhaustion |
| `privacy_mode` (legacy) | string `"Standard"` / `"Private"` / `"Ghost"` | `Standard` | mirrored from `transport.privacy_mode` for backward-compat with `ChatScreen` |

Migration is non-destructive — missing keys read as defaults so existing
installations pick up `Standard` mode + null hints automatically.

---

## Test matrix (cross-device, manual)

The eight scenarios from the Track 2B plan that exercise this end-to-end:

| # | Setup | Expected |
|---|---|---|
| 15 | RU Tecno without VPN, Standard mode | Direct fails ~5 s, REALITY succeeds, hint recorded → Online via Reality |
| 16 | RU Tecno **with** VPN, Standard mode | Direct succeeds, hint Direct → Online via Direct |
| 17 | EU emulator, Standard mode | Direct succeeds, hint Direct |
| 18 | Network change mid-conversation (Wi-Fi → mobile) | Connection drops, next connect re-probes, hint may switch |
| 19 | REALITY server restart simulation | Active socket dies, fallback chain tries the same kind first (warm-cached) then advances if probe fails |
| 20 | Privacy Mode switch while connected (any → any) | Notification shows brief Connecting then Online via new kind |
| 21 | Ghost mode without Tor available | Connect aborts with "Cannot reach relay (tried 1)" — no silent downgrade |
| 22 | All transports fail (airplane mode + bad relay endpoint) | Chain exhausts, `transportFailureCount` increments, user-visible error |

These are the "B6 test matrix" deliverable from the original Track 2B plan;
results recorded in `KNOWN_ISSUES.md` once Vladislav runs them.

---

*Last reviewed: 2026-05-09 (ADR-020 Phase 3 ship).*
