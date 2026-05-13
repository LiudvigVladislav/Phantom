// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

object RelayTransportConfig {
    // Application-level heartbeat: client sends RelayMessage.Ping on this cadence.
    // Aggressive interval so a half-dead socket (TCP write buffer accepts the
    // frame but the wire is broken) is detected within ~25 s instead of the
    // 60-70 s the previous 20 s/50 s pair gave us.
    const val PING_INTERVAL_MS = 10_000L

    // If the relay has not emitted a Pong for this long, declare the connection
    // dead and force a reconnect. Bumped from 25 s to 60 s so a slow uplink
    // saturated by a large envelope (e.g. an 80 KB voice note over cellular on
    // an aggressive-OEM device that parks the radio) does not get torn down
    // mid-upload. App-level Ping frames are still sent every 10 s; this only
    // controls how long we tolerate silence before force-reconnecting.
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

    // Legacy constant — kept so any downstream caller that still references it
    // compiles. The reconnect loop no longer respects this value.
    @Deprecated("Use RECONNECT_INFINITE. Reconnect loop never gives up.")
    const val RECONNECT_MAX_ATTEMPTS = Int.MAX_VALUE

    // PR-H1c (2026-05-13) — proactive AlarmManager-driven reconnect.
    //
    // Test #35 confirmed the residual ~120 s pong-timeout cycle is NOT a relay
    // ping-handler bug (server pings_received and pongs_sent counters match
    // 2/2, 3/3, 5/5 across 4 sessions). Each session_summary on the server
    // shows since_last_ping_ms ≈ 155-160 s and close_origin=error with
    // "Connection reset without closing handshake". The client side
    // session_summary shows pings_sent=11 vs pongs_received=5 for the same
    // window — i.e. ~6 of 11 application-level pings never reached the
    // server. Combined with `lastInboundFrameMs` ≈ 70 000 in the client's
    // pong-timeout WARN, the picture is a half-open TCP socket: aggressive-
    // OEM radio park (Tecno HiOS) or NAT idle eviction (МТС carrier middlebox)
    // silently drops outbound packets while the OS still considers the socket
    // healthy. The relay's read-side stays parked in `recv()` for ~3 minutes
    // until kernel TCP keepalive eventually surfaces the RST.
    //
    // PR-H1c attacks this with three layers:
    //   1. TCP-level SO_KEEPALIVE with aggressive timer (15 s idle, 5 s probe,
    //      3 fail) so the OS detects dead sockets in ~30 s instead of the
    //      kernel default 2 hours. Both client and relay opt in.
    //   2. Liveness based on ANY inbound frame, not just Pong (see
    //      DEAD_SOCKET_TIMEOUT_MS below).
    //   3. PROACTIVE alarm-driven reconnect (this constant). The
    //      AlarmManager keepalive on Android wakes the radio every 30 s and
    //      currently only logs "pong fresh — no action". With ALARM_STALE_
    //      RECONNECT_MS the alarm becomes an EARLY recovery path: if no
    //      inbound frame has arrived for 45 s the alarm forces a reconnect
    //      BEFORE the in-process pong watchdog (DEAD_SOCKET_TIMEOUT_MS = 70 s)
    //      would notice. This shaves 25-40 s off recovery on Tecno HiOS where
    //      the radio-park scenario specifically delays our app-level
    //      detection.
    //
    // The 45 s threshold is deliberately well below DEAD_SOCKET_TIMEOUT_MS
    // (70 s) so the two paths are non-racing: alarm fires first (proactive),
    // pong watchdog only fires if the alarm was missed (e.g. AlarmManager
    // throttled by Doze on a never-tested OEM). 25 s of headroom prevents
    // accidental double-trigger.
    const val ALARM_STALE_RECONNECT_MS = 45_000L

