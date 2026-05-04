# 07 — Tor + UnifiedPush: Combined Architecture

*Research date: 2026-05-04. Author: PHANTOM research agent.*

## TL;DR

UnifiedPush (UP) and Tor are complementary, not redundant. Tor protects
**conversation content and metadata**; UnifiedPush replaces FCM/APNs with a
FOSS push pathway. Used together, the architecture looks like:

- **Wakeup channel (UnifiedPush):** distributor delivers a tiny "you have
  mail" signal to the device. Distributor sees push timing and payload size,
  but not message content or recipient identity.
- **Data channel (Tor → relay onion):** on wakeup, app brings up Tor, drains
  queued envelopes from the relay, sleeps. Relay never sees client IP. Push
  distributor never sees conversation content.

This may be the killer architecture: it gives PHANTOM near-Briar privacy
properties with near-Signal battery cost.

Concrete recommendations:

1. **Default**: Tor as primary transport, UnifiedPush as optional
   wakeup-only signal.
2. **Self-host the distributor** (ntfy backed by the same Hetzner VPS, or
   adjacent) for the FOSS-positioned default.
3. **Power-user toggle**: disable UnifiedPush entirely → app maintains a
   long-lived Tor connection for instant delivery, at battery cost.
4. **Push payload is opaque.** The distributor sees only an
   encrypted "wake up" token; the actual envelope content stays on the relay
   behind Tor.
5. **Document the privacy gradient**: pure Tor (most private) → Tor + own
   distributor → Tor + 3rd-party distributor (ntfy.sh) → FCM/APNs (least
   private).

---

## 1. UnifiedPush architecture recap

