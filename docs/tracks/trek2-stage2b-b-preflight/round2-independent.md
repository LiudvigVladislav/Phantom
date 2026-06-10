# Trek 2 Stage 2B-B — Round 2 Independent Preflight

> **Status — historical review input.** This file is one of five review documents whose findings were consolidated into [synthesis.md](synthesis.md). The binding decisions for Stage 2B-B implementation are in `synthesis.md § User locks (2026-06-10)` + D1-D15. Some open questions and "recommended" options in this file have since been superseded or amended. Do NOT code against guidance in this file directly; consult the synthesis.

Round 2 is a second-pass review of Round 1 across the three high-risk dimensions of Stage 2B-B: security, implementation risk, and the test matrix. This pass had no access to source code; it worked from Round 1 summaries plus excerpts. Its job is to find what the first round missed, to flag claims that do not hold up, and to strengthen what does.

---

## Round 2 — Security

### New blockers (P1)

**B4 — MAC key provenance is unspecified and potentially self-defeating.** The entire `seq_mac` scheme depends on `_seqMacVerifyKey` being established from a source that the relay cannot unilaterally control. If the relay delivers the verify key to the client (e.g., inside a handshake payload, a session-init response, or any relay-signed structure where the relay also controls signing), then the MAC provides zero authentication: a relay-layer attacker sends a forged key alongside forged MACs and both verify correctly. Round 1's B1 and B2 fixes are moot in this case. Before scope closes, the provenance of `_seqMacVerifyKey` must be documented and locked. Acceptable: key derived client-side from session material that never transits the relay. Unacceptable: any path where the relay is the authoritative source of the key value.

**B5 — Circuit-breaker-open is a potential MAC-verify downgrade trigger.** If injecting envelopes with systematically bad MACs causes enough verify-failures to trip the breaker into the open state, and the open-state fallback either skips verification or re-enters the legacy `reason=no_verify_key` unverified path, an attacker can force a downgrade to unverified ingestion by flooding garbage envelopes. Round 1 does not address whether MAC verify failures count as health-check failures for breaker state, nor whether the open-state REST path enforces the same verify gate. Both must be locked explicitly: breaker trip signals must be transport errors only, never MAC failures; and the REST fallback path must enforce identical verify semantics regardless of breaker state.

### Concerns (P2) and Round-1 contradictions

**B2 mitigation creates a covert unverified window.** Round 1 correctly identifies the stale-key problem and recommends resetting `_seqMacVerifyKey = ""` on the failed-refresh path. However, the locked behaviour rules simultaneously specify that `_seqMacVerifyKey == ""` triggers the legacy unverified path with `reason=no_verify_key` rather than a hard drop. This means the B2 fix trades a stale-key false-positive for a silent verification bypass for the duration of the degraded-auth window. That is a contradiction: B2's mitigation partially re-introduces the same risk it is meant to close. The correct posture for a non-empty `_seqMacVerifyKey` that is then cleared on failure is to enter a **suspended ingestion** state — no envelope accepted, no emit, no cursor advance — until a fresh key is established, not a fallback to unverified. The legacy no-key path should be reserved for relays that have never supplied a key, not for post-failure cleared state.

**Two-cursor reconciliation protocol is under-specified.** Round 1 flags the in-memory `lastSeenSeq` at line 684 advancing without MAC verify as a P2 but does not define the merge/tie-breaking rule when `wsActivePollLoop` transitions from unverified in-memory cursor to the verified persisted cursor in `LastSeenSeqRepository`. If the persisted value is lower than in-memory (e.g., process killed mid-session), re-polling from the lower cursor re-delivers already-emitted envelopes. If it is higher, messages are silently skipped. The reconciliation rule — and who wins — must be stated explicitly in the implementation spec.

