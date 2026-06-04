# RC-DIRECT-STABILITY1 — Direct WebSocket stability fix-track mini-lock

> **Track scope.** Make Direct WebSocket hold reliably on Tecno Android against `relay.phntm.pro` on both Wi-Fi and Tele2 LTE. This is a **fix track** that consumes the empirical findings of RC-DIRECT-WS-DEATH1 Phase 1+2 (closed 2026-06-03) and tests concrete fix candidates against the production VPS + production Android codebase. 3.2b.1 adaptive validation stays `unfrozen but parked` behind this track per the strategic pivot.

> **Single-source-of-truth pattern.** Phase 1 and Phase 2 of RC-DIRECT-WS-DEATH1 stacked their mini-locks + outcome summaries in a single file (`rc-direct-ws-death1.md`) because both phases share an evidence-gathering goal. RC-DIRECT-STABILITY1 has a different goal (fix, not characterize) so it lives in a **new file**. Cross-references to the closed RC-DIRECT-WS-DEATH1 evidence appear inline.

---

## §1 — Proven facts (carry-forward from RC-DIRECT-WS-DEATH1 Phase 1+2)

These do not need re-proving inside this track. Every Arm in §4 and every verdict in §5 references them by short label.

| ID | Fact | Source |
|---|---|---|
| **F-Mode1** | On Tecno Wi-Fi, Direct WS dies at ~150 s median lifetime, after 8 successful ping/pongs, with the 9th relay-side Pong never reaching the device pcap inbound side. Return-path loss confirmed empirically by tshark on independent sessions. | `rc-direct-ws-death1.md` §14 + §32(a); PR #272 |
| **F-Mode2-pp0** | On Tecno Tele2 LTE, ~3 of ~39 deaths exhibit pp=0 sub-case: relay sends 1st Pong, device pcap shows 0 inbound TLS records around the anchor. Same return-path loss signature as F-Mode1 but earlier in the session. | `rc-direct-ws-death1.md` §14 + §32(b); PR #272 |
| **F-Mode2-pp1** | On Tecno Tele2 LTE, ~36 of ~39 deaths exhibit pp=1 sub-case: outbound 2nd-ping TLS payload visible on device pcap, relay TCP-acks it within 1 ms (0-byte TLS payload), but relay's WS layer logs `pings_received=1` only and closes with `Connection reset without closing handshake`. TCP-layer ambiguous — discriminating uplink IP-loss vs relay-side TLS/WS delivery stall requires TCP seq/ack-number deep-dive or server-side BPF. Parked. | `rc-direct-ws-death1.md` §32(c); PR #272 |
| **F-NotKtor** | Ktor adapter is not the proximal cause. Raw OkHttp `newWebSocket(...)` reproduces both modes with comparable lifetimes; removing the Ktor wrapper does not buy materially longer session. | `rc-direct-ws-death1.md` §16; PR #269 (Phase 1 closure) |
| **F-NotCount** | "OkHttp internal mis-handling — Pong frame arrives but is not counted" branch is refuted for every conclusively-classified death (all F-Mode1 + all F-Mode2-pp0). No captured death has inbound TLS records present at the expected anchor. | `rc-direct-ws-death1.md` §34; PR #272 |
| **F-CaddyAtMax** | The production Caddyfile (`deploy/Caddyfile:53-58`) is already at the most WS-friendly Caddy settings: `read_timeout 0`, `write_timeout 0`, `flush_interval -1`. There is no remaining Caddy parameter to tune within Caddy's documented WS proxy block that would help further. | `deploy/Caddyfile:53-58`; architect 2026-06-03 review of PR-2 mini-lock |
| **F-RelayInternal** | The relay container exposes port 8080 only on the internal Docker bridge (`expose: ["8080"]`, no `ports:` block — `deploy/docker-compose.yml:39-42`). Caddy is the sole TLS terminator + edge auth + rate limiter in front of the relay. The relay has no embedded TLS listener. | `deploy/docker-compose.yml:39-42`; security review 2026-06-03 |
| **F-AuthIntegrity** | Signed-challenge auth (ADR-027, relay `routes.rs:230-232` `is_pubkey_hex` 64-hex constraint) is integrity-based via Ed25519 over a one-shot nonce. Its security does not depend on TLS confidentiality — a plain-TCP/WS diagnostic path that preserves the auth protocol remains safe against replay or signature forgery during a short-window test. | ADR-027; security review 2026-06-03 §1 |
| **F-CIGuard** | `Inv-BypassIsLoopbackOnly` is enforced by a pre-merge CI gate (`.github/workflows/deploy-lint.yml`) that fails the build if any relay-service `ports:` entry binds to a non-loopback address. Shipped in PR #273 on master `6c923c39`. | PR #273; security review 2026-06-03 §3 + §5 |
| **F-Mode2-cadence-invariant** | On Tecno Tele2 LTE through production Caddy, Direct WS lifetime scales linearly with OkHttp `pingInterval` (lifetime ≈ 3 × ping_interval for subsequent sessions, ≈ 2 × ping_interval for the first session, observed at 10/15/20/30 s tested values), and the Mode 2 "first session 0 pong, then 1 pong" signature persists across all cadence values. **Cadence sensitivity exists, but only as detection timing — `pingInterval` changes WHEN OkHttp notices the already-broken path, not WHETHER the path breaks.** As a production fix lever, cadence is refuted. Production `RelayTransportFactory.kt:71 pingInterval(15_000L, MILLISECONDS)` is the right value and stays. | Arm C field matrix 2026-06-03 (`docs/tracks/rc-direct-stability1.md` §4 Arm C Outcome); memory `project_arm_c_lifetime_linear_in_interval_2026_06_04.md` |

---

## §2 — Refined hypothesis space — fix candidates

Each hypothesis below names a candidate mechanism that, if true, suggests a specific fix. Arms in §4 map 1-to-1 to these hypotheses.

| ID | Statement | If confirmed → fix path |
|---|---|---|
| **H-A — Caddy proxy interferes** | Caddy's WebSocket proxy or reverse-proxy hop materially degrades Direct WS lifetime, even at its WS-friendliest config (F-CaddyAtMax). A loopback bypass would show materially longer Direct WS lifetime. | If a loopback-bypassed WS holds ≥ 3× baseline on Tele2 (per §6 ship criterion), the production path needs a Caddy-side change or a Caddy-replacement transport. |
| **H-B — Caddy is innocent (control case)** | Caddy proxy is not the proximal kill mechanism. The bypass shows the same death rhythm as production. | Reframes the search: focus shifts to TCP/IP-layer behaviour (carrier NAT, MTU, radio power management, kernel retransmit interactions) — addressed by Arms C–F. |
| **H-C — Ping cadence interaction** | OkHttp's pingInterval value interacts with the carrier's idle-keepalive teardown window or radio sleep behaviour. A different cadence (10 s / 20 s / 30 s) produces measurably different lifetime. | If a specific cadence value materially extends session lifetime AND does not break authentication or message delivery, it becomes a candidate for a separate production-promotion PR with its own mini-lock per `Inv-OnlyDiagnosticCadenceChange`. |
| **H-D — Control-frame-only kill** | The kill mechanism is specific to WS control frames (Ping / Pong). Application data frames survive the same conditions. | Direct WS becomes viable on networks that kill silent connections if we maintain a periodic data-frame heartbeat — but only via app-layer Text/Binary frames, never via WS Ping (PR-H1e regression risk). |
| **H-E — Long-lived sockets structurally lose** | Any long-lived WS socket on the target network is doomed within an upper bound. A sequence of short-lived sockets, each refreshed proactively before its expected death, maintains realtime continuity. | Short-lived WS rotation becomes the production strategy; reconnect overhead pays itself back via UX continuity. Calls/voice continuity must be gated separately (Inv-RotationNotForCallSignaling). |
| **H-F — WebSocket is structurally wrong on this path** | None of A–E produces a path that meets the §6 ship criterion. WebSocket cannot be made reliable on this network class with any reasonable client-side / server-side change. | Direct realtime moves to an alternative protocol (SSE long-poll or HTTP long-poll) implemented as a separate relay track. RC-DIRECT-STABILITY1 closes with the verdict "Direct WS structurally unstable; 3.2b.1 adaptive validation becomes mandatory + alternative-transport track opens". |

The decision tree in §5 maps Arm A–F outcomes onto these hypotheses and into concrete next actions.

---

## §3 — Invariants enforced by this mini-lock (hard guards)

These survive every revision. Code review for any PR opened under this track rejects the diff if any invariant is violated.

