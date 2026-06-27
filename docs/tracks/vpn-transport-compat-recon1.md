# Track: VPN-TRANSPORT-COMPAT-RECON1

**Type:** Facts-first reconnaissance / diagnostic. NOT a code-fix track.
**Status:** Open (mini-lock).
**Opened:** 2026-06-27, in parallel with the parking of REST-SEND-CONNECTIVITY-RECON1.
**Trigger:** 2026-06-26 I-2 attempt #1 was invalidated because the Mac host's VPN broke the Android emulator's outbound DNS / TCP path to `relay.phntm.pro`. The emu logs showed `UnknownHostException: Unable to resolve host "relay.phntm.pro"` while host-side `curl` continued to reach the same hostname via the OS system resolver. Disabling the VPN and restarting the emu cleanly restored end-to-end delivery.
**Last known master at open:** PR #337 squash `2ff13030` (plus the REST-SEND-CONNECTIVITY-RECON1 parking PR pending).

---

## §1 Goal

Discriminate the surface and the depth of Phantom's incompatibility with the operator running under VPN. Phantom is a privacy-oriented messenger; the product hypothesis is that it MUST work under VPN. The 2026-06-26 evidence shows it does not, at least in one configuration (Mac host VPN + Android emulator). This recon's job is to bound the incompatibility:

- which layer breaks (DNS / TCP / TLS / application protocol)
- which environment exhibits the break (AVD on Mac / AVD on Windows / real Android device on host VPN / real Android device with on-device VPN)
- which Phantom paths are affected (Direct WSS only / REST fallback also / prekey publish / bundle fetch / send / poll)

The recon produces an **evidence-graded compatibility matrix**, NOT a fix. No code change in this track. Any product fix is a separate scope-lock that comes only after the recon closes.

## §2 Scope

- Bound the failure surface and depth on at least one VPN configuration accessible to the operator (Mac host VPN, the configuration that surfaced the original evidence).
- Compare emulator behaviour against physical-device behaviour under the same VPN.
- Compare DNS / TCP / TLS / HTTPS reachability from the host vs from inside the emulator, with VPN on vs off.
- Map which Phantom code paths are affected vs not affected (Direct WSS vs REST fallback vs prekey publish vs bundle fetch vs send vs poll).

## §3 Out of scope

- **Any code change.** The recon discriminates; it does not propose, sketch, or land a code fix. If a fix is identified, a separate scope-lock follows.
- **RC PR #330.** Stays Draft / HOLD throughout. This recon does NOT block PR #330 — RC #330's gating is the Direct WSS / Mode 2 track, not this one.
- **REST-SEND-CONNECTIVITY-RECON1** (parked). The 2026-06-26 emu `connectFailed` burst evidence and its hypothesis matrix are NOT re-opened by this recon. If a future smoke catches a co-incident emu + host burst, the REST-SEND recon re-opens on its own terms — independent of this track.
- **Direct WSS Mode 2 root cause.** Separate parked track. If this recon observes Mode 2 ping-timeout behaviour while diagnosing VPN paths, it is logged as a side-finding and handed to the Direct WSS track; it is NOT scope-locked here.
- **Voice / media paths.** Voice transport-independence is already field-confirmed; if VPN regresses media specifically, that is a future Voice track concern, not this one.
- **Predictive remediation.** No "obviously the fix is X" framings. Specifically NOT pre-locked: bridge-mode emulator network, DNS-over-HTTPS bypass, custom OkHttp resolver, in-app VPN detection / warning UX. Each is a fix shape and is out of scope until evidence closes a gate.

## §4 Hypotheses to discriminate

Each hypothesis names a candidate locus / mechanism for the observed incompatibility. Only one (or a clean intersection of two) is expected to survive the matrix.

