# Track: PR-CRYPTO-SESSION-REPAIR1 — Double Ratchet MAC-error session repair

**Status:** queued (mini-lock authored before code per `docs/WORKING_RULES.md` rule 3). Inserted at the HEAD of the Android Stabilization Sprint queue (before resuming `PR-UI-CHAT-NEW-MSG-CHIP1`) after PR-RECV-DIAG1 (#234 `684c2be6`) shipped 2026-05-27 and Test #86 exposed the underlying crypto-layer hazard.
**Branch (not yet opened):** `feat/pr-crypto-session-repair1` (cut fresh from master AFTER this mini-lock merges).
**Layer:** Android + shared crypto/messaging only. NO UI, NO transport, NO relay.
**Authored:** 2026-05-27.

## Problem

After several `force-stop` / install-debug cycles on Tecno, the local Double Ratchet state drifts out of sync with the relay's view of the session. Symptoms observed during PR-RECV-DIAG1 Test #86:

1. Incoming envelope arrives via Direct WS.
2. `DefaultMessagingService.handleDeliver` calls the ratchet → `Permanent decrypt failure (MAC error)`.
3. `handleDeliver` proceeds to call `transport.ackDeliver(envelopeId)` — the relay drops its copy.
4. The H2b idempotent ledger (`processedEnvelopeRepository.markProcessed`) records the envelope as "processed", so any future redelivery would be skipped on the ledger gate before re-attempting decrypt.
5. The message silently never reaches the UI. There is no notification, no failed-bubble, no diagnostic surface.

The only working remediation today is `pm clear phantom.android` — full local wipe. That destroys all session keys, prekeys, ratchet state, and message history. For a release this is a non-starter.

This is a release blocker for Alpha 2. On aggressive-OEM Android (Tecno HiOS / Xiaomi HyperOS / etc.) the OS can `force-stop` the app at will, and any cycle that lands the local state ahead of the remote's reproduces the same loss.

## Goal

Replace the "ack on MAC error" silent loss with a non-destructive session repair path. Specifically:

1. Stop ack-deliver-on-MAC-error in debug/beta builds — hold the envelope in the ledger as `decrypt_failed_mac` instead of marking it processed.
2. Surface MAC errors in logcat with enough state to debug the next reproducer without SSH-correlating to relay logs.
3. Add a session-repair path that can mark a session as suspect, optionally reset the local ratchet chain, and force a fresh X3DH handshake on the next outgoing message.
4. Add a non-destructive "repair without wipe" capability so the user does not lose history.

## Architect-designed 4-step plan

Architect-confirmed sequence (Vladislav-relayed 2026-05-27). All four steps in this PR — they are coupled and shipping any one alone leaves the path incomplete.

### Step 1 — do NOT ack-deliver on MAC error in debug/beta

`DefaultMessagingService.handleDeliver` currently calls `transport.ackDeliver(...)` in all branches that finish processing, INCLUDING the MAC-error branch. The fix:

- On `Permanent decrypt failure (MAC error)` and similar terminal decrypt errors:
  - DO NOT call `transport.ackDeliver(...)`.
  - DO NOT call `processedEnvelopeRepository.markProcessed(envelopeId)`.
  - Instead, write a new row into a `decrypt_failed_envelopes` table (forward-only SqlDelight migration; same shape as `processed_envelope_ledger` plus `error_type`, `received_at_ms`, `sender_identity`, `conversation_id`).
  - The relay will retry delivery on the next session. If the session-repair path in Step 3 has executed by then, decrypt succeeds and normal handling resumes.

- Gate: `BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "beta"`. Production release build retains current ack-deliver behaviour pending field telemetry from beta. (Locked because Vladislav explicitly does not want the current alpha userbase to suddenly start seeing failed-decrypt-loop UX without the repair path being battle-tested first.)

### Step 2 — DECRYPT_TRACE diagnostic logs

Add structured logging under tag `PhantomMessaging` (consistent with `RECV_DIAG` from PR-RECV-DIAG1) at every decrypt-related decision point:

```
DECRYPT_TRACE attempt        msgId=<short> sender=<short> conv=<short> sessionState=<rk_idx,ck_idx> x3dhInit=<present|absent>
DECRYPT_TRACE ok             msgId=<short> sender=<short> conv=<short> elapsedMs=<n>
DECRYPT_TRACE fail_mac       msgId=<short> sender=<short> conv=<short> sessionState=<rk_idx,ck_idx> action=<hold|ack>
DECRYPT_TRACE fail_other     msgId=<short> sender=<short> conv=<short> errorClass=<...> action=<hold|ack>
DECRYPT_TRACE session_suspect msgId=<short> conv=<short> reason=<repeated_mac|mac_after_x3dh|...>
DECRYPT_TRACE repair_armed   conv=<short> trigger=<inbound_mac|outbound_send>
DECRYPT_TRACE repair_done    conv=<short> elapsedMs=<n>
```

Short ids = first 8 chars. `sessionState` should expose the Double Ratchet's `root_key_index` + `chain_key_index` (whatever the libsignal binding exposes — if not directly accessible, log a hash of the current root key + chain key bytes so divergence is visible across two logs).

`elapsedMs` on `ok` lets us spot decrypt slowness in the field (it's near-zero today but useful as a regression detector).

### Step 3 — session-repair path

When a session is marked suspect (two MAC errors within a short window, OR a MAC error on the first envelope after a presumed-fresh X3DH init):

1. Mark the conversation's session as `suspect` in a new column on the existing session table (`conversations` or wherever Double Ratchet state lives).
2. On the NEXT outgoing message in that conversation, before encrypting, the sender:
   - Skips the existing ratchet chain.
   - Generates a fresh X3DH initial message using the receiver's published bundle (fetched fresh from `/prekeys/get/<identity>`).
   - Sends the new init as the outgoing payload.
   - Persists the new ratchet state, clears the `suspect` flag.
3. On the receiver side, an X3DH init message resets the local ratchet for that conversation as it already does today — no receiver-side code change beyond the held-envelope retry path in Step 4.

This is the "repair without wipe" capability: history stays, contact stays, but the session keys are fresh. The cost is one extra round-trip on the next outgoing send in a suspect conversation, no user-visible action required.

### Step 4 — held-envelope retry on repair

When Step 3 completes (`repair_done`), iterate `decrypt_failed_envelopes` for the conversation:

- For each held envelope, re-attempt decrypt with the new session.
- On `ok`: process normally (insertMessage / bubble / notification), then `markProcessed` + delete from `decrypt_failed_envelopes`.
- On `fail` again: leave in `decrypt_failed_envelopes`, log `DECRYPT_TRACE replay_fail`. Do NOT loop the repair — Step 3 only re-fires on a NEW MAC error from a freshly arrived envelope.

24h TTL on `decrypt_failed_envelopes` to avoid unbounded growth on a chronically-broken contact (same TTL pattern as `voice_chunks`).

## In scope (this PR only)

1. `DefaultMessagingService.handleDeliver` — branch on decrypt result, choose `hold` (debug/beta) or `ack` (release) action.
2. New SqlDelight table `decrypt_failed_envelopes` + forward-only migration.
3. New repository `DecryptFailedEnvelopeRepository` in `shared/core/messaging`.
4. `DefaultMessagingService.sendMessage` — before encrypt, check `conversations.session_suspect`; if set, take the repair path (Step 3).
5. New helper `SessionRepairService` (or extension on `MessagingService`) — owns the suspect-marking heuristic, the fresh X3DH fetch+send, the post-repair held-envelope replay.
6. `DECRYPT_TRACE` log lines per Step 2.
7. Unit tests in `shared/core/messaging/src/commonTest`:
   - `decrypt_mac_error_does_not_ack` (debug build only — gate the test on `BuildConfig.DEBUG`).
   - `repair_marks_session_then_fresh_x3dh_on_next_send`.
   - `held_envelopes_replayed_after_repair`.
   - `replay_fail_leaves_envelope_in_table`.
   - `ttl_24h_evicts_old_held_envelopes`.
8. Integration test on `androidTest` — full force-stop / inject corrupt ratchet / deliver envelope → verify HOLD path + verify next-send-fresh-X3DH + verify replay.

## Out of scope (NOT in this PR)

- ❌ UI: no badge, no "fixing connection..." overlay, no toast. Repair is silent from the user perspective. If a UI signal is wanted later, separate PR.
- ❌ Production-release ack-on-MAC behaviour change. Stays current until beta telemetry proves the held path safe.
- ❌ Relay-side anything. Relay protocol unchanged. The repair path uses existing `/prekeys/get/<identity>` + standard send.
- ❌ Group chat repair. 1:1 first; groups have their own session model and need a separate design pass.
- ❌ Decrypt errors other than MAC error. Other errors (corrupted ciphertext, malformed protobuf) get logged via `DECRYPT_TRACE fail_other` but stay on the existing ack path — they don't indicate session-state drift.
- ❌ Cross-device session sync. Multi-device is not in Alpha-1/Alpha-2 surface.
- ❌ Sealed-sender repair semantics. Sealed-sender envelopes that fail MAC will use the same path — but the architectural design above assumes the sender-identity hint inside the unsealed sealed-sender layer is enough to identify the conversation; verify on first dry-run.

## Anti-pattern signatures — verify NOT present in the diff

- ❌ `transport.ackDeliver(...)` called inside any branch downstream of a MAC-error path in debug/beta builds.
- ❌ `processedEnvelopeRepository.markProcessed(envelopeId)` called inside any branch downstream of a MAC-error path in debug/beta builds (would cause the ledger to skip the relay's redelivery before repair has a chance to land).
- ❌ Infinite repair loop — `Step 3` must NOT re-fire from inside `Step 4` replay-fail.
- ❌ User-visible UI change. Search the diff for any compose/screen file edit; flag as out-of-scope.
- ❌ Relay-side code change. Search the diff for any `services/relay` edit; flag as out-of-scope.
- ❌ "Fix while we're here" cleanups outside the four steps above.

## Test acceptance (Test #87 — Vladislav-finalize before APK)

Must PASS on Tecno real device + emulator pair:

1. **Cold install on both, exchange 5 messages each way → verify normal path unaffected.** No `DECRYPT_TRACE fail_*` lines. All messages visible.
2. **Force-stop Tecno → re-launch → emu sends 1 message → expect `DECRYPT_TRACE attempt` + either `ok` (lucky case) or `fail_mac action=hold`.** If hold, message stays invisible (expected).
3. **From the held-state, Tecno sends 1 outgoing → expect `DECRYPT_TRACE repair_armed trigger=outbound_send` + fresh X3DH fetch + send → emu decrypts as fresh-session init → emu sends 1 reply → Tecno `DECRYPT_TRACE repair_done` → Tecno `DECRYPT_TRACE replay_ok` on the previously-held envelope → BOTH messages appear in Tecno UI in correct chronological order.**
4. **Re-do force-stop cycle 3× in sequence → verify repair fires each time and no envelopes are lost** (Step 3 is idempotent).
5. **Trigger the chronic-bad-contact case** (mock the X3DH init to also fail) → `DECRYPT_TRACE replay_fail` per attempt, NO infinite repair loop, NO crash, envelope stays in `decrypt_failed_envelopes`.
6. **24h TTL eviction** (mock `received_at_ms` back > 24h) → entry deleted on next sweep.
7. **Release build (`assembleRelease` if signing available, otherwise `assembleBetaRelease`)** → ack-on-MAC behaviour preserved. Verify by reading the compiled bytecode / build flavour config, not by running (we don't want to corrupt prod state for a test).

## Parking conditions

Stay in track if:
- DECRYPT_TRACE field naming needs adjustment — change within the PR.
- SessionRepairService split off into a separate file vs extension function — minor refactor decision within the PR.
- 24h TTL needs to be 12h or 48h based on Test #87 — tune within the PR.

Escalate to a NEW track only if:
- libsignal Kotlin binding does NOT expose enough session state to log `sessionState=<rk_idx,ck_idx>` and the workaround (hashing root key bytes) is also blocked — separate diagnostic PR with libsignal upstream investigation.
- The mocked-fail-X3DH test in acceptance #5 reveals an actual loop-able path — STOP, redesign.
- Sealed-sender + MAC-error has fundamentally different identity semantics than assumed in "Out of scope" — defer to a follow-up PR with proper sealed-sender repair design.

## Logs

```
DECRYPT_TRACE attempt        msgId=<8> sender=<8> conv=<8> sessionState=<rk,ck> x3dhInit=<bool>
DECRYPT_TRACE ok             msgId=<8> sender=<8> conv=<8> elapsedMs=<n>
DECRYPT_TRACE fail_mac       msgId=<8> sender=<8> conv=<8> sessionState=<rk,ck> action=<hold|ack>
DECRYPT_TRACE fail_other     msgId=<8> sender=<8> conv=<8> errorClass=<...>  action=<hold|ack>
DECRYPT_TRACE session_suspect conv=<8> reason=<repeated_mac|mac_after_x3dh|...>
DECRYPT_TRACE repair_armed   conv=<8> trigger=<inbound_mac|outbound_send>
DECRYPT_TRACE repair_done    conv=<8> elapsedMs=<n> replayed=<n>
DECRYPT_TRACE replay_ok      msgId=<8> conv=<8>
DECRYPT_TRACE replay_fail    msgId=<8> conv=<8> errorClass=<...>
DECRYPT_TRACE ttl_evict      msgId=<8> conv=<8> ageMs=<n>
```

All under PhantomMessaging tag.

## Last hand-off

(empty — track queued, awaiting Vladislav greenlight on this mini-lock before code begins)
