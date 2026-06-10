# Trek 2 Stage 2B-B — Architecture Preflight Round 1

> **Status — historical review input.** This file is one of five review documents whose findings were consolidated into [synthesis.md](synthesis.md). The binding decisions for Stage 2B-B implementation are in `synthesis.md § User locks (2026-06-10)` + D1-D15. Some open questions and "recommended" options in this file have since been superseded or amended. Do NOT code against guidance in this file directly; consult the synthesis.

**Scope under review:** Stage 2B-B semantic layer — `seq_mac` verification, cursor advancement, circuit breaker, 410 re-auth, cadence/jitter.
**Branch:** `docs/trek2-stage2b-b-preflight`
**Shell baseline:** PR #306, master `0ac29cf5`.

---

## 1. Architectural blockers (P1)

### B1 — `seq_mac` verify key sourcing is session-scoped, but token rotation invalidates the cache silently

**What.** `_seqMacVerifyKey` is populated in `acquireOrRefreshToken` from `response.seqMacVerifyKey`
(`RestFallbackOrchestrator.kt:950`). The server derives the per-identity verify key from the root key and the identity hex — it is stable for a given identity for the lifetime of the root key. However, after a token rotation the orchestrator calls `acquireOrRefreshToken` again, which overwrites `_seqMacVerifyKey` with whatever the new `SessionResponse` returns. If Stage 2B-B's verification code captures the key value at loop startup, it will hold a stale reference after rotation; if it reads `_seqMacVerifyKey` on every envelope, it is racing against the `tokenMutex`-protected write in `acquireOrRefreshToken`.

**Why it matters.** A MAC verify call that uses a stale or empty key will always fail, causing the cursor to stall permanently and every envelope to be dropped on the verify path. An empty-key race (relay not yet redeployed with Stage 1.x) causing a false-negative is silent — the envelope may be silently rejected without the user seeing any error.

**Where.** `RestFallbackOrchestrator.kt:200-209` (`_seqMacVerifyKey`/`seqMacVerifyKey`). The rotation path is `acquireOrRefreshToken` at line 950. The verify call site does not exist yet — Stage 2B-B creates it.

**What to do.** Lock the key-read ordering: the verify key must be read inside the same `tokenMutex.withLock` block that a 410-triggered reauth will complete, OR the verify key must be read by value at the top of each envelope-processing iteration (after the token is confirmed valid), never captured across iterations. Additionally, define the fallback policy when `_seqMacVerifyKey` is empty: options are (a) accept envelope but skip cursor advance, or (b) reject envelope and pause. The policy must be locked before implementation.

---

### B2 — Cursor advancement has two possible call sites; the wrong choice breaks guardrail A (no delivery loss)

**What.** The scope specifies cursor advancement gated on (a) `seq_mac` verifies AND (b) storage layer accepts or deduplicates. The storage dedup result is an outcome from the existing pipeline downstream of `_inbound.emit()` / `_inbound.tryEmit()`. The orchestrator's `wsActivePollLoop` (`RestFallbackOrchestrator.kt:752-851`) emits to `_inbound` and then returns — it has no visibility into whether the downstream consumer persisted the envelope successfully.

**Why it matters.** If Stage 2B-B calls `upsertLastSeenSeq` inside `wsActivePollLoop` immediately after `tryEmit` (line 843), it advances the cursor before storage confirms the write. A crash between emit and persist leaves a permanently advanced cursor that will never re-request the lost envelope. This breaks guardrail A ("delivery never lost").

**Where.** `RestFallbackOrchestrator.kt:843` (current tryEmit site). `LastSeenSeqRepository.kt:64` (the `upsertLastSeenSeq` signature the cursor write must eventually call). The storage acceptance confirmation lives in the wire-up layer or the messaging service, not inside the orchestrator itself.

**What to do.** Stage 2B-B must define a callback or a dedicated channel by which the downstream consumer confirms storage acceptance back to the orchestrator — either a completion lambda per envelope, or a `Long -> Unit` acknowledgment function wired into `RestFallbackOrchestrator` alongside `lastSeenSeqReader`. The cursor advance must happen in that callback, not in the emit site. This requires a new constructor parameter on the orchestrator and a corresponding wire-up change in `AppContainer.kt`.

---

### B3 — 410 reauth must reuse `acquireOrRefreshToken` with `forceRefresh=true`, but the 410 may arrive mid-hold (long connection)

