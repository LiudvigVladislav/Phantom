// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

object RelayTransportConfig {
    // Poll cadence for the in-process idle watchdog in
    // KtorRelayTransport.startIdleWatchdog. Since PR-H1e the loop no longer
    // emits app-level RelayMessage.Ping frames (APP_LEVEL_PING_ENABLED = false)
    // and since PR-R0.4b it no longer triggers forceReconnect — it only logs
    // a passive idle_watchdog diagnostic line at ~60 s intervals. 10 s is
    // kept so the 60 s log cadence can be achieved via a simple modulo on
    // the local lastLoggedAt mark without measurable CPU/wakeup overhead.
    const val PING_INTERVAL_MS = 10_000L

    // If no Pong reply arrives within this window after an OkHttp WS Ping, OkHttp
    // closes the socket and fires onFailure — the legitimate dead-socket trigger.
    // 60 s is intentionally generous: a slow uplink saturated by a large envelope
    // (e.g. an 80 KB voice note over cellular on an aggressive-OEM device that
    // parks the radio) must not be torn down mid-upload.
    const val PONG_TIMEOUT_MS = 60_000L

    // How long a sent envelope may sit unacknowledged before it is treated as
    // lost in transit. Bumped from 15 s to 60 s for the same reason as
    // PONG_TIMEOUT_MS — uploading a large payload on a slow link plus the
    // relay's own queueing/persistence can easily exceed 15 s before the ack
    // round-trips back to the client. On expiry the socket is closed
    // (force-reconnect) and the envelope is re-enqueued at the head of
    // pendingOutbox so it lands first on the next session.
    const val ACK_TIMEOUT_MS = 60_000L

    // How often the watchdog scans pendingAcks for envelopes that have aged
    // past ACK_TIMEOUT_MS.
    const val ACK_WATCHDOG_INTERVAL_MS = 5_000L

    // Base delay for exponential backoff on reconnect.
    // Lowered from 2 s to 1 s after QA-v6 showed every reconnect on cellular
    // costs the user ~3 s of message-delivery latency end-to-end (2 s here +
    // 1 s WS handshake). Halving this halves the user-visible reconnect gap
    // without making backoff useless when the relay is genuinely down — the
    // exponential climb still reaches 30 s within ~5 attempts.
    const val RECONNECT_BASE_DELAY_MS = 1_000L

    // Upper cap on the backoff delay. Even after a long outage the client retries
    // at most this often.
    const val RECONNECT_MAX_DELAY_MS = 30_000L

    // Messenger transport must reconnect forever. An app that gives up after N
    // attempts is useless on intermittent cellular networks. The previous limit
    // of 5 attempts was the cause of "Max retry attempts reached — giving up"
    // seen in QA on 2026-04-24.
    const val RECONNECT_INFINITE = true

    // PR-H1c (2026-05-13) — TCP keepalive constants declared for future
    // client-side wiring. **Currently NOT applied on client side** — client
    // liveness relies on OkHttp WS Ping (15 s) and its onFailure callback.
    // Server side
    // (`services/relay/src/main.rs`) does apply socket2 keepalive as
    // defence-in-depth.
    //
    // Whether to plug these constants into the client OkHttp socket via a
    // SocketFactory is an open question — the branch
    // `fix/transport-tcp-keepalive` holds a 153-line KeepAliveSocketFactory
    // candidate; the decision is deferred to Phase 1 connectivity testing
    // (if WS lifetime regresses under conditions OkHttp WS Ping + AlarmManager
    // don't cover, wire it in; otherwise drop the branch).
    //
    // Values chosen to surface a half-open socket in ~30 s wall-clock if and
    // when applied: idle 15 s + 3 probes × 5 s = 30 s.
    const val TCP_KEEPALIVE_IDLE_SECONDS = 15
    const val TCP_KEEPALIVE_INTERVAL_SECONDS = 5
    const val TCP_KEEPALIVE_COUNT = 3

    // PR-H1e (2026-05-14) — app-level Ping disabled in production.
    //
    // The diagnostic sprint isolated each heartbeat layer (app Ping,
    // OkHttp WS-protocol Ping, AlarmManager keepalive, TCP keepalive)
    // across four runs on Tecno МТС cellular + emulator-on-dev-Wi-Fi:
    //
    //   Run 0  ws_ping=15s app_ping=on  → Phone 46.5s / Emu 39.5s
    //   Run B  ws_ping= 5s app_ping=on  → Phone 21.8s / Emu 29.5s
    //   Run C  ws_ping=15s app_ping=off → Phone 72.0s / Emu 56.4s   ← winner
    //   Run BC ws_ping= 5s app_ping=off → Phone 30.9s / Emu 29.6s
    //
    // Two independent findings (both with paired controls):
    //   1. Suppressing the app-level Ping/Pong loop ≈ doubles WS lifetime.
    //   2. A 5-second OkHttp WS Ping is itself a kill trigger — its 5 s
    //      pong-window is too tight and the cadence overrides the gain
    //      from (1). 15 s is the safer cadence.
    //
    // OkHttp WS Ping stays at the production default 15 000 ms (set in
    // `RelayTransportFactory`). AlarmManager-driven proactive reconnect
    // stays enabled (Test #41 showed zero MAC errors / ledger dedup /
    // FIFO flush issues with the alarm path live). The app-level Ping
    // loop is disabled; the renamed startIdleWatchdog loop continues to
    // tick on PING_INTERVAL_MS emitting passive diagnostic logs only
    // (PR-R0.4b — no forceReconnect from the idle watchdog).
    const val APP_LEVEL_PING_ENABLED = false
}
