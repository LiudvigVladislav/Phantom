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

1. Operator edits `deploy/docker-compose.yml` to add `ports: ["127.0.0.1:8081:8080"]` to the relay service block. The CI gate from PR #273 enforces the loopback prefix at merge time. A short comment alongside the new line documents the revert TODO.
2. Operator runs `docker compose up -d relay` on the VPS. ~5 s container restart; existing WS users drop and reconnect via REST fallback per `RestStateMachine`.
3. Operator opens an SSH local-forward from a dev machine to the VPS loopback: `ssh -L 8081:127.0.0.1:8081 root@phntm.pro`. The dev machine now reaches the relay's bypass listener at `127.0.0.1:8081`.
4. I build a diagnostic Android APK `phantom-bypass-arm-a.apk` with `BuildConfig.DEBUG_BYPASS_URL = "ws://10.0.2.2:8081/ws"` (emulator) OR the SSH-tunnel dev-machine address that Tecno can reach during the test window. The new diagnostic class `RcDirectArmA` is a sibling of `RcDirectArmB` (Phase 1) — same raw OkHttp `newWebSocket(...)` pattern, same WebSocketListener telemetry shape, but pointed at the bypass URL.
5. After the field test, the operator removes the `ports:` line + restarts the container. Total revert window ~5 min.

**Auth.** Unchanged from production. The diagnostic class performs the production `/auth/challenge?identity=<hex>` GET + Ed25519 sign + WS upgrade with `?id=&signing_pubkey=&challenge=&signature=`. Auth security held by F-AuthIntegrity even without TLS — see ADR-028 §"Security baseline for plain-WS diagnostic paths" for the rationale.

**Cost.** ~80-100 LOC new client code (`RcDirectArmA.kt` plus AppContainer + PhantomMessagingService wire-up, mirroring the Arm B pattern). One compose-file two-line delta (revertible). One Android APK build with the new debug-only `DEBUG_BYPASS_URL` field.

**Discriminator.** If Arm A's median session lifetime on Tele2 LTE ≥ 3× the v11 Arm A Tele2 baseline (~90 s vs ~31 s baseline), **H-A confirmed** — Caddy is the proximal kill mechanism on at least one mode. If lifetime is within ±25 % of v11 Arm A baseline, **H-B confirmed** — Caddy is innocent, focus moves to lower-layer experiments.

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
3. Build 3 diagnostic APKs (`p-armc-10000.apk` / `p-armc-20000.apk` / `p-armc-30000.apk`). Plus baseline `p-armc-0.apk` (= raw OkHttp at production 15 s, equivalent to Arm B from Phase 1).
4. 4 × 15-min field runs on the worst-affected network (Tele2 LTE per Phase 2 evidence). Logcat capture with `RC_DIRECT_ARM_C_*` tag + PCAPdroid optional (re-use Phase 2 capture protocol if Inv-WallClockAlignment-grade evidence is needed).

**Cost.** ~50 LOC new client code (RcDirectArmC.kt is a near-clone of RcDirectArmB with a single Builder parameter change). 1 hour of field test execution (4 × 15 min runs).

**Discriminator.** If one ping-interval value (e.g. 20 000 ms or 30 000 ms) produces median lifetime ≥ 3× v11 Arm B Tele2 baseline, **H-C confirmed**. That value is then a candidate for production promotion in a separate named PR. If all four values produce statistically identical lifetimes, **H-C refuted**.

### Arm D — Data-frame heartbeat diagnostic (client + relay coordinated)

**Goal.** Probe H-D by maintaining a periodic application-data-frame heartbeat (WS Text or Binary, not Ping) on the diagnostic path. Verify whether data-plane traffic survives the conditions where control-plane Pong dies.

**Setup.**

1. **Prerequisite (relay PR, ships first).** Small relay-side PR adds an echo-opcode handler in `services/relay/src/routes.rs` for a specific WS Text payload (e.g. `"\x00HEARTBEAT-ECHO"`), returning the same payload as a Text frame. Behind a feature flag gated by env var `RELAY_ENABLE_HEARTBEAT_ECHO=1`, default off. Architect review explicit ACK; passes `Relay CI / build-test`.
2. New diagnostic class `RcDirectArmD` (after relay PR lands on master). Sends one Text-frame heartbeat every 15 s on the diagnostic WS; counts inbound echo replies; emits `RC_DIRECT_ARM_D_echo_received seq=N rtt_ms=...` per round-trip. No production WS path touched.
3. Field run identical to Arm C cadence (Tele2 LTE 15-min capture).

**Cost.** ~30 LOC relay code (echo handler), ~40 LOC Android code (RcDirectArmD), 1 hour field test. Relay PR is small but is the first relay production touch in this track — requires WORKING_RULES rule 9 verification.

**Discriminator.** If echoes arrive consistently while control-plane Pong dies (e.g. 30+ successful echoes round-trip while OkHttp `pingInterval` Ping/Pong dies at cycle 8 or earlier), **H-D confirmed** — kill is specific to control frames or to silent sockets. Then a production short-message heartbeat (Inv-DataFrameNotControlFrame protects against PR-H1e regression) becomes a candidate. If echoes also stop near the death moment, **H-D refuted** — both control and data planes lose together, kill is at a lower layer.

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
| **D** FAIL (echo also dies) | H-D refuted | Kill is below WS frame level — TCP/IP/radio | Continue to Arm E |
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
| **6** | Arm E — debug-gated rotation timer + Inv-RotationNotForCallSignaling gate | ~80 LOC client | None — `DEBUG_RC_DIRECT_ROTATION_MS` debug-only |
| **7** | Track outcome PR — evidence summary §13+ appended to this file + MASTER_TIMELINE bump + PROJECT_LOG entry per `feedback_durable_log.md`. Verdict per §6. | ~200 LOC docs | None |
| **8** (conditional) | Arm F parking-lot mini-lock opens as a separate track only if track outcome is "WS structurally unstable" per §6 FAIL | n/a (separate track) | n/a |

Steps 2-6 do not require all to run sequentially in chat sessions — Arm A is the primary discriminator and may close the track early (e.g. if H-A confirms, jump to RC-CADDY-FIX1 track and the track outcome PR drafts immediately).

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

- **Arm F — SSE / HTTP long-poll alternative.** Triggered only if §6 verdict is FAIL across A-E. Implementation cost is relay-track-scale (new route + new state table + auth-surface adjustment). Designs as its own mini-lock.
- **RC-CADDY-FIX1 track.** Triggered only if Arm A returns H-A (Caddy in kill chain). Production Caddyfile or Caddy replacement work.
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