| ID | Invariant | What it forbids |
|---|---|---|
| **Inv-NoProductionRegression** | No Arm in this track ships a change that measurably regresses delivery success ratio, message latency p95, or sustained Direct WS session lifetime versus the master baseline at the time the Arm's experiment was conducted. | Any Arm A-F PR that merges without recorded before/after delivery telemetry confirming non-regression. |
| **Inv-NoCallsVoiceTouch** | This track is messaging-signaling and Direct-WS-stability only. Calls signaling code (ADR-025), voice transport (REST media), `TransportCapabilitiesResolver` (PR-C1), `ConnectionUiState` notification text, and any calls/voice/media capability surface remain unchanged. | Any diff under this track that touches `services/calls/*`, `apps/android/src/androidMain/kotlin/phantom/android/calls/*`, `TransportCapabilitiesResolver.kt`, or `ConnectionUiState.kt`. |
| **Inv-OnlyDiagnosticCadenceChange** | Ping-interval variants explored in Arm C are encoded **only** in a new `BuildConfig.DEBUG_RC_DIRECT_PING_INTERVAL_MS` debug-only field consumed by the raw-OkHttp diagnostic arm class (sibling of `RcDirectArmB`). The production `RelayTransportFactory.kt:71` `pingInterval(15_000L, TimeUnit.MILLISECONDS)` line is **read-only** for the entire RC-DIRECT-STABILITY1 track. Any change to that production line requires a separate named PR with its own mini-lock. | Any diff that edits `RelayTransportFactory.kt:71` under this track. |
| **Inv-NoSpinningUntilEvidence** | 3.2b.1 adaptive validation design stays on hold (no design Council session, no code) until either (a) ≥ 1 Arm in this track ships a path that meets §6 ship criterion, in which case 3.2b.1 is deprioritised, OR (b) all Arms close without meeting §6, in which case 3.2b.1 becomes the mandatory next track. | Any 3.2b.1 design-note PR or code PR opened on the WS-HEALTH-STATE1 track while RC-DIRECT-STABILITY1 has Arms still open. |
| **Inv-BypassIsLoopbackOnly** (Arm A) | The Arm A diagnostic bypass port binding on the production VPS must use `127.0.0.1:PORT:8080` or `::1:PORT:8080` exclusively, never `0.0.0.0:PORT:8080` or unqualified `PORT:8080`. The diagnostic APK build flag `DEBUG_BYPASS_URL` must be absent from any non-debug build (pinned to `""` in the release block + `BuildConfig.DEBUG &&` runtime gate). Pre-merge enforcement: CI workflow `.github/workflows/deploy-lint.yml` (shipped in PR #273). | Any compose-file change that binds the relay service to a non-loopback host_ip. Any release-build APK with a non-empty `DEBUG_BYPASS_URL`. |
| **Inv-NoLanInNsc** | Throughout this track, `apps/android/src/androidMain/res/xml/network_security_config.xml` cleartext whitelist remains restricted to its current set (`10.0.2.2`, `localhost`, `127.0.0.1`). For physical-device Arm A testing, use an SSH tunnel from the dev machine to the VPS loopback rather than adding a LAN IP to the cleartext whitelist. | Any commit under this track that broadens the NSC cleartext whitelist. |
| **Inv-DataFrameNotControlFrame** (Arm D) | Arm D's data-frame heartbeat uses WS `Message::Text` or `Message::Binary` exclusively. No `Message::Ping` variants are sent from app code in any Arm of this track. Protects against repeating the PR-H1e regression (app-level Ping halved WS lifetime). | Any client or relay code under this track that emits a WS Ping frame from application code. |
| **Inv-RotationNotForCallSignaling** (Arm E) | Short-lived WS rotation (Arm E) does not fire while a call-signaling session is active. ADR-025 requires WS continuity during the call setup handshake; a rotation mid-handshake would drop signaling state. Gate Arm E behind a `state != CALL_ACTIVE` check before any proactive close. | Any Arm E code path that closes the WS during an active call-signaling state. |
| **Inv-RelayChangeNeedsItsOwnPR** (Arm D, Arm F) | Arms D and F require a relay-side production code addition (echo opcode for Arm D; SSE / long-poll route for Arm F). Each such relay change ships in a small dedicated relay PR **before** any Android diagnostic APK is built that depends on it. The relay PR must pass `Relay CI / build-test` and architect explicit ACK or grep-verified evidence per WORKING_RULES rule 9. | Any Android Arm D / F APK that ships before the matching relay-side endpoint exists in `services/relay/`. |

---

## §4 — Experimental arms

Six arms total. Order in §7 is cheap-first (server-side → client-side → alternative path). Each arm has its own per-arm acceptance criterion in §6 plus contributes to the cross-arm ship criterion.

### Arm A — Caddy bypass test (server-side, ~15 min VPS reconfig)

**Goal.** Probe H-A vs H-B by removing Caddy from the Direct WS path and measuring WS lifetime against the same Tecno + Tele2 / Wi-Fi conditions used in Phase 2.

**Setup.**

1. Operator edits `deploy/docker-compose.yml` to add `ports: ["127.0.0.1:8081:8080"]` to the relay service block (landed by PR-3a). The CI gate from PR #273 enforces the loopback prefix at merge time. The comment alongside the new line documents the revert TODO.
2. Operator runs `docker compose up -d --force-recreate relay` on the VPS. ~5 s container restart; existing WS users drop and reconnect via REST fallback per `RestStateMachine`.
3. Operator establishes the **two-command bridge** between the test device and the VPS loopback. Both commands are required — if only `adb reverse` is set up, Tecno's `ws://127.0.0.1:8081/ws` falls into Windows localhost (where nothing listens), not the VPS loopback:

   ```powershell
   # 1. Hold an SSH local-forward (background, no remote shell) from the dev
   #    machine to the VPS loopback. The `-N` flag tells SSH to forward only.
   ssh -N -L 8081:127.0.0.1:8081 phantom@relay.phntm.pro

   # 2. (Physical Tecno only — skip for emulator.) Forward the on-device
   #    127.0.0.1:8081 over USB to the dev machine's 127.0.0.1:8081, which
   #    in turn is the SSH-tunnel endpoint from step 1.
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 103603734A004351 reverse tcp:8081 tcp:8081
   ```

   After both commands the data path is:
   `Tecno ws://127.0.0.1:8081/ws → adb reverse over USB → dev-machine 127.0.0.1:8081 → ssh tunnel → VPS 127.0.0.1:8081 → relay container 8080`.
4. I build a diagnostic Android APK `phantom-bypass-arm-a.apk` with `BuildConfig.DEBUG_BYPASS_URL` set per the device-target rules below. The new diagnostic class `RcDirectArmA` is a sibling of `RcDirectArmB` (Phase 1) — same raw OkHttp `newWebSocket(...)` pattern, same WebSocketListener telemetry shape, but pointed at the bypass URL.

   **Device-target rules (locked 2026-06-03 to close `Inv-NoLanInNsc` conflict per PR #274 review):**

   - **Emulator Arm A** — `DEBUG_BYPASS_URL = "ws://10.0.2.2:8081/ws"` is allowed. `10.0.2.2` is the Android emulator's host-loopback alias and is already in `network_security_config.xml` cleartext whitelist.
   - **Physical Tecno Arm A** — the bypass URL **must resolve to a hostname / IP already in the current `network_security_config.xml` cleartext whitelist** (`localhost` or `127.0.0.1`). A LAN IP of the dev machine is **forbidden** because adding it to NSC would violate `Inv-NoLanInNsc`. The only approved physical-device pattern: an on-device `adb reverse tcp:8081 tcp:8081` so the Tecno reaches its own loopback (`ws://127.0.0.1:8081/ws`), and `adb reverse` forwards the connection back over USB to the dev machine, which in turn holds the SSH tunnel to the VPS loopback. This preserves on-device `localhost` / `127.0.0.1` semantics — no NSC change.
   - **Any other physical-device bypass pattern** (direct LAN address, mDNS hostname, Wi-Fi-routed reach) **requires a separate pre-approved design amendment** to this mini-lock §4 Arm A before the diagnostic APK is built. `network_security_config.xml` remains read-only for the entire track per `Inv-NoLanInNsc`.
5. After the field test, the operator removes the `ports:` line + restarts the container. Total revert window ~5 min.

**Auth.** Unchanged from production. The diagnostic class performs the production `/auth/challenge?identity=<hex>` GET + Ed25519 sign + WS upgrade with `?id=&signing_pubkey=&challenge=&signature=`. Auth security held by F-AuthIntegrity even without TLS — see ADR-028 §"Security baseline for plain-WS diagnostic paths" for the rationale.

**Cost.** ~80-100 LOC new client code (`RcDirectArmA.kt` plus AppContainer + PhantomMessagingService wire-up, mirroring the Arm B pattern). One compose-file two-line delta (revertible). One Android APK build with the new debug-only `DEBUG_BYPASS_URL` field.

**Discriminator.** If Arm A's median session lifetime on Tele2 LTE ≥ 3× the v11 Arm A Tele2 baseline (~90 s vs ~31 s baseline), **H-A confirmed** — Caddy is the proximal kill mechanism on at least one mode. If lifetime is within ±25 % of v11 Arm A baseline, **H-B confirmed** — Caddy is innocent, focus moves to lower-layer experiments.

**Outcome (PR #276 emulator smoke + Tecno field test 2026-06-03):** **PARTIAL / PASS for loopback-path stability, but the discriminator is architecturally undecidable.** Recorded findings:

- **Wire-up proven.** Emulator smoke + Tecno field test both produced the expected `service_short_circuit` → `armed` → `session_start` → `auth_done` → `ws_open` sequence. `Inv-ParallelArmIsolation` held on both runs (production `session_summary` = 0, `ws_ping_timeout_diag` = 0, `RC_DIRECT_ARM_B_*` = 0).
- **New positive empirical finding.** Tecno field test (15 min 50 s capture, APK SHA `ab6c24f5...`) opened a single WS at `~16 s` after start and held it to capture end — **~15 min 33 s sustained, zero `SocketTimeoutException: sent ping but didn't receive pong` events**. This is the first empirical evidence that the relay binary + raw OkHttp + relay-side WS protocol layer **can sustain a WS for 15+ minutes when the data path is loopback** (Tecno localhost → USB / ADB → Windows → SSH tunnel → VPS loopback → relay). v11 Arm A Tele2 baseline (~31 s) **did not reproduce** through the tunnel.
- **Architectural limitation that prevents H-A vs H-B verdict.** The `adb reverse` + `ssh -N -L` two-command bridge routes the WS payload over USB and SSH, **not over Tele2 LTE radio**. The experiment proves "loopback path is stable" but cannot prove or refute "Caddy is the kill mechanism on Tele2". Cannot assign a §23-equivalent decision-tree row for H-A vs H-B from this evidence.
- **Decision per PR #276 + field-test review:** the §6 ship criterion ("zero ping-timeouts in 15-min capture") is met **for the loopback path** but the captured network class is loopback, not the target problematic network (Tele2 LTE). Recorded as PARTIAL for the track's purpose. RC-DIRECT-STABILITY1 continues to Arms C / D / E which exercise the production path through Tele2 + Caddy naturally because the diagnostic is on the device side (not the path side).
- **Arm A.2 — promoted to active arm.** Originally deferred to §9 parking lot 2026-06-03 as conditional fallback after Arms C / D / E. Promoted ahead of Arm E by PR #284 on 2026-06-04 after the Arm D outcome (PR #283 squash `601d9d8d`) revealed control/application delivery asymmetry at the relay application layer. Full scope, security mini-lock, and W/X/Y discriminator now live in the **Arm A.2 subsection below**.
- **Lesson learned (memory `feedback_diagnostic_design_must_isolate_one_variable.md`):** any future "remove component X from path" diagnostic must draw the full data path first and verify the experiment exercises the target network. Tunnel / loopback designs remove X AND the target network simultaneously, making the verdict architecturally undecidable.

### Arm A.2 — Public non-Caddy TLS bypass (server-side overlay + client diagnostic)

**Goal.** Probe whether the Caddy edge path (TLS implementation + HTTP framing + WS proxy layer, taken together) is in the kill chain by routing the diagnostic WS through a separate public TLS terminator that performs ONLY TLS unwrap and forwards raw TCP to the relay. Promoted from §9 parking lot to active arm by PR #284 on 2026-06-04, ahead of Arm E, after the Arm D outcome (PR #283 squash `601d9d8d`) revealed control/application delivery asymmetry at the relay application layer.

**Why before Arm E.** Arm E (WS rotation) extends session lifetime by proactive reconnect, which presupposes the next WS session would be healthy. The Arm D asymmetry (device sends Ping AND Text at the same instant; relay sees Ping but not Text) makes that presupposition unsafe — rotating to a fresh WS faces the same uplink-Text-fails-immediately failure mode on the new connection. Arm A.2 discriminates the layer at which the asymmetry emerges (Caddy edge vs below-Caddy) before deciding whether Arm E remains a viable next step.

**Refined scope (locked 2026-06-04 after structured trade-off review of four TLS terminator candidates):**

- **Primary TLS terminator candidate: `stunnel`.** Pure TLS unwrap → raw TCP forward to the relay container on the Docker compose network (`connect = relay:8080`). `127.0.0.1:8080` is **only** valid for a host-network / native stunnel deployment and must be explicitly documented in PR-8a if that path is taken. No HTTP awareness, no WS-aware proxying, no Upgrade-header parsing — relay receives the HTTP/WS upgrade directly after stunnel decrypts. Reasons: minimises variables by removing Caddy's TLS + HTTP/WS proxy layer while adding only a TLS unwrap + raw TCP forwarder, smallest new public attack surface (single-purpose TLS terminator with low historical CVE volume), simplest config and revert.
- **Fallback candidate: `HAProxy`.** Activated only if stunnel reveals a hard blocker during design or implementation: (a) Caddy cert/key sharing read-only with stunnel is structurally impossible, (b) relay code is found to genuinely depend on proxy headers (X-Forwarded-For / X-Real-IP / Forwarded), (c) stunnel cannot provide the TLS mode required (e.g., TLS 1.3-only with specific cipher suite). Fallback escalation is mechanical — does not require re-running the structured trade-off review.
- **Endpoint shape:** public `wss://relay.phntm.pro:8444/ws`. Reachable directly from the device over Tele2 LTE radio. **NO** loopback, **NO** `adb reverse`, **NO** SSH tunnel. Per `feedback_diagnostic_design_must_isolate_one_variable.md` and the Arm A 2026-06-03 lesson: bypassing the target network voids the diagnostic and must be avoided. Host port `:8444` — NOT `:8443` — because `:8443` is already bound by the production `phantom-xray` REALITY+WSS container (Stage 5E, 2026-05-07); see §4 Arm A.2 PR-8a implementation record port-choice note for the deploy-time discovery.
- **Time-boxed exposure.** stunnel container is brought up only during the Arm A.2 capture window and torn down immediately after. Configuration lives in a separate `deploy/docker-compose.armA2.yml` overlay file, NOT merged into the persistent `deploy/docker-compose.yml`. The implementation PR ships the overlay file and the operator runbook (open / verify / capture / close).
- **No production traffic promotion.** Debug APK only via a new BuildConfig field analogous to `DEBUG_BYPASS_URL`. Production `BuildConfig.RELAY_URL` (`wss://relay.phntm.pro/ws` through Caddy) and `RelayTransportFactory.kt:71 pingInterval(15_000L, MILLISECONDS)` remain read-only for the entire Arm A.2 lifecycle. No user traffic routes through `:8444` ever.
- **Heartbeat echo flag re-enabled for the capture window.** The same `RELAY_ENABLE_HEARTBEAT_ECHO=1` from Arm D is re-enabled on the VPS `.env` for the Arm A.2 capture window only. Operator runbook in the implementation PR specifies enable-before-capture and revert-after-capture exactly. The Arm A.2 client class sends both OkHttp control Ping (via `pingInterval`) and application Text heartbeats (via Arm D-style sender) so the discriminator can read both frame classes at the relay application layer.

**Setup.**

1. **PR-8a (server-side overlay + runbook docs).** New `deploy/docker-compose.armA2.yml` with the stunnel service. Mounts Caddy's cert volume read-only at the path stunnel expects. Binds `:8444` on the public interface (host-side — `:8443` is held by production `phantom-xray`; container-internal still `:8443`). **`connect = relay:8080` inside the Docker compose network; `127.0.0.1:8080` is allowed only for a host-network / native stunnel deployment and must be explicitly documented in PR-8a.** Operator runbook in PR commit body: deploy step + reachability verification (curl HTTPS handshake + manual WS upgrade probe against `:8444`) + revert step + cert-rotation handling note for the unlikely case where Let's Encrypt renewal fires inside the test window.
2. **VPS deploy step (operator-owned).** `docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.armA2.yml up -d stunnel` opens the bypass; the matching `down stunnel` closes it. Plus `.env` flip for `RELAY_ENABLE_HEARTBEAT_ECHO=1` enable-and-revert.
3. **PR-8b (Android diagnostic class).** New `RcDirectArmA2` class near-cloning `RcDirectArmD` with the bypass URL hardcoded to `wss://relay.phntm.pro:8444/ws`. Gated by new `BuildConfig.DEBUG_RC_DIRECT_ARM_A2_URL` field (debug-only, release-pinned `"0"`). AppContainer + Service wire-up under precedence A → A.2 → B → C → D → production. Sends both OkHttp control Ping AND application Text heartbeats — both frame classes are needed to discriminate W vs X vs Y.
4. **Field run.** Tele2 LTE 15-min capture on Tecno through the new endpoint. Same logcat collection format as Arm A / C / D. Both `arm-a2-tecno-tele2.log` (client) and `arm-a2-relay.log` (server) captured with timestamps inside the open-stunnel window.

**Cost.** ~30 LOC compose overlay + ~10-line stunnel config + ~60 LOC client (`RcDirectArmA2` + BuildConfig + AppContainer + Service). 30 min VPS reconfig + 15 min field test. Larger than Arm B / C but smaller than Arm A (which had its own loopback bypass compose work).

**Discriminator — three locked outcomes (W / X / Y).** Mapped onto §5 decision tree as new rows.

- **W (stunnel sustains the WS).** Direct WS sessions on `:8444` hold ≥ 3× the v11 Arm A Tele2 baseline (~90 s vs ~31 s baseline) without the Mode 2 "first session 0 pong, then 1 pong" signature, **AND** application Text echo round-trips succeed (relay logs `event=heartbeat_echo_received` per outbound Text frame). **Verdict: Caddy edge path is in the kill chain or contributes.** Does NOT prove "TLS innocent" — TLS is still present in stunnel, only the specific Caddy TLS + HTTP + WS-proxy layer is removed. **Trigger:** open `RC-CADDY-FIX1` track (replace Caddy WS edge with a properly-engineered TLS + HTTP/WS terminator stack for production, OR investigate Caddy-specific tuning candidates not surfaced in Arm B).
- **X (asymmetry persists — Ping survives, Text dies).** Same control/application delivery asymmetry as Arm D: relay logs `ws_protocol_ping_received` per session but logs zero `event=heartbeat_echo_received`. **Verdict: Caddy loses priority as the proximal cause; the asymmetry origin is below the Caddy edge or in a layer that Caddy and stunnel share** (carrier path inspection, WebSocket-over-TLS pattern in general, OkHttp egress behaviour on the device side, or interaction across these layers). **Trigger:** open below-edge / carrier-side investigation track; Direct WS realtime-via-Caddy becomes architectural concern; 3.2b.1 escalates from "parked" to "needed". Does NOT single-attribute the kill to any of the four hypotheses from the Arm D outcome four-hypothesis open set.
- **Y (everything dies — Mode 2 signature unchanged through `:8444`).** Same death rhythm as production through stunnel. **Verdict: Caddy strongly loses priority as root; the kill is structural at carrier / path / lower-layer level.** Direct WS as realtime messenger transport on Tele2 LTE is structurally hard. **Trigger:** uplift realtime out of Direct WS to HTTPS long-poll / SSE / REST polling for messaging + WebRTC + TURN for calls per ADR-028 4-layer architecture intent; Arm E (rotation) does not unlock a different outcome and is deprecated; open Arm F parking-lot mini-lock as the next track. Does NOT prove "TLS broken" or "WebSocket broken" — shifts probability mass toward lower layers without identifying which one.

**Wording bound.** None of W / X / Y attribute the kill to a specific lower layer with confidence higher than the evidence supports. W reads as "Caddy edge path is in the kill chain or contributes", not "Caddy is the killer" and not "TLS is innocent". X reads as "asymmetry origin below the Caddy edge OR in a layer Caddy and stunnel share", not "carrier discriminates". Y reads as "Caddy loses priority; problem is below" without naming the specific lower layer. This wording bound carries forward from the Arm D outcome four-hypothesis open set (OkHttp writer-side / Caddy-TLS / carrier / interaction).

**Mini-lock hard gates (P2 — verified in the implementation PR before deploy).**

1. **Cert sourcing strategy.** stunnel reads Caddy's cert + key files from Caddy's cert volume, mounted read-only at the path stunnel expects. Cert/key bytes do NOT enter git. The exact path on the VPS is determined by inspecting the Caddy data volume in the implementation PR. If sharing read-only is structurally impossible (e.g., Caddy stores certs in an internal database format rather than PEM), HAProxy fallback triggers, OR a separate ACME client is added under a mini-lock amendment (cost increase + new attack surface review).
2. **Relay proxy header dependency check.** Run `grep -rIn 'X-Forwarded-For\|X-Real-IP\|Forwarded' services/relay/src/` BEFORE deploy. If matches found, evaluate impact: if rate-limiting or auth genuinely depend on these headers, HAProxy fallback triggers (HAProxy forwards proper proxy headers; stunnel does not); otherwise document the gap and proceed.
3. **Time-box hard limit.** stunnel container up-time bounded by capture window — operator runbook specifies open / verify / capture / tear-down sequence with explicit timestamps. No persistent VPS exposure of `:8444` outside test windows.
4. **No production traffic promotion.** Debug-flag-gated client only. Production `BuildConfig.RELAY_URL` unchanged. `RelayTransportFactory.kt:71` unchanged. No `:8444` URL anywhere in production code paths. `Inv-OnlyDiagnosticCadenceChange` (§3) extends in spirit to Arm A.2 — production transport remains read-only.
5. **Security mini-lock.** Public TLS only (TLS 1.2+ minimum, prefer TLS 1.3). No cleartext on `:8444` ever. Auth (signed-challenge per ADR-027) unchanged — auth is in-payload, not endpoint-specific. Connection cap + per-IP rate-limit at the stunnel level if available, else at the relay level (relay's existing rate-limit applies post-stunnel-unwrap). Explicit deploy / verify / revert runbook in the implementation PR commit body. AGPL compliance preserved — stunnel is OSI-approved GPL-2.0; relay binary unchanged.

**Memory pointer.** Vladislav-locked hard-points trail preserved in `project_next_session_arm_a2_scope_2026_06_04.md`; superseded by PR #284's mini-lock landing on master.

**PR-8a server-side implementation record (this PR, 2026-06-04).** Server-side stunnel overlay + config + operator runbook. Two new files in `deploy/` + this implementation record subsection in track doc + PROJECT_LOG/MASTER_TIMELINE bump. Zero application or relay code change.

- **Pre-code Gate 1 — relay proxy header dependency: PASS.** Ran `grep -rIn 'X-Forwarded-For\|X-Real-IP\|Forwarded' services/relay/src/` on master `f7af95d8`. Zero matches. Relay does not depend on proxy headers for auth (signed-challenge per ADR-027 is in-payload), rate-limit, or any other policy. stunnel raw TCP forward to `relay:8080` is safe — relay receives the unmodified TCP stream from the client device after stunnel decrypts. HAProxy fallback per §4 Arm A.2 Refined scope rule (b) NOT triggered.
- **Pre-code Gate 2 — Caddy cert format and path: PASS.** SSH-verified on VPS 2026-06-04:
  - Caddy cert volume layout standard: `/data/caddy/certificates/acme-v02.api.letsencrypt.org-directory/relay.phntm.pro/relay.phntm.pro.{crt,key,json}`
  - Cert file is PEM: `-----BEGIN CERTIFICATE-----`
  - Key file is PEM EC: `-----BEGIN EC PRIVATE KEY-----`
  - Caddy stores cert + key as standalone PEM files, NOT internal DB format — read-only volume sharing with stunnel is structurally possible. HAProxy fallback per §4 Arm A.2 Refined scope rule (a) NOT triggered.
- **Pre-code bonus check — compose network name: confirmed.** SSH-verified on VPS 2026-06-04: actual Docker network name is `deploy_phantom-internal` (default compose project name `deploy` prefix added by Docker). `phantom-relay` and `phantom-caddy` both attached at IPs `172.18.0.2` and `172.18.0.6` respectively. stunnel attaches via overlay's `networks: [phantom-internal]` reference — compose merges across `-f` files and resolves the reference to the same actual network. Docker DNS resolves `relay` → relay container IP from inside the stunnel container.

- **Deploy-time finding — `clients = 50` dropped from stunnel config (2026-06-05).** After the TLS-options-cleanup fixup (#288) landed, retried `compose up -d stunnel-arm-a2` succeeded at the build and TLS-option parse steps but the container exited again at config-parse:
  ```
  [.] Reading configuration from file /etc/stunnel/stunnel.conf
  [.] Initializing service [relay-arm-a2]
  [!] /etc/stunnel/stunnel.conf:115: "clients = 50": Specified option name is not valid here
  [!] Configuration failed
  ```
  Root cause: `clients` is a **global** stunnel directive, not a per-service one. The original `stunnel.armA2.conf` from PR-8a (#285) placed it inside the `[relay-arm-a2]` service section — a scope bug that the earlier deploy failures (entrypoint + TLS-options syntax) masked until this point. Two options: (A) relocate `clients = 50` to the global section (above `[relay-arm-a2]`); (B) drop the directive entirely and rely on the substitute defenses already in the diagnostic posture. **Chose (B).** The Security mini-lock clause "Connection cap + per-IP rate-limit at the stunnel level if available, else at the relay level" falls back to the second branch cleanly: relay's existing rate-limit applies post-stunnel-unwrap, plus the diagnostic-only safeguards (time-boxed exposure / TLS-only endpoint / signed-challenge relay auth / `restart: "no"` / explicit `compose stop` + `compose rm -f` teardown / non-production `:8444` port) provide ample protection for a 15-min capture window. Less stunnel config = less risk of a fifth deploy-time surprise. If edge-level connection limiting is needed later, implement it explicitly via HAProxy, nftables, or relay-side limits under a separate mini-lock.

  Cumulative diagnostic-design-lesson (fourth instance): the PR-8a config trail has now caught one finding per fixup PR (#286 port availability + #287 image entrypoint behaviour + #288 TLS-option syntax + this PR directive-scope). All four are stunnel/OpenSSL/Docker/version-dependent and require either pre-deploy smoke-testing against the target image (`docker run --rm -v "$PWD/stunnel.armA2.conf:/etc/stunnel/stunnel.conf:ro" phantom-stunnel-arm-a2:latest -test` or equivalent) or first-deploy validation before any scope-lock PR declares them. The PR-8b Android client work that follows is **gated on the operator confirming a green stunnel startup** (`Configuration successful` + `Service [relay-arm-a2] (FD=...)` in logs) before any APK is built — same gate text as #288's bullet, restated here because this finding extends rather than supersedes that gate.

- **Deploy-time finding — TLS `options = NO_*` cleanup (2026-06-05).** After the Alpine-build fixup (#287) landed, the container built and started successfully — but stunnel exited at config-parse time:
  ```
  [.] stunnel 5.72 on x86_64-alpine-linux-musl platform
  [.] Compiled with OpenSSL 3.3.0 9 Apr 2024
  [.] Running  with OpenSSL 3.3.7 7 Apr 2026
  [.] Reading configuration from file /etc/stunnel/stunnel.conf
  [.] Initializing service [relay-arm-a2]
  [!] /etc/stunnel/stunnel.conf:93: "options = NO_TLSv1.1": Illegal TLS option
  [!] Configuration failed
  ```
  Root cause: stunnel option syntax for TLS-1.1 disable is `NO_TLSv1_1` (underscore), not `NO_TLSv1.1` (dot). The original `stunnel.armA2.conf` from PR-8a (#285) shipped the dot form — a syntax bug that the dweomer-image entrypoint failure (#287) masked until the Alpine-built container actually reached the config-parse phase. Additionally, even the corrected `NO_SSLv2` / `NO_SSLv3` / `NO_TLSv1` / `NO_TLSv1_1` option flags are largely redundant with the modern `sslVersionMin = TLSv1.2` + `sslVersionMax = TLSv1.3` directives also present in the config, and OpenSSL 3.x has removed several legacy option constants entirely (the next NO_* line would likely have been the next minefield). **Removed all four `options = NO_*` lines from `deploy/stunnel.armA2.conf`. Kept `sslVersionMin = TLSv1.2` + `sslVersionMax = TLSv1.3` which alone enforce the §4 Arm A.2 Security mini-lock "TLS 1.2+ minimum, prefer TLS 1.3" rule cleanly.** No security regression — the publicly-negotiated minimum is still TLS 1.2.

  Diagnostic-design-lesson recurrence (third instance): future stunnel/OpenSSL config locks should be smoke-tested against the actual target version pair before scope-lock, not just paper-reviewed. Cumulative lesson set across PR-8a deploy fixups (#286 / #287 / this PR): port availability + image entrypoint behaviour + TLS-option syntax compatibility are all VPS-state-or-version-dependent and must be verified before a scope-lock PR declares them. The PR-8b Android client work that follows this implementation record subsection is gated on the operator confirming a green stunnel startup (`Configuration successful` + `Service [relay-arm-a2] (FD=...)` in logs) before any APK is built.

- **Deploy-time finding — image switched from dweomer to Alpine-built (2026-06-05).** After the port fixup (#286) landed, retried deploy attempt of PR-8a failed at Step 3 differently: container started but immediately exited with status 1 and `STUNNEL_SERVICE=` empty / `STUNNEL_ACCEPT=` empty / `STUNNEL_CONNECT=` empty in logs. Diagnostic on VPS:
  ```
  $ docker ps -a --filter "name=phantom-stunnel-arm-a2" --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'
  NAMES                    STATUS                     IMAGE
  phantom-stunnel-arm-a2   Exited (1) 2 minutes ago   c46e11e6cc13
  ```
  Root cause: the `dweomer/stunnel` image has a custom entrypoint script that GENERATES `/etc/stunnel/stunnel.conf` from `STUNNEL_SERVICE` / `STUNNEL_ACCEPT` / `STUNNEL_CONNECT` env vars at startup, ignoring (or overwriting) any bind-mounted `/etc/stunnel/stunnel.conf`. Our config bind-mount (`./stunnel.armA2.conf:/etc/stunnel/stunnel.conf:ro`) was incompatible with that flow. We had two viable fixes: (a) supply the env vars and lose the §4 Arm A.2 Security mini-lock TLS hardening + cipher list + clients cap (NOT acceptable per the mini-lock); (b) override the entrypoint to bypass dweomer's init script (fragile — depends on image internals); (c) build stunnel from Alpine ourselves. **Chose (c) — minimal Alpine + stunnel apk via `deploy/stunnel-armA2/Dockerfile`, Alpine base pinned by digest.** Removes the entrypoint-magic risk class entirely. Reduces supply-chain trust to Alpine maintainers only (vs Alpine + dweomer). Builds locally on first `compose up` (~10-20 s) and caches the result.

  Diagnostic-design-lesson recurrence: when reviewing a third-party container image for use in a security-sensitive overlay, verify its entrypoint behaviour (`docker inspect` or read its Dockerfile / README) before locking the image choice. Memory entry candidate: future image-choice trade-off reviews should explicitly answer "does this image's entrypoint accept a bind-mounted config, or does it generate one from env vars at startup?" as a P1 question.

- **Deploy-time finding — host port forced to `:8444` (2026-06-05).** Initial deploy attempt of PR-8a (master `b4fc4cd4`) failed at Step 3 with `Bind for 0.0.0.0:8443 failed: port is already allocated`. Diagnostic on VPS:
  ```
  $ sudo ss -tlnp | grep ':8443'
  LISTEN 0 4096 0.0.0.0:8443 0.0.0.0:* users:(("docker-proxy",pid=910161,fd=8))
  LISTEN 0 4096    [::]:8443    [::]:* users:(("docker-proxy",pid=910167,fd=8))
  $ docker ps --format 'table {{.Names}}\t{{.Ports}}' | grep 8443
  phantom-xray   0.0.0.0:8443->8443/tcp, [::]:8443->8443/tcp
  ```
  Port `:8443` is bound by the production `phantom-xray` container (Stage 5E REALITY+WSS endpoint, deployed 2026-05-07 per memory `project_stage5e_xray_success_2026_05_07.md`; load-bearing transport for RU users via TSPU 16-KB curtain bypass). The PR #284 scope lock declared `:8443` as the Arm A.2 endpoint shape without VPS-state verification — port choice was undertested at scope time. Bypass host port changed to `:8444` (verified free on VPS via `sudo ss -tlnp | grep ':8444'` returning empty). Inside the stunnel container the `accept = 0.0.0.0:8443` directive stays — only the Docker port-mapping is `8444:8443` (host:container) so xray's `:8443` is untouched. Per the diagnostic-design lesson in `feedback_diagnostic_design_must_isolate_one_variable.md`: future scope-lock PRs that declare a host port should grep-verify or SSH-verify the port is free on the target VPS before merge. This finding does not change W/X/Y semantics — the discriminator reads the same against either host port; only the URL the device + curl probe target changes from `:8443/ws` to `:8444/ws`.
- **Files shipped:**
  - `deploy/stunnel-armA2/Dockerfile` — minimal stunnel container built from `alpine:3.20@sha256:d9e853...` (digest-pinned for reproducibility) + `apk add --no-cache stunnel` + `ENTRYPOINT ["stunnel", "/etc/stunnel/stunnel.conf"]`. Replaces the previous `dweomer/stunnel@sha256:c46e11...` image choice after PR-8a deploy attempt 2026-06-05 revealed that dweomer's entrypoint script generates `stunnel.conf` from `STUNNEL_SERVICE/ACCEPT/CONNECT` env vars and ignores a bind-mounted `/etc/stunnel/stunnel.conf` — incompatible with the §4 Arm A.2 Security mini-lock TLS hardening + cipher list shipped in `stunnel.armA2.conf`. Building from Alpine removes the entrypoint-magic class of risk + reduces supply-chain trust to Alpine maintainers only.
  - `deploy/docker-compose.armA2.yml` — overlay file. Service `stunnel-arm-a2`, container `phantom-stunnel-arm-a2`, **`build: ./stunnel-armA2`** (local build from the Dockerfile above; first `compose up` triggers ~10-20 s build, subsequent ups use cached `phantom-stunnel-arm-a2:latest` tag), `restart: "no"` (time-boxed), ports `8444:8443` (host `:8444` → container `:8443`; host port forced to `:8444` because `:8443` is held by production `phantom-xray` — see PR-8a deploy finding note below), volumes `caddy-data:/data:ro` + `./stunnel.armA2.conf:/etc/stunnel/stunnel.conf:ro`, network `phantom-internal`, depends_on `relay`, security posture `cap_drop ALL` + `no-new-privileges` + `read_only` rootfs + tmpfs `/tmp:4m`.
  - `deploy/stunnel.armA2.conf` — stunnel config. `[relay-arm-a2]` service block, `accept 0.0.0.0:8443`, `connect relay:8080`, cert + key paths under `/data/caddy/certificates/acme-v02.api.letsencrypt.org-directory/relay.phntm.pro/`, TLS 1.2 minimum + 1.3 preferred via `sslVersionMin/Max` directives, strong cipher suites only (TLS 1.3 AES-256-GCM / ChaCha20-Poly1305 / AES-128-GCM; TLS 1.2 HIGH excluding aNULL/eNULL/EXPORT/DES/MD5/PSK/RC4), `pid =` empty (no pid file — read_only rootfs collides with default `/var/run/stunnel.pid` write attempt). No stunnel-level `clients` connection cap — fell back to relay-side rate-limit + diagnostic-only safeguards (time-box, TLS-only, signed-challenge relay auth, `restart: "no"`, explicit teardown, non-production port `:8444`) after the directive scope-bug surfaced in the PR-8a clients-drop fixup (#289).

**Operator runbook (open / verify / capture / revert) — Vladislav-owned.**

```bash
# Pre-requisites on VPS:
ssh phantom@relay.phntm.pro
cd /home/phantom/Phantom/deploy
git pull origin master

# Step 1: Enable heartbeat-echo flag for the capture window only.
# Idempotent: if the line already exists (e.g., a previous capture window
# left it), update it in place; otherwise append. Avoids duplicate lines
# accumulating across multiple Arm A.2 runs.
grep -q '^RELAY_ENABLE_HEARTBEAT_ECHO=' .env \
  && sed -i 's/^RELAY_ENABLE_HEARTBEAT_ECHO=.*/RELAY_ENABLE_HEARTBEAT_ECHO=1/' .env \
  || printf '\nRELAY_ENABLE_HEARTBEAT_ECHO=1\n' >> .env
grep RELAY_ENABLE_HEARTBEAT_ECHO .env  # verify exactly one line, set to 1

# Step 2: Recreate relay container so it picks up the flag.
docker compose up -d --force-recreate relay
sleep 3
docker logs phantom-relay 2>&1 | grep "heartbeat_echo_enabled" | tail -1
# Expect: heartbeat_echo_enabled=true in the "relay feature flags" line

# Step 3: Bring up stunnel-arm-a2 via overlay. Operator is already
# in /home/phantom/Phantom/deploy so the file paths are unprefixed.
docker compose \
  -f docker-compose.yml \
  -f docker-compose.armA2.yml \
  up -d stunnel-arm-a2
sleep 3
docker logs phantom-stunnel-arm-a2 2>&1 | tail -10
# Expect: "Configuration successful" + "Service [relay-arm-a2] (FD=*)" lines

# Step 4: Reachability verify (TLS handshake + WS upgrade probe).
curl -v --connect-timeout 5 \
  -H "Connection: Upgrade" \
  -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  https://relay.phntm.pro:8444/ws 2>&1 | head -30
# Expect: TLS handshake success + HTTP/1.1 101 Switching Protocols (or
# 400 Bad Request if relay's WS handler rejects an unauthed probe — both
# indicate stunnel successfully terminated TLS and forwarded to relay).

# Step 5: Run the Arm A.2 PR-8b diagnostic APK field capture window
# (when PR-8b ships). Same logcat collection format as Arm D, plus a
# parallel docker logs capture for the relay heartbeat_echo lines.

# Step 6: After capture window completes, tear down stunnel-arm-a2.
# Explicit stop + rm pair — `compose rm -fs` is not consistently
# supported across compose versions and ambiguous for a public TLS
# surface. Stop first (signals SIGTERM, container exits cleanly),
# then remove.
docker compose \
  -f docker-compose.yml \
  -f docker-compose.armA2.yml \
  stop stunnel-arm-a2
docker compose \
  -f docker-compose.yml \
  -f docker-compose.armA2.yml \
  rm -f stunnel-arm-a2
docker ps --filter "name=phantom-stunnel-arm-a2" --format '{{.Names}}'
# Expect: empty output (container gone)

# Step 7: Revert heartbeat-echo flag.
sed -i '/^RELAY_ENABLE_HEARTBEAT_ECHO=/d' .env
grep RELAY_ENABLE_HEARTBEAT_ECHO .env  # expect empty
docker compose up -d --force-recreate relay
sleep 3
docker logs phantom-relay 2>&1 | grep "heartbeat_echo_enabled" | tail -1
# Expect: heartbeat_echo_enabled=false

# Step 8: Confirm relay healthy on production path.
curl -sS https://relay.phntm.pro/health
# Expect: {"status":"ok"}
```

**Cert-rotation handling note.** Let's Encrypt cert renewal cycle is ~60 days; production cert rotation is asynchronous and Caddy-managed. If renewal happens to fire inside an open Arm A.2 capture window, Caddy writes new PEM files to `/data/caddy/certificates/.../`. stunnel will NOT pick them up automatically — stunnel reads cert + key at startup and caches them. Two mitigations: (a) operator schedules capture windows away from anticipated renewal events (visible via `docker exec phantom-caddy caddy list-certificates`), (b) if a renewal does fire mid-window, `docker restart phantom-stunnel-arm-a2` re-reads the cert without disturbing relay. The window is short enough (~15 min) that mid-window renewals are unlikely in practice.

**Alpine-pin refresh handling note.** The stunnel image is built locally from `deploy/stunnel-armA2/Dockerfile` with the Alpine base pinned by digest (`alpine:3.20@sha256:d9e853...`), not by tag, so the diagnostic endpoint is reproducible across deploys. To refresh the Alpine pin when a security-relevant Alpine / stunnel / OpenSSL update lands:

```bash
# Query the current digest of alpine:3.20:
TOKEN=$(curl -s 'https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/alpine:pull' | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
curl -sI -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.docker.distribution.manifest.v2+json,application/vnd.oci.image.index.v1+json,application/vnd.oci.image.manifest.v1+json" \
  "https://registry-1.docker.io/v2/library/alpine/manifests/3.20" \
  | grep -i docker-content-digest
```

Update the `FROM` line in `deploy/stunnel-armA2/Dockerfile` with the new digest, commit as a separate small PR, then redeploy via the runbook above. Operator may also need to force a rebuild with `docker compose -f docker-compose.yml -f docker-compose.armA2.yml build --pull stunnel-arm-a2` before `up -d` if the Docker daemon has cached the old layers. Never reference Alpine by tag alone (`alpine:3.20`) without digest — it makes the public TLS surface unreproducible. `apk` pulls stunnel from Alpine's package index at build time and is version-locked to the 3.20 branch; refreshing Alpine also refreshes stunnel + OpenSSL.

**WORKING_RULES rule 8 carve-out (PR-8a).** Server-side overlay + config only. Zero Android transport code touched. No client `RcDirectArmA2.kt` in this PR (that lands in PR-8b after this overlay is deployed). Rule 8 transport regression gate carve-out applies per the rule's own server-side-only clause.

**WORKING_RULES rule 9 (no merge without verification).** Every code-state claim in this PR is grep-verified or SSH-VPS-verified:

- relay code does not reference proxy headers → `grep` evidence above (Gate 1)
- Caddy cert/key are PEM at the declared path → SSH evidence above (Gate 2)
- compose service `relay` exists with `expose: 8080` → verified by reading `deploy/docker-compose.yml` lines 27-66
- compose network attachment pattern works across `-f` merge → verified by reading `deploy/docker-compose.yml` `networks.phantom-internal` top-level + `services.relay.networks`/`services.caddy.networks` membership
- compose project-name prefix produces `deploy_phantom-internal` actual name → SSH evidence above (`docker network ls`)
- cert + key paths absolute and correct → SSH evidence above (`find` output)

### Arm B — Caddy tuning verification (server-side, observational)

**Goal.** Confirm empirically that the production Caddyfile is at its WS-friendliest settings and no further Caddy-side tuning is available.

**Setup.** Read-only inspection of `deploy/Caddyfile:53-58` plus one synthetic alternate Caddy block on a staging path (if any meaningful parameter remains to tune — per F-CaddyAtMax this is expected to be empty).

**Cost.** Essentially zero — F-CaddyAtMax already documents that Caddyfile is at the WS-friendliest extreme. Arm B closes by referencing F-CaddyAtMax + Arm A's bypass result. If Arm A shows H-B (Caddy innocent), Arm B auto-closes with no further work.

**Discriminator.** If somehow Caddy has a remaining tuning parameter that materially extends WS lifetime, Arm B promotes that change to a production Caddyfile PR. Expected outcome: no work needed.

### Arm C — OkHttp ping interval matrix (client diagnostic APK)

**Goal.** Probe H-C by varying OkHttp's `pingInterval` value on the raw-OkHttp diagnostic path (sibling of `RcDirectArmB` from Phase 1, unchanged Ktor production code path per Inv-OnlyDiagnosticCadenceChange).

**Setup.**

1. New `BuildConfig.DEBUG_RC_DIRECT_PING_INTERVAL_MS` field in `apps/android/build.gradle.kts`, mirroring the existing `DEBUG_RC_DIRECT_ARM` and `DEBUG_PHASE2_MODE` patterns. Values: `"0"` (use production default of 15 000 ms) / `"10000"` / `"20000"` / `"30000"`. Release-pinned to `"0"` for defence-in-depth.
2. New diagnostic class `RcDirectArmC` constructed only when `BuildConfig.DEBUG && BuildConfig.DEBUG_RC_DIRECT_PING_INTERVAL_MS != "0"`. Class reads the value, parses to `Long`, passes to `OkHttpClient.Builder().pingInterval(value, TimeUnit.MILLISECONDS)`. Otherwise identical to `RcDirectArmB`'s read-only diagnostic pattern.
3. Build 3 Arm C diagnostic APKs (`p-armc-10000.apk` / `p-armc-20000.apk` / `p-armc-30000.apk`) — these are the cadence-variation runs. Each constructs `RcDirectArmC` with the corresponding ping interval and emits `RC_DIRECT_ARM_C_*` logs.
4. Baseline APKs are **not** Arm C variants — at `rcDirectPingIntervalMs=0` the gate `BuildConfig.DEBUG_RC_DIRECT_PING_INTERVAL_MS != "0"` is false and `RcDirectArmC` is not constructed. The matrix needs an explicit baseline choice:

   | Baseline | Build flags | Expected telemetry | What it measures |
   |---|---|---|---|
   | **Production baseline** | `rcDirectPingIntervalMs=0`, `rcDirectArm=0` (defaults) | `PhantomRelay/session_summary` + `ws_ping_timeout_diag`; **no** `RC_DIRECT_ARM_C_*`, **no** `RC_DIRECT_ARM_B_*` | Production Ktor WS path through Caddy at 15 s ping |
   | **Raw OkHttp 15 s baseline (Arm B)** | `rcDirectPingIntervalMs=0`, `rcDirectArm=B` | `RC_DIRECT_ARM_B_*` lifecycle lines | Raw OkHttp (no Ktor wrapper) through Caddy at 15 s ping |
   | **Arm C matrix value** | `rcDirectPingIntervalMs=10000` / `20000` / `30000`, `rcDirectArm=0` | `RC_DIRECT_ARM_C_*` lifecycle lines | Raw OkHttp through Caddy at the matrix ping value |

   The discriminator compares Arm C matrix runs to whichever baseline is chosen. The Arm B baseline is the cleaner comparator because it isolates "raw OkHttp + cadence variation" from "Ktor wrapper". The production baseline is the cleaner comparator if the question is "does Arm C deliver a UX improvement over production today".

5. 4 × 15-min field runs on the worst-affected network (Tele2 LTE per Phase 2 evidence). Logcat capture pulls `RC_DIRECT_ARM_C:V` plus `RC_DIRECT_ARM_B:V` plus the existing production tag set (so a baseline APK with `rcDirectArm=B` is captured by the same logcat command without reconfiguration). PCAPdroid optional (re-use Phase 2 capture protocol if Inv-WallClockAlignment-grade evidence is needed).

**Cost.** ~420 LOC new client code (RcDirectArmC.kt is a near-clone of RcDirectArmA — which is itself a near-clone of RcDirectArmB — with a single Builder parameter change). 1 hour of field test execution (4 × 15 min runs).

**Refined scope (locked 2026-06-03 per PR-4 review):**

- **Strictly diagnostic.** RcDirectArmC is a **diagnostic cadence arm**, not a production-fix candidate. Its purpose is to measure cadence-sensitivity of the OkHttp ping path on the production carrier route (Tecno → Tele2 → Caddy → relay) — that is, to answer "does varying pingInterval change WS lifetime?" with empirical numbers. The answer is a data point fed into the §5 decision tree; it does **not** authorise any production promotion of a varied pingInterval value.
- **Production `RelayTransportFactory.kt:71` is read-only for the entire RC-DIRECT-STABILITY1 track per `Inv-OnlyDiagnosticCadenceChange`.** Any value Arm C finds materially improves WS lifetime ships only via a separate named PR with its own mini-lock — never via this track.
- **Arm C runs against production `BuildConfig.RELAY_URL` (`wss://relay.phntm.pro/ws`)** — through Caddy, through Tele2 LTE radio, through carrier middleboxes. No bypass, no USB tunnel. This addresses the architectural limitation that Arm A's adb-reverse tunnel hit (see Arm A Outcome subsection above): the diagnostic must exercise the target network to discriminate path-layer questions.
- **No production code mutation.** Arm C is a sibling diagnostic class gated by a new BuildConfig flag; the production transport factory, REST orchestration, capability resolver, and chain selection are all untouched.

**Discriminator.** If one ping-interval value produces median lifetime ≥ 3× v11 Arm B Tele2 baseline on Tele2 LTE, **H-C confirmed** (cadence-sensitive kill mechanism). The result is recorded as evidence in the §5 decision tree and the §6 ship criterion is evaluated per-value. Any production promotion of a varied pingInterval is gated to a separate named PR with its own mini-lock per Inv-OnlyDiagnosticCadenceChange. If all four values produce statistically identical lifetimes, **H-C refuted** — the kill mechanism does not depend on pingInterval cadence on the production path.

**Outcome (Tecno Tele2 LTE field matrix 2026-06-03):** **H-C refuted — cadence is detection timing, not a fix lever.** Recorded findings:

- **Linear scaling lifetime ≈ 3 × ping_interval across the entire matrix.** Single field-test day, Tecno + Tele2 LTE through production Caddy, no SSH tunnel, no adb reverse. Four 15-min captures from sequential APK installs:

  | Run | Ping interval | Failures in 15 min | Median lifetime | P95 |
  |---|---:|---:|---:|---:|
  | Arm B baseline (raw OkHttp at 15 s) | 15 s | 18 | 45 s | 45 s |
  | Arm C 10 s | 10 s | 23 | 30 s | 30 s |
  | Arm C 20 s | 20 s | 15 | 60 s | 60 s |
  | Arm C 30 s | 30 s | 9 | 90 s | 90 s |

- **Mechanism: `RealWebSocket.writePingFrame()` fails on the next ping after `awaitingPong` was set unanswered.** For subsequent sessions, lifetime ≈ 3 × ping_interval decomposes as: one successful ping/pong was counted, then the next Pong was missed, and OkHttp failed on the following scheduled ping because `awaitingPong` was still true. For the first session, lifetime ≈ 2 × ping_interval: zero successful pongs, the first Pong was missed, and OkHttp failed on the next ping tick. Varying the interval only changes WHEN detection fires, not WHETHER the packet loss happens.
- **Mode 2 "first session 0 pong, then 1 pong" signature persists across all four cadence values.** First reconnect always loses first Pong; subsequent reconnects lose second Pong. The Phase 2 carrier signature is identical regardless of ping interval. Rules out "ping interval interacts with carrier idle-keepalive teardown" as the kill mechanism.
- **Matrix validity clean.** Arm B baseline only fired `RC_DIRECT_ARM_B_*`. Arm C 10/20/30 only fired `RC_DIRECT_ARM_C_*`. Production Ktor path silent across all four (`session_summary = 0`, `ws_ping_timeout_diag = 0`). Inv-ParallelArmIsolation held.
- **Deceptive metric warning.** "Fewer failures per 15 min" at 30 s (9 vs 23 at 10 s) is not a real UX improvement — it is the same broken connection diagnosed later. The connection still dies; it just takes longer to notice.
- **Verdict per §5 + §6:** **§6 ship criterion (zero `SocketTimeoutException: sent ping but didn't receive pong` in 15-min capture) FAILS across all 4 values.** **H-C as a production fix lever is refuted.** Cadence sensitivity is observed, but only as detection timing: changing `pingInterval` changes WHEN OkHttp notices the already-broken path, not WHETHER the path breaks. §5 decision-tree row "Arm C all-FAIL → H-C refuted, continue to Arm D" fires. Production `RelayTransportFactory.kt:71 pingInterval(15_000L, MILLISECONDS)` line **stays at 15 s** per `Inv-OnlyDiagnosticCadenceChange`. No production promotion candidate.
- **Carry-forward to Arm D.** The Mode 2 carrier signature is independent of WS-control-frame cadence. The next discrimination question becomes: is the kill specific to WS control frames (Ping/Pong) at all, or do application data frames also die at the same Phase 2 anchor (first inbound after WS upgrade)? That is the Arm D data-frame-heartbeat question, scoped below.
- **Memory:** detailed Arm C result + structural findings preserved in `feedback`-style memory entry `project_arm_c_lifetime_linear_in_interval_2026_06_04.md` for future-track reference.

### Arm D — Data-frame heartbeat diagnostic (client + relay coordinated)

**Goal.** Probe H-D by maintaining a periodic application-data-frame heartbeat (WS Text payload, not Ping) on the diagnostic path. Verify whether data-plane traffic survives the conditions where control-plane Pong dies. Specifically: does the Mode 2 "first inbound after WS upgrade" anchor that kills the first Pong also kill the first inbound heartbeat echo? Per the Arm C structural finding, the carrier signature is cadence-invariant — if it is also data-vs-control-invariant, the kill is at packet layer (broader than WS control frames). If echoes survive while Ping/Pong dies, the kill is WS-control-specific.

**Refined scope (locked 2026-06-03 + 2026-06-04 PR-5 review):**

- **Payload format (Vladislav-locked):** ASCII Text frames matching the prefix `phantom:diagnostic:heartbeat-echo:v1:<seq>:<client_ms>`. Reasons: grep-friendly in relay + device logs, no JSON envelope ambiguity, easy prefix check (relay can match on exact prefix), namespaced so future protocol additions do not collide. Binary opcode pattern parked.
- **Default-off env flag (Vladislav-locked):** relay echo handler is gated by `RELAY_ENABLE_HEARTBEAT_ECHO=1`. Default off. Flag flipped only by operator during Arm D field test window, with explicit revert step after the test. PR-6 commit body must include the revert checklist.
- **Echo direction is WS Text only** (Inv-DataFrameNotControlFrame from §3). Relay sends back `Message::Text(...)` matching the inbound payload verbatim. Never `Message::Pong(...)` or `Message::Ping(...)` from application code on either side.
- **Payload validation rigour on the relay side (locked per architect pre-review 2026-06-04 for PR-6):**
  - **Length cap:** total payload ≤ 256 bytes. Reject (log + ignore, do not echo) above this. Prevents the relay from becoming a free echo amplifier under flag-on.
  - **Prefix exact match** on the full `phantom:diagnostic:heartbeat-echo:v1:` string (not just `phantom:`). Future `:v2:` payloads will not silently enter the v1 handler.
  - **Parse `<seq>` and `<client_ms>` as `u64`** for the relay log line; reject on parse failure.
  - **Echo via `Message::Text(...)` only** per `Inv-DataFrameNotControlFrame` (§3). Never `Message::Pong` (would re-introduce PR-H1e regression class). Inline comment at the send site naming the invariant; unit test asserting returned opcode type.
  - **Per-session counters** `echo_frames_received` / `echo_frames_sent` added to existing `session_summary` log line; per-frame echo logs at `debug!` level only.
- **Feature flag read pattern (locked per architect pre-review):** `std::env::var("RELAY_ENABLE_HEARTBEAT_ECHO")` is read **once at process start** in `RelayConfig::from_env()` (existing codebase idiom), parsed strictly as `v == "1"` (any other value including `"true"` / `"yes"` fails closed), stored as `pub heartbeat_echo_enabled: bool`, threaded through `Arc<AppState>`. A startup `info!` log line announces the flag state.

**Setup.**

1. **PR-6 (relay code, ships first).** Adds the echo handler in `services/relay/src/routes.rs` matching the payload contract above. Behind `RELAY_ENABLE_HEARTBEAT_ECHO=1`, default off. Architect explicit pre-draft review locks the relay-side design (handler insertion point, env-flag read pattern, validation rigour, logging shape, operator runbook). Passes `Relay CI / build-test` + `Deploy lint` if any. Inv-RelayChangeNeedsItsOwnPR carries through.
2. **VPS deploy (Vladislav-owned).** After PR-6 merges, operator adds `RELAY_ENABLE_HEARTBEAT_ECHO=1` to `.env` on the VPS and runs `docker compose up -d --force-recreate relay`. Reachability verification: connect a quick local WS client against the loopback bypass binding (PR-3a) or via a short-lived diagnostic APK, send the canonical payload, verify echo returns. Revert checklist: remove the line from `.env`, recreate.
3. **PR-7 (Android diagnostic class).** New `RcDirectArmD` (after PR-6 lands on master AND `RELAY_ENABLE_HEARTBEAT_ECHO=1` deployed on the VPS). Near-clone of `RcDirectArmC`. Sends one Text-frame heartbeat every 15 s on the diagnostic WS with the canonical payload; counts inbound echo replies; emits `RC_DIRECT_ARM_D_echo_sent seq=N` per outbound and `RC_DIRECT_ARM_D_echo_received seq=N rtt_ms=...` per round-trip. No production WS path touched. Read-only outbound is allowed for this arm specifically because the WS Text echo is the diagnostic primitive — this is an explicit narrow carve-out from Inv-RawArmReadOnly noted inline.
4. **Field run** identical to Arm C cadence (Tele2 LTE 15-min capture on Tecno, through production Caddy, no SSH tunnel needed).

**Cost.** ~30 LOC relay code (echo handler), ~40 LOC Android code (RcDirectArmD), 1 hour field test. Relay PR is small but is the first relay production touch in this track — requires WORKING_RULES rule 9 verification.

**Discriminator.** If echoes arrive consistently while control-plane Pong dies (e.g. 30+ successful echoes round-trip while OkHttp `pingInterval` Ping/Pong dies at cycle 8 or earlier), **H-D confirmed** — kill is specific to control frames or to silent sockets. Then a production short-message heartbeat (Inv-DataFrameNotControlFrame protects against PR-H1e regression) becomes a candidate. If echoes also stop near the death moment, **H-D refuted** — both control and data planes lose together, kill is at a lower layer.

**Outcome (Tecno Tele2 LTE field run 2026-06-04 13:14:10 — 13:30:20 UTC):** **H-D refuted — application-data Text heartbeat does not survive Mode 2.** Single 16-min capture from PR-7 APK (`phantom-armd.apk` SHA `bbcf64278c13bc28437a0aa5196fe7b30007348203e8a41a7612cc23aac690c8`) through production Caddy with `RELAY_ENABLE_HEARTBEAT_ECHO=1` flipped on the VPS `.env` for this run. Recorded findings:

- **Client-side counters (`arm-d-tecno-tele2.log`).** 21 `RC_DIRECT_ARM_D_ws_open` (s=1..s=21), 19 `RC_DIRECT_ARM_D_ws_failure` (s=14 missing failure line in logcat capture; s=21 in-flight at capture end), 39 `RC_DIRECT_ARM_D_echo_sent`, **0** `RC_DIRECT_ARM_D_echo_received`, 0 heartbeat-sender exceptions. Across every `ws_failure` summary: `echo_received=0 echo_missing=N last_echo_rtt_ms=-1 inbound_text_frames=0 inbound_binary_frames=0`.
- **Relay-side counters (`arm-d-relay.log`).** 21 `event="connect"` (conn_id 0..20, matching client `ws_open`), 20 `ws_protocol_ping_received` (conn_id 0..19; conn_id 20 too short before capture end), 20 `ws_protocol_pong_sent` (matching). **0** `event=heartbeat_echo_received` (per-frame log per [services/relay/src/routes.rs:523](services/relay/src/routes.rs#L523)). **0** `event=heartbeat_echo_sent`. **0** `event=heartbeat_echo_rejected`.
- **Mode 2 carrier signature persists.** s=1 dies at lifetime 30 002 ms with "after 0 successful ping/pongs" (≈ 2× ping_interval); s=2..s=20 die at ~45 008 ms with "after 1 successful ping/pongs" (≈ 3× ping_interval). Identical to Arm C and the Phase 2 baseline. The F-Mode2-cadence-invariant fact (§1) widens: the Mode 2 signature is also data-frame-heartbeat-invariant.
- **Control/application delivery asymmetry at relay application layer.** For sessions s=2..s=20, the device sends a WS control Ping **and** a WS Text echo seq=1 at approximately the same wall-clock instant (~+15 s after `ws_open`). Relay logs `ws_protocol_ping_received` per session but **never** logs `heartbeat_echo_received`. The two frame classes diverge in delivery to the relay application layer despite traversing the same WS connection in the same direction at the same time. Exact cause of the divergence remains open across at least four hypotheses: OkHttp writer-side enqueue-vs-egress timing on the device, Caddy/TLS WS frame handling distinguishing opcode classes, carrier path stateful inspection, or interaction across these layers. `ws.send(Text)` returning success only proves the frame was queued in OkHttp on the device — not that the bytes physically left the radio. This asymmetry is a sharper discriminator than "round-trip generally broken": it narrows the kill toward control-frame-vs-Text differential on the uplink while leaving the precise layer-of-divergence open for Arm A.2 to discriminate.
- **First-session downlink Pong loss isolated to s=1.** Relay log conn_id=0 shows ping_received at +15 s and pong_sent at +15 s, but the device reports "after 0 successful ping/pongs" for s=1. The Pong was sent by relay but never observed by device OkHttp. From s=2 onward, the device reports "after 1 successful ping/pongs" — first downlink Pong arrives. Read together with the asymmetry above, the path tolerates exactly one control round-trip per session before subsequent uplink frames (both Text and second Ping) stop reaching the relay application layer.
- **PR #280 lifecycle fix held in field.** Zero heartbeat-sender exceptions across all 21 sessions, no `CancellationException` noise, the `openedAt: CompletableDeferred<Long>` gating between `onOpen` and the heartbeat coroutine shipped cleanly. The first echo seq=1 was visible per logcat in every session that lived past +15 s. Inv-DataFrameNotControlFrame held on the client side: all 39 outbound heartbeat frames used the `RcDirectArmD.kt` WS Text path, never WS Ping/Pong. Relay-side `build_heartbeat_echo_response` was not exercised in this run because relay observed 0 `heartbeat_echo_received`; therefore no relay `Message::Text` echo response, and no relay `Message::Pong`, was constructed.
- **Matrix validity clean.** Arm D fired `RC_DIRECT_ARM_D_*` only; production Ktor, Arm B, Arm C diagnostic paths silent. Inv-ParallelArmIsolation held.
- **Side finding (not blocker): relay `event=session_summary` and `event=disconnect` lines absent for all 21 sessions in the captured docker-log window.** Two candidate explanations: (a) the docker-logs capture filter dropped them (the original `grep -E` SSH invocation had bash quoting issues, so the command that actually produced the captured log is not reproducible from chat); (b) the relay WS handler does not observe the client-side teardown — the `.next().await` in the WS loop is still pending across all 21 conn_ids when capture ended, indicating a possible server-side WS-handler leak when the carrier silently half-closes the TCP path. (a) is the parsimonious explanation; (b) would be a separate concrete relay bug if reproducible. This side finding does **not** affect the Arm D H-D verdict — per-frame `event=heartbeat_echo_received` fires on every accepted Text receipt at [services/relay/src/routes.rs:523](services/relay/src/routes.rs#L523), independent of session close, and that counter shows zero events. Tracked as a separate low-priority diagnostic outside this track.
- **Verdict per §5 + §6.** §6 ship criterion (zero `SocketTimeoutException: sent ping but didn't receive pong` in 15-min capture) **FAILS** — 19 such failures observed across 19 ws_failure summaries. **H-D as a production fix lever is refuted.** A production short-message heartbeat would replicate the failure mode it was intended to mitigate. No production-promotion candidate from Arm D. Operator runbook revert step fires after this PR merges: `RELAY_ENABLE_HEARTBEAT_ECHO=1` line removed from VPS `.env`, `docker compose up -d --force-recreate relay`.
- **Carry-forward deviation: re-prioritise Arm A.2 ahead of Arm E.** §5 row "D FAIL → continue to Arm E" was scoped before the control/application asymmetry was observed in field. The empirical asymmetry makes WS rotation (Arm E) unsuitable as the next step: rotating to a fresh WS would face the same uplink-Text-fails-immediately failure mode on the new connection, so rotation-extends-lifetime is not the right question to ask first. Arm A.2 (public non-Caddy TLS bypass on a different VPS port, currently in §9 parking lot) discriminates whether the Caddy WS/TLS edge is the layer at which the asymmetry emerges; that answer reshapes whether Arm E remains viable at all. Arm A.2 trigger rule in §9 parking lot was originally "after Arms C/D/E all close without meeting §6" — this outcome subsection refines the rule to "after Arm D close with asymmetric verdict; Arm E sequenced after Arm A.2 outcome lands". The next PR (#284) carries the Arm A.2 scope mini-lock with the three-outcome decision tree (Caddy-edge-killer / Text-class-killer / carrier-stateful-kill) and the public TLS surface security mini-lock.
- **Memory.** Detailed Arm D result + control/application asymmetry pointer preserved in memory entry `project_arm_d_control_application_asymmetry_2026_06_04.md` for future-track reference.

### Arm E — Short-lived WS rotation (client refactor)

**Goal.** Probe H-E by closing and re-opening the production Direct WS on a proactive timer (e.g. at session_open + 0.8 × baseline expected lifetime). Measure delivery continuity end-to-end.

**Setup.**

1. New `BuildConfig.DEBUG_RC_DIRECT_ROTATION_MS` field. Values: `"0"` (disabled = production behaviour) / `"24000"` (Tele2 80% = ~24 s) / `"120000"` (Wi-Fi 80% = ~120 s).
2. `KtorRelayTransport` generation-scoped timer change: a debug-gated `kotlinx.coroutines.delay(rotationMs).cancel()` wrapper around the existing reconnect loop. Generation-scoped so cleanup is automatic on session close. Inv-RotationNotForCallSignaling gate at the wire-up site checks `connectionUiState.value.state != CallActive` before triggering rotation.
3. Field test on Tele2 LTE 15-min capture; measure: (a) delivery success ratio for a synthetic message-send sequence, (b) end-to-end RTT for echo through the WS, (c) reconnect overhead per rotation.

**Cost.** ~80 LOC (timer + Inv gate + telemetry). Larger client touch than Arms A-D because it modifies the production Ktor lifecycle (debug-gated, so no production regression risk). Field test 1 hour.

**Discriminator.** If rotation produces delivery success ratio ≥ baseline AND end-to-end RTT degradation ≤ 25 %, **H-E confirmed** — short-lived rotation becomes a candidate for production promotion in a separate named PR. If rotation degrades delivery (network gap during rotation > delivery improvement), **H-E refuted**.

### Arm F — Alternative Direct realtime: SSE or HTTP long-poll

**Goal.** Probe H-F by implementing a non-WS realtime signaling path. **Last-resort experiment** — Arm F has the largest implementation cost of all six (relay-track-scale work per architect 2026-06-03 review).

**Setup.** This Arm is **deferred to a separate relay-track mini-lock** if pursued — see §9 parking lot. RC-DIRECT-STABILITY1 documents it here only to prevent the design space from closing prematurely. If Arms A-E close with §6 ship criterion met, Arm F drops entirely.

**Cost.** Relay-track-scale: new route in `services/relay/src/routes.rs`, new connection-tracking state, new auth surface adjusted for SSE / long-poll semantics. Comparable in scope to the original WS relay implementation.

**Discriminator.** Not evaluated in this track. If RC-DIRECT-STABILITY1 closes without §6 ship criterion met, the parking-lot Arm F mini-lock opens as the next track.

---

## §5 — Decision tree (Arm outcome → next action)

Each Arm's primary outcome maps to one row below. The cross-arm ship gate in §6 decides whether the track ships PASS, PARTIAL, or FAIL.

| Arm | Primary outcome | Implication | Next action |
|---|---|---|---|
| **A** PASS (bypass holds ≥ 3× baseline on Tele2) | H-A confirmed | Caddy is in the kill chain | Open `RC-CADDY-FIX1` track; deprioritise Arms C/D/E for now |
| **A** FAIL (bypass shows same death rhythm) | H-B confirmed | Caddy innocent | Continue to Arms B (auto-close) → C → D → E |
| **B** auto-close | Per F-CaddyAtMax | No Caddy tuning available | Closes automatically when Arm A reads H-B |
| **C** PASS for one specific cadence | H-C confirmed | Specific pingInterval value materially extends lifetime | Promote that value to production via separate named PR + mini-lock; do not auto-promote in this track |
| **C** all-FAIL | H-C refuted | Cadence does not matter on this path | Continue to Arm D |
| **D** PASS (echo survives, Ping/Pong dies) | H-D confirmed | Control frames are killed selectively; data plane survives | Production-data-frame heartbeat becomes candidate; design in separate named PR |
| **D** FAIL (echo also dies) | H-D refuted | Kill is below WS frame level OR distinct application-frame delivery class (per Arm D 2026-06-04 control/application asymmetry) | Continue to **Arm A.2** (re-prioritised ahead of Arm E by PR #284 after Arm D outcome PR #283) |
| **A.2 W** (stunnel sustains WS ≥ 3× Tele2 baseline + Text echoes succeed) | Caddy edge path is in the kill chain or contributes | TLS implementation + HTTP framing + WS proxy layer (or combination) is implicated; does NOT prove TLS innocent | Open `RC-CADDY-FIX1` track |
| **A.2 X** (Ping survives, Text dies — Arm D asymmetry persists through `:8444`) | Asymmetry origin below Caddy edge OR in a layer Caddy and stunnel share | Caddy loses priority as proximal cause; below-edge / carrier-side investigation needed | Open below-edge investigation track; 3.2b.1 escalates from "parked" to "needed" |
| **A.2 Y** (Mode 2 signature persists through `:8444`) | Structural carrier / path / lower-layer kill | Direct WS realtime on Tele2 LTE is structurally hard; Arm E deprecated as the asymmetry rules out "rotation extends healthy lifetime" framing | Uplift realtime per ADR-028 4-layer architecture; open Arm F parking-lot mini-lock |
| **E** PASS (rotation delivers measurable continuity gain) | H-E confirmed | Short-lived rotation is viable | Production rotation becomes candidate; design in separate named PR; respect Inv-RotationNotForCallSignaling for call signaling |
| **E** FAIL (rotation degrades delivery) | H-E refuted | WS reconnect overhead too high | Continue to track closure |
| **All A-E FAIL** | H-F confirmed | WebSocket is structurally wrong for this path | Close RC-DIRECT-STABILITY1 with verdict "Direct WS structurally unstable on this network class". 3.2b.1 becomes mandatory. Open Arm F parking-lot mini-lock as next track. |
| **Mixed verdicts** | One or more Arms close PASS, others FAIL | Multiple production-candidate fixes available | Stack the PASS fixes in priority order (server-side first); 3.2b.1 stays parked. |

---

## §6 — Acceptance gates (Vladislav-locked ship criterion)

The track closes with one of three verdicts. The verdict is computed against the worst-affected network observed in Phase 2 (Tele2 LTE).

```text
PASS / ship-candidate:
  zero `SocketTimeoutException: sent ping but didn't receive pong` events
  in a 15-minute capture on the target problematic network.
```

```text
PARTIAL / candidate for deeper investigation:
  p95 WS session lifetime ≥ 3× baseline
  AND no message-delivery regression,
  but ping-timeouts still occur.
```

```text
FAIL:
  p95 < 3× baseline,
  OR delivery regression,
  OR same death rhythm remains.
```

Why the asymmetry between PASS and PARTIAL: a WS that holds 2 minutes instead of 30 seconds is observably better but still structurally dying. "Better" is not the same as "fixed". For PHANTOM's product target, PARTIAL = a continued reason to keep 3.2b.1 as a safety net + open Arm F path. PASS = Direct WS is truly fixed for this network class on the conclusively-classified mode.

If no Arm meets PASS and no Arm meets PARTIAL: the track's empirical conclusion is "WS is structurally unstable on this path". 3.2b.1 design becomes mandatory; Arm F parking-lot mini-lock opens.

Cross-arm aggregation rule: the track ships PASS if **at least one** Arm produces a path meeting the PASS criterion. PARTIAL ships only if no Arm produces PASS but at least one Arm produces PARTIAL.

---

## §7 — Implementation order (cheap-first)

Ordering is locked. Each entry below ships as one PR (or one paired client+relay PR for Arm D).

| Step | What | Cost | Production touch |
|---|---|---|---|
| **0** | PR #273 (already shipped on master `6c923c39`) — CI loopback-only-ports gate ships first so `Inv-BypassIsLoopbackOnly` is enforced before any Arm A PR can add a port binding | ~150 LOC CI workflow | None — workflow only |
| **1** | This PR — mini-lock + ADR-028 | ~600 LOC docs | None |
| **2** | Arm A — compose-file delta PR (loopback bypass binding) + Android `RcDirectArmA.kt` diagnostic APK | ~100 LOC client + 2 LOC compose | Loopback bypass on VPS (revertible in ~5 min); debug-only APK build |
| **3** | Arm B — auto-closes if Arm A returns H-B per F-CaddyAtMax. No new PR unless Arm A surprises | 0 LOC | None |
| **4** | Arm C — `RcDirectArmC.kt` + new `DEBUG_RC_DIRECT_PING_INTERVAL_MS` BuildConfig field; production code untouched (Inv-OnlyDiagnosticCadenceChange) | ~50 LOC client | None — debug-only APK |
| **5a** | Arm D pre-step — relay PR adds echo-opcode handler behind `RELAY_ENABLE_HEARTBEAT_ECHO=1` env var | ~30 LOC relay | Relay binary change; feature-flagged off by default |
| **5b** | Arm D Android — `RcDirectArmD.kt` after relay PR lands | ~40 LOC client | None — debug-only APK |
| **5c** | PR #284 — Arm A.2 scope mini-lock (this PR) — promotes Arm A.2 from §9 parking lot to §4 active arm after Arm D outcome reveals control/application asymmetry | ~250 LOC docs | None |
| **5d** | Arm A.2 PR-8a — `deploy/docker-compose.armA2.yml` overlay + stunnel config + operator runbook for time-boxed `:8444` public TLS bypass (host port forced to `:8444` because `:8443` held by production `phantom-xray`) | ~30 LOC compose + ~10 LOC stunnel config | Time-boxed public TLS endpoint on `:8444` during capture window only (revert via explicit `compose stop` + `compose rm -f`) |
| **5e** | Arm A.2 PR-8b — Android `RcDirectArmA2.kt` near-clone of `RcDirectArmD` + `DEBUG_RC_DIRECT_ARM_A2_URL` BuildConfig field | ~60 LOC client | None — debug-only APK |
| **6** | Arm E — debug-gated rotation timer + Inv-RotationNotForCallSignaling gate. **Conditional on Arm A.2 outcome:** deferred if A.2 closes W (RC-CADDY-FIX1 triggered) or Y (Arm F triggered); pursued only if A.2 closes X (below-edge investigation in progress, rotation may still help reduce session-death frequency for end-user UX) | ~80 LOC client | None — `DEBUG_RC_DIRECT_ROTATION_MS` debug-only |
| **7** | Track outcome PR — evidence summary §13+ appended to this file + MASTER_TIMELINE bump + PROJECT_LOG entry per `feedback_durable_log.md`. Verdict per §6. | ~200 LOC docs | None |
| **8** (conditional) | Arm F parking-lot mini-lock opens as a separate track only if track outcome is "WS structurally unstable" per §6 FAIL | n/a (separate track) | n/a |

Steps 2-6 do not require all to run sequentially in chat sessions — Arm A is the primary discriminator and may close the track early (e.g. if H-A confirms, jump to RC-CADDY-FIX1 track and the track outcome PR drafts immediately). Arm A.2 sequencing was originally "after Arms C / D / E all close" per §9 parking lot trigger rule; refined by PR #284 to "after Arm D close with asymmetric verdict; Arm E sequenced after Arm A.2 outcome lands".

---

## §8 — Out of scope (hard)

- ❌ Any production code change to `KtorRelayTransport.kt`, `RelayTransportFactory.kt` `pingInterval` line, `RestFallbackOrchestrator`, `RestStateMachine`, `TransportManager.reorderChain`, `TransportStrategy.chain`, `TransportCapabilitiesResolver` (PR-C1 calls gate), or `ConnectionUiState`.
- ❌ Any 3.2b.1 adaptive validation design or code (`Inv-NoSpinningUntilEvidence`).
- ❌ Calls / voice signaling changes (`Inv-NoCallsVoiceTouch`).
- ❌ App-level WS Ping reintroduction in any Arm (`Inv-DataFrameNotControlFrame`).
- ❌ CHIP1 work (parked at `78bd979e`).
- ❌ `RC-REALITY-PROBE1` work (separate track).
- ❌ `PR-UI-CONNECTION-LABELS1` work (separate track).
- ❌ WebRTC + TURN call architecture changes (ADR-028 fixes the intent; implementation is its own future track).
- ❌ Mode 2 pp=1 TCP-layer mechanism discrimination (parked per RC-DIRECT-WS-DEATH1 §38; may close as Arm A side-effect but is not a primary goal here).
- ❌ Phase 2b TLS keylog / decrypted Pong proof (parked per RC-DIRECT-WS-DEATH1 §38).
- ❌ Server-side BPF on Hetzner relay host (parked per RC-DIRECT-WS-DEATH1 §38; pulled into this track only as escalation if Arm A + Arm B together cannot discriminate Mode 2 pp=1 mechanism).
- ❌ Arm F implementation (deferred to its own mini-lock per §4 Arm F).
- ❌ Any change to `network_security_config.xml` cleartext whitelist (`Inv-NoLanInNsc`).
- ❌ Any `BuildConfig.DEBUG_BYPASS_URL` value in a release build (`Inv-BypassIsLoopbackOnly` + double-gate).

---

## §9 — Parking lot (deferred until §6 verdict lands)

- **Arm A.2 — public non-Caddy TLS bypass.** **Promoted to active arm in §4 by PR #284 on 2026-06-04** after the Arm D outcome (PR #283 squash `601d9d8d`) revealed control/application delivery asymmetry at the relay application layer. Original parking-lot trigger rule "after Arms C / D / E all close without meeting §6" refined by PR #284 to "after Arm D close with asymmetric verdict; Arm E sequenced after Arm A.2 outcome lands". stunnel locked as primary terminator candidate, HAProxy as fallback if cert sharing, relay proxy-header dependency, or TLS mode requirements block stunnel in implementation. See §4 Arm A.2 subsection for full scope, security mini-lock, P2 hard gates, and W/X/Y discriminator.
- **Arm F — SSE / HTTP long-poll alternative.** Triggered only if §6 verdict is FAIL across A-E (including Arm A.2 if it ran). Implementation cost is relay-track-scale (new route + new state table + auth-surface adjustment). Designs as its own mini-lock.
- **RC-CADDY-FIX1 track.** Triggered only if Arm A.2 returns H-A (Caddy in kill chain). Production Caddyfile or Caddy replacement work.
- **3.2b.1 design Council session.** Triggered only if track outcome is FAIL OR PARTIAL. If PASS, 3.2b.1 is deprioritised (Direct WS works → no UX-protection shield needed).
- **Server-side BPF on Hetzner relay host.** Triggered only if Arm A returns H-B (Caddy innocent) AND Mode 2 pp=1 discrimination remains needed for product decisions (currently parked as out-of-scope per §8).
- **Second Android handset on Wi-Fi + Tele2.** Triggered only if any Arm result is suspiciously Tecno-specific (e.g. Arm A PASS on Tecno but indistinguishable from production on a second handset).
- **Architecture intent operationalisation** (ADR-028 §"Consequences"). Voice REST media work, WebRTC + TURN call work — each its own future track. Cross-reference from ADR-028 not from this mini-lock.

---

## §10 — Process gates

- **WORKING_RULES rule 8** (transport regression gate): **applies** to any Arm PR that touches client transport code (Arms A, C, D, E). Each such PR's commit body must record Tele2 LTE smoke test result OR justify a carve-out per the rule's own carve-out clause.
- **WORKING_RULES rule 9** (no merge without verification): applies fully. Architect explicit ACK or grep-verified evidence per claim. Phase 1 + Phase 2 pattern.
- **`feedback_ws_heartbeat_diagnostic_2026_05_27.md`** — informs Inv-DataFrameNotControlFrame. The OkHttp WS Ping is the trigger that fires WsSessionEnded which drives the existing REST auto-fallback; disabling pingInterval breaks recovery. Arms must respect this.
- **`feedback_apk_build_is_mine.md`** — APK builds and adb command generation are assistant-owned; field test execution is owned by Vladislav. Arm A bypass setup (compose-file edit + container restart + SSH tunnel) is operator-owned; the diagnostic APK is assistant-owned.
- **`feedback_logcat_format.md`** — all logcat capture commands for this track use the canonical PowerShell `Tee-Object` format with appropriate tag set per Arm (`RC_DIRECT_ARM_A_V` / `RC_DIRECT_ARM_C_V` / `RC_DIRECT_ARM_D_V` added to the existing set).
- **`feedback_session_close_discipline.md`** — this mini-lock (rev1) lands before any Arm code branch is cut. Arm A PR cuts from master after this PR merges.
- **`feedback_durable_log.md`** — track outcome (step 7 above) appends `MASTER_TIMELINE_2026.md` bump + `PROJECT_LOG.md` Session journal entry per the locked pattern.
- **`feedback_pr_first_person_voice.md`** — PR descriptions and commit messages for every step in §7 read in first person; no "Vladislav-locked" / "per Vladislav direction" qualifiers.

---

## §11 — Open questions (locked 2026-06-03)

| OQ | Question | Locked answer |
|---|---|---|
| **OQ-S1** | New track file or continuation of `rc-direct-ws-death1.md` as Phase 3? | New file `docs/tracks/rc-direct-stability1.md`. Different goal (fix vs characterize). |
| **OQ-S2** | Branch name for this PR. | `feat/pr-rc-direct-stability1-mini-lock`. |
| **OQ-S3** | Arm order. | Cheap-first: A → B (auto-close after A) → C → D → E → F (deferred). |
| **OQ-S4** | 3.2b.1 status after this track opens. | Unfrozen but parked behind RC-DIRECT-STABILITY1 outcome per Inv-NoSpinningUntilEvidence. |
| **OQ-S5** | Architecture intent capture. | Separate ADR-028 file alongside this mini-lock; mini-lock §1 / §12 reference it; ADR-028 is the source of truth for the 4-layer intent (REST messages / WS+SSE signaling / REST voice media / WebRTC+TURN calls / Tor+Reality overlays). |
| **OQ-S6** | CHIP1 status. | Stays parked at `78bd979e`. |
| **OQ-S7** | Phase 2 parking-lot items. | Partially pulled: Arm A + Arm B may close Mode 2 pp=1 mechanism discrimination as side-effect (Caddy bypass narrows it). If A + B together fail to discriminate, server-side BPF moves up to next priority. Not a primary goal of this track. |
| **OQ-S8** | Tests numbering. | Continue sequentially from Test #89 (last numbered field test was #88 — `project_test88_evidence_2026_05_28.md`). No reset. |
| **OQ-S9** | Ship criterion formulation — PASS vs PARTIAL vs FAIL. | Per §6: PASS = zero ping-timeout events in 15-min capture on target network; PARTIAL = p95 lifetime ≥ 3× baseline AND no delivery regression but ping-timeouts persist; FAIL = neither. The asymmetry prevents "better but still dying" from being mistaken for "fixed". |

---

## §12 — Source-of-truth pointers

- `docs/tracks/rc-direct-ws-death1.md` §1-§39 — the closed evidence track that produced the facts in §1 of this file. Single mini-lock for the evidence-gathering work; this file is the fix-track sibling.
- `docs/adr/ADR-028-direct-stability-architecture-intent.md` — the 4-layer architecture intent (REST messages / WS+SSE signaling / REST voice media / WebRTC+TURN calls / Tor+Reality privacy overlays) that frames this track and any future stability or transport track.
- `docs/adr/ADR-003-Transport-Abstraction.md` — transport contract this track operates within; not superseded by ADR-028.
- `docs/adr/ADR-020-Adaptive-Transport-Selection.md` — runtime outer-transport selector; this track does not change ADR-020 mechanics.
- `docs/adr/ADR-025-Call-Signaling-E2EE.md` — call signaling contract that Inv-RotationNotForCallSignaling (Arm E) preserves.
- `docs/adr/ADR-027-Per-User-Signed-Challenge-Auth.md` — auth contract that F-AuthIntegrity references; the auth protocol's integrity-not-confidentiality property is what makes Arm A's plain-WS path safe under ADR-028's security-baseline section.
- `MASTER_TIMELINE_2026.md` "Last updated 2026-06-03 (wed, late)" — track sequencing; this PR bumps it.
- `docs/PROJECT_LOG.md` Session journal 2026-06-03 (wed, late) entry — durable log bundle covering #273 + this PR.
- `.github/workflows/deploy-lint.yml` (master `6c923c39`) — pre-merge CI gate enforcing `Inv-BypassIsLoopbackOnly`. Shipped in PR #273.
- `deploy/docker-compose.yml:39-42` — relay service network configuration (production-safe baseline).
- `deploy/Caddyfile:53-58` — production Caddy WS proxy block at WS-friendliest settings per F-CaddyAtMax.
- `services/relay/src/routes.rs:230-232` — relay `is_pubkey_hex` 64-hex validator referenced by F-AuthIntegrity.
- `apps/android/src/androidMain/kotlin/phantom/android/diagnostic/RcDirectArmB.kt` — template for Arms A / C / D / E client diagnostic classes (same raw-OkHttp pattern, same telemetry shape).
- Architect review 2026-06-03 (this PR pre-draft review) — informs §3 invariants and §6 ship criterion refinements.
- Security review 2026-06-03 (this PR pre-draft review) — informs `Inv-BypassIsLoopbackOnly` CI enforcement requirement (already shipped in PR #273) and `Inv-NoLanInNsc`.

RC-DIRECT-STABILITY1 mini-lock rev1 closes here. Arm A is the next code/relay PR after this lands; track outcome PR will append §13+ to this file once §6 verdict is reached.
