// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * The kind of outer transport that wraps the WebSocket. Used both as the
 * element type in a [TransportStrategy] chain and as the persisted
 * "last working transport" hint in [TransportPreferences].
 */
enum class TransportKind {
    /** Plain `wss://relay.phntm.pro/ws` over the device's default network. */
    Direct,

    /**
     * libXray-driven VLESS+REALITY tunnel to the Hetzner endpoint. The WSS
     * exits there and reaches the relay over the Hetzner-internal network.
     * Built for the TSPU 16-KB curtain that breaks Tor on Russian carriers.
     */
    Reality,

    /**
     * kmp-tor onion circuit to the relay's onion address. Slowest but the
     * only mode that hides user→relay correlation from any party except the
     * Tor consensus.
     */
    Tor,
}

/**
 * Ordered fallback chain that [TransportManager] walks. Each element is tried
 * with `PER_ATTEMPT_TIMEOUT_MS` until one succeeds; chain exhaustion returns
 * `Result.failure(NoTransportReachableException)`.
 *
 * [TOR_FIRST] has only one element by design — Ghost mode never silently
 * downgrades out of Tor.
 */
enum class TransportStrategy(val chain: List<TransportKind>) {
    DIRECT_FIRST(listOf(TransportKind.Direct, TransportKind.Reality, TransportKind.Tor)),
    REALITY_FIRST(listOf(TransportKind.Reality, TransportKind.Tor)),
    TOR_FIRST(listOf(TransportKind.Tor));

    companion object {
        fun from(mode: PrivacyMode): TransportStrategy = when (mode) {
            PrivacyMode.Standard -> DIRECT_FIRST
            PrivacyMode.Private  -> REALITY_FIRST
            PrivacyMode.Ghost    -> TOR_FIRST
        }
    }
}

/**
 * What [TransportManager.connect] returns on the success path. The `socksPort`
 * is null for [TransportKind.Direct] — direct WSS does not go through a SOCKS
 * proxy.
 */
data class ConnectedTransport(
    val kind: TransportKind,
    val socksPort: Int?,
)

/**
 * Thrown / returned when every entry in the active [TransportStrategy] chain
 * was tried and none reached the relay. The caller (foreground service) should
 * surface this to the user as a "no network paths working" UI state.
 */
class NoTransportReachableException(
    val attempts: List<TransportAttemptFailure>,
) : Exception(
    "All ${attempts.size} transport(s) failed: " +
        attempts.joinToString { "${it.kind}=${it.reason}" }
)

/** One failed entry in a chain walk. Kept for diagnostics + UI. */
data class TransportAttemptFailure(
    val kind: TransportKind,
    val reason: String,
)
