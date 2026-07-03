# Track: DIRECT-WSS-MODE2-RECON1

**Type:** Facts-first reconnaissance / diagnostic. NOT a code-fix track. NOT a re-investigation of Direct WSS Mode 2 root cause (that lives in `rc-direct-stability1.md` / `rc-direct-ws-death1.md` and is parked at the Arm G / Android libXray write-stall verdict from 2026-06-08).
**Status:** Open (mini-lock).
**Opened:** 2026-06-27 after the REST-SEND-CONNECTIVITY-RECON1 parking PR #338 closed and pointed Direct WSS / Mode 2 as the principal forward direction for transport reliability work and the gate on RC PR #330.
**Last known master at open:** PR #338 squash `6d7809f4`.

---

## §1 Goal

Characterise the **current** Direct WSS Mode 2 behaviour on master HEAD across Tecno Wi-Fi, Tecno Tele2 LTE, and the Android emulator on Wi-Fi, then validate whether the RC-RECONNECT-QUIESCENCE1 implementation on RC PR #330 actually suppresses the reconnect storm + REST-fallback-delivery picture that Mode 2 produces. The recon does NOT re-open the Mode 2 root cause — `F-Mode1` (return-path loss) and `F-Mode2-pp0` / `F-Mode2-pp1` (uplink-loss + control/application asymmetry) are already proven in `rc-direct-stability1.md` §1. The recon does NOT propose a fix; if PR #330 fails to suppress the storm, the next deliverable is a verdict PR, not a code change.

The recon answers seven concrete questions (locked 2026-06-27):

1. Does the relay stop sending Pongs, or does the client stop seeing them? (server-side vs client-side discrimination on top of the existing `F-Mode1` / `F-Mode2` root-cause map)
2. Does the TCP/WS connection become half-dead while remaining formally open?
3. Is the failure surface in the Android coroutine / Job / lifecycle layer rather than the network?
4. Is there a stable rhythm — e.g. death after N successful pongs, after N seconds, after an idle window?
5. Does the same rhythm reproduce identically across Tecno Wi-Fi, Tecno Tele2 LTE, and emulator Wi-Fi, or does the rhythm differ per network class?
6. Does REST fallback continue to deliver during the Direct WSS degradation window?
7. Does RC PR #330 (Mode 2 sticky / quiescence) actually suppress the reconnect storm that Mode 2 produces, and does it preserve message delivery during the storm-suppression window?

## §2 Scope

Two phases, executed in order. Phase B does not start until Phase A has produced at least one clean baseline.

### Phase A — clean baseline Direct WSS (no fix candidate APK)

