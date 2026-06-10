# Trek 2 Stage 1.x — Server-Side Prerequisite (Scope Mini-Lock DRAFT)

**Status:** Locked scope mini-lock for Stage 1.x. No code written. This document captures the synthesis of the multi-stage preflight conducted 2026-06-10.

**Why this PR exists:** Trek 2 Stage 2B client preflight 2026-06-10 surfaced 4 server-side BLOCKERS that prevent the client-side long-poll runtime from being safe to ship. Stage 2B client implementation is PAUSED until this PR merges to master.

## Locked threat-model wording (verbatim, do NOT soften)

The following wording is locked verbatim and MUST appear unchanged in:

- This document.
- The Stage 1.x PR description.
- The `///` doc-comment on the `PollEnvelope.seq_mac` field in `services/relay/src/rest_fallback.rs`.

> This protects against poll-layer / DB tamper / bugs (envelopes mishandled by relay code, by-token cache corruption, accidental seq replay from misuse of the dedup buckets), **not** a fully malicious relay operator unless signing key is outside the relay process. Do not pretend otherwise.

**What `seq_mac` defends against (concrete):**

- DB-level row corruption (SQLite WAL page swap producing mismatched `envelope_id` / `seq` pairs for same identity).
- by-token cache race returning wrong identity's envelope batch.
- Accidental `seq` replay from dedup-bucket misuse producing duplicate `seq` values with inconsistent MACs.
- Middleware or proxy layer silently reordering or truncating the envelope list before client receipt.

**Precision on the DB-tamper scope.** `seq_mac` detects non-MAC-aware store corruption / row mismatch — i.e. a layer that mutates `envelope_id`, `seq`, or `sequence_ts` without also recomputing the stored `seq_mac` column over the new field values. It does NOT protect against an actor who can rewrite both the envelope fields AND the `seq_mac` column consistently using the relay-side root key. Layers that can mount the consistent-rewrite attack are equivalent to a fully malicious relay operator and are out of scope per the locked threat wording.

**What `seq_mac` does NOT defend against:**

- Malicious or fully compromised relay operator (holds the key, can forge valid MACs).
- Stolen long-term identity key (attacker authenticates as the identity, receives the key, forges their own envelopes).
- Relay-side key exfiltration via `/proc/self/mem`, core dump, or backup read.

## Scope — 4 locks

### Lock-1 — `seq_mac` additive HMAC, computed at STORE TIME

Server computes HMAC-SHA-256 over `(identity_hex, seq, envelope_id, sequence_ts)` at the moment the envelope is persisted to the REST store (inside `mirror_envelope_to_rest_store` at `services/relay/src/rest_fallback.rs:519-559`). MAC is persisted as a new column `seq_mac TEXT NOT NULL` on `RestEnvelope`. `drain_eligible` (`rest_fallback.rs:917-943`) reads the stored column verbatim — it does NOT recompute the MAC.

Wire-shape addition: `PollEnvelope` gains one new field `seq_mac: String` (always non-empty when opt-in path returns an envelope). Old clients ignore unknown JSON fields (additive contract).

**Canonical HMAC input (variable length, length-prefixed envelope_id):**

```
b"phantom-seq-mac-v1\x00"         19 bytes   UTF-8 + null terminator domain tag
identity_hex                       64 bytes   lowercase ASCII (fixed by construction)
seq (big-endian u64)                8 bytes
envelope_id_len (big-endian u16)    2 bytes   byte length of UTF-8 encoded envelope_id
envelope_id_bytes                  variable   exact UTF-8 bytes of envelope_id
sequence_ts (big-endian u64)        8 bytes   POST-quantize value (60_000ms floor)
```

Total length = 101 bytes + `envelope_id_len` bytes. Output: 32 bytes → lowercase hex 64 chars on wire.

**Why `envelope_id` is length-prefixed UTF-8, not fixed-width.** I verified against master `f9c81937` that the server-side `RestSendRequest.envelope_id` is typed as `String` (UTF-8, not enforced to a UUID shape), and Stage 2A `EnvelopeId.random()` generates a 32-char lowercase hex (NOT a 36-byte UUID canonical hyphenated form). The MAC encoding cannot assume a fixed 36-byte UUID and cannot assume ASCII either — Rust `String` is UTF-8 and can contain multi-byte characters. The canonical input therefore encodes the **exact UTF-8 byte sequence** of `envelope_id`, prefixed with its byte length (NOT character length).

