# Track: REST-SEND-CONNECTIVITY-RECON1

**Type:** Facts-first reconnaissance / diagnostic. NOT a code-fix track.
**Status:** Open (mini-lock).
**Started:** 2026-06-26 after the RC-PREKEY-PUBLISH-DEBOUNCE-RACE PR #333 baseline-replay smoke surfaced a new transport-layer blocker that is independent of the prekey path PR #333 closed.
**Last known master at open:** PR #333 squash `5a5ce15b`.

---

## §1 Goal

Discriminate the root cause of the 2026-06-26 Wi-Fi smoke's outbound REST send blocker observed on the emulator peer. The smoke ran on the post-PR-#333 APK (SHA-256 `3d3317bd184c464337a6dd6e59bc2571eccbe659953b5e458a6802456decfb54`) and reproduced the exact RC-PREKEY-PUBLISH-DEBOUNCE-RACE chain on the prekey side as expected. The user-visible baseline failed because the emulator's outbound REST send to `relay.phntm.pro/65.108.154.152:443` died with 5 × `event=connectFailed exception=SocketTimeoutException` (each ~5006 ms) before `REST_TRACE send_fail_giving_up total_elapsedMs=53110 attempts=5` and `SEND_TRACE relay_send_return ok=false` on message `ac074425-0b5e-4721-9871-a305e9360a42`.

This track produces an **evidence-graded discrimination matrix**. No code change, no fix scope-lock, no PR #330 work until the matrix is filled and the dominant cause is identified.

## §2 Scope

- Bound the failure: device-side vs network-side vs relay-side.
- Bound the failure shape: TCP connect timeout vs DNS vs TLS vs HTTP-layer.
- Bound the failure window: transient burst vs sustained loss vs pattern correlated with concurrent operations.
- Map the user-visible consequence: does the failed message stay retryable; does the bubble surface a Failed state; what does the next user-action recovery look like.
- Map the existing client-side retry contract: the OkHttp connect timeout (5 s observed), the per-message attempts budget (5 observed), the inter-attempt delays (observed ~1.18 / 2.76 / 9.20 / 14.92 s — exponential), and the total send-budget (53 s for the give-up path).

## §3 Out of scope

- **Any code change.** This track does not write code. If a fix is identified, a separate scope-lock follows AFTER this recon closes.
- **RC PR #330.** Stays Draft / HOLD throughout. No edits, no rebase, no force-push, no merge attempts.
- **Direct WSS stability** in general. WS connectivity behaviour appears in this recon only as a corroborating signal that the failure shape is path-class (e.g. same destination IP also produced `ws_auth connectFailed` and `op=poll connectFailed` in the same window on the same device), not as a track to fix.
- **The prekey publish path that PR #333 closed.** This recon does not re-open that surface.
- **Architectural changes** to message-delivery semantics (durable retry queue, ack deadline, optimistic placeholder, etc.). If recon shows the existing retry budget is wrong, the FIX track will scope that — not this one.
- **Predictive remediation.** No "obvious" cause is locked-in ahead of facts. Specifically, **emulator-NAT-side blame is NOT pre-locked** — the recon explicitly looks for control evidence that physical devices on the same WAN at the same wall-clock window saw the same destination-IP loss.

## §4 Hypotheses to discriminate

Each hypothesis is a candidate root cause; only ONE is expected to survive the discrimination matrix. The recon's job is to gather evidence for each, not to prove a single one in advance.

