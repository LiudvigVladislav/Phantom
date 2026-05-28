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

    /**
     * PR-D1d (2026-05-17): per-envelope ACK deadline measured from enqueue
     * into pendingAcks. If the relay does not send back AckDeliver for the
     * envelope before this elapses, the WS data plane is treated as failed
     * and the orchestrator switches WS_ACTIVE → REST_ACTIVE.
     *
     * 10 s is deliberately shorter than [ACK_TIMEOUT_MS] (60 s). The ACK
     * watchdog is a session-level safety net that force-reconnects; this
     * deadline is the fast first-attempt signal that catches a bad envelope
     * on the very first WS send and hands off to REST without waiting for
     * two full session deaths (~60 s each) as required by the existing
     * active_outbound_threshold mechanism.
     *
     * The two mechanisms are orthogonal and additive — if the deadline fires,
     * the state machine switches; the existing watchdog continues its own
     * slower job independently.
     */
    const val ACK_DEADLINE_MS = 10_000L

    /**
     * PR-RECV-DIAG1 v1.6 — inbound-stall threshold. If the WS read loop
     * has not seen any Frame.Text for this duration, the idle watchdog
     * emits an `InboundStalledEvent` and the state machine transitions
     * WsActive → RestActive. 60 s is the same threshold the idle
     * watchdog already uses for its diagnostic log, so this is just
     * promoting that log into an actionable signal.
     */
    const val INBOUND_STALL_THRESHOLD_MS = 60_000L

    /**
     * PR-LTE-NETCHANGE1 (2026-05-28) — minimum interval between two
     * consecutive `TransportRewalkCoordinator` rewalk runs, used to
     * stop a chronically-flapping network interface from looping the
     * chain walker. Enforced inside the coordinator BEFORE any reset
     * work begins; on rate-limit hit the coordinator logs
     * `NETWORK_TRACE rate_limited reason=interval ageMs=<n>` and
     * returns.
     *
     * Exception (architect guardrail 3, 2026-05-28): a transition from
     * `networkPresent=false` to `networkPresent=true` ALWAYS bypasses
     * this rate-limit. If the network had genuinely disappeared and
     * just came back, suppressing the rewalk would leave us stuck on
     * stale transport decisions; that case must always re-walk the
     * chain immediately.
     */
    const val NETWORK_REWALK_MIN_INTERVAL_MS = 5_000L

    /**
     * PR-LTE-NETCHANGE1 (2026-05-28) — debounce window for the Android
     * `ConnectivityManager.NetworkCallback`. Android emits a flurry of
     * `onCapabilitiesChanged` / `onAvailable` / `onLost` callbacks over
     * ~500 ms during a single real network transition (entering a cell,
     * leaving Wi-Fi range, VPN coming up). Without debounce we would
     * fire 5–8 rewalks for one real event.
     *
     * Architect guardrail 2 (2026-05-28): after the debounce window
     * elapses, the observer reads the CURRENT `ConnectivityManager`
     * snapshot (active network + capabilities), NOT the payload of the
     * triggering callback. That avoids deciding on stale callback data
     * after Android has already settled on a different transport.
     */
    const val NETWORK_CHANGE_DEBOUNCE_MS = 1_500L

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
    // PR-RECV-DIAG1 v1.5 (Test #84.4 verdict 2026-05-27 + my own analysis):
    // re-enable app-level Ping for diagnostic isolation.
    //
    // Test #84.4 showed Tecno WS connecting successfully but `inbound_frames=0`
    // across multiple 60s sessions, even when emu sent an envelope during a
    // confirmed-active session and the relay Ack'd it as `delivered`. With
    // both OkHttp WS-Ping AND app-level Ping disabled, we have ZERO inbound
    // traffic from the relay to test the return-channel health.
    //
    // Re-enabling app-level Ping creates a probe every PING_INTERVAL_MS:
    //   - if relay's Pong reaches Tecno's reader: return-channel WORKS, so
    //     the missing Deliver frames are a relay-side delivery / routing /
    //     mailbox bug. Next diagnostic moves to relay-side logs.
    //   - if Pong does NOT reach Tecno: return-channel is broken at the
    //     network layer (Tecno Wi-Fi NAT / ISP / Cloudflare timeout).
    //     The fix is connectivity / proxy, not relay code.
    //
    // This is a diagnostic toggle — when we know which side is at fault,
    // we'll either keep app-level Ping as the production heartbeat OR
    // switch back to WS-protocol Ping with the routing fix.
    const val APP_LEVEL_PING_ENABLED = true
}