**`pollHoldSecs` clamping range is not bounded in 2B-B scope.** N3 carry-forward says 2B-B must clamp relay-supplied breaker cooldown and reauth interval values, but no numeric ceiling is given. A clamp of `0..3600` still permits relay-forced one-hour polling blackouts. A concrete upper bound (suggested: 120 s for breaker cooldown, 60 s for 410 reauth backoff) should be locked alongside the existing `pollHoldSecs in 1..480` gate.

### Round-1 claims that hold up

B1 (constant-time comparison) is correctly identified and the mitigation — hex-decode to raw bytes before comparison, XOR-accumulate in `phantom.core.crypto` — is the right call. B3 (cursor-after-storage ordering) is also correct and remains a hard requirement; the ordering invariant should be enforced at the repository interface level, not left to call-site discipline. The 410 reauth ≥5 s backoff recommendation is sound and should be a hard lower bound, not advisory.

### Open questions to lock before scope

1. What is the exact provenance and transport path of `_seqMacVerifyKey`? Document it in the threat model addendum before any implementation review signs off.
2. Are the `LastSeenSeqRepository` cursor write and the message-table insert wrapped in a single SQLCipher transaction, or are they separate commits? If separate, B3 is not fully closed by ordering alone.
3. What is the specified client behaviour when both WS and REST breakers are simultaneously open — is there a hard-stop with user-visible error, or silent retry accumulation?
4. Is there a cap on consecutive 410 responses before a hard local logout? Without one, a relay returning persistent 410s exhausts the reauth loop indefinitely.

---

## Round 2 — Implementation risk

### New blockers (P1)

**P1-5 — `envelope_id_len` must use UTF-8 byte length, not Kotlin `String.length`.** The canonical encoding spec explicitly states "u16 big-endian (UTF-8 byte length, not chars)". Kotlin `String.length` returns UTF-16 char count. For any `envelope_id` containing multi-byte UTF-8 sequences (emoji, CJK, certain Latin extended), `envelopeId.length.toShort()` produces a wrong field value, the MAC never verifies, and the failure is entirely silent — it just looks like a relay-side bad MAC. The only correct expression is `envelopeId.encodeToByteArray().size`, and the same byte array must be used for both the length field and the subsequent variable-length body bytes. Round 1 reprints the spec verbatim but does not flag this as a concrete implementation trap.

**P1-6 — The HMAC key itself must be hex-decoded before passing to `Auth.authHmacSha256`.** Round 1 flags hex-decode-before-comparison for the MAC output (B1), but `_seqMacVerifyKey` is a `String` field. If that string is hex-encoded key material and the implementation passes it directly to `Auth.authHmacSha256` as its `key` argument, the effective key is the ASCII bytes of the hex string — a completely different key, silently producing wrong verification for every envelope. The decode requirement applies to the key input, not only to the comparison output. This must be stated as an explicit implementation constraint, not left implicit.

**P1-7 — `identity_hex` binding is underspecified.** The encoding spec says "receiving identity" but does not define what key material is hexed, at what encoding stage (raw public key bytes vs. a derived form), or whose perspective "receiving" means from the client's call site. Any ambiguity here — uppercase vs. lowercase, sender vs. receiver identity, key bytes vs. fingerprint — silently breaks MAC verify for all envelopes without a detectable error other than universal `mac_mismatch`. The exact derivation of the 64-byte lowercase ASCII value must be locked to a single authoritative definition shared by server `seq_mac.rs` and the client implementation before any code is written.

### Concerns (P2) and Round-1 contradictions

**`emit` recommendation conflicts with single-suspension-point rule.** Round 1 correctly identifies the `tryEmit`-vs-`emit` risk but the recommended fix ("switch to `emit`") is underspecified against P1-3's constraint that verify-then-emit must stay inside a single suspension point with no interleaving `launch`. A suspending `emit` held open by a full buffer leaves the coroutine context live and cancellation-sensitive; if cancellation arrives between `emit` return and the storage-acceptance callback, the cursor can still advance past an undelivered envelope. The fix requires both `emit` *and* the storage callback being wired atomically to the post-emit return, with structured-concurrency cancellation propagation considered explicitly.

