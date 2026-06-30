# Track: QUIESCENCE-VALIDATION-MC-HALF-MINI-LOCK

**Type:** Implementation / methodology scope-lock for the MC half of QUIESCENCE-VALIDATION-METHODOLOGY-RECON1's H-ME verdict. NOT a code change. NOT a re-investigation of the H-ME verdict or PR #330's quiescence contract.
**Status:** Open (mini-lock); **§5 Strategy 1 locked 2026-06-30 — see §13**.
**Opened:** 2026-06-30 immediately after the L1 synthetic-trigger implementation (the MB half of H-ME) merged earlier the same day as PR #353 squash `ed3406eb`. The methodology recon's H-ME verdict (PR #349 squash `54f2e50d`) mandates BOTH the MB half (synthetic field trigger — DONE) AND the MC half (deterministic state-machine / integration validation — THIS mini-lock).
**Last known master at open:** PR #353 squash `ed3406eb`.
**Last known master at Strategy 1 amendment:** PR #354 squash `b9e979f2`.

---

## §1 Goal

Scope the MC half of the H-ME closure verdict: deterministic state-machine / integration validation that asserts PR #330's quiescence chain invariants directly, in a controlled non-field environment. The MC half completes the H-ME pair — MB asserts that the production dispatch path responds correctly when fed a synthetic Mode 2; MC asserts that the gate's internal state machine + the wiring between state machine, dispatcher, and detector behave per the PR #330 mini-lock contract regardless of input source.

Together, MC PASS + MB PASS satisfies the DIRECT-WSS-MODE2-RECON1 §11 B1 acceptance gate per L-13.3.1 (both halves required).

The mini-lock does NOT pick the implementation shape; it discriminates among candidate strategies and forwards the chosen strategy to a separate implementation PR.

## §2 Scope

