# Tor architecture for PHANTOM relay — research findings

_Research date: 2026-05-04. Author: research pass for the МТС-WiFi 120s-WSS-drop incident._

## TL;DR

Routing PHANTOM's WSS traffic through a Tor v3 onion service is **conditionally viable for messaging and voice-message delivery, but unsuitable for real-time WebRTC voice/video calls**. The single biggest factor is that Tor is TCP-only — UDP-based DTLS-SRTP cannot traverse it without a full TURN-over-TCP-over-Tor proxy that destroys the latency budget. For text messages and 5–30s voice messages (~330 KB–~1.6 MB bursts) a v3 onion service on the existing Hetzner relay is realistic: published OnionPerf data shows median onion-service download times for 5 MB of ~5–10 s, and Tor 0.4.7 congestion control eliminated the historical hard speed cap. The right architecture for PHANTOM is **dual-mode: keep public WSS as primary, expose `.onion` as an opt-in fallback for users on networks that filter or break TLS (e.g., МТС WiFi)**, with calls explicitly disabled on the Tor path.

---

## 1. Tor v3 onion service performance baseline

### Latency

A v3 onion connection is a **6-hop circuit** (3 client hops + 3 service hops, joined at a rendezvous point), which roughly doubles the per-hop latency overhead of an exit circuit. Tor Project's OnionPerf measurements (published continuously at metrics.torproject.org) consistently show:

