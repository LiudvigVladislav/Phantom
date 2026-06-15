# Sprint 2b — OPK lifecycle hardening + pending/active session state machine (scope-lock)

**Status:** Locked scope mini-lock for Sprint 2b. No code written. This document is the binding contract for the implementation PRs that follow it.

**Why this PR exists.** The 2026-06-15 integration LTE smoke captured `errorClass=OpkNotFound action=fall_through_to_hold → fail_mac action=hold` on the Tecno after a Sprint 2a-guard emulator reply. Two Council rounds (Round 1 with 4 lenses + 1 cross-check, Round 2 with 4 fresh lenses + 1 cross-check) followed by a bounded Round 3 server-contract investigation confirmed the root cause: a **publish-snapshot consistency gap** in `PreKeyApiClient.publishWithRetry`. The serialized request body is captured ONCE before the retry loop and reused identically across all attempts; a retry attempt succeeding server-side AFTER a local OPK consume restores the relay-side bundle to a stale 40-OPK set that includes the already-consumed OPK; a later bundle fetch hands that OPK to a peer; the peer's `x3dhInit` references it; the local lookup returns `null`.

The original 2026-06-15 PR #313 lock mandated **OPK lifecycle / consume / idempotency / restart-resilience** as a Sprint 2b inclusion. The Council Round 2 confirmed this remains binding. Sprint 2b therefore folds two distinct fixes into a single locked scope: the publish-snapshot consistency fix that closes the observed smoke, AND the pending/active session state machine + two-phase OPK consume that closes the broader OPK lifecycle / restart-resilience axis.

**Durable trail outside the repo** (Council artifacts referenced from this scope-doc):

