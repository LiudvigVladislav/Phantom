# Track: PR-CRYPTO-INBOUND-X3DH-REPAIR1 — inbound counterpart to PR-CRYPTO-SESSION-REPAIR1 (#243)

**Status:** queued (mini-lock authored before code per `docs/WORKING_RULES.md` rule 3).
**Branch (not yet opened):** `feat/pr-crypto-inbound-x3dh-repair1` (cut fresh from master AFTER this mini-lock merges).
**Layer:** crypto / receive path inside `DefaultMessagingService.handleDeliver`. **No UI, no transport, no DB schema change.**
**Authored:** 2026-05-29 (post Test #83 v2 BLOCKED diagnosis on `feat/pr-ui-chat-new-msg-chip1` branch — UI verification could not complete because the receive path silently drops fresh-X3DH repair envelopes from peers whose outbound side has correctly executed PR #243 commit 4).
**Architect:** Vladislav-forwarded crypto/session architect verdict 2026-05-29 evening (transcript in chat); verbatim scope reproduced in §Goal + §Scope below.
**Dependency for:** **PR-UI-CHAT-NEW-MSG-CHIP1 (queue position #3, formally BLOCKED by this track per Test #83 v2 logcat evidence at `C:\temp\test83-v2-tecno.log` + `C:\temp\test83-v2-emu.log`).** CHIP1 branch parked at `origin/feat/pr-ui-chat-new-msg-chip1` with 3 unmerged commits (`4b5b2490`, `a1092023`, `8db77f24`). After this track ships, CHIP1 rebases onto fresh master, rebuilds APK, reruns Test #83.

## Empirical evidence — Test #83 v2 logs (2026-05-29)

### Bidirectional MAC-fail picture

| When | Side | msgId | x3dhInitPresent | result |
|---|---|---|---|---|
| 06:45:18 (early) | Tecno | `3e2969f4` | false | DECRYPT_TRACE ok ✅ |
| 06:45:18 (early) | Tecno | `78f99fc6` | false | DECRYPT_TRACE ok ✅ |
| 12:08:15 | Emu | `cd246cbe` (sealed read receipt from Tecno) | false | fail_mac × 2, action=hold ❌ |
| 12:08:16 | Emu | `dbdd8a05` (sealed read receipt from Tecno) | false | fail_mac × 2, action=hold ❌ |
| 12:08:43 | Emu | — | — | **#243 commit 4 fires: `repair_armed trigger=outbound_send` → `bootstrap_init_ok` → `repair_done`** ✅ |
| 12:08:44 | Emu | `cd246cbe` / `dbdd8a05` | (held) | `replay_loop_start count=2` → `replay_fail × 2` (correct: held envelopes were encrypted under Tecno's stale state, emu's fresh-X3DH state cannot decrypt them — anti-loop guarantee from #243 commit 5 preserved) |
| 07:07:56 | **Tecno** | `4eb6d2d7` (emu's fresh-X3DH outbound carrying x3dhInit) | **true** | **fail_mac × 2, action=hold** ❌ |
| 07:09:11 | Tecno | `1ae7218e` | false | fail_mac × 2, action=hold ❌ |
| 07:10:35 | Tecno | `d4ed789b` | false | fail_mac × 1, action=hold ❌ |

The critical row is `4eb6d2d7`: emu correctly sent a fresh-X3DH repair envelope (PR #243 commit 4 working as designed), but Tecno's receive path **ignored the `x3dhInit` payload** and tried to decrypt under its stale session anyway, producing MAC fail → hold. **PR #243 wired only the outbound half; the inbound counterpart was never built.**

### Code grep — root cause line-verified

`shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultMessagingService.kt:2328-2332`:

```kotlin
if (state != null) {
    // Existing session — decrypt directly. Any x3dhInit /
    // signing pubkey that came with this frame are
    // re-bootstraps; ignored when we already hold a
    // session, the peer simply pays a few wasted bytes.
```

This comment is the **self-documenting confirmation** of the gap. The receive path explicitly chooses to ignore `wireFrame.x3dhInit` when an existing session is loaded — which is exactly the wrong choice when the existing session is the stale one and the inbound x3dhInit is the peer's repair hint.

The MAC-failure branch immediately below (line 2384 `catch (e: IllegalArgumentException)` for MAC / verification) currently has TWO arms:
- Lines 2423-2484: **hold** (debug/beta, `holdMacFailures && repo != null`)
- Lines 2486+: **release/ack** (existing ADR-012 destructive ack path)

This track inserts a THIRD arm BEFORE both of those, gated on `wireFrame.x3dhInit != null`, that attempts fresh recipient-side bootstrap from the inbound x3dhInit before falling through to hold.

## Goal

> *(Verbatim architect summary, 2026-05-29 evening.)*
>
> Pause PR-UI-CHAT-NEW-MSG-CHIP1 as blocked. Create a separate crypto/session-repair PR that handles incoming fresh X3DH repair envelopes when the receiver already has a stale/broken session. After this PR is merged/rebased into CHIP1, rebuild APK and rerun Test #83.

## Scope

### In scope (this PR only)

> *(Verbatim architect "Key Changes" + grep-verified insertion points + commit-ordering safety pattern from PR #243 commit 5b.)*

1. **In `DefaultMessagingService.handleDeliver`**, when `sessionExists=true`, `wireFrame.x3dhInit != null`, and decrypt under the existing ratchet fails with MAC:
   - attempt recipient-side X3DH bootstrap from that `x3dhInit`;
   - decrypt the same envelope under the freshly-derived state;
   - if successful: commit the new ratchet state, process payload normally, `markProcessed PROCESSED`, ack, and **do not hold** the envelope.

   **Insertion point:** inside `catch (e: IllegalArgumentException)` at `DefaultMessagingService.kt:2384` (MAC / verification check), **BEFORE** the existing hold branch `if (holdMacFailures && decryptFailedEnvelopeRepository != null)` at line 2423. Pseudocode skeleton:

   ```kotlin
   } catch (e: IllegalArgumentException) {
       if (/* MAC */) {
           val x3dhInit = wireFrame.x3dhInit
           if (x3dhInit != null) {
               // PR-CRYPTO-INBOUND-X3DH-REPAIR1 — try fresh
               // recipient bootstrap from the inbound x3dhInit
               // BEFORE falling through to hold. The candidate
               // bootstrap MUST NOT save_session before decrypt
               // succeeds (see § Scope item 5 — OLD RATCHET
               // SESSION MUST BE PRESERVED on failure).
               val repairResult = runCatching {
                   val candidate = sessionManager.recipientBootstrapInMemory(
                       conversationId = conversationId,
                       x3dhInit       = x3dhInit,
                       // ... senderPubKey, signingPubKey, etc.
                   ) // throws typed exception on failure, never returns null
                   val (newState, decrypted) = ratchet.decrypt(candidate, encrypted)
                   newState to decrypted
               }
               repairResult.onSuccess { (newState, decrypted) ->
                   sessionManager.saveSession(conversationId, newState)
                   messagingLog(INFO,
                       "DECRYPT_TRACE inbound_repair_ok msgId=… conv=… " +
                       "bootstrap=true plaintextBytes=${decrypted.size} elapsedMs=…")
                   processedEnvelopeRepository?.markProcessed(...)
                   // continue with normal payload processing path
                   //   (insertMessage, conversation upsert, emit, notify, ack)
                   return@withLock decrypted
               }
               repairResult.onFailure { err ->
                   messagingLog(WARN,
                       "DECRYPT_TRACE inbound_repair_fail msgId=… conv=… " +
                       "errorClass=${err::class.simpleName} action=fall_through_to_hold")
                   // intentional fall-through to existing hold branch below
               }
           }
           // EXISTING hold branch below — UNCHANGED.
           if (holdMacFailures && decryptFailedEnvelopeRepository != null) {
               // … existing PR #243 commit 3a code …
           }
           // EXISTING release/ack branch below — UNCHANGED.
           …
       }
   }
   ```

2. **Do not use this path for frames without `x3dhInit`.** The existing `fail_mac action=hold` behaviour for non-x3dhInit MAC failures remains identical (PR #243 commit 3a contract preserved). The fix is strictly **additive** — a new repair attempt INSIDE the MAC-failure branch, BEFORE the hold path, only for frames carrying a repair hint.

3. **Safe bootstrap commit ordering in `SessionManager`.** Derive the candidate recipient-bootstrap state in memory WITHOUT overwriting the existing session first; only `saveSession` the new/advanced session AFTER decrypt under the candidate succeeds. Same pattern as PR #243 commit 5b for the replay loop (`docs/tracks/crypto-session-repair.md` § "commit 5b Replay Ratchet Commit Ordering"): the ratchet is committed ONLY after the durable success.

   - **New `SessionManager` API surface — do NOT return `RatchetState?`.** Use a name like `deriveRecipientBootstrapCandidate(...)` or `recipientBootstrapInMemory(...)`. **Return is `RatchetState`** (non-nullable) — derivation either succeeds and returns the candidate, OR throws a typed exception (e.g. `InboundBootstrapFailedException` carrying a cause). Pick one approach; a nullable return erases the `errorClass` we need for the `DECRYPT_TRACE inbound_repair_fail errorClass=…` log line and forces the caller to log a meaningless `Unknown`. Typed exception preserves diagnostic specificity (`X3DHException`, `Ed25519VerificationException`, `IllegalArgumentException`, etc.) all the way to the log.
   - Existing `recipientHandshake4DH(...)` / `saveSession(...)` continue to work unchanged for the existing no-session bootstrap path. New method is additive; no signature change on existing methods.
   - **No SQL schema change.** The candidate state lives in memory across the decrypt attempt; on success, the existing `ratchetStateRepository.upsertRatchetState(conv, json)` writes it.

4. **If fallback bootstrap or candidate-decrypt fails**, preserve the old session, **do not ack** in debug/beta, and keep the existing hold/suspect path. The fix degrades gracefully to today's behaviour when the repair hint is also bad.

5. **OLD RATCHET SESSION MUST BE PRESERVED on candidate bootstrap / candidate-decrypt failure.** This is the central invariant of this PR. Specifically:
   - The new in-memory path MUST NOT touch `ratchetStateRepository.upsertRatchetState(conv, …)` BEFORE the candidate-decrypt succeeds.
   - The existing on-disk session row for `conv` must remain identical until the candidate-decrypt succeeds, at which point a single `sessionManager.saveSession(conv, newAdvancedState)` commits the FRESH state derived from the candidate's post-decrypt advance.
   - If the candidate decrypt throws (candidate bootstrap was wrong, OR x3dhInit was forged/replayed, OR ratchet derivation diverged), the on-disk row is byte-identical to its pre-receive content, and the receiver can keep trying its old session against future incoming envelopes that don't carry x3dhInit (the existing PR #243 commit 3a hold path takes over for THIS envelope).
   - **OPK consumption is left as an implementation decision at commit-1 review** (eager-consume-on-candidate-derive vs lazy-consume-on-decrypt-success vs reserved-only-not-consumed). Whichever model is chosen, this preservation invariant on the *ratchet session row* must hold regardless. OPK lifecycle is its own follow-up if it surfaces a separate issue.

6. **Mutex serialization.** The repair attempt runs INSIDE the same per-conversation mutex (`mutexFor(conversationId)`) the existing `handleDeliver` MAC-failure branch already holds. No concurrent ratchet races. Same architect pre-decision #1 from PR #243.

7. **Anti-loop invariant 4 preserved.** The new inbound-repair branch ITSELF MUST NOT call `setSessionSuspect`. If it fails (candidate bootstrap throws OR candidate decrypt throws MAC again), control falls through to the existing PR #243 commit 3a hold branch unchanged — which may set suspect on a fresh hold-table insertion exactly as it does today. This PR does NOT change suspect semantics in either direction; it only inserts a repair attempt BEFORE the hold branch when an x3dhInit is present.

### Out of scope (deferred to other tracks)

- ❌ **CHIP1 UI** (`feat/pr-ui-chat-new-msg-chip1`). Stays UI-only. Resumes after this PR merges.
- ❌ **Transport WS ping-timeout / `ws_alive_60s` race.** Captured in the CHIP1 hand-off as observation #1; future track `PR-WS-HEALTH-STATE1`. Architect verdict 2026-05-29: "unless it blocks delivery after crypto repair is fixed."
- ❌ **Outbound `repair_armed` semantics** (PR #243 commit 4). Already working — verified by the emu side of Test #83 v2 logs.
- ❌ **Replay loop semantics** (PR #243 commits 5 / 5a / 5b / 6). Already working — verified by emu side `replay_loop_start count=2 → replay_fail × 2` with anti-loop preserved.
- ❌ **Group chat receive path** (`GroupMessagingService`). 1:1 only, same as PR #243.
- ❌ **iOS** (no native iOS port yet).
- ❌ **DB / SQL / schema migration.** This track is a pure receive-path code change. The candidate state lives in memory; the existing `ratchetStateRepository.upsertRatchetState` persists it on success.
- ❌ **Decrypt errors other than MAC.** Same scope discipline as PR #243.

## Implementation plan (sketched; refine per architect pre-decisions before commit 1)

- **Commit 1 — `SessionManager.tryRecipientBootstrapInMemory` API surface (no behaviour change).**
  Add the in-memory bootstrap method. Return type: `RatchetState?` (null on bootstrap failure). Existing call sites that use `recipientHandshake4DH + saveSession` continue to compile + behave identically. Tests added for the in-memory variant against the same test bundle that PR #243 commits 4 + 5 used (`PreSeededRatchetStateRepository`, `MacFailingDoubleRatchet`, `PassthroughDoubleRatchet`, `FakeDecryptFailedEnvelopeLedger`).

- **Commit 2 — Receive-path repair branch in `DefaultMessagingService.handleDeliver`.**
  Insert the new `if (x3dhInit != null) { tryInboundRepairDecrypt(...) }` block per the pseudocode skeleton above, BEFORE the existing `holdMacFailures` branch. New `DECRYPT_TRACE` log keys per § Logs below. Strict order: derive candidate → decrypt under candidate → on success, save + markProcessed + ack + return decrypted → on failure, fall through to existing hold path (no setSessionSuspect from inbound side).

- **Commit 3 — Tests.**
  Per architect-supplied test plan reproduced in § Test plan below. At minimum: 4 new tests in `DefaultMessagingServiceTest.kt` (success path, fallback-fails-then-hold path, no-x3dhInit-keeps-old-hold path, no-session-bootstrap-path-unchanged path) + run full DMS + storage suites.

(If the architect wants per-commit ACK like PR #243 was reviewed, expand to 2a / 2b / 2c. If single ACK at end per CHIP1's B1 path is fine, one merged commit per logical step is enough. Decide at pre-decision review before commit 1.)

## Test plan

> *(Verbatim architect test plan + run-the-existing-suite item.)*

- **Unit test:** existing stale session + incoming `x3dhInit` + old-session MAC fail → fallback bootstrap succeeds → message inserted, envelope acked, no held row.
- **Unit test:** existing stale session + incoming `x3dhInit` but fallback bootstrap/decrypt fails → old session preserved, held row created, no ack.
- **Regression:** existing session + no `x3dhInit` + MAC still follows current hold path (PR #243 commit 3a contract).
- **Regression:** no-session bootstrap path still works (`if (state == null)` branch in `handleDeliver`).
- Run `:shared:core:messaging:jvmTest` `DefaultMessagingServiceTest` (target 52 tests, 0 failures — current is 48 from PR #243, +4 new).
- Run `:shared:core:storage:jvmTest` (regression check — 52 tests, 0 failures).
- Run `:apps:android:assembleDebug` (verify AppContainer wiring still compiles).

## Acceptance

> *(Verbatim architect acceptance + the Test #83 unblock step.)*

1. **Rebuild APK** from this PR's branch after merge into master.
2. **Rebase `feat/pr-ui-chat-new-msg-chip1`** onto fresh master (CHIP1 branch carries 3 commits `4b5b2490`/`a1092023`/`8db77f24`; trivial rebase, no conflicts expected since this track touches only `DefaultMessagingService.handleDeliver` and CHIP1 touches only `apps/android/.../chat/*.kt`).
3. **Rerun Test #83** (10 scenarios per `docs/tracks/chat-new-msg-chip.md` § "Test acceptance"). Expected logs on Tecno for first manual message from emulator:
   - `DECRYPT_TRACE attempt msgId=<8> sender=<8> conv=<8> sessionExists=true x3dhInitPresent=true`
   - `DECRYPT_TRACE inbound_repair_ok msgId=<8> conv=<8> bootstrap=true plaintextBytes=<n>` (or equivalent repair marker — exact key locked at commit 2 review)
   - `CHAT_CHIP incoming conv=<8> count=1` when scrolled up
4. **ONLY AFTER Test #83 passes**: resume CHIP1's final anti-pattern grep + architect ACK (B1 path).
5. **CHIP1 PR opens** with PR body recording "first Test #83 BLOCKED by transport (observation #1), second BLOCKED by inbound crypto gap closed by this PR (#NNN `<sha>`), third run PASS".

## Logs

New `DECRYPT_TRACE` keys to add (consistent with PR #243 commit 2 observability):

```
DECRYPT_TRACE inbound_repair_armed   msgId=<8> conv=<8> x3dhInitPresent=true reason=fail_mac_existing_session
DECRYPT_TRACE inbound_repair_ok      msgId=<8> conv=<8> bootstrap=true plaintextBytes=<n> elapsedMs=<n>
DECRYPT_TRACE inbound_repair_fail    msgId=<8> conv=<8> errorClass=<…> action=fall_through_to_hold
```

All under `PhantomMessaging` tag (same as PR #243).

## Assumptions

> *(Verbatim architect assumptions.)*

- This must be a **separate crypto PR**, not part of CHIP1.
- Transport WS ping timeout remains a separate follow-up (`PR-WS-HEALTH-STATE1`) unless it blocks delivery after crypto repair is fixed.

## Process gates

- **WORKING_RULES rule 8** (transport regression gate): **carve-out applies** — crypto-only PR, zero transport touch. Wi-Fi sufficient for any device verification.
- **WORKING_RULES rule 9** (no merge without verification): standard path — either explicit architect PR-thread ACK on each commit (PR #243 model) or grep-verified evidence in PR body per claim. Given the scope is tightly bounded by this mini-lock + architect-supplied test plan, single ACK at end likely sufficient. Pre-decision at first commit review.
- **`feedback_verify_architect_claims_2026_05_27.md`**: this mini-lock was authored AFTER Claude grep-verified the architect's central claim (`DefaultMessagingService.kt:2328` literal comment "Any x3dhInit … ignored when we already hold a session"). The verification iteration is itself the working pattern — don't trust line-number claims without grep, regardless of who is making them.
- **`feedback_session_close_discipline.md`**: this mini-lock is the durable session-close artifact for the CHIP1 BLOCKED diagnosis. The CHIP1 hand-off note edit currently lives in the working tree on `feat/pr-ui-chat-new-msg-chip1` as unstaged change (per Vladislav 2026-05-29: "Если edit уже в working tree, пусть остаётся unstaged до отдельного решения") — to be committed in a separate decision once this PR ships.

## Last hand-off

(empty — track queued, awaiting Vladislav greenlight on this mini-lock before code begins)
