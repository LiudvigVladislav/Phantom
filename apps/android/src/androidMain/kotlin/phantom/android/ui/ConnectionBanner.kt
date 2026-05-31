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
import phantom.android.transport.ConnectionUiState

/**
 * Thin status strip that shows the user-visible transport state. Appears below
 * the top bar on ChatListScreen. Hidden in the fully-healthy
 * [ConnectionUiState.Online] case so it does not add visual noise during the
 * common path — appears with a positive or warning label for every other state.
 *
 * PR-WS-HEALTH-STATE1 Commit 3.1 (2026-05-30): switched from raw
 * [phantom.core.transport.TransportState] to the composite presentation flow
 * [ConnectionUiState], derived in `AppContainer` from
 * `(wsTransport.state, hybridTransport.stateMachine.state)`. The
 * `RestActive`/`WsCandidate` cases now show `"Online · Limited realtime"` /
 * `"Online · Recovering"` instead of misleading `"Connecting…"` /
 * `"Offline — messages queued"`. Aligns ChatList with the notification shade
 * overlay phrasing already in production at `PhantomMessagingService.kt:256`.
 *
 * `Connecting` is still suppressed for the first DELAYED_SHOW_MS so a routine
 * reconnect cycle (well below 1 s on a good network) never flashes the banner.
 */
private const val DELAYED_SHOW_MS = 1_500L

@Composable
fun ConnectionBanner(
    stateFlow: State<ConnectionUiState>,
    modifier: Modifier = Modifier,
) {
    val uiState by stateFlow
    var showAfterGrace by remember { mutableStateOf(false) }
    // Suppresses the banner until we have evidence the transport has actually
    // tried to connect. Otherwise the very first frame after onboarding (or after
    // a process restart that is racing with the foreground service) renders
    // "Offline — messages queued" purely because the underlying TransportState
    // defaults to Disconnected — a false alarm the user has no action to take on.
    var hasEverConnected by remember { mutableStateOf(false) }

    // Reset the grace timer on every state change so brief Connecting blips
    // are hidden but a persistent Connecting is revealed within DELAYED_SHOW_MS.
    LaunchedEffect(uiState) {
        if (uiState is ConnectionUiState.Online ||
            uiState is ConnectionUiState.LimitedRealtime ||
            uiState is ConnectionUiState.Recovering ||
            uiState is ConnectionUiState.Connecting
        ) {
            hasEverConnected = true
        }
        if (uiState is ConnectionUiState.Online) {
            showAfterGrace = false
        } else {
            showAfterGrace = false
            delay(DELAYED_SHOW_MS)
            showAfterGrace = true
        }
    }

    val visible = uiState !is ConnectionUiState.Online &&
        showAfterGrace &&
        hasEverConnected
    val label: String
    val dotColor: Color
    when (uiState) {
        is ConnectionUiState.Online -> {
            label = "Online"
            dotColor = Success
        }
        is ConnectionUiState.LimitedRealtime -> {
            // PR-WS-HEALTH-STATE1 Commit 3.1: REST fallback is delivering
            // envelopes. Positive label — user is still effectively online,
            // just without realtime WS guarantees. Success dot so the row
            // does not look alarming. Phrasing matches the notification
            // shade overlay at PhantomMessagingService.kt:256.
            label = "Online · Limited realtime"
            dotColor = Success
        }
        is ConnectionUiState.Recovering -> {
            // PR-WS-HEALTH-STATE1 Commit 3.1: WS came back but RestStateMachine
            // is still in WsCandidate (60 s uptime OR outbound ACK round-trip
            // not yet observed). REST polling continues until promotion.
            // Amber so the user knows it is transitional, not fully stable.
            label = "Online · Recovering"
            dotColor = Warning
        }
        is ConnectionUiState.Connecting -> {
            label = "Connecting…"
            dotColor = Warning
        }
        is ConnectionUiState.Reconnecting -> {
            // PR-H1c (2026-05-13): emitted when the in-process pong watchdog /
            // ack watchdog / sendRaw failure path notices the WS is stale and
            // forceReconnect() is in flight. Distinct from Offline (no socket
            // at all) and Error (terminal). Outbound sends still queue and
            // flush once the new session lands — nothing is lost. Soft amber
            // + "in flight" wording so the user does not think the app is broken.
            label = "Reconnecting…"
            dotColor = Warning
        }
        is ConnectionUiState.Offline -> {
            label = "Offline — messages queued"
            dotColor = Danger
        }
        is ConnectionUiState.Error -> {
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
