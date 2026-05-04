# NO-GO check #2: Vanguards Proposal 292 deployability

**Verdict:** CONDITIONAL (lean NO-GO for full Prop 292; GO for Vanguards-Lite as the
realistic baseline)

PHANTOM's `06-security-analysis.md` calls full Vanguards "mandatory" against the 2024
SUMo flow-correlation attack. The reality in mid-2026 is more nuanced: a weaker
variant (Vanguards-Lite, Prop 333) is on by default in every stable Tor, while the
full Prop 292 implementation is only available via an external Python addon that is
effectively in maintenance freeze. The "mandatory" framing has to be walked back to
"Vanguards-Lite is automatic; full Vanguards is a best-effort add-on with caveats."

## Native vs addon

Two different things share the "vanguards" name:

- **Vanguards-Lite (Proposal 333)** — implemented natively in C-tor since
  `0.4.7.1-alpha` and **on by default** for all onion services and onion clients in
  every current stable (0.4.7.x and 0.4.8.x). No config flags, no extra process. The
  spec mandates: "Vanguards-Lite MUST be the default for all onion service and onion
  client activity." It uses one extra fixed layer (L2) and keeps standard path
  length, so there is essentially zero performance cost.
- **Full Vanguards (Proposal 292, "Mesh Vanguards")** — only **partially** in
  C-tor. The L2 pinning logic landed; the L3 layer, bandwidth-side-channel guards
  (bandguards), and rendezvous-point rotation logic still live in the external
  Python addon `mikeperry-tor/vanguards`. Prop 292 is listed as "Closed" in the
  torspec proposal index because the lite variant superseded it as the in-tree
  approach. There is no roadmap entry to fold the rest of Prop 292 into C-tor.
- **Arti** got a Vanguards implementation in Arti 1.2.2 (Aug 2024), but Arti is not
  yet a drop-in replacement for the C daemon for hidden-service hosting on a Linux
  VPS, so this does not change the deployment picture for PHANTOM's relay today.

## Maintenance status of mikeperry-tor/vanguards

- Last commit on `master`: **2023-10-31**, and the four most-recent commits are all
  Dependabot dependency bumps (`requests`, `urllib3`, `certifi`). The last
  substantive code change is from **2021-07-09** (vanguards-lite path verification).
- **Zero GitHub releases** ever published.
- 23 open issues, 6 open PRs as of the snapshot, with no merges or maintainer replies
  since 2024.
- **Removed from Debian Trixie** and consequently absent from Whonix 18 — distro
  packagers have effectively dropped it.
- Conclusion: the addon is unmaintained in practice. It still works against current
  C-tor control-port APIs, but it has no security review since 2021, and any future
  incompatibility from a tor daemon change will not be fixed upstream.

## Tor Project current recommendation

- For onion services with uptime under ~1 month: Vanguards-Lite (built-in, default)
  is sufficient. This explicitly covers OnionShare-style ephemeral services.
- For long-lived onion services: the spec still recommends full Vanguards "if you
  can run the addon," but the project does not provide it as a packaged product and
  is not actively maintaining the implementation. Forum guidance to operators in
  2024-2025 has shifted toward "use the built-in defaults" rather than pushing the
  addon.
- The 2024 Tor Project newsletter highlighted Arti's Vanguards support as the
  forward path; no equivalent push for the C addon.

## Deployment gotchas (if PHANTOM still wants the addon)

- Python 3 process running alongside `tor`, talking to the ControlPort/ControlSocket
  with the management cookie. One more thing to package, supervise, and monitor on
  the relay VM.
- Adds extra hops on rendezvous circuits — measurable latency hit on every
  message/call setup over the onion path. Not catastrophic but noticeable on
  cellular.
- No upstream security responder. PHANTOM would inherit informal ownership of any
  CVE that lands in the Python code or its deps.
- Debian/Ubuntu apt path is gone in current stable; install would be `pip` from
  source, which conflicts with hardened/minimal images.
- Does not protect against SUMo's flow-correlation attack at the network layer —
  that attack is end-to-end and mostly orthogonal to guard-discovery defences.

## Risk if we proceed without the addon (Vanguards-Lite only)

- Guard-discovery: an adversary running ~5% of Tor middle relays can locate the
  L2 guard of a long-lived onion in weeks rather than months. For a long-lived
  PHANTOM relay this is real but not catastrophic; the mitigation is rotating the
  onion address or accepting the risk for an alpha.
- SUMo (NDSS 2024): Vanguards is **not the right defence here.** SUMo is a flow-
  correlation attack achieving ~99.6% precision over multi-minute sessions; it
  requires AS/ISP-level vantage on both ends. Full Vanguards reduces guard-
  *discovery* probability over time, which makes the AS-level vantage harder to
  obtain, but does not break the correlation itself once vantage exists. The
  defences that actually matter against SUMo are traffic-padding (circuit padding
  machines, already shipped in C-tor) and limiting per-session duration — not Prop
  292 specifically.
- The "Vanguards mandatory against SUMo" line in PHANTOM's `06-security-analysis.md`
  is technically incorrect framing and should be rewritten.

## Mitigation if NO-GO

1. **Walk back the "mandatory Vanguards" claim** in `06-security-analysis.md`. Replace
   with: "Tor 0.4.7+ enables Vanguards-Lite by default, which is our baseline.
   Full Prop 292 is not deployable on a maintained code path and is deferred."
2. Document SUMo as a residual risk with the correct mitigations: short-lived
   sessions where possible, padding machines (already on), no claim of resistance
   to global ISP-level adversaries.
3. Re-evaluate when (a) Arti reaches feature-parity for hidden-service hosting and
   we can swap the relay daemon, or (b) a new maintainer ships a packaged Vanguards
   replacement. Track Arti releases as the trigger.
4. Optional, low-priority: pilot the Python addon on the Helsinki relay only, with
   the explicit understanding that we own any breakage. Not recommended for alpha.

## Sources

- Tor design proposal 292 (Mesh Vanguards), status Closed:
  https://spec.torproject.org/proposals/292-mesh-vanguards.html
- Tor design proposal 333 (Vanguards-Lite):
  https://spec.torproject.org/proposals/333-vanguards-lite.html
- Vanguards-Lite spec, default-on language:
  https://spec.torproject.org/vanguards-spec/vanguards-lite.html
- mikeperry-tor/vanguards repo (commit history confirms last commit 2023-10-31,
  last code change 2021-07-09, zero releases):
  https://github.com/mikeperry-tor/vanguards
  https://github.com/mikeperry-tor/vanguards/commits/master
  https://github.com/mikeperry-tor/vanguards/releases
- Tor Project blog, "Announcing the Vanguards Add-On for Onion Services" (2018,
  original announcement, still the canonical doc):
  https://blog.torproject.org/announcing-vanguards-add-onion-services/
- Tor Project blog, "Announcing Vanguards Support in Arti" (2024-08):
  https://blog.torproject.org/announcing-vanguards-for-arti/
- Tor Project newsletter, August 2024 (Arti Vanguards highlight):
  https://newsletter.torproject.org/archive/2024-08-01-major-tb-release-arti-vanguard-support-defcon-32/text/
- Whonix wiki on Vanguards (notes Debian Trixie removal, Whonix 18 absence):
  https://www.whonix.org/wiki/Vanguards
- Tor forum, "Should I use Vanguards for Onion Services?":
  https://forum.torproject.org/t/should-i-use-vanguards-for-onion-services/21200
- SUMo paper, NDSS 2024 (the attack the recommendation tries to address):
  https://www.ndss-symposium.org/wp-content/uploads/2024-337-paper.pdf
