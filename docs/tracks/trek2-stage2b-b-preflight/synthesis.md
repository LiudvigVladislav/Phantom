# Trek 2 Stage 2B-B — Preflight Synthesis

This file consolidates Round 1 (four parallel domain reviews: architecture, security, KMP implementation, test matrix) and Round 2 (independent second-pass cross-check) into a single decision-ready document. It is the input to the Stage 2B-B scope-doc lock. Nothing here is implementation guidance; it is contract-shaping material.

**Stage 2B-A shell baseline:** PR #306, master `0ac29cf5`.
**Branch:** `docs/trek2-stage2b-b-preflight`.
**Round 1 files:** `round1-architect.md`, `round1-security.md`, `round1-kmp.md`, `round1-tests.md`.
**Round 2 file:** `round2-independent.md`.

---

## Blocker register

Each blocker is tagged with its origin and severity. P1 must be addressed before scope-doc lock or explicitly waived by name; P2 is a strong concern that the scope-doc should answer.

### Architecture (Round 1)

- **A-B1 — Stale `_seqMacVerifyKey` on token rotation.** Verify call must read the key inside the same `tokenMutex.withLock` block (or by value per envelope-iteration after token is confirmed). Verify-key fallback policy must be locked.
- **A-B2 — Cursor advancement at wrong call site (`tryEmit` return) breaks guardrail A.** Cursor advance MUST be triggered by a storage-acceptance callback from the downstream consumer, not by the orchestrator's emit site. Requires a new constructor parameter on `RestFallbackOrchestrator` and a wire-up change in `AppContainer`.
- **A-B3 — `410 Gone` may arrive mid-hold; both poll loops need explicit branches.** Reuse `acquireOrRefreshToken(staleToken = token)` discipline via the existing mutex. Do not introduce a second lock that recursively acquires `tokenMutex`.

### Security (Round 1)

- **S-B1 — Variable-time MAC comparison is a timing oracle.** Kotlin `==` on hex `seq_mac` leaks the expected MAC byte-by-byte in O(16 × 64) RTT. Constant-time `constantTimeEquals(ByteArray, ByteArray)` in `phantom.core.crypto`; decode hex to raw bytes first; never compare hex strings.
- **S-B2 — `_seqMacVerifyKey` stale on failed-refresh.** `acquireOrRefreshToken` failed-refresh path (lines 929-939) does NOT reset `_seqMacVerifyKey`. Stale key combined with fresh token produces false-positive verifies. Reset the slot in the same branch that nulls `sessionToken`.
- **S-B3 — Cursor advance before storage write violates guardrail A.** Same finding as A-B2 from the security angle. Ordering rule: `(decrypt success ∧ storage insert OK-or-dedup) → cursor advance`. Storage failure → drop envelope, log, leave in relay queue for re-delivery.

### KMP implementation (Round 1)

- **K-P1-1 — Module placement.** `SeqMacVerifier` belongs in `:shared:core:transport` commonMain, not `:shared:core:crypto`. The transport module already depends on the crypto module; no new gradle dep.
- **K-P1-2 — `java.nio.ByteBuffer` is JVM-only.** Canonical BE encoding of `seq` (u64), `envelope_id_len` (u16), and `sequence_ts` (u64) MUST use manual `ushr` arithmetic. Latent break the moment iOS targets land.
- **K-P1-3 — Verify-then-emit must stay in a single suspension point.** `_inbound` is `MutableSharedFlow(replay=0, extraBufferCapacity=32)`. No interleaving `launch` between verify and emit.
- **K-P1-4 — Kotlin `Long` is signed; server fields are u64.** Use `ushr` throughout BE helpers, never `shr`. Equivalent in bit pattern for non-negative values but correct by contract.

### Test matrix (Round 1)

