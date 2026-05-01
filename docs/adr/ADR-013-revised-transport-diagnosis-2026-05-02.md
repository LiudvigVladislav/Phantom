# ADR-013: Revised Transport Diagnosis — 2026-05-02

Status: proposed
Date: 2026-05-02
Layer: core/transport (shared), app/service (android), services/relay

---

## Plain-Language Verdict

The battery-management setting did not change behaviour because the primary problem is
probably NOT battery management. The phone's WebSocket appears to be breaking in a way
that `forceReconnect()` is being called over and over (every 30 seconds from the alarm)
but the reconnect loop is stuck and never actually opens a new socket. Separately, there
is strong evidence that the relay's "one active connection per identity" rule causes a
race where rapid restarts register a new connection, orphan the old one, and messages
sent to the orphaned slot are silently dropped at a critical moment. These two problems
look the same from the outside — messages stop arriving — but have different root causes
and different fixes.

**Most likely explanation for the phone log (12:40–12:50):** `forceReconnect()` is
calling `scope?.cancel()` on a scope that is no longer the live generation's scope. The
phone connected once at 12:40:44. The `scope` field on `KtorRelayTransport` is set to
`transportScope` inside the `webSocket{}` block, but `forceReconnect()` reads the field
as `scope?.cancel()`. If the `webSocket{}` block never exited after the initial connect,
`scope` is still the live `transportScope` — which means cancelling it should work. But
the log shows `connectionPool 1 → 1` (the socket count did not drop), which is the same
signature as ADR-011 Step 6: `forceShutdownActiveEngine()` ran, called
`executor.shutdownNow()`, and the socket survived anyway. The alarm fires every 30
seconds, calls `forceReconnect()` each time, each time the shutdown is a no-op, and
no "Attempting WebSocket connect" line appears because the reconnect loop is still
parked inside `webSocket{}` waiting for the read loop to exit.

**Most likely explanation for the emulator log (17:41–17:50):** The "Connection reset"
entries appearing at shrinking intervals after user restarts are rate-limiting by
something between the emulator and the relay (host NAT or relay's OkHttp equivalent —
not our relay, which has no connection-rate limit, but Caddy or the OS TCP stack may
reset rapid reconnects). The initial 5-minute clean run before the first user restart
is important: it shows the relay and emulator are fundamentally healthy. The breakage
began with user-initiated restarts, not organic degradation.

---

## Hypothesis Evaluation

### H1 — Relay drops envelopes for offline recipients silently

**Verdict: NOT the primary cause for the specific send at 12:46, but a possible
contributing factor deserving verification.**

Reading `routes.rs` lines 343–397: the relay persists the envelope to the in-memory
store BEFORE attempting live delivery. If the recipient is offline, the relay logs
"recipient offline — queued for next reconnect" and the envelope waits. If the
recipient is online, the relay pushes through the mpsc channel and the envelope is
also retained until the recipient sends `{"type":"ack-deliver"}`.

However: the relay registers a client keyed by `identity` (the public key hex passed
as `?id=`). When the emulator restarts rapidly, `handle_socket` runs cleanup
(`routes.rs` lines 215–230) with a `conn_id` guard: only removes the old entry if
`conn_id` still matches. This means if the new connection registers BEFORE the old
`handle_socket` cleanup runs, the old cleanup is a no-op and the new connection's
entry is in place. This part is correct.

The real risk is a narrow window: the phone sends at 12:46:02. At that moment, the
emulator has been restarting. If the emulator's last restart was mid-way through
handle_socket teardown (socket closed, but `clients.remove` not yet called), the
recipient entry in `state.clients` is momentarily absent. The relay would log
"recipient offline — queued", envelope goes to store. On the emulator's next connect,
the flush path (routes.rs lines 155–175) would redeliver it. The relay's flush is
correct — it reads from the store on every connect, sends everything unacknowledged.
The question is whether the emulator's "Received envelope" log was searched. If the
emulator never showed "Received envelope" for that message ID, the most likely
explanations are: (a) emulator was not connected at the time the relay attempted
live delivery AND the store-flush on subsequent reconnect also failed because the
emulator was cycling too fast for the flush to complete before the next restart,
or (b) the emulator process was fully offline (not just reconnecting) when the relay
tried to flush.

H1 is ruled out as a relay-side BUG — the code correctly stores and re-delivers.
H1 is kept open as a TIMING SCENARIO that needs the 2-emulator test to confirm.

### H2 — Phone's WebSocket died silently before the visible alarm fires

**Verdict: Consistent with the evidence; the alarm-driven `forceReconnect` may itself
be a no-op because the socket survived all shutdown attempts.**

