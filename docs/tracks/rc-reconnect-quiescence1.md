# RC-RECONNECT-QUIESCENCE1 — typed gate + rewalk transaction (mini-lock)

> **Retrospective backfill (2026-06-23).** WORKING_RULES rule 3 says a
> mini-lock is committed to `master` BEFORE the feature branch opens.
> This track's feature branch (`canary/reconnect-quiescence`,
> draft PR #330) opened before this mini-lock landed. That is a
> deliberate process slip on this track — the work proceeded without
> the documented contract that rule 3 exists to enforce.
>
> This file is committed in a separate docs-layer PR to repair the
> trail. The mini-lock content reflects the actual goal, scope, and
> acceptance gate the work HAS converged on by the time this backfill
> lands, not a sanitised plan written after the fact. Sections marked
> `(retrospective note)` flag where the work diverged from what a
> Day-0 mini-lock would have committed to, and why.
>
> The branch is held in draft until this mini-lock is merged to
> `master`, then rebased forward onto the new master. After this point
> the rule-3 contract is in effect for any further work on this track.

---

## §1 — Goal

Stop the Tele2 LTE reconnect-loop storms observed when R3.6 sticky-per-route Fast REST degradation was enabled during the Tele2 field validation. (R3.6 was MERGED in PR #328 but the release flags `MODE_2_FAST_PATH_ENABLED` and `MODE_2_STICKY_ENABLED` stay literal `"0"` post-merge — R3.6 is INACTIVE on production builds and the storms were observed under the debug-flag-enabled field run, not under release defaults.) The storms manifest as ~32 second cycles of WS dial → handshake → silent-drop → dial — burning radio, draining battery, and producing the operator-visible "transport thrashing" log spam. R3.6 sticky-per-route DID protect message delivery during these storms (the user-visible payload flowed via the REST safety-net while the WS loop spun in parallel), so this is a battery / radio / log-noise track, not a "messages stopped arriving" track. After this work:

- Mode 2 silent-drop sessions cleanly transition the transport to REST without the WS reconnect loop ever re-engaging until a route change. The REST delivery path R3.6 already provides is unaffected; the reconnect storm next to it is what this track removes.
- A route change with `clearsMode2Sticky = true` runs a single typed rewalk transaction. The coordinator's first step, `beginRouteChange`, returns one of two outcomes depending on the gate state at that moment:
  - **Gate was non-`Open`** (i.e., `Quiesced` / `ProbeAvailable` / `ProbeClaimed` / `CandidateProving`): `beginRouteChange` returns `StickyRecovery`. The coordinator drains the dying session, issues a single-use recovery probe (`ProbeAvailable`), and the reconnect-loop side atomically claims it (`ProbeClaimed`). On the recovery WS handshake the gate consumes `ProbeClaimed → CandidateProving` immediately (owner-generation + route-epoch match required, single-lock atomic transaction). The gate then sits in `CandidateProving` for a 60-second in-flight probation window; only on `ws_alive_60s` survival (the existing R3.6 probation telemetry signal) does the gate promote to `Open` and the mode promote to `WsActive`.
  - **Gate was already `Open`** (no sticky engaged, ordinary reconnect scenario): `beginRouteChange` returns `OpenReconnect`. The coordinator drains the dying session and `requestServiceRestart` dials normally — no probe is issued, no `ProbeClaimed` claim happens, the gate stays `Open` throughout, and `CandidateProving` is not entered. The recovery-side machinery (`awaitReconnectPermit` returns an `OpenPermit` rather than a `ClaimedProbe`, `validatePermitAfterAuth` re-checks against the live route-epoch and connection-generation counters, no probe-budget bookkeeping) all runs on the same reconnect-loop side, but the path through it is the Open-gate ordinary-dial path, not the sticky-recovery probe path.
- The mechanism is opt-in behind a release-pinned BuildConfig flag. Production behaviour is unchanged at this PR's merge; a separate rollout PR flips the flag.

---

## §2 — Scope

This track changes the following surface:

- **`shared/core/transport/WsReconnectGate.kt`** — declares the typed `sealed interface WsReconnectGate` (`Open` / `Quiesced` / `ProbeAvailable` / `ProbeClaimed` / `CandidateProving`), the typed `WsReconnectPermit` (`OpenPermit` / `ClaimedProbe` / `LoopRetired`), the typed `RouteChangeOutcome` (`OpenReconnect` / `StickyRecovery` / `QuiescencePreserved`), the typed `ProbeIssueResult` (`ProbeIssued` / `Rejected`), the two facade interfaces `WsReconnectGateProvider` (reconnect-loop side) and `RewalkCoordinatorGateProvider` (coordinator side), and the `simpleKind()` redacting telemetry helper.
- **`shared/core/transport/RestStateMachine.kt`** — implements both facade interfaces with a single-lock atomic gate surface (`gateLock`) and the gate primitives: `allocateConnectionGeneration`, `awaitReconnectPermit`, `validatePermitAfterAuth`, `recordProbeAttemptFailed`, `beginRouteChange`, `issueProbeAfterRewalk`, `revokeRouteChange`, `revokeProbe`. The implementation honours owner-generation matching, generation-floor consultation, and bypass-locked `WsSessionConnected` consumption.
- **`shared/core/transport/KtorRelayTransport.kt`** — `runReconnectLoop` consults the gate via `WsReconnectGateProvider`. Adds a `disconnectAndJoin` lifecycle API with a critical NonCancellable teardown region.
- **`apps/android/src/androidMain/kotlin/phantom/android/transport/TransportRewalkCoordinator.kt`** — the new typed transaction whose canonical six-step shape is `beginRouteChange → submitNetworkChangedEvent → disconnectAndJoin → releaseTransport → issueProbeAfterRewalk → requestServiceRestart`, with typed `RouteChangeOutcome` (`OpenReconnect` / `StickyRecovery` / `QuiescencePreserved`) and a `lastRewalkAtMs` budget invariant (only advanced by a successful rewalk; a failed teardown does not eat the budget). All three branches start with step 1 (`beginRouteChange`), which is what returns the typed outcome and decides what happens next: **`StickyRecovery`** runs all six steps in order; **`OpenReconnect`** runs steps 2–4 then jumps directly to step 6 (`requestServiceRestart`), skipping step 5 (`issueProbeAfterRewalk`) because the gate is `Open` and no sticky-recovery probe is needed; **`QuiescencePreserved`** logs `NETWORK_TRACE rewalk_quiescence_preserved` and returns via `return@withLock` immediately after step 1, skipping the remaining FIVE substeps — sticky window stays armed, `lastRewalkAtMs` is NOT bumped, sticky preferences are NOT cleared, and `requestServiceRestart` is NOT called in this branch. Only `OpenReconnect` and `StickyRecovery` reach `requestServiceRestart`. On both branches the post-coordinator work (the reconnect-loop body) runs in `KtorRelayTransport.runReconnectLoop` and (for the gate transitions it observes) `RestStateMachine.onWsSessionConnected`, NOT inside the coordinator. But the path through that work differs between the two branches:

  - On **`StickyRecovery`**: the reconnect-loop's `awaitReconnectPermit` atomically claims the `ProbeAvailable` issued by step 5 (`ProbeAvailable → ProbeClaimed`); the recovery WS handshake fires; the gate's owner-validated single-lock transaction in `onWsSessionConnected` consumes `ProbeClaimed → CandidateProving`; after 60 seconds of `ws_alive_60s` survival the gate promotes to `Open` and the mode promotes to `WsActive`. This is the path that requires the probe-budget bookkeeping (`recordProbeAttemptFailed`).
  - On **`OpenReconnect`**: the gate is `Open` throughout (no probe was ever issued by step 5 because that step was skipped); `awaitReconnectPermit` returns an `OpenPermit` rather than a `ClaimedProbe`; `validatePermitAfterAuth` re-checks against the live route-epoch and connection-generation counters; `ProbeClaimed`, `CandidateProving`, and the probe-budget bookkeeping are NOT entered. The loop dials the ordinary Open path.
- **`apps/android/transport/HybridRelayTransport.kt`** — migration arming hook for the typed gate outcome.
- **`apps/android/di/AppContainer.kt`** — wires `currentKindProvider` from `TransportManager.state` and `tokenSource` from `LibsodiumCsprng.uniformLong`; threads `BuildConfig.RECONNECT_QUIESCENCE_ENABLED` through `RestFallbackOrchestrator` → `RestStateMachine`.
- **`apps/android/build.gradle.kts`** — adds the `RECONNECT_QUIESCENCE_ENABLED` BuildConfig field, pinned to `"0"` in the release block.
- **Tests** — full integration `HybridRelayTransportIntegrationTest20` driving the locked Mode 2 → migration → recovery lifecycle through real production code; deterministic flush-cancellation anchor in `KtorRelayTransportDisconnectAndJoinTest`; strengthened `WsReconnectGateTest` residual-count test; strengthened `TransportRewalkCoordinatorTransactionTest` NonCancellable-load-bearing test.
- **`apps/android/proguard-rules.pro`** — drops the `-keep class phantom.core.transport.KtorRelayTransport { *; }` wildcard so R8 strips the `*ForTest` seams from the release APK. A new `verifyR8StripsTestSeams` Gradle task wired as `finalizedBy` on `assembleRelease` fails the release build by name if any `phantom.*` class block in `mapping.txt` lists a `*ForTest` member that survived.

---

## §3 — Out of scope

Deliberately not touched in this track even if tempting:

- **No calls / voice / TURN / Reality / Tor changes.** ADR-025 calls signaling, ADR-016 Reality (Xray) wire-up, and the Tor onion transport stay as they are. This track is messaging-WS-realtime only.
- **No production-default actuation flip.** All three related flags (`MODE_2_FAST_PATH_ENABLED`, `MODE_2_STICKY_ENABLED`, `RECONNECT_QUIESCENCE_ENABLED`) stay `"0"` in the release block at this PR's merge. A separate rollout PR flips them.
- **No widening of the test-seam visibility model.** `*ForTest` members on production transport classes stay `internal` at the Kotlin source level. Integration tests in `apps:android:androidUnitTest` (a different Gradle module) reach them via a reflection bridge confined to `androidUnitTest`, NOT by promoting the seams to public API.
- **No real `runReconnectLoop` integration test that pins line-1287 invocation.** Test 20 drives the shared `runReconnectMergeAndFlush` private helper that both the production loop and the test seam call. A regression that removes the line-1287 call site from `runReconnectLoop` itself would leave Test 20 green; that gap is acknowledged as a known limit and deferred to a follow-up fixture-track (Ktor MockEngine WS or embedded loopback server). See §6.
- **No full audit of all `runBlocking { ... }` inside `runTest` helper usages.** The CI hang on `BodyTimeoutContractTest > r12_body_timeout_after_headers_does_not_retry_immediately` (run `27997609089`) appears to be caused by `submitEventNow` wrapping `suspend submitEvent` in `runBlocking { ... }` from inside a `runTest(timeout = 5.minutes)` body. The TARGETED point repair of the two affected commonTest files (`BodyTimeoutContractTest` and `RestFallbackOrchestratorBreakerTest` — the two grep hits that put `submitEventNow` / `onEventNow` inside a `runTest` body at this SHA) IS in scope per §4 item 5 and §7 step 3 — convert those non-suspend test bodies to suspend bodies that call `submitEvent(...)` / `onEvent(...)` directly. A wider audit of every other commonTest file that uses `*Now` helpers inside `runTest` IS NOT in scope; that audit is deferred to a follow-up track (see §6 L-Workaround-not-root-cause-on-CI). *(Retrospective note: the targeted point repair was added to the track after CI showed a 30-minute hang on the first jvmTest run; it is acknowledged as scope creep on top of the original closing-test-package and explicitly called out in the PR description.)*

---

## §4 — Test acceptance

PASS for this track requires ALL of:

1. **Local jvmTest sweep** GREEN: `:shared:core:transport:jvmTest` 566 tests / 0 failures.
2. **Local Android unit sweep** GREEN: `:apps:android:testDebugUnitTest + :apps:android:assembleDebug + :apps:android:compileReleaseKotlinAndroid` 140 tests / 0 failures.
3. **Local release minification** GREEN: `:apps:android:assembleRelease` succeeds AND `verifyR8StripsTestSeams` reports `PASS — no *ForTest members survived on any phantom.* class`.
4. **Negative control for `verifyR8StripsTestSeams`**: re-introducing the wildcard `-keep class phantom.core.transport.KtorRelayTransport { *; }` in `proguard-rules.pro` empirically fails `assembleRelease` with 127 violations from the verifier, then removing the wildcard restores GREEN.
5. **Android CI green on the final SHA.** A CI run on the PR head must complete the full pipeline (identity / crypto / storage / transport / messaging jvmTests, then `assembleDebug`, then artifact upload) inside the 30-minute job timeout. The 2026-06-23 first attempt at SHA `ecd67e04` hung for 28 minutes inside `:shared:core:transport:jvmTest` on `BodyTimeoutContractTest > r12_body_timeout_after_headers_does_not_retry_immediately`; the suspect path is `RestStateMachineTestHelpers.kt:29` (`submitEventNow` wrapping `suspend submitEvent` in `runBlocking`) called inside `runTest`. The root-cause repair is in scope.
6. **Tele2 LTE field smoke** with all three flags forced to `"1"` via `-PmodeTwoFastPath=1 -PmodeTwoSticky=1 -PreconnectQuiesce=1` on a fresh APK built from the exact PR head (SHA-256 recorded), on the Tecno BF7-12, on Tele2 Иркутская LTE. Expected observation: Mode 2 silent-drop session terminates → state machine flips to `RestActive` → quiescence gate flips to `Quiesced` → migration drains pending stores → REST delivery resumes. A subsequent route change to Wi-Fi runs the typed rewalk transaction → coordinator issues a single-use probe (`ProbeAvailable`) → reconnect-loop side claims it (`ProbeClaimed`) → recovery WS handshake fires → gate advances `ProbeClaimed → CandidateProving` immediately on the owner-validated `Connected` event → CandidateProving holds for 60 seconds of in-flight probation → on `ws_alive_60s` survival the gate promotes to `Open` and the mode promotes to `WsActive` cleanly. ZERO reconnect-loop storms (no ~32 s recycle pattern) over a ≥ 15-minute observation window on each network.

A Wi-Fi-only PASS is NOT sufficient per WORKING_RULES rule 8 (Wi-Fi is too permissive — Direct WS succeeds and the fallback machinery is never exercised). Tele2 LTE is the load-bearing gate.

---

## §5 — Parking conditions

Two architectural failures park the track and force a redesigned restart from `master` per WORKING_RULES rule 4:

- **Park A — gate state machine deadlocks under contention.** If the single-lock atomic discipline produces a reproducible deadlock between `gateLock` and any of the state-machine's suspend-context awaits on a CI runner or on the Tecno field device, the track parks. The redesign would split the single gateLock into per-substate locks OR move from `kotlinx.coroutines.sync.Mutex` to a lock-free CAS-based publication.
- **Park B — release-binary attack surface cannot be closed without losing diagnostic logging.** If tightening `proguard-rules.pro` to strip `*ForTest` seams provably breaks production diagnostic logs (R8 strips a logger entry-point that turns out to be reflectively reached), the track parks. The redesign would move the test seams into a separate companion-object class that lives only in `androidUnitTest` source so R8 has nothing to scope, removing the need for the ProGuard rule entirely.

A third attempt on the same architecture after two parks is not allowed.

---

## §6 — Known limits acknowledged at PR head

Honest limits documented in the draft PR body and re-stated here so a future reader of `docs/tracks/` does not need to read the PR:

- **L-Test20-invocation-gap.** Test 20 exercises the shared private helper `KtorRelayTransport.runReconnectMergeAndFlush` via the test-only seam `runReconnectMergeAndFlushForTest`. Both the production `runReconnectLoop:1287` call site AND the test seam invoke the same private helper, so seam-body and production-body cannot drift. The test does NOT pin that production `runReconnectLoop` continues to invoke that helper. A future regression that removes the line-1287 call site leaves Test 20 green. The structurally correct pin is an integration test that drives `runReconnectLoop` itself with a recording WebSocket session (Ktor MockEngine WS or embedded loopback server); building that fixture is deferred to a follow-up track.
- **L-Runtime-narrowness.** The "no new runtime functionality" framing on the closing test package is too broad. Three production-path branches were added — `sendRawAttemptForTest?.invoke`, `flushEnteredForTest?.complete`, `flushReleaseForTest?.await` — and the volatile-field READ fires on every call regardless of seam state. Production-observable BEHAVIOR is unchanged when the seams are null, which is the rollout-relevant invariant. The defensible narrower claim is "no production-observable behavior change at runtime when test seams are null."
- **L-Workaround-not-root-cause-on-CI.** If the CI hang turns out to be a generic `runBlocking { ... }`-inside-`runTest` thread-starvation issue on Linux x64, the point repair in this track converts the affected non-suspend test bodies to suspend bodies that call `submitEvent(...)` directly. It does NOT fix the broader problem class — any other commonTest file that uses `*Now`-helpers inside `runTest` could re-introduce the same hang. A follow-up audit track would scrub the wider commonTest surface for the same pattern.

---

## §7 — Last hand-off (per WORKING_RULES rule 5)

- **Branch:** `canary/reconnect-quiescence` (feature) + `docs/rc-reconnect-quiescence1-minilock` (this docs PR).
- **Feature branch head at the time of writing:** `ecd67e04`.
- **Next decision:** Android CI 30-min timeout on `ecd67e04`. Plan locked 2026-06-23: docs PR (this file + `PROJECT_LOG.md` entry) merges first; feature branch rebases onto the new master; two commits in the feature branch history (`69c8452c`, `c72c2b98`) are reworded to drop personal attribution; then a targeted source repair of `submitEventNow` / `onEventNow` call sites inside `runTest` bodies; force-push; CI; on green SHA → fresh architects review pass (full + adversarial) → PR evidence per rule 9 → APK with three flags `"1"` → Tele2 LTE smoke → mark PR ready.
