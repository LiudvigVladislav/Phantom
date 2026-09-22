// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * Bounds application-level liveness traffic to one probe per quiet window.
 *
 * OkHttp protocol pongs are not exposed to Ktor's text-frame reader, so a
 * healthy but otherwise quiet socket cannot satisfy the text-frame stall
 * detector. The probe is sent shortly before that detector fires. A returned
 * [RelayMessage.Pong] resets the reader's inbound mark and rearms this gate;
 * no response preserves the existing fail-closed stall transition.
 */
internal class IdleLivenessProbeGate {
    enum class Action { None, SendProbe, EmitStall }

    private var probeSent = false
    private var stallEmitted = false

    fun next(sinceLastInboundMs: Long): Action {
        if (sinceLastInboundMs < RelayTransportConfig.INBOUND_STALL_PROBE_THRESHOLD_MS / 2) {
            probeSent = false
            stallEmitted = false
        }

        if (!probeSent &&
            sinceLastInboundMs >= RelayTransportConfig.INBOUND_STALL_PROBE_THRESHOLD_MS &&
            sinceLastInboundMs < RelayTransportConfig.INBOUND_STALL_THRESHOLD_MS
        ) {
            probeSent = true
            return Action.SendProbe
        }

        if (!stallEmitted &&
            sinceLastInboundMs >= RelayTransportConfig.INBOUND_STALL_THRESHOLD_MS
        ) {
            stallEmitted = true
            return Action.EmitStall
        }

        return Action.None
    }
}
