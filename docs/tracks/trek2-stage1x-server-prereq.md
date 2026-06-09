# Trek 2 Stage 1.x — Server-Side Prerequisite (Scope Mini-Lock DRAFT)

**Status:** DRAFT — proposed Stage 1.x scope mini-lock awaiting Vladislav + Codex confirmation. No code written. Branch `feat/pr-trek2-stage1x-server-prereq` parked at master `f9c81937`. This document captures the synthesis of two-layer preflight (Layer 1 = Claude Code project subagents; Layer 2 = Ruflo MCP sonnet cross-check) conducted 2026-06-10.

**Why this PR exists:** Trek 2 Stage 2B client preflight 2026-06-10 surfaced 4 server-side BLOCKERS that prevent the client-side long-poll runtime from being safe to ship. Stage 2B client implementation is PAUSED until this PR merges to master.

## Vladislav-locked threat-model wording (verbatim, do NOT soften)

The following wording is locked verbatim by Vladislav 2026-06-10 and MUST appear unchanged in:

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
envelope_id_len (big-endian u16)    2 bytes
envelope_id_bytes                  variable   ASCII string (length-prefixed)
sequence_ts (big-endian u64)        8 bytes   POST-quantize value (60_000ms floor)
```

Total length = 101 bytes + `envelope_id_len` bytes. Output: 32 bytes → lowercase hex 64 chars on wire.

**Why `envelope_id` is length-prefixed, not fixed-width.** Vladislav verified against master `f9c81937` that the server-side `RestSendRequest.envelope_id` is typed as `String` (not enforced to a UUID shape), and Stage 2A `EnvelopeId.random()` generates a 32-char lowercase hex (NOT a 36-byte UUID canonical hyphenated form). The MAC encoding cannot assume a fixed 36-byte UUID. Length-prefix with a `u16-BE` length field closes the variable-length ambiguity that motivates collision-free domain separation; the server-side request handler must enforce `envelope_id` length ≤ 65535 bytes (a generous ceiling — production sizes are ~32 chars).

**Domain tag rationale.** The `\x00` null terminator cannot appear in the subsequent ASCII `identity_hex` field, making domain separation unambiguous at the boundary. The `v1` version string enables future MAC scheme rotation (`v2\x00`) without changing the field layout.

**Why store-time computation, not response-time.** Response-time HMAC computed inside `drain_eligible` would be computed over whatever values the DB returns — including already-corrupted values. A client receiving a response-time MAC over corrupted data would verify successfully and consume the corrupted envelope. This collapses the locked "DB tamper" threat scope to "tamper between drain and wire" — a far narrower threat than the wording promises. Store-time computation creates a persistent integrity anchor: the MAC reflects the envelope state at the moment of write; any subsequent corruption causes a mismatch when re-read.

**HMAC root key sourcing.** Environment variable `RELAY_SEQ_MAC_KEY` (64-char hex = 32 bytes), parsed at startup into `Vec<u8>`. Stored in `RelayConfig` with `[REDACTED]` `Debug` impl mirroring the existing `secret_token` pattern at `config.rs:221`. Key bytes implement `Zeroize` / `ZeroizeOnDrop` (low cost, correct practice for 32-byte key material).

**The root key NEVER leaves the relay process.** It is used exclusively to derive per-identity verify keys (see "Client key distribution" below). Server-side MAC computation uses the derived per-identity key, NOT the root key directly.

**Fail-to-start.** If `RELAY_SEQ_MAC_KEY` is absent at startup, the relay process exits with a startup error. NOT warn-and-disable — silently disabling MAC ships a relay that violates its own security contract.

**Test fixture key.** `[0u8; 32]` hardcoded in `RelayConfig::from_env_for_test()`. Deterministic, never used in production paths.

**Log redaction.** `seq_mac` values MUST NOT appear in any `tracing` event at any level. The MAC value is observable on the wire, but log-to-wire correlation enables traffic analysis. HMAC key bytes MUST NOT appear in logs at any level.

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

Documented as integrity signal for client-side anomaly detection, NOT as an access-control boundary. Matches the locked threat wording exactly: a fully malicious relay operator with the root key can still mint any verify key and forge any MAC, but the protection scope is precisely as Vladislav's wording states.

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
- Circuit-breaker open (Stage 2B client falls back to short-poll): drop `X-Phantom-Long-Poll`, retain `X-Phantom-Padded-Poll`. This preserves padding posture during the breaker's transient short-poll period — closes the guardrail C ("padding never reduced silently") gap that Layer 2 security identified.

**Header strict equality:** `v == "1"`, matching the existing `LONG_POLL_OPT_IN_HEADER` pattern. Any other value treated as absent.

**Server statelessness.** No per-identity persistent flag. Each request independently evaluated. Restart-safe.

### Lock-3 — immediate prior-token invalidation on `TokenStore::issue()`

**Stage 1.x Lock-3 invariant (the contract):**

> After 3 concurrent `issue()` calls for the same identity with distinct challenges, **exactly one returned token validates** via `state.rest_tokens.validate(token).await` after `join_all` returns. The other two return `None`.

**Implementation strategy is invariant-driven, not pre-locked.** Initial analysis (Layer 1 architect + tester + Layer 2 architecture) suggests the existing `RestTokenStore::issue()` at `rest_fallback.rs:175-194` already delivers this invariant via the write-lock removal of the prior token from `by_token` before inserting the new token. The TOCTOU window between releasing the `by_token` write lock and acquiring the `by_identity` write lock appears benign in consequence — a concurrent `validate()` observes at most a transient `None`, never a successful validate against the prior token.

**If the concurrent-issue contract test passes against the existing code,** Stage 1.x Lock-3 deliverable reduces to:

1. Doc-comment on `issue()` explicitly stating the invalidation invariant verbatim.
2. The concurrent-issue contract test as the invariant-pinning gate (see Tests section below).
3. **In-flight poll behavior locked as b2:** an in-flight long-poll request on T1 (when T2 is issued mid-stream) completes naturally on T1. The returned envelope may be ack'd via T1 while the connection is still open. The next poll request on T1 returns 401.

**If the concurrent-issue contract test FAILS against the existing code, structural refactor enters scope.** Most likely refactor: collapse `issue()` into a single critical section that acquires `by_identity` write-lock first, then `by_token` write-lock (consistent lock-acquisition order across all `RestTokenStore` mutations), removing the split-lock TOCTOU pattern. The test, not a pre-locked design decision, drives whether refactor is required.

**Inverse-DoS mitigation (Vladislav-confirmed against master `f9c81937`).** Layer 2 test-plan flagged an attack window: attacker with stolen T1 triggers T2 issuance (via legitimate client's reauth path), then races to ack the in-flight envelope on T1, draining it from the queue before the legitimate T2-holder polls.

This window is **structurally closed by the existing `rest_ack_deliver` behavior:** Vladislav confirmed against master that `rest_ack_deliver` validates the bearer via `state.rest_tokens.validate(token).await` at request start (not at connection open / not cached). Under b2, when T2 is issued, T1 is immediately removed from `by_token`, so any subsequent ack on T1 returns 401. No ack-handler modification is required.

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

**Synchronous-drop guarantee.** Rust language guarantee (NOT a tokio policy): `Drop` fires synchronously when the owning future is dropped, including under `JoinHandle::abort()`. The tokio runtime drops the future in-place on the thread executing the abort, before scheduling the next task. `Drop` is not deferred to a finalizer thread. This guarantee covers: hold loop completion (timeout or wake), axum handler future drop on client TCP close, server shutdown / runtime drop, panic / early return paths, and external `JoinHandle::abort()`.

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
- `inverse_dos_ack_blocked_after_token_rotation` — T1 in-flight hold open; T2 issued; attacker attempts ack via T1; assert 401 (not 200). Pins the existing Vladislav-confirmed `rest_ack_deliver` token-validation-at-request-start contract against future drift.

**Lock-4 contract tests (in `hold_cap.rs`):**

- `three_concurrent_holds_for_same_identity_all_succeed`.
- `fourth_concurrent_hold_returns_429_with_retry_after_30`.
- `concurrent_holds_for_identity_y_not_blocked_by_x_cap`.
- `hold_secs_request_above_cap_clamps_to_480` (config-parse-time + runtime-timeout dual enforcement).
- `concurrent_hold_counter_retires_on_poll_completion`.
- `concurrent_hold_counter_retires_on_client_disconnect` — drop the spawned `JoinHandle`; assert hold_count decremented synchronously.
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

## Vladislav-locked answers to scope OQs (2026-06-10)

- **OQ-1 → YES.** Hard fail-to-start if `RELAY_SEQ_MAC_KEY` is missing. Deployment runbook must require provisioning the key before relay redeploy (`openssl rand -hex 32`).
- **OQ-2 → CONFIRMED against master `f9c81937`.** `rest_ack_deliver` validates the bearer via `state.rest_tokens.validate(token).await` at request start. No ack-handler change needed unless implementation drifts; the `inverse_dos_ack_blocked_after_token_rotation` regression test locks the contract.
- **OQ-3 → YES.** 60_000ms `sequence_ts` quantization floor confirmed by master: `quantize_sequence_ts_to_60s(ts_ms) = ts_ms - ts_ms % 60_000`.
- **OQ-4 → YES.** Cap = 3 concurrent holds per identity.
- **OQ-5 → REVERSED.** `envelope_id` is NOT a 36-byte UUID. Server `RestSendRequest.envelope_id` is typed `String`, and Stage 2A `EnvelopeId.random()` is 32-char lowercase hex. Encoding changed to length-prefixed `envelope_id` (`u16-BE len` + bytes) in the canonical HMAC input (see Lock-1 above).
- **OQ-6 → SEPARATE DOCS PR FIRST.** This document ships as a standalone docs PR ahead of any implementation. Implementation PR opens only after this PR is merged + Codex review round completed.
- **OQ-7 → YES.** `zeroize` crate addition is acceptable. Use for root key material. Do NOT add `secrecy` unless Codex review explicitly demands it.
- **OQ-8 → YES.** Stage 1.x is a server-only PR. WORKING_RULES rule 8 carve-out applies — no Tele2 LTE smoke required for Stage 1.x. CI + contract tests + race tests are the merge gate. Stage 2B-B (client cursor + breaker + reauth) WILL need Tele2 smoke when it ships later.

## Codex review must-look-at items

Per Layer 2 security cross-check, the following design decisions warrant independent cryptographer review before merge:

1. **HMAC-SHA-256 input domain separation.** Confirm the `b"phantom-seq-mac-v1\x00"` MAC-computation domain tag AND the `b"phantom-seq-mac-key-v1\x00"` key-derivation domain tag are distinct and free from cross-domain reuse. Verify the length-prefixed encoding (`u16-BE len(envelope_id)` + envelope_id_bytes) produces an injective mapping across all production envelope_id formats (32-char hex from Stage 2A, plus any other shapes the server accepts). Confirm quantized `sequence_ts` + length-prefixed `envelope_id` + monotonic `seq` + fixed-length `identity_hex` yields collision-free MAC inputs.
2. **Key material handling.** Confirm `Vec<u8>` + `Zeroize` / `ZeroizeOnDrop` is the correct Rust memory-handling approach. Evaluate whether the `secrecy::Secret<[u8; 32]>` pattern is warranted (stronger compile-time guarantee but adds a dep).
3. **Lock-3 inverse-DoS scenario.** Verify the D10 mitigation (ack validates current token state at ack time) actually closes the attack window. Confirm there is no alternative path where T1 ack succeeds after T2 issuance.
4. **Lock-4 RAII guard under tokio cancellation.** Confirm `Drop` fires synchronously on `JoinHandle::abort()` under multi-thread runtime. The Rust language guarantee holds, but verify no tokio-specific path defers Drop to a finalizer.

## Cross-references

- Layer 1 + Layer 2 preflight synthesis: `~/.claude/projects/.../memory/project_trek2_stage1x_preflight_synthesis_2026_06_10.md` (local).
- Vladislav's original 4-lock scope: `~/.claude/projects/.../memory/project_trek2_stage1x_minilock_2026_06_10.md` (local).
- Stage 2B client mini-lock (PAUSED behind this PR): `~/.claude/projects/.../memory/project_trek2_stage2b_minilock_2026_06_10.md` (local).
- Stage 2B preflight synthesis (surfaced the 4 server BLOCKERs): `~/.claude/projects/.../memory/project_trek2_stage2b_preflight_synthesis_2026_06_10.md` (local).
- Stage 1 SHIPPED (the contract surface Stage 1.x extends): master `dc6f52df` (PR #297).
- Stage 2A SHIPPED: master `30dc16f5` (PR #298).
- Trek 2 mini-lock 2026-06-09 (9 decisions + 3 guardrails): in repo via PR #297 docs.

---

**End of DRAFT. Awaiting Vladislav + Codex confirmation before any code is written.**
