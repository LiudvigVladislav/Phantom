# Trek 2 Stage 2B-B — Round 1 preflight: test-matrix review

Stage 2B-A shell merged today as PR #306 at master `0ac29cf5`. Stage 2B-B is the semantic layer: `seq_mac` HMAC-SHA-256 verify per envelope, cursor advance gated on `(verify ∧ accept/dedup)`, WS↔REST circuit breaker, re-auth on `410 Gone`, jitter via `Csprng`, plus the Tele2 LTE field gate.

## 1. Test-matrix blockers (P1)

**B1 — No client mirror of `services/relay/tests/seq_mac_vectors.rs`.** The server has 13 byte-pinned vectors. If the Kotlin verifier is built from spec alone, canonical-input encoding can drift silently — wrong length-prefix width, char-length vs UTF-8-byte-length, sign-extension on `seq` / `sequence_ts`, hex-decode of the verify key into wrong byte order — and CI passes because the verifier is the only thing exercising itself. Stage 1.x already names `cargo test --features test-vectors-export` for exactly this consumer.

**B2 — Cursor monotonicity is not pinned anywhere.** Stage 2B-A pins "cursor not written". The instant 2B-B opens the write seam, no test asserts monotonic non-decreasing advance across `(WS Deliver, REST poll)` interleavings or restarts. A regression that writes `max(seq)` without comparing against persisted, or writes out-of-order, drops messages on re-poll. Highest-impact correctness invariant; no cell for it.

**B3 — No test for "verify failure must NOT advance the cursor."** `m5_*` proves cursor is forwarded out; nothing proves advance is gated on `verify ∧ (accept ∨ dedup)`. A 2B-B that writes on raw receipt silently loses every MAC-rejected envelope: the next poll requests `since_seq > rejected.seq` and the server has no obligation to re-send. Exact failure mode lock L4 structurally prevented in 2B-A — 2B-B has to assert behaviourally.

**B4 — Breaker has no state-transition matrix.** Scope names "circuit breaker between WS and REST poll" but no closed/open/half-open contract test exists, and no test for "breaker does NOT lose the cursor while transitioning." A breaker that resets `lastSeenSeq` on open re-delivers every envelope in the retention window per transition.

**B5 — No test pins that jitter is drawn from `Csprng`, not `Random.Default`.** `RestFallbackOrchestrator` carries 9 `Random.Default.nextDouble()` sites (lines 415, 468, 487, 591, 644, 662, 699, 803, 824, 848). Stage 2B-B migrates per OQ7. A missed site under the breaker becomes a herd-amplifier on Tele2 token churn. A grep-based `androidUnitTest` pin would catch this; none exists.

**B6 — No `410 Gone` re-auth test.** Zero client cells for (a) detecting 410, (b) re-running `authSessionOnce()`, (c) retrying with fresh token, (d) NOT retrying if 410 fires twice in <30 s, (e) preserving cursor across the dance. A client that loops 410 → re-auth → 410 generates the inverse-DoS pattern Stage 1.x Lock-3 closed on the server.

## 2. Test-matrix concerns (P2)

**C1 — `LongPollOptInWireRequestTest` is regex over source.** Cheap in 2B-A. In 2B-B the verify path adds `if (longPollOptIn)` shapes and `count == 1` regex gets brittle. Behavioural fake-transport coverage carries the same invariant without snapping under refactor.

**C2 — `m6_session_response_seq_mac_verify_key_is_cached_in_session_scope` proves caching but not that the verifier sees those exact bytes** rather than the empty default `""`.

**C3 — Old-relay path needs a behavioural cell, not just a DTO sanity check.** Need "old relay → MAC missing → verifier returns `MissingMac` → orchestrator falls back per locked policy."

**C4 — Existing `RestFallbackOrchestratorTest` uses `UnconfinedTestDispatcher`; breaker tests need virtual time.** `m7_*` already shows the right pattern (`StandardTestDispatcher(testScheduler) + advanceTimeBy + runCurrent`). New cells must keep to it.

**C5 — `(verify ∧ storage-error)` and `(verify ∧ dedup)` look identical at `_inbound.emit(env)`.** `dedup` advances cursor; `storage-error` does not. Must split in test.

## 3. Required test matrix M8–M17