**What.** The Stage 1.x server returns 410 with body `{"error":"session token rotated; obtain a fresh challenge"}` when a replay-cache hit finds the token has been rotated (`rest_fallback.rs:1381-1387`). This can happen during a live long-poll hold (the server accepted the request with T1, but a concurrent client action triggered T2 issuance mid-hold). The server returns 410 at the END of the hold, not immediately on connection.

**Why it matters.** `wsActivePollLoop` (line 813) and `pollLoop` (line 656) currently treat non-200/non-401 status codes as unexpected and apply backoff. A 410 response is not currently handled as a token-rotation signal in either loop. If Stage 2B-B's 410 handler calls `acquireOrRefreshToken(forceRefresh=true)` while a concurrent 401-handling path is also refreshing, the two can race inside `tokenMutex` — safe only if the mutex is correctly held. The concern is that a 410 in `wsActivePollLoop` and a 401 in `pollLoop` fire in the same window.

**Where.** `wsActivePollLoop:813-831` (current non-200 handling). `pollLoop:656-668` (current 401 handling). `acquireOrRefreshToken:907-957` (the CAS/mutex path that Stage 2B-B must call for 410 reauth).

**What to do.** Add an explicit `410` branch in both `wsActivePollLoop` and `pollLoop` (or factor the shared logic into a helper). The 410 branch must set `staleToken = token` and set `forceRefresh = true` in the subsequent `acquireOrRefreshToken` call — the same discipline as the 401 CAS path. The existing mutex in `acquireOrRefreshToken` will serialise concurrent refresh attempts; this only works correctly if Stage 2B-B does NOT skip the mutex. Confirm that the `getChallenge` lambda (which calls the relay) is non-blocking from the mutex perspective — the mutex holds during `authSessionOnce`, which is a network call. This is an existing property of the code (see line 928); Stage 2B-B must not add a second lock that acquires tokenMutex from inside a tokio-style block that is itself awaited inside the mutex.

---

## 2. Architectural concerns (P2)

### C1 — Circuit breaker state is orthogonal to `RestStateMachine.RestMode` but shares lifecycle

The existing `RestStateMachine` tracks WS/REST mode transitions. The Stage 2B-B circuit breaker is a different concept: it tracks whether the WS transport is _currently degraded_, not which mode the state machine has converged to. These two states can diverge: the state machine may be in `WsCandidate` (probing WS recovery) while the breaker is open (WS is still failing). Stage 2B-B must decide whether the breaker is a new field internal to the orchestrator, a new state on `RestStateMachine`, or an independent component. Conflating the two risks making the state machine untestable and adding mode transitions that were not in the original lock.

### C2 — `computeLongPollReadTimeoutMs` is shared between `pollLoop` and `wsActivePollLoop` but the two loops have different semantic roles post-2B-B

After Stage 2B-B, `wsActivePollLoop` becomes the primary long-poll backbone and `pollLoop` remains the legacy state-machine-driven fallback. If Stage 2B-B installs a dedicated long-poll cadence on `wsActivePollLoop`, calling `computeLongPollReadTimeoutMs` with the same parameters in both loops means both get the same raised timeout. This may or may not be the intent. The shared helper at `RestFallbackOrchestrator.kt:1111-1119` is pure and is fine to reuse; the concern is that the cadence constants (`POLL_ACTIVE_MS`, `POLL_IDLE_MS`, `POLL_LONG_IDLE_MS` at lines 1150-1164) backing `pollIntervalMs()` are shared between both loops and were designed for the legacy short-poll cadence, not for long-poll semantics. If `wsActivePollLoop` is supposed to honour server hold times, the `delay(jitteredDelay)` at line 849 (which uses `pollIntervalMs()`) will fire immediately after the server-held response returns, starting the next hold cycle without any client-side jitter budget. The Csprng-based jitter from Stage 2A's `phantom.core.transport.Csprng` needs to be plumbed here, not just used for the per-request timeout.

### C3 — `_inbound.tryEmit` vs `_inbound.emit` in `wsActivePollLoop` creates a backpressure gap

`pollLoop` (line 684) uses `_inbound.emit(env)` which suspends until a collector is available. `wsActivePollLoop` (line 843) uses `_inbound.tryEmit(env)` which silently drops if the buffer is full. With `extraBufferCapacity = 32` on `_inbound` (line 147), a burst of 33 or more envelopes in a single long-poll response will silently drop the overflow under `tryEmit`. After Stage 2B-B begins cursor advancement, dropped envelopes that were already cursor-advanced would be permanently lost. This is a pre-existing issue in the shell, but Stage 2B-B's cursor advancement makes it load-bearing. The fix is either to switch `wsActivePollLoop` to `emit` (suspending) or to ensure cursor advancement never fires for an envelope that `tryEmit` dropped.

