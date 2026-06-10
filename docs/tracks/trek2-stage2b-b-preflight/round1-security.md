# Trek 2 Stage 2B-B — Security Review Round 1

> **Status — historical review input.** This file is one of five review documents whose findings were consolidated into [synthesis.md](synthesis.md). The binding decisions for Stage 2B-B implementation are in `synthesis.md § User locks (2026-06-10)` + D1-D15. Some open questions and "recommended" options in this file have since been superseded or amended. Do NOT code against guidance in this file directly; consult the synthesis.

**Reviewer role:** security  
**Stage under review:** Trek 2 Stage 2B-B — MAC verify, cursor write, circuit breaker, re-auth on 410  
**Base:** master `0ac29cf5` (Stage 2B-A merged as PR #306)  
**Date:** 2026-06-10  

---

## 1. Security Blockers (P1)

### B1 — Timing-oracle on MAC verify: variable-time comparison leaks verify-key to network attacker

**Attack scenario.** An on-path observer who can inject or replay envelopes with crafted `seq_mac` values can mount a timing side-channel against the verify step. If Stage 2B-B implements the MAC comparison as a standard string equality (`seqMac == expectedHex`), the JVM/Kotlin short-circuits the comparison on the first differing byte. An attacker who controls the relay (or a MITM after TLS is stripped) can vary the first hex digit of the injected `seq_mac` across 16 values and measure response latency (cursor-advance vs no-advance, or ack-vs-no-ack issued). After ~16 probes per byte position and 64 positions they can reconstruct the expected HMAC output byte-by-byte. This does not directly reveal the verify key, but it lets the attacker forge a valid-looking `seq_mac` for any `(identity_hex, seq, envelope_id, sequence_ts)` tuple without the key, collapsing the integrity contract entirely.

**Why it is possible now.** `Csprng.kt` and the existing cryptographic helpers in `commonMain` do not include a constant-time byte comparison utility. No constrained-time comparison primitive is imported from libsodium in the current `phantom.core.crypto` surface. When Stage 2B-B implements the verify call it will naturally reach for Kotlin string equality unless the requirement is stated explicitly. The `hmac` crate on the server side calls `mac.verify_slice(expected)` which uses `subtle`'s constant-time `CtOption`; the client side has no equivalent yet.

**Mitigation.** Stage 2B-B must implement MAC comparison using a constant-time byte-by-byte XOR accumulator — standard "all-bytes-XOR, check result is zero" pattern that does not branch on individual byte equality. This must operate on the 32 decoded raw bytes, not on the 64-char hex strings. The `phantom.core.crypto` module should expose a single `constantTimeEquals(a: ByteArray, b: ByteArray): Boolean` helper backed by the approach above; no external dependency is required. The verify site must decode `seqMac` from hex to `ByteArray` before calling the helper.

**Blocker:** YES. The timing oracle is exploitable by a relay that is compromised at the application layer (within the Stage 1.x threat model — "by-token cache corruption, accidental seq replay"). A MITM that triggers enough long-poll requests at low latency can reconstruct the expected MAC in O(16 × 64) network round-trips, which is feasible on a local-attacker LTE path.

---

### B2 — `seqMacVerifyKey` cached as plain `String` with no lifecycle invalidation on session boundary

**Attack scenario.** `RestFallbackOrchestrator._seqMacVerifyKey` (line 201, `RestFallbackOrchestrator.kt`) is a `var` of type `String`, overwritten on every successful `acquireOrRefreshToken` call. If a token rotation occurs mid-session (e.g. `410 Gone` re-auth path landing in Stage 2B-B), the verify key is refreshed atomically with the token because both writes happen inside `tokenMutex`. However: (a) the old verify key value remains live in JVM heap until GC; Kotlin `String` has no zero-on-drop equivalent — the bytes persist in heap. (b) More critically, a scenario where the `authSessionOnce()` call returns a `null` body but does NOT null out `_seqMacVerifyKey` (see the `token_invalidated_after_failed_refresh` path at line 935-939 of `RestFallbackOrchestrator.kt`) leaves the stale verify key cached while `sessionToken` is cleared to `null`. A subsequent poll that gets a token from a re-auth against a degraded relay could pick up an envelope, pass it to the verify path using the stale key from the previous session, and produce either a false-positive verify (envelope looks valid but belongs to a different session's key derivation) or a false-negative. The false-positive case is more dangerous: it allows cursor advancement under an incorrect key, silently mismapping `seq` values.

**Why it is possible now.** The failed-refresh path at `acquireOrRefreshToken` lines 929-939 nulls `sessionToken` and zeros `tokenExpiresAt` but does NOT reset `_seqMacVerifyKey = ""`. The slot is last written at line 950: `_seqMacVerifyKey = response.seqMacVerifyKey`. The failed-refresh code runs when `response == null` and skips line 950, leaving the old value intact.

**Mitigation.** In the `response == null` branch of `acquireOrRefreshToken` (at or near line 939), add `_seqMacVerifyKey = ""` alongside the existing `sessionToken = null; tokenExpiresAt = 0L` resets. The verify call site in Stage 2B-B must check `seqMacVerifyKey.isNotEmpty()` before attempting verification, and must reject all envelopes (drop, not pass-through) when the key slot is empty.

**Blocker:** YES. The stale-key-on-failed-refresh path is reachable whenever the relay returns a bad response during re-auth — exactly the degraded-relay scenario the Stage 1.x threat model explicitly covers.

---

### B3 — Cursor can advance before storage write completes (partial-write window)

**Attack scenario.** Stage 2B-B will call `LastSeenSeqRepository.upsertLastSeenSeq` after MAC verify passes and the envelope is accepted or deduped by the storage layer. The Stage 2B mini-lock (D8, from the prior synthesis) specifies that cursor advancement is gated on "MAC verify AND storage accept-or-dedup". If the implementation advances the cursor (`upsertLastSeenSeq`) before confirming that the message was actually persisted to the local database (i.e. before the `INSERT OR REPLACE` transaction commits), a process kill between cursor write and message write loses the message permanently. Guardrail A ("delivery never lost") is violated. This is not an attacker scenario but an implementation correctness constraint that has a direct security consequence: a malicious relay that sends a high-sequence-number valid-MAC envelope could trigger cursor advancement past pending real messages that were never stored.

**Why it is possible now.** `LastSeenSeqRepository.kt` (line 64) specifies `upsertLastSeenSeq(identityHex, seq, nowMs)` as a separate suspending call with no transaction coupling to the message insert. The interface does not enforce that the caller holds any lock or scope across the two writes. The ordering discipline is entirely the caller's responsibility.

**Mitigation.** Stage 2B-B must advance the cursor ONLY after the storage layer returns success or idempotent-duplicate for the envelope insert. The rule: `(decrypt success AND storage insert returns OK-or-dedup) → cursor advance`. On storage failure the cursor must NOT advance; the envelope is left in the relay queue for re-delivery on the next poll. Document this ordering as a named invariant in the call site with a comment, and add a test that simulates a storage failure and verifies the cursor does not advance.

**Blocker:** YES. This is a guardrail A violation path with a concrete relay-cooperation attack vector.

---

## 2. Security Concerns (P2)

### P2-1 — `seqMacVerifyKey` logged or accessible via `seqMacVerifyKey` public property

`RestFallbackOrchestrator.seqMacVerifyKey` (line 209) is a public `val` exposing the 64-char hex key. The existing log lines in `acquireOrRefreshToken` (e.g. the `token_cached` log at line 951-956) do not log this value directly, but the `REST_TRACE` log category is broad and a future developer adding a diagnostic line could easily include it. The Stage 1.x scope document (`docs/tracks/trek2-stage1x-server-prereq.md`, Log redaction section, item 2) explicitly forbids logging the derived `seq_mac_verify_key`. The `_seqMacVerifyKey` field has no zeroize-on-drop behaviour (it is a JVM `String`). This is an accepted residual risk given the JVM's memory model, but the public surface of `seqMacVerifyKey` should carry a doc-comment that (a) cites the log-redaction rule and (b) limits the visibility to package-internal or at most `internal` scope in Kotlin, not public. The current `val seqMacVerifyKey: String get() = _seqMacVerifyKey` is `internal` in scope per the class visibility but the KDoc does not call out the log prohibition.

**Severity:** P2. No immediate exploit; defence-in-depth gap.

### P2-2 — `pollLoop` in `RestFallbackOrchestrator` advances `lastSeenSeq` without MAC verify (legacy path)

`pollLoop` at line 684 of `RestFallbackOrchestrator.kt` sets `lastSeenSeq = env.seq` for every received envelope before emitting to `_inbound`. This is an in-memory-only cursor (not persisted) used only for the `?since_seq=` query parameter within the current session. It does not write to `LastSeenSeqRepository`. However, Stage 2B-B adds a persisted cursor via `LastSeenSeqRepository`. If `pollLoop` is not updated in Stage 2B-B to also pass through MAC verify before advancing its own in-memory `lastSeenSeq`, there are now two separate cursors: one verified (persisted) and one unverified (in-memory in `pollLoop`). The unverified one could produce a `since_seq` value that skips past envelopes the persisted cursor has not yet seen. This is a functional correctness issue that also has a security dimension: a relay that sends a high-`seq` envelope to `pollLoop` with a passing MAC can advance the in-memory cursor past a batch of envelopes sitting in the persisted cursor's window.

**Severity:** P2. Becomes P1 if `pollLoop`'s in-memory cursor is left unverified while `wsActivePollLoop` gains a verified persisted cursor — the two cursors diverge.

### P2-3 — Ghost-mode jitter does not include send-path timing normalisation

`Csprng.uniformLong` is the jitter source for the long-poll cadence (B4). The Stage 2B scope includes jitter for poll timing. Ghost mode requires 30 s base + jitter per the Trek 2 mini-lock. However, the mini-lock wording is for the hold-consumption (server-side hold) delay; it does not address the send path. If a Ghost-mode client sends a message immediately after receiving one (standard messaging behaviour), the timing correlation between poll completion and send is visible to a relay-layer observer even with polling jitter. This is a defence-in-depth gap rather than a Stage 2B-B blocker because the threat requires relay-side traffic analysis that the locked threat model does not promise to defend against.

**Severity:** P2. Not a blocker; note for Ghost track.

### P2-4 — `_seqMacVerifyKey` reset correctness under concurrent `acquireOrRefreshToken` calls

`tokenMutex` serialises all `acquireOrRefreshToken` calls, so concurrent reset-and-set scenarios on `_seqMacVerifyKey` are not a race in the traditional sense. However, the existing code structure means that between the moment `sessionToken = null` (line 937) and the moment any subsequent successful auth sets `_seqMacVerifyKey` again (line 950), a window exists where a reader of `seqMacVerifyKey` (Stage 2B-B's verify path) could read an empty string and reject all envelopes. This is the correct behaviour — better than using a stale key. Document this as intentional: empty `seqMacVerifyKey` → reject, do not pass-through.

**Severity:** P2. Correct behaviour once the B2 fix from Section 1 lands, but must be documented.

---

## 3. MAC Verify Implementation Rules (Locked)

### Location

The verify call MUST live inside the envelope-processing function that runs per-envelope after `PollEnvelope` is received and before any call to `LastSeenSeqRepository.upsertLastSeenSeq` or the decrypt pipeline. In the current architecture this is the inner `for (env in parsed.envelopes)` loop in `wsActivePollLoop` (line 842 of `RestFallbackOrchestrator.kt`) and the equivalent loop in `pollLoop` (line 678). Both sites must gate cursor advancement on verify. Stage 2B-B should extract a named `verifyEnvelopeMac(env: PollEnvelope, verifyKeyHex: String): Boolean` function so the logic is not duplicated between the two loop bodies and can be independently tested.

### Canonical encoding constraints (verbatim from server doc)

The Kotlin verify path must reproduce the following byte sequence for the HMAC-SHA-256 input, in this exact order:

```
b"phantom-seq-mac-v1\x00"         19 bytes   domain tag (fixed literal)
identity_hex                       64 bytes   lowercase ASCII (the receiving identity)
seq (big-endian u64)                8 bytes
envelope_id_len (big-endian u16)    2 bytes   byte length of UTF-8 encoded envelope_id
envelope_id_bytes                  variable   exact UTF-8 bytes (NOT null-terminated, NOT padded)
sequence_ts (big-endian u64)        8 bytes   post-quantize value (60_000ms floor)
```

Total = 101 bytes + `envelope_id_len` bytes. The `envelope_id` is `PollEnvelope.id` (`@SerialName("id")`). `sequence_ts` is `PollEnvelope.sequenceTs` (`@SerialName("sequence_ts")`). The `identity_hex` is the receiving identity bound to this session — the same value used as the key in `AuthSessionRequest.identityHex`, i.e. `RestFallbackOrchestrator.identityHex`. The `seq` is `PollEnvelope.seq` (`@SerialName("seq")`), a signed `Long` on the Kotlin side; it must be cast to an unsigned 8-byte big-endian value consistent with the server's `u64`. Use `seq.toULong().toLong()` with `ByteBuffer.allocate(8).putLong(seq).array()` to produce the 8-byte BE encoding, NOT `BigInteger`.

### Behaviour on verify failure

On `verifyEnvelopeMac` returning `false`:

1. **Do not advance the cursor.** `LastSeenSeqRepository.upsertLastSeenSeq` must NOT be called.
2. **Do not attempt to decrypt the envelope.** The envelope is structurally suspect — feeding it to the Double Ratchet decrypt path risks poisoning the ratchet state.
3. **Drop the envelope.** Do not emit it to `_inbound`. Do not ack it (`ackInbound` must not be called).
4. **Emit a structured log line** with these fields and no others (per Stage 1.x log-redaction rule):
   - `event = "poll_mac_verify_failed"`
   - `id = env.id.take(8)` (8-char prefix only — consistent with existing log discipline)
   - `seq = env.seq`
   - `reason = "mac_mismatch"` (if the verify key was present but MAC was wrong) or `reason = "no_verify_key"` (if `_seqMacVerifyKey` was empty)
   - Do NOT log `seqMac`, `seqMacVerifyKey`, or the decoded key bytes.
5. **Continue the loop.** A single failed envelope does not abort the poll session; subsequent envelopes in the batch are processed independently. This prevents a single crafted envelope from blocking delivery of all subsequent envelopes.

Empty `seqMac` field (old relay without Stage 1.x) must also fail verify with `reason = "no_mac_field"` and the same drop-and-log behaviour. Rationale: if the relay now claims Stage 1.x support via `seqMacVerifyKey` in the session response, an empty `seqMac` on a polled envelope is anomalous and must not be silently promoted to "unverified pass-through".

**Exception — old relay (seqMacVerifyKey empty).** When `_seqMacVerifyKey` is empty at session start (relay deployed without `RELAY_SEQ_MAC_KEY`), the verify path is not reachable. In this case Stage 2B-B must fall back to the legacy unverified path — cursor advancement gated only on storage accept-or-dedup, no MAC check. This preserves backward compatibility with pre-Stage-1.x relays. The fallback must be explicit and named (`reason = "no_verify_key"` in the skip log), not an accidental branch through the verify path.

### Key zeroing and memory lifetime

`_seqMacVerifyKey` is a Kotlin `String`, which is immutable and heap-allocated. The JVM provides no `Arrays.fill`-equivalent for `String`; zeroing is not feasible in idiomatic KMP. The acceptable mitigation is:

- Minimise the lifetime of the decoded raw key bytes. Decode `seqMacVerifyKey` from hex to `ByteArray` only at the moment of the verify call, inside the loop body. Do NOT cache the decoded `ByteArray` in a field. After the verify call completes, the `ByteArray` goes out of scope and becomes GC-eligible at the next GC cycle.
- Null the `_seqMacVerifyKey = ""` field on the failed-refresh path (B2 fix) to bound the heap lifetime of the string value.
- Document in the verify function that the decoded bytes are ephemeral by design.

### Timing-safe comparison

As detailed in B1: the comparison between the computed HMAC output and the wire `seqMac` field MUST be constant-time. Implement as:

```
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var acc = 0
    for (i in a.indices) acc = acc or (a[i].toInt() xor b[i].toInt())
    return acc == 0
}
```

Place in `phantom.core.crypto` (same module as `Csprng`). The verify call decodes both the expected (computed HMAC raw bytes) and the actual (`seqMac` hex-decoded to `ByteArray`) before calling `constantTimeEquals`. Do not compare the hex strings directly. The `hmac` crate on the server uses `subtle`-crate constant-time comparison; the Kotlin side must match the same discipline.

---

## 4. Cursor Write Attack Surface

### Replay (same envelope re-served, valid MAC, cursor monotonicity)

The relay can re-serve the same envelope on subsequent polls before the client acks it. A valid MAC on a replayed envelope must not re-advance the cursor. `LastSeenSeqRepository.upsertLastSeenSeq` has monotonicity enforcement — it is a silent no-op when the incoming `seq` is less than or equal to the current persisted value (see `LastSeenSeqRepository.kt` lines 28-31). This structurally closes the replay-advance scenario. However, the storage dedup check (envelope-id uniqueness in the message table) is a separate gate: the cursor must advance only after the storage layer returns "accepted" (new row) or "duplicate" (already present). A replayed envelope that returns "duplicate" from the message store is safe to include in cursor advancement because the `seq` is not new information and monotonicity ensures the cursor does not regress. Record this in a test: `replay_envelope_valid_mac_duplicate_storage_does_not_advance_cursor_past_current`.

### Malicious relay (forged MAC)

A malicious relay operator holds the root key, can derive any per-identity verify key, and can forge a valid MAC for any `(identity_hex, seq, envelope_id, sequence_ts)` tuple. The client cannot distinguish this from a legitimate envelope. This is explicitly out of the Stage 1.x threat model: "not a fully malicious relay operator unless signing key is outside the relay process." Record this residual risk by name in the Stage 2B-B implementation; do not attempt to close it in this stage.

### By-token cache corruption (wrong-recipient envelope)

The Stage 1.x scope states that `seq_mac` defends against "by-token cache race returning wrong identity's envelope batch." The MAC canonical input includes `identity_hex` (the receiving identity, 64 bytes fixed). A misrouted envelope addressed to identity B arriving in identity A's poll response will fail verify because the MAC was computed with B's verify key and is checked with A's verify key. The attack is caught. No additional client-side defence is needed beyond the verify path described above, provided that the `identityHex` used in the canonical encoding is always `RestFallbackOrchestrator.identityHex` (the session-bound identity), never sourced from the envelope's `fromHex` or any relay-provided field.

### Partial storage write

If the message table insert fails (disk full, SQLCipher error, transaction abort), the cursor MUST NOT advance. The ordering rule from B3 above applies: the call sequence must be `storageInsert() → cursor advance`, never the reverse. If `storageInsert()` throws or returns an error, drop the envelope, log `event = "poll_storage_fail_no_cursor_advance"`, and leave the envelope in the relay queue for re-delivery. Do not ack the envelope. This is the only way guardrail A ("delivery never lost") holds under partial-write conditions.

---

## 5. Re-auth on 410 Attack Surface

### Challenge freshness

On receiving a `410 Gone` with body `{"error":"session token rotated; obtain a fresh challenge"}`, the client must mint a NEW challenge via `GET /auth/challenge`. The existing `authSessionOnce()` call path in `RestFallbackOrchestrator.kt` (lines 976-980) already calls `getChallenge(identityHex)` which fetches a fresh nonce from the server. A fresh nonce is used every time. The client does NOT reuse the original challenge that triggered the 410. This is correct — re-using the stale challenge would produce another 410 (the server's `SessionChallengeCache` no longer holds it or its token has been rotated).

The 410 re-auth path in Stage 2B-B must feed through the existing `acquireOrRefreshToken(reason = "poll_410", staleToken = token)` path. The `staleToken` parameter causes `acquireOrRefreshToken` to skip the CAS-reuse check (because the cached token equals the stale one) and call `authSessionOnce()`, which mints a fresh challenge. No change to the challenge-mint path is required.

### Race: 410 while a send is in-flight

The Stage 1.x Lock-3 in-flight contract applies: if a poll request is in-flight on token T1 when T2 is issued (the legitimate client's 410 reauth path), the poll may complete on T1 and return an envelope, but the subsequent ack on that envelope MUST use T2 (T1 has been removed from `by_token`; an ack on T1 returns 401). The client already handles this via the `staleToken` CAS path in `ackInbound`. Stage 2B-B does not change this contract. What Stage 2B-B must ensure is that the `wsActivePollLoop` and `pollLoop`, which share `tokenMutex` via `acquireOrRefreshToken`, cannot both trigger a re-auth simultaneously — the mutex serialises them, so only one `authSessionOnce()` call fires. The second loop hits the CAS-reuse path. This is already correct at the current code shape.

### Rate-limit interaction

The 410 path calls `GET /auth/challenge` (a separate endpoint) and then `POST /auth/session`. If a buggy or adversarial relay serves 410 responses repeatedly, each one triggers a fresh challenge fetch and a fresh session issuance. The server's `SessionChallengeCache` has a 5-minute TTL (`SESSION_CHALLENGE_TTL` in `rest_fallback.rs`). If the client cycles through fresh challenges faster than the 5-minute TTL, it is effectively making one `POST /auth/session` call per re-auth without hitting the replay-safety cache. The relay's rate-limit on auth is the only backstop. Stage 2B-B should add a client-side 410 retry backoff — do not immediately re-auth on every 410; insert a backoff of at least `POLL_FAIL_BACKOFF_MS` (5 000 ms) before re-auth. This prevents a misbehaving relay from triggering a burst of auth calls and exhausting any server-side auth rate-limit window. Without this, a relay under a 410-injection attack could force the client into a rapid challenge-mint + session-issuance loop, consuming the rate-limit budget and then returning 429 on the legitimate path.

---

## 6. Carry-Forward Findings from Prior Synthesis

The following findings from the prior two-layer preflight (memory file `project_trek2_stage2b_preflight_synthesis_2026_06_10.md`) directly affect Stage 2B-B.

**N1 — Unsigned seq cursor poisoning (prior synthesis S4, BLK-2).**  
This finding stated that a malicious or compromised relay can return a fake higher `seq` value, advancing the client cursor past real envelopes and causing silent delivery loss. Stage 2B-B CLOSES N1 by implementing `seq_mac` verification before any cursor advance. After Stage 2B-B, cursor advancement requires a valid MAC over `(identity_hex, seq, envelope_id, sequence_ts)`, meaning the relay cannot forge a high `seq` without also holding the per-identity verify key. Within the locked threat model, N1 is closed for non-malicious-operator scenarios. For the malicious-operator scenario (operator holds root key, can derive verify key), N1 remains residual — record explicitly as accepted risk, not closed.

**N2 — Padding regression as guardrail-C violation (prior synthesis S6, BLK-3).**  
This finding identified that dropping `X-Phantom-Long-Poll: 1` when the circuit breaker opens causes the server to serve an unpadded response, telegraphing "degraded long-poll client" to an observer. Stage 2B-A CLOSED the mechanism by requiring both `X-Phantom-Long-Poll: 1` and `X-Phantom-Padded-Poll: 1` to be sent together under `longPollEnabled`, and by the circuit-breaker design dropping only `X-Phantom-Long-Poll` while retaining `X-Phantom-Padded-Poll` (per Stage 1.x Lock-2 four-cell wire contract). Stage 2B-B inherits this closure. The circuit-breaker implementation in 2B-B must honour the same header rule: open breaker = drop LP header only, retain PP header. Re-lock required if the circuit-breaker implementation diverges from this rule.

**N3 — Kill-switch malformed-input modes (prior synthesis §11, BLK-4).**  
This finding identified that `pollHoldSecs = Int.MAX_VALUE`, negative, or missing from the `SessionResponse` could cause unbounded hold timeouts. Stage 2B-A PARTIALLY CLOSED this by adding the `pollHoldSecs !in MIN_POLL_HOLD_SECS..MAX_POLL_HOLD_SECS_CAP` gate in `computeLongPollReadTimeoutMs` (line 1116, `RestFallbackOrchestrator.kt`), which returns `null` (legacy timeout) for out-of-range values. Stage 2B-B must ensure the circuit-breaker cooldown arithmetic and the re-auth interval arithmetic are also guarded against malformed server-supplied values. Specifically, the re-auth interval and the breaker cooldown must not be derived from relay-supplied values without range clamping. The user should re-lock this if Stage 2B-B derives any timing constant from `pollHoldSecs` outside of the existing `computeLongPollReadTimeoutMs` gate.

**N6 — B2 timeout asymmetry breaking server-side rollback (prior synthesis §14, BLK-10).**  
This finding identified that the client raised OkHttp timeout is keyed on the client-side flag only, not on the server-announced `pollHoldSecs`, leaving clients with a long timeout even when the server rolls back to `RELAY_POLL_HOLD_SECS=0`. Stage 2B-A CLOSED this structurally: `computeLongPollReadTimeoutMs` gates on both `longPollEnabled == true` AND `pollHoldSecs in 1..480`. When the server sets `RELAY_POLL_HOLD_SECS=0`, the `SessionResponse.pollHoldSecs` field is `0`, which is outside the `1..480` range, so the function returns `null` and the legacy short-poll timeout applies. Confirmed closed at `RestFallbackOrchestrator.kt:1115-1116`. Stage 2B-B does not reopen this if the circuit-breaker cooldown does not use a relay-derived value for its own timeout arithmetic (see N3 above).

---

## 7. Open Questions for the User (≤ 5)

**OQ-1.** The Stage 2B-B cursor-advance rule says "gated on MAC verify AND storage accept-or-dedup". There are two separate storage operations in the pipeline: (a) the message-table insert and (b) `LastSeenSeqRepository.upsertLastSeenSeq`. Should these two writes be in the same SQLCipher database transaction, or is sequential ordering (message insert succeeds → then cursor write) sufficient? A process kill between the two writes is safe with sequential ordering (message is present, cursor catches up on next poll), but a same-transaction approach gives stronger atomicity. The answer determines whether `LastSeenSeqRepository` must expose a transaction-scoped variant.

**OQ-2.** The `pollLoop` in `RestFallbackOrchestrator` maintains its own in-memory `lastSeenSeq` variable (line 572, 684) that is NOT persisted and NOT MAC-verified today (Stage 2B-A L4/L5). Stage 2B-B adds MAC-verified cursor advancement to `wsActivePollLoop`. Should `pollLoop`'s in-memory cursor also gain MAC verification, or should Stage 2B-B decommission `pollLoop`'s cursor and have it always poll with `since_seq = LastSeenSeqRepository.getLastSeenSeq(...)` instead? The two-cursor situation (P2-2 above) needs a single resolution.

**OQ-3.** The 410 re-auth path currently has no explicit client-side rate-limit on how rapidly re-auth is triggered. Should Stage 2B-B add a minimum backoff (≥ `POLL_FAIL_BACKOFF_MS`) between consecutive 410 re-auth attempts, or is the existing `tokenMutex` serialisation sufficient protection against burst re-auth?

**OQ-4.** An envelope with `seqMac = ""` (empty string) arrives on a client where `_seqMacVerifyKey` is non-empty (Stage 1.x relay deployed). The verify path cannot proceed — there is nothing to verify against. The rule proposed above is: drop the envelope and log `reason = "no_mac_field"`. Is this the correct behaviour, or should such envelopes be treated as a possible relay misconfiguration and quarantined (retained in a local error store) rather than dropped silently?

**OQ-5.** Stage 2B-B is the first client-side stage that writes to `LastSeenSeqRepository`. The repository is backed by the SQLCipher-protected `phantom.db`. If the database is inaccessible at cursor-advance time (e.g. the file is locked by an ongoing backup or migration), what is the desired failure mode: (a) drop the envelope and retry on next poll, (b) ack the envelope to the relay and log the cursor-write failure, or (c) hold the envelope in memory and retry the cursor write? Option (b) is guardrail-A-violating; option (a) is the safe choice, but it requires the caller to distinguish transient DB errors from permanent ones.
