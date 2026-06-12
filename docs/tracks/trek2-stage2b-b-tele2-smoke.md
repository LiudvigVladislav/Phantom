# Trek 2 Stage 2B-B — Tele2 LTE smoke runbook

This runbook is the mandatory pre-merge field gate for the Stage 2B-B implementation PR (`feat/pr-trek2-stage2b-b-impl`). The scope-doc `trek2-stage2b-b-scope.md` (line 449+) makes the smoke binding under `WORKING_RULES rule 8`: Stage 2B-B is NOT shippable without it. S1-S6 are all mandatory; S6 was promoted from optional under OQ-5 LOCK and a controllable trigger is acceptable only if natural Mode-2 does not reproduce inside the time-box.

The runbook ships as part of C6. The smoke itself runs against the C6 head (or a later C6+ review-fix commit if any).

## Device + carrier

- **Device.** Tecno (model and Android version captured in the PR description).
- **Carrier.** Tele2 LTE on the SIM bound to the Tele2 access path that produced the byte-budget cutoff in the Direct-stability tracks (Иркутская field site).
- **Network condition expectations.** LTE-only (4G); cellular data on; Wi-Fi off; airplane mode off. Switch tests AT cell tower locations known to exhibit Mode-2 on Direct-WSS sessions; the scope's S6 mandates Mode-2 provocation under the byte-threshold class.

## APK build requirement (load-bearing)

The smoke runs on a **debug or beta APK** built with `LONGPOLL_V2_ENABLED == "1"`. A release APK pins the flag at `"0"` by the L6 / Stage 2B-A scope-locked release pin and exercises **zero** Stage 2B-B runtime paths — a release-APK smoke is NOT a valid Stage 2B-B smoke and the PR cannot exit draft on its evidence.

The PR description MUST quote, verbatim:

1. **APK SHA-256.** Captured via `sha256sum app-debug.apk` (or beta equivalent) at the exact APK that ran the field session. The SHA pins the binary that produced the log evidence so a different build cannot be substituted post-hoc.
2. **Build variant.** `debug` or `beta`.
3. **Runtime flag proof.** A logcat excerpt of the orchestrator construction line, literal shape: `REST_TRACE orchestrator_started long_poll_enabled=true LONGPOLL_V2_ENABLED=1`. The `LONGPOLL_V2_ENABLED=<0|1>` suffix is computed from the Gradle-generated `BuildConfig.LONGPOLL_V2_ENABLED` BuildConfig field; the Boolean `long_poll_enabled=` precursor is kept for back-compatible parsers. Without this line the runtime value is unknown.

## Per-scenario log-line evidence (load-bearing)

Each scenario's pass criterion requires ALL of the following log signals during the smoke window:

| Proof | Log signal |
|---|---|
| Feature flag is on | `REST_TRACE orchestrator_started ... LONGPOLL_V2_ENABLED=1` at orchestrator construction |
| Verify-key state is `KeyPresent` | `REST_TRACE seq_mac_verify_key_state=KeyPresent` ≥ 1× before first envelope ingest |
| At least one envelope passes verify | ≥ 1 × `REST_TRACE seq_mac_verified id=<hex> seq=<n>` |
| Cursor is persisted | ≥ 1 × `REST_TRACE cursor_advanced seq=<n>` with strictly-monotonic `seq` |
| Long-poll headers emitted | `REST_TRACE poll_call ... X-Phantom-Long-Poll=1 X-Phantom-Padded-Poll=1` (key=value form, value `1` ⇔ flag on, value `absent` ⇔ flag off) |

A log block missing any of the five proofs is NOT a pass regardless of subjective verdict (envelope delivered, chat appears responsive, etc.). The PR description quotes the matching lines verbatim per scenario.

## Six scenarios

### S1 — WS up, REST poll arrives, MAC verifies, no decrypt regression

**Setup.** Standard Direct-WSS up. Peer sends five envelopes over a short window (≤ 30 s).

**Pass criteria.**

- Every envelope reaches the chat exactly once (5 visible bubbles).
- No `seq_mac_verify_fail` lines.
- No `decrypt_fail` lines.
- Both `ws_active_poll_ok` AND WS `Deliver` increment without duplicates in the messages table (no `processed_envelope_id` reuse rows).
- The five log-line proofs (table above).

### S2 — WS down, REST primary, multi-envelope batch

**Setup.** Force WS off (`/dev hub` debug toggle or kill WS by detaching observable). Peer sends four envelopes; relay returns `more=true` on the first poll.

**Pass criteria.**

- All four envelopes arrive in `seq` order at the chat.
- Persisted cursor advances to the highest received `seq`.
- Cold restart of the orchestrator (force-stop + relaunch) polls with that `since_seq` and gets ZERO duplicates from the relay.
- The five log-line proofs.

### S3 — Token rotation mid-poll, 410 fires, re-auth + retry

**Setup.** Operator rotates the relay's session token store mid-poll (e.g. restart relay or trigger explicit rotation via debug endpoint).

**Pass criteria.**

