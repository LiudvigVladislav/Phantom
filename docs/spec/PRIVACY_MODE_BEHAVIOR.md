<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (c) 2026 Willen LLC
-->

# Privacy Mode — Behavior Specification

**Status:** Approved 2026-05-05 by Vladislav (founder)
**Scope:** Single source of truth for the runtime behavior of the three Privacy Modes (Standard / Private / Ghost). All product, design, engineering and security review work derives from this document.

**Related:**
- `docs/spec/PRIVACY_MODE_COMPARISON_TABLE.md` — marketing-ready feature comparison
- `docs/spec/GHOST_MODE_TRANSITIONS.md` — state-machine for mode transitions
- `docs/spec/PRIVACY_MODE_ONBOARDING.md` — UI / copy spec for selection flow
- `docs/adr/ADR-016-tor-unified-push-hybrid-transport.md` — transport architecture
- `Design/PHANTOM_FULL_COMPOSE.md` §10 — visual design of Privacy Mode cards

---

## 1. Core principle

Privacy Mode is **the single user-facing knob** that controls four orthogonal subsystems through one product decision:

1. **Discovery** — how (and whether) other people can find you on PHANTOM.
2. **Presence** — what your contacts can observe about your activity (online dot, last seen, typing, read receipts).
3. **Push wakeup** — whether the relay can wake your device through an external channel (UnifiedPush) or only over our own WebSocket keepalive.
4. **Transport** — how your traffic egresses to the relay (direct WSS vs Tor onion via embedded bridges).

Mapping these four to **one** user choice (Standard / Private / Ghost) keeps the UX honest — the user does not have to reason about each subsystem independently. Each mode is a coherent product promise, not a configuration sandbox.

The three modes form a **monotonic privacy ladder**: Ghost ⊃ Private ⊃ Standard. Anything Private hides, Ghost also hides. Anything Standard does, Private and Ghost may or may not do, but never reveals more.

---

## 2. Mode definitions

### 2.1 Standard (free, default)

**Product promise:** "PHANTOM is a normal messenger — fast, friendly, and end-to-end encrypted by default. Your conversations are private; your presence is normal."

| Subsystem | Behavior |
|---|---|
| Discovery | Searchable by `@username`. Anyone who knows your username can send you a contact request. |
| Inbound contact requests | Allowed from anyone. User reviews and accepts/declines per request. |
| Presence (online dot, last seen) | Visible to all confirmed contacts. |
| Read receipts | **On by default**, configurable in settings (per app, not per chat). |
| Typing indicators | **On by default**, configurable. |
| Delivery receipts | On (sender sees when relay delivered envelope to recipient device). |
| Disappearing messages | **Off by default**, per-chat opt-in. |
| Push wakeup | UnifiedPush via self-hosted ntfy distributor (`ntfy.phntm.pro`). |
| Transport | **Direct WSS** preferred → **auto-fallback to Tor + bridges** if direct fails (see §3). |
| Voice / video calls | ✅ supported (WebRTC P2P, signaling over messaging channel). |
| Voice messages | ✅ supported (chunked envelopes, work over both transports). |
| File / image attachments | ✅ supported. |
| Group chats | ✅ create, join, participate. |
| Profile avatar | Visible to confirmed contacts. |
| Profile name | Visible to confirmed contacts. |
| Local DB storage | SQLCipher at-rest encryption. |
| Encrypted backup / export | ✅ supported. |
| Multi-device | ✅ supported (Beta scope). |

### 2.2 Private (free)

**Product promise:** "Quieter than Standard — no one can find you by username, and you don't broadcast typing or read receipts unless you explicitly opt in."