- Bind the future MC implementation PR to the locks carried forward from the methodology recon's §13.3 closure verdict that apply to the MC half specifically (mostly L-13.3.1, L-13.3.3, L-13.3.4, L-13.3.5, L-13.3.11 — see §4 below).
- Enumerate the strategies for where the MC tests live (Strategy 1: extract gate-only carve-out PR from PR #330; Strategy 2: MC tests on PR #330's own branch; Strategy 3: MC after PR #330 merges). For each: feasibility, honesty profile, sequencing.
- Specify the six Phase B hypotheses from `direct-wss-mode2-recon1.md` §4 that MC MUST cover and the depth of coverage expected per hypothesis. MC closes the gate-mediated hypotheses that N-1 identified as NOT COVERED on master HEAD.
- Specify the acceptance gates for MC closure (what PASS / PARTIAL / FAIL look like).
- Specify the hand-off contract for the controlled Wi-Fi smoke run that opens AFTER MC PASS + MB landed.
- Be honest about what MC can and cannot prove. MC asserts model correctness on the gate's deterministic state machine; MC does NOT substitute for the field-shape match that MB provides.

## §3 Out of scope

- **Any code change.** This mini-lock is docs-only. The MC implementation is the next item.
- **MB half of H-ME.** Already DELIVERED (PR #353 squash `ed3406eb`). NOT re-scoped here.
- **PR #330's quiescence contract.** Locked by PR #330's own mini-lock + the methodology recon's H-ME verdict. NOT re-investigated.
- **The L1 synthetic-trigger surface.** Locked by `quiescence-validation-l1-synthetic-mini-lock.md` + the merged PR #353. NOT re-amended.
- **Re-investigation of Mode 2 root cause.** Parked in `rc-direct-stability1.md` / `rc-direct-ws-death1.md`.
- **N-4 / N-5.** Rejected by the methodology recon's closure verdict.
- **The controlled Wi-Fi smoke run.** That runs AFTER MC PASS + MB landed (MB IS landed). NOT this mini-lock's question; the smoke run is its own separate item.
- **B2 (Tele2 LTE delivery-blocker track).** Per `direct-wss-mode2-recon1.md` §11. Separate track; NOT this question.

## §4 Binding constraints carried forward from §13.3

The MC implementation PR is bound by the locks below from the methodology recon's closure verdict. Locks that govern only the MB half (L-13.3.2 / L-13.3.6 / L-13.3.7 / L-13.3.8 / L-13.3.9 / L-13.3.10) are NOT re-stated; they were satisfied by PR #353 and stay in force without re-binding here.

### §4.1 L-13.3.1 — Both halves required

MC PASS alone does NOT close B1. PR #353 delivered MB; this mini-lock's eventual implementation delivers MC. B1 closes only when both PASS verdicts are on record. The MC implementation PR's commit message + body MUST state explicitly that B1 closure also requires the MB PASS verdict (already landed but the closure record needs both citations).

### §4.2 L-13.3.3 — Sequential dispatcher order load-bearing

MC MUST include a structural test that asserts `WsSessionLifecycleDispatcher.dispatch(Ended)` consumes the state-machine consumer FIRST and the telemetry consumer SECOND. The test fails loudly if a future refactor flips order or fans out in parallel. The MB half's PR #353 did not add this test (its acceptance matrix relied on observed sequential order); MC closes the structural gap.

### §4.3 L-13.3.4 — `closeOrigin = "synthetic"` non-branching discipline

MC MUST include a grep-style negative-presence test that fails if any source file under `shared/core/transport` or `apps/android/src` adds an `if (...closeOrigin == "synthetic"...)` branch. The MB half's PR #353 implemented the synthetic event-emission discipline; MC enforces it at code-review time on every PR going forward.

### §4.4 L-13.3.5 — `maybeRetryBootstrap()` branch scope gap

MC MUST cover the `maybeRetryBootstrap()` alternative reconnect path explicitly OR document it as an out-of-scope acknowledgment. The synthetic trigger refuses to fire when `restCapabilityActiveProvider() == false` is implicit in the wrapper chain (AppContainer + `triggerDebugForceMode2`); MC's state-machine tests need to either (a) include a cell that drives the dispatcher into the `maybeRetryBootstrap()` arm and assert the gate observes a defensible posture, or (b) document the gap in the implementation PR body with an explicit deferral note.

### §4.5 L-13.3.11 — Acceptance matrix is test floor, not ceiling

The MC implementation PR's test set MUST include every applicable item from the source-grounded acceptance matrix at `C:\temp\quiescence-h-me-council-2026-06-29\tests.md`. "Applicable" means the items targeting the gate state-machine layer (transitions, single-probe-per-route-change, 60s probation, no self-reentry, no message loss in the quiescence window). Items that targeted the synthetic event emission are already covered by PR #353; MC does NOT re-implement them.

## §5 Strategy discrimination — where the MC tests live

The MC tests target the `WsReconnectGate` state machine and its wiring into the dispatcher + `RestStateMachine`. The gate component is introduced by PR #330 and does NOT exist on master HEAD. Three strategies exist for landing the MC tests:

### §5.1 Strategy 1 — Carve gate-only PR out of PR #330; MC tests stack on the landed gate

A new PR carves the `WsReconnectGate` source files out of PR #330 (gate state machine + the dispatcher / state-machine wiring + the existing gate-targeted unit tests PR #330 already authored: `WsReconnectGateTest` 1172 LOC, `RestFallbackOrchestratorQuiescenceWiringTest` 184 LOC, `KtorRelayTransportRunReconnectLoopTest` 480 LOC, `TransportRewalkCoordinatorTransactionTest` 667 LOC). The carve-out PR lands the gate code on master WITHOUT activating the quiescence contract (the activation BuildConfig flag stays release-pinned `"0"`). After carve-out merges, the MC implementation PR stacks on top, adds any remaining MC-specific tests, and produces the MC PASS verdict.

- **Honesty profile:** HIGH. Mirrors the path-2 step 2 narrowing carve-out pattern (PR #352 — the narrowing was carved out of PR #330's mini-lock §2 plan). Same risk profile: scoped surgical extraction of a non-activating-by-itself component.
- **Sequencing cost:** ~1-2 PRs (the carve-out + the MC implementation; the MC implementation may be tiny if PR #330's own gate tests cover most of the matrix).
- **Risk:** the carve-out itself needs an internal mini-lock to avoid drifting from PR #330's eventual integrated shape. Same shape as the path-2 step 2 narrowing PR — small, surgical, well-bounded.

### §5.2 Strategy 2 — MC tests added to PR #330's own branch; MC PASS = those tests PASS on PR #330's branch CI

The MC tests are written as commits ON PR #330's branch. PR #330's CI then runs them. MC PASS = PR #330's CI green on the new test commits.

- **Honesty profile:** MEDIUM. PR #330's CI runs the tests, so the assertion is structurally honest at the unit-test layer. But the validation is coupled to PR #330's eventual merge — MC PASS lives only on a branch that has not yet landed. If PR #330 changes between MC PASS and eventual merge, the MC verdict needs re-running.
- **Sequencing cost:** zero new PRs (commits go on PR #330's existing branch).
- **Risk:** couples MC to PR #330's mini-lock review cycle. If PR #330's review surfaces design changes, MC tests may need to be redone before merge. Also: PR #330 is currently Draft / HOLD specifically because B1 is open; landing MC inside PR #330 means MC PASS happens at the same moment PR #330 merges, which doesn't help operator confidence ahead of the merge decision.

### §5.3 Strategy 3 — MC after PR #330 merges; B1 conditionally closes on MB alone temporarily

Sequence: MB is already landed (PR #353). Run controlled Wi-Fi smoke. If smoke PASS, B1 closes conditionally on MB-only with an explicit "MC half deferred" deferral. PR #330 merges (operator decision). MC tests written on the post-PR-#330 master. MC PASS retroactively converts B1's conditional closure into full closure.

- **Honesty profile:** LOW. Violates L-13.3.1 ("both halves required") by allowing PR #330 to merge before MC PASS. Re-locking B1 closure as "MB-only conditional" weakens the H-ME verdict's central claim that both halves are required.
- **Sequencing cost:** zero immediate PRs; MC deferred.
- **Risk:** the methodology recon's H-ME verdict is the load-bearing reason path-2 was opened. Bypassing the "both halves required" lock is a re-litigation of the verdict, not an implementation choice. Strategy 3 should be available only as a documented fallback if Strategies 1 and 2 both fail feasibility, NOT as a default.

### §5.4 Recommendation NOT pre-decided here

This mini-lock enumerates the strategies and their honesty profiles. The implementation PR's operator decision is which strategy to pursue. Operator MAY pick a strategy explicitly OR ask for additional discrimination in a separate amendment to this mini-lock before any implementation PR opens. Strategy 1 is the natural mirror of path-2 step 2's pattern and likely cleanest; the mini-lock notes this without forcing the choice.

## §6 What MC must prove — load-bearing hypotheses

The MC implementation PR's test set MUST close the gate-mediated Phase B hypotheses N-1 identified as NOT COVERED on master HEAD:

- **H-330-Quiesces-Storm** — when Mode 2 is detected (synthetic or real), the gate's `Quiesced` state prevents the WS reconnect loop from issuing more than the contracted number of new connection attempts during the quiescence window. MC asserts the state-transition shape: gate enters `Quiesced` on the first qualifying Mode 2; subsequent reconnect attempts inside the window are refused; counter is exactly N (the contracted number).
- **H-330-Single-Probe-Per-RouteChange** — a single user-observable route change triggers exactly one recovery probe via the gate's `ProbeAvailable → ProbeClaimed` arbitration. MC asserts: one `NetworkChanged(clearsMode2Sticky=true)` event produces exactly one `ws_recovery_probe_granted` log line; multiple `NetworkChanged` events inside the same quiescence window produce exactly one probe each (or are coalesced per the gate's documented contract).
- **H-330-No-Self-Reentry** — the candidate WS dying within the probation window does NOT autonomously spawn a new socket without a new route-change event. MC asserts: gate observes `WsSessionEnded` for the candidate during `CandidateProving`; the gate-mediated counter for autonomous spawn attempts remains zero.

MC SHOULD reinforce the gate-coordinated aspects of the hypotheses N-1 identified as PARTIAL on master HEAD (the REST-side hypotheses are already test-covered for the REST state machine surface; MC adds the gate-coordinated layer):

- **H-330-Preserves-REST** — REST fallback delivery during the gate's `Quiesced` state. MC drives the gate into `Quiesced`, asserts the `RestStateMachine.WsActive → RestActive` transition is gate-aware, and asserts REST `inbound_deliver` events flow uninterrupted across the quiescence window.
- **H-330-Probe-Lives-60s** — the gate's `CandidateProving → Open` 60s alive-tick. MC asserts the gate-side timer fires at the documented numeric (`MODE_2_MIN_DURATION_MS` / `MODE_2_MAX_DURATION_MS` are REST-side; the gate's `CandidateProving` duration is the gate's own contract — MC verifies it against PR #330's gate source).
- **H-330-No-Message-Loss-Or-Dups** — across the gate-coordinated quiescence + recovery window. MC asserts the gate's interaction with `RestInboundDeduplicator` + the `KtorRelayTransportFifoTest` invariants holds across the window.

## §7 Acceptance gates

MC closure (handing off to the controlled Wi-Fi smoke step) opens when the matrix supports:

1. **MC PASS** — all six gate-mediated assertions in §6 hold; the structural assertion in §4.2 (sequential dispatcher order) PASSES; the negative-presence test in §4.3 (`closeOrigin` non-branching) PASSES; the `maybeRetryBootstrap()` disposition from §4.4 is documented; the test floor from §4.5 is included. Disposition: combined with the already-landed MB PASS, B1 acceptance gate from `direct-wss-mode2-recon1.md` §11 closes; the controlled Wi-Fi smoke can open as a separate operator-scheduled item.
2. **MC PARTIAL** — some §6 assertions hold; others surface honest model-level failures. Disposition: the recon closes with a verdict listing exactly which assertions failed; the implementation PR for the failing pieces is a separate scope-lock; PR #330 stays Draft / HOLD; B1 stays open.
3. **MC FAIL** — none or only one §6 assertion holds. Disposition: PR #330's gate design has a model-level defect that MC surfaced. The disposition is a re-design discussion, not a test-fix. The mini-lock closes inconclusive and the next step is operator-led on PR #330's mini-lock contract.

Two-or-more concurrent verdicts that contradict each other (e.g., MC PASS at the state-machine layer but MC PARTIAL at the wiring layer) closes the mini-lock as **inconclusive — escalate to Council** per WORKING_RULES.

## §8 Preconditions

The MC implementation PR may open only after BOTH:

- The chosen strategy from §5 is locked. If Strategy 1: the gate-only carve-out PR has merged on master. If Strategy 2: PR #330's branch is the active working branch and PR #330's operator-side mini-lock allows test additions. If Strategy 3: PR #330 has merged on master AND B1 has been conditionally closed under an explicit deferral amendment to `direct-wss-mode2-recon1.md` §11.
- The MB half is on record. (PR #353 squash `ed3406eb`; satisfied at this mini-lock's open.)

The mini-lock does NOT pre-empt the strategy choice. The implementation PR documents the chosen strategy + the precondition it satisfied.

## §9 Hand-off — MC implementation PR contract

The MC implementation PR opens ONLY after this mini-lock merges AND a strategy is chosen per §5 + §8.

The implementation PR:

- Cites this mini-lock's squash SHA in its body.
- Cites the methodology recon's H-ME verdict (PR #349 squash `54f2e50d`) + the MB half (PR #353 squash `ed3406eb`) in its body.
- States the chosen §5 strategy with rationale.
- Documents the §4.4 `maybeRetryBootstrap()` disposition (covered OR explicit deferral).
- Includes the §4.2 sequential-dispatcher-order test + the §4.3 negative-presence test.
- Adopts the §4.5 acceptance matrix as the test floor.
- Closes the §6 gate-mediated hypotheses with deterministic assertions.
- States explicitly that B1 closure now has both MC and MB verdicts on record per L-13.3.1.

After the implementation PR merges with MC PASS, the controlled Wi-Fi smoke run opens as a separate operator-scheduled item. After Wi-Fi smoke PASS: B1 acceptance gate closes; PR #330 may proceed to Ready / smoke retry per its own mini-lock.

## §10 Park conditions

This mini-lock parks (suspends ≥ 7 days, releases the lock on the implementation PR) under any of:

- **P-1 — All three §5 strategies fail feasibility.** The implementation cannot proceed cleanly. Disposition: the mini-lock parks with a verdict listing why each strategy failed; operator decides whether to weaken L-13.3.1 (re-open H-ME verdict) or to keep PR #330 Draft / HOLD indefinitely.
- **P-2 — Operator unavailable for further MC work.** Pragmatic park.
- **P-3 — PR #330's gate component design changes materially.** If a separate fix-track lands a different gate shape, the §6 hypotheses' assertion shapes become stale and need re-verification before any implementation PR opens.

## §11 What this mini-lock does NOT pre-decide

- The chosen §5 strategy. The implementation PR (or a separate amendment) picks.
- The exact test files / classes the MC implementation adds. The implementation PR designs.
- Whether MC's coverage of the PARTIAL hypotheses (§6 second list) is "thorough enough" beyond the test floor. The implementation PR's reviewer judges.
- The numeric values for the gate's contracted constants (the gate's own contract from PR #330's mini-lock is the source of truth; MC verifies against that source).
- Any change to PR #330's mini-lock or the H-ME verdict. Both stay locked.
- Any change to L1 mini-lock or MB half. Both stay locked (MB landed in PR #353).
- The controlled Wi-Fi smoke run procedure. That's the next mini-lock, opens after MC PASS.

## §12 Hand-off note

If this mini-lock merges and the operator does NOT immediately open the implementation PR: the next session reads §4 + §5 + §6 + §7 + §9 and opens either (a) an amendment to this mini-lock that picks a §5 strategy, OR (b) the implementation PR with a strategy choice documented in its body. Do NOT open the implementation PR with the §5 strategy ambiguous. Do NOT skip §6's gate-mediated hypotheses. Do NOT relax L-13.3.1 to allow PR #330 to merge before MC PASS without an explicit amendment.

If this mini-lock merges and the operator opens the implementation PR same-session: the implementation PR's commit message + body cite this mini-lock's squash SHA verbatim and document the chosen §5 strategy.

Do NOT propose changes to PR #330's contract from inside this mini-lock or the implementation PR. Do NOT amend the H-ME verdict. Do NOT skip the controlled Wi-Fi smoke step after MC PASS — the smoke is the final field-shape check before B1 closes.

## §13 Strategy 1 lock — gate-only carve-out from PR #330 (2026-06-30)

Operator-chosen §5 Strategy 1. The MC implementation now follows a two-PR sequence: (a) a gate-only carve-out PR that lands the `WsReconnectGate` source + targeted unit tests on master without activating the quiescence contract; (b) a follow-on MC implementation PR that stacks on the landed gate code and adds any remaining MC tests + the structural / negative-presence assertions required by §4.2 / §4.3.

Rationale for Strategy 1 over the alternatives is captured in the operator's review (recorded in PROJECT_LOG): the carved-out path is HIGH honesty profile, mirrors the path-2 step 2 narrowing carve-out pattern that already worked (PR #352 squash `a28bb1d2`), preserves L-13.3.1, and avoids coupling the MC verdict to PR #330's review lifecycle (which Strategy 2 would do) or weakening the "both halves required" lock (which Strategy 3 would do). Strategies 2 and 3 are NOT carried forward.

### §13.1 Gate-only carve-out scope

The gate-only carve-out PR extracts a deliberately-narrow subset of PR #330's diff. The implementation PR designs the file-level shape; this amendment pins what MUST be in and what MUST stay out.

**MUST be in the carve-out's initial diff:**

- **`shared/core/transport/src/commonMain/kotlin/phantom/core/transport/WsReconnectGate.kt`** — the gate state machine (`Open / Quiesced / ProbeAvailable / ProbeClaimed / CandidateProving`) plus its public API. The implementation PR may bring this file verbatim from PR #330's branch (commit `6f49cd89`) OR may re-author it; either way the carve-out's body MUST cite the source.
- **Targeted unit tests for the gate** — at minimum `WsReconnectGateTest.kt` (PR #330's 1172 LOC) brought across verbatim or re-authored against the carved gate. The full test file may be brought even if some assertions inside it depend on integration wiring that the carve-out chooses to NOT include — those assertions then either remain as `@Ignore` with a forward-pointer to the MC implementation PR or are extracted to the MC implementation PR's surface. The implementation PR decides per-cell which shape applies.
- **A new Android-side BuildConfig flag** — `RECONNECT_QUIESCENCE_ENABLED` defaulting to `"0"` in the release block (mirrors the existing `MODE_2_FAST_PATH_ENABLED` / `MODE_2_STICKY_ENABLED` / `DEBUG_FORCE_MODE_2_DETECTION` patterns); debug-block `localOrEnv("reconnectQuiesce", "RECONNECT_QUIESCENCE_ENABLED", "0")` so operator can opt in via `-PreconnectQuiesce=1`.
- **A companion release-pin test** — `ReconnectQuiescenceReleaseBuildConfigPinTest` (or equivalent name) in `androidUnitTest` mirroring `Mode2FastPathReleaseBuildConfigPinTest` / `Mode2DebugForceReleaseBuildConfigPinTest`. Three cells: release pin literal `"0"` present, debug block does NOT carry the literal pin, debug block declares the flag via `localOrEnv(...)` for the opt-in.
- **Minimal wiring stubs** sufficient to make the gate compileable and unit-testable in isolation. This means: any constructor parameters the gate exposes to its surrounding orchestrator code (e.g., a `reconnectQuiescenceEnabled: Boolean = false` constructor parameter, mirroring the L1 MB pattern) AND the AppContainer-side wire-up reading the BuildConfig and injecting the Boolean. The wire-up MUST default to `false` in release and MUST NOT change the runtime behaviour of any existing transport code path when the flag is `false`.
- **The narrowed ProGuard discipline** — if the carve-out introduces new `KtorRelayTransport` members that need to survive R8 (e.g., a gate-mediated accessor accessed by name from `HybridRelayTransport` via the concrete type), the carve-out PR extends the narrowed keep block at `apps/android/proguard-rules.pro` with justified entries AND extends `KtorRelayTransportProguardNarrowingPinTest` to assert the new member names are listed. If the carve-out's new members are reachable purely through `RelayTransport` interface dispatch, no keep changes are needed. The `verifyR8StripsTestSeams` Gradle task remains the runtime backstop.

**MUST NOT be in the carve-out's initial diff:**

- **Activation of the quiescence contract.** The `RECONNECT_QUIESCENCE_ENABLED` flag is release-pinned `"0"`; the constructor-injected Boolean defaults to `false` in release. Production transport behaviour is byte-identical to current master HEAD when the flag is off.
- **Runtime behaviour changes in unrelated transport paths.** The carve-out is structural: it lands the gate component as a compileable, unit-testable unit. It does NOT change the dispatcher's routing of real `WsSessionLifecycleEvent.Ended` events to the gate when the flag is off.
- **PR #330's full RestStateMachine / KtorRelayTransport / TransportRewalkCoordinator modifications.** The carve-out brings ONLY what the gate state machine needs to compile + be unit-tested in isolation. The larger production-behaviour rewrites from PR #330 stay on PR #330's branch and ship with the eventual full PR #330 merge.
- **Changes to PR #330's contract.** PR #330's mini-lock + RC-RECONNECT-QUIESCENCE1 contract are NOT amended by this carve-out. The gate component lands as the structurally same shape PR #330 intends; PR #330's full integration + activation are a separate concern that happens at PR #330's own eventual merge.
- **Synthetic-trigger code.** PR #353 already shipped the MB half. The carve-out does NOT re-touch any L1 synthetic-trigger surface.
- **MC test cells beyond the gate's own unit tests.** §4.2 sequential-dispatcher-order test, §4.3 negative-presence test on `closeOrigin`, and the §6 hypothesis assertions are the MC implementation PR's scope, NOT the carve-out's.

### §13.2 Sequencing

1. **Gate-only carve-out PR** opens. Initial diff matches §13.1. Verified locally: gate's unit tests PASS; `assembleRelease` + `verifyR8StripsTestSeams` PASS; production transport behaviour unchanged (because the BuildConfig flag is `"0"` in release).
2. **Gate-only carve-out PR merges on master.** The carve-out commit becomes the qualifying base for the MC implementation PR per §13's contract.
3. **MC implementation PR** opens, stacked on the landed gate code. Carries forward all §4 / §6 / §7 / §9 requirements; adopts §4.5 acceptance matrix as test floor; cites the gate-only carve-out's squash SHA verbatim in body.
4. **MC PASS verdict** on the MC implementation PR (per §7) combined with the already-landed MB PASS verdict (PR #353 squash `ed3406eb`) closes the B1 gate per L-13.3.1.
5. **Controlled Wi-Fi smoke run** opens as a separate operator-scheduled item AFTER MC PASS lands. Validates the end-to-end quiescence chain on a real device.
6. **PR #330** advances per its own mini-lock contract AFTER both halves PASS + Wi-Fi smoke PASS. The carve-out from §13.1 reduces PR #330's effective diff (the gate component is already on master), simplifying PR #330's eventual integration.

### §13.3 What this amendment does NOT change

- §1-§12 of this mini-lock stay in force.
- Strategy 2 and Strategy 3 from §5 are NOT carried forward (Strategy 1 explicitly chosen).
- The §4 binding constraints (L-13.3.1 / .3 / .4 / .5 / .11) remain binding on the MC implementation PR.
- The §6 load-bearing hypotheses remain binding on the MC implementation PR.
- The §7 acceptance gates remain unchanged.
- PR #330's contract is NOT amended. PR #353 (MB half) is NOT amended. The methodology recon's H-ME verdict is NOT amended.
- The L1 mini-lock + the L1 §5.1 Option A amendment + the path-2 step 2 narrowing PR stay in force.

### §13.4 Hand-off to the gate-only carve-out PR

The gate-only carve-out PR opens AFTER this amendment merges. The carve-out PR:

- Cites this amendment's squash SHA in its body.
- Cites PR #330's gate-source provenance (`6f49cd89:shared/core/transport/src/commonMain/kotlin/phantom/core/transport/WsReconnectGate.kt`).
- States the carve-out scope per §13.1 explicitly (what's in, what's not).
- Documents the BuildConfig flag wiring + release pin.
- Includes the companion release-pin test.
- Includes the narrowed ProGuard discipline extension (if any new keep entries are needed) + structural pin test updates.
- States explicitly that the carve-out is structural — production transport behaviour is byte-identical to master HEAD when the flag is `"0"`.
- States that PR #330's contract is NOT amended; that the MB half (PR #353) is NOT amended; that the L1 mini-lock + L1 §5.1 Option A amendment + path-2 step 2 narrowing PR stay in force.

After the carve-out PR merges, the MC implementation PR's preconditions per §8 are satisfied; the MC implementation PR opens as the next step.

Do NOT open the gate-only carve-out PR with the activation flag pinned to `"1"`. Do NOT skip the companion release-pin test. Do NOT touch any production transport runtime path when the flag is `"0"`. Do NOT pre-empt the MC implementation PR's scope — the §4 / §6 / §7 requirements live on the MC implementation PR, not on the carve-out.