- `C:\temp\trek2-stage2b-b-d-integration-smoke-2026-06-15\` — the smoke that produced the field signal (findings.md + tecno-lte-C0.log + emu.log + relay.log)
- `C:\temp\sprint-2b-reconnaissance-2026-06-15\reconnaissance.md` — code map (7-point answer + hypothesis tree + Council anchors)
- `C:\temp\sprint-2b-reconnaissance-2026-06-15\root-cause-confirmed.md` — timeline evidence + C-1 / BS-4 resolution
- `C:\temp\sprint-2b-council-layer1-2026-06-15\` — Round 1 reports + Layer 2 Ruflo verdict (mostly downgraded by Round 2 evidence)
- `C:\temp\sprint-2b-council-layer1-round2-2026-06-15\` — Round 2 reports + Layer 2 Ruflo verdict + Round 1 → Round 2 delta synthesis

## Server-contract pins (Round 3 bounded investigation, master `9f730730`)

The Sprint 2b implementation PRs MUST NOT widen, narrow, or reinterpret any of the following server-side contracts. If a future server change drifts any of these, this scope is invalidated and must be re-locked.

1. **`/prekeys/publish` replaces the OPK pool wholesale on each publish.** The relay's `prekeys::publish` at `services/relay/src/prekeys.rs:273-276` assigns `prev.one_time_prekeys = dedup_and_cap_opks(opks)`, comment-anchored as "OPK pool is REPLACED on each publish, not merged: the client owns its OPK lifecycle and a publish is the canonical 'here is my current pool' statement." The `PublishRequest` KDoc at `services/relay/src/routes.rs:1574-1575` reinforces this. **Implication for Sprint 2b**: the client-side re-snapshot fix is sufficient — there is no merge case that the client could fail to neutralise.

2. **`/prekeys/publish` rate limit: 10 calls per 3600-second window per identity.** `PUBLISH_RATE_LIMIT = 10` + `PUBLISH_RATE_WINDOW_SECS = 3600` at `services/relay/src/routes.rs:1543-1544`. Exceeded → HTTP 429. Bounds stale-snapshot restoration attempts to ≤10 per hour per identity.

3. **`/prekeys/bundle/{identity}` rate limit: 60 calls per 60-second window per requester.** Bounds bundle-fetch reconnaissance and DoS-via-fetch from any single requester key.

4. **`/relay/send` (WS message) rate limit: `RELAY_RATE_LIMIT_PER_WINDOW = 60` per `RELAY_RATE_LIMIT_WINDOW_SECS = 60` per identity** (defaults at `services/relay/src/config.rs:144-145`). Bounds inbound `x3dhInit` spam at 60 envelopes per minute per peer.

5. **`MAX_OPKS_PER_PUBLISH = 100`** (`services/relay/src/prekeys.rs:81`).

6. **`MAX_OPKS_PER_IDENTITY = 200`** (`services/relay/src/prekeys.rs:85`).

7. **Atomic OPK consume on bundle fetch.** `consume_bundle` at `services/relay/src/prekeys.rs:301-323` pops one OPK under a write-lock critical section and persists post-consume state to disk before returning. Two concurrent fetches cannot both receive the same OPK; this guarantee is unchanged by Sprint 2b.

8. **SPK rotation idempotency.** `prekeys::publish` line 265-266 documented idempotent retry guard: same `spk.key_id` → no rotation. Sprint 2b's client-side retry re-snapshot will produce DIFFERENT bodies (different OPK lists) across attempts; the SPK part of the body remains stable. Server-side idempotency on SPK is unchanged.

9. **Signing-key TOFU binding.** The X25519 identity is bound to a single Ed25519 signing key on first publish (`prekeys.rs:255-260`). Subsequent publishes must use the same one or are rejected with `SigningKeyMismatch`. Sprint 2b's re-snapshot fix does not change the signing key.

## Sprint 2b client locks (L1-L10)

Ten binding locks. Each lock cites the Council verdict that codified it. The implementation PRs must NOT extend or contract any of them.

### L1 — Factory-lambda re-snapshot at the helper level (codifies Round 2 LOCK-A1)

The publish-snapshot consistency fix MUST be applied at the `PreKeyLifecycleService.publishBundle` helper level, with a factory-lambda signature threaded through to `PreKeyApiClient.publishWithRetry`. The helper signature becomes:

```kotlin
private suspend fun publishBundle(
    identityX25519Hex: String,
    signing: IdentitySigningKeyPair,
    spk: LocalSignedPreKeyEntity,
    opksProvider: suspend () -> List<LocalOneTimePreKeyEntity>,
)
```

`PreKeyApiClient.publishBundle` becomes:

```kotlin
suspend fun publishBundle(requestProvider: suspend () -> PublishRequest): PublishResult
```

`PreKeyApiClient.publishWithRetry` invokes `requestProvider()` ON EACH RETRY ATTEMPT, re-serialises a fresh `bodyBytes`, and sends that to `transport.publish(url, bodyBytes)`. The fix covers ALL FOUR call sites in `PreKeyLifecycleService`: `bootstrapForNewIdentity` (line 99), `maybeReplenishOneTimePreKeys` (line 205), `verifyBundleOnRelay` (line 180), `maybeRotateSignedPreKey` (line 268). Module boundary preservation per ADR-001: repository reads stay in `shared/core/messaging`, HTTP retry logic stays in `shared/core/transport`. Cells M-2bA-1 + M-2bA-2 pin this.

### L2 — Decrypt-existing-first ordering at the inbound repair callsite (codifies Round 2 LOCK-A2)

When `DefaultMessagingService` receives an inbound envelope with `sessionExists=true AND x3dhInitPresent=true`, the implementation MUST attempt `ratchet.decrypt(existingState, encrypted)` BEFORE entering `recipientBootstrapInMemory`. If the existing-session decrypt succeeds, the envelope is treated as a stale relay re-delivery; the advanced ratchet state is saved via `saveSession`; the `x3dhInit` header is acknowledged at the `ProcessedEnvelopeRepository` ledger but NO OPK is consumed. If the existing-session decrypt fails (MAC mismatch), the existing inbound X3DH repair path at `DefaultMessagingService:2569` fires unchanged.

L2 is INDEPENDENT of `SessionRole` and is therefore decoupled from the Sprint 1 + Sprint 2a tagging mechanism. L2 DEPENDS on Sprint 2a Site 1 (the outbound role guard at `DefaultMessagingService:434`) remaining in place — see L9. Cell M-2bA-3 pins this.

### L3 — Pending session state via companion table (codifies Round 1 + Round 2 LOCK-B1)

The pending session slot MUST live in a NEW companion table, NOT a column added to the existing `ratchet_state` table. The schema:

```sql
CREATE TABLE pending_ratchet_state (
    conversation_id TEXT NOT NULL PRIMARY KEY,
    state_blob      TEXT NOT NULL,
    reserved_at_ms  INTEGER NOT NULL
);
```

A new `PendingRatchetStateRepository` interface in `shared/core/storage` exposes `get / upsert / delete / getAll` operations distinct from the existing `RatchetStateRepository`. The Keystore alias is reused (`phantom_ratchet_wrap_v1`) — identical threat model, no benefit from a separate alias at Alpha. The Sprint 2a outbound role guard at `DefaultMessagingService:434` continues to read the ACTIVE slot ONLY and MUST NEVER read the pending slot for outbound decisions. The boundary is mechanical (different repository, different table). Cells M-2bB-1 + M-2bB-2 pin this.

### L4 — Two-phase OPK consume protocol

The eager delete at `SessionManager.recipientBootstrapInMemory:357` MUST be replaced with a three-phase protocol:

1. **Reserve**: write `opk_reservation(opk_key_id_hex PK, envelope_id, reserved_at_ms)` BEFORE the X3DH 4-DH derivation. Use `INSERT OR IGNORE` semantics — if an existing reservation for the same opk_key_id_hex exists (e.g., from a previous derivation attempt that crashed mid-flight), the insert is a no-op and the existing reservation is read back.
2. **Derive + decrypt**: run the existing X3DH 4-DH handshake + candidate-decrypt unchanged.
3. **Commit-or-rollback**:
   - **Success**: in a single SQLDelight cross-table transaction, DELETE the `local_one_time_pre_key` row, DELETE the `opk_reservation` row, and UPSERT the pending ratchet state via `PendingRatchetStateRepository.upsert(conversationId, advancedState)`. The OPK is now permanently consumed.
   - **Failure**: DELETE only the `opk_reservation` row. The `local_one_time_pre_key` row is preserved. The existing ACTIVE ratchet state is untouched. The OPK remains available for a future retry.

Cells M-2bB-3 + M-2bB-4 + M-2bB-5 pin this.

### L5 — `opk_reservation` table schema + repository

The `opk_reservation` table schema:

```sql
CREATE TABLE opk_reservation (
    opk_key_id_hex  TEXT NOT NULL PRIMARY KEY,
    envelope_id     TEXT NOT NULL,
    reserved_at_ms  INTEGER NOT NULL
);
```

A new `OpkReservationRepository` interface in `shared/core/storage` exposes `reserve(opk_key_id_hex, envelope_id, now_ms): ReservationOutcome` (returns `Created` or `AlreadyReserved`), `release(opk_key_id_hex)`, `getAll(): List<OpkReservation>`, and `deleteStaleOlderThan(threshold_ms)`. The `recipientBootstrapInMemory` flow calls `reserve` first; the commit path calls neither `release` nor `deleteByKeyId` — the cross-table transaction in L4 deletes both rows atomically.

### L6 — Reservation TTL + startup recovery sweep

Reservations have a 5-minute TTL. On process startup, `AppContainer` calls `OpkReservationRepository.deleteStaleOlderThan(now_ms - 5 * 60 * 1000)` BEFORE any inbound handler can fire. Stale reservations are silently swept; their OPK rows remain available (they were never deleted in the failure path; if the process crashed mid-derive after reserve but before commit, the reservation is stale by definition). 5 minutes is well above the expected derivation latency (<1s) and well below any reasonable user-facing reconnect cadence.

### L7 — `PENDING_SESSION_CANDIDATE_CAP = 8` with LRU eviction (defense-in-depth)

The number of in-flight pending session candidates per identity is capped at `PENDING_SESSION_CANDIDATE_CAP = 8`. When a new bootstrap would exceed the cap, the OLDEST pending candidate (by `reserved_at_ms`) is evicted; the eviction MUST transactionally release the evicted candidate's OPK reservation (rolling back the reserve from L4 phase 1) and delete the pending ratchet state row. The cap is a **defense-in-depth** mitigation on top of the server-side rate limits in server-contract pins #2/#3/#4 (publish 10/hour, fetch 60/min, WS message 60/min). The relay's existing rate limits already bound the realistic attack rate; the client cap bounds local memory + storage growth from a peer that happens to spam pending bootstraps within those rate envelopes.

The default value `8` is an Alpha-locked design choice, NOT a derived constant. It is retunable post-merge based on field telemetry. The lock is on the existence of the cap, not on the specific value. Cells M-2bB-6 + M-2bB-7 pin this.

### L8 — Sender signal (typed control envelope) DEFERRED to Sprint 2c/3

Sprint 2b does NOT introduce a typed control envelope for the recipient to signal the sender "re-bootstrap with a fresh OPK; the one you used is unknown to me." Council Round 2 verdict 3:1 against introducing it in Sprint 2b (Round 2 kmp-builder: out of proportion; Round 2 architect: 3 args against — dominant fix closes without wire change, forged-signal attack surface, Ghost-mode traffic-pattern violation; Round 2 security: CONDITIONAL GO only if signed + nonce-bound + Ghost 30s-jitter, fails one or more conditions → defer; Round 2 tester: not required for correctness). The deferred work is named in Sprint 2c or Sprint 3 scope; entry criteria are the three security conditions verbatim. Sprint 2b adds a recipient-side `opk_not_found_total{reason}` metric in 2b-C for debuggability ONLY.

### L9 — Sprint 2a outbound role guard is a load-bearing prerequisite

Sprint 2a's outbound role guard at `DefaultMessagingService:434` (commit `df34222d`, PR #311 merged on master) is a load-bearing prerequisite for Sprint 2b. The L2 decrypt-existing-first ordering and the L3 pending/active state machine BOTH depend on the Sprint 2a guard continuing to fire correctly when an existing session is `SessionRole.RESPONDER` AND `!sessionSuspect`. Sprint 2b-A MUST include a regression test asserting Sprint 2a's U1/U2/U3 cells remain green. If a future revert or refactor of Sprint 2a fires, Sprint 2b is invalidated until Sprint 2a is restored or the L2/L3 interaction is re-locked. Cell M-2bA-4 pins this.

### L10 — Atomic-pair landing gate + Sprint complete gate + #310 ready gate

The PR-staging gate sequence MUST be:

1. **Sprint 2b-A merged solo**: technically mergeable as a release-pinned step. **Does NOT satisfy PR #313 lock.** Does NOT unblock PR #310. Closes ONLY the publish-snapshot consistency root cause + decrypt-existing-first protective layer.
2. **Sprint 2b-A + Sprint 2b-B merged**: **OPK lifecycle foundation complete**. Satisfies the PR #313 lock's OPK-lifecycle / consume / idempotency / restart-resilience mandate. **Does NOT unblock PR #310 yet** — pending/active runtime wiring is in 2b-C.
3. **Sprint 2b-A + 2b-B + 2b-C merged + Wi-Fi M-OPK-3 PASS**: **Sprint 2b complete**. The runtime pending→active wiring is in place. The Wi-Fi deterministic field gate (M-OPK-3) has reproduced the 2026-06-15 smoke shape and PASSED.
4. **Stage 2B-D integration LTE smoke PASS on Tele2 LTE**: PR #310 unblocked for ready transition; Stage 2B-D promotion (`LONGPOLL_V2_ENABLED "0" → "1"` in release variant) entry-criteria met.

The gate wording in this lock is binding. PR descriptions, release notes, commit messages, and operator-facing communications MUST NOT claim Sprint 2b "complete" or "PR #310 unblocked" at any earlier gate.

## Sprint 2b commit boundary (Sprint 2b-A, Sprint 2b-B, Sprint 2b-C)

Three PRs, each individually green at the local sweep level, each independently release-pin-rollbackable. The split aligns with the L10 landing-gate sequence.

### Sprint 2b-A — publish-snapshot consistency + decrypt-existing-first + Sprint 2a regression (zero schema)

**Code surface:**
- `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/PreKeyApiClient.kt` — `publishBundle` signature change to factory-lambda; `publishWithRetry` invokes the factory on each attempt; logging at `prekey_publish_start` shows attempt-specific `opks=N` reflecting the fresh snapshot.
- `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/PreKeyLifecycleService.kt` — `publishBundle` helper takes `opksProvider: suspend () -> List<LocalOneTimePreKeyEntity>`; all four call sites (`bootstrapForNewIdentity`, `maybeReplenishOneTimePreKeys`, `verifyBundleOnRelay`, `maybeRotateSignedPreKey`) pass `{ oneTimePreKeyRepository.getAll() }` (or `{ generated_opks }` for the bootstrap case where the snapshot is point-in-time-of-call by definition).
- `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultMessagingService.kt:2569` — decrypt-existing-first early-return: attempt `ratchet.decrypt(existingState, encrypted)` first; on success, save advanced state + mark envelope processed + return without entering `recipientBootstrapInMemory`; on failure, fall through to the existing PR #249 inbound X3DH repair path unchanged.

**Tests (commonTest):**
- **M-2bA-1** `publishBundle_retryRace_resnapshotsLocalPoolPerAttempt` — `BodyCapturingPublishTransport` asserts `parseBody(captured[1]).one_time_pre_keys` differs from attempt 0 when `opkRepo.deleteByKeyId(consumed)` runs between them. FAILS on master `9f730730`, PASSES after L1.
- **M-2bA-2** `publishWithRetry_perAttemptResnapshot_bodyDoesNotContainDeletedOpk` — body of attempt 2 MUST NOT contain `opk_consumed` deleted between attempts 1 and 2.
- **M-2bA-3** `handleDeliver_existingSessionDecryptsCleanly_doesNotInvokeInboundRepair` — `SpyOneTimePreKeyRepo.deleteCalls.size == 0` after delivering an `x3dhInit`-bearing envelope that decrypts cleanly under the existing session. The load-bearing decrypt-existing-first invariant.
- **M-2bA-4** Sprint 2a regression — U1/U2/U3 at `DefaultMessagingServiceTest.kt` continue to assert byte-identical wire shape (role guard + suspect override behaviour preserved). No code change inside these tests; if they fail, Sprint 2b-A is reverted.

**Local sweep:**
- `:shared:core:transport:jvmTest` green (including all new M-2bA cells)
- `:shared:core:messaging:jvmTest` green (including M-2bA-3 + M-2bA-4)
- `:apps:android:testDebugUnitTest` green
- `:apps:android:assembleDebug` green

**ADR-009 amendment:** the factory-lambda publish invariant is recorded as an amendment inline in this PR's body and back-propagated into ADR-009 at merge. NOT a new ADR.

### Sprint 2b-B — schema + reservation + two-phase consume + cap (schema change)

**Code surface:**
- `shared/core/storage/src/commonMain/sqldelight/phantom/core/storage/OpkReservation.sq` (new file, per L5)
- `shared/core/storage/src/commonMain/sqldelight/phantom/core/storage/PendingRatchetState.sq` (new file, per L3)
- `shared/core/storage/src/commonMain/kotlin/phantom/core/storage/OpkReservationRepository.kt` (new interface) + `SqlDelightOpkReservationRepository.kt` (new impl)
- `shared/core/storage/src/commonMain/kotlin/phantom/core/storage/PendingRatchetStateRepository.kt` (new interface) + `SqlDelightPendingRatchetStateRepository.kt` (new impl) — reuses `PrivateKeyStorageCodec` + `IdentityCipher` (Keystore alias `phantom_ratchet_wrap_v1`)
- `shared/core/storage/src/commonMain/kotlin/phantom/core/storage/SessionTransactionRepository.kt` (new interface) — exposes `commitBootstrap(opkKeyIdHex, conversationId, stateBlob): Boolean` which runs the L4 phase-3 cross-table transaction atomically
- `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/SessionManager.kt:317-419` — `recipientBootstrapInMemory` rewires to L4's three-phase protocol; the eager delete at line 357 is replaced with `opkReservationRepository.reserve(opkId, envelopeId, now())`; the caller in `DefaultMessagingService:2569` now calls `sessionTransactionRepository.commitBootstrap` on success and `opkReservationRepository.release` on failure
- `apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt` — wires the three new repositories; the startup recovery sweep (per L6) runs `opkReservationRepository.deleteStaleOlderThan(now - 5 * 60 * 1000)` in `init { ... }` before messaging starts
- `AppContainer` also wires the L7 cap-8 eviction: a `PendingSessionCapEnforcer` (new class in `shared/core/messaging`) checks the cap on each successful `commitBootstrap`; on overflow, the oldest pending row + its reservation are transactionally rolled back

**Tests (commonTest):**
- **M-2bB-1** `pendingRatchetStateRepository_writes_areIsolatedFromActive` — writing to pending slot does NOT update `RatchetStateRepository.getRatchetState(conv)`.
- **M-2bB-2** `sprint2aGuard_readsActiveSlot_neverReadsPending` — Sprint 2a U1/U2/U3 with a populated pending slot continue to read ONLY the active slot.
- **M-2bB-3** `recipientBootstrapInMemory_failedDecrypt_preservesOpk` — when candidate-decrypt fails, `opkRepo.has(opkId) == true` AND `opkReservationRepo.has(opkId) == false`. The load-bearing rollback invariant.
- **M-2bB-4** `recipientBootstrapInMemory_processCrashMidDerive_opkSurvivesRestart` — `runTest` simulates: reserve + crash. After process restart (via `SessionManager` recreation against the same backing repos + startup sweep), `opkRepo.has(opkId) == true` AND `opkReservationRepo.has(opkId) == false` (sweep ran).
- **M-2bB-5** `commitBootstrap_atomicallyDeletesOpkAndReservationAndUpsertsPending` — all three operations land in a single transaction or none of them.
- **M-2bB-6** `pendingSessionCapEnforcer_evictsOldestOnOverflow` — after 9 reserves, the oldest pending row + its reservation are rolled back.
- **M-2bB-7** `pendingSessionCapEnforcer_evictionRollsBackOpk` — evicted candidate's OPK row is restored (`opkRepo.has(opkId) == true` post-eviction).

**Instrumented tests (androidInstrumentedTest):**
- Schema migration test asserting fresh installs + upgrade installs land at the same schema version.

**Local sweep:**
- `:shared:core:storage:jvmTest` green
- `:shared:core:messaging:jvmTest` green
- `:apps:android:testDebugUnitTest` green
- `:apps:android:assembleDebug` green

**ADR-029 written + locked**: "Pending/Active Session State Machine + OPK Reserve Protocol." Covers the L3 companion-table decision, L4 three-phase protocol, L5 reservation schema, L6 TTL + sweep, L7 cap-8.

### Sprint 2b-C — pending→active commit wiring + outbound reuse + Wi-Fi field gate

**Code surface:**
- `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultMessagingService.kt` — wire the state-machine transition: on first successful inbound message decoded from the pending session's chain, promote `pending_ratchet_state` row to `ratchet_state` (active slot) via `SessionTransactionRepository.promotePendingToActive(conversationId)`. The active slot replaces the previous active row only at promotion time, not on bootstrap.
- `DefaultMessagingService:434` — outbound path's Sprint 2a guard adds a PENDING-slot reuse check: before calling `initiatorBootstrap` for a new outbound, check `PendingRatchetStateRepository.get(conv)`; if a pending INITIATOR slot exists within PENDING_TTL, reuse its `RatchetState` and the cached `x3dhInit` header; do NOT consume a second OPK. If the pending slot is expired, discard and run a fresh bootstrap.
- `apps/android/src/androidMain/kotlin/phantom/android/dev/` — instrumented test trigger for the M-OPK-3 Wi-Fi field gate harness.
- Recipient-side `opk_not_found_total{reason}` metric emission (L8) — debuggability seam for Sprint 2c.

**Tests (commonTest):**
- **M-2bC-1** `firstInboundOnPendingChain_promotesPendingToActive` — pending row deletion + active row upsert in a single transaction.
- **M-2bC-2** `outboundSendWithinPendingTtl_reusesPendingSlot_noOpkConsume` — second outbound send before promotion uses cached pending state; `opkRepo.deleteCalls.size` unchanged.
- **M-2bC-3** `outboundSendAfterPendingTtl_runsFreshBootstrap` — expired pending slot is discarded; new bootstrap consumes a fresh OPK.

**Instrumented tests (androidInstrumentedTest):**
- **M-OPK-3** `publishRetryDuringConsume_secondReplyDecryptsOk` — the load-bearing acceptance gate. Test harness: `publishWithRetryDelayHook` stalls publish attempt 3 for 5s while inbound `recipientBootstrapInMemory` consumes OPK_X locally; an injected second reply (`am start` triggered from the test driver) MUST decrypt under the inbound-repair path with ZERO `errorClass=OpkNotFound` and ZERO `fail_mac action=hold` in logcat. Reproduces the 2026-06-15 smoke shape deterministically. Wi-Fi only — no Tele2 required for landing.

**Local sweep:**
- All commonTest + androidInstrumentedTest cells green
- `:apps:android:assembleDebug` green
- `:apps:android:compileReleaseKotlinAndroid` green (release-pin assertions)

**Wi-Fi field gate (M-OPK-3 pattern):**

1. `pm clear` both Tecno + emulator
2. Fresh QR pair
3. Tecno → emu "alpha-1" → emu receives + replies "beta-1"
4. `am kill` Tecno (force-stop Phantom)
5. Re-launch Phantom on Tecno
6. emu → Tecno "beta-2" (Sprint 2a guard fires on emu's outbound; emu attaches `x3dhInit` referencing one of Tecno's OPKs)
7. Tecno receives "beta-2"; asserts `inbound_repair_ok bootstrap=true` (NOT `inbound_repair_fail errorClass=OpkNotFound`)
8. Tecno UI shows "beta-2"

PASS = step 7 + step 8. FAIL = either condition. INVALID = transport-blocked before step 6.

The Wi-Fi field gate is the load-bearing acceptance gate for Sprint 2b-C landing. Tele2 LTE re-run remains a post-Sprint-2b integration smoke for PR #310 ready (per L10 gate 4).

## Named non-goals (deferred to Sprint 2c / Sprint 3 / threat-model backlog)

- **Sender signal (typed control envelope from recipient to sender on `OpkNotFound`)** — L8. Entry criteria: signed under recipient Ed25519 + nonce-bound to `(envelope_id, opk_key_id_hex)` + Ghost-mode 30s+jitter delay (per Round 2 security). If any of the three fails, the signal is not introduced.
- **Traffic fingerprint mitigation from re-snapshot body-size delta** — Round 2 Layer 2 verdict: medium-severity traffic-analysis concern, NOT a correctness defect; refill-to-40 has unbounded costs (relay churn, pool growth pressure). Named residual risk in this scope-doc; revisited in Sprint 3 threat-model rollup.
- **`OpkDecryptFailed` vs `OpkNotFound` distinction** — Round 2 BS-4 resolved cleanly against the actual smoke evidence; the smoke literal is `OpkNotFound`. Sprint 2b does NOT split the error taxonomy further. If future field signals reveal a Keystore-decode-failure path, a separate error class is added then.
- **Multi-device OPK pool** — Round 1 Layer 2 BS-3. The reservation system is per-device. Multi-device server-side OPK pool semantics are a separate track.
- **PreKeyLifecycleService.maybeRotateSignedPreKey OPK-pool side effects** — SPK rotation does not touch OPKs in current code; Sprint 2b does not change this.

## PR review-time checks (named open questions, NOT hidden)

The following Round 2 blind spots are NOT blockers for scope-lock but MUST be addressed at PR review time. Each PR description references the corresponding check.

- **BS-R2-2 `publishWithRetry` success detection** (Round 2 Layer 2): does the client correctly detect server-side commit on attempt N when the response leg times out? Sprint 2b-A PR description MUST analyse the retry-on-success behaviour and confirm idempotency at the HTTP layer (server-side `SignedPreKey.key_id` idempotency per server-contract pin #8 covers SPK; OPK-list-difference across attempts is a new dimension that needs analysis).
- **BS-R2-3 `pending_ratchet_state` migration on in-flight sessions** (Round 2 Layer 2): if a device is mid-ratchet at the 2b-B schema migration, absence of a pending row for an active session must not cause spurious re-key. Sprint 2b-B PR description MUST include a migration-time invariant + a unit test for the upgrade path.
- **BS-R2-5 Tecno-specific OPK consumption timing** (Round 2 Layer 2): if Tecno's KMP layer consumes OPKs at a different handshake point than the emulator baseline (e.g., before vs after server ACK), the re-snapshot fix may close the relay-staleness window but not the local-consumption window. Sprint 2b-B PR description MUST confirm the consume site at `SessionManager.kt:357` is the ONLY consume site that the L4 protocol must rewire.
- **C-1 documentation debt** (Round 1 Layer 2): the KDoc at `SessionManager.kt:351-353` ("safe because we hold the only async reference; the pool is per-device, not concurrent") was an incorrect mental model. Sprint 2b-B PR MUST rewrite this KDoc with the corrected publish-vs-consume staleness explanation.

## What this scope does NOT do

- Does NOT introduce a sender signal envelope type (deferred per L8).
- Does NOT change the relay's publish, fetch, consume, or rate-limit semantics.
- Does NOT change Sprint 2a's outbound role guard at `DefaultMessagingService:434`.
- Does NOT change `PR #249 inbound X3DH repair` path's exception types or fall-through behaviour; only the OPK-consumption mechanic underneath it.
- Does NOT change `RatchetState` codec (`rs1:` blob is preserved; pending slot uses the same codec).
- Does NOT change `ProcessedEnvelopeRepository` retention window (named residual risk per Round 2 BS-5 — separate track).
- Does NOT add a Sprint 2b ADR for the publish protocol fix (amends ADR-009 instead).
- Does NOT validate iOS / JVM / desktop client transports for OPK lifecycle behaviour. Sprint 2b is Android-only; iOS / JVM verification is a separate per-platform round.

## After this PR sequence

After Sprint 2b-A + 2b-B + 2b-C land on master + M-OPK-3 PASS, the L10 gate sequence calls for:

- **Stage 2B-D pre-promotion integration LTE smoke re-run** against the master-after-Sprint-2b state, with the Round 14 relay binary deployed + Sprint 2a crypto + Stage 2B-B client + Sprint 2b together. The runbook at `C:\temp\trek2-stage2b-b-d-integration-smoke-2026-06-15\runbook.md` applies with the addition that Layer 3 end-to-end-decrypt MUST now pass.
- **If the integration smoke PASSES**: PR #310 flips ready-for-review. Stage 2B-D promotion (`LONGPOLL_V2_ENABLED "0" → "1"` in the release variant) proceeds.
- **If the integration smoke FAILS**: re-investigate per facts-first rule; Sprint 2b-D candidates emerge from the new field evidence.

Sprint 2c follows Sprint 2b with the deferred items: sender signal (per L8 entry criteria), `opk_not_found_total{reason}` metric consumers, multi-device OPK pool considerations, traffic-fingerprint mitigation evaluation.