- **H-VPN-AVD-DNS** — The Mac host's VPN interferes with the QEMU user-mode NAT DNS proxy (`10.0.2.3`) that the Android emulator depends on. Surface: emu sees `UnknownHostException` while the host system resolver is unaffected. Physical Android devices on the same host VPN are not affected because they do not go through QEMU NAT.
- **H-VPN-AVD-NAT** — The Mac host's VPN routing interferes with the QEMU user-mode NAT layer below DNS, breaking outbound packet egress from the emu's virtual interface (`eth0 10.0.2.15`). DNS may appear to resolve transiently if cached; outbound TCP/TLS fails or stalls. The 2026-06-27 corrupted ICMP responses (`wrong data byte`, huge `time=` values, `time of day goes back`) point this direction.
- **H-VPN-OS-DNS** — The Mac host's VPN itself breaks system DNS resolution at the OS level. The 2026-06-26 evidence partially supports this: `dig relay.phntm.pro` timed out (`no servers could be reached`) while `curl` succeeded — suggesting OS resolver works but `dig`'s direct UDP/53 path does not. Emu inherits the broken DNS path via QEMU proxy.
- **H-VPN-HOST-ROUTING** — Selective host-routing rules introduced by the VPN client route `65.108.154.152/32` (or a containing prefix) through a tunnel that drops packets. Host curl might survive via system resolver doing a different lookup, but the actual TCP path to that IP is impaired for some clients and not others.
- **H-VPN-REAL-DEVICE-AFFECTED** — A physical Android device with VPN ON (either client-side VPN inside the device, or the device sharing the host VPN via tethering / hotspot) is ALSO unable to reach the relay. This would escalate the incompatibility from "AVD setup quirk" to "production blocker on any user with VPN".
- **H-VPN-DWSS-ONLY** — VPN breaks Direct WSS only, while REST fallback continues to deliver. This would mean the existing REST fallback path is enough to keep the messenger functional under VPN, even if Direct WSS is degraded.
- **H-VPN-REST-ALSO** — VPN breaks both Direct WSS and REST fallback. This is the worst-case product blocker shape: the messenger is non-functional under VPN regardless of the existing fallback.

H-VPN-AVD-DNS and H-VPN-AVD-NAT are not mutually exclusive — the original evidence is consistent with both being true simultaneously (DNS path AND NAT path both impaired).

## §5 Diagnostic instruments

Each instrument runs only when justified by the previous instrument's outcome. Each new-instrument decision is a track-doc amendment, not a free choice in the moment.

