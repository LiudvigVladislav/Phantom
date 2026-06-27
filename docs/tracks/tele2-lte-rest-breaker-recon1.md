# Track: TELE2-LTE-REST-BREAKER-RECON1

**Type:** Facts-first reconnaissance / diagnostic. NOT a code-fix track.
**Status:** Open (mini-lock).
**Opened:** 2026-06-27 after K-2 of `direct-wss-mode2-recon1.md` Phase A captured a session in which Tecno's REST poll on Tele2 LTE failed with repeated `InterruptedIOException`, opened the R3.6 sticky-recovery breaker through the full cooldown ramp `5 s → 10 s → 20 s → 40 s → 80 s → 120 s → 120 s`, and delivered zero inbound messages to Tecno for the remainder of the 30-minute window. The `H-REST-Survives` hypothesis from the parent recon's §4 was refuted on Tele2 LTE — Phase B (RC PR #330 validation) cannot proceed without a clean REST-fallback baseline on this network class.
**Last known master at open:** PR #339 squash `5934310e`.

---

## §1 Goal

Discriminate the root cause of the Tecno Tele2 LTE REST-poll failure observed in `direct-wss-mode2-recon1.md` §10 K-2 (2026-06-27). The recon produces an **evidence-graded discrimination matrix** that answers four concrete questions about the observed surface:

1. Why does Tecno's REST poll on Tele2 LTE receive `InterruptedIOException` rather than completing the long-poll cleanly?
2. Why does the breaker cooldown ramp reach the 120 s ceiling so quickly (≤ 5 minutes from first failure)?
3. After the breaker opens, why does Tecno receive no inbound messages — is the breaker open state itself blocking the inbound dispatch path, or is the relay-side state separately broken?
4. Is the failure surface Tele2-specific, or would it reproduce on other carriers / Wi-Fi under similar Direct WSS Mode 2 conditions?

The recon does NOT propose a fix. If the matrix supports a specific cause, the disposition is a separate scope-lock; if the cause stays ambiguous, the disposition is a park.

## §2 Scope

- Bound the `InterruptedIOException` source: orchestrator cancellation (e.g. during WS state-machine transitions) vs network-side TCP cut vs OkHttp timeout.
- Bound the breaker cooldown progression: is the 5 s → 120 s ramp policy correct for the observed failure shape, or does the policy promote a transient blip into a sustained outage.
- Map the breaker open state's effect on inbound delivery — does it only skip `op=poll`, or does it also block WS deliver-in dispatch / REST-fallback inbound consumption.
- Bound the carrier specificity: does the same failure shape reproduce on other LTE carriers or on Wi-Fi under similar WS storm conditions.

## §3 Out of scope

- **Any code change.** This track discriminates. If a fix is identified, a separate scope-lock follows.
- **RC PR #330.** Stays Draft / HOLD throughout. No edits, no rebase, no force-push, no merge attempts.
- **Direct WSS Mode 2 root cause.** Parked in `rc-direct-stability1.md` / `rc-direct-ws-death1.md`. The carry-forward `F-Mode2-pp0` shape is INPUT to this recon (it explains why Direct WSS dies on LTE), not scope to re-investigate.
- **Sprint 2b-C `OpkNotFound` / `fail_mac` family.** K-2 surfaced this on the Emu side as a side-finding; that is NOT scope under this recon. If it reproduces in subsequent runs, a separate `SPRINT-2B-C-INBOUND-REPAIR-REGRESSION-RECON` track opens.
- **DIRECT-WSS-MODE2-RECON1 Phase A K-3 / Phase B.** Both gated on this recon's closure.
- **VPN-TRANSPORT-COMPAT-RECON1.** Separate parallel track. VPN OFF for every reproduction attempt.
- **REST-SEND-CONNECTIVITY-RECON1.** Parked in PR #338. The `InterruptedIOException` here is a separate failure class (post-headers cancellation, not pre-handshake `connectFailed`).
- **Predictive remediation.** Specifically NOT pre-locked: extending the breaker cooldown floor / ceiling, removing `InterruptedIOException` as a breaker-failure class, decoupling REST poll from WS state transitions, adding a REST-only fallback mode that bypasses the breaker, replacing the long-poll endpoint with short-poll on LTE. Each is a fix shape and is out of scope until evidence closes a gate.

## §4 Hypotheses to discriminate

Each hypothesis names a candidate locus / mechanism for the observed surface. Only one (or a clean intersection of two) is expected to survive the matrix.

- **H-OrchestratorCancellation** — The `InterruptedIOException` events on Tecno are produced by the REST-fallback orchestrator cancelling its in-flight long-poll calls during Direct WSS state-machine transitions (e.g. when a WS connect attempt starts or a WS reconnect is scheduled, the orchestrator interrupts the active poll to avoid wasted work). On Tele2 LTE where Direct WSS dies every ~31 s, the orchestrator interrupts polls every ~31 s, and the breaker counts each as a `ConsecutiveRestFailure`. Discriminator: cross-correlate `InterruptedIOException` event timestamps against orchestrator state-machine transition events (`RestActive ↔ WsCandidate` etc.) in the same logcat. If the InterruptedIO timestamps cluster within `±1-2 s` of WS state transitions, H-OrchestratorCancellation is supported.
- **H-NetworkSideCancellation** — The `InterruptedIOException` is a genuine network-side TCP cut (carrier NAT mid-poll teardown, mid-stream RST, or LTE radio sleep cutting the connection mid-body). The orchestrator and OkHttp surface it as `InterruptedIOException` because the body read is interrupted by the network. Discriminator: M-2 packet capture during a reproduction, or correlation of InterruptedIO events against radio-state-change logs.
- **H-BreakerCooldownTooAggressive** — The R3.6 sticky-recovery breaker cooldown ramp `5 → 10 → 20 → 40 → 80 → 120 s` (verified against K-2 logcat `cooldown_ms=5000 ... 120000`) is too steep for transient LTE blips: a single Mode 2 burst (~31 s) cascades into a sustained breaker-open state because the cooldown ceiling is reached before the underlying network recovers. The policy assumes the underlying failure persists if not cleared in 5-10 s, which is not true for the Tele2 LTE WS-storm shape. Discriminator: M-5 source code read of the breaker cooldown policy + M-1 cross-correlation of breaker_open events against actual REST poll success windows.
- **H-BreakerOpenBlocksInboundDispatch** — When the breaker is open, the REST orchestrator skips `op=poll` (this is by design and visible in `poll_call_skipped reason=breaker_open_*`), but the side effect is that inbound messages also do not propagate from any WS deliver-in path because the WS-deliver-in handler shares state with the REST-poll dispatcher. The breaker-open state is not isolated to the REST poll cycle — it also halts the inbound delivery handoff. Discriminator: M-5 source code read of the inbound dispatch architecture, looking for shared state between `op=poll` and `ws_deliver_in` paths.
- **H-Tele2LTESpecific** — The failure surface is unique to Tele2 LTE and does not reproduce on other carriers (MTS, Megafon, Beeline) or on Wi-Fi under similar WS storm conditions. The InterruptedIOException + breaker progression is driven by a Tele2-specific carrier behaviour (NAT teardown rhythm, packet loss class, RAN configuration). Discriminator: M-3 reproduction attempts on a different LTE carrier if an operator-accessible SIM exists, OR cross-reference with prior corpus from RC-DIRECT-STABILITY1 §4 Arm A.2 / T2 evidence which used Tele2 LTE too.
- **H-OkHttpInterruptionRace** — The `InterruptedIOException` is OkHttp's reaction to dispatcher state being changed (executor shutdown, thread cancellation) during the long-poll body read. The race is not between WS state and REST state but between OkHttp client lifecycle events. Discriminator: M-5 source code read of OkHttp client + dispatcher wiring on master HEAD, looking for shutdown / interrupt calls during normal operation.

H-OrchestratorCancellation and H-BreakerCooldownTooAggressive are not mutually exclusive — both could be true simultaneously and explain different parts of the observed surface (one explains the InterruptedIOException source, the other explains the sustained-open breaker state).

## §5 Diagnostic instruments

Each instrument runs only when justified by the previous instrument's outcome. Each new-instrument decision is a track-doc amendment, not a free choice in the moment.

- **M-1 — Re-read existing K-2 corpus for orchestrator-state-transition timeline.** Cross-correlate each `InterruptedIOException` event timestamp against the nearest `WsSessionLifecycleEvent` / `RestStateMachine` state transition entry in the same logcat. Discriminates H-OrchestratorCancellation. The K-2 evidence files (`~/Downloads/direct-wss-mode2-k2-tecno-lte/tecno.log` SHA `13d597a2...`) are already on disk; M-1 is a re-read instrument, not a re-run.
- **M-2 — Repeat K-2 on Tele2 LTE with packet capture on Tecno (`tcpdump` via `adb shell` if available, or on an upstream router if reachable).** Captures any TCP-layer events (RST, FIN, retransmit storms) that co-incide with the `InterruptedIOException` events. Discriminates H-NetworkSideCancellation from H-OrchestratorCancellation / H-OkHttpInterruptionRace.
- **M-3 — Repeat K-2 on a different LTE carrier (if an operator-accessible SIM exists).** Same APK, same Mac, same Tecno, different carrier. Discriminates H-Tele2LTESpecific. If not available, M-3 reduces to a documentation-only point and the recon proceeds without it.
- **M-4 — Repeat K-2 with WS disabled** (a debug-flag-gated REST-only mode if one exists, or a manual workaround) so the breaker behaviour is tested independent of WS state-machine churn. Discriminates whether the breaker progression is caused by the WS-storm-co-incident cancellations or by intrinsic REST instability on LTE.
- **M-5 — Source-code read** of the breaker cooldown policy (`RestFallbackOrchestrator`), the WS-to-REST state-machine transitions, the InterruptedIOException handling, and the WS-deliver-in dispatch path. NOT a code change — only a read. Maps the architectural shared-state surface between WS deliver-in and REST poll. Discriminates H-BreakerOpenBlocksInboundDispatch and H-OkHttpInterruptionRace.

The recon may add an instrument if a previous instrument's evidence demands one; it does not run instruments that the evidence does not require. The natural order is M-1 first (cheap, evidence already on disk), then M-5 (also cheap, source-only), then M-2 / M-3 / M-4 depending on what M-1 + M-5 reveal.

## §6 Acceptance gates

The recon CLOSES when the matrix supports exactly one of the following dispositions:

1. **H-OrchestratorCancellation confirmed.** M-1 shows `InterruptedIOException` events cluster within `±1-2 s` of WS state transitions, AND M-5 confirms the orchestrator code interrupts in-flight polls during specific WS state changes. Disposition: a separate fix scope-lock opens to either (a) decouple the orchestrator's cancellation policy from WS state churn, or (b) prevent `InterruptedIOException` from incrementing the `ConsecutiveRestFailures` counter when the cause is a known-internal cancellation. The recon does NOT propose which option is right — only documents that the cause is orchestrator-internal.
2. **H-NetworkSideCancellation confirmed.** M-2 packet capture shows TCP RST / FIN / mid-stream cuts co-incident with the `InterruptedIOException` events. Disposition: the failure is below the app layer; the breaker policy is technically correct in counting these as REST failures, but the cooldown ramp may still need adjustment for the LTE class. Open a separate fix scope-lock for cooldown policy.
3. **H-BreakerCooldownTooAggressive confirmed AND H-OrchestratorCancellation NOT confirmed.** M-1 shows `InterruptedIOException` events are NOT clustered with WS state transitions, M-5 confirms the cooldown ramp policy reaches 120 s in 5 escalations. Disposition: fix scope-lock for cooldown policy only; root cause of `InterruptedIOException` remains open as a separate question for the parent recon's K-3.
4. **H-BreakerOpenBlocksInboundDispatch confirmed.** M-5 shows shared state between `op=poll` and `ws_deliver_in` dispatch. Disposition: fix scope-lock for inbound-dispatch isolation; this is a delivery-correctness blocker independent of the WS Mode 2 question.
5. **H-Tele2LTESpecific confirmed.** M-3 shows the same APK on a different LTE carrier does NOT reproduce the failure. Disposition: documented as a Tele2-carrier-specific surface; RC PR #330 validation can proceed on a different carrier OR the contract is re-scoped to "Direct WSS quiescence works on non-Tele2 LTE networks, Tele2 LTE handled separately."
6. **H-OkHttpInterruptionRace confirmed.** M-5 source read identifies an OkHttp lifecycle race producing `InterruptedIOException`. Disposition: fix scope-lock for the race; potentially affects more than just LTE.

Two-or-more concurrent verdicts that contradict each other close the recon as **inconclusive — escalate to Council**.

## §7 Park conditions

The recon parks (suspends work ≥ 7 days, releases the master lock for any other track) under any of:

- **P-1 — Symptom non-reproducible in repeat M-2 attempts.** Three consecutive K-2-shape reproduction attempts on Tele2 LTE produce no breaker_open events and < 3 `InterruptedIOException` events per attempt. The 2026-06-27 K-2 evidence stays on file as a single-shot event.
- **P-2 — Operator unavailable for further attempts on Tele2 LTE.** Pragmatic park; the recon captures whatever M-1 + M-5 produce and parks the remainder.
- **P-3 — Carrier-network-side cause pinpointed (H-NetworkSideCancellation confirmed).** If M-2 conclusively shows the failure is TCP-level cuts from the carrier, and no client-side mitigation is realistic without changing the long-poll architecture, the recon parks with the disposition "documented as a Tele2-carrier-network limitation" and the parent recon's Phase B re-scopes the contract accordingly.

## §8 Out-of-scope explicitly NOT promoted to invariants

- Direct WSS Mode 2 root cause (parked in `rc-direct-stability1.md` / `rc-direct-ws-death1.md`).
- Sprint 2b-C `OpkNotFound` / `fail_mac` family (single-observation side-finding from K-2; not scope here).
- DIRECT-WSS-MODE2-RECON1 Phase B and K-3 (gated on this recon's closure).
- VPN-TRANSPORT-COMPAT-RECON1, REST-SEND-CONNECTIVITY-RECON1 (separate / parked).
- Voice / Calls / Reality / Tor / Trek 2 / DWS-UX / R3.6 sticky-recovery design itself (R3.6 is observed evidence, not scope to redesign).
- Any pre-locked fix shape.

## §9 Hand-off note

If this session ends before M-1 runs: the next session reads §1 + §4 + §5 and runs M-1 (re-read of K-2 corpus for orchestrator-state-transition timeline). M-1's output is a recon-progress comment / docs PR amendment on this track; do NOT propose a fix shape in the M-1 progress note.

If this session ends after M-1 runs: the next session decides M-5 (source code read) vs M-2 (packet capture re-run) based on M-1's verdict. The natural order is M-1 → M-5 → M-2/M-3/M-4 because the first two are cheap and produce maximum discrimination per effort.

Do NOT propose a fix shape in any progress note. Do NOT touch RC PR #330. Do NOT re-open the parent `direct-wss-mode2-recon1` Phase A K-3 or Phase B until this recon closes with one of §6's dispositions.

## §10 Progress note — M-1 + M-5 combined (2026-06-27)

M-1 re-read of the existing K-2 corpus (`~/Downloads/direct-wss-mode2-k2-tecno-lte/tecno.log` SHA `13d597a2...`) and M-5 source-code read of REST orchestrator + breaker + WS deliver-in path. Combined progress pass; no operator action required, no field re-run. Four hypotheses moved to refuted, two moved to strongly-supported. Recon does NOT close on this pass — M-2 packet capture remains the cleanest path to conclusive H-NetworkSideCancellation confirmation per §6 Gate 2, OR the strong correlation with `rc-direct-stability1.md` §13 carry-forward evidence can serve as substitute confirmation (decision for the operator on the next pass).

### M-1 — InterruptedIOException timing pattern

11 `op=poll callFailed exception=InterruptedIOException` events on Tecno in the K-2 corpus, with the following invariant:

- **`totalMs = 35005 ± 1 ms` on every single event.** The 11 events at wall-clock `13:17:33.935, 13:18:18.791, 13:18:26.774, 13:19:09.322, 13:19:14.354, 13:19:50.140, 13:20:38.863, 13:21:34.054, 13:22:51.920, 13:24:50.726, 13:27:26.070Z` all carry the same `totalMs` value modulo 4 ms of jitter (35002 to 35006).

A fixed timeout firing at exactly 35 s is mechanistically inconsistent with H-OrchestratorCancellation — if the cause were orchestrator cancellation driven by WS state transitions, the elapsed time at cancellation would vary by 0-35 s depending on when the WS event arrived in the call's lifetime. The observed constancy at the budget ceiling indicates the call ran to its own timeout, not to an external interrupt.

12 `op=poll responseHeadersEnd status=200` events also appear in the corpus, meaning the server DID respond with HTTP 200 headers on those polls. Body chunks were observed (e.g. `cumulative_bytes=875, 1152, 2304, 3456` on poll starting `13:17:28.228Z`). **Zero `op=poll responseBodyEnd` events** across the entire 30-minute window: not a single poll completed its body delivery. Successful polls received headers and partial body chunks (typically up to `cumulative_bytes=3456` of the expected ~4608-byte padded Round 14 poll response), then the remaining bytes never arrived, and OkHttp's `callTimeout` fired at the 35 s budget.

### M-5 — source-code read of REST orchestrator, breaker, and WS deliver-in

Three load-bearing findings from the source-only read of master HEAD `b3943641` (no code change):

1. **35 s = `(pollHoldSecs=30 + POLL_HOLD_SAFETY_MARGIN_SECS=5) × 1000 ms`** — defined in `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/RestFallbackOrchestrator.kt::computeLongPollReadTimeoutMs` at line 3689 with `POLL_HOLD_SAFETY_MARGIN_SECS = 5` (line 3632). Threaded into the OkHttp `callTimeout` via `AndroidNativeOkHttpRestFallbackTransport.kt` lines 335 (`callTimeout(effectiveCallMs, ...)`) and 337 (`readTimeout(effectiveReadMs, ...)`). The `effectiveCallMs` resolves to the 35 s long-poll value when long-poll is enabled, which it is on master HEAD (`LONGPOLL_V2_ENABLED == "1"` per Stage 2B-D release pin in PR #320). The InterruptedIOException firing at exactly 35 s is the OkHttp call timeout reaching its budget, NOT an orchestrator-internal interrupt — this is the textbook callTimeout shape.

2. **WS deliver-in path is NOT gated by REST breaker state.** `apps/android/src/androidMain/kotlin/phantom/android/transport/HybridRelayTransport.kt::startWsPassthroughCollectors` at line 446 launches a `scope.launch { wsTransport.incoming.collect { deliver -> ... _incoming.emit(deliver) ... } }` block that pipes WS frames directly into the messaging-service inbound SharedFlow. The collector body neither reads nor checks the REST orchestrator's breaker state. When the breaker is open, only `op=poll` is skipped (`RestFallbackOrchestrator.kt::BreakerIterationDecision.Skip("breaker_open_${current.reason}")` at line 2438); the WS deliver-in path remains live. Therefore the absence of inbound deliveries to Tecno during the K-2 window is NOT caused by the breaker blocking WS dispatch — it is caused by (a) Tecno's WS sessions never becoming alive (Mode 2 pp0, 0 successful ping/pongs per session, ~31 s lifetime) so no actual WS deliver-in frames arrive, AND (b) the REST poll path failing as analysed in §10.M-1 above. Two independent failure surfaces, not one cascaded blocker.

3. **The breaker's failure-counting policy is internally consistent** — every `InterruptedIOException` on `op=poll` increments the `ConsecutiveRestFailures` counter (visible in `RestFallbackOrchestrator.kt` around the breaker_open log emission at line 2554). With every poll attempt timing out at the 35 s budget, the counter reaches the breaker threshold quickly and the cooldown ramp `5 → 10 → 20 → 40 → 80 → 120 s` enters the standard escalation. The cooldown ramp itself is doing what it was designed to do; the underlying failure (poll body never completing) is what drives the breaker — not a bug in the breaker policy.

### Hypothesis matrix after M-1 + M-5

| Hypothesis | Status | Evidence basis |
|---|---|---|
| H-OrchestratorCancellation | **REFUTED** | Fixed 35 s timeout (M-1) + M-5 source-read shows the 35 s value comes from OkHttp `callTimeout`, not an orchestrator interrupt. If WS-state-transition-driven cancellation were the cause, observed elapsed times would vary by 0-35 s, not cluster at the budget ceiling. |
| H-NetworkSideCancellation | **STRONGLY SUPPORTED — not final-confirmed without M-2** | 12 polls received `responseHeadersEnd status=200` from the relay, then partial body (up to `cumulative_bytes=3456` of ~4608 expected), then the remaining chunks never arrived. The shape matches the carry-forward `rc-direct-stability1.md` §13 T2 outcome ("any long-held connection on Tele2 LTE uplink is structurally untrustworthy") applied in the downlink direction (poll RESPONSE body vs the T2 POST request body). M-2 packet capture would confirm whether the missing chunks correspond to TCP RST / FIN / mid-stream cuts on the wire, but the corpus-side evidence is already strong enough that the recon may opt to close on M-1 + M-5 + the existing T2 reference rather than re-run on packets. |
| H-BreakerCooldownTooAggressive | **DEMOTED — not the root cause** | The breaker is correctly counting genuine REST failures. Even if the cooldown ramp were extended (say, `5 → 30 → 120 s` instead of `5 → 10 → 20 → 40 → 80 → 120 s`), every poll attempt would still time out at 35 s and the breaker would still escalate to ceiling. Cooldown policy is downstream of the root cause; tightening it would be a fix shape (out of scope under this recon's §3). |
| H-BreakerOpenBlocksInboundDispatch | **REFUTED** | M-5 source-read: WS deliver-in path runs in a `scope.launch` block that does not check breaker state. When the breaker is open, only `op=poll` skips; WS frames continue to flow through `wsTransport.incoming.collect { ... _incoming.emit(deliver) ... }` independent of REST orchestrator state. The K-2 inbound-delivery gap on Tecno is fully explained by the two independent failures (WS Mode 2 + REST poll timeouts), not by a coupling between breaker state and WS dispatch. |
| H-Tele2LTESpecific | **SUPPORTED** | K-1 (Tecno Wi-Fi, same master HEAD APK, same operator-workstation): 88-90 successful REST poll / send / inbound_deliver events, `relay_send_return ok=false = 0`. K-2 (Tecno Tele2 LTE, same APK): 0 successful poll body completions. The single-variable difference between K-1 and K-2 is the network class. Cross-reference: `rc-direct-stability1.md` §13 T2 outcome already pinned "long-held connection on Tele2 LTE uplink is structurally untrustworthy" at the byte-threshold class for POST bodies; K-2 surfaces the same family on downlink poll bodies. M-3 (different LTE carrier) would tighten "Tele2-specific" vs "any Russian-mobile LTE" but is not strictly necessary if the recon accepts the strong correlation with the existing T2 evidence. |
| H-OkHttpInterruptionRace | **REFUTED** | M-1 + M-5 show the `InterruptedIOException` is the OkHttp `callTimeout` firing at its budgeted ceiling — i.e. the normal mechanism for an overdue call. No dispatcher lifecycle race, no executor shutdown, no thread cancellation. The exception class name `InterruptedIOException` is OkHttp's stock surface for the `callTimeout` path. |

### Net architectural picture

The K-2 inbound-delivery gap on Tecno is caused by **two independent failures on the same network class**, both rooted below the application layer:

1. **Tele2 LTE long-held downlink connection drops body bytes** before the response completes — same family as `rc-direct-stability1.md` §13 T2 (uplink POST body) but in the downlink direction. OkHttp surfaces the resulting timeout as `InterruptedIOException` at the 35 s call-timeout ceiling.
2. **Tele2 LTE Direct WSS dies in Mode 2 pp0 rhythm** (0 successful ping/pongs per ~31 s session) — `rc-direct-stability1.md` §1 `F-Mode2-pp0` re-confirmed.

Neither failure is in the application code at master HEAD. The R3.6 breaker policy is correctly observing the REST failure pattern; the WS deliver-in path is correctly wired and would deliver if the WS sessions became alive. Both failures share a common cause: **the Tele2 LTE network actively breaks long-held connections in both directions**. This is consistent with the carry-forward "long-connection-uplink finding" from `rc-direct-stability1.md` §13.

### Disposition

The recon does NOT close on this pass — §6 Gate 2 (H-NetworkSideCancellation confirmed) requires M-2 packet capture for conclusive on-the-wire evidence. Two paths are equally available to the operator for the next step:

1. **Run M-2** (Tele2 LTE reproduction with `tcpdump` on Tecno via `adb shell` if available, or on an upstream router). Conclusively pin the missing-chunks behaviour to TCP-layer cuts. This is the strict-discipline path the §6 gate originally specified.
2. **Accept the strong corpus-side correlation with `rc-direct-stability1.md` §13 T2 outcome as substitute for M-2** and close the recon on M-1 + M-5 + the existing T2 reference. The K-2 downlink shape is mechanistically the same family as T2's uplink shape on the same network class; another packet capture would say the same thing.

If Option 2 is chosen, the recon closes with `H-NetworkSideCancellation supported via M-1 + M-5 + rc-direct-stability1.md §13 carry-forward` AND `H-Tele2LTESpecific supported via K-1 vs K-2 comparison`. Neither verdict resolves the parent `direct-wss-mode2-recon1.md` Phase B blocker on its own, because both findings push the failure below the application layer where this recon's facts-first scope does not propose fixes. The next-track design question (NOT scope here) becomes: does PR #330's quiescence contract have a defensible meaning on a network class where REST fallback cannot complete long-poll bodies, OR does Phase B re-scope to network classes where the underlying failure surface does not apply.

### Side-findings preserved (NOT scoped here)

- **Round 14 padded poll body size (~4608 bytes) sits close to the T2 cumulative-bytes threshold class observed in `rc-direct-stability1.md` §13.** On Tele2 LTE downlink the call gets through the first 75% of the body (3456 / 4608 bytes typical) before the remaining 1152 bytes never arrive. Could be coincidence; could be that the byte budget that pinned T2's POST surface also applies to long-poll responses. NOT a hypothesis under this recon; reference for any future scope-lock on the cumulative-bytes question.
- **The `effectiveCallMs` resolution at `AndroidNativeOkHttpRestFallbackTransport.kt:330` always equals `readTimeoutOverrideMs` when provided** — meaning when long-poll is enabled, `callTimeout` and `readTimeout` are coupled to the same 35 s budget. A future fix-track might consider decoupling these or extending only the call budget while keeping read budget short — but that is a fix shape and is out of scope here.

### Next-session pickup

If the operator schedules M-2, the next session designs the packet capture (`tcpdump` on Tecno via `adb shell tcpdump -i any 'host relay.phntm.pro and port 443' -w` if root is available, OR on an upstream router if not; relay-side `pcap` would also serve if the operator has VPS access). M-2's progress note appends another section to this file with the on-the-wire evidence.

If the operator chooses Option 2 (close on M-1 + M-5 + T2 correlation), the next session writes the closure verdict PR with the §6 acceptance-gate disposition mapped to "H-NetworkSideCancellation supported via corpus + carry-forward" AND a forward-pointer for the parent `direct-wss-mode2-recon1.md` Phase B / RC PR #330 work to consider the architectural question raised in §10 Disposition.