The pong watchdog inside `startPing` checks elapsed since `lastPongMark` at every
`PING_INTERVAL_MS` (15 seconds). If a pong arrived at 12:40:44 (connect time), the
watchdog would have set `lastPongMark = timeSource.markNow()` once. The `PONG_TIMEOUT_MS`
is 25 seconds. The first watchdog check that exceeds 25 seconds of silence would call
`forceShutdownActiveEngine()` + `generationClient.close()` + `scope.cancel()`. This
should appear in logcat as "Pong timeout — shutting down active engine". That log line
is absent from the provided excerpt. Two interpretations:

  (a) The relay was successfully responding to pings (OkHttp-level WebSocket pings,
      not the app-level `{"type":"ping"}` frames) in the first 2 minutes, keeping the
      OkHttp layer alive, while the app-level Pong frames were being silently dropped
      (e.g. the relay's response travelled through the TCP stack but was not read by
      the Ktor incoming channel). In that case `lastPongMark` would be stale, pong
      watchdog would trip, forceShutdownActiveEngine would run, and we'd see the
      "connectionPool 1 → 1" pattern from ADR-011 Step 6 — socket survived.

  (b) The pong watchdog fired but its log line was filtered out because the user was
      not capturing `PhantomRelay:W`. This is possible since `PhantomWakeup:V` was
      also missing.

H2 is plausible and merges into the H3 analysis below.

### H3 — `scope?.cancel()` does not interrupt the suspended `webSocket{}` block

**Verdict: THIS IS THE MOST LIKELY EXPLANATION for the repeating alarm pattern.**

In `KtorRelayTransport.forceReconnect()` (line 589):

```kotlin
scope?.cancel()
forceShutdownActiveEngine()
runCatching { currentGenerationClient?.close() }
```

`scope` is set to `transportScope` at line 182 inside the `webSocket{}` block. After
`forceShutdownActiveEngine()` fires `executor.shutdownNow()` and `evictAll()` (confirmed
no-op against active connections per ADR-011 Step 6), the OkHttp reader thread is still
parked in `recv()`. Cancelling `scope` propagates a `CancellationException` into the
`transportScope` children (pingJob, ackWatchdog), but the `webSocket{}` block itself is
a suspension point in Ktor that waits for the OkHttp reader to exit before returning.
That reader is parked in a non-cancellable kernel syscall. So:

- `scope.cancel()` terminates pingJob and ackWatchdog.
- `scope.cancel()` does NOT unblock `readLoop()`'s `for (frame in incoming)` loop,
  because the Ktor incoming channel is fed by the OkHttp reader, which is blocked in
  `recv()`.
- The `webSocket{}` block does not exit.
- `runReconnectLoop` does not advance to its next iteration.
- No "Attempting WebSocket connect" log appears.
- The alarm fires again 30 seconds later, calls `forceReconnect()` again, same result.

This is exactly the pattern observed: `forceReconnect` logs every 30 seconds, no
subsequent connect attempt, no webSocket block exit. The phone is trapped in the same
generation indefinitely.

`currentGenerationClient?.close()` at the end of `forceReconnect()` calls
`HttpClient.close()`, which calls OkHttp's `ExecutorService.shutdown()` — the graceful
shutdown confirmed in ADR-011 Step 5 to also not interrupt `recv()`. So `forceReconnect`
as currently coded is, on Tecno HiOS with a parked radio, a complete no-op for the
purpose of actually forcing a new connection.

The fix described in ADR-010's second addendum (`executor.shutdownNow()` via
`forceShutdownActiveEngine()`) was incorporated into the current code (RelayTransportFactory.kt
line 67), but ADR-011 Step 6 confirmed that `shutdownNow()` also does not interrupt
`recv()` on a parked-radio socket on HiOS. The socket survives ALL three calls.

**The alarm fire only helps if the radio is awake when the alarm fires.** The original
rationale for the AlarmManager approach (ADR-011) was that the alarm fire itself wakes
the radio, and once the radio is awake, a fresh `httpClientFactory()` call opens a new
socket quickly. But `forceReconnect()` does NOT call `httpClientFactory()` — it cancels
the scope and waits for `runReconnectLoop` to call the factory on the next iteration.
If the scope cancellation propagates but the `webSocket{}` block is still stuck, the
factory is never called. The alarm fires but delivers no new connection.

### H4 — Home router or carrier NAT timeout

**Verdict: Possible contributing factor on the phone; not the primary cause on the
emulator.**

If the user's router has a stateful NAT entry timeout shorter than the ping interval
(15 seconds), TCP packets from the relay to the phone would be silently dropped at
the router after the entry expires. The relay would continue to accept the connection
as live (its own side of the socket is open), and our pong watchdog would detect
silence and fire — but then be unable to close the socket (H3). This scenario would
produce exactly the observed pattern: phone appears connected to relay, relay logs
no error, but pong never arrives after the initial seconds.