- **J-1 — Host VPN ON/OFF reachability matrix.** Timestamped `dig relay.phntm.pro`, `curl -v https://relay.phntm.pro/relay/poll`, `curl --resolve relay.phntm.pro:443:65.108.154.152 https://relay.phntm.pro/relay/poll` from the operator host with VPN OFF (baseline) and VPN ON. The first deliverable. Discriminates host-side OS DNS vs system resolver vs forced-IP path.
- **J-2 — AVD VPN ON/OFF reachability matrix.** From inside the emu via `adb -s $EMU shell`: `getprop net.dns1 / net.dns2`, `ping -c 3 relay.phntm.pro` and `ping -c 3 65.108.154.152`, plus a HTTPS probe if a usable client is present (`toybox` typically lacks `wget`; on some images `cmd connectivity` is restricted; the fallback is to inspect Phantom app logs while the operator drives a known message exchange under the VPN). Run with host VPN OFF (baseline) and with host VPN ON.
- **J-3 — Real-device under VPN.** Two sub-cases: (a) host VPN ON, physical Tecno tethered through the host (closest analogue to the AVD's situation); (b) on-device VPN client active on a physical Android device. For each, run a Phantom send-and-receive cycle and capture `logcat` plus the user-visible outcome. Discriminates H-VPN-REAL-DEVICE-AFFECTED.
- **J-4 — Layer-bisection for the surviving hypothesis.** If J-1 + J-2 land on H-VPN-AVD-DNS, run `nslookup relay.phntm.pro 10.0.2.3` from inside the emu to isolate the QEMU DNS proxy from the underlying network. If J-1 + J-2 land on H-VPN-AVD-NAT, capture a `tcpdump` on the Mac host's `vnic` interface during a failed emu attempt to confirm packets are or aren't egressing the host. The exact instrument is decided after J-1 + J-2 results.
- **J-5 — Phantom path-by-path.** With VPN on a known-affected configuration, run Phantom and selectively trigger Direct WSS only, REST fallback only, prekey publish, bundle fetch, send, poll. Determine which paths fail and which succeed. Discriminates H-VPN-DWSS-ONLY vs H-VPN-REST-ALSO.

The recon may add an instrument if a previous instrument's evidence demands one; it does not run instruments that the evidence does not require.

## §6 Acceptance gates

The recon CLOSES (handing off either to a fix scope-lock or to a Park decision) when the matrix is filled enough to support exactly one of the following verdicts:

1. **AVD-only verdict (H-VPN-AVD-DNS and/or H-VPN-AVD-NAT confirmed; H-VPN-REAL-DEVICE-AFFECTED refuted).** Physical Android devices under VPN work, AVD under host VPN does not. Disposition: documented as a known testing-environment limitation. Operator workflow updated to disable VPN before AVD smoke. No product code change. Real-user impact bounded to "developers running AVD with host VPN".
2. **Real-device-affected verdict (H-VPN-REAL-DEVICE-AFFECTED confirmed).** Physical Android devices under VPN also cannot deliver. Disposition: this becomes a high-priority product issue; a separate scope-lock track opens to design the fix. This recon's job ends with the verdict; the fix is a separate scope.
3. **Path-specific verdict (H-VPN-DWSS-ONLY confirmed AND H-VPN-REST-ALSO refuted).** VPN degrades Direct WSS but REST fallback continues to deliver. Disposition: documented as a degraded-mode but functional posture; the existing transport architecture covers the case. Direct WSS hardening for VPN can be a future enhancement but is not a delivery blocker.
4. **REST-also verdict (H-VPN-REST-ALSO confirmed).** VPN breaks REST fallback too. Disposition: highest-priority delivery blocker for VPN users; scope-lock opens immediately for a fix.

Two-or-more concurrent verdicts that contradict each other close the recon as **inconclusive — escalate to Council**.

## §7 Park conditions

The recon parks (suspends work ≥ 7 days, releases the master lock for any other track) under any of:

- **P-1 — Symptom non-reproducible.** Three consecutive J-1 + J-2 cycles under the previously-failing VPN configuration cannot reproduce the failure pattern. The 2026-06-26 evidence stays on file as a single-shot environment artefact.
- **P-2 — VPN-vendor scope creep.** If the failure surface turns out to be vendor-specific (e.g. one VPN provider but not others), and producing a fix would require accommodating per-vendor behaviour, the recon parks and the disposition is documented as "VPN-vendor-specific; user-facing documentation only". This avoids the recon turning into an open-ended compatibility chase.
- **P-3 — Operator unavailable for further attempts.** A pragmatic park — if the operator does not have the bandwidth for instruments J-2 / J-3 / J-4 on a physical-device or different-VPN configuration, the recon parks with the current evidence captured and a hand-off note for whoever picks it up.

## §8 Out-of-scope explicitly NOT promoted to invariants

The following candidates remain explicitly OUT of this recon's invariant set:

- Direct WSS Mode 2 root cause (separate parked track).
- REST-send-connectivity-recon1 (parked; see `rest-send-connectivity-recon1.md` §10).
- ACK deadline / inbound stall / quiescence work.
- Reality / Tor / TURN paths.
- Voice / media paths.
- Any pre-locked fix shape.

## §9 Hand-off note

If this session ends before J-1 runs: the next session reads this file's §5 and runs J-1 (host VPN ON/OFF reachability matrix). The output of J-1 is a docs-only progress comment / PR amendment in this track; do NOT propose a fix shape in the J-1 progress note. Each subsequent instrument is justified by the previous one's evidence — the recon does not run J-2 / J-3 / J-4 ahead of need.

If this session ends after J-1 runs: the next session reads the J-1 progress note + the §5 ordering rule and decides whether J-2 or J-3 is the next instrument based on whether J-1 isolated the host or the emu as the locus of failure.
