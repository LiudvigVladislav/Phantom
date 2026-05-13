// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import phantom.android.ui.theme.BgDeep
import phantom.android.ui.theme.BorderSubtle
import phantom.android.ui.theme.Danger
import phantom.android.ui.theme.PhantomFontMono
import phantom.android.ui.theme.Success
import phantom.android.ui.theme.TextDim
import phantom.android.ui.theme.Warning
import phantom.core.transport.TransportState

/**
 * Thin status strip that shows WebSocket connection health. Appears below the top bar
 * on ChatListScreen. Hidden while Connected so it does not add visual noise during the
 * common case — appears only when the transport is degraded.
 *
 * Connecting is suppressed for the first DELAYED_SHOW_MS so a routine reconnect cycle
 * (well below 1 s on a good network) never flashes the banner. Once that grace period
 * elapses the banner appears so the user understands why messages are not going out.
 */
private const val DELAYED_SHOW_MS = 1_500L

@Composable
fun ConnectionBanner(
    stateFlow: State<TransportState>,
    modifier: Modifier = Modifier,
) {
    val transportState by stateFlow
    var showAfterGrace by remember { mutableStateOf(false) }
    // Suppresses the banner until we have evidence the transport has actually
    // tried to connect. Otherwise the very first frame after onboarding (or after
    // a process restart that is racing with the foreground service) renders
    // "Offline — messages queued" purely because TransportState defaults to
    // Disconnected — a false alarm the user has no action to take on.
    var hasEverConnected by remember { mutableStateOf(false) }

    // Reset the grace timer on every state change so brief Connecting blips
    // are hidden but a persistent Connecting is revealed within DELAYED_SHOW_MS.
    LaunchedEffect(transportState) {
        if (transportState is TransportState.Connecting ||
            transportState is TransportState.Connected
        ) {
            hasEverConnected = true
        }
        if (transportState is TransportState.Connected) {
            showAfterGrace = false
        } else {
            showAfterGrace = false
            delay(DELAYED_SHOW_MS)
            showAfterGrace = true
        }
    }

    val visible = transportState !is TransportState.Connected &&
        showAfterGrace &&
        hasEverConnected
    val label: String
    val dotColor: Color
    when (transportState) {
        is TransportState.Connected -> {
            label = "Online"
            dotColor = Success
        }
        is TransportState.Connecting -> {
            label = "Connecting…"
            dotColor = Warning  // canonical amber from the design palette
        }
        is TransportState.Reconnecting -> {
            // PR-H1c (2026-05-13): emitted when the in-process pong watchdog
            // / ack watchdog / sendRaw failure path notices the WS is stale
            // and forceReconnect() is in flight. Distinct from Disconnected
            // (no socket at all, possibly cold-start) and Error (terminal).
            // Outbound sends still queue and flush once the new session
            // lands — nothing is lost. Soft amber + "in flight" wording so
            // the user does not think the app is broken.
            label = "Reconnecting…"
            dotColor = Warning
        }
        is TransportState.Disconnected -> {
            label = "Offline — messages queued"
            dotColor = Danger
        }
        is TransportState.Error -> {
            label = "Offline — reconnecting"
            dotColor = Danger
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgDeep)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                text = label,
                color = TextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = PhantomFontMono,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}