- **Time-to-first-byte (TTFB) for onion-service circuits: ~1.0–2.5 s median** in 2024–2025 measurements, vs ~0.5–1.0 s for exit circuits ([Tor Metrics — Performance](https://metrics.torproject.org/onionperf-latencies.html)).
- One academic study measuring real-time over Tor reported **average one-way delay (OWD) ~280 ms** for a single client through a 3-hop circuit, and **~458 ms OWD for the V-Tor (both ends on Tor) setup** — close to the 6-hop onion case ([Re-thinking the Feasibility of Voice Calling Over Tor, PETS 2020](https://petsymposium.org/popets/2020/popets-2020-0063.pdf)).
- A 12-month, 7-country, ~6,650-relay study found Tor sustains "good voice quality" (PESQ > 3, OWD < 400 ms) in **>85% of cases** for 3-hop circuits.

### Throughput

- OnionPerf benchmarks 50 KB / 1 MB / 5 MB downloads. **Median 5 MB download time over onion services in current data is ~5–10 s**, i.e., roughly 0.5–1 MB/s (4–8 Mbps) per circuit on a reasonably loaded network ([Tor Metrics — Performance](https://metrics.torproject.org/onionperf-latencies.html)).
- Tor 0.4.7-stable (May 2022) shipped end-to-end congestion control (Tor-Vegas algorithm, [proposal 324](https://spec.torproject.org/proposals/324-rtt-congestion-control.html)), removing the legacy ~500 KB/s SENDME-based ceiling. Post-deployment, "smoother and improved browsing free of speed limitations and bottlenecks, without adding any burden on end-to-end latency" ([Congestion Control Arrives in Tor 0.4.7-stable!](https://blog.torproject.org/congestion-contrl-047/)).
- KIST (Kernel-Informed Socket Transport, default since 0.3.2.x) reduces circuit congestion by >30%, cuts latency by 18%, and increases throughput ~10% ([Never Been KIST, USENIX Security '14](https://www.usenix.org/conference/usenixsecurity14/technical-sessions/presentation/jansen)).

### Connection stability for long-lived WebSockets

This is the most subtle Tor property for a chat relay:

- **`MaxCircuitDirtiness` (default 10 minutes)** governs only when *new* streams may attach to an existing circuit. Existing streams continue on their circuit until they close or the circuit closes ([Tor manual / forum](https://forum.torproject.org/t/a-question-about-maxcircuitdirtiness-option/11121)).
- An open WebSocket → one stream → one circuit. The circuit will be kept alive by Tor's keepalive cells and will not be torn down on the dirtiness timer. It survives until the underlying TCP at any of the 6 hops dies, or until `CircuitsAvailableTimeout` (24 h default) for unused circuits.
- Practical observation: long-lived onion connections **do** drop with non-trivial frequency due to relay churn — any of 6 relays restarting/leaving kills the circuit. Industry wisdom: assume ~1 reconnect per 1–6 hours per long-lived stream. PHANTOM's reconnect/queue logic must be robust regardless.
- Onion-service-specific failure mode: stale introduction points. Long-lived clients have hit issues where their cached intro points become invalid ([Tor issue #25882](https://gitlab.torproject.org/tpo/core/tor/-/issues/25882)). A modern Tor (≥ 0.4.7) handles this transparently in most cases.

### Vanguards

Vanguards (Mesh-Vanguards, [proposal 292](https://spec.torproject.org/vanguards-spec/)) defend against guard-discovery attacks on hidden services. Without Vanguards, an adversary running ~5% of relays can identify a service's guard "in minutes"; with Vanguards, the attack window expands to "weeks or even months" ([Tor blog](https://blog.torproject.org/announcing-vanguards-add-onion-services/)). Arti added Vanguards support in v1.2.2 (July 2024) ([Tor blog](https://blog.torproject.org/announcing-vanguards-for-arti/)).

**Relevant for PHANTOM?** Yes for the relay operator. The relay's onion service runs 24/7 on a known Hetzner box; without Vanguards a determined adversary could correlate guard usage and ultimately deanonymize Hetzner. But: PHANTOM's relay is *not* secret — it's published as `relay.phntm.pro`. The threat model for the relay is "messages don't leak", not "the server's location is hidden". Vanguards are still a cheap hardening measure (the [`vanguards` add-on by mikeperry-tor](https://github.com/mikeperry-tor/vanguards) is a single Python daemon), but they are not load-bearing for our use case.

---

## 2. WebSocket compatibility through Tor

### Verdict: **Yes, WebSocket works through `.onion`.** Both `ws://` and `wss://` are fully supported.

- The Tor SOCKS5 proxy carries arbitrary TCP, so any client library that supports SOCKS5 (most do — Ktor, OkHttp, the Go stdlib, browsers via Tor Browser) can open a WebSocket to `wss://abcd…onion`.
- Inside an onion circuit the data is already wrapped in three layers of Tor encryption end-to-end, so **TLS becomes optional**. OnionShare's chat feature uses plain WebSockets on the onion connection: `ws://m6dazoly4sqnoqrm.onion:5000/` is a documented valid form ([How OnionShare Works](https://docs.onionshare.org/2.5/en/features.html)).
- For PHANTOM, keeping `wss://` is still preferable because (a) the relay code already terminates TLS via Caddy, (b) it preserves a uniform code path, (c) some intermediate proxies/SDKs assume `wss` for WebSocket-over-port-443 semantics. CA/B Forum allows DV certs for v3 `.onion` names, so a public cert is obtainable if needed ([Tor blog: Get a TLS certificate for your onion site](https://blog.torproject.org/tls-certificate-for-onion-site/)).

### How others do it in production

- **OnionShare** (Tor Project–adjacent, widely deployed): WebSocket-over-onion for its chat mode; reverse-proxy file uploads/downloads of multi-GB sizes ([OnionShare 2.5 docs](https://docs.onionshare.org/2.5/en/features.html)).
- **Cwtch / Ricochet-Refresh**: Custom binary protocol over onion-service TCP, not WebSocket. They tunnel chat plus offline-message relay through "untrusted infrastructure" ([Cwtch docs](https://docs.cwtch.im/)).
- **Briar**: Bramble Transport Protocol over Tor onion-service TCP, no WebSocket layer.
- **Bitcoin / Lightning nodes**: Many production peers run on `.onion` + custom binary protocols.
- **Bisq DEX**: WebSocket-over-Tor for marketplace traffic in production for years.

### Alternatives for half-duplex push (HTTP/2 SSE, long-poll)

- HTTP/2 server push and SSE work fine over onion services (it's all just TCP with HTTP semantics). They have **no inherent advantage over WebSocket on Tor** — same circuit, same latency, same drop semantics. WebSocket's bidirectional framing is a better fit for PHANTOM's existing envelope protocol than rebuilding push-channels on SSE.
- Long polling adds a TTFB (~1–2 s on onion) per poll, which is unacceptable for delivery latency. Skip.

---

## 3. Voice message throughput analysis

### PHANTOM workload

- 5 s voice → 6 chunks × ~55 KB envelope ≈ **330 KB**.
- 30 s voice → 29 chunks × ~55 KB envelope ≈ **1.6 MB**.

### Through a typical Tor circuit

- 5 MB onion download median ≈ 5–10 s ⇒ throughput ~0.5–1 MB/s. **A 1.6 MB burst should land in 2–5 s in median conditions, and 10–30 s on a slow circuit.** At the p95 tail it can be much worse. Note: Cwtch reports a ~30% base64 overhead on file payloads (their wire format). PHANTOM does not base64-encode chunks, but the JSON-serialised `EncryptedMessage` already ~6× expands the binary (the project memory cites 8 KB chunk → ~55 KB envelope), so there's no further bloat.
- Tor file-share tools (Cwtch, OnionShare) routinely push 10s of MB; 1.6 MB is well within typical-circuit capacity.
- However, voice-message UX expects "send and trust it lands" — under sustained user load on a single onion service the throughput per-client degrades because the **service's three-hop side is shared** across all clients on that circuit.
- Mitigation: client-side chunked upload with explicit ACK per chunk (which PHANTOM already does post-PR-3). On Tor, retries cost a full circuit RTT, ~2 s per failed chunk. Preserve current 8 KB chunk size.

### Verdict

Voice-message bursts are **viable through Tor for the messaging-fallback case**. Expect 2–10× higher upload time than direct WSS, but no fundamental blocker.

---

## 4. Voice/video calls through Tor — verdict

### Hard "no" for the direct WebRTC path

WebRTC media is **DTLS-SRTP over UDP**. Tor carries **TCP only**. Tunnelling UDP through a TCP-over-Tor circuit:

- Forces packet ordering/retransmission semantics that are antithetical to real-time media (re-transmitted late audio is worse than dropped audio).
- Adds the 280–460 ms one-way delay of Tor (Sharma et al. 2020) on top of the existing WebRTC latency budget. With a typical 150–200 ms WebRTC end-to-end target, the math doesn't work for an interactive call.

The Sharma et al. paper showed that *audio-only*, slow-rate (Opus 16 kbps), with custom packetisation, can achieve PESQ > 3 in 81% of cases — but that's a research prototype, not WebRTC, and not video.

### What other Tor messengers do

- **Briar**: No voice/video calls. Text + file + forum + blog only.
- **Cwtch**: No voice/video calls. Text + file + group chat only ([Cwtch roadmap](https://openprivacy.ca/discreet-log/19-cwtch-roadmap/)).
- **OnionShare**: No real-time media; chat and file transfer only.
- **Ricochet-Refresh**: Text only.

This is not a coincidence — none of the Tor-native messengers ships voice calls.

### Architectural options for PHANTOM if we ship Tor

| Option | Privacy | Latency | Verdict |
|---|---|---|---|
| **(a) Disable calls when Tor mode is selected** | Best | N/A (calls disabled) | **Recommended.** UX message: "Calls are not available over Tor." |
| (b) Signalling over Tor, media direct P2P | Leaks both peers' IPs to each other | Native | Privacy regression — defeats the point of choosing Tor mode. Reject. |
| (c) Signalling over Tor, media through TURN-over-Tor | Best | +280–460 ms OWD ⇒ unusable for two-way | Reject. |
| (d) Signalling over Tor, media via TURN on relay (UDP-direct from clients) | Leaks client IP to relay, not peer | Native | Worth considering if "hide who I'm calling" is the only goal, but doesn't help МТС-WiFi (UDP often blocked too). |

**Recommendation**: Option (a). Calls remain experimental even on the WSS path (per Alpha-1 status). When the user enables Tor transport, calls are disabled with a clear UX explanation. Voice *messages* still work.

---

## 5. Onion service hosting on Hetzner — concrete plan

### docker-compose addition

Add a `tor` service alongside the existing relay/Caddy stack:

```yaml
services:
  tor:
    image: dperson/torproxy   # or build from official tor:latest
    restart: unless-stopped
    volumes:
      - ./tor/data:/var/lib/tor          # persists ed25519 v3 keys
      - ./tor/torrc:/etc/tor/torrc:ro
    networks:
      - relay-net
    # No ports published — connections come from inside the Tor network only.

  relay:
    # existing service, listens on internal port 8080
    networks:
      - relay-net
```

`./tor/torrc`:

```
HiddenServiceDir /var/lib/tor/phantom_relay/
HiddenServiceVersion 3
HiddenServicePort 443 relay:8080
# Optional hardening:
HiddenServiceNonAnonymousMode 0
ControlPort 9051
```

The `./tor/data/phantom_relay/hostname` file gets the assigned `.onion` address on first run. **Back this up.** The `hs_ed25519_secret_key` is the identity — losing it = losing the address forever; leaking it = anyone can impersonate the service.

References: [`goldy/tor-hidden-service` Docker image](https://hub.docker.com/r/goldy/tor-hidden-service); [torservers/onionize-docker](https://github.com/torservers/onionize-docker); [Dockerized Tor Onion Services with Vanity v3 Tor Addresses](https://0day.work/dockerized-tor-onion-services-with-vanity-v3-tor-addresses/).

### Vanity addresses

`mkp224o` can grind a vanity v3 prefix (e.g., `phntm…`). Cost is exponential in prefix length: 5 chars ≈ minutes, 7 chars ≈ days on a single CPU. Optional polish for Alpha-2.

### Resource budget on existing Hetzner CPX22 (2 vCPU, 4 GB RAM)

- **CPU**: A single Tor daemon servicing a low-traffic onion (≤ a few Mbps) sustains <5% of one core. The 6-hop crypto is offloaded to relay nodes elsewhere; the service-side daemon mostly does intro-point and rendezvous handshakes.
- **Memory**: ~50–150 MB resident under normal load. Spikes possible — Tor's OOM handler kicks in at 75% of `MaxMemInQueues` (set to e.g. 512 MB).
- **Network**: Onion service traffic is **2× the application bytes** (packets transit through 3 service-side hops, each adding handshake/keepalive overhead). On Hetzner CPX22's effectively unmetered traffic this is irrelevant.
- **Reference**: Tor Project recommends ≥2 GB RAM for a relay running flat-out; we are running a service, much lighter ([Tor support — relay memory](https://support.torproject.org/relay-operators/relay-memory/)).

### Failover / multi-instance

For multi-VPS scale-out, **OnionBalance v3** publishes a "super-descriptor" merging introduction points from N backend instances ([Onionbalance docs](https://onionservices.torproject.org/apps/base/onionbalance/)). For Alpha-2 we do not need this — single Hetzner box is sufficient.

### Coexistence with public WSS

The Tor service is **purely additive**. The existing Caddy + WSS endpoint on `relay.phntm.pro:443` is untouched. Inside Docker, `relay:8080` is reachable both from Caddy (clearnet) and from the tor container (onion). One process, two ingress paths.

---

## 6. Bandwidth scaling concerns

### Per-onion-service practical ceiling

Published Tor relay throughput hits ~400–450 Mbps per direction on modern AESNI-enabled CPUs ([Tor performance support](https://support.torproject.org/relays/performance/bandwidth-limits/)). But **the bottleneck for an onion service is not the service-side daemon — it's the slowest of the 6 relays in any given circuit**, and the aggregate of all current circuits to that service.

In practice, well-tuned production onion services (post-0.4.7 congestion control) sustain on the order of **5–20 Mbps aggregate** per single onion-service identity before queueing latency becomes user-visible. This is a soft ceiling, not a hard one.

### Comparison with current relay

Hetzner CPX22 has a 1 Gbps NIC, effectively unmetered. The current public WSS relay is bound by application logic, not network. So Tor delivers **~1–2% of the bandwidth headroom** of the clearnet path.

### Saturation point

Rough back-of-envelope for PHANTOM:
- A heavy active user round-trips ~10 KB/min average (text + delivery acks + presence). 1 voice message burst per minute peaks at ~330 KB.
- Sustained load per active user: ~50 kbps average, 500 kbps peak.
- 5 Mbps onion ceiling ÷ 500 kbps peak = **~10 concurrently-uploading users** before queueing.
- 5 Mbps ÷ 50 kbps avg = **~100 simultaneously-active users** at average load.

For Alpha-2 (target user count: hundreds at most, of which a single-digit % might pick the Tor mode), this is fine. For 10K+ DAU we'd need OnionBalance and probably a second VPS.

---

## 7. Architecture options ranked

### Option B (dual-mode, WSS public + `.onion` opt-in) — **recommended**

- Client setting toggles transport: "Default", "Tor".
- Default = `wss://relay.phntm.pro` via Caddy (today's behaviour).
- Tor mode = SOCKS5 to local Tor daemon → `wss://<hash>.onion`.
- Calls disabled in Tor mode.
- Pros: user choice; no privacy regression for non-Tor users; addresses the МТС-WiFi failure mode (Tor-over-obfs4/WebTunnel bridges defeats the WSS-killing middlebox); minimal server-side change; falls back gracefully.
- Cons: client must bundle Tor (Arti or tor-android). ~4–10 MB binary footprint. Background battery cost on mobile.

### Option C (WSS-through-`.onion`, single transport)

- Drop public WSS entirely; everyone speaks `.onion`.
- Pros: simpler server (no Caddy), uniform privacy.
- Cons: every user pays the latency tax (TTFB ~1–2 s on cold connect, ~280 ms ongoing); calls become impossible for everyone; mobile battery cost everywhere; censored networks still need bridges anyway. **Reject for Alpha-2** — too aggressive a UX regression.

### Option A (only-Tor, Briar-style P2P)

- No relay; pure peer-to-peer via onion services.
- Pros: maximum metadata resistance; matches Briar's threat model.
- Cons: requires both peers online simultaneously (Briar's known UX pain), or a separate offline-message store; massive architectural rewrite of PHANTOM's relay-mediated model. **Out of scope** for Alpha-2; revisit if PHANTOM ever pivots to fully decentralised.

### Sanity check: Cwtch's middle ground

Cwtch does Option B-ish: peers connect peer-to-peer over onion when both online, with **untrusted offline-message servers** for when one is offline. PHANTOM's current relay role is more like an always-on synchronisation hub — closer to Option B than to Cwtch, but the Cwtch precedent confirms Option B is workable.

---

## 8. Open questions for follow-up

1. **Arti vs C-tor on Android/iOS**: Arti is the Rust rewrite, gaining feature parity (Vanguards landed July 2024). For PHANTOM's KMP architecture, Arti is more attractive than embedding C-tor. Need a survey: current Arti maturity for SOCKS5-only client use case in 2026; iOS embeddability; binary size.
2. **Pluggable transports for client-side**: МТС specifically blocks/throttles `obfs4` on 4G in 2025 ([Staying ahead of censors in 2025](https://blog.torproject.org/staying-ahead-of-censors-2025/)). PHANTOM's Tor mode should ship with **WebTunnel + Snowflake** support, not raw Tor. WebTunnel was promoted to stable in 2024 and is the Tor Project's recommended transport for Russian users in 2025–2026. Need a separate research doc on PT integration.
3. **Reconnect storm on mass circuit churn**: If 1000 users are on the onion service and the service-side guard restarts, all 1000 reconnect simultaneously. Test this in a Shadow simulation or staging before Alpha-2 GA.
4. **Onion key backup procedure**: Operationally critical. Should the `hs_ed25519_secret_key` live in our Hetzner backup snapshot, or be air-gapped? If we lose the key, every installed Alpha-2 client has a stale `.onion` baked in.
5. **Vanity prefix length cost-benefit**: Worth grinding a 7-char vanity for memorability/anti-phishing, or stick with the random hash and rely on the client's pinned config?
6. **Fallback algorithm**: If the client tries Tor and fails (no bridges work), should it auto-fall-back to clearnet WSS, or refuse? Privacy-conscious users would prefer "fail closed". Surface as a setting.
7. **Onion-service-side rate limits**: Caddy-style rate limits don't work directly because all onion clients arrive on `127.0.0.1`. Need application-layer (relay) rate limits keyed on session/identity.
8. **Voice-message UX on slow Tor circuits**: At p95 a 30s voice message could take 30+ s to upload. Need a progress UI and ability to cancel.

---

## Sources

- [Tor Metrics — Performance / OnionPerf latencies](https://metrics.torproject.org/onionperf-latencies.html)
- [Tor Metrics — Traffic / Bandwidth](https://metrics.torproject.org/bandwidth.html)
- [Tor Metrics — Onion Services seen](https://metrics.torproject.org/hidserv-dir-v3-onions-seen.html)
- [Some news from the Onion Space, February 2025](https://forum.torproject.org/t/some-news-from-the-onion-space-february-2025/17474)
- [Improving the Performance and Security of Tor's Onion Services (PETS 2025)](https://petsymposium.org/popets/2025/popets-2025-0029.pdf)
- [A Comprehensive and Long-term Evaluation of Tor V3 Onion Services (IEEE 2023)](https://ieeexplore.ieee.org/document/10229057)
- [Re-thinking the Feasibility of Voice Calling Over Tor (PETS 2020)](https://petsymposium.org/popets/2020/popets-2020-0063.pdf)
- [Congestion Control Arrives in Tor 0.4.7-stable!](https://blog.torproject.org/congestion-contrl-047/)
- [Tor proposal 324 — RTT-based congestion control](https://spec.torproject.org/proposals/324-rtt-congestion-control.html)
- [Never Been KIST: Tor's Congestion Management Blossoms with Kernel-Informed Socket Transport (USENIX Security '14)](https://www.usenix.org/conference/usenixsecurity14/technical-sessions/presentation/jansen)
- [Hiding in plain sight: Introducing WebTunnel](https://blog.torproject.org/introducing-webtunnel-evading-censorship-by-hiding-in-plain-sight/)
- [Staying ahead of censors in 2025: What we've learned from fighting censorship in Iran and Russia](https://blog.torproject.org/staying-ahead-of-censors-2025/)
- [Tor in Russia: A call for more WebTunnel bridges](https://blog.torproject.org/call-for-webtunnel-bridges/)
- [Announcing the Vanguards Add-On for Onion Services](https://blog.torproject.org/announcing-vanguards-add-onion-services/)
- [Announcing Vanguards Support in Arti](https://blog.torproject.org/announcing-vanguards-for-arti/)
- [Tor Vanguards Specification (proposal 292)](https://spec.torproject.org/vanguards-spec/)
- [Vanguards add-on (mikeperry-tor)](https://github.com/mikeperry-tor/vanguards)
- [Briar — How it works](https://briarproject.org/how-it-works/)
- [Briar (Wikipedia)](https://en.wikipedia.org/wiki/Briar_(software))
- [Cwtch documentation](https://docs.cwtch.im/)
- [Cwtch protocol draft (PDF)](https://cwtch.im/cwtch.pdf)
- [Discreet Log #17: Filesharing FAQ — Open Privacy Research Society](https://openprivacy.ca/discreet-log/17-filesharing-faq/)
- [Discreet Log #19: Cwtch Roadmap (1.4 to 1.7)](https://openprivacy.ca/discreet-log/19-cwtch-roadmap/)
- [How OnionShare Works (2.5)](https://docs.onionshare.org/2.5/en/features.html)
- [Get a TLS certificate for your onion site](https://blog.torproject.org/tls-certificate-for-onion-site/)
- [HTTPS for your Onion Service — Tor Community](https://community.torproject.org/onion-services/advanced/https/)
- [Onionbalance — load balancing for Onion Services](https://onionservices.torproject.org/apps/base/onionbalance/)
- [Cooking with Onions: Finding the Onionbalance](https://blog.torproject.org/cooking-onions-finding-onionbalance/)
- [Tor support — bandwidth limits](https://support.torproject.org/relays/performance/bandwidth-limits/)
- [Tor support — relay memory](https://support.torproject.org/relay-operators/relay-memory/)
- [MaxCircuitDirtiness discussion (Tor Forum)](https://forum.torproject.org/t/a-question-about-maxcircuitdirtiness-option/11121)
- [Stale onion service introduction points (Tor issue #25882)](https://gitlab.torproject.org/tpo/core/tor/-/issues/25882)
- [Voice over Tor? (Guardian Project, 2012 — historical context)](https://guardianproject.info/2012/12/10/voice-over-tor/)
- [Dockerized Tor Onion Services with Vanity v3 Tor Addresses](https://0day.work/dockerized-tor-onion-services-with-vanity-v3-tor-addresses/)
- [`goldy/tor-hidden-service` Docker image](https://hub.docker.com/r/goldy/tor-hidden-service)
- [`torservers/onionize-docker` — Tor v3 onion services for Docker](https://github.com/torservers/onionize-docker)
