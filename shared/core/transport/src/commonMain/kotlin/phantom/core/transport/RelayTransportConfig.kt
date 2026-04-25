package phantom.core.transport

object RelayTransportConfig {
    // Application-level heartbeat: client sends RelayMessage.Ping on this cadence.
    // Aggressive interval so a half-dead socket (TCP write buffer accepts the
    // frame but the wire is broken) is detected within ~25 s instead of the
    // 60-70 s the previous 20 s/50 s pair gave us.
    const val PING_INTERVAL_MS = 10_000L

    // If the relay has not emitted a Pong for this long, declare the connection
    // dead and force a reconnect. ~2.5 × PING_INTERVAL_MS so a single dropped
    // ping does not thrash the reconnect state machine; first detection happens
    // on the next ping iteration after the timeout elapses.
    const val PONG_TIMEOUT_MS = 25_000L

    // How long a sent envelope may sit unacknowledged before it is treated as
    // lost in transit. The frame may have been written into a half-dead socket
    // that does not surface an exception; without this watchdog the envelope
    // would never reach the relay and never be retried. On expiry the socket
    // is closed (force-reconnect) and the envelope is re-enqueued at the head
    // of pendingOutbox so it lands first on the next session.
    const val ACK_TIMEOUT_MS = 15_000L

    // How often the watchdog scans pendingAcks for envelopes that have aged
    // past ACK_TIMEOUT_MS.
    const val ACK_WATCHDOG_INTERVAL_MS = 5_000L

    // Base delay for exponential backoff on reconnect.
    const val RECONNECT_BASE_DELAY_MS = 2_000L

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