- **H-A — Carrier / WAN side total loss.** The emulator's WAN path to `65.108.154.152:443` lost packets for the duration of the symptom window. Both directions, all TCP/UDP, multiple destination ports if needed. The emulator-specific NAT happens to be on this path but is not the contributor; a physical device on the same WAN at the same minute would have failed similarly.
- **H-B — Emulator NAT / virtual-network artifact.** The QEMU user-mode NAT (`10.0.2.16` → host network) at the operator workstation introduced packet loss, port-reservation churn, or routing instability specific to the emulator, while the physical Tecno on the same WAN at the same window stayed reachable. NOT carrier-side, NOT relay-side.
- **H-C — Relay accept-loop / kernel backlog contention.** `relay.phntm.pro` was reachable on TCP from some sources but rejecting / dropping new connections from the emulator's source-port range during the window. Manifests as `connectFailed` on the emulator and `200 OK` from a synthetic control client at the same wall-clock minute.
- **H-D — Client-side OkHttp connection-pool / route eviction.** The emulator's OkHttp client poisoned its connection state (route eviction, certificate pinning, HappyEyeballs misfire, IPv6 fallback) and is unable to establish a *new* TCP connection even when the underlying network is healthy. A control HTTPS client on the same emulator at the same wall-clock minute against the same destination succeeds.
- **H-E — Cross-failure interaction.** The Direct WSS path and the REST send path share an OkHttp client or a transport-singleton in a way that propagates a degraded state (e.g. Direct WSS `connectFailed` poisons a shared dispatcher / route DB), and the REST send symptom is a downstream artefact of WS-layer state, not an independent network event.
- **H-F — Sender-side retry-budget under-spec.** The connectivity blip was transient; the 5-attempt × 53 s total budget gave up too early for the actual recovery window. A longer / smarter retry curve would have succeeded without any other change. (This is a contract-level hypothesis, not a network-layer one.)

## §5 Diagnostic instruments

The recon does NOT yet commit to which of these run, or in what order. Each instrument's value is conditional on the symptom reappearing (or being made to reappear in a controlled way) and on the previous instrument's verdict. The PR for each instrument lands its own evidence summary before the next one runs.

- **I-1 — Log corpus from the 2026-06-26 smoke** (`C:\temp\smoke-pr333-baseline\tecno.log` + `emu.log`). Already on disk; the recon's first deliverable is a precise minute-by-minute timeline of `connectFailed` events on both devices against `65.108.154.152` and a side-by-side mapping to user-visible UI states.
- **I-2 — Synthetic control HTTPS probe on the host workstation.** A simple `curl --resolve relay.phntm.pro:443:65.108.154.152 https://relay.phntm.pro/` (or equivalent) timestamped to coincide with a symptom reproduction window. Discriminates H-A vs H-B vs H-C: if the host probe succeeds while the emulator fails at the same wall-clock minute, H-A is refuted.
- **I-3 — pcap on the host workstation's WAN-side interface** during a controlled reproduction. Captures any out-of-emulator TCP SYNs from the host's IP toward `65.108.154.152:443`, their RST/timeout outcomes, and whether the WAN actually saw the emulator's SYNs leave at all. Discriminates the H-A / H-B / H-C boundary on packet evidence rather than log inference.
- **I-4 — Concurrent physical-device control.** Tecno on the same WAN running a passive REST status probe loop (e.g. `/health` or `/prekeys/status` at low frequency) during a symptom reproduction window on the emulator. If both fail at the same wall-clock minute → H-A confirmed; if only the emulator fails → H-A refuted.
- **I-5 — Repeat-on-LTE smoke.** The same smoke template but on Tele2 LTE (Tecno cellular as the only path), recording whether the same `connectFailed` pattern reproduces on a completely different network egress. Tightens the H-A bound: if Tele2 LTE smoke is clean, the 2026-06-26 symptom was Wi-Fi-WAN-specific, not destination-specific.
- **I-6 — Relay-side accept log read** during a symptom reproduction window. If the relay shows any inbound connection attempts from the emulator's egress IP during the window, H-C is refuted (TCP did reach the relay; the relay accepted or rejected it explicitly, not silently). If no inbound attempts at all, the failure is below the relay.
- **I-7 — Source-code read of the OkHttp client and dispatcher wiring** used by REST send. NOT a code change. The read maps which client(s) and pool(s) the REST send path actually uses, whether they are shared with Direct WSS, and what state survives a `connectFailed`. Discriminates H-D / H-E.

The recon may add a new instrument if a previous instrument's evidence demands one; it does not run instruments that the evidence does not require. Each new-instrument decision is a track-doc amendment, not a free choice in the moment.

## §6 Acceptance gates

