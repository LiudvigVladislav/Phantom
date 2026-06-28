# Track: QUIESCENCE-VALIDATION-METHODOLOGY-RECON1

**Type:** Facts-first reconnaissance / methodology design. NOT a code-fix track. NOT a re-investigation of PR #330's quiescence design or RC-RECONNECT-QUIESCENCE1 contract.
**Status:** Open (mini-lock).
**Opened:** 2026-06-28 after `direct-wss-mode2-recon1.md` §12 K-6 outcome surfaced a structural validation gap: Wi-Fi did not reproduce Mode 2 (so the quiescence chain never engaged) AND Tele2 LTE breaks the REST fallback substrate (per TELE2-LTE-REST-BREAKER-RECON1 §11 closure). Neither network class available to the operator can field-validate the core PR #330 mechanism.
**Last known master at open:** PR #344 squash `364a7a48`.

---

## §1 Goal

Discriminate among candidate methodologies for field-validating PR #330's quiescence chain (`sticky → quiesced → probe → ws_alive_60s recovery`) when:

- **Wi-Fi does not reproduce Mode 2** — K-1 and K-6 both captured `F-Mode1` rhythm on the same Wi-Fi network; the Mode 2 detector (PR #328 R3.6) targets the Mode 2 signature specifically and stays dormant on Mode 1 deaths.
- **Tele2 LTE breaks the REST fallback substrate** — per TELE2-LTE-REST-BREAKER-RECON1 §11 closure (PR #342 squash `4302f56d`), the long-poll body never completes on Tele2 LTE; the REST fallback substrate that quiescence relies on as a delivery vehicle is structurally broken on that network class.

The recon produces an **evidence-graded matrix** that answers: which methodology (or combination of methodologies) can close DIRECT-WSS-MODE2-RECON1 §11's B1 acceptance gate honestly — i.e., a finding that "the quiescence chain was actually exercised, observed, and behaves per the PR #330 mini-lock contract" rather than the §12 K-6 letter-met-not-spirit verdict.

The recon does NOT propose a methodology fix; it discriminates among candidates and forwards a chosen path back to DIRECT-WSS-MODE2-RECON1 for execution.

## §2 Scope

- Bound the methodological space: field-only / instrumentation-augmented field / synthetic in-app trigger / state-machine integration tests / multi-method combination.
- Map the existing infrastructure each methodology would lean on (existing tests in `commonTest`, existing debug-flag patterns, existing fake-transport plumbing) so that the cost / risk profile of each is grounded in what already ships on master, not in hypothetical refactors.
- For each methodology candidate, enumerate the architectural risk (false confidence vs the field) and the honesty constraints required for the methodology to count as actual validation rather than ceremony.
- Produce a recommendation that DIRECT-WSS-MODE2-RECON1 can pick up as its next deliverable — either a single methodology or a defensible multi-method combination.

## §3 Out of scope

- **Any code change.** This recon discriminates. If a chosen methodology requires new code (e.g., a synthetic-trigger debug flag), that is a separate scope-lock under the chosen methodology's mini-lock, NOT under this recon.
- **RC PR #330.** Stays Draft / HOLD throughout. No edits, no rebase, no force-push, no merge attempts.
- **Re-investigation of Direct WSS Mode 2 root cause.** Parked in `rc-direct-stability1.md` / `rc-direct-ws-death1.md`. Carry-forward facts (`F-Mode1`, `F-Mode2-pp0`, `F-Mode2-pp1`, `F-Mode2-cadence-invariant`) stay as input.
- **TELE2-LTE-REST-BREAKER-RECON1.** Closed (PR #342 `4302f56d`). Its §11 closure is carry-forward evidence; the recon's findings are not re-opened.
- **VPN-TRANSPORT-COMPAT-RECON1.** Separate parallel track. VPN OFF for any methodology execution that involves the field.
- **B2 (separate Tele2 LTE delivery-blocker track).** Per DIRECT-WSS-MODE2-RECON1 §11. NOT this recon's question; opens on its own when operator schedules it.
- **Re-defining PR #330's quiescence contract.** The mini-lock contract that PR #330's RC-RECONNECT-QUIESCENCE1 declares is the contract to be validated; this recon does NOT amend or weaken that contract.
- **Predictive remediation.** Specifically NOT pre-locked: extending B1's hypothesis set, weakening §11's release / rollout gate, dropping any of the six Phase B hypotheses, accepting "letter met / spirit not" verdicts as B1-PASS. Each is a methodology shortcut and is out of scope until evidence closes a gate.

## §4 Candidate methodologies (the matrix to discriminate)

Each methodology names a path through which the four NOT-EXERCISED Phase B hypotheses from `direct-wss-mode2-recon1.md` §12 could be honestly tested. Only one path (or a clean combination of paths) is expected to survive the matrix.

- **H-MA — Third network class that reproduces Mode 2 AND keeps REST alive.** Candidates the operator might access: a different LTE carrier (MTS, Megafon, Beeline; the M-3 instrument from TELE2-LTE-REST-BREAKER-RECON1 was never run, so other carriers' long-poll body-loss profile is unknown); tethered Wi-Fi through a mobile hotspot (the radio path may differ from direct LTE); a Wi-Fi network with degraded RSSI or packet loss profile (controlled environment such as RF shield room or shaped link). Discriminator: does any operator-accessible configuration both (i) trigger Mode 2 detection AND (ii) keep the REST fallback long-poll body completing reliably?
- **H-MB — Synthetic in-app trigger via debug-only flag.** A debug-only build flag that forces the Mode 2 detector into the "detected" state for testing (e.g., `DEBUG_FORCE_MODE_2_DETECTION="1"`). The flag would arm the sticky window and step the quiescence state machine through its expected transitions. Discriminator: can the flag be designed so the synthetic trigger exercises the SAME code path the field would exercise on a real Mode 2 episode, without becoming a parallel test-only code path that diverges from production?
- **H-MC — State-machine / integration validation against a fake transport.** Existing infrastructure: `RestStateMachineTest.kt`, `RestFallbackOrchestratorBreakerTest.kt`, `WsActivePollJobLifecycleTest.kt`, `RestFallbackOrchestratorPollLoopTest.kt` already script the orchestrator's transport interactions for the breaker / poll / WS-active flows. The fake transport pattern could be extended (or read for sufficiency) to drive the quiescence chain through Mode 2 detection → sticky_armed → ws_recovery_probe_granted → ws_alive_60s proof, asserting on the structured log events and the gate transitions. Discriminator: does the existing fake-transport infrastructure faithfully model the surface that the field presents, or does it script a model that diverges from the field in load-bearing ways?
- **H-MD — Combination MC + MA.** State-machine tests as the primary validation (gives reproducibility and asserts mechanism correctness on the model the design defines), supplemented by at least one field run on a network class where Mode 2 fires AND REST survives. The state-machine half closes "the model says quiescence is correct"; the field half closes "the field's model matches the design model".
- **H-ME — Combination MC + MB.** State-machine tests primary, synthetic in-app trigger as a secondary field-shaped check on a real device. Avoids the H-MA dependency on finding a third network class.
- **H-MF — Field-only escalation: accept B1 cannot close, re-scope §11's release / rollout gate to allow Wi-Fi-only ship for non-LTE-primary users.** Strictly speaking this is not a validation methodology but a release-gate amendment. Listed here only because it is the realistic alternative if H-MA through H-ME all turn out to be infeasible: instead of validating the mechanism, accept the validation gap and ship to the user population whose network class works.

H-MA / H-MB / H-MC / H-MD / H-ME are not mutually exclusive — the realistic outcome is likely a combination, and the recon's job is to identify which combination meets the honesty bar.

## §5 Diagnostic instruments

Each instrument is a research / design exercise. NO code change; NO field run from this recon. Instruments produce written evidence about feasibility / risk / cost of each H-Mx candidate.

- **N-1 — Existing-test inventory.** Catalogue the relevant `commonTest` / `androidUnitTest` files (`RestFallbackOrchestrator*Test.kt`, `RestStateMachineTest.kt`, `WsActivePollJobLifecycleTest.kt`, `BodyTimeoutContractTest.kt`, `LongPollOptInHeaderSeamTest.kt`, etc.) and map which Phase B hypotheses they already cover, partially cover, or do not cover at all. Output: a table showing per-hypothesis test-coverage status on master HEAD. Discriminates H-MC's feasibility — the more existing infrastructure covers, the lower the cost of MC.
- **N-2 — Fake-transport surface review.** Read the test-only fakes that the existing tests use (`FakeRelayTransport`, `RestFallbackTransport` test doubles, `BodyTimeoutTestTransport`). Assess whether they faithfully model the surface that PR #330's quiescence depends on (Mode 2 detector input signals, sticky window timing, recovery probe outcome). Output: per-fake assessment of "models the right surface" vs "models a simplified surface that may miss load-bearing field shapes". Discriminates H-MC's honesty bar.
- **N-3 — Synthetic-trigger debug-flag design exercise.** Sketch what a `DEBUG_FORCE_MODE_2_DETECTION` flag would look like: where it injects, what code path it touches, whether the resulting field run would exercise the SAME production path or a parallel test-only path. Output: a feasibility note + the load-bearing honesty constraint ("the synthetic trigger must hit the SAME code path the field hits"). Discriminates H-MB's risk profile.
- **N-4 — Third-network-class survey.** Enumerate the carriers / configurations the operator could plausibly access in the next 7 days without significant cost. For each: what is known about the carrier's Tele2-or-not long-poll body completion behaviour (e.g., from prior `rc-direct-stability1.md` references)? What is the field-test cost? Output: a per-candidate go / no-go table. Discriminates H-MA's feasibility.
- **N-5 — Release-gate review against PR #330's user population.** What is the user population's network-class distribution? If Tele2 LTE users dominate, the H-MF "Wi-Fi-only ship" path is product-unviable and H-MF is REFUTED on the product side. If the user population is mixed or LTE is minority, H-MF may be acceptable as an interim step. Output: a product-level note on whether H-MF is acceptable. Discriminates whether H-MF can be a closure for this recon.

The recon's natural instrument order is N-1 + N-2 first (cheapest, source-read only), then N-3 / N-4 / N-5 driven by what N-1 + N-2 surface.

## §6 Acceptance gates

The recon closes (handing off back to DIRECT-WSS-MODE2-RECON1 with a chosen methodology) when the matrix supports one of:

1. **H-MC sufficient.** N-1 + N-2 show existing state-machine / fake-transport infrastructure already covers (or with modest extension can cover) the four NOT-EXERCISED Phase B hypotheses, AND the fakes' surface faithfully models the field. Disposition: DIRECT-WSS-MODE2-RECON1 picks up state-machine validation as the next B1 deliverable; a separate execution PR adds the tests; B1 closes on test PASS.
2. **H-MD or H-ME (combination).** N-1 + N-2 show state-machine validation is necessary but not sufficient on its own (e.g., one or two hypotheses require field shape that the fakes cannot honestly model); a complementary field-side instrument (H-MA's third network class or H-MB's synthetic trigger) is required. Disposition: DIRECT-WSS-MODE2-RECON1 picks up the combination as the next B1 deliverable; the field component is a separate execution PR.
3. **H-MA only (improbable but possible).** A field-accessible third network class faithfully reproduces Mode 2 + keeps REST alive AND N-2 indicates state-machine fakes don't honestly cover the surface. Disposition: B1 closes on a field run on the identified network class; state-machine validation is not promoted.
4. **H-MB only (also improbable).** Synthetic in-app trigger is designed honestly enough that exercising the same code path the field would exercise is achievable, AND state-machine fakes don't cover the surface. Disposition: a separate scope-lock for the synthetic-flag code change, then a field run with the flag.
5. **H-MF — release-gate amendment.** N-5 shows the user population accommodates "Wi-Fi-only first" without significant LTE-user-exclusion harm. Disposition: separate docs PR amending DIRECT-WSS-MODE2-RECON1 §11's release / rollout gate; B1 is closed as "non-regression PASS" with the explicit limitation that LTE users do not receive PR #330 in this rollout; B2 stays as the gate for LTE rollout.

Two-or-more concurrent verdicts that contradict each other (e.g., MC and MA both look sufficient but disagree on the load-bearing surface) close the recon as **inconclusive — escalate to Council** per WORKING_RULES.

## §7 Park conditions

The recon parks (suspends work ≥ 7 days, releases the master lock for any other track) under any of:

- **P-1 — All methodologies fail their feasibility / honesty bar.** None of H-MA, H-MB, H-MC, H-MD, H-ME look like they can honestly close the four NOT-EXERCISED hypotheses within reasonable effort, AND H-MF is product-unviable per N-5. Disposition: the recon parks with a verdict "PR #330's core mechanism cannot be honestly field-validated under current constraints; the operator decides whether to ship without validation, redesign the contract, or shelve PR #330."
- **P-2 — Operator unavailable for further methodology design work.** Pragmatic park; the recon captures what it has and hands off.
- **P-3 — A separate fix-track lands that materially changes the substrate (e.g., a new transport for LTE) AND removes the validation question by changing the network classes in scope.** Disposition: the recon parks because the question it answers no longer applies on the current substrate.

## §8 Out-of-scope explicitly NOT promoted to invariants

- Direct WSS Mode 2 root cause (parked in `rc-direct-stability1.md` / `rc-direct-ws-death1.md`).
- TELE2-LTE-REST-BREAKER-RECON1 (closed).
- DIRECT-WSS-MODE2-RECON1's K-3 / Phase B / RC PR #330 status — this recon does not move them.
- VPN-TRANSPORT-COMPAT-RECON1, REST-SEND-CONNECTIVITY-RECON1.
- Trek 2, DWS-UX, R3.6, Voice, Calls, Reality, Tor.
- Any code change in this recon.
- Any pre-locked methodology choice.
- Any product-side roadmap claims about PR #330's ship timeline.

## §9 Hand-off note

If this session ends before N-1 runs: the next session reads §1 + §4 + §5 and runs N-1 (existing-test inventory). N-1's output is a docs-only progress comment / PR amendment on this track; do NOT propose a chosen methodology in the N-1 progress note.

If this session ends after N-1 but before N-2: the natural next instrument is N-2 (fake-transport surface review). If N-1 already shows the existing tests have severely-thin coverage of the four NOT-EXERCISED hypotheses, N-3 / N-4 / N-5 may be promoted earlier than the natural order.

Do NOT propose a methodology in any progress note. Do NOT touch PR #330. Do NOT amend §11's release / rollout gate from inside this recon — H-MF's verdict, if it lands, is a separate amendment PR on `direct-wss-mode2-recon1.md`, NOT a side-effect of closure here.

## §10 N-1 progress — existing-test inventory on master HEAD (2026-06-29)

Operator-greenlit after PR #345 squash `aab66577`. Source-read only; no operator devices touched; PR #330 untouched. Scope per §5: catalogue tests on master HEAD that touch the surface PR #330's quiescence chain depends on, then map per-hypothesis coverage. NOT a methodology recommendation per §9 hand-off rule.

### §10.1 Load-bearing component placement (master HEAD vs PR #330)

The quiescence chain `sticky → quiesced → probe → ws_alive_60s recovery` is implemented in PR #330 across the following components. The split between "on master HEAD" and "introduced by PR #330" is the load-bearing input for the per-hypothesis matrix below.

| Component | On master HEAD? | PR #330 delta |
|---|---|---|
| `WsReconnectGate.kt` (gate state machine: `Open / Quiesced / ProbeAvailable / ProbeClaimed / CandidateProving`) | **ABSENT** | NEW file (+447 LOC) |
| `TransportRewalkCoordinator.kt` | Present | Modified (+369 LOC) |
| `RestStateMachine.kt` (REST-side `WsActive / RestActive / WsCandidate`) | Present (Trek 2 Stage 2B-A / 2B-B) | Modified (+1046 LOC) |
| `RestFallbackOrchestrator.kt` | Present | Modified (+47 LOC) |
| `KtorRelayTransport.kt` | Present | Modified (+959 LOC) |
| `HybridRelayTransport.kt` | Present | Modified (+39 LOC) |
| `WsSessionLifecycleDispatcher.kt` | Present (R3.6 IMPL-LOCK #3 / #4) | Modified (+5 LOC) |
| Mode 2 detection (`WsDegradationDetector`, `PingTimeoutTextParser`) | Present (PR-WS-HEALTH-STATE1 Commit 3.2a) | Untouched |
| Build-config pins for `MODE_2_FAST_PATH_ENABLED`, `MODE_2_STICKY_ENABLED` | Present | `RECONNECT_QUIESCENCE_ENABLED` pin NEW |

The gate state machine (`Open / Quiesced / ProbeAvailable / ProbeClaimed / CandidateProving`) is the load-bearing implementation of four of the six Phase B hypotheses, and it does not exist on master HEAD.

### §10.2 Test files inventoried on master HEAD (shared transport common-test + android unit-test)

| Test file | LOC | `@Test` count | Surface |
|---|---|---|---|
| `RestStateMachineTest.kt` | 1273 | 67 | Pure-logic state machine `WsActive ↔ RestActive ↔ WsCandidate`; counters, 60s alive-tick, route-change resets, outbound-ack short-circuits |
| `RestFallbackOrchestratorBreakerTest.kt` | 3040 | 41 | Trek 2 Stage 2B-B C5 — L8 410 reauth dance, L9 circuit breaker, D11 hard timer caps, typed `Retry-After` plumbing |
| `RestFallbackOrchestratorVerifyAndPostureTest.kt` | 2481 | 33 | Trek 2 Stage 2B-B C4 — per-envelope verify-and-emit gate, L2 verify-key state machine, L7 bad-MAC posture |
| `RestFallbackOrchestratorC6Test.kt` | 1222 | 22 | Trek 2 Stage 2B-B C6 — L10 jitter (`Csprng`), M-B19 fail-closed inputs |
| `AckInboundAndAdvanceCursorTest.kt` | 1012 | 16 | Trek 2 Stage 2B-B C3 — cursor advance gate, M11 / M-B20 / M-B26 / M-B27 / M-B29 cells |
| `RestFallbackOrchestratorPollLoopTest.kt` | 781 | 5 | Trek 2 Stage 2B-B C3 — both REST poll loops concurrent (legacy `pollLoop` + `wsActivePollLoop`), cursor read-failure backoff |
| `PreKeyPublishReliabilityTest.kt` | 642 | 14 | PR-R0 / PR-R0.1 — mutex debounce, retry on `SocketTimeoutException`, 422 / 408 / 400 outcome classes, exponential backoff |
| `BodyTimeoutContractTest.kt` | 523 | 6 | Round 12 Decision B — REST poll body-timeout-after-headers preserves cursor, suppresses ack, accounts toward breaker |
| `WsActivePollJobLifecycleTest.kt` | 554 | 11 | Trek 2 Stage 2B-A B3 — parallel `wsActivePollJob` lifecycle (M3 / M5 / M6 / M7) |
| `RestFallbackOrchestratorTest.kt` | 558 | 17 | Integration — capability gate, oversize-body refuse, idempotency-key stability, retry-safe 401 mid-flight, 200-replay dedup |
| `KtorRelayTransportPendingOutboundTest.kt` | 426 | 13 | PR-D1c — `snapshotPendingOutbound` union + dedup + sequenceTs sort, `markPendingOutboundAcceptedByFallback` atomic removal |
| `WsDegradationDetectorTest.kt` | 412 | 18 | PR-WS-HEALTH-STATE1 Commit 3.2a Gates 3-7 — verdict shape, rising-edge log, Direct-only suspect lock, WsCandidate gate |
| `ProbeTraceTransportManagerTest.kt` | 388 | 10 | `TransportManager.connect` `PROBE_TRACE` log lines at every phase of chain walk |
| `LongPollReadTimeoutGateTest.kt` | 283 | 15 | Trek 2 Stage 2B-A B2 — `computeLongPollReadTimeoutMs` 9-cell matrix |
| `KtorRelayTransportFifoTest.kt` | 258 | 5 | PR-H2a — strict-FIFO `sequenceTs` ordering across live-send → ack-pending → outbox-requeue |
| `RestInboundDeduplicatorTest.kt` | 151 | 9 | PR-D1b — `Emit / SkipNoAck / ReAck` action discipline, FIFO eviction at capacity, expiry |
| `PingTimeoutTextParserTest.kt` | 122 | 13 | PR-WS-HEALTH-STATE1 Commit 3.3 — OkHttp `ping/pongs` phrase regex parser |
| `LongPollOptInHeaderSeamTest.kt` | 76 | 2 | Trek 2 Stage 2B-A B1 M1 — `longPollEnabled` seam pass-through + constants pin |
| `WsLifecycleDispatchBehaviourTest.kt` (android) | 233 | 8 | R3.6 IMPL-LOCK #3 / #4 — production dispatcher behaviour (bootstrap retry vs state event, detector call counts, supervision body) |
| `WsLifecycleCollectorSideEffectsTest.kt` (android) | 108 | 3 | R3.6 — end-to-end `Ended → toLegacyEndedEvent → feedDegradationDetector` on real `WsDegradationDetector` |
| `WsSessionLifecycleContractTest.kt` (android) | 159 | 3 | R3.6 — L5 / L6 / L7 lifecycle channel ordering, burst capacity, in-order consumption |
| `WsDegradationCollectorBindingsTest.kt` (android) | 261 | 7 | PR-WS-HEALTH-STATE1 Gate 6b — collector → detector mapping helpers |
| `HybridRelayTransportMapperTest.kt` (android) | — | — | R3.6 — `WsSessionEndedEvent` ↔ `RestStateMachineEvent` legacy + L8 / L9 adapters |
| `Mode2FastPathReleaseBuildConfigPinTest.kt` (android) | 169 | 3 | 3.6 — release-pin literal `"0"` for `MODE_2_FAST_PATH_ENABLED` |
| `Mode2StickyReleaseBuildConfigPinTest.kt` (android) | 116 | 3 | R3.6 — release-pin literal `"0"` for `MODE_2_STICKY_ENABLED` |
| `ConnectionUiStateTest.kt` (android) | 158 | 15 | PR-WS-HEALTH-STATE1 Commit 3.1 Gate 7 — `deriveConnectionUiState` 7-row priority table |

Out-of-scope from this inventory (not part of the quiescence surface): `KtorMediaUploadTransportTest`, `RestMediaAuthTokenProviderTest`, `TransportCapabilitiesResolverTest`, `TransportManagerTest` (general transport pick logic; the probe-trace variant is covered above), `SeqMacVerifierTest`, `VerifyKeyStateMachineTest`, `EnvelopeIdTest`, `RelayMessageSerializationTest`, `AuthSessionResponsePollHoldSecsTest`, `PreKeyApiClientTest`, `PreKeyPublishResnapshotTest`, `CallManagerGuardTest`, `RcDirectArmGTest`, `TransportNameForOverlayTest`, `AppContainer*WiringTest`, `LongPollOptIn*Test` (android wire), `LongPollV2ReleaseBuildConfigPinTest`, `PollSkipLpAndPpReleaseBuildConfigPinTest`, `S6*Test`, `Tele2RunbookPermissionLiteralContractTest`, `ConnectionBannerErrorLabelTest`.

### §10.3 Per-hypothesis coverage matrix (master HEAD)

| Phase B hypothesis | Coverage on master HEAD | Closest existing tests | Gap |
|---|---|---|---|
| **H-330-Quiesces-Storm** — when Mode 2 detected, sticky-quiescence path prevents the WS reconnect loop from issuing more than the contracted number of new connection attempts during the quiescence window | **NOT COVERED** | Mode 2 detection itself: `WsDegradationDetectorTest`, `PingTimeoutTextParserTest`. Reconnect-loop unguarded behaviour: `KtorRelayTransportFifoTest`. NONE of the gate's suppression behaviour exists on master | The gate (`WsReconnectGate.Quiesced` state) is the load-bearing suppressor; absent on master |
| **H-330-Preserves-REST** — REST fallback delivery is uninterrupted across the quiescence window | **PARTIAL** | REST fallback delivery during `RestActive` is exhaustively covered: `RestFallbackOrchestratorBreakerTest` (41 tests, breaker + reauth + retry-after), `RestFallbackOrchestratorPollLoopTest` (two concurrent poll loops), `AckInboundAndAdvanceCursorTest` (cursor advance gate), `BodyTimeoutContractTest` (cursor preservation on body timeout), `RestStateMachineTest` (RestActive transition) | The "across quiescence window" window itself is gate-mediated and not exercised on master — REST-fallback delivery during a `Quiesced` gate state is structurally untestable on master HEAD because `Quiesced` does not exist there |
| **H-330-Single-Probe-Per-RouteChange** — a single user-observable route change triggers exactly one recovery probe | **NOT COVERED** | `RestStateMachineTest` covers `NetworkChanged` resets in `WsActive` and `RestActive → WsCandidate` on `NetworkChanged`; this is the REST-side state-machine response. The gate's `ProbeAvailable / ProbeClaimed` single-probe semantic is gate-mediated | The gate's probe-claim arbitration (the actual single-probe-per-route-change implementation) is absent on master |
| **H-330-Probe-Lives-60s** — successful recovery probe earns the `ws_alive_60s` proof; unsuccessful one does not promote the gate | **PARTIAL** | `RestStateMachineTest` covers `WsCandidate → WsActive` on alive-tick after 60s AND outbound-ack received; this is the REST-side commit timer. `WsActivePollJobLifecycleTest` covers `wsActivePollJob` lifecycle but not gate promotion | The 60s timer pattern IS tested on master in REST-state-machine form; the gate-side `CandidateProving → Open` promotion is a different control-structure invocation of the same numeric and is absent on master |
| **H-330-No-Self-Reentry** — candidate WS dying within the probation window does NOT autonomously spawn a new socket without a new route-change event | **NOT COVERED** | The "no self-reentry" invariant does not exist on master; existing reconnect loop in `KtorRelayTransport` reconnects unconditionally on disconnect. `RestStateMachineTest` `WsCandidate → RestActive on WsSessionEnded (regression)` is the closest related semantic but covers REST-side regression, not the gate's no-self-reentry constraint | The constraint itself is introduced by PR #330; nothing on master constrains the reconnect loop in this way |
| **H-330-No-Message-Loss-Or-Dups** — no messages lost or duplicated across the quiescence + recovery window | **PARTIAL** | End-to-end loss / dup invariants are well-covered on master: `KtorRelayTransportFifoTest` (5 tests, sequenceTs ordering across the live-send → ack-pending → outbox-requeue transition), `KtorRelayTransportPendingOutboundTest` (13 tests, snapshot/mark API for WS → REST migration), `RestInboundDeduplicatorTest` (9 tests, ACK-after-persistence dedup), `AckInboundAndAdvanceCursorTest` (16 tests, cursor advance gate), `BodyTimeoutContractTest` (cursor preservation) | The invariants are covered for the WS↔REST mode-switch shape that master implements; the SPECIFIC quiescence-window + probe-recovery flow is gate-mediated and not exercised on master HEAD |

Summary count: **0 of 6 FULL-COVERED on master HEAD. 3 of 6 PARTIAL (Preserves-REST, Probe-Lives-60s, No-Message-Loss-Or-Dups — related invariants covered, gate-coordinated flow not). 3 of 6 NOT-COVERED (Quiesces-Storm, Single-Probe-Per-RouteChange, No-Self-Reentry — the load-bearing gate component is absent on master HEAD).**

### §10.4 What N-1 does NOT decide

- N-1 does NOT decide H-MC's feasibility. The §5 framing is "discriminates H-MC's feasibility — the more existing infrastructure covers, the lower the cost of MC." The N-1 result establishes that master HEAD covers no hypothesis fully and three only partially. Whether the PARTIAL coverage is structurally sufficient (e.g., the 60s alive-tick semantic IS tested in `RestStateMachineTest`, and the gate's `CandidateProving → Open` is a different control-structure invocation of the same numeric) is a fake-surface honesty question, not a master-HEAD-inventory question. That is N-2's job.
- N-1 does NOT decide whether PR #330's own added tests cover the gap. PR #330 adds eight test files (`WsReconnectGateTest.kt` 1172 LOC / 0 @Test count not extracted, `HybridRelayTransportIntegrationTest20.kt` 686 LOC, `TransportRewalkCoordinatorTransactionTest.kt` 667 LOC, `KtorRelayTransportDisconnectAndJoinTest.kt` 848 LOC, `KtorRelayTransportRunReconnectLoopTest.kt` 480 LOC, `RestFallbackOrchestratorQuiescenceWiringTest.kt` 184 LOC, `ReconnectQuiescenceReleaseBuildConfigPinTest.kt` 117 LOC, plus `KtorRelayTransportInternalTestSeams.kt` 153 LOC) but those exist on PR #330's branch only. Whether they honestly cover the six hypotheses at the state-machine / integration level is a question about PR #330's own CI — it is OUT OF SCOPE of this recon's N-1 instrument by the §5 framing "master HEAD". A separate question, not opened here.
- N-1 does NOT propose H-MC, H-MD, H-ME, or any other methodology. Per §9 hand-off rule.
- N-1 does NOT propose extending any test on master HEAD. Per §3 "any code change" out-of-scope.

### §10.5 Hand-off to N-2

The natural next instrument per §5 is N-2 — fake-transport surface review. N-2 reads the test-only fakes that the existing tests use (`FakeRelayTransport`, `RestFallbackTransport` test doubles, `BodyTimeoutTestTransport`, the `FakePreKeyPublishHttpTransport` from PR-R0.1, etc.) and assesses whether they faithfully model the surface that PR #330's quiescence depends on (Mode 2 detector input signals, sticky window timing, recovery probe outcome). Output: per-fake assessment of "models the right surface" vs "models a simplified surface that may miss load-bearing field shapes."

N-2 is operator-scheduled. Do NOT auto-start.

If the operator chooses to promote a different instrument first (e.g., N-3 synthetic-trigger design exercise, given the structural result that the gate's load-bearing surface is entirely PR #330-introduced), the §5 phrasing "natural instrument order" does not foreclose that choice.

Do NOT promote any methodology in any future N-x progress note. Per §9.
