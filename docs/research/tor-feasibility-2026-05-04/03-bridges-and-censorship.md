# Tor Bridges and Censorship in Russia 2026 — research findings

**Research date:** 2026-05-04
**Scope:** Whether Tor (with bridges) is reachable from Russian carrier networks in 2026, with a focus on PHANTOM's first-connect UX problem on TSPU-filtered МТС / Beeline / MegaFon mobile data.

---

## TL;DR

Tor is reachable from Russia in 2026, but only through **bridges** — direct connections have been blocked since December 2021. The pluggable-transport mix that works **today** is **WebTunnel + Snowflake**, with **obfs4 declining**. WebTunnel is the most effective when the bridge is fresh and not yet on TSPU's blocklist; Russia began enumerating and blocking WebTunnel bridges in June 2025, which forced the Tor Project to switch from web/email distribution to **Telegram bot distribution (`@GetBridgesBot`)** because Telegram makes wholesale enumeration much harder. Snowflake (WebRTC, volunteer-run) is the most resilient by construction and is now the largest cohort of Russian Tor users (Tor Project Snowflake operations updates Oct–Dec 2025). The single biggest factor for PHANTOM is the **chicken-and-egg first-connect problem**: a freshly installed PHANTOM in Russia cannot fetch bridges through Tor (Tor is blocked) and cannot fetch them through `bridges.torproject.org` (mostly DNS/HTTPS-blocked), so PHANTOM **must ship a small set of fallback bridges in the APK and rotate them on every release** — that is the only solution that actually works for users on a hostile network. Long-term sustainability depends on Snowflake volunteer load and on continued Tor-Project investment in WebTunnel; both are real but neither is guaranteed.

---

## 1. Bridge ecosystem 2025–2026

### 1.1 obfs4 — the legacy default, declining

obfs4 was the most-used Tor pluggable transport from 2014 through 2024. Random-looking traffic; resistant to passive DPI; well-tested. Still used today and still the default offered by Orbot.

**Status in Russia, 2025–2026:** **degraded but partially working.**

- The Tor Project's own retrospective ("Staying ahead of censors in 2025", late 2025) reports user complaints that obfs4 connections are blocked on some 4G mobile networks in Russia.
- Forum reports on `forum.torproject.org` (thread "Apparently, a new wave of Tor blocking is underway in Russia", 2025) describe obfs4 working on some ASNs and not on others — TSPU is doing per-flow probing rather than blanket port blocking.
- obfs4 is NOT formally deprecated. The replacement client binary (`lyrebird`) handles both obfs4 and webtunnel and continues to ship.

For PHANTOM in Russia, obfs4 is **fallback only** — it is not the right default in 2026.

### 1.2 meek (azure / amazon / Cloudflare) — almost dead for new users

meek uses TLS domain-fronting against a major CDN. The list of fronting providers has shrunk to almost nothing:

- **Cloudflare:** dropped domain fronting in **September 2015**.
- **Amazon CloudFront:** dropped 2018.
- **Google App Engine:** dropped 2018.
- **Microsoft Azure:** **still appears to work** as of 2024–2025 (per `arlolra/meek` and Tor Project forum discussion); the `meek-azure` built-in bridge is still in Tor Browser and Orbot defaults.

For PHANTOM in Russia, meek-azure is fragile — a single Microsoft policy change kills it everywhere overnight. Treat as last-resort fallback. The Tor Project itself has been deprioritizing meek in user-facing docs since the rise of WebTunnel.

### 1.3 Snowflake — the workhorse

Snowflake is the WebRTC-based pluggable transport: a volunteer's browser tab acts as a temporary proxy; the Tor client looks like a video call.

**Capacity (Tor Project Snowflake Daily Operations updates, Oct/Nov/Dec 2025):**
- ~35,000–40,000 simultaneous users globally
- ~2.7 Gbit/s aggregate at the snowflake-01 bridge as of March 2024; stable through Dec 2025
- Russia is now the **largest country cohort** of Snowflake users (Tor Project, October 2025 update)

**Per-user throughput** (Bocovich et al., USENIX Security 2024; corroborated by user reports):
- typically **< 5–10 Mbps** per client
- significantly slower than obfs4 / WebTunnel because of WebRTC overhead + Tor's 3-hop latency + volunteer proxy variance

**For PHANTOM messaging payloads**, Snowflake throughput is fine — text messages and even compressed voice envelopes do not need 10 Mbps. Latency variance (occasional 1–3 s spikes when a proxy churns) matters more, and that is a real cost to user-perceived "send delay".