**Breaker state and `RestStateMachine` have no defined interaction protocol.** Round 1 recommends keeping `LongPollBreakerState` separate from `RestStateMachine`. This is structurally sound, but the review does not define the synchronisation contract between them. Breaker open while `RestStateMachine` is `RestActive` is a reachable diverged state with no specified resolution. The breaker's open signal must have a defined, named effect on the state machine (or the two must share a single serialised event bus), otherwise concurrent state divergence is an implementation-time surprise, not a design choice.

**Implicit half-open contradicts tracked-Job requirement.** Round 1 says the breaker timer Job must be tracked and cancelled by `stop()`, and separately that the existing jittered delay serves as implicit half-open. Cancelling the timer Job on `stop()` means the open→half-open transition never fires on a restart cycle. Half-open must be a named state with explicit entry and a cancel-safe re-entry path, not an emergent side-effect of delay scheduling.

**Monotonic cursor write not enforced at repository layer.** Round 1's Option (b) cursor seam correctly separates read-only from read-write contracts, but does not require `upsertLastSeenSeq` to enforce `new_seq > current_seq` at the DB layer. A concurrent upsert from a race between WS and REST paths can write a lower seq over a higher one. Repository-level monotonicity guard (conditional SQL update or check-before-write inside the transaction) must be in scope.

### Round-1 claims that hold up

P1-2 (`java.nio.ByteBuffer` ban, `ushr` arithmetic) and P1-4 (`ushr` by contract for u64) are correct and non-negotiable given the forthcoming iOS targets — any JVM-only primitive in commonMain is a latent build break. The `LongPollCursorRepository` Option (b) interface split is the right structural call; the old SAM contract surviving as read-only preserves Stage 2B-A invariants cleanly. The `Auth.authHmacSha256` vs `authHmacSha512256` distinction is a real silent-failure trap and Round 1 is right to name it explicitly.

### Open questions to lock before scope

1. What is the exact derivation of `identity_hex` — which key, whose side, what byte representation — with a pointer to the single shared definition?
2. Is `_seqMacVerifyKey` stored as hex or raw bytes? Document the canonical internal representation.
3. Does `upsertLastSeenSeq` enforce monotonicity inside the SQLCipher transaction, or is that the caller's responsibility?
4. What is the defined interaction event (name and direction) between `LongPollBreakerState` open signal and `RestStateMachine`?

---

## Round 2 — Test matrix

### New blockers (P1)

**B7 — No golden vector contains a multi-byte UTF-8 `envelope_id`.** The implementation review identified that `envelope_id_len` must use `encodeToByteArray().size`, not `String.length`. M8 mirrors the 13 server vectors, but if every fixture in `seq_mac_vectors.rs` uses ASCII-only envelope IDs (the common case for UUID-shaped identifiers), the UTF-8 length trap is never exercised by the golden suite. M10's "wrong envelope_id with UTF-8 collision" sub-cell is a tamper-negative test, not a positive encoding-correctness test. At least one golden vector — agreed between server and client fixture owners — must carry a multi-byte UTF-8 `envelope_id` so that char-count vs. byte-count divergence produces a detectable MAC mismatch in CI.

**B8 — No test for `identity_hex` derivation correctness.** M10's single-bit-flip sub-cell proves the verifier rejects a wrong value, but nothing proves the client derives `identity_hex` correctly from session/identity material in the first place. If the derivation is consistently wrong (wrong key, uppercase hex, hashed form), M10 passes while every production verification fails against server-generated MACs. A round-trip cell is required: server generates a MAC using the agreed identity encoding, client verifies it using its own derivation path. This is distinct from golden vector parity and cannot be collapsed into M8.

**B9 — No test for key-decoded-before-use correctness.** The implementation review flagged that `_seqMacVerifyKey` as a hex string must be decoded to raw bytes before passing to `Auth.authHmacSha256`. If the key is passed as its ASCII hex bytes, all verifications produce wrong results that are internally consistent — the verifier agrees with itself but not with the server. No proposed cell exercises this: M8 golden vectors only catch key-encoding errors if the test fixture applies the same (possibly wrong) decoding as production code. An explicit cell must construct a known-key MAC with `Auth.authHmacSha256(rawKeyBytes, message)` and assert failure when the same key is passed hex-encoded, proving the production path decodes.