**Server-side validation requirement.** The relay's REST send handler MUST reject any `RestSendRequest.envelope_id` whose UTF-8 byte length exceeds `65535` (the `u16-BE` length-prefix capacity). Production sizes are ~32 bytes; the 65535 ceiling is a defensive cap. If future requirements need to constrain `envelope_id` to ASCII or hex specifically, add that validation explicitly — do NOT silently assume it in the MAC encoding.

**Domain tag rationale.** The `\x00` null terminator cannot appear in the subsequent ASCII `identity_hex` field, making domain separation unambiguous at the boundary. The `v1` version string enables future MAC scheme rotation (`v2\x00`) without changing the field layout.

**Why store-time computation, not response-time.** Response-time HMAC computed inside `drain_eligible` would be computed over whatever values the DB returns — including already-corrupted values. A client receiving a response-time MAC over corrupted data would verify successfully and consume the corrupted envelope. This collapses the locked "DB tamper" threat scope to "tamper between drain and wire" — a far narrower threat than the wording promises. Store-time computation creates a persistent integrity anchor: the MAC reflects the envelope state at the moment of write; any subsequent corruption causes a mismatch when re-read.

**HMAC root key sourcing.** Environment variable `RELAY_SEQ_MAC_KEY` (64-char hex = 32 bytes), parsed at startup into a **fixed-size, type-level-bounded newtype** rather than a heap-allocated `Vec<u8>`:

```rust
struct SeqMacRootKey(Zeroizing<[u8; 32]>);
```

This shape is preferred over `Vec<u8>` because (a) the size is part of the type and cannot drift, (b) the inner `[u8; 32]` lives on the stack/struct embedding rather than the heap, reducing the surface where the key bytes could be cloned by accident, and (c) `Zeroizing<[u8; 32]>` from the `zeroize` crate guarantees the bytes are wiped on drop without requiring a manual `ZeroizeOnDrop` impl. The wrapper newtype provides a single chokepoint for the `[REDACTED]` `Debug` impl, mirroring the existing `secret_token` pattern at `config.rs:221`.

`secrecy::SecretBox<[u8; 32]>` is an acceptable alternative but introduces a new dependency. After review, the selected design is `Zeroizing<[u8; 32]>` + a newtype for Stage 1.x; promote to `secrecy::SecretBox` only if a future review round finds a concrete misuse path.

**The root key NEVER leaves the relay process.** It is used exclusively to derive per-identity verify keys (see "Client key distribution" below). Server-side MAC computation uses the derived per-identity key, NOT the root key directly.

**Fail-to-start.** If `RELAY_SEQ_MAC_KEY` is absent at startup, the relay process exits with a startup error. NOT warn-and-disable — silently disabling MAC ships a relay that violates its own security contract.

**Test fixture key.** `[0u8; 32]` hardcoded in `RelayConfig::from_env_for_test()`. Deterministic, never used in production paths.

**Log redaction.** The following three classes of secret/sensitive bytes MUST NOT appear in any `tracing` event at any level:

1. **`RELAY_SEQ_MAC_KEY`** (the root key) — secret, redacted in `Debug` via the newtype impl.
2. **Derived `seq_mac_verify_key`** values — although delivered to the client in `SessionResponse`, log emission is forbidden to prevent correlation between relay-log identity records and out-of-band verify-key compromise reports.
3. **`seq_mac`** values — observable on the wire to anyone who controls a TLS-terminating proxy, but log-to-wire correlation still enables traffic analysis even without bulk capture.

**Crate dependencies.** `hmac = "0.12"` added to `services/Cargo.toml` workspace deps. Pairs with existing `sha2 = "0.10"`. `zeroize` crate added for key-byte zeroization.

**Key rotation.** Stage 1.x ships a single-key design. Multi-key (versioned MAC with `v2\x00` domain tag increment) is deferred to Stage 2+.

**Client key distribution — per-identity derived verify key.**

The relay does NOT publish the root key. Instead, the relay computes a per-identity verify key using HMAC-SHA-256:

```
seq_mac_verify_key = HMAC-SHA-256(
    key = root_key,
    msg = b"phantom-seq-mac-key-v1\x00" || identity_hex
)
```

