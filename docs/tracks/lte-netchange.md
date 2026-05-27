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

### Step 3 — On meaningful change, do the rewalk

In order, all in the same coroutine block:

1. Clear `TransportPreferences.lastWorkingTransport` and `lastSuccessAt` (sticky hint reset).
2. `transport.disconnect()` — tears down the current WS generation.
3. `transportManager.release()` — releases the cached probe/select state.
4. Restart one clean foreground-service connect generation so `TransportManager.connect()` walks Standard as `[Direct, Reality, Tor]` fresh against the new network.

Rate-limit: minimum interval between two consecutive rewalk attempts (e.g. `NETWORK_REWALK_MIN_INTERVAL_MS = 5_000L`) so a chronically-flapping interface doesn't loop the chain walker.

### Step 4 — Expose `notifyNetworkChanged()` on `HybridRelayTransport`

The NetworkCallback owner (service / container) does NOT directly clear preferences or call `disconnect()` — it submits an event. Add a small app-layer method:

```kotlin
fun notifyNetworkChanged(reason: NetworkChangeReason) {
    submitStateEvent(RestStateMachine.Event.NetworkChanged)
    // additional Hybrid-layer cleanup: clear hint, disconnect, release
}
```

This keeps the existing `RestStateMachine.Event.NetworkChanged` dispatch contract intact (the state machine logic already implements the right transitions per the existing docstring at `RestStateMachine.kt:32`/`:37`) and lets the actual wiring live in the Android-only layer.

### Step 5 — LTE diagnostics hardening

Independent of the network-change rewire, the chain walk on cold-start LTE needs better attribution:

1. **Log the VPN-detector input.** `TransportManager.connect()` consults the VPN detector to decide whether to filter Reality out of the chain. Log:
   ```
   PROBE_TRACE chain_start ordered=[Direct, Reality, Tor] vpnActive=<bool> realityFiltered=<bool>
   ```
   The current log shows `ordered=[...]` but not whether the order *was modified* due to VPN. After this PR, the answer to "did Reality fail or was it filtered out?" is one logcat line.

2. **Add direct-probe phase details to `AndroidNativeOkHttpDirectProbe`.** The Ktor-based Reality probe already has a phase listener; the OkHttp-based Direct probe does not. Mirror the same pattern: emit `PROBE_TRACE direct dns_start/done`, `tcp_connect_start/done`, `tls_start/done`, `request_start/done`, `failure errorClass=<...>`. Without these, "Direct probe failed on Tele2" leaves no signal of *which phase* failed (DNS / TCP / TLS / request / response).

3. **Log Reality filter reason explicitly.** When Reality is removed from the chain due to `vpnActive=true`, log:
   ```
   PROBE_TRACE reality_filtered reason=vpn_active
   ```
   So the chain ordering log alone is enough to explain why `[Direct, Tor]` was the actual probe order.

## In scope (this PR only)

1. New Android source: `NetworkChangeObserver.kt` (or extension on `AppContainer` / `PhantomMessagingService`) — `ConnectivityManager.NetworkCallback` registration + meaningful-change classification + debounce.
2. New constant `NETWORK_REWALK_MIN_INTERVAL_MS` in `RelayTransportConfig.kt`.
3. New method `HybridRelayTransport.notifyNetworkChanged(reason: NetworkChangeReason)` (or equivalent) — clears hint, calls `disconnect()` + `release()`, restarts the connect generation, submits `Event.NetworkChanged` to the state machine.
4. `TransportManager.kt` — extra log lines: `vpnActive`, `realityFiltered`, `reality_filtered reason=...`.
5. `AndroidNativeOkHttpDirectProbe` — phase logging mirroring the Ktor probe.
6. Unit tests in `shared/core/transport/src/commonTest`:
   - `network_change_triggers_chain_rewalk_and_clears_hint`.
   - `network_change_rate_limited_within_min_interval`.
   - `vpn_active_filters_reality_from_chain_and_logs_reason`.
7. Android integration test (if feasible without real network swap; otherwise documented manual test plan).
8. Test #88 acceptance scenarios on Tecno real device with logcat capture.

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
3. Within `~2 s` (debounce + meaningful-change classification), log must show:
   ```
   NETWORK_TRACE changed old=wifi new=cellular validated=<bool> vpnActive=false
   ```
4. Old WS generation must disconnect (`WsSessionEnded` from the old connection).
5. `TransportManager.connect()` must run again with fresh chain walk on the LTE network.
6. PASS = chain walk re-runs; sticky hint from Wi-Fi is cleared; LTE-appropriate chain outcome (per Scenario 1).

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
- `notifyNetworkChanged()` ends up as an extension on `AppContainer` rather than a method on `HybridRelayTransport` — minor refactor decision within the PR, no scope change.
- Direct probe phase log field names need refinement — change within the PR.

Escalate to a NEW track only if:
- `ConnectivityManager.NetworkCallback` proves unreliable on Tecno HiOS (OEM-specific defects) → separate diagnostic PR with WorkManager-based fallback design.
- Test #88 Scenario 2 reveals that the rewalk *itself* corrupts in-flight messages — STOP, redesign the disconnect/release ordering.
- Tele2 LTE behaviour cannot be made attributable with the proposed logs (e.g. Direct probe phases all complete but the *connection* still silently dies post-handshake) → separate diagnostic PR; this one ships with whatever attribution is achievable.

## Transport regression gate (applies to this PR)

Per `docs/WORKING_RULES.md` rule 8 (added in the same docs PR as this mini-lock): because this PR changes chain selection + reconnect lifecycle + network-change handling + probe diagnostics, **a Tecno Tele2 LTE smoke test (Test #88) is required before merge**. Wi-Fi-only PASS is not sufficient — Wi-Fi cannot prove the network-change rewalk works because Wi-Fi never falls through to LTE in a development build.

## Last hand-off

(empty — track queued, awaiting Vladislav greenlight on this mini-lock + green CI on the prep docs PR before code begins)
