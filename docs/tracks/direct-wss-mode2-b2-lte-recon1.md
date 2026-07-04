# Track: DIRECT-WSS-MODE2-B2-LTE-RECON1

**Type:** Facts-first reconnaissance / diagnostic. NOT a code-fix track. NOT a re-investigation of Direct WSS Mode 2 root cause (parked at Arm G v10/v11 Android libXray multi-segment ClientHello write-stall verdict from 2026-06-08 in `rc-direct-stability1.md` / `rc-direct-ws-death1.md`).
**Status:** Open (mini-lock).
**Opened:** 2026-07-04.
**Parent:** `docs/tracks/direct-wss-mode2-recon1.md` §11 Phase B amendment (2026-06-28, B1 / B2 split). B1 CLOSED via controlled Wi-Fi smoke PASS 2026-07-02 (PR #361 squash `fe14c977`). B2 was forward-pointer only in §11; this mini-lock materialises it.
**Last known master at open:** PR #364 squash `bbf0cfff`.

---

## §1 Goal

Discriminate the mechanism by which **Tele2 LTE** breaks long-lived delivery on Tecno + real device, so a follow-up fix-track can open with a targeted disposition. The recon answers two orthogonal questions:

1. **Causal question — what specifically kills the connection?** Carrier NAT idle-timeout / cumulative-byte freeze / age-based kill / Doze or OEM battery management / client-vs-relay timeout config mismatch.
2. **Fault-scope question — which transport is affected?** Direct WSS only (REST fallback delivers reliably) / REST long-poll has its own separate failure mode / both Direct WSS AND REST long-poll fail on Tele2 LTE (requires alternative transport OR relay-side hold-policy change).

The recon does NOT propose a fix. If a specific mechanism + affected-transport pair surfaces, the next deliverable is a **separate fix-track scope-lock**, NOT a code change on this mini-lock's branch.

---

## §2 Scope

- **Network class:** Tele2 LTE only. Wi-Fi baseline is closed by B1 on 2026-07-02 and is not re-opened here.
- **Tele2 DUT:** Tecno BF7-12 real device only. Explicitly NOT valid as the Tele2 DUT: any emulator, any AVD, any real device with VPN active, any real device that changes network mid-session (Wi-Fi ↔ LTE ↔ any other).
- **Peer device (for B2-K7 delivery smoke only):** either a second real device on any network, OR an emulator on Wi-Fi. The peer's role is limited to being the message-source / message-sink for real-message exchanges. The recon does NOT observe anything on the peer's transport surface — B2 characterises the **Tele2 DUT's** failure surface, and the peer is a delivery counterparty only.
- **APK:** master HEAD (from `bbf0cfff` at open) built with **release-flag posture unchanged** (`RECONNECT_QUIESCENCE_ENABLED`, `MODE_2_FAST_PATH_ENABLED`, `MODE_2_STICKY_ENABLED` all stay literal `"0"` — production defaults, not fix-candidate build). B2-K4 has a legal exception documented under §5 for its instrument-specific build variant.
- **Session shape:** one Tele2 LTE real-device session per instrument. Continuous `logcat` capture with host-clock prefixed lines per the `host_recv_ts` discipline (`rest-send-connectivity-recon1-i2-host-synthetic-probe-design.md` §3). Tele2 DUT is captured for every instrument; the peer is only captured for B2-K7.
- **Coverage per instrument:** at least one clean session. Two clean sessions with no reproduction on the primary instrument (B2-K1) triggers PARK per §8.
- **What each session extracts:** WSS session lifetime (armed → terminated), ping / pong round-trip counts, close reason / code / origin marker, cumulative bytes sent + received before failure, wall-clock age at failure, REST long-poll iteration count + body-completion rate, cross-device delivery verdict (did messages arrive both directions during the session), screen-state and Doze-state during the observation window.

---

## §3 Out of scope

- **Any code change.** The recon discriminates. If a chosen candidate surfaces, that is a separate scope-lock under a fix-track mini-lock — NOT this branch.
- **PR #330.** Closed as superseded 2026-07-04. Its code already lives on master (via MC-1/2/3 squashes + PR #364 orphan-scope-Job fix). This mini-lock does NOT reanimate PR #330's Draft.
- **Release flag flip.** `RECONNECT_QUIESCENCE_ENABLED` etc stay literal `"0"` in the release block throughout. Any production rollout is a separate flag-flip PR, not this recon's territory.
- **Wi-Fi B1 re-validation.** B1 closed 2026-07-02; no re-run required or scoped here.
- **VPN on the Tele2 DUT, emulator or AVD as the Tele2 DUT, bench harness, artificial network switching mid-session.** Every session in which the Tele2 DUT involves any of the above is INVALIDATED and does NOT count toward B2 coverage. Emulator on Wi-Fi as the B2-K7 peer is explicitly ALLOWED per §2 (peer role only).
- **Alternative transports implementation.** Reality / Tor / alt relay endpoint / different relay-side hold-policy are candidate FIX shapes for a future fix-track, not this recon's territory. B2 only produces a verdict pointing at which fix shape family fits; it does NOT design or land any of them.
- **Re-investigation of Direct WSS Mode 2 root cause.** Parked per `rc-direct-stability1.md` § 13 + `rc-direct-ws-death1.md` at Arm G v10/v11 write-stall (2026-06-08). Carry-forward facts (`F-Mode1`, `F-Mode2-pp0`, `F-Mode2-pp1`, `F-NotKtor`, `F-NotCount`, `F-CaddyAtMax`, `F-Mode2-cadence-invariant`) stay as INPUT here, not scope.
- **TELE2-LTE-REST-BREAKER-RECON1 re-open.** Closed 2026-06-28 (PR #342 squash `4302f56d`) with `H-NetworkSideCancellation` supported via corpus + carry-forward (NOT packet-confirmed) and `H-Tele2LTESpecific` supported via K-1 vs K-2 comparison (NOT confirmed against other LTE carriers). Both are carry-forward evidence into B2's hypothesis matrix, not re-opened findings.
- **Trek 2 long-poll backbone architecture changes.** Ambient infrastructure. B2 observes it via structured logs (`REST_TRACE poll_call`, `REST_TRACE poll_fail`, `REST_TRACE inbound_deliver`, `slow_post_aborted`) but does NOT propose changes to it.
- **Voice / calls / attachments.** Multi-transport voice contract already field-confirmed 2026-06-17. Out of B2's remit.
- **Predictive remediation.** Specifically NOT pre-locked: WSS ping interval flip beyond the B2-K2 discrimination matrix, raw-OkHttp swap (`F-NotKtor`), Ktor-adapter swap, short-lived WSS rotation, control-frame-vs-data-frame heartbeat redesign, relay-side hold-policy change. Each is a fix shape that belongs to a fix-track, not to this recon.

---

## §4 Hypotheses to discriminate

The hypotheses split into two levels. Levels are orthogonal: a session's verdict may support one causal hypothesis AND one fault-scope hypothesis independently.

### Causal hypotheses — WHY does the connection die on Tele2 LTE

**Verdicts marked in bold are from Run 1 K1 + K4 + K6 evidence (see §11).**

- **H-B2-Carrier-NAT-Idle** — Tele2 carrier-grade NAT (or similar carrier middlebox) has an idle-timeout. A connection with no application-layer traffic for longer than the timeout window (typical carrier values 60-1800 s) is silently dropped or the middlebox stops forwarding packets. Client sees the WSS as alive until the next send; relay sees it as gone. Discriminator: does the connection survive when kept-alive with periodic app-layer bytes (B2-K3 vs B2-K1)? — **REFUTED as sole cause per §11 K6.** Every Tecno WS session had `inbound_frames=0` on the relay side; there was no idle window to time out because no traffic ever crossed after WS Upgrade.
- **H-B2-Byte-Freeze** — Connection freezes after a cumulative byte volume threshold, independent of activity pattern. Consistent with the cumulative-bytes hypothesis referenced in `rc-direct-stability1.md` (Arm A.2 open-set extension, net4people #490 carry-forward). Discriminator: does the byte-flow soak (B2-K3) reproduce failure at a repeatable byte count regardless of ping cadence? — **REFUTED as sole cause per §11 K6.** Zero bytes on the WS after handshake; no byte-count could have accumulated to hit a freeze threshold.
- **H-B2-Age-Kill** — Carrier or intermediate middlebox explicitly terminates long-lived connections by age, independent of traffic pattern or byte volume. Discriminator: does the same failure fire at a fixed wall-clock age across independent B2-K1 sessions with different byte / ping profiles? — **REFUTED as sole cause per §11 K1.** Sessions die at ~31 s of client wall clock (OkHttp ping-timeout), not at an age threshold; every session across 89 iterations dies at the same ~31 s regardless of position in the soak.
- **H-B2-Doze-OEM** — Tecno / Android Doze-mode standby OR Tecno-specific OEM battery-management kill sockets / coroutine timers / process background when the screen goes off or the app moves to background. Client-side cause; carrier not involved. Discriminator: B2-K5 matrix (screen-on / screen-off / battery-optimisation-on / battery-optimisation-off) — does failure reproduce only under specific screen-state combinations? — **UNSUPPORTED per §11 K1 shape.** Screen ON, charger plugged in, `phantom.android` on battery-optimisation whitelist throughout the soak; failure is uniform across the whole soak, not gated on any Doze-lifecycle event.
- **H-B2-Config-Mismatch** — Client OkHttp / Ktor timeout config OR relay hold-policy config produces spurious disconnection that reads as "connection died on Tele2" but is actually a config-race artefact. Discriminator: relay-side logs + tcpdump (B2-K6) — is the observed close reason initiated by client, by relay, or by network? — **REFUTED per §11 K6.** Relay side records `close_error="Connection reset without closing handshake"` on 84/84 Tecno sessions, `close_origin="error"`. Relay is idle-blocked on `ws_read`, not initiating anything; the underlying TCP flow expires and delivers a raw reset from below.
- **H-B2-WS-Frames-Blocked-Post-Upgrade** — Tele2 LTE path permits the WS Upgrade handshake (TLS handshake succeeds, HTTP `Upgrade: websocket` completes, relay's `event="connect"` fires) but drops or fails to route all subsequent WebSocket application-layer frames from Tecno's uplink direction — Pings, application-data frames, Close frames. Broader than the original informal "Control-Frame-Filter" sketch: it is NOT selective for the WS Ping opcode (0x9), it is uniform for all opcodes emitted from the Tecno side after the handshake completes. Discriminator: relay-side observation of `inbound_frames` per session across a soak (B2-K6). — **STRONGLY SUPPORTED per §11 K6.** 0 hits on `ws_protocol_ping_received` OR `ws_protocol_pong_sent` across 92 Tecno `conn_id` values in the Run 1 window; 84/84 session_summary records show `pings_received=0 inbound_frames=0 outbound_frames=0`. Client's own `SocketTimeoutException` body asserts that OkHttp did send a Ping on the wire.

### Fault-scope hypotheses — WHICH transport is affected

- **H-B2-REST-Survives-WSS-Fails** — Direct WSS is the failure surface; REST long-poll fallback continues to complete bodies and deliver messages during the WSS-degraded window. Discriminator: parallel WSS + REST captures (B2-K1 + B2-K4 back-to-back) — does REST show any `slow_post_aborted` / `SocketTimeoutException` / `body_read_incomplete` during the same wall-clock window WSS is failing? — **REFUTED per §11 K4-natural.** REST long-poll on Tecno fails 9/9 with `InterruptedIOException` at ~35 s elapsed; `body_chunk=95` chunks land on Tecno but `body_eof=0` — the response never completes on the client side. Breaker cascades to the 120 s ceiling.
- **H-B2-REST-LongPoll-Separate-Fault** — REST long-poll has a distinct failure mode from Direct WSS. Consistent with TELE2-LTE-REST-BREAKER-RECON1 §11 closure (`H-NetworkSideCancellation` supported via corpus; body loss on Tele2 LTE downlink documented; primary path DEMOTED per project memory 2026-06-05 `t2-outcome`). Discriminator: does REST long-poll (B2-K4) fail at a **different** rhythm / cause than WSS (B2-K1)? — **SUPPORTED per §11 K4-natural + K6.** WS shape: no application-layer frames ever cross to relay. REST shape: relay's own log records `event="rest_poll_returned"` and Caddy reports HTTP 200 with `Content-Length: 4608` written over `duration: 30.9 s`, but Tecno still sees `body_chunk` without `body_eof`. Different failure modes on the same network class.
- **H-B2-Both-Fail** — Both Direct WSS AND REST long-poll fail on Tele2 LTE. Neither transport family is a viable delivery vehicle. Verdict maps to §6 FAIL-Wider-Issue; the follow-up fix shape family becomes alternative transport (Reality / Tor / alt relay endpoint) OR relay-side hold-policy change, NOT client-side timeout tuning. — **SUPPORTED per §11.** Tecno's inbound path is broken on both transports simultaneously; outbound POST `/relay/send` from Tecno works, but nothing reaches Tecno inbound.

Multiple causal hypotheses may be simultaneously supported (e.g., an idle-timeout AND a byte-freeze at different thresholds). The fault-scope hypotheses are the three mutually-exclusive verdicts on transport coverage.

---

## §5 Diagnostic instruments

Seven K-run patterns. Executed one at a time, one Tele2 LTE session per instrument. Which instruments run in which order is operator-scheduled, but **B2-K1 + B2-K4** are the required first deliverable (§7).

- **B2-K1 — Direct WSS idle soak.** Hold the WSS connection open with no application-layer traffic beyond the built-in `KtorRelayTransport` ping cadence. Log lifetime, ping / pong counts per session, close reason, wall-clock age at failure. Sessions of 20 min minimum; 45 min preferred. Purpose: baseline Tele2 LTE WSS lifetime under idle conditions.
- **B2-K2 — WSS ping-interval matrix.** Repeat B2-K1 shape at three ping cadences: 15 s / 30 s / 45 s. Purpose: discriminate whether ping cadence itself is a fix lever (F-Mode2-cadence-invariant carry-forward says it is not for the Mode 2 rhythm, but B2's failure surface may be different and this must be tested cleanly, not assumed).
- **B2-K3 — Byte-flow soak.** Hold WSS + generate a continuous small-payload stream (e.g., 32-byte text pings at 1-2 s cadence) so cumulative bytes grow steadily. Log cumulative bytes sent + received before failure. Purpose: discriminate H-B2-Byte-Freeze from H-B2-Carrier-NAT-Idle (byte-freeze fires at byte count; idle fires at time-without-traffic).
- **B2-K4 — REST long-poll soak.** Two legal execution shapes:
  - **B2-K4-natural** (preferred, no build variant): observe the REST long-poll behaviour during the WSS-degraded window that B2-K1 produces naturally. When Direct WSS enters `RestActive` mode after a WSS failure, the client's REST long-poll loop takes over. Extract REST iteration count / body-completion rate / `slow_post_aborted` / `SocketTimeoutException` / body-loss events during that window. Cost: depends on how frequently B2-K1 produces a WSS-degraded window long enough for a meaningful REST sample.
  - **B2-K4-forced** (fallback if B2-K4-natural cannot produce a sufficient window): a **diagnostic-only** debug build variant with WSS explicitly disabled (client-side toggle inside a debug-only `BuildConfig` field, gated to debug build type, stripped by R8 from release exactly like the existing `debugForceMode2Enabled` L1 seam). This is diagnostic tooling per WORKING_RULES rule 3 category, NOT a fix-candidate build; it does NOT flip any of the three RC quiescence release flags (`RECONNECT_QUIESCENCE_ENABLED` / `MODE_2_FAST_PATH_ENABLED` / `MODE_2_STICKY_ENABLED`), which stay literal `"0"` in the release block per §2. If B2-K4-forced is exercised, the mini-lock treats the required debug-toggle work as part of the operator-run smoke template artefact per §10 hand-off, not as a code change on the recon's mini-lock branch.

  Log target regardless of shape: iteration count, per-iteration body-completion rate, per-iteration wall-clock, `slow_post_aborted` / `SocketTimeoutException` / body-loss events. Purpose: fault-scope discriminator (WSS-only vs REST-also-affected).
- **B2-K5 — Screen / Doze matrix.** Repeat B2-K1 with four screen / battery-optimisation combinations: screen-on + battery-opt-on / screen-on + battery-opt-off / screen-off + battery-opt-on / screen-off + battery-opt-off. Purpose: discriminate H-B2-Doze-OEM from carrier-side hypotheses.
- **B2-K6 — Relay-side logs / server-side observation.** Cross-reference the client-side session's marker events against relay-side logs (`ssh phantom@relay.phntm.pro` + `docker logs relay` or equivalent) for the same session's identity. If tcpdump is available server-side without triggering ADR review, capture packet-level FIN vs RST vs silence. Purpose: discriminate H-B2-Config-Mismatch (relay-initiated close) from carrier-initiated silence and client-initiated timeout.
- **B2-K7 — Delivery smoke over Tele2.** Two-device pair (Tecno on Tele2 LTE + emu on Wi-Fi or second real device). Send real messages in both directions on a 60-90 s cadence over 15+ minutes. Log per-message `send_start` / `relay_send_return ok=true/false` / peer-side `handleDeliver DONE ack-deliver sent` OR `DECRYPT_TRACE ok`. Purpose: does the operational delivery goal survive the transport surface B2 is characterising?

---

## §6 Acceptance gates

- **PASS-A — Idle-time threshold with stable fix candidate.** B2-K1 + B2-K2 identify a Tele2 idle-timeout window (± 10 s). Fix-track candidate = keep-alive tuning (client-side ping cadence + optional keep-alive app-layer bytes). Fault-scope verdict independent.
- **PASS-B — Byte / age threshold with rotation strategy candidate.** B2-K3 identifies a repeatable byte-count threshold OR B2-K1 identifies a repeatable age threshold with the failure fire independent of activity. Fix-track candidate = short-lived WSS rotation with pre-emptive re-dial before the threshold. Fault-scope verdict independent.
- **PASS-C — Doze / OEM cause with Android-side mitigation candidate.** B2-K5 identifies a specific screen-state / battery-optimisation combination that gates failure. Fix-track candidate = Android-side foreground-service posture, `ForegroundServiceType` review, wake-lock discipline, OEM-specific battery-management OFF instruction to the operator. Fault-scope verdict independent.
- **PASS-D — REST survives while WSS fails, fallback strategy viable.** B2-K1 fails at some rhythm AND B2-K4 delivers cleanly in the same wall-clock window AND B2-K7 confirms message-level delivery survives. Fix-track candidate = detection-earns-actuation faster WSS→REST fall-back (per `project-dws-ux-hardening-track-pointer-2026-06-17`). Causal verdict independent (WSS-side cause maps to any of PASS-A / PASS-B / PASS-C).
- **FAIL-Wider-Issue — Both WSS and REST fail on Tele2.** B2-K1 fails AND B2-K4 fails AND B2-K7 confirms end-to-end delivery is broken. Fix-track candidate family = alternative transport (Reality / Tor / alt relay endpoint) OR relay-side hold-policy change. Explicitly NOT a client-side timeout-tuning problem.
- **PARK — Cannot reproduce on fresh Tele2 after 2 clean attempts on B2-K1.** Redirects to next-network-class candidacy: if a third LTE carrier is available (MTS, Megafon, Beeline; per `direct-wss-mode2-recon1.md` §12 forward-pointer path (a) — the M-3 instrument from TELE2-LTE-REST-BREAKER-RECON1 was never run), re-scope B2 to include that carrier. Otherwise B2 closes with the "cannot exercise from operator-accessible networks" verdict and points at `H-MF` disposition from `quiescence-validation-methodology-recon1.md` §4.

---

## §7 First deliverable

**B2-K1 + B2-K4** on ONE Tele2 LTE real-device session (or two back-to-back sessions, one per instrument), executed after this mini-lock merges. NOT started before merge — this is a facts-first recon, and the mini-lock's out-of-scope + acceptance-gate contracts are the load-bearing invariants for the session's discipline. B2-K4 runs in the `B2-K4-natural` shape per §5 as the default; if the WSS-degraded window from B2-K1 is too short to produce a meaningful REST sample, B2-K4 escalates to `B2-K4-forced` (diagnostic build variant) as documented in §10.

- APK from master HEAD at whatever squash is on master when the session runs. Release-flag posture unchanged (`"0"` in release block).
- No VPN. No AVD. No artificial network switching. Single Tele2 LTE network throughout.
- Both sides captured: Tecno-side full `logcat -v time` with the `host_recv_ts` prefix discipline; peer-side (emu-Wi-Fi or second real device) same discipline.
- Session length: 20 min minimum, 45 min preferred.
- What returns to the recon after the session: two logcat files + verdict-block from a smoke-template parser (built after mini-lock merges as a separate operator-run template artefact).

The operator-run smoke template is a follow-on artefact, not part of this mini-lock's diff. Its shape is captured in the `feedback_smoke_template_structure_2026_06_16` project memory and instantiated per the B2-K1 + B2-K4 profile after this mini-lock is on master.

---

## §8 Park conditions

- **Two clean B2-K1 attempts, no reproduction.** Redirect per §6 PARK.
- **Operator-workstation blockers.** SSH access to relay lost; Tecno device unavailable; Tele2 SIM inactive; ADB path broken. PARK until access restored; no scope change.
- **Cross-contamination.** Any session in which the Tele2 DUT ran with VPN active, on emulator, on Wi-Fi, or with artificial network switching is INVALIDATED and does NOT count toward B2 coverage. (Emulator on Wi-Fi as B2-K7 peer stays allowed per §2.) A session that turns out to have been cross-contaminated after the fact does not itself trigger PARK; it triggers a re-run under clean conditions.

---

## §9 What this mini-lock does NOT decide

- **Which fix candidate lands.** The recon points at a fix-shape family; the fix-track scope-lock names the specific fix, sets its own acceptance gates, and lands the code change.
- **§11 release / rollout gate.** LTE rollout gate stays in force per parent recon `direct-wss-mode2-recon1.md` §11 amendment (2026-06-28). B1 Wi-Fi PASS does NOT and cannot unblock LTE. This mini-lock's PASS verdict does not by itself flip the release gate — a fix-track landing + a subsequent B2-validated field run is the gate-flip precondition.
- **Wi-Fi re-validation.** B1 is closed by PR #361 `fe14c977` on 2026-07-02. Not re-opened.
- **PR #330 re-open.** PR #330 is closed as superseded by MC-1/2/3 + PR #364. This mini-lock does NOT reanimate it. Any future fix-track that emerges from B2 opens on its own branch, not on `canary/reconnect-quiescence`.
- **The role of Sprint 2b-C repair path.** Sprint 2b-C `inbound_repair_ok promotion=true` bootstrap path is ambient infrastructure that fires when receiver-side X3DH init state needs repair. It is observed as a side-channel in B2-K7 delivery captures but is NOT a B2 hypothesis or verdict target.

---

## §10 Hand-off note

- **Branch:** `docs/direct-wss-mode2-b2-lte-recon1-minilock` (this docs PR only).
- **Next after merge:** operator-run smoke template artefact for B2-K1 + B2-K4 first deliverable. Template ships APK-build recipe from master + adb command block + logcat capture discipline + verdict-block parser. Template execution captures the first B2 evidence corpus.
- **Rollback:** if this mini-lock reaches merge but the operator-run smoke template surfaces a scope error (e.g., a hypothesis is un-testable in the constraint set), amend this mini-lock via a follow-up docs PR, do NOT patch-in scope changes silently in the template.
- **In-force lineage:** `direct-wss-mode2-recon1.md` §11 amendment (parent). `quiescence-validation-methodology-recon1.md` verdict H-ME (methodology carry-forward for what "field-validation" means). `rc-direct-stability1.md` § 13 (Direct WSS Mode 2 root-cause park). `TELE2-LTE-REST-BREAKER-RECON1` §11 closure (REST long-poll body-loss carry-forward). All stay in force here.

---

## §11 Run 1 evidence closure — K1 + K4-natural + K6 (2026-07-04)

**Run 1 window:** `2026-07-04T12:09:28Z → 12:58:00Z UTC`. Tecno BF7-12 on Tele2 LTE, emu peer on Wi-Fi. APK from master `7207593b` debug build (`sha256 fa77a24e177167bb98f1f72e66db2ba688d3c774e1c3e49ae736f87b79ecf6c9`). Release-flag posture unchanged — `RECONNECT_QUIESCENCE_ENABLED` / `MODE_2_FAST_PATH_ENABLED` / `MODE_2_STICKY_ENABLED` all stay `"0"`. Constraints per §2 respected: Tele2 DUT only, no VPN, no artificial network switching, screen ON + charger + battery-opt whitelist throughout.

Durable evidence bundle at `C:\temp\b2-k1-k4-recon-2026-07-04\`:

- `run1-analysis.md` — Tecno + emu client-side reconstruction (122 lines).
- `k6-analysis.md` — relay-side discrimination write-up (161 lines).
- `k6-stage/` — raw relay-side log slices (4 files, ~1.6 MB total: `phantom-relay-{full-window,tecno}.log`, `phantom-caddy-{full-window,tecno-endpoints}.log`).

### K1 — WSS baseline observation

- **89 WSS `session_summary` records** on Tecno client side during the 45-min soak.
- Every record: `duration_ms=31005..31027` (tight ~31 s cluster); `close_origin=error`; `close_code=none`; `pings_sent=0` (app-level dead counter — real OkHttp Ping fires per `thrown` body); `pongs_received=0`; `inbound_frames=0`; `okhttp_successful_ping_pongs=0`; `since_last_pong_ms=-1`; `thrown='SocketTimeoutException: sent ping but didn't receive pong within 15000ms (after 0 successful ping/pongs)'`.
- Rhythm: WS Upgrade succeeds → OkHttp emits WS Ping (opcode 0x9) at 15 s → wait 15 s for Pong → timeout at ~30 s → close → reconnect. 89 iterations × ~30 s = the full soak.
- Distinct from the prior "Direct WSS Mode 2 slow decay after N pongs" shape parked in `rc-direct-stability1.md` § 13.

### K4-natural — REST long-poll observation

- Mode switch fires at `12:10:59.962` local (`mode_switched from=WsActive to=REST_ACTIVE reason=idle_threshold`). REST long-poll takes over for the remainder of the soak.
- Client counts: 9 × `REST_TRACE poll_call`, 9 × `REST_TRACE poll_fail reason=InterruptedIOException elapsedMs=~35025`. 100 % of polls fail with a client-side read-timeout at ~35 s (`hold_secs=30` server-advertised + margin).
- Body arrival on Tecno: `body_chunk=95`, `body_eof=0`. Server sent chunks; client never observed EOF.
- Breaker cascades: 21 × `REST_TRACE breaker_open`. Cooldown grows `5 000 → 10 000 → 20 000 → 40 000 → 80 000 → 120 000 ms` (ceiling) and stays at the ceiling for the rest of the soak.

### K6 — relay-side discrimination

Extracted from `phantom-relay` and `phantom-caddy` container logs via `docker logs --timestamps --since 2026-07-04T12:09:28Z --until 2026-07-04T12:58:00Z`, filtered on Tecno identity prefix `d95a9d5a3fc31afd`.

**WS side:**

- Relay recorded **84 `event="session_summary"`** records for Tecno's identity. Every single one shows `pings_received=0`, `pongs_sent=0`, `inbound_frames=0`, `outbound_frames=0`, `echo_frames_received=0`, `echo_frames_sent=0`, `since_last_ping_ms=-1`, `since_last_pong_ms=-1`, `since_last_inbound_ms=-1`, `close_origin="error"`, `close_code=0`, `close_error="ws_read: WebSocket protocol error: Connection reset without closing handshake"`, `duration_ms ≈ 153 000` (with one 1 025 580 ms outlier — a session that survived across two probe iterations before the underlying TCP flow expired).
- **0 hits** on `ws_protocol_ping_received` OR `ws_protocol_pong_sent` across **all 92** Tecno `conn_id` values in the full-window log. The marker fires 440 times in the same window for other users at the same relay, confirming the marker is emitted correctly at INFO — it just never fires for any Tecno session.
- The delta 31 s (client) vs 153 s (relay) is reconciled by: client's OkHttp closes at its 30 s ping-timeout; relay's `ws_read` remains blocked ~120 s more until the underlying TCP flow expires (Linux TCP keepalive OR Tele2 carrier drops the flow) and delivers a raw RST from below.

**REST side (supporting, not fully dug):**

- `phantom-caddy` access log records Tecno `/relay/poll` requests as HTTP `200`, `Content-Length: 4608`, `duration: 30.9 s`, connection: keep-alive with the `Connection: close` request header. Client remote IP visible.
- `phantom-relay` emits **25 `event="rest_poll_returned"`** records for Tecno in the window (vs client's 9 `poll_call` — reconciliation of that 25 : 9 volume delta not fully dug; not needed for the mini-lock verdict).
- Emu → Tecno delivery-exercise message `5cc3e70b` appears in **one** `rest_poll_returned` event with `envelope_id=5cc3e70b-a7b2-416b-bcc3-22bf13b2e193 more=true chunked_flush=true` — the relay did try to deliver it. Tecno never processed it (matches the K1 client log absence of that ID).

### Verdict candidate per §6

**`FAIL-Wider-Issue`.** Both Direct WSS AND REST long-poll fail on the Tecno's Tele2 LTE inbound path. Tecno's outbound POST `/relay/send` works. Client-side keep-alive tuning cannot fix `H-B2-WS-Frames-Blocked-Post-Upgrade` because the frames are dropped in transit regardless of ping cadence. Client-side timeout tuning cannot fix the REST body-loss because the response is fully written server-side within `hold_secs=30 + margin` but doesn't arrive intact.

### Hypothesis-matrix delta this closure lands in §4

- **NEW candidate added:** `H-B2-WS-Frames-Blocked-Post-Upgrade` (STRONGLY SUPPORTED).
- **Marked REFUTED as sole cause:** `H-B2-Carrier-NAT-Idle`, `H-B2-Byte-Freeze`, `H-B2-Age-Kill` — the actual mechanism does not require an idle window / byte threshold / age threshold; frames simply do not cross after handshake.
- **Marked REFUTED:** `H-B2-Config-Mismatch` (relay is idle, not initiating close).
- **Marked UNSUPPORTED:** `H-B2-Doze-OEM` (uniform failure across the whole soak with screen ON + charger + battery-opt whitelist).
- **Marked REFUTED:** `H-B2-REST-Survives-WSS-Fails`.
- **Marked SUPPORTED:** `H-B2-REST-LongPoll-Separate-Fault`.
- **Marked SUPPORTED:** `H-B2-Both-Fail` (main fault-scope verdict).

### What this closure does NOT do

- Does NOT locate the specific carrier / middlebox component responsible for dropping post-handshake WS frames. Discriminating "carrier CGN drops frames" vs "middlebox strips WS payloads" vs "MTU-adjacent drop of the first non-handshake frame" would require simultaneous packet capture at both endpoints — beyond B2's remit.
- Does NOT scope-lock a fix. B2 points at the fix-family; the fix-track opens on its own mini-lock with its own acceptance gates.
- Does NOT change `direct-wss-mode2-recon1.md` §11 release / rollout gate. LTE rollout gate stays in force.
- Does NOT re-open PR #330.
- Does NOT invalidate B1's Wi-Fi closure (different network class, different failure surface).

### Fix-family pointers (for a later, separate track)

The follow-on scope-lock must cover the whole Direct WSS surface — not just text messages, but voice notes, calls, delivery / ack, reconnect, route-change, push / fallback. Candidate fix-families that a facts-first architectural decision would evaluate:

- Alternative transport for LTE-detected paths: Reality-shaped tunnel, alternative relay endpoint (different port / domain / path), QUIC / H3 / WebTransport if realistic on the target Android stack.
- Relay-side hold-policy change: short-poll LTE mode (much smaller response bodies + shorter hold), no long-held bodies on LTE-detected client IPs, server-emitted unsolicited Pongs.
- Hybrid transport-selection by network class: TURN-like relay-mode for calls with carrier detection, short-poll receive + POST send on LTE.
- Anything else surfaced by external architectural review.

This mini-lock does NOT choose among them. The next artefact after this closure is an operator-scheduled architectural review (Fable 5 / council), not a fix-track scope-lock.

### Next step

1. Land this closure in repo (this PR).
2. Prepare a canonical prompt for an architectural review pass (Fable 5 / council) that receives Run 1 evidence + constraints and produces a ranked fix-family recommendation.
3. Open a separate fix-track mini-lock only after (2) is on record.

### Run 1 script bugs found and fixed (non-blocking)

Two bugs in `b2-k1-k4-run.sh` surfaced during Run 1 analysis and were patched inline (`bash -n` clean, re-verified against Run 1 corpus):

- Regex `to=RestActive` did not match the log-writer output `to=REST_ACTIVE` (source-code CamelCase vs log-writer SCREAMING_SNAKE). Widened to `to=(RestActive|REST_ACTIVE)`.
- `grep -c "..." || echo 0` produced multi-line `"0\n0"` on macOS bash whenever grep matched zero lines, which broke every downstream `[ $X -ge 1 ]` with "integer expression expected". Replaced across all counters with a `count_matches()` helper that swallows grep's exit via `|| true` and forces single-line output via `head -1`.

Neither bug affects the K1 / K4 / K6 substantive findings — the coarse OBSERVED_ bucket the runner wrote (`OBSERVED_INCONCLUSIVE_NEEDS_POSTHOC_REVIEW`) was corrected by hand to the actual `OBSERVED_WSS_FAILED_REST_ALSO_STRUGGLED` bucket in the durable analysis.