- Domain separation tag for KEY DERIVATION: `b"phantom-seq-mac-key-v1\x00"` (22 bytes). Distinct from the MAC-computation domain tag (`b"phantom-seq-mac-v1\x00"`) to prevent cross-domain reuse.
- Output: 32 bytes → 64-char lowercase hex, returned in `SessionResponse.seq_mac_verify_key` as an additive field.
- The same derived key is used by BOTH the server (to compute `seq_mac` for envelopes addressed to that identity) AND the client (to verify those MACs).

**Why per-identity derivation, not root-key publication.**

- A leaked verify key compromises ONLY that one identity's MAC verification — never any other identity's MACs. Blast radius is bounded by single-identity scope.
- The root key never leaves the relay process. It is in memory only, redacted in `Debug`, zeroized on drop.
- Server-side MAC computation: `seq_mac = HMAC-SHA-256(seq_mac_verify_key, canonical_input)`. The root key is touched ONLY in the key-derivation step.
- A client cannot forge MACs for envelopes addressed to another identity, even with a leaked verify key — they don't know that other identity's derived key, and cannot derive it (no access to root).

Documented as integrity signal for client-side anomaly detection, NOT as an access-control boundary. Matches the locked threat wording exactly: a fully malicious relay operator with the root key can still mint any verify key and forge any MAC, but the protection scope is precisely as the locked wording states.

### Lock-2 — padded-vs-held server-side decoupling, two-header design

Add a new request header constant in `rest_fallback.rs`:

```
pub const PADDED_POLL_OPT_IN_HEADER: &str = "x-phantom-padded-poll";
```

The existing `X-Phantom-Long-Poll: 1` header continues to gate the hold path. The new `X-Phantom-Padded-Poll: 1` header gates the padded-body response shape. The server derives:

```
let padded_opt_in = long_poll_opt_in || padded_poll_opt_in;
```

The `padded_opt_in` boolean replaces `long_poll_opt_in` at the padding gate (`rest_fallback.rs:1581`). The hold gate stays unchanged on `long_poll_opt_in` at `rest_fallback.rs:1548`.

**4-cell wire contract:**

| `X-Phantom-Long-Poll: 1` | `X-Phantom-Padded-Poll: 1` | Hold | Response shape |
|:---:|:---:|:---:|:---:|
| absent | absent | short-poll | legacy small body |
| present | absent | hold up to `poll_hold_secs` | padded 4608 bytes |
| absent | present | short-poll | padded 4608 bytes |
| present | present | hold up to `poll_hold_secs` | padded 4608 bytes |

**Stage 2B-A client semantics:**

- Normal long-poll: send BOTH headers.
- Circuit-breaker open (Stage 2B client falls back to short-poll): drop `X-Phantom-Long-Poll`, retain `X-Phantom-Padded-Poll`. This preserves padding posture during the breaker's transient short-poll period — closes the guardrail C ("padding never reduced silently") gap identified during review.

**Header strict equality:** `v == "1"`, matching the existing `LONG_POLL_OPT_IN_HEADER` pattern. Any other value treated as absent.

**Server statelessness.** No per-identity persistent flag. Each request independently evaluated. Restart-safe.

### Lock-3 — immediate prior-token invalidation on `TokenStore::issue()`

**Stage 1.x Lock-3 invariant (the contract):**

> After 3 concurrent `issue()` calls for the same identity with distinct challenges, **exactly one returned token validates** via `state.rest_tokens.validate(token).await` after `join_all` returns. The other two return `None`.

**Implementation strategy is invariant-driven, not pre-locked.** Initial analysis suggests the existing `RestTokenStore::issue()` at `rest_fallback.rs:175-194` already delivers this invariant via the write-lock removal of the prior token from `by_token` before inserting the new token. The TOCTOU window between releasing the `by_token` write lock and acquiring the `by_identity` write lock appears benign in consequence — a concurrent `validate()` observes at most a transient `None`, never a successful validate against the prior token.

**If the concurrent-issue contract test passes against the existing code,** Stage 1.x Lock-3 deliverable reduces to:

1. Doc-comment on `issue()` explicitly stating the invalidation invariant verbatim.
2. The concurrent-issue contract test as the invariant-pinning gate (see Tests section below).
3. **In-flight poll behavior locked as b2 — ack contract corrected during review:**

   - An in-flight long-poll request on T1 (when T2 is issued mid-stream) **completes naturally on T1**. The poll's bearer was validated at request start; the long-held connection state does not re-validate, so the envelope is returned to the client over the T1 connection.
   - The returned envelope is **NOT ackable via T1**. Ack is a separate HTTP request that re-validates the bearer against the current `TokenStore` at request start, and T1 has been removed from `by_token` upon T2's issuance.
   - **Ack must use T2.** The legitimate client (who possesses T2 from the reauth flow) is the only party that can complete the ack. A stolen-T1 attacker holding only T1 reads the envelope but cannot drain it from the queue — closing the inverse-DoS window structurally.
   - Subsequent poll requests on T1 (after the in-flight one completes) also return 401, because they re-validate against the current `TokenStore`.