| Subsystem | Behavior |
|---|---|
| Discovery | **Username search returns no results.** Stealth on the discovery layer. |
| Inbound contact requests | Only from peers who already know your `identity-key` (out-of-band exchange — QR scan, paste, NFC). |
| Presence | Visible only to **confirmed** contacts (people you have explicitly accepted). |
| Read receipts | **Off by default**, configurable in settings. User who wants Telegram-like UX can turn on. |
| Typing indicators | **Off by default**, configurable. |
| Delivery receipts | On (relay-level — sender sees envelope was delivered). |
| Disappearing messages | **Default 24 h ON for new chats**, per-chat configurable. Existing chats unchanged unless user opts in. |
| Push wakeup | UnifiedPush via `ntfy.phntm.pro`. |
| Transport | Direct WSS preferred → auto-fallback to Tor + bridges. |
| Voice / video calls | ✅ supported. |
| Voice messages | ✅ supported. |
| File / image attachments | ✅ supported. |
| Group chats | ✅ create, join (by invite link only), participate. |
| Profile avatar | Visible to confirmed contacts. Optional "hide avatar" toggle in settings. |
| Profile name | Visible to confirmed contacts. |
| Local DB storage | SQLCipher. |
| Encrypted backup / export | ✅ supported. |
| Multi-device | ✅ supported. |

### 2.3 Ghost (PRO)

**Product promise:** "You disappear from PHANTOM's social layer entirely. The system cannot find you, your contacts cannot see when you are online, and every message you send self-destructs in 24 hours by default. The trade-off is that voice and video calls are unavailable in this mode."

