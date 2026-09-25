// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.service

import android.content.Context
import phantom.android.R
import phantom.core.transport.ManagerState
import phantom.core.transport.PrivacyMode
import phantom.core.transport.TorBootstrapStage
import phantom.core.transport.TransportKind

private fun privacyModeName(context: Context, mode: PrivacyMode): String = context.getString(
    when (mode) {
        PrivacyMode.Standard -> R.string.settings_privacy_standard
        PrivacyMode.Private -> R.string.settings_privacy_private
        PrivacyMode.Ghost -> R.string.settings_privacy_ghost
    },
)

private fun torStageName(context: Context, stage: TorBootstrapStage): String = context.getString(
    when (stage) {
        TorBootstrapStage.Initial -> R.string.service_tor_initial
        TorBootstrapStage.Negotiating -> R.string.service_tor_negotiating
        TorBootstrapStage.Searching -> R.string.service_tor_searching
        TorBootstrapStage.Slow -> R.string.service_tor_slow
        TorBootstrapStage.Throttled -> R.string.service_tor_throttled
    },
)

private fun transportName(context: Context, kind: TransportKind): String = context.getString(
    when (kind) {
        TransportKind.Direct -> R.string.service_transport_direct
        TransportKind.Reality -> R.string.service_transport_reality
        TransportKind.Tor -> R.string.service_transport_tor
    },
)

internal fun foregroundTransportStatus(
    context: Context,
    state: ManagerState,
    effectiveMode: PrivacyMode,
): String {
    val mode = privacyModeName(context, effectiveMode)
    return when (state) {
        ManagerState.Idle -> context.getString(R.string.service_status_idle, mode)
        is ManagerState.Probing -> state.torStatus?.let { tor ->
            context.getString(
                R.string.service_status_tor_progress,
                torStageName(context, tor.stage),
                tor.percent,
                tor.bridgeProfile.displayName,
                tor.attempt,
                tor.totalAttempts,
                mode,
            )
        } ?: context.getString(R.string.service_status_probing, transportName(context, state.kind), mode)
        is ManagerState.Connected -> context.getString(
            R.string.service_status_connected, transportName(context, state.kind), mode,
        )
        is ManagerState.AllFailed -> if (state.attempts.any { it.kind == TransportKind.Tor }) {
            context.getString(R.string.service_status_tor_failed, mode)
        } else {
            context.getString(R.string.service_status_all_failed, state.attempts.size, mode)
        }
    }
}

internal fun foregroundRestStatus(
    context: Context,
    outerTransportName: String,
    effectiveMode: PrivacyMode,
    recovering: Boolean,
): String {
    val transport = when (outerTransportName) {
        "Direct" -> transportName(context, TransportKind.Direct)
        "Reality" -> transportName(context, TransportKind.Reality)
        "Tor" -> transportName(context, TransportKind.Tor)
        else -> context.getString(R.string.service_transport_relay)
    }
    return context.getString(
        if (recovering) R.string.service_status_verifying_realtime
        else R.string.service_status_limited_realtime,
        transport,
        privacyModeName(context, effectiveMode),
    )
}