**If the concurrent-issue contract test FAILS against the existing code, structural refactor enters scope.** Most likely refactor: collapse `issue()` into a single critical section that acquires `by_identity` write-lock first, then `by_token` write-lock (consistent lock-acquisition order across all `RestTokenStore` mutations), removing the split-lock TOCTOU pattern. The test, not a pre-locked design decision, drives whether refactor is required.

**Inverse-DoS mitigation (verified against master `f9c81937`).** Review flagged an attack window: attacker with stolen T1 triggers T2 issuance (via legitimate client's reauth path), then races to ack the in-flight envelope on T1, draining it from the queue before the legitimate T2-holder polls.

This window is **structurally closed by the existing `rest_ack_deliver` behavior:** I verified against master that `rest_ack_deliver` validates the bearer via `state.rest_tokens.validate(token).await` at request start (not at connection open / not cached). Under b2, when T2 is issued, T1 is immediately removed from `by_token`, so any subsequent ack on T1 returns 401. No ack-handler modification is required.

**Stage 1.x MUST include an `inverse_dos_ack_blocked_after_token_rotation` regression test** to lock this contract against future drift (e.g. a future caching optimization in `rest_ack_deliver` that breaks the current behavior).

**Multi-device dependency.** Lock-3 bakes in `one token per identity at any wall-clock instant`. The existing `by_identity: HashMap<String, String>` (identity → one token) already enforces this. When Phantom moves to multi-device, `by_identity` becomes `identity → Vec<(device_id, token)>` and `issue()` takes a `device_id` parameter to invalidate only that device's prior token, not all devices' tokens. Document this dependency in the PR description as an explicit future-compat note.

### Lock-4 — per-identity hold cap (3 concurrent) + bounded hold secs (480s)

**Counter placement: merged with notifier.** Wrap the existing `tokio::sync::Notify` in a `HoldSlot` struct:

```
struct HoldSlot {
    notify: Notify,
    hold_count: AtomicU8,
}
```

The `notifiers` map value type changes from `Arc<Notify>` to `Arc<HoldSlot>`. The existing `Arc::strong_count == 1` cleanup path (`state.rs` notifier eviction) automatically handles `hold_count` reclamation — no separate counter map, no counter/notifier divergence.

**RAII guard.** Stack-frame guard owned by the hold handler:

```
struct HoldGuard {
    slot: Arc<HoldSlot>,
    state: Arc<AppState>,
    identity: String,
}

impl Drop for HoldGuard {
    fn drop(&mut self) {
        self.slot.hold_count.fetch_sub(1, Ordering::AcqRel);
        // if Arc::strong_count(&self.slot) == 1 after decrement:
        //   under write-lock on notifier map: remove entry
    }
}
```

**Drop semantics — precise contract (review amendment).**

The earlier draft over-claimed "Drop fires synchronously under `JoinHandle::abort()`". The correct contract is more nuanced:

- **`Drop` fires when the owning future is actually dropped.** This is a Rust language guarantee, NOT a tokio policy.
- **`JoinHandle::abort()` requests cancellation; the task's future is dropped when tokio processes that cancellation.** The drop is not instantaneous from the caller's perspective. Tests that abort a task and then assert on `hold_count` MUST `await` the `JoinHandle` (or otherwise observe task completion via a watch channel / atomic flag) before reading the counter. A naive "abort then immediately read" pattern races with tokio's cancellation processing.
- **Hold loop completion (timeout or wake), panic, and server shutdown** all drop the future via normal mechanisms; the guard's `Drop` runs synchronously inside those paths.

**Client TCP close / RST behaviour.**

- A client TCP close (FIN) or reset (RST) may not be observed by the server **immediately**. The server learns of the disconnect when the next socket write fails or when keep-alive / read timeout fires. Until the handler future is dropped, the hold slot remains occupied.
- Stale hold slots are **bounded** by the runtime hold timeout (`tokio::time::timeout(hold_secs, slot.notify.notified())` — see "Bounded hold secs" below). At most `hold_secs` of staleness; with the 480-second cap, this is the worst case.
- RAII guarantees cleanup once the handler future is dropped, but NOT at the exact network-close instant. Operators reading the hold-count metric should not expect sub-second precision against client-side TCP close events.

**Lock-acquisition order (deadlock prevention).** Always acquire the notifier map write-lock AFTER all `AtomicU8` operations on `hold_count` complete. NEVER hold the notifier map lock while awaiting.

**429 response.** HTTP 429 Too Many Requests. Body: `{"error":"too_many_concurrent_holds"}`. Header: `Retry-After: 30` (fixed value; conservative for the Tele2 LTE TCP-RST observability window).

**Bounded hold secs — dual-layer enforcement:**

- **Config-parse-time clamp.** `RELAY_POLL_HOLD_SECS` parsed in `RelayConfig::from_env()` and clamped: `let poll_hold_secs = parsed.min(480)`. The `SessionResponse.poll_hold_secs` returned to clients is therefore always ≤ 480.
- **Runtime per-hold cap.** `tokio::time::timeout(hold_secs, slot.notify.notified())` inside the hold loop. Enforcement point — if the config clamp is bypassed by a future code path, the runtime timeout still prevents the hold from exceeding 480 seconds.

Both layers are required. Config clamp prevents operator misconfiguration; runtime timeout is the hard correctness boundary.

**Per-identity cap = 3 concurrent holds.** Atomic CAS check before increment: if `hold_count.load(Acquire) >= 3` → return 429 immediately. Else `hold_count.fetch_add(1, AcqRel)` and proceed.

## What this PR does NOT do

- Does NOT begin Stage 2B client implementation. Stage 2B client is PAUSED behind this PR.
- Does NOT add `seq_mac` signing-key isolation (out-of-process oracle / client-derived secret). Stage 2+ if needed.
- Does NOT add full malicious-relay-operator resistance. The locked threat wording above is precisely calibrated — do not over-claim.
- Does NOT add multi-device support. Single-device-per-identity assumption baked in.
- Does NOT touch Stage 2A client foundations.

## Backward compatibility

With `RELAY_POLL_HOLD_SECS=0` (production default kill switch):

- **Kill-switch semantic clarification.** `RELAY_POLL_HOLD_SECS=0` disables the **hold** path only — it does NOT disable padding. The Stage 1.x contract decouples the two:
  - Old no-header clients still get the legacy small body (Stage 1 baseline preserved).
  - Opt-in clients sending `X-Phantom-Padded-Poll: 1` still receive the padded 4608-byte body even when hold is disabled — this is required so Stage 2B-A's client-side circuit-breaker fallback (short-poll + padded) still works during the staging window where the operator is testing the hold path.
- **Lock-1.** `seq_mac` field added to `PollEnvelope`. Old clients ignore unknown JSON fields (additive contract). Server still computes MAC at store time — value is essentially unused by old clients. Storage cost ~64 chars per envelope row is acceptable.
- **Lock-2.** New `X-Phantom-Padded-Poll` header. Old no-header clients never send it → legacy small body. `X-Phantom-Padded-Poll: 1` clients get padded 4608 body regardless of `RELAY_POLL_HOLD_SECS` value.
- **Lock-3.** `issue()` doc-comment only (deliverable depends on the invariant contract test). Behaviour byte-identical. Zero impact.
- **Lock-4.** `max_poll_hold_secs` defaults to 480 but never reached when `poll_hold_secs=0`. Per-identity counter never incremented (guard at `effective_hold_secs > 0`). Zero impact.

All four locks are safe to deploy with `RELAY_POLL_HOLD_SECS=0`. The hold kill switch remains effective; padding for opt-in clients is intentionally independent of the hold kill switch (this is what makes Stage 2B-A's circuit-breaker fallback semantically meaningful).

**Deployment ordering.** Operator must provision `RELAY_SEQ_MAC_KEY` in the VPS `.env` file BEFORE redeploying the relay with Stage 1.x. The relay will fail-to-start if the key is absent. Document the key generation command in the deployment runbook:

```
openssl rand -hex 32
```

## Tests

**Test file layout:**

```
services/relay/tests/
├── poll_hold.rs              # extends with Lock-1 + Lock-2 contract tests
├── token_invalidation.rs     # NEW — Lock-3 contract + concurrent-issue race
├── hold_cap.rs               # NEW — Lock-4 cap + bounded hold + 429 + RAII Drop
├── seq_mac_vectors.rs        # NEW — golden HMAC vectors + JSON export
```

**Lock-1 contract tests (in `seq_mac_vectors.rs` + `poll_hold.rs`):**

Five golden vectors covering boundary conditions:

1. **All-zero:** `identity = "00" * 32`, `seq = 0`, `envelope_id = "00000000-0000-0000-0000-000000000000"`, `sequence_ts = 0`.
2. **All-ones:** `identity = "ff" * 32`, `seq = u64::MAX`, `envelope_id = "ffffffff-ffff-ffff-ffff-ffffffffffff"`, `sequence_ts = u64::MAX - (u64::MAX % 60_000)`.
3. **Realistic mid:** representative production values.
4. **Quantization-boundary canary:** `sequence_ts = 1_700_000_000_001` pre-quantize, `1_700_000_000_000` post-quantize. Assert pre-quantize input produces a DIFFERENT MAC than post-quantize. Pins the contract that MAC is computed over the stored (post-quantize) value.
5. **Realistic mid with a different `envelope_id`** — sanity vector for routine drift detection.

All vectors use the test fixture ROOT key `[0u8; 32]`. The expected per-identity derived verify key for each vector is also byte-pinned (so that key-derivation determinism is independently verifiable). Expected MAC values are byte-pinned in the test file constants AND mirrored in this document as an appendix (to follow).

**Additional vector: key-derivation determinism.** A standalone test asserts that for fixed root key `[0u8; 32]` and fixed `identity_hex`, the derived `seq_mac_verify_key` matches a byte-pinned expected value:

```
seq_mac_verify_key = HMAC-SHA-256(
    [0u8; 32],
    b"phantom-seq-mac-key-v1\x00" || identity_hex
)
```

This pins the key-derivation domain tag and the HMAC algorithm against future drift.

**JSON export for cross-tier:** `cargo test --features test-vectors-export` emits `services/relay/test-fixtures/seq_mac_vectors.json` for Stage 2B-A Kotlin client tests to consume directly. No manual transcription.

**Lock-2 contract tests (in `poll_hold.rs`):**

- `header_padded_only_returns_padded_short_poll` — `X-Phantom-Padded-Poll: 1` alone → response time < 500 ms, body == 4608 bytes exact.
- `header_both_returns_padded_held` — both headers → held to `poll_hold_secs`, body == 4608 bytes exact.
- `header_long_poll_only_preserves_stage1_behavior` — `X-Phantom-Long-Poll: 1` alone → held + padded (Stage 1 legacy: the old header still gates BOTH on Stage 1).
- `neither_header_returns_short_unpadded` — no opt-in → short-poll, legacy small body.

**Lock-3 contract tests (in `token_invalidation.rs`):**

- `issue_new_token_invalidates_prior_for_same_identity` — two `obtain_token` calls for same identity with different challenges; assert T1 validates to 401 after T2 issued, T2 still valid.
- `issue_for_identity_x_does_not_affect_identity_y` — multi-identity isolation.
- `retry_safety_cache_hit_does_not_invalidate_existing_token` — Stage 1 `rest_fallback_endpoints.rs` 5-identical-calls retry-safety still passes (uses `SessionChallengeCache` path, not `issue()`).
- `inflight_poll_on_old_token_completes_naturally` — `tokio::spawn` poll on T1 (opt-in, hold=3s); after 200ms, issue T2; assert poll task returns 200 (not 401 mid-stream); assert subsequent T1 poll returns 401.
- `concurrent_issue_for_same_identity_is_serialised` — `tokio::sync::Barrier::new(3)` + 3 spawned `obtain_token` calls for same identity; after `join_all`, exactly one token validates.
- `inverse_dos_ack_blocked_after_token_rotation` — T1 in-flight hold open; T2 issued; attacker attempts ack via T1; assert 401 (not 200). Pins the existing `rest_ack_deliver` token-validation-at-request-start contract against future drift.
- `t1_in_flight_poll_returns_200_then_t1_ack_returns_401` — T1 poll started + held; T2 issued mid-stream; envelope arrives; T1 poll returns 200 with envelope; assert T1 ack returns 401. Locks the precise b2 in-flight contract (poll completes naturally, ack does NOT).
- `t2_ack_for_envelope_returned_on_t1_poll_succeeds` — same fixture as above; after T1 poll returns the envelope, assert that the legitimate client can ack the envelope using T2 → assert 200. Confirms that the inverse-DoS mitigation does NOT cause envelope loss for the legitimate client (guardrail A preserved).

**Lock-4 contract tests (in `hold_cap.rs`):**

- `three_concurrent_holds_for_same_identity_all_succeed`.
- `fourth_concurrent_hold_returns_429_with_retry_after_30`.
- `concurrent_holds_for_identity_y_not_blocked_by_x_cap`.
- `hold_secs_request_above_cap_clamps_to_480` (config-parse-time + runtime-timeout dual enforcement).
- `concurrent_hold_counter_retires_on_poll_completion`.
- `concurrent_hold_counter_retires_on_client_disconnect` — abort the spawned `JoinHandle`, then **await its completion** (via `handle.await` ignoring the `JoinError`, OR via a watch channel signalled inside the handler's `Drop`); after the future has been observed to drop, assert `hold_count` is decremented. Do NOT assert immediately after `abort()` — tokio's cancellation processing races with the assertion.
- `cap_exhaustion_does_not_drop_pending_envelopes` — guardrail A check.

**Backward-compat regression tests:**

- `old_session_response_struct_without_poll_hold_secs_still_parses` (existing `poll_hold.rs:285`) — confirm still green.
- `old_poll_envelope_struct_without_seq_mac_still_parses` — NEW. Locks the additive Lock-1 wire contract.
- `RELAY_POLL_HOLD_SECS_zero_returns_immediately_with_seq_mac_intact` — kill switch does NOT disable MAC computation.
- `padded_body_4608_invariant_holds_on_both_padded_paths` — guardrail C: padding never reduced silently, byte-exact 4608 on both `X-Phantom-Long-Poll` and `X-Phantom-Padded-Poll` paths.
- `WS_send_path_writes_pollenvelope_with_seq_mac_for_REST_poller` — verifies MAC computed at store-time in `mirror_envelope_to_rest_store` and read by REST poll.

**WS-vs-REST asymmetry contract tests (in `poll_hold.rs`):**

- `ws_deliver_wire_does_not_contain_seq_mac` — send envelope via WS Send; capture WS Deliver frame bytes; assert deserialized JSON does NOT contain key `"seq_mac"` at any nesting level. Server WS Deliver type does NOT serialize the stored MAC column on wire, even though the value exists in storage.
- `rest_poll_wire_contains_seq_mac` — same envelope, REST poll with opt-in header; assert response JSON contains `"seq_mac"` as 64-char lowercase hex string.

**Race-test infrastructure decision.** `tokio::test(flavor = "multi_thread", worker_threads = 4)` + `tokio::sync::Barrier::new(N)`. NOT `loom`. Loom would force production code instrumentation and slow CI; tokio multi-thread runtime is sufficient and the Lock-3 race windows are at HTTP request granularity.

## PR commit boundary

Recommended grouping (5 commits + tests):

- **A1 — HMAC infrastructure**: add `hmac` and `zeroize` crates to workspace deps; add `seq_mac_key` field on `RelayConfig` with env-var parse + `[REDACTED]` Debug + fail-to-start on absent; define `compute_seq_mac()` function with **variable-length canonical encoding (101 bytes + `envelope_id_len` bytes)**.
- **A2 — Lock-1**: store-time HMAC computation in `mirror_envelope_to_rest_store`; new `seq_mac TEXT NOT NULL` column on `RestEnvelope`; `seq_mac` field on `PollEnvelope` wired from stored column; **publish derived per-identity `seq_mac_verify_key` in `SessionResponse`; never publish `RELAY_SEQ_MAC_KEY`**.
- **A3 — Lock-2**: new `PADDED_POLL_OPT_IN_HEADER` constant; `padded_opt_in` derivation; padding gate replaced.
- **A4 — Lock-3**: doc-comment on `issue()`; verify `rest_ack_deliver` token validation behavior (D10 grep gate); contract tests.
- **A5 — Lock-4**: `HoldSlot` wrapper struct; `HoldGuard` RAII; per-identity cap CAS guard; `max_poll_hold_secs` config field + dual-layer enforcement; 429 response shape.
- **A6 — Tests + this doc**: all new contract tests + this `docs/tracks/trek2-stage1x-server-prereq.md`. Tests co-located with their A-commits where possible; this commit collects what didn't fit.

## Locked answers to scope OQs (2026-06-10)

- **OQ-1 → YES.** Hard fail-to-start if `RELAY_SEQ_MAC_KEY` is missing. Deployment runbook must require provisioning the key before relay redeploy (`openssl rand -hex 32`).
- **OQ-2 → CONFIRMED against master `f9c81937`.** `rest_ack_deliver` validates the bearer via `state.rest_tokens.validate(token).await` at request start. No ack-handler change needed unless implementation drifts; the `inverse_dos_ack_blocked_after_token_rotation` regression test locks the contract.
- **OQ-3 → YES.** 60_000ms `sequence_ts` quantization floor confirmed by master: `quantize_sequence_ts_to_60s(ts_ms) = ts_ms - ts_ms % 60_000`.
- **OQ-4 → YES.** Cap = 3 concurrent holds per identity.
- **OQ-5 → REVERSED.** `envelope_id` is NOT a 36-byte UUID. Server `RestSendRequest.envelope_id` is typed `String`, and Stage 2A `EnvelopeId.random()` is 32-char lowercase hex. Encoding changed to length-prefixed `envelope_id` (`u16-BE len` + bytes) in the canonical HMAC input (see Lock-1 above).
- **OQ-6 → SEPARATE DOCS PR FIRST.** This document ships as a standalone docs PR ahead of any implementation. Implementation PR opens only after this PR is merged + independent review round completed.
- **OQ-7 → YES.** `zeroize` crate addition is acceptable. Use for root key material. Do NOT add `secrecy` unless an independent review finds a concrete misuse path.
- **OQ-8 → YES.** Stage 1.x is a server-only PR. WORKING_RULES rule 8 carve-out applies — no Tele2 LTE smoke required for Stage 1.x. CI + contract tests + race tests are the merge gate. Stage 2B-B (client cursor + breaker + reauth) WILL need Tele2 smoke when it ships later.

## Review outcome (2026-06-10)

Independent review of the original scope returned AMEND-REQUIRED on all 4 must-look-at items. The amendments are applied in this revision:

- **Item 1 (HMAC domain separation):** `envelope_id_bytes` reframed as exact UTF-8 bytes (NOT ASCII assumption), with server-side max-length validation requirement.
- **Item 2 (Key material handling):** root key type changed from `Vec<u8>` to fixed-size `Zeroizing<[u8; 32]>` newtype. Log-redaction rule expanded to include the derived per-identity `seq_mac_verify_key`.
- **Item 3 (Inverse-DoS):** Lock-3 in-flight contract corrected — envelope returned on T1 poll is NOT ackable via T1; ack must use T2. Two new tests pin the precise contract (T1 ack → 401, T2 ack → 200).
- **Item 4 (RAII HoldGuard):** "Drop fires synchronously under `JoinHandle::abort()`" reframed — Drop fires when the future is actually dropped; tests must await the JoinHandle before asserting `hold_count`. TCP-close wording corrected: cleanup is bounded by hold timeout, not at the exact network-close instant.

## Review must-look-at items (original list, retained for historical context)

The following design decisions warranted independent cryptographer review before merge. All 4 returned AMEND-REQUIRED and have been addressed above:

1. **HMAC-SHA-256 input domain separation.** **AMENDED:** Tags confirmed distinct. `envelope_id_bytes` reframed as exact UTF-8 bytes (not ASCII assumption); server-side validation `len ≤ 65535` required.
2. **Key material handling.** **AMENDED:** changed to fixed-size `Zeroizing<[u8; 32]>` newtype. Log-redaction rule expanded to include derived `seq_mac_verify_key`.
3. **Lock-3 inverse-DoS scenario.** **AMENDED:** in-flight ack contract corrected — T1 ack returns 401, T2 ack for same envelope returns 200. Two new tests pin the contract.
4. **Lock-4 RAII guard under tokio cancellation.** **AMENDED:** Drop semantics reframed — fires when future is actually dropped (not at `abort()` instant); tests must await JoinHandle before asserting `hold_count`. TCP-close cleanup bounded by hold timeout, not network-close instant.

## Cross-references

- Stage 1 SHIPPED (the contract surface Stage 1.x extends): master `dc6f52df` (PR #297).
- Stage 2A SHIPPED: master `30dc16f5` (PR #298).
- Trek 2 mini-lock 2026-06-09 (9 decisions + 3 guardrails): in repo via PR #297 docs.
- Stage 2B client implementation is PAUSED behind this Stage 1.x server PR.

---

**End of locked scope. Implementation follows in a subsequent PR.**
