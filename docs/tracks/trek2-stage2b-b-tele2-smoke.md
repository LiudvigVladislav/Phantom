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
3. **Runtime flag proof.** A logcat excerpt showing `LONGPOLL_V2_ENABLED=1` at orchestrator construction. The flag is computed from the Gradle-generated `BuildConfig.LONGPOLL_V2_ENABLED` and surfaced in the orchestrator start log line. Without this line the runtime value is unknown.

## Per-scenario log-line evidence (load-bearing)

Each scenario's pass criterion requires ALL of the following log signals during the smoke window:

| Proof | Log signal |
|---|---|
| Feature flag is on | `LONGPOLL_V2_ENABLED=1` at orchestrator construction |
| Verify-key state is `KeyPresent` | `seq_mac_verify_key_state=KeyPresent` ≥ 1× before first envelope ingest |
| At least one envelope passes verify | ≥ 1 × `seq_mac_verified` (or equivalent verify-pass log shape) |
| Cursor is persisted | ≥ 1 × `cursor_advanced seq=<n>` with strictly-monotonic `seq` |
| Long-poll headers emitted | `X-Phantom-Long-Poll: 1` AND `X-Phantom-Padded-Poll: 1` visible in `REST_TRACE` |

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

**Time-box.** ≥ 30 minutes of Tecno+Tele2 LTE elapsed wall-clock. A **controllable trigger** (debug-mode helper that simulates `BREAKER_CONSECUTIVE_FAIL_THRESHOLD = 5` consecutive REST poll failures by force-returning IOException from the transport) is acceptable iff natural Mode-2 does not reproduce in the 30-min window. If the controllable trigger is used, the PR description names it explicitly and quotes the helper's invocation line.

**Pass criteria (all six required).**

1. **Open trigger.** `breaker_open reason=ConsecutiveRestFailures cooldown_ms=<n>` logs within the threshold (5 consecutive failures).
2. **Cooldown skip.** REST poll enters the cooldown — `REST_TRACE poll_call_skipped reason=breaker_open_*` lines fire for at least `BREAKER_INITIAL_COOLDOWN_MS = 5_000` ms.
3. **Direct WSS continuity.** Direct WSS continues delivering messages during the REST cooldown (at least one `WS Deliver` log inside the cooldown window). Per scope: "Direct WSS, Reality, and Tor transports stay operational — the messenger remains usable over those paths." A breaker-open MUST NOT silence the messenger.
4. **HalfOpen transition.** At cooldown expiry the breaker transitions to `HalfOpen` and issues **exactly one** probe poll: `breaker_half_open` followed by `poll_call ... probe=true` (or equivalent probe-flag shape — the implementation logs the half-open probe distinctly enough to grep).
5. **Probe outcome.** The probe outcome transitions the breaker correctly:
   - **Probe success** → `breaker_closed`. Polling resumes.
   - **Probe failure** → `breaker_open reason=ConsecutiveRestFailures cooldown_ms=<doubled>` capped at `BREAKER_COOLDOWN_CEILING_MS = 120_000`.
6. **Cursor invariance.** Cursor is preserved across all transitions (M-B16 applies in the field) — the persisted `last_seen_seq` does not regress at any point during the cooldown / HalfOpen / re-close cycle.

If the controllable trigger is used, the trigger emits `breaker_test_trigger_fired` (or equivalent helper-only log) and the PR description includes both that line AND the natural-trigger absence note (e.g., "30 min Tele2 LTE upload did not provoke Mode-2 in the observation window; controllable trigger invoked at 21 m 47 s mark per breaker_test_trigger_fired log").

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
  <logcat line showing LONGPOLL_V2_ENABLED=1>
Elapsed wall-clock: <hh:mm:ss>
```

## Verdict gates

- **All six PASS** → smoke verdict PASS; PR can exit draft.
- **Any FAIL** → PR stays in draft; remediation lands as a follow-up commit on the branch; the smoke is re-run against the new head.
- **Inconclusive S6 with controllable trigger absent** → smoke is incomplete; re-run with the controllable trigger explicitly invoked.

The Tele2 smoke is a once-per-implementation-PR gate, not a per-commit gate. If the smoke landed on an earlier C6+ commit and later review-fix commits do not touch the L8/L9/L10 surface, the existing smoke evidence stands. If a later commit touches that surface, the smoke is re-run.

## After PASS

Stage 2B-B merges as code (release-pin still at `"0"`). The promotion to release runtime is a separate, deliberately-ceremonial Stage 2B-D PR with its own rollout gate; see scope-doc § After this PR for the sequencing.
