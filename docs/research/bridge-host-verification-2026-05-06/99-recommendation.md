# Recommendation — second WebTunnel bridge host (`bridge2.phntm.pro`)

Date: 2026-05-06
Decision owner: Vladislav
Research basis: `01-flokinet-research.md`, `02-alternative-isps-research.md`

---

## Recommendation

**Order one VPS at FlokiNET, Bucharest (Romania) POP, VPS I tier (~€7.50/month + €5 setup), pay with Monero.**

## Why this over the alternatives — single decisive paragraph

FlokiNET is the only candidate that simultaneously satisfies *all three* hard
constraints PHANTOM cannot relax: (1) it accepts a Russian-resident customer with no
KYC and no PII (Hostkey refuses RF residents outright; PQ Hosting forces KYC the moment
a Tor IP touches signup); (2) it accepts Monero (PQ currently accepts only credit cards;
Hostkey routes all crypto through KYC'd BitPay); and (3) its AS200651 (~6,900 IPv4
addresses, 28 prefixes) is small enough not to appear on any of the three published
TSPU "16-kilobyte curtain" target lists, while M247's AS9009 — a household name in the
RU-VPN reseller market — is exactly the kind of mass-host TSPU pre-emptively flags.
BuyVM/Frantech is the only other candidate that clears all three filters, but its
geographically nearest POP is Luxembourg, giving 80–120 ms more latency to Russian
users than Bucharest, and Luxembourg slabs frequently sell out for weeks. FlokiNET
Bucharest wins on latency, jurisdiction, payment, and AS-stealth simultaneously, and
the Tor Project itself lists FlokiNET in its Good ISPs page as bridge-friendly.

## Risks accepted

1. **Empirical RU reachability is inferred, not measured.** No Tor-Project blog post
   names FlokiNET as "verified working from RF." We are inferring from (a) absence
   from the throttled list and (b) Tor Project's general "use less well-known providers"
   guidance. **Mitigation:** OONI-test from a Russian carrier vantage point within
   7 days of deploy.
2. **Voxility (AS3223) is one of FlokiNET RO's transit upstreams.** Voxility is large
   enough that an AS-level TSPU action against it would also collateral-damage the RO
   POP. **Mitigation:** if observed, fail over to FlokiNET Amsterdam (different transit
   mix via Serverius AS50673).
3. **Romania is a NATO/EU state with lawful-intercept law.** A Romanian court order
   could compel logs. FlokiNET's stated policy mitigates this for non-court requests
   only. **Acceptance:** consistent with the existing Hetzner relay's threat model;
   bridge2 holds no user content, only WebTunnel TLS metadata.
4. **Single-vendor concentration.** Both Hetzner relay and FlokiNET bridge are
   single-vendor. **Mitigation:** the *bridge* layer is now on a different vendor and
   different jurisdiction from the relay, so vendor-correlated takedown is reduced.

## Concrete operator action items

| # | Action                                                                    | Owner      |
|---|---------------------------------------------------------------------------|------------|
| 1 | Order **FlokiNET Romania VPS I**: 1 vCPU / 2 GB RAM / 40 GB NVMe / DDoS / IPv4+IPv6, location **Bucharest**, payment **Monero** | Vladislav |
| 2 | Use a fresh email alias (Proton or `phntm.pro` infra alias). No personal info. | Vladislav |
| 3 | DNS: create A + AAAA `bridge2.phntm.pro` pointing at the new VPS IPs (Cloudflare DNS allowed for the *DNS record only*; we are NOT proxying through CF). | Vladislav |
| 4 | Install Debian 12, harden (SSH key only, no root login, ufw default-deny, unattended-upgrades). | Operator skill |
| 5 | Deploy WebTunnel bridge per existing OnionWrapper/Stage-5C playbook (`docs/transport/STAGE_5C_WEBTUNNEL.md`). Reuse the operator-bridges deploy script. | Operator skill |
| 6 | Add the new bridge line to `OperatorBridges.WEBTUNNEL` in source (mirror what was just done for bridge1 in commit `80d1e550`). | Dev |
| 7 | OONI-probe / RU-vantage measurement within 7 days of deploy. | Vladislav (manual) |
| 8 | Update `docs/project/MASTER_TIMELINE_2026.md` with the deploy date and verification result. | Dev / Vladislav |

**Expected monthly cost:** **€7.50** (€95/year prepaid). Setup: **€5** one-off.
**Expected provisioning time:** under 24 h (FlokiNET advertises automated VPS
provisioning on Romania stock; Monero confirmation 20–60 min, then auto-deploy).

**Hostname:** `bridge2.phntm.pro` (matches existing naming convention from bridge1).

## Conditions to revisit

Trigger a re-evaluation (with this same doc set as the starting point) **if any of**:

- A. OONI / manual RU-carrier test within 14 days of deploy shows TSPU 16-KB throttling
  on the FlokiNET RO IP — fall back to **FlokiNET Amsterdam** (same vendor, different
  transit mix), then if also throttled, to **BuyVM Luxembourg**.
- B. AS200651 or AS3223 (Voxility) appears on a published TSPU target list within
  60 days — same fallback chain.
- C. FlokiNET changes its KYC or RF-resident policy — fall back to **BuyVM Luxembourg**
  via Monero.
- D. We need to deploy a *third* bridge for redundancy — pick BuyVM Luxembourg by
  default to diversify vendor + jurisdiction + AS.

---

*This document is the basis for a recurring infrastructure expense; review at next
quarterly Tor-stack maintenance window or sooner on any of the trigger conditions above.*
