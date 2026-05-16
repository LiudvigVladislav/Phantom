// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

object RelayTransportConfig {
    // Tick cadence for the in-process dead-socket watchdog in
    // KtorRelayTransport.startPing. Each tick checks `lastInboundFrameMark`
    // against DEAD_SOCKET_TIMEOUT_MS so a wire that has gone silent is
    // detected within roughly this interval. Since PR-H1e the same loop
    // no longer emits app-level RelayMessage.Ping frames
    // (APP_LEVEL_PING_ENABLED = false); 10 s is kept because it gives a
    // responsive watchdog without measurable CPU/wakeup overhead.
    const val PING_INTERVAL_MS = 10_000L

    // If no inbound frame has arrived from the relay for this long, declare
    // the connection dead and force a reconnect (see DEAD_SOCKET_TIMEOUT_MS
    // alias below — same value, distinct name used by the watchdog). 60 s
    // chosen so a slow uplink saturated by a large envelope (e.g. an 80 KB
    // voice note over cellular on an aggressive-OEM device that parks the
    // radio) does not get torn down mid-upload.
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

    // PR-H1c: alias for PONG_TIMEOUT_MS used by the new liveness check, which
    // accepts ANY inbound frame as proof of life (not just Pong). Same value;
    // distinct name so the call sites read clearly. When the wire is healthy
    // every Deliver / Ack / Pong refreshes liveness; under Tor / Reality where
    // Pong frames may be re-routed but envelope traffic still flows the
    // existing watchdog would false-positive — this rename removes that risk
    // class from the future transport modes.
    const val DEAD_SOCKET_TIMEOUT_MS = PONG_TIMEOUT_MS

    // PR-H1c (2026-05-13) — TCP keepalive constants declared for future
    // client-side wiring. **Currently NOT applied on client side** — client
    // liveness relies on OkHttp WS Ping (15 s) and the dead-socket watchdog
    // (DEAD_SOCKET_TIMEOUT_MS above). Server side
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
    // FIFO flush issues with the alarm path live). Only the app-level
    // Ping loop is disabled — its watchdog half (dead-socket detection
    // on `lastInboundFrameMark`) continues to tick on PING_INTERVAL_MS.
    const val APP_LEVEL_PING_ENABLED = false
}