**M8 — `seq_mac` golden-vector parity.** `commonTest/.../SeqMacGoldenVectorTest.kt`. Golden-vector. All 13 server vectors: 3 domain-tag pins, 2 derivation determinism, 5 corner cases, 3 boundary cases (empty `envelope_id`, `u16::MAX` byte boundary, multi-byte UTF-8 byte-length).

**M9 — verify success path.** `commonTest/.../SeqMacVerifierTest.kt`. Behavioural. Known key + canonical input → `Verified`; deterministic across two calls.

**M10 — verify failure paths (6 sub-cells).** Same file. Behavioural. (a) wrong key, (b) wrong `identity_hex` single-bit flip, (c) wrong `seq` off-by-one, (d) pre-quantize `sequence_ts` (pinned against server vector 4), (e) wrong `envelope_id` (single char + UTF-8 byte-length collision), (f) tampered MAC bytes (last hex char). Each → `Rejected`. `seqMac == ""` → discriminated `MissingMac`.

**M11 — cursor advance gate (4 sub-cells).** `commonTest/.../LongPollCursorAdvanceGateTest.kt`. Behavioural with fake writer/verifier/storage. (a) `(verify ∧ accept) → advance`; (b) `(verify ∧ dedup) → advance`; (c) `(verify ∧ storage-error) → NO advance`; (d) `(verify-fail) → NO advance` AND no emit on `_inbound`.

**M12 — cursor monotonicity.** Same file. Behavioural. Out-of-order batch `seq = [3, 5, 4, 6]` with concurrent WS Deliver at `seq = 7`; persisted cursor `≥ 7` at end-of-batch; writer never called with non-increasing value. Pins B2.

**M13 — breaker state-transition matrix (5 sub-cells).** `commonTest/.../RestPollCircuitBreakerTest.kt`. Behavioural with `StandardTestDispatcher + advanceTimeBy`. (a) closed → open on N consecutive failures (N from a constant); (b) open → half-open after recovery window; (c) half-open + success → closed; (d) half-open + failure → open with refreshed timer; (e) cursor preserved across all transitions.

**M14 — `410 Gone` re-auth (5 sub-cells).** Same file or sibling. Behavioural. (a) 410 → `authSessionOnce()` once → retry with new token → 200 → envelope emitted; (b) 410 → re-auth fails → no busy-loop; (c) 410 → re-auth → 410 within 30 s → exponential backoff, no fresh re-auth; (d) cursor preserved across 410 dance; (e) 410 on `/relay/ack-deliver` uses existing self-healing fall-through, NOT re-auth dance (Stage 1.x Lock-3: T1 ack after T2 → 401, burning a fresh token there would be wrong).

**M15 — jitter source is Csprng (pair).** `androidUnitTest/.../JitterSourceCsprngPinTest.kt` greps `RestFallbackOrchestrator.kt` for `Random\.Default` and asserts `count == 0`. `commonTest/.../JitterSourceCsprngBehaviourTest.kt` injects a recording `Csprng` fake and asserts every jitter draw goes through `uniformLong(...)`. Either alone has a blind spot.

**M16 — L6 release pin still holds.** `LongPollV2ReleaseBuildConfigPinTest` runs unchanged; assert `LONGPOLL_V2_ENABLED == "0"` in release at the 2B-B PR boundary. A single PR cannot add the semantic layer AND flip the release pin — the flip is a separate promotion PR.

**M17 — old-relay `MISSING_MAC` outcome class.** `commonTest`. Behavioural. Fake transport returns `seqMac = ""`; verifier returns `MissingMac` (discriminated from `Rejected`); orchestrator behaviour pinned per OQ-2. Silent pass-through must be impossible by test.

## 4. Golden vector compatibility

Mirror the 13 server vectors in `commonTest/.../SeqMacGoldenVectorTest.kt` with a `private object Vectors` carrying byte-exact constants identical to the Rust constants: domain tags via `byteArrayOf(...)`; `u64` fields as `Long` with sign-extension explicitly handled by the encoder; `envelope_id` as `String`; expected MAC as 64 lowercase hex.

Canonical input bytes per `docs/tracks/trek2-stage1x-server-prereq.md` lines 42–49:

