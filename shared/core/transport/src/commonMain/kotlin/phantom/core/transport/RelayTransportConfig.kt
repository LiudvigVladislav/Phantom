package phantom.core.transport

object RelayTransportConfig {
    // Application-level heartbeat: client sends RelayMessage.Ping on this cadence.
    // Kept short enough that relay-side idle timeouts (Caddy default 60s) do not
    // close the socket on quiet conversations.
    const val PING_INTERVAL_MS = 20_000L

    // If the relay has not emitted a Pong for this long, declare the connection
    // dead and force a reconnect. Tuned to roughly 2.5 * PING_INTERVAL so a single
    // dropped ping does not thrash the reconnect state machine.
    const val PONG_TIMEOUT_MS = 50_000L

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