UnifiedPush is a FOSS specification with three components
([UnifiedPush — Introduction](https://unifiedpush.org/developers/intro/),
[UnifiedPush — F-Droid announcement](https://f-droid.org/2022/12/18/unifiedpush.html)):

1. **Application** (PHANTOM client): registers with a distributor, gets back
   an HTTPS endpoint URL.
2. **Distributor** (ntfy, NextPush, FCM gateway, etc.): a separate app or
   system service on the device that holds the persistent connection to
   its push server. Receives push payloads via HTTPS POST and delivers to
   apps via Android intents (or platform equivalent).
3. **Push server**: the backend the distributor connects to. For ntfy: an
   ntfy server (self-hostable). For NextPush: a Nextcloud instance.

The application gives its endpoint URL to the application's own backend
(in PHANTOM's case, the relay). The relay sends a push by HTTPS POST to that
endpoint. The push server forwards through the distributor's persistent
connection to the device, which wakes the app.

In 2025 UnifiedPush adopted **WebPush (RFC 8030 / RFC 8291) compatibility**,
including end-to-end encryption between the application's backend and the
device ([UnifiedPush — Push for decentralized services](https://unifiedpush.org/news/20250131_push_for_decentralized/),
[UnifiedPush — Security and privacy](https://unifiedpush.org/news/20250513_push_security_privacy/)).
This is important: the push payload is encrypted with a key the app
generates, so the distributor and push server cannot read it.

---

## 2. Server side — relay sending pushes

### 2.1 The mechanics

When a new envelope arrives for user X, the relay:

1. Looks up X's UnifiedPush endpoint URL (registered earlier).
2. Constructs a WebPush-encrypted payload ("new envelope, count=1").
3. POSTs to the endpoint over HTTPS.
4. Push server forwards via distributor; device wakes.

### 2.2 Does the relay need Tor for this?

The endpoint URL is an HTTPS URL — say `https://ntfy.phntm.pro/up/<token>`.
The relay reaches it as a normal HTTPS client. Two options:

**Option A: relay → distributor over plain HTTPS** (default).
- Distributor sees: relay's IP, push timing, encrypted payload size,
  destination token (i.e., which user gets pushed).
- The token is per-user, so the distributor learns "user X got a push at
  time T from relay R".
- This is acceptable if the distributor is operated by the same trust
  domain as the relay (PHANTOM-hosted ntfy on the same VPS or adjacent).

**Option B: relay → distributor over Tor** (paranoid).
- Adds Tor latency to every push (multiple seconds).
- Distributor sees: a Tor exit IP, push timing, encrypted payload size, token.
- Hides relay's IP from the distributor — but if PHANTOM operates both, this
  is meaningless. If user runs against a 3rd-party distributor (ntfy.sh),
  it's marginal value at significant cost.

**Recommendation: Option A**, with the caveat that PHANTOM should self-host
the distributor by default. Then the distributor and relay are the same
operational entity, and the "what does the distributor learn" surface
collapses into "what does the relay operator learn", which we already
manage.

### 2.3 What the distributor sees

Even with WebPush encryption, the distributor learns:
- **Push timing** (when each user got woken).
- **Push payload size** (informative for "is this just a notification or
  does it carry data").
- **Destination token** (per-app per-user).
- **Source IP** (relay's IP if Option A; Tor exit if Option B).

It does *not* see message content (encrypted), recipient identity beyond
the opaque token, or any conversation metadata.

For PHANTOM: the destination token is per-user, so the distributor knows
*which user* got woken at what time. This is meaningfully more than nothing
but meaningfully less than what FCM/APNs see (FCM correlates push tokens
with Google account identities).

---

## 3. Client side — receiving pushes and bringing up Tor

### 3.1 The flow

1. Distributor receives push notification on its persistent connection,
   wakes the PHANTOM app via OS intent.
2. PHANTOM app starts. It does NOT yet have the message content — only the
   "you have something" signal.
3. App brings up Tor (Arti). On warm cache, this can be sub-second; on
   cold cache, several seconds.
4. App opens Tor circuit to relay onion, runs WSS, drains queued envelopes.
5. App processes envelopes (decrypt, persist, render notification with
   actual content).
6. App tears down Tor circuit (or holds for N seconds in case of follow-up
   activity), goes back to sleep.

### 3.2 Battery economics

UnifiedPush passive listening is cheap: the distributor's persistent
connection is shared across all UP-using apps on the device, so the marginal
cost per app is essentially zero
([UnifiedPush — F-Droid announcement](https://f-droid.org/2022/12/18/unifiedpush.html)).

Tor circuit is only paid when there's actual data. So battery cost ≈
1 × push events per day × cost-per-cold-Tor-bring-up. For a normal user
(say 50 push events / day), this is dramatically cheaper than Briar's
always-on Tor.

This is the main reason Tor + UnifiedPush is attractive for PHANTOM where
Briar's pure Tor approach was not.

### 3.3 iOS reality check

Apple does not allow third-party push distributors. UnifiedPush on iOS uses
APNs as a transport in the form of a NSE (Notification Service Extension) +
a "FCM gateway" or a special backend that forwards through APNs. This is a
real constraint and means **UnifiedPush is fundamentally Android-flavored**.
For iOS, PHANTOM will most likely need a separate APNs path (with the same
encrypted-wake-up-only payload model) until Apple changes its policy. The
*architecture* — wake-up-only push, content fetched over Tor — translates
across, but the distributor implementation differs per platform.

---

## 4. Tor + UnifiedPush coexistence

### 4.1 Both can run independently

The distributor's persistent connection is over its own transport (HTTPS
WebSocket to the push server, typically). It does not share the Tor circuit;
it does not even need to know Tor exists.

The PHANTOM app's Tor circuit comes up only when the app is awake. They
operate on different time scales:
- Distributor: persistent, low bandwidth, OS-managed.
- Tor: on-demand, bursty, app-managed.

This is the intended split.

### 4.2 Why this is the killer architecture

Pure Briar-style Tor: battery 4× heavier, latency seconds for every message
delivery.

PHANTOM with FCM-only: identifies users to Google, defeats privacy
positioning.

PHANTOM with **UnifiedPush wake + Tor data**:
- Battery cost approximately equal to Signal/WhatsApp.
- Privacy approximately equal to Briar (no IP correlation between user and
  conversation; relay never sees client IP; distributor sees only opaque
  wake events).
- Censorship resistance via Tor on data plane; UnifiedPush distributor can
  itself be self-hosted and reached via direct HTTPS or a fronted endpoint.

This combination is what nobody else has fully built for production-quality
1:1 messaging. Briar didn't because Briar refuses centralized infrastructure.
Cwtch didn't because Cwtch is Tor-only and accepts the latency.
Session has its own custom routing and doesn't use UnifiedPush.

PHANTOM is uniquely positioned to ship this combination.

---

## 5. Privacy analysis

### 5.1 What each layer sees

| Observer | Sees with FCM | Sees with UP (3rd-party) | Sees with UP (self-hosted) | Sees with pure Tor (no push) |
|---|---|---|---|---|
| Push provider (FCM/distributor) | user's Google ID, push timing, payload size, app-level metadata | opaque token, push timing, payload size | opaque token, push timing, payload size (same trust domain as relay) | n/a |
| Relay | client IP, conversation metadata | client IP, conversation metadata | client IP, conversation metadata | nothing (Tor hides it) |
| ISP (between user and relay) | TLS metadata, SNI, IP of relay | same | same | only "user is talking to Tor guard" |
| ISP (between relay and push) | TLS to FCM | TLS to push server | nothing visible (same VPS) | n/a |
| Global passive | flow correlation possible | flow correlation possible | flow correlation possible | flow correlation possible (SUMo) but harder |

### 5.2 The privacy gradient (most → least private)

1. **Pure Tor, no push, always-on app.** Briar model. Maximum privacy, terrible
   battery and UX.
2. **Tor + self-hosted UnifiedPush distributor.** Wake-up signal sees
   opaque tokens; conversation goes through Tor. Battery acceptable.
   *Recommended for PHANTOM default.*
3. **Tor + 3rd-party UnifiedPush (ntfy.sh, NextPush on a stranger's
   server).** Like above, but distributor is operated by a third party
   who can correlate "user X received push" with whatever they already
   know. Acceptable for users who explicitly choose it.
4. **Tor + FCM/APNs gateway.** UnifiedPush has FCM gateway distributors.
   Useful when the user can't avoid Google. Push provider sees Google
   account identity correlated with the app.
5. **Direct WSS + FCM/APNs (current PHANTOM Alpha).** Relay sees client IP;
   push provider sees Google/Apple identity. This is the baseline most
   messengers ship.

PHANTOM should explicitly position itself at level 2 by default and
document the gradient so users know where they stand.

### 5.3 Comparison to FCM and APNs

FCM specifically receives:
- Google account ID (not just an opaque token)
- Push payload (encrypted by app, but FCM has metadata about delivery)
- App identity
- Device information
- Push timing

APNs is similar but Apple rather than Google.

UnifiedPush distributor (especially self-hosted) sees a strict subset of
this: opaque token, timing, payload size. It does not see user identity.

For PHANTOM's positioning ("FOSS, privacy-first, works for users who want
to escape the Google/Apple ecosystem"), UnifiedPush is the correct push
primitive. FCM/APNs gateway distributors should be a fallback for
constrained environments only.

---

## 6. Recommended path for PHANTOM

### 6.1 Default architecture

```
[device]
  ├─ UnifiedPush distributor (ntfy app, system distributor, or APNs on iOS)
  │     persistent connection ─────► [PHANTOM ntfy server (self-hosted)]
  │                                          ▲
  │                                          │ HTTPS POST (encrypted wake)
  │                                          │
  │                                   [PHANTOM relay (Rust)]
  │                                          ▲
  └─ PHANTOM app ──── Tor circuit ──────► relay onion (WSS over Tor)
                          (on demand)        (data plane)
```

- Wake = UnifiedPush, encrypted, distributor sees only opaque token.
- Data = Tor, relay never sees client IP.

### 6.2 Configuration matrix

| User profile | Push setting | Transport |
|---|---|---|
| Default new user | Self-hosted PHANTOM distributor | Auto Tor (per 05) |
| Russian user | Same | Always Tor |
| Maximum-privacy power user | Off | Always Tor (long-lived connection, higher battery) |
| F-Droid / GrapheneOS user | UP via system ntfy distributor or NextPush | Always Tor or Auto |
| Stock Android user with Play Services | UP-via-FCM-gateway as fallback or self-hosted UP | Auto Tor |
| iOS user | APNs gateway (until Apple allows alternatives) | Auto Tor |

### 6.3 Self-hosting the distributor

The PHANTOM relay's Hetzner CPX22 VPS can run an ntfy instance alongside
Caddy and the relay. From [UnifiedPush ntfy self-hosted docs](https://unifiedpush.org/users/troubleshooting/self-hosted-ntfy/),
the deployment is well-documented. Estimated cost: trivial CPU/RAM, +1
domain (`ntfy.phntm.pro`), +1 systemd unit.

This makes "Tor + UnifiedPush self-hosted" the default behavior with no
external dependency on ntfy.sh.

### 6.4 Disabling push

A Settings option: "Disable wake-up push (more private, more battery)."
When enabled:
- App does not register with any distributor.
- App maintains a long-lived Tor connection to relay (Briar-style cost).
- Relay knows not to send pushes for this user; everything goes via the
  open WSS-over-Tor pipe.

For users who want belt-and-suspenders privacy, this is the option.

### 6.5 Push payload contents

The push payload from relay → distributor should be **opaque and minimal**:

```
encrypted_payload = WebPush_AES_GCM_encrypt(
    plaintext = b"\x00",   // single null byte; presence is the signal
    key       = client's WebPush public key
)
```

The app receives the wake-up, knows there's something to fetch, opens Tor,
fetches via the existing envelope-pull WSS RPC. The push payload itself
contains *no* useful information about the message — not even count, not
even sender hash. This minimizes what the distributor can learn from
payload size (always the same). If we want to convey count or priority,
that should travel inside the actual envelope over Tor, not in the push.

---

## 7. Open questions

- **iOS story:** what's the cleanest integration? An APNs-bridge
  distributor (like UnifiedPush has for FCM) keeps the app architecture
  consistent across platforms but introduces a Apple-trust dependency.
  Worth a dedicated feasibility note in 2026.
- **Distributor rotation:** if `ntfy.phntm.pro` is blocked in Russia, can
  the app fall back to a different distributor without re-registering all
  users? UnifiedPush spec supports endpoint rotation but the UX is
  non-trivial.
- **Wake without UnifiedPush on iOS:** If we ship Android with UP and iOS
  with APNs-bridge, the privacy story is asymmetric. Document this
  asymmetry.
- **Battery measurement:** real-world battery delta of (UP + Tor on
  wakeup) vs (always-on Tor) vs (FCM + WSS direct) needs measurement. We
  don't have it yet; back-of-envelope says UP+Tor ≈ FCM+WSS, but ship one
  and measure.
- **Throttling:** if relay sends 100 pushes for 100 quick messages from
  the same sender, distributor and OS will start rate-limiting. Push
  coalescing on the relay side is a normal optimization but adds latency.

---

## Sources

- [UnifiedPush — Introduction](https://unifiedpush.org/developers/intro/)
- [UnifiedPush — F-Droid announcement (2022-12-18)](https://f-droid.org/2022/12/18/unifiedpush.html)
- [UnifiedPush — Push for decentralized services (2025)](https://unifiedpush.org/news/20250131_push_for_decentralized/)
- [UnifiedPush — Push notifications, security and privacy (2025-05)](https://unifiedpush.org/news/20250513_push_security_privacy/)
- [UnifiedPush — Distributors](https://unifiedpush.org/users/distributors/)
- [UnifiedPush — Self-hosted ntfy](https://unifiedpush.org/users/troubleshooting/self-hosted-ntfy/)
- [UnifiedPush — ntfy distributor docs](https://unifiedpush.org/users/distributors/ntfy/)
- [UnifiedPush — Self-hosted with Matrix](https://unifiedpush.org/users/troubleshooting/self-hosted-with-matrix/)
- [Element docs — Using UnifiedPush and ntfy for push notifications](https://docs.element.io/latest/element-support/element-androidios-client-settings/using-unified-push-and-ntfy-for-push-notifications/)
- [ntfy — Configuration](https://ntfy.sh/docs/config/)
- [HackSignal — WebPush and UnifiedPush](https://hacksignal.com/news/webpush-and-unifiedpush-decentralizing-push-notifications-for-a-more-open-internet/)
- [Eugene Davis — Offline notifications with ntfy and UnifiedPush](https://eugenemdavis.net/archives/2026-02-23-offline-notifications-with-ntfy-and-unifiedpush/)
