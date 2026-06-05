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

**Outcome (Tecno Tele2 LTE field run 2026-06-05 ~01:00 — ~01:16 local, 15-min capture window):** **W refuted, X met, Y met — verdict "Y with persistent control/application asymmetry continuation."** Caddy strongly loses priority as proximal cause; Direct WS realtime on Tele2 LTE is structurally unreliable at a layer Caddy and stunnel share OR below them. Track does NOT close on this outcome — two cheap follow-up micro-experiments (T2 byte-threshold + Arm G WS-over-Reality, both per Vladislav-locked plan after four-architect external review 2026-06-05) discriminate the architectural pivot before final close. Single 15-min capture from PR-8b debug APK (`phantom-arma2.apk` SHA `17fec82e783107fac09621c4ef9d47d581b883fe27dd88003eb214b5499fed2f`, `debugRcDirectArmA2Url=wss://relay.phntm.pro:8444/ws`) through the §4 Arm A.2 PR-8a stunnel overlay on host `:8444` with `RELAY_ENABLE_HEARTBEAT_ECHO=1` flipped on the VPS `.env` for this run. Recorded findings:

- **Client-side counters (`arm-a2-tecno-tele2.log`).** 21 `RC_DIRECT_ARM_A2_ws_open` (s=1..s=21), 20 `RC_DIRECT_ARM_A2_ws_failure` (s=21 in-flight at capture end), 40 `RC_DIRECT_ARM_A2_echo_sent` events (1 for s=1 + 2×19 for s=2..s=20 from failure summaries + 1 in-flight for s=21), **0** `RC_DIRECT_ARM_A2_echo_received`, 0 `RC_DIRECT_ARM_A2_heartbeat_sender_threw`. Across every `ws_failure` summary: `echo_received=0 echo_missing=N last_echo_rtt_ms=-1 inbound_text_frames=0 inbound_binary_frames=0`.
- **Relay-side counters (`arm-a2-relay.log`).** 20 `ws_protocol_ping_received` (conn_id 1..20), 20 `ws_protocol_pong_sent` (conn_id 1..20, matching). **0** `event=heartbeat_echo_received` (per-frame log per [services/relay/src/routes.rs:523](services/relay/src/routes.rs#L523)). **0** `event=heartbeat_echo_sent`. **0** `event=heartbeat_echo_rejected`. Note: the operator's `docker logs` grep filter (post-quoting-issue rerun) restricted output to ping/pong + heartbeat_echo events; `event=connect`/`disconnect`/`session_summary` not in captured window. Per-frame `heartbeat_echo_received` log fires independently of session close, so the 0 count is dispositive for the verdict regardless of the absent session_summary events.
- **Mode 2 carrier signature persists — byte-perfect identical to Arm D and to the v11 / Arm C baselines.** s=1 dies at lifetime 30 004 ms with "after 0 successful ping/pongs" (≈ 2× ping_interval); s=2..s=20 die at ~45 008 ms with "after 1 successful ping/pongs" (≈ 3× ping_interval). The F-Mode2-cadence-invariant fact (§1) widens further: the Mode 2 signature is also **edge-stack-invariant** — same death rhythm through Caddy (Go TLS + HTTP/WS proxy) and through stunnel (OpenSSL 3.3.7 + raw TCP forward). Two different TLS stacks and two different proxy paths give one identical death; the proximal cause is not the edge stack.
- **Control/application delivery asymmetry at relay application layer continues through `:8444`.** Same shape as Arm D: for sessions s=2..s=20, the device sends a WS Control Ping AND a WS Text echo seq=1 at approximately the same wall-clock instant (~+15 s after `ws_open`). Relay logs `ws_protocol_ping_received` per session but **never** logs `heartbeat_echo_received`. The asymmetry is reproduced through a non-Caddy edge — the Caddy HTTP/WS proxy layer is NOT what discriminates Ping from Text at the relay application layer.
- **Hypothesis-set update.** The Arm D outcome four-hypothesis open set (OkHttp writer-side enqueue-vs-egress timing / Caddy-TLS WS frame handling / carrier path stateful inspection / interaction across layers) is **EXTENDED to five hypotheses** after Independent Audit input 2026-06-05: cumulative-bytes-per-TCP-connection freeze (~14-32 KB threshold) documented for RU mobile operators in `net4people/bbs Issue #490` (hyperion-cs, 2025-06-27) and Runnin4ik's dpi-detector CLI measurements. The new hypothesis fits the observed evidence (linear lifetime ≈ 3× ping_interval as cumulative bytes climb toward threshold; Ping=6B + Text=50B+ both exhaust budget but Text faster; per-connection reset on reconnect). T2 micro-experiment discriminates the byte-threshold hypothesis from the others.
- **PR #280 + Arm A.2 PR-8a fixup chain held in field.** Zero heartbeat-sender exceptions across all 21 sessions, no `CancellationException` noise, the `openedAt: CompletableDeferred<Long>` gating between `onOpen` and the heartbeat coroutine shipped cleanly. The first echo seq=1 was visible per logcat in every session that lived past +15 s. Inv-DataFrameNotControlFrame held on the client side: all 40 outbound heartbeat frames used the `RcDirectArmA2.kt` WS Text path, never WS Ping/Pong. Relay-side `build_heartbeat_echo_response` was not exercised in this run because relay observed 0 `heartbeat_echo_received`; therefore no relay `Message::Text` echo response, and no relay `Message::Pong`, was constructed. The four PR-8a deploy-time fixups (#286 port + #287 image + #288 TLS options + #289 directive scope) held — the stunnel container served the test without further config errors.
- **Matrix validity clean.** Arm A.2 fired `RC_DIRECT_ARM_A2_*` only; production Hybrid Ktor path, Arm A, Arm B, Arm C, Arm D diagnostic paths silent. Inv-ParallelArmIsolation held. xray REALITY production on `:8443` untouched throughout the four PR-8a fixups + this field test.
- **Verdict per §5 + §6 + §4 Arm A.2 Discriminator W/X/Y.**
  - **W refuted.** WS lifetime not extended (median ~45 s; threshold for W was ≥ 90 s = 3× Tele2 baseline); Mode 2 "first session 0 pong, then 1 pong" signature persists; echo round-trips never succeed (`heartbeat_echo_received = 0` at relay). Caddy edge path is NOT the kill chain — stunnel removes Caddy and the kill persists identically.
  - **X met.** Ping survives one round-trip per session; Text dies — Arm D asymmetry persists through `:8444` byte-identically. Caddy loses priority as proximal cause; asymmetry origin is below Caddy edge OR in a layer Caddy and stunnel share.
  - **Y met.** Mode 2 signature persists through `:8444` byte-perfect identical to production through Caddy. Caddy strongly loses priority as root; the kill is structural at carrier / path / lower-layer level (per X above) OR cumulative-bytes class (per the new fifth hypothesis).
  - **Honest synthesis:** "Y with persistent control/application asymmetry continuation." Both X and Y framings interpret the same evidence from different angles — the bypass did not change the failure mode. §6 ship criterion (zero `SocketTimeoutException: sent ping but didn't receive pong` in 15-min capture) **FAILS** — 20 such failures observed.
- **Wording bounds carried forward (locked).** None of W / X / Y attribute the kill to a specific lower layer with confidence higher than the evidence supports. Does NOT claim "Caddy is innocent" (TLS is still present in stunnel — only Caddy's specific TLS+HTTP+WS-proxy layer is removed, and a different OpenSSL TLS stack failed identically). Does NOT claim "carrier definitely discriminates control vs application opcodes" (asymmetry at relay app layer is observed; the layer at which the asymmetry emerges is not pinned across the five-hypothesis open set). Does NOT claim "TLS broken" or "WebSocket broken" (TLS handshake completes; one control round-trip per session succeeds).
- **§5 decision-tree rows that fire.** Both A.2 X and A.2 Y rows fire concurrently (X: asymmetry origin below Caddy edge → open below-edge investigation track + 3.2b.1 escalates from "parked" to "needed"; Y: structural carrier / path / lower-layer kill → uplift realtime per ADR-028 + open Arm F mini-lock + Arm E deprecated). The X+Y joint firing is consistent because both rows interpret the same evidence; their triggers stack rather than conflict. **However, the track does NOT close on this outcome.** Vladislav-locked refinement after four-architect external review: two cheap micro-experiments precede the track-close decision.
- **Carry-forward: T2 + Arm G micro-experiments before track close.**
  - **T2 (byte-threshold discriminator, ~1 day).** New temporary relay endpoint `/diag/slow-post` (default-off env flag, mirrors heartbeat_echo pattern) accepts chunked POST and logs received bytes per chunk. Android `T2SlowPostDiag.kt` sends 40 KB chunked, 5 KB per chunk, 10 s between chunks; total 90 s. Single-shot field test on Tecno Tele2 LTE. Discriminator: if POST dies at ≈14-32 KB, byte-threshold hypothesis (`net4people/bbs #490`) confirmed and architectural implication is that even long SSE responses die — Matrix-style 25-sec long-poll becomes the mandatory primary realtime pattern; inside Reality must also use short-cycle (mux / XHTTP). If POST completes 40 KB, byte-threshold refuted and long SSE / WS-over-Reality remain viable.
  - **Arm G (Reality-tunneled WS, ~2-3 days).** New Android diagnostic class `RcDirectArmG.kt` near-clone of `RcDirectArmD` / `RcDirectArmA2` but routed through the embedded libXray SOCKS5 listener on `localhost:10808` (already Stage 5E production-validated for Reality outer transport). Heartbeat WS connects through SOCKS → libXray Xray-core → external `:8443` Reality endpoint (production endpoint + UUID from existing `OperatorXrayConfig.kt`) → relay. Same heartbeat payload, same Inv-DataFrameNotControlFrame, same lifecycle fixes. New `DEBUG_RC_DIRECT_ARM_G_VIA_REALITY=1` BuildConfig field, release-pinned `"0"`. 10-15 min field test on Tecno Tele2 LTE. Discriminator: if Reality WS holds ≥ 10 min with echo round-trips succeeding, **Reality-primary realtime for RU mobile** becomes the strong architectural answer (Reality already production-validated; Habr 1000694 / qna.habr 1402200 + 1403570 / plisio.net all report mobile LTE works under Reality at current censorship phase). If Reality WS dies same Mode 2, the kill is below all transport layers — pure REST architecture per Option A becomes correct; T2 result then determines whether long SSE is viable.
- **Architectural decision tree after T2 + Arm G.** Hard time-box 1 week from this outcome PR landing. Decision tree:
  - **Arm G holds ≥ 10 min →** Reality-tunneled realtime primary for RU mobile; REST + 3.2b.1 detection as safety net; cost estimate ~3-4 weeks implementation.
  - **T2 confirms byte threshold (~14-32 KB) →** Matrix-style 25-sec long-poll mandatory primary; Reality optional accelerator BUT only with short-cycle (mux / XHTTP); REST + long-poll fundament; cost estimate ~6-8 weeks.
  - **Both negative (Arm G dies AND T2 holds 40 KB) →** Naked-TLS-WS-pattern-specific cause (rare); Option A pure: REST + short-cycle + abandon Direct WS concept; cost estimate ~6-8 weeks.
  - **Arm G holds AND T2 dies at byte threshold →** Reality buffers traffic effectively, byte threshold real for naked TLS only; same architecture as "Arm G holds" branch with Reality required for everything realtime.
- **VPS operator tear-down after this PR merges.** `compose stop stunnel-arm-a2 && compose rm -f stunnel-arm-a2 && docker ps verify` per §4 Arm A.2 PR-8a operator runbook Step 6; revert `RELAY_ENABLE_HEARTBEAT_ECHO=1` from `.env` per Step 7; recreate relay per Step 7. Port `:8444` no longer in use after tear-down; xray REALITY `:8443` STAYS untouched (Stage 5E production). T2 will use a new short-lived flag (`RELAY_ENABLE_SLOW_POST_DIAG=1`); Arm G uses existing Stage 5E Reality endpoint with no server-side changes.
- **External architecture review consumed before this outcome verdict locked.** Four-architect input 2026-06-05 (Gemini close-immediately+Option A / Claude Code in-chat Arm G next / Claude Code Independent Audit T2+Arm G + byte-threshold hypothesis / working architect Arm G primary). The Independent Audit introduced the cumulative-bytes hypothesis I missed in my own four-hypothesis open set; the byte-threshold hypothesis is now the most cheaply-testable single-cause candidate (T2 = 1 day) and reframes the architecture decision significantly. Convergence across all four: bare Direct WSS no longer single foundation of realtime; ADR-028 4-layer architecture intent is correct path. Divergence: timing of track close (now vs after T2+Arm G) and which option (A pure vs Reality-tunneled vs Matrix-style). Vladislav-locked path: refined working-architect plan (T2+Arm G before final close, 1-week time-box).
- **Memory.** Detailed Arm A.2 result + five-hypothesis open set + T2/Arm G next-step plan preserved in memory entry `project_arm_a2_outcome_y_with_asymmetry_2026_06_05.md` for future-track reference.

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

---

## §13 — T2 slow-POST byte-threshold diagnostic (post-Arm-A.2 follow-up mini-lock)

Promoted as a Stage 2 micro-experiment in the §4 Arm A.2 Outcome subsection on 2026-06-05 after the external four-architect review consumed for that outcome introduced the **cumulative-bytes-per-TCP-connection-freeze** hypothesis missed in the original Arm D four-hypothesis open set. T2 directly discriminates that hypothesis. Locked by Vladislav on 2026-06-06 with hard time-box 1 week from §4 Arm A.2 Outcome PR landing.

**Goal.** Discriminate hypothesis 5 (cumulative-bytes freeze, `net4people/bbs Issue #490` 14-32 KB threshold on RU mobile operators) from the other four hypotheses in the Arm A.2 Outcome open set. T2 does this by sending a slow chunked HTTP POST to the relay — 40 960 bytes total, 8 chunks × 5120 bytes, 10-second delay between chunks, `sink.flush()` after each chunk — and reading the relay's per-chunk `event=slow_post_chunk_received total_bytes=N` counter to determine the exact byte point at which the carrier path "freezes" (if any).

**Why a POST and not a WebSocket.** Per the Independent Audit input: the byte-threshold hypothesis predicts that any single TCP connection accumulating ≥ ~14-32 KB of cumulative payload is frozen by the carrier mid-stream, regardless of whether it carries WS frames or chunked HTTP body. A short HTTPS request (auth/session = 200 in ~663 ms within the same field test) succeeds because it stays under the byte budget; a long-held connection (WS or slow POST) exhausts the budget and is frozen. T2 tests the byte-threshold framing without the WS layer's confounding factors (control vs application opcode, ping/pong scheduling, etc.).

**Refined scope (locked 2026-06-06 after Vladislav review of design + 5 hard gates + 4 architect-review additions):**

- **Primary discriminator = relay-side `total_received`, NOT Android `total_sent`.** Per Vladislav hard gate 2: `write()` accepting bytes proves only OkHttp queue-accept; it does NOT prove physical egress from the device radio. The verdict counter is the relay-side `event=slow_post_chunk_received total_bytes=N` log entry, and the failure-time `event=slow_post_aborted total_bytes=N reason=...` entry which captures the exact cumulative byte count at the moment the stream ends.
- **SEPARATE OkHttp profile for T2.** Per Vladislav hard gate 1: the WebSocket arms' `callTimeout(10s)` would kill the slow POST in 10 seconds before threshold detection. T2's client uses `connectTimeout=5s`, `writeTimeout=30s`, `readTimeout=60s`, `callTimeout=180s`, `protocols(HTTP_1_1)`. Each chunk is written via a `RequestBody` that explicitly calls `sink.flush()` after each chunk emit so the chunk physically egresses (or is queued for egress) before the 10-second `Thread.sleep()` between chunks fires. **Do NOT copy the `OkHttpClient.Builder()` profile from `RcDirectArmA2` or `RcDirectArmD` — those values produce garbage data for T2.**
- **Caddy streaming preflight required BEFORE field test.** Per Vladislav hard gate 3: the operator must verify that Caddy does NOT buffer the entire POST body before forwarding to the relay handler. If Caddy buffers, the per-chunk events all fire at the end and the byte-threshold discrimination fails. The preflight uses an explicit Python uploader (`scripts/t2-slow-post-preflight.py`) — NOT `curl --limit-rate` — because `curl --limit-rate` smooths the throttle but does not guarantee discrete chunks with explicit per-chunk flush. The Python uploader writes raw HTTP/1.1 chunked transfer-encoding framing with explicit `sock.sendall()` per chunk and `time.sleep()` between chunks. Operator watches relay docker logs in parallel; chunk events must appear progressively (every ~10 s), not clustered at end.
- **Body locked at `40960 = 8 × 5120` bytes.** Per Vladislav hard gate 4: 8 chunks × 5120 bytes = 40 960 total, 10 s between chunks = total ~70-80 s. This spans the documented `net4people #490` threshold range (14-32 KB across operators) with margin above and below to read the verdict cleanly.
- **Default-off env flag + cap + header guard.** Per Vladislav hard gate 4: relay endpoint gated by `RELAY_ENABLE_SLOW_POST_DIAG=1` strict `== "1"` parse (any other value fails closed). Body cap 64 KB. Required headers: `Content-Type: application/octet-stream` AND `X-Phantom-Diag: slow-post-v1` (the latter is the anti-stray-POST guard so a random external POST does not run up the byte counter and skew a concurrent diagnostic). When the flag is `false` (production default), the `/diag/slow-post` route is NOT registered and returns 404 — defence-in-depth per Vladislav addition B 2026-06-06 (route off → 404, not live-405).
- **Android wire-up is ONE-SHOT, not reconnect loop.** Per Vladislav hard gate 5: T2 is a single-shot diagnostic — one POST, log outcome, terminate. The Service short-circuit at precedence A → A.2 → **T2** → B → C → D → production starts the POST once and returns; the foreground Service stays alive but T2 itself is finished. Re-running requires app restart.
- **No production traffic promotion.** Debug APK only via `BuildConfig.DEBUG_T2_SLOW_POST_URL`, release-pinned `""`. Production transport stack unchanged — `RelayTransportFactory.kt:71`, `KtorRelayTransport`, `TransportManager` all untouched. The `/diag/slow-post` relay endpoint is itself env-flag-gated and returns 404 in production.

**Discriminator — three locked outcomes:**

- **Relay receives 14-32 KB and aborts** (`event=slow_post_aborted total_bytes=N` where N is in this range) → `net4people/bbs Issue #490` byte-threshold hypothesis **strongly confirmed** on this path. Architectural implication: even long SSE responses die at the threshold; **Matrix-style 25-sec long-poll becomes the mandatory primary realtime pattern**; inside Reality must also use short-cycle (mux / XHTTP).
- **Relay receives > 32 KB but < 40 KB and aborts** → same class of hypothesis (cumulative-bytes freeze) with a different threshold value. Same architectural implication.
- **Relay receives all 40 960 bytes and responds 200 OK** (`event=slow_post_completed total_bytes=40960 elapsed_ms=~70000`) → byte-threshold hypothesis **refuted on HTTP POST through Caddy**. Architectural implication: kill is in hypotheses 1/2/3/4 (OkHttp egress, Caddy WS framing, carrier stateful inspection, or interaction); **Arm G (WS-over-Reality) is the primary next test**.

**Wording bound.** None of the three outcomes attribute the kill (or non-kill) to a layer with confidence higher than the evidence supports. "Refuted on HTTP POST through Caddy" does NOT mean "refuted globally" — WS-over-Caddy may still hit a different mechanism (the asymmetry continuation from Arm D and Arm A.2 outcomes is one such candidate). Arm G follows regardless of T2's outcome.

**Setup.**

1. **Relay endpoint** (this PR). `services/relay/src/config.rs` adds `slow_post_diag_enabled: bool` field, strict `RELAY_ENABLE_SLOW_POST_DIAG="1"` parse. `services/relay/src/routes.rs` adds `slow_post_diag` handler that streams the body via `Body::into_data_stream()`, logs per chunk + terminal events, validates header guards. Route registration is conditional on the flag — when off, route returns 404. The route is mounted **outside the existing 30-second `TimeoutLayer`** because the T2 POST runs ~70-80 s and would otherwise be killed mid-stream by the timeout. Global `RequestBodyLimitLayer` (~65 KB) still applies; in-handler 64 KB cap is strictly below it. `services/relay/src/main.rs` startup log line extends to include `slow_post_diag_enabled=...` flag state.
2. **VPS deploy step (Vladislav-owned, post-merge runbook).** After this PR merges, operator flips `RELAY_ENABLE_SLOW_POST_DIAG=1` on the VPS `.env` (idempotent grep-then-update-or-append pattern matching the PR-6 flag flip from §4 Arm A.2 PR-8a runbook Step 1), recreates the relay container, verifies `slow_post_diag_enabled=true` in the relay startup log.
3. **Caddy streaming preflight (Vladislav-owned).** From the dev machine (Windows / WSL), operator runs `python scripts/t2-slow-post-preflight.py --url https://relay.phntm.pro/diag/slow-post`. In a parallel SSH session, operator tails relay docker logs: `docker logs -f phantom-relay 2>&1 | grep -E "event=slow_post_chunk_received|event=slow_post_completed|event=slow_post_aborted"`. **Pass criterion:** chunk events appear progressively, one every ~10 s, NOT all clustered at end. **Fail criterion:** chunk events all fire after the 70-second window — indicates Caddy buffering. If Caddy buffers, T2 through Caddy is unreliable; operator either tunes Caddy or aborts T2 through Caddy and decides next path (e.g. re-deploy stunnel overlay for T2 path, with the understanding that this then tests stunnel-streaming, not production Caddy).
4. **Android diagnostic class** (this PR). New `apps/android/src/androidMain/kotlin/phantom/android/diagnostic/T2SlowPostDiag.kt`. Streams 8 chunks × 5120 bytes via a custom `RequestBody` that calls `sink.flush()` after each chunk + `Thread.sleep(10_000)` between chunks. Logs `T2_SLOW_POST_chunk_sent seq=N total_sent=N elapsed_ms=T` per chunk write (secondary client-side counter, NOT the verdict). Logs `T2_SLOW_POST_completed`, `T2_SLOW_POST_non_2xx`, or `T2_SLOW_POST_failed` at outcome with `total_sent` at the moment of failure. `OkHttpClient.Builder()` uses T2-specific timeouts (5s/30s/60s/180s), distinct from the WS arms.
5. **BuildConfig wire-up** (this PR). `apps/android/build.gradle.kts` adds `DEBUG_T2_SLOW_POST_URL` field via `localOrEnv("debugT2SlowPostUrl", "DEBUG_T2_SLOW_POST_URL", "")`; release block pins to `""` for defence-in-depth.
6. **AppContainer + Service wire-up** (this PR). `AppContainer` lazy field `t2SlowPostDiag`, double-gated by `BuildConfig.DEBUG && DEBUG_T2_SLOW_POST_URL.isNotEmpty()`. `PhantomMessagingService.onStartCommand` short-circuit branch inserted between Arm A.2 and Arm B (precedence A → A.2 → **T2** → B → C → D → production); calls `t2SlowPostDiag.start()` and returns. Service stays alive (foreground host) but T2 itself terminates after one shot. `onDestroy` teardown mirrors the start gate, calls `t2SlowPostDiag.stop()` in case the Service is killed mid-POST.
7. **Field test (Vladislav-owned).** Tecno + Tele2 LTE 90-second window. Operator builds debug APK with `debugT2SlowPostUrl=https://relay.phntm.pro/diag/slow-post` in `local.properties`, installs, opens Phantom, lets onboarding create identity, returns to home screen. Foreground Service detects flag and triggers `T2SlowPostDiag.start()` automatically. In parallel, operator captures relay docker logs for the 90-second window. After completion, operator captures Android logcat (`RC_DIRECT_*` + `T2_SLOW_POST` tags) and uninstalls the diagnostic APK.
8. **Operator runbook revert.** After capture: revert `RELAY_ENABLE_SLOW_POST_DIAG=1` from `.env` via `sed -i '/^RELAY_ENABLE_SLOW_POST_DIAG=/d' .env`, recreate relay, verify `slow_post_diag_enabled=false` in startup log. T2 diagnostic surface goes away (route returns 404 again).

**Cost.** ~50 LOC relay handler + ~210 LOC Android diagnostic class + ~30 LOC AppContainer/Service wire-up + ~30 LOC build.gradle.kts + ~160 LOC Python preflight script + this §13 mini-lock. 30 min preflight + 90 sec field test + analysis. Single PR; not split like PR-6/PR-7 because the scope is smaller (no reconnect loop, no paired state machine).

**Memory pointer.** Locked design + 5 hard gates + A/B/C/D refinements trail preserved in memory entry `project_next_session_arm_a2_outcome_plus_t2_plus_arm_g_2026_06_05.md`; superseded by this PR's §13 mini-lock landing on master.

**Outcome (Tecno Tele2 LTE field run 2026-06-05 10:35:16 — 10:37:59 UTC, 163-second relay-side window):** **byte-threshold hypothesis class directionally confirmed; observed abort point is significantly EARLIER than the documented `net4people/bbs #490` 14-32 KB range — relay-side abort at `total_bytes=5120` (≈ 5 KB).** Single field run from PR #292 debug APK (`phantom-t2.apk`, `debugT2SlowPostUrl=https://relay.phntm.pro/diag/slow-post`) through production Caddy on Tecno + Tele2 LTE Иркутская after PR-streaming preflight from a wired-LAN+VPN dev machine had PASSed (progressive per-chunk events, no Caddy buffering). Recorded findings:

- **Relay-side counters (`C:\temp\t2-relay-window.log`, captured via `docker logs --since/--until` post-test).** Two `event=slow_post_chunk_received` entries fired at the same wall-clock instant (`2026-06-05T10:35:16.502178Z` and `2026-06-05T10:35:16.502207Z`, both `elapsed_ms=0` within the request scope), with `total_bytes=4090` then `total_bytes=5120` (`chunk_bytes=4090` then `chunk_bytes=1030`). The split is TCP/TLS segmentation of the first Android chunk's 5120-byte burst visible to `axum`'s `Body::into_data_stream()` as two data chunks. After that, the relay request observed **no further body chunks for 163 seconds**, then `event=slow_post_aborted conn_id=1 total_bytes=5120 elapsed_ms=163251 reason="read_error" err=error reading a body from connection` at `2026-06-05T10:37:59.753234Z`. The relay-side verdict counter (per hard gate 2: relay `total_received` is primary) reads 5120 of 40960 bytes — **chunks 2-8 never arrived at the handler**.
- **Concurrent short-POST request bodies reached relay and were processed during the same 163-second window.** Relay logged `event="prekey_publish" identity=bab6daa26fab3528 opk_count=40` three times (`10:35:16.518236Z`, `10:36:17.862272Z`, `10:37:20.063788Z`) and `event="rest_session_issued" identity=bab6daa2 token_prefix=22f706f5` once (`10:35:16.541050Z`). Server-side ingestion of short bodies was uniformly unaffected. **Client-side response reception was NOT uniformly successful, however.** The `/auth/session` POST returned 200 to the Android client (rest_session bootstrapped cleanly per `T2_SLOW_POST_service_short_circuit` firing immediately after onboarding completed). For `/prekeys/publish` the Android client logged three `PREKEY_TRACE prekey_publish_start ... attempt=N/3` events but ALSO logged `PREKEY_TRACE prekey_publish_retry reason=SocketTimeoutException` at `05:36:15.397` (attempt=1, `elapsedMs=60580`) and `05:37:16.761` (attempt=2, `elapsedMs=60853`) — the client wrote the request body, the relay processed it (per the three server-side `prekey_publish` events), but the response did not return to the client within OkHttp's `readTimeout` for attempts 1 and 2. This is consistent with byte-budget / duration-budget class behaviour extending to the response direction on sufficiently long-held connections, not just the upload direction.
- **Tecno client-side T2 counters DID capture in this run (correction to PR #293 initial body).** Logcat at `C:\temp\t2-tecno-tele2.log` (UTF-16 LE) decoded to `C:\temp\t2-tecno-tele2-utf8.log` shows the full T2 timeline: `T2_SLOW_POST_service_short_circuit endpoint_url=https://relay.phntm.pro/diag/slow-post gen=1` at `05:35:14.808`, `T2_SLOW_POST_armed total_bytes=40960 chunk_bytes=5120 chunk_count=8 delay_ms_between_chunks=10000 connect_timeout_ms=5000 write_timeout_ms=30000 read_timeout_ms=60000 call_timeout_ms=180000 protocols=HTTP_1_1` at `05:35:14.810`, **all 8 `T2_SLOW_POST_chunk_sent` events** at `05:35:15.154` (seq=1, `total_sent=5120 elapsed_ms=344`) through `05:36:25.175` (seq=8, `total_sent=40960 elapsed_ms=70365`), and terminal `W T2_SLOW_POST_failed t=SocketTimeoutException msg=timeout total_sent=40960 elapsed_ms=130377` at `05:37:25.187`. Android's `T2SlowPostDiag` therefore reports its `RequestBody.writeTo()` returned successfully for all 40960 bytes (custom `RequestBody` performed `sink.flush()` after each chunk and OkHttp accepted every chunk), then `Call.execute()` blocked waiting for the response and tripped `readTimeout=60s` ~60 seconds after the last chunk write — wall-clock at the timeout fires 30 sec before the relay's own `read_error` abort (130 377 ms client vs 163 251 ms relay) which is the OkHttp-side and relay-side timeouts firing at their respective independent deadlines on the same dead connection. This **strengthens rather than weakens** the verdict: the Android client believed it had pushed every chunk into the OkHttp socket, OkHttp's per-chunk `sink.flush()` returned for every chunk, yet the relay physically received only the first 5120 bytes. The discriminator is the gap between `total_sent=40960` (queue-accept) and `total_received=5120` (physical egress) — exactly the asymmetry hard gate 2 anticipated when it locked the relay counter as primary.
- **Isolation caveat (honest).** T2 was NOT a sole-connection field test. Relay logs and Tecno logcat both show the diagnostic POST ran concurrently with production bootstrap traffic from the same install (prekey publish + auth/session issuance after onboarding) and on the same `T2SlowPostDiag.kt` start path. The discriminator still holds — server-side short-body ingestion was uniformly unaffected while the long-held T2 POST stalled at ~5 KB — but I cannot rule out that the precise abort-byte-count (5120) interacted with the simultaneous short-POST traffic on adjacent TCP connections that may share carrier-allocated TCP/PDP context. The verdict is therefore "byte-threshold class confirmed" not "exact threshold 5120 ± ε confirmed."
- **Wording bound (carried forward from Arm A.2 Outcome).** The `net4people/bbs #490` documented threshold range is 14-32 KB; the observed abort point on this carrier (Tele2, Иркутская, LTE) on this device (Tecno Spark) at this hour is ≈ 5 KB. I do NOT claim "`net4people` exact threshold confirmed on Tele2." The honest framing is: **the external 14-32 KB class hypothesis is directionally confirmed, and the observed abort point is earlier than the documented range** — the class of failure (cumulative-bytes-per-TCP-connection freeze on RU mobile uplink) matches, the magnitude does not. The early cutoff strengthens rather than weakens the architectural implication: bare Direct mobile uplink is even less viable than the published threshold suggested.
- **Architectural implication: bare Direct uplink is demoted as primary realtime path on RU mobile.** The five-hypothesis open set from the Arm A.2 Outcome (1: OkHttp writer / 2: Caddy-TLS / 3: carrier path / 4: interaction / 5: cumulative-bytes-per-connection) narrows: hypothesis 5 has supporting evidence at the byte-threshold class level. Hypotheses 1/2/3/4 are not refuted — the asymmetry continuation from Arm D and Arm A.2 Outcomes (relay sees WS Control Ping but not WS Text echo from the same wall-clock instant) is orthogonal to the long-POST byte-budget failure and may still be live. The architectural pivot is therefore: **any long-held connection on Tele2 LTE uplink is structurally untrustworthy**, regardless of whether it carries WS frames, SSE chunks, or chunked HTTP body. This is not a WS-specific finding — it is a long-connection-uplink finding that subsumes the WS-specific Arm A.2 and Arm D verdicts.
- **Verdict per §5 + §6.** §6 ship criterion (zero `SocketTimeoutException: sent ping but didn't receive pong` in a 15-min field capture) is unaffected by T2 directly — T2 is a POST, not a WS — but the implication for §6 is that even fixing the WS-specific kill would not produce a reliable Direct realtime on Tele2 LTE if the kill class is "long-held uplink connection." §5 decision-tree row "Y met → uplift realtime per ADR-028" continues to fire from Arm A.2; T2 strengthens the architectural component of that row (long SSE responses through Caddy would also die at the byte threshold; Matrix-style 25-second long-poll becomes the mandatory primary REST realtime pattern; inside Reality must also use short-cycle framing such as mux / XHTTP).
- **Track does NOT close on this outcome.** The remaining cheap micro-experiment per the §4 Arm A.2 Outcome "Carry-forward" is Arm G (Reality-tunneled WS through the embedded libXray SOCKS5 listener → `:8443` Stage 5E Reality endpoint → relay). T2 directly informs Arm G design: if Arm G dies in the same byte-budget class, the kill is below all transport layers and the architecture pivot lands on pure REST + Matrix-style long-poll; if Arm G holds ≥ 10 minutes with echo round-trips succeeding, the kill is naked-TLS-WS-pattern specific and Reality-tunneled realtime becomes the primary architectural answer for RU mobile. Hard time-box remains 1 week from the §4 Arm A.2 Outcome PR landing (2026-06-05) — Arm G follows immediately after this outcome PR.
- **Operator runbook revert (this outcome PR's pre-merge step).** After this PR is reviewed and before merge, the operator reverts `RELAY_ENABLE_SLOW_POST_DIAG=1` from the VPS `.env` (idempotent `sed -i 's/^RELAY_ENABLE_SLOW_POST_DIAG=1$/# RELAY_ENABLE_SLOW_POST_DIAG=0  # T2 closed 2026-06-05/' .env`), recreates the relay container, verifies `slow_post_diag_enabled=false` in the relay startup log, and verifies `POST /diag/slow-post` returns 404. The T2 diagnostic surface goes away (route returns 404 again) and the relay returns to the §4 Arm A.2 post-tear-down baseline state. xray REALITY production on `:8443` remains untouched.
- **Memory.** T2 outcome + 5-KB observed cutoff + isolation caveat + byte-threshold-class wording preserved in memory entry `project_t2_outcome_2026_06_05.md` for future-track reference.

## §14 — Arm G Reality-tunneled WS heartbeat diagnostic (post-T2 follow-up mini-lock)

Promoted as the final cheap micro-experiment in the §4 Arm A.2 Outcome carry-forward and re-confirmed in the §13 T2 Outcome carry-forward. T2 demoted bare Direct mobile uplink as primary realtime path on RU mobile; the remaining open architectural question is whether wrapping the inner WS+Text heartbeat in an outer Reality-VLESS tunnel through the Stage 5E `:8443` endpoint changes the kill behaviour. Arm G answers that question with a single 10-15 minute field test on Tecno + Tele2 LTE.

**Goal.** Discriminate whether the kill that subsumed both Arm A.2 (WS-specific control/application asymmetry through stunnel `:8444`) and §13 T2 (long-connection-uplink failure at ~5 KB through Caddy `:443`) survives when the underlay is an outer Reality+VLESS tunnel terminated at the operator Xray endpoint instead of a bare TLS connection terminated at Caddy or stunnel. Arm G runs the same `phantom:diagnostic:heartbeat-echo:v1:<seq>:<client_ms>` payload, the same `Inv-DataFrameNotControlFrame` invariant, and the same SEND_TIME_MAP_CAPACITY lifecycle that Arm A.2 / Arm D shipped, but routes the OkHttp WS client through the embedded libXray SOCKS5 listener instead of connecting directly to relay.

**Why Reality is the discriminator.** Stage 5E.B (PR-Stage5E series, 2026-05-07) production-validated on Tecno + МТС that VLESS+REALITY at `:8443` carries arbitrary TCP through TSPU on RU mobile without the 16-KB-curtain throttle. If Arm G's WS survives ≥ 10 minutes with echo round-trips succeeding, the kill that took down Arm A.2/D/T2 is naked-TLS-WS-pattern specific — Reality buffers/obfuscates traffic effectively enough that the inner WS+Text heartbeat tunnels through. If Arm G dies in the same Mode 2 signature or in the same byte-budget class as T2, the kill is below all transport layers we can stack short of full UDP+QUIC (which is blocked on RU per the Independent Audit), and the architecture pivot lands on pure REST + Matrix-style 25-second long-poll (Option A) — Reality has done its job for plain HTTP but realtime needs a different design entirely.

**Refined scope (locked 2026-06-05 after Council on T2 outcome + 7 explicit decisions + 4 code-state notes verified against master `d0f41fbe`):**

- **Clean diagnostic isolation, NOT production-realism.** Arm G short-circuits in `PhantomMessagingService.onStartCommand` BEFORE production `transport.connect(...)` and BEFORE production `prekey publish` + `auth/session` bootstrap on the production transport path. The discriminator answers exactly one question — does the WS+Text heartbeat survive when the underlay is Reality — and answers it without the carrier-side state confounders T2 introduced by running concurrently with production bootstrap traffic (T2 Outcome isolation caveat, §13). The signed-challenge identity / `myPubKey` / `signingPubKeyHex` are derived the same way the Service derives them today for Arm A.2 / Arm D / T2 (so the relay-side identity is byte-identical to the WS-arm baselines), but no production transport traffic runs in parallel — `Inv-ParallelArmIsolation` is enforced at the service short-circuit level.
- **Reuse production `xrayService` singleton from a debug-only Arm G harness.** The production lazy field `AppContainer.xrayService` ([apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt:649-653](apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt#L649)) wraps `createXrayService(OperatorXrayConfig.toConfig(dataDir.absolutePath))` with the default `socksPort = pickFreeLoopbackPort()`. Arm G's diagnostic class touches the same lazy field, calls `xrayService.start()`, waits for `XrayState.Ready(socksPort)`, routes its OkHttp client through `Proxy(SOCKS, InetSocketAddress("127.0.0.1", socksPort))`, and calls `xrayService.stop()` in teardown. This is safe at master `d0f41fbe` because the only other materialiser of the singleton is the `xrayServiceProvider = { xrayService }` lambda passed to `TransportManager` ([AppContainer.kt:676](apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt#L676)), and `TransportManager` is reached only via `transport.connect(...)` in the production path — which Arm G short-circuits before. Conditions: (i) Arm G short-circuit is BEFORE `transport.connect(...)` in the precedence chain (locked by §14 below); (ii) Arm G GUARANTEES `xrayService.stop()` in `onDestroy` teardown even if the Service is killed mid-diagnostic (otherwise the embedded libXray daemon survives the diagnostic and may collide with production private-mode on next launch).
- **SOCKS port is dynamic, NOT hardcoded `10808`.** Arm G must NOT contain `localhost:10808` as a constant anywhere. The port comes from `XrayState.Ready(socksPort)` ([XrayState.kt:32](shared/core/xray/src/commonMain/kotlin/phantom/core/xray/XrayState.kt#L32)) AFTER `xrayService.start()` returns and the state transitions. The reason is documented inline at [OperatorXrayConfig.kt:43-49](shared/core/xray/src/androidMain/kotlin/phantom/core/xray/OperatorXrayConfig.kt#L43-L49): cross-device test 2026-05-10 hit `bind: address already in use` on every fresh install when a sibling Xray-style client (V2RayNG / Outline / shadowsocks-android) was already holding the canonical 10808 port. Arm G inheriting this bug would silently fail to connect on any device with one of those apps installed.
- **`Ready(socksPort)` wait condition uses `withTimeout(15_000L)`.** The wait sequence is `withTimeout(15_000L) { xrayService.state.first { it is XrayState.Ready } }`. Rationale: Stage 5E observed time-to-Ready ~2-3 seconds on Tecno Tele2 LTE; 15 seconds gives a 5× margin. If the wait times out, Arm G MUST log `RC_DIRECT_ARM_G_xray_ready_timeout outcome=xray_not_ready` and terminate the diagnostic WITHOUT attempting WS connect. Reason: a stalled `xrayService.start()` must not turn the diagnostic into an indefinitely-hanging foreground session that the operator can only kill by force-stop.
- **Same heartbeat payload, lifecycle, and `Inv-DataFrameNotControlFrame` as Arm A.2 / Arm D.** Payload format: `phantom:diagnostic:heartbeat-echo:v1:<seq>:<client_ms>`. SEND_TIME_MAP_CAPACITY = 32. Shield #1 + shield #2 from PR #276 (Arm A PR-3b), `openedAt: CompletableDeferred<Long>` gating between `onOpen` and the heartbeat coroutine (PR #280 P1 fix), `CancellationException` rethrow in the heartbeat coroutine (PR #280 P2 fix). Outbound heartbeat frames are WS Text only; never WS Ping/Pong. Relay-side `RELAY_ENABLE_HEARTBEAT_ECHO=1` is REQUIRED on the VPS for this run — the operator pre-merge step toggles it, the post-test runbook reverts it (same pattern as §4 Arm A.2 PR-8a runbook).
- **Echo round-trips are REQUIRED for PASS, NOT lifetime alone.** A WS that lives ≥ 10 minutes but never delivers a single `RC_DIRECT_ARM_G_echo_received` is not a PASS — Arm A.2 already showed Mode 2 where the WS lifetime is ~45 seconds with 1 successful Pong but 0 echo received, and Arm D before that showed the control/application asymmetry where WS Ping survives but WS Text dies. Lifetime alone would conflate "Reality keeps the TCP alive" with "the full app-data round-trip works." PASS requires BOTH (lifetime ≥ 10 min in a single session OR across reconnects without dropping below the threshold AT ANY POINT) AND (echo round-trips landing at the relay with `event=heartbeat_echo_received` AND landing back at the client with `RC_DIRECT_ARM_G_echo_received`).
- **Single 15-minute field run on Tecno + Tele2 LTE.** Duration formula: 10 minutes is the minimum discriminator (Mode 2 baseline lifetime ~45 sec × ~13 reconnects = clear Mode 2 signature within 10 min if it fires); 15 minutes is the locked field-run duration for stronger confidence and to surface any secondary byte-budget effect on a long Reality session. If PASS is unambiguous at 10 min, the run continues to 15 min anyway — the extra 5 min is cheap signal that costs only operator wait time.
- **PR structure is split into three PRs: PR-G1 docs-only mini-lock (this PR), PR-G2 Android diagnostic code, PR-G3 outcome docs after field test.** This is firmer than the §13 T2 single-PR structure because Arm G introduces the first SOCKS5-proxied diagnostic surface in the codebase and the first reuse-of-production-XrayService pattern in a debug path — those are surfaces I want explicit pre-code review on (PR-G1 lands, architect reviews mini-lock; PR-G2 lands, architect reviews code against the locked mini-lock; PR-G3 lands, architect reviews the outcome against the locked PASS/FAIL gates). The 1-week time-box (§4 Arm A.2 Outcome 2026-06-05 + 1 week = 2026-06-12) accommodates three PRs because PR-G1 is small and PR-G3 is small; PR-G2 is the substantive one.
- **WORKING_RULES rule 8 carve-out — narrow scope.** Arm G is Android transport-adjacent (new OkHttp client profile + first SOCKS5 proxy usage in a diagnostic surface). The carve-out is: debug-only; only the Arm G OkHttp client uses SOCKS; the SOCKS endpoint is loopback `127.0.0.1:<Ready.socksPort>` from the embedded libXray daemon (NOT a system proxy, NOT a remote bind); release build pins `DEBUG_RC_DIRECT_ARM_G_VIA_REALITY=""` to `""`; Arm G NEVER writes to `TransportPreferences.lastWorkingTransport` or any other production DataStore / preferences surface; Arm G NEVER calls into `TransportManager`.

**Hard gates (all must hold; mini-lock PR-G2 review checklist):**

1. `BuildConfig.DEBUG && BuildConfig.DEBUG_RC_DIRECT_ARM_G_VIA_REALITY == "1"` — strict `== "1"` parse, fails closed on any other value (mirrors `RELAY_ENABLE_HEARTBEAT_ECHO` / `RELAY_ENABLE_SLOW_POST_DIAG` patterns).
2. `BuildConfig.RELAY_URL` untouched. `RelayTransportFactory.kt` untouched. `TransportManager` untouched. `KtorRelayTransport` untouched. Production transport stack receives zero code change.
3. xray logs from Arm G are redacted: UUID / shortId / publicKey / signed-challenge token are NEVER logged. Only `start_requested` / `ready socksPort=N` / `stop_done` / `failed message_class=N` (logged at start/Ready/stop boundaries; mid-run failures use a generic class enum rather than the libXray error string which may embed credentials).
4. Server-side: zero changes. xray REALITY production on `:8443` is reused exactly as Stage 5E.B.5 deployed it. `OperatorXrayConfig.SERVER_HOST` / `SERVER_PORT` / `SNI` / `PUBLIC_KEY` / `SHORT_ID` / `UUID` reused via `toConfig()`. Operator pre-merge step toggles `RELAY_ENABLE_HEARTBEAT_ECHO=1` on `.env` (same flag T2 / Arm D / Arm A.2 used); post-test runbook reverts it.
5. xray lifecycle structured logs: `RC_DIRECT_ARM_G_xray_start_requested`, `RC_DIRECT_ARM_G_xray_ready socksPort=N elapsed_ms=T`, `RC_DIRECT_ARM_G_xray_ready_timeout outcome=xray_not_ready elapsed_ms=15000`, `RC_DIRECT_ARM_G_xray_stop_done`, `RC_DIRECT_ARM_G_xray_failed message_class=N`. PASS verdict requires the `ready` event before any WS connection attempt.
6. `Inv-ParallelArmIsolation` enforced at the Service short-circuit: when Arm G short-circuit fires, NO production `transport.connect(...)` runs and NO production prekey/auth bootstrap traffic runs. The relay sees a single Arm G WS connection per `myPubKey` identity at any given time, not a production session in parallel.
7. Arm G short-circuit slot is between Arm D and the production fall-through in `PhantomMessagingService.onStartCommand`. Precedence chain after this PR's PR-G2 lands: `Arm A → Arm A.2 → T2 → Arm B → Arm C → Arm D → Arm G → production`.
8. teardown invariant: `onDestroy` MUST call both `container.rcDirectArmG?.stop()` AND `container.xrayService.stop()`. The teardown for the diagnostic class itself is parallel to the existing `rcDirectArmA2?.stop()` / `t2SlowPostDiag?.stop()` pattern; the additional `xrayService.stop()` is unique to Arm G because Arm G is the only diagnostic that materialises the production XrayService singleton.
9. fail-fast on any of: `xray_not_ready` after 15-sec timeout; `xray_failed` from `XrayState.Failed`; WS handshake error before `onOpen`; echo gaps exceeding `ECHO_TIMEOUT_MS` (defined identically to Arm A.2 / Arm D); silent reconnect storm (more than 5 reconnects in 60 seconds — indicates Mode 2 firing). Each failure mode emits a distinct structured log line and the outcome PR records which fired.

**Discriminator — three locked outcomes (mirrors §13 T2 discriminator structure):**

- **PASS — WS lifetime ≥ 10 minutes in a single session (or across reconnects without dropping below threshold at any point) AND echo round-trips succeeding (`heartbeat_echo_received` events at relay AND `RC_DIRECT_ARM_G_echo_received` events at client) AND no Mode 2 signature ("first session 0 pong then 1 pong" rhythm at ~30-45 sec lifetime).** Architectural verdict: Reality-tunneled realtime is viable for RU mobile. Architecture pivot: Reality becomes the **primary** outer transport for realtime in private mode; bare Direct WS demoted to "Standard mode on clean networks only"; 3.2b.1 (WS health detection escalation, currently parked) becomes the safety-net trigger that promotes a stuck Direct WS session to Reality without user intervention. Cost estimate: ~3-4 weeks implementation (3.2b.1 design+implementation, transport-manager priority adjustment, Reality-as-default for Tele2/RU detection, field validation).
- **PARTIAL — WS lifetime ≥ 10 minutes BUT echo round-trips fail (relay never sees `heartbeat_echo_received`).** Architectural verdict: Reality keeps the TCP connection alive end-to-end but the inner WS+Text payload still hits the control/application asymmetry that Arm A.2 / Arm D documented. Reality is a **necessary-but-not-sufficient** transport layer. Decision: the architecture pivot lands closer to the FAIL branch — pure REST + Matrix-style long-poll becomes the primary path, but Reality is retained as the safety-net transport for the REST short-poll layer when Direct breaks. Cost estimate: ~6-8 weeks (between PASS and FAIL costs).
- **FAIL — WS dies in same Mode 2 signature (lifetime ≈ 3× ping_interval, "first 0 then 1 pong" rhythm) OR in same byte-budget class as T2 (Reality-side TCP freezes at cumulative bytes well below the 14-32 KB documented range).** Architectural verdict: the kill is below all transport layers PHANTOM can stack short of UDP+QUIC (blocked on RU per Independent Audit). The decision lands on Option A pure: REST + Matrix-style 25-second long-poll architecture, abandon Direct WS concept. Reality is retained ONLY as the outer transport for HTTP fallback (which Stage 5E already validates), not as a realtime carrier. Cost estimate: ~6-8 weeks (3.2b.1 deprecated; Matrix-pattern long-poll design+implementation; transport-manager rewrite to REST-primary).

**Architecture decision tree after Arm G outcome (Vladislav-locked refinement to §4 Arm A.2 Outcome carry-forward decision matrix):**

| Arm G outcome | Architectural pivot | Cost estimate |
|---|---|---|
| **PASS** (lifetime ≥ 10 min + echo + no Mode 2) | Reality-primary realtime for RU mobile + 3.2b.1 safety net for stuck-Direct escalation | ~3-4 weeks |
| **PARTIAL** (lifetime ≥ 10 min + echo fails) | REST + Matrix-style long-poll primary + Reality as REST-fallback safety net | ~6-8 weeks |
| **FAIL** (Mode 2 persists OR byte-budget class persists) | Pure REST + Matrix-style 25-sec long-poll (Option A), abandon Direct WS concept | ~6-8 weeks |

**Wording bounds carried forward and locked:**

- Does NOT claim "Reality is the solution" or "Reality fixes Direct realtime" before Arm G result is in.
- Does NOT claim "Reality definitely buffers carrier inspection well enough" — Stage 5E.B.5 validated Reality for HTTP through Caddy on RU mobile, but the inner WS heartbeat pattern was not measured during that work.
- Does NOT claim "Arm G FAIL = REST is the only path" — REST + Matrix-style long-poll is the locked fallback per the decision tree, but a future Reality protocol upgrade (Vision / XHTTP / mux) could re-open the realtime question.
- Does NOT claim "the 1-week time-box is hard-stop, abandon Arm G if it slips." The time-box is a discipline budget — if PR-G2 review surfaces a real blocker (e.g. libXray AAR fails to bind SOCKS on Tecno Spark for an unrelated reason), the budget is extended explicitly via a §15 mini-lock rather than rushed past.

**Setup.**

1. **PR-G1 docs-only mini-lock (this PR).** New §14 subsection of `docs/tracks/rc-direct-stability1.md` capturing this design lock. PROJECT_LOG entry. MASTER_TIMELINE bump. Memory pointer update. Zero code change.
2. **PR-G2 Android diagnostic code (next PR after PR-G1 review).** New `apps/android/src/androidMain/kotlin/phantom/android/diagnostic/RcDirectArmG.kt` (~250 LOC) following the `RcDirectArmA2` / `RcDirectArmD` near-clone shape. New `DEBUG_RC_DIRECT_ARM_G_VIA_REALITY` BuildConfig field via `localOrEnv("debugRcDirectArmGViaReality", "DEBUG_RC_DIRECT_ARM_G_VIA_REALITY", "")` in `apps/android/build.gradle.kts`; release block pins to `""`. AppContainer lazy field `rcDirectArmG` double-gated by `BuildConfig.DEBUG && DEBUG_RC_DIRECT_ARM_G_VIA_REALITY == "1"`. `PhantomMessagingService.onStartCommand` short-circuit branch inserted between Arm D and the production fall-through; `onDestroy` teardown calls both `rcDirectArmG?.stop()` AND `xrayService.stop()`. Six (or more) integration tests in the diagnostic class for hard gates 1-9 above. `./gradlew :apps:android:assembleDebug` must remain `BUILD SUCCESSFUL` with `debugRcDirectArmGViaReality=""` (the default empty string is the production state).
3. **VPS operator pre-merge step for PR-G2 field test.** Operator flips `RELAY_ENABLE_HEARTBEAT_ECHO=1` on the VPS `.env` (idempotent grep-then-update-or-append pattern matching the §4 Arm A.2 PR-8a runbook); recreates the relay container; verifies `heartbeat_echo_enabled=true` in the relay startup log. Same flag the Arm A.2 / Arm D runs already used; no new env flag. xray REALITY production on `:8443` is reused exactly — zero server-side code change.
4. **Field test (operator-owned, post-PR-G2 merge).** Tecno + Tele2 LTE Иркутская, 15-minute capture window. Operator builds debug APK with `debugRcDirectArmGViaReality=1` in `local.properties`, installs on Tecno, opens Phantom, lets onboarding create identity, returns to home screen. Foreground Service detects the flag and triggers `RcDirectArmG.start()` automatically. In parallel, operator captures relay docker logs for the 15-minute window: `docker logs -f phantom-relay 2>&1 | grep -E "event=heartbeat_echo_received|event=heartbeat_echo_sent|event=heartbeat_echo_rejected|event=ws_protocol_ping_received|event=ws_protocol_pong_sent|event=ws_open|event=ws_failure|event=session_summary"`. After completion, operator captures Android logcat (`RC_DIRECT_ARM_G:V PhantomMessaging:V PhantomTransport:V PhantomHybrid:V PhantomRelay:V TransportManager:V RestStateMachine:V`) into `C:\temp\arm-g-tecno-tele2.log`.
5. **PR-G3 outcome docs after field test analysis.** §14 Arm G Outcome subsection appended (mirrors §4 Arm A.2 Outcome and §13 T2 Outcome subsection structure): verbatim relay + logcat evidence; verdict per the locked PASS/PARTIAL/FAIL discriminator; architecture decision tree row that fires; wording bounds.
6. **Operator runbook revert (PR-G3 pre-merge step).** Operator reverts `RELAY_ENABLE_HEARTBEAT_ECHO=1` from `.env` (`sed -i 's/^RELAY_ENABLE_HEARTBEAT_ECHO=1$/# RELAY_ENABLE_HEARTBEAT_ECHO=0  # Arm G closed 2026-06-XX/' .env`), recreates relay, verifies `heartbeat_echo_enabled=false` in startup log. The heartbeat-echo relay surface goes dormant.

**Cost.** PR-G1: this docs-only mini-lock + PROJECT_LOG + MASTER_TIMELINE + memory. ~1-2 hours. PR-G2: ~250 LOC `RcDirectArmG.kt` + ~30 LOC AppContainer wire-up + ~30 LOC Service short-circuit + ~10 LOC build.gradle.kts + 6 integration tests + this §14 mini-lock cross-reference in PR description. ~1 day. PR-G3: ~10 bullets + memory entry. 30 min preflight + 15-min field test + analysis. ~2-3 hours. Total time-box: 1 week from §4 Arm A.2 Outcome PR landing (2026-06-05) = 2026-06-12 hard deadline.

**Source-of-truth pointers.**

- Production Xray endpoint config: [shared/core/xray/src/androidMain/kotlin/phantom/core/xray/OperatorXrayConfig.kt](shared/core/xray/src/androidMain/kotlin/phantom/core/xray/OperatorXrayConfig.kt) (constants + `toConfig(dataDir, socksPort = pickFreeLoopbackPort())`)
- XrayService lifecycle facade: [shared/core/xray/src/commonMain/kotlin/phantom/core/xray/XrayService.kt](shared/core/xray/src/commonMain/kotlin/phantom/core/xray/XrayService.kt) + [XrayState.kt](shared/core/xray/src/commonMain/kotlin/phantom/core/xray/XrayState.kt) (`Off` / `Starting` / `Ready(socksPort)` / `Failed(message)`)
- XrayServiceFactory implementation: [shared/core/xray/src/androidMain/kotlin/phantom/core/xray/XrayServiceFactory.android.kt](shared/core/xray/src/androidMain/kotlin/phantom/core/xray/XrayServiceFactory.android.kt) (sets `Ready(socksPort = config.socksPort)` at line 58 after libXray runtime is up)
- Existing production wire-up: [apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt:649-653](apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt#L649-L653) (`xrayService` lazy) and [AppContainer.kt:676](apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt#L676) (`xrayServiceProvider = { xrayService }` lambda to TransportManager)
- Diagnostic short-circuit precedent: [apps/android/src/androidMain/kotlin/phantom/android/service/PhantomMessagingService.kt:496-593](apps/android/src/androidMain/kotlin/phantom/android/service/PhantomMessagingService.kt#L496-L593) (Arm A.2 + T2 short-circuit blocks)
- Heartbeat echo relay handler: [services/relay/src/routes.rs:523](services/relay/src/routes.rs#L523) (`event=heartbeat_echo_received` per-frame log fired regardless of session close — dispositive even when session_summary is absent)

**Memory pointer.** Arm G mini-lock design + 7 explicit Council decisions + 4 code-state notes verified against master `d0f41fbe` preserved in memory entry `project_arm_g_minilock_2026_06_05.md`; superseded by this PR's §14 mini-lock landing on master.
