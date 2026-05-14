# ADR-010: Transport Reconnect Deadlock — Diagnosis and Structural Fix

Status: Superseded by PR-H1c (#132) + PR-H1e (#134) — 2026-05-14
Date: 2026-05-01
Layer: core/transport (shared), app/service (android)

> **Note (2026-05-14).** This ADR correctly diagnosed the WebSocket-reader
> deadlock on aggressive-OEM Android (`SSLSocket.read()` parked in kernel
> `recv()` after `dispatcher.cancelAll()`). The remedy ultimately taken
> was **not** the proposed `connectionPool.evictAll()` path. Instead,
> PR-H1c (`e946caba`) and PR-H1e (`bcc501be`) shipped an alternative
> implementation: inbound-frame liveness (any frame refreshes the
> watchdog), TCP `SO_KEEPALIVE` on the relay socket, AlarmManager-driven
> proactive reconnect at 45 s, and OkHttp WS-protocol Ping locked at
> 15 s with app-level Ping disabled. Test #37 (PR-H1c) and Test #41
> (PR-H1e) verified end-to-end: detection 155 s → 30–46 s, recovery
> 5 s → ~1 s, zero message loss. The diagnosis below remains accurate;
> the "Decision" section is preserved as the proposal that was
> considered and superseded.

---

## Diagnosis

### What the code actually does when a pong timeout fires

1. `startPing` detects the dead socket and calls `forceCancelAllEngineCalls()` then `scope.cancel()`.
2. `forceCancelAllEngineCalls()` calls `sharedOkHttpClient.dispatcher.cancelAll()`.
3. `scope.cancel()` signals the per-generation coroutine scope.
4. The `webSocket{}` block is blocked inside `readLoop()`, which is a `for (frame in incoming)` loop over the Ktor/OkHttp incoming channel.

### Why `dispatcher.cancelAll()` does not unblock the reader on HiOS

The hypothesis in the brief is correct and can be confirmed from OkHttp's documented internal architecture. Once an HTTP request has been upgraded to a WebSocket, OkHttp promotes it from the dispatcher's execution queue to a `RealWebSocket` whose reader runs in a background thread permanently parked in a blocking `SSLSocket.read()` (a kernel `recv()` syscall). At that point the `Call` object is no longer in `dispatcher.runningCalls()` — OkHttp removes it immediately after the 101 Switching Protocols handshake completes. As a result, `dispatcher.cancelAll()` iterates an empty (or already-cleared) running-calls list, finds nothing to cancel, and returns without touching the reader thread.

The reader thread is only unblocked when:
- the OS-level read timeout (`readTimeout`) expires and the kernel returns `ETIMEDOUT`, or
- the underlying `Socket` is explicitly closed at the file-descriptor level.

Neither happens when `dispatcher.cancelAll()` runs. So after `forceCancelAllEngineCalls()` returns, the reader thread is still parked in `recv()`. The Ktor `webSocket{}` block awaits the reader channel; the channel never closes because the reader never exits; the `webSocket{}` block never returns. The `runReconnectLoop` outer `try` is therefore never reached and no new iteration begins.

The existing `withTimeoutOrNull(5_000)` guard in the `finally` block only wraps `generationScope.cancelAndJoin()`. It does not affect the `webSocket{}` block itself — the coroutine running `webSocket{}` is a *child* of the generation scope, so cancelling the scope propagates cancellation to it, but the Ktor `webSocket` builder catches that cancellation and waits for the OkHttp reader to exit before returning. The reader is still parked in `recv()`, so `cancelAndJoin` times out at 5 s and the log shows "generation scope cancelAndJoin timed out", yet the `webSocket{}` coroutine is still alive, still blocking the `runReconnectLoop` coroutine on which it is running. The 5 s timeout only releases `generationScope`'s *other* children (pingJob, ackWatchdog). The reconnect loop remains stuck.

The clean exit only arrives when `readTimeout` (currently 25 s) fires in the kernel, which is why users observe a 25–70 s gap rather than an immediate reconnect.

### The two competing detection paths and why they fight each other

`PONG_TIMEOUT_MS = 60_000` and `readTimeout = 25_000`. These two timers detect the same event (dead socket) via different mechanisms. Whichever fires first wins:

- If `readTimeout` fires first (25 s of kernel silence): OkHttp throws `SocketTimeoutException`; `webSocket{}` exits; `runReconnectLoop` catches it and attempts reconnect. This is the "good" path.
- If the pong watchdog fires first (up to 60 s of app-level silence): `forceCancelAllEngineCalls()` does nothing; `scope.cancel()` signals the scope but the `webSocket{}` call is not cooperatively cancellable while the reader is in kernel space; everything stalls until `readTimeout` finally fires anyway.

In practice the pong watchdog *always* fires before `readTimeout` on HiOS because HiOS parks the Wi-Fi radio, which means no frames arrive at all — so the pong watchdog trips at ~10 s + 10 s + some jitter while `readTimeout` still needs 25 s of total kernel silence. The pong watchdog takes an action that does nothing (cancel dispatcher calls that are already gone) and then leaves the system in a state where the only recovery is the slower kernel timeout.

### The real fix: close the socket's file descriptor, not the dispatcher

The only call that reliably unblocks a parked kernel `recv()` on Android is `socket.close()` or `sslSocket.close()` on the underlying `java.net.Socket`. OkHttp exposes this through `OkHttpClient.connectionPool.evictAll()`, which closes every pooled connection's socket at the file-descriptor level. A WebSocket connection's socket lives in the connection pool for the duration of the session. Calling `connectionPool.evictAll()` closes the socket, which causes the kernel to return from `recv()` with a "connection reset" or "broken pipe" error, which causes OkHttp's reader thread to exit, which causes the Ktor incoming channel to close, which causes `readLoop()`'s `for (frame in incoming)` to complete, which causes `webSocket{}` to return, which lets `runReconnectLoop` proceed to the next iteration immediately.

This is option (b) from the brief in a more targeted form: instead of recreating the full `OkHttpClient` on every reconnect, we call `connectionPool.evictAll()` on the singleton client. The cost is identical — all open connections are closed, a new TCP+TLS handshake is required for the next connect — but the client object itself (connection pool, dispatcher thread pools, SSL context) is reused.

---

## Recommended Fix

Replace `dispatcher.cancelAll()` with `connectionPool.evictAll()` inside `forceCancelAllEngineCalls()`.

```
// BEFORE
actual fun forceCancelAllEngineCalls() {
    runCatching { sharedOkHttpClient.dispatcher.cancelAll() }
}

// AFTER
actual fun forceCancelAllEngineCalls() {
    runCatching { sharedOkHttpClient.connectionPool.evictAll() }
}
```

That single-line change is the structural fix. It closes the socket at the file-descriptor level, unblocks the kernel `recv()`, and lets the reconnect loop proceed within milliseconds rather than waiting for `readTimeout`.

### Why not the other options

**(a) Lower `readTimeout` further.** APK 9 proved this breaks healthy connections. The real-network pong RTT distribution overlaps with any timeout value low enough to be useful as a dead-socket detector. This approach conflates two unrelated events (slow-but-live vs. dead) in a single timeout knob.

**(c) Move to NIO / non-blocking I/O.** Correct in principle — coroutine cancellation works properly when the suspension point is a `suspendCancellableCoroutine` backed by NIO rather than a blocking `java.io` read. But this requires replacing the Ktor+OkHttp stack, which is weeks of work and introduces new unknowns before Kickstarter.

**(d) ConnectivityManager.NetworkCallback.** Useful as a complementary signal for proactive reconnect on network transitions (roaming, Wi-Fi → LTE switch), but it does not help with the HiOS radio-parking case. HiOS parks the radio while the OS still believes the Wi-Fi connection is valid — `NetworkCallback.onLost` is not called until the connection is completely torn down, which happens long after the socket has been silently dead. This is a useful Alpha-2 hardening measure, not a fix for the current symptom.

### Timer alignment after the fix

With `connectionPool.evictAll()` working as expected, the pong watchdog becomes the *primary* dead-socket detector and `readTimeout` becomes a last-resort backstop. Set them to make that hierarchy explicit:

- `PONG_TIMEOUT_MS`: keep at `60_000` (or reduce to `30_000` if testing shows the large-envelope concern is resolved by chunking in Alpha-2).
- `readTimeout`: raise back to `60_000` (or even `90_000`) — it is now a backstop for the case where `forceCancelAllEngineCalls` itself fails (e.g., OkHttp internal API change), not a primary detection mechanism. The comment in `RelayTransportFactory` should be updated to reflect this.
- `ACK_TIMEOUT_MS`: keep at `60_000`. The ack watchdog path also calls `forceCancelAllEngineCalls`, so it benefits from the same fix.

---

## Implementation Scope

**Layer being changed:** core/transport (shared) + androidMain actual.

**Files affected:**

1. `shared/core/transport/src/androidMain/kotlin/phantom/core/transport/RelayTransportFactory.kt`
   - Change `sharedOkHttpClient.dispatcher.cancelAll()` to `sharedOkHttpClient.connectionPool.evictAll()` in `forceCancelAllEngineCalls()`.
   - Update `readTimeout` from `25` to `60` seconds and revise the comment to reflect the new role (backstop, not primary detector).
   - The `sharedOkHttpClient` singleton itself is safe across reconnect cycles after this change. `connectionPool.evictAll()` drains the pool but does not destroy the pool object or the client. The next `webSocket()` call allocates a new connection into the same pool — this is the intended lifecycle.

2. `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/RelayTransportConfig.kt`
   - No change required. Optionally reduce `PONG_TIMEOUT_MS` to `30_000` in a follow-up once the fix is proven in QA (do not bundle with the fix).

3. `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/KtorRelayTransport.kt`
   - No structural change required. The comment in the `finally` block should be updated to note that `connectionPool.evictAll()` is what actually unblocks the reader; the 5 s `cancelAndJoin` timeout is still appropriate as a guard against edge cases.

**No changes needed to:**
- `PhantomMessagingService.kt` (the double-`connect()` concern is addressed below but is a separate PR)
- `AppContainer.kt`
- Relay server code

### Contract between `KtorRelayTransport` and `RelayTransportFactory` after the fix

`forceCancelAllEngineCalls()` must guarantee: after it returns, any in-progress `webSocket{}` block on the engine will exit within a bounded time (target: under 500 ms on a device where the Wi-Fi radio is parked). Callers (`startPing`, `startAckWatchdog`) depend on this guarantee to know that the reconnect loop will make forward progress. Previously the contract was unmet. After `connectionPool.evictAll()`, the guarantee holds.

---

## Additional Issues Found

### Issue A — `sharedOkHttpClient` singleton is safe after the fix, but was unsafe before it

Before this fix, `dispatcher.cancelAll()` was a no-op on the WebSocket connection. The singleton was technically safe (no state was modified) but also ineffective. After the fix, `connectionPool.evictAll()` modifies the pool, which is safe — OkHttp's `ConnectionPool` is thread-safe and designed to have connections evicted at any time. Multiple concurrent reconnect attempts (see Issue B) could each call `evictAll()`, which is idempotent.

### Issue B — Double `connect()` is not present in the current code, but service restart on onboarding creates a brief second loop

The original brief mentioned `onCreate + onStartCommand both call connect()`. Reading the current code: `onCreate` does NOT call `connect()`. It calls `startForeground()` and `acquireKeepAliveLocks()` only. `onStartCommand` launches the coroutine that eventually calls `connect()`. There is no double-call from lifecycle methods on a single service instance.

However, there is a different problem: when onboarding completes, `MainActivity` calls `context.startForegroundService(Intent(..., PhantomMessagingService::class.java))`. If the service is already running (it was started from `MainActivity.onCreate()` and stopped itself via `stopSelf()` because no identity existed), this results in a new `onStartCommand` on the already-running service (Android does not create a second instance — it delivers `onStartCommand` again). This is harmless today because `stopSelf()` was called and the service was destroyed. But if there is any timing window where the first `onStartCommand` is still in progress when the second arrives, two `connect()` coroutines could run concurrently. Recommend adding a `@Volatile var connectStarted = false` guard inside the service, or checking `messagingService != null` before launching the connect coroutine.

### Issue C — `readLoop` sets `_state.value = TransportState.Disconnected` after it exits, racing with `runReconnectLoop` which sets `TransportState.Connecting` at the top of the next iteration

`readLoop()` ends with `_state.value = TransportState.Disconnected`. Control returns to `webSocket{}`, which returns, and then `runReconnectLoop` immediately sets `_state.value = TransportState.Connecting`. These happen in sequence on the same coroutine, so there is no true race — but it means the state briefly flashes `Disconnected` before the reconnect loop sets `Connecting`. The `ConnectionBanner` UI observes this flow. Under the current fix the reconnect will be near-instant, making the flash imperceptible. No code change needed, but worth noting if the banner ever shows incorrect state in QA.

### Issue D — `flushPendingOutbox` does not re-queue failed items

If `sendRaw` fails on any item during the post-reconnect flush, that item is logged as an error and silently dropped from the outbox. The code comment acknowledges this ("a single write failure per entry is accepted"). With the new fix this window is smaller (the new socket is genuinely healthy when flush runs), but it remains a correctness gap. Recommend adding re-enqueue-on-failure to the flush loop as a follow-up before Beta.

---

## Open Questions

1. **Verify `connectionPool.evictAll()` timing on HiOS.** This fix is grounded in documented OkHttp behaviour, but should be confirmed with a logcat trace showing that after `evictAll()` the `webSocket{}` block exits in under 500 ms on the Tecno Spark Go 2023. A log line immediately before and after `evictAll()` will confirm.

2. **Does OkHttp's `connectionPool` actually hold the WebSocket connection throughout the session?** The OkHttp source confirms this for HTTP/1.1 upgrades, which is what `wss://` over a standard Caddy reverse proxy will use. If the relay were using HTTP/2 multiplexing, the connection model would differ. Confirm that `relay.phntm.pro` is HTTP/1.1 for the WS upgrade path (should be true given the current Caddy config, but worth a one-line `curl -v --http1.1` check).

3. **Is `WIFI_MODE_FULL_HIGH_PERF` actually being granted on Tecno HiOS / Android 12?** The `WifiLock.acquire()` call is wrapped in `runCatching` and logs a warning on failure but does not stop the service. On HiOS, `WIFI_MODE_FULL_HIGH_PERF` may be silently downgraded or ignored. If radio parking still occurs after this ADR's fix is applied, the WifiLock should be investigated. Confirm with `adb shell dumpsys wifi | grep -i phantom`.

4. **`sendRaw` called on a dead session returns `true`.** During the stall window (pong timeout fired, socket is dead, reconnect has not completed), `sendRaw` calls `session?.send(Frame.Text(...))`, which calls OkHttp's `RealWebSocket.send()`. On a half-dead socket this enqueues into OkHttp's outgoing buffer and returns `true` without throwing. The ACK watchdog correctly handles this case (requeues on timeout), but the false `true` return from `sendRaw` causes `send()` to NOT re-enqueue the message in the outbox. The message is then only in `pendingAcks` and will be recovered by the ack watchdog at `ACK_TIMEOUT_MS` (60 s). This is the correct recovery path, but it means the user sees a "sending" indicator for up to 60 s even after the fix. If that is unacceptable, `sendRaw` should also return `false` when `_state.value !is TransportState.Connected` — but that change must be coordinated with the ACK watchdog to avoid double-tracking. Flag for Alpha-2.

---

## Addendum — Updated 2026-05-01: Original Recommendation Was Wrong; Option (A) Adopted

Status: superseded-in-place. The recommended fix above (`connectionPool.evictAll()`) was implemented in APK 12 and **did not fix the hang**. Real-device QA on Tecno HiOS produced this log entry:

```
forceCancelAllEngineCalls: pool connections before=2 (idle=1) → after=1 — evictAll() invoked
[3+ minutes of silence, no reconnect]
```

The `before=2, idle=1` observation is the key. `evictAll()` only closes **idle** connections — connections that are not currently leased to any caller. The WebSocket connection is an active, leased connection: OkHttp holds it open for the duration of the reader thread's lifetime. `evictAll()` therefore closes the one idle HTTP connection (likely a keep-alive from a previous prekey REST call) and leaves the live WebSocket connection completely untouched. The reconnect loop deadlock continues exactly as before.

### Why `connection.cancel()` is still not enough

OkHttp does expose `RealConnection.cancel()` through its internal API, and this does close the socket at the file-descriptor level on an active connection. However, it is not reachable from a stable public API surface. The only public path that closes an active, upgraded WebSocket connection's socket is to destroy the `OkHttpClient` that owns the connection pool the connection lives in. Calling `client.dispatcher.cancelAll()` was wrong (open question 1 above confirmed: nothing to cancel after 101 upgrade). Calling `client.connectionPool.evictAll()` was wrong (confirmed: skips non-idle connections). The correct primitive is `client.close()` (or equivalently: creating a new client per reconnect and letting the old one be garbage collected after `close()` is called on it, which tears down the dispatcher thread pool and the connection pool together).

### Why per-reconnect OkHttpClient recreation is correct

`OkHttpClient.close()` shuts down:
- The dispatcher's `ExecutorService` (no more threads are born from it)
- The `ConnectionPool`'s background cleanup thread
- Every connection in the pool, including active ones, by closing their sockets

This is the only public OkHttp API that unconditionally closes an active WebSocket connection's underlying socket. Creating a new `OkHttpClient` on each reconnect attempt and calling `close()` on the previous one is therefore the only approach that provides the bounded-time socket teardown guarantee that `forceCancelAllEngineCalls()` is supposed to offer.

The HTTP/1.1 ALPN lock added in APK 12 is correct and should be kept — it prevents OkHttp from negotiating h2 for the WebSocket upgrade (h2 multiplexed streams have different pool semantics). It is orthogonal to the fix described here.

### New contract: Option (A)

**`KtorRelayTransport` takes `httpClientFactory: () -> HttpClient` instead of `httpClient: HttpClient`.**

On each reconnect loop iteration a fresh `OkHttpClient` (wrapped in a fresh Ktor `HttpClient`) is created at the top of the `try` block. After the `webSocket{}` block exits (for any reason — clean close, exception, or cancellation), `httpClient.close()` is called in the `finally` block. This tears down the OkHttp connection pool and dispatcher for that generation, which closes the socket, which unblocks the kernel `recv()`, which lets the `webSocket{}` block return within milliseconds.

`forceCancelAllEngineCalls()` as a top-level `expect fun` is **removed**. The pong watchdog and ack watchdog no longer call it. Instead they call `generationClient.close()` directly on the per-generation client reference, which they receive via the generation scope's structured hierarchy. The bounded-teardown guarantee is now provided by the client lifecycle rather than a best-effort dispatcher/pool call.

#### Singleton fate

The `sharedOkHttpClient` singleton in `androidMain/RelayTransportFactory.kt` is **split into two**:

- **WS client**: no singleton. Created per reconnect attempt by the factory lambda. Closed in the `finally` block.
- **REST client** (used by `PreKeyApiClient`): retains a singleton-style instance. `PreKeyApiClient` is instantiated once in `AppContainer.initMessaging()` and holds its own `HttpClient` for the lifetime of the app. This client does not need the forced-teardown behaviour — REST calls are short-lived and not subject to the reader-thread park problem. It keeps the same `OkHttpClient.Builder` configuration (HTTP/1.1 ALPN, no ping, 60 s readTimeout) but is constructed independently, not shared with the WS factory.

#### Summary of changes required

| File | Change |
|---|---|
| `commonMain/RelayTransportFactory.kt` | Replace `expect fun createHttpClient(): HttpClient` with `expect fun createHttpClientFactory(): () -> HttpClient`. Remove `expect fun forceCancelAllEngineCalls()`. |
| `androidMain/RelayTransportFactory.kt` | Remove `sharedOkHttpClient` singleton. Implement `createHttpClientFactory()` returning a lambda that builds a fresh `OkHttpClient` + Ktor `HttpClient` on each call. Remove `actual fun forceCancelAllEngineCalls()`. Keep HTTP/1.1 ALPN and 60 s readTimeout in the builder. |
| `iosMain/RelayTransportFactory.kt` | Rename `createHttpClient()` to factory, remove `forceCancelAllEngineCalls()` stub. |
| `jvmMain/RelayTransportFactory.kt` | Same rename, remove stub. |
| `KtorRelayTransport.kt` | Constructor changes from `httpClient: HttpClient` to `httpClientFactory: () -> HttpClient`. `runReconnectLoop` creates `var generationClient: HttpClient? = null` at the top of each iteration. `finally` block calls `generationClient?.close()` before proceeding. Remove calls to `forceCancelAllEngineCalls()` in `startPing` and `startAckWatchdog`. Those functions call `generationClient.close()` instead (reference passed in or captured). |
| `AppContainer.kt` | Wire `transport = KtorRelayTransport(createHttpClientFactory())`. The `PreKeyApiClient` instantiation keeps its own `createHttpClient()` call (or a new `createRestHttpClient()` alias). |

#### Open questions resolved by this addendum

- Open question 1 (evictAll timing): **closed — evictAll does not close active connections**.
- Open question 2 (HTTP/1.1 vs HTTP/2 pool semantics): **closed — HTTP/1.1 lock is confirmed correct and kept; but the real fix is client recreation, not pool management**.

#### New open questions for QA after the fix

1. Does `HttpClient.close()` on the per-generation Ktor client block the calling coroutine for any meaningful duration on HiOS? If it parks the coroutine for longer than ~200 ms while the dispatcher shutdown completes, the `finally` block may need to run close on a dedicated IO dispatcher context rather than the reconnect coroutine's thread. Confirm with a timestamp log around the `close()` call.
2. Does creating one `OkHttpClient` per reconnect cycle on HiOS cause any GC pressure or memory churn visible in Profiler? Each client allocates a thread pool; on aggressive-OEM devices with constrained heap, this could be visible. Measure in APK 13 with Android Profiler during a rapid reconnect storm (simulate by toggling airplane mode 5 times in 30 s).
3. The REST client (`PreKeyApiClient`) now owns a completely independent `OkHttpClient`. Both clients will hold separate connection pools and separate dispatcher thread pools. Confirm total thread count does not exceed system limits on HiOS. Expected: 2 dispatcher threads (one per client) + 2 pool cleanup threads = 4 additional threads total. Should be fine but worth a `adb shell ps -T` check.
