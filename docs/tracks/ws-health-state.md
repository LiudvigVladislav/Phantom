# PR-WS-HEALTH-STATE1 — mini-lock

**Status:** Draft, **revision 3** after architect P3 follow-up on rev2 (PR #252 2026-05-30). Rev2 fixed F4/F7/F8/F9/H4/Inv3/Inv6. Rev3 propagates those corrections into the still-stale H3 (sub-corroboration framing), H5 (timeout numbers), H6 (downgrade + reword off "REST `/auth/challenge` race"), and H7 (relay-side evidence dependency made explicit). Rev3 also folds in the four-commit Implementation plan ladder Vladislav approved on PR #252 thread. Still awaiting Vladislav explicit ACK on §1, §2, §3 + Implementation plan before code work begins. Three-section opening procedure per `feedback_session_close_discipline.md` + 2026-05-30 lock.

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

These timeouts are the **exact ceilings** producing the `elapsedMs=60016/60017/60020` values in F6/F7 and the WS-reconnect `auth/challenge` 60 s wait in F8 (since the WS reconnect path uses a similar HTTPS GET that hits the same network condition).

These ceilings were chosen to give chunked media upload (PR-M2) enough budget. They are NOT tuned for burst short-message delivery. A 60 s ceiling on `/relay/send` for a 1.6 KB body is several orders of magnitude over the legitimate need.

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
> Reframed hypothesis: during the burst window, Tecno's local network stack (cellular radio state, NAT entry table on the home router, or Android's per-app socket/file-descriptor limit) was overwhelmed by the volume of *fresh* TCP+TLS handshakes the REST fallback issues per call. Each `/relay/send`, `/relay/poll`, `/relay/auth/challenge`, and WS reconnect = one fresh socket. Multiple in flight + retry storms = many simultaneous handshakes.
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

### Commit 1 — Diagnostic `EventListener` on REST fallback (no behaviour change)

Instrument `AndroidNativeOkHttpRestFallbackTransport` with an OkHttp `EventListener` exposing per-call phase timings, analogous to the existing `ProbeEventListener` used by `KtorTransportProbe`. Emit structured `REST_TRACE phase_*` log lines: `dnsStart`/`dnsEnd`/`connectStart`/`connectEnd`/`secureConnectStart`/`secureConnectEnd`/`responseHeadersStart`/`callFailed` per call, each tagged with `op={send|poll|ack|session}` and `id=<idem>`.

This commit is the **falsification instrument for H3/H4/H5**. After it ships and a Test #83 v3 scenario 5 is rerun, the logs will tell us WHICH phase consumes the 60 s — DNS / TCP SYN-ACK / TLS handshake / response headers. Until we have that data, the timeout-tightening commit (Commit 2) is guessing.

Scope guard: NO timeout changes, NO state-machine changes, NO UI changes. Diagnostic-only.

### Commit 2 — Short-message fail-fast ceilings

Lower per-call ceilings for `/relay/send`, `/relay/poll`, `/relay/ack-deliver`, and `auth/challenge` (both REST fallback and WS reconnect paths per H5 revision) so a single stuck handshake fails within 10-15 s instead of 60 s. Specific numbers Vladislav-set during the Commit-2 review window.

Add jittered backoff (per the H4 finding that "all retries landing simultaneously" makes the burst symptom worse).

Inv2 (REST send during WS death succeeds in 5 s or fails in 10 s) and Inv6 (20 → ≥ 19 in 60 s) are the merge gates.

Scope guard: NO change to the underlying client architecture (still fresh `OkHttpClient` + `ConnectionPool(0, 1ms)` per call — F9 design intentional per `:40-:42`). NO change to chain selection.

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