The recon CLOSES (handing off either to a fix scope-lock or to a Park decision) when the matrix is filled enough to support EXACTLY ONE of the following six verdicts:

1. **H-A confirmed (WAN side).** I-2 + I-4 (or I-2 + I-5) show the synthetic host probe and the physical-device probe both failed at the same wall-clock minute as the emulator. → No client-side fix scope. Likely follow-up: operator-side runbook for the symptom window + a smarter sender-side retry contract (H-F as a layered secondary).
2. **H-B confirmed (emulator NAT artifact).** I-2 succeeds while the emulator fails at the same minute. I-4 succeeds. I-3 shows no emulator SYNs leaving the host's WAN interface. → No production code change for this symptom; runbook update; future smokes should de-prioritise emulator-on-Wi-Fi as a binding baseline gate.
3. **H-C confirmed (relay accept / backlog).** I-2 + I-4 succeed only from non-emulator clients. I-6 shows emulator connection attempts arriving and being dropped / RST by the relay. → Server-side fix scope-lock.
4. **H-D confirmed (client connection-pool / route eviction).** I-7 + a controlled OkHttp clear-pool probe shows the emulator can establish a fresh connection after a forced-eviction step. → Client-side fix scope-lock at the OkHttp client / dispatcher layer.
5. **H-E confirmed (cross-failure interaction).** I-7 shows REST send and Direct WSS share state in a way that propagates a degraded outcome. → Client-side fix scope-lock at the transport-singleton layer.
6. **H-F confirmed (retry-budget under-spec).** Evidence shows the underlying network was healthy within the give-up window but the client gave up before it recovered. → Contract-level fix scope-lock on `send_fail_giving_up` budget.

Two-or-more concurrent verdicts close the recon as **inconclusive — escalate to Council** per WORKING_RULES.

## §7 Park conditions

The recon Parks (suspends work for ≥7 days, releases the master lock for any other track) under any of:

- **P-1 — Symptom non-reproducible.** Three consecutive Wi-Fi smoke runs on the same APK + same operator workstation + same WAN cannot reproduce the failure pattern within 60 minutes of total run time each. The 2026-06-26 evidence remains on file but is reclassified as a single-shot event without a contemporaneous client-side cause.
- **P-2 — Two architecturally-incompatible verdicts.** If the recon hits two acceptance-gate verdicts that contradict each other AND a third instrument cannot break the tie, the track parks and escalates to Council.
- **P-3 — Network instability of operator-workstation provenance.** If I-2 + I-3 show the operator-workstation's general Internet path was degraded during the 2026-06-26 window for reasons unrelated to PHANTOM (ISP outage, household WAN flap, etc.), the recon parks with a note: "no PHANTOM-specific cause demonstrated; runbook flags the smoke as INVALID under WAN-side instability".

## §8 Out-of-scope explicitly NOT promoted to invariants

The following candidates have surfaced in prior reviews / smokes and remain explicitly OUT of this recon's invariant set:

