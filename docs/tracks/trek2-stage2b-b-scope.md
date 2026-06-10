# Trek 2 Stage 2B-B — Semantic Layer (Scope Mini-Lock)

**Status:** Locked scope mini-lock for Stage 2B-B. No code written. This document is the binding contract for the implementation PR that follows it.

**Why this PR exists.** Trek 2 Stage 2B-A (PR #306, master `0ac29cf5`) shipped the client-side shell of the long-poll backbone: opt-in headers, raised read/call timeout under a flag, parallel `wsActivePollJob`, read-only cursor seam, presence-only `seq_mac` parsing. The shell is byte-for-byte runtime-equivalent at the release pin and exercises none of the semantic invariants. Stage 2B-B is the **semantic side**: it makes the backbone actually defend message integrity and actually advance the cursor.

The two-round preflight (PR #307, master `f8cdc91a`, bundle at [docs/tracks/trek2-stage2b-b-preflight/](trek2-stage2b-b-preflight/)) consolidated four parallel domain reviews plus an independent second-pass cross-check into a single binding synthesis: 15 locked decisions D1-D15, six user locks OQ-1 through OQ-6, one rejected open question OQ-7. This scope-doc lifts those decisions into a per-lock contract that the implementation PR types itself against. **No new decisions are invented here.** Everything below either references a synthesis lock by tag or carries a single-sentence corollary that the synthesis already established.

**What lands after this PR.** Stage 2B-C is the documentation cleanup (release notes, ADR rollup, public README updates). Stage 2B-D is the release-flag promotion + rollout-gate PR that actually flips `LONGPOLL_V2_ENABLED` to `"1"` in the release variant; until 2B-D ships, the backbone is wired but inactive in production builds. See the *After this PR* section at the end of this scope-doc for the full sequencing. Direct WSS hardening is the next track after 2B-D, not after 2B-C.

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
- **`KeySuspended`** — the orchestrator cleared the key after a failed refresh OR after a `KeyPresent` session received an empty/malformed key on a subsequent refresh. Behaviour: drop all envelopes, no emit, no cursor advance, until a subsequent successful `acquireOrRefreshToken` returns a fresh, non-empty, 64-hex key (transition to `KeyPresent`).

**Locked transition matrix (load-bearing: prevents MAC-verify downgrade).** The initial state of the state machine is `KeyAbsent` — bootstrapping is just the first refresh observation, not a special pre-state. This collapses the matrix to a clean 3 states × 4 outcomes = 12 cells. A `KeyPresent` session that subsequently receives an empty or malformed verify key from the relay MUST NOT regress to `KeyAbsent` — that would re-open the legacy unverified pass-through and constitute a MAC verify downgrade triggered by relay state.

**Refresh outcome taxonomy (exhaustive):**

- `Empty` — the relay responded 2xx with `seq_mac_verify_key == ""`.
- `Valid(hex)` — the relay responded 2xx with a 64-character lowercase-hex `seq_mac_verify_key`.
- `Malformed` — the relay responded 2xx with a non-empty `seq_mac_verify_key` that is NOT 64 lowercase-hex characters (non-hex, odd length, wrong length, uppercase). Stage 1.x server contract emits only the valid shape, so a `Malformed` observation is a relay bug or a hostile injection.
- `Failure` — the relay did not respond 2xx (null body, 4xx, 5xx, network exception, timeout). This is the L2 corollary (forced-refresh-failure) input.

**Locked matrix (12 cells, exhaustive):**

| Current state | Refresh outcome | Next state | Notes |
|---|---|---|---|
| `KeyAbsent` | `Empty` | `KeyAbsent` | Old-relay steady state. Legacy unverified pass-through allowed. |
| `KeyAbsent` | `Valid(hex)` | `KeyPresent(hex)` | Bootstrap or mid-session relay upgrade. Verify path activates. |
| `KeyAbsent` | `Malformed` | `KeySuspended` | Stage 1.x server never emits a malformed key. A malformed observation is a bug or hostile injection — fail safe by suspending ingestion until a valid key arrives. M-B19 fail-closed covers this. |
| `KeyAbsent` | `Failure` | `KeyAbsent` | Bootstrap failure or transient network error during refresh on an old-relay session. No token published, ingestion paused via the existing `sessionToken == null` discipline. The verify-key state is `KeyAbsent` because we have not yet observed a relay claiming otherwise. |
| `KeyPresent(_)` | `Empty` | `KeySuspended` | **Locked downgrade protection.** Even though the relay sent an empty value, the orchestrator does NOT regress to `KeyAbsent`. The relay's intent is ambiguous (genuine downgrade attempt or transient bug); the safer fallback is to suspend ingestion until a non-empty key reappears. |
| `KeyPresent(_)` | `Valid(newHex)` | `KeyPresent(newHex)` | Normal rotation. The new value replaces the old; cells under `_inboundStateMutex` see only one consistent value (M-B25). |
| `KeyPresent(_)` | `Malformed` | `KeySuspended` | Same downgrade protection. M-B19 fail-closed also covers this. |
| `KeyPresent(_)` | `Failure` | `KeySuspended` | The L2 corollary (forced-refresh-failure rule). |
| `KeySuspended` | `Empty` | `KeySuspended` | Suspension persists. No transition back to `KeyAbsent` ever. |
| `KeySuspended` | `Valid(hex)` | `KeyPresent(hex)` | Normal recovery path. Ingestion resumes. |
| `KeySuspended` | `Malformed` | `KeySuspended` | Same downgrade protection. |
| `KeySuspended` | `Failure` | `KeySuspended` | No regression; the L2 corollary's "no REST ingestion until successful re-authorisation" rule continues. |

The transition matrix is implemented as TWO NESTED exhaustive `when` blocks — outer over the sealed `VerifyKeyState` type, inner over the sealed `RefreshOutcome` type. The pattern:

```
sealed class VerifyKeyState { object KeyAbsent; data class KeyPresent(val hex: String); object KeySuspended }
sealed class RefreshOutcome { object Empty; data class Valid(val hex: String); object Malformed; object Failure }

fun transition(current: VerifyKeyState, outcome: RefreshOutcome): VerifyKeyState =
    when (current) {
        VerifyKeyState.KeyAbsent -> when (outcome) {
            RefreshOutcome.Empty       -> VerifyKeyState.KeyAbsent
            is RefreshOutcome.Valid    -> VerifyKeyState.KeyPresent(outcome.hex)
            RefreshOutcome.Malformed   -> VerifyKeyState.KeySuspended
            RefreshOutcome.Failure     -> VerifyKeyState.KeyAbsent
        }
        is VerifyKeyState.KeyPresent -> when (outcome) {
            RefreshOutcome.Empty       -> VerifyKeyState.KeySuspended
            is RefreshOutcome.Valid    -> VerifyKeyState.KeyPresent(outcome.hex)
            RefreshOutcome.Malformed   -> VerifyKeyState.KeySuspended
            RefreshOutcome.Failure     -> VerifyKeyState.KeySuspended
        }
        VerifyKeyState.KeySuspended -> when (outcome) {
            RefreshOutcome.Empty       -> VerifyKeyState.KeySuspended
            is RefreshOutcome.Valid    -> VerifyKeyState.KeyPresent(outcome.hex)
            RefreshOutcome.Malformed   -> VerifyKeyState.KeySuspended
            RefreshOutcome.Failure     -> VerifyKeyState.KeySuspended
        }
    }
```

Kotlin's compile-time exhaustiveness check works only on a single sealed type per `when` block; `when (state to outcome)` would compile against the generic `Pair` type without exhaustiveness coverage of the 12-cell cross product. The nested `when` pattern makes accidental omission of any state OR outcome row a compile error. Cell M-B11 covers every row.

**Publication sites.** The `Failure` row is published from the `response == null` branch of `acquireOrRefreshToken` (the existing path that nulls `sessionToken` and zeros `tokenExpiresAt` at lines ~929-939); the `Empty` / `Valid` / `Malformed` rows are published from the success branch at the existing `_seqMacVerifyKey = response.seqMacVerifyKey` line. Both publication sites take the same brief `_inboundStateMutex.withLock { ... }` block from inside the already-held `tokenMutex` critical section (L6 lock order). The outcome classifier — non-empty hex of length 64 → `Valid`; empty string → `Empty`; non-empty but malformed → `Malformed` — runs at the publication site, NOT in the relay; the orchestrator never trusts the relay to label the outcome.

**Forced-refresh-failure corollary (locked, derives from D2 + D15).** If a forced session/key refresh under L7 itself fails (the relay returns `null` body, 4xx, 5xx, or a network exception), the verify-key state transitions to `KeySuspended` immediately. **No REST ingestion happens on either `wsActivePollJob` or the legacy `pollLoop` until a subsequent successful re-authorisation moves the state back to `KeyPresent`.** This is the single sentence the scope-doc must surface so an implementer does not silently keep ingesting under a stale-or-empty key after a failed refresh.

The state machine is observed by BOTH REST poll loops (L6). Cells M-B11 (state transitions) + M-B14 + M17 pin this.

### L3 — Cursor advance via successful-ack signal (codifies D3 + OQ-2 LOCK)

Cursor advancement (`upsertLastSeenSeq`) does NOT live in the orchestrator's poll loops, and it does NOT fire before the relay's `/relay/ack-deliver` returns success. The cursor advance is the LAST step of a strict three-step chain: `storage accept-or-dedup → relay ack confirmed → cursor persist`. Reversing any pair in that chain breaks `HybridRelayTransport`'s self-healing `ReAck` path — if the cursor advanced before the ack landed, a relay still holding the envelope would re-deliver it, the next poll would arrive with `since_seq > env.seq` already, the `ReAck` branch would never get its second chance, and the envelope would be lost from the local view.

**Step 1 — pending-seq mapping at the orchestrator emit site (single insertion point).** The map insertion happens at EXACTLY ONE call site: inside the orchestrator's poll loop, immediately BEFORE `_inbound.emit(env)`. Pseudocode:

```
_inboundStateMutex.withLock {
    _pendingSeqForAck[env.id] = env.seq
}
_inbound.emit(env)        // mutex released; consumer may run on a different thread
```

`HybridRelayTransport` is the consumer of `_inbound`, not a co-author of the map. The handler's `Action.Emit` branch documented in Step 3 below is the CONSUMPTION point (the consumer reads the envelope and eventually triggers `sendDeliveryAck`), not a second insertion point. There is no second emit-site in `HybridRelayTransport` or anywhere else that populates `_pendingSeqForAck`; a future caller that wants to advance the cursor by `seq` for an envelope that NEVER came through the orchestrator's poll loop has no path here and must use a different mechanism. This keeps the producer-consumer split clean: orchestrator produces (seq is observable on its `PollEnvelope`); transport consumes (seq is not on `RelayMessage.Deliver`).

The map is purely in-memory; on orchestrator restart it is empty and pending envelopes re-arrive on the next poll. Access to the map is serialised through `_inboundStateMutex` (see L6 below).

**Step 2 — orchestrator entry point (single combined method, three-phase mutex pattern + bounded local retry on cursor-after-ack failure).** The orchestrator exposes a single new entry point that combines the ack call AND the cursor finalization into one suspending function the caller cannot accidentally split:

```
suspend fun ackInboundAndAdvanceCursor(envelopeId: String): AckOutcome
```

The orchestrator is already constructor-bound to its `identityHex`; callers do NOT pass it. The combined method REPLACES the previous draft's split between `ackInbound(envelopeId)` and `onEnvelopeAcked(envelopeId, ackSucceeded)`. The split was structurally unsafe: a coroutine cancellation arriving AFTER `ackInbound` returned `AckOutcome.Acked` but BEFORE `onEnvelopeAcked` was even entered would have left the relay queue cleared, the cursor un-advanced, and `_pendingSeqForAck[envelopeId]` leaked until the orchestrator restarted. The combined method closes this window by owning the entire post-ack lifecycle internally and wrapping it in `withContext(NonCancellable) { ... }` from the moment `AckOutcome.Acked` is observed.

The implementation MUST follow this three-phase pattern so storage I/O is NEVER held under `_inboundStateMutex`. On `AckOutcome.Acked` the cursor write is bounded-retried INSIDE the orchestrator because once the relay has 2xx'd the ack, it has already removed the envelope from its queue and will NOT re-deliver it — the `ReAck` branch will NOT fire and the entry cannot be relied on for an external retry.

**Why the outer `withContext(NonCancellable)` of the previous draft was wrong.** Wrapping the entire post-ack region in `withContext(NonCancellable)` would have (a) prevented `CancellationException` propagation from `upsert` and `delay`, (b) made `coroutineContext[Job]?.isCancelled` always observe the `NonCancellable` job's `isCancelled = false` regardless of the caller's job state, and (c) potentially allowed the function to return successfully after caller cancellation without naturally rethrowing the cancellation signal (`withContext(NonCancellable)` can complete even after a parent job is cancelled — see Kotlin's `kotlinx-coroutines-core` documentation on `NonCancellable`). The corrected pattern keeps the retry loop fully cancellable and wraps ONLY the `finally` cleanup in `NonCancellable`, so caller cancellation aborts retries as intended, the entry is still removed from the map under the cleanup's `NonCancellable` umbrella, and `CancellationException` naturally rethrows to the caller after `finally` completes. Cancellation-status tracking uses an explicit `var wasCancelled` flag captured in the outer (cancellable) scope, NOT a runtime `Job` query from inside `NonCancellable`.

```
suspend fun ackInboundAndAdvanceCursor(envelopeId: String): AckOutcome {

Phase 0 (cancellable — caller cancellation aborts before the ack fires):
    val ackOutcome = ackInbound(envelopeId)
    if (ackOutcome !is AckOutcome.Acked) {
        // Ack failed (network, 5xx, capability disabled). Cursor stays,
        // entry stays in _pendingSeqForAck for the next ack attempt.
        // The relay still holds the envelope; the `ReAck` branch will
        // trigger on the next poll and retry the ack.
        return ackOutcome
    }

    // From here on the relay has CLEARED the envelope from its queue.
    // We MUST remove the `_pendingSeqForAck` entry regardless of what
    // happens next, including cancellation. The retry loop ITSELF
    // stays cancellable (so the caller can abort a slow shutdown);
    // only the cleanup in `finally` is wrapped in NonCancellable.
    //
    // Critical: there is NO suspension point between the `return`
    // above and the `try { ... }` block. Cancellation cannot leak
    // into the gap. The first suspension point is
    // `_inboundStateMutex.withLock` INSIDE the try; from that point
    // on, `finally` guarantees cleanup.

    var pendingSeq: Long? = null
    var upsertOk = false
    var wasCancelled = false
    try {

Phase 1 (mutex held — first suspension point):
        pendingSeq = _inboundStateMutex.withLock {
            _pendingSeqForAck[envelopeId]   // read only; do NOT remove yet
        }
        if (pendingSeq == null) return ackOutcome   // no-op; nothing to clean up

Phase 2 (mutex released, bounded retry loop — fully cancellable):
        try {
            // Index iteration to keep the backoff lookup in sync with the attempt
            // counter. delay() runs only BETWEEN attempts, never after the last
            // attempt — `CURSOR_WRITE_RETRY_BACKOFF_MS.size` must equal
            // `CURSOR_WRITE_MAX_ATTEMPTS - 1` by contract.
            for (attemptIdx in 0 until CURSOR_WRITE_MAX_ATTEMPTS) {
                try {
                    cursorRepository.upsertLastSeenSeq(identityHex, pendingSeq!!, nowMs())
                    upsertOk = true
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    // Cancellation MUST propagate. `runCatching` would
                    // have silently caught it and turned this into
                    // `upsertOk = false`. Rethrow to the outer catch,
                    // which sets `wasCancelled = true` and re-throws,
                    // unwinding through `finally`.
                    throw ce
                } catch (t: Throwable) {
                    // All other failures (SQLCipher I/O, disk full,
                    // transaction abort, etc.) feed the retry loop.
                }
                if (upsertOk) break
                if (attemptIdx < CURSOR_WRITE_MAX_ATTEMPTS - 1) {
                    delay(CURSOR_WRITE_RETRY_BACKOFF_MS[attemptIdx])
                    // delay() throws CancellationException naturally;
                    // the outer catch below tags it as cancellation.
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            wasCancelled = true
            throw ce   // propagate to outer finally
        }
    } finally {

Phase 3 (cleanup — runs even under cancellation, wrapped narrowly in NonCancellable):
        withContext(NonCancellable) {
            _inboundStateMutex.withLock {
                // Remove the entry whether upsert eventually succeeded OR the
                // local retry budget was exhausted OR the coroutine was
                // cancelled OR `pendingSeq == null` (cancellation hit the
                // Phase 1 mutex read before the value was assigned).
                // `remove` is idempotent: a no-op if absent.
                _pendingSeqForAck.remove(envelopeId)
                // Log the exhaustion event ONLY for a genuine attempt-budget
                // exhaustion (`upsertOk == false`) AND not under cancellation
                // (`wasCancelled == false`) AND only when we actually
                // attempted a write (`pendingSeq != null`). The flag is
                // captured in the outer (cancellable) scope before entering
                // NonCancellable, so it correctly reports the caller's job
                // state — not the NonCancellable Job's `isCancelled = false`.
                if (!upsertOk && !wasCancelled && pendingSeq != null) {
                    log("event=poll_cursor_write_exhausted envelope_id=${envelopeId.take(8)} seq=$pendingSeq")
                }
            }
        }
    }
    return ackOutcome
}   // close ackInboundAndAdvanceCursor
```

**Backoff array contract (load-bearing).** `CURSOR_WRITE_RETRY_BACKOFF_MS.size == CURSOR_WRITE_MAX_ATTEMPTS - 1`. With `CURSOR_WRITE_MAX_ATTEMPTS = 3` the array is `longArrayOf(100, 500)` — exactly two backoffs, one between attempts 1 and 2 and one between attempts 2 and 3. There is NO backoff after the final attempt. An array length that does not match this contract is enforced by a `check(...)` assertion in the orchestrator's `init` block. (The `check` runs at construction time, not compile time; Kotlin has no compile-time array-length proof at this scope. Construction-time assertion still fires on every orchestrator instantiation including the test suite, which is sufficient to catch a mismatch before any release.)

**Locked constants for cursor-write retry:**

| Constant | Value | Rationale |
|---|---|---|
| `CURSOR_WRITE_MAX_ATTEMPTS` | `3` | One nominal + two retries. A SQLCipher failure that survives three attempts is structural (disk full, schema mismatch, file-corruption); further retries are a busy-loop. |
| `CURSOR_WRITE_RETRY_BACKOFF_MS` | `longArrayOf(100, 500)` | Indexed by `attempt - 1` for retries before attempt `attempt + 1`. Tight initial retry catches transient lock contention; the second waits longer for a transient I/O fault. |

On `ackSucceeded == false` → DO NOT advance the cursor. Leave the entry in `_pendingSeqForAck` for the next ack attempt. The relay still holds the envelope; the `ReAck` branch will trigger on the next poll and retry the ack. **This is the only branch that relies on ReAck-driven retry.**

**Step 3 — `HybridRelayTransport.handleRestInbound` integration.** The inbound handler currently at `apps/android/src/androidMain/kotlin/phantom/android/transport/HybridRelayTransport.kt:~954` already classifies REST-mirrored envelopes into four branches. Stage 2B-B replaces every call to `orchestrator.ackInbound(env.id)` with `orchestrator.ackInboundAndAdvanceCursor(env.id)`. There is no separate `onEnvelopeAcked` site for the caller to remember to invoke; the orchestrator owns the entire post-ack lifecycle. Branch-by-branch contract:

- **First check — `processedEnvelopeRepository.exists(env.id) == true` (already-processed dedup, line ~961).** The handler currently calls `orchestrator.ackInbound(env.id)`. Stage 2B-B replaces that call with `orchestrator.ackInboundAndAdvanceCursor(env.id)`. The handler still discards the return value (just like the existing `runCatching { orchestrator.ackInbound(env.id) }` pattern). This is the canonical "WS already stored the message via its own `messageId` path; the REST poll mirror now ack's the relay copy and advances the cursor by the REST `seq` carried on the mirror." There is no `seq` field on `RelayMessage.Deliver` (`shared/core/transport/src/commonMain/kotlin/phantom/core/transport/RelayEnvelope.kt`); the cursor advance happens here exclusively because `seq` is observable only on the REST `PollEnvelope`.
- **Second check (`RestInboundDeduplicator`) `Action.Emit` (fresh delivery, line ~975).** The handler emits a `RelayMessage.Deliver` on `_incoming`; `DefaultMessagingService` later decrypts, persists, and calls `sendDeliveryAck(messageId)`. The cursor advance fires inside `sendDeliveryAck` itself — see Step 4. The pending-seq mapping was already populated by the orchestrator at Step 1 (the single insertion site), BEFORE the envelope reached `_inbound` or `HybridRelayTransport`. The handler does NOT touch `_pendingSeqForAck`.
- **Second check `Action.SkipNoAck` (line ~990).** `DefaultMessagingService` is still mid-decrypt. No ack, no cursor advance, no map mutation. The previous `Emit` for this id already populated the map; Step 4 will fire when the eventual `sendDeliveryAck` completes.
- **Second check `Action.ReAck` (line ~999).** `DefaultMessagingService` already persisted the message and called `sendDeliveryAck` once, but the prior ack network round-trip failed. The handler currently retries `orchestrator.ackInbound(env.id)`. Stage 2B-B replaces that retry with `orchestrator.ackInboundAndAdvanceCursor(env.id)`. If the retry succeeds, the cursor advances; if it fails again, the map entry stays for the next poll.

**Step 4 — `sendDeliveryAck` integration.** `HybridRelayTransport.sendDeliveryAck(messageId)` at line ~1014 currently calls `orchestrator.ackInbound(messageId)`, captures the `AckOutcome`, and marks the dedup tracker `markAcknowledged` regardless of network outcome. Stage 2B-B replaces the `orchestrator.ackInbound(messageId)` call with `orchestrator.ackInboundAndAdvanceCursor(messageId)` and uses the returned `AckOutcome` for the existing `Acked` / `Failed` / `DisabledByCapability` branch logic. The cursor advance is now ENTIRELY internal to the combined method — `sendDeliveryAck` does not invoke a separate cursor entry point. On `AckOutcome.Failed` (network or 5xx), the dedup tracker still marks acknowledged so the `ReAck` path runs on the next redelivery — and on THAT next attempt's success, the cursor advance fires from inside the Step 3 `ReAck` branch's call to `ackInboundAndAdvanceCursor`.

**Step 5 — quarantine and storage failure (MUST NOT advance the cursor).**

- **Debug/beta `FAILED_MAC` hold-and-quarantine path** in `DefaultMessagingService` (around line ~2590, the `holdMacFailures = BuildConfig.DEBUG` branch from PR-CRYPTO-SESSION-REPAIR1). This path deliberately does NOT call `sendDeliveryAck` — the envelope is held in `decryptFailedEnvelopeRepo` while the X3DH repair path tries to recover, and the relay copy is retained for the recovery attempt. Stage 2B-B preserves this: when the hold path fires, `ackInboundAndAdvanceCursor` is NOT invoked. No cursor advance. The relay queue and the local quarantine are both authoritative until the recovery completes; the recovery path will eventually call `sendDeliveryAck`, which fires Step 4 and advances the cursor at THAT point.
- **Storage write failure** (disk full, SQLCipher I/O error, transaction abort that persists neither the message row nor any quarantine row). Drop the envelope, leave the relay queue copy intact for re-delivery, log `event=poll_storage_fail_no_cursor_advance envelope_id=<8-char prefix>`, do NOT invoke `ackInboundAndAdvanceCursor`. The next poll re-delivers; the `processedEnvelopeRepository.exists == false` + `restDedup` flow re-attempts the insert. This is the rule that holds guardrail A under partial-write conditions.

**Step 6 — release-mode `FAILED_MAC` path.** In release mode `holdMacFailures == false` (production); the existing `markProcessed + sendDeliveryAck` path runs on a permanent decrypt failure exactly as it does today, treating the envelope as consumed. `sendDeliveryAck` fires Step 4 normally; the cursor advances on `AckOutcome.Acked`. This preserves Stage 2B-B's guardrail A by inheriting the existing release contract — a release client that cannot decrypt has no path to recover the message anyway, so retaining the relay copy gains nothing.

**Storage transaction discipline (OQ-2 LOCK).** Sequential commits are acceptable: message-table insert commits first, then `sendDeliveryAck` invokes `ackInboundAndAdvanceCursor`, which itself runs ack → cursor write under the combined-method's internal phases. Crash recovery is correct under either ordering by the existing relay queue semantics:

- **Crash between message persist and the ack network attempt.** The relay still holds the envelope. Next poll re-delivers it. The first check (`processedEnvelopeRepository.exists == true`) fires from Step 3 and re-ack's; on ack success the cursor advances normally.
- **Crash between `ackInbound` success and the cursor write.** The relay has ALREADY cleared the envelope from its queue (the relay removed it on receipt of the successful ack). The relay does NOT re-deliver this envelope. Recovery is "natural": the next legitimate envelope for this identity arrives with a higher `seq` (the relay's next-issued sequence), the normal cursor-advance flow runs against THAT envelope's seq, and the persisted cursor implicitly "skips" the lost seq value. The gap is fine — no envelope ever held that seq from the client's perspective. This is the actual recovery mechanism; the cursor does NOT need to "catch up to the missing seq" because there is no missing seq, just a sparse-but-monotonic sequence the cursor follows. Cell M-B26 pins this end-to-end with a `since_seq = 5 → crash on seq=6 → next poll returns seq=7 → cursor ends at 7` test.

Single SQLCipher transaction across the message insert and the cursor write is nice-to-have, NOT a blocker; the two redundancy paths above cover the failure modes without it.

Cells M11 + M12 + M-B16 + M-B20 + M-B21 + M-B29 pin this. M-B20 pins "ack network failure → no cursor advance, entry remains in `_pendingSeqForAck`, next poll's `ReAck` branch retries and on success advances the cursor"; M-B21 pins "debug-mode `FAILED_MAC` hold path does NOT invoke `ackInboundAndAdvanceCursor`, cursor stays frozen, relay queue copy remains for recovery"; M-B29 pins "cancellation arriving between `ackInbound` returning `Acked` and the cursor write does NOT leak `_pendingSeqForAck`; the combined-method `withContext(NonCancellable)` bridge handles the entire post-ack lifecycle as an indivisible unit."

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

### L6 — Both REST poll loops enforce symmetric verify semantics + concurrency-safe shared state (codifies D10 + D15 corollary + R2-S-B5)

After Stage 2B-B, the orchestrator has two REST poll loops: `wsActivePollLoop` (Stage 2B-A backbone) and the legacy `pollLoop` (state-machine-driven fallback). **Both loops enforce IDENTICAL `seq_mac` verify semantics and IDENTICAL verify-key state observance.**

Concretely:

- Both loops read the same `seqMacVerifyKey` state machine (L2). `KeyAbsent` → legacy unverified pass-through identically. `KeyPresent` → verify and gate cursor on result identically. `KeySuspended` → drop all envelopes identically.
- Both loops use the same `SeqMacVerifier` from L5 with the same canonical encoding helpers.
- Both loops use the same `constantTimeEquals` comparator from L1.
- Both loops emit the same outcome rules under verify failure: drop, no emit, no ack, no cursor advance.
- The `LongPollBreakerState.SuspendedOnPoison` state from L7 is observed by BOTH loops; reaching that state in either loop suspends BOTH.
- The `_macRefreshAttemptedFor` latch (L7) is shared between both loops; a refresh attempted because of `wsActivePollJob`'s envelope counts against the legacy `pollLoop`'s subsequent attempts on the same `envelope_id`.

**Concurrency-safe shared state.** Both poll loops run on the orchestrator's `scope` under `Dispatchers.Default`, which dispatches to multiple threads. Plain `MutableMap` / `MutableSet` for `_pendingSeqForAck` (L3), `_macFailCount` (L7 Step 1), `_macRefreshAttemptedFor` (L7 Step 3), `LongPollBreakerState` (L9), the breaker counters, AND `seqMacVerifyKey` state (L2) are all NOT thread-safe — concurrent mutation produces lost updates, double-counting, missed latch checks, or a stale-key vs fresh-key read torn across loop iterations. Stage 2B-B introduces a single orchestrator-scoped `Mutex` (`_inboundStateMutex: Mutex`) that serialises ALL read-modify-write access to these structures. Discipline:

- Every read-modify-write on `_pendingSeqForAck`, `_macFailCount`, `_macRefreshAttemptedFor`, `LongPollBreakerState`, any breaker counter, OR the L2 `seqMacVerifyKey` state machine value happens inside `_inboundStateMutex.withLock { ... }`. **The verify-key state machine is explicitly in scope of the mutex** — its reads (per-envelope verify lookup) and writes (`KeyPresent` ↔ `KeySuspended` ↔ `KeyAbsent` transitions on bootstrap / refresh / failed-refresh) MUST all flow through the mutex so a refresh-vs-poll race cannot produce a verify call that reads half a stale key and half a fresh one. The pattern for a per-envelope verify is "snapshot the verify-key state inside `_inboundStateMutex.withLock`, release, then run the verify against the snapshot."
- The mutex is held only across in-memory operations. Network I/O (`acquireOrRefreshToken`, `transport.poll`, `ackInbound`) and storage I/O (`cursorRepository.upsertLastSeenSeq`) are released BEFORE the suspending call to avoid holding the mutex across a network round-trip. The L3 three-phase pattern is the canonical example.
- `_inboundStateMutex` is distinct from `tokenMutex`. The order if both must be held: `tokenMutex` outer, `_inboundStateMutex` inner. No code path holds them in the reverse order; this is asserted by inspection at PR review time AND by the M-B23 ordering test.
- A token refresh that mutates `seqMacVerifyKey` state publishes the new state from INSIDE `acquireOrRefreshToken`'s existing `tokenMutex.withLock { ... }` critical section. Kotlin's `Mutex` is NOT re-entrant; a caller that wraps its own `tokenMutex.withLock { acquireOrRefreshToken(...) }` would deadlock the instant `acquireOrRefreshToken` tried to re-acquire `tokenMutex` internally. The correct pattern: at the existing line where `acquireOrRefreshToken` already assigns `_seqMacVerifyKey = response.seqMacVerifyKey` (already inside `tokenMutex`), Stage 2B-B adds a brief `_inboundStateMutex.withLock { ... }` block that publishes the transition-matrix outcome (see L2 matrix) onto the state machine. The poll loop never wraps `acquireOrRefreshToken` in `tokenMutex` itself; it just calls it directly (the function takes the mutex internally).
- `_inboundStateMutex` is initialised in the orchestrator's primary constructor body alongside `tokenMutex` and lives for the orchestrator's lifetime.

This closes R2-S-B5: the breaker / suspension MUST NOT become a MAC verify downgrade. Neither REST poll loop is permitted to be an unverified ingestion bypass under any state, and neither is permitted to race past the shared state checks.

Cells M-B17 (legacy `pollLoop` does NOT bypass under `SuspendedOnPoison`) + M-B22 + M-B23 pin this. M-B22 specifically pins "same `envelope_id` observed by both loops concurrently: counter increments exactly once, latch fires exactly once, no double cursor advance, no double refresh." M-B23 specifically pins "concurrent breaker open from both loops races to the same `LongPollBreakerState.SuspendedOnPoison` without inconsistent intermediate states."

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

**Hard timer caps (D11).** Any timer value derived from a relay-supplied field is clamped to a hard ceiling regardless of the existing range gates: **breaker cooldown ≤ 120 s (`BREAKER_COOLDOWN_CEILING_MS`); 410 reauth interval ≤ 60 s (`BREAKER_410_STORM_COOLDOWN_MS`); HTTP 429 `Retry-After` ≤ 120 s (`RETRY_AFTER_HARD_CAP_SECONDS = 120`, then multiplied by 1000 for `delay`)**.

**`Retry-After` plumbing — new in Stage 2B-B.** The current `RestFallbackResponse<T>` (`shared/core/transport/src/commonMain/kotlin/phantom/core/transport/RestFallbackTransport.kt:~114`) carries `statusCode`, `bodyParsed`, `rawBody`, `elapsedMs` and NO HTTP headers. Stage 2B-B adds a typed field:

```
data class RestFallbackResponse<T>(
    val statusCode: Int,
    val bodyParsed: T?,
    val rawBody: String,
    val elapsedMs: Long,
    val retryAfterSeconds: Long? = null,    // NEW in Stage 2B-B
)
```

The Android transport (`AndroidNativeOkHttpRestFallbackTransport`) parses the OkHttp `Response.header("Retry-After")` value into the typed field:

- Non-numeric, empty, negative, zero, or HTTP-date-form values → `null` (the client falls back to its own backoff).
- Numeric values are parsed as `Long` seconds.

The orchestrator clamps overflow-safely by clamping the SECONDS value to `RETRY_AFTER_HARD_CAP_SECONDS = 120` FIRST, then multiplying by `1000L`. This pattern avoids any `Long` arithmetic overflow regardless of the advertised value:

```
val clampedSeconds = retryAfterSeconds.coerceAtMost(RETRY_AFTER_HARD_CAP_SECONDS)
val delayMs = clampedSeconds * 1000L     // safe: max value 120_000L
delay(delayMs)
```

A relay sending `Retry-After: 86400` (one day) is clamped to 120 seconds before any multiplication; the multiplication can never overflow. The clamp applies in both REST poll loops AND in any send-path retry that consumes the header (Stage 1.x `HoldCapExceeded` returns `Retry-After: 30`; that value passes through the clamp unchanged because 30 s ≪ 120 s ceiling). Existing transport fakes (`RestFallbackOrchestratorTest.FakeTransport` and any test fake added in this stage) MUST also expose the typed field so the clamp logic is testable without a real OkHttp client.

This plumbing landing in C5 unblocks M-B24; without the typed field on `RestFallbackResponse`, the orchestrator has no signal to clamp. A misconfigured or hostile relay sending `Retry-After: 86400` (one day) MUST NOT lock the client out for a day. This prevents a misbehaving relay from forcing arbitrarily long polling blackouts even if its `pollHoldSecs` value passes the existing `1..480` range gate.

Cells M14 (5 sub-cells covering: 410 → authSession → retry → 200; 410 reauth fails → no busy-loop; 410-then-410 within 30 s → ceiling; cursor preserved; 410 on `/relay/ack-deliver` does NOT trigger reauth dance per Stage 1.x Lock-3) pin this.

### L9 — Circuit breaker mechanism + quantitative numbers (codifies D7 + D11)

The breaker is a lightweight `LongPollBreakerState` sealed type plus a counter, **separate from `RestStateMachine`**. All quantitative parameters are locked here; the implementation does NOT invent new constants.

**States:**

- `Closed` — normal operation. Default at orchestrator `start()`.
- `Open(reason: BreakerOpenReason, cooldownMs: Long)` — REST poll backs off; the breaker timer counts down `cooldownMs` to half-open.
- `HalfOpen(probeInFlight: Boolean = false)` — named state with cancel-safe re-entry. EXACTLY ONE probe poll is issued per `HalfOpen` entry, guarded by an atomic `probeInFlight` permit held under `_inboundStateMutex`. On entering `HalfOpen` the permit is `false`; the first poll loop that observes `HalfOpen` claims the permit (`probeInFlight = true`) under `_inboundStateMutex.withLock` and proceeds to issue the probe. Any other poll loop observing `HalfOpen(probeInFlight = true)` short-circuits and does NOT issue a probe; it sleeps (`delay(intervalMs)`) and re-observes the state on the next iteration. The probe outcome resolves the state: success → `Closed`; failure → `Open(reason, cooldownMs * growth)`. The permit prevents two simultaneous probe round-trips when both REST poll loops wake at the same instant past the cooldown timer.

  **Cancellation-safe permit release.** The probe call (`transport.poll(...)`) is a suspending function. If the claimant loop is cancelled mid-probe (mode transition, `stop()`, scope cancellation), the permit must be released so a subsequent `start()` cycle's first observer can re-attempt the probe. Without this, the breaker remains stuck in `HalfOpen(probeInFlight = true)` forever and the second loop perpetually short-circuits. The claimant's probe call is wrapped in `try { ... } finally { withContext(NonCancellable) { _inboundStateMutex.withLock { if (state is HalfOpen && state.probeInFlight) state = HalfOpen(probeInFlight = false) } } }`. The `finally` only resets the permit if the state is still `HalfOpen(probeInFlight = true)` — if the probe already resolved into `Closed` or `Open`, the `finally` is a no-op. The `withContext(NonCancellable)` ensures the cleanup itself cannot be cancelled inside its own critical section.
- `SuspendedOnPoison` — entered exclusively by L7's poison posture. Exits only on orchestrator restart or explicit recovery; the breaker timer does NOT auto-recover this state.

**`BreakerOpenReason` taxonomy:**

- `ConsecutiveRestFailures` — REST poll failed `N` consecutive times. Failure is one of: network `IOException` (DNS, connect, TLS, read), HTTP 5xx response, response timeout (the OkHttp `SocketTimeoutException` raised by the L2-gated read budget). Status 401 / 410 / 429 / 4xx-other are NOT transport failures (they have their own paths: 401 → token refresh, 410 → L8 reauth dance, 429 → respect `Retry-After`, other 4xx → drop + log + advance the counter only for diagnostic purposes).
- `Status410Storm` — `K` consecutive `410 Gone` responses within `W` wall-clock seconds. Transitions to `Open` immediately on the `K`th 410, with the cooldown set to the L8 ceiling so the 410 dance and the breaker dance do not race.

**Quantitative locks (final, no further tuning during implementation):**

| Parameter | Value | Rationale |
|---|---|---|
| `BREAKER_CONSECUTIVE_FAIL_THRESHOLD` (N) | `5` | Five consecutive network-class failures is the same threshold the existing `pollFailureCount` patterns elsewhere in the orchestrator use; mirrors the `SEND_MAX_ATTEMPTS = 5` send-side budget. |
| `BREAKER_INITIAL_COOLDOWN_MS` | `5_000` | Matches the existing `POLL_FAIL_BACKOFF_MS` floor used by the orchestrator's jittered backoff path. |
| `BREAKER_COOLDOWN_GROWTH_FACTOR` | `2.0` | Standard exponential growth. Half-open failure doubles the next cooldown. |
| `BREAKER_COOLDOWN_CEILING_MS` | `120_000` | Mirrors the D11 hard cap on relay-derived breaker cooldown — 120 s ceiling regardless of growth factor. |
| `BREAKER_410_STORM_THRESHOLD` (K) | `3` | Three 410s in close succession is a clear rotation-loop signal. |
| `BREAKER_410_STORM_WINDOW_MS` (W) | `30_000` | Mirrors the D6 "three consecutive 410s within 30 s → ceiling" trigger from L8 so the breaker and L8 do not race; they fire on the same condition. |
| `BREAKER_410_STORM_COOLDOWN_MS` | `60_000` | Matches the L8 410-reauth-interval ceiling so the breaker exit timer aligns with the L8 backoff exit timer. |
| `BREAKER_HALFOPEN_PROBE_BUDGET` | `1` | Exactly one probe poll per half-open entry. A failed probe re-opens with cooldown × `BREAKER_COOLDOWN_GROWTH_FACTOR`, capped at `BREAKER_COOLDOWN_CEILING_MS`. |

**Cooldown growth contract.** After a failed half-open probe, the next `Open` cooldown is `min(currentCooldown * BREAKER_COOLDOWN_GROWTH_FACTOR, BREAKER_COOLDOWN_CEILING_MS)`. On entering `Closed` from `HalfOpen`-success, the cooldown resets to `BREAKER_INITIAL_COOLDOWN_MS` for the next potential open cycle.

**Breaker open does NOT alter MAC verify semantics.** A loop running with breaker `Open` still gates cursor on verify, still observes the verify-key state machine, still uses the same `SeqMacVerifier`. Per R2-S-B5, the breaker MUST NOT become a MAC verify downgrade.

**Interaction with `RestStateMachine`.** Breaker open emits a named `RestStateMachine.Event.RestPollDegraded` event (read-only signal); `RestStateMachine` does NOT transition `RestMode` purely because of breaker state. The existing `RestMode.{WsActive, WsCandidate, RestActive}` semantics remain governed by their existing inputs.

**Breaker timer Job tracked and cancelled by `stop()`.** Same lifecycle discipline as `aliveTickJob` from Stage 2A. On `stop()` the timer Job is cancelled; the breaker state itself is reset to `Closed` on the next `start()` because Stage 2B-B does NOT persist breaker state across orchestrator lifecycles. The `SuspendedOnPoison` state has the same behaviour — reset on next `start()`, no persistence.

Cells M13 (5 transition sub-cells + M-13e for `stop()` during `Open`) + M-B18 (numeric values match the constants table verbatim) pin this.

### L10 — Jitter source migration (codifies D12)

All **eleven** existing `Random.Default.nextDouble()` sites in `RestFallbackOrchestrator` migrate to draws from the existing `Csprng` interface (`Csprng.uniformLong`) introduced in Stage 2A. The line numbers verified against master `f8cdc91a` are: **`:415, :468, :487, :591, :644, :662, :699, :761, :803, :824, :848`** (eleven sites, not nine; the earlier draft of this scope-doc miscounted). Exact line numbers shift post-implementation; the migration target is total replacement of every `Random.Default` reference in the orchestrator.

The migration is total: NO `Random.Default`, `java.util.Random`, or `SecureRandom` references remain in the orchestrator after this stage. Tests pin both layers: a grep gate on `Random.Default | java.util.Random | SecureRandom` patterns plus a behavioural pin where a recording `Csprng` fake sees every jitter draw. The expected draw count over a known orchestrator scenario is part of M15's assertion: a recorded N draws against the fake matches the deterministic count for the scenario.

Cell M15 pins this.

## Tele2 LTE smoke gate (codifies D14 + OQ-5 LOCK)

WORKING_RULES rule 8 is binding for Stage 2B-B and **NOT waivable**. Stage 2B-B is NOT shippable without a real-device smoke test on Tecno + Tele2 LTE.

**APK build requirement (load-bearing).** The smoke MUST run on a debug or beta APK built with `LONGPOLL_V2_ENABLED == "1"` so the Stage 2B-B runtime paths actually fire. A smoke that uses a release APK (pinned at `LONGPOLL_V2_ENABLED == "0"` per L6 / Stage 2B-A scope L6) does NOT exercise any Stage 2B-B code and is NOT a valid smoke. The implementation PR description MUST cite the APK SHA-256, the build variant (debug or beta), AND a logcat excerpt proving `LONGPOLL_V2_ENABLED=1` was the runtime value.

**Per-scenario log-line evidence (load-bearing).** Each scenario's pass criterion includes specific logcat lines that the implementation PR description quotes verbatim. A scenario passes ONLY when ALL of the following appear in the captured logs during the smoke window:

- The feature flag is on: `LONGPOLL_V2_ENABLED=1` at orchestrator construction.
- The verify-key state is `KeyPresent`: `seq_mac_verify_key_state=KeyPresent` at least once before envelope ingestion.
- At least one envelope passes verify: at least one `seq_mac_verified` log line (or equivalent verify-pass log shape).
- The cursor is actually persisted: at least one `cursor_advanced seq=<n>` log line with a strictly-monotonic `seq` value.
- The long-poll headers are emitted on at least one request: `X-Phantom-Long-Poll: 1` AND `X-Phantom-Padded-Poll: 1` visible in REST_TRACE.

A pass log block that omits any of the five proofs is not a pass, regardless of subjective verdict.

**Six scenarios:**

- **S1 — WS up, REST poll arrives, MAC verifies, no decrypt regression.** Five envelopes from peer; pass when every envelope reaches the chat exactly once, no `seq_mac_verify_fail`, no `decrypt_fail`, both `ws_active_poll_ok` and WS Deliver increment without storage-table duplicates, plus the five log-line proofs above.
- **S2 — WS down, REST primary, multi-envelope batch.** Four envelopes with `more=true` on the first poll; pass when all four arrive in `seq` order, the persisted cursor advances to the highest, and a cold restart polls with that `since_seq` and gets zero duplicates.
- **S3 — Token rotation mid-poll, 410 fires, re-auth + retry.** Pass when `poll_410_token_rotated` logs, a fresh `session_request` logs at re-auth, `ws_active_poll_ok` logs after retry, no envelope is duplicated or lost, cursor is preserved.
- **S4 — Server kill switch `RELAY_POLL_HOLD_SECS=0`.** Operator flips the env var on the VPS. Pass when the client receives short-poll cadence with the padded body shape, `poll_hold_secs=0` is parsed, the L8 timer gate returns the legacy timeout, and chat continues.
- **S5 — Voice notes uniform-functionality.** Voice notes from peer arrive in the same poll window as text sent at the same instant. Privacy-modes uniform-functionality lock holds: long-poll does not degrade voice.
- **S6 — Breaker open under Tele2 byte-budget death (mandatory per OQ-5 LOCK).** Sustain Tele2 LTE upload pressure to provoke Mode-2 cutoff (5-14 KB upload then silence per the byte-threshold finding). The breaker does NOT "select" a transport; it controls the REST poll cadence. Pass criteria (all required):
  1. `breaker_open reason=ConsecutiveRestFailures cooldownMs=<n>` logs within the threshold (`BREAKER_CONSECUTIVE_FAIL_THRESHOLD = 5`).
  2. REST poll enters the cooldown (`POLL_TRACE rest_breaker_open_skip` lines fire for at least `BREAKER_INITIAL_COOLDOWN_MS = 5_000` ms).
  3. Direct WSS continues delivering messages during the cooldown (at least one WS Deliver log inside the cooldown window).
  4. At cooldown expiry, the breaker transitions to `HalfOpen` and issues exactly one probe poll (`breaker_halfopen_probe` log).
  5. The probe outcome transitions the breaker correctly: probe success → `breaker_closed`; probe failure → `breaker_open cooldownMs=<doubled>` capped at `BREAKER_COOLDOWN_CEILING_MS = 120_000`.
  6. Cursor is preserved across all transitions (M-B16 applies in the field).

  Time-box ≥ 30 minutes; a controllable trigger (e.g. a debug-mode helper that simulates `BREAKER_CONSECUTIVE_FAIL_THRESHOLD` consecutive REST poll failures) is acceptable if natural Mode-2 does not reproduce in the window. If the controllable trigger is used, the PR description names it explicitly.

The smoke is run on the implementation PR before merge. The PR description records the device, carrier, APK SHA-256, build variant, time-box, the pass / fail verdict per scenario, AND the log-line evidence block for each scenario as inline quotes.

## Test matrix M8-M17 + new cells

The implementation PR ships the following cells. M-prefixed cells extend the Stage 2B-A M1-M7 numbering; M-B prefixed cells are the Round 2 additions.

| Cell | Source set | Pins |
|---|---|---|
| **M8** | commonTest, golden-vector | `seq_mac` parity against all 13 server vectors in `services/relay/tests/seq_mac_vectors.rs` |
| **M9** | commonTest, behavioural | Verify success: known key + canonical input → `Verified`; deterministic |
| **M10** | commonTest, behavioural | Verify failures, 6 sub-cells: wrong key / single-bit-flipped `identity_hex` / off-by-one `seq` / pre-quantize `sequence_ts` / single-char-changed `envelope_id` (incl. UTF-8 byte-length collision) / tampered last hex char of `seq_mac` |
| **M11** | commonTest, behavioural | Cursor advance gate, 4 sub-cells: `(verify ∧ accept) → advance`; `(verify ∧ dedup) → advance`; `(verify ∧ storage-error) → NO advance`; `(verify-fail) → NO advance + no emit` |
| **M12** | commonTest, behavioural | Cursor monotonicity: out-of-order batch `seq = [3, 5, 4, 6]` arriving through `wsActivePollLoop` and the legacy `pollLoop` concurrently (each loop receives an interleaved subset); persisted cursor is monotonically non-decreasing through the run; writer never called with a value ≤ the persisted value. `RelayMessage.Deliver` does NOT carry `seq`; cursor advancement happens exclusively through REST `PollEnvelope.seq` |
| **M13** | commonTest, behavioural with `StandardTestDispatcher + advanceTimeBy` | Breaker state transitions, 6 sub-cells: (a) closed → open on N consecutive failures; (b) open → half-open after recovery window; (c) half-open + success → closed; (d) half-open + failure → open with refreshed timer; (e) cursor preserved across all transitions; (M-13e) `stop()` during `Open` cancels timer Job, no re-fire on next `start()` |
| **M14** | commonTest, behavioural | 410 reauth, 5 sub-cells: (a) 410 → `authSession` → retry with new token → 200 → envelope emitted; (b) 410 → re-auth fails → L2 corollary fires (`KeySuspended`); (c) 410-then-410 within 30 s → 60 s ceiling backoff; (d) cursor preserved across the 410 dance; (e) 410 on `/relay/ack-deliver` uses existing self-healing fall-through, NOT re-auth dance (Stage 1.x Lock-3 — T1 ack after T2 → 401, burning a fresh token there is wrong) |
| **M15** | commonTest, behavioural + apps/android androidUnitTest, source-parse | Jitter source = `Csprng`; grep gate covers `Random.Default`, `java.util.Random`, `SecureRandom`; behavioural recording fake sees every draw |
| **M16** | apps/android androidUnitTest | Release variant BuildConfig pin `LONGPOLL_V2_ENABLED == "0"` unchanged from Stage 2B-A |
| **M17** | commonTest, behavioural | Old-relay `MissingMac` outcome class: when `_seqMacVerifyKey` is empty (`KeyAbsent`), envelopes pass through with `reason=no_verify_key`; when `_seqMacVerifyKey` is non-empty (`KeyPresent`) and `PollEnvelope.seqMac` is empty, envelopes are dropped with `reason=no_mac_field` — discriminated outcomes |
| **M-B7** | commonTest, golden-vector with INDEPENDENT Rust-generated expected MAC | At least one golden vector with a multi-byte UTF-8 `envelope_id` (emoji or CJK). The expected MAC for this vector MUST be generated by the Rust `seq_mac` implementation (a test-only entry added to `services/relay/tests/seq_mac_vectors.rs` or its JSON export, NOT the production wire surface) and byte-pinned in the Kotlin test fixture. The Kotlin verifier MUST NOT compute the expected MAC itself; that would let an `encodeToByteArray().size` vs `String.length` drift verify against itself silently. This is the same independent-oracle pattern Stage 1.x used for the existing 13 golden vectors |
| **M-B8** | commonTest, behavioural | `identity_hex` derivation round-trip: server-generated MAC verifies against client-derived `identity_hex` using `RestFallbackOrchestrator.identityHex` (the receiving identity) |
| **M-B9** | commonTest, behavioural | Hex-key-decode-before-use: assert MAC computed with `Auth.authHmacSha256(rawKeyBytes, message)` verifies; assert MAC computed with the hex `String` as ASCII bytes DOES NOT verify |
| **M-B10** | commonTest, behavioural with structured-concurrency cancellation | Cancellation-safety on verify-then-emit boundary: cancellation injected between `emit` return and the `ackInboundAndAdvanceCursor` invocation does NOT advance the cursor |
| **M-B11** | commonTest, behavioural | Verify-key state machine transitions: `KeyAbsent → KeyPresent → KeySuspended → KeyPresent` paths exercised; the corollary path (failed refresh → `KeySuspended`) is exercised explicitly |
| **M-B12** | commonTest, behavioural | MAC repeat counter increments under sustained verify-fail; no ack, no cursor advance under any count |
| **M-B13** | commonTest, behavioural | First repeat triggers forced session refresh; latched exactly once per `envelope_id`; no second refresh on threshold multiples |
| **M-B14** | commonTest, behavioural | Second repeat after refresh transitions `LongPollBreakerState` to `SuspendedOnPoison` |
| **M-B15** | commonTest, behavioural | Direct WSS path remains operational while both REST poll loops are suspended |
| **M-B16** | commonTest, behavioural with fake storage | Persisted cursor and relay queue remain byte-identical to pre-poison state across all retry counts |
| **M-B17** | commonTest, behavioural | After `SuspendedOnPoison`, the same bad envelope returned through the legacy `pollLoop` is NOT emitted, NOT acked, and does NOT advance the cursor; both loops enforce identical verify semantics under suspension |
| **M-B18** | commonTest, behavioural with `StandardTestDispatcher + advanceTimeBy` | Breaker numeric contract: each constant from the L9 table is read at runtime from the orchestrator's companion and asserted to match the locked value (catches a future tuning drift); `K=3` 410 responses within `W=30_000` ms transitions to `Open(Status410Storm, cooldownMs=60_000)`; failed half-open probe doubles cooldown with `BREAKER_COOLDOWN_GROWTH_FACTOR = 2.0` capped at `BREAKER_COOLDOWN_CEILING_MS = 120_000`; closed-from-half-open-success resets cooldown to `BREAKER_INITIAL_COOLDOWN_MS = 5_000` |
| **M-B19** | commonTest, behavioural fail-closed | Malformed verify-key and `seq_mac` inputs must fail closed without exceptions and without loop death. Sub-cells: (a) `seqMacVerifyKey` is non-hex string → state machine enters `KeySuspended`, no envelope ingestion; (b) `seqMacVerifyKey` has odd char length → `KeySuspended`; (c) `seqMacVerifyKey` is not 64 chars (32, 63, 65, 128 sampled) → `KeySuspended`; (d) `PollEnvelope.seqMac` is non-hex → drop with `reason=no_mac_field`; (e) `PollEnvelope.seqMac` has odd char length → drop with `reason=no_mac_field`; (f) `PollEnvelope.seqMac` is not 64 chars → drop with `reason=no_mac_field`. None of these cases throw; the loop continues; the orchestrator keeps polling |
| **M-B20** | commonTest, behavioural | Ack network failure path: `ackInboundAndAdvanceCursor` returns `AckOutcome.Failed`; the cursor does NOT advance; the `_pendingSeqForAck` entry remains; the next poll's `ReAck` branch retries via another `ackInboundAndAdvanceCursor` call; on retry success the cursor advances exactly once |
| **M-B21** | commonTest, behavioural with debug flag on | Debug-mode `FAILED_MAC` hold path: when `holdMacFailures == true` and the envelope produces `MacError`, `ackInboundAndAdvanceCursor` is NOT invoked, the relay queue is NOT cleared, the cursor stays frozen; the X3DH-repair-path's eventual `sendDeliveryAck` invokes `ackInboundAndAdvanceCursor` and advances the cursor at THAT point |
| **M-B22** | commonTest, concurrency | Same `envelope_id` observed by `wsActivePollLoop` and the legacy `pollLoop` concurrently: `_macFailCount` increments exactly once per verify failure, `_macRefreshAttemptedFor` admits exactly one refresh, `_pendingSeqForAck` carries exactly one entry, the cursor advances exactly once on ack success |
| **M-B23** | commonTest, concurrency | Concurrent breaker-open signals from both poll loops converge on `LongPollBreakerState.SuspendedOnPoison` with no inconsistent intermediate state visible to either loop; the `_inboundStateMutex` discipline holds; assertion that `tokenMutex` and `_inboundStateMutex` are NEVER acquired in reverse order across the code path |
| **M-B24** | commonTest, behavioural | HTTP 429 `Retry-After` hard cap: the orchestrator honours `Retry-After: N` from the relay but clamps `N` to `RETRY_AFTER_HARD_CAP_SECONDS = 120` seconds BEFORE multiplying by `1000L`. A malicious or misconfigured relay sending `Retry-After: 86400` cannot lock the client out for a day, and no input value can overflow `Long` arithmetic because the clamp executes before the multiplication. Test sub-cells: (a) typed `retryAfterSeconds: Long? = null` populated from the OkHttp `Response.header("Retry-After")` parse path; (b) malformed `Retry-After` (non-numeric, negative, zero, HTTP-date form, empty) → treated as null + log; (c) overflow-safe clamp — `Retry-After: 86400` → effective delay 120_000 ms; `Retry-After: Long.MAX_VALUE` → effective delay 120_000 ms (no overflow); (d) the clamp applies in both REST poll loops and in the send-path retry that consumes the header |
| **M-B25** | commonTest, concurrency | Refresh-vs-poll race: a token refresh that flips the verify-key state from `KeyPresent(hexA)` to `KeyPresent(hexB)` while a poll-loop verify is in flight against an envelope MUST NOT produce a verify call that uses half `hexA` and half `hexB`. The poll-loop snapshots the verify-key inside `_inboundStateMutex.withLock` BEFORE releasing the mutex and running the verify; the refresh path takes `tokenMutex` outer + `_inboundStateMutex` inner to publish the new state. Test exercises 100 concurrent poll-verify iterations interleaved with 100 refresh-induced state flips; assertion: every verify uses a single consistent key value end-to-end |
| **M-B26** | commonTest, behavioural with sparse seq | Crash recovery via sparse-sequence skip: after a process kill between `ackInbound` success and the cursor write, the relay has already removed the envelope from its queue and will NOT redeliver it. The next poll arrives with a higher `seq` (the relay's next envelope for this identity), which the cursor advance path applies through the normal Step 4 / Step 3 flow. The persisted cursor "skips" the lost seq value; the gap is implicit because no envelope holds it. Test seeds `since_seq = 5`, simulates a crash mid-ack on `seq = 6`, brings up a fresh orchestrator, polls and receives `seq = 7`; persisted cursor ends at `7` with no anomaly logged. This is the actual crash-recovery mechanism — the relay's `ackInbound` is what triggers queue removal, not the cursor advance |
| **M-B27** | commonTest, behavioural | Cursor-write bounded retry on ack-success, three sub-cells: (a) repository throws once then succeeds → entry removed; cursor at expected seq; (b) exhaustion: all three attempts throw → `event=poll_cursor_write_exhausted` log fires; entry REMOVED from `_pendingSeqForAck` (no memory leak); subsequent observation does NOT reattempt the upsert for this id; sparse-sequence catch-up via M-B26 covers recovery; (c) cancellation safety: cancel the caller's coroutine after `ackSucceeded = true` but before the first storage write completes → the `finally` + `withContext(NonCancellable)` block still removes the entry from `_pendingSeqForAck` under `_inboundStateMutex`; no entry leaks past cancellation; the `CancellationException` re-throws to the caller after cleanup |
| **M-B28** | commonTest, concurrency | Half-open probe singleton with cancellation safety, three sub-cells: (a) both REST poll loops wake simultaneously past the cooldown timer with the breaker in `HalfOpen(probeInFlight = false)`; under `_inboundStateMutex` exactly ONE loop claims the permit and calls `transport.poll(...)` exactly ONCE; the other loop observes `HalfOpen(probeInFlight = true)`, short-circuits, and sleeps until the next iteration; the probe outcome resolves the state correctly for both loops; (b) claimant cancellation: the claimant loop's probe call is cancelled mid-flight (mode transition or scope cancellation); the `finally` + `withContext(NonCancellable)` block releases the permit (`HalfOpen(probeInFlight = true) → HalfOpen(probeInFlight = false)`); (c) recovery after claimant cancellation: on the next iteration the second loop observes `HalfOpen(probeInFlight = false)`, claims the permit, calls `transport.poll(...)` once, and resolves the state — proving the cancellation path does not strand the breaker permanently |
| **M-B29** | commonTest, concurrency | Cancellation between `ackInbound` returning `Acked` and the cursor write does NOT leak `_pendingSeqForAck`. Test injects cancellation at four precise points inside `ackInboundAndAdvanceCursor`: (a) at the Phase 1 mutex acquire (the first suspension point after `Acked`) → `pendingSeq` stays `null`; `finally` runs unconditionally and removes the entry via `_pendingSeqForAck.remove(envelopeId)` (idempotent); no `poll_cursor_write_exhausted` log fires; `CancellationException` re-throws to the caller; (b) during the bounded retry loop's third (last) `upsertLastSeenSeq` attempt → `CancellationException` propagates (not swallowed by `runCatching` because we use explicit `catch (ce: CancellationException) { throw ce }`); outer catch sets `wasCancelled = true`; `finally` removes entry; no exhaustion log fires; (c) during the `delay(...)` between retry attempts → `delay` throws `CancellationException` naturally; outer catch tags `wasCancelled = true`; `finally` removes entry; no exhaustion log; (d) after `Acked` returns but before the `try { ... }` block is entered — verified by construction that no suspension point exists in this gap, so cancellation cannot arrive here; the test confirms this by counting suspension points in the disassembled bytecode |

## PR-commit boundary

The implementation PR is structured as six commits, each independently green and individually reviewable. The bundle is one PR because the semantic layer is tightly coupled — splitting would create intermediate states where verify is partial or cursor advancement is half-wired.

- **C1 — `SeqMacVerifier` + commonMain BE helpers + constant-time compare.** Implements L1 + L5. Ships M8, M9, M-B7, M-B9.
- **C2 — Verify-key state machine + forced-refresh-failure corollary.** Implements L2 + the L2 corollary path. Ships M-B11.
- **C3 — `LongPollCursorRepository` seam + single combined `ackInboundAndAdvanceCursor(envelopeId)` orchestrator method wired into all four call sites in `HybridRelayTransport` per L3 Steps 3-4 + three-phase mutex pattern with bounded local retry + explicit `CancellationException` rethrow (no `runCatching` on the upsert) + `finally` cleanup + legacy `pollLoop` in-memory cursor decommission + `_inboundStateMutex` introduced.** Implements L3 + L4 + the L6 concurrency-safe shared-state plumbing. Ships M11, M12, M-B20, M-B26, M-B27, M-B29. `AppContainer` bridge upgraded to implement both read and write methods. M-B16 (poison-state cursor invariant) and M-B21 (debug-mode `FAILED_MAC` hold path) ship in C4 because they exercise behaviour introduced in C4.
- **C4 — Both REST poll loops symmetric verify + bad-MAC posture + concurrent-state discipline + verify-key transition matrix.** Implements L6 (symmetric verify, incl. verify-key state under mutex) + L7 (poison posture) + the L2 exhaustive `when` transition matrix at the existing `acquireOrRefreshToken` publication sites (success + failure branches). Wires the MAC verify into both `wsActivePollLoop` AND the legacy `pollLoop`; introduces `_macFailCount`, `_macRefreshAttemptedFor`, `LongPollBreakerState.SuspendedOnPoison`. Ships M10, M17, M-B8, M-B10, M-B12, M-B13, M-B14, M-B15, M-B16, M-B17, M-B21, M-B22, M-B23, M-B25.
- **C5 — 410 reauth backoff + circuit breaker + hard timer caps + typed `Retry-After` plumbing + half-open probe permit.** Implements L8 + L9 + D11. Adds `retryAfterSeconds: Long? = null` to `RestFallbackResponse`; parses `Retry-After` in the Android OkHttp transport; updates transport fakes. Implements the `HalfOpen(probeInFlight)` permit with cancellation-safe `finally` release per L9. Ships M13, M14, M-B18, M-B24, M-B28.
- **C6 — Jitter source migration + fail-closed input handling + Tele2 LTE smoke runbook.** Implements L10 (eleven `Random.Default.nextDouble()` sites migrated). Ships M15, M-B19. Adds a `docs/tracks/trek2-stage2b-b-tele2-smoke.md` runbook describing the device, carrier, APK build variant (`LONGPOLL_V2_ENABLED=1` debug/beta APK mandatory), per-scenario log-line evidence requirements, and pass criteria for the mandatory Tele2 smoke. M16 release-pin contract test runs against the C6 head.

The Tele2 LTE smoke run is performed against the C6 head (or a later C6+ release if review-fix commits land). The PR description records the smoke verdict per scenario before the PR exits draft.

## WORKING_RULES rule 8

Stage 2B-B is NOT shippable without the Tele2 LTE smoke. Rule 8 is binding and **NOT waivable** on this PR. S1-S5 are mandatory; S6 was promoted from optional to mandatory under OQ-5 LOCK with a ≥ 30 minute time-box on Tecno + Tele2 LTE. A controllable trigger is acceptable for S6 if natural Mode-2 does not reproduce in the window.

## Backward compatibility

- **Server-side.** Zero impact. The server contract (Stage 1.x + 2B-A surface) is unchanged. A Stage 2B-A client on the same master continues to operate identically; the only difference for Stage 2B-B is that it now verifies the `seq_mac` the server has been sending all along.
- **Old relays (pre-Stage-1.x).** Old relays do not announce `seq_mac_verify_key`; the client enters `KeyAbsent` state and runs the legacy unverified pass-through with `reason=no_verify_key`. No behaviour change for old relays.
- **Stage 2B-A shell flag.** `LONGPOLL_V2_ENABLED` BuildConfig pin remains `"0"` in release. Stage 2B-B does NOT flip the pin — that is the separate Stage 2B-D promotion PR. Release-mode behaviour is byte-identical to Stage 2B-A.
- **Legacy `pollLoop` cursor.** The in-memory `lastSeenSeq` variable inside `pollLoop` is decommissioned (OQ-6 LOCK + D10). Both loops now share the persisted cursor. This is a structural change, but the wire behaviour is preserved: `since_seq` values sent to the server are the same monotonic sequence.
- **`ackInbound` path.** Wire behaviour unchanged: the orchestrator still POSTs `/relay/ack-deliver` with the same body shape and the same outcome classification (`Acked` / `Failed` / `DisabledByCapability`). The new combined entry point `ackInboundAndAdvanceCursor(envelopeId)` wraps `ackInbound` + the cursor finalization into one cancellation-safe method; the four `HybridRelayTransport` call sites that previously called `orchestrator.ackInbound(...)` directly now call the combined method instead. The standalone `ackInbound(...)` function remains in the orchestrator for any non-cursor-bound caller (test helpers, future debug paths) but is no longer used from the production REST inbound pipeline. The relay sees no behavioural change.

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

After Stage 2B-B merges, the long-poll backbone is **semantically wired but NOT yet active in production**: the release-mode APK still pins `LONGPOLL_V2_ENABLED == "0"` (Stage 2B-A scope L6). The backbone is therefore complete *as code* but not yet validated *in production*. The sequence after 2B-B is:

1. **Stage 2B-C — documentation cleanup.** Release notes, ADR rollup, public README updates. No code change.
2. **Stage 2B-D — release flag promotion + rollout gate.** A separate, deliberately ceremonial PR that flips `LONGPOLL_V2_ENABLED` to `"1"` in the release variant. Promotion is conditional on (i) a beta cohort running with the debug-mode flag for an agreed observation window, (ii) zero new `seq_mac_verify_fail` field reports beyond expected baseline, (iii) zero new cursor-monotonicity field reports, (iv) the Tele2 LTE smoke S1-S6 reproduced cleanly on a release build. The rollout gate's pass criteria are locked at the time the promotion PR opens.
3. **Direct WSS hardening track opens.** The backbone is "active" only after Stage 2B-D promotion is green in production. Direct WSS hardening is the next track per the locked strategic order, but it starts only after Stage 2B-D, not after Stage 2B-C.

This sequencing is explicit so the strategic frame's "backbone first" framing does not silently degrade into "backbone shipped but never validated; we have moved on to Direct WSS." The backbone is the un-killable-messenger guarantee at the product level; validating it under production load is the load-bearing step that justifies that guarantee.
