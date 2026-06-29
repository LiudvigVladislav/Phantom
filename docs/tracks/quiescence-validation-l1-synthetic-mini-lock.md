# Track: QUIESCENCE-VALIDATION-L1-SYNTHETIC-MINI-LOCK

**Type:** Implementation scope-lock for the MB half of QUIESCENCE-VALIDATION-METHODOLOGY-RECON1's H-ME verdict. NOT a code change. NOT a re-investigation of the H-ME verdict or PR #330's quiescence contract.
**Status:** Open (mini-lock).
**Opened:** 2026-06-30 after `quiescence-validation-methodology-recon1.md` §13 closed with verdict H-ME on 2026-06-29 (PR #349 squash `54f2e50d`). H-ME mandates BOTH (a) the MC half — deterministic state-machine / integration validation — and (b) the MB half — an L1 synthetic field trigger that injects a synthetic `WsSessionLifecycleEvent.Ended` into the production lifecycle channel. This mini-lock scopes the MB half only. The MC half is scoped separately.
**Last known master at open:** PR #349 squash `54f2e50d`.

---

## §1 Goal

Formalise the implementation contract for the L1 synthetic-trigger debug flag identified in `quiescence-validation-methodology-recon1.md` §12 and locked as the MB half of the H-ME closure verdict in §13. The implementation enables a controlled, on-device Mode 2 reproduction so PR #330's quiescence chain (`sticky → Quiesced → ProbeAvailable → ProbeClaimed → CandidateProving → Open`) can be exercised end-to-end on Wi-Fi without waiting for a random field Mode 2 episode.

This mini-lock is the building blueprint that an implementation PR will satisfy. It is NOT itself a code change. It is NOT the implementation PR. It defines what the implementation PR MUST do and what it MUST NOT do.

## §2 Scope

- Transcribe the eleven binding locks `L-13.3.1` through `L-13.3.11` from the methodology recon's §13.3 verdict as binding constraints on the implementation PR.
- Promote the two security council BLOCKERS (`L-13.3.6` ProGuard narrowing, `L-13.3.7` S6-style four-layer operator surface) to formal preconditions that the implementation PR MUST satisfy before any code lands.
- Formalise the `SyntheticTriggerResult` typed-return contract concretely: enum / sealed members, semantics, downstream caller obligations.
- Formalise the race contract for the check-then-trySend window (`L-13.3.9` Part B): the epoch-snapshot discipline, the receiving-side dedup mechanism, and the adversarial test cells the implementation PR MUST include.
- Bind the implementation PR's test set to the source-grounded acceptance matrix at `C:\temp\quiescence-h-me-council-2026-06-29\tests.md` as the test floor.
- Specify the implementation PR's hand-off contract: when it may open, what it must contain, what verdict it must produce.

## §3 Out of scope

- **Any code change.** This mini-lock is docs-only. The implementation PR is the next item.
- **MC half of H-ME.** Separate scope-lock. Will likely stack on PR #330's added tests or extend on-master test infrastructure once PR #330's gate component lands. NOT scoped here.
- **PR #330.** Stays Draft / HOLD throughout. No edits, no rebase, no force-push, no merge attempts from this mini-lock.
- **The H-ME verdict itself.** Locked by `quiescence-validation-methodology-recon1.md` §13.1. Not re-litigated.
- **Re-investigation of Mode 2 root cause.** Parked in `rc-direct-stability1.md` / `rc-direct-ws-death1.md`. Carry-forward facts (`F-Mode1`, `F-Mode2-pp0`, `F-Mode2-pp1`, `F-Mode2-cadence-invariant`) stay as input.
- **The controlled Wi-Fi smoke run that validates the chain end-to-end.** That runs only after the implementation PR merges AND the MC half is in place. NOT this mini-lock's question.
- **`closeOrigin` semantics outside the synthetic discipline.** The `local / remote / error / unknown` taxonomy is unchanged; `"synthetic"` is the additional tell, not a redefinition.
- **N-4 / N-5.** Rejected by the closure verdict. Not re-opened.

## §4 Binding constraints — transcribed from §13.3

The implementation PR is bound by all eleven locks from the methodology recon's closure verdict. Each is RESTATED here verbatim or near-verbatim with implementation-PR-facing implications added below it. The original §13.3 wording remains the authoritative text; this mini-lock binds the implementation PR to that text.

### §4.1 L-13.3.1 — Both halves required

The DIRECT-WSS-MODE2-RECON1 §11 B1 acceptance gate is satisfied only when **MC PASS + MB PASS** are both on record. The implementation PR delivers the MB half. It MUST NOT claim B1 closure on the strength of MB alone. The implementation PR's commit message + body MUST explicitly state that B1 remains open until the MC half also passes.

### §4.2 L-13.3.2 — L1 mandatory, L2 rejected

The implementation PR injects at L1 (`_wsSessionLifecycle.trySend(WsSessionLifecycleEvent.Ended(...))`). The implementation PR MUST NOT add any L2 entry point that submits `RestStateMachine.Event.WsSessionEnded(...)` directly. If a developer wants a state-machine-direct entry point for unit tests, it goes through the existing `RestStateMachineTest` controllable-`now` fixture — NOT through a production-surface API.

### §4.3 L-13.3.3 — Sequential dispatcher order load-bearing

The implementation PR MUST include a structural test that asserts `WsSessionLifecycleDispatcher.dispatch(Ended)` consumes the state-machine consumer FIRST (lines 54-58 on master HEAD: `submitStateEvent` arm) and the telemetry consumer SECOND (line 59: `feedDegradationDetector`). The test fails loudly if a future refactor flips order or fans out in parallel.

### §4.4 L-13.3.4 — `closeOrigin = "synthetic"` non-branching discipline

The implementation PR MUST NOT add any `if (event.closeOrigin == "synthetic") { … }` arm anywhere in the dispatch chain or downstream consumers — dispatcher, state machine, detector, gate, or PR #330's `WsReconnectGate`. The implementation PR MUST include a grep-style negative-presence test that fails if any such arm is added in the same PR or any future PR.

### §4.5 L-13.3.5 — `maybeRetryBootstrap()` branch scope gap

The dispatcher routes to `maybeRetryBootstrap()` when `restCapabilityActiveProvider()` returns false. The implementation PR MUST explicitly document whether the synthetic trigger is expected to fire in that branch (it should not, because the state-machine consumer is the path being validated). If the synthetic fires while `restCapabilityActive == false`, the implementation PR MUST document the expected behaviour OR refuse to fire (returning `RefusedDisabled` or a new `RefusedNoRestCapability` member of `SyntheticTriggerResult`).

### §4.6 L-13.3.8 — Boolean injection, not provider lambda

The injected gate parameter on `KtorRelayTransport` is `debugForceMode2Enabled: Boolean = false`. Default `false`. NO `() -> Boolean` provider variant. NO runtime mutation surface. The build-time value flows from `phantom.android.BuildConfig.DEBUG_FORCE_MODE_2_DETECTION == "1"` read in `AppContainer` (Android side, around the existing `mode2FastPathEnabled` wiring at `AppContainer.kt:1348-1357`).

### §4.7 L-13.3.10 — Typed `SyntheticTriggerResult` return

The synthetic-trigger method returns a typed result. See §6 for the full contract.

### §4.8 L-13.3.11 — Acceptance matrix is test floor, not ceiling

The implementation PR adopts the matrix at `C:\temp\quiescence-h-me-council-2026-06-29\tests.md` as the minimum test set. The implementation PR MAY add more tests; it MUST NOT remove any matrix items. The matrix is source-grounded against PR #330 head `6f49cd89` and master HEAD `98ee7e09` — the implementation PR re-verifies marker names against current master at the time of opening.

## §5 Preconditions (BLOCKERS — must be satisfied before any code lands)

### §5.1 L-13.3.6 — ProGuard narrowing precondition

On master HEAD, `apps/android/proguard-rules.pro:95` carries `-keep class phantom.core.transport.KtorRelayTransport { *; }`. This wildcard preserves every member of `KtorRelayTransport` in the release APK. If the implementation PR adds `internal fun debugForceMode2Synthetic(...)` under this wildcard, the method body — including the `_wsSessionLifecycle.trySend(...)` call and the `closeOrigin = "synthetic"` / `closeError = "DEBUG_FORCE_MODE_2_DETECTION"` string literals — survives R8 / ProGuard shrinking in release builds. The injected Boolean prevents actuation at the constructor-argument level, but the method body remains callable from within the same process.

The implementation PR MUST satisfy ONE of:

- **Option A — Stack on PR #330's narrowing.** PR #330's mini-lock §2 plans to remove the wildcard and add a `verifyR8StripsTestSeams` Gradle task that fails the release build on any surviving `*ForTest` member. If that plan lands first, the implementation PR opens as a stack on top of PR #330 (or rebases after PR #330 merges). The implementation PR's commit message + body MUST cite the specific commit where the narrowing took effect.
- **Option B — Include equivalent narrowing in the implementation PR itself.** The implementation PR removes the wildcard, adds a `verifyR8StripsTestSeams`-equivalent Gradle task, and includes a verification step in the PR's test set that asserts the synthetic-trigger method is stripped from the release APK. The PR body documents the narrowing rationale explicitly.

The implementation PR MUST NOT open without Option A or Option B satisfied. A PR that adds `debugForceMode2Synthetic` under the current wildcard is rejected at review without exception.

### §5.2 L-13.3.7 — S6-style four-layer protected operator surface

The operator trigger surface MUST follow the `S6BreakerTriggerActivity` four-layer pattern. Raw `am broadcast` BroadcastReceiver without the four layers is forbidden. The four layers, all required:

1. **Debug-overlay manifest only.** The Activity (or Receiver, if the implementation PR chooses an Activity-equivalent) is declared only in `apps/android/src/debug/AndroidManifest.xml`, never in the production manifest. Release-build APKs do not contain the manifest entry.
2. **Signature-level `android:permission`.** The component carries `android:permission="<signature-permission-name>"` so only callers signed with the same certificate (i.e., a signed development build of the same app or a signed debug tool) can reach it. The exact permission name is decided in the implementation PR but MUST be signature-level (`<permission android:protectionLevel="signature">`).
3. **Runtime `BuildConfig` re-check inside the component.** The component's entry point method (`onCreate` for an Activity, `onReceive` for a Receiver) re-evaluates `BuildConfig.DEBUG_FORCE_MODE_2_DETECTION == "1"` AND `BuildConfig.DEBUG == true` AND aborts (calling `finish()` or returning) if either is false. The check is defence-in-depth against an attacker who somehow installs a debug APK on a release device.
4. **`AppContainer` re-check before reaching `KtorRelayTransport.debugForceMode2Synthetic`.** The component obtains the `KtorRelayTransport` reference through `AppContainer`. `AppContainer` re-checks `BuildConfig.DEBUG_FORCE_MODE_2_DETECTION == "1"` AND `BuildConfig.DEBUG == true` AND refuses to expose the method if either is false. The injected Boolean on `KtorRelayTransport` itself is the fifth check (constructor-time gate), but `AppContainer` is the choke point in the call chain — it MUST NOT be bypassed.

Concrete shape choice for the operator surface: the implementation PR chooses ONE of:

- **Default — Debug-only Activity** mirroring `S6BreakerTriggerActivity`. Operator launches via `am start -n phantom.android/.dev.DebugForceMode2Activity` (or equivalent).
- **Non-Activity alternative** ONLY if it satisfies all four layers explicitly. The implementation PR documents which layers correspond to which mechanisms in the alternative shape. Generic `am broadcast` Receiver without explicit four-layer mapping is rejected.

The implementation PR MUST include an `AppContainerDebugForceMode2WiringTest` (mirroring `AppContainerS6DebugTriggerWiringTest`) that asserts the four layers wire correctly.

The implementation PR MUST NOT open without the four-layer mapping documented and tested.

## §6 `SyntheticTriggerResult` formal contract

The synthetic-trigger method signature:

```
internal fun debugForceMode2Synthetic(durationMs: Long): SyntheticTriggerResult
```

The implementation PR adds the following sealed class (or enum class — sealed preferred for extensibility, see below) in `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/`:

```
sealed class SyntheticTriggerResult {
    object Fired : SyntheticTriggerResult()
    object RefusedDisabled : SyntheticTriggerResult()
    object RefusedNotConnected : SyntheticTriggerResult()
    data class RefusedDurationOutOfRange(
        val requestedMs: Long,
        val minMs: Long,
        val maxMs: Long,
    ) : SyntheticTriggerResult()
    object RefusedAlreadyFired : SyntheticTriggerResult()
}
```

Semantics per member, binding on the implementation PR:

- **`Fired`** — the synthetic event was constructed and the `_wsSessionLifecycle.trySend(...)` call returned success. The trigger CANNOT be fired again until a new `WsSessionLifecycleEvent.Connected` resets the one-shot latch.
- **`RefusedDisabled`** — the injected `debugForceMode2Enabled` Boolean is `false`. The method body MUST exit immediately after the check, without inspecting any state, without logging anything that reveals the existence of the synthetic-trigger surface (release-pin defence-in-depth).
- **`RefusedNotConnected`** — the injected Boolean is `true` AND `_state.value !is TransportState.Connected` at the moment of the check. The synthetic does not fire. The race-window between this check and the `trySend` is addressed in §7 below.
- **`RefusedDurationOutOfRange`** — `durationMs` lies outside `MODE_2_MIN_DURATION_MS..MODE_2_MAX_DURATION_MS` (25_000 ms ... 65_000 ms per `RestStateMachine.kt:1732-1747` on master HEAD; the implementation PR re-verifies the constants at opening). The data class fields enable the caller to diagnose the rejection.
- **`RefusedAlreadyFired`** — the one-shot latch for the current `Connected` epoch has already been consumed. Caller must wait for a new `Connected` event.

L-13.3.5 (`maybeRetryBootstrap()` branch scope gap) may require a sixth member, `RefusedNoRestCapability`, if the implementation PR chooses that disposition. The implementation PR decides; this mini-lock pins the requirement that L-13.3.5's gap MUST be addressed in the result type, not silently.

Why sealed class over enum: extensibility for data-carrying refusal members like `RefusedDurationOutOfRange`. An enum could carry only object members; a sealed class lets refusal members carry diagnostic fields. The implementation PR MAY revert to enum if all refusals reduce to object members — but the default is sealed class.

## §7 Race contract — L-13.3.9 Part B resolution requirements

The methodology recon's L-13.3.9 Part B locked the requirement that the implementation PR MUST resolve the check-then-trySend race. This mini-lock pins the three specifics the implementation PR's resolution MUST address.

### §7.1 Epoch snapshot at top of method

The implementation PR MUST take a single read of the current `sessionEpoch` at the top of `debugForceMode2Synthetic` and use that snapshot value throughout the method body. NO re-reads. The synthetic event built by the method carries the snapshot epoch, never a stale or later-read value.

If `sessionEpoch` is not directly accessible from `KtorRelayTransport` (master HEAD reading: it lives inside the per-session coroutine), the implementation PR adds a read-only accessor — `internal val currentSessionEpoch: Long?` or equivalent — that returns the current session's epoch or `null` if no session is active. The synthetic refuses (`RefusedNotConnected`) if the accessor returns `null`.

### §7.2 Receiving-side dedup mechanism

The implementation PR MUST specify which mechanism handles a duplicate `Ended` arriving for the same `sessionEpoch`. The options:

- **Option D-1 — Rely on `RestStateMachine`'s existing epoch filter.** The state machine already filters by `sessionEpoch` (per the `WsSessionLifecycleEvent.Ended` KDoc reference to "sticky-recovery epoch filter"). The implementation PR documents the exact filter location and asserts the synthetic-`Ended` interleaving with a real-`Ended` for the same epoch resolves to "first-event wins, second silently dropped" (or whichever ordering the filter actually implements — the PR documents reality, not a wish).
- **Option D-2 — Dispatcher-level dedup by `(sessionEpoch, closeOrigin)` tuple.** The dispatcher tracks the most recent `Ended` it has dispatched per epoch and drops duplicates. This requires a new dispatcher field + invariant.
- **Option D-3 — Method-level mutex on `_wsSessionLifecycle`.** The synthetic method holds a brief lock that serialises against the per-session `finally` block's `trySend`. This is the most conservative but adds threading complexity.

The implementation PR picks ONE option, documents the choice with rationale, and adds tests that exercise the race window adversarially (§7.3 below).

### §7.3 Adversarial race-window tests

The implementation PR MUST include tests that:

- Force a real session death (e.g., via `forceReconnect()` or a synthetic exception in the per-session loop) INSIDE the window between the `_state.value is Connected` guard return and the `_wsSessionLifecycle.trySend(...)` call.
- Assert the worst-case observable behaviour matches the chosen dedup option's documented invariant.
- Assert no panic / crash / silent state corruption occurs in the chosen interleaving.

The test mechanism is up to the implementation PR — likely a controllable test seam on `KtorRelayTransport` plus a `runTest` coroutine harness. The test MUST be deterministic; flaky tests are not accepted.

## §8 Acceptance matrix binding — L-13.3.11

The implementation PR's test set MUST include every item from the tests/adversarial council's acceptance matrix at `C:\temp\quiescence-h-me-council-2026-06-29\tests.md`. The matrix covers:

- Per-honesty-constraint adversarial tests (6 constraints, 1-2 tests each).
- Required positive log markers (12+ markers source-grounded against PR #330 head `6f49cd89`: `mode_2_signature_matched action=fast_path`, `sticky_armed`, `ws_reconnect_quiesced`, `ws_recovery_probe_granted`, `ws_reconnect_resumed`, `sticky_cleared proof=ws_alive_60s`, `ws_reconnect_open proof=ws_alive_60s`, `WS_DEGRADED_TELEMETRY counter`, `WS_DEGRADED detected` on rising edge, `mode_switched from=WS_ACTIVE to=REST_ACTIVE reason=mode_2_fast_path`, `mode_switched from=REST_ACTIVE to=WS_ACTIVE reason=ws_alive_60s`, plus subsidiary markers).
- Required absence of negative signals (5+ checks: no parallel `DEBUG_FORCE_MODE_2_DETECTION_TRIGGERED` branch line, no `closeOrigin`-switching arm, no bypass of `Connected` precondition, no `relay_send_return ok=false` across the quiescence window, no `LIFECYCLE_CONSUMER_ERROR`).
- Structural code-review check for "this is NOT a parallel test-only path" (7 specific diff verifications).
- End-to-end field-run PASS criteria (full logcat timeline from `Connected` through quiescence through `ws_alive_60s` restoration).
- End-to-end field-run FAIL criteria (7+ failure shapes that invalidate the run).
- One-shot vs repeat semantics (§7 above resolves the open question).

The implementation PR re-verifies marker names against current master at opening — if any marker has changed name between the matrix's source-grounding date (2026-06-29) and the implementation PR's opening date, the PR documents the drift.

## §9 Hand-off — implementation PR contract

The implementation PR opens ONLY after this mini-lock merges. Pre-mini-lock-merge implementation work is rejected at review.

The implementation PR:

- Cites this mini-lock (squash SHA) in its body.
- Cites the methodology recon's H-ME verdict (PR #349 squash `54f2e50d`) in its body.
- States in its body whether it satisfies L-13.3.6 via Option A or Option B (§5.1 above).
- Documents the chosen operator-surface shape (§5.2) and includes the four-layer wiring test.
- Includes the `SyntheticTriggerResult` sealed class as specified in §6.
- Documents the chosen receiving-side dedup mechanism (§7.2 Option D-1 / D-2 / D-3).
- Includes the adversarial race-window tests (§7.3).
- Adopts the acceptance matrix (§8) as the test floor.
- Re-verifies all matrix markers against current master at opening; documents any drift.
- States explicitly that B1 closure requires the MC half ALSO PASS and that this PR does NOT close B1 alone.

The implementation PR's reviewers verify every item above. A PR missing any item is rejected.

## §10 Park conditions

This mini-lock parks (suspends ≥ 7 days, releases lock on the implementation PR) under any of:

- **P-1 — PR #330's narrowing plan changes.** If PR #330's mini-lock §2 ProGuard narrowing approach pivots away from the `verifyR8StripsTestSeams` Gradle task, the L-13.3.6 Option A reference here becomes stale. The mini-lock parks until the new shape is documented.
- **P-2 — Operator unavailable for further L1 work.** Pragmatic park; the mini-lock stays open as documentation but no implementation PR opens.
- **P-3 — Architectural change in `KtorRelayTransport` or the dispatcher.** If a separate track lands a change to the lifecycle channel shape, the dispatcher's consumer order, or the state-machine signature check, the L-13.3.3 / L-13.3.4 / L-13.3.5 references become stale. The mini-lock parks until the implementation contract is re-verified.

## §11 What this mini-lock does NOT pre-decide

- The exact Kotlin file naming for `SyntheticTriggerResult` (commonMain location decided in the implementation PR).
- The exact signature permission string for the operator surface (decided in the implementation PR).
- The exact name of the operator-surface Activity / component (decided in the implementation PR; suggested `DebugForceMode2Activity` mirroring `S6BreakerTriggerActivity`).
- The exact dedup option (§7.2 Options D-1 / D-2 / D-3) — implementation PR picks.
- The exact `verifyR8StripsTestSeams`-equivalent Gradle task implementation (decided in the implementation PR if Option B is chosen).
- The MC half's test infrastructure scope. Separate mini-lock.
- The controlled Wi-Fi smoke run procedure. Opens after MC PASS + MB PASS land.
- Any PR #330 contract change. PR #330's contract is locked; this mini-lock validates it, does not amend it.

## §12 Hand-off note

If this mini-lock merges and the operator does NOT immediately open the implementation PR: the next session reads §4 + §5 + §6 + §7 + §8 + §9 and opens the implementation PR with all binding items satisfied. Do NOT open the implementation PR with any §5 BLOCKER unresolved. Do NOT open it without §6 `SyntheticTriggerResult` specified concretely. Do NOT open it without §7 dedup option chosen. Do NOT open it without §8 matrix re-verification against current master.

If this mini-lock merges and the operator opens the implementation PR same-session: the implementation PR's commit message + body cite this mini-lock's squash SHA verbatim.

Do NOT propose changes to PR #330's contract from inside this mini-lock or the implementation PR. Do NOT amend the H-ME verdict from inside this mini-lock. Do NOT skip the MC half — B1 closure requires BOTH halves PASS per L-13.3.1.
