# Trek 2 Stage 2B-B — Semantic Layer (Scope Mini-Lock)

**Status:** Locked scope mini-lock for Stage 2B-B. No code written. This document is the binding contract for the implementation PR that follows it.

**Why this PR exists.** Trek 2 Stage 2B-A (PR #306, master `0ac29cf5`) shipped the client-side shell of the long-poll backbone: opt-in headers, raised read/call timeout under a flag, parallel `wsActivePollJob`, read-only cursor seam, presence-only `seq_mac` parsing. The shell is byte-for-byte runtime-equivalent at the release pin and exercises none of the semantic invariants. Stage 2B-B is the **semantic side**: it makes the backbone actually defend message integrity and actually advance the cursor.

The two-round preflight (PR #307, master `f8cdc91a`, bundle at [docs/tracks/trek2-stage2b-b-preflight/](trek2-stage2b-b-preflight/)) consolidated four parallel domain reviews plus an independent second-pass cross-check into a single binding synthesis: 15 locked decisions D1-D15, six user locks OQ-1 through OQ-6, one rejected open question OQ-7. This scope-doc lifts those decisions into a per-lock contract that the implementation PR types itself against. **No new decisions are invented here.** Everything below either references a synthesis lock by tag or carries a single-sentence corollary that the synthesis already established.

**What lands after this PR.** Stage 2B-C is the documentation cleanup (release notes, ADR rollup, public README updates). Once 2B-C ships, the long-poll backbone is complete and Direct WSS hardening is the next track per the strategic order.

## Strategic frame

The order is the same as Stage 2B-A: backbone first → Direct WSS hardening → Reality Android repair → Tor / Ghost polish. The backbone is what makes the messenger un-killable at the product level. Stage 2B-A installed the shell; Stage 2B-B installs the integrity + cursor + breaker + reauth semantics that turn the shell into reliable delivery. After 2B-B, the messenger continues delivering messages through REST long-poll even when Direct WSS or Reality misbehaves, with `seq_mac` defending against poll-layer tamper / DB corruption per the locked threat model.

## Locked server contract surface (verified against master `f8cdc91a`)

The seven server-contract points from Stage 2B-A still apply. Stage 2B-B additionally types itself against the `seq_mac` integrity tag on `PollEnvelope`. The implementation PR must NOT widen, narrow, or reinterpret any of them. If a future server change drifts any of these, this scope is invalidated and must be re-locked.

1. **`X-Phantom-Long-Poll`** header. `"x-phantom-long-poll"`, strict `v == "1"` opt-in. (`services/relay/src/rest_fallback.rs:169`, `:1964-1968`.)
2. **`X-Phantom-Padded-Poll`** header. `"x-phantom-padded-poll"`, strict `v == "1"` opt-in. (`rest_fallback.rs:192`, `:1972-1976`.)
3. **`SessionResponse.poll_hold_secs: u32`**. Always present on the wire. `0` = server kill switch; `>0` = advertised hold ceiling. (`rest_fallback.rs:822-838`.)
4. **`SessionResponse.seq_mac_verify_key: String`**. 64-char lowercase hex, per-identity HMAC-SHA-256 verify key derived from the relay-side root key. Stage 2B-A cached this value; Stage 2B-B uses it for verification. (`rest_fallback.rs:839-853`.)
5. **Post-rotation challenge replay → `410 Gone`** with body `{"error":"session token rotated; obtain a fresh challenge"}`. (`rest_fallback.rs:1381-1387`.)
6. **`padded_opt_in = long_poll_opt_in || padded_poll_opt_in`**. (`rest_fallback.rs:1982`.)
7. **Kill-switch decoupling.** `RELAY_POLL_HOLD_SECS=0` zeros the hold but does NOT strip the padded body shape for PP-opted-in clients. (`rest_fallback.rs:1983-1987`, `:2044`.)

**Additionally locked by Stage 1.x and consumed by Stage 2B-B:**

8. **`PollEnvelope.seq_mac: String`** — 64-char lowercase hex, HMAC-SHA-256 over a canonical input. The locked threat-model wording is verbatim in `services/relay/src/rest_fallback.rs` `///` doc-comment on `PollEnvelope.seq_mac` and in [docs/tracks/trek2-stage1x-server-prereq.md](trek2-stage1x-server-prereq.md). Stage 2B-B verifies this MAC at the receiving client.
9. **Canonical MAC input layout (verbatim from `services/relay/src/seq_mac.rs:155-164`)**:
   ```
   b"phantom-seq-mac-v1\x00"        19 bytes  domain tag (fixed literal)
   identity_hex                      64 bytes  lowercase ASCII (receiving identity)
   seq                                8 bytes  u64 big-endian
   envelope_id_len                    2 bytes  u16 big-endian (UTF-8 byte length, NOT char count)
   envelope_id_bytes              variable     exact UTF-8 bytes (no NUL, no padding)
   sequence_ts                        8 bytes  u64 big-endian (post-quantize, 60s floor)
   ```
   Total `101 + envelope_id_byte_length` bytes.
10. **Per-identity verify-key derivation** (server-side, client mirrors no derivation work): `seq_mac_verify_key = HMAC-SHA-256(root_key, b"phantom-seq-mac-key-v1\x00" || identity_hex)`. The client receives the derived key in `SessionResponse.seq_mac_verify_key`.

## Stage 2B-B client locks (L1-L10)

Ten binding locks. Each lock cites the synthesis decision(s) it codifies. The implementation PR must NOT extend or contract any of them.

### L1 — Constant-time MAC comparison (codifies D1)

The byte-by-byte equality check between the computed HMAC output (32 bytes) and the wire-decoded `seq_mac` value (32 bytes after hex-decode) MUST run in constant time with respect to the matching prefix length. The implementation provides `constantTimeEquals(a: ByteArray, b: ByteArray): Boolean` in `phantom.core.crypto` using the standard XOR-accumulate-then-check-zero pattern. The MAC verify path NEVER compares the hex `String` representations directly; both inputs are hex-decoded to 32-byte `ByteArray` instances before the comparison call. Cells M9 + M-B9 pin this.

### L2 — Verify-key state machine + forced-refresh-failure corollary (codifies D2)

The orchestrator's verify-key state is one of three named values:

- **`KeyAbsent`** — the relay never supplied a verify key in this orchestrator-session. Either an old relay (Stage 1.x not deployed) or a `RELAY_SEQ_MAC_KEY`-less deployment. Behaviour: legacy unverified pass-through with structured log `reason=no_verify_key`. This is the only state in which a `PollEnvelope` reaches the inbound flow without MAC verification.
- **`KeyPresent(hexValue)`** — the most recent successful `acquireOrRefreshToken` returned a non-empty `seq_mac_verify_key`. Behaviour: verify on every envelope, gate cursor advance on the result. This is the normal operating state.
- **`KeySuspended`** — the orchestrator cleared the key after a failed refresh. Behaviour: drop all envelopes, no emit, no cursor advance, until a subsequent successful `acquireOrRefreshToken` returns a fresh non-empty key (transition to `KeyPresent`).

**Forced-refresh-failure corollary (locked, derives from D2 + D15).** If a forced session/key refresh under L7 itself fails (the relay returns `null` body, 4xx, 5xx, or a network exception), the verify-key state transitions to `KeySuspended` immediately. **No REST ingestion happens on either `wsActivePollJob` or the legacy `pollLoop` until a subsequent successful re-authorisation moves the state back to `KeyPresent`.** This is the single sentence the scope-doc must surface so an implementer does not silently keep ingesting under a stale-or-empty key after a failed refresh.

The state machine is observed by BOTH REST poll loops (L6). Cells M-B11 (state transitions) + M-B14 + M17 pin this.

### L3 — Cursor advance via storage-acceptance callback (codifies D3 + OQ-2 LOCK)

Cursor advancement (`upsertLastSeenSeq`) does NOT live in the orchestrator's poll loops. It lives in a callback wired into `RestFallbackOrchestrator` as a new constructor parameter:

```
onEnvelopePersisted: suspend (identityHex: String, seq: Long) -> Unit
```

The wire-up layer (`AppContainer`) threads this callback through the downstream messaging consumer; the consumer invokes it after the message-table insert returns success **or** idempotent-duplicate. The orchestrator NEVER calls `upsertLastSeenSeq` directly.

**Storage transaction discipline (OQ-2 LOCK).** Sequential commits are acceptable: message-table insert commits first, then the callback fires and `upsertLastSeenSeq` commits separately. Process kill between the two leaves the message persisted and the cursor frozen at the prior value — the next poll re-receives the envelope, the storage layer dedupes, the callback fires, the cursor catches up. Single SQLCipher transaction across both writes is nice-to-have, NOT a blocker. The repository contract's monotonicity guard (D5) makes the sequential discipline safe under process kill or concurrent writes.

Cells M11 + M12 pin this.

### L4 — Cursor repository seam shape (codifies D4 + OQ-6 LOCK + D10)

The orchestrator's cursor seam grows from the Stage 2B-A read-only `LongPollCursorReader` SAM to a full read/write `LongPollCursorRepository` interface:

```
interface LongPollCursorRepository {
    suspend fun getLastSeenSeq(identityHex: String): Long?
    suspend fun upsertLastSeenSeq(identityHex: String, seq: Long, nowMs: Long)
}
```

This is a regular `interface`, not a `fun interface`, because a SAM cannot model two methods. `LongPollCursorReader` is RETAINED in the codebase as the Stage 2B-A read-only contract for callers that genuinely only need to read; the orchestrator constructor parameter migrates from `lastSeenSeqReader: LongPollCursorReader?` to `cursorRepository: LongPollCursorRepository?`. The `AppContainer` bridge lambda upgrades to implement both methods, delegating to the existing `SqlDelightLastSeenSeqRepository` for both.

**Both REST poll loops share the persisted cursor (OQ-6 LOCK).** The legacy `pollLoop`'s in-memory `lastSeenSeq` variable is decommissioned in Stage 2B-B. Both loops now query `cursorRepository.getLastSeenSeq(identityHex)` at the start of each iteration. There is a single source of truth for `since_seq` across both loops.

The repository implementation in `SqlDelightLastSeenSeqRepository` already enforces monotonicity inside a transaction (D5); Stage 2B-B introduces no path that writes outside that contract.

Cells M-B16 + M-B17 pin this.

### L5 — Canonical MAC encoding helpers (codifies D8 + D9)

A single `SeqMacVerifier` object lives in `:shared:core:transport`'s `commonMain` (NOT `:shared:core:crypto`). It calls `Auth.authHmacSha256` from the libsodium bindings already accessible through the existing `:shared:core:transport → :shared:core:crypto` dependency. The verifier:

- Encodes all multi-byte integers (`seq` u64, `envelope_id_len` u16, `sequence_ts` u64) using manual `ushr` bit-shift arithmetic. `java.nio.ByteBuffer` MUST NOT appear anywhere in the verifier — it is JVM-only and would break iOS targets the moment they land.
- Uses `String.encodeToByteArray().size` for `envelope_id_len`, never `String.length` (which is UTF-16 char count and is wrong for any multi-byte UTF-8 envelope id).
- Hex-decodes `_seqMacVerifyKey` to a 32-byte `ByteArray` before passing it as the HMAC key. The hex `String` is NEVER passed as the key directly — that produces an effective key of the ASCII hex bytes, which silently verifies wrong against every server-generated MAC.
- Hex-decodes the wire `seqMac` field to a 32-byte `ByteArray` before constant-time comparison (L1).
- Uses `RestFallbackOrchestrator.identityHex` (the constructor-bound receiving identity) as the canonical `identity_hex` field. Asserts `length == 64` at the seam.

The verifier's doc-comment carries a single-source-of-truth pointer to `services/relay/src/seq_mac.rs` so future readers track the canonical encoding to one file.

Cells M8 (13 golden vectors + M-B7 multi-byte UTF-8) + M-B8 (`identity_hex` derivation roundtrip) + M-B9 (hex-key-decode) pin this.

### L6 — Both REST poll loops enforce symmetric verify semantics (codifies D10 + D15 corollary + R2-S-B5)

After Stage 2B-B, the orchestrator has two REST poll loops: `wsActivePollLoop` (Stage 2B-A backbone) and the legacy `pollLoop` (state-machine-driven fallback). **Both loops enforce IDENTICAL `seq_mac` verify semantics and IDENTICAL verify-key state observance.**

Concretely:

- Both loops read the same `seqMacVerifyKey` state machine (L2). `KeyAbsent` → legacy unverified pass-through identically. `KeyPresent` → verify and gate cursor on result identically. `KeySuspended` → drop all envelopes identically.
- Both loops use the same `SeqMacVerifier` from L5 with the same canonical encoding helpers.
- Both loops use the same `constantTimeEquals` comparator from L1.
- Both loops emit the same outcome rules under verify failure: drop, no emit, no ack, no cursor advance.
- The `LongPollBreakerState.SuspendedOnPoison` state from L7 is observed by BOTH loops; reaching that state in either loop suspends BOTH.
- The `_macRefreshAttemptedFor` latch (L7) is shared between both loops; a refresh attempted because of `wsActivePollJob`'s envelope counts against the legacy `pollLoop`'s subsequent attempts on the same `envelope_id`.

This closes R2-S-B5: the breaker / suspension MUST NOT become a MAC verify downgrade. Neither REST poll loop is permitted to be an unverified ingestion bypass under any state.

Cells M-B17 (legacy `pollLoop` does NOT bypass under `SuspendedOnPoison`) pins this directly.

### L7 — Bad-MAC posture (codifies D15 + OQ-1 LOCK + OQ-7 REJECTED)

A bad-MAC envelope (`mac_mismatch` outcome, or `no_mac_field` when verify-key state is `KeyPresent`) is handled by a four-step posture, in order:

1. **Telemetry only.** A per-orchestrator-session, per-`envelope_id` counter (`_macFailCount: MutableMap<String, Int>`) increments on every verify-fail outcome. Structured log line `event=poll_mac_verify_repeat id=<8-char prefix> count=<n> seq=<n> reason=mac_mismatch|no_mac_field`. The counter is in-memory only; it resets on orchestrator restart. **No destructive action driven by the counter.**

2. **Drop and continue (OQ-1 LOCK).** Drop the envelope. No emit on `_inbound`. No ack to the relay. No cursor advance. Continue the loop (do NOT halt). This rule holds at EVERY retry count and applies to BOTH REST poll loops.

3. **One forced session/key refresh, latched per `envelope_id`.** When the counter reaches `MAC_REPEAT_REFRESH_THRESHOLD = 2`, the orchestrator calls `acquireOrRefreshToken(reason = "poll_mac_repeat", staleToken = token)` so that a stale `_seqMacVerifyKey` from a degraded earlier session is replaced. The refresh fires **exactly once per `envelope_id` within an orchestrator-session lifetime**: an in-memory `Set<String>` latch (`_macRefreshAttemptedFor`) records the envelope id at refresh time and short-circuits subsequent refresh requests for the same id. The latch resets only on orchestrator restart. The refresh path uses the existing `tokenMutex` discipline — Stage 2B-B does NOT introduce a second lock path. If the refresh itself fails, the L2 corollary fires (`KeySuspended`, no REST ingestion until successful re-authorisation).

4. **Suspend BOTH REST poll loops if the same envelope still fails after refresh.** A second verify failure on an `envelope_id` for which `_macRefreshAttemptedFor` already records a refresh transitions `LongPollBreakerState` to `SuspendedOnPoison`. Both `wsActivePollJob` and the legacy `pollLoop` observe the breaker (L6). Direct WSS and non-REST transports (Reality, Tor) STAY OPERATIONAL — the messenger remains usable. Structured log line `event=poll_mac_repeat_suspend reason=verify_fail_after_refresh source=<ws_active|legacy>` records which loop tripped the suspension first. The suspended state exits only when the orchestrator restarts OR `LongPollBreakerState` is explicitly closed by a manual recovery path.

**The rule "no ack, no delete, no cursor advance past the envelope" holds absolutely.** At any retry count. In any verifier state. On either REST poll loop. OQ-7 is explicitly REJECTED: no threshold value approves cursor advance on an unverified envelope, because under any verifier drift (stale key, encoding bug, version skew, DB corruption) the relay's copy of the envelope is the surviving authoritative source. The `MAC_REPEAT_REFRESH_THRESHOLD = 2` constant is the ONLY quantitative parameter; it controls when to refresh the session, not when to delete or advance past an envelope.

**Out of scope for Stage 2B-B.** Resolving queue liveness for a sustained-bad envelope requires a separate server-side design (server-side MAC revalidation, server-side quarantine on repeat client report, or an authenticated poison-report mechanism). Stage 2B-B explicitly accepts that, under a sustained-bad envelope, the REST long-poll path (BOTH loops) becomes degraded while Direct WSS and other transports keep running. That is the correct trade-off given the locked threat model.

Cells M-B12 (counter increments, no ack, no cursor) + M-B13 (refresh latched exactly once) + M-B14 (suspension after post-refresh failure) + M-B15 (Direct WSS operational under suspension) + M-B16 (relay queue + persisted cursor byte-identical to pre-poison) + M-B17 (legacy `pollLoop` cannot bypass) pin this.

### L8 — 410 reauth + hard timer caps (codifies D6 + D11 + OQ-4 LOCK)

**410 reauth backoff.** When a `/relay/poll` request returns `410 Gone`, the orchestrator:

1. Sets `staleToken = token` and calls `acquireOrRefreshToken(reason = "poll_410", staleToken = token)`. The existing `tokenMutex` serialises this against any concurrent 401 refresh path.
2. Inserts a backoff before the next poll attempt. Backoff is **capped exponential with a 5-second floor and a 60-second ceiling**. The first 410 backs off 5 s; each subsequent 410 doubles up to 60 s. Three consecutive 410s within 30 wall-clock seconds switches to the 60 s ceiling immediately. The backoff recovers to the 5 s floor after a successful (200-OK) poll.

**No hard local logout (OQ-4 LOCK).** Persistent 410 responses NEVER nuke the user's identity. The long-poll path may suspend or degrade per L7; Direct WSS remains operational. The identity binding is preserved across any 410 storm.

**Hard timer caps (D11).** Any timer value derived from a relay-supplied field is clamped to a hard ceiling regardless of the existing range gates: **breaker cooldown ≤ 120 s; 410 reauth interval ≤ 60 s**. This prevents a misbehaving relay from forcing arbitrarily long polling blackouts even if its `pollHoldSecs` value passes the existing `1..480` range gate.

Cells M14 (5 sub-cells covering: 410 → authSession → retry → 200; 410 reauth fails → no busy-loop; 410-then-410 within 30 s → ceiling; cursor preserved; 410 on `/relay/ack-deliver` does NOT trigger reauth dance per Stage 1.x Lock-3) pin this.

### L9 — Circuit breaker mechanism (codifies D7)

The breaker is a lightweight `LongPollBreakerState` sealed type plus a counter, **separate from `RestStateMachine`**. States:

- `Closed` — normal operation.
- `Open(reason: BreakerOpenReason)` — REST poll backs off aggressively; breaker timer counts down to half-open. Reasons include `ConsecutiveRestFailures` and `Status410Storm` (NOT `MacVerifyFailure` — that path goes through L7's SuspendedOnPoison instead).
- `HalfOpen` — named state with cancel-safe re-entry. ONE probe poll is issued; on success → `Closed`; on failure → `Open` with refreshed timer.
- `SuspendedOnPoison` — entered exclusively by L7's poison posture. Exits only on orchestrator restart or explicit recovery.

**Breaker open does NOT alter MAC verify semantics.** A loop running with breaker `Open` still gates cursor on verify, still observes the verify-key state machine, still uses the same `SeqMacVerifier`. Per R2-S-B5, the breaker MUST NOT become a MAC verify downgrade.

**Interaction with `RestStateMachine`.** Breaker open emits a named `RestStateMachine.Event.RestPollDegraded` event (read-only signal); `RestStateMachine` does NOT transition `RestMode` purely because of breaker state. The existing `RestMode.{WsActive, WsCandidate, RestActive}` semantics remain governed by their existing inputs.

**Breaker timer Job tracked and cancelled by `stop()`.** Same lifecycle discipline as `aliveTickJob` from Stage 2A. On `stop()` the timer Job is cancelled; the breaker state itself is reset to `Closed` on the next `start()` because Stage 2B-B does NOT persist breaker state across orchestrator lifecycles.

Cells M13 (5 transition sub-cells + M-13e for `stop()` during `Open`) pin this.

### L10 — Jitter source migration (codifies D12)

All nine existing `Random.Default.nextDouble()` sites in `RestFallbackOrchestrator` (currently at `:415, :468, :487, :591, :644, :662, :699, :803, :824, :848` — exact line numbers shift post-implementation) migrate to draws from the existing `Csprng` interface (`Csprng.uniformLong`) introduced in Stage 2A. The migration is total: NO `Random.Default`, `java.util.Random`, or `SecureRandom` references remain in the orchestrator after this stage. Tests pin both layers: a grep gate on `Random.Default | java.util.Random | SecureRandom` patterns plus a behavioural pin where a recording `Csprng` fake sees every jitter draw.

Cell M15 pins this.

## Tele2 LTE smoke gate (codifies D14 + OQ-5 LOCK)

WORKING_RULES rule 8 is binding for Stage 2B-B and **NOT waivable**. Stage 2B-B is NOT shippable without a real-device smoke test on Tecno + Tele2 LTE. The smoke covers six scenarios:

- **S1 — WS up, REST poll arrives, MAC verifies, no decrypt regression.** Five envelopes from peer; pass when every envelope reaches the chat exactly once, no `seq_mac_verify_fail`, no `decrypt_fail`, both `ws_active_poll_ok` and WS Deliver increment without storage-table duplicates.
- **S2 — WS down, REST primary, multi-envelope batch.** Four envelopes with `more=true` on the first poll; pass when all four arrive in `seq` order, the persisted cursor advances to the highest, and a cold restart polls with that `since_seq` and gets zero duplicates.
- **S3 — Token rotation mid-poll, 410 fires, re-auth + retry.** Pass when `poll_410_token_rotated` logs, a fresh `session_request` logs at re-auth, `ws_active_poll_ok` logs after retry, no envelope is duplicated or lost, cursor is preserved.
- **S4 — Server kill switch `RELAY_POLL_HOLD_SECS=0`.** Operator flips the env var on the VPS. Pass when the client receives short-poll cadence with the padded body shape, `poll_hold_secs=0` is parsed, the L8 timer gate returns the legacy timeout, and chat continues.
- **S5 — Voice notes uniform-functionality.** Voice notes from peer arrive in the same poll window as text sent at the same instant. Privacy-modes uniform-functionality lock holds: long-poll does not degrade voice.
- **S6 — Breaker open under Tele2 byte-budget death (mandatory per OQ-5 LOCK).** Sustain Tele2 LTE upload pressure to provoke Mode-2 cutoff (5-14 KB upload then silence per the byte-threshold finding). Pass when `breaker_open` logs within the threshold, cursor is preserved across the transition, envelopes continue on whichever path the breaker selects. Time-box ≥ 30 minutes; a controllable trigger is acceptable if natural Mode-2 does not reproduce in the window.

The smoke is run on the implementation PR before merge. The PR description records the device, carrier, time-box, and the pass / fail verdict per scenario.

## Test matrix M8-M17 + new cells

The implementation PR ships the following cells. M-prefixed cells extend the Stage 2B-A M1-M7 numbering; M-B prefixed cells are the Round 2 additions.

| Cell | Source set | Pins |
|---|---|---|
| **M8** | commonTest, golden-vector | `seq_mac` parity against all 13 server vectors in `services/relay/tests/seq_mac_vectors.rs` |
| **M9** | commonTest, behavioural | Verify success: known key + canonical input → `Verified`; deterministic |
| **M10** | commonTest, behavioural | Verify failures, 6 sub-cells: wrong key / single-bit-flipped `identity_hex` / off-by-one `seq` / pre-quantize `sequence_ts` / single-char-changed `envelope_id` (incl. UTF-8 byte-length collision) / tampered last hex char of `seq_mac` |
| **M11** | commonTest, behavioural | Cursor advance gate, 4 sub-cells: `(verify ∧ accept) → advance`; `(verify ∧ dedup) → advance`; `(verify ∧ storage-error) → NO advance`; `(verify-fail) → NO advance + no emit` |
| **M12** | commonTest, behavioural | Cursor monotonicity: out-of-order batch `seq = [3, 5, 4, 6]` with concurrent WS Deliver at `seq = 7`; persisted cursor `≥ 7` at end-of-batch; writer never called with non-increasing value |
| **M13** | commonTest, behavioural with `StandardTestDispatcher + advanceTimeBy` | Breaker state transitions, 6 sub-cells: (a) closed → open on N consecutive failures; (b) open → half-open after recovery window; (c) half-open + success → closed; (d) half-open + failure → open with refreshed timer; (e) cursor preserved across all transitions; (M-13e) `stop()` during `Open` cancels timer Job, no re-fire on next `start()` |
| **M14** | commonTest, behavioural | 410 reauth, 5 sub-cells: (a) 410 → `authSession` → retry with new token → 200 → envelope emitted; (b) 410 → re-auth fails → L2 corollary fires (`KeySuspended`); (c) 410-then-410 within 30 s → 60 s ceiling backoff; (d) cursor preserved across the 410 dance; (e) 410 on `/relay/ack-deliver` uses existing self-healing fall-through, NOT re-auth dance (Stage 1.x Lock-3 — T1 ack after T2 → 401, burning a fresh token there is wrong) |
| **M15** | commonTest, behavioural + apps/android androidUnitTest, source-parse | Jitter source = `Csprng`; grep gate covers `Random.Default`, `java.util.Random`, `SecureRandom`; behavioural recording fake sees every draw |
| **M16** | apps/android androidUnitTest | Release variant BuildConfig pin `LONGPOLL_V2_ENABLED == "0"` unchanged from Stage 2B-A |
| **M17** | commonTest, behavioural | Old-relay `MissingMac` outcome class: when `_seqMacVerifyKey` is empty (`KeyAbsent`), envelopes pass through with `reason=no_verify_key`; when `_seqMacVerifyKey` is non-empty (`KeyPresent`) and `PollEnvelope.seqMac` is empty, envelopes are dropped with `reason=no_mac_field` — discriminated outcomes |
| **M-B7** | commonTest, golden-vector | At least one golden vector with a multi-byte UTF-8 `envelope_id` (emoji or CJK); exercises the `String.length` vs `encodeToByteArray().size` trap |
| **M-B8** | commonTest, behavioural | `identity_hex` derivation round-trip: server-generated MAC verifies against client-derived `identity_hex` using `RestFallbackOrchestrator.identityHex` (the receiving identity) |
| **M-B9** | commonTest, behavioural | Hex-key-decode-before-use: assert MAC computed with `Auth.authHmacSha256(rawKeyBytes, message)` verifies; assert MAC computed with the hex `String` as ASCII bytes DOES NOT verify |
| **M-B10** | commonTest, behavioural with structured-concurrency cancellation | Cancellation-safety on verify-then-emit boundary: cancellation injected between `emit` return and the storage-acceptance callback does NOT advance the cursor |
| **M-B11** | commonTest, behavioural | Verify-key state machine transitions: `KeyAbsent → KeyPresent → KeySuspended → KeyPresent` paths exercised; the corollary path (failed refresh → `KeySuspended`) is exercised explicitly |
| **M-B12** | commonTest, behavioural | MAC repeat counter increments under sustained verify-fail; no ack, no cursor advance under any count |
| **M-B13** | commonTest, behavioural | First repeat triggers forced session refresh; latched exactly once per `envelope_id`; no second refresh on threshold multiples |
| **M-B14** | commonTest, behavioural | Second repeat after refresh transitions `LongPollBreakerState` to `SuspendedOnPoison` |
| **M-B15** | commonTest, behavioural | Direct WSS path remains operational while both REST poll loops are suspended |
| **M-B16** | commonTest, behavioural with fake storage | Persisted cursor and relay queue remain byte-identical to pre-poison state across all retry counts |
| **M-B17** | commonTest, behavioural | After `SuspendedOnPoison`, the same bad envelope returned through the legacy `pollLoop` is NOT emitted, NOT acked, and does NOT advance the cursor; both loops enforce identical verify semantics under suspension |

## PR-commit boundary

The implementation PR is structured as six commits, each independently green and individually reviewable. The bundle is one PR because the semantic layer is tightly coupled — splitting would create intermediate states where verify is partial or cursor advancement is half-wired.

- **C1 — `SeqMacVerifier` + commonMain BE helpers + constant-time compare.** Implements L1 + L5. Ships M8, M9, M-B7, M-B9.
- **C2 — Verify-key state machine + forced-refresh-failure corollary.** Implements L2 + the L2 corollary path. Ships M-B11.
- **C3 — `LongPollCursorRepository` seam + storage-acceptance callback + legacy `pollLoop` in-memory cursor decommission.** Implements L3 + L4. Ships M11, M12, M-B16. `AppContainer` bridge upgraded to implement both read and write methods.
- **C4 — Both REST poll loops symmetric verify + bad-MAC posture (L6 + L7).** Wires the MAC verify into both `wsActivePollLoop` AND the legacy `pollLoop`; introduces `_macFailCount`, `_macRefreshAttemptedFor`, `LongPollBreakerState.SuspendedOnPoison`. Ships M10, M17, M-B8, M-B10, M-B12, M-B13, M-B14, M-B15, M-B17.
- **C5 — 410 reauth backoff + circuit breaker + hard timer caps.** Implements L8 + L9 + D11. Ships M13, M14.
- **C6 — Jitter source migration + Tele2 LTE smoke runbook.** Implements L10. Ships M15. Adds a `docs/tracks/trek2-stage2b-b-tele2-smoke.md` runbook describing the device, carrier, scenarios, and pass criteria for the mandatory Tele2 smoke. M16 release-pin contract test runs against the C6 head.

The Tele2 LTE smoke run is performed against the C6 head (or a later C6+ release if review-fix commits land). The PR description records the smoke verdict per scenario before the PR exits draft.

## WORKING_RULES rule 8

Stage 2B-B is NOT shippable without the Tele2 LTE smoke. Rule 8 is binding and **NOT waivable** on this PR. S1-S5 are mandatory; S6 was promoted from optional to mandatory under OQ-5 LOCK with a ≥ 30 minute time-box on Tecno + Tele2 LTE. A controllable trigger is acceptable for S6 if natural Mode-2 does not reproduce in the window.

## Backward compatibility

- **Server-side.** Zero impact. The server contract (Stage 1.x + 2B-A surface) is unchanged. A Stage 2B-A client on the same master continues to operate identically; the only difference for Stage 2B-B is that it now verifies the `seq_mac` the server has been sending all along.
- **Old relays (pre-Stage-1.x).** Old relays do not announce `seq_mac_verify_key`; the client enters `KeyAbsent` state and runs the legacy unverified pass-through with `reason=no_verify_key`. No behaviour change for old relays.
- **Stage 2B-A shell flag.** `LONGPOLL_V2_ENABLED` BuildConfig pin remains `"0"` in release. Stage 2B-B does NOT flip the pin — that is the separate Stage 2B-C / promotion PR. Release-mode behaviour is byte-identical to Stage 2B-A.
- **Legacy `pollLoop` cursor.** The in-memory `lastSeenSeq` variable inside `pollLoop` is decommissioned (OQ-6 LOCK + D10). Both loops now share the persisted cursor. This is a structural change, but the wire behaviour is preserved: `since_seq` values sent to the server are the same monotonic sequence.
- **`ackInbound` path.** Unchanged. Stage 2B-B's bad-MAC posture EXPLICITLY does not ack bad envelopes (L7), so the ack path is invoked exclusively for verified-and-stored envelopes.

## What this scope does NOT do

- Does not address Stage 2B-C documentation cleanup (release notes, ADR rollup, public README).
- Does not flip `LONGPOLL_V2_ENABLED` to `"1"` in the release variant. That is a separate, deliberately ceremonial promotion PR.
- Does not introduce a server-side MAC revalidation, quarantine, or authenticated poison-report mechanism. Resolving queue liveness for a sustained-bad envelope is OUT OF SCOPE and requires a separate server-side design.
- Does not introduce a hard local logout on consecutive 410s (OQ-4 LOCK).
- Does not touch the Direct WSS fast path. Stage 2B-B is REST long-poll semantic work.
- Does not introduce voice / media padding (Stage 3).
- Does not address Reality Android repair (RC-LIBXRAY-REALITY-WIRE1 sub-track).
- Does not address Tor or Ghost mode tweaks.
- Does not add iOS targets. iOS support requires Kotlin/Native cross-compilation infrastructure that is forthcoming.
- Does not introduce new test infrastructure beyond what the M-cells require.

## After this PR

After Stage 2B-B merges, the long-poll backbone is semantically complete: integrity-checked, cursor-advanced under storage acceptance, breaker-protected, reauth-aware, jitter-CSPRNG. The next deliverable is Stage 2B-C (documentation cleanup). After 2B-C, Direct WSS hardening is the next track per the locked strategic order.
