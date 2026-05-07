# PHANTOM Roadmap

Public-facing roadmap. For the detailed internal execution map, see
[`docs/project/Roadmap_2.0_to_Execution_Map.md`](docs/project/Roadmap_2.0_to_Execution_Map.md).
Dates below are targets, not commitments — we will adjust if a milestone
demands more time to land safely.

---

## Alpha 1 — May 2026 · **current phase**

One-to-one text messaging end-to-end, with the core privacy claims
verifiable by an external reviewer.

- 1:1 text E2EE (X3DH + Double Ratchet + Sealed Sender)
- Safety Numbers verification (60-digit fingerprint + QR)
- Disappearing messages (5 timer presets)
- Typing indicator, read receipts, message edit & delete
- Foreground service so messages arrive when the app is backgrounded
- Deep links (`phantom://invite/…`) and Universal Links
  (`https://phntm.pro/invite/…`)
- Relay deployed on `relay.phntm.pro` (Caddy + Rust/axum, ciphertext-only)
- Key rotation detection with re-verify UI banner
- Memzero of every transient key material in the ratchet

First public alpha invites go out during this phase. Feedback window
is two weeks before we open Alpha 2 planning.

---

## Alpha 2 — Summer 2026

Rich content without sacrificing the privacy model.

- Attachments (photos, files) via an encrypted media server (MinIO);
  content keys stay on the client, the server sees ciphertext blobs.
- Voice notes as first-class media messages (not the Alpha-1 base64 hack).
- Stable groups with Sender Keys, re-verify on member changes.
- Public channels (read-only broadcast) with per-channel sender keys.
- Hosted username directory with rate-limited lookup by `@username`.
- **External funding outreach:** see [`funding.json`](funding.json) for the canonical channel list.

---

## Beta — Fall 2026

Real-time and censorship-resistant transport options.

- 1:1 voice and video calls over WebRTC signalling through the relay.
- Pluggable transports beyond WebSocket: `obfs4`, planned Tor bridge.
- BLE and Wi-Fi Direct local transport for offline / high-risk use
  cases.
- Desktop client (Compose Multiplatform JVM target) — parity with the
  mobile client for text + files + calls.
- Hardened multi-device identity (linked-device trust, no shared key).

---

## v1.0 — Winter 2026 / early 2027

Public release milestones.

- iOS client (Compose Multiplatform iOS target).
- Channels with moderation tooling that never requires reading
  private chats.
- Self-hosted relay kit for businesses (public sibling of the hosted
  `relay.phntm.pro`).
- Third-party security audit — budget earmarked for Cure53 or Trail of
  Bits, funded from the Kickstarter.
- Public launch on F-Droid, Google Play, and the Apple App Store.

---

## Post-v1.0

Longer-term ideas, explicitly **not** promised for the Kickstarter:

- Federation between independently-run PHANTOM directories.
- A minimal bot API restricted to the public channel surface (bots
  cannot read private chats by construction).
- Phantom for Business — admin console, SSO, SLA-backed hosted relay.

---

## Explicit non-goals

We do not plan to become any of these, even under commercial pressure:

- A super-app with wallets, shopping, or mini-apps.
- A cryptocurrency platform.
- A content-moderation service that reads personal messages.
- A data broker or ad network.

These are ruled out by the [Product Doctrine](docs/doctrine/Product_Doctrine.md),
not just by lack of time.

---

## How to influence the roadmap

- **Issues.** File a GitHub issue describing the problem you want
  solved. Feature requests that conflict with the doctrine will be
  closed with an explanation; no judgement implied.
- **Grants.** If you represent a funder interested in a specific item
  above, reach out via `support@phntm.pro` (general) or
  `press@phntm.pro` (media / public-facing inquiries).
- **Security priorities.** Credible threat-model input that would
  reshuffle priorities is taken very seriously.
  See [SECURITY.md](SECURITY.md) for the full contact-routing table
  including `security@`, `privacy@`, `legal@`, and `abuse@`.
