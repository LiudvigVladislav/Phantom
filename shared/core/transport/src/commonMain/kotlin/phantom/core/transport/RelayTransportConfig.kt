// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

object RelayTransportConfig {
    // Application-level heartbeat: client sends RelayMessage.Ping on this cadence.
    // 10 seconds is the standard cellular-WSS sweet spot (Element/Signal use
    // ~30 s; we picked 10 to detect a half-dead socket faster). NAT-entry
    // freshness on cellular CGN is now defended at the TCP layer via
    // SO_KEEPALIVE (TCP_KEEPIDLE=30s) configured in
    // RelayTransportFactory.androidMain (Layer 2, ADR-014). The earlier 3 s
    // experiment was reverted — it broke even the Pixel emulator on a stable
    // PC, suggesting middlebox or server-side rate-limit triggered by
    // frequent small frames.
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
}