However, the emulator is on the same host machine as the relay in these runs (or on
the same LAN), so NAT expiry does not explain the emulator's Connection reset entries.
H4 is a plausible contributing cause specifically for the Tecno, not a shared root cause.

### H5 — Foreground service killed by a non-battery HiOS policy

**Verdict: Cannot be ruled out; secondary investigation after H3 is resolved.**

The user's "unrestricted" battery panel applies to the app's background battery usage
scheduling. HiOS has at least two other mechanisms that can kill a foreground service
independently:

1. "Auto-launch" permission — governs whether the app can start itself. If disabled,
   services are stopped when the screen turns off, regardless of battery setting.
2. "Lock screen" or "Float window" allowlist — on some HiOS builds, only allowlisted
   apps' services survive when the lock screen is active.
3. Per-app memory management — HiOS may use `SIGKILL` (not a graceful stop) when
   RAM is constrained. `SIGKILL` prevents `onDestroy` from running, so the alarm is
   never cancelled, but the alarm fires into a dead process and launches a fresh one
   with no connection state.

If the service were being killed, we would expect to see `PhantomMessagingService`
`onDestroy` followed by a fresh `onStartCommand`. The 12:40:44 log shows "connected"
with no preceding service-start log, suggesting the service was alive at 12:40. The
absence of a service-restart log between 12:40 and 12:50 argues against H5 as the
cause of the specific session. However, H5 may explain OTHER sessions where the
problem appears from a cold state.

---

## What We Should NOT Change in Code Until Diagnostics Run

1. Do not modify the relay. Routes.rs has correct store-and-forward semantics (H1
   is not a relay code bug). Changing the relay before confirming whether the phone
   even reaches the relay would introduce variables.

2. Do not change `PONG_TIMEOUT_MS`, `PING_INTERVAL_MS`, or `ACK_TIMEOUT_MS`. These
   are detection thresholds; the problem is in the action taken after detection, not
   the detection timing.

3. Do not change the alarm interval. The alarm fires correctly per the logs. The
   problem is what happens after the alarm fires — `forceReconnect` is a no-op.

4. Do not change `PhantomWakeupReceiver` logic. The logic (check pong staleness,
   call forceReconnect) is correct in intent. The problem is that `forceReconnect`
   cannot break through the kernel `recv()` barrier.

---

## What We Could Change in Code Regardless of Diagnostic Outcome

These changes add observability and are safe regardless of which hypothesis is correct.
They do not modify transport behaviour.

**Change 1: Log when `forceReconnect` is called but `currentGenerationClient` is null.**

Currently `forceReconnect()` calls `runCatching { currentGenerationClient?.close() }`.
If the client is null, the call is a silent no-op. Add:

```kotlin
if (currentGenerationClient == null) {
    relayLog(RelayLogLevel.WARN,
        "forceReconnect(): currentGenerationClient is null — loop may already be idle or stuck")
}
```

This will tell us immediately whether the generation client survived each alarm fire.

**Change 2: Log relay-side envelope routing decisions with identity prefix.**

In `handle_message` in routes.rs, the "recipient offline — queued" log already exists
(line 379–384). The "live delivery dispatched" log also exists (line 377). However,
neither log includes the first 8 characters of the recipient key (stripped for
sealed-sender privacy). For debugging store-forward timing, adding
`recipient_prefix = %&to[..to.len().min(8)]` to both log events would let us
cross-reference relay logs against client logs without revealing full keys.
This is a logging change only, no relay behaviour changes.

**Change 3: Add `PhantomWakeup:V` to the logcat filter for the next diagnostic session.**

This is not a code change — it is an instruction for the next test run. The receiver's
`TAG = "PhantomWakeup"` means all receiver logs are filtered out unless the user
captures `PhantomWakeup:V` or `PhantomWakeup:W`. Without it we cannot see whether the
alarm fired, whether pong was read as fresh or stale, or whether `forceReconnect` was
called. Every diagnostic run going forward must include this tag.

---

## Diagnostic Steps

### Step 1: Logcat filters to add (change nothing else)

Run the next test with this filter:
```
adb logcat PhantomRelay:V PhantomWakeup:V PhantomMessaging:V *:S
```

The `PhantomWakeup:V` tag was missing from all logs so far. It will show:
- "pong fresh — no action" when the alarm fires and the pong is recent
- "stale pong — forcing reconnect" when the alarm actually triggered forceReconnect
- The elapsed pong value at the time of each alarm fire

### Step 2: 2-emulator test plan (no Tecno required for this step)

Goal: confirm whether store-and-forward works correctly across rapid restarts, ruling
out H1 as a relay bug.