19 bytes `b"phantom-seq-mac-v1\x00"`, 64 bytes lowercase ASCII `identity_hex`, 8 bytes big-endian `seq`, 2 bytes big-endian `u16` length of `envelope_id` in **UTF-8 bytes** (not chars), those exact UTF-8 bytes, 8 bytes big-endian `sequence_ts`. Total 101 + UTF-8 byte length. HMAC-SHA-256 → 32 bytes → 64-char lowercase hex.

Each vector: Kotlin reconstructs canonical input bytes, runs HMAC-SHA-256 via the existing libsodium binding, asserts hex matches the server's pinned constant. Source of Rust constants: direct copy-paste with `// from services/relay/tests/seq_mac_vectors.rs` comment (Round 1) or `services/relay/test-fixtures/seq_mac_vectors.json` via `--features test-vectors-export` (Round 2 nice-to-have).

Per-identity verify-key derivation: domain tag `b"phantom-seq-mac-key-v1\x00"` (23 bytes) + 64-byte lowercase ASCII `identity_hex`. Client only ever sees the derived 32-byte key in `SessionResponse.seq_mac_verify_key`. M8 covers at least one derivation case for `[0u8;32]` root + `"a".repeat(64)` identity, byte-pinned against server `derive_verify_key_is_deterministic_for_zero_root`.

## 5. Tele2 LTE smoke scenarios

Mandatory per WORKING_RULES rule 8. Each scenario gives one observable pass criterion via device log line.

**S1 — WS up, REST poll arrives, MAC verifies, no decrypt regression.** Direct WSS up; `LONGPOLL_V2_ENABLED=="1"`. Peer sends 5 envelopes. Pass: every envelope in chat exactly once; no `seq_mac_verify_fail`; no `decrypt_fail`; `ws_active_poll_ok envelopes=N` and WS Deliver both increment without message-table duplicates.

**S2 — WS down, REST primary, multi-envelope batch.** Force WS down. Peer sends 4 envelopes with `more=true` on first poll. Pass: all 4 in seq-order; persisted `lastSeenSeq` advances to highest; cold restart polls with that `since_seq` and gets zero duplicates.

**S3 — Token rotation mid-poll, 410 fires, re-auth, retry.** Trigger fresh `/auth/session` from another device. Pass: log `poll_410_token_rotated`; log `session_request` at re-auth; log `ws_active_poll_ok envelopes=…` after retry; no duplicated or lost envelope; cursor preserved.

**S4 — Server kill switch `RELAY_POLL_HOLD_SECS=0`.** Operator flips env var. Pass: client receives short-poll cadence with padded (4608-byte) body shape per the locked decoupling; `poll_hold_secs=0` parsed; `computeLongPollReadTimeoutMs` returns `null` and OkHttp falls back to legacy timeout; no `socket_timeout`; chat continues via short-poll.

**S5 — Voice rule belt-and-braces.** Throughout S1–S4, voice notes from peer arrive in same poll window as text sent at same instant. Privacy-modes uniform-functionality lock (2026-06-06): long-poll must not degrade voice.

**S6 (optional but high-value) — Breaker open under Tele2 byte-budget death.** Run long enough to provoke Mode-2 cutoff (5–14 KB upload then silence, project memory). Pass: `breaker_open` log fires within threshold; cursor preserved; envelopes continue on whichever path the breaker selects. Historically flaky in 15 min — see OQ-4.

## 6. Open questions for the user (≤ 5)

1. **Breaker open/close threshold and recovery window.** Scope names the breaker without N consecutive failures or recovery duration. M13 needs both locked.

2. **`MISSING_MAC` policy on old relay.** When `seqMac == ""`: pass-through to legacy decrypt, refuse envelope, or pass-through with telemetry counter? M17 pins whichever.

3. **Cursor write seam shape.** Add a second SAM `LongPollCursorWriter` (parallel to the reader), or a combined `LongPollCursorReadWriter`? Affects M11 fake shape and AppContainer wiring test.

4. **S6 hard-gate or best-effort?** WORKING_RULES rule 8 says the smoke runs; not which scenarios must pass. Tele2 Mode-2 reproduction is flaky in 15 min.

5. **Jitter migration scope.** Migrate ALL 9 existing `Random.Default` sites in `RestFallbackOrchestrator` to `Csprng` in 2B-B, or only new 2B-B sites? M15 source-parse pin requires the answer.