- Direct WSS Mode 2 root cause (separate parked track).
- The DWS-UX execution plan tracks (separate, parked behind production observation per the existing pointer).
- ACK deadline / inbound stall / quiescence work (separate scope; not gated by this recon).
- Reality / Tor / TURN paths.
- Voice / media paths (PR #325 already confirmed transport-independent).

## §9 Hand-off note

If this session ends before any instrument runs, the next session picks up with: read `C:\temp\smoke-pr333-baseline\*.log`, fill in the I-1 minute-by-minute timeline as the first deliverable, and post the timeline + a recommended I-2 / I-4 sequence to a recon-progress PR comment. Do NOT propose a fix shape in that comment.

## §10 Verdict — PARKED 2026-06-27

The recon parks WITHOUT a formal closure on any of the six hypotheses from §4. The target signature (`emu connectFailed` to `65.108.154.152:443` from `10.0.2.16`) did not reproduce in either clean attempt; the only attempt that did encounter a failure-shape was invalidated by environment contamination unrelated to the original 2026-06-26 evidence.

### Attempt summary

| Attempt | Date | Window | VPN | Result | Notes |
|---|---|---|---|---|---|
| #1 | 2026-06-26 | ~8 min | Mac host ON | **INVALIDATED** | Host VPN ↔ AVD QEMU NAT/DNS proxy interaction prevented the emu from reaching the relay at the DNS layer (`UnknownHostException`). The target TCP-layer `connectFailed` signature could not even be tested. Spawned the follow-up VPN compatibility recon — see `vpn-transport-compat-recon1.md`. |
| #2 | 2026-06-26 | ~29 min | OFF | **NOT TARGET REPRO** | No emu / Tecno `connectFailed` to `65.108.154.152:443`. Host probe 1234/1236 SUCCESS, 2 isolated `ec=28`. All Phantom messages delivered (`relay_send_return ok=true`). Direct WSS Mode 2 ping-timeout pattern visible on both devices as a separate signal. |
| #3 | 2026-06-27 | ~37 min | OFF | **NOT TARGET REPRO** | No emu / Tecno `connectFailed` to `65.108.154.152:443`. Host probe 1597/1606 SUCCESS, 9 `ec=28`. Includes a 6-fail host-probe burst cluster at `21:53:10 → 21:54:03Z` (53 s span) without a co-incident emu / Tecno transport event in the same window — devices were idle, so the host blip cluster could not be cross-correlated against any Phantom-side attempt. UX-visible `prekey_fetch_result=timeout` once (8002 ms, 2 ms over 8000 ms budget) followed by a 4 s retry succeeding (`http_bundle_fetch_done status=200` → `relay_send_return ok=true`); message delivered, not lost. |

### Disposition

- Parked per §7 P-1 ("symptom non-reproducible") — strictly speaking the P-1 trigger asks for three CLEAN reproduction attempts; two were achieved before parking. The operator decision is to park now rather than spend further session time on a target that is not reproducing, on the explicit understanding that the original 2026-06-26 evidence stays on file as a single-shot event and that any future regression on the same shape re-opens this recon, NOT the prekey-debounce track or any other track.
- The I-1 hypothesis matrix (`rest-send-connectivity-recon1-i1-timeline.md` §5) stays valid as the last successful discrimination: H-A REFUTED, H-B strongly supported but not final-confirmed.
- No code change. No fix scope-lock. No RC PR #330 movement. RC #330 stays Draft / HOLD pending the Direct WSS / Mode 2 track, NOT pending this recon.

### Side-findings preserved for future work

- **2026-06-27 host-side probe burst cluster (`21:53:10 → 21:54:03Z`).** Nine `ec=28` host-probe failures, six clustered in 53 seconds, while Phantom devices were idle. If a future I-2 reproduction catches a co-incident emu burst plus host-probe burst, the standing hypothesis matrix from I-1 (H-A REFUTED, H-B strong) would need to be re-examined — that combined evidence could reopen H-A or H-C in a way the original 2026-06-26 corpus did not allow. The host-probe TSV from attempt #3 is preserved at `C:\temp\smoke-pr333-baseline-i2-v2\host-probe.tsv` on the operator workstation.
- **Direct WSS Mode 2 / ping-timeout pattern.** Both emu and Tecno logs across attempts #2 and #3 show repeated `WebSocket connect FAILED ... sent ping but didn't receive pong within 15000ms` shapes (~10 hits per side per attempt). Delivery did not break — REST fallback caught the traffic. This is the same Mode 2 family the Direct WSS track owns; it is NOT a finding under this recon's scope.
- **First-message UX delay (8 s prekey-fetch timeout + 4 s retry).** Attempt #3 surfaced the same "yellow dot" UX shape previously preserved as a side-finding in `[[project_voice_smoke_pass_2026_06_17]]`-class observations (`prekey_fetch_result=timeout` on the budget boundary, followed by a successful retry). The transport works correctly; the surface is a UX delay rather than a delivery failure. Belongs to a future DWS-UX-class follow-up, NOT this recon.

### Next track pointer

The follow-up VPN compatibility recon is opened in parallel with this parking — see `docs/tracks/vpn-transport-compat-recon1.md`. The Direct WSS / Mode 2 stabilisation track remains the principal forward direction for transport reliability work, independent of either recon.
