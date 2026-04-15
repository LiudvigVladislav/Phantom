package phantom.core.transport

object RelayTransportConfig {
    const val PING_INTERVAL_MS = 30_000L
    const val RECONNECT_BASE_DELAY_MS = 2_000L
    const val RECONNECT_MAX_DELAY_MS = 30_000L
    const val RECONNECT_MAX_ATTEMPTS = 5
}