**B10 — Coroutine cancellation mid-emit is untested.** P1-3 requires verify-then-emit in a single suspension point with no interleaving `launch`. No proposed cell exercises cancellation arriving between `emit` return and the storage-acceptance callback, or `emit` suspending on a full buffer. M11 tests outcome correctness under normal completion; a cancellation-safety cell — inject cancellation signal after verify passes but before write commits, assert cursor has NOT advanced — is required to close the structured-concurrency risk.

### Concerns (P2) and Round-1 contradictions

**M14(d) backoff policy is contradictory with locked security behaviour.** The security review locked "≥5s floor between consecutive 410 reauths." M14(d) specifies "exponential backoff." These are different policies. The test cell cannot be written deterministically until one definition is chosen. A capped exponential with a 5s minimum floor is the likely intent, but it must be stated explicitly — the test assertion depends on knowing the exact formula.

**S6 marked optional conflicts with B4 being a P1 blocker.** Round 1 elevated cursor preservation across breaker transitions to P1 (B4). The smoke test that most directly exercises this in a realistic transport condition — S6, breaker open under byte-budget pressure — is labelled "optional/high-value." This is inconsistent: either B4 is P1 and S6 is required, or B4 is demoted. Recommend removing the "optional" qualifier from S6.

**M13 has no sub-cell for stop-during-open.** The implementation review flagged that the breaker timer Job must be tracked and cancelled by `stop()`, and that implicit half-open via delay creates a cancel-path gap. M13's five sub-cells cover normal state transitions but not the stop+restart cycle while the breaker is open. Without this cell, the breaker state after a `stop()` during open is unspecified and untested.

**M15 grep-pin scope is too narrow.** Grepping for `Random.Default count=0` misses `java.util.Random` on JVM targets and any import alias. The behavioural pin (recording Csprng fake sees every draw) must be the primary assertion; the grep acts only as a secondary line-count guard. The grep pattern must also cover `java.util.Random` and `SecureRandom` misuse as non-CSPRNG jitter sources.

**Key-cleared-on-failed-refresh has no test cell.** The security review identified that resetting `_seqMacVerifyKey = ""` on the failed-refresh path must produce suspended ingestion, not legacy unverified pass-through. No proposed M-cell covers this path. A cell is needed: trigger failed refresh, assert subsequent envelopes are dropped with `reason=no_verify_key` and cursor does not advance, assert normal operation resumes after key is re-established.

### Round-1 claims that hold up

B1 (golden vector parity) and B3 (verify-fail must not advance cursor, M11) are correctly identified as P1 and the proposed sub-cell structure for M11 is sound — particularly splitting `verify∧storage-error` from `verify∧dedup` at the cursor-advance outcome. B6's M14 five sub-cell structure correctly decomposes the 410 reauth contract. B5's M15 dual-pin approach (grep + behavioural recording fake) is the right defence-in-depth pattern for jitter-source discipline, subject to the scope correction above.

### Open questions to lock before scope

1. Do any of the 13 server golden vectors in `seq_mac_vectors.rs` use a non-ASCII `envelope_id`? If not, who adds one, and is the JSON export path (`--features test-vectors-export`) a stable CI artifact or aspirational?
2. What is the exact consecutive-410 backoff formula — fixed floor, capped exponential, or other — so M14(d) can have a deterministic assertion?
3. Is there a defined owner for the `identity_hex` round-trip integration test (B8), given it requires both server-side MAC generation and client-side verification in the same test run?
4. What is the observable signal for "suspended ingestion" after key-cleared on failed-refresh — a specific log event, a state enum value, or only the absence of `_inbound.emit`? The M-cell assertion depends on this being inspectable.