Setup: two emulators (A = sender, B = receiver) both running on the same machine.

1. Start both emulators. Confirm both show "WebSocket connected" in logcat.
2. On emulator B: force-stop PHANTOM (Settings > Apps > Force Stop).
3. Wait 15 seconds (B is now offline; relay should log "recipient offline — queued"
   for any message sent to B's identity).
4. On emulator A: send a message to B.
5. Confirm relay log shows "recipient offline — queued for next reconnect" with B's
   message ID.
6. Restart PHANTOM on emulator B.
7. Confirm relay log shows "flushing queued envelopes" for B's identity on reconnect.
8. Confirm emulator B logcat shows "Received envelope: id=<same id>".
9. Confirm emulator B shows the message in the UI.

If step 8 or 9 fails, H1 is confirmed as a real problem (store-forward broken).
If they succeed, H1 is ruled out and the focus shifts to the phone-side H3.

Variant: repeat the above but force-stop B and immediately restart it 5 times in
10 seconds (simulating the rapid restart pattern in the emulator log). Confirm the
relay's conn_id guard (routes.rs lines 215–230) correctly handles the race and the
message is delivered on the final stable connection.

### Step 3: Phone-specific forceReconnect stuck test

Goal: confirm H3 (scope cancel does not break out of webSocket block on parked radio).

On the Tecno:

1. Start PHANTOM. Let it connect (screen stays on for 30 seconds to confirm "WebSocket
   connected successfully" appears).
2. Turn screen off. Wait 3 minutes.
3. Turn screen back on.
4. Pull logcat immediately with filter `PhantomRelay:V PhantomWakeup:V *:S`.

Expected if H3 is the cause:
- "forceReconnect() called" appears every ~30 seconds (alarm firing)
- "webSocket{} block exited — entering finally block" NEVER appears between those lines
- "Attempting WebSocket connect" NEVER appears after the first connection
- "connectionPool 1 → 1" appears (socket survived shutdown attempts)

Expected if H5 (service kill) is the cause instead:
- A gap with NO logs at all (process was dead)
- Followed by fresh "WebSocket connected successfully" (service restarted by alarm)

The presence or absence of the "finally block" log is the definitive discriminator
between H3 (socket stuck) and H5 (service killed).

### Step 4: Relay log capture

SSH into relay.phntm.pro. Run:
```
docker logs phantom-relay --since 30m --follow 2>&1 | grep -E "(event|msg_id|connect|queued|flush)"
```

While the phone is doing its test session (Step 3). This tells us:
- Whether the relay ever sees the phone disconnect and reconnect
- Whether sent envelopes are marked "recipient offline" or "live delivery dispatched"
- Whether the emulator's reconnects are visible as connect/disconnect events

---

## Revised Root Cause Summary

| Hypothesis | Verdict | Evidence |
|---|---|---|
| H1 Relay silent drop | Not a code bug; possible timing scenario | Relay code is correct; needs 2-emulator timing test |
| H2 WebSocket died silently | Partially confirmed | "Pong timeout" log line absent; pong watchdog may have fired silently |
| H3 scope.cancel() no-op | Most likely primary cause | Log pattern: forceReconnect every 30s, no subsequent connect, connectionPool 1→1 |
| H4 NAT timeout | Possible phone-specific contributor | Does not explain emulator resets |
| H5 Service killed by HiOS | Cannot rule out | Need "finally block" log presence/absence as discriminator |

The ADR-011 fix (AlarmManager wakeup) was sound in architecture but is currently
undermined by `forceReconnect()` being unable to break the kernel `recv()` barrier.
The alarm fires, the radio wakes, but the old generation's socket is still alive and
blocking the reconnect loop. The fix must make `forceReconnect()` capable of
bypassing the stuck generation entirely — most likely by having it directly launch a
new `runReconnectLoop()` iteration rather than trying to close the old one gracefully.
That code change should not happen until Step 3 above confirms H3 is the actual cause,
because if H5 is the real cause the code change would be in the wrong place.

---

## References

- ADR-010: Transport Reconnect Deadlock
  `docs/adr/ADR-010-transport-reconnect-deadlock.md`
- ADR-011: AlarmManager-Driven Periodic Network Wakeup
  `docs/adr/ADR-011-alarm-manager-network-wakeup.md`
- Relay source: `services/relay/src/routes.rs` — handle_socket, handle_message (store-forward)
- Transport source: `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/KtorRelayTransport.kt`
- Receiver source: `apps/android/src/androidMain/kotlin/phantom/android/service/PhantomWakeupReceiver.kt`
- Engine shutdown: `shared/core/transport/src/androidMain/kotlin/phantom/core/transport/RelayTransportFactory.kt`
