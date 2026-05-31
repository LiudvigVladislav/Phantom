# PR-WS-HEALTH-STATE1 — mini-lock

**Status:** Draft, **revision 5** — pre-merge polish per architect green-after-cleanup on rev4 (PR #252 2026-05-30). Rev2 fixed F4/F7/F8/F9/H4/Inv3/Inv6. Rev3 propagated those into H3/H5/H6/H7 and added the four-commit Implementation plan. Rev4 cleaned H4 path wording + extended Commit 1 to cover WS reconnect auth diagnostics. **Rev5** weakens F9's "exact ceilings" claim — the REST fallback OkHttp client numbers (`AndroidNativeOkHttpRestFallbackTransport.kt:188-191`) explain F6/F7 only; the WS reconnect `auth/challenge` 60 s wall in F8 goes through a separate `KtorRelayTransport` client whose effective ceiling Commit 1 must measure before timeout tightening. PR body refreshed to match rev5 via `gh pr edit`. Three-section opening procedure per `feedback_session_close_discipline.md` + 2026-05-30 lock.

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

### F4. Tecno client-side outbound envelopes expired waiting for relay ACK during s=1

**Corrected after architect review 2026-05-30 (P2 on PR #252):** the original wording attributed these envelopes to "relay sent 4 envelopes to Tecno"; that is wrong. The `PhantomRelay` tag in these lines is the **Tecno-side `KtorRelayTransport` client**, not the relay server. The `to=7fbdfbf11d35b462…` address is the recipient (emulator) pubkey, and the `outbound_ack_deadline_*` logs are the **Tecno client** waiting for the relay's ack on outbound envelopes that Tecno emitted. Confirmed by adjacent `SEND_TRACE encrypt_lock_wait` / `session_lookup` on the same conv at `test83-v3-tecno.log:182-:187` — the SEND_TRACE pipeline is what produced these envelopes.

So the corrected fact:

Tecno emitted 4 outbound envelopes during s=1 (lines :180, :193, :206, :219), each `outbound_ack_deadline_armed timeoutMs=10000`. All four expired 10 s later with `outbound_ack_deadline_expired ageMs=10003-10004` (lines :222, :232, :239, :241). The relay never acked them within the deadline. Their identity (likely sealed read receipts from `markConversationRead(...)` in Standard mode per the Test #83 v2 forensic appendix pattern; could also be other outbound sends) is unknown without correlating to relay-side log.

The signal value is preserved: bidirectional traffic was failing — Tecno's outbound was not getting acked AND inbound was sparse — so the WS path between Tecno and relay was sick in both directions, not just receive-stalled.

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

### F7. REST `/poll` failed twice with `InterruptedIOException elapsedMs≈60017` on a short-poll endpoint

**Corrected after architect review 2026-05-30 (P2 on PR #252):** original wording called these "long-poll requests"; that is wrong. The contract on both sides is **short-poll**: client `RestFallbackTransport.kt:81-82` says *"Short-poll only — server returns immediately, empty array if nothing"*, and the relay server at `services/relay/src/rest_fallback.rs:1163-1165` confirms *"Short-poll: returns immediately with an empty array if nothing is queued"*.

So a 60 s `poll_fail` on a short-poll endpoint is **definitively a stuck HTTP call**, not a long-poll waiting for server-side timeout. There is no legitimate interpretation under which the client sits at the server for 60 s.

- `test83-v3-tecno.log:900` — `REST_TRACE poll_fail reason=InterruptedIOException elapsedMs=60017 next_delay_ms=5000`.
- `test83-v3-tecno.log:910` — analogous, elapsedMs=60017.

### F8. WS reconnect auth challenge failed twice with `SocketTimeoutException`

**Corrected after architect review 2026-05-30 (P2 on PR #252):** original wording called this "REST `/auth/challenge`"; that is wrong. The two failed `Auth handshake failed` entries carry the **`PhantomRelay` tag with `[gen=1 s=4]` and `[gen=1 s=5]`** — those are WS session generation/seq numbers belonging to **`KtorRelayTransport`**, not the REST fallback layer. The actual REST auth path emits `REST_TRACE session_challenge_fail` (search the log — that string never appears).

So the corrected fact: **after s=3 collapsed, the WS-reconnect loop tried to re-establish a fresh WS session (s=4, then s=5), and each of those new WS sessions' `auth/challenge` HTTP call timed out with `SocketTimeoutException`**.

- `test83-v3-tecno.log:905` — `[gen=1 s=4] Auth handshake failed (attempt=1): SocketTimeoutException ... [url=https://relay.phntm.pro/auth/challenge?identity=…]`.
- `test83-v3-tecno.log:913` — `[gen=1 s=5] Auth handshake failed (attempt=2): SocketTimeoutException`.

Implication: during the burst-collapse window, **both REST fallback AND WS reconnect were failing simultaneously** with similar 60 s socket timeouts. The failure is broader than "REST path is broken"; it's "any new HTTPS call to `relay.phntm.pro` is broken during this window".

### F9. REST fallback OkHttp client uses a fresh client per call with `call=60 s / connect=30 s / read=60 s / write=60 s`

**Corrected after architect review 2026-05-30 (P2 on PR #252):** original wording cited `RelayTransportFactory.kt:221-224` (`callTimeout=120s`) — that is a different code path (prekey/Ktor client), not the REST fallback transport actually used for `/relay/send`, `/relay/poll`, `/relay/ack-deliver`. The real path is:

`shared/core/transport/src/androidMain/.../AndroidNativeOkHttpRestFallbackTransport.kt`:

- `:172-:180` — `private fun buildClient(): OkHttpClient` is invoked **per call**, returning a fresh OkHttpClient with `ConnectionPool(0, 1ms)` (line `:174`) — zero idle keepalive, no pooling between calls.
- `:188-:191` — `CALL_TIMEOUT_MS = 60_000L`, `CONNECT_TIMEOUT_MS = 30_000L`, `READ_TIMEOUT_MS = 60_000L`, `WRITE_TIMEOUT_MS = 60_000L`.
- Comment at `:40-:42` explicitly explains the no-pool stance: *"re-use a pool entry the server side has discarded, resulting in 30 s+ stalls. One fresh TCP+TLS handshake per call costs ~50–200 ms on a healthy uplink and is the price we pay for reliable delivery on hostile networks."*

These timeouts are the **exact ceilings** producing the `elapsedMs=60016/60017/60020` values in F6/F7 (REST `/send` and `/poll` go through this client).

The WS reconnect `auth/challenge` HTTPS GET in F8 is a **separate code path** — it goes through `KtorRelayTransport` and a different HTTP client, **not** `AndroidNativeOkHttpRestFallbackTransport`. Its observable ~60 s `SocketTimeoutException` wall in F8 (`:905`, `:913`) is empirical and aligns numerically with the REST 60 s budget, but the two are NOT the same client. Whatever the WS auth client's effective ceiling is, it should be confirmed by Commit 1's `EventListener` instrumentation on that path before any timeout tightening.

These ceilings (REST fallback's 60 s read/write/call, 30 s connect) were chosen to give chunked media upload (PR-M2) enough budget. They are NOT tuned for burst short-message delivery. A 60 s ceiling on `/relay/send` for a 1.6 KB body is several orders of magnitude over the legitimate need.

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
> **Sub-corroboration from Tecno-side log (F4):** Tecno-side `outbound_ack_deadline_expired` on s=1 (lines :222/:232/:239/:241) proves Tecno's outbound was not getting acked by relay either — bidirectional sickness on the same WS session. This narrows the H3 hypothesis to "WS path between Tecno and relay was dropping in both directions". But this corroboration is **client-side only**; relay-side confirmation (relay sent successfully, never got ack back) still requires the VPS log pull above.
> 
> If confirmed: the fix is NOT in our code — it is in chain selection. Burst should fall through to Tor / next chain link sooner.

### H4 — Tecno-side socket / radio / OS resource stall under bursts of fresh HTTPS calls

> **Replaces the original "OkHttp Dispatcher/ConnectionPool saturation" framing**, which is **architecturally impossible** per F9: the REST fallback uses `ConnectionPool(0, 1ms)` on a fresh `OkHttpClient` per call, so there is no shared pool to saturate. The architect surfaced this on 2026-05-30 PR #252 review.
> 
> Reframed hypothesis: during the burst window, Tecno's local network stack (cellular radio state, NAT entry table on the home router, or Android's per-app socket/file-descriptor limit) was overwhelmed by the volume of *fresh* TCP+TLS handshakes the REST fallback issues per call. Each `/relay/send`, `/relay/poll`, `/auth/challenge`, and WS reconnect = one fresh socket. Multiple in flight + retry storms = many simultaneous handshakes.
> 
> Falsification test: instrument `AndroidNativeOkHttpRestFallbackTransport` with an OkHttp `EventListener` exposing `dnsStart` / `connectStart` / `secureConnectStart` / `responseHeadersStart` / `callFailed` per call (analogous to `ProbeEventListener`). Rerun Test #83 v3 scenario 5. If the 60 s elapsed time is dominated by a single phase — e.g. `dnsStart` → `connectStart` → no `secureConnectStart` for 30 s — that pinpoints the network-stack stall layer (DNS / TCP SYN-ACK / TLS handshake) and tells us whether the issue is per-socket cost or radio/router state.
> 
> If confirmed: the fix is to (a) lower per-call ceilings so a single stuck handshake fails fast and triggers fall-through, (b) add jittered backoff to avoid all retries landing simultaneously, and (c) potentially detect repeated phase-failures and force a longer cooldown before next attempt.

### H5 — REST fallback timeouts (call=60 s / connect=30 s / read=60 s / write=60 s) are too generous for burst short-message delivery

> Numeric source: F9 (revised), `AndroidNativeOkHttpRestFallbackTransport.kt:188-191`. The original wording of this hypothesis used the prekey/Ktor `callTimeout=120s` from `RelayTransportFactory.kt`, which is a different code path; corrected here to match F9 rev2.
> 
> Note: the WS reconnect `auth/challenge` HTTPS GET uses a separate client (Ktor-based in `KtorRelayTransport`), but it observably hits the **same `SocketTimeoutException` 60 s wall** (F8 :905/:913). So the hypothesis "60 s per-call ceilings are over-provisioned for burst short messages" applies to BOTH the REST fallback path AND the WS reconnect auth path — the fix likely needs to touch both, not just `AndroidNativeOkHttpRestFallbackTransport`.
> 
> Falsification test: temporarily set REST short-message send/poll/auth timeouts to 10 s and rerun Test #83 v3 scenario 5. If reliability drops, the 60 s budgets were load-bearing. If user-visible recovery is faster but reliability holds, the budgets were over-provisioned for this path.

### H6 — Auth-state race between WS reconnect challenge and REST session refresh (low priority)

> Reframed after F8 correction: the failed `auth/challenge` calls in F8 are the **WS reconnect** path, not the REST `/auth/session` path. A race between the WS reconnect's identity-challenge GET and any in-flight REST fallback session refresh is therefore the actual shape of this hypothesis, not "REST `/auth/challenge` race".
> 
> Falsification test: log auth-token state at every transport state transition (`token_state_pre_transition` + `token_state_post_transition` with `expires_in_ms`, `acquired_at_ms`, `reuse_counter`, `path={ws|rest}`). If either path's auth call is firing while the other is mid-refresh, the race is the cause.
> 
> **Demoted to low priority:** F8 shows the two consecutive auth challenges both hit `SocketTimeoutException` at the socket layer — not a 401/403 / token-rejection signal. A socket-level failure across two distinct generations of WS session (`s=4`, `s=5`) is more consistent with the network/socket/radio/OS stall framing (H4) than with an auth-state race. Investigate H6 only if H3/H4/H5 are all falsified.

### H7 — Relay-side inbound-flow back-pressure during burst

> Falsification test: relay-side log for the s=3 window. Did relay attempt to deliver all ~20 burst envelopes to Tecno's WS during s=3, or did it queue them and try to drain in batches? If relay queue depth was rising while Tecno's WS was alive but silent on the inbound side, the relay's outbound flow control could be at fault — too many envelopes inflight on a stalled socket trigger back-pressure that kills throughput.
> 
> **Required evidence (not yet available):** this hypothesis depends on **relay-side telemetry** — outbound queue depth per recipient, ack-timeout counters from the relay's view, and `inbound_frames`/`acks_received` numbers from the relay's session_summary. None of that is in `test83-v3-tecno.log`. F4 shows only Tecno-client-side outbound expirations, which corroborate bidirectional sickness but do NOT confirm relay→Tecno ack drops. The relay log pull queued by H3 is the prerequisite for evaluating H7.
> 
> Tecno's session_summary `since_last_inbound_ms` numbers (F2) hint at long inbound silences (29-52 s before each WS death), which is consistent with H7 but also with H3/H4 — so the inbound-silence signal alone cannot discriminate.

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

### Inv3 — Auth path N consecutive failures within 30 s MUST surface to the user OR fall through

**Reworded after architect review 2026-05-30 (P2 on PR #252):** the original Inv3 spoke specifically of "REST `/auth/challenge`"; the actual failure in F8 is the **WS reconnect auth challenge** from `KtorRelayTransport`. The invariant must cover **both auth paths** — the WS reconnect HTTPS auth GET AND any REST fallback session-challenge call — because in practice the same network condition kills both, and the user-visible black-hole symptom is identical.

If any auth path (WS reconnect `auth/challenge`, REST fallback session challenge, or any other recovery-time auth call) returns `SocketTimeoutException` or any transport error N times consecutively (N = 2 per F8's observed pattern) within a 30 s window, the orchestration layer MUST either (a) trigger a connection-state event that the UI can surface as "Reconnecting" OR (b) fall through to the next chain link.

Silent retry forever (current behaviour beyond the test window) is unacceptable — the user sits in a transport black hole with no signal.

Verification: contract test on the connection-state flow exposed by the relay transport AND the REST fallback orchestrator, driven by N consecutive auth-layer transport errors from either path.

### Inv4 — WS reconnect fast path stays intact

After the fix, WS reconnect after a clean OkHttp ping-timeout close MUST still succeed in < 2 s on a healthy network (F1 baseline: 617 ms s=1→s=2, ~1 s s=2→s=3).

The fix MUST NOT regress this. Any change to `KtorRelayTransport` close-handler or reconnect loop must include a regression test for reconnect latency.

### Inv5 — R0.4b "no reconnect action" path remains unchanged

The `idle_watchdog` MUST remain passive-log-only per PR-R0.4b (#151) Vladislav-locked design. This track MUST NOT reintroduce `forceReconnect` from the watchdog under any hypothesis-derived fix.

Verification: `feedback_ws_heartbeat_diagnostic_2026_05_27.md` already records the v1.3/v1.4 own-goal. The PR diff must show the watchdog log line at `KtorRelayTransport.kt:1008-1011` unchanged AND no new `forceReconnect()` call from any new code path.

### Inv6 — Burst delivery floor on stable Wi-Fi: 20 → ≥19 in 60 s

**Vladislav-set target 2026-05-30 (P3 on PR #252):** a burst of **20 consecutive incoming messages** on a stable home Wi-Fi MUST result in **at least 19** of them being delivered to the receiver UI within **60 seconds**. No 60 s silent timeout during the burst is acceptable.

Test #83 v3 baseline: 7 / ~20 = 35 %. Post-fix target: 19 / 20 = 95 %.

Verification: a controlled-burst scenario in the Test #83 acceptance ladder (already drafted in `docs/tracks/chat-new-msg-chip.md`). The PR cannot merge without a PASS reading on this scenario.

### Inv7 — REST `/poll` failure visible to UI

If `RestStateMachine` is in `RestActive` and N consecutive `/poll` calls fail (N = 2 per F7), the consumer MUST receive a connection-state event (e.g. `ConnectionState.Reconnecting`) so the UI can show non-silent feedback.

Currently the UI was just "Online via Direct · Standard" while the user got nothing for 2 minutes. That is misleading.

Verification: contract test on the connection-state flow exposed by the relay transport, driven by N consecutive `/poll` `InterruptedIOException`.

### Inv8 — No reintroduction of `lastWorkingTransport` lock-in from REST fallback

Per `feedback_sticky_fallback_hint_2026_05_27.md`, REST fallback successes during burst MUST NOT save `lastWorkingTransport`. Only `kind == strategy.chain.first()` may. This is a pre-existing locked invariant; this track confirms it stays.

---

## Implementation plan (locked after Vladislav ACK on §1 §2 §3)

Four ordered commits, each independently mergeable but designed to land together as the WS-HEALTH-STATE1 squash. Each commit has its own architect ACK gate per PR #243's per-commit-review model.

### Commit 1 — Diagnostic `EventListener` on REST fallback **and on WS reconnect auth path** (no behaviour change)

Instrument `AndroidNativeOkHttpRestFallbackTransport` with an OkHttp `EventListener` exposing per-call phase timings, analogous to the existing `ProbeEventListener` used by `KtorTransportProbe`. Emit structured `REST_TRACE phase_*` log lines: `dnsStart`/`dnsEnd`/`connectStart`/`connectEnd`/`secureConnectStart`/`secureConnectEnd`/`responseHeadersStart`/`callFailed` per call, each tagged with `op={send|poll|ack|session}` and `id=<idem>`.

**Add the same phase instrumentation to the WS reconnect `auth/challenge` HTTPS GET** in `KtorRelayTransport` (or wherever the auth-challenge Ktor call lives), tagged `op=ws_auth gen=<n> s=<n>`. Per F8 + the H5 revision, both paths hit the same 60 s socket-level wall under the burst condition; covering only the REST fallback would leave the WS reconnect side of the failure invisible.

This commit is the **falsification instrument for H3/H4/H5**. After it ships and a Test #83 v3 scenario 5 is rerun, the logs will tell us WHICH phase consumes the 60 s — DNS / TCP SYN-ACK / TLS handshake / response headers — on BOTH the REST fallback and the WS reconnect auth paths. Until we have that data, the timeout-tightening commit (Commit 2) is guessing.

Scope guard: NO timeout changes, NO state-machine changes, NO UI changes. Diagnostic-only.

### Commit 2 — Short-message fail-fast ceilings

Lower per-call ceilings for `/relay/send`, `/relay/poll`, `/relay/ack-deliver`, and `auth/challenge` (both REST fallback and WS reconnect paths per H5 revision) so a single stuck handshake fails within 10-15 s instead of 60 s. Specific numbers Vladislav-set during the Commit-2 review window — **now locked in the design note appendix below**.

Add jittered backoff (per the H4 finding that "all retries landing simultaneously" makes the burst symptom worse).

Inv2 (REST send during WS death succeeds in 5 s or fails in 10 s) and Inv6 (20 → ≥ 19 in 60 s) are the merge gates.

Scope guard: NO change to the underlying client architecture (still fresh `OkHttpClient` + `ConnectionPool(0, 1ms)` per call — F9 design intentional per `:40-:42`). NO change to chain selection.

#### Commit 2 design note (Vladislav-locked 2026-05-30 post Commit 1 field run, **revision 2** post architect P3 on PR #254)

Empirical justification — the Commit 1 EventListener captured the exact mechanism on `C:\temp\test83-v4-tecno.log`:

- `:679 REST_TRACE phase_event op=send key=300a861b event=secureConnectStart` at `05:06:03.744`.
- `:721 REST_TRACE phase_event op=send key=300a861b event=connectFailed exception=SocketException message=Socket closed elapsedMs=59999` at `05:07:03.602`.

Exactly 60 000 ms of TLS handshake silence terminated by `Socket closed`. That is **OkHttp's `callTimeout(60 s)` force-closing the socket**, not a network-layer timeout. The 60 s wall is therefore entirely client-side configurable. Tightening it is safe and is exactly the right knob — there is no upstream server budget that requires 60 s.

The same pattern reproduced for `op=poll` (`:724`), `op=ack` (`:727`), and `op=ws_auth` (`:737-:738 callFailed exception=SocketTimeoutException totalMs=60198`). Cross-path symmetry confirms H5: REST short-message paths AND the WS reconnect auth path share the same over-provisioned ceiling and the same field failure mode.

##### Numeric targets (Vladislav-locked, line refs corrected in rev2)

| Constant | File | Line | Current | Commit 2 |
|---|---|---|---|---|
| `CALL_TIMEOUT_MS` | `AndroidNativeOkHttpRestFallbackTransport.kt` | `:223` | `60_000L` | **`10_000L`** |
| `CONNECT_TIMEOUT_MS` | `AndroidNativeOkHttpRestFallbackTransport.kt` | `:224` | `30_000L` | **`5_000L`** |
| `READ_TIMEOUT_MS` | `AndroidNativeOkHttpRestFallbackTransport.kt` | `:225` | `60_000L` | **`10_000L`** |
| `WRITE_TIMEOUT_MS` | `AndroidNativeOkHttpRestFallbackTransport.kt` | `:226` | `60_000L` | **`10_000L`** |
| WS-path `connectTimeout` (Direct only) | `RelayTransportFactory.kt` (Android) | `:90` | `if (socksProxyPort != null) 90 else 10` (seconds) | **`if (socksProxyPort != null) 90 else 5`** (seconds) |
| WS-path `callTimeout` (Direct only — NEW) | `RelayTransportFactory.kt` (Android) | new line after `:90` | not set | **`callTimeout(10, SECONDS)` when `socksProxyPort == null`** |
| `SEND_RETRY_DELAYS_MS` (whole array) | `RestFallbackOrchestrator.kt` | `:674-:676` | `1_000L, 3_000L, 8_000L, 20_000L, 60_000L` | **`1_000L, 3_000L, 8_000L, 15_000L, 15_000L`** |

> **Rev2 correction (architect P3 on rev1):** the original rev1 said *"`SEND_RETRY_DELAYS_MS[4] 60_000 → 15_000`"*, which is **insufficient**. `delayForRetry(attemptIndex)` at `RestFallbackOrchestrator.kt:520-525` uses `idx = (attemptIndex - 1).coerceIn(...)` and the retry loop at `:280-:282` only enters `delay(...)` when `attempt < SEND_MAX_ATTEMPTS`, so the 5th call (`attempt = 5` failure) reaches `break` **without ever calling `delayForRetry(5)`**. Index `[4]` is therefore dead code. The actually-used indices are `[0..3]` = current `1s/3s/8s/20s`. To achieve the target cadence `1/3/8/15/15` we must change `[3] 20_000 → 15_000` for the real effect; setting `[4] 60_000 → 15_000` is harmless cosmetic alignment. The whole-array spec above captures both.
>
> **Rev2 correction (architect P3 on rev1):** constants in `AndroidNativeOkHttpRestFallbackTransport.kt` were at `:188-191` BEFORE PR-WS-HEALTH-STATE1 Commit 1 landed. Commit 1 (`d79eaedd`) inserted the `.eventListener(...)` block in `buildClient()` and shifted the companion-object constants to `:223-226`. The rev2 table above cites current-master line numbers.

##### Jitter (±20 %, applied at orchestrator level — rev2: 7 sites + computed-then-logged shape)

Seven `delay(...)` sites in `RestFallbackOrchestrator.kt` are retry/backoff and MUST be jittered:

| Line | Site | Nominal source |
|---|---|---|
| `:281` | send retry on exception | `delayForRetry(attempt)` |
| `:324` | send retry on retryable 5xx/408/429 status | `delayForRetry(attempt)` |
| `:332` | send retry on unexpected status | `delayForRetry(attempt)` |
| `:425` | poll backoff when no token (added in rev2) | `POLL_BACKOFF_NO_TOKEN_MS` |
| `:445` | poll fail backoff | `intervalMs.coerceAtLeast(POLL_FAIL_BACKOFF_MS)` |
| `:454` | poll 401 token stale | `POLL_FAIL_BACKOFF_MS` |
| `:485` | poll unexpected status | `intervalMs.coerceAtLeast(POLL_FAIL_BACKOFF_MS)` |

**NOT jittered** (normal cadence, not retry backoff): `delay(intervalMs)` at `:461/:478` (regular poll spacing), `delay(POLL_DRAIN_IMMEDIATE_MS)` at `:475` (server-says-more drain). These are already rate-limited spacing for non-failure paths.

**Required code shape (rev2 — Vladislav P3): compute jitter, log the jittered value, then delay.** Mutating `delay(d * factor)` inline would leave the existing `next_delay_ms=` log fields showing the nominal value (`delayForRetry(attempt)`), making the rev2 acceptance gate ("`next_delay_ms` lands in ±20 % band") unprovable from logs. Concrete shape:

```kotlin
val nominalDelay = delayForRetry(attempt)
val jitterFactor = 0.8 + Random.Default.nextDouble() * 0.4   // 0.8..1.2
val jitteredDelay = (nominalDelay * jitterFactor).toLong()
log(
    "REST_TRACE send_retry id=${envelopeId.take(8)} reason=$lastReason " +
        "attempt=$attempt next_delay_ms=$jitteredDelay nominal_delay_ms=$nominalDelay " +
        "elapsedMs=$attemptElapsed",
)
delay(jitteredDelay)
```

- `next_delay_ms` is the **actual** wait time (preserves the historical field semantics — operators reading it see the real wait).
- `nominal_delay_ms` is a NEW field recording the un-jittered value so the ±20 % band can be verified post-hoc.
- Same shape (with the appropriate constant name in `nominalDelay`) applied to all 7 sites.

The `Random.Default` import (`import kotlin.random.Random`) is added at the top of the file. No deterministic-mean behaviour change; the jitter spreads simultaneous retries by ±20 % so the H4 stampede pattern (`send + poll + ack + ws_auth` all hit `secureConnectStart` simultaneously, observed at `test83-v4-tecno.log:679/:710/:711/:719`) is broken without serialising the calls.

##### Hard guards (preserved invariants)

| Invariant | Guarded by |
|---|---|
| Inv5 — R0.4b idle_watchdog stays passive | NO changes to `idle_watchdog` / `forceReconnect` / `lastInboundFrameMark`. Verified by grep absence in the Commit 2 diff. |
| Inv8 — REST fallback success MUST NOT update `lastWorkingTransport` | NO changes to `TransportManager` / `lastWorkingTransport` policy. Verified by grep absence. |
| SOCKS / Tor path budgets | `connectTimeout(90, SECONDS)` for SOCKS unchanged. NEW `callTimeout(10, SECONDS)` applies ONLY when `socksProxyPort == null`. Tor circuit cost remains accommodated. |
| Media upload OkHttp client | `AndroidNativeOkHttpMediaUploadTransport.kt:657-:665` (`buildClient()`). Already at 10 s `callTimeout` per `:673`. Separate code path; NOT touched. |
| Prekey publish / fetch clients | `RelayTransportFactory.kt:152-:215` (`createRestHttpClient()`) and `:218-:254` (`createPreKeyPublishHttpClient()`, deprecated) and the inline client in `AndroidNativeOkHttpPreKeyPublishTransport.publish()` at `:270-:285`. All prekey paths — NOT touched. **Rev2 correction**: rev1 mislabeled these as "media upload"; they are actually prekey-related. The actual media upload client is in `AndroidNativeOkHttpMediaUploadTransport`. |
| WS frame-level liveness gate | `readTimeout(60, TimeUnit.SECONDS)` at `RelayTransportFactory.kt:78` unchanged — it is the OS-level backstop for the WS-frame readLoop per ADR-010, not a per-call ceiling for the auth/challenge GET. |
| WS heartbeat | `pingInterval(15_000L)` at `RelayTransportFactory.kt:71` unchanged per `feedback_ws_heartbeat_diagnostic_2026_05_27.md`. |

##### NO parallel-handshake limiter in Commit 2 (Vladislav-decided 2026-05-30)

The Commit 1 field log shows simultaneous `send + poll + ack + ws_auth` `secureConnectStart` events (`test83-v4-tecno.log:679, :710, :711, :719`). Adding a per-host concurrency limiter is tempting, but Vladislav-rejected for THIS commit because:

- Head-of-line blocking risk: one stuck `send` would block `poll` and `ws_auth`, recreating the "ничего не происходит" symptom in a different shape.
- Diagnostic data after fail-fast lands may show stampede is no longer destructive once the 60 s wait collapses to 10 s.

If Commit 2 field test (Test #83 v5) still shows stampede-correlated failures, scope expansion goes to a deferred **optional Commit 2b**: mild per-host limiter (e.g. max 2 concurrent short-message HTTPS calls, low priority on `ack`). NOT in scope of THIS commit.

##### Scope diff (files touched in Commit 2 code — rev2 corrected)

| File | Change |
|---|---|
| `shared/core/transport/src/androidMain/.../AndroidNativeOkHttpRestFallbackTransport.kt` | 4 companion constants at `:223-:226` (rev2 line refs). |
| `shared/core/transport/src/androidMain/.../RelayTransportFactory.kt` | Direct-path `connectTimeout(10)` → `connectTimeout(5)` at `:90`. NEW `callTimeout(10, SECONDS)` (Direct only) inserted after `:90`. |
| `shared/core/transport/src/commonMain/.../RestFallbackOrchestrator.kt` | `SEND_RETRY_DELAYS_MS` whole-array `:674-:676` `{1_000, 3_000, 8_000, 20_000, 60_000} → {1_000, 3_000, 8_000, 15_000, 15_000}` (rev2: change `[3]` for actual effect, `[4]` kept for cosmetic consistency). Jitter applied at the **7** `delay(...)` sites `:281/:324/:332/:425/:445/:454/:485` using the computed-then-logged shape. New `import kotlin.random.Random`. |

Expected diff size: ~35 lines net across 3 files (rev2: 7 sites × ~3 lines + log-shape change is ~25 lines, plus the constant edits). No new files.

##### Test plan for Commit 2 code (Vladislav-locked 2026-05-30)

NOT a full CHIP1 acceptance ladder — transport-focused:

1. Cold-start install (force-stop + install APK).
2. Open PHANTOM, dispatch chat with emu → generate read receipts (Standard mode).
3. Burst 20 messages from emu in PowerShell loop (per Test #83 v3 playbook).
4. **PASS gate**: zero occurrences of `secureConnectStart → 60 s silence → connectFailed Socket closed elapsedMs=59999` anywhere in `C:\temp\test83-v5-tecno.log`. Any stall MUST fail within 10-15 s (the new ceiling).
5. **PASS gate**: any retry MUST land at jittered intervals (rev2: visible in the NEW `nominal_delay_ms=` and existing `next_delay_ms=` field pair on `send_retry` / `poll_fail` lines: `next_delay_ms` is the actual jittered wait, `nominal_delay_ms` is the un-jittered source; verifier asserts `0.8 ≤ next_delay_ms / nominal_delay_ms ≤ 1.2`).
6. **Observation gate (NOT blocking Commit 2 merge)**: if stampede pattern persists (multiple ops `secureConnectStart` within the same 100 ms window during the failure burst), record as evidence for optional Commit 2b. If stampede is gone, Commit 2b is not needed.

##### Why no Commit 2 code in this docs PR

`feedback_session_close_discipline.md` — design note first, then code. Same process model as PR-CRYPTO-INBOUND-X3DH-REPAIR1 (#248 mini-lock → #249 code). The numbers above are Vladislav-locked, but the code lands in a separate PR after explicit ACK on the design note. The design-note merge is the architect's last chance to push back on numbers before code branches.

### Commit 3 — Connection-state surface to UI + fall-through after repeated failures

When `RestStateMachine` is in `RestActive` and either (a) N consecutive `/poll` fail, (b) N consecutive `/send` fail, OR (c) any auth-path call fails N consecutive times (per Inv3 revision covering both WS reconnect and REST), emit a connection-state event the UI can surface as "Reconnecting" (no longer "Online via Direct · Standard" while delivery is dead).

Also: after the same N, trigger chain rewalk / fall-through to next chain link (Tor on Tecno). Currently the orchestrator silently retries forever.

Inv3 and Inv7 are the merge gates.

Scope guard: NO change to the chain-link list itself; NO change to the WS heartbeat (Inv5 protects); NO change to `lastWorkingTransport` (Inv8 protects).

### Commit 4 — Explicit guard tests for the locked invariants

Tests that codify the no-regression invariants directly:

- A test that asserts `idle_watchdog` never calls `forceReconnect` regardless of `sinceLastPong` (Inv5).
- A test that asserts REST fallback success never updates `lastWorkingTransport` (Inv8).
- A test that asserts the `R0.4b` log line `no reconnect action (R0.4b)` still fires under the same conditions as Test #83 v3 (idle WS, watchdog passive).

These tests are merge-blocking gates so a future regression cannot silently re-enable behaviour PR-R0.4b removed.

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

---

## Commit 3.1 design note — UI composite transport state ONLY (Vladislav-locked 2026-05-30, **rev3 after architect P2 on rev2 code shape**)

**Rev3 change (architect P2 on rev2 implementation shape):**

Rev2 drafted the derivation as `combine(wsTransport.state, hybridTransport?.stateMachine?.state ?: MutableStateFlow(RestMode.WsActive))`. The elvis is evaluated **once at `combine(...)` construction time** — if `hybridTransport == null` then, `combine` permanently subscribes to a dummy `MutableStateFlow` that no later code updates. UI would stay stuck on `WsActive` forever even after `hybridTransport` lands. Rev3 replaces that shape with a class-level `connectionRestMode: MutableStateFlow(RestMode.WsActive)` source that `combine` always reads from, and a separate coroutine forwards `hybrid.stateMachine.state` into it once `hybridTransport` exists. Standard "swap upstream lazily" pattern. Gate 8 genuinely satisfiable.

**Rev2 changes** (architect P2s on rev1):

1. Derivation table precedence: `RestMode` priority OVER raw `wsState` (rev1 had ambiguous overlap between `Connected | *` and `* | RestActive`). Aligns with the notification shade overlay precedence at `PhantomMessagingService.kt:254-:260`.
2. Owner shifted from `HybridRelayTransport` to `AppContainer` (`container.transport.get() = hybridTransport ?: wsTransport` at `AppContainer.kt:325-:326` falls back to bare `wsTransport` during the startup race). Type renamed `TransportEffectiveState` → `ConnectionUiState` (architect-preferred).

Added gates 7 (precedence test) and 8 (startup / null-hybrid fallback).


**Goal.** When `RestMode = RestActive` and raw WS state is `Connecting` / `Reconnecting` / `Error` / `Disconnected`, the ChatList main screen and the `ConnectionBanner` MUST NOT show misleading `Connecting...` / `Offline — messages queued`. Delivery is already happening through REST fallback; UI must reflect that reality.

**Scope guards (Vladislav-locked):**

- ❌ NO ping/pong behaviour changes (Commit 3.3 territory).
- ❌ NO chain rewalk / fall-through after N consecutive failures (Commit 3.2 territory).
- ❌ NO `RestStateMachine` / `KtorRelayTransport` / `TransportManager` behavioural changes.
- ❌ NO changes to the prekey-retry hook logic at `AppContainer.kt:987-:999` / `:1022-` (those keep raw `transport.state.is Connected` semantics — same as today).
- ✅ Pure UI / derived-state computation in Android layer.

### §1 — Consumer audit (read-only verified against master `3a1e5b56`)

Six consumers of `container.transport.state` (= `HybridRelayTransport.state` which currently delegates to `wsTransport.state` at `HybridRelayTransport.kt:137`):

| File | Line | Use | Bucket |
|---|---|---|---|
| `apps/android/.../screens/chatlist/ChatListScreen.kt` | `:147` | `val transportState = container.transport.state.collectAsState()` then passes to `ConnectionBanner` | **UI** |
| `apps/android/.../screens/chat/ChatScreen.kt` | `:330-:331` | `val transportState = container.transport.state.collectAsState(); val isConnected = transportState is TransportState.Connected` then drives the "online" status row at `:4480` | **UI** |
| `apps/android/.../ui/ConnectionBanner.kt` | `:71-:117` | Pattern matches all 5 variants for label + color (Connected/Connecting/Reconnecting/Disconnected/Error) | **UI** |
| `apps/android/.../service/PhantomWakeupReceiver.kt` | `:164` | `if (wsState is TransportState.Connecting)` for alarm logic | **Background scheduling — keep raw WS state** |
| `apps/android/.../di/AppContainer.kt` | `:987-:999` | `if (st is TransportState.Connected) retryWaitingMessages()` | **Logic — keep raw WS state** |
| `apps/android/.../di/AppContainer.kt` | `:1022-` | `if (st !is TransportState.Connected) return@collect; verifyBundleOnRelay() / replenishOPK / rotateSPK` | **Logic — keep raw WS state** |

Two consumers of `hybrid.stateMachine.state` (`RestMode`):

| File | Line | Use |
|---|---|---|
| `apps/android/.../service/PhantomMessagingService.kt` | `:246-:267` | Notification shade overlay — already produces `"Online via Direct · Limited realtime"` for `RestMode.RestActive` and `"Online via Direct · Recovering"` for `RestMode.WsCandidate` |
| `apps/android/.../di/AppContainer.kt` | `:714` | Internal DI wiring — collected for observer registration |

### §2 — Bonus finding: composite phrasing already in production

The notification shade overlay at `PhantomMessagingService.kt:254-:260` already produces the EXACT phrasing Vladislav wants on ChatList:

```kotlin
val text = when (mode) {
    phantom.core.transport.RestMode.RestActive ->
        "Online via Direct · Limited realtime · $modeLabel"
    phantom.core.transport.RestMode.WsCandidate ->
        "Online via Direct · Recovering · $modeLabel"
    phantom.core.transport.RestMode.WsActive ->
        null // Let the TransportManager state collector reassert
}
```

So Commit 3.1 is partly "extract the existing-and-correct shade logic into a shared derivation and route ChatList through it". Not greenfield design.

### §3 — Path A vs Path B (architectural choice)

**Path A — Android-side derived `TransportEffectiveState` (PREFERRED):**

NEW file `apps/android/src/androidMain/kotlin/phantom/android/transport/ConnectionUiState.kt`:

```kotlin
sealed class ConnectionUiState {
    object Online : ConnectionUiState()                       // RestMode.WsActive + WS Connected
    object LimitedRealtime : ConnectionUiState()              // RestMode.RestActive (fallback delivering)
    object Recovering : ConnectionUiState()                   // RestMode.WsCandidate (transitioning back)
    object Connecting : ConnectionUiState()                   // RestMode.WsActive + WS Connecting
    object Reconnecting : ConnectionUiState()                 // RestMode.WsActive + WS Reconnecting
    object Offline : ConnectionUiState()                      // RestMode.WsActive + WS Disconnected
    data class Error(val cause: Throwable) : ConnectionUiState()  // RestMode.WsActive + WS Error(t)
}
```

NEW property + derivation function on `AppContainer` (rev3 — architect-corrected combine shape):

```kotlin
// In AppContainer:

// Always-present RestMode source. Initial value is RestMode.WsActive
// so the pre-init window flows the bare-wsTransport semantics through
// the derivation correctly. After initMessaging wires hybridTransport
// (rev3 — see the separate forwarder coroutine below), this flow
// becomes the live mirror of hybrid.stateMachine.state.
private val connectionRestMode = MutableStateFlow(RestMode.WsActive)

// Composed presentation flow — always present from AppContainer
// construction, so consumers never see null.
val connectionUiState: StateFlow<ConnectionUiState> = combine(
    wsTransport.state,
    connectionRestMode,
) { wsState, restMode ->
    deriveConnectionUiState(wsState, restMode)
}.stateIn(
    scope = appScope,
    started = SharingStarted.Eagerly,
    initialValue = ConnectionUiState.Connecting,
)

// Inside initMessaging, AFTER hybridTransport becomes non-null
// (around the same site as the existing transport.state listeners
// at AppContainer.kt:987 and :1022):
appScope.launch {
    val hybrid = hybridTransport ?: return@launch
    hybrid.stateMachine.state.collect { mode ->
        connectionRestMode.value = mode
    }
}

// Pure derivation function, table-driven, unit-testable.
// Sealed-class API kept internal so tests can target it without
// going through combine / StateFlow plumbing.
internal fun deriveConnectionUiState(
    wsState: TransportState,
    restMode: RestMode,
): ConnectionUiState = when (restMode) {
    RestMode.RestActive  -> ConnectionUiState.LimitedRealtime  // priority 1
    RestMode.WsCandidate -> ConnectionUiState.Recovering       // priority 2
    RestMode.WsActive    -> when (wsState) {                   // priority 3+
        TransportState.Connected    -> ConnectionUiState.Online
        TransportState.Connecting   -> ConnectionUiState.Connecting
        TransportState.Reconnecting -> ConnectionUiState.Reconnecting
        TransportState.Disconnected -> ConnectionUiState.Offline
        is TransportState.Error     -> ConnectionUiState.Error(wsState.cause)
    }
}
```

**Architectural note (rev3 — addresses architect P2 on rev2 code shape):** the rev2 draft used `combine(wsTransport.state, hybridTransport?.stateMachine?.state ?: MutableStateFlow(RestMode.WsActive))`. The elvis operator is evaluated **once at `combine(...)` construction time**. If `hybridTransport == null` at that moment (startup race), `combine` permanently subscribes to a fresh dummy `MutableStateFlow` that no later code path ever updates — so even after `initMessaging` constructs `hybridTransport`, the UI flow stays stuck on the initial `WsActive` and never tracks the real `stateMachine.state`. The rev3 shape above instead uses a class-level `connectionRestMode` source that `combine` always reads from, and a separate coroutine forwards updates into it once `hybridTransport` lands. This is the standard pattern for "swap the upstream of a flow lazily after construction" and makes gate 8 genuinely satisfiable.

The pre-init window: `connectionRestMode` starts at `RestMode.WsActive`, so the derivation flows the bare-wsTransport semantics correctly (`Connecting` / `Disconnected` / `Connected` / `Reconnecting`). Once the forwarder coroutine starts collecting `hybrid.stateMachine.state`, every emission propagates through `connectionRestMode` → `combine` → `connectionUiState` → all UI consumers, without any extra plumbing at the consumer sites.

Derivation table (rev2 — architect-corrected for explicit precedence on `RestMode`):

Pattern-match order matters in Kotlin `when` — first match wins. The table is intentionally written so that `RestMode` is evaluated BEFORE raw `wsState`, because `RestActive`/`WsCandidate` carry presentation semantics that supersede raw WS health. Concretely: `RestStateMachine.kt:34-:45` documents that `WsCandidate` keeps REST polling continuing until either 60 s of WS uptime OR an outbound ACK round-trip lands — so raw WS `Connected` may transiently co-exist with `RestMode.WsCandidate`/`RestActive`, and the UI must reflect the LimitedRealtime/Recovering state in that window, not "Online".

| Priority | `restMode` | `wsState` | `connectionUiState` |
|---|---|---|---|
| 1 | `RestActive` | * | `LimitedRealtime` |
| 2 | `WsCandidate` | * | `Recovering` |
| 3 | `WsActive` | `Connected` | `Online` |
| 4 | `WsActive` | `Reconnecting` | `Reconnecting` |
| 5 | `WsActive` | `Connecting` | `Connecting` |
| 6 | `WsActive` | `Disconnected` | `Offline` |
| 7 | `WsActive` | `Error(t)` | `Error(t)` |

This matches the precedence already used by the notification shade overlay at `PhantomMessagingService.kt:254-:260`, where `RestActive` and `WsCandidate` are explicitly intercepted BEFORE falling through to `WsActive` (`null` return → defers to the raw-state-driven `TransportManager` collector).

Side note (architect 2026-05-30): **do NOT reuse `TransportCapabilities`** as the UI presentation source. `TransportCapabilities` lives at `shared/core/transport/.../TransportCapabilities.kt:28` and answers "can the user send text / voice / start a call right now?" — capability domain. UI presentation answers "what label and color does the banner show?" — presentation domain. Keep them separated even though both consume `RestMode`.

Owner of the derivation — **`AppContainer` (rev2)**, NOT `HybridRelayTransport`.

Architect 2026-05-30 surfaced that `container.transport` at `AppContainer.kt:325-:326` is implemented as `get() = hybridTransport ?: wsTransport` — i.e. it falls back to bare `wsTransport` BEFORE `initMessaging` constructs the hybrid wrapper. If the derivation lived on `HybridRelayTransport`, every UI consumer would need to handle a null source during the startup race. Hoisting the derivation to `AppContainer` lets it expose an always-present `StateFlow<ConnectionUiState>` that internally:

1. If `hybridTransport != null` → derive from `(hybridTransport.wsTransport.state, hybridTransport.stateMachine.state)`.
2. If `hybridTransport == null` → derive from `(wsTransport.state, RestMode.WsActive)` (implied — no fallback layer yet).

This matches the existing `transport` accessor's null-safety pattern, keeps the consumer API trivially `val ui = container.connectionUiState.collectAsState(initial = …)`, and avoids touching `HybridRelayTransport`'s public surface area at all.

Consumer switches:

- `ChatListScreen.kt:147` — switch source: `container.connectionUiState.collectAsState(initial = ConnectionUiState.Connecting)`.
- `ChatScreen.kt:330-:331` — switch source + `isConnected = (state is Online || state is LimitedRealtime || state is Recovering)` (now correctly true when delivering via fallback).
- `ConnectionBanner.kt:71-:117` — accept `ConnectionUiState`, pattern-match new variants:
  - `Online` → no banner.
  - `LimitedRealtime` → `"Online · Limited realtime"` / cyan dot.
  - `Recovering` → `"Online · Recovering"` / amber.
  - `Connecting` → keep current `"Connecting…"` / amber (correct on cold-start).
  - `Reconnecting` → keep current `"Reconnecting…"` / amber.
  - `Offline` → `"Offline — messages queued"` / danger.
  - `Error(t)` → keep current `"Offline — reconnecting"` / danger.
- Optional: `PhantomMessagingService.kt:246-:267` shade overlay — switch to `container.connectionUiState.collect` for DRY (one source of truth). If kept on `stateMachine.state`, the two paths must stay in semantic sync — feasible but brittle. Recommended DRY-fy.

Type naming rev2: `TransportEffectiveState` → **`ConnectionUiState`** (architect-preferred). The new name makes it obvious the type is presentation-only and not a candidate substitute for the existing `TransportState` source of truth.

Logic consumers (`AppContainer.kt:987-:999` retryWaitingMessages, `:1022-` prekey lifecycle) **unchanged** — still use raw `transport.state.is Connected` because the semantics they want is "fresh WS came up, retry the things that need a WS round-trip" and that's WS-specific.

`PhantomWakeupReceiver.kt:164` **unchanged** — same WS-specific semantics for alarm-driven connectivity poke.

**Blast radius Path A (rev2):**
- NEW files: 1 (`ConnectionUiState.kt`, ~30 lines).
- EDIT files: 3-4 (`AppContainer.kt` +25 lines for the property + collector + derivation; `ChatListScreen.kt:147` ~1 line; `ChatScreen.kt:330-:331` ~2 lines; `ConnectionBanner.kt:71-:117` ~30 lines pattern rewrite).
- OPTIONAL: 1 more (`PhantomMessagingService.kt:246-:267` ~10 lines DRY-fy).
- `HybridRelayTransport.kt`: **UNTOUCHED** (architect-corrected from rev1).
- Common `TransportState` API: **UNTOUCHED**.
- All existing test fakes: **UNTOUCHED**.
- iOS / JVM stubs: **UNTOUCHED**.

Expected net diff: ~85 lines across 4-5 files.

**Path B — new common-side `TransportState.LimitedRealtime` variant (REJECTED):**

EDIT `TransportState.kt`:

```kotlin
data class LimitedRealtime(val via: FallbackKind) : TransportState()
```

Blast radius forces edits in:

- `TransportState.kt` (+5).
- `KtorRelayTransport.kt:1604` `isConnected()` — decide whether `LimitedRealtime` counts as connected.
- `ConnectionBanner.kt`, `ChatScreen.kt`, `ChatListScreen.kt` — same UI edits as Path A.
- `PhantomWakeupReceiver.kt:164` — new variant interpretation.
- `AppContainer.kt:987, :1024` — does `LimitedRealtime` qualify for prekey retry?
- iOS stub `RelayTransport` impl.
- Tests: `FakeRelayTransportTest.kt`, `DefaultMessagingServiceTest.kt`, `Alpha0IntegrationTest.kt` — all `_state.value = TransportState.X` sites and any `when` exhaustiveness check needs new branch.

Files touched: ~10-15 across Android + common + iOS-stub + 3-5 test files. Expected net diff: ~150+ lines.

**Decision:** **Path A** — strict per Vladislav's 2026-05-30 guidance ("for малого 3.1 лучше сначала проверить, можно ли сделать derived UI model рядом с Android layer, не меняя common transport API"). Audit confirms common `TransportState` is too widely consumed (6+ Android sites, all test fakes, common `isConnected()`) for a low-blast-radius variant addition. Path B reserved as escape hatch only if Path A surfaces an actual blocker during code.

### §4 — Acceptance gates for the Commit 3.1 code PR (rev2: 8 gates)

1. **UI consistency:** when `RestMode = RestActive` (REST fallback delivering), `ConnectionBanner` does NOT show `"Connecting…"` / `"Offline — messages queued"` / `"Reconnecting…"`. It shows `"Online · Limited realtime"`.
2. **No raw-state regression:** when `RestMode = WsActive` (no fallback) and WS is `Connecting`/`Reconnecting`/`Disconnected`, the banner still shows the same labels it does today.
3. **Logic consumers unchanged:** `AppContainer.kt:987-:999` prekey retry and `:1022-` lifecycle hooks fire ONLY on raw `TransportState.Connected` transitions (same as today). Verified by grep on the Commit 3.1 diff. `PhantomWakeupReceiver.kt:164` `is TransportState.Connecting` check also unchanged.
4. **Hard guards:** Inv5 (R0.4b passive watchdog) and Inv8 (no `lastWorkingTransport` lock-in) untouched. Grep-absence verifiable.
5. **Common `TransportState` API unchanged.** Grep `phantom.core.transport.TransportState` for added variants — must show zero.
6. **No iOS/JVM stub edits.** Grep `shared/core/transport/src/(iosMain|jvmMain)` for changes — must show zero.
7. **RestMode-over-wsState precedence verified (rev2 — architect P2 on rev1):** a unit test on `deriveConnectionUiState(wsState, restMode)` exhaustively asserts the 7 rows of the priority table, including the architect-flagged ambiguous cases:
    - `(Connected, RestActive)` → `LimitedRealtime` (NOT `Online`).
    - `(Connected, WsCandidate)` → `Recovering` (NOT `Online`).
    - `(Reconnecting, RestActive)` → `LimitedRealtime` (NOT `Reconnecting`).
    - `(Error(t), RestActive)` → `LimitedRealtime` (NOT `Error(t)`).
   The function lives at internal visibility so the test can target it without going through `combine`/`StateFlow` plumbing.
8. **Startup / null-hybrid path (rev2 — architect P2 on rev1):** `container.connectionUiState` is an always-present `StateFlow` (initialised before `initMessaging` completes) and consumers reading it before `hybridTransport` is wired observe a non-null state derived from `(wsTransport.state, RestMode.WsActive)` — i.e. the same `Connecting`/`Disconnected`/`Connected`/`Reconnecting` semantics they got today on `container.transport.state`. Verified by a startup-race test in `AppContainer`-style harness, or by reasoning from the implementation if no harness exists.

### §5 — Implementation order for the code PR (rev2 — owner shifted to AppContainer)

1. NEW `ConnectionUiState.kt` + extract the pure `deriveConnectionUiState(wsState, restMode)` function next to it. Unit-test the 7 priority rows of the table (gate 7).
2. ADD `connectionUiState: StateFlow<ConnectionUiState>` to `AppContainer` with the null-hybrid fallback derivation (gate 8). Wire the collector inside `initMessaging` after `hybridTransport` becomes non-null.
3. SWITCH `ChatListScreen.kt`, `ChatScreen.kt`, `ConnectionBanner.kt` to consume `container.connectionUiState`.
4. (Optional) DRY-fy `PhantomMessagingService.kt:246-:267` shade overlay onto the same flow.
5. Field rerun: cold-start → chat-open → observe REST migration in real time → confirm ChatList shows `"Online · Limited realtime"`, NOT `"Connecting…"`.

### §6 — Out of scope for Commit 3.1 (hard)

- ❌ Commit 3.3 ping/pong investigation (separate diagnostic track).
- ❌ Commit 3.2 chain rewalk (intentionally AFTER 3.3 so it cannot mask the root cause).
- ❌ Commit 2b stampede limiter (deferred per architect — stampede not destructive).
- ❌ `TransportState` common-side new variant (Path B, rejected).
- ❌ Refactoring `AppContainer` prekey hooks to use `effectiveState` (their semantics ARE WS-specific).
- ❌ CHIP1 (stays parked at `78bd979e`).

---

## Commit 3.3 design note — ping/pong diagnostics ONLY (no behaviour, no rewalk) (Vladislav-locked 2026-05-31, **rev2 after architect 3-point conditional ACK**)

**Rev2 changes** (architect conditional ACK on PR #258 2026-05-31 — 3 sharpenings):

1. **H-Ping3 elevated to PRIMARY hypothesis** with explicit framing: "the system has built a misleading diagnostic surface around a dead app-level heartbeat. The REAL heartbeat lives inside OkHttp at the WS protocol layer, but we have zero visibility there." H-Ping3 unifies findings A/B/C and is now the central explanation we are testing; other hypotheses are orthogonal alternatives.
2. **VPS deploy hardened from "merge plan step" to "merge-blocking precondition"** — gate 6 now explicitly says without VPS-deployed relay binary, gates 3 and 4 fail by definition and the field test must be aborted.
3. **Scope guard against app-level Ping A/B sharpened with historical evidence** — `RelayTransportFactory.kt:46-:47` PR-H1e Run B halved WS lifetime when ping cadence tightened to 5 s, and PR-RECV-DIAG1 v1.3 at `:57-:63` worsened behaviour when ping disabled entirely. 15 s is the only known-stable value; any heartbeat-cadence A/B in this commit is forbidden behaviour-change.


**Goal.** Discriminate WHERE the OkHttp WS protocol Ping/Pong path drops on Tecno's home Wi-Fi. Test #83 v6 reproduced the failure mode **33 times** in one run: each WS session lives ~31 seconds, dies with `SocketTimeoutException: sent ping but didn't receive pong within 15000ms (after 0 successful ping/pongs)` despite receiving `inbound_frames=10±2` Frame.Text envelopes from the relay during its lifetime. This is the load-bearing transport bug that drove every preceding track: Commit 1 phase events traced the 60 s callTimeout firing; Commit 2 tightened it to 10 s; Commit 3.1 gave the UI an honest label for the failure mode. None of those fix the ping/pong itself.

**Scope guards (Vladislav-locked):**

- ❌ NO behaviour changes. Diagnostic instrumentation ONLY, same pattern as Commit 1 (PR #253 OkHttp `EventListener` on REST + WS auth).
- ❌ NO chain rewalk (Commit 3.2 territory, last per Vladislav anti-masking rationale).
- ❌ NO change to `pingInterval(15_000L)` at `RelayTransportFactory.kt:71`.
- ❌ NO change to `readTimeout(60s)` at `RelayTransportFactory.kt:78`.
- ❌ NO app-level `RelayMessage.Ping` sender reintroduction AND NO A/B testing of any heartbeat cadence (rev2 — sharpened per Vladislav 2026-05-31). Historical evidence at `RelayTransportFactory.kt:46-:47` records the PR-H1e Run B result: tightening the ping cadence to 5 s **halved** the WS lifetime (Phone 21.8 s vs 46.5 s baseline) because *"the tighter pong window and the cadence itself appeared to provoke teardown"*. PR-RECV-DIAG1 v1.3 at `:57-:63` then proved the opposite extreme (`pingInterval = 0L` / disabled) made things WORSE — Test #84.4 showed WS never died → `WsSessionEnded` events never fired → REST fallback never activated. **15 s is the only known-stable value**; any heartbeat-cadence A/B in this commit is forbidden behaviour-change.
- ❌ NO `APP_LEVEL_PING_ENABLED` flag flip. It is dead config (finding D in §2) — the value is not consumed by any sender code, so flipping it changes nothing observable BUT would imply we believe the flag matters. Future cleanup commit can mark it deprecated or delete it; this commit does not touch it.
- ❌ NO change to `RestStateMachine`, `HybridRelayTransport`, `TransportManager`, REST fallback.
- ❌ NO `forceReconnect` from idle_watchdog (Inv5).
- ❌ NO `lastWorkingTransport` updates (Inv8).
- ✅ Add structured `PING_TRACE` log lines on both client (Tecno + emulator) and relay (VPS).
- ✅ Field-test ladder must capture **three log streams** (Tecno + emulator + VPS relay) over the same wall-clock window.

### §1 — Empirical base (independent grep-verified against `C:\temp\test83-v6-tecno.log`)

33 sessions in v6 follow the same stable pattern (5 sample sessions cited; rest follow within ±10 ms):

| Line | Session | duration_ms | thrown text | pings_sent | pongs_received | inbound_frames | acks_received | since_last_pong_ms |
|---|---|---|---|---|---|---|---|---|
| `:258` | `gen=1 s=1` | 31019 | `after 0 successful ping/pongs` | 0 | 0 | 10 | 0 | -1 |
| `:352` | `gen=1 s=2` | 31023 | same | 0 | 0 | 9 | 0 | -1 |
| `:486` | `gen=1 s=3` | 31023 | same | 0 | 0 | 11 | 0 | -1 |
| `:587` | `gen=1 s=4` | 31022 | same | 0 | 0 | 10 | 0 | -1 |
| `:667` | `gen=1 s=5` | 31013 | same | 0 | 0 | 7 | 0 | -1 |

**Observation:** every session dies at **exactly the same elapsed time** (~31 s = 15 s ping wait + 16 s before client gives up). This is OkHttp's `pingInterval(15_000L)` firing followed by an internal 15-16 s pong-wait window. Architect was right that the thrown-text "after 0 successful ping/pongs" is the most-trustworthy signal we have today.

### §2 — Independent code audit (read-only against master `3567d37a`)

All 7 architect line refs verified verbatim:

| Architect cite | Actual finding |
|---|---|
| `RelayTransportFactory.kt:71` `.pingInterval(15_000L, MS)` | ✅ EXACT — OkHttp protocol Ping at 15 s. |
| `RelayTransportFactory.kt:168` `pingIntervalMillis = 0L` (Ktor ping disabled) | ✅ — assignment is at `:170` inside `install(WebSockets) {` block opening at `:168`. Comment at `:169`: *"Disabled — app-level Ping/Pong handles liveness in KtorRelayTransport"*. |
| `KtorRelayTransport.kt:501` `pingsSent` / `pongsReceived` declared | ✅ EXACT in `SessionStats` data class. |
| `routes.rs:475-:485` `Message::Ping → socket.send(Message::Pong)` | ✅ EXACT — server has WS-protocol pong reply code, counts `pings_received` at `:483`. |
| `RelayTransportConfig.kt:181` `APP_LEVEL_PING_ENABLED = true` | ✅ EXACT. |
| No `RelayMessage.Ping` send call anywhere | ✅ Grep-verified zero send sites; only handler for `RelayMessage.Pong` at `KtorRelayTransport.kt:1226`. |
| `KtorRelayTransport.kt:1226` `RelayMessage.Pong` handler | ✅ EXACT. |

**Five additional findings the architect did not surface (worth recording for the field-test analysis):**

A. `pingsSent` (`KtorRelayTransport.kt:501`) is **DEAD CODE** — declared, but grep finds zero `pingsSent++` / `pingsSent =` write sites. Field `pings_sent=0` in `session_summary` is therefore not "client thinks no pings happened"; it's "client never had a counter wired".

B. `pongsReceived` is incremented at `KtorRelayTransport.kt:1235` **only on `RelayMessage.Pong` text-frame receive**. Since no peer sends `RelayMessage.Ping` (finding A's mirror), no peer will reply with `RelayMessage.Pong` either, so `pongsReceived` will always be 0 too. Counter is structurally dead, not just zero by accident.

C. `lastPongMark` (`KtorRelayTransport.kt:286`, used at `:999` to compute `sinceLastPong` for the idle_watchdog log) is updated only at session-open (`:744`) and on `RelayMessage.Pong` text receive (`:1233`). NOT updated on OkHttp protocol Pong. So `sinceLastPong` in idle_watchdog logs grows unbounded for the entire session lifetime regardless of OkHttp protocol Pong health.

D. Documentation drift: the comment at `RelayTransportConfig.kt:9` declares *"Since PR-H1e the loop no longer emits app-level RelayMessage.Ping frames (APP_LEVEL_PING_ENABLED = false)"*, but the actual constant value at `:181` is `true`. The flag is **dead config** — value of `true` is not consumed by any sender code, so flipping it changes nothing. The design note records this so a future reader does not waste time A/B-testing the flag.

E. Ktor's `HttpClient(OkHttp) { install(WebSockets) }` abstracts away OkHttp's `WebSocketListener` callbacks (`onPing` / `onPong`). The codebase has `EventListener` instrumentation for HTTP calls (`ProbeEventListener` + `HttpPhaseEventListener` from Commit 1) but **zero** instrumentation on OkHttp protocol WS frames. Without bypassing Ktor's WS abstraction, we cannot directly observe OkHttp protocol Ping/Pong from inside our code. This is the load-bearing instrumentation gap.

### §3 — Hypothesis pool (rev2: H-Ping3 elevated to primary per Vladislav 2026-05-31)

Each hypothesis labeled with the test that would falsify it.

> **Rev2 framing (Vladislav 2026-05-31):** the system has built a misleading diagnostic surface around a dead app-level heartbeat. The REAL heartbeat lives inside OkHttp at the WS protocol layer, but we have zero visibility there (finding E). The counters and marks we *do* have (`pingsSent`, `pongsReceived`, `lastPongMark`) all attach to an app-level RelayMessage.Ping/Pong loop that has had no sender since PR-H1e (findings A/B/C). Therefore the "after 0 successful ping/pongs" exception text is the ONLY non-lying signal currently in our hands — and even that is a single number we read from a Throwable's message field. **H-Ping3 below is the central hypothesis** because it would unify all four observations (A/B/C dead counters + exception-text accuracy) into a single explanation: nothing is broken about ping/pong, the WS sessions are dying for a separate TCP-layer reason and the ping-pong wording is just OkHttp's idiom for "I gave up waiting for the next frame". The other hypotheses are kept as orthogonal falsifiable alternatives; the field test is structured to discriminate all of them at once.

**H-Ping3 — F2 counter-lie is the whole issue (no actual transport bug). PRIMARY HYPOTHESIS.**

> Hypothesis: OkHttp's internal ping scheduler IS firing and reply DOES arrive, but the F2-flagged counter-mismatch (the exception text counter and our `session_summary` counter both come from app-level paths that are dead per findings A/B/C above) makes us think no ping/pong happened when it actually did. Sessions die for a SEPARATE reason (e.g. peer NAT timeout closing the underlying TCP connection from the relay side, or an iptables idle-timeout on the home router).
>
> Falsification: relay-side `ws_protocol_pong_sent` log. If relay shows N pongs sent AND relay's `pings_received` ≈ floor(31000/15000) ≈ 2 per session, the ping/pong path is healthy and the "after 0 successful ping/pongs" exception text is genuinely a lie. The death cause is then a TCP-layer event the WS layer surfaces as a generic ping timeout.
>
> Unifies findings A/B/C from §2: all three counters are structurally dead in current code, so "0 successful ping/pongs" being reported on a session that received `inbound_frames=10` is consistent with "ping/pong actually worked, the counters and the exception text are both lying together because they share the dead app-level path". Requires VPS log to confirm.

**H-Ping1 — OkHttp's WS ping scheduler is conditionally gated.**

> Hypothesis: OkHttp's `RealWebSocket` initialises ping scheduling only after some condition met (e.g. first write to the socket, or `okhttp.WebSocketListener.onOpen` fully returned). If we never write anything in the first 15 s — and v6 shows the client only reads inbound frames during the 31 s session window — OkHttp may not actually fire its first protocol Ping until something else nudges it.
>
> Falsification: relay-side log of `pings_received` for the session window. If relay log shows zero `pings_received` count during the 31 s window, OkHttp's scheduler genuinely didn't fire. If relay shows `pings_received > 0` but client still throws "after 0 successful ping/pongs", H-Ping1 is falsified (scheduler fires, problem is elsewhere).
>
> Currently **plausible** — but requires VPS log to discriminate.

**H-Ping2 — Pings sent OK, pong reply lost between relay and Tecno.**

> Hypothesis: OkHttp on Tecno sends WS-protocol Ping, relay receives and replies with Pong, but Pong is dropped between relay and Tecno (cellular middlebox, Wi-Fi router QoS, OS NAT entry expiry). OkHttp times out waiting for Pong and closes the socket.
>
> Falsification: relay-side `ws_protocol_pong_sent` count for the session. If relay log shows N pong sends but Tecno's OkHttp exception says "after 0 successful ping/pongs", drop happened on the return path. If relay log shows zero pong sends, H-Ping2 is falsified.
>
> Currently **plausible** — same VPS log dependency.

**H-Ping4 — App-side ACK lag triggers relay-side close.**

> Hypothesis: Tecno receives 10±2 inbound frames in 31 s, doesn't ACK any of them (`acks_received=0` consistently in v6). Relay's outbound-ack watchdog (`outbound_ack_deadline_armed` then `outbound_ack_deadline_expired` per the v3/v5b1 observations) tears down the WS session because it concludes the recipient is stuck. The OkHttp side surfaces the relay-initiated close as a ping timeout because the WS frames stop arriving.
>
> Falsification: relay-side log of `outbound_ack_deadline_expired` count during the 31 s window. If we see ~10 expirations per session, relay is closing on its side. If zero, H-Ping4 is falsified.
>
> Currently **plausible** but doesn't match the "after 0 successful ping/pongs" wording — that text comes from OkHttp's own scheduler, not a relay-initiated close.

**H-Ping5 (NEW from finding D) — Documentation drift confuses readers but doesn't cause the bug.**

> Hypothesis: `APP_LEVEL_PING_ENABLED = true` is dead config (no sender). Flipping the value or removing it does NOT change the failure mode.
>
> Falsification: this is metadata only; the field test does not need to actively prove it. Recorded as a fix candidate for a follow-up cleanup commit AFTER 3.3 closes. NOT part of 3.3 scope.

**H-Ping6 (NEW from finding E) — Ktor abstraction is preventing observability, but underlying OkHttp is fine.**

> Hypothesis: OkHttp's `WebSocketListener.onPing/onPong` callbacks ARE firing correctly inside Ktor's bridge, but Ktor's API doesn't surface them to us. The bug is purely visibility, not behaviour.
>
> Falsification: same as H-Ping3 — relay-side `ws_protocol_pong_sent` log discriminates. If relay shows pongs, H-Ping6 holds. If not, we have a real transport bug separately from the visibility gap.
>
> The proposed Commit 3.3 instrumentation does NOT try to bypass Ktor; it instead parses the OkHttp exception text and adds relay-side correlation, which is enough to test H-Ping3 / H-Ping6.

### §4 — Locked instrumentation shape (Vladislav-locked, no behaviour)

#### Client-side (Tecno + emulator, same Android code)

NEW log lines emitted at session-close inside `KtorRelayTransport.endSessionStats(...)` (`:539-:587`):

```
RELAY_TRACE ws_ping_timeout_diag
  gen=<n> s=<n>
  duration_ms=<n>
  okhttp_successful_ping_pongs=<n>     # NEW — parsed from exception text
  okhttp_throwable_class=<simpleName>  # NEW — exception class for correlation
  app_level_ping_sent=<n>              # = stats.pingsSent (currently always 0; field documents the dead counter)
  app_level_pong_received=<n>          # = stats.pongsReceived (currently always 0; same)
  app_level_dead_counter=true          # NEW — explicit flag so the next reader does not chase the numbers
  inbound_frames=<n>                   # = stats.inboundFrames
  acks_received=<n>                    # = stats.acksReceived
  since_last_inbound_ms=<n>            # = stats.lastInboundFrameAtMs derived
  ping_interval_ms=15000               # NEW — config snapshot for grep correlation
  read_timeout_ms=60000                # NEW — same
```

Parsing rule for `okhttp_successful_ping_pongs`: regex `\(after (\d+) successful ping/pongs\)` on the thrown message. If unmatched, emit `-1`.

Plus the existing `session_summary` line stays UNCHANGED (do not modify the existing field set — operators correlate by timestamp). The new `ws_ping_timeout_diag` is a separate line, easy to grep.

NEW file (likely): `shared/core/transport/src/commonMain/.../PingTimeoutTextParser.kt` — internal helper extracting `okhttp_successful_ping_pongs` from the exception. Unit-testable. ~20 lines.

EDIT: `KtorRelayTransport.endSessionStats(...)` adds a second `relayLog(...)` call right after the existing `session_summary` emit at `:568-:586`. ~15 lines.

#### Relay-side (VPS, Rust)

NEW log lines emitted at `services/relay/src/routes.rs:483/:485` area (Ping handler):

```
tracing::info!(
    conn_id = conn_id,
    payload_len = payload.len(),
    pings_received = pings_received,
    "ws_protocol_ping_received"
);
```

Plus immediately after the `socket.send(Message::Pong(payload)).await` at `:485`:

```
tracing::info!(
    conn_id = conn_id,
    payload_len = payload.len(),
    "ws_protocol_pong_sent"
);
```

If the `socket.send(...)` returns `Err`, the existing error branch at `:486-:494` already logs `"ws-protocol pong send failed — closing session"` (which becomes the discriminator for H-Ping2 success vs failure). Keep the existing error path; add ONLY the two `info!` lines above.

Optionally add a parallel pair for the app-level path (where `RelayMessage::Ping` from the JSON parser is handled), tagged `event="app_ping_received"` / `event="app_pong_sent"`. We expect zero hits — recording the zero is itself the falsification of H-Ping5.

Scope: ~10 lines in `routes.rs`. NO change to the actual routing / heartbeat / timer / queue / WS lifecycle code.

#### Field-test capture

Vladislav-locked: Test #83 v7 captures **three** log streams over the same wall-clock window:

1. `C:\temp\test83-v7-tecno.log` (Android logcat with the canonical filter set).
2. `C:\temp\test83-v7-emu.log` (same).
3. `C:\temp\test83-v7-relay.log` — VPS relay container logs, time-synchronised via `docker compose logs --since=<start> --until=<end> relay > test83-v7-relay.log` (Vladislav-delegated via `feedback_ssh_delegation.md`).

VPS deploy MUST land the relay change before the field test. Architect explicit gate: design-note ACK is conditional on the deploy being part of the rollout plan.

### §5 — Acceptance gates (8 total)

1. **No behaviour change.** Grep on the Commit 3.3 diff finds zero edits in: `pingInterval`, `readTimeout`, `RestStateMachine.kt`, `HybridRelayTransport.kt`, `TransportManager.kt`, `forceReconnect`, `lastWorkingTransport`, `RestFallbackOrchestrator.kt`, `APP_LEVEL_PING_ENABLED`.

2. **Client emits `ws_ping_timeout_diag`** once per WS session that closes with `SocketTimeoutException`. Field-verified by `Select-String` on v7-tecno.log.

3. **Relay emits `ws_protocol_ping_received` and `ws_protocol_pong_sent`** with monotonically-increasing `pings_received` counter. Field-verified on v7-relay.log.

4. **Three-source correlation:** for each WS session that died with "after 0 successful ping/pongs" in `test83-v7-tecno.log`, the corresponding window in `test83-v7-relay.log` either:
   - Shows N≥1 `ws_protocol_ping_received` events (H-Ping3 / H-Ping6 confirmed — counters lie, ping/pong was fine), OR
   - Shows zero (H-Ping1 confirmed — scheduler genuinely didn't fire, client problem), OR
   - Shows N≥1 `ws_protocol_pong_sent` after each ping but Tecno still saw zero (H-Ping2 confirmed — drop on the return path).

   After v7 we can answer "**ping dropped on segment X**" in one sentence per the architect's gate.

5. **Counter lies documented in the new diagnostic line.** The `app_level_dead_counter=true` field MUST be present in every `ws_ping_timeout_diag` emit, so any future grep of `pings_sent=0` finds an accompanying line warning that the count is structurally zero.

6. **VPS deploy is a merge-blocking precondition (rev2 — sharpened per Vladislav 2026-05-31).** Without the new relay binary live on `relay.phntm.pro`, gates 3 and 4 fail by definition — `v7-relay.log` would contain zero `ws_protocol_ping_received` / `ws_protocol_pong_sent` events regardless of what client and network actually did, and the 3-source correlation in gate 4 would be unable to discriminate H-Ping1 / H-Ping2 / H-Ping3 / H-Ping6. Therefore the merge order is locked: **(a) merge client + relay code → (b) VPS rebuild + `docker compose up -d --force-recreate relay` + `curl https://relay.phntm.pro/health` verification → (c) only then field Test #83 v7 → (d) only then PR-level field PASS verdict**. If VPS deploy is skipped or partial, the field test must be aborted and the Commit 3.3 merge gates are NOT considered satisfied no matter what the client log shows.

7. **Pure parser is unit-tested.** `PingTimeoutTextParser` has unit tests for: matching `after 7 successful ping/pongs`, matching `after 0 successful ping/pongs`, no-match on unrelated exception text, robustness against partial/garbled text.

8. **Inv5 (R0.4b passive watchdog) and Inv8 (no `lastWorkingTransport` lock-in) untouched.** Grep absence verified.

### §6 — Implementation order (locked for the code PR)

1. **Code PR scope** (this docs note's scope only locks the design — code lands separately):
   - NEW `PingTimeoutTextParser.kt` (~20 lines + unit test).
   - EDIT `KtorRelayTransport.endSessionStats(...)` adds the second `relayLog(...)` after the existing `session_summary` emit.
   - EDIT `services/relay/src/routes.rs` adds two `tracing::info!` lines around the `Message::Ping` handler.

2. **VPS deploy step** before field test: rebuild relay binary from the new `routes.rs`, `docker compose up -d --force-recreate relay`, verify via `curl https://relay.phntm.pro/health`.

3. **Test #83 v7 field run** captures three log streams.

4. **Analysis pass:** discriminate H-Ping1 / H-Ping2 / H-Ping3 / H-Ping6 by §5 gate 4.

5. **Outcome decides next track:**
   - H-Ping3 / H-Ping6 confirmed → Commit 3.3-followup is a counter-cleanup pass (mark dead counters as such or remove). The 31 s closes themselves are then a relay-server / network issue, not client.
   - H-Ping1 confirmed → research how to coax OkHttp's WS ping scheduler to fire pre-write. Possibly a Commit 3.3b.
   - H-Ping2 confirmed → research relay→client path stability. Possibly a network/QoS observation track.

   Whatever the answer, **Commit 3.2 chain rewalk does not start until we have it in writing**. That is the anti-masking gate Vladislav locked.

### §7 — Out of scope for Commit 3.3 (hard)

- ❌ Behaviour changes of ANY kind. This is instrumentation only.
- ❌ Commit 3.2 chain rewalk (LAST per anti-masking rationale).
- ❌ Restructuring to raw OkHttp `newWebSocket(...)` to install our own `WebSocketListener` (finding E says Ktor abstracts the protocol Ping/Pong callbacks; bypassing it is a much larger refactor that has not been ACKd).
- ❌ Fixing the `APP_LEVEL_PING_ENABLED` documentation drift in `RelayTransportConfig.kt` (recorded for a later cleanup commit).
- ❌ Removing the dead `pingsSent` counter (recorded for a later cleanup commit; keeping the counter but flagging `app_level_dead_counter=true` is the 3.3 approach).
- ❌ App-level `RelayMessage.Ping` sender reintroduction (PR-H1e explicitly removed it; not in 3.3 scope).
- ❌ CHIP1 (stays parked at `78bd979e`).
- ❌ Any change to the existing `session_summary` line shape. Operators correlate by it; preserve byte-for-byte.
