<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (c) 2026 Willen LLC
-->

# Privacy Mode — Feature Comparison

**Status:** Approved 2026-05-05 by Vladislav (founder)
**Audiences:** product team · marketing · in-app onboarding · grant applications
**Companion:** behaviour spec at `docs/spec/PRIVACY_MODE_BEHAVIOR.md`

---

## A. Visual identity

| Mode | Icon | Tier | Tagline (≤ 7 words) |
|---|---|---|---|
| **Standard** | 🌐 | Free, default | "Normal messenger, with E2EE by default." |
| **Private** | 🔒 | Free | "Quieter. Discoverable only by people you know." |
| **Ghost** | 👻 | PRO | "Invisible. Anonymous. Ephemeral by design." |

Icon usage rules:
- **Onboarding cards:** icon at 32 px alongside the mode title.
- **Settings → Privacy Mode summary:** icon at 24 px next to current mode label.
- **Foreground notification (Android shade):** icon NOT used (the system notification icon stays the PHANTOM mark; the text alone conveys mode).
- **Marketing site / app store:** icons may scale to 48 px, monochrome variants permitted (white-on-cyan, cyan-on-black).

---

## B. Compact differentiator table — for marketing & app store

```
┌─────────────────┬─────────────┬──────────────┬───────────────┐
│                 │  STANDARD   │   PRIVATE    │     GHOST     │
│                 │  🌐 Free    │  🔒 Free     │   👻 PRO      │
├─────────────────┼─────────────┼──────────────┼───────────────┤
│ End-to-end      │     ✅      │      ✅      │      ✅       │
│  encrypted      │             │              │               │
│                 │             │              │               │
│ Visible by      │     ✅      │      ❌      │      ❌       │
│  username       │             │              │               │
│                 │             │              │               │
│ Online dot,     │  contacts   │  confirmed   │    no one     │
│  last seen      │             │   contacts   │               │
│                 │             │              │               │
│ Read receipts   │   on, opt.  │  off, opt.   │    forced     │
│                 │             │              │     off       │
│                 │             │              │               │
│ Disappearing    │  off, opt.  │  24h, opt.   │  24h, conf.   │
│  messages       │             │              │   per chat    │
│                 │             │              │               │
│ Voice & video   │     ✅      │      ✅      │      ❌       │
│  calls          │             │              │               │
│                 │             │              │               │
│ Voice messages  │     ✅      │      ✅      │      ✅       │
│                 │             │              │               │
│ Group chats     │     ✅      │      ✅      │      ❌       │
│                 │             │              │               │
│ Push wakeup     │  UnifiedP.  │  UnifiedP.   │    none       │
│                 │             │              │               │
│ Network traffic │   direct    │   direct     │ Tor + bridges │
│                 │  (Tor as    │  (Tor as     │   (always)    │
│                 │  fallback)  │  fallback)   │               │
│                 │             │              │               │
│ Bypasses ISP    │  on demand  │  on demand   │     yes       │
│  censorship     │             │              │               │
│                 │             │              │               │
│ Multi-device    │     ✅      │      ✅      │      ❌       │
│                 │             │              │               │
│ Encrypted       │     ✅      │      ✅      │      ❌       │
│  backup         │             │              │               │
└─────────────────┴─────────────┴──────────────┴───────────────┘
```

---

## C. Full feature comparison — for product team

Comprehensive matrix: every mode-sensitive subsystem, with the exact behaviour. Engineering, QA and security review use this table to assert conformance.

