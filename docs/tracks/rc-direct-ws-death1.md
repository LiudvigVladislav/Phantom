# RC-DIRECT-WS-DEATH1 — Direct WS 140 s death rhythm root-cause diagnostic mini-lock

Author: assistant + external architect audits (Codex memo + "Глобальный аудит транспортов мессенджеров" PDF) + Vladislav direction.
Status: **rev4, Vladislav-locked 2026-06-02 (tue) after 1-point consistency cleanup of rev3**.
Master at lock: `c1d0c408`.
Sibling tracks: `docs/tracks/ws-health-state.md` (PR-WS-HEALTH-STATE1, currently paused at 3.2b design rev5, code not started).

**Rev4 changes** (Vladislav 1-point cleanup of rev3, 2026-06-02 — rev3 closed four stale-fragment contradictions, but Arm A's "no code change / Cost: 0 LOC / existing production path" framing still co-existed with four live references to a new `RC_DIRECT_ARM_A_*` log prefix + a `"A"` build-flag value. Either Arm A has zero code (rev3 declaration) OR it ships new telemetry plumbing (the leftover references) — not both. Rev4 picks "zero code" consistently, since that is what makes Arm A trivially comparable to v8/v9 baselines and Vladislav's review locked it):

1. **§4 Arm A telemetry phrasing corrected.** Rev3 left "Telemetry tag: `RC_DIRECT_ARM_A_*` for any new log lines added for this track; existing lines stay under their current tags". Since Arm A adds no new code, there are no new log lines to tag. Rev4 replaces with: "No new tag — Arm A uses existing production telemetry (`PhantomRelay`, `TransportRewalkCoordinator`, `ws_ping_timeout_diag`, `session_summary`, `WS_DEGRADED_TELEMETRY` family). The Phase 1 evidence summary correlates Arm A and Arm B by wall-clock timestamps + APK SHA + build commit, not by per-arm log tags."

2. **§7 step 2 build-flag values reduced.** Rev3 listed `"0"` / `"A"` / `"B"` / `"E"`. The `"A"` value was described as "semantically identical to `"0"` but adds the `RC_DIRECT_ARM_A_*` log prefix bookkeeping" — there is no bookkeeping under rev4. Rev4 drops `"A"` from the possible-values list. Final values: `"0"` (disabled, production default; also covers Arm A field runs), `"B"` (Arm B sequential — production Ktor disabled for the run), `"E"` (data-frame heartbeat — Phase 2 only).

3. **§7 step 5 field-run capture wording corrected.** Rev3 said "capturing `RC_DIRECT_ARM_A_*` + `RC_DIRECT_ARM_B_*` log lines". Rev4 replaces with: "capturing existing production telemetry tags (Arm A) + `RC_DIRECT_ARM_B_*` (Arm B run window only)".

4. **§10 `feedback_logcat_format.md` line corrected.** Rev3 added `RC_DIRECT_ARM_A_V RC_DIRECT_ARM_B_V` to the canonical logcat tag set. Rev4 drops the `RC_DIRECT_ARM_A_V` token since Arm A produces no new tag — only `RC_DIRECT_ARM_B_V` is added to the existing set.