---

## 3. Interface evolution recommendations

**The question:** does `LongPollCursorReader` grow (add `upsertLastSeenSeq` to the existing SAM fun interface) or get replaced (new `LongPollCursorRepository` with both read and write methods)?

**For growing the existing interface.** Adding `upsertLastSeenSeq` to `LongPollCursorReader` requires no new file, no new `AppContainer` wire-up entry, and the name "reader" becomes a naming lie that the file already anticipates (the doc-comment at `LongPollCursorReader.kt:15` says "Stage 2B-B will replace this single-method interface with a full read/write seam"). Fewer moving parts in the diff.

**For introducing a replacement interface.** The existing `LongPollCursorReader` is a `fun interface` (SAM), meaning it has exactly one abstract method and every test that passes a lambda is structurally valid. Adding a second abstract method to a `fun interface` removes the SAM property and breaks all lambda-style test fakes. This is a compile-time break across every test that currently constructs `LongPollCursorReader { identityHex -> ... }`. The replacement approach introduces `LongPollCursorRepository` as a regular `interface`, deprecates `LongPollCursorReader`, and changes the orchestrator constructor parameter from `lastSeenSeqReader: LongPollCursorReader?` to `lastSeenSeqRepository: LongPollCursorRepository?` — with the `AppContainer` lambda bridge updated to implement both methods. This cleanly separates the read-only Stage 2B-A shell contract from the read/write Stage 2B-B runtime contract.

**Recommendation: replacement.** The doc-comment at `LongPollCursorReader.kt:15-16` explicitly forecasts that Stage 2B-B will "replace" this interface, not extend it. The SAM break is certain the moment a second method is added — the replacement approach makes that break explicit and allows test fakes to be lambda-constructed for the read method via the old interface (for legacy tests) while requiring a full class for the new interface (which is correct, since a write-path fake needs state to be verifiable). The `AppContainer.kt:1009-1011` wire-up lambda needs to grow in exactly one place. Naming: `LongPollCursorRepository` mirrors `LastSeenSeqRepository` from the storage layer and communicates read+write semantics without ambiguity.

**Concrete interface shape for `LongPollCursorRepository`:**

```
interface LongPollCursorRepository {
    suspend fun getLastSeenSeq(identityHex: String): Long?
    suspend fun upsertLastSeenSeq(identityHex: String, seq: Long, nowMs: Long)
}
```

This is interface-identical in method shapes to `LastSeenSeqRepository` (minus `count()` and `deleteAll()`), which means the `AppContainer` bridge can be a thin delegation object with zero logic.

---

## 4. Boundary diagram (text)

The following numbered steps describe one envelope's complete path through the Stage 2B-B semantic layer. This is not an existing code path — it is the target architecture Stage 2B-B must implement.

1. Server sends `/relay/poll` response with `PollEnvelope { id, seq, seq_mac, sequence_ts, ... }`.
2. `wsActivePollLoop` receives the response. Before emitting, it reads `seqMacVerifyKey` (sourced from `_seqMacVerifyKey`, written by the most recent `acquireOrRefreshToken` under `tokenMutex`).
3. The loop calls the HMAC-SHA-256 verify routine, replicating the canonical input: domain tag + identity_hex + seq (u64-BE) + envelope_id_len (u16-BE) + envelope_id_bytes + sequence_ts (u64-BE, post-quantize 60s floor). Inputs come from the `PollEnvelope` DTO fields. The verify key bytes are decoded from the 64-char hex `_seqMacVerifyKey`.
4. **MAC verify decision:**
   - FAIL: log `seq_mac_verify_fail`, do NOT advance cursor, do NOT emit to `_inbound`. (Policy: skip or halt is an open question — see OQ1 below.)
   - PASS or key absent (empty `_seqMacVerifyKey`): proceed to step 5. (Policy for absent key is an open question — see OQ2.)