| # | Subsystem | Standard 🌐 | Private 🔒 | Ghost 👻 |
|---|---|---|---|---|
| **Discovery & social graph** | | | | |
| 1 | Searchable by `@username` | yes | no | no |
| 2 | Listed in suggested-contacts ("People you may know") | yes (if mutual contact has Standard) | no | no |
| 3 | Inbound contact request — username search source | accepted, user reviews | rejected silently | rejected silently |
| 4 | Inbound contact request — out-of-band identity-key | accepted, user reviews | accepted, user reviews | accepted, user reviews |
| 5 | Profile avatar visible to confirmed contacts | yes | yes (toggle off available) | no — placeholder only |
| 6 | Profile name visible to confirmed contacts | yes | yes | yes (pseudonym only) |
| 7 | "Real Name" field editable | yes | yes | locked |
| **Presence & receipts** | | | | |
| 8 | Online dot on avatar | shown to contacts | shown to confirmed only | hidden from all |
| 9 | Last-seen timestamp | shown to contacts | shown to confirmed only | hidden from all |
| 10 | Typing indicator broadcast | on, configurable | off, configurable | forced off |
| 11 | Typing indicator received | shown if peer broadcasts | shown if peer broadcasts | shown if peer broadcasts |
| 12 | Read receipts broadcast | on, configurable | off, configurable | forced off |
| 13 | Read receipts received | shown if peer broadcasts | shown if peer broadcasts | shown if peer broadcasts |
| 14 | Delivery receipts (relay-ack-based) | on | on | suppressed in UI; relay still tracks |
| **Disappearing messages** | | | | |
| 15 | Default TTL on new chat | off | 24 h | 24 h |
| 16 | Per-chat TTL configurable | yes | yes | yes |
| 17 | TTL counted from | message send time | message send time | message send time |
| 18 | TTL applies to | sender's local copy + delivered envelopes | both | both |
| 19 | Soft-delete on TTL expiry, both devices | per-chat opt-in | yes (default), per-chat configurable | yes (default), per-chat configurable |
| **Push wakeup** | | | | |
| 20 | UnifiedPush registration with relay | yes (`POST /push/register` on app start) | yes | no |
| 21 | Relay → ntfy push on offline envelope | yes (`services/relay/src/push.rs`) | yes | no-op (Ghost identity check) |
| 22 | Custom WS keepalive (foreground service) | always on | always on | always on (only path) |
| 23 | FCM / APN | never used (operator policy ADR-016) | never used | never used |
| **Transport (data plane)** | | | | |
| 24 | Direct WSS to relay.phntm.pro | preferred | preferred | never used |
| 25 | Tor onion via `RELAY_ONION_URL` | fallback after 30 s direct timeout | fallback after 30 s direct timeout | always |
| 26 | Pluggable transports (Snowflake, WebTunnel, obfs4) | only when Tor fallback active | only when Tor fallback active | always |
| 27 | Connection padding (anti-fingerprint) | enabled when Tor active | enabled when Tor active | always enabled |
| 28 | Tor bootstrap timeout | 300 s (Stage 2C.7) | 300 s | 300 s |
| 29 | Recovery from Tor → direct after stable window | 5 min sustained direct viability | 5 min sustained | never (Ghost stays Tor) |
| **Calls** | | | | |
| 30 | Audio call — outbound | supported, WebRTC P2P | supported | disabled, dialog explains |
| 31 | Video call — outbound | supported | supported | disabled |
| 32 | Audio call — inbound | rings, normal flow | rings, normal flow | dropped silently → missed-call entry in chat |
| 33 | Video call — inbound | rings, normal flow | rings, normal flow | dropped silently → missed-call entry |
| 34 | Call signaling (ICE) channel | messaging WS (current transport) | messaging WS | messaging WS (Tor — slower setup) |
| 35 | Call media stream | direct UDP P2P → TURN fallback | direct UDP P2P → TURN | n/a (calls disabled) |
| **Voice messages & attachments** | | | | |
| 36 | Voice messages outbound | supported | supported | supported (slower upload via Tor) |
| 37 | Voice messages inbound | supported | supported | supported |
| 38 | Image attachments | supported | supported | supported (slower) |
| 39 | File attachments | supported | supported | supported (slower) |
| 40 | Per-chat attachment auto-download | on by default | on by default | on by default (configurable) |
| **Group chats** | | | | |
| 41 | Create group | yes | yes | no |
| 42 | Join group via invite link | yes | yes | no |
| 43 | Join group via discovery | yes | n/a (no discovery) | no |
| 44 | Participate in existing group | yes | yes | no — auto-leave on transition |
| 45 | Group disappearing-message default | off | 24 h | n/a |
| 46 | Group member list visibility | members visible to other members | members visible to other members | n/a |
| **Local storage** | | | | |
| 47 | SQLCipher at-rest encryption | yes | yes | yes |
| 48 | Plaintext message cache (decrypted) | in DB | in DB | in DB (Alpha 2) → RAM-only (Beta) |
| 49 | Cache evict on app close | no | no | aggressive (Beta scope; SQLCipher only in Alpha 2) |
| 50 | Thumbnail cache for images | persistent | persistent | persistent (Alpha 2) → no thumbnails (Beta) |
| **Backup & multi-device** | | | | |
| 51 | Encrypted backup export (`.phantom-backup`) | supported | supported | disabled — button greyed out |
| 52 | Restore from backup | supported | supported | n/a (no backups) |
| 53 | Multi-device pairing | supported (Beta) | supported (Beta) | prohibited |
| 54 | Cross-device message sync | supported | supported | n/a |
| **Notifications & UI signaling** | | | | |
| 55 | Foreground service notification text | "Encrypted connection active" / Tor variants | same | "Ghost mode active (Tor bridges)" / variants |
| 56 | Connection state icon in chat-list header | 🔓 direct / 🔒 Tor | 🔓 / 🔒 | 🔒 always (Ghost lock variant) |
| 57 | First-time mode activation explainer | none (Standard is default) | none | mandatory dialog (`docs/spec/PRIVACY_MODE_BEHAVIOR.md` §5.4) |
| 58 | Mode shown in Settings header | "Privacy Mode: Standard" + 🌐 | "Privacy Mode: Private" + 🔒 | "Privacy Mode: Ghost" + 👻 |

