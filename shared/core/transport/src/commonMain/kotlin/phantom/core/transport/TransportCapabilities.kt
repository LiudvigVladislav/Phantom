// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * Snapshot of what transport actions are currently allowed.
 *
 * Produced by [TransportCapabilitiesResolver.resolve] from the current
 * [RestMode] and Tor-active flag. The Android DI ([AppContainer]) exposes
 * a [kotlinx.coroutines.flow.StateFlow] of this type derived from
 * [HybridRelayTransport.stateMachine.state] and [TorService.state].
 *
 * PR-C1 (2026-05-17): initial capability model.
 *  - [canStartCalls] true only on [RestMode.WsActive] without Tor.
 *  - [canSendVoice] true on [RestMode.WsActive], [RestMode.WsCandidate],
 *    [RestMode.RestActive] when NOT on Tor (per D2b.2 intent).
 *  - [realtimeStable] is a separate field: effectively `canStartCalls` for
 *    now, but intentionally decoupled so C2 can add a Reality-probe-pass
 *    dimension without changing this interface.
 *  - [callDisabledReason] is null iff [canStartCalls] is true.
 */
data class TransportCapabilities(
    val canSendText: Boolean,
    val canSendVoice: Boolean,
    val canStartCalls: Boolean,
    /** True only when the transport has been proven stable for realtime traffic. */
    val realtimeStable: Boolean,
    /**
     * Non-null iff [canStartCalls] is false. Names the policy reason so
     * the UI can show an honest, context-specific message.
     * Null when [canStartCalls] is true.
     */
    val callDisabledReason: CallDisabledReason?,
)

/**
 * Why call initiation is currently blocked.
 *
 * Each variant maps to a distinct user-visible message in [ChatScreen].
 * Do not add new variants without a corresponding string resource and a
 * new row in [TransportCapabilitiesResolver]'s mapping table — this enum
 * is exhaustive by design (no catch-all variant).
 */
enum class CallDisabledReason {
    /**
     * Transport is in [RestMode.RestActive] or [RestMode.WsCandidate]:
     * not yet proven stable for realtime traffic. Calls require at minimum
     * [RestMode.WsActive] without Tor.
     */
    LIMITED_REALTIME,

    /**
     * Tor is the active outer transport. Tor is text-only by policy
     * (strategic pivot 2026-05-15): its latency cannot carry WebRTC
     * signalling or audio at acceptable quality. Voice chunks are also
     * blocked on Tor.
     */
    TOR_TRANSPORT,

    /**
     * Reality candidate without a realtime probe pass. Reserved for PR-C2
     * when the Reality endpoint pool + upstream-frame / downstream-ack
     * probe is implemented. Not emitted in C1.
     */
    REALITY_UNPROBED,

    /**
     * No transport has been initialised yet (pre-bootstrap, or
     * [HybridRelayTransport] null because identity is missing). Tells the
     * user we are still connecting rather than showing a permanent error.
     */
    NO_TRANSPORT,
}

/**
 * Pure function: maps ([RestMode]?, torActive) → [TransportCapabilities].
 *
 * Lock table (PR-C1, 2026-05-17) — every cell is tested in
 * [TransportCapabilitiesResolverTest]:
 *
 * | restMode          | torActive | canSendText | canSendVoice | canStartCalls | realtimeStable | callDisabledReason |
 * |-------------------|-----------|-------------|--------------|---------------|----------------|--------------------|
 * | WsActive          | false     | true        | true         | true          | true           | null               |
 * | WsCandidate       | false     | true        | true         | false         | false          | LIMITED_REALTIME   |
 * | RestActive        | false     | true        | true         | false         | false          | LIMITED_REALTIME   |
 * | any               | true      | true        | false        | false         | false          | TOR_TRANSPORT      |
 * | null              | false     | true        | false        | false         | false          | NO_TRANSPORT       |
 *
 * Notes:
 *  - Tor trumps restMode: torActive=true applies regardless of restMode.
 *  - restMode==null means hybridTransport has not been constructed yet —
 *    no signing key, mid-migration, or very early bootstrap.
 *  - canSendText is always true: REST short-poll carries text reliably.
 *  - This resolver is intentionally a stateless object with no I/O
 *    dependencies so it can be unit-tested without coroutines.
 */
object TransportCapabilitiesResolver {

    fun resolve(
        restMode: RestMode?,
        torActive: Boolean,
        // realityProbePassed: Boolean = false,  // reserved for C2
    ): TransportCapabilities {
        // Tor trumps everything else: text-only by policy.
        if (torActive) {
            return TransportCapabilities(
                canSendText = true,
                canSendVoice = false,
                canStartCalls = false,
                realtimeStable = false,
                callDisabledReason = CallDisabledReason.TOR_TRANSPORT,
            )
        }

        return when (restMode) {
            null -> TransportCapabilities(
                canSendText = true,
                canSendVoice = false,
                canStartCalls = false,
                realtimeStable = false,
                callDisabledReason = CallDisabledReason.NO_TRANSPORT,
            )

            RestMode.WsActive -> TransportCapabilities(
                canSendText = true,
                canSendVoice = true,
                canStartCalls = true,
                realtimeStable = true,
                callDisabledReason = null,
            )

            RestMode.WsCandidate,
            RestMode.RestActive -> TransportCapabilities(
                canSendText = true,
                canSendVoice = true,
                canStartCalls = false,
                realtimeStable = false,
                callDisabledReason = CallDisabledReason.LIMITED_REALTIME,
            )
        }
    }
}