5. `_inbound.emit(env)` (suspending — see C3 concern above). The downstream consumer (messaging service) decrypts and calls the storage layer.
6. **Storage accept/dedup callback fires back to the orchestrator** with `(envelopeId, accepted: Boolean)`. `accepted=true` means first write; `accepted=false` means dedup (already in store). Both outcomes are valid for cursor advance. (This callback is the new constructor parameter described in B2.)
7. Cursor advance: `longPollCursorRepository.upsertLastSeenSeq(identityHex, env.seq, nowMs())`. The repository's monotonicity guard (`SqlDelightLastSeenSeqRepository.kt:47`) silently no-ops if a concurrent path already advanced past this seq.
8. **Circuit breaker evaluation** occurs at the top of each `wsActivePollLoop` iteration, reading consecutive timeout/failure counters accumulated since the last successful step 1. If the counter exceeds the threshold: open the breaker. Open breaker means drop `X-Phantom-Long-Poll` header on the next call (short-poll posture) while retaining `X-Phantom-Padded-Poll` (guardrail C). The breaker lives inside the orchestrator — it is not a `RestStateMachine` state transition.
9. **410 reauth path** (parallel concern): when step 1 returns status 410, the loop extracts `staleToken = token`, then calls `acquireOrRefreshToken(reason="poll_410", staleToken=staleToken, forceRefresh=true)`. The existing `tokenMutex` serialises this against any concurrent 401 path. After successful reauth, the loop retries the poll without backoff (the server had a fresh envelope pending). The `_seqMacVerifyKey` is refreshed inside `acquireOrRefreshToken` as a side effect, so step 2's key is always fresh after reauth.

---

## 5. Open questions (≤ 5)

**OQ1 — MAC verify-fail policy: skip-and-continue or halt-loop?**
When `seq_mac` verification fails for an envelope, should the loop (a) skip that envelope, log the failure, and continue polling, or (b) halt `wsActivePollLoop` entirely and surface a fatal event? Skip-and-continue preserves liveness but silently loses the envelope; halt-and-surface prevents silent loss but could permanently stall the long-poll backbone on a single corrupt envelope. Which posture is correct for the Alpha threat model?

**OQ2 — Empty verify key policy: accept-without-verification or skip-cursor-advance?**
When `_seqMacVerifyKey` is empty (relay not yet redeployed with Stage 1.x), should the loop (a) accept envelopes without MAC verification and advance the cursor as if verification passed, or (b) accept envelopes but suppress cursor advancement (frozen cursor, same as Stage 2B-A)? Option (a) provides a smooth upgrade path; option (b) preserves the invariant that cursor advancement is always MAC-gated. Must be locked before implementation.

**OQ3 — Circuit breaker threshold and hysteresis: what numbers?**
The breaker must open on "consecutive WS timeouts / inbound idle." What is the consecutive-timeout count that opens the breaker, and what is the recovery criterion that closes it (e.g. one successful WS round-trip, or N successive WS round-trips)? The 2B-A scope doc does not specify these values. They affect Tele2 LTE behaviour directly and must be set before the Tele2 smoke gate.

**OQ4 — Cursor advancement for envelopes received on legacy `pollLoop` vs `wsActivePollLoop`: one shared repository, one shared monotonicity guard, but two concurrent writers?**
After Stage 2B-B, both `pollLoop` (state-machine-driven) and `wsActivePollLoop` (always-on backbone) may receive overlapping envelopes. If both loops implement cursor advancement independently, `upsertLastSeenSeq` will be called concurrently from two coroutines. The `SqlDelightLastSeenSeqRepository` wraps the write in a transaction for monotonicity, so the storage layer is safe. The question is whether `pollLoop` should also gain `seq_mac` verification and cursor advancement in Stage 2B-B, or whether only `wsActivePollLoop` gets the semantic layer in this PR, leaving `pollLoop` as a cursor-frozen path until a follow-on stage.

**OQ5 — Does the `ackInbound` path need to be updated to use the Stage 2B-B token (T2) after a 410-triggered rotation?**
The Stage 1.x Lock-3 in-flight contract specifies that an envelope returned on a T1 long-poll cannot be acked via T1 after T2 is issued — the ack must use T2. The orchestrator's `ackInbound` at line 515 calls `acquireOrRefreshToken` with no `staleToken`, which will return the current cached token (T2 after rotation). This appears safe by construction. However, if the 410 arrives before `ackInbound` is called but after the envelope was emitted to `_inbound`, there is a window where the downstream consumer holds an envelope with no valid ack token until the reauth completes. Should Stage 2B-B explicitly coordinate the reauth-complete signal with any pending ack calls, or is the existing CAS discipline in `acquireOrRefreshToken` sufficient?