- `poll_410_token_rotated` log fires inside the smoke window.
- A fresh `session_request` logs at re-auth (new token issued).
- `ws_active_poll_ok` logs after retry on the new token.
- No envelope duplicated; no envelope lost.
- Cursor preserved across the 410 dance.
- The five log-line proofs.

### S4 — Server kill switch `RELAY_POLL_HOLD_SECS=0`

**Setup.** Operator flips the env var on the VPS and bounces the relay; client reconnects.

**Pass criteria.**

- Client receives short-poll cadence with the padded body shape.
- `poll_hold_secs=0` parsed at session boot.
- L8 timer gate returns the legacy 10_000 ms `readTimeoutMs` per `computeLongPollReadTimeoutMs`.
- Chat continues end-to-end (no perceived stall beyond short-poll cadence).
- The five log-line proofs.

### S5 — Voice notes uniform-functionality

**Setup.** Peer sends voice notes (Trek 3 voice-uploaded media path) AND text in the same poll window.

**Pass criteria.**

- Voice notes arrive in the same poll window as text sent at the same instant.
- Privacy-modes uniform-functionality lock holds: long-poll does NOT degrade voice (no `voice_send_dropped` or equivalent media-loss log).
- The five log-line proofs.

### S6 — Breaker open under Tele2 byte-budget death (mandatory per OQ-5 LOCK)

**Setup.** Sustain Tele2 LTE upload pressure to provoke Mode-2 cutoff (5-14 KB upload then silence per the byte-threshold finding from the Direct-stability tracks). The breaker does NOT "select" a transport — it controls the REST poll cadence.

**Time-box.** ≥ 30 minutes of Tecno+Tele2 LTE elapsed wall-clock. A **controllable trigger** is acceptable iff natural Mode-2 does not reproduce in the 30-min window. The orchestrator ships a public helper, `forceBreakerTripForS6TestTrigger()`, that synthesises the threshold-fail-count + transitions the breaker through the production `transitionToOpenUnderMutex` path — byte-identical to a natural 5-failure trip. The helper emits the trigger log line `REST_TRACE breaker_test_trigger_fired reason=ConsecutiveRestFailures threshold=5` before the regular `breaker_open` line.

**Build-time gate.** A dedicated `BuildConfig.S6_DEBUG_TRIGGER_ENABLED` String "1"/"0" pin, independent of `BuildConfig.DEBUG`. Debug builds default to `"1"`; release builds pin to `"0"`. A future beta variant (`isDebuggable=false`) opts in via `s6DebugTriggerEnabled=1` in `local.properties` (or `S6_DEBUG_TRIGGER_ENABLED=1` env). The runbook's "debug OR beta APK" allowance is now honoured regardless of how the future beta variant is configured.

**Three-layer defence-in-depth for production safety.**

1. **Orchestrator constructor flag.** `s6DebugTriggerEnabled` defaults to `false`; the helper short-circuits with `REST_TRACE breaker_test_trigger_refused reason=disabled_in_release` when `false`.
2. **AppContainer-level gate.** `AppContainer.triggerS6BreakerForDebug()` checks `BuildConfig.S6_DEBUG_TRIGGER_ENABLED == "1"`. Release APKs see `"0"` and return `false` before reaching the orchestrator helper.
3. **Receiver registration gate.** `AppContainer` registers `phantom.android.dev.S6BreakerTriggerReceiver` dynamically iff `BuildConfig.S6_DEBUG_TRIGGER_ENABLED == "1"`. Release APKs never register the receiver; the broadcast intent is dropped at the framework level. On API 33+ the receiver registers with `RECEIVER_EXPORTED` so `adb shell am broadcast` (system shell user) can deliver — round-2's `RECEIVER_NOT_EXPORTED` silently dropped the intent on API 33+ devices.

**Trigger recipe.** The Tecno operator, with the device attached over USB, fires from the connected laptop:

```bash
adb shell am broadcast \
  --receiver-permission phantom.android.dev.permission.TRIGGER_S6 \
  -a phantom.android.dev.S6_BREAKER_TRIGGER
```

(The `--receiver-permission` flag is REQUIRED on round-5 wiring: the receiver is registered with a signature-level sender permission so co-installed third-party apps on a debug/beta device cannot broadcast the trigger. The system shell satisfies signature-scoped permissions on a debug-keyed APK; omitting the flag causes Android to drop the broadcast at delivery time without invoking `onReceive`.)

Logcat should then show:

```
Phantom/S6Debug  Registered S6BreakerTriggerReceiver for action phantom.android.dev.S6_BREAKER_TRIGGER (RECEIVER_EXPORTED on API 33+; debug build only)
... <wait until the natural Mode-2 window expires> ...
Phantom/S6Debug  S6 breaker trigger broadcast received; dispatching on AppContainer.appScope
PhantomRelay     REST_TRACE breaker_test_trigger_fired reason=ConsecutiveRestFailures threshold=5
PhantomRelay     REST_TRACE breaker_open reason=ConsecutiveRestFailures cooldown_ms=5000
Phantom/S6Debug  triggerS6BreakerForDebug() returned dispatched=true
```

