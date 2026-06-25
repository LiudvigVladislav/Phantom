# RC-PREKEY-PUBLISH-DEBOUNCE-RACE — prekey publish debounce vs. in-flight failure (mini-lock)

> **Why this track exists.** A Tele2 LTE field-smoke attempt against the RC-RECONNECT-QUIESCENCE1 PR head (#330 at `6f49cd89`) on 2026-06-25 could not establish a bidirectional baseline. Tecno-side outbound to the second device returned `/prekeys/bundle/<peer> = 404` repeatedly (37 deferrals over the session). Tracing the second device's log surfaced a race between the in-flight prekey publish HTTP POST and the verify-driven republish path, where the debounce gate logged the republish as if it had succeeded while the underlying publish was still in flight. When the in-flight publish later failed with `ConnectException`, no retry was scheduled, and the bundle never reached the relay. This is a messaging-crypto baseline blocker, not a transport-quiescence defect — RC PR #330 stays Draft until this track lands.

---

## §1 — Goal

Stop the prekey publish debounce race so that:

1. The relay reliably ends up with a published bundle for every fresh-installed identity that successfully booted PHANTOM.
2. A debounced verify-driven republish never logs `upload_ok stored_opks=0` while the in-flight publish it was suppressed by can still fail.
3. After the in-flight publish fails, a retry path is scheduled so the bundle is eventually delivered to the relay without operator intervention (force-stop / restart should not be required for the bundle to reach the server).

Outcome the user observes: bidirectional baseline between two fresh installs (one mobile, one peer) works on the first message exchange after onboarding, no matter how the publish HTTP POST timed out under the carrier on the first attempt.

---

## §2 — Scope

This track touches the messaging-crypto prekey upload + publish + verify-republish coordination surface:

- **`PreKeyApi` / `PreKeyApiClient`** (and the Android-native `AndroidNativeOkHttpPreKeyPublishTransport` adapter, including the Sprint 2b factory-lambda re-snapshot path) — the call sites where `prekey_publish_*` telemetry is emitted and where the debounce gate currently shortcuts the post-debounce log line to `upload_ok stored_opks=0`.
- **`PreKeyLifecycleService`** (or its equivalent verify-republish coordinator) — the path that runs `verify_status` and decides whether to schedule a republish.
- **`MessagingService` bootstrap path** at the point where `bootstrap_start → upload_start → prekey_publish_start → upload_ok → bootstrap_done` is wired.
- **Unit tests** in `shared:core:messaging:jvmTest` reproducing (a) the race directly (b) the force-path bypass (c) the retry-after-fail behaviour.

Telemetry contract change is in-scope: the log keyword for a debounced republish must distinguish "actually stored" from "deferred under debounce". The previous `upload_ok stored_opks=0` was misleading.

---

## §3 — Out of scope

Deliberately not touched even if tempting:

- **Anything in `shared:core:transport`** — the gate, sticky window, runReconnectLoop, REST fallback orchestrator, migration. RC-RECONNECT-QUIESCENCE1 already owns that surface and is on hold awaiting this track.
- **Schema changes** — the storage layout for OPK / SPK / `opk_reservation` / `pending_ratchet_state` stays exactly as Sprint 2b left it. The fix is a coordination defect, not a storage one.
- **Sprint 2b OPK consume / two-phase promotion semantics.** Those are correct; the publish-side bug fires before any consume can happen.
- **Retry-budget tuning of any other HTTP path** (poll, send, ack, media). This track is exclusively about prekey publish.
- **A blanket audit of every `debounced` log in the codebase.** Other surfaces may also misleadingly log as success after a debounce, but they are deferred to a follow-up audit track per WORKING_RULES rule 7 ("new findings = log, not develop").
- **A field-smoke harness** that does not depend on a hand-operated baseline. The smoke instructions stay manual; this track makes the manual baseline reliable, it does not replace it with automation.

---

## §4 — Invariants

These are the hard guards every PR opened under this track must satisfy. Code review rejects the diff if any invariant is violated.

| ID | Invariant | What it forbids |
|---|---|---|
| **Inv-NoFalseSuccess** | A debounced prekey publish MUST NOT log `upload_ok` (or any other "success"-shaped telemetry value) while the in-flight publish that suppressed it has not yet returned. Either the log keyword distinguishes "deferred under debounce" from "stored", or the log is emitted only after the in-flight result is known. | The current `prekey_publish_debounced → upload_ok stored_opks=0` shortcut. |
| **Inv-RetryAfterFail** | If the in-flight prekey publish HTTP POST returns a hard failure (`ConnectException`, timeout, 5xx exhausted), AND a debounced republish was already requested while it was in flight, the failure MUST schedule a retry path that fires the previously debounced republish. The bundle MUST NOT stay missing from the relay because the gate marked the republish "done" while it was actually still pending. | A code path where `prekey_publish_fail_giving_up` is logged and no subsequent `upload_start` or `prekey_publish_start` fires for the same identity in the same app session, when `verify_status` had already detected the relay had no record. |
| **Inv-ForcePathOnZeroRecord** | When `verify_status` reports `spk_age_days=null AND opks_remaining=0` (the unambiguous "relay has no record yet" signal), the resulting republish path MUST bypass the debounce gate entirely. The race that motivates this track is specifically the case where the relay has zero record but the gate suppresses the republish — that case can never be the right one to debounce. | A code path where `verify_republish_triggered` for `spk_age_days=null opks_remaining=0` is silently dropped by `prekey_publish_debounced`. |
| **Inv-NoSpinningRetry** | The retry path from `Inv-RetryAfterFail` MUST honour the existing `attempt=N/3` budget on the wire-layer publish AND a separate per-identity per-session retry budget (initial value to be locked in implementation review). It MUST NOT busy-loop publish attempts. | Any code path that schedules `prekey_publish_start` more than the locked retry budget within one app session for one identity. |
| **Inv-NoBaselineMaskingByRegression** | The local sweep AND any unit test added for the race MUST be deterministic. The test MUST construct the exact `prekey_publish_start (in-flight) → verify_start → verify_republish_triggered → prekey_publish_debounced → upload_ok → prekey_publish_fail_giving_up` sequence and assert that, after the fail, a retry IS scheduled (and observable). A test that only checks "log line says deferred not stored" is not sufficient. | Any test that asserts log shape without asserting retry observability. |
| **Inv-NoRcCoupling** | Nothing in this track depends on, or is depended on by, `BuildConfig.RECONNECT_QUIESCENCE_ENABLED` / `MODE_2_FAST_PATH_ENABLED` / `MODE_2_STICKY_ENABLED`. The fix lands and is verifiable with all three release-flag pins at `"0"`. | Any wire-up that gates the fix behind the RC track's flag. |

---

## §5 — Test acceptance

PASS for this track requires ALL of:

1. **New `shared:core:messaging:jvmTest` (or `commonTest`) unit test** reproducing the race deterministically. The test fakes `PreKeyApi.publish` so the first invocation suspends (mid-flight), drives `verify_status` to return `spk_age_days=null opks_remaining=0`, observes `verify_republish_triggered` and the debounce, then resumes the first invocation with a `ConnectException`. After that, the test MUST observe a follow-up `prekey_publish_start` (or `upload_start`) for the same identity. Without the fix the test fails because no follow-up fires.
2. **New unit test for `Inv-ForcePathOnZeroRecord`** — the verify-driven republish under `spk_age_days=null opks_remaining=0` MUST bypass the debounce gate and reach the publish layer directly. Without the fix the test fails because the call ends in `prekey_publish_debounced`.
3. **New unit test for `Inv-NoSpinningRetry`** — the test runs the race repeatedly inside a virtual-time loop and asserts the per-identity per-session retry budget is honoured.
4. **Local jvmTest sweep** GREEN on the messaging module (`./gradlew :shared:core:messaging:jvmTest --no-daemon --max-workers=1 --rerun-tasks`).
5. **Local Android unit sweep** GREEN (`./gradlew :apps:android:testDebugUnitTest + :apps:android:assembleDebug + :apps:android:compileReleaseKotlinAndroid`).
6. **Android CI green** on the PR head before review.
7. **Field-smoke baseline replay** on the new APK: a fresh install of PHANTOM on two devices (Tecno BF7-12 + emulator, or Tecno + a second physical Android) with the same Wi-Fi network MUST allow bidirectional message exchange on the first 3 ping / 3 pong attempt without operator force-stop + restart on either device. The smoke captures full logcat on both sides for the bootstrap window and grep proves: `bootstrap_done` fires within 60 seconds of onboarding on both devices, AND `/prekeys/bundle/<peer>` reaches `200` on the first send attempt from each direction. No `404 — peer has not published yet` log lines in the baseline window.
8. **Only then** — the RC-RECONNECT-QUIESCENCE1 branch (#330) rebases on top of the new master, the APK is rebuilt with the three flags forced to `"1"`, and the Tele2 LTE smoke is retried per its existing template.

---

## §6 — Parking conditions

Two architectural failures on this track park it and force a redesigned restart from master, per WORKING_RULES rule 4.

- **Park A — debounce join introduces deadlock.** If the fix shape "debounce JOINS the in-flight publish and inherits its result" produces a reproducible deadlock between the publish lock and the verify-republish lock under contention (CI runner hang on the new unit test counts as a reproduction), the track parks. A redesign that drops the join and adopts the force-path-on-zero-record bypass plus an explicit retry queue would be the next attempt.
- **Park B — telemetry change breaks downstream parsers.** If introducing a new log keyword (e.g. `upload_deferred` instead of `upload_ok stored_opks=0`) breaks the relay-side analyzer or any in-app debug overlay that grep-parses publish telemetry, the track parks. The next attempt would either keep the old keyword but add a new `result=deferred` parameter, or update the analyzer in the same PR. The fix shape MUST NOT silently change a log line consumed by something else.

A third attempt on the same architectural shape after two parks is not allowed.

---

## §7 — Known limits

Honest limits that will be documented in the PR body and re-stated here so a future reader does not need to read the PR:

- **L-PrekeyFix-NoSendPathTouch.** This track does not change the send-side `SEND_TRACE prekey_fetch_*` semantics. If the relay is still 404 for legitimate reasons (peer offline, peer never installed), the send will still defer. The fix only guarantees that a peer who DID install and DID boot PHANTOM successfully ends up with their bundle on the relay.
- **L-PrekeyFix-NoBroaderDebounceAudit.** Other surfaces in the codebase that use a debounce + success-log pattern may have analogous races. They are deferred to a follow-up audit track (`docs/tracks/<name-tbd>` once opened).
- **L-PrekeyFix-NoCarrierRetrySemantics.** This track does not change behaviour on lossy carrier links (Tele2 LTE / МТС) outside the bootstrap window. Once the bundle is on the relay, future republishes (after OPK depletion, after SPK rotation) follow the existing path. If those paths have a similar race, that is a separate fix.
- **L-PrekeyFix-FieldSmokeRemainsManual.** This track restores the baseline so the existing manual smoke template (PR #330 evidence comment) is repeatable. It does NOT automate the field smoke. Automating it is a separate harness-track.

---

## §8 — Last hand-off (per WORKING_RULES rule 5)

- **Branch:** `docs/rc-prekey-publish-debounce-race-minilock` (this docs PR) — feature branch is NOT yet open per Rule 3 contract.
- **Master head at the time of writing:** `740c7c28`.
- **Field-smoke blocker evidence:** preserved on the operator workstation under `C:\temp\smoke-v2-20260625-1247\` — `tecno-full.log` (UTF-16, 17.6 MB), `emu-full.log` (UTF-8, 6.1 MB), `tecno-utf8.txt` (decoded), `baseline-blocker.txt` (architect verdict + timeline). Not committed to repo per the operator's discretion on smoke-artifact bulk.
- **Reference timeline** (replicated from `baseline-blocker.txt` for in-repo readability):

  ```text
  Emu identity ec44d78b5967bc1f...
    22:43:02.046  PREKEY_TRACE bootstrap_start
    22:43:02.555  PREKEY_TRACE upload_start opks=40 spk_key_id=...
    22:43:02.798  PREKEY_TRACE prekey_publish_start native=true attempt=1/3  <-- HTTP POST in flight
    22:43:16.615  PREKEY_TRACE verify_start                                    <-- 14 s later, background verify fires
    22:43:16.761  PREKEY_TRACE http_status_done status=200
    22:43:16.763  PREKEY_TRACE verify_status spk_age_days=null opks_remaining=0
    22:43:16.763  PREKEY_TRACE verify_republish_triggered -- relay has no record
    22:43:16.859  PREKEY_TRACE upload_start identity=...                       <-- republish attempt
    22:43:16.859  PREKEY_TRACE prekey_publish_debounced                        <-- DEBOUNCED by in-flight POST
    22:43:16.860  PREKEY_TRACE upload_ok stored_opks=0 elapsedMs=1             <-- FALSE SUCCESS LOG
    22:43:23.824  PREKEY_TRACE prekey_publish_fail_giving_up                   <-- 7 s after debounce, original POST fails
                  last_exception=ConnectException:
                    Failed to connect to relay.phntm.pro/65.108.154.152:443
                    ECONNREFUSED
    22:43:23.825  PREKEY_TRACE upload_fail ConnectException
    (no further publish attempts in the rest of the session)
  ```

- **Next decision (this PR is merged → next steps):** Open the feature branch `feat/rc-prekey-publish-debounce-race` off the freshly-merged master. Implement the three invariants Inv-NoFalseSuccess + Inv-RetryAfterFail + Inv-ForcePathOnZeroRecord with the unit tests from §5 items 1–3. After local sweeps + CI green, run the §5 item 7 field-smoke baseline replay. After that passes, the operator rebases the RC-RECONNECT-QUIESCENCE1 branch on the new master and the Tele2 LTE smoke is retried.
