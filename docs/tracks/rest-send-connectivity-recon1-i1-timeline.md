# REST-SEND-CONNECTIVITY-RECON1 — I-1 deliverable

**Instrument:** I-1 — Log corpus from the 2026-06-26 smoke (`C:\temp\smoke-pr333-baseline\tecno.log` + `emu.log`).
**Date:** 2026-06-26 (analysis date; smoke evidence dates are device-local timestamps preserved verbatim from the logs).
**Parent track:** `docs/tracks/rest-send-connectivity-recon1.md`.
**Type:** Facts-first reconnaissance deliverable. NO code change. NO fix scope-lock.

---

## §1 Summary verdict

The 2026-06-26 baseline-replay smoke logs alone discriminate the first hypothesis pair from the parent mini-lock §4:

- **H-A (carrier / WAN-side total loss) — REFUTED.** A physical device (Tecno) on the same Wi-Fi accessed the same destination IP and port (`65.108.154.152:443`) successfully throughout the exact wall-clock window in which the emulator's outbound connect attempts to that same destination failed. Carrier-side loss would have hit both devices.
- **H-B (emulator NAT / virtual-network artifact) — STRONGLY SUPPORTED BY I-1, NOT FINAL-CONFIRMED UNTIL I-2 / HOST SYNTHETIC PROBE OR A REPEAT DISCRIMINATOR.** Only the emulator's NAT-translated source (`10.0.2.16`) saw `connectFailed`; every observed failure carries that source IP and a distinct outbound source port; no failures appear from the physical-device source on the same network at the same minute. The shape is consistent with QEMU user-mode NAT churn, but the corpus alone cannot conclusively distinguish "NAT-specific egress fault" from "relay-side reject targeting that egress range". Closure requires I-2 (synthetic host-side probe) or another concurrent repeat.
- **H-C, H-D, H-E — weakened but not killed.** The corpus rules out the simpler shapes of each (relay edge would have hit Tecno too; pool eviction would not explain 5 distinct outbound source ports failing identically; cross-failure coupling does not explain WS-auth and REST-send sharing the same `10.0.2.16` origin signature). Each remains a residual candidate pending I-2 / I-3 evidence.
- **H-F (sender-side retry-budget under-spec) — OPEN.** Not discriminated by I-1. The `send_fail_giving_up total_elapsedMs=53110 attempts=5` shape is observed but the corpus does not show when the underlying egress recovered, so it cannot say whether a longer budget would have succeeded.

## §2 Clock anchor and offset

