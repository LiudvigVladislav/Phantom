# Track: PR-LTE-NETCHANGE1 — Standard LTE chain rewalk + LTE diagnostics hardening

**Status:** queued (mini-lock authored before code per `docs/WORKING_RULES.md` rule 3). **Inserted at the HEAD of the next-session queue, BEFORE `PR-CRYPTO-SESSION-REPAIR1`**, because Tele2 LTE is the daily transport test environment and it currently blocks base-functionality testing.
**Branch (not yet opened):** `feat/pr-lte-netchange1` (cut fresh from master after this mini-lock merges).
**Layer:** Android transport only. NO crypto, NO UI, NO relay.
**Authored:** 2026-05-28 from the external transport architect's `PR-LTE-NETCHANGE1` review of master `1d876aa5` (post PR #234).

## Problem

Two related issues observed after PR-RECV-DIAG1 (#234) shipped:

1. **Tele2 LTE Иркутская blocks base messenger function.** Vladislav's primary test phone reports that on Tele2 LTE neither Direct nor Reality work; the chain falls to Tor. With the sticky-Tor regression from PR #234 now fixed (v1.7 of that PR), Tor as the result of a real chain-walk is no longer a bug — but currently the logs do not attribute *why* Direct and Reality fail, so we cannot tell whether this is correct fallback behaviour or a regression.
2. **`Event.NetworkChanged` is a dead handler.** `RestStateMachine.kt:294` defines `object NetworkChanged : Event()` and `RestStateMachine.kt:83/149` wires the handler, but `HybridRelayTransport.kt:94` explicitly documents that `NetworkChanged events are deliberately NOT wired in D1b`. No Android code submits the event. The recovery path on Wi-Fi ↔ LTE switch is therefore broken: a stale `lastWorkingTransport` from the previous network can survive into a session on the new network, and `KtorRelayTransport.connect()` (line 600) caches `socksProxyPort` at first call and reuses it on every internal reconnect via `httpClientFactory(socksProxyPort)` (line 677) without re-invoking `TransportManager.connect()` — so the chain is never re-walked.

The combination means: even when the underlying network has changed, the transport stack can stay locked to whatever path worked on the previous network, with no diagnostic surface explaining the lock-in.

## Goal

Add the missing Android-side wiring so:

1. A real network change (Wi-Fi ↔ cellular, VPN appear/disappear, network lost/regained, validated-capability change) fires `Event.NetworkChanged` end-to-end.
2. On that event, the sticky hint is cleared, the WS generation disconnects, and `TransportManager.connect()` re-walks Standard as `[Direct, Reality, Tor]` against the current network.
3. The cold-start LTE path becomes diagnostically *attributable*: every chain step logs not only `ok=true/false` but also *why* it failed (Direct probe phase, Reality filtered due to VPN, etc.).

After this PR, "Tor on Tele2 LTE" is acceptable **only** if the log explicitly shows Direct failed and Reality either failed or was filtered with a documented reason.

## Architect-designed implementation changes

Lifted from the external transport architect's `PR-LTE-NETCHANGE1` review (2026-05-28) with minor wording aligned to the local style. All five items in this PR — they are coupled and shipping any subset leaves the chain-rewalk path incomplete.

### Step 1 — Android `ConnectivityManager.NetworkCallback`

Add a single `ConnectivityManager.NetworkCallback` owned by either the foreground `PhantomMessagingService` or `AppContainer`. Single instance for the process lifetime; registered on service / container init, unregistered on shutdown.

The callback must detect **meaningful** changes only — not every minor capability flip:
- Wi-Fi ↔ cellular transport flip.
- VPN capability appears or disappears.
- Network lost / available.
- `NET_CAPABILITY_VALIDATED` capability change (validated → not validated, or vice versa).

Trivial changes (signal strength, link bandwidth jitter) are NOT meaningful changes for our purposes.

### Step 2 — Debounce 1-2 s

A short debounce (1500 ms is the architect's centre of the 1–2 s window) coalesces rapid bursts of callbacks during a single transition. Android emits a flurry of capability updates over ~500 ms when you walk into a cellular cell or out of Wi-Fi range; without debounce we would fire `NetworkChanged` 5–8 times for one real event.

Log on every accepted (post-debounce) change:

```
NETWORK_TRACE changed old=<wifi|cellular|vpn|none> new=<wifi|cellular|vpn|none> validated=<bool> vpnActive=<bool>
```

### Step 3 — On meaningful change, run the `TransportRewalkCoordinator` (lives outside HybridRelayTransport)

Introduce a named component **`TransportRewalkCoordinator`** that lives in `PhantomMessagingService` or `AppContainer` — whichever already holds references to `TransportManager` + `TransportPreferences` + the foreground-service connect-generation lifecycle. `HybridRelayTransport` is the WS+REST wrapper; it does NOT own the chain walker, the sticky-hint preferences, or the service lifecycle (verified 2026-05-28: zero references to `TransportManager` or `TransportPreferences` in `HybridRelayTransport.kt`).

`NetworkChangeObserver` fires the coordinator. The coordinator drives the rewalk:

1. **Rate-limit gate.** If `nowMs() - lastRewalkAtMs < NETWORK_REWALK_MIN_INTERVAL_MS` (5_000 ms), log `NETWORK_TRACE rate_limited reason=interval ageMs=<n>` and return. Otherwise continue.
2. **Log start.** `NETWORK_TRACE rewalk_start reason=<wifi_to_cellular|cellular_to_wifi|vpn_added|vpn_removed|validated_changed|network_lost|network_available>`.
3. **Clear sticky hint.** `TransportPreferences.lastWorkingTransport = null; lastSuccessAt = null`.
4. **Notify state machine.** Call `hybridTransport.submitNetworkChangedEvent()` — the single narrow method the Hybrid layer exposes for this flow (see Step 4). State machine logic at `RestStateMachine.kt:83/149/294` runs the existing `NetworkChanged` transition.
5. **Tear down current generation.** `hybridTransport.disconnect()`.
6. **Release outer subsystems.** `transportManager.release()`.
7. **Restart service connect generation.** Coordinator triggers a fresh foreground-service connect generation so `TransportManager.connect()` walks Standard as `[Direct, Reality, Tor]` against the new network.
8. **Log done.** `NETWORK_TRACE rewalk_done elapsedMs=<n>`.

All eight steps run in one coroutine block. The `lastRewalkAtMs` mark is updated on step 1 once we commit to running, so concurrent NetworkCallback fires inside the rate-limit window are dropped at step 1 with the `rate_limited` log line.

The constant `NETWORK_REWALK_MIN_INTERVAL_MS = 5_000L` lives in `RelayTransportConfig.kt` for visibility alongside the existing transport timers.

### Step 4 — Expose `submitNetworkChangedEvent()` on `HybridRelayTransport` (narrow contract)

`HybridRelayTransport` exposes exactly one new method, and only that:

```kotlin
fun submitNetworkChangedEvent() {
    submitStateEvent(RestStateMachine.Event.NetworkChanged)
}
```

**Deliberately narrow.** Hybrid does NOT clear preferences, does NOT call `disconnect()`, does NOT call `TransportManager.release()`, does NOT restart the connect generation. Those five actions belong to `TransportRewalkCoordinator` in Step 3 because that is where the lifecycle ownership sits. Hybrid only has the state-machine handle, so it only does the state-machine handoff.

This keeps the existing `RestStateMachine.Event.NetworkChanged` dispatch contract intact (the state machine logic already implements the right transitions per the existing docstring at `RestStateMachine.kt:32`/`:37`) without bloating Hybrid into a god-object that owns chain selection, preferences, and service lifecycle.

The coordinator in Step 3 calls this method as item #4 of the rewalk sequence. If a future refactor moves the state machine out of Hybrid, the same one-line method moves with it; the coordinator-side rewalk stays unchanged.

### Step 5 — LTE diagnostics hardening

Independent of the network-change rewire, the chain walk on cold-start LTE needs better attribution.

**Already present in master `3e73d776`** (verified 2026-05-28 via grep on `TransportManager.kt`):

```
PROBE_TRACE chain_start ordered=[Direct, Reality, Tor] vpnActive=<bool> realityFiltered=<bool>
```

`vpnActive` and `realityFiltered` are already logged in `chain_start` (lines 84/88 of `TransportManager.kt`). The earlier draft of this mini-lock was wrong to claim otherwise — verified against the actual source on 2026-05-28 by the external transport architect. Do NOT re-add what is already there.

**Remaining diagnostics gaps this PR closes:**

1. **Add direct-probe phase details to `AndroidNativeOkHttpDirectProbe`.** The Ktor-based Reality probe already has a phase listener; the OkHttp-based Direct probe does not. Mirror the same pattern: emit `PROBE_TRACE direct dns_start/done`, `tcp_connect_start/done`, `tls_start/done`, `request_start/done`, `failure errorClass=<...>`. Without these, "Direct probe failed on Tele2" leaves no signal of *which phase* failed (DNS / TCP / TLS / request / response).

2. **Log Reality filter reason explicitly.** When Reality is removed from the chain due to `vpnActive=true`, log:
   ```
   PROBE_TRACE reality_filtered reason=vpn_active
   ```
   So the chain ordering log alone is enough to explain why `[Direct, Tor]` was the actual probe order. The boolean `realityFiltered=true` already appears in `chain_start`, but a dedicated line with the reason makes the cause obvious (today the reason is always `vpn_active`; if more reasons emerge, the same line format extends).

## In scope (this PR only)

1. **`NetworkChangeObserver`** (new Android source) — owns the single `ConnectivityManager.NetworkCallback` registration, classifies meaningful changes (`transportType`, `vpnActive`, `validated`, `networkPresent`), debounces 1500 ms, fires `TransportRewalkCoordinator` on accepted change.
2. **`TransportRewalkCoordinator`** (new Android source) — lives in `PhantomMessagingService` or `AppContainer` (whichever already holds `TransportManager` + `TransportPreferences`). Owns the 8-step rewalk in Step 3 above. Rate-limits via `NETWORK_REWALK_MIN_INTERVAL_MS`.
3. `RelayTransportConfig.kt` — new constant `NETWORK_REWALK_MIN_INTERVAL_MS = 5_000L`.
4. `HybridRelayTransport.submitNetworkChangedEvent()` — narrow one-line method that submits `RestStateMachine.Event.NetworkChanged` and does nothing else. Hint-clear / `disconnect()` / `TransportManager.release()` / connect-generation restart all live in `TransportRewalkCoordinator`.
5. `TransportManager.kt` — add ONE new log line `PROBE_TRACE reality_filtered reason=vpn_active` when Reality is dropped from the chain (today the only filter reason). `vpnActive` + `realityFiltered` in `chain_start` are ALREADY logged at `TransportManager.kt:84` — do NOT re-add what's there.
6. `AndroidNativeOkHttpDirectProbe` — phase logging mirroring the Ktor probe (`dns_start/done`, `tcp_connect_start/done`, `tls_start/done`, `request_start/done`, `failure errorClass=...`).
7. **Structured log keys** (verified absent in master `3e73d776`, all to be added):
   - `NETWORK_TRACE changed old=<...> new=<...> validated=<bool> vpnActive=<bool>`
   - `NETWORK_TRACE rewalk_start reason=<wifi_to_cellular|cellular_to_wifi|vpn_added|vpn_removed|validated_changed|network_lost|network_available>`
   - `NETWORK_TRACE rate_limited reason=interval ageMs=<n>`
   - `NETWORK_TRACE rewalk_done elapsedMs=<n>`
   - `PROBE_TRACE reality_filtered reason=vpn_active`
   - `PROBE_TRACE direct <phase>_<start|done|fail> errorClass=<...>`
8. Unit tests in `shared/core/transport/src/commonTest` (commonTest if abstract enough, otherwise `androidTest`):
   - `network_change_triggers_chain_rewalk_and_clears_hint`.
   - `network_change_rate_limited_within_min_interval` — verifies the `rate_limited` log + no second rewalk inside the window.
   - `vpn_active_filters_reality_from_chain_and_logs_reason` — verifies `reality_filtered reason=vpn_active` fires when VPN is up.
   - `coordinator_does_not_call_disconnect_when_already_disconnected` — guards against double-disconnect on rapid sequential changes.
9. Android instrumented test (if feasible without real network swap; otherwise documented manual test plan in this file under Test #88).
10. Test #88 acceptance scenarios on Tecno real device with logcat capture (`PhantomMessaging:V PhantomTransport:V PhantomHybrid:V PhantomRelay:V TransportManager:V RestStateMachine:V *:S`).

## Out of scope (NOT in this PR)

- ❌ **Crypto / session repair.** Stays in `PR-CRYPTO-SESSION-REPAIR1` per the architect's explicit assumption. Two different bug classes; do not mix.
- ❌ **Relay / server changes.** This is a pure Android fix.
- ❌ **Standard mode chain order changes.** Direct → Reality → Tor stays. The fix is fresh re-evaluation and better LTE attribution, NOT forcing Direct when the carrier path is genuinely broken.
- ❌ **Tor as default.** The acceptance criterion explicitly allows Tor when Direct + Reality both fail with documented reasons.
- ❌ **iOS / desktop / web.** Android only.
- ❌ **REST poll behaviour changes.** This PR fixes when REST polling starts (after rewalk) but does not change *how* REST polling works.
- ❌ **"While we're here" cleanups** outside the five steps above.

## Anti-pattern signatures — verify NOT present in the diff

- ❌ `Event.NetworkChanged` submitted from any path *other* than the new NetworkChangeObserver. (One event source. If background code starts submitting it for unrelated reasons, the state machine becomes unobservable.)
- ❌ `NetworkChangeObserver` registered more than once per process. (Android leaks NetworkCallbacks easily; check single registration / single unregistration.)
- ❌ Direct call to `TransportManager.connect()` from the observer skipping `disconnect()` / `release()`. (Architect-explicit: full reset, then re-connect.)
- ❌ Removing Tor from the chain or reordering Standard chain. (Architect-explicit: chain order unchanged.)
- ❌ Catch-all `try { ... } catch (e: Exception) { log.warn(...) }` swallowing network-callback failures. (If the observer dies silently, the user has no way to know recovery is gone.)
- ❌ Hardcoded `vpnActive=false` or `realityFiltered=false` for logs. (Must reflect actual runtime state.)
- ❌ Any UI / state holder changes. Search the diff for `screens/` or `compose` edits; flag as out-of-scope.

## Test acceptance (Test #88)

All four scenarios on Tecno real device with full logcat capture (canonical tag set: `PhantomMessaging:V PhantomTransport:V PhantomHybrid:V PhantomRelay:V TransportManager:V RestStateMachine:V *:S`).

### Scenario 1 — Cold start on LTE, Wi-Fi disabled, VPN disabled

```
PROBE_TRACE chain_start ordered=[Direct, Reality, Tor] vpnActive=false realityFiltered=false
PROBE_TRACE direct dns_start ...
PROBE_TRACE direct tcp_connect_start ...
PROBE_TRACE direct {tls_start/request_start/failure errorClass=...}
PROBE_TRACE probe_returned kind=Direct ok=<true|false>
... (Reality if Direct false)
... (Tor if Reality false)
PROBE_TRACE chain_attempt_success kind=<...> socksPort=<...>
```

PASS = either Direct succeeds AND messages flow, OR Tor lands AND all earlier chain steps logged a documented failure reason (no `ok=false reason=null`).

### Scenario 2 — Wi-Fi → LTE while app already connected

1. Connect on Wi-Fi at home; verify `chain_attempt_success kind=Direct`.
2. Disable Wi-Fi (phone falls to LTE).
3. Within `~2 s` (debounce + meaningful-change classification), log must show in this order:
   ```
   NETWORK_TRACE changed old=wifi new=cellular validated=<bool> vpnActive=false
   NETWORK_TRACE rewalk_start reason=wifi_to_cellular
   ...
   NETWORK_TRACE rewalk_done elapsedMs=<n>
   ```
4. Old WS generation must disconnect (`WsSessionEnded` from the old connection).
5. **CRITICAL:** A new `PROBE_TRACE chain_start ordered=[Direct, Reality, Tor] vpnActive=<bool> realityFiltered=<bool>` line must appear AFTER the `NETWORK_TRACE` block. If `NETWORK_TRACE changed` is logged but no new `chain_start` follows, the fix is INCOMPLETE — the coordinator did not actually trigger `TransportManager.connect()` again. (Architect-explicit checkpoint 2026-05-28.)
6. PASS = the full sequence above appears + chain walk re-runs against the LTE network + sticky hint from Wi-Fi is cleared + LTE-appropriate chain outcome (per Scenario 1).

### Scenario 3 — LTE with VPN active

1. Enable a VPN, then connect on LTE.
2. Log must show:
   ```
   PROBE_TRACE chain_start ordered=[Direct, Tor] vpnActive=true realityFiltered=true
   PROBE_TRACE reality_filtered reason=vpn_active
   ```
3. Standard chain becomes `[Direct, Tor]` (Reality dropped).
4. PASS = the Tor jump is explainable from logs alone (no "Tor appeared, why?" mystery).

### Scenario 4 — Direct succeeds, WS inbound stalls

(Reuses the existing PR-RECV-DIAG1 v1.6 InboundIdleTimeout path; this PR must not regress it.)

1. Direct WS connects on LTE.
2. After ~60 s with no inbound frames, app must:
   - Show `Online via Direct · Limited realtime` in UI.
   - REST fallback must poll messages.
   - It must NOT silently relabel the session as `realtime healthy`.
3. PASS = UI label honest, REST polls fire, messages still arrive end-to-end.

## Assumptions (architect-confirmed)

- MAC-error / Double Ratchet repair is owned by `PR-CRYPTO-SESSION-REPAIR1` (separate, queued behind this PR).
- This PR does not change relay / server code.
- Standard mode policy remains `Direct → Reality → Tor`; the fix is fresh re-evaluation + better LTE attribution, not forcing Direct when the carrier path is genuinely broken.

## Parking conditions

Stay in track if:
- Debounce window needs adjustment based on Test #88 timing — change within the PR (1–2 s window already approved).
- The rewalk coordinator lives on `PhantomMessagingService` rather than `AppContainer` (or vice versa) — minor refactor decision within the PR, no scope change. Constraint: the coordinator MUST hold the `TransportManager` + `TransportPreferences` references, so the choice is between the two existing components that already do.
- Direct probe phase log field names need refinement — change within the PR.

Escalate to a NEW track only if:
- `ConnectivityManager.NetworkCallback` proves unreliable on Tecno HiOS (OEM-specific defects) → separate diagnostic PR with WorkManager-based fallback design.
- Test #88 Scenario 2 reveals that the rewalk *itself* corrupts in-flight messages — STOP, redesign the disconnect/release ordering.
- Tele2 LTE behaviour cannot be made attributable with the proposed logs (e.g. Direct probe phases all complete but the *connection* still silently dies post-handshake) → separate diagnostic PR; this one ships with whatever attribution is achievable.

## Transport regression gate (applies to this PR)

Per `docs/WORKING_RULES.md` rule 8 (added in the same docs PR as this mini-lock): because this PR changes chain selection + reconnect lifecycle + network-change handling + probe diagnostics, **a Tecno Tele2 LTE smoke test (Test #88) is required before merge**. Wi-Fi-only PASS is not sufficient — Wi-Fi cannot prove the network-change rewalk works because Wi-Fi never falls through to LTE in a development build.

## Last hand-off

(empty — track queued, awaiting Vladislav greenlight on this mini-lock + green CI on the prep docs PR before code begins)