- **T-B1 — No client mirror of the 13 server golden vectors.** Canonical-input drift goes silent without it.
- **T-B2 — Cursor monotonicity not pinned anywhere.** Open the write seam without a monotonicity test and a race writes a lower seq.
- **T-B3 — No "verify failure must NOT advance cursor" test.** `m5_*` proves the forward; nothing proves the gate.
- **T-B4 — Breaker has no state-transition matrix.** No closed/open/half-open contract; no test for "breaker does NOT lose the cursor while transitioning."
- **T-B5 — No pin that jitter source is `Csprng`, not `Random.Default`.** Nine `Random.Default.nextDouble()` sites in the orchestrator today.
- **T-B6 — No `410 Gone` re-auth test.** Zero cells for any leg of the dance.

### Independent (Round 2)

- **R2-S-B4 — MAC key provenance is unspecified.** If the relay is the authoritative source of `_seqMacVerifyKey`, the scheme is self-defeating. Scope-doc MUST document provenance. (Note: under the Stage 1.x locked threat model the relay IS authoritative for the per-identity verify key, and that is explicitly within scope — but the scope-doc must record this without ambiguity, so a future reviewer does not treat `seq_mac` as end-to-end integrity.)
- **R2-S-B5 — Breaker-open as MAC-verify downgrade trigger.** Two hard rules: (a) breaker trip signals are transport errors only, never MAC failures; (b) REST fallback path enforces identical verify semantics regardless of breaker state.
- **R2-I-P1-5 — `envelope_id_len` must use `encodeToByteArray().size`, NOT `String.length`.** Concrete trap Round 1 did not flag explicitly.
- **R2-I-P1-6 — HMAC key input must be hex-decoded before passing to `Auth.authHmacSha256`.** Round 1 flagged hex-decode for the output (comparison) but not for the input (the key itself).
- **R2-I-P1-7 — `identity_hex` binding underspecified.** Which key, whose side, what byte representation. Universal `mac_mismatch` until locked.
- **R2-T-B7 — No multi-byte UTF-8 `envelope_id` golden vector.** Trap from R2-I-P1-5 never exercised by the suite if all server vectors are ASCII.
- **R2-T-B8 — No `identity_hex` derivation correctness test.** M10 single-bit-flip proves rejection, not derivation correctness.
- **R2-T-B9 — No "key decoded before use" test.** M8 golden vectors only catch decode errors if the fixture applies the same (possibly wrong) decode as production.
- **R2-T-B10 — Coroutine cancellation mid-emit untested.** Verify-then-emit single-suspension-point invariant has no cell that exercises cancellation between emit and storage-acceptance.

---

## Cross-round contradictions and convergences

- **A-B2 ≡ S-B3** — both rounds flag cursor-write before storage-acceptance as the load-bearing guardrail-A risk. Convergent.
- **R2-S-B2 contradiction on B-2 mitigation.** Round 1's `_seqMacVerifyKey = ""` reset on failed-refresh + "empty key falls through to legacy unverified path" together amount to a silent unverified window during degraded-auth. Round 2 proposes a **suspended ingestion** state distinct from the never-supplied-key fallback. Scope-doc must lock the discriminator: empty-from-start (old relay) vs. empty-from-clear (failed refresh) need different behaviours, signalled by a distinct internal state, not by the same empty string sentinel.
- **R2-I `emit` vs single-suspension-point.** Round 1 suggested "switch `tryEmit` to `emit`" without reconciling against K-P1-3's no-interleave rule. Both `emit` and the storage-acceptance callback must be wired atomically.
- **R2-T M14(d) backoff vs S 410 floor.** "Exponential backoff" (M14d) is incompatible with "≥5 s hard floor" (S re-auth concern) unless explicitly combined as capped exponential with 5 s minimum.
- **R2-T S6 optional vs T-B4 P1.** Cursor preservation across breaker transitions cannot be P1 while the only realistic-transport scenario that exercises it is optional. Either elevate S6 or demote T-B4.

---

## Decisions for scope-doc (recommended, await user lock)

These are recommendations the scope-doc should adopt or explicitly reject. Each lock closes one or more blockers.

