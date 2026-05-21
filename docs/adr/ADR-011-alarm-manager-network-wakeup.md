# ADR-011: AlarmManager-Driven Periodic Network Wakeup

Status: **Accepted** (implemented and refined through PR-H1c → PR-H1e Run C)
Original date: 2026-05-01
Acceptance date: 2026-05-13 (PR-H1c shipped the AlarmManager wakeup as part of the six-layer stale-socket recovery)
Refined: 2026-05-14 (PR-H1e Run C locked the final cadence after a four-run heartbeat experiment)
Layer: app/service (android)

---

## Status note (2026-05-21)

This ADR landed in production through the H1 reliability sprint. The decision section below is preserved as originally drafted; this status note records what shipped and where.

**Implementation references:**

- **PR-H1c (#132, master `e946caba`, 2026-05-13)** — shipped the six-layer stale-socket recovery that includes the AlarmManager wakeup as designed in this ADR. The receiver path checks `lastInboundFrameElapsedMs` (broader than the original `lastPongMark` — see Decision §5 below) and calls `forceReconnect()` when the inbound channel is stale. **Test #37** verified detection time 155 s → 30–46 s, recovery 5 s → ~1 s, zero message loss across twelve consecutive reconnect cycles per device on Tecno МТС + emulator.
- **PR-H1e (#134, master `bcc501be`, 2026-05-14)** — locked the final production cadence after a four-run heartbeat experiment on `diag/h1e-ws-ping-experiments`. The locked Run C policy is:
  - `APP_LEVEL_PING_ENABLED=false` — the redundant app-level RelayMessage Ping/Pong layer is disabled; OkHttp WS Ping is the sole client-side liveness check.
  - OkHttp WS `pingInterval(15s)` hard-coded.
  - AlarmManager proactive reconnect at 45 s of stale inbound (below the 60 s pong-watchdog floor in §122 of the original decision) — survives Doze and OEM AlarmManager throttling as a safety net.
  - Dead-socket watchdog continues ticking on `PING_INTERVAL_MS`.

**Acceptance criteria (from §397 below) — verification status:**

1. **3-minute screen-off Tecno HiOS delivery within 90 s** — verified by Test #37 / Test #41. Tecno is now Wi-Fi-only since 2026-05-14 (no SIM), so the OEM-radio-park leg of the original criterion is no longer reproducible on the current hardware; the half-open TCP middlebox class (which the same fix addresses) IS reproduced on a daily basis on Tele2 LTE.
2. **`adb shell dumpsys alarm | grep phantom` shows the `PhantomWakeupReceiver` entry repeating at ~60 s** — confirmed in PR-H1c diagnostic logs.
3. **`USE_EXACT_ALARM` revoke fallback** — implemented per Risks R1; verified in the AppContainer / `PhantomMessagingService.kt` wakeup path.
4. **60-minute Doze soak with ≤ 3 % battery drain** — not formally measured. Field-tested on multiple device sessions without complaints; battery impact has not regressed against the Briar / Element reference numbers cited in §299.
5. **Receiver logs "pong fresh — no action" vs "stale pong — forcing reconnect"** — both log lines present in production logcat after H1c. Note that under Run C the trigger is `lastInboundFrameElapsedMs`, not strictly pong age, so a more general phrasing is used in production code.

**Remaining open question.** OQ-2 (adaptive interval — fire more often during active conversations, less often when idle for hours) is **still open** and tracked in `docs/PROJECT_LOG.md → Open follow-ups`. The locked 45 s / 60 s cadence is the production answer until that follow-up is picked up; no urgency given current battery field data.

The Decision section below is preserved as-written for archival reasons. Read the Status note above for what's actually true today.

---

---

## Context

Alpha-1 QA on a Tecno Spark Go 2023 running HiOS produced reliable message delivery failures
whenever the device had been idle for 60–120 seconds with the screen off. ADR-010 and four
subsequent APK iterations (APK 11 through APK 14) exhausted every user-space approach to
keeping the WebSocket alive. This ADR records the diagnosis that proves user-space is
insufficient and adopts a OS-level wakeup mechanism as the correct structural fix.

For readers less familiar with the technical background: the root cause is that the phone's
operating system cuts power to the Wi-Fi radio when it thinks the screen is not in use, even
when PHANTOM's foreground service is running. Our code cannot override this from inside the
app. The fix in this ADR asks the OS itself to wake the app on a regular schedule, at which
point the radio is powered and a fresh network connection can be opened.

---

## Diagnosis

### Step 1 — HiOS ignores WIFI_MODE_FULL_HIGH_PERF

`PhantomMessagingService.acquireKeepAliveLocks()` requests `WifiManager.WIFI_MODE_FULL_HIGH_PERF`
(type 4). `adb shell dumpsys wifi` on the Tecno device after lock acquisition shows `type=3`
(`WIFI_MODE_FULL`), not `type=4`. HiOS silently downgrades the lock type and then parks the
Wi-Fi radio under its own battery management policy regardless.

Log evidence (APK 14, 2026-05-01 QA session):

```
WifiLock acquired (FULL_HIGH_PERF)
[adb shell dumpsys wifi]: WifiLock ... type=3  uid=... tag=phantom:wifi
```

### Step 2 — WebSocket reader thread parks in kernel recv()

Once the radio parks, the TCP socket that carries the WebSocket becomes silent. OkHttp's reader
thread is blocked in a Linux `recv()` syscall on that socket. The `recv()` will not return until
either the kernel read timeout fires or the socket's file descriptor is closed. No user-space
call can interrupt a thread parked inside a kernel syscall before that.

Log evidence: 3+ minute gaps between the last `Pong` log line and the next reconnect attempt in
every APK 12–14 run, regardless of what the pong watchdog did.

### Step 3 — dispatcher.cancelAll() is a no-op post-upgrade

Once the WebSocket 101 handshake completes, OkHttp removes the Call from
`dispatcher.runningCalls()`. `dispatcher.cancelAll()` finds nothing and returns without
touching the reader thread. Confirmed in APK 11 logs: the cancel call completes immediately
but the read loop stays parked.

### Step 4 — connectionPool.evictAll() skips active connections

ADR-010 recommended `connectionPool.evictAll()` as the fix. APK 12 proved this wrong:

```
forceCancelAllEngineCalls: pool connections before=2 (idle=1) → after=1 — evictAll() invoked
[3+ minutes of silence]
```

`evictAll()` closed the one idle REST connection and left the active WebSocket connection
untouched. OkHttp's pool contract: leased (active) connections are not evicted.

### Step 5 — HttpClient.close() via executor.shutdown() does not interrupt recv()

APK 13 adopted per-generation `HttpClient` creation (the ADR-010 Addendum recommendation).
`HttpClient.close()` calls OkHttp's `ExecutorService.shutdown()` — a graceful shutdown that
waits for running tasks to finish. A thread parked in kernel `recv()` is a running task. It
does not finish until the syscall returns. The `close()` call blocks alongside it.

### Step 6 — executor.shutdownNow() sends InterruptedException but recv() ignores it

APK 14 added `forceShutdownActiveEngine()` which calls `executor.shutdownNow()`. This sends
`Thread.interrupt()` to parked threads. However, Linux kernel `recv()` is not an interruptible
wait point in the Java sense: `Thread.interrupt()` sets the interrupted flag but the kernel
does not honour it until `recv()` returns on its own. APK 14 log after both `shutdownNow` and
`evictAll`:

```
connectionPool 1 → 1
```

The connection count did not change. The socket survived both calls.

### Conclusion

The Tecno HiOS Wi-Fi radio park is a kernel/firmware boundary. Every approach attempted in
APK 11–14 operates inside the Linux user-space process. Once the radio is parked and the
kernel's `recv()` is blocking, no user-space API — not OkHttp dispatcher, not connection pool,
not coroutine cancellation, not thread interrupt — can unblock it. The radio resumes only when
the OS decides to resume it: on screen-on, on an FCM high-priority push, or when an
`AlarmManager.setExactAndAllowWhileIdle()` alarm fires. This is a Linux/Android kernel
limitation, not a PHANTOM code bug. User-space fixes are exhausted.

---

## Decision

Adopt periodic OS-level network wakeup via `AlarmManager.setExactAndAllowWhileIdle()`.

The alarm fires every 60 seconds. On each fire:

1. Read `lastPongMark` from `KtorRelayTransport` (the timestamp of the last pong frame
   received from the relay).
2. If `lastPongMark` is fresh (elapsed < 30 seconds), the connection is healthy. No action.
3. If `lastPongMark` is stale (elapsed >= 30 seconds), or if the transport `state` is not
   `Connected`, force a reconnect:
   a. Cancel the current `runReconnectLoop` coroutine scope.
   b. Call `transport.disconnect()`.
   c. Call `transport.connect(...)` with the saved credentials.
   d. The new `connect()` call invokes `httpClientFactory()` for a fresh `HttpClient`. Because
      the alarm fire woke the device, the radio is powered, and the new socket establishes
      immediately.

The 60-second alarm interval is the minimum allowed by `setExactAndAllowWhileIdle()` under
Android Doze. It aligns with the delivery latency expectation for an Alpha messenger. A
future "energy-saver" mode that backs off to 5-minute intervals when the user explicitly
enables battery saving is deferred to Alpha-2.

### Alternative considered and rejected: FCM silent push

A Firebase Cloud Messaging silent push sent by the relay on incoming message would achieve the
same radio wakeup without a recurring alarm. It was rejected for Alpha-1 for three reasons:

1. It requires relay-side implementation: a persistent FCM credential, a device token
   registration endpoint, and logic to send a silent push whenever a message is queued for an
   offline recipient. This is non-trivial relay work that is not in Alpha-1 scope.
2. It requires FCM credential rotation infrastructure and `google-services.json` integration
   that ties every build to a Google project. This creates a barrier for FOSS builds and
   GrapheneOS users who do not have Google Play Services.
3. The FOSS replacement path (UnifiedPush) is on the roadmap but not yet designed.

FCM/UnifiedPush will replace the periodic alarm in Alpha-2 or Beta. The alarm mechanism
serves as the correct Alpha-1 stop-gap.

---

## Implementation Scope

**Layer being changed:** app/service (android). No changes to shared core modules.

### Files affected

**1. `apps/android/src/androidMain/AndroidManifest.xml`**

Add the following permission and receiver declarations:

```xml
<!-- Required for setExactAndAllowWhileIdle() on Android 12 and below. -->
<!-- On Android 13+ (API 33), USE_EXACT_ALARM must also be declared.   -->
<!-- On Android 14+ (API 34), USE_EXACT_ALARM is the primary grant;    -->
<!-- SCHEDULE_EXACT_ALARM requires user confirmation in Settings.       -->
<!-- Messenger apps qualify for USE_EXACT_ALARM under Play Console      -->
<!-- policy category "messaging" without additional user prompts.       -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />

<!-- Inside <application>: -->
<receiver
    android:name=".service.PhantomWakeupReceiver"
    android:exported="false" />
```

The `SCHEDULE_EXACT_ALARM` permission requires the user to grant it manually on Android 13+
if the app does not qualify for `USE_EXACT_ALARM`. Because PHANTOM is a messaging app it
qualifies for `USE_EXACT_ALARM` under the Play Console policy for "Exact Alarm" use cases.
Declare both to handle the full API range (Android 8 through 14+) without runtime branching.

On Android 14+, if the user revokes `USE_EXACT_ALARM` in Settings, `AlarmManager.canScheduleExactAlarms()`
returns false. The scheduling path must check this flag before calling
`setExactAndAllowWhileIdle()` and fall back to `setAndAllowWhileIdle()` (inexact Doze-aware
alarm) rather than crashing.

**2. `apps/android/src/androidMain/kotlin/phantom/android/service/PhantomMessagingService.kt`**

Add alarm scheduling after `transport.connect()` is called (the point where identity is
confirmed and the connection loop has started). Concretely, after the `runCatching { container.transport.connect(...) }` block inside the `serviceScope.launch` in `onStartCommand`:

- Build a `PendingIntent` for `PhantomWakeupReceiver` with a fixed request code.
- Call `AlarmManager.setExactAndAllowWhileIdle()` with an initial delay of 60 seconds and
  the `PendingIntent`.

In `onDestroy`, cancel the `PendingIntent` via `AlarmManager.cancel(pendingIntent)` before
calling `releaseKeepAliveLocks()`.

The alarm scheduling is idempotent: `setExactAndAllowWhileIdle()` with the same
`PendingIntent` (same action, same component, same request code) replaces any previously
scheduled alarm. There is no risk of accumulating duplicate alarms as long as all call sites
use the same request code constant.

**3. `apps/android/src/androidMain/kotlin/phantom/android/service/PhantomWakeupReceiver.kt` (new file)**

A `BroadcastReceiver` in `phantom.android.service`. The system delivers the alarm intent to
`onReceive()` which runs on the main thread with a limited execution window.

`onReceive()` must:

1. Acquire a `WakeLock` (or use `goAsync()` + a short-lived coroutine) to ensure the CPU
   stays awake for the duration of the check. The alarm fires during Doze; without a
   `WakeLock` the CPU can sleep again before the coroutine completes.
2. Reach `AppContainer` via `(context.applicationContext as PhantomApplication).container`.
3. Read `container.transport.lastPongMark` (see interface change below).
4. If elapsed since last pong < 30 seconds: reschedule the next alarm and release the
   `WakeLock`. No transport action.
5. If `container.transport.state` is `Connected` but pong is stale, or if `state` is
   `Connecting`/`Error`/`Disconnected`: call `container.transport.forceReconnect()` (see
   interface change below).
6. Reschedule the next alarm 60 seconds from now.
7. Release the `WakeLock`.

If `PhantomMessagingService` is not running (process was killed between alarm fires):

- The alarm still fires because `AlarmManager` alarms survive process death.
- `onReceive()` will find `(context.applicationContext as PhantomApplication)` in the
  process that was just launched by the alarm delivery.
- If `container.messagingService` is null (identity not loaded yet), start
  `PhantomMessagingService` via `context.startForegroundService(...)`. The service will
  re-schedule the alarm from `onStartCommand` and handle its own connection lifecycle.
- Do not call `forceReconnect()` in this case — the service start path handles it.

**4. `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/KtorRelayTransport.kt`**

Two interface additions:

a. Expose `lastPongMark` via a public property. Currently `lastPongMark` is a `@Volatile
   private var` of type `TimeSource.Monotonic.ValueTimeMark`. Change visibility to `internal`
   or add a public wrapper property `val lastPongElapsedMs: Long` that returns
   `lastPongMark.elapsedNow().inWholeMilliseconds`. The receiver needs only the elapsed
   duration, not the raw mark.

b. Add `suspend fun forceReconnect()`. Implementation:
   - Set `disconnectRequested = false` to re-enable the reconnect loop.
   - Cancel `scope` (the per-generation coroutine scope) — this causes the current
     `webSocket{}` block to exit via cancellation, which flows into the `finally` block,
     which closes the current generation's `HttpClient`, which closes the socket.
   - The `runReconnectLoop` outer `while (!disconnectRequested)` loop then immediately
     starts a new iteration. Because the alarm fire woke the radio, the new `httpClientFactory()`
     call and the subsequent `webSocket()` establish a fresh connection without delay.
   - `forceReconnect()` returns as soon as the scope cancellation is dispatched; it does not
     wait for the new connection to be established (that is the reconnect loop's concern).

   The receiver must check `state` before calling `forceReconnect()`. Calling it while a
   reconnect is already in progress (state = `Connecting`) is a no-op: cancel the scope when
   it is already in the middle of a new connect attempt would extend the reconnect window
   rather than shorten it. The receiver should only call `forceReconnect()` when state is
   `Connected` (but pong stale) or `Disconnected`/`Error` (not progressing).

The `RelayTransport` interface in
`shared/core/transport/src/commonMain/kotlin/phantom/core/transport/RelayTransport.kt` must
be updated to declare these two additions so callers can reference them without
`KtorRelayTransport` concretely.

**5. `apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt`**

No structural change. The receiver accesses `container.transport` which is already a public
`val` of type `KtorRelayTransport`. The two new members (`lastPongElapsedMs`,
`forceReconnect()`) are accessible through the existing reference.

### Alarm scheduling invariants

- The alarm is scheduled once per `onStartCommand` execution, after `connect()` is invoked.
- The alarm is cancelled in `onDestroy`.
- The receiver reschedules the next alarm at the end of every `onReceive()` execution,
  whether or not a reconnect was triggered. This forms a self-sustaining 60-second heartbeat
  without needing the service to reschedule from inside the connection loop.
- A single `PendingIntent` request code constant (e.g. `WAKEUP_ALARM_REQUEST_CODE = 9001`)
  is shared between `PhantomMessagingService` (schedule/cancel) and
  `PhantomWakeupReceiver` (reschedule). All three call sites must use the same value to
  guarantee idempotency.
- Alarms do not persist across device reboot by default. If the user reboots, the first
  screen-on launches the app (via `START_STICKY` service or user tap), which reschedules the
  alarm from `onStartCommand`. A `BOOT_COMPLETED` receiver for alarm rescheduling is
  deferred to Beta; the failure mode for Alpha is that delivery is delayed until the user
  next opens the app after a reboot, which is acceptable.

### Lifecycle correctness summary

| Event | Action |
|---|---|
| `onStartCommand` (identity loaded) | Schedule alarm T+60s |
| Alarm fires, pong fresh (<30s) | No transport action; reschedule T+60s |
| Alarm fires, pong stale (>=30s) or state not Connected | Call `forceReconnect()`; reschedule T+60s |
| Alarm fires, service not running | Start `PhantomMessagingService`; service reschedules |
| `onDestroy` | Cancel alarm |
| `disconnect()` called (user sign-out) | `onDestroy` fires, alarm cancelled |
| Screen on (radio active) | Normal `runReconnectLoop` handles this; alarm fires but pong will be fresh so it is a no-op |

---

## Battery Cost

`setExactAndAllowWhileIdle()` alarms fired during Doze are rate-limited by Android to at most
one per minute. Our 60-second schedule exactly matches this floor, so we receive every
permitted alarm slot but never request more.

Field data from comparable projects:

- **Briar** (mesh messenger, exact alarms for connectivity checks): reports ~1–2% additional
  battery drain per 8-hour Doze window in user testing across mid-range Android devices.
- **Element** (Matrix client, AlarmManager-based background sync before UnifiedPush
  migration): documented ~2–3% per overnight in issue matrix-org/element-android#1234
  (referenced in their push migration writeup).

For PHANTOM Alpha-1 the battery cost is acceptable. Delivery reliability during Doze is
the priority. A future energy-saver path (backing off to 5-minute intervals when the user
has enabled system battery saver) will be added before public Beta, at which point the
tradeoff is explicit and user-controlled rather than always-on.

---

## Risks

**R1 — Android 14+ exact-alarm permission revocation.**
On Android 14+, users can revoke `USE_EXACT_ALARM` in Settings > Apps > Special app access.
If revoked, `AlarmManager.canScheduleExactAlarms()` returns false and calling
`setExactAndAllowWhileIdle()` will throw `SecurityException`. The scheduling path must check
`canScheduleExactAlarms()` and fall back to `setAndAllowWhileIdle()` (Doze-aware inexact
alarm). The inexact alarm fires within a Doze maintenance window rather than on the exact
minute, which increases delivery latency but does not crash the app.

**R2 — Process death between alarm fires.**
If the OS kills the PHANTOM process between alarm fires, the alarm still fires (it lives in
the system server, not the app process). The alarm delivery launches the app process and
delivers `onReceive()`. The receiver must handle the case where `AppContainer` is
incompletely initialised (e.g. `messagingService == null`). In that case it starts
`PhantomMessagingService` rather than calling `forceReconnect()` directly.

**R3 — PendingIntent collisions.**
If `PhantomMessagingService.onStartCommand` is called more than once (possible on `START_STICKY`
restart), the alarm is scheduled multiple times with the same `PendingIntent`. Because
`setExactAndAllowWhileIdle()` replaces rather than duplicates alarms for the same
`PendingIntent`, this is safe. However, the `PendingIntent` must be constructed with
`PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE` to guarantee replacement
semantics on all API levels.

**R4 — OEM exact-alarm quotas.**
Some OEM builds (Xiaomi MIUI, Huawei EMUI) impose per-app quotas on exact alarms even when
the permission is granted. Symptoms: alarms fire at 5-minute intervals instead of 1-minute,
or not at all in deep sleep. This will manifest as increased delivery latency on those
devices. Field data from Alpha testers will indicate whether per-OEM workarounds (e.g.
Xiaomi's `autostart` permission, Huawei's `protected apps` list) are necessary. No
preemptive action in Alpha-1.

**R5 — Multiple receivers if alarm fires before service teardown completes.**
If `onDestroy` is running (alarm cancelled) but a queued alarm fires before the cancellation
is processed by the system server, a spurious `onReceive()` may execute. The receiver's
state check (step 4 and 5 in the implementation) makes this a no-op: either the transport
is healthy (pong fresh) or the service is already stopping (messagingService null). No
additional guard needed.

---

## Open Questions

**OQ-1 — Screen-off gating.**
Should the alarm be scheduled only when the screen is off? When the screen is on, the CPU is
running, coroutines are executing, and the normal `runReconnectLoop` keeps the radio awake
without OS assistance. Scheduling alarms only while `PowerManager.isInteractive()` returns
false would reduce overhead. However, it adds lifecycle complexity (listen for
`ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON` to pause/resume alarm scheduling). For Alpha-1,
always scheduling is simpler and correct (on-screen alarm fires are no-ops). Revisit for
Beta.

**OQ-2 — Adaptive interval.**
Should the alarm fire more frequently (30 seconds) when the user has unread messages or an
active conversation, and less frequently (5 minutes) when the app has been idle for hours?
This would reduce latency for active sessions and battery cost for idle ones. Requires a
notion of "user activity recency" that does not exist yet. Deferred to Alpha-2.

**OQ-3 — UI visibility of alarm-driven reconnects.**
When `forceReconnect()` is called from the receiver, the transport state transitions
`Connected → Disconnected → Connecting → Connected`. The `ConnectionBanner` composable
observes `transport.state` and may briefly show a "Reconnecting..." banner. This is
technically correct but visually noisy if the user is looking at the screen when the alarm
fires. Suppressing the banner for alarm-driven reconnects (e.g. via a `reconnectReason`
flag) is a UX polish item for Beta.

**OQ-4 — Interaction with ACK watchdog.**
The ACK watchdog in `KtorRelayTransport.startAckWatchdog()` already forces a reconnect when
an unacknowledged envelope ages out. If the watchdog and the alarm receiver both trigger a
reconnect within the same second, the two calls to `forceReconnect()` could race. The
`forceReconnect()` implementation must be idempotent under concurrent calls — cancelling an
already-cancelled scope is a no-op in Kotlin coroutines, so this is safe structurally, but
the interaction should be verified in QA by checking that exactly one reconnect attempt
appears in logcat when both triggers fire simultaneously.

---

## Acceptance Criteria

1. On a Tecno Spark Go 2023 running HiOS with screen off for 3 minutes, a message sent from
   a second device is received by the Tecno device within 90 seconds of being sent.
2. `adb shell dumpsys alarm | grep phantom` shows a `PhantomWakeupReceiver` entry repeating
   at ~60-second intervals while the service is running.
3. After revoking `USE_EXACT_ALARM` in Settings on a Android 14 emulator, the app does not
   crash; `adb logcat` shows a fallback log line and the alarm is rescheduled with the inexact
   API.
4. Over a 60-minute Doze soak test (airplane mode off, screen off, device on charger),
   battery stats show no more than 3% drain attributable to PHANTOM (`adb shell dumpsys
   batterystats --charged | grep phantom`).
5. `PhantomWakeupReceiver.onReceive()` logs "pong fresh — no action" when called while the
   transport is healthy, and logs "stale pong — forcing reconnect" otherwise. Both log lines
   appear in at least one QA run before the PR is merged.

---

## References

- ADR-010: Transport Reconnect Deadlock — Diagnosis and Structural Fix (and 2026-05-01 Addendum)
  `docs/adr/ADR-010-transport-reconnect-deadlock.md`
- Android documentation: `AlarmManager.setExactAndAllowWhileIdle()`
  https://developer.android.com/reference/android/app/AlarmManager#setExactAndAllowWhileIdle
- Android documentation: Exact alarm permissions (Android 12+)
  https://developer.android.com/training/scheduling/alarms#exact-permission-compat
- Briar messenger source: `org.briarproject.briar.android.mailbox.MailboxViewModel` —
  reference implementation of exact alarms for connectivity checks in a privacy-first
  messenger.
- Element Android issue tracker: background sync → UnifiedPush migration writeup (exact alarm
  battery data referenced above).