Snowflake is **what the Tor Project recommends Russian users use first** in 2025–2026. It has been the most resilient against TSPU because each connection looks like an unrelated WebRTC negotiation against a Cloudflare or Fastly fronted broker, and because there are too many ephemeral proxies for TSPU to enumerate.

### 1.4 WebTunnel — the new "blend in" transport

WebTunnel wraps Tor inside what looks exactly like a normal HTTPS WebSocket session against a domain serving a real-looking website. The bridge runs behind a real TLS cert + a real-looking web page; the censor sees what looks like ordinary HTTPS.

**Bridge count timeline:**
- launch (early 2024): 60 bridges
- late 2024: 143 bridges
- target by end 2024: 200+
- a community campaign (2024-11-28 → 2025-03-10) offered Tor t-shirts to operators running ≥5 WebTunnel bridges to recruit more

**Russian status — the critical drama of 2025:**
- For ~6 months after launch, WebTunnel worked excellently in Russia. Auto-fetched bridges in Tor Browser worked first try.
- **June 2025:** Russian censors began **listing and blocking most distributed WebTunnel bridges**.
- Tor Project response: hardened WebTunnel itself (SNI imitation, certificate-chain pinning to non-WebPKI certs, safer SNI allowlisting handling) AND shifted distribution from public web/email to **Telegram bot** (which is harder to enumerate from inside the censor) in late 2025.

**For PHANTOM:** WebTunnel is the highest-quality transport when it is unblocked, but it requires fresh bridges. Any list of WebTunnel bridges baked into the APK will go stale in weeks. WebTunnel must be paired with a live bridge-fetching mechanism.

### 1.5 Conjure (refraction networking) — emerging, not production

Conjure uses unused IP space inside cooperating ISPs as a pool of "phantom" decoy proxies. Different model from bridges — far harder for a censor to enumerate because there is no list to leak.

**Status:** integrated into Tor Browser **alpha** as a pluggable transport (Tor Project alpha tester call, 2024). Deployed in production at the **University of Colorado** and a small/mid Michigan ISP only, plus a low-capacity bridge ("Haunt").

For PHANTOM in 2026: **not yet usable at scale**. Track for 2027.

### 1.6 Quick reliability tier table for Russia 2026

| Transport | Russia today | Throughput | First-time success | PHANTOM role |
|---|---|---|---|---|
| Direct (no PT) | blocked since 2021-12 | n/a | ~0% | dead |
| obfs4 | mixed; many ASNs blocked | high | ~30–60% | fallback |
| meek-azure | fragile; depends on Azure | low | ~40% | last-resort |
| Snowflake | working; largest RU cohort | low–medium | ~70–85% | **default for first connect** |
| WebTunnel | working when fresh; June 2025 enumeration | high | ~50–80% with fresh bridge | **preferred steady-state** if we can rotate bridges |
| Conjure | not deployed at scale | medium | n/a | future |

(Success-rate ranges are rough engineering estimates from forum reports; no rigorous public dataset exists for early 2026.)

---

## 2. Russian (РФ) Tor accessibility 2025–2026

### 2.1 Direct Tor: blocked

Confirmed since **December 2021**. OONI documented this explicitly in their 2021 "Russia started blocking Tor" report; the situation has only tightened. Direct connections to public Tor relays are silently dropped, and the public bridge-distribution endpoints (`bridges.torproject.org`) are largely blocked or DNS-poisoned.

### 2.2 What TSPU does (concretely)

TSPU = "Технические средства противодействия угрозам", the federal middlebox stack deployed at every major Russian ISP since 2019 and ramped through 2022–2025.

Observed behaviors against Tor:
- **Pattern-matching Tor TLS handshakes** (TLS ClientHello fingerprint heuristics; the "tor-but-not-Firefox" pattern).
- **Active probing** of suspected obfs4 bridges — TSPU connects to the candidate bridge with an obfs4-style first message and looks for Tor-shaped response. If positive, the bridge IP+port is added to the blocklist.
- **Per-ASN granularity.** The same bridge IP is reachable from one Russian ISP and not from another, which is why user reports are inconsistent.
- **WireGuard / OpenVPN are detected within seconds** by DPI signatures (per the TSPU analysis in independent OONI-adjacent research); obfs4 is harder but has been increasingly fingerprinted in 2024–2025.
- **SNI allowlisting** — for some ASNs, only specific SNIs work outbound. WebTunnel's June 2025 hardening explicitly addressed this with SNI imitation.