**Rev3 changes** (Vladislav 4-point cleanup of rev2, 2026-06-02 — rev2 amended the header change-block + §4 Arm B + §3 Inv-ParallelArmIsolation + §5 decision tree + §6 gate #1 + §7 steps 2-3 + §11 OQ-Q5 but missed four leftover rev1 fragments that contradicted the rev2 amendments):

1. **§3 `Inv-RawArmReadOnly` rewritten.** Rev2 left the invariant describing Arm B as "raw-OkHttp parallel diagnostic arm" with a "separate identity-suffixed session tag (`<identity>_rcD1B`)". Both phrases were superseded by rev2 P1 (sequential, not parallel) and the grep-verified `is_pubkey_hex` constraint (suffix identities fail auth). Rev3 rewrites the invariant under sequential-only semantics with the suffix-tag claim removed.
2. **§6 gate #3 made conditional on emulator reproduction.** Rev2 left gate #3 demanding an unconditional "explicit determination of whether Pong frame 9 is present or absent" in the emulator TCP stream — but rev2 P3 already established that an emulator that does NOT reproduce the death rhythm is a valid Phase 1 outcome, not a pcap failure. Rev3 makes gate #3 conditional: "if Arm C emulator reproduces the death rhythm, the pcap analysis must determine Pong 9 present/absent; otherwise the non-reproduction is itself the recorded outcome and gate #3 escalates to Tecno PCAPdroid or second-Android probe."
3. **§7 step 1 rewritten under sequential / own-`OkHttpClient` semantics.** Rev2 left the implementation-order step calling Arm B a "raw OkHttp parallel diagnostic arm" that "Reuses production `createHttpClientFactory(...)` to extract the preconfigured `OkHttpClient`". Both phrases are wrong post-rev2: parallel is forbidden, and the production factory does not expose the raw `OkHttpClient` (it returns the Ktor `HttpClient` wrapper; `activeOkHttp` is private). Rev3 rewrites step 1 under "sequential after Arm A; constructs its own `OkHttpClient`; no edits to `RelayTransportFactory.kt`".
4. **§10 process gate rev naming + branch name corrected.** Rev2 left the `feedback_session_close_discipline.md` line saying "mini-lock (this file) is rev1 before any code branch" and proposed a code branch named `feat/pr-rc-direct-ws-death1-arm-ab`. After rev2 forbade `"AB"` (parallel A+B), the `arm-ab` branch name is misleading. Rev3 updates both: mini-lock is now rev3 (this revision), and the code branch is `feat/pr-rc-direct-ws-death1-arm-b` (Phase 1 ships Arm B sequential code; Arm A is the existing production path, not a separate branch).

**Rev2 changes** (Vladislav 5-point review of rev1, 2026-06-02):

1. **P1 — Arm B made SEQUENTIAL, not parallel (load-bearing isolation fix).** Rev1 specified Arm B as a parallel diagnostic raw OkHttp connection in the same APK / same session as Arm A, using `<identity>_rcD1B` for relay-side correlation. Grep-verified against `services/relay/src/routes.rs:230-232` (`is_pubkey_hex(s) = s.len() == 64 && s.chars().all(|c| c.is_ascii_hexdigit())`) and `:242` (`/auth/challenge` returns `400 "bad identity"` on anything else): the proposed `<identity>_rcD1B` suffix would fail `is_pubkey_hex` at the auth-challenge step and never establish a WS. Using the production identity unchanged would let the parallel raw socket overwrite `state.clients[identity]` and silently replace the live production socket — exactly the production-behaviour change `Inv-RawArmReadOnly` forbids. Rev2 makes Arm B **sequential after Arm A** in the first wave. Parallel A+B is explicitly **forbidden** until a separate design note ships a relay-side diagnostic-identity namespace (or a relay-side `state.clients[identity]` multi-socket policy) that decouples diagnostic and live sockets at the relay storage layer.
2. **P2 — Arm B raw OkHttp client construction wording corrected.** Rev1 said the arm would "extract preconfigured OkHttpClient" from the production factory. Source check: `RelayTransportFactory.kt:34` returns `HttpClient(OkHttp){ engine { preconfigured = okHttp } }` — i.e. the factory **returns the Ktor `HttpClient` wrapper**, the raw `okHttp` reference lives inside the factory's lambda closure and is not exposed to callers (the `@Volatile activeOkHttp` field on `:32` is for `forceShutdownActiveEngine` and is also not a public extraction point). Rev2 corrects §4 Arm B: the arm **creates its own separate `OkHttpClient`** with identical builder parameters (`pingInterval(15s)`, `readTimeout(60s)`, `connectTimeout(5s)`, `protocols(HTTP_1_1)`) — no extraction, no shared state with production, no edit to `RelayTransportFactory.kt`.
3. **P2 — Acceptance gate softened on Arm B outcome.** Rev1 §6 gate #1 required A + B to both reach "at least 5 WS death cycles". This is wrong-shaped — Arm B holding materially longer than Arm A is itself the strong signal we want from the comparison (it means Ktor adapter is the proximal kill). Rev2 rephrases the gate: "Arm A reproduces the baseline death rhythm AND Arm B either reproduces it OR survives materially longer (defined as ≥ 2 × Arm A median session lifetime in the same run)."
4. **P3 — BuildConfig source + runtime gate made explicit.** Rev1 named the flag but did not specify wiring. Rev2 §7 step 2 specifies: the field is added via `apps/android/build.gradle.kts` (Gradle `buildConfigField`), and the runtime gate is `BuildConfig.DEBUG && BuildConfig.DEBUG_RC_DIRECT_ARM != "0"`. Release builds (`!BuildConfig.DEBUG`) ignore the flag entirely — defence-in-depth against a release build accidentally reading a debug flag.
5. **P3 — Emulator-non-reproduces is its own outcome, not "pcap failed".** Rev1 §4 Arm D framed emulator pcap as "required" without acknowledging that if the emulator does not exhibit the 140 s death rhythm at all, packet capture there says nothing about Tecno's behaviour. Rev2 §4 Arm D + §5 decision tree add an explicit "Emulator does not reproduce the death rhythm" branch: that is a **valid Phase 1 outcome** (Tecno-specific or path-specific) and triggers PCAPdroid-on-Tecno or a second-Android probe before declaring Phase 1 inconclusive.

**This track is strictly diagnostic.** It exists to localise where the 9th Pong is lost in the OkHttp Ping/Pong path that kills the Direct WS session every ~140 s. It ships **no production behaviour change**. It does **not** alter `pingInterval(15s)`, `readTimeout(60s)`, REST orchestration, capability gating, chain selection, or any user-facing surface. Its single output is **evidence** that decides whether 3.2b.1 adaptive-validation code becomes necessary or whether a localised fix in the client / Ktor / Android stack can close the death rhythm at the root.

---

## §1 — Доказанные facts (proven, source-level)

### F1. Direct WS death rhythm is carrier-independent

Three field runs against the same Tecno (`103603734A004351`) on three different network paths show one pattern:

| Test | Network | Sessions | Avg lifetime | Killer signature |
|---|---|---|---|---|
| **#83 v8** | Tele2 LTE cellular | 4 | ~140 s | `SocketTimeoutException: sent ping but didn't receive pong within 15000ms (after 8 successful ping/pongs)` |
| **#83 v9** | Ростелеком Wi-Fi | 5 | ~145 s | identical signature, identical 8-cycle pre-kill count |
| **#83 v10** | Ростелеком Wi-Fi (Private mode `REALITY_FIRST`) | n/a (Reality `/health` failed at 20 s, fall-through to Tor) | n/a | n/a — but Tor demonstrated the first `ws_alive_60s` commit of the series once it eventually came up (~5 min wall clock) |

Source line references (independently grep-verified):

- v8: `C:\temp\test83-v8-tecno.log:191/:192` (s=1 `session_summary` + paired `ws_ping_timeout_diag`), `:368/:369` (s=2 same pair), `:372` (`WS_DEGRADED detected` verdict).
- v9: `C:\temp\test83-v9-wifi2-tecno.log:374` — one `WS_DEGRADED detected` rising edge with `gated_by_ws_candidate=false weighted_sum=4.6`.
- v10: `C:\temp\test83-v10-reality-tecno.log:70` (`probe_returned kind=Reality ok=false elapsedMs=20063`), `:72` (`chain_attempt_failed kind=Reality reason=probe_failed`), `:243` (`send_response id=7eef5720 status=201 elapsedMs=564`), `:443` (`mode_switched WsCandidate→WS_ACTIVE reason=ws_alive_60s`).

### F2. The killer is OkHttp's automatic pinger, not a relay-side bug

PR-WS-HEALTH-STATE1 Commit 3.3 (#259, master `8727031f`) wired two `tracing::info!` lines around the relay-side `Message::Pong` send. Test #83 v7 captured all three log streams synchronously:

- Relay logs `ws_protocol_pong_sent` for **every** Pong including the one that precedes a client-side timeout.
- Tecno OkHttp surfaces `okhttp_successful_ping_pongs=8` in `ws_ping_timeout_diag` for the dying session, then closes with the `RealWebSocket.writePingFrame()` exception text verbatim.

**What v7 proves:** the relay-side send-Pong path is not the failure. **What v7 does NOT prove:** whether the 9th Pong physically reached the device socket buffer (return-path loss between relay and device) OR reached the buffer but was mis-counted by the OkHttp / Ktor / Android stack. These two possibilities are the heart of this diagnostic.

### F3. OkHttp ping/pong source-level mechanism (audit-confirmed)

From the public OkHttp source (`okhttp3.internal.ws.RealWebSocket.writePingFrame$okhttp`):

```
failedPing = if (awaitingPong) sentPingCount else -1
sentPingCount++
awaitingPong = true
if (failedPing != -1) {
  failWebSocket(SocketTimeoutException("sent ping but didn't receive pong within " +
      "${pingIntervalMillis}ms (after ${failedPing - 1} successful ping/pongs)"), null)
}
```

The socket fails on the **next** scheduled ping after a missed pong, not on the moment the pong is missed. The "pong timeout" is **not independent** of the ping interval — it is exactly `pingIntervalMillis`. The single missed Pong arms a one-shot failure that lands on the next 15 s tick. Worst-case detection latency = 2 × ping interval (the unanswered ping is fired, then ~15 s elapses, then the next ping observes `awaitingPong=true` and fails).

### F4. Raw `onPing/onPong` callbacks are NOT exposed by OkHttp's `WebSocketListener`

Per OkHttp issue #3538 (Swankjesse, mainainer): `"Pings are not available in OkHttp's web sockets APIs"`. Internal `pinger` consumes incoming Pong frames silently (`receivedPongCount++; awaitingPong=false`). The internal comment `// This API doesn't expose pings.` confirms the absence of a public hook.

### F5. Ktor `webSocket()` does not surface control frames in `incoming`

Ktor docs and our own code path comment at `KtorRelayTransport.kt:1031-1032` independently state: `"OkHttp WS Ping/Pong control frames are consumed inside OkHttp's engine and never surface as Frame.Pong in Ktor's incoming channel"`. The `webSocketRaw` variant theoretically allows control-frame visibility, but the consumption happens **inside the OkHttp engine** before frames reach Ktor's wire-format layer, so `webSocketRaw` is expected to provide little additional signal on the ping/pong question. This is recorded as a low-priority arm (§4 Phase 2 optional).

### F6. Ktor blocks dynamic ping configuration

Ktor's `install(WebSockets) { pingIntervalMillis = N }` block over the OkHttp engine throws `WebSocketException("OkHttp doesn't support dynamic ping interval...")`. Ping config must live on `OkHttpClient.Builder.pingInterval(...)` (the call site on `RelayTransportFactory.kt:71`). This is a hard wall — any "swap ping interval mid-flight" experiment would require reconstructing the entire OkHttpClient. For raw-OkHttp arm (§4 Arm B), this constraint does not apply.

### F7. Our current production config

- `RelayTransportFactory.kt:71` — `OkHttpClient.Builder().pingInterval(15_000L, TimeUnit.MILLISECONDS)`.
- `RelayTransportFactory.kt:78` — `.readTimeout(60, TimeUnit.SECONDS)`.
- `RelayTransportFactory.kt:108` — `.protocols(listOf(Protocol.HTTP_1_1))`.
- `RelayTransportFactory.kt:170` — `install(WebSockets) { pingIntervalMillis = 0L }` (Ktor's own ping explicitly disabled).
- Numerics multiply out: 8 ping cycles × 15 s = 120 s ping window, +15 s pong timeout = 135 s detection, +~5 s reconnect orchestration overhead = observed 135–151 s.

`deploy/Caddyfile:5` confirms `relay.phntm.pro` is Cloudflare DNS-only — Cloudflare is NOT in the WS proxy chain. Caddyfile:54-55 sets `read_timeout 0` and `write_timeout 0` for the relay reverse_proxy — Caddy is not killing idle WS frames on a timer.

### F8. RC-DIRECT-WS-DEATH1 is the gate for 3.2b.1 code

Per `docs/tracks/ws-health-state.md` § Commit 3.2b (rev5 merged 2026-06-02 in #263 `f692cdcf`) and `MASTER_TIMELINE_2026.md` next-session pointer: **3.2b.1 commonMain code is designed but paused** until this track returns evidence. If RC-DIRECT-WS-DEATH1 finds a local client / Ktor / Android-stack fix that closes the death rhythm at the root, 3.2b.1 may be deprioritised (Direct WS becomes healthy enough that adaptive-validation scaffolding is unnecessary). If RC-DIRECT-WS-DEATH1 confirms server-side or return-path loss, 3.2b.1 becomes a necessary UX shield because the root cause is out of our hands.

---

## §2 — Refined hypothesis space

The audits + F2 narrow the space to four candidates. Each maps to a distinct test outcome.

| H | Statement | What needs to be observed to confirm | What needs to be observed to refute |
|---|---|---|---|
| **H-A** | The 9th Pong does not physically reach the device's socket buffer (return-path loss between relay and device — network / NAT / radio / MTU / Hetzner / TCP-layer issue above WS frame layer) | Packet capture on device shows: Pong frames 1..8 present in TCP recv buffer; Pong frame 9 absent. Relay-side `ws_protocol_pong_sent` line present for cycle 9. | Packet capture shows Pong frame 9 present on device |
| **H-B** | Pong frame 9 reaches the device socket buffer, but OkHttp's `RealWebSocket.onReadPong()` does not execute (reader thread blocked / scheduling / kernel→userspace gap / Android-specific thread management) | Packet capture: Pong frame 9 present on device. **AND** raw OkHttp arm with custom WebSocketListener instrumentation shows no observable `onReadingPongFrame` log near the timeout moment. | Custom listener log shows `onReadingPongFrame` executes for cycle 9, then ~15 s later `writePingFrame` fails anyway |
| **H-C** | `onReadPong()` executes for cycle 9, but a race or mis-ordered read sequence keeps `awaitingPong=true` (OkHttp internal bug or subtle threading) | Reflection / custom subclass of `RealWebSocket` (debug-only) logs `awaitingPong` value at the moment of `writePingFrame` failure: `awaitingPong=true` even though prior `onReadPong()` ran | `awaitingPong=false` immediately before failure (would refute) |
| **H-D** | Everything counts correctly, but the ping-write itself is slow (radio wakeup, write blocks long enough that the next ping cycle starts while the prior pong-wait still has time but the `awaitingPong` check fires anyway) | Packet capture shows ping frames written with > 15 s gaps; OkHttp internal trace shows ping write latency in seconds | Ping writes occur on schedule (< 100 ms variance) |

**H-A** is a network / infrastructure problem. **H-B / H-C / H-D** are client-stack problems. The first-wave arm matrix (§4) is designed to discriminate H-A vs the rest with minimum scaffolding; later arms refine within H-B / H-C / H-D if those are the survivors.

---

## §3 — Invariants enforced by this mini-lock (hard guards)

These survive every Vladislav review of any rev of this design. Code review for the diagnostic code PR will reject the diff if any invariant is violated.

| ID | Invariant | What it forbids |
|---|---|---|
| **Inv-NoProductionBehaviour** | RC-DIRECT-WS-DEATH1 does **not** change any production code path. All diagnostic arms are gated behind a debug build flag (`BuildConfig.DEBUG_RC_DIRECT_ARM=N`) that defaults to `0` (disabled) in release builds. | Any merge to master that affects production WS lifetime, REST orchestration, capability resolution, chain selection, or UI labels. |
| **Inv-NoHeartbeatCadenceFix** | Diagnostic arms do not ship `pingInterval` / `readTimeout` / `callTimeout` changes as fixes. If a measurement shows a different cadence helps a specific arm, it is recorded as an observation, not promoted to production until a separate design note locks the change. | A PR that touches `RelayTransportFactory.kt:71` / `:78` / `:154` based on RC-DIRECT findings without a separate rev1 design note. |
| **Inv-NoAppLevelPingResurrection** | Diagnostic arms may include an **app-level data-frame heartbeat** (Arm E in §4) but only for diagnostic measurement — never as a production reliability mechanism. PR-H1e removed app-level Ping for proven reasons (it halved WS lifetime). This invariant prevents accidentally re-introducing it via diagnostic backfill. | Any code path where the data-frame heartbeat fires when `BuildConfig.DEBUG_RC_DIRECT_ARM != E`. |
| **Inv-NoTransportCapabilityChange** | RC-DIRECT-WS-DEATH1 does not touch `TransportCapabilitiesResolver` (PR-C1), `ConnectionUiState` (Commit 3.1), `ConnectionUiState` notification text, or any calls/voice/media capability surface. | Any diff that imports `TransportCapabilities*` from this track. |
| **Inv-NoChainPolicyChange** | RC-DIRECT-WS-DEATH1 does not touch `TransportManager.reorderChain(...)`, `TransportStrategy.chain`, `lastWorkingTransport`, `KindSuspectStore` (which does not exist anyway in this track), or any chain ordering. | Any diff that imports `TransportManager` or `TransportStrategy` mutations from this track. |
| **Inv-RawArmReadOnly** | **Rev3-amended (parallel + suffix-tag claims removed):** the raw-OkHttp diagnostic arm (Arm B) is a **read-only** observation connection that runs **sequentially after** Arm A's session window closes (relay-side `state.clients[identity]` cleaned up first). It uses the production identity unchanged (the only identity the relay's 64-hex `is_pubkey_hex` validator accepts). It does not send real app envelopes. It does not interact with the live `KtorRelayTransport` while a production session is open. It does not register for ack receipts. It does not participate in REST fallback or outbound flow control. The `RC_DIRECT_ARM_B_*` log prefix is the correlation key, not an identity suffix. | Any code where the raw arm calls `KtorRelayTransport.send(...)` / `RestFallbackOrchestrator.send(...)`, OR any code where the arm runs while a production WS session is open for the same identity. |
| **Inv-ParallelArmIsolation** | **Rev2-amended: parallel A+B in the same APK / same session is FORBIDDEN** until a relay-side diagnostic-identity namespace ships (separate design note required). The relay's `is_pubkey_hex` validator at `services/relay/src/routes.rs:230-232` enforces 64-hex identity; any suffix-based scheme fails auth. Reusing the production identity in a parallel raw socket overwrites `state.clients[identity]` and replaces the live socket — exactly the production-behaviour change `Inv-RawArmReadOnly` forbids. Arm B runs **sequentially** after Arm A's session window closes (relay-side `state.clients[identity]` cleaned up first). If parallel becomes useful in a later phase, a separate design-note PR designs the relay-side namespace (e.g. `?identity_aux=<purpose>` query param accepted by `auth_challenge` and stored under a separate map). | Any diff where Arm A and Arm B run in the same APK process session against the same production identity. |
| **Inv-NoChangeUntilEvidence** | 3.2b.1 commonMain code remains paused until this track produces a written evidence summary that explicitly answers H-A vs H-B/C/D. | Any commit on `feat/pr-ws-health-state1-commit3-2b-1` before this track ships an evidence-summary PR. |

---

## §4 — Experimental arms

The arm naming convention is letter-based to match Vladislav-locked first-wave ordering. Arms inside Phase 1 run in the first APK build; Phase 2 arms ship in follow-up builds only if Phase 1 results require disambiguation.

### Phase 1 — first wave (locked)

#### Arm A — current Ktor baseline (no code change)

Repeat the v8/v9 measurement on the SAME APK build as Arm B so the comparison is causally isolated to the WS-client code path (not "different build, different week, different network conditions"). Capture the standard `WS_DEGRADED_TELEMETRY` family, `ws_ping_timeout_diag`, `session_summary` lines. **No new code. No new log tag.** Arm A uses existing production telemetry (`PhantomRelay`, `TransportRewalkCoordinator`, `ws_ping_timeout_diag`, `session_summary`, `WS_DEGRADED_TELEMETRY` family). The Phase 1 evidence summary correlates Arm A and Arm B by wall-clock timestamps + APK SHA + build commit, not by per-arm log tags.

**Cost:** 0 LOC. The data is the same shape as v8/v9; the only difference is "captured in the same APK build session as Arm B".

**What it proves:** baseline reference for same-build same-session comparison.

#### Arm B — raw OkHttp `newWebSocket(...)` sequential diagnostic arm (separate APK build session, AFTER Arm A)

**Rev2 P1: SEQUENTIAL, not parallel.** Arm B runs AFTER Arm A's capture window closes. Same APK build, separate logcat run. The relay-side identity-uniqueness constraint forbids parallel diagnostic and live sockets sharing one identity (see rev2 P1 change-block); making the arms sequential is the safe path until a relay-side diagnostic-identity namespace is designed and shipped.

**Rev2 P2: arm creates its own `OkHttpClient`.** The arm does NOT extract a client from the production factory (the factory returns a Ktor `HttpClient` wrapper; the raw OkHttp reference is lambda-closure-internal and intentionally not exposed). The arm constructs a **separate** `OkHttpClient` with identical builder parameters: `.pingInterval(15_000L, TimeUnit.MILLISECONDS)`, `.readTimeout(60, TimeUnit.SECONDS)`, `.connectTimeout(5, TimeUnit.SECONDS)`, `.protocols(listOf(Protocol.HTTP_1_1))`. No edits to `RelayTransportFactory.kt`. No edits to `KtorRelayTransport.kt`. The only structural difference vs Arm A's data path is the **absence of Ktor's `install(WebSockets) { pingIntervalMillis = 0L }` wrapper** around the same OkHttp engine.

The arm opens a single WebSocket via `okHttp.newWebSocket(request, listener)`. The `WebSocketListener` overrides log every `onMessage(text)`, `onMessage(bytes)`, `onOpen`, `onClosing`, `onClosed`, `onFailure(t, response)` with the `RC_DIRECT_ARM_B_*` log prefix and a wall-clock timestamp + sequence number.

The arm uses the production identity for `/auth/challenge` (this is the only identity the relay's `is_pubkey_hex` validator accepts). Because Arm B runs **sequentially after** Arm A's session window closes (production Ktor disconnect completed AND relay-side `state.clients[identity]` cleaned up), there is no parallel-socket-replacement risk. The arm runs read-only: it does NOT call `webSocket.send(...)`. It does NOT register for ack receipts. It does NOT participate in REST fallback. It exists solely to observe what its own `WebSocketListener` sees during its own session window.

**Cost:** ~50–80 LOC (one new file in androidMain, one ctor in AppContainer to construct + start under `BuildConfig.DEBUG_RC_DIRECT_ARM == "B"` debug flag). Build flag wiring: ~10 LOC.

**Telemetry tag:** `RC_DIRECT_ARM_B_*` exclusively. Distinct log file destination in the field-test run script so post-hoc analysis can correlate the two streams by wall-clock timestamp.

**What it proves vs disproves:**

- If Arm B holds significantly longer than Arm A in the same session (e.g. Arm B `okhttp_successful_ping_pongs > 20` while Arm A dies at 8): something Ktor-adapter-specific in the production wrapping causes early kill. The fix space narrows to Ktor's WebSocket plugin or its interaction with our wiring.
- If Arm B dies at the same ~8 ping/pongs as Arm A: the Ktor adapter is **not** the proximal cause; whatever kills A also kills B; root cause is below Ktor (OkHttp internals, Android stack, network, MTU). The track advances to packet capture (Arm D) to discriminate H-A from H-B/C/D.

#### Arm C — emulator parity (same APK)

Run the same APK on Android emulator (existing `emulator-5554` per memory) over the same 15-minute window. If emulator shows substantially different rhythm than Tecno (e.g. emulator WS lives > 5 min steady while Tecno dies at 140 s) — strong Tecno-specific signal that narrows H-B / H-C / H-D to Tecno Android stack quirks.

**Cost:** 0 LOC. APK install + adb logcat capture only.

**Operational note:** emulator network path differs from real Tecno (emulator routes through host machine NAT; Tecno routes through carrier/Wi-Fi). This is a known weakness of emulator parity for network-layer questions but a strength for client-stack questions.

#### Arm D — packet capture (emulator required, Tecno best-effort)

**Vladislav-locked: emulator pcap is required IF emulator reproduces the death rhythm. Tecno pcap is best-effort via PCAPdroid (acknowledging that PCAPdroid is VPN-based and may alter the network path).**

**Emulator side:** `adb shell tcpdump` is available on standard emulator images via root shell. The capture filters TCP traffic to/from `relay.phntm.pro` on port 443 during the same 15-minute window as Arm C.

**Tecno side:** if the handset is not rooted (assumed default; confirmed by Vladislav if false), use **PCAPdroid** (https://github.com/emanuele-f/PCAPdroid). It is a VPN-based root-free packet capture tool. Caveats recorded in advance:

- PCAPdroid runs as a local Android VPN. It may change kernel routing and add latency vs direct socket path.
- Any timing observations on Tecno with PCAPdroid running are "PCAPdroid-influenced" not raw production.
- Use Tecno pcap as secondary corroboration of emulator pcap if emulator reproduces; **escalate Tecno pcap to primary evidence if Arm C shows emulator does NOT reproduce the death rhythm** (in that case it is the only available pcap evidence path within Phase 1 unless a second Android handset arrives).

**Three Phase-1 outcomes for the pcap arm (rev2-amended):**

1. **Emulator reproduces 140 s death AND emulator pcap shows Pong frame 9 absent:** H-A confirmed (network / radio / NAT / Hetzner / TCP path). Action: deprioritise client-stack investigation; escalate `RC-REALITY-PROBE1` and open a server-side network track.
2. **Emulator reproduces 140 s death AND emulator pcap shows Pong frame 9 present:** H-B / H-C / H-D confirmed (client stack). Action: advance to Arm E (control-frame vs data-frame discrimination) and/or Phase 2 Arm F (instrumented raw OkHttp subclass).
3. **Emulator does NOT reproduce the death rhythm:** Tecno-specific or path-specific outcome (carrier NAT / Tecno HiOS power management / Tecno Android stack). This is a **valid Phase 1 outcome**, not a pcap failure. Action: PCAPdroid-on-Tecno becomes the primary pcap evidence path (with its VPN-based-route caveat noted); if a second Android handset is available it runs as Arm I and tests vendor parity; if neither is available, Phase 1 ships with this outcome explicitly recorded and Phase 2 plans the Tecno-specific investigation.

#### Arm E — app data-frame heartbeat (diagnostic only)

**Conditional:** runs only if Arm A + Arm B + Arm D have eliminated H-A. (If H-A is the winner, Arm E adds nothing.) The arm modifies the diagnostic build to send a tiny app-level text frame from client to relay every 15 s, and relay echoes the same frame back. Client counts inbound echo frames.

**Inv-NoAppLevelPingResurrection applies.** The arm fires ONLY when `BuildConfig.DEBUG_RC_DIRECT_ARM == "E"`. Production code path is untouched. PR-H1e history is preserved: this is diagnostic, never production.

**What it proves vs disproves:**

- If echoes arrive consistently while Pong frames are silently dropped: control-frame-specific path issue (kernel / radio / OkHttp internal control-frame handling) — narrows to H-B specifically.
- If echoes also stop near the death moment: any-reverse-traffic loss — broader network/radio problem, even though Caddy and Hetzner are not periodic timers.

**Cost:** ~30 LOC client + a small relay-side echo handler (~15 LOC under a debug-only WS opcode). Code review gate: echo path is gated by an unsafe-by-default header that production clients never send.

### Phase 2 — confirmatory arms (optional)

These arms run only after Phase 1 produces an ambiguous result or specifically calls for them via the decision tree (§5).

#### Arm F — instrumented raw OkHttp via custom RealWebSocket subclass

If Phase 1 narrows root cause to H-B / H-C / H-D, this arm shadows raw `RealWebSocket` with a debug subclass that logs every `onReadingPongFrame`, every `awaitingPong` mutation, every `writePingFrame` entry/exit. Requires reflective access to OkHttp internals or repackaged OkHttp module. **Significant scope** — only triggered if Phase 1 cannot discriminate H-B from H-C from H-D.

#### Arm G — Ktor `webSocketRaw` arm

Marginal value per F5 (OkHttp engine consumes control frames before Ktor sees them, even via `webSocketRaw`). Recorded as "cheap to add, low-information-content" — included only if Phase 1 results suggest Ktor wrapping is the proximal cause and we want to confirm that webSocketRaw also misses the same control frames.

#### Arm H — Caddy bypass via VPS reverse_proxy swap

**Vladislav-locked: optional Phase 2 only.** Direct OkHttp → relay (no Caddy in the path). Requires brief VPS reconfiguration (~15 min production impact). Runs only if (a) Phase 1 narrows to "something on the path", AND (b) emulator pcap shows ambiguity about whether the kill is on the Hetzner network layer vs Caddy's WS proxy frame handling. The Caddyfile evidence in F7 already lowers Caddy's prior probability, so Arm H is unlikely to be needed.

#### Arm I — second Android handset parity

**Vladislav-locked: optional, not gating.** If a second Android handset (different vendor, different Android version) is available, the same APK run on that handset adds parity evidence for or against Tecno-specific stack quirks. If second handset is not available at the time of Phase 1 execution, the track ships rev1 without it.

---

## §5 — Decision tree (outcome → root cause attribution)

Phase 1 (Arms A + B sequential, C, D) decision boundaries (rev2-amended):

| Arm A vs B (Tecno, sequential) | Arm C (emulator) | Arm D (pcap) | Outcome |
|---|---|---|---|
| B survives ≥ 2 × A median lifetime | Any | — (not needed for this outcome) | **Ktor-adapter fix scope.** Ktor wrapping is the proximal kill. Assess fix cost (raw OkHttp migration vs Ktor patch). |
| A & B both die at ~8 cycles | Emu reproduces 140 s death | Emu pcap: Pong frame 9 **absent** | **H-A confirmed (network / path / Hetzner).** Action: deprioritise client-stack; escalate `RC-REALITY-PROBE1`; open server-side network track. 3.2b.1 becomes necessary UX shield. |
| A & B both die at ~8 cycles | Emu reproduces 140 s death | Emu pcap: Pong frame 9 **present** | **H-B / H-C / H-D confirmed (client stack).** Action: advance to Arm E (data-frame heartbeat discrimination) or Phase 2 Arm F (instrumented raw OkHttp subclass). |
| A & B both die at ~8 cycles | **Emu does NOT reproduce** | Tecno pcap via PCAPdroid | **Tecno-specific OR path-specific outcome.** Action: PCAPdroid Tecno pcap becomes primary evidence path; if second Android handset available it becomes Arm I; if both unavailable, Phase 1 ships this outcome explicitly and Phase 2 plans Tecno-specific investigation (HiOS power management / radio behaviour). |
| Mixed / ambiguous results | Any | Pcap unclear | **Phase 2 triggered:** longer run (30 min per OQ-Q4), Arm E, or instrumented subclass Arm F. |

3.2b.1 code resumption decision:

- **Outcome = Ktor-adapter fix scope:** assess whether the fix is small (e.g. switching to raw OkHttp WS for the production WS path is ~150 LOC scaffold given F7 already exposes the `OkHttpClient`). If small: pivot to a `PR-WS-DIRECT-FIX1` track. If large (full transport rewrite): keep 3.2b.1 as UX shield while a longer client refactor proceeds.
- **Outcome = H-A network:** 3.2b.1 becomes a **necessary** UX shield. The client cannot fix Hetzner network behaviour; adaptive validation + soft demotion to working paths is the correct response.
- **Outcome = H-B/C/D client stack:** triage Phase 2 arms for tighter localisation, then re-decide on 3.2b.1 vs targeted fix.
- **Outcome = Tecno-specific stack:** narrow track to Tecno workaround; 3.2b.1 still useful for non-Tecno users on bad networks but may not be the primary fix surface.
- **Outcome = Ambiguous after Phase 2:** open a third audit cycle and re-Council with the new evidence.

---

## §6 — Acceptance gates (what counts as "we know enough to act")

Phase 1 ships an evidence-summary PR (mini-lock continuation, similar to v8 PASS report) when ALL of:

1. **A + B sequential capture** complete on Tecno (Arm A first, Arm B in a separate session window after Arm A disconnects and relay-side state clears): Arm A reproduces the baseline 140 s death rhythm (≥ 5 cycles in 15 min, matching the v8/v9 noise floor). Arm B either reproduces the same rhythm OR survives materially longer (defined as ≥ 2 × Arm A median session lifetime in the same APK build).
2. **Arm C emulator parity** complete with the same APK build and a same-day measurement.
3. **Arm D packet capture** — gate is conditional on Arm C reproduction (rev3-amended):
   - If Arm C emulator reproduces the 140 s death rhythm, Arm D emulator pcap is captured + analysed with explicit determination of whether Pong frame 9 is present or absent in the TCP recv stream of the dying session.
   - If Arm C emulator does NOT reproduce the death rhythm, the non-reproduction is itself the recorded Phase 1 outcome and gate #3 escalates to Tecno-side PCAPdroid (with its VPN-based-route caveat noted) OR a second-Android probe; if both unavailable, gate #3 is satisfied by recording the non-reproduction outcome explicitly and Phase 2 plans the Tecno-specific or path-specific follow-up.
4. **Tecno pcap via PCAPdroid** attempted (best-effort, may be skipped if PCAPdroid materially alters timing — must be explicitly noted, not silently omitted).
5. **Decision-tree outcome** explicitly named in the evidence summary (one of the rows in §5 table) with a one-paragraph "what this rules in and rules out" justification.

Phase 2 acceptance gates depend on which arm was triggered; defined in the Phase 2 design-note continuation (rev2 of this file).

---

## §7 — Implementation order (locked for the code PR)

The code PR for RC-DIRECT-WS-DEATH1 Phase 1 ships:

1. NEW `apps/android/src/androidMain/.../diagnostic/RcDirectArmB.kt` — raw OkHttp **sequential** diagnostic arm (rev3-amended). Constructs its **own** `OkHttpClient` with identical builder parameters to production (`.pingInterval(15_000L, TimeUnit.MILLISECONDS)`, `.readTimeout(60, TimeUnit.SECONDS)`, `.connectTimeout(5, TimeUnit.SECONDS)`, `.protocols(listOf(Protocol.HTTP_1_1))`) — does NOT extract or reuse any client from `RelayTransportFactory.kt` (the production factory returns a Ktor `HttpClient` wrapper; the raw `OkHttpClient` is lambda-closure-internal and not exposed). Performs the production `/auth/challenge` + sign-with-Ed25519 flow with the unchanged 64-hex identity (the only identity the relay's `is_pubkey_hex` validator accepts). Opens a WebSocket via `okHttp.newWebSocket(request, listener)`. The `WebSocketListener` logs with `RC_DIRECT_ARM_B_*` prefix exclusively. The arm starts ONLY after the production Ktor WS connection has been disconnected for the duration of the Arm B run window.
2. NEW `BuildConfig.DEBUG_RC_DIRECT_ARM` field added via `apps/android/build.gradle.kts` `buildConfigField("String", "DEBUG_RC_DIRECT_ARM", "\"0\"")` in the `defaultConfig` block, with a debug override pattern (e.g. Gradle property `rcDirectArm`) so a field run can pick the arm without a code edit. Possible values (rev4-amended): `"0"` (disabled, production default — also covers Arm A field runs since Arm A is the existing production code path with no new tag), `"B"` (Arm B sequential — production Ktor disabled for the duration of the run), `"E"` (data-frame heartbeat — Phase 2 only). **Rev2: `"AB"` removed** — parallel A+B forbidden per Inv-ParallelArmIsolation. **Rev4: `"A"` removed** — Arm A has no bookkeeping under rev3+ since it adds no new code.
3. EDIT `AppContainer.kt` — conditional `RcDirectArmB` construction + start under `if (BuildConfig.DEBUG && BuildConfig.DEBUG_RC_DIRECT_ARM == "B")`. Release builds (`!BuildConfig.DEBUG`) ignore the flag entirely as defence-in-depth. ~10 LOC.
4. **Zero** edits to `KtorRelayTransport.kt`, `RelayTransportFactory.kt`, `RestFallbackOrchestrator.kt`, `TransportManager.kt`, `TransportCapabilitiesResolver.kt`, `ConnectionUiState.kt`, or anything in the WS-HEALTH-STATE1 design surface.
5. Tele2 LTE Tecno + emulator field run capturing existing production telemetry tags during the Arm A run window (`PhantomRelay`, `TransportRewalkCoordinator`, `ws_ping_timeout_diag`, `session_summary`) plus `RC_DIRECT_ARM_B_*` during the separate Arm B run window, plus `tcpdump` on emulator (conditional on Arm C reproduction per §6 gate #3). Tecno PCAPdroid attempted as secondary corroboration when Arm C reproduces; promoted to primary if Arm C does NOT reproduce.
6. Architect / Vladislav review of evidence summary before any §5 decision-tree branch is acted upon.

Phase 2 arms (E, F, G, H, I) live in a separate code PR after Phase 1 evidence summary is locked.

---

## §8 — Out of scope (hard)

- ❌ Any change to `pingInterval(15s)` / `readTimeout(60s)` / `callTimeout(10s)` as a "fix". Only as observation if a Phase 2 arm explicitly measures cadence sensitivity.
- ❌ Any change to `RestFallbackOrchestrator`, `RestStateMachine`, `RestHealthMonitor` (not yet implemented), or REST behaviour at all.
- ❌ Any change to `TransportManager.connect()`, `TransportManager.reorderChain()`, `TransportStrategy.chain`, `lastWorkingTransport`.
- ❌ Any change to `TransportCapabilitiesResolver` (PR-C1) — calls / voice / media capability surface is untouched.
- ❌ Any change to `ConnectionUiState` or notification text (separate `PR-UI-CONNECTION-LABELS1` track).
- ❌ Any change to 3.2a `WsDegradationDetector` or `WsDegradationCollectorBindings` telemetry — this track measures the lower layer, not the detector that observes it.
- ❌ App-level Ping reintroduction (PR-H1e removed for proven reasons).
- ❌ Raw OkHttp as the production WS path. Arm B is **diagnostic**; if results indicate raw OkHttp is the right production path, a separate design note locks the migration.
- ❌ `RC-REALITY-PROBE1` work (separate track — why Xray `/health` probe times out at 20 s).
- ❌ `PR-UI-CONNECTION-LABELS1` work (separate track — release-mode hide of "Tor" / "Reality" / "Direct" / "REST fallback" labels).
- ❌ 3.2b.1 code start. Strictly paused per Inv-NoChangeUntilEvidence.
- ❌ CHIP1 (parked at `78bd979e`).

---

## §9 — Parking lot (deferred until Phase 1 evidence)

- **Custom RealWebSocket subclass with `awaitingPong` introspection** — Arm F in Phase 2; cost is significant (requires repackaging OkHttp or aggressive reflection), only triggered if H-B / H-C / H-D needs internal discrimination.
- **HTTP/2 vs HTTP/1.1 WS upgrade comparison** — currently forced HTTP/1.1 (`RelayTransportFactory.kt:108`). Per the existing comment, HTTP/2 had separate issues. Not relevant to ping/pong path under HTTP/1.1, so not exercised in Phase 1.
- **MTU sensitivity** — control frames are ≤ 125 bytes per RFC 6455 §5.5, and the OkHttp ping/pong path uses opcode 0x9/0xA which are inherently small. MTU is unlikely to be a per-frame killer. Recorded as low-prior hypothesis.
- **Connection-pool stale-reuse on the WS path** — the WS factory recreates the engine per reconnect generation (ADR-010 Updated 2026-05-01), so pool reuse is structurally prevented. Recorded as inapplicable.
- **Caddy `flush_interval -1` testing** — Caddy `read_timeout 0 / write_timeout 0` already configured. `flush_interval` controls low-latency buffering; not a periodic killer. Phase 2 Arm H subsumes any Caddy-side testing.
- **Battery / radio power management** — Tecno HiOS aggressive power management is documented elsewhere; ADR-010 / ADR-011 already address some of it. If Phase 1 concludes "Tecno-specific stack" outcome, this becomes the follow-up investigation surface.

---

## §10 — Process gates

- **WORKING_RULES rule 8** (transport regression gate): **carve-out applies.** This track is diagnostic-only and ships no production behaviour. The diagnostic build flag default is `"0"` (disabled) in release. Field test on Tele2 LTE Tecno is required for evidence, but the gate is "evidence captured" not "no production regression" because there is no production-affecting change.
- **WORKING_RULES rule 9** (no merge without verification): applies fully. Architect explicit ACK on the diagnostic-only diff OR grep-verified evidence per claim. All hypothesis-space attributions (§2) must remain grep-verifiable against captured logs.
- **`feedback_ws_heartbeat_diagnostic_2026_05_27.md`** — informs Inv-NoHeartbeatCadenceFix. We do not disable / shorten / lengthen heartbeat as a "fix"; only as observation in a Phase 2 arm if data demands it.
- **`feedback_apk_build_is_mine.md`** — APK builds and adb command generation are assistant-owned; field test execution is Vladislav-owned. PCAPdroid setup on Tecno is Vladislav-owned (it is a user app install + permission grant, not an automation task).
- **`feedback_logcat_format.md`** — all logcat capture commands generated for this track use the canonical PowerShell `Tee-Object` format with the `RC_DIRECT_ARM_B_V` log tag added to the existing set (`PhantomMessaging:V PhantomTransport:V PhantomHybrid:V PhantomRelay:V TransportManager:V RestStateMachine:V TransportRewalkCoordinator:V`). Arm A uses no new tag — the existing set already captures everything it produces.
- **`feedback_session_close_discipline.md`** — mini-lock (this file) is rev3 before any code branch (rev1 → rev2 audit-driven amendments → rev3 stale-fragment cleanup). Code branch `feat/pr-rc-direct-ws-death1-arm-b` is cut from master after rev3 merge and explicit Vladislav ACK on the implementation order in §7. The `-arm-b` suffix is correct: Phase 1 ships the Arm B sequential code; Arm A is the existing production path with no new code; Arms C / D are operational (logcat + tcpdump on the same build), not code branches.
- **`feedback_durable_log.md`** — Phase 1 evidence summary will be appended as a new Session journal entry in `docs/PROJECT_LOG.md` and as a `Last updated` bump in `docs/project/MASTER_TIMELINE_2026.md` after the evidence summary PR merges.

---

## §11 — Open questions (locked by Vladislav 2026-06-02)

| OQ | Question | Vladislav-locked answer |
|---|---|---|
| **OQ-Q1** | Tecno rooted? Packet capture path. | Tecno pcap best-effort via PCAPdroid (acknowledging VPN-based capture alters network path). Emulator pcap is the **required** primary evidence source for Arm D. Tecno pcap is secondary corroboration. |
| **OQ-Q2** | Second Android handset for parity? | Optional, not blocker. emu vs Tecno parity is the required minimum; second handset is a bonus when available. rev1 ships without it. |
| **OQ-Q3** | Caddy bypass arm (production VPS impact)? | Phase 2 optional only. Not in first wave. Triggered only if Phase 1 narrows to "something on the path" AND emulator pcap is ambiguous about Hetzner vs Caddy. |
| **OQ-Q4** | Run length per arm? | 15 minutes default. 30 minutes only for confirmatory rerun if Phase 1 result is ambiguous. |
| **OQ-Q5** | Parallel vs sequential arms? | **Rev2 (post-grep against `services/relay/src/routes.rs:230-232`):** Arm A baseline + Arm B raw OkHttp run **sequentially** in the first wave (same APK build, separate logcat run windows). Parallel A+B is forbidden per Inv-ParallelArmIsolation until a relay-side diagnostic-identity namespace ships. Arm C emu parity is sequential same-day with the same APK. Arms D / E / F / G / H / I are sequential per Phase ordering. `webSocketRaw` (Arm G) is NOT in first wave per F5 + Vladislav direction. |

---

## §12 — Source-of-truth pointers

- `docs/tracks/ws-health-state.md` § Commit 3.2b — the adaptive-validation track this diagnostic gates.
- `MASTER_TIMELINE_2026.md` "Last updated 2026-06-02" — track sequencing.
- `docs/PROJECT_LOG.md` Session journal 2026-06-02 entry — `RC-DIRECT-WS-DEATH1` recorded as next track.
- Memory `project_next_session_ws_health_state_3_2b_2026_06_01.md` § "Update 2026-06-02 evening" — 6-arm matrix history and the sequencing pivot.
- OkHttp issue #3227 (open by mainainer Swankjesse 2017-03-18) — "pong timeout not independent of ping interval".
- OkHttp issue #3538 (closed) — "Pings are not available in OkHttp's web sockets APIs".
- Ktor YouTrack KTOR-4752 — "The pingInterval and pingIntervalMillis properties are not applicable for the OkHttp engine".
- Codex external architect memo (received 2026-06-02 via Vladislav).
- "Глобальный аудит транспортов мессенджеров и архитектурный разбор" PDF (received 2026-06-02 via Vladislav).