---

## D. "When to choose which mode" — UX text

Used as helper copy on the onboarding selection screen, in the Settings → Privacy Mode chooser, and in marketing site comparison block.

### Standard 🌐
> **Choose Standard if you want PHANTOM to feel like a normal messenger.**
> You can be found by username, your contacts can see when you are online, and calls work as you would expect. Every message is end-to-end encrypted by default — that does not change in any mode.

### Private 🔒
> **Choose Private if you want a quieter presence.**
> Username search will not find you — only people who already have your identity key can reach you. Read receipts and typing indicators are off by default; turn them on per setting if you want them. Everything else (calls, groups, attachments) works normally.

### Ghost 👻 (PRO)
> **Choose Ghost when anonymity matters more than features.**
> You disappear from PHANTOM's social layer. Your messages self-destruct after 24 hours by default. All your traffic is routed through Tor with bridges — slower, but invisible to your network provider.
>
> The trade-off: voice and video calls are unavailable in Ghost mode, and group chats are not supported. This is by design — calls and groups would expose information that contradicts the anonymity promise.
>
> Ghost is part of PHANTOM PRO.

---

## E. PRO value proposition (for billing screens & marketing)

Ghost is the headline PRO feature, but PRO unlocks more than mode-switching alone.

**Free tier (Standard + Private):**
- Full E2EE messaging, voice messages, calls, groups, attachments.
- Self-hosted UnifiedPush wakeup (no Google FCM, no Apple APN).
- Auto-fallback to Tor when network conditions require it.
- SQLCipher at-rest encryption.
- One device per identity (Alpha 2) → multi-device (Beta).

**PRO tier adds:**
- 👻 **Ghost Mode** — invisible profile, always-Tor, 24h disappearing, no push, no backups, no calls, no groups.
- (Beta) Plaintext-only-in-RAM storage for Ghost.
- (Beta) Multiple identity profiles per app install.
- (Future) Hidden service hosting for self-managed relay scenarios.
- Priority support contact.

The PRO upsell exists because Ghost mode imposes ongoing operational costs (Tor onion service hosting, bridge availability, custom WS keepalive infrastructure) that a tiny minority of users actually require. Free users get full E2EE messaging without compromise — Standard and Private are not crippled versions of Ghost. They are the right tools for most people.

---

## F. Grant-application differentiation language (NLnet, FUTO, FLOSS/fund)

For the "What makes PHANTOM different" sections of grant proposals:

> PHANTOM is one of very few messengers that ship a **layered privacy model** — three coherent privacy contracts (Standard, Private, Ghost) that map to four orthogonal subsystems (discovery, presence, push, transport) through a single user choice. Each mode is a complete product promise, not a setting toggle. The strongest tier (Ghost) provides anonymity guarantees comparable to Briar or Cwtch — running over Tor with bridges, no third-party push, no group surface — while the everyday tier (Standard) provides a normal messenger UX at no privacy cost beyond what Signal-grade E2EE already provides.
>
> The architectural decision to embed Tor + Lyrebird pluggable transports (Snowflake / WebTunnel / obfs4 / meek) inside the application itself, rather than relying on a separate Tor app such as Orbot, means PHANTOM works for users in censored networks without requiring them to discover, install and configure a second tool. This is critical for users in Russia, Iran, China and similarly restricted environments where multi-app onboarding is itself a privacy hazard.

---

## G. App-store listing snippet (Google Play, F-Droid, App Store)

Short version (≤ 200 chars), suitable for app-store descriptions:

> Three privacy modes — Standard, Private, Ghost — let you choose how visible you are. End-to-end encrypted always; Ghost adds Tor + bridges + 24h disappearing messages.

---

*End of comparison document. Update simultaneously with `PRIVACY_MODE_BEHAVIOR.md` whenever a mode-sensitive subsystem changes behaviour.*