- **D1 — Constant-time comparison.** Mandate `constantTimeEquals(ByteArray, ByteArray)` in `phantom.core.crypto`, decode `seqMac` and computed-MAC to 32 raw bytes before comparison. Closes S-B1.
- **D2 — Verify-key state machine.** Three named states: `KeyAbsent` (relay never supplied), `KeyPresent(hexValue)` (last bootstrap returned a non-empty value), `KeySuspended` (cleared on failed refresh). Behaviour: `KeyAbsent` → legacy unverified pass-through with `reason=no_verify_key`; `KeyPresent` → verify and gate cursor on result; `KeySuspended` → drop all envelopes, no emit, no cursor advance, until next successful bootstrap returns a key. Closes S-B2, R2-S-B2.
- **D3 — Cursor advance call site.** Cursor advance lives in a storage-acceptance callback wired into `RestFallbackOrchestrator` as a new constructor parameter (`onEnvelopePersisted: suspend (identityHex: String, seq: Long) -> Unit`). The orchestrator never calls `upsertLastSeenSeq` directly; the wire-up layer threads the callback through the downstream consumer. Closes A-B2, S-B3.
- **D4 — Cursor seam shape.** Introduce `LongPollCursorRepository(read, write)` as a regular interface; retain `LongPollCursorReader` SAM as the Stage 2B-A read-only contract; orchestrator switches constructor parameter to the new repository type. AppContainer bridge lambda upgraded to implement both methods. Architect Round 1 + KMP Round 1 converged on this option.
- **D5 — Monotonicity enforcement at repository layer.** `SqlDelightLastSeenSeqRepository.upsertLastSeenSeq` already wraps the check inside a transaction (per `LastSeenSeqRepository.kt:28-31`). Stage 2B-B must NOT introduce a path that writes outside that contract. Closes R2-I monotonic-cursor-write concern.
- **D6 — 410 reauth backoff.** Capped exponential with a 5-second floor and a 60-second ceiling. Three consecutive 410s within 30 s switches to ceiling immediately. Recovers to floor after a successful poll. Closes T-B6 partially; closes R2-T M14(d) contradiction.
- **D7 — Breaker mechanism.** Lightweight `LongPollBreakerState` as orchestrator-internal sealed type + counter, separate from `RestStateMachine`. Interaction protocol: breaker open emits a named `Event.RestPollDegraded` to `RestStateMachine` but does NOT transition `RestMode`. Half-open is a named state, not implicit delay-scheduling. Breaker timer Job tracked and cancelled by `stop()`. Open state does NOT alter MAC verify semantics, does NOT downgrade to the no-key path. Closes T-B4, R2-S-B5, R2-I (breaker/state-machine and implicit-half-open concerns).
- **D8 — Canonical encoding helpers.** Single `SeqMacVerifier` object in `:shared:core:transport` commonMain. All BE encoding via `ushr` helpers (no `java.nio.ByteBuffer`). `envelope_id_len` uses `encodeToByteArray().size`. Verify-key passed as decoded `ByteArray`, never as the hex `String`. Closes K-P1-1, K-P1-2, K-P1-4, R2-I-P1-5, R2-I-P1-6.
- **D9 — `identity_hex` lock.** The 64-byte lowercase ASCII identity in the canonical input is the **receiving** identity — that is, `RestFallbackOrchestrator.identityHex` from the constructor, the same value used as the AuthSession identity. The implementation must assert `length == 64` at the seam. Document this in the `SeqMacVerifier` doc-comment with a single-source-of-truth pointer to `services/relay/src/seq_mac.rs`. Closes R2-I-P1-7, R2-T-B8 (partially — see test cell M-Bx below).
- **D10 — Cursor reconciliation between `pollLoop` and `wsActivePollLoop`.** Stage 2B-B does ONE of: (a) backfill MAC verify and persisted-cursor read into the legacy `pollLoop`, OR (b) decommission `pollLoop`'s in-memory cursor and have it poll with `since_seq = persistedCursor` shared with `wsActivePollLoop`. Recommendation: option (b) — single source of truth for `since_seq` across both loops. Closes the two-cursor reconciliation P2.
- **D11 — `pollHoldSecs`-derived timers must be hard-capped.** Breaker cooldown ≤ 120 s; 410 reauth interval ≤ 60 s. Any value derived from a relay-supplied field clamped to these ceilings even if the existing `pollHoldSecs in 1..480` gate would let it through. Closes R2-S `pollHoldSecs` clamping concern.
- **D12 — Jitter source migration.** All nine existing `Random.Default.nextDouble()` sites in `RestFallbackOrchestrator` migrate to `Csprng.uniformLong`-derived jitter. Test gate: behavioural recording-fake Csprng sees every draw; secondary grep gate on `Random.Default`, `java.util.Random`, and `SecureRandom`. Closes T-B5, R2-T M15 scope.
- **D13 — Test matrix M8-M17 + new cells.** Adopt Round 1's M8-M17 plus three new cells from Round 2: M-B7 multi-byte-UTF-8 `envelope_id` golden vector; M-B9 hex-key-decode-before-use behavioural; M-B10 cancellation-safety on verify-then-emit boundary; M-13e breaker stop-during-open. M14(d) becomes "capped-exponential 5 s floor, 60 s ceiling" per D6.
- **D14 — Tele2 LTE smoke.** S1-S5 mandatory. S6 (breaker open under byte-budget pressure) promoted from optional to required; the time-box for the smoke session must allow Mode-2 reproduction (≥ 30 min on Tele2, not 15 min). WORKING_RULES rule 8 is not waivable; field test on Tecno + Tele2 LTE before merge.
- **D15 — Bad-MAC posture (poison-envelope safe response).** The earlier draft of D15 proposed an ack + cursor advance after a bounded retry count. **That draft is REJECTED.** A bad-MAC envelope MUST NOT be acknowledged and MUST NOT advance the persisted cursor at any retry count. If MAC verification drifts because of a stale verify key, a canonical-encoding bug, a client/server version skew, or local DB corruption, an ack-and-advance posture silently and irreversibly destroys real messages — the exact guardrail-A violation `seq_mac` was introduced to detect. `seq_mac` defends against poll-layer / DB tamper / bugs per the locked threat model; the right response to a sustained verification failure is to assume the verifier is wrong about THIS envelope, not to delete the relay's authoritative copy.
  
  The locked safe posture:
  - **Telemetry only on repeated failures.** A per-session, per-`envelope_id` counter increments on every `mac_mismatch` / `no_mac_field` outcome. The counter feeds structured logging (`event=poll_mac_verify_repeat id=<8-char prefix> count=<n>`) for diagnosis; it does NOT drive any destructive action.
  - **One forced session/key refresh — latched per `envelope_id`.** On the first repeat (recommended `MAC_REPEAT_REFRESH_THRESHOLD = 2`), force a fresh `acquireOrRefreshToken(reason="poll_mac_repeat", staleToken=token)` so a stale `_seqMacVerifyKey` from a degraded earlier session is replaced and `seqMacVerifyKey` state transitions through `KeyPresent → fresh KeyPresent`. The refresh fires **exactly once per `envelope_id`** within an orchestrator-session lifetime — an in-memory `Set<String>` latch (`_macRefreshAttemptedFor`) records the envelope id at refresh time and short-circuits any subsequent refresh request for the same id; the latch resets only on orchestrator restart. The refresh goes through the existing `tokenMutex` discipline; D15 does NOT introduce a second lock path. This addresses the most common drift cause without touching the relay's queue and without producing a refresh storm under a sustained-bad envelope.
  - **Suspend / degrade BOTH REST poll loops if the same envelope still fails after the refresh.** Per **R2-S-B5** (breaker / suspension must not become a MAC verify downgrade), the suspended posture MUST apply symmetrically to both REST poll loops — neither becomes an unverified ingestion bypass. Concretely, **both `wsActivePollJob` and the legacy `pollLoop` enforce identical `seqMacVerifyKey` state semantics (`KeyAbsent` / `KeyPresent` / `KeySuspended`) and identical `seq_mac` verify discipline** (the same canonical-encoding helper from D8, the same constant-time compare from D1, the same outcome rules — drop, no emit, no ack, no cursor advance — under verify failure). Reaching the SuspendedOnPoison state in EITHER loop transitions `LongPollBreakerState` to `SuspendedOnPoison`; the breaker is observed by BOTH loops. While suspended, the legacy `pollLoop` does NOT silently drain the poisoned envelope: the same MAC verify gate that suspended `wsActivePollJob` blocks ingestion on `pollLoop` of the same envelope (identical `_macRefreshAttemptedFor` latch + counter feeds both paths). Direct WSS and non-REST transports (Reality, Tor) stay operational; the messenger remains usable while the bad envelope is investigated. Log `event=poll_mac_repeat_suspend reason=verify_fail_after_refresh source=<ws_active|legacy>` so the suspending loop is auditable.
  - **No ack. No delete. No cursor advance past the envelope.** Under any retry count, on any verifier state, in either REST poll loop.
  
  Resolving queue liveness for a sustained-bad envelope is OUT OF SCOPE for Stage 2B-B. It requires a separate server-side design: server-side MAC revalidation, server-side quarantine on repeat client report, or an authenticated poison-report mechanism. None of those are Stage 2B-B work. The locked posture above explicitly accepts that, under a sustained-bad envelope, the REST long-poll path (BOTH loops) becomes degraded while Direct WSS and other transports keep running — that is the correct trade-off given the threat model.
  
  Test cells: **M-B12** pins "MAC repeat counter increments but no ack, no cursor advance"; **M-B13** pins "first repeat triggers forced session refresh, latched exactly once per envelope id, no second refresh on threshold multiples"; **M-B14** pins "second repeat after refresh transitions to `SuspendedOnPoison`"; **M-B15** pins "Direct WSS path remains operational while both REST poll loops are suspended"; **M-B16** pins "the persisted cursor and the relay's queue remain byte-identical to the pre-poison state across all retry counts"; **M-B17** pins "after `SuspendedOnPoison`, the same bad envelope returned through the legacy `pollLoop` is NOT emitted, NOT acked, and does NOT advance the cursor — both REST poll loops enforce identical verify semantics under suspension." OQ-7 is **rejected** (no threshold approves cursor advance on unverified envelope); the `MAC_REPEAT_REFRESH_THRESHOLD` value is the only quantitative parameter and is locked at 2 unless re-locked.

