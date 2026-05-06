# FlokiNET research — bridge-host candidate for `bridge2.phntm.pro`

Date: 2026-05-06
Author: Research agent (Claude Opus 4.7)
Purpose: Evaluate FlokiNET as the host for PHANTOM's second WebTunnel Tor bridge,
specifically for users on Russian carrier networks subject to TSPU's "16-kilobyte curtain"
(silent TLS-stream throttling of foreign datacenter IPs, deployed nationwide June 2025).

---

## 1. Company background and jurisdiction

FlokiNET ehf was founded in **2012 in Reykjavík, Iceland** as an offshore, privacy-focused
host that explicitly markets itself as "a safe harbor for freedom of speech, free press
and whistleblower projects". The legal entity is Icelandic; the Romanian subsidiary
**FLOKINET SRL** (tax code 37124482) handles the Bucharest POP.
[flokinet.is](https://flokinet.is/) ·
[listafirme.eu/flokinet-srl-37124482](https://listafirme.eu/flokinet-srl-37124482/)

Privacy posture is unusually strong for a commercial host:

- "We do not require any personal details or identification. Any valid email address is
  enough information to be a customer." — confirmed on FlokiNET's homepage.
- Payment options include **Monero, Bitcoin, Ethereum, Litecoin, Dash, Proton Wallet,
  cash by post**, plus PayPal / Wise / bank transfer for users who don't need anonymity.
- Operates Tor `.onion` mirrors of website, billing portal and blog.
- Stated policy: "protect customer identity and content unless there is a valid legal
  order from a competent court in the hosting jurisdiction" — i.e., Iceland / Romania
  / Finland / Netherlands courts only; no MLAT auto-forwarding to RU.

Sources: [flokinet.is homepage](https://flokinet.is/) ·
[FlokiNET Review 2026 — websiteplanet](https://www.websiteplanet.com/web-hosting/flokinet/) ·
[FlokiNET Review 2026 — gist okye72](https://gist.github.com/okye72/9ea060dcc8054044ca9371d5e63d9c59)

## 2. ASN and BGP profile

FlokiNET operates **AS200651 ("FlokiNET ehf")**, registered in Iceland.
[bgp.he.net/AS200651](https://bgp.he.net/AS200651) ·
[bgp.tools/as/200651](https://bgp.tools/as/200651) ·
[ipinfo.io/AS200651](https://ipinfo.io/AS200651)

Key BGP facts:

- **28 IPv4 prefixes / ~6,912 IPs**, 6 IPv6 prefixes.
- Notable blocks: `37.156.68.0/24`, multiple `185.100.x.0/24`, `213.218.160.0/24`.
- **Upstream transit (per bgp.he.net):**
  - Advania Island (AS50613) — Iceland fibre
  - Inter.link GmbH (AS5405) — Germany
  - GlobalConnect AB (AS12552) — Sweden / Nordic
  - Voxility LLP (AS3223) — UK / Romania DDoS-scrubbing transit
  - Serverius Holding (AS50673) — Netherlands
  - Link11 GmbH (AS34309) — Germany DDoS
- 38 observed peers, 32 RPKI-valid prefixes, average AS-path length 4.69.
- Member of **InterLAN exchange (Bucharest)** for the RO POP.

The AS-path profile is small, heterogeneous, and **not currently on any known TSPU
"16 KB curtain" target list**. Independent measurements
([the IMC '22 TSPU paper](https://ensa.fi/papers/tspu-imc22.pdf) and
[net4people/bbs #490](https://github.com/net4people/bbs/issues/490)) explicitly name
**Hetzner, DigitalOcean, OVH, and Cloudflare** as the throttled mass-market datacenters;
FlokiNET does not appear in any published throttling list and Tor's blog and
[support.torproject.org circumvention guide](https://support.torproject.org/tor-browser/circumvention/connecting-from-censored-regions/)
explicitly recommend "less well-known providers" rather than "major hosts like OVH,
Hetzner, Linode, or DigitalOcean" — FlokiNET fits that recommendation.

Tor Project's
[WebTunnel campaign post](https://blog.torproject.org/call-for-webtunnel-bridges/)
contains the verbatim warning: **"Do not host your bridges with Hetzner."**

## 3. POPs (datacenter locations) and per-POP assessment

FlokiNET advertises four POPs ([flokinet.is](https://flokinet.is/)):

| POP            | Jurisdiction      | Geographic latency to RU users (est.)          | TSPU exposure        | Lawful-intercept risk                                            |
|----------------|-------------------|------------------------------------------------|----------------------|------------------------------------------------------------------|
| **Reykjavík**  | Iceland           | High (180–250 ms; trans-Atlantic + UK leg)     | Not flagged          | Lowest; Iceland has no MLAT with RF and strong press-freedom law |
| **Bucharest**  | Romania (EU/NATO) | **Low (40–70 ms; direct EU peering)**          | **Not flagged**      | EU lawful-intercept exists but no RF MLAT path                   |
| **Helsinki**   | Finland (EU/NATO) | Lowest (20–40 ms to Moscow / SPb)              | Slight elevated risk | EU; Finland borders RF — political pressure conceivable          |
| **Amsterdam**  | Netherlands (EU)  | Medium (35–55 ms via AMS-IX)                   | **Not flagged**      | EU; well-tested for circumvention infra                          |

Notes:

- **Iceland POP** ships via Advania (AS50613) and ultimately submarine fibre — slow
  for RU users; not a good fit for an interactive WebTunnel bridge.
- **Bucharest POP** is the cheapest, has 1 Tbps+ DDoS scrubbing included, transits via
  Voxility (AS3223) and InterLAN. Latency to Moscow ~50 ms. Voxility itself is large
  enough to *potentially* attract TSPU attention, but as of net4people/bbs reporting
  through April 2026 it has not been throttled at the AS level. The FlokiNET
  ([Romania VPS III at €27.99/mo on serverhunter](https://www.serverhunter.com/offer/flokinet-romania-vps-iii/))
  product line is the most popular for circumvention infra.
- **Helsinki POP** would give the lowest latency, but Finland's geographic and political
  proximity to Russia post-2022 means it is the *most likely* of the four to face direct
  TSPU targeting if the Kremlin escalates. We rank it second on latency, third on risk.
- **Amsterdam POP** is well-balanced: low-medium latency, EU jurisdiction, no current
  TSPU flag. Serverius (AS50673) transit is established but not a primary TSPU target.

## 4. Empirical RU-operator reports

Direct, dated empirical reports specifically for FlokiNET-hosted bridges in Russia
are **scarce**. The signal we do have:

- The Tor Project's
  [Good/Bad ISPs page](https://community.torproject.org/relay/community-resources/good-bad-isps/)
  lists FlokiNET (AS200651, Iceland) as **"Supports bridges, relays, and exits"** with
  a positive note ("Sponsor enn.lu and saveyourprivacy exit nodes"). FlokiNET appears
  in the *good* category.
- FlokiNET's AS200651 currently hosts **~75 Tor relays** (20 middle, 55 exit) per
  [nusenu's OrNetStats](https://nusenu.github.io/OrNetStats/w/as_number/AS200651.html)
  pushing ~8.8 Gbit/s. Bridge counts are not published (by design).
- The Tor Project's
  [staying-ahead-of-censors-2025 post](https://blog.torproject.org/staying-ahead-of-censors-2025/)
  and the [unblocking-Tor support page](https://support.torproject.org/tor-browser/circumvention/connecting-from-censored-regions/)
  both reiterate the "use less well-known providers" guidance. FlokiNET, with only
  ~6,900 IPv4 addresses, is by definition not a mass-market target.
- No issue in [net4people/bbs](https://github.com/net4people/bbs/issues) (filtered
  May 2025 – April 2026) reports FlokiNET ranges as throttled or blocked. The blocking
  conversation is dominated by Hetzner, OVH, DigitalOcean, Cloudflare per the
  [16-kilobyte curtain analysis](https://en.zona.media/article/2025/06/19/cloudflare).

Caveat: absence of evidence is not evidence of absence. We can only say FlokiNET is
**not yet known to be throttled**. The mitigation: deploy on FlokiNET, then OONI-test
from a Russian carrier vantage point within 7 days of deploy to confirm.

## 5. Tor-bridge policy (does FlokiNET allow it?)

Yes. FlokiNET *is itself* a major Tor relay AS (75 relays, exit-friendly). Tor
infrastructure is part of their explicit value proposition — see the
[FlokiNET privacy blog post on VPS / DDoS / privacy](https://blog.flokinet.is/2026/02/04/flokinet-vps-ddos-protection-privacy-hosting/).
A WebTunnel bridge (HTTPS-tunneled, much lower abuse surface than an exit) is well
within their AUP and requires no special ticket beyond the standard order.

## 6. Recommended POP and product

**Recommendation: FlokiNET Romania (Bucharest), VPS III tier.**

- **Specs:** 4 vCPU / 4 GB RAM / 90 GB NVMe / 9 TB monthly bandwidth /
  IPv4 + native IPv6 / 1 Tbps+ DDoS protection included.
- **Price:** €27.99/month + €5 one-time setup
  ([serverhunter listing](https://www.serverhunter.com/offer/flokinet-romania-vps-iii/)).
- A smaller VPS I (~2 vCPU / 2 GB / 40 GB / lower BW) is sufficient for a single
  WebTunnel bridge and is in the **€7.50 – €12 /month** range per the
  [hostingrevelations Tor-VPS roundup](https://hostingrevelations.com/tor-vps-hosting/)
  and [FlokiNET Review 2026 — websiteplanet](https://www.websiteplanet.com/web-hosting/flokinet/).
  For PHANTOM's expected RU bridge load (low hundreds of clients in pilot) **VPS I
  is enough**; pick VPS II/III only if we plan to consolidate bridge2 + future
  Snowflake-broker on the same box.

**Alternates within FlokiNET:** Amsterdam POP if Bucharest IPs end up flagged later,
Helsinki only as a third fallback.

## 7. Cost estimate

| Plan        | Specs                       | €/month | Setup | Notes                           |
|-------------|-----------------------------|---------|-------|---------------------------------|
| Romania VPS I  | 1 vCPU / 2 GB / 40 GB / DDoS | ~€7.50 | €5    | Sufficient for one WebTunnel bridge |
| Romania VPS II | 2 vCPU / 2 GB / 60 GB / DDoS | ~€14.99 | €5    | Comfortable headroom             |
| Romania VPS III | 4 vCPU / 4 GB / 90 GB / DDoS | €27.99 | €5    | Future expansion                 |

Annual cost if we go with VPS I and pre-pay 12 months: **~€95** including setup —
roughly the same order of magnitude as the existing Hetzner relay box.

---

## Sources cited

- [FlokiNET — homepage](https://flokinet.is/)
- [FlokiNET — VPS plans page](https://flokinet.is/vps-server.php) (404 at fetch time, content via cache)
- [FlokiNET privacy blog — 2026-02-04 post](https://blog.flokinet.is/2026/02/04/flokinet-vps-ddos-protection-privacy-hosting/)
- [FlokiNET Romania VPS III on Serverhunter](https://www.serverhunter.com/offer/flokinet-romania-vps-iii/)
- [FlokiNET Review 2026 — Websiteplanet](https://www.websiteplanet.com/web-hosting/flokinet/)
- [FlokiNET Review 2026 — gist okye72](https://gist.github.com/okye72/9ea060dcc8054044ca9371d5e63d9c59)
- [bgp.he.net — AS200651](https://bgp.he.net/AS200651)
- [bgp.tools — AS200651](https://bgp.tools/as/200651)
- [PeeringDB — AS200651](https://www.peeringdb.com/asn/200651)
- [nusenu OrNetStats — AS200651](https://nusenu.github.io/OrNetStats/w/as_number/AS200651.html)
- [Tor Project Good/Bad ISPs](https://community.torproject.org/relay/community-resources/good-bad-isps/)
- [Tor Project blog — Call for WebTunnel bridges](https://blog.torproject.org/call-for-webtunnel-bridges/)
- [Tor Project blog — Staying ahead of censors 2025](https://blog.torproject.org/staying-ahead-of-censors-2025/)
- [Tor support — Connecting from censored regions](https://support.torproject.org/tor-browser/circumvention/connecting-from-censored-regions/)
- [zona.media — The 16-kilobyte curtain (EN)](https://en.zona.media/article/2025/06/19/cloudflare)
- [net4people/bbs — Issue #490 Censor has new method](https://github.com/net4people/bbs/issues/490)
- [TSPU IMC '22 paper (Xue et al.)](https://ensa.fi/papers/tspu-imc22.pdf)
- [FLOKINET SRL Romanian company registry](https://listafirme.eu/flokinet-srl-37124482/)
