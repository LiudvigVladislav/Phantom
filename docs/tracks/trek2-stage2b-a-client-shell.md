# Trek 2 Stage 2B-A — Client Long-Poll Shell (Scope Mini-Lock)

**Status:** Locked scope mini-lock for Stage 2B-A. No code written. This document is the binding contract for the implementation PR that follows it.

**Why this PR exists.** Trek 2 Stage 1.x (PR #303, master `e3794e6e`) shipped the server-side contract for the long-poll backbone: `seq_mac` integrity tag, atomic token rotation, decoupled `X-Phantom-Long-Poll` / `X-Phantom-Padded-Poll` headers, hold cap. Stage 2B-A is the **client-side shell** that begins consuming that contract — headers, timeouts, parallel poll job lifecycle — but does NOT begin consuming any of its semantic guarantees yet. The hard boundary between "shell" and "semantics" is what keeps this PR low-risk: a release-mode APK with the kill switch off must be byte-for-byte equivalent in observable runtime behaviour to today's master.

**What lands after this PR.** Stage 2B-B picks up the semantic side: `seq_mac` verification, `since_seq` advancement under MAC verify, circuit breaker, re-auth on 410 Gone, and the Tele2 LTE field-gate. Stage 2B-C is documentation cleanup. Stage 2B-B will need the WORKING_RULES rule 8 Tele2 LTE smoke gate; 2B-A does not (justified below).

## Strategic frame

This stack exists because of the order locked in the master delivery framing: backbone first, then Direct WSS hardening, then Reality Android repair, then Tor / Ghost polish. The backbone — long-poll-as-reliability-layer — is what makes the messenger un-killable at the product level even when WS or Reality misbehaves. Once delivery is guaranteed by the backbone, the Direct WSS fast path can be tuned without putting the product at risk. Stage 2B-A is the first half of installing that backbone on the client.

## Locked server contract surface (verified against master `e3794e6e`)

These are the 7 server-side facts the client types itself against. Each one was verified by direct grep on master before this document was written. The implementation PR must NOT widen, narrow, or reinterpret any of them. If a future server change drifts any of these, this scope is invalidated and must be re-locked.

1. **`X-Phantom-Long-Poll`** header. Constant `"x-phantom-long-poll"` (`rest_fallback.rs:169`). Strict `v == "1"` opt-in (`rest_fallback.rs:1964-1968`). Absence or any other value → server treats request as legacy short-poll.
2. **`X-Phantom-Padded-Poll`** header. Constant `"x-phantom-padded-poll"` (`rest_fallback.rs:192`). Strict `v == "1"` opt-in (`rest_fallback.rs:1972-1976`). Same equality contract as LP.
3. **`SessionResponse.poll_hold_secs: u32`** field. Always present on the wire (no `skip_serializing_if`). `0` means the server kill switch is active; client must short-poll regardless of headers. `>0` is the server's advertised hold ceiling. (`rest_fallback.rs:822-838`.)
4. **`SessionResponse.seq_mac_verify_key: String`** field. Always present, 64-char lowercase hex (32-byte HMAC-SHA-256 output), derived per-identity from the relay-side root key. (`rest_fallback.rs:839-853`.) Stage 2B-A persists this value but does NOT use it for verification.
5. **Post-rotation challenge replay → `410 Gone`**. Body is exactly `{"error":"session token rotated; obtain a fresh challenge"}`. (`rest_fallback.rs:1381-1387`.) Stage 2B-A does NOT take action on this status; the re-auth flow lands in Stage 2B-B. The shell parses the body for log-line completeness only.
6. **`padded_opt_in = long_poll_opt_in || padded_poll_opt_in`**. Server pads the response when EITHER header is present. (`rest_fallback.rs:1982`.) Sending PP alone gives a padded short-poll; sending LP alone gives a padded hold; sending both gives a padded hold; sending neither gives a legacy small body.
7. **Kill-switch decoupling.** `RELAY_POLL_HOLD_SECS=0` zeros the hold timer for the server-side wait but does NOT strip the padded body shape from PP-opted-in clients. The server line is `effective_hold_secs = if LP { config.poll_hold_secs } else { 0 }`; the padded shape gate is independently `padded_opt_in`. (`rest_fallback.rs:1983-1987`, `:2044`.) Stage 2B-A relies on this decoupling so that the eventual circuit-breaker fallback in 2B-B can keep the canonical wire footprint without forcing a server-side hold.

A consequence worth surfacing: under server kill switch (`RELAY_POLL_HOLD_SECS=0`), a Stage 2B-A client that opts into both LP and PP receives the legacy short-poll cadence with the padded body shape. The server does not raise an error, does not strip the headers; it simply skips the wait. This is the safe default behaviour Stage 2B-A must produce on the client side as well — see L2 below.

## Stage 2B-A client locks

Seven locks. Each one is binding for the implementation PR. Any departure from this list requires explicit re-lock before the PR opens.

### L1 — Header gating

The implementation MUST send `X-Phantom-Long-Poll: 1` and `X-Phantom-Padded-Poll: 1` if and only if the BuildConfig flag `LONGPOLL_V2_ENABLED == "1"`. When the flag is `"0"` (release default), neither header is emitted on any `/relay/poll` request. The flag is the single point of control for the entire shell — header emission, timeout gating, parallel job lifecycle. Strict string equality on `"1"` mirrors the server's strict equality on header values.

Both headers are emitted together. There is no Stage 2B-A configuration in which the client sends only LP or only PP. The server accepts the LP-alone and PP-alone shapes for future flexibility; the client does not exercise them in this PR.

### L2 — Read-timeout gating

The client read-timeout for `/relay/poll` is raised above the legacy default if and only if BOTH conditions hold:

- `LONGPOLL_V2_ENABLED == "1"`, AND
- the parsed `SessionResponse.poll_hold_secs` is in the inclusive range `1..480`.

If either condition fails — flag off, or server kill switch (`poll_hold_secs == 0`), or server outside the locked range — the client uses the legacy short-poll timeout. The raised timeout target is `pollHoldSecs + safety_margin` where `safety_margin` is small (a few seconds) to absorb TCP / TLS round-trip variance without ballooning the hung-request budget on Tele2 LTE-grade radios. The exact margin number is the implementation PR's call within `[2, 8]` seconds; outside that band requires a re-lock.

The `480` upper bound mirrors the server's `MAX_POLL_HOLD_SECS_CAP` (`rest_fallback.rs`). A server advertising `poll_hold_secs > 480` is out-of-spec and the client falls back to legacy timeout rather than honouring the advertised value.

### L3 — Parallel REST poll job

`wsActivePollJob` is allowed to run in parallel with WS. Lifecycle is tied to the transport/session owner and the feature flag, not to a WS-down-only fallback. It MUST NOT disable or replace the Direct WSS fast path.

Concretely:

- The job is owned by the session / transport scope that today owns the legacy poll job. It starts and stops with the session, not with the WS connection's up/down state.
- When WS is up, the parallel job continues to issue `/relay/poll` requests at its own cadence. Both transports are live simultaneously. Dedup at the storage layer is what keeps the message table consistent — the same `envelope_id` arriving on both paths is dropped on the second insert as it is today.
- When WS is down, the parallel job continues unchanged. The shell does not switch modes on WS state; that decision belongs to Stage 2B-B's circuit breaker.
- The job is gated on `LONGPOLL_V2_ENABLED == "1"`. With the flag off, the parallel job is not spawned and the legacy poll job runs as today.

This parallel posture is the load-bearing reason Stage 2B-A is safe to ship with the kill switch on a release APK: even when the flag is flipped, the legacy WS fast path is not displaced — the long-poll job is an additional reliability source, not a fallback.

### L4 — Cursor frozen

`since_seq` is NOT advanced in Stage 2B-A. The single point of truth is the `LastSeenSeqRepository` shipped in Stage 2A (master `30dc16f5`). Stage 2B-A reads from that repository when issuing `/relay/poll?since_seq=<n>` requests and writes nothing back to it. The cursor advances only when Stage 2B-B lands — at that point advancement is gated on `seq_mac` verifying AND the envelope being accepted or deduplicated by the storage layer.

A failure mode this lock prevents: a flag-on client running 2B-A with a future bug that advances the cursor on raw envelope receipt would silently lose messages whose MAC the eventual 2B-B path would have rejected. Holding the cursor still in 2B-A makes that class of bug structurally impossible.

### L5 — MAC unverified, presence parsing only

`seq_mac` may be parsed for DTO and wire-stability reasons — the field is on the server's `PollEnvelope` and a Kotlin DTO that omits it would drift from the locked server contract. Parsing is presence-only: the field is read into the DTO, never inspected, never verified. The `SessionResponse.seq_mac_verify_key` is persisted to the session-scoped key store so that Stage 2B-B can pick it up without a session-rotation handshake, but the key is not used in 2B-A.

Client MUST NOT advance `since_seq` based on unverified `seq_mac` in 2B-A. No `seq_mac` verification in 2B-A.

The sealed envelope continues to flow through the existing legacy decrypt path. The presence of an unverified `seq_mac` on the wire envelope does NOT bypass, gate, or modify the decrypt pipeline. From the recipient's perspective, an envelope received on a 2B-A client behaves identically to an envelope received on a master-tip client today.

### L6 — Release pin

The `LONGPOLL_V2_ENABLED` BuildConfig field is pinned to `"0"` in the release variant. A contract test in the implementation PR loads the release APK config and asserts the literal `"0"` value. The debug variant may carry `"1"` so the parallel job and timeout-raise paths exercise on developer devices and emulators.

This pin is the safety contract with the operator: a Stage 2B-A merge changes nothing observable on a shipped Alpha APK until the operator flips the build flag in a future release.

### L7 — Out of scope

The following are explicitly NOT in Stage 2B-A and any code that touches them is a scope violation:

- `seq_mac` verification (Stage 2B-B).
- `since_seq` advancement (Stage 2B-B).
- Circuit breaker logic between WS and REST poll transports (Stage 2B-B).
- Re-authentication on `410 Gone` (Stage 2B-B).
- Voice / media padding (separate track).
- Tor-mode poll behaviour (Ghost / Tor polish track, post-Direct hardening).
- Ghost-mode hold-jitter (same track).
- Any modification of the existing legacy poll job behaviour beyond the parallel-job lifecycle.

## Test matrix

The implementation PR ships these contract tests. Each one is a guardrail against the failure mode named after it.

**M1 — Header emission contract.** A unit test on the HTTP client interceptor (or equivalent layer) asserts that with `LONGPOLL_V2_ENABLED == "1"` the outgoing `/relay/poll` request carries exactly two new headers, `X-Phantom-Long-Poll: 1` and `X-Phantom-Padded-Poll: 1`, and with `LONGPOLL_V2_ENABLED == "0"` neither header is present.

**M2 — Timeout-gate matrix.** A parameterised test over the cross product:

| `LONGPOLL_V2_ENABLED` | `pollHoldSecs` | Expected read-timeout |
|:---:|:---:|:---|
| `"0"` | `0` | legacy |
| `"0"` | `30` | legacy |
| `"0"` | `null` / unset | legacy |
| `"1"` | `0` | legacy (server kill switch) |
| `"1"` | `1` | raised to `1 + safety_margin` |
| `"1"` | `30` | raised to `30 + safety_margin` |
| `"1"` | `480` | raised to `480 + safety_margin` |
| `"1"` | `481` | legacy (out of spec) |
| `"1"` | `null` / unset | legacy |

**M3 — Kill-switch contract.** With `LONGPOLL_V2_ENABLED == "0"`, the parallel `wsActivePollJob` is not spawned. Observed by the absence of the job in the session's tracked jobs map (or equivalent scope-internal mechanism), not by a sleep-and-check timer.

**M4 — Release pin BuildConfig contract.** Loads the release variant's `BuildConfig` and asserts `LONGPOLL_V2_ENABLED == "0"` as a literal string match.

**M5 — Cursor frozen.** A unit test on the cursor write path: with the parallel job receiving an envelope and processing it through the shell, `LastSeenSeqRepository` is read but never written. Pins L4 structurally so a future regression that adds a write call is caught at PR-test time.

**M6 — `seq_mac` presence parsing without verification.** A unit test on the DTO parser: a `PollEnvelope` wire shape with a 64-char `seq_mac` field deserialises cleanly and the field is accessible on the DTO; no verify-key invocation occurs on the parse path. Pins L5.

**M7 — Parallel job continues when WS is up.** An integration-style test (within the existing test harness) that brings up a WS-stub and asserts the parallel `/relay/poll` job continues issuing requests. Pins L3 against a future refactor that accidentally turns the shell into a WS-down-only fallback.

## PR-commit boundary

Three commits, one per logical seam:

**B1 — Headers + flag.** Introduces `LONGPOLL_V2_ENABLED` BuildConfig (debug `"1"`, release `"0"`), wires the two opt-in headers into the `/relay/poll` request path under the flag, ships M1 + M4. Build does not yet change timeout or job lifecycle.

**B2 — Timeout gate.** Adds the `LONGPOLL_V2_ENABLED == "1" && pollHoldSecs in 1..480` check at the OkHttp / Ktor client construction point and routes the raised timeout there, ships M2.

**B3 — Parallel `wsActivePollJob` lifecycle.** Introduces the parallel job under session / transport scope ownership per L3, ships M3 + M5 + M6 + M7. Includes the DTO change that adds `seq_mac` and `seq_mac_verify_key` field presence per L5.

Each commit must independently build green and run its own tests. The bundle is mergeable as one PR because the seams are tightly coupled — splitting into three PRs would create intermediate states where the flag and the job are out of step.

## WORKING_RULES rule 8 carve-out

Stage 2B-A is a shell, runtime-behaviour-equivalent at `LONGPOLL_V2_ENABLED == "0"` to today's master. The release variant is pinned at `"0"` (L6) and a contract test guards the pin (M4). No production code path changes for a shipped Alpha APK on this PR's merge. The Tele2 LTE smoke gate from WORKING_RULES rule 8 is carved out on the same justification used for Stage 1.x server-only PRs: the relevant runtime behaviour is not exercised on real hardware until the flag flips, and the flag flip belongs to a later, gated decision.

Stage 2B-B WILL exercise the runtime path on real hardware. The Tele2 LTE smoke requirement transfers to that PR and is not waivable there.

## Backward compatibility

- **Server-side.** Zero impact. The server already accepts the two new headers and the strict `"1"` equality contract since Stage 1.x merge. A 2B-A client with the flag off is byte-identical on the wire to a master-tip client today.
- **Existing legacy poll job.** Continues to run unchanged. The parallel job is additive; it does not modify, replace, or steal work from the legacy job. With the flag off, the parallel job is never spawned and master-tip behaviour is preserved exactly.
- **`seq_mac` on the wire.** Old clients (pre-2B-A) already ignore unknown JSON fields via the Kotlin serializer's `ignoreUnknownKeys` setting. The 2B-A DTO addition of the field changes nothing for existing message decoding.
- **`since_seq`.** Cursor is read-only in 2B-A (L4). No write path means no cursor regression risk.
- **Re-authentication on `410 Gone`.** Out of scope. A 2B-A client that gets `410 Gone` from `/relay/poll` after a token rotation logs the event and falls through to the legacy error-handling path, which today retries on a fresh session as the WS reconnect path does. This is unchanged from master and is NOT a Stage 2B-A code path — the polish lands in 2B-B.

## What this scope does NOT do

- Does not begin Stage 2B-B implementation. 2B-B is paused behind this PR.
- Does not verify `seq_mac` on any received envelope.
- Does not advance `since_seq`.
- Does not introduce a circuit breaker between WS and REST poll.
- Does not change re-authentication behaviour.
- Does not add voice / media padding.
- Does not touch Tor-mode poll behaviour.
- Does not touch Ghost-mode hold-jitter.
- Does not modify the legacy poll job's cadence, timeout, or lifecycle.
- Does not flip `LONGPOLL_V2_ENABLED` to `"1"` in the release variant.

## After this PR

Stage 2B-B picks up the semantic side: `seq_mac` verify against `SessionResponse.seq_mac_verify_key`, `since_seq` advancement gated on MAC verify AND storage accept-or-dedup, circuit breaker between WS and REST poll, and `410 Gone` → re-auth. Tele2 LTE smoke is mandatory on Stage 2B-B per WORKING_RULES rule 8. Stage 2B-C is documentation cleanup.