**Mandatory smoke verification step (one-shot per APK build).** Before relying on the controllable trigger for S6 evidence, the operator MUST verify the broadcast delivery path is wired correctly on THIS specific APK:

1. Launch the APK on Tecno; verify the `Phantom/S6Debug  Registered S6BreakerTriggerReceiver ...` line appears at startup (proves the receiver registered with the right gate).
2. Without waiting for natural Mode-2, fire the broadcast immediately and observe BOTH `S6 breaker trigger broadcast received` AND `breaker_test_trigger_fired` in logcat within 1-2 seconds.
3. Reset the breaker (force-stop + relaunch the app, or let the cooldown expire to `Closed`).

If either log line is missing on this verification, the APK is misconfigured (likely a release variant or a debug build with `s6DebugTriggerEnabled=0` flipped). DO NOT count S6 evidence under such an APK; rebuild and re-verify.

A release APK on the same device would show NO `Registered S6BreakerTriggerReceiver` line at app startup and the broadcast would be silently dropped. If the operator sees a `breaker_test_trigger_refused reason=disabled_in_release` line instead of `breaker_test_trigger_fired`, the build is a release variant and the smoke is INVALID per the APK-build requirement above. If the controllable trigger is used, the PR description quotes the trigger log line explicitly AND the receiver-registered line from app startup as proof the build was debug/beta.

**Pass criteria (all six required).**

1. **Open trigger.** `REST_TRACE breaker_open reason=ConsecutiveRestFailures cooldown_ms=<n>` logs within the threshold (5 consecutive failures). When the controllable trigger is used, the `breaker_test_trigger_fired` line precedes it.
2. **Cooldown skip.** REST poll enters the cooldown — `REST_TRACE poll_call_skipped reason=breaker_open_ConsecutiveRestFailures` lines fire for at least `BREAKER_INITIAL_COOLDOWN_MS = 5_000` ms.
3. **Direct WSS continuity.** Direct WSS continues delivering messages during the REST cooldown (at least one `WS Deliver` log inside the cooldown window). Per scope: "Direct WSS, Reality, and Tor transports stay operational — the messenger remains usable over those paths." A breaker-open MUST NOT silence the messenger.
4. **HalfOpen transition.** At cooldown expiry the breaker transitions to `HalfOpen` and issues **exactly one** probe poll. The transition emits `REST_TRACE breaker_half_open`; the probe iteration emits `REST_TRACE poll_call ... probe=true X-Phantom-Long-Poll=1 X-Phantom-Padded-Poll=1`. Regular (non-probe) iterations show `probe=false`.
5. **Probe outcome.** The probe outcome transitions the breaker correctly:
   - **Probe success** → `REST_TRACE breaker_closed`. Polling resumes.
   - **Probe failure** → `REST_TRACE breaker_open reason=ConsecutiveRestFailures cooldown_ms=<doubled>` capped at `BREAKER_COOLDOWN_CEILING_MS = 120_000`.
6. **Cursor invariance.** Cursor is preserved across all transitions (M-B16 applies in the field) — the persisted `last_seen_seq` does not regress at any point during the cooldown / HalfOpen / re-close cycle. Grep `REST_TRACE cursor_advanced seq=` lines and assert strict monotonicity across the window.

If the controllable trigger is used, the PR description includes both the trigger log line AND the natural-trigger absence note (e.g., "30 min Tele2 LTE upload did not provoke Mode-2 in the observation window; controllable trigger invoked at 21 m 47 s mark per `REST_TRACE breaker_test_trigger_fired` log").

## Smoke evidence block (PR description)

The PR description records per scenario:

```
S<n> verdict: PASS | FAIL
S<n> evidence:
  <pasted logcat excerpt with the five log-line proofs for that scenario>
  <plus the scenario-specific lines named in the pass criteria above>
```

Additionally, before the per-scenario block:

```
Device: <Tecno model + Android version>
Carrier: Tele2 LTE
APK SHA-256: <sha256sum output>
APK variant: debug | beta
Runtime flag proof:
  <logcat line: `REST_TRACE orchestrator_started long_poll_enabled=true LONGPOLL_V2_ENABLED=1`>
Elapsed wall-clock: <hh:mm:ss>
```

## Verdict gates

- **All six PASS** → smoke verdict PASS; PR can exit draft.
- **Any FAIL** → PR stays in draft; remediation lands as a follow-up commit on the branch; the smoke is re-run against the new head.
- **Inconclusive S6 with controllable trigger absent** → smoke is incomplete; re-run with the controllable trigger explicitly invoked.

The Tele2 smoke is a once-per-implementation-PR gate, not a per-commit gate. If the smoke landed on an earlier C6+ commit and later review-fix commits do not touch the L8/L9/L10 surface, the existing smoke evidence stands. If a later commit touches that surface, the smoke is re-run.

## After PASS

Stage 2B-B merges as code (release-pin still at `"0"`). The promotion to release runtime is a separate, deliberately-ceremonial Stage 2B-D PR with its own rollout gate; see scope-doc § After this PR for the sequencing.