---

## User locks (2026-06-10)

The six open questions below were locked verbatim by the user after the synthesis review. These are the binding answers the scope-doc draft consumes; the open-question text below is preserved for audit trail.

- **OQ-1 LOCK** — Skip-and-continue. Bad-MAC envelope: drop, no emit, no cursor advance, no ack; the batch continues. The parallel loop does NOT halt on a single bad envelope.
- **OQ-2 LOCK** — Sequential commits are OK for Stage 2B-B. The cursor write happens **only after** the storage layer accepts or dedupes the envelope. Single SQLCipher transaction across message-table insert + cursor write is nice-to-have, NOT a blocker.
- **OQ-3 LOCK** — `MISSING_MAC` (`seqMac == ""` while verify-key state is `KeyPresent`): drop the envelope. No quarantine in this PR.
- **OQ-4 LOCK** — No hard local logout on consecutive 410s. Use the capped 410 backoff (D6). Long-poll may suspend or degrade; the user identity must NOT be nuked by relay-side 410 storms.
- **OQ-5 LOCK** — S6 (breaker open under byte-budget pressure) is mandatory. Minimum smoke time-box ≥ 30 min on Tele2 LTE. If natural Mode-2 does not reproduce in the window, a controllable trigger is acceptable.
- **OQ-6 LOCK** — Decommission the legacy `pollLoop` in-memory cursor IN Stage 2B-B, not in a follow-up. D10's option (b) — single source of truth `since_seq` via the persisted cursor for both loops — is now binding.
- **OQ-7 REJECTED** — The earlier OQ-7 framing (poison-drain threshold) was a quantitative parameter on a D15 draft that proposed ack + cursor advance on an unverified envelope. **That posture is rejected.** A bad-MAC envelope MUST NOT be acked and MUST NOT advance the cursor at any retry count. The replacement D15 (above) carries a different parameter — `MAC_REPEAT_REFRESH_THRESHOLD = 2` — which controls only when to force a session/key refresh, not when to delete or advance past an envelope. That parameter is locked at 2 unless re-locked.

