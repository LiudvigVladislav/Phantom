# Trek 2 Stage 2B-B — post-d395f682 council deltas

Two binding decisions taken at branch HEAD `d395f682` on 2026-06-13 after a two-layer council (4 Claude Code subagents + 4 Ruflo MCP sonnet cross-check agents) on the S6 incident from the Tele2 LTE smoke captured the same day.

Council log evidence is captured in `docs/tracks/trek2-stage2b-b-tele2-smoke-d395f682-2026-06-13/` (8 .log files: `s1.log`, `s2.log`, `s6.log`, `s6-before.log`, `s6-relay-window.log`, `variant-check.log`, `verify-check.log`, `verify-check-3.log`). The two binding council decisions themselves (A and B below) are the synthesis — there is no separate `synthesis.md` file; the contract this document encodes is the synthesis, and the upstream Layer 1 / Layer 2 review threads were captured ephemerally in the council session.

Both decisions are binding on this branch (`feat/pr-trek2-stage2b-b-impl`, PR #309) and on all subsequent Stage 2B-* PRs and release/operator communications.

---

## Decision A — Deployment rollout gate and Stage 2B-B evidence interpretation

The server-side environment variable `RELAY_POLL_HOLD_SECS > 0` constitutes a separate deployment rollout gate, distinct from the client-side `LONGPOLL_V2_ENABLED` flag promotion in Stage 2B-D.

Until this server-side gate is open in production:

1. Stage 2B-B is NOT counted as an active reliability backbone in production. The client implementation is shipped and inert/observable, but the long-poll recovery path it provides is structurally disabled at the server.
2. The multi-transport rule ("any single transport can die; messages still arrive") is forward-looking under the current deployment shape — it describes the intended end state once the gate opens, not the currently-validated production behavior.
3. Stage 2B-B field evidence captured against an environment where `RELAY_POLL_HOLD_SECS=0` does not validate the long-poll recovery path. Such runs may validate breaker mechanics (open/half-open cycling, cursor preservation, both-loop gating) but do NOT validate recovery.
4. PR descriptions, commit messages, release notes, and operator-facing communications must NOT claim that the REST backbone is protecting delivery in production until BOTH (a) the deployment rollout gate is open AND (b) a re-validation field run with `RELAY_POLL_HOLD_SECS > 0` has produced affirmative recovery evidence (at least one `breaker_closed` + `cursor_advanced` cycle under degraded-WS conditions).

The Tele2 LTE smoke captured at branch HEAD `d395f682` on 2026-06-13 observed the breaker mechanics correctly but did not exercise the recovery path because the kill switch was active throughout the capture window. The log evidence is at `docs/tracks/trek2-stage2b-b-tele2-smoke-d395f682-2026-06-13/`; the binding decisions distilled from the surrounding council are A and B captured in this document.

---

## Decision B — Client contract: REST poll body-timeout-after-headers

When the REST poll path (`/relay/poll`) receives `HTTP 200 OK` response headers from the relay, but the response body does NOT complete reading before the configured OkHttp call/read timeout fires, the client MUST:

1. **Preserve the cursor.** `lastSeenSeq` stays at its current value. No advance. No regression. No partial-state write.
2. **Suppress ack and emit.** Do NOT call `ackInboundAndAdvanceCursor` for any envelope. The poll response is treated as if zero envelopes were delivered, unless the response body has been fully read, parsed, authenticated, and accepted by the normal envelope pipeline.
3. **Account toward the breaker.** The failure is recorded via `recordRestFailure` (or its equivalent post-Round-12 site) and contributes to the L9 `ConsecutiveRestFailures` counter exactly like a transport-class poll failure.
4. **Retry on the next cooldown cycle.** No immediate retry. The standard breaker progression (`Closed → Open → HalfOpen → probe`) handles the back-off.

The current implementation at `RestFallbackOrchestrator.kt` satisfies (1)–(4) as incidental behavior (the field session at `d395f682` confirms cursor preservation across 5 breaker cycles under this exact failure shape). This contract makes the behavior load-bearing rather than emergent, and pins it against future changes to:

- the OkHttp `callTimeout` / `readTimeout` configuration in `AndroidNativeOkHttpRestFallbackTransport.kt`,
- the body-read code path in `RestFallbackOrchestrator.kt`,
- the `HttpPhaseEventListener` instrumentation,
- any future relay-side hold-then-body semantics.

A regression test asserting these four invariants is required before Round 12 lands and before any later code change to the timeout budget or body-read path lands in this orchestrator. The test must drive a MockWebServer that returns 200 headers and then hangs the body until the client times out, and assert all four invariants on the orchestrator state after the timeout fires.

---

## Relationship to Round 12

Round 12 is a diagnostic-only patch and may begin only after both decisions above are landed in the repository. Round 12's scope is captured in the council synthesis at `docs/tracks/trek2-stage2b-b-s6-council-d395f682/synthesis.md` § "Recommended Round 12 scope" and includes, at minimum: `responseBodyStart` / `responseBodyEnd byteCount=N` events in `HttpPhaseEventListener`, a debug-only `ForwardingSource` per-chunk byte logger, `hold_secs=<n>` as a structured field on `poll_call` trace events, and a `POLL_SKIP_LP_AND_PP` BuildConfig flag (debug-only, session-mode=Standard gated, strips BOTH long-poll and padded-poll opt-in headers atomically).

The regression test that pins Decision B's four invariants is a prerequisite for Round 12's first commit.
