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
- **Device:** Tecno BF7-12 real device. No emulator, no AVD, no VPN, no artificial network switching mid-session.
- **APK:** master HEAD (from `bbf0cfff` at open) built with **release-flag posture unchanged** (`RECONNECT_QUIESCENCE_ENABLED`, `MODE_2_FAST_PATH_ENABLED`, `MODE_2_STICKY_ENABLED` all stay literal `"0"` — production defaults, not fix-candidate build).
- **Session shape:** one Tele2 LTE real-device session per instrument. Continuous `logcat` capture with host-clock prefixed lines per the `host_recv_ts` discipline (`rest-send-connectivity-recon1-i2-host-synthetic-probe-design.md` §3). Both sides captured (Tecno + one peer — emu on Wi-Fi is acceptable for peer role since we care about the Tele2 side's failure surface).
- **Coverage per instrument:** at least one clean session. Two clean sessions with no reproduction on the primary instrument (B2-K1) triggers PARK per §8.
- **What each session extracts:** WSS session lifetime (armed → terminated), ping / pong round-trip counts, close reason / code / origin marker, cumulative bytes sent + received before failure, wall-clock age at failure, REST long-poll iteration count + body-completion rate, cross-device delivery verdict (did messages arrive both directions during the session), screen-state and Doze-state during the observation window.

---

## §3 Out of scope

- **Any code change.** The recon discriminates. If a chosen candidate surfaces, that is a separate scope-lock under a fix-track mini-lock — NOT this branch.
- **PR #330.** Closed as superseded 2026-07-04. Its code already lives on master (via MC-1/2/3 squashes + PR #364 orphan-scope-Job fix). This mini-lock does NOT reanimate PR #330's Draft.
- **Release flag flip.** `RECONNECT_QUIESCENCE_ENABLED` etc stay literal `"0"` in the release block throughout. Any production rollout is a separate flag-flip PR, not this recon's territory.
- **Wi-Fi B1 re-validation.** B1 closed 2026-07-02; no re-run required or scoped here.
- **VPN / emulator / AVD / bench harness / artificial network switching.** Every session that involves any of the above is INVALIDATED and does NOT count toward B2 coverage.
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

- **H-B2-Carrier-NAT-Idle** — Tele2 carrier-grade NAT (or similar carrier middlebox) has an idle-timeout. A connection with no application-layer traffic for longer than the timeout window (typical carrier values 60-1800 s) is silently dropped or the middlebox stops forwarding packets. Client sees the WSS as alive until the next send; relay sees it as gone. Discriminator: does the connection survive when kept-alive with periodic app-layer bytes (B2-K3 vs B2-K1)?
- **H-B2-Byte-Freeze** — Connection freezes after a cumulative byte volume threshold, independent of activity pattern. Consistent with the cumulative-bytes hypothesis referenced in `rc-direct-stability1.md` (Arm A.2 open-set extension, net4people #490 carry-forward). Discriminator: does the byte-flow soak (B2-K3) reproduce failure at a repeatable byte count regardless of ping cadence?
- **H-B2-Age-Kill** — Carrier or intermediate middlebox explicitly terminates long-lived connections by age, independent of traffic pattern or byte volume. Discriminator: does the same failure fire at a fixed wall-clock age across independent B2-K1 sessions with different byte / ping profiles?
- **H-B2-Doze-OEM** — Tecno / Android Doze-mode standby OR Tecno-specific OEM battery-management kill sockets / coroutine timers / process background when the screen goes off or the app moves to background. Client-side cause; carrier not involved. Discriminator: B2-K5 matrix (screen-on / screen-off / battery-optimisation-on / battery-optimisation-off) — does failure reproduce only under specific screen-state combinations?
- **H-B2-Config-Mismatch** — Client OkHttp / Ktor timeout config OR relay hold-policy config produces spurious disconnection that reads as "connection died on Tele2" but is actually a config-race artefact. Discriminator: relay-side logs + tcpdump (B2-K6) — is the observed close reason initiated by client, by relay, or by network?

### Fault-scope hypotheses — WHICH transport is affected

- **H-B2-REST-Survives-WSS-Fails** — Direct WSS is the failure surface; REST long-poll fallback continues to complete bodies and deliver messages during the WSS-degraded window. Discriminator: parallel WSS + REST captures (B2-K1 + B2-K4 back-to-back) — does REST show any `slow_post_aborted` / `SocketTimeoutException` / `body_read_incomplete` during the same wall-clock window WSS is failing?
- **H-B2-REST-LongPoll-Separate-Fault** — REST long-poll has a distinct failure mode from Direct WSS. Consistent with TELE2-LTE-REST-BREAKER-RECON1 §11 closure (`H-NetworkSideCancellation` supported via corpus; body loss on Tele2 LTE downlink documented; primary path DEMOTED per project memory 2026-06-05 `t2-outcome`). Discriminator: does REST long-poll (B2-K4) fail at a **different** rhythm / cause than WSS (B2-K1)?
- **H-B2-Both-Fail** — Both Direct WSS AND REST long-poll fail on Tele2 LTE. Neither transport family is a viable delivery vehicle. Verdict maps to §6 FAIL-Wider-Issue; the follow-up fix shape family becomes alternative transport (Reality / Tor / alt relay endpoint) OR relay-side hold-policy change, NOT client-side timeout tuning.

Multiple causal hypotheses may be simultaneously supported (e.g., an idle-timeout AND a byte-freeze at different thresholds). The fault-scope hypotheses are the three mutually-exclusive verdicts on transport coverage.

---

## §5 Diagnostic instruments

Seven K-run patterns. Executed one at a time, one Tele2 LTE session per instrument. Which instruments run in which order is operator-scheduled, but **B2-K1 + B2-K4** are the required first deliverable (§7).

- **B2-K1 — Direct WSS idle soak.** Hold the WSS connection open with no application-layer traffic beyond the built-in `KtorRelayTransport` ping cadence. Log lifetime, ping / pong counts per session, close reason, wall-clock age at failure. Sessions of 20 min minimum; 45 min preferred. Purpose: baseline Tele2 LTE WSS lifetime under idle conditions.
- **B2-K2 — WSS ping-interval matrix.** Repeat B2-K1 shape at three ping cadences: 15 s / 30 s / 45 s. Purpose: discriminate whether ping cadence itself is a fix lever (F-Mode2-cadence-invariant carry-forward says it is not for the Mode 2 rhythm, but B2's failure surface may be different and this must be tested cleanly, not assumed).
- **B2-K3 — Byte-flow soak.** Hold WSS + generate a continuous small-payload stream (e.g., 32-byte text pings at 1-2 s cadence) so cumulative bytes grow steadily. Log cumulative bytes sent + received before failure. Purpose: discriminate H-B2-Byte-Freeze from H-B2-Carrier-NAT-Idle (byte-freeze fires at byte count; idle fires at time-without-traffic).
- **B2-K4 — REST long-poll soak.** WSS explicitly disabled OR mode forced to `RestActive`. Continuous REST long-poll iterations against `/relay/poll`. Log iteration count, per-iteration body-completion rate, per-iteration wall-clock, `slow_post_aborted` / `SocketTimeoutException` / body-loss events. Purpose: fault-scope discriminator (WSS-only vs REST-also-affected).
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

**B2-K1 + B2-K4** on ONE Tele2 LTE real-device session (or two back-to-back sessions, one per instrument), executed after this mini-lock merges. NOT started before merge — this is a facts-first recon, and the mini-lock's out-of-scope + acceptance-gate contracts are the load-bearing invariants for the session's discipline.

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
- **Cross-contamination.** Any session run with VPN active, on emulator, on Wi-Fi, or with artificial network switching is INVALIDATED and does NOT count toward B2 coverage. A session that turns out to have been cross-contaminated after the fact does not itself trigger PARK; it triggers a re-run under clean conditions.

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