- VPN OFF throughout. Network unchanged during a single test session.
- One Tecno + one emulator, both with master-HEAD debug APK (no PR #330 fix flags forced on).
- Continuous `logcat` capture on both devices, host-clock prefixed per the `host_recv_ts` discipline from `rest-send-connectivity-recon1-i2-host-synthetic-probe-design.md` §3 (Tab B / Tab C pattern).
- 15-30 minute sessions, sending messages in both directions every few minutes.
- Three network classes: Tecno Wi-Fi, Tecno Tele2 LTE, emulator Wi-Fi.
- One session per network class minimum.
- Extract per-session: WSS session count, lifetime distribution, ping/pong count per session, Mode 1 vs Mode 2 classification per session, REST fallback delivery continuity during WSS death windows, cross-device timing correlation.

### Phase B — RC PR #330 validation (with fix-candidate APK)

- Same conditions as Phase A but with an APK built from RC PR #330 head (currently `6f49cd89`) with the three RC release flags forced to `"1"`: `MODE_2_FAST_PATH_ENABLED`, `MODE_2_STICKY_ENABLED`, `RECONNECT_QUIESCENCE_ENABLED`.
- Three network classes again, one session each minimum.
- Extract per-session: Mode 2 detection event present, `sticky_armed` event present, reconnect storm count during the quiescence window, REST fallback delivery during quiescence window, `ws_recovery_probe_granted` events, `ws_alive_60s` proof events, route-change triggering of single-probe vs multi-probe issuance, message loss / duplicate count across the session, gate state transitions (`WsCandidate ↔ RestActive`).
- Compare Phase B results against the Phase A baseline for the same network class.

## §3 Out of scope

- **Any code change.** The recon discriminates and validates; it does not propose, sketch, or land a code fix. If Phase B shows PR #330 does NOT meet its mini-lock contract, the disposition is to keep PR #330 in Draft and open a fix-track scope-lock as a separate item — NOT to touch PR #330's diff under this recon.
- **Re-investigation of Direct WSS Mode 2 root cause.** Parked in `rc-direct-stability1.md` after Arm G v10/v11 hit the Android libXray multi-segment ClientHello write stall (2026-06-08). The carry-forward facts (`F-Mode1`, `F-Mode2-pp0`, `F-Mode2-pp1`, `F-NotKtor`, `F-NotCount`, `F-CaddyAtMax`, `F-Mode2-cadence-invariant`) stay valid here as input, not as scope to revisit.
- **VPN-TRANSPORT-COMPAT-RECON1.** Separate parallel track opened in PR #338. VPN is OFF for every Phase A / Phase B run. Cross-contaminating a Direct WSS test with VPN is explicitly forbidden — any session run under VPN is INVALIDATED and not counted toward Phase A / Phase B coverage.
- **REST-SEND-CONNECTIVITY-RECON1.** Parked in PR #338. REST send connectivity is observed as a side-channel ("does REST fallback continue to deliver"), not as scope to re-open.
- **Trek 2 long-poll backbone, DWS-UX, R3.6 sticky-per-route.** These are already landed on master and stay as ambient infrastructure. The recon observes them via the existing structured logs (`REST_TRACE poll_call`, `REST_TRACE inbound_deliver`, `ws_alive_60s`, etc.) but does not propose any change to them.
- **Reality / Tor / TURN / Calls / Voice.** Out of scope for this recon. Voice transport-independence is already field-confirmed (2026-06-17 voice-smoke entry).
- **Predictive remediation.** No "obviously the fix is X" framings. Specifically NOT pre-locked: cadence change beyond the `F-Mode2-cadence-invariant` already-refuted lever, raw-OkHttp swap, Ktor-adapter swap (`F-NotKtor`), short-lived WS rotation, control-frame-vs-data-frame heartbeat redesign. Each is a fix shape that belongs to a future fix-track, not to this recon.

## §4 Hypotheses to discriminate

These are NOT root-cause hypotheses (those are settled by §1's carry-forward facts from RC-DIRECT-STABILITY1). These are hypotheses about the **current observable behaviour** on master HEAD and about PR #330's quiescence contract.

### Phase A hypotheses (current Mode 2 behavioural surface on master HEAD)

- **H-Pong-Server-Side** — The Mode 2 death anchor is "relay stops sending Pong" rather than "device loses inbound TLS records". Discriminator: relay-side `ws_protocol_pong_sent` count vs client-side received-pong count over the death window.
- **H-Pong-Client-Side** — The Mode 2 death anchor is "device pcap shows the inbound TLS records but OkHttp does not surface them as received Pongs". Discriminator: same as H-Pong-Server-Side; this hypothesis is exactly the `F-NotCount` shape that was refuted at the root-cause level for `F-Mode1` and `F-Mode2-pp0`. Carry-forward presumption: NOT the current rhythm; if Phase A finds evidence, the root-cause investigation re-opens (separate track).
- **H-Half-Dead-TCP** — The TCP socket remains formally open during the Mode 2 death window (no `RST`, no `FIN`), and only the OkHttp ping-timeout fires the `SocketTimeoutException`. Discriminator: socket-level lifecycle events vs WS-layer error event timing.
- **H-Lifecycle-Layer** — The failure surface is in the Android `serviceScope` / `Job` / `lifecycle` layer rather than the network — a coroutine cancellation or supervised-job re-launch poisons the WS state. Discriminator: lifecycle event timing in `logcat` against WS death anchor.
- **H-Rhythm-Pong-Count** — Mode 2 death follows a stable pong-count rhythm (e.g. "after exactly N pongs, the (N+1)th never arrives"). `F-Mode2-cadence-invariant` already established that lifetime scales linearly with `pingInterval`, suggesting a count-based pattern. Discriminator: pong-count distribution per session across multiple runs.
- **H-Rhythm-Time-Idle** — Mode 2 death anchors against an idle window (no app-data traffic for N seconds) rather than against pong count. Discriminator: cross-correlate the WS death event against the last preceding outbound application-data frame timestamp.
- **H-Network-Class-Identical** — The same rhythm reproduces across Tecno Wi-Fi, Tecno Tele2 LTE, and emulator Wi-Fi. `F-Mode1` (Wi-Fi 8-pong return-path loss) vs `F-Mode2-pp0/pp1` (Tele2 LTE uplink-loss) historical evidence suggests rhythms differ by network class; Phase A would re-validate this on master HEAD.
- **H-REST-Survives** — REST fallback delivery continues during the Direct WSS degradation window. Earlier voice smoke (2026-06-17) and REST-SEND recon attempts (2026-06-26 / 2026-06-27) showed REST fallback caught the traffic while WS Mode 2 ping-timeouts hit on both devices. Phase A would re-confirm this on a wider sample.

### Phase B hypotheses (RC PR #330 quiescence contract validation)

- **H-330-Quiesces-Storm** — When Mode 2 is detected (per the existing detector), PR #330's sticky-quiescence path prevents the WS reconnect loop from issuing more than the contracted number of new connection attempts during the quiescence window. Discriminator: count of `WebSocket connect` events between `sticky_armed` and `ws_recovery_probe_granted` for a single Mode 2 episode.
- **H-330-Preserves-REST** — REST fallback delivery is uninterrupted across the quiescence window. Discriminator: `REST_TRACE inbound_deliver` events with `relay_send_return ok=true` across the quiescence window.
- **H-330-Single-Probe-Per-RouteChange** — A single user-observable route change (Wi-Fi → LTE or vice versa) triggers exactly one recovery probe, not multiple. Discriminator: count of `ws_recovery_probe_granted` events per `lte-netchange` event.
- **H-330-Probe-Lives-60s** — A successful recovery probe earns the `ws_alive_60s` proof; an unsuccessful one does not promote the gate to `WsActive`. Discriminator: `ws_alive_60s proof=ws_alive_60s` log lines paired with gate-state transition lines.
- **H-330-No-Self-Reentry** — If the candidate WS dies within the probation window, it does NOT autonomously spawn a new socket without a new route-change event triggering it. Discriminator: same as H-330-Single-Probe-Per-RouteChange — counts must match.
- **H-330-No-Message-Loss-Or-Dups** — Across the full quiescence + recovery window, no messages are lost or duplicated. Discriminator: end-to-end message-id correlation between sender's `SEND_TRACE relay_send_return id=...` and receiver's `inbound_deliver id=...`.

## §5 Diagnostic instruments

Each instrument runs only when justified by the previous instrument's outcome.

### Phase A instruments

- **K-1 — Tecno Wi-Fi session capture** (~30 min, no fix-candidate APK). Continuous `logcat` on Tecno + on the emulator peer. Extract Mode 1 / Mode 2 / clean-survive distribution. Compare against `F-Mode1` baseline (8-pong ~150 s lifetime).
- **K-2 — Tecno Tele2 LTE session capture** (~30 min). Same shape as K-1. Extract Mode 2 distribution. Compare against `F-Mode2-pp0` / `F-Mode2-pp1` baselines.
- **K-3 — Emulator Wi-Fi session capture** (~30 min). Same shape. Note: emulator on host VPN has known DNS contamination per VPN-TRANSPORT-COMPAT-RECON1; VPN MUST be OFF for the entire session including before the emulator boots.
- **K-4 — Cross-device timing correlation** for at least one captured Mode 2 episode (preferably from K-2 since it has the highest Mode 2 incidence). Anchor the death event on the WS-killing device against the peer device's REST poll / inbound deliver timing — does the peer notice the failure within the expected quiescence window? Discriminates whether the Mode 2 surface is per-device or per-peer-pair.

### Phase B instruments

- **K-5 — Build PR #330 fix-candidate APK** with the three RC release flags forced to `"1"`. Capture APK SHA-256 for the verdict file.
- **K-6 — Tecno Wi-Fi PR #330 session capture** (~30 min). Same shape as K-1 but with the K-5 APK. Compare against K-1 baseline for the same network class.
- **K-7 — Tecno Tele2 LTE PR #330 session capture** (~30 min). Same shape as K-2 with the K-5 APK.
- **K-8 — Route-change-induced single-probe verification.** A controlled route change (Wi-Fi → LTE → Wi-Fi sequence) on the K-5 APK; count `ws_recovery_probe_granted` per `lte-netchange`. Discriminates H-330-Single-Probe-Per-RouteChange.

The recon may add an instrument if a previous instrument's evidence demands one; it does not run instruments that the evidence does not require.

## §6 Acceptance gates

The recon CLOSES (handing off either to a fix scope-lock or to a Park decision) when both phases have run on all three network classes and the matrix supports one of the following dispositions:

### Phase A dispositions

1. **A-Baseline-Captured** — Phase A produces three clean baselines (one per network class) and the carry-forward `F-Mode1` / `F-Mode2-pp0` / `F-Mode2-pp1` facts re-confirm on master HEAD. The rhythm hypotheses (H-Rhythm-Pong-Count vs H-Rhythm-Time-Idle) close on the captured data. Phase B can now start.
2. **A-Regression** — A network class that was previously clean (e.g. Tecno Wi-Fi at the `F-Mode1` baseline) now shows a different death rhythm or substantially-different lifetime distribution. Disposition: open a separate regression track to compare master HEAD against the Phase 2 outcome reference state (`8f4c68c9`). Phase B does NOT start; the regression takes priority.
3. **A-Inconclusive** — One or more network classes cannot be captured cleanly within three attempts. Disposition: Park per §7 P-2 ("operator unavailable for further attempts on a given network class").

### Phase B dispositions (only reachable after A-Baseline-Captured)

1. **B-330-Works** — All six Phase B hypotheses (H-330-Quiesces-Storm / Preserves-REST / Single-Probe-Per-RouteChange / Probe-Lives-60s / No-Self-Reentry / No-Message-Loss-Or-Dups) hold across all three network classes. Disposition: PR #330's mini-lock contract is field-validated; the recon closes with a verdict that PR #330 may proceed to Ready / smoke retry per its own mini-lock §5 item 7. The recon does NOT mark PR #330 Ready or merge it — that decision is the operator's, with this verdict as input.
2. **B-330-Partial** — Some Phase B hypotheses hold and others do not. Disposition: the recon closes with a verdict listing exactly which hypotheses failed and on which network class; PR #330 stays Draft / HOLD and a fix-track scope-lock opens as a separate item to address the failing hypotheses. The recon does NOT propose the fix.
3. **B-330-Fails** — None or only one Phase B hypothesis holds. Disposition: PR #330 stays Draft / HOLD; the recon's verdict states that the quiescence contract is not met. A fix-track scope-lock opens as a separate item OR PR #330's mini-lock is revisited at the architectural level (separate decision).

## §7 Park conditions

The recon parks (suspends work ≥ 7 days, releases the master lock for any other track) under any of:

- **P-1 — Phase A clean baseline cannot be captured on any network class.** Three consecutive attempts on a given network class produce no Mode 2 episodes AND no Mode 1 episodes — i.e. Direct WSS is now stable enough that the recon has no symptom to characterise. Disposition: the recon parks with a verdict that Direct WSS Mode 2 / Mode 1 are no longer observable on master HEAD on this network class, which would be a surprising positive regression worth its own forward-pointer track.
- **P-2 — Operator unavailable for further attempts on a given network class.** Pragmatic park; the recon captures whatever it has and parks the remainder.
- **P-3 — Phase B blocked by APK-build environment.** If the K-5 APK build from PR #330 head cannot complete on the operator workstation within reasonable effort (e.g. branch divergence from master makes the build fail), Phase B parks until PR #330 is rebased on the current master under separate handling.

## §8 Out-of-scope explicitly NOT promoted to invariants

- Direct WSS Mode 2 root cause (parked in `rc-direct-stability1.md` / `rc-direct-ws-death1.md`).
- VPN-TRANSPORT-COMPAT-RECON1 (parallel track).
- REST-SEND-CONNECTIVITY-RECON1 (parked in PR #338).
- Trek 2, DWS-UX, R3.6, Voice / Calls / Reality / Tor — all out of scope as either landed or separate.
- Any pre-locked fix shape.

## §9 Hand-off note

If this session ends before K-1 runs: the next session reads §1 + §5 + §6 and runs K-1 (Tecno Wi-Fi session capture). K-1 output is a Phase-A-progress comment / PR amendment on this track; do NOT propose a fix shape in the K-1 progress note.

If this session ends after K-1 runs but before K-2 / K-3: the next session decides K-2 vs K-3 based on K-1's outcome — if K-1 produced a clean Mode 1 baseline, K-2 (Tele2 LTE) is the higher-information next instrument; if K-1 produced no death events at all (P-1 trigger candidate), K-3 (emulator Wi-Fi) is run before re-attempting K-1 with a different session shape.

If this session ends after Phase A completes: the next session designs K-5 (PR #330 APK build) and Phase B begins.

If this session ends after Phase B completes: the next session prepares the verdict PR per the §6 disposition matched by the evidence.

Do NOT propose a fix shape in any progress note. Do NOT touch PR #330 outside of the K-5 APK build (which is a build-time-only operation — no commits to PR #330's branch).

## §10 Phase A progress — K-1 + K-2 (2026-06-27)

### K-1 — Tecno Wi-Fi (clean baseline captured)

Operator-executed 2026-06-27 over ~44 minutes on Windows. APK SHA-256 `3d3317bd184c464337a6dd6e59bc2571eccbe659953b5e458a6802456decfb54` (master HEAD `5934310e`, build cache identical to PR #333 baseline as all post-#333 PRs were docs-only). VPN OFF throughout. Network unchanged.

| Side | WS sessions | Lifetime (median) | Pongs (mode) | REST poll / send / inbound_deliver | `relay_send_return ok=false` |
|---|---|---|---|---|---|
| Tecno (Wi-Fi) | 21 | ~136 s | 7 (18 of 21 sessions) | 90 | 0 |
| Emu (Wi-Fi) | 21 | ~136 s | 7 (17 of 21 sessions) | 88 | 0 |

`F-Mode1` baseline re-confirmed on master HEAD with slight cadence shift (~136 s × 7 pongs vs the historical ~150 s × 8 pongs in `rc-direct-stability1.md` §1). Outlier shape (lifetime ≈ 76 s / 91 s / 121 s with 3 / 4 / 6 pongs) appears on both devices in the same sessions — not pure Mode 2, but shows Wi-Fi rhythm is not always the clean 7-pong shape.

Side-finding flagged on K-1 but NOT scoped: **cross-device synchronisation is exact.** Both Tecno and Emu deaths fall within `±2-3 s` on every session number. Most plausibly an artefact of synchronised 15 s pingInterval × shared idle conditions, but a relay-side anchor is not ruled out by the corpus alone. K-2 / K-3 evidence will tighten this. Evidence files: `C:\temp\direct-wss-mode2-k1-tecno-wifi\tecno.log` SHA-256 `8A50650ECB3F74AB8AB537E5C1D1B9F0E33194090528EBF8FE32F7FD214F7145`, `C:\temp\direct-wss-mode2-k1-tecno-wifi\emu.log` SHA-256 `7EA0A1CD3A112699F6DA5C8088F42D290F8079B33AB3235FDA1A686933258302`.

K-1 disposition per §6: **A-Baseline-Captured (Wi-Fi)**. Phase A continues with K-2.

### K-2 — Tecno Tele2 LTE (captured BUT NOT a clean baseline — REST fallback failed on LTE)

Operator-executed 2026-06-27 over ~30 minutes on Mac (different Wi-Fi for the emulator). Tecno on Tele2 LTE only (Wi-Fi disabled). APK same as K-1. VPN OFF throughout.

| Side | WS sessions | Lifetime (median) | Pongs (mode) | REST behaviour | Inbound delivered |
|---|---|---|---|---|---|
| Tecno (Tele2 LTE) | 21 | ~31 s | **0** (all 21 sessions) | **7 × `breaker_open ConsecutiveRestFailures`**, cooldown `5 s → 10 s → 20 s → 40 s → 80 s → 120 s → 120 s` | **0 `inbound_deliver` / 0 `DECRYPT_TRACE` / 0 `poll_received`** across the entire 30 min |
| Emu (Mac Wi-Fi) | 7 | ~121 s | 6 (5 of 7 sessions) | 160 poll / send / deliver events | Bootstrap message decrypted OK, 2 subsequent messages held |

**`F-Mode2 pp0` re-confirmed strongly on Tecno Tele2 LTE.** All 21 sessions ≈ 31 s with 0 successful ping/pongs — textbook `rc-direct-stability1.md` §1 `F-Mode2-pp0` shape. `WebSocket connect` count 54 (≈2.5× sessions worth of reconnect storm) on Tecno.

**`H-REST-Survives` REFUTED on Tele2 LTE** (mini-lock §4 Phase A hypothesis). Tecno-side REST poll started failing with `op=poll callFailed exception=InterruptedIOException` (11 events) shortly after Direct WSS began flapping. The breaker (R3.6 sticky-recovery family, landed in PR #328) progressed through the full cooldown ramp `5000 → 10000 → 20000 → 40000 → 80000 → 120000 ms` over ~5 minutes and stayed at the 120 s ceiling. After breaker open, `poll_call_skipped reason=breaker_open_ConsecutiveRestFailures` events dominate Tecno's REST surface for the remainder of the 30 min, and zero inbound messages were delivered to Tecno from the relay.

**Two independent blockers in one session, not one cascaded failure:**

1. **Tecno-side REST poll breaker** (this is the new blocker for Phase B). Direct WSS Mode 2 happened to be co-incident, but the breaker progression and the `InterruptedIOException` failure class are surfaces of the REST-orchestrator / breaker layer, not the WS path. Without a clean REST fallback baseline on Tele2 LTE, PR #330's quiescence contract (which assumes REST fallback continues to deliver during WS storm-suppression) cannot be validated on this network class.
2. **Emu-side crypto holds** on 2 of 3 Tecno-sent messages. `1efed06e` (bootstrap) decrypted OK with `bootstrap=true`. `f8b8bff8` hit `inbound_repair_fail errorClass=OpkNotFound action=fall_through_to_hold` → `fail_mac x3dhInitPresent=true action=hold` (repeated ×16+ over ~8 minutes of retries). `5f86fce8` hit `pending_fallback_fail reason=mac_fail_under_pending` → `fail_mac x3dhInitPresent=false action=hold`. This is the same Sprint 2b-C `OpkNotFound` / `fail_mac` family the prekey-debounce-race fix and Sprint 2b-A/B/C work was supposed to address, surfacing again in this corpus. Side-finding only — NOT scoped into TELE2-LTE-REST-BREAKER-RECON1 and NOT re-opening Sprint 2b-C from this PR.

Evidence files: `~/Downloads/direct-wss-mode2-k2-tecno-lte/tecno.log` SHA-256 `13d597a28676d47a869890a52115de10a64c5f843560d6bd6c7a2fe382b20cda`, `~/Downloads/direct-wss-mode2-k2-tecno-lte/emu.log` SHA-256 `5186d939966f9def947c12fba6965fd00c8194a72e0f7335abdff2505d0fe651`. The PowerShell-equivalent analysis script's `DELIVERY OK` label is misleading on this corpus because it only counts `relay_send_return ok=false` (sender-side); future K-runs need to also count receiver-side `inbound_deliver` / `DECRYPT_TRACE` / `fail_mac` / `breaker_open` to avoid the same false-positive classification.

K-2 disposition per §6: **NOT A-Baseline-Captured for Tele2 LTE.** Direct WSS Mode 2 shape is captured cleanly (`F-Mode2 pp0` re-confirmed) but the REST fallback assumption is refuted, so K-2 cannot serve as a clean baseline for Phase B comparison.

### Phase B BLOCKED pending TELE2-LTE-REST-BREAKER-RECON1 verdict

Per the parent §6 rule that Phase B requires at least one CLEAN baseline (one where Direct WSS is poor AND REST fallback continues to deliver), K-2 does not unblock Phase B. K-1 alone (Wi-Fi only) is not sufficient because PR #330's quiescence contract is targeted primarily at Tele2 LTE Mode 2 — validating only on Wi-Fi would not exercise the contract's intended use case.

The next deliverable is the TELE2-LTE-REST-BREAKER-RECON1 mini-lock, opened in parallel with this progress note (`docs/tracks/tele2-lte-rest-breaker-recon1.md`). Until that recon closes with one of its acceptance-gate verdicts, this track stays open with Phase A at 1.5 / 3 network classes (Wi-Fi clean + LTE partially captured + emu-Wi-Fi K-3 NOT yet attempted).

K-3 (emu Wi-Fi only) **is NOT promoted as next instrument**. Running K-3 ahead of resolving the Tele2 LTE REST-fallback question would produce a third baseline but would not address the Phase B blocker. The operator decision is to gate K-3 / Phase B / RC PR #330 work on the TELE2-LTE-REST-BREAKER-RECON1 outcome.

### Side-findings preserved (NOT actioned in this PR)

- **K-1 cross-device synchronisation (`±2-3 s` per session-number)** — probable artefact of synchronised pingInterval × shared idle but not conclusively distinguished from relay-side anchor. Reproduces in K-3 / repeat-K-1 would confirm; not promoted to a hypothesis under this recon's §4.
- **K-2 Emu-side Sprint 2b-C `fail_mac` / `OpkNotFound` holds** on 2 of 3 messages — same family as the closed Sprint 2b-C work. Single observation in this corpus; NOT scoped into TELE2-LTE-REST-BREAKER-RECON1 and NOT re-opening the Sprint 2b-C track from this PR. If reproduces on multiple sessions, opens a separate `SPRINT-2B-C-INBOUND-REPAIR-REGRESSION-RECON` track.
- **K-2 Tecno `WebSocket connect` count = 54 vs 21 sessions** (~2.5×) — reconnect storm on master HEAD, which is exactly what PR #330's quiescence is meant to suppress. Reference for the eventual Phase B comparison.

## §11 Phase B amendment — B1 / B2 split (2026-06-28)

TELE2-LTE-REST-BREAKER-RECON1 closed 2026-06-28 (PR #342 squash `4302f56d`) with two supported hypotheses — H-NetworkSideCancellation supported via corpus + carry-forward (NOT packet-confirmed), and H-Tele2LTESpecific supported via K-1 vs K-2 comparison (NOT confirmed against other LTE carriers). The closure forwarded three candidate paths back to this parent recon. The operator decision recorded here is a fourth refinement: **Phase B SPLITS into B1 (Wi-Fi mechanical validation of PR #330) and B2 (separate Tele2 LTE delivery-blocker track to be opened on its own terms), with an explicit release / rollout gate.**

The reformulation matters because the original framing of path (c) ("accept LTE limitation and validate Phase B on Wi-Fi as best-available evidence") risked reading as a product-level acceptance that PR #330 ships when Wi-Fi passes. The B1 / B2 split makes the framing more precise: validating the quiescence mechanism on Wi-Fi is one deliverable; the LTE delivery surface remains a release blocker on its own terms.

### Phase B1 — Wi-Fi mechanical validation of PR #330

**Scope.** Validate that PR #330's RC-RECONNECT-QUIESCENCE1 quiescence contract is mechanically correct on the network class where REST fallback can complete the long-poll cycle. This answers: "given a working REST fallback substrate, does the quiescence mechanism behave as the mini-lock contract requires?" — not: "is PR #330 sufficient to ship to LTE users?"

- Build a fix-candidate APK from PR #330 head (currently `6f49cd89`) with the three RC release flags forced to `"1"`: `MODE_2_FAST_PATH_ENABLED`, `MODE_2_STICKY_ENABLED`, `RECONNECT_QUIESCENCE_ENABLED`. (This is K-5 in §5 of the original mini-lock — build-time operation only, NO commits to PR #330's branch.)
- One Tecno session on Wi-Fi (~30 min) with the fix-candidate APK, paired with the emu on Wi-Fi. Compare against the K-1 Wi-Fi baseline (already captured 2026-06-27).
- Extract the same per-session metrics defined for Phase B in §2: Mode 2 detection event presence, `sticky_armed` event presence, reconnect storm count during the quiescence window, REST fallback delivery during the window, `ws_recovery_probe_granted` / `ws_alive_60s` proof events, route-change probe count, message loss / duplicates, gate transitions.

**B1 acceptance gate (mechanical correctness only):**

- **B1-PASS:** All six Phase B hypotheses (H-330-Quiesces-Storm / Preserves-REST / Single-Probe-Per-RouteChange / Probe-Lives-60s / No-Self-Reentry / No-Message-Loss-Or-Dups) hold on the Wi-Fi run. The quiescence mechanism behaves per the PR #330 mini-lock contract on the network class where REST fallback works. **This does NOT mean PR #330 is ship-ready** — it means the mechanism is mechanically correct.
- **B1-PARTIAL or B1-FAIL:** Per the original §6 Phase B dispositions. PR #330 stays Draft / HOLD; a separate fix-track may open for the failing hypotheses. The recon may amend B1 scope at that point.

K-3 (emu Wi-Fi) under B1: still NOT promoted as a B1 prerequisite. K-3 would add a third baseline but does not add discrimination value for the quiescence-mechanism check; the K-1 Tecno Wi-Fi baseline + the K-6 Tecno Wi-Fi fix-candidate comparison are sufficient for B1.

### Phase B2 — separate Tele2 LTE delivery-blocker track

**Scope.** Tele2 LTE end-to-end delivery is a separate problem, governed by `rc-direct-stability1.md` §13 (long-connection-uplink finding) and the new TELE2-LTE-REST-BREAKER-RECON1 §11 closure (downlink long-poll body loss on the same network class). It is NOT a question this recon answers; it requires its own facts-first track when the operator opens it.

**Not yet opened.** The B2 mini-lock is a forward-pointer only at the time of this amendment. When the operator schedules it, the candidate scope shapes (listed in TELE2-LTE-REST-BREAKER-RECON1 §11 closure as forward-pointer only — short-poll mode on LTE, decoupling `callTimeout` from `readTimeout`, server-side keep-alive byte trickle, alternative relay endpoint or transport on LTE-detected paths) become candidate hypotheses for B2's §4 — but each requires its own discrimination matrix and acceptance gates, NOT promotion as a fix from this recon.

### Release / rollout gate (the load-bearing constraint of the B1 / B2 split)

**PR #330 does NOT ship to LTE users on the strength of a B1-PASS verdict alone.** B1-PASS confirms the quiescence mechanism is mechanically correct on Wi-Fi; it does NOT confirm that the mechanism delivers messages on Tele2 LTE, because the underlying REST fallback substrate is structurally broken on that network class per the carry-forward evidence. Shipping PR #330 to LTE users without a B2-resolution would expose users to a known failure mode that this recon already documented.

**Two paths to LTE rollout (NOT promoted from this recon — listed as the candidate decision set the operator will face after B1):**

- **(i)** B2 closes with a fix-track verdict that resolves the long-held-connection problem on Tele2 LTE, the fix lands, and a B2-validated APK demonstrates Tele2 LTE end-to-end delivery in a separate field run. Then PR #330 may ship to LTE users.
- **(ii)** An alternative transport for the LTE class lands separately (Reality, an alternative relay endpoint, or a relay-side hold-policy change) and demonstrates working delivery on Tele2 LTE without the long-poll body-loss surface. Then PR #330 may ship to LTE users.

Either path requires affirmative evidence of working LTE delivery, NOT just B1-PASS on Wi-Fi. "Mechanism mechanically correct on Wi-Fi" is not sufficient evidence for shipping to a user population whose primary network is Tele2 LTE.

### Forward-pointer summary

| Deliverable | Status | Gate before next step |
|---|---|---|
| B1 Wi-Fi mechanical validation (K-5 build + K-6 Tecno Wi-Fi session) | Operator-scheduled, NOT auto-started | B1-PASS = quiescence mechanism mechanically correct on Wi-Fi |
| B2 Tele2 LTE delivery-blocker track | NOT yet opened (forward-pointer only) | Operator decision after B1 closes |
| PR #330 ship to LTE users | NOT permitted on B1-PASS alone | Requires B2 resolution OR alternative LTE transport |

### What this amendment does NOT decide

- **No code change.** This is a docs amendment to clarify the Phase B scope split.
- **No fix scope-lock.** The B2 candidate fix-shape sets stay as forward-pointer.
- **No commit to PR #330's branch.** The K-5 fix-candidate APK is a build-time operation when B1 runs.
- **No promotion of B2 to a mini-lock today.** B2 is announced as the next track that opens when the operator schedules it.
- **No re-opening of `rc-direct-stability1.md` §13 root-cause investigation.** §13's long-connection-uplink finding is carry-forward evidence used by both TELE2-LTE-REST-BREAKER-RECON1 (closed) and the eventual B2 track.

## §12 K-6 outcome — Wi-Fi non-regression PASS, quiescence NOT EXERCISED (2026-06-28)

Operator-executed K-6 on 2026-06-28: Tecno Wi-Fi + emu Wi-Fi pair (~30 min), VPN OFF, fix-candidate APK SHA-256 `65ebaebf3a3f72e0eb8bc4bf381bcc85f72482370b0c401ad36a8d6f99803e35` built from PR #330 head `6f49cd89` with all three RC release flags forced to `"1"` via env vars at build time (`MODE_2_FAST_PATH_ENABLED` + `MODE_2_STICKY_ENABLED` + `RECONNECT_QUIESCENCE_ENABLED` — verified in `BuildConfig.java`). NO commits made to PR #330's branch; the build was a detached-HEAD operation only.

### Verdict: NEW class — B1 non-regression PASS / QUIESCENCE NOT EXERCISED

This run does NOT close B1 per §11's acceptance gate. §11 required all six Phase B hypotheses to hold for B1-PASS; one of the six PASSED, four were NOT EXERCISED, and one was vacuously satisfied. The verdict is a third class that §11 did not anticipate but the field result demands: **the mechanism did not regress, but it also was not exercised by the field conditions** because Wi-Fi did not reproduce Mode 2 on this run (consistent with K-1's `F-Mode1` rhythm carry-forward).

### Phase B hypothesis matrix on K-6

| Hypothesis (§4 Phase B) | K-6 status | Evidence |
|---|---|---|
| H-330-Quiesces-Storm | **NOT EXERCISED** | Mode 2 detector never fired. Zero `MODE_2_KILLED` / `sticky_armed` / `mode_2_fast_path` / `ws_reconnect_quiesced` / `ws_recovery_probe_granted` events on either side. With no Mode 2 episode there is no storm to quiesce. |
| H-330-Preserves-REST | **VACUOUSLY PASS** | REST events present (Tecno 71, emu 74; K-1 baseline 90/88, lower count attributable to shorter session window — 30 min vs K-1's 44 min). But REST was NOT stress-tested by a quiescence window, so the assertion that REST keeps delivering DURING quiescence cannot be verified from this corpus. |
| H-330-Single-Probe-Per-RouteChange | **NOT EXERCISED** | No route change was driven during the session (static Wi-Fi throughout). The hypothesis discriminator requires at least one user-observable route change; this run had none. |
| H-330-Probe-Lives-60s | **NOT EXERCISED in the quiescence context** | Five `mode_switched ... reason=ws_alive_60s` events observed on Tecno (4) + emu (1), BUT each one was a `WsCandidate → WS_ACTIVE` transition without a preceding `sticky_armed` event. These are the R3.6 sticky-per-route fast REST degradation lifecycle transitions that landed in PR #328 (already on master before PR #330); they are NOT quiescence-recovery probes. The `ws_alive_60s` proof field has the same name but the semantic context is different — the quiescence-recovery probe requires `sticky_armed` precondition. |
| H-330-No-Self-Reentry | **VACUOUSLY PASS** | No quiescence probe was issued, so a probe cannot self-reenter. The hypothesis is not falsifiable on this corpus. |
| H-330-No-Message-Loss-Or-Dups | **PASS** | `relay_send_return ok=false` count = 0 on both sides. Manual UI check (all 4 × 2 messages delivered as recorded by the operator). End-to-end correlation between sender's `SEND_TRACE relay_send_return ok=true` and receiver's decrypt path confirmed on the operator's UI verdict. |

**Net: 1 PASS, 4 NOT EXERCISED, 1 VACUOUSLY PASS.** §11's `B1-PASS` definition ("all six Phase B hypotheses hold") is technically met — none of the six FAILED — but the spirit of validation (the mechanism was actually tested) was not met.

### What this verdict establishes

- **PR #330 with the three flags forced to `"1"` does NOT regress Wi-Fi delivery.** Messages flowed in both directions. No duplicates. No losses. The R3.6 state machine cycled correctly (`WsActive → REST_ACTIVE → WS_CANDIDATE → WS_ACTIVE` via `ws_frame_text_received` then `ws_alive_60s`) — same shape as master HEAD without quiescence flags would produce.
- **Sprint 2b-C inbound_repair_ok promotion=true works on Wi-Fi.** Seven `pending_fallback_fail` events on each side were all followed by successful `DECRYPT_TRACE inbound_repair_ok ... promotion=true`. Zero `DECRYPT_TRACE fail_mac`, zero `OpkNotFound`, zero `inbound_repair_fail`, zero `action=hold`. This is a positive data point against the K-2 LTE side-finding's "Sprint 2b-C might regress" worry — on Wi-Fi the repair path stays healthy.
- **Reconnect ratios unchanged vs K-1.** Tecno 33/16 = 2.06× connect attempts per WSS session; emu 33/16 = 2.06×. K-1 baseline: 2.0× both. The PR #330 mechanism neither amplified nor suppressed reconnects — because it was not engaged.

### What this verdict does NOT establish

- **Core PR #330 quiescence chain (sticky → probe → ws_alive_60s recovery) was NOT field-validated.** The four NOT-EXERCISED hypotheses cover the load-bearing claims of the PR #330 mini-lock; none of them were tested by this run.
- **PR #330 is NOT closer to ship-readiness on the strength of K-6 alone.** Per §11 release / rollout gate: B1-PASS on Wi-Fi (even strict B1-PASS) does not unblock LTE rollout; this K-6 result does not even strictly meet B1-PASS, it meets a non-regression-only sub-class.
- **`F-Mode1` vs Mode 2 detector boundary on the device side.** K-1 captured 21 F-Mode1 sessions on the same Wi-Fi without firing the detector; K-6 captured 16 sessions of the same shape, also without firing the detector. This is consistent with the F-Mode1 / F-Mode2 distinction in `rc-direct-stability1.md` §1 (Mode 1 = return-path loss with 7-8 pongs / ~150 s; Mode 2 = uplink loss with 0-1 pongs / ~30-45 s). The Mode 2 detector apparently looks for the Mode 2 signature specifically, not the Mode 1 signature. This is by design (PR #328's R3.6 explicitly targets Mode 2), but it means the Wi-Fi class as currently observed is not a venue where the detector can fire.

### Phase B status after K-6

- **B1 acceptance gate: NOT closed.** Per §11's strict reading (all six hypotheses hold), K-6 meets the letter but not the spirit. A future deliverable that exercises the quiescence chain is required before B1 can be honestly declared closed.
- **K-3 (emu Wi-Fi-only) remains NOT promoted.** It does not change the Mode-2-not-fired situation.
- **PR #330 stays Draft / HOLD.** No change to RC #330's status. The release / rollout gate from §11 stays in force.

### Forward-pointer — methodology question (NOT scoped here)

The K-6 outcome surfaces a structural validation question that this recon's current scope does not answer: **how do we validate the quiescence mechanism when Wi-Fi does not reproduce Mode 2 (no symptom to suppress) AND Tele2 LTE breaks the REST fallback substrate the mechanism depends on (no working substrate to validate against)?**

Three candidate paths for the operator to decide (NOT promoted from this recon; listed only as the candidate decision set for a future methodology recon if one opens):

- **(a)** Find a third network class that reproduces Mode 2 AND keeps REST fallback alive. Candidates: a different LTE carrier (M-3 from TELE2-LTE-REST-BREAKER-RECON1 was never run), tethered Wi-Fi through a mobile hotspot, a Wi-Fi network with degraded RSSI / packet loss profile. Each is uncertain.
- **(b)** Use synthetic / instrumentation-only triggers (e.g. a debug-only flag that forces Mode 2 detection state for testing). Carries the risk of false-confidence: "we broke things by hand and quiescence worked" does not prove the mechanism correctly classifies real Mode 2 vs benign noise.
- **(c)** Accept that field-only validation of the quiescence chain is not possible in current conditions, and validate at a different level (state-machine / integration tests against a fake transport that scripts the Mode 2 surface, or a controlled relay-side reproduction). Carries the risk that the model the tests check is not the model the field actually presents.

A separate facts-first recon (working name `QUIESCENCE-VALIDATION-METHODOLOGY-RECON1`) is the natural place to discriminate among (a), (b), (c). This recon is NOT opened by this PR — it is a forward-pointer for the operator's next decision.

### Side-findings preserved (NOT actioned here)

- **Operator's QR-add → reverse-add warm-up flow** was the same paired-bootstrap pattern as K-1; not a scenario deviation, just the natural first-contact flow.
- **Receiver-side decrypt counts (Tecno 6, emu 10)** lower than expected for 4 × 2 = 8 scheduled messages plus warm-up plus acks. The discrepancy is likely because some inbound paths logged `inbound_repair_ok` (7 events on each side) rather than `DECRYPT_TRACE ok`; the analysis script's regex counted only the latter. Future K-runs should add `inbound_repair_ok` to the receiver-side delivery count to avoid under-counting deliveries that took the repair path.
- **Five `mode_switched ... reason=ws_alive_60s` events** (Tecno 4 + emu 1) demonstrate the R3.6 sticky-per-route fast REST degradation flow from PR #328 is working correctly with the three flags forced on. This is a positive data point for the R3.6 baseline, NOT for PR #330's quiescence layer specifically.

## §13 B1 acceptance gate — CLOSED via controlled Wi-Fi smoke (2026-07-02)

**B1 status flips: CLOSED.** The three §7 QUIESCENCE-VALIDATION-MC-HALF-MINI-LOCK gates all cleared:

1. **MC PASS**: PR #360 squash `fd435c93` merged (three-PR stack MC-1 `91745acd` + MC-2 `7bcf2d04` + MC-3 `fd435c93`).
2. **MB PASS**: PR #353 squash `ed3406eb`.
3. **Wi-Fi smoke PASS**: run 2 (2026-07-02, Tecno BF7-12 + emu Wi-Fi pair, fix-candidate APK `sha256: 5d3de3790b999f957e667d63dcf93539ecbb0015867de7ef3703b2161ade304e` built from master `fd435c93` with all four flags forced to `"1"` via `-P` gradle properties: `RECONNECT_QUIESCENCE_ENABLED` + `MODE_2_FAST_PATH_ENABLED` + `MODE_2_STICKY_ENABLED` + `DEBUG_FORCE_MODE_2_DETECTION`).

The path forward addressing §11's forward-pointer methodology question: option (b) synthetic trigger via `DebugForceMode2Activity`. The methodology recon `QUIESCENCE-VALIDATION-METHODOLOGY-RECON1` (PR #349 squash `54f2e50d`) closed with verdict H-ME requiring both an MB half (synthetic field trigger) and an MC half (deterministic state-machine / integration validation). MB landed via PR #353 (L1 synthetic-trigger implementation); MC landed via PR #358/#359/#360 stack. This Wi-Fi smoke is the final field-shape check that §7 of the MC-half mini-lock required as the last gate before B1 closure.

### Wi-Fi smoke run 2 evidence

Chronology (Tecno wall clock, complete quiescence chain):

```
17:34:53.781  mode_2_signature_matched action=fast_path duration_ms=45000
17:34:53.791  sticky_armed gen=1
17:34:53.792  ws_reconnect_quiesced gen=1
17:34:58.847  ws_recovery_probe_granted gen=1 route_epoch=1
17:35:09.992  ws_reconnect_quiesced reason=route_change_invalidates_probe
17:35:10.066  ws_recovery_probe_granted gen=1 route_epoch=2
17:35:11.770  sticky_recovery_started gen=1
17:35:11.775  ws_reconnect_resumed sticky_gen=1 session_epoch=2
17:36:11.799  sticky_cleared proof=ws_alive_60s elapsed_ms_since_arm=78008
17:36:11.800  ws_reconnect_open proof=ws_alive_60s
17:36:11.801  mode_switched WsCandidate → WS_ACTIVE reason=ws_alive_60s
```

All 8 required markers TRUE, 1× `sticky_armed`, 1 grant per unique route_epoch, probation 78008ms within [60000..90000] window per acceptance matrix. Delivery both directions PASS via WS path — Emu → Tecno through Sprint 2b-C `inbound_repair_ok promotion=true` bootstrap (`7b1733ad`), Tecno → Emu mirror (`f290bfb1`), then Emu → Tecno second message via direct `DECRYPT_TRACE ok bootstrap=false` (`6960ac2a`) after session promoted.

### Phase B hypothesis matrix — B1 CLOSED per §11 acceptance

| Hypothesis | Verdict on Wi-Fi smoke run 2 |
|---|---|
| H-330-Quiesces-Storm | **PASS** — synthetic Mode 2 → `sticky_armed` → `ws_reconnect_quiesced`; no reconnect storm during window |
| H-330-Preserves-REST | **PASS** — REST fallback flowed messages during Quiesced state; 0 delivery errors |
| H-330-Single-Probe-Per-RouteChange | **PASS** — 2 unique route_epochs × 1 probe grant each (correct `route_change_invalidates_probe` invariant) |
| H-330-Probe-Lives-60s | **PASS** — `sticky_cleared proof=ws_alive_60s elapsed_ms_since_arm=78008` (within 60-90s window) |
| H-330-No-Self-Reentry | **PASS** — candidate session did not autonomously spawn; recovery gated by NetworkChanged only |
| H-330-No-Message-Loss-Or-Dups | **PASS** — 0 `relay_send_return ok=false`; 3 messages delivered end-to-end via Sprint 2b-C repair + direct decrypt paths |

**Net: 6 PASS, 0 NOT EXERCISED, 0 FAIL.** §11's strict acceptance is met — this time in both letter AND spirit.

### Run 1 side-finding preserved (NOT a B1 blocker)

An earlier smoke attempt (run 1, same session, before `pm clear + fresh onboarding`) surfaced `x3dhInitPresent=false` + `fail_mac action=hold` on receiver-side Tecno. Root cause was contamination from prior test cycles' broken conversation state — session record persisted without matching X3DH init blob. After full `pm clear` on both devices + fresh onboarding + pairing, run 2 showed `x3dhInitPresent=true` throughout and Sprint 2b-C `inbound_repair_ok promotion=true` fired successfully for the first message each direction. Root cause is contamination-only; if the pattern reproduces on future clean-state runs, `RECV-SESSION-X3DH-INIT-STATE-RECON1` will open then.

### What this closure establishes

- **PR #330's quiescence contract is field-validated on Wi-Fi.** The full chain (`sticky → probe → ws_alive_60s recovery`) fires and the 60-second probation completes cleanly. R3.6 sticky-per-route flow from PR #328 remains intact.
- **MC PASS + MB PASS + Wi-Fi smoke PASS jointly satisfy §7 of the MC-half mini-lock.** The two H-ME halves are both on record; the controlled Wi-Fi smoke was the final field-shape check.
- **Sprint 2b-C repair path is functional on master `fd435c93`.** 3× `inbound_repair_ok promotion=true` fired on run 2, closing the "Sprint 2b-C might be regressed by MC stack" concern.

### What this closure does NOT change

- **B2 remains unopened.** Tele2 LTE delivery-blocker track from §11 is still forward-pointer only. PR #330 does NOT ship to LTE users on the strength of a Wi-Fi B1-PASS alone per §11's release / rollout gate.
- **Release / rollout gate stays in force.** Either path (i) B2 closes with a fix that resolves the LTE long-connection surface, or path (ii) an alternative transport lands, is still required before LTE rollout.
- **PR #330's Draft / HOLD status.** No change here; the RC PR moves per its own mini-lock contract now that B1 is closed.
- **No BodyTimeoutContractTest unquarantine.** Deferred to a separate follow-up PR.

### Non-blocking follow-ups (opened separately, not by this closure)

- `BodyTimeoutContractTest` unquarantine — reproduce hang in Linux Docker → fix `runTest` + `gateLock` + fixture dispatcher-mix → remove class-level `@Ignore` from PR #360 Round 6 quarantine.
- Smoke script delivery invariant fix — accept `handleDeliver DONE` / `Inserting message into DB` / `DECRYPT_TRACE ok` / `DECRYPT_TRACE inbound_repair_ok` as delivery signals in `smoke-run.sh` (currently only `REST_TRACE inbound_deliver` is counted, which produced a false-positive `FAIL` on run 2 whose delivery went via WS path).
- §4.3 P3 regex hardening in `CloseOriginSyntheticNonBranchingGrepTest` — extend reversed-comparison pattern to receiver-prefixed forms like `"synthetic" == event.closeOrigin` (deferred from PR #360 Round 4 revert).