### 2.3 OONI measurements 2024–2026

OONI's `ooni/probe` ships a Tor circumvention test and a dedicated Tor-Snowflake test. Russian users running OONI Probe contribute to the public dataset at `explorer.ooni.org`. The 2024–2025 trend in that data:
- Direct Tor: fail
- obfs4 default bridges: increasing failure
- Snowflake: mostly success
- WebTunnel: mostly success until June 2025; partial degradation since

For PHANTOM, the actionable conclusion is that **Snowflake is the most reliable single transport choice** for Russian first-connect, with WebTunnel as the next step once a fresh bridge is available.

### 2.4 Mobile-network specifics (МТС / Beeline / MegaFon)

User reports on `forum.torproject.org` and `ntc.party` consistently single out Russian 4G/5G mobile networks as **more aggressive than home broadband**:
- More obfs4 blocking
- More NAT churn forcing Tor circuit rebuilds (this is exactly what is breaking PHANTOM's WebSocket transport)
- IPv6 sometimes available but bridges generally don't expose IPv6
- Mobile МТС and MegaFon both have known TSPU deployments; some operators apply policies more strictly

This matches Vladislav's reports of the Tecno-on-МТС silent-drop behavior. **Tor on mobile in Russia is harder than Tor on broadband in Russia.** It still works, but circuit churn is higher and battery cost rises.

---

## 3. Bridge auto-discovery and UX

The Tor Project's own toolbox for "first connect on a bad network":

### 3.1 Moat (BridgeDB over domain-fronting)

Moat is what Tor Browser's Connect Assist uses: it fetches bridges from BridgeDB through a domain-fronted HTTPS request (historically against Azure or Fastly). Works inside Tor Launcher, no Tor connection required.

**Status in Russia:** **partially blocked**. Default bridges fetched via Moat in some Russian places do not work — the censor has at times blocked the front used by Moat, or the bridges Moat returns are already on the blocklist. Tor's own `bridges.torproject.org` site is also unreliable from Russian IPs.

### 3.2 Email distribution

Send `get bridges` to `bridges@torproject.org` from a Riseup or Gmail address; receive bridges in reply. **Slow but censorship-resistant** (the censor cannot easily intercept Gmail). Used as last-resort manual fallback. Not viable for in-app UX.

### 3.3 Telegram bot — `@GetBridgesBot`

This is the **distribution mechanism the Tor Project leaned on hardest in 2025** for Russian users. Send `/obfs4` or `/webtunnel` to `@GetBridgesBot` in Telegram; the bot replies with bridge lines.

Why Telegram works:
- Telegram is not blocked in Russia (after the 2018–2020 unblock saga it has been allowed; even when politically squeezed in Apr–May 2024, the messenger itself stayed reachable).
- Per-user issuance in private chats makes wholesale enumeration much harder than scraping a website.
- Telegram bots can rate-limit and deduplicate per Telegram user ID, frustrating mass-enumeration attacks.

**For PHANTOM, this is a usable path even for end users** — we can document "open Telegram, message `@GetBridgesBot`, get a `webtunnel` line, paste it in PHANTOM" as a manual recovery path when our auto-fetch fails.

### 3.4 Built-in UX patterns from existing apps

- **Tor Browser Connect Assist** (since 14.x, late 2024): tries direct → tries default Snowflake → tries default WebTunnel → tries Moat fetch → presents manual config. The user sees a single "Connect" button; the layered fallback is invisible.
- **Orbot 17.x**: similar layered approach, with Snowflake as the prominent first fallback.
- **Briar** historically required manual bridge entry on bad networks. Has been improving but is still UX-poor compared to Tor Browser.
- **Cwtch**: relies on the underlying Tor binary's defaults; minimal in-app UX.

PHANTOM should follow the **Tor Browser Connect Assist pattern** rather than the Briar pattern. One button. Fallback chain hidden. Failure surfaces a recovery flow that points users to `@GetBridgesBot`.

---

## 4. The first-time bootstrap problem (the hardest UX moment for PHANTOM)

### 4.1 The chicken-and-egg

User installs PHANTOM in Russia. App opens. App needs a Tor circuit to:
- register an onion service identity (so contacts can reach this user)
- send the first message

Tor is blocked. To unblock Tor we need bridges. To fetch bridges we either (a) need Tor (no), (b) need `bridges.torproject.org` reachable (often no), or (c) need a domain-fronted Moat call (often no). The user has not even configured an account yet and the app cannot connect. **This is the moment most apps lose users.**

### 4.2 What other apps actually do

- **Tor Browser** ships **default bridges** in `Bridges.json` that are baked into the binary. These rotate per Tor Browser release (~every 6 weeks). On first run with no network info, Tor Browser tries those defaults. They are **the same** for every Tor Browser user, which means TSPU finds and blocks them quickly — but they are usually fresh enough at release time to work for the first few weeks of each release cycle.
- **Orbot** ships the same default-bridge approach, with the same rotation cadence.
- **Briar** historically required out-of-band bridge config; this is precisely why Briar is hard to onboard in censored regions.
- **OnionShare** does not solve this — assumes a working Tor.
- **Cwtch** uses default bridges, with a built-in option to add custom ones.

### 4.3 PHANTOM-specific recommendation: bundled fallback bridges + release rotation

The only thing that empirically works for first-connect on a hostile network is **bundling a small set of fresh bridges in the app binary**.

Concrete plan:
1. **Bundle 5–10 Snowflake broker addresses + 5–10 WebTunnel bridges + 3 obfs4 bridges** in PHANTOM's APK as a `bridges.json` resource.
2. **Rotate this list on every PHANTOM release** (PHANTOM ships roughly every 4–6 weeks based on the current cadence, which matches Tor Browser's rotation).
3. On first run, try in order: Snowflake → WebTunnel → obfs4. Show a single progress UI; do not surface internals to the user.
4. If all bundled bridges fail (the inevitable end-state once TSPU enumerates them), fall back to:
   - Built-in **Moat** fetch over domain fronting (Azure / Fastly).
   - If that fails, surface a **"Get a bridge from Telegram"** UI with a one-tap deep-link to `@GetBridgesBot` and a paste-bridge input.
5. **Cache last-known-good bridge** in PHANTOM's secure storage so subsequent launches skip the discovery loop.

### 4.4 Operational cost on PHANTOM team

Updating the bundled bridge list every release is real work. We would need:
- A script that pulls fresh bridges from BridgeDB before each release tag.
- Coordination with Tor Project / Snowflake operators to make sure the chosen bridges are not "too fresh to be tested" or "about to be retired".
- Moat fallback configured with an Azure/Fastly fronting domain we monitor.

This is sustainable for a small team. Tor Browser's Anti-Censorship Team manages something similar at vastly larger scale.

### 4.5 Alternative: self-hosted PHANTOM bridge

We could run our own WebTunnel bridge on Hetzner relay infrastructure (the same VPS PHANTOM already operates). Pros: full control, freshness guaranteed. Cons: a single bridge will be blocked within days of becoming popular; we'd be in a perpetual cat-and-mouse with TSPU; legal exposure for us as bridge operators. **Not recommended for Alpha/Beta.** Reconsider once PHANTOM scale justifies a dedicated bridge operations role.

---

## 5. Bridge sustainability / operational

### 5.1 Snowflake volunteer load

Snowflake bridges are run by:
- Two main snowflake-01/02 bridges operated by Tor Project / The Calyx Institute
- Tens of thousands of volunteer-browser proxies (the Snowflake browser extension and standalone proxies)

**Sustainability outlook (positive for 2026, uncertain for 2028):**
- Tor Project is investing heavily — Snowflake Daily Operations posts (Oct/Nov/Dec 2025) show capacity growth and proxy churn handling improvements.
- Volunteer proxy count is growing, partly because Tor Browser bundles a one-click "be a Snowflake proxy" feature.
- Risk: a major censorship event (e.g., China deploying a Snowflake-killer DPI signature) could change global volunteer dynamics.

### 5.2 WebTunnel operator load

WebTunnel bridges require an operator to run a real-looking website behind their bridge, which means **hosting cost + a domain name + a TLS cert + content**. Considerably more friction than running an obfs4 bridge.

**Sustainability outlook (concerning):**
- Tor Project's late-2024 campaign needed a t-shirt incentive to push from 143 to 200+ bridges.
- The June 2025 enumeration event burned a large fraction of bridges in one shot; operators have to set up new ones.
- Long-term equilibrium between operator recruitment and TSPU enumeration is unclear.

### 5.3 Time for a new transport to become "unblocked"

Empirical pattern from 2022–2025:
- A new transport's "honeymoon period" is **roughly 6–18 months** between launch and the censor catching up.
- The censor can enumerate distributed bridges in **hours to days** once they identify the distribution channel.
- Hardening (like WebTunnel's mid-2025 SNI work) buys **weeks to months**, not years.

Implication for PHANTOM: **plan for transport churn**. Architect the Tor transport so that swapping pluggable transports (snowflake → webtunnel → next-thing) is a config change, not a code change.

### 5.4 Tier comparison summary

| Dimension | Snowflake | WebTunnel | obfs4 |
|---|---|---|---|
| Hardness to enumerate | high (ephemeral) | low (bridges enumerable) | medium |
| Per-user throughput | low–medium | high | high |
| Operator cost | low (browser tab) | high (domain + cert + site) | low |
| Russia 2026 | working | working when fresh | mixed |
| Battery on mobile | high (WebRTC churn) | medium | low |
| First-connect for new user | best | good if fresh | unreliable |

---

## Recommendations for PHANTOM

1. **Do not rely on direct Tor.** It does not work in Russia. Bridges are mandatory.
2. **Default first-connect order: Snowflake → WebTunnel → obfs4 → Moat → manual via `@GetBridgesBot`.** This matches Tor Browser Connect Assist.
3. **Bundle a fresh bridge list (`bridges.json`) in every PHANTOM release.** Rotate every release. Pull fresh bridges from BridgeDB / Tor Project's distributors via a release-script.
4. **Build a manual recovery UI** that one-taps users into Telegram → `@GetBridgesBot` → copies returned line back into PHANTOM. Russian users already use Telegram; this leverages an unblocked channel.
5. **Cache last-known-good bridge.** Avoid re-running the full discovery loop on every launch — that is what burns battery and creates UX delay.
6. **Architect the transport layer to swap transports without code changes.** Treat each PT as a plugin. The 12-month transport churn is real and we will need to follow it.
7. **Do not run our own WebTunnel bridge in Alpha/Beta.** Operational and legal cost too high for current team size.
8. **For non-Russia users, default first-connect is direct Tor.** Don't pay the bridge UX cost where it isn't needed. Detect via geolocation / connectivity probe + user-selected "censored network" toggle.
9. **Be honest with users.** If Tor cannot connect after the fallback chain, say so plainly and offer the Telegram path. Do not pretend connectivity is fine.
10. **Coordinate with the Tor Project Anti-Censorship Team.** PHANTOM is a downstream consumer of their bridge ecosystem. Open a channel; ideally PHANTOM contributes back (running Snowflake proxies opportunistically when on Wi-Fi + power, donating to bridge campaigns).

---

## Open questions for follow-up

1. **What does the in-app Telegram-bot recovery flow look like UX-wise?** Need a Vladislav-driven mock that respects PHANTOM's no-Telegram-style design language while still being clear.
2. **Can PHANTOM detect "TSPU is interfering" at runtime cheaply enough to skip direct-Tor?** Probing wastes battery; we need a quick decision heuristic.
3. **What is the exact license / availability of the BridgeDB API for our release-rotation script?** Need confirmation that automated fetch is allowed and won't get rate-limited.
4. **Is Tor Project willing to allocate WebTunnel bridges specifically to PHANTOM's bundled list at release time?** If yes we get higher-quality fresh bridges. If no we pull from public pools.
5. **iOS Connect Assist — does Onion Browser publish their bundled bridge list and rotation policy?** Could be a model.
6. **What does PHANTOM do when Telegram itself is blocked in Russia?** Telegram has been threatened repeatedly. We need a backup recovery channel — possibly email-to-`bridges@torproject.org` with a one-tap mailto.
7. **Real-world A/B test in Russia:** how often does the bundled-bridge approach succeed first-try in 2026, and after how many days from APK release does success rate drop materially? We need our own measurement.
8. **Snowflake's high battery cost on mobile (WebRTC keep-alive).** Quantify with a real test on Tecno hardware vs WebTunnel — choice of default may swap.
9. **Onion-service descriptor publication on a bridged Tor circuit** — is it more failure-prone than client-side usage? Briar's experience suggests yes; need to measure.
10. **Legal note:** is bundling Tor + bridges in an APK distributed in Russia an issue under current Russian law? Tor itself is not formally illegal, but VPN/circumvention apps have been pulled from Russian Play. Get a quick legal read.

---

## Sources

- [Russia started blocking Tor — OONI, 2021-12](https://ooni.org/post/2021-russia-blocks-tor/)
- [Responding to Tor censorship in Russia — Tor Project blog](https://blog.torproject.org/tor-censorship-in-russia/)
- [Staying ahead of censors in 2025 — Tor Project blog](https://blog.torproject.org/staying-ahead-of-censors-2025/)
- [Tor in Russia: A call for more WebTunnel bridges — Tor Project blog](https://blog.torproject.org/call-for-webtunnel-bridges/)
- [Hiding in plain sight: Introducing WebTunnel — Tor Project blog, 2024](https://blog.torproject.org/introducing-webtunnel-evading-censorship-by-hiding-in-plain-sight/)
- [Snowflake Daily Operations October 2025 update — Tor Project Forum](https://forum.torproject.org/t/snowflake-daily-operations-october-2025-update/20752)
- [Snowflake Daily Operations November 2025 update — Tor Project Forum](https://forum.torproject.org/t/snowflake-daily-operations-november-2025-update/20907)
- [Snowflake Daily Operations December 2025 update — Tor Project Forum](https://forum.torproject.org/t/snowflake-daily-operations-december-2025-update/21066)
- [Snowflake, a censorship circumvention system using temporary WebRTC proxies — Bocovich et al., USENIX Security 2024](https://www.usenix.org/system/files/usenixsecurity24-bocovich.pdf)
- [Snowflake Tor Project page](https://snowflake.torproject.org/)
- [OONI Tor Snowflake test](https://ooni.org/nettest/tor-snowflake/)
- [Apparently, a new wave of Tor blocking is underway in Russia — Tor Project Forum, 2025](https://forum.torproject.org/t/apparently-a-new-wave-of-tor-blocking-is-underway-in-russia/21006)
- [So, Russia began blocking Webtunnel bridges, which is good actually — Tor Project Forum](https://forum.torproject.org/t/so-russia-began-blocking-webtunnel-bridges-which-is-good-actually/19538)
- [Tor needs 200 new WebTunnel bridges to fight censorship — BleepingComputer](https://www.bleepingcomputer.com/news/security/tor-needs-200-new-webtunnel-bridges-to-fight-censorship/)
- [Tor Issues Urgent Call for WebTunnel Bridge Operators — CyberInsider](https://cyberinsider.com/tor-issues-urgent-call-for-webtunnel-bridge-operators-to-curb-blocks/)
- [Tor Project needs 200 WebTunnel bridges more to bypass Russia's censorship — SecurityAffairs](https://securityaffairs.com/171601/digital-id/tor-project-needs-200-webtunnel-bridges.html)
- [Connecting to Tor from censored regions — Tor Browser Manual](https://support.torproject.org/tor-browser/circumvention/connecting-from-censored-regions/)
- [WebTunnel announcement on tor-relays — Tor Project Forum](https://forum.torproject.org/t/tor-relays-announcement-webtunnel-a-new-pluggable-transport-for-bridges-now-available-for-deployment/8180)
- [The Telegram bot @GetBridgesBot now supports WebTunnel bridges — Tor Project Forum](https://forum.torproject.org/t/the-telegram-bot-getbridgesbot-now-supports-webtunnel-bridges/20127)
- [Request a bridge from Telegram — Tor Project Forum](https://forum.torproject.org/t/request-a-bridge-from-telegram/3277)
- [The Tor Project on X: Russia bridge instructions — 2021-12](https://x.com/torproject/status/1473409444721532930)
- [How to use the "meek" pluggable transport — Tor Project blog](https://blog.torproject.org/how-use-meek-pluggable-transport/)
- [Domain Fronting Is Critical to the Open Web — Tor Project blog](https://blog.torproject.org/domain-fronting-critical-open-web/)
- [Conjure pluggable transport project — Tor Project GitLab](https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/conjure/)
- [Conjure call for testers on Tor Browser Alpha — Tor Project Forum](https://forum.torproject.org/t/call-for-testers-help-the-tor-project-to-test-conjure-on-tor-browser-alpha/7815)
- [Tor (dirauths and default bridges) blocked by certain Russian ISPs since 2021-12-01 — net4people/bbs issue #97](https://github.com/net4people/bbs/issues/97)
- [Tor bridges affected by unrelated block of connections to some ISPs in Russia — Tor Project Forum, 2025](https://forum.torproject.org/t/tor-bridges-affected-by-unrelated-block-of-connections-to-some-isps-in-russia/21053)
- [Connecting to Tor from censored regions / unblocking Tor — Tor support](https://support.torproject.org/tor-browser/circumvention/unblocking-tor/)
- [Getting bridges — Tor support](https://support.torproject.org/tor-browser/circumvention/getting-bridges/)
- [Keeping the internet free together: State of the Onion Community Day — Tor Project blog](https://blog.torproject.org/community-day-state-of-the-onion-2025/)