    // PR-H1c: alias for PONG_TIMEOUT_MS used by the new liveness check, which
    // accepts ANY inbound frame as proof of life (not just Pong). Same value;
    // distinct name so the call sites read clearly. When the wire is healthy
    // every Deliver / Ack / Pong refreshes liveness; under Tor / Reality where
    // Pong frames may be re-routed but envelope traffic still flows the
    // existing watchdog would false-positive — this rename removes that risk
    // class from the future transport modes.
    const val DEAD_SOCKET_TIMEOUT_MS = PONG_TIMEOUT_MS

    // PR-H1c: TCP keepalive parameters applied to the WebSocket socket via a
    // custom SocketFactory on Android (extended socket options API 29+). On
    // older Android API levels we fall back to plain SO_KEEPALIVE=true with
    // OS defaults — better than nothing, much weaker than the tuned values.
    //
    // Values chosen to surface a half-open socket in ~30 s wall-clock:
    //   idle 15 s + 3 probes × 5 s = 30 s
    // Aggressive enough for mobile networks where NAT/middlebox typically
    // evict idle entries within 60-120 s. Mirrored on the relay (socket2
    // TcpKeepalive in axum bind chain).
    const val TCP_KEEPALIVE_IDLE_SECONDS = 15
    const val TCP_KEEPALIVE_INTERVAL_SECONDS = 5
    const val TCP_KEEPALIVE_COUNT = 3

    // ── PR-H1e diagnostic flags (2026-05-13 night) ────────────────────────
    //
    // Test #37 confirmed PR-H1c reduced detection 155 s → 30–46 s and
    // recovery 5 s → ~1 s with zero loss. But the underlying WS dies on
    // BOTH МТС cellular AND emulator-on-dev-Wi-Fi every 30–60 s — same
    // cycle on environments that share no carrier path. That points at
    // something inside our stack rather than pure middlebox behaviour.
    //
    // PR-H1e is a diagnostic sprint, not a fix. These flags isolate one
    // heartbeat layer at a time so a real-device test can attribute the
    // cycle of death to a specific subsystem. Defaults preserve current
    // behaviour — flipping any of them changes only diagnostic output and
    // experimental wire timing, never user-facing logic.
    //
    // Each session start logs one `transport_diag` line (see
    // KtorRelayTransport.connect) listing the live values, so logs from
    // different APK builds remain attributable after the fact.

    /**
     * Override OkHttp WebSocket-protocol Ping interval (milliseconds).
     * `null` = use the production default of 15 000 (set in
     * `RelayTransportFactory`). Set to e.g. 5_000 to test architect's
     * hypothesis that 15 s falls inside the middlebox's idle-eviction
     * window. If the cycle of death extends past 5 s × N when this is
     * lowered, the middlebox treats short bursts as activity and the
     * problem domain is "ping cadence vs middlebox idle policy". If the
     * cycle is unchanged, that hypothesis is ruled out.
     */
    val EXPERIMENTAL_WS_PING_INTERVAL_MS: Long? = null

    /**
     * If true, suppress the app-level RelayMessage.Ping/Pong loop in
     * `KtorRelayTransport.startPing`. The dead-socket watchdog inside
     * the same loop still runs (it ticks off `lastInboundFrameMark`,
     * which OkHttp WS Pong frames refresh independently). Architect's
     * hypothesis: four parallel heartbeat layers (app Ping, OkHttp WS
     * Ping, AlarmManager keepalive, TCP keepalive) may trip carrier DPI
     * or anomaly detection. Removing the noisiest layer tests this.
     */
    val EXPERIMENTAL_DISABLE_APP_PING: Boolean = false

    /**
     * If true, `PhantomWakeupReceiver` skips its proactive
     * `forceReconnect()` decision — useful in combination with the two
     * flags above to isolate which subsystem actually closes the dying
     * socket. Defaults remain the production-validated PR-H1c behaviour.
     */
    val EXPERIMENTAL_DISABLE_ALARM_RECONNECT: Boolean = false
}