Decisions D1-D14 + the replacement D15 stand. Scope-doc proceeds against D1-D14 + D15 + OQ-1-OQ-6 locks. No further OQs pending.

---

## Open questions for the user (original, preserved for audit)

These were the six open questions before the lock above. They are kept here so the lock decisions remain auditable against the original framing.

- **OQ-1 — Verify-fail behaviour scope.** On a verify failure inside a multi-envelope batch, the loop should (a) skip that envelope and continue with the rest of the batch, or (b) halt the parallel `wsActivePollLoop` entirely and surface a fatal event. Round 1 architect recommended (a); security review concurred. The user lock is whether (a) is the final posture or whether a halt-surface is more appropriate for the Alpha threat model.
- **OQ-2 — Storage transaction atomicity.** Are the message-table insert and the `upsertLastSeenSeq` cursor write wrapped in a SINGLE SQLCipher transaction, or are they sequential commits? Sequential is safe under monotonicity (process kill leaves cursor behind, never ahead); single-transaction gives stronger atomicity at the cost of a wider transaction scope. The repository contract today supports both; the user must lock the discipline.
- **OQ-3 — `MISSING_MAC` policy on old relay (`seqMacVerifyKey != "" ∧ seqMac == ""`).** Drop with `reason=no_mac_field` (Round 1 default), or quarantine to a local error-store for later inspection? Drop is simpler; quarantine helps diagnose relay misconfiguration. Lock one.
- **OQ-4 — Hard cap on consecutive 410 responses.** Without a cap, persistent relay-side 410s loop the reauth indefinitely (within the 5-60 s backoff). Should Stage 2B-B introduce a hard local logout after N consecutive 410s in M minutes? If so, lock N and M.
- **OQ-5 — Tele2 smoke S6 reproduction time-box.** Mode-2 byte-budget reproduction has historically been flaky in 15 min. If S6 is required (per D14), the smoke session needs ≥ 30 min sustained Tele2 LTE upload pressure. Confirm time-box.
- **OQ-6 — `pollLoop` cursor decommission timing.** D10 recommends decommissioning the legacy `pollLoop`'s in-memory cursor and having both loops share the persisted cursor. Is this in scope for Stage 2B-B, or is it a Stage 2B-C follow-up? If Stage 2B-C, the two-cursor reconciliation rule from D10's option (a) must be locked instead.

---

## Voice-rule note

This document and the four Round 1 files plus the Round 2 file are all committed to a public repository path. The neutral first-person engineering voice is used throughout; no external reviewer service or person is named anywhere in any of the six files. Voice-rule cleanliness is verifiable with a single grep across `docs/tracks/trek2-stage2b-b-preflight/`.

---

## Next step

Preflight is LOCKED. D1-D14 + the replacement D15 (bad-MAC safe posture: telemetry + one forced refresh + suspend long-poll path, never ack, never advance cursor) stand. OQ-1 through OQ-6 are locked verbatim under § User locks (2026-06-10). OQ-7 was raised by the earlier draft of D15 and is now REJECTED along with that draft.

The next deliverable is the Stage 2B-B scope-doc, drafted strictly against D1-D15 + the OQ locks (NOT inventing new decisions). Implementation does not start until the scope-doc PR merges. WORKING_RULES rule 8 is binding: Stage 2B-B is not shippable without the Tele2 LTE smoke.