| Subsystem | Behavior |
|---|---|
| Discovery | **Completely invisible.** Username search returns no results. Identity-key exchange is the only path. |
| Inbound contact requests | Only via direct out-of-band exchange (QR / NFC / pasted identity hash). The relay's `/discover` endpoint returns 404 for Ghost identities. |
| Presence | **Never broadcast.** Always appears offline to everyone. No last-seen. No online dot on your avatar. |
| Read receipts | **Forced off.** Setting greyed out — cannot be enabled while in Ghost mode. |
| Typing indicators | **Forced off.** |
| Delivery receipts | **Forced off** to peers. (Internally the relay still tracks delivery for ack semantics, but the sender's app does not display the "delivered" state.) |
| Disappearing messages | **Default 24 h ON for all chats**, per-chat configurable in either direction (user can change to 1 h, 7 d, 30 d, or "off" per chat). TTL counted from each message's send time. Applies to both sent and received messages. |
| Push wakeup | **Disabled.** No UnifiedPush registration with the relay. The relay's outbound push hook (`POST /push/<topic>` from `services/relay/src/push.rs`) is silently a no-op for Ghost identities. The app receives messages only when the foreground service is alive and the WebSocket is open. |
| Transport | **Always Tor + bridges.** Direct WSS is never used. Bypasses any DPI / TSPU / domain-block at the cost of latency. |
| Voice / video calls | ❌ **disabled.** When the user taps the call button: confirmation dialog (see §4.4) explains why and offers to switch modes. Incoming calls produce a missed-call notification at the next foreground sync; the Ghost user cannot return them without leaving Ghost. |
| Voice messages | ✅ supported. Slower upload via Tor circuit (see §3 for latency numbers) but does deliver. |
| File / image attachments | ✅ supported (slower over Tor). |
| Group chats | ❌ **prohibited.** Cannot create, cannot join. Existing groups: see §5.1 transition flow — user must leave all groups to enter Ghost. |
| Profile avatar | **Hidden — placeholder shown to all peers.** Even confirmed contacts see a default avatar. |
| Profile name | **Pseudonym required**, validated client-side (no real-name patterns enforced; user choice). The "Real Name" field is locked while in Ghost. |
| Local DB storage | SQLCipher (Alpha 2 scope). Plaintext-only-in-RAM mode is **deferred to Beta** — see §6 decision log Q4. |
| Encrypted backup / export | ❌ **disabled.** Anonymity = nothing leaves the device. The "export" button is greyed out. |
| Multi-device | ❌ **prohibited.** Single device is the anchor of trust. See §5.2 transition flow for what happens when a user with multiple devices switches to Ghost. |

---

## 3. Transport behavior — auto-fallback flow

This applies to **Standard and Private** only. **Ghost is always Tor**, no fallback logic.

### 3.1 Connect sequence (Standard / Private)

```
┌─────────────────────────────────────────────────────────────────┐
│ App opens → PhantomMessagingService.onStartCommand              │
│                                                                 │
│ Step 1: Try direct WSS to relay.phntm.pro                       │
│   - Window: 30 sec (production) / 5 sec (DEBUG_AGGRESSIVE_TOR)  │
│   - On WS connected → state = Connected (direct), DONE          │
│                                                                 │
│ Step 2: Window elapsed without direct connect                   │
│   - Start torService in background                              │
│   - Foreground notification: "Connecting via Tor…"              │
│   - Continue retrying direct WSS in parallel                    │
│                                                                 │
│ Step 3: Whichever path reaches Connected first, use it          │
│   - If direct connects first → cancel Tor (it stops bootstrap)  │
│   - If Tor reaches Ready first → connect WS over onion          │
│                                                                 │
│ Step 4 (post-fallback recovery — STANDARD/PRIVATE ONLY):        │
│   If we are connected via Tor and direct WSS becomes reachable  │
│   for ≥ 5 minutes continuously, swap back to direct. Notif:     │
│   "Encrypted connection active" (no "via Tor").                 │
│                                                                 │
│   Battery rationale: Tor circuits + bridges keep more sockets   │
│   open, more frequent keepalive traffic, more JNI work — direct │
│   WSS is materially cheaper. When the network proves stable     │
│   enough that Tor is no longer protecting us from anything, we  │
│   should not pay its cost.                                      │
│                                                                 │
│   GHOST never recovers to direct. Ghost = explicit privacy      │
│   contract — silently going direct would violate the user's     │
│   choice. Ghost stays on Tor for the lifetime of the session    │
│   regardless of network health.                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 Connect sequence (Ghost)

```
┌─────────────────────────────────────────────────────────────────┐
│ App opens → PhantomMessagingService.onStartCommand              │
│                                                                 │
│ Step 1: Start torService unconditionally with bridges enabled   │
│   - Foreground notification: "Connecting via Tor…"              │
│                                                                 │
│ Step 2: Wait for TorState.Ready (300 s timeout)                 │
│   - On Ready → connect WS to RELAY_ONION_URL via SOCKS5 proxy   │
│   - On timeout → state = Failed, notification "Tor unavailable" │
│     User intervention required (toggle bridges / different      │
│     network / temporarily switch mode)                          │
│                                                                 │
│ Direct WSS is NEVER attempted in Ghost. Even if WS would work   │
│ direct, Ghost users have explicitly chosen Tor — fallback to    │
│ direct would silently violate their privacy choice.             │
└─────────────────────────────────────────────────────────────────┘
```

### 3.3 Latency expectations

User-visible latency for typical operations:

| Operation | Direct WSS | Tor onion (no bridges) | Tor onion via Snowflake bridge |
|---|---|---|---|
| Cold start → connection ready | 2–5 sec | 30–90 s (cold) / 5–15 s (warm guards) | 60–120 s (cold) / 10–30 s (warm) |
| Send a text message → "delivered" tick | 100–300 ms | 500–1500 ms | 700–2000 ms |
| Receive a new message | < 1 sec | 1–3 sec | 1–3 sec |
| Upload 30-sec voice message (~270 KB) | 2–5 sec | 15–30 sec | 30–60 sec |
| Upload 2-min voice message (~1 MB) | 5–10 sec | 60–120 sec | 90–180 sec |

These are rough envelopes from the kmp-tor / onionwrapper literature plus our Test 6 measurements (2026-05-05). They are not contractual — actual numbers depend on Tor circuit selection, bridge availability, and device CPU.

---

## 4. Calls — detailed behavior

### 4.1 Why calls are mode-sensitive

Tor is a TCP-only anonymity network. WebRTC media streams are UDP. The two cannot be combined. Any call that succeeds while transport is Tor would have to use direct UDP P2P, which would expose both peers' real IP addresses to each other — defeating the anonymity Tor was protecting in the first place.

Standard and Private do not need anonymity from the relay (their identity is already known to the relay through `?id=...`), so direct UDP P2P is acceptable. Ghost mode promises anonymity even from the relay, so calls cannot run.

### 4.2 Standard / Private call behavior

Calls work as in the current Alpha 2 codebase:
- WebRTC `PeerConnection` with STUN/TURN.
- ICE candidate exchange (signaling) flows over the messaging channel.
  - Direct WSS: signaling is fast (~200–500 ms RTT for offer/answer).
  - Tor fallback active: signaling is slower (1–2 sec for offer/answer), but call still establishes.
- Media stream (audio / video) over **direct UDP P2P**, not via Tor.
- If P2P UDP cannot establish (NAT) → fall back to TURN relay (operator-run on the same VPS).

**Mid-call mode change:** if a Standard / Private user changes mode while a call is in progress, the call continues until it ends naturally. The new mode applies to all subsequent calls.

### 4.3 Mid-call transport switch

If the user is in Standard / Private with auto-fallback active, and the transport switches between direct and Tor while a call is in progress:
- The call is unaffected. Media stream is direct UDP P2P, independent of messaging-channel transport.
- Signaling (ICE candidate gathering, hangup signal) re-routes through whichever transport is currently active.

### 4.4 Ghost mode — call attempt UX

When a Ghost user taps the call button (audio or video):

```
┌─────────────────────────────────────┐
│         Calls disabled              │
│                                     │
│  In Ghost Mode, calls cannot run    │
│  over Tor without revealing your    │
│  real IP address. To make calls,    │
│  switch to Standard or Private mode │
│  in Settings.                       │
│                                     │
│      [ Open Settings ]              │
│      [ Cancel ]                     │
└─────────────────────────────────────┘
```

In the chat header the call buttons are visible but dim/disabled with a tooltip "Disabled in Ghost Mode" on long-press.

### 4.5 Incoming call to a Ghost user

The peer initiates a WebRTC offer through the messaging channel.
- The Ghost user has no push registered, so no immediate wakeup.
- When the Ghost user next opens PHANTOM, the foreground service drains pending envelopes, including the call offer.
- Because Ghost has calls disabled, the offer is **dropped** (not auto-answered, not auto-rejected with a signaling response — just dropped).
- The chat shows a `Missed call from <peer>` system message with the timestamp.
- The Ghost user cannot return the call without changing mode.

The peer who tried to call sees:
- Initial outgoing-call screen for ~30 sec.
- After timeout, an UI message: "No answer."
- The peer does not learn that the recipient is in Ghost mode (would leak Ghost-mode status to peers).

---

## 5. Mode transitions

### 5.1 Standard / Private → Ghost

If the user has joined groups, the system blocks the transition with a warning dialog:

```
┌─────────────────────────────────────────────┐
│  Switching to Ghost Mode requires leaving   │
│  N group chats. Groups are not supported    │
│  in Ghost Mode for anonymity reasons.       │
│                                             │
│  Continue?                                  │
│                                             │
│  [ Cancel ]    [ Leave groups and switch ]  │
└─────────────────────────────────────────────┘
```

If the user confirms:
1. Issue automated `LEAVE_GROUP` envelopes for each group.
2. Wait for `LEAVE_GROUP_ACK` from the relay (or 30 sec timeout per group).
3. Set Privacy Mode to Ghost in local config.
4. Apply `default disappearing 24 h` to any 1:1 chats that did not already have a TTL set (does not overwrite existing TTLs).
5. If multi-device: see §5.2.
6. Restart `PhantomMessagingService` to re-enter Tor-only transport mode.

If push tokens were previously registered with the relay (UnifiedPush), the app calls `DELETE /push/register` to remove the topic and instructs the local UnifiedPush distributor to unregister.

Detailed state machine: `docs/spec/GHOST_MODE_TRANSITIONS.md` §1.

### 5.2 Multi-device → Ghost

If the user has additional registered devices (Beta scope) when switching to Ghost:

```
┌─────────────────────────────────────────────┐
│  Ghost Mode is single-device only.          │
│  Choose which device remains active:        │
│                                             │
│  ◉  This device (Pixel 7)                   │
│  ○  Tablet (Galaxy Tab S9)                  │
│  ○  Desktop (MacBook Pro)                   │
│                                             │
│  All other devices will be removed from     │
│  your account and prompted to wipe PHANTOM  │
│  data on next app launch.                   │
│                                             │
│  [ Cancel ]    [ Continue ]                 │
└─────────────────────────────────────────────┘
```

On confirm:
1. Issue `DEVICE_REVOKE` envelopes to all other devices.
2. Each revoked device, on next app launch, sees a system-wide screen:
   ```
   This device has been removed from your PHANTOM account.
   All local data will be wiped. Tap to continue.
   ```
3. After acknowledgment, the device wipes the SQLCipher DB, identity keys, and all caches.
4. The chosen primary device proceeds with Ghost mode activation.

### 5.3 Ghost → Standard / Private (downgrade)

A user can downgrade out of Ghost at any time. No data loss.

1. Privacy Mode set to chosen target (Standard or Private).
2. Foreground service restarts with new transport policy (Standard / Private get auto-fallback rather than always-Tor).
3. Push wakeup is offered — user prompted: "Enable push notifications? (Recommended for delivery on slow networks.)"
   - Yes → register UnifiedPush topic with the relay.
   - No → keep custom WS keepalive only (same as Ghost).
4. Existing 1:1 chats keep whatever disappearing-message TTL was set in Ghost (24 h default). User can change per chat in Standard / Private.
5. Group chats remain unavailable until the user is re-invited (existing groups were left during §5.1).
6. Avatar / name visibility resumes (user's previously stored avatar/name are restored to broadcast state).

### 5.4 First-time Ghost activation — explainer dialog

The first time a user enters Ghost mode (from any source — onboarding selection or settings change), a one-time explainer:

```
┌─────────────────────────────────────────────┐
│  Welcome to Ghost Mode                      │
│                                             │
│  In Ghost Mode:                             │
│                                             │
│  • Messages disappear after 24 hours by     │
│    default on both devices automatically.   │
│    Applies to all messages — sent and       │
│    received. You can change the timer per   │
│    chat in chat settings.                   │
│                                             │
│  • Voice and video calls are disabled.      │
│                                             │
│  • You are invisible to other PHANTOM users │
│    — username search will not find you.     │
│                                             │
│  • Group chats are unavailable.             │
│                                             │
│  • All your traffic goes through Tor with   │
│    bridges — connections are slower but     │
│    your network identity is hidden.         │
│                                             │
│  Note: PHANTOM cannot prevent screenshots,  │
│  exports outside the app, or someone        │
│  photographing your screen.                 │
│                                             │
│             [ I understand ]                │
└─────────────────────────────────────────────┘
```

The dialog is shown only on first activation per device. A flag is stored in local config (`ghost_explainer_seen=true`).

---

## 6. The 6 finalized decisions (Vladislav, 2026-05-05)

Captured here to anchor what was decided, why, and on what date — so future engineering does not silently re-litigate.

### Q1 — Read receipts in Private

**Decision:** Default OFF, configurable in Settings.
**Rationale:** Private must *feel* private. If only discovery differs from Standard, the second mode does not earn its existence. Silent communication by default is a meaningful product line.

### Q2 — Disappearing messages in Ghost

**Decision:** Default 24 h ON, configurable per chat. TTL from send time. Applies to both sent and received. Soft delete on both devices via TTL expiration. First-time activation triggers explainer dialog (§5.4).
**Rationale:** Hard promise of ephemeral storage is the core of Ghost's product value. Per-chat configurability respects user agency for chats where 24 h is too short or too long.

### Q3 — Group chats in Ghost

**Decision:** Hard prohibit. Auto-leave required during transition (§5.1). Existing 1:1 chats remain.
**Rationale:** Anonymity in groups is structurally hard (member list reveals participants). Cleanly prohibiting is honest. Alpha 2 scope.

### Q4 — Plaintext-only-in-RAM in Ghost

**Decision:** Deferred to Beta. SQLCipher at-rest encryption is sufficient for Alpha 2.
**Rationale:** Plaintext-only-in-RAM requires re-architecting `MessageRepository` to lazily decrypt per chat-open rather than maintaining an in-memory cache. Significant work (1–2 weeks), marginal incremental security beyond SQLCipher for Alpha. Beta scope.

### Q5 — Multi-device in Ghost

**Decision:** Hard prohibit. Single-device anchor of trust. Switching from multi-device requires choosing one primary; others wipe (§5.2).
**Rationale:** Multi-device synchronization expands the threat surface (key sync messages, device fingerprints). Ghost users opt out of that complexity.

### Q6 — Avatar visibility in Private

**Decision:** Visible to confirmed contacts by default. Optional "hide avatar" toggle in Settings.
**Rationale:** Within an already-accepted contact relationship, the avatar is part of normal UX. The toggle exists for users with stronger personal preference.

---

## 7. Debug build flags

These flags affect **debug builds only** and are stripped from release builds via `BuildConfig`.

### `BuildConfig.DEBUG_UNLOCK_GHOST` (default `false`, debug only)

When `true`, the PRO-lock on Ghost mode in the onboarding selection card is removed. Tester can pick Ghost without a PRO subscription. Used for development and Test 6 (МТС without VPN) where Ghost is the path being verified.

Release builds always have this `false`. The release-build onboarding card stays PRO-locked at opacity 0.5 with a "PRO" badge.

### `BuildConfig.DEBUG_AGGRESSIVE_TOR_FALLBACK` (default `false`, debug only)

When `true`, the auto-fallback window in Standard / Private (§3.1 Step 1) shortens from **30 sec to 5 sec** before Tor starts in the background.

Used to expedite Test 6 cycles — without this flag a 30-sec wait per test reset adds up. Production behavior is the longer, more conservative 30-sec window so users on healthy networks never waste a Tor bootstrap.

---

## 8. Foreground notification text per state

The foreground notification is the user's primary visibility into what mode and transport are active. Text rules:

| State | Notification text |
|---|---|
| Standard / Private — connecting direct (window <30s) | `Encrypted connection active` |
| Standard / Private — direct connected | `Encrypted connection active` |
| Standard / Private — auto-fallback Tor starting | `Connecting via Tor…` |
| Standard / Private — Tor bootstrapping N% | `Tor: bootstrapping N%` |
| Standard / Private — connected via Tor onion | `Connected via Tor (bridges)` |
| Standard / Private — recovered to direct | `Encrypted connection active` |
| Ghost — Tor bootstrapping N% | `Ghost: bootstrapping N%` |
| Ghost — connected via Tor onion | `Ghost mode active (Tor bridges)` |
| Ghost — Tor failed | `Ghost: connection failed — open app` |

The text is the only mode-related dynamic UI element outside the app itself. It reflects the current runtime — not the user's mode setting in isolation.

---

## 9. Out-of-scope for Alpha 2

Items explicitly deferred to keep Alpha 2 ship-able:

1. **Plaintext-only-in-RAM mode** for Ghost (Q4 above) → Beta.
2. **Multi-device for Standard / Private** → Beta. Alpha 2 is single-device per identity.
3. **Per-chat Privacy-Mode override** (e.g. "this one chat is Ghost while everything else is Standard") → not planned. Privacy Mode is per-app, not per-chat.
4. **Dynamic mode based on context** (e.g. auto-Ghost on cellular, Standard on home WiFi) → not planned.
5. **Identity rotation** (changing your `identity-key` while preserving chats) → Beta.
6. **Mode-aware backup encryption** (Ghost backups exist in different format) → not in scope; Ghost has no backups (§2.3).

---

*End of behavior specification. Implementation must conform to this document. Deviations require a follow-up amendment dated and signed in the git log.*
