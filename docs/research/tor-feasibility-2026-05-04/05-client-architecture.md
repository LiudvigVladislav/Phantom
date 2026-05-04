# 05 — Client Architecture: How PHANTOM Uses Tor

*Research date: 2026-05-04. Author: PHANTOM research agent.*

## TL;DR

Five strategies were evaluated:

- **A. Always-on Tor (Briar)** — simplest threat model, worst UX, dies if Tor
  is fully blocked.
- **B. Auto-detect (direct first, Tor on failure)** — fast on good networks,
  but transition logic leaks censorship state to attackers and is
  hard to get right.
- **C. User toggle (Always / Auto / Never)** — power-user friendly, terrible
  default for non-technical users.
- **D. Hybrid (control via Tor, media direct)** — best raw performance, but
  leaks user IP for voice and breaks PHANTOM's privacy positioning.
- **E. Recommended: tiered "Privacy Mode" with sane defaults.** New users
  default to Auto (direct WSS, fall back to Tor on failure with explicit user
  consent), with clear UI to upgrade to Always-Tor. Bootstrap uses bundled
  WebTunnel bridges fetched via Telegram BridgeDB.

The recommendation is essentially **C with a smart default = Auto, plus an
honest transition UX** that does not silently switch to Tor (because that
silent switch is itself a censorship signal). PHANTOM's positioning
("works in Russia without VPN, privacy-first") is best served by making Tor
the visible, opt-in upgrade rather than the always-on baseline.

The hardest unsolved subproblem is **bootstrap in Russia**: a fresh install
on a fresh SIM cannot reach the relay's plain WSS (blocked) or fetch a Tor
descriptor (Tor blocked). The only working primitive in 2025–2026 is
out-of-band bridge distribution via Telegram or domain-fronted endpoints.

---

## 1. The strategy space

### 1.1 A. Always-on Tor (the Briar approach)

All transport routes through Tor. There is no plain WSS path. The threat model
collapses to "Tor's threat model".

**Pros:** simple, single attack surface, no transition logic, no metadata leak
on switching.

**Cons:**
- Doesn't work when Tor is fully blocked and bridges fail (parts of Russia,
  Iran, China at various times in 2025).
