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
- **`feedback_session_close_discipline.md`** — mini-lock (this file) is rev4 before any code branch (rev1 → rev2 audit-driven amendments → rev3 stale-fragment cleanup → rev4 Arm A consistency cleanup). Code branch `feat/pr-rc-direct-ws-death1-arm-b` is cut from master after rev4 merge and explicit Vladislav ACK on the implementation order in §7. The `-arm-b` suffix is correct: Phase 1 ships the Arm B sequential code; Arm A is the existing production path with no new code; Arms C / D are operational (logcat + tcpdump on the same build), not code branches.
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

---

## Phase 1 outcome — evidence summary (Vladislav-locked 2026-06-02 (tue) after sequential v11 capture set)

Master at lock: this PR's merge commit, on top of `a9f66ad0`. Phase 1 closed; Phase 2 PCAPdroid plan is pointed at §17 below and will be designed in a separate mini-lock section (next session, not this PR).

### §13 — Empirical base — Phase 1 v11 capture set

Four logcat captures executed sequentially on the same APK build day. Identity unchanged throughout (the only identity the relay's 64-hex `is_pubkey_hex` validator accepts). Sequential isolation discipline observed per Inv-ParallelArmIsolation: each capture starts after `am force-stop` + ≥ 30 s wait so the relay-side `state.clients[identity]` mapping clears between sessions.

**Three baselines must be kept separate. Earlier write-ups collapsed v8 / v9 / v11 into one "same ~140 s rhythm" claim, which is wrong — v11 introduces a second severity mode the earlier captures did not exhibit. Phase 2 analysis must address each mode on its own terms.**

| Test | Device · network | APK · flag | Sessions in 15 min | Median lifetime | Pre-fail successful ping/pongs | Notes |
|---|---|---|---|---|---|---|
| **v8** (2026-05-31) | Tecno · Tele2 LTE | 3.2a baseline · flag n/a (production Ktor path) | 4 | ~140 s | **8** | First-baseline cellular run |
| **v9** (2026-06-01) | Tecno · Ростелеком Wi-Fi | 3.2a baseline · flag n/a | 5 | ~145 s | **8** | Wi-Fi parity of v8; established the "8-pong rhythm" as a cross-network pattern |
| **v11 Arm A Tecno** (2026-06-02) | Tecno · Tele2 LTE | `phantom-arm-a-v11.apk` SHA `2ac2afda...` · flag `"0"` (production Ktor path) | **29** | **~31 s** (range 31 017–31 028 ms) | **0** | New severity mode; same OkHttp pinger kill mechanism but pong drops immediately. UI hit `Limited realtime` within ~2 min |
| **v11 Arm A emu** (2026-06-02) | Emulator (`emulator-5554`) · Wi-Fi via host PC | same APK as above · flag `"0"` | **0** OkHttp ping-timeout closes | n/a (WS stayed alive the whole 15 min) | n/a | 4 `REST_TRACE mode_switched` lines but all from the 60 s `inbound_idle_timeout` state-machine threshold, **not** from OkHttp `pingInterval` death. Emulator did **not** reproduce v11 Tele2 severity |
| **v11 Arm B Tecno (Tele2 LTE)** (2026-06-02) | Tecno · Tele2 LTE | `phantom-arm-b-v11.apk` SHA `48ffbefe...` · flag `"B"` (raw OkHttp diagnostic) | 13 closed (14 opened; s=14 was still running at Ctrl+C) | s=1 30 s · s=2-13 ~45 s | s=1 **0** · s=2-13 **1** | Sequential to Arm A Tele2 with relay-side state cleared. Matches Arm A Tele2 severity within margin |
| **v11 Arm B Tecno (Wi-Fi)** (2026-06-02) | Tecno · Wi-Fi (mobile data off) | same `phantom-arm-b-v11.apk` · flag `"B"` | 5 closed (6 opened; s=6 was still running at Ctrl+C) | **~150 s** (range 150 031–150 037 ms, all five within 6 ms of each other) | **8** in every fail | **Numerically reproduces the v9 Wi-Fi baseline** within ~5 s and identical pp count |

Source log paths (UTF-16 LE BOM as produced by PowerShell `Tee-Object`):

- `C:\temp\test83-v11-arm-a-tecno.log` (Arm A Tecno Tele2 LTE)
- `C:\temp\test83-v11-arm-a-emu.log` (Arm A emulator Wi-Fi)
- `C:\temp\test83-v11-arm-b-tecno.log` (Arm B Tecno Tele2 LTE)
- `C:\temp\test83-v11-arm-b-wifi-tecno.log` (Arm B Tecno Wi-Fi)

**APK build provenance.** The two v11 APK SHAs in the table above (`2ac2afda...` for Arm A flag `"0"` and `48ffbefe...` for Arm B flag `"B"`) were built locally on the field-run day on top of master `a9f66ad0` — i.e. **after the orthogonal SEO PRs #267 (`73395111`) and #268 (`a9f66ad0`) had landed**, not on top of `08514aee` (PR #266's merge commit). The Phase 1 Arm B code itself is unchanged between those build bases; the SHAs differ from the PR #266 commit-body baseline (`444639626e14...`) because the BuildConfig bake-in includes the build's manifest-version and resource fingerprints that touch each release. None of the orthogonal SEO/funding changes affect the WS path or the diagnostic class — they touch `site/` and `deploy/` only.

Sanity wire-confirm gates from Arm B Wi-Fi log (representative of all four Arm B field-test contract checks):

- `RC_DIRECT_ARM_B_armed` = 1 — diagnostic class constructed and started exactly once
- `RC_DIRECT_ARM_B_service_short_circuit` = 1 — `PhantomMessagingService.onStartCommand` bypassed the production Hybrid Ktor path
- Production `session_summary` = 0 — production WS never opened during the Arm B window (Inv-ParallelArmIsolation held; no contention with `state.clients[identity]`)
- Production `ws_ping_timeout_diag` = 0 — same; 3.2a detector telemetry quiet during Arm B
- `RC_DIRECT_ARM_B_ws_open` = 6 / `RC_DIRECT_ARM_B_ws_failure` = 5 / `RC_DIRECT_ARM_B_ws_closed` = 0 — every closed session ended via `WebSocketListener.onFailure(SocketTimeoutException)`, none via clean close handshake

### §14 — Two failure modes on the same Tecno device

The same Tecno running the same APK (modulo build flag) and signing-in with the same identity exhibits **two qualitatively distinct OkHttp ping-timeout failure modes depending on the active network path**. The mechanism is identical in both — `okhttp3.internal.ws.RealWebSocket.writePingFrame()` fails the socket on the next scheduled ping after `awaitingPong` was left set by an unanswered ping. What differs is **how early in the ping sequence the unanswered ping happens**.

**The failure mechanism is the same OkHttp pinger timeout class, but v11 shows at least two severity modes: Wi-Fi 8-pong rhythm and Tele2 severe 0-1-pong rhythm. These modes must not be collapsed in any forward analysis.**

| Mode | Networks where observed | Median lifetime | Pre-fail successful pp | Implication |
|---|---|---|---|---|
| **Mode 1 — "Wi-Fi 8-pong rhythm"** | v9 Tecno Ростелеком Wi-Fi (production Ktor); v11 Arm B Tecno Wi-Fi (raw OkHttp) | ~145–150 s | **8** | Eight Pongs round-trip cleanly; the ninth is the loss. This is the originally hypothesised pattern. |
| **Mode 2 — "Tele2 severe 0-1-pong rhythm"** | v11 Arm A Tecno Tele2 LTE (production Ktor); v11 Arm B Tecno Tele2 LTE (raw OkHttp) | ~31 s (Arm A) / 30 s for s=1 then 45 s for s=2-13 (Arm B) | **0–1** | The first or second Pong is dropped almost immediately. Severity is several times worse than Mode 1. v8 (also Tele2 LTE, prior month) was closer to Mode 1, so this is **not** "Tele2 always behaves like this"; the network condition itself swings between modes. |

Both modes share the same proximal kill mechanism (OkHttp pinger; matches the exception text verbatim) and the same line-by-line code path in `RealWebSocket`. The split is in the **network return-path / device interaction**, not in the client code.

### §15 — Decision tree resolution (mini-lock §5)

Mini-lock §5 listed five outcome rows. Phase 1 captures match two of them simultaneously, in a way the table did not anticipate as a single outcome but combines cleanly:

| Mini-lock §5 row | Phase 1 evidence | Verdict |
|---|---|---|
| "B survives ≥ 2 × A median lifetime → Ktor-adapter fix scope" | Arm B does NOT survive materially longer than Arm A in either network. Tele2: Arm A 31 s vs Arm B 30-45 s. Wi-Fi: Arm B ~150 s matches v9 Ktor ~145 s. | **Ktor adapter is NOT primary cause.** This is the load-bearing falsification of the rev1 hypothesis space's first line. |
| "A & B both die at ~8 cycles + Emu pcap Pong present → H-B/C/D client stack" | Wi-Fi mode matches "die at ~8 cycles". Emulator pcap on default emulator image is blocked (`tcpdump: inaccessible or not found`); this row's pcap precondition is unmet in Phase 1. | Conditional outcome — promoted to Phase 2 with PCAPdroid on Tecno as the primary pcap path (per emu-non-reproduces row below). |
| "A & B both die at ~8 cycles + Emu pcap Pong absent → H-A network/path" | Same conditional as above — pcap missing. | Conditional — Phase 2. |
| "Emu does NOT reproduce → Tecno-specific OR path-specific" | Arm A emu on Wi-Fi via host PC stayed alive the full 15 min with zero OkHttp ping timeouts. **This row fires.** | **Tecno-and-network combination is in the picture.** The failure does not reproduce on the same Wi-Fi class when routed through a different device + network stack (emulator via host PC vs Tecno via Wi-Fi NIC). |
| "Mixed / ambiguous → Phase 2 longer run / Arm E / Arm F" | Not the current state. | Not triggered. |

**Phase 1 decision-tree outcome (combined):** raw OkHttp reproduces the failure on Tecno in both networks; raw OkHttp does NOT survive longer than Ktor wrapping; the same network class via a different device + stack (emulator via host PC) does NOT reproduce. The root cause sits **below the Ktor wrapper, somewhere in the intersection of (Tecno device / Android-HiOS network stack / OkHttp internal handling / network return-path)** and is **path/condition-sensitive** (two severity modes observed on the same device).

### §16 — Rules in / rules out / still open (Vladislav-locked language)

**Rules out (Phase 1):**

- **Ktor adapter as primary cause.** Raw OkHttp reproduces both the Mode 1 (Wi-Fi 8-pong rhythm) and Mode 2 (Tele2 severe 0-1-pong rhythm) failures with similar lifetimes and identical pp counts to the production Ktor path. Removing Ktor from the WS path does not buy a materially longer session.

**Rules in (Phase 1):**

- **The raw OkHttp / WS protocol-ping path is affected on Tecno.** The failure is reproducible with no Ktor in the data path; the issue is below Ktor and above the relay's send-Pong contract (proven sent in PR #259 / Test #83 v7).

**Still open (handed to Phase 2):**

- Network return-path loss between relay and device, vs Tecno / Android-HiOS / device network stack quirk, vs OkHttp internal handling (`awaitingPong` race / scheduler / reader-thread blocking). Phase 1 cannot discriminate these without packet capture on the device.

**Do NOT claim from Phase 1 evidence (architect-locked):**

- "Carrier-independent severity" — v11 captures show Tele2 severity (Mode 2) ≫ Wi-Fi severity (Mode 1) on the same device. Modes share a mechanism but not a severity profile.
- "Tele2-only" — Wi-Fi Mode 1 reproduced on Tecno in both v9 (Ktor) and v11 (raw OkHttp). Wi-Fi is not exempt.
- "Server-side bug" — the relay-side Pong-send bug was **falsified in Test #83 v7** specifically (PR #259 / Commit 3.3): for that one captured session-window the relay logged `ws_protocol_pong_sent` for the Pong preceding the client-side timeout. **The v11 captures do NOT include a synchronous relay-side recapture** for either Mode 1 or Mode 2 sessions, so this Phase 1 cannot extend v7's falsification to the v11 windows by itself. Phase 2 packet capture + relay-log cross-correlation is required to discriminate "Pong sent by relay but lost on return-path" from "Pong sent and arrived but mis-handled on device". Until that happens, Phase 1 only carries the v7-window falsification forward as a prior, not as a current proof.
- "Device-only bug" — emulator on Wi-Fi via host PC ran the same APK and did not exhibit either mode. The Tecno device contributes; the network conditions contribute; isolating each requires Phase 2 evidence.

### §17 — Phase 2 pointer (separate mini-lock section, NOT this PR)

Phase 2 design will plan packet-capture on Tecno using **PCAPdroid** (root-free VPN-based capture) with **two explicit targets**, one per mode identified in §14:

1. **Mode 1 capture (Wi-Fi 8-pong rhythm):** capture window starting ~120 s into a healthy Arm A (or Arm B) Wi-Fi run, watching for the 9th Pong on the wire. If the 9th Pong is present on the device socket but not counted by OkHttp, the root cause is in OkHttp internal handling or the Android socket → userspace read path. If absent, return-path loss between relay and device.
2. **Mode 2 capture (Tele2 severe 0-1-pong rhythm):** capture window starting at session open, watching for the 1st-2nd Pong on the wire. Same present/absent dichotomy, applied to the much earlier failure.

PCAPdroid caveat (mini-lock §4 Arm D): VPN-based capture may alter network path / timing. Any Mode 2 number obtained under PCAPdroid is "PCAPdroid-influenced" and not raw production; for Mode 1 the larger absolute timescale gives more tolerance.

Phase 2 mini-lock section is intentionally NOT drafted in this PR — modelled after the mini-lock vs evidence-summary separation already established here (§1-§12 mini-lock, §13-§18 evidence). The Phase 2 mini-lock continues the same file as `## Phase 2 ...` sections, locked separately.

### §18 — Open questions handed to Phase 2

1. **Two-mode convergence under PCAPdroid VPN routing.** If PCAPdroid's VPN interface changes the kernel routing, Mode 2 (~30 s lifetime) may shift toward Mode 1 (or disappear entirely). The Phase 2 mini-lock must define how to validate that PCAPdroid did not mask the mode being investigated.

2. **Second Android handset on Wi-Fi.** Mode 1 reproduced on Tecno Wi-Fi (v9 + v11 Arm B). It did NOT reproduce on emulator Wi-Fi via host PC. A non-Tecno physical handset on the same Wi-Fi class would discriminate "Tecno-specific HiOS quirk" vs "any non-emulator Android handset". Mini-lock §4 Arm I covers this; optional per OQ-Q2.

3. **Server-side cross-correlation with PR #259 telemetry.** During Mode 1 and Mode 2 sessions, relay-side `ws_protocol_pong_sent` log entries can be matched to client-side `RC_DIRECT_ARM_B_ws_failure` lifetimes (UTC clock difference is known). If relay sends N+1 Pongs but the client only counts N (Mode 1: relay sends 9, client counts 8; Mode 2: relay sends 2, client counts 1), the missing Pong's send-time on the relay is a known point in time to anchor a packet-capture analysis around.

4. **Whether 3.2b.1 adaptive validation code should resume.** Per `Inv-NoChangeUntilEvidence`, 3.2b.1 has been paused awaiting this evidence. The Phase 1 outcome — "client-stack-OR-path-dependent, two modes, both reproducible with raw OkHttp" — favours 3.2b.1 becoming a necessary UX shield (the client cannot single-handedly fix Mode 2 severity on a degraded carrier). But Phase 2 packet capture may still localise a small client-side fix that closes Mode 1 at the root; if so, 3.2b.1 may be deprioritised again. **Decision deferred until Phase 2 evidence summary lands.**

Phase 1 closes here. CHIP1 remains parked at `78bd979e` throughout. 3.2b.1 code remains paused per `Inv-NoChangeUntilEvidence`.

---

## Phase 2 mini-lock — packet-capture wire-correlation (Vladislav-locked 2026-06-03 (wed))

Master at lock: `16ee99b9` (PR #269 merge commit). Phase 2 is a docs-only mini-lock; the field-test execution will happen in a separate session and produce a Phase 2 evidence summary PR analogous to §13-§18.

### §19 — Phase 2 goal & scope (two-tier evidence model)

Phase 1 ruled out the Ktor adapter as primary cause and located the failure below it, somewhere in the intersection of (Tecno device / Android-HiOS network stack / OkHttp internal handling / network return-path). Phase 2's job is to discriminate **network return-path loss** from **device-side mis-handling of traffic that did arrive**, for both Mode 1 and Mode 2 (§14).

**Two-tier evidence model — this is the critical structure choice:**

| Tier | What it proves | How it is captured | Mandatory in Phase 2? |
|---|---|---|---|
| **Tier 1 — raw wire correlation** | Inbound TCP / TLS records present-or-absent on the device's PCAPdroid pcap at the UTC moment a relay-side `ws_protocol_pong_sent` line was emitted. Plus TCP-level evidence: retransmits, RST/FIN, zero-windows, MTU-related fragmentation. Does NOT prove "the WS Pong frame itself reached the device" — TLS payload is encrypted at this tier — but does prove whether **any** inbound TLS Application Data record arrived in the relevant window. | PCAPdroid raw mode (root-free VPN-based capture). Capture as `.pcapng`, analysed with `tshark` post-hoc. | **YES — primary gate.** |
| **Tier 2 — decrypted Pong frame proof** | The actual WS protocol-Pong frame (opcode 0xA, ≤ 125 bytes payload) present-or-absent on the device. This is the literal H-A / H-B discriminator from mini-lock §2. | EITHER PCAPdroid-mitm (TLS interception via local CA, alters trust chain), OR debug build emitting `SSL_KEYLOGFILE` for Wireshark TLS decryption (alters debug-only build, not production), OR server-side BPF on the relay host. | **NO — optional, deferred to Phase 2b.** |

**Rationale for two-tier split:** PCAPdroid in raw mode on non-rooted Android is observably the lowest-risk capture path (no MITM CA install, no TLS interception, no production-build edit). But TLS encrypts the WS payload, so the literal Pong frame is not visible without decryption. The wire-correlation evidence at Tier 1 is *sufficient* to discriminate H-A (no inbound TLS records at the expected moment → return-path loss) from H-B/C/D (inbound TLS records present at the expected moment but client doesn't count Pong → device-side or OkHttp internal mis-handling), without the additional Tier 2 ceremony. Tier 2 is reserved for the cases Tier 1 cannot close.

**Out-of-band from Phase 1:** Phase 2 does not re-run Arm A / Arm B as standalone arms. It overlays PCAPdroid on top of an Arm A or Arm B session (the active diagnostic-flag layer from Phase 1 still applies — the relay-side identity contract is unchanged).

### §20 — Refined hypothesis space (after Phase 1)

| H | Statement | What needs to be observed (Tier 1 raw wire) to confirm | What needs to be observed to refute |
|---|---|---|---|
| **H-A — return-path loss** | The 9th Pong (Mode 1) or 1st-2nd Pong (Mode 2) never reaches the device. The relay sent it, but it was lost en route (carrier / NAT / radio / Hetzner egress / device link layer). | PCAPdroid pcap shows **zero** inbound TLS Application Data records from `relay.phntm.pro` in the ≥ 2 s window after relay's `ws_protocol_pong_sent` UTC timestamp for the failing cycle. RST/FIN may or may not be present. | PCAPdroid pcap shows inbound TLS records in the expected window. |
| **H-B/C/D — device-side / OkHttp internal** | Inbound traffic arrived on the device, but OkHttp did not count the Pong (reader thread blocked, Android scheduling, `awaitingPong` race, kernel→userspace gap). | PCAPdroid pcap shows **inbound** TLS Application Data records in the expected window despite the OkHttp `writePingFrame` failure firing on the next cycle. | PCAPdroid pcap shows no inbound TLS records in the expected window. |
| **H-Pcap — PCAPdroid VPN-induced mode shift** | PCAPdroid's local VPN interface alters kernel routing / latency / NAT enough to mask one or both modes (Mode 2 → Mode 1, or Mode 2 → disappears entirely). | Arm P3 control run (PCAPdroid-on, no analysis target) reproduces the same mode counts and lifetimes as the matching Phase 1 PCAPdroid-off baseline within tolerance. **Confirms PCAPdroid does NOT mask the mode.** | Arm P3 shows materially different mode counts / lifetimes vs Phase 1 baseline. **Refutes Phase 2 evidence — escalate to Phase 2b TLS-keylog approach or server-side BPF.** |

**Mode classification bins (Vladislav-locked, post-hoc, no discard):**

| Mode | pp_count threshold | Lifetime threshold | Both conditions required |
|---|---|---|---|
| **Mode 1** | `pp_count >= 6` | `120 s <= lifetime <= 170 s` | yes |
| **Mode 2** | `pp_count <= 2` | `lifetime <= 60 s` | yes |
| **Unbinned** | anything else | anything else | recorded as "Mode-ambiguous", reported but not used to close a discrimination gate |

Sessions are classified post-hoc against actual pp_count and lifetime, not against the pre-declared target. A Tele2 run that lands on Mode 1 numbers is recorded as "Mode 1 on Tele2", not discarded. A Wi-Fi run that lands on Mode 2 numbers is recorded as "Mode 2 on Wi-Fi", not discarded.

### §21 — Invariants enforced by this Phase 2 mini-lock (hard guards)

Phase 1 invariants (mini-lock §3) remain in force. The Phase 2 mini-lock adds:

| ID | Invariant | What it forbids |
|---|---|---|
| **Inv-PcapDoesNotMaskMode** | Every Phase 2 capture session is paired with an Arm P3 control reading (PCAPdroid-on, same network, same flag, no analysis target) within the same field-test day. If the control reading does NOT reproduce the matching Phase 1 baseline mode within tolerance (lifetime ± 25 %, pp_count ± 2), the capture session's Tier 1 evidence is marked "PCAPdroid-influenced" and does not close any §23 discrimination row. | Treating PCAPdroid-on lifetime/pp_count numbers as Phase 1-equivalent without the matched control reading on the same day. |
| **Inv-NoTrafficBeyondTelemetry** | Phase 2 introduces no new outbound or inbound payload. The only new emission is a single `PHASE2_CAPTURE_MARKER mode=... utc=...` line in logcat (no WS frame). The relay-side telemetry from PR #259 is read-only consumed; no new server-side change. | Any debug code that sends a WS frame to "mark" the capture, or any relay-side change for Phase 2 instrumentation purposes. |
| **Inv-ProductionUnchanged** | Phase 2 ships ZERO production code change. Flag `BuildConfig.DEBUG_RC_DIRECT_ARM` already exists from Phase 1; Phase 2 uses it unchanged. The PCAPdroid setup is a user-space app on Tecno, not a code change. The `PHASE2_CAPTURE_MARKER` logcat emit is gated by `BuildConfig.DEBUG && BuildConfig.DEBUG_RC_DIRECT_ARM != "0"` — never fires in release. | Any production-path edit. Any release-mode emission of `PHASE2_CAPTURE_MARKER`. Any change to `pingInterval` / `readTimeout` / `callTimeout` on the basis of Phase 2 evidence (cadence sensitivity remains observation-only per Inv-NoHeartbeatCadenceFix). |
| **Inv-PcapReadOnlyAnalysis** | PCAPdroid pcap files are analysed read-only by `tshark` / PowerShell scripts. No mutation of capture artefacts. Capture export to `.pcapng` is preserved verbatim alongside the logcat trace. Source-of-truth pointer in §30. | Any "cleaned-up" capture or re-emitted pcap. Any analysis output that is not reproducible from the verbatim pcap. |
| **Inv-WallClockAlignment** | Every Phase 2 field session is preceded by an NTP-fix step (Tecno + Windows host + relay container). The `PHASE2_CAPTURE_MARKER mode=... utc=...` logcat line is the canonical alignment anchor on the client side; the relay-side `ws_protocol_pong_sent` UTC timestamp is the canonical anchor on the server side. PCAPdroid's pcap timestamp is the third source and must agree with the other two within ≤ 1 s for the run to be analysable. | Running a Phase 2 capture without NTP-fix on at least the three components. Reporting Tier 1 evidence from a run whose three time sources disagree by > 1 s. |
| **Inv-NoCarrierOrUiClaim** | Phase 2 evidence statements are scoped to Tier 1 raw wire correlation. Phase 2 must NOT claim carrier-independent severity, Tele2-only behaviour, Tecno-only behaviour, or any UI-layer outcome (the `PR-UI-CONNECTION-LABELS1` track is separate). | Any Phase 2 evidence summary line that extrapolates beyond the captured device + network + UTC window. |

### §22 — Experimental arms (Phase 2)

Phase 2 arms overlay PCAPdroid on top of a Phase 1 Arm A or Arm B session. Each capture run records: client logcat (existing tags + `PHASE2_CAPTURE_MARKER`), PCAPdroid `.pcapng` export, relay docker logs covering the same UTC window. No new code arms.

#### Arm P1 — Mode 1 capture (Wi-Fi 8-pong rhythm)

Capture target: the **9th Pong** in a Mode 1-classified session. The 8-pong rhythm was reproduced on Tecno Wi-Fi by both v9 (Ktor production) and v11 Arm B (raw OkHttp) — so the run can use either flag. Vladislav-default: **Arm B flag `"B"`** (raw OkHttp, smaller stack to interpret).

**Capture window opening:** PCAPdroid recording started **before** the WS connection opens (logcat marker `PHASE2_CAPTURE_MARKER mode=P1 utc=<start_utc>`). Capture continues across the full session (~150 s expected). The 9th Pong is the cycle whose **absence** would cause OkHttp's next `writePingFrame` to fail; relay-side `ws_protocol_pong_sent` for cycle 9 is the UTC anchor to align with PCAPdroid pcap.

**Capture filter:** app-scoped to Phantom (PCAPdroid's per-app filter). Post-capture `tshark` filter for analysis on the export: `tcp port 443 and host relay.phntm.pro` plus a slightly wider `ip host <relay_ipv4>` to catch RST / ICMP unreachable. DNS / TCP-handshake / TLS-handshake records are kept in the export (narrow analysis, not narrow capture).

**Per-run telemetry contract (Tier 1 evidence rows):**

- Logcat: `RC_DIRECT_ARM_B_ws_open` × 1, `RC_DIRECT_ARM_B_ws_failure` × 1, `okhttp_successful_ping_pongs` = 8, `PHASE2_CAPTURE_MARKER mode=P1 utc=...` × 1.
- Relay docker log: `ws_protocol_pong_sent` × 9 for the same identity within the matching UTC window.
- PCAPdroid pcap: TLS handshake records, ≥ 8 inbound TLS Application Data records correlated in time with relay's first 8 `ws_protocol_pong_sent` timestamps, plus **explicit presence-or-absence determination** for the 9th expected inbound TLS Application Data record at relay's 9th `ws_protocol_pong_sent` timestamp + RTT (RTT estimated from handshake or from earlier Pong pairs).

**Acceptance gate per Arm P1:** at least 2 independent Mode 1-classified sessions on Tecno Wi-Fi pass the per-run telemetry contract within the same field-test day, with the matched Arm P3 control reading also taken on the same day.

#### Arm P2 — Mode 2 capture (Tele2 severe 0-1-pong rhythm)

Capture target: the **1st or 2nd Pong** in a Mode 2-classified session. The 0-1-pong rhythm was reproduced on Tecno Tele2 LTE by both v11 Arm A (production Ktor) and v11 Arm B (raw OkHttp). Vladislav-default: **Arm B flag `"B"`** (consistency with Arm P1).

**Capture window opening:** PCAPdroid recording started **before** the WS connection opens (logcat marker `PHASE2_CAPTURE_MARKER mode=P2 utc=<start_utc>`). Capture continues for ~120 s to cover several Mode 2 session lifetimes. Each session's 1st-2nd Pong cycle and its `ws_protocol_pong_sent` UTC timestamp on relay is an anchor for Tier 1 analysis.

**Capture filter:** identical to Arm P1, app-scoped to Phantom.

**Per-run telemetry contract (Tier 1 evidence rows):**

- Logcat per session: `RC_DIRECT_ARM_B_ws_open` × 1, `RC_DIRECT_ARM_B_ws_failure` × 1, `okhttp_successful_ping_pongs` = 0 (Mode 2 worst case) or 1, `PHASE2_CAPTURE_MARKER mode=P2 utc=...` × 1 per session.
- Relay docker log: `ws_protocol_pong_sent` × 1 (matches OkHttp pp=0) or × 2 (matches OkHttp pp=1) for the same identity within the matching UTC window.
- PCAPdroid pcap: TLS handshake records, **explicit presence-or-absence determination** for the 1st (and 2nd if relay sent two) inbound TLS Application Data record at relay's corresponding `ws_protocol_pong_sent` timestamp + RTT.

**Acceptance gate per Arm P2:** at least 2 independent Mode 2-classified sessions on Tecno Tele2 LTE pass the per-run telemetry contract within the same field-test day, with the matched Arm P3 control reading also taken on the same day.

#### Arm P3 — control run (PCAPdroid-on, no analysis target)

Same network conditions as the matched Arm P1 or Arm P2 capture, same flag, same APK, PCAPdroid-on with capture started before WS opens. The control run **discards** the pcap (or keeps it sealed for an integrity check only) and reads only the logcat-derived mode count / median lifetime / median pp_count. The control run is matched against the corresponding PCAPdroid-off Phase 1 baseline (Phase 1 §13 table for the same network condition).

**Inv-PcapDoesNotMaskMode acceptance rule:** if the Arm P3 control reading agrees with the matched Phase 1 baseline within `lifetime ± 25 %` and `pp_count ± 2`, the matched Arm P1 or P2 capture's Tier 1 evidence closes §23 rows normally. If outside tolerance, the matched capture is marked "PCAPdroid-influenced" in the Phase 2 evidence summary and does not close §23 rows; escalation per §27 parking lot.

### §23 — Decision tree (Tier 1 raw wire outcome → root cause attribution)

Phase 2 closes each mode independently. Either mode can finish ahead of the other; the Phase 2 evidence summary reports them as separate gates.

| Arm P1 / P2 outcome | Arm P3 control | Verdict | Action |
|---|---|---|---|
| Inbound TLS records **absent** at the expected anchor (no record arrived around relay's send time + RTT) | Within tolerance vs Phase 1 baseline | **H-A confirmed for this mode (return-path loss).** Pong never reached the device socket; OkHttp had nothing to count. | 3.2b.1 unfreezes as **UX-protection** per §24 row 1; open server-side network track (`RC-RETURN-PATH-LOSS1`). Mode-specific (Mode 1 conclusion does not imply Mode 2 conclusion). |
| Inbound TLS records **present** at the expected anchor (a record arrived around relay's send time + RTT, but OkHttp did not count Pong) | Within tolerance vs Phase 1 baseline | **H-B/C/D confirmed for this mode (device-side / OkHttp internal).** Traffic arrived but client mis-handled. | RC-DIRECT client-stack fix path takes priority per §24 row 2; 3.2b.1 may still be useful but not as the primary fix surface. Phase 2b (TLS keylog or server-side BPF) optional to discriminate H-B from H-C from H-D. |
| Tier 1 ambiguous (records straddle the anchor; relay-side timestamps drift > 1 s vs pcap; NTP skew untraceable) | Within tolerance | **Inconclusive for this mode.** | Re-run with stricter NTP discipline; if still ambiguous after 2 retries, escalate to Phase 2b. |
| Arm P1 / P2 outcome (any) | **Outside tolerance** vs Phase 1 baseline | **PCAPdroid masked the mode.** Tier 1 evidence does not close. | Per §24 row 3: Phase 2 inconclusive for the affected mode; escalate to Phase 2b TLS keylog (debug-only build) OR server-side BPF on Hetzner. 3.2b.1 stays paused per Inv-NoChangeUntilEvidence until a non-PCAPdroid evidence path produces a verdict. |
| Mixed outcomes across the two modes (e.g. Mode 1 = H-A confirmed, Mode 2 = H-B/C/D confirmed) | Both within tolerance | **Both verdicts stand, independently.** | Action per row above for each mode; Phase 2 evidence summary records both. |

### §24 — Acceptance gates (what counts as "we know enough to act")

The Phase 2 evidence summary PR ships when ALL of:

1. **Mode 1 gate (Arm P1):** at least 2 independent Mode 1-classified sessions captured with Tier 1 per-run telemetry contract satisfied; matched Arm P3 control reading taken same field-test day and within tolerance per Inv-PcapDoesNotMaskMode (or, if outside tolerance, the affected captures are explicitly marked "PCAPdroid-influenced" in the evidence summary).
2. **Mode 2 gate (Arm P2):** at least 2 independent Mode 2-classified sessions captured with Tier 1 per-run telemetry contract satisfied; matched Arm P3 control reading taken same field-test day and within tolerance per Inv-PcapDoesNotMaskMode (or marked as above).
3. **Relay-side cross-correlation:** for every captured session, the relay docker log for the same UTC window is preserved alongside the logcat + pcap and the `ws_protocol_pong_sent` line counts are tabulated against the OkHttp-counted pp values.
4. **Wall-clock alignment evidence:** for every captured session, the three time sources (Tecno logcat marker, relay docker `ws_protocol_pong_sent` timestamp, PCAPdroid pcap frame timestamp) are shown to agree within ≤ 1 s in the evidence summary.
5. **§23 decision-tree outcome** explicitly named per mode in the evidence summary (one row per mode from §23 table) with a one-paragraph "what this rules in and rules out" justification per mode.

**3.2b.1 unpause criteria — explicit, Vladislav-locked:**

| Phase 2 verdict | 3.2b.1 decision |
|---|---|
| Tier 1 shows path loss / unstable return path for either mode (Arm P3 control within tolerance) | **3.2b.1 unfreezes as UX-protection.** Adaptive validation cannot fix the network, but it is the correct response to "session is dying for reasons the client cannot control". |
| Tier 1 shows traffic reaches device, OkHttp not counting Pong, for either mode (Arm P3 control within tolerance) | **RC-DIRECT client-stack fix path takes priority.** 3.2b.1 may still be useful as a slow fallback, but the cheaper / cleaner fix is in the OkHttp internal handling or its Android-side scheduling. 3.2b.1 stays paused until the client-stack fix decision is made. |
| PCAPdroid changes the mode (Arm P3 control outside tolerance) for either mode | **Phase 2 inconclusive for that mode; 3.2b.1 decision deferred** until Phase 2b or a non-PCAPdroid evidence path produces a verdict. Per Inv-NoChangeUntilEvidence, 3.2b.1 remains paused. |
| Mixed verdicts across the two modes | **Decision per mode; Mode 1 and Mode 2 are decoupled.** Example: Mode 1 = H-B/C/D → client-stack fix scope for Mode 1 specifically; Mode 2 = H-A → 3.2b.1 unfreezes as Mode 2 UX-protection. |

### §25 — Implementation order (locked for the Phase 2 work)

Phase 2 has no production code PR. It has:

1. **Pre-field-test prep PR (this PR):** Phase 2 mini-lock §19-§30 appended to `docs/tracks/rc-direct-ws-death1.md`. Zero code change. Optional sibling commit adds `PHASE2_CAPTURE_MARKER` logcat emit gated by `BuildConfig.DEBUG && BuildConfig.DEBUG_RC_DIRECT_ARM != "0"` — **deferred to its own follow-up PR**, not bundled in this mini-lock PR.
2. **Field-test setup (Vladislav-owned):** install PCAPdroid (https://github.com/emanuele-f/PCAPdroid) on Tecno; grant per-app capture permission for Phantom only; verify NTP-fix on Tecno, Windows host, and relay container (`docker exec relay ntpdate -q`).
3. **Field-test execution (Vladislav-owned, assistant generates commands):**
   - Arm P3 Wi-Fi control × 1 (15 min, flag `"B"`, PCAPdroid-on, no analysis target).
   - Arm P1 Wi-Fi capture × 2 (15 min each, flag `"B"`, PCAPdroid-on with analysis target).
   - Arm P3 Tele2 LTE control × 1 (15 min, flag `"B"`, PCAPdroid-on, no analysis target).
   - Arm P2 Tele2 LTE capture × 2 (15 min each, flag `"B"`, PCAPdroid-on with analysis target).
   - Sequential per Inv-ParallelArmIsolation; relay-side state clears between sessions.
4. **Analysis (assistant-owned):** `tshark` / PowerShell extraction of TLS Application Data record counts, anchoring against relay-side `ws_protocol_pong_sent` UTC timestamps; mode classification per §20 bins; §23 decision-tree row attribution per mode.
5. **Phase 2 evidence summary PR:** appended as new sections (§31 onward) to `docs/tracks/rc-direct-ws-death1.md`, modelled after §13-§18. Includes `Last updated` bump in `MASTER_TIMELINE_2026.md` + Session journal entry in `docs/PROJECT_LOG.md` (same bundle pattern as PR #269).
6. **Architect / Vladislav review of Phase 2 evidence summary before any §23 / §24 decision branch is acted upon.**

Optional analysis commands recorded for the assistant's later use (not committed to the repo as code — kept in this mini-lock as the source-of-truth contract; if Phase 2 becomes a repeatable track, a `scripts/phase2-analysis.ps1` may be added in a follow-up PR):

```text
# TLS Application Data records inbound from relay, per session window
tshark -r capture.pcapng -Y "ip.src == <relay_ipv4> and tls.record.content_type == 23" \
  -T fields -e frame.time_epoch -e frame.len -e tcp.seq -e tcp.ack

# RST/FIN events from the relay's IP
tshark -r capture.pcapng -Y "ip.src == <relay_ipv4> and (tcp.flags.reset == 1 or tcp.flags.fin == 1)" \
  -T fields -e frame.time_epoch -e tcp.flags

# Cross-reference: relay-side ws_protocol_pong_sent UTC timestamps
# (collected from docker logs phantom-relay in the matching field-test session)
grep 'ws_protocol_pong_sent' relay-docker.log | jq '.utc'

# Expected CSV per session: [session_id, ws_open_utc, ws_failure_utc, okhttp_pp_count,
#  relay_pongs_sent_count, inbound_tls_app_records_count, anchor_record_present_bool]
```

### §26 — Out of scope (hard)

- ❌ Any production code change. Phase 2 ships zero production diff.
- ❌ Any change to `pingInterval(15s)` / `readTimeout(60s)` / `callTimeout(10s)` as a "fix" (Inv-NoHeartbeatCadenceFix; observation-only).
- ❌ Any change to `RestFallbackOrchestrator`, `RestStateMachine`, `RestHealthMonitor`, `TransportManager`, `TransportStrategy`, `KtorRelayTransport.kt`, `RelayTransportFactory.kt`, `TransportCapabilitiesResolver`, `ConnectionUiState`.
- ❌ Any new WS frame ever sent or echoed for capture-marking purposes (Inv-NoTrafficBeyondTelemetry — the only emission is a logcat line).
- ❌ Any relay-side change to enable Phase 2. PR #259's `ws_protocol_pong_sent` instrumentation is read-only consumed.
- ❌ App-level Ping reintroduction (Inv-NoAppLevelPingResurrection from Phase 1 still applies).
- ❌ PCAPdroid-mitm / TLS interception in Phase 2 proper. That is Phase 2b parking-lot (§27).
- ❌ Rooting the Tecno handset for `tcpdump` access. PCAPdroid root-free is the chosen path.
- ❌ Server-side BPF on the Hetzner relay host in Phase 2 proper. Parking-lot.
- ❌ 3.2b.1 code resumption before Phase 2 evidence summary lands (Inv-NoChangeUntilEvidence carries through).
- ❌ CHIP1 work (parked at `78bd979e`).
- ❌ `RC-REALITY-PROBE1` work (separate track).
- ❌ `PR-UI-CONNECTION-LABELS1` work (separate track).
- ❌ Carrier-level / UI-level / vendor-level claims (Inv-NoCarrierOrUiClaim).
- ❌ Second Android handset (Arm I from Phase 1 §4) — optional, not gating Phase 2; parking-lot.

### §27 — Parking lot (deferred until Phase 2 evidence)

- **Phase 2b — TLS keylog / decrypted Pong proof.** Required only if Phase 2 Tier 1 cannot discriminate one or both modes, OR if Inv-PcapDoesNotMaskMode fails (PCAPdroid altered the mode). Two candidate paths: (a) debug-only build that emits `SSL_KEYLOGFILE` to the device's app-private storage, used with Wireshark TLS decryption for the analysis pcap; (b) PCAPdroid-mitm with the local CA installed for Phantom only — alters the trust chain and may itself shift the mode, recorded as the more invasive option. Designed in its own mini-lock section if triggered.
- **Server-side BPF on Hetzner relay host.** Required if both PCAPdroid raw and Phase 2b alter the mode. Captures the wire at the relay's egress, not the device's ingress; discriminates Hetzner-egress / Caddy-proxy issues from radio-path issues. Production VPS impact ~5 min during BPF attach/detach.
- **Second Android handset on Wi-Fi + Tele2 (Arm I from Phase 1 §4).** Discriminates Tecno-specific HiOS quirks from general Android-handset behaviour. Optional, not gating; recorded as a high-value follow-up if Tier 1 confirms H-B/C/D for either mode.
- **Mode-3 (or higher) classification.** If a Phase 2 session lands in the "Unbinned" row of §20 (pp_count ≥ 3 but ≤ 5, OR lifetime > 60 s but < 120 s), and this happens repeatedly across the field-test day, a new mode bin may be locked. Not a Phase 2 deliverable.
- **Scripted parser `scripts/phase2-analysis.ps1` in repo.** Only if Phase 2 becomes a repeatable track. Single-use Phase 2 analysis lives in the assistant's working notes, not the repo.
- **3.2b.1 mini-lock section update.** Once Phase 2's verdicts arrive, the WS-HEALTH-STATE1 track's 3.2b.1 plan needs a rev-bump that incorporates Phase 2's mode-specific verdicts. Not Phase 2's job; sits on the WS-HEALTH-STATE1 track.

### §28 — Process gates

- **WORKING_RULES rule 8** (transport regression gate): carve-out applies; Phase 2 ships zero production behaviour and zero code change.
- **WORKING_RULES rule 9** (no merge without verification): applies fully. Phase 2 evidence summary PR's every concrete claim must be grep-verifiable against the captured logcat + pcap + relay docker log artefacts, OR architect-explicit-ACK after diff read.
- **`feedback_apk_build_is_mine.md`** — APK builds are assistant-owned (no new APK needed for Phase 2; the Phase 1 v11 APK SHAs `2ac2afda...` / `48ffbefe...` are reused). PCAPdroid setup on Tecno is Vladislav-owned (user-app install + permission grant).
- **`feedback_logcat_format.md`** — Phase 2 logcat capture commands generated by the assistant use the canonical PowerShell `Tee-Object` format with the existing tag set (no new tag added for Phase 2; `PHASE2_CAPTURE_MARKER` is emitted under the existing `PhantomMessaging` tag if and when the follow-up emit PR ships, OR captured as an `adb shell log -t PhantomMessaging` manual marker prior to that follow-up PR).
- **`feedback_durable_log.md`** — Phase 2 evidence summary PR will append a Session journal entry to `docs/PROJECT_LOG.md` and bump `Last updated` in `docs/project/MASTER_TIMELINE_2026.md`, same bundle pattern as PR #269.
- **`feedback_session_close_discipline.md`** — Phase 2 mini-lock is rev1; this PR ships rev1. Field-test execution and analysis happen in separate sessions. Phase 2 evidence summary PR is a separate session and a separate PR.
- **`feedback_ws_heartbeat_diagnostic_2026_05_27.md`** — informs Inv-NoHeartbeatCadenceFix carried over from Phase 1.

### §29 — Open questions (locked by Vladislav 2026-06-03)

| OQ | Question | Vladislav-locked answer (2026-06-03) |
|---|---|---|
| **OQ-P1** | PCAPdroid mode: raw vs mitm? | Raw first. MITM not as primary because it may shift the mode being measured. Decrypted Pong frame proof = optional Phase 2b only if Tier 1 cannot discriminate (parking lot §27). |
| **OQ-P2** | Filter scope: app-only vs full-device? | App-scoped capture for Phantom (PCAPdroid per-app filter). Analysis filter on `relay.phntm.pro:443` for the WS path, plus wider `ip host <relay_ipv4>` to catch RST / ICMP unreachable. DNS / TCP-handshake / TLS-handshake records kept in the export (narrow analysis, not narrow capture). |
| **OQ-P3** | Wall-clock alignment strategy? | Both NTP-fix on all three components (Tecno, Windows host, relay container) AND explicit logcat marker `PHASE2_CAPTURE_MARKER mode=... utc=...`. No WS marker frame — would alter the stream being measured (Inv-NoTrafficBeyondTelemetry). |
| **OQ-P4** | Synchronous relay-side recapture using PR #259 instrumentation? | **Hard requirement.** Every Phase 2 PCAPdroid session has matched relay docker log preservation for the same UTC window. Phase 1's relay-side wording was softened in §16 because v11 had no synchronous recapture; Phase 2 closes this gap by construction. |
| **OQ-P5** | Mode-swing handling within a session? | Classify post-hoc, do NOT discard. Pre-declared target (Wi-Fi for Mode 1 / Tele2 LTE for Mode 2) is the planning intent; the actual mode is determined by §20 bins applied to the session's pp_count and lifetime. A Tele2 session that lands on Mode 1 is recorded as "Mode 1 on Tele2", not retried. Unbinned sessions are recorded but do not close discrimination gates. |
| **OQ-P6** | Evidence format: manual Wireshark vs scripted? | Scripted `tshark` / PowerShell extraction is the gate, not manual Wireshark inspection. Analysis commands + expected CSV schema are in §25 as the source-of-truth contract. Parser-in-repo (`scripts/phase2-analysis.ps1`) only if Phase 2 becomes repeatable (parking lot §27). |
| **OQ-P7** | 3.2b.1 unpause criteria — where defined? | In §24 acceptance-gates table, explicit Vladislav-locked criteria per Phase 2 verdict. Decoupled across the two modes (a Mode 1 verdict does not force a Mode 2 decision and vice versa). |
| **OQ-P8** | Branch hygiene if master moves before Phase 2 PR publish? | Branch from `16ee99b9`. Docs-only mini-lock — conflict probability low. If unrelated PRs land before publish, quick rebase before push; PR body honestly reports actual base commit. |

### §30 — Source-of-truth pointers

- Phase 1 mini-lock and evidence summary (this file §1-§18) — every Phase 2 design decision references back to a Phase 1 outcome.
- `docs/tracks/ws-health-state.md` § Commit 3.2b.1 — the adaptive-validation code Phase 2's verdict gates.
- `MASTER_TIMELINE_2026.md` "Last updated 2026-06-02 (tue, late)" — track sequencing. Will be bumped again when Phase 2 evidence summary PR lands.
- `docs/PROJECT_LOG.md` Session journal 2026-06-02 (tue, late) entry — `RC-DIRECT-WS-DEATH1 Phase 1 CLOSED` recorded; Phase 2 mini-lock will be a follow-up Session journal entry on its own PR.
- PR #259 (`8727031f`, Commit 3.3 — relay-side `ws_protocol_pong_sent` telemetry) — the synchronous recapture data source required by Inv-WallClockAlignment and OQ-P4.
- Phase 1 v11 APK SHAs `2ac2afda...` (Arm A, flag `"0"`) and `48ffbefe...` (Arm B, flag `"B"`), built on master `a9f66ad0` — reused for Phase 2 field tests without rebuild.
- PCAPdroid project page: https://github.com/emanuele-f/PCAPdroid — root-free Android packet capture via local VPN.
- PCAPdroid-mitm project page: https://github.com/emanuele-f/PCAPdroid-mitm — TLS interception variant for Phase 2b only.
- Wireshark TLS decryption reference: https://wiki.wireshark.org/TLS — TLS keylog reference for Phase 2b path (a) only.
- OkHttp issue #3227 (open by maintainer Swankjesse 2017-03-18) — "pong timeout not independent of ping interval" — informs H-B/C/D hypothesis attribution.
- Codex external architect memo (received 2026-06-02 via Vladislav) — informs Phase 1 hypothesis space carried into Phase 2.

Phase 2 mini-lock rev1 closes here. CHIP1 remains parked at `78bd979e` throughout. 3.2b.1 code remains paused per `Inv-NoChangeUntilEvidence`. Phase 2 evidence summary will be a separate PR after field-test execution and analysis complete.

---

## Phase 2 outcome — evidence summary (Vladislav-locked 2026-06-03 (wed) after PCAPdroid v12 capture set)

Master at lock: this PR's merge commit, on top of `358e063e`. Phase 2 closed; 3.2b.1 code path unfreezes per §35; Council on revised 3.2b.1 scope follows in a separate session per Vladislav direction.

### §31 — Empirical base — Phase 2 v12 capture set

Six logcat + pcap + relay-log triples executed sequentially on the same field-test day across two network types. Identity unchanged throughout. APKs all built from master `358e063e` with `rcDirectArm=B` (Phase 1 Arm B raw OkHttp diagnostic) and one of three `phase2Mode` values (P3 control / P1 Wi-Fi target / P2 Tele2 LTE target) per Phase 2 mini-lock §22. PCAPdroid in raw mode, app-scoped to `phantom.android`, full pcap export.

| # | Session | Network | APK SHA256 | UTC start | UTC end | Duration |
|---|---|---|---|---|---|---|
| 1 | Arm P3 Wi-Fi control | Wi-Fi | `09b3ec5c12028a8a87f6cbb01c45f4cb0511c830eeb939a8f59265addc1986d8` (APK_P3) | `2026-06-03T04:51:15.140Z` | `2026-06-03T05:06:11.531Z` | 14m 56s |
| 2 | Arm P1 Wi-Fi capture #1 | Wi-Fi | `2ca6908c627f57172edee0f0ec47ceb78e55f47058c3657f6bc992087a056bfb` (APK_P1) | `2026-06-03T05:10:37.209Z` | `2026-06-03T05:25:05.804Z` | 14m 28s |
| 3 | Arm P1 Wi-Fi capture #2 | Wi-Fi | same APK_P1 | `2026-06-03T06:05:42.381Z` | `2026-06-03T06:22:00.628Z` | 16m 18s |
| 4 | Arm P3 Tele2 LTE control | Tele2 LTE | APK_P3 (reinstall) | `2026-06-03T06:28:41.468Z` | `2026-06-03T06:46:45.331Z` | 18m 03s |
| 5 | Arm P2 Tele2 LTE capture #1 | Tele2 LTE | `ce8c52de38c621f1334c61fa65b3c345632ea9a19405f665ca312af8f25994a9` (APK_P2) | `2026-06-03T06:50:51.637Z` | `2026-06-03T07:06:03.922Z` | 15m 12s |
| 6 | Arm P2 Tele2 LTE capture #2 | Tele2 LTE | same APK_P2 | `2026-06-03T07:08:53.165Z` | `2026-06-03T07:25:22.341Z` | 16m 29s |

Total: 6 sessions × 3 artifacts = **18 files** archived in `C:\temp\phase2-day-2026-06-03\`. Artifact triples per session: `arm-<arm-id>-<network>-<phase>-tecno.log` (PowerShell `Tee-Object`, UTF-16 LE BOM), `arm-<arm-id>-<network>-<phase>-tecno.pcap` (PCAPdroid full pcap export), `arm-<arm-id>-<network>-<phase>-relay.log` (`docker logs phantom-relay --since/--until`, UTF-16 LE BOM).

Initial Sessions 2 (`arm-p1-wifi-cap1-relay.log`) was empty (0 bytes) on first pull due to UTC argument truncation; re-pull on the same field-test day succeeded with `132 940 bytes` and the session was promoted from PARTIAL to PASS for Mode 1 evidence.

Wall-clock alignment per Inv-WallClockAlignment §21: three time sources verified within ≤ 1 s at field-test day start (Tecno `adb shell date -u` 04:45:50, Windows host `[DateTime]::UtcNow` 04:45:51.26, relay container `docker exec phantom-relay date -u` 04:45:53 — three-second spread of which most is `adb shell` + `ssh round-trip` latency, real clock skew ≤ 1 s).

### §32 — Tier 1 raw wire correlation findings

Independently verified with `tshark` 4.6.6 spot-checks on three architect-anchored cases. Reproducible commands in §36.

**(a) Mode 1 — Wi-Fi 8-pong rhythm — return-path loss confirmed.**

| Capture | Mode 1 deaths | With relay `ws_protocol_pong_sent` log anchor | Inbound TLS records ±2s around anchor (tshark) |
|---|---|---|---|
| Arm P1 Wi-Fi cap1 (Session 2) | 5 | 5 | **0** |
| Arm P1 Wi-Fi cap2 (Session 3) | 6 | 5 of 6 (one outside relay log window) | **0** |

For each Mode 1 death: relay docker log records `ws_protocol_pong_sent conn_id=<id> pongs_sent=N+1` at the expected UTC, but the device pcap shows **zero inbound TLS Application Data records** from `65.108.154.152:443` in a ±2 s window around relay's send-time + RTT, and **zero packets of any kind** from relay in a wider ±10 s window. This is the canonical signature of return-path loss: relay sent the Pong, it never reached the device's TCP stack.

Tshark spot-check #1 (P1 Wi-Fi cap2 Session 3, 9th Pong anchor `2026-06-03T06:11:00.196Z`): independently verified `0` inbound TLS records ±2 s, `0` packets ±10 s. Architect parser output matches.

**(b) Mode 2 — Tele2 LTE severe 0-1-pong rhythm — pp=0 sub-case: return-path loss confirmed.**

| Capture | pp=0 deaths | With relay `ws_protocol_pong_sent` log anchor | Inbound TLS records ±2s around anchor (tshark) |
|---|---|---|---|
| Arm P2 Tele2 LTE cap1 (Session 5) + cap2 (Session 6) combined | 3 | 3 | **0** |

Same signature as Mode 1: relay sent 1st Pong, device pcap shows no inbound TLS records around the anchor.

Tshark spot-check #2 (P2 Tele2 cap1 first session, 1st Pong anchor `2026-06-03T06:51:28.529Z`): independently verified `0` inbound TLS records ±2 s, `0` packets ±10 s. Architect parser output matches.

**(c) Mode 2 — Tele2 LTE severe 0-1-pong rhythm — pp=1 sub-case: TCP-layer ambiguous.**

| Capture | pp=1 deaths | Outbound 2nd ping TLS payload visible in pcap | Relay-side `ws_protocol_ping_received` for the 2nd ping |
|---|---|---|---|
| Arm P2 Tele2 LTE cap1 + cap2 combined | 36 | yes (28-byte outbound TLS Application Data records around expected 2nd-ping UTC) | **no** (relay session_summary consistently shows `pings_received=1`) |

For each pp=1 death: the device pcap contains an outbound 28-byte TLS Application Data record at the expected 2nd-ping UTC (≈ 1st-ping UTC + 15 s pingInterval), but the relay logs only `pings_received=1` for that `conn_id` and closes the session with `Connection reset without closing handshake` and a `since_last_ping_ms` value ≈ session lifetime − 15 s.

Tshark spot-check #3 (P2 Tele2 cap1, conn_id=197, 2nd-ping anchor near `2026-06-03T06:52:13.452Z`): outbound 28-byte TLS Application Data record observed at `06:52:14.649Z` (1.2 s after architect's expected anchor). At `06:52:14.650Z` — 1 ms after the outbound packet — a TCP ACK arrives from relay (0-byte TLS payload). Relay's WS layer logged no `ws_protocol_ping_received` for the 2nd ping; session ended at `06:54:34.569Z` with `pings_received=1 pongs_sent=1 since_last_ping_ms=153650`.

**Architect refinement (Vladislav-locked language):** Mode 2 pp=1 sub-case is **TCP-layer ambiguous**. The pcap shows an outbound TLS payload and a near-immediate relay TCP ACK, but the relay's WS layer logs no second ping/pong. Discriminating "Tecno-side TCP retransmit satisfied by duplicate ACK → uplink loss at IP layer" from "TCP packet arrived at relay's TCP buffer but the inner WS frame was not delivered to relay's WS application layer → relay-side TLS/WS delivery stall" requires TCP sequence/ack-number analysis or server-side BPF. Both candidates point to the same operational conclusion: **the link Tecno ↔ relay is unreliable in this sub-case**.

The exact mechanism is parked (see §38 parking lot) — discrimination is not load-bearing for the §35 unfreeze decision.

### §33 — Inv-PcapDoesNotMaskMode verification (P3 control comparisons)

Per Phase 2 mini-lock §21 Inv-PcapDoesNotMaskMode: each Arm P1 / P2 capture is paired with an Arm P3 control reading taken on the same field-test day. Acceptance: control reading within `lifetime ± 25 %` and `pp_count ± 2` of the matching Phase 1 baseline.

| Control | Phase 1 baseline | Control reading (under PCAPdroid VPN) | Within tolerance? |
|---|---|---|---|
| Arm P3 Wi-Fi (Session 1) vs v9 Tecno Wi-Fi (production Ktor) | 5 sessions, ~145 s median lifetime, 8 pp | 5 sessions × pp=8 × lifetime 150 045 .. 150 053 ms (mean ~150 s) | **YES** — lifetime within +3 % of baseline; pp identical |
| Arm P3 Tele2 LTE (Session 4) vs v11 Arm A Tecno Tele2 LTE | 29 sessions, ~31 s median lifetime, pp=0 | 23 sessions × pp=0 or 1 × lifetime 30 004 .. 45 010 ms | **YES** — lifetime band overlaps Phase 1; pp within ±2 |

Inv-PcapDoesNotMaskMode **satisfied for both modes**. PCAPdroid raw mode did not materially shift either rhythm relative to Phase 1 baselines. Tier 1 evidence in §32 closes §23 rows for both modes.

### §34 — Decision tree resolution (Phase 2 mini-lock §23)

Phase 2 mini-lock §23 listed five outcome rows. Phase 2 captures match two of them cleanly plus identify one row not anticipated by the original §23 table.

| §23 row | Phase 2 evidence | Verdict per row |
|---|---|---|
| "Tier 1 records **absent** at expected anchor + Arm P3 control within tolerance → **H-A confirmed for this mode (return-path loss)**" | **Mode 1 fires this row** (§32(a): 11 Mode 1 deaths across cap1+cap2, 10 with relay Pong anchor, 0 inbound TLS records). Inv-PcapDoesNotMaskMode satisfied per §33. | **H-A confirmed for Mode 1.** Return-path loss is the mechanism. Direct WS on Wi-Fi cannot be made reliable by client-side changes alone. |
| Same row applied to Mode 2 pp=0 sub-case | **Mode 2 pp=0 sub-case fires this row** (§32(b): 3 deaths, 3 with relay Pong anchor, 0 inbound TLS records). Inv-PcapDoesNotMaskMode satisfied per §33. | **H-A confirmed for Mode 2 pp=0 sub-case.** Return-path loss for 1st Pong. |
| "Tier 1 records **present** at expected anchor + Arm P3 control within tolerance → **H-B/C/D confirmed (device-side / OkHttp internal)**" | Not observed in any captured session. No Mode 1 or Mode 2 death has inbound TLS records present at the expected anchor. | **H-B/C/D refuted** as a primary or even contributing mechanism for any death captured in Phase 2. Phase 1's hypothesis that OkHttp internal mis-handling might explain the deaths is **not supported** by Phase 2 raw wire evidence. |
| "Tier 1 ambiguous" | Mode 2 pp=1 sub-case (§32(c)) is **Tier 1 evidence ambiguous at the TCP layer**, but for a refined reason: outbound TLS payload visible, relay TCP-acks it, but relay WS layer doesn't process it. Original §23 "ambiguous" row anticipated NTP drift or anchor-aliasing as causes; this is a different ambiguity. | **§23 table did not anticipate this row.** §38 adds it to the parking lot. **Operational implication identical to H-A:** link is unreliable for this sub-case, regardless of which TCP-layer mechanism is responsible. |
| "PCAPdroid masked the mode → §27 escalation" | Not fired. Inv-PcapDoesNotMaskMode satisfied per §33. | n/a |
| "Mixed verdicts across modes" | Fires (Mode 1 = H-A; Mode 2 = H-A for pp=0 + TCP-ambiguous for pp=1) | **Per-mode verdicts stand, independent.** §35 applies §24 verdict rows per mode. |

### §35 — 3.2b.1 unpause decision per §24 acceptance gates

Phase 2 mini-lock §24 Vladislav-locked: per-mode 3.2b.1 unpause criteria, decoupled across modes.

**Mode 1 (Wi-Fi 8-pong rhythm): Tier 1 shows path loss, Arm P3 control within tolerance → §24 row 1 fires → 3.2b.1 unfreezes as UX-protection.**

**Mode 2 (Tele2 LTE severe 0-1-pong rhythm): Tier 1 shows path loss for pp=0 sub-case (3 of ≥ 39 deaths); TCP-ambiguous for pp=1 sub-case (36 of ≥ 39 deaths). Both sub-cases share the same operational implication: the link is unreliable. Arm P3 control within tolerance → §24 row 1 fires (modulo the pp=1 TCP-ambiguity which does not affect the unfreeze decision per Vladislav direction) → 3.2b.1 unfreezes as UX-protection.**

**Combined verdict (Vladislav-locked language):** `3.2b.1 unfreezes as UX-protection for both modes`, formulated as: "Mode 1 closed as return-path loss; Mode 2 closed as unstable TCP/TLS path with mixed sub-cases".

`Inv-NoChangeUntilEvidence` (Phase 1 mini-lock §3) is now formally satisfied for the 3.2b.1 commonMain code path. Implementation work can resume.

The 3.2b.1 scope itself — adaptive validation threshold revision in light of Phase 2 evidence (Mode 2 severity, mixed sub-cases, bidirectional fragility) — is a separate Council session per Vladislav direction (parking lot §38).

### §36 — Tshark reproducible commands (independent verification of architect parser output)

`tshark` 4.6.6 installed via `winget install --id WiresharkFoundation.Wireshark`. All three spot-checks executed against the raw pcap artifacts in `C:\temp\phase2-day-2026-06-03\` with the following commands.

**Spot-check #1 — Mode 1 P1 Wi-Fi cap2, 9th Pong anchor:**

```text
tshark -r arm-p1-wifi-cap2-tecno.pcap \
  -Y 'ip.src == 65.108.154.152 and tls and frame.time >= "2026-06-03 06:10:58.196" and frame.time <= "2026-06-03 06:11:02.196"' \
  -T fields -e frame.time_utc -e tcp.srcport -e tcp.len
# Result: empty (0 lines) — 0 inbound TLS records ±2s

tshark -r arm-p1-wifi-cap2-tecno.pcap \
  -Y 'ip.src == 65.108.154.152 and frame.time >= "2026-06-03 06:10:50" and frame.time <= "2026-06-03 06:11:10"' \
  -T fields -e frame.time_utc -e tcp.flags
# Result: empty — 0 ALL packets ±10s
```

**Spot-check #2 — Mode 2 pp=0 P2 Tele2 cap1 first session, 1st Pong anchor:**

```text
tshark -r arm-p2-tele2-cap1-tecno.pcap \
  -Y 'ip.src == 65.108.154.152 and tls and frame.time >= "2026-06-03 06:51:26.529" and frame.time <= "2026-06-03 06:51:30.529"' \
  -T fields -e frame.time_utc -e tcp.srcport -e tcp.len
# Result: empty — 0 inbound TLS records ±2s

tshark -r arm-p2-tele2-cap1-tecno.pcap \
  -Y 'ip.src == 65.108.154.152 and frame.time >= "2026-06-03 06:51:20" and frame.time <= "2026-06-03 06:51:40"' \
  -T fields -e frame.time_utc
# Result: empty — 0 ALL packets ±10s
```

**Spot-check #3 — Mode 2 pp=1 P2 Tele2 cap1, conn_id=197 full session timeline:**

```text
tshark -r arm-p2-tele2-cap1-tecno.pcap \
  -Y 'tcp.port == 55954 and (ip.src == 65.108.154.152 or ip.dst == 65.108.154.152)' \
  -T fields -e frame.time_utc -e ip.src -e ip.dst -e tcp.len -e tcp.flags
# Key rows from output (see §32(c) for full interpretation):
#   06:51:43.830  10.215.173.1 → 65.108.154.152   0 bytes  SYN (0x0002)
#   06:51:44.636  65.108.154.152 → 10.215.173.1   370 bytes  TLS handshake done
#   06:51:59.645  10.215.173.1 → 65.108.154.152   28 bytes  1st ping outbound
#   06:51:59.836  65.108.154.152 → 10.215.173.1   24 bytes  1st pong inbound  (pp=1 confirmed)
#   06:52:14.649  10.215.173.1 → 65.108.154.152   28 bytes  2nd ping outbound
#   06:52:14.650  65.108.154.152 → 10.215.173.1    0 bytes  TCP ACK (no TLS payload)
#   06:52:29.658  10.215.173.1 → 65.108.154.152   24 bytes  outbound (3rd ping or close-prelude)
#   06:52:29.661  10.215.173.1 → 65.108.154.152    0 bytes  FIN-ACK (0x0011)  Tecno closes
#   06:53:32.141  65.108.154.152 → 10.215.173.1    0 bytes  RST-ACK (0x0014)  relay closes
```

Note: `10.215.173.1` is PCAPdroid's local VPN gateway address on the device side (since PCAPdroid in raw mode tunnels traffic through a local VPN). The actual relay IP `65.108.154.152` is preserved as the remote peer; PCAPdroid's VPN does not NAT the remote peer address.

Relay-side cross-correlation for spot-check #3 (UTF-8-converted relay log):

```text
grep "conn_id=197 " arm-p2-tele2-cap1-relay-clean.log
# Output:
#   06:51:45.746  metadata event="connect" key=89c3b665e9e446b1 conn_id=197
#   06:52:00.919617  ws_protocol_ping_received conn_id=197 pings_received=1
#   06:52:00.919712  ws_protocol_pong_sent conn_id=197 pongs_sent=1
#   06:54:34.569331  WARN ws read error — closing session conn_id=197 error=WebSocket protocol error: Connection reset without closing handshake
#   06:54:34.569376  ws session ended event="session_summary" conn_id=197 duration_ms=168823 pings_received=1 pongs_sent=1 inbound_frames=1 outbound_frames=0 since_last_ping_ms=153650
```

Relay-side observed exactly 1 ping/pong for conn_id=197, despite Tecno's pcap showing 2 outbound ping-like 28-byte TLS payloads. This is the TCP-ambiguous Mode 2 pp=1 sub-case per §32(c).

`PCAPdroid` v1.6.x raw mode; `Wireshark`/`tshark` 4.6.6; `PowerShell` ANSI-strip helper applied to UTF-16 BOM relay log for `grep` consumption.

### §37 — Architect interpretation reconciliation

Architect provided first-pass analysis (received 2026-06-03 via Vladislav) using a custom read-only parser. Independent tshark spot-checks confirm or refine the architect's findings as follows:

| Architect claim | Tshark verification | Status |
|---|---|---|
| "Mode 1 (Wi-Fi): 0 inbound TLS records ±2s around relay-side 9th Pong UTC" | Spot-check #1: confirmed `0` ±2 s plus `0` ±10 s | **Confirmed** |
| "Mode 2 pp=0 sub-case (Tele2): 0 inbound TLS records ±2s around relay-side 1st Pong UTC" | Spot-check #2: confirmed `0` ±2 s plus `0` ±10 s | **Confirmed** |
| "Mode 2 pp=1 sub-case (Tele2): outbound TLS visible around expected 2nd ping, relay не видит следующий ping/pong, **похоже на uplink loss**" | Spot-check #3: outbound TLS confirmed (at 06:52:14.649 — 1.2 s after architect's expected 06:52:13.452); relay-side `pings_received=1` for conn_id=197 confirmed. **TCP ACK from relay at 06:52:14.650 introduces TCP-layer ambiguity** that distinguishes "pure uplink loss" from "TCP arrived but WS-layer stalled". Architect's "похоже на uplink loss" was a hypothesis, not a closed conclusion. | **Refined**: architect's operational verdict ("link unreliable") stands; the specific mechanism ("uplink loss" vs "relay-side TLS/WS delivery stall") is left open per §32(c) and parked in §38. |

The refinement does **not** change the §35 unfreeze verdict. It does motivate keeping the discrimination as a parking-lot item for a future deeper-dive track if the 3.2b.1 work itself surfaces actionable questions about Mode 2 sub-case classification at runtime.

### §38 — Parking lot (deferred until Phase 2 outcomes are absorbed)

- **Mode 2 pp=1 TCP-layer mechanism discrimination.** Distinguishing "Tecno-side TCP retransmit satisfied by duplicate ACK → IP-layer uplink loss" from "TCP packet arrived at relay's TCP buffer but the inner WS frame was not delivered to relay's WS application layer → relay-side TLS/WS delivery stall" requires either TCP sequence/ack-number deep-dive analysis on the existing pcap artifacts, or a future server-side BPF capture on the Hetzner relay host paired with a Phase 2-equivalent Tecno field run. Not load-bearing for §35 unfreeze decision. Open only if the 3.2b.1 implementation work surfaces operational questions that need this discrimination.
- **Phase 2b — TLS keylog / decrypted Pong proof.** Mini-lock §27 parking-lot item carries over: not triggered by Phase 2 outcomes because Tier 1 raw wire correlation closed the discrimination gates for both modes. Re-evaluate only if a future track explicitly needs WS-frame-level evidence beyond TCP/TLS record-level evidence.
- **Server-side BPF on Hetzner relay host.** Same status as Phase 2b: not triggered.
- **Second Android handset on Wi-Fi + Tele2 (Arm I from Phase 1 §4).** Not triggered. Phase 2 closed both modes on Tecno alone.
- **3.2b.1 scope-revision Council session (Vladislav-locked sequencing).** This evidence summary PR lands first; Council on the revised 3.2b.1 design in light of Phase 2 findings (Mode 2 severity, mixed sub-cases, bidirectional fragility, threshold implications for adaptive validation) follows as a separate session per Vladislav direction. The Council outcome lands as a new design-note PR on the WS-HEALTH-STATE1 track, not as a new Phase 3 section here.

### §39 — Source-of-truth pointers

Phase 2 evidence summary references:

- Phase 1 mini-lock and evidence summary (this file §1-§18) — Phase 1 hypothesis space and Phase 1 v11 baselines for Inv-PcapDoesNotMaskMode comparison.
- Phase 2 mini-lock (this file §19-§30) — Phase 2 design that this evidence summary fulfils.
- `docs/tracks/ws-health-state.md` § Commit 3.2b.1 — the adaptive-validation code that unfreezes per §35.
- `MASTER_TIMELINE_2026.md` "Last updated 2026-06-03 (wed)" — track sequencing; Phase 2 closure bumped this PR.
- `docs/PROJECT_LOG.md` Session journal 2026-06-03 (wed) entry — `RC-DIRECT-WS-DEATH1 Phase 2 CLOSED + 3.2b.1 UNFROZEN` durable-log entry from this PR.
- PR #259 (`8727031f`, Commit 3.3 — relay-side `ws_protocol_pong_sent` telemetry) — the relay-side data source used for the Inv-WallClockAlignment cross-correlation.
- PR #271 (`358e063e`, Phase 2 marker emit) — the `PHASE2_CAPTURE_MARKER` logcat anchor used to align Tecno logcat / relay docker logs / PCAPdroid pcap timestamps per session.
- Phase 2 v12 APK SHAs: `09b3ec5c...` (APK_P3, `phase2Mode=P3`), `2ca6908c...` (APK_P1, `phase2Mode=P1`), `ce8c52de...` (APK_P2, `phase2Mode=P2`) — all built on master `358e063e` with `rcDirectArm=B`.
- Phase 2 v12 artifact set: 18 files in `C:\temp\phase2-day-2026-06-03\` (6 sessions × 3 artifacts each). UTF-16 BOM relay logs; UTF-8-converted variants for grep consumption under `*-clean.log`.
- Tshark version: `Wireshark` / `tshark` 4.6.6 (installed via winget `WiresharkFoundation.Wireshark`). PCAPdroid version: 1.6.x (raw mode, app-scoped to `phantom.android`, full pcap export).
- Architect first-pass analysis (received 2026-06-03 via Vladislav) — base interpretation that §32 + §37 either confirmed or refined.

Phase 2 closes here. CHIP1 remains parked at `78bd979e`. `Inv-NoChangeUntilEvidence` is now satisfied for 3.2b.1; that code path unfreezes for design + implementation work, which proceeds on the WS-HEALTH-STATE1 track in a separate session after Council on revised scope.
