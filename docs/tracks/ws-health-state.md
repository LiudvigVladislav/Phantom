# PR-WS-HEALTH-STATE1 — mini-lock

**Status:** Draft. Awaiting Vladislav explicit ACK on §1, §2, §3 before code work begins. Three-section opening procedure per `feedback_session_close_discipline.md` + 2026-05-30 lock.

**Motivating event:** Test #83 v3 (2026-05-30) PASSED Phase 1 crypto gate + Phase 2 scenarios 1-4, but BLOCKED on scenario 5 (burst incoming, ~20 messages). Tecno received 7 of ~20 and then transport collapsed for >2 minutes.

**Empirical base files (Vladislav's Windows PC):**

- `C:\temp\test83-v3-tecno.log` (208,198 bytes)
- `C:\temp\test83-v3-emu.log` (384,648 bytes)

**Scope note:** This track is **READ-ONLY diagnosis** until Vladislav explicit ACK. No code, no APK, no force-push. The CHIP1 branch at `origin/feat/pr-ui-chat-new-msg-chip1` head `78bd979e` stays parked.

---

## §1 — Доказанные факты (proven facts)

Each fact is grep-verifiable in the named log file at the cited line(s). No inference, no synthesis. If you cannot reproduce the grep, the fact is wrong and the mini-lock must be revised before merge.

### F1. Three sequential WS sessions on Tecno during the test

`gen=1 s=1`, `gen=1 s=2`, `gen=1 s=3`. Each terminated by an OkHttp ping timeout. Reconnect was fast (<2 s gap between sessions).

- `test83-v3-tecno.log:58` — s=1 attempt 0.
- `test83-v3-tecno.log:60` — s=1 `WebSocket connected successfully`.
- `test83-v3-tecno.log:257` — s=1 `WebSocket connect FAILED ... sent ping but didn't receive pong within 15000ms (after 3 successful ping/pongs)`.
- `test83-v3-tecno.log:272` — s=2 `Attempting WebSocket connect`. Gap from line :269 ≈ 617 ms.
- `test83-v3-tecno.log:274` — s=2 connected.
- `test83-v3-tecno.log:540` — s=2 session_summary (after 4 successful ping/pongs).
- `test83-v3-tecno.log:883` — s=3 `WebSocket connect FAILED ... (after 7 successful ping/pongs)`.

### F2. session_summary ping counters report 0 while the close exception reports N>0

For ALL three sessions, `pings_sent=0 pongs_received=0` is logged in the session_summary, while the same session's thrown exception reports "after N successful ping/pongs" with N ∈ {3, 4, 7}. The two telemetry streams contradict each other.

- `test83-v3-tecno.log:269` — `[gen=1 s=1] session_summary ... thrown='...after 3 successful ping/pongs' pings_sent=0 pongs_received=0 missed_pongs=0 ping_send_failures=0 inbound_frames=4 delivers_received=4 acks_received=0 since_last_ping_ms=-1 since_last_pong_ms=-1 since_last_inbound_ms=31144`.
- `test83-v3-tecno.log:540` — s=2 analogous, N=4, since_last_inbound_ms=51827.
- `test83-v3-tecno.log:894` — s=3 analogous, N=7, since_last_inbound_ms=29308.

### F3. idle_watchdog explicitly suppresses reconnect even at sinceLastPong > 2 min

The `idle_watchdog` code path emits `no reconnect action (R0.4b)` four times during the test, including once with `sinceLastPong=120028ms`. This is the **intentional design** from PR-R0.4b (#151, squash `727e1a83`, 2026-05-16) — see `docs/PROJECT_LOG.md:1959`. The watchdog is passive-log-only by design.

- `test83-v3-tecno.log:240` — s=1 sinceLastPong=60019ms, pendingAcks=3.
- `test83-v3-tecno.log:499` — s=2 sinceLastPong=60013ms, pendingAcks=0.
- `test83-v3-tecno.log:549` — s=3 sinceLastPong=60013ms.
- `test83-v3-tecno.log:782` — s=3 sinceLastPong=**120028ms**, pendingAcks=0.

Source for the log line:
- `shared/core/transport/.../KtorRelayTransport.kt:1008-1011` — emits the exact `no reconnect action (R0.4b)` string.
- `shared/core/transport/.../RelayTransportConfig.kt:160` — comment confirming "PR-R0.4b — no forceReconnect from the idle watchdog".

### F4. Relay-side outbound acks expired during s=1 burst window

Relay sent 4 envelopes to Tecno via WS during s=1 (lines :180, :193, :206, :219), all four `outbound_ack_deadline_armed timeoutMs=10000`. All four expired 10 s later with `outbound_ack_deadline_expired ageMs=10003-10004` (lines :222, :232, :239, :241). Tecno's WS was NOT delivering acks back to relay, even though the WS connection was technically open.

### F5. State-machine transition `candidate_session_regression` is correct per code

On s=3 collapse the state machine emitted `mode_switched from=WsCandidate to=REST_ACTIVE reason=candidate_session_regression`. This matches `RestStateMachine.kt:131` which calls `transitionToRest("candidate_session_regression")` when a WS session closes while still in `WsCandidate` mode (not yet promoted to `WsActive`). Not a bug — correct behaviour.

- `test83-v3-tecno.log:896` — the mode_switched log.
- `shared/core/transport/.../RestStateMachine.kt:127-131` — the source.

### F6. REST `/send` retried 3 times, each timed out at ~60 s with `InterruptedIOException`

After the s=3 → RestActive switch, the queued send `id=3c496e93` retried 3 times. Each retry took ~60 seconds before failing.

- `test83-v3-tecno.log:880` — `REST_TRACE send_start id=3c496e93 bodyBytes=1624 attempt=1/5`.
- `test83-v3-tecno.log:899` — `REST_TRACE send_retry id=3c496e93 reason=InterruptedIOException attempt=1 next_delay_ms=1000 elapsedMs=60016`.
- `test83-v3-tecno.log:907` — `send_retry ... attempt=2 elapsedMs=60020`.
- `test83-v3-tecno.log:909` — `send_start attempt=3/5`.

### F7. REST `/poll` failed twice with `InterruptedIOException elapsedMs≈60017`

Same window. Long-poll requests hung the full read-timeout budget.

- `test83-v3-tecno.log:900` — `REST_TRACE poll_fail reason=InterruptedIOException elapsedMs=60017 next_delay_ms=5000`.
- `test83-v3-tecno.log:910` — analogous, elapsedMs=60017.

### F8. REST `/auth/challenge` failed twice with `SocketTimeoutException`

REST also tried to re-establish auth and hit the same wall.

- `test83-v3-tecno.log:905` — `Auth handshake failed (attempt=1): SocketTimeoutException Socket timeout has expired [url=https://relay.phntm.pro/auth/challenge?...]`.
- `test83-v3-tecno.log:913` — `Auth handshake failed (attempt=2): SocketTimeoutException`.

### F9. OkHttp client REST timeouts are intentionally generous (60 s read/write, 120 s call)

`RelayTransportFactory.kt:221-224` for the direct REST path:

```
.connectTimeout(15, TimeUnit.SECONDS)
.writeTimeout(60, TimeUnit.SECONDS)
.readTimeout(60, TimeUnit.SECONDS)
.callTimeout(120, TimeUnit.SECONDS)
```

Comment context at `:208-:210` explicitly notes these are "generous budgets" for chunked media upload. They are NOT tuned for burst short-message delivery.

### F10. Sender (emu) side was healthy throughout the burst

- Zero `session_summary` lines in `test83-v3-emu.log` — emu's WS did not die during the test.
- `SEND_TRACE relay_send_call` count = 41 outbound attempts (grep count).
- `REST_TRACE send_response` count = 36 with `status=201` (relay accepted). Example wins during the same minute Tecno was failing: `test83-v3-emu.log:492 status=201 elapsedMs=1091`, `:537 status=201 elapsedMs=864`, `:581 status=201 elapsedMs=940`, `:604 status=201 elapsedMs=1061`.

The asymmetry matters: the relay accepted what emu sent during the same minute it could not deliver to Tecno. The blocker is **Tecno's receive + recovery path**, not the relay or sender.

### F11. Pre-collapse Tecno CHIP and crypto were healthy

CHIP1 logs fired correctly through scenario 4 and started scenario 5 normally (count incremented 1→2→3→4→5→6→7 on `test83-v3-tecno.log:600/:646/:690/:727/:777/:815/:858`). PR #249 inbound X3DH repair fired correctly at `:72-:76`. No app-layer regression preceded the transport collapse.

---

## §2 — Гипотезы (hypotheses)

Each hypothesis is labeled with the test that would falsify it. Lock no root cause until at least one hypothesis survives its falsification test. Multiple may turn out to be partially true.

### H1 — OkHttp WS Ping scheduler stopped firing pre-collapse

> Status: **FALSIFIED by F2**.
> 
> The exception text in F2 ("after N successful ping/pongs") is from OkHttp's internal scheduler; it is the ground-truth source for whether pings happened. N ∈ {3, 4, 7} > 0 in all three sessions. Pings DID fire. This hypothesis is closed.

### H2 — `session_summary` ping counters are wired wrong, not the transport

> Status: **PARTIALLY CONFIRMED** by F2's contradiction with the exception message.
> 
> Read of `KtorRelayTransport.kt` close-handler shows the counters increment only on app-level events, not on OkHttp's `WebSocketListener.onPing/onPong` (which OkHttp's internal scheduler bypasses anyway for protocol-level ping/pong).
> 
> Falsification test: in a unit test, simulate an OkHttp WS Ping cycle and assert `session.pingsSent` increments. If the counter increments — wiring works, bug elsewhere. If it does not — the wiring needs to be fixed regardless of any other change, because it makes ALL future transport diagnostics misleading.
> 
> Severity: diagnostic only. Does NOT cause user-visible failure. But fixes confidence in future log-based root-cause analyses.

### H3 — Network packet loss on the Tecno path during burst

> Falsification test: pull Caddy and relay container logs from the VPS for the window `01:18:30-01:20:40 UTC` (Vladislav-delegated via `feedback_ssh_delegation`). If relay's view shows it sent envelopes successfully to Tecno's WS socket and got socket-write OK during the window where Tecno reports `InterruptedIOException`, the loss is at network level (Tecno's local network, ISP, or Hetzner uplink dropped packets).
> 
> Confirming signal would also include: `outbound_ack_deadline_expired` on relay side for the same window (relay sent, never got ack back). F4 already shows this pattern for s=1.
> 
> If confirmed: the fix is NOT in our code — it is in chain selection. Burst should fall through to Tor / next chain link sooner.

### H4 — OkHttp `Dispatcher` / `ConnectionPool` saturation on Tecno after WS death

> Falsification test: enable OkHttp `Dispatcher.executorService` queue-depth metrics + `ConnectionPool.connectionCount()` snapshots during a controlled burst. If queue depth > 0 during the 60 s timeouts in F6/F7/F8, the pool is full.
> 
> If confirmed: the WS death released N concurrent requests back into a saturated pool; new REST calls were queued behind them; each queued call inherited an aged callTimeout and effectively had less time to succeed.
> 
> The fix would be a separate OkHttpClient instance for REST fallback (small dedicated pool, no shared state with the dying WS path).

### H5 — REST timeouts (60 s read / 60 s write / 120 s call) are too generous for burst delivery

> Falsification test: temporarily set REST short-message send/poll/auth timeouts to 10 s and rerun Test #83 v3 scenario 5. If reliability drops, the 60 s budgets were load-bearing. If user-visible recovery is faster but reliability holds, the budgets were over-provisioned for this path.
> 
> Note: F9 comment confirms the 60 s budget is tuned for chunked media upload. PR-M2 (media) and PR-WS-HEALTH-STATE1 (short messages) have different latency budgets; mixing them in one client is suspicious.

### H6 — REST `/auth/challenge` race with WS-to-REST mode switch (token state)

> Falsification test: log auth-token state at every state transition (`token_state_pre_transition` + `token_state_post_transition` with `expires_in_ms`, `acquired_at_ms`, `reuse_counter`). If `/auth/challenge` was called while a refresh was in flight from the WS-side path, the race is the cause.
> 
> Less likely than H3/H4/H5 because F8 shows two consecutive auth challenges both hitting `SocketTimeoutException`, not a 401/403. The socket-level failure is more consistent with network/pool issues than auth race.

### H7 — Relay-side inbound-flow back-pressure during burst

> Falsification test: relay logs for the s=3 window. Did relay attempt to deliver all ~20 burst envelopes to Tecno's WS during s=3, or did it queue them and try to drain in batches? If relay queue depth was rising while Tecno's WS was alive but silent (no acks coming back per F4), the relay's outbound flow control could be at fault — too many envelopes inflight on a stalled socket trigger back-pressure that kills throughput.
> 
> This would also explain why the s=3 session ran 136 seconds (much longer than s=1's 76 s or s=2's 91 s) — relay was still trying to push envelopes when the OkHttp ping timeout finally fired.

### H8 — Tecno-specific network condition (cellular policy, NAT, DPI)

> Falsification test: rerun Test #83 v3 burst on the emulator only (emu sends to itself across two instances) OR on a second Wi-Fi router (Vladislav can rotate to a tested router). If the collapse does not reproduce, it is Tecno-specific. If it reproduces on a second device or network, it is code-level.
> 
> Vladislav 2026-05-30 noted Test #83 v3 ran on a "home Hetzner-tested Wi-Fi" not the Mac-side alt-network from v2 — so this is NOT the v2's TLS-DPI stall recurring. But Tecno hardware-specific stack behaviour cannot be ruled out without a second-device test.

---

## §3 — Invariants после фикса (post-fix invariants)

These are the merge gates. Regardless of which hypothesis turns out to be the root cause, all of these MUST be true after PR-WS-HEALTH-STATE1 ships. Without them, no code lands.

### Inv1 — Diagnostic counters MUST be honest

If `session_summary` reports `pings_sent=0` AND the same session's close exception (or any other source) reports a positive ping count, the discrepancy must be impossible. Either both are zero, or both reflect the true count.

Verification: a unit test in `KtorRelayTransportTest` that drives a fake WS session through a ping cycle and asserts counter parity with the synthetic "successful ping/pongs" number.

Severity: **non-negotiable for diagnostic clarity**. Cheapest invariant in the set.

### Inv2 — WS death during burst MUST yield REST send success or fail-fast within 10 s

When WS in `WsActive` or `WsCandidate` dies and the state machine transitions to `RestActive`, the next REST `/send` call MUST either (a) return 2xx within 5 s OR (b) fail with a transport error within 10 s that triggers the next chain link (Tor / direct retry / queue-until-reconnect).

The current 60 s read timeout violates this — it gives the user a full minute of silent wait per send retry. F6's `elapsedMs=60016` is the worst case.

Verification: integration test that simulates WS death during a 20-message burst and asserts that within 30 s of the WS close, either ≥ 80% of the burst is delivered via REST OR the chain falls through.

### Inv3 — REST `/auth/challenge` N consecutive failures within 30 s MUST surface to the user OR fall through

If REST `/auth/challenge` returns `SocketTimeoutException` or any transport error N times consecutively (N = 2 per F8's observed pattern) within a 30 s window, the orchestration layer MUST either (a) trigger a connectivity-pulse event that the UI can surface as "reconnecting" OR (b) fall through to the next chain link.

Silent retry forever (current behaviour beyond the test window) is unacceptable — the user sits in a transport black hole with no signal.

Verification: contract test on `RestFallbackOrchestrator` (or equivalent) that drives N consecutive auth failures and asserts the appropriate event is emitted to consumers.

### Inv4 — WS reconnect fast path stays intact

After the fix, WS reconnect after a clean OkHttp ping-timeout close MUST still succeed in < 2 s on a healthy network (F1 baseline: 617 ms s=1→s=2, ~1 s s=2→s=3).

The fix MUST NOT regress this. Any change to `KtorRelayTransport` close-handler or reconnect loop must include a regression test for reconnect latency.

### Inv5 — R0.4b "no reconnect action" path remains unchanged

The `idle_watchdog` MUST remain passive-log-only per PR-R0.4b (#151) Vladislav-locked design. This track MUST NOT reintroduce `forceReconnect` from the watchdog under any hypothesis-derived fix.

Verification: `feedback_ws_heartbeat_diagnostic_2026_05_27.md` already records the v1.3/v1.4 own-goal. The PR diff must show the watchdog log line at `KtorRelayTransport.kt:1008-1011` unchanged AND no new `forceReconnect()` call from any new code path.

### Inv6 — Burst delivery floor on stable network

Define N = TBD (Vladislav-set). A burst of N consecutive incoming messages on a stable home Wi-Fi MUST result in at least M of them being delivered to the receiver UI within 60 s.

Test #83 v3 saw 7 / ~20 = 35%. That is the floor; the fix should raise it substantially. Vladislav to set the post-fix target (e.g. M/N = 95% within 60 s) before code work begins.

Verification: a controlled-burst scenario in the Test #83 acceptance ladder (already drafted in `docs/tracks/chat-new-msg-chip.md`). The PR cannot merge without a PASS reading on this scenario.

### Inv7 — REST `/poll` failure visible to UI

If `RestStateMachine` is in `RestActive` and N consecutive `/poll` calls fail (N = 2 per F7), the consumer MUST receive a connection-state event (e.g. `ConnectionState.Reconnecting`) so the UI can show non-silent feedback.

Currently the UI was just "Online via Direct · Standard" while the user got nothing for 2 minutes. That is misleading.

Verification: contract test on the connection-state flow exposed by the relay transport, driven by N consecutive `/poll` `InterruptedIOException`.

### Inv8 — No reintroduction of `lastWorkingTransport` lock-in from REST fallback

Per `feedback_sticky_fallback_hint_2026_05_27.md`, REST fallback successes during burst MUST NOT save `lastWorkingTransport`. Only `kind == strategy.chain.first()` may. This is a pre-existing locked invariant; this track confirms it stays.

---

## Process gates

- **WORKING_RULES Rule 8** (transport regression gate): **APPLIES.** Code in this track touches `RestStateMachine` / `KtorRelayTransport` / OkHttp client configuration. Must include Tele2 LTE smoke test before merge OR document a justified carve-out (e.g., "Wi-Fi-only reproduces the burst failure so a Wi-Fi rerun is sufficient empirical confirmation; Tele2 path is covered by Test #88 baseline").
- **WORKING_RULES Rule 9** (no merge without verification): applies. Architect explicit ACK on the PR diff OR grep-verified evidence per code-state claim.
- **`feedback_ws_heartbeat_diagnostic_2026_05_27.md`**: **DO NOT** disable WS heartbeat as a diagnostic A/B. Inv5 protects this.
- **`feedback_sticky_fallback_hint_2026_05_27.md`**: Inv8 protects this.
- **`feedback_verify_architect_claims_2026_05_27.md`**: applies in both directions. This mini-lock's own claims (line numbers in F1-F11) must remain grep-verifiable; any future architect input must be grep-verified too.
- **`feedback_session_close_discipline.md`**: mini-lock first, then code. After Vladislav ACK on §1, §2, §3, branch is `feat/pr-ws-health-state1` from master `c291c33f` (or whatever lands first if a docs-only PR moves master between now and code start).

---

## Out of scope (hard)

- ❌ CHIP1 UI (`feat/pr-ui-chat-new-msg-chip1` head `78bd979e` stays parked).
- ❌ Crypto / messaging / DB schema / iOS.
- ❌ Voice / media upload pipelines (separate M2 tracks).
- ❌ Reintroducing `forceReconnect` from `idle_watchdog` (Inv5).
- ❌ Promoting REST fallback success to `lastWorkingTransport` (Inv8).