- Battery: Briar's persistent Tor circuit is a primary cause of its 4× drain
  vs. server-based messengers ([byteiota](https://byteiota.com/briar-offline-mesh-when-internet-shutdowns-cut-85m-off/)).
- Latency: onion rendezvous adds seconds to first connect, breaking
  expectations set by Signal/Telegram.
- Voice/video over a 6-hop circuit is borderline unusable for real-time use.

**Verdict:** wrong for PHANTOM. We have already invested in keeping clients
lightweight; reverting to "every operation goes through 6 relays" undoes that.

### 1.2 B. Auto-detect (direct WSS first, Tor on failure)

Try direct WSS to the relay; on N consecutive failures, switch to onion.

**Pros:** zero latency cost on good networks, no UX changes for happy-path
users, only-pay-the-cost-when-needed.

**Cons:**
- **Transition is itself a signal.** Russian DPI can observe the SNI/IP of
  WSS attempts; a user whose client suddenly stops trying WSS and starts
  Tor handshakes is observable. (More on this in 06.)
- Tor descriptor fetch and HSDir lookup require Tor to be at least partially
  reachable — if WSS is blocked because *the network* is hostile, Tor likely
  is too.
- Failure modes are subtle: slow network, captive portal, transient DNS, all
  look like "WSS failed" and trigger Tor switch unnecessarily.
- Once on Tor, when do we switch back? Every reconnect? Every N minutes?
  Every wakeup? Each policy has tradeoffs.

**Verdict:** mechanically attractive, but the auto-switch is not actually
"transparent" the way it sounds. It's still a user-visible event (latency
spike, battery change, sometimes a permission prompt for the bundled Tor
process). It needs to be surfaced honestly in UI.

### 1.3 C. User toggle (Always / Auto / Never)

A Settings entry: Privacy Mode = { Always Tor, Auto, Never (direct only) }.

**Pros:** explicit, auditable, user-controlled. Power users get what they
want. Default can be tuned per-region.

**Cons:**
- The default is the only thing that matters for 95% of users. Default = Auto
  inherits all of B's complexity. Default = Always inherits A's UX problems.
  Default = Never makes the toggle useless.
- "Auto" still needs a transparent UI: don't silently switch.

**Verdict:** correct shape for PHANTOM, but useless without a thoughtful
default. See section 4.

### 1.4 D. Hybrid (signaling via Tor, voice/media direct)

Control plane (envelopes, prekey publish, message metadata) goes through Tor;
voice/large media uses a direct path or a separate non-Tor relay.

**Pros:** real-time media is workable. Battery cost of Tor is amortized over
a thin signaling channel.

**Cons:**
- **Leaks IP for media.** A WebRTC-style direct connection or a media-only
  relay sees the user's IP, defeating Tor's protection at exactly the
  moments of highest sensitivity (a voice call).
- **Trivial confirmation attack:** observer who controls or watches the media
  endpoint correlates "media call from IP X" with "Tor-side signaling for
  user Y" once any pattern leaks.
- Adds significant complexity (two transports, two reconnect state machines,
  two failure modes).
- Sets a confusing user expectation: "private when typing, not private when
  calling".

**Verdict:** rejected. The whole point of putting Tor in front of PHANTOM is
that the relay never sees IPs. Splitting the data plane back out destroys that
property.

### 1.5 E. Recommendation

See section 4. Short version: **C with default Auto, with explicit honest UI
about the transition**, plus a separate "Always-Tor Privacy Mode" prominent
in onboarding for users who declare they're in a censored region.

---

## 2. The bootstrap problem

This is the hardest part of the design and is largely unsolved across the
ecosystem.

### 2.1 What bootstrap requires for PHANTOM

A first-launch client must be able to:

1. Generate a local Curve25519 identity (offline — works always).
2. **Reach the relay** to publish prekeys and an account record (network).
3. Pair with a contact (QR exchange — works offline; the *next* step,
   delivering the first envelope, requires the relay).

Steps 2 and 3-tail need the relay reachable.

### 2.2 The Russia case

A fresh install on a fresh Russian SIM in 2025–2026 typically faces:

- Plain WSS to `relay.phntm.pro` may be blocked by DPI, especially on mobile
  ISPs that have begun blocking unknown TLS SNI patterns.
- Tor is blocked: default obfs4 bridges from BridgeDB are blocked on a
  growing list of Russian ASNs, and obfs4 itself is detected by some mobile
  ISPs ([Tor blog: Russia 2025](https://blog.torproject.org/staying-ahead-of-censors-2025/)).
- WebTunnel bridges (HTTPS-camouflaged) worked well into mid-2025 but
  Roskomnadzor began enumerating them; the Tor team's recommendation is to
  distribute WebTunnel bridges via Telegram and host on
  less-well-known ASNs ([Tor blog: WebTunnel Russia](https://blog.torproject.org/call-for-webtunnel-bridges/)).

So the bootstrap chain in the worst case is:

```
fresh app → cannot reach WSS → cannot reach Tor directly →
  needs a bridge → needs to fetch bridge → BridgeDB blocked →
  Telegram distributor (works) → user copy-pastes WebTunnel line →
  Tor up → relay onion reachable → publish prekeys
```

This is a multi-step manual flow. It is what Tor Browser users in Russia do
today.

### 2.3 What PHANTOM can do

Three layered options, in order of UX cost:

1. **Bundle WebTunnel bridges with the app.** Ship 3-5 fresh WebTunnel bridge
   lines in the app binary. Rotate per release. Risk: bridges burn faster
   when distributed via app stores (they're trivially extractable).
2. **Bundle a bridge-fetcher that pulls from Telegram BridgeDB.** Requires
   the user to already have working Telegram, but Telegram is currently
   reachable in Russia. This is what a meaningful fraction of Tor users in
   Russia actually do.
3. **Use domain fronting for the *bridge fetch only*.** Reach the relay's
   `/bridges` HTTPS endpoint via fronting through a CDN that is too
   expensive to block (Cloudflare, Fastly, Yandex Cloud). The endpoint
   serves a fresh WebTunnel bridge; the actual messaging traffic then goes
   over Tor.

For the relay side: the relay must publish an onion address in addition to
its WSS endpoint. The QR code that one user scans from another may include
the onion address, so contacts can reach each other through it once both
sides have Tor up.

### 2.4 Acknowledging what we can't fix

If a user's network blocks the entire HTTPS internet (deep firewall mode), no
amount of client cleverness will help. This is the limit of any messenger
that isn't running on physical-layer mesh (Briar's Bluetooth fallback). PHANTOM
should not pretend to solve this case; it should fail clearly and recommend
moving to a less hostile network.

---

## 3. Practical engineering

### 3.1 Tor library: Arti (Rust) vs C-tor

PHANTOM's relay is Rust. The natural choice is Arti (the Rust Tor implementation).
As of Arti 1.8 (December 2025), Arti has stable embedder APIs, supports
onion service hosting and client connection
([Tor blog: Arti 1.8.0](https://blog.torproject.org/arti_1_8_0_released/)).
Mobile bindings exist (Guardian Project demo'd Arti on Android and iOS in 2023
and have iterated since [Guardian Project blog](https://guardianproject.info/2023/03/04/arti-next-gen-tor-on-mobile/)).

**Caveat from Arti maintainers:** "support for advanced censorship circumvention
features is not straightforward on mobile operating systems ... pluggable
transports can be tested in 'managed' mode on old Android versions but likely
won't work on the latest Android and never worked on iOS"
([Guardian Project](https://guardianproject.info/2023/03/04/arti-next-gen-tor-on-mobile/)).

Implication: the bridge story on iOS in particular requires Arti's native
support for WebTunnel-as-built-in (not as a separate pluggable transport
process). This must be verified against current Arti capability before
committing.

### 3.2 Tor lifecycle in the client

Don't keep Tor running 24/7 (Briar mistake). Bring it up on demand:

- App is launched: start Tor lazily.
- App goes to background: tear Tor down within 30s of last activity, unless
  a UnifiedPush distributor is the wakeup channel (see 07).
- Wake-up from push: bring Tor up, drain queue, sleep again.

Tor circuit cost: roughly one-time CPU + a few hundred KB of directory data
per "cold start". Maintaining an idle circuit for 5 minutes is cheap; the
expensive part is the bootstrap.

### 3.3 Reconnect and transition logic

State machine sketch:

```
DIRECT_OK ──fail──> DIRECT_RETRY ──fail×N──> CONFIRM_TOR_SWITCH (UI)
                                                  │
                                                  ▼
                                              TOR_BOOTSTRAP ──fail──> TOR_BRIDGES (UI)
                                                  │
                                                  ▼
                                                TOR_OK
```

Key UX constraint: the `CONFIRM_TOR_SWITCH` step is **explicit**. The user
sees "Direct connection failed. Try Tor?" with a small explainer. This serves
two purposes: (1) it doesn't silently flip a switch on a censored network and
trigger DPI heuristics, (2) it educates the user about what's happening.

### 3.4 Onion address distribution

Two patterns to combine:

- **Public onion address** in the QR code (alongside WSS URL). Anyone scanning
  can dial via Tor.
- **Client-authorized onion** for the relay's "trusted devices" channel
  (see 06 §G). Client auth uses an X25519 key that PHANTOM ships in the QR
  payload; the Tor network alone cannot reach this onion without that key.
  This is overkill for v1 but should be a planned upgrade path.

---

## 4. Recommended design for PHANTOM

### 4.1 Goals to satisfy

- Works in Russia without VPN (yes, via Tor + bridges)
- Privacy-first positioning (yes, Tor as named feature, never silently disabled)
- Not unusable on good networks (yes, default = direct WSS when working)
- Voice/calls still work (yes, accept that calls in Always-Tor mode are
  lower quality, but they work)

### 4.2 Concrete recommendation

1. **Three Privacy Modes**, exposed in Settings and in onboarding:
   - **Auto** (default): try direct WSS; on persistent failure, prompt user
     to enable Tor. Once enabled, Tor stays for that session.
   - **Always Tor**: every connection through Tor. Used by users who declare
     "I'm in a censored region" or "Maximum privacy" during onboarding.
   - **Never Tor**: for users who explicitly opt out (e.g., low-end devices
     that struggle with Arti's CPU footprint). Power-user setting.

2. **Onboarding prompt**: a single yes/no at first launch — "Are you in a
   region where messengers are restricted?" — flips default from Auto to
   Always Tor. Not a multi-screen wizard; one question.

3. **Transition is honest**: explicit user confirmation before switching
   from direct → Tor mid-session.

4. **Bootstrap layered fallback**:
   - Default: WSS direct.
   - Fallback 1: WSS via app-bundled domain-fronted entry.
   - Fallback 2: Tor with app-bundled WebTunnel bridges.
   - Fallback 3: Tor with user-pasted WebTunnel bridge from Telegram.

5. **No hybrid signaling/media split.** When Tor is on, *all* traffic goes
   through it. Voice quality suffers; we tell the user.

6. **UnifiedPush is the wakeup channel** (see 07). It runs *outside* Tor by
   default (the distributor is its own privacy story), and it's only used to
   tell the app "wake up and drain queue", not to deliver content.

7. **Identity stays X25519.** Onion address is metadata in the QR payload,
   not part of the identity hash.

### 4.3 What this gives us

- Russian users get a working messenger without VPN, with a one-click
  upgrade to Tor.
- Privacy-conscious users worldwide get always-on Tor at the cost of one
  toggle.
- Users on good networks see no performance regression.
- The architecture has a clean migration story: identity is independent of
  transport, so we can swap Tor for something better (Snowflake-only, Arti
  WebTunnel-native, mixnet) without re-pairing the world.

---

## 5. Open questions

- What is Arti's actual battery cost on a real Android device with our
  reconnect cadence (every voice envelope = ~30s active)? Needs measurement,
  not estimation.
- iOS background limits: can we bring up Arti from a UnifiedPush wakeup
  within the time budget Apple gives us, or does the Tor bootstrap exceed it?
- Do we want client-authorized onions for the relay (private onion + auth
  key in QR)? Strong privacy property but adds key management complexity.
- How do we rotate bundled WebTunnel bridges? OTA via the relay itself once
  any contact is established? Per-release shipped via app store?

---

## Sources

- [byteiota — Briar Offline Mesh (battery 4×)](https://byteiota.com/briar-offline-mesh-when-internet-shutdowns-cut-85m-off/)
- [Briar — Quick Start (battery toggles)](https://briarproject.org/quick-start/)
- [Tor blog — Staying ahead of censors 2025](https://blog.torproject.org/staying-ahead-of-censors-2025/)
- [Tor blog — Tor in Russia: a call for WebTunnel bridges](https://blog.torproject.org/call-for-webtunnel-bridges/)
- [Tor blog — Responding to Tor censorship in Russia](https://blog.torproject.org/tor-censorship-in-russia/)
- [Tor Project Forum — new wave of Tor blocking in Russia](https://forum.torproject.org/t/apparently-a-new-wave-of-tor-blocking-is-underway-in-russia/21006)
- [Tor blog — Arti 1.0.0 released](https://blog.torproject.org/arti_100_released/)
- [Tor blog — Arti 1.8.0 released (Dec 2025)](https://blog.torproject.org/arti_1_8_0_released/)
- [Guardian Project — Arti, next-gen Tor on mobile](https://guardianproject.info/2023/03/04/arti-next-gen-tor-on-mobile/)
- [Tor Support — Connecting from censored regions](https://support.torproject.org/tor-browser/circumvention/connecting-from-censored-regions/)
- [Tor metrics — OnionPerf latencies](https://metrics.torproject.org/onionperf-latencies.html)