Device-local clocks differ. The anchor is the QR-driven reciprocal bundle fetch that both devices perform at first contact (each peer fetches the other's prekey bundle within a few seconds of being added as a contact). Selecting the two `http_bundle_fetch_start` entries on each side:

- Emu local: `06-25 18:44:03.139 PREKEY_TRACE http_bundle_fetch_start identity=bd20236147b015cb…` (Tecno's identity)
- Tecno local: `06-26 05:02:28.488 PREKEY_TRACE http_bundle_fetch_start identity=83aca7fa1daa69a8…` (emu's identity)

Treating these as wall-clock co-incident gives Tecno → Emu offset `≈ +13:41:35`. This is an approximation derived from a single anchor pair — not a wall-clock-synchronised reference. All cross-device times in §3 below carry that approximation.

## §3 Cross-correlation — emu connectFailed burst vs Tecno same-minute reachability

Source: `REST_TRACE phase_event op=send|poll|ws_auth` lines with `event=connectFailed` or `event=connectStart|secureConnectEnd|connectEnd` against `65.108.154.152:443`. Both devices target the same destination IP.

| Wall-clock anchor (emu local) | Tecno wall-clock (≈ +13:41:35) | Emu source `10.0.2.16` → `65.108.154.152:443` | Tecno (physical Wi-Fi) → `65.108.154.152:443` |
|---|---|---|---|
| `18:46:25` | `05:05:00` | `op=poll callFailed InterruptedIOException totalMs=35002` (long-poll cancelled by orchestrator) | Idle in `op=poll` long-poll cycle |
| `18:46:44` | `05:05:09` | `op=poll connectFailed SocketTimeout` after 5005 ms, src port 56804 | (mid long-poll, no new connect at this exact second) |
| `18:46:50` | `05:05:15` | `op=poll connectFailed SocketTimeout` after 5006 ms, src port 54688 | (mid long-poll) |
| `18:46:52` | `05:05:17` | `op=send key=ac074425…` `connectFailed SocketTimeout` after 5006 ms, src port 54702 (attempt 1/5) | (mid long-poll) |
| `18:46:56` | `05:05:21` | (between retries) | `op=poll connectStart` → `secureConnectEnd handshake=TLSv1.3 cipher=TLS_AES_128_GCM_SHA256` → `connectEnd elapsedMs=366`, fresh TCP+TLS succeeds |
| `18:46:58` | `05:05:23` | `op=send connectFailed SocketTimeout` after 5001 ms, src port 49788 (attempt 2/5) | Continuing successful long-poll on the connection established at 05:05:21 |
| `18:47:06` | `05:05:31` | `op=send connectFailed SocketTimeout` after 5003 ms, src port 49820 (attempt 3/5) — concurrent `op=ws_auth connectFailed` at the same instant | `op=poll responseHeadersEnd status=200` (the connection from 05:05:21 returns a chunked-flush long-poll body) |
| `18:47:20` | `05:05:45` | `op=send connectFailed SocketTimeout` after 5005 ms, src port 44184 (attempt 4/5) | `op=poll responseBodyEnd byteCount=4608 callEnd totalMs=31544` |
| `18:47:40` | `05:06:05` | `op=send connectFailed SocketTimeout` after 5006 ms, src port 47550 (attempt 5/5) → `send_fail_giving_up id=ac074425 total_elapsedMs=53110 attempts=5 reason=SocketTimeoutException` → `SEND_TRACE relay_send_return id=ac074425 ok=false` | Continuing healthy long-poll cycles (`connectEnd elapsedMs=300-450 ms`) |

**Zero `connectFailed` events appear in the Tecno log at any timestamp during the entire smoke window.** A search of `tecno.log` for `connectFailed` and `callFailed.*SocketTimeout` returns no matches.

## §4 Additional facts from the emu log

The corpus also pins these supporting facts:

- **Emu had successful REST send earlier in the same session.** `18:45:52` send `1d071e74` returned `send_response status=201 elapsedMs=664`. The connect-failure burst is therefore an in-session degradation, not a baseline incapacity.
- **The connect-failure burst window is bounded.** First `op=poll connectFailed` at `18:46:25`; last `op=send connectFailed` at `18:47:40` — approximately 75 seconds. Whether emu connectivity recovered before the orchestrator gave up on `ac074425` is not visible in the corpus; the give-up at `18:47:40` is a 5-attempt budget exhaustion, not a confirmed steady-state failure.
- **All 5 `ac074425` send attempts use distinct outbound source ports.** Captured from each `connectFailed message=...from /10.0.2.16 (port N)`: `54702`, `49788`, `49820`, `44184`, `47550`. Each retry opens a fresh socket. Failure is not connection-pool poisoning — fresh sockets fail identically.
- **Parallel `op=poll` and `op=ws_auth` attempts in the same window share the failure.** `op=poll connectFailed` at `18:46:44 / 18:46:50 / 18:46:55 / 18:47:00`; `op=ws_auth connectFailed` at `18:47:06` (`a2a0bac`). All from the same `10.0.2.16` source to the same destination. Cross-feature failure is not WS↔REST code-path coupling — it is the emu's egress losing reachability for ~75 seconds.
- **DNS is not the failure surface.** Every `dnsEnd` line for the destination resolves to `addresses=[65.108.154.152]` with `elapsedMs=0-2`. The IP is stable across the smoke; the failure is on the post-DNS TCP connect.

## §5 Hypothesis matrix update

| Hypothesis (from parent mini-lock §4) | Status after I-1 | Evidence |
|---|---|---|
| **H-A** carrier / WAN-side total loss | **REFUTED** | Tecno on the same Wi-Fi at the same wall-clock minute as the emu connect-failure burst completed `connectEnd elapsedMs=366` to `65.108.154.152:443` and ran healthy long-poll cycles. Carrier-side loss would have hit both devices. |
| **H-B** emulator NAT / virtual-network artifact | **STRONGLY SUPPORTED BY I-1, NOT FINAL-CONFIRMED UNTIL I-2 / HOST SYNTHETIC PROBE OR REPEAT DISCRIMINATOR** | Only `10.0.2.16` source saw `connectFailed`; physical-device source on the same network at the same minute saw none. Burst shape (~75 s, all subsystems from the same source IP) is consistent with QEMU user-mode NAT churn. Final confirmation requires a controlled host-side synthetic probe (I-2) or a deliberate repeat. |
| **H-C** relay accept-loop / kernel backlog targeted at emu egress | **WEAKENED** | The simpler "relay refuses all clients" shape is killed by Tecno success. A relay-side filter that targets the emu's egress IP range specifically is not ruled out by the corpus alone; in practice this collapses into the H-B observation if I-2 cannot distinguish them. |
| **H-D** OkHttp connection-pool / route eviction | **WEAKENED** | Each retry uses a distinct outbound source port. Fresh socket creation fails identically across 5 attempts. Pool poisoning does not explain fresh-socket failure. |
| **H-E** cross-failure interaction WS ↔ REST | **WEAKENED** | The `op=ws_auth` failure at `18:47:06` and the `op=send` / `op=poll` failures share the same `10.0.2.16` source and same destination. Cross-feature failure is best explained by a single shared egress-layer failure (consistent with H-B), not by code-path state propagation. |
| **H-F** sender-side retry-budget under-spec | **OPEN** | The corpus shows the give-up at attempt 5 / 53.110 s elapsed but does not show when (if at all) the underlying egress recovered. Whether a longer or smarter retry curve would have succeeded is not answerable from I-1 alone. |

## §6 Acceptance gates after I-1

Re-reading parent mini-lock §6 against the I-1 evidence:

- **Gate 1 (H-A confirmed) — REFUTED.** No further work on this gate.
- **Gate 2 (H-B confirmed) — existing logs already provide strong physical-device control evidence; I-2 remains the next closure step.** The corpus has effectively served as an opportunistic I-4 (physical-device control on the same Wi-Fi at the same minute) but did so passively, without a deliberate co-incident probe. I-2 is required to lock the verdict.
- **Gates 3-6 — open.** No I-1 evidence forces any of these closed, but each one's likelihood is downgraded by the explanatory power of H-B for the observed shape.

## §7 Recommended next instruments

In priority order, with the explicit acceptance criterion for each step's outcome:

1. **I-2 (synthetic host probe) — priority 1.** During a reproduced symptom window, run a timestamped `curl --resolve relay.phntm.pro:443:65.108.154.152 https://relay.phntm.pro/<small endpoint>` from the host workstation (not the emulator). The probe pins H-B vs H-C:
    - Probe SUCCESS while emu fails at the same wall-clock minute → H-B confirmed (Gate 2). Recon closes.
    - Probe FAIL with the same signature → H-C reopens (relay-side reject targeting the egress IP range). Recon escalates to I-3 or I-6.
    - Symptom does not reproduce in three consecutive smoke windows totalling ≥ 60 minutes → P-1 park condition triggers and the recon parks per mini-lock §7.
2. **I-4 (concurrent physical-device control) — priority 2 (already partially satisfied by the existing corpus).** A deliberate repeat with Tecno running a passive low-frequency status probe loop concurrent with an emu reproduction would tighten the corpus-side evidence. Only worth running if I-2 is inconclusive.
3. **I-3 (pcap on host WAN interface) — priority 3.** Packet-level evidence of whether emu SYNs leave the host's WAN-side interface during the symptom window. Required only if I-2 cannot discriminate.
4. **I-7 (OkHttp dispatcher source-read) — DEFER.** Network-layer evidence dominates; H-D and H-E are already weakened by the source-port and cross-feature observations. Source-read becomes relevant only if H-B is refuted by I-2 / I-3 evidence.

I-5 (Tele2 LTE repeat) and I-6 (relay-side accept log read) remain in the parent mini-lock's candidate set but are not promoted to a step in this sequence; their value is conditional on I-2 / I-3 outcomes.

## §8 Out of scope

Restating the parent mini-lock §3 constraints, applied to this deliverable:

- No code change in this PR.
- No fix scope-lock. Specifically: no proposal to extend the send retry budget, no proposal to switch the emulator to bridge-mode networking, no proposal to add a `curl`-based out-of-band fallback. Each of those is a fix shape and is explicitly out of scope until a recon verdict closes Gate 2 (or another gate).
- No work on RC PR #330. That PR stays Draft / HOLD throughout.
- No update to `docs/PROJECT_LOG.md` or `docs/project/MASTER_TIMELINE_2026.md` in this PR — durable journal updates follow once Gate 2 (or another gate) is conclusively closed, so the journal records a verdict rather than an in-progress hypothesis.

## §9 Next-session pickup

If the recon picks up in a new session before I-2 runs, the entry point is: re-read this file (§3 + §5 + §7), then design the I-2 probe (exact `curl` invocation, expected timestamping discipline against operator-workstation clock, reproduction trigger conditions). Do not propose a fix shape in the I-2 design discussion.

If the recon picks up after I-2 runs, the entry point is: read this file's §5 hypothesis matrix and amend it with the I-2 evidence, then either close Gate 2 (H-B confirmed) or escalate to I-3 / I-6 per §7.
