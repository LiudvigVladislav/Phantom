# Alternative ISPs survey — fallbacks if FlokiNET turns out unviable

Date: 2026-05-06
Scope: Hostkey NL, M247 small POPs, BuyVM (Frantech), PQ Hosting (Moldova).
Goal: One short section per provider so we can pivot quickly if FlokiNET deploy fails
TSPU validation.

---

## A. Hostkey NL

- **Jurisdiction:** Hostkey B.V., Amsterdam, the Netherlands. EU jurisdiction; standard
  EU data-retention and lawful-intercept law.
- **ASN status against TSPU:** Hostkey operates from multiple ASes (notably AS395839
  and reseller blocks). Not on the published TSPU "16 KB curtain" hot-list per
  [net4people/bbs #490](https://github.com/net4people/bbs/issues/490) and the
  [zona.media analysis](https://en.zona.media/article/2025/06/19/cloudflare) — but
  Hostkey is large enough that this could change.
- **RU empirical reports:** Hostkey is well-known in the RU sysadmin community; bridges
  there are anecdotally reachable from Russia, but **no Tor-Project-endorsed report
  vouches for them**.
- **Cost:** NL VDS plans start at ~€7-10/month for a 2 vCPU / 2 GB / 40 GB box per
  [Hostkey Netherlands VDS page](https://hostkey.com/vps/vds-netherlands/).
- **Privacy posture:** **Mandatory KYC.** Per
  [Hostkey KYC page](https://hostkey.com/about-us/kyc-verification/), all customers
  undergo identity verification under EU/NL AML rules; even Bitcoin payments go through
  BitPay which itself enforces KYC.
- **Gotchas — DEAL-BREAKER:** Per [Hostkey legal terms](https://hostkey.com/about-us/legal/),
  Hostkey **does not provision services to citizens or residents of the Russian
  Federation**, plus a sanctions list. Vladislav, who is operating from RF, **cannot
  legally sign up**. This rules Hostkey out for PHANTOM unless we route the order
  through a non-RU foundation entity later.

## B. M247 small POPs

- **Jurisdiction:** M247 Europe SRL is registered in Romania (Bucharest); parent
  M247 Ltd is UK. Operates AS9009. ([bgp.tools/as/9009](https://bgp.tools/as/9009),
  [m247global.com](https://www.m247global.com/))
- **ASN status against TSPU:** AS9009 is a **large IP estate** (M247 has 55+ POPs in
  36 cities). Large mass-market hosts with extensive RF-VPN customer bases are exactly
  the kind of provider TSPU has historically pre-emptively flagged. AS9009 has had
  individual /24s blocked for unrelated reasons in the past (see
  [Tor forum: Tor bridges affected by unrelated block](https://forum.torproject.org/t/tor-bridges-affected-by-unrelated-block-of-connections-to-some-isps-in-russia/21053)).
  **Risk: medium-high** that any AS9009 prefix already attracts elevated TSPU attention.
- **RU empirical reports:** Mixed. M247 small POPs (Sofia, Prague, Vienna) have been
  used for circumvention infra and are *sometimes* reachable; M247 Bucharest /
  Frankfurt / London are *more often* throttled.
- **Cost:** M247 doesn't sell directly to small VPS customers in most regions; you
  buy via a reseller. Realistic ballpark: **€10–25/month** for 2 vCPU / 2 GB.
- **Privacy posture:** Standard EU host; KYC for invoiced customers; cryptocurrency
  via reseller varies.
- **Gotchas:** Reseller chain reduces ability to negotiate AUP; M247 is also famous
  in the OSINT community as a heavy VPN/proxy host
  ([HN thread on M247 suspicions](https://news.ycombinator.com/item?id=22086904)) —
  exactly the property TSPU likes to target.

## C. BuyVM / Frantech

- **Jurisdiction:** Frantech Solutions (parent), incorporated in Las Vegas, USA;
  Luxembourg POP operated as a separate legal entity. Operates **AS53667 ("Ponynet")**.
- **ASN status against TSPU:** AS53667 is **not on any published TSPU throttling
  list**. The Tor Project Good/Bad ISPs page lists Frantech/Ponynet as bridge-friendly
  ([forum thread tracking it](https://forum.torproject.org/t/good-bad-isps-frantech-ponynet-as53667-buyvm-net/2485)).
  US POPs (LV, NJ, Miami) have **high RU latency** (150-200 ms); only the **Luxembourg
  POP** is geographically viable for RU users.
- **RU empirical reports:** No specific RU-reachability dataset, but BuyVM is well
  represented in the Tor relay set and has not been singled out for RU blocking.
- **Cost:** **Among the cheapest** in this set — KVM VPS from $2-7/month for 1-2 GB
  RAM with unmetered 1 Gbps ([BuyVM review 2026 — gist](https://gist.github.com/svgolj7/cfeb3604285239f241c6b1f876c934e8),
  [LowEndBox listing](https://lowendbox.com/blog/buyvm-7m-kvm-2gb-ram-40gb-ssd-2tb-bw-in-las-vegas/)).
  Luxembourg POP slightly more expensive (~$5-10/mo for the equivalent box).
- **Privacy posture:** Accepts Bitcoin/crypto; **does not require PII when paying with
  crypto** ([BuyVM Frantech review summary](https://gist.github.com/svgolj7/cfeb3604285239f241c6b1f876c934e8)).
  Strong public stance on free speech / anti-censorship — Frantech AUP explicitly
  permits Tor exits/relays/bridges with a courtesy ticket
  ([buyvm.net/acceptable-use-policy](https://buyvm.net/acceptable-use-policy/)).
- **Gotchas:** Stock-outs are common — Luxembourg slabs sell out for weeks at a time.
  US-jurisdiction means a (theoretically) higher MLAT exposure than Iceland/Romania,
  though Frantech's anti-subpoena stance is well-documented.

## D. PQ Hosting (Moldova)

- **Jurisdiction:** PQ.Hosting SRL, **Chișinău, Moldova**. Non-EU but
  Council-of-Europe member. Geographically adjacent to RF.
- **ASN status against TSPU:** PQ runs across multiple AS announcements (incl. their
  "global catalog" of 12+ POPs per [pq.hosting](https://pq.hosting/en)). Some PQ
  Russian ranges are *inside* Russia (Moscow POP) — those are obviously unsuitable.
  Moldova/NL POPs are not on any published TSPU throttling list, but PQ's heavy use
  by RU-VPN resellers makes mass-flagging plausible.
- **RU empirical reports:** PQ is heavily used by RU VPN resellers and has had
  individual prefixes flagged before. No clean confirmation that fresh PQ IPs work
  reliably as Tor bridges from inside RF.
- **Cost:** Cheap. ~€4-8/month for a 1-2 GB VPS at most POPs.
- **Privacy posture:** **Significantly degraded.** Per
  [PQ's own security-measures post](https://pq.hosting/en/news/pqhosting-strengthens-security-measures-for-customers):
  if a registration request comes via Tor, the user is automatically pushed into KYC
  and **cannot order until verified**. Multiple registrations from the same IP also
  trigger forced KYC.
- **Gotchas — DEAL-BREAKER for our threat model:**
  1. KYC-on-Tor policy directly contradicts our requirement for anonymous-friendly
     payment.
  2. Their public statement that they **only currently accept credit-card payments**
     (per the websearch summary of their site) due to "geopolitical circumstances"
     forecloses Monero/BTC at signup.
  3. Moldova is geographically and politically vulnerable to RF pressure; lawful-
     intercept exposure is unclear.

---

## Comparison summary

| Provider     | Sign-up viable for Vladislav (RF resident)? | Anon payment? | TSPU risk | Latency to RU | Verdict                              |
|--------------|---------------------------------------------|---------------|-----------|---------------|--------------------------------------|
| **FlokiNET (RO)** | Yes (no KYC, any email)                    | Yes (Monero)  | Low       | Low (~50 ms)  | **PRIMARY pick** (see doc 99)        |
| Hostkey NL   | **NO — refuses RF residents**               | No (KYC)      | Med       | Low           | Eliminated                           |
| M247         | Yes (via reseller)                          | Varies        | Med-High  | Low-Med       | Risky; reseller-only                 |
| BuyVM (LU)   | Yes                                         | Yes (BTC)     | Low       | Med           | **Strong fallback #1**               |
| PQ Hosting   | Technically yes                             | **Currently CC-only; KYC-on-Tor** | Med-High | Low | Eliminated for our threat model |

---

## Sources cited

- [Hostkey — Netherlands VDS](https://hostkey.com/vps/vds-netherlands/)
- [Hostkey — KYC verification policy](https://hostkey.com/about-us/kyc-verification/)
- [Hostkey — General Terms (sanctions list)](https://hostkey.com/about-us/legal/)
- [bgp.tools — AS9009 M247 Europe SRL](https://bgp.tools/as/9009)
- [M247 Global homepage](https://www.m247global.com/)
- [Hacker News — Suspicion regarding M247](https://news.ycombinator.com/item?id=22086904)
- [Tor forum — bridges affected by unrelated block](https://forum.torproject.org/t/tor-bridges-affected-by-unrelated-block-of-connections-to-some-isps-in-russia/21053)
- [Tor forum — Frantech/Ponynet (AS53667) good ISP entry](https://forum.torproject.org/t/good-bad-isps-frantech-ponynet-as53667-buyvm-net/2485)
- [BuyVM Acceptable Use Policy](https://buyvm.net/acceptable-use-policy/)
- [BuyVM Frantech 2026 review (gist)](https://gist.github.com/svgolj7/cfeb3604285239f241c6b1f876c934e8)
- [LowEndBox — BuyVM Las Vegas $7/mo listing](https://lowendbox.com/blog/buyvm-7m-kvm-2gb-ram-40gb-ssd-2tb-bw-in-las-vegas/)
- [PQ.Hosting global catalog](https://pq.hosting/en)
- [PQ.Hosting strengthens security measures (KYC-on-Tor)](https://pq.hosting/en/news/pqhosting-strengthens-security-measures-for-customers)
- [net4people/bbs #490 — TSPU 16 KB curtain analysis](https://github.com/net4people/bbs/issues/490)
- [zona.media — 16-kilobyte curtain](https://en.zona.media/article/2025/06/19/cloudflare)
